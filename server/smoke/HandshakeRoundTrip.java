// Smoke test : handshake de login BOUT-EN-BOUT sur une vraie socket TCP, en réutilisant
// UNIQUEMENT les classes du jeu (serveur GruntNIOTCPServer via LoginServer, client
// GruntBuilder, codec DHXORConnectionWrapper, messages ClientInfo/BootData). Sans libGDX.
//
// Prouve que NOTRE serveur décode un ClientInfo1 et répond un BootData1 que le client du
// jeu accepte — exactement le handshake que fera le vrai client. Voir docs/PROTOCOL.md.
// Pré-requis : libs/game.jar + libs/commons-logging.jar + server/java compilé (-Xverify:none).
import com.perblue.grunt.translate.*;
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.heroes.network.messages.*;
import dhserver.LoginServer;
import java.util.concurrent.*;

public class HandshakeRoundTrip {
  static final int PORT = 18080;

  public static void main(String[] args) throws Exception {
    final CountDownLatch got = new CountDownLatch(1);
    final long SERVER_TIME = 424242L;

    // Serveur : répond un BootData déterministe à tout ClientInfo.
    new LoginServer(PORT, ci -> {
      BootData bd = new BootData();
      bd.serverTime = SERVER_TIME;
      bd.loginEvent = "welcome";
      return bd;
    }).start();

    // Client (simulé avec la pile réseau du jeu) : envoie ClientInfo, attend BootData.
    GruntConnection conn = new GruntBuilder(MessageFactory.getInstance())
        .setAddress("127.0.0.1").setPort(PORT)
        .setConnectionWrapper(DHXORConnectionWrapper.class)
        .setConnectionListener(new GruntConnectionListener() {
          public void onOpen(GruntConnection c) { c.send(new ClientInfo()); }
          public void onClose(GruntConnection c) {}
        }).buildConnection();
    conn.setListener(BootData.class, new GruntListener<BootData>() {
      public void onReceive(GruntConnection c, GruntMessage m) {
        BootData bd = (BootData) m;
        System.out.println("[client] recu " + m.getFullName() + " serverTime=" + bd.serverTime + " loginEvent=" + bd.loginEvent);
        if (bd.serverTime == SERVER_TIME && "welcome".equals(bd.loginEvent)) got.countDown();
      }
    });
    conn.open();

    boolean ok = got.await(15, TimeUnit.SECONDS);
    System.out.println("HANDSHAKE TCP " + (ok ? "OK (ClientInfo1 -> BootData1 via classes du jeu)" : "TIMEOUT"));
    System.exit(ok ? 0 : 1);
  }
}
