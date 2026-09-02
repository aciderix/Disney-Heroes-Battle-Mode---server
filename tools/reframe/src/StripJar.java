import java.io.*;
import java.util.zip.*;

/**
 * Copie {@code <src.jar>} vers {@code <dst.jar>} en EXCLUANT les entrées dont le nom commence par l'un des préfixes
 * donnés — filtre ZIP-À-ZIP EN STREAMING (jamais d'extraction sur le système de fichiers). Sert à retirer
 * {@code org/lwjgl/**} et {@code com/badlogic/gdx/backends/**} de {@code game.jar} (LWJGL2 réduit embarqué par
 * PerBlue, qui masquerait sinon le vrai LWJGL3 à la compilation du port desktop) — remplace {@code zip -d}, absent
 * par défaut de Git for Windows.
 *
 * <p>Pourquoi le streaming plutôt qu'extraire+filtrer+réempaqueter (unzip+jar) : un jar de ~65k petites entrées
 * extrait sur disque = ~130k opérations fichier (écriture puis lecture) — sur Windows (NTFS + antivirus temps réel
 * scannant chaque fichier), ceci prend PLUSIEURS MINUTES. Le filtre en streaming (2 flux, jamais de fichiers
 * individuels sur disque) fait le même travail en quelques secondes. Vérifié : game.jar (73 Mo, ~65k entrées) →
 * 7 s en streaming contre 15+ minutes en extraction/réempaquetage classique.
 *
 * Usage : java StripJar <src.jar> <dst.jar> <préfixe1> [préfixe2 ...]
 */
public class StripJar {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java StripJar <src.jar> <dst.jar> <préfixe1> [préfixe2 ...]");
            System.exit(1);
        }
        String src = args[0], dst = args[1];
        String[] excludePrefixes = new String[args.length - 2];
        System.arraycopy(args, 2, excludePrefixes, 0, excludePrefixes.length);

        int kept = 0, skipped = 0;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)));
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dst)))) {
            out.setLevel(1); // rapide : le contenu (bytecode) est déjà peu compressible, pas la peine de max-compresser
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = in.getNextEntry()) != null) {
                boolean excluded = false;
                for (String p : excludePrefixes) if (e.getName().startsWith(p)) { excluded = true; break; }
                if (excluded) { skipped++; continue; }
                kept++;
                out.putNextEntry(new ZipEntry(e.getName()));
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.closeEntry();
            }
        }
        System.out.println("[StripJar] " + src + " -> " + dst + " (kept=" + kept + " skipped=" + skipped + ")");
    }
}
