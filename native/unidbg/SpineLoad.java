import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import java.io.File;
import java.nio.file.Files;

/** Charge le VRAI libspine-native.so dans unidbg et exécute le pipeline de CHARGEMENT d'origine :
 *  Atlas_create (atlas des particules) puis Effect_create (parse le .np) — sans aucune RE du format.
 *  args: <lib.so> <atlas> <np> */
public class SpineLoad {
  public static void main(String[] args) throws Exception {
    AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit().setProcessName("dhspine").build();
    Memory memory = emulator.getMemory();
    memory.setLibraryResolver(new AndroidResolver(23));
    VM vm = emulator.createDalvikVM();
    vm.setVerbose(false);
    DalvikModule dm = vm.loadLibrary(new File(args[0]), true);
    Module module = dm.getModule();
    System.out.printf("loaded base=0x%x size=%d%n", module.base, module.size);

    DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
    DvmClass cPart  = vm.resolveClass("com/perblue/heroes/cparticle/Native");
    cSpine.callStaticJniMethod(emulator, "Spine_init()V");

    byte[] atlasBytes = Files.readAllBytes(new File(args[1]).toPath());
    byte[] npBytes    = Files.readAllBytes(new File(args[2]).toPath());
    System.out.printf("atlas=%d bytes, np=%d bytes%n", atlasBytes.length, npBytes.length);

    long t0 = System.nanoTime();
    int atlasH = cSpine.callStaticJniMethodInt(emulator, "Atlas_create([BZ)I",
        new ByteArray(vm, atlasBytes), 1);
    System.out.printf("Atlas_create -> handle=%d in %.1f ms%n", atlasH, (System.nanoTime()-t0)/1e6);
    Object aerr = cSpine.callStaticJniMethodObject(emulator, "getLastSpineError()Ljava/lang/String;");
    System.out.println("  spineErr=" + aerr);

    long t1 = System.nanoTime();
    int effH = cPart.callStaticJniMethodInt(emulator, "Effect_create([BI)I",
        new ByteArray(vm, npBytes), atlasH);
    System.out.printf("Effect_create -> handle=%d in %.1f ms  <== parse .np par le CODE D'ORIGINE%n",
        effH, (System.nanoTime()-t1)/1e6);
    Object perr = cPart.callStaticJniMethodObject(emulator, "getLastParticleError()Ljava/lang/String;");
    System.out.println("  particleErr=" + perr);
    Object mult = null;
    try { int m = cPart.callStaticJniMethodInt(emulator, "usesMultiply(I)Z", effH);
          System.out.println("  usesMultiply=" + m); } catch (Throwable t){ System.out.println("  usesMultiply err "+t); }
    try { int z = cPart.callStaticJniMethodInt(emulator, "usesZOffsets(I)Z", effH);
          System.out.println("  usesZOffsets=" + z); } catch (Throwable t){ System.out.println("  usesZOffsets err "+t); }
    System.out.println(effH>0 ? "PARSE OK (handle valide) — #NP-V3 résolu par exécution" : "PARSE FAIL");
  }
}
