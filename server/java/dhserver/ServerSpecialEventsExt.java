package dhserver;

import com.perblue.common.specialevent.EventReward;
import com.perblue.heroes.game.logic.SpecialEventsHelper;
import com.perblue.heroes.game.objects.IUser;
import com.perblue.heroes.game.specialevent.ClientSpecialEventsHelperExt;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Extension SERVEUR de {@link SpecialEventsHelper} (docs/PRINCIPLES.md §3/§4).
 *
 * <p>Le jeu enregistre une {@code ISpecialEventsHelperExtension} pendant {@code GameMain.create()}
 * ({@code SpecialEventsHelper.init(new ClientEventUserProvider(), new ClientSpecialEventsHelperExt())}).
 * L'implémentation <b>cliente</b> ne peut pas être <b>construite</b> headless : son constructeur touche
 * libGDX (« Gdx.app not available ») car elle sert à <b>pousser vers le serveur</b> les temps de
 * visionnage d'évènements (message {@code UpdateEventViewTimes} via {@code Gdx.app.postRunnable}).
 *
 * <p><b>On n'en recopie AUCUNE ligne</b> (PRINCIPLES §4). On alloue l'instance du jeu <b>sans exécuter
 * son constructeur</b> ({@code Unsafe.allocateInstance}, comme le shim {@code DH.app}) et on <b>exécute
 * ses vraies méthodes</b> quand elles ne dépendent pas de la couche cliente :
 * <ul>
 *   <li><b>{@code sendEventRewards}</b> : la méthode du jeu ne touche pas libGDX (elle applique une
 *       logique d'état : {@code PREMIUM_STAMINA_CONSUMABLE → convertTimeLimitedItems + setTime}) → on
 *       <b>délègue au code d'origine</b>. Zéro recopie, zéro risque d'omission.</li>
 *   <li><b>{@code trySetEventViewed}</b> : la méthode du jeu, elle, pousse au serveur via
 *       {@code Gdx.app.postRunnable(sendMessage(UpdateEventViewTimes))} — un aller CLIENT→serveur qui
 *       n'a pas de destinataire côté serveur (le serveur EST l'autorité). On ne peut donc pas la
 *       déléguer ; on fait la seule action autoritative qu'elle contient : <b>enregistrer</b> le temps
 *       de visionnage dans l'état du joueur. C'est de la <b>glue serveur</b> (recevoir/consigner une
 *       valeur), pas de la logique de jeu recopiée.</li>
 * </ul>
 */
public final class ServerSpecialEventsExt implements SpecialEventsHelper.ISpecialEventsHelperExtension {

  /** Instance du jeu allouée SANS constructeur → on exécute ses vraies méthodes (pas de recopie). */
  private final ClientSpecialEventsHelperExt game;

  public ServerSpecialEventsExt() {
    try {
      Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Object unsafe = theUnsafe.get(null);
      game = (ClientSpecialEventsHelperExt) unsafe.getClass()
          .getMethod("allocateInstance", Class.class)
          .invoke(unsafe, ClientSpecialEventsHelperExt.class);
    } catch (Throwable t) {
      throw new RuntimeException("échec allocation ClientSpecialEventsHelperExt (sans ctor)", t);
    }
  }

  @Override
  public void sendEventRewards(IUser user, EventReward reward, String s1, String s2, int n) {
    // Code d'ORIGINE du jeu (ne dépend pas de libGDX) — exécuté, jamais recopié.
    game.sendEventRewards(user, reward, s1, s2, n);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void trySetEventViewed(IUser user, long eventID, long viewTime) {
    // Glue serveur : le client POUSSE cette info au serveur (UpdateEventViewTimes) ; ici le serveur
    // EST l'autorité → il enregistre directement, sans la poussée réseau (qui n'aurait pas de cible).
    Map<Long, Long> viewTimes = user.getIndividual().getEventViewTimes();
    if (!viewTimes.containsKey(eventID)) viewTimes.put(eventID, viewTime);
  }
}
