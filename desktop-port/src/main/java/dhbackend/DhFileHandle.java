package dhbackend;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;

/**
 * FileHandle qui conserve son chemin RELATIF (pour que {@code path()} == la chaîne demandée
 * par le jeu) tout en résolvant les lectures via le classloader. libGDX indexe chaque asset
 * chargé par la chaîne passée à {@code Gdx.files.internal(path)} (cf. AssetManager) : rendre
 * des handles à chemin absolu casse cet invariant (un loader qui met en file une dépendance
 * via un FileHandle résolu l'indexe sous le chemin absolu, un autre qui fait
 * {@code get(relatif)} rate → "Asset not loaded"). Un handle Classpath au chemin relatif (avec
 * la racine des assets sur le classpath) restaure le comportement Android. Le ctor protégé
 * {@code (String, FileType)} de FileHandle est exposé ici. Porté de DragonSoul `DsFileHandle`.
 */
final class DhFileHandle extends FileHandle {
    DhFileHandle(String path, Files.FileType type) { super(path, type); }
}
