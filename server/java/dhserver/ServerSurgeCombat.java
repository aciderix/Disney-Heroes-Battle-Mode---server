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
