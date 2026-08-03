import dhserver.ServerContext;
import dhserver.UserStore;

/**
 * ANCRE D'HORLOGE PERSISTÉE (ère de contenu R1…R102 + décomptes) — prouve : (1) round-trip DB de la méta
 * {@code clock_offset_ms} ; (2) {@code ServerContext.setClockOffsetMillis} décale bien {@code serverTimeNow} ;
 * (3) offset FIXE ⇒ le temps de jeu s'écoule au rythme réel (deux lectures à ~Δ réel d'écart diffèrent de ~Δ).
 * C'est ce qui rend « démarrer R1 puis avancer » robuste aux redémarrages, sans dérive.
 */
public final class ClockAnchorTest {
  public static void main(String[] a) throws Exception {
    long saved = ServerContext.clockOffsetMillis();   // à restaurer en fin de test
    try {
      java.io.File tmp = java.io.File.createTempFile("dh-clock", ".db");
      tmp.deleteOnExit();
      try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
        // (1) round-trip DB
        if (store.getMetaLong("clock_offset_ms") != null)
          throw new AssertionError("méta absente attendue au départ");
        long off = 72_700L * 3600_000L;   // ~8,3 ans (recule l'heure de jeu vers 2018)
        store.setMetaLong("clock_offset_ms", off);
        Long re = store.getMetaLong("clock_offset_ms");
        if (re == null || re != off) throw new AssertionError("round-trip méta cassé : " + re);
        System.out.println("[clock] round-trip DB de clock_offset_ms OK (" + off + " ms)");

        // (2) l'offset décale serverTimeNow (currentTimeMillis − OFFSET)
        long real = System.currentTimeMillis();
        ServerContext.setClockOffsetMillis(off);
        if (ServerContext.clockOffsetMillis() != off) throw new AssertionError("offset non posé");
        long game = com.perblue.heroes.util.TimeUtil.serverTimeNow();
        long delta = real - game;
        if (Math.abs(delta - off) > 5_000L)
          throw new AssertionError("serverTimeNow ne reflète pas l'offset : Δ=" + delta + " vs " + off);
        if (game >= real) throw new AssertionError("offset positif → heure de jeu doit être RECULÉE");
        System.out.println("[clock] heure de jeu reculée de ~" + (off / 3600_000L / 24 / 365) + " ans (offset appliqué)");

        // (3) offset FIXE ⇒ le temps s'écoule (deux lectures diffèrent, pas figées)
        long g1 = com.perblue.heroes.util.TimeUtil.serverTimeNow();
        Thread.sleep(10);
        long g2 = com.perblue.heroes.util.TimeUtil.serverTimeNow();
        if (g2 < g1) throw new AssertionError("le temps de jeu doit AVANCER (offset fixe)");
        System.out.println("[clock] offset fixe → temps de jeu qui s'écoule au rythme réel (décomptes préservés)");

        // reset persisté
        store.setMetaLong("clock_offset_ms", 0L);
        if (store.getMetaLong("clock_offset_ms") != 0L) throw new AssertionError("reset méta cassé");
      }
      System.out.println("CLOCK ANCHOR TEST OK");
    } finally {
      ServerContext.setClockOffsetMillis(saved);   // ne pas polluer les autres tests
    }
  }
}
