import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.debugger.BreakPointCallback;
import com.github.unidbg.debugger.Debugger;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ORACLE D'EXTRACTION du format `.np` v3 (g261ter) -- PRINCIPLES §4/§8 : on n'invente rien, on
 * OBSERVE l'exécution du VRAI parseur ARM (`ParticleEmitter::load`, natif d'origine PerBlue,
 * `native/reference/libspine-native.so`) via des BREAKPOINTS unidbg posés sur les 2 primitives de
 * lecture confirmées par désassemblage ciblé (native/tools/disasm.py) :
 *   - 0x1a770 (read4)     : lit 4 octets (int/float BE) au curseur, avance le curseur de 4.
 *   - 0x1a0d4 (readByte)  : lit 1 octet (bool) au curseur, avance le curseur de 1.
 * Dans les DEUX cas, r0 = adresse de la variable locale "curseur" (uint8_t* data) dans la pile de
 * l'appelant -- *r0 = valeur COURANTE du curseur (un pointeur dans le buffer .np émulé). On
 * n'a besoin de RIEN savoir d'autre sur la convention d'appel : on lit *r0 à CHAQUE hit, dans
 * l'ORDRE d'exécution réel -- c'est la vérité terrain, pas une reconstruction manuelle du
 * désassemblage complet de ParticleEmitter::load (qui avait donné 0/535 EOF-exact, cf. NP_FORMAT.md).
 *
 * Le premier hit donne le curseur de DÉPART (= offset 0 du fichier après l'entête 2 octets + le
 * champ emitterCount déjà lu par ParticleEffect::load AVANT d'entrer dans l'emitter) -- tous les
 * offsets sont calculés relativement à cette valeur.
 *
 * BUILD + RUN (mêmes instructions que ParticlePool.java) :
 *   cd native/unidbg && gradle -q cp && CP=$(cat build/runtime.cp)
 *   javac -cp "$CP" NpFormatOracle.java
 *   A=../../desktop-port/build/apk/assets/ETC1/world/units/<hero>/vfx
 *   java -Xverify:none -cp "$CP:." NpFormatOracle ../reference/libspine-native.so \
 *        "$A/particles-DEFAULT.atlas" "$A/<effect>.np"
 */
public class NpFormatOracle {
    static final long ADDR_READ4 = 0x1a770;
    static final long ADDR_READBYTE = 0x1a0d4;

    static final class Hit { final boolean isInt; final long cursor; final int val;
        Hit(boolean isInt, long cursor, int val) { this.isInt = isInt; this.cursor = cursor; this.val = val; } }

    public static void main(String[] args) throws Exception {
        String so = args[0];
        byte[] atlasBytes = Files.readAllBytes(new File(args[1]).toPath());
        byte[] npBytes = Files.readAllBytes(new File(args[2]).toPath());
        System.out.println("np file = " + args[2] + " (" + npBytes.length + " bytes)");

        AndroidEmulatorBuilder b = AndroidEmulatorBuilder.for32Bit();
        b.setProcessName("dhoracle");
        AndroidEmulator emu = b.build();
        Memory memory = emu.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();

        // GetDirectBufferAddress/Capacity (JNI 230/231), absents d'unidbg -- cf. UnidbgVM.java.
        Pointer env = vm.getJNIEnv();
        UnidbgPointer table = (UnidbgPointer) env.getPointer(0);
        table.setPointer(230 * 4, emu.getSvcMemory().registerSvc(new ArmSvc() {
            @Override public long handle(Emulator<?> e) { DvmObject<?> o = vm.getObject(e.getContext().getIntArg(1));
                Object v = o == null ? null : o.getValue(); return v instanceof UnidbgPointer ? ((UnidbgPointer) v).peer : 0; } }));
        table.setPointer(231 * 4, emu.getSvcMemory().registerSvc(new ArmSvc() {
            @Override public long handle(Emulator<?> e) { return 0x100000; } }));

        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        System.out.println("atlasH=" + atlasH);

        List<Hit> hits = new ArrayList<>();
        long[] base = {-1};
        Debugger dbg = emu.attach();
        final long modBase = mod.base;
        BreakPointCallback cb = (e, addr) -> {
            boolean isInt = ((addr - modBase) & ~1L) == ADDR_READ4;
            UnidbgPointer cursorSlot = e.getContext().getPointerArg(0); // r0 = &data (local var pile)
            UnidbgPointer cursor = cursorSlot == null ? null : (UnidbgPointer) cursorSlot.getPointer(0);
            long cAddr = cursor == null ? -1 : cursor.peer;
            int val = 0;
            // Le hook tire AVANT le `rev` (byte-swap BE->hôte) de la fonction native -> les 4 octets sont
            // encore en ordre fichier (BIG-ENDIAN) ; getInt() les relit tels quels en LE hôte -> corriger.
            if (cursor != null) val = isInt ? Integer.reverseBytes(cursor.getInt(0)) : (cursor.getByte(0) & 0xff);
            // CALIBRATION (vérifiée par recoupement fichier réel, arena_promote.np) : les 2 premiers octets
            // (magie 0x00 + version 0x03) sont consommés par un test direct data[0]==0&&data[1]==3 dans
            // ParticleEffect::load, PAS via read4/readByte -> le 1er hit hooké (emitterCount) est déjà au
            // vrai offset fichier 2, pas 0. D'où le -2 : sans lui, tous les offsets seraient décalés de 2.
            if (base[0] < 0 && cAddr >= 0) base[0] = cAddr - 2;
            hits.add(new Hit(isInt, cAddr, val));
            return true; // continue l'exécution
        };
        dbg.addBreakPoint(mod, ADDR_READ4, cb);
        dbg.addBreakPoint(mod, ADDR_READBYTE, cb);

        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        String err = (String) cPart.callStaticJniMethodObject(emu, "getLastParticleError()Ljava/lang/String;").getValue();
        System.out.println("effH=" + effH + " err=\"" + err + "\" hits=" + hits.size());

        System.out.println("--- séquence (offset_fichier type valeur) ---");
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            long off = h.cursor < 0 ? -1 : h.cursor - base[0];
            System.out.printf("%4d  off=%-6d %-4s val=%d (0x%x)%n", i, off, h.isInt ? "int" : "bool", h.val, h.val);
        }
        long maxEnd = 0;
        for (Hit h : hits) if (h.cursor >= 0) maxEnd = Math.max(maxEnd, (h.cursor - base[0]) + (h.isInt ? 4 : 1));
        System.out.println("dernier octet consommé par lecture scalaire (read4/readByte) : off=" + maxEnd
            + " / taille fichier=" + npBytes.length + " -> reste (pool+tag, memcpy bulk)=" + (npBytes.length - maxEnd));
        if (maxEnd + 8 <= npBytes.length) {
            int poolSize = ((npBytes[(int) maxEnd] & 0xff) << 24) | ((npBytes[(int) maxEnd + 1] & 0xff) << 16)
                | ((npBytes[(int) maxEnd + 2] & 0xff) << 8) | (npBytes[(int) maxEnd + 3] & 0xff);
            int tagLen = ((npBytes[(int) maxEnd + 4] & 0xff) << 24) | ((npBytes[(int) maxEnd + 5] & 0xff) << 16)
                | ((npBytes[(int) maxEnd + 6] & 0xff) << 8) | (npBytes[(int) maxEnd + 7] & 0xff);
            System.out.println("trailer @ off=" + maxEnd + " : poolSize=" + poolSize + " tagLen=" + tagLen
                + " -> bloc attendu = 8 + " + poolSize + "*4 + " + tagLen + " = " + (8 + poolSize * 4 + tagLen)
                + " (reste dispo=" + (npBytes.length - maxEnd) + ")");
            if (maxEnd + 8L + poolSize * 4L + tagLen <= npBytes.length && tagLen > 0 && tagLen < 200) {
                int tagOff = (int) (maxEnd + 8 + poolSize * 4L);
                System.out.println("tag @ off=" + tagOff + " = \"" + new String(npBytes, tagOff, tagLen,
                    java.nio.charset.StandardCharsets.UTF_8) + "\"");
            }
        }
        // Recoupement : les mêmes offsets, lus DIRECTEMENT dans le fichier source (vérité indépendante).
        System.out.println("--- octets bruts du fichier aux 24 premiers offsets observés (recoupement) ---");
        for (int i = 0; i < Math.min(24, hits.size()); i++) {
            Hit h = hits.get(i);
            if (h.cursor < 0) continue;
            long off = h.cursor - base[0];
            if (off < 0 || off + 4 > npBytes.length) continue;
            int be = ((npBytes[(int) off] & 0xff) << 24) | ((npBytes[(int) off + 1] & 0xff) << 16)
                   | ((npBytes[(int) off + 2] & 0xff) << 8) | (npBytes[(int) off + 3] & 0xff);
            System.out.printf("  off=%-6d fichier[off..+4)=0x%08x (int BE=%d, float=%f) fichier[off]=%d%n",
                off, be, be, Float.intBitsToFloat(be), npBytes[(int) off] & 0xff);
        }
    }
}
