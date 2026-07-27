import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #7 — édition des réglages (EditGuild) + renommage (SetGuildName) + dissolution (DISBAND) autoritatifs,
 * avec persistance. Le fondateur est RULER → toutes les permissions d'édition passent.
 */
public final class GuildManageTest {

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = "old"; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-manage", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk("Alpha"), gid);
      store.saveGuild(g); store.save(su);

      // --- EDIT settings ---
      EditGuild eg = new EditGuild();
      eg.guildID = gid; eg.motto = "For glory"; eg.minLevel = 42;
      eg.newMemberPolicy = GuildNewMemberPolicy.APPLICATION_ONLY;
      eg.country = "FR"; eg.timeZone = "GMT+1";
      eg.autoPostAidRequests = false; eg.ignoreKickedPlayersList = true; eg.tacticiansSeeOfficerChat = true;
      if (!su.editGuild(g, eg)) throw new AssertionError("editGuild devrait s'appliquer (RULER)");
      store.saveGuild(g);
      ServerGuild r = store.loadGuild(1, gid);
      if (!"For glory".equals(r.info.motto)) throw new AssertionError("motto non persisté: " + r.info.motto);
      if (r.info.minTeamLevel != 42) throw new AssertionError("minLevel non persisté: " + r.info.minTeamLevel);
      if (r.info.newMemberPolicy != GuildNewMemberPolicy.APPLICATION_ONLY)
        throw new AssertionError("policy non persistée: " + r.info.newMemberPolicy);
      if (!"FR".equals(r.info.country)) throw new AssertionError("country non persisté");
      System.out.println("[guild] EditGuild OK : motto/minLevel/policy/country persistés");

      // --- RENAME ---
      if (!su.renameGuild(g, "Omega")) throw new AssertionError("renameGuild devrait s'appliquer (RULER)");
      store.saveGuild(g);
      r = store.loadGuild(1, gid);
      if (!"Omega".equals(r.info.basicInfo.name)) throw new AssertionError("nom non renommé: " + r.info.basicInfo.name);
      if (!"Alpha".equals(r.info.basicInfo.previousName)) throw new AssertionError("previousName non conservé");
      System.out.println("[guild] SetGuildName OK : 'Alpha' → 'Omega' (previousName conservé), persisté");

      // --- DISBAND (logique du handler : leave tous + delete) ---
      for (Long mid : new java.util.ArrayList<>(g.memberIDs)) {
        ServerUser mu = (mid == 1L) ? su : store.loadIfExists(mid, 1);
        if (mu != null) { mu.leaveGuild(); store.save(mu); }
      }
      store.deleteGuild(1, gid);
      if (su.inGuild()) throw new AssertionError("après dissolution, le fondateur ne doit plus être en guilde");
      if (store.loadGuild(1, gid) != null) throw new AssertionError("guilde dissoute devait être supprimée");
      // relecture DB du joueur : appartenance effacée
      ServerUser re = store.loadIfExists(1L, 1);
      if (re.inGuild()) throw new AssertionError("appartenance effacée non persistée");
      System.out.println("[guild] DISBAND OK : guilde supprimée + appartenance effacée (persisté)");

      System.out.println("GUILD MANAGE TEST OK");
    }
  }
}
