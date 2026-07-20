import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.unit.UnitStats;
import com.perblue.heroes.game.logic.HeroHelper;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * UNLOCK_HERO — débloquer un héros avec ses fragments (ex. Vanellope, 10× STONE_VANELLOPE ; flux amorcé par
 * le tuto). Logique du jeu {@code HeroHelper.unlock} : anti-triche RÉEL (assez de fragments, pas déjà possédé),
 * débit GOLD, consommation des fragments, ajout du héros au roster. Vérifie crédit + consommation + persistance
 * + anti-triche (déjà possédé / fragments insuffisants).
 */
public final class UnlockHeroTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "uh");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "uh");
    ServerContext.bind(u, iu);
    ServerContext.bindBattlePass(su.refreshBattlePass());
    return u;
  }

  static void giveStones(User u, ItemType stone, int n) {
    RewardDrop r = new RewardDrop();
    r.itemType = stone; r.quantity = n;
    RewardHelper.giveReward(u, r, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, false, "test unlock");
  }

  static Action unlock(UnitType hero) {
    Action m = new Action();
    m.command = CommandType.UNLOCK_HERO;
    m.heroType = hero;
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    UnitType V = UnitType.VANELLOPE;
    ItemType stone = UnitStats.getStoneType(V);
    int need = UnitStats.getUnlockStones(V);

    ServerUser su = ServerUser.newPlayer(1L, 1);
    User u = bind(su);

    // 0) Donner de l'or (le déblocage débite un coût GOLD — un compte neuf a 0 or ; le vrai compte en a 226M).
    com.perblue.heroes.game.logic.UserHelper.giveUser(u, ResourceType.GOLD, 5_000_000L,
        com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test gold");

    // 1) Donner exactement le nb de fragments requis.
    giveStones(u, stone, need);
    System.out.println("[unlock] " + stone + " en inventaire = " + u.getItemAmount(stone)
        + " (requis " + need + ") | canUnlock=" + HeroHelper.canUnlock(V, u) + " déjàHéros=" + (u.getHero(V) != null));
    if (u.getItemAmount(stone) != need) throw new AssertionError("fragments non crédités");
    if (!HeroHelper.canUnlock(V, u)) throw new AssertionError("canUnlock devrait être true (fragments OK)");

    long goldBefore = u.getResource(ResourceType.GOLD);

    // 2) UNLOCK via le serveur.
    boolean ok = su.applyAction(unlock(V));
    User u2 = bind(su);
    boolean hasHero = u2.getHero(V) != null;
    int stonesAfter = u2.getItemAmount(stone);
    long goldAfter = u2.getResource(ResourceType.GOLD);
    System.out.println("[unlock] UNLOCK_HERO appliqué=" + ok + " | héros=" + hasHero
        + " fragments restants=" + stonesAfter + " gold " + goldBefore + "→" + goldAfter);
    if (!ok || !hasHero) throw new AssertionError("Vanellope aurait dû être débloquée");
    if (stonesAfter != 0) throw new AssertionError("les fragments auraient dû être consommés");
    if (goldAfter >= goldBefore) throw new AssertionError("le coût GOLD aurait dû être débité");

    // 3) PERSISTANCE : reload wire → Vanellope dans le roster, 0 fragment.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    User ur = bind(reloaded);
    System.out.println("[unlock] après reload → héros Vanellope=" + (ur.getHero(V) != null)
        + " fragments=" + ur.getItemAmount(stone));
    if (ur.getHero(V) == null) throw new AssertionError("le héros débloqué aurait dû PERSISTER");
    if (ur.getItemAmount(stone) != 0) throw new AssertionError("la consommation des fragments aurait dû PERSISTER");

    // 4) ANTI-TRICHE (déjà possédé) : re-unlock → REFUSÉ.
    boolean again = su.applyAction(unlock(V));
    System.out.println("[unlock] RE-UNLOCK (déjà possédé) appliqué=" + again + " (doit être false)");
    if (again) throw new AssertionError("re-débloquer un héros possédé aurait dû être REFUSÉ");

    // 5) ANTI-TRICHE (fragments insuffisants) : nouveau compte avec 5 fragments (< 10) → REFUSÉ.
    ServerUser su2 = ServerUser.newPlayer(2L, 1);
    User w = bind(su2);
    giveStones(w, stone, need - 5);
    boolean poor = su2.applyAction(unlock(V));
    System.out.println("[unlock] UNLOCK avec " + (need - 5) + " fragments (< " + need + ") appliqué=" + poor + " (doit être false)");
    if (poor) throw new AssertionError("débloquer sans assez de fragments aurait dû être REFUSÉ (NOT_ENOUGH_STONES)");

    System.out.println("[unlock] OK — UNLOCK_HERO (héros crédité, fragments+gold consommés, persiste) + anti-triche (déjà possédé / fragments insuffisants)");
  }
}
