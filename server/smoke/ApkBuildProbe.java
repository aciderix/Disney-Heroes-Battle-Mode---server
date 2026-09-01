import dhlauncher.LauncherDaemon;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * PATCH APK (brique 4b) — SONDE DEV (réseau + outils Android, EXCLUE de regression). Prouve que la cible {@code apk} du
 * launcher produit un APK REDIRIGÉ + RE-SIGNÉ de bout en bout : POST /build/start target=apk {serverHost,serverPort} →
 * poll /build/status → DONE + fichier APK présent. Nécessite {@code game/disney-heroes-12.1.0.apk} + accès aux jars
 * baksmali/smali/uber-apk-signer (téléchargés par patch_apk.sh). projectDir = racine du dépôt.
 */
public final class ApkBuildProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("dh.launcher.projectdir", new File(".").getAbsoluteFile().getParentFile() == null
                ? "." : System.getProperty("user.dir", "."));
        String apk = "game/disney-heroes-12.1.0.apk";
        if (!new File(apk).isFile()) { System.out.println("ApkBuildProbe : APK absent — sonde non exécutable"); return; }
        String outDir = new File(System.getProperty("java.io.tmpdir"), "dh-apkprobe").getPath();

        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            String host = "10.0.0.5"; int port = 8080;
            String r = post(http, base + "/build/start",
                    "apkPath=" + enc(apk) + "&target=apk&apkMode=picker&outDir=" + enc(outDir));
            System.out.println("start → " + r);
            String state = "RUNNING";
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 240_000L) {
                String st = get(http, base + "/build/status");
                if (st.contains("\"state\":\"DONE\"")) { state = "DONE"; break; }
                if (st.contains("\"state\":\"FAILED\"")) { state = "FAILED: " + st; break; }
                for (int j = 0; j < 30_000_000; j++) { /* busy-wait ~court */ }
            }
            File outApk = new File(outDir, "dh-picker.apk");
            boolean ok = "DONE".equals(state) && outApk.isFile() && outApk.length() > 1_000_000;
            System.out.println("état=" + state + " · apk=" + outApk.getPath() + " (" + (outApk.isFile() ? outApk.length() : 0) + " o)");
            System.out.println("ApkBuildProbe : " + (ok ? "TOUT VERT ✅ (APK produit par le launcher)" : "ÉCHEC ✗"));
        } finally { d.stop(); }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String get(HttpClient h, String url) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static String post(HttpClient h, String url, String body) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
