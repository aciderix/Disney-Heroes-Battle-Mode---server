import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Smoke test des RESSOURCES d'un compte neuf. Vérifie que l'énergie/stamina d'un nouveau joueur vaut
 * son CAP (120 au niveau d'équipe 1, {@code MAX_STAMINA} de {@code team_levels.tab}) et NON des millions
 * (bug « 39,96 M / 120 » causé par {@code getLastResourceGenerationTime=0} → régen depuis l'époque 1970).
 * Vérifie aussi que la valeur SURVIT au round-trip wire (l'horloge de génération est bien persistée).
 */
public final class ResourceTest {

  static long res(ServerUser u, ResourceType rt) {
    BootData bd = u.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "res-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "res-test");
    ServerContext.bind(user, iu);
    return user.getResource(rt);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);

    long stamina = res(u, ResourceType.STAMINA);
    long gold = res(u, ResourceType.GOLD);
    long diamonds = res(u, ResourceType.DIAMONDS);
    System.out.println("[res] STAMINA=" + stamina + "  GOLD=" + gold + "  DIAMONDS=" + diamonds);

    if (stamina != 120)
      throw new AssertionError("STAMINA attendu 120 (cap niveau 1), obtenu " + stamina);
    if (stamina < 0 || stamina > 1000)
      throw new AssertionError("STAMINA aberrant (régen depuis l'époque ?) : " + stamina);
    if (gold != 0) throw new AssertionError("GOLD d'un compte neuf attendu 0, obtenu " + gold);
    if (diamonds != 0) throw new AssertionError("DIAMONDS d'un compte neuf attendu 0, obtenu " + diamonds);

    // Round-trip wire : l'énergie ne doit pas se ré-gonfler (gen-time persisté).
    ServerUser u2 = ServerUser.fromWire(1L, 1, u.userInfoWire(), u.userExtraWire(), u.individualWire());
    long stamina2 = res(u2, ResourceType.STAMINA);
    if (stamina2 != 120)
      throw new AssertionError("STAMINA après round-trip attendu 120, obtenu " + stamina2);

    System.out.println("RESOURCE TEST OK (STAMINA=120/120 compte neuf, GOLD/DIAMONDS=0, persiste au wire)");
  }
}
