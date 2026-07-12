package dhserver;

import com.perblue.common.stats.GeneralStats;
import com.perblue.heroes.game.data.StatFileHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Charge la <b>couche données du jeu</b> côté serveur : installe l'ouvreur de fichiers de stats
 * ({@code .tab}) que le jeu utilise pour peupler ses classes de données ({@code ChestStats},
 * {@code UnitStats}, …). Ainsi le serveur <b>exécute le code du jeu</b> avec les <b>vraies données</b>
 * (docs/PRINCIPLES.md §3/§4 : on lit les fichiers d'origine, on ne recopie rien).
 *
 * <p>Miroir serveur de {@code dhbackend.DhStatFileExt} (côté client) : ici on lit depuis le dossier
 * extrait {@code game-data/stats/} (source de vérité, régénérable par {@code tools/extract_game_data.sh}).
 * Répertoire configurable via {@code -Ddh.stats}.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ServerStats implements StatFileHelper.StatFileHelperExt {

  private final File dir;

  private ServerStats(File dir) { this.dir = dir; }

  private static String base(String name) {
    int i = name.lastIndexOf('/');
    return i >= 0 ? name.substring(i + 1) : name;
  }

  private InputStream open(String name) {
    File f = new File(dir, base(name));
    try { return f.isFile() ? new FileInputStream(f) : null; }
    catch (Exception e) { return null; }
  }

  @Override
  public InputStream openFileRaw(GeneralStats stats, String name) {
    InputStream in = open(name);
    // Absent (ex. binaire .tabb inexistant) → signaler pour que le jeu retombe sur le texte .tab.
    if (in == null) throw new RuntimeException(new java.io.FileNotFoundException(base(name)));
    return in;
  }

  @Override
  public Reader openFile(GeneralStats stats, String name) {
    InputStream in = open(name);
    if (in == null) throw new RuntimeException(new java.io.FileNotFoundException(base(name)));
    return new InputStreamReader(in, StandardCharsets.UTF_8);
  }

  @Override public boolean forceText() { return false; }        // essaie .tabb puis .tab
  @Override public Map getStatFileHashes() { return Collections.emptyMap(); }

  /** Installe l'ouvreur (avant tout chargement de stats). Dossier : {@code -Ddh.stats} ou game-data/stats. */
  public static void install() {
    String path = System.getProperty("dh.stats", "game-data/stats");
    File dir = new File(path).getAbsoluteFile();
    if (!dir.isDirectory()) throw new IllegalStateException("dossier de stats introuvable: " + dir);
    StatFileHelper.setExt(new ServerStats(dir));
    System.out.println("[stats] ouvreur installé sur " + dir + " (" + dir.list().length + " fichiers)");
  }
}
