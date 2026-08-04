import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * OUTIL D'INDUSTRIALISATION (#74, levier A) — GRAPHE DE MESSAGES d'un mode, ancré dans le BYTECODE.
 *
 * <p>Supprime la « Limite 1 » de {@link ScreenContract} (portée PAR CLASSE). Un mode est MULTI-classes : un
 * message peut être ENVOYÉ depuis un écran ou un HELPER hors du package de l'écran principal (ex. réel :
 * {@code ArenaAttack} n'est pas émis par {@code ArenaLeagueScreen} mais par le hero-chooser / {@code ArenaHelper}).
 * Cet outil scanne TOUT le jar une fois, construit le graphe {@code message → {émetteurs (new), lecteurs
 * (GETFIELD/getter)}}, puis, à partir des classes GRAINE d'un mode (un préfixe de package UI), DÉCOUVRE
 * AUTOMATIQUEMENT toutes les classes du mode (écrans + hero choosers + helpers) qui touchent ses messages.
 *
 * <p>Les messages TRÈS partagés (BootData, RewardDrop, LootResults…) sont « génériques » : au-dessus d'un seuil
 * de classes référentes ils sont IGNORÉS pour l'union (sinon elle explose à tout le jar) — signalés à part.
 *
 * <p>Usage : {@code ModeGraph <game.jar> <prefixe-UI-du-mode> [--generic N] [--list]}
 *   ex.  {@code ModeGraph libs/game.jar com/perblue/heroes/ui/arena/}
 *   • sortie : messages du mode (avec émetteurs/lecteurs), CLASSES du mode (union), et une LISTE prête à passer
 *     à {@code ScreenContract} (préfixes séparés par des virgules).
 *   • {@code --generic N} : seuil « générique » (défaut 25). {@code --list} : n'imprime que la liste pour contract.sh.
 */
public final class ModeGraph {
  static final String MSG = "com/perblue/heroes/network/messages/";
  static final String HEROES = "com/perblue/heroes/";

  // message top-level -> classes qui le construisent (new) / le lisent (GETFIELD ou getter)
  static final TreeMap<String, TreeSet<String>> emitters = new TreeMap<>();
  static final TreeMap<String, TreeSet<String>> readers  = new TreeMap<>();
  // classe (outer) -> nombre de messages DISTINCTS référencés (détecte les HUBS génériques : dispatchers d'action,
  // GameMain… qui touchent des dizaines de messages de tous les modes → à exclure de l'union, sinon pollution).
  static final TreeMap<String, TreeSet<String>> msgsPerClass = new TreeMap<>();
  // messages référencés (new/read) par les classes GRAINE (le package UI du mode)
  static final TreeSet<String> seedEmits = new TreeSet<>();
  static final TreeSet<String> seedReads = new TreeSet<>();
  static final TreeSet<String> seedClasses = new TreeSet<>();

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println("Usage: ModeGraph <game.jar> <prefixe-UI-du-mode> [--generic N] [--list]");
      return;
    }
    String jar = args[0];
    String seed = args[1];   // préfixe de package (finit par /) OU token de nom (mode éparpillé, ex. "Arena")
    boolean seedIsPrefix = seed.endsWith("/") || seed.startsWith("com/perblue/");
    // hub=18 : mesuré, les HELPERS de mode référencent ≤ ~11 messages distincts (SurgeHelper 10, ArenaHelper 11),
    // les DISPATCHERS génériques ≥ 20 (ActionHelper 20, ClientActionHelper 27, GameMain 153) → seuil net à 18.
    int generic = 25, hub = 18; boolean listOnly = false, logic = false;
    for (int i = 2; i < args.length; i++) {
      if (args[i].equals("--generic") && i + 1 < args.length) generic = Integer.parseInt(args[++i]);
      else if (args[i].equals("--hub") && i + 1 < args.length) hub = Integer.parseInt(args[++i]);
      else if (args[i].equals("--list")) listOnly = true;
      else if (args[i].equals("--logic")) logic = true;
    }
    final int hubThreshold = hub;

    int scanned = 0;
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
      ZipEntry e;
      while ((e = zis.getNextEntry()) != null) {
        if (!e.getName().endsWith(".class")) continue;
        String bin = e.getName().substring(0, e.getName().length() - 6);
        if (!bin.startsWith(HEROES)) continue;                 // logique du jeu uniquement
        if (bin.startsWith(MSG)) continue;                     // on n'indexe pas les messages eux-mêmes
        TreeSet<String> emit = new TreeSet<>(), read = new TreeSet<>();
        new ClassReader(zis.readAllBytes()).accept(new RefVisitor(emit, read), ClassReader.SKIP_DEBUG);
        String outer = outer(bin);                              // regroupe les classes internes sous leur outer
        for (String m : emit) emitters.computeIfAbsent(m, k -> new TreeSet<>()).add(outer);
        for (String m : read) readers.computeIfAbsent(m, k -> new TreeSet<>()).add(outer);
        TreeSet<String> perCls = msgsPerClass.computeIfAbsent(outer, k -> new TreeSet<>());
        perCls.addAll(emit); perCls.addAll(read);
        // GRAINE : préfixe de package, ou token de nom parmi les classes ui/ (mode éparpillé, ex. "Arena").
        // Les classes de debug/test ne sont jamais graine (hors production).
        boolean isSeed = !nonProd(bin) && (seedIsPrefix ? bin.startsWith(seed)
            : (bin.startsWith("com/perblue/heroes/ui/") && simple(bin).contains(seed)));
        if (isSeed) { seedClasses.add(outer); seedEmits.addAll(emit); seedReads.addAll(read); }
        scanned++;
      }
    }

    // Token du mode pour l'AFFINITÉ DE NOM : token de graine, ou dernier segment du package.
    String token = seedIsPrefix ? lastSegment(seed) : seed;
    String tokL = token.toLowerCase();

    // Messages DU MODE = ceux référencés par les classes graine (union émis + lus).
    TreeSet<String> modeMsgs = new TreeSet<>(seedEmits); modeMsgs.addAll(seedReads);

    // Classement des messages du mode :
    //  • CORE = le NOM porte le token (spécifique au mode : ArenaAttack, SurgeData…) → sert à l'EXPANSION ;
    //  • GÉNÉRIQUE = partagé au-delà du seuil (BootData, RewardDrop…) — jamais discriminant ;
    //  • PARTAGÉ = roster/combat commun (HeroSummary, PlayerRow, LineupSummary…) ou cross-mode SANS le token
    //    → EXCLU de l'expansion (sinon on aspire d'AUTRES modes via ces messages partagés — mesuré sur Arena
    //    qui ramenait Heist/Surge par HeroSummary/PlayerRow). L'affinité de nom donne la précision.
    TreeSet<String> core = new TreeSet<>(), shared = new TreeSet<>(), genericMsgs = new TreeSet<>();
    for (String m : modeMsgs) {
      if (simple(m).toLowerCase().contains(tokL)) core.add(m);
      else if (refCount(m) > generic) genericMsgs.add(m);
      else shared.add(m);
    }

    // UNION = graine + classes pertinentes référençant un message CORE, en EXCLUANT les HUBS génériques
    // (dispatchers d'action, GameMain… qui touchent > seuil messages distincts de tous les modes → pollueraient
    // le contrat §A/B). Ces hubs restent visibles dans le RAPPORT (émetteurs) — utile pour savoir QUI envoie.
    TreeSet<String> union = new TreeSet<>(seedClasses);
    TreeSet<String> hubsExcluded = new TreeSet<>();
    for (String m : core) for (String c : refsOf(m)) {
      if (seedClasses.contains(c)) continue;
      if (isHub(c, hubThreshold)) { hubsExcluded.add(c); continue; }
      if (relevant(c)) union.add(c);
    }

    if (listOnly) { System.out.println(String.join(",", union)); return; }

    System.out.println("=== GRAPHE DE MESSAGES DU MODE — graine " + seed + " (token « " + token + " ») ===");
    System.out.println("(classes scannées : " + scanned + " ; graine : " + seedClasses.size()
        + " ; seuil générique : " + generic + " ; ancré bytecode, #74 levier A)\n");

    System.out.println("MESSAGES CORE DU MODE (nom = token) — qui les ENVOIE / les LIT :");
    System.out.println("  (un émetteur HORS graine = classe à inclure que la portée PAR-CLASSE de ScreenContract ratait)");
    for (String m : core) {
      TreeSet<String> em = emitters.getOrDefault(m, new TreeSet<>());
      TreeSet<String> rd = readers.getOrDefault(m, new TreeSet<>());
      System.out.println("  • " + simple(m) + "   [new par " + em.size() + " · lu par " + rd.size() + "]");
      TreeSet<String> emOutside = new TreeSet<>();
      for (String c : em) if (!seedClasses.contains(c)) emOutside.add(simple(c));
      if (!emOutside.isEmpty()) System.out.println("        ENVOYÉ hors graine : " + emOutside);
    }
    System.out.println();

    if (!shared.isEmpty()) {
      System.out.println("MESSAGES PARTAGÉS (roster/combat commun, sans le token) — NON utilisés pour l'union :");
      StringBuilder s = new StringBuilder("  ");
      for (String m : shared) s.append(simple(m)).append(" ");
      System.out.println(s.toString().trim() + "\n");
    }
    if (!genericMsgs.isEmpty()) {
      System.out.println("MESSAGES GÉNÉRIQUES (partagés > " + generic + " classes) — exclus de l'union :");
      StringBuilder g = new StringBuilder("  ");
      for (String m : genericMsgs) g.append(simple(m)).append(" ");
      System.out.println(g.toString().trim() + "\n");
    }

    if (!hubsExcluded.isEmpty()) {
      System.out.println("HUBS GÉNÉRIQUES (dispatchers > " + hubThreshold + " messages) — émettent un message du mode "
          + "mais EXCLUS de l'union (pollueraient §A/B) ; à router à la main si l'un envoie une requête du mode :");
      for (String c : hubsExcluded) System.out.println("  ⚙ " + c + "  [" + msgsPerClass.get(c).size() + " msgs]");
      System.out.println();
    }

    System.out.println("CLASSES DU MODE (union graine + référents des messages CORE, hubs exclus) : " + union.size());
    for (String c : union) System.out.println("  · " + c + (seedClasses.contains(c) ? "" : "   (hors graine)"));
    System.out.println();

    System.out.println("→ À passer à ScreenContract (contract.sh) pour le CONTRAT COMPLET du mode :");
    System.out.println(String.join(",", union));

    if (logic) logicRecon(jar, union);
  }

  /** LEVIER C1 (recensement de la LOGIQUE headless) : pour les classes *Helper/*Stats du mode, liste les méthodes
   *  STATIQUES prenant un IUser — ce sont les points d'entrée que le SERVEUR peut EXÉCUTER headless (comme
   *  ClientOracle exécute QuestHelper/ChestHelper, comme le serveur exécute déjà CampaignHelper/ArenaHelper).
   *  On EXÉCUTE ces méthodes du jeu (PRINCIPLES §3), jamais on ne réécrit la règle. NB : la GL-freeness n'est pas
   *  prouvée ici (elle se confirme à l'exécution, cf. ClientOracle/becomeMainThread) — c'est un RECENSEMENT. */
  static void logicRecon(String jar, TreeSet<String> union) throws IOException {
    TreeSet<String> want = new TreeSet<>();
    for (String c : union) if (c.endsWith("Helper") || c.endsWith("Stats")) want.add(c);
    System.out.println();
    System.out.println("C1. LOGIQUE HEADLESS DU MODE — méthodes STATIQUES prenant un IUser (points d'entrée à EXÉCUTER,");
    System.out.println("    jamais réécrire §3 ; GL-freeness à confirmer à l'exécution comme ClientOracle) :");
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
      ZipEntry e;
      TreeMap<String, TreeSet<String>> byClass = new TreeMap<>();
      while ((e = zis.getNextEntry()) != null) {
        if (!e.getName().endsWith(".class")) continue;
        String bin = e.getName().substring(0, e.getName().length() - 6);
        if (!want.contains(bin)) continue;
        TreeSet<String> methods = byClass.computeIfAbsent(bin, k -> new TreeSet<>());
        new ClassReader(zis.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
          @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
            if ((acc & Opcodes.ACC_STATIC) != 0 && (acc & Opcodes.ACC_PUBLIC) != 0
                && d.contains("game/objects/IUser"))
              methods.add(n + prettyParams(d));
            return null;
          }
        }, ClassReader.SKIP_CODE);
      }
      if (byClass.isEmpty()) { System.out.println("  (aucune méthode statique IUser dans les Helper/Stats du mode)"); return; }
      for (Map.Entry<String, TreeSet<String>> en : byClass.entrySet()) {
        if (en.getValue().isEmpty()) continue;
        System.out.println("  " + simple(en.getKey()) + " :");
        for (String m : en.getValue()) System.out.println("        " + m);
      }
    }
  }

  /** Rend « (Type1, Type2) » depuis un descripteur de méthode (types d'objet en nom simple). */
  static String prettyParams(String desc) {
    int end = desc.indexOf(')'); StringBuilder sb = new StringBuilder("(");
    int i = 1; boolean first = true;
    while (i < end) {
      int arr = 0; while (desc.charAt(i) == '[') { arr++; i++; }
      char c = desc.charAt(i);
      String t;
      if (c == 'L') { int semi = desc.indexOf(';', i); String cn = desc.substring(i + 1, semi); t = cn.substring(cn.lastIndexOf('/') + 1); i = semi + 1; }
      else { t = prim(c); i++; }
      for (int k = 0; k < arr; k++) t += "[]";
      if (!first) sb.append(", "); sb.append(t); first = false;
    }
    return sb.append(")").toString();
  }
  static String prim(char c) {
    switch (c) { case 'Z': return "boolean"; case 'B': return "byte"; case 'C': return "char"; case 'S': return "short";
      case 'I': return "int"; case 'J': return "long"; case 'F': return "float"; case 'D': return "double"; default: return "?"; }
  }

  /** Classe pertinente pour l'union : UI, ou un helper/stats/manager/objet de logique de jeu (pas un message). */
  static boolean relevant(String bin) {
    if (bin.startsWith(MSG) || nonProd(bin)) return false;
    if (bin.startsWith("com/perblue/heroes/ui/")) return true;
    return bin.endsWith("Helper") || bin.endsWith("Stats") || bin.endsWith("Manager")
        || bin.endsWith("Screen") || bin.endsWith("Window") || bin.endsWith("Display") || bin.endsWith("Card")
        || bin.startsWith("com/perblue/heroes/game/logic/");
  }

  /** Classe hors PRODUCTION (debug/test) — jamais pertinente pour implémenter un mode. */
  static boolean nonProd(String bin) {
    return bin.contains("/debug/") || simple(bin).contains("Debug") || simple(bin).contains("Test");
  }

  /** Nom de l'outer (retire le suffixe $Interne) pour regrouper les classes internes sous leur écran/helper. */
  static String outer(String bin) { int i = bin.indexOf('$'); return i < 0 ? bin : bin.substring(0, i); }
  static String simple(String bin) { int i = bin.lastIndexOf('/'); return bin.substring(i + 1); }

  /** Union des classes émettrices + lectrices d'un message. */
  static TreeSet<String> refsOf(String m) {
    TreeSet<String> r = new TreeSet<>();
    if (emitters.get(m) != null) r.addAll(emitters.get(m));
    if (readers.get(m)  != null) r.addAll(readers.get(m));
    return r;
  }
  static int refCount(String m) { return refsOf(m).size(); }

  /** Hub générique : classe (outer) référençant > seuil messages DISTINCTS (dispatcher d'action, GameMain…). */
  static boolean isHub(String outer, int threshold) {
    TreeSet<String> s = msgsPerClass.get(outer);
    return s != null && s.size() > threshold;
  }

  /** Dernier segment d'un préfixe de package (com/perblue/heroes/ui/surge/ → surge). */
  static String lastSegment(String prefix) {
    String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    int i = p.lastIndexOf('/'); return i < 0 ? p : p.substring(i + 1);
  }

  /** Visiteur : messages top-level CONSTRUITS (new) et LUS (GETFIELD / getter ()T) par une classe. */
  static class RefVisitor extends ClassVisitor {
    final Set<String> emit, read;
    RefVisitor(Set<String> emit, Set<String> read) { super(Opcodes.ASM9); this.emit = emit; this.read = read; }
    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
      return new MethodVisitor(Opcodes.ASM9) {
        @Override public void visitTypeInsn(int op, String type) {
          if (op == Opcodes.NEW && isMsg(type)) emit.add(type);
        }
        @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
          if (op == Opcodes.GETFIELD && isMsg(owner)) read.add(owner);
        }
        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
          if (isMsg(owner) && (name.startsWith("get") || name.startsWith("is")) && desc.startsWith("()"))
            read.add(owner);
        }
      };
    }
  }

  /** Message top-level (pas une classe interne anonyme $1). */
  static boolean isMsg(String bin) { return bin.startsWith(MSG) && bin.indexOf('$') < 0; }
}
