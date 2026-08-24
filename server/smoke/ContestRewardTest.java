import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.ContestHelper;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST incr.4 (réclamation des PALIERS) — les {@code progressRewards} sont livrés par COURRIER dès que les points
 * requis sont atteints (wiki : livraison immédiate), une seule fois (idempotent, {@code earnedProgressRewards}).
 *
 * <p>Contest BATTLE_WON=10 pts, palier[0]=20 pts → ACE_OF_SPADES×5. 1 combat=10 (pas de palier) ; 2ᵉ=20 → COURRIER livré ;
 * 3ᵉ=30 → PAS re-livré (déjà gagné). Le tout via la logique du jeu (getRewards) + `deliverMail` (§3).
 */
public final class ContestRewardTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestreward] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  static int contestMails(ServerUser su) {
    int n = 0;
    BootData bd = su.bootData();
    if (bd.mailMessages != null) for (Object o : bd.mailMessages) {
      MailMessage mm = (MailMessage) o;
      if ("Contest Reward".equals(mm.subject)) n++;
    }
    return n;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 960_070L;
    ServerUser su = ServerUser.newPlayer(9891L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(20L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();
    com.perblue.heroes.game.objects.User user = su.gameUser();

    // 1 combat = 10 pts → sous le palier (20) → aucun courrier de contest.
    ServerContestData.record(su, user, u -> ContestHelper.onCampaignAttack(u, GameMode.CAMPAIGN, CombatOutcome.WIN, new ArrayList<>(), new ArrayList<>()));
    check(ServerContestData.getContestData(su, cid).progressPoints == 10, "10 pts après 1 combat");
    check(contestMails(su) == 0, "aucun courrier de palier sous 20 pts (" + contestMails(su) + ")");
    System.out.println("[contestreward] 10 pts : 0 courrier (palier 20 non atteint) ✔");

    // 2ᵉ combat = 20 pts → palier atteint → COURRIER livré 1×.
    ServerContestData.record(su, user, u -> ContestHelper.onCampaignAttack(u, GameMode.CAMPAIGN, CombatOutcome.WIN, new ArrayList<>(), new ArrayList<>()));
    check(ServerContestData.getContestData(su, cid).progressPoints == 20, "20 pts après 2 combats");
    check(contestMails(su) == 1, "palier 20 → 1 courrier de contest (" + contestMails(su) + ")");
    check(ServerContestData.getContestData(su, cid).extraData.earnedProgressRewards.contains(Integer.valueOf(0)), "palier 0 marqué gagné");
    System.out.println("[contestreward] 20 pts : palier livré (1 courrier « Contest Reward ») + marqué gagné ✔");

    // 3ᵉ combat = 30 pts → palier DÉJÀ gagné → PAS re-livré.
    ServerContestData.record(su, user, u -> ContestHelper.onCampaignAttack(u, GameMode.CAMPAIGN, CombatOutcome.WIN, new ArrayList<>(), new ArrayList<>()));
    check(contestMails(su) == 1, "idempotent : toujours 1 courrier après 30 pts (" + contestMails(su) + ")");
    System.out.println("[contestreward] 30 pts : palier NON re-livré (idempotent) ✔");

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestreward] OK — progressRewards livrés par courrier au palier, idempotents. [incr.4 headless]");
  }
}
