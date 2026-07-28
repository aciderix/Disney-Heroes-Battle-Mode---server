package dhserver;

import com.perblue.heroes.game.data.war.WarStats;
import com.perblue.heroes.game.logic.WarHelper;
import com.perblue.heroes.network.messages.WarLeague;
import com.perblue.heroes.network.messages.WarSummaryState;

/**
 * GUILD WAR (#68) — SAISONS, LIGUES et NOTE DE MATCHMAKING (MMR), calculées depuis les DONNÉES DU JEU.
 *
 * <p><b>Ce que le jar contient</b> (vérifié, pas supposé — détail dans {@code docs/GUILD_WAR.md}) : toute la
 * logique d'écran et de règles est CÔTÉ CLIENT ({@code WarStats}, {@code WarHelper}, {@code WarCombatHelper},
 * {@code ui.war.WarClientHelper}) et 9 tables {@code war_*.tab} portent l'équilibrage. Comme pour l'arène et
 * l'invasion, le serveur n'a donc qu'à produire les bons messages et à RÉ-EXÉCUTER les mêmes validations.
 *
 * <p><b>Pourquoi le MMR est au SERVEUR</b> : scan du pool de constantes des 20 341 classes {@code com/perblue/**}
 * — 28 constantes de {@code war_constants.tab} ({@code ELO_K}, {@code ELO_N}, {@code WIN/LOSE/DRAW_COEFFICIENT},
 * {@code NORMALIZE_RATING_TO}, {@code STARTING_MMR}, {@code TOP_GUILDS_*}, {@code REMATCH_*}, {@code BAN_*},
 * {@code PROTECT_*}…) n'apparaissent QUE dans leur propre déclaration et dans AUCUNE autre classe. Témoin de
 * contrôle : les constantes réellement consommées ({@code BASE_CAR_SIZE}, {@code TIMEOUTS}…) apparaissent bien
 * dans ≥ 2 classes. C'est exactement la signature de {@code MERCENARY_COST} et {@code INVASION_BASE_DATE} :
 * ces valeurs sont parsées POUR LE SERVEUR. Cohérent avec {@code WarHelper.isWarActive}, qui délègue à une
 * {@code WarHelperExtension} dont aucune implémentation cliente n'existe.
 *
 * <p><b>Le jeu documente lui-même ses règles</b> : {@code game-data/strings/HowToPlay.properties} (cartes
 * {@code WAR_CARD_*}) énonce le barème (1 point par lineup battu, 100 par salle), les phases (sabotage 24 h,
 * bans les 12 premières heures), et la remise à zéro de saison — et ces énoncés correspondent UN À UN aux
 * constantes. Le reset de saison implémenté ici en découle directement, il n'est pas inventé.
 *
 * <p><b>Seule lecture structurelle assumée</b> (documentée, isolée) : {@link #ratingChange} — voir sa doc.
 */
public final class ServerWar {

  private ServerWar() {}

  // ---------------------------------------------------------------------------------------------
  // Constantes du jeu (WarStats$Constants) — champs PACKAGE-PRIVATE sans accesseur.
  // ---------------------------------------------------------------------------------------------

  /**
   * Lit un champ de {@code WarStats$Constants}. Les champs sont <b>package-private</b> : il FAUT
   * {@code getDeclaredField} + {@code setAccessible}. Le piège a déjà coûté un bug silencieux sur
   * {@code GuildStats$Constants}, où {@code getField} retombait TOUJOURS sur les valeurs par défaut du
   * constructeur sans lever la moindre erreur.
   */
  private static Object constant(String field) {
    try {
      java.lang.reflect.Field cs = WarStats.class.getDeclaredField("CONSTANT_STATS");
      cs.setAccessible(true);
      Object holder = cs.get(null);
      Object stats = holder.getClass().getMethod("getStats").invoke(holder);
      java.lang.reflect.Field f = stats.getClass().getDeclaredField(field);
      f.setAccessible(true);
      return f.get(stats);
    } catch (Throwable t) {
      System.out.println("[war] constante " + field + " indisponible : " + t);
      return null;
    }
  }

  private static int constInt(String field, int dflt) {
    Object o = constant(field);
    return o instanceof Number ? ((Number) o).intValue() : dflt;
  }

  private static float constFloat(String field, float dflt) {
    Object o = constant(field);
    return o instanceof Number ? ((Number) o).floatValue() : dflt;
  }

  /** MMR d'une guilde qui n'a jamais fait la guerre ({@code STARTING_MMR}). */
  public static int startingMMR()            { return constInt("STARTING_MMR", 10); }
  /** Facteur K de l'Elo ({@code ELO_K}). */
  public static int eloK()                   { return constInt("ELO_K", 200); }
  /** Échelle logistique de l'Elo ({@code ELO_N}) — remplace le 400 de l'Elo classique. */
  public static int eloN()                   { return constInt("ELO_N", 2000); }
  /**
   * {@code ELO_LOSS_BUFFER_THRESHOLD} — <b>exposée mais VOLONTAIREMENT NON APPLIQUÉE</b>.
   *
   * <p>Rien dans le jar ni dans les textes du jeu ne dit ce que cette valeur tamponne (un MMR plancher ? un
   * écart de MMR ? une fenêtre d'appariement ?). Lui prêter un rôle serait <b>inventer une règle</b>
   * (PRINCIPLES §4) et le premier essai — « sous ce MMR on ne perd pas de points » — s'est révélé à la fois
   * arbitraire ET sans effet (voir {@link #ratingChange} : avec {@code LOSE_COEFFICIENT}=0.4 une défaite
   * contre bien plus fort est déjà positive). On la lit donc, on la documente, et on ne s'en sert pas tant
   * qu'un fait ne l'aura pas éclairée.
   */
  public static int lossBufferThreshold()    { return constInt("ELO_LOSS_BUFFER_THRESHOLD", 500); }
  public static float winCoefficient()       { return constFloat("WIN_COEFFICIENT", 1.00f); }
  public static float loseCoefficient()      { return constFloat("LOSE_COEFFICIENT", 0.40f); }
  public static float drawCoefficient()      { return constFloat("DRAW_COEFFICIENT", 0.50f); }
  /** Gain de MMR quand aucun adversaire n'a pu être apparié ({@code BYE_RATING_GAIN}). */
  public static int byeRatingGain()          { return constInt("BYE_RATING_GAIN", 50); }
  /** Plafond de MMR appliqué aux guildes HORS top à la remise à zéro ({@code NORMALIZE_RATING_TO}). */
  public static int normalizeRatingTo()      { return constInt("NORMALIZE_RATING_TO", 599); }
  /** MMR de la 1ʳᵉ guilde à la remise à zéro ({@code TOP_GUILDS_BASE_RATING}). */
  public static int topGuildsBaseRating()    { return constInt("TOP_GUILDS_BASE_RATING", 700); }
  /** Nombre de guildes concernées par ce traitement ({@code TOP_X_GUILDS_TO_BASE}). */
  public static int topXGuildsToBase()       { return constInt("TOP_X_GUILDS_TO_BASE", 10); }
  /** Décrément appliqué entre deux rangs du top ({@code TOP_GUILDS_RATING_DECREMENT}). */
  public static int topGuildsRatingDecrement() { return constInt("TOP_GUILDS_RATING_DECREMENT", 10); }
  /** Nombre d'adversaires récents mémorisés pour l'anti-rematch ({@code MAX_PREVIOUS_WARS}). */
  public static int maxPreviousWars()        { return constInt("MAX_PREVIOUS_WARS", 20); }
  /** Au-delà de ce nombre de guerres, un adversaire déjà rencontré redevient appariable
   *  ({@code REMATCH_THRESHOLD}). */
  public static int rematchThreshold()       { return constInt("REMATCH_THRESHOLD", 7); }
  /** Pénalité de score d'appariement pour un adversaire récent ({@code REMATCH_COST}). */
  public static int rematchCost()            { return constInt("REMATCH_COST", 200); }
  /** Points gagnés par lineup ennemi battu ({@code POINTS_PER_LINEUP}) — « Defeating an enemy Hero team
   *  earns your Guild one point » (aide du jeu). */
  public static int pointsPerLineup()        { return constInt("POINTS_PER_LINEUP", 1); }
  /** Points gagnés par salle/voiture nettoyée ({@code POINTS_PER_CAR}) — « worth 100 points » (aide du jeu). */
  public static int pointsPerCar()           { return constInt("POINTS_PER_CAR", 100); }
  /** Nombre de boîtes de fin de saison ({@code NUM_SEASON_BOXES}). */
  public static int numSeasonBoxes()         { return constInt("NUM_SEASON_BOXES", 5); }
  /** Heure de bascule quotidienne du mode, fuseau serveur ({@code RESET_HOUR}). */
  public static int resetHour()              { return constInt("RESET_HOUR", 11); }

  // ---------------------------------------------------------------------------------------------
  // Saisons — délégué à WarHelper (donc au fuseau serveur du jeu et à RESET_HOUR).
  // ---------------------------------------------------------------------------------------------

  /** Identifiant de la saison contenant {@code now} (une saison = un mois calendaire). */
  public static int seasonIDAt(long now) {
    org.joda.time.DateTime d =
        new org.joda.time.DateTime(now, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone());
    return WarHelper.getSeasonID(d.getYear(), d.getMonthOfYear());
  }

  /** Début de la saison {@code seasonID} (1ᵉʳ du mois à {@code RESET_HOUR}, fuseau serveur). */
  public static long seasonStartTime(int seasonID) { return WarHelper.getSeasonStartTime(seasonID); }

  /** Fin de la saison {@code seasonID} = début de la suivante. */
  public static long seasonEndTime(int seasonID) { return WarHelper.getSeasonStartTime(seasonID + 1); }

  public static int seasonYear(int seasonID)  { return WarHelper.getSeasonYear(seasonID); }
  public static int seasonMonth(int seasonID) { return WarHelper.getSeasonMonth(seasonID); }

  /** La guerre est-elle activée sur ce shard ? ({@code ENABLED_SERVERS} — shard 1 l'est). */
  public static boolean enabledForShard(int shardID) { return WarStats.isWarEnabledForServer(shardID); }

  // ---------------------------------------------------------------------------------------------
  // Ligues — bornes lues dans war_league_brackets.tab via WarStats (aucune table recopiée).
  // ---------------------------------------------------------------------------------------------

  /** MMR minimal de la ligue, d'après les données du jeu. */
  public static int leagueMinMMR(WarLeague league) {
    try {
      Object range = WarStats.getLeagueRange(league);
      return (int) ((Number) range.getClass().getMethod("getMin").invoke(range)).longValue();
    } catch (Throwable t) {
      return 0;
    }
  }

  /** MMR maximal de la ligue, d'après les données du jeu. */
  public static long leagueMaxMMR(WarLeague league) {
    try {
      Object range = WarStats.getLeagueRange(league);
      return ((Number) range.getClass().getMethod("getMax").invoke(range)).longValue();
    } catch (Throwable t) {
      return Integer.MAX_VALUE;
    }
  }

  /**
   * Ligue correspondant à un MMR. Les plages du jeu sont contiguës et croissantes
   * (UNRANKED 0 · COPPER 1 · BRONZE 200 · SILVER 400 · GOLD 600 · PLATINUM 800 · CHALLENGER 1000 ·
   * LEGENDARY 1200+) → on prend la plus HAUTE ligue dont le minimum est atteint.
   */
  public static WarLeague leagueForMMR(int mmr) {
    WarLeague[] all = WarLeague.values();
    for (int i = all.length - 1; i >= 0; i--) {
      if (mmr >= leagueMinMMR(all[i])) return all[i];
    }
    return WarLeague.UNRANKED;
  }

  // ---------------------------------------------------------------------------------------------
  // « Pas de rétrogradation d'une ligue déjà atteinte dans la saison »
  // (aide du jeu WAR_CARD_6_BULLET_D3 ; implémenté par UserFlag.WAR_PROMOTIONS + updatePromotionFlag).
  // ---------------------------------------------------------------------------------------------

  /** Ajoute {@code league} au masque de ligues atteintes (même encodage que {@code updatePromotionFlag}). */
  public static int markLeagueReached(int promotionMask, WarLeague league) {
    return promotionMask | (1 << league.ordinal());
  }

  /** La plus haute ligue atteinte dans la saison, d'après le masque. */
  public static WarLeague highestLeagueReached(int promotionMask) {
    WarLeague best = WarLeague.UNRANKED;
    for (WarLeague l : WarLeague.values()) {
      if ((promotionMask & (1 << l.ordinal())) != 0 && l.ordinal() > best.ordinal()) best = l;
    }
    return best;
  }

  /** Ligue effective : jamais en dessous de la plus haute déjà atteinte dans la saison. */
  public static WarLeague effectiveLeague(int mmr, int promotionMask) {
    WarLeague byMMR = leagueForMMR(mmr);
    WarLeague floor = highestLeagueReached(promotionMask);
    return byMMR.ordinal() >= floor.ordinal() ? byMMR : floor;
  }

  // ---------------------------------------------------------------------------------------------
  // Remise à zéro de fin de saison — RÈGLE ÉNONCÉE PAR LE JEU (HowToPlay WAR_CARD_6_BULLET_A*).
  // ---------------------------------------------------------------------------------------------

  /**
   * Nouveau MMR d'une guilde au début d'une saison, d'après son classement final.
   *
   * <p>Règle du jeu, citée : « The top ten Guilds (based on MMR) from the previous season will have their MMR
   * reset to start the new season in the <b>Gold</b> league » et « All other Guilds will be seeded from
   * <b>Copper to Silver</b> in accordance with their MMR ». Les constantes s'y superposent exactement :
   * {@code TOP_X_GUILDS_TO_BASE=10}, {@code TOP_GUILDS_BASE_RATING=700} avec
   * {@code TOP_GUILDS_RATING_DECREMENT=10} place les rangs 1..10 entre 700 et 610 — soit ENTIÈREMENT dans
   * la plage GOLD (600-799) — et {@code NORMALIZE_RATING_TO=599} est EXACTEMENT le plafond de SILVER
   * (400-599), c'est-à-dire la borne haute de « Copper to Silver ».
   *
   * @param finalRank rang final dans le classement de la saison écoulée (1 = premier ; ≤ 0 = non classé)
   * @param finalMMR  MMR final
   */
  public static int seasonResetMMR(int finalRank, int finalMMR) {
    if (finalRank >= 1 && finalRank <= topXGuildsToBase()) {
      return topGuildsBaseRating() - (finalRank - 1) * topGuildsRatingDecrement();
    }
    return Math.min(Math.max(finalMMR, startingMMR()), normalizeRatingTo());
  }

  // ---------------------------------------------------------------------------------------------
  // Variation de MMR après une guerre.
  // ---------------------------------------------------------------------------------------------

  /**
   * Variation de MMR de la guilde {@code myMMR} après une guerre contre {@code theirMMR}.
   *
   * <p><b>⚠️ LECTURE STRUCTURELLE ASSUMÉE — isolée ici exprès</b> (même traitement que
   * {@code ServerInvasion.teamForRotation}). Faits : le jeu ÉNONCE que « Your Guild's MMR goes up or down
   * depending on if your Guild wins or loses a War » et FOURNIT {@code ELO_K}, {@code ELO_N},
   * {@code WIN/LOSE/DRAW_COEFFICIENT}, {@code ELO_LOSS_BUFFER_THRESHOLD} — mais AUCUNE table du jar n'écrit
   * la formule. On applique donc l'algorithme que ces noms désignent, l'Elo standard, avec {@code N} en
   * échelle logistique (au lieu du 400 conventionnel) et {@code K} en facteur :
   * <pre>
   *   E  = 1 / (1 + 10^((MMRadv − MMRmoi) / N))     (score attendu)
   *   ΔR = K × (coefficient(issue) − E)
   * </pre>
   * Un BYE donne {@code BYE_RATING_GAIN} fixe, comme son nom l'indique. {@code ELO_LOSS_BUFFER_THRESHOLD}
   * n'est <b>pas</b> appliquée (cf. {@link #lossBufferThreshold()}). Une seule méthode à corriger si une
   * preuve contraire apparaît.
   *
   * <p><b>Propriété mesurée des constantes du jeu</b> (pas un choix de notre part) : comme
   * {@code LOSE_COEFFICIENT}=0.4 &gt; 0, une DÉFAITE contre un adversaire suffisamment plus fort rapporte
   * quand même du MMR. Le point de bascule est {@code N × log10((1−LOSE)/LOSE)} ≈ <b>352</b> points d'écart
   * avec les valeurs actuelles. C'est visiblement voulu : un Elo « pur » utiliserait 0 pour une défaite, et
   * la table n'aurait alors aucune raison de porter un coefficient de défaite.
   *
   * @param state issue de la guerre : {@code VICTORY}, {@code DEFEAT}, {@code DRAW} ou {@code BYE}
   * @return la VARIATION brute ; utiliser {@link #applyRatingChange} pour obtenir le MMR résultant borné
   */
  public static int ratingChange(int myMMR, int theirMMR, WarSummaryState state) {
    if (state == WarSummaryState.BYE) return byeRatingGain();
    float coeff;
    switch (state) {
      case VICTORY: coeff = winCoefficient();  break;
      case DEFEAT:  coeff = loseCoefficient(); break;
      case DRAW:    coeff = drawCoefficient(); break;
      default:      return 0;
    }
    double expected = 1.0 / (1.0 + Math.pow(10.0, (theirMMR - myMMR) / (double) eloN()));
    return (int) Math.round(eloK() * (coeff - expected));
  }

  /**
   * Applique une variation en bornant le MMR au plancher des DONNÉES du jeu.
   *
   * <p>Le plancher n'est pas choisi : c'est le minimum de la table de ligues elle-même
   * ({@code war_league_brackets.tab} commence à {@code UNRANKED = 0}), donc un MMR négatif ne correspondrait
   * à aucune ligue existante.
   */
  public static int applyRatingChange(int mmr, int delta) {
    return Math.max(leagueMinMMR(WarLeague.UNRANKED), mmr + delta);
  }

  /**
   * Écart de MMR à partir duquel une DÉFAITE devient malgré tout un gain, dérivé des constantes :
   * {@code N × log10((1 − LOSE_COEFFICIENT) / LOSE_COEFFICIENT)}.
   */
  public static int defeatBreakEvenGap() {
    double c = loseCoefficient();
    if (c <= 0 || c >= 1) return Integer.MAX_VALUE;
    return (int) Math.round(eloN() * Math.log10((1 - c) / c));
  }
}
