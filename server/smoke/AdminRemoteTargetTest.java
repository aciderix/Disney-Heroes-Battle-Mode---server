import dhlauncher.LauncherDaemon;
import dhserver.LoginServer;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;
import dhserver.admin.AdminService;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * ADMIN DISTANT (chantier F) — le daemon peut administrer un serveur DISTANT (cloud) : POST /admin/target {adminUrl,
 * token} bascule la cible du proxy /admin/* vers un {@code AdminService} distant, VALIDÉ par un /admin/ping authentifié.
 * Ici le « serveur distant » = un {@code AdminService} en-process avec un jeton connu (pas d'hébergement local). Référence
 * {@code LoginServer} → auto-détecté ISOLÉ par {@code regression.sh}.
 */
public final class AdminRemoteTargetTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        ServerContext.init();
        String db = Files.createTempFile("remoteadmin", ".db").toString();
        System.setProperty("dh.db", db);
        UserStore store = new UserStore(db);
        ServerUser user = store.loadOrCreate(1L, 1);
        LoginServer server = new LoginServer(0, user, store);

        // « serveur distant » : AdminService avec un jeton connu (bind loopback pour le test)
        String remoteTok = "remote-operator-secret";
        AdminService remoteAdmin = new AdminService("127.0.0.1", 0, remoteTok, server, store);
        remoteAdmin.start();
        String remoteUrl = "http://127.0.0.1:" + remoteAdmin.port();

        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // défaut = local ; sans serveur hébergé → /admin/monitor 503
            ok(get(http, base + "/admin/target").body().contains("\"mode\":\"local\""), "cible par défaut = local");
            ok(get(http, base + "/admin/monitor").statusCode() == 503, "monitor sans cible → 503");

            // mauvais jeton → rejet à la validation (ping 401 → 502)
            ok(post(http, base + "/admin/target", "adminUrl=" + enc(remoteUrl) + "&token=WRONG").statusCode() == 502,
                    "cible distante mauvais jeton → 502 (ping refusé)");
            // URL injoignable → 502
            ok(post(http, base + "/admin/target", "adminUrl=" + enc("http://127.0.0.1:1") + "&token=x").statusCode() == 502,
                    "cible distante injoignable → 502");

            // bon URL+jeton → mode remote (ping validé)
            HttpResponse<String> set = post(http, base + "/admin/target", "adminUrl=" + enc(remoteUrl) + "&token=" + remoteTok);
            ok(set.statusCode() == 200 && set.body().contains("\"mode\":\"remote\""), "cible distante définie (ping validé)");
            ok(get(http, base + "/admin/target").body().contains("\"mode\":\"remote\""), "GET target = remote");

            // le proxy /admin/* va désormais au serveur DISTANT
            HttpResponse<String> mon = get(http, base + "/admin/monitor");
            ok(mon.statusCode() == 200 && mon.body().contains("\"onlineCount\":0"), "monitor proxifié vers le serveur DISTANT");
            HttpResponse<String> rel = get(http, base + "/admin/releases");
            ok(rel.statusCode() == 200 && rel.body().contains("\"releases\":["), "releases proxifiées vers le distant");

            // clear → repli local → 503
            ok(post(http, base + "/admin/target/clear", "").body().contains("\"mode\":\"local\""), "clear → local");
            ok(get(http, base + "/admin/monitor").statusCode() == 503, "après clear : monitor → 503 (local non hébergé)");
        } finally {
            remoteAdmin.stop();
            d.stop();
        }

        System.out.println("AdminRemoteTargetTest : " + passed + " ok, " + failed + " échec(s)");
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
