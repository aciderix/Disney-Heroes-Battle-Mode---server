import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * OUTIL DEV (opérateur) — régénère le run d'expédition PERSISTÉ d'un compte avec le code SERVEUR courant (defenders
 * valides + nodeRewards pré-peuplés). Sert à re-tester le combat EN JEU quand un run persisté par un ancien build est
 * cassé (ex. étoiles ennemies invalides / nodeRewards vide). N'est PAS un test de régression (prend des arguments).
 *
 * Usage : ExpAdminReset [dbPath] [userID] [shardID] [difficulty]
 *   défaut : server/data/dh-server.db 1 1 1
 */
public final class ExpAdminReset {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid   = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard  = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    int diff   = a.length > 3 ? Integer.parseInt(a[3]) : 1;
    UserStore store = new UserStore(db);
    ServerUser su = store.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[exp-admin] AUCUN compte (" + uid + "," + shard + ") dans " + db); return; }
    ExpeditionRunData run = ServerExpedition.resetRun(su, diff, new ArrayList<>(), true);
    if (run == null) { System.out.println("[exp-admin] resetRun refusé"); return; }
    store.save(su);
    DefenderData d0 = (DefenderData) run.defenders.get(0);
    HeroData h0 = (HeroData) d0.lineup.get(0);
    System.out.println("[exp-admin] run RÉGÉNÉRÉ compte " + uid + " : defenders=" + run.defenders.size()
        + " lineup=" + d0.lineup.size() + " nodeRewards=" + (run.nodeRewards == null ? 0 : run.nodeRewards.size())
        + " ex.hero[0]=" + h0.type + " lvl=" + h0.level + " stars=" + h0.stars
        + " expeditionID=" + su.expeditionIDPersisted() + " [persisté]");
    store.close();
  }
}
