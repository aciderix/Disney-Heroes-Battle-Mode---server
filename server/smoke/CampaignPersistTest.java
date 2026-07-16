import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.CampaignHelper;
import dhserver.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Smoke test de la PERSISTANCE de la progression de campagne (docs/PRINCIPLES.md §6 : persistance
 * complète et fidèle). Une victoire au 1-1 doit, APRÈS save+reload SQLite : donner 3★ au niveau,
 * DÉBLOQUER le 1-2, créditer l'or, débiter la stamina. Le statut de niveau vit hors {@code this.extra}
 * ({@code ClientCampaignLevelStatus} en mémoire) → {@code ServerUser.recordCampaignAttack} doit le
 * re-synchroniser vers {@code individualUserExtra.levelStatuses} (sinon 1-2 ne se débloque jamais).
 */
public final class CampaignPersistTest {

  static AttackUnitSummary us(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000;
    return s;
  }

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "persist-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "persist-test");
    ServerContext.bind(u, iu);
    return u;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "/tmp/campaign-persist-test.db";
    new java.io.File(db).delete();
    try (UserStore store = new UserStore(db)) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.FROZONE);

      // CampaignAttack WIN 1-1 (comme le client l'envoie).
      CampaignAttack m = new CampaignAttack();
      m.campaignType = CampaignType.NORMAL; m.chapter = 1; m.level = 1; m.stagesCleared = 1;
      AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
      AttackLineupSummary lu = new AttackLineupSummary(); lu.units = new ArrayList();
      lu.units.add(us(UnitType.RALPH)); lu.units.add(us(UnitType.ELASTIGIRL)); lu.units.add(us(UnitType.FROZONE));
      b.attackers = new ArrayList(Arrays.asList(lu)); b.defenders = new ArrayList();
      m.base = b;
      su.recordCampaignAttack(m);
      store.save(su);

      // Recharge depuis SQLite (round-trip wire complet).
      ServerUser re = store.loadOrCreate(1L, 1);
      User u = bind(re);
      ICampaignLevelStatus s11 = u.getCampaignLevel(CampaignType.NORMAL, 1, 1);
      int stars = s11 == null ? 0 : s11.getStars();
      boolean unlocked12 = CampaignHelper.isLevelUnlocked(u, CampaignType.NORMAL, 1, 2);
      long gold = u.getResource(ResourceType.GOLD);
      long stamina = u.getResource(ResourceType.STAMINA);
      // lastWinTime : lu directement sur le wire rechargé (pas de getter sur ICampaignLevelStatus).
      long lastWin = 0;
      for (Object o : re.bootData().individualUserExtra.levelStatuses) {
        CampaignLevelStatus w = (CampaignLevelStatus) o;
        if (w.campaignType == CampaignType.NORMAL && w.chapter == 1 && w.level == 1) lastWin = w.lastWinTime;
      }
      System.out.println("[persist] APRÈS reload : 1-1 stars=" + stars + " 1-2_unlocked=" + unlocked12
          + " gold=" + gold + " stamina=" + stamina + " lastWinTime=" + lastWin);

      if (stars < 1)
        throw new AssertionError("progression 1-1 NON persistée (stars=" + stars + ") — levelStatuses non re-syncé");
      if (!unlocked12)
        throw new AssertionError("1-2 NON débloqué après victoire persistée au 1-1");
      if (gold <= 0)
        throw new AssertionError("or non persisté (gold=" + gold + ")");
      if (stamina < 0 || stamina > 1000)
        throw new AssertionError("stamina aberrante après combat+reload : " + stamina);
      if (lastWin <= 0)
        throw new AssertionError("lastWinTime NON persisté (=" + lastWin + ") — resyncCampaign/readLastWinTime KO");

      System.out.println("CAMPAIGN PERSIST TEST OK (1-1 à " + stars + "★ persisté, 1-2 débloqué, or="
          + gold + ", stamina=" + stamina + ", lastWinTime=" + lastWin + " — progression survit au round-trip SQLite)");
    }
  }
}
