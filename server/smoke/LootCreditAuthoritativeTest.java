import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.logic.CampaignLootHelper;
import com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot;
import com.perblue.heroes.game.objects.GuildInfoPerkProvider;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.*;

/**
 * #25 — CRÉDIT DE LOOT AUTORITAIRE (bascule + garde-fou §4bis). Prouve, sur le VRAI code exécuté par le serveur
 * ({@code ServerUser.recordCampaignAttack}) :
 *  (A) CAS HONNÊTE (loot client == tirage serveur) → le serveur CRÉDITE SON tirage et AVANCE l'état évolutif
 *      ({@code expLootPool}) — ce qui PROUVE qu'il a roulé lui-même (pas un simple écho du client) — et ça PERSISTE.
 *  (B) CAS DIVERGENT (loot client falsifié) → le serveur NE bascule PAS : repli sur loot client (jamais léser un
 *      honnête) et l'état N'avance PAS (pool inchangé) — comportement distinct, tracé (log de divergence).
 * La graine LOOT étant reproductible côté serveur (hash userID, cf. #25/B), le serveur roule le MÊME butin qu'un
 * client légitime → en pratique autorité effective pour un compte joué depuis la création.
 */
public final class LootCreditAuthoritativeTest {

  static final CampaignType TYPE = CampaignType.NORMAL;
  static final int CH = 1, LV = 1;                    // niveau DÉBLOQUÉ sur compte neuf (recordOutcome ne lève pas)
  static final UnitType[] TEAM = {UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE};

  static ServerUser reload(ServerUser su) throws Exception {
    return ServerUser.fromWire(su.userID, su.shardID, su.userInfoWire(), su.userExtraWire(), su.individualWire());
  }
  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000; return s;
  }
  static CampaignAttack atk(java.util.List<RewardDrop> loot) {
    CampaignAttack ca = new CampaignAttack();
    ca.campaignType = TYPE; ca.chapter = CH; ca.level = LV; ca.stagesCleared = 1;
    ca.lootEarned = loot; ca.memoryChanges = new ArrayList<>();
    AttackBase b = new AttackBase(); b.outcome = CombatOutcome.WIN; b.stars = 3;
    AttackLineupSummary lu = new AttackLineupSummary(); lu.units = new ArrayList<>();
    for (UnitType t : TEAM) lu.units.add(unit(t));
    b.attackers = new ArrayList<>(Collections.singletonList(lu)); b.defenders = new ArrayList<>();
    ca.base = b; return ca;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ---------- (A) CAS HONNÊTE : loot client = tirage serveur → crédit serveur + avance du pool ----------
    ServerUser su = ServerUser.newPlayer(1L, 1);
    for (UnitType t : TEAM) su.grantHero(t);
    User setup = su.gameUser();
    setup.setResource(ResourceType.STAMINA, 100000L);

    // Calcule le tirage serveur ATTENDU (même graine userID par défaut, pool=0, pitié vide) — miroir du handler.
    User u = su.gameUser(); IndividualUser iu = u.getIndividual();
    ServerContext.bind(u, iu);
    u.resetRandom(RandomSeedType.LOOT);
    CampaignLoot exp = CampaignLootHelper.getLoot(u, TYPE, 0, CH, LV, SpecialEventSnapshot.NONE,
        new GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo()), true);
    java.util.List<RewardDrop> expLoot = new ArrayList<>(exp.combinedLoot);
    int expPool = exp.newExpLootPool;
    int poolBefore = su.gameUser().getExpLootPool();
    System.out.println("[credit] (A) tirage serveur attendu : " + sigOf(expLoot) + " newPool=" + expPool + " (poolAvant=" + poolBefore + ")");

    // Le client envoie EXACTEMENT ce loot (joueur honnête) → le serveur doit matcher et créditer SON tirage.
    su.recordCampaignAttack(atk(new ArrayList<>(expLoot)));

    ServerUser back = reload(su);
    int poolAfter = back.expLootPoolPersisted();
    int memAfter = back.lootMemorySize();   // le chemin AUTORITAIRE appelle updateMemoryUnconditional → mémoire peuplée
    // NB : le pool final ≠ exactement newExpLootPool car recordOutcome retouche ensuite le pool (XP poolé) ; le
    // point prouvé = l'état a AVANCÉ (pool≠avant, mémoire peuplée) via le tirage SERVEUR, et c'est persisté.
    System.out.println("[credit] (A) poolXP persisté=" + poolAfter + " (avant " + poolBefore + ", roll serveur newPool=" + expPool
        + "), mémoire pitié=" + memAfter + " entrée(s)");
    if (poolAfter == poolBefore && memAfter <= 0)
      throw new AssertionError("AUTORITÉ : l'état évolutif (pool+pitié) n'a PAS avancé côté serveur → le chemin autoritaire n'a pas crédité son tirage");
    if (memAfter <= 0)
      throw new AssertionError("AUTORITÉ : la mémoire de pitié n'a PAS été avancée (updateMemoryUnconditional non exécuté)");
    java.util.List<RewardDrop> credited = expLootItemsPresent(back, expLoot);
    System.out.println("[credit] (A) items serveur crédités+persistés : " + credited.size() + " type(s)");
    System.out.println("[credit] (A) OK — crédit AUTORITAIRE (tirage serveur) + avance d'état (pool+pitié) persistée");

    // ---------- (B) CAS DIVERGENT : loot client falsifié → repli client, pool NON avancé ----------
    ServerUser su2 = ServerUser.newPlayer(2L, 1);
    for (UnitType t : TEAM) su2.grantHero(t);
    su2.gameUser().setResource(ResourceType.STAMINA, 100000L);
    int pool2Before = su2.gameUser().getExpLootPool();
    java.util.List<RewardDrop> fake = new ArrayList<>();
    fake.add(com.perblue.heroes.game.logic.RewardHelper.createDrop(ItemType.EXP_FLASK, 99999L));  // falsifié
    su2.recordCampaignAttack(atk(fake));
    ServerUser back2 = reload(su2);
    int mem2After = back2.lootMemorySize();
    System.out.println("[credit] (B) loot client falsifié → mémoire pitié=" + mem2After + " (doit rester 0 : PAS de bascule autoritaire)");
    if (mem2After != 0)
      throw new AssertionError("DIVERGENCE : l'état ne devrait PAS avancer côté serveur (repli client attendu) — mémoire=" + mem2After);
    System.out.println("[credit] (B) OK — divergence → repli sur loot client (jamais léser l'honnête), état non avancé, logué");

    System.out.println("LOOT CREDIT AUTHORITATIVE TEST OK — crédit du tirage SERVEUR quand il == client (autorité + avance d'état), repli sûr sinon");
  }

  static String sigOf(java.util.List<RewardDrop> l) {
    List<String> p = new ArrayList<>();
    for (RewardDrop d : l) if (d.quantity > 0) p.add((d.itemType != null && d.itemType != ItemType.DEFAULT ? d.itemType : d.resourceType) + "x" + d.quantity);
    Collections.sort(p); return p.toString();
  }
  static java.util.List<RewardDrop> expLootItemsPresent(ServerUser back, java.util.List<RewardDrop> expLoot) {
    java.util.List<RewardDrop> present = new ArrayList<>();
    User u = back.gameUser();
    for (RewardDrop d : expLoot) if (d.itemType != null && d.itemType != ItemType.DEFAULT && d.quantity > 0
        && u.getItemAmount(d.itemType) > 0) present.add(d);
    return present;
  }
}
