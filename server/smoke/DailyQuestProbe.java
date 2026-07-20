import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.quests.QuestStats;
import com.perblue.heroes.game.data.quests.QuestType;
import com.perblue.heroes.game.logic.QuestHelper;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/** Probe FACTUEL : pour le compte courant (DB), quelles quêtes sont DÉBLOQUÉES et PRÊTES À RÉCLAMER MAINTENANT. */
public final class DailyQuestProbe {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long now = System.currentTimeMillis();
    java.util.Calendar c = java.util.Calendar.getInstance();
    c.setTimeInMillis(now);
    System.out.println("[probe] heure serveur (machine) = " + c.get(java.util.Calendar.HOUR_OF_DAY) + "h"
        + c.get(java.util.Calendar.MINUTE) + " (" + new java.util.Date(now) + ")");

    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (dhserver.UserStore store = new dhserver.UserStore(db)) {
      ServerUser su = store.loadOrCreate(1L, 1);
      BootData bd = su.bootData();
      User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "probe");
      var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
          bd.individualUserExtra, 1L, bd.userInfo.diamonds, "probe");
      ServerContext.bind(u, iu);
      ServerContext.bindBattlePass(su.refreshBattlePass());
      System.out.println("[probe] teamLevel=" + u.getTeamLevel());

      for (int id = 1; id <= 300; id++) {
        try {
          if (QuestStats.getQuest(id) == null) continue;
          QuestType ty = QuestStats.getType(id);
          if (ty != QuestType.DAILY_QUEST && ty != QuestType.FREE_STAMINA) continue;
          boolean unlocked = QuestHelper.isUnlocked(id, u);
          if (!unlocked) continue;
          boolean ready = false;
          try { ready = QuestHelper.isReadyToComplete(id, u); } catch (Throwable t) { ready = false; }
          System.out.println("[probe] quête id=" + id + " type=" + ty + " DÉBLOQUÉE ready=" + ready);
        } catch (Throwable ignore) {}
      }
    }
    System.out.println("[probe] fin");
  }
}
