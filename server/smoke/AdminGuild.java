import com.perblue.heroes.network.messages.ItemType;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.RewardDrop;
import com.perblue.heroes.game.logic.RewardHelper;
import dhserver.ServerContest;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

import java.util.*;

/**
 * PANNEAU ADMIN GUILDE (opérateur) — les leviers de guilde que le vrai backend PerBlue déclenche et que le client
 * n'expose pas : (1) GÉNÉRER un CADEAU de guilde (GUILD CRATE, #66) — dans le jeu d'origine déclenché par un achat
 * de membre ; (2) attribuer des POINTS DE CONTEST à une guilde (#67) — la « planification » d'un contest est par
 * nature live-ops opérateur. Tout passe par la base (multi-serveur, PRINCIPLES §5/§6) et est relu par les handlers
 * {@code GetGuildGiftRewards} / {@code CLAIM_GUILD_GIFT_REWARDS} / {@code GET_GUILD_CONTEST_RANKINGS}.
 *
 * Usage :
 *   AdminGuild --db server/data/dh-server.db --shard 1 --guild &lt;guildID&gt; \
 *              [--gift --from &lt;userID&gt; --reward DIAMONDS:500 --reward STAMINA_CONSUMABLE:3 ...] \
 *              [--contest-points &lt;n&gt;]
 *
 * Exemples :
 *   AdminGuild --shard 1 --guild 1 --gift --from 1 --reward GOLD:50000 --reward DIAMONDS:100
 *   AdminGuild --shard 1 --guild 1 --contest-points 250
 */
public final class AdminGuild {

  /** Une récompense « TYPE:quantité » — essaie ResourceType puis ItemType (logique/format du jeu). */
  static RewardDrop parseDrop(String spec) {
    String[] kv = spec.split(":");
    if (kv.length != 2) { System.out.println("[admin] récompense ignorée (format TYPE:qté) : " + spec); return null; }
    String name = kv[0].trim();
    long qty;
    try { qty = Long.parseLong(kv[1].trim()); }
    catch (Throwable t) { System.out.println("[admin] quantité invalide : " + spec); return null; }
    try { return RewardHelper.createDrop(ResourceType.valueOf(name), qty); } catch (Throwable ignore) {}
    try { return RewardHelper.createDrop(ItemType.valueOf(name), qty); } catch (Throwable ignore) {}
    System.out.println("[admin] type inconnu (ni ResourceType ni ItemType) : " + name);
    return null;
  }

  /** Durée « 7d » / « 12h » / « 30m » → millisecondes. */
  static long parseDuration(String s) {
    s = s.trim().toLowerCase();
    long mult = 1000L;
    if (s.endsWith("d")) { mult = 86400_000L; s = s.substring(0, s.length() - 1); }
    else if (s.endsWith("h")) { mult = 3600_000L; s = s.substring(0, s.length() - 1); }
    else if (s.endsWith("m")) { mult = 60_000L; s = s.substring(0, s.length() - 1); }
    return (long) (Double.parseDouble(s) * mult);
  }

  public static void main(String[] a) throws Exception {
    Map<String, String> opt = new HashMap<>();
    List<String> rewards = new ArrayList<>();
    List<String> rankRewards = new ArrayList<>();
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      if ("--gift".equals(k)) { opt.put("gift", "1"); continue; }
      if ("--all-guilds".equals(k)) { opt.put("all-guilds", "1"); continue; }
      if ("--contest-status".equals(k)) { opt.put("contest-status", "1"); continue; }
      if ("--contest-end".equals(k)) { opt.put("contest-end", "1"); continue; }
      if ("--reward".equals(k) && i + 1 < a.length) { rewards.add(a[++i]); continue; }
      if ("--rank-reward".equals(k) && i + 1 < a.length) { rankRewards.add(a[++i]); continue; }
      if (k.startsWith("--") && i + 1 < a.length) opt.put(k.substring(2), a[++i]);
    }
    String db = opt.getOrDefault("db", "server/data/dh-server.db");
    int shard = Integer.parseInt(opt.getOrDefault("shard", "1"));
    boolean gift = opt.containsKey("gift");
    boolean contest = opt.containsKey("contest-points");
    boolean allGuilds = opt.containsKey("all-guilds");
    boolean season = opt.containsKey("contest-start") || opt.containsKey("contest-status")
        || opt.containsKey("contest-end");

    if (season) { runSeason(db, shard, opt, rankRewards); return; }
    if (gift && allGuilds) { runGlobalGift(db, shard, opt, rewards); return; }

    if (!opt.containsKey("guild") || (!gift && !contest)) {
      System.out.println("Usage:\n"
          + "  Cadeau  : AdminGuild --shard <s> (--guild <id> | --all-guilds) --gift [--from <userID>] --reward TYPE:qté ...\n"
          + "  Contest : AdminGuild --shard <s> --guild <id> --contest-points <n> [--member <userID>]\n"
          + "  Saison  : AdminGuild --shard <s> --contest-start \"Nom\" --duration 7d [--rank-reward 1-1:GOLD:100000 ...]\n"
          + "            AdminGuild --shard <s> --contest-status\n"
          + "            AdminGuild --shard <s> --contest-end        (clôture + paie les récompenses)");
      return;
    }
    long guildID = Long.parseLong(opt.get("guild"));

    ServerContext.init();
    try (UserStore store = new UserStore(db)) {
      ServerGuild g = store.loadGuild(shard, guildID);
      if (g == null) { System.out.println("[admin] guilde " + guildID + " introuvable (shard " + shard + ")"); return; }
      String gname = g.info == null || g.info.basicInfo == null ? "?" : g.info.basicInfo.name;
      System.out.println("[admin] guilde #" + guildID + " « " + gname + " » (" + g.memberIDs.size() + " membre(s))");

      if (gift) {
        List<RewardDrop> drops = new ArrayList<>();
        for (String r : rewards) { RewardDrop d = parseDrop(r); if (d != null) drops.add(d); }
        if (drops.isEmpty()) { System.out.println("[admin] aucun --reward valide : cadeau annulé"); return; }
        long fromID = Long.parseLong(opt.getOrDefault("from", String.valueOf(
            g.memberIDs.isEmpty() ? 0L : g.memberIDs.iterator().next())));
        ServerUser gifter = store.loadIfExists(fromID, shard);
        if (gifter == null) { System.out.println("[admin] offreur " + fromID + " introuvable"); return; }
        gifter.grantGuildGift(g, drops, com.perblue.heroes.util.TimeUtil.serverTimeNow());
        store.saveGuild(g);
        System.out.println("[admin] CADEAU généré (offreur " + fromID + ") : " + drops.size()
            + " récompense(s) — réclamable par les " + g.memberIDs.size()
            + " membre(s) via CLAIM_GUILD_GIFT_REWARDS [persisté]");
      }

      if (contest) {
        int pts = Integer.parseInt(opt.get("contest-points"));
        long before = g.info.contestPoints;
        // --member : à QUI imputer les points (ventilation du classement joueurs). Défaut = 1ᵉ membre.
        long memberID = Long.parseLong(opt.getOrDefault("member", String.valueOf(
            g.memberIDs.isEmpty() ? 0L : g.memberIDs.iterator().next())));
        ServerUser who = store.loadIfExists(memberID, shard);
        if (who == null) { System.out.println("[admin] membre " + memberID + " introuvable"); return; }
        who.awardGuildContestPoints(g, pts);
        store.saveGuild(g);
        System.out.println("[admin] CONTEST : guilde " + before + " → " + g.info.contestPoints + " (+" + pts
            + ") ; membre " + memberID + " → " + who.contestPointsIn(g)
            + " [persisté] — GET_GUILD_CONTEST_RANKINGS + GET_CONTEST_RANKINGS");
      }
      System.out.println("[admin] TERMINÉ");
    }
  }

  /** CADEAU GLOBAL — offre le même cadeau à TOUTES les guildes du shard (serveur communautaire : l'admin
   *  remplace le déclencheur « achat réel » du jeu d'origine). */
  static void runGlobalGift(String db, int shard, Map<String, String> opt, List<String> rewards) throws Exception {
    ServerContext.init();
    try (UserStore store = new UserStore(db)) {
      List<RewardDrop> drops = new ArrayList<>();
      for (String r : rewards) { RewardDrop d = parseDrop(r); if (d != null) drops.add(d); }
      if (drops.isEmpty()) { System.out.println("[admin] aucun --reward valide : annulé"); return; }
      List<ServerGuild> all = store.listGuilds(shard, null, 10000);
      int n = 0;
      for (ServerGuild g : all) {
        if (g.memberIDs.isEmpty()) continue;
        long fromID = g.memberIDs.iterator().next();          // offreur = un membre (l'écran affiche un offreur)
        ServerUser gifter = store.loadIfExists(fromID, shard);
        if (gifter == null) continue;
        gifter.grantGuildGift(g, drops, com.perblue.heroes.util.TimeUtil.serverTimeNow());
        store.saveGuild(g);
        n++;
      }
      System.out.println("[admin] CADEAU GLOBAL : " + drops.size() + " récompense(s) offerte(s) à "
          + n + " guilde(s) du shard " + shard + " [persisté]");
    }
  }

  /** SAISON DE CONTEST — programmation (start/durée/paliers), statut, clôture + paiement. */
  static void runSeason(String db, int shard, Map<String, String> opt, List<String> rankRewards) throws Exception {
    ServerContext.init();
    try (UserStore store = new UserStore(db)) {
      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      ServerContest c = null;
      byte[] raw = store.loadShardState(shard, "contest");
      if (raw != null) c = ServerContest.fromBytes(raw);

      if (opt.containsKey("contest-start")) {
        c = new ServerContest();
        c.name = opt.get("contest-start");
        c.startTime = now;
        c.endTime = now + parseDuration(opt.getOrDefault("duration", "7d"));
        for (String spec : rankRewards) {
          // format : "min-max:TYPE:qté" (ex. 1-1:GOLD:100000, 2-3:DIAMONDS:500)
          int c1 = spec.indexOf(':');
          if (c1 < 0) { System.out.println("[admin] palier ignoré : " + spec); continue; }
          String range = spec.substring(0, c1), rew = spec.substring(c1 + 1);
          int dash = range.indexOf('-');
          int lo, hi;
          try {
            lo = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
            hi = dash < 0 ? lo : Integer.parseInt(range.substring(dash + 1));
          } catch (Throwable t) { System.out.println("[admin] rangs invalides : " + spec); continue; }
          RewardDrop d = parseDrop(rew);
          if (d == null) continue;
          ServerContest.Tier tier = null;
          for (ServerContest.Tier t : c.tiers) if (t.minRank == lo && t.maxRank == hi) tier = t;
          if (tier == null) { tier = new ServerContest.Tier(lo, hi); c.tiers.add(tier); }
          tier.rewards.add(d);
        }
        store.saveShardState(shard, "contest", c.toBytes());
        System.out.println("[admin] SAISON programmée : " + c.describe(now) + " [persisté]");
        return;
      }

      if (c == null) { System.out.println("[admin] aucune saison programmée sur le shard " + shard); return; }

      if (opt.containsKey("contest-status")) {
        System.out.println("[admin] SAISON : " + c.describe(now));
        List<ServerGuild> all = store.listGuilds(shard, null, 10000);
        all.sort((x, y) -> Long.compare(y.info.contestPoints, x.info.contestPoints));
        int r = 0;
        for (ServerGuild g : all) {
          r++;
          if (r > 10) break;
          ServerContest.Tier t = c.tierFor(r);
          System.out.println("   #" + r + "  " + g.info.basicInfo.name + "  " + g.info.contestPoints
              + " pts" + (t == null ? "" : "  → " + t.rewards.size() + " récompense(s)"));
        }
        return;
      }

      // --contest-end : clôture + PAIEMENT (idempotent).
      if (c.paidOut) { System.out.println("[admin] saison déjà payée — rien à faire (idempotent)"); return; }
      List<ServerGuild> all = store.listGuilds(shard, null, 10000);
      all.sort((x, y) -> Long.compare(y.info.contestPoints, x.info.contestPoints));
      int rank = 0, paidGuilds = 0, paidPlayers = 0;
      for (ServerGuild g : all) {
        rank++;
        ServerContest.Tier tier = c.tierFor(rank);
        if (tier == null || tier.rewards.isEmpty()) continue;
        boolean any = false;
        for (Long mid : g.memberIDs) {
          ServerUser mu = store.loadIfExists(mid, shard);
          if (mu == null) continue;
          if (mu.deliverContestSeasonReward(c.name, rank, tier) > 0) { store.save(mu); paidPlayers++; any = true; }
        }
        if (any) paidGuilds++;
        System.out.println("[admin]   #" + rank + " " + g.info.basicInfo.name + " (" + g.info.contestPoints
            + " pts) → " + tier.rewards.size() + " récompense(s) à " + g.memberIDs.size() + " membre(s)");
      }
      c.paidOut = true;
      if (c.endTime > now) c.endTime = now;                   // clôture anticipée demandée par l'admin
      store.saveShardState(shard, "contest", c.toBytes());
      System.out.println("[admin] SAISON CLÔTURÉE « " + c.name + " » : " + paidGuilds + " guilde(s), "
          + paidPlayers + " joueur(s) récompensé(s) par courrier [persisté]");
    }
  }
}
