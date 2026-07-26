import com.perblue.heroes.network.messages.*;
import dhserver.*;

import java.util.*;

/**
 * ARÈNE — VRAI PvP contre de VRAIS joueurs du shard (pas seulement des bots). Prouve, tout via la base :
 *  (1) le classement inclut les AUTRES comptes du shard qui ont posé une défense ({@code bot=false}), + complément bots ;
 *  (2) la row d'un vrai adversaire est bâtie depuis SA défense RÉELLE (les héros qu'il a posés), pas en synthétique ;
 *  (3) {@code startArenaAttack} sert les héros de défense RÉELS du défenseur ;
 *  (4) VICTOIRE contre un vrai joueur mieux classé → l'attaquant MONTE et le défenseur DESCEND (les DEUX côtés), dans
 *      le classement PARTAGÉ + PERSISTANT → le défenseur voit son rang chuter à sa prochaine ouverture ;
 *  (5) DÉFAITE → aucun changement de rang, mais une chance consommée.
 */
public final class ArenaRealPvPTest {

  static final int SHARD = 1;
  static final ArenaType TYPE = ArenaType.FIGHT_PIT;

  static ServerUser makePlayer(UserStore store, long id, String name, UnitType[] def) throws Exception {
    ServerUser su = ServerUser.newPlayer(id, SHARD);
    for (UnitType t : def) su.grantHero(t);
    HeroLineupUpdate u = new HeroLineupUpdate();
    u.type = HeroLineupType.FIGHT_PIT_DEFENSE; u.iD = 0L; u.customName = "";
    u.lineup = new HeroLineup();
    u.lineup.heroes = new ArrayList<>(Arrays.asList(def));
    u.lineup.mercenaryType = UnitType.DEFAULT;
    u.emeraldStatSlotChoices = new HashMap<>();
    u.realGearOptions = new HashMap<>();
    if (!su.applyHeroLineupUpdate(u)) throw new AssertionError("défense non posée pour " + name);
    store.save(su);
    return su;
  }

  static Set<UnitType> rowLineupTypes(ArenaInfo ai, long id) {
    for (Object o : ai.yourLeague.players) {
      ArenaRow r = (ArenaRow) o;
      if (r.playerRow != null && r.playerRow.info != null && r.playerRow.info.iD == id) {
        Set<UnitType> out = new HashSet<>();
        for (Object h : r.lineup) out.add(((HeroSummary) h).type);
        return out;
      }
    }
    return null;
  }

  static int rankOf(ArenaInfo ai, long id) {
    List<Object> ps = new ArrayList<>(ai.yourLeague.players);
    for (int i = 0; i < ps.size(); i++) {
      ArenaRow r = (ArenaRow) ps.get(i);
      if (r.playerRow != null && r.playerRow.info != null && r.playerRow.info.iD == id) return i + 1;
    }
    return -1;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = "server/smoke/out/pvp-test.db";
    new java.io.File(db).delete();
    new java.io.File(db + "-wal").delete();
    new java.io.File(db + "-shm").delete();

    try (UserStore store = new UserStore(db)) {
      // Trois VRAIS comptes du shard, chacun avec une défense DISTINCTE posée.
      UnitType[] defA = {UnitType.VANELLOPE, UnitType.BAYMAX, UnitType.HIRO, UnitType.OLAF, UnitType.ELSA};
      UnitType[] def2 = {UnitType.WOODY, UnitType.SULLEY, UnitType.STITCH, UnitType.GENIE, UnitType.HERCULES};
      UnitType[] def3 = {UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MERIDA, UnitType.MAUI};
      ServerUser attacker = makePlayer(store, 1L, "Attacker", defA);
      makePlayer(store, 2L, "DefenderTwo", def2);
      makePlayer(store, 3L, "DefenderThree", def3);
      System.out.println("[pvp] 3 vrais comptes créés+persistés (défenses distinctes)");

      ServerArena.OpponentSource src = new StoreOpponentSource(store);

      // (1) Classement de l'attaquant : contient les VRAIS joueurs 2 & 3 (bot=false) + complément bots + toi.
      ServerUser.ArenaResult ar = attacker.arenaInfoWithLadder(TYPE, null, src);
      ServerArenaLadder ladder = ar.ladder;
      boolean has2 = false, has3 = false, hasBot = false;
      for (ServerArenaLadder.Entry e : ladder.entries()) {
        if (e.id == 2L) { has2 = true; if (e.bot) throw new AssertionError("le joueur 2 ne doit PAS être un bot"); }
        if (e.id == 3L) { has3 = true; if (e.bot) throw new AssertionError("le joueur 3 ne doit PAS être un bot"); }
        if (e.bot) hasBot = true;
      }
      if (!has2 || !has3) throw new AssertionError("les vrais joueurs 2 & 3 doivent être dans le classement");
      if (!hasBot) throw new AssertionError("complément de bots attendu (peu de vrais joueurs)");
      System.out.println("[pvp] (1) classement = vrais joueurs 2&3 (bot=false) + bots + toi : "
          + ladder.entries().size() + " entrées");

      // (2) La row du joueur 2 utilise SA défense RÉELLE (def2), pas une équipe synthétique.
      Set<UnitType> row2 = rowLineupTypes(ar.info, 2L);
      if (row2 == null) throw new AssertionError("row du joueur 2 absente");
      if (!row2.equals(new HashSet<>(Arrays.asList(def2))))
        throw new AssertionError("row du joueur 2 = " + row2 + " ≠ sa défense réelle " + Arrays.toString(def2));
      Set<UnitType> row3 = rowLineupTypes(ar.info, 3L);
      if (!row3.equals(new HashSet<>(Arrays.asList(def3))))
        throw new AssertionError("row du joueur 3 ≠ sa défense réelle");
      System.out.println("[pvp] (2) rows des vrais joueurs = LEUR défense réelle (2=" + row2 + ")");

      // (3) START attaque du joueur 2 → ses héros de défense RÉELS (HeroData).
      Object resp = attacker.startArenaAttack(TYPE, 2L, ladder, src);
      if (!(resp instanceof StartArenaAttackResponse)) throw new AssertionError("réponse START inattendue");
      StartArenaAttackResponse sr = (StartArenaAttackResponse) resp;
      Set<UnitType> served = new HashSet<>();
      for (Object h : sr.heroes) served.add(((HeroData) h).type);
      if (!served.equals(new HashSet<>(Arrays.asList(def2))))
        throw new AssertionError("START a servi " + served + " ≠ défense réelle du joueur 2 " + Arrays.toString(def2));
      System.out.println("[pvp] (3) START(joueur 2) → ses 5 héros de défense RÉELS : " + served);

      // (4) VICTOIRE contre le joueur 2 (mieux classé) → attaquant MONTE, joueur 2 DESCEND (les deux), points, 1 chance.
      int myRankBefore = rankOf(ar.info, 1L);
      int def2RankBefore = rankOf(ar.info, 2L);
      if (!(def2RankBefore < myRankBefore))
        throw new AssertionError("pré-condition : le joueur 2 doit être MIEUX classé que toi (" + def2RankBefore + " vs " + myRankBefore + ")");
      ArenaUpdate up = attacker.resolveArenaAttack(2L, /*win*/ true, TYPE, ladder, src);
      int myRankAfter = rankOf2(up, 1L);
      int def2RankAfter = rankOf2(up, 2L);
      System.out.println("[pvp] (4) VICTOIRE : toi " + myRankBefore + "→" + myRankAfter
          + " ; joueur 2 " + def2RankBefore + "→" + def2RankAfter);
      if (myRankAfter >= myRankBefore) throw new AssertionError("l'attaquant doit MONTER");
      if (def2RankAfter <= def2RankBefore) throw new AssertionError("le défenseur (joueur 2) doit DESCENDRE");
      if (myRankAfter != def2RankBefore || def2RankAfter != myRankBefore)
        throw new AssertionError("victoire fight-pit = ÉCHANGE des rangs attaquant/défenseur");
      ServerArenaLadder.Entry meE = ladder.entries().get(ladder.indexOf(1L));
      if (meE.points <= 0) throw new AssertionError("points non crédités à l'attaquant");
      if (meE.remainingFightChances != ServerArena.MAX_FIGHTS - 1)
        throw new AssertionError("1 chance doit être consommée");
      // PERSISTE le classement partagé (comme le fait LoginServer).
      store.saveArenaLadder(SHARD, TYPE.name(), ladder);
      System.out.println("[pvp] (4) OK — swap des DEUX côtés + points + chance consommée, classement persisté");

      // (4b) CÔTÉ DÉFENSEUR : le joueur 2 rouvre l'arène (même classement partagé, rechargé de la DB) → il voit son
      // rang CHUTÉ. C'est la preuve « des deux côtés ».
      ServerUser defender2 = store.loadOrCreate(2L, SHARD);
      ServerArenaLadder shared = store.loadArenaLadder(SHARD, TYPE.name());
      ServerUser.ArenaResult ar2 = defender2.arenaInfoWithLadder(TYPE, shared, src);
      int def2SeesOwnRank = ar2.info.yourLeague.yourRank;
      System.out.println("[pvp] (4b) le joueur 2 rouvre l'arène → il se voit rang " + def2SeesOwnRank
          + " (était " + def2RankBefore + " avant de perdre sa place)");
      if (def2SeesOwnRank <= def2RankBefore)
        throw new AssertionError("le défenseur doit voir son rang AVOIR CHUTÉ après avoir été battu");

      // (5) DÉFAITE contre le joueur 3 → rang inchangé, 1 chance de plus consommée.
      int rankBeforeLoss = ladder.indexOf(1L) + 1;
      ArenaUpdate up2 = attacker.resolveArenaAttack(3L, /*win*/ false, TYPE, ladder, src);
      int rankAfterLoss = up2.yourLeague.yourRank;
      if (rankAfterLoss != rankBeforeLoss) throw new AssertionError("une DÉFAITE ne change pas le rang");
      ServerArenaLadder.Entry meE2 = ladder.entries().get(ladder.indexOf(1L));
      if (meE2.remainingFightChances != ServerArena.MAX_FIGHTS - 2)
        throw new AssertionError("2ᵉ combat doit consommer une 2ᵉ chance");
      System.out.println("[pvp] (5) DÉFAITE → rang inchangé (" + rankAfterLoss + "), 2ᵉ chance consommée");

      System.out.println("[pvp] OK — VRAI PvP : vrais joueurs classés, défense réelle lue, victoire/défaite correctes DES DEUX CÔTÉS, persisté");
    } finally {
      new java.io.File(db).delete();
      new java.io.File(db + "-wal").delete();
      new java.io.File(db + "-shm").delete();
    }
  }

  static int rankOf2(ArenaUpdate up, long id) {
    List<Object> ps = new ArrayList<>(up.yourLeague.players);
    for (int i = 0; i < ps.size(); i++) {
      ArenaRow r = (ArenaRow) ps.get(i);
      if (r.playerRow != null && r.playerRow.info != null && r.playerRow.info.iD == id) return i + 1;
    }
    return -1;
  }
}
