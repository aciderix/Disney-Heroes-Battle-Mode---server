import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IHero;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.HashMap;

/**
 * Smoke test du handler {@code Action}/{@code EQUIP_ITEM} : le serveur exécute la logique du jeu
 * ({@code HeroHelper.equipItem}) sur l'état autoritatif, et l'équipement <b>persiste au round-trip wire</b>.
 *
 * <p>Scénario : nouveau joueur → coffre GOLD (Frozone) → coffre SILVER (Badge of Friendship) →
 * {@code applyAction(EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP)} → le badge est équipé en slot 6 de
 * Frozone, l'objet est consommé, et l'état survit à une sérialisation/relecture.
 */
public final class EquipTest {

  static BuyChests chest(ChestType t) {
    BuyChests b = new BuyChests();
    b.chestType = t; b.count = 1;
    ServerRollRequest r = new ServerRollRequest();
    r.channel = ServerRollChannel.CHEST; r.rollId = "1";
    b.roll = r;
    return b;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);
    u.openChest(chest(ChestType.GOLD));     // Frozone
    u.openChest(chest(ChestType.SILVER));   // Badge of Friendship (slot 6 de Frozone)

    Action m = new Action();
    m.command = CommandType.EQUIP_ITEM;
    m.heroType = UnitType.FROZONE;
    m.itemType = ItemType.BADGE_OF_FRIENDSHIP;
    m.extra = new HashMap();
    boolean ok = u.applyAction(m);
    if (!ok) throw new AssertionError("applyAction(EQUIP_ITEM) a renvoyé false");

    // Round-trip wire : sérialise l'état puis relit, et vérifie que l'équipement a persisté.
    ServerUser u2 = ServerUser.fromWire(1L, 1, u.userInfoWire(), u.userExtraWire(), u.individualWire());
    BootData bd = u2.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "equip-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "equip-test");
    ServerContext.bind(user, iu);
    IHero fz = user.getHero(UnitType.FROZONE);

    boolean equipped = fz != null && fz.getItem(HeroEquipSlot.SIX) != null;
    int badgeLeft = user.getItemAmount(ItemType.BADGE_OF_FRIENDSHIP);
    if (!equipped) throw new AssertionError("slot 6 de Frozone NON équipé après round-trip");
    if (badgeLeft != 0) throw new AssertionError("le badge n'a pas été consommé (reste " + badgeLeft + ")");

    System.out.println("EQUIP TEST OK (EQUIP_ITEM -> Frozone slot 6 = Badge, consommé, persiste au wire)");
  }
}
