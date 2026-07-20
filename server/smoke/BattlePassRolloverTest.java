import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.battlepass.BattlePassV2Stats;
import com.perblue.heroes.game.logic.BattlePassV2Helper;
import com.perblue.heroes.game.objects.IBattlePassV2Data;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * BATTLE PASS — CHANGEMENT DE MOIS (rollover de saison) + PREMIUM POUR TOUS (vérif par les faits, §8).
 * <ul>
 *   <li><b>Premium pour tous</b> : {@code premiumUnlocked=true} (le VRAI gate du track premium, pas
 *       {@code boughtBattlePass}) → un palier PREMIUM se réclame.</li>
 *   <li><b>Rollover</b> (le mois change → {@code startTime} ne correspond plus à la saison courante) :
 *       progress + paliers réclamés remis à 0, MAIS les récompenses MÉRITÉES non réclamées de la saison
 *       écoulée sont conservées dans {@code previousUnclaimed} (réclamables via
 *       {@code BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS}). Premium reste débloqué (nouvelle saison).</li>
 * </ul>
 */
public final class BattlePassRolloverTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "bp");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "bp");
    ServerContext.bind(u, iu);
    ServerContext.bindBattlePass(su.refreshBattlePass());
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    User u = bind(su);

    long start = BattlePassV2Stats.getSeasonStartTime();
    java.util.List<?> tiers = BattlePassV2Stats.getRewardTiers(start);
    // Points pour couvrir ~5 paliers (progress ≥ points → paliers 1..5 mérités).
    int idx = Math.min(4, tiers.size() - 1);
    int grantPoints = ((com.perblue.heroes.game.data.battlepass.BattlePassV2RewardTier) tiers.get(idx)).getPoints();
    if (grantPoints <= 0) grantPoints = 50;

    // 0) PREMIUM POUR TOUS : le message doit avoir premiumUnlocked=true.
    IBattlePassV2Data bp = com.perblue.heroes.DH.app.getUserBattlePassV2();
    System.out.println("[roll] premiumUnlocked=" + bp.getPremiumUnlocked());
    if (!bp.getPremiumUnlocked()) throw new AssertionError("premiumUnlocked devrait être true (premium pour tous)");

    // 1) Accumuler des QUEST_POINTS → progression.
    u.getIndividual().setResource(u, ResourceType.QUEST_POINTS, grantPoints, "test");
    System.out.println("[roll] progress=" + bp.getProgress() + " (grant " + grantPoints + ")");

    // 2) Claim d'un palier PREMIUM (tier avec récompense premium non vide, points ≤ progress) → doit RÉUSSIR.
    int premTier = -1;
    for (int t = 1; t <= idx + 1 && t <= tiers.size(); t++) {
      var info = BattlePassV2Helper.computeReward(u, BattlePassType.QUEST, start, t);
      if (info.getPoints() <= bp.getProgress() && info.getPremiumRewards() != null && !info.getPremiumRewards().isEmpty()) { premTier = t; break; }
    }
    if (premTier > 0) {
      Action c = new Action(); c.command = CommandType.BATTLE_PASS_V2_CLAIM_REWARD;
      c.extra = new java.util.EnumMap<>(ActionExtraType.class);
      c.extra.put(ActionExtraType.TYPE, BattlePassType.QUEST.name());
      c.extra.put(ActionExtraType.INDEX, Integer.toString(premTier));
      c.extra.put(ActionExtraType.MODE, Boolean.toString(true));   // premium
      boolean ok = su.applyAction(c);
      boolean claimed = BattlePassV2Helper.isPremiumTierClaimed(u, com.perblue.heroes.DH.app.getUserBattlePassV2(), premTier);
      System.out.println("[roll] CLAIM PREMIUM palier " + premTier + " appliqué=" + ok + " isPremiumTierClaimed=" + claimed);
      if (!ok || !claimed) throw new AssertionError("le claim PREMIUM aurait dû réussir (premium pour tous)");
    } else {
      System.out.println("[roll] (aucun palier premium non vide ≤ progress — claim premium non testé ce coup-ci)");
    }

    // 3) SIMULER LE CHANGEMENT DE MOIS : la donnée persistée a un startTime du mois PRÉCÉDENT.
    BattlePassV2Data raw = su.refreshBattlePass();     // le message persisté (référence)
    long prevProgress = raw.progress;
    int claimedFreeCount = raw.claimedFreeRewards == null ? 0 : raw.claimedFreeRewards.size();
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.setTimeInMillis(start); cal.add(java.util.Calendar.MONTH, -1);
    raw.startTime = cal.getTimeInMillis();             // saison du mois dernier → force le rollover au prochain refresh
    System.out.println("[roll] AVANT rollover : progress=" + prevProgress + " claimedFree=" + claimedFreeCount
        + " startTime(simulé mois-1)=" + raw.startTime);

    // 4) Rollover : refreshBattlePass détecte startTime != saison courante → reset + préserve unclaimed.
    BattlePassV2Data after = su.refreshBattlePass();
    int prevUnclaimedFree = after.previousUnclaimedFreeRewards == null ? 0 : after.previousUnclaimedFreeRewards.size();
    int prevUnclaimedPrem = after.previousUnclaimedPremiumRewards == null ? 0 : after.previousUnclaimedPremiumRewards.size();
    System.out.println("[roll] APRÈS rollover : progress=" + after.progress + " claimedFree="
        + (after.claimedFreeRewards == null ? 0 : after.claimedFreeRewards.size())
        + " premiumUnlocked=" + after.premiumUnlocked + " startTime=" + after.startTime
        + " | previousUnclaimed free=" + prevUnclaimedFree + " prem=" + prevUnclaimedPrem);
    if (after.progress != 0) throw new AssertionError("progress aurait dû être remis à 0 au rollover");
    if (after.claimedFreeRewards != null && !after.claimedFreeRewards.isEmpty())
      throw new AssertionError("les paliers réclamés auraient dû être vidés au rollover");
    if (!after.premiumUnlocked) throw new AssertionError("premium devrait rester débloqué à la nouvelle saison");
    if (after.startTime != start) throw new AssertionError("startTime aurait dû être ré-ancré à la saison courante");
    if (prevProgress > 0 && (prevUnclaimedFree + prevUnclaimedPrem) == 0)
      throw new AssertionError("des récompenses méritées non réclamées auraient dû être conservées (previousUnclaimed)");

    // 5) COLLECTE des récompenses de la saison écoulée (BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS) → previousUnclaimed vidé.
    Action col = new Action(); col.command = CommandType.BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS;
    col.extra = new java.util.EnumMap<>(ActionExtraType.class);
    col.extra.put(ActionExtraType.TYPE, BattlePassType.QUEST.name());
    boolean collected = su.applyAction(col);
    BattlePassV2Data end = su.refreshBattlePass();
    int leftFree = end.previousUnclaimedFreeRewards == null ? 0 : end.previousUnclaimedFreeRewards.size();
    int leftPrem = end.previousUnclaimedPremiumRewards == null ? 0 : end.previousUnclaimedPremiumRewards.size();
    System.out.println("[roll] COLLECT unclaimed appliqué=" + collected + " | reste free=" + leftFree + " prem=" + leftPrem);
    if (collected && (leftFree != 0 || leftPrem != 0))
      throw new AssertionError("previousUnclaimed aurait dû être vidé après collecte");

    System.out.println("[roll] OK — premium pour tous (premiumUnlocked) + rollover (reset progress/claims, unclaimed conservés+collectés, premium maintenu, saison ré-ancrée)");
  }
}
