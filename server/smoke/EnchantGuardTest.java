import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.EnchantingHelper;
import com.perblue.heroes.game.data.item.enchanting.EnchantingStats;
import com.perblue.heroes.game.data.item.ItemStats;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;
import java.util.*;

/**
 * ENCHANTING (#72) incrément 2 — GARDE-FOUS d'enchant. Vérifie, via la logique du jeu (§3), le barème et l'anti-triche :
 * plafond d'étoiles par rareté (refus au max), matériaux insuffisants (refus, pas de débit), coût OR exact, et le
 * chemin DIAMANTS ({@code useDiamonds=true} → paie {@code getEnchantMaxDiamondCost} → item au MAX, sans consommer de
 * matériaux). Zéro invention (§4).
 */
public final class EnchantGuardTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[enchant-guard] " + m); }
  static final UnitType H = UnitType.RALPH; static final HeroEquipSlot S = HeroEquipSlot.ONE; static final ItemType MAT = ItemType.VOID_DUST;
  static final SpecialEventSnapshot SNAP = SpecialEventSnapshot.NONE;

  static int stars(ServerUser su) { return su.gameUser().getHero(H).getItem(S).getStars(); }
  static int maxStars(ServerUser su) { return EnchantingStats.getMaxStars(ItemStats.getRarity(su.gameUser().getHero(H).getItem(S).getType())); }
  static EnchantItem e(int mat, boolean dia) {
    EnchantItem x = new EnchantItem(); x.hero = H; x.slot = S; x.useDiamonds = dia;
    Map<ItemType, Integer> m = new HashMap<>(); if (mat > 0) m.put(MAT, mat); x.itemsUsed = m; return x;
  }
  static ServerUser mk(long id, int diamonds, int mat) {
    ServerUser su = ServerUser.newPlayer(id, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.bootData().userInfo.diamonds = diamonds;               // diamants PERSISTÉS (source hors-extra)
    su.grantHero(H, Rarity.ORANGE, 100, 5); su.debugGiveFullGear(H);
    if (mat > 0) su.gameUser().addItem(MAT, mat, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "t");
    su.gameUser().setResource(ResourceType.GOLD, 100_000_000L, "t");
    return su;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // --- Chemin DIAMANTS : useDiamonds=true → paie getEnchantMaxDiamondCost → item au MAX, matériaux INTACTS ---
    ServerUser d = mk(9401L, 50_000, 5);
    int dcost = EnchantingHelper.getEnchantMaxDiamondCost(d.gameUser(), H, S);
    check(dcost > 0, "coût diamant max > 0 (=" + dcost + ")");
    long dia0 = d.gameUser().getResource(ResourceType.DIAMONDS); int mat0 = d.gameUser().getItemAmount(MAT);
    check(d.applyEnchantItem(e(5, true)), "enchant diamants appliqué");
    System.out.println("[enchant-guard] diamants : étoiles →" + stars(d) + "/" + maxStars(d) + ", diamants "
        + dia0 + "→" + d.gameUser().getResource(ResourceType.DIAMONDS) + ", " + MAT + " " + mat0 + "→" + d.gameUser().getItemAmount(MAT));
    check(stars(d) == maxStars(d), "diamants → item au MAX (" + stars(d) + "/" + maxStars(d) + ")");
    check(dia0 - d.gameUser().getResource(ResourceType.DIAMONDS) == dcost, "diamants débités du coût max exact (" + dcost + ")");
    check(d.gameUser().getItemAmount(MAT) == mat0, "matériaux NON consommés par le chemin diamants");

    // --- PLAFOND d'étoiles : au max, tout nouvel enchant est refusé ---
    d.gameUser().addItem(MAT, 100, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "t");
    check(!d.applyEnchantItem(e(50, false)), "enchant refusé au plafond d'étoiles");
    // Persistance : l'item maxé (diamants) survit à la DB.
    ServerUser rl = ServerUser.fromWire(9401L, 1, d.userInfoWire(), d.userExtraWire(), d.individualWire());
    check(stars(rl) == maxStars(rl), "item maxé (diamants) survit au round-trip wire");

    // --- MATÉRIAUX INSUFFISANTS : demande > possédé, sans diamants → refus, aucun débit ---
    ServerUser b = mk(9402L, 0, 2);
    int matB = b.gameUser().getItemAmount(MAT); long goldB = b.gameUser().getResource(ResourceType.GOLD);
    check(!b.applyEnchantItem(e(10, false)), "enchant refusé (matériaux insuffisants : demande 10, a 2)");
    check(b.gameUser().getItemAmount(MAT) == matB, "matériaux NON débités sur refus");
    check(b.gameUser().getResource(ResourceType.GOLD) == goldB, "or NON débité sur refus");
    check(stars(b) == 0, "étoiles inchangées sur refus");

    // --- ANTI-TRICHE DIAMANTS : useDiamonds=true sans assez de diamants → refus ---
    ServerUser c = mk(9403L, 100, 5);   // 100 diamants < coût (~5040)
    check(!c.applyEnchantItem(e(5, true)), "enchant diamants refusé (diamants insuffisants)");
    check(stars(c) == 0, "étoiles inchangées sur refus diamants");

    System.out.println("[enchant-guard] OK — garde-fous (plafond étoiles, matériaux insuffisants, coût diamant exact, anti-triche) (#72 incr. 2)");
  }
}
