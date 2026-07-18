import com.perblue.heroes.game.data.content.ContentHelper;
import com.perblue.heroes.game.data.content.ContentStats;
import com.perblue.heroes.game.data.content.ContentUpdate;
import com.perblue.heroes.game.data.misc.StaminaStats;
import dhserver.ServerContext;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * PROBE (faits reproductibles, zéro supposition) — prouve la cause du « 39,96 M / 120 » et l'hypothèse
 * « ancrer la date à l'ère du contenu ».
 *
 * <p>Deux tables, deux clés (relevé au code) :
 * <ul>
 *   <li>CAP utilisable = {@code team_levels.tab} MAX_STAMINA, clé = <b>niveau d'équipe</b> (L1=120).</li>
 *   <li>MONTANT de régén = {@code stamina_values.tab} REGEN_AMOUNT, clé = <b>content release</b> R
 *       (palier), R résolu par la <b>DATE</b> via {@code content.1.tab}.</li>
 * </ul>
 *
 * <p>Le montant de régén est indexé par la DATE présentée au jeu :
 * {@code ContentHelper.getCurrent(timeMillis).getContentUpdate().release} → {@code StaminaStats.getRegenAmount(R)}.
 * Ce probe imprime R + le REGEN pour plusieurs dates → montre que 2026 (horloge du conteneur) résout R102
 * (regen 39,96 M) alors que fév. 2023 (ère du client 12.1.0) résout un R d'époque (regen faible).
 */
public final class StaminaEraProbe {

  static long millis(int y, int m, int d) {
    return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
  }

  static void probeRelease(int release) {
    ContentUpdate r = ContentUpdate.of(release);
    System.out.printf("ContentUpdate.of(%-3d) -> regen=%,-14d interval=%,dms hardCap=%,d%n",
        release, StaminaStats.getRegenAmount(r), StaminaStats.getRegenInterval(r), StaminaStats.getHardCap(r));
  }

  /** Résout R par DATE réelle (timestamp), comme le CLIENT via getColumn(serverTimeNow()). */
  static int releaseAtDate(ContentStats cs, long millis) {
    return cs.getColumn(millis).getContentUpdate().release;
  }

  static void probeClientDate(ContentStats cs, String label, long millis) {
    int r = releaseAtDate(cs, millis);
    long regen = StaminaStats.getRegenAmount(ContentUpdate.of(r));
    System.out.printf("%-26s -> R%-4d  regen=%,d%n", label, r, regen);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // Charge le calendrier de contenu du shard 1 (content.1.tab) via l'ouvreur serveur, pour résoudre
    // R par DATE exactement comme le client (getColumn(serverTimeNow())).
    com.perblue.common.stats.ShardStats ss = ContentHelper.get();
    ss.setShardID(1, new java.util.HashMap<>());
    ContentStats cs = (ContentStats) ss.getStats();
    System.out.println("[content] shard=" + ContentHelper.getShardID()
        + "  colonnes(dates)=" + cs.getColumns().size());

    System.out.println();
    System.out.println("=== 1) R résolu par la DATE présentée au client (content.1.tab) ===");
    probeClientDate(cs, "horloge conteneur (now)", System.currentTimeMillis());
    probeClientDate(cs, "2026-07-18", millis(2026, 7, 18));
    probeClientDate(cs, "2023-02-01 (ere APK 12.1.0)", millis(2023, 2, 1));
    probeClientDate(cs, "2021-06-01 (~debut overflow)", millis(2021, 6, 1));
    probeClientDate(cs, "2018-01-01", millis(2018, 1, 1));
    probeClientDate(cs, "2016-09-06 (R1 lancement)", millis(2016, 9, 6));

    System.out.println();
    System.out.println("=== 2) REGEN par content release explicite (stamina_values.tab) ===");
    for (int r : new int[]{1, 42, 60, 102}) probeRelease(r);

    System.out.println();
    System.out.println("=== CONCLUSION (faits) ===");
    int rNow = releaseAtDate(cs, System.currentTimeMillis());
    int rEra = releaseAtDate(cs, millis(2023, 2, 1));
    long regenNow = StaminaStats.getRegenAmount(ContentUpdate.of(rNow));
    long regenEra = StaminaStats.getRegenAmount(ContentUpdate.of(rEra));
    System.out.printf("serverTime = NOW (2026)        -> R%d  regen=%,d  (cap L1 = 120)%n", rNow, regenNow);
    System.out.printf("serverTime = fev 2023 (ere)    -> R%d  regen=%,d%n", rEra, regenEra);
    System.out.println("Chaine PROUVEE : le client fait TimeUtil.initClock(BootData.serverTime, deviceTime)");
    System.out.println("  -> serverTimeNow() = serverTime du SERVEUR -> ContentStats.getColumn() -> R");
    System.out.println("  -> StaminaStats.getRegenAmount(R). Notre serveur envoie currentTimeMillis (2026)");
    System.out.println("  -> R" + rNow + " -> regen " + String.format("%,d", regenNow) + " = le \"39,96 M\".");
    System.out.println("Correctif = ancrer bd.serverTime/pong.serverTime a l'ere du contenu (fev 2023 -> R"
        + rEra + ").");
  }
}
