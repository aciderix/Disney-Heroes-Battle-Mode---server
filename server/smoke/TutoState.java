import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/** DEV : dump des actes de tuto ayant PROGRESSÉ (step>0) depuis la DB persistée → position exacte du tuto. */
public final class TutoState {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore store = new UserStore(db)) {
      ServerUser su = store.loadOrCreate(1L, 1);
      List<?> acts = su.bootData().individualUserExtra.tutorialActs;
      System.out.println("=== TUTO (" + db + ") : actes avec step>0 ===");
      int progressed = 0;
      for (Object o : acts) {
        TutorialAct t = (TutorialAct) o;
        if (t.step > 0 || t.maxStep > 0) {
          System.out.println("  " + t.type + " : step=" + t.step + " maxStep=" + t.maxStep);
          progressed++;
        }
      }
      System.out.println("  (actes progressés = " + progressed + " / total " + acts.size() + ")");
    }
  }
}
