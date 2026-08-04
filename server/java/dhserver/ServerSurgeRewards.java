package dhserver;

import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.game.data.surge.SurgeStats;
import com.perblue.heroes.game.logic.SurgeHelper;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.SurgeData;
import com.perblue.heroes.network.messages.SurgeMemberSummary;
import com.perblue.heroes.network.messages.SurgeResultInfo;
import com.perblue.heroes.network.messages.SurgeRewards;
import com.perblue.heroes.ui.surge.SurgeClientHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * SURGE (#72) incrément 6 — RÉCOMPENSES (or/tokens personnels + influence de guilde) et bascule de surge.
 *
 * <p><b>Tout est calculé par le CODE DU JEU (§3/§4), zéro valeur inventée</b> :
 * <ul>
 *   <li><b>tokens</b> (monnaie {@code CRYPT_TOKENS}) = {@code SurgeClientHelper.getPlayerSurgeCoins(surge)}
 *       = {@code getTokensPerClearedRegion() × régions + getBaseTokens()} (régions = vagues×3 + régions de la
 *       vague courante). Prouvé au bytecode (offsets 0-30 de {@code getPlayerSurgeCoins}).</li>
 *   <li><b>or</b> ({@code GOLD}) = {@code member.storedGold} (accumulé par {@code recordOutcome→storeGold} à chaque
 *       combat).</li>
 *   <li><b>influence</b> (ressource de GUILDE {@code GUILD_INFLUENCE}) = {@code SurgeHelper.getInfluenceProgress(surge)
 *       + SurgeStats.getBaseInfluence()} — la somme EXACTE affichée par {@code SurgeClearedWindow} (offsets 96-102 :
 *       {@code getInfluenceProgress + getBaseInfluence}).</li>
 * </ul>
 *
 * <p><b>Flux CLIENT prouvé (SurgeResultsWindow / SurgeScreen, disasm) — le serveur le MIROITE côté autorité :</b>
 * le serveur pose un {@link SurgeRewards} par membre dans {@code SurgeData.unclaimedRewards[surgeID_terminé]}
 * (livré par {@code GetSurge}). Le client ouvre la fenêtre de résultats si {@code totalGold≠0 || totalTokens≠0},
 * et au bouton « réclamer » il envoie {@code SurgeClaimRewards{surgeID}} PUIS se crédite localement
 * {@code UserHelper.giveUser(CRYPT_TOKENS, totalTokens)} + {@code (GOLD, totalGold)} (offsets 163/249,
 * {@code RewardSourceType.NORMAL}). Le serveur applique le MÊME crédit de façon autoritative, une seule fois
 * (anti double-réclamation via le registre {@link Ledger}).
 *
 * <p><b>Bascule (rollover)</b> : quand le surgeID change ({@link ServerSurgeState#loadOrReset}), l'état du surge
 * TERMINÉ est figé dans un registre {@code surgeprev:<guildID>} (résultats + récompense par membre + set réclamé),
 * l'influence est créditée UNE fois à la guilde, et le nouveau {@link SurgeData} embarque {@code previousResults}.
 * Comme la bascule de saison de guerre / le reset d'invasion : calculée paresseusement au prochain accès.
 */
public final class ServerSurgeRewards {

  private ServerSurgeRewards() {}

  static String ledgerKey(long guildID) { return "surgeprev:" + guildID; }

  // ---------------------------------------------------------------- calcul des récompenses (code du jeu) ----

  /** Récompense PERSONNELLE d'un membre pour {@code surge} : tokens (jeu) + or stocké. Jamais nulle (wire-sûr). */
  public static SurgeRewards rewardFor(SurgeData surge, SurgeMemberSummary member) {
    ServerContext.init();
    SurgeRewards r = new SurgeRewards();
    r.surgeID = surge.surgeID;
    r.baseTokens = SurgeStats.getBaseTokens();
    r.totalTokens = SurgeClientHelper.getPlayerSurgeCoins(surge);   // jeu : T×régions + baseTokens
    r.baseGold = 0L;                                                // pas de base d'or (l'or vient des combats)
    r.totalGold = member != null ? member.storedGold : 0L;          // or accumulé par recordOutcome→storeGold
    return r;
  }

  /** Influence de GUILDE gagnée sur ce surge = {@code getInfluenceProgress + getBaseInfluence} (code du jeu). */
  public static long guildInfluenceFor(SurgeData surge) {
    ServerContext.init();
    return SurgeHelper.getInfluenceProgress(surge) + SurgeStats.getBaseInfluence();
  }

  /** Résultats du surge terminé (informationnel : {@code previousResults}). Champs directement dérivables du jeu ;
   *  collections non nulles ; {@code achievedTier} laissé à 0 (non prouvé headless — pas d'invention §4). */
  public static SurgeResultInfo resultFor(SurgeData surge) {
    SurgeResultInfo ri = new SurgeResultInfo();
    ri.surgeID = surge.surgeID;
    ri.endTime = surge.raidEndTime;
    ri.totalPointsGained = surge.totalPointsGained;
    ri.totalInfluenceGained = guildInfluenceFor(surge);
    ri.wavesCompleted = surge.wavesCompleted;
    ri.waveRegionsCleared = surge.waveRegionsCleared != null ? surge.waveRegionsCleared.size() : 0;
    ri.basePointsNeeded = surge.basePointsNeeded;
    ri.achievedTier = 0;
    ri.highlights = new java.util.ArrayList<>();
    ri.memberResults = new java.util.HashMap<>();
    return ri;
  }

  // ---------------------------------------------------------------- registre de bascule (persisté) ----------

  /** Registre du surge PRÉCÉDENT d'une guilde : résultats + récompense figée par membre + set réclamé. Persisté
   *  à part ({@code surgeprev:<guildID>}) pour ne pas toucher au codec round-trip-testé de {@link SurgeData}. */
  public static final class Ledger {
    static final int VERSION = 1;
    public long prevSurgeID;
    public SurgeResultInfo result;
    public final java.util.Map<Long, SurgeRewards> rewards = new java.util.HashMap<>();
    public final java.util.Set<Long> claimed = new java.util.HashSet<>();

    byte[] encode() {
      try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bos);
        o.writeInt(VERSION);
        o.writeLong(prevSurgeID);
        writeMsg(o, result);
        o.writeInt(rewards.size());
        for (java.util.Map.Entry<Long, SurgeRewards> e : rewards.entrySet()) {
          o.writeLong(e.getKey());
          writeMsg(o, e.getValue());
        }
        o.writeInt(claimed.size());
        for (Long id : claimed) o.writeLong(id);
        o.flush();
        return bos.toByteArray();
      } catch (Exception ex) { throw new RuntimeException("sérialisation registre surge échouée", ex); }
    }

    static Ledger decode(byte[] b) {
      try {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        Ledger l = new Ledger();
        in.readInt();                       // version (compat future)
        l.prevSurgeID = in.readLong();
        l.result = (SurgeResultInfo) readMsg(in);
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
          long id = in.readLong();
          l.rewards.put(id, (SurgeRewards) readMsg(in));
        }
        int c = in.readInt();
        for (int i = 0; i < c; i++) l.claimed.add(in.readLong());
        return l;
      } catch (Exception ex) { throw new RuntimeException("lecture registre surge échouée", ex); }
    }
  }

  private static void writeMsg(DataOutputStream o, com.perblue.grunt.translate.GruntMessage msg) throws Exception {
    if (msg == null) { o.writeInt(-1); return; }
    GruntOutputStream g = new GruntOutputStream();
    msg.writeAll(g);
    byte[] wire = g.getBytes();
    o.writeInt(wire.length);
    o.write(wire);
  }

  private static com.perblue.grunt.translate.GruntMessage readMsg(DataInputStream in) throws Exception {
    int len = in.readInt();
    if (len < 0) return null;
    byte[] wire = new byte[len];
    in.readFully(wire);
    return MessageFactory.getInstance().readMessage(new GruntInputStream(wire));
  }
}
