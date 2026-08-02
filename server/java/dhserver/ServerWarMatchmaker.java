package dhserver;

import com.perblue.heroes.game.data.war.WarStats;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarQueueState;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * GUILD WAR (#68) — APPARIEMENT des guildes inscrites, et ouverture des guerres.
 *
 * <p>C'est de l'<b>état/traitement opérateur</b> : le jar client n'a aucun appariement (il se contente
 * d'afficher la guerre reçue), et les constantes qui le pilotent — {@code REMATCH_THRESHOLD},
 * {@code REMATCH_COST}, {@code WORST/BEST_REMATCH_SCALE}, {@code MAX_PREVIOUS_WARS},
 * {@code BYE_RATING_GAIN} — ne sont référencées par AUCUNE classe cliente (cf. {@link ServerWar}).
 *
 * <p><b>Chronologie des phases : reprise EXACTE du client, pas une invention.</b>
 * {@code WarClientHelper.checkForEndOfSabotage} fait, mot pour mot,
 * « si {@code state == SABOTAGE} et {@code stateEndTime} dépassé → {@code state = ACTIVE} et
 * {@code stateEndTime = endTime} » ; et {@code WarHelper.isBanPhase} lit
 * « {@code state == SABOTAGE} et {@code extraStateEndTime} non dépassé ». D'où :
 * <ul>
 *   <li>ouverture en {@code SABOTAGE} avec {@code stateEndTime = début + SABOTAGE_PHASE_LENGTH} (24 h) et
 *       {@code extraStateEndTime = début + SABOTAGE_BAN_PHASE_LENGTH} (12 h) — « Day one … sabotage
 *       throughout the full 24 hours. During the first 12 hours … Champions and Leader can also set the
 *       Guild's Bans » ;</li>
 *   <li>puis {@code ACTIVE} jusqu'à {@code endTime} — « Day Two is all about clearing rooms ».</li>
 * </ul>
 * <b>Seul point non nommé par une constante</b> : la durée du jour 2. Elle se déduit de « two-day long
 * Wars » (aide du jeu) et du jour 1 à 24 h — {@link #warDuration()} l'isole pour qu'un fait contraire ne
 * demande qu'une seule correction.
 */
public final class ServerWarMatchmaker {

  private ServerWarMatchmaker() {}

  /**
   * Durée totale d'une guerre. « Compete in <b>two-day</b> long Wars against other Guilds » (aide du jeu),
   * et le jour 1 vaut {@code SABOTAGE_PHASE_LENGTH}. Aucune constante ne nomme le jour 2 : il est donc
   * dérivé ici, en un seul endroit.
   */
  public static long warDuration() { return 2L * WarStats.getSabotagePhaseLength(); }

  /** Une guilde candidate à l'appariement, figée au moment du tour. */
  public static final class Candidate {
    public final ServerGuild guild;
    public final int mmr;
    Candidate(ServerGuild g) { this.guild = g; this.mmr = ServerWar.currentMMR(g); }
  }

  /** Un appariement décidé : {@code b == null} ⇒ BYE. */
  public static final class Pairing {
    public final ServerGuild a, b;
    public final int cost;
    Pairing(ServerGuild a, ServerGuild b, int cost) { this.a = a; this.b = b; this.cost = cost; }
    public boolean isBye() { return b == null; }
  }

  /**
   * Pénalité d'appariement pour re-jouer contre {@code opponent}.
   *
   * <p><b>⚠️ LECTURE STRUCTURELLE ASSUMÉE (isolée)</b>, du même genre que {@code ServerWar.ratingChange}.
   * Les quatre constantes disent ce qu'elles pèsent mais aucune table n'écrit la formule :
   * {@code REMATCH_THRESHOLD}=7 délimite « adversaire récent », {@code REMATCH_COST}=200 est le prix d'un
   * re-match, et {@code WORST_REMATCH_SCALE}=1 / {@code BEST_REMATCH_SCALE}=0 encadrent visiblement une
   * ÉCHELLE appliquée à ce prix. La lecture retenue — la seule qui utilise les quatre — est une
   * interpolation par ancienneté : l'adversaire de la guerre PRÉCÉDENTE est le « pire » re-match (échelle
   * {@code WORST}, prix plein), celui d'il y a {@code REMATCH_THRESHOLD} guerres est le « meilleur »
   * (échelle {@code BEST}, prix nul), et au-delà du seuil la pénalité disparaît.
   *
   * @param warsSince rang dans l'historique : 0 = guerre précédente, −1 = jamais rencontré
   */
  public static int rematchPenalty(int warsSince) {
    int threshold = ServerWar.rematchThreshold();
    if (warsSince < 0 || warsSince >= threshold) return 0;
    int worst = constInt("WORST_REMATCH_SCALE", 1);
    int best = constInt("BEST_REMATCH_SCALE", 0);
    double t = threshold <= 1 ? 0.0 : warsSince / (double) (threshold - 1);   // 0 = le plus récent
    double scale = worst + (best - worst) * t;
    return (int) Math.round(ServerWar.rematchCost() * scale);
  }

  private static int constInt(String field, int dflt) {
    try {
      java.lang.reflect.Field cs = WarStats.class.getDeclaredField("CONSTANT_STATS");
      cs.setAccessible(true);
      Object holder = cs.get(null);
      Object stats = holder.getClass().getMethod("getStats").invoke(holder);
      java.lang.reflect.Field f = stats.getClass().getDeclaredField(field);
      f.setAccessible(true);
      Object v = f.get(stats);
      return v instanceof Number ? ((Number) v).intValue() : dflt;
    } catch (Throwable t) {
      return dflt;
    }
  }

  /** Coût d'apparier deux guildes : écart de MMR + pénalité de re-match (des DEUX côtés). */
  public static int pairingCost(Candidate a, Candidate b) {
    int gap = Math.abs(a.mmr - b.mmr);
    int penalty = rematchPenalty(a.guild.warsSinceOpponent(b.guild.guildID))
        + rematchPenalty(b.guild.warsSinceOpponent(a.guild.guildID));
    return gap + penalty;
  }

  /**
   * Apparie les guildes inscrites. Déterministe : on trie par MMR décroissant (puis par identifiant pour
   * lever les ex æquo), et on marie chaque guilde encore libre à la candidate de coût minimal. Un effectif
   * impair laisse la DERNIÈRE guilde sans adversaire — c'est le BYE, que les données prévoient
   * explicitement via {@code BYE_RATING_GAIN}.
   */
  public static List<Pairing> pair(List<ServerGuild> queued) {
    List<Candidate> pool = new ArrayList<>();
    for (ServerGuild g : queued) pool.add(new Candidate(g));
    pool.sort(Comparator.<Candidate>comparingInt(c -> -c.mmr).thenComparingLong(c -> c.guild.guildID));

    List<Pairing> out = new ArrayList<>();
    boolean[] taken = new boolean[pool.size()];
    for (int i = 0; i < pool.size(); i++) {
      if (taken[i]) continue;
      int bestJ = -1, bestCost = Integer.MAX_VALUE;
      for (int j = i + 1; j < pool.size(); j++) {
        if (taken[j]) continue;
        int cost = pairingCost(pool.get(i), pool.get(j));
        if (cost < bestCost) { bestCost = cost; bestJ = j; }
      }
      taken[i] = true;
      if (bestJ < 0) {
        out.add(new Pairing(pool.get(i).guild, null, 0));      // effectif impair → BYE
      } else {
        taken[bestJ] = true;
        out.add(new Pairing(pool.get(i).guild, pool.get(bestJ).guild, bestCost));
      }
    }
    return out;
  }

  /** Le camp initial d'une guilde : identité, ligue et MMR courants ; le reste se remplit ensuite. */
  private static WarGuildInfo initialSide(ServerGuild g) {
    WarGuildInfo side = new WarGuildInfo();
    side.guildInfo = g.info != null ? g.info.basicInfo : null;
    side.mmr = ServerWar.currentMMR(g);
    side.league = ServerWar.effectiveLeague(side.mmr, g.warPromotionMask);
    return side;
  }

  /**
   * Ouvre la guerre décidée par {@code pairing} : construit l'état, met les deux guildes à jour (guerre en
   * cours, historique d'adversaires, état de file selon {@code QUEUED_SINGLE}/{@code QUEUED_PERSISTENT}) et
   * la persiste. Le {@code warID} est attribué par {@code UserStore.saveWar}, sous verrou.
   *
   * <p>Un BYE est enregistré comme une guerre réelle, en état {@link WarSummaryState#BYE} : la guilde a bien
   * « fait » cette guerre (c'est ce que compte {@code MAX_PREVIOUS_WARS}) et elle encaisse
   * {@code BYE_RATING_GAIN}.
   */
  public static ServerWarState openWar(UserStore store, int shardID, Pairing pairing, long now)
      throws java.sql.SQLException {
    ServerWarState w = new ServerWarState();
    w.shardID = shardID;
    w.seasonID = ServerWar.seasonIDAt(now);
    w.startTime = now;
    w.endTime = now + warDuration();
    w.guildAID = pairing.a.guildID;
    w.guildBID = pairing.isBye() ? 0L : pairing.b.guildID;

    if (pairing.isBye()) {
      w.state = WarSummaryState.BYE;
      w.stateEndTime = w.endTime;
      w.extraStateEndTime = 0L;
    } else {
      // Ouverture en phase de SABOTAGE (jour 1), avec la fenêtre de BAN à l'intérieur — exactement ce que
      // lisent WarHelper.isBanPhase et WarClientHelper.checkForEndOfSabotage.
      w.state = WarSummaryState.SABOTAGE;
      w.stateEndTime = now + WarStats.getSabotagePhaseLength();
      w.extraStateEndTime = now + WarStats.getSabotageBanPhaseLenght();
    }

    w.guildAWire = null; w.guildBWire = null;
    w.putSide(pairing.a.guildID, initialSide(pairing.a));
    if (!pairing.isBye()) w.putSide(pairing.b.guildID, initialSide(pairing.b));

    store.saveWar(w);                                   // attribue le warID sous verrou

    // QUI participe, avec quelle défense : c'est l'ouverture de la guerre qui fige la liste des membres
    // (chacun avec ses lineups WAR_DEFENSE_1..3 déjà posés). Sans ça, `WarGuildInfo.members` resterait vide
    // et PERSONNE ne participerait — défaut réel trouvé par la vérification EN JEU, cf. ServerWarMembers.
    int na = ServerWarMembers.syncAll(store, shardID, w, pairing.a);
    int nb = pairing.isBye() ? 0 : ServerWarMembers.syncAll(store, shardID, w, pairing.b);
    System.out.println("[war] guerre ouverte : " + na + " membre(s) côté " + pairing.a.guildID
        + (pairing.isBye() ? " (BYE)" : " · " + nb + " côté " + pairing.b.guildID));
    store.saveWar(w);

    applyToGuild(pairing.a, w, pairing.isBye() ? 0L : pairing.b.guildID);
    if (!pairing.isBye()) applyToGuild(pairing.b, w, pairing.a.guildID);
    store.saveGuild(pairing.a);
    if (!pairing.isBye()) store.saveGuild(pairing.b);
    return w;
  }

  private static void applyToGuild(ServerGuild g, ServerWarState w, long opponentID) {
    g.currentWarID = w.warID;
    g.setWarQueueState(ServerWar.queueStateAfterMatch(g.warQueueState()));
    g.warQueuedTime = 0L;
    // La fenêtre de guerre doit être écrite dans le GuildInfo DU JEU : c'est elle que le client relit
    // (`WarHelper.isWarActive` compare `getYourGuildInfo().warEndTime` à l'heure serveur). Sans ça, le
    // client considérerait qu'aucune guerre n'est en cours.
    g.setWarWindow(w.startTime, w.endTime);
    if (opponentID > 0) g.rememberWarOpponent(opponentID, ServerWar.maxPreviousWars());
  }

  /**
   * Fait avancer la phase d'une guerre si son échéance est passée — même règle que le client
   * ({@code checkForEndOfSabotage}) : {@code SABOTAGE} → {@code ACTIVE} avec
   * {@code stateEndTime = endTime}. La fin de guerre (issue + MMR) est traitée à part.
   *
   * @return {@code true} si la phase a changé
   */
  public static boolean advancePhase(ServerWarState w, long now) {
    if (w.state == WarSummaryState.SABOTAGE && w.stateEndTime <= now) {
      w.state = WarSummaryState.ACTIVE;
      w.stateEndTime = w.endTime;
      return true;
    }
    return false;
  }

  /** La guerre est-elle dans sa fenêtre de BAN ? (miroir serveur de {@code WarHelper.isBanPhase}) */
  public static boolean isBanPhase(ServerWarState w, long now) {
    return w.state == WarSummaryState.SABOTAGE && w.extraStateEndTime > now;
  }

  /** Les guildes encore inscrites du shard, dans l'ordre d'inscription (le plus ancien d'abord). */
  public static List<ServerGuild> queuedGuilds(UserStore store, int shardID) throws java.sql.SQLException {
    List<ServerGuild> out = new ArrayList<>();
    for (ServerGuild g : store.listGuilds(shardID, null, 10_000)) {
      if (g.currentWarID > 0) continue;                                  // déjà en guerre
      if (g.warQueueState() == WarQueueState.NOT_QUEUED) continue;
      out.add(g);
    }
    out.sort(Comparator.comparingLong(g -> g.warQueuedTime));
    return out;
  }
}
