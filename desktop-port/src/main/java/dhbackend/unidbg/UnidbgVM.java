package dhbackend.unidbg;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.array.FloatArray;
import com.github.unidbg.linux.android.dvm.array.IntArray;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Pont vers le VRAI binaire natif de PerBlue (`libspine-native.so`, ARM) exécuté in-process par unidbg.
 * Le code Java d'origine `cspine.Native`/`cparticle.Native` (remplacé par des shadows minces) dispatche
 * ici : on exécute donc le CODE D'ORIGINE de spine ET des particules, sans réécriture (PRINCIPLES §4 :
 * câblage, pas récréation ; c'est de la plomberie plateforme, aucune logique de jeu).
 *
 * VM unidbg = mono-thread → toutes les entrées sont `synchronized` (exclusion mutuelle globale).
 */
public final class UnidbgVM {
    private static UnidbgVM INSTANCE;
    public static synchronized UnidbgVM get() {
        if (INSTANCE == null) INSTANCE = new UnidbgVM();
        return INSTANCE;
    }

    private final AndroidEmulator emulator;
    private final VM vm;
    private final DvmClass cSpine, cPart;

    /** Buffers émulés réutilisés pour getVertices (alloués une fois ; appels sérialisés). */
    private final MemoryBlock embVerts, embIndices, embDraw;
    private final DvmObject<?> objVerts, objIndices, objDraw;

    private UnidbgVM() {
        String libPath = System.getProperty("dh.spinelib");
        if (libPath == null) libPath = "../native/reference/libspine-native.so";
        File lib = new File(libPath);
        if (!lib.exists()) throw new IllegalStateException("libspine-native.so introuvable: " + lib.getAbsolutePath());

        // OPTIM (opt-in) : backend d'émulation dynarmic (JIT ARM) au lieu de l'interpréteur Unicorn par défaut.
        // Exécute les MÊMES instructions ARM du VRAI binaire → résultats identiques (à certifier), mais bien plus
        // rapide. Fallback Unicorn si une instruction n'est pas supportée (arg true). Activé par -Ddh.dynarmic.
        AndroidEmulatorBuilder builder = AndroidEmulatorBuilder.for32Bit();
        boolean dynarmic = System.getProperty("dh.dynarmic") != null && !"0".equals(System.getProperty("dh.dynarmic"));
        if (dynarmic) {
            builder.addBackendFactory(new com.github.unidbg.arm.backend.DynarmicFactory(true));
            System.out.println("[unidbg] backend dynarmic (JIT ARM) activé");
        }
        emulator = builder.setProcessName("dhspine").build();
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));
        vm = emulator.createDalvikVM();
        vm.setVerbose(false);
        vm.loadLibrary(lib, true);

        // Implémente GetDirectBufferAddress (230) / GetDirectBufferCapacity (231), absents d'unidbg :
        // renvoie le pointeur émulé porté par le DvmObject (sa value). Plomberie JNI standard.
        Pointer env = vm.getJNIEnv();
        UnidbgPointer table = (UnidbgPointer) env.getPointer(0);
        table.setPointer(230 * 4, emulator.getSvcMemory().registerSvc(new ArmSvc() {
            @Override public long handle(Emulator<?> emu) {
                DvmObject<?> o = vm.getObject(emu.getContext().getIntArg(1));
                Object v = o == null ? null : o.getValue();
                return v instanceof UnidbgPointer ? ((UnidbgPointer) v).peer : 0;
            }
        }));
        table.setPointer(231 * 4, emulator.getSvcMemory().registerSvc(new ArmSvc() {
            @Override public long handle(Emulator<?> emu) { return 0x100000; }
        }));

        cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        cPart  = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emulator, "Spine_init()V");

        // Dimensionnés pour le PLUS GRAND des deux meshes : spine (2300 v / 6900 i) et particules
        // (4000 v / 6000 i, 6 floats/sommet). copyFloats recopie capacity() floats → le buffer émulé
        // doit couvrir la capacité du buffer de mesh du jeu.
        embVerts   = memory.malloc(8192 * 6 * 4, false);
        embIndices = memory.malloc(16384 * 2, false);
        embDraw    = memory.malloc(8192 * 2, false);
        objVerts   = vm.resolveClass("java/nio/FloatBuffer").newObject(embVerts.getPointer());
        objIndices = vm.resolveClass("java/nio/ShortBuffer").newObject(embIndices.getPointer());
        objDraw    = vm.resolveClass("java/nio/ShortBuffer").newObject(embDraw.getPointer());
    }

    // ----- profilage : temps cumulé passé DANS l'émulation unidbg (spine+particules) -----
    // Permet d'attribuer le coût par frame (unidbg vs rasterisation logicielle). Getter/reset statiques.
    private static final java.util.concurrent.atomic.AtomicLong EMU_NANOS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong EMU_CALLS = new java.util.concurrent.atomic.AtomicLong();
    public static long emuNanos() { return EMU_NANOS.get(); }
    public static long emuCalls() { return EMU_CALLS.get(); }
    public static void emuReset() { EMU_NANOS.set(0); EMU_CALLS.set(0); }

    // ----- helpers de dispatch (chronométrés) -----
    private int si(String sig, Object... a)   { long t=System.nanoTime(); try { return cSpine.callStaticJniMethodInt(emulator, sig, a); } finally { rec(t); } }
    private void sv(String sig, Object... a)  { long t=System.nanoTime(); try { cSpine.callStaticJniMethod(emulator, sig, a); } finally { rec(t); } }
    private float sf(String sig, Object... a) { long t=System.nanoTime(); try { return cSpine.callStaticJniMethodInt(emulator, sig, a); } finally { rec(t); } } // float via bits ci-dessous
    private DvmObject<?> so(String sig, Object... a) { long t=System.nanoTime(); try { return cSpine.callStaticJniMethodObject(emulator, sig, a); } finally { rec(t); } }
    private int pi(String sig, Object... a)   { long t=System.nanoTime(); try { return cPart.callStaticJniMethodInt(emulator, sig, a); } finally { rec(t); } }
    private void pv(String sig, Object... a)  { long t=System.nanoTime(); try { cPart.callStaticJniMethod(emulator, sig, a); } finally { rec(t); } }
    private DvmObject<?> po(String sig, Object... a) { long t=System.nanoTime(); try { return cPart.callStaticJniMethodObject(emulator, sig, a); } finally { rec(t); } }
    private static void rec(long startNanos) { EMU_NANOS.addAndGet(System.nanoTime() - startNanos); EMU_CALLS.incrementAndGet(); }
    private static int fb(float f) { return Float.floatToRawIntBits(f); }

    private static String str(DvmObject<?> o) { return o == null ? null : (String) o.getValue(); }

    /** Copie N floats de la mémoire émulée vers un FloatBuffer direct du jeu (LE), position/limit posés. */
    private static void copyFloats(MemoryBlock src, FloatBuffer dst, int n) {
        if (dst == null || n <= 0) return;
        byte[] raw = src.getPointer().getByteArray(0, n * 4);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        dst.clear();
        int cap = dst.capacity();
        int cnt = Math.min(n, cap);
        for (int i = 0; i < cnt; i++) dst.put(i, bb.getFloat(i * 4));
        dst.position(0); dst.limit(cnt);
    }
    private static void copyShorts(MemoryBlock src, ShortBuffer dst, int n) {
        if (dst == null || n <= 0) return;
        byte[] raw = src.getPointer().getByteArray(0, n * 2);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        dst.clear();
        int cap = dst.capacity();
        int cnt = Math.min(n, cap);
        for (int i = 0; i < cnt; i++) dst.put(i, bb.getShort(i * 2));
        dst.position(0); dst.limit(cnt);
    }

    // =============================================================== cspine
    public synchronized String getLastSpineError() { return str(so("getLastSpineError()Ljava/lang/String;")); }
    public synchronized void printUsedHandleReport() { sv("printUsedHandleReport()V"); }

    public synchronized int atlasCreate(byte[] bytes, boolean premul) {
        return si("Atlas_create([BZ)I", new ByteArray(vm, bytes), premul ? 1 : 0);
    }
    public synchronized void atlasDispose(int h) { sv("Atlas_dispose(I)V", h); }
    public synchronized boolean atlasGetParams(int h, int page, int[] out) {
        IntArray a = new IntArray(vm, out);
        boolean r = si("Atlas_getParams(II[I)Z", h, page, a) != 0;
        System.arraycopy(a.getValue(), 0, out, 0, out.length);
        return r;
    }
    public synchronized String atlasGetTexture(int h, int page) { return str(so("Atlas_getTexture(II)Ljava/lang/String;", h, page)); }

    public synchronized int skeletonDataCreate(byte[] skel, int atlasH) {
        return si("SkeletonData_create([BI)I", new ByteArray(vm, skel), atlasH);
    }
    public synchronized void skeletonDataDispose(int h) { sv("SkeletonData_dispose(I)V", h); }
    public synchronized float[] skeletonDataGetAnimationDurations(int h) {
        DvmObject<?> o = so("SkeletonData_getAnimationDurations(I)[F", h);
        return o == null ? null : (float[]) o.getValue();
    }
    public synchronized int skeletonDataGetAnimationID(int h, String name) {
        return si("SkeletonData_getAnimationID(ILjava/lang/String;)I", h, new StringObject(vm, name));
    }
    public synchronized String[] skeletonDataGetAnimationNames(int h) { return strArray(so("SkeletonData_getAnimationNames(I)[Ljava/lang/String;", h)); }
    public synchronized int skeletonDataGetBoneID(int h, String name) {
        return si("SkeletonData_getBoneID(ILjava/lang/String;)I", h, new StringObject(vm, name));
    }
    public synchronized String[] skeletonDataGetBoneNames(int h) { return strArray(so("SkeletonData_getBoneNames(I)[Ljava/lang/String;", h)); }
    public synchronized String[] skeletonDataGetSkinNames(int h) { return strArray(so("SkeletonData_getSkinNames(I)[Ljava/lang/String;", h)); }
    public synchronized String[] skeletonDataGetSlotNames(int h) { return strArray(so("SkeletonData_getSlotNames(I)[Ljava/lang/String;", h)); }

    private static String[] strArray(DvmObject<?> o) {
        if (o == null) return null;
        Object v = o.getValue();
        if (v instanceof String[]) return (String[]) v;
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v; String[] r = new String[a.length];
            for (int i = 0; i < a.length; i++) r[i] = a[i] == null ? null : (a[i] instanceof DvmObject ? (String) ((DvmObject<?>) a[i]).getValue() : a[i].toString());
            return r;
        }
        return null;
    }

    public synchronized int skeletonCreate(int dataH) { return si("Skeleton_create(I)I", dataH); }
    public synchronized void skeletonDispose(int h) { sv("Skeleton_dispose(I)V", h); }
    public synchronized void skeletonUpdate(int h, float dt) { sv("Skeleton_update(IF)V", h, fb(dt)); }
    public synchronized void skeletonUpdateWorldTransform(int h) { sv("Skeleton_updateWorldTransform(I)V", h); }
    public synchronized void skeletonSetToSetupPose(int h) { sv("Skeleton_setToSetupPose(I)V", h); }
    public synchronized void skeletonSetColor(int h, float r, float g, float b, float a) { sv("Skeleton_setColor(IFFFF)V", h, fb(r), fb(g), fb(b), fb(a)); }
    public synchronized void skeletonSetTintBlack(int h, float r, float g, float b) { sv("Skeleton_setTintBlack(IFFF)V", h, fb(r), fb(g), fb(b)); }
    public synchronized boolean skeletonSetSkin(int h, String name) { return si("Skeleton_setSkin(ILjava/lang/String;)Z", h, name == null ? null : new StringObject(vm, name)) != 0; }
    public synchronized boolean skeletonSetSlotEyeState(int h, int slot, int state) { return si("Skeleton_setSlotEyeState(III)Z", h, slot, state) != 0; }
    public synchronized void skeletonGetBoneTransform(int h, int boneId, float[] out, int off) {
        FloatArray a = new FloatArray(vm, out);
        sv("Skeleton_getBoneTransform(II[FI)V", h, boneId, a, off);
        System.arraycopy(a.getValue(), 0, out, 0, out.length);
    }
    public synchronized void skeletonGetBoneTransforms(int h, int[] ids, int idOff, float[] out, int outOff) {
        IntArray ia = new IntArray(vm, ids); FloatArray fa = new FloatArray(vm, out);
        sv("Skeleton_getBoneTransforms(I[II[FI)V", h, ia, idOff, fa, outOff);
        System.arraycopy(fa.getValue(), 0, out, 0, out.length);
    }
    public synchronized void skeletonSetBoneTransform(int h, int boneId, float x, float y, float rot, float sx, float sy, float shx, float shy) {
        sv("Skeleton_setBoneTransform(IIFFFFFFF)V", h, boneId, fb(x), fb(y), fb(rot), fb(sx), fb(sy), fb(shx), fb(shy));
    }
    public synchronized void skeletonGetPosedBounds(int h, float[] out) {
        FloatArray a = new FloatArray(vm, out);
        sv("Skeleton_getPosedBounds(I[F)V", h, a);
        System.arraycopy(a.getValue(), 0, out, 0, out.length);
    }

    private boolean loggedSkel, loggedEff;
    public synchronized int skeletonGetVertices(int h, FloatBuffer verts, ShortBuffer indices, ShortBuffer draws) {
        int n = si("Skeleton_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;Ljava/nio/ShortBuffer;)I", h, objVerts, objIndices, objDraw);
        if (!loggedSkel) { loggedSkel = true; System.out.println("[UnidbgVM] 1er Skeleton_getVertices (spine d'origine via unidbg) -> drawCount=" + n); }
        copyFloats(embVerts, verts, verts == null ? 0 : verts.capacity());
        copyShorts(embIndices, indices, indices == null ? 0 : indices.capacity());
        copyShorts(embDraw, draws, n * 2);
        return n;
    }
    public synchronized int skeletonGetVerticesAndBounds(int h, FloatBuffer verts, ShortBuffer indices, ShortBuffer draws, float[] bounds) {
        FloatArray ba = new FloatArray(vm, bounds);
        int n = si("Skeleton_getVerticesAndBounds(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;Ljava/nio/ShortBuffer;[F)I", h, objVerts, objIndices, objDraw, ba);
        copyFloats(embVerts, verts, verts == null ? 0 : verts.capacity());
        copyShorts(embIndices, indices, indices == null ? 0 : indices.capacity());
        copyShorts(embDraw, draws, n * 2);
        if (bounds != null) System.arraycopy(ba.getValue(), 0, bounds, 0, bounds.length);
        return n;
    }

    public synchronized int animStateDataCreate(int dataH, float defaultMix) { return si("AnimationStateData_create(IF)I", dataH, fb(defaultMix)); }
    public synchronized void animStateDataDispose(int h) { sv("AnimationStateData_dispose(I)V", h); }
    public synchronized void animStateDataSetMix(int h, int fromA, int toA, float dur) { sv("AnimationStateData_setMix(IIIF)V", h, fromA, toA, fb(dur)); }

    public synchronized int animStateCreate(int asdH) { return si("AnimationState_create(I)I", asdH); }
    public synchronized void animStateDispose(int h) { sv("AnimationState_dispose(I)V", h); }
    public synchronized void animStateUpdate(int h, float dt) { sv("AnimationState_update(IF)V", h, fb(dt)); }
    public synchronized void animStateApply(int h, int skelH) { sv("AnimationState_apply(II)V", h, skelH); }
    public synchronized int animStateSetAnimation(int h, int track, int animId, boolean loop) { return si("AnimationState_setAnimation(IIIZ)I", h, track, animId, loop ? 1 : 0); }
    public synchronized int animStateAddAnimation(int h, int track, int animId, boolean loop, float delay) { return si("AnimationState_addAnimation(IIIZF)I", h, track, animId, loop ? 1 : 0, fb(delay)); }
    public synchronized void animStateClearTracks(int h) { sv("AnimationState_clearTracks(I)V", h); }
    public synchronized int animStateGetCurrentAnimationID(int h, int track) { return si("AnimationState_getCurrentAnimationID(II)I", h, track); }
    public synchronized float animStateGetCurrentAnimationTime(int h, int track) {
        int bits = si("AnimationState_getCurrentAnimationTime(II)F", h, track);
        return Float.intBitsToFloat(bits);
    }
    public synchronized boolean animStateNextEvent(int h, int[] out) {
        IntArray a = new IntArray(vm, out);
        boolean r = si("AnimationState_nextEvent(I[I)Z", h, a) != 0;
        System.arraycopy(a.getValue(), 0, out, 0, out.length);
        return r;
    }

    // =============================================================== cparticle
    public synchronized String getLastParticleError() { return str(po("getLastParticleError()Ljava/lang/String;")); }
    public synchronized int effectCreate(byte[] np, int atlasH) { return pi("Effect_create([BI)I", new ByteArray(vm, np), atlasH); }
    public synchronized int effectClone(int h) { return pi("Effect_clone(I)I", h); }
    public synchronized void effectDispose(int h) { pv("Effect_dispose(I)V", h); }
    // Particules : le drawCalls a **3 shorts par draw call** PUIS **1 short = nombre total de sommets**
    // (NativeParticleEffect.getVertices lit drawCalls.get(n*3) pour dériver verts.limit/indices.limit) →
    // on copie n*3+1 shorts. (≠ spine : 2 shorts/pair.) Le nombre de sommets à l'index n*3 est écrit
    // par le natif d'origine.
    private static int drawShorts(int n) { return n > 0 ? n * 3 + 1 : 0; }
    public synchronized int effectGetVertices(int h, FloatBuffer verts, ShortBuffer draws) {
        int n = pi("Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I", h, objVerts, objDraw);
        if (!loggedEff) { loggedEff = true; System.out.println("[UnidbgVM] 1er Effect_getVertices (particules d'origine via unidbg) -> n=" + n); }
        copyFloats(embVerts, verts, verts == null ? 0 : verts.capacity());
        copyShorts(embDraw, draws, drawShorts(n));
        return n;
    }
    public synchronized int effectGetVerticesAboveZ(int h, float z, FloatBuffer verts, ShortBuffer draws) {
        int n = pi("Effect_getVerticesAboveZ(IFLjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I", h, fb(z), objVerts, objDraw);
        copyFloats(embVerts, verts, verts == null ? 0 : verts.capacity()); copyShorts(embDraw, draws, drawShorts(n)); return n;
    }
    public synchronized int effectGetVerticesBelowZ(int h, float z, FloatBuffer verts, ShortBuffer draws) {
        int n = pi("Effect_getVerticesBelowZ(IFLjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I", h, fb(z), objVerts, objDraw);
        copyFloats(embVerts, verts, verts == null ? 0 : verts.capacity()); copyShorts(embDraw, draws, drawShorts(n)); return n;
    }
    public synchronized void effectStart(int h) { pv("Effect_start(I)V", h); }
    public synchronized void effectReset(int h) { pv("Effect_reset(I)V", h); }
    public synchronized void effectKill(int h) { pv("Effect_kill(I)V", h); }
    public synchronized void effectStopEmitting(int h) { pv("Effect_stopEmitting(I)V", h); }
    public synchronized void effectSetPositionXY(int h, float x, float y) { pv("Effect_setPositionXY(IFF)V", h, fb(x), fb(y)); }
    public synchronized void effectSetPositionXYZ(int h, float x, float y, float z) { pv("Effect_setPositionXYZ(IFFF)V", h, fb(x), fb(y), fb(z)); }
    public synchronized void effectSetRotation(int h, float rot) { pv("Effect_setRotation(IF)V", h, fb(rot)); }
    public synchronized void effectSetScale(int h, float scale) { pv("Effect_setScale(IF)V", h, fb(scale)); }
    public synchronized boolean effectUpdate(int h, float dt) { return pi("Effect_update(IF)Z", h, fb(dt)) != 0; }
    public synchronized boolean effectUsesMultiply(int h) { return pi("Effect_usesMultiply(I)Z", h) != 0; }
    public synchronized boolean effectUsesZOffsets(int h) { return pi("Effect_usesZOffsets(I)Z", h) != 0; }
}
