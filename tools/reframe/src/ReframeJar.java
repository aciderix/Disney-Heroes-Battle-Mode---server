import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

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
            cr.accept(cw, ClassReader.SKIP_FRAMES);
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
