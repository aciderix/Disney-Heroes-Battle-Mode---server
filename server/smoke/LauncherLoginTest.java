import dhlauncher.LauncherDaemon;
import dhserver.UserStore;
import dhserver.auth.AuthService;
import dhserver.auth.SessionStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smoke assertif (chantier C2a-1) — le DAEMON launcher-core orchestre le login/register mnémonique contre un VRAI
 * {@code AuthService} (serveur de jeu simulé) : /identity/generate, /identity/register, /identity/login. Prouve que
 * le daemon dérive+signe localement (clé jamais transmise) et établit la session côté serveur. No-arg, regression.sh.
 */
public final class LauncherLoginTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }
    static HttpClient http = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        String db = Files.createTempFile("launcher", ".sqlite").toString();
        try (UserStore store = new UserStore(db)) {
            SessionStore ssServer = new SessionStore();
            AuthService server = new AuthService(0, ssServer, store);   // = serveur de jeu distant
            server.start();
            String authBase = "http://127.0.0.1:" + server.port();

            LauncherDaemon daemon = new LauncherDaemon(0);              // = backend du launcher, machine du joueur
            daemon.start();
            String d = "http://127.0.0.1:" + daemon.port();

            try {
                // 1) le launcher génère une phrase (écran « Nouveau compte »)
                String gen = post(d + "/identity/generate", "");
                String phrase = json(gen, "phrase");
                long userID = Long.parseLong(jsonNum(gen, "userID"));
                ok(phrase.split(" ").length == 8, "phrase générée = 8 mots");
                ok(userID > 0, "userID dérivé");

                // 2) LOGIN avant création → refus (compte inconnu côté serveur)
                HttpResponse<String> pre = postR(d + "/identity/login", form("phrase", phrase, "serverAuthUrl", authBase));
                ok(pre.statusCode() != 200, "login avant register → refusé");
                ok(ssServer.authenticatedUser(loginId(pre.body())) == 0, "aucune session avant register");

                // 3) REGISTER via le daemon → compte créé côté serveur + session établie
                HttpResponse<String> reg = postR(d + "/identity/register", form("phrase", phrase, "serverAuthUrl", authBase));
                ok(reg.statusCode() == 200 && reg.body().contains("\"ok\":true"), "register via daemon → 200 ok");
                ok(store.lookupPubKey(userID) != null, "compte enregistré côté serveur (clé publique stockée)");
                String lrid1 = json(reg.body(), "loginRequestID");
                ok(ssServer.authenticatedUser(lrid1) == userID, "session serveur liée au loginRequestID du register");

                // 4) LOGIN via le daemon (restauration : mêmes phrase → même userID, nouveau loginRequestID)
                HttpResponse<String> log = postR(d + "/identity/login", form("phrase", phrase, "serverAuthUrl", authBase));
                ok(log.statusCode() == 200 && log.body().contains("\"ok\":true"), "login via daemon → 200 ok");
                String lrid2 = json(log.body(), "loginRequestID");
                ok(!lrid2.equals(lrid1), "nouveau loginRequestID par session");
                ok(ssServer.authenticatedUser(lrid2) == userID, "session serveur liée au loginRequestID du login");

                // 5) health
                ok(post(d + "/health", "").contains("\"ok\":true"), "/health OK");
            } finally {
                daemon.stop();
                server.stop();
            }
        }

        Files.deleteIfExists(java.nio.file.Path.of(db));
        System.out.println("[LauncherLoginTest] OK — " + checks + " assertions (daemon → AuthService : generate/register/login)");
    }

    static String form(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(kv[i]).append('=').append(java.net.URLEncoder.encode(kv[i + 1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return b.toString();
    }
    static String post(String url, String body) throws Exception { return postR(url, body).body(); }
    static HttpResponse<String> postR(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
    static String json(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new AssertionError("champ '" + key + "' absent de: " + body);
        return m.group(1);
    }
    static String jsonNum(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        if (!m.find()) throw new AssertionError("champ num '" + key + "' absent de: " + body);
        return m.group(1);
    }
    static String loginId(String body) {
        Matcher m = Pattern.compile("\"loginRequestID\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }
}
