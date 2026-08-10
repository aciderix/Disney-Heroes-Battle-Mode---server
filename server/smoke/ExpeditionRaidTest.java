import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * EXPEDITION (#72) incrément 4 — RAID ({@code ExpeditionRaid}). Le serveur ré-exécute l'autorité via la méthode du
 * jeu {@code ExpeditionHelper.doRaid} : gate {@code isDifficultyRaidable} (anti-triche), débit des tickets de raid
 * ({@code getRaidCost}, lève si insuffisant), crédit de TOUS les nœuds, complétion du run. Zéro invention (§4).
 *
 * <p>La difficulté n'est RAIDABLE qu'après avoir été CLEARÉE une fois (progression : compléter les 15 nœuds active
 * la difficulté suivante → {@code getRawMaxEnabledDifficulty > diff}). Le test exerce cette progression RÉELLE.
 */
public final class ExpeditionRaidTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[expedition-raid] " + m); }

  static ExpeditionAttack win(int node) {
    ExpeditionAttack ea = new ExpeditionAttack();
    ea.nodeIndex = node;
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    b.attackers = new ArrayList<>(); b.defenders = new ArrayList<>();
    ea.base = b; ea.attackerHeroes = new ArrayList<>(); ea.defenderHeroes = new ArrayList<>();
    return ea;
  }
  static ExpeditionRaid raid(int diff) { ExpeditionRaid r = new ExpeditionRaid(); r.difficulty = diff; return r; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4903L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);

    // Run frais.
    ExpeditionRunData run = ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    int nodes = run.defenders.size();

    // --- ANTI-TRICHE : la difficulté 1 n'est PAS encore raidable (jamais clearée) → raid refusé ---
    check(!ServerExpedition.recordRaid(su, raid(1)), "raid refusé tant que la difficulté n'est pas clearée");
    check(su.expeditionRunOrNull().nodesDefeated == 0, "run inchangé après refus");

    // --- Clear RÉEL des 15 nœuds (FIGHT) → active la difficulté 2 → la difficulté 1 devient RAIDABLE ---
    for (int n = 0; n < nodes; n++) check(ServerExpedition.recordAttack(su, win(n)), "nœud " + n + " WIN");
    check(su.expeditionRunOrNull().nodesDefeated == nodes, "les " + nodes + " nœuds vaincus");
    check(com.perblue.heroes.game.logic.ExpeditionHelper.isDifficultyRaidable(su.gameUser(), 1),
        "difficulté 1 RAIDABLE après clear complet");

    // Nouveau run à raider + un ticket de raid EXPEDITION_RAID_1 (write-through, persiste).
    ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    su.gameUser().addItem(ItemType.EXPEDITION_RAID_1, 1, false,
        com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    int ticketsBefore = su.gameUser().getItemAmount(ItemType.EXPEDITION_RAID_1);
    check(ticketsBefore >= 1, "1 ticket de raid accordé (=" + ticketsBefore + ")");
    long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

    // --- RAID : complète l'expédition d'un coup, débite le ticket, crédite tous les nœuds ---
    check(ServerExpedition.recordRaid(su, raid(1)), "raid diff 1 appliqué");
    ExpeditionRunData r2 = su.expeditionRunOrNull();
    check(r2.nodesDefeated == nodes, "raid → run COMPLET (nodesDefeated=" + r2.nodesDefeated + ")");
    long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
    int ticketsAfter = su.gameUser().getItemAmount(ItemType.EXPEDITION_RAID_1);
    System.out.println("[expedition-raid] RAID → nodesDefeated=" + r2.nodesDefeated + ", or " + goldBefore + "→"
        + goldAfter + ", tickets " + ticketsBefore + "→" + ticketsAfter);
    check(goldAfter > goldBefore, "or crédité par le raid");
    check(ticketsAfter == ticketsBefore - 1, "1 ticket de raid débité (anti-triche coût)");

    // --- ANTI-TRICHE : sans ticket, un nouveau raid est refusé ---
    ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    check(!ServerExpedition.recordRaid(su, raid(1)), "raid refusé sans ticket (DONT_HAVE_ITEM)");

    // --- Persistance DB ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-expraid-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    // regagne un ticket + raid, puis persiste
    su.gameUser().addItem(ItemType.EXPEDITION_RAID_1, 1, false,
        com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    check(ServerExpedition.recordRaid(su, raid(1)), "raid (2e) appliqué");
    store.save(su);
    ServerUser rl = store.loadIfExists(4903L, 1);
    check(rl.expeditionRunOrNull().nodesDefeated == nodes, "run complet survit à la DB");
    WireCheck.assertRoundTrips(ServerExpedition.response(rl));
    store.close();

    System.out.println("[expedition-raid] OK — RAID autoritatif (doRaid : gate + débit ticket + crédit + complétion) + persistance (#72 incr. 4)");
  }
}
