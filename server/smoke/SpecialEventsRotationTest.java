import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import com.badlogic.gdx.utils.IntSet;
import dhserver.*;

/**
 * SPECIAL_EVENTS incr. 3 — SÉMANTIQUE D'OUVERTURE des modes PORT (fait §8, bytecode {@code DifficultyModeHelper.isOpen}).
 *
 * <p><b>Vraie structure</b> (relevée au bytecode, corrige une lecture antérieure) :
 * <pre>isOpen(mode) = isModeOpen(mode)                    [MODES_OPEN = OVERRIDE, ouvre quel que soit le jour]
 *              OR isModeDropBonusActive(mode)             [DropBonus = OVERRIDE aussi, quel que soit le jour]
 *              OR getOpenDays(mode).contains(dayOfWeek)   [DÉFAUT : rotation par jour, table DU JEU, SANS event]</pre>
 *
 * ⇒ <b>La ROTATION QUOTIDIENNE est le comportement PAR DÉFAUT du jeu</b> (DOCKS [6,4,2,1] / WAREHOUSE [7,5,3,1]) — aucun
 * événement requis. MODES_OPEN et DropBonus sont des <b>OVERRIDES OPÉRATEUR</b> (live-ops : forcer un mode ouvert un jour
 * hors planning). On prouve donc : (1) sans event, {@code isOpen} == {@code getOpenDays.contains(jour)} ; (2) un override
 * (MODES_OPEN ou DropBonus) ouvre un mode NORMALEMENT FERMÉ ce jour ; (3) retirer l'override → le mode se referme (défaut).
 */
public final class SpecialEventsRotationTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[rotation] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8761L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    com.perblue.heroes.game.objects.User u = su.gameUser();   // bind (installe des défauts, qu'on remplace ci-dessous)

    IntSet docksDays = DifficultyModeHelper.getOpenDays(GameMode.PORT_DOCKS);
    IntSet whDays    = DifficultyModeHelper.getOpenDays(GameMode.PORT_WAREHOUSE);
    check(docksDays.size > 0 && whDays.size > 0, "getOpenDays non vides");
    System.out.println("[rotation] getOpenDays DU JEU : DOCKS=" + docksDays + " WAREHOUSE=" + whDays);

    // ⚠️ DÉTERMINISME (fait §8) : isOpen calcule le jour depuis snapshot.snapshotTime — NON depuis serverTimeNow. Le jour
    // serveur réel n'est pas fiable pour ce test : DOCKS [6,4,2,1] et WAREHOUSE [7,5,3,1] couvrent les 7 jours, mais le
    // jour 1 (dimanche-équivalent) est le SEUL où LES DEUX sont ouverts → aucun mode « fermé » pour démontrer l'override.
    // On ANCRE donc le temps à un jour où PORT_DOCKS est FERMÉ (existe toujours dans une fenêtre de 7 jours, jours {3,5,7}),
    // via ServerEvents.snapshotAt(T) (mêmes events installés, snapshotTime figé). Le test devient indépendant du calendrier.
    final long DAY = 24L * 3600_000L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long anchor = -1L; int dow = -1;
    for (int k = 0; k < 7; k++) {
      long t = now + k * DAY;
      int d = com.perblue.heroes.util.TimeUtil.getUserDailyActivityDayOfWeek(u, t);
      if (!docksDays.contains(d)) { anchor = t; dow = d; break; }
    }
    check(anchor >= 0, "un jour où PORT_DOCKS est fermé existe dans la fenêtre de 7 jours");
    final GameMode closed = GameMode.PORT_DOCKS;   // fermé au jour ancré (déterministe)

    // (1) SANS aucun event : isOpen suit la table getOpenDays (rotation DÉFAUT du jeu), au jour ancré.
    ServerEvents.install(java.util.Collections.emptyList());
    boolean docksDefault = DifficultyModeHelper.isOpen(GameMode.PORT_DOCKS, u, ServerEvents.snapshotAt(anchor));
    boolean whDefault     = DifficultyModeHelper.isOpen(GameMode.PORT_WAREHOUSE, u, ServerEvents.snapshotAt(anchor));
    System.out.println("[rotation] SANS event, jour ancré=" + dow + " : DOCKS=" + docksDefault + " (att " + docksDays.contains(dow)
        + "), WAREHOUSE=" + whDefault + " (att " + whDays.contains(dow) + ")");
    check(docksDefault == docksDays.contains(dow), "DOCKS défaut == getOpenDays.contains(jour)");
    check(whDefault    == whDays.contains(dow),    "WAREHOUSE défaut == getOpenDays.contains(jour)");
    check(!docksDefault, "PORT_DOCKS fermé au jour ancré (prémisse de l'override)");

    // (2a) OVERRIDE MODES_OPEN : ouvre le mode fermé.
    ServerEvents.install(java.util.Collections.singletonList(
        ServerEvents.buildModesOpenEvent(910_010L, java.util.Collections.singletonList(closed),
            ServerEvents.defaultStart(), ServerEvents.defaultEnd())));
    check(DifficultyModeHelper.isOpen(closed, u, ServerEvents.snapshotAt(anchor)),
        "MODES_OPEN override ouvre " + closed + " (fermé en défaut ce jour)");
    System.out.println("[rotation] override MODES_OPEN : " + closed + " ouvert (jour hors planning) ✔");

    // (2b) OVERRIDE DropBonus : ouvre AUSSI le mode fermé (isModeDropBonusActive).
    ServerEvents.install(java.util.Collections.singletonList(
        ServerEvents.buildDropBonusEvent(910_011L, java.util.Collections.singletonList(closed),
            1, ServerEvents.defaultStart(), ServerEvents.defaultEnd())));
    check(DifficultyModeHelper.isOpen(closed, u, ServerEvents.snapshotAt(anchor)),
        "DropBonus override ouvre " + closed + " (fermé en défaut ce jour)");
    System.out.println("[rotation] override DropBonus : " + closed + " ouvert (jour hors planning) ✔");

    // (3) Retrait de l'override → le mode se REFERME (retour au défaut getOpenDays).
    ServerEvents.install(java.util.Collections.emptyList());
    check(!DifficultyModeHelper.isOpen(closed, u, ServerEvents.snapshotAt(anchor)),
        closed + " refermé après retrait de l'override (retour au défaut)");
    System.out.println("[rotation] retrait override : " + closed + " refermé (défaut) ✔");

    System.out.println("[rotation] OK — rotation par jour = DÉFAUT du jeu (getOpenDays) ; MODES_OPEN/DropBonus = overrides opérateur (headless).");
  }
}
