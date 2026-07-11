package dhserver;

import com.perblue.grunt.translate.GruntConnection;
import com.perblue.grunt.translate.GruntConnectionListener;
import com.perblue.grunt.translate.GruntListener;
import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.GruntServerFactory;
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.ChangeTutorialStep;
import com.perblue.heroes.network.messages.ClientInfo;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.Ping;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Serveur de jeu TCP — réutilise INTÉGRALEMENT la pile réseau du jeu (GruntNIOTCPServer via
 * {@link GruntServerFactory}, codec {@link DHXORConnectionWrapper}, registre {@link MessageFactory}).
 * Aucune réimplémentation binaire (docs/PRINCIPLES.md §3 : serveur autoritaire basé sur le jeu).
 *
 * Cette version INSTRUMENTE : elle journalise CHAQUE message reçu du client (le client = source de
 * vérité) afin d'établir empiriquement le flux post-BootData (nouveau joueur → tuto) avant d'écrire
 * les handlers. Répond BootData au ClientInfo ; les autres messages sont journalisés (handlers à venir).
 */
public final class LoginServer {

  private final int port;
  /** État serveur autoritaire (un seul compte pour l'instant — persistance = étape 5). */
  private final ServerUser user;

  public LoginServer(int port, ServerUser user) { this.port = port; this.user = user; }

  /** Toutes les classes de message du jeu (dérivées du registre MessageFactory.messageIndex). */
  @SuppressWarnings("unchecked")
  private static Set<Class<? extends GruntMessage>> allMessageClasses() {
    Set<Class<? extends GruntMessage>> out = new LinkedHashSet<>();
    try {
      Field f = MessageFactory.class.getDeclaredField("messageIndex");
      f.setAccessible(true);
      Map<Object, Object> idx = (Map<Object, Object>) f.get(null);
      for (Object key : idx.keySet()) {
        String full = String.valueOf(key);            // ex. "UpdateStats1"
        String base = full.replaceAll("\\d+$", "");    // retire la version en suffixe
        try {
          Class<?> c = Class.forName("com.perblue.heroes.network.messages." + base);
          if (GruntMessage.class.isAssignableFrom(c)) out.add((Class<? extends GruntMessage>) c);
        } catch (Throwable ignore) { /* nom non résoluble → ignoré */ }
      }
    } catch (Throwable t) { System.out.println("[login] enum messages échec: " + t); }
    return out;
  }

  public void start() throws Exception {
    final Executor exec = Executors.newCachedThreadPool();
    final Set<Class<? extends GruntMessage>> msgClasses = allMessageClasses();
    System.out.println("[login] " + msgClasses.size() + " classes de message enregistrées (log)");

    GruntConnectionListener listener = new GruntConnectionListener() {
      public void onOpen(final GruntConnection conn) {
        System.out.println("[login] onOpen " + conn);
        // Handler de LOG universel : journalise chaque message, et répond BootData au ClientInfo.
        GruntListener<GruntMessage> logger = new GruntListener<GruntMessage>() {
          public void onReceive(GruntConnection c, GruntMessage m) {
            String name = m.getFullName();
            System.out.println("[login] <== " + name);
            if (m instanceof ClientInfo) {
              BootData bd = user.bootData();
              bd.setAsReplyTo(m);
              c.send(bd);
              System.out.println("[login] ==> BootData (reply) : "
                  + bd.individualUserExtra.tutorialActs.size() + " actes de tuto");
            } else if (m instanceof ChangeTutorialStep) {
              // Progression du tutoriel : le serveur est autoritaire → on met à jour l'état
              // (persisté en étape 5). Fire-and-forget côté client (aucune réponse attendue).
              ChangeTutorialStep cts = (ChangeTutorialStep) m;
              boolean applied = user.applyTutorialStep(cts);
              System.out.println("[login]     tuto " + cts.type + " -> step " + cts.step
                  + (cts.forceSkip ? " (forceSkip)" : "") + (applied ? "" : " [type inconnu, ignoré]"));
            } else if (m instanceof Ping) {
              // Écho de latence/keepalive : le client mesure le RTT et surveille l'activité serveur.
              // Sans réponse, son chien de garde ferme la connexion (« Reconnecting… »).
              Ping in = (Ping) m;
              long now = System.currentTimeMillis();
              Ping pong = new Ping();
              pong.timestamp = in.timestamp;     // renvoyé tel quel (le client calcule le RTT)
              pong.serverReceive = now;
              pong.serverTime = now;
              pong.serverDelay = 0;
              pong.setAsReplyTo(m);
              c.send(pong);
              System.out.println("[login] ==> Ping (echo)");
            }
          }
        };
        for (Class<? extends GruntMessage> c : msgClasses) {
          try { conn.setListener(c, logger); } catch (Throwable ignore) {}
        }
      }
      public void onClose(GruntConnection conn) { System.out.println("[login] onClose " + conn); }
    };

    GruntServerFactory.startNioTcp(port, MessageFactory.getInstance(), exec, listener,
        DHXORConnectionWrapper.class, 5000, true, true, false, 65536);
    System.out.println("[login] écoute sur " + port + " (protocole de jeu, codec DHXOR)");
  }

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
    // Étapes 3-4 : compte NOUVEAU JOUEUR autoritaire → tutoriel d'intro, avec progression du tuto
    // appliquée côté serveur (ChangeTutorialStep). Structure/valeurs entièrement issues des classes
    // du jeu (docs/PRINCIPLES.md §4). userID fixe pour l'instant (persistance = étape 5).
    ServerUser user = new ServerUser(/*userID*/ 1L, /*shardID*/ 1);
    System.out.println("[login] compte nouveau joueur créé (id=1, tutoriels au step 0)");
    new LoginServer(port, user).start();
    Thread.currentThread().join();
  }
}
