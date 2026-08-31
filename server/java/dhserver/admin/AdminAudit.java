package dhserver.admin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * ADMIN — JOURNAL D'AUDIT des actions opérateur puissantes (chantier D, {@code docs/LAUNCHER_UI.md} §4.6 : « éditer un
 * compte = autoritatif, À JOURNALISER »). Chaque MUTATION admin (don de ressources, héros, TL, campagne, tutos, unlock)
 * écrit une ligne horodatée dans {@code admin-audit.log}, à côté de la base ({@code dh.db}). Consultable via
 * {@code GET /admin/audit}. Append-only, tolérant aux erreurs d'écriture (ne bloque jamais l'action).
 *
 * <p>Format de ligne (TSV) : {@code <ISO-8601>\t<action>\t<detail>\t<result>}.
 */
public final class AdminAudit {
    private AdminAudit() {}

    /** Fichier d'audit = même dossier que la base SQLite ({@code -Ddh.db}), nom fixe {@code admin-audit.log}. */
    static File file() {
        String db = System.getProperty("dh.db", "server/data/dh-server.db");
        File parent = new File(db).getAbsoluteFile().getParentFile();
        return new File(parent, "admin-audit.log");
    }

    /** Ajoute une entrée d'audit (jamais bloquant : une erreur d'écriture est avalée). */
    public static synchronized void log(String action, String detail, String result) {
        String line = java.time.Instant.now().toString()
                + '\t' + safe(action) + '\t' + safe(detail) + '\t' + safe(result) + '\n';
        try {
            File f = file();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignore) { /* audit best-effort : ne casse pas l'action */ }
    }

    /** {@code {"lines":[..]}} — {@code n} dernières entrées d'audit (récentes en dernier). Vide si aucun audit encore. */
    public static synchronized String tailJson(int n) {
        List<String> lines = new ArrayList<>();
        File f = file();
        if (f.isFile()) {
            try {
                List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                int from = Math.max(0, all.size() - Math.max(1, Math.min(n, 1000)));
                lines = all.subList(from, all.size());
            } catch (IOException ignore) { /* renvoie ce qu'on a */ }
        }
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ContentEra.jsonStr(lines.get(i)));
        }
        return sb.append("]}").toString();
    }

    private static String safe(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
}
