import com.perblue.heroes.network.messages.*;
import dhserver.ServerArenaLadder;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * ARÈNE #41 — le CLASSEMENT (ladder) doit être PERSISTANT : généré à la 1re ouverture puis rechargé À L'IDENTIQUE
 * (mêmes bots/ids/rangs), et une mutation (points/fights/rang) doit SURVIVRE au round-trip DB. Prouve que rangs et
 * points ne s'évaporent plus (contrairement à la régénération stateless).
 */
public final class ArenaLadderTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-arena-ladder", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH); su.grantHero(UnitType.ELASTIGIRL); su.grantHero(UnitType.MOANA);
      store.save(su);

      String type = ArenaType.FIGHT_PIT.name();

      // 1re ouverture : pas de ladder → généré + persisté.
      if (store.loadArenaLadder(1, type) != null) throw new AssertionError("ladder devrait être absent au départ");
      ServerUser.ArenaResult r1 = su.arenaInfoWithLadder(ArenaType.FIGHT_PIT, store.loadArenaLadder(1, type));
      store.saveArenaLadder(1, type, r1.ladder);
      int n1 = r1.ladder.entries().size();
      java.util.List<Long> ids1 = ids(r1.ladder);
      System.out.println("[ladder] généré : " + n1 + " entrées, ids=" + ids1
          + " rank=" + r1.info.yourLeague.yourRank);
      if (n1 < 2) throw new AssertionError("ladder trop court");

      // 2e ouverture : ladder rechargé → MÊMES entrées (ordre/ids), pas une nouvelle génération.
      ServerArenaLadder loaded = store.loadArenaLadder(1, type);
      if (loaded == null) throw new AssertionError("ladder non persisté");
      if (!ids(loaded).equals(ids1)) throw new AssertionError("ladder rechargé ≠ généré (ids/ordre): "
          + ids(loaded) + " vs " + ids1);
      System.out.println("[ladder] rechargé identique : ids=" + ids(loaded));

      // MUTATION persistée : on change points + fights + on échange 2 rangs → doit survivre au reload.
      ServerArenaLadder.Entry top = loaded.entries().get(0);
      top.points = 123;
      top.remainingFightChances = 2;
      loaded.swap(0, 1);
      long newTopId = loaded.entries().get(0).id;
      store.saveArenaLadder(1, type, loaded);

      ServerArenaLadder reloaded = store.loadArenaLadder(1, type);
      if (reloaded.entries().get(0).id != newTopId)
        throw new AssertionError("swap de rang non persisté");
      // Retrouver l'entrée mutée (id de l'ex-top, désormais en position 1) et vérifier points/fights.
      ServerArenaLadder.Entry moved = null;
      for (ServerArenaLadder.Entry e : reloaded.entries()) if (e.points == 123) moved = e;
      if (moved == null || moved.remainingFightChances != 2)
        throw new AssertionError("points/fights non persistés");
      System.out.println("[ladder] mutation persistée : points=123 + fights=2 + swap de rang OK après reload");

      // L'ArenaInfo reconstruit depuis le ladder rechargé porte bien les points dans l'extra de row.
      ServerUser.ArenaResult r2 = su.arenaInfoWithLadder(ArenaType.FIGHT_PIT, reloaded);
      boolean seen = false;
      for (Object o : r2.info.yourLeague.players) {
        ArenaRow row = (ArenaRow) o;
        if (row.challengerExtra != null && row.challengerExtra.points == 123) seen = true;
      }
      if (!seen) throw new AssertionError("points persistés non reportés dans ArenaRowExtra");
      System.out.println("[ladder] OK — classement PERSISTANT (généré→rechargé identique, mutation rang/points/fights durable, reporté en ArenaRowExtra)");
    }
  }

  private static java.util.List<Long> ids(ServerArenaLadder l) {
    java.util.List<Long> out = new java.util.ArrayList<>();
    for (ServerArenaLadder.Entry e : l.entries()) out.add(e.id);
    return out;
  }
}
