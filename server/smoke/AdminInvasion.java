import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerInvasion;
import dhserver.ServerUser;
import dhserver.UserStore;

import java.util.HashMap;
import java.util.Map;

/**
 * PANNEAU ADMIN INVASION (opérateur) — pendant de {@code AdminWar}/{@code AdminGuild}/{@code AdminMail} pour
 * le mode INVASION (#69), volet BOSS BATTLES.
 *
 * <p>Comme pour la guerre, le boss d'invasion est **serveur-autoritatif** : le jar CLIENT n'a aucune méthode
 * pour le créer (il ne fait que le LIRE et l'attaquer — {@code getBoss}, {@code recordBossFightOutcome}).
 * C'est donc le backend qui « fait apparaître » un boss pour la guilde (ici : décision opérateur, cohérent
 * avec « ADMIN = source » retenu pour un serveur communautaire). Cet outil ne crée aucune règle : il appelle
 * {@link ServerInvasion#spawnBoss} (niveau/échéance tirés des DONNÉES du jeu) et LIT l'état persisté.
 *
 * <pre>
 * Usage :
 *   AdminInvasion [--db server/data/dh-server.db] [--shard 1] --status
 *   AdminInvasion [--db …] [--shard 1] --spawn-boss [--guild &lt;id&gt;] [--finder &lt;userID&gt;] [--level &lt;n&gt;]
 * </pre>
 */
public final class AdminInvasion {

  static String hhmm(long t) {
    if (t <= 0) return "—";
    return new org.joda.time.DateTime(t, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone())
        .toString("yyyy-MM-dd HH:mm");
  }

  static void status(UserStore store, int shardID, long now) throws Exception {
    System.out.println("[invasion] shard " + shardID + " — invasion "
        + (ServerInvasion.isActive(now) ? "EN COURS" : "hors fenêtre")
        + " · rotation " + ServerInvasion.rotation(ServerInvasion.invasionStart(now)));
    java.util.List<ServerGuild> guilds = store.listGuilds(shardID, null, 10_000);
    if (guilds.isEmpty()) { System.out.println("[invasion] aucune guilde sur ce shard."); return; }
    for (ServerGuild g : guilds) {
      String name = g.info != null && g.info.basicInfo != null ? g.info.basicInfo.name : "?";
      java.util.List<com.perblue.heroes.network.messages.InvasionBossInfo> bosses =
          ServerInvasion.activeBosses(g, now);
      System.out.println("  guilde " + g.guildID + " « " + name + " » : " + bosses.size() + " boss actif(s)");
      for (com.perblue.heroes.network.messages.InvasionBossInfo b : bosses)
        System.out.println("     boss #" + b.bossID + " niv " + b.bossLevel + " échéance " + hhmm(b.endTime)
            + " attaquants=" + (b.damageDone == null ? 0 : b.damageDone.size()));
    }
  }

  public static void main(String[] a) throws Exception {
    Map<String, String> opt = new HashMap<>();
    boolean showStatus = false, spawn = false, clear = false;
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      switch (k) {
        case "--status": showStatus = true; break;
        case "--spawn-boss": spawn = true; break;
        case "--clear-bosses": clear = true; break;
        default:
          if (k.startsWith("--") && i + 1 < a.length && !a[i + 1].startsWith("--")) opt.put(k.substring(2), a[++i]);
          else System.out.println("[admin] option ignorée : " + k);
      }
    }
    boolean giveBreaker = opt.containsKey("give-breaker");
    if (!showStatus && !spawn && !clear && !giveBreaker) {
      System.out.println("Usage : AdminInvasion [--db <chemin>] [--shard <n>] (--status | --clear-bosses [--guild <id>] | --spawn-boss [--guild <id>] [--finder <userID>] [--level <n>] | --give-breaker <n> --user <id>)");
      return;
    }

    ServerContext.init();
    String db = opt.getOrDefault("db", System.getProperty("dh.db", "server/data/dh-server.db"));
    int shardID = Integer.parseInt(opt.getOrDefault("shard", "1"));
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    try (UserStore store = new UserStore(db)) {
      if (giveBreaker) {
        long uid = Long.parseLong(opt.getOrDefault("user", "1"));
        int n = Integer.parseInt(opt.get("give-breaker"));
        ServerUser u = store.loadOrCreate(uid, shardID);
        u.giveResource(com.perblue.heroes.network.messages.ResourceType.BREAKER, n);
        store.save(u);
        System.out.println("[invasion] +" + n + " BREAKER au joueur " + uid + " (total "
            + u.resourceAmount(com.perblue.heroes.network.messages.ResourceType.BREAKER) + ") — TEST opérateur.");
      }
      if (clear) {
        java.util.List<ServerGuild> gs = opt.containsKey("guild")
            ? java.util.Collections.singletonList(store.loadGuild(shardID, Long.parseLong(opt.get("guild"))))
            : store.listGuilds(shardID, null, 10_000);
        for (ServerGuild g : gs) {
          if (g == null) continue;
          int n = 0;
          for (com.perblue.heroes.network.messages.InvasionBossInfo b :
               new java.util.ArrayList<>(ServerInvasion.activeBosses(g, now))) { g.replaceInvasionBoss(b.bossID, null); n++; }
          store.saveGuild(g);
          System.out.println("[invasion] guilde " + g.guildID + " : " + n + " boss retiré(s).");
        }
      }
      if (spawn) {
        ServerGuild g;
        if (opt.containsKey("guild")) {
          g = store.loadGuild(shardID, Long.parseLong(opt.get("guild")));
        } else {
          java.util.List<ServerGuild> guilds = store.listGuilds(shardID, null, 10_000);
          g = guilds.isEmpty() ? null : guilds.get(0);
        }
        if (g == null) { System.out.println("[invasion] aucune guilde cible."); return; }
        long finderID = opt.containsKey("finder") ? Long.parseLong(opt.get("finder"))
            : (g.memberIDs.isEmpty() ? 0L : g.memberIDs.iterator().next());
        ServerUser finder = finderID > 0 ? store.loadOrCreate(finderID, shardID) : null;
        int level = Integer.parseInt(opt.getOrDefault("level", "0"));   // 0 → BOSS_FIGHT_INITAL_LEVEL
        com.perblue.heroes.network.messages.InvasionBossInfo b =
            ServerInvasion.spawnBoss(g, finder, level, now);
        store.saveGuild(g);
        System.out.println("[invasion] boss #" + b.bossID + " niv " + b.bossLevel + " apparu pour la guilde "
            + g.guildID + " (trouvé par " + finderID + "), échéance " + hhmm(b.endTime) + " — persisté.");
      }
      if (showStatus) status(store, shardID, now);
    }
  }
}
