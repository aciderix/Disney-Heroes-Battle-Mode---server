import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.FriendPairID;
import dhserver.*;

/** OUTIL DEV (#72) — lit l'état PERSISTÉ des missions/amitiés de userID=1 (empowerment RALPH-VANELLOPE + nb missions
 *  + claims en attente). Sert à vérifier la persistance après une session EN JEU. Usage : FriendMissionDump [db]. */
public final class FriendMissionDump {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    UserStore store = new UserStore(a.length > 0 ? a[0] : "server/data/dh-server.db");
    ServerUser u = store.loadIfExists(1L, 1);
    if (u == null) { System.out.println("RESULT ERR — userID=1 introuvable"); store.close(); return; }
    FriendPairID pair = FriendPairID.of(UnitType.RALPH, UnitType.VANELLOPE);
    com.perblue.heroes.game.objects.User gu = u.gameUser();
    int emp = gu.getIndividual().getFriendship(pair).getEmpowerment();
    int miss = 0; for (Object o : gu.getIndividual().getMissions()) miss++;
    int claims = 0; for (Object o : gu.getIndividual().getMissionClaimData()) claims++;
    long stamina = gu.getResource(ResourceType.FRIEND_STAMINA);
    int camp = gu.getIndividual().getFriendshipCampaignProgress(pair);
    com.perblue.heroes.network.messages.FriendshipBattleInfo lb = gu.getIndividual().getFriendship(pair).getLastBattle();
    boolean fav = gu.getIndividual().isFavoriteFriendship(pair);
    long diamonds = gu.getResource(ResourceType.DIAMONDS);
    store.close();
    System.out.println("RESULT OK — RALPH-VANELLOPE empowerment=" + emp + ", missions=" + miss
        + ", claimsEnAttente=" + claims + ", FRIEND_STAMINA=" + stamina + ", campaignProgress=" + camp
        + ", lastBattle=" + (lb == null ? "null" : ("{node=" + lb.node + " won=" + lb.won + "}"))
        + ", favorite=" + fav + ", DIAMONDS=" + diamonds);
  }
}
