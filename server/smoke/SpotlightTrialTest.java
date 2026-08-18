import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import com.perblue.heroes.game.logic.SpotlightTrialHelper;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (#72) incr. 1 — QUICK WIN #2 : <b>SPOTLIGHT_TRIAL réutilise l'infra PORT</b> + conso {@code spotlightTrialUses}
 * (fait décisif §11 + recon g135).
 *
 * <p><b>Fait établi (§8, bytecode)</b> : {@code SPOTLIGHT_TRIAL} est un {@code GameMode} du sous-système {@code DifficultyModeHelper}
 * (cooldown {@code SPOTLIGHT_TRIAL_ATTACK}, VIP {@code SPOTLIGHT_TRIAL_COOLDOWN}). Contrairement aux Team Trials, sa rotation par
 * défaut est VIDE ({@code getOpenDays(SPOTLIGHT_TRIAL)=[]}) → il n'est ouvrable QUE par un override opérateur MODES_OPEN
 * ({@code SpotlightTrialHelper.getSpecialEvent} cherche justement un event MODES_OPEN ouvrant {@code GameMode.SPOTLIGHT}).
 * <b>Auto-suffisant</b> : {@code recordOutcome} (offset 206) appelle lui-même {@code SpotlightTrialHelper.onSpotlightTrialUse}
 * (incrémente {@code spotlightTrialUses} = {@code getTotalEventUses}+1, clé = eventID) → <b>zéro nouveau code combat</b>.
 *
 * <p>On prouve, par le chemin FIDÈLE (override opérateur MODES_OPEN) :
 * (1) sans event, {@code isOpen(SPOTLIGHT_TRIAL)}=false (getOpenDays vide) ; (2) override MODES_OPEN → ouvert +
 * {@code isSpotlightTrialActive}=true ; (3) combat WIN → butin crédité + cooldown {@code SPOTLIGHT_TRIAL_ATTACK} +
 * <b>{@code spotlightTrialUses} incrémenté (0→1)</b> via {@code onSpotlightTrialUse} DANS {@code recordOutcome} ;
 * (4) persistance round-trip wire + DB (uses + cooldown).
 */
public final class SpotlightTrialTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[spotlight] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8750L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    com.perblue.heroes.game.objects.User u = su.gameUser();
    GameMode mode = GameMode.SPOTLIGHT_TRIAL;

    // (1) SANS event : getOpenDays(SPOTLIGHT)=[] → fermé par défaut (aucune rotation ; purement event-driven).
    ServerEvents.setOperatorEvents(Collections.emptyList());
    ServerEvents.installBootDefaults();
    com.badlogic.gdx.utils.IntSet days = DifficultyModeHelper.getOpenDays(mode);
    check(days != null && days.size == 0, "getOpenDays(SPOTLIGHT_TRIAL) VIDE (event-driven pur) : " + days);
    check(!DifficultyModeHelper.isOpen(mode, u, ServerEvents.snapshot()), "SPOTLIGHT fermé sans event");
    System.out.println("[spotlight] getOpenDays(SPOTLIGHT)=[] → fermé par défaut ✔");

    // (2) OVERRIDE MODES_OPEN opérateur (chemin AdminEvents --open) : ouvre SPOTLIGHT + le rend actif.
    ServerEvents.setOperatorEvents(Collections.singletonList(
        ServerEvents.buildModesOpenEvent(950_010L, Collections.singletonList(mode),
            ServerEvents.defaultStart(), ServerEvents.defaultEnd())));
    ServerEvents.installBootDefaults();
    check(DifficultyModeHelper.isOpen(mode, u, ServerEvents.snapshot()), "MODES_OPEN override ouvre SPOTLIGHT_TRIAL");
    check(SpotlightTrialHelper.isSpotlightTrialActive(u, ServerEvents.snapshot()), "isSpotlightTrialActive=true (event MODES_OPEN reconnu)");
    System.out.println("[spotlight] override MODES_OPEN → SPOTLIGHT ouvert + actif ✔");

    // (3) COMBAT WIN via recordDifficultyModeAttack → recordOutcome (crédit + cooldown + onSpotlightTrialUse).
    // ⚠️ FAIT §8 : SPOTLIGHT n'a qu'UNE difficulté valide par shard = SpotlightTrialStats.getDifficultyForShard(shardID)
    // (isVisible l'EXIGE : diff != celle du shard → GAME_MODE_LOCKED). On la lit (pas de valeur en dur §4).
    com.perblue.heroes.game.data.ModeDifficulty spotDiff =
        com.perblue.heroes.game.data.teamtrials.SpotlightTrialStats.get().getDifficultyForShard(su.gameUser().getShardID());
    int usesBefore = su.gameIndividual().getSpotlightTrialUses();
    CooldownType cd = DifficultyModeHelper.getCooldownType(mode);
    check(su.gameUser().getCooldownEnd(cd) <= com.perblue.heroes.util.TimeUtil.serverTimeNow(), "pas de cooldown au départ");
    long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

    RewardDrop loot = new RewardDrop();
    loot.resourceType = ResourceType.GOLD; loot.itemType = ItemType.DEFAULT; loot.quantity = 5000;
    DifficultyModeAttack m = new DifficultyModeAttack();
    m.gameMode = mode; m.modeDifficulty = spotDiff.getIndex(); m.stagesCleared = 3;
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.lootEarned = new ArrayList<>(Collections.singletonList(loot));
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
    m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
    su.recordDifficultyModeAttack(m);

    long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
    check(goldAfter == goldBefore + 5000, "butin SPOTLIGHT crédité (+5000) : " + goldBefore + "→" + goldAfter);
    long cdEnd = su.gameUser().getCooldownEnd(cd);
    check(cdEnd > com.perblue.heroes.util.TimeUtil.serverTimeNow(), "cooldown SPOTLIGHT_TRIAL_ATTACK posé");
    int usesAfter = su.gameIndividual().getSpotlightTrialUses();
    check(usesAfter == usesBefore + 1, "spotlightTrialUses incrémenté (" + usesBefore + "→" + usesAfter + ") via onSpotlightTrialUse DANS recordOutcome");
    System.out.println("[spotlight] WIN : +5000 GOLD + cooldown + spotlightTrialUses " + usesBefore + "→" + usesAfter + " ✔");

    // (4) Persistance round-trip wire + DB (uses + cooldown).
    ServerUser rl = ServerUser.fromWire(8750L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameIndividual().getSpotlightTrialUses() == usesAfter, "spotlightTrialUses survit au round-trip wire");
    check(rl.gameUser().getCooldownEnd(cd) == cdEnd, "cooldown survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("spotlight", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8750L, 1);
    check(fromDb != null && fromDb.gameIndividual().getSpotlightTrialUses() == usesAfter
        && fromDb.gameUser().getCooldownEnd(cd) == cdEnd, "spotlightTrialUses + cooldown persistés en DB");
    store.close(); db.delete();
    System.out.println("[spotlight] persistance (wire + DB : uses + cooldown) ✔");

    // (5) Override retiré → SPOTLIGHT re-fermé (état propre).
    ServerEvents.setOperatorEvents(Collections.emptyList());
    ServerEvents.installBootDefaults();
    check(!DifficultyModeHelper.isOpen(mode, u, ServerEvents.snapshot()), "override retiré → SPOTLIGHT re-fermé");

    System.out.println("[spotlight] OK — SPOTLIGHT_TRIAL réutilise l'infra PORT + conso spotlightTrialUses dans recordOutcome (headless).");
  }
}
