import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 5 — GATING FRANCHISE (anti-triche serveur-autoritatif §3).
 *
 * <p>Un sous-trial de franchise n'autorise QUE les héros de sa franchise. Le serveur REVALIDE le lineup rapporté par le client :
 * (1) un lineup 100% de la franchise du sous-trial est ACCEPTÉ ; (2) un lineup contenant un héros HORS franchise est REJETÉ
 * ({@code ClientErrorCodeException}). Franchise du sous-trial + appartenance = données du jeu ({@code ServerEvents.franchiseForSubtrial}
 * + {@code ClientTrialEventHelper.getAllHeroesInFranchise}), 0 invention (§4).
 */
public final class TrialGatingTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialgating] " + m); }

  @SuppressWarnings({"rawtypes", "unchecked"})
  static AttackBase lineup(UnitType... types) {
    AttackBase b = new AttackBase();
    b.outcome = CombatOutcome.WIN; b.stars = 3;
    b.defenders = new ArrayList<>();
    AttackLineupSummary ls = new AttackLineupSummary();
    ls.units = new ArrayList();
    for (UnitType t : types) { AttackUnitSummary u = new AttackUnitSummary(); u.type = t; ls.units.add(u); }
    b.attackers = new ArrayList<>(); ((List) b.attackers).add(ls);
    return b;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 992501L;

    // Sous-trial non-WILDCARD (franchises de la saison : subtrial i ↔ franchiseNamesInOrder().get(i-1)).
    List<String> frOrder = ServerEvents.franchiseNamesInOrder();
    int sub = -1; String frName = null;
    for (int i = 0; i < frOrder.size(); i++) {
      if (!"WILDCARD".equals(frOrder.get(i))) { sub = i + 1; frName = frOrder.get(i); break; }
    }
    check(sub > 0, "au moins un sous-trial de franchise (non-WILDCARD)");
    Franchise franchise = Franchise.valueOf(frName);
    Set allowed = com.perblue.heroes.ui.trials.ClientTrialEventHelper
        .getAllHeroesInFranchise(java.util.Collections.singleton(franchise));
    check(!allowed.isEmpty(), "franchise " + frName + " a des héros");
    UnitType inFranchise = (UnitType) allowed.iterator().next();

    // Un héros HORS franchise : premier UnitType absent de l'ensemble autorisé (autre franchise).
    UnitType outFranchise = null;
    for (String other : frOrder) {
      if (other.equals(frName) || "WILDCARD".equals(other)) continue;
      Set os = com.perblue.heroes.ui.trials.ClientTrialEventHelper
          .getAllHeroesInFranchise(java.util.Collections.singleton(Franchise.valueOf(other)));
      for (Object o : os) { if (!allowed.contains(o)) { outFranchise = (UnitType) o; break; } }
      if (outFranchise != null) break;
    }
    check(outFranchise != null, "un héros hors franchise " + frName + " existe");
    System.out.println("[trialgating] sous-trial " + sub + " (" + frName + ") : inFranchise=" + inFranchise
        + " outFranchise=" + outFranchise);

    ServerUser su = ServerUser.newPlayer(8831L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // (1) lineup 100% franchise → ACCEPTÉ (record se déroule, pas d'exception de gating).
    TrialEventAttack ok = new TrialEventAttack();
    ok.eventID = EV; ok.subtrialNumber = sub; ok.nodeNumber = 1; ok.stagesCleared = 3;
    ok.attackEndTime = now; ok.lootEarned = new ArrayList<>();
    ok.base = lineup(inFranchise);
    su.recordTrialEventAttack(ok);
    TrialEventData d = su.trialEventDataOrNull();
    check(d != null && d.subtrials.containsKey(Integer.valueOf(sub)), "attaque franchise-légale enregistrée");
    System.out.println("[trialgating] lineup 100% " + frName + " (" + inFranchise + ") → ACCEPTÉ ✔");

    // (2) lineup avec un héros HORS franchise → REJETÉ (ClientErrorCodeException).
    TrialEventAttack bad = new TrialEventAttack();
    bad.eventID = EV; bad.subtrialNumber = sub; bad.nodeNumber = 2; bad.stagesCleared = 3;
    bad.attackEndTime = now; bad.lootEarned = new ArrayList<>();
    bad.base = lineup(inFranchise, outFranchise);
    boolean rejected = false;
    try { su.recordTrialEventAttack(bad); }
    catch (com.perblue.heroes.ClientErrorCodeException e) { rejected = true; }
    check(rejected, "lineup avec héros hors franchise " + outFranchise + " REJETÉ par le gating serveur");
    System.out.println("[trialgating] lineup avec " + outFranchise + " (hors " + frName + ") → REJETÉ ✔");

    // (3) le nœud rejeté n'a rien enregistré (pas d'octroi sur refus).
    TrialEventData d2 = su.trialEventDataOrNull();
    TrialEventSubtrialData sd = (TrialEventSubtrialData) d2.subtrials.get(Integer.valueOf(sub));
    check(sd == null || sd.nodeLevelStatuses == null || !sd.nodeLevelStatuses.containsKey(Integer.valueOf(2)),
        "aucun statut enregistré pour le nœud refusé");
    System.out.println("[trialgating] refus = rien accordé (nœud 2 absent) ✔");

    System.out.println("[trialgating] OK — gating franchise serveur-autoritatif (données du jeu) : légal accepté, illégal refusé [headless].");
  }
}
