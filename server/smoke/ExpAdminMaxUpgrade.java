import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.item.ItemStats;
import com.perblue.heroes.game.data.item.enchanting.EnchantingStats;
import com.perblue.heroes.game.logic.EnchantingHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;

/** OUTIL DEV : prépare un compte pour vérifier le MAX-UPGRADE PRIME BADGES en jeu — RALPH rang YELLOW (6 slots
 *  YELLOW enchantables) + matériaux d'enchant (VOID_DUST/SHIMMER_DUST/PRIMAL_ESSENCE) + or.
 *  Usage : ExpAdminMaxUpgrade [db] [userID] [shard]. */
public final class ExpAdminMaxUpgrade {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    UserStore s = new UserStore(db);
    ServerUser su = s.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[maxup-adm] aucun compte"); return; }
    su.grantHero(UnitType.RALPH, Rarity.YELLOW, 200, 5);   // rang YELLOW → 6 slots YELLOW (getMaxStars=5)
    su.debugGiveFullGear(UnitType.RALPH);
    for (ItemType it : new ItemType[]{ItemType.VOID_DUST, ItemType.SHIMMER_DUST, ItemType.PRIMAL_ESSENCE})
      su.gameUser().addItem(it, 500, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "adm");
    su.gameUser().setResource(ResourceType.GOLD, 50_000_000L, "adm");
    s.save(su);
    var hero = su.gameUser().getHero(UnitType.RALPH);
    System.out.println("[maxup-adm] compte " + uid + " prêt : RALPH rang YELLOW, slots :");
    for (HeroEquipSlot slot : HeroEquipSlot.values()) {
      var it = hero.getItem(slot);
      if (it == null) continue;
      Rarity r = ItemStats.getRarity(it.getType());
      System.out.println("  " + slot + "=" + it.getType() + " (" + r + ") étoiles=" + it.getStars()
          + "/" + EnchantingStats.getMaxStars(r));
    }
    var plan = EnchantingHelper.buildMaxUpgradePlanForHero(su.gameUser(), UnitType.RALPH, SpecialEventSnapshot.NONE);
    System.out.println("[maxup-adm] plan max-upgrade : slots=" + plan.executionOrder.size()
        + " or=" + plan.totalGold + " items=" + plan.totalItems
        + " | VOID_DUST=" + su.gameUser().getItemAmount(ItemType.VOID_DUST)
        + " or=" + su.gameUser().getResource(ResourceType.GOLD) + " [persisté]");
    s.close();
  }
}
