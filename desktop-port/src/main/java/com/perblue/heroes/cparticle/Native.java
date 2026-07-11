package com.perblue.heroes.cparticle;

import dhbackend.unidbg.UnidbgVM;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cparticle.Native} : dispatche vers le VRAI moteur de particules
 * d'origine (`libspine-native.so`) exécuté par unidbg ({@link dhbackend.unidbg.UnidbgVM}). Résout #NP-V3
 * (le parseur `.np` d'origine tourne). Câblage, pas récréation (PRINCIPLES §4).
 */
public class Native {
    public static void ensureLoaded() { UnidbgVM.get(); }

    public static String getLastParticleError() { return UnidbgVM.get().getLastParticleError(); }
    static int Effect_create(byte[] npBytes, int atlasHandle) { return UnidbgVM.get().effectCreate(npBytes, atlasHandle); }
    static int Effect_clone(int handle) { return UnidbgVM.get().effectClone(handle); }
    static void Effect_dispose(int handle) { UnidbgVM.get().effectDispose(handle); }
    static int Effect_getVertices(int handle, FloatBuffer verts, ShortBuffer drawCalls) { return UnidbgVM.get().effectGetVertices(handle, verts, drawCalls); }
    static int Effect_getVerticesAboveZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls) { return UnidbgVM.get().effectGetVerticesAboveZ(handle, z, verts, drawCalls); }
    static int Effect_getVerticesBelowZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls) { return UnidbgVM.get().effectGetVerticesBelowZ(handle, z, verts, drawCalls); }
    static void Effect_start(int handle) { UnidbgVM.get().effectStart(handle); }
    static void Effect_reset(int handle) { UnidbgVM.get().effectReset(handle); }
    static void Effect_kill(int handle) { UnidbgVM.get().effectKill(handle); }
    static void Effect_stopEmitting(int handle) { UnidbgVM.get().effectStopEmitting(handle); }
    static void Effect_setPositionXY(int handle, float x, float y) { UnidbgVM.get().effectSetPositionXY(handle, x, y); }
    static void Effect_setPositionXYZ(int handle, float x, float y, float z) { UnidbgVM.get().effectSetPositionXYZ(handle, x, y, z); }
    static void Effect_setRotation(int handle, float rot) { UnidbgVM.get().effectSetRotation(handle, rot); }
    static void Effect_setScale(int handle, float scale) { UnidbgVM.get().effectSetScale(handle, scale); }
    static boolean Effect_update(int handle, float dt) { return UnidbgVM.get().effectUpdate(handle, dt); }
    static boolean Effect_usesMultiply(int handle) { return UnidbgVM.get().effectUsesMultiply(handle); }
    static boolean Effect_usesZOffsets(int handle) { return UnidbgVM.get().effectUsesZOffsets(handle); }
}
