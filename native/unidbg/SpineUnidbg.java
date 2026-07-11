import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.memory.Memory;
import java.io.File;

/** Prototype : charger le VRAI libspine-native.so (ARM) dans unidbg et appeler ses fonctions JNI
 *  d'origine, sans réécriture. Test de faisabilité + chrono. */
public class SpineUnidbg {
  public static void main(String[] args) throws Exception {
    AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit()
        .setProcessName("dhspine").build();
    Memory memory = emulator.getMemory();
    memory.setLibraryResolver(new AndroidResolver(23));
    VM vm = emulator.createDalvikVM();
    vm.setVerbose(false);
    long t0 = System.nanoTime();
    DalvikModule dm = vm.loadLibrary(new File(args[0]), true);
    Module module = dm.getModule();
    System.out.printf("loaded %s base=0x%x size=%d in %.1f ms%n",
        module.name, module.base, module.size, (System.nanoTime()-t0)/1e6);

    DvmClass cNative = vm.resolveClass("com/perblue/heroes/cspine/Native");
    long t1 = System.nanoTime();
    cNative.callStaticJniMethod(emulator, "Spine_init()V");
    System.out.printf("Spine_init OK in %.1f us%n", (System.nanoTime()-t1)/1e3);

    Object err = cNative.callStaticJniMethodObject(emulator, "getLastSpineError()Ljava/lang/String;");
    System.out.println("getLastSpineError -> " + err);

    // warm-up + chrono d'appels répétés (proxy du coût par-frame)
    int N = 200;
    long t2 = System.nanoTime();
    for (int i=0;i<N;i++) cNative.callStaticJniMethod(emulator, "Spine_init()V");
    double us = (System.nanoTime()-t2)/1e3/N;
    System.out.printf("Spine_init x%d : %.1f us/appel (émulation ARM in-process)%n", N, us);
    System.out.println("OK");
  }
}
