import com.perblue.heroes.network.messages.ResourceType;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/** DEV (non-régression) : sème un compte prêt-HUB (tuto complété, OR+diamants abondants, TL élevé) dans la DB du
 *  serveur pour la VÉRIF EN JEU des coffres (issue #1). Usage : ScratchSeedChestAccount <db> <userID> */
public final class ScratchSeedChestAccount {
  public static void main(String[] a) throws Exception {
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 777L;
    ServerContext.init();
    UserStore store = new UserStore(db);
    ServerUser su = store.loadOrCreate(uid, 1);
    su.setTeamLevel(60);
    su.giveResource(ResourceType.GOLD, 50_000_000);
    su.giveResource(ResourceType.DIAMONDS, 200_000);
    int done = su.completeAllTutorials();
    store.save(su);
    store.close();
    System.out.println("[seed] userID=" + uid + " prêt-HUB : TL60, OR=+50M, DIAM=+200k, tuto complété (" + done + " actes) → " + db);
  }
}
