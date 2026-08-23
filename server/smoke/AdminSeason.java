import dhserver.ServerContext;
import dhserver.ServerEvents;
import dhserver.UserStore;

/**
 * PANNEAU ADMIN SAISON (opérateur live-ops) — pendant d'{@code AdminClock}/{@code AdminEvents}.
 *
 * <p>Les FRANCHISE_TRIALS de saison tournent par DATE (calendrier figé dans l'APK,
 * {@code patched_heroes_franchise_season_mapping.tab}). La saison « courante » est normalement choisie par l'horloge
 * serveur. Cet outil règle une ANCRE DE SAISON <b>découplée de l'horloge</b> : l'opérateur choisit quelle saison est
 * active SANS toucher aux timers joueur (resets quotidiens, cooldowns, régén). C'est un OFFSET additionnel appliqué
 * UNIQUEMENT à {@code ServerEvents.seasonTrialConfigs()} (via {@code ServerContext.seasonTimeNow()}).
 *
 * <p>Différence avec {@code AdminClock} : {@code AdminClock} bouge l'HORLOGE (mur + ère de contenu + saison + tous les
 * timers, monde cohérent — utile pour la vérif §8) ; {@code AdminSeason} bouge SEULEMENT la saison (les timers joueur
 * restent à l'heure réelle). Deux préoccupations, deux outils. Défaut (aucune ancre) → la saison suit la date réelle.
 *
 * <pre>
 * Usage :
 *   AdminSeason [--db …] [--shard 1] --status
 *   AdminSeason [--db …] --set-date &lt;yyyy-MM-dd&gt;   (saison active = celle en vigueur à cette date)
 *   AdminSeason [--db …] --offset-hours &lt;h&gt;        (décale la saison ; négatif = saison plus ancienne)
 *   AdminSeason [--db …] --reset                   (la saison suit à nouveau la date réelle)
 * </pre>
 * NB : le serveur applique l'ancre AU BOOT → redémarrer le serveur après un changement.
 */
public final class AdminSeason {

  static void status(UserStore store) throws Exception {
    long seasonNow = ServerContext.seasonTimeNow();
    String date = new org.joda.time.DateTime(seasonNow, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone())
        .toString("yyyy-MM-dd");
    Long persisted = store.getMetaLong("season_anchor_offset_ms");
    System.out.println("[season] ancre courante " + ServerContext.seasonAnchorOffsetMillis() + " ms"
        + " (persistée en DB : " + (persisted == null ? "aucune" : persisted + " ms") + ")");
    System.out.println("[season] date de RÉFÉRENCE saison = " + date
        + " (horloge serveur " + new org.joda.time.DateTime(com.perblue.heroes.util.TimeUtil.serverTimeNow(),
              com.perblue.heroes.util.TimeUtil.getServerDateTimeZone()).toString("yyyy-MM-dd")
        + " + ancre) — timers joueur INCHANGÉS.");
    int n = ServerEvents.seasonTrialCount();
    System.out.println("[season] TRIALS de la saison sélectionnée : " + n);
    for (int i = 0; i < n; i++) {
      System.out.println("   trial " + i + " : " + ServerEvents.seasonTrialFranchises(i)
          + " questType=" + ServerEvents.seasonTrialQuestType(i));
    }
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
      System.out.println("Usage : AdminSeason [--db <chemin>] [--shard <n>] (--status | --set-date <yyyy-MM-dd> | --offset-hours <h> | --reset)");
      return;
    }

    ServerContext.init();
    String db = opt.getOrDefault("db", System.getProperty("dh.db", "server/data/dh-server.db"));

    try (UserStore store = new UserStore(db)) {
      // Applique d'abord l'ancre persistée (pour un --status fidèle) sauf si on la change dans cet appel.
      Long persisted = store.getMetaLong("season_anchor_offset_ms");
      if (persisted != null && !setDate && !setOff && !reset) ServerContext.setSeasonAnchorOffsetMillis(persisted);

      if (reset) {
        store.setMetaLong("season_anchor_offset_ms", 0L);
        ServerContext.setSeasonAnchorOffsetMillis(0L);
        System.out.println("[season] RÉINITIALISÉE : la saison suit la date réelle (ancre 0, persistée). Redémarrer le serveur.");
      }
      if (setOff) {
        long h = Long.parseLong(opt.get("offset-hours"));
        long off = h * 3600_000L;                    // positif = saison plus récente ; négatif = plus ancienne
        store.setMetaLong("season_anchor_offset_ms", off);
        ServerContext.setSeasonAnchorOffsetMillis(off);
        System.out.println("[season] ancre = " + h + " h (persistée). Redémarrer le serveur.");
      }
      if (setDate) {
        String[] p = opt.get("set-date").split("-");
        long target = new org.joda.time.LocalDate(
                Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]))
            .toDateTimeAtStartOfDay(com.perblue.heroes.util.TimeUtil.getServerDateTimeZone()).getMillis();
        // seasonTimeNow() = serverTimeNow() + ancre = target  →  ancre = target − serverTimeNow()
        long off = target - com.perblue.heroes.util.TimeUtil.serverTimeNow();
        store.setMetaLong("season_anchor_offset_ms", off);
        ServerContext.setSeasonAnchorOffsetMillis(off);
        System.out.println("[season] saison réglée sur la date " + opt.get("set-date")
            + " (ancre " + off + " ms, persistée) — timers joueur INCHANGÉS. Redémarrer le serveur.");
      }
      status(store);
    }
  }
}
