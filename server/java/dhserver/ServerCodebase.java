package dhserver;

import com.perblue.heroes.network.messages.CodebaseAttackInfo;
import com.perblue.heroes.network.messages.CodebaseAttackLog;
import com.perblue.heroes.network.messages.CodebaseAttackLogs;
import com.perblue.heroes.network.messages.HeroSummary;
import com.perblue.heroes.network.messages.HeroLineupType;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.game.data.codebase.CodebaseStats;
import com.perblue.heroes.game.codebase.CodebaseHelper;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * CODEBASE (« The Codebase ») — état SERVEUR-AUTORITATIF du classement/journal d'attaques (le seul état du mode qui ne vit
 * pas déjà dans le wire per-user). La PROGRESSION per-user (high scores courant + à vie) est portée par
 * {@code IndividualUserExtra.{current,lifetime}Codebase{HighScore,HighRageLevel}}+{@code currentCodebaseID} (write-through §3,
 * auto-persistée) ; c'est {@code CodebaseHelper.recordOutcome} (exécuté par le serveur, cf. {@link ServerUser#recordCodebaseAttack})
 * qui la met à jour. Ici on tient le blob PAR-SHARD {@code CodebaseAttackLogs} = {@code Map<iterationID, CodebaseAttackLog>} —
 * chaque {@code CodebaseAttackLog{topScores, recent}} de {@code CodebaseAttackInfo{lineup, rageLevel, score, attackTime}} —
 * exactement comme les ladders/rankings serveur-autoritatifs (Arena/Invasion) : stocké via
 * {@code UserStore.loadShardState/saveShardState(shardID, "codebase_logs")} (round-trip wire). Bornes lues des `.tab`
 * ({@code CodebaseStats$Constants.ATTACK_LOG_MAX_TOP_ROWS / ATTACK_LOG_MAX_RECENT_ROWS}) — §4, jamais en dur.
 */
public final class ServerCodebase {
  private ServerCodebase() {}

  static final String SHARD_KEY = "codebase_logs";

  /** ID d'itération courant (rotation déterministe du jeu : {@code SCHEDULING_EPOCH}+{@code AVAILABLE_DAYS}, aucun event). */
  public static int currentIteration(User user) {
    return CodebaseHelper.getCurrentIterationID(user);
  }

  private static int maxTopRows() {
    return ((CodebaseStats.Constants) CodebaseStats.CONSTANTS.getStats()).ATTACK_LOG_MAX_TOP_ROWS;
  }

  private static int maxRecentRows() {
    return ((CodebaseStats.Constants) CodebaseStats.CONSTANTS.getStats()).ATTACK_LOG_MAX_RECENT_ROWS;
  }

  /** Charge le blob des journaux du shard (jamais null ; map non-null). */
  public static CodebaseAttackLogs loadLogs(UserStore store, int shardID) {
    CodebaseAttackLogs logs = null;
    try {
      byte[] b = store.loadShardState(shardID, SHARD_KEY);
      if (b != null && b.length > 0)
        logs = (CodebaseAttackLogs) MessageFactory.getInstance().readMessage(new GruntInputStream(b));
    } catch (Exception e) {
      System.out.println("[codebase] journaux illisibles, réinitialisés : " + e);
    }
    if (logs == null) logs = new CodebaseAttackLogs();
    if (logs.logs == null) logs.logs = new HashMap<>();
    return logs;
  }

  /** Persiste le blob des journaux du shard (round-trip wire, même en-tête que MessageFactory.readMessage). */
  public static void saveLogs(UserStore store, int shardID, CodebaseAttackLogs logs) throws java.sql.SQLException {
    GruntOutputStream go = new GruntOutputStream();
    logs.writeAll(go);
    store.saveShardState(shardID, SHARD_KEY, go.getBytes());
  }

  /** Le lineup CODEBASE réel du joueur en {@code List<HeroSummary>} (roster → getHeroSummary, logique du jeu §3). */
  public static List<HeroSummary> lineupOf(User user) {
    List<HeroSummary> out = new ArrayList<>();
    try {
      com.perblue.heroes.network.messages.HeroLineup hl = user.getHeroLineup(HeroLineupType.CODEBASE, 0L);
      if (hl != null && hl.heroes != null) {
        for (Object o : hl.heroes) {
          UnitData ud = (UnitData) user.getHero((UnitType) o);
          if (ud != null) out.add(ClientNetworkStateConverter.getHeroSummary(ud));
        }
      }
    } catch (Throwable t) { /* pas de lineup posé → journal sans portraits, dégradation sûre */ }
    return out;
  }

  /**
   * Insère une attaque dans le journal de SA FAIBLESSE (le blob est {@code Map<CodebaseWeakness, CodebaseAttackLog>} — le journal
   * est groupé par faiblesse du méga-virus, celle que {@code CodebaseAttackLogScreen} affiche pour la faiblesse courante). RECENT
   * (plus récent en tête) et TOP (score décroissant), chacun borné par sa constante `.tab`. Mute {@code logs} en place ;
   * l'appelant persiste ensuite via {@link #saveLogs}.
   */
  public static void recordAttack(CodebaseAttackLogs logs, com.perblue.heroes.network.messages.CodebaseWeakness weakness,
      List<HeroSummary> lineup, int rageLevel, long score, long attackTime) {
    CodebaseAttackInfo info = new CodebaseAttackInfo();
    info.lineup = lineup == null ? new ArrayList<>() : new ArrayList<>(lineup);
    info.rageLevel = rageLevel;
    info.score = score;
    info.attackTime = attackTime;

    if (weakness == null) weakness = com.perblue.heroes.network.messages.CodebaseWeakness.DEFAULT;
    CodebaseAttackLog log = (CodebaseAttackLog) logs.logs.get(weakness);
    if (log == null) { log = new CodebaseAttackLog(); logs.logs.put(weakness, log); }
    if (log.recent == null) log.recent = new ArrayList<>();
    if (log.topScores == null) log.topScores = new ArrayList<>();

    int maxRecent = maxRecentRows();
    log.recent.add(0, info);
    while (log.recent.size() > maxRecent) log.recent.remove(log.recent.size() - 1);

    int maxTop = maxTopRows();
    log.topScores.add(info);
    log.topScores.sort((a, b) ->
        Long.compare(((CodebaseAttackInfo) b).score, ((CodebaseAttackInfo) a).score));
    while (log.topScores.size() > maxTop) log.topScores.remove(log.topScores.size() - 1);
  }
}
