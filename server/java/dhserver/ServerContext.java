package dhserver;

import com.perblue.heroes.DH;
import com.perblue.heroes.GameMain;
import com.perblue.heroes.game.logic.SpecialEventsHelper;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.specialevent.ClientEventUserProvider;
import com.perblue.heroes.network.messages.SpecialEventsRaw;

import java.lang.reflect.Field;

/**
 * Contexte d'exécution serveur pour faire tourner la LOGIQUE du jeu headless (docs/PRINCIPLES.md §3
 * « lire & exécuter »). Deux choses :
 * <ol>
 *   <li>charge la couche données du jeu ({@link ServerStats#install()} → les {@code .tab}) ;</li>
 *   <li>fournit un {@code DH.app} — beaucoup de classes du jeu passent par ce singleton client
 *       ({@code GameMain}), ex. {@code User.getIndividual()} = {@code DH.app.getYourIndividualUser()}.
 *       On alloue un {@code GameMain} <b>sans constructeur</b> (Unsafe) et on pose {@code user}/
 *       {@code individualUser} par réflexion → les getters répondent. C'est de la couche plateforme,
 *       pas de la logique de jeu.</li>
 * </ol>
 * Un seul compte pour l'instant → un seul {@code DH.app} lié au joueur courant.
 */
public final class ServerContext {

  private static GameMain app;              // GameMain headless (DH.app)
  private static Field userField, individualField;

  private ServerContext() {}

  /** Initialise la couche données + le shim {@code DH.app} (idempotent). */
  public static synchronized void init() {
    if (app != null) return;
    ServerStats.install();
    try {
      Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Object unsafe = theUnsafe.get(null);
      // GameMain alloué SANS exécuter son constructeur (aucune init client/libGDX).
      app = (GameMain) unsafe.getClass().getMethod("allocateInstance", Class.class)
          .invoke(unsafe, GameMain.class);
      Field appF = DH.class.getDeclaredField("app"); appF.setAccessible(true); appF.set(null, app);
      userField = field(GameMain.class, "user");
      individualField = field(GameMain.class, "individualUser");
      // Couche évènements spéciaux — comme GameMain.create() :
      // SpecialEventsHelper.init(new ClientEventUserProvider(), extension). L'extension CLIENTE touche
      // libGDX (« Gdx.app not available » headless) → on fournit l'équivalent SERVEUR (ServerSpecialEventsExt).
      // Sans ça, SpecialEventsHelper.helper est null → NPE dès qu'un don d'objet enregistre une tâche de
      // contest (ChestHelper.giveChestRewards → RewardHelper.giveReward → ContestHelper.onItemEarn).
      SpecialEventsHelper.init(new ClientEventUserProvider(), new ServerSpecialEventsExt());
      System.out.println("[ctx] DH.app headless + données du jeu + couche évènements spéciaux");
    } catch (Throwable t) {
      throw new RuntimeException("échec init contexte serveur (DH.app)", t);
    }
  }

  /** Lie le joueur courant au shim {@code DH.app} (getYourUser/getYourIndividualUser). */
  public static synchronized void bind(User user, IndividualUser individualUser) {
    init();
    try {
      userField.set(app, user); individualField.set(app, individualUser);
      // Charge les évènements du joueur dans la couche — comme GameMain.handleBootData
      // (SpecialEventsHelper.setSpecialEvents). Nouveau joueur sans évènement live = raw vide → aucun
      // contest actif (getActiveContestsWithTask renvoie une liste vide au lieu de NPE).
      SpecialEventsHelper.setSpecialEvents(new SpecialEventsRaw(), user, user.getShardID());
    } catch (Throwable t) { throw new RuntimeException("échec bind DH.app", t); }
  }

  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    for (Class<?> k = c; k != null; k = k.getSuperclass()) {
      try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; }
      catch (NoSuchFieldException e) { /* remonter */ }
    }
    throw new NoSuchFieldException(name);
  }
}
