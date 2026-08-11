import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.item.ItemStats;
import com.perblue.heroes.game.data.item.enchanting.EnchantingStats;
import com.perblue.heroes.game.logic.EnchantingHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;
import java.util.*;

/**
 * ENCHANTING (#72) — MAX-UPGRADE PRIME BADGES ({@code EnhanceMaxPrimeBadge}) + gear RED/YELLOW.
 *
 * <p>Le serveur ré-dérive le plan depuis l'état persisté ({@code EnchantingHelper.buildMaxUpgradePlanForHero}) puis
 * l'applique ({@code applyMaxUpgradePlanForHero} = un {@code enchantItem} par slot), avec garde-fou OR agrégé
 * ({@code plan.totalGold}). Exercé sur :
 * <ul>
 *   <li><b>gear YELLOW</b> : RALPH rang YELLOW → 6 slots YELLOW ({@code getMaxStars(YELLOW)=5}) — max-upgrade des 6
 *       slots d'un coup, coût OR + matériaux EXACTS du plan du jeu, persistance PROFONDE (wire + DB), + anti-triche
 *       OR insuffisant (refus, aucun débit).</li>
 *   <li><b>gear RED</b> : RALPH rang RED → slot ONE = PRESTO (RED, {@code getMaxStars(RED)=5}) — enchant simple
 *       (rarity-agnostic) qui monte les étoiles.</li>
 * </ul>
 * Zéro invention (§4) : coûts/effets = barème du jeu. Leçon EXPEDITION : on vérifie le CONTENU (étoiles par slot),
 * pas juste le type au round-trip.
 */
public final class EnchantMaxUpgradeTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[max-upgrade] " + m); }
  static final UnitType HERO = UnitType.RALPH;

  static EnhanceMaxPrimeBadge msg(UnitType t) {
    EnhanceMaxPrimeBadge m = new EnhanceMaxPrimeBadge();
    m.unitType = t;
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ===================== PART A : gear YELLOW (6 slots) — max-upgrade =====================
    ServerUser su = ServerUser.newPlayer(9301L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    su.grantHero(HERO, Rarity.YELLOW, 200, 5);
    su.debugGiveFullGear(HERO);                       // 6 slots YELLOW
    // Approvisionne largement les matériaux du plan (le plan est plafonné par getMaxStars, pas par le stock).
    for (ItemType it : new ItemType[]{ItemType.VOID_DUST, ItemType.SHIMMER_DUST, ItemType.PRIMAL_ESSENCE})
      su.gameUser().addItem(it, 500, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    su.gameUser().setResource(ResourceType.GOLD, 50_000_000L, "test");

    // Toutes les 6 pièces sont bien YELLOW (getMaxStars=5) et à 0 étoile.
    var hero = su.gameUser().getHero(HERO);
    int yellowSlots = 0;
    for (HeroEquipSlot slot : HeroEquipSlot.values()) {
      var itq = hero.getItem(slot);
      if (itq == null) continue;
      Rarity r = ItemStats.getRarity(itq.getType());
      check(r == Rarity.YELLOW, slot + " est YELLOW (=" + r + ")");
      check(EnchantingStats.getMaxStars(r) == 5, "getMaxStars(YELLOW)=5");
      check(itq.getStars() == 0, slot + " à 0 étoile au départ (=" + itq.getStars() + ")");
      yellowSlots++;
    }
    check(yellowSlots == 6, "6 slots YELLOW équipés (=" + yellowSlots + ")");

    // Plan du jeu (valeurs EXACTES à débiter).
    var plan = EnchantingHelper.buildMaxUpgradePlanForHero(su.gameUser(), HERO, SpecialEventSnapshot.NONE);
    check(!plan.isEmpty(), "plan non vide");
    check(plan.executionOrder.size() == 6, "plan couvre les 6 slots (=" + plan.executionOrder.size() + ")");
    long planGold = plan.totalGold;
    Map<ItemType, Integer> planItems = new HashMap<>();
    for (Object e : plan.totalItems.entrySet()) {
      Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
      planItems.put((ItemType) en.getKey(), ((Number) en.getValue()).intValue());
    }
    check(planGold > 0, "coût OR du plan > 0 (=" + planGold + ")");
    check(!planItems.isEmpty(), "plan consomme des matériaux (=" + planItems + ")");

    long gold0 = su.gameUser().getResource(ResourceType.GOLD);
    Map<ItemType, Integer> mat0 = new HashMap<>();
    for (ItemType it : planItems.keySet()) mat0.put(it, su.gameUser().getItemAmount(it));

    // --- Max-upgrade ---
    check(su.applyMaxPrimeBadge(msg(HERO)), "max-upgrade appliqué");

    // Toutes les 6 pièces YELLOW ont monté en étoiles.
    hero = su.gameUser().getHero(HERO);
    int[] starsAfter = new int[6]; int i = 0; int upgraded = 0;
    for (HeroEquipSlot slot : HeroEquipSlot.values()) {
      var itq = hero.getItem(slot);
      if (itq == null) continue;
      starsAfter[i++] = itq.getStars();
      if (itq.getStars() > 0) upgraded++;
    }
    check(upgraded == 6, "les 6 slots YELLOW ont monté en étoiles (=" + upgraded + "/6)");

    // OR débité = plan.totalGold EXACT ; matériaux débités = plan.totalItems EXACT.
    long goldSpent = gold0 - su.gameUser().getResource(ResourceType.GOLD);
    check(goldSpent == planGold, "OR débité = plan.totalGold exact (" + planGold + ", vu " + goldSpent + ")");
    for (Map.Entry<ItemType, Integer> en : planItems.entrySet()) {
      int spent = mat0.get(en.getKey()) - su.gameUser().getItemAmount(en.getKey());
      check(spent == en.getValue(), en.getKey() + " débité = " + en.getValue() + " (vu " + spent + ")");
    }
    System.out.println("[max-upgrade] YELLOW RALPH : 6 slots enchantés, or -" + goldSpent + ", matériaux " + planItems);

    // --- Persistance PROFONDE : round-trip wire + DB (les étoiles par slot survivent) ---
    ServerUser rl = ServerUser.fromWire(9301L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    var heroRl = rl.gameUser().getHero(HERO);
    i = 0;
    for (HeroEquipSlot slot : HeroEquipSlot.values()) {
      var itq = heroRl.getItem(slot);
      if (itq == null) continue;
      check(itq.getStars() == starsAfter[i], slot + " étoiles survivent au round-trip wire (="
          + starsAfter[i] + ", vu " + itq.getStars() + ")");
      i++;
    }
    // DB
    java.io.File db = java.io.File.createTempFile("maxup", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9301L, 1);
    check(fromDb != null, "compte relu depuis la DB");
    var heroDb = fromDb.gameUser().getHero(HERO);
    check(heroDb.getItem(HeroEquipSlot.ONE).getStars() == starsAfter[0],
        "slot ONE étoiles persistées en DB (=" + starsAfter[0] + ")");
    store.close(); db.delete();

    // --- Plan AUTO-LIMITANT (fait §8) : compte SANS ressource → plan vide → no-op, AUCUN débit ---
    ServerUser poor = ServerUser.newPlayer(9303L, 1);
    poor.bootData().userInfo.basicInfo.teamLevel = 200;
    poor.grantHero(HERO, Rarity.YELLOW, 200, 5);
    poor.debugGiveFullGear(HERO);
    // ni matériaux ni or → le plan re-dérivé est VIDE (le jeu ne planifie que le finançable).
    poor.gameUser().setResource(ResourceType.GOLD, 1_000L, "test");
    int matBefore = poor.gameUser().getItemAmount(ItemType.VOID_DUST);
    long goldBefore = poor.gameUser().getResource(ResourceType.GOLD);
    check(!poor.applyMaxPrimeBadge(msg(HERO)), "max-upgrade no-op (plan vide, sans ressource)");
    check(poor.gameUser().getResource(ResourceType.GOLD) == goldBefore, "aucun OR débité (plan vide)");
    check(poor.gameUser().getItemAmount(ItemType.VOID_DUST) == matBefore, "aucun matériau débité (plan vide)");
    check(poor.gameUser().getHero(HERO).getItem(HeroEquipSlot.ONE).getStars() == 0, "aucune étoile (plan vide)");
    System.out.println("[max-upgrade] plan vide (compte sans ressource) : no-op, aucun débit ✔");

    // --- AFFORDABILITÉ PARTIELLE (fait §8) : le plan se plafonne à l'OR disponible (5 M → 3 slots exacts) ---
    ServerUser part = ServerUser.newPlayer(9304L, 1);
    part.bootData().userInfo.basicInfo.teamLevel = 200;
    part.grantHero(HERO, Rarity.YELLOW, 200, 5);
    part.debugGiveFullGear(HERO);
    for (ItemType it : new ItemType[]{ItemType.VOID_DUST, ItemType.SHIMMER_DUST, ItemType.PRIMAL_ESSENCE})
      part.gameUser().addItem(it, 500, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    part.gameUser().setResource(ResourceType.GOLD, 5_000_000L, "test");   // ne finance pas les 6 slots
    var partPlan = EnchantingHelper.buildMaxUpgradePlanForHero(part.gameUser(), HERO, SpecialEventSnapshot.NONE);
    check(partPlan.executionOrder.size() > 0 && partPlan.executionOrder.size() < 6,
        "plan partiel plafonné par l'OR (=" + partPlan.executionOrder.size() + " slots, < 6)");
    check(partPlan.totalGold <= 5_000_000L, "coût du plan partiel <= OR disponible (=" + partPlan.totalGold + ")");
    int partSlots = partPlan.executionOrder.size();
    long partGold0 = part.gameUser().getResource(ResourceType.GOLD);
    check(part.applyMaxPrimeBadge(msg(HERO)), "max-upgrade partiel appliqué");
    var partHero = part.gameUser().getHero(HERO); int partUpgraded = 0;
    for (HeroEquipSlot slot : HeroEquipSlot.values()) {
      var itq = partHero.getItem(slot); if (itq != null && itq.getStars() > 0) partUpgraded++;
    }
    check(partUpgraded == partSlots, "exactement " + partSlots + " slots enchantés (finançables) (vu " + partUpgraded + ")");
    check(part.gameUser().getResource(ResourceType.GOLD) >= 0, "OR non négatif après plan partiel");
    System.out.println("[max-upgrade] affordabilité partielle : " + partSlots + " slots enchantés (OR -"
        + (partGold0 - part.gameUser().getResource(ResourceType.GOLD)) + ") ✔");

    // ===================== PART B : gear RED (slot PRESTO) — enchant simple =====================
    ServerUser red = ServerUser.newPlayer(9302L, 1);
    red.bootData().userInfo.basicInfo.teamLevel = 200;
    red.grantHero(HERO, Rarity.RED, 200, 5);
    red.debugGiveFullGear(HERO);
    var redItem = red.gameUser().getHero(HERO).getItem(HeroEquipSlot.ONE);
    Rarity redR = ItemStats.getRarity(redItem.getType());
    check(redR == Rarity.RED, "slot ONE RED (=" + redItem.getType() + "/" + redR + ")");
    check(EnchantingStats.getMaxStars(redR) == 5, "getMaxStars(RED)=5");
    red.gameUser().addItem(ItemType.VOID_DUST, 40, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    red.gameUser().setResource(ResourceType.GOLD, 5_000_000L, "test");
    EnchantItem ei = new EnchantItem();
    ei.hero = HERO; ei.slot = HeroEquipSlot.ONE; ei.useDiamonds = false;
    Map<ItemType, Integer> used = new HashMap<>(); used.put(ItemType.VOID_DUST, 20); ei.itemsUsed = used;
    int redStars0 = redItem.getStars();
    check(red.applyEnchantItem(ei), "enchant RED appliqué");
    int redStars1 = red.gameUser().getHero(HERO).getItem(HeroEquipSlot.ONE).getStars();
    check(redStars1 > redStars0, "gear RED enchanté (étoiles " + redStars0 + "→" + redStars1 + ")");
    System.out.println("[max-upgrade] RED gear (" + redItem.getType() + ") : étoiles " + redStars0 + "→" + redStars1 + " ✔");

    System.out.println("[max-upgrade] OK — prime badges (max-upgrade 6 slots YELLOW) + gear RED/YELLOW vérifiés (headless).");
  }
}
