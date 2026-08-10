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

    // --- Incrément 2 : ResetExpedition génère le run (defenders) + persiste ---
    grantRoster(su);   // le bot n'en a pas besoin, mais garde le compte cohérent
    ExpeditionRunData run = ServerExpedition.resetRun(su, 1, new java.util.ArrayList<>(), true);
    check(run != null, "resetRun (firstEver) accepté");
    check(run.difficulty == 1, "difficulté = 1");
    check(run.defenders != null && run.defenders.size() >= 10, "run généré avec des nœuds (defenders="
        + (run.defenders == null ? 0 : run.defenders.size()) + ")");
    DefenderData n0 = (DefenderData) run.defenders.get(0);
    check(n0.lineup != null && n0.lineup.size() == 5, "nœud 0 = lineup de 5 ennemis (="
        + (n0.lineup == null ? 0 : n0.lineup.size()) + ")");
    GetExpeditionResponse rr = ServerExpedition.resetResponse(su, run);
    check(rr.wasReset, "resetResponse.wasReset = true");
    check(rr.expeditionID == 1L, "nouvel expeditionID = 1");
    WireCheck.assertRoundTrips(rr);
    System.out.println("[expedition-boot] ResetExpedition → run " + run.defenders.size()
        + " nœuds × 5 ennemis, expeditionID=" + rr.expeditionID + " — round-trip wire vert");

    // --- Persistance DB : le run survit au save/reload (blob `expedition`) ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-expedition-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4901L, 1);
    ExpeditionRunData run2 = rl.expeditionRunOrNull();
    check(run2 != null, "run persisté (blob expedition)");
    check(run2.difficulty == 1 && run2.defenders != null && run2.defenders.size() == run.defenders.size(),
        "run identique après reload DB (diff=" + run2.difficulty + ", nœuds=" + run2.defenders.size() + ")");
    check(rl.expeditionIDPersisted() == 1L, "expeditionID persisté = 1");
    store.close();
    System.out.println("[expedition-boot] persistance DB : run " + run2.defenders.size() + " nœuds relu OK");

    System.out.println("[expedition-boot] OK — boot + ResetExpedition (génération du run) + persistance (#72 incr. 1-2)");
  }

  static void grantRoster(ServerUser su) {
    try {
      su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
      su.grantHero(UnitType.VANELLOPE, Rarity.ORANGE, 60, 5);
    } catch (Throwable ignore) {}
  }
}
