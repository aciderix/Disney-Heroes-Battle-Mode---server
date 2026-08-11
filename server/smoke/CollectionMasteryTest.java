import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.CollectionHelper;
import dhserver.*;
import java.util.*;

/**
 * COLLECTIONS (#72) incrément 2 — MAÎTRISE de combat. Un combat de CAMPAGNE gagné doit accumuler la maîtrise des
 * héros attaquants (mode {@code CAMPAIGN}). Le serveur, après {@code CampaignHelper.recordOutcome} (qui n'accumule
 * PAS la maîtrise, contrairement à Surge/Invasion), exécute {@code CollectionHelper.recordHeroMastery(user, attackers,
 * mode)} sur WIN (miroir du client). Persistance write-through ({@code individualUserExtra.collectionMasteryUses}).
 * Une DÉFAITE n'accumule rien.
 */
public final class CollectionMasteryTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[mastery] " + m); }
  static final UnitType HERO = UnitType.ELASTIGIRL;   // appartient à la collection DAMAGE
  static final CollectionType COL = CollectionType.DAMAGE;
  static final CollectionTier T = CollectionTier.BRONZE;

  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.RED; s.survived = true; s.power = 100; s.startingHP = 1000; s.startingEnergy = 0;
    return s;
  }
  static CampaignAttack attack(CombatOutcome outcome, UnitType... heroes) {
    AttackLineupSummary ls = new AttackLineupSummary();
    ls.units = new ArrayList<>();
    for (UnitType h : heroes) ls.units.add(unit(h));
    AttackBase base = new AttackBase();
    base.attackers = new ArrayList<>(Arrays.asList(ls));
    base.defenders = new ArrayList<>();
    base.outcome = outcome; base.stars = 3;
    CampaignAttack m = new CampaignAttack();
    m.base = base; m.campaignType = CampaignType.NORMAL; m.chapter = 1; m.level = 1;
    m.lootEarned = new ArrayList<>(); m.memoryChanges = new ArrayList<>(); m.stagesCleared = 1;
    return m;
  }
  static int mastery(ServerUser su) {
    return su.gameUser().getIndividual().getCollectionHeroMasteryUses(COL, T, HERO);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9950L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 250;
    su.grantHero(HERO, Rarity.RED, 200, 6);            // 6★ → éligible à la maîtrise (>= MIN_HERO_STARS_REQUIRED)
    su.grantHero(UnitType.RALPH, Rarity.RED, 200, 6);

    int m0 = mastery(su);
    check(m0 == 0, "maîtrise ELASTIGIRL/DAMAGE/BRONZE = 0 au départ (=" + m0 + ")");

    // --- Combat GAGNÉ → maîtrise +1 ---
    su.recordCampaignAttack(attack(CombatOutcome.WIN, HERO, UnitType.RALPH));
    int m1 = mastery(su);
    check(m1 == m0 + 1, "maîtrise +1 après une victoire (=" + m1 + ")");
    System.out.println("[mastery] WIN campagne : maîtrise ELASTIGIRL/DAMAGE/BRONZE " + m0 + "→" + m1);

    // --- 2ᵉ victoire → +1 encore (cumul) ---
    su.recordCampaignAttack(attack(CombatOutcome.WIN, HERO, UnitType.RALPH));
    check(mastery(su) == m0 + 2, "maîtrise cumule sur 2 victoires (=" + mastery(su) + ")");

    // --- DÉFAITE → aucune accumulation ---
    int mBeforeLoss = mastery(su);
    su.recordCampaignAttack(attack(CombatOutcome.LOSS, HERO, UnitType.RALPH));
    check(mastery(su) == mBeforeLoss, "défaite : aucune maîtrise accumulée (=" + mastery(su) + ")");
    System.out.println("[mastery] défaite : aucune accumulation ✔");

    // --- Persistance : write-through individualUserExtra.collectionMasteryUses → round-trip wire + DB ---
    int mFinal = mastery(su);
    ServerUser rl = ServerUser.fromWire(9950L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getIndividual().getCollectionHeroMasteryUses(COL, T, HERO) == mFinal,
        "maîtrise survit au round-trip wire (=" + mFinal + ")");
    java.io.File db = java.io.File.createTempFile("mastery", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9950L, 1);
    check(fromDb != null && fromDb.gameUser().getIndividual().getCollectionHeroMasteryUses(COL, T, HERO) == mFinal,
        "maîtrise persistée en DB (=" + mFinal + ")");
    store.close(); db.delete();
    System.out.println("[mastery] persistance (wire + DB) de la maîtrise ✔");

    System.out.println("[mastery] OK — maîtrise de combat (campagne WIN) accumulée + persistée, défaite exempte (headless).");
  }
}
