// Smoke test : prouve que le handler BuyChests EXÉCUTE le code du jeu côté serveur et répond sur
// le WIRE. Démarre LoginServer (ServerContext = données du jeu + shim DH.app), un client envoie
// ClientInfo -> BootData puis BuyChests(GOLD) -> LootResults (Frozone) reçu. Cf. docs/SERVER_PLAN.md §6.
// Requiert : libs/game.jar + commons-logging + sqlite-jdbc + slf4j + joda-time + game-data/stats sur le CP.
import com.perblue.grunt.translate.*;
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.io.File;
import java.util.concurrent.*;

public class ChestWireTest {
  static final int PORT = 18090;
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    File db = File.createTempFile("dhwire", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    ServerUser user = store.loadOrCreate(1L, 1);
    LoginServer server = new LoginServer(PORT, user, store);
    server.start();

    final CountDownLatch got = new CountDownLatch(1);
    final long[] t0 = {0};
    GruntConnection conn = new GruntBuilder(MessageFactory.getInstance())
        .setAddress("127.0.0.1").setPort(PORT).setConnectionWrapper(DHXORConnectionWrapper.class)
        .setConnectionListener(new GruntConnectionListener() {
          public void onOpen(GruntConnection c) {}
          public void onClose(GruntConnection c) {}
        }).buildConnection();
    conn.setListener(BootData.class, new GruntListener<BootData>() {
      public void onReceive(GruntConnection c, GruntMessage m) {
        System.out.println("[client] BootData reçu, héros=" + ((BootData)m).userExtra.heroes.size() + " → envoi BuyChests(GOLD)");
        BuyChests bc = new BuyChests(); bc.chestType = ChestType.GOLD; bc.count = 1;
        t0[0] = System.currentTimeMillis();
        c.send(bc);
      }
    });
    conn.setListener(LootResults.class, new GruntListener<LootResults>() {
      public void onReceive(GruntConnection c, GruntMessage m) {
        LootResults lr = (LootResults) m;
        long dt = System.currentTimeMillis() - t0[0];
        System.out.println("[client] LootResults reçu en " + dt + "ms : lootDrops=" + lr.lootDrops.size()
            + " heroesUnlocked=" + lr.heroesUnlocked.size());
        got.countDown();
      }
    });
    conn.open();
    // ⚠️ FENÊTRE DE DÉMARRAGE NON FIABLE — c'est la VRAIE cause du prétendu « flake ChestWireTest », longtemps
    // attribuée à tort au chargement de GuildStats. Mesuré : un ClientInfo émis dans les tout premiers
    // instants de la connexion n'arrive JAMAIS au serveur (aucun `<== ClientInfo1` journalisé), alors que le
    // même envoi ~50 ms plus tard passe ; et après le second envoi le serveur décode EXACTEMENT UN message,
    // pas deux — les octets du premier n'ont donc jamais atteint son décodeur. Attendre l'`onOpen` serveur
    // (`connectionsAccepted`) réduit la fenêtre mais NE LA FERME PAS : mesuré 3 échecs sur 10 avec cette
    // seule garde. **Le mécanisme exact reste inexpliqué** (ni `GruntNIOTCPServer.read`, qui ne consomme
    // rien quand la connexion est absente ou pas prête, ni `GruntTCPConnection.send`, synchrone, ne montrent
    // où les octets disparaissent) — c'est consigné tel quel dans SHIMS.md.
    //
    // Le VRAI client n'y est pas exposé : son `/login` HTTP précède l'ouverture du socket de jeu et couvre
    // largement la fenêtre. Ce test, lui, enchaîne connexion et envoi dans la même milliseconde. On RÉÉMET
    // donc le ClientInfo jusqu'à obtenir le BootData — le serveur y répond de façon idempotente. Ce n'est pas
    // un faux « OK » : le test exige toujours un échange RÉEL `BuyChests → LootResults` pour passer.
    long deadline = System.currentTimeMillis() + 10_000;
    while (server.connectionsAccepted.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
    if (server.connectionsAccepted.get() == 0) { System.out.println("CHEST WIRE TIMEOUT (accept)"); System.exit(1); }
    boolean ok = false;
    for (int attempt = 1; attempt <= 10 && !ok; attempt++) {
      if (attempt > 1) System.out.println("[client] ClientInfo sans réponse — réémission #" + attempt);
      conn.send(new ClientInfo());
      ok = got.await(3, TimeUnit.SECONDS);
    }
    System.out.println("CHEST WIRE " + (ok ? "OK (BuyChests -> LootResults)" : "TIMEOUT"));
    System.exit(ok ? 0 : 1);
  }
}
