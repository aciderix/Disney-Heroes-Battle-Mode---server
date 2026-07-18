import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.HeroHelper;
import dhserver.*;
import java.util.EnumMap;

/**
 * Régression du bug d'équipement trouvé EN JEU (tuto, étape équip) : le client équipe
 * {@code BADGE_OF_FRIENDSHIP} en {@code SLOT=SIX} de Frozone, mais le serveur RENVOYAIT
 * {@code ClientErrorCodeException: WRONG_ITEM} car il IGNORAIT le slot du client et devinait via
 * {@code HeroHelper.getSlotThatCanEquip} — qui renvoie le PREMIER slot équipable (ex. ONE) quand
 * plusieurs le sont (Frozone a d'autres gear après les coffres) → equipItem tentait le Badge en ONE.
 *
 * <p>Ce test charge l'état RÉEL du bug ({@code /tmp/dh-equipbug.db}, ou une DB passée en arg) où Frozone a
 * plusieurs slots équipables + le Badge en inventaire, applique {@code EQUIP_ITEM} avec {@code extra[SLOT]=SIX}
 * et vérifie que le serveur HONORE le slot du client → Badge en slot SIX, sans WRONG_ITEM.
 */
public final class EquipSlotTest {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "/tmp/dh-equipbug.db";
    try (UserStore store = new UserStore(db)) {
      ServerUser u = store.loadOrCreate(1L, 1);
      BootData bd = u.bootData();
      User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "eqslot");
      IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
          bd.individualUserExtra, 1L, bd.userInfo.diamonds, "eqslot");
      ServerContext.bind(user, iu);

      IHero frozone = user.getHero(UnitType.FROZONE);
      if (frozone == null) throw new AssertionError("Frozone absent de la DB " + db);
      HeroEquipSlot guessed = HeroHelper.getSlotThatCanEquip(user, frozone);
      System.out.println("[repro] getSlotThatCanEquip(FROZONE) = " + guessed
          + " (le client, lui, demande SLOT=SIX)");
      if (guessed == HeroEquipSlot.SIX)
        System.out.println("[repro] NB: ici getSlotThatCanEquip=SIX → l'ancien bug ne se déclenche pas sur cette DB");

      // S'assurer que l'objet est présent (isole le bug de SLOT du bug de loot manquant, cf. DONT_HAVE_ITEM) :
      // si le serveur n'a pas le Badge (divergence loot #25), on le crédite pour tester UNIQUEMENT le slot.
      if (user.getItemAmount(ItemType.BADGE_OF_FRIENDSHIP) <= 0) {
        user.addItem(ItemType.BADGE_OF_FRIENDSHIP, 1, false,
            com.perblue.heroes.game.logic.RewardSourceType.NORMAL);
        System.out.println("[repro] Badge absent de l'inventaire serveur → crédité pour isoler le test du SLOT");
      }

      // Reproduit l'Action envoyée par le client (relevé en jeu : EQUIP_ITEM FROZONE BADGE_OF_FRIENDSHIP SLOT=SIX).
      Action m = new Action();
      m.command = CommandType.EQUIP_ITEM;
      m.heroType = UnitType.FROZONE;
      m.itemType = ItemType.BADGE_OF_FRIENDSHIP;
      m.extra = new EnumMap<>(ActionExtraType.class);
      m.extra.put(ActionExtraType.SLOT, HeroEquipSlot.SIX);

      boolean ok = u.applyAction(m);
      if (!ok) throw new AssertionError("applyAction(EQUIP_ITEM SLOT=SIX) a renvoyé false (WRONG_ITEM ?)");

      // applyAction resynchronise l'état héros vers le wire de la ServerUser → relire une vue FRAÎCHE.
      BootData bd2 = u.bootData();
      User user2 = ClientNetworkStateConverter.getUser(bd2.userInfo, bd2.userExtra, "eqslot2");
      ServerContext.bind(user2, ClientNetworkStateConverter.getIndividualUser(
          bd2.individualUserExtra, 1L, bd2.userInfo.diamonds, "eqslot2"));
      IEquippedItem six = user2.getHero(UnitType.FROZONE).getItem(HeroEquipSlot.SIX);
      if (six == null || six.getType() != ItemType.BADGE_OF_FRIENDSHIP)
        throw new AssertionError("slot SIX attendu = BADGE_OF_FRIENDSHIP, obtenu " + (six == null ? "null" : six.getType()));

      System.out.println("EQUIP SLOT TEST OK — le serveur honore le SLOT du client (Badge en slot SIX, "
          + "plus de WRONG_ITEM ; persiste au wire).");
    }
  }
}
