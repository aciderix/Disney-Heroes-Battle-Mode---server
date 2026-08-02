package dhserver;

import com.perblue.heroes.network.messages.WarBoxInfo;
import com.perblue.heroes.network.messages.WarLeague;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.util.ArrayList;
import java.util.List;

/**
 * GUILD WAR (#68) — L'ORDONNANCEUR : ce que le backend faisait tourner tout seul.
 *
 * <p>Jusqu'ici, chaque brique existait (appariement, phases, clôture, boîtes) mais rien ne les
 * DÉCLENCHAIT. C'est le rôle de ce tour de boucle, idempotent et rejouable :
 * <ol>
 *   <li>bascule de SAISON pour toutes les guildes (avec le rang final, donc les boîtes de fin de saison) ;</li>
 *   <li>CLÔTURE des guerres échues (issue, MMR, remboursement au perdant, boîtes de promotion) ;</li>
 *   <li>AVANCE des phases (SABOTAGE → ACTIVE) ;</li>
 *   <li>APPARIEMENT des guildes inscrites, à l'heure prévue.</li>
 * </ol>
 *
 * <p><b>Calendrier</b> : le client affiche « Next War Starts in » à partir de
 * {@code WarQueueStateUpdate.nextMatchmakingTime}, dont il fait directement {@code warInfo.startTime}
 * ({@code WarClientHelper.updateWarInfoQueueState}). Le moment de l'appariement EST donc le début de la
 * prochaine guerre. <b>⚠️ Lecture structurelle assumée, isolée dans {@link #nextMatchmakingTime}</b> :
 * on l'ancre sur {@code RESET_HOUR} (11 h, fuseau serveur), seule heure que les données fournissent et
 * qu'aucune classe cliente n'utilise — même signature que les autres constantes serveur.
 */
public final class ServerWarScheduler {

  private ServerWarScheduler() {}

  /** Clé de l'horodatage du dernier appariement, dans {@code shard_state}. */
  private static final String LAST_MATCHMAKING = "war_last_matchmaking";

  /**
   * DERNIÈRE occurrence de l'heure d'appariement à {@code now} ou avant — le repère de la fenêtre
   * COURANTE. C'est lui qui décide si un tour doit apparier : on apparie quand la fenêtre courante est
   * plus récente que le dernier appariement enregistré.
   *
   * <p><b>⚠️ LECTURE STRUCTURELLE ASSUMÉE (isolée).</b> `RESET_HOUR` est la seule heure de référence que
   * `war_constants.tab` fournit, et aucune classe cliente ne la lit (même signature que `ELO_K` &c.).
   * Une seule méthode à corriger si un fait la contredit.
   */
  public static long lastMatchmakingTime(long now) {
    org.joda.time.DateTimeZone tz = com.perblue.heroes.util.TimeUtil.getServerDateTimeZone();
    org.joda.time.DateTime today = new org.joda.time.DateTime(now, tz)
        .withTime(ServerWar.resetHour(), 0, 0, 0);
    if (today.getMillis() > now) today = today.minusDays(1);
    return today.getMillis();
  }

  /**
   * Prochain moment d'appariement — et donc de début de guerre (c'est ce que le client affiche dans
   * « Next War Starts in »). Dérivé de {@link #lastMatchmakingTime} : le jour suivant, à la même heure
   * LOCALE (d'où {@code plusDays}, qui absorbe un changement d'heure).
   */
  public static long nextMatchmakingTime(long now) {
    org.joda.time.DateTimeZone tz = com.perblue.heroes.util.TimeUtil.getServerDateTimeZone();
    return new org.joda.time.DateTime(lastMatchmakingTime(now), tz).plusDays(1).getMillis();
  }

  /** Ce qu'a produit un tour d'ordonnanceur — pour journaliser et pour les tests. */
  public static final class Tick {
    public int seasonsRolled;
    public int warsFinished;
    public int phasesAdvanced;
    public int warsOpened;
    public int byes;
    public int promotionBoxesAwarded;
    public int seasonBoxesAwarded;
    public int playersRefunded;
    public boolean matchmakingRan;

    @Override public String toString() {
      return "saisons=" + seasonsRolled + " clôtures=" + warsFinished + " phases=" + phasesAdvanced
          + " guerres=" + warsOpened + " (dont BYE " + byes + ")"
          + " boîtes promo=" + promotionBoxesAwarded + " saison=" + seasonBoxesAwarded
          + " remboursés=" + playersRefunded + (matchmakingRan ? " [appariement]" : "");
    }
  }

  /**
   * Un tour complet. Sûr à rejouer : chaque étape est idempotente (une guerre close ne se recompte pas,
   * une saison déjà basculée ne rebascule pas, l'appariement ne tourne qu'une fois par fenêtre).
   */
  public static Tick tick(UserStore store, int shardID, long now) throws java.sql.SQLException {
    return tick(store, shardID, now, false);
  }

  /**
   * Idem, avec {@code force} = apparier MAINTENANT sans attendre la fenêtre (outil d'opérateur
   * {@code AdminWar --war-tick --force}). Les autres étapes ne se forcent pas : elles dépendent d'échéances
   * réelles (fin de guerre, fin de saison) qu'on ne bouscule pas.
   */
  public static Tick tick(UserStore store, int shardID, long now, boolean force)
      throws java.sql.SQLException {
    Tick t = new Tick();
    if (!ServerWar.enabledForShard(shardID)) return t;

    List<ServerGuild> guilds = store.listGuilds(shardID, null, 10_000);
    // Rang par MMR décroissant : il sert à la fois au reset de saison et aux classements.
    List<ServerGuild> byMMR = new ArrayList<>(guilds);
    byMMR.sort((a, b) -> Integer.compare(ServerWar.currentMMR(b), ServerWar.currentMMR(a)));

    int season = ServerWar.seasonIDAt(now);

    // --- 1. BASCULE DE SAISON (avec boîtes de fin de saison sur la ligue atteinte).
    for (int i = 0; i < byMMR.size(); i++) {
      ServerGuild g = byMMR.get(i);
      if (g.warSeasonID == season) continue;
      int finalRank = i + 1;
      WarLeague achieved = ServerWar.highestLeagueReached(g.warPromotionMask);
      boolean hadSeason = g.warSeasonID != 0;
      if (ServerWar.rollOverSeason(g, season, finalRank)) {
        t.seasonsRolled++;
        // Récompenses de fin de saison : seulement pour une guilde qui a RÉELLEMENT joué une saison
        // (« End-of-Season Rewards are obtainable only by participating in a Guild's War »).
        if (hadSeason && achieved != WarLeague.UNRANKED) {
          t.seasonBoxesAwarded += awardBoxes(store, shardID, g, achieved, finalRank, season, now, false);
        }
        g.warBoxedLeagueMask = 0;                 // nouvelle saison : les promotions se re-gagnent
        store.saveGuild(g);
      }
    }

    // --- 2. CLÔTURE des guerres échues + 3. AVANCE des phases.
    java.util.Set<Long> seen = new java.util.HashSet<>();
    for (ServerGuild g : guilds) {
      if (g.currentWarID <= 0 || !seen.add(g.currentWarID)) continue;
      ServerWarState w = store.loadWar(shardID, g.currentWarID);
      if (w == null) { g.currentWarID = 0L; store.saveGuild(g); continue; }

      if (now >= w.endTime) {
        ServerGuild ga = findGuild(guilds, w.guildAID);
        ServerGuild gb = w.isBye() ? null : findGuild(guilds, w.guildBID);
        if (ga == null) continue;
        ServerWarEnd.Result r = ServerWarEnd.finishWar(store, w, ga, gb, now);
        if (r != null && !r.alreadyFinished) {
          t.warsFinished++;
          t.playersRefunded += ServerWarEnd.creditRefunds(store, shardID, r);
          t.promotionBoxesAwarded += awardPromotionIfNeeded(store, shardID, ga, season, now);
          if (gb != null) t.promotionBoxesAwarded += awardPromotionIfNeeded(store, shardID, gb, season, now);
        }
      } else if (ServerWarMatchmaker.advancePhase(w, now)) {
        t.phasesAdvanced++;
        store.saveWar(w);
      }
    }

    // --- 4. APPARIEMENT, une seule fois par fenêtre.
    //
    // La fenêtre COURANTE est la dernière occurrence de RESET_HOUR ; on apparie si le dernier appariement
    // enregistré lui est antérieur. Comparer à la PROCHAINE occurrence (ce que faisait la première version)
    // était faux : elle est par construction dans le futur, donc la condition n'était JAMAIS vraie.
    //
    // Sur un shard NEUF (aucun repère), on pose le repère de la fenêtre courante SANS apparier : sinon le
    // tout premier tour de vie du serveur déclencherait une guerre hors calendrier, quelle que soit l'heure.
    // Le premier appariement a donc lieu à la prochaine occurrence de RESET_HOUR ; `--war-tick --force`
    // (AdminWar) permet à l'opérateur d'en déclencher un immédiatement.
    long last = readLong(store, shardID, LAST_MATCHMAKING);
    long window = lastMatchmakingTime(now);
    if (last <= 0L && !force) {
      writeLong(store, shardID, LAST_MATCHMAKING, window);
    } else if (force || last < window) {
      t.matchmakingRan = true;
      List<ServerGuild> queued = ServerWarMatchmaker.queuedGuilds(store, shardID);
      for (ServerWarMatchmaker.Pairing p : ServerWarMatchmaker.pair(queued)) {
        ServerWarMatchmaker.openWar(store, shardID, p, now);
        t.warsOpened++;
        if (p.isBye()) t.byes++;
      }
      // On enregistre le repère de la FENÊTRE, pas `now` : sans quoi l'heure d'appariement dériverait un
      // peu plus à chaque tour (le tour tombe toujours après l'heure, jamais dessus).
      writeLong(store, shardID, LAST_MATCHMAKING, window);
    }
    return t;
  }

  /**
   * Un tour sur TOUS les shards qui portent des guildes (PRINCIPLES §5). Rend le cumul des tours.
   */
  public static Tick tickAllShards(UserStore store, long now, boolean force) throws java.sql.SQLException {
    Tick total = new Tick();
    for (Integer shardID : store.listGuildShards()) {
      Tick t = tick(store, shardID, now, force);
      total.seasonsRolled += t.seasonsRolled;
      total.warsFinished += t.warsFinished;
      total.phasesAdvanced += t.phasesAdvanced;
      total.warsOpened += t.warsOpened;
      total.byes += t.byes;
      total.promotionBoxesAwarded += t.promotionBoxesAwarded;
      total.seasonBoxesAwarded += t.seasonBoxesAwarded;
      total.playersRefunded += t.playersRefunded;
      total.matchmakingRan |= t.matchmakingRan;
    }
    return total;
  }

  /** Un tour a-t-il fait quoi que ce soit ? (sert à ne journaliser que les tours utiles) */
  public static boolean isEmpty(Tick t) {
    return t.seasonsRolled == 0 && t.warsFinished == 0 && t.phasesAdvanced == 0 && t.warsOpened == 0
        && t.promotionBoxesAwarded == 0 && t.seasonBoxesAwarded == 0;
  }

  /**
   * Période entre deux tours, en millisecondes. C'est un réglage d'EXPLOITATION (fréquence à laquelle le
   * serveur regarde l'heure), <b>pas</b> une règle de jeu : les échéances, elles, viennent toutes des
   * données (`RESET_HOUR`, `SABOTAGE_PHASE_LENGTH`, saisons). Réglable par {@code -Ddh.war.tick.seconds}.
   */
  public static long tickPeriodMillis() {
    return Math.max(1L, Long.getLong("dh.war.tick.seconds", 60L)) * 1000L;
  }

  /**
   * Démarre la boucle d'ordonnancement en tâche de fond (démon : elle n'empêche pas l'arrêt du serveur).
   * C'est le DÉCLENCHEUR qui manquait : jusqu'ici toutes les briques existaient mais rien ne les appelait.
   */
  public static Thread startBackgroundLoop(UserStore store) {
    Thread th = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          Tick t = tickAllShards(store, com.perblue.heroes.util.TimeUtil.serverTimeNow(), false);
          if (!isEmpty(t)) System.out.println("[war] ordonnanceur : " + t);
        } catch (Throwable ex) {
          // Un tour raté ne doit jamais tuer la boucle : la prochaine occasion rattrapera (tout est idempotent).
          System.out.println("[war] ordonnanceur : tour en échec — " + ex);
        }
        try { Thread.sleep(tickPeriodMillis()); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      }
    }, "war-scheduler");
    th.setDaemon(true);
    th.start();
    System.out.println("[war] ordonnanceur démarré (tour toutes les " + (tickPeriodMillis() / 1000) + " s)");
    return th;
  }

  private static ServerGuild findGuild(List<ServerGuild> all, long guildID) {
    for (ServerGuild g : all) if (g.guildID == guildID) return g;
    return null;
  }

  /**
   * Accorde les boîtes de PROMOTION si la guilde a atteint une ligue pour laquelle elle n'en a pas encore
   * reçu. Le masque {@code warBoxedLeagueMask} est distinct de {@code warPromotionMask} : le second dit
   * « ligue atteinte » (et sert de plancher anti-rétrogradation), le premier « boîtes déjà remises ».
   * Les confondre distribuerait à nouveau des boîtes à chaque guerre.
   */
  private static int awardPromotionIfNeeded(UserStore store, int shardID, ServerGuild g, int season,
      long now) throws java.sql.SQLException {
    WarLeague reached = ServerWar.highestLeagueReached(g.warPromotionMask);
    if (reached == WarLeague.UNRANKED) return 0;
    if ((g.warBoxedLeagueMask & (1 << reached.ordinal())) != 0) return 0;   // déjà remises
    int n = awardBoxes(store, shardID, g, reached, 0, season, now, true);
    g.warBoxedLeagueMask |= (1 << reached.ordinal());
    store.saveGuild(g);
    return n;
  }

  /**
   * Dépose des boîtes chez CHAQUE membre de la guilde. Le montant dépend du NIVEAU D'ÉQUIPE de chacun
   * (variable {@code L} des expressions), donc les boîtes sont générées joueur par joueur.
   *
   * @param promotion {@code true} = boîtes de promotion, {@code false} = boîtes de fin de saison
   * @return le nombre de boîtes déposées
   */
  private static int awardBoxes(UserStore store, int shardID, ServerGuild g, WarLeague league,
      int finalRank, int season, long now, boolean promotion) throws java.sql.SQLException {
    int count = 0;
    for (Long memberID : g.memberIDs) {
      ServerUser u = store.loadIfExists(memberID, shardID);
      if (u == null) continue;
      int teamLevel = u.basicInfo() != null ? u.basicInfo().teamLevel : 1;
      List<WarBoxInfo> boxes = promotion
          ? ServerWarEnd.promotionBoxes(league, teamLevel, season, now, 1L)
          : ServerWarEnd.seasonBoxes(league, Math.max(1, finalRank), teamLevel, season, now, 1L);
      if (boxes.isEmpty()) continue;
      ServerWarBoxes pending = store.loadWarBoxes(shardID, memberID);
      for (WarBoxInfo b : boxes) { pending.add(b); count++; }   // `add` attribue l'identifiant du joueur
      store.saveWarBoxes(shardID, memberID, pending);
    }
    return count;
  }

  private static long readLong(UserStore store, int shardID, String key) throws java.sql.SQLException {
    byte[] raw = store.loadShardState(shardID, key);
    return raw != null && raw.length == 8 ? java.nio.ByteBuffer.wrap(raw).getLong() : 0L;
  }

  private static void writeLong(UserStore store, int shardID, String key, long v)
      throws java.sql.SQLException {
    store.saveShardState(shardID, key, java.nio.ByteBuffer.allocate(8).putLong(v).array());
  }
}
