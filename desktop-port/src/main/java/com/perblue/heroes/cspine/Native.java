package com.perblue.heroes.cspine;

import dhbackend.unidbg.UnidbgVM;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cspine.Native} : signatures IDENTIQUES au jeu, mais chaque méthode
 * DISPATCHE vers le VRAI binaire natif d'origine (`libspine-native.so`) exécuté par unidbg
 * ({@link dhbackend.unidbg.UnidbgVM}). Ce n'est PAS une réécriture de spine — c'est de la plomberie qui
 * fait tourner le CODE D'ORIGINE de PerBlue (PRINCIPLES §4 : câblage, pas récréation).
 * Placé avant game.jar sur le classpath → remplace la classe du jeu (dont le {@code <clinit>} chargeait
 * la lib via SharedLibraryLoader, indisponible sur desktop x86_64).
 */
public class Native {
    public static void ensureLoaded() { UnidbgVM.get(); }

    static void Spine_init() { UnidbgVM.get(); }
    public static String getLastSpineError() { return UnidbgVM.get().getLastSpineError(); }
    public static void printUsedHandleReport() { UnidbgVM.get().printUsedHandleReport(); }

    static int Atlas_create(byte[] atlasBytes, boolean premultipliedAlpha) { return UnidbgVM.get().atlasCreate(atlasBytes, premultipliedAlpha); }
    static void Atlas_dispose(int handle) { UnidbgVM.get().atlasDispose(handle); }
    static boolean Atlas_getParams(int handle, int page, int[] out) { return UnidbgVM.get().atlasGetParams(handle, page, out); }
    static String Atlas_getTexture(int handle, int page) { return UnidbgVM.get().atlasGetTexture(handle, page); }

    static int SkeletonData_create(byte[] skelBytes, int atlasHandle) { return UnidbgVM.get().skeletonDataCreate(skelBytes, atlasHandle); }
    static void SkeletonData_dispose(int handle) { UnidbgVM.get().skeletonDataDispose(handle); }
    static float[] SkeletonData_getAnimationDurations(int handle) { return UnidbgVM.get().skeletonDataGetAnimationDurations(handle); }
    static int SkeletonData_getAnimationID(int handle, String name) { return UnidbgVM.get().skeletonDataGetAnimationID(handle, name); }
    static String[] SkeletonData_getAnimationNames(int handle) { return UnidbgVM.get().skeletonDataGetAnimationNames(handle); }
    static int SkeletonData_getBoneID(int handle, String name) { return UnidbgVM.get().skeletonDataGetBoneID(handle, name); }
    static String[] SkeletonData_getBoneNames(int handle) { return UnidbgVM.get().skeletonDataGetBoneNames(handle); }
    static String[] SkeletonData_getSkinNames(int handle) { return UnidbgVM.get().skeletonDataGetSkinNames(handle); }
    static String[] SkeletonData_getSlotNames(int handle) { return UnidbgVM.get().skeletonDataGetSlotNames(handle); }
    // Diagnostics (rapports) — NO-OP étiqueté (hors chemin de rendu ; pas de logique de jeu).
    static boolean SkeletonData_getStats(int handle, NativeSkeletonDataStats out) { return false; }
    static VertexWeightReportEntry[] SkeletonData_getVertexWeightReport(int handle, int slot) { return null; }

    static int Skeleton_create(int dataHandle) { return UnidbgVM.get().skeletonCreate(dataHandle); }
    static void Skeleton_dispose(int handle) { UnidbgVM.get().skeletonDispose(handle); }
    static void Skeleton_update(int handle, float dt) { UnidbgVM.get().skeletonUpdate(handle, dt); }
    static void Skeleton_updateWorldTransform(int handle) { UnidbgVM.get().skeletonUpdateWorldTransform(handle); }
    static void Skeleton_setToSetupPose(int handle) { UnidbgVM.get().skeletonSetToSetupPose(handle); }
    static void Skeleton_setColor(int handle, float r, float g, float b, float a) { UnidbgVM.get().skeletonSetColor(handle, r, g, b, a); }
    static void Skeleton_setTintBlack(int handle, float r, float g, float b) { UnidbgVM.get().skeletonSetTintBlack(handle, r, g, b); }
    static boolean Skeleton_setSkin(int handle, String name) { return UnidbgVM.get().skeletonSetSkin(handle, name); }
    static boolean Skeleton_setSlotEyeState(int handle, int slot, int state) { return UnidbgVM.get().skeletonSetSlotEyeState(handle, slot, state); }
    static void Skeleton_getBoneTransform(int handle, int boneId, float[] out, int off) { UnidbgVM.get().skeletonGetBoneTransform(handle, boneId, out, off); }
    static void Skeleton_getBoneTransforms(int handle, int[] boneIds, int idOff, float[] out, int outOff) { UnidbgVM.get().skeletonGetBoneTransforms(handle, boneIds, idOff, out, outOff); }
    static void Skeleton_setBoneTransform(int handle, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy) { UnidbgVM.get().skeletonSetBoneTransform(handle, boneId, x, y, rot, sx, sy, shx, shy); }
    static void Skeleton_getPosedBounds(int handle, float[] out) { UnidbgVM.get().skeletonGetPosedBounds(handle, out); }
    static int Skeleton_getVertices(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls) { return UnidbgVM.get().skeletonGetVertices(handle, verts, indices, drawCalls); }
    static int Skeleton_getVerticesAndBounds(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds) { return UnidbgVM.get().skeletonGetVerticesAndBounds(handle, verts, indices, drawCalls, bounds); }
    static int Skeleton_getVerticesAndBoundsGlitched(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds) { return UnidbgVM.get().skeletonGetVerticesAndBounds(handle, verts, indices, drawCalls, bounds); }

    static int AnimationStateData_create(int dataHandle, float defaultMix) { return UnidbgVM.get().animStateDataCreate(dataHandle, defaultMix); }
    static void AnimationStateData_dispose(int handle) { UnidbgVM.get().animStateDataDispose(handle); }
    static void AnimationStateData_setMix(int handle, int fromAnim, int toAnim, float duration) { UnidbgVM.get().animStateDataSetMix(handle, fromAnim, toAnim, duration); }

    static int AnimationState_create(int asdHandle) { return UnidbgVM.get().animStateCreate(asdHandle); }
    static void AnimationState_dispose(int handle) { UnidbgVM.get().animStateDispose(handle); }
    static void AnimationState_update(int handle, float dt) { UnidbgVM.get().animStateUpdate(handle, dt); }
    static void AnimationState_apply(int handle, int skeletonHandle) { UnidbgVM.get().animStateApply(handle, skeletonHandle); }
    static int AnimationState_setAnimation(int handle, int track, int animId, boolean loop) { return UnidbgVM.get().animStateSetAnimation(handle, track, animId, loop); }
    static int AnimationState_addAnimation(int handle, int track, int animId, boolean loop, float delay) { return UnidbgVM.get().animStateAddAnimation(handle, track, animId, loop, delay); }
    static void AnimationState_clearTracks(int handle) { UnidbgVM.get().animStateClearTracks(handle); }
    static int AnimationState_getCurrentAnimationID(int handle, int track) { return UnidbgVM.get().animStateGetCurrentAnimationID(handle, track); }
    static float AnimationState_getCurrentAnimationTime(int handle, int track) { return UnidbgVM.get().animStateGetCurrentAnimationTime(handle, track); }
    static boolean AnimationState_nextEvent(int handle, int[] out) { return UnidbgVM.get().animStateNextEvent(handle, out); }
}
