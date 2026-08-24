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
    int trialIndex = 0;                                                        // param admin --trial (index du trial de saison)
    int trialModifiers = 0;                                                    // param admin --modifiers (nb de combat modifiers/nœud)
    boolean chestDiscount = false, closeChestDiscount = false;                 // CHEST_DISCOUNT (live-ops)
    long chestDiscountID = 900_002L;                                           // eventID stable par défaut
    int chestPercent = 50;                                                     // param admin --percent (% de remise)
    java.util.List<com.perblue.heroes.network.messages.ChestType> chestTargets = new java.util.ArrayList<>();  // --chest (répétable)
    boolean chancesBoost = false, closeChancesBoost = false;                  // INCREASED_CHANCES (live-ops)
    long chancesBoostID = 900_003L;                                           // eventID stable par défaut
    java.util.Map<String, Integer> chanceMods = new java.util.LinkedHashMap<>();  // --chance-type X --additional N
    String pendingChanceType = null;                                          // dernier --chance-type en attente d'un --additional
    String merchantEvent = null; boolean closeMerchant = false;               // "TRADER_DISCOUNT"/"TRADER_REFRESH_DISCOUNT"
    long merchantID = 900_004L; int merchantPercent = 50;                     // params admin
    java.util.List<com.perblue.heroes.network.messages.MerchantType> merchantTargets = new java.util.ArrayList<>();  // --merchant (répétable)
    String miscEvent = null; boolean closeMisc = false;                       // "MISC_BONUS"/"MISC_DISCOUNT"
    long miscID = 900_005L; int miscValue = 50;                               // params admin
    java.util.List<com.perblue.heroes.game.specialevent.MultiplierType> miscTargets = new java.util.ArrayList<>();   // --mult (répétable)
    boolean flagLogin = false, closeFlagLogin = false;                        // FLAG_USER_ON_LOGIN
    long flagLoginID = 900_007L;
    java.util.List<com.perblue.heroes.game.objects.UserFlag> flagsSet = new java.util.ArrayList<>(), flagsClear = new java.util.ArrayList<>();
    boolean teamLevelEvent = false, closeTeamLevel = false, teamLevelEvery = false;  // FREE_STUFF_AT/EVERY_X_TEAM_LEVEL
    long teamLevelID = 900_008L; int teamLevelValue = 50;
    java.util.List<String> tlItems = new java.util.ArrayList<>(); java.util.List<Integer> tlQtys = new java.util.ArrayList<>();
    String pendingTlItem = null;
    boolean extraChest = false, closeExtraChest = false;                       // EXTRA_CHEST (coffre bonus CRATES)
    long extraChestID = 900_009L;
    int ecCost = 100, ecBuyX = 10, ecMaxBuys = 50, ecMaxPurchases = 5, ecFreeBuys = 0, ecDraws = 1;
    com.perblue.heroes.network.messages.ResourceType ecCurrency = com.perblue.heroes.network.messages.ResourceType.DIAMONDS;
    String ecTitle = "Event Crate", ecInfo = "Limited-time bonus crate!";
    java.util.List<ServerEvents.ChestDrop> ecDrops = new java.util.ArrayList<>();  // --ec-drop RESULT[:QTY[:WEIGHT]] (répétable)
    boolean contest = false, closeContest = false, contestGuild = false, contestAggregate = false;   // CONTEST (leaderboard)
    long contestID = 900_010L;
    java.util.List<ServerEvents.ContestTask> contestTasks = new java.util.ArrayList<>();       // --contest-task TYPE:POINTS:COUNT[:MAXTIMES:MAXDAILY]
    java.util.List<ServerEvents.ContestProgress> contestProgress = new java.util.ArrayList<>(); // --contest-progress POINTS:ITEM:QTY
    java.util.List<ServerEvents.ContestRank> contestRanks = new java.util.ArrayList<>();        // --contest-rank / --contest-rank-unit KIND:RANK:ID:QTY
    boolean contestEnd = false; long contestEndID = 900_010L;                                   // --contest-end <id> (clôture : rankRewards)
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
        case "--chest-discount":  chestDiscount = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) chestDiscountID = Long.parseLong(a[++i]); break;
        case "--close-chest-discount": closeChestDiscount = true; break;
        case "--chest":      chestTargets.add(com.perblue.heroes.network.messages.ChestType.valueOf(a[++i].toUpperCase())); break;
        case "--percent":    chestPercent = Integer.parseInt(a[++i]); break;
        case "--chances-boost":  chancesBoost = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) chancesBoostID = Long.parseLong(a[++i]); break;
        case "--close-chances-boost": closeChancesBoost = true; break;
        case "--chance-type": pendingChanceType = a[++i]; chanceMods.putIfAbsent(pendingChanceType, 1); break;  // défaut +1
        case "--additional": if (pendingChanceType != null) chanceMods.put(pendingChanceType, Integer.parseInt(a[++i]));
                             else System.out.println("[events] --additional sans --chance-type préalable, ignoré"); break;
        case "--merchant-discount":         merchantEvent = "TRADER_DISCOUNT";
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) merchantID = Long.parseLong(a[++i]); break;
        case "--merchant-refresh-discount": merchantEvent = "TRADER_REFRESH_DISCOUNT";
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) merchantID = Long.parseLong(a[++i]); break;
        case "--close-merchant-discount":   closeMerchant = true; break;
        case "--merchant":   merchantTargets.add(com.perblue.heroes.network.messages.MerchantType.valueOf(a[++i].toUpperCase())); break;
        case "--merchant-percent": merchantPercent = Integer.parseInt(a[++i]); break;
        case "--misc-bonus":   miscEvent = "MISC_BONUS";
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) miscID = Long.parseLong(a[++i]); break;
        case "--misc-discount": miscEvent = "MISC_DISCOUNT";
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) miscID = Long.parseLong(a[++i]); break;
        case "--close-misc":   closeMisc = true; break;
        case "--mult":         miscTargets.add(com.perblue.heroes.game.specialevent.MultiplierType.valueOf(a[++i].toUpperCase())); break;
        case "--misc-value":   miscValue = Integer.parseInt(a[++i]); break;
        case "--flag-login":   flagLogin = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) flagLoginID = Long.parseLong(a[++i]); break;
        case "--close-flag-login": closeFlagLogin = true; break;
        case "--set-flag":     flagsSet.add(com.perblue.heroes.game.objects.UserFlag.valueOf(a[++i].toUpperCase())); break;
        case "--clear-flag":   flagsClear.add(com.perblue.heroes.game.objects.UserFlag.valueOf(a[++i].toUpperCase())); break;
        case "--team-level":   teamLevelEvent = true; teamLevelValue = Integer.parseInt(a[++i]); break;
        case "--every":        teamLevelEvery = true; break;
        case "--close-team-level": closeTeamLevel = true; break;
        case "--reward-item":  pendingTlItem = a[++i].toUpperCase(); tlItems.add(pendingTlItem); tlQtys.add(1); break;
        case "--reward-qty":   if (pendingTlItem != null) tlQtys.set(tlQtys.size() - 1, Integer.parseInt(a[++i])); break;
        case "--contest": contest = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) contestID = Long.parseLong(a[++i]); break;
        case "--close-contest": closeContest = true; break;
        case "--contest-end": contestEnd = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) contestEndID = Long.parseLong(a[++i]); break;
        case "--contest-guild": contestGuild = true; break;
        case "--contest-aggregate": contestAggregate = true; break;
        case "--contest-task": {          // TYPE:POINTS:COUNT[:MAXTIMES:MAXDAILY]
          String[] p = a[++i].split(":");
          contestTasks.add(new ServerEvents.ContestTask(p[0].toUpperCase(),
              p.length > 1 ? Integer.parseInt(p[1]) : 10, p.length > 2 ? Integer.parseInt(p[2]) : 1,
              p.length > 3 ? Integer.parseInt(p[3]) : -1, p.length > 4 ? Integer.parseInt(p[4]) : -1, "", ""));
          break;
        }
        case "--contest-progress": {      // POINTS:ITEM:QTY
          String[] p = a[++i].split(":");
          String drop = "{\"kind\":\"ITEM\",\"itemType\":\"" + p[1].toUpperCase() + "\",\"quantity\":" + (p.length > 2 ? p[2] : "1") + "}";
          contestProgress.add(new ServerEvents.ContestProgress(Long.parseLong(p[0]), java.util.Collections.singletonList(drop)));
          break;
        }
        case "--contest-rank": {          // KIND:RANK:ITEM:QTY  (KIND=percent|number)
          String[] p = a[++i].split(":");
          String drop = "{\"kind\":\"ITEM\",\"itemType\":\"" + p[2].toUpperCase() + "\",\"quantity\":" + (p.length > 3 ? p[3] : "1") + "}";
          contestRanks.add(new ServerEvents.ContestRank(!"number".equalsIgnoreCase(p[0]), Integer.parseInt(p[1]), java.util.Collections.singletonList(drop)));
          break;
        }
        case "--contest-rank-unit": {      // KIND:RANK:UNIT:QTY  (lot ultime = héros vedette)
          String[] p = a[++i].split(":");
          String drop = "{\"kind\":\"UNIT\",\"unitType\":\"" + p[2].toUpperCase() + "\",\"quantity\":" + (p.length > 3 ? p[3] : "1") + "}";
          contestRanks.add(new ServerEvents.ContestRank(!"number".equalsIgnoreCase(p[0]), Integer.parseInt(p[1]), java.util.Collections.singletonList(drop)));
          break;
        }
        case "--extra-chest": extraChest = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) extraChestID = Long.parseLong(a[++i]); break;
        case "--close-extra-chest": closeExtraChest = true; break;
        case "--ec-cost":     ecCost = Integer.parseInt(a[++i]); break;
        case "--ec-currency": ecCurrency = com.perblue.heroes.network.messages.ResourceType.valueOf(a[++i].toUpperCase()); break;
        case "--ec-buyx":     ecBuyX = Integer.parseInt(a[++i]); break;
        case "--ec-maxbuys":  ecMaxBuys = Integer.parseInt(a[++i]); break;
        case "--ec-maxpurchases": ecMaxPurchases = Integer.parseInt(a[++i]); break;
        case "--ec-freebuys": ecFreeBuys = Integer.parseInt(a[++i]); break;
        case "--ec-draws":    ecDraws = Integer.parseInt(a[++i]); break;
        case "--ec-title":    ecTitle = a[++i]; break;
        case "--ec-info":     ecInfo = a[++i]; break;
        case "--ec-drop": {
          String[] p = a[++i].split(":");
          ecDrops.add(new ServerEvents.ChestDrop(p[0].toUpperCase(), p.length > 1 ? p[1] : "1",
              p.length > 2 ? Integer.parseInt(p[2]) : 1));
          break;
        }
        case "--open-trial":  openTrial = true;
          if (i + 1 < a.length && a[i + 1].matches("\\d+")) trialID = Long.parseLong(a[++i]); break;
        case "--close-trial": closeTrial = true; break;
        case "--chances":    trialChances = Integer.parseInt(a[++i]); break;
        case "--title":      trialTitle = a[++i]; break;
        case "--trial":      trialIndex = Integer.parseInt(a[++i]); break;
        case "--modifiers":  trialModifiers = Integer.parseInt(a[++i]); break;
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
        specs.removeIf(js -> js.contains("TRIAL_FRANCHISE"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events TRIAL_FRANCHISE retirés (" + (before - specs.size()) + ").");
      }
      if (openTrial) {
        // Un seul franchise trial actif : on remplace tout TRIAL_FRANCHISE existant par le nouveau (id = eventID stable).
        specs.removeIf(js -> js.contains("TRIAL_FRANCHISE"));
        specs.add(ServerEvents.specJsonTrialFranchise(trialID, start, end, trialChances, trialTitle, trialIndex, trialModifiers));
        changed = true;
        System.out.println("[events] event TRIAL_FRANCHISE ajouté : eventID=" + trialID + " trial=" + trialIndex
            + " (franchises saison=" + ServerEvents.seasonTrialFranchises(trialIndex) + ") chances=" + trialChances
            + " title=\"" + trialTitle + "\" modifiers=" + trialModifiers + " (" + days + " j). Le client le verra via REFRESH_SPECIAL_EVENTS.");
      }

      if (closeChestDiscount) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("CHEST_DISCOUNT"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events CHEST_DISCOUNT retirés (" + (before - specs.size()) + ").");
      }
      if (chestDiscount) {
        if (chestTargets.isEmpty()) { System.out.println("[events] --chest-discount requiert au moins un --chest <TYPE> (ex. GOLD). Ignoré."); }
        else {
          specs.removeIf(js -> js.contains("CHEST_DISCOUNT"));
          specs.add(ServerEvents.specJsonChestDiscount(chestDiscountID, chestTargets, chestPercent, start, end));
          changed = true;
          System.out.println("[events] event CHEST_DISCOUNT ajouté : eventID=" + chestDiscountID + " coffres=" + chestTargets
              + " remise=" + chestPercent + "% (" + days + " j). Le client verra les prix remisés via REFRESH_SPECIAL_EVENTS.");
        }
      }

      if (closeChancesBoost) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("INCREASED_CHANCES"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events INCREASED_CHANCES retirés (" + (before - specs.size()) + ").");
      }
      if (chancesBoost) {
        if (chanceMods.isEmpty()) { System.out.println("[events] --chances-boost requiert au moins un --chance-type <TYPE> [--additional N]. Ignoré."); }
        else {
          specs.removeIf(js -> js.contains("INCREASED_CHANCES"));
          specs.add(ServerEvents.specJsonIncreasedChances(chancesBoostID, chanceMods, start, end));
          changed = true;
          System.out.println("[events] event INCREASED_CHANCES ajouté : eventID=" + chancesBoostID + " chances=" + chanceMods
              + " (" + days + " j). Le client verra les chances quotidiennes augmentées via REFRESH_SPECIAL_EVENTS.");
        }
      }

      if (closeMerchant) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("TRADER_DISCOUNT") || js.contains("TRADER_REFRESH_DISCOUNT"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events TRADER_DISCOUNT/TRADER_REFRESH_DISCOUNT retirés (" + (before - specs.size()) + ").");
      }
      if (merchantEvent != null) {
        if (merchantTargets.isEmpty()) { System.out.println("[events] --merchant-discount/-refresh-discount requiert au moins un --merchant <TYPE>. Ignoré."); }
        else {
          final String me = merchantEvent;
          specs.removeIf(js -> js.contains("\"" + me + "\""));
          specs.add(ServerEvents.specJsonMerchant(merchantEvent, merchantID, merchantTargets, merchantPercent, start, end));
          changed = true;
          System.out.println("[events] event " + merchantEvent + " ajouté : eventID=" + merchantID + " marchands=" + merchantTargets
              + " remise=" + merchantPercent + "% (" + days + " j). Prix remisés via REFRESH_SPECIAL_EVENTS.");
        }
      }

      if (closeMisc) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("MISC_BONUS") || js.contains("MISC_DISCOUNT"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events MISC_BONUS/MISC_DISCOUNT retirés (" + (before - specs.size()) + ").");
      }
      if (miscEvent != null) {
        if (miscTargets.isEmpty()) { System.out.println("[events] --misc-bonus/--misc-discount requiert au moins un --mult <TYPE> (ex. BONUS_ALCHEMY). Ignoré."); }
        else {
          final String me = miscEvent;
          specs.removeIf(js -> js.contains("\"" + me + "\""));
          specs.add(ServerEvents.specJsonMisc(miscEvent, miscID, miscTargets, miscValue, start, end));
          changed = true;
          System.out.println("[events] event " + miscEvent + " ajouté : eventID=" + miscID + " mults=" + miscTargets
              + " valeur=" + miscValue + "% (" + days + " j).");
        }
      }

      if (closeFlagLogin) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("FLAG_USER_ON_LOGIN"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events FLAG_USER_ON_LOGIN retirés (" + (before - specs.size()) + ").");
      }
      if (flagLogin) {
        if (flagsSet.isEmpty() && flagsClear.isEmpty()) { System.out.println("[events] --flag-login requiert au moins un --set-flag ou --clear-flag. Ignoré."); }
        else {
          specs.removeIf(js -> js.contains("FLAG_USER_ON_LOGIN"));
          specs.add(ServerEvents.specJsonFlagUserOnLogin(flagLoginID, flagsSet, flagsClear, start, end));
          changed = true;
          System.out.println("[events] event FLAG_USER_ON_LOGIN ajouté : eventID=" + flagLoginID + " set=" + flagsSet
              + " clear=" + flagsClear + " (" + days + " j).");
        }
      }

      if (closeTeamLevel) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("FREE_STUFF_AT_TEAM_LEVEL") || js.contains("FREE_STUFF_EVERY_X_TEAM_LEVEL"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events TEAM LEVEL retirés (" + (before - specs.size()) + ").");
      }
      if (teamLevelEvent) {
        if (tlItems.isEmpty()) { System.out.println("[events] --team-level requiert au moins un --reward-item <ITEM> [--reward-qty N]. Ignoré."); }
        else {
          specs.removeIf(js -> js.contains("FREE_STUFF_AT_TEAM_LEVEL") || js.contains("FREE_STUFF_EVERY_X_TEAM_LEVEL"));
          specs.add(ServerEvents.specJsonTeamLevel(teamLevelID, teamLevelValue, teamLevelEvery, tlItems, tlQtys, start, end));
          changed = true;
          System.out.println("[events] event TEAM LEVEL ajouté : eventID=" + teamLevelID + " niveau=" + teamLevelValue
              + (teamLevelEvery ? " (tous les X)" : " (au palier)") + " récompenses=" + tlItems + "×" + tlQtys + " (" + days + " j).");
        }
      }

      if (closeExtraChest) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("EXTRA_CHEST"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events EXTRA_CHEST retirés (" + (before - specs.size()) + ").");
      }
      if (extraChest) {
        if (ecDrops.isEmpty()) { System.out.println("[events] --extra-chest requiert au moins un --ec-drop RESULT[:QTY[:WEIGHT]] (ex. GOLD:100000:3). Ignoré."); }
        else {
          specs.removeIf(js -> js.contains("EXTRA_CHEST"));
          specs.add(ServerEvents.specJsonExtraChest(extraChestID, ecCost, ecCurrency, ecBuyX, ecMaxBuys, ecMaxPurchases,
              ecFreeBuys, true, ecTitle, ecInfo, ecDrops, ecDraws, start, end));
          changed = true;
          System.out.println("[events] event EXTRA_CHEST ajouté : eventID=" + extraChestID + " coût=" + ecCost + " " + ecCurrency
              + " buyX=" + ecBuyX + " maxBuys=" + ecMaxBuys + " freeBuys=" + ecFreeBuys + " loot=" + ecDrops.size()
              + " entrée(s) (" + days + " j). Coffre bonus visible sur CRATES via REFRESH_SPECIAL_EVENTS.");
        }
      }

      if (closeContest) {
        int before = specs.size();
        specs.removeIf(js -> js.contains("CONTEST"));
        changed = changed || specs.size() != before;
        System.out.println("[events] events CONTEST retirés (" + (before - specs.size()) + ").");
      }
      if (contest) {
        if (contestTasks.isEmpty()) { System.out.println("[events] --contest requiert au moins un --contest-task TYPE:POINTS:COUNT (ex. BATTLE_WON:10:1). Ignoré."); }
        else {
          specs.removeIf(js -> js.contains("CONTEST"));
          specs.add(ServerEvents.specJsonContest(contestID, contestGuild, contestAggregate, contestTasks, contestProgress, contestRanks, start, end));
          changed = true;
          System.out.println("[events] event CONTEST ajouté : eventID=" + contestID + (contestGuild ? " [GUILDE]" : " [solo]")
              + " tâches=" + contestTasks.size() + " paliers=" + contestProgress.size() + " rangs=" + contestRanks.size()
              + " (" + days + " j). Écran CONTESTS via REFRESH_SPECIAL_EVENTS.");
        }
      }

      if (contestEnd) {
        // CLÔTURE : distribue les rankRewards par rang final (ladder per-shard). ACTION (pas un changement de spec) →
        // hors du bloc `changed`. On reconstruit l'event CONTEST depuis la config pour lire ses rankRewards.
        com.perblue.common.specialevent.SpecialEventInfo target = null;
        for (com.perblue.common.specialevent.SpecialEventInfo e : ServerEvents.eventsFromConfig(s.loadShardState(shard, "operator_events")))
          if (e.getID() == contestEndID) { target = e; break; }
        if (target == null) System.out.println("[events] --contest-end : aucun CONTEST id=" + contestEndID + " dans la config. Ignoré.");
        else {
          int n = ServerContestData.distributeRankRewards(s, shard, contestEndID, target);
          System.out.println("[events] CONTEST " + contestEndID + " clôturé : " + n + " joueur(s) récompensé(s) par rang [courrier].");
        }
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
