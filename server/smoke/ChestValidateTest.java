import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Anti-triche coffres : le serveur VALIDE l'achat (ChestHelper.validateChestPurchase) AVANT d'accorder.
 * Vérifie que (1) une ouverture GRATUITE légitime PASSE (coffre accordé), et (2) une 2ᵉ ouverture APRÈS
 * consommation (coffre gratuit hors cooldown + 0 monnaie) est REFUSÉE (ClientErrorCodeException levée →
 * aucun coffre accordé). C'est l'enforcement du cooldown 24h : la dispo est calculée sur l'ÉTAT SERVEUR
 * (ressource + horodatage régén persistés) avec l'HORLOGE DU SERVEUR → l'heure du mobile ne contourne rien.
 */
public final class ChestValidateTest {

  static LootResults open(ServerUser su, ChestType t) {
    BuyChests bc = new BuyChests(); bc.chestType = t; bc.count = 1;
    return su.openChest(bc);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    // 1) Ouverture GRATUITE légitime → doit RÉUSSIR.
    LootResults lr = open(su, ChestType.SILVER);
    if (lr == null) throw new AssertionError("ouverture gratuite légitime : LootResults null");
    System.out.println("[validate] ouverture GRATUITE SILVER : accordée (wasFree=" + lr.wasFree + ")");
    if (!lr.wasFree) throw new AssertionError("1ère ouverture attendue GRATUITE");

    // 2) 2ᵉ ouverture : coffre gratuit consommé (hors cooldown) + 0 monnaie → doit être REFUSÉE.
    boolean refused = false; String reason = null;
    try {
      open(su, ChestType.SILVER);
    } catch (Throwable t) {
      // ClientErrorCodeException (RuntimeException du jeu) attendue.
      if (t.getClass().getSimpleName().contains("ClientErrorCode")) { refused = true; reason = t.getMessage(); }
      else throw new AssertionError("exception inattendue au 2ᵉ open : " + t);
    }
    System.out.println("[validate] 2ᵉ ouverture SILVER (hors cooldown, 0 monnaie) : "
        + (refused ? "REFUSÉE (" + reason + ")" : "ACCORDÉE ⚠"));
    if (!refused) throw new AssertionError("le serveur a accordé un coffre illégitime (anti-triche KO)");

    // Le refus ne doit pas avoir corrompu l'état (compte toujours cohérent) : GOLD gratuit encore ouvrable.
    LootResults g = open(su, ChestType.GOLD);
    if (g == null) throw new AssertionError("après un refus, l'ouverture GOLD légitime doit encore marcher");
    System.out.println("[validate] ouverture GRATUITE GOLD après refus : accordée (wasFree=" + g.wasFree + ")");

    System.out.println("[validate] OK — ouverture légitime accordée, ouverture illégitime refusée, état sain");
  }
}
