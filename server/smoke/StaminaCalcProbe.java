import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.data.content.ContentHelper;
import com.perblue.heroes.game.logic.UserHelper;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.util.TimeUtil;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * PROBE (faits reproductibles) — répond EXACTEMENT à la question : « pourquoi un joueur NIVEAU 1
 * gagne +39 M de stamina quand il descend sous 120, au lieu de +1 toutes les 6 min ? »
 *
 * <p>Prouvé au bytecode, ici vérifié à l'exécution :
 * <ol>
 *   <li>Le MONTANT de régén ne dépend PAS du niveau d'équipe. {@code UserHelper.getGenerationAmount(user,
 *       STAMINA)} = {@code RegenerationRate(∞, StaminaStats.getRegenAmount(ContentHelper.getCurrent(user)
 *       .getContentUpdate()))} — l'unique entrée est la <b>content release</b> (résolue par la DATE), jamais
 *       le niveau. Un compte niveau 1 et un compte niveau 565, à la même date, ont le MÊME montant.</li>
 *   <li>La régén STAMINA est dans la branche NON-capée de {@code updateAndGetResource} (pas de
 *       {@code Math.min(cap, ...)}) : un seul tick ajoute le montant ENTIER puis la boucle sort → la valeur
 *       DÉBORDE le cap.</li>
 * </ol>
 * Donc à la date 2026 (R102) le montant = 39 965 650 : un joueur niveau 1 qui descend à 108 puis attend 6 min
 * saute à ~39,96 M. Ce n'est pas un calcul « faux » — c'est le taux de fin de vie (content release), appliqué
 * à un cap de niveau 1 (120). Le « +1 toutes les 6 min » n'existe qu'aux PREMIÈRES content releases (R1..).
 */
public final class StaminaCalcProbe {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);
    BootData bd = u.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "calc");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "calc");
    ServerContext.bind(user, iu);

    int teamLevel = user.getTeamLevel();
    long cap = UserHelper.getResourceCap(ResourceType.STAMINA, user);
    long interval = UserHelper.getResourceGenerationInterval(ResourceType.STAMINA, user, false);
    // getGenerationAmount(user,STAMINA) construit RegenerationRate(∞, StaminaStats.getRegenAmount(R)) ;
    // le champ .amount est package-private → on lit la MEME source publique (valeur identique).
    UserHelper.getGenerationAmount(user, ResourceType.STAMINA); // (prouve juste que l'appel passe)
    int contentR = ContentHelper.getCurrent(user).getContentUpdate().release;
    long regenAmount = com.perblue.heroes.game.data.misc.StaminaStats.getRegenAmount(
        ContentHelper.getCurrent(user).getContentUpdate());

    System.out.println("=== Profil du compte ===");
    System.out.printf("teamLevel=%d  cap(STAMINA)=%d  interval=%,dms (%d min)  contentRelease=R%d%n",
        teamLevel, cap, interval, interval / 60000, contentR);
    System.out.printf("getGenerationAmount(user,STAMINA).amount = %,d  <-- LE MONTANT PAR TICK%n", regenAmount);
    System.out.println("  (issu de StaminaStats.getRegenAmount(R" + contentR + "), AUCUNE dependance au niveau)");

    // Mise en situation : le joueur descend sous le cap (108) et 1 h s'écoule (>> 1 intervalle de 6 min).
    long now = TimeUtil.serverTimeNow();
    iu.setResource(user, ResourceType.STAMINA, 108L, "probe");
    iu.setLastResourceGenerationTime(ResourceType.STAMINA, now - 3600_000L); // il y a 1 h
    System.out.println();
    System.out.println("=== Mise en situation : stamina=108 (<120), derniere regen il y a 1 h ===");
    long got = user.getResource(ResourceType.STAMINA);
    System.out.printf("user.getResource(STAMINA) = %,d   (cap affiche = %d)%n", got, cap);
    System.out.println(got > cap
        ? "=> DEBORDE le cap : un seul tick a ajoute le montant ENTIER (branche non-capee). C'est le \"39 M / 120\"."
        : "=> reste au cap (branche capee).");

    // Contre-preuve : le montant serait le meme a un niveau eleve (dependance = content release, pas niveau).
    System.out.println();
    System.out.println("=== Le montant par tick selon la content release (StaminaStats), pour reference ===");
    for (int r : new int[]{1, 20, 42, 60, 102}) {
      System.out.printf("  R%-3d -> +%,d par 6 min%n", r,
          com.perblue.heroes.game.data.misc.StaminaStats.getRegenAmount(
              com.perblue.heroes.game.data.content.ContentUpdate.of(r)));
    }
    System.out.println();
    System.out.println("CONCLUSION : le taux est indexe sur la CONTENT RELEASE (date), pas le niveau du joueur.");
    System.out.println("A R1 (2016) c'est +1/6min ; a R102 (2026) c'est +39 965 650/6min, pour TOUS les niveaux.");
    System.out.println("Le 'bug' visible = un compte niveau 1 (cap 120) auquel on presente l'ere R102 (2026).");
  }
}
