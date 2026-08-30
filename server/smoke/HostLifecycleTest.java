import dhlauncher.LauncherDaemon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * C2a-3 (hébergement local) — le launcher-core lance / interroge / arrête un serveur de jeu LOCAL via {@code /host/*}.
 * Intégration RÉELLE (lance {@code dhserver.LoginServer} + {@code content_server.py} en process) → auto-détecté
 * ISOLÉ par {@code regression.sh} (démarre un serveur/socket). Ports hauts (18080/1/2) pour éviter les collisions.
 */
public final class HostLifecycleTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        int C = 18080, G = 18081, A = 18082;
        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // état initial = arrêté
            ok(post(http, base + "/host/status", "").contains("\"running\":false"), "status initial = arrêté");

            // start
            String st = post(http, base + "/host/start", "contentPort=" + C + "&gamePort=" + G + "&authPort=" + A);
            ok(st.contains("\"gamePort\":" + G), "start renvoie le gamePort");

            // poll status jusqu'à running + gamePortListening (serveur = boot ServerContext ~qq s)
            boolean up = false;
            long deadline = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < deadline) {
                String s = post(http, base + "/host/status", "");
                if (s.contains("\"running\":true") && s.contains("\"gamePortListening\":true")) { up = true; break; }
                Thread.sleep(500);
            }
            ok(up, "serveur local RUNNING + port de jeu en écoute");

            // le port de jeu accepte bien une connexion TCP (preuve indépendante du daemon)
            boolean tcp = false;
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", G), 1000); tcp = true;
            } catch (Exception ignore) {}
            ok(tcp, "connexion TCP directe au port de jeu OK");

            // idempotence : re-start ne casse rien, renvoie l'état courant
            ok(post(http, base + "/host/start", "gamePort=" + G).contains("\"running\":true"), "start idempotent");

            // stop
            ok(post(http, base + "/host/stop", "").contains("\"running\":false"), "stop → arrêté");

            // le port n'écoute plus
            Thread.sleep(500);
            boolean down = false;
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", G), 500);
            } catch (Exception e) { down = true; }
            ok(down, "port de jeu FERMÉ après stop");
        } finally {
            try { post(http, base + "/host/stop", ""); } catch (Exception ignore) {}
            d.stop();
        }

        System.out.println("HostLifecycleTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static String post(HttpClient h, String url, String body) throws Exception {
        HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }
}
