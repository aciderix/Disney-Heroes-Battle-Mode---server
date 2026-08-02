package dhserver;

import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.BasicBreakerFight;
import com.perblue.heroes.network.messages.BasicUserInfo;
import com.perblue.heroes.network.messages.BreakerQuest;
import com.perblue.heroes.network.messages.BreakerUserWardLineupData;
import com.perblue.heroes.network.messages.CombatModifier;
import com.perblue.heroes.network.messages.HeroSummary;
import com.perblue.heroes.network.messages.IndividualUserExtra;
import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.UserExtra;
import com.perblue.heroes.network.messages.UserInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * INVASION (#69) — la BREAKER QUEST (mode SOLO) : la liste des combats et leur composition.
 *
 * <p><b>Manque RÉEL trouvé EN JEU (2026-08-02).</b> Taper « GO » sur BREAKER QUEST envoie
 * {@code GetBreakerQuest} — que le serveur journalisait <b>sans y répondre</b> : aucun handler. L'écran
 * restait entièrement VIDE, le client attendant un {@code BreakerQuest} qui ne venait jamais. Le mode SOLO
 * n'avait donc aucune entrée, alors que le mode était marqué terminé. Et le handler
 * {@code InvasionBreakerAttackStart}, lui, répondait avec des listes VIDES.
 *
 * <p><b>La composition vient des données du jeu</b>, pas d'une invention : {@code invasion_breaker_fight_comp
 * .tab} (drop-table {@code InvasionStats.BREAKER_FIGHT_COMP}), tirée par
 * {@link ServerInvasion#rollBreakerComposition} dans le contexte du joueur. Un tirage rend <b>25</b>
 * {@code DropItem} — relevé à l'exécution : cinq groupes de cinq unités, chacune portant ses paramètres
 * {@code level}/{@code stars}/{@code rarity}, plus deux marqueurs :
 * <ul>
 *   <li>{@code ward=WARD_xxx} — l'unité appartient au groupe de CE garde (quatre gardes : la ligne ROOT de la
 *       table est {@code <BREAKER>, <WARD_1>, <WARD_2>, <WARD_3>, <WARD_4>}) ; sans ce paramètre, l'unité
 *       appartient au groupe BREAKER lui-même ;</li>
 *   <li>{@code boss=true} — l'unité VEDETTE de son groupe (une par groupe), celle que l'écran affiche.</li>
 * </ul>
 *
 * <p>⚠️ <b>Lecture assumée, isolée ici</b> : {@code BasicBreakerFight.wards} est une liste de
 * {@code HeroSummary} et l'écran en montre un aperçu par combat — on y met <b>le boss de chaque groupe de
 * garde</b> (quatre icônes), le {@code bossHero} étant celui du groupe BREAKER. C'est la seule lecture qui
 * donne un aperçu non redondant ; si un fait la contredit, il n'y a que {@link #toBasicFight} à corriger.
 */
public final class ServerInvasionBreaker {

  private ServerInvasionBreaker() {}

  private static String str(Object o) { return o == null ? null : String.valueOf(o); }

  private static int intOf(Object o, int dflt) {
    try { return o == null ? dflt : Integer.parseInt(String.valueOf(o).trim()); }
    catch (Throwable t) { return dflt; }
  }

  /** Un groupe de la composition : les unités qui partagent le même garde (ou aucun = groupe BREAKER). */
  public static final class Group {
    /** Le garde de ce groupe ({@code WARD_xxx}), ou {@code null} pour le groupe BREAKER. */
    public String ward;
    public final List<HeroSummary> heroes = new ArrayList<>();
    /** L'unité marquée {@code boss=true} du groupe — la vedette affichée. */
    public HeroSummary boss;
  }

  /**
   * Le {@link User} synthétique qui sert d'atelier à la fabrication des unités — même patron que les bots
   * d'arène ({@code ServerArena.syntheticOpponent}) : un {@code UserInfo} porteur du <b>shard du joueur</b>
   * (sinon le contenu daté n'est pas chargé) et d'un niveau d'équipe élevé (sans quoi
   * {@code createAndAddHero} refuse les niveaux visés). L'identifiant est celui du joueur : un
   * {@code IndividualUser} à l'identifiant 0 fait échouer {@code ServerContext.bind} (mesuré EN JEU).
   */
  private static User workshop(ServerUser owner) {
    UserInfo ui = new UserInfo();
    ui.shardID = owner != null ? owner.shardID : 1;
    ui.basicInfo = new BasicUserInfo();
    // Le TL indexe des tables du jeu (`TeamLevelStats`, 751 entrées) : 999 sortait du tableau
    // (`ArrayIndexOutOfBounds`, mesuré EN JEU). On prend le MAXIMUM réel des données, pas un chiffre rond.
    ui.basicInfo.teamLevel = maxTeamLevel();
    User w = ClientNetworkStateConverter.getUser(ui, new UserExtra(), "breaker");
    ServerContext.bind(w, ClientNetworkStateConverter.getIndividualUser(
        new IndividualUserExtra(), owner != null ? owner.userID : 1L, 0, "breaker"));
    return w;
  }

  /** Le niveau d'équipe MAXIMUM que les données du jeu définissent (dichotomie sur `getMaxHeroLevel`). */
  private static int maxTeamLevel() {
    int lo = 1, hi = 2000;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      try { com.perblue.heroes.game.data.misc.TeamLevelStats.getMaxHeroLevel(mid); lo = mid; }
      catch (Throwable t) { hi = mid - 1; }
    }
    return lo;
  }

  /**
   * Découpe une composition tirée en ses groupes, dans l'ordre de la table
   * ({@code BREAKER} d'abord, puis les gardes rencontrés).
   */
  public static List<Group> groups(ServerUser owner, List<?> composition) {
    List<Group> out = new ArrayList<>();
    java.util.Map<String, Group> byWard = new java.util.LinkedHashMap<>();
    User w = workshop(owner);
    for (Object o : composition) {
      String ward = null, boss = null;
      UnitType type;
      Rarity rarity = Rarity.WHITE;
      int level = 1, stars = 1;
      try {
        // `DropItem.getType()` rend une CHAÎNE (le nom de la ligne de table), pas un UnitType — mesuré.
        type = UnitType.valueOf(String.valueOf(o.getClass().getMethod("getType").invoke(o)));
        Object p = o.getClass().getMethod("getParameters").invoke(o);
        if (p instanceof Map) {
          Map<?, ?> params = (Map<?, ?>) p;
          ward = str(params.get("ward"));
          boss = str(params.get("boss"));
          level = intOf(params.get("level"), 1);
          stars = intOf(params.get("stars"), 1);
          String r = str(params.get("rarity"));
          if (r != null) { try { rarity = Rarity.valueOf(r); } catch (Throwable ignore) {} }
        }
      } catch (Throwable t) {
        System.out.println("[invasion] unité de composition illisible : " + t);
        continue;
      }
      HeroSummary hs = summary(w, type, rarity, stars, level);
      if (hs == null) continue;
      String key = ward == null ? "" : ward;
      Group g = byWard.get(key);
      if (g == null) { g = new Group(); g.ward = ward; byWard.put(key, g); out.add(g); }
      g.heroes.add(hs);
      if ("true".equalsIgnoreCase(boss)) g.boss = hs;
    }
    // Repli : un groupe sans marqueur `boss` montre sa première unité (jamais d'aperçu vide).
    for (Group g : out) if (g.boss == null && !g.heroes.isEmpty()) g.boss = g.heroes.get(0);
    return out;
  }

  /** Fabrique le résumé wire d'une unité, par la logique du jeu ({@code createAndAddHero} + converter). */
  private static HeroSummary summary(User w, UnitType type, Rarity rarity, int stars, int level) {
    try {
      // ⚠️ (ÉTOILES, NIVEAU) — ordre relevé au bytecode, cf. ServerUser.grantHero.
      w.createAndAddHero(type, rarity, stars, level, new String[]{"breaker"});
      UnitData ud = (UnitData) w.getHero(type);
      if (ud == null) return null;
      ud.setRarity(rarity); ud.setStars(stars); ud.setLevel(level);
      return ClientNetworkStateConverter.getHeroSummary(ud);
    } catch (Throwable t) {
      System.out.println("[invasion] unité " + type + " non fabricable : " + t);
      return null;
    }
  }

  /** L'aperçu d'un combat pour la liste de la BREAKER QUEST. */
  public static BasicBreakerFight toBasicFight(int room, List<Group> groups) {
    BasicBreakerFight bf = new BasicBreakerFight();
    bf.index = room;
    @SuppressWarnings("unchecked") List<Object> wards = (List<Object>) bf.wards;
    for (Group g : groups) {
      if (g.ward == null) bf.bossHero = g.boss;          // groupe BREAKER → la vedette du combat
      else if (g.boss != null) wards.add(g.boss);        // un garde → une icône d'aperçu
    }
    return bf;
  }

  /** Les lineups de gardes, format « données » (ce que l'attaquant reçoit pour combattre). */
  public static List<BreakerUserWardLineupData> toWardLineups(List<Group> groups) {
    List<BreakerUserWardLineupData> out = new ArrayList<>();
    for (Group g : groups) {
      if (g.ward == null) continue;
      BreakerUserWardLineupData d = new BreakerUserWardLineupData();
      try { d.ward = CombatModifier.valueOf(g.ward); }
      catch (Throwable t) { System.out.println("[invasion] garde inconnu : " + g.ward); }
      @SuppressWarnings("unchecked") List<Object> enemies = (List<Object>) d.enemies;
      enemies.addAll(g.heroes);
      out.add(d);
    }
    return out;
  }

  /** Les unités du groupe BREAKER (celles qu'on affronte hors gardes). */
  public static List<HeroSummary> breakerLineup(List<Group> groups) {
    for (Group g : groups) if (g.ward == null) return g.heroes;
    return new ArrayList<>();
  }

  /**
   * La BREAKER QUEST du joueur : une page de combats à partir de sa progression.
   *
   * <p>La taille de page vient des données ({@code BREAKER_PAGE_SIZE} / {@code BREAKER_FIRST_PAGE_SIZE}) et
   * l'indice de départ est le nombre de combats déjà GAGNÉS ({@code UserInvasionData.breakerBattlesWon}) —
   * c'est le {@code R} des formules {@code BREAKER_FIGHT_LEVEL/GOLD/POINT_REWARD} de
   * {@code invasion_constants.tab}.
   */
  public static BreakerQuest buildQuest(ServerUser user, com.perblue.heroes.network.messages.UserInvasionData ud,
      ServerInvasion.IInvasionProvider inv, long invasionID) {
    BreakerQuest bq = new BreakerQuest();
    int done = ud != null ? Math.max(0, ud.breakerBattlesWon) : 0;
    int page = ServerInvasion.breakerPageSize(done == 0);
    @SuppressWarnings("unchecked") List<Object> fights = (List<Object>) bq.basicBreakerFights;
    for (int i = 0; i < page; i++) {
      int room = done + i;
      long seed = invasionID * 1_000_003L + room * 31L + user.userID;
      List<Group> gs = groups(user, ServerInvasion.rollBreakerComposition(user, room, inv, seed));
      if (gs.isEmpty()) continue;
      fights.add(toBasicFight(room, gs));
    }
    return bq;
  }
}
