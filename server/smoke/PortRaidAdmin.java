import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.ModeDifficulty;
import dhserver.*;

/**
 * Outil DEV — prépare le compte (id=1) pour vérifier EN JEU le RAID d'un mode « difficulty » (PORT).
 * Le raid AUTO est VIP-gaté (RAID_PORT = VIP 4) et exige un étage 3★ (raidable). On pose donc sur le compte serveur :
 *   • VIP 4 (débloque RAID_PORT ; RAID_WITHOUT_TICKETS dès VIP 3 → raid PORT sans ticket) ;
 *   • {@code setDifficultyModeStars(PORT_DOCKS/WAREHOUSE, ONE, 3)} (raidable) ;
 *   • purge des cooldowns d'attaque PORT (pour passer {@code doChecks}).
 * On prépare LES DEUX modes PORT (celui ouvert dépend du jour serveur). Usage : PortRaidAdmin [db].
 */
public final class PortRaidAdmin {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore s = new UserStore(db)) {
      ServerUser su = s.loadOrCreate(1L, 1);
      BootData bd = su.bootData();
      if (bd.userInfo.basicInfo.teamLevel < 200) bd.userInfo.basicInfo.teamLevel = 200;
      bd.userInfo.basicInfo.vIPLevel = 4;                 // RAID_PORT (VIP 4)
      var iu = su.gameIndividual();
      for (GameMode mode : new GameMode[]{GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE}) {
        iu.setDifficultyModeStars(mode, ModeDifficulty.ONE, 3);          // raidable (3★)
        CooldownType cd = com.perblue.heroes.game.logic.DifficultyModeHelper.getCooldownType(mode);
        if (cd != null) iu.setCooldownEnd(cd, 0L);                       // purge cooldown d'attaque
      }
      s.save(su);
      ServerUser v = s.loadOrCreate(1L, 1);
      System.out.println("[portraidadmin] VIP=" + v.bootData().userInfo.basicInfo.vIPLevel
          + " DOCKS raidable=" + v.gameIndividual().isAutoAttackAvailable(GameMode.PORT_DOCKS, ModeDifficulty.ONE)
          + " WAREHOUSE raidable=" + v.gameIndividual().isAutoAttackAvailable(GameMode.PORT_WAREHOUSE, ModeDifficulty.ONE)
          + " [persisté]");
    }
  }
}
