import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.QuestHelper;
import com.perblue.heroes.game.data.quests.QuestStats;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UserFlag;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * QUÊTE HEBDOMADAIRE (écran QUESTS : « Rewards X/5, Quests Y/105 ») — rend l'écran FONCTIONNEL de bout en bout :
 * <ul>
 *   <li><b>Progression comptée</b> : le nombre de quêtes quotidiennes faites cette semaine
 *       ({@code WEEKLY_DAILY_QUESTS_COMPLETE}) est un {@code UserFlag} (dans {@code this.extra} → auto-persisté) ;
 *       les paliers/récompenses gagnées ({@code getNumWeeklyRewardsEarned}) en dérivent.</li>
 *   <li><b>Réclamation</b> {@code REDEEM_DAILY_QUESTS} → {@code QuestHelper.redeemWeeklyRewards} : convertit la
 *       progression en N boîtes-récompense ({@code WEEKLY_QUEST_REWARDS += N}), reset le compteur — persiste.</li>
 *   <li><b>Anti-triche / prérequis</b> : réclamable seulement le LUNDI &amp; pas déjà réclamé.</li>
 * </ul>
 */
public final class WeeklyQuestTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "wq");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "wq");
    ServerContext.bind(u, iu); return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    User u = bind(su);

    if (!QuestHelper.isWeeklyQuestEnabled(u)) throw new AssertionError("weekly quest devrait être activé (data)");
    long[] goals = QuestHelper.getWeeklyQuestGoals();
    System.out.println("[weekly] paliers (quêtes requises) = " + java.util.Arrays.toString(goals));
    if (goals.length == 0) throw new AssertionError("aucun palier weekly");

    // 1) PROGRESSION COMPTÉE : on injecte N quêtes quotidiennes faites cette semaine DANS L'ÉTAT PERSISTÉ
    //    (userExtra.counts, clé String = UserFlag.name()) — c'est là que completeQuest(DAILY_QUEST) écrit (via
    //    resyncCounts). On atteint le 1er palier → au moins 1 récompense gagnée.
    int done = (int) goals[0];
    su.bootData().userExtra.counts.put(UserFlag.WEEKLY_DAILY_QUESTS_COMPLETE.name(), done);
    User u2 = bind(su);   // relit depuis le wire (setCounts String→UserFlag)
    int prog = QuestHelper.getWeeklyDailyQuestsComplete(u2);
    int earned = QuestHelper.getNumWeeklyRewardsEarned(u2);
    System.out.println("[weekly] progression=" + prog + " (fait " + done + ") | récompenses gagnées=" + earned);
    if (prog != done) throw new AssertionError("progression non lue depuis le wire persisté");
    if (earned < 1) throw new AssertionError("au moins 1 récompense aurait dû être gagnée au palier 1");
    System.out.println("[weekly] progression LUE depuis le wire persisté (" + prog + ")");

    // 2) RÉCLAMATION via le serveur (REDEEM_DAILY_QUESTS). Aujourd'hui = " + jour ".
    boolean isMonday = com.perblue.heroes.util.TimeUtil.getUserDailyActivityDayOfWeek(u2) == 2;
    int rewardsBefore = u2.getCount(UserFlag.WEEKLY_QUEST_REWARDS);
    Action m = new Action(); m.command = CommandType.REDEEM_DAILY_QUESTS;
    boolean applied;
    try { applied = su.applyAction(m); }
    catch (Throwable t) { applied = false; }
    User u3 = bind(su);
    int rewardsAfter = u3.getCount(UserFlag.WEEKLY_QUEST_REWARDS);
    int progAfter = QuestHelper.getWeeklyDailyQuestsComplete(u3);
    System.out.println("[weekly] lundi=" + isMonday + " | REDEEM appliqué=" + applied
        + " | boîtes-récompense " + rewardsBefore + " → " + rewardsAfter + " | progression après=" + progAfter);

    if (isMonday) {
      // Lundi : le REDEEM convertit la progression en boîtes (WEEKLY_QUEST_REWARDS += N) et reset le compteur.
      if (!applied) throw new AssertionError("REDEEM aurait dû s'appliquer un lundi");
      if (rewardsAfter <= rewardsBefore) throw new AssertionError("des boîtes-récompense auraient dû être ajoutées");
      if (progAfter != 0) throw new AssertionError("le compteur weekly aurait dû être remis à 0 après redeem");
      // Persistance du claim.
      var ix = (IndividualUserExtra) null; // (état weekly = UserExtra, déjà relu via u3)
      System.out.println("[weekly] OK — progression comptée+persistée, REDEEM (lundi) crédite les boîtes+reset, persiste");
    } else {
      // Hors lundi : le serveur REFUSE (anti-triche WEEKLY_QUEST_WRONG_DAY), état inchangé.
      if (applied) throw new AssertionError("REDEEM aurait dû être REFUSÉ hors lundi (anti-triche)");
      if (rewardsAfter != rewardsBefore || progAfter != done)
        throw new AssertionError("aucune mutation sur un REDEEM refusé");
      System.out.println("[weekly] OK — progression comptée+persistée ; REDEEM REFUSÉ hors lundi (anti-triche), état intact");
    }
  }
}
