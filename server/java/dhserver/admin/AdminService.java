package dhserver.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.perblue.heroes.network.messages.CampaignType;
import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.UnitType;
import dhserver.LoginServer;
import dhserver.ServerContext;
import dhserver.ServerUser;
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
        this(bind, port, token, server, store, null);
    }

    /** @param ssl si non-null → l'AdminService sert en HTTPS (jeton chiffré, chantier F) ; sinon HTTP (local loopback). */
    public AdminService(String bind, int port, String token, LoginServer server, UserStore store,
                        javax.net.ssl.SSLContext ssl) throws IOException {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("jeton admin requis");
        this.token = token;
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        this.server = server;
        this.store = store;
        String host = (bind == null || bind.isEmpty()) ? "127.0.0.1" : bind;
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(host), port);
        if (ssl != null) {
            com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(addr, 0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl));
            http = https;
        } else {
            http = HttpServer.create(addr, 0);
        }
        http.createContext("/admin/ping", guarded(this::handlePing));
        http.createContext("/admin/monitor", guarded(this::handleMonitor));
        http.createContext("/admin/releases", guarded(this::handleReleases)); // ère de contenu : liste R1…Rn
        http.createContext("/admin/release", guarded(this::handleSetRelease)); // POST {name|#idx|reset} → règle l'ère
        http.createContext("/admin/clock", guarded(this::handleClock));        // GET état | POST {offsetHours} → décale l'horloge
        // GESTION DES JOUEURS (autoritatif, À JOURNALISER) — POST {userID, …}, renvoie le résumé du compte
        http.createContext("/admin/player/lookup", guarded(this::handlePlayerLookup));
        http.createContext("/admin/player/giveResource", guarded(this::handleGiveResource));
        http.createContext("/admin/player/grantHero", guarded(this::handleGrantHero));
        http.createContext("/admin/player/setTeamLevel", guarded(this::handleSetTeamLevel));
        http.createContext("/admin/player/grantCampaign", guarded(this::handleGrantCampaign));
        http.createContext("/admin/player/completeTutorials", guarded(this::handleCompleteTutorials));
        http.createContext("/admin/player/unlock", guarded(this::handleUnlock));
        http.createContext("/admin/audit", guarded(this::handleAudit)); // GET → journal des actions admin
        // EVENTS live-ops (chantier D A) — specs {kind,…} persistées (shard_state), appliquées à chaud
        http.createContext("/admin/events/remove", guarded(this::handleEventRemove)); // POST {index}
        http.createContext("/admin/events/clear", guarded(this::handleEventClear));   // POST → tout retirer
        http.createContext("/admin/events", guarded(this::handleEvents));             // GET liste | POST {spec}
        http.createContext("/admin/enums", guarded(this::handleEnums));               // GET listes d'enums réelles
        // MODÉRATION (chantier D E — CONSTRUITE) — bans (rejet login) / mutes (anti-chat) / kick (ferme la connexion)
        http.createContext("/admin/moderation/ban", guarded(this::handleBan));
        http.createContext("/admin/moderation/unban", guarded(this::handleUnban));
        http.createContext("/admin/moderation/mute", guarded(this::handleMute));
        http.createContext("/admin/moderation/unmute", guarded(this::handleUnmute));
        http.createContext("/admin/moderation/kick", guarded(this::handleKick));
        http.createContext("/admin/moderation", guarded(this::handleModeration));     // GET bans+mutes
        http.setExecutor(null); // handlers courts, non bloquants
    }

    public void start() { http.start(); }
    public void stop()  { http.stop(0); }
    public int port()   { return http.getAddress().getPort(); }
    public boolean isTls() { return http instanceof com.sun.net.httpserver.HttpsServer; }

    /** Génère un jeton opérateur aléatoire (128 bits, URL-safe) quand l'hébergeur n'en fournit pas. */
    public static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- garde de jeton (401 sinon) ----
    private interface AdminHandler { void handle(HttpExchange ex) throws Exception; }

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

    private void handlePing(HttpExchange ex) throws Exception {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, "{\"ok\":true}");
    }

    /** Monitoring lecture-seule : joueurs en ligne (userID + ancienneté), connexions acceptées, uptime, mode strict. */
    private void handleMonitor(HttpExchange ex) throws Exception {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, server.monitorSnapshotJson());
    }

    /** GET /admin/releases → liste des releases R1…Rn (nom, date, Max TL, courante) + offset d'ère courant. */
    private void handleReleases(HttpExchange ex) throws Exception {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, ContentEra.listJson(SHARD));
    }

    /**
     * POST /admin/release {@code {name}} — règle l'ÈRE de contenu (découplée : ne touche NI sauvegardes NI timers).
     * {@code name} = nom de release ({@code R50}) ou {@code #index} ; {@code reset}/vide → ère = date réelle.
     * Applique à CHAUD (prochain BootData) + persiste ({@code content_offset_ms}). Renvoie l'état d'ère.
     */
    private void handleSetRelease(HttpExchange ex) throws Exception {
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
    private void handleClock(HttpExchange ex) throws Exception {
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

    // ─── GESTION DES JOUEURS (édition autoritative, journalisée) ─────────────────────────────────────────────────
    // ⚠️ Édite le compte PERSISTÉ. Si le joueur est EN LIGNE, la session serveur détient sa propre instance : les
    // changements ADMIN seront visibles à sa reconnexion (BootData), et une mutation faite pendant qu'il joue peut être
    // écrasée par sa session. Acceptable pour un opérateur (joueur hors ligne) — routage via l'instance vive = ultérieur.

    private void handlePlayerLookup(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        long uid = uid(form(ex));
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);   // lecture seule (pas d'audit)
        send(ex, 200, su.adminSummaryJson());
    }

    private void handleGiveResource(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        var f = form(ex);
        long uid = uid(f);
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        ResourceType type;
        long amount;
        try { type = ResourceType.valueOf(f.getOrDefault("type", "").trim().toUpperCase()); }
        catch (IllegalArgumentException e) { send(ex, 400, "{\"error\":\"type invalide\"}"); return; }
        try { amount = Long.parseLong(f.getOrDefault("amount", "").trim()); }
        catch (NumberFormatException e) { send(ex, 400, "{\"error\":\"amount\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);
        su.giveResource(type, amount);
        store.save(su);
        AdminAudit.log("giveResource", "uid=" + uid + " type=" + type + " amount=" + amount, "ok");
        send(ex, 200, su.adminSummaryJson());
    }

    private void handleGrantHero(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        var f = form(ex);
        long uid = uid(f);
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        UnitType hero;
        try { hero = UnitType.valueOf(f.getOrDefault("hero", "").trim().toUpperCase()); }
        catch (IllegalArgumentException e) { send(ex, 400, "{\"error\":\"hero invalide\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);
        String rar = f.getOrDefault("rarity", "").trim();
        if (!rar.isEmpty()) {
            Rarity rarity;
            try { rarity = Rarity.valueOf(rar.toUpperCase()); }
            catch (IllegalArgumentException e) { send(ex, 400, "{\"error\":\"rarity invalide\"}"); return; }
            int level = intOr(f, "level", 1), stars = intOr(f, "stars", 1);
            su.grantHero(hero, rarity, level, stars);
            AdminAudit.log("grantHero", "uid=" + uid + " hero=" + hero + " rarity=" + rarity + " level=" + level + " stars=" + stars, "ok");
        } else {
            su.grantHero(hero);
            AdminAudit.log("grantHero", "uid=" + uid + " hero=" + hero + " (défaut WHITE 1/1)", "ok");
        }
        store.save(su);
        send(ex, 200, su.adminSummaryJson());
    }

    private void handleSetTeamLevel(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        var f = form(ex);
        long uid = uid(f);
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        int level = intOr(f, "level", -1);
        if (level < 1) { send(ex, 400, "{\"error\":\"level\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);
        su.setTeamLevel(level);
        store.save(su);
        AdminAudit.log("setTeamLevel", "uid=" + uid + " level=" + level, "ok");
        send(ex, 200, su.adminSummaryJson());
    }

    private void handleGrantCampaign(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        var f = form(ex);
        long uid = uid(f);
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        CampaignType type;
        try { type = CampaignType.valueOf(f.getOrDefault("campaignType", "NORMAL").trim().toUpperCase()); }
        catch (IllegalArgumentException e) { send(ex, 400, "{\"error\":\"campaignType invalide\"}"); return; }
        int chapter = intOr(f, "chapter", -1), level = intOr(f, "level", -1), stars = intOr(f, "stars", 3);
        if (chapter < 1 || level < 0) { send(ex, 400, "{\"error\":\"chapter|level\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);
        su.grantCampaignLevel(type, chapter, level, stars);
        store.save(su);
        AdminAudit.log("grantCampaign", "uid=" + uid + " type=" + type + " ch=" + chapter + " lvl=" + level + " stars=" + stars, "ok");
        send(ex, 200, su.adminSummaryJson());
    }

    private void handleCompleteTutorials(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        long uid = uid(form(ex));
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);
        int n = su.completeAllTutorials();
        store.save(su);
        AdminAudit.log("completeTutorials", "uid=" + uid + " completed=" + n, "ok");
        send(ex, 200, su.adminSummaryJson());
    }

    private void handleUnlock(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        long uid = uid(form(ex));
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return; }
        ServerUser su = store.loadOrCreate(uid, SHARD);
        int granted = su.adminUnlock();
        store.save(su);
        AdminAudit.log("unlock", "uid=" + uid + " (TL300 + chapitre requis + roster JAUNE)", "granted=" + granted);
        send(ex, 200, su.adminSummaryJson());
    }

    /** GET /admin/audit?tail=N → dernières entrées du journal des actions admin. */
    private void handleAudit(HttpExchange ex) throws Exception {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        int n = 100;
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String kv : q.split("&")) if (kv.startsWith("tail=")) { try { n = Integer.parseInt(kv.substring(5)); } catch (Exception ignore) {} }
        send(ex, 200, AdminAudit.tailJson(n));
    }

    // ─── EVENTS live-ops ─────────────────────────────────────────────────────────────────────────────────────────
    /** GET /admin/events → liste des specs opérateur ; POST {@code {spec}} → ajoute une spec {kind,…} (VALIDÉE). */
    private void handleEvents(HttpExchange ex) throws Exception {
        if ("GET".equals(ex.getRequestMethod())) { send(ex, 200, EventsAdmin.listJson(store, SHARD)); return; }
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        String spec = form(ex).getOrDefault("spec", "");
        String res = EventsAdmin.addSpec(store, SHARD, spec);
        if (res == null) { send(ex, 400, "{\"error\":\"spec invalide\"}"); return; }
        AdminAudit.log("eventAdd", "spec=" + spec, "ok");
        send(ex, 200, res);
    }

    /** POST /admin/events/remove {@code {index}} → retire la spec à cet index. */
    private void handleEventRemove(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        int index = intOr(form(ex), "index", -1);
        String res = EventsAdmin.removeAt(store, SHARD, index);
        if (res == null) { send(ex, 404, "{\"error\":\"index hors bornes\"}"); return; }
        AdminAudit.log("eventRemove", "index=" + index, "ok");
        send(ex, 200, res);
    }

    /** POST /admin/events/clear → retire TOUS les overrides (rotation par défaut du jeu). */
    private void handleEventClear(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        String res = EventsAdmin.clear(store, SHARD);
        AdminAudit.log("eventClear", "-", "ok");
        send(ex, 200, res);
    }

    /** GET /admin/enums → listes d'enums RÉELLES (GameMode, ChestType, …) pour l'éditeur d'events. */
    private void handleEnums(HttpExchange ex) throws Exception {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, EventsAdmin.enumsJson());
    }

    // ─── MODÉRATION (bans / mutes / kick) ────────────────────────────────────────────────────────────────────────
    /** GET /admin/moderation → {bans:[…],mutes:[…]}. */
    private void handleModeration(HttpExchange ex) throws Exception {
        if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return; }
        send(ex, 200, Moderation.listJson());
    }

    /** POST /admin/moderation/ban {userID} → bannit (rejet au login) + kick immédiat si en ligne. */
    private void handleBan(HttpExchange ex) throws Exception {
        long uid = requireUid(ex); if (uid <= 0) return;
        String res = Moderation.addBan(store, SHARD, uid);
        boolean kicked = server.kick(uid);
        AdminAudit.log("ban", "uid=" + uid, "kicked=" + kicked);
        send(ex, 200, res);
    }

    /** POST /admin/moderation/unban {userID}. */
    private void handleUnban(HttpExchange ex) throws Exception {
        long uid = requireUid(ex); if (uid <= 0) return;
        String res = Moderation.removeBan(store, SHARD, uid);
        AdminAudit.log("unban", "uid=" + uid, "ok");
        send(ex, 200, res);
    }

    /** POST /admin/moderation/mute {userID} → interdit le chat. */
    private void handleMute(HttpExchange ex) throws Exception {
        long uid = requireUid(ex); if (uid <= 0) return;
        String res = Moderation.addMute(store, SHARD, uid);
        AdminAudit.log("mute", "uid=" + uid, "ok");
        send(ex, 200, res);
    }

    /** POST /admin/moderation/unmute {userID}. */
    private void handleUnmute(HttpExchange ex) throws Exception {
        long uid = requireUid(ex); if (uid <= 0) return;
        String res = Moderation.removeMute(store, SHARD, uid);
        AdminAudit.log("unmute", "uid=" + uid, "ok");
        send(ex, 200, res);
    }

    /** POST /admin/moderation/kick {userID} → ferme la connexion vive (non persistant). */
    private void handleKick(HttpExchange ex) throws Exception {
        long uid = requireUid(ex); if (uid <= 0) return;
        boolean kicked = server.kick(uid);
        AdminAudit.log("kick", "uid=" + uid, "kicked=" + kicked);
        send(ex, 200, "{\"kicked\":" + kicked + "}");
    }

    /** Parse+valide {userID} (POST). Envoie 405/400 et renvoie 0 si invalide (l'appelant s'arrête alors). */
    private long requireUid(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"method\"}"); return 0; }
        long uid = uid(form(ex));
        if (uid <= 0) { send(ex, 400, "{\"error\":\"userID\"}"); return 0; }
        return uid;
    }

    private static long uid(java.util.Map<String, String> f) {
        try { return Long.parseLong(f.getOrDefault("userID", "0").trim()); } catch (NumberFormatException e) { return 0L; }
    }
    private static int intOr(java.util.Map<String, String> f, String k, int def) {
        try { return Integer.parseInt(f.getOrDefault(k, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    // ---- utilitaires HTTP ----
    private static java.util.Map<String, String> form(HttpExchange ex) throws Exception {
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
