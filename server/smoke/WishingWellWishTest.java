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
 * <p>RAMPE DE PITY : la règle (par drop : jackpot → reset ; stone → jackpot*=MULT_X, heroChips=base ; sinon →
 * jackpot*=MULT_Y, heroChips*=MULT_Z) est TRANSCRITE FIDÈLEMENT de {@code WishingWellChestResultWindow
 * .reachedDestination} (seule copie de la règle dans le jar, classe GL non instanciable headless) ; les VALEURS
 * viennent des .tab (WeightConstants). Persistée via {@code ChestHelper.updateWishingWellWeights}. On vérifie la
 * DIRECTION (monte hors jackpot), la CONTINUITÉ entre tirages (persistance) et l'accumulation au-dessus de la base.
 */
public final class WishingWellWishTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[wish] " + m); }

  static boolean sawRamp = false;         // au moins un tirage a fait MONTER la pity jackpot
  static boolean sawReset = false;         // au moins un tirage a réinitialisé (jackpot ou stone) la pity hero-chips
  static float peakJackpot = 0f;

  static int rollBatch(ServerUser su, int n, String heroToken) {
    BuyChests m = new BuyChests();
    m.chestType = ChestType.WISH; m.count = 1; m.cost = 500;
    int heroNamed = 0;
    float prevNewJ = Float.NaN, prevNewHC = Float.NaN;
    for (int i = 0; i < n; i++) {
      LootResults lr = su.openChest(m);
      check(lr.lootDrops != null && !lr.lootDrops.isEmpty(), "un souhait produit toujours au moins un drop");
      // CONTINUITÉ (persistance de la pity entre tirages) : le old de ce tirage == le new du précédent.
      if (!Float.isNaN(prevNewJ)) {
        check(Math.abs(lr.oldWishJackpotWeight - prevNewJ) < 1e-4f,
            "pity jackpot persiste entre tirages (old=" + lr.oldWishJackpotWeight + " prevNew=" + prevNewJ + ")");
        check(Math.abs(lr.oldWishHeroChipsWeight - prevNewHC) < 1e-4f, "pity hero-chips persiste entre tirages");
      }
      // RAMPE : détermine le type de drop et vérifie la DIRECTION de la mise à jour (règle du jeu).
      boolean anyJackpot = false, anyStone = false;
      for (Object o : lr.lootDrops) {
        RewardDrop d = (RewardDrop) o;
        if ((d.flags & 16) != 0) anyJackpot = true;
        if (com.perblue.heroes.game.data.item.ItemStats.getCategory(d.itemType)
            == com.perblue.heroes.game.data.item.ItemCategory.STONE) anyStone = true;
        if (d.itemType != null && d.itemType != ItemType.DEFAULT && d.itemType.name().contains(heroToken)) heroNamed++;
      }
      if (anyJackpot) { sawReset = true; check(lr.newWishJackpotWeight <= lr.oldWishJackpotWeight + 1e-4f, "jackpot → reset (pity jackpot ne monte pas)"); }
      else { check(lr.newWishJackpotWeight > lr.oldWishJackpotWeight, "non-jackpot → pity jackpot MONTE (" + lr.oldWishJackpotWeight + "→" + lr.newWishJackpotWeight + ")"); sawRamp = true; }
      if (anyStone && !anyJackpot) sawReset = true;   // stone → hero-chips remis à la base
      peakJackpot = Math.max(peakJackpot, lr.newWishJackpotWeight);
      prevNewJ = lr.newWishJackpotWeight; prevNewHC = lr.newWishHeroChipsWeight;
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
    int ralphNamed = rollBatch(su, 120, "RALPH");
    long diaAfter = su.bootData().userInfo.diamonds;
    check(ralphNamed > 0, "au moins un drop nommé RALPH sur 120 souhaits (biais cible) = " + ralphNamed);
    check(diaAfter < diaBefore, "les DIAMONDS ont été débités (" + diaBefore + "→" + diaAfter + ")");
    check(diaAfter == diaBefore - 120L * 500L, "débit EXACT = 120×500 (" + (diaBefore - diaAfter) + ")");
    System.out.println("[wish] 120 souhaits cible RALPH : " + ralphNamed + " drops RALPH ; DIAMONDS -" + (diaBefore - diaAfter));

    // --- RAMPE DE PITY (règle du jeu, transcrite de reachedDestination ; valeurs .tab) ---
    check(sawRamp, "la pity jackpot MONTE sur les tirages non-jackpot (rampe active)");
    check(peakJackpot > 1.0f, "la pity jackpot a dépassé la base JACKPOT_BASE=1.0 (accumulation) : peak=" + peakJackpot);
    // Les poids de pity persistent (write-through individualUserExtra) : round-trip wire.
    float persistedJ = su.gameUser().getIndividual().getWishingWellJackpotWeight();
    ServerUser rlw = ServerUser.fromWire(7002L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(Math.abs(rlw.gameUser().getIndividual().getWishingWellJackpotWeight() - persistedJ) < 1e-4f,
        "la pity jackpot survit au round-trip wire (=" + persistedJ + ")");
    System.out.println("[wish] rampe de pity : jackpot base 1.0 → peak " + peakJackpot + " (persistée) ✔");

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
    int vanNamed = rollBatch(su, 120, "VANELLOPE");
    check(vanNamed > 0, "au moins un drop nommé VANELLOPE après bascule (biais suit la cible) = " + vanNamed);
    System.out.println("[wish] 120 souhaits cible VANELLOPE : " + vanNamed + " drops VANELLOPE (le biais suit la cible) ✔");

    System.out.println("[wish] OK — souhait biaisé cible + crédit shards + débit diamants + RAMPE de pity + persistance (headless).");
  }
}
