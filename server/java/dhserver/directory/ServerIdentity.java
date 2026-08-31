package dhserver.directory;

import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * ANNUAIRE (brique 1) — IDENTITÉ CRYPTOGRAPHIQUE D'UN SERVEUR. Chaque serveur auto-hébergé possède sa PROPRE paire de
 * clés Ed25519, réutilisant EXACTEMENT le mécanisme des comptes joueurs ({@link MnemonicIdentity}, §3 : on ne réinvente
 * aucune crypto). La <b>clé privée</b> (la phrase mnémonique) reste sur le serveur, dans un fichier local ; la
 * <b>clé publique</b> est publiée (dans {@code /info} et l'annuaire) pour que le launcher VÉRIFIE la signature.
 *
 * <p>But : un serveur SIGNE sa fiche (nom/adresse/mode…) → un pirate ne peut pas usurper une fiche existante sans la clé
 * privée (cf. {@code docs/SERVER_EXPLORER.md} §5). Game-free (aucune classe {@code com.perblue}) → embarquable côté
 * launcher comme côté serveur.
 */
public final class ServerIdentity {
    private final Identity identity;

    private ServerIdentity(Identity identity) { this.identity = identity; }

    /** Clé publique (X.509, 44 octets) encodée base64url — l'empreinte publique à publier/épingler. */
    public String publicKeyB64() { return b64(identity.publicKey); }

    /** userID dérivé de la clé publique (FNV, comme les comptes) — identifiant stable et compact du serveur. */
    public long serverId() { return MnemonicIdentity.userIdOf(identity.publicKey); }

    /** Signe un défi (octets) avec la clé PRIVÉE du serveur → signature base64url. */
    public String sign(byte[] challenge) { return b64(MnemonicIdentity.sign(identity.keyPair.getPrivate(), challenge)); }

    /**
     * Charge l'identité du serveur depuis {@code file} (phrase mnémonique) ; la CRÉE (phrase fraîche) et l'écrit si le
     * fichier est absent — première exécution. Best-effort {@code 0600} (POSIX) pour protéger la clé privée.
     */
    public static ServerIdentity loadOrCreate(Path file) throws IOException {
        String phrase;
        if (Files.isRegularFile(file)) {
            phrase = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!MnemonicIdentity.isValid(phrase)) throw new IOException("phrase d'identité serveur invalide : " + file);
        } else {
            phrase = MnemonicIdentity.generate();
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, (phrase + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            try {
                Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(file, perms);
            } catch (Exception ignore) { /* systèmes non-POSIX (Windows) : pas de chmod, toléré */ }
        }
        return new ServerIdentity(MnemonicIdentity.fromPhrase(phrase));
    }

    /** Fabrique une identité en mémoire depuis une phrase donnée (tests / dérivation launcher). */
    public static ServerIdentity fromPhrase(String phrase) { return new ServerIdentity(MnemonicIdentity.fromPhrase(phrase)); }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
