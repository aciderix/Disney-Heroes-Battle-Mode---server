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
 *
 * MODE BATCH (certification 535/535 EOF-exact, un seul dossier avec .np+.atlas cote a cote) :
 *   java -Xverify:none -cp "$CP:." NpFormatOracle ../reference/libspine-native.so verify <dossier> [limite]
 */
public class NpFormatOracle {
    static final long ADDR_READ4 = 0x1a770;
    static final long ADDR_READBYTE = 0x1a0d4;
    // 3 lectures d'octet INLINE dans ParticleEmitter::load lui-même (PAS via le helper partagé readByte)
    // -- trouvées en scannant TOUT `ldrb rX, [rY], #1` dans la fonction (0x19755..0x19fc9, native/tools/
    // disasm.py). Confirmées conditionnelles (derrière un `cbz`/branch) -- cohérent avec des champs
    // OPTIONNELS (ex. readSpawnShape : active+code, PUIS 2 champs seulement si ellipse). Contrairement à
    // read4/readByte (r0 = &curseur, double indirection), ici le registre tient DIRECTEMENT l'adresse du
    // curseur (chargée par l'instruction juste avant depuis [sp,#0x50]) -- simple déréférence.
    static final long ADDR_INLINE1 = 0x19848; // ldrb r2,[r1],#1 -- r1 = curseur direct
    static final long ADDR_INLINE2 = 0x19874; // ldrb r0,[r2],#1 -- r2 = curseur direct
    static final long ADDR_INLINE3 = 0x19a2c; // ldrb r0,[r2],#1 -- r2 = curseur direct
    // g262bis : readRanged/readScaled elles-mêmes (PAS les primitives read4/readByte) -- code séquentiel
    // simple (contrairement à activateParticles, optimisé/vectorisé). r1 à l'ENTRÉE = pointeur de
    // DESTINATION dans la struct emitter (RangedNumericValue*/ScaledNumericValue*), passé tel quel par
    // ParticleEmitter::load -- donne l'offset struct de CHAQUE occurrence sans toucher au code de sim.
    static final long ADDR_READ_RANGED = 0x19fd0;
    static final long ADDR_READ_SCALED = 0x1a020;

    static final class Hit { final boolean isInt; final long cursor; final int val;
        Hit(boolean isInt, long cursor, int val) { this.isInt = isInt; this.cursor = cursor; this.val = val; } }
    static final class FieldDest { final String kind; final long destPtr;
        FieldDest(String kind, long destPtr) { this.kind = kind; this.destPtr = destPtr; } }

    public static void main(String[] args) throws Exception {
        String so = args[0];
        if ("verify".equals(args[1])) {
            verifyBatch(so, args[2], args.length > 3 ? args[3] : null);
            return;
        }
        if ("offsets".equals(args[1])) {
            offsetsBatch(so, args[2], args.length > 3 ? args[3] : null);
            return;
        }
        byte[] atlasBytes = Files.readAllBytes(new File(args[1]).toPath());
        byte[] npBytes = Files.readAllBytes(new File(args[2]).toPath());
        System.out.println("np file = " + args[2] + " (" + npBytes.length + " bytes)");

        AndroidEmulator emu = newEmulator();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        System.out.println("atlasH=" + atlasH);

        List<Hit> hits = new ArrayList<>();
        attachHooks(emu, mod, hits);
        DvmClass cPart2 = cPart;
        int effH = cPart2.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        String err = (String) cPart2.callStaticJniMethodObject(emu, "getLastParticleError()Ljava/lang/String;").getValue();
        System.out.println("effH=" + effH + " err=\"" + err + "\" hits=" + hits.size());
        long base = hits.isEmpty() ? 0 : hits.get(0).cursor - 2;

        System.out.println("--- séquence (offset_fichier type valeur) ---");
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            long off = h.cursor < 0 ? -1 : h.cursor - base;
            System.out.printf("%4d  off=%-6d %-4s val=%d (0x%x)%n", i, off, h.isInt ? "int" : "bool", h.val, h.val);
        }
        boolean eofExact = eofExactMultiEmitter(hits, base, npBytes);
        System.out.println("EOF-exact ? " + eofExact);
    }

    static AndroidEmulator newEmulator() {
        AndroidEmulatorBuilder b = AndroidEmulatorBuilder.for32Bit();
        b.setProcessName("dhoracle");
        AndroidEmulator emu = b.build();
        Memory memory = emu.getMemory();
        memory.setLibraryResolver(new AndroidResolver(23));
        return emu;
    }

    static void installBufferSvc(AndroidEmulator emu, VM vm) {
        // GetDirectBufferAddress/Capacity (JNI 230/231), absents d'unidbg -- cf. UnidbgVM.java.
        Pointer env = vm.getJNIEnv();
        UnidbgPointer table = (UnidbgPointer) env.getPointer(0);
        table.setPointer(230 * 4, emu.getSvcMemory().registerSvc(new ArmSvc() {
            @Override public long handle(Emulator<?> e) { DvmObject<?> o = vm.getObject(e.getContext().getIntArg(1));
                Object v = o == null ? null : o.getValue(); return v instanceof UnidbgPointer ? ((UnidbgPointer) v).peer : 0; } }));
        table.setPointer(231 * 4, emu.getSvcMemory().registerSvc(new ArmSvc() {
            @Override public long handle(Emulator<?> e) { return 0x100000; } }));
    }

    static void attachHooks(AndroidEmulator emu, Module mod, List<Hit> hits) {
        Debugger dbg = emu.attach();
        final long modBase = mod.base;
        BreakPointCallback cbShared = (e, addr) -> {
            boolean isInt = ((addr - modBase) & ~1L) == ADDR_READ4;
            UnidbgPointer cursorSlot = e.getContext().getPointerArg(0); // r0 = &data (local var pile)
            UnidbgPointer cursor = cursorSlot == null ? null : (UnidbgPointer) cursorSlot.getPointer(0);
            long cAddr = cursor == null ? -1 : cursor.peer;
            int val = 0;
            // Le hook tire AVANT le `rev` (byte-swap BE->hôte) de la fonction native -> les 4 octets sont
            // encore en ordre fichier (BIG-ENDIAN) ; getInt() les relit tels quels en LE hôte -> corriger.
            if (cursor != null) val = isInt ? Integer.reverseBytes(cursor.getInt(0)) : (cursor.getByte(0) & 0xff);
            hits.add(new Hit(isInt, cAddr, val));
            return true; // continue l'exécution
        };
        dbg.addBreakPoint(mod, ADDR_READ4, cbShared);
        dbg.addBreakPoint(mod, ADDR_READBYTE, cbShared);
        for (long off : new long[] { ADDR_INLINE1 }) attachInline(dbg, mod, hits, off, 1);
        for (long off : new long[] { ADDR_INLINE2, ADDR_INLINE3 }) attachInline(dbg, mod, hits, off, 2);
    }

    /** Sites inline (0x19848/0x19874/0x19a2c) : le registre `reg` tient DIRECTEMENT l'adresse du
     *  curseur (simple déréférence), pas une double indirection comme read4/readByte. */
    static void attachInline(Debugger dbg, Module mod, List<Hit> hits, long offset, int reg) {
        dbg.addBreakPoint(mod, offset, (e, addr) -> {
            UnidbgPointer cursor = e.getContext().getPointerArg(reg);
            long cAddr = cursor == null ? -1 : cursor.peer;
            int val = cursor == null ? 0 : (cursor.getByte(0) & 0xff);
            hits.add(new Hit(false, cAddr, val));
            return true;
        });
    }

    /** Hooke readRanged/readScaled : capture (kind, destPtr) dans l'ORDRE d'exécution. readScaled
     *  délègue ses 10 premiers octets à readRanged en lui passant le MÊME r1 (sous-objet "low") --
     *  filtré ici (destPtr identique au dernier "Scaled" vu -> c'est la délégation interne, pas un
     *  Ranged autonome). */
    static void attachDestHooks(AndroidEmulator emu, Module mod, List<FieldDest> dests) {
        Debugger dbg = emu.attach();
        final long[] lastScaledDest = { -1 };
        dbg.addBreakPoint(mod, ADDR_READ_SCALED, (e, addr) -> {
            UnidbgPointer dest = e.getContext().getPointerArg(1);
            long d = dest == null ? -1 : dest.peer;
            lastScaledDest[0] = d;
            dests.add(new FieldDest("Scaled", d));
            return true;
        });
        dbg.addBreakPoint(mod, ADDR_READ_RANGED, (e, addr) -> {
            UnidbgPointer dest = e.getContext().getPointerArg(1);
            long d = dest == null ? -1 : dest.peer;
            if (d == lastScaledDest[0]) { lastScaledDest[0] = -1; return true; } // délégation interne de readScaled -- ignorée
            dests.add(new FieldDest("Ranged", d));
            return true;
        });
    }

    /** Rejoue plusieurs `.np` réels et imprime, pour chacun, la liste des occurrences Ranged/Scaled avec
     *  leur offset struct RELATIF au premier (= delay, toujours 1er par construction, cf. séquence
     *  certifiée g261quater) -- permet de voir si l'ensemble d'offsets est stable entre fichiers (champ
     *  toujours au même endroit) et si des fichiers "riches" (tangentiel/centripète actifs) en révèlent
     *  PLUS que les simples (vérifie l'hypothèse g262bis : v3 ne sérialise peut-être pas tous les champs
     *  RangedNumericValue déclarés dans la classe actuelle). */
    static void offsetsBatch(String so, String npRoot, String limitArg) throws Exception {
        File root = new File(npRoot);
        List<File> files = new ArrayList<>();
        File[] anyAtlasHolder = new File[1];
        collectNp(root, files, anyAtlasHolder);
        java.util.Collections.sort(files);
        if (files.isEmpty()) { System.out.println("aucun .np trouvé sous: " + npRoot); return; }
        int limit = limitArg != null ? Integer.parseInt(limitArg) : files.size();

        AndroidEmulator emu = newEmulator();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        byte[] dummyAtlas = Files.readAllBytes(anyAtlasHolder[0].toPath());
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, dummyAtlas), 1);

        java.util.Map<String, Integer> rangedOffsetFreq = new java.util.TreeMap<>();
        java.util.Map<String, Integer> scaledOffsetFreq = new java.util.TreeMap<>();
        int maxRanged = 0, maxScaled = 0;
        for (int fi = 0; fi < Math.min(limit, files.size()); fi++) {
            File f = files.get(fi);
            byte[] npBytes = Files.readAllBytes(f.toPath());
            List<FieldDest> dests = new ArrayList<>();
            attachDestHooks(emu, mod, dests);
            try {
                cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
            } catch (Throwable t) { continue; }
            if (dests.isEmpty()) continue;
            long base = dests.get(0).destPtr; // 1er champ Ranged/Scaled = delay, toujours (séquence certifiée)
            int nR = 0, nS = 0;
            StringBuilder line = new StringBuilder();
            for (FieldDest d : dests) {
                long rel = d.destPtr - base;
                String key = d.kind + "@" + rel;
                line.append(key).append(' ');
                if ("Ranged".equals(d.kind)) { nR++; rangedOffsetFreq.merge(String.valueOf(rel), 1, Integer::sum); }
                else { nS++; scaledOffsetFreq.merge(String.valueOf(rel), 1, Integer::sum); }
            }
            maxRanged = Math.max(maxRanged, nR); maxScaled = Math.max(maxScaled, nS);
            System.out.printf("%-70s Ranged=%d Scaled=%d : %s%n", f.getName(), nR, nS, line);
        }
        System.out.println("=== offsets Ranged (relatif à delay) vus, avec fréquence ===");
        for (java.util.Map.Entry<String, Integer> e : rangedOffsetFreq.entrySet()) System.out.println("  " + e.getKey() + " : " + e.getValue() + "x");
        System.out.println("=== offsets Scaled (relatif à delay) vus, avec fréquence ===");
        for (java.util.Map.Entry<String, Integer> e : scaledOffsetFreq.entrySet()) System.out.println("  " + e.getKey() + " : " + e.getValue() + "x");
        System.out.println("max Ranged/fichier=" + maxRanged + " max Scaled/fichier=" + maxScaled);
    }

    /** Vérifie RÉCURSIVEMENT tout un dossier (et ses sous-dossiers) de `.np` réels : compte
     *  EOF-exact / total. AUCUN atlas correct requis (la structure se parse avant la résolution des
     *  régions -- vérifié : erreur "Region not found" survient APRÈS que tous nos hits soient capturés)
     *  -- un SEUL atlas bidon (le 1er `.atlas` trouvé sous la racine) sert pour TOUS les fichiers.
     *  npDir = racine (parcourue récursivement) ; limitArg optionnel = limite le nombre de fichiers. */
    static void verifyBatch(String so, String npRoot, String limitArg) throws Exception {
        File root = new File(npRoot);
        List<File> files = new ArrayList<>();
        File[] anyAtlasHolder = new File[1];
        collectNp(root, files, anyAtlasHolder);
        java.util.Collections.sort(files);
        if (files.isEmpty()) { System.out.println("aucun .np trouvé sous: " + npRoot); return; }
        int limit = limitArg != null ? Integer.parseInt(limitArg) : files.size();

        AndroidEmulator emu = newEmulator();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        byte[] dummyAtlas = Files.readAllBytes(anyAtlasHolder[0].toPath());
        System.out.println("dummyAtlas=" + anyAtlasHolder[0] + " | " + files.size() + " fichiers .np trouvés sous " + npRoot);
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, dummyAtlas), 1);

        int ok = 0, total = 0;
        List<String> fails = new ArrayList<>();
        for (int fi = 0; fi < Math.min(limit, files.size()); fi++) {
            File f = files.get(fi);
            byte[] npBytes = Files.readAllBytes(f.toPath());
            List<Hit> hits = new ArrayList<>();
            attachHooks(emu, mod, hits);
            try {
                cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
            } catch (Throwable t) {
                fails.add(f.getPath() + " : EXCEPTION " + t);
                total++; continue;
            }
            long base = hits.isEmpty() ? 0 : hits.get(0).cursor - 2;
            boolean ok1 = eofExactMultiEmitter(hits, base, npBytes);
            total++;
            if (ok1) ok++; else fails.add(f.getPath() + " (" + npBytes.length + " o, " + hits.size() + " hits)");
        }
        System.out.println("=== RÉSULTAT : " + ok + "/" + total + " EOF-exact ===");
        for (String s : fails) System.out.println("  FAIL: " + s);
    }

    static void collectNp(File dir, List<File> npOut, File[] anyAtlasHolder) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { collectNp(k, npOut, anyAtlasHolder); continue; }
            String n = k.getName();
            if (n.endsWith(".np")) npOut.add(k);
            else if (n.endsWith(".atlas") && anyAtlasHolder[0] == null) anyAtlasHolder[0] = k;
        }
    }

    /**
     * Vérification EOF-exact complète, gère les fichiers multi-emitters (plusieurs trailers).
     * `poolSize`/`tagLen` sont eux-mêmes lus via `read4` (donc déjà présents comme les 2 DERNIERS hits
     * contigus avant le trou) -- PAS relus depuis le fichier à `pos` (ils sont AVANT `pos`, pas dessus) :
     * seuls le pool de floats et le nom de région (tag) qui les suivent sont copiés en bloc (memcpy),
     * invisibles aux 2 primitives -- c'est CE bloc qui forme le "trou" entre 2 emitters.
     */
    static boolean eofExactMultiEmitter(List<Hit> hits, long base, byte[] npBytes) {
        long pos = 2;
        int hi = 0;
        while (true) {
            int lastInt1 = -1, lastInt2 = -1; // avant-dernier et dernier hit "int" contigu = poolSize, tagLen
            while (hi < hits.size()) {
                Hit h = hits.get(hi);
                long off = h.cursor - base;
                if (off != pos) break;
                if (h.isInt) { lastInt1 = lastInt2; lastInt2 = h.val; }
                pos += h.isInt ? 4 : 1;
                hi++;
            }
            if (hi >= hits.size() && pos == npBytes.length) return true; // fin nette, aucun trailer restant
            int poolSize = lastInt1, tagLen = lastInt2;
            if (poolSize < 0 || poolSize > 100000 || tagLen < 0 || tagLen > 500) return false;
            long trailerEnd = pos + poolSize * 4L + tagLen; // pos inclut déjà les 8o de poolSize+tagLen
            if (trailerEnd > npBytes.length) return false;
            pos = trailerEnd;
            if (hi >= hits.size()) return pos == npBytes.length;
            if (hits.get(hi).cursor - base != pos) return false; // emitter suivant doit reprendre pile ici
        }
    }

    static int be32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }
}
