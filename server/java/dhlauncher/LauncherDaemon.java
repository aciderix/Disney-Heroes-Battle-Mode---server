package dhlauncher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LAUNCHER-CORE — daemon HTTP LOCAL (chantier C2a, {@code docs/LAUNCHER.md}). Backend Java du launcher, sur la machine
 * du JOUEUR : garde l'état (session, process, favoris), réutilise {@link MnemonicIdentity} (la clé privée reste locale,
 * jamais transmise) et APPELLE l'{@code AuthService} du serveur de jeu DISTANT pour authentifier.
 *
 * <p>Lié à {@code 127.0.0.1} UNIQUEMENT (jamais exposé au réseau). Le front (Tauri/React) l'appelle en HTTP local.
 * Requêtes {@code application/x-www-form-urlencoded}, réponses JSON. <b>C2a-1</b> : identité (generate/login/register).
 */
public final class LauncherDaemon {
    private final HttpServer http;
    private final HttpClient client = HttpClient.newHttpClient();
    private final LauncherConfig config = new LauncherConfig();
    private final String projectDir = System.getProperty("dh.launcher.projectdir", System.getProperty("user.dir", "."));
    private final HostManager host = new HostManager(projectDir);
    private final BuildManager build = new BuildManager(projectDir);

    /** @param port port local (0 = éphémère, choisi par l'OS). Lié à loopback seulement. */
    public LauncherDaemon(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/health", ex -> send(ex, 200, "{\"ok\":true}"));
        http.createContext("/identity/generate", this::generate);
        http.createContext("/identity/login", ex -> auth(ex, false));
        http.createContext("/identity/register", ex -> auth(ex, true));
        http.createContext("/servers", this::servers);          // GET (liste) | POST (ajout)
        http.createContext("/servers/remove", this::serversRemove);
        http.createContext("/servers/ping", this::serversPing);
        http.createContext("/host/start", this::hostStart);     // C2a-3 : héberger en local
        http.createContext("/host/stop", this::hostStop);
        http.createContext("/host/status", this::hostStatus);
        http.createContext("/build/start", this::buildStart);   // C2a-4 : générer le serveur depuis l'APK
        http.createContext("/build/status", this::buildStatus);
        http.setExecutor(null);
    }

    public void start() { http.start(); }
    public void stop()  { http.stop(0); }
    public int port()   { return http.getAddress().getPort(); }

    /** Génère une nouvelle phrase + dérive l'identité (pour l'écran « Nouveau compte » — à noter par le joueur). */
    private void generate(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        String phrase = MnemonicIdentity.generate();
        Identity id = MnemonicIdentity.fromPhrase(phrase);
        send(ex, 200, "{\"phrase\":\"" + phrase + "\",\"userID\":" + id.userID
                + ",\"publicKey\":\"" + b64(id.publicKey) + "\"}");
    }

    /**
     * Orchestration LOGIN ({@code register=false}) ou REGISTER ({@code register=true}) contre l'{@code AuthService}
     * distant : dérive l'identité de la phrase, demande un nonce, le SIGNE (clé privée jamais transmise), soumet.
     * Champs : {@code phrase}, {@code serverAuthUrl} (base de l'AuthService, ex. {@code http://host:8082}).
     * Renvoie {@code {ok, userID, loginRequestID}} — le {@code loginRequestID} authentifié que le client de jeu
     * présentera ensuite dans son {@code ClientInfo} (cf. C2a-2 « play »).
     */
    private void auth(HttpExchange ex, boolean register) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        try {
            String phrase = f.getOrDefault("phrase", "");
            String authUrl = trimSlash(f.getOrDefault("serverAuthUrl", ""));
            if (!MnemonicIdentity.isValid(phrase) || authUrl.isEmpty()) { send(ex, 400, "{\"ok\":false,\"error\":\"phrase|serverAuthUrl\"}"); return; }
            Identity id = MnemonicIdentity.fromPhrase(phrase);
            String loginRequestID = UUID.randomUUID().toString();

            // 1) challenge
            HttpResponse<String> ch = remote(authUrl + "/auth/challenge", "userID=" + id.userID);
            if (ch.statusCode() != 200) { send(ex, 502, "{\"ok\":false,\"error\":\"challenge\"}"); return; }
            byte[] nonce = unb64(json(ch.body(), "nonce"));
            byte[] sig = MnemonicIdentity.sign(id.keyPair.getPrivate(), nonce);

            // 2) verify (login) ou register
            String body = "userID=" + id.userID + "&loginRequestID=" + enc(loginRequestID)
                    + "&nonce=" + b64(nonce) + "&signature=" + b64(sig)
                    + (register ? "&pubKey=" + b64(id.publicKey) : "");
            HttpResponse<String> vr = remote(authUrl + (register ? "/auth/register" : "/auth/verify"), body);
            if (vr.statusCode() != 200) { send(ex, 401, "{\"ok\":false,\"error\":\"" + (register ? "register" : "verify") + "\"}"); return; }

            send(ex, 200, "{\"ok\":true,\"userID\":" + id.userID + ",\"loginRequestID\":\"" + loginRequestID + "\"}");
        } catch (Exception e) { send(ex, 500, "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /** GET /servers → liste des favoris (JSON array) ; POST /servers {name, host, [contentPort, gamePort, authPort]} → ajout. */
    private void servers(HttpExchange ex) throws IOException {
        try {
            if ("GET".equals(ex.getRequestMethod())) { send(ex, 200, config.toJsonArray()); return; }
            if (!post(ex)) return;
            Map<String, String> f = form(ex);
            LauncherConfig.Server s = config.add(f.getOrDefault("name", ""), f.getOrDefault("host", ""),
                    intOr(f, "contentPort", 8080), intOr(f, "gamePort", 8081), intOr(f, "authPort", 8082));
            send(ex, 200, s.toJson());
        } catch (IllegalArgumentException e) { send(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}"); }
        catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /** POST /servers/remove {id} → retire un favori. */
    private void serversRemove(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        try {
            boolean removed = config.remove(form(ex).getOrDefault("id", ""));
            send(ex, removed ? 200 : 404, "{\"ok\":" + removed + "}");
        } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /** POST /servers/ping {host, port} → teste l'accessibilité TCP + latence (ms). */
    private void serversPing(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        String host = f.getOrDefault("host", "");
        int port = intOr(f, "port", 8081);
        long t0 = System.nanoTime();
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new InetSocketAddress(host, port), 2000);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            send(ex, 200, "{\"reachable\":true,\"latencyMs\":" + ms + "}");
        } catch (Exception e) {
            send(ex, 200, "{\"reachable\":false}");
        }
    }

    /** POST /host/start {[contentPort=8080], [gamePort=8081], [authPort=8082], [strict=false]} → héberge en local. */
    private void hostStart(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        boolean strict = "true".equalsIgnoreCase(f.getOrDefault("strict", "false")) || "1".equals(f.getOrDefault("strict", ""));
        try {
            send(ex, 200, host.start(intOr(f, "contentPort", 8080), intOr(f, "gamePort", 8081), intOr(f, "authPort", 8082), strict));
        } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /** POST /host/stop → arrête le serveur local hébergé. */
    private void hostStop(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        send(ex, 200, host.stop());
    }

    /** GET /host/status → état du serveur local hébergé (running, écoute, ports, PIDs, uptime). */
    private void hostStatus(HttpExchange ex) throws IOException {
        send(ex, 200, host.status());
    }

    /** POST /build/start {apkPath, [target=server|client|apk], [outDir], [full=false]} → génère depuis l'APK (async). */
    private void buildStart(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        boolean full = "true".equalsIgnoreCase(f.getOrDefault("full", "false")) || "1".equals(f.getOrDefault("full", ""));
        BuildManager.Target tgt;
        try { tgt = BuildManager.Target.valueOf(f.getOrDefault("target", "server").trim().toUpperCase()); }
        catch (Exception e) { send(ex, 400, "{\"error\":\"target invalide (server|client|apk)\"}"); return; }
        send(ex, 200, build.start(f.getOrDefault("apkPath", ""), f.getOrDefault("outDir", ""), tgt, full));
    }

    /** GET /build/status → état du build (state, step, outDir, tail du log). */
    private void buildStatus(HttpExchange ex) throws IOException {
        send(ex, 200, build.status());
    }

    private static int intOr(Map<String, String> f, String k, int def) {
        try { return Integer.parseInt(f.getOrDefault(k, "").trim()); } catch (Exception e) { return def; }
    }

    private HttpResponse<String> remote(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ---- utilitaires HTTP ----
    private static boolean post(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) return true;
        ex.sendResponseHeaders(405, -1); ex.close(); return false;
    }
    private static Map<String, String> form(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String kv : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            m.put(dec(i < 0 ? kv : kv.substring(0, i)), i < 0 ? "" : dec(kv.substring(i + 1)));
        }
        return m;
    }
    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
    private static String json(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("champ '" + key + "' absent");
        return m.group(1);
    }
    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String dec(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }
}
