import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;
/** OUTIL DEV : reset + vainc N nœuds (pour rendre un COFFRE disponible en jeu : N multiple de 3, chestsOpened=0).
 *  Usage : ExpAdminClearNodes [db] [userID] [shard] [nodes]. */
public final class ExpAdminClearNodes {
  static ExpeditionAttack win(int node){ ExpeditionAttack ea=new ExpeditionAttack(); ea.nodeIndex=node;
    AttackBase b=new AttackBase(); b.outcome=CombatOutcome.WIN; b.stars=3; b.attackers=new ArrayList<>(); b.defenders=new ArrayList<>();
    ea.base=b; ea.attackerHeroes=new ArrayList<>(); ea.defenderHeroes=new ArrayList<>(); return ea; }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db=a.length>0?a[0]:"server/data/dh-server.db"; long uid=a.length>1?Long.parseLong(a[1]):1L;
    int shard=a.length>2?Integer.parseInt(a[2]):1; int n=a.length>3?Integer.parseInt(a[3]):3;
    UserStore s=new UserStore(db); ServerUser su=s.loadIfExists(uid,shard);
    if(su==null){ System.out.println("[clear] aucun compte"); return; }
    ServerExpedition.resetRun(su,1,new ArrayList<>(),true);
    for(int i=0;i<n;i++) ServerExpedition.recordAttack(su, win(i));
    s.save(su);
    ExpeditionRunData r=su.expeditionRunOrNull();
    System.out.println("[clear] compte "+uid+" : nodesDefeated="+r.nodesDefeated+" chestsOpened="+r.chestsOpened
      +" (coffre dispo="+(r.nodesDefeated%3==0 && r.chestsOpened==r.nodesDefeated/3-1)+") expeditionID="+su.expeditionIDPersisted()+" [persisté]");
    s.close();
  }
}
