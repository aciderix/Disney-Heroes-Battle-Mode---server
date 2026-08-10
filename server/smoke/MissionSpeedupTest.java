import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.FriendshipHelper;
import com.perblue.heroes.game.missions.MissionHelper;
import dhserver.*;

/**
 * FRIENDSHIPS (#72) incrément 3c — SPEEDUP_MISSION + SET_MISSION_ITEM_COST_LIMIT (actions QoL des missions idle).
 * Tout par le code du jeu (§3, {@code MissionHelper.getSpeedupData}/{@code useSpeedups} + {@code IndividualUser.
 * setMissionItemCostLimit}), zéro invention (§4). Exercé via {@link ServerMissions} (chemin des handlers).
 */
public final class MissionSpeedupTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[mission-speedup] " + m); }

  static double baseTime(ServerUser su, FriendPairID pair) {
    for (Object o : su.gameUser().getIndividual().getMissions()) {
      com.perblue.heroes.game.missions.IMission mm = (com.perblue.heroes.game.missions.IMission) o;
      if (mm.getFriendship().equals(pair)) return mm.getBaseTimeRemaining();
    }
    return -1;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4801L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
    su.grantHero(UnitType.VANELLOPE, Rarity.ORANGE, 60, 5);
    FriendPairID pair = FriendPairID.of(UnitType.RALPH, UnitType.VANELLOPE);
    check(FriendshipHelper.getUnlockStatus(su.gameUser(), pair) == FriendshipHelper.FriendPairStatus.UNLOCKED,
        "paire débloquée");

    // --- SET_MISSION_ITEM_COST_LIMIT : plafond write-through ---
    check(ServerMissions.applySetItemCostLimit(su, ItemType.STONE_VANELLOPE, 3), "SET_ITEM_COST_LIMIT accepté");
    check(su.gameUser().getIndividual().getMissionItemCostLimit(ItemType.STONE_VANELLOPE) == 3, "plafond = 3");
    // Persistance DB.
    String db = System.getProperty("java.io.tmpdir") + "/dh-mspeedup-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4801L, 1);
    check(rl.gameUser().getIndividual().getMissionItemCostLimit(ItemType.STONE_VANELLOPE) == 3,
        "plafond survit à la DB");
    // 0 retire l'entrée.
    check(ServerMissions.applySetItemCostLimit(rl, ItemType.STONE_VANELLOPE, 0), "SET_ITEM_COST_LIMIT(0) accepté");
    check(rl.gameUser().getIndividual().getMissionItemCostLimit(ItemType.STONE_VANELLOPE) == 0, "plafond retiré (0)");
    System.out.println("[mission-speedup] SET_MISSION_ITEM_COST_LIMIT OK (3 → persiste → 0 retire)");

    // --- SPEEDUP_MISSION : accélère une mission POWER_UP (sans coût) ---
    check(ServerMissions.applyAddMission(rl, MissionType.POWER_UP_MISSION, pair), "ADD POWER_UP accepté");
    double t0 = baseTime(rl, pair);
    check(t0 > 0, "mission en cours avec du temps restant (t0=" + t0 + ")");
    long speedupDur = com.perblue.heroes.game.data.missions.MissionStats.getSpeedupDuration();
    rl.giveItem(ItemType.MISSION_SPEEDUP, 5);
    int stockBefore = rl.gameUser().getIndividual().getItemAmount(ItemType.MISSION_SPEEDUP);

    check(ServerMissions.applySpeedupMission(rl, pair.getPrimary(), ItemType.MISSION_SPEEDUP, 3),
        "SPEEDUP_MISSION accepté");
    double t1 = baseTime(rl, pair);
    int stockAfter = rl.gameUser().getIndividual().getItemAmount(ItemType.MISSION_SPEEDUP);
    System.out.println("[mission-speedup] SPEEDUP : baseTimeRemaining " + t0 + "→" + t1
        + " (dur/objet=" + speedupDur + "), MISSION_SPEEDUP " + stockBefore + "→" + stockAfter);
    check(t1 < t0, "temps restant RÉDUIT par le speedup (" + t0 + "→" + t1 + ")");
    check(stockAfter < stockBefore, "MISSION_SPEEDUP consommés (" + stockBefore + "→" + stockAfter + ")");

    // Persistance de l'état accéléré.
    store.save(rl);
    ServerUser rl2 = store.loadIfExists(4801L, 1);
    double t2 = baseTime(rl2, pair);
    check(Math.abs(t2 - t1) < 1.0, "temps accéléré survit à la DB (t1=" + t1 + " t2=" + t2 + ")");
    WireCheck.assertRoundTrips(rl2.gameUser().getIndividual().getExtra());
    store.close();

    System.out.println("[mission-speedup] OK — SPEEDUP_MISSION + SET_MISSION_ITEM_COST_LIMIT via le code du jeu — #72 incrément 3c");
  }
}
