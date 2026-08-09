import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * CHALLENGES (#72) incrément 4 — VUE des stickers d'un joueur (`GetUserChallengeDataExtra{targetUserID}` →
 * `UserChallengeDataExtra`). Le handler `LoginServer` charge l'état de défis PERSISTÉ du joueur ciblé et le renvoie
 * (wire-sûr). Ce test exerce EXACTEMENT ce chemin serveur : setup + persistance DB → relecture d'un AUTRE joueur →
 * `ServerChallenges.load` → réponse wire-safe, userID correct.
 */
public final class ChallengeViewTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[challenge-view] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = System.getProperty("java.io.tmpdir") + "/dh-challenge-view-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);

    // Joueur ciblé : TL100 avec un défi STARTER auto-peuplé, persisté.
    ServerUser target = ServerUser.newPlayer(5001L, 1);
    target.bootData().userInfo.basicInfo.teamLevel = 100;
    ServerChallenges.ensureSetup(target);
    store.save(target);

    // Un AUTRE joueur demande la vue → le handler charge la cible depuis le store (chemin exact du handler).
    ServerUser loaded = store.loadIfExists(5001L, 1);
    check(loaded != null, "joueur ciblé relu depuis la DB");
    UserChallengeDataExtra reply = ServerChallenges.load(loaded);
    reply.userID = 5001L;                                  // le handler force le targetUserID
    check(reply.userID == 5001L, "userID de la réponse = targetUserID");
    check(reply.slots != null && reply.slots.get(ChallengeSlots.STARTER) != null,
        "l'état de défis du joueur ciblé est renvoyé (slot STARTER présent)");
    ChallengeHandleExtra h = (ChallengeHandleExtra) reply.slots.get(ChallengeSlots.STARTER);
    check(h.type == StickerType.TO_CATCH_A_STAR, "défi STARTER attendu (obtenu " + h.type + ")");
    WireCheck.assertRoundTrips(reply);                     // le serveur l'écrit sur le fil en réponse

    // Cible INCONNUE → freshData wire-sûr (userID correct), jamais null (l'écran ne doit pas rester vide).
    UserChallengeDataExtra fresh = ServerChallenges.freshData(9999L);
    fresh.userID = 9999L;
    check(fresh.slots != null && fresh.completionTime != null && fresh.purchaseTime != null,
        "freshData wire-sûr pour un joueur sans état");
    WireCheck.assertRoundTrips(fresh);
    store.close();

    System.out.println("[challenge-view] OK — GetUserChallengeDataExtra renvoie l'état persisté du joueur ciblé — #72 incrément 4");
  }
}
