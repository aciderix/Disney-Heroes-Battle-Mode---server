import dhlauncher.LauncherDaemon;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

/**
 * C2a-4 (génération depuis l'APK) — le launcher-core orchestre le pipeline reproductible sur un APK fourni, vers un
 * dossier de SORTIE choisi. Vérifie la CIBLE {@code server} avec l'étape LÉGÈRE (extraction des données {@code .tab},
 * unzip, sans réseau) → produit de VRAIS fichiers depuis le VRAI APK, sans écraser le {@code game-data} du projet.
 * Vérifie aussi le refus HONNÊTE des cibles pas encore câblées ({@code client}) et l'APK introuvable.
 */
public final class BuildDataGenTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        String root = System.getProperty("dh.launcher.projectdir", System.getProperty("user.dir", "."));
        String apk = new File(root, "game/disney-heroes-12.1.0.apk").getPath();
        File out = Files.createTempDirectory("dh-genserver").toFile();

        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // APK introuvable → FAILED honnête
            ok(post(http, base + "/build/start", "apkPath=/nope.apk&target=server").contains("apk-introuvable"),
               "APK introuvable → FAILED");

            // cible non encore câblée → refus honnête (pas de faux succès)
            String cl = post(http, base + "/build/start", "apkPath=" + enc(apk) + "&target=client");
            ok(cl.contains("\"state\":\"FAILED\"") && cl.contains("venir"), "cible client → refus honnête (incrément à venir)");

            // cible SERVER, extraction réelle vers le dossier de sortie (pkg=false = données seules, rapide ;
            // le packaging complet du bundle est vérifié par ServerBundleTest, qui LANCE le bundle hors dev).
            String st = post(http, base + "/build/start", "apkPath=" + enc(apk) + "&target=server&pkg=false&outDir=" + enc(out.getPath()));
            ok(st.contains("\"target\":\"SERVER\""), "start SERVER accepté");

            boolean done = false;
            long deadline = System.currentTimeMillis() + 120_000L;
            while (System.currentTimeMillis() < deadline) {
                String s = post(http, base + "/build/status", "");
                if (s.contains("\"state\":\"DONE\"")) { done = true; break; }
                if (s.contains("\"state\":\"FAILED\"")) { System.out.println("  build FAILED: " + s); break; }
                Thread.sleep(500);
            }
            ok(done, "build SERVER terminé (state=DONE)");

            File stats = new File(out, "game-data/stats");
            File[] tabs = stats.listFiles((dir, n) -> n.endsWith(".tab") || n.endsWith(".tabb"));
            ok(stats.isDirectory() && tabs != null && tabs.length > 50,
               "données .tab GÉNÉRÉES dans le dossier de sortie (" + (tabs == null ? 0 : tabs.length) + " fichiers)");
        } finally {
            d.stop();
            try { deleteRec(out); } catch (Exception ignore) {}
        }

        System.out.println("BuildDataGenTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static String post(HttpClient h, String url, String body) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
