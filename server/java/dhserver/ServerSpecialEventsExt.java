package dhserver;

import com.perblue.common.specialevent.EventReward;
import com.perblue.heroes.game.logic.ItemHelper;
import com.perblue.heroes.game.logic.SpecialEventsHelper;
import com.perblue.heroes.game.objects.IUser;
import com.perblue.heroes.network.messages.ItemType;
import com.perblue.heroes.network.messages.RewardDrop;
import com.perblue.heroes.network.messages.TimeType;
import com.perblue.heroes.util.TimeUtil;

import java.util.Map;

/**
 * Extension SERVEUR de {@link SpecialEventsHelper} (docs/PRINCIPLES.md §3 « lire & exécuter »).
 *
 * <p>Le jeu enregistre une {@code ISpecialEventsHelperExtension} pendant {@code GameMain.create()}
 * ({@code SpecialEventsHelper.init(new ClientEventUserProvider(), new ClientSpecialEventsHelperExt())}).
 * L'implémentation <b>cliente</b> ({@code ClientSpecialEventsHelperExt}) touche libGDX
 * ({@code Gdx.app.postRunnable}, « Gdx.app not available » headless) : elle sert à <b>pousser vers le
 * serveur</b> les temps de visionnage d'évènements ({@code UpdateEventViewTimes}). Côté serveur, cette
 * poussée réseau n'a pas de sens (le serveur EST l'autorité) → on fournit l'équivalent serveur, qui
 * applique la <b>même logique d'état</b> que le client sans le message réseau.
 *
 * <p>Fidélité (chaque méthode = comportement du client, moins la couche transport cliente) :
 * <ul>
 *   <li><b>{@code sendEventRewards}</b> : logique d'état pure du client (aucun libGDX) — si une
 *       récompense contient {@code PREMIUM_STAMINA_CONSUMABLE}, convertir les objets à durée limitée
 *       et horodater {@code LAST_AMPED_STAMINA_BUY}. <b>Reproduite à l'identique.</b></li>
 *   <li><b>{@code trySetEventViewed}</b> : la partie <b>autoritative</b> du client est d'inscrire le
 *       temps de visionnage dans {@code user.getIndividual().getEventViewTimes()}. Le client, en plus,
 *       met en cache un {@code UpdateEventViewTimes} et l'envoie AU serveur ({@code DH.app
 *       .getNetworkProvider().sendMessage}) — inutile côté serveur (destinataire = lui-même). On
 *       conserve l'inscription autoritative, on omet la poussée réseau cliente.</li>
 * </ul>
 * <b>RÉEL</b> (pas une rustine) : aucune donnée falsifiée ; on exécute la logique d'état du jeu et on
 * n'omet qu'un envoi client→serveur dénué de sens sur le serveur.
 */
public final class ServerSpecialEventsExt implements SpecialEventsHelper.ISpecialEventsHelperExtension {

  @Override
  public void sendEventRewards(IUser user, EventReward reward, String s1, String s2, int n) {
    for (Object o : reward.getDrops()) {
      RewardDrop drop = (RewardDrop) o;
      if (drop.itemType == ItemType.PREMIUM_STAMINA_CONSUMABLE) {
        ItemHelper.convertTimeLimitedItems(user, TimeUtil.serverTimeNow());
        user.setTime(TimeType.LAST_AMPED_STAMINA_BUY, TimeUtil.serverTimeNow());
        break;
      }
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void trySetEventViewed(IUser user, long eventID, long viewTime) {
    Map<Long, Long> viewTimes = user.getIndividual().getEventViewTimes();
    if (viewTimes.containsKey(eventID)) return;
    viewTimes.put(eventID, viewTime);
    // (client : mise en cache + envoi d'un UpdateEventViewTimes au serveur — sans objet côté serveur)
  }
}
