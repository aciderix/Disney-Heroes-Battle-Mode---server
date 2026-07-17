import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.UserHelper;
import dhserver.*;

/**
 * Inspecteur d'état persisté (DEV) — charge la DB SQLite d'un joueur et imprime TOUT son état de progression
 * pour vérifier la persistance de bout en bout (PRINCIPLES §6) : ressources, héros (rang/niveau/étoiles/gear),
 * progression campagne, inventaire. Usage : {@code DbInspect [chemin.db]} (défaut server/data/dh-server.db).
 */
public final class DbInspect {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore store = new UserStore(db)) {
      ServerUser su = store.loadOrCreate(1L, 1);
      BootData bd = su.bootData();
      User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "inspect");
      IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
          bd.individualUserExtra, 1L, bd.userInfo.diamonds, "inspect");
      ServerContext.bind(u, iu);

      long staminaRaw = u.getResource(ResourceType.STAMINA);
      long staminaCap = UserHelper.getResourceCap(ResourceType.STAMINA, u);
      System.out.println("=== ÉTAT PERSISTÉ (" + db + ") ===");
      System.out.println("RESSOURCES : teamLevel=" + u.getTeamLevel()
          + "  gold=" + u.getResource(ResourceType.GOLD)
          + "  diamants=" + bd.userInfo.diamonds
          + "  stamina=" + Math.min(staminaRaw, staminaCap) + "/" + staminaCap);

      System.out.println("HÉROS :");
      int nHeroes = 0;
      for (Object o : u.getHeroes()) {
        IHero h = (IHero) o; nHeroes++;
        StringBuilder gear = new StringBuilder();
        for (HeroEquipSlot slot : HeroEquipSlot.values()) {
          IEquippedItem it = h.getItem(slot);
          if (it != null && it.getType() != null && it.getType() != ItemType.DEFAULT)
            gear.append(slot).append("=").append(it.getType()).append(" ");
        }
        System.out.println("  " + h.getType() + " " + h.getRarity() + " niv." + h.getLevel()
            + " " + h.getStars() + "★  gear[" + gear.toString().trim() + "]");
      }
      System.out.println("  (total héros = " + nHeroes + ")");

      System.out.println("CAMPAGNE (NORMAL ch.1) :");
      for (int lv = 1; lv <= 15; lv++) {
        ICampaignLevelStatus s = u.getCampaignLevel(CampaignType.NORMAL, 1, lv);
        if (s != null && (s.getStars() > 0 || s.getTotalWins() > 0 || s.getTotalAttempts() > 0))
          System.out.println("  1-" + lv + " : " + s.getStars() + "★  wins=" + s.getTotalWins()
              + " tentatives=" + s.getTotalAttempts());
      }

      System.out.println("INVENTAIRE (items > 0) :");
      int nItems = 0;
      for (ItemType it : ItemType.values()) {
        int amt = u.getItemAmount(it);
        if (amt > 0) { System.out.println("  " + it + " x" + amt); nItems++; }
      }
      System.out.println("  (total types d'objets = " + nItems + ")");
    }
  }
}
