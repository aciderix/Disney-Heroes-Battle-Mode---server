package dhlauncher;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * LAUNCHER-CORE — LANCEMENT DU JEU (chantier C2b, endpoint {@code /play}). Gère le CYCLE DE VIE du CLIENT (port PC)
 * lancé <b>sur la machine du joueur</b> contre le serveur choisi : lancer / arrêter / état. Miroir de {@link HostManager}
 * mais côté client. Lance le {@code run.sh}/{@code run.bat} du <b>bundle CLIENT généré</b> (même artefact que le
 * standalone) — aucune règle réécrite (§3) : le script du bundle porte déjà la sélection JRE, natifs, spine, etc.
 *
 * <p>Identité transmise par ENV que le {@code run.sh} du client comprend déjà :
 * <ul>
 *   <li><b>{@code DH_SERVER}</b> = {@code host:contentPort} → redirige {@code ServerType.LIVE} vers le serveur choisi ;
 *   <li><b>{@code DH_USERID}</b> (mode permissif) = le userID à jouer ({@code BuildOptions.TEST_USER_ID}).
 * </ul>
 * En mode <b>strict</b>, le client boote en userID 0 et le {@code content_server} du serveur FRAPPE le billet
 * (login unique) — sur un serveur hébergé EN LOCAL par ce launcher, {@code HostManager} gère déjà ce pont. Le strict
 * vers un serveur DISTANT (injection du {@code loginRequestID} dans le {@code ClientInfo}) est un incrément séparé
 * (hook client dédié) — documenté, pas simulé.
 */
public final class PlayManager {

    @SuppressWarnings("unused")
    private final String projectDir;
    private Process client;
    private long startedAt;
    private String serverProp = "";
    private long userID;
    private boolean strict;

    public PlayManager(String projectDir) { this.projectDir = projectDir; }

    public synchronized boolean isRunning() { return client != null && client.isAlive(); }

    /**
     * Lance le CLIENT du bundle {@code clientDir} contre {@code serverProp} (=host:contentPort). En permissif, joue
     * {@code userID} ({@code DH_USERID}) ; en strict, ne passe PAS de userID (billet côté serveur). Idempotent.
     */
    public synchronized String start(String clientDir, String serverProp, long userID, boolean strict) throws IOException {
        if (isRunning()) return status();
        stopQuiet();
        File dir = new File(clientDir);
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        File script = new File(dir, win ? "run.bat" : "run.sh");
        if (!script.isFile()) throw new IOException("bundle client invalide (run script absent): " + script);
        this.serverProp = serverProp; this.userID = userID; this.strict = strict;

        ProcessBuilder pb = new ProcessBuilder(win
                ? new java.util.ArrayList<>(List.of("cmd", "/c", script.getPath()))
                : new java.util.ArrayList<>(List.of("bash", script.getPath())))
                .directory(dir).redirectErrorStream(true)
                .redirectOutput(new File(dir, "play.log"));
        pb.environment().put("DH_SERVER", serverProp);
        if (!strict && userID > 0) pb.environment().put("DH_USERID", String.valueOf(userID));
        client = pb.start();
        startedAt = System.currentTimeMillis();
        return status();
    }

    /** Arrête le client. Idempotent. */
    public synchronized String stop() { stopQuiet(); return status(); }

    private void stopQuiet() {
        if (client != null) {
            client.destroy();
            try { if (!client.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) client.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        client = null; startedAt = 0;
    }

    /** État JSON : running, pid, serveur, userID, strict, uptime. */
    public synchronized String status() {
        boolean running = isRunning();
        long up = running && startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        long pid = running ? client.pid() : -1;
        return "{\"running\":" + running
                + ",\"pid\":" + pid
                + ",\"server\":\"" + serverProp + "\""
                + ",\"userID\":" + userID
                + ",\"strict\":" + strict
                + ",\"uptimeMs\":" + up + "}";
    }
}
