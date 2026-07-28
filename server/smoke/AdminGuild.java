import com.perblue.heroes.network.messages.ItemType;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.RewardDrop;
import com.perblue.heroes.game.logic.RewardHelper;
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

  public static void main(String[] a) throws Exception {
    Map<String, String> opt = new HashMap<>();
    List<String> rewards = new ArrayList<>();
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      if ("--gift".equals(k)) { opt.put("gift", "1"); continue; }
      if ("--reward".equals(k) && i + 1 < a.length) { rewards.add(a[++i]); continue; }
      if (k.startsWith("--") && i + 1 < a.length) opt.put(k.substring(2), a[++i]);
    }
    String db = opt.getOrDefault("db", "server/data/dh-server.db");
    int shard = Integer.parseInt(opt.getOrDefault("shard", "1"));
    boolean gift = opt.containsKey("gift");
    boolean contest = opt.containsKey("contest-points");
    if (!opt.containsKey("guild") || (!gift && !contest)) {
      System.out.println("Usage: AdminGuild --shard <s> --guild <guildID> "
          + "[--gift --from <userID> --reward TYPE:qté ...] [--contest-points <n>]");
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
}
