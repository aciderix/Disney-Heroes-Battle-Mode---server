package dhlauncher;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * LAUNCHER-CORE — HÉBERGEMENT LOCAL (chantier C2a-3, {@code docs/LAUNCHER.md} §2.6 « version minimale »). Gère le
 * CYCLE DE VIE d'un serveur de jeu hébergé <b>sur la machine du joueur</b> : lancer / arrêter / état. C'est
 * volontairement MINIMAL (auto-hébergement) : le panneau opérateur complet (ère de contenu, events, guerre,
 * modération, monitoring) = chantier D ; le cloud + sécurité réseau internet = chantier F.
 *
 * <p>Reproduit exactement le lancement de {@code desktop-port/run-online.sh} (aucune règle réécrite, §3) : le
 * serveur de jeu ({@code dhserver.LoginServer <gamePort>}, {@code AuthService} sur {@code dh.auth.port}) + le
 * {@code content_server.py} (login/contenu HTTP). Les deux tournent en PROCESS gérés par le daemon (état vivant).
 */
public final class HostManager {

    private final String projectDir;
    private Process server;
    private Process content;
    private long startedAt;
    private int contentPort, gamePort, authPort;
    private int adminPort;        // ADMIN (chantier D) : port de l'AdminService du serveur (0 = indisponible)
    private String adminToken;    // ADMIN : jeton opérateur généré par le daemon, passé au serveur (proxifié par /admin/*)
    private File dataDir;         // dossier des logs hôte (host-server.log / host-content.log ou host.log en bundle)
    private boolean strict;
    private boolean bundleMode;   // true = un seul process (run.sh du bundle, qui lance content+serveur en interne)

    public HostManager(String projectDir) { this.projectDir = projectDir; }

    /** Vivant ? En mode dev = serveur ET content_server ; en mode BUNDLE = le seul process run.sh. */
    public synchronized boolean isRunning() {
        if (bundleMode) return server != null && server.isAlive();
        return server != null && server.isAlive() && content != null && content.isAlive();
    }

    /**
     * Héberge un BUNDLE serveur AUTONOME généré (C2a-4-pkg) : lance son {@code run.sh}/{@code run.bat} — le MÊME
     * artefact que le standalone → le bouton « Héberger » du launcher exécute exactement ce qui se double-clique.
     * Un seul process géré (le script lance content_server + serveur en interne, et les arrête ensemble au SIGTERM).
     */
    public synchronized String startBundle(String bundleDir, int contentPort, int gamePort, int authPort, boolean strict) throws IOException {
        if (isRunning()) return status();
        stopQuiet();
        File dir = new File(bundleDir);
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        File script = new File(dir, win ? "run.bat" : "run.sh");
        if (!script.isFile()) throw new IOException("bundle invalide (run script absent): " + script);
        this.contentPort = contentPort; this.gamePort = gamePort; this.authPort = authPort;
        this.strict = strict; this.bundleMode = true; this.content = null;
        // ADMIN en mode BUNDLE (g260) : RUN_BAT_/RUN_SH_ (BuildManager) relaient DÉJÀ DH_ADMIN_BIND/DH_ADMIN_PORT
        // (-D) + DH_ADMIN_TOKEN (env, lu directement par LoginServer — jamais sur la ligne de commande, absent de
        // `ps`) depuis longtemps (chantier F, admin distant) — seul HostManager ne les générait/passait jamais
        // pour SON PROPRE lancement bundle local. Même convention que le mode dev : port = authPort+1, jeton
        // aléatoire par session. Glue uniquement (§1) : le script du bundle n'est pas modifié, on finit de
        // câbler ce qu'il sait déjà faire.
        this.adminPort = authPort + 1;
        this.adminToken = java.util.UUID.randomUUID().toString().replace("-", "");
        this.dataDir = dir;

        ProcessBuilder pb = new ProcessBuilder(win
                ? new java.util.ArrayList<>(List.of("cmd", "/c", script.getPath()))
                : new java.util.ArrayList<>(List.of("bash", script.getPath())))
                .directory(dir).redirectErrorStream(true)
                .redirectOutput(new File(dir, "host.log"));
        pb.environment().put("DH_CONTENT_PORT", String.valueOf(contentPort));
        pb.environment().put("DH_GAME_PORT", String.valueOf(gamePort));
        pb.environment().put("DH_AUTH_PORT", String.valueOf(authPort));
        pb.environment().put("DH_ADMIN_BIND", "127.0.0.1");
        pb.environment().put("DH_ADMIN_PORT", String.valueOf(adminPort));
        pb.environment().put("DH_ADMIN_TOKEN", adminToken);
        if (strict) pb.environment().put("DH_SERVER_OPTS", "-Ddh.auth=on");
        server = pb.start();
        startedAt = System.currentTimeMillis();
        return status();
    }

    /**
     * Démarre le serveur local. Idempotent : si déjà en cours, renvoie l'état courant sans relancer. Le mode
     * {@code strict} active l'auth mnémonique ({@code -Ddh.auth=on}) + le pont {@code /auth/mint} du content_server.
     */
    public synchronized String start(int contentPort, int gamePort, int authPort, boolean strict) throws IOException {
        if (isRunning()) return status();
        // nettoie un démarrage précédent partiellement mort
        stopQuiet();
        this.contentPort = contentPort; this.gamePort = gamePort; this.authPort = authPort; this.strict = strict;
        this.bundleMode = false;
        // ADMIN : le daemon GÉNÈRE le jeton opérateur et le passe au serveur → il le connaît sans lire les logs (le
        // proxy /admin/* l'injecte). Port = authPort+1 (127.0.0.1 par défaut). Cf. AdminService (option A locale).
        this.adminPort = authPort + 1;
        this.adminToken = java.util.UUID.randomUUID().toString().replace("-", "");

        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = System.getProperty("java.class.path");   // le daemon embarque déjà dhserver + les jars du jeu
        File dataDir = new File(projectDir, "server/data");
        dataDir.mkdirs();
        this.dataDir = dataDir;
        String db = new File(dataDir, "dh-server.db").getPath();
        String stats = new File(projectDir, "game-data/stats").getPath();
        File mintFile = new File(dataDir, "host-mint-userid");   // pont identité launcher → content_server (strict)
        if (strict) { try { new java.io.FileWriter(mintFile, false).close(); } catch (IOException ignore) {} }

        // 1) serveur de jeu TCP + AuthService (miroir exact de run-online.sh)
        List<String> srv = new ArrayList<>(List.of(java, "-XX:TieredStopAtLevel=1",
                "-Ddh.db=" + db, "-Ddh.stats=" + stats, "-Ddh.auth.port=" + authPort,
                "-Ddh.admin.port=" + adminPort, "-Ddh.admin.bind=127.0.0.1", "-Ddh.admin.token=" + adminToken));
        if (strict) srv.add("-Ddh.auth=on");
        srv.add("-cp"); srv.add(cp);
        srv.add("dhserver.LoginServer"); srv.add(String.valueOf(gamePort));
        server = new ProcessBuilder(srv)
                .directory(new File(projectDir))
                .redirectErrorStream(true)
                .redirectOutput(new File(dataDir, "host-server.log"))
                .start();

        // 2) content_server.py (login/contenu HTTP)
        List<String> cs = new ArrayList<>(List.of(pythonBin(),
                new File(projectDir, "server/content_server.py").getPath(),
                "--port", String.valueOf(contentPort),
                "--rewrite-host", "127.0.0.1:" + contentPort,
                "--game-server", "127.0.0.1:" + gamePort));
        ProcessBuilder cpb = new ProcessBuilder(cs)
                .directory(new File(projectDir))
                .redirectErrorStream(true)
                .redirectOutput(new File(dataDir, "host-content.log"));
        cpb.environment().put("PYTHONIOENCODING", "utf-8");
        if (strict) {
            cpb.environment().put("DH_AUTH_URL", "http://127.0.0.1:" + authPort);
            cpb.environment().put("DH_MINT_USERID_FILE", mintFile.getPath());
        }
        content = cpb.start();

        startedAt = System.currentTimeMillis();
        return status();
    }

    /** Arrête proprement le serveur + content_server. Idempotent. */
    public synchronized String stop() {
        stopQuiet();
        return status();
    }

    private void stopQuiet() {
        if (content != null) { content.destroy(); }
        if (server != null)  { server.destroy(); }
        // laisse une chance à l'arrêt propre puis force
        try {
            if (content != null && !content.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) content.destroyForcibly();
            if (server != null && !server.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        server = null; content = null; startedAt = 0;
        adminPort = 0; adminToken = null;
    }

    /** ADMIN — base de l'AdminService du serveur hébergé (proxy {@code /admin/*}), ou {@code null} si indisponible
     *  (arrêté). Loopback : le serveur écoute {@code 127.0.0.1:adminPort} (mode dev ET mode bundle, g260). */
    public synchronized String adminBaseUrl() {
        if (!isRunning() || adminPort <= 0) return null;
        return "http://127.0.0.1:" + adminPort;
    }

    /** ADMIN — jeton opérateur à injecter dans les requêtes proxifiées vers l'AdminService, ou {@code null}. */
    public synchronized String adminToken() {
        return isRunning() ? adminToken : null;
    }

    /**
     * MONITORING — {@code n} dernières lignes d'un log hôte. {@code which} = {@code server} (host-server.log) ou
     * {@code content} (host-content.log) en mode dev ; en mode bundle, un seul {@code host.log}. Renvoie du JSON
     * {@code {"which":..,"lines":[..]}} ({@code lines} vide si le fichier n'existe pas encore).
     */
    public synchronized String tailLog(String which, int n) {
        String file = bundleMode ? "host.log" : ("content".equalsIgnoreCase(which) ? "host-content.log" : "host-server.log");
        File f = (dataDir != null) ? new File(dataDir, file) : null;
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (f != null && f.isFile()) {
            try {
                java.util.List<String> all = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                int from = Math.max(0, all.size() - Math.max(1, Math.min(n, 1000)));
                lines = all.subList(from, all.size());
            } catch (IOException ignore) { /* fichier en cours d'écriture : renvoie ce qu'on a */ }
        }
        StringBuilder sb = new StringBuilder("{\"which\":\"").append("content".equalsIgnoreCase(which) ? "content" : "server")
                .append("\",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(lines.get(i)));
        }
        return sb.append("]}").toString();
    }

    /** Échappe une chaîne pour l'inclure dans du JSON (guillemets, backslash, caractères de contrôle). */
    private static String jsonStr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Résout un interpréteur Python UTILISABLE : le PYTHON EMBARQUÉ (frère de {@code projectDir}, déposé par
     *  {@code tools/build_launcher.sh} dans {@code runtime/python/}) en priorité, sinon {@code python3}/{@code python}
     *  du PATH — mais seulement après un test d'EXÉCUTION réel (pas juste une présence de fichier) : sur Windows,
     *  "python3"/"python" nus peuvent résoudre vers le stub "App Execution Alias" du Windows Store — un fichier bien
     *  réel que {@code where}/{@code File.isFile()} trouvent, mais qui échoue au lancement (exit 49, "Python was not
     *  found… Microsoft Store") si aucune app Store n'y est liée. Même famille que g251 #11 (patch_apk.sh), vue EN
     *  JEU dans ce code-ci (content_server.py ne démarrait jamais lors de « Héberger »). */
    private String pythonBin() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        File launcherRoot = new File(projectDir).getParentFile();
        if (launcherRoot != null) {
            File embedded = win ? new File(launcherRoot, "runtime/python/python.exe")
                                 : new File(launcherRoot, "runtime/python/bin/python3");
            if (embedded.isFile()) return embedded.getPath();
        }
        for (String cand : new String[]{"python3", "python"}) {
            if (runsOk(cand)) return cand;
        }
        throw new IllegalStateException("Aucun interpréteur Python utilisable (python3/python introuvable, ou stub "
                + "Windows Store non configuré — Réglages Windows > Applications > Alias d'exécution d'application)");
    }

    /** Test d'EXÉCUTION (pas de présence) : seul moyen fiable de distinguer un vrai Python d'un stub Windows Store. */
    private static boolean runsOk(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "--version").redirectErrorStream(true).start();
            return p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    /** Le port de jeu TCP accepte-t-il des connexions ? (le process peut être vivant mais pas encore en écoute). */
    private boolean gamePortListening() {
        if (gamePort <= 0) return false;
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", gamePort), 300); return true; }
        catch (Exception e) { return false; }
    }

    /** État JSON : running, écoute du port de jeu, ports, PIDs, uptime. */
    public synchronized String status() {
        boolean running = isRunning();
        long up = running && startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        long srvPid = server != null && server.isAlive() ? server.pid() : -1;
        long csPid = content != null && content.isAlive() ? content.pid() : -1;
        return "{\"running\":" + running
                + ",\"gamePortListening\":" + (running && gamePortListening())
                + ",\"contentPort\":" + contentPort
                + ",\"gamePort\":" + gamePort
                + ",\"authPort\":" + authPort
                + ",\"strict\":" + strict
                + ",\"serverPid\":" + srvPid
                + ",\"contentPid\":" + csPid
                + ",\"uptimeMs\":" + up + "}";
    }
}
