package dhserver;

import com.perblue.heroes.network.messages.GameMode;
import com.perblue.heroes.game.specialevent.SpecialEventType;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.common.specialevent.SpecialEvents;
import com.perblue.common.specialevent.SpecialEventBuilder;
import com.perblue.common.specialevent.components.EventVisibility;
import com.perblue.common.specialevent.components.ModesOpen;
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
  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs) {
    try {
      SpecialEventInfo info = buildTrialEvent(id, com.perblue.heroes.network.messages.GenericTrialType.CAMPAIGN, startMs, endMs);
      Object trial = info.getComponent((Class) Class.forName("com.perblue.heroes.game.specialevent.TrialEventInfo"));

      // Lire base_trial_config VIA les stats du jeu (§3/§4) — pas de valeur en dur.
      Field bf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("BASE_TRIAL_CONFIG_STATS");
      bf.setAccessible(true);
      Object dhcs = bf.get(null);
      Object cst = dhcs.getClass().getMethod("getStats").invoke(dhcs);   // BaseTrialConfigConstants
      int nodeCount        = readInt(cst, "NODE_COUNT");
      int maxDailyResets   = readInt(cst, "MAX_DAILY_RESETS");
      boolean allowRaiding = readBool(cst, "ENABLE_RAIDING");
      boolean statSlots    = readBool(cst, "ENABLE_STAT_SLOTS");
      int primeBadge       = readInt(cst, "PRIME_BADGE_LEVEL_REQ");
      int enhancedPrime    = readInt(cst, "ENHANCED_PRIME_BADGE_LEVEL_REQ");
      int patchLevelReq    = readInt(cst, "PATCH_LEVEL_REQ");
      String franchisesStr = (String) readField(cst, "FRANCHISES");

      Class franchiseCls = (Class) Class.forName("com.perblue.heroes.network.messages.Franchise");
      java.util.Set franchises = new java.util.LinkedHashSet();
      java.util.List subtrials = new java.util.ArrayList();
      for (String fr : franchisesStr.split(",")) {
        fr = fr.trim(); if (fr.isEmpty()) continue;
        Object franchise = Enum.valueOf(franchiseCls, fr);
        franchises.add(franchise);
        // 1 sous-trial par franchise (title/preset = simple ossature de schéma ; le contenu vient des .tab).
        Object sub = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventSubtrialInfo")
            .getConstructor(SpecialEventInfo.class, JsonValue.class)
            .newInstance(info, JSON.parse("{\"title\":{},\"preset\":\"none\"}"));
        subtrials.add(sub);
      }
      // nodeCount : NODE_COUNT nœuds appliqués à TOUS les sous-trials (scope ALL). Clés EXACTES nodeCount/scope (schéma du jeu).
      Object tnc = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventNodeCount")
          .getConstructor(JsonValue.class, java.util.Map.class)
          .newInstance(JSON.parse("{\"nodeCount\":" + nodeCount + ",\"scope\":{}}"), new java.util.HashMap());

      setField(trial, "subtrials", subtrials);
      setField(trial, "nodeCount", new java.util.ArrayList(java.util.List.of(tnc)));
      setField(trial, "franchises", franchises);
      setField(trial, "maxDailyResets", maxDailyResets);
      setField(trial, "allowRaiding", allowRaiding);
      setField(trial, "enableStatSlots", statSlots);
      setField(trial, "primeBadgeLevelReq", primeBadge);
      setField(trial, "enhancedPrimeBadgeLevelReq", enhancedPrime);
      setField(trial, "patchLevelReq", patchLevelReq);
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildFranchiseTrialEvent", e);
    }
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
          f.setInt(trial, "chancesPerReset".equals(name) ? 2 : 0);
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
    return buildModesOpenEvent(id, modes, start, end);
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
