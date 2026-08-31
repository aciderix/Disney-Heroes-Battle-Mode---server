package dhserver.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dhserver.LoginServer;
import dhserver.ServerContext;
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
    /** Shard du serveur hébergé (mono-shard pour l'auto-hébergement ; multi-shard = chantier D ultérieur). */
    private static final int SHARD = 1;

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
        http.createContext("/admin/releases", guarded(this::handleReleases)); // ère de contenu : liste R1…Rn
        http.createContext("/admin/release", guarded(this::handleSetRelease)); // POST {name|#idx|reset} → règle l'ère
        http.createContext("/admin/clock", guarded(this::handleClock));        // GET état | POST {offsetHours} → décale l'horloge
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

    /** GET /admin/releases → liste des releases R1…Rn (nom, date, Max TL, courante) + offset d'ère courant. */
    private void handleReleases(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, ContentEra.listJson(SHARD));
    }

    /**
     * POST /admin/release {@code {name}} — règle l'ÈRE de contenu (découplée : ne touche NI sauvegardes NI timers).
     * {@code name} = nom de release ({@code R50}) ou {@code #index} ; {@code reset}/vide → ère = date réelle.
     * Applique à CHAUD (prochain BootData) + persiste ({@code content_offset_ms}). Renvoie l'état d'ère.
     */
    private void handleSetRelease(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        String sel = form(ex).getOrDefault("name", "").trim();
        try {
            if (sel.isEmpty() || "reset".equalsIgnoreCase(sel)) {
                ContentEra.resetRelease(store);
            } else {
                var target = ContentEra.resolve(SHARD, sel);
                if (target == null) { send(ex, 404, "{\"error\":\"release introuvable\"}"); return; }
                ContentEra.applyRelease(store, target);
            }
            send(ex, 200, ContentEra.statusJson(SHARD));
        } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /**
     * GET /admin/clock → état horloge ({@code offsetMs}, dates jeu/réelle). POST {@code {offsetHours}} → décale
     * l'HORLOGE ENTIÈRE (ère + tous les timers), persiste ({@code clock_offset_ms}). ⚠️ Puissant (mode test) :
     * distinct du release-picker qui, lui, ne touche que l'ère. {@code offsetHours} positif = avancer le temps de jeu.
     */
    private void handleClock(HttpExchange ex) throws IOException {
        try {
            if ("POST".equals(ex.getRequestMethod())) {
                double hours = 0;
                try { hours = Double.parseDouble(form(ex).getOrDefault("offsetHours", "0").trim()); }
                catch (NumberFormatException e) { send(ex, 400, "{\"error\":\"offsetHours\"}"); return; }
                long offsetMs = Math.round(-hours * 3600_000d); // serverTimeNow = now − OFFSET → positif d'heures = avance
                store.setMetaLong("clock_offset_ms", offsetMs);
                ServerContext.setClockOffsetMillis(offsetMs);
            } else if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
            long off = ServerContext.clockOffsetMillis();
            send(ex, 200, "{\"offsetMs\":" + off
                    + ",\"gameDate\":" + ContentEra.jsonStr(ContentEra.fmt(com.perblue.heroes.util.TimeUtil.serverTimeNow()))
                    + ",\"realDate\":" + ContentEra.jsonStr(ContentEra.fmt(System.currentTimeMillis())) + "}");
        } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    // ---- utilitaires HTTP ----
    private static java.util.Map<String, String> form(HttpExchange ex) throws IOException {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8), java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
