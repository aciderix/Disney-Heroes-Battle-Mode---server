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
 * Smoke assertif (chantier C2a-2, volet « play » côté serveur) — le serveur « imprime un billet » (/auth/mint) pour
 * un joueur RÉCEMMENT AUTHENTIFIÉ, que content_server rendra au /login du client. Prouve : mint après auth → billet
 * lié (LoginServer strict l'acceptera) ; mint sans auth → 401 ; billet nominatif (lié au bon userID). HTTP réel.
 */
public final class AuthMintTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static HttpClient http = HttpClient.newHttpClient();
    static String base;

    public static void main(String[] args) throws Exception {
        String db = Files.createTempFile("mint", ".sqlite").toString();
        SecureRandom rnd = new SecureRandom(new byte[]{88});
        Identity alice = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
        Identity bob   = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));

        try (UserStore store = new UserStore(db)) {
            store.registerAccount(alice.userID, alice.publicKey);
            store.registerAccount(bob.userID, bob.publicKey);
            SessionStore ss = new SessionStore();
            AuthService svc = new AuthService(0, ss, store);
            svc.start();
            base = "http://127.0.0.1:" + svc.port();
            try {
                // mint AVANT authentification → refusé (le joueur n'a pas fait le défi-réponse)
                ok(postR("/auth/mint", "userID=" + alice.userID).statusCode() == 401, "mint sans auth → 401");

                // le LAUNCHER authentifie alice (challenge → sign → verify) → marque « alice authentifiée »
                byte[] nonce = Base64.getUrlDecoder().decode(json(post("/auth/challenge", "userID=" + alice.userID), "nonce"));
                byte[] sig = MnemonicIdentity.sign(alice.keyPair.getPrivate(), nonce);
                ok(postR("/auth/verify", "userID=" + alice.userID + "&loginRequestID=launcher-tmp&nonce=" + b64(nonce) + "&signature=" + b64(sig))
                        .statusCode() == 200, "verify launcher → 200 (alice authentifiée)");

                // content_server (au /login du client) demande un billet → billet frais, lié à alice
                String mint = post("/auth/mint", "userID=" + alice.userID);
                String lrid = json(mint, "loginRequestID");
                ok(!lrid.isEmpty(), "mint après auth → billet émis");
                ok(ss.authenticatedUser(lrid) == alice.userID, "billet nominatif : session liée à alice (LoginServer strict acceptera)");

                // bob n'a PAS été authentifié → pas de billet (impossible d'usurper alice non plus)
                ok(postR("/auth/mint", "userID=" + bob.userID).statusCode() == 401, "mint bob (non authentifié) → 401");
            } finally {
                svc.stop();
            }
        }

        Files.deleteIfExists(java.nio.file.Path.of(db));
        System.out.println("[AuthMintTest] OK — " + checks + " assertions (mint : billet nominatif après auth, refus sinon)");
    }

    static String post(String path, String body) throws Exception { return postR(path, body).body(); }
    static HttpResponse<String> postR(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
    static String json(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new AssertionError("champ '" + key + "' absent de: " + body);
        return m.group(1);
    }
}
