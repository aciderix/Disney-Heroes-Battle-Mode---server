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
