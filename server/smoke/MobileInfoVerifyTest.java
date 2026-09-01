import com.perblue.dhlauncher.MobileInfoVerifier;
import com.sun.net.httpserver.HttpServer;
import dhserver.UserStore;
import dhserver.auth.AuthService;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.SessionStore;
import dhserver.directory.ServerIdentity;
import dhserver.directory.ServerInfo;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * VÉRIF /info MOBILE (V3 brique 4) — PROUVE que le picker mobile ({@link MobileInfoVerifier}, Ed25519 pur-Java) vérifie la
 * fiche SIGNÉE d'un serveur, EXACTEMENT comme le launcher desktop : (1) POSITIF contre un vrai {@code AuthService /info}
 * (recompose la canonique identique à {@code ServerInfo.canonical} → signature valide → fiche + latence) ; (2) NÉGATIF :
 * un serveur qui SIGNE une fiche puis en ALTÈRE un champ (online) est REJETÉ (la protection anti-usurpation). Auto-contenu.
 */
public final class MobileInfoVerifyTest {
    static int checks = 0;
    static void ok(boolean c, String m) { if (!c) throw new AssertionError("ÉCHEC: " + m); checks++; }

    public static void main(String[] args) throws Exception {
        SecureRandom rnd = new SecureRandom(new byte[]{ 7 });
        Path db = Files.createTempFile("dh-info", ".db");
        try (UserStore store = new UserStore(db.toString())) {
            ServerIdentity id = ServerIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
            final ServerInfo info = new ServerInfo("Baroness Legion", "strict", "12.1.0", "0.2.0", 3, 50, 1_725_000_000_000L);
            SessionStore ss = new SessionStore();
            AuthService svc = new AuthService(0, ss, store, id, () -> info);
            svc.start();
            String base = "http://127.0.0.1:" + svc.port();

            // 1) POSITIF — le mobile vérifie la vraie fiche signée.
            MobileInfoVerifier.Result r = MobileInfoVerifier.verify(base);
            ok(r.ok, "vérif /info : " + r.message);
            ok("Baroness Legion".equals(r.name), "nom vérifié");
            ok("strict".equals(r.mode), "mode vérifié");
            ok(r.online == 3 && r.maxOnline == 50, "en-ligne/max vérifiés");
            ok(r.serverId == id.serverId(), "serverId == dérivé de la clé");
            ok("12.1.0".equals(r.gameVersion), "version jeu vérifiée");
            ok(r.pingMs >= 0, "latence mesurée (" + r.pingMs + " ms)");
            svc.stop();

            // 2) NÉGATIF — serveur MALVEILLANT : signe la fiche (online=3) mais en sert une ALTÉRÉE (online=999).
            MnemonicIdentity.Identity mal = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
            HttpServer bad = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            bad.createContext("/info", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                String nonce = q != null && q.startsWith("nonce=") ? q.substring(6) : "";
                ServerInfo real = new ServerInfo("Pirate", "open", "12.1.0", "0.2.0", 3, 50, 0);
                String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MnemonicIdentity.sign(mal.keyPair.getPrivate(), real.canonical(nonce)));
                String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(mal.publicKey);
                // JSON ALTÉRÉ : online=999 alors que la signature couvre online=3
                String body = "{\"protocol\":1,\"serverId\":" + mal.userID + ",\"name\":\"Pirate\",\"mode\":\"open\","
                    + "\"gameVersion\":\"12.1.0\",\"serverVersion\":\"0.2.0\",\"online\":999,\"maxOnline\":50,"
                    + "\"full\":false,\"openTime\":0,\"pubKey\":\"" + pub + "\",\"nonce\":\"" + nonce + "\",\"sig\":\"" + sig + "\"}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                OutputStream os = ex.getResponseBody(); os.write(b); os.close();
            });
            bad.start();
            MobileInfoVerifier.Result bad2 = MobileInfoVerifier.verify("http://127.0.0.1:" + bad.getAddress().getPort());
            ok(!bad2.ok, "fiche ALTÉRÉE (online falsifié) REJETÉE : " + bad2.message);
            bad.stop(0);
        } finally { Files.deleteIfExists(db); }
        System.out.println("MobileInfoVerifyTest OK — " + checks + " assertions (vérif /info mobile + anti-falsification PROUVÉES)");
    }
}
