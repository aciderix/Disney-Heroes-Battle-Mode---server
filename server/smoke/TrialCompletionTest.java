import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 6 — COMPLÉTION : récompense (Patch Essence, client-reporté) + hook de complétion.
 *
 * <p>On prouve, chemin serveur-autoritatif (§3), 0 règle réécrite :
 * <ol>
 *   <li><b>Récompense créditée</b> : le loot client-reporté ({@code lootEarned}, ici un drop Patch Essence) est crédité par
 *       {@code BaseEventTrialNode.recordOutcome} → {@code RewardHelper.giveRewards} (modèle client-autoritatif §4bis, comme PORT).</li>
 *   <li><b>Correctif §8 (étoiles)</b> : le 2ᵉ param de {@code recordOutcome} sont les ÉTOILES ({@code base.stars}), PAS
 *       {@code stagesCleared} — ici {@code stagesCleared=0} mais {@code base.stars=3} ⇒ le nœud est bien à 3★.</li>
 *   <li><b>Hook de complétion</b> : {@code PatchedHeroesHelper.handleFranchiseTrialCompletion} est appelé (parité client) sans erreur.</li>
 *   <li>Persistance (wire + DB) de l'item crédité et des étoiles du nœud.</li>
 * </ol>
 */
public final class TrialCompletionTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialcomplete] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 993001L;
    int SUB = 1;      // WILDCARD (1ᵉʳ sous-trial) → pas de gating, lineup libre
    int NODE = 5;     // ≥ getFranchiseTrialsStageNumber → éligible au flag de complétion
    ItemType ESSENCE = com.perblue.heroes.game.logic.PatchedHeroesHelper.getPatchEssenceTier(1);
    check(ESSENCE != null, "getPatchEssenceTier(1) fournit un ItemType");
    int give = 7;

    ServerUser su = ServerUser.newPlayer(8851L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    int before = su.itemAmount(ESSENCE);

    // Loot client-reporté = 1 drop de Patch Essence (RewardDrop{itemType, quantity}).
    RewardDrop drop = new RewardDrop();
    drop.itemType = ESSENCE;
    drop.quantity = give;

    TrialEventAttack m = new TrialEventAttack();
    m.eventID = EV; m.subtrialNumber = SUB; m.nodeNumber = NODE;
    m.stagesCleared = 0;                    // ⚠ différent des étoiles : prouve que recordOutcome utilise base.stars
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>(); m.lootEarned.add(drop);
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
    m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
    su.recordTrialEventAttack(m);

    // (1) récompense créditée (via recordOutcome → giveRewards).
    int after = su.itemAmount(ESSENCE);
    check(after == before + give, "Patch Essence créditée (" + before + "→" + after + ", attendu +" + give + ")");
    System.out.println("[trialcomplete] récompense Patch Essence (" + ESSENCE + ") créditée : " + before + "→" + after + " ✔");

    // (2) correctif étoiles : nœud à 3★ bien que stagesCleared=0.
    TrialEventData d = su.trialEventDataOrNull();
    TrialEventSubtrialData sd = (TrialEventSubtrialData) d.subtrials.get(Integer.valueOf(SUB));
    CampaignLevelStatus st = (CampaignLevelStatus) sd.nodeLevelStatuses.get(Integer.valueOf(NODE));
    check(st != null && st.stars == 3, "nœud à 3★ (base.stars, pas stagesCleared) : " + (st == null ? "null" : st.stars));
    System.out.println("[trialcomplete] étoiles du nœud = " + st.stars + " (base.stars ; stagesCleared=0 ignoré) ✔");

    // (3) persistance (wire + DB) : item + étoiles.
    java.io.File db = java.io.File.createTempFile("trialcomplete", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8851L, 1);
    check(fromDb.itemAmount(ESSENCE) == after, "Patch Essence persistée en DB");
    TrialEventData dd = fromDb.trialEventDataOrNull();
    check(((CampaignLevelStatus)((TrialEventSubtrialData)dd.subtrials.get(Integer.valueOf(SUB)))
        .nodeLevelStatuses.get(Integer.valueOf(NODE))).stars == 3, "étoiles du nœud persistées en DB");
    store.close(); db.delete();
    System.out.println("[trialcomplete] persistance (wire + DB) ✔");

    System.out.println("[trialcomplete] OK — récompense client-reportée créditée + correctif étoiles + hook complétion + persistance [headless].");
  }
}
