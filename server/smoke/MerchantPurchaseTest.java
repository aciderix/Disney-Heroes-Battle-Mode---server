import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.MerchantHelper;
import dhserver.*;
import java.util.*;

/**
 * MERCHANT (#72) incrément 2 — ACHAT ({@code PurchaseMerchantItem} → {@code MerchantHelper.purchaseItem}). Le serveur
 * ré-exécute la logique du jeu : coût RECALCULÉ serveur (anti-triche, {@code expectedCost} ignoré), débit de la monnaie,
 * don de l'objet, marque {@code purchased} dans le blob. Persistance round-trip. Anti-triche : ré-achat refusé, objet
 * hors stock refusé.
 */
public final class MerchantPurchaseTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[merchant-buy] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8200L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    MerchantType T = MerchantType.GEAR;
    // Génère le stock + provisionne largement la monnaie du marchand (GEAR_TOKENS).
    su.generateMerchant(T);
    ResourceType cur = MerchantHelper.getMerchantPrimaryCurrency(T);
    su.gameUser().setResource(cur, 5_000_000_000L, "test");

    MerchantData blob = su.merchantDataPersisted(T);
    check(blob != null && blob.inventory != null && !blob.inventory.isEmpty(), "stock généré");

    // Choisit l'objet le MOINS CHER (coût > 0, non acheté) de la monnaie primaire, et son typeIndex (rang parmi
    // les objets identiques non achetés) — pour garantir un achat abordable quel que soit le roll.
    MerchantItemData target = null; int typeIndex = 0;
    for (int i = 0; i < blob.inventory.size(); i++) {
      MerchantItemData mid = (MerchantItemData) blob.inventory.get(i);
      if (mid.purchased || mid.cost <= 0 || mid.currency != cur) continue;
      if (target != null && mid.cost >= target.cost) continue;
      int rank = 0;
      for (int j = 0; j < i; j++) {
        MerchantItemData p = (MerchantItemData) blob.inventory.get(j);
        if (!p.purchased && com.perblue.heroes.game.logic.RewardHelper.compareDrops(p.item, mid.item, false)) rank++;
      }
      target = mid; typeIndex = rank;
    }
    check(target != null, "un objet tarifé trouvé");
    ItemType boughtType = target.item.itemType;
    long price = target.cost;
    int qty = (int) Math.max(1L, target.item.quantity);
    int haveBefore = su.gameUser().getItemAmount(boughtType);
    long curBefore = su.gameUser().getResource(cur);
    System.out.println("[merchant-buy] achat " + boughtType + " x" + qty + " coût " + price + " " + cur
        + " (solde " + curBefore + ")");

    // --- ACHAT (coût déclaré CORRECT ; le serveur recalcule et vérifie l'anti-tamper) ---
    PurchaseMerchantItem m = new PurchaseMerchantItem();
    m.merchantType = T; m.itemToPurchase = target.item; m.typeIndex = typeIndex;
    m.expectedCost = price;                 // coût déclaré = coût réel (le client honnête)
    m.expectedQuantity = qty;
    su.applyPurchaseMerchantItem(m);

    long curAfter = su.gameUser().getResource(cur);
    int haveAfter = su.gameUser().getItemAmount(boughtType);
    check(curAfter == curBefore - price, "monnaie débitée du VRAI coût (recalc serveur) : " + curBefore + "→" + curAfter + " (attendu -" + price + ")");
    check(haveAfter == haveBefore + qty, "objet crédité (+" + qty + ") : " + haveBefore + "→" + haveAfter);
    // l'objet est marqué acheté dans le blob (target EST l'objet du blob, muté par le handler)
    check(target.purchased, "objet marqué purchased dans le blob");
    System.out.println("[merchant-buy] débit " + price + " + don " + qty + " " + boughtType + " + purchased=true ✔");

    // --- Anti-triche : ré-achat du MÊME objet → refusé (déjà acheté) ---
    boolean rejected = false;
    try { su.applyPurchaseMerchantItem(m); } catch (Throwable t) { rejected = true; }
    check(rejected, "ré-achat du même objet REFUSÉ (déjà acheté)");
    check(su.gameUser().getResource(cur) == curAfter, "aucun débit sur ré-achat refusé");
    System.out.println("[merchant-buy] anti-triche : ré-achat refusé (aucun débit) ✔");

    // --- Anti-triche : coût DÉCLARÉ falsifié (≠ recalc serveur) sur un autre objet → refusé (anti-tamper) ---
    MerchantItemData other = null; int otherIdx = 0;
    for (int i = 0; i < blob.inventory.size(); i++) {
      MerchantItemData mid = (MerchantItemData) blob.inventory.get(i);
      if (mid.purchased || mid.cost <= 0 || mid.currency != cur) continue;
      int rank = 0;
      for (int j = 0; j < i; j++) { MerchantItemData p = (MerchantItemData) blob.inventory.get(j);
        if (!p.purchased && com.perblue.heroes.game.logic.RewardHelper.compareDrops(p.item, mid.item, false)) rank++; }
      other = mid; otherIdx = rank; break;
    }
    if (other != null) {
      long beforeCheat = su.gameUser().getResource(cur);
      PurchaseMerchantItem cheat = new PurchaseMerchantItem();
      cheat.merchantType = T; cheat.itemToPurchase = other.item; cheat.typeIndex = otherIdx;
      cheat.expectedCost = 1;               // le tricheur déclare 1 alors que le vrai coût est other.cost
      cheat.expectedQuantity = (int) Math.max(1L, other.item.quantity);
      boolean tamperRejected = false;
      try { su.applyPurchaseMerchantItem(cheat); } catch (Throwable t) { tamperRejected = true; }
      check(tamperRejected, "coût falsifié REFUSÉ (anti-tamper CLIENT_OUT_OF_SYNC)");
      check(su.gameUser().getResource(cur) == beforeCheat, "aucun débit sur coût falsifié");
      check(!other.purchased, "objet non marqué acheté après refus anti-tamper");
      System.out.println("[merchant-buy] anti-triche : coût falsifié refusé (aucun débit) ✔");
    }

    // --- Persistance round-trip wire + DB : purchased + soldes ---
    ServerUser rl = ServerUser.fromWire(8200L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    MerchantData rlBlob = rl.merchantDataPersisted(T);
    int purchasedCount = 0; for (Object o : rlBlob.inventory) if (((MerchantItemData) o).purchased) purchasedCount++;
    check(purchasedCount == 1, "1 objet acheté survit au round-trip wire (=" + purchasedCount + ")");
    check(rl.gameUser().getResource(cur) == curAfter, "solde monnaie survit au round-trip wire");
    check(rl.gameUser().getItemAmount(boughtType) == haveAfter, "objet acheté survit au round-trip wire");

    java.io.File db = java.io.File.createTempFile("merchbuy", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8200L, 1);
    int pc2 = 0; for (Object o : fromDb.merchantDataPersisted(T).inventory) if (((MerchantItemData) o).purchased) pc2++;
    check(pc2 == 1 && fromDb.gameUser().getResource(cur) == curAfter, "achat persisté en DB (purchased + solde)");
    store.close(); db.delete();
    System.out.println("[merchant-buy] persistance (wire + DB) : purchased + débit + don ✔");

    System.out.println("[merchant-buy] OK — achat marchand (coût recalc serveur + débit + don + purchased) + anti-triche + persistance (headless).");
  }
}
