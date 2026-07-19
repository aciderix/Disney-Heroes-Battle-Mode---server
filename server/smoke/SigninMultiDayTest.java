import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * Vérifie la progression MULTI-JOUR du sign-in (question : « à j2/j3 le joueur aura-t-il les récompenses
 * correspondantes, bien créditées et disponibles ? »). On rejoue la logique du jeu :
 *  - JOUR 1 : réclamer le jour actif → la récompense (GOLD du jour 0) est CRÉDITÉE au joueur.
 *  - simuler le passage à JOUR 2 en reculant {@code LAST_USER_DAILY_RESET} (le reset quotidien LAZY du jeu
 *    {@code DailyActivityHelper.checkAndUpdateDailyValues}, appelé par {@code getDailyChances}, remet alors
 *    la chance « daily_signin » à son max).
 *  - JOUR 2 : le jour actif AVANCE, la réclamation redevient possible → la récompense (DIAMONDS du jour 1)
 *    est créditée. Le compteur {@code getMonthlySignins} passe à 2 et PERSISTE au round-trip wire.
 */
public final class SigninMultiDayTest {

  static long gold(BootData bd) {
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "sd");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(bd.individualUserExtra, 1L, bd.userInfo.diamonds, "sd");
    ServerContext.bind(u, iu);
    return u.getResource(ResourceType.GOLD);
  }
  static long diamonds(BootData bd) {
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "sd");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(bd.individualUserExtra, 1L, bd.userInfo.diamonds, "sd");
    ServerContext.bind(u, iu);
    return u.getResource(ResourceType.DIAMONDS);
  }

  static boolean claim(ServerUser su, int index) {
    Action a = new Action();
    a.command = CommandType.CLAIM_SIGNIN_REWARD;
    a.extra = new java.util.HashMap<>();
    a.extra.put(ActionExtraType.INDEX, String.valueOf(index));
    return su.applyAction(a);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    long gold0 = gold(su.bootData());
    long diam0 = diamonds(su.bootData());
    System.out.println("[multi] départ GOLD=" + gold0 + " DIAMONDS=" + diam0);

    // --- JOUR 1 : réclamer le jour actif ---
    User u1 = ClientNetworkStateConverter.getUser(su.bootData().userInfo, su.bootData().userExtra, "d1");
    IndividualUser iu1 = ClientNetworkStateConverter.getIndividualUser(su.bootData().individualUserExtra, 1L, su.bootData().userInfo.diamonds, "d1");
    ServerContext.bind(u1, iu1);
    com.perblue.heroes.game.logic.SigninHelper.setData(su.buildSigninRewards());
    int active1 = com.perblue.heroes.game.logic.SigninHelper.getActiveRewardIndex(u1);
    System.out.println("[multi] JOUR1 jour actif=" + active1 + " réclamable=" + com.perblue.heroes.game.logic.SigninHelper.isClaimable(u1, active1));
    if (!claim(su, active1)) throw new AssertionError("JOUR1 : réclamation échouée");
    long goldAfter1 = gold(su.bootData());
    System.out.println("[multi] JOUR1 après claim GOLD=" + goldAfter1 + " (Δ=" + (goldAfter1 - gold0) + ")");
    if (goldAfter1 <= gold0) throw new AssertionError("JOUR1 : GOLD non crédité (récompense non reçue)");

    // --- Simuler JOUR 2 : reculer LAST_USER_DAILY_RESET de 2 jours → le reset quotidien lazy du jeu
    //     remettra la chance « daily_signin » à 1 (nouveau jour). On recule aussi lastSigninTime (déjà < now). ---
    User u2 = ClientNetworkStateConverter.getUser(su.bootData().userInfo, su.bootData().userExtra, "back");
    IndividualUser iu2 = ClientNetworkStateConverter.getIndividualUser(su.bootData().individualUserExtra, 1L, su.bootData().userInfo.diamonds, "back");
    ServerContext.bind(u2, iu2);
    long twoDays = 2L * 24 * 60 * 60 * 1000;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    u2.setTime(TimeType.LAST_USER_DAILY_RESET, now - twoDays);
    // Re-sync du temps vers le wire (setTime écrit dans this.extra.times → déjà persisté ; on relit ci-dessous).
    // On persiste l'état modifié : setTime a muté userExtra (this.extra) partagé → visible via su.bootData().

    // --- JOUR 2 : le reset lazy doit rendre le jour suivant réclamable ---
    User u3 = ClientNetworkStateConverter.getUser(su.bootData().userInfo, su.bootData().userExtra, "d2");
    IndividualUser iu3 = ClientNetworkStateConverter.getIndividualUser(su.bootData().individualUserExtra, 1L, su.bootData().userInfo.diamonds, "d2");
    ServerContext.bind(u3, iu3);
    com.perblue.heroes.game.logic.SigninHelper.setData(su.buildSigninRewards());
    int monthlyBefore = u3.getMonthlySignins();
    int active2 = com.perblue.heroes.game.logic.SigninHelper.getActiveRewardIndex(u3);
    boolean claimable2 = com.perblue.heroes.game.logic.SigninHelper.isClaimable(u3, active2);
    System.out.println("[multi] JOUR2 monthlySignins=" + monthlyBefore + " jour actif=" + active2 + " réclamable=" + claimable2);
    if (!claimable2) throw new AssertionError("JOUR2 : le reset quotidien n'a PAS rendu le sign-in réclamable → j2/j3 cassé");
    if (active2 <= active1) throw new AssertionError("JOUR2 : le jour actif n'a pas avancé (" + active1 + "→" + active2 + ")");
    long diamBefore2 = diamonds(su.bootData());
    if (!claim(su, active2)) throw new AssertionError("JOUR2 : réclamation échouée");

    // Vérifs finales : compteur avancé + récompense du jour 2 créditée + persistance wire.
    User uf = ClientNetworkStateConverter.getUser(su.bootData().userInfo, su.bootData().userExtra, "fin");
    IndividualUser iuf = ClientNetworkStateConverter.getIndividualUser(su.bootData().individualUserExtra, 1L, su.bootData().userInfo.diamonds, "fin");
    ServerContext.bind(uf, iuf);
    int monthlyAfter = uf.getMonthlySignins();
    long diamAfter2 = diamonds(su.bootData());
    System.out.println("[multi] FIN monthlySignins=" + monthlyAfter + " DIAMONDS " + diamBefore2 + "→" + diamAfter2);
    if (monthlyAfter != monthlyBefore + 1)
      throw new AssertionError("monthlySignins devait passer à " + (monthlyBefore + 1) + ", obtenu " + monthlyAfter);
    if (diamAfter2 <= diamBefore2)
      throw new AssertionError("JOUR2 : récompense (DIAMONDS) non créditée");
    System.out.println("[multi] OK — j1 et j2 réclamés, récompenses créditées, compteur avancé & persisté");
  }
}
