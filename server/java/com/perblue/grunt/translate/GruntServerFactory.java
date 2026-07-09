package com.perblue.grunt.translate;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabrique du serveur réseau DU JEU, réutilisé tel quel.
 *
 * {@code GruntNIOTCPServer} (la pile serveur NIO du jeu, même framing/codec que le client)
 * est <b>package-private</b> et sans fabrique publique : cette classe est placée dans le
 * même package pour l'instancier, sans modifier le bytecode du jeu (cf. docs/PRINCIPLES.md
 * §1 : on ne touche pas au jeu ; on ajoute une couche autour).
 *
 * Détail reversé (docs/SHIMS.md) : le constructeur crée le thread NIO mais ne l'active pas
 * (drapeau {@code running=false}, thread non démarré). Le démarrage se fait donc ici :
 * on lève {@code running} puis on démarre le thread — c'est le bootstrap que le jeu ferait
 * côté serveur. Aucune rustine : on active réellement la boucle réseau native du jeu.
 */
public final class GruntServerFactory {
  private GruntServerFactory() {}

  /**
   * Construit et démarre le serveur TCP NIO du jeu.
   *
   * Params (reversés du ctor) : port, factory de messages, executor, listener de connexion,
   * classe du wrapper de connexion (codec), sendTimeout(ms), keepAlive, noDelay,
   * useProxyProtocol, bufferSize.
   */
  public static GruntServer startNioTcp(int port, GruntMessageFactory factory, Executor exec,
      GruntConnectionListener listener, Class<? extends ConnectionWrapper> wrapper,
      int sendTimeout, boolean keepAlive, boolean noDelay, boolean useProxyProtocol,
      int bufferSize) throws Exception {
    GruntNIOTCPServer s = new GruntNIOTCPServer(port, factory, exec, listener, wrapper,
        sendTimeout, keepAlive, noDelay, useProxyProtocol, bufferSize);

    Field running = GruntNIOTCPServer.class.getDeclaredField("running");
    running.setAccessible(true);
    ((AtomicBoolean) running.get(s)).set(true);

    Field thread = GruntNIOTCPServer.class.getDeclaredField("thread");
    thread.setAccessible(true);
    Thread t = (Thread) thread.get(s);
    t.setDaemon(true);
    if (!t.isAlive()) t.start();
    return s;
  }
}
