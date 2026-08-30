import dhserver.UserStore;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;
import dhserver.auth.SessionStore;

import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * Smoke assertif (chantier C1c) — cœur du défi-réponse : challenge à usage unique, TTL/expiration, signature Ed25519
 * vérifiée contre la clé publique STOCKÉE, liaison loginRequestID→userID, REJET d'usurpation / rejeu / compte inconnu.
 * Horloge injectée pour tester l'expiration. No-arg, dans regression.sh.
 */
public final class SessionAuthTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }

    // horloge mutable de test
    static long clock = 1_000_000L;

    public static void main(String[] args) throws Exception {
        String db = Files.createTempFile("sess", ".sqlite").toString();
        SecureRandom rnd = new SecureRandom(new byte[]{77});
        Identity alice = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
        Identity bob   = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));

        try (UserStore store = new UserStore(db)) {
            store.registerAccount(alice.userID, alice.publicKey);
            store.registerAccount(bob.userID, bob.publicKey);
            SessionStore ss = new SessionStore(() -> clock);

            // 1) nominal : challenge → sign → verifyAndBind → session liée
            byte[] n1 = ss.issueChallenge(alice.userID);
            byte[] s1 = MnemonicIdentity.sign(alice.keyPair.getPrivate(), n1);
            ok(ss.verifyAndBind(alice.userID, "req-1", n1, s1, store), "auth nominale d'alice");
            ok(ss.authenticatedUser("req-1") == alice.userID, "session req-1 → alice");
            ok(ss.authenticatedUser("req-inconnu") == 0, "loginRequestID inconnu → 0");

            // 2) usage unique : rejouer le même nonce échoue (consommé)
            ok(!ss.verifyAndBind(alice.userID, "req-1b", n1, s1, store), "nonce déjà consommé → rejet (anti-rejeu)");

            // 3) usurpation : nonce d'alice signé par BOB → rejet
            byte[] n2 = ss.issueChallenge(alice.userID);
            byte[] forged = MnemonicIdentity.sign(bob.keyPair.getPrivate(), n2);
            ok(!ss.verifyAndBind(alice.userID, "req-2", n2, forged, store), "signature de bob pour alice → rejet");
            ok(ss.authenticatedUser("req-2") == 0, "pas de session après usurpation");

            // 4) mauvais userID annoncé pour le nonce (nonce émis pour alice, prétend être bob)
            byte[] n3 = ss.issueChallenge(alice.userID);
            byte[] s3 = MnemonicIdentity.sign(bob.keyPair.getPrivate(), n3);
            ok(!ss.verifyAndBind(bob.userID, "req-3", n3, s3, store), "userID ≠ celui du challenge → rejet");

            // 5) compte inconnu : userID non enregistré → rejet même avec une vraie signature
            Identity ghost = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
            byte[] n4 = ss.issueChallenge(ghost.userID);
            byte[] s4 = MnemonicIdentity.sign(ghost.keyPair.getPrivate(), n4);
            ok(!ss.verifyAndBind(ghost.userID, "req-4", n4, s4, store), "compte non enregistré → rejet");

            // 6) EXPIRATION du challenge : au-delà du TTL, la signature (valide) est refusée
            byte[] n5 = ss.issueChallenge(alice.userID);
            byte[] s5 = MnemonicIdentity.sign(alice.keyPair.getPrivate(), n5);
            clock += SessionStore.CHALLENGE_TTL_MS + 1;
            ok(!ss.verifyAndBind(alice.userID, "req-5", n5, s5, store), "challenge expiré → rejet");

            // 7) EXPIRATION de la session : une session valide expire après SESSION_TTL_MS
            byte[] n6 = ss.issueChallenge(alice.userID);
            byte[] s6 = MnemonicIdentity.sign(alice.keyPair.getPrivate(), n6);
            ok(ss.verifyAndBind(alice.userID, "req-6", n6, s6, store), "auth nominale (2)");
            ok(ss.authenticatedUser("req-6") == alice.userID, "session req-6 active");
            clock += SessionStore.SESSION_TTL_MS + 1;
            ok(ss.authenticatedUser("req-6") == 0, "session expirée → 0");
        }

        Files.deleteIfExists(java.nio.file.Path.of(db));
        System.out.println("[SessionAuthTest] OK — " + checks + " assertions (challenge/usage-unique/usurpation/inconnu/expiration)");
    }
}
