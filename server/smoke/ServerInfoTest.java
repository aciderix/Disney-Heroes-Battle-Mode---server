import dhlauncher.ServerInfoVerifier;
import dhlauncher.ServerInfoVerifier.Verified;
import dhserver.UserStore;
import dhserver.auth.AuthService;
import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;
import dhserver.auth.SessionStore;
import dhserver.directory.ServerIdentity;
import dhserver.directory.ServerInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * ANNUAIRE (brique 1) — l'identité SIGNÉE du serveur + {@code GET /info}. Prouve : (1) persistance de l'identité serveur
 * (loadOrCreate) ; (2) bout-en-bout HTTP réel AuthService → {@link ServerInfoVerifier} (fiche vérifiée, champs exacts) ;
 * (3) la SIGNATURE protège vraiment (mauvaise clé = usurpation → rejet ; champ altéré → rejet ; nonce différent = rejeu
 * → rejet). Référence {@code System.exit} → auto-détecté ISOLÉ par {@code regression.sh}.
 */
public final class ServerInfoTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) passed++; else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        // (1) PERSISTANCE de l'identité serveur
        Path idFile = Files.createTempFile("srvid", ".txt"); Files.delete(idFile);
        ServerIdentity a = ServerIdentity.loadOrCreate(idFile);
        ServerIdentity b = ServerIdentity.loadOrCreate(idFile); // relecture
        ok(a.publicKeyB64().equals(b.publicKeyB64()), "identité serveur persistée (même clé publique au reload)");
        ok(a.serverId() == b.serverId() && a.serverId() != 0, "serverId stable et non nul");

        // (2) BOUT-EN-BOUT HTTP : AuthService /info signé → vérifieur launcher
        String db = Files.createTempFile("srvinfo", ".db").toString();
        System.setProperty("dh.db", db);
        try (UserStore store = new UserStore(db)) {
            SessionStore ss = new SessionStore();
            long open = System.currentTimeMillis();
            AuthService svc = new AuthService(0, ss, store, a,
                    () -> new ServerInfo("Legion FR", "strict", "12.1.0", "0.2.0", 3, 50, open));
            svc.start();
            try {
                String base = "http://127.0.0.1:" + svc.port();
                Verified v = new ServerInfoVerifier().verify(base);
                ok("Legion FR".equals(v.name), "fiche vérifiée : nom");
                ok("strict".equals(v.mode) && "12.1.0".equals(v.gameVersion) && "0.2.0".equals(v.serverVersion), "fiche vérifiée : mode + versions");
                ok(v.online == 3 && v.maxOnline == 50 && !v.full, "fiche vérifiée : en-ligne/capacité");
                ok(v.serverId == a.serverId() && v.pubKeyB64.equals(a.publicKeyB64()), "fiche vérifiée : identité == serveur");
            } finally { svc.stop(); }
        }

        // (3) LA SIGNATURE PROTÈGE — au niveau crypto (impossible via l'endpoint honnête, on teste directement)
        Identity idA = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate());
        Identity idB = MnemonicIdentity.fromPhrase(MnemonicIdentity.generate());
        ServerInfo info = new ServerInfo("Legion FR", "strict", "12.1.0", "0.2.0", 3, 50, 1000L);
        String nonce = "defi-frais-123";
        byte[] sig = MnemonicIdentity.sign(idA.keyPair.getPrivate(), info.canonical(nonce));

        ok(MnemonicIdentity.verify(idA.publicKey, info.canonical(nonce), sig), "signature valide (bonne clé, même fiche, même nonce)");
        ok(!MnemonicIdentity.verify(idB.publicKey, info.canonical(nonce), sig), "USURPATION rejetée (fiche signée par une autre clé)");
        ServerInfo tampered = new ServerInfo("Legion FR", "strict", "12.1.0", "0.2.0", 999, 50, 1000L); // en-ligne gonflé
        ok(!MnemonicIdentity.verify(idA.publicKey, tampered.canonical(nonce), sig), "ALTÉRATION rejetée (un champ modifié)");
        ok(!MnemonicIdentity.verify(idA.publicKey, info.canonical("autre-nonce"), sig), "REJEU rejeté (nonce différent)");

        System.out.println("ServerInfoTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }
}
