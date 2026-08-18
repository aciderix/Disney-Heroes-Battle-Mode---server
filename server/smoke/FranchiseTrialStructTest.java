import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.trials.*;
import com.perblue.heroes.game.specialevent.TrialEventInfo;
import com.perblue.heroes.game.specialevent.SpecialEventType;
import com.perblue.common.specialevent.SpecialEventInfo;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (#72) EVENT/FRANCHISE incr. 1 — STRUCTURE FIDÈLE du franchise trial (data-driven, §4).
 *
 * <p><b>Fait (§8, bytecode + données)</b> : un franchise trial est un SpecialEvent {@code TrialEventInfo} que le CLIENT LIT
 * des évènements actifs — c'était au backend PerBlue de le CONSTRUIRE depuis les `.tab` {@code patched_heroes_*}. Notre serveur
 * autoritatif joue ce rôle : {@code ServerEvents.buildFranchiseTrialEvent} LIT {@code base_trial_config} (via
 * {@code PatchStats.BASE_TRIAL_CONFIG_STATS.getStats()}) → NB de nœuds ({@code NODE_COUNT}) + franchises de la saison
 * ({@code FRANCHISES}) + resets/gating, et bâtit 1 sous-trial par franchise × {@code NODE_COUNT} nœuds. <b>0 invention</b>.
 *
 * <p>On prouve : (1) l'event se construit ; (2) {@code new ClientEventTrial(user, info)} (ctor pur serveur) expose
 * {@code getSubtrials().size()} == nb de franchises de la saison, chacun avec {@code NODE_COUNT} nœuds ; (3) les valeurs
 * viennent des `.tab` (cohérentes avec {@code base_trial_config}).
 */
public final class FranchiseTrialStructTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[frtrial] " + m); }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8780L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    var u = su.gameUser();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // Valeurs ATTENDUES lues du jeu (mêmes stats que le builder — pas de doublon en dur).
    var bf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("BASE_TRIAL_CONFIG_STATS");
    bf.setAccessible(true);
    Object cst = bf.get(null).getClass().getMethod("getStats").invoke(bf.get(null));
    var ncF = cst.getClass().getDeclaredField("NODE_COUNT"); ncF.setAccessible(true); int expNodeCount = ncF.getInt(cst);
    var frF = cst.getClass().getDeclaredField("FRANCHISES"); frF.setAccessible(true); String frStr = (String) frF.get(cst);
    int expFranchises = 0; for (String s : frStr.split(",")) if (!s.trim().isEmpty()) expFranchises++;
    System.out.println("[frtrial] base_trial_config : NODE_COUNT=" + expNodeCount + " FRANCHISES=" + frStr + " (" + expFranchises + ")");
    check(expNodeCount > 0 && expFranchises > 0, "base_trial_config non vide");

    // Construire l'event franchise trial + le runtime serveur.
    SpecialEventInfo info = ServerEvents.buildFranchiseTrialEvent(972001L, now - 1000, now + 30L * 86400000L);
    TrialEventInfo tei = (TrialEventInfo) info.getComponent(TrialEventInfo.class);
    check(tei != null, "composant TrialEventInfo présent");
    check(info.getType() == SpecialEventType.TRIAL, "type = TRIAL");

    ClientEventTrial trial = new ClientEventTrial(u, info);
    List subs = trial.getSubtrials();
    check(subs != null && subs.size() == expFranchises,
        "subtrials = nb franchises saison (" + (subs == null ? "null" : subs.size()) + " att " + expFranchises + ")");
    System.out.println("[frtrial] ClientEventTrial : subtrials=" + subs.size() + " (1/franchise) eventID=" + trial.getEventID());

    // Chaque sous-trial = NODE_COUNT nœuds.
    for (int i = 0; i < subs.size(); i++) {
      Object s = subs.get(i);
      List nodes = (List) s.getClass().getMethod("getNodes").invoke(s);
      check(nodes != null && nodes.size() == expNodeCount,
          "sous-trial " + i + " a NODE_COUNT nœuds (" + (nodes == null ? "null" : nodes.size()) + " att " + expNodeCount + ")");
    }
    System.out.println("[frtrial] chaque sous-trial a " + expNodeCount + " nœuds ✔ (data-driven, 0 invention)");

    // Franchises de l'event = celles de la saison (lues du .tab).
    check(tei.getFranchises() != null && tei.getFranchises().size() == expFranchises, "franchises event = saison");
    System.out.println("[frtrial] franchises event = " + tei.getFranchises());

    System.out.println("[frtrial] OK — structure FIDÈLE du franchise trial (subtrials/franchise × NODE_COUNT nœuds, lus de base_trial_config) [headless, structure].");
  }
}
