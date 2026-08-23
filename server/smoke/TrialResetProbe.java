import com.perblue.heroes.network.messages.*;
import dhserver.*;

/** DEV probe (paramétré/diagnostic, EXCLU de la régression) : valeurs de reset d'un franchise trial construit. */
public final class TrialResetProbe {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 991001L;
    ServerUser su = ServerUser.newPlayer(8801L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    com.perblue.heroes.game.objects.User u = su.gameUser();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    com.perblue.common.specialevent.SpecialEventInfo info =
        ServerEvents.buildFranchiseTrialEvent(EV, now - 1000L, now + 30L * 86400000L);
    TrialEventData blob = ServerTrials.getData(su, EV);
    com.perblue.heroes.game.objects.trials.ClientEventTrial t =
        new com.perblue.heroes.game.objects.trials.ClientEventTrial(u, info);
    t.setUserData(blob);
    com.perblue.heroes.game.specialevent.TrialEventInfo ti =
        (com.perblue.heroes.game.specialevent.TrialEventInfo) info.getComponent(
            com.perblue.heroes.game.specialevent.TrialEventInfo.class);
    System.out.println("[probe] getChancesPerReset=" + ti.getChancesPerReset() + " getMaxDailyResets=" + ti.getMaxDailyResets());
    System.out.println("[probe] chancesRemaining=" + t.getChancesRemaining()
        + " dailyResetsRemaining=" + t.getDailyResetsRemaining() + " maxDailyResets=" + t.getMaxDailyResets());
    System.out.println("[probe] paidResetsRemaining=" + t.getPaidResetsRemaining()
        + " maxPaidResets=" + t.getMaxPaidResets() + " paidDailyResetsRemaining=" + t.getPaidDailyResetsRemaining()
        + " paidChancesRemaining=" + t.getPaidChancesRemaining());
    System.out.println("[probe] getResourceCostType=" + t.getResourceCostType() + " getResetCost(1)=" + t.getResetCost(1)
        + " getResetCost(200)=" + t.getResetCost(200) + " canUseResetItems=" + t.canUseResetItems());
  }
}
