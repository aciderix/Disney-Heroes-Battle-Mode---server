import com.perblue.heroes.network.messages.ResourceType;
import dhserver.*;

/** OUTIL DEV (#72 incr. 2) — met FRIEND_STAMINA de userID=1 à une valeur cible (défaut 10), pour tester l'achat
 *  d'énergie d'amitié EN JEU (buyFriendStamina refuse si au plafond `FRIEND_STAMINA_FULL`). Usage :
 *  SetFriendStamina [db] [valeur]. FRIEND_STAMINA vit dans individualUserExtra.resources (write-through). */
public final class SetFriendStamina {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long target = a.length > 1 ? Long.parseLong(a[1]) : 10L;
    UserStore store = new UserStore(db);
    ServerUser u = store.loadIfExists(1L, 1);
    if (u == null) { System.out.println("RESULT ERR — userID=1 introuvable"); store.close(); return; }
    long cur = u.gameUser().getResource(ResourceType.FRIEND_STAMINA);
    u.giveResource(ResourceType.FRIEND_STAMINA, target - cur);   // setResource(current + delta)
    store.save(u);
    long after = u.gameUser().getResource(ResourceType.FRIEND_STAMINA);
    store.close();
    System.out.println("RESULT OK — FRIEND_STAMINA " + cur + " → " + after);
  }
}
