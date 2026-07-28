import com.perblue.heroes.game.data.war.WarStats;
import com.perblue.heroes.game.logic.WarHelper;
import com.perblue.heroes.network.messages.WarLeague;
import com.perblue.heroes.network.messages.WarSummaryState;
import dhserver.ServerContext;
import dhserver.ServerWar;

/**
 * GUILD WAR #68 — ÉTAPE 1 : saisons, ligues, MMR.
 *
 * <p>Toutes les assertions se mesurent CONTRE LES DONNÉES DU JEU (ou contre la logique du jeu), jamais contre
 * des nombres recopiés à la main : les bornes de ligue sont relues via {@code WarStats.getLeagueRange}, les
 * saisons via {@code WarHelper.getSeasonID/Year/Month/StartTime}, et les constantes via les accesseurs de
 * {@link ServerWar} (eux-mêmes adossés à {@code WarStats$Constants} par réflexion).
 *
 * <p>Prouve : identité de saison et aller-retour, ancrage sur {@code RESET_HOUR} au fuseau serveur, bornes de
 * ligue conformes aux données, activation du shard, remise à zéro de saison (top 10 → GOLD, autres écrêtés au
 * plafond SILVER), plancher de ligue non rétrogradable, et propriétés du delta de MMR.
 */
public final class WarSeasonTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ---------------------------------------------------------------------------------------
    // 1. SAISONS — une saison = un mois calendaire, ancrée à RESET_HOUR au fuseau serveur.
    // ---------------------------------------------------------------------------------------
    org.joda.time.DateTimeZone tz = com.perblue.heroes.util.TimeUtil.getServerDateTimeZone();
    int resetHour = ServerWar.resetHour();
    System.out.println("[war] fuseau serveur=" + tz + " RESET_HOUR=" + resetHour);

    for (int[] ym : new int[][]{{2018, 1}, {2018, 12}, {2019, 1}, {2026, 7}, {2026, 12}}) {
      int id = WarHelper.getSeasonID(ym[0], ym[1]);
      check(ServerWar.seasonYear(id) == ym[0] && ServerWar.seasonMonth(id) == ym[1],
          "aller-retour de saison cassé pour " + ym[0] + "/" + ym[1] + " (id=" + id + ")");
      org.joda.time.DateTime start = new org.joda.time.DateTime(ServerWar.seasonStartTime(id), tz);
      check(start.getYear() == ym[0] && start.getMonthOfYear() == ym[1] && start.getDayOfMonth() == 1,
          "le début de saison doit être le 1er du mois, obtenu " + start);
      check(start.getHourOfDay() == resetHour,
          "le début de saison doit être à RESET_HOUR=" + resetHour + ", obtenu " + start.getHourOfDay());
      // La fin d'une saison est le début de la suivante (aucun trou, aucun recouvrement).
      check(ServerWar.seasonEndTime(id) == ServerWar.seasonStartTime(id + 1), "fin de saison ≠ début suivante");
      check(ServerWar.seasonEndTime(id) > ServerWar.seasonStartTime(id), "saison de durée négative");
    }
    // Un instant tombant DANS la saison est bien attribué à cette saison.
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    int cur = ServerWar.seasonIDAt(now);
    check(now >= ServerWar.seasonStartTime(cur) && now < ServerWar.seasonEndTime(cur),
        "l'instant courant doit être dans sa propre saison (id=" + cur + ")");
    System.out.println("[war] saison courante id=" + cur + " (" + ServerWar.seasonYear(cur) + "/"
        + ServerWar.seasonMonth(cur) + "), du "
        + new org.joda.time.DateTime(ServerWar.seasonStartTime(cur), tz) + " au "
        + new org.joda.time.DateTime(ServerWar.seasonEndTime(cur), tz));

    // ---------------------------------------------------------------------------------------
    // 2. LIGUES — les bornes viennent de war_league_brackets.tab, pas d'une copie.
    // ---------------------------------------------------------------------------------------
    WarLeague prev = null;
    for (WarLeague l : WarLeague.values()) {
      int min = ServerWar.leagueMinMMR(l);
      long max = ServerWar.leagueMaxMMR(l);
      check(min <= max, "plage de ligue incohérente pour " + l + " : " + min + ".." + max);
      if (prev != null) {
        check(min > ServerWar.leagueMinMMR(prev),
            "les minima de ligue doivent croître (" + prev + "=" + ServerWar.leagueMinMMR(prev)
                + " puis " + l + "=" + min + ")");
        check(ServerWar.leagueMaxMMR(prev) + 1 == min,
            "les plages doivent être contiguës entre " + prev + " et " + l);
      }
      // Le MMR minimal de la ligue doit être classé DANS cette ligue.
      check(ServerWar.leagueForMMR(min) == l, "leagueForMMR(" + min + ") devrait être " + l
          + ", obtenu " + ServerWar.leagueForMMR(min));
      // Et juste en dessous, dans la précédente.
      if (prev != null) {
        check(ServerWar.leagueForMMR(min - 1) == prev,
            "leagueForMMR(" + (min - 1) + ") devrait être " + prev);
      }
      prev = l;
      System.out.println("[war] ligue " + l + " : " + min + " .. " + max);
    }

    // ---------------------------------------------------------------------------------------
    // 3. ACTIVATION DU SHARD (ENABLED_SERVERS des données du jeu).
    // ---------------------------------------------------------------------------------------
    check(ServerWar.enabledForShard(1), "le shard 1 doit être activé pour la guerre (ENABLED_SERVERS)");
    check(ServerWar.enabledForShard(1) == WarStats.isWarEnabledForServer(1), "désaccord avec WarStats");
    System.out.println("[war] shard 1 activé=" + ServerWar.enabledForShard(1)
        + " · shard 99 activé=" + ServerWar.enabledForShard(99));

    // ---------------------------------------------------------------------------------------
    // 4. CONSTANTES lues par réflexion — la lecture doit RÉUSSIR (piège getField/getDeclaredField).
    //    On ne teste pas des valeurs en dur : on teste qu'elles sont COHÉRENTES entre elles.
    // ---------------------------------------------------------------------------------------
    check(ServerWar.eloK() > 0, "ELO_K doit être lu (>0)");
    check(ServerWar.eloN() > 0, "ELO_N doit être lu (>0)");
    check(ServerWar.startingMMR() > 0, "STARTING_MMR doit être lu (>0)");
    check(ServerWar.winCoefficient() > ServerWar.drawCoefficient(), "WIN doit valoir plus que DRAW");
    check(ServerWar.drawCoefficient() > ServerWar.loseCoefficient(), "DRAW doit valoir plus que LOSE");
    check(ServerWar.topXGuildsToBase() > 0 && ServerWar.topGuildsBaseRating() > 0, "TOP_GUILDS_* doivent être lus");
    check(ServerWar.pointsPerCar() > ServerWar.pointsPerLineup(),
        "une salle doit valoir plus qu'un lineup (aide du jeu : 100 vs 1)");
    System.out.println("[war] ELO_K=" + ServerWar.eloK() + " ELO_N=" + ServerWar.eloN()
        + " départ=" + ServerWar.startingMMR() + " coeffs W/D/L=" + ServerWar.winCoefficient() + "/"
        + ServerWar.drawCoefficient() + "/" + ServerWar.loseCoefficient()
        + " · points lineup=" + ServerWar.pointsPerLineup() + " salle=" + ServerWar.pointsPerCar());

    // ---------------------------------------------------------------------------------------
    // 5. REMISE À ZÉRO DE SAISON — règle ÉNONCÉE par le jeu (HowToPlay WAR_CARD_6_BULLET_A2/A3).
    //    « top ten → Gold » / « all other Guilds from Copper to Silver ».
    // ---------------------------------------------------------------------------------------
    for (int rank = 1; rank <= ServerWar.topXGuildsToBase(); rank++) {
      int reset = ServerWar.seasonResetMMR(rank, 5000);
      WarLeague lg = ServerWar.leagueForMMR(reset);
      check(lg == WarLeague.GOLD,
          "rang " + rank + " du top doit repartir en GOLD (MMR " + reset + " → " + lg + ")");
    }
    System.out.println("[war] top " + ServerWar.topXGuildsToBase() + " → MMR "
        + ServerWar.seasonResetMMR(1, 5000) + ".." + ServerWar.seasonResetMMR(ServerWar.topXGuildsToBase(), 5000)
        + " (tout GOLD)");

    int silverMax = (int) ServerWar.leagueMaxMMR(WarLeague.SILVER);
    check(ServerWar.normalizeRatingTo() == silverMax,
        "NORMALIZE_RATING_TO (" + ServerWar.normalizeRatingTo() + ") doit être le plafond de SILVER ("
            + silverMax + ") — c'est ce que dit « seeded from Copper to Silver »");
    for (int[] c : new int[][]{{11, 5000}, {50, 1500}, {200, 300}, {999, 0}}) {
      int reset = ServerWar.seasonResetMMR(c[0], c[1]);
      WarLeague lg = ServerWar.leagueForMMR(reset);
      check(reset <= ServerWar.normalizeRatingTo(),
          "hors top, le MMR doit être écrêté à " + ServerWar.normalizeRatingTo() + ", obtenu " + reset);
      check(reset >= ServerWar.startingMMR(), "le MMR remis à zéro ne peut pas passer sous STARTING_MMR");
      check(lg.ordinal() >= WarLeague.COPPER.ordinal() && lg.ordinal() <= WarLeague.SILVER.ordinal(),
          "hors top, la ligue doit être entre COPPER et SILVER, obtenu " + lg + " (MMR " + reset + ")");
    }
    System.out.println("[war] hors top → écrêté à " + ServerWar.normalizeRatingTo()
        + " (plafond SILVER) : COPPER→SILVER conforme à l'aide du jeu");

    // ---------------------------------------------------------------------------------------
    // 6. PLANCHER DE LIGUE — « once a Guild reaches a League … it cannot be demoted » (WAR_CARD_6_D3).
    //    Même encodage que WarHelper.updatePromotionFlag (masque de bits sur l'ordinal).
    // ---------------------------------------------------------------------------------------
    int mask = 0;
    check(ServerWar.highestLeagueReached(mask) == WarLeague.UNRANKED, "masque vide → UNRANKED");
    mask = ServerWar.markLeagueReached(mask, WarLeague.SILVER);
    mask = ServerWar.markLeagueReached(mask, WarLeague.GOLD);
    check(ServerWar.highestLeagueReached(mask) == WarLeague.GOLD, "la plus haute atteinte doit être GOLD");
    // MMR retombé en BRONZE, mais GOLD a été atteint → la ligue effective reste GOLD.
    int bronzeMMR = ServerWar.leagueMinMMR(WarLeague.BRONZE);
    check(ServerWar.leagueForMMR(bronzeMMR) == WarLeague.BRONZE, "MMR témoin mal choisi");
    check(ServerWar.effectiveLeague(bronzeMMR, mask) == WarLeague.GOLD,
        "une guilde ne doit pas être rétrogradée sous une ligue déjà atteinte");
    // En revanche, monter au-dessus du plancher reste possible.
    int platMMR = ServerWar.leagueMinMMR(WarLeague.PLATINUM);
    check(ServerWar.effectiveLeague(platMMR, mask) == WarLeague.PLATINUM, "la promotion doit rester possible");
    // Le masque doit être compatible bit-à-bit avec celui du jeu (updatePromotionFlag = 1 << ordinal).
    check(ServerWar.markLeagueReached(0, WarLeague.GOLD) == (1 << WarLeague.GOLD.ordinal()),
        "l'encodage du masque doit être celui de WarHelper.updatePromotionFlag");
    System.out.println("[war] plancher de ligue : atteint GOLD → reste GOLD même à MMR " + bronzeMMR);

    // ---------------------------------------------------------------------------------------
    // 7. DELTA DE MMR — propriétés structurelles (la formule elle-même est documentée comme
    //    lecture assumée dans ServerWar.ratingChange).
    // ---------------------------------------------------------------------------------------
    int hi = 1500, lo = 800;
    int winVsStronger = ServerWar.ratingChange(lo, hi, WarSummaryState.VICTORY);
    int winVsWeaker = ServerWar.ratingChange(hi, lo, WarSummaryState.VICTORY);
    check(winVsStronger > 0 && winVsWeaker > 0, "une victoire doit faire monter le MMR");
    check(winVsStronger > winVsWeaker,
        "battre plus fort doit rapporter plus (" + winVsStronger + " vs " + winVsWeaker + ")");
    int lossVsWeaker = ServerWar.ratingChange(hi, lo, WarSummaryState.DEFEAT);
    check(lossVsWeaker < 0, "perdre contre plus faible doit faire descendre, obtenu " + lossVsWeaker);
    int lossVsStronger = ServerWar.ratingChange(lo, hi, WarSummaryState.DEFEAT);
    check(lossVsStronger > lossVsWeaker, "perdre contre plus fort doit coûter moins cher");
    check(Math.abs(winVsStronger) <= ServerWar.eloK() && Math.abs(lossVsWeaker) <= ServerWar.eloK(),
        "la variation ne peut pas dépasser ELO_K");
    // Égaux : le nul est neutre, la victoire monte, la défaite descend.
    int even = 1000;
    int evenWin = ServerWar.ratingChange(even, even, WarSummaryState.VICTORY);
    int evenDraw = ServerWar.ratingChange(even, even, WarSummaryState.DRAW);
    int evenLoss = ServerWar.ratingChange(even, even, WarSummaryState.DEFEAT);
    check(evenDraw == 0, "à MMR égal, un nul ne doit rien changer, obtenu " + evenDraw);
    check(evenWin > 0 && evenLoss < 0, "à MMR égal : victoire positive, défaite négative");

    // PROPRIÉTÉ DES CONSTANTES DU JEU (mesurée, pas choisie) : LOSE_COEFFICIENT > 0 fait qu'une défaite
    // contre BIEN plus fort rapporte quand même. Le point de bascule est dérivé, jamais écrit en dur.
    int gap = ServerWar.defeatBreakEvenGap();
    check(gap > 0, "le point de bascule de défaite doit être dérivable des constantes");
    check(ServerWar.ratingChange(even, even + gap + 50, WarSummaryState.DEFEAT) > 0,
        "au-delà de " + gap + " points d'écart, une défaite doit rapporter (LOSE_COEFFICIENT="
            + ServerWar.loseCoefficient() + ")");
    check(ServerWar.ratingChange(even, even + gap - 50, WarSummaryState.DEFEAT) < 0,
        "en deçà de " + gap + " points d'écart, une défaite doit coûter");
    System.out.println("[war] bascule de défaite dérivée des constantes : " + gap + " points d'écart");

    // BYE = gain fixe des données.
    check(ServerWar.ratingChange(even, 0, WarSummaryState.BYE) == ServerWar.byeRatingGain(),
        "un BYE doit rapporter exactement BYE_RATING_GAIN");

    // Bornage : le MMR ne descend jamais sous le plancher de la table de ligues (UNRANKED).
    int floorMMR = ServerWar.leagueMinMMR(WarLeague.UNRANKED);
    check(ServerWar.applyRatingChange(ServerWar.startingMMR(), -10_000) == floorMMR,
        "le MMR doit être borné au plancher de war_league_brackets.tab (" + floorMMR + ")");
    check(ServerWar.applyRatingChange(even, evenWin) == even + evenWin, "l'application doit rester exacte");

    // ELO_LOSS_BUFFER_THRESHOLD est LUE mais délibérément NON APPLIQUÉE (sémantique inconnue) :
    // on vérifie qu'elle n'influence PAS le résultat, pour que le jour où elle sera élucidée le test bouge.
    check(ServerWar.lossBufferThreshold() > 0, "ELO_LOSS_BUFFER_THRESHOLD doit être lue");
    int belowBuffer = ServerWar.lossBufferThreshold() - 1;
    check(ServerWar.ratingChange(belowBuffer, belowBuffer, WarSummaryState.DEFEAT) == evenLoss,
        "tant que sa sémantique est inconnue, ELO_LOSS_BUFFER_THRESHOLD ne doit rien changer");

    System.out.println("[war] MMR : victoire " + lo + " vs " + hi + " → +" + winVsStronger
        + " · victoire " + hi + " vs " + lo + " → +" + winVsWeaker
        + " · défaite " + hi + " vs " + lo + " → " + lossVsWeaker
        + " · BYE → +" + ServerWar.byeRatingGain());

    System.out.println("WAR SEASON TEST OK");
  }
}
