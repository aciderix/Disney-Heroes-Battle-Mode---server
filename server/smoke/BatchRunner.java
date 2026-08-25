import java.lang.reflect.Method;
import java.util.*;

/**
 * LANCEUR DE TESTS EN UN SEUL PROCESS (mode RAPIDE de la régression) — amortit le coût DOMINANT {@code ServerContext.init}
 * (~1,7 s de parsing des ~274 `.tab` + chargement de {@code game-framed.jar}), payé UNE fois au lieu de 157 fois. Chaque test
 * de la suite expose un {@code public static void main(String[])} ne dépendant d'aucun argument ; on l'invoque par réflexion,
 * en RÉINITIALISANT l'état statique mutable partagé AVANT chaque test (offset d'horloge + événements opérateur) pour préserver
 * l'ISOLATION (ordre-indépendance). Un test PASSE s'il termine sans exception ni {@code AssertionError}. Sortie : une ligne
 * {@code PASS <t>} / {@code FAIL <t> :: <cause>} par test, puis un résumé. Les process-par-test restent le filet AUTORITATIF
 * (isolation JVM totale) ; ce mode est l'accélérateur de dev.
 *
 * Usage : BatchRunner <Test1> <Test2> …
 */
public final class BatchRunner {
  public static void main(String[] args) throws Exception {
    // Init unique, partagé par tous les tests (idempotent ensuite).
    dhserver.ServerContext.init();

    int pass = 0, fail = 0;
    List<String> failed = new ArrayList<>();
    java.io.PrintStream realOut = System.out;
    java.io.PrintStream nullOut = new java.io.PrintStream(new java.io.OutputStream() { public void write(int b) {} });

    for (String t : args) {
      resetSharedState();
      Throwable err = null;
      System.setOut(nullOut);   // musèle la sortie verbeuse des tests ; on ne garde que le verdict
      try {
        Method m = Class.forName(t).getMethod("main", String[].class);
        m.invoke(null, (Object) new String[0]);
      } catch (java.lang.reflect.InvocationTargetException e) {
        err = e.getCause() == null ? e : e.getCause();
      } catch (Throwable e) {
        err = e;
      } finally {
        System.setOut(realOut);
      }
      if (err == null) { pass++; realOut.println("PASS " + t); }
      else {
        fail++; failed.add(t);
        String cause = err.getClass().getSimpleName() + (err.getMessage() == null ? "" : ": " + err.getMessage());
        realOut.println("FAIL " + t + " :: " + cause);
      }
    }
    realOut.println("[batch] RÉSULTAT : " + pass + "/" + args.length + " verts");
    if (fail > 0) { realOut.println("[batch] ÉCHECS : " + String.join(" ", failed)); System.exit(1); }
  }

  /** Réinitialise l'état statique mutable partagé entre tests (ordre-indépendance) : offset d'horloge + événements opérateur. */
  static void resetSharedState() {
    try { dhserver.ServerContext.setClockOffsetMillis(0L); } catch (Throwable ignore) {}
    try { dhserver.ServerEvents.setOperatorEvents(java.util.Collections.emptyList()); } catch (Throwable ignore) {}
    try { dhserver.ServerEvents.installBootDefaults(); } catch (Throwable ignore) {}
  }
}
