package dhbackend;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;

import java.io.File;

/**
 * Backend Files desktop ({@code com.badlogic.gdx.Files} — 8 méthodes). Porté de DragonSoul
 * `DsFiles` (noms dé-obfusqués : a/b/c/d → classpath/internal/external/local…).
 *
 * Les assets internes ("ui/...", "world/...") sont rendus en handles **Classpath au chemin
 * relatif** (racine des assets sur le classpath via run-desktop.sh) → lectures par le
 * classloader, {@code path()} relatif = comportement Android (clés AssetManager correctes).
 * External/Local pointent sur un dossier disque inscriptible. Fidélité : RÉEL.
 */
public final class DhFiles implements Files {
    private final String externalRoot;
    private final String localRoot;
    private static final boolean TRACE = Boolean.getBoolean("dh.trace.files");

    public DhFiles(String externalRoot, String localRoot) {
        this.externalRoot = externalRoot;
        this.localRoot = localRoot;
        new File(externalRoot).mkdirs();
        new File(localRoot).mkdirs();
    }

    private static FileHandle abs(File f) {
        if (TRACE) System.out.println("[files] " + (f.exists() ? "OK   " : "MISS ") + f.getPath());
        return new FileHandle(f.getAbsolutePath()); // FileType.Absolute
    }

    /** Asset interne/classpath : conserver le chemin relatif (voir DhFileHandle). Les chemins
     *  absolus (external/local produits par le jeu) retombent sur un handle disque. */
    private FileHandle internalHandle(String path) {
        if (new File(path).isAbsolute()) return abs(new File(path));
        if (TRACE) System.out.println("[files] CP   " + path);
        return new DhFileHandle(path, Files.FileType.Classpath);
    }

    private static FileHandle under(String root, String path) {
        File f = new File(path);
        return abs(f.isAbsolute() ? f : new File(root, path));
    }

    @Override public FileHandle classpath(String path) { return internalHandle(path); }
    @Override public FileHandle internal(String path)  { return internalHandle(path); }
    @Override public FileHandle external(String path)  { return under(externalRoot, path); }
    @Override public FileHandle local(String path)     { return under(localRoot, path); }
    @Override public FileHandle absolute(String path)  { return new FileHandle(path); }
    @Override public String getExternalStoragePath()   { return externalRoot + File.separator; }
    @Override public String getLocalStoragePath()      { return localRoot + File.separator; }

    @Override public FileHandle getFileHandle(String path, Files.FileType type) {
        switch (type) {
            case Classpath: case Internal: return internalHandle(path);
            case External:  return under(externalRoot, path);
            case Local:     return under(localRoot, path);
            case Absolute:  default: return new FileHandle(path);
        }
    }
}
