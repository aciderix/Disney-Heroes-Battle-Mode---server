package com.perblue.heroes.cspine;

import com.badlogic.gdx.assets.loaders.TextureLoader;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * Shadow de {@code cspine.NativeAtlas} : porte un {@link TextureAtlas} libGDX standard (les
 * régions Spine y sont référencées par nom). Construit par {@link NativeAtlasLoader} à partir
 * du fichier {@code .atlas} + des textures de pages chargées par l'AssetManager (le pipeline de
 * textures du jeu — ETC1 compris — fonctionne déjà). Fidélité : RÉEL.
 */
public class NativeAtlas implements Disposable, AutoCloseable {
    public static final NativeAtlas NULL = new NativeAtlas();
    public int handle;

    TextureAtlas atlas; // package : rempli par le loader
    Array<String> textureFiles = new Array<>();

    public NativeAtlas() {}

    void set(TextureAtlas atlas, Array<String> files) { this.atlas = atlas; this.textureFiles = files; }

    TextureAtlas gdx() { return atlas; }

    public boolean load(byte[] data, boolean b) { return atlas != null; }

    public void setLoaderParams(TextureLoader.TextureParameter p, int i) { /* pages chargées via l'AssetManager */ }

    public int[] getParams(int i) { return new int[0]; }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Array<Texture> getTextures() {
        Array<Texture> out = new Array<>();
        if (atlas != null) for (Object t : atlas.getTextures()) out.add((Texture) t);
        return out;
    }

    public Array<String> getTextureFiles() { return textureFiles; }

    // Décodage des codes de page. Sans encodage natif connu, on renvoie le défaut fourni par le
    // jeu (les vraies valeurs viennent du .atlas via TextureAtlas). Non critique.
    public static Texture.TextureFilter getFilter(int code, Texture.TextureFilter def) { return def; }
    public static Pixmap.Format getFormat(int code, Pixmap.Format def) { return def; }
    public static Texture.TextureWrap getWrap(int code, Texture.TextureWrap def) { return def; }

    @Override public void close() { dispose(); }
    @Override public void dispose() { if (atlas != null) { atlas.dispose(); atlas = null; } }
}
