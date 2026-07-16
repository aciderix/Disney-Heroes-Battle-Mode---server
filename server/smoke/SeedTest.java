import com.perblue.heroes.network.messages.*;
import dhserver.*;

import java.util.HashMap;

/**
 * Smoke test du handler {@code Action SET_SEED} (SERVER_PLAN §Partiels C). Avant chaque combat, le client
 * (combat client-autoritatif) annonce la GRAINE RNG qu'il a utilisée, par type ({@code RandomSeedType} :
 * COMBAT, LOOT…) dans {@code Action{SET_SEED, extra={ID=<graine>, TYPE=<type>}}}. Le serveur la mémorise
 * (état de session, éphémère) pour pouvoir REPRODUIRE/valider le tirage plus tard (re-sim combat §D, re-roll
 * loot §E). Ici : deux SET_SEED (COMBAT, LOOT) → {@code getPendingSeed} renvoie les bonnes graines.
 */
public final class SeedTest {

  @SuppressWarnings("unchecked")
  static Action setSeed(long seed, RandomSeedType type) {
    Action m = new Action();
    m.command = CommandType.SET_SEED;
    m.extra = new HashMap();
    m.extra.put(ActionExtraType.ID, Long.toString(seed));
    m.extra.put(ActionExtraType.TYPE, type.name());
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    long combatSeed = -8273347794875362245L, lootSeed = 7323988583130461974L;
    boolean okC = su.applyAction(setSeed(combatSeed, RandomSeedType.COMBAT));
    boolean okL = su.applyAction(setSeed(lootSeed, RandomSeedType.LOOT));

    Long gotC = su.getPendingSeed(RandomSeedType.COMBAT);
    Long gotL = su.getPendingSeed(RandomSeedType.LOOT);
    Long gotChest = su.getPendingSeed(RandomSeedType.CHEST);   // jamais envoyée → null
    System.out.println("[seed] applied COMBAT=" + okC + " LOOT=" + okL
        + " | pending COMBAT=" + gotC + " LOOT=" + gotL + " CHEST=" + gotChest);

    if (!okC || !okL) throw new AssertionError("applyAction(SET_SEED) a renvoyé false");
    if (gotC == null || gotC != combatSeed) throw new AssertionError("graine COMBAT non mémorisée : " + gotC);
    if (gotL == null || gotL != lootSeed) throw new AssertionError("graine LOOT non mémorisée : " + gotL);
    if (gotChest != null) throw new AssertionError("graine CHEST non envoyée mais présente : " + gotChest);

    System.out.println("SEED TEST OK (SET_SEED COMBAT=" + combatSeed + " + LOOT=" + lootSeed
        + " mémorisées via getPendingSeed — prêt pour la re-sim/re-roll autoritatif §D/§E)");
  }
}
