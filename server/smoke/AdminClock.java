import dhserver.ServerContext;
import dhserver.UserStore;

/**
 * PANNEAU ADMIN HORLOGE / ÈRE DE CONTENU (opérateur) — pendant d'{@code AdminInvasion}/{@code AdminWar}.
 *
 * <p>Le serveur est la SOURCE DE L'HEURE et l'ÈRE de contenu (R1…R102 : chapitres, Team Level max, rosters,
 * ÉCHELLE DE PUISSANCE des ennemis) est choisie PAR DATE via {@code ContentStats.getServerColumn(serverTimeNow)}.
 * Décaler l'horloge du serveur décale donc l'ère de façon cohérente (prouvé : 2026 → Max TL 565 / R102 ;
 * 2018 → Max TL 70). Cet outil règle l'ANCRE D'HORLOGE PERSISTÉE (méta {@code clock_offset_ms}) : un serveur
 * neuf peut ainsi DÉMARRER À R1 puis AVANCER — automatiquement au rythme réel (offset constant), ou par saut
 * d'ère décidé par l'opérateur. L'ancre survit aux redémarrages (ré-appliquée au boot par LoginServer) → aucune
 * dérive. AUCUNE règle contournée (PRINCIPLES §2) : on avance/recule la pendule, la logique datée du jeu suit.
 *
 * <pre>
 * Usage :
 *   AdminClock [--db …] [--shard 1] --status
 *   AdminClock [--db …] --set-date &lt;yyyy-MM-dd&gt;     (heure de jeu = cette date maintenant)
 *   AdminClock [--db …] --offset-hours &lt;h&gt;          (h négatif = reculer l'ère ; positif = avancer)
 *   AdminClock [--db …] --reset                     (heure réelle)
 * </pre>
 * NB : le serveur applique l'ancre AU BOOT → redémarrer le serveur après un changement.
 */
public final class AdminClock {

  static void status(UserStore store, int shard) throws Exception {
    com.perblue.heroes.game.data.content.ContentHelper.get().setShardID(shard, new java.util.HashMap<>());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    String date = new org.joda.time.DateTime(now, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone())
        .toString("yyyy-MM-dd HH:mm");
    com.perblue.heroes.game.data.content.ContentStats.ContentColumn col =
        com.perblue.heroes.game.data.content.ContentHelper.getCurrent(now);
    Long persisted = store.getMetaLong("clock_offset_ms");
    System.out.println("[clock] offset courant " + ServerContext.clockOffsetMillis() + " ms"
        + " (persisté en DB : " + (persisted == null ? "aucun" : persisted + " ms") + ")");
    System.out.println("[clock] heure de JEU = " + date
        + " | ère de contenu : Max Team Level = " + (col == null ? "?" : col.getMaxTeamLevel())
        + " (shard " + shard + ", content." + shard + ".tab)");
  }

  public static void main(String[] a) throws Exception {
    java.util.Map<String, String> opt = new java.util.HashMap<>();
    boolean showStatus = false, reset = false;
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      if (k.equals("--status")) showStatus = true;
      else if (k.equals("--reset")) reset = true;
      else if (k.startsWith("--") && i + 1 < a.length && !a[i + 1].startsWith("--")) opt.put(k.substring(2), a[++i]);
      else System.out.println("[admin] option ignorée : " + k);
    }
    boolean setDate = opt.containsKey("set-date"), setOff = opt.containsKey("offset-hours");
    if (!showStatus && !reset && !setDate && !setOff) {
      System.out.println("Usage : AdminClock [--db <chemin>] [--shard <n>] (--status | --set-date <yyyy-MM-dd> | --offset-hours <h> | --reset)");
      return;
    }

    ServerContext.init();
    String db = opt.getOrDefault("db", System.getProperty("dh.db", "server/data/dh-server.db"));
    int shard = Integer.parseInt(opt.getOrDefault("shard", "1"));

    try (UserStore store = new UserStore(db)) {
      // Applique d'abord l'ancre persistée (pour un --status fidèle) sauf si on la change dans cet appel.
      Long persisted = store.getMetaLong("clock_offset_ms");
      if (persisted != null && !setDate && !setOff && !reset) ServerContext.setClockOffsetMillis(persisted);

      if (reset) {
        store.setMetaLong("clock_offset_ms", 0L);
        ServerContext.setClockOffsetMillis(0L);
        System.out.println("[clock] RÉINITIALISÉ à l'heure réelle (offset 0, persisté). Redémarrer le serveur.");
      }
      if (setOff) {
        long h = Long.parseLong(opt.get("offset-hours"));
        long off = -h * 3600_000L;                 // OFFSET négatif = avance
        store.setMetaLong("clock_offset_ms", off);
        ServerContext.setClockOffsetMillis(off);
        System.out.println("[clock] offset = " + h + " h (persisté). Redémarrer le serveur.");
      }
      if (setDate) {
        String[] p = opt.get("set-date").split("-");
        long target = new org.joda.time.LocalDate(
                Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]))
            .toDateTimeAtStartOfDay(com.perblue.heroes.util.TimeUtil.getServerDateTimeZone()).getMillis();
        long off = System.currentTimeMillis() - target;   // serverTimeNow = now − OFFSET = target
        store.setMetaLong("clock_offset_ms", off);
        ServerContext.setClockOffsetMillis(off);
        System.out.println("[clock] heure de jeu réglée sur " + opt.get("set-date")
            + " (offset " + off + " ms, persisté). Redémarrer le serveur.");
      }
      status(store, shard);
    }
  }
}
