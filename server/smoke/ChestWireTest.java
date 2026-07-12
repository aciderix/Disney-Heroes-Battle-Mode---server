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
    new LoginServer(PORT, user, store).start();

    final CountDownLatch got = new CountDownLatch(1);
    final long[] t0 = {0};
    GruntConnection conn = new GruntBuilder(MessageFactory.getInstance())
        .setAddress("127.0.0.1").setPort(PORT).setConnectionWrapper(DHXORConnectionWrapper.class)
        .setConnectionListener(new GruntConnectionListener() {
          public void onOpen(GruntConnection c) { c.send(new ClientInfo()); }
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
    boolean ok = got.await(30, TimeUnit.SECONDS);
    System.out.println("CHEST WIRE " + (ok ? "OK (BuyChests -> LootResults)" : "TIMEOUT"));
    System.exit(ok ? 0 : 1);
  }
}
