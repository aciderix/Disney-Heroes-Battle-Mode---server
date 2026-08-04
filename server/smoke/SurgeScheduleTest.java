/**
 * SURGE (#72) incrément 1 — CALENDRIER : le serveur calcule la fenêtre du surge via le CODE DU JEU
 * ({@code SurgeHelper.getNextSurgeStartTime}/{@code getSurgeEndTime}, {@code SurgeStats.getIntermission}), pas de
 * date inventée (§3/§4). Assertions RELATIONNELLES (pas d'horaires absolus) pour rester déterministe.
 */
public final class SurgeScheduleTest {

  static void check(boolean cond, String msg) { if (!cond) throw new AssertionError("[surge-schedule] " + msg); }

  public static void main(String[] a) throws Exception {
    dhserver.ServerContext.init();
    long now = System.currentTimeMillis();

    long end = dhserver.ServerSurge.surgeEndTime(now);
    long next = dhserver.ServerSurge.nextSurgeStartTime(now);
    boolean active = dhserver.ServerSurge.isActive(now);

    // Cohérence de la règle « actif ⟺ prochain départ après la fin courante » (cf. ServerSurge, prouvé §8).
    check(active == (next > end), "isActive doit valoir (nextStart > end) : active=" + active + " next=" + next + " end=" + end);

    // Entracte = valeur du jeu (15 min mesuré) ; strictement positif.
    long inter = dhserver.ServerSurge.intermission();
    check(inter == 900000L, "intermission attendue 900000 ms (SurgeStats), obtenu " + inter);

    // La fenêtre est cohérente : fin et prochain départ sont dans le futur proche (le jeu calcule à partir de now).
    check(end > now, "surgeEndTime doit être dans le futur");
    check(next > now, "nextSurgeStartTime doit être dans le futur");

    // Identité : id opaque stable = fin de fenêtre si actif, 0 sinon.
    long id = dhserver.ServerSurge.currentSurgeID(now);
    check(active ? (id == end) : (id == 0L), "currentSurgeID incohérent (active=" + active + ", id=" + id + ", end=" + end + ")");

    // Déterminisme : rappeler donne le même résultat (pur calcul du jeu).
    check(dhserver.ServerSurge.surgeEndTime(now) == end && dhserver.ServerSurge.nextSurgeStartTime(now) == next,
        "le calendrier doit être déterministe pour un même now");

    System.out.println("[surge-schedule] OK — fenêtre via code du jeu (actif=" + active + ", entracte=" + inter
        + "ms, surgeID=" + id + ") — #72 incrément 1");
  }
}
