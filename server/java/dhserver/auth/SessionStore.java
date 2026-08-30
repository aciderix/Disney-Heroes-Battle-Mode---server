package dhserver.auth;

import dhserver.UserStore;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Sessions d'authentification MNÉMONIQUE (chantier C1c) — le cœur du défi-réponse asymétrique, côté serveur.
 *
 * <p>Flux : {@link #issueChallenge(long)} émet un <b>nonce</b> à usage unique (TTL court) lié à un {@code userID} ;
 * le launcher le <b>signe</b> (Ed25519) avec la clé privée dérivée de la phrase ; {@link #verifyAndBind} vérifie la
 * signature contre la <b>clé publique STOCKÉE</b> ({@link UserStore#lookupPubKey}) et, si OK, <b>lie</b>
 * {@code loginRequestID → userID} (la session que {@code LoginServer} consultera pour authentifier le socket).
 *
 * <p><b>Aucun secret côté serveur</b> : on ne détient que la clé publique. Nonce à usage unique (anti-rejeu), TTL sur
 * challenges et sessions. Horloge injectable pour tester l'expiration. Thread-safe.
 */
public final class SessionStore {
    public static final long CHALLENGE_TTL_MS = 60_000L;    // 1 min pour signer
    public static final long SESSION_TTL_MS   = 300_000L;   // 5 min pour ouvrir le socket

    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Entry> pending  = new ConcurrentHashMap<>();  // nonce (base64) → challenge en attente
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();  // loginRequestID → session authentifiée
    private final LongSupplier clock;

    public SessionStore() { this(System::currentTimeMillis); }
    /** @param clock source de temps (injectable pour les tests d'expiration). */
    public SessionStore(LongSupplier clock) { this.clock = clock; }

    private static final class Entry {
        final long userID, expiry;
        Entry(long u, long e) { userID = u; expiry = e; }
    }

    /** Émet un nonce (challenge) lié à {@code userID}, à usage unique, TTL {@link #CHALLENGE_TTL_MS}. */
    public byte[] issueChallenge(long userID) {
        byte[] nonce = new byte[32];
        rnd.nextBytes(nonce);
        pending.put(b64(nonce), new Entry(userID, clock.getAsLong() + CHALLENGE_TTL_MS));
        return nonce;
    }

    /**
     * Vérifie la signature du nonce et, si valide, LIE {@code loginRequestID → userID}. Le nonce est CONSOMMÉ
     * (retiré même en cas d'échec → pas de re-tentative sur le même challenge). Renvoie true si authentifié.
     */
    public boolean verifyAndBind(long userID, String loginRequestID, byte[] nonce, byte[] signature, UserStore store)
            throws java.sql.SQLException {
        if (loginRequestID == null || loginRequestID.isEmpty() || nonce == null || signature == null) return false;
        Entry p = pending.remove(b64(nonce));                 // usage unique
        long t = clock.getAsLong();
        if (p == null || p.userID != userID || t > p.expiry) return false;   // nonce inconnu / mauvais userID / expiré
        byte[] pub = store.lookupPubKey(userID);
        if (pub == null) return false;                        // compte non enregistré
        if (!MnemonicIdentity.verify(pub, nonce, signature)) return false;   // signature invalide → rejet
        sessions.put(loginRequestID, new Entry(userID, t + SESSION_TTL_MS));
        return true;
    }

    /** userID authentifié pour ce {@code loginRequestID}, ou {@code 0} si aucun / expiré (consultée par LoginServer). */
    public long authenticatedUser(String loginRequestID) {
        if (loginRequestID == null) return 0;
        Entry s = sessions.get(loginRequestID);
        if (s == null) return 0;
        if (clock.getAsLong() > s.expiry) { sessions.remove(loginRequestID); return 0; }
        return s.userID;
    }

    /** Purge les challenges et sessions expirés (à appeler périodiquement). */
    public void sweep() {
        long t = clock.getAsLong();
        pending.entrySet().removeIf(e -> t > e.getValue().expiry);
        sessions.entrySet().removeIf(e -> t > e.getValue().expiry);
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
