import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.CollectionHelper;
import dhserver.*;

/** OUTIL DEV : prépare un compte pour vérifier l'ACHAT d'avatar de collection en jeu — niveau cumulé DAMAGE >= 8
 *  (BRONZE/SILVER/GOLD highest claimed = 3 → cumLevel 9) + MASTERY_TOKENS. L'avatar COLLECTION_AVATAR_DAMAGE coûte
 *  100 tokens et requiert cumLevel 8. Usage : ExpAdminAvatar [db] [userID] [shard]. */
public final class ExpAdminAvatar {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    UserStore s = new UserStore(db);
    ServerUser su = s.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[avatar-adm] aucun compte"); return; }
    CollectionType C = CollectionType.DAMAGE;
    for (CollectionTier t : new CollectionTier[]{CollectionTier.BRONZE, CollectionTier.SILVER, CollectionTier.GOLD})
      for (int lvl = 1; lvl <= 3; lvl++) CollectionHelper.debugSetHighestClaimedLevel(su.gameUser(), C, t, lvl);
    su.gameUser().setResource(ResourceType.MASTERY_TOKENS, 1000, "adm");
    // persiste le niveau cumulé (collectionsClaimed est write-through, mais on force un save)
    s.save(su);
    ItemType avatar = ItemType.valueOf("COLLECTION_AVATAR_DAMAGE");
    System.out.println("[avatar-adm] compte " + uid + " : cumLevel(DAMAGE)="
        + CollectionHelper.getCumulativeCollectionLevel(su.gameUser(), C)
        + " MASTERY_TOKENS=" + su.gameUser().getResource(ResourceType.MASTERY_TOKENS)
        + " avatar " + avatar + " coût=" + CollectionHelper.getAvatarCost(avatar)
        + " possédé=" + su.gameUser().getItemAmount(avatar) + " [persisté]");
    s.close();
  }
}
