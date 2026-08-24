package dhserver;

import com.perblue.heroes.network.messages.AllContestData;
import com.perblue.heroes.network.messages.ContestData;
import com.perblue.heroes.network.messages.ContestExtraData;
import com.perblue.heroes.game.objects.ClientContestData;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * CONTEST — état per-user SERVEUR-AUTORITATIF de progression des contests ({@link AllContestData} = Map&lt;contestID,
 * {@link ContestData}&gt;).
 *
 * <p>Le client envoie {@code GetAllContestData} et attend un {@code AllContestData} (points de progression/rang + compteurs
 * de tâches par contest). Ce message N'A PAS de builder côté client (état backend PerBlue) — comme {@code ArenaInfo}/
 * {@code TrialEventData}, on le construit ICI, serveur-autoritativement, et on le PERSISTE (colonne BLOB {@code contestData}).
 * L'objet d'exécution manipulé par la logique du jeu ({@code ContestHelper.recordTasks}/{@code on*}) est un
 * {@link ClientContestData} qui ENVELOPPE le {@code ContestData} wire (ses setters écrivent dans le wire → persistance directe).
 *
 * <p>Incr. 2 = servir l'état (frais/persisté) + persistance. L'AVANCE de l'état (points via les {@code ContestHelper.on*}) =
 * incr. 3 ; le classement + la réclamation = incr. 4. Les valeurs de RÈGLES (points/paliers) viennent du {@code Contest}
 * (données admin/jeu), jamais inventées (§4).
 */
public final class ServerContestData {
  private ServerContestData() {}

  /** {@code ContestData} FRAIS (rien joué) : 0 point, compteurs de tâches vides mais INITIALISÉS (sinon NPE au 1ᵉʳ crédit). */
  public static ContestData freshContestData(int shardID) {
    ContestData d = new ContestData();
    d.progressPoints = 0L;
    d.rank = 0;
    d.rankPoints = 0L;
    d.totalParticipants = 0;
    ContestExtraData ex = new ContestExtraData();
    ex.taskCompletionCount = new HashMap<>();
    ex.taskDailyCompletionCount = new HashMap<>();
    ex.taskPartialCount = new HashMap<>();
    ex.oldTaskCompletionCount = new HashMap<>();
    ex.oldTaskDailyCompletionCount = new HashMap<>();
    ex.oldTaskPartialCount = new HashMap<>();
    ex.contribution = new HashMap<>();
    ex.earnedProgressRewards = new ArrayList<>();
    ex.lastDailyResetTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    ex.originalShardID = shardID;
    d.extraData = ex;
    return d;
  }

  /** Le blob {@code AllContestData} de {@code su} (map par contestID), FRAIS si absent, posé sur {@code su}. */
  @SuppressWarnings("unchecked")
  public static synchronized AllContestData getAllData(ServerUser su) {
    AllContestData all = su.contestDataOrNull();
    if (all == null) {
      all = new AllContestData();
      all.contests = new HashMap<>();
      su.setContestData(all);
    } else if (all.contests == null) {
      all.contests = new HashMap<>();
    }
    return all;
  }

  /** Le {@code ContestData} per-user du contest {@code contestID} (persisté s'il existe, sinon frais + stocké dans le blob). */
  @SuppressWarnings("unchecked")
  public static synchronized ContestData getContestData(ServerUser su, long contestID) {
    AllContestData all = getAllData(su);
    ContestData cd = (ContestData) all.contests.get(contestID);
    if (cd == null) {
      cd = freshContestData(su.shardID);
      all.contests.put(contestID, cd);
      su.setContestData(all);
    }
    return cd;
  }

  /** Objet d'EXÉCUTION ({@code IContestData}) pour la logique du jeu, enveloppant le {@code ContestData} wire persisté. */
  public static ClientContestData clientData(long contestID, ContestData cd) {
    return new ClientContestData(contestID, cd);
  }

  /**
   * Réponse {@code AllContestData} pour {@code su} : le {@code ContestData} per-user (persisté ou frais) de CHAQUE contest
   * ACTIF (composant {@code Contest} du snapshot opérateur). L'appelant persiste ({@code store.save}) et répond au client.
   */
  @SuppressWarnings("unchecked")
  public static synchronized AllContestData response(ServerUser su) {
    // Bind pour que le snapshot filtre par éligibilité (shard) — su.gameUser() est déjà bindé dans le flux login, mais
    // on le refait pour être robuste (chemins de test).
    try { ServerContext.bind(su.gameUser(), su.gameUser().getIndividual()); } catch (Throwable ignore) {}
    AllContestData resp = new AllContestData();
    resp.contests = new HashMap<>();
    try {
      com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
      for (Object o : snap.getActiveEvents()) {
        com.perblue.common.specialevent.SpecialEventInfo e = (com.perblue.common.specialevent.SpecialEventInfo) o;
        if (e.getComponent(com.perblue.common.specialevent.components.Contest.class) != null)
          resp.contests.put(e.getID(), getContestData(su, e.getID()));
      }
    } catch (Throwable t) { System.out.println("[contest] response: " + t); }
    return resp;
  }

  /** IDs des contests ACTIFS (composant {@code Contest} du snapshot opérateur) pour {@code su} (user déjà bindé). */
  @SuppressWarnings("unchecked")
  public static java.util.List<Long> activeContestIDs(ServerUser su) {
    java.util.List<Long> ids = new java.util.ArrayList<>();
    try {
      com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
      for (Object o : snap.getActiveEvents()) {
        com.perblue.common.specialevent.SpecialEventInfo e = (com.perblue.common.specialevent.SpecialEventInfo) o;
        if (e.getComponent(com.perblue.common.specialevent.components.Contest.class) != null) ids.add(e.getID());
      }
    } catch (Throwable t) { System.out.println("[contest] activeContestIDs: " + t); }
    return ids;
  }

  /** Un hook qui exécute la logique DU JEU de crédit ({@code ContestHelper.on*}) sur le {@code User} préparé. */
  public interface ContestHook { void run(com.perblue.heroes.game.objects.User u); }

  /**
   * Exécute un crédit de tâche de contest (§3) : (1) PRÉ-PEUPLE la map {@code user.getContestData()} avec, pour chaque
   * contest ACTIF, un {@code ClientContestData} qui enveloppe le {@code ContestData} PERSISTÉ du blob de {@code su} ;
   * (2) lance le hook du jeu ({@code ContestHelper.on*}) qui mute cet objet via {@code user.getContestData(id)} — mutation
   * EN PLACE du blob (aucune extraction nécessaire) ; (3) l'appelant PERSISTE ({@code store.save}). Sans la pré-population,
   * le jeu créerait un {@code ClientContestData} frais hors de notre blob → progression perdue.
   */
  /**
   * PRÉ-PEUPLE la map {@code user.getContestData()} avec, pour chaque contest ACTIF, un {@code ClientContestData}
   * enveloppant le {@code ContestData} PERSISTÉ du blob de {@code su}. À appeler AVANT toute logique du jeu qui crédite
   * un contest ({@code ContestHelper.on*}, y compris les crédits INTERNES comme {@code onItemEarn} de {@code giveChestRewards})
   * → ces crédits mutent EN PLACE notre blob. L'appelant PERSISTE ensuite ({@code store.save}).
   */
  @SuppressWarnings("unchecked")
  public static synchronized void prepare(ServerUser su, com.perblue.heroes.game.objects.User user) {
    try {
      java.util.Map<Object, Object> map = user.getContestData();
      if (map == null) return;
      for (Long id : activeContestIDs(su))
        map.put(id, clientData(id, getContestData(su, id)));   // blob-backed → mutation en place
    } catch (Throwable t) { System.out.println("[contest] prepare: " + t); }
  }

  /** {@link #prepare} + exécute le hook du jeu ({@code ContestHelper.on*}). L'appelant persiste. */
  public static synchronized void record(ServerUser su, com.perblue.heroes.game.objects.User user, ContestHook hook) {
    prepare(su, user);
    try { hook.run(user); } catch (Throwable t) { System.out.println("[contest] record: " + t); }
  }
}
