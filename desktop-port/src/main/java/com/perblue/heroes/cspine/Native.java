package com.perblue.heroes.cspine;

import dhbackend.jnispine.HostSpine;
import dhbackend.spine.JavaSpineBackend;
import dhbackend.unidbg.UnidbgVM;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cspine.Native} : signatures IDENTIQUES au jeu. Trois backends (flag
 * {@code -Ddh.spinebackend}) :
 * <ul>
 *   <li><b>unidbg</b> (défaut) — le VRAI binaire natif ARM d'origine ({@code libspine-native.so}) émulé
 *       ({@link UnidbgVM}). Fidélité maximale (bits exacts), lourd.</li>
 *   <li><b>jni</b> — le VRAI runtime spine-c officiel 3.6 (colle {@code cspine_jni.c} d'origine) compilé HÔTE
 *       x86-64 ({@link HostSpine}), appelé en JNI RÉEL. « Opt.2 sans unidbg » : même code natif, pas d'émulation
 *       ARM, fidélité par construction. À certifier contre l'oracle unidbg.</li>
 *   <li><b>java</b> — runtime spine-libgdx Java ({@link JavaSpineBackend}) : divergent, patché à la main →
 *       DÉCONSEILLÉ (dérive §4). Conservé pour référence.</li>
 * </ul>
 * DEV : profilage ({@code -Ddh.cspineprofile}). Aucun effet en prod.
 */
public class Native {
    private static final String BK = System.getProperty("dh.spinebackend", "");
    private static final boolean JNI = "jni".equalsIgnoreCase(BK);
    private static final boolean JAVA = "java".equalsIgnoreCase(BK);
    private static final JavaSpineBackend JB = JavaSpineBackend.INSTANCE;
    static { if (JNI) HostSpine.ensureLoaded(); }

    // ---- Profilage DEV (surface cspine) ----
    private static final boolean PROF = System.getProperty("dh.cspineprofile") != null
            && !"0".equals(System.getProperty("dh.cspineprofile"));
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> HITS
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static void p(String m) {
        if (PROF) HITS.computeIfAbsent(m, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }
    static volatile float DIAG_maxAnimTime = 0;
    static volatile int DIAG_setAnimMin = Integer.MAX_VALUE, DIAG_setAnimMax = Integer.MIN_VALUE, DIAG_setAnimNeg = 0;
    static final long[] DIAG_curIdHist = new long[32];
    public static void resetProfile() { HITS.clear(); DIAG_maxAnimTime = 0; DIAG_setAnimMin = Integer.MAX_VALUE; DIAG_setAnimMax = Integer.MIN_VALUE; DIAG_setAnimNeg = 0; java.util.Arrays.fill(DIAG_curIdHist, 0); }
    public static String reportProfile() {
        StringBuilder hist = new StringBuilder();
        for (int i = 0; i < DIAG_curIdHist.length; i++) if (DIAG_curIdHist[i] > 0) hist.append(" id").append(i).append('=').append(DIAG_curIdHist[i]);
        StringBuilder b = new StringBuilder("[cspine-profile] backend=" + (JNI ? "jni" : JAVA ? "java" : "unidbg") + " "
                + HITS.size() + " méthodes ; maxAnimTime=" + DIAG_maxAnimTime + "s setAnimId=["
                + DIAG_setAnimMin + ".." + DIAG_setAnimMax + "] neg=" + DIAG_setAnimNeg
                + "\n[cspine-profile] curIdHist:" + hist + "\n");
        if (JAVA) b.append(JavaSpineBackend.reportCurNames());
        return b.toString();
    }

    public static void ensureLoaded() { if (JNI) HostSpine.ensureLoaded(); else UnidbgVM.get(); }

    static void Spine_init() { if (JNI) HostSpine.Spine_init(); else UnidbgVM.get(); }
    public static String getLastSpineError() { return JNI ? HostSpine.getLastSpineError() : UnidbgVM.get().getLastSpineError(); }
    public static void printUsedHandleReport() { if (!JNI) UnidbgVM.get().printUsedHandleReport(); }

    static int Atlas_create(byte[] a, boolean pma) { p("Atlas_create"); return JNI ? HostSpine.Atlas_create(a, pma) : UnidbgVM.get().atlasCreate(a, pma); }
    static void Atlas_dispose(int h) { p("Atlas_dispose"); if (JNI) HostSpine.Atlas_dispose(h); else UnidbgVM.get().atlasDispose(h); }
    static boolean Atlas_getParams(int h, int pg, int[] out) { p("Atlas_getParams"); return JNI ? HostSpine.Atlas_getParams(h, pg, out) : UnidbgVM.get().atlasGetParams(h, pg, out); }
    static String Atlas_getTexture(int h, int pg) { p("Atlas_getTexture"); return JNI ? HostSpine.Atlas_getTexture(h, pg) : UnidbgVM.get().atlasGetTexture(h, pg); }

    static int SkeletonData_create(byte[] b, int a) { p("SkeletonData_create"); return JNI ? HostSpine.SkeletonData_create(b, a) : JAVA ? JB.SkeletonData_create(b, a) : UnidbgVM.get().skeletonDataCreate(b, a); }
    static void SkeletonData_dispose(int h) { p("SkeletonData_dispose"); if (JNI) HostSpine.SkeletonData_dispose(h); else if (JAVA) JB.SkeletonData_dispose(h); else UnidbgVM.get().skeletonDataDispose(h); }
    static float[] SkeletonData_getAnimationDurations(int h) { p("SkeletonData_getAnimationDurations"); return JNI ? HostSpine.SkeletonData_getAnimationDurations(h) : JAVA ? JB.SkeletonData_getAnimationDurations(h) : UnidbgVM.get().skeletonDataGetAnimationDurations(h); }
    static int SkeletonData_getAnimationID(int h, String n) { p("SkeletonData_getAnimationID"); return JNI ? HostSpine.SkeletonData_getAnimationID(h, n) : JAVA ? JB.SkeletonData_getAnimationID(h, n) : UnidbgVM.get().skeletonDataGetAnimationID(h, n); }
    static String[] SkeletonData_getAnimationNames(int h) { p("SkeletonData_getAnimationNames"); return JNI ? HostSpine.SkeletonData_getAnimationNames(h) : JAVA ? JB.SkeletonData_getAnimationNames(h) : UnidbgVM.get().skeletonDataGetAnimationNames(h); }
    static int SkeletonData_getBoneID(int h, String n) { p("SkeletonData_getBoneID"); return JNI ? HostSpine.SkeletonData_getBoneID(h, n) : JAVA ? JB.SkeletonData_getBoneID(h, n) : UnidbgVM.get().skeletonDataGetBoneID(h, n); }
    static String[] SkeletonData_getBoneNames(int h) { p("SkeletonData_getBoneNames"); return JNI ? HostSpine.SkeletonData_getBoneNames(h) : JAVA ? JB.SkeletonData_getBoneNames(h) : UnidbgVM.get().skeletonDataGetBoneNames(h); }
    static String[] SkeletonData_getSkinNames(int h) { p("SkeletonData_getSkinNames"); return JNI ? HostSpine.SkeletonData_getSkinNames(h) : JAVA ? JB.SkeletonData_getSkinNames(h) : UnidbgVM.get().skeletonDataGetSkinNames(h); }
    static String[] SkeletonData_getSlotNames(int h) { p("SkeletonData_getSlotNames"); return JNI ? HostSpine.SkeletonData_getSlotNames(h) : JAVA ? JB.SkeletonData_getSlotNames(h) : UnidbgVM.get().skeletonDataGetSlotNames(h); }
    static boolean SkeletonData_getStats(int h, NativeSkeletonDataStats out) { p("SkeletonData_getStats"); return false; }
    static VertexWeightReportEntry[] SkeletonData_getVertexWeightReport(int h, int s) { p("SkeletonData_getVertexWeightReport"); return null; }

    static int Skeleton_create(int d) { p("Skeleton_create"); return JNI ? HostSpine.Skeleton_create(d) : JAVA ? JB.Skeleton_create(d) : UnidbgVM.get().skeletonCreate(d); }
    static void Skeleton_dispose(int h) { p("Skeleton_dispose"); if (JNI) HostSpine.Skeleton_dispose(h); else if (JAVA) JB.Skeleton_dispose(h); else UnidbgVM.get().skeletonDispose(h); }
    static void Skeleton_update(int h, float dt) { p("Skeleton_update"); if (JNI) HostSpine.Skeleton_update(h, dt); else if (JAVA) JB.Skeleton_update(h, dt); else UnidbgVM.get().skeletonUpdate(h, dt); }
    static void Skeleton_updateWorldTransform(int h) { p("Skeleton_updateWorldTransform"); if (JNI) HostSpine.Skeleton_updateWorldTransform(h); else if (JAVA) JB.Skeleton_updateWorldTransform(h); else UnidbgVM.get().skeletonUpdateWorldTransform(h); }
    static void Skeleton_setToSetupPose(int h) { p("Skeleton_setToSetupPose"); if (JNI) HostSpine.Skeleton_setToSetupPose(h); else if (JAVA) JB.Skeleton_setToSetupPose(h); else UnidbgVM.get().skeletonSetToSetupPose(h); }
    static void Skeleton_setColor(int h, float r, float g, float b, float a) { p("Skeleton_setColor"); if (JNI) HostSpine.Skeleton_setColor(h, r, g, b, a); else if (JAVA) JB.Skeleton_setColor(h, r, g, b, a); else UnidbgVM.get().skeletonSetColor(h, r, g, b, a); }
    static void Skeleton_setTintBlack(int h, float r, float g, float b) { p("Skeleton_setTintBlack"); if (JNI) HostSpine.Skeleton_setTintBlack(h, r, g, b); else if (JAVA) JB.Skeleton_setTintBlack(h, r, g, b); else UnidbgVM.get().skeletonSetTintBlack(h, r, g, b); }
    static boolean Skeleton_setSkin(int h, String n) { p("Skeleton_setSkin"); return JNI ? HostSpine.Skeleton_setSkin(h, n) : JAVA ? JB.Skeleton_setSkin(h, n) : UnidbgVM.get().skeletonSetSkin(h, n); }
    static boolean Skeleton_setSlotEyeState(int h, int s, int st) { p("Skeleton_setSlotEyeState"); return JNI ? HostSpine.Skeleton_setSlotEyeState(h, s, st) : JAVA ? JB.Skeleton_setSlotEyeState(h, s, st) : UnidbgVM.get().skeletonSetSlotEyeState(h, s, st); }
    static void Skeleton_getBoneTransform(int h, int bid, float[] out, int off) { p("Skeleton_getBoneTransform"); if (JNI) HostSpine.Skeleton_getBoneTransform(h, bid, out, off); else if (JAVA) JB.Skeleton_getBoneTransform(h, bid, out, off); else UnidbgVM.get().skeletonGetBoneTransform(h, bid, out, off); if (PROF && DIAG_btLogged < 6) { DIAG_btLogged++; System.out.printf("[bt] skel=%d bone=%d -> x=%.3f y=%.3f rot=%.3f sx=%.3f sy=%.3f%n", h, bid, out[off], out[off+1], out[off+2], out[off+3], out[off+4]); } }
    static volatile int DIAG_btLogged = 0;
    static void Skeleton_getBoneTransforms(int h, int[] ids, int idOff, float[] out, int outOff) { p("Skeleton_getBoneTransforms"); if (JNI) HostSpine.Skeleton_getBoneTransforms(h, ids, idOff, out, outOff); else if (JAVA) JB.Skeleton_getBoneTransforms(h, ids, idOff, out, outOff); else UnidbgVM.get().skeletonGetBoneTransforms(h, ids, idOff, out, outOff); }
    static void Skeleton_setBoneTransform(int h, int bid, float x, float y, float rot, float sx, float sy, float shx, float shy) { p("Skeleton_setBoneTransform"); if (JNI) HostSpine.Skeleton_setBoneTransform(h, bid, x, y, rot, sx, sy, shx, shy); else if (JAVA) JB.Skeleton_setBoneTransform(h, bid, x, y, rot, sx, sy, shx, shy); else UnidbgVM.get().skeletonSetBoneTransform(h, bid, x, y, rot, sx, sy, shx, shy); }
    static void Skeleton_getPosedBounds(int h, float[] out) { p("Skeleton_getPosedBounds"); if (JNI) HostSpine.Skeleton_getPosedBounds(h, out); else if (JAVA) JB.Skeleton_getPosedBounds(h, out); else UnidbgVM.get().skeletonGetPosedBounds(h, out); }
    static int Skeleton_getVertices(int h, FloatBuffer v, ShortBuffer i, ShortBuffer d) { p("Skeleton_getVertices"); return JNI ? HostSpine.Skeleton_getVertices(h, v, i, d) : JAVA ? JB.Skeleton_getVertices(h, v, i, d) : UnidbgVM.get().skeletonGetVertices(h, v, i, d); }
    static int Skeleton_getVerticesAndBounds(int h, FloatBuffer v, ShortBuffer i, ShortBuffer d, float[] bd) { p("Skeleton_getVerticesAndBounds"); return JNI ? HostSpine.Skeleton_getVerticesAndBounds(h, v, i, d, bd) : JAVA ? JB.Skeleton_getVerticesAndBounds(h, v, i, d, bd) : UnidbgVM.get().skeletonGetVerticesAndBounds(h, v, i, d, bd); }
    static int Skeleton_getVerticesAndBoundsGlitched(int h, FloatBuffer v, ShortBuffer i, ShortBuffer d, float[] bd) { p("Skeleton_getVerticesAndBoundsGlitched"); return JNI ? HostSpine.Skeleton_getVerticesAndBoundsGlitched(h, v, i, d, bd) : JAVA ? JB.Skeleton_getVerticesAndBoundsGlitched(h, v, i, d, bd) : UnidbgVM.get().skeletonGetVerticesAndBounds(h, v, i, d, bd); }

    static int AnimationStateData_create(int d, float mix) { p("AnimationStateData_create"); return JNI ? HostSpine.AnimationStateData_create(d, mix) : JAVA ? JB.AnimationStateData_create(d, mix) : UnidbgVM.get().animStateDataCreate(d, mix); }
    static void AnimationStateData_dispose(int h) { p("AnimationStateData_dispose"); if (JNI) HostSpine.AnimationStateData_dispose(h); else if (JAVA) JB.AnimationStateData_dispose(h); else UnidbgVM.get().animStateDataDispose(h); }
    static void AnimationStateData_setMix(int h, int f, int t, float dur) { p("AnimationStateData_setMix"); if (JNI) HostSpine.AnimationStateData_setMix(h, f, t, dur); else if (JAVA) JB.AnimationStateData_setMix(h, f, t, dur); else UnidbgVM.get().animStateDataSetMix(h, f, t, dur); }

    static int AnimationState_create(int a) { p("AnimationState_create"); return JNI ? HostSpine.AnimationState_create(a) : JAVA ? JB.AnimationState_create(a) : UnidbgVM.get().animStateCreate(a); }
    static void AnimationState_dispose(int h) { p("AnimationState_dispose"); if (JNI) HostSpine.AnimationState_dispose(h); else if (JAVA) JB.AnimationState_dispose(h); else UnidbgVM.get().animStateDispose(h); }
    static void AnimationState_update(int h, float dt) { p("AnimationState_update"); if (JNI) HostSpine.AnimationState_update(h, dt); else if (JAVA) JB.AnimationState_update(h, dt); else UnidbgVM.get().animStateUpdate(h, dt); }
    static void AnimationState_apply(int h, int sk) { p("AnimationState_apply"); if (JNI) HostSpine.AnimationState_apply(h, sk); else if (JAVA) JB.AnimationState_apply(h, sk); else UnidbgVM.get().animStateApply(h, sk); }
    static int AnimationState_setAnimation(int h, int tr, int id, boolean loop) { p("AnimationState_setAnimation"); if (id < DIAG_setAnimMin) DIAG_setAnimMin = id; if (id > DIAG_setAnimMax) DIAG_setAnimMax = id; if (id <= 0) DIAG_setAnimNeg++; return JNI ? HostSpine.AnimationState_setAnimation(h, tr, id, loop) : JAVA ? JB.AnimationState_setAnimation(h, tr, id, loop) : UnidbgVM.get().animStateSetAnimation(h, tr, id, loop); }
    static int AnimationState_addAnimation(int h, int tr, int id, boolean loop, float delay) { p("AnimationState_addAnimation"); return JNI ? HostSpine.AnimationState_addAnimation(h, tr, id, loop, delay) : JAVA ? JB.AnimationState_addAnimation(h, tr, id, loop, delay) : UnidbgVM.get().animStateAddAnimation(h, tr, id, loop, delay); }
    static void AnimationState_clearTracks(int h) { p("AnimationState_clearTracks"); if (JNI) HostSpine.AnimationState_clearTracks(h); else if (JAVA) JB.AnimationState_clearTracks(h); else UnidbgVM.get().animStateClearTracks(h); }
    static int AnimationState_getCurrentAnimationID(int h, int tr) { p("AnimationState_getCurrentAnimationID"); int id = JNI ? HostSpine.AnimationState_getCurrentAnimationID(h, tr) : JAVA ? JB.AnimationState_getCurrentAnimationID(h, tr) : UnidbgVM.get().animStateGetCurrentAnimationID(h, tr); if (id >= 0 && id < DIAG_curIdHist.length) DIAG_curIdHist[id]++; return id; }
    static float AnimationState_getCurrentAnimationTime(int h, int tr) { p("AnimationState_getCurrentAnimationTime"); float t = JNI ? HostSpine.AnimationState_getCurrentAnimationTime(h, tr) : JAVA ? JB.AnimationState_getCurrentAnimationTime(h, tr) : UnidbgVM.get().animStateGetCurrentAnimationTime(h, tr); if (t > DIAG_maxAnimTime) DIAG_maxAnimTime = t; return t; }
    static boolean AnimationState_nextEvent(int h, int[] out) { p("AnimationState_nextEvent"); return JNI ? HostSpine.AnimationState_nextEvent(h, out) : JAVA ? JB.AnimationState_nextEvent(h, out) : UnidbgVM.get().animStateNextEvent(h, out); }
}
