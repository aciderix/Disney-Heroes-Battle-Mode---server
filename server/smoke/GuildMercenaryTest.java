import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #57 — MERCENAIRES : poster un héros à louer (POST_HERO) l'ajoute au pool du joueur
 * (userExtra.recentlyPostedHeroes, auto-persisté) ; postedMercenaries() le renvoie en MercenaryHeroData avec
 * son HeroData réel. Survit au round-trip DB. Un héros non possédé n'est pas posté.
 */
public final class GuildMercenaryTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "MercGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-merc", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.grantHero(UnitType.ELASTIGIRL);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk(), gid);
      store.saveGuild(g); store.save(su);

      // Héros non possédé → non posté.
      su.postMercenary(UnitType.MAUI);
      if (!su.postedMercenaries().isEmpty()) throw new AssertionError("héros non possédé ne devrait pas être posté");

      // Poster RALPH.
      su.postMercenary(UnitType.RALPH);
      java.util.List<MercenaryHeroData> merc = su.postedMercenaries();
      if (merc.size() != 1) throw new AssertionError("1 mercenaire attendu, obtenu " + merc.size());
      MercenaryHeroData md = merc.get(0);
      if (md.heroData == null || md.heroData.type != UnitType.RALPH) throw new AssertionError("HeroData incorrect");
      if (md.ownerID != 1L) throw new AssertionError("ownerID incorrect");
      System.out.println("[guild] posté RALPH → mercenaire (owner " + md.ownerID + "/" + md.ownerName + ")");

      // Cap de la guilde de base = 1 slot/membre (le perk MERCENARY en ajoute). Poster un 2ᵉ héros ÉVINCE le 1ᵉʳ.
      su.postMercenary(UnitType.ELASTIGIRL);
      java.util.List<MercenaryHeroData> after = su.postedMercenaries();
      if (after.size() != 1) throw new AssertionError("cap de guilde de base = 1 slot, obtenu " + after.size());
      if (after.get(0).heroData.type != UnitType.ELASTIGIRL) throw new AssertionError("le plus récent devrait être gardé");
      System.out.println("[guild] cap 1 slot respecté : poster ELASTIGIRL évince RALPH → pool [" + after.get(0).heroData.type + "]");

      // Round-trip DB.
      store.save(su);
      ServerUser rs = store.loadIfExists(1L, 1);
      java.util.List<MercenaryHeroData> reload = rs.postedMercenaries();
      if (reload.size() != 1 || reload.get(0).heroData.type != UnitType.ELASTIGIRL)
        throw new AssertionError("pool non persisté (" + reload.size() + ")");
      System.out.println("[guild] round-trip DB OK : " + reload.size() + " mercenaire persisté (" + reload.get(0).heroData.type + ")");

      System.out.println("GUILD MERCENARY TEST OK");
    }
  }
}
