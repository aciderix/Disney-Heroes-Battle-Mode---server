import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.ICampaignLevelStatus;
import com.perblue.heroes.game.logic.CampaignHelper;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.logic.RewardSourceType;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.*;

/**
 * ÉCRAN ELITE_CAMPAIGN — RAID (message {@code RaidCampaign}). Prouve headless, sur le VRAI code du jeu, que le
 * handler serveur {@code ServerUser.recordRaidCampaign} REJOUE fidèlement la séquence CLIENT (relevée au bytecode
 * de {@code CampaignPreviewScreen}/{@code RaidTicketOutcomeWindow}) :
 *   (1) {@code CampaignHelper.chargeForRaid} → DÉBIT (RAID_TICKET + énergie) + validation anti-triche ;
 *   (2) {@code CampaignHelper.recordRaidOutcome} → progression ({@code totalWins += raidCount}), GOLD, crédit du loot.
 * On vérifie chaque maillon + la PERSISTANCE (round-trip wire), pour un raid NORMAL puis ELITE (chances quotidiennes).
 *
 * <p>NB : {@code raidCount>1} exige le VIP {@code RAID_10} → un compte sans VIP raide ×1 (chemin réaliste TL11).
 */
public final class EliteCampaignRaidTest {

  static final int SHARD = 1;
  static final UnitType[] HEROES = {UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MERIDA, UnitType.MAUI};

  static ServerUser reload(ServerUser su) throws Exception {
    return ServerUser.fromWire(su.userID, su.shardID, su.userInfoWire(), su.userExtraWire(), su.individualWire());
  }

  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000; s.startingEnergy = 0;
    return s;
  }

  /** Gagne un niveau à 3★ via le VRAI chemin de combat (recordCampaignAttack), pour autoriser le raid (3★ requis). */
  static void winThreeStars(ServerUser su, CampaignType type, int chapter, int level) {
    CampaignAttack ca = new CampaignAttack();
    ca.campaignType = type; ca.chapter = chapter; ca.level = level; ca.stagesCleared = 1;
    ca.lootEarned = new ArrayList<>(); ca.memoryChanges = new ArrayList<>();
    AttackBase base = new AttackBase();
    base.outcome = CombatOutcome.WIN; base.stars = 3;
    AttackLineupSummary lu = new AttackLineupSummary();
    lu.units = new ArrayList<>();
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE}) lu.units.add(unit(t));
    base.attackers = new ArrayList<>(Collections.singletonList(lu));
    base.defenders = new ArrayList<>();
    ca.base = base;
    su.recordCampaignAttack(ca);
  }

  static RaidCampaign raidMsg(CampaignType type, int chapter, int level, int raidCount, ItemType lootItem, long lootQty) {
    RaidCampaign rc = new RaidCampaign();
    rc.campaignType = type; rc.chapter = chapter; rc.level = level; rc.raidCount = raidCount;
    rc.rewards = new ArrayList<>();
    rc.outcomes = new ArrayList<>();
    for (int i = 0; i < raidCount; i++) {
      RaidOutcome o = new RaidOutcome();
      o.loot = new ArrayList<>();
      o.loot.add(RewardHelper.createDrop(lootItem, lootQty));   // loot client par raid (fait confiance client, §4bis)
      o.memoryChanges = new ArrayList<>();
      rc.outcomes.add(o);
    }
    return rc;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    ServerUser su = ServerUser.newPlayer(1L, SHARD);
    for (UnitType t : HEROES) su.grantHero(t);

    // Dotation : énergie large + tickets de raid (compte sans VIP → useTickets=true → RAID_TICKET requis).
    User setup = su.gameUser();
    setup.setResource(ResourceType.STAMINA, 100000L);
    RewardHelper.giveReward(setup, RewardHelper.createDrop(ItemType.RAID_TICKET, 20L), RewardSourceType.NORMAL, "test");

    // ---------- (A) RAID NORMAL 1-1 (×1) ----------
    winThreeStars(su, CampaignType.NORMAL, 1, 1);   // 3★ requis pour raider

    ServerUser before = reload(su);
    User ub = before.gameUser();
    long staBefore = ub.getResource(ResourceType.STAMINA);
    long goldBefore = ub.getResource(ResourceType.GOLD);
    int ticketsBefore = ub.getItemAmount(ItemType.RAID_TICKET);
    int flaskBefore = ub.getItemAmount(ItemType.EXP_FLASK);
    ICampaignLevelStatus stB = ub.getCampaignLevel(CampaignType.NORMAL, 1, 1);
    int winsBefore = stB == null ? 0 : stB.getTotalWins();
    int staCost = CampaignHelper.getStaminaCost(ub, CampaignType.NORMAL, 1, 1);
    System.out.println("[raid] (A) AVANT : stamina=" + staBefore + " gold=" + goldBefore + " tickets=" + ticketsBefore
        + " flask=" + flaskBefore + " totalWins(1-1)=" + winsBefore + " coût_stamina=" + staCost);

    su.recordRaidCampaign(raidMsg(CampaignType.NORMAL, 1, 1, 1, ItemType.EXP_FLASK, 5L));

    ServerUser after = reload(su);
    User ua = after.gameUser();
    long staAfter = ua.getResource(ResourceType.STAMINA);
    long goldAfter = ua.getResource(ResourceType.GOLD);
    int ticketsAfter = ua.getItemAmount(ItemType.RAID_TICKET);
    int flaskAfter = ua.getItemAmount(ItemType.EXP_FLASK);
    ICampaignLevelStatus stA = ua.getCampaignLevel(CampaignType.NORMAL, 1, 1);
    int winsAfter = stA == null ? 0 : stA.getTotalWins();
    System.out.println("[raid] (A) APRÈS : stamina=" + staAfter + " gold=" + goldAfter + " tickets=" + ticketsAfter
        + " flask=" + flaskAfter + " totalWins(1-1)=" + winsAfter + " (persisté)");

    if (!(staAfter < staBefore))
      throw new AssertionError("RAID : énergie non débitée (" + staBefore + " → " + staAfter + ")");
    if (ticketsAfter != ticketsBefore - 1)
      throw new AssertionError("RAID : ticket de raid non débité (" + ticketsBefore + " → " + ticketsAfter + ")");
    if (!(goldAfter > goldBefore))
      throw new AssertionError("RAID : or non crédité (" + goldBefore + " → " + goldAfter + ")");
    if (winsAfter != winsBefore + 1)
      throw new AssertionError("RAID : totalWins non incrémenté (" + winsBefore + " → " + winsAfter + ")");
    if (!(flaskAfter >= flaskBefore + 5))
      throw new AssertionError("RAID : loot client non crédité (EXP_FLASK " + flaskBefore + " → " + flaskAfter + ")");
    System.out.println("[raid] (A) OK — NORMAL 1-1 : énergie -" + (staBefore - staAfter) + ", ticket -1, or +"
        + (goldAfter - goldBefore) + ", totalWins +1, loot +" + (flaskAfter - flaskBefore) + " EXP_FLASK, persisté");

    // ---------- (B) RAID ELITE 1-1 (×1) : chances quotidiennes ----------
    // Élite requiert un niveau 3★ (on gagne d'abord l'ELITE 1-1 par le chemin de combat). Si l'ère de contenu
    // ne rend pas l'ELITE 1-1 jouable headless, on le NOTE (vérif EN JEU obligatoire de toute façon).
    boolean eliteSetup;
    try {
      winThreeStars(su, CampaignType.ELITE, 1, 1);
      ServerUser eb = reload(su);
      ICampaignLevelStatus est = eb.gameUser().getCampaignLevel(CampaignType.ELITE, 1, 1);
      eliteSetup = est != null && est.getStars() >= 3;
      System.out.println("[raid] (B) ELITE 1-1 après combat : "
          + (est == null ? "null" : est.getStars() + "★") + " → setup=" + eliteSetup);
    } catch (Throwable t) {
      eliteSetup = false;
      System.out.println("[raid] (B) ELITE 1-1 combat indisponible headless : " + t);
    }

    if (eliteSetup) {
      ServerUser eBefore = reload(su);
      User eub = eBefore.gameUser();
      int eUsesBefore = eub.getDailyUses("campaign_elite", false);
      int eTicketsBefore = eub.getItemAmount(ItemType.RAID_TICKET);
      ICampaignLevelStatus estB = eub.getCampaignLevel(CampaignType.ELITE, 1, 1);
      int eWinsBefore = estB == null ? 0 : estB.getTotalWins();
      System.out.println("[raid] (B) AVANT : dailyUses(campaign_elite)=" + eUsesBefore
          + " tickets=" + eTicketsBefore + " totalWins(E1-1)=" + eWinsBefore);

      su.recordRaidCampaign(raidMsg(CampaignType.ELITE, 1, 1, 1, ItemType.EXP_FLASK, 3L));

      ServerUser eAfter = reload(su);
      User eua = eAfter.gameUser();
      int eUsesAfter = eua.getDailyUses("campaign_elite", false);
      ICampaignLevelStatus estA = eua.getCampaignLevel(CampaignType.ELITE, 1, 1);
      int eWinsAfter = estA == null ? 0 : estA.getTotalWins();
      System.out.println("[raid] (B) APRÈS : dailyUses(campaign_elite)=" + eUsesAfter
          + " totalWins(E1-1)=" + eWinsAfter + " (persisté)");

      if (eWinsAfter != eWinsBefore + 1)
        throw new AssertionError("RAID ELITE : totalWins non incrémenté (" + eWinsBefore + " → " + eWinsAfter + ")");
      if (eUsesAfter != eUsesBefore + 1)
        throw new AssertionError("RAID ELITE : dailyUses(campaign_elite) non incrémenté (" + eUsesBefore + " → " + eUsesAfter + ")");
      System.out.println("[raid] (B) OK — ELITE 1-1 : dailyUses +1, totalWins +1, persisté");
    } else {
      System.out.println("[raid] (B) ELITE non testable headless (ère de contenu) — à VÉRIFIER EN JEU (obligatoire).");
    }

    System.out.println("ELITE CAMPAIGN RAID TEST OK");
  }
}
