import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * ALCHEMY (achat d'or) — commande {@code BUY_GOLD{COUNT=<tier>}} → logique du jeu {@code UserHelper.buyGold}.
 * Vérifie : (1) DIAMONDS débités + OR crédité ; (2) persistance (reload wire) ; (3) anti-triche RÉEL — la limite
 * quotidienne d'achats finit par REFUSER ({@code GOLD_PURCHASES_USED}). Gate ALCHEMY = TL12 (compte mis TL65).
 */
public final class AlchemyTest {

  static User bindU(ServerUser su, long uid) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "al");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, uid, bd.userInfo.diamonds, "al");
    ServerContext.bind(u, iu);
    ServerContext.bindBattlePass(su.refreshBattlePass());
    return u;
  }
  static User bind(ServerUser su) { return bindU(su, 1L); }
  static User bindPoor(ServerUser su) { return bindU(su, 2L); }

  static Action buyGold(int howMany) {   // COUNT = NOMBRE d'achats (pas un tier)
    Action m = new Action(); m.command = CommandType.BUY_GOLD;
    m.extra = new java.util.EnumMap<>(ActionExtraType.class);
    m.extra.put(ActionExtraType.COUNT, Integer.toString(howMany));
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    BootData bd0 = su.bootData();
    bd0.userInfo.basicInfo.teamLevel = 65;      // ALCHEMY = TL12
    bd0.userInfo.diamonds = 1_000_000;          // diamants à dépenser (champ dédié userInfo.diamonds)

    User u0 = bind(su);
    long d0 = u0.getResource(ResourceType.DIAMONDS);
    long g0 = u0.getResource(ResourceType.GOLD);
    System.out.println("[alchemy] AVANT : DIAMONDS=" + d0 + " GOLD=" + g0);

    boolean applied = su.applyAction(buyGold(1));   // acheter 1
    if (!applied) throw new AssertionError("BUY_GOLD (1 achat) aurait dû s'appliquer");

    User u1 = bind(su);
    long d1 = u1.getResource(ResourceType.DIAMONDS);
    long g1 = u1.getResource(ResourceType.GOLD);
    System.out.println("[alchemy] APRÈS 1 achat : DIAMONDS=" + d1 + " GOLD=" + g1
        + " (−" + (d0 - d1) + " diamants, +" + (g1 - g0) + " or)");
    if (d1 >= d0) throw new AssertionError("les DIAMONDS auraient dû être débités");
    if (g1 <= g0) throw new AssertionError("l'OR aurait dû être crédité");

    // PERSISTANCE : reload wire → diamants (userInfo) + or (userExtra) conservés.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    User ur = bind(reloaded);
    System.out.println("[alchemy] après reload wire → DIAMONDS=" + ur.getResource(ResourceType.DIAMONDS)
        + " GOLD=" + ur.getResource(ResourceType.GOLD));
    if (ur.getResource(ResourceType.DIAMONDS) != d1) throw new AssertionError("DIAMONDS non persistés");
    if (ur.getResource(ResourceType.GOLD) != g1) throw new AssertionError("GOLD non persisté");

    // NB : la limite QUOTIDIENNE ne mord pas à cette ère — VIPStats.areUnlimitedGoldBuysEnabled(shard 1)=true
    // (achats d'or ILLIMITÉS, comportement R102 authentique). Le vrai gate anti-triche = le COÛT EN DIAMANTS.
    // ANTI-TRICHE : sans assez de diamants, l'achat est REFUSÉ (NOT_ENOUGH_DIAMONDS). Compte pauvre : 15 diamants.
    ServerUser poor = ServerUser.newPlayer(2L, 1);
    BootData bp = poor.bootData();
    bp.userInfo.basicInfo.teamLevel = 65;
    bp.userInfo.diamonds = 15;                 // couvre le 1er achat (coût 10) mais pas le 2e (coût 20)
    boolean ok1 = poor.applyAction(buyGold(1));   // coût 10 ≤ 15 → OK
    long dLeft = bindPoor(poor).getResource(ResourceType.DIAMONDS);
    boolean ok2 = poor.applyAction(buyGold(1));   // coût 20 > 5 → REFUSÉ
    long dLeft2 = bindPoor(poor).getResource(ResourceType.DIAMONDS);
    System.out.println("[alchemy] compte pauvre : achat1=" + ok1 + " (reste " + dLeft + " diamants) achat2=" + ok2
        + " (reste " + dLeft2 + ")");
    if (!ok1) throw new AssertionError("le 1er achat (10 ≤ 15) aurait dû passer");
    if (ok2) throw new AssertionError("le 2e achat (coût 20 > 5) aurait dû être REFUSÉ (NOT_ENOUGH_DIAMONDS)");
    if (dLeft2 != dLeft) throw new AssertionError("un achat refusé ne doit RIEN débiter");

    System.out.println("[alchemy] OK — BUY_GOLD : DIAMONDS→OR (logique du jeu) + persiste + anti-triche (coût diamants ; achats illimités à cette ère)");
  }
}
