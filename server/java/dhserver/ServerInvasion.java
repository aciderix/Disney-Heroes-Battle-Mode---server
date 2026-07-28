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
    if (d == null || d.invasionID != invasionID) return newUserData(userID, guildID, invasionID);
    d.guildID = guildID;                                   // la guilde peut avoir changé en cours de semaine
    return d;
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
    o.points = com.perblue.heroes.game.data.invasion.InvasionStats.getBreakerFightPoints(room, 1, 1);
    u.giveResource(com.perblue.heroes.network.messages.ResourceType.GOLD, o.gold);
    u.giveResource(com.perblue.heroes.network.messages.ResourceType.BREAKER, o.breakers);
    ud.breakersGained += o.breakers;
    ud.points += o.points;
    return o;
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
