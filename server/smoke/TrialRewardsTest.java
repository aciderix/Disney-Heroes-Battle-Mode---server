import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) — RÉCOMPENSES data-driven + CHANCES (param admin) + consommation.
 *
 * <p>Corrige les manques relevés en jeu (rewards vides, chances hardcodées) :
 * <ol>
 *   <li><b>rewardTypes</b> peuplé depuis le `.tab` (franchise_trials_enemy_config REWARDS/BONUSES) : 14 stages, re-parse client OK.</li>
 *   <li><b>chancesPerReset</b> = param admin (défaut {@code DEFAULT_TRIAL_CHANCES}=10), PLUS de hardcode.</li>
 *   <li><b>consommation</b> : une VICTOIRE consomme une chance ({@code getChancesRemaining} 10→9).</li>
 * </ol>
 */
public final class TrialRewardsTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialrewards] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 994001L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // (1) rewardTypes data-driven + chances param, re-parse client OK.
    com.perblue.common.specialevent.SpecialEventInfo info = ServerEvents.buildFranchiseTrialEvent(EV, now - 1000L, now + 30L * 86400000L, 10);
    com.perblue.heroes.game.specialevent.TrialEventInfo ti =
        (com.perblue.heroes.game.specialevent.TrialEventInfo) info.getComponent(com.perblue.heroes.game.specialevent.TrialEventInfo.class);
    check(ti.getRewardTypes() != null && ti.getRewardTypes().size() == 14,
        "rewardTypes = 14 stages (" + (ti.getRewardTypes() == null ? "null" : ti.getRewardTypes().size()) + ")");
    check(ti.getChancesPerReset() == 10, "chancesPerReset = param admin 10 (" + ti.getChancesPerReset() + ")");
    com.perblue.common.specialevent.SpecialEventInfo re =
        com.perblue.common.specialevent.SpecialEventBuilder.buildEvent(info.toJson().toString());   // chemin re-parse CLIENT
    com.perblue.heroes.game.specialevent.TrialEventInfo rti =
        (com.perblue.heroes.game.specialevent.TrialEventInfo) re.getComponent(com.perblue.heroes.game.specialevent.TrialEventInfo.class);
    check(rti.getRewardTypes() != null && rti.getRewardTypes().size() == 14, "rewardTypes survit au re-parse client");
    System.out.println("[trialrewards] rewardTypes=14 (data-driven .tab) + chancesPerReset=10 (param admin) + re-parse client OK ✔");

    // (2) chances par défaut ≠ ancien hardcode 2.
    check(ServerEvents.DEFAULT_TRIAL_CHANCES == 10, "DEFAULT_TRIAL_CHANCES=10 (vérité terrain), plus de 2 hardcodé");

    // (3) consommation : ClientEventTrial → getChancesRemaining 10 → 9 après une victoire.
    ServerUser su = ServerUser.newPlayer(8871L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    TrialEventData blob = ServerTrials.getData(su, EV);
    com.perblue.heroes.game.objects.trials.ClientEventTrial t =
        new com.perblue.heroes.game.objects.trials.ClientEventTrial(su.gameUser(), info);
    t.setUserData(blob);
    check(t.getChancesRemaining() == 10, "chances au départ = 10 (" + t.getChancesRemaining() + ")");

    TrialEventAttack m = new TrialEventAttack();
    m.eventID = EV; m.subtrialNumber = 1; m.nodeNumber = 1; m.stagesCleared = 3;
    m.attackEndTime = now; m.lootEarned = new ArrayList<>();
    m.base = new AttackBase(); m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
    m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
    su.recordTrialEventAttack(m);

    TrialEventData d2 = su.trialEventDataOrNull();
    com.perblue.heroes.game.objects.trials.ClientEventTrial t2 =
        new com.perblue.heroes.game.objects.trials.ClientEventTrial(su.gameUser(), info);
    t2.setUserData(d2);
    check(d2.chancesUsed == 1, "chancesUsed = 1 après victoire (" + d2.chancesUsed + ")");
    check(t2.getChancesRemaining() == 9, "chances consommées : 10 → 9 (" + t2.getChancesRemaining() + ")");
    System.out.println("[trialrewards] chances CONSOMMÉES : 10 → " + t2.getChancesRemaining() + " (chancesUsed=" + d2.chancesUsed + ") ✔");

    System.out.println("[trialrewards] OK — rewards data-driven (.tab) + chances param admin (10) + consommation réelle [headless].");
  }
}
