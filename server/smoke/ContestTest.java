import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.common.specialevent.components.Contest;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composant <b>CONTEST</b> (leaderboard de tâches, solo ou guilde) — INCRÉMENT 1 : structure.
 *
 * <p>Via la LOGIQUE DU JEU (§3) : {@code ServerEvents.buildContestEvent} construit un {@code SpecialEventInfo} CONTEST
 * (schéma relevé au bytecode {@code Contest.load} : {@code contestInformation{guild,aggregate}} + {@code contestTask[]} +
 * {@code contestProgressRewards[]} + {@code contestRankRewards[]} ; {@code rewarditem} = tableau de drops en formatVersion 0).
 * (1) le composant charge (tasks/progress/ranks peuplés, guild/aggregate lus) ; (2) une fois installé, le snapshot expose le
 * CONTEST (composant {@code Contest} récupérable) ; (3) round-trip de la spec persistée (admin). Tâches + récompenses = params ADMIN.
 * NB : progression/classement/réclamation serveur-autoritatifs = incréments 2-4.
 */
public final class ContestTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contest] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  static SpecialEventInfo build(long id) {
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(
        new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""),
        new ServerEvents.ContestTask("ITEM_BURN", 5, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> progress = Arrays.asList(
        new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))),
        new ServerEvents.ContestProgress(500L, Collections.singletonList(item("ACE_OF_SPADES", 25))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(
        new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))),
        new ServerEvents.ContestRank(false, 1, Collections.singletonList(item("ACE_OF_SPADES", 500))));
    return ServerEvents.buildContestEvent(id, false, false, tasks, progress, ranks, now - 1000, now + 7L * 86_400_000L);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long id = 960_001L;

    // (1) le composant charge et peuple la structure.
    SpecialEventInfo ev = build(id);
    Contest c = (Contest) ev.getComponent(Contest.class);
    check(c != null, "composant Contest présent");
    check(c.getTasks().size() == 2, "2 tâches (" + c.getTasks().size() + ")");
    check(c.getProgressRewards().size() == 2, "2 paliers de progression (" + c.getProgressRewards().size() + ")");
    check(c.getRankRewards().size() == 2, "2 récompenses de rang (" + c.getRankRewards().size() + ")");
    check(!c.isGuildContest() && !c.isAggregateContest(), "solo (non guilde, non agrégé)");
    System.out.println("[contest] structure : 2 tâches, 2 paliers, 2 rangs, solo ✔");

    // (2) installé → le snapshot expose le CONTEST (getActiveEvents filtre par éligibilité → user bindé requis).
    ServerUser su = ServerUser.newPlayer(9761L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();
    com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
    boolean found = false;
    for (SpecialEventInfo e : (java.util.List<SpecialEventInfo>) snap.getActiveEvents()) if (e.getComponent(Contest.class) != null && e.getID() == id) found = true;
    check(found, "le snapshot expose le CONTEST installé");
    System.out.println("[contest] snapshot expose le CONTEST (id=" + id + ") ✔");

    // (3) round-trip de la spec persistée.
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    String spec = ServerEvents.specJsonContest(id, false, false, "Test Contest", "summary", tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(ServerEvents.writeConfig(Collections.singletonList(spec)));
    check(rebuilt.size() == 1 && rebuilt.get(0).getID() == id, "spec CONTEST round-trip (" + rebuilt.size() + ")");
    Contest rc = (Contest) rebuilt.get(0).getComponent(Contest.class);
    check(rc != null && rc.getTasks().size() == 1 && rc.getProgressRewards().size() == 1 && rc.getRankRewards().size() == 1,
        "event reconstruit → structure préservée");
    System.out.println("[contest] spec round-trip → CONTEST reconstruit (1 tâche/1 palier/1 rang) ✔");

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contest] OK — CONTEST objet du jeu, tâches + récompenses = params admin, structure+snapshot+spec. [incr.1 headless]");
  }
}
