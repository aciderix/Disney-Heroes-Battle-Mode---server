import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.MerchantHelper;
import dhserver.*;
import java.util.*;

/**
 * MERCHANT (#72) incrément 1 — GÉNÉRATION du stock d'un marchand (blob serveur-autoritatif). Le serveur roule la vraie
 * table du jeu ({@code MerchantStats.<TYPE>_DROP_STATS} via {@code ServerUser.generateMerchant}) : objets + coût de base
 * = prix {@code items.tab} (via {@code ItemStats.getStat}) × quantité, monnaie du marchand. Stockage write-through
 * ({@code individualUserExtra.merchantData}) → persistance round-trip wire + DB. Zéro invention (§3/§4).
 */
public final class MerchantGenTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[merchant] " + m); }

  static int count(MerchantData d) { return d == null || d.inventory == null ? 0 : d.inventory.size(); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8100L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    MerchantType T = MerchantType.GEAR;
    check(MerchantHelper.isAvailable(su.gameUser(), T), "marchand GEAR disponible");
    check(su.merchantDataPersisted(T) == null, "aucun stock avant génération");

    // --- Génère le stock ---
    MerchantData data = su.generateMerchant(T);
    check(data != null && data.inventory != null && !data.inventory.isEmpty(), "stock généré non vide");
    int n = data.inventory.size();
    System.out.println("[merchant] GEAR généré : " + n + " objets");

    // Invariant : le coût de CHAQUE objet == prix items.tab (ItemStats.getStat) × quantité (0 possible pour un
    // upsell/objet sans prix). Et la MAJORITÉ des objets a un coût > 0 (vraie boutique).
    int withCost = 0;
    for (Object o : data.inventory) {
      MerchantItemData mid = (MerchantItemData) o;
      check(mid.item != null && mid.item.itemType != null && mid.item.itemType != ItemType.DEFAULT, "objet a un itemType");
      check(mid.currency != null && mid.currency != ResourceType.DEFAULT, "objet a une monnaie");
      check(!mid.purchased, "objet non acheté à la génération");
      com.perblue.heroes.game.data.item.StatType ps = mid.currency == ResourceType.GOLD
          ? com.perblue.heroes.game.data.item.StatType.GOLD_PRICE
          : mid.currency == ResourceType.DIAMONDS ? com.perblue.heroes.game.data.item.StatType.DIAMOND_PRICE
          : com.perblue.heroes.game.data.item.StatType.TOKEN_PRICE;
      long expect = (long) com.perblue.heroes.game.data.item.ItemStats.getStat(mid.item.itemType, ps) * Math.max(1, mid.item.quantity);
      check(mid.cost == expect, "coût = prix items.tab × qté (" + mid.item.itemType + " attendu " + expect + " obtenu " + mid.cost + ")");
      if (mid.cost > 0) withCost++;
    }
    check(withCost >= n - 2, "la majorité des objets sont tarifés (>0) : " + withCost + "/" + n + " (0 = upsells sans prix)");
    MerchantItemData s0 = (MerchantItemData) data.inventory.get(0);
    System.out.println("[merchant] coûts = prix items.tab × qté (" + withCost + "/" + n + " tarifés ; ex. "
        + s0.item.itemType + " " + s0.cost + " " + s0.currency + ") ✔");

    // --- Persistance : round-trip wire ---
    ServerUser rl = ServerUser.fromWire(8100L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    MerchantData rd = rl.merchantDataPersisted(T);
    check(count(rd) == n, "stock survit au round-trip wire (" + count(rd) + " vs " + n + ")");
    MerchantItemData r0 = (MerchantItemData) rd.inventory.get(0);
    check(r0.cost == s0.cost && r0.item.itemType == s0.item.itemType && r0.currency == s0.currency,
        "1er objet identique après round-trip wire (coût/type/monnaie)");

    // --- Persistance : DB ---
    java.io.File db = java.io.File.createTempFile("merch", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8100L, 1);
    check(fromDb != null && count(fromDb.merchantDataPersisted(T)) == n, "stock persisté en DB (" + count(fromDb.merchantDataPersisted(T)) + ")");
    store.close(); db.delete();
    System.out.println("[merchant] persistance du stock (wire + DB) : " + n + " objets ✔");

    // --- Un 2e marchand coexiste (MEMORY) ---
    su.generateMerchant(MerchantType.MEMORY);
    check(count(su.merchantDataPersisted(MerchantType.MEMORY)) > 0, "2e marchand (MEMORY) coexiste");
    check(count(su.merchantDataPersisted(T)) == n, "GEAR intact après génération MEMORY");
    System.out.println("[merchant] 2 marchands coexistent (GEAR + MEMORY) ✔");

    System.out.println("[merchant] OK — génération de stock (roll + coûts items.tab) + persistance blob (headless).");
  }
}
