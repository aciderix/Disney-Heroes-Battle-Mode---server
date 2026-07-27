import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #54 (éco) — brûler de la stamina en combat fait GAGNER de l'influence à la guilde
 * (coût stamina × getStaminaBurnInfluenceMultiplier, plafonné). Source passive qui rend les perks achetables.
 */
public final class GuildInfluenceTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "InflGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-infl", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk(), gid);
      long before = g.info.influence;

      long gain = su.applyStaminaBurnInfluence(g, CampaignType.NORMAL, 1, 1);
      if (gain <= 0) throw new AssertionError("influence devrait augmenter (gain=" + gain + ")");
      if (g.info.influence != before + gain) throw new AssertionError("influence incohérente");
      System.out.println("[guild] combat 1-1 → +" + gain + " influence guilde (total " + g.info.influence + ")");

      // Plusieurs combats accumulent.
      long g2 = su.applyStaminaBurnInfluence(g, CampaignType.NORMAL, 1, 2);
      if (g.info.influence != before + gain + g2) throw new AssertionError("accumulation incorrecte");
      System.out.println("[guild] combat 1-2 → +" + g2 + " (total " + g.info.influence + ")");

      // Round-trip DB.
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, gid);
      if (rg.info.influence != g.info.influence) throw new AssertionError("influence non persistée");
      System.out.println("[guild] round-trip DB OK : influence " + rg.info.influence + " persistée");

      System.out.println("GUILD INFLUENCE TEST OK");
    }
  }
}
