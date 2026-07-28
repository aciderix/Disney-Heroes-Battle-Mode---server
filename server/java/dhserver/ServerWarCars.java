package dhserver;

import com.perblue.heroes.game.logic.GuildHelper;
import com.perblue.heroes.game.logic.WarHelper;
import com.perblue.heroes.network.messages.GuildRole;
import com.perblue.heroes.network.messages.WarCarInfo;
import com.perblue.heroes.network.messages.WarCarType;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarHeroSummary;
import com.perblue.heroes.network.messages.WarLineupSummary;
import com.perblue.heroes.network.messages.WarMemberInfo;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GUILD WAR (#68) — LES VOITURES : affectation des membres, étoiles, portes de garage.
 *
 * <p><b>Source de vérité de l'état.</b> Le client reconstruit lui-même {@code WarGuildInfo.cars[].members}
 * à partir de {@code WarGuildInfo.members[].assignedCar} ({@code WarClientHelper.collectWarInfoCarMembers}).
 * L'état canonique du serveur est donc la carte {@code members} ; {@link #rebuildCars} en DÉRIVE les voitures,
 * exactement comme le client, pour que les deux ne puissent pas diverger.
 *
 * <p><b>Étoiles = lineups.</b> {@code WarHelper.hasRemainingLineups} et
 * {@code WarClientHelper.getDefeatedEnemyCarTypes} testent tous deux
 * {@code starsEarned >= starsTotal} pour dire « voiture prise ». Une étoile est donc un LINEUP de défense :
 * {@code starsTotal} = nombre de lineups postés dans la salle, {@code starsEarned} = nombre de lineups
 * battus. C'est cohérent avec {@code POINTS_PER_LINEUP=1} (« Defeating an enemy Hero team earns your Guild
 * one point ») et {@code POINTS_PER_CAR=100} pour la salle entière.
 *
 * <p><b>Portes de garage.</b> {@link #closedGarageDoors} est le miroir serveur de
 * {@code WarClientHelper.getClosedGarageDoors} — règle relevée telle quelle, pas déduite : l'étage 1 est
 * toujours ouvert ; l'étage 2 s'ouvre dès QU'UNE voiture de l'étage 1 est prise ; l'étage 3 dès qu'une de
 * l'étage 2 l'est. C'est la formulation exacte de l'aide du jeu, « You must steal a car to open the next
 * floor ». Pendant la phase de SABOTAGE, VOTRE propre garage est entièrement fermé (celui de l'ennemi non).
 */
public final class ServerWarCars {

  private ServerWarCars() {}

  /**
   * Ordre des salles (index 0..8), relevé dans {@code WarClientHelper.GARAGE_ORDER}. Il coïncide avec
   * {@code WarHelper.getFloorNumber} : 0-2 = étage 1, 3-5 = étage 2, 6-8 = étage 3.
   */
  public static final List<WarCarType> GARAGE_ORDER = java.util.Collections.unmodifiableList(Arrays.asList(
      WarCarType.REDUCE_ATTACKER_HP_FLAT,
      WarCarType.INCREASE_DEFENDER_ARMOR_FLAT,
      WarCarType.INCREASE_DEFENDER_BD_FLAT,
      WarCarType.REDUCE_ATTACKER_BD_AND_SP_FLAT,
      WarCarType.INCREASE_DEFENDER_REALITY_FLAT,
      WarCarType.INCREASE_DEFENDER_SP_FLAT,
      WarCarType.POINT_PER_CLEAN_SWEEP,
      WarCarType.POINT_PER_DEFENSE_WIN,
      WarCarType.POINT_PER_FULL_DEFENSE));

  /** Nombre de salles d'un garage. */
  public static final int GARAGE_SIZE = 9;

  /** Position d'une voiture dans le garage, ou {@code -1}. */
  public static int garageIndex(WarCarType car) { return GARAGE_ORDER.indexOf(car); }

  /** Taille maximale d'une salle pour cette guilde : {@code BASE_CAR_SIZE} + perk de taille. */
  public static int maxCarSize(ServerGuild g, WarCarType car) {
    return WarHelper.getMaxCarSize(
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info), car);
  }

  /**
   * (Re)construit {@code cars} à partir de {@code members}, à l'identique de
   * {@code WarClientHelper.collectWarInfoCarMembers}, et recalcule les étoiles.
   *
   * <p>Chaque type de voiture existe TOUJOURS dans la carte (le client crée une {@code WarCarInfo} vide pour
   * les manquantes) : on fait pareil, sinon l'écran verrait des salles absentes.
   */
  public static void rebuildCars(WarGuildInfo side) {
    if (side.cars == null) return;
    for (WarCarType car : WarCarType.values()) {
      if (car == WarCarType.DEFAULT) continue;
      WarCarInfo info = (WarCarInfo) side.cars.get(car);
      if (info == null) side.cars.put(car, new WarCarInfo());
      else info.members.clear();
    }
    if (side.members != null) {
      for (Object o : side.members.values()) {
        WarMemberInfo m = (WarMemberInfo) o;
        WarCarInfo info = (WarCarInfo) side.cars.get(m.assignedCar);
        if (info != null) info.members.add(m);
      }
    }
    for (WarCarType car : WarCarType.values()) {
      if (car == WarCarType.DEFAULT) continue;
      recomputeStars((WarCarInfo) side.cars.get(car));
    }
  }

  /**
   * Recalcule {@code starsTotal}/{@code starsEarned} d'une salle : un lineup posté = une étoile à prendre,
   * un lineup dont TOUS les héros sont battus = une étoile gagnée par l'attaquant.
   *
   * <p>« Defeating an enemy Hero team earns your Guild one point. Each player has three teams. »
   */
  public static void recomputeStars(WarCarInfo info) {
    if (info == null) return;
    int total = 0, earned = 0;
    for (Object om : info.members) {
      WarMemberInfo m = (WarMemberInfo) om;
      if (m.defenses == null) continue;
      for (Object ol : m.defenses) {
        total++;
        if (lineupDefeated((WarLineupSummary) ol)) earned++;
      }
    }
    info.starsTotal = total;
    info.starsEarned = earned;
  }

  /** Un lineup est battu quand AUCUN de ses héros ne tient debout (miroir de {@code allLineupsDefeated}). */
  public static boolean lineupDefeated(WarLineupSummary lineup) {
    if (lineup == null || lineup.heroes == null || lineup.heroes.isEmpty()) return true;
    for (Object oh : lineup.heroes) {
      if (!((WarHeroSummary) oh).defeated) return false;
    }
    return true;
  }

  /** Une salle est PRISE quand toutes ses étoiles sont tombées (test exact du client). */
  public static boolean carDefeated(WarCarInfo info) {
    return info != null && info.starsEarned >= info.starsTotal;
  }

  /**
   * Portes FERMÉES du garage de {@code side}, dans l'ordre {@link #GARAGE_ORDER} — miroir serveur de
   * {@code WarClientHelper.getClosedGarageDoors}.
   *
   * @param ownGarage {@code true} si c'est le garage de la guilde qui regarde (pendant la phase de SABOTAGE,
   *                  son propre garage est entièrement fermé)
   */
  public static boolean[] closedGarageDoors(WarGuildInfo side, WarSummaryState state, boolean ownGarage) {
    boolean[] closed = new boolean[GARAGE_SIZE];
    if (state == WarSummaryState.SABOTAGE) {
      if (ownGarage) Arrays.fill(closed, true);
      return closed;
    }
    boolean[] taken = new boolean[GARAGE_SIZE];
    for (int i = 0; i < GARAGE_SIZE; i++) {
      taken[i] = side.cars != null && carDefeated((WarCarInfo) side.cars.get(GARAGE_ORDER.get(i)));
    }
    // Étage 1 : toujours ouvert.
    closed[0] = closed[1] = closed[2] = false;
    // Étage 2 : fermé tant qu'aucune voiture de l'étage 1 n'est prise.
    boolean floor2Closed = !taken[0] && !taken[1] && !taken[2];
    closed[3] = closed[4] = closed[5] = floor2Closed;
    // Étage 3 : fermé si l'étage 2 l'est, ou si aucune voiture de l'étage 2 n'est prise.
    boolean floor3Closed = floor2Closed || (!taken[3] && !taken[4] && !taken[5]);
    closed[6] = closed[7] = closed[8] = floor3Closed;
    return closed;
  }

  /** Une salle est-elle attaquable (porte ouverte) ? */
  public static boolean isCarOpen(WarGuildInfo side, WarCarType car, WarSummaryState state) {
    int idx = garageIndex(car);
    if (idx < 0) return false;
    return !closedGarageDoors(side, state, false)[idx];
  }

  // ---------------------------------------------------------------------------------------------
  // ASSIGN_WAR_CAR
  // ---------------------------------------------------------------------------------------------

  /**
   * Affecte {@code targetUserID} à la salle {@code car}, avec les contrôles du jeu :
   * {@code GuildHelper.canMoveWarLineups} pour se déplacer soi-même,
   * {@code canMoveOthersWarLineups} pour déplacer un autre membre, et la capacité de la salle
   * ({@code WarHelper.getMaxCarSize} = {@code BASE_CAR_SIZE} + perk de guilde).
   *
   * @return {@code null} si l'affectation est acceptée, sinon le motif de refus
   */
  public static String assignCar(ServerGuild g, ServerWarState w, long actorUserID, GuildRole actorRole,
      long targetUserID, WarCarType car) {
    WarGuildInfo side = w.sideOf(g.guildID);
    if (side == null) return "cette guilde n'est pas dans cette guerre";
    if (car == null) return "salle inconnue";

    // RÈGLE RELEVÉE DANS LE CLIENT, pas déduite : `WarCarLineupsTable` teste
    // `if (isNextWarState && (c'estVous || canMoveWarLineups(rôle)))` pour afficher le bouton d'édition —
    // donc CHACUN édite toujours SA propre place, et `canMoveWarLineups` n'est que le droit SUPPLÉMENTAIRE
    // d'éditer la carte d'un autre en place. Le bouton « déplacer les autres ici » est lui gaté par
    // `canMoveOthersWarLineups`.
    // ⚠️ Fait mesuré : dans la table de permissions de ce build, `WAR_MOVE_LINEUPS` n'est accordé à AUCUN
    // rôle (pas même RULER) — gater l'auto-placement dessus l'aurait rendu impossible pour tout le monde.
    boolean self = actorUserID == targetUserID;
    if (!self && !GuildHelper.canMoveOthersWarLineups(actorRole)) {
      return "votre rôle (" + actorRole + ") ne permet pas de déplacer un autre membre";
    }

    WarMemberInfo member = side.members != null ? (WarMemberInfo) side.members.get(targetUserID) : null;
    if (member == null) return "ce joueur ne participe pas à cette guerre";

    if (car != WarCarType.DEFAULT) {
      int occupants = 0;
      for (Object o : side.members.values()) {
        WarMemberInfo m = (WarMemberInfo) o;
        if (m.assignedCar == car && m.userInfo != null && m.userInfo.iD != targetUserID) occupants++;
      }
      int max = maxCarSize(g, car);
      if (occupants >= max) return "la salle " + car + " est pleine (" + occupants + "/" + max + ")";
    }

    member.assignedCar = car;
    rebuildCars(side);
    w.putSide(g.guildID, side);
    return null;
  }

  /** Membres actuellement affectés à une salle. */
  public static List<WarMemberInfo> membersOf(WarGuildInfo side, WarCarType car) {
    List<WarMemberInfo> out = new ArrayList<>();
    if (side.members == null) return out;
    for (Object o : side.members.values()) {
      WarMemberInfo m = (WarMemberInfo) o;
      if (m.assignedCar == car) out.add(m);
    }
    return out;
  }
}
