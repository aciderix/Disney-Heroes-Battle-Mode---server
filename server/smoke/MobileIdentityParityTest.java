import com.perblue.dhlauncher.Ed25519;
import com.perblue.dhlauncher.MobileIdentity;
import dhserver.auth.MnemonicIdentity;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * PARITÉ CRYPTO MOBILE ↔ SERVEUR (V3 brique 1) — PROUVE que l'{@link Ed25519} pur-Java du picker mobile produit
 * EXACTEMENT la même identité que l'Ed25519 natif du JDK (SunEC) utilisé par {@link MnemonicIdentity} côté serveur.
 * Conséquence : une MÊME phrase donne le MÊME {@code userID} / clé publique sur le launcher desktop ET sur le mobile
 * (comptes portables entre appareils), et les signatures se vérifient en CROISÉ (le serveur, qui ne connaît QUE
 * l'Ed25519 SunEC, accepte les signatures forgées par le mobile). §8 : oracle = le serveur lui-même.
 *
 * Auto-contenu, aucun argument → inscrit dans {@code regression.sh}.
 */
public final class MobileIdentityParityTest {
    static int checks = 0;
    static void ok(boolean c, String m) { if (!c) throw new AssertionError("ÉCHEC: " + m); checks++; }

    public static void main(String[] args) throws Exception {
        SecureRandom rnd = new SecureRandom();

        // 1) VECTEURS FIXES : mêmes phrases → mêmes userID/clé des deux côtés + signatures croisées.
        for (int t = 0; t < 40; t++) {
            String phrase = MnemonicIdentity.generate(rnd);
            // les deux implémentations valident la MÊME phrase (schéma BIP39 8 mots identique)
            ok(MobileIdentity.isValid(phrase), "mobile isValid(" + phrase + ")");
            ok(MnemonicIdentity.isValid(phrase), "serveur isValid(" + phrase + ")");

            MnemonicIdentity.Identity srv = MnemonicIdentity.fromPhrase(phrase);
            MobileIdentity.Identity mob = MobileIdentity.fromPhrase(phrase);

            // seed BIP39 identique (PBKDF2-HMAC-SHA512)
            ok(Arrays.equals(MnemonicIdentity.toSeed(phrase), MobileIdentity.toSeed(phrase)), "seed BIP39 [" + phrase + "]");
            // clé publique X.509 (SPKI 44 o) BIT-À-BIT identique
            ok(Arrays.equals(srv.publicKey, mob.publicKey), "clé publique SPKI [" + phrase + "]");
            ok(mob.publicKey.length == 44, "SPKI = 44 o");
            // userID identique (dérivé de la clé)
            ok(srv.userID == mob.userID, "userID [" + phrase + "] srv=" + srv.userID + " mob=" + mob.userID);
            ok(mob.userID > 0, "userID positif");
            // le userID dérive bien de la clé mobile (règle serveur registerAndBind)
            ok(MnemonicIdentity.userIdOf(mob.publicKey) == mob.userID, "userIdOf(SPKI)==userID");

            byte[] nonce = new byte[32]; rnd.nextBytes(nonce);

            // signature MOBILE vérifiée par le SERVEUR (SunEC) — le cas qui compte pour l'auth strict
            byte[] mSig = mob.sign(nonce);
            ok(mSig.length == 64, "signature 64 o");
            ok(MnemonicIdentity.verify(mob.publicKey, nonce, mSig), "SERVEUR vérifie signature MOBILE [" + phrase + "]");

            // signature SERVEUR vérifiée par le MOBILE (Ed25519 pur-Java verify) — réciproque
            byte[] sSig = MnemonicIdentity.sign(srv.keyPair.getPrivate(), nonce);
            ok(Ed25519.verify(mob.publicKeyRaw, nonce, sSig), "MOBILE vérifie signature SERVEUR [" + phrase + "]");

            // Ed25519 pur-Java : déterminisme de la signature (RFC 8032 pure) — mêmes octets que SunEC
            ok(Arrays.equals(mSig, sSig), "signatures identiques (déterministe) [" + phrase + "]");

            // anti-falsification : nonce modifié → rejeté des deux côtés
            byte[] bad = nonce.clone(); bad[0] ^= 0x01;
            ok(!MnemonicIdentity.verify(mob.publicKey, bad, mSig), "serveur rejette nonce falsifié");
            ok(!Ed25519.verify(mob.publicKeyRaw, bad, mSig), "mobile rejette nonce falsifié");
        }

        // 2) MESSAGES de tailles variées (le chemin de signature dépend de la longueur du message).
        String phrase = MnemonicIdentity.generate(rnd);
        MobileIdentity.Identity mob = MobileIdentity.fromPhrase(phrase);
        for (int len : new int[] { 0, 1, 31, 32, 33, 63, 64, 65, 100, 255, 256, 1000 }) {
            byte[] msg = new byte[len]; rnd.nextBytes(msg);
            byte[] sig = mob.sign(msg);
            ok(MnemonicIdentity.verify(mob.publicKey, msg, sig), "serveur vérifie msg len=" + len);
            ok(Ed25519.verify(mob.publicKeyRaw, msg, sig), "mobile vérifie msg len=" + len);
        }

        // 3) phrase invalide (faute de frappe) rejetée par le mobile comme par le serveur.
        String good = MnemonicIdentity.generate(rnd);
        String[] w = good.split(" ");
        w[0] = w[0].equals("zoo") ? "zone" : "zoo";  // casse le checksum
        String typo = String.join(" ", w);
        ok(!MobileIdentity.isValid(typo) || !MnemonicIdentity.isValid(typo), "faute de frappe rejetée (au moins un côté, checksum)");
        // cohérence stricte : les deux doivent donner le même verdict de validité
        ok(MobileIdentity.isValid(typo) == MnemonicIdentity.isValid(typo), "verdict de validité identique sur faute de frappe");

        System.out.println("MobileIdentityParityTest OK — " + checks + " assertions (parité mobile↔serveur PROUVÉE)");
    }
}
