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

      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildModesOpenEvent", e);
    }
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
   * Pose les événements opérateur au boot (appelé après {@code setSpecialEvents} dans {@code ServerContext.bind}).
   * Incr. 1 : ouvre les DEUX modes PORT (DOCKS + WAREHOUSE) — l'autorité serveur les accepte alors tous deux.
   * (La rotation fidèle par jour d'ouverture pourra être ajoutée en s'appuyant sur {@code getOpenDays}.)
   */
  public static void installBootDefaults() {
    List<SpecialEventInfo> events = new ArrayList<>();
    events.add(buildModesOpenEvent(900_001L,
        Arrays.asList(GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE), defaultStart(), defaultEnd()));
    install(events);
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
