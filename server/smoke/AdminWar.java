import com.perblue.heroes.network.messages.WarQueueState;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerWar;
import dhserver.ServerWarBoxes;
import dhserver.ServerWarScheduler;
import dhserver.ServerWarState;
import dhserver.UserStore;

import java.util.HashMap;
import java.util.Map;

/**
 * PANNEAU ADMIN GUERRE DE GUILDE (opérateur) — pendant de {@code AdminGuild}/{@code AdminMail} pour le mode
 * GUILD WAR (#68). Il ne crée aucune règle : il ne fait qu'APPELER l'ordonnanceur et LIRE l'état persisté.
 *
 * <p>Raison d'être : l'appariement suit un CALENDRIER (une occurrence de {@code RESET_HOUR} par jour). Un
 * shard neuf attend donc la prochaine occurrence — ce qui est le comportement correct mais impraticable pour
 * tester ou pour ouvrir une saison à la demande. {@code --tick --force} déclenche un appariement immédiat ;
 * c'est le SEUL levier forcé, les autres étapes (clôture, bascule de saison) dépendent d'échéances réelles
 * qu'on ne bouscule pas.
 *
 * <pre>
 * Usage :
 *   AdminWar [--db server/data/dh-server.db] [--shard 1] --status
 *   AdminWar [--db …] [--shard 1] --tick [--force]
 * </pre>
 */
public final class AdminWar {

  static String hhmm(long t) {
    if (t <= 0) return "—";
    return new org.joda.time.DateTime(t, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone())
        .toString("yyyy-MM-dd HH:mm");
  }

  static void status(UserStore store, int shardID) throws Exception {
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    System.out.println("[war] shard " + shardID + " — guerre "
        + (ServerWar.enabledForShard(shardID) ? "ACTIVÉE" : "DÉSACTIVÉE")
        + " · saison " + ServerWar.seasonIDAt(now)
        + " · prochain appariement " + hhmm(ServerWarScheduler.nextMatchmakingTime(now)));

    java.util.List<ServerGuild> guilds = store.listGuilds(shardID, null, 10_000);
    if (guilds.isEmpty()) { System.out.println("[war] aucune guilde sur ce shard."); return; }

    System.out.println(String.format("%-24s %6s %6s %-10s %-18s %s",
        "GUILDE", "ID", "MMR", "LIGUE", "FILE", "GUERRE EN COURS"));
    for (ServerGuild g : guilds) {
      int mmr = ServerWar.currentMMR(g);
      String war = "—";
      if (g.currentWarID > 0) {
        ServerWarState w = store.loadWar(shardID, g.currentWarID);
        war = w == null ? ("#" + g.currentWarID + " (introuvable)")
            : ("#" + w.warID + " " + w.state + (w.isBye() ? "" : " vs " + w.opponentOf(g.guildID))
               + " → " + hhmm(w.endTime));
      }
      String name = g.info != null && g.info.basicInfo != null ? g.info.basicInfo.name : "?";
      System.out.println(String.format("%-24s %6d %6d %-10s %-18s %s",
          name, g.guildID, mmr, ServerWar.effectiveLeague(mmr, g.warPromotionMask),
          g.warQueueState(), war));
    }

    int pending = 0;
    for (ServerGuild g : guilds) {
      for (Long memberID : g.memberIDs) {
        ServerWarBoxes b = store.loadWarBoxes(shardID, memberID);
        pending += b.size();
      }
    }
    System.out.println("[war] boîtes en attente de réclamation (tous membres) : " + pending);
  }

  public static void main(String[] a) throws Exception {
    Map<String, String> opt = new HashMap<>();
    boolean tick = false, force = false, showStatus = false;
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      switch (k) {
        case "--tick":   tick = true; break;
        case "--force":  force = true; break;
        case "--status": showStatus = true; break;
        default:
          if (k.startsWith("--") && i + 1 < a.length && !a[i + 1].startsWith("--")) opt.put(k.substring(2), a[++i]);
          else System.out.println("[admin] option ignorée : " + k);
      }
    }
    if (!tick && !showStatus) {
      System.out.println("Usage : AdminWar [--db <chemin>] [--shard <n>] (--status | --tick [--force])");
      return;
    }

    ServerContext.init();
    String db = opt.getOrDefault("db", System.getProperty("dh.db", "server/data/dh-server.db"));
    int shardID = Integer.parseInt(opt.getOrDefault("shard", "1"));

    try (UserStore store = new UserStore(db)) {
      if (tick) {
        long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
        ServerWarScheduler.Tick t = ServerWarScheduler.tick(store, shardID, now, force);
        System.out.println("[war] tour" + (force ? " FORCÉ" : "") + " : " + t);
      }
      if (showStatus) status(store, shardID);
    }
  }
}
