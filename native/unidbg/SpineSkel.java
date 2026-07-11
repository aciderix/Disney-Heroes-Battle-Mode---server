import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.DynarmicFactory;
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

/** Valide le pipeline SPINE d'origine via unidbg : Atlas/SkeletonData/Skeleton/AnimationState +
 *  Skeleton_getVertices, avec chrono par-frame. args: <lib> <atlas> <skel> */
public class SpineSkel {
  static VM vm;
  public static void main(String[] args) throws Exception {
    AndroidEmulatorBuilder bld = AndroidEmulatorBuilder.for32Bit();
    bld.setProcessName("dhspine");
    if (args.length>3 && args[3].equals("dynarmic")) bld.addBackendFactory(new DynarmicFactory(true));
    AndroidEmulator emulator = bld.build();
    System.out.println("backend="+(args.length>3?args[3]:"unicorn"));
    Memory memory = emulator.getMemory();
    memory.setLibraryResolver(new AndroidResolver(23));
    vm = emulator.createDalvikVM();
    vm.setVerbose(false);
    vm.loadLibrary(new File(args[0]), true);

    // GetDirectBufferAddress / Capacity (JNI 230/231) manquants dans unidbg
    Pointer env = vm.getJNIEnv();
    UnidbgPointer table = (UnidbgPointer) env.getPointer(0);
    table.setPointer(230*4, emulator.getSvcMemory().registerSvc(new ArmSvc(){
      public long handle(Emulator<?> emu){
        DvmObject<?> o = vm.getObject(emu.getContext().getIntArg(1));
        Object v = o==null?null:o.getValue();
        return v instanceof UnidbgPointer ? ((UnidbgPointer)v).peer : 0;
      }}));
    table.setPointer(231*4, emulator.getSvcMemory().registerSvc(new ArmSvc(){
      public long handle(Emulator<?> emu){ return 0x40000; }}));

    DvmClass N = vm.resolveClass("com/perblue/heroes/cspine/Native");
    N.callStaticJniMethod(emulator, "Spine_init()V");
    byte[] atlas = Files.readAllBytes(new File(args[1]).toPath());
    byte[] skel  = Files.readAllBytes(new File(args[2]).toPath());
    System.out.printf("atlas=%d skel=%d%n", atlas.length, skel.length);

    int atlasH = N.callStaticJniMethodInt(emulator, "Atlas_create([BZ)I", new ByteArray(vm, atlas), 1);
    System.out.println("Atlas_create -> "+atlasH+"  err="+N.callStaticJniMethodObject(emulator,"getLastSpineError()Ljava/lang/String;"));
    int dataH = N.callStaticJniMethodInt(emulator, "SkeletonData_create([BI)I", new ByteArray(vm, skel), atlasH);
    System.out.println("SkeletonData_create -> "+dataH+"  err="+N.callStaticJniMethodObject(emulator,"getLastSpineError()Ljava/lang/String;"));
    if (dataH<=0){ System.out.println("SKEL FAIL"); return; }
    int skelH = N.callStaticJniMethodInt(emulator, "Skeleton_create(I)I", dataH);
    int asdH  = N.callStaticJniMethodInt(emulator, "AnimationStateData_create(IF)I", dataH, Float.floatToRawIntBits(0.2f));
    int stH   = N.callStaticJniMethodInt(emulator, "AnimationState_create(I)I", asdH);
    System.out.printf("skelH=%d asdH=%d stH=%d%n", skelH, asdH, stH);
    // anim 1 (1-based), track 0, loop
    int set = N.callStaticJniMethodInt(emulator, "AnimationState_setAnimation(IIIZ)I", stH, 0, 1, 1);
    System.out.println("setAnimation(anim=1) -> "+set);

    MemoryBlock vb = memory.malloc(256*1024, false);
    MemoryBlock ib = memory.malloc(64*1024, false);
    MemoryBlock db = memory.malloc(4*1024, false);
    DvmObject<?> V = vm.resolveClass("java/nio/FloatBuffer").newObject(vb.getPointer());
    DvmObject<?> I = vm.resolveClass("java/nio/ShortBuffer").newObject(ib.getPointer());
    DvmObject<?> D = vm.resolveClass("java/nio/ShortBuffer").newObject(db.getPointer());
    String GV = "Skeleton_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;Ljava/nio/ShortBuffer;)I";

    // une frame
    N.callStaticJniMethod(emulator, "AnimationState_update(IF)V", stH, Float.floatToRawIntBits(0.1f));
    N.callStaticJniMethod(emulator, "AnimationState_apply(II)V", stH, skelH);
    N.callStaticJniMethod(emulator, "Skeleton_updateWorldTransform(I)V", skelH);
    int nDraw = N.callStaticJniMethodInt(emulator, GV, skelH, V, I, D);
    System.out.println("Skeleton_getVertices -> drawCount="+nDraw+"  err="+N.callStaticJniMethodObject(emulator,"getLastSpineError()Ljava/lang/String;"));
    float[] fv = vb.getPointer().getFloatArray(0, 18);
    System.out.print("  sommets[0..17]: "); for (float f: fv) System.out.printf("%.2f ", f); System.out.println();
    byte[] dcb = db.getPointer().getByteArray(0, Math.min(nDraw*2,16)*2);
    System.out.print("  drawCalls(shorts LE): ");
    for (int q=0;q+1<dcb.length;q+=2) System.out.printf("%d ", (short)((dcb[q]&0xff)|(dcb[q+1]<<8)));
    System.out.println();

    // breakdown : chaque appel séparément
    int M=300; long a,b;
    a=System.nanoTime(); for(int k=0;k<M;k++) N.callStaticJniMethod(emulator,"AnimationState_update(IF)V",stH,Float.floatToRawIntBits(0.016f)); b=System.nanoTime();
    System.out.printf("  update:%.0f us  ",(b-a)/1e3/M);
    a=System.nanoTime(); for(int k=0;k<M;k++) N.callStaticJniMethod(emulator,"AnimationState_apply(II)V",stH,skelH); b=System.nanoTime();
    System.out.printf("apply:%.0f us  ",(b-a)/1e3/M);
    a=System.nanoTime(); for(int k=0;k<M;k++) N.callStaticJniMethod(emulator,"Skeleton_updateWorldTransform(I)V",skelH); b=System.nanoTime();
    System.out.printf("worldTransform:%.0f us  ",(b-a)/1e3/M);
    a=System.nanoTime(); for(int k=0;k<M;k++) N.callStaticJniMethodInt(emulator,GV,skelH,V,I,D); b=System.nanoTime();
    System.out.printf("getVertices:%.0f us%n",(b-a)/1e3/M);

    // chrono par-frame complet (update+apply+worldtransform+getVertices)
    int Nn=300; long t=System.nanoTime();
    for (int k=0;k<Nn;k++){
      N.callStaticJniMethod(emulator, "AnimationState_update(IF)V", stH, Float.floatToRawIntBits(0.016f));
      N.callStaticJniMethod(emulator, "AnimationState_apply(II)V", stH, skelH);
      N.callStaticJniMethod(emulator, "Skeleton_updateWorldTransform(I)V", skelH);
      N.callStaticJniMethodInt(emulator, GV, skelH, V, I, D);
    }
    double us=(System.nanoTime()-t)/1e3/Nn;
    System.out.printf("spine par-frame (update+apply+wt+getVertices): %.1f us/squelette -> ~%d squelettes/frame@60fps%n",
        us, (long)(16667/Math.max(us,0.001)));
  }
}
