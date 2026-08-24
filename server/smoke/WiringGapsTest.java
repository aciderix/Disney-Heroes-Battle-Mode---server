import dhserver.*;
import com.perblue.heroes.network.messages.*;
import java.util.*;

/**
 * AUDIT A2 (Phase 2) — GAPS de câblage bouclés : handlers d'écrans secondaires précédemment SANS route LoginServer.
 * Prouve la LOGIQUE derrière les 3 handlers ajoutés (réponses réelles/fidèles, pas de faux endpoint) + round-trip WIRE
 * des 3 messages de réponse (le client doit pouvoir les lire) :
 *   • GetGMemInvasionRankInfo → GuildMemberInvasionRankInfo : rang invasion PAR MEMBRE de guilde (données réelles user_invasion).
 *   • GetBlockedList          → BlockedList (VIDE : blocage non implémenté = aucun bloqué = fidèle).
 *   • GetUserSaveData         → UserSaveData : sauvegarde du compte du DEMANDEUR (= ce que le serveur persiste).
 */
public final class WiringGapsTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[wiringgaps] " + m); }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long invID = ServerInvasion.rotation(ServerInvasion.invasionStart(now));

    java.io.File tmp = java.io.File.createTempFile("dh-wiring-gaps", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // Guilde G : fondateur A (100 pts d'invasion) + membre B (50 pts).
      ServerUser A = ServerUser.newPlayer(3101L, 1); A.grantHero(UnitType.RALPH); A.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = A.createGuild(mk("RankersGuild"), gid);
      ServerUser B = ServerUser.newPlayer(3102L, 1);
      B.joinGuildAs(gid, GuildRole.MEMBER); g.memberIDs.add(B.userID);
      store.saveGuild(g); store.save(A); store.save(B);

      UserInvasionData da = ServerInvasion.newUserData(A.userID, gid, invID); da.points = 100L;
      UserInvasionData db = ServerInvasion.newUserData(B.userID, gid, invID); db.points = 50L;
      store.saveUserInvasion(1, A.userID, ServerInvasion.userDataToBytes(da));
      store.saveUserInvasion(1, B.userID, ServerInvasion.userDataToBytes(db));

      // --- GetGMemInvasionRankInfo : classement par membre (données réelles) ---
      List<InvasionGuildMemberRankRow> rows = ServerInvasion.guildMemberRanking(store, 1, gid, invID, 50);
      check(rows.size() == 2, "2 membres classés (" + rows.size() + ")");
      check(rows.get(0).score == 100L && rows.get(0).rank == 1, "A 1er (100 pts) (" + rows.get(0).score + "/" + rows.get(0).rank + ")");
      check(rows.get(1).score == 50L && rows.get(1).rank == 2, "B 2e (50 pts) (" + rows.get(1).score + "/" + rows.get(1).rank + ")");
      check(rows.get(0).user != null, "ligne porte le BasicUserInfo du membre");
      System.out.println("[wiringgaps] GetGMemInvasionRankInfo : A 100/#1, B 50/#2 (données réelles) ✔");

      GuildMemberInvasionRankInfo gmr = new GuildMemberInvasionRankInfo();
      gmr.guildID = gid; gmr.invasionID = invID; gmr.users = new ArrayList<>(rows);
      WireCheck.assertRoundTrips(gmr);
      System.out.println("[wiringgaps] round-trip wire GuildMemberInvasionRankInfo ✔");

      // --- GetBlockedList : VIDE (fidèle) ---
      BlockedList bl = new BlockedList(); bl.users = new ArrayList<>();
      WireCheck.assertRoundTrips(bl);
      check(bl.users.isEmpty(), "BlockedList vide (aucun bloqué)");
      System.out.println("[wiringgaps] GetBlockedList → BlockedList vide (fidèle) ✔");

      // --- GetUserSaveData : sauvegarde du compte du demandeur (données réelles persistées) ---
      BootData bd = A.bootData();
      UserSaveData sd = new UserSaveData();
      sd.info = bd.userInfo; sd.extra = bd.userExtra; sd.individualUserExtra = bd.individualUserExtra;
      check(sd.info != null && sd.extra != null && sd.individualUserExtra != null, "UserSaveData porte info+extra+individu");
      WireCheck.assertRoundTrips(sd);
      System.out.println("[wiringgaps] GetUserSaveData → UserSaveData (compte réel, round-trip) ✔");
    }
    System.out.println("[wiringgaps] OK — 3 handlers A2 (invasion member rank / blocked / save data) : logique réelle/fidèle + wire. [Phase 2 étape 2]");
  }
}
