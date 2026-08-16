import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.CollectionHelper;
import dhserver.*;

/**
 * COLLECTIONS (#72) incrément 3 — ACHAT d'AVATAR de collection (« mastery shop », {@code Action
 * BUY_COLLECTION_AVATAR}). Le serveur ré-exécute {@code CollectionHelper.buyCollectionAvatar} : gate
 * {@code COLLECTION_AVATAR_LOCKED} (niveau cumulé de collection requis) + débit {@code MASTERY_TOKENS}
 * ({@code getAvatarCost}) + don de l'avatar (item). Sink des MASTERY_TOKENS gagnés par les claims (incr. 1).
 *
 * <p>Fait (sonde) : {@code COLLECTION_AVATAR_DAMAGE} coûte 100 MASTERY_TOKENS et requiert cumulativeLevel(DAMAGE) >= 8.
 * {@code getCumulativeCollectionLevel = Σ highestClaimed(tier)} → on pose highestClaimed(BRONZE)=8 (debug).
 */
public final class CollectionAvatarTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[avatar] " + m); }
  static final ItemType AVATAR = ItemType.valueOf("COLLECTION_AVATAR_DAMAGE");   // fnum (valeur data-définie)
  static final CollectionType COL = CollectionType.DAMAGE;

  /** Monte le niveau cumulé de collection (Σ highestClaimed par tier ; max 3/tier) à >= 8 : BRONZE/SILVER/GOLD = 3 → 9. */
  static void raiseCumLevel(ServerUser su) {
    for (CollectionTier t : new CollectionTier[]{CollectionTier.BRONZE, CollectionTier.SILVER, CollectionTier.GOLD})
      for (int lvl = 1; lvl <= 3; lvl++)
        CollectionHelper.debugSetHighestClaimedLevel(su.gameUser(), COL, t, lvl);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ===== Succès : niveau requis atteint + tokens → achat, débit exact, avatar possédé =====
    ServerUser su = ServerUser.newPlayer(9970L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 250;
    int cost = CollectionHelper.getAvatarCost(AVATAR);
    check(cost > 0, "coût avatar > 0 (=" + cost + ")");
    // Atteint le niveau cumulé requis (highest claimed BRONZE = 8 → cumLevel(DAMAGE)=8).
    raiseCumLevel(su);
    su.gameUser().setResource(ResourceType.MASTERY_TOKENS, cost + 50, "test");
    long tok0 = su.gameUser().getResource(ResourceType.MASTERY_TOKENS);
    int owned0 = su.gameUser().getItemAmount(AVATAR);

    check(su.applyBuyCollectionAvatar(AVATAR), "achat avatar appliqué");
    long tok1 = su.gameUser().getResource(ResourceType.MASTERY_TOKENS);
    int owned1 = su.gameUser().getItemAmount(AVATAR);
    check(tok0 - tok1 == cost, "MASTERY_TOKENS débités du coût exact (" + cost + ", vu " + (tok0 - tok1) + ")");
    check(owned1 == owned0 + 1, "avatar possédé (+1) (=" + owned1 + ")");
    System.out.println("[avatar] achat " + AVATAR + " : MASTERY_TOKENS -" + (tok0 - tok1) + ", possédé=" + owned1);

    // Persistance : items + ressources write-through → round-trip wire + DB.
    ServerUser rl = ServerUser.fromWire(9970L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getItemAmount(AVATAR) == owned1, "avatar survit au round-trip wire");
    check(rl.gameUser().getResource(ResourceType.MASTERY_TOKENS) == tok1, "tokens survivent au round-trip wire");
    java.io.File db = java.io.File.createTempFile("avatar", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9970L, 1);
    check(fromDb != null && fromDb.gameUser().getItemAmount(AVATAR) == owned1, "avatar persisté en DB");
    store.close(); db.delete();
    System.out.println("[avatar] persistance (wire + DB) ✔");

    // ===== Anti-triche 1 : niveau de collection non atteint → COLLECTION_AVATAR_LOCKED, aucun débit =====
    ServerUser locked = ServerUser.newPlayer(9971L, 1);
    locked.bootData().userInfo.basicInfo.teamLevel = 250;
    locked.gameUser().setResource(ResourceType.MASTERY_TOKENS, cost + 50, "test");   // assez de tokens
    long lt0 = locked.gameUser().getResource(ResourceType.MASTERY_TOKENS);
    check(!locked.applyBuyCollectionAvatar(AVATAR), "achat REFUSÉ (avatar verrouillé, niveau non atteint)");
    check(locked.gameUser().getResource(ResourceType.MASTERY_TOKENS) == lt0, "aucun token débité au refus (verrouillé)");
    check(locked.gameUser().getItemAmount(AVATAR) == 0, "aucun avatar donné au refus (verrouillé)");
    System.out.println("[avatar] anti-triche verrouillé (niveau non atteint) : refusé, aucun débit ✔");

    // ===== Anti-triche 2 : niveau atteint mais MASTERY_TOKENS insuffisants → refus, aucun avatar =====
    ServerUser poor = ServerUser.newPlayer(9972L, 1);
    poor.bootData().userInfo.basicInfo.teamLevel = 250;
    raiseCumLevel(poor);
    poor.gameUser().setResource(ResourceType.MASTERY_TOKENS, cost - 1, "test");       // 1 de moins que le coût
    check(!poor.applyBuyCollectionAvatar(AVATAR), "achat REFUSÉ (tokens insuffisants)");
    check(poor.gameUser().getItemAmount(AVATAR) == 0, "aucun avatar donné au refus (tokens)");
    System.out.println("[avatar] anti-triche tokens insuffisants : refusé, aucun avatar ✔");

    System.out.println("[avatar] OK — achat d'avatar de collection + anti-triche (verrou/tokens) + persistance (headless).");
  }
}
