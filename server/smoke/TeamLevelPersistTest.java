import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import dhserver.*;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Smoke test de la PERSISTANCE du NIVEAU D'ÉQUIPE (docs/PRINCIPLES.md §6). {@code User.teamLevel} est un
 * CHAMP de {@code User} (hors {@code this.extra}) : {@code getUser} le lit depuis
 * {@code userInfo.basicInfo.teamLevel}, mais {@code setTeamLevel} (montée de niveau via {@code giveTeamXP}
 * dans {@code recordOutcome}) ne l'écrit QUE sur l'objet {@code User}. Sans re-sync vers le wire, le niveau
 * reste BLOQUÉ à 1 → l'équipe « remonte 1→2 » à chaque palier d'XP (18) et ré-accorde
 * {@code STAMINA_GAIN_ON_LEVEL} (+20 stamina) EN BOUCLE, au lieu de progresser. {@code recordCampaignAttack}
 * re-synchronise donc {@code userInfo.basicInfo.teamLevel = user.getTeamLevel()} (comme resyncHeroes/Campaign).
 * Ici : 3 victoires au 1-1 (3×6 = 18 XP = seuil niv.1→2) doivent, APRÈS save+reload SQLite, donner
 * teamLevel = 2 (et non 1).
 */
public final class TeamLevelPersistTest {

  static AttackUnitSummary us(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000;
    return s;
  }

  @SuppressWarnings("unchecked")
  static void fight(ServerUser su) {
    CampaignAttack m = new CampaignAttack();
    m.campaignType = CampaignType.NORMAL; m.chapter = 1; m.level = 1; m.stagesCleared = 1;
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    AttackLineupSummary lu = new AttackLineupSummary(); lu.units = new ArrayList();
    lu.units.add(us(UnitType.RALPH)); lu.units.add(us(UnitType.ELASTIGIRL)); lu.units.add(us(UnitType.FROZONE));
    b.attackers = new ArrayList(Arrays.asList(lu)); b.defenders = new ArrayList(); m.base = b;
    su.recordCampaignAttack(m);
  }

  static int teamLevel(ServerUser su) {
    BootData bd = su.bootData();
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "tl");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "tl");
    ServerContext.bind(u, iu);
    return u.getTeamLevel();
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "/tmp/teamlevel-persist-test.db";
    new java.io.File(db).delete();
    try (UserStore store = new UserStore(db)) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.FROZONE);

      int before = teamLevel(su);
      for (int i = 0; i < 3; i++) fight(su);   // 3 × 6 XP = 18 = seuil niv.1→2
      int afterFights = teamLevel(su);
      store.save(su);

      ServerUser re = store.loadOrCreate(1L, 1);
      int afterReload = teamLevel(re);
      System.out.println("[teamlevel] neuf=" + before + " après 3×1-1=" + afterFights
          + " après reload SQLite=" + afterReload);

      if (afterFights != 2)
        throw new AssertionError("montée de niveau NON appliquée (teamLevel=" + afterFights
            + " après 18 XP) — attendu 2");
      if (afterReload != 2)
        throw new AssertionError("niveau d'équipe NON persisté (teamLevel=" + afterReload
            + " après reload) — re-sync userInfo.basicInfo.teamLevel manquant");

      System.out.println("TEAM LEVEL PERSIST TEST OK (niv.1→2 après 18 XP, survit au round-trip SQLite = "
          + afterReload + " — plus de re-level en boucle ni de refill +20 stamina répété)");
    }
  }
}
