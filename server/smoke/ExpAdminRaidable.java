import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * OUTIL DEV (opérateur) — rend l'expédition RAIDABLE pour un compte de test, par le CHEMIN RÉEL : clear complet des
 * 15 nœuds (→ le jeu active la difficulté suivante → la difficulté 1 devient raidable), puis run frais + tickets de
 * raid. Sert à vérifier le RAID en jeu (§8). Usage : ExpAdminRaidable [db] [userID] [shard] [tickets].
 */
public final class ExpAdminRaidable {
  static ExpeditionAttack win(int node) {
    ExpeditionAttack ea = new ExpeditionAttack(); ea.nodeIndex = node;
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    b.attackers = new ArrayList<>(); b.defenders = new ArrayList<>();
    ea.base = b; ea.attackerHeroes = new ArrayList<>(); ea.defenderHeroes = new ArrayList<>();
    return ea;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length>0?a[0]:"server/data/dh-server.db";
    long uid = a.length>1?Long.parseLong(a[1]):1L;
    int shard = a.length>2?Integer.parseInt(a[2]):1;
    int tickets = a.length>3?Integer.parseInt(a[3]):5;
    UserStore store = new UserStore(db);
    ServerUser su = store.loadIfExists(uid, shard);
    if (su==null){ System.out.println("[raidable] aucun compte ("+uid+","+shard+")"); return; }
    ExpeditionRunData run = ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    int nodes = run.defenders.size();
    for (int n=0;n<nodes;n++) ServerExpedition.recordAttack(su, win(n));   // clear complet → active diff 2
    boolean raidable = com.perblue.heroes.game.logic.ExpeditionHelper.isDifficultyRaidable(su.gameUser(), 1);
    ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);             // run frais à raider
    su.gameUser().addItem(ItemType.EXPEDITION_RAID_1, tickets, false,
        com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "admin");
    store.save(su);
    System.out.println("[raidable] compte "+uid+" : diff1 raidable="+raidable+", tickets EXPEDITION_RAID_1="
        + su.gameUser().getItemAmount(ItemType.EXPEDITION_RAID_1) + ", run frais (nodesDefeated=0), expeditionID="
        + su.expeditionIDPersisted() + " [persisté]");
    store.close();
  }
}
