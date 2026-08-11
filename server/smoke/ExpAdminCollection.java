import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.CollectionHelper;
import com.perblue.heroes.game.objects.IUser;
import dhserver.*;
import java.util.*;

/** OUTIL DEV : prépare un compte pour vérifier le CLAIM de collection en jeu — collection DAMAGE/BRONZE amenée à
 *  l'état CLAIMABLE (6 héros DAMAGE à 6★ + maîtrise >= cap 20 → 6 héros maîtrisés >= 5 requis pour le niveau 1).
 *  Usage : ExpAdminCollection [db] [userID] [shard]. */
public final class ExpAdminCollection {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    UserStore s = new UserStore(db);
    ServerUser su = s.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[coll-adm] aucun compte"); return; }
    IUser u = su.gameUser();
    CollectionType COL = CollectionType.DAMAGE; CollectionTier T = CollectionTier.BRONZE;
    var cs = Class.forName("com.perblue.heroes.game.data.collections.CollectionStats");
    int cap = (int) cs.getMethod("getNumUsesRequiredForMastery", CollectionTier.class, int.class).invoke(null, T, 1);
    int lvl = Math.max(1, su.bootData().userInfo.basicInfo.teamLevel);   // ne pas dépasser le TL (HERO_ABOVE_TEAM_LEVEL)
    List<UnitType> heroes = (List<UnitType>) CollectionHelper.getHeroesInCollection(u, COL);
    List<UnitType> g = new ArrayList<>();
    for (UnitType h : heroes) { if (g.size() >= 6) break; if (u.getHero(h) == null) su.grantHero(h, Rarity.RED, lvl, 6); g.add(h); }
    for (UnitType h : g) u.getIndividual().setCollectionHeroMasteryUses(COL, T, h, cap + 1);
    s.save(su);
    System.out.println("[coll-adm] compte " + uid + " : DAMAGE/BRONZE, cap=" + cap + ", héros maîtrisés=" + g
        + " → state(lvl1)=" + CollectionHelper.getCollectionState(u, COL, T, 1)
        + " highestClaimed=" + u.getIndividual().getHighestClaimedCollectionLevel(COL, T)
        + " MASTERY_TOKENS=" + u.getResource(ResourceType.MASTERY_TOKENS) + " [persisté]");
    s.close();
  }
}
