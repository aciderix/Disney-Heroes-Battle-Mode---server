import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.ArmSvc;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
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
        if ("trace".equals(args[1])) {
            traceRun(so, args[2], args[3]);
            return;
        }
        if ("map".equals(args[1])) {
            mapFields(so, args[2], args[3]);
            return;
        }
        if ("patchtest".equals(args[1])) {
            // args: patchtest <so> <np> <atlas> <fileOffsetActiveByte> <0|1>
            patchTest(so, args[2], args[3], Integer.parseInt(args[4]), Integer.parseInt(args[5]));
            return;
        }
        if ("vertextest".equals(args[1])) {
            // args: vertextest <so> <np> <atlas> <fileOffsetActiveByte> <0|1>
            vertexTest(so, args[2], args[3], Integer.parseInt(args[4]), Integer.parseInt(args[5]));
            return;
        }
        if ("batchtest".equals(args[1])) {
            // args: batchtest <so> <np> <atlas> <off1,off2,off3,...>
            String[] parts = args[4].split(",");
            int[] offs = new int[parts.length];
            for (int i = 0; i < parts.length; i++) offs[i] = Integer.parseInt(parts[i].trim());
            batchVertexTest(so, args[2], args[3], offs);
            return;
        }
        if ("batchval".equals(args[1])) {
            // args: batchval <so> <np> <atlas> <off1,off2,...> <testVal>
            String[] parts = args[4].split(",");
            int[] offs = new int[parts.length];
            for (int i = 0; i < parts.length; i++) offs[i] = Integer.parseInt(parts[i].trim());
            batchValueTest(so, args[2], args[3], offs, Float.parseFloat(args[5]));
            return;
        }
        if ("golden".equals(args[1])) {
            // args: golden <so> <np> <atlas> <out.golden> [nframes] [dt_ms]
            int nf = args.length > 5 ? Integer.parseInt(args[5]) : 30;
            float dtMs = args.length > 6 ? Float.parseFloat(args[6]) : 100f;
            dumpGolden(so, args[2], args[3], args[4], nf, dtMs);
            return;
        }
        if ("rngprobe".equals(args[1])) {
            // args: rngprobe <so> <np> <atlas>
            rngProbe(so, args[2], args[3]);
            return;
        }
        if ("activorder".equals(args[1])) {
            // args: activorder <so> <np> <atlas>
            activOrder(so, args[2], args[3]);
            return;
        }
        if ("scaledprobe".equals(args[1])) {
            // args: scaledprobe <so> <np> <atlas>  -- hooke getScaled(0x17309) : r1=base, r2=diff, [sp]=percent
            scaledProbe(so, args[2], args[3]);
            return;
        }
        if ("drawxhook".equals(args[1])) {
            // args: drawxhook <so> <np> <atlas>  -- WriteHook : capture les écritures dont la valeur float
            // est proche de -251 (drawX cible em3) + le PC de l'instruction -> localise le calcul de position.
            drawxHook(so, args[2], args[3]);
            return;
        }
        if ("goldenall".equals(args[1])) {
            // args: goldenall <so> <assetsRoot> <outDir> [nframes] [dt_ms]  -- 1 golden par .np (récursif)
            int nf = args.length > 4 ? Integer.parseInt(args[4]) : 30;
            float dtMs = args.length > 5 ? Float.parseFloat(args[5]) : 100f;
            dumpGoldenAll(so, args[2], args[3], nf, dtMs);
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

    // g262quater : symboles réels de la simulation (trouvés via nm/pyelftools sur libspine-native.so,
    // JOURNAL g262) -- adresses relatives au module (mod.base ajouté au moment du hook).
    static final long SYM_ACTIVATE_PARTICLES = 0x17331, SZ_ACTIVATE_PARTICLES = 2956;
    static final long SYM_UPDATE_PARTICLES = 0x16589, SZ_UPDATE_PARTICLES = 3428; // per-particle physics (le vrai)
    static final long SYM_UPDATE = 0x1641d, SZ_UPDATE = 360;

    /**
     * TRACE D'EXÉCUTION (g262quater) -- au lieu de désassembler STATIQUEMENT (linéaire, se fait piéger
     * par les pools de constantes flottantes embarquées dans le code -> décodage garbage après ~950 o,
     * cf. JOURNAL g262ter), on hooke l'EXÉCUTION RÉELLE (`Backend.hook_add_new(CodeHook,...)`, un hook
     * par INSTRUCTION dans la plage donnée) pendant un VRAI appel Effect_create+start+update. Chaque PC
     * capturé est par construction une VRAIE instruction (jamais une donnée -- une donnée n'est jamais
     * exécutée) : on n'a plus besoin de deviner où s'arrêter, la trace elle-même EST la réponse. Les
     * adresses collectées sont ensuite redonnées une par une à `disasm.py` (capstone, robuste sur un
     * point précis) pour un désassemblage propre, dans l'ordre d'exécution réel.
     */
    static void traceRun(String so, String npPath, String atlasPath) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
        List<Long> sorted = runAndTrace(so, npBytes, atlasBytes);
        System.out.println("trace: " + sorted.size() + " adresses uniques exécutées (activateParticles+updateParticles+update)");
        try (java.io.PrintWriter pw = new java.io.PrintWriter("trace_addrs.txt")) {
            for (long a : sorted) pw.printf("0x%x%n", a);
        }
        System.out.println("écrit trace_addrs.txt (" + sorted.size() + " adresses, triées)");
    }

    /** Rejoue Effect_create+start+20×update et renvoie les offsets (relatifs au module) RÉELLEMENT
     *  exécutés dans activateParticles/updateParticles/update, triés. Réutilisé par `trace` et
     *  `patchtest` (même protocole, pour que les deux runs soient comparables). */
    static List<Long> runAndTrace(String so, byte[] npBytes, byte[] atlasBytes) throws Exception {
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
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);

        Backend backend = emu.getBackend();
        final java.util.LinkedHashSet<Long> trace = new java.util.LinkedHashSet<>();
        final long base = mod.base;
        CodeHook hook = new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) { trace.add(address - base); }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        };
        long a1 = base + (SYM_ACTIVATE_PARTICLES & ~1L), a2 = base + (SYM_UPDATE_PARTICLES & ~1L), a3 = base + (SYM_UPDATE & ~1L);
        backend.hook_add_new(hook, a1, a1 + SZ_ACTIVATE_PARTICLES, null);
        backend.hook_add_new(hook, a2, a2 + SZ_UPDATE_PARTICLES, null);
        backend.hook_add_new(hook, a3, a3 + SZ_UPDATE, null);

        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);
        for (int i = 0; i < 20; i++) {
            cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.1f));
        }
        List<Long> sorted = new ArrayList<>(trace);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /**
     * CORRÉLATION (g262quinquies) : rejoue Effect_create en hookant EN MÊME TEMPS readRanged/readScaled
     * (destPtr = offset struct) ET read4/readByte/inline (position fichier), dans un flux d'ÉVÉNEMENTS
     * UNIQUE (ordre d'exécution réel préservé) -- pour chaque occurrence Ranged/Scaled, le TOUT PREMIER
     * hit qui suit son entrée readRanged/readScaled est par construction son octet `active` (vérifié :
     * `active` est lu via le MÊME helper partagé 0x1a0c4 que readByte, à travers un fin wrapper 0x1a8d4 --
     * donc déjà capturé par le hook readByte existant). Donne directement, pour CHAQUE champ, la paire
     * (offset struct, offset fichier de son octet `active`) -- utile pour cibler un `patchtest` précis.
     */
    static void mapFields(String so, String npPath, String atlasPath) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
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

        // flux d'événements unique : chaque callback ajoute un Object (Hit ou FieldDest) à la MÊME liste,
        // dans l'ordre réel d'exécution (tous les hooks tournent sur le même emulator/thread).
        List<Object> events = new ArrayList<>();
        List<Hit> hitsView = new ArrayList<Hit>() { public boolean add(Hit h) { events.add(h); return true; } };
        attachHooks(emu, mod, hitsView);
        Debugger dbg = emu.attach();
        final long[] lastScaledDest = { -1 };
        dbg.addBreakPoint(mod, ADDR_READ_SCALED, (e, addr) -> {
            UnidbgPointer dest = e.getContext().getPointerArg(1);
            long d = dest == null ? -1 : dest.peer;
            lastScaledDest[0] = d;
            events.add(new FieldDest("Scaled", d));
            return true;
        });
        dbg.addBreakPoint(mod, ADDR_READ_RANGED, (e, addr) -> {
            UnidbgPointer dest = e.getContext().getPointerArg(1);
            long d = dest == null ? -1 : dest.peer;
            if (d == lastScaledDest[0]) { lastScaledDest[0] = -1; return true; }
            events.add(new FieldDest("Ranged", d));
            return true;
        });

        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        System.out.println("effH=" + effH + " events=" + events.size());

        long fileBase = -1;
        for (Object o : events) if (o instanceof Hit) { fileBase = ((Hit) o).cursor - 2; break; }
        long structBase = -1;
        for (Object o : events) if (o instanceof FieldDest) { structBase = ((FieldDest) o).destPtr; break; }

        for (int i = 0; i < events.size(); i++) {
            Object o = events.get(i);
            if (!(o instanceof FieldDest)) continue;
            FieldDest fd = (FieldDest) o;
            // le tout prochain Hit dans le flux = son octet `active`
            long fileOff = -1;
            for (int j = i + 1; j < events.size(); j++) {
                if (events.get(j) instanceof Hit) { fileOff = ((Hit) events.get(j)).cursor - fileBase; break; }
            }
            System.out.printf("%-6s structOff=%-6d(0x%x abs=0x%x)  fileOff(active)=%d%n",
                fd.kind, fd.destPtr - structBase, fd.destPtr - structBase, fd.destPtr, fileOff);
        }
    }

    /**
     * TEST EMPIRIQUE (g262quinquies) : patche l'octet `active` d'UN champ (offset fichier donné, trouvé
     * via `map`) à 0 ou 1 dans une COPIE du fichier, rejoue le MÊME protocole (`runAndTrace`) sur
     * l'ORIGINAL et sur la version PATCHÉE, et affiche la DIFFÉRENCE d'adresses exécutées -- montre
     * EXACTEMENT quel bloc de code (donc quel offset struct / quelle fonction newLowValue/newHighValue)
     * s'active/se désactive avec ce champ. Confirmation empirique, pas une lecture d'assembleur devinée.
     */
    static void patchTest(String so, String npPath, String atlasPath, int fileOffsetActive, int newVal) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] orig = Files.readAllBytes(new File(npPath).toPath());
        byte[] patched = orig.clone();
        System.out.println("octet @ " + fileOffsetActive + " : " + orig[fileOffsetActive] + " -> " + newVal);
        patched[fileOffsetActive] = (byte) newVal;

        List<Long> t1 = runAndTrace(so, orig, atlasBytes);
        List<Long> t2 = runAndTrace(so, patched, atlasBytes);
        java.util.Set<Long> s1 = new java.util.TreeSet<>(t1), s2 = new java.util.TreeSet<>(t2);
        java.util.Set<Long> onlyOrig = new java.util.TreeSet<>(s1); onlyOrig.removeAll(s2);
        java.util.Set<Long> onlyPatched = new java.util.TreeSet<>(s2); onlyPatched.removeAll(s1);
        System.out.println("original: " + s1.size() + " adresses, patché: " + s2.size() + " adresses");
        System.out.println("--- présentes SEULEMENT dans l'ORIGINAL (disparues avec le patch) ---");
        for (long a : onlyOrig) System.out.printf("  0x%x%n", a);
        System.out.println("--- présentes SEULEMENT dans le PATCHÉ (apparues avec le patch) ---");
        for (long a : onlyPatched) System.out.printf("  0x%x%n", a);
    }

    /**
     * TEST EMPIRIQUE PAR LA SORTIE RENDUE (g262quinquies) : certains champs sont traités SANS
     * branchement conditionnel (pas de `ifeq` sur `active` dans le bytecode Java -- ex. life/angle/
     * sizeX/sizeY, cf. JOURNAL g262) -> le patch de leur octet `active` ne fait AUCUNE différence de
     * trace d'ADRESSES (le code tourne pareil), seule la VALEUR change. Signal plus fort : comparer les
     * SOMMETS RÉELLEMENT RENDUS (`Effect_getVertices`, comme le fait le jeu) image par image entre
     * l'original et le patché -- si les positions divergent (ou pas), ça révèle un effet MESURABLE,
     * pas déduit d'un désassemblage. Dump les 2 premiers sommets (x,y) sur N frames pour les 2 versions.
     */
    static void vertexTest(String so, String npPath, String atlasPath, int fileOffsetActive, int newVal) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] orig = Files.readAllBytes(new File(npPath).toPath());
        byte[] patched = orig.clone();
        System.out.println("octet @ " + fileOffsetActive + " : " + orig[fileOffsetActive] + " -> " + newVal);
        patched[fileOffsetActive] = (byte) newVal;

        System.out.println("=== ORIGINAL ===");
        runAndDumpVertices(so, orig, atlasBytes, true);
        System.out.println("=== PATCHÉ ===");
        runAndDumpVertices(so, patched, atlasBytes, true);
    }

    /**
     * RNG PROBE (g263) : vérifie si l'inline LCG d'activateParticles (0x173c4, str newseed) et pr_rand
     * (0x1619e) écrivent le MÊME `seed` global (un seul flux RNG partagé) ou deux distincts. Dumpe aussi
     * la graine INITIALE + les premières valeurs. Décisif pour répliquer la RNG en C : 1 flux ou 2 ?
     */
    static void rngProbe(String so, String npPath, String atlasPath) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
        AndroidEmulator emu = newEmulator();
        Memory memory = emu.getMemory();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);

        final long SEED_ADDR = 0x40049004L;   // trouvé par rngprobe précédent (déterministe)
        System.out.printf("mod.base=0x%x seed_offset=0x%x%n", mod.base, SEED_ADDR - mod.base);
        Backend backend = emu.getBackend();
        final java.util.List<Integer> seeds = new ArrayList<>();
        backend.hook_add_new(new com.github.unidbg.arm.backend.WriteHook() {
            @Override public void hook(Backend b, long address, int size, long value, Object user) {
                if (address == SEED_ADDR) seeds.add((int) value);
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, SEED_ADDR, SEED_ADDR + 4, null);

        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);
        cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.1f));
        cPart.callStaticJniMethodInt(emu, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I",
            effH, vm.resolveClass("java/nio/FloatBuffer").newObject(memory.malloc(8192*6*4, false).getPointer()),
            vm.resolveClass("java/nio/ShortBuffer").newObject(memory.malloc(8192*2, false).getPointer()));
        System.out.println("total seed advances (draws) frame0 = " + seeds.size());
        int prev = 0; boolean allMul = true;
        for (int i = 0; i < Math.min(16, seeds.size()); i++) {
            int s = seeds.get(i);
            String rec = (i > 0) ? (((prev * 16807) == s) ? "=prev*16807" : "!=prev*16807") : "";
            System.out.printf("  [%2d] newseed=0x%08x f=%.6f %s%n", i, s, lcgFloat(s), rec);
            if (i > 0 && (prev * 16807) != s) allMul = false;
            prev = s;
        }
        System.out.println("récurrence pure *16807 entre draws consécutifs ? " + allMul);
    }
    static float lcgFloat(int newseed) {
        int bits = (newseed & 0x7fffff) | 0x3f800000;
        return Float.intBitsToFloat(bits) - 1.0f;
    }

    /** Hooke getScaled(0x17309) : r1=base(low), r2=diff, r3=&value, [sp]=percent. Dump les valeurs
     *  EXACTES de vitesse/etc. utilisées à l'exécution -> comparer à ma sim. */
    static void scaledProbe(String so, String npPath, String atlasPath) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
        AndroidEmulator emu = newEmulator();
        Memory memory = emu.getMemory();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        Debugger dbg = emu.attach();
        final int[] cnt = {0};
        final int[] upc = {0};
        // updateParticles entry (0x16589) : r0=emitter. Dump des 6 premiers appels (émetteur + delta +
        // struct particule[0] complet 0x00..0x80) pour localiser la composante position manquante (§4).
        dbg.addBreakPoint(mod, 0x16589, (e, addr) -> {
            if (upc[0] >= 6) return true;
            com.github.unidbg.arm.context.Arm32RegisterContext c = (com.github.unidbg.arm.context.Arm32RegisterContext) e.getContext();
            long r0 = c.getR0Int() & 0xffffffffL;
            long partArr = UnidbgPointer.pointer(emu, r0).getInt(0x8bc) & 0xffffffffL;
            System.out.printf("[updateParticles #%d] emitter=0x%x delta=%.3f partArr=0x%x%n",
                upc[0], r0, Float.intBitsToFloat(c.getR1Int()), partArr);
            if (partArr != 0) {
                UnidbgPointer pp = UnidbgPointer.pointer(emu, partArr);
                System.out.print("   part[0] non-nuls : ");
                for (int o = 0; o < 0x80; o += 4) { float v = Float.intBitsToFloat(pp.getInt(o));
                    if (Math.abs(v) > 0.001 && Math.abs(v) < 1e7) System.out.printf("[0x%x]=%.3f ", o, v); }
                System.out.println();
            }
            upc[0]++;
            return true;
        });
        dbg.addBreakPoint(mod, 0x17309, (e, addr) -> {
            if (cnt[0] < 20) {
                com.github.unidbg.arm.context.Arm32RegisterContext c = (com.github.unidbg.arm.context.Arm32RegisterContext) e.getContext();
                float base = Float.intBitsToFloat(c.getR1Int());
                float diff = Float.intBitsToFloat(c.getR2Int());
                long valPtr = c.getR3Int() & 0xffffffffL;
                long off = valPtr - (mod.base); // offset absolu module (pas emitter, indicatif)
                UnidbgPointer sp = c.getStackPointer();
                float percent = Float.intBitsToFloat(sp.getInt(0));
                System.out.printf("[getScaled %2d] base=%.2f diff=%.2f percent=%.3f valPtrModOff=0x%x",
                    cnt[0], base, diff, percent, off);
                cnt[0]++;
            }
            return true;
        });
        // retour de getScaled (0x1732c pop) : r0 = résultat = base + diff*getScale(percent)
        dbg.addBreakPoint(mod, 0x1732c, (e, addr) -> {
            if (cnt[0] <= 20) {
                com.github.unidbg.arm.context.Arm32RegisterContext c = (com.github.unidbg.arm.context.Arm32RegisterContext) e.getContext();
                System.out.printf(" => result=%.2f%n", Float.intBitsToFloat(c.getR0Int()));
            }
            return true;
        });
        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);
        // Suivi de la particule[0] de l'émetteur #3 (module off 0x21cb0c) sur 6 frames : si velocityZ(0x188)
        // pilote la position, drawY(0x44) doit croître vers la valeur golden. drawX(0x40) = velocity.
        long[] ems = { mod.base + 0x21cb0c, mod.base + 0x21dd14 };  // émetteur #3 et #5
        for (int frame = 0; frame < 3; frame++) {
            cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.1f));
            for (int ei = 0; ei < ems.length; ei++) {
                UnidbgPointer emp2 = UnidbgPointer.pointer(emu, ems[ei]);
                long pa = emp2.getInt(0x8bc) & 0xffffffffL;
                if (pa == 0) continue;
                UnidbgPointer p0 = UnidbgPointer.pointer(emu, pa);
                System.out.printf("frame %d em%d part[0] : drawX=%.2f drawY=%.2f | disp0x34=%.2f 0x38=%.2f | vel=%.2f velZ=%.2f%n",
                    frame, ei==0?3:5,
                    Float.intBitsToFloat(p0.getInt(0x40)), Float.intBitsToFloat(p0.getInt(0x44)),
                    Float.intBitsToFloat(p0.getInt(0x34)), Float.intBitsToFloat(p0.getInt(0x38)),
                    Float.intBitsToFloat(p0.getInt(0xc)), Float.intBitsToFloat(p0.getInt(0x28)));
            }
        }
        // scan du struct émetteur #3 : cherche -240 et 100 (source du spawn) parmi les 0x904 octets
        UnidbgPointer em3p = UnidbgPointer.pointer(emu, ems[0]);
        System.out.print("em3 struct : offsets contenant ~-240 ou ~100 : ");
        for (int o = 0; o < 0x904; o += 4) { float v = Float.intBitsToFloat(em3p.getInt(o));
            if (Math.abs(v-(-240))<2 || Math.abs(v-100)<0.5 || Math.abs(v-(-251))<2) System.out.printf("[0x%x]=%.2f ", o, v); }
        System.out.println();
        UnidbgPointer emp = UnidbgPointer.pointer(emu, ems[0]);
        UnidbgPointer pp = UnidbgPointer.pointer(emu, emp.getInt(0x8bc) & 0xffffffffL);
        System.out.print("particle0 floats @0..0x100 (fin) : ");
        for (int o = 0; o < 0x100; o += 4) { float v = Float.intBitsToFloat(pp.getInt(o));
            if (Math.abs(v) > 0.01 && Math.abs(v) < 1e6) System.out.printf("[0x%x]=%.2f ", o, v); }
        System.out.println();
    }

    /** WriteHook : loggue chaque écriture mémoire dont la valeur (interprétée float) est proche de la cible
     *  drawX em3 (~-251 / -240) + le PC de l'instruction (reg_read) -> localise le calcul de position. */
    static void drawxHook(String so, String npPath, String atlasPath) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
        AndroidEmulator emu = newEmulator();
        Memory memory = emu.getMemory();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        final Backend backend = emu.getBackend();
        final long modbase = mod.base;
        final int[] n = {0};
        backend.hook_add_new(new com.github.unidbg.arm.backend.WriteHook() {
            @Override public void hook(Backend b, long address, int size, long value, Object user) {
                if (size != 4 || n[0] >= 40) return;
                float f = Float.intBitsToFloat((int) value);
                if (address == 0x4022f080L && f > -260 && f < -210) {   // drawX em3 : capte -220 ET -251
                    long pc = b.reg_read(unicorn.ArmConst.UC_ARM_REG_PC).longValue();
                    System.out.printf("WRITE @0x%x = %.3f  (PC=0x%x modoff=0x%x)%n",
                        address, f, pc, pc - modbase);
                    n[0]++;
                }
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, modbase, modbase + 0x400000, null);   // large fenêtre heap+module
        // RNG : compteur de tirages (WriteHook sur le seed global) pour corréler tirage->drawY au spawn.
        final long SEED = 0x40049004L;
        final int[] drawIdx = {0};
        backend.hook_add_new(new com.github.unidbg.arm.backend.WriteHook() {
            @Override public void hook(Backend b, long address, int size, long value, Object user) {
                if (address != SEED) return;
                drawIdx[0]++;
                if (drawIdx[0] >= 305 && drawIdx[0] <= 343) {   // fenêtre des 3 émetteurs identiques
                    int s = (int) value;
                    float f = Float.intBitsToFloat(((s & 0x7fffff) | 0x3f800000)) - 1.0f;
                    System.out.printf("  draw[%d]=%.4f%n", drawIdx[0], f);
                }
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, SEED, SEED + 4, null);
        // Carte SPAWN par émetteur : au store drawX (0x17c54, vstr s2,[r0]), lire r8=émetteur + drawX=mem[r0].
        // r0 est le ptr drawPos élément ; drawX y est écrit à cet instant. On lit juste après (0x17c58).
        final int[] sc2 = {0};
        backend.hook_add_new(new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) {
                if (sc2[0] >= 40) return;
                long r8 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R8).longValue() & 0xffffffffL;
                long r0 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R0).longValue() & 0xffffffffL;
                UnidbgPointer pd = UnidbgPointer.pointer(emu, r0);
                float dx = Float.intBitsToFloat(pd.getInt(0));   // drawX @0x17c54, drawY @0x17c58 (déjà écrits)
                float dy = Float.intBitsToFloat(pd.getInt(4));
                UnidbgPointer ep = UnidbgPointer.pointer(emu, r8);
                float x0 = Float.intBitsToFloat(ep.getInt(0x98 + 0x1c));   // valeur constante lowMin
                float y0 = Float.intBitsToFloat(ep.getInt(0xc0 + 0x1c));
                float vel = Float.intBitsToFloat(ep.getInt(0x110 + 0x1c));
                float ang = Float.intBitsToFloat(ep.getInt(0x160 + 0x1c));
                float f188 = Float.intBitsToFloat(ep.getInt(0x188 + 0x1c));
                System.out.printf("[SPAWN] mod+0x%x drawIdx=%d -> draw=(%.1f,%.1f) | 0x98=%.0f 0xc0=%.0f vel=%.0f ang=%.0f 0x188=%.0f%n",
                    r8 - modbase, drawIdx[0], dx, dy, x0, y0, vel, ang, f188);
                sc2[0]++;
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, modbase + 0x17c5c, modbase + 0x17c5e, null);
        System.out.println("=== Effect_start ===");
        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);
        // Adresse de drawX de la particule em3 (déterministe) : émetteur module off 0x21cb0c, particles @+0x8bc, +0x40.
        UnidbgPointer em3 = UnidbgPointer.pointer(emu, modbase + 0x21cb0c);
        final long drawXAddr = (em3.getInt(0x8bc) & 0xffffffffL) + 0x40;
        System.out.printf("drawXAddr em3 = 0x%x (valeur avant update = %.3f)%n",
            drawXAddr, Float.intBitsToFloat(UnidbgPointer.pointer(emu, drawXAddr).getInt(0)));
        // combine final (0x17118) : r5 -> posAccum[idx] (x@0, y@4) ; r1 -> offset array (x@-8, y@-4 après adds r1,#8).
        final long comb = modbase + 0x17118;
        final int[] cc = {0};
        backend.hook_add_new(new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) {
                long r5 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R5).longValue() & 0xffffffffL;
                long r1 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R1).longValue() & 0xffffffffL;
                long r2 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R2).longValue() & 0xffffffffL;
                if ((r2 - 4) != drawXAddr) return;   // seulement em3
                UnidbgPointer pp5 = UnidbgPointer.pointer(emu, r5);
                UnidbgPointer pp1 = UnidbgPointer.pointer(emu, r1);
                float px = Float.intBitsToFloat(pp5.getInt(0)), py = Float.intBitsToFloat(pp5.getInt(4));
                long e3 = modbase + 0x21cb0c;
                System.out.printf("[0x17118 EM3 #%d] posAccumAddr=0x%x (em3+0x%x, idx=%d) offsetArrAddr=0x%x | posAccum=(%.3f,%.3f) offset=(%.3f,%.3f)%n",
                    cc[0], r5, r5-e3, (r5-(e3+0x894))/8, r1-12, px, py,
                    Float.intBitsToFloat(pp1.getInt(-12)), Float.intBitsToFloat(pp1.getInt(-8)));
                cc[0]++;
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, comb, comb + 2, null);
        // getScaled pendant l'update, filtré émetteur em3 (champ ptr r3 dans [em3base, em3base+0x904]) :
        // donne la recette exacte (champ -> valeur scaled) qui compose posAccum/offset.
        final long em3base = modbase + 0x21cb0c;
        final boolean[] pending = {false};
        backend.hook_add_new(new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) {
                long r3 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R3).longValue() & 0xffffffffL;
                if (r3 < em3base || r3 >= em3base + 0x904) return;
                float base = Float.intBitsToFloat(b.reg_read(unicorn.ArmConst.UC_ARM_REG_R1).intValue());
                float diff = Float.intBitsToFloat(b.reg_read(unicorn.ArmConst.UC_ARM_REG_R2).intValue());
                long sp = b.reg_read(unicorn.ArmConst.UC_ARM_REG_SP).longValue() & 0xffffffffL;
                float pct = Float.intBitsToFloat(UnidbgPointer.pointer(emu, sp).getInt(0));
                System.out.printf("[getScaled UPD] champ@em3+0x%x base=%.3f diff=%.3f pct=%.4f",
                    r3 - em3base, base, diff, pct);
                pending[0] = true;
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, modbase + 0x17309, modbase + 0x1730b, null);
        backend.hook_add_new(new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) {
                if (!pending[0]) return;
                float r0 = Float.intBitsToFloat(b.reg_read(unicorn.ArmConst.UC_ARM_REG_R0).intValue());
                System.out.printf(" => %.4f%n", r0);
                pending[0] = false;
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, modbase + 0x1732c, modbase + 0x1732e, null);
        // Surveille posAccum.x (em3base+0x894) et offset via single-step : voir la construction de -220.
        final long posAccAddr = em3base + 0x894;
        final long uStart2 = modbase + (SYM_UPDATE_PARTICLES & ~1L);
        final float[] lastPA = { Float.intBitsToFloat(UnidbgPointer.pointer(emu, posAccAddr).getInt(0)) };
        final long[] lpc = {0};
        backend.hook_add_new(new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) {
                float cur = Float.intBitsToFloat(UnidbgPointer.pointer(emu, posAccAddr).getInt(0));
                if (cur != lastPA[0]) {
                    System.out.printf("posAccum.x %.3f -> %.3f  (instr préc modoff=0x%x)%n", lastPA[0], cur, lpc[0]-modbase);
                    lastPA[0] = cur;
                }
                lpc[0] = address;
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, uStart2, uStart2 + SZ_UPDATE_PARTICLES, null);
        // getScale 0x16368 : r0 = champ (Scalar). Log l'offset-émetteur des champs sommés au spawn d'em3.
        final long e3b = modbase + 0x21cb0c;
        backend.hook_add_new(new CodeHook() {
            @Override public void hook(Backend b, long address, int size, Object user) {
                long r0 = b.reg_read(unicorn.ArmConst.UC_ARM_REG_R0).longValue() & 0xffffffffL;
                if (r0 >= e3b && r0 < e3b + 0x904)
                    System.out.printf("[getScale 0x16368] champ@em3+0x%x%n", r0 - e3b);
            }
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook u) {}
            @Override public void detach() {}
        }, modbase + 0x16368, modbase + 0x1636a, null);
        // Dump des champs-clés (valeur constante @+0x1c) pour les 2 émetteurs qui spawnent hors origine.
        for (long eb : new long[]{ modbase + 0x220124, modbase + 0x220a28, modbase + 0x22132c }) {
            UnidbgPointer ep = UnidbgPointer.pointer(emu, eb);
            System.out.printf("emitter mod+0x%x hdr[0x0..0x48]:", eb - modbase);
            for (int off = 0; off <= 0x48; off += 4)
                System.out.printf(" %.1f", Float.intBitsToFloat(ep.getInt(off)));
            System.out.println();
        }
        System.out.println("=== Effect_update frame0 ===");
        cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.1f));
    }

    /**
     * ACTIV ORDER (g264) : capture l'ORDRE d'exécution des champs ScaledNumericValue traités par
     * `activateParticles` (via le helper 0x17ebc, r1 = pointeur champ) pendant un vrai spawn. Corrélé aux
     * offsets struct (via l'emitter base capturé à l'entrée 0x17331, r0), donne l'ordre JAVA de lecture
     * des champs -> avec map (structOff -> slot parseur), le MAPPING sémantique complet. Le 1er émetteur
     * qui spawn suffit (tous ont le même layout). velocity=0x110 sert d'ancre de validation.
     */
    static void activOrder(String so, String npPath, String atlasPath) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
        AndroidEmulator emu = newEmulator();
        Memory memory = emu.getMemory();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        Module mod = dm.getModule();
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);

        Debugger dbg = emu.attach();
        final long[] emitterBase = {0};
        final java.util.List<long[]> order = new ArrayList<>();  // {emitterBase, offset}
        // entrée activateParticles (0x17331) : r0 = emitter base -> mise à jour à CHAQUE entrée
        dbg.addBreakPoint(mod, 0x17331, (e, addr) -> {
            com.github.unidbg.arm.context.Arm32RegisterContext c = (com.github.unidbg.arm.context.Arm32RegisterContext) e.getContext();
            emitterBase[0] = c.getR0Int() & 0xffffffffL;
            return true;
        });
        dbg.addBreakPoint(mod, 0x17ebc, (e, addr) -> {
            com.github.unidbg.arm.context.Arm32RegisterContext c = (com.github.unidbg.arm.context.Arm32RegisterContext) e.getContext();
            long ptr = c.getR1Int() & 0xffffffffL;
            if (emitterBase[0] != 0 && order.size() < 200) order.add(new long[]{emitterBase[0], ptr - emitterBase[0]});
            return true;
        });
        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);
        cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.1f));
        // regroupe par emitter base, montre l'ordre des offsets par émetteur (UNION = ordre Java complet)
        java.util.LinkedHashMap<Long, java.util.List<Long>> byEm = new java.util.LinkedHashMap<>();
        for (long[] o : order) byEm.computeIfAbsent(o[0], k -> new ArrayList<>()).add(o[1]);
        int ei = 0;
        for (java.util.Map.Entry<Long, java.util.List<Long>> en : byEm.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (long off : en.getValue()) sb.append(String.format("0x%x ", off));
            System.out.printf("émetteur#%d (base 0x%x) : %s%n", ei++, en.getKey(), sb);
        }
    }

    static final int NFRAMES = 15;
    static final class Traj { float[] x = new float[NFRAMES], y = new float[NFRAMES]; int[] verts = new int[NFRAMES]; }

    /**
     * GOLDEN (g263) : dumpe la sortie RENDUE de l'oracle (le VRAI binaire ARM via unidbg) pour un `.np`,
     * N frames, dans un fichier binaire = RÉFÉRENCE ABSOLUE contre laquelle la sim C sera certifiée
     * (harnais différentiel `np_certify`, comme CompareBackend pour spine). Format binaire little-endian :
     *   magic "NPGL" (4o) | int32 version=1 | int32 nframes | float32 dt_sec |
     *   [par frame]: int32 vertCount | float32[vertCount*6] (x,y,light,dark,u,v)
     * (les draw calls ne sont pas dumpés ici : la fidélité VISUELLE = les sommets ; le regroupement en
     * draw calls est purement une optim de rendu, testable séparément si besoin.)
     */
    static void dumpGolden(String so, String npPath, String atlasPath, String outPath, int nframes, float dtMs) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] npBytes = Files.readAllBytes(new File(npPath).toPath());
        int total = writeGolden(so, npBytes, atlasBytes, outPath, nframes, dtMs);
        System.out.println("golden écrit : " + outPath + " (" + nframes + " frames, " + total + " sommets cumulés)");
    }

    static int writeGolden(String so, byte[] npBytes, byte[] atlasBytes, String outPath, int nframes, float dtMs) throws Exception {
        AndroidEmulator emu = newEmulator();
        Memory memory = emu.getMemory();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);

        com.github.unidbg.memory.MemoryBlock embV = memory.malloc(8192 * 6 * 4, false);
        com.github.unidbg.memory.MemoryBlock embD = memory.malloc(8192 * 2, false);
        Object objVerts = vm.resolveClass("java/nio/FloatBuffer").newObject(embV.getPointer());
        Object objDraw = vm.resolveClass("java/nio/ShortBuffer").newObject(embD.getPointer());

        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(64 * 1024 * 1024).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'N').put((byte) 'P').put((byte) 'G').put((byte) 'L');
        out.putInt(1);
        out.putInt(nframes);
        out.putFloat(dtMs / 1000f);
        int totalVerts = 0;
        int dtBits = Float.floatToRawIntBits(dtMs / 1000f);
        for (int f = 0; f < nframes; f++) {
            cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, dtBits);
            int n = cPart.callStaticJniMethodInt(emu, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I",
                effH, objVerts, objDraw);
            UnidbgPointer vp = embV.getPointer();
            UnidbgPointer dp = embD.getPointer();
            int vertCount = n > 0 ? (dp.getShort(n * 3L * 2) & 0xffff) : 0;
            out.putInt(vertCount);
            for (int i = 0; i < vertCount * 6; i++) out.putFloat(vp.getFloat(i * 4L));
            totalVerts += vertCount;
        }
        out.flip();
        byte[] arr = new byte[out.remaining()];
        out.get(arr);
        Files.write(new File(outPath).toPath(), arr);
        return totalVerts;
    }

    /** Génère un golden par `.np` sous une racine (récursif), 1 atlas bidon partagé (la structure de rendu
     *  ne dépend pas de l'atlas -- seul le nom de région, hors de notre fenêtre). Nom du golden = chemin
     *  relatif avec `/`->`__` + `.golden`, sous outDir. */
    static void dumpGoldenAll(String so, String assetsRoot, String outDir, int nframes, float dtMs) throws Exception {
        File root = new File(assetsRoot);
        List<File> files = new ArrayList<>();
        File[] anyAtlasHolder = new File[1];
        collectNp(root, files, anyAtlasHolder);
        java.util.Collections.sort(files);
        if (files.isEmpty()) { System.out.println("aucun .np sous " + assetsRoot); return; }
        byte[] atlasBytes = Files.readAllBytes(anyAtlasHolder[0].toPath());
        new File(outDir).mkdirs();
        int ok = 0;
        for (File f : files) {
            String rel = root.toURI().relativize(f.toURI()).getPath().replace('/', '_');
            String outPath = outDir + "/" + rel + ".golden";
            try {
                byte[] npBytes = Files.readAllBytes(f.toPath());
                writeGolden(so, npBytes, atlasBytes, outPath, nframes, dtMs);
                ok++;
            } catch (Throwable t) { System.out.println("FAIL golden " + f.getName() + " : " + t); }
        }
        System.out.println("=== " + ok + "/" + files.size() + " goldens écrits sous " + outDir + " ===");
    }

    static Traj runAndDumpVertices(String so, byte[] npBytes, byte[] atlasBytes, boolean print) throws Exception {
        AndroidEmulator emu = newEmulator();
        Memory memory = emu.getMemory();
        VM vm = emu.createDalvikVM();
        vm.setVerbose(false);
        DalvikModule dm = vm.loadLibrary(new File(so), true);
        installBufferSvc(emu, vm);
        DvmClass cSpine = vm.resolveClass("com/perblue/heroes/cspine/Native");
        DvmClass cPart = vm.resolveClass("com/perblue/heroes/cparticle/Native");
        cSpine.callStaticJniMethod(emu, "Spine_init()V");
        int atlasH = cSpine.callStaticJniMethodInt(emu, "Atlas_create([BZ)I", new ByteArray(vm, atlasBytes), 1);
        int effH = cPart.callStaticJniMethodInt(emu, "Effect_create([BI)I", new ByteArray(vm, npBytes), atlasH);
        cPart.callStaticJniMethod(emu, "Effect_start(I)V", effH);

        com.github.unidbg.memory.MemoryBlock embV = memory.malloc(8192 * 6 * 4, false);
        com.github.unidbg.memory.MemoryBlock embD = memory.malloc(8192 * 2, false);
        Object objVerts = vm.resolveClass("java/nio/FloatBuffer").newObject(embV.getPointer());
        Object objDraw = vm.resolveClass("java/nio/ShortBuffer").newObject(embD.getPointer());

        Traj t = new Traj();
        for (int f = 0; f < NFRAMES; f++) {
            cPart.callStaticJniMethodInt(emu, "Effect_update(IF)Z", effH, Float.floatToRawIntBits(0.1f));
            int n = cPart.callStaticJniMethodInt(emu, "Effect_getVertices(ILjava/nio/FloatBuffer;Ljava/nio/ShortBuffer;)I",
                effH, objVerts, objDraw);
            // n = nombre de DRAW CALLS (pas de sommets, cf. UnidbgVM.java) -- le vrai compte de sommets est
            // le short à l'offset n*3 du buffer drawCalls (3 shorts/appel + 1 short total à la fin).
            UnidbgPointer vp = embV.getPointer();
            UnidbgPointer dp = embD.getPointer();
            int vertCount = n > 0 ? (dp.getShort(n * 3L * 2) & 0xffff) : 0;
            t.verts[f] = vertCount;
            if (vertCount > 0) { t.x[f] = vp.getFloat(0); t.y[f] = vp.getFloat(4); }
            if (print) {
                StringBuilder sb = new StringBuilder();
                int nShow = Math.min(3, vertCount);
                for (int i = 0; i < nShow; i++) {
                    float x = vp.getFloat(i * 6L * 4), y = vp.getFloat(i * 6L * 4 + 4);
                    sb.append(String.format("(%.2f,%.2f) ", x, y));
                }
                System.out.printf("frame %2d: drawCalls=%-3d verts=%-4d %s%n", f, n, vertCount, sb);
            }
        }
        return t;
    }

    /** BATCH (g262sexies) : applique `vertextest` (résumé seulement) à une LISTE d'offsets fichier
     *  `active`, contre le MÊME fichier -- pour dérouler rapidement l'attribution des champs restants
     *  sans relire 15 lignes à chaque fois. Résumé : 1ᵉʳ frame où ça diverge, frame de "mort" (verts=0)
     *  originale vs patchée, delta de position au dernier frame commun. */
    static void batchVertexTest(String so, String npPath, String atlasPath, int[] offsets) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] orig = Files.readAllBytes(new File(npPath).toPath());
        Traj base = runAndDumpVertices(so, orig, atlasBytes, false);
        int baseDeath = deathFrame(base);
        for (int off : offsets) {
            byte[] patched = orig.clone();
            byte origVal = orig[off];
            patched[off] = (byte) (origVal == 0 ? 1 : 0);
            Traj p = runAndDumpVertices(so, patched, atlasBytes, false);
            int pDeath = deathFrame(p);
            int firstDiv = -1;
            float maxDx = 0, maxDy = 0;
            for (int f = 0; f < NFRAMES; f++) {
                if (base.verts[f] == 0 || p.verts[f] == 0) continue;
                float dx = Math.abs(base.x[f] - p.x[f]), dy = Math.abs(base.y[f] - p.y[f]);
                if ((dx > 0.01f || dy > 0.01f) && firstDiv < 0) firstDiv = f;
                maxDx = Math.max(maxDx, dx); maxDy = Math.max(maxDy, dy);
            }
            System.out.printf("off=%-5d (%d->%d) firstDiv=%-3s death %d->%d  maxDX=%.2f maxDY=%.2f%n",
                off, origVal, patched[off], firstDiv < 0 ? "-" : String.valueOf(firstDiv), baseDeath, pDeath, maxDx, maxDy);
        }
    }
    static int deathFrame(Traj t) { for (int f = 0; f < NFRAMES; f++) if (t.verts[f] == 0) return f; return -1; }

    /** Comme batchVertexTest, mais patche la VALEUR (lowMin, 4 octets juste après `active`) au lieu du
     *  flag `active` -- nécessaire pour les champs lus SANS `ifeq` dans le bytecode Java (life/angle/
     *  sizeX/sizeY, cf. JOURNAL g262) : `active` ne gate rien pour eux, `newLowValue` calcule toujours
     *  une valeur (juste `min+(max-min)*rand`, indépendant du flag) -- seule la VALEUR compte. */
    static void batchValueTest(String so, String npPath, String atlasPath, int[] offsets, float testVal) throws Exception {
        byte[] atlasBytes = Files.readAllBytes(new File(atlasPath).toPath());
        byte[] orig = Files.readAllBytes(new File(npPath).toPath());
        Traj base = runAndDumpVertices(so, orig, atlasBytes, false);
        int baseDeath = deathFrame(base);
        int bits = Float.floatToRawIntBits(testVal);
        for (int off : offsets) {
            byte[] patched = orig.clone();
            int lm = off + 1; // lowMin = juste après le bool `active`
            patched[lm] = (byte) (bits >>> 24); patched[lm + 1] = (byte) (bits >>> 16);
            patched[lm + 2] = (byte) (bits >>> 8); patched[lm + 3] = (byte) bits;
            Traj p = runAndDumpVertices(so, patched, atlasBytes, false);
            int pDeath = deathFrame(p);
            float maxDx = 0, maxDy = 0; int firstDiv = -1;
            for (int f = 0; f < NFRAMES; f++) {
                if (base.verts[f] == 0 || p.verts[f] == 0) continue;
                float dx = Math.abs(base.x[f] - p.x[f]), dy = Math.abs(base.y[f] - p.y[f]);
                if ((dx > 0.01f || dy > 0.01f) && firstDiv < 0) firstDiv = f;
                maxDx = Math.max(maxDx, dx); maxDy = Math.max(maxDy, dy);
            }
            System.out.printf("off=%-5d lowMin->%.1f  firstDiv=%-3s death %d->%d  maxDX=%.2f maxDY=%.2f%n",
                off, testVal, firstDiv < 0 ? "-" : String.valueOf(firstDiv), baseDeath, pDeath, maxDx, maxDy);
        }
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
