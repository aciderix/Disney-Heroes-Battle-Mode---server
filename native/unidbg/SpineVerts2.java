import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.context.RegisterContext;
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

/** unidbg + implémentation du JNI GetDirectBufferAddress manquant (plomberie plateforme) → teste le
 *  chemin de RENDU d'origine Effect_getVertices et le chronomètre. */
public class SpineVerts2 {
  public static void main(String[] args) throws Exception {
    AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit().setProcessName("dhspine").build();
    Memory memory = emulator.getMemory();
    memory.setLibraryResolver(new AndroidResolver(23));
    final VM vm = emulator.createDalvikVM();
    vm.setVerbose(false);
    vm.loadLibrary(new File(args[0]), true);

    // --- Implémente GetDirectBufferAddress (index JNI 230) + GetDirectBufferCapacity (231),
    //     absents d'unidbg : renvoie le pointeur émulé porté par le DvmObject (sa value). ---
    Pointer env = vm.getJNIEnv();
    UnidbgPointer table = (UnidbgPointer) env.getPointer(0);
    UnidbgPointer getAddr = emulator.getSvcMemory().registerSvc(new ArmSvc() {
      public long handle(Emulator<?> emu) {
        RegisterContext ctx = emu.getContext();
        int hash = ctx.getIntArg(1);            // jobject (r1) = hash unidbg
        DvmObject<?> obj = vm.getObject(hash);
        Object v = obj == null ? null : obj.getValue();
        return v instanceof UnidbgPointer ? ((UnidbgPointer) v).peer : 0;
      }
    });
    UnidbgPointer getCap = emulator.getSvcMemory().registerSvc(new ArmSvc() {
      public long handle(Emulator<?> emu) { return 0x10000; }   // capacité (octets) — suffisante
    });
    table.setPointer(230 * 4, getAddr);
    table.setPointer(231 * 4, getCap);

    DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
    DvmClass cPart  = vm.resolveClass("com/perblue/heroes/cparticle/Native");
    cSpine.callStaticJniMethod(emulator, "Spine_init()V");
    byte[] atlasBytes = Files.readAllBytes(new File(args[1]).toPath());
    byte[] npBytes    = Files.readAllBytes(new File(args[2]).toPath());
    int atlasH = cSpine.callStaticJniMethodInt(emulator, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
    int effH   = cPart.callStaticJniMethodInt(emulator, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
    cPart.callStaticJniMethod(emulator, "Effect_start(I)V", effH);
    for (int i=0;i<10;i++) cPart.callStaticJniMethodInt(emulator, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.033f));

    MemoryBlock vb = memory.malloc(64*1024, false);
    MemoryBlock ib = memory.malloc(16*1024, false);
    DvmObject<?> vBuf = vm.resolveClass("java/nio/FloatBuffer").newObject(vb.getPointer());
    DvmObject<?> iBuf = vm.resolveClass("java/nio/ShortBuffer").newObject(ib.getPointer());
    String SIG = "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I";

    int n = cPart.callStaticJniMethodInt(emulator, SIG, effH, vBuf, iBuf);
    System.out.println("Effect_getVertices -> " + n + "  particleErr=" +
        cPart.callStaticJniMethodObject(emulator, "getLastParticleError()Ljava/lang/String;"));
    float[] fv = vb.getPointer().getFloatArray(0, 18);
    System.out.print("  sommets[0..17]: "); for (float f: fv) System.out.printf("%.2f ", f); System.out.println();

    int N=500;
    long t=System.nanoTime();
    for (int i=0;i<N;i++){
      cPart.callStaticJniMethodInt(emulator, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.016f));
      cPart.callStaticJniMethodInt(emulator, SIG, effH, vBuf, iBuf);
    }
    double us=(System.nanoTime()-t)/1e3/N;
    System.out.printf("update+getVertices: %.1f us/frame/effet  (budget 60fps=16667us -> ~%d effets/frame)%n",
        us, (long)(16667/Math.max(us,0.001)));
  }
}
