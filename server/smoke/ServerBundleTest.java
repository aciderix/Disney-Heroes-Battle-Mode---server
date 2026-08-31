import dhlauncher.BuildManager;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * C2a-4-pkg (packaging clé-en-main) — le launcher génère un BUNDLE serveur AUTONOME depuis l'APK, puis on le
 * COPIE hors de l'arbre de dev et on le LANCE via son {@code run.sh} : le port de jeu doit écouter (preuve que le
 * bundle est self-contained). {@code new Socket}/{@code LoginServer} → auto-détecté ISOLÉ par regression.sh.
 */
public final class ServerBundleTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        String root = System.getProperty("dh.launcher.projectdir", System.getProperty("user.dir", "."));
        String apk = new File(root, "game/disney-heroes-12.1.0.apk").getPath();
        File gen = Files.createTempDirectory("dh-bundle-gen").toFile();

        // 1) GÉNÈRE le bundle serveur (extract + package). Synchrone via BuildManager (pas de HTTP nécessaire ici).
        BuildManager bm = new BuildManager(root);
        bm.start(apk, gen.getPath(), BuildManager.Target.SERVER, false, true);
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline && bm.status().contains("\"state\":\"RUNNING\"")) Thread.sleep(500);
        ok(bm.status().contains("\"state\":\"DONE\""), "génération du bundle serveur = DONE (" + bm.status().replaceAll(".*\"step\":\"([^\"]*)\".*", "$1") + ")");

        // 2) structure du bundle
        ok(new File(gen, "lib/dhserver.jar").isFile(), "lib/dhserver.jar présent");
        ok(new File(gen, "lib/game-framed.jar").isFile(), "lib/game-framed.jar présent");
        ok(new File(gen, "content_server.py").isFile(), "content_server.py présent");
        ok(new File(gen, "run.sh").isFile(), "run.sh présent");
        File tabs = new File(gen, "game-data/stats");
        ok(tabs.isDirectory() && tabs.list().length > 50, "game-data/stats peuplé");
        // RUNTIME EMBARQUÉ (zéro-install) : jlink doit avoir produit un JRE minimal utilisable, et run.sh doit le préférer.
        File embJava = new File(gen, "runtime/jre/bin/java");
        ok(embJava.isFile(), "runtime/jre/bin/java embarqué (jlink, zéro-install côté Java)");
        try {
            String rs = new String(java.nio.file.Files.readAllBytes(new File(gen, "run.sh").toPath()), java.nio.charset.StandardCharsets.UTF_8);
            ok(rs.contains("runtime/jre/bin/java"), "run.sh préfère le JRE embarqué (repli `java` système)");
            // PYTHON embarqué : run.sh doit préférer runtime/python (le DL réel du CPython relocatable se fait au build,
            // réseau requis → best-effort ; en CI hors-ligne il retombe sur python3 système, d'où on n'exige que la LOGIQUE).
            ok(rs.contains("runtime/python/bin/python3"), "run.sh préfère le Python embarqué (repli `python3` système)");
        } catch (Exception e) { ok(false, "lecture run.sh : " + e); }

        // 3) COPIE le bundle HORS de l'arbre de dev, puis l'HÉBERGE via HostManager.startBundle — c.-à-d. le chemin
        //    « bouton Héberger du launcher lance le bundle généré » (même artefact que le double-clic standalone).
        File runDir = Files.createTempDirectory("dh-bundle-run").toFile();
        copyTree(gen.toPath(), runDir.toPath());
        int G = 19091;
        dhlauncher.HostManager hm = new dhlauncher.HostManager(runDir.getPath());
        boolean listening = false, downAfterStop = false;
        try {
            String st = hm.startBundle(runDir.getPath(), 19090, G, 19092, false);
            ok(st.contains("\"gamePort\":" + G), "startBundle accepté");

            long dl = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < dl && hm.isRunning()) {
                try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", G), 500); listening = true; break; }
                catch (Exception e) { Thread.sleep(500); }
            }
            ok(listening, "BUNDLE HÉBERGÉ HORS DEV via le launcher : port de jeu " + G + " en écoute (autonome)");

            hm.stop();
            Thread.sleep(1000);
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", G), 500); }
            catch (Exception e) { downAfterStop = true; }
            ok(downAfterStop, "arrêt propre : port fermé après stop (content_server + serveur tués ensemble)");
        } finally {
            try { hm.stop(); } catch (Exception ignore) {}
            deleteRec(gen); deleteRec(runDir);
        }

        System.out.println("ServerBundleTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else { Files.createDirectories(t.getParent()); Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        new File(dst.toFile(), "run.sh").setExecutable(true, false);
    }
    static void deleteRec(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteRec(c); f.delete(); }
}
