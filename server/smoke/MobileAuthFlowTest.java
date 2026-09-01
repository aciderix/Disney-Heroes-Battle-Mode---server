import com.perblue.dhlauncher.MobileAuth;
import com.perblue.dhlauncher.MobileIdentity;
import dhserver.UserStore;
import dhserver.auth.AuthService;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.SessionStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * HANDSHAKE MOBILE ↔ AuthService (V3 brique 3a) — PROUVE que le picker mobile authentifie un compte mnémonique par
 * défi-réponse Ed25519 contre le VRAI {@code AuthService} du serveur (via {@link MobileAuth} + {@link MobileIdentity}),
 * et qu'après le handshake le serveur peut FRAPPER LE BILLET nominatif ({@code mintForUser}) → le {@code /login} strict
 * du jeu se liera au bon compte. Le serveur ne connaît QUE l'Ed25519 SunEC : il accepte donc la signature forgée côté
 * mobile (cohérent avec la parité de la brique 1). Auto-contenu → {@code regression.sh}.
 */
public final class MobileAuthFlowTest {
    static int checks = 0;
    static void ok(boolean c, String m) { if (!c) throw new AssertionError("ÉCHEC: " + m); checks++; }

    public static void main(String[] args) throws Exception {
        SecureRandom rnd = new SecureRandom(new byte[]{ 42 });
        Path db = Files.createTempFile("dh-mobauth", ".db");
        try (UserStore store = new UserStore(db.toString())) {
            SessionStore ss = new SessionStore();
            AuthService svc = new AuthService(0, ss, store);
            svc.start();
            String base = "http://127.0.0.1:" + svc.port();

            // 1) COMPTE NEUF : le mobile génère une phrase, dérive l'identité, s'authentifie (register).
            String phrase = MobileIdentity.generate(rnd);
            MobileIdentity.Identity id = MobileIdentity.fromPhrase(phrase);
            ok(store.lookupPubKey(id.userID) == null, "compte absent avant handshake");
            ok(ss.mintForUser(id.userID) == null, "pas de billet avant handshake (non authentifié)");

            MobileAuth.Result r = MobileAuth.authenticate(base, id);
            ok(r.ok, "handshake compte neuf : " + r.message);
            // le serveur a ENREGISTRÉ la clé (le userID dérive bien de la clé, signature valide)
            byte[] stored = store.lookupPubKey(id.userID);
            ok(stored != null && java.util.Arrays.equals(stored, id.publicKey), "clé publique enregistrée == clé mobile");
            // → le serveur peut frapper le billet nominatif (ce que fera content_server au /login)
            String ticket = ss.mintForUser(id.userID);
            ok(ticket != null && !ticket.isEmpty(), "billet frappé après handshake (compte authentifié)");
            // et ce billet authentifie bien le socket sur CE userID (ce que lira LoginServer)
            ok(ss.authenticatedUser(ticket) == id.userID, "billet → userID correct");

            // 2) RECONNEXION (même phrase) : idempotent, ré-authentifie.
            MobileIdentity.Identity again = MobileIdentity.fromPhrase(phrase);
            ok(MobileAuth.authenticate(base, again).ok, "handshake reconnexion (même phrase)");
            ok(ss.mintForUser(again.userID) != null, "billet à la reconnexion");

            // 3) PORTABILITÉ desktop→mobile : un compte créé côté SERVEUR (SunEC) est authentifiable par le MOBILE
            //    (même phrase → même userID/clé, cf. brique 1) — preuve que les comptes se partagent entre appareils.
            String phrase2 = MnemonicIdentity.generate(rnd);
            MnemonicIdentity.Identity srv = MnemonicIdentity.fromPhrase(phrase2);
            store.registerAccount(srv.userID, srv.publicKey);              // « créé sur le launcher desktop »
            MobileIdentity.Identity mob = MobileIdentity.fromPhrase(phrase2);
            ok(mob.userID == srv.userID, "même userID desktop↔mobile [portabilité]");
            ok(MobileAuth.authenticate(base, mob).ok, "le mobile authentifie un compte créé côté desktop");
            ok(ss.mintForUser(mob.userID) != null, "billet pour le compte porté");

            // 4) usurpation : un compte inconnu (jamais authentifié) n'a pas de billet.
            MobileIdentity.Identity ghost = MobileIdentity.fromPhrase(MnemonicIdentity.generate(rnd));
            ok(ss.mintForUser(ghost.userID) == null, "aucun billet pour un compte non authentifié");

            svc.stop();
        } finally { Files.deleteIfExists(db); }
        System.out.println("MobileAuthFlowTest OK — " + checks + " assertions (handshake mobile↔serveur + portabilité PROUVÉS)");
    }
}
