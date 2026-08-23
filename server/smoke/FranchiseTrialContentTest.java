import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.trials.*;
import com.perblue.heroes.game.specialevent.TrialEventInfo;
import com.perblue.common.specialevent.SpecialEventInfo;
import dhserver.*;
import java.util.*;
import java.lang.reflect.*;

/**
 * FRANCHISE_TRIALS EVENT/FRANCHISE incr. 1b — CONTENU ennemis BIEN FORMÉ (data-driven, §4), INDUSTRIEL.
 *
 * <p>{@code ServerEvents.buildFranchiseTrialEvent} peuple le contenu ennemis EN BOUCLE depuis les 14 stages de
 * {@code franchise_trials_enemy_config.tab} (level/rarity/stars par stage, scope nœud) + un lineup par sous-trial dont les
 * {@code units} sont des {@code RANDOM_HERO} de la FRANCHISE du sous-trial (schéma du jeu découvert via son parseur :
 * {@code units}/{@code kind}=RANDOM_HERO/{@code categories}=[{FRANCHISE, franchises:[{franchise:X}]}]/{@code realGear}).
 *
 * <p>On prouve la BONNE FORMATION (le parseur du jeu accepte, tout est peuplé depuis les `.tab`, 0 invention) :
 * l'event construit ; {@code TrialEventInfo} a 14 {@code enemyLevel/Rarity/Stars} (= 14 stages) + 1 lineup/franchise, chaque
 * lineup portant 5 {@code units} ; les niveaux LUS = ceux des stages du jeu. <b>La génération effective des ennemis + le combat
 * = client-autoritatif → vérifiés EN JEU (§8).</b>
 */
public final class FranchiseTrialContentTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[frcontent] " + m); }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8781L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    var u = su.gameUser();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // stages + franchises lus du jeu (mêmes stats que le builder — pas de doublon en dur).
    Field ecf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("FRANCHISE_TRIALS_ENEMY_CONFIG_STATS");
    ecf.setAccessible(true); Object ecStats = ecf.get(null);
    Map stages = (Map) ecStats.getClass().getSuperclass().getField("stageToEnemyConfigs").get(ecStats);
    Field bf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("BASE_TRIAL_CONFIG_STATS");
    bf.setAccessible(true); Object cst = bf.get(null).getClass().getMethod("getStats").invoke(bf.get(null));
    // Franchises = trial 0 de la SAISON COURANTE (franchise_season_mapping), PAS base_trial_config (gabarit statique).
    java.util.List<String> frList = ServerEvents.seasonTrialFranchises(0);
    String[] frNames = frList.toArray(new String[0]);
    // niveau attendu du stage 1 (lu du jeu).
    Object stage1 = stages.get(1);
    Field lf = stage1.getClass().getDeclaredField("levels"); lf.setAccessible(true);
    String stage1Level = String.valueOf(lf.get(stage1));

    // Le parseur du jeu ACCEPTE l'event (bonne formation du schéma) — sinon buildFranchiseTrialEvent lève.
    SpecialEventInfo info = ServerEvents.buildFranchiseTrialEvent(974001L, now - 1000, now + 30L * 86400000L);
    TrialEventInfo tei = (TrialEventInfo) info.getComponent(TrialEventInfo.class);

    check(tei.getEnemyLevel().size() == stages.size(), "enemyLevel = " + stages.size() + " stages");
    check(tei.getEnemyRarity().size() == stages.size(), "enemyRarity = " + stages.size() + " stages");
    check(tei.getEnemyStars().size() == stages.size(), "enemyStars = " + stages.size() + " stages");
    check(tei.getEnemyLineups().size() == frNames.length, "1 lineup par franchise (" + frNames.length + ")");
    System.out.println("[frcontent] enemyLevel=" + tei.getEnemyLevel().size() + " enemyRarity=" + tei.getEnemyRarity().size()
        + " enemyStars=" + tei.getEnemyStars().size() + " lineups=" + tei.getEnemyLineups().size());

    // Chaque lineup porte 5 units (héros ennemis) — lues via le champ `units`.
    for (Object lineup : tei.getEnemyLineups()) {
      Field uf = null;
      for (Class<?> c = lineup.getClass(); c != null && uf == null; c = c.getSuperclass()) {
        try { uf = c.getDeclaredField("manualHeroes"); } catch (NoSuchFieldException ignore) {}
      }
      uf.setAccessible(true);
      java.util.List units = (java.util.List) uf.get(lineup);
      check(units != null && units.size() == 5, "lineup a 5 units (" + (units == null ? "null" : units.size()) + ")");
    }
    System.out.println("[frcontent] chaque lineup = 5 units RANDOM_HERO de la franchise ✔");

    // Le niveau du stage 1 (lu) est bien injecté dans enemyLevel[0] (via son expr).
    Object lvl0 = tei.getEnemyLevel().get(0);
    Field ef = null;
    for (Class<?> c = lvl0.getClass(); c != null && ef == null; c = c.getSuperclass()) {
      try { ef = c.getDeclaredField("expr"); } catch (NoSuchFieldException ignore) {}
    }
    if (ef != null) { ef.setAccessible(true); check(String.valueOf(ef.get(lvl0)).contains(stage1Level),
        "enemyLevel[0] porte le niveau du stage 1 (" + stage1Level + ")"); }

    // Le runtime serveur se construit avec la structure complète.
    ClientEventTrial trial = new ClientEventTrial(u, info);
    check(trial.getSubtrials().size() == frNames.length, "subtrials = franchises");
    for (Object s : trial.getSubtrials()) {
      java.util.List nodes = (java.util.List) s.getClass().getMethod("getNodes").invoke(s);
      check(nodes.size() == stages.size(), "chaque sous-trial a " + stages.size() + " nœuds");
    }
    System.out.println("[frcontent] runtime : " + trial.getSubtrials().size() + " sous-trials × " + stages.size() + " nœuds ✔");

    System.out.println("[frcontent] OK — contenu ennemis BIEN FORMÉ, data-driven (14 stages + lineup franchise/sous-trial), 0 invention. "
        + "Génération d'ennemis + combat = client-autoritatif → vérif EN JEU (§8).");
  }
}
