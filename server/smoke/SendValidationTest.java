import com.perblue.heroes.game.logic.ChestHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.BuyChests;
import com.perblue.heroes.network.messages.ChestType;

/**
 * MIROIR DES VALIDATIONS D'ENVOI (#74 B4 — cf. docs/HEADLESS_VERIFICATION.md).
 *
 * <p>Prouve, via l'{@link ClientOracle}, qu'on peut rejouer HEADLESS la validation que le CLIENT exécute AVANT
 * d'émettre une action, sur NOTRE état serveur reconstruit — sans in-game. Exemple de référence (prédicat PUR,
 * sûr) : {@code ChestHelper.validateChestPurchase} (l'anti-triche que le serveur applique déjà dans
 * {@code openChest}). On vérifie les deux verdicts :
 * <ol>
 *   <li>compte NEUF, coffre SILVER gratuit disponible → le client ENVERRAIT (validation passe) ;</li>
 *   <li>après consommation du coffre gratuit (chemin serveur réel), hors cooldown et 0 monnaie → le client
 *       REFUSERAIT (validation lève) = anti-triche : le serveur ne doit pas accorder ce que le client
 *       n'enverrait pas.</li>
 * </ol>
 * Ce test est le patron réutilisable : pour chaque nouvelle action de mode, ajouter un couple
 * {@code assertClientWouldSend}/{@code assertClientWouldRefuse} avec le VRAI prédicat du jeu.
 */
public final class SendValidationTest {

  public static void main(String[] a) throws Exception {
    dhserver.ServerContext.init();
    dhserver.ServerUser u = dhserver.ServerUser.newPlayer(1L, 1);

    // 1) Compte neuf : coffre SILVER GRATUIT dispo (initNewPlayerResources pose SILVER_CHEST=1) → ENVERRAIT.
    //    (5ᵉ arg usedItem = null, 4ᵉ cost = 0 = branche gratuite — exactement ce que openChest passe.)
    ClientOracle.assertClientWouldSend("ouvrir coffre SILVER gratuit", u.gameUser(),
        x -> ChestHelper.validateChestPurchase(x, ChestType.SILVER, 1, 0, null, SpecialEventSnapshot.NONE));

    // 2) Consommer le coffre gratuit par le CHEMIN SERVEUR RÉEL (openChest décrémente la ressource gratuite).
    BuyChests bc = new BuyChests();
    bc.chestType = ChestType.SILVER;
    bc.count = 1;
    bc.cost = 0;
    u.openChest(bc);

    // 3) 2ᵉ ouverture « gratuite » hors cooldown, 0 monnaie → le client REFUSERAIT (pas de coffre gratuit +
    //    pas de quoi payer). Si la validation PASSAIT, ce serait une faille anti-triche.
    ClientOracle.assertClientWouldRefuse(
        "ré-ouvrir coffre SILVER gratuit (hors cooldown, 0 or)", u.gameUser(), "",
        x -> ChestHelper.validateChestPurchase(x, ChestType.SILVER, 1, 0, null, SpecialEventSnapshot.NONE));

    System.out.println("[sendvalidation] miroir OK — envoi légitime ACCEPTÉ, envoi illégitime REFUSÉ (#74 B4)");
  }
}
