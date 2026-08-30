import dhlauncher.BuildManager;

import java.io.File;
import java.nio.file.Files;

/**
 * DEV (C2a-4b, vérif LOURDE — hors régression rapide car build gradle + ~500 Mo d'assets) — génère le BUNDLE
 * CLIENT PC depuis l'APK et vérifie sa STRUCTURE autonome. Le LANCEMENT du client (rendu du hub) est vérifié
 * séparément EN JEU (harnais Xvfb) : cf. `JOURNAL.md` g198.
 *
 * <p>Usage : {@code ClientBundleTest} (projectdir = -Ddh.launcher.projectdir ou user.dir). Sortie temporaire nettoyée.
 */
public final class ClientBundleTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        String root = System.getProperty("dh.launcher.projectdir", System.getProperty("user.dir", "."));
        String apk = new File(root, "game/disney-heroes-12.1.0.apk").getPath();
        File out = Files.createTempDirectory("dh-client-bundle").toFile();
        try {
            BuildManager bm = new BuildManager(root);
            bm.start(apk, out.getPath(), BuildManager.Target.CLIENT, false, true);
            long dl = System.currentTimeMillis() + 600_000L;
            while (System.currentTimeMillis() < dl && bm.status().contains("\"state\":\"RUNNING\"")) Thread.sleep(2000);
            ok(bm.status().contains("\"state\":\"DONE\""), "build CLIENT = DONE (" + step(bm) + ")");

            ok(new File(out, "lib/dhdesktop.jar").isFile(), "lib/dhdesktop.jar (classes desktop-port)");
            ok(new File(out, "lib/game-logic-framed.jar").isFile(), "lib/game-logic-framed.jar (classes jeu reframées)");
            File rt = new File(out, "lib/runtime");
            ok(rt.isDirectory() && rt.list().length > 20, "lib/runtime/*.jar (LWJGL3/gdx/unidbg… " + (rt.list()==null?0:rt.list().length) + ")");
            ok(new File(out, "native/libgdx64.so").isFile(), "native/libgdx64.so");
            ok(new File(out, "native/libspine-native.so").isFile(), "native/libspine-native.so (ARM d'origine)");
            File assets = new File(out, "assets");
            ok(assets.isDirectory() && assets.list().length > 3, "assets/ (extraits de l'APK)");
            ok(new File(out, "resources").isDirectory(), "resources/");
            ok(new File(out, "run.sh").isFile() && new File(out, "run.bat").isFile(), "run.sh + run.bat");
        } finally {
            deleteRec(out);
        }
        System.out.println("ClientBundleTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static String step(BuildManager bm) { return bm.status().replaceAll(".*\"step\":\"([^\"]*)\".*", "$1"); }
    static void deleteRec(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteRec(c); f.delete(); }
}
