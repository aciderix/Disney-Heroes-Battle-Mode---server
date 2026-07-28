import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerInvasion;

/**
 * INVASION #69 (socle) — le CALENDRIER et l'IDENTITÉ de l'invasion sont dérivés des DONNÉES DU JEU
 * ({@code invasion_constants.tab} : START_DAY/START_TIME, END_DAY/END_TIME, INVASION_BASE_DATE,
 * INVASION_BASE_ROTATION ; {@code UnitStats.getTeam} pour les héros d'équipe). Aucune valeur inventée.
 *
 * Prouve : fenêtre lundi 12h → samedi 12h, rotation ancrée sur la date de base, cohérence semaine à semaine,
 * héros d'équipe non vides, et cohérence de l'InvasionInfo servi au client.
 */
public final class InvasionScheduleTest {
  static long utc(String iso) { return java.time.Instant.parse(iso).toEpochMilli(); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // Les constantes lues sont bien celles du jeu.
    if (ServerInvasion.baseRotation() != 23)
      throw new AssertionError("INVASION_BASE_ROTATION attendu 23, lu " + ServerInvasion.baseRotation());
    if (ServerInvasion.baseDate() != 1645920000000L)
      throw new AssertionError("INVASION_BASE_DATE inattendu : " + ServerInvasion.baseDate());
    System.out.println("[invasion] constantes du jeu lues : base=" + new java.util.Date(ServerInvasion.baseDate())
        + " rotation=" + ServerInvasion.baseRotation() + " " + ServerInvasion.startDay() + "→" + ServerInvasion.endDay());

    // Mercredi 2026-07-01 08:00Z : l'invasion en cours a commencé le LUNDI 2026-06-29 à 12:00Z.
    long wed = utc("2026-07-01T08:00:00Z");
    long start = ServerInvasion.invasionStart(wed);
    java.time.ZonedDateTime zs = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC);
    if (zs.getDayOfWeek() != java.time.DayOfWeek.MONDAY || zs.getHour() != 12)
      throw new AssertionError("début attendu LUNDI 12:00Z, obtenu " + zs);
    long end = ServerInvasion.invasionEnd(start);
    java.time.ZonedDateTime ze = java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneOffset.UTC);
    if (ze.getDayOfWeek() != java.time.DayOfWeek.SATURDAY || ze.getHour() != 12)
      throw new AssertionError("fin attendue SAMEDI 12:00Z, obtenue " + ze);
    if (!ServerInvasion.isActive(wed)) throw new AssertionError("mercredi devrait être EN COURS");
    System.out.println("[invasion] fenêtre : " + zs + " → " + ze + " (mercredi = actif)");

    // Dimanche : hors fenêtre (l'invasion s'est terminée samedi 12h).
    long sun = utc("2026-07-05T08:00:00Z");
    if (ServerInvasion.isActive(sun)) throw new AssertionError("dimanche NE devrait PAS être actif");
    if (ServerInvasion.buildInfo(sun).currentInvasion != null)
      throw new AssertionError("hors fenêtre : currentInvasion doit être nul");
    System.out.println("[invasion] dimanche : hors fenêtre (pas d'invasion courante) ✔");

    // Lundi 11:59Z = encore la semaine PRÉCÉDENTE ; 12:00Z = nouvelle invasion.
    long monBefore = utc("2026-06-29T11:59:00Z"), monAfter = utc("2026-06-29T12:00:00Z");
    if (ServerInvasion.invasionStart(monBefore) >= ServerInvasion.invasionStart(monAfter))
      throw new AssertionError("la bascule du lundi 12:00 est incorrecte");
    System.out.println("[invasion] bascule lundi 12:00Z correcte (avant → semaine précédente)");

    // Rotation : +1 par semaine, et exacte à la date de base.
    int r1 = ServerInvasion.rotation(start);
    int r2 = ServerInvasion.rotation(start + 7L * 86400_000L);
    if (r2 != r1 + 1) throw new AssertionError("la rotation doit croître de 1 par semaine (" + r1 + "→" + r2 + ")");
    if (ServerInvasion.rotation(ServerInvasion.baseDate()) != ServerInvasion.baseRotation())
      throw new AssertionError("à INVASION_BASE_DATE la rotation doit valoir INVASION_BASE_ROTATION");
    System.out.println("[invasion] rotation ancrée sur les données : #" + r1 + " puis #" + r2 + " la semaine suivante");

    // L'équipe tourne et ses héros viennent des données du jeu.
    HeroTeam t = ServerInvasion.teamForRotation(r1);
    if (t == null || t == HeroTeam.NONE) throw new AssertionError("équipe vedette invalide");
    java.util.List<UnitType> heroes = ServerInvasion.teamHeroes(t);
    if (heroes.size() < 50) throw new AssertionError("équipe " + t + " : " + heroes.size() + " héros (trop peu)");
    if (ServerInvasion.teamForRotation(r1) == ServerInvasion.teamForRotation(r1 + 1))
      throw new AssertionError("l'équipe doit changer d'une rotation à l'autre");
    System.out.println("[invasion] équipe #" + r1 + " = " + t + " (" + heroes.size()
        + " héros via UnitStats.getTeam), change à la rotation suivante");

    // InvasionInfo servi au client : cohérent avec la fenêtre.
    InvasionInfo info = ServerInvasion.buildInfo(wed);
    if (info.currentInvasion == null || info.currentInvasion.invasion == null)
      throw new AssertionError("invasion courante manquante en pleine fenêtre");
    InvasionData d = info.currentInvasion.invasion;
    if (d.startTime != start || d.endTime != end) throw new AssertionError("fenêtre incohérente dans InvasionData");
    if (d.rotationNumber != r1 || d.team != t) throw new AssertionError("rotation/équipe incohérentes");
    if (d.teamHeroes == null || d.teamHeroes.isEmpty()) throw new AssertionError("teamHeroes vide");
    if (info.nextInvasionStartTime <= start) throw new AssertionError("prochaine invasion mal calculée");
    System.out.println("[invasion] InvasionInfo cohérent (rotation #" + d.rotationNumber + ", équipe " + d.team
        + ", " + d.teamHeroes.size() + " héros, prochaine le " + new java.util.Date(info.nextInvasionStartTime) + ")");

    System.out.println("INVASION SCHEDULE TEST OK");
  }
}
