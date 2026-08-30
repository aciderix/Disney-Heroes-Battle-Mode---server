// Smoke ISOLÉ (Phase 2 C2a-2, LOGIN UNIQUE strict) — RÉGRESSION du bug trouvé en vérif EN JEU strict+avancé :
// en mode STRICT, le client boote en userID=0 et RECOPIE le BILLET nominatif (loginRequestID, frappé par
// content_server /login → /auth/mint) dans son ClientInfo. Le LoginServer DOIT résoudre le compte DEPUIS le billet
// (source de vérité), pas depuis le userID annoncé (0). Avant correctif, uid=0 court-circuitait auth+chargement →
// compte PAR DÉFAUT servi SANS authentification (mauvais compte + contournement de l'auth).
//
// Prouve, avec la VRAIE pile (LoginServer + SessionStore + UserStore + codec + ClientInfo/BootData) :
//   (A) STRICT   : userID=0 + billet valide → BootData du compte MINTÉ (discriminé par teamLevel=123), pas le défaut.
//   (B) STRICT   : userID=0 SANS billet     → REJET (aucun BootData).
//   (C) STRICT   : userID=0 + billet BIDON  → REJET.
//   (D) PERMISSIF: userID=0 sans billet     → compte PAR DÉFAUT servi (TL=1) — comportement historique inchangé.
import com.perblue.grunt.translate.*;
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.heroes.network.messages.*;
import dhserver.*;
import dhserver.auth.*;
import dhserver.auth.MnemonicIdentity.Identity;
import java.io.File;
import java.util.concurrent.*;

public final class StrictSingleLoginTest {
  static final int PORT_STRICT = 18097;
  static final int PORT_PERM   = 18098;
  static int checks = 0;
  static void ok(boolean c, String m) {
    checks++;
    if (!c) { System.out.println("[StrictSingleLoginTest] ÉCHEC: " + m); System.exit(1); }
    System.out.println("  ✓ " + m);
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    File db = File.createTempFile("strictlogin", ".db"); db.delete();
    try (UserStore store = new UserStore(db.getPath())) {
      // Compte PAR DÉFAUT (userID 1, TL 1) = ce que le serveur servait SANS résolution (le repli à corriger).
      ServerUser def = store.loadOrCreate(1L, 1);

      // Compte AUTHENTIFIÉ (mnémonique) avec teamLevel DISTINCTIF = 123 (discriminateur persisté).
      Identity id = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate());
      long uid = id.userID;
      ServerUser mu = store.loadOrCreate(uid, 1);
      mu.bootData().userInfo.basicInfo.teamLevel = 123;
      store.save(mu);

      // SessionStore partagée → auth défi-réponse (register+bind) → mint d'un billet nominatif pour `uid`.
      SessionStore sessions = new SessionStore();
      byte[] nonce = sessions.issueChallenge(uid);
      byte[] sig = MnemonicIdentity.sign(id.keyPair.getPrivate(), nonce);
      ok(sessions.registerAndBind(uid, "launcher-tmp", id.publicKey, nonce, sig, store), "compte authentifié (défi-réponse)");
      String billet = sessions.mintForUser(uid);
      ok(billet != null && !billet.isEmpty(), "billet nominatif frappé (/auth/mint)");
      ok(sessions.authenticatedUser(billet) == uid, "billet → userID (SessionStore)");

      // Serveur STRICT (auth requise) + serveur PERMISSIF (auth off) partageant le même UserStore.
      LoginServer strict = new LoginServer(PORT_STRICT, def, store);
      strict.setAuth(sessions, true);
      strict.start();
      LoginServer perm = new LoginServer(PORT_PERM, def, store);   // pas de setAuth → authRequired=false
      perm.start();

      // (A) STRICT : userID=0 + billet valide → compte MINTÉ (TL 123), PAS le défaut (TL 1).
      int tlA = handshakeTeamLevel(PORT_STRICT, 0L, billet, 6000);
      ok(tlA == 123, "STRICT : userID=0 + billet → compte résolu DEPUIS le billet (TL=123, pas le défaut) [reçu=" + tlA + "]");

      // (B) STRICT : userID=0 SANS billet → REJET (aucun BootData).
      ok(handshakeTeamLevel(PORT_STRICT, 0L, "", 2500) == Integer.MIN_VALUE,
          "STRICT : userID=0 sans billet → REJETÉ (aucun BootData, pas de repli sur le compte par défaut)");

      // (C) STRICT : userID=0 + billet BIDON → REJET.
      ok(handshakeTeamLevel(PORT_STRICT, 0L, "bogus-" + java.util.UUID.randomUUID(), 2500) == Integer.MIN_VALUE,
          "STRICT : billet invalide → REJETÉ");

      // (D) PERMISSIF : userID=0 sans billet → compte PAR DÉFAUT (TL 1) — comportement historique inchangé.
      int tlD = handshakeTeamLevel(PORT_PERM, 0L, "", 6000);
      ok(tlD == 1, "PERMISSIF : userID=0 sans billet → compte par défaut servi (TL=1) — inchangé [reçu=" + tlD + "]");
    }
    System.out.println("[StrictSingleLoginTest] OK — " + checks + " assertions (login unique strict : résolution par billet + rejets + permissif inchangé)");
    System.exit(0);
  }

  /** Ouvre une connexion, envoie ClientInfo{userID, loginRequestID} (réémis pour couvrir la fenêtre de démarrage
   *  non fiable, cf. ChestWireTest), et renvoie BootData.userInfo.basicInfo.teamLevel — ou {@code Integer.MIN_VALUE}
   *  si AUCUN BootData n'arrive dans le délai (= connexion rejetée). */
  static int handshakeTeamLevel(int port, long userID, String billet, int timeoutMs) throws Exception {
    final CountDownLatch got = new CountDownLatch(1);
    final int[] tl = { Integer.MIN_VALUE };
    GruntConnection conn = new GruntBuilder(MessageFactory.getInstance())
        .setAddress("127.0.0.1").setPort(port).setConnectionWrapper(DHXORConnectionWrapper.class)
        .setConnectionListener(new GruntConnectionListener() {
          public void onOpen(GruntConnection c) {}
          public void onClose(GruntConnection c) {}
        }).buildConnection();
    conn.setListener(BootData.class, new GruntListener<BootData>() {
      public void onReceive(GruntConnection c, GruntMessage m) {
        tl[0] = ((BootData) m).userInfo.basicInfo.teamLevel;
        got.countDown();
      }
    });
    conn.open();
    long deadline = System.currentTimeMillis() + timeoutMs;
    boolean done = false;
    while (!done && System.currentTimeMillis() < deadline) {
      ClientInfo ci = new ClientInfo();
      ci.userID = userID;
      ci.loginRequestID = billet;
      conn.send(ci);
      done = got.await(400, TimeUnit.MILLISECONDS);
    }
    try { conn.close(); } catch (Throwable ignore) {}
    return tl[0];
  }
}
