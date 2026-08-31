import dhserver.LoginServer;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;
import dhserver.admin.AdminService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

/**
 * ADMIN inc.6a — l'{@code AdminService} (dans la JVM du serveur) sert le monitoring LECTURE SEULE, protégé par un
 * JETON opérateur. Vérifie : garde de jeton (401 sans / mauvais jeton, 200 avec, via {@code Authorization: Bearer}
 * ET {@code X-Admin-Token}), et le contenu de {@code /admin/monitor} (état réel : 0 en ligne, 0 connexion, uptime,
 * mode). Référence {@code LoginServer} → auto-détecté ISOLÉ par {@code regression.sh}.
 */
public final class AdminMonitorTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        ServerContext.init();
        String db = Files.createTempFile("admintest", ".db").toString();
        UserStore store = new UserStore(db);
        ServerUser user = store.loadOrCreate(1L, 1);
        LoginServer server = new LoginServer(0, user, store);  // pas de socket : on teste juste l'AdminService

        String tok = "secret-operator-token";
        AdminService admin = new AdminService("127.0.0.1", 0, tok, server, store);
        admin.start();
        String base = "http://127.0.0.1:" + admin.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // garde de jeton
            ok(get(http, base + "/admin/monitor", null, null).statusCode() == 401, "monitor sans jeton → 401");
            ok(get(http, base + "/admin/monitor", "X-Admin-Token", "mauvais").statusCode() == 401, "monitor mauvais jeton → 401");

            // jeton correct via X-Admin-Token
            HttpResponse<String> r = get(http, base + "/admin/monitor", "X-Admin-Token", tok);
            ok(r.statusCode() == 200, "monitor bon jeton (X-Admin-Token) → 200");
            ok(r.body().contains("\"onlineCount\":0"), "monitor : 0 joueur en ligne");
            ok(r.body().contains("\"connectionsAccepted\":0"), "monitor : 0 connexion acceptée");
            ok(r.body().contains("\"online\":[]"), "monitor : liste online vide");
            ok(r.body().contains("\"uptimeMs\":"), "monitor : uptime présent");
            ok(r.body().contains("\"strict\":false"), "monitor : mode permissif par défaut");

            // jeton correct via Authorization: Bearer
            ok(get(http, base + "/admin/monitor", "Authorization", "Bearer " + tok).statusCode() == 200,
                    "monitor bon jeton (Authorization: Bearer) → 200");

            // ping (vérif de jeton) : 401 sans, ok avec
            ok(get(http, base + "/admin/ping", null, null).statusCode() == 401, "ping sans jeton → 401");
            HttpResponse<String> p = get(http, base + "/admin/ping", "X-Admin-Token", tok);
            ok(p.statusCode() == 200 && p.body().contains("\"ok\":true"), "ping bon jeton → {ok:true}");

            // jetons aléatoires distincts et non vides
            String a = AdminService.randomToken(), b = AdminService.randomToken();
            ok(a != null && !a.isEmpty() && !a.equals(b), "randomToken() non vide et distinct");
        } finally {
            admin.stop();
        }

        System.out.println("AdminMonitorTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static HttpResponse<String> get(HttpClient h, String url, String header, String value) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (header != null) b.header(header, value);
        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
