import dhserver.*;
import java.util.*;

/**
 * ANCRE DE SAISON DÉCOUPLÉE DES TIMERS JOUEUR (séparation horloge ↔ config admin).
 *
 * <p>Prouve que régler l'ancre de saison ({@code ServerContext.setSeasonAnchorOffsetMillis}) change la SAISON
 * sélectionnée ({@code ServerEvents.seasonTrialConfigs}/{@code seasonTrialFranchises}) SANS déplacer :
 * (1) l'horloge serveur {@code serverTimeNow()} ; (2) les TIMERS JOUEUR ({@code DailyActivityHelper.getLastDailyResetTime}/
 * {@code getNextDailyResetTime}, la borne de reset quotidien). C'est la garantie demandée : « changer la saison
 * n'impacte pas la réinitialisation dans X temps des joueurs ». Défaut (ancre 0) → la saison suit la date réelle
 * (comportement historique inchangé).
 */
public final class SeasonAnchorTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[seasonanchor] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerContext.setSeasonAnchorOffsetMillis(0L);   // état de départ propre (défaut)

    ServerUser su = ServerUser.newPlayer(7701L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());

    // ── Référence : ancre 0 → saison courante + témoins de timers joueur ────────────────────────────────
    List<String> seasonDefault = ServerEvents.seasonTrialFranchises(0);
    check(!seasonDefault.isEmpty(), "saison par défaut non vide (" + seasonDefault + ")");
    long lastResetBefore = com.perblue.heroes.game.logic.DailyActivityHelper.getLastDailyResetTime(su.gameUser());
    long nextResetBefore = com.perblue.heroes.game.logic.DailyActivityHelper.getNextDailyResetTime(su.gameUser());
    check(ServerContext.seasonAnchorOffsetMillis() == 0L, "ancre par défaut = 0");
    System.out.println("[seasonanchor] ancre 0 : saison t0=" + seasonDefault
        + " | reset quotidien [" + lastResetBefore + " → " + nextResetBefore + "] ✔");

    // ── Recule la SAISON de ~2 ans (2024) : franchise DIFFÉRENTE attendue, timers INCHANGÉS ──────────────
    long twoYears = -2L * 365 * 24 * 3600_000L;
    ServerContext.setSeasonAnchorOffsetMillis(twoYears);
    List<String> seasonPast = ServerEvents.seasonTrialFranchises(0);
    check(!seasonPast.isEmpty(), "saison passée non vide (" + seasonPast + ")");
    check(!seasonPast.equals(seasonDefault),
        "la saison DOIT changer avec l'ancre (" + seasonDefault + " → " + seasonPast + ")");

    long lastResetAfter = com.perblue.heroes.game.logic.DailyActivityHelper.getLastDailyResetTime(su.gameUser());
    long nextResetAfter = com.perblue.heroes.game.logic.DailyActivityHelper.getNextDailyResetTime(su.gameUser());
    // Les bornes de reset quotidien sont calées sur serverTimeNow (RESET_HOUR du jour) → IDENTIQUES malgré l'ancre.
    check(lastResetAfter == lastResetBefore,
        "reset quotidien (last) INCHANGÉ par l'ancre de saison : " + lastResetBefore + " vs " + lastResetAfter);
    check(nextResetAfter == nextResetBefore,
        "reset quotidien (next) INCHANGÉ par l'ancre de saison : " + nextResetBefore + " vs " + nextResetAfter);
    // seasonTimeNow doit avoir bougé de ~2 ans ; serverTimeNow ne DOIT PAS (dérive murale < 1 min tolérée).
    long drift = Math.abs((ServerContext.seasonTimeNow() - twoYears) - com.perblue.heroes.util.TimeUtil.serverTimeNow());
    check(drift < 60_000L, "seasonTimeNow = serverTimeNow + ancre (dérive " + drift + " ms)");
    System.out.println("[seasonanchor] ancre -2 ans : saison t0=" + seasonPast
        + " (≠ défaut) | reset quotidien INCHANGÉ [" + lastResetAfter + " → " + nextResetAfter + "] ✔");

    // ── Retour à l'ancre 0 → saison de nouveau la courante (réversible, sans effet de bord) ──────────────
    ServerContext.setSeasonAnchorOffsetMillis(0L);
    List<String> seasonBack = ServerEvents.seasonTrialFranchises(0);
    check(seasonBack.equals(seasonDefault),
        "retour ancre 0 → saison courante rétablie (" + seasonDefault + " vs " + seasonBack + ")");
    System.out.println("[seasonanchor] retour ancre 0 : saison t0=" + seasonBack + " (== défaut) ✔");

    System.out.println("[seasonanchor] OK — ancre de saison ADMIN découplée : la saison suit l'ancre, "
        + "les timers joueur (reset quotidien) suivent l'horloge réelle. [headless]");
  }
}
