import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.logic.HeroHelper;
import dhserver.*;

/** Outil DEV : prépare le compte pour tester SKILL_UPGRADE EN JEU — booste RALPH (marge de niveau) + crédite
 *  des SKILL_POINTS, puis sauvegarde. Affiche le slot montable (pour cibler le tap en jeu). */
public final class SkillSetup {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore s = new UserStore(db)) {
      ServerUser su = s.loadOrCreate(1L, 1);
      su.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5);          // marge de niveau pour les skills
      BootData bd = su.bootData();
      User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "setup");
      var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
          bd.individualUserExtra, 1L, bd.userInfo.diamonds, "setup");
      ServerContext.bind(u, iu);
      u.getIndividual().setResource(u, ResourceType.SKILL_POINTS, 500L, "setup");
      s.save(su);

      // relire + rapport
      ServerUser su2 = s.loadOrCreate(1L, 1);
      BootData bd2 = su2.bootData();
      User u2 = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd2.userInfo, bd2.userExtra, "chk");
      var iu2 = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
          bd2.individualUserExtra, 1L, bd2.userInfo.diamonds, "chk");
      ServerContext.bind(u2, iu2);
      System.out.println("[setup] SKILL_POINTS=" + u2.getResource(ResourceType.SKILL_POINTS)
          + " GOLD=" + u2.getResource(ResourceType.GOLD));
      for (SkillSlot sl : new SkillSlot[]{SkillSlot.WHITE, SkillSlot.GREEN, SkillSlot.BLUE, SkillSlot.PURPLE, SkillSlot.RED}) {
        boolean can = HeroHelper.canUpgradeSkill(UnitType.RALPH, sl, u2);
        int lvl = u2.getHero(UnitType.RALPH).getSkillLevel(sl);
        System.out.println("[setup] RALPH " + sl + " niveau=" + lvl + " montable=" + can);
      }
      System.out.println("[setup] OK — compte prêt pour test SKILL_UPGRADE en jeu [persisté]");
    }
  }
}
