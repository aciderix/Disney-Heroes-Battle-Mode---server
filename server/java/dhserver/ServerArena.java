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


  /** Chances de combat quotidiennes par ligue — <b>5</b> (relevé EN JEU « Fights Left 5/5 »). À terme = cap de la
   *  {@code ResourceType} clé ({@code ArenaHelper.getKeyResource}) ; valeur observée pour l'instant. */
  public static final int MAX_FIGHTS = 5;

  /**
   * ARÈNE #41 — construit l'{@link ArenaInfo} depuis un CLASSEMENT PERSISTANT ({@link ServerArenaLadder}) : les
   * rangs (ordre de la liste), points et fights viennent de l'état sauvegardé, pas d'une régénération. Les lineups
   * des bots se régénèrent déterministiquement (graine = id du bot) ; ta row = ta défense RÉELLE + identité live.
   */
  public static ArenaInfo buildArenaInfo(User user, UserInfo userInfo, ArenaType type, ServerArenaLadder ladder) {
    return buildArenaInfo(user, userInfo, type, ladder, null);
  }

  /** Comme ci-dessus mais avec la source d'adversaires réels : les rows des vrais joueurs sont bâties depuis LEUR
   *  défense RÉELLE (via {@code src.loadDefender}), pas en synthétique. */
  public static ArenaInfo buildArenaInfo(User user, UserInfo userInfo, ArenaType type, ServerArenaLadder ladder,
                                         OpponentSource src) {
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();  // heure de JEU (suit le décalage d'ère), pas l'horloge murale
    ArenaInfo info = new ArenaInfo();
    info.type = type;
    info.season = buildSeason(type, now);
    info.yourLeague = buildLeagueFromLadder(user, userInfo, type, ladder, src);
    info.topLeague = info.yourLeague;   // une seule ligue COPPER (placement opérateur §3)
    return info;
  }

  /** Variante SANS état persistant (headless/tests) : génère un classement transitoire déterministe et bâtit. */
  public static ArenaInfo buildArenaInfo(User user, UserInfo userInfo, ArenaType type) {
    return buildArenaInfo(user, userInfo, type, generateLadder(user, userInfo, type));
  }

  /**
   * ARÈNE (vrai PvP) — source d'adversaires RÉELS : les autres comptes du shard qui ont posé une défense.
   * Fournie par la couche persistance ({@link StoreOpponentSource} sur {@link UserStore}). {@code null} en headless →
   * ladder 100 % bots (repli). C'est la façon dont le serveur affronte de VRAIS joueurs : leur défense est lue par
   * le MÊME chemin que la sienne ({@code playerLineups}), aucune régénération synthétique.
   */
  public interface OpponentSource {
    /** Autres vrais joueurs du shard AVEC une défense posée pour {@code type} (entrées bot=false : id/nom/TL). */
    List<ServerArenaLadder.Entry> realOpponents(int shardID, long excludeID, ArenaType type);
    /** {@link User} (jeu) d'un vrai joueur — pour lire sa défense RÉELLE —, ou {@code null} si absent. */
    User loadDefender(long id, int shardID);
  }

  /** Force d'une entrée pour l'ordre du classement (proxy) : bot → niveau du bot, vrai joueur → team level. */
  private static int strength(ServerArenaLadder.Entry e) { return e.bot ? e.botLevel : e.teamLevel; }

  /** Variante SANS source d'adversaires réels (headless/tests) : 100 % bots + toi. */
  public static ServerArenaLadder generateLadder(User user, UserInfo userInfo, ArenaType type) {
    return generateLadder(user, userInfo, type, null);
  }

  /**
   * ARÈNE #41/vrai PvP — GÉNÈRE un classement initial pour {@code (shard, type)} : d'abord les VRAIS joueurs du
   * shard ayant une défense (via {@code src}), puis complément de bots calibrés sur ton roster jusqu'à
   * {@link #SYNTHETIC_OPPONENTS} adversaires, ordonnés par force décroissante, puis TOI en dernier (provisoire).
   * Points 0 et {@link #MAX_FIGHTS} chances pour tous au départ. {@code src==null} → 100 % bots (repli headless).
   */
  public static ServerArenaLadder generateLadder(User user, UserInfo userInfo, ArenaType type, OpponentSource src) {
    ServerArenaLadder ladder = new ServerArenaLadder();
    int userTL = userInfo.basicInfo != null ? userInfo.basicInfo.teamLevel : 1;
    long myID = userInfo.basicInfo != null ? userInfo.basicInfo.iD : 1L;
    List<ServerArenaLadder.Entry> opponents = new ArrayList<>();
    if (src != null) {
      for (ServerArenaLadder.Entry e : src.realOpponents(user.getShardID(), myID, type)) {
        e.bot = false;
        if (e.remainingFightChances <= 0) e.remainingFightChances = MAX_FIGHTS;
        opponents.add(e);                                          // VRAIS joueurs d'abord
      }
    }
    Rarity oppRarity = calibrateRarity(user);
    int oppBaseLevel = calibrateLevel(user);
    int need = Math.max(0, SYNTHETIC_OPPONENTS - opponents.size());  // complément bots si trop peu de vrais joueurs
    for (int i = 0; i < need; i++) {
      ServerArenaLadder.Entry e = new ServerArenaLadder.Entry();
      e.id = ServerArenaLadder.BOT_ID_BASE + i;
      e.name = "Rival " + (i + 1);
      e.teamLevel = userTL;
      e.bot = true;
      e.botRarityOrdinal = oppRarity.ordinal();
      e.botLevel = Math.max(1, oppBaseLevel + (i - need / 2));
      e.remainingFightChances = MAX_FIGHTS;
      opponents.add(e);
    }
    opponents.sort((a, b) -> Integer.compare(strength(b), strength(a)));   // plus fort en haut
    ladder.entries().addAll(opponents);
    ServerArenaLadder.Entry me = new ServerArenaLadder.Entry();
    me.id = myID;
    me.name = userInfo.basicInfo != null ? userInfo.basicInfo.name : "You";
    me.teamLevel = userTL;
    me.remainingFightChances = MAX_FIGHTS;
    ladder.entries().add(me);                                       // toi en DERNIER (provisoire)
    ladder.lastFightReset = com.perblue.heroes.util.TimeUtil.serverTimeNow();  // heure de JEU ; prochain reset = prochain 21h
    return ladder;
  }

  /**
   * ARÈNE — RÉGÉN des combats = RESET QUOTIDIEN (fidèle : le jeu réinitialise l'arène chaque jour à 21h serveur,
   * {@code ArenaHelper.getNextDailyUpdateTime} / {@code resetArenaChances} ; ce n'est PAS un timer par combat). Si un
   * passage de reset quotidien a eu lieu depuis {@code ladder.lastFightReset}, on remet TOUTES les entrées à
   * {@link #MAX_FIGHTS} et on note l'instant. Appelé à chaque ouverture/attaque ; l'appelant persiste le ladder.
   * @return true si un reset a été appliqué (le ladder a changé → à persister).
   */
  public static boolean maybeDailyReset(ServerArenaLadder ladder, ArenaType type, long now) {
    if (ladder == null) return false;
    long due;
    try { due = ArenaHelper.getNextDailyUpdateTime(type, ladder.lastFightReset); }
    catch (Throwable t) { due = ladder.lastFightReset + 24L * 60 * 60 * 1000; }   // repli : +24h
    if (now < due) return false;                                   // pas encore franchi le reset quotidien
    for (ServerArenaLadder.Entry e : ladder.entries()) e.remainingFightChances = MAX_FIGHTS;
    ladder.lastFightReset = now;
    System.out.println("[arena] reset quotidien des combats → " + MAX_FIGHTS + "/joueur ("
        + ladder.entries().size() + " entrées)");
    return true;
  }

  /**
   * ARÈNE (vrai PvP) — fusionne dans un ladder DÉJÀ chargé les VRAIS joueurs du shard qui ont posé une défense
   * depuis mais ne sont pas encore classés (ils ont rejoint/posé leur défense après la génération). Ajoutés en bas
   * (non classés, ils grimperont en jouant). Garde le classement à jour sans le régénérer. Renvoie le nb ajouté.
   */
  public static int mergeRealOpponents(ServerArenaLadder ladder, OpponentSource src, int shardID,
                                       long myID, ArenaType type) {
    if (src == null) return 0;
    int added = 0;
    for (ServerArenaLadder.Entry e : src.realOpponents(shardID, myID, type)) {
      if (ladder.indexOf(e.id) >= 0) continue;                     // déjà classé
      e.bot = false;
      if (e.remainingFightChances <= 0) e.remainingFightChances = MAX_FIGHTS;
      int meIdx = ladder.indexOf(myID);                            // inséré JUSTE au-dessus de toi si tu es en bas
      if (meIdx >= 0) ladder.entries().add(meIdx, e); else ladder.entries().add(e);
      added++;
    }
    return added;
  }

  /** Bâtit la ligue à partir des entrées ORDONNÉES du classement (rang = index). */
  private static ArenaLeagueInfo buildLeagueFromLadder(User user, UserInfo userInfo, ArenaType type,
                                                       ServerArenaLadder ladder, OpponentSource src) {
    ArenaLeagueInfo lg = new ArenaLeagueInfo();
    lg.type = type;
    lg.tier = ArenaTier.COPPER;
    lg.division = 1;
    lg.seasonal = false;
    lg.singlePromotionPositions = 5;
    lg.doublePromotionPositions = 0;
    lg.players = new ArrayList<ArenaRow>();
    int numTeams = defenseTeamCount(type);
    int shardID = user.getShardID();
    long myID = userInfo.basicInfo != null ? userInfo.basicInfo.iD : 1L;
    for (ServerArenaLadder.Entry e : ladder.entries()) {
      ArenaRow r;
      if (!e.bot && e.id == myID) {
        List<LineupSummary> yourTeams = playerLineups(user, type, numTeams);
        List<HeroSummary> yourLineup = firstTeamSummaries(yourTeams);
        long yourPower = totalPower(yourTeams);
        r = row(userInfo.basicInfo, yourLineup, yourTeams, yourPower, /*isYou*/ e.points <= 0);
      } else if (!e.bot) {
        r = realPlayerRow(e, src, type, numTeams, shardID);        // VRAI adversaire → sa défense RÉELLE
      } else {
        r = botRow(e, shardID, numTeams);                          // bot → défense synthétique déterministe
      }
      applyRowExtra(r, e);
      lg.players.add(r);
    }
    int idx = ladder.indexOf(myID);
    lg.yourRank = idx < 0 ? ladder.entries().size() : idx + 1;
    return lg;
  }

  /** Row d'un VRAI adversaire, bâtie depuis SA défense RÉELLE (lue par le même chemin que la tienne). heroIds
   *  décalés dans une plage haute (unicité dans la ligue). Repli bot si le compte est introuvable. */
  private static ArenaRow realPlayerRow(ServerArenaLadder.Entry e, OpponentSource src, ArenaType type,
                                        int numTeams, int shardID) {
    User d = src != null ? src.loadDefender(e.id, shardID) : null;
    if (d == null) return botRow(e, shardID, numTeams);            // compte disparu → repli synthétique
    BasicUserInfo who = new BasicUserInfo();
    who.iD = e.id; who.name = e.name; who.teamLevel = e.teamLevel;
    who.creationTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    who.userLastActive = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    who.previousName = "";
    List<LineupSummary> teams = playerLineups(d, type, numTeams);  // SA défense posée (ou roster à défaut)
    offsetHeroIds(teams, 1_000_000 + (int) (e.id % 9000L) * 100);  // unicité heroId dans la ligue
    List<HeroSummary> first = firstTeamSummaries(teams);
    return row(who, first, teams, totalPower(teams), /*isYou*/ false);
  }

  /** Réattribue des heroId uniques (base + compteur) à toutes les équipes — évite les collisions d'ID entre les
   *  rows de plusieurs vrais joueurs/toi dans la même ligue (le client indexe les widgets par heroId). */
  private static void offsetHeroIds(List<LineupSummary> teams, int base) {
    int c = 0;
    for (LineupSummary ls : teams) {
      if (ls.lineup == null) continue;
      for (Object o : ls.lineup) {
        try { ((ExtendedHeroSummary) o).summary.heroId = base + (c++); } catch (Throwable ignore) {}
      }
    }
  }

  /** Une row d'adversaire (bot) reconstruite depuis son entrée de classement (lineups déterministes via son id). */
  private static ArenaRow botRow(ServerArenaLadder.Entry e, int shardID, int numTeams) {
    BasicUserInfo who = new BasicUserInfo();
    who.iD = e.id;
    who.name = e.name;
    who.teamLevel = e.teamLevel;
    who.creationTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    who.userLastActive = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    who.previousName = "";
    Rarity rarity = rarityFromOrdinal(e.botRarityOrdinal);
    return syntheticOpponent(rarity, e.botLevel, who, shardID, numTeams, new java.util.Random(e.id));
  }

  /** Reporte points/fights/bestScore de l'entrée persistée dans l'extra de la row (ce que le client affiche). */
  private static void applyRowExtra(ArenaRow r, ServerArenaLadder.Entry e) {
    ArenaRowExtra x = new ArenaRowExtra();
    x.points = e.points;
    x.pointsTiebreaker = e.pointsTiebreaker;
    x.remainingFightChances = e.remainingFightChances;
    x.bestScore = e.bestScore;
    r.challengerExtra = x;
  }

  private static Rarity rarityFromOrdinal(int ord) {
    Rarity[] vals = Rarity.values();
    if (ord < 0 || ord >= vals.length) return Rarity.WHITE;
    return vals[ord];
  }

  /** User synthétique (bot) lié, à TL élevé, dans le shard du joueur (contenu déjà chargé). */
  private static User botUser(int shardID, long id) {
    UserInfo ui = new UserInfo();
    ui.shardID = shardID;
    ui.basicInfo = new BasicUserInfo();
    ui.basicInfo.teamLevel = 65;
    User bot = ClientNetworkStateConverter.getUser(ui, new UserExtra(), "bot");
    ServerContext.bind(bot,
        ClientNetworkStateConverter.getIndividualUser(new IndividualUserExtra(), id, 0, "bot"));
    return bot;
  }

  /**
   * ARÈNE #44 — les héros du défenseur en {@code HeroData} COMPLET (pour {@code StartArenaAttackResponse.heroes},
   * FIGHT_PIT) : le client rejoue le combat avec. Bot → régénéré DÉTERMINISTIQUEMENT (même graine = son id → même
   * équipe que celle affichée dans la liste). 5 héros.
   */
  public static List<HeroData> defenderHeroData(ServerArenaLadder.Entry e, int shardID, OpponentSource src) {
    if (!e.bot && src != null) {                                   // VRAI défenseur → SES héros de défense réels
      User d = src.loadDefender(e.id, shardID);
      if (d != null) return realDefenderHeroData(d);
    }
    return defenderHeroData(e, shardID);                            // bot → synthétique déterministe
  }

  /** Les héros de la défense FIGHT_PIT RÉELLE d'un vrai joueur en {@link HeroData} (le client rejoue le combat). */
  private static List<HeroData> realDefenderHeroData(User d) {
    List<HeroData> out = new ArrayList<>();
    try {
      HeroLineup hl = d.getHeroLineup(HeroLineupType.FIGHT_PIT_DEFENSE, 0L);
      if (hl != null && hl.heroes != null) {
        for (Object o : hl.heroes) {
          UnitData ud = (UnitData) d.getHero((UnitType) o);
          if (ud != null) out.add(ClientNetworkStateConverter.getHeroData(ud));
        }
      }
    } catch (Throwable t) { /* jamais fatal */ }
    return out;
  }

  public static List<HeroData> defenderHeroData(ServerArenaLadder.Entry e, int shardID) {
    List<HeroData> out = new ArrayList<>();
    try {
      User bot = botUser(shardID, e.id);
      Rarity rarity = rarityFromOrdinal(e.botRarityOrdinal);
      List<String> pool = new ArrayList<>(java.util.Arrays.asList(HERO_POOL));
      java.util.Collections.shuffle(pool, new java.util.Random(e.id));
      for (String n : pool) {
        if (out.size() >= 5) break;
        try {
          UnitType t = UnitType.valueOf(n);
          if (bot.getHero(t) == null) bot.createAndAddHero(t, rarity, e.botLevel, 1, new String[]{"bot"});
          out.add(ClientNetworkStateConverter.getHeroData((UnitData) bot.getHero(t)));
        } catch (Throwable perHero) { /* héros refusé → suivant */ }
      }
    } catch (Throwable t) { /* jamais fatal */ }
    return out;
  }

  /** ARÈNE #44 — les {@code numTeams} équipes de défense du défenseur en {@code LineupSummary} (COLISEUM :
   *  {@code StartColiseumAttackResponse.defendingLineups}). Réutilise la génération synthétique déterministe. */
  /** COLISEUM : équipes de défense du défenseur — RÉELLES si vrai joueur (via {@code src}), sinon synthétiques (bot). */
  @SuppressWarnings("unchecked")
  public static List defenderLineups(ServerArenaLadder.Entry e, int shardID, int numTeams, OpponentSource src) {
    if (!e.bot && src != null) {
      User d = src.loadDefender(e.id, shardID);
      if (d != null) {
        List<LineupSummary> teams = playerLineups(d, ArenaType.COLISEUM, numTeams);
        offsetHeroIds(teams, 1_000_000 + (int) (e.id % 9000L) * 100);
        return teams;
      }
    }
    return defenderLineups(e, shardID, numTeams);                   // bot → synthétique
  }

  @SuppressWarnings("unchecked")
  public static List defenderLineups(ServerArenaLadder.Entry e, int shardID, int numTeams) {
    BasicUserInfo who = new BasicUserInfo();
    who.iD = e.id; who.name = e.name; who.teamLevel = e.teamLevel;
    who.creationTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    who.userLastActive = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    who.previousName = "";
    ArenaRow r = syntheticOpponent(rarityFromOrdinal(e.botRarityOrdinal), e.botLevel, who, shardID,
        numTeams, new java.util.Random(e.id));
    return r.lineups;
  }

  /** Saison courante, calculée fidèlement via {@link ArenaHelper} + {@code arena_constants.tab}. */
  private static ArenaSeasonInfo buildSeason(ArenaType type, long now) {
    ArenaSeasonInfo s = new ArenaSeasonInfo();
    long seasonStart = safeSeasonStart(type, now);
    s.id = seasonStart;                                   // identifiant stable par saison
    // Numéro de jour dans la saison (SEASON_WEEKS×7 jours), 1-based.
    long day = (now - seasonStart) / (24L * 60 * 60 * 1000);
    s.dayNumber = (int) Math.max(0, day);
    s.endOfDay = safeNextDailyUpdate(type, now);          // prochain reset quotidien (= boundary de maybeDailyReset)
    s.endOfWeek = safeNextWeeklyReset(type, now);         // prochain reset hebdo (mercredi 21h)
    s.ended = false;
    s.featuredHeroesA = true;
    s.fightScoringBonusTypes = new ArrayList<>();
    s.lastSeasonChampion = null;
    s.lastWeekResults = null;
    return s;
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

  /** Types de lineup de DÉFENSE pour le mode (modèle du jeu, PRINCIPLES §4) : COLISEUM = 3 lignes, FIGHT_PIT = 1. */
  private static HeroLineupType[] defenseLineupTypes(ArenaType type) {
    if (type == ArenaType.COLISEUM)
      return new HeroLineupType[]{HeroLineupType.COLISEUM_DEFENSE_1,
          HeroLineupType.COLISEUM_DEFENSE_2, HeroLineupType.COLISEUM_DEFENSE_3};
    return new HeroLineupType[]{HeroLineupType.FIGHT_PIT_DEFENSE};
  }

  /** Une équipe de défense RÉELLE posée par le joueur (persistée via {@code HeroLineupUpdate}, #41), ou null si
   *  non posée/vide. Les héros viennent du roster du joueur (getHero → getHeroSummary/HP_MAX). */
  private static LineupSummary readDefenseTeam(User user, HeroLineupType dt) {
    try {
      HeroLineup hl = user.getHeroLineup(dt, 0L);
      if (hl == null || hl.heroes == null || hl.heroes.isEmpty()) return null;
      List<ExtendedHeroSummary> team = new ArrayList<>(); long power = 0;
      for (Object o : hl.heroes) {
        UnitData ud = (UnitData) user.getHero((UnitType) o);
        if (ud == null) continue;                          // héros posé mais plus au roster → ignoré
        team.add(extended(ud, ClientNetworkStateConverter.getHeroSummary(ud)));
        try { power += Math.max(0L, ud.getPower(0)); } catch (Throwable t) {}
      }
      return team.isEmpty() ? null : lineupSummary(team, power);
    } catch (Throwable t) { return null; }
  }

  /** Équipes de défense du joueur : d'abord la DÉFENSE RÉELLE qu'il a posée (persistée, modèle du jeu) ; à défaut,
   *  son roster découpé en {@code numTeams} équipes (placeholder tant qu'aucune défense n'est posée). */
  private static List<LineupSummary> playerLineups(User user, ArenaType type, int numTeams) {
    List<LineupSummary> teams = new ArrayList<>();
    for (HeroLineupType dt : defenseLineupTypes(type)) {
      LineupSummary ls = readDefenseTeam(user, dt);
      if (ls != null) teams.add(ls);
    }
    if (!teams.isEmpty()) return teams;                    // défense RÉELLE posée → on l'utilise
    return playerLineupsFromRoster(user, numTeams);        // sinon placeholder roster
  }

  /** Placeholder : roster du joueur découpé en {@code numTeams} équipes de 5 (héros dans l'ordre du roster). */
  private static List<LineupSummary> playerLineupsFromRoster(User user, int numTeams) {
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


  private static long safeSeasonStart(ArenaType type, long now) {
    try { return ArenaHelper.getCurrentSeasonStartTime(type, now); }
    catch (Throwable t) { return now; }
  }
  private static long safeNextWeeklyReset(ArenaType type, long now) {
    try { return ArenaHelper.getNextWeeklyResetTime(type, now); }
    catch (Throwable t) { return now + 7L * 24 * 60 * 60 * 1000; }
  }
  /** Prochain reset quotidien via la logique du jeu (repli sur le calcul local 21h). */
  private static long safeNextDailyUpdate(ArenaType type, long now) {
    try { return ArenaHelper.getNextDailyUpdateTime(type, now); }
    catch (Throwable t) { return nextDailyReset(now); }
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
