import org.objectweb.asm.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Aligne les APPELS de méthodes gdx d'un jar (ex. spine-libgdx compilé contre le gdx STOCK) sur les
 * signatures RÉELLES du gdx MODIFIÉ de PerBlue (game.jar).
 *
 * PerBlue a modifié son libGDX : p.ex. {@code Array} implémente {@code java.util.Collection<T>} donc
 * {@code add(Object)} renvoie {@code boolean} (au lieu de {@code void} en stock). Le bytecode de
 * spine appelle {@code Array.add(Object)V} → {@code NoSuchMethodError} sur game.jar. Ce patch réécrit
 * le descripteur de l'appel vers celui de game.jar ({@code ...Z}) et insère un {@code POP} quand un
 * {@code void} devient une valeur (pile cohérente). Général : couvre TOUTES les divergences de type
 * de retour, pas seulement {@code add}. Aucune logique modifiée — on réconcilie une ABI divergente.
 */
public class PatchGdxCalls {
    // clé: owner + "." + name + paramDesc  ->  descripteur complet réel dans game.jar
    static final Map<String, String> REAL = new HashMap<>();

    public static void main(String[] args) throws Exception {
        String gameJar = args[0], inJar = args[1], outJar = args[2];
        indexGame(gameJar);
        System.out.println("[patch] " + REAL.size() + " signatures gdx indexées depuis game.jar");

        int[] patched = {0};
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(inJar)));
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outJar)))) {
            zos.setLevel(6);
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] data = zis.readAllBytes();
                if (e.getName().endsWith(".class")) data = patch(data, patched);
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.println("[patch] appels réécrits: " + patched[0]);
    }

    static void indexGame(String jar) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class") || !e.getName().startsWith("com/badlogic/gdx")) continue;
                byte[] b = zis.readAllBytes();
                new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                    String owner;
                    @Override public void visit(int v, int a, String n, String s, String sup, String[] i) { owner = n; }
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                        String params = d.substring(0, d.indexOf(')') + 1);
                        REAL.put(owner + "." + n + params, d);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    static byte[] patch(byte[] in, int[] patched) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); // maxStack ajusté ; frames conservées (pile nette inchangée)
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int a, String mn, String md, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(a, mn, md, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (owner.startsWith("com/badlogic/gdx")) {
                            String params = desc.substring(0, desc.indexOf(')') + 1);
                            String real = REAL.get(owner + "." + name + params);
                            if (real != null && !real.equals(desc)) {
                                char oldRet = desc.charAt(desc.indexOf(')') + 1);
                                char newRet = real.charAt(real.indexOf(')') + 1);
                                super.visitMethodInsn(op, owner, name, real, itf);
                                // void -> valeur : dépiler la valeur produite non attendue par l'appelant.
                                if (oldRet == 'V' && newRet != 'V') {
                                    super.visitInsn((newRet == 'J' || newRet == 'D') ? Opcodes.POP2 : Opcodes.POP);
                                }
                                patched[0]++;
                                return;
                            }
                        }
                        super.visitMethodInsn(op, owner, name, desc, itf);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
