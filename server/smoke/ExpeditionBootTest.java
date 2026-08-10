import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * EXPEDITION (#72) incrément 1 — BOOT / RENDU. Le handler {@code GetExpedition} → {@code GetExpeditionResponse}
 * (patron GetSurge) doit renvoyer un état FRAIS wire-sûr : {@code currentExpedition=null} (sélection de difficulté),
 * {@code expeditionID} persisté, {@code weeklyWardInfo} non-null. Vérifie via {@code ServerExpedition.response} +
 * round-trip wire ({@code WireCheck}).
 */
public final class ExpeditionBootTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[expedition-boot] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4901L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;

    GetExpeditionResponse r = ServerExpedition.response(su);
    check(r != null, "réponse non-null");
    check(r.currentExpedition != null, "currentExpedition non-null (le codec l'écrit sans garde)");
    check(r.currentExpedition.difficulty == 0 && r.currentExpedition.nodesDefeated == 0,
        "run vide = aucun run actif (état frais → sélection de difficulté)");
    check(r.weeklyWardInfo != null, "weeklyWardInfo non-null (sinon NPE client)");
    check(r.weeklyWardInfo.currentWards != null, "currentWards non-null");
    check(r.expeditionID == 0L, "expeditionID frais = 0 (persisté)");
    // Round-trip wire : la réponse doit s'écrire+relire sans exception (défaut nº3).
    WireCheck.assertRoundTrips(r);
    System.out.println("[expedition-boot] GetExpeditionResponse OK (expeditionID=" + r.expeditionID
        + ", run=aucun, weeklyWardInfo non-null) — round-trip wire vert");

    System.out.println("[expedition-boot] OK — boot/rendu Expédition (#72 incrément 1) via le code du jeu");
  }
}
