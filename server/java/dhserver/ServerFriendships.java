package dhserver;

import com.perblue.heroes.game.logic.FriendshipHelper;
import com.perblue.heroes.game.objects.FriendPairID;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;

/**
 * FRIENDSHIPS/MISSIONS (#72) — mode « Amitiés ». Cf. docs/FRIENDSHIPS.md.
 *
 * <p><b>Incrément 2</b> — FAVORI + STAMINA d'amitié. Tout par le CODE DU JEU (§3), zéro invention (§4).
 * L'état d'amitié vit dans {@code IndividualUserExtra} (persisté par write-through pour les ressources ;
 * l'ensemble des favoris est un champ à part de {@code IndividualUser} → re-synchronisé, cf. {@code resyncFriendFavorites}).
 *
 * <p>Empower ({@code EMPOWER_FRIENDSHIP}) + campagne d'amitié = incrément 3 (nécessitent la re-sérialisation
 * complète de la map {@code friendships}, partagée avec {@code recordOutcome}).
 */
public final class ServerFriendships {

  private ServerFriendships() {}

  /**
   * SET_FAVORITE_FRIENDSHIP — (dé)favorise une amitié ({@code FriendshipHelper.setFavoritedFriendship} =
   * {@code IndividualUser.setFavoriteFriendship}, aucun verrou). Re-synchronise l'ensemble des favoris vers
   * {@code IndividualUserExtra}. Ne persiste pas (appelant).
   */
  public static boolean applySetFavorite(ServerUser su, FriendPairID pair, boolean favorite) {
    ServerContext.init();
    User user = su.gameUser();
    FriendshipHelper.setFavoritedFriendship(user, pair, favorite);
    su.resyncFriendFavorites(user.getIndividual());
    return true;
  }

  /**
   * BUY_FRIEND_STAMINA — achat d'énergie d'amitié ({@code FriendshipHelper.buyFriendStamina} : débite
   * {@code DIAMONDS}=getFriendStaminaBuyCost, crédite {@code FRIEND_STAMINA}=getFriendStaminaBuyAmount, dans les
   * limites quotidiennes/plafond du jeu). Ne persiste pas (appelant).
   */
  public static boolean applyBuyStamina(ServerUser su) {
    ServerContext.init();
    User user = su.gameUser();
    try { FriendshipHelper.buyFriendStamina(user); }
    catch (Throwable t) { System.out.println("[friendship] buyFriendStamina refusé : " + t); return false; }
    su.resyncDiamonds(user);   // diamants (champ dédié) ; FRIEND_STAMINA vit dans individualUserExtra.resources (write-through)
    return true;
  }
}
