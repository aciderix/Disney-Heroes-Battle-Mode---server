package dhserver;

import com.perblue.heroes.network.messages.HeroTeam;
import com.perblue.heroes.network.messages.InvasionData;
import com.perblue.heroes.network.messages.InvasionDataWrapper;
import com.perblue.heroes.network.messages.InvasionInfo;
import com.perblue.heroes.network.messages.UnitType;

/**
 * INVASION (#69) — CALENDRIER et IDENTITÉ de l'invasion courante, calculés depuis les DONNÉES DU JEU.
 *
 * <p><b>Ce que le jar contient</b> (vérifié, pas supposé) : {@code invasion_constants.tab} porte tout le
 * calendrier — {@code START_DAY=MONDAY}, {@code START_TIME=12h}, {@code END_DAY=SATURDAY}, {@code END_TIME=12h},
 * {@code INVASION_BASE_DATE=2022-02-27T00:00Z}, {@code INVASION_BASE_ROTATION=23}. La logique métier
 * ({@code InvasionHelper}) et les objets ({@code ClientInvasion}, {@code ClientInvasionUser}) existent CÔTÉ
 * CLIENT : le serveur n'a donc qu'à produire un {@code InvasionInfo} correct, comme pour l'arène.
 *
 * <p><b>Pourquoi c'est au serveur de calculer</b> : {@code INVASION_BASE_DATE} et {@code INVASION_BASE_ROTATION}
 * sont déclarés dans les données mais ne sont référencés par AUCUNE classe cliente (scan du pool de constantes
 * de tout le jar) — exactement la signature de {@code MERCENARY_COST}. Ces constantes existent pour que le
 * SERVEUR dérive la rotation ; {@code InvasionData.rotationNumber} n'est d'ailleurs écrit que par le message.
 *
 * <p><b>Seule lecture structurelle</b> (documentée, non issue d'une table) : l'ÉQUIPE vedette tourne parmi
 * {@code RED/BLUE/YELLOW} au rythme de la rotation. Le jar donne le mapping héros→équipe
 * ({@code UnitStats.getTeam}, 94 RED / 99 BLUE / 93 YELLOW) et le champ {@code InvasionData.team}, mais aucune
 * table n'énonce « rotation N ⇒ équipe X ». Le cycle à 3 est le choix le plus simple cohérent avec les données ;
 * il est ISOLÉ dans {@link #teamForRotation(int)} pour être corrigé d'un seul endroit si une preuve apparaît.
 */
public final class ServerInvasion {

  private ServerInvasion() {}

  private static final long DAY = 86_400_000L;
  private static final long WEEK = 7L * DAY;

  /** Lit un champ de {@code InvasionStats$Constants} (package-private → getDeclaredField + setAccessible). */
  private static Object constant(String field) {
    try {
      Class<?> st = Class.forName("com.perblue.heroes.game.data.invasion.InvasionStats");
      java.lang.reflect.Field cs = st.getDeclaredField("CONSTANT_STATS");
      cs.setAccessible(true);
      Object holder = cs.get(null);
      Object stats = holder.getClass().getMethod("getStats").invoke(holder);
      java.lang.reflect.Field f = stats.getClass().getDeclaredField(field);
      f.setAccessible(true);
      return f.get(stats);
    } catch (Throwable t) {
      System.out.println("[invasion] constante " + field + " indisponible : " + t);
      return null;
    }
  }

  private static long constLong(String field, long dflt) {
    Object o = constant(field);
    return o instanceof Number ? ((Number) o).longValue() : dflt;
  }

  private static String constStr(String field, String dflt) {
    Object o = constant(field);
    return o == null ? dflt : o.toString();
  }

  public static long baseDate()       { return constLong("INVASION_BASE_DATE", 1645920000000L); }
  public static int  baseRotation()   { return (int) constLong("INVASION_BASE_ROTATION", 23L); }
  public static long startTimeOfDay() { return constLong("START_TIME", 43_200_000L); }
  public static long endTimeOfDay()   { return constLong("END_TIME", 43_200_000L); }
  public static String startDay()     { return constStr("START_DAY", "MONDAY"); }
  public static String endDay()       { return constStr("END_DAY", "SATURDAY"); }

  /** Index ISO du jour (LUNDI=1 … DIMANCHE=7) depuis le nom du jour des données. */
  private static int dayIndex(String name) {
    switch (name == null ? "" : name.trim().toUpperCase()) {
      case "MONDAY": return 1;
      case "TUESDAY": return 2;
      case "WEDNESDAY": return 3;
      case "THURSDAY": return 4;
      case "FRIDAY": return 5;
      case "SATURDAY": return 6;
      case "SUNDAY": return 7;
      default: return 1;
    }
  }

  /** Instant du DÉBUT de la semaine d'invasion (jour+heure de START) le plus récent à {@code now} ou avant. */
  public static long invasionStart(long now) {
    java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC);
    java.time.ZonedDateTime day = z.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC);
    int want = dayIndex(startDay());
    int have = day.getDayOfWeek().getValue();
    long start = day.plusDays(want - have).toInstant().toEpochMilli() + startTimeOfDay();
    if (start > now) start -= WEEK;                 // la fenêtre courante a commencé la semaine précédente
    return start;
  }

  /** Fin de l'invasion commencée à {@code start} (jour+heure de END dans la même semaine). */
  public static long invasionEnd(long start) {
    int span = dayIndex(endDay()) - dayIndex(startDay());
    if (span <= 0) span += 7;                        // END après START dans la semaine
    java.time.ZonedDateTime s = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC);
    java.time.ZonedDateTime d = s.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).plusDays(span);
    return d.toInstant().toEpochMilli() + endTimeOfDay();
  }

  /** L'invasion est-elle EN COURS à {@code now} (entre START et END) ? Sinon on est dans l'entre-deux. */
  public static boolean isActive(long now) {
    long s = invasionStart(now);
    return now >= s && now < invasionEnd(s);
  }

  /** Début de la PROCHAINE invasion après {@code now}. */
  public static long nextStart(long now) { return invasionStart(now) + WEEK; }

  /** Numéro de rotation de l'invasion commencée à {@code start} :
   *  {@code INVASION_BASE_ROTATION + nombre de semaines écoulées depuis INVASION_BASE_DATE}. */
  public static int rotation(long start) {
    long weeks = Math.floorDiv(start - baseDate(), WEEK);
    return (int) (baseRotation() + weeks);
  }

  /** Équipe vedette d'une rotation (cycle RED → BLUE → YELLOW). Voir la note « lecture structurelle » en tête. */
  public static HeroTeam teamForRotation(int rotation) {
    HeroTeam[] cycle = { HeroTeam.RED, HeroTeam.BLUE, HeroTeam.YELLOW };
    int i = Math.floorMod(rotation, cycle.length);
    return cycle[i];
  }

  /** Les héros de l'équipe {@code team} d'après les DONNÉES du jeu ({@code UnitStats.getTeam}). */
  public static java.util.List<UnitType> teamHeroes(HeroTeam team) {
    java.util.List<UnitType> out = new java.util.ArrayList<>();
    for (UnitType u : UnitType.values()) {
      try {
        if (com.perblue.heroes.game.data.unit.UnitStats.getTeam(u) == team) out.add(u);
      } catch (Throwable ignore) { /* unité sans stats (DEFAULT…) */ }
    }
    return out;
  }

  /** Construit l'{@code InvasionData} de l'invasion couvrant {@code now} (valeurs des données du jeu). */
  public static InvasionData buildData(long now) {
    long start = invasionStart(now);
    int rot = rotation(start);
    HeroTeam team = teamForRotation(rot);
    InvasionData d = new InvasionData();
    d.invasionID = rot;                              // identifiant stable de l'invasion = sa rotation
    d.rotationNumber = rot;
    d.startTime = start;
    d.endTime = invasionEnd(start);
    d.team = team;
    d.teamHeroes = new java.util.ArrayList<>(teamHeroes(team));
    d.featuredHeroes = new java.util.ArrayList<>();  // vedettes = choix opérateur (voir doc) ; vide = aucune
    d.lateTeamHeroes = new java.util.ArrayList<>();
    d.customTeamName = new java.util.HashMap<>();
    d.customTeamShortName = new java.util.HashMap<>();
    d.bossDifficultyScalar = 1f;
    d.breakerDifficultyScalar = 1f;
    // Empowerments : valeurs DU JEU (invasion_constants).
    d.initialTeamEmpowerment = (int) constLong("FEATURED_TEAM_EMPOWER", 2);
    d.initialFeaturedEmpowerment = (int) constLong("FEATURED_HERO_EMPOWER", 5);
    d.powerUpTeamEmpowerment = (int) constLong("EMPOWER_STONE_TEAM_AMOUNT", 1);
    d.powerUpHeroEmpowerment = (int) constLong("EMPOWER_STONE_HERO_AMOUNT", 5);
    return d;
  }

  /** État d'invasion NEUF pour un joueur (début d'une nouvelle rotation). Les compteurs partent à zéro ;
   *  les empowerments initiaux viennent des données ({@code FEATURED_TEAM_EMPOWER}). */
  public static com.perblue.heroes.network.messages.UserInvasionData newUserData(long userID, long guildID, long invasionID) {
    com.perblue.heroes.network.messages.UserInvasionData d =
        new com.perblue.heroes.network.messages.UserInvasionData();
    d.userID = userID;
    d.guildID = guildID;
    d.invasionID = invasionID;
    d.initalized = true;
    d.league = com.perblue.heroes.network.messages.InvasionLeague.values()[0];
    d.bossClaimStatus = new java.util.HashMap<>();
    d.unitEmpowerment = new java.util.HashMap<>();
    d.teamEmpowerments = (int) constLong("FEATURED_TEAM_EMPOWER", 2);
    d.bossSpawnLevel = (int) constLong("BOSS_FIGHT_INITAL_LEVEL", 450);
    return d;
  }

  /** Relit l'état persisté d'un joueur et le REMET À ZÉRO si l'invasion a changé de rotation
   *  (le jeu fait de même via {@code InvasionHelper.resetUserInvasion}). Renvoie toujours un état exploitable. */
  public static com.perblue.heroes.network.messages.UserInvasionData loadOrResetUserData(
      byte[] persisted, long userID, long guildID, long invasionID) {
    com.perblue.heroes.network.messages.UserInvasionData d = null;
    if (persisted != null && persisted.length > 0) {
      try {
        d = (com.perblue.heroes.network.messages.UserInvasionData)
            com.perblue.heroes.network.messages.MessageFactory.getInstance().readMessage(
                new com.perblue.grunt.translate.util.GruntInputStream(persisted));
      } catch (Exception e) { System.out.println("[invasion] état joueur illisible, réinitialisé : " + e); }
    }
    if (d == null || d.invasionID != invasionID) {
      // BASCULE DE ROTATION : état neuf, mais la LIGUE et les récompenses de rang en attente sont REPORTÉES
      // depuis l'invasion qui vient de se terminer (le rang final n'est pas connu ici — il est appliqué par
      // l'appelant qui a accès au classement ; à défaut, la ligue est simplement conservée).
      com.perblue.heroes.network.messages.UserInvasionData fresh = newUserData(userID, guildID, invasionID);
      carryOverLeague(d, fresh, 0);
      return fresh;
    }
    d.guildID = guildID;                                   // la guilde peut avoir changé en cours de semaine
    return d;
  }

  /** CLÔTURE D'INVASION (#69) — reporte la LIGUE d'une semaine sur l'autre. À la bascule de rotation, l'état
   *  du joueur repart à zéro (comme {@code InvasionHelper.resetUserInvasion}) MAIS sa ligue doit SURVIVRE, mise
   *  à jour selon son rang final ({@link #leagueAfterRank} : promotion ≤5, relégation ≥60, sinon maintien).
   *  Arme aussi les drapeaux de récompense de rang pour que le joueur puisse réclamer.
   *
   *  @param previous l'état de l'invasion QUI SE TERMINE (peut être {@code null} = joueur nouveau)
   *  @param fresh    l'état NEUF de la nouvelle invasion, modifié en place
   *  @param finalRank rang final du joueur dans l'invasion précédente (0 = non classé) */
  public static void carryOverLeague(
      com.perblue.heroes.network.messages.UserInvasionData previous,
      com.perblue.heroes.network.messages.UserInvasionData fresh, int finalRank) {
    if (fresh == null) return;
    com.perblue.heroes.network.messages.InvasionLeague prevLeague = previous == null || previous.league == null
        ? com.perblue.heroes.network.messages.InvasionLeague.UNRANKED : previous.league;
    fresh.league = finalRank > 0 ? leagueAfterRank(prevLeague, finalRank) : prevLeague;
    if (previous != null && finalRank > 0) {
      // Le joueur a participé et est classé → il a des récompenses de rang à réclamer.
      fresh.hasUserRankRewards = true;
      if (previous.guildID > 0) fresh.hasGuildRankRewards = true;
    }
  }

  /** LECTURE SEULE d'un état d'invasion persisté — contrairement à {@link #loadOrResetUserData}, n'écrase NI
   *  la guilde NI rien d'autre. Indispensable aux CLASSEMENTS, qui lisent en masse des états dont on ne connaît
   *  pas la guilde a priori (la passer à 0 effacerait l'appartenance et viderait le classement des guildes). */
  public static com.perblue.heroes.network.messages.UserInvasionData readUserData(byte[] persisted) {
    if (persisted == null || persisted.length == 0) return null;
    try {
      return (com.perblue.heroes.network.messages.UserInvasionData)
          com.perblue.heroes.network.messages.MessageFactory.getInstance().readMessage(
              new com.perblue.grunt.translate.util.GruntInputStream(persisted));
    } catch (Exception e) { return null; }
  }

  /** Octets wire d'un {@code UserInvasionData} (pour la persistance). */
  public static byte[] userDataToBytes(com.perblue.heroes.network.messages.UserInvasionData d) {
    com.perblue.grunt.translate.util.GruntOutputStream go = new com.perblue.grunt.translate.util.GruntOutputStream();
    d.writeAll(go);
    return go.getBytes();
  }

  /** Réponse {@code InvasionInfo} pour un joueur : invasion courante (si active) + prochaine échéance. */
  public static InvasionInfo buildInfo(long now) {
    InvasionInfo info = new InvasionInfo();
    long start = invasionStart(now);
    boolean active = isActive(now);
    if (active) {
      InvasionDataWrapper w = new InvasionDataWrapper();
      w.invasion = buildData(now);
      info.currentInvasion = w;
      info.nextInvasionStartTime = start + WEEK;
    } else {
      info.currentInvasion = null;
      info.nextInvasionStartTime = start + WEEK;     // l'invasion de la semaine est finie → la suivante
    }
    info.shouldReset = false;
    return info;
  }

  /** GÉNÈRE la COMPOSITION d'un breaker (le breaker + ses wards) pour une {@code room}, en tirant la
   *  TABLE DE DROP DU JEU {@code InvasionStats.BREAKER_FIGHT_COMP} (fichier {@code invasion_breaker_fight_comp.tab} :
   *  {@code ROOT → <BREAKER>, <WARD_1..4>}, avec des conditions {@code RoomTest(n)} qui font varier les wards
   *  selon la salle). Rien n'est inventé : les unités, niveaux, étoiles et wards viennent des données.
   *
   *  <p><b>Le JOUEUR compte</b> (vérifié) : la table consulte l'utilisateur courant du contexte de jeu. Avec un
   *  joueur lié, elle produit de VRAIS héros (ex. {@code BO_PEEP{level=25}}, {@code CHEF_SKINNER{level=735}}) —
   *  ce que fait le jeu, cf. {@code InvasionHelper.makeBreakerDefender(IInvasion, IHero)}. Sans joueur lié, elle
   *  retombe sur des mobs génériques {@code SOULLESS_*} : un mode DÉGRADÉ qu'il ne faut pas servir. D'où le
   *  paramètre {@code user} obligatoire, qui rend le contexte explicite.
   *
   *  <p>Le tirage est DÉTERMINISTE pour une graine donnée → un même joueur revoit la même composition tant que
   *  la salle et l'invasion ne changent pas (graine dérivée de invasionID+room côté appelant).
   *
   *  @return la liste d'objets {@code DropItem} du jeu (unité + modificateurs), vide en cas d'échec. */
  public static java.util.List<?> rollBreakerComposition(ServerUser user, int room, IInvasionProvider inv, long seed) {
    try {
      if (user != null) user.bindGameContext();     // contexte EXPLICITE (sinon composition dégradée)
      Class<?> st = Class.forName("com.perblue.heroes.game.data.invasion.InvasionStats");
      java.lang.reflect.Field f = st.getDeclaredField("BREAKER_FIGHT_COMP");
      f.setAccessible(true);
      Object comp = f.get(null);
      Object table = comp.getClass().getMethod("getTable").invoke(comp);
      Class<?> ctxC = Class.forName("com.perblue.heroes.game.data.invasion.InvasionStats$BreakerDTContext");
      java.lang.reflect.Constructor<?> ctor = ctxC.getDeclaredConstructor(
          int.class, Class.forName("com.perblue.heroes.game.objects.IInvasion"));
      ctor.setAccessible(true);
      Object ctx = ctor.newInstance(room, inv.asGameInvasion());
      java.lang.reflect.Method roll = table.getClass().getMethod("roll",
          Class.forName("com.perblue.common.droptable.DTContext"), java.util.Random.class);
      Object out = roll.invoke(table, ctx, new java.util.Random(seed));
      // IMPORTANT : la liste rendue par DropTable.roll est RÉUTILISÉE d'un tirage à l'autre (tampon interne du
      // moteur de drop-tables). On en prend donc une COPIE, sinon deux tirages successifs « deviennent » le
      // même résultat — bug observé : trois compositions distinctes finissaient toutes identiques.
      return out instanceof java.util.List
          ? new java.util.ArrayList<>((java.util.List<?>) out)
          : java.util.Collections.emptyList();
    } catch (Throwable t) {
      Throwable c = t.getCause() != null ? t.getCause() : t;
      System.out.println("[invasion] tirage composition breaker (room " + room + ") : " + c);
      return java.util.Collections.emptyList();
    }
  }

  /** Fournit l'objet {@code IInvasion} du jeu attendu par les contextes de drop-table. */
  public interface IInvasionProvider {
    com.perblue.heroes.game.objects.IInvasion asGameInvasion();
  }

  /** Résultat autoritatif d'un combat de BREAKER : ce qui a été débité et accordé. */
  public static final class BreakerOutcome {
    public boolean accepted;              // faux = refusé (énergie insuffisante, invasion inactive…)
    public String refusal = "";
    public int staminaCost;
    public int gold, breakers, points, level;
    @Override public String toString() {
      return accepted ? ("−" + staminaCost + " énergie, +" + gold + " or, +" + breakers
          + " BREAKER, +" + points + " pts (niveau " + level + ")") : ("REFUSÉ : " + refusal);
    }
  }

  /** RÉSOUT un combat de breaker de façon autoritative, avec les FORMULES DU JEU :
   *  coût {@code BREAKER_FIGHT_STAMINA_COST}, niveau {@code BREAKER_FIGHT_LEVEL(room)},
   *  or {@code BREAKER_FIGHT_GOLD_REWARD(room)}, points {@code BREAKER_FIGHT_POINT_REWARD},
   *  gain {@code BREAKER_FIGHT_BREAKER_REWARD} — toutes lues via {@code InvasionStats}.
   *  Le débit d'énergie s'applique même en cas de défaite (comme le jeu) ; les gains sont réservés à la victoire. */
  public static BreakerOutcome resolveBreakerFight(ServerUser u,
      com.perblue.heroes.network.messages.UserInvasionData ud, int room, boolean victory, long now) {
    BreakerOutcome o = new BreakerOutcome();
    if (!isActive(now)) { o.refusal = "invasion inactive"; return o; }
    if (room < 0) { o.refusal = "room invalide"; return o; }
    o.staminaCost = com.perblue.heroes.game.data.invasion.InvasionStats.getBreakerFightStaminaCost();
    long stamina = u.resourceAmount(com.perblue.heroes.network.messages.ResourceType.INVASION_STAMINA);
    if (stamina < o.staminaCost) { o.refusal = "énergie d'invasion insuffisante (" + stamina + "<" + o.staminaCost + ")"; return o; }
    o.accepted = true;
    u.giveResource(com.perblue.heroes.network.messages.ResourceType.INVASION_STAMINA, -o.staminaCost);
    o.level = com.perblue.heroes.game.data.invasion.InvasionStats.getBreakerFightLevel(room);
    ud.breakerBattlesFought++;
    if (!victory) return o;
    ud.breakerBattlesWon++;
    o.gold = com.perblue.heroes.game.data.invasion.InvasionStats.getBreakerFightGoldReward(room);
    o.breakers = com.perblue.heroes.game.data.invasion.InvasionStats.getBreakerFightBreakerReward();
    // POINTS D'INVASION — on reproduit EXACTEMENT l'appel du jeu (InvasionHelper.recordBreakerFightOutcome) :
    // getBreakerFightPoints(room, userLevelSnapshot, invasionMaxTeamLevel), l'expression étant BREAKER_FIGHT_POINT_REWARD
    // = "1R*M" avec R=room et M=getInvasionPointsMultiplier(userLevelSnapshot, invasionMaxTeamLevel). userLevelSnapshot=0
    // retombe fidèlement sur invasionMaxTeamLevel (branche du jeu). Le facteur d'évènement vient de
    // snapshot.getLootResourceMultiplier(INVASION_BREAKER, INVASION_POINTS) — SpecialEventSnapshot.NONE ⇒ 1 (PARTIEL, cf.
    // SHIMS : aucun bonus d'évènement headless, comme la campagne). NB : room 0 ⇒ R=0 ⇒ 0 point, comportement DU JEU.
    u.bindGameContext();
    int invasionMaxTeamLevel = com.perblue.heroes.game.data.content.ContentHelper
        .getCurrent(u.gameUser()).getInvasionMaxTeamLevel();
    int eventMul = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE.getLootResourceMultiplier(
        com.perblue.heroes.network.messages.GameMode.INVASION_BREAKER,
        com.perblue.heroes.network.messages.ResourceType.INVASION_POINTS);
    o.points = com.perblue.heroes.game.data.invasion.InvasionStats
        .getBreakerFightPoints(room, ud.userLevelSnapshot, invasionMaxTeamLevel) * eventMul;
    u.giveResource(com.perblue.heroes.network.messages.ResourceType.GOLD, o.gold);
    u.giveResource(com.perblue.heroes.network.messages.ResourceType.BREAKER, o.breakers);
    ud.breakersGained += o.breakers;
    ud.points += o.points;
    return o;
  }

  // ===================== BOSS (#69) =====================

  /** Durée de vie d'un boss trouvé ({@code BOSS_FIGHT_TIME_LIMIT}, 24 h par défaut). */
  /** Taille d'une page de BREAKER QUEST — {@code BREAKER_FIRST_PAGE_SIZE} pour la première, sinon
   *  {@code BREAKER_PAGE_SIZE} (données du jeu, `invasion_constants.tab`). */
  public static int breakerPageSize(boolean first) {
    return (int) constLong(first ? "BREAKER_FIRST_PAGE_SIZE" : "BREAKER_PAGE_SIZE", 10L);
  }

  public static long bossTimeLimit() { return constLong("BOSS_FIGHT_TIME_LIMIT", 86_400_000L); }
  /** Durée du verrou d'attaque ({@code ATTACK_LOCK_DURATION}, 5 min). */
  public static long attackLockDuration() { return constLong("ATTACK_LOCK_DURATION", 300_000L); }
  /** Niveau du premier boss ({@code BOSS_FIGHT_INITAL_LEVEL}). */
  public static int bossInitialLevel() { return (int) constLong("BOSS_FIGHT_INITAL_LEVEL", 450L); }
  /** Progression de niveau après une victoire ({@code BOSS_FIGHT_WIN_LEVEL_DELTA}). */
  public static int bossWinLevelDelta() { return (int) constLong("BOSS_FIGHT_WIN_LEVEL_DELTA", 25L); }

  /** FAIT APPARAÎTRE un boss pour la guilde : il est « trouvé » par {@code finder} et devient attaquable par
   *  tous les membres jusqu'à {@code BOSS_FIGHT_TIME_LIMIT}. Niveau et échéance viennent des DONNÉES du jeu. */
  public static com.perblue.heroes.network.messages.InvasionBossInfo spawnBoss(
      ServerGuild g, ServerUser finder, int level, long now) {
    if (g == null) return null;
    com.perblue.heroes.network.messages.InvasionBossInfo b =
        new com.perblue.heroes.network.messages.InvasionBossInfo();
    b.bossID = g.nextBossID++;
    b.bossLevel = level > 0 ? level : bossInitialLevel();
    b.foundTime = now;
    b.endTime = now + bossTimeLimit();
    b.damageDone = new java.util.HashMap<>();
    b.augments = new java.util.ArrayList<>();
    b.breakpoints = new java.util.ArrayList<>();
    // LINEUP = l'unité du boss (MAMA_BOT), marquée BOSS pour INVASION_BOSS. `getBossUnitData` la lit ici :
    // sans lineup, la carte est dégradée et le combat impossible. Construite dans le contexte du découvreur.
    b.lineup = new java.util.ArrayList<>(ServerInvasionBreaker.bossLineup(finder, b.bossLevel));
    if (finder != null) {
      b.foundByUser = finder.basicInfo();
      b.finderTeamLevelSnapshot = finder.basicInfo() == null ? 1 : finder.basicInfo().teamLevel;
    }
    if (g.info != null) b.foundByGuild = g.info.basicInfo;
    com.perblue.grunt.translate.util.GruntOutputStream go = new com.perblue.grunt.translate.util.GruntOutputStream();
    b.writeAll(go);
    g.addInvasionBoss(go.getBytes());
    return b;
  }

  /** Les boss ENCORE ACTIFS de la guilde (échéance non atteinte). Les expirés sont retirés. */
  public static java.util.List<com.perblue.heroes.network.messages.InvasionBossInfo> activeBosses(
      ServerGuild g, long now) {
    java.util.List<com.perblue.heroes.network.messages.InvasionBossInfo> out = new java.util.ArrayList<>();
    if (g == null) return out;
    for (com.perblue.heroes.network.messages.InvasionBossInfo b : g.invasionBosses()) {
      if (b.endTime <= now) { g.replaceInvasionBoss(b.bossID, null); continue; }
      out.add(b);
    }
    return out;
  }

  /** RENSEIGNE la vue PAR JOUEUR d'un boss avant de l'envoyer : {@code actionState} (FIGHT/CLAIM/DEFAULT) et
   *  {@code youAttacked}. Le client (InvasionBossCard.onCardPressed) n'ouvre l'aperçu de combat QUE si
   *  {@code actionState == FIGHT} (et ne propose la réclamation que si {@code CLAIM}) — sans ce champ, taper le
   *  boss ne fait RIEN (même famille de défaut que {@code activeBreakerFight}). L'état vient de la logique du
   *  jeu : boss non vaincu ⇒ FIGHT ; vaincu et part du joueur non réclamée ⇒ CLAIM ; sinon DEFAULT. */
  public static void applyBossActionState(com.perblue.heroes.network.messages.InvasionBossInfo boss,
      ServerUser user, com.perblue.heroes.network.messages.UserInvasionData ud) {
    if (boss == null) return;
    long total = 0L;
    boolean youAttacked = false;
    if (boss.damageDone != null) {
      for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) boss.damageDone).entrySet()) {
        Object v = e.getValue();
        if (v instanceof com.perblue.heroes.network.messages.InvasionBossDamageData)
          total += ((com.perblue.heroes.network.messages.InvasionBossDamageData) v).damage;
        if (user != null && e.getKey() instanceof Long && ((Long) e.getKey()) == user.userID) youAttacked = true;
      }
    }
    boss.youAttacked = youAttacked;
    float hp = 0f;
    try { hp = com.perblue.heroes.game.logic.InvasionHelper.getBossHP(boss); } catch (Throwable ignore) {}
    boolean defeated = hp > 0f && total >= hp;
    if (defeated) {
      // RÉCLAMÉ ? les valeurs de bossClaimStatus sont des BossClaimStatusData (pas des Boolean) : on lit
      // rewardsClaimed. Sans ce correctif l'ancien test Boolean.TRUE.equals(...) était toujours faux.
      boolean claimed = false;
      if (ud != null && ud.bossClaimStatus != null) {
        Object cs = ud.bossClaimStatus.get(boss.bossID);
        if (cs instanceof com.perblue.heroes.network.messages.BossClaimStatusData)
          claimed = ((com.perblue.heroes.network.messages.BossClaimStatusData) cs).rewardsClaimed;
      }
      boss.actionState = (youAttacked && !claimed)
          ? com.perblue.heroes.network.messages.InvasionBossActionState.CLAIM
          : com.perblue.heroes.network.messages.InvasionBossActionState.DEFAULT;
    } else {
      boss.actionState = com.perblue.heroes.network.messages.InvasionBossActionState.FIGHT;
    }
  }

  /** RÔLES de récompense GAGNÉS par le joueur sur un boss VAINCU, dérivés de l'état PARTAGÉ observable.
   *  <p>Le serveur d'origine décide ces rôles dans {@code InvasionHelperExt} — code SERVEUR, ABSENT du jar
   *  client (voir {@code InvasionHelper} : {@code ext = null} hors client). On applique donc ici la
   *  SÉMANTIQUE explicite de chaque valeur de {@link com.perblue.heroes.network.messages.InvasionBossRewardType}
   *  aux faits persistés : découvreur, dégâts cumulés du joueur, PV total du boss (seuils 10 %/30 %), plus gros
   *  contributeur, dernier attaquant (= coup fatal). Le BUTIN par rôle reste tiré des DONNÉES du jeu
   *  ({@code invasion_boss_rewards*.tab}) — c'est le CLIENT qui le tire ({@code InvasionHelper.rollBossRewardLoot})
   *  puis le RENVOIE dans {@code ClaimInvasionBossRewards} (modèle client-autoritatif du portage). La liste des
   *  rôles est ce que le client itère pour savoir QUELS butins tirer (cf. {@code InvasionClientHelper.claimBossRewards}). */
  public static java.util.List<com.perblue.heroes.network.messages.InvasionBossRewardType> earnedBossRoles(
      com.perblue.heroes.network.messages.InvasionBossInfo boss, long userID) {
    java.util.List<com.perblue.heroes.network.messages.InvasionBossRewardType> roles = new java.util.ArrayList<>();
    if (boss == null || boss.damageDone == null) return roles;
    long userDmg = 0, maxDmg = 0, lastAt = 0, userLastAt = 0;
    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) boss.damageDone).entrySet()) {
      Object v = e.getValue();
      if (!(v instanceof com.perblue.heroes.network.messages.InvasionBossDamageData)) continue;
      com.perblue.heroes.network.messages.InvasionBossDamageData d =
          (com.perblue.heroes.network.messages.InvasionBossDamageData) v;
      if (d.damage > maxDmg) maxDmg = d.damage;
      if (d.lastAttackTime > lastAt) lastAt = d.lastAttackTime;
      if (e.getKey() instanceof Long && ((Long) e.getKey()) == userID) { userDmg = d.damage; userLastAt = d.lastAttackTime; }
    }
    if (userDmg <= 0) return roles;   // n'a pas participé → aucun rôle
    float hp = 0f;
    try { hp = com.perblue.heroes.game.logic.InvasionHelper.getBossHP(boss); } catch (Throwable ignore) {}
    roles.add(com.perblue.heroes.network.messages.InvasionBossRewardType.PARTICIPANT);
    if (boss.foundByUser != null && boss.foundByUser.iD == userID)
      roles.add(com.perblue.heroes.network.messages.InvasionBossRewardType.FINDER);
    if (userDmg >= maxDmg)
      roles.add(com.perblue.heroes.network.messages.InvasionBossRewardType.MOST_DAMAGE);
    if (hp > 0 && userDmg >= 0.30f * hp)
      roles.add(com.perblue.heroes.network.messages.InvasionBossRewardType.THIRTY_PERCENT_HP);
    if (hp > 0 && userDmg >= 0.10f * hp)
      roles.add(com.perblue.heroes.network.messages.InvasionBossRewardType.TEN_PERCENT_HP);
    if (userLastAt > 0 && userLastAt >= lastAt)
      roles.add(com.perblue.heroes.network.messages.InvasionBossRewardType.FINISHER);
    return roles;
  }

  /** RENSEIGNE la vue PAR JOUEUR {@code ud.bossClaimStatus} avant l'envoi de {@code yourData} : pour chaque boss
   *  ACTIF VAINCU auquel le joueur a droit et NON encore réclamé, SYNTHÉTISE une entrée réclamable
   *  {@code {rewardsClaimed:false, rewardsEarned:rôles}}. INDISPENSABLE : sans cette entrée NON-NULLE, taper le boss
   *  KO ne déclenche RIEN côté client — {@code InvasionBossCard.lastClaimable} exige
   *  {@code actionState==CLAIM ET getBossClaimStatus(bossID) != null} (établi au bytecode 2026-08-03). Les entrées
   *  déjà RÉCLAMÉES ({@code rewardsClaimed==true}) sont conservées telles quelles ; les boss non vaincus sont purgés. */
  public static void populateClaimStatus(ServerGuild g, ServerUser user,
      com.perblue.heroes.network.messages.UserInvasionData ud, long now) {
    if (ud == null || user == null) return;
    if (ud.bossClaimStatus == null) ud.bossClaimStatus = new java.util.HashMap<>();
    for (com.perblue.heroes.network.messages.InvasionBossInfo boss : activeBosses(g, now)) {
      Object existing = ud.bossClaimStatus.get(boss.bossID);
      if (existing instanceof com.perblue.heroes.network.messages.BossClaimStatusData
          && ((com.perblue.heroes.network.messages.BossClaimStatusData) existing).rewardsClaimed) continue; // déjà réclamé
      long total = 0L;
      if (boss.damageDone != null)
        for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) boss.damageDone).entrySet())
          if (e.getValue() instanceof com.perblue.heroes.network.messages.InvasionBossDamageData)
            total += ((com.perblue.heroes.network.messages.InvasionBossDamageData) e.getValue()).damage;
      float hp = 0f;
      try { hp = com.perblue.heroes.game.logic.InvasionHelper.getBossHP(boss); } catch (Throwable ignore) {}
      boolean defeated = hp > 0f && total >= hp;
      java.util.List<com.perblue.heroes.network.messages.InvasionBossRewardType> roles =
          defeated ? earnedBossRoles(boss, user.userID) : java.util.Collections.emptyList();
      if (roles.isEmpty()) { ud.bossClaimStatus.remove(boss.bossID); continue; }  // pas (encore) réclamable
      com.perblue.heroes.network.messages.BossClaimStatusData cs =
          new com.perblue.heroes.network.messages.BossClaimStatusData();
      cs.rewardsClaimed = false;
      cs.escapeClaimed = false;
      cs.rewardsEarned = new java.util.ArrayList<>(roles);
      ((java.util.Map<Object, Object>) ud.bossClaimStatus).put(boss.bossID, cs);
    }
  }

  /** DÉGÂTS FIDÈLES infligés au boss lors d'une {@code InvasionBossAttack} — SOURCE établie au bytecode
   *  (2026-08-02) : le client calcule {@code InvasionBossAttackScreen.getBossDamage = UnitCombatStats.totalDamageTaken}
   *  de la vedette, et ce MÊME compteur est sérialisé dans {@code AttackUnitSummary.damageTaken} (Scene :
   *  {@code summary.damageTaken += ev.getDamage()} ET {@code stats.totalDamageTaken += ev.getDamage()} sur le
   *  MÊME évènement) — donc {@code base.defenders[*].units[*].damageTaken} de l'unité boss = exactement le
   *  chiffre affiché au joueur. Combat client-autoritatif (comme campagne/arène/breaker) : on LIT ce chiffre,
   *  on ne re-simule pas. On somme la vedette (type du boss) sur tous les lineups défenseurs. */
  public static long extractBossDamage(com.perblue.heroes.network.messages.InvasionBossAttack ba,
      com.perblue.heroes.network.messages.UnitType bossType) {
    if (ba == null || ba.base == null || ba.base.defenders == null) return 0L;
    double dmg = 0;
    for (Object lo : ba.base.defenders) {
      if (!(lo instanceof com.perblue.heroes.network.messages.AttackLineupSummary)) continue;
      java.util.List<?> units = ((com.perblue.heroes.network.messages.AttackLineupSummary) lo).units;
      if (units == null) continue;
      for (Object uo : units) {
        if (!(uo instanceof com.perblue.heroes.network.messages.AttackUnitSummary)) continue;
        com.perblue.heroes.network.messages.AttackUnitSummary us =
            (com.perblue.heroes.network.messages.AttackUnitSummary) uo;
        if (bossType == null || us.type == bossType) dmg += us.damageTaken;
      }
    }
    return (long) dmg;
  }

  /** Résultat d'une attaque de boss. */
  public static final class BossOutcome {
    public boolean accepted;
    public String refusal = "";
    public int keyCost;
    public long damage, totalDamage;
    public boolean defeated;
    @Override public String toString() {
      return accepted ? ("−" + keyCost + " clé(s), " + damage + " dégâts (cumul joueur " + totalDamage + ")"
          + (defeated ? " → BOSS VAINCU" : "")) : ("REFUSÉ : " + refusal);
    }
  }

  /** ATTAQUE de boss, autoritative : vérifie le VERROU (un attaquant à la fois, {@code ATTACK_LOCK_DURATION}),
   *  débite les clés ({@code BREAKER}, coût {@code BOSS_FIGHT_{1X,5X}_KEY_COST} selon le multiplicateur),
   *  cumule les dégâts DU JOUEUR dans {@code damageDone} (état partagé de la guilde) et persiste l'objet du jeu. */
  public static BossOutcome attackBoss(ServerGuild g, ServerUser u,
      com.perblue.heroes.network.messages.UserInvasionData ud,
      long bossID, int damageMultiplier, long damage, long now) {
    BossOutcome o = new BossOutcome();
    if (g == null || u == null) { o.refusal = "guilde ou joueur absent"; return o; }
    if (!isActive(now)) { o.refusal = "invasion inactive"; return o; }
    com.perblue.heroes.network.messages.InvasionBossInfo boss = null;
    for (com.perblue.heroes.network.messages.InvasionBossInfo b : activeBosses(g, now))
      if (b.bossID == bossID) boss = b;
    if (boss == null) { o.refusal = "boss inconnu ou expiré"; return o; }
    if (!g.lockBoss(bossID, u.userID, now, attackLockDuration())) {
      o.refusal = "boss déjà attaqué par un autre membre"; return o;
    }
    o.keyCost = damageMultiplier > 1
        ? (int) constLong("BOSS_FIGHT_5X_KEY_COST", 3L)
        : (int) constLong("BOSS_FIGHT_1X_KEY_COST", 1L);
    long keys = u.resourceAmount(com.perblue.heroes.network.messages.ResourceType.BREAKER);
    if (keys < o.keyCost) {
      g.unlockBoss(bossID, u.userID);
      o.refusal = "clés insuffisantes (" + keys + "<" + o.keyCost + ")"; return o;
    }
    o.accepted = true;
    u.giveResource(com.perblue.heroes.network.messages.ResourceType.BREAKER, -o.keyCost);
    o.damage = Math.max(0L, damage);
    if (boss.damageDone == null) boss.damageDone = new java.util.HashMap<>();
    // La valeur doit être l'OBJET DU JEU InvasionBossDamageData{damage, lastAttackTime} — un Long brut n'est
    // pas sérialisable par le format wire (la carte est typée Map<Long, InvasionBossDamageData>).
    Object prev = boss.damageDone.get(u.userID);
    com.perblue.heroes.network.messages.InvasionBossDamageData dd =
        prev instanceof com.perblue.heroes.network.messages.InvasionBossDamageData
            ? (com.perblue.heroes.network.messages.InvasionBossDamageData) prev
            : new com.perblue.heroes.network.messages.InvasionBossDamageData();
    dd.damage += o.damage;
    dd.lastAttackTime = now;
    ((java.util.Map<Object, Object>) boss.damageDone).put(u.userID, dd);
    o.totalDamage = dd.damage;
    if (ud != null) { ud.bossBattlesFought++; }
    g.replaceInvasionBoss(bossID, boss);
    g.unlockBoss(bossID, u.userID);
    return o;
  }

  // ===================== LIGUES ET CLASSEMENTS (#69) =====================

  /** Seuils de ligue lus des données ({@code LEAGUE_PROMOTE_THRESHOLD} / {@code LEAGUE_DEMOTE_THRESHOLD} :
   *  rang ≤ 5 → promotion, rang ≥ 60 → relégation ; {@code LEAGUE_DESIRED_SIZE}=50 par division). */
  public static int leaguePromoteThreshold() { return (int) constLong("LEAGUE_PROMOTE_THRESHOLD", 5L); }
  public static int leagueDemoteThreshold()  { return (int) constLong("LEAGUE_DEMOTE_THRESHOLD", 60L); }
  public static int leagueDesiredSize()      { return (int) constLong("LEAGUE_DESIRED_SIZE", 50L); }

  /** La ligue SUIVANTE (promotion) ou PRÉCÉDENTE (relégation) dans l'ordre du jeu
   *  {@code UNRANKED → BRONZE → SILVER → GOLD → PLATINUM → CHALLENGER}. */
  public static com.perblue.heroes.network.messages.InvasionLeague shiftLeague(
      com.perblue.heroes.network.messages.InvasionLeague cur, boolean up) {
    com.perblue.heroes.network.messages.InvasionLeague[] order = {
        com.perblue.heroes.network.messages.InvasionLeague.UNRANKED,
        com.perblue.heroes.network.messages.InvasionLeague.BRONZE,
        com.perblue.heroes.network.messages.InvasionLeague.SILVER,
        com.perblue.heroes.network.messages.InvasionLeague.GOLD,
        com.perblue.heroes.network.messages.InvasionLeague.PLATINUM,
        com.perblue.heroes.network.messages.InvasionLeague.CHALLENGER };
    int i = 0;
    for (int k = 0; k < order.length; k++) if (order[k] == cur) i = k;
    int j = Math.max(0, Math.min(order.length - 1, i + (up ? 1 : -1)));
    return order[j];
  }

  /** Ligue d'arrivée d'après le RANG final, selon les seuils des données : ≤ promote → montée,
   *  ≥ demote → descente, sinon maintien. */
  public static com.perblue.heroes.network.messages.InvasionLeague leagueAfterRank(
      com.perblue.heroes.network.messages.InvasionLeague cur, int rank) {
    if (rank > 0 && rank <= leaguePromoteThreshold()) return shiftLeague(cur, true);
    if (rank >= leagueDemoteThreshold()) return shiftLeague(cur, false);
    return cur;
  }

  /** CLASSEMENT DES JOUEURS de l'invasion courante : tous les états d'invasion du shard, triés par points
   *  décroissants. Les noms sont résolus depuis le store. */
  public static java.util.List<com.perblue.heroes.network.messages.InvasionRankingRow> userRanking(
      UserStore store, int shardID, long invasionID, int limit) {
    java.util.List<com.perblue.heroes.network.messages.InvasionRankingRow> rows = new java.util.ArrayList<>();
    try {
      java.util.LinkedHashMap<Long, byte[]> all = store.listUserInvasions(shardID);
      for (java.util.Map.Entry<Long, byte[]> e : all.entrySet()) {
        com.perblue.heroes.network.messages.UserInvasionData ud = readUserData(e.getValue());
        if (ud == null || ud.invasionID != invasionID || ud.points <= 0) continue;   // hors invasion / sans score
        com.perblue.heroes.network.messages.InvasionRankingRow r =
            new com.perblue.heroes.network.messages.InvasionRankingRow();
        r.score = ud.points;
        try {
          ServerUser su = store.loadIfExists(e.getKey(), shardID);
          if (su != null) r.user = su.basicInfo();
        } catch (Exception ignore) {}
        rows.add(r);
      }
    } catch (Exception ex) { System.out.println("[invasion] userRanking : " + ex); }
    rows.sort((x, y) -> Long.compare(y.score, x.score));
    int rank = 0;
    java.util.List<com.perblue.heroes.network.messages.InvasionRankingRow> out = new java.util.ArrayList<>();
    for (com.perblue.heroes.network.messages.InvasionRankingRow r : rows) {
      r.rank = ++rank;
      if (out.size() < limit) out.add(r);
    }
    return out;
  }

  /** CLASSEMENT DES GUILDES : score d'une guilde = SOMME des points d'invasion de ses membres. */
  public static java.util.List<com.perblue.heroes.network.messages.InvasionRankingRow> guildRanking(
      UserStore store, int shardID, long invasionID, int limit) {
    java.util.List<com.perblue.heroes.network.messages.InvasionRankingRow> rows = new java.util.ArrayList<>();
    try {
      java.util.LinkedHashMap<Long, byte[]> all = store.listUserInvasions(shardID);
      java.util.LinkedHashMap<Long, Long> byGuild = new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<Long, byte[]> e : all.entrySet()) {
        com.perblue.heroes.network.messages.UserInvasionData ud = readUserData(e.getValue());
        if (ud == null || ud.invasionID != invasionID || ud.guildID <= 0 || ud.points <= 0) continue;
        byGuild.merge(ud.guildID, ud.points, Long::sum);
      }
      for (java.util.Map.Entry<Long, Long> e : byGuild.entrySet()) {
        com.perblue.heroes.network.messages.InvasionRankingRow r =
            new com.perblue.heroes.network.messages.InvasionRankingRow();
        r.score = e.getValue();
        try {
          ServerGuild g = store.loadGuild(shardID, e.getKey());
          if (g != null && g.info != null) r.guild = g.info.basicInfo;
        } catch (Exception ignore) {}
        rows.add(r);
      }
    } catch (Exception ex) { System.out.println("[invasion] guildRanking : " + ex); }
    rows.sort((x, y) -> Long.compare(y.score, x.score));
    int rank = 0;
    java.util.List<com.perblue.heroes.network.messages.InvasionRankingRow> out = new java.util.ArrayList<>();
    for (com.perblue.heroes.network.messages.InvasionRankingRow r : rows) {
      r.rank = ++rank;
      if (out.size() < limit) out.add(r);
    }
    return out;
  }

  /** Résumé lisible (journal serveur / admin). */
  public static String describe(long now) {
    long s = invasionStart(now);
    int rot = rotation(s);
    return "rotation #" + rot + " équipe " + teamForRotation(rot)
        + " du " + new java.util.Date(s) + " au " + new java.util.Date(invasionEnd(s))
        + (isActive(now) ? " [EN COURS]" : " [terminée, prochaine " + new java.util.Date(nextStart(now)) + "]");
  }
}
