package dhlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RÉGLAGES LOCAUX du launcher (chantier C2b) — persistés dans {@code settings.txt} du dossier de config standard par OS
 * (même dossier que {@code servers.txt}, cf. {@link LauncherConfig#defaultDir}). Format interne key=value (une ligne par
 * clé → aucun parseur JSON requis, robuste, cohérent avec {@code LauncherConfig}) ; l'endpoint {@code /settings} en
 * produit du JSON pour le front. Pur JDK (le launcher-core reste distribuable sans le game jar).
 *
 * <p>Ensemble de clés FERMÉ (on ne persiste que ce que le front consomme réellement — pas de réglage « décoratif ») :
 * langue, version d'avertissement acceptée, et chemins par défaut (APK, sortie de build, bundle client/serveur).
 */
public final class SettingsManager {

    /** Clés connues + valeur par défaut. Toute autre clé POSTée est ignorée (sécurité). */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("language", "fr");
        DEFAULTS.put("disclaimerAcceptedVersion", "0");   // entier (émis en nombre)
        DEFAULTS.put("apkPath", "");
        DEFAULTS.put("outDir", "");
        DEFAULTS.put("clientDir", "");
        DEFAULTS.put("bundleDir", "");
    }
    /** Clés dont la valeur JSON est un NOMBRE (les autres = chaînes). */
    private static boolean isNumber(String key) { return "disclaimerAcceptedVersion".equals(key); }

    private final Path dir;
    private final Path file;

    public SettingsManager(Path dir) { this.dir = dir; this.file = dir.resolve("settings.txt"); }
    public SettingsManager() { this(LauncherConfig.defaultDir()); }

    private synchronized Map<String, String> load() throws IOException {
        Map<String, String> m = new LinkedHashMap<>(DEFAULTS);
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i);
                if (DEFAULTS.containsKey(k)) m.put(k, line.substring(i + 1));
            }
        }
        return m;
    }

    private synchronized void save(Map<String, String> m) throws IOException {
        Files.createDirectories(dir);
        StringBuilder b = new StringBuilder();
        for (String k : DEFAULTS.keySet()) b.append(k).append('=').append(m.getOrDefault(k, DEFAULTS.get(k))).append('\n');
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    }

    /** Fusionne les clés CONNUES de {@code updates} (les inconnues sont ignorées), persiste, renvoie l'état JSON. */
    public synchronized String update(Map<String, String> updates) throws IOException {
        Map<String, String> m = load();
        for (Map.Entry<String, String> e : updates.entrySet())
            if (DEFAULTS.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
        save(m);
        return toJson(m);
    }

    /** État JSON courant (pour {@code GET /settings}). */
    public synchronized String toJson() throws IOException { return toJson(load()); }

    private static String toJson(Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (String k : DEFAULTS.keySet()) {
            if (!first) b.append(','); first = false;
            b.append('"').append(k).append("\":");
            String v = m.getOrDefault(k, DEFAULTS.get(k));
            if (isNumber(k)) { long n; try { n = Long.parseLong(v.trim()); } catch (Exception e) { n = 0; } b.append(n); }
            else b.append('"').append(esc(v)).append('"');
        }
        return b.append('}').toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.toString();
    }
}
