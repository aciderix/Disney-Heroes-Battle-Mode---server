package dhserver;

import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.logic.ArenaHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * ARÈNE PvP — construction serveur-autoritative de l'{@link ArenaInfo} (réponse à {@code GetArenaInfo}).
 *
 * <p>Le <b>builder d'ArenaInfo n'existe PAS dans le jar client</b> (le client ne fait que LIRE l'arène ; c'est le
 * backend PerBlue qui la construisait). On le reconstruit <b>fidèlement</b> à partir des données+règles du jeu
 * ({@code arena_*.tab} via {@link ArenaHelper} pour la saison/les divisions ; {@code ArenaTier}/{@code ArenaType})
 * — cf. PRINCIPLES §3/§4. Le <b>placement</b> initial (nouveau joueur → tier COPPER, division 1, provisoire) est
 * une décision d'OPÉRATEUR (§3), comme premium-for-all du battle pass.
 *
 * <p><b>Palier 1</b> (ici) : produire un ArenaInfo STRUCTURELLEMENT VALIDE (saison + ta ligue avec TA row +
 * quelques adversaires) → l'écran passe de « LOADING… » à l'arène rendue. Les <b>adversaires</b> (pool réel de
 * joueurs du shard + complément synthétique) et le <b>combat</b> ({@code ArenaAttack}) suivent (tâches #43/#44).
 */
public final class ServerArena {

  private ServerArena() {}

  /** Taille de ligue visée (COPPER) — {@code LEAGUE_SIZE} vaut 100 en prod ; on peuple avec le pool réel puis on
   *  complète en synthétique. En solo on en met une poignée pour une ligue crédible sans surcharger. */
  private static final int SYNTHETIC_OPPONENTS = 9;

  /** Pool CURÉ de héros qui se créent+résument sans erreur en 12.1.0 (vérifié : 27/27 headless, cf. HeroPoolProbe).
   *  Sert à générer des équipes de défense synthétiques VARIÉES via la logique du jeu (createAndAddHero). */
  private static final String[] HERO_POOL = {
    "RALPH","VANELLOPE","ELASTIGIRL","FROZONE","MERIDA","MAUI","MOANA","HERCULES","GENIE","JASMINE",
    "STITCH","WOODY","SULLEY","BAYMAX","HIRO","JACK_SPARROW","SCAR","URSULA","MALEFICENT","GASTON",
    "YAX","DASH","MR_INCREDIBLE","VIOLET","OLAF","ELSA","ANNA"
  };


  /** Construit l'{@link ArenaInfo} du joueur pour un {@code type} d'arène (FIGHT_PIT/COLISEUM). */
  public static ArenaInfo buildArenaInfo(User user, UserInfo userInfo, ArenaType type) {
    long now = System.currentTimeMillis();

    ArenaInfo info = new ArenaInfo();
    info.type = type;
    info.season = buildSeason(type, now);
    info.yourLeague = buildLeague(user, userInfo, type, now);
    info.topLeague = info.yourLeague;   // palier 1 : la ligue du haut = ta ligue (une seule ligue COPPER)
    return info;
  }

  /** Saison courante, calculée fidèlement via {@link ArenaHelper} + {@code arena_constants.tab}. */
  private static ArenaSeasonInfo buildSeason(ArenaType type, long now) {
    ArenaSeasonInfo s = new ArenaSeasonInfo();
    long seasonStart = safeSeasonStart(type, now);
    s.id = seasonStart;                                   // identifiant stable par saison
    // Numéro de jour dans la saison (SEASON_WEEKS×7 jours), 1-based.
    long day = (now - seasonStart) / (24L * 60 * 60 * 1000);
    s.dayNumber = (int) Math.max(0, day);
    s.endOfDay = nextDailyReset(now);                     // prochain reset quotidien (DAILY_UPDATE_HOUR=21)
    s.endOfWeek = safeNextWeeklyReset(type, now);         // prochain reset hebdo (mercredi 21h)
    s.ended = false;
    s.featuredHeroesA = true;
    s.fightScoringBonusTypes = new ArrayList<>();
    s.lastSeasonChampion = null;
    s.lastWeekResults = null;
    return s;
  }

  /** Ta ligue COPPER division 1 : ta row + des adversaires. */
  private static ArenaLeagueInfo buildLeague(User user, UserInfo userInfo, ArenaType type, long now) {
    ArenaLeagueInfo lg = new ArenaLeagueInfo();
    lg.type = type;
    lg.tier = ArenaTier.COPPER;                           // placement initial (opérateur §3)
    lg.division = 1;
    lg.seasonal = false;
    lg.singlePromotionPositions = 5;                      // PROMOTION_POSITIONS (arena_constants.tab)
    lg.doublePromotionPositions = 0;
    lg.players = new ArrayList<ArenaRow>();

    // Nombre d'ÉQUIPES de défense par row selon le mode (source du jeu, PRINCIPLES §4) : COLISEUM = 3 lignes
    // (COLISEUM_DEFENSE_1/2/3, cf. ArenaHelper.COLISEUM_DEFENSE_LINEUPS) ; FIGHT_PIT = 1. Le rendu COLISEUM
    // (PVPRow.createColiUnitTable) indexe ArenaRow.lineups → il DOIT être non vide (sinon get(-1) plante).
    int numTeams = defenseTeamCount(type);

    // TA row (identité réelle + tes équipes de défense, dérivées de ton roster tant que la défense d'arène
    // n'est pas persistée — tâche #41).
    List<LineupSummary> yourTeams = playerLineups(user, numTeams);
    List<HeroSummary> yourLineup = firstTeamSummaries(yourTeams);
    long yourPower = totalPower(yourTeams);
    ArenaRow you = row(userInfo.basicInfo, yourLineup, yourTeams, yourPower, /*isYou*/ true);

    // ADVERSAIRES : en PROD PvP = pool des autres joueurs du shard dans cette ligue (défenses persistées, tâche
    // #41) ; ici (solo) on COMPLÈTE en SYNTHÉTIQUE — équipes générées via la logique du jeu (createAndAddHero →
    // getHeroSummary), variées (pool curé) et calibrées par tier. Graine dérivée de ton ID → stable entre refresh.
    List<ArenaRow> opponents = new ArrayList<>();
    long seed = (userInfo.basicInfo != null ? userInfo.basicInfo.iD : 1L) * 2654435761L
        + lg.tier.ordinal() * 1009L + lg.division;
    java.util.Random rng = new java.util.Random(seed);
    // CALIBRAGE : adversaires comparables à TON roster (rareté représentative + niveau médian) → matchs équitables
    // en solo (un nouveau joueur affronte des équipes de son niveau, pas des monstres). En prod multi-joueurs, ce
    // rôle revient au pool réel (joueurs de rang voisin).
    Rarity oppRarity = calibrateRarity(user);
    int oppBaseLevel = calibrateLevel(user);
    int shardID = user.getShardID();                                        // bots dans TON shard (contenu déjà chargé)
    for (int i = 0; i < SYNTHETIC_OPPONENTS; i++) {
      int lvl = Math.max(1, oppBaseLevel + (i - SYNTHETIC_OPPONENTS / 2));   // légère échelle autour de ton niveau
      opponents.add(syntheticOpponent(oppRarity, lvl, syntheticOpponentInfo(userInfo, i), shardID, numTeams, rng));
    }
    // Classement : adversaires par PUISSANCE décroissante (rang 1 = plus fort), toi en DERNIER (provisoire).
    opponents.sort((x, y) -> Long.compare(y.heroPower, x.heroPower));
    lg.players.addAll(opponents);
    lg.players.add(you);
    lg.yourRank = lg.players.size();
    return lg;
  }

  private static ArenaRow row(BasicUserInfo who, List<HeroSummary> lineup, List<LineupSummary> lineups,
                             long power, boolean isYou) {
    ArenaRow r = new ArenaRow();
    PlayerRow pr = new PlayerRow();
    pr.info = who;
    pr.teamPower = power;
    pr.totalPower = power;
    r.playerRow = pr;
    r.lineup = new ArrayList<>(lineup);                    // équipe unique (FIGHT_PIT lit ce champ)
    r.lineups = new ArrayList<>(lineups);                  // équipes multiples (COLISEUM lit ce champ — non vide requis)
    r.heroPower = power;
    r.isNew = isYou;
    r.provisional = isYou;
    r.lastAttackTime = 0;
    r.rankDelta = 0;
    r.combatModifiers = new java.util.HashMap<>();
    return r;
  }

  /** Nombre d'équipes de défense pour le mode : COLISEUM = {@code ArenaHelper.COLISEUM_DEFENSE_LINEUPS.size()}
   *  (3 lignes), FIGHT_PIT = 1. Source = données du jeu (PRINCIPLES §4). */
  private static int defenseTeamCount(ArenaType type) {
    if (type == ArenaType.COLISEUM) {
      try { return Math.max(1, ArenaHelper.COLISEUM_DEFENSE_LINEUPS.size()); }
      catch (Throwable t) { return 3; }
    }
    return 1;
  }

  /** Enveloppe une équipe d'{@link ExtendedHeroSummary} dans un {@link LineupSummary} (format wire d'une défense —
   *  {@code lineup} attend des {@code ExtendedHeroSummary}, pas des {@code HeroSummary}). */
  private static LineupSummary lineupSummary(List<ExtendedHeroSummary> team, long power) {
    LineupSummary ls = new LineupSummary();
    ls.lineup = new ArrayList<>(team);
    ls.power = power;
    ls.combatModifiers = new java.util.HashMap<>();
    return ls;
  }

  /** {@link ExtendedHeroSummary} d'un héros : résumé wire + PV max (StatType.HP_MAX, logique du jeu) + énergie 0
   *  (les héros démarrent le combat à 0 d'énergie). Aucune donnée inventée (PRINCIPLES §4). */
  private static ExtendedHeroSummary extended(UnitData ud, HeroSummary hs) {
    ExtendedHeroSummary e = new ExtendedHeroSummary();
    e.summary = hs;
    try { e.health = (long) ud.getCachedStat(com.perblue.heroes.game.data.item.StatType.HP_MAX, ud.getLevel()); }
    catch (Throwable t) { e.health = 0; }
    e.energy = 0;
    return e;
  }

  private static long totalPower(List<LineupSummary> teams) {
    long p = 0; for (LineupSummary ls : teams) p += Math.max(0L, ls.power); return p;
  }

  /** Les {@link HeroSummary} de la 1re équipe (pour {@code ArenaRow.lineup}, que FIGHT_PIT lit). */
  private static List<HeroSummary> firstTeamSummaries(List<LineupSummary> teams) {
    List<HeroSummary> out = new ArrayList<>();
    if (!teams.isEmpty() && teams.get(0).lineup != null)
      for (Object o : teams.get(0).lineup) out.add(((ExtendedHeroSummary) o).summary);
    return out;
  }

  /** Équipes de défense du joueur : son roster découpé en {@code numTeams} équipes de 5 (héros dans l'ordre du
   *  roster). Placeholder tant que la défense d'arène n'est pas persistée (#41) — les héros/puissances viennent
   *  du jeu (getHeroSummary/getPower/HP_MAX). Vide seulement si le joueur n'a aucun héros. */
  private static List<LineupSummary> playerLineups(User user, int numTeams) {
    List<ExtendedHeroSummary> all = new ArrayList<>();
    List<Long> powers = new ArrayList<>();
    try {
      for (Object o : user.getHeroes()) {
        UnitData ud = (UnitData) o;
        all.add(extended(ud, ClientNetworkStateConverter.getHeroSummary(ud)));
        long pw = 0; try { pw = Math.max(0L, ud.getPower(0)); } catch (Throwable t) {}
        powers.add(pw);
      }
    } catch (Throwable t) { /* roster vide/headless → aucune équipe, géré en amont */ }
    List<LineupSummary> teams = new ArrayList<>();
    int idx = 0;
    for (int t = 0; t < numTeams && idx < all.size(); t++) {
      List<ExtendedHeroSummary> team = new ArrayList<>(); long pw = 0;
      while (team.size() < 5 && idx < all.size()) { team.add(all.get(idx)); pw += powers.get(idx); idx++; }
      if (!team.isEmpty()) teams.add(lineupSummary(team, pw));
    }
    return teams;
  }

  /**
   * Construit un adversaire SYNTHÉTIQUE : {@code numTeams} équipes de défense de 5 héros VARIÉS (pool curé) créées
   * via la logique du jeu ({@code createAndAddHero} sur un User synthétique à TL élevé) à un niveau calibré par tier
   * (+variation par adversaire → échelle de puissance), converties en {@code HeroSummary}/{@code LineupSummary}.
   * Aucune donnée inventée : héros, puissance et résumés viennent du code+données du jeu (PRINCIPLES §3/§4).
   * COLISEUM lit {@code lineups} (3 équipes) ; FIGHT_PIT lit {@code lineup} (= 1re équipe).
   */
  private static ArenaRow syntheticOpponent(Rarity rarity, int level, BasicUserInfo who, int shardID,
                                            int numTeams, java.util.Random rng) {
    List<LineupSummary> teams = new ArrayList<>();
    long totalPower = 0;
    int heroCounter = 0;                                  // compteur de héros du bot → heroId uniques dans la ligue
    try {
      UserInfo ui = new UserInfo();
      ui.shardID = shardID;                              // MÊME shard que le joueur → contenu déjà chargé (pas de
                                                         // content.<shard>.tab manquant : setShardID = no-op de reload)
      ui.basicInfo = new BasicUserInfo();
      ui.basicInfo.teamLevel = 65;                       // TL élevé → createAndAddHero autorise le niveau visé
      User bot = ClientNetworkStateConverter.getUser(ui, new UserExtra(), "bot");
      var iu = ClientNetworkStateConverter.getIndividualUser(new IndividualUserExtra(), who.iD, 0, "bot");
      ServerContext.bind(bot, iu);

      // Héros DISTINCTS tirés du pool (mélangé), répartis en numTeams équipes de 5, créés via la logique du jeu.
      List<String> pool = new ArrayList<>(java.util.Arrays.asList(HERO_POOL));
      java.util.Collections.shuffle(pool, rng);
      int idx = 0;
      for (int tIdx = 0; tIdx < numTeams; tIdx++) {
        List<ExtendedHeroSummary> team = new ArrayList<>(); long teamPower = 0;
        while (team.size() < 5 && idx < pool.size()) {
          String n = pool.get(idx++);
          try {
            UnitType t = UnitType.valueOf(n);
            if (bot.getHero(t) == null) bot.createAndAddHero(t, rarity, level, 1, new String[]{"bot"});
            UnitData ud = (UnitData) bot.getHero(t);
            HeroSummary hs = ClientNetworkStateConverter.getHeroSummary(ud);
            // heroId UNIQUE dans la ligue : chaque bot est un User neuf (ids recommencent) → décalage par ID de bot.
            hs.heroId = (int) ((who.iD - 900000L) * 100L) + (++heroCounter);
            team.add(extended(ud, hs));
            teamPower += Math.max(0L, ud.getPower(0));
          } catch (Throwable perHero) { /* rareté/niveau refusés pour ce héros → suivant */ }
        }
        if (!team.isEmpty()) { teams.add(lineupSummary(team, teamPower)); totalPower += teamPower; }
      }
      // ROBUSTESSE : jamais d'adversaire vide — repli WHITE niv.1 (combo prouvé sûr) si rien n'a été créé.
      if (teams.isEmpty()) {
        List<ExtendedHeroSummary> team = new ArrayList<>(); long teamPower = 0;
        for (String n : new String[]{"RALPH","ELASTIGIRL","FROZONE","VANELLOPE","YAX"}) {
          try {
            UnitType t = UnitType.valueOf(n);
            if (bot.getHero(t) == null) bot.createAndAddHero(t, Rarity.WHITE, 1, 1, new String[]{"bot"});
            UnitData ud = (UnitData) bot.getHero(t);
            HeroSummary hs = ClientNetworkStateConverter.getHeroSummary(ud);
            hs.heroId = (int) ((who.iD - 900000L) * 100L) + (++heroCounter);
            team.add(extended(ud, hs));
            teamPower += Math.max(0L, ud.getPower(0));
          } catch (Throwable ignore) {}
        }
        if (!team.isEmpty()) { teams.add(lineupSummary(team, teamPower)); totalPower += teamPower; }
      }
    } catch (Throwable t) { /* génération d'un adversaire échouée → teams vides ; jamais fatal */ }
    List<HeroSummary> first = firstTeamSummaries(teams);
    return row(who, first, teams, totalPower, /*isYou*/ false);
  }

  /** Rareté représentative du roster du joueur (la plus fréquente) → adversaires du même acabit. WHITE par défaut. */
  private static Rarity calibrateRarity(User user) {
    java.util.Map<Rarity, Integer> count = new java.util.EnumMap<>(Rarity.class);
    try {
      for (Object o : user.getHeroes()) {
        Rarity r = ((UnitData) o).getRarity();
        if (r != null) count.merge(r, 1, Integer::sum);
      }
    } catch (Throwable t) {}
    Rarity best = Rarity.WHITE; int bestN = -1;
    for (java.util.Map.Entry<Rarity, Integer> e : count.entrySet())
      if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
    return best;
  }

  /** Niveau médian des héros du joueur (borne basse 1) → base de calibrage des adversaires. */
  private static int calibrateLevel(User user) {
    List<Integer> levels = new ArrayList<>();
    try { for (Object o : user.getHeroes()) levels.add(((UnitData) o).getLevel()); } catch (Throwable t) {}
    if (levels.isEmpty()) return 2;
    java.util.Collections.sort(levels);
    return Math.max(1, levels.get(levels.size() / 2));
  }

  /** Identité SYNTHÉTIQUE d'un adversaire (nom/ID « bot » hors IDs joueurs réels). */
  private static BasicUserInfo syntheticOpponentInfo(UserInfo self, int i) {
    BasicUserInfo b = new BasicUserInfo();
    b.iD = 900000L + i;                                   // IDs réservés « bots » (hors IDs joueurs réels)
    b.name = "Rival " + (i + 1);
    b.teamLevel = self.basicInfo != null ? self.basicInfo.teamLevel : 1;
    b.creationTime = System.currentTimeMillis();
    b.userLastActive = System.currentTimeMillis();
    b.previousName = "";
    return b;
  }

  private static long safeSeasonStart(ArenaType type, long now) {
    try { return ArenaHelper.getCurrentSeasonStartTime(type, now); }
    catch (Throwable t) { return now; }
  }
  private static long safeNextWeeklyReset(ArenaType type, long now) {
    try { return ArenaHelper.getNextWeeklyResetTime(type, now); }
    catch (Throwable t) { return now + 7L * 24 * 60 * 60 * 1000; }
  }

  /** Prochain reset quotidien à DAILY_UPDATE_HOUR (21h, heure serveur). */
  private static long nextDailyReset(long now) {
    java.util.Calendar c = java.util.Calendar.getInstance();
    c.setTimeInMillis(now);
    c.set(java.util.Calendar.HOUR_OF_DAY, 21);
    c.set(java.util.Calendar.MINUTE, 0);
    c.set(java.util.Calendar.SECOND, 0);
    c.set(java.util.Calendar.MILLISECOND, 0);
    if (c.getTimeInMillis() <= now) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
    return c.getTimeInMillis();
  }
}
