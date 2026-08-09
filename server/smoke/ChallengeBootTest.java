import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * CHALLENGES (#72) incrément 1 — LIVRAISON BootData. Le client peuple son ClientUserChallengeData depuis
 * {@code BootData.userChallengeDataExtra} pour rendre l'écran « Sticker Challenges » (TL20). Vérifie que le serveur
 * livre l'état PAR JOUEUR (userID correct, conteneurs non-null wire-sûrs) et que historicWeeklyChallenges est non-null.
 */
public final class ChallengeBootTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[challenge-boot] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(42L, 1);
    BootData bd = su.bootData();

    UserChallengeDataExtra d = bd.userChallengeDataExtra;
    check(d != null, "BootData.userChallengeDataExtra ne doit pas être null (écran vide sinon)");
    check(d.userID == 42L, "userID doit être celui du joueur, obtenu " + d.userID);
    check(d.slots != null && d.completionTime != null && d.purchaseTime != null && d.completedChapters != null,
        "conteneurs non-null (wire-sûr, défaut nº3)");
    check(bd.historicWeeklyChallenges != null, "historicWeeklyChallenges non-null (StickerHelper.getHistoricChallenges)");

    // Round-trip wire du message de challenge (défaut nº3) et de tout le BootData (le client le décode au boot).
    WireCheck.assertRoundTrips(d);
    WireCheck.assertRoundTrips(bd);

    System.out.println("[challenge-boot] OK — userChallengeDataExtra livré (userID=" + d.userID
        + ", slots=" + d.slots.size() + "), historic non-null, round-trip BootData — #72 CHALLENGES incrément 1");
  }
}
