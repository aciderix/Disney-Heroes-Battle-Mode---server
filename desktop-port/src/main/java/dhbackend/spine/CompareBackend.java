package dhbackend.spine;

import dhbackend.jnispine.HostSpine;
import dhbackend.unidbg.UnidbgVM;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Harnais DIFFÉRENTIEL de certification (#28) — mode {@code -Ddh.spinebackend=compare}.
 *
 * <p>Pour CHAQUE appel de {@code cspine.Native}, exécute les DEUX backends :
 * <ul>
 *   <li><b>unidbg</b> = ORACLE (le VRAI binaire ARM de PerBlue = le mobile, bit-exact) → dont le résultat est
 *       RENDU au jeu (le jeu boote/tourne normalement, indépendamment des bugs du JNI) ;</li>
 *   <li><b>JNI natif</b> ({@link HostSpine}, spine-c recompilé x86) = CANDIDAT, en parallèle avec sa PROPRE table
 *       de handles → ses sorties sont comparées à l'oracle mais JAMAIS rendues au jeu.</li>
 * </ul>
 * Les handles diffèrent entre backends → table de correspondance {@code u2j} (handle unidbg → handle JNI),
 * remplie à chaque {@code *_create}, traduite à chaque usage. À la fin : rapport auto (0 diff = certifié ;
 * sinon liste exacte des divergences, avec distance ULP pour distinguer arrondi FP ARM↔x86 d'un écart de logique).
 */
public final class CompareBackend {
    public static final CompareBackend INSTANCE = new CompareBackend();
    private CompareBackend() {}

    private final ConcurrentHashMap<Integer, Integer> u2j = new ConcurrentHashMap<>();
    private int tr(int hu) { Integer hj = u2j.get(hu); return hj == null ? 0 : hj; }

    /** Comparaison ACTIVE seulement dans la fenêtre voulue (ex. un combat) — hors fenêtre le boot tourne sur
     *  unidbg seul (rapide) ; les create/dispose restent doublés pour garder la table de handles cohérente. */
    public volatile boolean active = false;

    // PerBlue réordonne les os en interne (getBoneID unidbg ≠ index de getBoneNames). Pour comparer le MÊME os
    // logique, on traduit l'index d'os unidbg → index d'os JNI PAR NOM. Rempli via les appels getBoneID du jeu.
    private final ConcurrentHashMap<Integer, Integer> skel2data = new ConcurrentHashMap<>();         // skelHandle(u) → dataHandle(u)
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Integer>> dataUBone2JBone = new ConcurrentHashMap<>(); // dataHandle(u) → (uBoneId → jBoneId)
    private int jBoneOf(int skelHu, int uBoneId) {
        Integer dataH = skel2data.get(skelHu); if (dataH == null) return -1;
        ConcurrentHashMap<Integer, Integer> m = dataUBone2JBone.get(dataH); if (m == null) return -1;
        Integer j = m.get(uBoneId); return j == null ? -1 : j;
    }

    // ---- chrono par backend (perf) : temps passé côté unidbg(émulé ARM) vs JNI(natif x86) sur le MÊME mix
    //      d'appels du hot-path squelettique (update/apply/transforms/events). Actif seulement en fenêtre combat. ----
    public final LongAdder uNanos = new LongAdder(), jNanos = new LongAdder();

    // ---- rapport de diffs ----
    static final class Stat { final LongAdder calls = new LongAdder(), diffs = new LongAdder(); volatile long maxUlp = 0; volatile double maxAbs = 0; volatile double maxAbsMat = 0; volatile double maxAbsPos = 0; volatile String firstEx; }
    private final ConcurrentHashMap<String, Stat> stats = new ConcurrentHashMap<>();
    private Stat st(String m) { return stats.computeIfAbsent(m, k -> new Stat()); }
    public void ensureLoaded() { UnidbgVM.get(); HostSpine.ensureLoaded(); }

    public String report() {
        StringBuilder b = new StringBuilder("[compare] certification unidbg(oracle) vs JNI-natif :\n");
        stats.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().diffs.sum(), x.getValue().diffs.sum()))
            .forEach(e -> { Stat s = e.getValue();
                String split = (s.maxAbsMat > 0 || s.maxAbsPos > 0)
                    ? String.format(" [mat=%.3g pos=%.3g]", s.maxAbsMat, s.maxAbsPos) : "";
                b.append(String.format("  %-34s appels=%-7d DIFFS=%-7d maxAbs=%.3g%s maxUlp=%d%s%n",
                    e.getKey(), s.calls.sum(), s.diffs.sum(), s.maxAbs, split, s.maxUlp, s.firstEx == null ? "" : "  ex: " + s.firstEx)); });
        long tot = stats.values().stream().mapToLong(s -> s.diffs.sum()).sum();
        b.append("  ==> TOTAL DIFFS = ").append(tot).append(tot == 0 ? "  ✅ CERTIFIÉ IDENTIQUE" : "  ❌ divergences").append('\n');
        double uMs = uNanos.sum() / 1e6, jMs = jNanos.sum() / 1e6;
        b.append(String.format("  [perf] hot-path squelettique (update/apply/transforms/events, HORS getVertices) sur le MÊME mix :%n"
                + "         unidbg(ARM émulé)=%.0f ms   JNI(natif x86)=%.1f ms   → natif ≈ %.0f× plus rapide%n",
                uMs, jMs, jMs > 0 ? uMs / jMs : 0));
        return b.toString();
    }

    // ---- comparateurs ----
    private static long ulp(float a, float b) { return Math.abs((long) Float.floatToRawIntBits(a) - (long) Float.floatToRawIntBits(b)); }
    private void ci(String m, int u, int j) { Stat s = st(m); s.calls.increment(); if (u != j) { s.diffs.increment(); if (s.firstEx == null) s.firstEx = "u=" + u + " j=" + j; } }
    private void cb(String m, boolean u, boolean j) { Stat s = st(m); s.calls.increment(); if (u != j) { s.diffs.increment(); if (s.firstEx == null) s.firstEx = "u=" + u + " j=" + j; } }
    private void cf(String m, float u, float j) { Stat s = st(m); s.calls.increment(); if (Float.floatToRawIntBits(u) != Float.floatToRawIntBits(j)) { s.diffs.increment(); long ul = ulp(u, j); if (ul > s.maxUlp) s.maxUlp = ul; double ad = Math.abs((double) u - j); if (ad > s.maxAbs) s.maxAbs = ad; if (s.firstEx == null) s.firstEx = "u=" + u + " j=" + j; } }
    private void cfa(String m, float[] u, float[] j, int n) { Stat s = st(m); s.calls.increment(); if (u == null || j == null) { if (u != j) s.diffs.increment(); return; } boolean d = false; long mu = 0; double ma = 0; for (int i = 0; i < n; i++) { if (Float.floatToRawIntBits(u[i]) != Float.floatToRawIntBits(j[i])) { d = true; mu = Math.max(mu, ulp(u[i], j[i])); ma = Math.max(ma, Math.abs((double) u[i] - j[i])); } } if (d) { s.diffs.increment(); if (mu > s.maxUlp) s.maxUlp = mu; if (ma > s.maxAbs) s.maxAbs = ma; if (s.firstEx == null) s.firstEx = "u[0]=" + u[0] + " j[0]=" + j[0] + " maxAbs=" + ma; } }
    private void csa(String m, String[] u, String[] j) { Stat s = st(m); s.calls.increment(); if (!java.util.Arrays.equals(u, j)) { s.diffs.increment(); if (s.firstEx == null) s.firstEx = java.util.Arrays.toString(u) + " vs " + java.util.Arrays.toString(j); } }

    // ================= lifecycle (create/dispose TOUJOURS doublés → table de handles cohérente)
    public void Spine_init() { UnidbgVM.get(); HostSpine.Spine_init(); }
    public String getLastSpineError() { return UnidbgVM.get().getLastSpineError(); }

    // ================= Atlas
    public int Atlas_create(byte[] a, boolean pma) { int u = UnidbgVM.get().atlasCreate(a, pma); int j = HostSpine.Atlas_create(a, pma); u2j.put(u, j); return u; }
    public void Atlas_dispose(int h) { UnidbgVM.get().atlasDispose(h); HostSpine.Atlas_dispose(tr(h)); u2j.remove(h); }
    public boolean Atlas_getParams(int h, int pg, int[] out) { boolean u = UnidbgVM.get().atlasGetParams(h, pg, out); if (active) { int[] ju = new int[out.length]; cb("Atlas_getParams", u, HostSpine.Atlas_getParams(tr(h), pg, ju)); } return u; }
    public String Atlas_getTexture(int h, int pg) { String u = UnidbgVM.get().atlasGetTexture(h, pg); if (active) { HostSpine.Atlas_getTexture(tr(h), pg); st("Atlas_getTexture").calls.increment(); } return u; }

    // ================= SkeletonData
    public int SkeletonData_create(byte[] b, int atlas) { int u = UnidbgVM.get().skeletonDataCreate(b, atlas); int j = HostSpine.SkeletonData_create(b, tr(atlas)); u2j.put(u, j); return u; }
    public void SkeletonData_dispose(int h) { UnidbgVM.get().skeletonDataDispose(h); HostSpine.SkeletonData_dispose(tr(h)); u2j.remove(h); }
    public float[] SkeletonData_getAnimationDurations(int h) { float[] u = UnidbgVM.get().skeletonDataGetAnimationDurations(h); if (active) cfa("SkeletonData_getAnimationDurations", u, HostSpine.SkeletonData_getAnimationDurations(tr(h)), u == null ? 0 : u.length); return u; }
    public int SkeletonData_getAnimationID(int h, String n) { int u = UnidbgVM.get().skeletonDataGetAnimationID(h, n); if (active) ci("SkeletonData_getAnimationID", u, HostSpine.SkeletonData_getAnimationID(tr(h), n)); return u; }
    public String[] SkeletonData_getAnimationNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetAnimationNames(h); if (active) csa("SkeletonData_getAnimationNames", u, HostSpine.SkeletonData_getAnimationNames(tr(h))); return u; }
    public int SkeletonData_getBoneID(int h, String n) {
        int u = UnidbgVM.get().skeletonDataGetBoneID(h, n);
        if (active) {
            int j = HostSpine.SkeletonData_getBoneID(tr(h), n);
            if (u >= 0 && j >= 0) dataUBone2JBone.computeIfAbsent(h, k -> new ConcurrentHashMap<>()).put(u, j);  // traduction par nom
            Stat s = st("SkeletonData_getBoneID"); s.calls.increment();
            if (u != j) { s.diffs.increment();
                if (s.firstEx == null) {
                    String[] un = UnidbgVM.get().skeletonDataGetBoneNames(h), jn = HostSpine.SkeletonData_getBoneNames(tr(h));
                    s.firstEx = "h=" + h + "→" + tr(h) + " name='" + n + "' u=" + u + " j=" + j
                        + " | uLen=" + (un == null ? -1 : un.length) + " jLen=" + (jn == null ? -1 : jn.length)
                        + " namesEqual=" + java.util.Arrays.equals(un, jn)
                        + (un != null && u >= 0 && u < un.length ? " uName[" + u + "]=" + un[u] : "")
                        + (jn != null && j >= 0 && j < jn.length ? " jName[" + j + "]=" + jn[j] : "");
                }
            }
        }
        return u;
    }
    public String[] SkeletonData_getBoneNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetBoneNames(h); if (active) csa("SkeletonData_getBoneNames", u, HostSpine.SkeletonData_getBoneNames(tr(h))); return u; }
    public String[] SkeletonData_getSkinNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetSkinNames(h); if (active) csa("SkeletonData_getSkinNames", u, HostSpine.SkeletonData_getSkinNames(tr(h))); return u; }
    public String[] SkeletonData_getSlotNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetSlotNames(h); if (active) csa("SkeletonData_getSlotNames", u, HostSpine.SkeletonData_getSlotNames(tr(h))); return u; }

    // ================= Skeleton
    public int Skeleton_create(int d) { int u = UnidbgVM.get().skeletonCreate(d); int j = HostSpine.Skeleton_create(tr(d)); u2j.put(u, j); skel2data.put(u, d); return u; }
    public void Skeleton_dispose(int h) { UnidbgVM.get().skeletonDispose(h); HostSpine.Skeleton_dispose(tr(h)); u2j.remove(h); }
    public void Skeleton_update(int h, float dt) { long t0=System.nanoTime(); UnidbgVM.get().skeletonUpdate(h, dt); if (active) { long t1=System.nanoTime(); HostSpine.Skeleton_update(tr(h), dt); long t2=System.nanoTime(); uNanos.add(t1-t0); jNanos.add(t2-t1); } }
    public void Skeleton_updateWorldTransform(int h) { long t0=System.nanoTime(); UnidbgVM.get().skeletonUpdateWorldTransform(h); if (active) { long t1=System.nanoTime(); HostSpine.Skeleton_updateWorldTransform(tr(h)); long t2=System.nanoTime(); uNanos.add(t1-t0); jNanos.add(t2-t1); } }
    public void Skeleton_setToSetupPose(int h) { UnidbgVM.get().skeletonSetToSetupPose(h); if (active) HostSpine.Skeleton_setToSetupPose(tr(h)); }
    public void Skeleton_setColor(int h, float r, float g, float b, float a) { UnidbgVM.get().skeletonSetColor(h, r, g, b, a); if (active) HostSpine.Skeleton_setColor(tr(h), r, g, b, a); }
    public void Skeleton_setTintBlack(int h, float r, float g, float b) { UnidbgVM.get().skeletonSetTintBlack(h, r, g, b); if (active) HostSpine.Skeleton_setTintBlack(tr(h), r, g, b); }
    public boolean Skeleton_setSkin(int h, String n) { boolean u = UnidbgVM.get().skeletonSetSkin(h, n); if (active) cb("Skeleton_setSkin", u, HostSpine.Skeleton_setSkin(tr(h), n)); return u; }
    public boolean Skeleton_setSlotEyeState(int h, int s, int st) { boolean u = UnidbgVM.get().skeletonSetSlotEyeState(h, s, st); if (active) cb("Skeleton_setSlotEyeState", u, HostSpine.Skeleton_setSlotEyeState(tr(h), s, st)); return u; }
    public void Skeleton_getBoneTransform(int h, int bid, float[] out, int off) {
        long t0=System.nanoTime(); UnidbgVM.get().skeletonGetBoneTransform(h, bid, out, off); long tU=System.nanoTime()-t0;
        if (!active) return;
        int jb = jBoneOf(h, bid);   // traduit l'index d'os unidbg → index JNI par NOM (PerBlue réordonne les os)
        Stat s = st("Skeleton_getBoneTransform"); s.calls.increment();
        if (jb < 0) { st("Skeleton_getBoneTransform.untranslated").calls.increment(); return; }  // os non traduisible (getBoneID pas appelé) → skip
        float[] ju = new float[off + 6]; long t2=System.nanoTime(); HostSpine.Skeleton_getBoneTransform(tr(h), jb, ju, off); uNanos.add(tU); jNanos.add(System.nanoTime()-t2);
        boolean d = false; long mu = 0; double ma = 0, mMat = 0, mPos = 0;
        // layout = matrice affine [a,b,c,d, worldX,worldY] : 0-3 = rotation/échelle (∈ ±1), 4-5 = translation (∈ ±centaines)
        for (int i = 0; i < 6; i++) { float a = out[off + i], c = ju[off + i]; if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(c)) { d = true; mu = Math.max(mu, ulp(a, c)); double ad = Math.abs((double) a - c); ma = Math.max(ma, ad); if (i < 4) mMat = Math.max(mMat, ad); else mPos = Math.max(mPos, ad); } }
        if (d) { s.diffs.increment(); if (mu > s.maxUlp) s.maxUlp = mu; if (ma > s.maxAbs) s.maxAbs = ma; if (mMat > s.maxAbsMat) s.maxAbsMat = mMat; if (mPos > s.maxAbsPos) s.maxAbsPos = mPos; if (s.firstEx == null) s.firstEx = "uBone=" + bid + " jBone=" + jb + " maxAbs=" + ma; }
    }
    public void Skeleton_getBoneTransforms(int h, int[] ids, int count, float[] out, int outOff) {
        long t0=System.nanoTime(); UnidbgVM.get().skeletonGetBoneTransforms(h, ids, count, out, outOff); long tU=System.nanoTime()-t0;
        if (!active) return;
        Stat s = st("Skeleton_getBoneTransforms"); s.calls.increment();
        int[] jids = new int[ids.length]; boolean ok = true;
        for (int i = 0; i < count; i++) { int jb = jBoneOf(h, ids[i]); if (jb < 0) { ok = false; break; } jids[i] = jb; }
        if (!ok) { st("Skeleton_getBoneTransforms.untranslated").calls.increment(); return; }  // os non traduisible → skip
        float[] ju = new float[out.length]; long t2=System.nanoTime(); HostSpine.Skeleton_getBoneTransforms(tr(h), jids, count, ju, outOff); uNanos.add(tU); jNanos.add(System.nanoTime()-t2);
        boolean d = false; long mu = 0; double ma = 0, mMat = 0, mPos = 0;
        for (int i = 0; i < count * 6; i++) { float a = out[outOff + i], c = ju[outOff + i]; if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(c)) { d = true; mu = Math.max(mu, ulp(a, c)); double ad = Math.abs((double) a - c); ma = Math.max(ma, ad); if (i % 6 < 4) mMat = Math.max(mMat, ad); else mPos = Math.max(mPos, ad); } }
        if (d) { s.diffs.increment(); if (mu > s.maxUlp) s.maxUlp = mu; if (ma > s.maxAbs) s.maxAbs = ma; if (mMat > s.maxAbsMat) s.maxAbsMat = mMat; if (mPos > s.maxAbsPos) s.maxAbsPos = mPos; if (s.firstEx == null) s.firstEx = "maxAbs=" + ma; }
    }
    public void Skeleton_setBoneTransform(int h, int bid, float x, float y, float rot, float sx, float sy, float shx, float shy) { UnidbgVM.get().skeletonSetBoneTransform(h, bid, x, y, rot, sx, sy, shx, shy); if (active) { int jb = jBoneOf(h, bid); if (jb >= 0) HostSpine.Skeleton_setBoneTransform(tr(h), jb, x, y, rot, sx, sy, shx, shy); } }
    public void Skeleton_getPosedBounds(int h, float[] out) { UnidbgVM.get().skeletonGetPosedBounds(h, out); if (active) { float[] ju = new float[out.length]; HostSpine.Skeleton_getPosedBounds(tr(h), ju); cfa("Skeleton_getPosedBounds", out, ju, out.length); } }
    public int Skeleton_getVertices(int h, FloatBuffer v, ShortBuffer i, ShortBuffer d) { int u = UnidbgVM.get().skeletonGetVertices(h, v, i, d); if (active) compareVerts("Skeleton_getVertices", h, v, i, d, u, null); return u; }
    public int Skeleton_getVerticesAndBounds(int h, FloatBuffer v, ShortBuffer i, ShortBuffer d, float[] b) { int u = UnidbgVM.get().skeletonGetVerticesAndBounds(h, v, i, d, b); if (active) compareVerts("Skeleton_getVerticesAndBounds", h, v, i, d, u, b); return u; }
    public int Skeleton_getVerticesAndBoundsGlitched(int h, FloatBuffer v, ShortBuffer i, ShortBuffer d, float[] b) { int u = UnidbgVM.get().skeletonGetVerticesAndBounds(h, v, i, d, b); if (active) compareVerts("Skeleton_getVerticesAndBoundsGlitched", h, v, i, d, u, b); return u; }

    // Compare le rendu : buffers JNI parallèles, mêmes capacités, sans perturber ceux du jeu (remplis par unidbg).
    private void compareVerts(String m, int h, FloatBuffer v, ShortBuffer i, ShortBuffer d, int nU, float[] bU) {
        Stat s = st(m); s.calls.increment();
        FloatBuffer vj = FloatBuffer.allocate(v.capacity()); ShortBuffer ij = ShortBuffer.allocate(i.capacity()); ShortBuffer dj = ShortBuffer.allocate(d.capacity());
        float[] bj = bU == null ? null : new float[bU.length];
        int nJ = bU == null ? HostSpine.Skeleton_getVertices(tr(h), vj, ij, dj) : HostSpine.Skeleton_getVerticesAndBounds(tr(h), vj, ij, dj, bj);
        boolean diff = nU != nJ;
        long mu = 0;
        int fv = Math.min(v.position(), vj.position());
        for (int k = 0; k < fv; k++) { float a = v.get(k), c = vj.get(k); if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(c)) { diff = true; mu = Math.max(mu, ulp(a, c)); } }
        if (diff) { s.diffs.increment(); if (mu > s.maxUlp) s.maxUlp = mu; if (s.firstEx == null) s.firstEx = "nU=" + nU + " nJ=" + nJ + " maxUlp=" + mu; }
    }

    // ================= AnimationStateData
    public int AnimationStateData_create(int d, float mix) { int u = UnidbgVM.get().animStateDataCreate(d, mix); int j = HostSpine.AnimationStateData_create(tr(d), mix); u2j.put(u, j); return u; }
    public void AnimationStateData_dispose(int h) { UnidbgVM.get().animStateDataDispose(h); HostSpine.AnimationStateData_dispose(tr(h)); u2j.remove(h); }
    public void AnimationStateData_setMix(int h, int f, int t, float dur) { UnidbgVM.get().animStateDataSetMix(h, f, t, dur); if (active) HostSpine.AnimationStateData_setMix(tr(h), f, t, dur); }

    // ================= AnimationState
    public int AnimationState_create(int a) { int u = UnidbgVM.get().animStateCreate(a); int j = HostSpine.AnimationState_create(tr(a)); u2j.put(u, j); return u; }
    public void AnimationState_dispose(int h) { UnidbgVM.get().animStateDispose(h); HostSpine.AnimationState_dispose(tr(h)); u2j.remove(h); }
    public void AnimationState_update(int h, float dt) { long t0=System.nanoTime(); UnidbgVM.get().animStateUpdate(h, dt); if (active) { long t1=System.nanoTime(); HostSpine.AnimationState_update(tr(h), dt); long t2=System.nanoTime(); uNanos.add(t1-t0); jNanos.add(t2-t1); } }
    public void AnimationState_apply(int h, int sk) { long t0=System.nanoTime(); UnidbgVM.get().animStateApply(h, sk); if (active) { long t1=System.nanoTime(); HostSpine.AnimationState_apply(tr(h), tr(sk)); long t2=System.nanoTime(); uNanos.add(t1-t0); jNanos.add(t2-t1); } }
    public int AnimationState_setAnimation(int h, int tr2, int id, boolean loop) { long t0=System.nanoTime(); int u = UnidbgVM.get().animStateSetAnimation(h, tr2, id, loop); if (active) { long t1=System.nanoTime(); ci("AnimationState_setAnimation", u, HostSpine.AnimationState_setAnimation(tr(h), tr2, id, loop)); long t2=System.nanoTime(); uNanos.add(t1-t0); jNanos.add(t2-t1); } return u; }
    public int AnimationState_addAnimation(int h, int tr2, int id, boolean loop, float delay) { int u = UnidbgVM.get().animStateAddAnimation(h, tr2, id, loop, delay); if (active) ci("AnimationState_addAnimation", u, HostSpine.AnimationState_addAnimation(tr(h), tr2, id, loop, delay)); return u; }
    public void AnimationState_clearTracks(int h) { UnidbgVM.get().animStateClearTracks(h); if (active) HostSpine.AnimationState_clearTracks(tr(h)); }
    public int AnimationState_getCurrentAnimationID(int h, int tr2) { int u = UnidbgVM.get().animStateGetCurrentAnimationID(h, tr2); if (active) ci("AnimationState_getCurrentAnimationID", u, HostSpine.AnimationState_getCurrentAnimationID(tr(h), tr2)); return u; }
    public float AnimationState_getCurrentAnimationTime(int h, int tr2) { float u = UnidbgVM.get().animStateGetCurrentAnimationTime(h, tr2); if (active) cf("AnimationState_getCurrentAnimationTime", u, HostSpine.AnimationState_getCurrentAnimationTime(tr(h), tr2)); return u; }
    public boolean AnimationState_nextEvent(int h, int[] out) { long t0=System.nanoTime(); boolean u = UnidbgVM.get().animStateNextEvent(h, out); if (active) { long t1=System.nanoTime(); int[] ju = new int[out.length]; boolean j = HostSpine.AnimationState_nextEvent(tr(h), ju); long t2=System.nanoTime(); uNanos.add(t1-t0); jNanos.add(t2-t1); cb("AnimationState_nextEvent", u, j); } return u; }
}
