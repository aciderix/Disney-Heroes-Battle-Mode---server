import com.perblue.heroes.network.messages.UnitType;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * DEV — met le compte de test (1,1) dans un état COHÉRENT avec son niveau d'équipe (monté artificiellement à TL65) :
 *  (a) roster suffisant pour l'arène (≥5 héros → équipe de défense fight pit) ;
 *  (b) tutoriels TERMINÉS (un vrai TL65 a déjà fait tous les tutos de déblocage) — sinon un tuto « en attente »
 *      (ex. SAVED_LINEUPS débloqué à TL20) se déclenche sur l'écran de défense d'arène qui n'a pas sa cible et
 *      DEADLOCKE. C'est une mise en état du COMPTE (comme SetTeamLevel), pas un comportement serveur.
 */
public final class GrantArenaRoster {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    try (UserStore store = new UserStore("server/data/dh-server.db")) {
      ServerUser su = store.loadOrCreate(1L, 1);
      for (UnitType t : new UnitType[]{UnitType.WOODY, UnitType.SULLEY, UnitType.HERCULES,
          UnitType.GENIE, UnitType.STITCH}) {
        try { su.grantHero(t); System.out.println("[setup] +" + t); }
        catch (Throwable x) { System.out.println("[setup] " + t + " deja la/echec: " + x); }
      }
      int done = su.completeAllTutorials();
      System.out.println("[setup] tutoriels marques termines: " + done);
      store.save(su);
      System.out.println("[setup] compte sauve (roster + tutos termines)");
    }
  }
}
