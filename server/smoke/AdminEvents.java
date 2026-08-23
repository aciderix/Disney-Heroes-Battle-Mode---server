import com.perblue.heroes.network.messages.GameMode;
import com.badlogic.gdx.utils.JsonValue;
import dhserver.*;
import java.util.*;

/**
 * PANNEAU ADMIN ÉVÉNEMENTS (opérateur live-ops) — pendant d'{@code AdminClock}/{@code AdminWar}/{@code AdminInvasion}.
 *
 * <p>Fait §8 (g130) : la ROTATION quotidienne des modes (PORT_DOCKS [6,4,2,1] / PORT_WAREHOUSE [7,5,3,1], FRANCHISE_TRIALS…)
 * est le comportement PAR DÉFAUT du jeu ({@code DifficultyModeHelper.getOpenDays}, appliqué par {@code isOpen} SANS event).
 * Cet outil gère les <b>OVERRIDES OPÉRATEUR</b> : forcer un mode ouvert un jour HORS son planning (live-ops), via les composants
 * DU JEU {@code ModesOpen} (ouverture pure) ou {@code DropBonus} (ouverture + bonus de drop). La CONFIG (liste de specs) est
 * persistée dans {@code shard_state} (clé {@code operator_events}) et reconstruite au boot par {@code LoginServer} via NOS
 * builders. Défaut = AUCUN override = rotation fidèle du jeu.
 *
 * <pre>
 * Usage :
 *   AdminEvents [--db …] [--shard 1] --status
 *   AdminEvents [--db …] --open &lt;GAME_MODE&gt; [--days N]        (override MODES_OPEN : force ouvert N jours, défaut 30)
 *   AdminEvents [--db …] --drop-bonus &lt;GAME_MODE&gt; [--days N] [--bonus B]  (override DropBonus + bonus de drop)
 *   AdminEvents [--db …] --close &lt;GAME_MODE&gt;                   (retire les overrides ouvrant ce mode)
 *   AdminEvents [--db …] --clear                              (retire TOUS les overrides → rotation par défaut)
 * </pre>
 * NB : le serveur charge la config AU BOOT → redémarrer le serveur après un changement.
 */
public final class AdminEvents {
  static final GameMode[] WATCH = { GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE };

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "server/data/dh-server.db"; int shard = 1;
    String open = null, dropBonus = null, close = null; boolean status = false, clear = false;
    boolean openTrial = false, closeTrial = false; long trialID = 900_001L;   // eventID stable par défaut du franchise trial
    int trialChances = ServerEvents.DEFAULT_TRIAL_CHANCES;                     // param admin (défaut = vérité terrain 10)
    String trialTitle = ServerEvents.DEFAULT_TRIAL_TITLE;                      // param admin --title
    int days = 30, bonus = 1;
    for (int i = 0; i < a.length; i++) {
      switch (a[i]) {
        case "--db":         db = a[++i]; break;
        case "--shard":      shard = Integer.parseInt(a[++i]); break;
        case "--status":     status = true; break;
        case "--clear":      clear = true; break;
        case "--open":       open = a[++i].toUpperCase(); break;
        case "--drop-bonus": dropBonus = a[++i].toUpperCase(); break;
        case "--close":      close = a[++i].toUpperCase(); break;
        case "--open-trial":  openTrial = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) trialID = Long.parseLong(a[++i]); break;
        case "--close-trial": closeTrial = true; break;
        case "--chances":    trialChances = Integer.parseInt(a[++i]); break;
        case "--title":      trialTitle = a[++i]; break;
        case "--days":       days = Integer.parseInt(a[++i]); break;
        case "--bonus":      bonus = Integer.parseInt(a[++i]); break;
        default: System.out.println("[events] arg ignoré: " + a[i]);
      }
    }
    try (UserStore s = new UserStore(db)) {
      // Charge la config existante (specs), sous forme de chaînes JSON réutilisables.
      List<String> specs = new ArrayList<>();
      for (JsonValue sp : ServerEvents.configSpecs(s.loadShardState(shard, "operator_events"))) specs.add(sp.toString());
      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      long start = now - 86_400_000L, end = now + (long) days * 86_400_000L;
      boolean changed = false;

      if (clear) { specs.clear(); changed = true; System.out.println("[events] TOUS les overrides retirés → rotation par défaut."); }
      if (open != null) {
        specs.add(ServerEvents.specJson("MODES_OPEN", Collections.singletonList(GameMode.valueOf(open)), 0, start, end));
        changed = true; System.out.println("[events] override MODES_OPEN ajouté : " + open + " forcé ouvert " + days + " j.");
      }
      if (dropBonus != null) {
        specs.add(ServerEvents.specJson("DROP_BONUS", Collections.singletonList(GameMode.valueOf(dropBonus)), bonus, start, end));
        changed = true; System.out.println("[events] override DropBonus ajouté : " + dropBonus + " (bonus " + bonus + ") " + days + " j.");
      }
      if (close != null) {
        final String closeMode = close;
        int before = specs.size();
        specs.removeIf(js -> specHasMode(js, closeMode));
        changed = changed || specs.size() != before;
        System.out.println("[events] overrides ouvrant " + close + " retirés (" + (before - specs.size()) + ").");
      }
      if (closeTrial) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("\"TRIAL_FRANCHISE\""));
        changed = changed || specs.size() != before;
        System.out.println("[events] events TRIAL_FRANCHISE retirés (" + (before - specs.size()) + ").");
      }
      if (openTrial) {
        // Un seul franchise trial actif : on remplace tout TRIAL_FRANCHISE existant par le nouveau (id = eventID stable).
        specs.removeIf(js -> js.contains("\"TRIAL_FRANCHISE\""));
        specs.add(ServerEvents.specJsonTrialFranchise(trialID, start, end, trialChances, trialTitle));
        changed = true;
        System.out.println("[events] event TRIAL_FRANCHISE ajouté : eventID=" + trialID + " chances=" + trialChances
            + " title=\"" + trialTitle + "\" (franchises de la saison, " + days + " j). Le client le verra via REFRESH_SPECIAL_EVENTS.");
      }

      if (changed) {
        s.saveShardState(shard, "operator_events", ServerEvents.writeConfig(specs));
        System.out.println("[events] persisté (shard " + shard + ", " + specs.size() + " override(s)). ⚠ redémarrer le serveur.");
      }
      if (status || !changed) printStatus(s.loadShardState(shard, "operator_events"));
    }
  }

  /** La spec (JSON) ouvre-t-elle ce mode ? (recherche du nom dans la liste "modes"). */
  static boolean specHasMode(String specJson, String mode) {
    JsonValue sp = new com.badlogic.gdx.utils.JsonReader().parse(specJson);
    JsonValue ms = sp.get("modes");
    if (ms != null) for (JsonValue m = ms.child; m != null; m = m.next) if (mode.equals(m.asString())) return true;
    return false;
  }

  static void printStatus(byte[] configBlob) {
    // On dérive les modes overridés DIRECTEMENT des specs persistées (pas d'install/refresh : celui-ci exige un user bindé
    // et emprunte un chemin guild — au BOOT le serveur l'installe avec un vrai user). Ici : lecture pure de la config.
    List<JsonValue> specs = ServerEvents.configSpecs(configBlob);
    Set<String> overridden = new LinkedHashSet<>();
    for (JsonValue sp : specs) {
      String kind = sp.getString("kind", "MODES_OPEN");
      JsonValue ms = sp.get("modes");
      if (ms != null) for (JsonValue m = ms.child; m != null; m = m.next) overridden.add(m.asString() + " [" + kind + "]");
    }
    System.out.println("[events] " + specs.size() + " override(s) opérateur configuré(s)"
        + (overridden.isEmpty() ? "" : " : " + overridden) + ".");
    for (GameMode m : WATCH) {
      boolean forced = false;
      for (JsonValue sp : specs) { JsonValue ms = sp.get("modes");
        if (ms != null) for (JsonValue x = ms.child; x != null; x = x.next) if (m.name().equals(x.asString())) forced = true; }
      var days = com.perblue.heroes.game.logic.DifficultyModeHelper.getOpenDays(m);
      System.out.println("   " + m + " : override=" + (forced ? "OUI (forcé ouvert par la config)" : "non")
          + " | jours d'ouverture par défaut (getOpenDays) = " + days);
    }
    System.out.println("[events] (sans override, un mode ouvre seulement ses jours getOpenDays — rotation fidèle du jeu.)");
  }
}
