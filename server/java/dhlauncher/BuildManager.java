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
    private volatile boolean doPackage = true;
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
        return start(apkPath, out, tgt, full, true);
    }

    /** @param pkg si {@code true} (défaut), assemble le BUNDLE autonome (packaging) ; {@code false} = données seules. */
    public synchronized String start(String apkPath, String out, Target tgt, boolean full, boolean pkg) {
        if (state == State.RUNNING) return status();
        this.target = tgt == null ? Target.SERVER : tgt;
        this.doPackage = pkg;
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
            // 4) PACKAGING clé-en-main : assemble un serveur AUTONOME lançable hors dev (C2a-4-pkg).
            if (doPackage) packageServer(new File(out));
            step = "done"; state = State.DONE;
        } catch (Exception e) {
            append("ÉCHEC (" + step + "): " + e.getMessage());
            state = State.FAILED;
        }
    }

    private String tool(String name) { return new File(projectDir, "tools/" + name).getPath(); }

    /** Jars runtime du serveur (mêmes que le classpath de run-online.sh, hors classes serveur compilées). */
    private static final String[] RUNTIME_JARS = {
        "game-framed.jar", "commons-logging.jar", "sqlite-jdbc.jar", "slf4j-api.jar", "joda-time.jar"
    };

    /**
     * PACKAGING (C2a-4-pkg) — assemble un BUNDLE serveur AUTONOME dans {@code out} : {@code lib/} (jars runtime +
     * {@code dhserver.jar} = classes serveur compilées), {@code content_server.py}, {@code game-data/} (déjà extrait),
     * {@code run.sh}/{@code run.bat}. Lançable hors de l'arbre de dev (clé-en-main, §DISTRIBUTION).
     */
    private void packageServer(File out) throws Exception {
        step = "package"; append("=== étape package (bundle serveur autonome) ===");
        File lib = new File(out, "lib"); lib.mkdirs();
        new File(out, "data").mkdirs();
        File libsSrc = new File(projectDir, "libs");

        // 1) jars runtime
        for (String j : RUNTIME_JARS) {
            File src = new File(libsSrc, j);
            if (!src.isFile()) throw new IllegalStateException("jar runtime absent: " + src
                    + " (lancer un build 'full' pour générer game-framed.jar, ou vérifier libs/)");
            java.nio.file.Files.copy(src.toPath(), new File(lib, j).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        append("jars runtime copiés (" + RUNTIME_JARS.length + ")");

        // 2) compile les classes serveur (dhserver + dhlauncher) → lib/dhserver.jar
        File cls = new java.io.File(out, "_classes"); cls.mkdirs();
        String rtCp = String.join(File.pathSeparator, java.util.Arrays.stream(RUNTIME_JARS)
                .map(j -> new File(lib, j).getPath()).toArray(String[]::new));
        java.util.List<String> srcFiles = new java.util.ArrayList<>();
        java.nio.file.Files.walk(new File(projectDir, "server/java").toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> srcFiles.add(p.toString()));
        java.util.List<String> javac = new java.util.ArrayList<>(java.util.List.of(
                javaBin("javac"), "-cp", rtCp, "-d", cls.getPath()));
        javac.addAll(srcFiles);
        runStep("compile-server", javac.toArray(new String[0]), null, null);
        runStep("jar-server", new String[]{ javaBin("jar"), "cf", new File(lib, "dhserver.jar").getPath(),
                "-C", cls.getPath(), "." }, null, null);
        deleteRec(cls);

        // 3) content_server.py
        java.nio.file.Files.copy(new File(projectDir, "server/content_server.py").toPath(),
                new File(out, "content_server.py").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // 4) scripts de lancement (bundle-relatifs, self-contained)
        writeExec(new File(out, "run.sh"), RUN_SH);
        writeText(new File(out, "run.bat"), RUN_BAT);
        append("bundle prêt : " + out.getPath() + " (run.sh / run.bat)");
    }

    private static String javaBin(String tool) {
        String home = System.getProperty("java.home");
        File f = new File(home, "bin/" + tool);
        return f.isFile() ? f.getPath() : tool;   // repli PATH
    }

    private static void writeText(File f, String s) throws java.io.IOException {
        java.nio.file.Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeExec(File f, String s) throws java.io.IOException {
        writeText(f, s);
        f.setExecutable(true, false);
    }
    private static void deleteRec(File f) {
        File[] k = f.listFiles(); if (k != null) for (File c : k) deleteRec(c); f.delete();
    }

    private static final String RUN_SH =
        "#!/usr/bin/env bash\n"
      + "# Serveur Disney Heroes (self-host) — bundle autonome généré par le launcher. Lançable hors dev.\n"
      + "set -uo pipefail\n"
      + "DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
      + "CONTENT_PORT=\"${DH_CONTENT_PORT:-8080}\"; GAME_PORT=\"${DH_GAME_PORT:-8081}\"; AUTH_PORT=\"${DH_AUTH_PORT:-8082}\"\n"
      + "mkdir -p \"$DIR/data\"\n"
      + "python3 \"$DIR/content_server.py\" --port \"$CONTENT_PORT\" --rewrite-host \"127.0.0.1:$CONTENT_PORT\" \\\n"
      + "        --game-server \"127.0.0.1:$GAME_PORT\" & CPID=$!\n"
      + "java -XX:TieredStopAtLevel=1 ${DH_SERVER_OPTS:-} -Ddh.db=\"$DIR/data/dh-server.db\" \\\n"
      + "     -Ddh.stats=\"$DIR/game-data/stats\" -Ddh.auth.port=\"$AUTH_PORT\" \\\n"
      + "     -cp \"$DIR/lib/*\" dhserver.LoginServer \"$GAME_PORT\" & JPID=$!\n"
      // les deux en arrière-plan + wait : le trap survit (contrairement à exec) → arrêt PROPRE des DEUX process
      // que l'arrêt vienne d'un Ctrl-C (standalone) ou d'un SIGTERM (bouton « arrêter » du launcher).
      + "trap 'kill $CPID $JPID 2>/dev/null' TERM INT EXIT\n"
      + "wait $JPID\n";

    private static final String RUN_BAT =
        "@echo off\r\n"
      + "REM Serveur Disney Heroes (self-host) — bundle autonome. Lancable hors dev.\r\n"
      + "set DIR=%~dp0\r\n"
      + "if \"%DH_CONTENT_PORT%\"==\"\" set DH_CONTENT_PORT=8080\r\n"
      + "if \"%DH_GAME_PORT%\"==\"\" set DH_GAME_PORT=8081\r\n"
      + "if \"%DH_AUTH_PORT%\"==\"\" set DH_AUTH_PORT=8082\r\n"
      + "if not exist \"%DIR%data\" mkdir \"%DIR%data\"\r\n"
      + "start \"dh-content\" python \"%DIR%content_server.py\" --port %DH_CONTENT_PORT% --rewrite-host 127.0.0.1:%DH_CONTENT_PORT% --game-server 127.0.0.1:%DH_GAME_PORT%\r\n"
      + "java -XX:TieredStopAtLevel=1 -Ddh.db=\"%DIR%data\\dh-server.db\" -Ddh.stats=\"%DIR%game-data\\stats\" -Ddh.auth.port=%DH_AUTH_PORT% -cp \"%DIR%lib\\*\" dhserver.LoginServer %DH_GAME_PORT%\r\n";

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
