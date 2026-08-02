package dhserver;

import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.BasicBreakerFight;
import com.perblue.heroes.network.messages.BasicUserInfo;
import com.perblue.heroes.network.messages.BreakerQuest;
import com.perblue.heroes.network.messages.BreakerUserFightInfo;
import com.perblue.heroes.network.messages.BreakerUserWardLineupInfo;
import com.perblue.heroes.network.messages.CombatModifier;
import com.perblue.heroes.network.messages.GameMode;
import com.perblue.heroes.network.messages.HeroBattleData;
import com.perblue.heroes.network.messages.HeroBattleDataExtraType;
import com.perblue.heroes.network.messages.HeroData;
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
 * INVASION (#69) — la BREAKER QUEST (mode SOLO) : liste des combats, aperçus et composition des adversaires.
 *
 * <p><b>Manque RÉEL trouvé EN JEU (2026-08-02).</b> Taper « GO » envoyait {@code GetBreakerQuest} que le
 * serveur journalisait <b>sans y répondre</b> : écran VIDE. Une fois le handler écrit, deux défauts de
 * TYPAGE WIRE se sont révélés à la sérialisation (invisibles headless, comme pour la guerre) :
 * <ol>
 *   <li>{@code BasicBreakerFight.wards} est une liste d'ENUMS ({@code CombatModifier}, {@code packEnumList} au
 *       bytecode), pas de {@code HeroSummary} — c'est un aperçu des TYPES DE GARDE ;</li>
 *   <li>le START d'un combat ne se répond pas avec un {@code BreakerUserFightData}
 *       ({@code breakerDefenders : List<HeroBattleData>}) mais avec un <b>{@code BreakerUserFightInfo}</b> —
 *       c'est ce type que le client range dans {@code BreakerQuest.activeBreakerFight}
 *       ({@code GameMain}$119) et dont {@code InvasionClientHelper.getBreakerDefenderLineup} tire les
 *       défenseurs.</li>
 * </ol>
 *
 * <p><b>Identité des adversaires : {@code HeroData}, pas {@code HeroSummary}.</b> Le client lit chaque
 * défenseur comme un {@code HeroData} (type/level/stars/rarity) et son état de combat depuis
 * {@code HeroData.modePersistentData[INVASION_BREAKER] = HeroBattleData{healthPercent, extra{BOSS}}}. On
 * produit donc des {@code HeroData} via la logique du jeu ({@code ClientNetworkStateConverter.getHeroData}
 * sur l'{@code UnitData} fabriqué), en y posant l'état de mode (pleine vie + drapeau boss lu par le client
 * via {@code Boolean.parseBoolean((String) extra.get(BOSS))}).
 *
 * <p><b>Composition tirée des données</b> ({@code invasion_breaker_fight_comp.tab} via
 * {@link ServerInvasion#rollBreakerComposition}) : un tirage rend 25 {@code DropItem} = cinq groupes de cinq
 * — le groupe BREAKER (sans {@code ward}) et quatre groupes de garde (un {@code ward=WARD_xxx} chacun), une
 * unité {@code boss=true} par groupe.
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
    /** Chaque unité, en {@code HeroData} prêt au wire (identité + état de mode INVASION_BREAKER). */
    public final List<HeroData> heroes = new ArrayList<>();
    /** Le résumé de l'unité {@code boss=true} du groupe — la vedette de l'aperçu. */
    public HeroSummary bossSummary;
  }

  /** Le niveau d'équipe MAXIMUM que les données du jeu définissent (dichotomie sur {@code getMaxHeroLevel}). */
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
   * Le {@link User} synthétique qui sert d'atelier — même patron que les bots d'arène : shard du joueur
   * (contenu daté chargé), niveau d'équipe = maximum réel des données (999 sortait de {@code TeamLevelStats},
   * {@code ArrayIndexOutOfBounds} mesuré), et identité du joueur (un {@code IndividualUser} d'identifiant 0
   * fait échouer {@code ServerContext.bind}, mesuré EN JEU).
   */
  private static User workshop(ServerUser owner) {
    UserInfo ui = new UserInfo();
    ui.shardID = owner != null ? owner.shardID : 1;
    ui.basicInfo = new BasicUserInfo();
    ui.basicInfo.teamLevel = maxTeamLevel();
    User w = ClientNetworkStateConverter.getUser(ui, new UserExtra(), "breaker");
    ServerContext.bind(w, ClientNetworkStateConverter.getIndividualUser(
        new IndividualUserExtra(), owner != null ? owner.userID : 1L, 0, "breaker"));
    return w;
  }

  /** Découpe une composition tirée en ses groupes, dans l'ordre de la table (BREAKER puis les gardes). */
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
        // `DropItem.getType()` rend une CHAÎNE (nom de ligne), pas un UnitType — mesuré.
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
      boolean isBoss = "true".equalsIgnoreCase(boss);
      HeroData hd = heroData(w, type, rarity, stars, level, isBoss);
      if (hd == null) continue;
      String key = ward == null ? "" : ward;
      Group g = byWard.get(key);
      if (g == null) { g = new Group(); g.ward = ward; byWard.put(key, g); out.add(g); }
      g.heroes.add(hd);
      if (isBoss) g.bossSummary = summary(w, type);
    }
    // Repli : un groupe sans marqueur `boss` montre sa première unité (jamais d'aperçu vide).
    for (Group g : out) {
      if (g.bossSummary == null && !g.heroes.isEmpty()) g.bossSummary = summary(w, g.heroes.get(0).type);
    }
    return out;
  }

  /** Un {@code HeroData} complet (identité + état de mode INVASION_BREAKER : pleine vie, drapeau boss). */
  @SuppressWarnings("unchecked")
  private static HeroData heroData(User w, UnitType type, Rarity rarity, int stars, int level, boolean boss) {
    try {
      // ⚠️ (ÉTOILES, NIVEAU) — ordre relevé au bytecode, cf. ServerUser.grantHero.
      if (w.getHero(type) == null) w.createAndAddHero(type, rarity, stars, level, new String[]{"breaker"});
      UnitData ud = (UnitData) w.getHero(type);
      if (ud == null) return null;
      ud.setRarity(rarity); ud.setStars(stars); ud.setLevel(level);
      HeroData hd = ClientNetworkStateConverter.getHeroData(ud);
      HeroBattleData bd = new HeroBattleData();
      bd.healthPercent = 1.0f;                       // adversaire à pleine vie au début du combat
      ((Map<Object, Object>) bd.extra).put(HeroBattleDataExtraType.BOSS, Boolean.toString(boss));
      ((Map<Object, Object>) hd.modePersistentData).put(GameMode.INVASION_BREAKER, bd);
      return hd;
    } catch (Throwable t) {
      System.out.println("[invasion] unité " + type + " non fabricable : " + t);
      return null;
    }
  }

  /** Le résumé wire d'une unité (pour l'aperçu de la liste). */
  private static HeroSummary summary(User w, UnitType type) {
    try {
      UnitData ud = (UnitData) w.getHero(type);
      return ud == null ? null : ClientNetworkStateConverter.getHeroSummary(ud);
    } catch (Throwable t) { return null; }
  }

  /**
   * L'aperçu d'un combat pour la liste de la BREAKER QUEST.
   *
   * <p>⚠️ {@code BasicBreakerFight.wards} = liste d'ENUMS ({@code CombatModifier}), pas de {@code HeroSummary}
   * (cf. l'en-tête de classe). {@code bossHero} = boss du groupe BREAKER.
   */
  public static BasicBreakerFight toBasicFight(int room, List<Group> groups) {
    BasicBreakerFight bf = new BasicBreakerFight();
    bf.index = room;
    @SuppressWarnings("unchecked") List<Object> wards = (List<Object>) bf.wards;
    for (Group g : groups) {
      if (g.ward == null) { bf.bossHero = g.bossSummary; continue; }
      try { wards.add(CombatModifier.valueOf(g.ward)); }
      catch (Throwable t) { System.out.println("[invasion] garde inconnu (aperçu) : " + g.ward); }
    }
    return bf;
  }

  /**
   * Le combat ACTIF complet, réponse à {@code InvasionBreakerAttackStart} : ce {@code BreakerUserFightInfo}
   * que le client range dans {@code activeBreakerFight} et dont il tire les défenseurs. {@code breakerLineup}
   * = groupe BREAKER ({@code List<HeroData>}) ; {@code wardLineups} = les gardes
   * ({@code BreakerUserWardLineupInfo}).
   */
  @SuppressWarnings("unchecked")
  public static BreakerUserFightInfo toFightInfo(int room, List<Group> groups) {
    BreakerUserFightInfo fi = new BreakerUserFightInfo();
    fi.index = room;
    List<Object> breakerLineup = (List<Object>) fi.breakerLineup;
    List<Object> wardLineups = (List<Object>) fi.wardLineups;
    for (Group g : groups) {
      if (g.ward == null) { breakerLineup.addAll(g.heroes); continue; }
      BreakerUserWardLineupInfo wl = new BreakerUserWardLineupInfo();
      wl.defeated = false;
      try { wl.ward = CombatModifier.valueOf(g.ward); }
      catch (Throwable t) { System.out.println("[invasion] garde inconnu : " + g.ward); }
      ((List<Object>) wl.enemies).addAll(g.heroes);
      wardLineups.add(wl);
    }
    return fi;
  }

  /**
   * La BREAKER QUEST du joueur : une page de combats depuis sa progression.
   *
   * <p>Taille de page = {@code BREAKER_PAGE_SIZE}/{@code BREAKER_FIRST_PAGE_SIZE} ; indice de départ = le
   * nombre de combats déjà GAGNÉS ({@code UserInvasionData.breakerBattlesWon}) — le {@code R} des formules
   * {@code BREAKER_FIGHT_LEVEL/GOLD/POINT_REWARD} de {@code invasion_constants.tab}.
   */
  public static BreakerQuest buildQuest(ServerUser user, com.perblue.heroes.network.messages.UserInvasionData ud,
      ServerInvasion.IInvasionProvider inv, long invasionID) {
    BreakerQuest bq = new BreakerQuest();
    int done = ud != null ? Math.max(0, ud.breakerBattlesWon) : 0;
    int page = ServerInvasion.breakerPageSize(done == 0);
    @SuppressWarnings("unchecked") List<Object> fights = (List<Object>) bq.basicBreakerFights;
    for (int i = 0; i < page; i++) {
      int room = done + i;
      List<Group> gs = groups(user,
          ServerInvasion.rollBreakerComposition(user, room, inv, fightSeed(invasionID, room, user)));
      if (gs.isEmpty()) continue;
      fights.add(toBasicFight(room, gs));
      // Le combat ACTIF (première salle non gagnée = tête de page) doit AUSSI voyager en entier dans
      // BreakerQuest.activeBreakerFight : c'est CE champ que le client lit (InvasionBreakerScreen) pour
      // activer l'aperçu/START du combat et connaître activeIndex. Sans lui, activeBreakerFight est null
      // côté client ⇒ taper la vedette n'ouvre RIEN (bloqué hors tutoriel — défaut trouvé EN JEU).
      if (room == done) bq.activeBreakerFight = toFightInfo(room, gs);
    }
    return bq;
  }

  /** Graine STABLE d'un combat (invasion, salle, joueur) : mêmes adversaires à la liste et à l'entrée. */
  public static long fightSeed(long invasionID, int room, ServerUser user) {
    return invasionID * 1_000_003L + room * 31L + user.userID;
  }
}
