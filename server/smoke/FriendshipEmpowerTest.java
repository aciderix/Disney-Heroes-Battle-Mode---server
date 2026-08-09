import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.FriendPairID;
import com.perblue.heroes.game.logic.FriendshipHelper;
import com.perblue.heroes.game.data.friendships.FriendshipStats;
import dhserver.*;

/**
 * FRIENDSHIPS (#72) incrément 3a — EMPOWER d'amitié. Tout par le CODE DU JEU (§3), zéro invention (§4) :
 * {@code FriendshipHelper.empowerFriendship} (paire DÉBLOQUÉE requise ; CONSOMME {@code count} ×
 * {@code FRIENDSHIP_EMPOWER_STONE} ; {@code empowerment += getEmpowermentPerConsumable*count}). Vérifie
 * déblocage, consommation d'items (anti-triche), gain d'empowerment, persistance DB (resyncFriendships).
 */
public final class FriendshipEmpowerTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[friendship-empower] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4501L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    // Débloquer la paire : posséder les DEUX héros au niveau requis (friendship_pairs.tab RALPH+VANELLOPE).
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
    su.grantHero(UnitType.VANELLOPE, Rarity.ORANGE, 60, 5);
    FriendPairID pair = FriendPairID.of(UnitType.RALPH, UnitType.VANELLOPE);

    FriendshipHelper.FriendPairStatus st = FriendshipHelper.getUnlockStatus(su.gameUser(), pair);
    check(st == FriendshipHelper.FriendPairStatus.UNLOCKED, "paire débloquée (obtenu " + st + ")");

    int perStone = FriendshipStats.getEmpowermentPerConsumable();
    check(perStone > 0, "getEmpowermentPerConsumable > 0 (donnée du jeu) = " + perStone);

    // --- ANTI-TRICHE : empower sans pierres → refusé (useItem lève), empowerment inchangé ---
    check(su.itemAmount(ItemType.FRIENDSHIP_EMPOWER_STONE) == 0, "0 pierre au départ");
    check(!ServerFriendships.applyEmpower(su, pair, 1), "empower refusé sans pierre (anti-triche useItem)");
    check(su.gameUser().getIndividual().getFriendship(pair).getEmpowerment() == 0, "empowerment inchangé après refus");

    // --- EMPOWER : donne 3 pierres, en consomme 2 ---
    su.giveItem(ItemType.FRIENDSHIP_EMPOWER_STONE, 3);
    check(ServerFriendships.applyEmpower(su, pair, 2), "applyEmpower(x2) doit réussir");
    int emp = su.gameUser().getIndividual().getFriendship(pair).getEmpowerment();
    check(emp == perStone * 2, "empowerment = perStone*2 = " + (perStone * 2) + " (obtenu " + emp + ")");
    check(su.itemAmount(ItemType.FRIENDSHIP_EMPOWER_STONE) == 1, "2 pierres consommées (reste 1, obtenu "
        + su.itemAmount(ItemType.FRIENDSHIP_EMPOWER_STONE) + ")");
    check(su.gameUser().getIndividual().getExtra().friendships.containsKey(pair.getAsLong()),
        "amitié re-synchronisée dans l'extra (clé getAsLong)");

    // --- PERSISTANCE DB : empowerment + item survivent ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-friendship-empower-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4501L, 1);
    check(rl != null, "joueur relu");
    check(rl.gameUser().getIndividual().getFriendship(pair).getEmpowerment() == perStone * 2,
        "empowerment survit à la persistance DB");
    check(rl.itemAmount(ItemType.FRIENDSHIP_EMPOWER_STONE) == 1, "stock de pierres survit à la DB");
    WireCheck.assertRoundTrips(rl.gameUser().getIndividual().getExtra());
    store.close();

    System.out.println("[friendship-empower] OK — déblocage + empower (consomme " + (perStone * 2) + " emp / 2 pierres) "
        + "+ anti-triche + persistance via le code du jeu — #72 incrément 3a");
  }
}
