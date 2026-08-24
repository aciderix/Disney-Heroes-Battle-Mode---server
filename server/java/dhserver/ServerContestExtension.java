package dhserver;

import com.perblue.heroes.game.logic.ContestHelper;
import com.perblue.heroes.game.objects.IUser;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.common.specialevent.components.pieces.ContestTaskInfo;
import com.perblue.common.specialevent.components.pieces.ContestProgressRewardInfo;

/**
 * CONTEST — extension serveur de {@link ContestHelper} (gap C). Le jeu délègue le CRÉDIT des tâches de contest à une
 * {@code ContestHelper.IContestHelperExtension} (champ statique privé {@code extension}). Côté CLIENT elle est posée au
 * boot ; côté SERVEUR (headless), le champ est {@code null} → sans elle, {@code recordTasks} ne crédite RIEN pour un
 * contest de GUILDE (et route quand même le solo par un autre chemin). On installe donc l'équivalent serveur (nous SOMMES
 * le backend, §3) : les méthodes {@code record*} par DÉFAUT de l'interface font déjà le vrai travail —
 * {@code recordContestTasks}→{@code user.getContestData(id)} et {@code recordGuildContestTasks}→{@code user.getGuildContestData(id)}
 * (exige {@code User.guildID>0}), toutes deux → {@code ContestHelper.recordTasks(…, IContestData)} (barème DU JEU, §4). On
 * n'implémente donc QUE les 2 méthodes abstraites de notification ({@code earnedPoints}/{@code earnedProgressLevel}), en NO-OP
 * tracé : la livraison des paliers reste faite par {@link ServerContestData#deliverEarnedProgressRewards} (idempotent, scan).
 *
 * <p>Vérifié (sonde) : avec l'extension, le SOLO crédite toujours EXACTEMENT le barème (pas de double-compte) et le contest
 * de GUILDE crédite le blob per-membre (à {@code guildID>0}). Sans extension, le crédit de guilde était perdu.
 */
public final class ServerContestExtension implements ContestHelper.IContestHelperExtension {

  /** Points crédités pour une tâche (notification). NO-OP tracé — le crédit réel est fait par les {@code record*} par défaut. */
  @Override
  public void earnedPoints(IUser user, SpecialEventInfo event, ContestTaskInfo task, long taskAmount, long pointsEarned) {
    // (silencieux par défaut ; utile pour diagnostic : System.out.println("[contest] +" + pointsEarned + " pts (contest " + event.getID() + ")"))
  }

  /** Palier de progression atteint (notification). NO-OP : la livraison par courrier = deliverEarnedProgressRewards (idempotent). */
  @Override
  public void earnedProgressLevel(IUser user, SpecialEventInfo event, ContestProgressRewardInfo reward) {
    // NO-OP tracé — cf. ServerContestData.deliverEarnedProgressRewards.
  }

  /** Installe une instance dans le champ statique {@code ContestHelper.extension} (pas de setter public → réflexion). Idempotent. */
  static synchronized void install() {
    try {
      java.lang.reflect.Field f = ContestHelper.class.getDeclaredField("extension");
      f.setAccessible(true);
      if (f.get(null) == null) {
        f.set(null, new ServerContestExtension());
        System.out.println("[ctx] ContestHelper.extension installée (crédit contest de guilde serveur-autoritatif)");
      }
    } catch (Throwable t) { System.out.println("[ctx] install ContestHelper.extension: " + t); }
  }
}
