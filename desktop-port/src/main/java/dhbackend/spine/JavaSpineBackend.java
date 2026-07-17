package dhbackend.spine;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Bone;
import com.esotericsoftware.spine.BoneData;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonBinary;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.Skin;
import com.esotericsoftware.spine.SlotData;
import com.esotericsoftware.spine.attachments.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Backend Opt.3 (#28) — implémente la sous-surface cspine du COMBAT (mesurée en Phase 0) via le runtime Java
 * {@code spine-libgdx-perblue}, EN REMPLACEMENT d'unidbg (émulation ARM). Activé par {@code -Ddh.spinebackend=java}.
 *
 * <p><b>Contrat RÉPLIQUÉ À L'IDENTIQUE</b> du JNI d'origine ({@code native/src/cspine_jni.c}) pour la fidélité :
 * <ul>
 *   <li>IDs d'animation <b>1-based</b> (0 = aucune) ; IDs de bone <b>0-based</b> (-1 = absent).</li>
 *   <li>{@code getBoneTransform} = <b>6 floats</b> {@code [worldX, worldY, worldRotationX, worldScaleX, worldScaleY, 0]}, bone = {@code skeleton.getBones().get(boneId)}.</li>
 *   <li>{@code getCurrentAnimationTime} = <b>trackTime</b> du TrackEntry courant (PAS animationTime).</li>
 *   <li>{@code nextEvent} = <b>false</b> (comme le natif : file d'events non branchée ; keyframes de combat =
 *       prefab, pas events spine).</li>
 *   <li>{@code Skeleton_create} = create + {@code setToSetupPose} + {@code updateWorldTransform} (comme le JNI).</li>
 *   <li>scale par défaut = 1 (le JNI ne fixe pas d'échelle) ; attachments stub (le combat ne rend rien, Phase 0 :
 *       0 {@code getVertices} → les transforms d'os n'en dépendent pas).</li>
 * </ul>
 * À CERTIFIER contre l'oracle unidbg (bone transforms + timing de mixing). L'atlas/textures restent sur unidbg
 * (setup, hors chemin combat) ; ce backend ignore {@code atlasHandle}.
 */
public final class JavaSpineBackend {

    public static final JavaSpineBackend INSTANCE = new JavaSpineBackend();
    private JavaSpineBackend() {}

    /** Le combat ne rend rien → attachments vides (suffisent pour parser + calculer les transforms d'os). */
    private static final AttachmentLoader STUB = new AttachmentLoader() {
        public RegionAttachment newRegionAttachment(Skin s, String n, String p) { return new RegionAttachment(n); }
        public MeshAttachment newMeshAttachment(Skin s, String n, String p) { return new MeshAttachment(n); }
        public BoundingBoxAttachment newBoundingBoxAttachment(Skin s, String n) { return new BoundingBoxAttachment(n); }
        public ClippingAttachment newClippingAttachment(Skin s, String n) { return new ClippingAttachment(n); }
        public PathAttachment newPathAttachment(Skin s, String n) { return new PathAttachment(n); }
        public PointAttachment newPointAttachment(Skin s, String n) { return new PointAttachment(n); }
    };

    private static final class SData {
        final SkeletonData data; final Array<Animation> anims; final Array<BoneData> bones;
        SData(SkeletonData d) { data = d; anims = d.getAnimations(); bones = d.getBones(); }
    }
    private static final class Asd { final AnimationStateData asd; final Array<Animation> anims; Asd(AnimationStateData a, Array<Animation> an) { asd = a; anims = an; } }
    private static final class St { final AnimationState state; final Array<Animation> anims; St(AnimationState s, Array<Animation> an) { state = s; anims = an; } }

    private final IntMap<SData> skelData = new IntMap<>();
    private final IntMap<Skeleton> skels = new IntMap<>();
    private final IntMap<Asd> asds = new IntMap<>();
    private final IntMap<St> states = new IntMap<>();
    private int nextSD = 1, nextSk = 1, nextAsd = 1, nextSt = 1;

    // ============================================================== SkeletonData
    public int SkeletonData_create(final byte[] skelBytes, int atlasHandle) {
        SkeletonBinary sb = new SkeletonBinary(STUB);
        FileHandle fh = new FileHandle(new java.io.File("mem.skel")) {
            @Override public InputStream read() { return new ByteArrayInputStream(skelBytes); }
        };
        SkeletonData d = sb.readSkeletonData(fh);
        if (d == null) return 0;
        int h = nextSD++; skelData.put(h, new SData(d)); return h;
    }
    public void SkeletonData_dispose(int h) { skelData.remove(h); }
    public float[] SkeletonData_getAnimationDurations(int h) {
        SData s = (SData) skelData.get(h); if (s == null) return new float[0];
        float[] a = new float[s.anims.size];
        for (int i = 0; i < s.anims.size; i++) a[i] = ((Animation) s.anims.get(i)).getDuration();
        return a;
    }
    public int SkeletonData_getAnimationID(int h, String name) {   // 1-based, 0 = absent (JNI)
        SData s = (SData) skelData.get(h); if (s == null) return 0;
        for (int i = 0; i < s.anims.size; i++) if (((Animation) s.anims.get(i)).getName().equals(name)) return i + 1;
        return 0;
    }
    public String[] SkeletonData_getAnimationNames(int h) {
        SData s = (SData) skelData.get(h); if (s == null) return new String[0];
        String[] a = new String[s.anims.size];
        for (int i = 0; i < s.anims.size; i++) a[i] = ((Animation) s.anims.get(i)).getName();
        return a;
    }
    public int SkeletonData_getBoneID(int h, String name) {        // 0-based, -1 = absent (JNI)
        SData s = (SData) skelData.get(h); if (s == null) return -1;
        for (int i = 0; i < s.bones.size; i++) if (((BoneData) s.bones.get(i)).getName().equals(name)) return i;
        return -1;
    }
    public String[] SkeletonData_getBoneNames(int h) {
        SData s = (SData) skelData.get(h); if (s == null) return new String[0];
        String[] a = new String[s.bones.size];
        for (int i = 0; i < s.bones.size; i++) a[i] = ((BoneData) s.bones.get(i)).getName();
        return a;
    }
    public String[] SkeletonData_getSkinNames(int h) {
        SData s = (SData) skelData.get(h); if (s == null) return new String[0];
        Array<Skin> sk = s.data.getSkins(); String[] a = new String[sk.size];
        for (int i = 0; i < sk.size; i++) a[i] = ((Skin) sk.get(i)).getName();
        return a;
    }
    public String[] SkeletonData_getSlotNames(int h) {
        SData s = (SData) skelData.get(h); if (s == null) return new String[0];
        Array<SlotData> sl = s.data.getSlots(); String[] a = new String[sl.size];
        for (int i = 0; i < sl.size; i++) a[i] = ((SlotData) sl.get(i)).getName();
        return a;
    }
    public boolean SkeletonData_getStats(int h, Object out) { return skelData.containsKey(h); }   // diag stub (= JNI)
    public Object[] SkeletonData_getVertexWeightReport(int h, int slot) { return null; }           // diag stub (= JNI)

    // ============================================================== Skeleton
    public int Skeleton_create(int dataHandle) {
        SData s = (SData) skelData.get(dataHandle); if (s == null) return 0;
        Skeleton sk = new Skeleton(s.data);
        sk.setToSetupPose(); sk.updateWorldTransform();   // parité JNI (Skeleton_create)
        int h = nextSk++; skels.put(h, sk); return h;
    }
    public void Skeleton_dispose(int h) { skels.remove(h); }
    public void Skeleton_update(int h, float dt) { Skeleton s = (Skeleton) skels.get(h); if (s != null) s.update(dt); }
    public void Skeleton_updateWorldTransform(int h) { Skeleton s = (Skeleton) skels.get(h); if (s != null) s.updateWorldTransform(); }
    public void Skeleton_setToSetupPose(int h) { Skeleton s = (Skeleton) skels.get(h); if (s != null) s.setToSetupPose(); }
    public void Skeleton_setColor(int h, float r, float g, float b, float a) { Skeleton s = (Skeleton) skels.get(h); if (s != null) s.getColor().set(r, g, b, a); }
    public void Skeleton_setTintBlack(int h, float r, float g, float b) { /* no-op (= JNI) */ }
    public boolean Skeleton_setSkin(int h, String name) {
        Skeleton s = (Skeleton) skels.get(h); if (s == null) return false;
        try { s.setSkin(name); s.setSlotsToSetupPose(); return true; } catch (Throwable t) { return false; }
    }
    public boolean Skeleton_setSlotEyeState(int h, int slot, int state) { return false; }   // stub (= JNI)
    public void Skeleton_getBoneTransform(int h, int boneId, float[] out, int off) {
        Skeleton s = (Skeleton) skels.get(h); if (s == null) return;
        Array<Bone> bs = s.getBones(); if (boneId < 0 || boneId >= bs.size) return;
        writeBone((Bone) bs.get(boneId), out, off);
    }
    // Réplique la sémantique EXACTE du JNI d'origine (boucle i=idOff..ids.length) — cf. cspine_jni.c. Note :
    // NativeSkeleton passe idOff = NOMBRE d'os (pas un offset) ⇒ sous unidbg la boucle est vide (no-op) quand
    // items.length == nombre d'os. Le JVM borne strictement (unidbg non) → on GARDE chaque écriture. Ce chemin
    // pluriel sert ShadowRenderable (ombres COSMÉTIQUES), hors chemin de décision du combat (le combat lit les
    // positions d'os via getBoneTransform SINGLE).
    public void Skeleton_getBoneTransforms(int h, int[] ids, int idOff, float[] out, int outOff) {
        Skeleton s = (Skeleton) skels.get(h); if (s == null) return;
        Array<Bone> bs = s.getBones();
        for (int i = idOff; i < ids.length; i++) {
            int bid = ids[i]; if (bid < 0 || bid >= bs.size) continue;
            int o = outOff + (i - idOff) * 6;
            if (o < 0 || o + 5 >= out.length) continue;   // garde de bornes (unidbg ne bornait pas)
            writeBone((Bone) bs.get(bid), out, o);
        }
    }
    // STRIDE 6 (pas 7) : le jeu alloue/lit 6 floats/os (NativeSkeleton : tmpTF=float[6], getBoneTransforms
    // dimensionne n*6). Le JNI d'origine écrit 7 (bug toléré par unidbg sans bounds-check ; le 7ᵉ déborde en
    // JVM strict). Le jeu ne lit que [worldX, worldY, worldRotationX, worldScaleX, worldScaleY, 0] → 6 floats
    // = EXACTEMENT ce que le combat consomme du chemin natif (bit-identique).
    private static void writeBone(Bone b, float[] out, int o) {
        out[o]   = b.getWorldX();           out[o+1] = b.getWorldY();
        out[o+2] = b.getWorldRotationX();   out[o+3] = b.getWorldScaleX();
        out[o+4] = b.getWorldScaleY();      out[o+5] = 0;
    }
    public void Skeleton_setBoneTransform(int h, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy) {
        Skeleton s = (Skeleton) skels.get(h); if (s == null) return;
        Array<Bone> bs = s.getBones(); if (boneId < 0 || boneId >= bs.size) return;
        Bone b = (Bone) bs.get(boneId);
        b.setX(x); b.setY(y); b.setRotation(rot); b.setScaleX(sx); b.setScaleY(sy); b.setShearX(shx); b.setShearY(shy);
    }
    public void Skeleton_getPosedBounds(int h, float[] out) { if (out.length >= 4) { out[0]=out[1]=out[2]=out[3]=0; } } // non appelé en combat (Phase 0)
    public int Skeleton_getVertices(int h, java.nio.FloatBuffer v, java.nio.ShortBuffer i, java.nio.ShortBuffer d) { return 0; }             // rendu — jamais appelé en combat
    public int Skeleton_getVerticesAndBounds(int h, java.nio.FloatBuffer v, java.nio.ShortBuffer i, java.nio.ShortBuffer d, float[] b) { return 0; }
    public int Skeleton_getVerticesAndBoundsGlitched(int h, java.nio.FloatBuffer v, java.nio.ShortBuffer i, java.nio.ShortBuffer d, float[] b) { return 0; }

    // ============================================================== AnimationStateData
    public int AnimationStateData_create(int dataHandle, float defaultMix) {
        SData s = (SData) skelData.get(dataHandle); if (s == null) return 0;
        AnimationStateData asd = new AnimationStateData(s.data); asd.setDefaultMix(defaultMix);
        int h = nextAsd++; asds.put(h, new Asd(asd, s.anims)); return h;
    }
    public void AnimationStateData_dispose(int h) { asds.remove(h); }
    public void AnimationStateData_setMix(int h, int fromA, int toA, float dur) {   // IDs 1-based (JNI)
        Asd a = (Asd) asds.get(h); if (a == null) return;
        if (fromA >= 1 && fromA <= a.anims.size && toA >= 1 && toA <= a.anims.size)
            a.asd.setMix((Animation) a.anims.get(fromA - 1), (Animation) a.anims.get(toA - 1), dur);
    }

    // ============================================================== AnimationState
    public int AnimationState_create(int asdHandle) {
        Asd a = (Asd) asds.get(asdHandle); if (a == null) return 0;
        int h = nextSt++; states.put(h, new St(new AnimationState(a.asd), a.anims)); return h;
    }
    public void AnimationState_dispose(int h) { states.remove(h); }
    public void AnimationState_update(int h, float dt) { St s = (St) states.get(h); if (s != null) s.state.update(dt); }
    public void AnimationState_apply(int h, int skelHandle) {
        St s = (St) states.get(h); Skeleton sk = (Skeleton) skels.get(skelHandle);
        if (s != null && sk != null) s.state.apply(sk);
    }
    public int AnimationState_setAnimation(int h, int track, int animId, boolean loop) {   // 1-based ; retour = animId (JNI)
        St s = (St) states.get(h); if (s == null || animId < 1 || animId > s.anims.size) return -1;
        s.state.setAnimation(track, (Animation) s.anims.get(animId - 1), loop); return animId;
    }
    public int AnimationState_addAnimation(int h, int track, int animId, boolean loop, float delay) {
        St s = (St) states.get(h); if (s == null || animId < 1 || animId > s.anims.size) return -1;
        s.state.addAnimation(track, (Animation) s.anims.get(animId - 1), loop, delay); return animId;
    }
    public void AnimationState_clearTracks(int h) { St s = (St) states.get(h); if (s != null) s.state.clearTracks(); }
    public int AnimationState_getCurrentAnimationID(int h, int track) {   // 1-based ; 0 = aucune (JNI)
        St s = (St) states.get(h); if (s == null) return 0;
        AnimationState.TrackEntry t = s.state.getCurrent(track); if (t == null || t.getAnimation() == null) return 0;
        Animation a = t.getAnimation();
        for (int i = 0; i < s.anims.size; i++) if (s.anims.get(i) == a) return i + 1;
        return 0;
    }
    public float AnimationState_getCurrentAnimationTime(int h, int track) {
        // Temps d'animation WRAPPÉ (borné par la durée, cycle pour un loop), pas le trackTime BRUT (accumulatif).
        // Le natif renvoie un temps borné (mesuré : max = durée de la plus longue anim) — le jeu s'en sert pour
        // re-déclencher les keyframes à chaque cycle. getTrackTime() (accumulatif → 90s) casse ce mécanisme
        // (le jeu croit l'anim au-delà de tous les keyframes → n'attaque plus qu'une fois). getAnimationTime()
        // reproduit le comportement natif observé.
        St s = (St) states.get(h); if (s == null) return 0;
        AnimationState.TrackEntry t = s.state.getCurrent(track); return t == null ? 0 : t.getAnimationTime();
    }
    public boolean AnimationState_nextEvent(int h, int[] out) { return false; }   // = JNI (file d'events non branchée)
}
