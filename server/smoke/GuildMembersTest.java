import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.GuildHelper;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #7 — gestion des membres à DEUX+ comptes (rejoindre OPEN, expulser, candidature APPLICATION_ONLY + accept).
 * On exerce la logique autoritative (mêmes opérations que les handlers LoginServer) + la persistance multi-compte.
 */
public final class GuildMembersTest {
  static CreateGuild mk(GuildNewMemberPolicy p) {
    CreateGuild m = new CreateGuild();
    m.name = "Legion"; m.motto = ""; m.minLevel = 1; m.newMemberPolicy = p; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static ServerUser mkUser(UserStore store, long id) throws Exception {
    ServerUser u = ServerUser.newPlayer(id, 1);
    u.grantHero(UnitType.RALPH);
    // nom lisible pour la candidature
    u.basicInfo().name = "User" + id;
    store.save(u);
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-members", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su1 = mkUser(store, 1L);
      ServerUser su2 = mkUser(store, 2L);
      ServerUser su3 = mkUser(store, 3L);
      su1.giveResource(ResourceType.GOLD, 5000);

      long gid = store.nextGuildID(1);
      ServerGuild g = su1.createGuild(mk(GuildNewMemberPolicy.OPEN), gid);
      store.saveGuild(g); store.save(su1);

      // --- su2 rejoint (OPEN) ---
      su2.joinGuildAs(gid, GuildRole.MEMBER);
      g.memberIDs.add(2L); g.info.memberCount = g.memberCount();
      store.saveGuild(g); store.save(su2);
      if (g.memberCount() != 2) throw new AssertionError("roster devrait avoir 2 membres");
      if (!store.loadIfExists(2L, 1).inGuild()) throw new AssertionError("su2 appartenance non persistée");
      System.out.println("[guild] JOIN OK : su2 rejoint (roster=" + g.memberIDs + ")");

      // --- kick su2 (RULER peut kicker MEMBER) ---
      if (!GuildHelper.canKickMember(su1.currentGuildRole(), su2.currentGuildRole()))
        throw new AssertionError("RULER devrait pouvoir kicker MEMBER");
      g.memberIDs.remove(Long.valueOf(2L)); g.info.memberCount = g.memberCount();
      su2.leaveGuild(); store.saveGuild(g); store.save(su2);
      if (g.memberCount() != 1) throw new AssertionError("roster devrait retomber à 1");
      if (store.loadIfExists(2L, 1).inGuild()) throw new AssertionError("su2 devrait être hors guilde après kick");
      System.out.println("[guild] KICK OK : su2 expulsé (roster=" + g.memberIDs + ")");

      // --- APPLICATION_ONLY : su3 postule, su1 accepte ---
      g.info.newMemberPolicy = GuildNewMemberPolicy.APPLICATION_ONLY;
      g.applicants.put(3L, "User3");
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, gid);
      if (!rg.applicants.containsKey(3L)) throw new AssertionError("candidature su3 non persistée");
      System.out.println("[guild] APPLY OK : su3 candidat (persisté)");

      if (!GuildHelper.canAcceptMembers(su1.currentGuildRole()))
        throw new AssertionError("RULER devrait pouvoir accepter des membres");
      g.applicants.remove(3L);
      su3.joinGuildAs(gid, GuildRole.MEMBER);
      g.memberIDs.add(3L); g.info.memberCount = g.memberCount();
      store.saveGuild(g); store.save(su3);
      if (!g.applicants.isEmpty()) throw new AssertionError("candidature devrait être retirée");
      if (g.memberCount() != 2 || !store.loadIfExists(3L, 1).inGuild())
        throw new AssertionError("su3 devrait être membre après accept");
      System.out.println("[guild] ACCEPT OK : su3 accepté (roster=" + g.memberIDs + ")");

      // round-trip DB final
      ServerGuild fg = store.loadGuild(1, gid);
      if (fg.memberIDs.size() != 2 || !fg.memberIDs.contains(1L) || !fg.memberIDs.contains(3L))
        throw new AssertionError("roster final non persisté: " + fg.memberIDs);
      System.out.println("[guild] round-trip DB OK : roster final " + fg.memberIDs);

      System.out.println("GUILD MEMBERS TEST OK");
    }
  }
}
