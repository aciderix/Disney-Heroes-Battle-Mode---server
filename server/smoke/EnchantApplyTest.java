import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.EnchantingHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;
import java.util.*;

/**
 * ENCHANTING (#72) incrément 1 — enchantement d'équipement ({@code EnchantItem}). Le serveur ré-exécute
 * {@code EnchantingHelper.enchantItem} : consomme les matériaux + débite l'OR (barème du jeu), monte l'enchant de
 * l'objet équipé, persiste (l'objet vit sur le héros → {@code resyncHeroes}). Anti-triche = levées du jeu. Zéro invention (§4).
 */
public final class EnchantApplyTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[enchant] " + m); }
  static final UnitType HERO = UnitType.RALPH;
  static final HeroEquipSlot SLOT = HeroEquipSlot.ONE;
  static final ItemType MAT = ItemType.VOID_DUST;

  static EnchantItem enchant(int matCount) {
    EnchantItem e = new EnchantItem();
    e.hero = HERO; e.slot = SLOT; e.useDiamonds = false;
    Map<ItemType, Integer> used = new HashMap<>(); used.put(MAT, matCount);
    e.itemsUsed = used;
    return e;
  }
  static int stars(ServerUser su) {
    return su.gameUser().getHero(HERO).getItem(SLOT).getStars();
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9001L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(HERO, Rarity.ORANGE, 100, 5);
    su.debugGiveFullGear(HERO);                 // équipe le gear ENCHANTABLE du rang (slot ONE = PC_FLYERS ORANGE, 0/5)
    // Matériaux + or (write-through → persistés).
    su.gameUser().addItem(MAT, 40, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    su.gameUser().setResource(ResourceType.GOLD, 5_000_000L, "test");

    int matUsed = 30;
    long goldCost = EnchantingHelper.getEnchantGoldCost(su.gameUser(), HERO, SLOT, enchant(matUsed).itemsUsed, SpecialEventSnapshot.NONE);
    check(goldCost > 0, "coût d'enchant > 0 (=" + goldCost + ")");
    int stars0 = stars(su);
    long gold0 = su.gameUser().getResource(ResourceType.GOLD);
    int mat0 = su.gameUser().getItemAmount(MAT);
    check(mat0 >= matUsed, matUsed + " " + MAT + " accordés (=" + mat0 + ")");

    // --- Enchant ---
    check(su.applyEnchantItem(enchant(matUsed)), "enchant appliqué");
    int stars1 = stars(su);
    long gold1 = su.gameUser().getResource(ResourceType.GOLD);
    int mat1 = su.gameUser().getItemAmount(MAT);
    System.out.println("[enchant] slot ONE : étoiles " + stars0 + "→" + stars1 + ", or -" + (gold0 - gold1)
        + ", " + MAT + " " + mat0 + "→" + mat1);
    check(mat1 == mat0 - matUsed, "matériaux débités de " + matUsed);
    check(gold0 - gold1 == goldCost, "or débité du coût exact (" + goldCost + ")");
    check(stars1 > stars0, "étoiles d'enchant montées (" + stars0 + "→" + stars1 + ")");

    // --- Persistance PROFONDE (l'enchant vit sur l'objet du héros) : round-trip wire + DB ---
    ServerUser rl = ServerUser.fromWire(9001L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(stars(rl) == stars1, "étoiles d'enchant survivent au round-trip wire (=" + stars1 + ")");
    String db = System.getProperty("java.io.tmpdir") + "/dh-enchant-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser dbl = store.loadIfExists(9001L, 1);
    check(stars(dbl) == stars1, "étoiles d'enchant survivent à la DB (=" + stars1 + ")");
    store.close();

    // --- ANTI-TRICHE : sans or, refus (NOT_ENOUGH_GOLD) ---
    su.gameUser().setResource(ResourceType.GOLD, 0L, "test");
    check(!su.applyEnchantItem(enchant(1)), "enchant refusé sans or (NOT_ENOUGH_GOLD)");
    check(su.gameUser().getItemAmount(MAT) == mat1, "matériaux NON débités sur refus");

    System.out.println("[enchant] OK — enchantement (enchantItem autoritatif : or+matériaux débités, étoiles montées, persistance profonde, anti-triche) (#72 incr. 1)");
  }
}
