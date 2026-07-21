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

  /** Nombre d'adversaires de démonstration en attendant le pool réel + génération synthétique (tâche #43). */
  private static final int DEMO_OPPONENTS = 9;

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

    // TA row (identité réelle + ta meilleure équipe en défense).
    List<HeroSummary> yourLineup = topLineup(user);
    long yourPower = teamPower(user);
    ArenaRow you = row(userInfo.basicInfo, yourLineup, yourPower, /*isYou*/ true);
    lg.players.add(you);

    // Adversaires de démonstration (palier 1) — remplacés par le pool réel + synthétique en tâche #43.
    for (int i = 0; i < DEMO_OPPONENTS; i++) {
      BasicUserInfo opp = syntheticOpponentInfo(userInfo, i);
      lg.players.add(row(opp, yourLineup, yourPower, /*isYou*/ false));
    }
    // Ton rang = dernier (nouveau/provisoire), classé au-dessous des adversaires établis.
    lg.yourRank = lg.players.size();
    // Réordonner : les adversaires en tête (rangs 1..N), toi en dernier.
    // (Le client lit yourRank ; l'ordre de la liste = ordre d'affichage.)
    Object yourRow = lg.players.remove(0);
    ((List<ArenaRow>) lg.players).add((ArenaRow) yourRow);
    return lg;
  }

  private static ArenaRow row(BasicUserInfo who, List<HeroSummary> lineup, long power, boolean isYou) {
    ArenaRow r = new ArenaRow();
    PlayerRow pr = new PlayerRow();
    pr.info = who;
    pr.teamPower = power;
    pr.totalPower = power;
    r.playerRow = pr;
    r.lineup = new ArrayList<>(lineup);
    r.lineups = new ArrayList<>();
    r.heroPower = power;
    r.isNew = isYou;
    r.provisional = isYou;
    r.lastAttackTime = 0;
    r.rankDelta = 0;
    r.combatModifiers = new java.util.HashMap<>();
    return r;
  }

  /** Jusqu'à 5 héros du joueur en HeroSummary (équipe de défense par défaut = tes plus forts). */
  private static List<HeroSummary> topLineup(User user) {
    List<HeroSummary> out = new ArrayList<>();
    try {
      for (Object o : user.getHeroes()) {
        UnitData ud = (UnitData) o;
        out.add(ClientNetworkStateConverter.getHeroSummary(ud));
        if (out.size() >= 5) break;
      }
    } catch (Throwable t) { /* roster vide/headless → lineup vide, valide */ }
    return out;
  }

  /** Puissance d'équipe = somme de la puissance (logique du jeu) des 5 premiers héros. */
  private static long teamPower(User user) {
    long p = 0; int n = 0;
    try {
      for (Object o : user.getHeroes()) {
        UnitData ud = (UnitData) o;
        try { p += Math.max(0L, ud.getPower(0)); } catch (Throwable t) {}
        if (++n >= 5) break;
      }
    } catch (Throwable t) {}
    return p;
  }

  /** Adversaire SYNTHÉTIQUE (palier 1 : identité factice ; l'équipe réelle synthétique = tâche #43). */
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
