import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import dhserver.*;
import java.util.*;

/**
 * PORT (#72) incrément 1 — COMBAT d'un mode « difficulty » ({@code DifficultyModeAttack} → {@code recordOutcome}).
 * Le serveur ré-exécute la logique du jeu : anti-triche (mode ouvert/cooldown/quota), crédit du butin, cooldown.
 * Persistance round-trip. On utilise PORT_DOCKS (ouvert le jour serveur du test).
 */
public final class PortAttackTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[port] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8700L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    GameMode mode = GameMode.PORT_DOCKS;
    com.perblue.heroes.game.specialevent.SpecialEventSnapshot NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
    check(DifficultyModeHelper.isOpen(mode, su.gameUser(), NONE), "PORT_DOCKS ouvert (jour serveur)");
    CooldownType cd = DifficultyModeHelper.getCooldownType(mode);
    check(su.gameUser().getCooldownEnd(cd) <= com.perblue.heroes.util.TimeUtil.serverTimeNow(), "pas de cooldown au départ");

    long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

    // Butin (client-reporté) : 5000 GOLD.
    RewardDrop loot = new RewardDrop();
    loot.resourceType = ResourceType.GOLD; loot.itemType = ItemType.DEFAULT; loot.quantity = 5000;
    DifficultyModeAttack m = new DifficultyModeAttack();
    m.gameMode = mode;
    m.modeDifficulty = 1;
    m.stagesCleared = 3;
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>(Collections.singletonList(loot));
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN;
    m.base.stars = 3;
    m.base.attackers = new ArrayList<>();
    m.base.defenders = new ArrayList<>();

    su.recordDifficultyModeAttack(m);

    long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
    check(goldAfter == goldBefore + 5000, "butin GOLD crédité (giveLoot) : " + goldBefore + "→" + goldAfter);
    long cdEnd = su.gameUser().getCooldownEnd(cd);
    check(cdEnd > com.perblue.heroes.util.TimeUtil.serverTimeNow(), "cooldown d'attaque posé (" + cd + ")");
    System.out.println("[port] PORT_DOCKS WIN : +5000 GOLD + cooldown " + cd + " posé ✔");

    // --- Persistance round-trip wire + DB : gold + cooldown ---
    ServerUser rl = ServerUser.fromWire(8700L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getResource(ResourceType.GOLD) == goldAfter, "GOLD survit au round-trip wire");
    check(rl.gameUser().getCooldownEnd(cd) == cdEnd, "cooldown survit au round-trip wire (" + rl.gameUser().getCooldownEnd(cd) + ")");
    java.io.File db = java.io.File.createTempFile("port", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8700L, 1);
    check(fromDb != null && fromDb.gameUser().getResource(ResourceType.GOLD) == goldAfter
        && fromDb.gameUser().getCooldownEnd(cd) == cdEnd, "GOLD + cooldown persistés en DB");
    store.close(); db.delete();
    System.out.println("[port] persistance (wire + DB) : GOLD + cooldown ✔");

    System.out.println("[port] OK — combat mode difficulty (recordOutcome : loot + cooldown) + persistance (headless).");
  }
}
