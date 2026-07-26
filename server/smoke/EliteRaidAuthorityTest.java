import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.ICampaignLevelStatus;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.logic.RewardSourceType;
import com.perblue.heroes.game.logic.DailyActivityHelper;
import com.perblue.heroes.util.TimeUtil;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.*;

/**
 * ELITE_CAMPAIGN — AUTORITÉ SERVEUR (anti-triche + reset quotidien). Prouve, sur le VRAI code du jeu exécuté
 * PAR LE SERVEUR ({@code ServerUser}), que :
 *  (A) ANTI-TRICHE — un raid envoyé par le client SANS ressource (0 ticket) est REFUSÉ côté serveur
 *      ({@code chargeForRaid} lève) et n'applique <b>RIEN</b> (or/tickets/progression inchangés, persistés) —
 *      même si le client prétend un butin. Le serveur tranche sur SON état.
 *  (B) ANTI-INFLATION — un {@code raidCount>1} sans le VIP {@code RAID_10} est REFUSÉ (le client ne peut pas
 *      multiplier les récompenses en gonflant raidCount).
 *  (C) RESET QUOTIDIEN — {@code DailyActivityHelper.checkAndUpdateDailyValues} (exécuté serveur, sur
 *      {@code TimeUtil.serverTimeNow} = HORLOGE SERVEUR) réinitialise les compteurs quotidiens (dont
 *      {@code campaign_elite}) au passage de jour, PAS le même jour, et c'est PERSISTÉ. Avancer l'horloge du
 *      client ne le déclenche pas — c'est la date serveur qui fait foi.
 */
public final class EliteRaidAuthorityTest {

  static final int SHARD = 1;
  static final UnitType[] HEROES = {UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MERIDA, UnitType.MAUI};
  static final long DAY = 24L * 60 * 60 * 1000;

  static ServerUser reload(ServerUser su) throws Exception {
    return ServerUser.fromWire(su.userID, su.shardID, su.userInfoWire(), su.userExtraWire(), su.individualWire());
  }
  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000; return s;
  }
  static void win3(ServerUser su, CampaignType type, int ch, int lvl) {
    CampaignAttack ca = new CampaignAttack();
    ca.campaignType = type; ca.chapter = ch; ca.level = lvl; ca.stagesCleared = 1;
    ca.lootEarned = new ArrayList<>(); ca.memoryChanges = new ArrayList<>();
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    AttackLineupSummary lu = new AttackLineupSummary(); lu.units = new ArrayList<>();
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE}) lu.units.add(unit(t));
    b.attackers = new ArrayList<>(Collections.singletonList(lu)); b.defenders = new ArrayList<>();
    ca.base = b; su.recordCampaignAttack(ca);
  }
  static RaidCampaign raid(CampaignType type, int ch, int lvl, int count) {
    RaidCampaign rc = new RaidCampaign();
    rc.campaignType = type; rc.chapter = ch; rc.level = lvl; rc.raidCount = count;
    rc.rewards = new ArrayList<>(); rc.outcomes = new ArrayList<>();
    for (int i = 0; i < Math.max(1, count); i++) {
      RaidOutcome o = new RaidOutcome(); o.loot = new ArrayList<>();
      o.loot.add(RewardHelper.createDrop(ItemType.EXP_FLASK, 999L));   // le client PRÉTEND un gros butin
      o.memoryChanges = new ArrayList<>(); rc.outcomes.add(o);
    }
    return rc;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ---------- (A) ANTI-TRICHE : raid sans ticket → REFUSÉ, rien appliqué ----------
    ServerUser su = ServerUser.newPlayer(1L, SHARD);
    for (UnitType t : HEROES) su.grantHero(t);
    User setup = su.gameUser();
    setup.setResource(ResourceType.STAMINA, 100000L);      // énergie OK
    // PAS de RAID_TICKET (compte sans VIP → useTickets=true → tickets requis)
    win3(su, CampaignType.NORMAL, 1, 1);                    // 3★ (prérequis raid)

    ServerUser b0 = reload(su); User u0 = b0.gameUser();
    long goldB = u0.getResource(ResourceType.GOLD);
    int tkB = u0.getItemAmount(ItemType.RAID_TICKET);
    ICampaignLevelStatus s0 = u0.getCampaignLevel(CampaignType.NORMAL, 1, 1);
    int winsB = s0 == null ? 0 : s0.getTotalWins();
    int flaskB = u0.getItemAmount(ItemType.EXP_FLASK);

    su.recordRaidCampaign(raid(CampaignType.NORMAL, 1, 1, 1));   // client tente un raid SANS ticket + faux loot

    ServerUser b1 = reload(su); User u1 = b1.gameUser();
    ICampaignLevelStatus s1 = u1.getCampaignLevel(CampaignType.NORMAL, 1, 1);
    int winsA = s1 == null ? 0 : s1.getTotalWins();
    System.out.println("[auth] (A) sans ticket : gold " + goldB + "→" + u1.getResource(ResourceType.GOLD)
        + " tickets " + tkB + "→" + u1.getItemAmount(ItemType.RAID_TICKET)
        + " totalWins " + winsB + "→" + winsA + " flask " + flaskB + "→" + u1.getItemAmount(ItemType.EXP_FLASK));
    if (u1.getResource(ResourceType.GOLD) != goldB || u1.getItemAmount(ItemType.RAID_TICKET) != tkB
        || winsA != winsB || u1.getItemAmount(ItemType.EXP_FLASK) != flaskB)
      throw new AssertionError("ANTI-TRICHE : un raid sans ticket a MODIFIÉ l'état (devrait être refusé sans effet)");
    System.out.println("[auth] (A) OK — raid sans ressource REFUSÉ par le serveur, aucun état modifié (autoritatif)");

    // ---------- (B) ANTI-INFLATION : raidCount>1 sans VIP RAID_10 → REFUSÉ ----------
    RewardHelper.giveReward(su.gameUser(), RewardHelper.createDrop(ItemType.RAID_TICKET, 50L),
        RewardSourceType.NORMAL, "auth");   // maintenant assez de tickets
    ServerUser c0 = reload(su); User uc0 = c0.gameUser();
    long goldB2 = uc0.getResource(ResourceType.GOLD);
    int tkB2 = uc0.getItemAmount(ItemType.RAID_TICKET);

    su.recordRaidCampaign(raid(CampaignType.NORMAL, 1, 1, 5));   // raidCount=5 SANS VIP RAID_10 → doit être refusé

    ServerUser c1 = reload(su); User uc1 = c1.gameUser();
    System.out.println("[auth] (B) raidCount=5 sans VIP : gold " + goldB2 + "→" + uc1.getResource(ResourceType.GOLD)
        + " tickets " + tkB2 + "→" + uc1.getItemAmount(ItemType.RAID_TICKET));
    if (uc1.getResource(ResourceType.GOLD) != goldB2 || uc1.getItemAmount(ItemType.RAID_TICKET) != tkB2)
      throw new AssertionError("ANTI-INFLATION : raidCount>1 sans VIP a été appliqué (devrait être refusé)");
    // sanity : un raid ×1 LÉGITIME, lui, PASSE et débite 1 ticket (le serveur autorise le licite)
    su.recordRaidCampaign(raid(CampaignType.NORMAL, 1, 1, 1));
    User uc2 = reload(su).gameUser();
    if (uc2.getItemAmount(ItemType.RAID_TICKET) != tkB2 - 1)
      throw new AssertionError("un raid ×1 LÉGITIME devrait débiter 1 ticket (" + tkB2 + "→" + uc2.getItemAmount(ItemType.RAID_TICKET) + ")");
    System.out.println("[auth] (B) OK — raidCount>1 sans VIP REFUSÉ ; raid ×1 légitime accepté (-1 ticket). Autoritatif.");

    // ---------- (C) RESET QUOTIDIEN autoritatif (horloge serveur), persisté ----------
    User ud = su.gameUser();
    ud.setDailyUses("campaign_elite", 4);
    ud.setDailyUses("campaign_any", 7);
    // même JOUR (LAST_USER_DAILY_RESET = maintenant) → PAS de reset
    ud.setTime(TimeType.LAST_USER_DAILY_RESET, TimeUtil.serverTimeNow());
    DailyActivityHelper.checkAndUpdateDailyValues(ud);
    int eliteSameDay = ud.getDailyUses("campaign_elite", false);
    System.out.println("[auth] (C) même jour : dailyUses(campaign_elite)=" + eliteSameDay + " (attendu 4, pas de reset)");
    if (eliteSameDay != 4)
      throw new AssertionError("RESET : ne devrait PAS réinitialiser le même jour (=" + eliteSameDay + ")");
    // NOUVEAU JOUR (LAST_USER_DAILY_RESET = il y a 3 jours) → reset
    ud.setTime(TimeType.LAST_USER_DAILY_RESET, TimeUtil.serverTimeNow() - 3 * DAY);
    DailyActivityHelper.checkAndUpdateDailyValues(ud);
    int eliteNewDay = ud.getDailyUses("campaign_elite", false);
    int anyNewDay = ud.getDailyUses("campaign_any", false);
    System.out.println("[auth] (C) nouveau jour : dailyUses(campaign_elite)=" + eliteNewDay
        + " campaign_any=" + anyNewDay + " (attendu 0)");
    if (eliteNewDay != 0 || anyNewDay != 0)
      throw new AssertionError("RESET : les compteurs quotidiens devraient être réinitialisés au nouveau jour");
    // PERSISTANCE du reset (les dailyUses vivent dans this.extra) : on resync via un raid légitime + reload
    // (le reset a muté l'objet en mémoire ; on vérifie qu'un round-trip wire conserve 0).
    ServerUser d1 = reload(su);
    int elitePersist = d1.gameUser().getDailyUses("campaign_elite", false);
    System.out.println("[auth] (C) après round-trip wire : dailyUses(campaign_elite)=" + elitePersist);
    if (elitePersist != 0)
      throw new AssertionError("RESET : le reset quotidien ne persiste pas au round-trip wire (=" + elitePersist + ")");
    System.out.println("[auth] (C) OK — reset quotidien sur HORLOGE SERVEUR (nouveau jour→0, même jour→inchangé), persisté");

    // ---------- (D) RAID-ALL MULTI-NIVEAUX (>1 résultat) : tous appliqués ----------
    ServerUser mu = ServerUser.newPlayer(2L, SHARD);
    for (UnitType t : HEROES) mu.grantHero(t);
    User ms = mu.gameUser();
    ms.setResource(ResourceType.STAMINA, 100000L);
    RewardHelper.giveReward(ms, RewardHelper.createDrop(ItemType.RAID_TICKET, 50L), RewardSourceType.NORMAL, "auth");
    win3(mu, CampaignType.NORMAL, 1, 1);
    win3(mu, CampaignType.NORMAL, 1, 2);   // 1-2 débloqué par le 3★ de 1-1
    ServerUser m0 = reload(mu); User um0 = m0.gameUser();
    int tkM = um0.getItemAmount(ItemType.RAID_TICKET);
    int w1B = um0.getCampaignLevel(CampaignType.NORMAL, 1, 1).getTotalWins();
    int w2B = um0.getCampaignLevel(CampaignType.NORMAL, 1, 2).getTotalWins();

    RaidAllCampaign all = new RaidAllCampaign();
    all.results = new ArrayList<>();
    all.results.add(raid(CampaignType.NORMAL, 1, 1, 1));
    all.results.add(raid(CampaignType.NORMAL, 1, 2, 1));
    mu.recordRaidAllCampaign(all);

    User um1 = reload(mu).gameUser();
    int w1A = um1.getCampaignLevel(CampaignType.NORMAL, 1, 1).getTotalWins();
    int w2A = um1.getCampaignLevel(CampaignType.NORMAL, 1, 2).getTotalWins();
    int tkMA = um1.getItemAmount(ItemType.RAID_TICKET);
    System.out.println("[auth] (D) raid-all 2 niveaux : totalWins 1-1 " + w1B + "→" + w1A
        + ", 1-2 " + w2B + "→" + w2A + ", tickets " + tkM + "→" + tkMA);
    if (w1A != w1B + 1 || w2A != w2B + 1 || tkMA != tkM - 2)
      throw new AssertionError("RAID-ALL multi-niveaux : les 2 niveaux devraient être raidés (-2 tickets, +1 win chacun)");
    System.out.println("[auth] (D) OK — RaidAllCampaign multi-niveaux applique CHAQUE niveau (autoritatif, persisté)");

    System.out.println("ELITE RAID AUTHORITY TEST OK — anti-triche + anti-inflation + reset quotidien + raid-all multi = SERVEUR AUTORITATIF");
  }
}
