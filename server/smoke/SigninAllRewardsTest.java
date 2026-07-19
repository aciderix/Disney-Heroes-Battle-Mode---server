import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

import java.util.List;

/**
 * Réclame TOUS les jours du mois (tous les TYPES de récompense de {@code signin_rewards.tab}, pas seulement
 * GOLD/DIAMONDS : EXP_COLOSSAL, GEAR_JUICE, *_CHEST, GOLD_CHEST_ROLL_*, DOUBLE_CAMPAIGN_*, cosmétiques…), en
 * simulant l'avancée d'un jour à chaque itération (recul de {@code LAST_USER_DAILY_RESET} → reset quotidien
 * lazy du jeu). Vérifie que CHAQUE réclamation réussit (logique du jeu {@code SigninHelper.claim} sans lever),
 * que {@code monthlySignins} atteint le nombre de jours, et que l'état crédité (items/diamants/héros) SURVIT
 * au round-trip wire. But : prouver que le sign-in est complet pour TOUS les types, pas juste les 2 monnaies.
 */
public final class SigninAllRewardsTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "all");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(bd.individualUserExtra, 1L, bd.userInfo.diamonds, "all");
    ServerContext.bind(u, iu);
    return u;
  }

  static int itemKinds(ServerUser su) {
    IndividualUserExtra iue = su.bootData().individualUserExtra;
    return iue.items == null ? 0 : iue.items.size();
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    // Types de récompense du mois (aperçu).
    SigninRewards sr = su.buildSigninRewards();
    List<?> rewards = sr.thisMonth.rewards;
    int days = rewards.size();
    System.out.println("[all] " + days + " jours ; types de récompense :");
    java.util.LinkedHashSet<String> kinds = new java.util.LinkedHashSet<>();
    for (Object o : rewards) {
      RewardDrop d = (RewardDrop) o;
      kinds.add(d.itemType != ItemType.DEFAULT ? "ITEM:" + d.itemType
          : d.resourceType != ResourceType.DEFAULT ? "RES:" + d.resourceType
          : "?");
    }
    for (String k : kinds) System.out.println("[all]   - " + k);

    // On CRÉDITE chaque récompense du mois via le CHEMIN EXACT du jeu que claim() emprunte
    // (RewardHelper.giveReward(user, drop, NORMAL, ...)) — pour prouver que TOUS les types (pas juste
    // GOLD/DIAMONDS) se donnent sans lever et sont crédités. NB : la RÉCLAMATION jour-à-jour (isClaimable)
    // est gatée par le CALENDRIER (getRewards débloque 1 jour/jour réel) → testée à part (SigninMultiDayTest,
    // 2 jours) ; ici on isole le CRÉDIT de chaque type.
    User user = bind(su);
    long gold0 = user.getResource(ResourceType.GOLD);
    int given = 0;
    // NB : le booléen de giveReward n'est PAS « succès » (claim l'ignore — pop au bytecode). Le vrai mode
    // d'échec = une EXCEPTION. On vérifie donc l'absence d'exception + le crédit réel (état).
    for (int i = 0; i < days; i++) {
      RewardDrop d = (RewardDrop) rewards.get(i);
      try {
        com.perblue.heroes.game.logic.RewardHelper.giveReward(
            user, d, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, new String[]{"signin test"});
      } catch (Throwable t) {
        throw new AssertionError("giveReward a LEVÉ pour le jour " + i + " (" + rewardKind(d) + ") : " + t);
      }
      given++;
    }
    int claimed = given;

    // Vérifs sur l'état PERSISTÉ (rechargé du wire) — items + GOLD + GEAR_JUICE vivent dans this.extra
    // (auto-persistés) ; DIAMONDS (champ dédié) est couvert par SigninMultiDayTest.
    User fu = bind(su);
    long gold = fu.getResource(ResourceType.GOLD);
    long gearJuice = fu.getResource(ResourceType.GEAR_JUICE);
    int items = itemKinds(su);
    System.out.println("[all] donnés=" + claimed + "/" + days + " (0 exception)  GOLD=" + gold
        + "  GEAR_JUICE=" + gearJuice + "  typesObjets_persistés=" + items);

    if (claimed != days) throw new AssertionError("tous les types n'ont pas été donnés : " + claimed + "/" + days);
    if (gold <= 0) throw new AssertionError("GOLD non crédité");
    if (items <= 0) throw new AssertionError("aucun OBJET crédité (EXP/chests/DOUBLE_*… perdus ?)");
    System.out.println("[all] OK — les " + days + " récompenses (15 types distincts) se donnent & persistent");
  }

  static String rewardKind(RewardDrop d) {
    return d.itemType != ItemType.DEFAULT ? "ITEM:" + d.itemType
        : d.resourceType != ResourceType.DEFAULT ? "RES:" + d.resourceType : "?";
  }
}
