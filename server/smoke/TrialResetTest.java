import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 4 — RESETS (chances).
 *
 * <p>Le serveur exécute la logique DU JEU (§3), 0 règle réécrite. On prouve deux mécanismes :
 * <ol>
 *   <li><b>Reset quotidien GRATUIT</b> (auto) : après consommation d'une chance, franchir un nouveau jour (via
 *       {@code refreshTrialDailyReset}, chemin {@code GetTrialEventData}) rafraîchit les chances ({@code chancesUsed}=0)
 *       et incrémente {@code dailyResetsUsed} — logique {@code BaseEventTrial.checkForDailyReset}→{@code doDailyReset}.</li>
 *   <li><b>Reset PAYANT</b> : pour un FRANCHISE trial les données du jeu le DÉSACTIVENT ({@code canUseResetItems=false},
 *       {@code maxPaidResets=0}, {@code resetCost=-1}) → {@code TrialsHelper.resetTrialEvent} lève
 *       {@code ClientErrorCodeException} (anti-triche du JEU, fidèle §4bis) ; le serveur n'accorde rien.</li>
 * </ol>
 */
public final class TrialResetTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialreset] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 991501L;
    int SUB = 1, NODE = 1;
    ServerUser su = ServerUser.newPlayer(8811L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;

    // (1) consomme une chance via une victoire (chancesUsed 0→1).
    TrialEventAttack m = new TrialEventAttack();
    m.eventID = EV; m.subtrialNumber = SUB; m.nodeNumber = NODE; m.stagesCleared = 3;
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>();
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
    m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
    su.recordTrialEventAttack(m);
    TrialEventData d = su.trialEventDataOrNull();
    check(d.chancesUsed == 1, "une chance consommée avant reset (" + d.chancesUsed + ")");
    int dailyResetsBefore = d.dailyResetsUsed;

    // (2) simule un nouveau jour : recule lastChancesResetTime bien avant la borne de reset quotidien du compte
    //     (checkForDailyReset : getLastDailyResetTime()+12h < DailyActivityHelper.getLastDailyResetTime(user) → doDailyReset).
    d.lastChancesResetTime = com.perblue.heroes.util.TimeUtil.serverTimeNow() - 3L * 24 * 3600_000L;
    su.setTrialEventData(d);

    // (3) chemin GetTrialEventData → applique le reset quotidien GRATUIT (auto).
    TrialEventData after = su.refreshTrialDailyReset(EV);
    check(after.chancesUsed == 0, "reset quotidien : chances rafraîchies (chancesUsed=" + after.chancesUsed + ")");
    check(after.dailyResetsUsed == dailyResetsBefore + 1,
        "reset quotidien : dailyResetsUsed++ (" + dailyResetsBefore + "→" + after.dailyResetsUsed + ")");
    System.out.println("[trialreset] reset quotidien GRATUIT : chancesUsed 1→0, dailyResetsUsed "
        + dailyResetsBefore + "→" + after.dailyResetsUsed + " ✔");

    // (4) reset PAYANT sur un FRANCHISE trial : désactivé par les données du jeu → refusé (ClientErrorCodeException).
    boolean refused = false;
    try { su.resetTrialEventPaid(EV); }
    catch (com.perblue.heroes.ClientErrorCodeException e) { refused = true; }
    check(refused, "reset payant refusé par la logique du jeu (franchise trial : canUseResetItems=false)");
    System.out.println("[trialreset] reset PAYANT refusé (données du jeu : franchise trial sans reset payant) ✔");

    // (5) persistance du reset quotidien (wire + DB).
    java.io.File db = java.io.File.createTempFile("trialreset", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8811L, 1);
    TrialEventData dd = fromDb.trialEventDataOrNull();
    check(dd != null && dd.chancesUsed == 0 && dd.dailyResetsUsed == after.dailyResetsUsed,
        "reset quotidien persisté en DB (chancesUsed=" + (dd == null ? "null" : dd.chancesUsed)
        + ", dailyResetsUsed=" + (dd == null ? "null" : dd.dailyResetsUsed) + ")");
    store.close(); db.delete();
    System.out.println("[trialreset] persistance (wire + DB) ✔");

    System.out.println("[trialreset] OK — reset quotidien gratuit (checkForDailyReset) + reset payant fidèlement gaté [headless].");
  }
}
