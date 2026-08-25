import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * RELEASE PICKER — preuve du DÉCOUPLAGE ère↔timers (headless). Régler l'offset d'ère de contenu :
 *   • change `bootData().contentStatsTimeOffset` (le client décale SON contenu daté) ;
 *   • NE change PAS `bootData().serverTime` (les timers/cooldowns/sauvegardes restent à l'heure réelle) ;
 *   • ne modifie pas `serverTimeNow()` (horloge serveur inchangée) → un cooldown posé « maintenant » reste inchangé.
 * ⇒ changer d'ère ne casse ni la sauvegarde ni les timers (contrairement à l'offset d'horloge d'AdminClock).
 */
public final class ReleaseOffsetTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[release] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long before = ServerContext.contentOffsetMillis();
    try {
      ServerContext.setContentOffsetMillis(0L);
      ServerUser su = ServerUser.newPlayer(9100L, 1);

      long realNow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      BootData bd0 = su.bootData();
      check(bd0.contentStatsTimeOffset == 0L, "offset 0 par défaut (=" + bd0.contentStatsTimeOffset + ")");
      check(Math.abs(bd0.serverTime - realNow) < 5000L, "serverTime = heure réelle (offset 0)");

      // Ère reculée de ~4 ans (comme choisir une release ancienne).
      long eraOffset = -4L * 365 * 24 * 3600_000L;
      ServerContext.setContentOffsetMillis(eraOffset);

      long realNow2 = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      BootData bd1 = su.bootData();
      check(bd1.contentStatsTimeOffset == eraOffset, "contentStatsTimeOffset reflète l'offset d'ère (=" + bd1.contentStatsTimeOffset + ")");
      // Le POINT CLÉ : serverTime (timers) reste à l'heure RÉELLE, PAS décalé de 4 ans.
      check(Math.abs(bd1.serverTime - realNow2) < 5000L, "serverTime (timers) RESTE à l'heure réelle malgré l'offset d'ère");
      check(bd1.serverTime + bd1.contentStatsTimeOffset < bd1.serverTime, "l'ère (serverTime+offset) est ANTÉRIEURE alors que les timers restent au présent");

      // serverTimeNow() (horloge serveur) inchangé par l'offset de contenu → cooldowns/resets intacts.
      check(Math.abs(realNow2 - realNow) < 10_000L, "serverTimeNow() inchangé par l'offset d'ère (horloge découplée)");

      System.out.println("[release] découplage vérifié : contentStatsTimeOffset=" + eraOffset
          + " ms ; serverTime(timers) reste réel ; serverTimeNow() inchangé ✔");
      System.out.println("[release] OK — changer d'ère ne casse ni sauvegarde ni timers (headless).");
    } finally {
      ServerContext.setContentOffsetMillis(before);
    }
  }
}
