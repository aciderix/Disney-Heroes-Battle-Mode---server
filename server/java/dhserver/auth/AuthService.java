package dhserver.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dhserver.UserStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint HTTP d'authentification MNÉMONIQUE (chantier C1c) — fine glue au-dessus de {@link SessionStore}
 * (`com.sun.net.httpserver`, JDK, aucune dépendance). Tourne dans le MÊME process que {@code LoginServer} → la
 * {@link SessionStore} est partagée en mémoire (le socket de jeu lit les sessions établies ici).
 *
 * <ul>
 *   <li>{@code POST /auth/challenge} — champ {@code userID} → renvoie {@code {"nonce":"<base64url>"}}.</li>
 *   <li>{@code POST /auth/verify} — champs {@code userID, loginRequestID, nonce, signature} (base64url) → 200
 *       {@code {"ok":true}} si la signature vérifie contre la clé publique stockée, sinon 401.</li>
 * </ul>
 * Corps en {@code application/x-www-form-urlencoded}. Base64 URL-safe (pas de {@code +/=} à ré-échapper).
 */
public final class AuthService {
    private final HttpServer http;
    private final SessionStore sessions;
    private final UserStore store;

    public AuthService(int port, SessionStore sessions, UserStore store) throws IOException {
        this.sessions = sessions;
        this.store = store;
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/auth/challenge", this::handleChallenge);
        http.createContext("/auth/verify", this::handleVerify);
        http.createContext("/auth/register", this::handleRegister);
        http.setExecutor(null); // exécuteur par défaut (suffisant : handlers courts, non bloquants)
    }

    public void start() { http.start(); }
    public void stop()  { http.stop(0); }
    public int port()   { return http.getAddress().getPort(); }

    private void handleChallenge(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
        Map<String, String> f = form(ex);
        try {
            long uid = Long.parseLong(f.getOrDefault("userID", "0"));
            if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
            byte[] nonce = sessions.issueChallenge(uid);
            send(ex, 200, "{\"nonce\":\"" + b64(nonce) + "\"}");
        } catch (RuntimeException e) { send(ex, 400, "{\"error\":\"bad-request\"}"); }
    }

    private void handleVerify(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
        Map<String, String> f = form(ex);
        try {
            long uid = Long.parseLong(f.getOrDefault("userID", "0"));
            String lrid = f.getOrDefault("loginRequestID", "");
            byte[] nonce = unb64(f.getOrDefault("nonce", ""));
            byte[] sig = unb64(f.getOrDefault("signature", ""));
            boolean ok = sessions.verifyAndBind(uid, lrid, nonce, sig, store);
            send(ex, ok ? 200 : 401, ok ? "{\"ok\":true}" : "{\"ok\":false}");
        } catch (Exception e) { send(ex, 400, "{\"error\":\"bad-request\"}"); }
    }

    private void handleRegister(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
        Map<String, String> f = form(ex);
        try {
            long uid = Long.parseLong(f.getOrDefault("userID", "0"));
            String lrid = f.getOrDefault("loginRequestID", "");
            byte[] pub = unb64(f.getOrDefault("pubKey", ""));
            byte[] nonce = unb64(f.getOrDefault("nonce", ""));
            byte[] sig = unb64(f.getOrDefault("signature", ""));
            boolean ok = sessions.registerAndBind(uid, lrid, pub, nonce, sig, store);
            send(ex, ok ? 200 : 401, ok ? "{\"ok\":true,\"userID\":" + uid + "}" : "{\"ok\":false}");
        } catch (Exception e) { send(ex, 400, "{\"error\":\"bad-request\"}"); }
    }

    // ---- utilitaires HTTP ----
    private static Map<String, String> form(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, String> m = new HashMap<>();
        for (String kv : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(urldec(k), urldec(v));
        }
        return m;
    }
    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
    private static String urldec(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }
}
