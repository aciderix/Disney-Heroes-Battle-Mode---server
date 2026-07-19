import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.game.logic.ChestHelper;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Coffres PAYANTS — DÉMONSTRATION END-TO-END DU DÉBIT (demande user : « vérifier les coffres payants avec des
 * team levels plus hauts pour vérifier tes dires »).
 *
 * <p>Découverte (relevée au bytecode, {@code ChestHelper.openChestInner}) : le 4ᵉ paramètre de
 * {@code validateChestPurchase} = <b>le coût déclaré par le client</b> ({@code BuyChests.cost}), pas 0. La branche
 * PAYANTE fait {@code if (coûtRecalculéServeur > coûtDéclaréClient) throw ERROR} (ANTI-TAMPER). Le serveur passait
 * 0 en dur → tout achat payant levait {@code ERROR} ({@code 288 > 0}) → le débit n'était jamais atteignable. Le
 * team level n'était PAS le blocage : {@code getMaxChestPurchases(GOLD)} = -1 (achats ILLIMITÉS). Fix : passer
 * {@code m.cost} (miroir exact du client).
 *
 * <p>Ce test, à team level 40, (1) consomme le coffre GOLD gratuit, (2) ouvre un GOLD PAYANT avec le coût correct
 * déclaré → le débit DIAMANTS a lieu (exactement le coût), persiste au round-trip wire ; (3) rejette un achat au
 * coût SOUS-DÉCLARÉ (anti-tamper).
 */
public final class ChestPaidDebitTest {

  static com.perblue.heroes.game.objects.User bind(ServerUser su) {
    BootData bd = su.bootData();
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "cc");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "cc");
    ServerContext.bind(u, iu); return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    BootData bd = su.bootData();

    // Team level ÉLEVÉ (40) — pour vérifier que ce n'est pas le blocage (GOLD = achats illimités).
    bd.userInfo.basicInfo.teamLevel = 40;
    // Diamants de départ (champ dédié userInfo.diamonds ; openChest rebind lit ce champ).
    bd.userInfo.diamonds = 100_000;

    var u = bind(su);
    int cost = ChestHelper.getPurchaseCost(u, ChestType.GOLD, 1, SpecialEventSnapshot.NONE);
    ResourceType cur = ChestHelper.getPurchaseCurrency(ChestType.GOLD, SpecialEventSnapshot.NONE);
    System.out.println("[paid] team level=" + bd.userInfo.basicInfo.teamLevel
        + " | GOLD : monnaie=" + cur + " coût(1)=" + cost);
    if (cur != ResourceType.DIAMONDS) throw new AssertionError("GOLD devrait coûter des DIAMONDS");

    // 1) Consommer le coffre GOLD GRATUIT (open #1, gratuit → wasFree=true → pas de débit).
    BuyChests free = new BuyChests();
    free.chestType = ChestType.GOLD; free.count = 1; free.cost = 0;
    LootResults r1 = su.openChest(free);
    System.out.println("[paid] open #1 (gratuit) : wasFree=" + r1.wasFree);
    if (!r1.wasFree) throw new AssertionError("le 1er GOLD d'un compte neuf doit être GRATUIT");

    // 2) OUVERTURE PAYANTE avec le coût CORRECT déclaré → doit débiter exactement `cost` diamants.
    var u2 = bind(su);
    if (ChestHelper.hasFreeChest(u2, ChestType.GOLD, null, 1))
      throw new AssertionError("le coffre gratuit aurait dû être consommé");
    int costNow = ChestHelper.getPurchaseCost(u2, ChestType.GOLD, 1, SpecialEventSnapshot.NONE);
    long diamAvant = bd.userInfo.diamonds;
    BuyChests paid = new BuyChests();
    paid.chestType = ChestType.GOLD; paid.count = 1; paid.cost = costNow;   // coût DÉCLARÉ = coût réel
    LootResults r2 = su.openChest(paid);
    long diamApres = bd.userInfo.diamonds;
    System.out.println("[paid] open #2 (PAYANT, coût déclaré=" + costNow + ") : wasFree=" + r2.wasFree
        + " | diamants " + diamAvant + " → " + diamApres + " (débit=" + (diamAvant - diamApres) + ")");
    if (r2.wasFree) throw new AssertionError("open #2 aurait dû être PAYANT (gratuit consommé)");
    if (diamAvant - diamApres != costNow)
      throw new AssertionError("débit attendu " + costNow + ", obtenu " + (diamAvant - diamApres));

    // 2b) PERSISTANCE : le débit survit au round-trip wire (userInfo re-sérialisé/relu).
    byte[] wire = su.userInfoWire();
    UserInfo reloaded = (UserInfo) com.perblue.heroes.network.messages.MessageFactory.getInstance()
        .readMessage(new com.perblue.grunt.translate.util.GruntInputStream(wire));
    System.out.println("[paid] persistance wire : diamants relus=" + reloaded.diamonds);
    if (reloaded.diamonds != diamApres)
      throw new AssertionError("diamants non persistés : wire=" + reloaded.diamonds + " attendu " + diamApres);

    // 3) ANTI-TAMPER : coût SOUS-DÉCLARÉ (client prétend payer moins) → REFUSÉ (ERROR).
    var u3 = bind(su);
    int costCheat = ChestHelper.getPurchaseCost(u3, ChestType.GOLD, 1, SpecialEventSnapshot.NONE) - 1;
    long diamAvantCheat = bd.userInfo.diamonds;
    BuyChests cheat = new BuyChests();
    cheat.chestType = ChestType.GOLD; cheat.count = 1; cheat.cost = costCheat;   // coût MENTI (trop bas)
    boolean refused = false;
    try { su.openChest(cheat); }
    catch (Throwable t) { refused = t.getClass().getName().contains("ClientErrorCode")
        || (t.getCause() != null && t.getCause().getClass().getName().contains("ClientErrorCode")); }
    long diamApresCheat = bd.userInfo.diamonds;
    System.out.println("[paid] open #3 (coût SOUS-DÉCLARÉ=" + costCheat + ") : "
        + (refused ? "REFUSÉ (anti-tamper OK)" : "ACCORDÉ ⚠") + " | diamants inchangés=" + (diamAvantCheat == diamApresCheat));
    if (!refused) throw new AssertionError("un coût sous-déclaré doit être REFUSÉ (anti-tamper)");
    if (diamAvantCheat != diamApresCheat) throw new AssertionError("aucun débit sur un achat refusé");

    System.out.println("[paid] OK — débit PAYANT end-to-end démontré (TL40) + persistance + anti-tamper");
  }
}
