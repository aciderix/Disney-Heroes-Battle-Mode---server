// Smoke test : prouve que la SÉRIALISATION des messages DU JEU fonctionne sur JVM desktop
// (sans libGDX). Construit un BootData, l'écrit via writeAll (format wire exact du jeu),
// puis le relit via MessageFactory.readMessage → mêmes champs. C'est exactement ce que fait
// le serveur de login : décoder un ClientInfo1 reçu, répondre un BootData1.
// Voir docs/PROTOCOL.md. Pré-requis : libs/game.jar + libs/commons-logging.jar (-Xverify:none).
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.grunt.translate.util.GruntInputStream;

public class MessageRoundTrip {
  public static void main(String[] args) {
    BootData bd = new BootData();
    bd.serverTime = 1234567890L;
    bd.serverHasArenaSeasons = true;
    bd.loginEvent = "hello";
    System.out.println("fullName=" + bd.getFullName());

    GruntOutputStream out = new GruntOutputStream();
    bd.writeAll(out);                       // format wire exact du jeu (en-tête + writeData)
    byte[] bytes = out.getBytes();
    System.out.println("serialise " + bytes.length + " octets");

    GruntMessage back = MessageFactory.getInstance().readMessage(new GruntInputStream(bytes));
    System.out.println("readMessage -> " + back.getClass().getName());

    boolean ok = back instanceof BootData
        && ((BootData) back).serverTime == 1234567890L
        && ((BootData) back).serverHasArenaSeasons
        && "hello".equals(((BootData) back).loginEvent);
    System.out.println("MESSAGE ROUND-TRIP " + (ok ? "OK (serialisation du jeu reutilisable)" : "ECHEC"));
    if (!ok) System.exit(1);
  }
}
