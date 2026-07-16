import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Smoke test de la PERSISTANCE du LOOT D'OBJETS de campagne (docs/PRINCIPLES.md §6). Le combat est joué
 * CÔTÉ CLIENT (client-autoritatif) qui ROULE le loot pendant le combat et l'envoie dans
 * {@code CampaignAttack.lootEarned} (List&lt;RewardDrop&gt;). {@code CampaignHelper.recordOutcome} N'EN ROULE
 * PAS — il APPLIQUE la liste reçue ({@code giveLoot → RewardHelper.giveRewards → IndividualUser.addItem}, qui
 * écrit dans {@code individualUserExtra.items}, auto-persisté). {@code ServerUser.recordCampaignAttack} doit
 * donc passer {@code m.lootEarned}/{@code m.memoryChanges} (et non des listes vides), SINON les objets
 * ramassés ne sont jamais crédités → inventaire vide → rien à équiper. Ici : un {@code RewardDrop} d'objet
 * dans {@code lootEarned} doit, APRÈS save+reload SQLite, être présent dans l'inventaire (dispo à l'équipement).
 */
public final class LootPersistTest {

  static AttackUnitSummary us(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000;
    return s;
  }

  static int itemAmount(ServerUser su, ItemType item) {
    BootData bd = su.bootData();
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "loot");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "loot");
    ServerContext.bind(u, iu);
    return iu.getItemAmount(item);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "/tmp/loot-persist-test.db";
    new java.io.File(db).delete();
    ItemType item = ItemType.BADGE_OF_FRIENDSHIP;   // objet réel du jeu (badge d'équipement du tuto)

    try (UserStore store = new UserStore(db)) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.FROZONE);
      int before = itemAmount(su, item);

      // Reconstruit un User pour rouler un RewardDrop d'objet (comme le client l'aurait roulé en combat).
      BootData bd = su.bootData();
      User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "roll");
      IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
          bd.individualUserExtra, 1L, bd.userInfo.diamonds, "roll");
      ServerContext.bind(u, iu);
      RewardDrop drop = RewardHelper.createDrop(item, 2, u, SpecialEventSnapshot.NONE, GameMode.CAMPAIGN);

      // CampaignAttack WIN 1-1 avec loot d'objet (comme le client l'envoie).
      CampaignAttack m = new CampaignAttack();
      m.campaignType = CampaignType.NORMAL; m.chapter = 1; m.level = 1; m.stagesCleared = 1;
      AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
      AttackLineupSummary lu = new AttackLineupSummary(); lu.units = new ArrayList();
      lu.units.add(us(UnitType.RALPH)); lu.units.add(us(UnitType.ELASTIGIRL)); lu.units.add(us(UnitType.FROZONE));
      b.attackers = new ArrayList(Arrays.asList(lu)); b.defenders = new ArrayList();
      m.base = b;
      m.lootEarned = new ArrayList(Arrays.asList(drop));
      m.memoryChanges = new ArrayList();
      su.recordCampaignAttack(m);

      int afterFight = itemAmount(su, item);
      store.save(su);

      ServerUser re = store.loadOrCreate(1L, 1);
      int afterReload = itemAmount(re, item);
      System.out.println("[loot] " + item + " : neuf=" + before + " après combat=" + afterFight
          + " après reload SQLite=" + afterReload);

      if (afterFight < 2)
        throw new AssertionError("loot d'objet NON crédité (" + item + "=" + afterFight
            + ") — m.lootEarned ignoré par recordCampaignAttack");
      if (afterReload < 2)
        throw new AssertionError("loot d'objet NON persisté (" + item + "=" + afterReload
            + " après reload) — items non sauvés");

      System.out.println("LOOT PERSIST TEST OK (" + item + " x" + afterReload
          + " crédité en combat ET survit au round-trip SQLite — dispo à l'équipement)");
    }
  }
}
