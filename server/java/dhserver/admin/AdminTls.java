package dhserver.admin;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Enumeration;

/**
 * ADMIN — TLS (chantier F). L'{@code AdminService} peut servir en HTTPS quand l'hébergeur fournit un keystore PKCS12
 * (créé UNE fois avec le {@code keytool} du JDK — pas besoin de keytool au RUNTIME). Le jeton opérateur transite alors
 * CHIFFRÉ. Le certificat étant en général auto-signé, le launcher l'épingle par son EMPREINTE SHA-256 (imprimée au boot).
 *
 * <pre>
 * Créer le keystore (une fois, sur n'importe quelle machine avec un JDK) :
 *   keytool -genkeypair -storetype PKCS12 -keystore admin.p12 -keyalg RSA -keysize 2048 \
 *           -alias admin -dname "CN=mon-serveur" -validity 3650 -storepass MON_MDP
 * Puis lancer le serveur avec :  DH_ADMIN_BIND=0.0.0.0 DH_ADMIN_TOKEN=... \
 *           DH_ADMIN_TLS_KEYSTORE=.../admin.p12 DH_ADMIN_TLS_PASS=MON_MDP ./run.sh
 * </pre>
 */
public final class AdminTls {
    private AdminTls() {}

    /** Charge le keystore PKCS12 et construit un {@link SSLContext} serveur (KeyManager). */
    public static SSLContext serverSslContext(File keystore, char[] pass) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystore)) { ks.load(in, pass); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Empreinte SHA-256 (hex, minuscules) du 1er certificat du keystore — à ÉPINGLER dans le launcher. */
    public static String fingerprintSha256(File keystore, char[] pass) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystore)) { ks.load(in, pass); }
        Certificate cert = null;
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
            Certificate c = ks.getCertificate(e.nextElement());
            if (c != null) { cert = c; break; }
        }
        if (cert == null) throw new IllegalStateException("aucun certificat dans le keystore");
        return hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
