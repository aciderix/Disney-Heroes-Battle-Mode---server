import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.util.zip.*;

/**
 * Corrige UN appel du backend audio STOCK de libGDX 1.9.7 (gdx-lwjgl3-audio.jar) vers une méthode
 * qui N'EXISTE PAS DU TOUT dans le cœur libGDX RÉDUIT du jeu (game-logic.jar/game-logic-framed.jar
 * — même nom pleinement qualifié com.badlogic.gdx.utils.*, ombrage délibéré sur le classpath, cf.
 * build.gradle) : {@code FloatArray.insert(int,float)} (le jeu n'a que add/incr/set/pop/clear/… —
 * vérifié par décompilation exhaustive, aucun équivalent).
 *
 * Complète {@code PatchGdxCalls} (déjà dans ce dépôt, commit ae68be6) qui réconcilie les
 * DIVERGENCES DE SIGNATURE (ex. {@code Array.add(Object)} renvoie {@code boolean} chez PerBlue,
 * {@code void} en stock — géré généralement par PatchGdxCalls en indexant game-logic.jar) mais qui
 * NE PEUT RIEN pour une méthode ABSENTE (aucune entrée à substituer).
 *
 * ⚠️ PREMIÈRE TENTATIVE (retirée) : NEUTRALISER l'appel (POP des arguments, no-op) semblait
 * suffisant (comportement observé : le jeu bootait, l'audio jouait) — MAIS FAUX : vérifié EN JEU
 * (Windows, tutoriel jusqu'au premier combat) — {@code renderedSecondsQueue} est une VRAIE FILE
 * consommée ailleurs par {@code OpenALMusic.update()} via {@code FloatArray.pop()} (3 sites
 * d'appel) : neutraliser {@code insert()} vide la file en continu SANS jamais la remplir →
 * `ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 3` dans
 * {@code FloatArray.pop()} dès que le flux audio tourne assez longtemps pour vider le tampon
 * initial. §2 (aucune rustine) : un no-op n'était PAS un comportement équivalent, juste un
 * symptôme masqué plus tard — corrigé en RÉIMPLÉMENTANT fidèlement la méthode (voir ci-dessous),
 * pas en la contournant.
 *
 * CORRECTIF RÉEL : le seul appel du paquet audio est {@code insert(0, computedValue)} (index
 * TOUJOURS littéral 0 — vérifié par bytecode, {@code iconst_0} juste avant l'appel — sémantique de
 * PRÉPEND). On réoriente l'appel vers {@code FloatArrayCompat.insert0(Object,float)} (classe
 * compagnon compilée séparément, réflexion pure sur les champs PUBLICS {@code size}/{@code items}
 * + la méthode PUBLIQUE {@code ensureCapacity} — TOUS présents et identiques côté PerBlue —
 * réimplémentation FIDÈLE du prépend stock, PAS une approximation) :
 *   pile avant l'appel original : [queueRef, 0, valeur] (objref, int littéral, float calculé)
 *   SWAP (échange les 2 sommets : valeur ↔ 0) → [queueRef, valeur, 0]
 *   POP (retire le 0, désormais au sommet)     → [queueRef, valeur]
 *   INVOKESTATIC FloatArrayCompat.insert0(Ljava/lang/Object;F)V — pile exactement attendue.
 * {@code FloatArrayCompat.class} (déjà sur le classpath d'exécution de CET outil, compilé par
 * run-desktop.sh dans le même dossier) est COPIÉE TELLE QUELLE dans le jar de sortie.
 *
 * Usage : java PatchGdxAudio <in.jar> <out.jar>
 */
public class PatchGdxAudio {
    static final String HELPER_PATH = "dhbackend/audiocompat/FloatArrayCompat.class";

    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        int patchedInsert = 0, classes = 0;
        boolean helperAlreadyPresent = false;
        java.io.File tmp = java.io.File.createTempFile("patchgdxaudio", ".jar");
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)));
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            zos.setLevel(1);
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                byte[] data = zis.readAllBytes();
                if (e.getName().equals(HELPER_PATH)) helperAlreadyPresent = true;
                if (e.getName().endsWith(".class")) {
                    classes++;
                    int[] counts = new int[1];
                    byte[] patched = patch(data, counts);
                    patchedInsert += counts[0];
                    data = patched != null ? patched : data;
                }
                ZipEntry ne = new ZipEntry(e.getName());
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
            if (!helperAlreadyPresent) {
                // Copie FloatArrayCompat.class (sur le classpath d'exécution de cet outil) dans le jar de sortie.
                try (InputStream helper = PatchGdxAudio.class.getClassLoader().getResourceAsStream(HELPER_PATH)) {
                    if (helper == null) throw new IllegalStateException(HELPER_PATH + " introuvable sur le classpath de PatchGdxAudio");
                    byte[] hb = helper.readAllBytes();
                    zos.putNextEntry(new ZipEntry(HELPER_PATH));
                    zos.write(hb);
                    zos.closeEntry();
                }
            }
        }
        java.nio.file.Files.copy(tmp.toPath(), new File(out).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tmp.delete();
        System.out.println("[patch-gdx-audio] " + classes + " classes ; FloatArray.insert redirigés vers FloatArrayCompat=" + patchedInsert);
    }

    static byte[] patch(byte[] in, int[] counts) {
        try {
            ClassReader cr = new ClassReader(in);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
                    MethodVisitor mv = super.visitMethod(a, n, d, s, ex);
                    return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (op == Opcodes.INVOKEVIRTUAL && owner.equals("com/badlogic/gdx/utils/FloatArray")
                                    && mn.equals("insert") && md.equals("(IF)V")) {
                                // pile: [queueRef, 0, valeur] -> SWAP -> [queueRef, valeur, 0] -> POP -> [queueRef, valeur]
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "dhbackend/audiocompat/FloatArrayCompat",
                                    "insert0", "(Ljava/lang/Object;F)V", false);
                                counts[0]++;
                                return;
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }
                    };
                }
            };
            cr.accept(cv, ClassReader.SKIP_FRAMES);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[patch-gdx-audio] WARN classe non patchée (" + t + ")");
            return null; // repli : classe recopiée telle quelle
        }
    }
}
