import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.codebase.CodebaseStats;
import com.perblue.heroes.game.data.campaign.CampaignStats;
import dhserver.*;
import java.util.*;

/**
 * CODEBASE (« The Codebase ») — restauration serveur-autoritative du mode de difficulté (headless).
 * Prouve la LOGIQUE RÉELLE (pas de faux endpoint) sur 3 axes :
 *   1) CLASSEMENT/journal per-shard {@link ServerCodebase} : topScores triés ↓ + bornés, recent en tête + borné (bornes lues
 *      des `.tab` : ATTACK_LOG_MAX_TOP_ROWS/RECENT_ROWS), round-trip wire {@code CodebaseAttackLogs} + persistance DB.
 *   2) ANTI-TRICHE RÉELLE : un compte NON débloqué (chapitre 41) est REFUSÉ par la vraie logique du jeu (ClientErrorCodeException) —
 *      preuve qu'on exécute {@code CodebaseHelper.recordOutcome}, pas un stub.
 *   3) CHEMIN NOMINAL : compte débloqué (chapitre 41 + team level + héros JAUNE dans les attaquants) → {@code recordCodebaseAttack}
 *      met à jour les HIGH SCORES per-user ({@code individualUserExtra}, write-through) + persiste au round-trip wire.
 */
public final class CodebaseTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[codebase] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ---------- 1) CLASSEMENT per-shard : tri + bornes + round-trip + DB ----------
    int maxTop = ((CodebaseStats.Constants) CodebaseStats.CONSTANTS.getStats()).ATTACK_LOG_MAX_TOP_ROWS;
    int maxRecent = ((CodebaseStats.Constants) CodebaseStats.CONSTANTS.getStats()).ATTACK_LOG_MAX_RECENT_ROWS;
    check(maxTop > 0 && maxRecent > 0, "bornes lues des .tab (" + maxTop + "/" + maxRecent + ")");

    int iter = 7;   // ID d'itération (= CodebaseAttack.codebaseID)
    CodebaseWeakness wk = CodebaseWeakness.STUN;
    CodebaseAttackLogs logs = new CodebaseAttackLogs(); logs.logs = new HashMap<>();
    // Insère maxTop+5 attaques de scores croissants 100,200,… : le TOP doit garder les maxTop meilleurs, trié ↓.
    int n = maxTop + 5;
    for (int i = 1; i <= n; i++)
      ServerCodebase.recordAttack(logs, wk, new ArrayList<>(), i % 6, (long) (i * 100), 1000L + i);
    CodebaseAttackLog log = (CodebaseAttackLog) logs.logs.get(wk);
    check(log != null, "log d'itération créé");
    check(log.topScores.size() == maxTop, "topScores borné à maxTop (" + log.topScores.size() + ")");
    check(((CodebaseAttackInfo) log.topScores.get(0)).score == (long) (n * 100), "top #1 = meilleur score (" + ((CodebaseAttackInfo) log.topScores.get(0)).score + ")");
    for (int i = 1; i < log.topScores.size(); i++)
      check(((CodebaseAttackInfo) log.topScores.get(i - 1)).score >= ((CodebaseAttackInfo) log.topScores.get(i)).score, "topScores trié décroissant");
    check(log.recent.size() == maxRecent, "recent borné à maxRecent (" + log.recent.size() + ")");
    check(((CodebaseAttackInfo) log.recent.get(0)).score == (long) (n * 100), "recent #0 = la plus récente (" + ((CodebaseAttackInfo) log.recent.get(0)).score + ")");
    System.out.println("[codebase] classement : top trié↓/borné " + maxTop + ", recent en-tête/borné " + maxRecent + " ✔");

    WireCheck.assertRoundTrips(logs);
    System.out.println("[codebase] round-trip wire CodebaseAttackLogs ✔");

    java.io.File db = java.io.File.createTempFile("codebase", ".db"); db.delete();
    try (UserStore store = new UserStore(db.getPath())) {
      ServerCodebase.saveLogs(store, 1, logs);
      CodebaseAttackLogs reloaded = ServerCodebase.loadLogs(store, 1);
      CodebaseAttackLog rl = (CodebaseAttackLog) reloaded.logs.get(wk);
      check(rl != null && rl.topScores.size() == maxTop, "classement persiste en DB (per-shard)");
      check(((CodebaseAttackInfo) rl.topScores.get(0)).score == (long) (n * 100), "top #1 survit à la DB");
    }
    db.delete();
    System.out.println("[codebase] persistance DB per-shard (loadShardState/saveShardState) ✔");

    // ---------- 2) ANTI-TRICHE : compte NON débloqué → refus par la vraie logique du jeu ----------
    ServerUser locked = ServerUser.newPlayer(4201L, 1);
    boolean refused = false;
    try {
      locked.recordCodebaseAttack(attack(iter, 500L, 12f, UnitType.RALPH));
    } catch (com.perblue.heroes.ClientErrorCodeException e) {
      refused = true;
      System.out.println("[codebase] compte verrouillé REFUSÉ par recordOutcome (" + e.getMessage() + ") ✔ [logique réelle]");
    }
    check(refused, "un compte non débloqué DOIT être refusé (preuve : vraie logique du jeu exécutée)");

    // ---------- 3) CHEMIN NOMINAL : débloqué + héros jaune → high scores mis à jour + persistés ----------
    ServerUser su = ServerUser.newPlayer(4202L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 300;                       // au-delà du gate team-level
    int reqChap = CodebaseStats.getRequiredCampaignChapter();               // 41 (codebase_constants.tab)
    int maxIdx = CampaignStats.getMaxLevelIndex(CampaignType.NORMAL, reqChap);
    su.grantCampaignLevel(CampaignType.NORMAL, reqChap, maxIdx, 3);         // chapitre requis TERMINÉ (Unlockable.CODEBASE)
    su.grantHero(UnitType.RALPH, Rarity.YELLOW, 200, 6);                    // héros JAUNE (CODEBASE_REQUIRES_YELLOW_HERO)

    long score1 = 750L;
    CodebaseAttackInfo entry = su.recordCodebaseAttack(attack(iter, score1, 40f, UnitType.RALPH));
    check(entry != null && entry.score == score1, "entrée de journal renvoyée (score " + score1 + ")");
    long high = su.bootData().individualUserExtra.currentCodebaseHighScore;
    check(high == score1, "currentCodebaseHighScore mis à jour (" + high + " == " + score1 + ") [write-through §3]");
    check(su.bootData().individualUserExtra.lifetimeCodebaseHighScore == score1, "lifetimeCodebaseHighScore mis à jour");
    check(su.bootData().individualUserExtra.currentCodebaseID == iter, "currentCodebaseID = itération (" + iter + ")");
    System.out.println("[codebase] chemin nominal : recordOutcome accepté → high score " + high + " (per-user, write-through) ✔");

    // persistance round-trip wire
    ServerUser rl2 = ServerUser.fromWire(4202L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl2.bootData().individualUserExtra.currentCodebaseHighScore == score1, "high score survit au round-trip wire");
    System.out.println("[codebase] high score persiste au round-trip wire ✔");

    System.out.println("[codebase] OK — mode restauré (classement per-shard + recordOutcome autoritatif + high scores persistés), headless.");
  }

  /** Construit un {@code CodebaseAttack} WIN minimal : 1 attaquant du type donné (le predicat « jaune » lit le rôster). */
  static CodebaseAttack attack(int iterationID, long finalScore, float megavirusDamage, UnitType heroType) {
    CodebaseAttack m = new CodebaseAttack();
    m.codebaseID = iterationID;
    m.finalScore = finalScore;
    m.megavirusTotalDamageTaken = megavirusDamage;
    m.finalWeaknessCount = 0;
    m.weakness = CodebaseWeakness.DEFAULT;
    m.minorBuffs = new ArrayList<>();
    m.lootEarned = new ArrayList<>();
    m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    m.base = new AttackBase();
    m.base.outcome = CombatOutcome.WIN;
    m.base.stars = 3;
    AttackUnitSummary aus = new AttackUnitSummary();
    aus.type = heroType; aus.rarity = Rarity.YELLOW; aus.power = 1000L; aus.survived = true;
    AttackLineupSummary als = new AttackLineupSummary();
    als.units = new ArrayList<>(Collections.singletonList(aus));
    m.base.attackers = new ArrayList<>(Collections.singletonList(als));
    m.base.defenders = new ArrayList<>();
    return m;
  }
}
