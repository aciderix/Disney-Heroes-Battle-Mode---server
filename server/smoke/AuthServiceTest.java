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
 * Smoke assertif (chantier C1c 2/2) — AuthService HTTP de bout en bout : challenge → signature → verify via un VRAI
 * client HTTP, session établie ; signature invalide → 401 sans session. No-arg, dans regression.sh.
 */
public final class AuthServiceTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    public static void main(String[] args) throws Exception {
        String db = Files.createTempFile("authsvc", ".sqlite").toString();
        SecureRandom rnd = new SecureRandom(new byte[]{55});
        Identity alice = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
        Identity bob   = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));

        try (UserStore store = new UserStore(db)) {
            store.registerAccount(alice.userID, alice.publicKey);
            SessionStore ss = new SessionStore();
            AuthService svc = new AuthService(0, ss, store);   // port éphémère
            svc.start();
            int port = svc.port();
            String base = "http://127.0.0.1:" + port;
            HttpClient http = HttpClient.newHttpClient();

            try {
                // 1) challenge → nonce
                String chal = post(http, base + "/auth/challenge", "userID=" + alice.userID);
                byte[] nonce = Base64.getUrlDecoder().decode(json(chal, "nonce"));
                ok(nonce.length == 32, "challenge renvoie un nonce de 32 octets");

                // 2) verify avec la bonne signature → 200 ok, session établie
                byte[] sig = MnemonicIdentity.sign(alice.keyPair.getPrivate(), nonce);
                HttpResponse<String> vr = postR(http, base + "/auth/verify",
                    "userID=" + alice.userID + "&loginRequestID=req-A&nonce=" + b64(nonce) + "&signature=" + b64(sig));
                ok(vr.statusCode() == 200 && vr.body().contains("\"ok\":true"), "verify signature valide → 200 ok");
                ok(ss.authenticatedUser("req-A") == alice.userID, "session req-A → alice (partagée avec LoginServer)");

                // 3) verify avec une signature d'un AUTRE (usurpation) → 401, pas de session
                String chal2 = post(http, base + "/auth/challenge", "userID=" + alice.userID);
                byte[] nonce2 = Base64.getUrlDecoder().decode(json(chal2, "nonce"));
                byte[] forged = MnemonicIdentity.sign(bob.keyPair.getPrivate(), nonce2);
                HttpResponse<String> vr2 = postR(http, base + "/auth/verify",
                    "userID=" + alice.userID + "&loginRequestID=req-B&nonce=" + b64(nonce2) + "&signature=" + b64(forged));
                ok(vr2.statusCode() == 401, "verify signature d'un autre → 401");
                ok(ss.authenticatedUser("req-B") == 0, "pas de session après usurpation");

                // 4) challenge pour un compte inconnu : verify échoue (401) même si le client signe correctement
                String chal3 = post(http, base + "/auth/challenge", "userID=" + bob.userID);
                byte[] nonce3 = Base64.getUrlDecoder().decode(json(chal3, "nonce"));
                byte[] sig3 = MnemonicIdentity.sign(bob.keyPair.getPrivate(), nonce3);
                HttpResponse<String> vr3 = postR(http, base + "/auth/verify",
                    "userID=" + bob.userID + "&loginRequestID=req-C&nonce=" + b64(nonce3) + "&signature=" + b64(sig3));
                ok(vr3.statusCode() == 401, "compte non enregistré → 401");
            } finally {
                svc.stop();
            }
        }

        Files.deleteIfExists(java.nio.file.Path.of(db));
        System.out.println("[AuthServiceTest] OK — " + checks + " assertions (challenge/verify HTTP, usurpation, compte inconnu)");
    }

    static String post(HttpClient http, String url, String body) throws Exception { return postR(http, url, body).body(); }
    static HttpResponse<String> postR(HttpClient http, String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
    /** Extraction minimale d'un champ string d'un JSON plat {@code {"k":"v"}} (évite une dépendance JSON). */
    static String json(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new AssertionError("champ '" + key + "' absent de: " + body);
        return m.group(1);
    }
}
