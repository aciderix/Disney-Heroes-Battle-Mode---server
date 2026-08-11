import com.perblue.heroes.network.messages.*;
import dhserver.*;

/** OUTIL DEV : prépare un compte pour vérifier SAVED_LINEUPS en jeu — quelques héros POSSÉDÉS (pour composer un
 *  lineup à sauver). Usage : ExpAdminLineup [db] [userID] [shard]. */
public final class ExpAdminLineup {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    UserStore s = new UserStore(db);
    ServerUser su = s.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[lineup-adm] aucun compte"); return; }
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.VANELLOPE, UnitType.ELASTIGIRL})
      if (su.gameUser().getHero(t) == null) su.grantHero(t, Rarity.PURPLE, 80, 3);
    s.save(su);
    StringBuilder owned = new StringBuilder();
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.VANELLOPE, UnitType.ELASTIGIRL})
      owned.append(t).append("=").append(su.gameUser().getHero(t) != null).append(" ");
    System.out.println("[lineup-adm] compte " + uid + " prêt : héros " + owned + "[persisté]");
    s.close();
  }
}
