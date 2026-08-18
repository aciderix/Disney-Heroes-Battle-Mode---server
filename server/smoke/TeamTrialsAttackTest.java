import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (#72) incr. 1 — QUICK WIN : <b>TEAM_TRIALS_BLUE réutilise TOUTE l'infra PORT</b> (fait décisif §11).
 *
 * <p><b>Fait établi (§8, bytecode)</b> : {@code TEAM_TRIALS_{BLUE,RED,YELLOW}} + {@code SPOTLIGHT_TRIAL} sont des
 * {@code GameMode} du sous-système {@code DifficultyModeHelper}, EXACTEMENT comme {@code PORT_DOCKS}/{@code PORT_WAREHOUSE} :
 * <ul>
 *   <li>{@code DifficultyModeHelper.getOpenDays} branche {@code TEAM_TRIALS_BLUE → TrialsHelper.BLUE_OPEN_DAYS} (même
 *       {@code switch} que PORT — vérifié au bytecode) ⇒ rotation par jour = DÉFAUT du jeu ;</li>
 *   <li>ouverture hors planning via un OVERRIDE opérateur MODES_OPEN ({@code ServerEvents}, chemin {@code AdminEvents --open}) ;</li>
 *   <li>combat client-autoritatif {@code DifficultyModeAttack} → {@code ServerUser.recordDifficultyModeAttack} →
 *       {@code DifficultyModeHelper.recordOutcome} (le {@code GameMode} n'est qu'un PARAMÈTRE) — <b>ZÉRO nouveau code combat</b>.</li>
 * </ul>
 *
 * <p>On prouve donc, par le chemin FIDÈLE (override opérateur MODES_OPEN, PAS le flag debug) :
 * (1) sans event, {@code isOpen(TEAM_TRIALS_BLUE)} == {@code getOpenDays.contains(jour)} ;
 * (2) un OVERRIDE MODES_OPEN ouvre TEAM_TRIALS_BLUE quel que soit le jour ;
 * (3) un combat WIN crédite le butin (client-reporté §4bis) + pose le cooldown du mode, via le MÊME {@code recordOutcome} que PORT ;
 * (4) persistance round-trip wire + DB ; (5) override retiré → le mode revient au défaut (état propre).
 *
 * <p>Gate réel {@code Unlockable.TEAM_TRIALS} (TL 55) atteint par l'état légitime (teamLevel=200), jamais désactivé (§8).
 */
public final class TeamTrialsAttackTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[teamtrials] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8740L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;   // au-delà du gate TEAM_TRIALS (TL55)
    com.perblue.heroes.game.objects.User u = su.gameUser();
    GameMode mode = GameMode.TEAM_TRIALS_BLUE;

    // (1) SANS event opérateur : isOpen suit la table getOpenDays (rotation DÉFAUT du jeu) — TEAM_TRIALS_BLUE traité comme PORT.
    // NB : ServerContext.bind() ré-installe TOUJOURS les événements OPÉRATEUR (installBootDefaults) à chaque bind — donc on
    // pilote l'ouverture par setOperatorEvents (chemin AdminEvents, persisté au boot), pas par install() (que le prochain bind écrase).
    ServerEvents.setOperatorEvents(Collections.emptyList());
    ServerEvents.installBootDefaults();
    com.badlogic.gdx.utils.IntSet blueDays = DifficultyModeHelper.getOpenDays(mode);
    check(blueDays != null && blueDays.size > 0, "getOpenDays(TEAM_TRIALS_BLUE) non vide (branche BLUE_OPEN_DAYS) : " + blueDays);
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    int dow = com.perblue.heroes.util.TimeUtil.getUserDailyActivityDayOfWeek(u, now);
    boolean def = DifficultyModeHelper.isOpen(mode, u, ServerEvents.snapshot());
    check(def == blueDays.contains(dow), "TEAM_TRIALS_BLUE défaut == getOpenDays.contains(jour=" + dow + ")");
    System.out.println("[teamtrials] getOpenDays(BLUE)=" + blueDays + " jour=" + dow + " défaut isOpen=" + def + " ✔");

    // (2) OVERRIDE MODES_OPEN opérateur (chemin AdminEvents --open) : ouvre TEAM_TRIALS_BLUE quel que soit le jour.
    // On le pose en événement OPÉRATEUR → réinstallé par installBootDefaults à chaque bind (y compris le bind interne de recordOutcome).
    ServerEvents.setOperatorEvents(Collections.singletonList(
        ServerEvents.buildModesOpenEvent(940_010L, Collections.singletonList(mode),
            ServerEvents.defaultStart(), ServerEvents.defaultEnd())));
    ServerEvents.installBootDefaults();
    check(DifficultyModeHelper.isOpen(mode, u, ServerEvents.snapshot()),
        "MODES_OPEN override ouvre TEAM_TRIALS_BLUE (chemin opérateur fidèle, pas de debug)");
    System.out.println("[teamtrials] override MODES_OPEN → TEAM_TRIALS_BLUE ouvert ✔");

    // (3) COMBAT WIN via le MÊME recordDifficultyModeAttack que PORT (le GameMode n'est qu'un paramètre).
    CooldownType cd = DifficultyModeHelper.getCooldownType(mode);
    check(su.gameUser().getCooldownEnd(cd) <= com.perblue.heroes.util.TimeUtil.serverTimeNow(), "pas de cooldown au départ");
    long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

    RewardDrop loot = new RewardDrop();
    loot.resourceType = ResourceType.GOLD; loot.itemType = ItemType.DEFAULT; loot.quantity = 6000;
    DifficultyModeAttack m = new DifficultyModeAttack();
    m.gameMode = mode; m.modeDifficulty = 1; m.stagesCleared = 3;
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>(Collections.singletonList(loot));
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
    m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
    su.recordDifficultyModeAttack(m);

    long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
    check(goldAfter == goldBefore + 6000, "butin TEAM_TRIALS_BLUE crédité (+6000) : " + goldBefore + "→" + goldAfter);
    long cdEnd = su.gameUser().getCooldownEnd(cd);
    check(cdEnd > com.perblue.heroes.util.TimeUtil.serverTimeNow(), "cooldown TEAM_TRIALS_BLUE posé (" + cd + ")");
    System.out.println("[teamtrials] WIN : +6000 GOLD + cooldown " + cd + " posé (recordOutcome PORT réutilisé) ✔");

    // (4) Persistance round-trip wire + DB.
    ServerUser rl = ServerUser.fromWire(8740L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getResource(ResourceType.GOLD) == goldAfter, "GOLD survit au round-trip wire");
    check(rl.gameUser().getCooldownEnd(cd) == cdEnd, "cooldown survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("teamtrials", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8740L, 1);
    check(fromDb != null && fromDb.gameUser().getResource(ResourceType.GOLD) == goldAfter
        && fromDb.gameUser().getCooldownEnd(cd) == cdEnd, "GOLD + cooldown persistés en DB");
    store.close(); db.delete();
    System.out.println("[teamtrials] persistance (wire + DB) ✔");

    // (5) Override retiré → TEAM_TRIALS_BLUE revient au défaut (rotation par jour), état propre.
    ServerEvents.setOperatorEvents(Collections.emptyList());
    ServerEvents.installBootDefaults();
    check(DifficultyModeHelper.isOpen(mode, u, ServerEvents.snapshot()) == blueDays.contains(dow),
        "override retiré → TEAM_TRIALS_BLUE revient au défaut getOpenDays");

    System.out.println("[teamtrials] OK — TEAM_TRIALS_BLUE réutilise l'infra PORT (getOpenDays + MODES_OPEN + recordOutcome), zéro nouveau code combat (headless).");
  }
}
