import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import java.io.File;
import java.nio.file.Files;

/** Teste le chemin de RENDU via unidbg : Effect_getVertices remplit un FloatBuffer/ShortBuffer directs.
 *  On alloue la mémoire émulée, on la passe comme "direct buffer", on relit + chrono. */
public class SpineVerts {
  public static void main(String[] args) throws Exception {
    AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit().setProcessName("dhspine").build();
    Memory memory = emulator.getMemory();
    memory.setLibraryResolver(new AndroidResolver(23));
    VM vm = emulator.createDalvikVM();
    vm.setVerbose(false);
    vm.loadLibrary(new File(args[0]), true);
    DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
    DvmClass cPart  = vm.resolveClass("com/perblue/heroes/cparticle/Native");
    cSpine.callStaticJniMethod(emulator, "Spine_init()V");
    byte[] atlasBytes = Files.readAllBytes(new File(args[1]).toPath());
    byte[] npBytes    = Files.readAllBytes(new File(args[2]).toPath());
    int atlasH = cSpine.callStaticJniMethodInt(emulator, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
    int effH   = cPart.callStaticJniMethodInt(emulator, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
    cPart.callStaticJniMethod(emulator, "Effect_start(I)V", effH);
    for (int i=0;i<10;i++) cPart.callStaticJniMethodInt(emulator, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.033f));

    // buffers directs émulés
    MemoryBlock vb = memory.malloc(64*1024, false);   // FloatBuffer verts
    MemoryBlock ib = memory.malloc(16*1024, false);   // ShortBuffer indices
    DvmClass fb = vm.resolveClass("java/nio/FloatBuffer");
    DvmClass sb = vm.resolveClass("java/nio/ShortBuffer");
    DvmObject<?> vBuf = fb.newObject(vb.getPointer());
    DvmObject<?> iBuf = sb.newObject(ib.getPointer());

    // 1er appel (warm) + vérif retour
    int n = cPart.callStaticJniMethodInt(emulator, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I",
        effH, vBuf, iBuf);
    System.out.println("Effect_getVertices -> " + n);
    Object perr = cPart.callStaticJniMethodObject(emulator, "getLastParticleError()Ljava/lang/String;");
    System.out.println("  particleErr=" + perr);
    float[] first = vb.getPointer().getFloatArray(0, 12);
    System.out.print("  premiers floats: ");
    for (float f: first) System.out.printf("%.3f ", f);
    System.out.println();

    // chrono par-frame : update + getVertices
    int N=500;
    long t=System.nanoTime();
    for (int i=0;i<N;i++){
      cPart.callStaticJniMethodInt(emulator, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.016f));
      cPart.callStaticJniMethodInt(emulator, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I", effH, vBuf, iBuf);
    }
    double us=(System.nanoTime()-t)/1e3/N;
    System.out.printf("update+getVertices: %.1f us/frame/effet (budget 60fps=16667us)%n", us);
  }
}
