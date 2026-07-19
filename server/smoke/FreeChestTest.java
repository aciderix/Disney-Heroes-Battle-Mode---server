import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Vérifie que l'ouverture d'un coffre GRATUIT le CONSOMME (fidélité — gap trouvé en jeu 2026-07-19 :
 * « FREE NOW » restait dispo après ouverture, coffre gratuit « farmable »). Le coffre gratuit est une
 * {@code ResourceType} RÉGÉNÉRÉE ({@code ChestHelper.getFreeChestResource}) ; l'ouvrir doit la décrémenter →
 * {@code hasFreeChest=false} (cooldown « Free in 23h ») puis régénération. Vérifie aussi que ça N'affecte PAS
 * les AUTRES coffres, et que l'état SURVIT au round-trip wire.
 */
public final class FreeChestTest {

  static boolean hasFree(ServerUser su, ChestType t) {
    BootData bd = su.bootData();
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "fc");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(bd.individualUserExtra, 1L, bd.userInfo.diamonds, "fc");
    ServerContext.bind(u, iu);
    return com.perblue.heroes.game.logic.ChestHelper.hasFreeChest(u, t, SpecialEventSnapshot.NONE, 1);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    if (!hasFree(su, ChestType.SILVER)) throw new AssertionError("compte neuf : coffre SILVER gratuit attendu dispo");
    if (!hasFree(su, ChestType.GOLD))   throw new AssertionError("compte neuf : coffre GOLD gratuit attendu dispo");

    BuyChests bc = new BuyChests(); bc.chestType = ChestType.SILVER; bc.count = 1;
    su.openChest(bc);

    boolean silverAfter = hasFree(su, ChestType.SILVER);
    boolean goldAfter = hasFree(su, ChestType.GOLD);
    System.out.println("[freechest] après openChest(SILVER) : hasFree(SILVER)=" + silverAfter + " hasFree(GOLD)=" + goldAfter);
    if (silverAfter) throw new AssertionError("le coffre GRATUIT SILVER n'a PAS été consommé (farmable) — gap");
    if (!goldAfter)  throw new AssertionError("l'ouverture SILVER ne doit PAS affecter le coffre GOLD");

    // Persistance : l'état de consommation survit au round-trip wire (la ressource vit dans this.extra).
    if (hasFree(su, ChestType.SILVER)) throw new AssertionError("consommation SILVER perdue au round-trip wire");
    System.out.println("[freechest] OK — coffre gratuit consommé (cooldown), autres coffres intacts, persiste");
  }
}
