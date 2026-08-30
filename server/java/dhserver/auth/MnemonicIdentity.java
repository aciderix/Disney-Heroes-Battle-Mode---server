package dhserver.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Identité joueur MNÉMONIQUE (chantier C, {@code docs/DISTRIBUTION.md} §2) — modèle « seed phrase » type wallet crypto.
 *
 * <p>phrase ({@value #WORDS} mots, wordlist BIP-0039 anglaise) → seed (BIP39 PBKDF2-HMAC-SHA512) → paire Ed25519
 * <b>DÉTERMINISTE</b> ; le {@code userID} (le {@code long} attendu par le protocole du jeu) dérive de la <b>clé
 * publique</b>. Le serveur ne stocke que la clé publique (le VÉRIFIEUR) — <b>aucun secret côté serveur</b> : auth par
 * défi-réponse asymétrique, la clé privée ne quitte jamais la machine du joueur, rien de sensible ne transite.
 *
 * <p>Pur JDK 21 (Ed25519 natif) — <b>aucune dépendance externe</b>. §4 : rien d'inventé, dérivations STANDARD
 * (BIP39 seed + Ed25519). §3 : couche plateforme, la logique du jeu n'est pas touchée.
 */
public final class MnemonicIdentity {
    private MnemonicIdentity() {}

    /** Nb de mots de la phrase. 8 mots × 11 bits = 88 bits = <b>80 bits d'entropie</b> (10 octets) + 8 bits de
     *  checksum (octet-aligné ; jeu-approprié — 12 mots BIP39 stricts = 128 bits). Changer WORDS impose de garder
     *  l'alignement octet (ENTROPY_BYTES = (WORDS*11 - CHECKSUM_BITS)/8 entier). */
    public static final int WORDS = 8;
    private static final int ENTROPY_BYTES = 10;   // 80 bits
    private static final int CHECKSUM_BITS = 8;    // = SHA-256(entropy)[0]
    private static final BigInteger MASK11 = BigInteger.valueOf(0x7FF);

    private static final String[] W = Bip39Wordlist.WORDS;
    private static final Map<String, Integer> IDX = buildIndex();
    private static Map<String, Integer> buildIndex() {
        Map<String, Integer> m = new HashMap<>(W.length * 2);
        for (int i = 0; i < W.length; i++) m.put(W[i], i);
        return m;
    }

    // ========================================================= génération / validation de la phrase
    /** Génère une phrase aléatoire de {@value #WORDS} mots (avec checksum), source {@link SecureRandom} par défaut. */
    public static String generate() { return generate(new SecureRandom()); }
    public static String generate(SecureRandom rnd) {
        byte[] ent = new byte[ENTROPY_BYTES]; rnd.nextBytes(ent); return encode(ent);
    }

    /** entropy (10 octets) → phrase {@value #WORDS} mots (entropie + checksum, big-endian, 11 bits/mot). */
    static String encode(byte[] ent) {
        if (ent.length != ENTROPY_BYTES) throw new IllegalArgumentException("entropy=" + ent.length + " (attendu " + ENTROPY_BYTES + ")");
        int cs = sha256(ent)[0] & 0xff;
        BigInteger bits = new BigInteger(1, ent).shiftLeft(CHECKSUM_BITS).or(BigInteger.valueOf(cs)); // 88 bits
        StringBuilder sb = new StringBuilder();
        for (int i = WORDS - 1; i >= 0; i--) {
            int idx = bits.shiftRight(i * 11).and(MASK11).intValue();
            if (sb.length() > 0) sb.append(' ');
            sb.append(W[idx]);
        }
        return sb.toString();
    }

    /** true si la phrase a {@value #WORDS} mots connus ET un checksum valide (détecte les fautes de frappe). */
    public static boolean isValid(String phrase) {
        try { decode(phrase); return true; } catch (RuntimeException e) { return false; }
    }

    /** phrase → entropy (10 octets) en vérifiant le checksum ; lève {@link IllegalArgumentException} si invalide. */
    static byte[] decode(String phrase) {
        String[] words = normalize(phrase).split(" ");
        if (words.length != WORDS) throw new IllegalArgumentException("attendu " + WORDS + " mots, reçu " + words.length);
        BigInteger bits = BigInteger.ZERO;
        for (String wd : words) {
            Integer idx = IDX.get(wd);
            if (idx == null) throw new IllegalArgumentException("mot hors wordlist: " + wd);
            bits = bits.shiftLeft(11).or(BigInteger.valueOf(idx));
        }
        int cs = bits.and(BigInteger.valueOf(0xff)).intValue();
        byte[] ent = toFixed(bits.shiftRight(CHECKSUM_BITS), ENTROPY_BYTES);
        if ((sha256(ent)[0] & 0xff) != cs) throw new IllegalArgumentException("checksum invalide (faute de frappe ?)");
        return ent;
    }

    // ========================================================= dérivations
    /** BIP39 : phrase → seed 64 octets (PBKDF2-HMAC-SHA512, salt {@code "mnemonic"}, 2048 itérations). */
    public static byte[] toSeed(String phrase) {
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                normalize(phrase).toCharArray(), "mnemonic".getBytes(StandardCharsets.UTF_8), 2048, 512);
            return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    /** seed → paire Ed25519 <b>déterministe</b> : les 32 premiers octets du seed servent de graine privée, la KPG
     *  Ed25519 (SunEC) en dérive la clé publique (scalaire × point de base). Vérifié stable dans le JDK. */
    public static KeyPair toKeyPair(byte[] seed) {
        final byte[] sk = Arrays.copyOf(seed, 32);
        try {
            SecureRandom det = new SecureRandom() {
                int p = 0;
                @Override public void nextBytes(byte[] out) { for (int i = 0; i < out.length; i++) out[i] = sk[p++ % sk.length]; }
            };
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            kpg.initialize(new NamedParameterSpec("Ed25519"), det);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    /** Identité complète (immuable) dérivée d'une phrase. */
    public static final class Identity {
        public final long userID;
        public final KeyPair keyPair;
        public final byte[] publicKey;   // encodage X.509 (44 octets) = le vérifieur stocké serveur
        Identity(long id, KeyPair kp, byte[] pub) { userID = id; keyPair = kp; publicKey = pub; }
    }

    public static Identity fromPhrase(String phrase) {
        if (!isValid(phrase)) throw new IllegalArgumentException("phrase invalide");
        KeyPair kp = toKeyPair(toSeed(phrase));
        byte[] pub = kp.getPublic().getEncoded();
        return new Identity(userIdOf(pub), kp, pub);
    }

    /** userID (long POSITIF) = 8 premiers octets big-endian de SHA-256(pubkey), bit de signe masqué. */
    public static long userIdOf(byte[] publicKeyEncoded) {
        byte[] h = sha256(publicKeyEncoded);
        long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xffL);
        return v & 0x7FFFFFFFFFFFFFFFL;
    }

    // ========================================================= défi-réponse (auth)
    /** Signe un challenge (nonce serveur) avec la clé privée — la preuve de possession, sans révéler la clé. */
    public static byte[] sign(PrivateKey priv, byte[] challenge) {
        try { Signature s = Signature.getInstance("Ed25519"); s.initSign(priv); s.update(challenge); return s.sign(); }
        catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    /** Vérifie une signature de challenge avec la clé publique stockée (le serveur ne détient aucun secret). */
    public static boolean verify(byte[] publicKeyEncoded, byte[] challenge, byte[] sig) {
        try {
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKeyEncoded));
            Signature v = Signature.getInstance("Ed25519"); v.initVerify(pub); v.update(challenge); return v.verify(sig);
        } catch (GeneralSecurityException e) { return false; }
    }

    // ========================================================= utilitaires
    /** Normalisation NFKD + minuscules + espaces simples (cohérente signeur/vérifieur — on contrôle les deux). */
    private static String normalize(String s) {
        return Normalizer.normalize(s.trim().toLowerCase().replaceAll("\\s+", " "), Normalizer.Form.NFKD);
    }
    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }
    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray(), out = new byte[len];
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, raw.length - copy, out, len - copy, copy);
        return out;
    }
}
