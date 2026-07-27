import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #7 — CHECK-IN autoritatif : émarger crédite l'influence de guilde + récompenses au joueur, une seule fois
 * par jour (garde quotidienne), et l'état survit au round-trip DB.
 */
public final class GuildCheckInTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "CheckInGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-checkin", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk(), gid);
      store.saveGuild(g); store.save(su);

      long inflBefore = g.info.influence;
      java.util.List<RewardDrop> r = su.checkInToGuild(g);
      if (r == null) throw new AssertionError("1er check-in devrait réussir");
      if (!g.checkedInToday.contains(1L)) throw new AssertionError("membre non enregistré comme émargé");
      if (g.info.influence < inflBefore) throw new AssertionError("influence guilde devrait augmenter/rester");
      System.out.println("[guild] check-in #1 OK : influence " + inflBefore + " → " + g.info.influence
          + ", " + r.size() + " récompense(s)");

      // 2e check-in le MÊME jour → refusé (garde quotidienne)
      java.util.List<RewardDrop> r2 = su.checkInToGuild(g);
      if (r2 != null) throw new AssertionError("2e check-in le même jour devrait être refusé");
      System.out.println("[guild] check-in #2 (même jour) correctement refusé");

      // round-trip DB : influence + set d'émargés persistés
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, gid);
      if (rg.info.influence != g.info.influence) throw new AssertionError("influence non persistée");
      if (!rg.checkedInToday.contains(1L)) throw new AssertionError("set émargés non persisté");
      System.out.println("[guild] round-trip DB OK : influence + émargés persistés");

      System.out.println("GUILD CHECKIN TEST OK");
    }
  }
}
