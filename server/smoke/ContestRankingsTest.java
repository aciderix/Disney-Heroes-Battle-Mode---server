import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST gap B (AFFICHAGE DU SCORE) — l'écran CONTESTS lit le « Score »/« Rank » de l'OVERVIEW solo depuis
 * {@code ContestRankings.yourInfo} (un {@code ContestRankingRow}), renvoyé par l'action {@code GET_CONTEST_RANKINGS}
 * (bytecode {@code ContestsOverviewContent}/{@code ContestsScreen} : branche solo → {@code yourInfo.points}/{@code .rank}).
 * Avant ce correctif, le handler renvoyait les points de CONTEST DE GUILDE (0 en solo) → « Score: 0 » malgré le crédit.
 *
 * <p>Ici on prouve {@link ServerContestData#soloRankings} (que le handler {@code GET_CONTEST_RANKINGS} appelle pour un
 * contest SOLO actif) : après crédit de points, {@code yourInfo.points} = les points crédités et {@code yourInfo.rank} est
 * cohérent avec le ladder serveur-autoritatif (départage horodaté).
 */
public final class ContestRankingsTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestrankings] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 970_090L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();

    java.io.File tmp = java.io.File.createTempFile("dh-contest-rankings", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser A = ServerUser.newPlayer(700L, 1);
      ServerUser B = ServerUser.newPlayer(800L, 1);

      // --- Cas 1 : joueur A sans aucun point → Score 0, rang 1/1 (aucune ligne fantôme, yourInfo présent) ---
      ServerContext.bind(A.gameUser(), A.gameUser().getIndividual());
      ContestRankings r0 = ServerContestData.soloRankings(store, A, cid);
      check(r0.yourInfo != null, "yourInfo non nul même à 0 point");
      check(r0.yourInfo.points == 0L, "A à 0 point : Score=0 (=" + r0.yourInfo.points + ")");
      check(r0.yourInfo.rank == 1 && r0.topPlayers.size() == 1, "A seul : rang 1/1 (" + r0.yourInfo.rank + ", top=" + r0.topPlayers.size() + ")");
      System.out.println("[contestrankings] A à 0 pt : Score " + r0.yourInfo.points + " rang " + r0.yourInfo.rank + " ✔");

      // --- Cas 2 : A crédite 30 pts (3 BATTLE_WON) via le VRAI hook du jeu → le Score de l'overview doit valoir 30 ---
      for (int i = 0; i < 3; i++) {
        ServerContestData.record(A, A.gameUser(), u ->
            com.perblue.heroes.game.logic.ContestHelper.onCampaignAttack(
                u, com.perblue.heroes.network.messages.GameMode.CAMPAIGN,
                com.perblue.heroes.network.messages.CombatOutcome.WIN,
                new java.util.ArrayList<>(), new java.util.ArrayList<>()));
      }
      store.save(A);
      ContestRankings r1 = ServerContestData.soloRankings(store, A, cid);
      check(r1.yourInfo.points == 30L, "A après 3 BATTLE_WON (10 pts ch.) : Score=30 (=" + r1.yourInfo.points + ")");
      check(r1.yourInfo.rank == 1, "A rang 1 (=" + r1.yourInfo.rank + ")");
      System.out.println("[contestrankings] A après 3 combats gagnés : Score " + r1.yourInfo.points + " rang " + r1.yourInfo.rank + " ✔");

      // --- Cas 3 : B arrive plus tard avec 10 pts → A (30) reste 1er, B 2e ; leaderboard à 2 joueurs, yourInfo par joueur ---
      long t0 = System.nanoTime(); while (System.nanoTime() - t0 < 3_000_000L) { /* ~3ms pour horodatage distinct */ }
      ServerContext.bind(B.gameUser(), B.gameUser().getIndividual());
      ServerContestData.record(B, B.gameUser(), u ->
          com.perblue.heroes.game.logic.ContestHelper.onCampaignAttack(
              u, com.perblue.heroes.network.messages.GameMode.CAMPAIGN,
              com.perblue.heroes.network.messages.CombatOutcome.WIN,
              new java.util.ArrayList<>(), new java.util.ArrayList<>()));
      store.save(B);
      ContestRankings rb = ServerContestData.soloRankings(store, B, cid);
      check(rb.yourInfo.points == 10L && rb.yourInfo.rank == 2, "B : Score 10 rang 2 (" + rb.yourInfo.points + "/" + rb.yourInfo.rank + ")");
      check(rb.topPlayers.size() == 2, "leaderboard à 2 joueurs (" + rb.topPlayers.size() + ")");
      check(((ContestRankingRow) rb.topPlayers.get(0)).points == 30L && ((ContestRankingRow) rb.topPlayers.get(1)).points == 10L, "top trié décroissant (30 puis 10)");
      ServerContext.bind(A.gameUser(), A.gameUser().getIndividual());
      ContestRankings ra = ServerContestData.soloRankings(store, A, cid);
      check(ra.yourInfo.points == 30L && ra.yourInfo.rank == 1, "A reste 1er (30 pts) parmi 2 (" + ra.yourInfo.points + "/" + ra.yourInfo.rank + ")");
      check(ra.topPlayers.size() == 2, "A voit 2 joueurs (" + ra.topPlayers.size() + ")");
      System.out.println("[contestrankings] 2 joueurs : A Score 30 rang 1, B Score 10 rang 2 ✔");

      // --- Cas 4 : ROUND-TRIP WIRE du message ContestRankings (le client doit pouvoir le lire) ---
      WireCheck.assertRoundTrips(ra);
      System.out.println("[contestrankings] round-trip wire ContestRankings OK ✔");
    }

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestrankings] OK — GET_CONTEST_RANKINGS solo → yourInfo.points = score crédité (overview Score/Rank corrects). [gap B headless]");
  }
}
