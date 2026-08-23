import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.logic.MerchantHelper;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.*;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composants <b>TRADER_DISCOUNT</b> (remise prix d'objet marchand) + <b>TRADER_REFRESH_DISCOUNT</b>
 * (remise coût de refresh). Via la LOGIQUE DU JEU (§3) :
 * (1) TRADER_DISCOUNT : sur un VRAI objet marchand généré (GEAR), {@code MerchantHelper.getItemCost(user, type, item, snapshot)}
 *     renvoie le prix REMISÉ (chemin réel d'{@code applyPurchaseMerchantItem}) ; marchand NON visé (MEMORY) inchangé.
 * (2) TRADER_REFRESH_DISCOUNT : {@code snapshot.getMerchantRefreshPrice(base, merchantType)} remisé ; marchand non visé inchangé.
 * Marchands + % = <b>params ADMIN</b> ({@code AdminEvents --merchant-discount/--merchant-refresh-discount --merchant <TYPE> --percent N}).
 */
public final class MerchantDiscountTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[merchant] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9297L, 1);
    User user = su.gameUser();
    IndividualUser iu = user.getIndividual();
    ServerContext.bind(user, iu);
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // ── TRADER_DISCOUNT (prix d'objet, chemin réel getItemCost) ─────────────────────────────────────────
    MerchantData blob = su.generateMerchant(MerchantType.GEAR);
    iu.initMerchantData(MerchantType.GEAR, blob);
    IMerchantItem item = null;
    for (Object o : iu.getMerchantItems(MerchantType.GEAR)) { item = (IMerchantItem) o; if (item.getCost() > 0) break; }
    check(item != null && item.getCost() > 0, "un objet GEAR à coût > 0 (" + (item == null ? "aucun" : item.getCost()) + ")");
    long itemBase = MerchantHelper.getItemCost(user, MerchantType.GEAR, item, SpecialEventSnapshot.NONE);
    check(itemBase > 0, "coût de base objet GEAR > 0 (" + itemBase + ")");
    System.out.println("[merchant] objet GEAR coût de base = " + itemBase + " ✔");

    SpecialEventInfo evD = ServerEvents.buildMerchantDiscountEvent(700_070L, Collections.singletonList(MerchantType.GEAR), 50, now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(evD));
    SpecialEventSnapshot snap = ServerEvents.snapshot();
    long itemDisc = MerchantHelper.getItemCost(user, MerchantType.GEAR, item, snap);
    check(itemDisc < itemBase, "objet GEAR remisé < base (" + itemDisc + " vs " + itemBase + ")");
    check(itemDisc == itemBase / 2, "objet GEAR = base/2 à −50 % (" + itemDisc + " vs " + (itemBase / 2) + ")");
    System.out.println("[merchant] TRADER_DISCOUNT GEAR −50 % : objet " + itemBase + "→" + itemDisc + " ✔");

    // ── TRADER_REFRESH_DISCOUNT (coût de refresh, getter snapshot) ──────────────────────────────────────
    SpecialEventInfo evR = ServerEvents.buildMerchantRefreshDiscountEvent(700_071L, Collections.singletonList(MerchantType.BLACK_MARKET), 50, now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(evR));
    SpecialEventSnapshot snapR = ServerEvents.snapshot();
    int refreshBase = 1000;
    int refreshBM = snapR.getMerchantRefreshPrice(refreshBase, MerchantType.BLACK_MARKET);
    int refreshGear = snapR.getMerchantRefreshPrice(refreshBase, MerchantType.GEAR);
    check(refreshBM == refreshBase / 2, "refresh BLACK_MARKET = base/2 (" + refreshBM + " vs " + (refreshBase / 2) + ")");
    check(refreshGear == refreshBase, "refresh GEAR (non visé) inchangé (" + refreshGear + ")");
    System.out.println("[merchant] TRADER_REFRESH_DISCOUNT BLACK_MARKET −50 % : refresh " + refreshBase + "→" + refreshBM
        + " | GEAR=" + refreshGear + " (inchangé) ✔");

    // ── Round-trip des specs persistées ────────────────────────────────────────────────────────────────
    String specD = ServerEvents.specJsonMerchant("TRADER_DISCOUNT", 700_070L, Collections.singletonList(MerchantType.GEAR), 50, now - 1000, now + 86_400_000L);
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(ServerEvents.writeConfig(Collections.singletonList(specD)));
    check(rebuilt.size() == 1 && rebuilt.get(0).getID() == 700_070L, "spec TRADER_DISCOUNT round-trip");
    ServerEvents.install(rebuilt);
    long itemDisc2 = MerchantHelper.getItemCost(user, MerchantType.GEAR, item, ServerEvents.snapshot());
    check(itemDisc2 == itemBase / 2, "event reconstruit → même remise objet (" + itemDisc2 + ")");
    System.out.println("[merchant] spec round-trip → remise objet préservée (" + itemDisc2 + ") ✔");

    ServerEvents.install(new ArrayList<>());
    System.out.println("[merchant] OK — TRADER_DISCOUNT + TRADER_REFRESH_DISCOUNT objets du jeu, marchands + % = params admin. [headless]");
  }
}
