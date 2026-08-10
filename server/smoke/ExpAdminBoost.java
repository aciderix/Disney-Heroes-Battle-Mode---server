import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * OUTIL DEV (opérateur) — met le ROSTER d'un compte de test à un niveau cohérent avec son TEAM LEVEL (héros niv. 100,
 * rareté élevée), pour vérifier EN JEU une VICTOIRE de nœud d'expédition (le compte de test avait TL100 mais des héros
 * niv. 40-60 → ennemis niv.100 imbattables). État de compte légitime (même esprit que SetTeamLevel), pas un contournement.
 * Régénère aussi le run d'expédition (nœud 0 rejouable). Usage : ExpAdminBoost [db] [userID] [shard].
 */
public final class ExpAdminBoost {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid  = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    UserStore store = new UserStore(db);
    ServerUser su = store.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[boost] AUCUN compte (" + uid + "," + shard + ")"); return; }
    UnitType[] team = { UnitType.RALPH, UnitType.HERCULES, UnitType.MAUI, UnitType.SULLEY, UnitType.VANELLOPE };
    for (UnitType t : team) {
      try { su.grantHero(t, Rarity.RED, 100, 6); System.out.println("[boost] " + t + " → RED niv.100 6★"); }
      catch (Throwable e) { System.out.println("[boost] " + t + " échec: " + e); }
    }
    ExpeditionRunData run = ServerExpedition.resetRun(su, 1, new ArrayList<>(), true);
    store.save(su);
    System.out.println("[boost] roster boosté + run régénéré (defenders=" + (run == null ? 0 : run.defenders.size())
        + ", expeditionID=" + su.expeditionIDPersisted() + ") [persisté]");
    store.close();
  }
}
