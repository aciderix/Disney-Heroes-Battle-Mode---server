import dhlauncher.LauncherDaemon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * ADMIN inc.6a — le daemon launcher PROXIFIE {@code /admin/monitor} vers l'{@code AdminService} du serveur LOCAL
 * hébergé (jeton injecté), et sert {@code /host/logs} (tail local). Intégration RÉELLE : lance
 * {@code dhserver.LoginServer} (+ AdminService dans sa JVM) via {@code /host/start} → auto-détecté ISOLÉ par
 * {@code regression.sh}. Ports hauts (18085/6/7 ; adminPort = authPort+1 = 18088) pour éviter les collisions.
 */
public final class AdminProxyTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        int C = 18085, G = 18086, A = 18087;
        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // avant hébergement : admin indisponible → 503 (pas de faux OK)
            ok(get(http, base + "/admin/monitor").statusCode() == 503, "monitor sans serveur hébergé → 503");

            // start
            post(http, base + "/host/start", "contentPort=" + C + "&gamePort=" + G + "&authPort=" + A);

            // poll status jusqu'à running + gamePortListening
            boolean up = false;
            long deadline = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < deadline) {
                String s = post(http, base + "/host/status", "").body();
                if (s.contains("\"running\":true") && s.contains("\"gamePortListening\":true")) { up = true; break; }
                Thread.sleep(500);
            }
            ok(up, "serveur local RUNNING + port de jeu en écoute");

            // le proxy monitor traverse l'AdminService (jeton injecté par le daemon) → état réel : 0 en ligne
            HttpResponse<String> mon = get(http, base + "/admin/monitor");
            ok(mon.statusCode() == 200, "monitor proxifié → 200");
            ok(mon.body().contains("\"onlineCount\":0"), "monitor : 0 joueur en ligne (aucun client connecté)");
            ok(mon.body().contains("\"connectionsAccepted\":"), "monitor : connexions acceptées présentes");
            ok(mon.body().contains("\"online\":"), "monitor : liste online présente");

            // ère de contenu proxifiée (GET) : la liste des releases traverse le proxy générique + le jeton
            HttpResponse<String> rel = get(http, base + "/admin/releases");
            ok(rel.statusCode() == 200 && rel.body().contains("\"releases\":["), "admin/releases proxifié → liste");

            // POST proxifié (corps relayé) : régler l'ère sur la 1re release (la plus ancienne) → offset ≠ 0
            HttpResponse<String> set = post(http, base + "/admin/release", "name=%231"); // %231 = "#1"
            ok(set.statusCode() == 200 && set.body().contains("\"eraName\":"), "admin/release POST #1 → ère réglée");
            ok(set.body().contains("\"offsetMs\":") && !set.body().contains("\"offsetMs\":0,"), "admin/release : offset d'ère ≠ 0");

            // reset → offset 0 (date réelle)
            HttpResponse<String> rst = post(http, base + "/admin/release", "name=reset");
            ok(rst.statusCode() == 200 && rst.body().contains("\"offsetMs\":0,"), "admin/release reset → offset 0");

            // horloge : GET état puis POST offset 0 (ne décale rien)
            ok(get(http, base + "/admin/clock").statusCode() == 200, "admin/clock GET → 200");
            HttpResponse<String> clk = post(http, base + "/admin/clock", "offsetHours=0");
            ok(clk.statusCode() == 200 && clk.body().contains("\"offsetMs\":0,"), "admin/clock POST 0 → offset 0");

            // release introuvable → 404 relayé
            ok(post(http, base + "/admin/release", "name=NOPE").statusCode() == 404, "admin/release inconnue → 404 relayé");

            // gestion joueurs proxifiée : lookup + giveResource + audit tracé
            HttpResponse<String> look = post(http, base + "/admin/player/lookup", "userID=1");
            ok(look.statusCode() == 200 && look.body().contains("\"userID\":1"), "admin/player/lookup proxifié → résumé");
            HttpResponse<String> give = post(http, base + "/admin/player/giveResource", "userID=1&type=GOLD&amount=100");
            ok(give.statusCode() == 200 && give.body().contains("\"gold\":"), "admin/player/giveResource proxifié → or crédité");
            HttpResponse<String> aud = get(http, base + "/admin/audit?tail=20");
            ok(aud.statusCode() == 200 && aud.body().contains("giveResource"), "admin/audit proxifié → mutation tracée");

            // events proxifiés : enums (GET) + liste (GET) + clear (POST)
            ok(get(http, base + "/admin/enums").body().contains("\"GameMode\":["), "admin/enums proxifié → GameMode réel");
            ok(get(http, base + "/admin/events").body().contains("\"count\":"), "admin/events proxifié → liste");
            ok(post(http, base + "/admin/events/clear", "").body().contains("\"count\":0"), "admin/events/clear proxifié → 0");

            // modération proxifiée : liste + ban/kick/unban
            ok(get(http, base + "/admin/moderation").body().contains("\"bans\":"), "admin/moderation proxifié → liste");
            ok(post(http, base + "/admin/moderation/ban", "userID=999").body().contains("999"), "ban 999 proxifié");
            ok(post(http, base + "/admin/moderation/kick", "userID=1").body().contains("\"kicked\":false"), "kick 1 (hors ligne) → false");
            ok(!post(http, base + "/admin/moderation/unban", "userID=999").body().contains("999"), "unban 999 → retiré");

            // tail des logs hôte (fichier écrit par le serveur au boot)
            HttpResponse<String> logs = get(http, base + "/host/logs?which=server&tail=50");
            ok(logs.statusCode() == 200 && logs.body().contains("\"lines\":"), "host/logs (server) → JSON lines");
            ok(logs.body().contains("[login]") || logs.body().contains("login"), "host/logs : contient une ligne de boot serveur");

            // stop → admin de nouveau indisponible
            post(http, base + "/host/stop", "");
            Thread.sleep(300);
            ok(get(http, base + "/admin/monitor").statusCode() == 503, "après stop : monitor → 503");
        } finally {
            try { post(http, base + "/host/stop", ""); } catch (Exception ignore) {}
            d.stop();
        }

        System.out.println("AdminProxyTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static HttpResponse<String> get(HttpClient h, String url) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient h, String url, String body) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
