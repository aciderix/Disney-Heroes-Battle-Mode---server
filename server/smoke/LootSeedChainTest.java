import com.perblue.heroes.network.messages.*; import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*; import com.perblue.heroes.game.logic.CampaignLootHelper;
import com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot; import com.perblue.heroes.game.objects.GuildInfoPerkProvider;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot; import dhserver.*; import java.util.*;
/**
 * #25 (etude A) - CORRECTIF de l'OFF-BY-ONE de graine LOOT. Le serveur divergeait en jeu car il roulait avec la
 * graine POST-tirage (S_{i+1}, recue via SET_SEED REASON=return AVANT le CampaignAttack) au lieu de la graine
 * PRE-combat (S_i). PREUVE du correctif : un serveur qui GERE SA PROPRE chaine (S0=getDefaultSeed(userID), avance
 * via returnRandom-nextLong, en IGNORANT le SET_SEED(LOOT) client) matche le client a CHAQUE combat sur N.
 */
public class LootSeedChainTest {
 static final CampaignType T=CampaignType.NORMAL; static final int CH=1,LV=1,N=6;
 static String sig(CampaignLoot l){ List<String> p=new ArrayList<>();
   for(Object o:l.combinedLoot){RewardDrop d=(RewardDrop)o; if(d.quantity>0)p.add((d.itemType!=null&&d.itemType!=ItemType.DEFAULT?d.itemType:d.resourceType)+"x"+d.quantity);}
   Collections.sort(p); return p.toString(); }
 // un roll + avance de la chaîne (mirroir client : resetRandom(graine courante) → getLoot → updateMemory → returnRandom(nextLong))
 static String rollAdvance(User u, IndividualUser iu){
   u.resetRandom(RandomSeedType.LOOT);
   CampaignLoot cl=CampaignLootHelper.getLoot(u,T,0,CH,LV,SpecialEventSnapshot.NONE,new GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo()),true);
   u.setExpLootPool(cl.newExpLootPool); CampaignLootHelper.updateMemoryUnconditional(u,cl,CH);
   long next=u.getRandom(RandomSeedType.LOOT).nextLong(); iu.setSeed(RandomSeedType.LOOT,next,"boot");
   return sig(cl);
 }
 static User bind(long id){ ServerUser su=ServerUser.newPlayer(id,1); BootData bd=su.bootData();
   User u=ClientNetworkStateConverter.getUser(bd.userInfo,bd.userExtra,"s");
   IndividualUser iu=ClientNetworkStateConverter.getIndividualUser(bd.individualUserExtra,id,bd.userInfo.diamonds,"s");
   ServerContext.bind(u,iu); return u; }
 public static void main(String[] a) throws Exception { ServerContext.init();
  // CLIENT et SERVEUR : deux comptes userID=1 identiques, chacun gère sa chaîne depuis S0=getDefaultSeed(1).
  User client=bind(1L); IndividualUser ci=client.getIndividual();
  User server=bind(1L); IndividualUser si=server.getIndividual();
  int mismatch=0;
  for(int i=1;i<=N;i++){
    ServerContext.bind(client,ci); String c=rollAdvance(client,ci);
    ServerContext.bind(server,si); String s=rollAdvance(server,si);
    boolean ok=c.equals(s); if(!ok)mismatch++;
    System.out.println("combat "+i+": client="+c+"  server="+s+(ok?"  ✅":"  ❌"));
  }
  if(mismatch!=0) throw new AssertionError("SEED-CHAIN: "+mismatch+" divergences (la chaine geree serveur devrait matcher le client a chaque combat)");
  System.out.println("LOOT SEED-CHAIN TEST OK - serveur gerant sa chaine (S0->nextLong->...) == client a CHAQUE combat");
 }}
