import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Recalcule les StackMapTable (COMPUTE_FRAMES) de TOUTES les classes d'un jar.
 *
 * Les classes issues de dex2jar (game.jar) n'ont pas de StackMapTable ; sous -Xverify:none la JVM
 * calcule paresseusement les oop-maps GC via l'ancien vérificateur (generateOopMap.cpp), qui
 * plante sur certaines méthodes (« Illegal class file ... in method loadBinaryData »). En
 * réécrivant chaque classe avec COMPUTE_FRAMES, on produit des frames valides : la JVM utilise le
 * vérificateur rapide par table (plus de generateOopMap), et le jeu peut tourner SANS -Xverify:none.
 *
 * getCommonSuperClass est résolu SANS charger/lier les classes (ASM lit seulement l'en-tête depuis
 * les octets du jar), pour éviter les VerifyError sur le bytecode dex2jar pendant le traitement.
 * Toute classe qui échoue au reframe est recopiée telle quelle (repli sûr).
 */
public class ReframeJar {
    static final Map<String, String[]> HIER = new HashMap<>(); // name -> [superName, isInterface?"1":"0"]
    static final Map<String, byte[]> BYTES = new HashMap<>();

    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        // 1) index : lire tous les .class (en-têtes) pour la hiérarchie.
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class")) continue;
                byte[] b = zis.readAllBytes();
                BYTES.put(e.getName(), b);
                try {
                    ClassReader cr = new ClassReader(b);
                    HIER.put(cr.getClassName(), new String[]{ cr.getSuperName(),
                        (cr.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0 ? "1" : "0" });
                } catch (Throwable ignore) {}
            }
        }
        System.out.println("[reframe] " + BYTES.size() + " classes indexées");

        int ok = 0, kept = 0, copied = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)));
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(1);
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) { continue; }
                byte[] data = zis.readAllBytes();
                if (name.endsWith(".class")) {
                    byte[] reframed = reframe(data);
                    if (reframed != null) { data = reframed; ok++; } else { kept++; }
                } else { copied++; }
                ZipEntry ne = new ZipEntry(name);
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.println("[reframe] reframed=" + ok + " kept-original=" + kept + " non-class=" + copied);
    }

    static byte[] reframe(byte[] in) {
        try {
            ClassReader cr = new ClassReader(in);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override protected String getCommonSuperClass(String a, String b) {
                    return commonSuper(a, b);
                }
            };
            // Normalisation non-sémantique (§1) du flag `itf` des invocations. dex2jar émet parfois un
            // appel dont l'entrée de constant pool a le mauvais type (Methodref de classe au lieu
            // d'InterfaceMethodref) → la JVM lève IncompatibleClassChangeError (« must be InterfaceMethodref
            // constant ») à la résolution du LIEN (au 1ᵉʳ appel, pas au chargement — d'où l'invisibilité jusqu'à
            // ce que le chemin soit exercé).
            //   • INVOKESTATIC : ex. les lambdas R8 `$r8$lambda$…` d'interfaces comme FXHandle. Règle JVMS :
            //     itf DOIT valoir « l'owner est une interface ».
            //   • INVOKEINTERFACE : règle JVMS 6.5 : `invokeinterface` référence TOUJOURS un InterfaceMethodref
            //     → itf DOIT valoir true (owner toujours une interface, jamais un tableau).
            //   • INVOKESPECIAL vers une méthode `default` de SUPER-INTERFACE DIRECTE (forme `T.super.m()`,
            //     ex. `SimpleStudiedBuff` → `IStudiedBuff.spawnParticles`, déclenché par le skill d'un héros en
            //     combat) : le constant pool DOIT être un InterfaceMethodref. On flip itf=true UNIQUEMENT si
            //     l'owner est une super-interface DIRECTE de la classe visitée (`this.itfs`). ⚠️ On NE flip PAS
            //     un invokespecial vers une super-interface INDIRECTE : JVMS 4.9.2 exige l'appartenance DIRECTE
            //     et un flip casserait la vérif (« interface method reference is in an indirect superinterface »,
            //     vu sur PegasusSkill3 → IRampageAbility, interface héritée via la super-classe, pas directe).
            // INVOKEVIRTUAL n'est jamais touché (owner jamais une interface).
            ClassVisitor fix = new ClassVisitor(Opcodes.ASM9, cw) {
                Set<String> itfs = java.util.Collections.emptySet();
                String cname; boolean hasSource;
                @Override public void visit(int v, int a, String n, String sig, String sup, String[] in) {
                    itfs = in == null ? java.util.Collections.emptySet()
                                      : new HashSet<>(java.util.Arrays.asList(in));
                    cname = n; hasSource = false;
                    super.visit(v, a, n, sig, sup, in);
                }
                @Override public void visitSource(String source, String debug) {
                    hasSource = source != null; super.visitSource(source, debug);
                }
                @Override public void visitEnd() {
                    // dex2jar laisse souvent les classes SANS attribut SourceFile → getFileName() renvoie null, ce
                    // qui fait NPE le code du jeu qui inspecte la stack (TagHelper.getTag → getFileName().replace),
                    // notamment sur le chemin restart() du 1er lancement (téléchargement de contenu). On rétablit un
                    // SourceFile synthétique = <NomSimple>.java. NON-SÉMANTIQUE (métadonnée de debug), §1
                    // « correction d'attributs incohérents laissés par dex2jar ».
                    if (!hasSource && cname != null) {
                        String sn = cname.substring(cname.lastIndexOf('/') + 1);
                        int d = sn.indexOf('$'); if (d > 0) sn = sn.substring(0, d);
                        super.visitSource(sn + ".java", null);
                    }
                    super.visitEnd();
                }
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                    MethodVisitor mv = super.visitMethod(a, n, d, s, ex);
                    return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (op == Opcodes.INVOKESTATIC)
                                itf = owner.charAt(0) != '[' && isInterface(owner);
                            else if (op == Opcodes.INVOKEINTERFACE)
                                itf = true;
                            else if (op == Opcodes.INVOKESPECIAL && itfs.contains(owner) && isInterface(owner))
                                itf = true;
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }
                    };
                }
            };
            cr.accept(fix, ClassReader.SKIP_FRAMES);
            return cw.toByteArray();
        } catch (Throwable t) {
            return null; // repli : on garde l'original
        }
    }

    static String superOf(String name) {
        String[] h = HIER.get(name);
        if (h != null) return h[0];
        // classe hors jar (JDK/libGDX/android...) : lecture par réflexion sans lier le bytecode dex2jar.
        try {
            Class<?> c = Class.forName(name.replace('/', '.'), false, ReframeJar.class.getClassLoader());
            if (c.isInterface()) return "java/lang/Object";
            Class<?> s = c.getSuperclass();
            return s == null ? null : s.getName().replace('.', '/');
        } catch (Throwable t) { return null; }
    }

    static boolean isInterface(String name) {
        String[] h = HIER.get(name);
        if (h != null) return "1".equals(h[1]);
        try { return Class.forName(name.replace('/', '.'), false, ReframeJar.class.getClassLoader()).isInterface(); }
        catch (Throwable t) { return false; }
    }

    static String commonSuper(String a, String b) {
        if (a.equals(b)) return a;
        if ("java/lang/Object".equals(a) || "java/lang/Object".equals(b)) return "java/lang/Object";
        if (isInterface(a) || isInterface(b)) return "java/lang/Object";
        Set<String> ax = new HashSet<>();
        for (String c = a; c != null; c = superOf(c)) { ax.add(c); if (c.equals("java/lang/Object")) break; }
        for (String c = b; c != null; c = superOf(c)) { if (ax.contains(c)) return c; if (c.equals("java/lang/Object")) break; }
        return "java/lang/Object";
    }
}
