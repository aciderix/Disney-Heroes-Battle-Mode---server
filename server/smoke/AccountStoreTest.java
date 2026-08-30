import dhserver.UserStore;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;

import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * Smoke assertif (chantier C1b) — vérifieur de comptes dans UserStore : register (userID→clé publique), lookup,
 * idempotence (ré-enregistrement même clé), rejet d'une clé différente pour un userID existant, persistance
 * (réouverture de la DB). No-arg, dans regression.sh.
 */
public final class AccountStoreTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }

    public static void main(String[] args) throws Exception {
        String db = Files.createTempFile("accts", ".sqlite").toString();
        SecureRandom rnd = new SecureRandom(new byte[]{42});
        Identity alice = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
        Identity bob   = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));

        try (UserStore store = new UserStore(db)) {
            // 1) inconnu → null
            ok(store.lookupPubKey(alice.userID) == null, "compte inconnu → lookup null");

            // 2) register → true (nouveau), lookup renvoie la clé publique exacte
            ok(store.registerAccount(alice.userID, alice.publicKey), "register alice = nouveau (true)");
            ok(java.util.Arrays.equals(store.lookupPubKey(alice.userID), alice.publicKey), "lookup renvoie la clé publique d'alice");

            // 3) idempotent : ré-enregistrer la MÊME clé → false (déjà là), pas d'erreur
            ok(!store.registerAccount(alice.userID, alice.publicKey), "re-register même clé = déjà présent (false)");

            // 4) collision : même userID, clé DIFFÉRENTE → rejet (jamais accepté silencieusement)
            boolean threw = false;
            try { store.registerAccount(alice.userID, bob.publicKey); } catch (Exception e) { threw = true; }
            ok(threw, "clé différente pour userID existant → rejetée");
            ok(java.util.Arrays.equals(store.lookupPubKey(alice.userID), alice.publicKey), "clé d'alice inchangée après tentative de collision");

            // 5) second compte indépendant
            ok(store.registerAccount(bob.userID, bob.publicKey), "register bob = nouveau");
            ok(bob.userID != alice.userID, "userIDs distincts");
        }

        // 6) PERSISTANCE : réouverture de la DB → les comptes sont là (le vérifieur survit au redémarrage)
        try (UserStore store2 = new UserStore(db)) {
            ok(java.util.Arrays.equals(store2.lookupPubKey(alice.userID), alice.publicKey), "alice persistée après réouverture");
            ok(java.util.Arrays.equals(store2.lookupPubKey(bob.userID), bob.publicKey), "bob persisté après réouverture");

            // 7) bout-en-bout : la clé publique stockée vérifie une signature de challenge de la clé privée d'alice
            byte[] challenge = new byte[32]; new SecureRandom(new byte[]{7}).nextBytes(challenge);
            byte[] sig = MnemonicIdentity.sign(alice.keyPair.getPrivate(), challenge);
            ok(MnemonicIdentity.verify(store2.lookupPubKey(alice.userID), challenge, sig), "clé publique stockée vérifie la signature d'alice");
            byte[] forged = MnemonicIdentity.sign(bob.keyPair.getPrivate(), challenge);
            ok(!MnemonicIdentity.verify(store2.lookupPubKey(alice.userID), challenge, forged), "usurpation rejetée via le vérifieur stocké");
        }

        Files.deleteIfExists(java.nio.file.Path.of(db));
        System.out.println("[AccountStoreTest] OK — " + checks + " assertions (register/lookup/idempotence/collision/persistance/défi-réponse)");
    }
}
