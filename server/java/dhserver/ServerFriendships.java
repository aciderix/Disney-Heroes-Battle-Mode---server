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
   * EMPOWER_FRIENDSHIP — monte le niveau d'amitié d'une paire ({@code FriendshipHelper.empowerFriendship} :
   * exige la paire DÉBLOQUÉE ({@code FRIENDSHIP_NOT_UNLOCKED} sinon) + {@code count>=1} + CONSOMME {@code count} ×
   * {@code FRIENDSHIP_EMPOWER_STONE} ({@code UserHelper.useItem}, anti-triche), puis {@code empowerment +=
   * getEmpowermentPerConsumable * count}). Re-synchronise la map des amitiés (empowerment) ; items consommés dans
   * {@code individualUserExtra.items} (write-through). Ne persiste pas (appelant). Renvoie {@code false} si refusé
   * (verrouillé / pierres insuffisantes / count invalide).
   */
  public static boolean applyEmpower(ServerUser su, FriendPairID pair, int count) {
    ServerContext.init();
    User user = su.gameUser();
    // ANTI-TRICHE : le coût du jeu est `count` × FRIENDSHIP_EMPOWER_STONE, consommé par `useItem`→`removeItem` qui
    // NE LÈVE PAS sur stock insuffisant (modèle client-autoritatif, cf. loot). On MIROITE la garde CLIENTE (le client
    // n'active l'empower que si assez de pierres) avec la donnée du jeu `getItemAmount` → refus autoritatif si le
    // stock est insuffisant (empêche un client modifié de sur-empower). Pas une règle inventée : c'est la garde
    // d'entrée que le client applique déjà (§3).
    if (count < 1) return false;
    if (user.getIndividual().getItemAmount(com.perblue.heroes.network.messages.ItemType.FRIENDSHIP_EMPOWER_STONE) < count) {
      System.out.println("[friendship] empower refusé (" + pair + " x" + count + ") : pierres insuffisantes");
      return false;
    }
    try {
      FriendshipHelper.empowerFriendship(user, pair, count);
    } catch (Throwable t) {
      System.out.println("[friendship] empower refusé (" + pair + " x" + count + ") : " + t);
      return false;
    }
    su.resyncFriendships(user.getIndividual());
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
