import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UserFlag;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * SET_FLAG — setter de flag booléen générique (extra={TYPE=<UserFlag>, COUNT=0/1}). Gap trouvé EN JEU à l'entrée
 * de l'arène (FREE_NAME_CHANGE_SEEN). Logique du jeu : User.setFlag(flag, COUNT!=0) → User.flags → resyncCounts.
 */
public final class SetFlagTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "sf");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "sf");
    ServerContext.bind(u, iu);
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    UserFlag FLAG = UserFlag.FREE_NAME_CHANGE_SEEN;

    if (bind(su).hasFlag(FLAG)) throw new AssertionError("le flag ne devrait pas être posé au départ");

    Action m = new Action(); m.command = CommandType.SET_FLAG;
    m.extra = new java.util.EnumMap<>(ActionExtraType.class);
    m.extra.put(ActionExtraType.TYPE, FLAG.name());
    m.extra.put(ActionExtraType.COUNT, "1");
    boolean applied = su.applyAction(m);
    boolean got = bind(su).hasFlag(FLAG);
    System.out.println("[flag] SET_FLAG " + FLAG + " appliqué=" + applied + " → getFlag=" + got);
    if (!applied) throw new AssertionError("SET_FLAG aurait dû s'appliquer");
    if (!got) throw new AssertionError("le flag aurait dû être posé");

    // PERSISTANCE : reload wire → flag conservé (via resyncCounts qui recopie User.flags → userExtra).
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    boolean gotR = bind(reloaded).hasFlag(FLAG);
    System.out.println("[flag] après reload → getFlag=" + gotR);
    if (!gotR) throw new AssertionError("le flag aurait dû PERSISTER");

    System.out.println("[flag] OK — SET_FLAG (FREE_NAME_CHANGE_SEEN) posé + persiste (générique, logique du jeu)");
  }
}
