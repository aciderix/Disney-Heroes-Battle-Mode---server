import com.perblue.heroes.network.messages.*;
import dhserver.*;

/** Probe DEV (lecture seule) — état PORT persisté du compte 1 : GOLD, cooldown WAREHOUSE, chances restantes. */
public final class PortStateProbe {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore s = new UserStore(db)) {
      ServerUser su = s.loadOrCreate(1L, 1);
      var u = su.gameUser();
      var iu = su.gameIndividual();
      var NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
      System.out.println("[probe] GOLD = " + u.getResource(ResourceType.GOLD));
      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      for (GameMode mode : new GameMode[]{GameMode.PORT_WAREHOUSE, GameMode.PORT_DOCKS}) {
        CooldownType cd = com.perblue.heroes.game.logic.DifficultyModeHelper.getCooldownType(mode);
        long cdEnd = cd == null ? 0 : iu.getCooldownEnd(cd);
        String uk = com.perblue.heroes.game.logic.DifficultyModeHelper.getUseKey(mode);
        int rem = com.perblue.heroes.game.logic.DailyActivityHelper.getRemainingDailyUses(u, uk, NONE);
        int max = com.perblue.heroes.game.logic.DailyActivityHelper.getMaxDailyUses(u, uk, NONE);
        System.out.println("[probe] " + mode + " : cooldown " + cd + " end=" + cdEnd
            + (cdEnd > now ? " (FUTUR, +" + ((cdEnd-now)/1000) + "s)" : " (passé/absent)")
            + " | chances " + rem + "/" + max);
      }
    }
  }
}
