import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * EXPEDITION (#72) incrément 3 — combat de nœud ({@code ExpeditionAttack}, client-autoritatif). Le serveur ré-exécute
 * l'autorité : anti-triche sur le nœud, sur VICTOIRE avance la progression + roule/crédite la récompense de nœud
 * ({@code ExpeditionStats.rollExpeditionDrops} + {@code ExpeditionHelper.giveLoot}), persiste. Zéro invention (§4).
 */
public final class ExpeditionCombatTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[expedition-combat] " + m); }

  static ExpeditionAttack attack(int node, CombatOutcome outcome) {
    ExpeditionAttack ea = new ExpeditionAttack();
    ea.nodeIndex = node;
    AttackBase base = new AttackBase();
    base.outcome = outcome;
    base.stars = 3;
    base.attackers = new ArrayList<AttackLineupSummary>();
    base.defenders = new ArrayList<AttackLineupSummary>();
    ea.base = base;
    ea.attackerHeroes = new ArrayList<>();
    ea.defenderHeroes = new ArrayList<>();
    return ea;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4902L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);

    // Génère le run (incr. 2).
    ExpeditionRunData run = ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    check(run != null && run.nodesDefeated == 0, "run frais, nodesDefeated=0");
    long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

    // --- ANTI-TRICHE : jouer un nœud ≠ courant est refusé ---
    check(!ServerExpedition.recordAttack(su, attack(3, CombatOutcome.WIN)), "nœud 3 (≠ courant 0) refusé");
    check(su.expeditionRunOrNull().nodesDefeated == 0, "progression inchangée après refus");

    // --- VICTOIRE du nœud 0 → progression + or ---
    check(ServerExpedition.recordAttack(su, attack(0, CombatOutcome.WIN)), "nœud 0 WIN appliqué");
    ExpeditionRunData r1 = su.expeditionRunOrNull();
    check(r1.nodesDefeated == 1, "nodesDefeated 0→1");
    long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
    System.out.println("[expedition-combat] nœud 0 WIN → nodesDefeated=" + r1.nodesDefeated
        + ", or " + goldBefore + "→" + goldAfter + " (totalGoldEarned=" + r1.totalGoldEarned + ")");
    check(goldAfter >= goldBefore, "or crédité (>= avant)");
    check(r1.nodeRewards != null && r1.nodeRewards.size() == 1, "1 récompense de nœud enregistrée");

    // --- DÉFAITE : pas de progression ---
    check(ServerExpedition.recordAttack(su, attack(1, CombatOutcome.LOSS)), "nœud 1 LOSS accepté (no-op)");
    check(su.expeditionRunOrNull().nodesDefeated == 1, "défaite = pas de progression");

    // --- VICTOIRE du nœud 1 → progression continue ---
    check(ServerExpedition.recordAttack(su, attack(1, CombatOutcome.WIN)), "nœud 1 WIN appliqué");
    check(su.expeditionRunOrNull().nodesDefeated == 2, "nodesDefeated 1→2");

    // --- Persistance DB ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-expcombat-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4902L, 1);
    ExpeditionRunData r2 = rl.expeditionRunOrNull();
    check(r2 != null && r2.nodesDefeated == 2, "progression (2 nœuds) survit à la DB");
    check(r2.totalGoldEarned == r1.totalGoldEarned, "totalGoldEarned persiste");
    WireCheck.assertRoundTrips(ServerExpedition.response(rl));
    store.close();
    System.out.println("[expedition-combat] persistance DB : nodesDefeated=" + r2.nodesDefeated + " relu OK");

    System.out.println("[expedition-combat] OK — combat de nœud (ExpeditionAttack) autoritatif + persistance (#72 incr. 3)");
  }
}
