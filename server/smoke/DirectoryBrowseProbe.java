import dhlauncher.LauncherDaemon;
import dhserver.UserStore;
import dhserver.auth.AuthService;
import dhserver.auth.SessionStore;
import dhserver.directory.ServerIdentity;
import dhserver.directory.ServerInfo;
import dhserver.directory.ServerRegistration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ANNUAIRE (brique 3) — SONDE DEV (réseau, EXCLUE de regression) des endpoints daemon /directory*. Prouve : (1) le daemon
 * VÉRIFIE une fiche via /info (POST /directory/verify → 200 sur un /info vivant, 502 sur une URL morte) ; (2) le daemon
 * PROXIFIE la lecture de l'annuaire (GET /directory → 200 tableau JSON). Lit PROJECT_URL/ANON_PUBLIC dans l'env.
 */
public final class DirectoryBrowseProbe {
    public static void main(String[] args) throws Exception {
        // Serveur local qui expose /info signé
        Path idFile = Files.createTempFile("browseid", ".txt"); Files.delete(idFile);
        ServerIdentity id = ServerIdentity.loadOrCreate(idFile);
        String db = Files.createTempFile("browse", ".db").toString();
        System.setProperty("dh.db", db);
        try (UserStore store = new UserStore(db)) {
            SessionStore ss = new SessionStore();
            String uniqueName = "Browse Test " + (System.currentTimeMillis() % 100000);
            AuthService svc = new AuthService(0, ss, store, id,
                    () -> new ServerInfo(uniqueName, "open", "12.1.0", "0.2.0", 2, 40, System.currentTimeMillis()));
            svc.start();
            String infoBase = "http://127.0.0.1:" + svc.port();

            // Inscription RÉELLE dans l'annuaire (pour prouver le chemin table → daemon /directory). Nettoyée ensuite.
            String dirUrl = System.getenv("PROJECT_URL"), dirKey = System.getenv("ANON_PUBLIC");
            boolean registered = false;
            if (dirUrl != null && dirKey != null) {
                int rc = ServerRegistration.register(dirUrl, dirKey, id,
                        new ServerInfo(uniqueName, "open", "12.1.0", "0.2.0", 2, 40, System.currentTimeMillis()),
                        "127.0.0.1:8080", infoBase);
                registered = rc == 200;
                System.out.println("(0) inscription annuaire → HTTP " + rc + " (pubKey=" + id.publicKeyB64() + ")");
            }

            LauncherDaemon d = new LauncherDaemon(0);
            d.start();
            String base = "http://127.0.0.1:" + d.port();
            HttpClient http = HttpClient.newHttpClient();
            try {
                // (1) vérif d'une fiche vivante
                HttpResponse<String> v = post(http, base + "/directory/verify", "infoUrl=" + enc(infoBase));
                boolean okVerify = v.statusCode() == 200 && v.body().contains("\"name\":\"" + uniqueName + "\"") && v.body().contains("\"verified\":true");
                System.out.println("(1) verify /info vivant → HTTP " + v.statusCode() + (okVerify ? " ✅" : " ✗ " + v.body()));

                // (1b) URL morte → 502
                HttpResponse<String> dead = post(http, base + "/directory/verify", "infoUrl=" + enc("http://127.0.0.1:1"));
                System.out.println("(1b) verify URL morte → HTTP " + dead.statusCode() + (dead.statusCode() == 502 ? " ✅ écartée" : " ✗"));

                // (2) liste annuaire (proxy Supabase) — 200 + tableau JSON ; notre serveur inscrit doit y figurer
                HttpResponse<String> list = get(http, base + "/directory");
                boolean okList = list.statusCode() == 200 && list.body().trim().startsWith("[");
                boolean present = !registered || list.body().contains(uniqueName);
                System.out.println("(2) GET /directory → HTTP " + list.statusCode() + (okList ? " ✅ tableau" : " ✗")
                        + (registered ? (present ? " · fiche inscrite PRÉSENTE ✅" : " · fiche ABSENTE ✗") : " (annuaire non configuré)"));

                boolean all = okVerify && dead.statusCode() == 502 && okList && present;
                System.out.println("DirectoryBrowseProbe : " + (all ? "TOUT VERT ✅" : "ÉCHEC ✗"));
            } finally { d.stop(); svc.stop(); }
        }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static HttpResponse<String> get(HttpClient h, String url) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient h, String url, String body) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
