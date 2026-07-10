package com.perblue.heroes.cspine;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;

/**
 * Loader de skeleton Spine (shadow de cspine.NativeSkeletonDataLoader). Dépend de l'atlas
 * (chargé d'abord), puis lit le {@code .skel} et construit le {@link NativeSkeletonData}.
 * Signatures BRUTES (génériques effacés côté PerBlue). Fidélité : RÉEL.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class NativeSkeletonDataLoader extends SynchronousAssetLoader {

    public NativeSkeletonDataLoader(FileHandleResolver resolver) { super(resolver); }

    /** Retire le suffixe {@code @<variante>} du chemin {@code .skel} et re-résout (cf. loader d'origine). */
    private FileHandle real(String fileName, FileHandle file) {
        int at = fileName.lastIndexOf('@');
        return at < 0 ? file : resolve(fileName.substring(0, at));
    }

    @Override
    public Array getDependencies(String fileName, FileHandle file, AssetLoaderParameters p) {
        NativeSkeletonDataParameter param = (NativeSkeletonDataParameter) p;
        Array deps = new Array();
        if (param != null && param.atlasFile != null) {
            deps.add(new AssetDescriptor(param.atlasFile, NativeAtlas.class, param.atlasParam));
        }
        return deps;
    }

    @Override
    public Object load(AssetManager am, String fileName, FileHandle file, AssetLoaderParameters p) {
        NativeSkeletonDataParameter param = (NativeSkeletonDataParameter) p;
        NativeAtlas atlas = param != null && param.atlasFile != null
                ? (NativeAtlas) am.get(param.atlasFile, NativeAtlas.class) : NativeAtlas.NULL;
        return NativeSkeletonData.fromFile(real(fileName, file), atlas, fileName);
    }

    public static class NativeSkeletonDataParameter extends AssetLoaderParameters<NativeSkeletonData> {
        public String atlasFile;
        public NativeAtlasLoader.NativeAtlasParameter atlasParam;
        public NativeSkeletonDataParameter() {}
    }
}
