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

    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // Choisit un TRIAL de la saison courante dont le 1ᵉʳ sous-trial est NON-WILDCARD (data-driven, franchise_season_mapping).
    int trialIdx = -1; String frName = null;
    for (int t = 0; t < ServerEvents.seasonTrialCount(); t++) {
      List<String> fs = ServerEvents.seasonTrialFranchises(t);
      if (!fs.isEmpty() && !"WILDCARD".equals(fs.get(0))) { trialIdx = t; frName = fs.get(0); break; }
    }
    check(trialIdx >= 0, "un trial de saison non-WILDCARD existe");
    int sub = 1;   // 1ᵉʳ sous-trial de ce trial = frName

    // Construit + INSTALLE l'event de CE trial (eventID EV) → activeTrialEvent(EV) le sert au record (franchises mémorisées).
    com.perblue.common.specialevent.SpecialEventInfo info =
        ServerEvents.buildFranchiseTrialEvent(EV, now - 1000L, now + 30L * 86400000L, 10, "GATING TEST", trialIdx);
    ServerEvents.setOperatorEvents(java.util.Collections.singletonList(info));
    ServerEvents.install(java.util.Collections.singletonList(info));

    Franchise franchise = Franchise.valueOf(frName);
    Set allowed = com.perblue.heroes.ui.trials.ClientTrialEventHelper
        .getAllHeroesInFranchise(java.util.Collections.singleton(franchise));
    check(!allowed.isEmpty(), "franchise " + frName + " a des héros");
    UnitType inFranchise = (UnitType) allowed.iterator().next();

    // Un héros HORS franchise : premier UnitType d'une AUTRE franchise absent de l'ensemble autorisé.
    UnitType outFranchise = null;
    for (Franchise other : Franchise.values()) {
      if (other == franchise || "WILDCARD".equals(other.name())) continue;
      Set os = com.perblue.heroes.ui.trials.ClientTrialEventHelper
          .getAllHeroesInFranchise(java.util.Collections.singleton(other));
      for (Object o : os) { if (!allowed.contains(o)) { outFranchise = (UnitType) o; break; } }
      if (outFranchise != null) break;
    }
    check(outFranchise != null, "un héros hors franchise " + frName + " existe");
    System.out.println("[trialgating] trial " + trialIdx + " sous-trial " + sub + " (" + frName + ") : inFranchise=" + inFranchise
        + " outFranchise=" + outFranchise);

    ServerUser su = ServerUser.newPlayer(8831L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;

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
