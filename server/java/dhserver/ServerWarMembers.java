package dhserver;

import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.network.messages.HeroLineup;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.BasicUserInfo;
import com.perblue.heroes.network.messages.HeroLineupType;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.WarCarType;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarHeroSummary;
import com.perblue.heroes.network.messages.WarLineupSummary;
import com.perblue.heroes.network.messages.WarMemberInfo;

/**
 * GUILD WAR (#68) — LES MEMBRES d'une guerre : qui participe, avec quelle défense.
 *
 * <p><b>Manque RÉEL trouvé par la vérification EN JEU (2026-08-02).</b> Rien ne créait jamais de
 * {@link WarMemberInfo} : {@code WarGuildInfo.members} restait vide, donc <em>personne</em> ne participait —
 * {@code assignCar} répondait « ce joueur ne participe pas à cette guerre », aucune attaque ne trouvait de
 * cible, et l'écran affichait 0/0 partout. Les tests headless ne l'avaient pas vu parce qu'ils
 * <b>fabriquaient eux-mêmes</b> les {@code WarMemberInfo} qu'ils testaient ensuite. C'est exactement le trou
 * que PRINCIPLES §8 (« la vérification EN JEU est obligatoire ») existe pour attraper.
 *
 * <p><b>Source de la défense : le lineup RÉEL du joueur.</b> Une défense de guerre = trois équipes
 * ({@code WAR_DEFENSE_1..3}), posées par le joueur et déjà persistées par le chemin {@code HeroLineupUpdate}
 * (le même que la défense d'arène, #41). On les relit par la logique du jeu
 * ({@code User.getHeroLineup} → {@code User.getHero} → {@code ClientNetworkStateConverter.getHeroSummary}),
 * exactement comme {@code ServerArena.readDefenseTeam}. Aucune règle réécrite.
 *
 * <p><b>L'état de GUERRE des héros est PRÉSERVÉ.</b> {@code WarHeroSummary} porte, en plus du héros,
 * {@code defeated} (KO définitif pour la guerre) et {@code sabotage}/{@code sabotagedByUser}. Reconstruire la
 * liste à chaque changement de lineup effacerait ces faits — donc on les REPORTE, héros par héros (appariés
 * par {@code UnitType}, la clé que le protocole lui-même utilise pour désigner une victime de sabotage).
 */
public final class ServerWarMembers {

  private ServerWarMembers() {}

  /** Les trois équipes de défense d'une guerre — modèle du jeu, pas une invention. */
  public static final HeroLineupType[] DEFENSE_LINEUPS = {
      HeroLineupType.WAR_DEFENSE_1, HeroLineupType.WAR_DEFENSE_2, HeroLineupType.WAR_DEFENSE_3,
  };

  /**
   * (Re)construit l'entrée de {@code userID} dans le camp de {@code guildID}, depuis sa défense posée.
   *
   * <p>{@code sortIndex} n'a qu'un usage dans tout le client : un comparateur croissant
   * ({@code Comparators}) — c'est donc un <b>ordre d'affichage</b> choisi par l'opérateur, pas une règle de
   * jeu. On y met le rang du membre dans le roster de la guilde : stable, et indépendant de la puissance
   * (qui change en cours de guerre).
   *
   * @return {@code true} si le membre a été posé (il l'est même sans défense : il participe quand même)
   */
  public static boolean syncOne(ServerWarState w, ServerGuild g, ServerUser u) {
    if (w == null || g == null || u == null) return false;
    WarGuildInfo side = w.sideOf(g.guildID);
    if (side == null) return false;
    if (side.members == null) return false;

    WarMemberInfo previous = (WarMemberInfo) side.members.get(u.userID);
    WarMemberInfo m = build(u, previous, g.memberIDs.indexOf(u.userID));
    @SuppressWarnings("unchecked")
    java.util.Map<Long, Object> members = (java.util.Map<Long, Object>) side.members;
    members.put(u.userID, m);
    ServerWarCars.rebuildCars(side);
    w.putSide(g.guildID, side);          // ⚠️ sans ce putSide, la mutation serait perdue (cf. ServerWarState)
    return true;
  }

  /**
   * (Re)construit TOUS les membres d'un camp depuis le roster de la guilde. Appelé à l'ouverture d'une guerre
   * — c'est là que le backing d'origine fige « qui est dans cette guerre ».
   *
   * @return le nombre de membres posés
   */
  public static int syncAll(UserStore store, int shardID, ServerWarState w, ServerGuild g)
      throws java.sql.SQLException {
    if (w == null || g == null) return 0;
    WarGuildInfo side = w.sideOf(g.guildID);
    if (side == null || side.members == null) return 0;
    @SuppressWarnings("unchecked")
    java.util.Map<Long, Object> members = (java.util.Map<Long, Object>) side.members;

    int n = 0;
    for (int i = 0; i < g.memberIDs.size(); i++) {
      Long id = g.memberIDs.get(i);
      ServerUser u = store.loadIfExists(id, shardID);
      if (u == null) {                                   // membre disparu : on le journalise, on ne le tait pas
        System.out.println("[war] membre " + id + " de la guilde " + g.guildID + " introuvable — non inscrit");
        continue;
      }
      members.put(id, build(u, (WarMemberInfo) members.get(id), i));
      n++;
    }
    ServerWarCars.rebuildCars(side);
    w.putSide(g.guildID, side);
    return n;
  }

  /** Le membre, bâti depuis sa défense réelle, en reportant l'état de guerre déjà acquis. */
  private static WarMemberInfo build(ServerUser su, WarMemberInfo previous, int rosterIndex) {
    WarMemberInfo m = new WarMemberInfo();
    BasicUserInfo who = su.basicInfo();
    m.userInfo = who;
    m.sortIndex = Math.max(0, rosterIndex);
    if (previous != null) {
      m.assignedCar = previous.assignedCar;
      m.sabotagesDealt = previous.sabotagesDealt;
      m.sparsDealt = previous.sparsDealt;
      m.underAttackByUserID = previous.underAttackByUserID;
      if (previous.combatModifiers != null) m.combatModifiers.putAll(previous.combatModifiers);
    } else {
      m.assignedCar = WarCarType.DEFAULT;
    }

    User user;
    try {
      user = su.gameUser();
    } catch (Throwable t) {
      System.out.println("[war] défense de " + su.userID + " illisible (" + t + ") — membre sans défense");
      return m;
    }

    @SuppressWarnings("unchecked")
    java.util.List<Object> defenses = (java.util.List<Object>) m.defenses;
    for (int i = 0; i < DEFENSE_LINEUPS.length; i++) {
      defenses.add(readDefense(user, DEFENSE_LINEUPS[i], previous, i));
    }
    return m;
  }

  /** Une équipe de défense, relue par le chemin du jeu ; l'état de guerre déjà acquis est reporté. */
  private static WarLineupSummary readDefense(User user, HeroLineupType type, WarMemberInfo previous,
      int index) {
    WarLineupSummary ls = new WarLineupSummary();
    try {
      HeroLineup hl = user.getHeroLineup(type, 0L);
      if (hl == null || hl.heroes == null) return ls;
      @SuppressWarnings("unchecked")
      java.util.List<Object> heroes = (java.util.List<Object>) ls.heroes;
      for (Object o : hl.heroes) {
        UnitType ut = (UnitType) o;
        UnitData ud = (UnitData) user.getHero(ut);
        if (ud == null) continue;                        // héros posé puis retiré du roster → ignoré
        WarHeroSummary hs = new WarHeroSummary();
        hs.hero = ClientNetworkStateConverter.getHeroSummary(ud);
        WarHeroSummary prev = findPrevious(previous, index, ut);
        if (prev != null) {
          hs.defeated = prev.defeated;
          hs.sabotage = prev.sabotage;
          hs.sabotagedByUser = prev.sabotagedByUser;
        }
        heroes.add(hs);
        try { ls.power += Math.max(0L, ud.getPower(0)); } catch (Throwable ignore) {}
      }
    } catch (Throwable t) {
      System.out.println("[war] lineup " + type + " illisible : " + t);
    }
    return ls;
  }

  /** L'état de guerre déjà acquis par ce héros, dans la MÊME équipe (apparié par {@code UnitType}). */
  private static WarHeroSummary findPrevious(WarMemberInfo previous, int index, UnitType ut) {
    if (previous == null || previous.defenses == null || index >= previous.defenses.size()) return null;
    Object o = previous.defenses.get(index);
    if (!(o instanceof WarLineupSummary)) return null;
    WarLineupSummary ls = (WarLineupSummary) o;
    if (ls.heroes == null) return null;
    for (Object h : ls.heroes) {
      WarHeroSummary hs = (WarHeroSummary) h;
      if (hs.hero != null && hs.hero.type == ut) return hs;
    }
    return null;
  }

  /** Le joueur a-t-il posé au moins un héros en défense de guerre ? (l'écran le signale par une pastille) */
  public static boolean hasDefense(WarMemberInfo m) {
    if (m == null || m.defenses == null) return false;
    for (Object o : m.defenses) {
      WarLineupSummary ls = (WarLineupSummary) o;
      if (ls.heroes != null && !ls.heroes.isEmpty()) return true;
    }
    return false;
  }
}
