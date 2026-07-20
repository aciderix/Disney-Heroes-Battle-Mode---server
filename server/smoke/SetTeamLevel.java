import com.perblue.heroes.network.messages.*;
import dhserver.*;
/** Outil DEV : fixe le team level du compte (pour débloquer les écrans gatés). Usage: SetTeamLevel [level]. */
public final class SetTeamLevel {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    int lvl = a.length > 0 ? Integer.parseInt(a[a.length-1]) : 65;
    String db = a.length > 1 ? a[0] : "server/data/dh-server.db";
    try (UserStore s = new UserStore(db)) {
      ServerUser su = s.loadOrCreate(1L, 1);
      BootData bd = su.bootData();
      int before = bd.userInfo.basicInfo.teamLevel;
      bd.userInfo.basicInfo.teamLevel = lvl;   // même référence que su.userInfo → sérialisé au save
      s.save(su);
      // relire pour vérifier
      ServerUser su2 = s.loadOrCreate(1L, 1);
      System.out.println("[tl] teamLevel " + before + " → " + su2.bootData().userInfo.basicInfo.teamLevel + " [persisté]");
    }
  }
}
