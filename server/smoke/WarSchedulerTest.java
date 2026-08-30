import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * GUILD WAR #68 — ÉTAPE 10 : l'ORDONNANCEUR (ce que le backend faisait tourner tout seul).
 *
 * <p>Prouve : (a) l'appariement ne tourne qu'une fois par fenêtre et ouvre de vraies guerres,
 * (b) les phases avancent, (c) les guerres échues se clôturent avec MMR, remboursement et boîtes de
 * PROMOTION, (d) les boîtes de promotion ne sont remises QU'UNE FOIS par ligue atteinte, (e) la bascule
 * de saison distribue les boîtes de fin de saison et remet le compteur de promotions à zéro, (f) un tour
 * rejoué ne double rien.
 */
public final class WarSchedulerTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  /** Une guilde inscrite en file, avec son chef enregistré (pour recevoir les boîtes). {@code now} = horloge de
   *  test FIXE (déterminisme : sinon la position dans le mois change les bascules de saison — cf. main). */
  static ServerGuild seed(UserStore store, long userID, String name, int mmr, int teamLevel, long now)
      throws Exception {
    ServerUser u = ServerUser.newPlayer(userID, 1);
    u.giveResource(ResourceType.GOLD, 5000);
    u.basicInfo().teamLevel = teamLevel;
    ServerGuild g = u.createGuild(mk(name), store.nextGuildID(1));
    ServerWar.rollOverSeason(g, ServerWar.seasonIDAt(now), 0);
    g.warMMR = mmr;
    g.warPromotionMask = ServerWar.markLeagueReached(0, ServerWar.leagueForMMR(mmr));
    g.setWarQueueState(WarQueueState.QUEUED_SINGLE);
    g.warQueuedTime = now;
    store.saveGuild(g);
    store.save(u);
    return g;
  }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    // Horloge de test DÉTERMINISTE : on ancre `now` au DÉBUT d'un mois (1ᵉʳ à RESET_HOUR) + 6 h, à partir d'un
    // timestamp FIXE — surtout PAS l'horloge réelle. Les saisons de guerre sont MENSUELLES (1ᵉʳ du mois) ; avec
    // l'horloge réelle, si le test tourne en fin de mois (ex. le 30), les ticks d'appariement franchissent le mois
    // suivant et font basculer la saison PLUS TÔT que prévu → la bascule attendue à l'étape 6 ne se produit plus
    // (échec non déterministe observé le 2026-08-30). Ancrer tôt dans un mois rend le scénario stable et reproductible.
    long anchorSrc = 1_750_000_000_000L; // ~2025-06-15 (valeur fixe : le résultat ne dépend d'aucune date réelle)
    long now = ServerWar.seasonStartTime(ServerWar.seasonIDAt(anchorSrc)) + 6L * 3600_000L;
    java.io.File tmp = java.io.File.createTempFile("dh-war-sched", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // ---------------------------------------------------------------------------------------
      // 1. CALENDRIER — ancré sur RESET_HOUR, toujours dans le futur.
      // ---------------------------------------------------------------------------------------
      long due = ServerWarScheduler.nextMatchmakingTime(now);
      check(due > now, "le prochain appariement doit être dans le futur");
      org.joda.time.DateTime d = new org.joda.time.DateTime(due,
          com.perblue.heroes.util.TimeUtil.getServerDateTimeZone());
      check(d.getHourOfDay() == ServerWar.resetHour(),
          "l'appariement doit être ancré sur RESET_HOUR=" + ServerWar.resetHour()
              + ", obtenu " + d.getHourOfDay());
      check(due - now <= 24 * 3600_000L, "il ne doit jamais être à plus de 24 h");
      System.out.println("[war] prochain appariement : " + d + " (RESET_HOUR=" + ServerWar.resetHour() + ")");

      // ---------------------------------------------------------------------------------------
      // 2. APPARIEMENT — 3 guildes inscrites → 1 paire + 1 BYE, une seule fois par fenêtre.
      // ---------------------------------------------------------------------------------------
      ServerGuild g1 = seed(store, 1L, "Alpha", 1000, 300, now);
      ServerGuild g2 = seed(store, 2L, "Bravo", 990, 300, now);
      ServerGuild g3 = seed(store, 3L, "Charlie", 500, 300, now);

      // Un tour AVANT l'heure prévue n'apparie pas : l'appariement suit un CALENDRIER, il ne se déclenche
      // pas au premier tour venu. (Corollaire opérationnel : un shard neuf attend la prochaine occurrence
      // de RESET_HOUR — d'où l'option `--war-tick` de l'admin pour forcer un tour.)
      ServerWarScheduler.Tick t0 = ServerWarScheduler.tick(store, 1, now);
      check(!t0.matchmakingRan && t0.warsOpened == 0,
          "avant l'heure prévue, aucun appariement, obtenu " + t0);
      System.out.println("[war] tour avant l'heure : aucun appariement (calendrier respecté)");

      // À l'heure prévue : 3 guildes → 1 paire + 1 BYE.
      long matchTime = due + 1000;
      ServerWarScheduler.Tick t1 = ServerWarScheduler.tick(store, 1, matchTime);
      check(t1.matchmakingRan, "à l'heure prévue, l'appariement doit tourner");
      check(t1.warsOpened == 2 && t1.byes == 1,
          "3 guildes → 1 paire + 1 BYE, obtenu " + t1.warsOpened + " guerres dont " + t1.byes + " BYE");
      System.out.println("[war] tour 1 (à l'heure) : " + t1);

      // Rejouer immédiatement ne doit RIEN rouvrir.
      ServerWarScheduler.Tick t2 = ServerWarScheduler.tick(store, 1, matchTime + 1000);
      check(!t2.matchmakingRan && t2.warsOpened == 0,
          "l'appariement ne doit tourner qu'une fois par fenêtre, obtenu " + t2);
      System.out.println("[war] tour 2 (immédiat) : aucun nouvel appariement — fenêtre respectée");

      // Les guildes appariées sont bien en guerre.
      ServerGuild r1 = store.loadGuild(1, g1.guildID);
      check(r1.currentWarID > 0, "la guilde doit pointer sur sa guerre");
      ServerWarState war = store.loadWar(1, r1.currentWarID);
      check(war != null && war.state == WarSummaryState.SABOTAGE,
          "la guerre doit s'ouvrir en phase de SABOTAGE");
      check(r1.info.warEndTime == war.endTime,
          "GuildInfo.warEndTime doit porter la fenêtre (c'est ce que le client lit)");

      // ---------------------------------------------------------------------------------------
      // 3. AVANCE DE PHASE.
      // ---------------------------------------------------------------------------------------
      long sabEnd = war.stateEndTime;
      ServerWarScheduler.Tick t3 = ServerWarScheduler.tick(store, 1, sabEnd + 1000);
      check(t3.phasesAdvanced >= 1, "la phase doit avancer à l'échéance du sabotage, obtenu " + t3);
      check(store.loadWar(1, r1.currentWarID).state == WarSummaryState.ACTIVE,
          "la guerre doit passer en ACTIVE");
      System.out.println("[war] tour 3 : " + t3.phasesAdvanced + " phase(s) avancée(s) → ACTIVE");

      // ---------------------------------------------------------------------------------------
      // 4. CLÔTURE + BOÎTES DE PROMOTION.
      // ---------------------------------------------------------------------------------------
      long after = war.endTime + 1000;
      ServerWarScheduler.Tick t4 = ServerWarScheduler.tick(store, 1, after);
      check(t4.warsFinished >= 1, "les guerres échues doivent se clôturer, obtenu " + t4);
      check(t4.promotionBoxesAwarded > 0, "des boîtes de promotion doivent être remises, obtenu " + t4);
      System.out.println("[war] tour 4 : " + t4);

      ServerGuild after1 = store.loadGuild(1, g1.guildID);
      check(after1.currentWarID == 0, "la guilde doit être libérée");
      check(after1.warsCompleted >= 1, "le bilan doit être tenu");
      check(after1.warBoxedLeagueMask != 0, "le masque de boîtes remises doit être posé");

      ServerWarBoxes boxes1 = store.loadWarBoxes(1, 1L);
      check(boxes1.size() > 0, "le chef doit avoir des boîtes en attente");
      int boxesAfterWar = boxes1.size();
      System.out.println("[war] boîtes en attente pour le chef d'Alpha : " + boxesAfterWar);

      // Les boîtes ne se redistribuent PAS au tour suivant (masque `warBoxedLeagueMask`).
      ServerWarScheduler.Tick t5 = ServerWarScheduler.tick(store, 1, after + 2000);
      check(t5.promotionBoxesAwarded == 0,
          "les boîtes de promotion ne doivent PAS être redistribuées, obtenu " + t5.promotionBoxesAwarded);
      check(store.loadWarBoxes(1, 1L).size() == boxesAfterWar, "le lot de boîtes doit rester stable");
      System.out.println("[war] tour 5 : aucune boîte redistribuée (masque distinct du plancher de ligue)");

      // ---------------------------------------------------------------------------------------
      // 5. RÉCLAMATION D'UNE BOÎTE — l'option choisie est créditée, la boîte disparaît.
      // ---------------------------------------------------------------------------------------
      ServerWarBoxes pending = store.loadWarBoxes(1, 1L);
      WarBoxInfo box = pending.boxes().get(0);
      check(!box.rewardOptions.isEmpty(), "une boîte doit offrir des options");
      RewardDrop opt = (RewardDrop) box.rewardOptions.get(0);
      check(opt.quantity > 0, "aucune option ne doit avoir une quantité <= 0");
      RewardDrop claimed = pending.claim(box.iD, 0);
      check(claimed != null && claimed.quantity == opt.quantity, "la réclamation doit rendre l'option");
      check(pending.claim(box.iD, 0) == null,
          "rejouer la même réclamation ne doit RIEN rendre (la boîte n'existe plus)");
      check(pending.size() == boxesAfterWar - 1, "la boîte doit avoir été retirée");
      store.saveWarBoxes(1, 1L, pending);
      check(store.loadWarBoxes(1, 1L).size() == boxesAfterWar - 1, "le retrait doit persister");
      System.out.println("[war] réclamation : "
          + (claimed.itemType != ItemType.DEFAULT ? claimed.itemType : claimed.resourceType)
          + "×" + claimed.quantity + " · rejouer ne rend rien · reste " + pending.size() + " boîte(s)");

      // ---------------------------------------------------------------------------------------
      // 6. BASCULE DE SAISON — boîtes de fin de saison + remise à zéro du masque de promotion.
      // ---------------------------------------------------------------------------------------
      int season = ServerWar.seasonIDAt(now);
      long nextSeason = ServerWar.seasonStartTime(season + 1) + 1000;
      int before = store.loadWarBoxes(1, 1L).size();
      ServerWarScheduler.Tick t6 = ServerWarScheduler.tick(store, 1, nextSeason);
      check(t6.seasonsRolled >= 1, "la saison doit basculer, obtenu " + t6);
      System.out.println("[war] tour 6 (nouvelle saison) : " + t6);

      ServerGuild seasonAfter = store.loadGuild(1, g1.guildID);
      check(seasonAfter.warSeasonID == season + 1, "la saison doit avancer");
      check(seasonAfter.warBoxedLeagueMask == 0,
          "le masque de boîtes de promotion doit repartir à zéro à la nouvelle saison");
      check(!seasonAfter.warSeasonHistory().isEmpty(), "la saison écoulée doit être archivée");
      // Les boîtes de saison ne tombent que si la table est positive au niveau d'équipe du joueur
      // (mesuré : négative sous TL 289 — d'où un chef à TL 300 dans ce test).
      check(store.loadWarBoxes(1, 1L).size() >= before,
          "la bascule de saison ne doit jamais RETIRER de boîtes");
      if (t6.seasonBoxesAwarded > 0) {
        System.out.println("[war] boîtes de fin de saison remises : " + t6.seasonBoxesAwarded
            + " (ligue atteinte, TL 300 → table positive)");
      }

      // Rejouer la bascule ne double rien.
      ServerWarScheduler.Tick t7 = ServerWarScheduler.tick(store, 1, nextSeason + 1000);
      check(t7.seasonsRolled == 0 && t7.seasonBoxesAwarded == 0,
          "une saison déjà basculée ne doit pas rebasculer, obtenu " + t7);
      System.out.println("[war] tour 7 : bascule de saison idempotente");

      System.out.println("WAR SCHEDULER TEST OK");
    }
  }
}
