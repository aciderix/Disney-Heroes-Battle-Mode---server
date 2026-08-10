import com.perblue.heroes.network.messages.*;
import dhserver.*;
/** OUTIL DEV : prépare un compte pour vérifier l'ENCHANT en jeu — héros + gear complet du rang (enchantable) +
 *  matériaux (VOID_DUST) + or. Usage : ExpAdminEnchant [db] [userID] [shard]. */
public final class ExpAdminEnchant {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db=a.length>0?a[0]:"server/data/dh-server.db"; long uid=a.length>1?Long.parseLong(a[1]):1L; int shard=a.length>2?Integer.parseInt(a[2]):1;
    UserStore s=new UserStore(db); ServerUser su=s.loadIfExists(uid,shard);
    if(su==null){ System.out.println("[ench-adm] aucun compte"); return; }
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 100, 5);
    su.debugGiveFullGear(UnitType.RALPH);
    if (su.bootData().userInfo.diamonds < 50000) su.bootData().userInfo.diamonds = 50000;   // pour le chemin diamants
    su.gameUser().addItem(ItemType.VOID_DUST, 50, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "adm");
    su.gameUser().addItem(ItemType.SHIMMER_DUST, 50, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "adm");
    su.gameUser().setResource(ResourceType.GOLD, 20_000_000L, "adm");
    s.save(su);
    var it=su.gameUser().getHero(UnitType.RALPH).getItem(HeroEquipSlot.ONE);
    System.out.println("[ench-adm] compte "+uid+" prêt : RALPH slot ONE="+(it==null?"null":it.getType())
      +" étoiles="+(it==null?"?":it.getStars())+", VOID_DUST="+su.gameUser().getItemAmount(ItemType.VOID_DUST)
      +", or="+su.gameUser().getResource(ResourceType.GOLD)+" [persisté]");
    s.close();
  }
}
