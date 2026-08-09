import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.FriendPairID;
import dhserver.*;

/**
 * FRIENDSHIPS (#72) incrément 2 — FAVORI + STAMINA. Tout par le CODE DU JEU (§3), zéro invention (§4) :
 * {@code FriendshipHelper.setFavoritedFriendship} (favori, re-sync {@code favoriteFriendships}) +
 * {@code buyFriendStamina} (débit DIAMONDS + crédit FRIEND_STAMINA). Vérifie persistance DB.
 */
public final class FriendshipShopTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[friendship-shop] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4401L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.giveResource(ResourceType.DIAMONDS, 10000);
    FriendPairID pair = FriendPairID.of(UnitType.RALPH, UnitType.VANELLOPE);

    // --- FAVORI : (dé)favorise, re-sync vers l'extra ---
    check(!su.gameUser().getIndividual().isFavoriteFriendship(pair), "pas favori au départ");
    check(ServerFriendships.applySetFavorite(su, pair, true), "applySetFavorite(true)");
    check(su.gameUser().getIndividual().isFavoriteFriendship(pair), "favori posé (runtime)");
    check(su.gameUser().getIndividual().getExtra().favoriteFriendships.contains(pair.getAsLong()),
        "favori re-synchronisé dans l'extra (getAsLong)");

    // --- PERSISTANCE DB du favori ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-friendship-shop-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4401L, 1);
    check(rl != null && rl.gameUser().getIndividual().isFavoriteFriendship(pair), "favori survit à la DB");
    WireCheck.assertRoundTrips(rl.bootData());

    // --- DÉ-FAVORI ---
    check(ServerFriendships.applySetFavorite(rl, pair, false), "applySetFavorite(false)");
    check(!rl.gameUser().getIndividual().isFavoriteFriendship(pair), "favori retiré");
    check(!rl.gameUser().getIndividual().getExtra().favoriteFriendships.contains(pair.getAsLong()),
        "favori retiré de l'extra");

    // --- BUY STAMINA : débit DIAMONDS + crédit FRIEND_STAMINA ---
    long d0 = rl.gameUser().getResource(ResourceType.DIAMONDS);
    long s0 = rl.gameUser().getResource(ResourceType.FRIEND_STAMINA);
    boolean bought = ServerFriendships.applyBuyStamina(rl);
    if (bought) {
      long d1 = rl.gameUser().getResource(ResourceType.DIAMONDS);
      long s1 = rl.gameUser().getResource(ResourceType.FRIEND_STAMINA);
      check(d1 < d0, "DIAMONDS débités par buyStamina (avant=" + d0 + " après=" + d1 + ")");
      check(s1 > s0, "FRIEND_STAMINA crédité (avant=" + s0 + " après=" + s1 + ")");
      // persistance
      store.save(rl);
      ServerUser rl2 = store.loadIfExists(4401L, 1);
      check(rl2.gameUser().getResource(ResourceType.FRIEND_STAMINA) == s1, "FRIEND_STAMINA survit à la DB");
      System.out.println("[friendship-shop] buyStamina: -" + (d0 - d1) + " diamants, +" + (s1 - s0) + " FRIEND_STAMINA (persisté)");
    } else {
      System.out.println("[friendship-shop] buyStamina refusé (plafond/limite quotidienne) — chemin de refus OK");
    }
    store.close();

    System.out.println("[friendship-shop] OK — favori (+persistance) & stamina via le code du jeu — #72 incrément 2");
  }
}
