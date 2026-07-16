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
 *
 * DEV : profilage de surface ({@code -Ddh.cspineprofile=1}, off par défaut) — compte les appels par méthode
 * pour identifier la sous-surface cspine réellement exercée (ex. par le combat headless, phase 0 de l'Opt.3).
 * Aucun effet en prod.
 */
public class Native {
    // ---- Profilage DEV (surface cspine) ----
    private static final boolean PROF = System.getProperty("dh.cspineprofile") != null
            && !"0".equals(System.getProperty("dh.cspineprofile"));
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> HITS
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static void p(String m) {
        if (PROF) HITS.computeIfAbsent(m, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }
    /** Remet les compteurs à zéro (à appeler juste avant la fenêtre à mesurer, ex. un combat). */
    public static void resetProfile() { HITS.clear(); }
    /** Rapport trié décroissant "méthode=appels". */
    public static String reportProfile() {
        StringBuilder b = new StringBuilder("[cspine-profile] ").append(HITS.size()).append(" méthodes appelées:\n");
        HITS.entrySet().stream()
            .sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum()))
            .forEach(e -> b.append(String.format("  %-42s %d%n", e.getKey(), e.getValue().sum())));
        return b.toString();
    }

    public static void ensureLoaded() { UnidbgVM.get(); }

    static void Spine_init() { UnidbgVM.get(); }
    public static String getLastSpineError() { return UnidbgVM.get().getLastSpineError(); }
    public static void printUsedHandleReport() { UnidbgVM.get().printUsedHandleReport(); }

    static int Atlas_create(byte[] atlasBytes, boolean premultipliedAlpha) { p("Atlas_create"); return UnidbgVM.get().atlasCreate(atlasBytes, premultipliedAlpha); }
    static void Atlas_dispose(int handle) { p("Atlas_dispose"); UnidbgVM.get().atlasDispose(handle); }
    static boolean Atlas_getParams(int handle, int page, int[] out) { p("Atlas_getParams"); return UnidbgVM.get().atlasGetParams(handle, page, out); }
    static String Atlas_getTexture(int handle, int page) { p("Atlas_getTexture"); return UnidbgVM.get().atlasGetTexture(handle, page); }

    static int SkeletonData_create(byte[] skelBytes, int atlasHandle) { p("SkeletonData_create"); return UnidbgVM.get().skeletonDataCreate(skelBytes, atlasHandle); }
    static void SkeletonData_dispose(int handle) { p("SkeletonData_dispose"); UnidbgVM.get().skeletonDataDispose(handle); }
    static float[] SkeletonData_getAnimationDurations(int handle) { p("SkeletonData_getAnimationDurations"); return UnidbgVM.get().skeletonDataGetAnimationDurations(handle); }
    static int SkeletonData_getAnimationID(int handle, String name) { p("SkeletonData_getAnimationID"); return UnidbgVM.get().skeletonDataGetAnimationID(handle, name); }
    static String[] SkeletonData_getAnimationNames(int handle) { p("SkeletonData_getAnimationNames"); return UnidbgVM.get().skeletonDataGetAnimationNames(handle); }
    static int SkeletonData_getBoneID(int handle, String name) { p("SkeletonData_getBoneID"); return UnidbgVM.get().skeletonDataGetBoneID(handle, name); }
    static String[] SkeletonData_getBoneNames(int handle) { p("SkeletonData_getBoneNames"); return UnidbgVM.get().skeletonDataGetBoneNames(handle); }
    static String[] SkeletonData_getSkinNames(int handle) { p("SkeletonData_getSkinNames"); return UnidbgVM.get().skeletonDataGetSkinNames(handle); }
    static String[] SkeletonData_getSlotNames(int handle) { p("SkeletonData_getSlotNames"); return UnidbgVM.get().skeletonDataGetSlotNames(handle); }
    // Diagnostics (rapports) — NO-OP étiqueté (hors chemin de rendu ; pas de logique de jeu).
    static boolean SkeletonData_getStats(int handle, NativeSkeletonDataStats out) { p("SkeletonData_getStats"); return false; }
    static VertexWeightReportEntry[] SkeletonData_getVertexWeightReport(int handle, int slot) { p("SkeletonData_getVertexWeightReport"); return null; }

    static int Skeleton_create(int dataHandle) { p("Skeleton_create"); return UnidbgVM.get().skeletonCreate(dataHandle); }
    static void Skeleton_dispose(int handle) { p("Skeleton_dispose"); UnidbgVM.get().skeletonDispose(handle); }
    static void Skeleton_update(int handle, float dt) { p("Skeleton_update"); UnidbgVM.get().skeletonUpdate(handle, dt); }
    static void Skeleton_updateWorldTransform(int handle) { p("Skeleton_updateWorldTransform"); UnidbgVM.get().skeletonUpdateWorldTransform(handle); }
    static void Skeleton_setToSetupPose(int handle) { p("Skeleton_setToSetupPose"); UnidbgVM.get().skeletonSetToSetupPose(handle); }
    static void Skeleton_setColor(int handle, float r, float g, float b, float a) { p("Skeleton_setColor"); UnidbgVM.get().skeletonSetColor(handle, r, g, b, a); }
    static void Skeleton_setTintBlack(int handle, float r, float g, float b) { p("Skeleton_setTintBlack"); UnidbgVM.get().skeletonSetTintBlack(handle, r, g, b); }
    static boolean Skeleton_setSkin(int handle, String name) { p("Skeleton_setSkin"); return UnidbgVM.get().skeletonSetSkin(handle, name); }
    static boolean Skeleton_setSlotEyeState(int handle, int slot, int state) { p("Skeleton_setSlotEyeState"); return UnidbgVM.get().skeletonSetSlotEyeState(handle, slot, state); }
    static void Skeleton_getBoneTransform(int handle, int boneId, float[] out, int off) { p("Skeleton_getBoneTransform"); UnidbgVM.get().skeletonGetBoneTransform(handle, boneId, out, off); }
    static void Skeleton_getBoneTransforms(int handle, int[] boneIds, int idOff, float[] out, int outOff) { p("Skeleton_getBoneTransforms"); UnidbgVM.get().skeletonGetBoneTransforms(handle, boneIds, idOff, out, outOff); }
    static void Skeleton_setBoneTransform(int handle, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy) { p("Skeleton_setBoneTransform"); UnidbgVM.get().skeletonSetBoneTransform(handle, boneId, x, y, rot, sx, sy, shx, shy); }
    static void Skeleton_getPosedBounds(int handle, float[] out) { p("Skeleton_getPosedBounds"); UnidbgVM.get().skeletonGetPosedBounds(handle, out); }
    static int Skeleton_getVertices(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls) { p("Skeleton_getVertices"); return UnidbgVM.get().skeletonGetVertices(handle, verts, indices, drawCalls); }
    static int Skeleton_getVerticesAndBounds(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds) { p("Skeleton_getVerticesAndBounds"); return UnidbgVM.get().skeletonGetVerticesAndBounds(handle, verts, indices, drawCalls, bounds); }
    static int Skeleton_getVerticesAndBoundsGlitched(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds) { p("Skeleton_getVerticesAndBoundsGlitched"); return UnidbgVM.get().skeletonGetVerticesAndBounds(handle, verts, indices, drawCalls, bounds); }

    static int AnimationStateData_create(int dataHandle, float defaultMix) { p("AnimationStateData_create"); return UnidbgVM.get().animStateDataCreate(dataHandle, defaultMix); }
    static void AnimationStateData_dispose(int handle) { p("AnimationStateData_dispose"); UnidbgVM.get().animStateDataDispose(handle); }
    static void AnimationStateData_setMix(int handle, int fromAnim, int toAnim, float duration) { p("AnimationStateData_setMix"); UnidbgVM.get().animStateDataSetMix(handle, fromAnim, toAnim, duration); }

    static int AnimationState_create(int asdHandle) { p("AnimationState_create"); return UnidbgVM.get().animStateCreate(asdHandle); }
    static void AnimationState_dispose(int handle) { p("AnimationState_dispose"); UnidbgVM.get().animStateDispose(handle); }
    static void AnimationState_update(int handle, float dt) { p("AnimationState_update"); UnidbgVM.get().animStateUpdate(handle, dt); }
    static void AnimationState_apply(int handle, int skeletonHandle) { p("AnimationState_apply"); UnidbgVM.get().animStateApply(handle, skeletonHandle); }
    static int AnimationState_setAnimation(int handle, int track, int animId, boolean loop) { p("AnimationState_setAnimation"); return UnidbgVM.get().animStateSetAnimation(handle, track, animId, loop); }
    static int AnimationState_addAnimation(int handle, int track, int animId, boolean loop, float delay) { p("AnimationState_addAnimation"); return UnidbgVM.get().animStateAddAnimation(handle, track, animId, loop, delay); }
    static void AnimationState_clearTracks(int handle) { p("AnimationState_clearTracks"); UnidbgVM.get().animStateClearTracks(handle); }
    static int AnimationState_getCurrentAnimationID(int handle, int track) { p("AnimationState_getCurrentAnimationID"); return UnidbgVM.get().animStateGetCurrentAnimationID(handle, track); }
    static float AnimationState_getCurrentAnimationTime(int handle, int track) { p("AnimationState_getCurrentAnimationTime"); return UnidbgVM.get().animStateGetCurrentAnimationTime(handle, track); }
    static boolean AnimationState_nextEvent(int handle, int[] out) { p("AnimationState_nextEvent"); return UnidbgVM.get().animStateNextEvent(handle, out); }
}
