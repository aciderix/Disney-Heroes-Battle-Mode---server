import dhserver.auth.MnemonicIdentity;
import dhserver.auth.MnemonicIdentity.Identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * DEV (vérif EN JEU strict, chantier C2a-2 « play ») — enregistre + authentifie un compte par PHRASE contre un
 * {@code AuthService} en cours d'exécution (register = challenge → signe → /auth/register), pour qu'un client lancé
 * ensuite avec {@code -Ddh.userid=<userID>} obtienne un billet nominatif au /login.
 *
 * <p>Args : {@code <authBaseUrl> [mot1 mot2 …]}. Sans phrase → en génère une. Imprime le {@code userID} sur STDOUT
 * (capturable par run-online.sh) ; le reste (phrase, logs) sur STDERR.
 */
public final class StrictAuthSeed {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: StrictAuthSeed <authBaseUrl> [phrase...]"); System.exit(2); }
        String base = args[0].replaceAll("/+$", "");
        String phrase = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                                        : MnemonicIdentity.generate();
        Identity id = MnemonicIdentity.fromPhrase(phrase);
        HttpClient http = HttpClient.newHttpClient();

        // challenge → signe → register (crée le compte côté serveur + marque « authentifié »)
        byte[] nonce = Base64.getUrlDecoder().decode(json(post(http, base + "/auth/challenge", "userID=" + id.userID), "nonce"));
        byte[] sig = MnemonicIdentity.sign(id.keyPair.getPrivate(), nonce);
        HttpResponse<String> reg = postR(http, base + "/auth/register",
            "userID=" + id.userID + "&loginRequestID=seed&pubKey=" + b64(id.publicKey)
            + "&nonce=" + b64(nonce) + "&signature=" + b64(sig));
        if (reg.statusCode() != 200) { System.err.println("[seed] register échec: " + reg.statusCode() + " " + reg.body()); System.exit(1); }

        System.err.println("[seed] compte authentifié — phrase = « " + phrase + " » → userID=" + id.userID);
        System.out.println(id.userID);   // STDOUT = juste le userID
    }

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String post(HttpClient h, String url, String body) throws Exception { return postR(h, url, body).body(); }
    static HttpResponse<String> postR(HttpClient h, String url, String body) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String json(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("champ '" + key + "' absent de: " + body);
        return m.group(1);
    }
}
