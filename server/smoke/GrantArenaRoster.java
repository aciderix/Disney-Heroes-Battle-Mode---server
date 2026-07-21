import com.perblue.heroes.network.messages.UnitType;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/** DEV : dote le compte (1,1) d'un roster suffisant pour l'arène (≥5 héros → équipe de défense fight pit). */
public final class GrantArenaRoster {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    try (UserStore store = new UserStore("server/data/dh-server.db")) {
      ServerUser su = store.loadOrCreate(1L, 1);
      for (UnitType t : new UnitType[]{UnitType.WOODY, UnitType.SULLEY, UnitType.HERCULES,
          UnitType.GENIE, UnitType.STITCH}) {
        try { su.grantHero(t); System.out.println("[roster] +" + t); }
        catch (Throwable x) { System.out.println("[roster] " + t + " deja la/echec: " + x); }
      }
      store.save(su);
      System.out.println("[roster] sauve");
    }
  }
}
