import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.FriendPairID;
import com.perblue.heroes.game.logic.FriendshipHelper;
import dhserver.*;
import java.util.*;

/**
 * FRIENDSHIPS (#72) incrément 3b — COMBAT de campagne d'amitié (MISSIONS). Le client joue le combat et envoie
 * {@code FriendshipCampaignAttack} ; le serveur ré-exécute {@code FriendshipCampaignHelper.recordOutcome} (§3,
 * autoritatif : valide FRIEND_STAMINA/déblocage/canUseHeroes, débite l'énergie, progresse le nœud, pose lastBattle,
 * crédite loot). Vérifie via {@code ServerUser.recordFriendCampaignAttack} : débit stamina, lastBattle, persistance.
 */
public final class FriendshipCampaignTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[friendship-campaign] " + m); }

  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.ORANGE; s.survived = true; s.power = 100; s.startingHP = 1000; s.startingEnergy = 0;
    return s;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4601L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    su.grantHero(UnitType.RALPH, Rarity.ORANGE, 60, 5);
    su.grantHero(UnitType.VANELLOPE, Rarity.ORANGE, 60, 5);
    su.giveResource(ResourceType.FRIEND_STAMINA, 175);   // >= STAMINA_COST (6/nœud)
    FriendPairID pair = FriendPairID.of(UnitType.RALPH, UnitType.VANELLOPE);
    check(FriendshipHelper.getUnlockStatus(su.gameUser(), pair) == FriendshipHelper.FriendPairStatus.UNLOCKED,
        "paire débloquée");

    int node = 1;   // 1-indexé : nœud jouable = getFriendshipCampaignProgress(pair)+1 = 1 (frais)
    long stamBefore = su.gameUser().getResource(ResourceType.FRIEND_STAMINA);

    // Construire l'issue CLIENT (combat gagné) : lineup avec les héros de la paire.
    AttackLineupSummary lineup = new AttackLineupSummary();
    lineup.units = new ArrayList<AttackUnitSummary>();
    lineup.units.add(unit(UnitType.RALPH));
    lineup.units.add(unit(UnitType.VANELLOPE));
    AttackBase base = new AttackBase();
    base.outcome = CombatOutcome.WIN;
    base.stars = 3;
    base.attackers = new ArrayList<AttackLineupSummary>(); base.attackers.add(lineup);
    base.defenders = new ArrayList<AttackLineupSummary>();
    FriendshipCampaignAttack fca = new FriendshipCampaignAttack();
    fca.base = base;
    fca.friendPairID = pair.getAsLong();
    fca.nodeNumber = node;
    fca.lootEarned = new ArrayList<RewardDrop>();
    fca.memoryChanges = new ArrayList<>();
    fca.stagesCleared = 1;

    su.recordFriendCampaignAttack(fca);

    // Le combat autoritatif doit avoir : débité la stamina, posé lastBattle (gagné, bon nœud).
    long stamAfter = su.gameUser().getResource(ResourceType.FRIEND_STAMINA);
    FriendshipBattleInfo lb = su.gameUser().getIndividual().getFriendship(pair).getLastBattle();
    check(lb != null, "lastBattle posé après le combat");
    System.out.println("[friendship-campaign] combat: stamina " + stamBefore + "→" + stamAfter
        + ", lastBattle{node=" + lb.node + " won=" + lb.won + " empowerment=" + lb.empowerment + "}");
    check(stamAfter < stamBefore, "FRIEND_STAMINA débitée par le combat (avant=" + stamBefore + " après=" + stamAfter + ")");
    check(lb.won, "lastBattle.won = true (victoire enregistrée)");
    check(lb.node == node, "lastBattle.node = " + node);

    // Persistance DB : lastBattle survit (resyncFriendships).
    String db = System.getProperty("java.io.tmpdir") + "/dh-friendship-campaign-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser rl = store.loadIfExists(4601L, 1);
    FriendshipBattleInfo lb2 = rl.gameUser().getIndividual().getFriendship(pair).getLastBattle();
    check(lb2 != null && lb2.won && lb2.node == node, "lastBattle (victoire) survit à la persistance DB");
    check(rl.gameUser().getResource(ResourceType.FRIEND_STAMINA) == stamAfter, "FRIEND_STAMINA survit à la DB");
    WireCheck.assertRoundTrips(rl.gameUser().getIndividual().getExtra());
    store.close();

    System.out.println("[friendship-campaign] OK — combat de campagne d'amitié autoritatif + persistance via le code du jeu — #72 incrément 3b");
  }
}
