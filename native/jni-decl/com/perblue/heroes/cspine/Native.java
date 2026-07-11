package com.perblue.heroes.cspine;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Déclarations `native` recopiées à l'IDENTIQUE de la classe {@code cspine.Native} du jeu (relevées
 * par {@code javap} sur game.jar). Sert UNIQUEMENT à générer les en-têtes JNI ({@code javac -h}) avec
 * les noms manglés exacts. Non embarqué dans le port (le vrai {@code cspine.Native} du jeu tourne).
 */
public class Native {
    static native void Spine_init();
    public static native String getLastSpineError();
    public static native void printUsedHandleReport();

    static native int Atlas_create(byte[] atlasBytes, boolean premultipliedAlpha);
    static native void Atlas_dispose(int handle);
    static native boolean Atlas_getParams(int handle, int page, int[] out);
    static native String Atlas_getTexture(int handle, int page);

    static native int SkeletonData_create(byte[] skelBytes, int atlasHandle);
    static native void SkeletonData_dispose(int handle);
    static native float[] SkeletonData_getAnimationDurations(int handle);
    static native int SkeletonData_getAnimationID(int handle, String name);
    static native String[] SkeletonData_getAnimationNames(int handle);
    static native int SkeletonData_getBoneID(int handle, String name);
    static native String[] SkeletonData_getBoneNames(int handle);
    static native String[] SkeletonData_getSkinNames(int handle);
    static native String[] SkeletonData_getSlotNames(int handle);
    static native boolean SkeletonData_getStats(int handle, NativeSkeletonDataStats out);
    static native VertexWeightReportEntry[] SkeletonData_getVertexWeightReport(int handle, int slot);

    static native int Skeleton_create(int dataHandle);
    static native void Skeleton_dispose(int handle);
    static native void Skeleton_update(int handle, float dt);
    static native void Skeleton_updateWorldTransform(int handle);
    static native void Skeleton_setToSetupPose(int handle);
    static native void Skeleton_setColor(int handle, float r, float g, float b, float a);
    static native void Skeleton_setTintBlack(int handle, float r, float g, float b);
    static native boolean Skeleton_setSkin(int handle, String name);
    static native boolean Skeleton_setSlotEyeState(int handle, int slot, int state);
    static native void Skeleton_getBoneTransform(int handle, int boneId, float[] out, int off);
    static native void Skeleton_getBoneTransforms(int handle, int[] boneIds, int idOff, float[] out, int outOff);
    static native void Skeleton_setBoneTransform(int handle, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy);
    static native void Skeleton_getPosedBounds(int handle, float[] out);
    static native int Skeleton_getVertices(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls);
    static native int Skeleton_getVerticesAndBounds(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds);
    static native int Skeleton_getVerticesAndBoundsGlitched(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds);

    static native int AnimationStateData_create(int dataHandle, float defaultMix);
    static native void AnimationStateData_dispose(int handle);
    static native void AnimationStateData_setMix(int handle, int fromAnim, int toAnim, float duration);

    static native int AnimationState_create(int asdHandle);
    static native void AnimationState_dispose(int handle);
    static native void AnimationState_update(int handle, float dt);
    static native void AnimationState_apply(int handle, int skeletonHandle);
    static native int AnimationState_setAnimation(int handle, int track, int animId, boolean loop);
    static native int AnimationState_addAnimation(int handle, int track, int animId, boolean loop, float delay);
    static native void AnimationState_clearTracks(int handle);
    static native int AnimationState_getCurrentAnimationID(int handle, int track);
    static native float AnimationState_getCurrentAnimationTime(int handle, int track);
    static native boolean AnimationState_nextEvent(int handle, int[] out);
}
