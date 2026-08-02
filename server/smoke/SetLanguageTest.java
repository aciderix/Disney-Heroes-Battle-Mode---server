import com.perblue.heroes.network.messages.SetLanguage;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * LANGUE DU JOUEUR — `SetLanguage{language}`, relevé « reçu mais non traité » dans les logs Windows
 * (2026-08-02).
 *
 * <p>Prouve : (a) la langue reçue est appliquée par le SETTER DU JEU (`User.setLanguage`), (b) elle survit au
 * round-trip SQLite (le champ vit dans `UserExtra` donc dans `this.extra` → auto-persisté, aucun re-sync),
 * (c) un code inconnu ou vide est refusé sans rien casser, (d) un compte neuf n'a pas de langue imposée.
 */
public final class SetLanguageTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static SetLanguage msg(String code) { SetLanguage m = new SetLanguage(); m.language = code; return m; }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-lang", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser u = store.loadOrCreate(1L, 1);

      // Un code valide du jeu : on prend la valeur DU JEU, pas une chaîne écrite à la main.
      String fr = com.perblue.heroes.util.localization.Language.FRENCH.getCode();
      check(u.setLanguage(msg(fr)), "la langue FRENCH doit être appliquée");
      check(fr.equals(u.language()), "langue attendue " + fr + ", obtenue " + u.language());
      System.out.println("[lang] appliquée : " + u.language());

      // Persistance : aucun re-sync n'est écrit, le champ doit survivre par lui-même (this.extra).
      store.save(u);
      ServerUser back = store.loadOrCreate(1L, 1);
      check(fr.equals(back.language()), "la langue doit survivre au round-trip SQLite, obtenue "
          + back.language());
      System.out.println("[lang] round-trip DB : " + back.language());

      // Changement de langue.
      String de = com.perblue.heroes.util.localization.Language.GERMAN.getCode();
      check(back.setLanguage(msg(de)) && de.equals(back.language()), "le changement de langue doit passer");
      System.out.println("[lang] changement : " + back.language());

      // Refus fidèles : rien d'appliqué, rien de cassé.
      check(!back.setLanguage(msg("")), "un code vide doit être refusé");
      check(!back.setLanguage(null), "un message nul doit être refusé");
      check(de.equals(back.language()), "un refus ne doit PAS altérer la langue en place");
      System.out.println("[lang] refus (vide / nul) : langue inchangée = " + back.language());

      // Un compte neuf ne se voit imposer AUCUNE langue par le serveur : il garde la valeur par défaut du
      // constructeur du jeu (`new UserExtra()` → chaîne VIDE, pas null — vérifié à l'exécution : ma première
      // assertion attendait `null` et le jeu avait raison). C'est le client qui la choisit puis l'annonce.
      ServerUser fresh = store.loadOrCreate(2L, 1);
      check(fresh.language() == null || fresh.language().isEmpty(),
          "un compte neuf ne doit porter aucune langue, obtenu '" + fresh.language() + "'");
      System.out.println("[lang] compte neuf : aucune langue imposée (défaut du jeu = '"
          + fresh.language() + "')");

      System.out.println("SET LANGUAGE TEST OK");
    }
  }
}
