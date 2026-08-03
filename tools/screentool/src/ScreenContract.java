import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * OUTIL D'INDUSTRIALISATION (#73) — extracteur de CONTRAT d'écran, ancré dans le BYTECODE (faits, zéro devinette).
 *
 * <p>But : ne plus reproduire nos défauts récurrents (documentés dans MEMORY/SHIMS/JOURNAL) quand on implémente
 * un nouvel écran/mode. Donné un ou plusieurs préfixes de classes CLIENTES (l'écran + ses displays/windows), il
 * lit leur bytecode et rapporte :
 *   A. Les MESSAGES RÉSEAU référencés (com/perblue/heroes/network/messages/*).
 *   B. Pour chaque message, les GETTERS que le client appelle dessus = les CHAMPS que le SERVEUR doit peupler
 *      (attrape le défaut nº1 « champ jamais renseigné » : activeBreakerFight, actionState, bossClaimStatus…).
 *   C. La COUVERTURE HANDLERS : lesquels de ces messages LoginServer route déjà (via `instanceof`), lesquels
 *      NON (attrape le défaut « pas de handler » : GetBreakerQuest).
 *   D. Le(s) GATE(s) Unlockable référencé(s) (verrou de fonctionnalité / TL).
 *
 * Usage : ScreenContract <game.jar> <server-classes-dir> <prefixe1[,prefixe2,...]>
 *   ex.  ScreenContract libs/game.jar build/server-classes com/perblue/heroes/ui/invasion/InvasionBreakerScreen
 * Les préfixes matchent le nom binaire (inclut donc les classes internes `$…`).
 */
public final class ScreenContract {
  static final String MSG = "com/perblue/heroes/network/messages/";

  // message binaire -> set de "getX/isX" appelés par le client (les champs à peupler)
  static final TreeMap<String, TreeSet<String>> readGetters = new TreeMap<>();
  // message binaire -> set de CHAMPS wire lus (GETFIELD) par le client (= le vrai contrat des messages wire)
  static final TreeMap<String, TreeSet<String>> readFields = new TreeMap<>();
  static final TreeSet<String> referencedMsgs = new TreeSet<>();
  static final TreeSet<String> unlockables = new TreeSet<>();
  static final TreeSet<String> sends = new TreeSet<>();   // messages construits (new) par l'écran = candidats ENVOYÉS

  public static void main(String[] args) throws Exception {
    if (args.length < 3) { System.out.println("Usage: ScreenContract <game.jar> <server-classes-dir> <prefix1[,prefix2]>"); return; }
    String jar = args[0], srvDir = args[1];
    String[] prefixes = args[2].split(",");

    // 1) analyser les classes clientes qui matchent un préfixe
    int analyzed = 0;
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        if (!e.getName().endsWith(".class")) continue;
        String bin = e.getName().substring(0, e.getName().length() - 6);
        boolean match = false;
        for (String p : prefixes) if (bin.equals(p) || bin.startsWith(p + "$")) { match = true; break; }
        if (!match) continue;
        new ClassReader(zis.readAllBytes()).accept(new ClientVisitor(), ClassReader.SKIP_DEBUG);
        analyzed++;
      }
    }

    // 2) set des messages routés par LoginServer (instanceof) — INCLUT les classes internes (le vrai routage
    //    est dans un listener anonyme LoginServer$…, pas dans l'outer class).
    TreeSet<String> handled = new TreeSet<>();
    File dh = new File(srvDir, "dhserver");
    File[] lsClasses = dh.listFiles((d, nm) -> nm.equals("LoginServer.class") || nm.startsWith("LoginServer$"));
    if (lsClasses != null && lsClasses.length > 0) {
      for (File f : lsClasses) try (InputStream in = new FileInputStream(f)) {
        new ClassReader(in.readAllBytes()).accept(new HandledVisitor(handled), ClassReader.SKIP_DEBUG);
      }
    } else {
      System.out.println("[warn] LoginServer*.class introuvable sous " + srvDir + " → couverture non calculée");
    }

    // 3) rapport
    System.out.println("=== CONTRAT D'ÉCRAN — " + args[2] + " ===");
    System.out.println("(classes analysées : " + analyzed + " ; ancré bytecode, cf. #73)\n");

    System.out.println("D. GATE(s) Unlockable : " + (unlockables.isEmpty() ? "(aucun trouvé)" : unlockables));
    System.out.println();

    System.out.println("A/B. CE QUE L'ÉCRAN LIT (serveur→client) — le serveur DOIT peupler ces CHAMPS/GETTERS :");
    System.out.println("     (défaut nº1 : un champ lu ici mais jamais renseigné par le serveur = écran vide / bouton inerte)");
    boolean anyRead = false;
    TreeSet<String> allRead = new TreeSet<>(readFields.keySet()); allRead.addAll(readGetters.keySet());
    for (String m : allRead) {
      TreeSet<String> fs = readFields.get(m), gs = readGetters.get(m);
      if ((fs == null || fs.isEmpty()) && (gs == null || gs.isEmpty())) continue;
      anyRead = true;
      System.out.println("  • " + simple(m) + " :");
      if (fs != null) for (String f : fs) System.out.println("        champ  ." + f);
      if (gs != null) for (String g : gs) System.out.println("        getter ." + g + "()");
    }
    if (!anyRead) System.out.println("  (rien lu directement — l'écran lit peut-être via un holder/helper non fourni en argument)");
    System.out.println();

    System.out.println("C. COUVERTURE HANDLERS (messages construits par l'écran = candidats ENVOYÉS client→serveur) :");
    if (sends.isEmpty()) System.out.println("  (aucun `new <Message>` détecté dans l'écran)");
    for (String m : sends) {
      boolean ok = handled.contains(m);
      System.out.println("  [" + (ok ? "OK " : "MANQUE") + "] " + simple(m)
          + (ok ? " — routé par LoginServer (instanceof)" : " — AUCUN handler LoginServer (à implémenter)"));
    }
    System.out.println();

    System.out.println("Tous les messages référencés : ");
    for (String m : referencedMsgs) System.out.println("  · " + simple(m) + (handled.contains(m) ? "  [handler✓]" : ""));
    System.out.println();
    printChecklist();
  }

  /** E. Défauts RÉCURRENTS (distillés de MEMORY/SHIMS/JOURNAL) que le bytecode de l'écran NE montre PAS —
   *  à cocher à la main pour chaque écran, pour « ne plus reproduire nos erreurs » (#73). */
  static void printChecklist() {
    System.out.println("E. CHECKLIST DES DÉFAUTS RÉCURRENTS (à vérifier — invisibles dans le bytecode de l'écran) :");
    String[] items = {
      "[ ] CHAMP JAMAIS RENSEIGNÉ (§A/B) : chaque champ listé en A/B est-il RÉELLEMENT posé par le serveur ? "
        + "(g46 activeBreakerFight, g47 InvasionBossInfo.actionState, g50 bossClaimStatus, g41 GuildInfo.warEndTime).",
      "[ ] HANDLER MANQUANT (§C) : chaque message ENVOYÉ a-t-il une route LoginServer ? (g45 GetBreakerQuest absent → écran vide).",
      "[ ] TYPAGE WIRE (explose À L'ÉCRITURE, invisible headless) : les List/Map de la réponse contiennent-elles le BON type ? "
        + "(g44 WarDefense.defenders=WarHeroData≠WarHeroSummary ; g45 activeCars=WarAttackCarBonus≠WarCarType). → round-trip wire OBLIGATOIRE (WireCheck).",
      "[ ] RESYNC APRÈS MUTATION : tout champ muté HORS this.extra doit être re-synchronisé vers le wire, sinon perdu au round-trip "
        + "(teamLevel, userName, diamonds, statuts de campagne, héros, compteurs UserFlag).",
      "[ ] POUSSÉE AU BOOT / ORDRE : l'info d'entrée doit-elle être poussée au login ? (g45 InvasionInfo jamais poussée → nav refusée, poule/œuf). "
        + "SocialHistory : tamponnée jusqu'au BootData (sinon reset l'efface).",
      "[ ] ANTI-TRICHE : coûts/points/paliers RECALCULÉS serveur (l'INDEX/coût client ignoré) ; valider AVANT d'accorder (ClientErrorCodeException).",
      "[ ] GATE RÉEL DU JEU (§D + Unlockable/TL) : respecter le verrou du jeu, ne jamais le désactiver (l'atteindre via l'état légitime).",
      "[ ] PatchStats/getBossHP : NE PAS déclencher PatchStats.<clinit> au push du BOOT (stat-sync incomplète → ExceptionInInitializerError empoisonne la classe).",
      "[ ] VÉRIF EN JEU OBLIGATOIRE : headless = 🟢 ; ✅ seulement après client réel → serveur → persistance → affichage.",
    };
    for (String it : items) System.out.println("  " + it);
  }

  static String simple(String bin) { int i = bin.lastIndexOf('/'); return bin.substring(i + 1); }

  static void noteType(String desc) {
    if (desc == null) return;
    // extraire les types d'objet d'un descripteur/nom interne
    int idx = 0;
    while ((idx = desc.indexOf(MSG, idx)) >= 0) {
      int end = idx + MSG.length();
      while (end < desc.length() && (Character.isLetterOrDigit(desc.charAt(end)) || desc.charAt(end) == '$' || desc.charAt(end) == '/')) end++;
      String bin = desc.substring(idx, end);
      if (bin.indexOf('$') < 0) referencedMsgs.add(bin);   // messages top-level (pas les $1 anonymes)
      idx = end;
    }
  }

  /** Visiteur des classes CLIENTES : collecte messages référencés, getters lus, sends, unlockables. */
  static class ClientVisitor extends ClassVisitor {
    ClientVisitor() { super(Opcodes.ASM9); }
    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
      return new MethodVisitor(Opcodes.ASM9) {
        @Override public void visitTypeInsn(int op, String type) {
          noteType(type);
          if (op == Opcodes.NEW && type.startsWith(MSG) && type.indexOf('$') < 0) sends.add(type);
        }
        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
          noteType(owner); noteType(desc);
          if (owner.startsWith(MSG) && owner.indexOf('$') < 0
              && (name.startsWith("get") || name.startsWith("is")) && desc.startsWith("()")) {
            readGetters.computeIfAbsent(owner, k -> new TreeSet<>()).add(name);
          }
        }
        @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
          noteType(owner); noteType(desc);
          if (owner.endsWith("/Unlockable") && op == Opcodes.GETSTATIC) unlockables.add(name);
          // CHAMP WIRE lu par le client (GETFIELD sur un message) = le serveur DOIT le peupler (défaut nº1).
          if (op == Opcodes.GETFIELD && owner.startsWith(MSG) && owner.indexOf('$') < 0)
            readFields.computeIfAbsent(owner, k -> new TreeSet<>()).add(name);
        }
        @Override public void visitLdcInsn(Object cst) { if (cst instanceof Type) noteType(((Type) cst).getInternalName()); }
      };
    }
  }

  /** Visiteur de LoginServer : collecte les opérandes d'INSTANCEOF = messages routés. */
  static class HandledVisitor extends ClassVisitor {
    final Set<String> out;
    HandledVisitor(Set<String> out) { super(Opcodes.ASM9); this.out = out; }
    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
      return new MethodVisitor(Opcodes.ASM9) {
        @Override public void visitTypeInsn(int op, String type) {
          if (op == Opcodes.INSTANCEOF && type.startsWith(MSG)) out.add(type);
        }
      };
    }
  }
}
