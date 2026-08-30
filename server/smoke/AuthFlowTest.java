import dhserver.UserStore;
import dhserver.auth.AuthService;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;
import dhserver.auth.SessionStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Smoke assertif (chantier C1d) — flux CRÉATION + RESTAURATION de compte de bout en bout, via le VRAI endpoint HTTP
 * AuthService (/auth/challenge, /auth/register, /auth/verify). Prouve : créer un compte à partir d'une phrase, le
 * RESTAURER sur un « client neuf » avec la même phrase (même userID déterministe), et les gardes de sécurité
 * (userID doit dériver de la clé ; register idempotent). No-arg, dans regression.sh.
 */
public final class AuthFlowTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] unb64(String s) { return Base64.getUrlDecoder().decode(s); }

    static HttpClient http = HttpClient.newHttpClient();
    static String base;

    public static void main(String[] args) throws Exception {
        String db = Files.createTempFile("authflow", ".sqlite").toString();
        SecureRandom rnd = new SecureRandom(new byte[]{123});

        // Le JOUEUR possède une PHRASE (sa seule clé). Rien côté serveur au départ.
        String phrase = MnemonicIdentity.generate(rnd);
        Identity me = MnemonicIdentity.fromPhrase(phrase);

        try (UserStore store = new UserStore(db)) {
            SessionStore ss = new SessionStore();
            AuthService svc = new AuthService(0, ss, store);
            svc.start();
            base = "http://127.0.0.1:" + svc.port();
            try {
                // 1) CRÉATION : compte inconnu → register (challenge → sign → /auth/register)
                ok(store.lookupPubKey(me.userID) == null, "compte inexistant avant création");
                byte[] n1 = challenge(me.userID);
                byte[] s1 = MnemonicIdentity.sign(me.keyPair.getPrivate(), n1);
                HttpResponse<String> reg = post("/auth/register", "userID=" + me.userID + "&loginRequestID=new-1"
                    + "&pubKey=" + b64(me.publicKey) + "&nonce=" + b64(n1) + "&signature=" + b64(s1));
                ok(reg.statusCode() == 200 && reg.body().contains("\"ok\":true"), "création → 200 ok");
                ok(java.util.Arrays.equals(store.lookupPubKey(me.userID), me.publicKey), "compte enregistré (clé publique stockée)");
                ok(ss.authenticatedUser("new-1") == me.userID, "session ouverte après création");

                // 2) RESTAURATION sur un « client neuf » : SEULE la phrase → même identité déterministe → login
                Identity restored = MnemonicIdentity.fromPhrase(phrase);   // re-dérivée de zéro
                ok(restored.userID == me.userID, "même phrase → même userID (restauration déterministe)");
                byte[] n2 = challenge(restored.userID);
                byte[] s2 = MnemonicIdentity.sign(restored.keyPair.getPrivate(), n2);
                HttpResponse<String> log = post("/auth/verify", "userID=" + restored.userID + "&loginRequestID=restore-1"
                    + "&nonce=" + b64(n2) + "&signature=" + b64(s2));
                ok(log.statusCode() == 200, "restauration → login 200 (compte existant)");
                ok(ss.authenticatedUser("restore-1") == me.userID, "session ouverte après restauration");

                // 3) SÉCURITÉ : réclamer un userID qui NE dérive PAS de sa clé → refus
                Identity other = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
                byte[] n3 = challenge(other.userID);
                byte[] s3 = MnemonicIdentity.sign(me.keyPair.getPrivate(), n3);   // signe avec MA clé
                HttpResponse<String> bad = post("/auth/register", "userID=" + other.userID + "&loginRequestID=bad-1"
                    + "&pubKey=" + b64(me.publicKey) + "&nonce=" + b64(n3) + "&signature=" + b64(s3));
                ok(bad.statusCode() != 200, "register refusé si userID ne dérive pas de la clé fournie");

                // 4) IDEMPOTENCE : re-register la même phrase → 200 (restauration via register aussi OK)
                byte[] n4 = challenge(me.userID);
                byte[] s4 = MnemonicIdentity.sign(me.keyPair.getPrivate(), n4);
                HttpResponse<String> again = post("/auth/register", "userID=" + me.userID + "&loginRequestID=new-2"
                    + "&pubKey=" + b64(me.publicKey) + "&nonce=" + b64(n4) + "&signature=" + b64(s4));
                ok(again.statusCode() == 200, "re-register même phrase → 200 (idempotent)");
            } finally {
                svc.stop();
            }
        }

        Files.deleteIfExists(java.nio.file.Path.of(db));
        System.out.println("[AuthFlowTest] OK — " + checks + " assertions (création + restauration + sécurité) ; phrase = « " + phrase + " »");
    }

    static byte[] challenge(long userID) throws Exception {
        String body = post("/auth/challenge", "userID=" + userID).body();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"nonce\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new AssertionError("nonce absent: " + body);
        return unb64(m.group(1));
    }
    static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
