import com.perblue.heroes.network.messages.*;
import dhserver.*;

import java.util.Arrays;
import java.util.List;

/**
 * GUILD WAR #68 — ÉTAPE 5 : attaques, conséquences et SCORE.
 *
 * <p>Prouve : (a) les contrôles de {@code doStartWarAttack} sont ré-exécutés (phase, appartenance, salle
 * ouverte, 2 attaques max, attaque supplémentaire gatée par le rôle ET le crédit de guilde), (b) une vague
 * gagnée met le lineup KO définitivement et fait bouger étoiles et portes, (c) le score suit le barème
 * ÉCRIT PAR LE CLIENT ({@code points = compte × scalar}), (d) les trois bonus de voiture ne rapportent que
 * si la voiture SURVIT, (e) l'issue se déduit des totaux, (f) journaux et score survivent au round-trip DB.
 */
public final class WarAttackTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  static WarMemberInfo member(long userID, String name, WarCarType car, int lineups) {
    WarMemberInfo m = new WarMemberInfo();
    BasicUserInfo bi = new BasicUserInfo();
    bi.iD = userID; bi.name = name; bi.teamLevel = 45;
    m.userInfo = bi;
    m.assignedCar = car;
    for (int i = 0; i < lineups; i++) {
      WarLineupSummary l = new WarLineupSummary();
      for (int h = 0; h < 5; h++) {
        WarHeroSummary hs = new WarHeroSummary();
        hs.defeated = false; hs.sabotage = WarSabotageType.DEFAULT;
        l.heroes.add(hs);
      }
      m.defenses.add(l);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  static void addMember(WarGuildInfo side, WarMemberInfo m) { side.members.put(m.userInfo.iD, m); }

  static AttackStageResult wave(boolean win) {
    AttackStageResult r = new AttackStageResult();
    r.outcome = win ? CombatOutcome.WIN : CombatOutcome.LOSS;
    r.stars = win ? 3 : 0;
    return r;
  }

  static List<AttackStageResult> sweep() { return Arrays.asList(wave(true), wave(true), wave(true)); }
  static List<AttackStageResult> partial() { return Arrays.asList(wave(true), wave(false), wave(false)); }
  static List<AttackStageResult> failed() { return Arrays.asList(wave(false), wave(false), wave(false)); }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-war-atk", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      int reqTL = com.perblue.heroes.game.data.misc.Unlockables.getTeamLevelReq(
          com.perblue.heroes.game.data.misc.Unlockable.WAR, 1);

      ServerUser rulerA = ServerUser.newPlayer(1L, 1);
      rulerA.giveResource(ResourceType.GOLD, 5000);
      rulerA.basicInfo().teamLevel = reqTL;
      ServerGuild ga = rulerA.createGuild(mk("Attaquants"), store.nextGuildID(1));
      ServerWar.rollOverSeason(ga, ServerWar.seasonIDAt(now), 0);
      ServerUser rulerB = ServerUser.newPlayer(2L, 1);
      rulerB.giveResource(ResourceType.GOLD, 5000);
      rulerB.basicInfo().teamLevel = reqTL;
      ServerGuild gb = rulerB.createGuild(mk("Defenseurs"), store.nextGuildID(1));
      ServerWar.rollOverSeason(gb, ServerWar.seasonIDAt(now), 0);
      store.saveGuild(ga); store.saveGuild(gb); store.save(rulerA); store.save(rulerB);

      // Guerre en phase de BATAILLE, deux camps garnis.
      ServerWarState w = new ServerWarState();
      w.shardID = 1; w.seasonID = ServerWar.seasonIDAt(now);
      w.startTime = now; w.endTime = now + ServerWarMatchmaker.warDuration();
      w.state = WarSummaryState.ACTIVE; w.stateEndTime = w.endTime;
      w.guildAID = ga.guildID; w.guildBID = gb.guildID;

      WarCarType room0 = ServerWarCars.GARAGE_ORDER.get(0);   // étage 1 — toujours ouvert
      WarCarType room3 = ServerWarCars.GARAGE_ORDER.get(3);   // étage 2 — fermé au départ

      WarGuildInfo sideA = new WarGuildInfo();
      sideA.guildInfo = ga.info.basicInfo;
      sideA.extraAttacksRemaining = 1; sideA.extraAttacksTotal = 1;
      addMember(sideA, member(1L, "ChefA", room0, 3));
      ServerWarCars.rebuildCars(sideA);

      WarGuildInfo sideB = new WarGuildInfo();
      sideB.guildInfo = gb.info.basicInfo;
      WarMemberInfo defender = member(2L, "ChefB", room0, 3);
      WarMemberInfo upstairs = member(3L, "EtageB", room3, 3);
      addMember(sideB, defender); addMember(sideB, upstairs);
      // ⚠️ CONSÉQUENCE FIDÈLE de « a room with no defenders is automatically defeated » : une salle VIDE
      // est déjà « prise », donc laisser l'étage 1 dégarni OUVRE immédiatement l'étage 2. Pour éprouver le
      // verrouillage d'étage il faut donc défendre les TROIS salles du rez-de-chaussée. (Premier jet de ce
      // test : étage 1 à moitié vide → étage 2 déjà ouvert, et c'était le test qui avait tort, pas la règle.)
      addMember(sideB, member(6L, "GardeB2", ServerWarCars.GARAGE_ORDER.get(1), 3));
      addMember(sideB, member(7L, "GardeB3", ServerWarCars.GARAGE_ORDER.get(2), 3));
      ServerWarCars.rebuildCars(sideB);

      w.putSide(ga.guildID, sideA);
      w.putSide(gb.guildID, sideB);

      // ---------------------------------------------------------------------------------------
      // 1. VALIDATIONS — celles de doStartWarAttack, ré-exécutées.
      // ---------------------------------------------------------------------------------------
      // Hors phase de bataille : refusé.
      w.state = WarSummaryState.SABOTAGE;
      check(!ServerWarAttack.validateStart(w, ga, rulerA, 2L, now).ok(),
          "attaquer pendant la phase de sabotage doit être refusé");
      w.state = WarSummaryState.ACTIVE;

      // Salle d'étage 2 encore fermée : refusé.
      ServerWarAttack.StartResult closed = ServerWarAttack.validateStart(w, ga, rulerA, 3L, now);
      check(!closed.ok(), "attaquer une salle d'étage fermé doit être refusé");
      System.out.println("[war] salle d'étage 2 fermée → attaque refusée (" + closed.error + ")");

      // Un joueur absent de la guerre : refusé.
      ServerUser latecomer = ServerUser.newPlayer(9L, 1);
      latecomer.basicInfo().teamLevel = reqTL;
      latecomer.joinGuildAs(ga.guildID, GuildRole.MEMBER);
      check(!ServerWarAttack.validateStart(w, ga, latecomer, 2L, now).ok(),
          "un joueur arrivé après le début de la guerre ne doit pas pouvoir attaquer");
      System.out.println("[war] joueur arrivé en retard → attaque refusée (WAR_JOINED_LATE)");

      // Défenseur inexistant : refusé.
      check(!ServerWarAttack.validateStart(w, ga, rulerA, 999L, now).ok(), "défenseur inconnu refusé");

      // Attaque LÉGITIME.
      ServerWarAttack.StartResult first = ServerWarAttack.validateStart(w, ga, rulerA, 2L, now);
      check(first.ok(), "la 1re attaque doit être autorisée : " + first.error);
      check(!first.usesExtraAttack, "la 1re attaque n'est pas une attaque supplémentaire");

      StartWarAttackResponse resp = ServerWarAttack.buildStartResponse(w, ga.guildID, 2L);
      check(resp.currentCar == room0, "la réponse doit désigner la salle du défenseur");
      check(resp.lineup0 != null && !resp.lineup0.defeated, "le 1er lineup de défense doit être servi");
      check(resp.lineup0.defenders.size() == 5, "5 défenseurs par lineup, obtenu "
          + resp.lineup0.defenders.size());
      check(resp.activeCars.size() == ServerWarCars.GARAGE_SIZE - countTaken(sideB),
          "les salles ENCORE DEBOUT doivent être listées");
      System.out.println("[war] START → salle " + resp.currentCar + ", 3 lineups servis, "
          + resp.activeCars.size() + " salles actives");

      // ---------------------------------------------------------------------------------------
      // 2. ENREGISTREMENT — une vague gagnée met le lineup KO définitivement.
      // ---------------------------------------------------------------------------------------
      ServerWarAttack.consumeAttack(w, ga, rulerA, first.usesExtraAttack);
      check(ServerWarAttack.attacksUsed(w, rulerA) == 1, "une attaque doit être décomptée");
      ServerWarAttack.recordAttack(w, ga.guildID, rulerA.basicInfo(), 2L, partial(), false, now);

      WarGuildInfo bAfter = w.sideOf(gb.guildID);
      WarMemberInfo defAfter = (WarMemberInfo) bAfter.members.get(2L);
      check(ServerWarCars.lineupDefeated((WarLineupSummary) defAfter.defenses.get(0)),
          "la vague gagnée doit mettre le lineup 0 KO");
      check(!ServerWarCars.lineupDefeated((WarLineupSummary) defAfter.defenses.get(1)),
          "un lineup non battu doit rester debout");
      WarCarInfo bRoom0 = (WarCarInfo) bAfter.cars.get(room0);
      check(bRoom0.starsEarned == 1 && bRoom0.starsTotal == 3,
          "1 étoile prise sur 3, obtenu " + bRoom0.starsEarned + "/" + bRoom0.starsTotal);
      check(!ServerWarCars.carDefeated(bRoom0), "la salle n'est pas encore prise");
      System.out.println("[war] attaque partielle : lineup 0 KO, salle " + bRoom0.starsEarned + "/"
          + bRoom0.starsTotal);

      // Score après cette attaque : 1 lineup pris.
      // ⚠️ FAIT DU JEU à énoncer explicitement : « Rooms that have no defenders are automatically defeated
      // and worth 100 points TO THE OTHER SIDE ». Les 5 salles que B a laissées vides valent donc DÉJÀ
      // 5 × POINTS_PER_CAR à A, avant même la moindre attaque. On calcule l'attendu depuis l'état plutôt
      // que de l'écrire en dur — c'est le comportement fidèle, pas un artefact.
      int emptyRoomsB = countTaken(w.sideOf(gb.guildID));
      check(emptyRoomsB == ServerWarCars.GARAGE_SIZE - 4,
          "B défend 4 salles → " + (ServerWarCars.GARAGE_SIZE - 4) + " salles vides, obtenu " + emptyRoomsB);
      WarOutcomeSummary sa = ServerWarScoring.summaryFor(w, ga.guildID);
      check(sa.lineupsDefeated == 1, "1 lineup battu, obtenu " + sa.lineupsDefeated);
      check(sa.lineupsDefeatedScalar == ServerWar.pointsPerLineup(), "barème lineup = POINTS_PER_LINEUP");
      check(sa.roomsDefeated == emptyRoomsB,
          "les salles VIDES comptent déjà comme prises (attendu " + emptyRoomsB + ", obtenu "
              + sa.roomsDefeated + ")");
      check(sa.cleanSweeps == 0, "une attaque partielle n'est pas un balayage parfait");
      System.out.println("[war] salles laissées vides par B : " + emptyRoomsB + " → déjà "
          + (emptyRoomsB * ServerWar.pointsPerCar()) + " points pour A (règle du jeu)");
      // Le défenseur a repoussé 2 vagues → 2 défenses victorieuses pour B.
      WarOutcomeSummary sb = ServerWarScoring.summaryFor(w, gb.guildID);
      check(sb.defensiveWins == 2, "2 vagues repoussées = 2 défenses victorieuses, obtenu " + sb.defensiveWins);
      System.out.println("[war] score A : " + sa.lineupsDefeated + " lineup(s) · score B : "
          + sb.defensiveWins + " défense(s) victorieuse(s)");

      // ---------------------------------------------------------------------------------------
      // 3. LIMITE DE 2 ATTAQUES + attaque SUPPLÉMENTAIRE gatée.
      // ---------------------------------------------------------------------------------------
      ServerWarAttack.StartResult second = ServerWarAttack.validateStart(w, ga, rulerA, 2L, now);
      check(second.ok(), "la 2e attaque doit être possible pour un RULER avec du crédit : " + second.error);
      check(second.usesExtraAttack, "la 2e attaque EST une attaque supplémentaire");

      // Un rôle non habilité ne l'obtient pas : on ferme le rang aux OFFICER+ et on teste un MEMBER.
      ga.setWarExtraAttackRank(GuildRole.OFFICER);
      ServerUser plainMember = ServerUser.newPlayer(4L, 1);
      plainMember.basicInfo().teamLevel = reqTL;
      plainMember.joinGuildAs(ga.guildID, GuildRole.MEMBER);
      WarGuildInfo aSide = w.sideOf(ga.guildID);
      addMember(aSide, member(4L, "SimpleA", room0, 3));
      w.putSide(ga.guildID, aSide);
      // Il consomme d'abord son attaque gratuite.
      ServerWarAttack.StartResult free4 = ServerWarAttack.validateStart(w, ga, plainMember, 2L, now);
      check(free4.ok() && !free4.usesExtraAttack, "l'attaque gratuite du membre doit passer");
      ServerWarAttack.consumeAttack(w, ga, plainMember, false);
      ServerWarAttack.StartResult extra4 = ServerWarAttack.validateStart(w, ga, plainMember, 2L, now);
      check(!extra4.ok(), "un MEMBER ne doit pas avoir droit à l'attaque supplémentaire (rang OFFICER)");
      System.out.println("[war] attaque supplémentaire refusée au MEMBER (" + extra4.error + ")");

      // Crédit de guilde épuisé → refus même pour le chef.
      aSide = w.sideOf(ga.guildID);
      aSide.extraAttacksRemaining = 0;
      w.putSide(ga.guildID, aSide);
      check(!ServerWarAttack.validateStart(w, ga, rulerA, 2L, now).ok(),
          "sans crédit d'attaque supplémentaire, le chef doit être refusé");
      aSide = w.sideOf(ga.guildID); aSide.extraAttacksRemaining = 1; w.putSide(ga.guildID, aSide);
      System.out.println("[war] crédit d'attaques supplémentaires épuisé → refusé");

      // La 2e attaque du chef : BALAYAGE PARFAIT, qui achève la salle.
      ServerWarAttack.consumeAttack(w, ga, rulerA, true);
      check(ServerWarAttack.attacksUsed(w, rulerA) == 2, "2 attaques consommées");
      ServerWarAttack.recordAttack(w, ga.guildID, rulerA.basicInfo(), 2L, sweep(), true, now + 1);
      check(!ServerWarAttack.validateStart(w, ga, rulerA, 2L, now).ok(),
          "au-delà de " + ServerWarAttack.MAX_ATTACKS_PER_WAR + " attaques, tout doit être refusé");
      check(w.sideOf(ga.guildID).extraAttacksRemaining == 0, "le crédit doit avoir été débité");

      bAfter = w.sideOf(gb.guildID);
      bRoom0 = (WarCarInfo) bAfter.cars.get(room0);
      check(ServerWarCars.carDefeated(bRoom0), "après le balayage, la salle doit être PRISE ("
          + bRoom0.starsEarned + "/" + bRoom0.starsTotal + ")");
      check(!ServerWarCars.closedGarageDoors(bAfter, WarSummaryState.ACTIVE, false)[3],
          "prendre une salle de l'étage 1 doit ouvrir l'étage 2");
      System.out.println("[war] balayage parfait → salle PRISE et étage 2 ouvert");

      // ---------------------------------------------------------------------------------------
      // 4. BARÈME — points = compte × scalar (l'arithmétique du client).
      // ---------------------------------------------------------------------------------------
      sa = ServerWarScoring.summaryFor(w, ga.guildID);
      check(sa.roomsDefeated == emptyRoomsB + 1,
          "les salles vides + la salle nettoyée, attendu " + (emptyRoomsB + 1) + ", obtenu " + sa.roomsDefeated);
      check(sa.roomsDefeatedScalar == ServerWar.pointsPerCar(), "barème salle = POINTS_PER_CAR");
      check(sa.lineupsDefeated == 3, "3 lineups battus au total, obtenu " + sa.lineupsDefeated);
      check(sa.cleanSweeps == 1, "1 balayage parfait, obtenu " + sa.cleanSweeps);
      int expected = sa.lineupsDefeated * sa.lineupsDefeatedScalar
          + sa.roomsDefeated * sa.roomsDefeatedScalar
          + sa.cleanSweeps * sa.cleanSweepsScalar
          + sa.defensiveWins * sa.defensiveWinsScalar
          + sa.cleanDefenses * sa.cleanDefensesScalar;
      check(ServerWarScoring.totalPoints(sa) == expected,
          "le total doit être la SOMME des compte × scalar (arithmétique de WarOutcomeWindow)");
      check(w.sideOf(ga.guildID).totalPoints == expected,
          "le total doit être écrit dans le camp : attendu " + expected + ", trouvé "
              + w.sideOf(ga.guildID).totalPoints);
      WarPointsUpdate upd = ServerWarScoring.toPointsUpdate(w, ga.guildID);
      check(upd.totalPoints == expected && upd.carDefeatedPoints == sa.roomsDefeated * sa.roomsDefeatedScalar,
          "WarPointsUpdate doit refléter le même barème");
      System.out.println("[war] barème A : " + sa.lineupsDefeated + "×" + sa.lineupsDefeatedScalar
          + " + " + sa.roomsDefeated + "×" + sa.roomsDefeatedScalar
          + " + balayages " + sa.cleanSweeps + "×" + sa.cleanSweepsScalar
          + " = " + ServerWarScoring.totalPoints(sa) + " points");

      // ---------------------------------------------------------------------------------------
      // 5. « SI LA VOITURE SURVIT » — le bonus tombe à zéro si la salle est prise.
      // ---------------------------------------------------------------------------------------
      // La salle POINT_PER_CLEAN_SWEEP de A est VIDE → comptée comme prise → barème nul.
      check(sa.cleanSweepsScalar == 0,
          "salle de balayage vide (donc « prise ») → barème 0, obtenu " + sa.cleanSweepsScalar);
      // On y place un défenseur : la voiture survit, le barème devient celui du jeu.
      aSide = w.sideOf(ga.guildID);
      addMember(aSide, member(5L, "GardienSweep", WarCarType.POINT_PER_CLEAN_SWEEP, 3));
      ServerWarCars.rebuildCars(aSide);
      w.putSide(ga.guildID, aSide);
      sa = ServerWarScoring.summaryFor(w, ga.guildID);
      int gameValue = Math.round(com.perblue.heroes.game.logic.WarHelper.getCarValue(
          WarCarType.POINT_PER_CLEAN_SWEEP,
          ((WarCarInfo) w.sideOf(ga.guildID).cars.get(WarCarType.POINT_PER_CLEAN_SWEEP)).level).floatValue());
      check(sa.cleanSweepsScalar == gameValue,
          "voiture DEBOUT → barème = valeur du jeu (" + gameValue + "), obtenu " + sa.cleanSweepsScalar);
      System.out.println("[war] « if your car survives » : barème de balayage 0 (salle prise) → "
          + gameValue + " (salle défendue)");

      // ---------------------------------------------------------------------------------------
      // 6. ISSUE — déduite des totaux.
      // ---------------------------------------------------------------------------------------
      WarSummaryState outA = ServerWarScoring.outcomeFor(w, ga.guildID);
      WarSummaryState outB = ServerWarScoring.outcomeFor(w, gb.guildID);
      int ptsA = ServerWarScoring.totalPoints(ServerWarScoring.summaryFor(w, ga.guildID));
      int ptsB = ServerWarScoring.totalPoints(ServerWarScoring.summaryFor(w, gb.guildID));
      // L'issue suit les totaux, dans les DEUX sens — on n'écrit pas de vainqueur attendu en dur.
      check((ptsA > ptsB) == (outA == WarSummaryState.VICTORY), "issue de A incohérente avec les totaux");
      check((ptsB > ptsA) == (outB == WarSummaryState.VICTORY), "issue de B incohérente avec les totaux");
      check(ptsA == ptsB
              ? outA == WarSummaryState.DRAW && outB == WarSummaryState.DRAW
              : outA != outB,
          "hors égalité, les deux camps doivent avoir des issues opposées : " + outA + " / " + outB);
      // Enseignement de ce scénario : A n'a garni QUE 2 salles, B en a garni 4 → B encaisse gratuitement
      // plus de salles vides que A, et mène malgré les attaques de A. C'est la règle du jeu qui parle.
      int emptyRoomsA = countTaken(w.sideOf(ga.guildID));
      check(emptyRoomsA > emptyRoomsB, "A doit laisser plus de salles vides que B dans ce scénario");
      System.out.println("[war] issue : A " + outA + " (" + ptsA + " pts, " + emptyRoomsA
          + " salles vides) · B " + outB + " (" + ptsB + " pts, " + emptyRoomsB + " salles vides)");

      // Cas NUL, sur un état symétrique minimal (les deux garages entièrement vides).
      ServerWarState even = new ServerWarState();
      even.shardID = 1; even.guildAID = ga.guildID; even.guildBID = gb.guildID;
      even.state = WarSummaryState.ACTIVE;
      WarGuildInfo evenA = new WarGuildInfo(); evenA.guildInfo = ga.info.basicInfo;
      WarGuildInfo evenB = new WarGuildInfo(); evenB.guildInfo = gb.info.basicInfo;
      ServerWarCars.rebuildCars(evenA); ServerWarCars.rebuildCars(evenB);
      even.putSide(ga.guildID, evenA); even.putSide(gb.guildID, evenB);
      check(ServerWarScoring.outcomeFor(even, ga.guildID) == WarSummaryState.DRAW
              && ServerWarScoring.outcomeFor(even, gb.guildID) == WarSummaryState.DRAW,
          "à égalité de points, les deux camps doivent être en DRAW");
      // Et un BYE reste un BYE.
      ServerWarState byeW = new ServerWarState();
      byeW.guildAID = ga.guildID; byeW.guildBID = 0;
      byeW.putSide(ga.guildID, evenA);
      check(ServerWarScoring.outcomeFor(byeW, ga.guildID) == WarSummaryState.BYE, "un BYE reste un BYE");
      System.out.println("[war] égalité → DRAW des deux côtés · guerre sans adversaire → BYE");

      // ---------------------------------------------------------------------------------------
      // 7. JOURNAUX + PERSISTANCE.
      // ---------------------------------------------------------------------------------------
      check(w.attacksBy(ga.guildID).size() == 2, "2 attaques journalisées côté A, obtenu "
          + w.attacksBy(ga.guildID).size());
      check(w.attacksAgainst(gb.guildID).size() == 2, "B doit voir 2 attaques subies");
      check(w.attacksBy(gb.guildID).isEmpty(), "B n'a mené aucune attaque");
      WarLogAttack logged = w.attacksBy(ga.guildID).get(1);
      check(logged.usedExtraAttack, "la 2e attaque doit être marquée « supplémentaire »");
      check(logged.warCar == room0 && logged.defender.iD == 2L, "le journal doit porter salle et défenseur");
      check(logged.waves.size() == 3, "3 vagues journalisées");

      store.saveWar(w);
      ServerWarState rw = store.loadWar(1, w.warID);
      check(rw.attacksBy(ga.guildID).size() == 2, "les journaux doivent survivre au round-trip DB");
      check(rw.attacksBy(ga.guildID).get(1).usedExtraAttack, "le drapeau d'attaque supplémentaire persiste");
      check(rw.sideOf(ga.guildID).totalPoints == w.sideOf(ga.guildID).totalPoints,
          "le score doit persister");
      WarOutcomeSummary rsa = ServerWarScoring.summaryFor(rw, ga.guildID);
      check(ServerWarScoring.totalPoints(rsa) == ServerWarScoring.totalPoints(sa),
          "le score doit se RECALCULER à l'identique depuis l'état relu");
      System.out.println("[war] round-trip DB : 2 attaques journalisées, score recalculé à l'identique ("
          + ServerWarScoring.totalPoints(rsa) + " pts)");

      System.out.println("WAR ATTACK TEST OK");
    }
  }

  static int countTaken(WarGuildInfo side) {
    int n = 0;
    for (WarCarType car : ServerWarCars.GARAGE_ORDER) {
      if (ServerWarCars.carDefeated((WarCarInfo) side.cars.get(car))) n++;
    }
    return n;
  }
}
