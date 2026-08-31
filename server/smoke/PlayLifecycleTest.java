import dhlauncher.PlayManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Smoke ISOLÉ (C2b) — cycle de vie de {@link PlayManager} (endpoint {@code /play}) : lance un bundle CLIENT stub
 * (run.sh = {@code tail -f /dev/null}, reste vivant sans CPU), vérifie running/status (serveur, userID), idempotence,
 * arrêt, et le rejet d'un bundle sans run script. N'exécute PAS le vrai client (couvert par la vérif EN JEU) — teste
 * la GLUE de lancement. Game-free (dhlauncher pur).
 */
public final class PlayLifecycleTest {
    static int ok = 0, fail = 0;
    static void ok(boolean c, String m) { if (c) ok++; else { fail++; System.out.println("  ✗ " + m); } }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("playbundle").toFile();
        File run = new File(dir, "run.sh");
        Files.writeString(run.toPath(), "#!/usr/bin/env bash\nexec tail -f /dev/null\n");
        run.setExecutable(true, false);

        PlayManager pm = new PlayManager(dir.getParent());
        String s1 = pm.start(dir.getPath(), "127.0.0.1:8080", 42L, false);
        ok(s1.contains("\"running\":true"), "start → running");
        ok(pm.isRunning(), "isRunning true après start");
        ok(pm.status().contains("\"server\":\"127.0.0.1:8080\""), "status porte le serveur (DH_SERVER)");
        ok(pm.status().contains("\"userID\":42"), "status porte le userID");

        String s2 = pm.start(dir.getPath(), "autre:1", 1L, false);   // déjà lancé → idempotent
        ok(s2.contains("\"running\":true") && pm.status().contains("\"server\":\"127.0.0.1:8080\""),
                "start idempotent (ne relance pas, garde le 1er serveur)");

        String st = pm.stop();
        ok(st.contains("\"running\":false"), "stop → running false");
        ok(!pm.isRunning(), "isRunning false après stop");

        boolean threw = false;
        try { pm.start(Files.createTempDirectory("empty").toString(), "x:1", 0L, false); }
        catch (IOException e) { threw = true; }
        ok(threw, "bundle sans run.sh → IOException (pas de faux démarrage)");

        System.out.println("PlayLifecycleTest : " + ok + " ok, " + fail + " échec(s)");
        if (fail > 0) System.exit(1);
    }
}
