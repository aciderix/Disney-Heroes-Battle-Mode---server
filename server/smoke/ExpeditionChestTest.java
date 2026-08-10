import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * EXPEDITION (#72) incrément 7 — COFFRE d'expédition ({@code OpenExpeditionChest}). Un coffre est disponible tous les
 * 3 nœuds vaincus (5 coffres pour 15 nœuds = les 5 régions de la carte CITY WATCH), ouverts DANS L'ORDRE : {@code
 * openChest} exige {@code nodesDefeated % 3 == 0} et {@code chestsOpened == nodesDefeated/3 - 1} (sinon {@code
 * NO_AVAILABLE_EXPEDITION_CHEST}). Le serveur ré-exécute {@code openChest} (roule + crédite) et incrémente {@code
 * chestsOpened}. Zéro invention (§4).
 */
public final class ExpeditionChestTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[expedition-chest] " + m); }

  static ExpeditionAttack win(int node) {
    ExpeditionAttack ea = new ExpeditionAttack(); ea.nodeIndex = node;
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    b.attackers = new ArrayList<>(); b.defenders = new ArrayList<>();
    ea.base = b; ea.attackerHeroes = new ArrayList<>(); ea.defenderHeroes = new ArrayList<>();
    return ea;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4907L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);

    ExpeditionRunData run = ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    int nodes = run.defenders.size();
    OpenExpeditionChest oc = new OpenExpeditionChest(); oc.rewardDrops = new ArrayList<>();

    // --- ANTI-TRICHE : pas de coffre disponible avant d'avoir vaincu un bloc de 3 nœuds ---
    check(!ServerExpedition.recordOpenChest(su, oc), "coffre refusé à 0 nœud (NO_AVAILABLE)");

    // --- Un coffre tous les 3 nœuds, ouverts dans l'ordre ---
    int chestsExpected = 0;
    for (int n = 0; n < nodes; n++) {
      check(ServerExpedition.recordAttack(su, win(n)), "nœud " + n + " WIN");
      int done = su.expeditionRunOrNull().nodesDefeated;
      if (done % 3 == 0) {   // un coffre devient disponible
        check(ServerExpedition.recordOpenChest(su, oc), "coffre dispo à " + done + " nœuds → ouvert");
        chestsExpected++;
        check(su.expeditionRunOrNull().chestsOpened == chestsExpected,
            "chestsOpened=" + chestsExpected + " (vu " + su.expeditionRunOrNull().chestsOpened + ")");
        // 2e ouverture immédiate refusée (déjà ouvert ce bloc — chestsOpened != nodesDefeated/3-1)
        check(!ServerExpedition.recordOpenChest(su, oc), "2e coffre au même palier refusé");
      }
    }
    check(chestsExpected == nodes / 3, nodes + " nœuds → " + (nodes / 3) + " coffres (= régions)");
    System.out.println("[expedition-chest] " + chestsExpected + " coffres ouverts (1 tous les 3 nœuds), chestsOpened="
        + su.expeditionRunOrNull().chestsOpened);

    // --- Persistance DB ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-expchest-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4907L, 1);
    check(rl.expeditionRunOrNull() != null && rl.expeditionRunOrNull().chestsOpened == chestsExpected,
        "chestsOpened persiste (=" + chestsExpected + ")");
    WireCheck.assertRoundTrips(ServerExpedition.response(rl));
    store.close();

    // --- Anti-état : sans run, refus propre ---
    ServerUser noRun = ServerUser.newPlayer(4908L, 1);
    noRun.bootData().userInfo.basicInfo.teamLevel = 100;
    check(!ServerExpedition.recordOpenChest(noRun, oc), "openChest refusé sans run actif");

    System.out.println("[expedition-chest] OK — coffre d'expédition (openChest autoritatif, 1/3 nœuds, ordre + chestsOpened + persistance) (#72 incr. 7)");
  }
}
