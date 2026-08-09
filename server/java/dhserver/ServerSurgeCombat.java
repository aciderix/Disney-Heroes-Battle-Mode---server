package dhserver;

import com.perblue.heroes.game.logic.SurgeHelper;
import com.perblue.heroes.game.objects.IHero;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.surge.SurgeClientMember;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.AttackLineupSummary;
import com.perblue.heroes.network.messages.AttackUnitSummary;
import com.perblue.heroes.network.messages.SurgeAttack;
import com.perblue.heroes.network.messages.SurgeMemberSummary;
import com.perblue.heroes.network.messages.SurgeObjectiveInfo;

/**
 * SURGE (#72) incrément 4a — ENREGISTREMENT AUTORITATIF d'un combat de région (client-combat, comme la campagne).
 *
 * <p>Le CLIENT joue le combat (HeadlessCombat) puis envoie {@link SurgeAttack}. Le serveur EXÉCUTE la logique
 * autoritative du jeu {@code SurgeHelper.recordOutcome} (§3, jamais réinventée) : or stocké, progression
 * d'objectifs, maîtrise de héros, contests, points/activité — sur le {@link SurgeClientMember} DU JEU adossé à la
 * {@link SurgeMemberSummary} du joueur (mutations écrites dans la summary → persistées dans le SurgeData de guilde).
 *
 * <p><b>Chaque paramètre est ÉTABLI PAR LES FAITS</b> (désassemblage du seul appelant, {@code SurgeAttackScreen},
 * offsets 239-261) — aucune valeur devinée (§4) :
 * <ul>
 *   <li>{@code outcome} = {@code base.outcome} ; {@code attackerLineups} = {@code base.attackers} ;
 *       {@code defenderLineups} = {@code base.defenders} (déjà des {@code AttackLineupSummary}, comme la campagne) ;</li>
 *   <li>3ᵉ collection = les {@code IHero} attaquants (pour {@code recordHeroMastery}) — {@code recordOutcome}
 *       n'appelle que {@code getType()}/{@code isMercenary()} dessus → reconstruits depuis
 *       {@code base.attackers[*].units[*].type} via {@code user.getHero(type)} (les héros RÉELS du joueur) ;</li>
 *   <li>Set d'objectifs = {@code m.objectiveProgress.keySet()} — le client met {@code (SurgeObjectiveInfo → 1)}
 *       pour chaque objectif QUALIFIÉ (scène-dépendant, client-autoritatif comme le loot) ;</li>
 *   <li>les DEUX booléens sont {@code iconst_0} au site d'appel → {@code false, false} (prouvé) ;</li>
 *   <li>{@code raidID}/{@code snapshot} passés par l'appelant.</li>
 * </ul>
 */
public final class ServerSurgeCombat {

  private ServerSurgeCombat() {}

  /**
   * Rejoue {@code SurgeHelper.recordOutcome} pour un combat de région rapporté par le client, sur la summary du
   * membre (mutée en place). {@code user} doit être lié au contexte de jeu ({@code DH.app}) par l'appelant.
   */
  public static void applyRegionOutcome(User user, SurgeMemberSummary summary, long surgeID, long raidID,
      SurgeAttack m, SpecialEventSnapshot snap) {
    SurgeClientMember member = new SurgeClientMember(surgeID, summary);
    java.util.List<IHero> attackerHeroes = reconstructHeroes(user, m);
    java.util.Set<SurgeObjectiveInfo> objectives = m.objectiveProgress == null
        ? java.util.Collections.emptySet()
        : new java.util.HashSet<>(m.objectiveProgress.keySet());
    // Signature (12 params) et booléens false/false : prouvés au site d'appel (SurgeAttackScreen 239-261).
    SurgeHelper.recordOutcome(user, member, raidID, m.district, false, m.base.outcome,
        m.base.attackers, m.base.defenders, attackerHeroes, objectives, false, snap);
  }

  /**
   * SURGE (#72) incrément 5 — ISSUE AUTORITATIVE d'un RAID (mécanique HQ). Le CLIENT joue le raid puis envoie
   * {@code Action{command=RAID_SURGE, extra={TYPE=district, COUNT, UPSELL, MODE}}} (précédé de
   * {@code HeroLineupUpdate{SURGE}} + {@code SET_SEED{SURGE}}). Le serveur rejoue {@code SurgeHelper.recordRaid}
   * (§3, jamais réinventé), qui fait l'autorité : {@code storeGold} (or du raid), {@code incDailyUses} (consommation
   * du pass de raid), {@code UserActivityTracker.onSurgeRaid}.
   *
   * <p><b>Params PROUVÉS au bytecode</b> (site d'appel {@code SurgeHelper.doRaid}, offsets 181-218) — zéro invention :
   * équipe = {@code user.getHeroLineup(SURGE)} (persistée par le {@code HeroLineupUpdate} précédent) ;
   * {@code RAID_TEAM_POWER} = Σ {@code PowerCalculator.getPower(hero, 0)} sur cette équipe ;
   * {@code GOLD} = {@code getGoldForSurgeRaid(user, lineup, opponent.lineup, emptyList, snap)} ;
   * appel = {@code recordRaid(user, member, surgeID, district, false, RAID_TEAM_POWER, 0L, GOLD, raidHeroes, snap)}.
   * Renvoie l'or crédité (delta {@code member.storedGold}).
   */
  public static long applyRaidOutcome(User user, SurgeMemberSummary summary, long surgeID,
      com.perblue.heroes.network.messages.DistrictType district,
      com.perblue.heroes.network.messages.LineupSummary opponentLineup, SpecialEventSnapshot snap) {
    SurgeClientMember member = new SurgeClientMember(surgeID, summary);
    com.perblue.heroes.network.messages.HeroLineup lineup =
        user.getHeroLineup(com.perblue.heroes.network.messages.HeroLineupType.SURGE, 0L);
    java.util.List<IHero> raidHeroes = new java.util.ArrayList<>();
    long teamPower = 0L;
    if (lineup != null && lineup.heroes != null) {
      for (Object o : lineup.heroes) {
        com.perblue.heroes.network.messages.UnitType t = (com.perblue.heroes.network.messages.UnitType) o;
        if (t == null) continue;
        com.perblue.heroes.game.objects.UnitData h = user.getHero(t);
        if (h != null) {
          raidHeroes.add(h);
          try { teamPower += com.perblue.heroes.game.data.combat.PowerCalculator.getPower(h, 0); }
          catch (Throwable ignore) {}
        }
      }
    }
    long before = summary.storedGold;
    long gold;
    try { gold = SurgeHelper.getGoldForSurgeRaid(user, lineup, opponentLineup,
        java.util.Collections.<Object>emptyList(), snap); }
    catch (Throwable t) { gold = 0L; }
    SurgeHelper.recordRaid(user, member, surgeID, district, false, teamPower, 0L, gold, raidHeroes, snap);
    return summary.storedGold - before;      // or réellement crédité par storeGold (autoritatif)
  }

  /** Les {@code IHero} attaquants = les héros RÉELS du joueur des types du lineup rapporté (mercenaires exclus :
   *  {@code recordHeroMastery} les ignore et ils ne sont pas au roster). Aucune invention — types = ce que le
   *  client a rapporté dans {@code base.attackers}. */
  static java.util.List<IHero> reconstructHeroes(User user, SurgeAttack m) {
    java.util.List<IHero> out = new java.util.ArrayList<>();
    if (m.base == null || m.base.attackers == null) return out;
    for (Object lo : m.base.attackers) {
      AttackLineupSummary ls = (AttackLineupSummary) lo;
      if (ls.units == null) continue;
      for (Object uo : ls.units) {
        AttackUnitSummary u = (AttackUnitSummary) uo;
        if (u.isMercenary || u.type == null) continue;
        IHero h = user.getHero(u.type);
        if (h != null) out.add(h);
      }
    }
    return out;
  }
}
