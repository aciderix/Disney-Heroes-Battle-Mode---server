import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.data.campaign.CampaignLevel;
import com.perblue.heroes.game.logic.CampaignHelper;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.ArrayList;
import java.util.List;

/**
 * Smoke test du PIPELINE de combat de campagne côté SERVEUR autoritatif (docs/PRINCIPLES.md §3 :
 * on EXÉCUTE la logique du jeu). Reproduit ce que le client fait via
 * {@code ClientNetworkStateConverter.getCampaignAttack} → {@code CampaignHelper.recordOutcome} :
 * sur une victoire (WIN) au niveau NORMAL 1-1, le jeu <b>consomme la stamina</b>
 * ({@code getStaminaCost} + {@code UserHelper.chargeUser}), <b>donne le loot/gold/XP</b>
 * ({@code giveLoot}/{@code giveGold}/{@code giveTeamXP}) et <b>met à jour la progression</b>
 * ({@code ICampaignLevelStatus}). On vérifie que chaque maillon bouge dans le bon sens — valeurs
 * issues du jeu (CampaignStats), rien d'inventé.
 */
public final class CampaignAttackTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "campaign-test");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "campaign-test");
    ServerContext.bind(user, iu);
    return user;
  }

  static AttackUnitSummary summary(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t;
    s.rarity = Rarity.WHITE;
    s.survived = true;
    s.power = 100;
    s.startingHP = 1000;
    s.startingEnergy = 0;
    return s;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    su.grantHero(UnitType.FROZONE);                 // roster à 3 (comme après le coffre du tuto)
    User user = bind(su);

    CampaignType type = CampaignType.NORMAL;
    int chapter = 1, level = 1;
    CampaignLevel cl = CampaignLevel.of(GameMode.CAMPAIGN, chapter, level);

    long staminaBefore = user.getResource(ResourceType.STAMINA);
    long goldBefore = user.getResource(ResourceType.GOLD);
    int teamLevelBefore = user.getTeamLevel();
    int staminaCost = CampaignHelper.getStaminaCost(user, type, chapter, level);
    ICampaignLevelStatus stBefore = user.getCampaignLevel(type, chapter, level);
    System.out.println("[camp] AVANT : stamina=" + staminaBefore + " gold=" + goldBefore
        + " teamLevel=" + teamLevelBefore + " coût_stamina=" + staminaCost
        + " statutNiveau=" + (stBefore == null ? "null" : stBefore.getStars() + "★"));

    // Équipe = héros possédés (résumés de combat, tous survivants pour une victoire propre).
    // recordOutcome attend une Collection de LINEUPS (AttackLineupSummary{units}), une par vague.
    AttackLineupSummary lineup = new AttackLineupSummary();
    lineup.units = new ArrayList<AttackUnitSummary>();
    lineup.units.add(summary(UnitType.RALPH));
    lineup.units.add(summary(UnitType.ELASTIGIRL));
    lineup.units.add(summary(UnitType.FROZONE));
    List<AttackLineupSummary> attackers = new ArrayList<>();
    attackers.add(lineup);
    List<AttackLineupSummary> defenders = new ArrayList<>();

    List<RewardDrop> lootEarned = new ArrayList<>();
    List<Object> memoryChanges = new ArrayList<>();

    int stagesCleared = 1;
    int stars = 3;
    // Exécute la logique AUTORITATIVE du jeu (comme le ferait le handler serveur du CampaignAttack).
    int ret = CampaignHelper.recordOutcome(user, user, cl, CombatOutcome.WIN, stars, stagesCleared,
        lootEarned, memoryChanges, attackers, defenders,
        com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);

    long staminaAfter = user.getResource(ResourceType.STAMINA);
    long goldAfter = user.getResource(ResourceType.GOLD);
    int teamLevelAfter = user.getTeamLevel();
    ICampaignLevelStatus stAfter = user.getCampaignLevel(type, chapter, level);
    System.out.println("[camp] APRÈS : stamina=" + staminaAfter + " gold=" + goldAfter
        + " teamLevel=" + teamLevelAfter + " ret=" + ret
        + " statutNiveau=" + (stAfter == null ? "null" : stAfter.getStars() + "★"));
    System.out.println("[camp] LOOT gagné (" + lootEarned.size() + ") :");
    for (RewardDrop d : lootEarned)
      System.out.println("        " + d.itemType + " x" + d.quantity
          + (d.resourceType != ResourceType.DEFAULT ? " (" + d.resourceType + ")" : ""));

    // --- Assertions du pipeline (effets AUTORITATIFS appliqués à l'utilisateur) ---
    // 1) ÉNERGIE : la stamina est débitée EXACTEMENT du coût du jeu (getStaminaCost).
    if (staminaAfter != staminaBefore - staminaCost)
      throw new AssertionError("STAMINA non débitée du coût attendu : " + staminaBefore + " - "
          + staminaCost + " != " + staminaAfter);
    // 2) OR : une victoire de campagne rapporte de l'or (giveGold → giveUser, appliqué direct).
    if (goldAfter <= goldBefore)
      throw new AssertionError("OR non crédité sur une victoire : " + goldBefore + " -> " + goldAfter);
    // 3) PROGRESSION : le niveau est enregistré avec ses étoiles (ICampaignLevelStatus).
    ICampaignLevelStatus st2 = user.getCampaignLevel(type, chapter, level);
    if (st2 == null || st2.getStars() < 1)
      throw new AssertionError("progression du niveau non enregistrée (étoiles=0)");
    // (lootEarned = objets seulement ; vide au niveau 1-1 est normal. Le gold/XP vont direct au user.)

    System.out.println("CAMPAIGN ATTACK TEST OK (énergie -" + staminaCost + " ["
        + staminaBefore + "→" + staminaAfter + "], or +" + (goldAfter - goldBefore)
        + " [" + goldBefore + "→" + goldAfter + "], niveau " + chapter + "-" + level + " à "
        + st2.getStars() + "★, " + lootEarned.size() + " objet(s) — logique du jeu exécutée)");
  }
}
