package com.perblue.heroes.cspine;

import dhbackend.spine.JavaSpineBackend;
import dhbackend.unidbg.UnidbgVM;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cspine.Native} : signatures IDENTIQUES au jeu. Par défaut chaque méthode
 * DISPATCHE vers le VRAI binaire natif d'origine (`libspine-native.so`) via unidbg ({@link UnidbgVM}) — le CODE
 * D'ORIGINE de PerBlue tourne (PRINCIPLES §4). Placé avant game.jar sur le classpath → remplace la classe du jeu.
 *
 * <p><b>Backend Opt.3 (#28)</b> : avec {@code -Ddh.spinebackend=java}, la sous-surface d'ANIMATION du combat
 * (SkeletonData/Skeleton/AnimationState/AnimationStateData) est routée vers {@link JavaSpineBackend} (runtime
 * Java spine-libgdx-perblue, PAS d'émulation ARM) au lieu d'unidbg. L'ATLAS/textures restent sur unidbg (setup,
 * hors chemin combat). But : combat autoritatif ~100× plus rapide, À CERTIFIER contre l'oracle unidbg.
 *
 * <p>DEV : profilage de surface ({@code -Ddh.cspineprofile=1}, off par défaut). Aucun effet en prod.
 */
public class Native {
    /** Opt.3 : router l'animation vers le runtime Java spine au lieu d'unidbg. */
    private static final boolean JAVA = "java".equalsIgnoreCase(System.getProperty("dh.spinebackend", ""));
    private static final JavaSpineBackend JB = JavaSpineBackend.INSTANCE;

    // ---- Profilage DEV (surface cspine) ----
    private static final boolean PROF = System.getProperty("dh.cspineprofile") != null
            && !"0".equals(System.getProperty("dh.cspineprofile"));
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> HITS
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static void p(String m) {
        if (PROF) HITS.computeIfAbsent(m, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }
    // Diag Opt.3 : le temps d'animation avance-t-il ? quelles animations sont jouées/courantes ?
    static volatile float DIAG_maxAnimTime = 0;
    static volatile int DIAG_setAnimMin = Integer.MAX_VALUE, DIAG_setAnimMax = Integer.MIN_VALUE, DIAG_setAnimNeg = 0;
    static final long[] DIAG_curIdHist = new long[32];   // histogramme des getCurrentAnimationID renvoyés
    public static void resetProfile() { HITS.clear(); DIAG_maxAnimTime = 0; DIAG_setAnimMin = Integer.MAX_VALUE; DIAG_setAnimMax = Integer.MIN_VALUE; DIAG_setAnimNeg = 0; java.util.Arrays.fill(DIAG_curIdHist, 0); }
    public static String reportProfile() {
        StringBuilder hist = new StringBuilder();
        for (int i = 0; i < DIAG_curIdHist.length; i++) if (DIAG_curIdHist[i] > 0) hist.append(" id").append(i).append('=').append(DIAG_curIdHist[i]);
        StringBuilder b = new StringBuilder("[cspine-profile] backend=" + (JAVA ? "java" : "unidbg") + " "
                + HITS.size() + " méthodes ; maxAnimTime=" + DIAG_maxAnimTime + "s setAnimId=["
                + DIAG_setAnimMin + ".." + DIAG_setAnimMax + "] neg=" + DIAG_setAnimNeg
                + "\n[cspine-profile] curIdHist:" + hist + "\n");
        if (JAVA) b.append(JavaSpineBackend.reportCurNames());
        HITS.entrySet().stream()
            .sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum()))
            .forEach(e -> b.append(String.format("  %-42s %d%n", e.getKey(), e.getValue().sum())));
        return b.toString();
    }

    public static void ensureLoaded() { UnidbgVM.get(); }

    static void Spine_init() { UnidbgVM.get(); }
    public static String getLastSpineError() { return UnidbgVM.get().getLastSpineError(); }
    public static void printUsedHandleReport() { UnidbgVM.get().printUsedHandleReport(); }

    // ---- Atlas : reste sur unidbg (setup textures, hors chemin combat) ----
    static int Atlas_create(byte[] atlasBytes, boolean premultipliedAlpha) { p("Atlas_create"); return UnidbgVM.get().atlasCreate(atlasBytes, premultipliedAlpha); }
    static void Atlas_dispose(int handle) { p("Atlas_dispose"); UnidbgVM.get().atlasDispose(handle); }
    static boolean Atlas_getParams(int handle, int page, int[] out) { p("Atlas_getParams"); return UnidbgVM.get().atlasGetParams(handle, page, out); }
    static String Atlas_getTexture(int handle, int page) { p("Atlas_getTexture"); return UnidbgVM.get().atlasGetTexture(handle, page); }

    // ---- SkeletonData : routable Java (Opt.3) ----
    static int SkeletonData_create(byte[] skelBytes, int atlasHandle) { p("SkeletonData_create"); return JAVA ? JB.SkeletonData_create(skelBytes, atlasHandle) : UnidbgVM.get().skeletonDataCreate(skelBytes, atlasHandle); }
    static void SkeletonData_dispose(int handle) { p("SkeletonData_dispose"); if (JAVA) JB.SkeletonData_dispose(handle); else UnidbgVM.get().skeletonDataDispose(handle); }
    static float[] SkeletonData_getAnimationDurations(int handle) { p("SkeletonData_getAnimationDurations"); return JAVA ? JB.SkeletonData_getAnimationDurations(handle) : UnidbgVM.get().skeletonDataGetAnimationDurations(handle); }
    static int SkeletonData_getAnimationID(int handle, String name) { p("SkeletonData_getAnimationID"); return JAVA ? JB.SkeletonData_getAnimationID(handle, name) : UnidbgVM.get().skeletonDataGetAnimationID(handle, name); }
    static String[] SkeletonData_getAnimationNames(int handle) { p("SkeletonData_getAnimationNames"); return JAVA ? JB.SkeletonData_getAnimationNames(handle) : UnidbgVM.get().skeletonDataGetAnimationNames(handle); }
    static int SkeletonData_getBoneID(int handle, String name) { p("SkeletonData_getBoneID"); return JAVA ? JB.SkeletonData_getBoneID(handle, name) : UnidbgVM.get().skeletonDataGetBoneID(handle, name); }
    static String[] SkeletonData_getBoneNames(int handle) { p("SkeletonData_getBoneNames"); return JAVA ? JB.SkeletonData_getBoneNames(handle) : UnidbgVM.get().skeletonDataGetBoneNames(handle); }
    static String[] SkeletonData_getSkinNames(int handle) { p("SkeletonData_getSkinNames"); return JAVA ? JB.SkeletonData_getSkinNames(handle) : UnidbgVM.get().skeletonDataGetSkinNames(handle); }
    static String[] SkeletonData_getSlotNames(int handle) { p("SkeletonData_getSlotNames"); return JAVA ? JB.SkeletonData_getSlotNames(handle) : UnidbgVM.get().skeletonDataGetSlotNames(handle); }
    // Diagnostics — NO-OP étiqueté (identique unidbg/java ; pas de logique de jeu).
    static boolean SkeletonData_getStats(int handle, NativeSkeletonDataStats out) { p("SkeletonData_getStats"); return false; }
    static VertexWeightReportEntry[] SkeletonData_getVertexWeightReport(int handle, int slot) { p("SkeletonData_getVertexWeightReport"); return null; }

    // ---- Skeleton : routable Java (Opt.3) ----
    static int Skeleton_create(int dataHandle) { p("Skeleton_create"); return JAVA ? JB.Skeleton_create(dataHandle) : UnidbgVM.get().skeletonCreate(dataHandle); }
    static void Skeleton_dispose(int handle) { p("Skeleton_dispose"); if (JAVA) JB.Skeleton_dispose(handle); else UnidbgVM.get().skeletonDispose(handle); }
    static void Skeleton_update(int handle, float dt) { p("Skeleton_update"); if (JAVA) JB.Skeleton_update(handle, dt); else UnidbgVM.get().skeletonUpdate(handle, dt); }
    static void Skeleton_updateWorldTransform(int handle) { p("Skeleton_updateWorldTransform"); if (JAVA) JB.Skeleton_updateWorldTransform(handle); else UnidbgVM.get().skeletonUpdateWorldTransform(handle); }
    static void Skeleton_setToSetupPose(int handle) { p("Skeleton_setToSetupPose"); if (JAVA) JB.Skeleton_setToSetupPose(handle); else UnidbgVM.get().skeletonSetToSetupPose(handle); }
    static void Skeleton_setColor(int handle, float r, float g, float b, float a) { p("Skeleton_setColor"); if (JAVA) JB.Skeleton_setColor(handle, r, g, b, a); else UnidbgVM.get().skeletonSetColor(handle, r, g, b, a); }
    static void Skeleton_setTintBlack(int handle, float r, float g, float b) { p("Skeleton_setTintBlack"); if (JAVA) JB.Skeleton_setTintBlack(handle, r, g, b); else UnidbgVM.get().skeletonSetTintBlack(handle, r, g, b); }
    static boolean Skeleton_setSkin(int handle, String name) { p("Skeleton_setSkin"); return JAVA ? JB.Skeleton_setSkin(handle, name) : UnidbgVM.get().skeletonSetSkin(handle, name); }
    static boolean Skeleton_setSlotEyeState(int handle, int slot, int state) { p("Skeleton_setSlotEyeState"); return JAVA ? JB.Skeleton_setSlotEyeState(handle, slot, state) : UnidbgVM.get().skeletonSetSlotEyeState(handle, slot, state); }
    static volatile int DIAG_btLogged = 0;
    static void Skeleton_getBoneTransform(int handle, int boneId, float[] out, int off) { p("Skeleton_getBoneTransform"); if (JAVA) JB.Skeleton_getBoneTransform(handle, boneId, out, off); else UnidbgVM.get().skeletonGetBoneTransform(handle, boneId, out, off); if (PROF && DIAG_btLogged < 6) { DIAG_btLogged++; System.out.printf("[bt] skel=%d bone=%d -> x=%.3f y=%.3f rot=%.3f sx=%.3f sy=%.3f%n", handle, boneId, out[off], out[off+1], out[off+2], out[off+3], out[off+4]); } }
    static void Skeleton_getBoneTransforms(int handle, int[] boneIds, int idOff, float[] out, int outOff) { p("Skeleton_getBoneTransforms"); if (JAVA) JB.Skeleton_getBoneTransforms(handle, boneIds, idOff, out, outOff); else UnidbgVM.get().skeletonGetBoneTransforms(handle, boneIds, idOff, out, outOff); }
    static void Skeleton_setBoneTransform(int handle, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy) { p("Skeleton_setBoneTransform"); if (JAVA) JB.Skeleton_setBoneTransform(handle, boneId, x, y, rot, sx, sy, shx, shy); else UnidbgVM.get().skeletonSetBoneTransform(handle, boneId, x, y, rot, sx, sy, shx, shy); }
    static void Skeleton_getPosedBounds(int handle, float[] out) { p("Skeleton_getPosedBounds"); if (JAVA) JB.Skeleton_getPosedBounds(handle, out); else UnidbgVM.get().skeletonGetPosedBounds(handle, out); }
    static int Skeleton_getVertices(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls) { p("Skeleton_getVertices"); return JAVA ? JB.Skeleton_getVertices(handle, verts, indices, drawCalls) : UnidbgVM.get().skeletonGetVertices(handle, verts, indices, drawCalls); }
    static int Skeleton_getVerticesAndBounds(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds) { p("Skeleton_getVerticesAndBounds"); return JAVA ? JB.Skeleton_getVerticesAndBounds(handle, verts, indices, drawCalls, bounds) : UnidbgVM.get().skeletonGetVerticesAndBounds(handle, verts, indices, drawCalls, bounds); }
    static int Skeleton_getVerticesAndBoundsGlitched(int handle, FloatBuffer verts, ShortBuffer indices, ShortBuffer drawCalls, float[] bounds) { p("Skeleton_getVerticesAndBoundsGlitched"); return JAVA ? JB.Skeleton_getVerticesAndBoundsGlitched(handle, verts, indices, drawCalls, bounds) : UnidbgVM.get().skeletonGetVerticesAndBounds(handle, verts, indices, drawCalls, bounds); }

    // ---- AnimationStateData : routable Java (Opt.3) ----
    static int AnimationStateData_create(int dataHandle, float defaultMix) { p("AnimationStateData_create"); return JAVA ? JB.AnimationStateData_create(dataHandle, defaultMix) : UnidbgVM.get().animStateDataCreate(dataHandle, defaultMix); }
    static void AnimationStateData_dispose(int handle) { p("AnimationStateData_dispose"); if (JAVA) JB.AnimationStateData_dispose(handle); else UnidbgVM.get().animStateDataDispose(handle); }
    static void AnimationStateData_setMix(int handle, int fromAnim, int toAnim, float duration) { p("AnimationStateData_setMix"); if (JAVA) JB.AnimationStateData_setMix(handle, fromAnim, toAnim, duration); else UnidbgVM.get().animStateDataSetMix(handle, fromAnim, toAnim, duration); }

    // ---- AnimationState : routable Java (Opt.3) ----
    static int AnimationState_create(int asdHandle) { p("AnimationState_create"); return JAVA ? JB.AnimationState_create(asdHandle) : UnidbgVM.get().animStateCreate(asdHandle); }
    static void AnimationState_dispose(int handle) { p("AnimationState_dispose"); if (JAVA) JB.AnimationState_dispose(handle); else UnidbgVM.get().animStateDispose(handle); }
    static void AnimationState_update(int handle, float dt) { p("AnimationState_update"); if (JAVA) JB.AnimationState_update(handle, dt); else UnidbgVM.get().animStateUpdate(handle, dt); }
    static void AnimationState_apply(int handle, int skeletonHandle) { p("AnimationState_apply"); if (JAVA) JB.AnimationState_apply(handle, skeletonHandle); else UnidbgVM.get().animStateApply(handle, skeletonHandle); }
    static int AnimationState_setAnimation(int handle, int track, int animId, boolean loop) { p("AnimationState_setAnimation"); if (animId < DIAG_setAnimMin) DIAG_setAnimMin = animId; if (animId > DIAG_setAnimMax) DIAG_setAnimMax = animId; if (animId <= 0) DIAG_setAnimNeg++; return JAVA ? JB.AnimationState_setAnimation(handle, track, animId, loop) : UnidbgVM.get().animStateSetAnimation(handle, track, animId, loop); }
    static int AnimationState_addAnimation(int handle, int track, int animId, boolean loop, float delay) { p("AnimationState_addAnimation"); return JAVA ? JB.AnimationState_addAnimation(handle, track, animId, loop, delay) : UnidbgVM.get().animStateAddAnimation(handle, track, animId, loop, delay); }
    static void AnimationState_clearTracks(int handle) { p("AnimationState_clearTracks"); if (JAVA) JB.AnimationState_clearTracks(handle); else UnidbgVM.get().animStateClearTracks(handle); }
    static int AnimationState_getCurrentAnimationID(int handle, int track) { p("AnimationState_getCurrentAnimationID"); int id = JAVA ? JB.AnimationState_getCurrentAnimationID(handle, track) : UnidbgVM.get().animStateGetCurrentAnimationID(handle, track); if (id >= 0 && id < DIAG_curIdHist.length) DIAG_curIdHist[id]++; return id; }
    static float AnimationState_getCurrentAnimationTime(int handle, int track) { p("AnimationState_getCurrentAnimationTime"); float t = JAVA ? JB.AnimationState_getCurrentAnimationTime(handle, track) : UnidbgVM.get().animStateGetCurrentAnimationTime(handle, track); if (t > DIAG_maxAnimTime) DIAG_maxAnimTime = t; return t; }
    static boolean AnimationState_nextEvent(int handle, int[] out) { p("AnimationState_nextEvent"); return JAVA ? JB.AnimationState_nextEvent(handle, out) : UnidbgVM.get().animStateNextEvent(handle, out); }
}
