import dhlauncher.LauncherConfig;
import dhlauncher.LauncherDaemon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smoke assertif (chantier C2a-2, volet serveurs) — favoris de serveurs via le daemon : GET/POST /servers,
 * /servers/remove, /servers/ping, + PERSISTANCE (relecture disque). Dossier de config = temp (isolé). No-arg.
 */
public final class LauncherServersTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }
    static HttpClient http = HttpClient.newHttpClient();
    static String base;

    public static void main(String[] args) throws Exception {
        Path cfg = Files.createTempDirectory("dh-launcher-cfg");
        System.setProperty("dh.launcher.config", cfg.toString());   // isole la config (pas le vrai home)

        LauncherDaemon daemon = new LauncherDaemon(0);
        daemon.start();
        base = "http://127.0.0.1:" + daemon.port();
        try {
            // 1) liste vide au départ
            ok(get("/servers").equals("[]"), "aucun favori au départ");

            // 2) ajout
            String add = post("/servers", form("name", "Local", "host", "127.0.0.1"));
            ok(add.contains("\"name\":\"Local\"") && add.contains("\"host\":\"127.0.0.1\""), "ajout renvoie l'entrée");
            String id = json(add, "id");
            ok(!id.isEmpty(), "un id est attribué");
            ok(add.contains("\"authPort\":8082"), "ports par défaut renseignés");

            // 3) liste = 1
            ok(count(get("/servers"), "\"id\":") == 1, "1 favori listé");

            // 4) PERSISTANCE : relecture disque via une nouvelle instance de config
            ok(new LauncherConfig(cfg).load().size() == 1, "favori persisté sur disque");

            // 5) ping : port OUVERT (celui du daemon) reachable ; port fermé → non
            ok(post("/servers/ping", form("host", "127.0.0.1", "port", String.valueOf(daemon.port()))).contains("\"reachable\":true"),
                "ping d'un port ouvert → reachable");
            ok(post("/servers/ping", form("host", "127.0.0.1", "port", "1")).contains("\"reachable\":false"),
                "ping d'un port fermé → non reachable");

            // 6) rejet d'un nom invalide (caractère interdit) → 400
            HttpResponse<String> bad = postR("/servers", form("name", "a|b", "host", "127.0.0.1"));
            ok(bad.statusCode() == 400, "nom invalide (|) rejeté");

            // 7) suppression → liste vide + disque vide
            ok(post("/servers/remove", form("id", id)).contains("\"ok\":true"), "suppression OK");
            ok(get("/servers").equals("[]"), "liste vide après suppression");
            ok(new LauncherConfig(cfg).load().isEmpty(), "disque vide après suppression");
        } finally {
            daemon.stop();
        }

        System.out.println("[LauncherServersTest] OK — " + checks + " assertions (favoris : add/list/remove/persist/ping)");
    }

    static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(kv[i]).append('=').append(java.net.URLEncoder.encode(kv[i + 1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return b.toString();
    }
    static String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static String post(String path, String body) throws Exception { return postR(path, body).body(); }
    static HttpResponse<String> postR(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
    static String json(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }
    static int count(String s, String sub) { int n = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); } return n; }
}
