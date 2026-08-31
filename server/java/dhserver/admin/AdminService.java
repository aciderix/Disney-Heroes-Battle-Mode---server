package dhserver.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dhserver.LoginServer;
import dhserver.UserStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * ADMIN — panneau opérateur HTTP (chantier D, {@code docs/LAUNCHER_UI.md} §4.6). Tourne dans le MÊME process que
 * {@link LoginServer} (accès à l'état VIVANT : joueurs en ligne, connexions, puis édition de comptes / events /
 * modération) — le launcher-core est volontairement GAME-FREE et ne peut pas exécuter ces opérations lui-même ;
 * il les PROXIFIE vers ce service (comme {@code content_server} proxifie {@code /login}→{@code /auth/mint}).
 *
 * <p><b>Sécurité (choix util. : « A et/ou C, toujours sécurisé »)</b> :
 * <ul>
 *   <li><b>Jeton opérateur OBLIGATOIRE</b> sur chaque requête ({@code Authorization: Bearer <jeton>} ou en-tête
 *       {@code X-Admin-Token}) — sans jeton valide, <b>401</b>. Le jeton est fourni par l'hébergeur (via
 *       {@code -Ddh.admin.token} / {@code DH_ADMIN_TOKEN}) ou généré aléatoirement au démarrage et imprimé.</li>
 *   <li><b>Liaison configurable</b> : {@code 127.0.0.1} par défaut (serveur sur le même PC = option A) ; peut écouter
 *       sur le réseau ({@code -Ddh.admin.bind=0.0.0.0}) pour administrer un serveur cloud (option C) — protégé par le
 *       jeton. Le durcissement réseau (TLS, rate-limit) = chantier F.</li>
 * </ul>
 * Comparaison du jeton en temps constant. {@code com.sun.net.httpserver} (JDK, aucune dépendance).
 *
 * <p>Endpoints (inc.6a — <b>monitoring</b>, lecture seule) : {@code GET /admin/ping} (vérif jeton) ;
 * {@code GET /admin/monitor} (joueurs en ligne + connexions acceptées + uptime + mode strict). Les domaines suivants
 * (ère de contenu, joueurs, events, modération) s'ajouteront comme contextes supplémentaires.
 */
public final class AdminService {
    private final HttpServer http;
    private final String token;
    private final byte[] tokenBytes;
    private final LoginServer server;
    private final UserStore store;

    public AdminService(String bind, int port, String token, LoginServer server, UserStore store) throws IOException {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("jeton admin requis");
        this.token = token;
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        this.server = server;
        this.store = store;
        String host = (bind == null || bind.isEmpty()) ? "127.0.0.1" : bind;
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        http.createContext("/admin/ping", guarded(this::handlePing));
        http.createContext("/admin/monitor", guarded(this::handleMonitor));
        http.setExecutor(null); // handlers courts, non bloquants
    }

    public void start() { http.start(); }
    public void stop()  { http.stop(0); }
    public int port()   { return http.getAddress().getPort(); }

    /** Génère un jeton opérateur aléatoire (128 bits, URL-safe) quand l'hébergeur n'en fournit pas. */
    public static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- garde de jeton (401 sinon) ----
    private interface AdminHandler { void handle(HttpExchange ex) throws IOException; }

    private com.sun.net.httpserver.HttpHandler guarded(AdminHandler h) {
        return ex -> {
            if (!authorized(ex)) { send(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
            try { h.handle(ex); }
            catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
        };
    }

    /** Jeton présenté dans {@code Authorization: Bearer <jeton>} OU {@code X-Admin-Token}, comparé en temps constant. */
    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String presented = (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) ? h.substring(7).trim() : null;
        if (presented == null) presented = ex.getRequestHeaders().getFirst("X-Admin-Token");
        if (presented == null) return false;
        byte[] pb = presented.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(pb, tokenBytes);
    }

    private void handlePing(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, "{\"ok\":true}");
    }

    /** Monitoring lecture-seule : joueurs en ligne (userID + ancienneté), connexions acceptées, uptime, mode strict. */
    private void handleMonitor(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, server.monitorSnapshotJson());
    }

    // ---- utilitaires HTTP ----
    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
