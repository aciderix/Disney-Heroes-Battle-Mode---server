import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * OUTIL DEV (#72 FRIENDSHIPS) — prépare le compte userID=1 pour la vérif EN JEU des amitiés :
 * grant RALPH+VANELLOPE (ORANGE 60/5 → paire 1 débloquée), FRIEND_STAMINA (campagne), FRIENDSHIP_EMPOWER_STONE
 * (empower). Usage : FriendAcctSetup [db]  (défaut server/data/dh-server.db).
 */
public final class FriendAcctSetup {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    UserStore store = new UserStore(a.length > 0 ? a[0] : "server/data/dh-server.db");
    ServerUser u = store.loadOrCreate(1L, 1);
    u.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
    u.grantHero(UnitType.VANELLOPE, Rarity.ORANGE, 60, 5);
    u.giveResource(ResourceType.FRIEND_STAMINA, 175);
    u.giveItem(ItemType.FRIENDSHIP_EMPOWER_STONE, 20);
    store.save(u);
    store.close();
    System.out.println("RESULT OK — RALPH+VANELLOPE (60/5), FRIEND_STAMINA="
        + u.gameUser().getResource(ResourceType.FRIEND_STAMINA)
        + ", stones=" + u.itemAmount(ItemType.FRIENDSHIP_EMPOWER_STONE));
  }
}
