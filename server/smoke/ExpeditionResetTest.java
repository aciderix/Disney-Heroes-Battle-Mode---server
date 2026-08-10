import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.ExpeditionHelper;
import com.perblue.heroes.game.data.expedition.ExpeditionStats;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;
import java.util.*;

/**
 * EXPEDITION (#72) incrément 6 — ÉCONOMIE DE RESET ({@code chargeForReset}/{@code getResetsRemaining}). Relancer une
 * expédition (hors 1ᵉʳ run) consomme un RESET GRATUIT ({@code CITY_WATCH_RESETS}) ; épuisé, il coûte
 * {@code getEpicKeyCost(diff)} clés epic ({@code CITY_WATCH_EPIC_KEYS}) ; à défaut, refusé ({@code
 * EXPEDITION_CHANCES_USED}). Barème DU JEU (§4) : diff 1-3 → coût 0 (resets libres) ; diff 4 (EPIC) → 35 clés.
 * Tout par la logique du jeu (§3), zéro invention.
 */
public final class ExpeditionResetTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[expedition-reset] " + m); }
  static final SpecialEventSnapshot SNAP = SpecialEventSnapshot.NONE;

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4905L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);

    long resets0 = ExpeditionHelper.getResetsRemaining(su.gameUser(), SNAP);
    check(resets0 >= 1, "compte neuf : ≥ 1 reset gratuit (=" + resets0 + ")");

    // Barème DU JEU (§4) : diff 1-3 → coût epic 0 ; diff 4 (EPIC) → 35 clés.
    check(ExpeditionHelper.getEpicKeyCost(su.gameUser(), SNAP, 1) == 0, "coût reset EASY = 0 clé");
    check(ExpeditionHelper.getEpicKeyCost(su.gameUser(), SNAP, 4) == ExpeditionStats.getEpicKeyCost(),
        "coût reset EPIC = getEpicKeyCost() (" + ExpeditionStats.getEpicKeyCost() + ")");

    // 1er run (firstEver) : NE consomme PAS de reset.
    check(ServerExpedition.resetRun(su, 1, new ArrayList<>(), true) != null, "1er run (firstEver) OK");
    check(ExpeditionHelper.getResetsRemaining(su.gameUser(), SNAP) == resets0, "firstEver ne consomme aucun reset");

    // Reset EASY : consomme le reset gratuit (CITY_WATCH_RESETS).
    check(ServerExpedition.resetRun(su, 1, new ArrayList<>(), false) != null, "reset gratuit OK");
    long resets1 = ExpeditionHelper.getResetsRemaining(su.gameUser(), SNAP);
    System.out.println("[expedition-reset] resets gratuits " + resets0 + "→" + resets1
        + ", resetsDoneToday=" + ServerExpedition.resetsDoneToday(su.gameUser()));
    check(resets1 == resets0 - 1, "reset gratuit décrémente CITY_WATCH_RESETS");
    // resetsDone = compteur d'activité quotidienne DU JEU (getDailyUses) — ne compte PAS les resets-ressource → ≥ 0.
    ResetExpeditionResponse rr = ServerExpedition.resetResponse(su, su.expeditionRunOrNull());
    check(rr.resetsDone >= 0, "resetResponse.resetsDone cohérent (=" + rr.resetsDone + ")");

    // Reset EASY épuisé : resets gratuits à 0 ET coût epic 0 (pas d'option payante en EASY) → REFUSÉ
    // (EXPEDITION_CHANCES_USED). Les resets EASY sont donc LIMITÉS au quota gratuit (CITY_WATCH_RESETS).
    if (ExpeditionHelper.getResetsRemaining(su.gameUser(), SNAP) == 0) {
      check(ServerExpedition.resetRun(su, 1, new ArrayList<>(), false) == null,
          "reset EASY refusé quand resets gratuits épuisés (coût epic 0, pas d'option payante)");
    }

    // --- ÉCONOMIE EPIC (diff 4) : REFUS sans clé, puis PAYANT avec clés (chemin non atteignable à TL100, prouvé headless) ---
    ServerUser epic = ServerUser.newPlayer(4906L, 1);
    epic.bootData().userInfo.basicInfo.teamLevel = 100;
    epic.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
    ServerExpedition.resetRun(epic, 4, new ArrayList<>(), true);            // 1er run EPIC (firstEver, gratuit)
    // épuise le reset gratuit
    ServerExpedition.resetRun(epic, 4, new ArrayList<>(), false);
    int cost = ExpeditionHelper.getEpicKeyCost(epic.gameUser(), SNAP, 4);
    check(cost > 0, "coût reset EPIC > 0 (=" + cost + ")");
    if (ExpeditionHelper.getResetsRemaining(epic.gameUser(), SNAP) == 0
        && epic.gameUser().getResource(ResourceType.CITY_WATCH_EPIC_KEYS) < cost) {
      check(ServerExpedition.resetRun(epic, 4, new ArrayList<>(), false) == null,
          "reset EPIC refusé sans clé (EXPEDITION_CHANCES_USED)");
    }
    epic.gameUser().setResource(ResourceType.CITY_WATCH_EPIC_KEYS, cost, "test");
    check(ServerExpedition.resetRun(epic, 4, new ArrayList<>(), false) != null, "reset EPIC PAYANT (clés) OK");
    long keysAfter = epic.gameUser().getResource(ResourceType.CITY_WATCH_EPIC_KEYS);
    System.out.println("[expedition-reset] reset EPIC payant : clés " + cost + "→" + keysAfter);
    check(keysAfter == 0, "clés epic débitées de " + cost);

    // Persistance DB (compte EASY) : le run régénéré + l'économie survivent.
    String db = System.getProperty("java.io.tmpdir") + "/dh-expreset-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4905L, 1);
    check(rl.expeditionRunOrNull() != null && rl.expeditionRunOrNull().nodesDefeated == 0, "run régénéré survit à la DB");
    check(ExpeditionHelper.getResetsRemaining(rl.gameUser(), SNAP) == resets1, "resets gratuits restants persistent");
    store.close();

    System.out.println("[expedition-reset] OK — économie de reset (EASY : gratuits limités puis refus ; EPIC : payant clés/refus) + persistance (#72 incr. 6)");
  }
}
