package dhserver;

import com.perblue.grunt.translate.GruntConnection;
import com.perblue.grunt.translate.GruntConnectionListener;
import com.perblue.grunt.translate.GruntListener;
import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.GruntServerFactory;
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.ClientInfo;
import com.perblue.heroes.network.messages.MessageFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Serveur de login v1 (protocole de jeu TCP).
 *
 * Réutilise INTÉGRALEMENT la pile réseau du jeu : le serveur NIO ({@code GruntNIOTCPServer}
 * via {@link GruntServerFactory}), le codec {@link DHXORConnectionWrapper} (Deflate + XOR,
 * clé du jeu) et le registre {@link MessageFactory}. Aucune réimplémentation binaire →
 * sérialisation/chiffrement identiques au client (cf. docs/PROTOCOL.md, docs/PRINCIPLES.md §3).
 *
 * Handshake géré : à la réception d'un {@code ClientInfo1}, répond un {@code BootData1}.
 * Les champs de BootData sont pour l'instant minimaux ; l'ensemble EXACT requis par le
 * vrai client se complétera en observant le client réel (principe : le jeu est la source
 * de vérité — pas d'invention). C'est un squelette v1 : le handshake bout-en-bout est
 * prouvé (server/smoke/HandshakeRoundTrip), la logique login/monde reste à étoffer.
 *
 * Lancement JVM : -Xverify:none + commons-logging au classpath (cf. docs/SHIMS.md).
 */
public final class LoginServer {

  /** Construit la réponse BootData pour un ClientInfo reçu. À enrichir (source = le jeu). */
  public interface BootDataProvider {
    BootData bootDataFor(ClientInfo clientInfo);
  }

  private final int port;
  private final BootDataProvider provider;

  public LoginServer(int port, BootDataProvider provider) {
    this.port = port;
    this.provider = provider;
  }

  public void start() throws Exception {
    final Executor exec = Executors.newCachedThreadPool();
    GruntConnectionListener listener = new GruntConnectionListener() {
      public void onOpen(GruntConnection conn) {
        conn.setListener(ClientInfo.class, new GruntListener<ClientInfo>() {
          public void onReceive(GruntConnection c, GruntMessage m) {
            BootData bd = provider.bootDataFor((ClientInfo) m);
            bd.setAsReplyTo(m);
            c.send(bd);
          }
        });
      }
      public void onClose(GruntConnection conn) {}
    };
    // port, factory, executor, listener, codec, sendTimeout, keepAlive, noDelay, proxyProto, buffer
    GruntServerFactory.startNioTcp(port, MessageFactory.getInstance(), exec, listener,
        DHXORConnectionWrapper.class, 5000, true, true, false, 65536);
    System.out.println("[login] écoute sur " + port + " (protocole de jeu, codec DHXOR)");
  }

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
    new LoginServer(port, ci -> {
      BootData bd = new BootData();
      bd.serverTime = System.currentTimeMillis();
      return bd;
    }).start();
    Thread.currentThread().join(); // reste actif
  }
}
