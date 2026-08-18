package dhserver;

import com.perblue.heroes.network.messages.TrialEventData;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE trials) — état per-user SERVEUR-AUTORITATIF ({@link TrialEventData}).
 *
 * <p>Le client envoie {@code GetTrialEventData{eventID}} et attend un {@code TrialEventData} pour rendre l'écran du trial
 * (chances restantes, sous-trials, nœuds gagnés). Ce message N'A PAS de builder côté client (état backend PerBlue) — comme
 * {@code ArenaInfo}/{@code GetExpeditionResponse}, on le construit ICI, serveur-autoritativement, et on le PERSISTE (colonne
 * BLOB {@code trialEventData}, patron {@code expeditionRun}). Un seul event trial actif à la fois (keyé par {@code eventID} :
 * si l'état persisté concerne un AUTRE event — nouvelle saison — on repart d'un état frais).
 *
 * <p>Incr. 2 = servir l'état (frais/persisté) + persistance. L'AVANCE de l'état (chances consommées, nœuds gagnés) = incr. 3
 * ({@code TrialEventAttack} → {@code recordOutcome}). Les valeurs de RÈGLES (chances/reset max) viennent du {@code TrialEventInfo}
 * (données du jeu), jamais inventées (§4).
 */
public final class ServerTrials {
  private ServerTrials() {}

  /** État FRAIS d'un event trial (rien joué) : 0 chance utilisée, 0 reset, aucun sous-trial progressé. */
  public static TrialEventData freshData(long eventID) {
    TrialEventData d = new TrialEventData();
    d.eventID = eventID;
    d.chancesUsed = 0;
    d.dailyResetsUsed = 0;
    d.lastChancesResetTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    d.paidChancesRemaining = 0;
    d.paidResetsUsed = 0;
    d.subtrials = new java.util.HashMap<>();   // Map<Integer, TrialEventSubtrialData> — peuplée à mesure des victoires (incr. 3)
    return d;
  }

  /**
   * État per-user de l'event {@code eventID} pour {@code su} : l'état PERSISTÉ s'il concerne CET event, sinon un état FRAIS
   * (nouvel event / nouvelle saison) qu'on pose sur {@code su}. L'appelant persiste via {@code store.save} et répond au client.
   */
  public static synchronized TrialEventData getData(ServerUser su, long eventID) {
    TrialEventData cur = su.trialEventDataOrNull();
    if (cur == null || cur.eventID != eventID) {
      cur = freshData(eventID);
      su.setTrialEventData(cur);
    }
    return cur;
  }
}
