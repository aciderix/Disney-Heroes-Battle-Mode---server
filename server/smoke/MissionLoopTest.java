import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.FriendshipHelper;
import com.perblue.heroes.game.missions.MissionHelper;
import dhserver.*;

/**
 * FRIENDSHIPS (#72) incrément 3c — MISSIONS IDLE d'amitié (cœur de l'écran MISSIONS 12.1.0).
 * Boucle ADD → (temps) → CLAIM + CANCEL + anti-triche + persistance, TOUT par le code du jeu (§3,
 * {@code MissionHelper}), zéro invention (§4). Exercé via {@link ServerMissions} (chemin des handlers).
 *
 * <p>Faits du jeu (sondés) : POWER_UP_MISSION = SANS coût, empReward=1, dur=60h → démarrable sur un compte
 * frais ; MEMORY_MISSION coûte 1 STONE_VANELLOPE → {@code CANT_AFFORD} (garde anti-triche) ; limite combinée = 1.
 * Le temps est simulé par {@code MissionHelper.debugHurryAllMissions} (méthode DEBUG DU JEU, outillage de test).
 */
public final class MissionLoopTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[mission] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4701L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
    su.grantHero(UnitType.VANELLOPE, Rarity.ORANGE, 60, 5);
    FriendPairID pair = FriendPairID.of(UnitType.RALPH, UnitType.VANELLOPE);
    check(FriendshipHelper.getUnlockStatus(su.gameUser(), pair) == FriendshipHelper.FriendPairStatus.UNLOCKED,
        "paire débloquée");

    // --- ADD : démarre une mission POWER_UP (sans coût) ---
    check(ServerMissions.applyAddMission(su, MissionType.POWER_UP_MISSION, pair), "ADD_MISSION accepté");
    check(MissionHelper.countMissions(su.gameUser()) == 1, "1 mission après ADD");
    System.out.println("[mission] ADD POWER_UP → countMissions=" + MissionHelper.countMissions(su.gameUser()));

    // --- ANTI-TRICHE : MEMORY sans STONE_VANELLOPE = CANT_AFFORD ; et limite combinée (1) atteinte ---
    check(!ServerMissions.applyAddMission(su, MissionType.MEMORY_MISSION, pair),
        "MEMORY refusée (coût STONE_VANELLOPE absent / limite combinée)");
    System.out.println("[mission] ADD MEMORY (non-affordable/limite) → refusé (anti-triche) ✓");

    // --- PERSISTANCE de la mission en cours ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-mission-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4701L, 1);
    check(MissionHelper.countMissions(rl.gameUser()) == 1, "mission survit à la persistance DB");
    check(rl.bootData().individualUserExtra.missions.size() == 1, "extra.missions persiste 1 MissionData");
    System.out.println("[mission] persistance : reload → countMissions="
        + MissionHelper.countMissions(rl.gameUser()));

    // --- CANCEL : annule par le héros primaire (protocole client) ---
    check(ServerMissions.applyCancelMission(rl, pair.getPrimary()), "CANCEL_MISSION accepté");
    check(MissionHelper.countMissions(rl.gameUser()) == 0, "0 mission après CANCEL");
    store.save(rl);
    ServerUser rl2 = store.loadIfExists(4701L, 1);
    check(MissionHelper.countMissions(rl2.gameUser()) == 0, "annulation persiste (0 mission)");
    System.out.println("[mission] CANCEL par héros " + pair.getPrimary() + " → countMissions=0 (persisté) ✓");

    // --- CLAIM : re-démarre, avance le temps (debug), réclame → empowerment crédité + claim vidé ---
    check(ServerMissions.applyAddMission(rl2, MissionType.POWER_UP_MISSION, pair), "2e ADD accepté");
    int empBefore = rl2.gameUser().getIndividual().getFriendship(pair).getEmpowerment();
    // Avance le temps via la méthode DEBUG du jeu (complète un cycle → génère MissionClaimData write-through).
    rl2.debugHurryMissions(1);
    check(rl2.gameUser().getIndividual().getMissionClaimData().iterator().hasNext(),
        "un MissionClaimData en attente après avance du temps");
    store.save(rl2);

    // Réclame par le handler serveur.
    ServerUser rl3 = store.loadIfExists(4701L, 1);
    check(ServerMissions.applyClaimMissionRewards(rl3), "CLAIM_MISSION_REWARDS a réclamé");
    int empAfter = rl3.gameUser().getIndividual().getFriendship(pair).getEmpowerment();
    check(!rl3.gameUser().getIndividual().getMissionClaimData().iterator().hasNext(),
        "missionClaimData vidé après réclamation");
    check(empAfter == empBefore + 1, "empowerment crédité (+1, empReward POWER_UP) : "
        + empBefore + "→" + empAfter);
    System.out.println("[mission] CLAIM → empowerment " + empBefore + "→" + empAfter + ", claim vidé ✓");

    // Persistance de la réclamation (empowerment).
    store.save(rl3);
    ServerUser rl4 = store.loadIfExists(4701L, 1);
    check(rl4.gameUser().getIndividual().getFriendship(pair).getEmpowerment() == empAfter,
        "empowerment réclamé survit à la DB");
    WireCheck.assertRoundTrips(rl4.gameUser().getIndividual().getExtra());
    store.close();

    System.out.println("[mission] OK — MISSIONS IDLE d'amitié (ADD/CLAIM/CANCEL + anti-triche + persistance) "
        + "via le code du jeu — #72 incrément 3c");
  }
}
