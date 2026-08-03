import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.network.messages.MessageFactory;

/**
 * OUTIL D'INDUSTRIALISATION (#73) — garde-fou RÉUTILISABLE contre le défaut nº3 (« typage wire faux qui
 * explose À L'ÉCRITURE, invisible headless » : g44 {@code WarDefense.defenders} attend {@code WarHeroData} et
 * non {@code WarHeroSummary} ; g45 {@code activeCars} attend {@code WarAttackCarBonus} et non {@code WarCarType}).
 *
 * <p>Écrit un message sur le fil AU FORMAT EXACT DU JEU ({@code writeAll}) puis le relit
 * ({@code MessageFactory.readMessage}). Une List/Map remplie du mauvais type lève une {@code ClassCastException}
 * à l'écriture — que ce garde-fou transforme en échec de test CLAIR. À appeler dans CHAQUE test de handler sur
 * la réponse serveur→client construite (comme {@code WarAttackTest} le fait déjà à la main).
 *
 * Usage test : {@code WireCheck.assertRoundTrips(response);}
 */
public final class WireCheck {
  private WireCheck() {}

  /** Écrit puis relit le message ; renvoie le message relu. Lève si l'écriture wire échoue. */
  public static GruntMessage roundTrip(GruntMessage m) throws Exception {
    GruntOutputStream out = new GruntOutputStream();
    m.writeAll(out);   // ← explose ici (ClassCastException) si un champ List/Map contient le mauvais type wire
    byte[] bytes = out.toByteArray();
    return MessageFactory.getInstance().readMessage(new GruntInputStream(bytes));
  }

  /** Assertion : le message se sérialise ET se relit dans le même type. Défaut nº3 attrapé de façon déterministe. */
  public static void assertRoundTrips(GruntMessage m) {
    GruntMessage back;
    try {
      back = roundTrip(m);
    } catch (Throwable e) {
      throw new AssertionError("round-trip wire ÉCHOUÉ pour " + m.getClass().getSimpleName()
          + " (typage wire faux ? cf. défaut nº3) : " + e, e);
    }
    if (!back.getClass().equals(m.getClass()))
      throw new AssertionError("round-trip wire : type relu " + back.getClass().getSimpleName()
          + " != écrit " + m.getClass().getSimpleName());
  }

  /** Petit self-test (sans argument) : round-trip d'un BootData, pour intégrer WireCheck à la régression. */
  public static void main(String[] a) throws Exception {
    dhserver.ServerContext.init();
    com.perblue.heroes.network.messages.BootData bd = new com.perblue.heroes.network.messages.BootData();
    assertRoundTrips(bd);
    System.out.println("[wirecheck] BootData round-trip OK — garde-fou opérationnel (#73)");
  }
}
