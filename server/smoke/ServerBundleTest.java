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

        // 3) COPIE le bundle HORS de l'arbre de dev, puis le LANCE via run.sh (ports hauts pour éviter les collisions)
        File runDir = Files.createTempDirectory("dh-bundle-run").toFile();
        copyTree(gen.toPath(), runDir.toPath());
        int G = 19091;
        ProcessBuilder pb = new ProcessBuilder("bash", new File(runDir, "run.sh").getPath())
                .directory(runDir).redirectErrorStream(true).redirectOutput(new File(runDir, "server.log"));
        pb.environment().put("DH_GAME_PORT", String.valueOf(G));
        pb.environment().put("DH_CONTENT_PORT", "19090");
        pb.environment().put("DH_AUTH_PORT", "19092");
        Process p = pb.start();

        boolean listening = false;
        try {
            long dl = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < dl && p.isAlive()) {
                try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", G), 500); listening = true; break; }
                catch (Exception e) { Thread.sleep(500); }
            }
            ok(listening, "BUNDLE LANCÉ HORS DEV : port de jeu " + G + " en écoute (serveur autonome)");
        } finally {
            p.destroy();
            try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); } catch (Exception ignore) {}
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
