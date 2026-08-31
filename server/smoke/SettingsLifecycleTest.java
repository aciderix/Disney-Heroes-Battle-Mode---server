import dhlauncher.SettingsManager;
import java.nio.file.Files;
import java.util.Map;

/**
 * Smoke ISOLÉ (C2b) — {@link SettingsManager} : défauts, fusion des clés CONNUES, persistance, rejet des clés inconnues,
 * type nombre pour {@code disclaimerAcceptedVersion}. Game-free (dhlauncher pur).
 */
public final class SettingsLifecycleTest {
    static int ok = 0, fail = 0;
    static void ok(boolean c, String m) { if (c) ok++; else { fail++; System.out.println("  ✗ " + m); } }

    public static void main(String[] a) throws Exception {
        var dir = Files.createTempDirectory("dhsettings");
        SettingsManager sm = new SettingsManager(dir);

        String def = sm.toJson();
        ok(def.contains("\"language\":\"fr\""), "défaut language=fr");
        ok(def.contains("\"disclaimerAcceptedVersion\":0"), "défaut disclaimerAcceptedVersion=0 (nombre)");

        String upd = sm.update(Map.of("language", "en", "apkPath", "/tmp/x.apk",
                "disclaimerAcceptedVersion", "1", "unknownKey", "zzz"));
        ok(upd.contains("\"language\":\"en\""), "fusion language=en");
        ok(upd.contains("\"apkPath\":\"/tmp/x.apk\""), "fusion apkPath");
        ok(upd.contains("\"disclaimerAcceptedVersion\":1"), "fusion version (nombre)");
        ok(!upd.contains("unknownKey"), "clé inconnue IGNORÉE (fermé)");

        // persistance : nouvelle instance relit le fichier
        SettingsManager sm2 = new SettingsManager(dir);
        ok(sm2.toJson().contains("\"language\":\"en\""), "persisté (relu par une nouvelle instance)");

        System.out.println("SettingsLifecycleTest : " + ok + " ok, " + fail + " échec(s)");
        if (fail > 0) System.exit(1);
    }
}
