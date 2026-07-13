import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.HashMap;

/**
 * Smoke test des Actions de bookkeeping léger, désormais REAL/NO-OP fidèle (plus « PARTIEL ») :
 * <ul>
 *   <li><b>VIEWED_CHESTS</b> → logique d'origine {@code User.setTime(LAST_CHESTS_VIEW_TIME,
 *       parseLong(extra.get(TIME)))} — l'horodatage <b>persiste au round-trip wire</b> (via
 *       {@code this.extra.times} partagé).</li>
 *   <li><b>RECORD_SERVER_ROLL_FINISHED</b> → NO-OP fidèle (le code client du jeu ne mute rien ;
 *       le comptage des rolls est déjà fait par {@code openChest}) : {@code applyAction} renvoie
 *       {@code true} sans lever.</li>
 * </ul>
 */
public final class ViewedChestsTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);
    long viewTime = 1783924054600L;

    Action viewed = new Action();
    viewed.command = CommandType.VIEWED_CHESTS;
    viewed.heroType = UnitType.DEFAULT;
    viewed.itemType = ItemType.DEFAULT;
    viewed.extra = new HashMap();
    viewed.extra.put(ActionExtraType.TIME, Long.toString(viewTime));
    if (!u.applyAction(viewed)) throw new AssertionError("applyAction(VIEWED_CHESTS) a renvoyé false");

    Action rollDone = new Action();
    rollDone.command = CommandType.RECORD_SERVER_ROLL_FINISHED;
    rollDone.heroType = UnitType.DEFAULT;
    rollDone.itemType = ItemType.DEFAULT;
    rollDone.extra = new HashMap();
    rollDone.extra.put(ActionExtraType.ID, "c0c88e4a-2af2-4a05-8176-49b3b0d8c002");
    rollDone.extra.put(ActionExtraType.TYPE, "CHEST");
    rollDone.extra.put(ActionExtraType.COUNT, "0");
    rollDone.extra.put(ActionExtraType.TIME, "3052");
    if (!u.applyAction(rollDone)) throw new AssertionError("applyAction(RECORD_SERVER_ROLL_FINISHED) a renvoyé false");

    // Round-trip wire : sérialise puis relit, et vérifie que LAST_CHESTS_VIEW_TIME a persisté.
    ServerUser u2 = ServerUser.fromWire(1L, 1, u.userInfoWire(), u.userExtraWire(), u.individualWire());
    BootData bd = u2.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "viewed-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "viewed-test");
    ServerContext.bind(user, iu);

    long got = user.getTime(TimeType.LAST_CHESTS_VIEW_TIME);
    if (got != viewTime)
      throw new AssertionError("LAST_CHESTS_VIEW_TIME attendu " + viewTime + ", obtenu " + got);

    System.out.println("VIEWED_CHESTS TEST OK (setTime persiste au wire ; RECORD_SERVER_ROLL_FINISHED NO-OP fidèle)");
  }
}
