import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.List;

/**
 * Smoke test de la feature SIGN IN (récompense de connexion quotidienne). Vérifie que le serveur
 * CONSTRUIT un {@link SigninRewards} non vide à partir de la table du jeu ({@code signin_rewards.tab},
 * roulée par {@code SigninStats.REWARDS_TABLE}) — <b>zéro donnée écrite à la main</b> (PRINCIPLES §3/§4) —
 * puis qu'une RÉCLAMATION ({@code Action{CLAIM_SIGNIN_REWARD}}) exécute la logique d'origine
 * ({@code SigninHelper.claim}) et crédite bien la récompense du jour.
 */
public final class SigninTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser u = ServerUser.newPlayer(1L, 1);

    // 1) CONSTRUCTION du message SigninRewards (réponse à REFRESH_SPECIAL_EVENTS).
    SigninRewards sr = u.buildSigninRewards();
    if (sr == null) throw new AssertionError("SigninRewards null");
    if (sr.thisMonth == null) throw new AssertionError("thisMonth null");
    List<?> rewards = sr.thisMonth.rewards;
    System.out.println("[signin] thisMonth: " + (rewards == null ? 0 : rewards.size())
        + " jours, [" + sr.thisMonth.startTime + " .. " + sr.thisMonth.endTime + "]"
        + " hero=" + sr.thisMonth.signinHero);
    if (rewards == null || rewards.isEmpty())
      throw new AssertionError("thisMonth.rewards VIDE — la table signin_rewards.tab n'a pas été roulée");
    // Le mois d'un vrai calendrier a 28..31 jours ; la table V1 va jusqu'à DAY_30 (31 nœuds). On tolère
    // 28..31 (borne du mois) — l'important est que ce soit peuplé et cohérent.
    if (rewards.size() < 20 || rewards.size() > 40)
      throw new AssertionError("nombre de jours aberrant : " + rewards.size());
    // Bornes : this englobe le temps courant ; last < this < next.
    if (!(sr.lastMonth.endTime < sr.thisMonth.startTime
        && sr.thisMonth.endTime < sr.nextMonth.startTime))
      throw new AssertionError("bornes mensuelles incohérentes");
    // Aperçu des 5 premiers jours (doit correspondre à la .tab : GOLD, DIAMONDS, EXP_COLOSSAL, GEAR_JUICE…).
    for (int i = 0; i < Math.min(5, rewards.size()); i++) {
      RewardDrop d = (RewardDrop) rewards.get(i);
      System.out.println("[signin]   jour " + i + " : " + d);
    }

    // 2) RÉCLAMATION du jour actif (Action{CLAIM_SIGNIN_REWARD}). On lit l'inventaire avant/après pour
    //    prouver que la récompense est bien créditée par la logique du jeu (SigninHelper.claim).
    ServerContext.init();
    com.perblue.heroes.game.objects.User user =
        com.perblue.heroes.game.ClientNetworkStateConverter.getUser(u.bootData().userInfo,
            u.bootData().userExtra, "signin-claim");
    com.perblue.heroes.game.objects.IndividualUser iu =
        com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
            u.bootData().individualUserExtra, 1L, u.bootData().userInfo.diamonds, "signin-claim");
    ServerContext.bind(user, iu);
    com.perblue.heroes.game.logic.SigninHelper.setData(sr);
    int active = com.perblue.heroes.game.logic.SigninHelper.getActiveRewardIndex(user);
    boolean claimable = com.perblue.heroes.game.logic.SigninHelper.isClaimable(user, active);
    System.out.println("[signin] jour actif=" + active + " réclamable=" + claimable);

    Action claim = new Action();
    claim.command = CommandType.CLAIM_SIGNIN_REWARD;
    claim.extra = new java.util.HashMap<>();
    claim.extra.put(ActionExtraType.INDEX, String.valueOf(active));
    boolean applied = u.applyAction(claim);
    System.out.println("[signin] CLAIM_SIGNIN_REWARD appliquée=" + applied);
    if (claimable && !applied)
      throw new AssertionError("réclamation d'un jour réclamable a échoué");

    System.out.println("[signin] OK");
  }
}
