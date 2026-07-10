package com.perblue.heroes.cspine;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.assets.loaders.TextureLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData.Page;
import com.badlogic.gdx.utils.Array;

/**
 * Loader d'atlas Spine (shadow de cspine.NativeAtlasLoader). Réutilise la mécanique standard
 * libGDX : les textures de pages du {@code .atlas} sont chargées par l'AssetManager (pipeline
 * ETC1 du jeu déjà fonctionnel), puis assemblées en {@link TextureAtlas}. Signatures BRUTES
 * (le libGDX de PerBlue a les génériques effacés). Fidélité : RÉEL.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class NativeAtlasLoader extends SynchronousAssetLoader {

    public NativeAtlasLoader(FileHandleResolver resolver) { super(resolver); }

    /**
     * Le jeu suffixe les atlas Spine natifs par {@code @<variante>} (ex. {@code .atlas@native}) pour
     * router vers ce loader. Comme l'original ({@code lastIndexOf('@')} + {@code substring} + re-resolve),
     * on retire ce suffixe et on re-résout le vrai fichier atlas.
     */
    private FileHandle real(String fileName, FileHandle file) {
        int at = fileName.lastIndexOf('@');
        return at < 0 ? file : resolve(fileName.substring(0, at));
    }

    private TextureAtlasData data(FileHandle file) {
        return new TextureAtlasData(file, file.parent(), false);
    }

    @Override
    public Array getDependencies(String fileName, FileHandle file, AssetLoaderParameters p) {
      try {
        NativeAtlasParameter param = (NativeAtlasParameter) p;
        Array deps = new Array();
        TextureLoader.TextureParameter tp = param != null && param.texParam != null
                ? param.texParam : new TextureLoader.TextureParameter();
        Array pages = data(real(fileName, file)).getPages();
        for (int i = 0; i < pages.size; i++) {
            Page page = (Page) pages.get(i);
            deps.add(new AssetDescriptor(page.textureFile, Texture.class, tp));
        }
        return deps;
      } catch (Throwable e) {
        System.err.println("[NativeAtlasLoader] getDependencies FAILED for " + fileName + ": " + e);
        throw e;
      }
    }

    @Override
    public Object load(AssetManager am, String fileName, FileHandle file, AssetLoaderParameters p) {
      try {
        TextureAtlasData d = data(real(fileName, file));
        Array files = new Array();
        Array pages = d.getPages();
        for (int i = 0; i < pages.size; i++) {
            Page page = (Page) pages.get(i);
            page.texture = (Texture) am.get(page.textureFile.path(), Texture.class); // textures préchargées
            files.add(page.textureFile.path());
        }
        NativeAtlas na = new NativeAtlas();
        na.set(new TextureAtlas(d, false), files);
        return na;
      } catch (Throwable e) {
        System.err.println("[NativeAtlasLoader] load FAILED for " + fileName + ": " + e);
        throw e;
      }
    }

    public static class NativeAtlasParameter extends AssetLoaderParameters<NativeAtlas> {
        public TextureLoader.TextureParameter texParam;
        public NativeAtlasParameter() {}
    }
}
