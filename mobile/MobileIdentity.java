package com.perblue.dhlauncher;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Identité joueur MNÉMONIQUE côté MOBILE — MIROIR EXACT de {@code dhserver.auth.MnemonicIdentity} du serveur, mais avec
 * l'{@link Ed25519} PUR-JAVA (le JDK/Android &lt; API 33 n'a pas d'Ed25519 natif). Même phrase → MÊME {@code userID} / clé
 * publique / signature que le launcher desktop (SunEC) — prouvé bit-à-bit par {@code server/smoke/MobileIdentityParityTest}.
 *
 * <p>phrase (8 mots BIP-0039) → seed (PBKDF2-HMAC-SHA512, {@code "mnemonic"}, 2048 it. — présent dès API 26) → clé Ed25519
 * DÉTERMINISTE (les 32 premiers octets du seed = clé privée RFC 8032) ; {@code userID} = SHA-256 de la clé publique X.509
 * (SPKI 44 o, préfixe Ed25519 fixe + 32 o bruts). §4 : dérivations STANDARD, rien d'inventé.
 */
public final class MobileIdentity {
    private MobileIdentity() {}

    public static final int WORDS = 8;
    private static final int ENTROPY_BYTES = 10;   // 80 bits
    private static final int CHECKSUM_BITS = 8;
    private static final BigInteger MASK11 = BigInteger.valueOf(0x7FF);

    /** Préfixe X.509 SubjectPublicKeyInfo pour une clé Ed25519 (12 o) — {@code getEncoded()} de SunEC produit ceci + 32 o. */
    private static final byte[] SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00 };

    private static final String[] W = Bip39Wordlist.WORDS;
    private static final Map<String, Integer> IDX = buildIndex();
    private static Map<String, Integer> buildIndex() {
        Map<String, Integer> m = new HashMap<String, Integer>(W.length * 2);
        for (int i = 0; i < W.length; i++) m.put(W[i], i);
        return m;
    }

    // ---- phrase ------------------------------------------------------------------------------------------------
    public static String generate() { return generate(new SecureRandom()); }
    public static String generate(SecureRandom rnd) {
        byte[] ent = new byte[ENTROPY_BYTES]; rnd.nextBytes(ent); return encode(ent);
    }

    static String encode(byte[] ent) {
        if (ent.length != ENTROPY_BYTES) throw new IllegalArgumentException("entropy=" + ent.length);
        int cs = sha256(ent)[0] & 0xff;
        BigInteger bits = new BigInteger(1, ent).shiftLeft(CHECKSUM_BITS).or(BigInteger.valueOf(cs));
        StringBuilder sb = new StringBuilder();
        for (int i = WORDS - 1; i >= 0; i--) {
            int idx = bits.shiftRight(i * 11).and(MASK11).intValue();
            if (sb.length() > 0) sb.append(' ');
            sb.append(W[idx]);
        }
        return sb.toString();
    }

    public static boolean isValid(String phrase) {
        try { decode(phrase); return true; } catch (RuntimeException e) { return false; }
    }

    static byte[] decode(String phrase) {
        String[] words = normalize(phrase).split(" ");
        if (words.length != WORDS) throw new IllegalArgumentException("attendu " + WORDS + " mots");
        BigInteger bits = BigInteger.ZERO;
        for (String wd : words) {
            Integer idx = IDX.get(wd);
            if (idx == null) throw new IllegalArgumentException("mot hors wordlist: " + wd);
            bits = bits.shiftLeft(11).or(BigInteger.valueOf(idx));
        }
        int cs = bits.and(BigInteger.valueOf(0xff)).intValue();
        byte[] ent = toFixed(bits.shiftRight(CHECKSUM_BITS), ENTROPY_BYTES);
        if ((sha256(ent)[0] & 0xff) != cs) throw new IllegalArgumentException("checksum invalide");
        return ent;
    }

    // ---- dérivations -------------------------------------------------------------------------------------------
    /** BIP39 : phrase → seed 64 octets (PBKDF2-HMAC-SHA512, salt {@code "mnemonic"}, 2048 it.). */
    public static byte[] toSeed(String phrase) {
        try {
            PBEKeySpec spec = new PBEKeySpec(normalize(phrase).toCharArray(),
                "mnemonic".getBytes(StandardCharsets.UTF_8), 2048, 512);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Identité complète dérivée d'une phrase (immuable). {@code secretSeed} (32 o) sert à signer — à garder privé. */
    public static final class Identity {
        public final long userID;
        public final byte[] publicKey;     // X.509 SPKI (44 o) = le vérifieur stocké serveur
        public final byte[] publicKeyRaw;  // 32 o
        private final byte[] secretSeed;    // 32 o (clé privée RFC 8032)
        Identity(long id, byte[] spki, byte[] raw, byte[] sk) {
            userID = id; publicKey = spki; publicKeyRaw = raw; secretSeed = sk;
        }
        /** Signe un challenge (nonce serveur) — preuve de possession, sans révéler la clé. Signature RAW (64 o). */
        public byte[] sign(byte[] challenge) { return Ed25519.sign(secretSeed, challenge); }
    }

    public static Identity fromPhrase(String phrase) {
        if (!isValid(phrase)) throw new IllegalArgumentException("phrase invalide");
        byte[] sk = Arrays.copyOf(toSeed(phrase), 32);
        byte[] raw = Ed25519.publicKeyFromSeed(sk);
        byte[] spki = new byte[SPKI_PREFIX.length + 32];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, 32);
        return new Identity(userIdOf(spki), spki, raw, sk);
    }

    /** userID (long POSITIF) = 8 premiers octets big-endian de SHA-256(SPKI), bit de signe masqué. Identique au serveur. */
    public static long userIdOf(byte[] publicKeyEncoded) {
        byte[] h = sha256(publicKeyEncoded);
        long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xffL);
        return v & 0x7FFFFFFFFFFFFFFFL;
    }

    // ---- utilitaires -------------------------------------------------------------------------------------------
    private static String normalize(String s) {
        return Normalizer.normalize(s.trim().toLowerCase().replaceAll("\\s+", " "), Normalizer.Form.NFKD);
    }
    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray(), out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return out;
    }
}
