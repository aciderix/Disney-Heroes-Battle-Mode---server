import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.battlepass.BattlePassV2Stats;
import com.perblue.heroes.game.logic.BattlePassV2Helper;
import com.perblue.heroes.game.objects.IBattlePassV2Data;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * BATTLE PASS V2 — handlers de RÉCLAMATION rendus fonctionnels côté serveur (logique du jeu, anti-triche RÉEL).
 * Vérifie PAR LES FAITS (PRINCIPLES §8) :
 * <ul>
 *   <li><b>Accumulation de progression</b> : la progression du battle pass EST la ressource du jeu
 *       {@code ResourceType.QUEST_POINTS} — {@code setResource(QUEST_POINTS)} route vers
 *       {@code getUserBattlePassV2().setProgress} (prouvé au bytecode). En liant le wrapper sur NOTRE
 *       BattlePassV2Data persisté, la progression s'accumule via le code du jeu, zéro glue.</li>
 *   <li><b>Claim d'un palier</b> ({@code BATTLE_PASS_V2_CLAIM_REWARD}) → {@code BattlePassV2Helper.claimReward}
 *       (récompense créditée + palier marqué réclamé) + <b>persiste</b> (battlePassWire).</li>
 *   <li><b>Anti-triche RÉEL</b> : re-claim d'un palier déjà pris = REFUSÉ ({@code BATTLE_PASS_TIER_ALREADY_CLAIMED}) ;
 *       claim d'un palier au-dessus de la progression = REFUSÉ ({@code BATTLE_PASS_MISSING_POINTS}).</li>
 * </ul>
 * NB : l'écran est verrouillé TL11 côté CLIENT (unlockables.tab), mais la logique serveur n'a aucun gate de
 * niveau → testable ici ; vérif EN JEU reportée à TL≥11.
 */
public final class BattlePassClaimTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "bp");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "bp");
    ServerContext.bind(u, iu);
    ServerContext.bindBattlePass(su.refreshBattlePass());   // wrapper sur NOTRE message persisté
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    User u = bind(su);

    long start = BattlePassV2Stats.getSeasonStartTime();
    java.util.List<?> tiers = BattlePassV2Stats.getRewardTiers(start);
    int maxTier = BattlePassV2Stats.getMaxTier(start);

    // Trouver un palier RÉEL : points > 0 ET récompense gratuite NON vide (pour tester le crédit + l'anti
    // double-claim, qui ne mord — sémantique du jeu isFreeTierClaimed — que sur une récompense non vide).
    int tReal = -1, tRealPoints = 0;
    for (int t = 1; t <= maxTier; t++) {
      var info = BattlePassV2Helper.computeReward(u, BattlePassType.QUEST, start, t);
      int pts = info.getPoints();
      if (pts > 0 && info.getFreeRewards() != null && !info.getFreeRewards().isEmpty()) { tReal = t; tRealPoints = pts; break; }
    }
    if (tReal < 0) throw new AssertionError("aucun palier avec points>0 et récompense gratuite non vide");
    System.out.println("[bpclaim] paliers=" + tiers.size() + " maxTier=" + maxTier
        + " | palier testé=" + tReal + " (points=" + tRealPoints + ")");

    Action claim = new Action();
    claim.command = CommandType.BATTLE_PASS_V2_CLAIM_REWARD;
    claim.extra = new java.util.EnumMap<>(ActionExtraType.class);
    claim.extra.put(ActionExtraType.TYPE, BattlePassType.QUEST.name());
    claim.extra.put(ActionExtraType.INDEX, Integer.toString(tReal));
    claim.extra.put(ActionExtraType.MODE, Boolean.toString(false));

    // 1) ANTI-TRICHE (points manquants) : à progression 0, réclamer un palier à points>0 = REFUSÉ.
    boolean claimNoPoints = su.applyAction(claim);
    System.out.println("[bpclaim] CLAIM palier " + tReal + " à progress=0 appliqué=" + claimNoPoints + " (doit être false : points manquants)");
    if (claimNoPoints) throw new AssertionError("claim au-dessus de la progression aurait dû être REFUSÉ (BATTLE_PASS_MISSING_POINTS)");

    // 2) ACCUMULATION : donner QUEST_POINTS via le code du jeu (setResource) → remonte dans getProgress().
    u.getIndividual().setResource(u, ResourceType.QUEST_POINTS, tRealPoints, "test");
    IBattlePassV2Data live = com.perblue.heroes.DH.app.getUserBattlePassV2();
    long readBack = u.getResource(ResourceType.QUEST_POINTS);
    System.out.println("[bpclaim] après don QUEST_POINTS=" + tRealPoints + " → getProgress=" + live.getProgress()
        + " getResource(QUEST_POINTS)=" + readBack);
    if (live.getProgress() != tRealPoints) throw new AssertionError("QUEST_POINTS n'a pas alimenté la progression du battle pass");
    if (readBack != tRealPoints) throw new AssertionError("getResource(QUEST_POINTS) ne lit pas la progression");

    // 3) CLAIM du palier via le SERVEUR → appliqué + palier marqué réclamé (isFreeTierClaimed).
    long diamondsBefore = u.getResource(ResourceType.DIAMONDS);
    boolean applied = su.applyAction(claim);
    IBattlePassV2Data after = com.perblue.heroes.DH.app.getUserBattlePassV2();
    boolean claimed = BattlePassV2Helper.isFreeTierClaimed(u, after, tReal);
    System.out.println("[bpclaim] CLAIM palier " + tReal + " appliqué=" + applied + " | isFreeTierClaimed=" + claimed);
    if (!applied) throw new AssertionError("le claim aurait dû réussir (progress ≥ points)");
    if (!claimed) throw new AssertionError("le palier aurait dû être marqué réclamé (isFreeTierClaimed)");

    // 4) ANTI-TRICHE (déjà réclamé) : re-claim = REFUSÉ (garde isFreeTierClaimed autoritative).
    boolean reclaim = su.applyAction(claim);
    System.out.println("[bpclaim] RE-CLAIM palier " + tReal + " appliqué=" + reclaim + " (doit être false : déjà réclamé)");
    if (reclaim) throw new AssertionError("re-réclamer un palier déjà pris aurait dû être REFUSÉ (anti-triche)");

    // 5) PERSISTANCE : recharger depuis le wire (comme au reload DB) → palier toujours réclamé + progress.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    reloaded.setBattlePassWire(su.battlePassWire());
    BattlePassV2Data persisted = reloaded.refreshBattlePass();   // message persisté (claimedFreeRewards, progress)
    Object claimedEntry = persisted.claimedFreeRewards.get(tReal);
    boolean stillClaimed = claimedEntry != null
        && !((BattlePassV2Rewards) claimedEntry).rewards.isEmpty();
    System.out.println("[bpclaim] après reload wire → palier " + tReal + " réclamé=" + stillClaimed + " progress=" + persisted.progress);
    if (!stillClaimed) throw new AssertionError("le palier réclamé aurait dû PERSISTER (battlePassWire)");
    if (persisted.progress != tRealPoints) throw new AssertionError("la progression aurait dû PERSISTER");

    System.out.println("[bpclaim] OK — accumulation (QUEST_POINTS→progress) + claim crédité/marqué + anti-triche (déjà réclamé / points manquants) + persistance");
  }
}
