import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import java.io.File;
import java.nio.file.Files;

/** Benchmark du chemin PAR-FRAME (Effect_update = simulation particules) via unidbg.
 *  args: <lib.so> <atlas> <np> <backend: unicorn|dynarmic> */
public class SpineBench {
  public static void main(String[] args) throws Exception {
    String backend = args.length>3 ? args[3] : "unicorn";
    AndroidEmulatorBuilder b = AndroidEmulatorBuilder.for32Bit();
    b.setProcessName("dhspine");
    if (backend.equals("dynarmic")) b.addBackendFactory(new DynarmicFactory(true));
    AndroidEmulator emulator = b.build();
    Memory memory = emulator.getMemory();
    memory.setLibraryResolver(new AndroidResolver(23));
    VM vm = emulator.createDalvikVM();
    vm.setVerbose(false);
    DalvikModule dm = vm.loadLibrary(new File(args[0]), true);
    System.out.printf("backend=%s base=0x%x%n", backend, dm.getModule().base);

    DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
    DvmClass cPart  = vm.resolveClass("com/perblue/heroes/cparticle/Native");
    cSpine.callStaticJniMethod(emulator, "Spine_init()V");
    byte[] atlasBytes = Files.readAllBytes(new File(args[1]).toPath());
    byte[] npBytes    = Files.readAllBytes(new File(args[2]).toPath());
    int atlasH = cSpine.callStaticJniMethodInt(emulator, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
    int effH   = cPart.callStaticJniMethodInt(emulator, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
    System.out.printf("atlasH=%d effH=%d%n", atlasH, effH);
    try { cPart.callStaticJniMethod(emulator, "Effect_start(I)V", effH); } catch (Throwable t){ System.out.println("start: "+t); }

    // warm-up
    for (int i=0;i<50;i++) cPart.callStaticJniMethodInt(emulator, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.016f));
    int N = 1000;
    long t = System.nanoTime();
    for (int i=0;i<N;i++) cPart.callStaticJniMethodInt(emulator, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.016f));
    double us = (System.nanoTime()-t)/1e3/N;
    System.out.printf("Effect_update: %.1f us/appel  (=%.2f ms ; budget frame 60fps=16667us)%n", us, us/1000);
    System.out.printf(">> pour tenir 60fps il faudrait < %d update/frame de ce type%n", (long)(16667/Math.max(us,0.001)));
  }
}
