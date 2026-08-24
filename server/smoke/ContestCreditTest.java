import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.ContestHelper;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST incr.3 — WIRING des tâches : un déclencheur du jeu ({@code ContestHelper.on*}) crédite des POINTS dans l'état
 * per-user PERSISTÉ (§3, on exécute la logique du jeu). {@code ServerContestData.record} pré-peuple {@code user.getContestData()}
 * avec le {@code ClientContestData} enveloppant le blob → le jeu mute EN PLACE notre blob. Cas : un contest {@code BATTLE_WON}
 * (10 pts/combat) → {@code onCampaignAttack(WIN)} crédite 10 points ; un 2ᵉ combat → 20 ; persistance WIRE.
 */
public final class ContestCreditTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestcredit] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 960_060L;
    ServerUser su = ServerUser.newPlayer(9881L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // Contest BATTLE_WON : 10 points par combat gagné (countNeeded 1).
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();

    com.perblue.heroes.game.objects.User user = su.gameUser();
    long p0 = ServerContestData.getContestData(su, cid).progressPoints;
    check(p0 == 0, "départ 0 point (" + p0 + ")");

    // 1er combat GAGNÉ → onCampaignAttack(WIN) crédite les tâches BATTLE_WON.
    ServerContestData.record(su, user, u ->
        ContestHelper.onCampaignAttack(u, GameMode.CAMPAIGN, CombatOutcome.WIN, new ArrayList<>(), new ArrayList<>()));
    long p1 = ServerContestData.getContestData(su, cid).progressPoints;
    System.out.println("[contestcredit] après 1 combat gagné : " + p1 + " point(s)");
    check(p1 > 0, "BATTLE_WON crédite des points (" + p1 + ")");

    // 2ᵉ combat gagné → cumul.
    ServerContestData.record(su, user, u ->
        ContestHelper.onCampaignAttack(u, GameMode.CAMPAIGN, CombatOutcome.WIN, new ArrayList<>(), new ArrayList<>()));
    long p2 = ServerContestData.getContestData(su, cid).progressPoints;
    System.out.println("[contestcredit] après 2 combats gagnés : " + p2 + " point(s)");
    check(p2 > p1, "2ᵉ combat cumule (" + p2 + " > " + p1 + ")");

    // Persistance WIRE.
    byte[] blob = su.contestWire();
    ServerUser su2 = ServerUser.newPlayer(9881L, 1);
    su2.setContestWire(blob);
    ContestData rc = (ContestData) su2.contestDataOrNull().contests.get(cid);
    check(rc != null && rc.progressPoints == p2, "points persistés au round-trip WIRE (" + (rc == null ? "null" : rc.progressPoints) + ")");
    System.out.println("[contestcredit] persistance WIRE → " + rc.progressPoints + " points préservés ✔");

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestcredit] OK — ContestHelper.onCampaignAttack crédite le blob per-user (BATTLE_WON), persiste. [incr.3 headless]");
  }
}
