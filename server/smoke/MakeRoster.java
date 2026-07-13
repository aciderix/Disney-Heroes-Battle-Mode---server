import com.perblue.heroes.network.messages.UnitType;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * OUTIL DEV : ajoute Ralph + Elastigirl au roster d'un joueur PERSISTÉ (ex. snapshot post-équip qui ne
 * possédait que Frozone), pour VISUALISER le roster à 3 héros en jeu SANS rejouer l'intro (lente).
 * Usage : MakeRoster <chemin dh-server.db>.
 */
public final class MakeRoster {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a[0];
    try (UserStore store = new UserStore(db)) {
      ServerUser u = store.loadOrCreate(1L, 1);
      System.out.println("[roster] avant : " + u.heroCount() + " héros");
      u.grantHero(UnitType.RALPH);
      u.grantHero(UnitType.ELASTIGIRL);
      store.save(u);
      System.out.println("[roster] après : " + u.heroCount() + " héros → sauvé dans " + db);
    }
  }
}
