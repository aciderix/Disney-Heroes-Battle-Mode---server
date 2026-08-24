import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.logic.ContestHelper;
import java.util.*;

/**
 * CONTEST gap C — contest de GUILDE (agrégé). Un {@code Contest.isGuildContest()} classe les GUILDES ; son score = la SOMME
 * des points de contest per-user de ses MEMBRES ({@code isAggregateContest()}). Le crédit per-membre passe par l'extension
 * serveur ({@link ServerContestExtension}, installée par {@code ServerContext}) → {@code recordGuildContestTasks} →
 * {@code user.getGuildContestData(id)} (exige {@code User.guildID>0}) → barème DU JEU. L'agrégat + le classement de guildes
 * sont SERVEUR-AUTORITATIFS ({@link ServerContestData#guildAggregate}/{@link ServerContestData#guildRankings}).
 *
 * <p>Scénario : Guilde G1 (membres A, C) et G2 (membre D) sur un contest BATTLE_WON:10. A gagne 2 combats (20), C 1 (10)
 * ⇒ G1=30 ; D gagne 1 (10) ⇒ G2=10. On vérifie l'agrégat, le classement (G1 rang 1, G2 rang 2), {@code yourGuildInfo}, et
 * le round-trip wire.
 */
public final class ContestGuildTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestguild] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  /** Fait gagner {@code n} combats de campagne à {@code su} (crédite le contest via le vrai hook du jeu). Persiste. */
  static void win(UserStore store, ServerUser su, int n) throws Exception {
    com.perblue.heroes.game.objects.User u = su.gameUser();
    ServerContext.bind(u, u.getIndividual());
    for (int i = 0; i < n; i++)
      ServerContestData.record(su, u, gu ->
          ContestHelper.onCampaignAttack(gu, GameMode.CAMPAIGN, CombatOutcome.WIN, new ArrayList<>(), new ArrayList<>()));
    store.save(su);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 990_010L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, true, true, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    com.perblue.common.specialevent.components.Contest ct =
        (com.perblue.common.specialevent.components.Contest) ev.getComponent(com.perblue.common.specialevent.components.Contest.class);
    check(ct.isGuildContest() && ct.isAggregateContest(), "contest de guilde agrégé");
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();

    java.io.File tmp = java.io.File.createTempFile("dh-contest-guild", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // --- Guilde G1 : fondateur A + membre C ---
      ServerUser A = ServerUser.newPlayer(9901L, 1); A.grantHero(UnitType.RALPH); A.giveResource(ResourceType.GOLD, 5000);
      long g1id = store.nextGuildID(1);
      ServerGuild g1 = A.createGuild(mk("Aggregators"), g1id);
      ServerUser C = ServerUser.newPlayer(9902L, 1);
      C.joinGuildAs(g1id, GuildRole.MEMBER); g1.memberIDs.add(C.userID);
      store.saveGuild(g1);
      // --- Guilde G2 : fondateur D ---
      ServerUser D = ServerUser.newPlayer(9903L, 1); D.grantHero(UnitType.RALPH); D.giveResource(ResourceType.GOLD, 5000);
      long g2id = store.nextGuildID(1);
      ServerGuild g2 = D.createGuild(mk("Challengers"), g2id);
      store.saveGuild(g2);
      // persiste les membres (fondateurs/membre) avant crédit
      store.save(A); store.save(C); store.save(D);

      // --- Crédit : A 2 combats (20), C 1 (10) ⇒ G1=30 ; D 1 (10) ⇒ G2=10 ---
      win(store, A, 2);
      win(store, C, 1);
      win(store, D, 1);

      long aggG1 = ServerContestData.guildAggregate(store, store.loadGuild(1, g1id), cid);
      long aggG2 = ServerContestData.guildAggregate(store, store.loadGuild(1, g2id), cid);
      check(aggG1 == 30L, "G1 agrégat = 20(A) + 10(C) = 30 (=" + aggG1 + ")");
      check(aggG2 == 10L, "G2 agrégat = 10(D) (=" + aggG2 + ")");
      System.out.println("[contestguild] agrégats : G1=" + aggG1 + " (A20+C10), G2=" + aggG2 + " (D10) ✔");

      // --- Classement de guildes (vu par A) : G1 rang 1 (30), G2 rang 2 (10) ---
      ServerContext.bind(A.gameUser(), A.gameUser().getIndividual());
      GuildContestRankings ra = ServerContestData.guildRankings(store, A, cid);
      check(ra.yourGuildInfo != null && ra.yourGuildInfo.points == 30L && ra.yourGuildInfo.rank == 1,
          "A : ta guilde score 30 rang 1 (" + (ra.yourGuildInfo == null ? "null" : ra.yourGuildInfo.points + "/" + ra.yourGuildInfo.rank) + ")");
      check(ra.topGuilds.size() == 2, "2 guildes classées (" + ra.topGuilds.size() + ")");
      check(((GuildContestRankingRow) ra.topGuilds.get(0)).points == 30L && ((GuildContestRankingRow) ra.topGuilds.get(1)).points == 10L,
          "top guildes trié 30 puis 10");
      check(ra.guildContestData != null && ra.guildContestData.progressPoints == 30L,
          "guildContestData agrégé = 30 (barre de progression, =" + (ra.guildContestData == null ? "null" : ra.guildContestData.progressPoints) + ")");
      System.out.println("[contestguild] classement vu par A : G1 score 30 rang 1, G2 score 10 rang 2, guildContestData=30 ✔");

      // --- Vu par D (membre de G2) : ta guilde score 10 rang 2 ---
      ServerContext.bind(D.gameUser(), D.gameUser().getIndividual());
      GuildContestRankings rd = ServerContestData.guildRankings(store, D, cid);
      check(rd.yourGuildInfo != null && rd.yourGuildInfo.points == 10L && rd.yourGuildInfo.rank == 2,
          "D : ta guilde score 10 rang 2 (" + (rd.yourGuildInfo == null ? "null" : rd.yourGuildInfo.points + "/" + rd.yourGuildInfo.rank) + ")");
      System.out.println("[contestguild] classement vu par D : ta guilde score 10 rang 2 ✔");

      // --- Round-trip WIRE du message GuildContestRankings ---
      WireCheck.assertRoundTrips(ra);
      System.out.println("[contestguild] round-trip wire GuildContestRankings OK ✔");
    }

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestguild] OK — contest de guilde : crédit per-membre (extension) + agrégat + classement serveur-autoritatif. [gap C headless]");
  }
}
