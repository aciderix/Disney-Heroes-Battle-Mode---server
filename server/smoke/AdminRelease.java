import dhserver.ServerContext;
import dhserver.UserStore;

/**
 * PANNEAU ADMIN « RELEASE PICKER » (opérateur) — choisir l'ÈRE DE CONTENU par NOM DE RELEASE plutôt que par date brute.
 *
 * <p>C'est un WRAPPER MINCE d'{@link AdminClock} (cf. `docs/PHASE2_TRACKING.md` étape 3) : l'ère (chapitres, Max Team Level,
 * rosters, échelle de puissance) est choisie PAR DATE via {@code ContentStats.getServerColumn(serverTimeNow)}. Chaque RELEASE
 * (colonne de {@code content.<shard>.tab}) a une {@code startTime}. Régler « la release Rxx » = poser l'ancre d'horloge persistée
 * ({@code clock_offset_ms}, la MÊME qu'AdminClock) sur la date de début de cette release → la logique datée du jeu suit (§2, aucune
 * règle contournée). ⚠️ DÉPENDANCE CACHÉE DOCUMENTÉE (étape 3) : {@code BootData.serverTime} pilote À LA FOIS le contenu daté ET
 * l'affichage des timers côté client → choisir une release décale aussi l'horloge perçue (compromis assumé d'AdminClock ; un
 * découplage contenu↔timers exigerait de modifier le client, hors §1).
 *
 * <pre>
 * Usage :
 *   AdminRelease [--db …] [--shard 1] --list                 (liste les releases : nom, date, Max TL)
 *   AdminRelease [--db …] [--shard 1] --status               (release courante)
 *   AdminRelease [--db …] [--shard 1] --set-release <nom|#index>   (ancre l'horloge sur cette release)
 *   AdminRelease [--db …] --reset                            (heure réelle → release courante réelle)
 * </pre>
 * NB : le serveur applique l'ancre AU BOOT → redémarrer le serveur après un changement (comme AdminClock).
 */
public final class AdminRelease {

  @SuppressWarnings("unchecked")
  static java.util.List<com.perblue.heroes.game.data.content.ContentStats.ContentColumn> columns(int shard) {
    com.perblue.heroes.game.data.content.ContentHelper.get().setShardID(shard, new java.util.HashMap<>());
    return (java.util.List<com.perblue.heroes.game.data.content.ContentStats.ContentColumn>)
        (java.util.List<?>) com.perblue.heroes.game.data.content.ContentHelper.getRawStats().getColumns();
  }

  static String fmt(long ms) {
    return new org.joda.time.DateTime(ms, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone()).toString("yyyy-MM-dd");
  }

  static String name(com.perblue.heroes.game.data.content.ContentStats.ContentColumn c) {
    try { return String.valueOf(c.getContentUpdate()); } catch (Throwable t) { return "?"; }
  }

  static void list(int shard) {
    java.util.List<com.perblue.heroes.game.data.content.ContentStats.ContentColumn> cols = columns(shard);
    System.out.println("[release] " + cols.size() + " release(s) sur content." + shard + ".tab :");
    int i = 1;
    for (com.perblue.heroes.game.data.content.ContentStats.ContentColumn c : cols) {
      System.out.println(String.format("  #%-3d %-14s %s  MaxTL=%d", i++, name(c), fmt(c.startTime), c.getMaxTeamLevel()));
    }
  }

  static void status(UserStore store, int shard) throws Exception {
    com.perblue.heroes.game.data.content.ContentHelper.get().setShardID(shard, new java.util.HashMap<>());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    com.perblue.heroes.game.data.content.ContentStats.ContentColumn col =
        com.perblue.heroes.game.data.content.ContentHelper.getCurrent(now);
    Long persisted = store.getMetaLong("clock_offset_ms");
    System.out.println("[release] heure de JEU = " + fmt(now) + " | release courante = "
        + (col == null ? "?" : name(col) + " (Max TL " + col.getMaxTeamLevel() + ")")
        + " | offset persisté = " + (persisted == null ? "aucun" : persisted + " ms") + " (shard " + shard + ")");
  }

  public static void main(String[] a) throws Exception {
    java.util.Map<String, String> opt = new java.util.HashMap<>();
    boolean doList = false, doStatus = false, reset = false;
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      if (k.equals("--list")) doList = true;
      else if (k.equals("--status")) doStatus = true;
      else if (k.equals("--reset")) reset = true;
      else if (k.startsWith("--") && i + 1 < a.length && !a[i + 1].startsWith("--")) opt.put(k.substring(2), a[++i]);
      else System.out.println("[admin] option ignorée : " + k);
    }
    boolean setRel = opt.containsKey("set-release");
    if (!doList && !doStatus && !reset && !setRel) {
      System.out.println("Usage : AdminRelease [--db <chemin>] [--shard <n>] (--list | --status | --set-release <nom|#index> | --reset)");
      return;
    }

    ServerContext.init();
    String db = opt.getOrDefault("db", System.getProperty("dh.db", "server/data/dh-server.db"));
    int shard = Integer.parseInt(opt.getOrDefault("shard", "1"));

    try (UserStore store = new UserStore(db)) {
      Long persisted = store.getMetaLong("clock_offset_ms");
      if (persisted != null && !setRel && !reset) ServerContext.setClockOffsetMillis(persisted);

      if (doList) { list(shard); return; }

      if (reset) {
        store.setMetaLong("clock_offset_ms", 0L);
        ServerContext.setClockOffsetMillis(0L);
        System.out.println("[release] RÉINITIALISÉ à l'heure réelle (offset 0, persisté). Redémarrer le serveur.");
      }

      if (setRel) {
        java.util.List<com.perblue.heroes.game.data.content.ContentStats.ContentColumn> cols = columns(shard);
        String sel = opt.get("set-release");
        com.perblue.heroes.game.data.content.ContentStats.ContentColumn target = null;
        if (sel.startsWith("#")) {
          int idx = Integer.parseInt(sel.substring(1));
          if (idx >= 1 && idx <= cols.size()) target = cols.get(idx - 1);
        } else {
          for (com.perblue.heroes.game.data.content.ContentStats.ContentColumn c : cols)
            if (name(c).equalsIgnoreCase(sel)) { target = c; break; }
        }
        if (target == null) {
          System.out.println("[release] release introuvable : '" + sel + "' (utilise --list). Aucun changement.");
          return;
        }
        long off = System.currentTimeMillis() - target.startTime;   // serverTimeNow = now − offset = startTime de la release
        store.setMetaLong("clock_offset_ms", off);
        ServerContext.setClockOffsetMillis(off);
        System.out.println("[release] ère réglée sur " + name(target) + " (" + fmt(target.startTime)
            + ", Max TL " + target.getMaxTeamLevel() + ") — offset " + off + " ms persisté.");
        System.out.println("[release] ⚠ décale aussi l'horloge perçue du client (timers) — compromis AdminClock. Redémarrer le serveur.");
      }

      status(store, shard);
    }
  }
}
