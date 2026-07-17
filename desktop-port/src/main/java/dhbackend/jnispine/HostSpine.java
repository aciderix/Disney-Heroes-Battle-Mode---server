package dhbackend.jnispine;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Backend Opt.3 « JNI natif » (#28) — exécute le VRAI runtime <b>spine-c officiel 3.6</b> (le même que celui
 * embarqué par le jeu, via la colle JNI d'origine {@code native/src/cspine_jni.c}) compilé pour l'HÔTE x86-64
 * ({@code libhostspine64.so}) et appelé en <b>JNI RÉEL</b> — PAS d'émulation ARM (unidbg), PAS de réécriture Java.
 *
 * <p>C'est « l'Opt.2 sans unidbg » : le même code natif d'origine, compilé pour la machine au lieu d'être émulé.
 * Fidélité PAR CONSTRUCTION (vrai spine-c : mixing, clear de track, etc. corrects d'office). Les symboles JNI de
 * la lib sont renommés {@code Java_dhbackend_jnispine_HostSpine_*} (rename mécanique de la colle, logique C
 * inchangée) pour se lier à cette classe sans toucher au shadow {@code cspine.Native}.
 *
 * <p>Activé par {@code -Ddh.spinebackend=jni}. La lib est chargée depuis {@code -Ddh.hostspine=<chemin>}.
 * Sous-ensemble COMBAT (Phase 0) ; {@code getStats}/{@code getVertexWeightReport} (types jeu, diag) omis.
 */
public final class HostSpine {
    private HostSpine() {}
    private static volatile boolean loaded = false;
    public static synchronized void ensureLoaded() {
        if (loaded) return;
        String p = System.getProperty("dh.hostspine");
        if (p == null) throw new IllegalStateException("dh.hostspine (chemin libhostspine64.so) non défini");
        System.load(p);
        loaded = true;
    }

    public static native void Spine_init();
    public static native String getLastSpineError();

    public static native int Atlas_create(byte[] atlasBytes, boolean premultipliedAlpha);
    public static native void Atlas_dispose(int handle);
    public static native boolean Atlas_getParams(int handle, int page, int[] out);
    public static native String Atlas_getTexture(int handle, int page);

    public static native int SkeletonData_create(byte[] skelBytes, int atlasHandle);
    public static native void SkeletonData_dispose(int handle);
    public static native float[] SkeletonData_getAnimationDurations(int handle);
    public static native int SkeletonData_getAnimationID(int handle, String name);
    public static native String[] SkeletonData_getAnimationNames(int handle);
    public static native int SkeletonData_getBoneID(int handle, String name);
    public static native String[] SkeletonData_getBoneNames(int handle);
    public static native String[] SkeletonData_getSkinNames(int handle);
    public static native String[] SkeletonData_getSlotNames(int handle);

    public static native int Skeleton_create(int dataHandle);
    public static native void Skeleton_dispose(int handle);
    public static native void Skeleton_update(int handle, float dt);
    public static native void Skeleton_updateWorldTransform(int handle);
    public static native void Skeleton_setToSetupPose(int handle);
    public static native void Skeleton_setColor(int handle, float r, float g, float b, float a);
    public static native void Skeleton_setTintBlack(int handle, float r, float g, float b);
    public static native boolean Skeleton_setSkin(int handle, String name);
    public static native boolean Skeleton_setSlotEyeState(int handle, int slot, int state);
    public static native void Skeleton_getBoneTransform(int handle, int boneId, float[] out, int off);
    public static native void Skeleton_getBoneTransforms(int handle, int[] boneIds, int idOff, float[] out, int outOff);
    public static native void Skeleton_setBoneTransform(int handle, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy);
    public static native void Skeleton_getPosedBounds(int handle, float[] out);
    public static native int Skeleton_getVertices(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls);
    public static native int Skeleton_getVerticesAndBounds(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds);
    public static native int Skeleton_getVerticesAndBoundsGlitched(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds);

    public static native int AnimationStateData_create(int dataHandle, float defaultMix);
    public static native void AnimationStateData_dispose(int handle);
    public static native void AnimationStateData_setMix(int handle, int fromAnim, int toAnim, float duration);

    public static native int AnimationState_create(int asdHandle);
    public static native void AnimationState_dispose(int handle);
    public static native void AnimationState_update(int handle, float dt);
    public static native void AnimationState_apply(int handle, int skeletonHandle);
    public static native int AnimationState_setAnimation(int handle, int track, int animId, boolean loop);
    public static native int AnimationState_addAnimation(int handle, int track, int animId, boolean loop, float delay);
    public static native void AnimationState_clearTracks(int handle);
    public static native int AnimationState_getCurrentAnimationID(int handle, int track);
    public static native float AnimationState_getCurrentAnimationTime(int handle, int track);
    public static native boolean AnimationState_nextEvent(int handle, int[] out);
}
