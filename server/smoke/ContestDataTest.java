import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.ClientContestData;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST incr.2 — état per-user SERVEUR-AUTORITATIF ({@link AllContestData}/{@link ContestData}) + persistance +
 * réponse {@code GetAllContestData}.
 *
 * <p>(1) {@code ServerContestData.getContestData} sert un état FRAIS (0 point, maps initialisées) puis persisté ;
 * (2) l'objet d'exécution {@code ClientContestData} enveloppe le {@code ContestData} wire → ses setters écrivent DANS le wire
 * (write-through) ; (3) round-trip WIRE ({@code contestWire}/{@code setContestWire}) préserve points+compteurs ;
 * (4) {@code response} expose le {@code ContestData} de chaque contest ACTIF (composant {@code Contest} du snapshot).
 */
public final class ContestDataTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestdata] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 960_050L;
    ServerUser su = ServerUser.newPlayer(9871L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());

    // (1) état FRAIS servi + posé sur su.
    ContestData cd = ServerContestData.getContestData(su, cid);
    check(cd != null && cd.extraData != null, "ContestData frais + extraData");
    check(cd.progressPoints == 0 && cd.rankPoints == 0, "frais = 0 point");
    check(cd.extraData.taskCompletionCount != null && cd.extraData.earnedProgressRewards != null, "maps/list initialisées");
    check(su.contestDataOrNull() != null && su.contestDataOrNull().contests.containsKey(cid), "posé dans le blob par-user");
    System.out.println("[contestdata] frais servi + posé (0 point, maps init) ✔");

    // (2) write-through via ClientContestData (l'objet d'exécution de ContestHelper). NB : le jeu LIE progress↔rank
    // (setRankPoints incrémente aussi progressPoints du delta) → on écrit rankPoints sur le champ wire pour un test isolé.
    ClientContestData ccd = ServerContestData.clientData(cid, cd);
    ccd.setProgressPoints(500L);
    ccd.setCompletedCount(0, 3L);
    cd.rankPoints = 500L;   // champ wire direct (évite l'effet de bord progress += delta de setRankPoints)
    check(cd.progressPoints == 500L, "setProgressPoints écrit dans le wire (" + cd.progressPoints + ")");
    check(ccd.getCompletedCount(0) == 3L, "setCompletedCount → getCompletedCount (" + ccd.getCompletedCount(0) + ")");
    System.out.println("[contestdata] write-through ClientContestData → wire (500 pts, tâche0=3) ✔");

    // (3) persistance WIRE : round-trip contestWire → setContestWire préserve points + compteurs.
    byte[] blob = su.contestWire();
    check(blob != null && blob.length > 0, "blob wire non vide");
    ServerUser su2 = ServerUser.newPlayer(9871L, 1);
    su2.setContestWire(blob);
    AllContestData reloaded = su2.contestDataOrNull();
    check(reloaded != null && reloaded.contests.containsKey(cid), "blob relu contient le contest");
    ContestData rc = (ContestData) reloaded.contests.get(cid);
    check(rc.progressPoints == 500L && rc.rankPoints == 500L, "points persistés (" + rc.progressPoints + ")");
    check(rc.extraData.taskCompletionCount.get(0) != null && ((Long) rc.extraData.taskCompletionCount.get(0)) == 3L, "compteur de tâche persisté");
    System.out.println("[contestdata] round-trip WIRE → points+compteurs préservés ✔");

    // (4) response expose les contests ACTIFS.
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();
    AllContestData resp = ServerContestData.response(su);
    check(resp.contests != null && resp.contests.containsKey(cid), "response expose le contest actif (id=" + cid + ")");
    System.out.println("[contestdata] response GetAllContestData → " + resp.contests.size() + " contest(s) actif(s) ✔");

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestdata] OK — état per-user CONTEST : frais/write-through/persistance/response. [incr.2 headless]");
  }
}
