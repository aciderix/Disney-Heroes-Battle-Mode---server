import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.data.campaign.CampaignLevel;
import com.perblue.heroes.game.logic.CampaignHelper;
import com.perblue.heroes.game.logic.CampaignLootHelper;
import com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot;
import com.perblue.heroes.game.objects.GuildInfoPerkProvider;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.*;

/**
 * #25 — PAS DE FAUX DIVERGENT. Un « faux divergent » = le serveur diverge d'un client LÉGITIME (→ repli
 * permanent → autorité illusoire). On le réfute sur N combats : un CLIENT de référence (même userID, donc même
 * graine {@code getDefaultSeed}) joue et avance son état EXACTEMENT comme le jeu (getLoot → setExpLootPool +
 * updateMemoryUnconditional → recordOutcome) ; on envoie SON loot au SERVEUR ({@code recordCampaignAttack}) et on
 * vérifie qu'à CHAQUE combat le serveur reste EN PHASE (même {@code expLootPool}) — s'il avait faussement divergé,
 * il aurait replié sur le loot client sans avancer son état comme le client → dérive détectée.
 *
 * <p>NB honnêteté : ce test couvre le cas de CE serveur (pas de guilde/évènement/drop-booster). Un client AVEC un
 * bonus non répliqué (perk de guilde, évènement, booster) produirait une VRAIE différence → le garde-fou replie
 * alors sur le loot client (jamais léser l'honnête) ; documenté SHIMS #25.
 */
public final class LootParityMultiCombatTest {

  static final CampaignType TYPE = CampaignType.NORMAL;
  static final int CH = 1, LV = 1, N = 12;
  static final UnitType[] TEAM = {UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE};

  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000; return s;
  }
  static List<AttackLineupSummary> attackers() {
    AttackLineupSummary lu = new AttackLineupSummary(); lu.units = new ArrayList<>();
    for (UnitType t : TEAM) lu.units.add(unit(t));
    return new ArrayList<>(Collections.singletonList(lu));
  }
  static CampaignAttack atk(java.util.List<RewardDrop> loot) {
    CampaignAttack ca = new CampaignAttack();
    ca.campaignType = TYPE; ca.chapter = CH; ca.level = LV; ca.stagesCleared = 1;
    ca.lootEarned = loot; ca.memoryChanges = new ArrayList<>();
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    b.attackers = attackers(); b.defenders = new ArrayList<>();
    ca.base = b; return ca;
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // CLIENT de référence (userID=1) — avance son état comme le VRAI jeu.
    ServerUser suClient = ServerUser.newPlayer(1L, 1);
    for (UnitType t : TEAM) suClient.grantHero(t);
    User client = suClient.gameUser(); IndividualUser ci = client.getIndividual();
    ServerContext.bind(client, ci);
    client.setResource(ResourceType.STAMINA, 100000L);

    // SERVEUR (userID=1, même graine par défaut) — reçoit le loot du client via recordCampaignAttack.
    ServerUser suServer = ServerUser.newPlayer(1L, 1);
    for (UnitType t : TEAM) suServer.grantHero(t);
    suServer.gameUser().setResource(ResourceType.STAMINA, 100000L);

    int falseDiverge = 0;
    for (int i = 1; i <= N; i++) {
      // --- CLIENT : roule + avance son état EXACTEMENT comme le jeu ---
      ServerContext.bind(client, ci);
      client.resetRandom(RandomSeedType.LOOT);
      CampaignLoot cl = CampaignLootHelper.getLoot(client, TYPE, 0, CH, LV, SpecialEventSnapshot.NONE,
          new GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo()), true);
      java.util.List<RewardDrop> clientLoot = new ArrayList<>(cl.combinedLoot);
      client.setExpLootPool(cl.newExpLootPool);
      CampaignLootHelper.updateMemoryUnconditional(client, cl, CH);
      CampaignHelper.recordOutcome(client, client, CampaignLevel.of(GameMode.CAMPAIGN, CH, LV),
          CombatOutcome.WIN, 3, 1, clientLoot, new ArrayList<>(), attackers(), new ArrayList<>(), SpecialEventSnapshot.NONE);

      // --- SERVEUR : reçoit le loot du client, roule de SON côté, doit MATCHER (pas de faux divergent) ---
      suServer.recordCampaignAttack(atk(new ArrayList<>(clientLoot)));

      int poolClient = client.getExpLootPool();
      int poolServer = suServer.expLootPoolPersisted();
      boolean inSync = poolClient == poolServer;
      System.out.println("[parity] combat " + i + " : poolClient=" + poolClient + " poolServer=" + poolServer
          + (inSync ? "  ✅ en phase" : "  ⚠️ FAUX DIVERGENT"));
      if (!inSync) falseDiverge++;
    }

    if (falseDiverge > 0)
      throw new AssertionError("FAUX DIVERGENT sur " + falseDiverge + "/" + N + " combats : le serveur diverge d'un client LÉGITIME");
    System.out.println("[parity] OK — 0 faux divergent sur " + N + " combats : le serveur reste EN PHASE avec un client légitime");
    System.out.println("LOOT PARITY MULTI-COMBAT TEST OK — crédit autoritaire fiable (pas de faux divergent) pour un compte sans guilde/évènement");
  }
}
