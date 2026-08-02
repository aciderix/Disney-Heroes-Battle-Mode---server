import com.perblue.heroes.network.messages.*;
import dhserver.*;
/**
 * Outil DEV : sème une guilde ADVERSE (avec son chef) sur le shard, inscrite en file de GUERRE, pour que
 * l'appariement du serveur ait quelqu'un à opposer au joueur. Même rôle que GuildAidSeed pour les dons.
 * Usage : WarRivalSeed <db> <userID> <nom de guilde> [MMR]
 */
public final class WarRivalSeed {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a[0]; long uid = Long.parseLong(a[1]); String name = a[2];
    int mmr = a.length > 3 ? Integer.parseInt(a[3]) : ServerWar.startingMMR();
    try (UserStore store = new UserStore(db)) {
      ServerUser u = store.loadIfExists(uid, 1);
      if (u == null) u = ServerUser.newPlayer(uid, 1);
      u.giveResource(ResourceType.GOLD, 5000);
      u.basicInfo().teamLevel = 100;
      u.basicInfo().name = name.replace(" ", "") + "Leader";
      CreateGuild m = new CreateGuild();
      m.name = name; m.motto = "Ready for war"; m.minLevel = 1;
      m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
      ServerGuild g = u.createGuild(m, store.nextGuildID(1));
      ServerWar.rollOverSeason(g, ServerWar.seasonIDAt(com.perblue.heroes.util.TimeUtil.serverTimeNow()), 0);
      g.warMMR = mmr;
      g.warPromotionMask = ServerWar.markLeagueReached(0, ServerWar.leagueForMMR(mmr));
      g.setWarQueueState(WarQueueState.QUEUED_PERSISTENT);
      g.warQueuedTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      store.saveGuild(g); store.save(u);
      System.out.println("[seed] guilde adverse '" + name + "' id=" + g.guildID + " MMR=" + mmr
          + " ligue=" + ServerWar.leagueForMMR(mmr) + " chef=" + uid + " (inscrite en file)");
    }
  }
}
