import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * ARÈNE — RÉGÉN des combats (reset QUOTIDIEN, fidèle au jeu : {@code ArenaHelper.getNextDailyUpdateTime}).
 *  (1) si un reset quotidien a été franchi depuis {@code lastFightReset} → toutes les entrées repassent à MAX ;
 *  (2) sinon (dernier reset récent) → aucun changement ;
 *  (3) {@code lastFightReset} SURVIT au round-trip d'octets (persistance v2).
 */
public final class ArenaFightResetTest {

  static final ArenaType TYPE = ArenaType.FIGHT_PIT;

  static ServerArenaLadder ladderWithSpentFights() {
    ServerArenaLadder l = new ServerArenaLadder();
    for (int i = 0; i < 3; i++) {
      ServerArenaLadder.Entry e = new ServerArenaLadder.Entry();
      e.id = 900000L + i; e.name = "Rival " + i; e.bot = true;
      e.remainingFightChances = 0;                       // combats ÉPUISÉS
      l.entries().add(e);
    }
    return l;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long now = System.currentTimeMillis();
    long DAY = 24L * 60 * 60 * 1000;

    // (1) dernier reset il y a 2 jours → un reset quotidien a forcément été franchi → refill à MAX.
    ServerArenaLadder l1 = ladderWithSpentFights();
    l1.lastFightReset = now - 2 * DAY;
    boolean did = ServerArena.maybeDailyReset(l1, TYPE, now);
    if (!did) throw new AssertionError("un reset quotidien aurait dû être appliqué (dernier reset il y a 2 jours)");
    for (ServerArenaLadder.Entry e : l1.entries())
      if (e.remainingFightChances != ServerArena.MAX_FIGHTS)
        throw new AssertionError("après reset, combats attendus=" + ServerArena.MAX_FIGHTS + " a=" + e.remainingFightChances);
    if (l1.lastFightReset != now) throw new AssertionError("lastFightReset doit être mis à jour");
    System.out.println("[reset] (1) reset quotidien franchi → combats refaits à " + ServerArena.MAX_FIGHTS + "/joueur");

    // (2) dernier reset = maintenant → aucun reset (on n'a pas encore franchi le prochain 21h).
    ServerArenaLadder l2 = ladderWithSpentFights();
    l2.lastFightReset = now;
    boolean did2 = ServerArena.maybeDailyReset(l2, TYPE, now);
    if (did2) throw new AssertionError("aucun reset ne devait être appliqué (dernier reset à l'instant)");
    for (ServerArenaLadder.Entry e : l2.entries())
      if (e.remainingFightChances != 0)
        throw new AssertionError("sans reset, combats doivent rester épuisés (0), a=" + e.remainingFightChances);
    System.out.println("[reset] (2) reset récent → combats INCHANGÉS (pas de régén prématurée)");

    // (3) persistance : lastFightReset survit au round-trip d'octets (format v2).
    ServerArenaLadder l3 = ladderWithSpentFights();
    l3.lastFightReset = 1234567890123L;
    ServerArenaLadder back = ServerArenaLadder.fromBytes(l3.toBytes());
    if (back.lastFightReset != 1234567890123L)
      throw new AssertionError("lastFightReset non persisté : " + back.lastFightReset);
    if (back.entries().size() != 3) throw new AssertionError("entrées non persistées");
    System.out.println("[reset] (3) lastFightReset persisté (round-trip octets v2) = " + back.lastFightReset);

    System.out.println("[reset] OK — régén des combats par reset quotidien (fidèle) + persistance");
  }
}
