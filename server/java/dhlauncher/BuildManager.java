package dhlauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * LAUNCHER-CORE — GÉNÉRATION DU SERVEUR DEPUIS L'APK (chantier C2a-4, {@code docs/DISTRIBUTION.md}). On livre le
 * LOGICIEL sans le jeu ; l'utilisateur fournit son APK, et le launcher GÉNÈRE les artefacts serveur (données +
 * jar reframé) dans un dossier de SORTIE choisi, en orchestrant le pipeline reproductible EXISTANT (§4/§7) :
 * {@code tools/extract_game_data.sh} (données {@code .tab} — léger), puis {@code tools/decompile.sh} (dex2jar,
 * lourd/Maven) + reframe ({@code ReframeJar}, StackMapTable valides). Aucun artefact n'est réécrit à la main.
 *
 * <p>Job en ARRIÈRE-PLAN (le build est long) avec état interrogeable ({@code /build/status}). Écrit dans un
 * dossier de sortie distinct → n'écrase PAS le serveur courant.
 */
public final class BuildManager {

    public enum State { IDLE, RUNNING, DONE, FAILED }

    /** Cibles de génération (le launcher les proposera). {@code SERVER} = hébergement ; {@code CLIENT} = port PC
     *  Win/Linux ; {@code APK} = patch du jeu mobile (intégration découverte/serveur — incrément ULTÉRIEUR). */
    public enum Target { SERVER, CLIENT, APK }

    private final String projectDir;
    private volatile State state = State.IDLE;
    private volatile String step = "";
    private volatile String outDir = "";
    private volatile Target target = Target.SERVER;
    private final StringBuilder log = new StringBuilder();
    private Thread worker;

    public BuildManager(String projectDir) { this.projectDir = projectDir; }

    /**
     * Démarre une génération pour une CIBLE. {@code full=false} → seulement l'extraction des données (léger, sans
     * réseau) ; {@code full=true} → pipeline complet (decompile dex2jar via Maven + reframe). Idempotent tant qu'un
     * build tourne. On avance par incréments : {@code SERVER} est câblé ; {@code CLIENT}/{@code APK} sont des
     * incréments à venir (refus HONNÊTE, pas de faux succès §2).
     */
    public synchronized String start(String apkPath, String out, Target tgt, boolean full) {
        if (state == State.RUNNING) return status();
        this.target = tgt == null ? Target.SERVER : tgt;
        synchronized (log) { log.setLength(0); }
        File apk = new File(apkPath == null ? "" : apkPath);
        if (apkPath == null || apkPath.isEmpty() || !apk.isFile()) {
            state = State.FAILED; step = "apk-introuvable"; append("APK introuvable: " + apkPath);
            return status();
        }
        if (this.target != Target.SERVER) {
            state = State.FAILED; step = "cible-à-venir";
            append("Cible '" + this.target + "' : incrément à venir (le build PC/APK sera ajouté ensuite). "
                 + "Seule la cible SERVER (hébergement) est câblée pour l'instant.");
            return status();
        }
        this.outDir = (out == null || out.isEmpty()) ? new File(projectDir, "build/generated-server").getPath() : out;
        state = State.RUNNING; step = "démarrage";
        worker = new Thread(() -> runPipeline(apk, this.outDir, full), "dh-build");
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    private void runPipeline(File apk, String out, boolean full) {
        try {
            new File(out).mkdirs();
            // 1) données .tab (léger, unzip) → <out>/game-data
            runStep("extract", new String[]{"bash", tool("extract_game_data.sh"), apk.getPath()},
                    "DH_DATA_DEST", new File(out, "game-data").getPath());
            if (full) {
                // 2) décompilation dex2jar (LOURD, Maven/réseau) → libs/game.jar (emplacement standard du pipeline)
                runStep("decompile", new String[]{"bash", tool("decompile.sh"), apk.getPath()}, null, null);
                // 3) reframe (StackMapTable valides) → libs/game-framed.jar
                runStep("reframe", new String[]{"bash", "-c",
                        "ASM=$(ls " + projectDir + "/tools/reframe/asm-*.jar 2>/dev/null | head -1); "
                      + "CLS=" + projectDir + "/tools/reframe/classes; "
                      + "java -cp \"$CLS:$ASM:" + projectDir + "/libs/game.jar\" ReframeJar "
                      + projectDir + "/libs/game.jar " + projectDir + "/libs/game-framed.jar"}, null, null);
            }
            step = "done"; state = State.DONE;
        } catch (Exception e) {
            append("ÉCHEC (" + step + "): " + e.getMessage());
            state = State.FAILED;
        }
    }

    private String tool(String name) { return new File(projectDir, "tools/" + name).getPath(); }

    private void runStep(String name, String[] cmd, String envKey, String envVal) throws Exception {
        step = name; append("=== étape " + name + " ===");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(projectDir)).redirectErrorStream(true);
        if (envKey != null) pb.environment().put(envKey, envVal);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) append(line);
        }
        int rc = p.waitFor();
        if (rc != 0) throw new IllegalStateException("étape " + name + " code " + rc);
    }

    private void append(String s) {
        synchronized (log) {
            log.append(s).append('\n');
            if (log.length() > 8000) log.delete(0, log.length() - 8000);   // ne garde que la fin (tail)
        }
    }

    /** État JSON : state, step, outDir, tail du log (échappé). */
    public String status() {
        String tail; synchronized (log) { tail = log.toString(); }
        return "{\"state\":\"" + state + "\",\"target\":\"" + target + "\",\"step\":\"" + esc(step)
                + "\",\"outDir\":\"" + esc(outDir) + "\",\"log\":\"" + esc(tail) + "\"}";
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default: b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.toString();
    }
}
