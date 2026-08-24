import dhserver.*;
import com.perblue.heroes.network.messages.*;
/**
 * DEV (hors régression, prend des arguments) — SEED d'un contest de GUILDE pour la vérif EN JEU (gap C).
 * Ajoute {@code memberID} à la guilde {@code guildID} et crédite directement son blob de contest per-user
 * ({@code rankPoints=progressPoints=points}) pour {@code contestID} → l'agrégat de la guilde inclut ce membre.
 * Usage : ContestGuildSeed &lt;guildID&gt; &lt;contestID&gt; &lt;memberID&gt; &lt;points&gt; (db = server/data/dh-server.db).
 */
public final class ContestGuildSeed {
  public static void main(String[] a) throws Exception {
    // args: guildID contestID memberID points
    long guildID = Long.parseLong(a[0]), cid = Long.parseLong(a[1]), mid = Long.parseLong(a[2]);
    long pts = Long.parseLong(a[3]);
    ServerContext.init();
    try (UserStore store = new UserStore("server/data/dh-server.db")) {
      ServerGuild g = store.loadGuild(1, guildID);
      if (g == null) { System.out.println("[seed] guilde introuvable"); return; }
      ServerUser m = store.loadIfExists(mid, 1);
      if (m == null) { m = ServerUser.newPlayer(mid, 1); System.out.println("[seed] membre " + mid + " créé"); }
      m.joinGuildAs(guildID, GuildRole.MEMBER);
      if (!g.memberIDs.contains(mid)) g.memberIDs.add(mid);
      // crédite directement le blob de contest per-user du membre (seed DEV, comme ArenaSeed)
      ContestData cd = ServerContestData.getContestData(m, cid);
      cd.rankPoints = pts; cd.progressPoints = pts;
      m.setContestData(m.contestDataOrNull());
      store.save(m); store.saveGuild(g);
      System.out.println("[seed] membre " + mid + " → guilde " + guildID + " (membres=" + g.memberIDs + "), contest " + cid + " rankPoints=" + ServerContestData.getContestData(m, cid).rankPoints + " [persisté]");
      // vérifie l'agrégat
      long agg = ServerContestData.guildAggregate(store, store.loadGuild(1, guildID), cid);
      System.out.println("[seed] agrégat guilde " + guildID + " = " + agg);
    }
  }
}
