import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * GUILD WAR #68 — ÉTAPE 6 : clôture d'une guerre (issue, MMR, ligue) et génération des boîtes.
 *
 * <p>Prouve : (a) rien ne se clôture avant l'échéance, (b) l'issue vient des totaux et les deux variations
 * de MMR se calculent sur les MMR d'AVANT (échange symétrique), (c) le plancher de ligue de la saison monte
 * mais ne redescend pas, (d) la clôture est IDEMPOTENTE, (e) un BYE encaisse {@code BYE_RATING_GAIN} sans
 * adversaire, (f) les guildes sont libérées et tout persiste, (g) les boîtes sortent des tables du jeu et
 * ne contiennent JAMAIS de quantité négative — le point sensible mesuré ici.
 */
public final class WarEndTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  static WarMemberInfo member(long userID, WarCarType car, int lineups, boolean allDefeated) {
    WarMemberInfo m = new WarMemberInfo();
    BasicUserInfo bi = new BasicUserInfo();
    bi.iD = userID; bi.name = "U" + userID; bi.teamLevel = 45;
    m.userInfo = bi; m.assignedCar = car;
    for (int i = 0; i < lineups; i++) {
      WarLineupSummary l = new WarLineupSummary();
      for (int h = 0; h < 5; h++) {
        WarHeroSummary hs = new WarHeroSummary();
        hs.defeated = allDefeated; hs.sabotage = WarSabotageType.DEFAULT;
        l.heroes.add(hs);
      }
      m.defenses.add(l);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  static void addMember(WarGuildInfo side, WarMemberInfo m) { side.members.put(m.userInfo.iD, m); }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-war-end", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      int season = ServerWar.seasonIDAt(now);
      ServerUser ra = ServerUser.newPlayer(1L, 1); ra.giveResource(ResourceType.GOLD, 5000);
      ServerGuild ga = ra.createGuild(mk("Vainqueurs"), store.nextGuildID(1));
      ServerUser rb = ServerUser.newPlayer(2L, 1); rb.giveResource(ResourceType.GOLD, 5000);
      ServerGuild gb = rb.createGuild(mk("Perdants"), store.nextGuildID(1));
      ServerWar.rollOverSeason(ga, season, 0);
      ServerWar.rollOverSeason(gb, season, 0);
      // MMR de départ distincts pour que l'Elo ait quelque chose à dire.
      ga.warMMR = 900; gb.warMMR = 1100;
      ga.warPromotionMask = ServerWar.markLeagueReached(0, ServerWar.leagueForMMR(ga.warMMR));
      gb.warPromotionMask = ServerWar.markLeagueReached(0, ServerWar.leagueForMMR(gb.warMMR));
      store.saveGuild(ga); store.saveGuild(gb);
      store.save(ra); store.save(rb);   // les comptes doivent exister pour être remboursables

      // Guerre finie sur le papier : A a tout pris chez B, B n'a rien pris chez A.
      ServerWarState w = new ServerWarState();
      w.shardID = 1; w.seasonID = season;
      w.startTime = now - ServerWarMatchmaker.warDuration();
      w.endTime = now;
      w.state = WarSummaryState.ACTIVE; w.stateEndTime = w.endTime;
      w.guildAID = ga.guildID; w.guildBID = gb.guildID;

      WarGuildInfo sideA = new WarGuildInfo(); sideA.guildInfo = ga.info.basicInfo;
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        addMember(sideA, member(100L + i, ServerWarCars.GARAGE_ORDER.get(i), 3, false));  // A tient tout
      }
      ServerWarCars.rebuildCars(sideA);
      WarGuildInfo sideB = new WarGuildInfo(); sideB.guildInfo = gb.info.basicInfo;
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        addMember(sideB, member(200L + i, ServerWarCars.GARAGE_ORDER.get(i), 3, true));   // B est laminé
      }
      ServerWarCars.rebuildCars(sideB);
      w.putSide(ga.guildID, sideA); w.putSide(gb.guildID, sideB);
      ServerWarScoring.refreshTotalPoints(w, ga.guildID);
      ServerWarScoring.refreshTotalPoints(w, gb.guildID);
      store.saveWar(w);

      // ---------------------------------------------------------------------------------------
      // 1. RIEN AVANT L'ÉCHÉANCE.
      // ---------------------------------------------------------------------------------------
      check(ServerWarEnd.finishWar(store, w, ga, gb, w.endTime - 1) == null,
          "une guerre ne se clôture pas avant son échéance");
      check(!ServerWarEnd.isFinished(w), "elle doit rester en cours");

      // ---------------------------------------------------------------------------------------
      // 2. CLÔTURE — issue depuis les totaux, MMR calculés sur les valeurs d'AVANT.
      // ---------------------------------------------------------------------------------------
      int mmrABefore = ga.warMMR, mmrBBefore = gb.warMMR;
      int expectedA = ServerWar.ratingChange(mmrABefore, mmrBBefore, WarSummaryState.VICTORY);
      int expectedB = ServerWar.ratingChange(mmrBBefore, mmrABefore, WarSummaryState.DEFEAT);

      ServerWarEnd.Result r = ServerWarEnd.finishWar(store, w, ga, gb, now);
      check(r != null && !r.alreadyFinished, "la clôture doit avoir lieu à l'échéance");
      check(r.pointsA > r.pointsB, "A doit mener (" + r.pointsA + " vs " + r.pointsB + ")");
      check(r.outcomeA == WarSummaryState.VICTORY && r.outcomeB == WarSummaryState.DEFEAT,
          "issues attendues VICTORY/DEFEAT, obtenu " + r.outcomeA + "/" + r.outcomeB);
      check(r.mmrDeltaA == expectedA && r.mmrDeltaB == expectedB,
          "les deux deltas doivent être calculés sur les MMR d'AVANT (attendu " + expectedA + "/"
              + expectedB + ", obtenu " + r.mmrDeltaA + "/" + r.mmrDeltaB + ")");
      check(ga.warMMR == mmrABefore + expectedA, "le MMR de A doit être appliqué");
      check(gb.warMMR == mmrBBefore + expectedB, "le MMR de B doit être appliqué");
      check(ga.warsWon == 1 && gb.warsLost == 1, "le bilan de saison doit être tenu");
      System.out.println("[war] clôture : A " + r.outcomeA + " " + r.pointsA + " pts (MMR " + mmrABefore
          + " → " + ga.warMMR + ") · B " + r.outcomeB + " " + r.pointsB + " pts (MMR " + mmrBBefore
          + " → " + gb.warMMR + ")");

      // Le camp porte le MMR et le delta affichés par le client.
      WarGuildInfo endA = w.sideOf(ga.guildID);
      check(endA.mmr == ga.warMMR && endA.mmrDelta == expectedA, "le camp doit porter MMR et delta");
      check(w.toSummary(ga.guildID).yourMmrDelta == expectedA, "le résumé doit porter le delta de A");
      check(w.toSummary(gb.guildID).yourMmrDelta == expectedB, "le résumé doit porter le delta de B");

      // ---------------------------------------------------------------------------------------
      // 3. PLANCHER DE LIGUE — il monte, il ne redescend pas dans la saison.
      // ---------------------------------------------------------------------------------------
      check(ServerWar.highestLeagueReached(ga.warPromotionMask).ordinal()
              >= ServerWar.leagueForMMR(mmrABefore).ordinal(),
          "la ligue atteinte ne peut pas régresser");
      check(endA.league == ServerWar.effectiveLeague(ga.warMMR, ga.warPromotionMask),
          "la ligue du camp doit tenir compte du plancher");
      // B a perdu du MMR : sa ligue effective ne doit PAS descendre sous celle déjà atteinte.
      WarGuildInfo endB = w.sideOf(gb.guildID);
      check(endB.league.ordinal() >= ServerWar.leagueForMMR(gb.warMMR).ordinal(),
          "B ne doit pas être rétrogradé sous la ligue déjà atteinte cette saison");
      System.out.println("[war] ligues : A " + endA.league + " · B " + endB.league
          + " (MMR brut " + gb.warMMR + " = " + ServerWar.leagueForMMR(gb.warMMR) + ")");

      // ---------------------------------------------------------------------------------------
      // 4. LIBÉRATION + IDEMPOTENCE + PERSISTANCE.
      // ---------------------------------------------------------------------------------------
      check(ga.currentWarID == 0 && gb.currentWarID == 0, "les guildes doivent être libérées");
      check(ServerWarEnd.isFinished(w), "la guerre doit être marquée terminée");
      int mmrAfter = ga.warMMR, wonAfter = ga.warsWon;
      ServerWarEnd.Result again = ServerWarEnd.finishWar(store, w, ga, gb, now + 1000);
      check(again != null && again.alreadyFinished, "une 2e clôture doit être signalée comme déjà faite");
      check(ga.warMMR == mmrAfter && ga.warsWon == wonAfter,
          "une 2e clôture ne doit RIEN recompter (idempotence)");
      System.out.println("[war] idempotence : 2e clôture sans effet (MMR " + ga.warMMR
          + ", " + ga.warsWon + " victoire)");

      ServerWarState rw = store.loadWar(1, w.warID);
      ServerGuild rga = store.loadGuild(1, ga.guildID);
      check(ServerWarEnd.isFinished(rw) && rw.state == WarSummaryState.VICTORY, "l'issue doit persister");
      check(rw.sideOf(ga.guildID).mmrDelta == expectedA, "le delta doit persister");
      check(rga.warMMR == mmrAfter && rga.currentWarID == 0 && rga.warsWon == 1,
          "l'état de guilde doit persister");
      System.out.println("[war] round-trip DB : issue, delta et bilan persistés");

      // ---------------------------------------------------------------------------------------
      // 4bis. REMBOURSEMENT DES SABOTAGES AU PERDANT.
      // « Tokens spent are refunded if you lose the War » — et c'est CELUI QUI A PAYÉ qui récupère.
      // ---------------------------------------------------------------------------------------
      // On rejoue une guerre courte où B (le perdant) a dépensé en sabotages.
      ServerWarState w2 = new ServerWarState();
      w2.shardID = 1; w2.seasonID = season;
      w2.startTime = now; w2.endTime = now + 1;
      w2.state = WarSummaryState.ACTIVE; w2.stateEndTime = w2.endTime;
      w2.guildAID = ga.guildID; w2.guildBID = gb.guildID;
      WarGuildInfo s2a = new WarGuildInfo(); s2a.guildInfo = ga.info.basicInfo;
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        addMember(s2a, member(300L + i, ServerWarCars.GARAGE_ORDER.get(i), 3, false));
      }
      ServerWarCars.rebuildCars(s2a);
      WarGuildInfo s2b = new WarGuildInfo();
      s2b.guildInfo = gb.info.basicInfo;
      s2b.sabotageCurrency = ServerWarSabotage.DEFAULT_SABOTAGE_CURRENCY;
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        addMember(s2b, member(400L + i, ServerWarCars.GARAGE_ORDER.get(i), 3, true));
      }
      ServerWarCars.rebuildCars(s2b);
      w2.putSide(ga.guildID, s2a); w2.putSide(gb.guildID, s2b);
      // Le joueur 2 (chef de B) a dépensé 300 en sabotages.
      w2.addSabotageFee(gb.guildID, rb.userID, 200);
      w2.addSabotageFee(gb.guildID, rb.userID, 100);
      store.saveWar(w2);

      long tokensBefore = rb.resourceAmount(ResourceType.WAR_TOKENS);
      ServerWarEnd.Result r2 = ServerWarEnd.finishWar(store, w2, ga, gb, now + 2);
      check(r2 != null && r2.outcomeB == WarSummaryState.DEFEAT, "B doit perdre cette 2e guerre");
      check(r2.refunds.size() == 1 && r2.refunds.get(rb.userID) == 300,
          "le perdant doit se voir rembourser 300, imputés au joueur qui a payé, obtenu " + r2.refunds);
      check(r2.refundCurrency == ServerWarSabotage.DEFAULT_SABOTAGE_CURRENCY,
          "la monnaie de remboursement doit être celle du camp");
      int credited = ServerWarEnd.creditRefunds(store, 1, r2);
      check(credited == 1, "un joueur doit être remboursé, obtenu " + credited);
      ServerUser rbAfter = store.loadIfExists(rb.userID, 1);
      check(rbAfter.resourceAmount(ResourceType.WAR_TOKENS) == tokensBefore + 300,
          "le remboursement doit être crédité et persisté (attendu " + (tokensBefore + 300) + ", obtenu "
              + rbAfter.resourceAmount(ResourceType.WAR_TOKENS) + ")");
      System.out.println("[war] remboursement au perdant : 300 " + r2.refundCurrency
          + " rendus au joueur " + rb.userID + " (celui qui avait payé)");
      // Le VAINQUEUR n'est pas remboursé.
      check(w2.totalSabotageFees(ga.guildID) == 0 && !r2.refunds.containsKey(ra.userID),
          "le vainqueur ne doit rien récupérer");

      // ---------------------------------------------------------------------------------------
      // 5. BYE — gain fixe, sans adversaire.
      // ---------------------------------------------------------------------------------------
      ServerUser rc = ServerUser.newPlayer(3L, 1); rc.giveResource(ResourceType.GOLD, 5000);
      ServerGuild gc = rc.createGuild(mk("Solitaire"), store.nextGuildID(1));
      ServerWar.rollOverSeason(gc, season, 0);
      int mmrCBefore = ServerWar.currentMMR(gc);
      ServerWarState bye = new ServerWarState();
      bye.shardID = 1; bye.seasonID = season;
      bye.startTime = now - ServerWarMatchmaker.warDuration(); bye.endTime = now;
      bye.state = WarSummaryState.SABOTAGE;
      bye.guildAID = gc.guildID; bye.guildBID = 0;
      WarGuildInfo sideC = new WarGuildInfo(); sideC.guildInfo = gc.info.basicInfo;
      ServerWarCars.rebuildCars(sideC);
      bye.putSide(gc.guildID, sideC);
      gc.currentWarID = 1234L;
      store.saveWar(bye);

      ServerWarEnd.Result byeR = ServerWarEnd.finishWar(store, bye, gc, null, now);
      check(byeR != null && byeR.outcomeA == WarSummaryState.BYE, "un BYE doit se clore en BYE");
      check(byeR.mmrDeltaA == ServerWar.byeRatingGain(),
          "un BYE rapporte exactement BYE_RATING_GAIN, obtenu " + byeR.mmrDeltaA);
      check(gc.warMMR == mmrCBefore + ServerWar.byeRatingGain(), "le gain de BYE doit être appliqué");
      check(gc.currentWarID == 0, "la guilde doit être libérée après un BYE");
      System.out.println("[war] BYE : MMR " + mmrCBefore + " → " + gc.warMMR + " (+"
          + ServerWar.byeRatingGain() + ")");

      // ---------------------------------------------------------------------------------------
      // 6. BOÎTES — issues des tables du jeu, JAMAIS de quantité négative.
      // ---------------------------------------------------------------------------------------
      // Fait mesuré : la variable des expressions est L = NIVEAU D'ÉQUIPE. Les lignes de PROMOTION sont
      // protégées par des max(…,1) ; celles de SAISON ne le sont PAS et deviennent négatives sous un
      // niveau élevé. On vérifie les deux comportements ET le garde-fou.
      int promoCount = 0;
      for (WarLeague lg : WarLeague.values()) {
        if (lg == WarLeague.UNRANKED) continue;
        for (int L : new int[]{1, 45, 100, 565}) {
          java.util.List<WarBoxInfo> boxes = ServerWarEnd.promotionBoxes(lg, L, season, now, 1L);
          check(!boxes.isEmpty(), "une promotion en " + lg + " doit donner des boîtes (TL " + L + ")");
          for (WarBoxInfo b : boxes) {
            check(!b.rewardOptions.isEmpty(), "une boîte doit offrir au moins une option");
            for (Object o : b.rewardOptions) {
              check(((RewardDrop) o).quantity > 0,
                  "aucune quantité <= 0 ne doit sortir d'une boîte (" + lg + " TL " + L + ")");
            }
            promoCount++;
          }
        }
      }
      check(ServerWarEnd.promotionBoxes(WarLeague.GOLD, 45, season, now, 1L).size()
              == com.perblue.heroes.game.data.war.WarStats.getNumPromotionBoxes(),
          "le nombre de boîtes de promotion doit valoir NUM_PROMOTION_BOXES");
      System.out.println("[war] boîtes de promotion : " + promoCount
          + " vérifiées sur 7 ligues × 4 niveaux, aucune quantité <= 0");

      // Le garde-fou est RÉELLEMENT exercé : à bas niveau, la table de saison est négative.
      java.util.List<?> rawLow = com.perblue.heroes.game.data.war.WarStats.getSeasonRewardsPreview(
          WarLeague.GOLD, 1, 45, now);
      int negatives = 0;
      for (Object opt : rawLow) {
        for (Object o : (java.util.List<?>) opt) if (((RewardDrop) o).quantity <= 0) negatives++;
      }
      check(negatives > 0,
          "à TL 45 la table de SAISON doit produire des quantités négatives (fait mesuré) — sinon le "
              + "garde-fou ne servirait à rien et cette assertion doit être revue");
      java.util.List<WarBoxInfo> lowSeason = ServerWarEnd.seasonBoxes(WarLeague.GOLD, 1, 45, season, now, 1L);
      for (WarBoxInfo b : lowSeason) {
        for (Object o : b.rewardOptions) {
          check(((RewardDrop) o).quantity > 0, "le garde-fou doit écarter les quantités <= 0");
        }
      }
      System.out.println("[war] table de SAISON à TL 45 : " + negatives
          + " quantités <= 0 dans les données → écartées (jamais retirer de ressources au joueur)");

      // À haut niveau, la même table devient positive et la boîte est réellement garnie.
      java.util.List<WarBoxInfo> highSeason = ServerWarEnd.seasonBoxes(WarLeague.GOLD, 1, 565, season, now, 1L);
      check(!highSeason.isEmpty() && !highSeason.get(0).rewardOptions.isEmpty(),
          "à haut niveau d'équipe, la boîte de saison doit être garnie");
      StringBuilder sb = new StringBuilder();
      for (Object o : highSeason.get(0).rewardOptions) {
        RewardDrop d = (RewardDrop) o;
        sb.append(d.itemType != ItemType.DEFAULT ? d.itemType : d.resourceType).append("×")
          .append(d.quantity).append(" ");
      }
      System.out.println("[war] table de SAISON à TL 565 : " + sb.toString().trim());

      System.out.println("WAR END TEST OK");
    }
  }
}
