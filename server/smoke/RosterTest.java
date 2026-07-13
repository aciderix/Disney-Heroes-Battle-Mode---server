import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IHero;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Smoke test du ROSTER DE DÉPART (fidélité vidéo, PRINCIPLES §4bis) : un compte neuf possède déjà
 * <b>Ralph + Elastigirl</b> AVANT le coffre, puis Frozone arrive via le coffre GOLD. On vérifie l'état
 * (WHITE, niveau 1) et la persistance au round-trip wire.
 */
public final class RosterTest {

  static BuyChests chest(ChestType t) {
    BuyChests b = new BuyChests();
    b.chestType = t; b.count = 1;
    ServerRollRequest r = new ServerRollRequest();
    r.channel = ServerRollChannel.CHEST; r.rollId = "1";
    b.roll = r;
    return b;
  }

  static void checkHero(User u, UnitType t) {
    IHero h = u.getHero(t);
    if (h == null) throw new AssertionError("héros ABSENT du roster : " + t);
    System.out.println("[roster]   " + t + " : rarity=" + h.getRarity() + " level=" + h.getLevel());
    if (h.getRarity() != Rarity.WHITE) throw new AssertionError(t + " attendu WHITE, obtenu " + h.getRarity());
    if (h.getLevel() != 1) throw new AssertionError(t + " attendu niveau 1, obtenu " + h.getLevel());
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);

    // Roster de départ (avant tout coffre).
    if (u.heroCount() != 2)
      throw new AssertionError("roster de départ attendu 2 héros (Ralph+Elastigirl), obtenu " + u.heroCount());

    u.openChest(chest(ChestType.GOLD));   // + Frozone

    // Round-trip wire : le roster (départ + Frozone) survit à la sérialisation.
    ServerUser u2 = ServerUser.fromWire(1L, 1, u.userInfoWire(), u.userExtraWire(), u.individualWire());
    BootData bd = u2.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "roster-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "roster-test");
    ServerContext.bind(user, iu);

    checkHero(user, UnitType.RALPH);
    checkHero(user, UnitType.ELASTIGIRL);
    checkHero(user, UnitType.FROZONE);

    System.out.println("ROSTER TEST OK (départ = Ralph+Elastigirl WHITE niv.1 ; +Frozone au coffre ; persiste au wire)");
  }
}
