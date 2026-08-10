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
    int emp = u.gameUser().getIndividual().getFriendship(pair).getEmpowerment();
    int miss = 0; for (Object o : u.gameUser().getIndividual().getMissions()) miss++;
    int claims = 0; for (Object o : u.gameUser().getIndividual().getMissionClaimData()) claims++;
    store.close();
    System.out.println("RESULT OK — RALPH-VANELLOPE empowerment=" + emp + ", missions=" + miss + ", claimsEnAttente=" + claims);
  }
}
