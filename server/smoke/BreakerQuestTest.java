import dhserver.*;
import com.perblue.heroes.network.messages.*;

/**
 * INVASION #69 — BREAKER QUEST : preuves des deux défauts trouvés EN JEU (2026-08-02).
 *
 * <p>(1) {@code BreakerQuest.activeBreakerFight} DOIT être renseigné : c'est CE champ que le client
 * (InvasionBreakerScreen) lit pour activer l'aperçu/START du combat. Sans lui, taper la vedette
 * n'ouvre rien — la BREAKER QUEST était injouable hors tutoriel.
 *
 * <p>(2) Les POINTS d'un combat gagné se calculent comme le jeu :
 * {@code getBreakerFightPoints(room, userLevelSnapshot, invasionMaxTeamLevel)} (et non des arguments
 * inventés {@code (room,1,1)}). On vérifie aussi que la voie VICTOIRE (qui exerce ce code : bindGameContext
 * + ContentHelper) s'exécute sans exception, et que la voie DÉFAITE débite l'énergie sans rien accorder.
 */
public class BreakerQuestTest {
  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // Un instant DANS la fenêtre d'invasion (lundi 14:00Z).
    long now = java.time.ZonedDateTime.parse("2026-08-03T14:00:00Z").toInstant().toEpochMilli();
    if (!ServerInvasion.isActive(now)) throw new AssertionError("fenêtre inactive à " + new java.util.Date(now));
    long invID = ServerInvasion.rotation(ServerInvasion.invasionStart(now));
    ServerInvasion.IInvasionProvider inv = ServerInvasionObject.at(now);

    ServerUser u = ServerUser.newPlayer(4242L, 1);
    UserInvasionData ud = ServerInvasion.newUserData(4242L, 0L, invID);

    // (1) La quête porte bien le combat ACTIF complet (activeBreakerFight), sinon écran injouable.
    BreakerQuest bq = ServerInvasionBreaker.buildQuest(u, ud, inv, invID);
    if (bq.basicBreakerFights.isEmpty()) throw new AssertionError("aucune salle dans la quête");
    if (bq.activeBreakerFight == null)
      throw new AssertionError("activeBreakerFight ABSENT — la BREAKER QUEST serait injouable (défaut trouvé en jeu)");
    if (bq.activeBreakerFight.index != ud.breakerBattlesWon)
      throw new AssertionError("activeBreakerFight.index attendu " + ud.breakerBattlesWon
          + ", obtenu " + bq.activeBreakerFight.index);
    if (bq.activeBreakerFight.breakerLineup == null || bq.activeBreakerFight.breakerLineup.isEmpty())
      throw new AssertionError("activeBreakerFight sans héros de breaker");
    System.out.println("[breaker] activeBreakerFight OK : index=" + bq.activeBreakerFight.index
        + " breakerLineup=" + bq.activeBreakerFight.breakerLineup.size()
        + " wardLineups=" + bq.activeBreakerFight.wardLineups.size()
        + " (" + bq.basicBreakerFights.size() + " salles)");

    // (2a) VICTOIRE room 1 → exerce le NOUVEAU code de points (aucune exception) + crédite les gains du jeu.
    u.giveResource(ResourceType.INVASION_STAMINA, 80);
    long stBefore = u.resourceAmount(ResourceType.INVASION_STAMINA);
    ServerInvasion.BreakerOutcome win = ServerInvasion.resolveBreakerFight(u, ud, 1, true, now);
    if (!win.accepted) throw new AssertionError("combat gagné refusé : " + win.refusal);
    if (win.level != 25) throw new AssertionError("BREAKER_FIGHT_LEVEL(1) attendu 25, obtenu " + win.level);
    if (win.gold != 1010) throw new AssertionError("BREAKER_FIGHT_GOLD_REWARD(1) attendu 1010, obtenu " + win.gold);
    if (win.breakers != 1) throw new AssertionError("BREAKER_FIGHT_BREAKER_REWARD attendu 1, obtenu " + win.breakers);
    if (win.points < 0) throw new AssertionError("points négatifs : " + win.points);
    if (ud.points != win.points) throw new AssertionError("ud.points non crédité (" + ud.points + " != " + win.points + ")");
    if (u.resourceAmount(ResourceType.INVASION_STAMINA) != stBefore - 10)
      throw new AssertionError("énergie non débitée de 10 sur victoire");
    if (u.resourceAmount(ResourceType.GOLD) != 1010) throw new AssertionError("or non crédité");
    if (u.resourceAmount(ResourceType.BREAKER) != 1) throw new AssertionError("BREAKER non crédité");
    System.out.println("[breaker] VICTOIRE room1 : " + win + " (voie de points exécutée sans exception ✔)");

    // (2b) DÉFAITE room 2 → énergie débitée, AUCUN gain (fidèle au jeu).
    long stBefore2 = u.resourceAmount(ResourceType.INVASION_STAMINA);
    long goldBefore2 = u.resourceAmount(ResourceType.GOLD);
    long ptsBefore2 = ud.points;
    ServerInvasion.BreakerOutcome loss = ServerInvasion.resolveBreakerFight(u, ud, 2, false, now);
    if (!loss.accepted) throw new AssertionError("combat perdu refusé : " + loss.refusal);
    if (loss.gold != 0 || loss.breakers != 0 || loss.points != 0)
      throw new AssertionError("une défaite ne doit RIEN accorder : " + loss);
    if (u.resourceAmount(ResourceType.INVASION_STAMINA) != stBefore2 - 10)
      throw new AssertionError("énergie non débitée sur défaite");
    if (u.resourceAmount(ResourceType.GOLD) != goldBefore2 || ud.points != ptsBefore2)
      throw new AssertionError("défaite : or/points ne doivent pas bouger");
    System.out.println("[breaker] DÉFAITE room2 : " + loss + " (énergie débitée, rien accordé ✔)");

    // ROOM 0 = 0 point (R=0 dans BREAKER_FIGHT_POINT_REWARD = 1R*M), comportement DU JEU.
    ServerUser u0 = ServerUser.newPlayer(4243L, 1);
    u0.giveResource(ResourceType.INVASION_STAMINA, 80);
    UserInvasionData ud0 = ServerInvasion.newUserData(4243L, 0L, invID);
    ServerInvasion.BreakerOutcome r0 = ServerInvasion.resolveBreakerFight(u0, ud0, 0, true, now);
    if (r0.points != 0) throw new AssertionError("room 0 devrait donner 0 point (1R*M, R=0), obtenu " + r0.points);
    if (r0.gold != 1000) throw new AssertionError("BREAKER_FIGHT_GOLD_REWARD(0) attendu 1000, obtenu " + r0.gold);
    System.out.println("[breaker] room0 : " + r0 + " (0 point fidèle : R=0 ✔)");

    System.out.println("[breaker] OK — activeBreakerFight fourni + voies victoire/défaite/room0 conformes au jeu.");
  }
}
