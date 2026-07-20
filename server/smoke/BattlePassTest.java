import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.BattlePassV2Helper;
import com.perblue.heroes.game.data.battlepass.BattlePassV2Stats;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * BATTLE PASS V2 — rend l'écran fonctionnel côté serveur (vérif en jeu à TL11, feature verrouillée avant) :
 * <ul>
 *   <li><b>Saison roulante mensuelle</b> : les constantes de saison (fixes/passées dans la `.tab`) sont
 *       ancrées au mois courant → {@code battlePassHidden()} = false, saison ACTIVE (now dans la fenêtre).</li>
 *   <li><b>Premium pour tous</b> : {@code BootData.battlePassV2Data.boughtBattlePass = 1} → premium débloqué.</li>
 *   <li>Le calcul des paliers/récompenses reste la <b>logique du jeu</b> (non modifiée).</li>
 * </ul>
 */
public final class BattlePassTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long now = System.currentTimeMillis();

    // 1) SAISON ACTIVE (roulante) : battlePassHidden() faux, saison ancrée au mois courant.
    boolean hidden = BattlePassV2Helper.battlePassHidden();
    long start = BattlePassV2Stats.getSeasonStartTime();
    long hide  = BattlePassV2Stats.getBattlePassHiddenTime();
    System.out.println("[bp] battlePassHidden=" + hidden + " | saison start=" + start + " hide=" + hide
        + " now=" + now);
    if (hidden) throw new AssertionError("le battle pass ne devrait PAS être caché (saison ancrée au mois courant)");
    if (!(start <= now && now < hide))
      throw new AssertionError("now devrait être DANS la fenêtre de saison [" + start + ", " + hide + ")");
    // Rolling : start = 1er du mois courant.
    java.util.Calendar c = java.util.Calendar.getInstance();
    c.set(java.util.Calendar.DAY_OF_MONTH, 1); c.set(java.util.Calendar.HOUR_OF_DAY, 0);
    c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
    if (start != c.getTimeInMillis())
      throw new AssertionError("la saison devrait démarrer au 1er du mois courant (" + c.getTimeInMillis() + "), obtenu " + start);
    System.out.println("[bp] saison ACTIVE, ancrée au 1er du mois courant (roulante)");

    // 2) BootData : premium pour tous + saison transmise.
    ServerUser su = ServerUser.newPlayer(1L, 1);
    BootData bd = su.bootData();
    BattlePassV2Data d = bd.battlePassV2Data;
    System.out.println("[bp] BootData.battlePassV2Data : type=" + d.type + " boughtBattlePass=" + d.boughtBattlePass
        + " startTime=" + d.startTime + " endTime=" + d.endTime + " progress=" + d.progress);
    if (d.type != BattlePassType.QUEST) throw new AssertionError("type devrait être QUEST (seul implémenté par le client 12.1.0)");
    if (d.boughtBattlePass <= 0) throw new AssertionError("premium (boughtBattlePass) devrait être débloqué pour tous");
    if (d.startTime != start || d.endTime != hide) throw new AssertionError("startTime/endTime devraient être la saison courante");

    // 3) Le calcul des récompenses du jeu ne crashe pas (paliers de la saison active).
    java.util.List<?> tiers = BattlePassV2Stats.getRewardTiers(start);
    int maxTier = BattlePassV2Stats.getMaxTier(start);
    System.out.println("[bp] paliers de récompense (saison) = " + (tiers == null ? 0 : tiers.size()) + " | maxTier=" + maxTier);
    if (tiers == null || tiers.isEmpty()) throw new AssertionError("la saison active devrait exposer des paliers de récompense");

    System.out.println("[bp] OK — saison roulante mensuelle ACTIVE + premium pour tous + paliers exposés (logique du jeu)");
  }
}
