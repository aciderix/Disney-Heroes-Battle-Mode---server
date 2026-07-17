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

    // ---- rapport de diffs ----
    static final class Stat { final LongAdder calls = new LongAdder(), diffs = new LongAdder(); volatile long maxUlp = 0; volatile String firstEx; }
    private final ConcurrentHashMap<String, Stat> stats = new ConcurrentHashMap<>();
    private Stat st(String m) { return stats.computeIfAbsent(m, k -> new Stat()); }
    public void ensureLoaded() { UnidbgVM.get(); HostSpine.ensureLoaded(); }

    public String report() {
        StringBuilder b = new StringBuilder("[compare] certification unidbg(oracle) vs JNI-natif :\n");
        stats.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().diffs.sum(), x.getValue().diffs.sum()))
            .forEach(e -> { Stat s = e.getValue();
                b.append(String.format("  %-34s appels=%-7d DIFFS=%-7d maxUlp=%d%s%n",
                    e.getKey(), s.calls.sum(), s.diffs.sum(), s.maxUlp, s.firstEx == null ? "" : "  ex: " + s.firstEx)); });
        long tot = stats.values().stream().mapToLong(s -> s.diffs.sum()).sum();
        b.append("  ==> TOTAL DIFFS = ").append(tot).append(tot == 0 ? "  ✅ CERTIFIÉ IDENTIQUE" : "  ❌ divergences").append('\n');
        return b.toString();
    }

    // ---- comparateurs ----
    private static long ulp(float a, float b) { return Math.abs((long) Float.floatToRawIntBits(a) - (long) Float.floatToRawIntBits(b)); }
    private void ci(String m, int u, int j) { Stat s = st(m); s.calls.increment(); if (u != j) { s.diffs.increment(); if (s.firstEx == null) s.firstEx = "u=" + u + " j=" + j; } }
    private void cb(String m, boolean u, boolean j) { Stat s = st(m); s.calls.increment(); if (u != j) { s.diffs.increment(); if (s.firstEx == null) s.firstEx = "u=" + u + " j=" + j; } }
    private void cf(String m, float u, float j) { Stat s = st(m); s.calls.increment(); if (Float.floatToRawIntBits(u) != Float.floatToRawIntBits(j)) { s.diffs.increment(); long ul = ulp(u, j); if (ul > s.maxUlp) s.maxUlp = ul; if (s.firstEx == null) s.firstEx = "u=" + u + " j=" + j + " ulp=" + ul; } }
    private void cfa(String m, float[] u, float[] j, int n) { Stat s = st(m); s.calls.increment(); if (u == null || j == null) { if (u != j) s.diffs.increment(); return; } boolean d = false; long mu = 0; for (int i = 0; i < n; i++) { if (Float.floatToRawIntBits(u[i]) != Float.floatToRawIntBits(j[i])) { d = true; mu = Math.max(mu, ulp(u[i], j[i])); } } if (d) { s.diffs.increment(); if (mu > s.maxUlp) s.maxUlp = mu; if (s.firstEx == null) s.firstEx = "u[0]=" + u[0] + " j[0]=" + j[0] + " maxUlp=" + mu; } }
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
    public int SkeletonData_getBoneID(int h, String n) { int u = UnidbgVM.get().skeletonDataGetBoneID(h, n); if (active) ci("SkeletonData_getBoneID", u, HostSpine.SkeletonData_getBoneID(tr(h), n)); return u; }
    public String[] SkeletonData_getBoneNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetBoneNames(h); if (active) csa("SkeletonData_getBoneNames", u, HostSpine.SkeletonData_getBoneNames(tr(h))); return u; }
    public String[] SkeletonData_getSkinNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetSkinNames(h); if (active) csa("SkeletonData_getSkinNames", u, HostSpine.SkeletonData_getSkinNames(tr(h))); return u; }
    public String[] SkeletonData_getSlotNames(int h) { String[] u = UnidbgVM.get().skeletonDataGetSlotNames(h); if (active) csa("SkeletonData_getSlotNames", u, HostSpine.SkeletonData_getSlotNames(tr(h))); return u; }

    // ================= Skeleton
    public int Skeleton_create(int d) { int u = UnidbgVM.get().skeletonCreate(d); int j = HostSpine.Skeleton_create(tr(d)); u2j.put(u, j); return u; }
    public void Skeleton_dispose(int h) { UnidbgVM.get().skeletonDispose(h); HostSpine.Skeleton_dispose(tr(h)); u2j.remove(h); }
    public void Skeleton_update(int h, float dt) { UnidbgVM.get().skeletonUpdate(h, dt); if (active) HostSpine.Skeleton_update(tr(h), dt); }
    public void Skeleton_updateWorldTransform(int h) { UnidbgVM.get().skeletonUpdateWorldTransform(h); if (active) HostSpine.Skeleton_updateWorldTransform(tr(h)); }
    public void Skeleton_setToSetupPose(int h) { UnidbgVM.get().skeletonSetToSetupPose(h); if (active) HostSpine.Skeleton_setToSetupPose(tr(h)); }
    public void Skeleton_setColor(int h, float r, float g, float b, float a) { UnidbgVM.get().skeletonSetColor(h, r, g, b, a); if (active) HostSpine.Skeleton_setColor(tr(h), r, g, b, a); }
    public void Skeleton_setTintBlack(int h, float r, float g, float b) { UnidbgVM.get().skeletonSetTintBlack(h, r, g, b); if (active) HostSpine.Skeleton_setTintBlack(tr(h), r, g, b); }
    public boolean Skeleton_setSkin(int h, String n) { boolean u = UnidbgVM.get().skeletonSetSkin(h, n); if (active) cb("Skeleton_setSkin", u, HostSpine.Skeleton_setSkin(tr(h), n)); return u; }
    public boolean Skeleton_setSlotEyeState(int h, int s, int st) { boolean u = UnidbgVM.get().skeletonSetSlotEyeState(h, s, st); if (active) cb("Skeleton_setSlotEyeState", u, HostSpine.Skeleton_setSlotEyeState(tr(h), s, st)); return u; }
    public void Skeleton_getBoneTransform(int h, int bid, float[] out, int off) { UnidbgVM.get().skeletonGetBoneTransform(h, bid, out, off); if (active) { float[] ju = new float[off + 6]; HostSpine.Skeleton_getBoneTransform(tr(h), bid, ju, off); float[] uu = new float[6], jj = new float[6]; for (int i = 0; i < 6; i++) { uu[i] = out[off + i]; jj[i] = ju[off + i]; } cfa("Skeleton_getBoneTransform", uu, jj, 6); } }
    public void Skeleton_getBoneTransforms(int h, int[] ids, int idOff, float[] out, int outOff) { UnidbgVM.get().skeletonGetBoneTransforms(h, ids, idOff, out, outOff); if (active) { float[] ju = new float[out.length]; HostSpine.Skeleton_getBoneTransforms(tr(h), ids, idOff, ju, outOff); cfa("Skeleton_getBoneTransforms", out, ju, out.length); } }
    public void Skeleton_setBoneTransform(int h, int bid, float x, float y, float rot, float sx, float sy, float shx, float shy) { UnidbgVM.get().skeletonSetBoneTransform(h, bid, x, y, rot, sx, sy, shx, shy); if (active) HostSpine.Skeleton_setBoneTransform(tr(h), bid, x, y, rot, sx, sy, shx, shy); }
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
    public void AnimationState_update(int h, float dt) { UnidbgVM.get().animStateUpdate(h, dt); if (active) HostSpine.AnimationState_update(tr(h), dt); }
    public void AnimationState_apply(int h, int sk) { UnidbgVM.get().animStateApply(h, sk); if (active) HostSpine.AnimationState_apply(tr(h), tr(sk)); }
    public int AnimationState_setAnimation(int h, int tr2, int id, boolean loop) { int u = UnidbgVM.get().animStateSetAnimation(h, tr2, id, loop); if (active) ci("AnimationState_setAnimation", u, HostSpine.AnimationState_setAnimation(tr(h), tr2, id, loop)); return u; }
    public int AnimationState_addAnimation(int h, int tr2, int id, boolean loop, float delay) { int u = UnidbgVM.get().animStateAddAnimation(h, tr2, id, loop, delay); if (active) ci("AnimationState_addAnimation", u, HostSpine.AnimationState_addAnimation(tr(h), tr2, id, loop, delay)); return u; }
    public void AnimationState_clearTracks(int h) { UnidbgVM.get().animStateClearTracks(h); if (active) HostSpine.AnimationState_clearTracks(tr(h)); }
    public int AnimationState_getCurrentAnimationID(int h, int tr2) { int u = UnidbgVM.get().animStateGetCurrentAnimationID(h, tr2); if (active) ci("AnimationState_getCurrentAnimationID", u, HostSpine.AnimationState_getCurrentAnimationID(tr(h), tr2)); return u; }
    public float AnimationState_getCurrentAnimationTime(int h, int tr2) { float u = UnidbgVM.get().animStateGetCurrentAnimationTime(h, tr2); if (active) cf("AnimationState_getCurrentAnimationTime", u, HostSpine.AnimationState_getCurrentAnimationTime(tr(h), tr2)); return u; }
    public boolean AnimationState_nextEvent(int h, int[] out) { boolean u = UnidbgVM.get().animStateNextEvent(h, out); if (active) { int[] ju = new int[out.length]; cb("AnimationState_nextEvent", u, HostSpine.AnimationState_nextEvent(tr(h), ju)); } return u; }
}
