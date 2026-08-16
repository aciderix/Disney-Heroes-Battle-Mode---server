import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.ChestHelper;
import com.perblue.heroes.game.data.wishingwell.WishingWellDTContext;
import dhserver.*;
import java.util.*;

/**
 * WISHING_WELL (#72) incrément 2 — le SOUHAIT ({@code openChest(BuyChests{chestType=WISH})}). Le serveur ré-exécute
 * le tirage du jeu (§3) : {@code ChestStats.WISHING_WELL_DROPS} roulée avec un {@code WishingWellDTContext} (BIAISÉ
 * par le héros CIBLE {@code wishingWellHero} + poids de pity), plancher des poids ({@code checkMinWeights}), crédit
 * des shards ({@code giveChestRewards}), débit DIAMONDS, persistance round-trip.
 *
 * <p>GAP §4 (prouvé au bytecode) : la RAMPE de pity par tirage (nouveau poids après un souhait) est ABSENTE du jar
 * client → non réimplémentable sans l'inventer. On vérifie donc que les poids restent au PLANCHER (pas de rampe) =
 * comportement documenté, pas une invention.
 */
public final class WishingWellWishTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[wish] " + m); }

  static int rollBatch(ServerUser su, int n, String heroToken, int[] weightsOut) {
    BuyChests m = new BuyChests();
    m.chestType = ChestType.WISH; m.count = 1; m.cost = 500;
    int heroNamed = 0;
    for (int i = 0; i < n; i++) {
      LootResults lr = su.openChest(m);
      check(lr.lootDrops != null && !lr.lootDrops.isEmpty(), "un souhait produit toujours au moins un drop");
      // Poids PLANCHERÉS (checkMinWeights) et SANS rampe : new == old à chaque tirage (GAP §4 documenté).
      check(lr.newWishJackpotWeight == lr.oldWishJackpotWeight, "pas de rampe jackpot (new==old)");
      check(lr.newWishHeroChipsWeight == lr.oldWishHeroChipsWeight, "pas de rampe hero-chips (new==old)");
      check(lr.newWishJackpotWeight >= 1.0f, "jackpot >= plancher JACKPOT_BASE");
      check(lr.newWishHeroChipsWeight >= 1.0f, "hero-chips >= plancher HERO_CHIPS_BASE");
      for (Object o : lr.lootDrops) {
        RewardDrop d = (RewardDrop) o;
        if (d.itemType != null && d.itemType != ItemType.DEFAULT && d.itemType.name().contains(heroToken)) heroNamed++;
      }
    }
    return heroNamed;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(7002L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    su.bootData().userInfo.diamonds = 5_000_000;               // de quoi payer les souhaits (500 DIAMONDS/x1)

    // --- Cible RALPH → le contexte de tirage porte la cible (biais structurel, déterministe) ---
    check(su.applySetWishingWellTarget(UnitType.RALPH), "cible RALPH posée");
    WishingWellDTContext ctx = new WishingWellDTContext(su.gameUser(), new Random());
    check(ctx.getTargetHero() == UnitType.RALPH, "le contexte de tirage cible RALPH (=" + ctx.getTargetHero() + ")");

    long diaBefore = su.bootData().userInfo.diamonds;
    int ralphNamed = rollBatch(su, 120, "RALPH", null);
    long diaAfter = su.bootData().userInfo.diamonds;
    check(ralphNamed > 0, "au moins un drop nommé RALPH sur 120 souhaits (biais cible) = " + ralphNamed);
    check(diaAfter < diaBefore, "les DIAMONDS ont été débités (" + diaBefore + "→" + diaAfter + ")");
    check(diaAfter == diaBefore - 120L * 500L, "débit EXACT = 120×500 (" + (diaBefore - diaAfter) + ")");
    System.out.println("[wish] 120 souhaits cible RALPH : " + ralphNamed + " drops RALPH ; DIAMONDS -" + (diaBefore - diaAfter));

    // Un shard RALPH a bien été CRÉDITÉ au joueur (STONE_RALPH = shards de héros ; fnum → valueOf).
    ItemType STONE_RALPH = ItemType.valueOf("STONE_RALPH");
    int stoneRalph = su.gameUser().getItemAmount(STONE_RALPH);
    check(stoneRalph > 0, "STONE_RALPH crédité (=" + stoneRalph + ")");

    // --- Persistance : les shards crédités survivent au round-trip wire + DB ---
    ServerUser rl = ServerUser.fromWire(7002L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getItemAmount(STONE_RALPH) == stoneRalph, "STONE_RALPH survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("wish2", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(7002L, 1);
    check(fromDb != null && fromDb.gameUser().getItemAmount(STONE_RALPH) == stoneRalph,
        "STONE_RALPH persisté en DB (=" + stoneRalph + ")");
    store.close(); db.delete();
    System.out.println("[wish] persistance des shards (wire + DB) : STONE_RALPH=" + stoneRalph + " ✔");

    // --- Le biais SUIT la cible : bascule VANELLOPE → des drops VANELLOPE apparaissent ---
    check(su.applySetWishingWellTarget(UnitType.VANELLOPE), "cible VANELLOPE posée");
    WishingWellDTContext ctx2 = new WishingWellDTContext(su.gameUser(), new Random());
    check(ctx2.getTargetHero() == UnitType.VANELLOPE, "le contexte cible VANELLOPE");
    int vanNamed = rollBatch(su, 120, "VANELLOPE", null);
    check(vanNamed > 0, "au moins un drop nommé VANELLOPE après bascule (biais suit la cible) = " + vanNamed);
    System.out.println("[wish] 120 souhaits cible VANELLOPE : " + vanNamed + " drops VANELLOPE (le biais suit la cible) ✔");

    System.out.println("[wish] OK — souhait biaisé cible + crédit shards + débit diamants + persistance (headless).");
    System.out.println("[wish] NB (§4) : rampe de pity par tirage ABSENTE du jar → poids au plancher, sans rampe (GAP documenté).");
  }
}
