package dhserver;

import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.ArenaType;
import com.perblue.heroes.network.messages.BasicUserInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * ARÈNE (vrai PvP) — {@link ServerArena.OpponentSource} adossée à {@link UserStore} : les adversaires sont les AUTRES
 * comptes RÉELS du shard qui ont posé une défense. Leur défense est lue par le MÊME chemin que celle du joueur
 * (aucune régénération synthétique). Le complément en bots (quand il y a peu de joueurs) reste géré par
 * {@link ServerArena#generateLadder}. Multi-serveur : tout passe par la base (PRINCIPLES §5/§6).
 */
public final class StoreOpponentSource implements ServerArena.OpponentSource {

  private final UserStore store;

  public StoreOpponentSource(UserStore store) { this.store = store; }

  @Override
  public List<ServerArenaLadder.Entry> realOpponents(int shardID, long excludeID, ArenaType type) {
    List<ServerArenaLadder.Entry> out = new ArrayList<>();
    try {
      for (Long id : store.listUserIDs(shardID, excludeID)) {
        ServerUser su = store.loadIfExists(id, shardID);
        if (su == null || !su.hasArenaDefense(type)) continue;      // seulement ceux qui ont une défense POSÉE
        ServerArenaLadder.Entry e = new ServerArenaLadder.Entry();
        e.id = id;
        BasicUserInfo bi = su.basicInfo();
        e.name = (bi != null && bi.name != null && !bi.name.isEmpty()) ? bi.name : ("Player " + id);
        e.teamLevel = bi != null ? bi.teamLevel : 1;
        e.bot = false;
        e.remainingFightChances = ServerArena.MAX_FIGHTS;
        out.add(e);
      }
    } catch (Exception ex) {
      System.out.println("[arena] realOpponents échec (shard " + shardID + ") : " + ex);
    }
    return out;
  }

  @Override
  public User loadDefender(long id, int shardID) {
    try {
      ServerUser su = store.loadIfExists(id, shardID);
      return su == null ? null : su.gameUser();
    } catch (Exception ex) {
      System.out.println("[arena] loadDefender échec (id " + id + ") : " + ex);
      return null;
    }
  }
}
