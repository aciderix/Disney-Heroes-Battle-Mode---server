import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #61 (correctif multi-serveur) — les AVATARS de guilde débloqués sont CALCULÉS depuis le niveau de
 * guilde (guild_avatars.tab, colonne REQUIRED_GUILD_LEVEL, table cumulative du jeu), PAS renvoyés vides.
 * Prouve : (1) une guilde renvoie la liste de base non vide (le hard-empty précédent était FAUX) ;
 * (2) monter le niveau de guilde (acheter GLn) ne réduit jamais la liste (cumulatif).
 */
public final class GuildAvatarTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "AvatarGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-avatar", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk(), gid);

      // Niveau 0 (guilde neuve, aucun perk GLn). La table étant cumulative avec avatars[0]=avatars[1],
      // la liste de base doit être NON VIDE — le hard-empty précédent était incorrect.
      java.util.List<Avatar> base = su.unlockedGuildAvatars(g);
      if (base.isEmpty())
        throw new AssertionError("avatars niveau 0 devraient être NON vides (table cumulative)");
      System.out.println("[guild] avatars niveau 0 : " + base.size() + " débloqué(s) (base)");

      // Monte le niveau de guilde à 2 en posant le perk GL2 (mute g.info.perkLevels).
      com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
          new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info);
      perks.setPerkLevel(GuildPerkType.GL1, 1);
      perks.setPerkLevel(GuildPerkType.GL2, 1);
      int lvl = com.perblue.heroes.game.logic.GuildHelper.getGuildLevel(perks);
      if (lvl != 2) throw new AssertionError("niveau de guilde attendu 2, obtenu " + lvl);

      java.util.List<Avatar> lvl2 = su.unlockedGuildAvatars(g);
      if (lvl2.size() < base.size())
        throw new AssertionError("cumulatif : niveau 2 (" + lvl2.size() + ") < niveau 0 (" + base.size() + ")");
      System.out.println("[guild] avatars niveau " + lvl + " : " + lvl2.size() + " débloqué(s) (cumulatif ≥ base)");

      // Chaque Avatar doit être bien formé (au moins un critère unit/item/skill non défaut).
      for (Avatar av : lvl2) {
        boolean ok = av.unit != null || av.item != null || av.skill != null;
        if (!ok) throw new AssertionError("Avatar mal formé (aucun unit/item/skill)");
      }

      // Round-trip DB : le niveau (perks) est persisté dans GuildInfo → avatars recalculés identiques.
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, gid);
      java.util.List<Avatar> reloaded = su.unlockedGuildAvatars(rg);
      if (reloaded.size() != lvl2.size())
        throw new AssertionError("avatars après round-trip DB : " + reloaded.size() + " != " + lvl2.size());
      System.out.println("[guild] round-trip DB OK : " + reloaded.size() + " avatars (niveau persisté)");

      System.out.println("GUILD AVATAR TEST OK");
    }
  }
}
