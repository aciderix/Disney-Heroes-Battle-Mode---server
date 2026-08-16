import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import dhserver.*;
import java.util.*;

/**
 * PORT (#72) incrément 3 — RÉCOMPENSE DOUBLE (regarder une vidéo pour doubler le butin d'un combat de mode
 * « difficulty »). Après un combat SANS le VIP {@code DOUBLE_PORT_REWARDS} (VIP < 4), la logique du jeu ({@code giveLoot})
 * pose un {@code DoubleVideoLootContainer} ; l'{@code Action CLAIM_DOUBLE_PORT_REWARDS} → {@code claimDoubleRewards}
 * crédite ce butin une 2ᵉ fois puis vide le container. Anti-triche : re-claim → {@code DOUBLE_REWARDS_NOT_AVAILABLE}.
 *
 * <p>Le container est un état de SESSION (non persisté par le jeu — aucun champ wire) : mémorisé sur le {@code ServerUser}
 * (caché par connexion en jeu). On vérifie donc combat→claim sur la MÊME instance ServerUser.
 */
public final class PortDoubleRewardTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[portdbl] " + m); }

  static Action claimDouble() { Action a = new Action(); a.command = CommandType.CLAIM_DOUBLE_PORT_REWARDS; return a; }

  static DifficultyModeAttack combat(GameMode mode, long gold) {
    RewardDrop loot = new RewardDrop();
    loot.resourceType = ResourceType.GOLD; loot.itemType = ItemType.DEFAULT; loot.quantity = gold;
    DifficultyModeAttack m = new DifficultyModeAttack();
    m.gameMode = mode; m.modeDifficulty = 1; m.stagesCleared = 3;
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>(Collections.singletonList(loot));
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
    m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8720L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    // VIP 0 : PAS de DOUBLE_PORT_REWARDS (VIP 4) → giveLoot crédite ×1 + pose le container (sinon auto-doublé, pas de claim).
    su.bootData().userInfo.basicInfo.vIPLevel = 0;
    var NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;

    GameMode mode = null;
    for (GameMode c : new GameMode[]{GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE}) {
      if (DifficultyModeHelper.isOpen(c, su.gameUser(), NONE)) { mode = c; break; }
    }
    check(mode != null, "au moins un mode PORT ouvert le jour serveur");
    System.out.println("[portdbl] mode ouvert = " + mode);

    long gold0 = su.gameUser().getResource(ResourceType.GOLD);

    // (1) Combat → +5000 GOLD + pose le container de récompense double.
    su.recordDifficultyModeAttack(combat(mode, 5000));
    long gold1 = su.gameUser().getResource(ResourceType.GOLD);
    check(gold1 == gold0 + 5000, "combat crédite +5000 : " + gold0 + "→" + gold1);

    // (2) CLAIM récompense double → +5000 encore (doublement).
    boolean claimed = su.applyAction(claimDouble());
    check(claimed, "CLAIM_DOUBLE_PORT_REWARDS appliqué");
    long gold2 = su.gameUser().getResource(ResourceType.GOLD);
    check(gold2 == gold1 + 5000, "récompense double crédite +5000 (total +10000) : " + gold1 + "→" + gold2);
    System.out.println("[portdbl] combat +5000 puis double +5000 (total +10000) ✔");

    // (3) Anti-triche : re-claim sans container → refusé (DOUBLE_REWARDS_NOT_AVAILABLE), GOLD inchangé.
    boolean reclaim = su.applyAction(claimDouble());
    check(!reclaim, "re-claim refusé (container consommé)");
    check(su.gameUser().getResource(ResourceType.GOLD) == gold2, "GOLD inchangé après re-claim refusé");
    System.out.println("[portdbl] anti-triche re-claim (DOUBLE_REWARDS_NOT_AVAILABLE) ✔");

    // (4) Claim SANS combat préalable (nouvelle session) → refusé d'emblée.
    ServerUser fresh = ServerUser.newPlayer(8721L, 1);
    fresh.bootData().userInfo.basicInfo.teamLevel = 200;
    check(!fresh.applyAction(claimDouble()), "claim sans combat refusé (rien à réclamer)");
    System.out.println("[portdbl] claim sans combat refusé ✔");

    // (5) Persistance du GOLD crédité (le container de session, lui, n'est pas persisté = fidèle au jeu).
    ServerUser rl = ServerUser.fromWire(8720L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getResource(ResourceType.GOLD) == gold2, "GOLD (combat+double) survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("portdbl", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8720L, 1);
    check(fromDb != null && fromDb.gameUser().getResource(ResourceType.GOLD) == gold2, "GOLD persisté en DB");
    store.close(); db.delete();
    System.out.println("[portdbl] persistance GOLD (wire + DB) ✔");

    System.out.println("[portdbl] OK — récompense double (claimDoubleRewards) + anti-triche + persistance (headless).");
  }
}
