import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.item.ItemStats;
import com.perblue.heroes.game.data.item.StatType;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IndividualUser;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * ÉCRAN ITEMS (inventaire) — VENTE d'objets contre or + marquage « vu ». Logique du jeu (UserHelper.sellItem) :
 * or = VEND_VALUE × count, anti-triche RÉEL (objet non vendable / pas assez d'exemplaires). VIEWED_CONSUMABLE_ITEM
 * efface la pastille « nouveau » (auto-persisté).
 */
public final class ItemsTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "it");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "it");
    ServerContext.bind(u, iu);
    return u;
  }

  static void give(User u, ItemType it, int n) {
    RewardDrop r = new RewardDrop(); r.itemType = it; r.quantity = n;
    RewardHelper.giveReward(u, r, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, false, "test");
  }

  static Action sell(ItemType it, int n) {
    Action m = new Action(); m.command = CommandType.SELL_ITEM; m.itemType = it;
    m.extra = new java.util.EnumMap<>(ActionExtraType.class);
    m.extra.put(ActionExtraType.COUNT, Integer.toString(n));
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ItemType SELLABLE = ItemType.EXP_FLASK;              // VEND_VALUE=400 (vérifié)
    ItemType UNSELLABLE = ItemType.STAMINA_CONSUMABLE;   // consommable → non vendable (VEND_VALUE=0, vérifié)
    long vend = (int) ItemStats.getStat(SELLABLE, StatType.VEND_VALUE);
    System.out.println("[items] " + SELLABLE + " VEND_VALUE=" + vend);

    ServerUser su = ServerUser.newPlayer(1L, 1);
    User u = bind(su);
    give(u, SELLABLE, 5);
    give(u, UNSELLABLE, 2);
    long goldBefore = u.getResource(ResourceType.GOLD);
    System.out.println("[items] avant : " + SELLABLE + "=" + u.getItemAmount(SELLABLE) + " gold=" + goldBefore);

    // 1) VENTE de 3 EXP_FLASK → +3×400 or, EXP_FLASK 5→2.
    boolean sold = su.applyAction(sell(SELLABLE, 3));
    User u1 = bind(su);
    long goldAfter = u1.getResource(ResourceType.GOLD);
    int left = u1.getItemAmount(SELLABLE);
    System.out.println("[items] SELL ×3 appliqué=" + sold + " | " + SELLABLE + "=" + left + " gold " + goldBefore + "→" + goldAfter);
    if (!sold) throw new AssertionError("la vente aurait dû réussir");
    if (left != 2) throw new AssertionError("il devrait rester 2 " + SELLABLE);
    if (goldAfter != goldBefore + 3 * vend) throw new AssertionError("or attendu = +" + (3 * vend));

    // 2) PERSISTANCE : reload wire → 2 restants, or persisté.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    User ur = bind(reloaded);
    System.out.println("[items] après reload → " + SELLABLE + "=" + ur.getItemAmount(SELLABLE) + " gold=" + ur.getResource(ResourceType.GOLD));
    if (ur.getItemAmount(SELLABLE) != 2) throw new AssertionError("la vente aurait dû PERSISTER");
    if (ur.getResource(ResourceType.GOLD) != goldBefore + 3 * vend) throw new AssertionError("l'or aurait dû PERSISTER");

    // 3) ANTI-TRICHE (pas assez) : vendre 10 alors qu'il en reste 2 → REFUSÉ, état intact.
    boolean tooMany = su.applyAction(sell(SELLABLE, 10));
    int leftAfter = bind(su).getItemAmount(SELLABLE);
    System.out.println("[items] SELL ×10 (n'en a que 2) appliqué=" + tooMany + " | restants=" + leftAfter);
    if (tooMany) throw new AssertionError("vendre plus qu'on n'a aurait dû être REFUSÉ");
    if (leftAfter != 2) throw new AssertionError("aucune mutation sur une vente refusée");

    // 4) ANTI-TRICHE (non vendable) : vendre un fragment héros (VEND_VALUE=0) → REFUSÉ.
    boolean unsell = su.applyAction(sell(UNSELLABLE, 1));
    System.out.println("[items] SELL " + UNSELLABLE + " (non vendable) appliqué=" + unsell + " (doit être false)");
    if (unsell) throw new AssertionError("vendre un objet non vendable aurait dû être REFUSÉ (CANT_SELL_ITEM)");

    // 5) VIEWED_CONSUMABLE_ITEM → marqué vu (auto-persisté).
    Action viewed = new Action(); viewed.command = CommandType.VIEWED_CONSUMABLE_ITEM; viewed.itemType = SELLABLE;
    boolean v = su.applyAction(viewed);
    boolean isViewed = ((IndividualUser) bind(su).getIndividual()).hasViewedConsumableItem(SELLABLE);
    System.out.println("[items] VIEWED_CONSUMABLE_ITEM appliqué=" + v + " hasViewed=" + isViewed);
    if (!v || !isViewed) throw new AssertionError("le consommable aurait dû être marqué vu");

    System.out.println("[items] OK — vente (or crédité + item décrémenté + persiste) + anti-triche (pas assez / non vendable) + marquage vu");
  }
}
