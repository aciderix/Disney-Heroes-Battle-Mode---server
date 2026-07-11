package com.perblue.heroes.cparticle;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Déclarations `native` recopiées à l'IDENTIQUE de {@code cparticle.Native} du jeu (via {@code javap}).
 * Sert UNIQUEMENT à générer les en-têtes JNI. Le vrai {@code cparticle.Native} du jeu tourne au runtime.
 */
public class Native {
    public static native String getLastParticleError();

    static native int Effect_create(byte[] npBytes, int atlasHandle);
    static native int Effect_clone(int handle);
    static native void Effect_dispose(int handle);

    static native int Effect_getVertices(int handle, FloatBuffer verts, ShortBuffer drawCalls);
    static native int Effect_getVerticesAboveZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls);
    static native int Effect_getVerticesBelowZ(int handle, float z, FloatBuffer verts, ShortBuffer drawCalls);

    static native void Effect_start(int handle);
    static native void Effect_reset(int handle);
    static native void Effect_kill(int handle);
    static native void Effect_stopEmitting(int handle);
    static native void Effect_setPositionXY(int handle, float x, float y);
    static native void Effect_setPositionXYZ(int handle, float x, float y, float z);
    static native void Effect_setRotation(int handle, float rot);
    static native void Effect_setScale(int handle, float scale);
    static native boolean Effect_update(int handle, float dt);
    static native boolean Effect_usesMultiply(int handle);
    static native boolean Effect_usesZOffsets(int handle);
}
