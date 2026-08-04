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

  static final TreeSet<String> handled = new TreeSet<>();

  public static void main(String[] args) throws Exception {
    if (args.length < 3) { System.out.println("Usage: ScreenContract <game.jar> <server-classes-dir> <prefix1[,prefix2]> [--scaffold <ModeName> <outdir>]"); return; }
    String jar = args[0], srvDir = args[1];
    String[] prefixes = args[2].split(",");
    String scaffoldMode = null, scaffoldOut = null;
    for (int i = 3; i < args.length; i++) if (args[i].equals("--scaffold") && i + 2 < args.length) { scaffoldMode = args[i+1]; scaffoldOut = args[i+2]; }

    // 1) analyser les classes clientes qui matchent un préfixe
    int analyzed = 0;
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        if (!e.getName().endsWith(".class")) continue;
        String bin = e.getName().substring(0, e.getName().length() - 6);
        boolean match = false;
        // Un préfixe finissant par "/" = TOUT le PACKAGE (tous les écrans/helpers du mode) → couvre le flux
        // multi-écrans (ex. arène = league + hero chooser + pvp helper). Sinon = la classe + ses internes.
        for (String p : prefixes)
          if (p.endsWith("/") ? bin.startsWith(p) : (bin.equals(p) || bin.startsWith(p + "$"))) { match = true; break; }
        if (!match) continue;
        new ClassReader(zis.readAllBytes()).accept(new ClientVisitor(), ClassReader.SKIP_DEBUG);
        analyzed++;
      }
    }

    // 2) set des messages routés par LoginServer (instanceof) — INCLUT les classes internes (le vrai routage
    //    est dans un listener anonyme LoginServer$…, pas dans l'outer class).
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

    System.out.println("C. COUVERTURE HANDLERS (messages ENVOYÉS client→serveur) :");
    // Un message CONSTRUIT (`new`) mais aussi LU par l'écran (§A/B) est une réponse serveur→client construite
    // localement, PAS un envoi → on l'exclut (sinon faux positif, cf. ArenaInfo/ArenaRow sur l'écran arène).
    java.util.Set<String> readMsgs = new java.util.HashSet<>(readFields.keySet()); readMsgs.addAll(readGetters.keySet());
    boolean anySent = false;
    for (String m : sends) {
      if (readMsgs.contains(m)) continue;               // lu → inbound, pas un envoi
      boolean ok = handled.contains(m);
      boolean req = looksLikeRequest(simple(m));
      if (!ok && !req) continue;                        // construit, non routé, non requête → type serveur→client local
      anySent = true;
      System.out.println("  [" + (ok ? "OK    " : "MANQUE") + "] " + simple(m)
          + (ok ? " — routé par LoginServer (instanceof)" : " — requête sans handler LoginServer (à implémenter)"));
    }
    if (!anySent) System.out.println("  (aucun message client→serveur détecté — écran en lecture seule ?)");
    System.out.println("  (note : un message serveur→client CONSTRUIT localement par l'écran n'est PAS un envoi — exclu ici)");
    System.out.println();

    System.out.println("Tous les messages référencés : ");
    for (String m : referencedMsgs) System.out.println("  · " + simple(m) + (handled.contains(m) ? "  [handler✓]" : ""));
    System.out.println();
    printChecklist();

    if (scaffoldMode != null) { System.out.println(); generateScaffold(jar, scaffoldMode, scaffoldOut); }
  }

  /** Génère des SQUELETTES (structure only, jamais de logique) : builder de réponse posant CHAQUE champ du
   *  contrat (TODO), test avec WireCheck, snippet handler. La LOGIQUE (règles) reste à brancher via le code du
   *  jeu (PRINCIPLES §3/§4 : jamais inventer). */
  static void generateScaffold(String jar, String mode, String outDir) throws Exception {
    // messages serveur→client (lus par l'écran) et client→serveur (envoyés, non routés)
    TreeSet<String> produce = new TreeSet<>(readFields.keySet());
    java.util.Set<String> readMsgs = new java.util.HashSet<>(readFields.keySet()); readMsgs.addAll(readGetters.keySet());
    TreeSet<String> toHandle = new TreeSet<>();
    // même filtre que le rapport C : exclure les lus (inbound) + ne garder que les requêtes non routées
    for (String s : sends) if (!handled.contains(s) && !readMsgs.contains(s) && looksLikeRequest(simple(s))) toHandle.add(s);

    // types des champs des messages à produire (name -> descriptor), en UNE passe sur le jar
    TreeSet<String> want = new TreeSet<>(produce);
    Map<String, LinkedHashMap<String, String>> fieldTypes = messageFieldTypes(jar, want);

    // Classement des types OBJET des champs (une passe jar). Un champ écrit sur le fil ne tolère pas NULL :
    // un ENUM appelle ordinal(), un SOUS-MESSAGE appelle writeSingle() → NPE à l'écriture (défaut nº3). Le
    // scaffolder pose donc un placeholder NON nul de STRUCTURE (pas une règle, la vraie valeur reste un TODO) :
    // enum → `Enum.values()[0]`, sous-message instanciable → `new Type()` — pour que le round-trip wire généré
    // PASSE d'emblée (preuve de structure), comme notre shim `new IAPProducts()`.
    TreeSet<String> allTypes = new TreeSet<>();
    for (LinkedHashMap<String, String> fm : fieldTypes.values())
      for (String d : fm.values()) { String b = objBinary(d); if (b != null) allTypes.add(b); }
    Map<String, Character> typeKind = classifyTypes(jar, allTypes);   // 'E' enum · 'N' newable message · sinon absent

    new File(outDir).mkdirs();
    StringBuilder builder = new StringBuilder(), test = new StringBuilder(), snippet = new StringBuilder();

    builder.append("// SQUELETTE GÉNÉRÉ (#73 scaffolder) pour le mode ").append(mode).append(" — STRUCTURE, PAS DE LOGIQUE.\n");
    builder.append("// À déplacer dans dhserver/ et à COMPLÉTER : chaque TODO = valeur RÉELLE via le code du jeu\n");
    builder.append("// (<Mode>Helper/<Mode>Stats), JAMAIS inventée (PRINCIPLES §3/§4). Anti-triche : recalcul serveur.\n");
    builder.append("package dhserver;\n\nimport com.perblue.heroes.network.messages.*;\n\n");
    builder.append("public final class Server").append(mode).append("Scaffold {\n");
    for (String m : produce) {
      String sm = simple(m);
      LinkedHashMap<String, String> fts = fieldTypes.getOrDefault(m, new LinkedHashMap<>());
      builder.append("\n  /** Construit ").append(sm).append(" (serveur→client) — l'écran LIT les champs ci-dessous : tous DOIVENT être posés. */\n");
      builder.append("  public static ").append(sm).append(" build").append(sm).append("(ServerUser u /*, contexte */) {\n");
      builder.append("    ").append(sm).append(" m = new ").append(sm).append("();\n");
      for (String f : readFields.get(m)) {
        String desc = fts.get(f);
        builder.append("    m.").append(f).append(" = ").append(initFor(desc, typeKind)).append(";   // TODO valeur réelle")
               .append(desc == null ? " (type ?)" : " (" + prettyType(desc) + ")").append("\n");
      }
      builder.append("    return m;\n  }\n");
    }
    builder.append("}\n");

    test.append("// SQUELETTE DE TEST GÉNÉRÉ (#73) pour ").append(mode).append(" — à compléter + ajouter à regression.sh\n");
    test.append("public final class ").append(mode).append("ScaffoldTest {\n");
    test.append("  public static void main(String[] a) throws Exception {\n    dhserver.ServerContext.init();\n");
    for (String m : produce) {
      String sm = simple(m);
      test.append("    WireCheck.assertRoundTrips(dhserver.Server").append(mode).append("Scaffold.build").append(sm).append("(null /*u*/));   // défaut nº3\n");
    }
    test.append("    // TODO: assertions d'état + round-trip DB (persistance).\n");
    test.append("    System.out.println(\"[").append(mode).append("ScaffoldTest] round-trip wire OK (squelette)\");\n  }\n}\n");

    snippet.append("// SNIPPET handler à INSÉRER dans LoginServer (chaîne if/else instanceof) — mode ").append(mode).append("\n");
    if (toHandle.isEmpty()) snippet.append("// (aucun message client→serveur non routé détecté)\n");
    for (String m : toHandle) {
      String sm = simple(m);
      snippet.append("} else if (m instanceof ").append(m.replace('/', '.')).append(") {\n");
      snippet.append("    ").append(sm).append(" cm = (").append(sm).append(") m;\n");
      snippet.append("    // TODO: valider (anti-triche, recalcul serveur) + exécuter la logique du jeu (<Mode>Helper) + persister ;\n");
      snippet.append("    // répondre : conn.send(Server").append(mode).append("Scaffold.build<Réponse>(user, …));\n");
      snippet.append("    System.out.println(\"[login] <== ").append(sm).append("\");\n");
    }

    write(outDir + "/Server" + mode + "Scaffold.java", builder.toString());
    write(outDir + "/" + mode + "ScaffoldTest.java", test.toString());
    write(outDir + "/LoginServer-" + mode + ".snippet.txt", snippet.toString());
    System.out.println("F. SCAFFOLD généré dans " + outDir + " :");
    System.out.println("   • Server" + mode + "Scaffold.java  (builder : " + produce.size() + " message(s), tous champs du contrat stubbés TODO)");
    System.out.println("   • " + mode + "ScaffoldTest.java   (round-trip wire de chaque réponse)");
    System.out.println("   • LoginServer-" + mode + ".snippet.txt  (" + toHandle.size() + " handler(s) à insérer)");
    System.out.println("   ⚠️ STRUCTURE seulement — brancher la LOGIQUE via le code du jeu, ne rien inventer (§3/§4).");
  }

  static void write(String path, String content) throws IOException {
    try (Writer w = new OutputStreamWriter(new FileOutputStream(path), java.nio.charset.StandardCharsets.UTF_8)) { w.write(content); }
  }

  /** Pour chaque message voulu, lit ses CHAMPS d'instance (nom→descripteur) depuis le jar. */
  static Map<String, LinkedHashMap<String, String>> messageFieldTypes(String jar, Set<String> want) throws IOException {
    Map<String, LinkedHashMap<String, String>> out = new HashMap<>();
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        if (!e.getName().endsWith(".class")) continue;
        String bin = e.getName().substring(0, e.getName().length() - 6);
        if (!want.contains(bin)) continue;
        LinkedHashMap<String, String> fm = new LinkedHashMap<>();
        new ClassReader(zis.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public FieldVisitor visitField(int acc, String n, String d, String s, Object v) {
            if ((acc & Opcodes.ACC_STATIC) == 0) fm.put(n, d);
            return null;
          }
        }, ClassReader.SKIP_CODE);
        out.put(bin, fm);
      }
    }
    return out;
  }

  static String initFor(String desc, Map<String, Character> typeKind) {
    if (desc == null) return "null /* TODO type ? */";
    switch (desc) {
      case "Z": return "false"; case "B": case "C": case "S": case "I": return "0";
      case "J": return "0L"; case "F": return "0f"; case "D": return "0d";
    }
    if (desc.startsWith("Ljava/util/List")) return "new java.util.ArrayList<>()";
    if (desc.startsWith("Ljava/util/Map")) return "new java.util.HashMap<>()";
    if (desc.startsWith("Ljava/util/Set")) return "new java.util.HashSet<>()";
    if (desc.equals("Ljava/lang/String;")) return "\"\"";
    if (desc.startsWith("[")) return "null /* array TODO */";
    // Placeholder NON nul de STRUCTURE (sinon NPE à l'écriture wire) : enum → values()[0] ; sous-message
    // instanciable → new Type(). Valeur de structure, pas une règle (la vraie reste un TODO).
    String b = objBinary(desc);
    if (b != null) {
      Character k = typeKind.get(b);
      String fqn = b.replace('/', '.');
      if (k != null && k == 'E') return fqn + ".values()[0] /* TODO valeur réelle */";
      if (k != null && k == 'N') return "new " + fqn + "() /* TODO peupler */";
    }
    return "null /* TODO: " + prettyType(desc) + " */";
  }

  /** Nom binaire du type OBJET d'un descripteur (Lcom/foo/Bar; → com/foo/Bar), sinon null (primitif/array/collection). */
  static String objBinary(String desc) {
    if (desc == null || !desc.startsWith("L") || !desc.endsWith(";")) return null;
    return desc.substring(1, desc.length() - 1);
  }

  /** Classe des types binaires (une passe jar) : 'E' = ÉNUM (ACC_ENUM/super Enum) ; 'N' = message NEWABLE
   *  (concret, non abstrait/interface/enum, avec un ctor sans argument) ; absent sinon (à laisser null + TODO). */
  static Map<String, Character> classifyTypes(String jar, Set<String> types) throws IOException {
    Map<String, Character> out = new HashMap<>();
    if (types.isEmpty()) return out;
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        if (!e.getName().endsWith(".class")) continue;
        String bin = e.getName().substring(0, e.getName().length() - 6);
        if (!types.contains(bin)) continue;
        final String fb = bin;
        final int[] acc = new int[1];
        final boolean[] noArgCtor = new boolean[1];
        new ClassReader(zis.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public void visit(int v, int a, String n, String sig, String sup, String[] itf) {
            acc[0] = a;
            if ((a & Opcodes.ACC_ENUM) != 0 || "java/lang/Enum".equals(sup)) out.put(fb, 'E');
          }
          @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
            if (n.equals("<init>") && d.equals("()V") && (a & Opcodes.ACC_PUBLIC) != 0) noArgCtor[0] = true;
            return null;
          }
        }, ClassReader.SKIP_CODE);
        boolean instantiable = (acc[0] & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM)) == 0;
        if (!out.containsKey(fb) && instantiable && noArgCtor[0]) out.put(fb, 'N');
      }
    }
    return out;
  }

  static String prettyType(String desc) {
    if (desc == null) return "?";
    if (desc.startsWith("L") && desc.endsWith(";")) { String s = desc.substring(1, desc.length()-1); return s.substring(s.lastIndexOf('/')+1); }
    return desc;
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

  /** Heuristique de direction : un message client→serveur est en général nommé comme une REQUÊTE/action.
   *  Sert à ne PAS confondre un type serveur→client construit localement (ArenaInfo, ArenaRow…) avec un envoi. */
  static final String[] REQ_PREFIX = {"Get","Set","Claim","Start","Change","Buy","Use","Sell","Post","Hire",
      "Donate","Upgrade","Activate","Edit","Create","Join","Leave","Kick","Promote","Demote","Accept","Reject",
      "Refresh","Request","Mark","Take","Delete","Open","Send","Choose","Skip","Complete","Collect","Spend","Equip"};
  static final String[] REQ_SUFFIX = {"Attack","Update"};
  static boolean looksLikeRequest(String simpleName) {
    for (String p : REQ_PREFIX) if (simpleName.startsWith(p)) return true;
    for (String s : REQ_SUFFIX) if (simpleName.endsWith(s)) return true;
    return false;
  }

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
