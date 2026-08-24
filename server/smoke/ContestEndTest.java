import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST incr.4 (CLÔTURE / rankRewards) — à la fin d'un contest, {@code ServerContestData.distributeRankRewards} livre les
 * récompenses de RANG par COURRIER selon le rang FINAL (ladder per-shard). Tiers : rang 1 (NUMBER) → ACE×100 ;
 * top 100 % (PERCENT) → ACE×10. A(500 pts)=rang 1 → ACE×100 ; B(300)=rang 2 (100 %) → ACE×10.
 */
public final class ContestEndTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestend] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  static MailMessage rankMail(ServerUser su) {
    BootData bd = su.bootData();
    if (bd.mailMessages != null) for (Object o : bd.mailMessages) {
      MailMessage mm = (MailMessage) o;
      if ("Contest Rank Reward".equals(mm.subject)) return mm;
    }
    return null;
  }
  static long attachQty(MailMessage mm) {
    if (mm == null || mm.extra == null || mm.extra.attachments == null || mm.extra.attachments.isEmpty()) return -1;
    return ((RewardDrop) mm.extra.attachments.get(0)).quantity;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 960_090L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    // Tiers de rang : le plus EXCLUSIF d'abord (rang 1 NUMBER), puis top 100 % (PERCENT).
    List<ServerEvents.ContestRank> ranks = Arrays.asList(
        new ServerEvents.ContestRank(false, 1, Collections.singletonList(item("ACE_OF_SPADES", 100))),
        new ServerEvents.ContestRank(true, 100, Collections.singletonList(item("ACE_OF_SPADES", 10))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();

    java.io.File tmp = java.io.File.createTempFile("dh-contest-end", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser A = ServerUser.newPlayer(100L, 1), B = ServerUser.newPlayer(200L, 1);
      store.save(A); store.save(B);
      ServerContext.bind(A.gameUser(), A.gameUser().getIndividual());
      ServerContestData.getContestData(A, cid).rankPoints = 500L; ServerContestData.response(A, store); store.save(A);
      ServerContext.bind(B.gameUser(), B.gameUser().getIndividual());
      ServerContestData.getContestData(B, cid).rankPoints = 300L; ServerContestData.response(B, store); store.save(B);

      int n = ServerContestData.distributeRankRewards(store, 1, cid, ev);
      check(n == 2, "2 joueurs récompensés (" + n + ")");

      ServerUser rA = store.loadIfExists(100L, 1), rB = store.loadIfExists(200L, 1);
      long qa = attachQty(rankMail(rA)), qb = attachQty(rankMail(rB));
      check(qa == 100, "A (rang 1) → rankReward ACE×100 (" + qa + ")");
      check(qb == 10, "B (rang 2, top 100%) → rankReward ACE×10 (" + qb + ")");
      System.out.println("[contestend] A rang 1 → ×" + qa + " | B rang 2 (100%) → ×" + qb + " ✔");
    }

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestend] OK — rankRewards distribués par rang final au courrier. [incr.4 headless]");
  }
}
