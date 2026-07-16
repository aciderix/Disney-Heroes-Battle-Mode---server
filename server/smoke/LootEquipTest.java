import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.HeroHelper;
import com.perblue.heroes.game.logic.RewardSourceType;
import com.perblue.heroes.game.data.unit.normalgear.NormalGearStats;
import com.perblue.heroes.game.data.unit.UnitStatsMath;
import com.perblue.common.EnumFloatMap;
import dhserver.*;

import java.util.HashMap;

/**
 * Smoke test bout-en-bout LOOT → ÉQUIPEMENT → AMÉLIORATION → PERSISTANCE (répond « les équipements ramassés
 * sont-ils dispo à l'équipement et améliorent-ils les héros ? »). Un objet de gear obtenu (ici ajouté à
 * l'inventaire comme un loot de campagne — cf. {@code LootPersistTest} pour le crédit du loot) doit :
 * (1) être reconnu comme <b>équipable</b> ({@code HeroHelper.getSlotThatCanEquip} renvoie le slot dont le gear
 * requis est possédé) ; (2) une fois équipé via le <b>vrai chemin serveur</b> {@code applyAction(EQUIP_ITEM)}
 * (= {@code HeroHelper.equipItem} + {@code resyncHeroes}), remplir le slot, <b>consommer</b> l'objet et
 * <b>augmenter les stats</b> du héros ({@code UnitStatsMath.getStats}, config gear-normal — évite le recompute
 * complet, fragile headless à cause de la ligne orpheline EVIL_QUEEN {@code PREDICTIVE_FORTIFICATION}, cf.
 * SHIMS) ; (3) <b>persister</b> au round-trip SQLite.
 */
public final class LootEquipTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "loot-equip");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "loot-equip");
    ServerContext.bind(u, iu);
    return u;
  }

  static double gearStatSum(IHero h) {
    double s = 0;
    EnumFloatMap m = UnitStatsMath.getStats(h, UnitStatsMath.CONFIG_CURRENT_NORMAL_GEAR_ONLY);
    for (com.perblue.heroes.game.data.item.StatType k : com.perblue.heroes.game.data.item.StatType.values())
      s += m.get(k);
    return s;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "/tmp/loot-equip-test.db";
    new java.io.File(db).delete();

    try (UserStore store = new UserStore(db)) {
      ServerUser su = ServerUser.newPlayer(1L, 1);   // roster de départ Ralph + Elastigirl
      User user0 = bind(su);
      IHero hero = user0.getHero(UnitType.RALPH);
      // Gear que le slot 1 de ce héros (à sa rareté) requiert — puis on l'AJOUTE à l'inventaire (comme un loot).
      HeroEquipSlot slot = HeroEquipSlot.ONE;
      ItemType gear = NormalGearStats.getItem(UnitType.RALPH, hero.getRarity(), slot);
      user0.addItem(gear, 1, false, RewardSourceType.NORMAL, "loot-equip-test");

      // Re-bind après addItem (items écrits dans this.extra → persistés).
      User user = bind(su);
      IHero h = user.getHero(UnitType.RALPH);
      double before = gearStatSum(h);
      boolean equippedBefore = h.getItem(slot) != null;
      int ownedBefore = user.getItemAmount(gear);

      // CHEMIN SERVEUR RÉEL : applyAction(EQUIP_ITEM) → equipItem + resyncHeroes (persiste).
      Action m = new Action();
      m.command = CommandType.EQUIP_ITEM; m.heroType = UnitType.RALPH; m.itemType = gear; m.extra = new HashMap();
      boolean ok = su.applyAction(m);

      User user2 = bind(su);
      IHero h2 = user2.getHero(UnitType.RALPH);
      double after = gearStatSum(h2);
      boolean equippedAfter = h2.getItem(slot) != null;
      int ownedAfter = user2.getItemAmount(gear);

      store.save(su);
      ServerUser re = store.loadOrCreate(1L, 1);
      User user3 = bind(re);
      IHero h3 = user3.getHero(UnitType.RALPH);
      boolean equippedReload = h3.getItem(slot) != null;
      double afterReload = gearStatSum(h3);

      System.out.println("[loot-equip] gear=" + gear + " slot=" + slot
          + " | équipé avant=" + equippedBefore + " après=" + equippedAfter + " reload=" + equippedReload
          + " | possédé " + ownedBefore + "→" + ownedAfter
          + " | stats gear " + before + "→" + after + " (reload " + afterReload + ")");

      if (!ok) throw new AssertionError("applyAction(EQUIP_ITEM) a renvoyé false");
      if (!equippedAfter) throw new AssertionError("slot " + slot + " NON équipé après applyAction");
      if (ownedAfter != ownedBefore - 1) throw new AssertionError("objet non consommé (" + ownedBefore + "→" + ownedAfter + ")");
      if (!(after > before)) throw new AssertionError("stats du héros NON améliorées (" + before + "→" + after + ")");
      if (!equippedReload) throw new AssertionError("équipement NON persisté après reload SQLite");
      if (afterReload != after) throw new AssertionError("stats gear non persistées (" + after + "→" + afterReload + ")");

      System.out.println("LOOT EQUIP TEST OK (" + gear + " équipé slot " + slot + " via applyAction, consommé, "
          + "stats gear " + before + "→" + after + " améliorées, survit au round-trip SQLite = dispo & effectif à l'équipement)");
    }
  }
}
