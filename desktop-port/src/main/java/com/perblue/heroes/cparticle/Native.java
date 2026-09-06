package com.perblue.heroes.cparticle;

import dhbackend.unidbg.UnidbgVM;
import dhbackend.jparticle.JavaParticleEngine;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cparticle.Native}. Deux backends :
 *  - DÉFAUT : moteur d'origine (`libspine-native.so`) via unidbg ({@link dhbackend.unidbg.UnidbgVM}).
 *  - {@code -Ddh.particlebackend=java} + resolver d'atlas enregistré : le VRAI moteur Java du jeu
 *    (`com.badlogic.gdx.graphics.g2d.ParticleEmitter`) via {@link dhbackend.jparticle.JavaParticleEngine}
 *    — pas d'émulation, pas de réimplémentation (docs/PARTICLE_REUSE.md, §3/§4). Câblage, pas récréation.
 */
public class Native {
    static {
        // g296 : backend particules Java -> enregistre le resolver d'atlas (parse le .atlas, uv ; sprite GL lazy).
        if ("java".equalsIgnoreCase(System.getProperty("dh.particlebackend")))
            try { JavaParticleEngine.setResolver(new dhbackend.jparticle.ParticleAtlasResolver()); } catch (Throwable ignore) {}
    }
    private static boolean jpe() { return JavaParticleEngine.enabled(); }
    public static void ensureLoaded() { UnidbgVM.get(); }

    public static String getLastParticleError() { return UnidbgVM.get().getLastParticleError(); }
    static int Effect_create(byte[] npBytes, int atlasHandle) {
        if (jpe()) return JavaParticleEngine.get().create(npBytes, atlasHandle);
        return UnidbgVM.get().effectCreate(npBytes, dhbackend.jnispine.AtlasBridge.toUnidbg(atlasHandle));
    }
    static int Effect_clone(int handle) { return UnidbgVM.get().effectClone(handle); }
    static void Effect_dispose(int handle) { if (jpe()) { JavaParticleEngine.get().dispose(handle); return; } UnidbgVM.get().effectDispose(handle); }
    static int Effect_getVertices(int handle, FloatBuffer verts, ShortBuffer drawCalls) { if (jpe()) return JavaParticleEngine.get().getVertices(handle, verts, drawCalls); return UnidbgVM.get().effectGetVertices(handle, verts, drawCalls); }
    static int Effect_getVerticesAboveZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls) { if (jpe()) return JavaParticleEngine.get().getVertices(handle, verts, drawCalls); return UnidbgVM.get().effectGetVerticesAboveZ(handle, z, verts, drawCalls); }
    static int Effect_getVerticesBelowZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls) { if (jpe()) return 0; return UnidbgVM.get().effectGetVerticesBelowZ(handle, z, verts, drawCalls); }
    static void Effect_start(int handle) { if (jpe()) { JavaParticleEngine.get().start(handle); return; } UnidbgVM.get().effectStart(handle); }
    static void Effect_reset(int handle) { if (jpe()) { JavaParticleEngine.get().start(handle); return; } UnidbgVM.get().effectReset(handle); }
    static void Effect_kill(int handle) { if (jpe()) { JavaParticleEngine.get().dispose(handle); return; } UnidbgVM.get().effectKill(handle); }
    static void Effect_stopEmitting(int handle) { if (jpe()) return; UnidbgVM.get().effectStopEmitting(handle); }
    static void Effect_setPositionXY(int handle, float x, float y) { if (jpe()) { JavaParticleEngine.get().setPosition(handle, x, y); return; } UnidbgVM.get().effectSetPositionXY(handle, x, y); }
    static void Effect_setPositionXYZ(int handle, float x, float y, float z) { if (jpe()) { JavaParticleEngine.get().setPosition(handle, x, y); return; } UnidbgVM.get().effectSetPositionXYZ(handle, x, y, z); }
    static void Effect_setRotation(int handle, float rot) { if (jpe()) { JavaParticleEngine.get().setRotation(handle, rot); return; } UnidbgVM.get().effectSetRotation(handle, rot); }
    static void Effect_setScale(int handle, float scale) { if (jpe()) return; UnidbgVM.get().effectSetScale(handle, scale); }
    static boolean Effect_update(int handle, float dt) { if (jpe()) return JavaParticleEngine.get().update(handle, dt); return UnidbgVM.get().effectUpdate(handle, dt); }
    static boolean Effect_usesMultiply(int handle) { if (jpe()) return false; return UnidbgVM.get().effectUsesMultiply(handle); }
    static boolean Effect_usesZOffsets(int handle) { if (jpe()) return false; return UnidbgVM.get().effectUsesZOffsets(handle); }
}
