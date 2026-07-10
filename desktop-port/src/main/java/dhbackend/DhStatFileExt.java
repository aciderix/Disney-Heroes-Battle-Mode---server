package dhbackend;

import com.perblue.common.stats.GeneralStats;
import com.perblue.heroes.game.data.StatFileHelper;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Fournit au jeu l'ouvreur de fichiers de stats ({@code .tab}) — normalement installé par
 * l'AndroidLauncher via {@link StatFileHelper#setExt}. {@code StatFileHelper.getOpener()}
 * renvoie ce champ ; s'il est null, tout chargement de stats NPE.
 *
 * Sur Android les {@code .tab} sont dans {@code assets/stats/} (plat) — PAS au chemin du
 * package (ce que fait {@code StatFileOpener.DEFAULT}). On lit donc {@code stats/<nom>} via le
 * classloader (les assets de l'APK, dont {@code stats/*.tab}, sont sur le classpath — cf.
 * run-desktop.sh). On ne réécrit pas les données du jeu, on ouvre ses fichiers d'origine
 * (principe §3/§4 : source de vérité = les {@code .tab} du jeu). Fidélité : RÉEL.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class DhStatFileExt implements StatFileHelper.StatFileHelperExt {

    private static String base(String name) {
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    @Override
    public InputStream openFileRaw(GeneralStats stats, String name) {
        String res = "stats/" + base(name);
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(res);
        if (in == null) in = DhStatFileExt.class.getClassLoader().getResourceAsStream(res);
        if (in == null) throw new RuntimeException("Stat file introuvable sur le classpath: " + res);
        return in;
    }

    @Override
    public Reader openFile(GeneralStats stats, String name) {
        return new InputStreamReader(openFileRaw(stats, name), StandardCharsets.UTF_8);
    }

    // Les .tab sont du texte (variante TXT) → on force le chemin texte (on possède les .tab).
    @Override public boolean forceText() { return true; }

    // Hashes = validation du contenu téléchargé ; non requis ici (#STATHASH).
    @Override public Map getStatFileHashes() { return Collections.emptyMap(); }

    /** Installe l'ouvreur (avant tout chargement de stats). */
    public static void install() { StatFileHelper.setExt(new DhStatFileExt()); }
}
