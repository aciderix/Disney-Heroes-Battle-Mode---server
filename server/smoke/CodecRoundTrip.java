// Smoke test : prouve que le codec réseau DU JEU (DHXORConnectionWrapper = Deflate + XOR,
// clé embarquée) se charge et fonctionne sur une JVM desktop → le serveur peut réutiliser
// directement les classes du jeu (aucune réimplémentation binaire). Voir docs/PROTOCOL.md.
//
// Pré-requis : libs/game.jar (via tools/decompile.sh) + libs/commons-logging.jar.
// Exécution   : voir server/smoke/run.sh (JVM lancée avec -Xverify:none — cf. docs/SHIMS.md).
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.grunt.translate.ConnectionWrapper;
import java.util.Arrays;

public class CodecRoundTrip {
  public static void main(String[] args) throws Exception {
    // « client » et « serveur » = deux instances de la MÊME classe de jeu → codec identique.
    ConnectionWrapper client = new DHXORConnectionWrapper();
    ConnectionWrapper server = new DHXORConnectionWrapper();

    byte[] msg = "ClientInfo1: userID=42 platform=ANDROID login=disney-heroes".getBytes("UTF-8");
    byte[] onWire  = client.wrapOut(msg);   // ce que le vrai client émettrait
    byte[] decoded = server.wrapIn(onWire); // ce que NOTRE serveur décoderait

    System.out.println("clair  (" + msg.length + "o): " + new String(msg, "UTF-8"));
    System.out.println("wire   (" + onWire.length + "o) hex[0..16]: " + hex(onWire, 16));
    System.out.println("decode (" + decoded.length + "o): " + new String(decoded, "UTF-8"));

    if (!Arrays.equals(msg, decoded)) {
      System.out.println("ROUND-TRIP ECHEC");
      System.exit(1);
    }
    System.out.println("ROUND-TRIP OK (codec du jeu reutilisable cote serveur)");
  }

  static String hex(byte[] b, int n) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < Math.min(n, b.length); i++) s.append(String.format("%02X ", b[i]));
    return s.toString();
  }
}
