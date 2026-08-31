import dhlauncher.LauncherDaemon;
import dhserver.LoginServer;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;
import dhserver.admin.AdminService;
import dhserver.admin.AdminTls;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * ADMIN DISTANT TLS (chantier F) — l'{@code AdminService} sert en HTTPS (keystore fourni) ; le launcher se connecte à un
 * serveur DISTANT en épinglant l'empreinte SHA-256 du certificat auto-signé. Vérifie : TLS actif, épinglage BON → proxy
 * OK ; empreinte FAUSSE → rejet ; HTTPS SANS épinglage (cert auto-signé) → rejet. Keystore généré via {@code keytool}
 * (JDK de test). Référence {@code LoginServer} → auto-détecté ISOLÉ par {@code regression.sh}.
 */
public final class AdminTlsTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        ServerContext.init();
        // keystore PKCS12 auto-signé (via keytool du JDK)
        File ks = File.createTempFile("admintls", ".p12"); ks.delete();
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        int rc = new ProcessBuilder(keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", ks.getPath(),
                "-keyalg", "RSA", "-keysize", "2048", "-alias", "admin", "-dname", "CN=localhost",
                "-validity", "3650", "-storepass", "testpass", "-keypass", "testpass")
                .redirectErrorStream(true).redirectOutput(new File(System.getProperty("java.io.tmpdir"), "keytool.log"))
                .start().waitFor();
        if (rc != 0 || !ks.isFile()) { System.out.println("AdminTlsTest : keytool a échoué (rc=" + rc + ") — test non exécutable"); System.exit(1); }
        char[] pass = "testpass".toCharArray();

        javax.net.ssl.SSLContext ssl = AdminTls.serverSslContext(ks, pass);
        String fp = AdminTls.fingerprintSha256(ks, pass);

        String db = Files.createTempFile("admintls", ".db").toString();
        System.setProperty("dh.db", db);
        UserStore store = new UserStore(db);
        ServerUser user = store.loadOrCreate(1L, 1);
        LoginServer server = new LoginServer(0, user, store);
        String tok = "tls-operator-secret";
        AdminService admin = new AdminService("127.0.0.1", 0, tok, server, store, ssl);
        admin.start();
        ok(admin.isTls(), "AdminService sert en HTTPS (TLS)");
        String remoteUrl = "https://127.0.0.1:" + admin.port();

        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // empreinte FAUSSE → rejet (pin mismatch au ping)
            ok(post(http, base + "/admin/target", "adminUrl=" + enc(remoteUrl) + "&token=" + tok + "&caFingerprint=deadbeef").statusCode() == 502,
                    "empreinte TLS fausse → 502");
            // HTTPS SANS épinglage : le cert auto-signé n'est pas dans les CA système → rejet
            ok(post(http, base + "/admin/target", "adminUrl=" + enc(remoteUrl) + "&token=" + tok).statusCode() == 502,
                    "HTTPS auto-signé sans épinglage → 502");
            // empreinte BONNE → cible HTTPS définie + proxy chiffré
            HttpResponse<String> set = post(http, base + "/admin/target", "adminUrl=" + enc(remoteUrl) + "&token=" + tok + "&caFingerprint=" + fp);
            ok(set.statusCode() == 200 && set.body().contains("\"tls\":true"), "cible HTTPS épinglée (ping validé sur TLS)");
            HttpResponse<String> mon = get(http, base + "/admin/monitor");
            ok(mon.statusCode() == 200 && mon.body().contains("\"onlineCount\":0"), "monitor proxifié via TLS épinglé");
            ok(post(http, base + "/admin/target/clear", "").body().contains("\"mode\":\"local\""), "clear → local");
        } finally {
            admin.stop();
            d.stop();
        }

        System.out.println("AdminTlsTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static HttpResponse<String> get(HttpClient h, String url) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient h, String url, String body) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
