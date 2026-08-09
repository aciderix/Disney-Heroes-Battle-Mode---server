import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * SURGE (#72) incrément 5 — RAID : le serveur rejoue {@code SurgeHelper.recordRaid} (autoritatif) à réception de
 * l'{@code Action RAID_SURGE}. Le combat de raid est client-autoritatif ; le protocole = HeroLineupUpdate{SURGE}
 * (équipe, persistée) + SET_SEED{SURGE} + Action RAID_SURGE{extra TYPE=district}. Ce test exerce le cœur serveur
 * ({@code ServerSurgeState.applyRaid} → {@code ServerSurgeCombat.applyRaidOutcome} → recordRaid) : l'équipe SURGE
 * doit être posée d'abord (comme en jeu), puis un raid s'exécute headless sans planter, incrémente {@code raidsUsed}
 * (compteur partagé), et le {@code SurgeUpdate} round-trip wire. Params recordRaid prouvés au bytecode (§4).
 */
public final class SurgeRaidTest {

  static void check(boolean c, String m) { if (!c) throw new AssertionError("[surge-raid] " + m); }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-surge-raid", ".db"); tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser ruler = ServerUser.newPlayer(1L, 1);
      ruler.bootData().userInfo.basicInfo.teamLevel = 100;   // héros niv.40 non « au-dessus du team level »
      ruler.giveResource(ResourceType.GOLD, 5000);
      ruler.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5);
      ruler.grantHero(UnitType.ELASTIGIRL, Rarity.ORANGE, 40, 5);
      ruler.grantHero(UnitType.FROZONE, Rarity.ORANGE, 40, 5);
      ServerGuild g = ruler.createGuild(mk("Surge Raiders"), store.nextGuildID(1));
      store.save(ruler); store.saveGuild(g);
      // Pool d'adversaires (districts non vides).
      for (long id = 10L; id <= 12L; id++) {
        ServerUser r = ServerUser.newPlayer(id, 1);
        r.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5); r.grantHero(UnitType.ELASTIGIRL, Rarity.ORANGE, 40, 5);
        store.save(r);
      }

      // ÉQUIPE SURGE posée AVANT le raid (comme le HeroLineupUpdate{SURGE} en jeu) — sinon pas d'équipe à raider.
      HeroLineupUpdate hlu = new HeroLineupUpdate();
      hlu.type = HeroLineupType.SURGE;
      hlu.lineup = new HeroLineup();
      hlu.lineup.heroes = new java.util.ArrayList<>(java.util.Arrays.asList(
          UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE));
      check(ruler.applyHeroLineupUpdate(hlu), "l'équipe SURGE doit être acceptée/persistée");
      store.save(ruler);

      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      SurgeData data = ServerSurgeState.loadOrReset(store, g, now);
      DistrictType district = ServerSurgeMap.activeDistricts().get(0);

      SurgeMemberSummary before = ServerSurgeState.memberSummary(data, ruler.userID);
      int raidsBefore = before != null ? before.raidsUsed : 0;

      // RAID (issue) : ServerSurgeState.applyRaid → recordRaid autoritatif.
      SurgeUpdate up = ServerSurgeState.applyRaid(g, data, ruler, district);
      check(up != null && up.member != null, "SurgeUpdate + member non nuls");
      check(up.member.raidsUsed == raidsBefore + 1,
          "raidsUsed doit être incrémenté (avant " + raidsBefore + ", après " + up.member.raidsUsed + ")");
      check(up.surgePointDelta >= 0, "l'or du raid (delta storedGold) doit être ≥ 0, obtenu " + up.surgePointDelta);
      WireCheck.assertRoundTrips(up);

      // Persistance : sauver + relire → raidsUsed tient.
      ServerSurgeState.save(store, g, data.surgeID, data);
      SurgeData reloaded = ServerSurgeState.loadOrReset(store, g, now);
      SurgeMemberSummary after = ServerSurgeState.memberSummary(reloaded, ruler.userID);
      check(after != null && after.raidsUsed == raidsBefore + 1, "raidsUsed doit survivre au round-trip DB");

      System.out.println("[surge-raid] OK — recordRaid exécuté headless (raidsUsed=" + up.member.raidsUsed
          + ", or+" + up.surgePointDelta + "), persisté, round-trip — #72 incrément 5 (protocole Action RAID_SURGE)");
    }
  }
}
