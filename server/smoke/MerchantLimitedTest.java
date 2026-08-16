import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.MerchantHelper;
import dhserver.*;
import java.util.*;

/**
 * MERCHANT (#72) incrément 4 — marchand LIMITÉ dans le temps (BLACK_MARKET). Le serveur ré-exécute la logique de
 * DÉCOUVERTE du jeu ({@code MerchantHelper.checkForFoundMerchant}, stamina + RNG) qui pose la fenêtre
 * {@code expiration}=now+durée (1 h) + {@code cooldownEnd}=+cooldown (20 h) depuis les {@code .tab}, puis génère le
 * stock. Persistance round-trip. Zéro invention (§4) : durées/cooldown = données du jeu.
 */
public final class MerchantLimitedTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[merchant-limited] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8400L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    MerchantType T = MerchantType.BLACK_MARKET;
    check(MerchantHelper.isMerchantUnlocked(T, su.gameUser()), "BLACK_MARKET débloqué (TL)");
    check(!MerchantHelper.isAvailable(su.gameUser(), T), "BLACK_MARKET indisponible au départ (non découvert)");
    check(su.merchantDataPersisted(T) == null, "aucun stock avant découverte");

    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long dur = MerchantHelper.getLimitedTimeMerchantDuration(T);
    long cd  = MerchantHelper.getLimitedTimeMerchantCooldown(T);

    // --- Découverte (logique du jeu) ---
    MerchantData data = su.discoverLimitedMerchant(T);
    check(data != null, "BLACK_MARKET découvert (MerchantData non null)");
    check(MerchantHelper.isAvailable(su.gameUser(), T), "BLACK_MARKET DISPONIBLE après découverte");
    check(data.inventory != null && !data.inventory.isEmpty(), "stock généré non vide (" + data.inventory.size() + " objets)");
    // Fenêtre limitée : expiration ~ now + durée (1 h) ; cooldown ~ expiration + cooldown (20 h).
    check(data.expiration > now, "expiration dans le futur");
    check(Math.abs(data.expiration - (now + dur)) < 60_000L, "expiration ≈ now + durée (" + dur + "ms) : " + (data.expiration - now));
    check(Math.abs(data.cooldownEnd - (data.expiration + cd)) < 60_000L, "cooldownEnd ≈ expiration + cooldown (" + cd + "ms)");
    System.out.println("[merchant-limited] BLACK_MARKET découvert : " + data.inventory.size()
        + " objets ; fenêtre " + (dur / 3600000.0) + "h, cooldown " + (cd / 3600000.0) + "h ✔");

    // --- Persistance round-trip wire + DB : fenêtre + stock ---
    ServerUser rl = ServerUser.fromWire(8400L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    MerchantData rd = rl.merchantDataPersisted(T);
    check(rd != null && rd.inventory.size() == data.inventory.size(), "stock survit au round-trip wire");
    check(rd.expiration == data.expiration && rd.cooldownEnd == data.cooldownEnd, "fenêtre (expiration/cooldown) survit au round-trip wire");
    check(MerchantHelper.isAvailable(rl.gameUser(), T), "toujours DISPONIBLE après round-trip wire");

    java.io.File db = java.io.File.createTempFile("merchlim", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8400L, 1);
    MerchantData dbd = fromDb.merchantDataPersisted(T);
    check(dbd != null && dbd.inventory.size() == data.inventory.size() && dbd.expiration == data.expiration,
        "stock + fenêtre persistés en DB");
    check(MerchantHelper.isAvailable(fromDb.gameUser(), T), "toujours DISPONIBLE après reload DB");
    store.close(); db.delete();
    System.out.println("[merchant-limited] persistance (wire + DB) fenêtre + stock ✔");

    System.out.println("[merchant-limited] OK — découverte marchand limité (fenêtre .tab + stock) + persistance (headless).");
  }
}
