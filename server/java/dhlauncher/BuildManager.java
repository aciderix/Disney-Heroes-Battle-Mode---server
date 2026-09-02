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
    /** PATCH APK (brique 4b) — serveur cible de la redirection (hôte + port), posé avant un build de cible {@code APK}. */
    private volatile String apkHost;
    private volatile int apkPort;
    /** PATCH APK (brique 4c) — mode ÉCRAN DE CHOIX : si {@code apkDirUrl != null}, on injecte le picker (annuaire) au lieu
     *  d'une redirection fixe. {@code apkDirUrl}/{@code apkDirKey} = URL + clé anon PUBLIQUE de l'annuaire. */
    private volatile String apkDirUrl;
    private volatile String apkDirKey;
    private final StringBuilder log = new StringBuilder();
    private Thread worker;

    public BuildManager(String projectDir) { this.projectDir = projectDir; }

    /** PATCH APK (brique 4b) — redirection FIXE vers {@code host:port}. Efface un éventuel mode picker. */
    public void setApkTarget(String host, int port) {
        this.apkHost = host == null ? null : host.trim(); this.apkPort = port;
        this.apkDirUrl = null; this.apkDirKey = null;
    }

    /** PATCH APK (brique 4c) — mode ÉCRAN DE CHOIX : injecte le picker alimenté par l'annuaire {@code dirUrl} (clé anon publique). */
    public void setApkPicker(String dirUrl, String anonKey) {
        this.apkDirUrl = dirUrl == null ? null : dirUrl.trim(); this.apkDirKey = anonKey;
        this.apkHost = "picker"; this.apkPort = 1;   // marqueurs (la validation « host+port » de start() passe)
    }

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
        if (this.target == Target.APK) {
            // PATCH APK (brique 4b) — redirige l'APK du joueur vers un serveur choisi, puis re-signe (tools/patch_apk.sh).
            if (apkHost == null || apkHost.isEmpty() || apkPort <= 0) {
                state = State.FAILED; step = "apk-cible-manquante";
                append("Patch APK : hôte + port du serveur cible requis (setApkTarget). Ex. 192.168.1.20:8080.");
                return status();
            }
        }
        String def = this.target == Target.CLIENT ? "build/generated-client"
                   : this.target == Target.APK ? "build/generated-apk" : "build/generated-server";
        this.outDir = (out == null || out.isEmpty()) ? new File(projectDir, def).getPath() : out;
        state = State.RUNNING; step = "démarrage";
        final Target t = this.target;
        worker = new Thread(() -> {
            if (t == Target.CLIENT) runClientPipeline(apk, this.outDir);
            else if (t == Target.APK) runApkPipeline(apk, this.outDir);
            else runPipeline(apk, this.outDir, full);
        }, "dh-build");
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    private void runPipeline(File apk, String out, boolean full) {
        try {
            new File(out).mkdirs();
            // Résout un éventuel XAPK (base + splits config, ex. téléchargements APKPure/APKMirror) en APK
            // universel AVANT extract/decompile — sinon `unzip assets/stats/*` / dex2jar échouent (le base.apk
            // seul d'un XAPK n'a que le code jeu, pas forcément les assets nécessaires selon la source ; miroir
            // du même correctif déjà appliqué côté patch APK g230 et côté build client g244).
            File resolved = resolveApk(apk, new File(out));
            // 1) données .tab (léger, unzip) → <out>/game-data
            runStep("extract", new String[]{bashBin(), tool("extract_game_data.sh"), resolved.getPath()},
                    "DH_DATA_DEST", new File(out, "game-data").getPath());
            if (full) {
                // 2) décompilation dex2jar (LOURD, Maven/réseau) → libs/game.jar (emplacement standard du pipeline)
                runStep("decompile", new String[]{bashBin(), tool("decompile.sh"), resolved.getPath()}, null, null);
                // 3) reframe (StackMapTable valides) → libs/game-framed.jar. AUTO-SUFFISANT : sur un package FRAIS,
                //    ni ASM ni ReframeJar.class ne sont présents (artefacts dérivés) → on les prépare ici (télécharge
                //    ASM 9.7 + compile ReframeJar.java) avant de lancer, comme run-desktop.sh. Utilise le JDK EMBARQUÉ.
                // pd = projectDir en séparateurs "/" (cosmétique/robustesse, cf. plus bas pourquoi ce n'est PAS le
                // vrai correctif de fond).
                String pd = projectDir.replace('\\', '/');
                File gameJar = new File(projectDir, "libs/game.jar"), framedJar = new File(projectDir, "libs/game-framed.jar");
                if (framedJar.isFile() && framedJar.lastModified() > gameJar.lastModified()) {
                    // IDEMPOTENT (perf) : game-framed.jar déjà à jour (plus récent que game.jar) → reframe (64k+
                    // classes) SAUTÉ. Supprimer libs/game-framed.jar pour forcer une régénération.
                    append("=== étape reframe ===\nlibs/game-framed.jar déjà à jour → SAUTÉ");
                } else {
                // SCRIPT ÉCRIT DANS UN FICHIER, PAS "bash -c <chaîne>" : vérifié EN JEU que `bash -c "<script complexe
                // multi-guillemets>"` lancé via ProcessBuilder sur Windows fait REPARSER par MSYS/bash la ligne de
                // commande reconstruite par CreateProcess → les "\" de chemins Windows ABSOLUS embarqués (ex.
                // javaBin() = "C:\Users\...\javac.exe") sont MANGÉS (perdus) même correctement doublement-quotés côté
                // Java (`ProcessBuilder` et l'argv-parser de MSYS bash n'utilisent PAS la même convention
                // d'échappement pour une chaîne -c à guillemets imbriqués) → commande totalement mal formée
                // (`java` affichait sa propre AIDE, ou `bash: ...javac.exe: command not found` selon la casse).
                // Écrire le script dans un FICHIER (bash le lit par I/O normal, aucun réencodage CreateProcess) et
                // lancer `bash <fichier>` (un SEUL argv simple, sans guillemets imbriqués) élimine le problème à la
                // racine — prouvé : le MÊME script, avec les MÊMES chemins "\" de javaBin(), réussit (64196 classes)
                // via fichier alors qu'il échouait systématiquement via -c.
                File scriptFile = new File(projectDir, "tools/reframe/_run_reframe.sh");
                String script = "set -e\n"
                      + "RF=" + pd + "/tools/reframe; ASM=\"$RF/asm-9.7.jar\"\n"
                      + "[ -f \"$ASM\" ] || curl -fsSL -o \"$ASM\" https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar\n"
                      + "CLS=\"$RF/classes\"\n"
                      + "if [ ! -f \"$CLS/ReframeJar.class\" ]; then\n"
                      + "  mkdir -p \"$CLS\"\n"
                      // -encoding UTF-8 EXPLICITE : sans lui, javac utilise l'encodage PAR DÉFAUT de l'OS (ex.
                      // windows-1252 sur un Windows en locale FR) → "unmappable character" sur les commentaires
                      // accentués/emoji du dépôt (UTF-8) — vérifié EN JEU (plantait la génération serveur Windows).
                      + "  \"" + javaBin("javac") + "\" -encoding UTF-8 -cp \"$ASM\" -d \"$CLS\" \"$RF/src/ReframeJar.java\"\n"
                      + "fi\n"
                      // Séparateur de classpath OS-CORRECT (File.pathSeparator = ";" Windows / ":" Linux-macOS) :
                      // un ":" en dur casse sur Windows (ambigu avec la lettre de lecteur "C:", vérifié EN JEU —
                      // `java -cp "C:\a:C:\b" X` échoue, `;` fonctionne).
                      + "\"" + javaBin("java") + "\" -cp \"$CLS" + File.pathSeparator + "$ASM" + File.pathSeparator + pd + "/libs/game.jar\" ReframeJar "
                      + pd + "/libs/game.jar " + pd + "/libs/game-framed.jar\n";
                new File(projectDir, "tools/reframe").mkdirs();
                java.nio.file.Files.write(scriptFile.toPath(), script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runStep("reframe", new String[]{bashBin(), scriptFile.getPath()}, null, null);
                }
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

    /** Résout l'APK à traiter : si l'entrée est un XAPK/.apks (zip contenant des .apk imbriqués — base + splits
     *  config, format typique des téléchargements APKPure/APKMirror), le FUSIONNE en APK UNIVERSEL (APKEditor),
     *  comme {@code tools/apk_inject_picker.sh}/{@code patch_apk.sh} (g230) et {@code desktop-port/run-desktop.sh}
     *  (g244) le font déjà pour leurs propres pipelines. Sinon renvoie l'APK tel quel, inchangé. Détection et
     *  fusion en JAVA PUR (ZipFile + HttpClient déjà utilisé par {@link #downloadTo}) — aucune dépendance shell,
     *  donc fiable identiquement sur tous les OS. Résultat mis en cache dans {@code <out>/universal.apk}
     *  (idempotent : ne refusionne pas si déjà présent). */
    private File resolveApk(File apk, File out) throws Exception {
        boolean isXapk;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
            isXapk = zf.stream().anyMatch(e -> e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk"));
        }
        if (!isXapk) return apk;
        File universal = new File(out, "universal.apk");
        if (!universal.isFile()) {
            append("XAPK détecté → fusion en APK universel (APKEditor) ...");
            File cache = new File(projectDir, "libs/apktools"); cache.mkdirs();
            File editor = new File(cache, "APKEditor.jar");
            if (!editor.isFile()) downloadTo(
                "https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar", editor);
            runStep("xapk-merge", new String[]{javaBin("java"), "-jar", editor.getPath(),
                "m", "-i", apk.getPath(), "-o", universal.getPath()}, null, null);
        }
        return universal;
    }

    /**
     * Pipeline APK (brique 4b) — redirige l'APK du joueur vers le serveur choisi ({@link #setApkTarget}) et le re-signe,
     * via {@code tools/patch_apk.sh} (baksmali/smali + uber-apk-signer). Sortie = {@code <out>/dh-<host>.apk}. On NE
     * redistribue PAS l'APK (le joueur fournit et patche le sien, §7) ; à installer HORS store.
     */
    private void runApkPipeline(File apk, String out) {
        try {
            new File(out).mkdirs();
            String outApk, done;
            if (apkDirUrl != null && !apkDirUrl.isEmpty()) {
                // brique 4c — ÉCRAN DE CHOIX injecté (alimenté par l'annuaire)
                outApk = new File(out, "dh-picker.apk").getPath();
                runStep("apk-picker", new String[]{bashBin(), tool("apk_inject_picker.sh"), apk.getPath(),
                        apkDirUrl, apkDirKey == null ? "" : apkDirKey, outApk}, embeddedToolsEnv());
                done = "APK avec écran de choix prêt : " + outApk + " (au lancement, sélection du serveur ; installer hors store)";
            } else {
                // brique 4b — redirection FIXE
                outApk = new File(out, "dh-" + apkHost.replaceAll("[^0-9A-Za-z._-]", "_") + ".apk").getPath();
                runStep("apk-patch", new String[]{bashBin(), tool("patch_apk.sh"), apk.getPath(),
                        apkHost, Integer.toString(apkPort), outApk}, embeddedToolsEnv());
                done = "APK patché prêt : " + outApk + " (redirige vers http://" + apkHost + ":" + apkPort + " ; installer hors store)";
            }
            if (!new File(outApk).isFile()) throw new IllegalStateException("APK patché absent en sortie");
            append(done);
            step = "done"; state = State.DONE;
        } catch (Exception e) {
            append("ÉCHEC (" + step + "): " + e.getMessage()); state = State.FAILED;
        }
    }

    /**
     * Pipeline CLIENT (C2a-4b) — construit le port PC (game-logic-framed + gradle + assets + natifs via
     * {@code run-desktop.sh DH_BUILD_ONLY=1}), puis assemble un BUNDLE client AUTONOME (bundle-relatif) : lib/,
     * assets/, resources/, native/, run.sh/run.bat lançant {@code dhdesktop.DesktopLauncher}.
     */
    private void runClientPipeline(File apk, String out) {
        try {
            new File(out).mkdirs();
            // 1) build-only : game-logic-framed + gradle compile + extraction assets/ressources + natifs + manifeste.
            //    On PASSE l'APK du joueur (DH_APK) → run-desktop.sh en extrait les assets (il n'est PAS dans le tooling).
            java.util.Map<String,String> cbEnv = embeddedToolsEnv();
            cbEnv.put("DH_BUILD_ONLY", "1");
            cbEnv.put("DH_APK", apk.getAbsolutePath());
            runStep("client-build", new String[]{bashBin(), new File(projectDir, "desktop-port/run-desktop.sh").getPath()}, cbEnv);
            java.util.Map<String,String> m = new java.util.HashMap<>();
            File manifest = new File(projectDir, "desktop-port/build/client-manifest.env");
            for (String line : java.nio.file.Files.readAllLines(manifest.toPath())) {
                int i = line.indexOf('='); if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
            }
            packageClient(new File(out), m);
            step = "done"; state = State.DONE;
        } catch (Exception e) {
            append("ÉCHEC (" + step + "): " + e.getMessage()); state = State.FAILED;
        }
    }

    /** Assemble le bundle client depuis le manifeste (chemins ABSOLUS des artefacts construits). */
    private void packageClient(File out, java.util.Map<String,String> m) throws Exception {
        step = "package-client"; append("=== étape package-client (bundle port PC autonome) ===");
        File lib = new File(out, "lib"); lib.mkdirs();
        File runtime = new File(lib, "runtime"); runtime.mkdirs();
        File nat = new File(out, "native"); nat.mkdirs();
        new File(out, "data").mkdirs();

        // classes desktop-port → lib/dhdesktop.jar
        String classes = req(m, "CLASSES");
        File clientJar = new File(lib, "dhdesktop.jar");
        runStep("jar-client", new String[]{ javaBin("jar"), "cf", clientJar.getPath(), "-C", classes, "." }, null, null);
        // GARDE-FOU (bug #5) : ne JAMAIS déclarer le build réussi avec un jar client VIDE (compilation gradle échouée
        // en amont mais avalée). Sans ce contrôle, "Jouer" crashait « ClassNotFoundException dhdesktop.DesktopLauncher »
        // sans aucune erreur visible. On exige au moins une classe .class dans le jar.
        int classCount = 0;
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(clientJar)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) if (en.nextElement().getName().endsWith(".class")) { classCount++; if (classCount > 0) break; }
        }
        if (classCount == 0)
            throw new java.io.IOException("build client INVALIDE : dhdesktop.jar ne contient AUCUNE classe (la compilation "
                + "du client a échoué — voir la sortie de run-desktop.sh). Client non lançable ; build interrompu.");
        // game-logic-framed.jar (doit précéder les jars runtime → ombrage le game-logic original)
        copyFile(new File(req(m, "FRAMED")), new File(lib, "game-logic-framed.jar"));
        // jars runtime (LWJGL3, gdx, unidbg…) → lib/runtime/
        for (String j : req(m, "RUNTIME_CP").split(File.pathSeparator)) {
            j = j.trim(); if (j.isEmpty()) continue; File src = new File(j);
            if (src.isFile()) copyFile(src, new File(runtime, src.getName()));
        }
        // natifs : libgdx64.so (NATDIR), libspine-native.so (ARM d'origine), libhostspine64.so (jni, optionnel)
        File natdir = new File(req(m, "NATDIR"));
        if (natdir.isDirectory()) for (File f : natdir.listFiles()) if (f.isFile()) copyFile(f, new File(nat, f.getName()));
        File spineLib = new File(req(m, "SPINE_LIB")); if (spineLib.isFile()) copyFile(spineLib, new File(nat, spineLib.getName()));
        // hostspine (backend spine jni rapide, optionnel) : préserver l'extension d'origine (.so Linux / .dll Windows)
        // — run.sh cherche libhostspine64.so, run.bat cherche libhostspine64.dll.
        String host = m.getOrDefault("HOSTSPINE", ""); if (!host.isEmpty() && new File(host).isFile()) copyFile(new File(host), new File(nat, new File(host).getName()));
        // assets + ressources (accédés en chemins relatifs par le jeu ; sur le classpath)
        append("copie des assets (~283 Mo) + ressources ...");
        copyDir(new File(req(m, "ASSETS")).toPath(), new File(out, "assets").toPath());
        copyDir(new File(req(m, "RESD")).toPath(), new File(out, "resources").toPath());
        // runtime JRE embarqué (zéro-install : le port PC n'a pas besoin de python)
        packageRuntime(out);
        // scripts de lancement
        writeExec(new File(out, "run.sh"), RUN_SH_CLIENT);
        writeText(new File(out, "run.bat"), RUN_BAT_CLIENT);
        append("bundle client prêt : " + out.getPath() + " (run.sh / run.bat)");
    }

    private static String req(java.util.Map<String,String> m, String k) {
        String v = m.get(k); if (v == null || v.isEmpty()) throw new IllegalStateException("manifeste : clé absente " + k); return v;
    }
    private static void copyFile(File a, File b) throws java.io.IOException {
        java.nio.file.Files.copy(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private static void copyDir(java.nio.file.Path src, java.nio.file.Path dst) throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(src)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) w::iterator) {
                java.nio.file.Path t = dst.resolve(src.relativize(p));
                if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(t);
                else { java.nio.file.Files.createDirectories(t.getParent());
                       java.nio.file.Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

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
        // -encoding UTF-8 : mêmes raisons que le javac de ReframeJar (repli Windows locale FR = CP1252, casse
        // sur les sources UTF-8 du dépôt).
        java.util.List<String> javac = new java.util.ArrayList<>(java.util.List.of(
                javaBin("javac"), "-encoding", "UTF-8", "-cp", rtCp, "-d", cls.getPath()));
        javac.addAll(srcFiles);
        runStep("compile-server", javac.toArray(new String[0]), null, null);
        runStep("jar-server", new String[]{ javaBin("jar"), "cf", new File(lib, "dhserver.jar").getPath(),
                "-C", cls.getPath(), "." }, null, null);
        deleteRec(cls);

        // 3) content_server.py + index.txt (manifeste de contenu). SANS index.txt, content_server meurt
        //    (« index introuvable ») → /live/index.txt KO → le client ne télécharge aucun asset. run.sh passe --index.
        java.nio.file.Files.copy(new File(projectDir, "server/content_server.py").toPath(),
                new File(out, "content_server.py").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        File srvIdx = new File(projectDir, "index.txt");
        if (srvIdx.isFile()) java.nio.file.Files.copy(srvIdx.toPath(), new File(out, "index.txt").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        else append("⚠ index.txt absent du projet — le serveur ne pourra pas servir /live/index.txt");

        // 4) runtimes embarqués (zéro-install) : JRE (jlink) pour le serveur Java + Python (relocatable) pour le
        //    content-server (désormais pur stdlib, sans `curl`). Best-effort : repli sur les runtimes système sinon.
        packageRuntime(out);
        packagePython(out);
        // 5) scripts de lancement (bundle-relatifs, self-contained)
        writeExec(new File(out, "run.sh"), RUN_SH);
        writeText(new File(out, "run.bat"), RUN_BAT);
        append("bundle prêt : " + out.getPath() + " (run.sh / run.bat)");
    }

    /** Environnement pour un sous-processus BASH qui invoque des outils NUS en interne (javac/java/jar/python3 —
     *  {@code run-desktop.sh}, {@code patch_apk.sh}, {@code apk_inject_picker.sh}) : fait précéder le PATH par le
     *  {@code bin/} du JDK EMBARQUÉ et le dossier du PYTHON EMBARQUÉ (frère de {@code projectDir}, {@code
     *  tools/build_launcher.sh} l'y dépose) — sans ça, ces outils résolvent via le PATH hérité du daemon vers un
     *  AUTRE JDK/Python système (ex. le "python3" nu peut même résoudre vers le stub Windows Store "App Execution
     *  Alias" si aucun Python système n'est installé) → versions incohérentes ou binaire absent. Vérifié EN JEU
     *  (Windows) : `UnsupportedClassVersionError` (JDK) et « Python was not found… Microsoft Store » (python3) SANS
     *  ce correctif. Nouvelle Map à chaque appel (mutable, l'appelant y ajoute ses propres variables). */
    private java.util.Map<String,String> embeddedToolsEnv() {
        java.util.Map<String,String> env = new java.util.HashMap<>();
        StringBuilder pre = new StringBuilder();
        pre.append(new File(System.getProperty("java.home"), "bin").getPath());
        File launcherRoot = new File(projectDir).getParentFile();
        if (launcherRoot != null) {
            File py = new File(launcherRoot, "runtime/python");
            if (py.isDirectory()) pre.append(File.pathSeparator).append(py.getPath());
        }
        // Git for Windows : coreutils (dirname/mkdir/basename/curl…) vivent dans <Git>/usr/bin, git dans <Git>/bin.
        // Le PATH hérité du daemon (lancé par double-clic, PATH Explorateur mis en cache) peut NE PAS les contenir,
        // et un `bash <script>` NON-login ne source pas /etc/profile → il ne se rajoute pas /usr/bin tout seul →
        // « dirname/mkdir/basename: command not found » (code 127) sur client-build ET apk-picker (vu EN JEU g254).
        // On les ajoute EXPLICITEMENT (dérivés du bash.exe résolu par bashBin()). Sans effet hors Windows.
        for (String d : windowsGitBinDirs()) pre.append(File.pathSeparator).append(d);
        String inheritedPath = System.getenv("PATH");
        env.put("PATH", pre + File.pathSeparator + (inheritedPath != null ? inheritedPath : ""));
        // Windows (locale FR) : stdout/stdin Python par défaut en cp1252 (PAS UTF-8) → UnicodeEncodeError sur tout
        // caractère hors cp1252 (ex. "→" U+2192) dans un print() des scripts .py du dépôt. Même famille que
        // `javac -encoding UTF-8` (g249). Vérifié EN JEU.
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }

    /** Dossiers Git for Windows à mettre sur le PATH des sous-processus bash (coreutils + git + éventuels outils
     *  mingw), dérivés du chemin absolu de {@code bash.exe} résolu par {@link #bashBin()}. Vide hors Windows ou si
     *  bash introuvable. Corrige « dirname/mkdir/basename/curl: command not found » sur les étapes qui utilisent
     *  {@link #embeddedToolsEnv()} (client-build, apk-picker, apk-patch). */
    private static java.util.List<String> windowsGitBinDirs() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return dirs;
        try {
            File binDir = new File(bashBin()).getParentFile();   // <Git>/bin  ou  <Git>/usr/bin
            if (binDir == null) return dirs;
            File gitRoot;
            if (binDir.getName().equalsIgnoreCase("bin") && binDir.getParentFile() != null
                    && binDir.getParentFile().getName().equalsIgnoreCase("usr")) {
                gitRoot = binDir.getParentFile().getParentFile();   // bash etait dans <Git>/usr/bin
            } else {
                gitRoot = binDir.getParentFile();                   // bash etait dans <Git>/bin
            }
            if (gitRoot != null) {
                for (String rel : new String[]{"usr\\bin", "bin", "mingw64\\bin"}) {
                    File d = new File(gitRoot, rel);
                    if (d.isDirectory()) dirs.add(d.getPath());
                }
            }
        } catch (Exception ignore) { /* bash introuvable → l'étape aura déjà échoué en amont */ }
        return dirs;
    }

    private static String javaBin(String tool) {
        String home = System.getProperty("java.home");
        // Sur Windows, le binaire réel est "<tool>.exe" — File.isFile() ne matche PAS "javac" (sans extension) même
        // si "javac.exe" existe (vérifié EN JEU : repli silencieux sur le "javac"/"java" NU du PATH → résout vers un
        // AUTRE JDK système, potentiellement d'une version DIFFÉRENTE de l'embarqué → UnsupportedClassVersionError
        // en aval, masqué par un PATH de processus différent selon le lanceur — un JDK embarqué ne doit JAMAIS
        // dépendre du PATH ambiant). On essaie l'extension native de la plateforme AVANT le nom nu.
        File f = new File(home, "bin/" + tool + (System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : ""));
        if (f.isFile()) return f.getPath();
        File plain = new File(home, "bin/" + tool);
        return plain.isFile() ? plain.getPath() : tool;   // repli PATH (dernier recours)
    }

    /** Chemin ABSOLU de bash. Sur Linux/macOS, "bash" (PATH) suffit. Sur Windows, "bash" NU n'est PAS fiable :
     *  aucun bash n'est embarqué dans le launcher (game-free), on dépend de Git for Windows installé par
     *  l'utilisateur — mais le launcher est lancé par double-clic depuis l'Explorateur, dont le PATH mis en
     *  cache peut ne PAS inclure "Git\bin" (installé après le démarrage de la session, avant un redémarrage) même
     *  si un terminal fraîchement ouvert le résout correctement. On cherche donc par emplacements CONNUS + le
     *  registre officiel de l'installeur Git for Windows (HKLM/HKCU SOFTWARE\GitForWindows\InstallPath), avant
     *  de retomber sur le PATH. Si RIEN n'est trouvé → erreur EXPLICITE (§2, pas de rustine : un vrai prérequis
     *  manquant doit être dit clairement, pas planter avec un code d'erreur Windows opaque en aval). */
    private static volatile String bashBinCache;
    private static String bashBin() {
        if (bashBinCache != null) return bashBinCache;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return bashBinCache = "bash";
        for (String c : windowsBashCandidates()) if (new File(c).isFile()) return bashBinCache = c;
        String pathEnv = System.getenv("Path");
        if (pathEnv != null) for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, "bash.exe");
            if (f.isFile()) return bashBinCache = f.getPath();
        }
        throw new IllegalStateException("bash introuvable (requis pour générer serveur/client/APK). "
            + "Installez Git for Windows (https://git-scm.com/download/win, coche \"Git Bash\") puis relancez le launcher.");
    }

    private static java.util.List<String> windowsBashCandidates() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String hive : new String[]{"HKLM", "HKCU"}) {
            String p = readRegistry(hive, "SOFTWARE\\GitForWindows", "InstallPath");
            if (p != null) { out.add(p + "\\bin\\bash.exe"); out.add(p + "\\usr\\bin\\bash.exe"); }
        }
        String pf = System.getenv("ProgramFiles");
        if (pf != null) { out.add(pf + "\\Git\\bin\\bash.exe"); out.add(pf + "\\Git\\usr\\bin\\bash.exe"); }
        String pf86 = System.getenv("ProgramFiles(x86)");
        if (pf86 != null) out.add(pf86 + "\\Git\\bin\\bash.exe");
        String lad = System.getenv("LocalAppData");
        if (lad != null) out.add(lad + "\\Programs\\Git\\bin\\bash.exe");
        return out;
    }

    /** Lit une valeur de registre Windows via `reg query` (utilitaire système, toujours présent). Best-effort :
     *  renvoie null si la clé n'existe pas / reg.exe absent (jamais d'exception qui casserait la résolution). */
    private static String readRegistry(String hive, String key, String value) {
        try {
            Process p = new ProcessBuilder("reg", "query", hive + "\\" + key, "/v", value)
                .redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            p.waitFor();
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith(value)) {
                    String[] parts = t.split("\\s{2,}");
                    if (parts.length >= 3) return parts[parts.length - 1].trim();
                }
            }
        } catch (Exception ignored) { /* best-effort */ }
        return null;
    }

    /** Modules JDK requis par le bundle (serveur + client). Superset VÉRIFIÉ : jdeps donne la base ; on AJOUTE
     *  les modules chargés par réflexion/ServiceLoader que jdeps ne voit pas — notamment {@code jdk.crypto.ec}
     *  (Ed25519 de l'auth mnémonique) et {@code jdk.httpserver} (AuthService {@code com.sun.net.httpserver}),
     *  plus charsets/localedata/zipfs. jlink tire les dépendances transitives. */
    private static final String JRE_MODULES =
        "java.base,java.desktop,java.instrument,java.management,java.naming,java.prefs,java.rmi,java.sql,"
      + "java.xml,java.logging,java.net.http,java.scripting,jdk.unsupported,jdk.httpserver,jdk.crypto.ec,"
      + "jdk.crypto.cryptoki,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.xml.dom";

    /** python-build-standalone (astral-sh) — build RELOCATABLE de CPython, un par OS/arch. Version ÉPINGLÉE (§7
     *  reproductibilité). Le tarball « install_only » se déballe en `python/` (avec `bin/python3` ou `python.exe`). */
    private static final String PY_TAG = "20240814";
    private static final String PY_VER = "3.11.9";
    private static final String PY_BASE =
        "https://github.com/astral-sh/python-build-standalone/releases/download/" + PY_TAG + "/cpython-" + PY_VER + "+" + PY_TAG + "-";

    /** Triplet python-build-standalone pour l'OS/arch de build (jlink et py-standalone = OS de build). null si non géré. */
    private static String pyTriple() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean a64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("linux"))   return a64 ? "aarch64-unknown-linux-gnu"  : "x86_64-unknown-linux-gnu";
        if (os.contains("win"))     return "x86_64-pc-windows-msvc";                 // py-standalone ne publie pas arm64-windows
        if (os.contains("mac") || os.contains("darwin")) return a64 ? "aarch64-apple-darwin" : "x86_64-apple-darwin";
        return null;
    }

    /**
     * PYTHON EMBARQUÉ (zéro-install) — télécharge un CPython RELOCATABLE (python-build-standalone) pour l'OS de build
     * dans {@code <out>/runtime/python}, pour que le {@code content_server.py} (désormais PUR STDLIB, sans `curl`)
     * tourne SANS python système. `run.sh`/`run.bat` préfèrent ce python (repli `python3`/`python` système). Best-effort
     * (§2, tracé) : si le téléchargement/extraction échoue (réseau, OS non géré), on log et on continue. Réseau requis
     * au moment de la GÉNÉRATION du bundle (comme le DL des assets) — en CI (GitHub Action) et sur la machine du joueur.
     */
    private void packagePython(File out) {
        try {
            String triple = pyTriple();
            if (triple == null) { append("python embarqué : OS non géré → repli python système."); return; }
            File runtime = new File(out, "runtime"); runtime.mkdirs();
            File pyDir = new File(runtime, "python");
            if (pyDir.exists()) deleteRec(pyDir);
            String url = PY_BASE + triple + "-install_only.tar.gz";
            File tgz = new File(runtime, "python-standalone.tar.gz");
            append("téléchargement python-build-standalone " + PY_VER + " (" + triple + ") …");
            downloadTo(url, tgz);
            // Extraction via `tar` (Linux/macOS natif ; Windows 10+ fournit tar.exe/bsdtar). Le tarball se déballe en `python/`.
            runStep("untar-python", new String[]{ "tar", "-xzf", tgz.getPath(), "-C", runtime.getPath() }, null, null);
            tgz.delete();
            File probe = new File(pyDir, "bin/python3");
            File probeWin = new File(pyDir, "python.exe");
            append("python embarqué : " + pyDir.getPath() + (probe.isFile() || probeWin.isFile() ? " ✓" : " (structure inattendue)"));
        } catch (Exception e) {
            append("python NON embarqué (téléchargement/extraction échoué : " + e.getMessage()
                + ") — le bundle utilisera le `python3`/`python` du système (repli).");
        }
    }

    /** Télécharge {@code url} → {@code dst} (java.net.http, suit les redirections ; respecte le proxy système). */
    private static void downloadTo(String url, File dst) throws Exception {
        java.net.http.HttpClient cli = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(30)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("User-Agent", "dh-launcher/1.0").GET().build();
        java.net.http.HttpResponse<java.nio.file.Path> resp =
            cli.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(dst.toPath()));
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode() + " sur " + url);
    }

    /**
     * RUNTIME EMBARQUÉ (zéro-install) — jlink un JRE MINIMAL dans {@code <out>/runtime/jre} (modules {@link #JRE_MODULES}).
     * Le {@code run.sh}/{@code run.bat} l'utilise s'il est présent, sinon repli sur le {@code java} du système. Le JRE
     * est celui de l'OS de build (jlink ne cross-compile pas sans jmods de l'OS cible) → un bundle Linux embarque un
     * JRE Linux (utilisé par {@code run.sh}) ; un bundle Windows (build sous Windows) embarque un JRE Windows. Best-effort :
     * si jlink est indisponible/échoue, on log et on continue (le bundle reste lançable avec le java système — §2 : pas
     * de faux « OK », juste une capacité en moins, tracée). Le port CLIENT PC devient 100% autonome (pas besoin de python).
     */
    private void packageRuntime(File out) {
        try {
            File runtime = new File(out, "runtime"); runtime.mkdirs();
            File jre = new File(runtime, "jre");
            if (jre.exists()) deleteRec(jre);   // jlink EXIGE que le dossier de sortie soit absent
            runStep("jlink-runtime", new String[]{ javaBin("jlink"),
                "--add-modules", JRE_MODULES,
                "--no-header-files", "--no-man-pages", "--strip-debug", "--compress", "zip-6",
                "--output", jre.getPath() }, null, null);
            append("runtime JRE embarqué (zéro-install, OS de build) : " + jre.getPath());
        } catch (Exception e) {
            append("runtime JRE NON embarqué (jlink indispo/échec : " + e.getMessage()
                + ") — le bundle utilisera le `java` du système (repli).");
        }
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
      // JRE EMBARQUÉ (zéro-install) : si `runtime/jre` est présent (jlink, OS de build), on l'utilise ; sinon repli
      // sur le `java` du système. Idem pour python : le content-server exige encore python3 (documenté).
      + "JAVA=\"$DIR/runtime/jre/bin/java\"; [ -x \"$JAVA\" ] || JAVA=java\n"
      + "PY=\"$DIR/runtime/python/bin/python3\"; [ -x \"$PY\" ] || PY=python3\n"
      + "CONTENT_PORT=\"${DH_CONTENT_PORT:-8080}\"; GAME_PORT=\"${DH_GAME_PORT:-8081}\"; AUTH_PORT=\"${DH_AUTH_PORT:-8082}\"\n"
      // ADMIN DISTANT (chantier F) : par défaut l'AdminService écoute 127.0.0.1 avec un jeton aléatoire imprimé. Pour
      // administrer CE serveur depuis un autre poste (cloud), exporter DH_ADMIN_BIND=0.0.0.0 + DH_ADMIN_TOKEN=<secret>
      // (LoginServer lit DH_ADMIN_TOKEN en env ; on ne le met PAS sur la ligne de commande → absent de `ps`).
      // TLS (recommandé pour Internet) : fournir DH_ADMIN_TLS_KEYSTORE=<chemin .p12> + DH_ADMIN_TLS_PASS=<mdp> — le jeton
      // transite alors CHIFFRÉ, et le serveur imprime au boot l'empreinte SHA-256 à épingler dans le launcher (héritées
      // par le process via getenv). Sinon (http clair) : exposer l'admin via tunnel SSH/VPN.
      + "ADMIN_OPTS=\"\"\n"
      + "[ -n \"${DH_ADMIN_BIND:-}\" ] && ADMIN_OPTS=\"$ADMIN_OPTS -Ddh.admin.bind=$DH_ADMIN_BIND\"\n"
      + "[ -n \"${DH_ADMIN_PORT:-}\" ] && ADMIN_OPTS=\"$ADMIN_OPTS -Ddh.admin.port=$DH_ADMIN_PORT\"\n"
      + "mkdir -p \"$DIR/data\"\n"
      + "\"$PY\" \"$DIR/content_server.py\" --port \"$CONTENT_PORT\" --rewrite-host \"127.0.0.1:$CONTENT_PORT\" \\\n"
      + "        --index \"$DIR/index.txt\" --game-server \"127.0.0.1:$GAME_PORT\" & CPID=$!\n"
      + "\"$JAVA\" -XX:TieredStopAtLevel=1 ${DH_SERVER_OPTS:-} $ADMIN_OPTS -Ddh.db=\"$DIR/data/dh-server.db\" \\\n"
      + "     -Ddh.stats=\"$DIR/game-data/stats\" -Ddh.auth.port=\"$AUTH_PORT\" \\\n"
      + "     -cp \"$DIR/lib/*\" dhserver.LoginServer \"$GAME_PORT\" & JPID=$!\n"
      // les deux en arrière-plan + wait : le trap survit (contrairement à exec) → arrêt PROPRE des DEUX process
      // que l'arrêt vienne d'un Ctrl-C (standalone) ou d'un SIGTERM (bouton « arrêter » du launcher).
      + "trap 'kill $CPID $JPID 2>/dev/null' TERM INT EXIT\n"
      + "wait $JPID\n";

    private static final String RUN_SH_CLIENT =
        "#!/usr/bin/env bash\n"
      + "# Port PC Disney Heroes — bundle client autonome généré par le launcher. Lançable hors dev.\n"
      + "set -uo pipefail\n"
      + "DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
      + "export JAVA_TOOL_OPTIONS=\n"
      + "export LIBGL_ALWAYS_SOFTWARE=\"${LIBGL_ALWAYS_SOFTWARE:-1}\"\n"
      + "export LC_ALL=\"${LC_ALL:-C.utf8}\"\n"
      // JRE EMBARQUÉ (zéro-install) : le port PC n'a PAS besoin de python → avec `runtime/jre` il est 100% autonome.
      + "JAVA=\"$DIR/runtime/jre/bin/java\"; [ -x \"$JAVA\" ] || JAVA=java\n"
      + "SERVER=\"${DH_SERVER:-127.0.0.1:8080}\"\n"
      + "CP=\"$DIR/lib/dhdesktop.jar:$DIR/lib/game-logic-framed.jar:$DIR/native:$DIR/assets:$DIR/resources:$DIR/lib/runtime/*\"\n"
      + "JOPTS=\"-XX:TieredStopAtLevel=1 -Dorg.lwjgl.util.Debug=false -Ddh.rundir=$DIR/data/run\"\n"
      + "JOPTS=\"$JOPTS -Ddh.spinelib=$DIR/native/libspine-native.so -Ddh.server=$SERVER\"\n"
      + "[ -f \"$DIR/native/libgdx64.so\" ] && JOPTS=\"$JOPTS -Ddh.gdxnative=$DIR/native/libgdx64.so\"\n"
      + "if [ -f \"$DIR/native/libhostspine64.so\" ] && [ \"${DH_SPINEBACKEND:-jni}\" != unidbg ]; then\n"
      + "  JOPTS=\"$JOPTS -Ddh.spinebackend=jni -Ddh.hostspine=$DIR/native/libhostspine64.so\"; fi\n"
      + "[ -n \"${DH_USERID:-}\" ] && JOPTS=\"$JOPTS -Ddh.userid=$DH_USERID\"\n"
      + "[ -n \"${DH_FRAMES:-}\" ] && JOPTS=\"$JOPTS -Ddh.frames=$DH_FRAMES\"\n"
      + "[ -n \"${DH_SHOT:-}\" ] && JOPTS=\"$JOPTS -Ddh.shot=$DH_SHOT\"\n"
      // FENÊTRE VISIBLE pour un vrai joueur (DesktopLauncher crée la fenêtre INVISIBLE par défaut — orienté capture
      // headless). Sans ça : « la console s'ouvre mais aucune fenêtre de jeu ». En mode capture (DH_SHOT) on garde invisible.
      + "[ -z \"${DH_SHOT:-}\" ] && JOPTS=\"$JOPTS -Ddh.visible=1\"\n"
      + "if [ -z \"${DISPLAY:-}\" ] && command -v Xvfb >/dev/null; then\n"
      + "  Xvfb :99 -screen 0 1280x720x24 >/tmp/dh_xvfb.log 2>&1 & XVFB=$!\n"
      + "  trap 'kill $XVFB 2>/dev/null' EXIT; export DISPLAY=:99; sleep 1; fi\n"
      + "if [ -n \"${DH_TIMEOUT:-}\" ]; then timeout \"$DH_TIMEOUT\" \"$JAVA\" $JOPTS -cp \"$CP\" dhdesktop.DesktopLauncher \"$@\"\n"
      + "else \"$JAVA\" $JOPTS -cp \"$CP\" dhdesktop.DesktopLauncher \"$@\"; fi\n";

    private static final String RUN_BAT_CLIENT =
        "@echo off\r\n"
      + "REM Port PC Disney Heroes — bundle client autonome. Lancable hors dev.\r\n"
      + "set DIR=%~dp0\r\n"
      + "set JAVA=%DIR%runtime\\jre\\bin\\java.exe\r\n"
      + "if not exist \"%JAVA%\" set JAVA=java\r\n"
      + "if \"%DH_SERVER%\"==\"\" set DH_SERVER=127.0.0.1:8080\r\n"
      + "set CP=%DIR%lib\\dhdesktop.jar;%DIR%lib\\game-logic-framed.jar;%DIR%native;%DIR%assets;%DIR%resources;%DIR%lib\\runtime\\*\r\n"
      + "set JOPTS=-XX:TieredStopAtLevel=1 -Ddh.rundir=\"%DIR%data\\run\" -Ddh.spinelib=\"%DIR%native\\libspine-native.so\" -Ddh.server=%DH_SERVER%\r\n"
      + "if exist \"%DIR%native\\libgdx64.so\" set JOPTS=%JOPTS% -Ddh.gdxnative=\"%DIR%native\\libgdx64.so\"\r\n"
      + "if exist \"%DIR%native\\libhostspine64.dll\" set JOPTS=%JOPTS% -Ddh.spinebackend=jni -Ddh.hostspine=\"%DIR%native\\libhostspine64.dll\"\r\n"
      // FENÊTRE VISIBLE (la fenêtre est créée invisible par défaut, orienté capture headless) — sans ça le joueur Windows
      // voit la console s'ouvrir mais aucune fenêtre de jeu.
      + "set JOPTS=%JOPTS% -Ddh.visible=1\r\n"
      + "\"%JAVA%\" %JOPTS% -cp \"%CP%\" dhdesktop.DesktopLauncher %*\r\n";

    private static final String RUN_BAT =
        "@echo off\r\n"
      + "REM Serveur Disney Heroes (self-host) — bundle autonome. Lancable hors dev.\r\n"
      + "set DIR=%~dp0\r\n"
      + "set JAVA=%DIR%runtime\\jre\\bin\\java.exe\r\n"
      + "if not exist \"%JAVA%\" set JAVA=java\r\n"
      + "set PY=%DIR%runtime\\python\\python.exe\r\n"
      + "if not exist \"%PY%\" set PY=python\r\n"
      + "if \"%DH_CONTENT_PORT%\"==\"\" set DH_CONTENT_PORT=8080\r\n"
      + "if \"%DH_GAME_PORT%\"==\"\" set DH_GAME_PORT=8081\r\n"
      + "if \"%DH_AUTH_PORT%\"==\"\" set DH_AUTH_PORT=8082\r\n"
      // ADMIN DISTANT (chantier F) : DH_ADMIN_BIND=0.0.0.0 + DH_ADMIN_TOKEN=<secret> pour exposer l'admin (cloud).
      // LoginServer lit DH_ADMIN_TOKEN en env ; bind/port via -D. TLS (recommandé Internet) : DH_ADMIN_TLS_KEYSTORE=<.p12>
      // + DH_ADMIN_TLS_PASS=<mdp> → jeton chiffré + empreinte SHA-256 imprimée au boot (à épingler). Sinon tunnel SSH/VPN.
      + "set ADMIN_OPTS=\r\n"
      + "if not \"%DH_ADMIN_BIND%\"==\"\" set ADMIN_OPTS=%ADMIN_OPTS% -Ddh.admin.bind=%DH_ADMIN_BIND%\r\n"
      + "if not \"%DH_ADMIN_PORT%\"==\"\" set ADMIN_OPTS=%ADMIN_OPTS% -Ddh.admin.port=%DH_ADMIN_PORT%\r\n"
      + "if not exist \"%DIR%data\" mkdir \"%DIR%data\"\r\n"
      + "start \"dh-content\" \"%PY%\" \"%DIR%content_server.py\" --port %DH_CONTENT_PORT% --rewrite-host 127.0.0.1:%DH_CONTENT_PORT% --index \"%DIR%index.txt\" --game-server 127.0.0.1:%DH_GAME_PORT%\r\n"
      + "\"%JAVA%\" -XX:TieredStopAtLevel=1 %ADMIN_OPTS% -Ddh.db=\"%DIR%data\\dh-server.db\" -Ddh.stats=\"%DIR%game-data\\stats\" -Ddh.auth.port=%DH_AUTH_PORT% -cp \"%DIR%lib\\*\" dhserver.LoginServer %DH_GAME_PORT%\r\n";

    private void runStep(String name, String[] cmd, String envKey, String envVal) throws Exception {
        java.util.Map<String,String> env = null;
        if (envKey != null) { env = new java.util.HashMap<>(); env.put(envKey, envVal); }
        runStep(name, cmd, env);
    }
    private void runStep(String name, String[] cmd, java.util.Map<String,String> env) throws Exception {
        step = name; append("=== étape " + name + " ===");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(projectDir)).redirectErrorStream(true);
        if (env != null) pb.environment().putAll(env);
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
