package dhserver.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dhserver.UserStore;
import dhserver.directory.ServerIdentity;
import dhserver.directory.ServerInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

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
    private static final SecureRandom RND = new SecureRandom();
    private final HttpServer http;
    private final SessionStore sessions;
    private final UserStore store;
    /** ANNUAIRE (brique 1) — identité du serveur (signe /info) ; {@code null} = pas de fiche publique. */
    private final ServerIdentity identity;
    /** Fournit une fiche FRAÎCHE à chaque appel /info (compteur en ligne vivant). */
    private final Supplier<ServerInfo> infoSupplier;

    public AuthService(int port, SessionStore sessions, UserStore store) throws IOException {
        this(port, sessions, store, null, null);
    }

    /** Variante ANNUAIRE : expose en plus {@code GET /info} SIGNÉ si une {@code identity} + un {@code infoSupplier} sont fournis. */
    public AuthService(int port, SessionStore sessions, UserStore store,
                       ServerIdentity identity, Supplier<ServerInfo> infoSupplier) throws IOException {
        this.sessions = sessions;
        this.store = store;
        this.identity = identity;
        this.infoSupplier = infoSupplier;
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/auth/challenge", this::handleChallenge);
        http.createContext("/auth/verify", this::handleVerify);
        http.createContext("/auth/register", this::handleRegister);
        http.createContext("/auth/mint", this::handleMint);
        if (identity != null && infoSupplier != null) http.createContext("/info", this::handleInfo);
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

    /**
     * {@code POST /auth/mint} — champ {@code userID}. Appelé par {@code content_server} au {@code /login} du client
     * (flux « Jouer » strict) : si le joueur a été RÉCEMMENT AUTHENTIFIÉ par le launcher (défi-réponse), imprime un
     * {@code loginRequestID} frais lié → {@code {"loginRequestID":"..."}} ; sinon 401. Endpoint LOCAL (le serveur de
     * jeu et content_server tournent ensemble) — à ne pas exposer publiquement.
     */
    private void handleMint(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
        Map<String, String> f = form(ex);
        try {
            long uid = Long.parseLong(f.getOrDefault("userID", "0"));
            String lrid = sessions.mintForUser(uid);
            if (lrid != null) send(ex, 200, "{\"loginRequestID\":\"" + lrid + "\"}");
            else send(ex, 401, "{\"error\":\"not-authenticated\"}");
        } catch (RuntimeException e) { send(ex, 400, "{\"error\":\"bad-request\"}"); }
    }

    /**
     * ANNUAIRE (brique 1) — {@code GET /info} PUBLIC (aucun jeton) : renvoie la fiche du serveur SIGNÉE Ed25519.
     * Le vérifieur (launcher) passe un défi frais {@code ?nonce=<base64url>} → la signature couvre {fiche + nonce}
     * (anti-rejeu : impossible de resservir une vieille signature). Sans nonce fourni, on en génère un (signature
     * quand même valide, mais sans garantie de fraîcheur côté appelant). La fiche (nom/mode/versions/en-ligne) provient
     * du serveur vivant via {@code infoSupplier}. La clé PUBLIQUE est incluse → le launcher vérifie SANS état préalable.
     */
    private void handleInfo(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
        try {
            String nonce = sanitizeNonce(query(ex.getRequestURI().getRawQuery()).get("nonce"));
            if (nonce.isEmpty()) { byte[] n = new byte[18]; RND.nextBytes(n); nonce = b64(n); }
            ServerInfo info = infoSupplier.get();
            String sig = identity.sign(info.canonical(nonce));
            send(ex, 200, info.toJson(identity.publicKeyB64(), identity.serverId(), nonce, sig));
        } catch (Exception e) { send(ex, 500, "{\"error\":\"info\"}"); }
    }

    /** Ne garde que des caractères base64url (défi opaque) et borne à 128 — un nonce hostile ne peut pas gonfler la réponse. */
    private static String sanitizeNonce(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 128; i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') b.append(c);
        }
        return b.toString();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(urldec(k), urldec(v));
        }
        return m;
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
