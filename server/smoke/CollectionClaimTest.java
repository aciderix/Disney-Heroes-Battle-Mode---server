import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.CollectionHelper;
import com.perblue.heroes.game.objects.IUser;
import dhserver.*;
import java.util.*;

/**
 * COLLECTIONS (#72) incrément 1 — CLAIM d'un palier ({@code Action CLAIM_COLLECTION_REWARDS}). Le serveur ré-exécute
 * {@code CollectionHelper.claimCollectionRewards} : anti-triche = levée du jeu si l'état n'est pas {@code CLAIMABLE} ;
 * sinon crédite les récompenses + monte le niveau réclamé (write-through {@code collectionsClaimed}). Round-trip
 * PROFOND (highest claimed par (type,tier) survit) + DB.
 *
 * <p>Setup (état CLAIMABLE) sur FAITS (sondes) : DAMAGE/BRONZE, niveau 1 requiert 5 héros « maîtrisés » ;
 * un héros est maîtrisé si {@code masteryUses >= getNumUsesRequiredForMastery(BRONZE,1)=20} ET étoiles >=
 * {@code getHeroStarsRequired(BRONZE)=3}. On grante 6 héros DAMAGE à 6★ + maîtrise 21 (setter direct du jeu).
 */
public final class CollectionClaimTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[collection] " + m); }
  static final CollectionType COL = CollectionType.DAMAGE;
  static final CollectionTier T = CollectionTier.BRONZE;

  static List<UnitType> setupClaimable(ServerUser su, int masteryPerHero) {
    IUser u = su.gameUser();
    List<UnitType> heroes = (List<UnitType>) CollectionHelper.getHeroesInCollection(u, COL);
    List<UnitType> g = new ArrayList<>();
    for (UnitType h : heroes) { if (g.size() >= 6) break; try { su.grantHero(h, Rarity.RED, 200, 6); g.add(h); } catch (Throwable t) {} }
    for (UnitType h : g) u.getIndividual().setCollectionHeroMasteryUses(COL, T, h, masteryPerHero);
    return g;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ===== Succès : état CLAIMABLE → claim → highest 0→1 + récompense + persistance =====
    ServerUser su = ServerUser.newPlayer(9900L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 250;
    setupClaimable(su, 21);                        // >= cap 20 → 6 héros maîtrisés (>=5 requis)
    check(CollectionHelper.getCollectionState(su.gameUser(), COL, T, 1).toString().equals("CLAIMABLE"),
        "état DAMAGE/BRONZE/lvl1 = CLAIMABLE au départ");
    check(su.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T) == 0, "highest claimed = 0 au départ");
    long tok0 = su.gameUser().getResource(ResourceType.MASTERY_TOKENS);

    check(su.applyClaimCollection(COL, T, 1), "claim niv.1 appliqué");
    int highest1 = su.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T);
    check(highest1 == 1, "highest claimed 0→1 (=" + highest1 + ")");
    long tok1 = su.gameUser().getResource(ResourceType.MASTERY_TOKENS);
    check(tok1 == tok0 + 8, "MASTERY_TOKENS +8 crédités (=" + (tok1 - tok0) + ")");
    System.out.println("[collection] claim DAMAGE/BRONZE/lvl1 : highest 0→1, MASTERY_TOKENS +" + (tok1 - tok0));

    // Anti-triche : re-claim du même niveau (désormais COMPLETED) → refusé, aucun changement.
    check(!su.applyClaimCollection(COL, T, 1), "re-claim niv.1 REFUSÉ (déjà réclamé)");
    check(su.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T) == 1, "highest inchangé après re-claim");
    check(su.gameUser().getResource(ResourceType.MASTERY_TOKENS) == tok1, "aucun token en double au re-claim");
    System.out.println("[collection] anti-triche re-claim (COMPLETED) : refusé, aucun double crédit ✔");

    // Persistance PROFONDE : round-trip wire + DB (highest claimed survit).
    ServerUser rl = ServerUser.fromWire(9900L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T) == 1,
        "highest claimed survit au round-trip wire (=1)");
    java.io.File db = java.io.File.createTempFile("coll", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9900L, 1);
    check(fromDb != null && fromDb.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T) == 1,
        "highest claimed persisté en DB (=1)");
    store.close(); db.delete();
    System.out.println("[collection] persistance highest claimed (wire + DB) ✔");

    // ===== Anti-triche : claim d'un niveau NON gagné (IN_PROGRESS) → refusé, aucun crédit =====
    ServerUser poor = ServerUser.newPlayer(9901L, 1);
    poor.bootData().userInfo.basicInfo.teamLevel = 250;
    setupClaimable(poor, 5);                       // 5 < cap 20 → aucun héros maîtrisé → IN_PROGRESS
    check(poor.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T) == 0, "poor : highest=0");
    long ptok = poor.gameUser().getResource(ResourceType.MASTERY_TOKENS);
    check(!poor.applyClaimCollection(COL, T, 1), "claim IN_PROGRESS REFUSÉ (anti-triche)");
    check(poor.gameUser().getIndividual().getHighestClaimedCollectionLevel(COL, T) == 0, "poor : highest reste 0 au refus");
    check(poor.gameUser().getResource(ResourceType.MASTERY_TOKENS) == ptok, "poor : aucun token crédité au refus");
    System.out.println("[collection] anti-triche claim non-gagné (IN_PROGRESS) : refusé, aucun crédit ✔");

    System.out.println("[collection] OK — claim de palier de collection + anti-triche + persistance (headless).");
  }
}
