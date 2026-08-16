import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.WishingWellHelper;
import dhserver.*;
import java.util.*;

/**
 * WISHING_WELL (#72) incrément 1 — fixe le HÉROS CIBLE ({@code Action SET_WISHING_WELL_TARGET_HERO}). Le serveur
 * ré-exécute {@code WishingWellHelper.setTargetHero} : valide l'éligibilité (héros non éligible → aucun effet =
 * anti-triche), pose {@code wishingWellHero} (write-through) + poids/cooldown. Persistance round-trip wire + DB.
 */
public final class WishingWellTargetTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[wishing-well] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9985L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    check(WishingWellHelper.isUnlocked(su.gameUser()), "puits débloqué");

    EnumSet<?> elig = WishingWellHelper.getAllEligibleHeroes(su.gameUser());
    check(elig.contains(UnitType.RALPH) && elig.contains(UnitType.VANELLOPE), "RALPH & VANELLOPE éligibles");
    // Un héros NON éligible (pour l'anti-triche).
    UnitType ineligible = null;
    for (UnitType t : UnitType.values()) { if (t != UnitType.DEFAULT && !elig.contains(t)) { ineligible = t; break; } }
    check(ineligible != null, "un héros non éligible trouvé (=" + ineligible + ")");

    check(su.gameUser().getIndividual().getWishingWellHero() == UnitType.DEFAULT, "cible initiale = DEFAULT");

    // --- Pose la cible RALPH ---
    check(su.applySetWishingWellTarget(UnitType.RALPH), "cible RALPH posée");
    check(su.gameUser().getIndividual().getWishingWellHero() == UnitType.RALPH, "cible = RALPH (=" + su.gameUser().getIndividual().getWishingWellHero() + ")");
    System.out.println("[wishing-well] cible DEFAULT→RALPH");

    // --- Change la cible → VANELLOPE ---
    check(su.applySetWishingWellTarget(UnitType.VANELLOPE), "cible VANELLOPE posée");
    check(su.gameUser().getIndividual().getWishingWellHero() == UnitType.VANELLOPE, "cible = VANELLOPE");
    System.out.println("[wishing-well] cible RALPH→VANELLOPE");

    // --- Anti-triche : héros non éligible → cible inchangée ---
    check(!su.applySetWishingWellTarget(ineligible), "cible non éligible REFUSÉE (" + ineligible + ")");
    check(su.gameUser().getIndividual().getWishingWellHero() == UnitType.VANELLOPE, "cible reste VANELLOPE après refus");
    System.out.println("[wishing-well] anti-triche héros non éligible (" + ineligible + ") : refusé, cible inchangée ✔");

    // --- Persistance : write-through → round-trip wire + DB ---
    ServerUser rl = ServerUser.fromWire(9985L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getIndividual().getWishingWellHero() == UnitType.VANELLOPE, "cible survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("wish", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9985L, 1);
    check(fromDb != null && fromDb.gameUser().getIndividual().getWishingWellHero() == UnitType.VANELLOPE,
        "cible persistée en DB (=VANELLOPE)");
    store.close(); db.delete();
    System.out.println("[wishing-well] persistance (wire + DB) de la cible ✔");

    System.out.println("[wishing-well] OK — cible du puits + anti-triche (non éligible) + persistance (headless).");
  }
}
