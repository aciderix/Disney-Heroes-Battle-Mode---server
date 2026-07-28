import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.ServerWar;
import dhserver.ServerWarState;
import dhserver.UserStore;

/**
 * GUILD WAR #68 — ÉTAPE 2 : état de guerre persisté + file d'attente.
 *
 * <p>Prouve : (a) une GUERRE est un état SYMÉTRIQUE dont les deux guildes tirent deux vues cohérentes
 * ({@code WarInfo.yourGuild}/{@code enemyGuild} échangés), (b) elle survit au round-trip SQLite avec les
 * objets DU JEU en octets wire (aucun schéma inventé), (c) elle est retrouvable par guilde, (d) la file
 * d'attente ré-exécute les contrôles de rôle et de déblocage du client, (e) l'état de guerre de la guilde
 * (MMR, plancher de ligue, adversaires récents, réglages) persiste en v8, et (f) la bascule de saison
 * archive le bilan et re-sème le MMR.
 */
public final class WarStateTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-war-state", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // Deux guildes, chacune avec son chef.
      ServerUser rulerA = ServerUser.newPlayer(1L, 1);
      rulerA.giveResource(ResourceType.GOLD, 5000);
      // Les deux identifiants sont alloués AVANT tout enregistrement — c'est exactement la situation qui
      // faisait collisionner deux créations concurrentes (défaut trouvé par ce test, corrigé depuis :
      // `nextGuildID` ALLOUE sous verrou au lieu de simplement lire MAX+1).
      ServerGuild ga = rulerA.createGuild(mk("Alpha Legion"), store.nextGuildID(1));
      ServerUser rulerB = ServerUser.newPlayer(2L, 1);
      rulerB.giveResource(ResourceType.GOLD, 5000);
      ServerGuild gb = rulerB.createGuild(mk("Bravo Legion"), store.nextGuildID(1));
      check(ga.guildID != gb.guildID,
          "deux allocations SANS enregistrement intercalé doivent différer (obtenu " + ga.guildID
              + " et " + gb.guildID + ")");
      store.saveGuild(ga); store.saveGuild(gb);
      store.save(rulerA); store.save(rulerB);
      // Et l'allocation ne recule jamais : le compteur reprend au-delà du MAX déjà enregistré.
      check(store.nextGuildID(1) > Math.max(ga.guildID, gb.guildID), "l'allocation doit être monotone");
      System.out.println("[war] guildes " + ga.guildID + " (" + ga.info.basicInfo.name + ") et "
          + gb.guildID + " (" + gb.info.basicInfo.name + ")");

      // ---------------------------------------------------------------------------------------
      // 1. AMORÇAGE DU MMR — une guilde neuve part de STARTING_MMR, pas de 0.
      // ---------------------------------------------------------------------------------------
      check(ServerWar.currentMMR(ga) == ServerWar.startingMMR(),
          "une guilde neuve doit partir à STARTING_MMR, obtenu " + ServerWar.currentMMR(ga));
      int season = ServerWar.seasonIDAt(now);
      check(ServerWar.rollOverSeason(ga, season, 0), "la 1re bascule doit amorcer la guilde");
      check(!ServerWar.rollOverSeason(ga, season, 0), "rejouer la même saison doit être un no-op (idempotent)");
      ServerWar.rollOverSeason(gb, season, 0);
      check(ga.warSeasonID == season && ga.warMMR == ServerWar.startingMMR(), "amorçage incorrect");
      System.out.println("[war] amorçage : MMR " + ga.warMMR + " saison " + ga.warSeasonID
          + " ligue " + ServerWar.leagueForMMR(ga.warMMR));

      // ---------------------------------------------------------------------------------------
      // 2. FILE D'ATTENTE — contrôles du client ré-exécutés.
      // ---------------------------------------------------------------------------------------
      // Un simple MEMBRE ne peut pas inscrire la guilde (canQueueForWar).
      ServerUser member = ServerUser.newPlayer(3L, 1);
      member.joinGuildAs(ga.guildID, GuildRole.MEMBER);
      ga.memberIDs.add(3L);
      String err = ServerWar.changeQueueState(ga, member, WarQueueState.QUEUED_SINGLE, now);
      check(err != null, "un MEMBER ne doit pas pouvoir inscrire la guilde en guerre");
      System.out.println("[war] MEMBER → refusé (" + err + ")");

      // Le chef le peut, si le mode est débloqué. Le compte neuf est TL1 → gate Unlockable.WAR (TL45).
      int reqTL = com.perblue.heroes.game.data.misc.Unlockables.getTeamLevelReq(
          com.perblue.heroes.game.data.misc.Unlockable.WAR, rulerA.gameUser());
      err = ServerWar.changeQueueState(ga, rulerA, WarQueueState.QUEUED_SINGLE, now);
      check(err != null, "à TL1 le gate de déblocage (TL" + reqTL + ") doit refuser l'inscription");
      System.out.println("[war] RULER TL1 → refusé par le gate du jeu (WAR = TL" + reqTL + ")");

      // On amène les deux chefs au niveau requis (état joueur LÉGITIME — le gate du jeu s'exécute et
      // devient satisfait, on ne le désactive pas ; même mécanisme que l'outil DEV SetTeamLevel).
      rulerA.basicInfo().teamLevel = reqTL;
      rulerB.basicInfo().teamLevel = reqTL;
      check(com.perblue.heroes.game.data.misc.Unlockables.isUnlocked(
          com.perblue.heroes.game.data.misc.Unlockable.WAR, rulerA.gameUser()), "WAR devrait être débloqué");

      err = ServerWar.changeQueueState(ga, rulerA, WarQueueState.QUEUED_SINGLE, now);
      check(err == null, "le chef au bon niveau doit pouvoir inscrire : " + err);
      check(ServerWar.isQueued(ga) && ga.warQueuedTime == now, "l'inscription doit être horodatée");
      err = ServerWar.changeQueueState(gb, rulerB, WarQueueState.QUEUED_PERSISTENT, now + 1);
      check(err == null, "inscription persistante refusée : " + err);
      System.out.println("[war] file : A=" + ga.warQueueState + " B=" + gb.warQueueState);

      // Après appariement : simple → sort de la file ; persistante → y reste.
      check(ServerWar.queueStateAfterMatch(WarQueueState.QUEUED_SINGLE) == WarQueueState.NOT_QUEUED,
          "une inscription SIMPLE doit sortir de la file après appariement");
      check(ServerWar.queueStateAfterMatch(WarQueueState.QUEUED_PERSISTENT) == WarQueueState.QUEUED_PERSISTENT,
          "une inscription PERSISTANTE doit y rester");

      // ---------------------------------------------------------------------------------------
      // 3. LA GUERRE — état symétrique, deux vues cohérentes.
      // ---------------------------------------------------------------------------------------
      ServerWarState w = new ServerWarState();
      w.warID = store.nextWarID(1);
      w.shardID = 1;
      w.seasonID = season;
      w.startTime = now;
      w.endTime = now + 2 * 86_400_000L;
      w.state = WarSummaryState.SABOTAGE;
      w.stateEndTime = now + com.perblue.heroes.game.data.war.WarStats.getSabotagePhaseLength();
      w.extraStateEndTime = now + com.perblue.heroes.game.data.war.WarStats.getSabotageBanPhaseLenght();
      w.guildAID = ga.guildID;
      w.guildBID = gb.guildID;

      WarGuildInfo sideA = new WarGuildInfo();
      sideA.guildInfo = ga.info.basicInfo;
      sideA.league = ServerWar.leagueForMMR(ga.warMMR);
      sideA.mmr = ga.warMMR;
      sideA.totalPoints = 137;
      WarGuildInfo sideB = new WarGuildInfo();
      sideB.guildInfo = gb.info.basicInfo;
      sideB.league = ServerWar.leagueForMMR(gb.warMMR);
      sideB.mmr = gb.warMMR;
      sideB.totalPoints = 42;
      w.putSide(ga.guildID, sideA);
      w.putSide(gb.guildID, sideB);

      check(w.involves(ga.guildID) && w.involves(gb.guildID), "les deux guildes doivent être dans la guerre");
      check(w.opponentOf(ga.guildID) == gb.guildID && w.opponentOf(gb.guildID) == ga.guildID,
          "l'adversaire doit être symétrique");
      check(!w.isBye(), "une guerre à deux guildes n'est pas un BYE");

      // La vue de A et la vue de B sont MIROIR l'une de l'autre.
      WarInfo viewA = w.toWarInfo(ga.guildID);
      WarInfo viewB = w.toWarInfo(gb.guildID);
      check(viewA.yourGuild.totalPoints == 137 && viewA.enemyGuild.totalPoints == 42,
          "vue de A incorrecte : " + viewA.yourGuild.totalPoints + "/" + viewA.enemyGuild.totalPoints);
      check(viewB.yourGuild.totalPoints == 42 && viewB.enemyGuild.totalPoints == 137,
          "vue de B incorrecte : " + viewB.yourGuild.totalPoints + "/" + viewB.enemyGuild.totalPoints);
      check(viewA.warID == viewB.warID && viewA.state == viewB.state && viewA.endTime == viewB.endTime,
          "les scalaires de la guerre doivent être identiques des deux côtés");
      System.out.println("[war] vues miroir OK : A voit " + viewA.yourGuild.totalPoints + " vs "
          + viewA.enemyGuild.totalPoints + " · B voit " + viewB.yourGuild.totalPoints + " vs "
          + viewB.enemyGuild.totalPoints);

      // Une guilde étrangère ne peut pas être écrite dans la guerre.
      boolean threw = false;
      try { w.putSide(9999L, new WarGuildInfo()); } catch (IllegalArgumentException ex) { threw = true; }
      check(threw, "écrire le camp d'une guilde étrangère doit lever");

      // ---------------------------------------------------------------------------------------
      // 4. ROUND-TRIP SQLITE de la guerre (octets wire des objets du jeu).
      // ---------------------------------------------------------------------------------------
      store.saveWar(w);
      ServerWarState rw = store.loadWar(1, w.warID);
      check(rw != null, "la guerre doit être relue");
      check(rw.warID == w.warID && rw.seasonID == season && rw.state == WarSummaryState.SABOTAGE,
          "scalaires non persistés");
      check(rw.stateEndTime == w.stateEndTime && rw.extraStateEndTime == w.extraStateEndTime,
          "les échéances de phase doivent persister");
      check(rw.sideOf(ga.guildID).totalPoints == 137 && rw.sideOf(gb.guildID).totalPoints == 42,
          "les WarGuildInfo doivent survivre au round-trip wire");
      check(rw.sideOf(ga.guildID).guildInfo != null
              && ga.info.basicInfo.name.equals(rw.sideOf(ga.guildID).guildInfo.name),
          "l'identité de guilde doit survivre au round-trip");
      System.out.println("[war] round-trip DB de la guerre OK (guerre #" + rw.warID + ", "
          + rw.sideOf(ga.guildID).guildInfo.name + " vs " + rw.sideOf(gb.guildID).guildInfo.name + ")");

      // Retrouvable par guilde, des DEUX côtés.
      check(store.listWarsForGuild(1, ga.guildID, 10).size() == 1, "guerre introuvable côté A");
      check(store.listWarsForGuild(1, gb.guildID, 10).size() == 1, "guerre introuvable côté B");
      check(store.listWarsForGuild(1, 9999L, 10).isEmpty(), "une guilde étrangère ne doit voir aucune guerre");

      // Résumé (élément de WarsList) : l'ennemi vu depuis chaque camp.
      WarSummary sa = rw.toSummary(ga.guildID);
      check(sa.enemyGuild != null && gb.info.basicInfo.name.equals(sa.enemyGuild.name),
          "le résumé de A doit désigner B comme ennemi");
      System.out.println("[war] résumé côté A : ennemi=" + sa.enemyGuild.name + " état=" + sa.state);

      // BYE = guerre sans adversaire.
      ServerWarState bye = new ServerWarState();
      bye.warID = 999; bye.shardID = 1; bye.guildAID = ga.guildID; bye.guildBID = 0;
      bye.state = WarSummaryState.BYE;
      bye.putSide(ga.guildID, sideA);
      check(bye.isBye() && bye.opponentOf(ga.guildID) == 0, "un BYE n'a pas d'adversaire");
      check(bye.toWarInfo(ga.guildID).enemyGuild != null, "la vue d'un BYE doit rester exploitable (camp vide)");

      // ---------------------------------------------------------------------------------------
      // 5. ROUND-TRIP de l'état de guerre DE LA GUILDE (ServerGuild v8).
      // ---------------------------------------------------------------------------------------
      ga.currentWarID = w.warID;
      ga.rememberWarOpponent(gb.guildID, ServerWar.maxPreviousWars());
      ga.warExtraAttackRank = GuildRole.MEMBER;      // le chef ouvre les attaques bonus à tous
      ServerWar.applyWarResult(ga, ServerWar.ratingChange(ga.warMMR, gb.warMMR, WarSummaryState.VICTORY),
          WarSummaryState.VICTORY);
      int mmrAfterWin = ga.warMMR;
      check(mmrAfterWin > ServerWar.startingMMR(), "une victoire doit faire monter le MMR de la guilde");
      check(ga.warsWon == 1 && ga.warsCompleted == 1 && ga.warsLost == 0, "bilan de saison incorrect");
      store.saveGuild(ga);

      ServerGuild rg = store.loadGuild(1, ga.guildID);
      check(rg.warQueueState == ga.warQueueState, "l'état de file doit persister");
      check(rg.warMMR == mmrAfterWin && rg.warSeasonID == season, "le MMR et la saison doivent persister");
      check(rg.warPromotionMask == ga.warPromotionMask, "le plancher de ligue doit persister");
      check(rg.currentWarID == w.warID, "la guerre en cours doit persister");
      check(rg.warExtraAttackRank == GuildRole.MEMBER, "le réglage d'attaques bonus doit persister");
      check(rg.warsWon == 1 && rg.warsCompleted == 1, "le bilan doit persister");
      check(rg.previousWarOpponents.size() == 1 && rg.previousWarOpponents.get(0) == gb.guildID,
          "l'historique d'adversaires doit persister");
      System.out.println("[war] round-trip DB de la guilde (v8) OK : MMR " + rg.warMMR
          + " ligue " + ServerWar.leagueForMMR(rg.warMMR) + " bilan " + rg.warsWon + "V/" + rg.warsLost + "D");

      // Anti-rematch : le plus récent en tête, sans doublon, borné.
      for (int i = 0; i < ServerWar.maxPreviousWars() + 5; i++) {
        rg.rememberWarOpponent(1000L + i, ServerWar.maxPreviousWars());
      }
      check(rg.previousWarOpponents.size() == ServerWar.maxPreviousWars(),
          "l'historique doit être borné à MAX_PREVIOUS_WARS (" + ServerWar.maxPreviousWars()
              + "), obtenu " + rg.previousWarOpponents.size());
      long last = 1000L + ServerWar.maxPreviousWars() + 4;
      check(rg.warsSinceOpponent(last) == 0, "le dernier adversaire doit être en tête");
      check(rg.warsSinceOpponent(gb.guildID) < 0, "un adversaire évincé ne doit plus être connu");
      rg.rememberWarOpponent(last, ServerWar.maxPreviousWars());
      check(rg.previousWarOpponents.size() == ServerWar.maxPreviousWars(),
          "ré-affronter le même adversaire ne doit pas créer de doublon");
      System.out.println("[war] anti-rematch : historique borné à " + rg.previousWarOpponents.size()
          + " (MAX_PREVIOUS_WARS), sans doublon");

      // ---------------------------------------------------------------------------------------
      // 6. BASCULE DE SAISON — archive le bilan, re-sème le MMR, remet les compteurs à zéro.
      // ---------------------------------------------------------------------------------------
      int before = ga.warMMR, wonBefore = ga.warsWon;
      check(ServerWar.rollOverSeason(ga, season + 1, 1), "la bascule de saison doit avoir lieu");
      check(ga.warSeasonID == season + 1, "la saison doit avancer");
      check(ga.warsWon == 0 && ga.warsLost == 0 && ga.warsCompleted == 0, "les compteurs doivent repartir à zéro");
      check(ServerWar.leagueForMMR(ga.warMMR) == WarLeague.GOLD,
          "le rang 1 doit repartir en GOLD, obtenu " + ServerWar.leagueForMMR(ga.warMMR));
      check(ga.warSeasonHistoryWire.size() == 1, "la saison écoulée doit être archivée");
      java.util.List<WarSeasonSummary> hist = ga.warSeasonHistory();
      check(hist.size() == 1 && hist.get(0).seasonID == season,
          "l'archive doit porter la saison écoulée, obtenu " + (hist.isEmpty() ? "rien" : hist.get(0).seasonID));
      check(hist.get(0).warsWon == wonBefore && hist.get(0).mMR == before,
          "l'archive doit porter le bilan et le MMR de fin de saison");
      System.out.println("[war] bascule de saison " + season + "→" + (season + 1) + " : MMR " + before
          + " → " + ga.warMMR + " (" + ServerWar.leagueForMMR(ga.warMMR) + "), archive "
          + hist.get(0).warsWon + "V @ MMR " + hist.get(0).mMR);

      // L'archive survit au round-trip DB (octets wire de l'objet du jeu).
      store.saveGuild(ga);
      ServerGuild rg2 = store.loadGuild(1, ga.guildID);
      java.util.List<WarSeasonSummary> hist2 = rg2.warSeasonHistory();
      check(hist2.size() == 1 && hist2.get(0).seasonID == season && hist2.get(0).mMR == before,
          "l'historique de saisons doit survivre au round-trip DB");
      check(rg2.warSeasonID == season + 1 && rg2.warMMR == ga.warMMR, "le nouvel état de saison doit persister");
      System.out.println("[war] historique de saisons persisté (WarSeasonSummary du jeu, octets wire)");

      System.out.println("WAR STATE TEST OK");
    }
  }
}
