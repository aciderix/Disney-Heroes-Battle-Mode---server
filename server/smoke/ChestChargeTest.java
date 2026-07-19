import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Coffres PAYANTS — vérifie les PIÈCES du débit (monnaie + montant par coffre, via la logique du jeu
 * {@code getPurchaseCurrency}/{@code getPurchaseCost}) et le REJET d'un achat au coût SOUS-DÉCLARÉ (anti-tamper).
 *
 * <p>NB (corrigé) : le débit end-to-end d'un achat payant LÉGITIME est démontré par {@code ChestPaidDebitTest}
 * (GOLD payant, coût correct déclaré → -288 diamants, persiste). Ici on reste sur : (1) les valeurs de coût sont
 * saines ; (2) une ouverture PAYANTE dont le client DÉCLARE un coût de 0 (le champ {@code BuyChests.cost}, 4ᵉ
 * param de {@code validateChestPurchase}) est REJETÉE — le serveur recalcule 10000 > 0 déclaré → ERROR. Ce n'est
 * PAS un verrou de team level : {@code validateChestPurchase} n'a pas de gate TL pour SILVER/GOLD (le verrou du
 * tuto {@code REBLOCK_SILVER_BUY_ONE} est côté CLIENT) — c'est le contrôle anti-tamper coût-déclaré-vs-recalculé.
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

    // 2) ANTI-TAMPER : une ouverture PAYANTE dont le client déclare cost=0 (gratuit consommé) est REFUSÉE.
    u.setResource(ResourceType.GOLD, 5_000_000, "t");
    u.setResource(ResourceType.DIAMONDS, 5000, "t");
    BuyChests free = new BuyChests(); free.chestType = ChestType.SILVER; free.count = 1; free.cost = 0;
    su.openChest(free);   // consomme le gratuit (wasFree=true)
    boolean refused = false;
    // 2ᵉ ouverture : gratuit consommé → payant, mais cost DÉCLARÉ = 0 → serveur recalcule 10000 > 0 → ERROR.
    try { su.openChest(free); } catch (Throwable t) { refused = t.getClass().getSimpleName().contains("ClientErrorCode"); }
    System.out.println("[charge] ouverture PAYANTE SILVER au coût déclaré=0 : " + (refused ? "REFUSÉE (anti-tamper)" : "ACCORDÉE ⚠"));
    if (!refused) throw new AssertionError("un achat payant au coût sous-déclaré (0) doit être REFUSÉ");

    System.out.println("[charge] OK — coûts (monnaie/montant) sains ; achat payant au coût sous-déclaré refusé");
  }

  static void check(com.perblue.heroes.game.objects.User u, ChestType t, ResourceType expCur, int expCost) {
    ResourceType cur = com.perblue.heroes.game.logic.ChestHelper.getPurchaseCurrency(t, SpecialEventSnapshot.NONE);
    int cost = com.perblue.heroes.game.logic.ChestHelper.getPurchaseCost(u, t, 1, SpecialEventSnapshot.NONE);
    System.out.println("[charge] " + t + " : monnaie=" + cur + " coût(1)=" + cost);
    if (cur != expCur) throw new AssertionError(t + " : monnaie attendue " + expCur + ", obtenu " + cur);
    if (cost != expCost) throw new AssertionError(t + " : coût attendu " + expCost + ", obtenu " + cost);
  }
}
