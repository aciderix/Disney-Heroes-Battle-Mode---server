import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * UPDATE_TIME — setter de temps générique (« vu à l'instant t » pour éteindre la pastille « ! » de nombreux
 * écrans : EVENTS, CHESTS, CONTEST, MERCHANT, PRIZE_WALL…). Gap trouvé EN JEU sur l'écran EVENTS
 * (LAST_EVENT_VIEW_TIME). Logique du jeu : IUser.setTime → this.extra.times (auto-persisté).
 */
public final class UpdateTimeTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "ut");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "ut");
    ServerContext.bind(u, iu);
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    long t = 1784570853882L;
    TimeType TT = TimeType.LAST_EVENT_VIEW_TIME;

    Action m = new Action(); m.command = CommandType.UPDATE_TIME;
    m.extra = new java.util.EnumMap<>(ActionExtraType.class);
    m.extra.put(ActionExtraType.TYPE, TT.name());
    m.extra.put(ActionExtraType.TIME, Long.toString(t));

    boolean applied = su.applyAction(m);
    long got = bind(su).getTime(TT);
    System.out.println("[time] UPDATE_TIME " + TT + " appliqué=" + applied + " → getTime=" + got);
    if (!applied) throw new AssertionError("UPDATE_TIME aurait dû s'appliquer");
    if (got != t) throw new AssertionError("getTime devrait renvoyer " + t);

    // PERSISTANCE : reload wire → temps conservé.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    long gotR = bind(reloaded).getTime(TT);
    System.out.println("[time] après reload → getTime=" + gotR);
    if (gotR != t) throw new AssertionError("le temps aurait dû PERSISTER");

    System.out.println("[time] OK — UPDATE_TIME (LAST_EVENT_VIEW_TIME) marqué + persiste (pastille EVENTS éteinte)");
  }
}
