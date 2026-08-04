import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerSurge;
import dhserver.ServerSurgeState;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * SURGE (#72) incrément 2 — ÉTAT PARTAGÉ DE GUILDE : {@link SurgeData} par (guilde, surgeID), membres dérivés du
 * ROSTER, persisté dans {@code shard_state}, remis à zéro quand le surgeID change. Vérifie : membres = roster,
 * round-trip wire (défaut nº3), round-trip DB (persistance), et remise à zéro sur changement de surge.
 */
public final class SurgeStateTest {

  static void check(boolean cond, String msg) { if (!cond) throw new AssertionError("[surge-state] " + msg); }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-surge-state", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // Guilde avec 2 membres (chef + 1).
      ServerUser ruler = ServerUser.newPlayer(1L, 1);
      ruler.giveResource(ResourceType.GOLD, 5000);          // createGuild débite 2000 GOLD (chargeForCreation)
      ServerGuild g = ruler.createGuild(mk("Surge Legion"), store.nextGuildID(1));
      ServerUser m2 = ServerUser.newPlayer(2L, 1);
      if (!g.memberIDs.contains(2L)) g.memberIDs.add(2L);
      store.save(ruler); store.save(m2); store.saveGuild(g);

      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      long curID = ServerSurge.currentSurgeID(now);

      // 1. Construction : surgeID courant + membres = roster.
      SurgeData d = ServerSurgeState.loadOrReset(store, g, now);
      check(d.surgeID == curID, "surgeID doit être celui du surge courant (" + curID + "), obtenu " + d.surgeID);
      check(d.members.size() == g.memberIDs.size(),
          "membres = roster : attendu " + g.memberIDs.size() + ", obtenu " + d.members.size());
      java.util.Set<Long> ids = new java.util.HashSet<>();
      for (Object o : d.members) ids.add(((SurgeMemberSummary) o).user.iD);
      check(ids.contains(1L) && ids.contains(2L), "les identités des membres doivent venir du roster : " + ids);
      check(d.opponents != null && d.objectives != null && d.log != null && d.surgeScoringInfo != null,
          "conteneurs/sous-messages non nuls (wire-sûr)");

      // 2. Round-trip WIRE (défaut nº3) — l'état s'écrit et se relit sur le fil.
      WireCheck.assertRoundTrips(d);

      // 3. Round-trip DB : recharger (même surgeID) rend l'état PERSISTÉ (pas reconstruit) avec les mêmes membres.
      SurgeData d2 = ServerSurgeState.loadOrReset(store, g, now);
      check(d2.surgeID == curID && d2.members.size() == d.members.size(),
          "l'état doit survivre au round-trip DB (surgeID/membres)");

      // 4. Remise à zéro sur changement de surge : on force un état stocké avec un AUTRE surgeID → loadOrReset
      //    doit détecter le décalage et reconstruire pour le surge courant.
      ServerSurgeState.save(store, g, curID + 987654L, d);
      SurgeData d3 = ServerSurgeState.loadOrReset(store, g, now);
      check(d3.surgeID == curID, "un surgeID stocké différent doit déclencher la reconstruction (→ " + curID + ")");

      System.out.println("[surge-state] OK — SurgeData partagé (surgeID=" + curID + ", membres=" + d.members.size()
          + ") : roster + round-trip wire + round-trip DB + reset sur nouveau surge — #72 incrément 2");
    }
  }
}
