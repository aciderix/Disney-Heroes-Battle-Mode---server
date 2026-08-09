import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * CHALLENGES (#72) incrément 3 — ÉCONOMIE stickers (achats + favori). Tout par le CODE DU JEU (§3), zéro invention
 * (§4) : {@code StickerHelper.purchaseSlot}/{@code purchaseBook}/{@code purchaseSticker} (débit DIAMONDS autoritatif),
 * {@code User.setFavoriteSticker} (persisté dans UserExtra). Vérifie flags/débit/persistance DB.
 */
public final class ChallengeShopTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[challenge-shop] " + m); }
  static long diamonds(ServerUser su) { return su.gameUser().getResource(ResourceType.DIAMONDS); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9101L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.giveResource(ResourceType.DIAMONDS, 10000);

    // --- BUY SLOT : déverrouille CHALLENGE_SLOT_2 (débit DIAMONDS = getSlotCost) ---
    check(!com.perblue.heroes.game.logic.StickerHelper.hasPurchasedSlot2(su.gameUser()), "slot 2 non acheté au départ");
    long d0 = diamonds(su);
    int slotCost = com.perblue.heroes.game.data.stickerbook.StickerChallengeStats.getSlotCost(ChallengeSlots.NORMAL_2);
    boolean slotOk = ServerChallenges.applyBuySlot(su, ChallengeSlots.NORMAL_2);
    check(slotOk, "applyBuySlot doit réussir");
    check(com.perblue.heroes.game.logic.StickerHelper.hasPurchasedSlot2(su.gameUser()), "CHALLENGE_SLOT_2 posé après achat");
    check(d0 - diamonds(su) == slotCost, "DIAMONDS débités = getSlotCost=" + slotCost + " (obtenu " + (d0 - diamonds(su)) + ")");
    check(!ServerChallenges.applyBuySlot(su, ChallengeSlots.NORMAL_2), "anti-double : slot déjà acheté refusé");
    System.out.println("[challenge-shop] buySlot: CHALLENGE_SLOT_2 posé, -" + slotCost + " diamants");

    // --- SET FAVORITE : posé dans UserExtra (persisté) ---
    boolean favOk = ServerChallenges.applySetFavorite(su, StickerType.TO_CATCH_A_STAR);
    check(favOk && su.favoriteSticker() == StickerType.TO_CATCH_A_STAR, "favori posé = TO_CATCH_A_STAR");
    System.out.println("[challenge-shop] setFavorite: " + su.favoriteSticker());

    // --- BUY BOOK : livre PAYANT CITY_PATROL (débit DIAMONDS = coût remisé, purchaseTime des stickers) ---
    long d1 = diamonds(su);
    boolean bookOk = ServerChallenges.applyBuyBook(su, StickerBookType.CITY_PATROL);
    check(bookOk, "applyBuyBook(CITY_PATROL) doit réussir");
    check(diamonds(su) < d1, "DIAMONDS débités pour le livre (avant=" + d1 + " après=" + diamonds(su) + ")");
    UserChallengeDataExtra d = su.challengeDataOrNull();
    check(d != null && d.purchaseTime != null && !d.purchaseTime.isEmpty(),
        "purchaseTime posé pour les stickers du livre (obtenu " + (d==null?null:d.purchaseTime) + ")");
    System.out.println("[challenge-shop] buyBook CITY_PATROL: -" + (d1 - diamonds(su)) + " diamants, "
        + d.purchaseTime.size() + " stickers marqués achetés");

    // --- PERSISTANCE DB : flag + favori + achats survivent ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-challenge-shop-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(9101L, 1);
    check(rl != null, "joueur relu");
    check(com.perblue.heroes.game.logic.StickerHelper.hasPurchasedSlot2(rl.gameUser()), "CHALLENGE_SLOT_2 survit à la DB");
    check(rl.favoriteSticker() == StickerType.TO_CATCH_A_STAR, "favori survit à la DB");
    UserChallengeDataExtra dr = rl.challengeDataOrNull();
    check(dr != null && dr.purchaseTime != null && !dr.purchaseTime.isEmpty(), "achats du livre survivent à la DB");
    WireCheck.assertRoundTrips(dr);
    store.close();

    System.out.println("[challenge-shop] OK — buySlot/setFavorite/buyBook + persistance via le code du jeu — #72 incrément 3");
  }
}
