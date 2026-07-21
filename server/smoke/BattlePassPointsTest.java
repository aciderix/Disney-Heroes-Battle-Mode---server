import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IBattlePassV2Data;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.logic.RewardSourceType;
import com.perblue.heroes.game.logic.BattlePassV2Helper;
import com.perblue.heroes.game.data.battlepass.BattlePassV2Stats;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * COMPTEUR « ① X/9 » DU BATTLE PASS — preuve de bout en bout que le compteur (= points de battle pass) est
 * <b>incrémenté et fonctionnel côté serveur</b>, via le <b>VRAI chemin de récompense du jeu</b>
 * ({@code RewardHelper.giveReward} d'un {@code RewardDrop(QUEST_POINTS)} → {@code UserHelper.giveUser} →
 * {@code setResource(QUEST_POINTS)} → {@code getUserBattlePassV2().setProgress}, prouvé au bytecode) — exactement
 * ce qui se produit quand une quête de battle pass crédite des points. On charge le <b>compte RÉEL</b>
 * ({@code -Ddh.db}), on donne des points, et on vérifie :
 *   1) le <b>numérateur</b> du header (`getPointsEarnedInTierSoFar`) et `getProgress()` <b>augmentent</b> ;
 *   2) le <b>palier</b> (`getTierByPoints`) et le <b>dénominateur</b> (`getMaxPointsInTier`) sont cohérents avec
 *      `battle_pass_v2_tiers.tab` (palier 1 → 2 = 9 points) ;
 *   3) l'état <b>PERSISTE</b> (round-trip wire battle pass : `battlePassWire()` → `setBattlePassWire()`).
 * Le compte réel n'est PAS modifié sur disque (aucune sauvegarde DB : on lit, on mute en mémoire, on prouve la
 * persistance par le round-trip wire).
 */
public final class BattlePassPointsTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "bpp");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "bpp");
    ServerContext.bind(u, iu);
    ServerContext.bindBattlePass(su.refreshBattlePass());   // wrapper sur NOTRE message persisté
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = System.getProperty("dh.db", "server/data/dh-server.db");
    ServerUser su;
    try (UserStore store = new UserStore(db)) { su = store.loadOrCreate(1L, 1); }

    long start = BattlePassV2Stats.getSeasonStartTime();
    User u = bind(su);
    IBattlePassV2Data live = com.perblue.heroes.DH.app.getUserBattlePassV2();

    int p0 = live.getProgress();
    int tier0 = BattlePassV2Helper.getTierByPoints(p0, start);
    int inTier0 = BattlePassV2Helper.getPointsEarnedInTierSoFar(p0, start);   // 1er arg = PROGRESS
    int maxInTier0 = BattlePassV2Helper.getMaxPointsInTier(tier0, start);      // 1er arg = TIER
    System.out.println("[bppoints] AVANT : progress=" + p0 + " → header ① tier=" + tier0
        + " compteur=" + inTier0 + "/" + maxInTier0);

    // --- VRAI CHEMIN DE RÉCOMPENSE DU JEU : donner 5 points de battle pass (QUEST_POINTS) ---
    long give = 5;
    RewardDrop drop = RewardHelper.createDrop(ResourceType.QUEST_POINTS, give);
    RewardHelper.giveReward(u, drop, RewardSourceType.NORMAL, "bppoints-test");

    int p1 = live.getProgress();
    int tier1 = BattlePassV2Helper.getTierByPoints(p1, start);
    int inTier1 = BattlePassV2Helper.getPointsEarnedInTierSoFar(p1, start);   // 1er arg = PROGRESS
    int maxInTier1 = BattlePassV2Helper.getMaxPointsInTier(tier1, start);      // 1er arg = TIER
    long asResource = u.getResource(ResourceType.QUEST_POINTS);
    System.out.println("[bppoints] APRÈS don " + give + " (RewardHelper.giveReward QUEST_POINTS) : progress=" + p1
        + " getResource(QUEST_POINTS)=" + asResource + " → header ① tier=" + tier1 + " compteur=" + inTier1 + "/" + maxInTier1);

    if (p1 != p0 + give) throw new AssertionError("progress aurait dû passer de " + p0 + " à " + (p0 + give) + " (obtenu " + p1 + ")");
    if (asResource != p1) throw new AssertionError("getResource(QUEST_POINTS) doit refléter la progression");
    if (maxInTier1 != 9) throw new AssertionError("dénominateur attendu 9 (seuil palier 2, battle_pass_v2_tiers.tab), obtenu " + maxInTier1);
    if (inTier1 != inTier0 + give) throw new AssertionError("le NUMÉRATEUR du header n'a pas avancé de " + give);

    // --- PERSISTANCE : round-trip wire du battle pass (comme la colonne BLOB de la DB) ---
    byte[] bpWire = su.battlePassWire();
    ServerUser su2 = ServerUser.newPlayer(1L, 1);
    su2.setBattlePassWire(bpWire);
    ServerContext.bindBattlePass(su2.refreshBattlePass());
    int pReload = com.perblue.heroes.DH.app.getUserBattlePassV2().getProgress();
    System.out.println("[bppoints] APRÈS round-trip wire → progress=" + pReload);
    if (pReload != p1) throw new AssertionError("la progression du compteur aurait dû PERSISTER (" + p1 + "), obtenu " + pReload);

    System.out.println("[bppoints] OK — le compteur « ① " + inTier1 + "/9 » est incrémenté par le VRAI chemin de "
        + "récompense du jeu (QUEST_POINTS→progress), cohérent avec les paliers, et PERSISTE. Serveur-autoritatif.");
  }
}
