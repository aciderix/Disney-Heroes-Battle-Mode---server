import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * SURGE (#72) incrément 4c — FLUX DE COMBAT DE DISTRICT : StartSurgeAttack → StartSurgeAttackResponse (lineup
 * défenseur + raidID) puis SurgeAttack → SurgeUpdate (recordOutcome + district vaincu + delta), persisté.
 * Vérifie round-trip wire des deux réponses (défaut nº3) + mutation d'état + persistance.
 */
public final class SurgeAttackFlowTest {

  static void check(boolean c, String m) { if (!c) throw new AssertionError("[surge-flow] " + m); }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static AttackUnitSummary unit(UnitType t, long p) {
    AttackUnitSummary u = new AttackUnitSummary(); u.type = t; u.power = p; u.rarity = Rarity.ORANGE; u.survived = true; return u;
  }
  static AttackLineupSummary lineup(AttackUnitSummary... us) {
    AttackLineupSummary l = new AttackLineupSummary(); l.units = new java.util.ArrayList<>(java.util.Arrays.asList(us)); return l;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-surge-flow", ".db"); tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser ruler = ServerUser.newPlayer(1L, 1);
      ruler.giveResource(ResourceType.GOLD, 5000);
      ruler.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5);
      ServerGuild g = ruler.createGuild(mk("Surge Legion"), store.nextGuildID(1));
      store.save(ruler); store.saveGuild(g);
      // Pool réel d'adversaires.
      for (long id = 10L; id <= 12L; id++) {
        ServerUser r = ServerUser.newPlayer(id, 1);
        r.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5); r.grantHero(UnitType.ELASTIGIRL, Rarity.ORANGE, 40, 5);
        store.save(r);
      }

      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      SurgeData data = ServerSurgeState.loadOrReset(store, g, now);
      DistrictType district = ServerSurgeMap.activeDistricts().get(0);

      // 1. START : lineup défenseur + raidID + verrou.
      StartSurgeAttackResponse resp = ServerSurgeState.startAttack(store, g, data, district, now);
      check(resp.heroes != null && !resp.heroes.isEmpty(), "réponse START doit porter un lineup défenseur");
      check(resp.raidID == now, "raidID attendu = now");
      check(resp.district == district, "district de la réponse incohérent");
      WireCheck.assertRoundTrips(resp);
      SurgeOpponentSummary op = ServerSurgeState.opponentFor(data, district);
      check(op != null && op.lockExpiration > now, "l'adversaire du district doit être verrouillé après START");

      // 2. ATTACK (WIN) : recordOutcome + district vaincu + delta.
      SurgeAttack m = new SurgeAttack();
      m.base = new AttackBase(); m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
      m.base.attackers = new java.util.ArrayList<>(java.util.Arrays.asList(lineup(unit(UnitType.RALPH, 5000))));
      m.base.defenders = new java.util.ArrayList<>(java.util.Arrays.asList(lineup(unit(UnitType.ELASTIGIRL, 4000))));
      m.district = district; m.usedAutoSelect = false; m.wasRecommended = false;
      m.objectiveProgress = new java.util.HashMap<>();
      SurgeObjectiveInfo obj = new SurgeObjectiveInfo();
      obj.slot = 0; obj.type = SurgeObjectiveType.values()[0]; obj.rarity = SurgeObjectiveRarity.values()[0];
      m.objectiveProgress.put(obj, 1);

      SurgeUpdate up = ServerSurgeState.applyAttack(g, data, ruler, m);
      check(up.districtsClearedDelta == 1, "une victoire doit vaincre le district (delta 1), obtenu " + up.districtsClearedDelta);
      check(up.opponent != null && up.opponent.clearedThisWave, "l'adversaire doit être marqué vaincu");
      check(up.member != null && up.member.districtsCleared == 1, "districtsCleared du membre = 1");
      WireCheck.assertRoundTrips(up);

      // 3. Persistance : sauver + relire → l'adversaire reste vaincu.
      ServerSurgeState.save(store, g, data.surgeID, data);
      SurgeData reloaded = ServerSurgeState.loadOrReset(store, g, now);
      SurgeOpponentSummary op2 = ServerSurgeState.opponentFor(reloaded, district);
      check(op2 != null && op2.clearedThisWave, "l'état de district vaincu doit survivre au round-trip DB");

      System.out.println("[surge-flow] OK — START→ATTACK→UPDATE : lineup défenseur (" + resp.heroes.size()
          + "), district vaincu, +" + up.surgePointDelta + " pts, persisté — #72 incrément 4c");
    }
  }
}
