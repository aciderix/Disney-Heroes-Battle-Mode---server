package dhserver;

import com.perblue.heroes.game.missions.MissionHelper;
import com.perblue.heroes.game.objects.FriendPairID;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.MissionType;
import com.perblue.heroes.network.messages.UnitType;

/**
 * FRIENDSHIPS/MISSIONS (#72) — incrément 3c : <b>MISSIONS IDLE d'amitié</b> (le cœur de l'écran MISSIONS
 * de 12.1.0, révélé par la vérif EN JEU g83). On envoie une PAIRE d'amis en mission temporisée
 * (POWER_UP / MEMORY / DISK_POWER) → la mission se termine par le TEMPS → réclamation des récompenses.
 * Cf. docs/FRIENDSHIPS.md §incr.3c.
 *
 * <p>Tout par le CODE DU JEU (§3, {@code com.perblue.heroes.game.missions.MissionHelper}), zéro invention (§4) :
 * les durées, coûts, limites et récompenses viennent des {@code MissionStats}/{@code friendship_*.tab}. Le serveur
 * est AUTORITATIF : il exécute {@code addMission}/{@code claimMissionRewards}/{@code cancelMission} sur SON état,
 * avec SON horloge ({@code TimeUtil.serverTimeNow()}), puis persiste.
 *
 * <p><b>Persistance</b> : la liste runtime {@code missions} → {@code individualUserExtra.missions} via
 * {@link ServerUser#resyncMissions} ; {@code missionClaimData} est write-through (extra) ; les récompenses
 * (items/ressources) write-through, l'{@code empowerment} d'amitié via {@code resyncFriendships}, diamants/héros/
 * compteurs via les resyncs standard.
 */
public final class ServerMissions {

  private ServerMissions() {}

  /**
   * ADD_MISSION — démarre une mission idle pour une paire d'amis.
   * Protocole client ({@code ClientActionHelper.addMission}) : {@code extra{TYPE=MissionType, ID=FriendPairID.getAsLong()}}
   * (+ {@code TIME}, ignoré : on utilise l'horloge SERVEUR).
   *
   * <p>{@code MissionHelper.addMission} lève {@code ClientErrorCodeException} sur la plupart des gates (paire non
   * débloquée, héros déjà en mission, limites, bits déjà au max) MAIS ne lève PAS sur coûts insuffisants
   * ({@code chargeMissionCosts} = {@code removeItem}, client-autoritatif). On MIROITE donc la garde cliente
   * complète via {@code canStartMission} (prédicat pur, renvoie {@code null} si OK) → refus autoritatif si un
   * client modifié tentait une mission non-affordable. Ne persiste pas (appelant).
   */
  public static boolean applyAddMission(ServerUser su, MissionType type, FriendPairID pair) {
    ServerContext.init();
    User user = su.gameUser();
    // ANTI-TRICHE : garde cliente complète (inclut CANT_AFFORD que addMission ne lève pas). null = OK.
    MissionHelper.MissionFailType fail = MissionHelper.canStartMission(user, type, pair);
    if (fail != null) {
      System.out.println("[mission] addMission refusé (" + type + " " + pair + ") : " + fail);
      return false;
    }
    try {
      MissionHelper.addMission(user, type, pair, com.perblue.heroes.util.TimeUtil.serverTimeNow());
    } catch (Throwable t) {
      System.out.println("[mission] addMission refusé (" + type + " " + pair + ") : " + t);
      return false;
    }
    IndividualUser iu = user.getIndividual();
    su.resyncMissions(iu);
    su.resyncCounts(user);   // LAST_MISSION_CYCLE_ID (chargeMissionCosts) + coûts (items/ressources write-through)
    su.resyncDiamonds(user);
    return true;
  }

  /**
   * CLAIM_MISSION_REWARDS — réclame les récompenses de TOUTES les missions terminées.
   * Protocole client ({@code ClientActionHelper.claimMissionRewards}) : {@code extra{TIME}} (ignoré).
   *
   * <p>{@code MissionHelper.claimMissionRewards(user, serverTimeNow)} = {@code updateAllMissions(time)} (fait
   * AVANCER les missions par le temps écoulé ; une mission dont le timer arrive à zéro produit une
   * {@code MissionClaimData} — récompenses roulées par {@code MissionStats.getOtherRewards}, empowerment par
   * {@code getEmpowermentReward} — via {@code addMissionClaimData}) PUIS applique chaque {@code MissionClaimData}
   * ({@code setEmpowerment} sur l'amitié, {@code RewardHelper.giveRewards}) et {@code clearMissionClaimData}.
   * Renvoie {@code true} si quelque chose a été réclamé. Ne persiste pas (appelant).
   */
  public static boolean applyClaimMissionRewards(ServerUser su) {
    ServerContext.init();
    User user = su.gameUser();
    boolean claimed;
    try {
      claimed = MissionHelper.claimMissionRewards(user, com.perblue.heroes.util.TimeUtil.serverTimeNow());
    } catch (Throwable t) {
      System.out.println("[mission] claimMissionRewards échec : " + t);
      return false;
    }
    IndividualUser iu = user.getIndividual();
    su.resyncMissions(iu);        // missions terminées retirées / cycles avancés
    su.resyncFriendships(iu);     // empowerment (DISK_POWER) crédité sur l'amitié
    su.resyncHeroes(user);        // récompenses héros/XP éventuelles
    su.resyncDiamonds(user);
    su.resyncCounts(user);        // LAST_MISSION_COLLECTION_ID + compteurs quotidiens
    return claimed;
  }

  /**
   * CANCEL_MISSION — annule la mission en cours d'un héros donné (rembourse les coûts, retire la mission).
   * Protocole client ({@code ClientActionHelper.cancelMission}) : {@code heroType = mission.getFriendship().getPrimary()}
   * (le héros PRIMAIRE de la paire identifie la mission) (+ {@code TIME}, ignoré).
   *
   * <p>{@code MissionHelper.cancelMissionByHero(user, hero, serverTimeNow)} retrouve la mission portant ce héros
   * ({@code getMissionWithHero}), rembourse ({@code refundMissionCosts}) et la retire. Ne persiste pas (appelant).
   */
  public static boolean applyCancelMission(ServerUser su, UnitType primaryHero) {
    ServerContext.init();
    User user = su.gameUser();
    try {
      MissionHelper.cancelMissionByHero(user, primaryHero, com.perblue.heroes.util.TimeUtil.serverTimeNow());
    } catch (Throwable t) {
      System.out.println("[mission] cancelMission refusé (" + primaryHero + ") : " + t);
      return false;
    }
    IndividualUser iu = user.getIndividual();
    su.resyncMissions(iu);
    su.resyncDiamonds(user);
    su.resyncCounts(user);   // remboursement des coûts (items/ressources write-through)
    return true;
  }
}
