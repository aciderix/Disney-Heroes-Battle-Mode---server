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

    // Bug g256 (personnages "éclatés" en combat) : cspine_jni.c applique un facteur TEXV_SCALE=0.5 sur la
    // coordonnée V de CHAQUE sommet, en dur, sans condition — ce facteur EST correct mais UNIQUEMENT pour
    // l'ETC1 (PerBlue empile l'alpha SOUS le RGB faute de canal alpha natif → texture physique 2× la hauteur
    // déclarée → il faut ramener V dans [0,0.5]). Le jeu (CETTE session, confirmé par
    // GameMain.initTextureCompressionType()/log applicatif "Final texture compression chosen: ETC2") utilise
    // l'ETC2, qui a un vrai canal alpha (EAC) et N'A PAS ce besoin — le 0.5 systématique divise alors la VRAIE
    // coordonnée V par 2, échantillonnant la MAUVAISE moitié verticale de l'atlas pour toute région dont le V
    // réel dépasse 0.5 → morceaux de sprite visuellement corrects individuellement (texturés depuis le VRAI
    // atlas) mais mal choisis/mal positionnés relativement les uns aux autres ("éclaté"). Confirmé PAR LE FAIT
    // (harnais différentiel CompareBackend, pas supposé) : diff systématique et EXACTE ×2 sur la coord V
    // (position #5) entre oracle unidbg et candidat JNI, sur TOUS les combattants. Fix glue (§1) : le format de
    // compression ACTUELLEMENT en cours (connu avec certitude côté Java, `GameMain.getTextureCompression()`,
    // AUCUNE readaptation côté C) est poussé au natif AVANT toute création d'atlas — le 0.5 ne s'applique plus
    // que si le jeu tourne RÉELLEMENT en ETC1.
    public static native void Spine_setEtc1AlphaPacked(boolean packed);
    private static volatile boolean texFlagPushed = false;
    /** Appelé par TOUT chemin qui crée un atlas (shadow direct ET CompareBackend) avant le premier
     *  {@code Atlas_create} — une seule fois par process (le format de compression est fixé pour toute
     *  la session par {@code GameMain.initTextureCompressionType()} au boot, jamais changé ensuite). */
    public static void ensureTexFlag() {
        if (texFlagPushed) return;
        synchronized (HostSpine.class) {
            if (texFlagPushed) return;
            boolean etc1;
            try {
                Object app = Class.forName("com.perblue.heroes.DH").getField("app").get(null);
                Object comp = app.getClass().getMethod("getTextureCompression").invoke(app);
                etc1 = comp != null && "ETC1".equals(comp.toString());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("lecture GameMain.getTextureCompression() impossible", e);
            }
            Spine_setEtc1AlphaPacked(etc1);
            texFlagPushed = true;
        }
    }

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
