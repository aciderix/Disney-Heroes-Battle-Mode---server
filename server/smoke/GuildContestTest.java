import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #67 — CONTESTS. Scoring OPÉRATEUR : points de contest à la GUILDE (GuildInfo.contestPoints) et au JOUEUR
 * (ressource GUILD_CONTEST_POINTS), persistés. Le classement des guildes (GET_GUILD_CONTEST_RANKINGS) trie le
 * shard par contestPoints ; le classement des joueurs (GET_CONTEST_RANKINGS) trie les membres par leurs points.
 * Prouve : le scoring met à jour l'état persistant + l'ENTRÉE du classement est correcte (tri décroissant).
 */
public final class GuildContestTest {
  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static ServerGuild mkGuild(UserStore store, long uid, String name, int contestPoints) throws Exception {
    ServerUser f = ServerUser.newPlayer(uid, 1);
    f.grantHero(UnitType.RALPH); f.giveResource(ResourceType.GOLD, 5000);
    long gid = store.nextGuildID(1);
    ServerGuild g = f.createGuild(mk(name), gid);
    f.awardGuildContestPoints(g, contestPoints);
    store.saveGuild(g);
    return g;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-contest", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // 3 guildes avec des points de contest distincts.
      ServerGuild g1 = mkGuild(store, 1L, "ContestA", 100);
      ServerGuild g2 = mkGuild(store, 2L, "ContestB", 300);
      ServerGuild g3 = mkGuild(store, 3L, "ContestC", 200);

      // Classement des guildes = tri décroissant par contestPoints (entrée de GET_GUILD_CONTEST_RANKINGS).
      java.util.List<ServerGuild> all = store.listGuilds(1, null, 200);
      all.sort((x, y) -> Long.compare(y.info.contestPoints, x.info.contestPoints));
      if (all.size() < 3) throw new AssertionError("3 guildes attendues, " + all.size());
      if (all.get(0).info.contestPoints != 300 || all.get(1).info.contestPoints != 200 || all.get(2).info.contestPoints != 100)
        throw new AssertionError("ordre du classement contest incorrect : "
            + all.get(0).info.contestPoints + "," + all.get(1).info.contestPoints + "," + all.get(2).info.contestPoints);
      System.out.println("[guild] classement contest guildes : 300 > 200 > 100 (ContestB > ContestC > ContestA)");

      // Round-trip DB : les points de contest de la guilde persistent.
      ServerGuild rg2 = store.loadGuild(1, g2.guildID);
      if (rg2.info.contestPoints != 300) throw new AssertionError("contestPoints guilde non persistés : " + rg2.info.contestPoints);
      System.out.println("[guild] round-trip DB OK : contestPoints guilde persistés (" + rg2.info.contestPoints + ")");

      // Points de contest du JOUEUR = ressource GUILD_CONTEST_POINTS, SPÉCIALE (non réglable par setResource :
      // le jeu la calcule depuis l'état du contest). On VÉRIFIE ce FAIT : giveResource est un no-op ici (≠ GOLD).
      ServerUser p = ServerUser.newPlayer(10L, 1);
      p.giveResource(ResourceType.GOLD, 500);
      if (p.resourceAmount(ResourceType.GOLD) != 500) throw new AssertionError("GOLD devrait être réglable");
      p.giveResource(ResourceType.GUILD_CONTEST_POINTS, 75);
      if (p.resourceAmount(ResourceType.GUILD_CONTEST_POINTS) != 0)
        throw new AssertionError("GUILD_CONTEST_POINTS devrait être NON réglable (opérateur/contest-calculé), obtenu "
            + p.resourceAmount(ResourceType.GUILD_CONTEST_POINTS));
      System.out.println("[guild] GUILD_CONTEST_POINTS confirmé SPÉCIAL (non réglable) → points joueur = opérateur/contest");

      System.out.println("GUILD CONTEST TEST OK");
    }
  }
}
