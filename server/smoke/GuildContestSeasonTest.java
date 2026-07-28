import com.perblue.heroes.network.messages.*;
import dhserver.ServerContest;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #67 — SAISON DE CONTEST programmée par l'ADMIN (serveur communautaire : pas d'argent réel ni de
 * back-office live-ops PerBlue → c'est l'admin qui fixe fenêtre, paliers et récompenses).
 * Prouve : persistance de la saison (état SHARD, table {@code shard_state}), classement final par
 * {@code GuildInfo.contestPoints}, distribution des récompenses par PALIER DE RANG via COURRIER, et
 * IDEMPOTENCE du paiement (jamais deux fois).
 */
public final class GuildContestSeasonTest {
  static ServerGuild mkGuild(UserStore store, long uid, String name, int pts) throws Exception {
    ServerUser f = ServerUser.newPlayer(uid, 1);
    f.grantHero(UnitType.RALPH); f.giveResource(ResourceType.GOLD, 5000);
    CreateGuild c = new CreateGuild();
    c.name = name; c.motto = ""; c.minLevel = 1;
    c.newMemberPolicy = GuildNewMemberPolicy.OPEN; c.country = "US"; c.timeZone = "UTC";
    ServerGuild g = f.createGuild(c, store.nextGuildID(1));
    f.awardGuildContestPoints(g, pts);
    store.saveGuild(g); store.save(f);
    return g;
  }

  /** Reproduit la clôture de l'admin (classement + paiement par palier, idempotent). */
  static int[] closeSeason(UserStore store, int shard, ServerContest c) throws Exception {
    if (c.paidOut) return new int[]{0, 0};
    java.util.List<ServerGuild> all = store.listGuilds(shard, null, 10000);
    all.sort((x, y) -> Long.compare(y.info.contestPoints, x.info.contestPoints));
    int rank = 0, guilds = 0, players = 0;
    for (ServerGuild g : all) {
      rank++;
      ServerContest.Tier t = c.tierFor(rank);
      if (t == null || t.rewards.isEmpty()) continue;
      boolean any = false;
      for (Long mid : g.memberIDs) {
        ServerUser mu = store.loadIfExists(mid, shard);
        if (mu == null) continue;
        if (mu.deliverContestSeasonReward(c.name, rank, t) > 0) { store.save(mu); players++; any = true; }
      }
      if (any) guilds++;
    }
    c.paidOut = true;
    store.saveShardState(shard, "contest", c.toBytes());
    return new int[]{guilds, players};
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-contest-season", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      mkGuild(store, 10L, "Alpha", 100);
      mkGuild(store, 11L, "Beta", 300);
      mkGuild(store, 12L, "Gamma", 200);

      // L'ADMIN programme la saison : rang 1 → 1000 DIAMONDS ; rangs 2-3 → 50000 GOLD.
      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      ServerContest c = new ServerContest();
      c.name = "Test Season";
      c.startTime = now;
      c.endTime = now + 7L * 86400_000L;
      ServerContest.Tier t1 = new ServerContest.Tier(1, 1);
      t1.rewards.add(com.perblue.heroes.game.logic.RewardHelper.createDrop(ResourceType.DIAMONDS, 1000L));
      ServerContest.Tier t2 = new ServerContest.Tier(2, 3);
      t2.rewards.add(com.perblue.heroes.game.logic.RewardHelper.createDrop(ResourceType.GOLD, 50000L));
      c.tiers.add(t1); c.tiers.add(t2);
      store.saveShardState(1, "contest", c.toBytes());
      if (!c.isActive(now)) throw new AssertionError("la saison devrait être ACTIVE");
      System.out.println("[guild] saison programmée : " + c.describe(now));

      // Round-trip de l'état SHARD (persistance des paliers + récompenses au format du jeu).
      ServerContest rc = ServerContest.fromBytes(store.loadShardState(1, "contest"));
      if (rc == null || rc.tiers.size() != 2) throw new AssertionError("saison non persistée");
      if (rc.tierFor(1) == null || rc.tierFor(1).rewards.get(0).resourceType != ResourceType.DIAMONDS)
        throw new AssertionError("palier rang 1 incorrect après relecture");
      if (rc.tierFor(3) == null || rc.tierFor(3).rewards.get(0).quantity != 50000L)
        throw new AssertionError("palier rangs 2-3 incorrect après relecture");
      if (rc.tierFor(99) != null) throw new AssertionError("un rang hors palier ne doit rien recevoir");
      System.out.println("[guild] round-trip état SHARD OK (2 paliers, récompenses du jeu)");

      // CLÔTURE : Beta(300)=#1 → DIAMONDS ; Gamma(200)=#2 et Alpha(100)=#3 → GOLD.
      int[] res = closeSeason(store, 1, rc);
      if (res[0] != 3 || res[1] != 3)
        throw new AssertionError("clôture : " + res[0] + " guilde(s), " + res[1] + " joueur(s) (attendu 3/3)");
      System.out.println("[guild] clôture : " + res[0] + " guilde(s), " + res[1] + " joueur(s) payé(s) par courrier");

      // Le #1 (Beta, fondateur 11) reçoit bien les DIAMONDS ; le #3 (Alpha, 10) le GOLD.
      ServerUser beta = store.loadIfExists(11L, 1), alpha = store.loadIfExists(10L, 1);
      RewardDrop bd = null, ad = null;
      for (MailMessage mm : beta.mailPersisted())
        if (mm.type == MailType.SYSTEM_MESSAGE && mm.extra != null && mm.extra.attachments != null
            && !mm.extra.attachments.isEmpty()) bd = (RewardDrop) mm.extra.attachments.get(0);
      for (MailMessage mm : alpha.mailPersisted())
        if (mm.type == MailType.SYSTEM_MESSAGE && mm.extra != null && mm.extra.attachments != null
            && !mm.extra.attachments.isEmpty()) ad = (RewardDrop) mm.extra.attachments.get(0);
      if (bd == null || bd.resourceType != ResourceType.DIAMONDS || bd.quantity != 1000L)
        throw new AssertionError("le rang #1 devrait recevoir 1000 DIAMONDS, obtenu " + bd);
      if (ad == null || ad.resourceType != ResourceType.GOLD || ad.quantity != 50000L)
        throw new AssertionError("le rang #3 devrait recevoir 50000 GOLD, obtenu " + ad);
      System.out.println("[guild] récompenses par rang correctes (#1 → 1000 DIAMONDS, #3 → 50000 GOLD)");

      // IDEMPOTENCE : une 2ᵉ clôture ne paie rien.
      int[] again = closeSeason(store, 1, ServerContest.fromBytes(store.loadShardState(1, "contest")));
      if (again[0] != 0 || again[1] != 0) throw new AssertionError("2ᵉ clôture NE DOIT RIEN payer");
      System.out.println("[guild] idempotence OK : 2ᵉ clôture ne paie rien");

      System.out.println("GUILD CONTEST SEASON TEST OK");
    }
  }
}
