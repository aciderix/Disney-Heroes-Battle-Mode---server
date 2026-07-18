import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.HeroHelper;
import dhserver.*;

/**
 * PROBE (DEV) — « comment détecter l'équipement possible » (le « +equip vert »).
 *
 * <p>Le bouton vert « +equip » du jeu correspond à la logique {@code HeroHelper.hasItemsToEquip(user, hero)}
 * (au moins un slot équipable avec un objet possédé). Le slot exact = {@code getSlotThatCanEquip(user, hero)},
 * le nombre = {@code getItemsToEquipCount(user, hero)}, l'état par slot = {@code getGearState(user, hero, slot)}.
 * Ce probe charge une DB persistée et liste, par héros, ce qui est équipable — c'est la détection que le pilote
 * doit utiliser (au lieu de dépendre d'un pointeur de tuto qui n'apparaît pas toujours).
 *
 * <p>Usage : {@code EquipDetectProbe [chemin.db]} (défaut server/data/dh-server.db).
 */
public final class EquipDetectProbe {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore store = new UserStore(db)) {
      ServerUser su = store.loadOrCreate(1L, 1);
      BootData bd = su.bootData();
      User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "equip");
      IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
          bd.individualUserExtra, 1L, bd.userInfo.diamonds, "equip");
      ServerContext.bind(u, iu);

      System.out.println("=== DÉTECTION ÉQUIPEMENT (" + db + ") ===");
      for (Object o : u.getHeroes()) {
        IHero h = (IHero) o;
        try { probeHero(u, h); }
        catch (Throwable t) { System.out.println("  " + h.getType() + " : erreur détection headless (" + t + ")"); }
      }
      System.out.println();
      System.out.println("=> Le pilote doit : pour chaque hero, si hasItemsToEquip -> ouvrir HeroDetail(hero)");
      System.out.println("   onglet GEAR, taper le slot getSlotThatCanEquip -> CraftingWindow -> EQUIP.");
    }
  }

  static void probeHero(User u, IHero h) {
    boolean has = HeroHelper.hasItemsToEquip(u, h);
    int count = HeroHelper.getItemsToEquipCount(u, h);
    HeroEquipSlot slot = HeroHelper.getSlotThatCanEquip(u, h);
    System.out.printf("%-12s niv.%d : +equip VERT=%s  nbSlots=%d  slotAEquiper=%s%n",
        h.getType(), h.getLevel(), has ? "OUI" : "non", count, slot);
    // détail par slot : état du gear (READY_TO_EQUIP / DONT_HAVE / EQUIPPED…)
    for (HeroEquipSlot s : HeroEquipSlot.values()) {
      IEquippedItem cur = h.getItem(s);
      String equipped = (cur != null && cur.getType() != null && cur.getType() != ItemType.DEFAULT)
          ? cur.getType().toString() : "-";
      String st;
      // getGearState touche parfois une classe d'UI cliente (Gdx.graphics null en headless) → tolérant.
      try { st = String.valueOf(HeroHelper.getGearState(u, h, s)); }
      catch (Throwable t) { st = "(n/a headless)"; }
      System.out.printf("     %-6s : etat=%-16s equipe=%s%n", s, st, equipped);
    }
  }
}
