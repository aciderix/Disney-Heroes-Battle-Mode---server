import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.MerchantHelper;
import com.perblue.heroes.game.logic.MerchantHelper.MerchantRefreshType;
import dhserver.*;
import java.util.*;

/**
 * MERCHANT (#72) incrément 3 — RAFRAÎCHIT le stock ({@code Action REFRESH_TRADER} → {@code MerchantHelper.refresh} +
 * régénération). Le serveur ré-exécute le gating/facture du jeu (quota gratuit/jour ou monnaie payante — anti-triche)
 * puis re-roule le stock. Persistance. Anti-triche : refresh sans monnaie refusé. Corrige l'ancien PARTIEL.
 */
public final class MerchantRefreshTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[merchant-refresh] " + m); }

  static List<ItemType> types(MerchantData d) {
    List<ItemType> l = new ArrayList<>();
    for (Object o : d.inventory) l.add(((MerchantItemData) o).item.itemType);
    return l;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8300L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    MerchantType T = MerchantType.GEAR;
    ResourceType refCur = com.perblue.heroes.game.data.misc.MerchantStats.getRefreshCurrency(T);   // monnaie de refresh (merchants.tab)
    su.generateMerchant(T);
    su.gameUser().setResource(refCur, 5_000_000_000L, "test");

    MerchantData before = su.merchantDataPersisted(T);
    int nBefore = before.inventory.size();
    check(nBefore > 0, "stock initial non vide");
    long curBefore = su.gameUser().getResource(refCur);
    System.out.println("[merchant-refresh] GEAR stock initial " + nBefore + " objets ; monnaie refresh " + refCur + "=" + curBefore);

    // --- REFRESH payant (GEAR = 0 refresh gratuit) ---
    su.applyRefreshMerchant(T, MerchantRefreshType.PAID);
    MerchantData after = su.merchantDataPersisted(T);
    long curAfter = su.gameUser().getResource(refCur);
    check(after != null && after.inventory != null && !after.inventory.isEmpty(), "stock re-généré non vide");
    check(curAfter < curBefore, "monnaie de refresh DÉBITÉE (" + curBefore + "→" + curAfter + ")");
    // Stock frais : tous les objets non achetés.
    int purchased = 0; for (Object o : after.inventory) if (((MerchantItemData) o).purchased) purchased++;
    check(purchased == 0, "stock rafraîchi = tous non achetés (" + purchased + " achetés)");
    System.out.println("[merchant-refresh] refresh PAID : -" + (curBefore - curAfter) + " " + refCur
        + " + stock re-roulé (" + after.inventory.size() + " objets, 0 acheté) ✔");

    // --- Persistance round-trip wire ---
    ServerUser rl = ServerUser.fromWire(8300L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    MerchantData rlAfter = rl.merchantDataPersisted(T);
    check(rlAfter != null && rlAfter.inventory.size() == after.inventory.size(), "stock rafraîchi survit au round-trip wire");
    check(rl.gameUser().getResource(refCur) == curAfter, "solde monnaie survit au round-trip wire");
    System.out.println("[merchant-refresh] persistance (wire) du refresh ✔");

    // --- Anti-triche : refresh sans la monnaie → refusé, aucun changement ---
    su.gameUser().setResource(refCur, 0, "test");
    long balZero = su.gameUser().getResource(refCur);
    List<ItemType> stockBeforeCheat = types(su.merchantDataPersisted(T));
    boolean rejected = false;
    try { su.applyRefreshMerchant(T, MerchantRefreshType.PAID); } catch (Throwable t) { rejected = true; }
    check(rejected, "refresh sans monnaie REFUSÉ");
    check(su.gameUser().getResource(refCur) == balZero, "aucun débit sur refresh refusé");
    check(types(su.merchantDataPersisted(T)).equals(stockBeforeCheat), "stock inchangé après refresh refusé");
    System.out.println("[merchant-refresh] anti-triche : refresh sans monnaie refusé (stock inchangé) ✔");

    System.out.println("[merchant-refresh] OK — refresh (gate/facture serveur + re-roll) + anti-triche + persistance (headless).");
  }
}
