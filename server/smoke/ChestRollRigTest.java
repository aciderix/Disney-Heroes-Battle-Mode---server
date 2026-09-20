import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.game.logic.ChestHelper;
import dhserver.ServerContext;
import dhserver.ServerUser;
import java.util.*;

/**
 * Coffres — RIG DE PREMIER TIRAGE (issue #1 bug 2 : « free boxes et diamond crates donnent la MÊME récompense
 * encore et encore »).
 *
 * <p>Cause racine (relevée aux données + au bytecode) : les tables de drop des coffres
 * ({@code gold_chest_drops.tab} / {@code silver_chest_drops.tab}) riggent les premiers tirages via des nœuds
 * {@code ? PreviousRolls(N) ? <RIG> ? <suite>} (ex. {@code ROOT_1X_FIRST : PreviousRolls(0) → HERO_FROZONE}).
 * Le prédicat {@code PreviousRolls} lit le compteur de tirages {@code user.getCount(ctx.getChestRollFlag())}
 * (bytecode {@code ChestContextDTCode$1}). {@code openChest} ne posait PAS le flag sur le contexte →
 * {@code getChestRollFlag()==null} → {@code getCount(null)==0} → {@code PreviousRolls(0)} TOUJOURS vrai → le
 * héros truqué du 1ᵉʳ tirage rendu à CHAQUE ouverture. Fix : poser
 * {@code ctx.setChestRollFlag(ChestHelper.getChestFlag(type, hasBulkBonus))} (+ {@code setIsPaidRoll}) avant le
 * roll — exactement le flag qu'incrémente {@code updateChestRollCounters} (déjà persisté par {@code resyncCounts}).
 *
 * <p>Ce test ouvre le MÊME coffre 12× (1ᵉʳ gratuit puis payants) et exige que les récompenses SE DIVERSIFIENT
 * (avant le fix : 1 seule valeur distincte ; après : plusieurs). Vérifie GOLD (diamond crate) ET SILVER (gold crate).
 */
public final class ChestRollRigTest {

  static com.perblue.heroes.game.objects.User bind(ServerUser su) {
    BootData bd = su.bootData();
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "cc");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "cc");
    ServerContext.bind(u, iu); return u;
  }

  static String dropStr(LootResults lr) {
    if (lr.lootDrops == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (Object o : lr.lootDrops) { RewardDrop d = (RewardDrop) o; sb.append(d.resourceType + "/" + d.itemType + "x" + d.quantity + " "); }
    return sb.toString().trim();
  }

  static void check(ServerUser su, BootData bd, ChestType type, int n) throws Exception {
    Map<String,Integer> hist = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      var u = bind(su);
      int cost = ChestHelper.getPurchaseCost(u, type, 1, SpecialEventSnapshot.NONE);
      boolean free = ChestHelper.hasFreeChest(u, type, null, 1);
      BuyChests m = new BuyChests(); m.chestType = type; m.count = 1; m.cost = free ? 0 : cost;
      LootResults lr = su.openChest(m);
      hist.merge(dropStr(lr), 1, Integer::sum);
    }
    System.out.println("[rig] " + type + " : " + n + " ouvertures → " + hist.size() + " drops DISTINCTS " + hist);
    if (hist.size() <= 1)
      throw new AssertionError(type + " : " + n + " ouvertures ont donné " + hist.size()
          + " récompense distincte (rig de 1ᵉʳ tirage figé → bug #2 non corrigé) : " + hist);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    BootData bd = su.bootData();
    bd.userInfo.basicInfo.teamLevel = 60;
    bd.userInfo.diamonds = 1_000_000;
    var u0 = bind(su); u0.setResource(ResourceType.GOLD, 500_000_000, "setup"); su.userInfoWire();

    check(su, bd, ChestType.GOLD, 12);     // diamond crate
    check(su, bd, ChestType.SILVER, 12);   // gold crate

    System.out.println("[rig] OK — le rig de 1ᵉʳ tirage n'est plus figé : les coffres se diversifient (bug #2 corrigé)");
  }
}
