import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * FRANCHISE_TRIALS (#72) incrément 0 — garde-fou WIRE (défaut nº3) sur les 3 messages du mode.
 *
 * <p>Confirme le TYPAGE des List/Map (qui explose À L'ÉCRITURE, invisible headless) AVANT d'écrire tout handler :
 * <ul>
 *   <li>{@code GetTrialEventData{eventID}} (requête) ;</li>
 *   <li>{@code TrialEventData{…, subtrials:Map<Integer,TrialEventSubtrialData>}} avec
 *       {@code TrialEventSubtrialData.nodeLevelStatuses:Map<Integer,CampaignLevelStatus>} (chaque nœud = un niveau,
 *       étoiles/complétion façon campagne) ;</li>
 *   <li>{@code TrialEventAttack{base:AttackBase, …, lootEarned:List<RewardDrop>}} (combat, façon DifficultyModeAttack).</li>
 * </ul>
 * Round-trip {@code writeAll}→{@code readMessage} via {@link WireCheck} : une Map/List du mauvais type lève ici.
 */
public final class TrialsWireTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialswire] " + m); }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // (1) GetTrialEventData (requête)
    GetTrialEventData req = new GetTrialEventData();
    req.eventID = 950123L;
    WireCheck.assertRoundTrips(req);
    GetTrialEventData reqBack = (GetTrialEventData) WireCheck.roundTrip(req);
    check(reqBack.eventID == 950123L, "GetTrialEventData.eventID survit");
    System.out.println("[trialswire] GetTrialEventData round-trip ✔");

    // (2) TrialEventData avec subtrials/nodeLevelStatuses peuplés (types EXACTS)
    TrialEventData data = new TrialEventData();
    data.eventID = 950123L;
    data.chancesUsed = 2;
    data.dailyResetsUsed = 1;
    data.lastChancesResetTime = System.currentTimeMillis();
    data.paidChancesRemaining = 3;
    data.paidResetsUsed = 0;
    TrialEventSubtrialData sub = new TrialEventSubtrialData();
    java.util.Map nodeStatuses = new java.util.HashMap();
    CampaignLevelStatus st = new CampaignLevelStatus();
    st.stars = 3;                                   // nœud complété 3★ (façon campagne)
    nodeStatuses.put(Integer.valueOf(1), st);
    nodeStatuses.put(Integer.valueOf(2), new CampaignLevelStatus());
    sub.nodeLevelStatuses = nodeStatuses;
    java.util.Map subtrials = new java.util.HashMap();
    subtrials.put(Integer.valueOf(0), sub);
    data.subtrials = subtrials;
    WireCheck.assertRoundTrips(data);
    TrialEventData dataBack = (TrialEventData) WireCheck.roundTrip(data);
    check(dataBack.eventID == 950123L && dataBack.chancesUsed == 2, "TrialEventData scalaires survivent");
    check(dataBack.subtrials != null && dataBack.subtrials.size() == 1, "subtrials Map survit (1 sous-trial)");
    Object subBack = dataBack.subtrials.get(Integer.valueOf(0));
    check(subBack instanceof TrialEventSubtrialData, "valeur subtrials = TrialEventSubtrialData (type wire OK, défaut nº3)");
    java.util.Map nodesBack = ((TrialEventSubtrialData) subBack).nodeLevelStatuses;
    check(nodesBack != null && nodesBack.size() == 2, "nodeLevelStatuses Map survit (2 nœuds)");
    check(nodesBack.get(Integer.valueOf(1)) instanceof CampaignLevelStatus, "valeur nodeLevelStatuses = CampaignLevelStatus (type wire OK)");
    check(((CampaignLevelStatus) nodesBack.get(Integer.valueOf(1))).stars == 3, "étoiles du nœud survivent (3★)");
    System.out.println("[trialswire] TrialEventData (subtrials Map<Integer,TrialEventSubtrialData> + nodeLevelStatuses Map<Integer,CampaignLevelStatus>) round-trip ✔");

    // (3) TrialEventAttack (combat client-autoritatif)
    TrialEventAttack atk = new TrialEventAttack();
    atk.base = new AttackBase();
    atk.base.outcome = CombatOutcome.WIN;
    atk.eventID = 950123L;
    atk.nodeNumber = 1;
    atk.subtrialNumber = 0;
    atk.stagesCleared = 3;
    atk.attackEndTime = System.currentTimeMillis();
    java.util.List loot = new java.util.ArrayList();
    loot.add(new RewardDrop());          // WireCheck : seul le TYPE de la liste compte (défaut nº3)
    atk.lootEarned = loot;
    WireCheck.assertRoundTrips(atk);
    TrialEventAttack atkBack = (TrialEventAttack) WireCheck.roundTrip(atk);
    check(atkBack.eventID == 950123L && atkBack.nodeNumber == 1 && atkBack.subtrialNumber == 0, "TrialEventAttack indices survivent");
    check(atkBack.base != null && atkBack.base.outcome == CombatOutcome.WIN, "TrialEventAttack.base.outcome survit");
    check(atkBack.lootEarned != null && atkBack.lootEarned.size() == 1, "lootEarned List<RewardDrop> survit");
    System.out.println("[trialswire] TrialEventAttack (base + lootEarned List<RewardDrop>) round-trip ✔");

    System.out.println("[trialswire] OK — contrat wire des messages Trials confirmé (défaut nº3 écarté ; incr. 0).");
  }
}
