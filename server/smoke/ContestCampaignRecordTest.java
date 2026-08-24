import dhserver.*;
import com.perblue.heroes.network.messages.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import java.util.*;

/**
 * CONTEST — GARDE DU CÂBLAGE DES HOOKS (§8) : prouve que le VRAI point d'entrée serveur {@code ServerUser.recordCampaignAttack}
 * crédite le contest EXACTEMENT une fois (pas de double-compte) alors même que {@code CampaignHelper.recordOutcome} appelle
 * LUI-MÊME {@code ContestHelper.onCampaignAttack} en interne. Le câblage = {@code ServerContestData.prepare} AVANT recordOutcome
 * (rend le blob per-user "blob-backed" → le hook interne le crédite) + {@code deliverEarnedProgressRewards} après. On vérifie
 * qu'un combat gagné = +10, deux = +20 (linéaire, une seule imputation par combat), et que ça persiste (round-trip DB).
 */
public final class ContestCampaignRecordTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[contestcamp] " + m); }
  static String item(String it, int q) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + it + "\",\"quantity\":" + q + "}"; }

  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000; s.startingEnergy = 0;
    return s;
  }

  static CampaignAttack winAttack() {
    CampaignAttack m = new CampaignAttack();
    m.campaignType = CampaignType.NORMAL; m.chapter = 1; m.level = 1; m.stagesCleared = 1;
    m.lootEarned = new ArrayList<>(); m.memoryChanges = new ArrayList<>();
    AttackBase base = new AttackBase();
    base.outcome = CombatOutcome.WIN; base.stars = 3;
    AttackLineupSummary lineup = new AttackLineupSummary();
    lineup.units = new ArrayList<AttackUnitSummary>();
    lineup.units.add(unit(UnitType.RALPH)); lineup.units.add(unit(UnitType.ELASTIGIRL)); lineup.units.add(unit(UnitType.FROZONE));
    base.attackers = new ArrayList<>(); base.attackers.add(lineup);
    base.defenders = new ArrayList<>();
    m.base = base;
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long cid = 991_010L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<ServerEvents.ContestTask> tasks = Arrays.asList(new ServerEvents.ContestTask("BATTLE_WON", 10, 1, -1, -1, "", ""));
    List<ServerEvents.ContestProgress> prog = Arrays.asList(new ServerEvents.ContestProgress(100L, Collections.singletonList(item("ACE_OF_SPADES", 5))));
    List<ServerEvents.ContestRank> ranks = Arrays.asList(new ServerEvents.ContestRank(true, 10, Collections.singletonList(item("ACE_OF_SPADES", 100))));
    SpecialEventInfo ev = ServerEvents.buildContestEvent(cid, false, false, tasks, prog, ranks, now - 1000, now + 7L * 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();

    java.io.File tmp = java.io.File.createTempFile("dh-contest-camp", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.FROZONE);
      com.perblue.heroes.game.objects.User bindU = su.gameUser();
      ServerContext.bind(bindU, bindU.getIndividual());

      // 1er combat gagné via le VRAI wrapper serveur → +10 (une seule imputation malgré le hook interne).
      su.recordCampaignAttack(winAttack());
      store.save(su);
      long p1 = ServerContestData.getContestData(su, cid).rankPoints;
      check(p1 == 10L, "1 combat gagné = 10 pts (pas de double-compte) (=" + p1 + ")");
      System.out.println("[contestcamp] recordCampaignAttack #1 : rankPoints=" + p1 + " ✔ (single, pas 20)");

      // 2e combat → +10 = 20 (linéaire).
      su.recordCampaignAttack(winAttack());
      store.save(su);
      long p2 = ServerContestData.getContestData(su, cid).rankPoints;
      check(p2 == 20L, "2 combats = 20 pts (=" + p2 + ")");
      System.out.println("[contestcamp] recordCampaignAttack #2 : rankPoints=" + p2 + " ✔");

      // Persistance : relu depuis la DB.
      ServerUser reloaded = store.loadIfExists(1L, 1);
      long pr = ServerContestData.getContestData(reloaded, cid).rankPoints;
      check(pr == 20L, "persisté en DB (=" + pr + ")");
      System.out.println("[contestcamp] round-trip DB : rankPoints=" + pr + " ✔");
    }

    // Fenêtre HEBDO (ancrage vendredi → jeudi).
    long[] w = ServerEvents.weeklyContestWindow(now);
    java.time.ZonedDateTime s = java.time.Instant.ofEpochMilli(w[0]).atZone(java.time.ZoneOffset.UTC);
    check(s.getDayOfWeek() == java.time.DayOfWeek.FRIDAY, "start = un vendredi (" + s.getDayOfWeek() + ")");
    check(s.getHour() == 0 && s.getMinute() == 0, "start = 00:00 UTC");
    check(w[1] - w[0] == 7L * 86_400_000L, "durée = 7 jours");
    check(w[0] <= now && now < w[1], "now dans la fenêtre");
    System.out.println("[contestcamp] fenêtre hebdo : " + s.toLocalDate() + " (vendredi) 00:00 → +7j ✔");

    ServerEvents.setOperatorEvents(new ArrayList<>());
    ServerEvents.install(new ArrayList<>());
    System.out.println("[contestcamp] OK — recordCampaignAttack crédite le contest 1×/combat (câblage prepare-avant), persiste ; fenêtre hebdo vendredi→jeudi. [hooks + ancrage]");
  }
}
