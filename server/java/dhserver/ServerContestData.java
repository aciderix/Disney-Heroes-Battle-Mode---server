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
  public static synchronized AllContestData response(ServerUser su, UserStore store) {
    // Bind pour que le snapshot filtre par éligibilité (shard) — su.gameUser() est déjà bindé dans le flux login, mais
    // on le refait pour être robuste (chemins de test).
    try { ServerContext.bind(su.gameUser(), su.gameUser().getIndividual()); } catch (Throwable ignore) {}
    AllContestData resp = new AllContestData();
    resp.contests = new HashMap<>();
    try {
      com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
      for (Object o : snap.getActiveEvents()) {
        com.perblue.common.specialevent.SpecialEventInfo e = (com.perblue.common.specialevent.SpecialEventInfo) o;
        if (e.getComponent(com.perblue.common.specialevent.components.Contest.class) != null) {
          long id = e.getID();
          ContestData cd = getContestData(su, id);
          recomputeRank(store, su, id, cd);   // classement serveur-autoritatif (ladder per-shard)
          resp.contests.put(id, cd);
        }
      }
    } catch (Throwable t) { System.out.println("[contest] response: " + t); }
    return resp;
  }

  // --- CLASSEMENT (leaderboard) per-(shard, contestID) : Map<userID, {points, reachedAt}> dans shard_state ----------------
  // Chaque entrée porte les points ET l'HORODATAGE où le joueur a atteint ce total (reachedAt) → DÉPARTAGE déterministe des
  // ex æquo : à points égaux, le PREMIER arrivé au total (reachedAt le plus ancien) est mieux classé (fidèle « first to reach »).
  private static String ladderKey(long contestID) { return "contest_ladder:" + contestID; }

  /**
   * Désérialise le ladder. Deux formats (auto-détectés par le 1ᵉʳ int) : ANCIEN ({@code n≥0} puis n×{@code userID,points})
   * → {@code reachedAt=0} (plus ancien) ; NOUVEAU (marqueur {@code -2}, puis {@code n}, puis n×{@code userID,points,reachedAt}).
   * Valeur = {@code long[]{points, reachedAt}}.
   */
  static java.util.Map<Long, long[]> loadLadder(UserStore store, int shardID, long contestID) {
    java.util.Map<Long, long[]> m = new java.util.LinkedHashMap<>();
    try {
      byte[] b = store.loadShardState(shardID, ladderKey(contestID));
      if (b == null || b.length == 0) return m;
      java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));
      int first = in.readInt();
      if (first >= 0) {                                   // ANCIEN format (n×{userID,points})
        for (int i = 0; i < first; i++) { long u = in.readLong(); long p = in.readLong(); m.put(u, new long[]{p, 0L}); }
      } else {                                            // NOUVEAU format (marqueur négatif = version)
        int n = in.readInt();
        for (int i = 0; i < n; i++) { long u = in.readLong(); long p = in.readLong(); long r = in.readLong(); m.put(u, new long[]{p, r}); }
      }
    } catch (Throwable t) { System.out.println("[contest] loadLadder: " + t); }
    return m;
  }

  static void saveLadder(UserStore store, int shardID, long contestID, java.util.Map<Long, long[]> m) {
    try {
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      java.io.DataOutputStream o = new java.io.DataOutputStream(bos);
      o.writeInt(-2);                                     // marqueur version 2 (négatif ⇒ distinct de l'ancien n≥0)
      o.writeInt(m.size());
      for (java.util.Map.Entry<Long, long[]> e : m.entrySet()) { o.writeLong(e.getKey()); o.writeLong(e.getValue()[0]); o.writeLong(e.getValue()[1]); }
      o.flush();
      store.saveShardState(shardID, ladderKey(contestID), bos.toByteArray());
    } catch (Throwable t) { System.out.println("[contest] saveLadder: " + t); }
  }

  /**
   * Met à jour le ladder per-shard avec les points de {@code su} pour {@code contestID}, puis calcule et pose {@code cd.rank}
   * et {@code cd.totalParticipants}. {@code reachedAt} : conservé si les points sont INCHANGÉS, remis à MAINTENANT si les points
   * ont bougé (le joueur vient d'atteindre un nouveau total). Rang = 1 + nb de joueurs STRICTEMENT devant, où « devant » =
   * plus de points, OU points égaux mais reachedAt plus ANCIEN (départage déterministe). §5 (état partagé, patron arena_ladder).
   */
  public static synchronized void recomputeRank(UserStore store, ServerUser su, long contestID, ContestData cd) {
    if (store == null) return;
    java.util.Map<Long, long[]> ladder = loadLadder(store, su.shardID, contestID);
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long[] prev = ladder.get(su.userID);
    long myPts = cd.rankPoints;
    long myReached = (prev != null && prev[0] == myPts) ? prev[1] : now;   // inchangé → garder ; changé/nouveau → maintenant
    ladder.put(su.userID, new long[]{myPts, myReached});
    saveLadder(store, su.shardID, contestID, ladder);
    int rank = 1;
    for (java.util.Map.Entry<Long, long[]> e : ladder.entrySet()) {
      if (e.getKey() == su.userID) continue;
      long p = e.getValue()[0], r = e.getValue()[1];
      if (p > myPts || (p == myPts && r < myReached)) rank++;
    }
    cd.rank = rank;
    cd.totalParticipants = ladder.size();
  }

  /**
   * La récompense de RANG qui couvre le rang {@code rank} (sur {@code total} participants), ou {@code null}. On prend le
   * PREMIER tier (dans l'ordre admin = du plus exclusif au moins) que le joueur satisfait : {@code isPercent} → percentile
   * {@code 100·rank/total ≤ maxRank} ; sinon rang absolu {@code rank ≤ maxRank}. (Glue de sélection ; tiers+drops = data admin/jeu.)
   */
  public static com.perblue.common.specialevent.components.pieces.ContestRankRewardInfo rankRewardFor(
      com.perblue.common.specialevent.components.Contest c, int rank, int total) {
    if (c == null || total <= 0) return null;
    for (Object o : c.getRankRewards()) {
      com.perblue.common.specialevent.components.pieces.ContestRankRewardInfo ri =
          (com.perblue.common.specialevent.components.pieces.ContestRankRewardInfo) o;
      boolean ok = ri.isPercent() ? (100.0 * rank / total) <= ri.getMaxRank() : rank <= ri.getMaxRank();
      if (ok) return ri;
    }
    return null;
  }

  /**
   * CLÔTURE d'un contest : distribue les {@code rankRewards} par COURRIER selon le RANG FINAL de chaque joueur du ladder
   * (classement par points décroissants). Renvoie le nombre de joueurs récompensés. Idempotence à la charge de l'appelant
   * (admin {@code --contest-end}, une fois). §3/§4 : les drops viennent de {@code ContestRankRewardInfo.getRewards}.
   */
  public static synchronized int distributeRankRewards(UserStore store, int shardID, long contestID,
      com.perblue.common.specialevent.SpecialEventInfo eventInfo) throws Exception {
    com.perblue.common.specialevent.components.Contest c =
        (com.perblue.common.specialevent.components.Contest) eventInfo.getComponent(com.perblue.common.specialevent.components.Contest.class);
    if (c == null) return 0;
    java.util.Map<Long, long[]> ladder = loadLadder(store, shardID, contestID);
    java.util.List<java.util.Map.Entry<Long, long[]>> sorted = new java.util.ArrayList<>(ladder.entrySet());
    // Points DÉCROISSANTS, puis reachedAt CROISSANT (le premier arrivé au total départage les ex æquo).
    sorted.sort((x, y) -> {
      int c1 = Long.compare(y.getValue()[0], x.getValue()[0]);
      return c1 != 0 ? c1 : Long.compare(x.getValue()[1], y.getValue()[1]);
    });
    int total = sorted.size(), delivered = 0, rank = 0;
    for (java.util.Map.Entry<Long, long[]> e : sorted) {
      rank++;
      com.perblue.common.specialevent.components.pieces.ContestRankRewardInfo ri = rankRewardFor(c, rank, total);
      if (ri == null) continue;
      ServerUser su = store.loadIfExists(e.getKey(), shardID);
      if (su == null) continue;
      com.perblue.heroes.game.objects.User u = su.gameUser();
      ServerContext.bind(u, u.getIndividual());
      @SuppressWarnings("unchecked")
      java.util.List<com.perblue.heroes.network.messages.RewardDrop> drops =
          (java.util.List<com.perblue.heroes.network.messages.RewardDrop>) ri.getRewards(u, eventInfo.getFormatVersion());
      if (drops != null && !drops.isEmpty()) {
        su.deliverMail(com.perblue.heroes.network.messages.MailType.SYSTEM_MESSAGE, "Contest",
            "Contest Rank Reward", "You finished #" + rank + " of " + total + "!", drops);
        store.save(su);
        delivered++;
      }
    }
    return delivered;
  }

  /**
   * CLASSEMENT SOLO (leaderboard serveur-autoritatif) d'un contest : construit un {@link com.perblue.heroes.network.messages.ContestRankings}
   * ({@code yourInfo} + {@code topPlayers}) depuis le ladder per-(shard, contestID). D'abord {@link #recomputeRank} pousse les points
   * COURANTS de {@code su} dans le ladder et calcule son rang (au cas où {@code GetAllContestData} n'aurait jamais été envoyé),
   * puis on trie (points DÉCROISSANTS, puis {@code reachedAt} CROISSANT = 1ᵉʳ arrivé départage) et on renseigne chaque ligne
   * ({@code PlayerRow.info} chargé par membre, {@code points}/{@code rank}). {@code yourInfo} = la ligne du joueur.
   */
  public static synchronized com.perblue.heroes.network.messages.ContestRankings soloRankings(
      UserStore store, ServerUser su, long contestID) {
    com.perblue.heroes.network.messages.ContestRankings cr =
        new com.perblue.heroes.network.messages.ContestRankings();
    cr.guildMembers = new ArrayList<>();   // solo → pas de membres de guilde
    cr.topPlayers = new ArrayList<>();
    try {
      ContestData cd = getContestData(su, contestID);
      recomputeRank(store, su, contestID, cd);
      java.util.Map<Long, long[]> ladder = loadLadder(store, su.shardID, contestID);
      java.util.List<java.util.Map.Entry<Long, long[]>> sorted = new ArrayList<>(ladder.entrySet());
      sorted.sort((x, y) -> {
        int c1 = Long.compare(y.getValue()[0], x.getValue()[0]);
        return c1 != 0 ? c1 : Long.compare(x.getValue()[1], y.getValue()[1]);
      });
      int idx = 0;
      for (java.util.Map.Entry<Long, long[]> e : sorted) {
        idx++;
        com.perblue.heroes.network.messages.ContestRankingRow row =
            new com.perblue.heroes.network.messages.ContestRankingRow();
        com.perblue.heroes.network.messages.PlayerRow pr = new com.perblue.heroes.network.messages.PlayerRow();
        try {
          ServerUser mu = (e.getKey() == su.userID) ? su : store.loadIfExists(e.getKey(), su.shardID);
          if (mu != null) pr.info = mu.basicInfo();
        } catch (Exception ex) { System.out.println("[contest] soloRankings membre " + e.getKey() + ": " + ex); }
        row.playerRow = pr;
        row.points = e.getValue()[0];
        row.rank = idx; row.contestRankIndex = idx - 1;
        cr.topPlayers.add(row);
        if (e.getKey() == su.userID) cr.yourInfo = row;
      }
      if (cr.yourInfo == null) {   // filet : ladder vide
        com.perblue.heroes.network.messages.ContestRankingRow mine =
            new com.perblue.heroes.network.messages.ContestRankingRow();
        com.perblue.heroes.network.messages.PlayerRow pr = new com.perblue.heroes.network.messages.PlayerRow();
        pr.info = su.basicInfo(); mine.playerRow = pr;
        mine.points = cd.rankPoints; mine.rank = (cd.rank > 0 ? cd.rank : 1); mine.contestRankIndex = mine.rank - 1;
        cr.yourInfo = mine;
      }
    } catch (Throwable t) { System.out.println("[contest] soloRankings: " + t); }
    return cr;
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

  /** {@link #prepare} + exécute le hook du jeu ({@code ContestHelper.on*}) + livre les paliers franchis. L'appelant persiste. */
  public static synchronized void record(ServerUser su, com.perblue.heroes.game.objects.User user, ContestHook hook) {
    prepare(su, user);
    try { hook.run(user); } catch (Throwable t) { System.out.println("[contest] record: " + t); }
    deliverEarnedProgressRewards(su, user);
  }

  /**
   * RÉCLAMATION AUTOMATIQUE des récompenses de PALIER ({@code progressRewards}) : pour chaque contest actif, tout palier
   * dont les points requis sont ATTEINTS et pas encore marqué gagné → livré par COURRIER (wiki : livraison immédiate au
   * palier) + marqué dans {@code earnedProgressRewards} (idempotent). Les drops viennent de {@code ContestProgressRewardInfo.
   * getRewards(user, formatVersion)} (données admin/jeu, §4). L'appelant persiste ({@code store.save}).
   */
  @SuppressWarnings("unchecked")
  public static synchronized void deliverEarnedProgressRewards(ServerUser su, com.perblue.heroes.game.objects.User user) {
    try {
      com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
      for (Object o : snap.getActiveEvents()) {
        com.perblue.common.specialevent.SpecialEventInfo e = (com.perblue.common.specialevent.SpecialEventInfo) o;
        com.perblue.common.specialevent.components.Contest c =
            (com.perblue.common.specialevent.components.Contest) e.getComponent(com.perblue.common.specialevent.components.Contest.class);
        if (c == null) continue;
        long id = e.getID();
        ContestData cd = getContestData(su, id);
        ClientContestData ccd = clientData(id, cd);
        long pts = ccd.getProgressPoints();
        if (cd.extraData.earnedProgressRewards == null) cd.extraData.earnedProgressRewards = new ArrayList<>();
        for (Object po : c.getProgressRewards()) {
          com.perblue.common.specialevent.components.pieces.ContestProgressRewardInfo pri =
              (com.perblue.common.specialevent.components.pieces.ContestProgressRewardInfo) po;
          int idx = pri.getProgressIndex();
          if (pts >= pri.getRequiredPoints() && !ccd.hasEarnedProgressReward(idx)) {
            java.util.List<com.perblue.heroes.network.messages.RewardDrop> drops =
                (java.util.List<com.perblue.heroes.network.messages.RewardDrop>) pri.getRewards(user, e.getFormatVersion());
            if (drops != null && !drops.isEmpty())
              su.deliverMail(com.perblue.heroes.network.messages.MailType.SYSTEM_MESSAGE, "Contest",
                  "Contest Reward", "You reached " + pri.getRequiredPoints() + " contest points!", drops);
            cd.extraData.earnedProgressRewards.add(Integer.valueOf(idx));
            System.out.println("[contest] palier " + idx + " (" + pri.getRequiredPoints() + " pts) livré par courrier [persisté]");
          }
        }
      }
    } catch (Throwable t) { System.out.println("[contest] deliverEarnedProgressRewards: " + t); }
  }
}
