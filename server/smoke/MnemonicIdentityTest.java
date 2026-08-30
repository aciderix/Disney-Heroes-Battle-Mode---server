import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;

import java.security.SecureRandom;

/**
 * Smoke assertif (chantier C1a) — lib d'identité mnémonique : génération/checksum, déterminisme phrase→userID,
 * dérivation Ed25519, défi-réponse (sign/verify), REJET d'usurpation. No-arg, dans regression.sh.
 */
public final class MnemonicIdentityTest {
    static int checks = 0;
    static void ok(boolean c, String msg) { checks++; if (!c) throw new AssertionError("ÉCHEC: " + msg); }

    public static void main(String[] args) throws Exception {
        // graine fixe → tests reproductibles
        SecureRandom rnd = new SecureRandom(new byte[]{1,2,3,4,5,6,7,8});

        // 1) génération : 8 mots, valide
        String phrase = MnemonicIdentity.generate(rnd);
        ok(phrase.split(" ").length == MnemonicIdentity.WORDS, "phrase = " + MnemonicIdentity.WORDS + " mots (reçu: " + phrase + ")");
        ok(MnemonicIdentity.isValid(phrase), "phrase générée valide: " + phrase);

        // 2) déterminisme : même phrase → même userID + même clé publique
        Identity a = MnemonicIdentity.fromPhrase(phrase);
        Identity b = MnemonicIdentity.fromPhrase(phrase);
        ok(a.userID == b.userID, "userID déterministe");
        ok(java.util.Arrays.equals(a.publicKey, b.publicKey), "clé publique déterministe");
        ok(a.userID > 0, "userID positif (long protocole)");

        // 3) checksum : une faute de frappe (mot connu mais mauvais) est rejetée
        int rejected = 0, tries = 0;
        String[] words = phrase.split(" ");
        for (String repl : new String[]{"abandon","zoo","ability","zone","able","young","about","zero"}) {
            if (repl.equals(words[MnemonicIdentity.WORDS - 1])) continue;
            words[MnemonicIdentity.WORDS - 1] = repl; tries++;
            if (!MnemonicIdentity.isValid(String.join(" ", words))) rejected++;
        }
        ok(rejected >= tries - 1, "checksum rejette les fautes de frappe (" + rejected + "/" + tries + ")");
        ok(!MnemonicIdentity.isValid(phrase.replaceFirst("\\S+", "notaword")), "mot hors wordlist rejeté");
        ok(!MnemonicIdentity.isValid("only three words"), "mauvais nombre de mots rejeté");

        // 4) défi-réponse : sign(challenge) vérifiable avec la clé publique
        byte[] challenge = new byte[32]; new SecureRandom(new byte[]{9}).nextBytes(challenge);
        byte[] sig = MnemonicIdentity.sign(a.keyPair.getPrivate(), challenge);
        ok(MnemonicIdentity.verify(a.publicKey, challenge, sig), "verify de la bonne signature");
        byte[] wrong = challenge.clone(); wrong[0] ^= 1;
        ok(!MnemonicIdentity.verify(a.publicKey, wrong, sig), "verify rejette un challenge altéré (anti-rejeu)");

        // 5) REJET d'usurpation : une AUTRE identité ne peut pas signer pour la clé publique de 'a'
        Identity mallory = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
        ok(mallory.userID != a.userID, "identités distinctes → userIDs distincts");
        byte[] forged = MnemonicIdentity.sign(mallory.keyPair.getPrivate(), challenge);
        ok(!MnemonicIdentity.verify(a.publicKey, challenge, forged), "usurpation rejetée (signature d'un autre)");

        // 6) unicité : 200 phrases → 200 userIDs distincts
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) ids.add(MnemonicIdentity.fromPhrase(MnemonicIdentity.generate(rnd)).userID);
        ok(ids.size() == 200, "200 phrases → 200 userIDs distincts (reçu " + ids.size() + ")");

        System.out.println("[MnemonicIdentityTest] OK — " + checks + " assertions ; phrase exemple = « " + phrase + " » → userID=" + a.userID);
    }
}
