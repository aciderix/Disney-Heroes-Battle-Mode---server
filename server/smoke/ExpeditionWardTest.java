import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.ExpeditionHelper;
import dhserver.*;

/**
 * EXPEDITION (#72) incrément 5 — WARDS HEBDOMADAIRES ({@code ExpeditionWeeklyInfo}). Les wards (modificateurs de
 * combat) tournent chaque semaine et ne s'appliquent qu'aux difficultés ≥ 3 (HARD/EPIC). Le POOL vient de la donnée
 * du jeu ({@code WardStats}) ; la rotation est générée serveur (déterministe par l'indice de semaine du jeu, backend
 * absent). Le test vérifie : structure via les accesseurs DU JEU {@code getWardsFor}/{@code getNextWardsFor}, rotation
 * déterministe (nextWards[semaine] == currentWards[semaine+1]), bornes hebdo, round-trip wire. Zéro invention (§4).
 */
public final class ExpeditionWardTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[expedition-ward] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(4904L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;

    GetExpeditionResponse r = ServerExpedition.response(su);
    ExpeditionWeeklyInfo w = r.weeklyWardInfo;
    check(w != null, "weeklyWardInfo non-null");
    check(w.currentWards != null && w.nextWards != null, "currentWards/nextWards non-null");
    System.out.println("[expedition-ward] currentWards=" + w.currentWards + " nextWards=" + w.nextWards);

    // Le pool du jeu (diff 3 & 4) est non vide → 2 wards exposés (HARD partagé + EPIC additionnel).
    check(w.currentWards.size() == 2, "2 wards exposés (HARD + EPIC), vu " + w.currentWards.size());

    // Accesseurs DU JEU : diff < 3 → aucun ward ; diff 3 → 1 ward ; diff 4 → 2 wards (subList cumulatif).
    check(ExpeditionHelper.getWardsFor(w, 1).isEmpty(), "diff 1 : aucun ward (fidélité)");
    check(ExpeditionHelper.getWardsFor(w, 2).isEmpty(), "diff 2 : aucun ward");
    check(ExpeditionHelper.getWardsFor(w, 3).size() == 1, "diff 3 : 1 ward");
    check(ExpeditionHelper.getWardsFor(w, 4).size() == 2, "diff 4 : 2 wards");
    check(ExpeditionHelper.getNextWardsFor(w, 4).size() == 2, "diff 4 : 2 next wards");
    // Les wards exposés sont bien des CombatModifier du POOL du jeu.
    for (Object o : w.currentWards) check(o instanceof CombatModifier && o.toString().startsWith("WARD_"),
        "ward = CombatModifier WARD_* du pool (vu " + o + ")");

    // Bornes hebdomadaires : expiration dans le futur, ≤ 7 jours.
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    check(w.currentWardExpiration > now, "expiration dans le futur");
    check(w.currentWardExpiration - now <= com.perblue.heroes.util.TimeUtil.MILLIS_PER_WEEK + 1000,
        "expiration ≤ 1 semaine");
    check(w.nextWardStartTime == w.currentWardExpiration, "nextWardStartTime = fin de la semaine courante");

    // ROTATION DÉTERMINISTE : les wards de « la semaine prochaine » == wards calculés pour semaine+1.
    long weekMs = com.perblue.heroes.util.TimeUtil.MILLIS_PER_WEEK;
    ExpeditionWeeklyInfo wNext = ServerExpedition.weeklyWardInfo(now + weekMs);
    check(w.nextWards.equals(wNext.currentWards),
        "nextWards == currentWards de la semaine suivante (rotation déterministe)");

    // Round-trip wire de la réponse complète (défaut nº3).
    WireCheck.assertRoundTrips(r);
    System.out.println("[expedition-ward] OK — wards hebdo (pool du jeu + rotation déterministe + bornes + accesseurs getWardsFor) (#72 incr. 5)");
  }
}
