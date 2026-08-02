package dhserver;

import com.perblue.heroes.game.logic.WarHelper;
import com.perblue.heroes.network.messages.CombatOutcome;
import com.perblue.heroes.network.messages.WarCarInfo;
import com.perblue.heroes.network.messages.WarCarType;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarLogAttack;
import com.perblue.heroes.network.messages.WarLogAttackWave;
import com.perblue.heroes.network.messages.WarLineupSummary;
import com.perblue.heroes.network.messages.WarMemberInfo;
import com.perblue.heroes.network.messages.WarOutcomeSummary;
import com.perblue.heroes.network.messages.WarPointsUpdate;

/**
 * GUILD WAR (#68) — LE SCORE.
 *
 * <p><b>Le barème n'est pas inventé : c'est le client qui l'écrit.</b> {@code WarOutcomeWindow} affiche
 * chaque ligne du récapitulatif en faisant {@code points = compte × scalar} et en cumulant le tout
 * (« {@code n14 = n12 * n13; n11 += n14;} »). Les cinq couples {@code X}/{@code Xscalar} de
 * {@link WarOutcomeSummary} sont donc le barème complet, et le TOTAL est leur somme. Aucune classe cliente
 * ne REMPLIT ces champs (seuls {@code WarLogsWindow} et {@code WarOutcomeWindow} les lisent) : c'est au
 * serveur de les calculer.
 *
 * <p><b>Les cinq catégories, telles que l'aide du jeu les définit</b>
 * ({@code HowToPlay.properties}, carte « Scoring ») :
 * <ul>
 *   <li><b>Lineups battus</b> — « Defeating an enemy Hero team earns your Guild one point » →
 *       barème {@code POINTS_PER_LINEUP} ;</li>
 *   <li><b>Salles nettoyées</b> — « Every enemy room your Guild clears … is worth 100 points » →
 *       {@code POINTS_PER_CAR} ;</li>
 *   <li><b>Balayages parfaits</b> — « A clean sweep is when you win all 3 rounds of a War attack », et les
 *       points ne tombent QUE « if your guild's Sharky car survives the War » ;</li>
 *   <li><b>Défenses victorieuses</b> — « each time an enemy failed to defeat a lineup », si l'Off Roader
 *       survit ;</li>
 *   <li><b>Défenses parfaites</b> — « when a player's 3 lineups each have 1 or more heroes left standing »,
 *       si la Roller Diva survit.</li>
 * </ul>
 * Les trois derniers barèmes valent donc le bonus de la voiture correspondante — lu dans les données du jeu
 * par {@code WarHelper.getCarValue} — <b>ou zéro si cette voiture a été prise</b>.
 *
 * <p>Tout se RECALCULE depuis l'état (défenses + journaux d'attaques) : aucun compteur n'est tenu en double,
 * donc rien ne peut dériver.
 */
public final class ServerWarScoring {

  private ServerWarScoring() {}

  /** Barème d'une voiture à bonus de points, ou 0 si la salle a été prise par l'ennemi. */
  public static int carPointScalar(WarGuildInfo side, WarCarType car) {
    if (side.cars == null) return 0;
    WarCarInfo info = (WarCarInfo) side.cars.get(car);
    if (info == null) return 0;
    if (ServerWarCars.carDefeated(info)) return 0;      // « if your … car survives the War »
    Number value = WarHelper.getCarValue(car, info.level);
    return value == null ? 0 : Math.round(value.floatValue());
  }

  /** Une attaque est un BALAYAGE PARFAIT si ses 3 vagues sont gagnées (« win all 3 rounds »). */
  public static boolean isCleanSweep(WarLogAttack attack) {
    if (attack == null || attack.waves == null || attack.waves.isEmpty()) return false;
    for (Object o : attack.waves) {
      if (!((WarLogAttackWave) o).victory) return false;
    }
    return true;
  }

  /** Une DÉFENSE PARFAITE : les 3 lineups du joueur gardent chacun au moins un héros debout. */
  public static boolean isCleanDefense(WarMemberInfo member) {
    if (member == null || member.defenses == null || member.defenses.isEmpty()) return false;
    for (Object o : member.defenses) {
      if (ServerWarCars.lineupDefeated((WarLineupSummary) o)) return false;
    }
    return true;
  }

  /**
   * Récapitulatif de {@code guildID} : ce que SON camp a accompli.
   *
   * @param w l'état de guerre ; tout est recalculé depuis lui
   */
  public static WarOutcomeSummary summaryFor(ServerWarState w, long guildID) {
    WarOutcomeSummary s = new WarOutcomeSummary();
    WarGuildInfo mine = w.sideOf(guildID);
    WarGuildInfo theirs = w.enemySideOf(guildID);
    if (mine == null) return s;
    s.guildInfo = mine.guildInfo;

    // — Ce que J'AI pris chez l'ennemi.
    int lineups = 0, rooms = 0;
    if (theirs.cars != null) {
      for (WarCarType car : ServerWarCars.GARAGE_ORDER) {
        WarCarInfo info = (WarCarInfo) theirs.cars.get(car);
        if (info == null) continue;
        lineups += info.starsEarned;
        if (ServerWarCars.carDefeated(info)) rooms++;
      }
    }
    s.lineupsDefeated = lineups;
    s.lineupsDefeatedScalar = ServerWar.pointsPerLineup();
    s.roomsDefeated = rooms;
    s.roomsDefeatedScalar = ServerWar.pointsPerCar();

    // — Balayages parfaits : mes attaques dont les 3 vagues sont gagnées.
    int sweeps = 0;
    for (WarLogAttack a : w.attacksBy(guildID)) {
      if (isCleanSweep(a)) sweeps++;
    }
    s.cleanSweeps = sweeps;
    s.cleanSweepsScalar = carPointScalar(mine, WarCarType.POINT_PER_CLEAN_SWEEP);

    // — Défenses victorieuses : chaque vague où un ennemi a ÉCHOUÉ contre mes lineups.
    int defWins = 0;
    for (WarLogAttack a : w.attacksAgainst(guildID)) {
      if (a.waves == null) continue;
      for (Object o : a.waves) {
        WarLogAttackWave wave = (WarLogAttackWave) o;
        if (!wave.victory && !wave.skipped) defWins++;
      }
    }
    s.defensiveWins = defWins;
    s.defensiveWinsScalar = carPointScalar(mine, WarCarType.POINT_PER_DEFENSE_WIN);

    // — Défenses parfaites : mes joueurs dont les 3 lineups tiennent encore.
    int clean = 0;
    if (mine.members != null) {
      for (Object o : mine.members.values()) {
        if (isCleanDefense((WarMemberInfo) o)) clean++;
      }
    }
    s.cleanDefenses = clean;
    s.cleanDefensesScalar = carPointScalar(mine, WarCarType.POINT_PER_FULL_DEFENSE);

    // — Consommation d'attaques.
    s.attacks = w.attacksBy(guildID).size();
    s.attacksTotal = mine.members == null ? 0 : mine.members.size();
    int extras = 0;
    for (WarLogAttack a : w.attacksBy(guildID)) if (a.usedExtraAttack) extras++;
    s.extraAttacks = extras;
    s.extraAttacksTotal = mine.extraAttacksTotal;
    return s;
  }

  /**
   * Total de points d'un récapitulatif — <b>somme des {@code compte × scalar}</b>, exactement l'arithmétique
   * que {@code WarOutcomeWindow} applique pour l'afficher.
   */
  public static int totalPoints(WarOutcomeSummary s) {
    return s.lineupsDefeated * s.lineupsDefeatedScalar
        + s.roomsDefeated * s.roomsDefeatedScalar
        + s.cleanSweeps * s.cleanSweepsScalar
        + s.defensiveWins * s.defensiveWinsScalar
        + s.cleanDefenses * s.cleanDefensesScalar;
  }

  /** Recalcule et ÉCRIT le total dans le camp de {@code guildID}. Retourne le total. */
  public static int refreshTotalPoints(ServerWarState w, long guildID) {
    WarOutcomeSummary s = summaryFor(w, guildID);
    int total = totalPoints(s);
    WarGuildInfo side = w.sideOf(guildID);
    if (side != null) {
      side.totalPoints = total;
      w.putSide(guildID, side);       // putSide fige un instantané : indispensable
    }
    return total;
  }

  /** Message de mise à jour du score poussé à la guilde ({@code WarPointsUpdate}). */
  public static WarPointsUpdate toPointsUpdate(ServerWarState w, long guildID) {
    WarOutcomeSummary s = summaryFor(w, guildID);
    WarPointsUpdate u = new WarPointsUpdate();
    u.guildID = guildID;
    u.carDefeatedPoints = s.roomsDefeated * s.roomsDefeatedScalar;
    u.cleanSweepPoints = s.cleanSweeps * s.cleanSweepsScalar;
    u.defenseWinPoints = s.defensiveWins * s.defensiveWinsScalar;
    u.fullDefensePoints = s.cleanDefenses * s.cleanDefensesScalar;
    u.totalPoints = totalPoints(s);
    return u;
  }

  /**
   * Issue de la guerre pour {@code guildID}, par comparaison des totaux.
   * Un BYE reste un BYE (aucun adversaire à comparer).
   */
  public static com.perblue.heroes.network.messages.WarSummaryState outcomeFor(
      ServerWarState w, long guildID) {
    if (w.isBye()) return com.perblue.heroes.network.messages.WarSummaryState.BYE;
    int mine = totalPoints(summaryFor(w, guildID));
    int theirs = totalPoints(summaryFor(w, w.opponentOf(guildID)));
    if (mine > theirs) return com.perblue.heroes.network.messages.WarSummaryState.VICTORY;
    if (mine < theirs) return com.perblue.heroes.network.messages.WarSummaryState.DEFEAT;
    return com.perblue.heroes.network.messages.WarSummaryState.DRAW;
  }

  /** Une vague gagnée ? (lecture de l'{@code AttackStageResult} du client) */
  public static boolean waveWon(com.perblue.heroes.network.messages.AttackStageResult r) {
    return r != null && r.outcome == CombatOutcome.WIN;
  }
}
