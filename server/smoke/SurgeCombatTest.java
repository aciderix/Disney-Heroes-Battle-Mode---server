import com.perblue.heroes.game.data.surge.SurgeStats;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerSurge;
import dhserver.ServerSurgeCombat;
import dhserver.ServerUser;

/**
 * SURGE (#72) incrément 4a — ENREGISTREMENT de combat de région : le serveur rejoue {@code SurgeHelper.recordOutcome}
 * (code du jeu) sur la summary du membre à partir d'un {@link SurgeAttack} rapporté par le client. Vérifie que le
 * chemin autoritatif s'exécute headless et MUTE le membre (progression d'objectif) — params prouvés au bytecode.
 */
public final class SurgeCombatTest {

  static void check(boolean c, String m) { if (!c) throw new AssertionError("[surge-combat] " + m); }

  static AttackUnitSummary unit(UnitType t, long power) {
    AttackUnitSummary u = new AttackUnitSummary();
    u.type = t; u.power = power; u.rarity = Rarity.ORANGE; u.survived = true;
    return u;
  }
  static AttackLineupSummary lineup(AttackUnitSummary... us) {
    AttackLineupSummary l = new AttackLineupSummary();
    l.units = new java.util.ArrayList<>(java.util.Arrays.asList(us));
    return l;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5);       // héros possédé → getHero(RALPH) != null
    com.perblue.heroes.game.objects.User u = su.gameUser();   // lie le contexte de jeu (DH.app)

    SurgeMemberSummary summary = new SurgeMemberSummary();
    summary.user = su.basicInfo();
    summary.objectiveProgress = new java.util.HashMap<>();

    // Combat rapporté : WIN, attaquant = RALPH (héros du joueur), défenseur avec de la puissance à vaincre.
    SurgeAttack m = new SurgeAttack();
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN;
    m.base.stars = 3;
    m.base.attackers = new java.util.ArrayList<>(java.util.Arrays.asList(lineup(unit(UnitType.RALPH, 5000))));
    m.base.defenders = new java.util.ArrayList<>(java.util.Arrays.asList(lineup(unit(UnitType.ELASTIGIRL, 4000))));
    m.district = SurgeStats.getHQDistrict();
    m.usedAutoSelect = false;
    m.wasRecommended = false;
    // objectiveProgress = {SurgeObjectiveInfo(slot 0) → 1} : le client qualifie l'objectif du slot 0.
    m.objectiveProgress = new java.util.HashMap<>();
    SurgeObjectiveInfo obj = new SurgeObjectiveInfo();
    obj.slot = 0; obj.type = SurgeObjectiveType.values()[0]; obj.rarity = SurgeObjectiveRarity.values()[0];
    m.objectiveProgress.put(obj, 1);

    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long surgeID = ServerSurge.currentSurgeID(now);

    // EXÉCUTE la logique autoritative du jeu (recordOutcome) — ne doit pas planter et doit muter le membre.
    ServerSurgeCombat.applyRegionOutcome(u, summary, surgeID, 0L, m, SpecialEventSnapshot.NONE);

    // recordObjectiveProgress a incrémenté la progression de l'objectif slot 0 (via SurgeClientMember → summary).
    Object p0 = summary.objectiveProgress.get(0);
    check(p0 != null && ((Number) p0).intValue() >= 1,
        "la progression d'objectif du slot 0 doit être ≥ 1 après un combat qualifiant, obtenu " + p0);

    System.out.println("[surge-combat] OK — recordOutcome exécuté headless (objectif slot0=" + p0
        + ", points=" + summary.totalPointsGained + ", or=" + summary.storedGold + ") — #72 incrément 4a");
  }
}
