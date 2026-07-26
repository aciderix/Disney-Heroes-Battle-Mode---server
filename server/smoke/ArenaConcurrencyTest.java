import com.perblue.heroes.network.messages.*;
import dhserver.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * ARÈNE — NON-RÉGRESSION d'INTERBLOCAGE (vrai PvP, multi-thread). Reproduit le scénario qui a figé le serveur en jeu :
 * deux ordres de verrous opposés en concurrence —
 *   - {@code store.save(user)} : verrouille le STORE puis sérialise l'user (store→user), et
 *   - {@code user.startArenaAttack(...)} (synchronisé sur l'user) qui charge un adversaire via {@code store}
 *     (user→store).
 * Avant le correctif (sérialisation wire SORTIE du verrou du store), ces deux boucles s'interbloquent. Le test
 * lance les deux en parallèle et exige qu'elles TERMINENT dans le délai imparti (sinon = interblocage = échec).
 */
public final class ArenaConcurrencyTest {

  static final int SHARD = 1;
  static final ArenaType TYPE = ArenaType.FIGHT_PIT;
  static final int ITERS = 150;
  static final long TIMEOUT_MS = 30_000;

  static ServerUser make(UserStore store, long id, UnitType[] def) throws Exception {
    ServerUser su = ServerUser.newPlayer(id, SHARD);
    for (UnitType t : def) su.grantHero(t);
    HeroLineupUpdate u = new HeroLineupUpdate();
    u.type = HeroLineupType.FIGHT_PIT_DEFENSE; u.iD = 0L; u.customName = "";
    u.lineup = new HeroLineup(); u.lineup.heroes = new ArrayList<>(Arrays.asList(def));
    u.lineup.mercenaryType = UnitType.DEFAULT;
    u.emeraldStatSlotChoices = new HashMap<>(); u.realGearOptions = new HashMap<>();
    su.applyHeroLineupUpdate(u);
    store.save(su);
    return su;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "server/smoke/out/conc-test.db";
    for (String s : new String[]{db, db + "-wal", db + "-shm"}) new java.io.File(s).delete();
    try (UserStore store = new UserStore(db)) {
      ServerUser attacker = make(store, 1L, new UnitType[]{UnitType.VANELLOPE, UnitType.BAYMAX, UnitType.HIRO, UnitType.OLAF, UnitType.ELSA});
      make(store, 2L, new UnitType[]{UnitType.WOODY, UnitType.SULLEY, UnitType.STITCH, UnitType.GENIE, UnitType.HERCULES});
      ServerArena.OpponentSource src = new StoreOpponentSource(store);
      ServerArenaLadder ladder = attacker.arenaInfoWithLadder(TYPE, null, src).ladder;
      store.saveArenaLadder(SHARD, TYPE.name(), ladder);

      AtomicInteger saves = new AtomicInteger(), starts = new AtomicInteger();
      AtomicReference<Throwable> err = new AtomicReference<>();

      Thread tSave = new Thread(() -> {           // store→user (save sérialise l'user)
        try { for (int i = 0; i < ITERS; i++) { store.save(attacker); saves.incrementAndGet(); } }
        catch (Throwable t) { err.compareAndSet(null, t); }
      }, "save-loop");
      Thread tAttack = new Thread(() -> {          // user→store (startArenaAttack charge un adversaire via store)
        try { for (int i = 0; i < ITERS; i++) { attacker.startArenaAttack(TYPE, 2L, ladder, src); starts.incrementAndGet(); } }
        catch (Throwable t) { err.compareAndSet(null, t); }
      }, "attack-loop");

      long t0 = System.currentTimeMillis();
      tSave.start(); tAttack.start();
      tSave.join(TIMEOUT_MS);
      tAttack.join(Math.max(1, TIMEOUT_MS - (System.currentTimeMillis() - t0)));

      if (tSave.isAlive() || tAttack.isAlive()) {
        System.out.println("[conc] ✖ INTERBLOCAGE : save=" + saves.get() + "/" + ITERS
            + " start=" + starts.get() + "/" + ITERS + " après " + TIMEOUT_MS + "ms");
        throw new AssertionError("INTERBLOCAGE détecté (threads toujours vivants) — ordre de verrous store/user");
      }
      if (err.get() != null) throw new AssertionError("erreur en thread : " + err.get());
      System.out.println("[conc] OK — " + saves.get() + " save + " + starts.get()
          + " startArenaAttack en parallèle, AUCUN interblocage (" + (System.currentTimeMillis() - t0) + "ms)");
    } finally {
      for (String s : new String[]{db, db + "-wal", db + "-shm"}) new java.io.File(s).delete();
    }
  }
}
