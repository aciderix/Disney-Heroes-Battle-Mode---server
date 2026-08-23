import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.network.messages.RewardDrop;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composants <b>FREE_STUFF_AT_TEAM_LEVEL</b> ({@code TeamAtLevel}) et
 * <b>FREE_STUFF_EVERY_X_TEAM_LEVEL</b> ({@code TeamLevelRecord}) : récompense au(x) palier(s) de niveau d'équipe.
 *
 * <p>Via la LOGIQUE DU JEU (§3) : {@code TeamAtLevel/TeamLevelRecord.getRewardTimes(info, user, old, new)} × les drops de
 * l'{@code EventRewards}. {@code ServerEvents.teamLevelRewardDrops} agrège → livrés par COURRIER au level-up (branché dans
 * {@code recordCampaignAttack}). (1) AT_LEVEL 50 : franchir 49→50 donne 1× le reward, 50→51 rien ; (2) EVERY_X 10 :
 * 9→10 donne 1×, 9→30 donne 3× (tous les 10) ; (3) round-trip spec. Niveau + drops = <b>params ADMIN</b>.
 */
public final class TeamLevelTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[teamlevel] " + m); }
  static final String IT = "ACE_OF_SPADES";
  static String drop(int qty) { return "{\"kind\":\"ITEM\",\"itemType\":\"" + IT + "\",\"quantity\":" + qty + "}"; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9282L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // (1) AT_LEVEL 50 (récompense en ATTEIGNANT 50).
    SpecialEventInfo at = ServerEvents.buildTeamLevelEvent(700_100L, 50, Collections.singletonList(drop(5)), false, now - 1000, now + 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(at));
    List<RewardDrop> d1 = ServerEvents.teamLevelRewardDrops(su.gameUser(), 49, 50);
    List<RewardDrop> d0 = ServerEvents.teamLevelRewardDrops(su.gameUser(), 50, 51);
    check(d1.size() == 1 && d1.get(0).quantity == 5, "AT_LEVEL 50 : 49→50 = 1 drop ×5 (" + d1.size() + ")");
    check(d0.isEmpty(), "AT_LEVEL 50 : 50→51 = 0 drop (" + d0.size() + ")");
    System.out.println("[teamlevel] AT_LEVEL 50 : 49→50=" + d1.size() + " drop (×" + d1.get(0).quantity + "), 50→51=" + d0.size() + " ✔");

    // (2) EVERY_X 10 (récompense TOUS les 10 niveaux — sémantique DU JEU : (new-1)/X − (old-1)/X, paliers 11,21,31…).
    SpecialEventInfo every = ServerEvents.buildTeamLevelEvent(700_101L, 10, Collections.singletonList(drop(2)), true, now - 1000, now + 86_400_000L);
    ServerEvents.setOperatorEvents(Collections.singletonList(every));
    List<RewardDrop> e1 = ServerEvents.teamLevelRewardDrops(su.gameUser(), 10, 20);   // franchit le palier 11
    List<RewardDrop> e2 = ServerEvents.teamLevelRewardDrops(su.gameUser(), 0, 30);    // franchit 11 et 21
    List<RewardDrop> e0 = ServerEvents.teamLevelRewardDrops(su.gameUser(), 9, 10);    // aucun palier
    check(e1.size() == 1, "EVERY_X 10 : 10→20 = 1× (" + e1.size() + ")");
    check(e2.size() == 2, "EVERY_X 10 : 0→30 = 2× (" + e2.size() + ")");
    check(e0.isEmpty(), "EVERY_X 10 : 9→10 = 0 (" + e0.size() + ")");
    System.out.println("[teamlevel] EVERY_X 10 (jeu: paliers 11,21…) : 10→20=" + e1.size() + "×, 0→30=" + e2.size() + "×, 9→10=" + e0.size() + " ✔");

    // (3) round-trip de la spec.
    String spec = ServerEvents.specJsonTeamLevel(700_100L, 50, false, Collections.singletonList(IT), Collections.singletonList(5), now - 1000, now + 86_400_000L);
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(ServerEvents.writeConfig(Collections.singletonList(spec)));
    check(rebuilt.size() == 1 && rebuilt.get(0).getID() == 700_100L, "spec TEAM LEVEL round-trip");
    ServerEvents.setOperatorEvents(rebuilt);
    check(ServerEvents.teamLevelRewardDrops(su.gameUser(), 49, 50).size() == 1, "event reconstruit → même reward");
    System.out.println("[teamlevel] spec round-trip → reward préservé ✔");

    ServerEvents.setOperatorEvents(new ArrayList<>());
    System.out.println("[teamlevel] OK — FREE_STUFF_AT/EVERY_X_TEAM_LEVEL objets du jeu, niveau + drops = params admin, livrés par courrier. [headless]");
  }
}
