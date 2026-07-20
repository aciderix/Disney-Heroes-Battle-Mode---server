import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.QuestHelper;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UserFlag;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * BOÎTE-RÉCOMPENSE HEBDOMADAIRE (écran QUESTS) — ouverture d'une boîte via le message {@code ClaimWeeklyQuestReward}.
 * Après {@code REDEEM_DAILY_QUESTS}, la progression est convertie en N boîtes ({@code WEEKLY_QUEST_REWARDS}) ;
 * ouvrir une boîte = {@code QuestHelper.claimWeeklyReward} (logique du jeu) :
 * <ul>
 *   <li><b>anti-triche RÉEL sur le NOMBRE</b> : {@code getWeeklyRewardsRemaining>0} sinon refus → on n'ouvre
 *       QUE le nombre de boîtes gagnées ({@code WEEKLY_QUEST_REWARDS} décrémenté) ;</li>
 *   <li>donne la récompense choisie + le bonus stamina ; <b>persiste</b>.</li>
 * </ul>
 */
public final class WeeklyBoxTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "wb");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "wb");
    ServerContext.bind(u, iu);
    return u;
  }

  static ClaimWeeklyQuestReward box() {
    ClaimWeeklyQuestReward m = new ClaimWeeklyQuestReward();
    RewardDrop r = new RewardDrop();
    r.itemType = ItemType.CODE_2_DIAMOND;   // récompense choisie (item simple, crédité via RewardHelper.giveReward)
    r.quantity = 1;
    m.rewardChosen = r;
    m.rewardDrops = new java.util.ArrayList<>();
    m.rewardDrops.add(r);
    m.staminaReward = 50;
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    // 1) Le joueur a GAGNÉ 2 boîtes (état persisté : WEEKLY_QUEST_REWARDS=2, comme après REDEEM_DAILY_QUESTS).
    su.bootData().userExtra.counts.put(UserFlag.WEEKLY_QUEST_REWARDS.name(), 2);
    User u0 = bind(su);
    int remaining0 = QuestHelper.getWeeklyRewardsRemaining(u0);
    System.out.println("[wbox] boîtes gagnées (WEEKLY_QUEST_REWARDS) = " + remaining0);
    if (remaining0 != 2) throw new AssertionError("2 boîtes attendues");

    // 2) OUVRIR une boîte (message ClaimWeeklyQuestReward) → logique du jeu, décrémente + crédite.
    boolean ok = su.claimWeeklyReward(box());
    User u1 = bind(su);
    int remaining1 = QuestHelper.getWeeklyRewardsRemaining(u1);
    System.out.println("[wbox] ouverture #1 appliquée=" + ok + " | boîtes restantes=" + remaining1);
    if (!ok) throw new AssertionError("l'ouverture aurait dû réussir (2 boîtes dispo)");
    if (remaining1 != 1) throw new AssertionError("le compteur de boîtes aurait dû passer à 1");

    // 3) PERSISTANCE : recharger depuis le wire → compteur toujours à 1.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    User ur = bind(reloaded);
    int remainingR = QuestHelper.getWeeklyRewardsRemaining(ur);
    System.out.println("[wbox] après reload wire → boîtes restantes=" + remainingR);
    if (remainingR != 1) throw new AssertionError("le décrément aurait dû PERSISTER");

    // 4) Ouvrir la 2e boîte → 0. Puis 5) ANTI-TRICHE : ouvrir une boîte de plus (0 restante) = REFUSÉ.
    boolean ok2 = su.claimWeeklyReward(box());
    int remaining2 = QuestHelper.getWeeklyRewardsRemaining(bind(su));
    boolean ok3 = su.claimWeeklyReward(box());
    int remaining3 = QuestHelper.getWeeklyRewardsRemaining(bind(su));
    System.out.println("[wbox] ouverture #2 appliquée=" + ok2 + " restantes=" + remaining2
        + " | ouverture #3 (0 dispo) appliquée=" + ok3 + " restantes=" + remaining3);
    if (!ok2 || remaining2 != 0) throw new AssertionError("la 2e ouverture aurait dû réussir (→ 0)");
    if (ok3) throw new AssertionError("ouvrir une boîte sans en avoir aurait dû être REFUSÉ (anti-triche NOMBRE)");
    if (remaining3 != 0) throw new AssertionError("aucune mutation sur une ouverture refusée");

    System.out.println("[wbox] OK — ouverture de boîte weekly (crédit + décrément + persiste) + anti-triche sur le nombre de boîtes");
  }
}
