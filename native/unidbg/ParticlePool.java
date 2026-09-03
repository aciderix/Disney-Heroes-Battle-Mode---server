import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * INVESTIGATION FPS PARTICULES (g261) — mesure le VRAI coût par-frame de la simulation+rendu de particules
 * (Effect_update + Effect_getVertices) via unidbg, sur de vrais .np de combat, ET teste si un POOL de K VMs
 * unidbg indépendantes (une par thread) scale ~K× (elles ne partagent rien).
 *
 * RÉSULTAT MESURÉ (Linux, ralph_skill1_impact 16 Ko, régime établi) : update ≈ 26 µs/effet, getVertices ≈ 35 µs/
 * effet → ~61 µs/effet/frame. 16 effets = ~1 ms/frame → budget 60fps = ~270 effets. ⇒ l'ÉMULATION des particules
 * N'EST PAS le goulot des ~50 ms qui font tomber le combat à 17fps (il faudrait ~950 effets). Le pool scale ×2,5
 * sur 4 cœurs mais c'est inutile vu le coût. La chute de FPS en combat est donc côté RENDU GPU (fill-rate/overdraw
 * des quads transparents), pas côté émulation → à confirmer sur un vrai GPU (test résolution). Voir docs/WINDOWS_PILOTING.md
 * / le brief particules. Outil laissé committé pour rejouer/étendre la mesure.
 *
 * BUILD + RUN :
 *   # classpath unidbg : soit via le module gradle dédié…
 *   cd native/unidbg && gradle -q cp && CP=$(cat build/runtime.cp)
 *   # …soit en réutilisant le RUNTIME_CP d'un client déjà généré (desktop-port/build/client-manifest.env) + libs/commons-logging.jar
 *   javac -cp "$CP" ParticlePool.java
 *   A=../../desktop-port/build/apk/assets/ETC1/world/units/ralph/vfx
 *   java -Xverify:none -cp "$CP:." ParticlePool ../reference/libspine-native.so \
 *        "$A/particles-DEFAULT.atlas" "$A/ralph_skill1_impact.np"  16 150 4 150
 *   # args : <lib.so> <atlas> <np> <E effets> <M frames mesurées> <K taille pool> [warm frames]
 */
public class ParticlePool {
  static byte[] atlasBytes, npBytes; static String SO;
  static volatile long updNanos, uvNanos; // cumul (baseline mono-VM seulement)

  /** Une VM unidbg autonome portant un sous-ensemble d'effets (émulateur + mémoire + .so séparés → rien de partagé). */
  static final class Worker {
    final AndroidEmulator emu; final VM vm; final DvmClass cSpine, cPart; final int[] eff;
    final DvmObject<?> objVerts, objDraw;
    Worker(int nEff) throws Exception {
      AndroidEmulatorBuilder b = AndroidEmulatorBuilder.for32Bit(); b.setProcessName("dhpp");
      emu = b.build();
      Memory memory = emu.getMemory(); memory.setLibraryResolver(new AndroidResolver(23));
      vm = emu.createDalvikVM(); vm.setVerbose(false);
      vm.loadLibrary(new File(SO), true);
      // override JNI 230/231 (GetDirectBufferAddress/Capacity), absents d'unidbg — cf. UnidbgVM.java
      Pointer env = vm.getJNIEnv(); UnidbgPointer table = (UnidbgPointer) env.getPointer(0);
      table.setPointer(230 * 4, emu.getSvcMemory().registerSvc(new ArmSvc() {
        @Override public long handle(Emulator<?> e) { DvmObject<?> o = vm.getObject(e.getContext().getIntArg(1));
          Object v = o == null ? null : o.getValue(); return v instanceof UnidbgPointer ? ((UnidbgPointer) v).peer : 0; } }));
      table.setPointer(231 * 4, emu.getSvcMemory().registerSvc(new ArmSvc() {
        @Override public long handle(Emulator<?> e) { return 0x100000; } }));
      MemoryBlock embV = memory.malloc(8192 * 6 * 4, false), embD = memory.malloc(8192 * 2, false);
      memory.malloc(16384 * 2, false); // embIndices (dimensionnement, non relu ici)
      objVerts = vm.resolveClass("java/nio/FloatBuffer").newObject(embV.getPointer());
      objDraw  = vm.resolveClass("java/nio/ShortBuffer").newObject(embD.getPointer());
      cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
      cPart  = vm.resolveClass("com/perblue/heroes/cparticle/Native");
      cSpine.callStaticJniMethod(emu, "Spine_init()V");
      int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
      eff = new int[nEff];
      for (int i = 0; i < nEff; i++) { eff[i] = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        try { cPart.callStaticJniMethod(emu, "Effect_start(I)V", eff[i]); } catch (Throwable ignore) {} }
    }
    void frame(float dt, boolean timed) {
      int dtb = Float.floatToRawIntBits(dt);
      for (int h : eff) {
        if (timed) { long a = System.nanoTime(); cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", h, dtb);
          long m = System.nanoTime(); cPart.callStaticJniMethodInt(emu, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I", h, objVerts, objDraw);
          long c = System.nanoTime(); updNanos += m - a; uvNanos += c - m; }
        else { cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", h, dtb);
          cPart.callStaticJniMethodInt(emu, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I", h, objVerts, objDraw); }
      }
    }
  }

  public static void main(String[] a) throws Exception {
    SO = a[0]; atlasBytes = Files.readAllBytes(new File(a[1]).toPath()); npBytes = Files.readAllBytes(new File(a[2]).toPath());
    int E = a.length > 3 ? Integer.parseInt(a[3]) : 16, M = a.length > 4 ? Integer.parseInt(a[4]) : 150, K = a.length > 5 ? Integer.parseInt(a[5]) : 4;
    int WARM = a.length > 6 ? Integer.parseInt(a[6]) : 150;
    System.out.printf("cores=%d | E=%d effets, M=%d frames, K=%d VMs, warm=%d%n",
        Runtime.getRuntime().availableProcessors(), E, M, K, WARM);

    // BASELINE : 1 VM
    Worker solo = new Worker(E);
    for (int i = 0; i < WARM; i++) solo.frame(0.016f, false);   // ramp des particules -> régime établi
    updNanos = 0; uvNanos = 0;
    long t0 = System.nanoTime(); for (int f = 0; f < M; f++) solo.frame(0.016f, true);
    double ms = (System.nanoTime() - t0) / 1e6 / M;
    System.out.printf("[1 VM] %.2f ms/frame pour %d effets | update=%.0f us/eff getVertices=%.0f us/eff (total %.0f us/eff)%n",
        ms, E, updNanos / 1e3 / M / E, uvNanos / 1e3 / M / E, (updNanos + uvNanos) / 1e3 / M / E);
    System.out.printf("[1 VM] budget 16.67ms/frame = %.0f effets max @60fps%n", 16.667 / (ms / E));

    // POOL : K VMs sur K threads
    Worker[] ws = new Worker[K]; int per = (E + K - 1) / K, rem = E;
    for (int k = 0; k < K; k++) { int n = Math.min(per, rem); rem -= n; ws[k] = new Worker(Math.max(n, 0)); }
    ExecutorService pool = Executors.newFixedThreadPool(K);
    for (int i = 0; i < WARM; i++) par(pool, ws, 1);
    long t1 = System.nanoTime(); par(pool, ws, M); double pms = (System.nanoTime() - t1) / 1e6 / M;
    pool.shutdownNow();
    System.out.printf("[%d VMs] %.2f ms/frame pour %d effets -> speedup x%.2f | budget = %.0f effets max @60fps%n",
        K, pms, E, ms / pms, 16.667 / (pms / E));
  }
  static void par(ExecutorService pool, Worker[] ws, int frames) throws Exception {
    for (int f = 0; f < frames; f++) { List<Future<?>> fu = new ArrayList<>();
      for (Worker w : ws) fu.add(pool.submit(() -> { w.frame(0.016f, false); return null; }));
      for (Future<?> x : fu) x.get(); }
  }
}
