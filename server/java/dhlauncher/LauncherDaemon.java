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
    static {
        // ADMIN DISTANT TLS (chantier F) — on ÉPINGLE l'empreinte SHA-256 exacte du certificat distant (cf. PinnedTls),
        // ce qui est STRICTEMENT plus fort que la vérification du nom d'hôte. Le HttpClient du JDK force pourtant celle-ci
        // (endpointIdentificationAlgorithm=HTTPS, non désactivable par SSLParameters — JDK-8213311). On la neutralise via
        // cette propriété : SANS RISQUE ici car ce daemon (game-free, dédié) ne fait AUCUN appel HTTPS non épinglé (le
        // serveur de contenu/jeu local est en HTTP clair ; les cibles admin distantes sont toutes épinglées). Doc SHIMS.md.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private final HttpServer http;
    private final HttpClient client = HttpClient.newHttpClient();
    private final LauncherConfig config = new LauncherConfig();
    private final String projectDir = System.getProperty("dh.launcher.projectdir", System.getProperty("user.dir", "."));
    private final HostManager host = new HostManager(projectDir);
    private final BuildManager build = new BuildManager(projectDir);
    private final PlayManager play = new PlayManager(projectDir);
    private final SettingsManager settings = new SettingsManager();
    /** ADMIN DISTANT (chantier F) — cible d'administration : {@code null} = serveur LOCAL hébergé (défaut) ; sinon un
     *  serveur DISTANT (cloud). Base URL de son AdminService (ex. {@code http://1.2.3.4:8083}) + jeton opérateur. */
    private volatile String adminRemoteUrl;
    private volatile String adminRemoteToken;
    /** Client HTTP épinglé (TLS) pour une cible HTTPS distante ; {@code null} = client par défaut (local ou http/CA). */
    private volatile HttpClient adminRemoteHttp;
    /** ANNUAIRE (brique 3) — URL + clé anon (PUBLIQUE) de l'annuaire communautaire (Supabase). Résolus depuis l'env
     *  (DH_DIRECTORY_URL/PROJECT_URL ; DH_DIRECTORY_ANON_KEY/ANON_PUBLIC/PUBLISHABLE_KEY). {@code null} = annuaire non
     *  configuré → /directory renvoie 503 (pas de faux OK). */
    private final String directoryUrl = firstNonBlank(System.getProperty("dh.directory.url"),
            System.getenv("DH_DIRECTORY_URL"), System.getenv("PROJECT_URL"));
    private final String directoryKey = firstNonBlank(System.getProperty("dh.directory.anonkey"),
            System.getenv("DH_DIRECTORY_ANON_KEY"), System.getenv("ANON_PUBLIC"), System.getenv("PUBLISHABLE_KEY"));
    private final ServerInfoVerifier verifier = new ServerInfoVerifier();

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
        http.createContext("/directory/verify", this::directoryVerify); // ANNUAIRE (brique 3) — vérifie 1 fiche via /info
        http.createContext("/directory", this::directoryList);          // ANNUAIRE (brique 3) — liste communautaire (Supabase)
        http.createContext("/host/start", this::hostStart);     // C2a-3 : héberger en local
        http.createContext("/host/stop", this::hostStop);
        http.createContext("/host/status", this::hostStatus);
        http.createContext("/build/start", this::buildStart);   // C2a-4 : générer le serveur depuis l'APK
        http.createContext("/build/status", this::buildStatus);
        http.createContext("/play", this::playStart);           // C2b : lancer le CLIENT sur le serveur choisi
        http.createContext("/play/stop", this::playStop);
        http.createContext("/play/status", this::playStatus);
        http.createContext("/settings", this::settingsHandler); // C2b : réglages locaux (GET état | POST fusion)
        // ADMIN DISTANT (chantier F) — choisir la cible : local (hébergé) ou distant (URL+jeton). Ces contextes sont
        // PLUS SPÉCIFIQUES que "/admin/" → traités ICI (daemon), pas proxifiés.
        http.createContext("/admin/target/clear", this::adminTargetClear); // POST → repli sur le serveur local
        http.createContext("/admin/target", this::adminTarget);            // GET état | POST {adminUrl, token}
        http.createContext("/admin/", this::adminProxy);          // chantier D : proxy GÉNÉRIQUE de tout /admin/* (jeton injecté)
        http.createContext("/host/logs", this::hostLogs);         // chantier D : tail des logs hôte
        http.setExecutor(null);
    }

    public void start() { http.start(); }
    public void stop()  { http.stop(0); }
    public int port()   { return http.getAddress().getPort(); }

    /**
     * ANNUAIRE (bug #4) — charge {@code directory.env} (placé À CÔTÉ DU JAR par {@code build_launcher.sh}) et pose
     * {@code dh.directory.url}/{@code dh.directory.anonkey} en propriétés système, que les champs {@code directoryUrl}/
     * {@code directoryKey} lisent EN PREMIER. Nécessaire parce que le binaire GUI (Tauri/Rust) spawn {@code java -cp
     * dhlauncher.jar …} DIRECTEMENT, sans jamais sourcer {@code run-launcher.sh} (le seul endroit qui lisait ce fichier).
     * L'ENV réel garde la priorité : on ne pose la propriété QUE si ni la propriété ni la variable d'env correspondante
     * ne sont déjà définies. Rien de secret ici (URL + clé anon PUBLIQUE ; jamais de service_role).
     */
    static void loadDirectoryEnv() {
        try {
            java.io.File jar = new java.io.File(
                LauncherDaemon.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            java.io.File dir = jar.isFile() ? jar.getParentFile() : jar;   // jar → son dossier ; classes → le dossier
            java.io.File[] candidates = {
                new java.io.File(dir, "directory.env"),
                new java.io.File(System.getProperty("user.dir", "."), "directory.env"),
            };
            for (java.io.File f : candidates) {
                if (f == null || !f.isFile()) continue;
                for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    if ("DH_DIRECTORY_URL".equals(k))       maybeSet("dh.directory.url", v, "DH_DIRECTORY_URL", "PROJECT_URL");
                    else if ("DH_DIRECTORY_ANON_KEY".equals(k)) maybeSet("dh.directory.anonkey", v, "DH_DIRECTORY_ANON_KEY", "ANON_PUBLIC", "PUBLISHABLE_KEY");
                }
                return;   // premier directory.env trouvé = celui qu'on utilise
            }
        } catch (Exception e) {
            System.out.println("[launcher] directory.env non chargé (" + e + ") — annuaire via env/props seulement");
        }
    }
    /** Pose la propriété {@code prop=val} SEULEMENT si ni cette propriété ni aucune des variables d'env {@code envs} n'est déjà définie. */
    private static void maybeSet(String prop, String val, String... envs) {
        if (val == null || val.isEmpty()) return;
        if (firstNonBlank(System.getProperty(prop)) != null) return;      // propriété déjà posée → priorité
        for (String e : envs) if (firstNonBlank(System.getenv(e)) != null) return; // env déjà défini → priorité
        System.setProperty(prop, val);
    }

    /**
     * POINT D'ENTRÉE du launcher distribué (package clé-en-main, cf. {@code tools/build_launcher.sh} +
     * {@code .github/workflows/launcher-release.yml}). Args : {@code --port <n>} (défaut 8090, ou
     * {@code -Ddh.launcher.port}) ; {@code --project <dir>} (racine du tooling embarqué → pose
     * {@code dh.launcher.projectdir} AVANT construction). Lié à loopback uniquement. Le front (Tauri/React, C2b)
     * s'y connecte en HTTP local ; en attendant, les endpoints sont utilisables tels quels (curl / tests).
     */
    public static void main(String[] args) throws Exception {
        int port = 8090;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--port".equals(args[i]))    { try { port = Integer.parseInt(args[i + 1]); } catch (Exception ignore) {} }
            else if ("--project".equals(args[i])) { System.setProperty("dh.launcher.projectdir", args[i + 1]); }
        }
        port = Integer.getInteger("dh.launcher.port", port);
        loadDirectoryEnv();                                // ANNUAIRE : lit directory.env À CÔTÉ DU JAR (le binaire GUI
                                                           // Tauri spawn `java` sans sourcer run-launcher.sh — bug #4)
        LauncherDaemon d = new LauncherDaemon(port);       // lit dh.launcher.projectdir posé ci-dessus
        d.start();
        System.out.println("[launcher] daemon local sur http://127.0.0.1:" + d.port()
            + " (projectDir=" + System.getProperty("dh.launcher.projectdir", System.getProperty("user.dir", ".")) + ")");
        System.out.println("[launcher] endpoints : /health /identity/* /servers* /host/* /build/*  (loopback only)");
        Thread.currentThread().join();                     // garde le process vivant (daemon)
    }

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

    /** POST /host/start {[bundleDir], [contentPort=8080], [gamePort=8081], [authPort=8082], [strict=false]} → héberge.
     *  Si {@code bundleDir} est fourni → lance le BUNDLE généré (run.sh/run.bat = même artefact que le standalone) ;
     *  sinon → serveur depuis le classpath courant (dev). */
    private void hostStart(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        boolean strict = "true".equalsIgnoreCase(f.getOrDefault("strict", "false")) || "1".equals(f.getOrDefault("strict", ""));
        String bundleDir = f.getOrDefault("bundleDir", "");
        int cp = intOr(f, "contentPort", 8080), gp = intOr(f, "gamePort", 8081), ap = intOr(f, "authPort", 8082);
        try {
            send(ex, 200, bundleDir.isEmpty() ? host.start(cp, gp, ap, strict)
                                              : host.startBundle(bundleDir, cp, gp, ap, strict));
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

    /**
     * POST /build/start {apkPath, [target=server|client|apk], [outDir], [full=false]} → génère depuis l'APK (async).
     * Pour {@code target=apk} (brique 4b) : {@code serverHost} + {@code serverPort} (le serveur cible de la redirection)
     * sont requis → l'APK est redirigé + re-signé (`tools/patch_apk.sh`).
     */
    private void buildStart(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        boolean full = "true".equalsIgnoreCase(f.getOrDefault("full", "false")) || "1".equals(f.getOrDefault("full", ""));
        BuildManager.Target tgt;
        try { tgt = BuildManager.Target.valueOf(f.getOrDefault("target", "server").trim().toUpperCase()); }
        catch (Exception e) { send(ex, 400, "{\"error\":\"target invalide (server|client|apk)\"}"); return; }
        boolean pkg = !("false".equalsIgnoreCase(f.getOrDefault("pkg", "true")) || "0".equals(f.getOrDefault("pkg", "")));
        if (tgt == BuildManager.Target.APK) {
            String mode = f.getOrDefault("apkMode", "redirect").trim();
            if ("picker".equalsIgnoreCase(mode)) {
                // brique 4c — écran de choix : alimenté par l'annuaire (doit être configuré sur ce launcher)
                if (directoryUrl == null || directoryKey == null) {
                    send(ex, 400, "{\"error\":\"annuaire non configuré (DH_DIRECTORY_URL/ANON_KEY) — requis pour target=apk mode=picker\"}"); return;
                }
                build.setApkPicker(directoryUrl, directoryKey);
            } else {
                String host = f.getOrDefault("serverHost", "").trim();
                int port = intOr(f, "serverPort", 0);
                if (host.isEmpty() || port <= 0) { send(ex, 400, "{\"error\":\"serverHost + serverPort requis pour target=apk mode=redirect\"}"); return; }
                build.setApkTarget(host, port);
            }
        }
        send(ex, 200, build.start(f.getOrDefault("apkPath", ""), f.getOrDefault("outDir", ""), tgt, full, pkg));
    }

    /** GET /build/status → état du build (state, step, outDir, tail du log). */
    private void buildStatus(HttpExchange ex) throws IOException {
        send(ex, 200, build.status());
    }

    /**
     * POST /play {clientDir, serverId | serverHost[+contentPort], [userID], [strict]} → lance le CLIENT (port PC) du
     * bundle {@code clientDir} contre le serveur choisi. Le serveur est résolu soit par {@code serverId} (favori,
     * → host:contentPort), soit par {@code serverHost}/{@code contentPort} directs. En permissif, {@code userID}
     * précise le compte joué (DH_USERID) ; en strict, le billet est frappé côté serveur (login unique).
     */
    private void playStart(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        try {
            String clientDir = f.getOrDefault("clientDir", "");
            if (clientDir.isEmpty()) { send(ex, 400, "{\"error\":\"clientDir requis\"}"); return; }
            boolean strict = "true".equalsIgnoreCase(f.getOrDefault("strict", "false")) || "1".equals(f.getOrDefault("strict", ""));
            long userID = 0L; try { userID = Long.parseLong(f.getOrDefault("userID", "0").trim()); } catch (Exception ignore) {}
            String serverProp;
            String serverId = f.getOrDefault("serverId", "");
            if (!serverId.isEmpty()) {
                LauncherConfig.Server s = config.get(serverId);
                if (s == null) { send(ex, 404, "{\"error\":\"serverId inconnu\"}"); return; }
                serverProp = s.serverProp();
            } else {
                String hostName = f.getOrDefault("serverHost", "127.0.0.1");
                serverProp = hostName + ":" + intOr(f, "contentPort", 8080);
            }
            send(ex, 200, play.start(clientDir, serverProp, userID, strict));
        } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /** POST /play/stop → arrête le client lancé. */
    private void playStop(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        send(ex, 200, play.stop());
    }

    /** GET /play/status → état du client (running, pid, server, userID, strict, uptime). */
    private void playStatus(HttpExchange ex) throws IOException {
        send(ex, 200, play.status());
    }

    /** GET /settings → réglages locaux (JSON) ; POST /settings {clés connues} → fusionne + persiste + renvoie l'état. */
    private void settingsHandler(HttpExchange ex) throws IOException {
        try {
            if ("GET".equals(ex.getRequestMethod())) { send(ex, 200, settings.toJson()); return; }
            if (!post(ex)) return;
            send(ex, 200, settings.update(form(ex)));
        } catch (Exception e) { send(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /**
     * ADMIN (chantier D) — PROXY GÉNÉRIQUE de tout {@code /admin/*} vers l'{@code AdminService} du serveur LOCAL
     * hébergé (le daemon connaît son URL + jeton car c'est lui qui a démarré le serveur), en injectant le jeton
     * opérateur et en relayant méthode + chemin + query + corps. Le daemon reste GAME-FREE (simple relais HTTP).
     * Aucun serveur local hébergé → 503 (pas de faux OK). Générique → les futurs endpoints admin (joueurs, events,
     * modération) passent sans modif ici. (L'admin d'un serveur DISTANT/cloud = incrément ultérieur, chantier F.)
     */
    private void adminProxy(HttpExchange ex) throws IOException {
        // Cible : DISTANTE si définie (/admin/target), sinon le serveur LOCAL hébergé.
        String remote = adminRemoteUrl;
        String baseUrl, tok;
        HttpClient hc;
        if (remote != null) { baseUrl = remote; tok = adminRemoteToken; hc = adminRemoteHttp != null ? adminRemoteHttp : client; }
        else { baseUrl = host.adminBaseUrl(); tok = host.adminToken(); hc = client; }
        if (baseUrl == null || tok == null) { send(ex, 503, "{\"error\":\"admin indisponible (héberge un serveur local, ou définis une cible distante via /admin/target)\"}"); return; }
        String path = ex.getRequestURI().getRawPath();
        String q = ex.getRequestURI().getRawQuery();
        String url = baseUrl + path + (q != null && !q.isEmpty() ? "?" + q : "");
        byte[] body = ex.getRequestBody().readAllBytes();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("X-Admin-Token", tok);
            if ("POST".equals(ex.getRequestMethod())) {
                b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else { b.GET(); }
            HttpResponse<String> r = hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
            send(ex, r.statusCode(), r.body());
        } catch (Exception e) { send(ex, 502, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}"); }
    }

    /**
     * ADMIN DISTANT (chantier F) — GET /admin/target → cible courante ({@code {mode:"local"}} ou
     * {@code {mode:"remote",baseUrl:...}}). POST {@code {adminUrl, token}} → bascule sur un serveur DISTANT, VALIDÉ par
     * un {@code /admin/ping} authentifié (rejette une URL/jeton faux → 502). Si {@code adminUrl} est en {@code https://}
     * ET qu'une {@code caFingerprint} (SHA-256 du cert, imprimée par le serveur au boot) est fournie, le jeton transite
     * CHIFFRÉ via un client TLS ÉPINGLÉ (cf. {@link #pinnedClient}) — recommandé pour un serveur exposé sur Internet.
     * En {@code http://} clair, passer par un tunnel SSH / VPN.
     */
    private void adminTarget(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            send(ex, 200, adminRemoteUrl != null ? "{\"mode\":\"remote\",\"baseUrl\":" + jstr(adminRemoteUrl) + "}" : "{\"mode\":\"local\"}");
            return;
        }
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        String u = trimSlash(f.getOrDefault("adminUrl", "").trim());
        String t = f.getOrDefault("token", "").trim();
        String fp = f.getOrDefault("caFingerprint", "").trim();   // empreinte SHA-256 à épingler (HTTPS auto-signé)
        if (u.isEmpty() || t.isEmpty()) { send(ex, 400, "{\"error\":\"adminUrl|token requis\"}"); return; }
        HttpClient hc = client;
        if (u.startsWith("https://") && !fp.isEmpty()) {
            try { hc = pinnedClient(fp); }
            catch (Exception e) { send(ex, 400, "{\"error\":\"empreinte TLS invalide\"}"); return; }
        }
        try {
            HttpResponse<String> r = adminGet(hc, u + "/admin/ping", t);
            if (r.statusCode() != 200) { send(ex, 502, "{\"error\":\"ping distant → HTTP " + r.statusCode() + " (URL ou jeton invalide)\"}"); return; }
        } catch (Exception e) { send(ex, 502, "{\"error\":\"serveur distant injoignable ou TLS non épinglé (" + e.getClass().getSimpleName() + ")\"}"); return; }
        adminRemoteUrl = u; adminRemoteToken = t; adminRemoteHttp = (hc != client ? hc : null);
        send(ex, 200, "{\"mode\":\"remote\",\"baseUrl\":" + jstr(u) + ",\"tls\":" + u.startsWith("https://") + "}");
    }

    /** POST /admin/target/clear → repli sur le serveur LOCAL hébergé. */
    private void adminTargetClear(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        adminRemoteUrl = null; adminRemoteToken = null; adminRemoteHttp = null;
        send(ex, 200, "{\"mode\":\"local\"}");
    }

    private HttpResponse<String> adminGet(HttpClient hc, String url, String token) throws Exception {
        return hc.send(HttpRequest.newBuilder(URI.create(url)).header("X-Admin-Token", token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Client HTTP épinglé sur l'empreinte SHA-256 d'un cert (HTTPS auto-signé du serveur distant), vérif hôte désactivée. */
    private HttpClient pinnedClient(String fingerprint) throws Exception {
        javax.net.ssl.SSLContext ctx = PinnedTls.pinning(fingerprint);
        javax.net.ssl.SSLParameters p = ctx.getDefaultSSLParameters();
        p.setEndpointIdentificationAlgorithm(null);   // on épingle le cert exact → pas de vérif de nom d'hôte
        return HttpClient.newBuilder().sslContext(ctx).sslParameters(p).build();
    }

    /** GET /host/logs?which=server|content&tail=N → N dernières lignes du log hôte (lecture fichier locale, game-free). */
    private void hostLogs(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { deny405(ex); return; }
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        send(ex, 200, host.tailLog(q.getOrDefault("which", "server"), intOr(q, "tail", 200)));
    }

    private static int intOr(Map<String, String> f, String k, int def) {
        try { return Integer.parseInt(f.getOrDefault(k, "").trim()); } catch (Exception e) { return def; }
    }

    /**
     * ANNUAIRE (brique 3) — GET /directory → liste des serveurs communautaires (proxy de lecture de la table Supabase,
     * clé anon PUBLIQUE). 503 si l'annuaire n'est pas configuré (pas de faux OK). La liste renvoyée est BRUTE (issue de
     * la table) ; le launcher DOIT re-vérifier chaque fiche via {@code POST /directory/verify} avant de faire confiance.
     */
    private void directoryList(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { deny405(ex); return; }
        if (directoryUrl == null || directoryKey == null) {
            send(ex, 503, "{\"error\":\"annuaire non configuré (DH_DIRECTORY_URL / DH_DIRECTORY_ANON_KEY)\"}"); return;
        }
        try {
            String url = trimSlash(directoryUrl) + "/rest/v1/servers?select=pub_key,name,mode,game_version,"
                + "server_version,address,info_url,online,max_online,open_time,updated_at&order=updated_at.desc";
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("apikey", directoryKey).header("Authorization", "Bearer " + directoryKey).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            send(ex, r.statusCode(), r.body());
        } catch (Exception e) { send(ex, 502, "{\"error\":\"annuaire injoignable (" + e.getClass().getSimpleName() + ")\"}"); }
    }

    /**
     * ANNUAIRE (brique 3) — POST /directory/verify {infoUrl} → interroge le {@code /info} du serveur avec un défi frais
     * et VÉRIFIE la signature (game-free {@link ServerInfoVerifier}). 200 = fiche prouvée + vivante (JSON vérifié) ;
     * 502 = injoignable ou signature invalide (fiche à écarter). C'est ce qui empêche de faire confiance à la table seule.
     */
    private void directoryVerify(HttpExchange ex) throws IOException {
        if (!post(ex)) return;
        Map<String, String> f = form(ex);
        String infoUrl = f.getOrDefault("infoUrl", "").trim();
        if (infoUrl.isEmpty()) { send(ex, 400, "{\"error\":\"infoUrl requis\"}"); return; }
        try {
            ServerInfoVerifier.Verified v = verifier.verify(infoUrl);
            send(ex, 200, v.toJson());
        } catch (Exception e) { send(ex, 502, "{\"reachable\":false,\"error\":" + jstr(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "}"); }
    }

    private static String firstNonBlank(String... v) {
        if (v != null) for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }

    /** Parse une query-string {@code a=b&c=d} en map (valeurs url-décodées). */
    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            m.put(dec(i < 0 ? kv : kv.substring(0, i)), i < 0 ? "" : dec(kv.substring(i + 1)));
        }
        return m;
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
        deny405(ex); return false;
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
        cors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
    /**
     * En-têtes CORS sur TOUTES les réponses. La fenêtre Tauri charge le front en {@code tauri://localhost} /
     * {@code https://tauri.localhost} — origine DIFFÉRENTE de {@code http://127.0.0.1:<port>} où tourne ce daemon.
     * WebKitGTK (Linux) comme WebView2 (Windows) appliquent CORS même en loopback : sans {@code Access-Control-Allow-Origin}
     * le {@code fetch} du front échoue (« Launcher local injoignable ») alors que le serveur a bien répondu 200. Pas de
     * cookies/credentials → {@code *} suffit. (Rapport bug launcher #1, reproduit Linux+Windows.)
     */
    private static void cors(HttpExchange ex) {
        com.sun.net.httpserver.Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Max-Age", "86400");
    }
    /** 405 « méthode non permise » AVEC en-têtes CORS (sinon le front lit un échec réseau opaque, pas le 405). */
    private static void deny405(HttpExchange ex) throws IOException {
        cors(ex);
        ex.sendResponseHeaders(405, -1);
        ex.close();
    }
    private static String json(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("champ '" + key + "' absent");
        return m.group(1);
    }
    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String jstr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String dec(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }
}
