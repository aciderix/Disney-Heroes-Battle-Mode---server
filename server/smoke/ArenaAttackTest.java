import com.perblue.heroes.network.messages.*;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import dhserver.ServerArenaLadder;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * ARÈNE #44 — combat PvP autoritatif. Prouve le protocole capturé en jeu :
 *  (1) START ({@code startArenaAttack}) → {@code StartArenaAttackResponse} avec les HÉROS DU DÉFENSEUR (HeroData) →
 *      wire round-trip valide (le client rejoue le combat avec) ;
 *  (2) RÉSULTAT ({@code resolveArenaAttack}) → sur VICTOIRE le RANG monte (swap dans le ladder), points crédités,
 *      chances de combat décrémentées ; sur DÉFAITE le rang ne change pas mais une chance est consommée ;
 *      {@code ArenaUpdate} valide + wire round-trip.
 */
public final class ArenaAttackTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE,
        UnitType.MERIDA, UnitType.MAUI})
      su.grantHero(t);

    ServerArenaLadder ladder = su.arenaInfoWithLadder(ArenaType.FIGHT_PIT, null).ladder;
    int n = ladder.entries().size();
    if (n < 3) throw new AssertionError("ladder trop court");
    long myID = 1L;
    int myRankBefore = ladder.indexOf(myID) + 1;                 // toi = dernier (provisoire) → rang n
    long defenderID = ladder.entries().get(n - 2).id;            // le bot JUSTE au-dessus de toi
    System.out.println("[atk] rang avant=" + myRankBefore + " défenseur=" + defenderID + " (juste au-dessus)");

    // (1) START → StartArenaAttackResponse avec les héros du défenseur.
    Object resp = su.startArenaAttack(ArenaType.FIGHT_PIT, defenderID, ladder);
    if (!(resp instanceof StartArenaAttackResponse)) throw new AssertionError("réponse START inattendue: " + resp);
    StartArenaAttackResponse sr = (StartArenaAttackResponse) resp;
    if (sr.defendingUserID != defenderID) throw new AssertionError("defendingUserID");
    if (sr.heroes == null || sr.heroes.size() != 5)
      throw new AssertionError("le défenseur doit avoir 5 héros (HeroData), a=" + (sr.heroes == null ? 0 : sr.heroes.size()));
    // wire round-trip de la réponse START
    GruntOutputStream o1 = new GruntOutputStream(); sr.writeAll(o1);
    StartArenaAttackResponse srBack = (StartArenaAttackResponse) MessageFactory.getInstance()
        .readMessage(new GruntInputStream(o1.getBytes()));
    if (srBack.heroes.size() != 5) throw new AssertionError("héros défenseur non conservés au wire");
    System.out.println("[atk] START → StartArenaAttackResponse : " + sr.heroes.size()
        + " héros défenseur (HeroData), round-trip wire OK");

    // (2a) RÉSULTAT = VICTOIRE → rang monte, points, 1 chance consommée.
    ArenaUpdate up = su.resolveArenaAttack(defenderID, /*win*/ true, ArenaType.FIGHT_PIT, ladder);
    int myRankAfter = up.yourLeague.yourRank;
    System.out.println("[atk] VICTOIRE → rang " + myRankBefore + " → " + myRankAfter);
    if (myRankAfter >= myRankBefore)
      throw new AssertionError("victoire contre un mieux classé DOIT faire monter le rang (" + myRankBefore + "→" + myRankAfter + ")");
    ServerArenaLadder.Entry me = ladder.entries().get(ladder.indexOf(myID));
    if (me.points <= 0) throw new AssertionError("points non crédités après victoire");
    if (me.remainingFightChances != 4)
      throw new AssertionError("1 chance de combat doit être consommée (5→4), a=" + me.remainingFightChances);
    // wire round-trip de l'ArenaUpdate
    GruntOutputStream o2 = new GruntOutputStream(); up.writeAll(o2);
    ArenaUpdate upBack = (ArenaUpdate) MessageFactory.getInstance()
        .readMessage(new GruntInputStream(o2.getBytes()));
    if (upBack.yourLeague.yourRank != myRankAfter) throw new AssertionError("rang non conservé au wire");
    System.out.println("[atk] ArenaUpdate : points=" + me.points + " fights=" + me.remainingFightChances
        + ", round-trip wire OK");

    // (2b) RÉSULTAT = DÉFAITE → rang inchangé, mais 1 chance de plus consommée.
    int rankBeforeLoss = up.yourLeague.yourRank;
    long strongerDefender = ladder.entries().get(0).id;           // le n°1
    ArenaUpdate up2 = su.resolveArenaAttack(strongerDefender, /*win*/ false, ArenaType.FIGHT_PIT, ladder);
    if (up2.yourLeague.yourRank != rankBeforeLoss)
      throw new AssertionError("une DÉFAITE ne doit pas changer le rang");
    ServerArenaLadder.Entry me2 = ladder.entries().get(ladder.indexOf(myID));
    if (me2.remainingFightChances != 3)
      throw new AssertionError("2ᵉ combat doit consommer une chance (4→3), a=" + me2.remainingFightChances);
    System.out.println("[atk] DÉFAITE → rang inchangé (" + rankBeforeLoss + "), fights=" + me2.remainingFightChances);

    System.out.println("[atk] OK — #44 : START (héros défenseur) + résolution victoire/défaite (rang/points/fights) + ArenaUpdate wire");
  }
}
