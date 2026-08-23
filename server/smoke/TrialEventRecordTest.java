import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 3 — RECORD d'un combat de trial ({@code TrialEventAttack}).
 *
 * <p>Le serveur rejoue l'issue via la logique du jeu ({@code BaseEventTrialNode.recordOutcome}) : avance le statut du nœud
 * (étoiles), consomme une chance, crédite les récompenses. On prouve : (1) une VICTOIRE sur (sous-trial 1, nœud 1) fait
 * apparaître ce nœud dans {@code subtrials} avec des étoiles ; (2) une chance est consommée ({@code chancesUsed}++) ;
 * (3) l'état persiste (wire + DB). Chemin serveur-autoritatif (blob {@code TrialEventData}), 0 règle réécrite (§3).
 */
public final class TrialEventRecordTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialrecord] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 991001L;
    int SUB = 1, NODE = 1;
    ServerUser su = ServerUser.newPlayer(8792L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;

    int chancesBefore = ServerTrials.getData(su, EV).chancesUsed;

    TrialEventAttack m = new TrialEventAttack();
    m.eventID = EV;
    m.subtrialNumber = SUB;
    m.nodeNumber = NODE;
    m.stagesCleared = 3;
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>();
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN;
    m.base.stars = 3;
    m.base.attackers = new ArrayList<>();
    m.base.defenders = new ArrayList<>();

    su.recordTrialEventAttack(m);

    TrialEventData d = su.trialEventDataOrNull();
    check(d != null && d.eventID == EV, "blob présent pour l'event");
    check(d.subtrials != null && d.subtrials.containsKey(Integer.valueOf(SUB)),
        "sous-trial " + SUB + " présent dans subtrials après victoire (" + (d.subtrials == null ? "null" : d.subtrials.keySet()) + ")");
    TrialEventSubtrialData sd = (TrialEventSubtrialData) d.subtrials.get(Integer.valueOf(SUB));
    check(sd.nodeLevelStatuses != null && sd.nodeLevelStatuses.containsKey(Integer.valueOf(NODE)),
        "nœud " + NODE + " enregistré dans nodeLevelStatuses (" + (sd.nodeLevelStatuses == null ? "null" : sd.nodeLevelStatuses.keySet()) + ")");
    CampaignLevelStatus st = (CampaignLevelStatus) sd.nodeLevelStatuses.get(Integer.valueOf(NODE));
    check(st != null && st.stars > 0, "nœud gagné avec étoiles (" + (st == null ? "null" : st.stars) + ")");
    check(d.chancesUsed == chancesBefore + 1, "une chance consommée (" + chancesBefore + "→" + d.chancesUsed + ")");
    System.out.println("[trialrecord] victoire (sous-trial " + SUB + ", nœud " + NODE + ") : étoiles=" + st.stars
        + ", chancesUsed=" + d.chancesUsed + " ✔");

    // Persistance wire + DB.
    check(su.trialEventWire() != null, "trialEventWire non nul");
    java.io.File db = java.io.File.createTempFile("trialrecord", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8792L, 1);
    TrialEventData dd = fromDb.trialEventDataOrNull();
    check(dd != null && dd.chancesUsed == d.chancesUsed, "chancesUsed persisté en DB");
    check(((CampaignLevelStatus)((TrialEventSubtrialData)dd.subtrials.get(Integer.valueOf(SUB))).nodeLevelStatuses.get(Integer.valueOf(NODE))).stars == st.stars,
        "étoiles du nœud persistées en DB");
    store.close(); db.delete();
    System.out.println("[trialrecord] persistance (wire + DB) ✔");

    System.out.println("[trialrecord] OK — TrialEventAttack rejoué (recordOutcome : avance nœud + conso chance + récompenses) + persistance [headless].");
  }
}
