import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST incr.4 (CLASSEMENT) — le rang est SERVEUR-AUTORITATIF, calculé sur un ladder per-(shard, contestID) persisté
 * dans {@code shard_state} (patron {@code arena_ladder}, §5). {@code GetAllContestData} met à jour les points du joueur dans
 * le ladder et calcule {@code rank} (1 + nb strictement au-dessus) + {@code totalParticipants}.
 *
 * <p>2 joueurs : A=500 pts, B=300 pts. A → rang 1/1 ; B → rang 2/2 (A devant) ; re-A → rang 1/2.
 */
public final class ContestRankTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestrank] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 960_080L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();

    java.io.File tmp = java.io.File.createTempFile("dh-contest-rank", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser A = ServerUser.newPlayer(100L, 1);
      ServerUser B = ServerUser.newPlayer(200L, 1);

      ServerContext.bind(A.gameUser(), A.gameUser().getIndividual());
      ServerContestData.getContestData(A, cid).rankPoints = 500L;
      AllContestData ra = ServerContestData.response(A, store);
      ContestData ca = (ContestData) ra.contests.get(cid);
      check(ca.rank == 1 && ca.totalParticipants == 1, "A seul : rang 1/1 (" + ca.rank + "/" + ca.totalParticipants + ")");
      System.out.println("[contestrank] A (500 pts) seul : rang " + ca.rank + "/" + ca.totalParticipants + " ✔");

      ServerContext.bind(B.gameUser(), B.gameUser().getIndividual());
      ServerContestData.getContestData(B, cid).rankPoints = 300L;
      AllContestData rb = ServerContestData.response(B, store);
      ContestData cb = (ContestData) rb.contests.get(cid);
      check(cb.rank == 2 && cb.totalParticipants == 2, "B derrière A : rang 2/2 (" + cb.rank + "/" + cb.totalParticipants + ")");
      System.out.println("[contestrank] B (300 pts) : rang " + cb.rank + "/" + cb.totalParticipants + " ✔");

      ServerContext.bind(A.gameUser(), A.gameUser().getIndividual());
      AllContestData ra2 = ServerContestData.response(A, store);
      ContestData ca2 = (ContestData) ra2.contests.get(cid);
      check(ca2.rank == 1 && ca2.totalParticipants == 2, "A reste 1er sur 2 (" + ca2.rank + "/" + ca2.totalParticipants + ")");
      System.out.println("[contestrank] A re-classé : rang " + ca2.rank + "/" + ca2.totalParticipants + " ✔");
    }

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestrank] OK — classement serveur-autoritatif (ladder per-shard), rang + participants. [incr.4 headless]");
  }
}
