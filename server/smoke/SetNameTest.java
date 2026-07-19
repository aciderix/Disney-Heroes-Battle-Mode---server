import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Smoke test de l'étape onboarding « CHOOSE NAME ». Le client envoie {@link SetPlayerName}{@code {name}}
 * (fire-and-forget) ; le serveur exécute la logique du jeu ({@code UserHelper.changeName}) et re-sync le nom
 * vers le wire. Vérifie que le nom est bien appliqué ET qu'il SURVIT au round-trip wire (persistance §6).
 */
public final class SetNameTest {

  static String name(ServerUser u) {
    BootData bd = u.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "name-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "name-test");
    ServerContext.bind(user, iu);
    return user.getName();
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);
    System.out.println("[setname] nom initial = '" + name(u) + "'");

    SetPlayerName spn = new SetPlayerName();
    spn.name = "HeroTester";
    boolean applied = u.setPlayerName(spn);
    System.out.println("[setname] setPlayerName appliqué=" + applied);
    if (!applied) throw new AssertionError("setPlayerName a échoué");

    // Le nom doit être visible dans le wire (basicInfo) ET après reconstruction d'un User de jeu.
    BootData bd = u.bootData();
    if (bd.userInfo.basicInfo == null || !"HeroTester".equals(bd.userInfo.basicInfo.name))
      throw new AssertionError("wire basicInfo.name attendu 'HeroTester', obtenu "
          + (bd.userInfo.basicInfo == null ? "null" : "'" + bd.userInfo.basicInfo.name + "'"));
    String reloaded = name(u);
    System.out.println("[setname] nom après round-trip wire = '" + reloaded + "'");
    if (!"HeroTester".equals(reloaded))
      throw new AssertionError("nom perdu au round-trip : '" + reloaded + "'");

    System.out.println("[setname] OK");
  }
}
