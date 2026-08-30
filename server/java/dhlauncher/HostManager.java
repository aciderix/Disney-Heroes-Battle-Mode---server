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

        ProcessBuilder pb = new ProcessBuilder(win
                ? new java.util.ArrayList<>(List.of("cmd", "/c", script.getPath()))
                : new java.util.ArrayList<>(List.of("bash", script.getPath())))
                .directory(dir).redirectErrorStream(true)
                .redirectOutput(new File(dir, "host.log"));
        pb.environment().put("DH_CONTENT_PORT", String.valueOf(contentPort));
        pb.environment().put("DH_GAME_PORT", String.valueOf(gamePort));
        pb.environment().put("DH_AUTH_PORT", String.valueOf(authPort));
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

        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = System.getProperty("java.class.path");   // le daemon embarque déjà dhserver + les jars du jeu
        File dataDir = new File(projectDir, "server/data");
        dataDir.mkdirs();
        String db = new File(dataDir, "dh-server.db").getPath();
        String stats = new File(projectDir, "game-data/stats").getPath();
        File mintFile = new File(dataDir, "host-mint-userid");   // pont identité launcher → content_server (strict)
        if (strict) { try { new java.io.FileWriter(mintFile, false).close(); } catch (IOException ignore) {} }

        // 1) serveur de jeu TCP + AuthService (miroir exact de run-online.sh)
        List<String> srv = new ArrayList<>(List.of(java, "-XX:TieredStopAtLevel=1",
                "-Ddh.db=" + db, "-Ddh.stats=" + stats, "-Ddh.auth.port=" + authPort));
        if (strict) srv.add("-Ddh.auth=on");
        srv.add("-cp"); srv.add(cp);
        srv.add("dhserver.LoginServer"); srv.add(String.valueOf(gamePort));
        server = new ProcessBuilder(srv)
                .directory(new File(projectDir))
                .redirectErrorStream(true)
                .redirectOutput(new File(dataDir, "host-server.log"))
                .start();

        // 2) content_server.py (login/contenu HTTP)
        List<String> cs = new ArrayList<>(List.of("python3",
                new File(projectDir, "server/content_server.py").getPath(),
                "--port", String.valueOf(contentPort),
                "--rewrite-host", "127.0.0.1:" + contentPort,
                "--game-server", "127.0.0.1:" + gamePort));
        ProcessBuilder cpb = new ProcessBuilder(cs)
                .directory(new File(projectDir))
                .redirectErrorStream(true)
                .redirectOutput(new File(dataDir, "host-content.log"));
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
