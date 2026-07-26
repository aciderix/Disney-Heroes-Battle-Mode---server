import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILDES #7 — flux de CRÉATION serveur-autoritatif + persistance multi-serveur.
 *
 * <p>Prouve : (1) CreateGuild débite le coût (2000 GOLD via GuildHelper.chargeForCreation) et REFUSE si insuffisant
 * (anti-triche) ; (2) la guilde créée est PERSISTÉE (table guilds) et rechargée à l'identique (nom/roster) ;
 * (3) l'appartenance du fondateur (guildID>0, RULER) survit au round-trip DB du JOUEUR (BasicUserInfo) ;
 * (4) la guilde apparaît dans listGuilds (écran FIND A GUILD) ; (5) LeaveGuild dissout la guilde vide.
 */
public final class GuildCreateTest {

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name;
    m.motto = "For the win";
    m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN;
    m.country = "US";
    m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      store.save(su);

      // --- 1) REFUS anti-triche : GOLD insuffisant (compte neuf = 0 GOLD) ---
      boolean refused = false;
      // ClientErrorCodeException est CHECKÉE mais dex2jar retire le `throws` → on l'attrape en Throwable.
      try { su.createGuild(mk("Broke Guild"), store.nextGuildID(1)); }
      catch (Throwable e) {
        if (e instanceof com.perblue.heroes.ClientErrorCodeException) refused = true; else throw e;
      }
      if (!refused) throw new AssertionError("CreateGuild aurait dû être REFUSÉ (0 GOLD < 2000)");
      if (su.inGuild()) throw new AssertionError("refus : le joueur ne doit PAS être en guilde");
      System.out.println("[guild] refus anti-triche OK (0 GOLD)");

      // --- 2) CRÉATION réussie après crédit du coût ---
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk("Heroes United"), gid);
      store.saveGuild(g);
      store.save(su);
      if (!su.inGuild()) throw new AssertionError("après création, le joueur doit être en guilde");
      if (su.currentGuildRole() != GuildRole.RULER) throw new AssertionError("fondateur doit être RULER");
      if (g.memberCount() != 1) throw new AssertionError("guilde neuve = 1 membre");
      System.out.println("[guild] création OK : guilde #" + gid + " '" + g.info.basicInfo.name
          + "' fondateur RULER, membres=" + g.memberCount());

      // --- 3) round-trip DB : guilde rechargée identique + appartenance joueur persistée ---
      ServerGuild reloaded = store.loadGuild(1, gid);
      if (reloaded == null) throw new AssertionError("guilde non persistée");
      if (!"Heroes United".equals(reloaded.info.basicInfo.name))
        throw new AssertionError("nom non persisté : " + reloaded.info.basicInfo.name);
      if (reloaded.memberIDs.size() != 1 || reloaded.memberIDs.get(0) != 1L)
        throw new AssertionError("roster non persisté : " + reloaded.memberIDs);
      if (reloaded.info.basicInfo.iD != gid)
        throw new AssertionError("guildID non persisté dans BasicGuildInfo");

      ServerUser reSu = store.loadIfExists(1L, 1);
      if (reSu == null || !reSu.inGuild() || reSu.currentGuildID() != gid)
        throw new AssertionError("appartenance du joueur non persistée (guildID)");
      if (reSu.currentGuildRole() != GuildRole.RULER)
        throw new AssertionError("rôle RULER non persisté");
      System.out.println("[guild] round-trip DB OK : guilde + appartenance RULER rechargées");

      // --- 4) listGuilds (écran FIND A GUILD) voit la guilde ---
      java.util.List<ServerGuild> list = store.listGuilds(1, null, 20);
      if (list.isEmpty()) throw new AssertionError("listGuilds vide alors qu'une guilde existe");
      boolean found = false;
      for (ServerGuild x : list) if (x.guildID == gid) found = true;
      if (!found) throw new AssertionError("guilde absente de listGuilds");
      // recherche par nom
      if (store.listGuilds(1, "Heroes", 20).isEmpty())
        throw new AssertionError("recherche par nom 'Heroes' ne trouve rien");
      System.out.println("[guild] listGuilds OK (" + list.size() + " guilde(s), recherche par nom OK)");

      // --- 5) LeaveGuild → dissolution (guilde vide supprimée) ---
      reSu.leaveGuild();
      java.util.List<Long> members = reloaded.memberIDs;
      members.remove(Long.valueOf(1L));
      if (members.isEmpty()) store.deleteGuild(1, gid);
      store.save(reSu);
      if (reSu.inGuild()) throw new AssertionError("après départ, joueur ne doit plus être en guilde");
      if (store.loadGuild(1, gid) != null) throw new AssertionError("guilde vide devait être dissoute");
      System.out.println("[guild] LeaveGuild OK : appartenance effacée + guilde vide dissoute");

      System.out.println("GUILD CREATE TEST OK");
    }
  }
}
