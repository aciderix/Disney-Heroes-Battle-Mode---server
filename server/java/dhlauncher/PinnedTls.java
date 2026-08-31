package dhlauncher;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * ADMIN DISTANT TLS (chantier F) — épinglage de certificat. Le launcher se connecte à l'{@code AdminService} HTTPS d'un
 * serveur distant dont le certificat est en général AUTO-SIGNÉ ; on le fait confiance UNIQUEMENT si l'empreinte SHA-256
 * de son certificat feuille correspond à celle épinglée par l'opérateur (imprimée par le serveur au boot). Game-free
 * (JDK pur). La vérification du nom d'hôte est désactivée côté client (on épingle le cert exact à la place).
 */
public final class PinnedTls {
    private PinnedTls() {}

    public static SSLContext pinning(String sha256hex) throws Exception {
        final String want = sha256hex.toLowerCase().replaceAll("[^0-9a-f]", "");
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("aucun certificat");
                try {
                    byte[] d = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder sb = new StringBuilder(d.length * 2);
                    for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                    if (!sb.toString().equals(want)) throw new CertificateException("empreinte du certificat non épinglée");
                } catch (java.security.NoSuchAlgorithmException e) { throw new CertificateException(e); }
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null);
        return ctx;
    }
}
