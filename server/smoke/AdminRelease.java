import dhserver.ServerContext;
import dhserver.UserStore;

/**
 * PANNEAU ADMIN « RELEASE PICKER » (opérateur) — choisir l'ÈRE DE CONTENU par NOM DE RELEASE, **sans casser les sauvegardes ni
 * les timers des joueurs**.
 *
 * <p>Contrairement à {@link AdminClock} (qui décale l'HORLOGE ENTIÈRE → resets/cooldowns/horodatages compris), ce picker règle
 * l'**offset d'ÈRE DE CONTENU DÉCOUPLÉ** (`ServerContext.setContentOffsetMillis`, persisté en méta `content_offset_ms`, émis dans
 * `bootData().contentStatsTimeOffset`). Le jeu/le client le supportent nativement : `GameMain` (boot) lit
 * `contentStatsTimeOffset` et appelle `ContentStats.setUserOffset` + `PatchStats.debugSetUserOffset` → le client résout SON
 * contenu daté par `serverTimeNow() + offset` (ère : caps, rosters, disponibilités, valeurs indexées par ContentUpdate comme la
 * stamina), MAIS garde ses TIMERS (resets quotidiens, cooldowns, régén, horodatages de sauvegarde) sur `serverTimeNow()` BRUT.
 * ⇒ changer d'ère NE touche NI la sauvegarde NI les timers.
 *
 * <pre>
 * Usage :
 *   AdminRelease [--db …] [--shard 1] --list                    # liste les releases : nom, date, Max TL
 *   AdminRelease [--db …] [--shard 1] --status                  # ère de contenu courante (+ heure réelle des timers)
 *   AdminRelease [--db …] [--shard 1] --set-release <nom|#idx>  # règle l'ère (ex. R50, #12) SANS toucher les timers
 *   AdminRelease [--db …] --reset                               # ère = date réelle
 * </pre>
 * NB : offset ré-appliqué au boot par LoginServer → redémarrer le serveur après un changement.
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

  /** L'ère courante = colonne à (serverTimeNow + offset de contenu) — timers restent sur serverTimeNow. */
  static com.perblue.heroes.game.data.content.ContentStats.ContentColumn currentEra(int shard) {
    columns(shard);   // installe le shard
    long eraTime = com.perblue.heroes.util.TimeUtil.serverTimeNow() + ServerContext.contentOffsetMillis();
    return com.perblue.heroes.game.data.content.ContentHelper.getRawStats().getColumn(eraTime);
  }

  static void list(int shard) {
    java.util.List<com.perblue.heroes.game.data.content.ContentStats.ContentColumn> cols = columns(shard);
    System.out.println("[release] " + cols.size() + " release(s) sur content." + shard + ".tab :");
    int i = 1;
    for (com.perblue.heroes.game.data.content.ContentStats.ContentColumn c : cols)
      System.out.println(String.format("  #%-3d %-14s %s  MaxTL=%d", i++, name(c), fmt(c.startTime), c.getMaxTeamLevel()));
  }

  static void status(UserStore store, int shard) throws Exception {
    long real = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long off = ServerContext.contentOffsetMillis();
    com.perblue.heroes.game.data.content.ContentStats.ContentColumn col = currentEra(shard);
    Long persisted = store.getMetaLong("content_offset_ms");
    System.out.println("[release] timers (heure réelle) = " + fmt(real)
        + " | ÈRE de contenu = " + (col == null ? "?" : name(col) + " @ " + fmt(real + off) + " (Max TL " + col.getMaxTeamLevel() + ")")
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
      Long persisted = store.getMetaLong("content_offset_ms");
      if (persisted != null && !setRel && !reset) ServerContext.setContentOffsetMillis(persisted);

      if (doList) { list(shard); return; }

      if (reset) {
        store.setMetaLong("content_offset_ms", 0L);
        ServerContext.setContentOffsetMillis(0L);
        System.out.println("[release] ère RÉINITIALISÉE à la date réelle (offset 0, persisté). Redémarrer le serveur.");
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
        // offset de CONTENU tel que serverTimeNow() + offset = date de début de la release. serverTimeNow n'est PAS touché
        // → les timers/cooldowns/sauvegardes restent à l'heure réelle. C'est le découplage voulu.
        long off = target.startTime - com.perblue.heroes.util.TimeUtil.serverTimeNow();
        store.setMetaLong("content_offset_ms", off);
        ServerContext.setContentOffsetMillis(off);
        System.out.println("[release] ère réglée sur " + name(target) + " (" + fmt(target.startTime)
            + ", Max TL " + target.getMaxTeamLevel() + ") — offset de contenu " + off + " ms persisté.");
        System.out.println("[release] ✅ timers/cooldowns/sauvegardes INCHANGÉS (offset découplé de l'horloge). Redémarrer le serveur.");
      }

      status(store, shard);
    }
  }
}
