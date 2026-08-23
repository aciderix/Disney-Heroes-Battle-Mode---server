package dhserver;

import com.perblue.heroes.network.messages.GameMode;
import com.perblue.heroes.game.specialevent.SpecialEventType;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.common.specialevent.SpecialEvents;
import com.perblue.common.specialevent.SpecialEventBuilder;
import com.perblue.common.specialevent.components.EventVisibility;
import com.perblue.common.specialevent.components.ModesOpen;
import com.perblue.common.specialevent.components.ChestDiscount;
import com.perblue.common.specialevent.components.IEventComponent;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SPECIAL_EVENTS — moteur serveur-autoritatif d'événements live (live-ops opérateur). Cf. {@code docs/SPECIAL_EVENTS.md}.
 *
 * <p><b>Principe (industriel, §3/§4 — rien à la main)</b> : on ne réécrit NI le schéma JSON NI les règles. On
 * construit les événements avec les <b>classes DU JEU</b> ({@code SpecialEventInfo} + composants
 * {@code EventVisibility}/{@code ModesOpen}/…) via leur propre {@code load(info, fullJson, fullJson.get(key))}
 * (contrat relevé au bytecode), et on les injecte dans la <b>machinerie du jeu</b>
 * ({@code SpecialEventsHelper}) — c'est cette machinerie qui calcule ensuite {@code isModeOpen}, les snapshots, etc.
 * Généralisable à TOUS les composants (drop bonus, discounts, contest…) : un builder par composant, ajouté au besoin.
 *
 * <p><b>Ouverture de mode (incr. 1)</b> : {@link #buildModesOpenEvent} produit un {@code SpecialEventInfo} qui déclare
 * un ensemble de modes ouverts sur une fenêtre de temps. {@link #installOperatorEvents} les pose dans le
 * {@code SpecialEventsHelper} (le serveur autoritatif voit alors {@code isModeOpen}=true pour ces modes). Aucune carte
 * UI ({@code eventCardDisplay}) n'est requise pour l'AUTORITÉ serveur (pas de {@code checkUnitType} à l'injection) ; la
 * carte n'est nécessaire QUE si l'on pousse le JSON au client pour l'AFFICHAGE (à venir, incr. suivant).
 */
public final class ServerEvents {
  private ServerEvents() {}

  private static final JsonReader JSON = new JsonReader();

  /** Fenêtre par défaut d'un event opérateur : ouvert de « il y a 1 j » à « dans 30 j » (roulant, re-posé au boot). */
  public static long defaultStart() { return com.perblue.heroes.util.TimeUtil.serverTimeNow() - 86_400_000L; }
  public static long defaultEnd()   { return com.perblue.heroes.util.TimeUtil.serverTimeNow() + 30L * 86_400_000L; }

  /**
   * Construit un événement <b>MODES_OPEN</b> (composant {@code ModesOpen}) déclarant {@code modes} ouverts sur
   * {@code [startMs, endMs]}, via les classes du jeu (contrat de chargement {@code load(info, full, full.get(key))},
   * format flat {@code formatVersion=0}). {@code SpecialEventInfo.toJson()} en donnerait la forme canonique.
   */
  public static SpecialEventInfo buildModesOpenEvent(long id, Collection<GameMode> modes, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (GameMode m : modes) { if (inc.length() > 0) inc.append(','); inc.append("{\"gameMode\":\"").append(m.name()).append("\"}"); }
      String full =
          "{\"kind\":\"MODES_OPEN\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"gameModeFilter\":{\"include\":[" + inc + "]}}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.MODES_OPEN);
      setField(info, "formatVersion", 0);

      // Composants construits + chargés par les classes DU JEU (contrat load(info, full, full.get(key))).
      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      ModesOpen mo = new ModesOpen(SpecialEventType.MODES_OPEN, GameMode.class);
      mo.load(info, root, root);   // ModesOpen lit "gameModeFilter" sur le nœud complet (param2)
      addComponent(info, mo);

      // Carte d'affichage minimale (cachée) — requise pour que le JSON soit RE-PARSABLE par le CLIENT (checkUnitType).
      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildModesOpenEvent", e);
    }
  }

  /**
   * Construit un événement <b>DROP_BONUS</b> (composant {@code DropBonus}) déclarant {@code modes} affectés sur
   * {@code [startMs, endMs]}, via les classes du jeu. C'est le levier de la <b>ROTATION QUOTIDIENNE FIDÈLE</b> : contrairement
   * à MODES_OPEN (override, ouvre quel que soit le jour), un DropBonus rend {@code isModeDropBonusActive(mode)=true} et le jeu
   * applique alors <b>SA PROPRE table</b> {@code DifficultyModeHelper.getOpenDays(mode)} (PORT_DOCKS [6,4,2,1] / PORT_WAREHOUSE
   * [7,5,3,1]) → les modes s'ouvrent seulement leurs jours. Fait §8 (bytecode {@code isOpen}) :
   * {@code isOpen = isUnlocked && (isModeOpen || (isModeDropBonusActive && getOpenDays.contains(dayOfWeek)))}.
   *
   * <p>Contrat (bytecode {@code DropBonus.load(info, full, node)}) : lit {@code gameModeFilter}/{@code stuffFilter}/{@code bonus}
   * sur le nœud COMPLET (param2). Le composant est construit par la FABRIQUE du jeu ({@code createComponent("dropBonus")},
   * enregistré par {@code SpecialEventsHelper} avec {@code DropBonusFactory} → provider/generics câblés, §4). {@code refresh}
   * peuple {@code DropBonusSnapshot.multipliers} avec CHAQUE mode de {@code gameModeFilter} en clé → {@code isModeDropBonusActive}.
   * {@code stuffFilter} vide = n'affecte pas la clé (le peuplement boucle sur {@code affectedGameModes}, pas sur le stuff).
   *
   * @param bonus multiplicateur de bonus de drop (int) ; la rotation ne dépend QUE de la présence du mode en clé, mais un
   *              DropBonus « réel » porte un bonus (fidèle : le jeu ouvre PORT via un event à bonus). 0 = rotation sans bonus.
   */
  public static SpecialEventInfo buildDropBonusEvent(long id, Collection<GameMode> modes, int bonus, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (GameMode m : modes) { if (inc.length() > 0) inc.append(','); inc.append("{\"gameMode\":\"").append(m.name()).append("\"}"); }
      String full =
          "{\"kind\":\"DROP_BONUS\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"gameModeFilter\":{\"include\":[" + inc + "]},"
        + "\"stuffFilter\":{},\"bonus\":" + bonus + "}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.DROP_BONUS);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      // Composant DropBonus via la FABRIQUE du jeu (provider câblé) — load lit gameModeFilter/stuffFilter/bonus sur le full.
      IEventComponent db = SpecialEventBuilder.createComponent("dropBonus");
      Method load = findMethod(db.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      load.setAccessible(true);
      load.invoke(db, info, root, root.get("dropBonus"));
      addComponent(info, db);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildDropBonusEvent", e);
    }
  }

  /**
   * Construit un événement <b>CHEST_DISCOUNT</b> (composant {@code ChestDiscount}) : réduit de {@code percentOff}% le COÛT
   * d'ouverture des coffres de {@code chests} sur {@code [startMs, endMs]}. Contrat relevé au bytecode ({@code ChestDiscount.load}) :
   * lit {@code chestFilter} (EnumFilter sur clé {@code chestType}) et {@code percentOff} (int) sur le nœud COMPLET (param2). Le
   * composant est du jeu (ctor {@code (ISpecialEventType, Class)}, PAS de provider → construction directe comme {@code ModesOpen}).
   * Effet serveur : {@code BaseEventSnapshot.getChestPrice(chestType, base)} renvoie le prix remisé → {@code ChestHelper.getPurchaseCost}
   * (utilisé par {@code openChest} + l'anti-tamper {@code validateChestPurchase}) débite/valide le prix REMISÉ. 100 % data/objet du jeu.
   *
   * @param chests coffres visés (EnumFilter include) ; {@code percentOff} = pourcentage de remise (param ADMIN, ex. 50 = −50 %).
   */
  public static SpecialEventInfo buildChestDiscountEvent(long id, Collection<com.perblue.heroes.network.messages.ChestType> chests,
      int percentOff, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (com.perblue.heroes.network.messages.ChestType c : chests) {
        if (inc.length() > 0) inc.append(','); inc.append("{\"chestType\":\"").append(c.name()).append("\"}");
      }
      String full =
          "{\"kind\":\"CHEST_DISCOUNT\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"chestFilter\":{\"include\":[" + inc + "]},\"percentOff\":" + percentOff + "}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.CHEST_DISCOUNT);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      ChestDiscount cd = new ChestDiscount(SpecialEventType.CHEST_DISCOUNT, com.perblue.heroes.network.messages.ChestType.class);
      cd.load(info, root, root);   // lit chestFilter/percentOff sur le nœud complet (param2)
      addComponent(info, cd);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildChestDiscountEvent", e);
    }
  }

  /**
   * Construit un événement <b>INCREASED_CHANCES</b> (composant {@code IncreasedChances}) : ajoute des chances de combat quotidiennes
   * SUPPLÉMENTAIRES aux modes {@code chances} (clé = {@code chanceType} du jeu, valeur = nombre en plus) sur {@code [startMs, endMs]}.
   * Contrat relevé au bytecode ({@code IncreasedChances.load}) : lit {@code chanceModifierList} (tableau d'objets {@code {chanceType,
   * additional}}) sur le nœud COMPLET (param2). Le composant a un CONVERTER ({@code IChanceGameModeConverter}) → construit par la FABRIQUE
   * du jeu ({@code createComponent("increasedChances")}, converter câblé §4). Effet serveur : {@code DailyActivityHelper.getMaxDailyUses(user,
   * chanceType, snapshot)} = {@code BaseEventSnapshot.getChances(chanceType, base)} = base + additional (ex. {@code DifficultyModeHelper}
   * PORT/trials). {@code chanceType} valides (bytecode) : {@code portDocks_use}, {@code portWarehouse_use}, {@code spotlightTrial_use},
   * {@code teamTrialsBlue_use}/{@code teamTrialsYellow_use}/{@code teamTrialsRed_use}, {@code codebase_use}. Params ADMIN (type + nombre).
   */
  public static SpecialEventInfo buildIncreasedChancesEvent(long id, java.util.Map<String, Integer> chances, long startMs, long endMs) {
    try {
      StringBuilder list = new StringBuilder();
      for (java.util.Map.Entry<String, Integer> e : chances.entrySet()) {
        if (list.length() > 0) list.append(',');
        list.append("{\"chanceType\":\"").append(e.getKey()).append("\",\"additional\":").append(e.getValue()).append("}");
      }
      String full =
          "{\"kind\":\"INCREASED_CHANCES\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"chanceModifierList\":[" + list + "]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.INCREASED_CHANCES);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      IEventComponent ic = SpecialEventBuilder.createComponent("increasedChances");
      Method load = findMethod(ic.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      load.setAccessible(true);
      load.invoke(ic, info, root, root);   // lit chanceModifierList sur le nœud complet (param2)
      addComponent(info, ic);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildIncreasedChancesEvent", e);
    }
  }

  /**
   * Construit un événement <b>TRIAL</b> (composant {@code TrialEventInfo}, clé "trial") — le PRÉREQUIS de FRANCHISE_TRIALS
   * (tout trial est un événement spécial). <b>Object-path INDUSTRIEL</b> (décision utilisateur, patron {@code buildMinimalCard}) :
   * on construit le composant via la FABRIQUE du jeu ({@code createComponent("trial")}) puis on remplit ses champs par un
   * filler GÉNÉRIQUE PAR TYPE — PAS de JSON `TrialEventInfo` à la main (schéma riche/niché = anti-pattern proscrit). Le contenu
   * ennemis (listes vides ici) sera tiré des {@code .tab} aux incréments suivants (§4). Le trial est OUVERT tous les jours
   * ({@code activeDays=[EVERYDAY]}) et de type {@code HERO_SPOTLIGHT} (le plus simple : état per-user = compteur
   * {@code spotlightTrialUses}).
   *
   * @param trialType type de trial ({@code GenericTrialType.HERO_SPOTLIGHT} par défaut).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static SpecialEventInfo buildTrialEvent(long id, com.perblue.heroes.network.messages.GenericTrialType trialType,
                                                 long startMs, long endMs) {
    try {
      String full = "{\"kind\":\"TRIAL\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.TRIAL);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      // Composant "trial" via la FABRIQUE du jeu + remplissage GÉNÉRIQUE PAR TYPE (object-path, pas de JSON riche à la main).
      IEventComponent trial = SpecialEventBuilder.createComponent("trial");
      fillTrialFields(trial, trialType == null ? com.perblue.heroes.network.messages.GenericTrialType.HERO_SPOTLIGHT : trialType);
      addComponent(info, trial);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildTrialEvent", e);
    }
  }

  /**
   * Construit un événement <b>FRANCHISE TRIAL</b> FIDÈLE (structure) depuis les données du jeu ({@code PatchStats}
   * {@code patched_heroes_base_trial_config.tab}) — <b>rôle backend autoritatif</b> : le client LIT cet event des
   * évènements actifs ({@code isFranchiseTrialAvailable}) ; c'était au backend PerBlue de le construire depuis ces mêmes `.tab`.
   * <b>0 invention (§4)</b> : {@code NODE_COUNT}, {@code FRANCHISES} (les franchises de la saison), {@code MAX_DAILY_RESETS},
   * {@code WAVE_COUNT}, gating levels… sont LUS de {@code BASE_TRIAL_CONFIG_STATS.getStats()}. Structure produite :
   * 1 sous-trial par franchise de la saison × {@code NODE_COUNT} nœuds. (Le CONTENU ennemis/gating/récompenses par nœud =
   * incrément suivant : ennemis = héros de la franchise + stages de {@code franchise_trials_enemy_config.tab}.)
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  /**
   * Nombre de chances par reset d'un franchise trial. **PAS dans les `.tab`** (ni base_trial_config ni event_trial_constants) :
   * c'est une valeur BACKEND-AUTHORED du JSON d'event PerBlue → **paramètre ADMIN** (règle utilisateur : « défini par le serveur →
   * à l'admin de le définir »). Défaut = 10 (vérité terrain, captures en jeu « CHANCES LEFT: 10/10 »), surchargeable
   * (`AdminEvents --open-trial --chances N`). NON inventé : valeur observée, exposée comme paramètre.
   */
  public static final int DEFAULT_TRIAL_CHANCES = 10;
  /** Titre par défaut (param admin `--title` surchargeable). Libellé littéral, pas une clé de localisation. */
  public static final String DEFAULT_TRIAL_TITLE = "FRANCHISE TRIALS";

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs) {
    return buildFranchiseTrialEvent(id, startMs, endMs, DEFAULT_TRIAL_CHANCES, DEFAULT_TRIAL_TITLE, 0);
  }

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances) {
    return buildFranchiseTrialEvent(id, startMs, endMs, chances, DEFAULT_TRIAL_TITLE, 0);
  }

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances, String title) {
    return buildFranchiseTrialEvent(id, startMs, endMs, chances, title, 0, 0);
  }

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances, String title, int trialIndex) {
    return buildFranchiseTrialEvent(id, startMs, endMs, chances, title, trialIndex, 0);
  }

  /**
   * @param trialIndex index du TRIAL de la saison courante (0,1,2…) — détermine les franchises (=sous-trials), le questType et
   *   les jours actifs (data-driven, {@code seasonTrialConfigs}). L'admin choisit quel trial de saison activer (`AdminEvents --trial N`).
   * @param modifiersPerNode nb de combat modifiers ALÉATOIRES par nœud, tirés du POOL `event_trial_arena_rules.tab`
   *   ({@code TrialEventCombatModifier kind:RANDOM}). 0 = aucun (défaut). CONTENU = données du jeu (§4) ; le NOMBRE est un
   *   paramètre admin (le vrai event backend le fixe). `AdminEvents --modifiers N`.
   */
  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances, String title, int trialIndex, int modifiersPerNode) {
    try {
      SpecialEventInfo info = buildTrialEvent(id, com.perblue.heroes.network.messages.GenericTrialType.CAMPAIGN, startMs, endMs);
      Object trial = info.getComponent((Class) Class.forName("com.perblue.heroes.game.specialevent.TrialEventInfo"));

      // Lire base_trial_config VIA les stats du jeu (§3/§4) — pas de valeur en dur.
      Field bf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("BASE_TRIAL_CONFIG_STATS");
      bf.setAccessible(true);
      Object dhcs = bf.get(null);
      Object cst = dhcs.getClass().getMethod("getStats").invoke(dhcs);   // BaseTrialConfigConstants
      int nodeCount        = readInt(cst, "NODE_COUNT");
      int waveCount        = readInt(cst, "WAVE_COUNT");
      int maxDailyResets   = readInt(cst, "MAX_DAILY_RESETS");
      boolean allowRaiding = readBool(cst, "ENABLE_RAIDING");
      boolean statSlots    = readBool(cst, "ENABLE_STAT_SLOTS");
      int primeBadge       = readInt(cst, "PRIME_BADGE_LEVEL_REQ");
      int enhancedPrime    = readInt(cst, "ENHANCED_PRIME_BADGE_LEVEL_REQ");
      int patchLevelReq    = readInt(cst, "PATCH_LEVEL_REQ");
      // Franchises (= sous-trials) : DATA-DRIVEN par la SAISON COURANTE (franchise_season_mapping), PAS base_trial_config
      // (gabarit statique). Le trial de saison `trialIndex` donne ses franchises + questType (auto-rotation par date).
      java.util.List<String> seasonFr = seasonTrialFranchises(trialIndex);
      if (seasonFr.isEmpty()) seasonFr = seasonTrialFranchises(0);              // repli : 1ᵉʳ trial de la saison
      TRIAL_FRANCHISES_BY_EVENT.put(id, new java.util.ArrayList<>(seasonFr));   // mémorise pour le gating serveur

      Class franchiseCls = (Class) Class.forName("com.perblue.heroes.network.messages.Franchise");
      java.util.Set franchises = new java.util.LinkedHashSet();
      java.util.List subtrials = new java.util.ArrayList();
      java.util.List<String> frNames = new java.util.ArrayList<>();
      for (String fr : seasonFr) {
        fr = fr.trim(); if (fr.isEmpty()) continue;
        Object franchise = Enum.valueOf(franchiseCls, fr);
        franchises.add(franchise);
        frNames.add(fr);
        // 1 sous-trial par franchise. TITRE du sous-trial = nom de la franchise (DATA-DRIVEN §4) via EventString.unlocalized
        // (libellé littéral, pas une clé de localisation → plus de « NONE.TITLE »). WILDCARD garde son libellé (joker).
        Object sub = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventSubtrialInfo")
            .getConstructor(SpecialEventInfo.class, JsonValue.class)
            .newInstance(info, JSON.parse("{\"title\":{},\"preset\":\"none\"}"));
        setField(sub, "title", com.perblue.common.specialevent.EventString.unlocalized(info, prettyName(fr)));
        subtrials.add(sub);
      }
      // nodeCount : NODE_COUNT nœuds appliqués à TOUS les sous-trials (scope ALL). Clés EXACTES nodeCount/scope (schéma du jeu).
      Object tnc = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventNodeCount")
          .getConstructor(JsonValue.class, java.util.Map.class)
          .newInstance(JSON.parse("{\"nodeCount\":" + nodeCount + ",\"scope\":{}}"), new java.util.HashMap());

      // waveCount : WAVE_COUNT vagues par nœud (scope ALL). Requis : la vue du sous-trial (getCampaignEnemiesViewV2) divise
      // par le nombre de vagues → sans lui, / by zero au rendu client (§8, découvert EN JEU). Clés waveCount/scope (schéma du jeu).
      Object twc = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventWaveCount")
          .getConstructor(JsonValue.class, java.util.Map.class)
          .newInstance(JSON.parse("{\"waveCount\":" + waveCount + ",\"scope\":{}}"), new java.util.HashMap());

      setField(trial, "subtrials", subtrials);
      setField(trial, "nodeCount", new java.util.ArrayList(java.util.List.of(tnc)));
      setField(trial, "waveCount", new java.util.ArrayList(java.util.List.of(twc)));
      setField(trial, "franchises", franchises);
      setField(trial, "maxDailyResets", maxDailyResets);
      setField(trial, "allowRaiding", allowRaiding);
      setField(trial, "enableStatSlots", statSlots);
      setField(trial, "primeBadgeLevelReq", primeBadge);
      setField(trial, "enhancedPrimeBadgeLevelReq", enhancedPrime);
      setField(trial, "patchLevelReq", patchLevelReq);

      // CONTENU ennemis — INDUSTRIEL (§4, 0 en dur) : lu des 14 stages de franchise_trials_enemy_config
      // (FRANCHISE_TRIALS_ENEMY_CONFIG_STATS.stageToEnemyConfigs) → level/rarity/stars par nœud (scope nodeNumber=stage) ;
      // lineup AUTO filtré par la franchise du sous-trial (scope subtrialNumber=i). Le jeu tire les vrais héros de la franchise.
      Field ecf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("FRANCHISE_TRIALS_ENEMY_CONFIG_STATS");
      ecf.setAccessible(true);
      Object ecStats = ecf.get(null);
      java.util.Map stages = (java.util.Map) ecStats.getClass().getSuperclass().getField("stageToEnemyConfigs").get(ecStats);
      java.util.List enemyLevel = new java.util.ArrayList(), enemyRarity = new java.util.ArrayList(), enemyStars = new java.util.ArrayList();
      java.util.List rewardTypes = new java.util.ArrayList();
      java.util.List combatModifiers = new java.util.ArrayList();
      // Un fragment "modifiers" = N règles ALÉATOIRES tirées du pool event_trial_arena_rules.tab (kind:RANDOM → le jeu pioche
      // dans EventTrialStats.getRandomArenaRules). CONTENU = données du jeu (§4) ; N = param admin (le vrai event le fixe).
      StringBuilder mods = new StringBuilder();
      for (int k = 0; k < modifiersPerNode; k++) { if (k > 0) mods.append(','); mods.append("{\"kind\":\"RANDOM\"}"); }
      for (Object key : new java.util.TreeSet(stages.keySet())) {
        int stage = ((Number) key).intValue();
        Object ec = stages.get(key);
        // levels/rarity/stars = champs String de EventTrialEnemyConfig (ex. "55","7","2") → utilisés tels quels comme expr (§4).
        String lvl = String.valueOf(readField(ec, "levels")), rar = String.valueOf(readField(ec, "rarity")), st = String.valueOf(readField(ec, "stars"));
        String sc = "\"scope\":{\"nodeNumber\":\"" + stage + "\"}";
        enemyLevel.add(mkTrialPiece("TrialEventEnemyLevel",  "{\"expr\":\"" + lvl + "\",\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        enemyRarity.add(mkTrialPiece("TrialEventEnemyRarity", "{\"expr\":\"" + rar + "\",\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        enemyStars.add(mkTrialPiece("TrialEventEnemyStars",  "{\"expr\":\"" + st  + "\",\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        // RÉCOMPENSES du stage — DATA-DRIVEN (§4) depuis les colonnes REWARDS/BONUSES de franchise_trials_enemy_config
        // (ex. "RANDOM_BADGE 7-11 8,RANDOM_BADGE 7-11 8" / "PATCH_ESSENCE_1 36"). On convertit le format .tab en pièces
        // TrialEventReward (schéma du jeu) : le PARSEUR du jeu valide. scope nodeNumber=stage (récompenses par nœud).
        String rewardsStr = String.valueOf(readField(ec, "rewards"));
        String bonusesStr = String.valueOf(readField(ec, "bonuses"));
        String rewardsJson = parseRewardList(rewardsStr);
        String bonusJson   = parseRewardList(bonusesStr);
        rewardTypes.add(mkTrialPiece("TrialEventRewardTypes",
            "{\"rewards\":[" + rewardsJson + "],\"bonusRewards\":[" + bonusJson + "],\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        // COMBAT MODIFIERS (« Rules ») — N règles aléatoires du pool `.tab` par nœud (si demandé par l'admin).
        if (modifiersPerNode > 0)
          combatModifiers.add(mkTrialPiece("TrialEventCombatModifiers",
              "{\"modifiers\":[" + mods + "],\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
      }
      setField(trial, "enemyLevel", enemyLevel);
      setField(trial, "enemyRarity", enemyRarity);
      setField(trial, "enemyStars", enemyStars);
      setField(trial, "rewardTypes", rewardTypes);
      if (!combatModifiers.isEmpty()) setField(trial, "combatModifiers", combatModifiers);
      // chancesPerReset : paramètre ADMIN (non dans les .tab) — voir DEFAULT_TRIAL_CHANCES.
      setField(trial, "chancesPerReset", chances);
      // TITRE principal = param admin (libellé littéral, plus de « NONE.TITLE »).
      if (title != null && !title.isEmpty())
        setField(trial, "trialTitle", com.perblue.common.specialevent.EventString.unlocalized(info, title));
      // questType = celui du trial de saison (MAJOR/MERGE…) → alimente handleFranchiseTrialCompletion (avant : NONE = no-op). §4.
      String qt = seasonTrialQuestType(trialIndex);
      if (qt != null) setField(trial, "questType",
          Enum.valueOf((Class) Class.forName("com.perblue.heroes.network.messages.TrialQuestType"), qt));
      // Lineup = liste `units` de héros ennemis (TrialEventEnemyHero) : ici 5 RANDOM_HERO tirés de la FRANCHISE du sous-trial
      // (schéma du jeu, découvert via son parseur : units / kind RANDOM_HERO / categories:[{FRANCHISE, franchises:[{franchise:X}]}]
      // / realGear:{kind:<RealGearMode>}). scope subtrialNumber 1-based. WILDCARD = joker → pas de filtre franchise (tous héros).
      // ⚠ CORRECTIF §8 : RealGearMode ∈ {FIRST, NONE, RANDOM, SECOND} — PAS "NORMAL" (tryValueOf lenient → null silencieux
      // → NPE à toJson lors du PUSH client). On pose NONE (valeur VALIDE, neutre : aucun real gear FORCÉ, non inventé §4).
      // L'ASSIGNATION effective du real gear (ASSIGN_REAL_GEAR par stage, `assignRealGear` au niveau lineup) est un raffinement
      // à calibrer EN JEU (§8) : granularité par-stage (enemy_config) vs lineup par-sous-trial ; combat client-autoritatif.
      java.util.List lineups = new java.util.ArrayList();
      for (int i = 0; i < frNames.size(); i++) {
        String fr = frNames.get(i);
        // categories OBLIGATOIRE (tableau) : filtre FRANCHISE ; WILDCARD (joker) → tableau vide = aucun filtre (tous héros).
        String cat = "WILDCARD".equals(fr) ? "\"categories\":[]," :
          "\"categories\":[{\"kind\":\"FRANCHISE\",\"franchises\":[{\"franchise\":\"" + fr + "\"}]}],";
        String unit = "{\"kind\":\"RANDOM_HERO\"," + cat + "\"realGear\":{\"kind\":\"NONE\"}}";
        StringBuilder units = new StringBuilder();
        for (int k = 0; k < 5; k++) { if (k > 0) units.append(","); units.append(unit); }
        lineups.add(mkTrialPiece("TrialEventEnemyLineup",
          "{\"kind\":\"MANUAL\",\"units\":[" + units + "],\"random\":{\"kind\":\"NORMAL\"},\"scope\":{\"subtrialNumber\":\"" + (i + 1) + "\"}}"));
      }
      setField(trial, "enemyLineups", lineups);

      // GATING (§4 data-driven) : 1 critère par sous-trial de franchise → n'autorise QUE les héros de la franchise. Le client
      // FILTRE le sélecteur via ce critère ET `TrialEventInfo.franchises` est DÉRIVÉ (au `load`) du `specificFranchise` de ce
      // filtre (offset 1599 : sans gatingCriteria, getFranchises()=null en jeu). heroCount = WAVE... non : = taille d'équipe (5)
      // = « toute l'équipe doit être de la franchise » (règle des franchise trials). WILDCARD (joker) → pas de critère.
      java.util.List gating = new java.util.ArrayList();
      for (int i = 0; i < frNames.size(); i++) {
        String fr = frNames.get(i);
        if ("WILDCARD".equals(fr)) continue;
        String crit = "{\"scope\":{\"subtrialNumber\":\"" + (i + 1) + "\"},\"random\":{\"kind\":\"NORMAL\"},\"criteria\":[{"
            + "\"style\":{\"kind\":\"INCLUSIVE\",\"heroCount\":5},"
            + "\"criterion\":{\"kind\":\"CATEGORIES\",\"categories\":[{\"kind\":\"FRANCHISE\",\"franchises\":[{\"franchise\":\""
            + fr + "\"}]}]}}]}";
        gating.add(mkTrialPiece("TrialEventGatingCriteria", crit));
      }
      setField(trial, "gatingCriteria", gating);

      // Carte UI (`image` de TrialEventInfo) — le client RE-PARSE l'event reçu (`SpecialEventBuilder.buildEvent`) et `load`
      // EXIGE un objet `image` valide. ⚠ ASYMÉTRIE DU JEU : pour un card kind=UNIT, `toJson` écrit la clé `image` mais `load`
      // relit `unitType` → un card UNIT (via cardUnitType, laissé DEFAULT par fillTrialFields) NE ROUND-TRIP PAS ("Named value
      // not found: unitType" → event rejeté côté client). On met donc les deux champs de carte à null : `toJson` émet alors
      // `{kind:MATCH_DISPLAY}` (carte « matchup »), que `load` round-trip proprement, SANS asset. (§8 : découvert EN JEU.)
      setField(trial, "cardUnitType", null);
      setField(trial, "cardImage", null);

      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildFranchiseTrialEvent", e);
    }
  }

  // FRANCHISE_TRIALS — franchises (= sous-trials) de l'event actif, par eventID, mémorisées à la construction (pour le gating).
  private static final java.util.Map<Long, java.util.List<String>> TRIAL_FRANCHISES_BY_EVENT = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * FRANCHISE_TRIALS (EVENT/FRANCHISE) — les TRIALS de la SAISON COURANTE (auto-rotation par date), lus de
   * {@code patched_heroes_franchise_season_mapping.tab} via la logique du jeu (§3, {@code FRANCHISE_SEASON_MAPPING_STATS}). Chaque
   * trial de saison = {@code FranchiseTrialConfig{franchises (=sous-trials), questType, activeDays}}. C'est la SOURCE des sous-trials
   * disponibles (pas {@code base_trial_config.FRANCHISES}, qui est un gabarit statique). Retour ordonné par index de trial (0,1,2…).
   */
  public static java.util.List<Object> seasonTrialConfigs() {
    try {
      Field sf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("FRANCHISE_SEASON_MAPPING_STATS");
      sf.setAccessible(true);
      Object stats = sf.get(null);
      // SÉLECTION DE SAISON = seasonTimeNow() (serverTimeNow + ancre de saison ADMIN), DÉCOUPLÉE des timers joueur
      // (qui gardent serverTimeNow). Ancre 0 par défaut → suit la date réelle (comportement historique). Cf. ServerContext.
      long now = ServerContext.seasonTimeNow();
      java.lang.reflect.Method gc = stats.getClass().getDeclaredMethod("getColumn", long.class); gc.setAccessible(true);
      Object col = gc.invoke(stats, now);
      java.util.Map<?, ?> tc = (java.util.Map<?, ?>) instanceField(col, "trialCollection");
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object k : new java.util.TreeSet<>(tc.keySet())) out.add(tc.get(k));   // ordonné par index de trial
      return out;
    } catch (Exception e) { throw new RuntimeException("seasonTrialConfigs", e); }
  }

  /** Franchises (noms) d'un trial de saison (= ses sous-trials, dans l'ordre). */
  @SuppressWarnings("unchecked")
  public static java.util.List<String> seasonTrialFranchises(int trialIndex) {
    java.util.List<Object> cfgs = seasonTrialConfigs();
    if (trialIndex < 0 || trialIndex >= cfgs.size()) return java.util.Collections.emptyList();
    try {
      java.util.List<?> fr = (java.util.List<?>) cfgs.get(trialIndex).getClass().getField("franchises").get(cfgs.get(trialIndex));
      java.util.List<String> out = new java.util.ArrayList<>();
      for (Object f : fr) out.add(((Enum<?>) f).name());
      return out;
    } catch (Exception e) { throw new RuntimeException("seasonTrialFranchises", e); }
  }

  /** questType (nom) d'un trial de saison (MAJOR/MERGE/…), ou {@code null}. Alimente la complétion (handleFranchiseTrialCompletion). */
  public static String seasonTrialQuestType(int trialIndex) {
    java.util.List<Object> cfgs = seasonTrialConfigs();
    if (trialIndex < 0 || trialIndex >= cfgs.size()) return null;
    try {
      Object qt = cfgs.get(trialIndex).getClass().getField("questType").get(cfgs.get(trialIndex));
      return qt == null ? null : ((Enum<?>) qt).name();
    } catch (Exception e) { return null; }
  }

  public static int seasonTrialCount() { return seasonTrialConfigs().size(); }

  /** Franchise (nom) du sous-trial {@code subtrialNumber} (1-based) de l'event {@code eventID}, ou {@code null}. WILDCARD = pas de restriction. */
  public static String franchiseForSubtrial(long eventID, int subtrialNumber) {
    java.util.List<String> fr = TRIAL_FRANCHISES_BY_EVENT.get(eventID);
    return (fr != null && subtrialNumber >= 1 && subtrialNumber <= fr.size()) ? fr.get(subtrialNumber - 1) : null;
  }

  /**
   * Convertit une liste de récompenses au FORMAT `.tab` (colonnes REWARDS/BONUSES de franchise_trials_enemy_config, ex.
   * {@code "RANDOM_BADGE 7-11 8,RANDOM_BADGE 7-11 8"} ou {@code "PATCH_ESSENCE_1 36"}) en fragments JSON {@code TrialEventReward}
   * (schéma du jeu), séparés par des virgules. DATA-DRIVEN (§4) : on ne fait que RE-EXPRIMER les valeurs du `.tab` dans le schéma
   * du jeu (le parseur du jeu valide). Deux cas : (a) 1ᵉʳ token = {@code RewardSelectionMode} (RANDOM_BADGE…) → {@code kind}
   * + {@code minTier}-{@code maxTier} (si plage) + {@code quantity} ; (b) 1ᵉʳ token = {@code ItemType} (PATCH_ESSENCE_n…) →
   * {@code kind:ITEM} + {@code itemType} + {@code quantity}.
   */
  /** Nom de franchise lisible pour l'UI (data-driven : l'enum du `.tab`, underscores → espaces). Ex. THE_JUNGLE_BOOK → "THE JUNGLE BOOK". */
  private static String prettyName(String enumName) {
    return enumName == null ? "" : enumName.replace('_', ' ');
  }

  private static String parseRewardList(String tabList) {
    if (tabList == null) return "";
    StringBuilder out = new StringBuilder();
    for (String tok : tabList.split(",")) {
      tok = tok.trim(); if (tok.isEmpty()) continue;
      String[] p = tok.split("\\s+");
      String frag;
      boolean isSelMode;
      try { Enum.valueOf((Class) Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventReward$RewardSelectionMode"), p[0]); isSelMode = true; }
      catch (Exception e) { isSelMode = false; }
      if (isSelMode) {
        if (p.length >= 3 && p[1].contains("-")) {           // ex. RANDOM_BADGE 7-11 8 → plage de RARETÉ de badge
          String[] rr = p[1].split("-");                     // minRarity/maxRarity = expressions (bycep), pas minTier/maxTier (=mods)
          frag = "{\"kind\":\"" + p[0] + "\",\"quantity\":\"" + p[2] + "\",\"minRarity\":\"" + rr[0] + "\",\"maxRarity\":\"" + rr[1] + "\"}";
        } else {                                             // ex. RANDOM 8
          frag = "{\"kind\":\"" + p[0] + "\",\"quantity\":\"" + (p.length > 1 ? p[1] : "1") + "\"}";
        }
      } else {                                               // ex. PATCH_ESSENCE_1 36 → item
        frag = "{\"kind\":\"ITEM\",\"itemType\":\"" + p[0] + "\",\"quantity\":\"" + (p.length > 1 ? p[1] : "1") + "\"}";
      }
      if (out.length() > 0) out.append(',');
      out.append(frag);
    }
    return out.toString();
  }

  /** Construit une pièce de config de trial (`game.specialevent.trial.*`) via son ctor {@code (JsonValue, Map)} — le fragment
   *  JSON ne porte que du CONTENU lu des `.tab` (§4) ; le PARSEUR du jeu valide/construit (schéma du jeu, pas deviné). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object mkTrialPiece(String simpleClass, String json) throws Exception {
    return Class.forName("com.perblue.heroes.game.specialevent.trial." + simpleClass)
        .getConstructor(JsonValue.class, java.util.Map.class).newInstance(JSON.parse(json), new java.util.HashMap());
  }

  private static Object readField(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
  }
  private static int readInt(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(o);
  }
  private static boolean readBool(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.getBoolean(o);
  }

  /**
   * Remplissage GÉNÉRIQUE PAR TYPE d'un {@code TrialEventInfo} (object-path) : {@code activeDays=[EVERYDAY]} (ouvert),
   * {@code trialType}=paramètre, {@code preset}="none", listes→vides (contenu ennemis tiré des .tab plus tard §4),
   * Set/Map→vides, int→défauts (chances=2), boolean→false, enum→1ʳᵉ constante, {@code EventString}→vide, String→"".
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void fillTrialFields(IEventComponent trial, com.perblue.heroes.network.messages.GenericTrialType trialType) throws Exception {
    JsonValue emptyObj = JSON.parse("{}");
    for (Field f : trial.getClass().getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
      f.setAccessible(true);
      Class<?> t = f.getType();
      String name = f.getName();
      try {
        if ("activeDays".equals(name)) {
          java.util.List days = new java.util.ArrayList();
          days.add(Enum.valueOf((Class) Class.forName("com.perblue.heroes.game.objects.trials.GenericTrialActiveDays"), "EVERYDAY"));
          f.set(trial, days);
        } else if ("trialType".equals(name)) {
          f.set(trial, trialType);
        } else if ("preset".equals(name)) {
          f.set(trial, "none");
        } else if (t == java.util.List.class) {
          f.set(trial, new java.util.ArrayList());
        } else if (t == java.util.Set.class) {
          f.set(trial, new java.util.HashSet());
        } else if (t == java.util.Map.class) {
          f.set(trial, new java.util.HashMap());
        } else if (t == int.class) {
          f.setInt(trial, 0);   // pas de valeur inventée ici ; chancesPerReset est posé explicitement (param admin) par l'appelant
        } else if (t == boolean.class) {
          f.setBoolean(trial, false);
        } else if (t == String.class) {
          f.set(trial, "");
        } else if (t == com.perblue.common.specialevent.EventString.class) {
          f.set(trial, com.perblue.common.specialevent.EventString.load(null, name, emptyObj));
        } else if (t.isEnum()) {
          Object[] consts = t.getEnumConstants();
          Object def = null;
          for (Object c : consts) if ("DEFAULT".equals(((Enum<?>) c).name())) { def = c; break; }
          f.set(trial, def != null ? def : consts[0]);
        }
        // autres types (objets structurés non contraints) : laissés null — à peupler depuis les .tab aux incréments suivants.
      } catch (Throwable ignore) { /* champ non contraint : laissé tel quel */ }
    }
  }

  /**
   * Construit une carte d'affichage {@code eventCardDisplay} MINIMALE (cachée) — via la FABRIQUE du jeu
   * ({@code SpecialEventBuilder.createComponent}) + un remplissage GÉNÉRIQUE PAR TYPE (pas champ-par-champ) : String→""
   * (sauf {@code preset}="none", le preset wildcard vide {@code *.eventCard.none}), {@code EventString}→vide (via son
   * {@code load} sur un nœud vide), {@code UnitTypeLookup}→{@code FixedUnitTypeLookup(DEFAULT)}, enum→DEFAULT, Class→UnitType.
   * La carte n'a AUCUN rôle serveur ; elle rend juste l'événement RE-PARSABLE par le client ({@code checkUnitType}).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static IEventComponent buildMinimalCard(SpecialEventInfo info) throws Exception {
    IEventComponent card = SpecialEventBuilder.createComponent("eventCardDisplay");
    JsonValue emptyObj = JSON.parse("{}");
    for (Field f : card.getClass().getDeclaredFields()) {
      f.setAccessible(true);
      Class<?> t = f.getType();
      try {
        if (t == String.class) f.set(card, "preset".equals(f.getName()) ? "none" : "");
        else if (t == int.class) f.setInt(card, 0);
        else if (t == long.class) f.setLong(card, 0L);
        else if (t == boolean.class) f.setBoolean(card, "hidden".equals(f.getName()));
        else if (t == com.perblue.common.specialevent.EventString.class)
          f.set(card, com.perblue.common.specialevent.EventString.load(info, f.getName(), emptyObj));
        else if (t == Class.class) f.set(card, com.perblue.heroes.network.messages.UnitType.class);
        else if (t == java.lang.Enum.class) f.set(card, com.perblue.heroes.network.messages.UnitType.DEFAULT);
        else if (t.getName().endsWith("UnitTypeLookup"))
          f.set(card, new com.perblue.common.specialevent.components.pieces.FixedUnitTypeLookup(
              com.perblue.heroes.network.messages.UnitType.DEFAULT));
      } catch (Throwable ignore) { /* champ non contraint : laissé tel quel */ }
    }
    return card;
  }

  /**
   * Sérialise des événements en {@code SpecialEventsRaw} (via le sérialiseur DU JEU {@code SpecialEventInfo.toJson()}) —
   * à POUSSER au CLIENT pour qu'il AFFICHE les événements (le client re-parse ce JSON par {@code buildEvent}).
   */
  public static com.perblue.heroes.network.messages.SpecialEventsRaw toRaw(List<SpecialEventInfo> events) {
    com.perblue.heroes.network.messages.SpecialEventsRaw raw = new com.perblue.heroes.network.messages.SpecialEventsRaw();
    raw.events = new ArrayList<>();
    for (SpecialEventInfo info : events) {
      com.perblue.heroes.network.messages.SpecialEventRaw ev = new com.perblue.heroes.network.messages.SpecialEventRaw();
      ev.eventID = info.getID();
      ev.jsonString = String.valueOf(info.toJson());
      raw.events.add(ev);
    }
    return raw;
  }

  /**
   * Événements opérateur COURANTS (overrides live-ops). <b>Défaut = VIDE</b> → le jeu applique sa <b>ROTATION par défaut</b>
   * ({@code getOpenDays} : DOCKS [6,4,2,1] / WAREHOUSE [7,5,3,1]) ; un opérateur AJOUTE des overrides (MODES_OPEN/DropBonus)
   * via l'outil {@code AdminEvents}, persistés dans {@code shard_state} (chargés au boot par {@code LoginServer}). Fait §8
   * (g130) : forcer les modes ouverts en permanence écraserait la rotation fidèle → on NE force RIEN par défaut.
   */
  private static volatile List<SpecialEventInfo> OPERATOR_EVENTS = new ArrayList<>();

  /** Remplace l'ensemble des événements opérateur (appelé au boot par {@code LoginServer} depuis le store shard). */
  public static void setOperatorEvents(List<SpecialEventInfo> events) {
    OPERATOR_EVENTS = (events == null) ? new ArrayList<>() : new ArrayList<>(events);
  }
  /** Copie des événements opérateur courants (pour push client via {@link #toRaw} / installation). */
  public static List<SpecialEventInfo> operatorEvents() { return new ArrayList<>(OPERATOR_EVENTS); }

  /** Rétro-compat (anciennement défauts en dur DOCKS+WAREHOUSE) : désormais = événements opérateur (défaut VIDE). */
  public static List<SpecialEventInfo> bootDefaultEvents() { return operatorEvents(); }

  // --- PERSISTANCE : config OPÉRATEUR = descripteurs (specs) reconstruits par NOS builders ------------------------------
  // On persiste la CONFIG opérateur (liste de specs {kind, modes, bonus, start, end}), PAS les événements sérialisés :
  // on reconstruit via buildModesOpenEvent/buildDropBonusEvent (qui s'installent proprement), au lieu du re-parse
  // buildEvent du jeu qui emprunte un chemin de refresh fragile (GuildStats). Format = JSON du jeu (JsonValue).

  /** Reconstruit les événements opérateur depuis la CONFIG persistée (JSON de specs) via nos builders. */
  public static List<SpecialEventInfo> eventsFromConfig(byte[] configBlob) {
    List<SpecialEventInfo> events = new ArrayList<>();
    for (JsonValue spec : configSpecs(configBlob)) {
      try { events.add(eventFromSpec(spec)); }
      catch (Throwable t) { System.out.println("[events] spec ignorée (" + spec + "): " + t); }
    }
    return events;
  }

  /** Construit UN événement depuis une spec {kind, modes[], bonus, start, end}. */
  private static SpecialEventInfo eventFromSpec(JsonValue spec) {
    String kind = spec.getString("kind", "MODES_OPEN");
    long id = spec.getLong("id", System.nanoTime() & 0xFFFFFFL);
    long start = spec.getLong("start", defaultStart());
    long end = spec.getLong("end", defaultEnd());
    List<GameMode> modes = new ArrayList<>();
    JsonValue ms = spec.get("modes");
    if (ms != null) for (JsonValue m = ms.child; m != null; m = m.next) modes.add(GameMode.valueOf(m.asString()));
    if ("DROP_BONUS".equals(kind)) return buildDropBonusEvent(id, modes, spec.getInt("bonus", 1), start, end);
    // FRANCHISE_TRIALS incr. 7 : un event TRIAL FRANCHISE (SpecialEventInfo TRIAL) — reconstruit data-driven depuis les `.tab`
    // (buildFranchiseTrialEvent). L'`id` de la spec = l'eventID que le client renverra (GetTrialEventData/TrialEventAttack).
    if ("TRIAL_FRANCHISE".equals(kind)) return buildFranchiseTrialEvent(id, start, end,
        spec.getInt("chances", DEFAULT_TRIAL_CHANCES), spec.getString("title", DEFAULT_TRIAL_TITLE),
        spec.getInt("trial", 0), spec.getInt("modifiers", 0));
    if ("CHEST_DISCOUNT".equals(kind)) {
      List<com.perblue.heroes.network.messages.ChestType> chests = new ArrayList<>();
      JsonValue cs = spec.get("chests");
      if (cs != null) for (JsonValue c = cs.child; c != null; c = c.next)
        chests.add(com.perblue.heroes.network.messages.ChestType.valueOf(c.asString()));
      return buildChestDiscountEvent(id, chests, spec.getInt("percentOff", 50), start, end);
    }
    if ("INCREASED_CHANCES".equals(kind)) {
      java.util.Map<String, Integer> ch = new java.util.LinkedHashMap<>();
      JsonValue cn = spec.get("chances");
      if (cn != null) for (JsonValue c = cn.child; c != null; c = c.next) ch.put(c.name(), c.asInt());
      return buildIncreasedChancesEvent(id, ch, start, end);
    }
    return buildModesOpenEvent(id, modes, start, end);
  }

  /**
   * Retourne l'event TRIAL FRANCHISE actif (installé dans {@code OPERATOR_EVENTS}) d'`eventID` donné, ou {@code null}. Sert à
   * ce que le SERVEUR rejoue le combat sur EXACTEMENT le même event que le client (mêmes chances/rewards admin) — cohérence
   * serveur-autoritative (évite un reconstruction avec des params par défaut divergents).
   */
  public static SpecialEventInfo activeTrialEvent(long eventID) {
    for (SpecialEventInfo e : OPERATOR_EVENTS) {
      try {
        if (e.getID() == eventID
            && e.getComponent((Class) Class.forName("com.perblue.heroes.game.specialevent.TrialEventInfo")) != null) return e;
      } catch (Exception ignore) {}
    }
    return null;
  }

  /** Liste des specs (JsonValue) d'une config persistée (vide si null). */
  public static List<JsonValue> configSpecs(byte[] configBlob) {
    List<JsonValue> out = new ArrayList<>();
    if (configBlob == null || configBlob.length == 0) return out;
    JsonValue root = JSON.parse(new String(configBlob, java.nio.charset.StandardCharsets.UTF_8));
    JsonValue specs = root.get("specs");
    if (specs != null) for (JsonValue s = specs.child; s != null; s = s.next) out.add(s);
    return out;
  }

  /** Sérialise une liste de specs (chaînes JSON d'objet) en octets de config persistable. */
  public static byte[] writeConfig(List<String> specJsons) {
    StringBuilder sb = new StringBuilder("{\"specs\":[");
    for (int i = 0; i < specJsons.size(); i++) { if (i > 0) sb.append(','); sb.append(specJsons.get(i)); }
    sb.append("]}");
    return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Construit la chaîne JSON d'UNE spec d'event TRIAL FRANCHISE (id=eventID ; chances/title/trial = params admin ; trial = index du trial de saison). */
  public static String specJsonTrialFranchise(long id, long start, long end, int chances, String title, int trialIndex, int modifiersPerNode) {
    String t = (title == null ? DEFAULT_TRIAL_TITLE : title).replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\"kind\":\"TRIAL_FRANCHISE\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"chances\":" + chances + ",\"title\":\"" + t + "\",\"trial\":" + trialIndex
        + ",\"modifiers\":" + modifiersPerNode + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec CHEST_DISCOUNT (coffres visés + pourcentage de remise = params ADMIN). */
  public static String specJsonChestDiscount(long id, Collection<com.perblue.heroes.network.messages.ChestType> chests,
      int percentOff, long start, long end) {
    StringBuilder c = new StringBuilder();
    for (com.perblue.heroes.network.messages.ChestType ct : chests) { if (c.length() > 0) c.append(','); c.append('"').append(ct.name()).append('"'); }
    return "{\"kind\":\"CHEST_DISCOUNT\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"chests\":[" + c + "],\"percentOff\":" + percentOff
        + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec INCREASED_CHANCES (chanceType → nombre de chances en plus = params ADMIN). */
  public static String specJsonIncreasedChances(long id, java.util.Map<String, Integer> chances, long start, long end) {
    StringBuilder c = new StringBuilder();
    for (java.util.Map.Entry<String, Integer> e : chances.entrySet()) {
      if (c.length() > 0) c.append(',');
      c.append('"').append(e.getKey()).append("\":").append(e.getValue());
    }
    return "{\"kind\":\"INCREASED_CHANCES\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"chances\":{" + c + "},\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec d'override opérateur. */
  public static String specJson(String kind, Collection<GameMode> modes, int bonus, long start, long end) {
    StringBuilder m = new StringBuilder();
    for (GameMode g : modes) { if (m.length() > 0) m.append(','); m.append('"').append(g.name()).append('"'); }
    return "{\"kind\":\"" + kind + "\",\"modes\":[" + m + "],\"bonus\":" + bonus
        + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /**
   * Installe une liste d'événements opérateur dans la machinerie du jeu ({@code SpecialEventsHelper}) : le serveur
   * autoritatif voit alors leurs effets ({@code isModeOpen}, snapshots…). On remplace la référence globale
   * {@code SPECIAL_EVENTS} et on invalide le cache de snapshot — le {@code snapshotWithoutRefresh()} suivant les prend.
   */
  public static void install(List<SpecialEventInfo> events) {
    try {
      Object helper = staticField(com.perblue.heroes.game.logic.SpecialEventsHelper.class, "helper");
      if (helper == null) return;   // couche événements non initialisée (SpecialEventsHelper.init non appelé)
      SpecialEvents se = new SpecialEvents();
      se.setEvents(new ArrayList<>(events));
      ((AtomicReference<Object>) instanceField(helper, "SPECIAL_EVENTS")).set(se);
      // Force la RECONSTRUCTION du snapshot depuis ces événements : (a) invalide le cache, (b) remet lastSnapshotTime=0
      // (le snapshot est mémoïsé par temps), (c) refresh(true). Sinon le snapshot périmé ne refléterait pas l'installation.
      AtomicReference<Object> snapCache = (AtomicReference<Object>) instanceField(helper, "SNAPSHOT_CACHE");
      if (snapCache != null) snapCache.set(null);
      try { Field lst = findField(helper.getClass(), "lastSnapshotTime"); lst.setAccessible(true); lst.setLong(helper, 0L); } catch (Throwable ignore) {}
      Method refresh = findMethod(helper.getClass(), "refresh", boolean.class);
      refresh.setAccessible(true); refresh.invoke(helper, true);
    } catch (Exception e) {
      System.out.println("[events] install échec : " + e);
    }
  }

  /**
   * Installe les événements OPÉRATEUR courants (appelé après {@code setSpecialEvents} dans {@code ServerContext.bind}).
   * Défaut = VIDE → aucune ouverture forcée → le jeu applique sa <b>rotation par défaut</b> ({@code getOpenDays}). Un
   * opérateur peut ajouter des overrides (via {@code AdminEvents}, chargés au boot par {@code LoginServer}).
   */
  public static void installBootDefaults() {
    install(operatorEvents());
  }

  /** Snapshot courant de la couche événements (sans refresh UI, sûr headless) — à passer aux checks serveur. */
  public static com.perblue.heroes.game.specialevent.SpecialEventSnapshot snapshot() {
    return com.perblue.heroes.game.logic.SpecialEventsHelper.snapshotWithoutRefresh();
  }

  /**
   * Snapshot de la couche événements ANCRÉ à une heure choisie (mêmes events installés, {@code snapshotTime}=time). Utilise
   * l'état brut courant ({@code snapshotRaw}) + le constructeur DU JEU {@code SpecialEventSnapshot(state, time)} (§3). Sert aux
   * tests DÉTERMINISTES de la rotation par jour : {@code DifficultyModeHelper.isOpen} calcule le jour depuis
   * {@code snapshot.snapshotTime} (fait §8) — figer le temps rend le jour indépendant du calendrier réel.
   */
  public static com.perblue.heroes.game.specialevent.SpecialEventSnapshot snapshotAt(long time) {
    return new com.perblue.heroes.game.specialevent.SpecialEventSnapshot(
        com.perblue.heroes.game.logic.SpecialEventsHelper.snapshotRaw(), time);
  }

  // --- réflexion (couche plateforme §3, comme l'alloc sans ctor de GameMain) ---
  private static void setField(Object o, String name, Object v) throws Exception {
    Field f = findField(o.getClass(), name); f.setAccessible(true); f.set(o, v);
  }
  private static void addComponent(SpecialEventInfo info, IEventComponent c) throws Exception {
    Method m = SpecialEventInfo.class.getDeclaredMethod("addComponent", IEventComponent.class);
    m.setAccessible(true); m.invoke(info, c);
  }
  private static Object staticField(Class<?> c, String name) throws Exception {
    Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(null);
  }
  private static Object instanceField(Object o, String name) throws Exception {
    Field f = findField(o.getClass(), name); f.setAccessible(true); return f.get(o);
  }
  private static Field findField(Class<?> c, String name) {
    for (; c != null; c = c.getSuperclass()) {
      try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) {}
    }
    throw new RuntimeException("champ introuvable : " + name);
  }
  private static Method findMethod(Class<?> c, String name, Class<?>... args) {
    for (; c != null; c = c.getSuperclass()) {
      try { return c.getDeclaredMethod(name, args); } catch (NoSuchMethodException ignore) {}
    }
    throw new RuntimeException("méthode introuvable : " + name);
  }
}
