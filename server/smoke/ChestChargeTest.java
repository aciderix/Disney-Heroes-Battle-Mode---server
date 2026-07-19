import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Coffres PAYANTS — vérifie les PIÈCES du débit (monnaie + montant par coffre, via la logique du jeu
 * {@code getPurchaseCurrency}/{@code getPurchaseCost}) et le REJET TL1. Contexte : à team level 1 (tuto), le
 * jeu REFUSE toute ouverture payante (limite d'achats / feature verrouillée / monnaie) — donc le débit
 * ({@code openChest}, branche « payant ») n'est atteint QUE pour une ouverture payante LÉGITIME (niveau plus
 * élevé + monnaie + feature débloquée), gardé derrière {@code validateChestPurchase}. Ici on certifie que
 * (1) les valeurs de coût sont saines et (2) le serveur REJETTE bien un achat payant illégitime à TL1.
 */
public final class ChestChargeTest {

  static com.perblue.heroes.game.objects.User bind(ServerUser su) {
    BootData bd = su.bootData();
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "cc");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(bd.individualUserExtra, 1L, bd.userInfo.diamonds, "cc");
    ServerContext.bind(u, iu); return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    var u = bind(su);

    // 1) Monnaie + montant de coût par coffre (logique du jeu que le débit utilise). Valeurs attendues (data).
    check(u, ChestType.SILVER, ResourceType.GOLD, 10000);
    check(u, ChestType.GOLD,   ResourceType.DIAMONDS, 288);
    check(u, ChestType.SOUL,   ResourceType.DIAMONDS, 74);

    // 2) REJET TL1 : même avec la monnaie, une ouverture PAYANTE (gratuit consommé) est refusée (enforcement).
    u.setResource(ResourceType.GOLD, 5_000_000, "t");
    u.setResource(ResourceType.DIAMONDS, 5000, "t");
    BuyChests free = new BuyChests(); free.chestType = ChestType.SILVER; free.count = 1;
    su.openChest(free);   // consomme le gratuit
    boolean refused = false;
    try { su.openChest(free); } catch (Throwable t) { refused = t.getClass().getSimpleName().contains("ClientErrorCode"); }
    System.out.println("[charge] ouverture PAYANTE SILVER à TL1 (avec GOLD) : " + (refused ? "REFUSÉE (correct)" : "ACCORDÉE ⚠"));
    if (!refused) throw new AssertionError("un achat payant illégitime à TL1 doit être REFUSÉ");

    System.out.println("[charge] OK — coûts (monnaie/montant) sains ; achat payant illégitime refusé (TL1)");
  }

  static void check(com.perblue.heroes.game.objects.User u, ChestType t, ResourceType expCur, int expCost) {
    ResourceType cur = com.perblue.heroes.game.logic.ChestHelper.getPurchaseCurrency(t, SpecialEventSnapshot.NONE);
    int cost = com.perblue.heroes.game.logic.ChestHelper.getPurchaseCost(u, t, 1, SpecialEventSnapshot.NONE);
    System.out.println("[charge] " + t + " : monnaie=" + cur + " coût(1)=" + cost);
    if (cur != expCur) throw new AssertionError(t + " : monnaie attendue " + expCur + ", obtenu " + cur);
    if (cost != expCost) throw new AssertionError(t + " : coût attendu " + expCost + ", obtenu " + cost);
  }
}
