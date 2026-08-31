package dhserver.directory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ANNUAIRE (brique 2) — INSCRIPTION d'un serveur dans l'annuaire (table Supabase), AUTHENTIFIÉE PAR SIGNATURE. Le serveur
 * envoie sa fiche à l'Edge Function {@code register-server}, qui VÉRIFIE la signature Ed25519 avant d'écrire (service role
 * côté Supabase → jamais exposé). La <b>chaîne canonique</b> {@link #canonical} DOIT être identique bit à bit à celle de
 * la fonction Deno (même ordre de champs, séparateur US 0x1F). Game-free.
 *
 * <p>Sécurité : la table n'accepte AUCUNE écriture directe (RLS) ; seule la fonction écrit, et seulement une charge dont
 * la signature correspond à {@code pubKey} → un tiers ne peut ni usurper une fiche ni polluer la table.
 */
public final class ServerRegistration {
    /** Version du format d'inscription. Doit correspondre à {@code REG} dans la fonction Deno. */
    public static final String REG = "REG1";
    private static final char SEP = '\u001F';

    private ServerRegistration() {}

    /** Chaîne canonique SIGNÉE = {@code REG1␟pubKey␟name␟mode␟gameVersion␟serverVersion␟address␟infoUrl␟online␟maxOnline␟openTime␟issuedAt}. */
    public static byte[] canonical(String pubKey, ServerInfo info, String address, String infoUrl, long issuedAt) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(REG).append(SEP)
          .append(pubKey).append(SEP)
          .append(info.name).append(SEP)
          .append(info.mode).append(SEP)
          .append(info.gameVersion).append(SEP)
          .append(info.serverVersion).append(SEP)
          .append(address).append(SEP)
          .append(infoUrl).append(SEP)
          .append(info.online).append(SEP)
          .append(info.maxOnline).append(SEP)
          .append(info.openTime).append(SEP)
          .append(issuedAt);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Inscrit/rafraîchit le serveur dans l'annuaire. {@code directoryUrl} = URL du projet Supabase (ex.
     * {@code https://xxx.supabase.co}) ; {@code anonKey} = clé publique (anon/publishable, sans danger). {@code address}
     * = host:port de connexion (redirige {@code ServerType.LIVE}) ; {@code infoUrl} = base URL exposant {@code /info}.
     * @return le code HTTP de la fonction (200 = inscrit).
     */
    public static int register(String directoryUrl, String anonKey, ServerIdentity id, ServerInfo info,
                               String address, String infoUrl) throws Exception {
        long issuedAt = System.currentTimeMillis();
        String sig = id.sign(canonical(id.publicKeyB64(), info, address, infoUrl, issuedAt));
        String body = "{"
            + "\"pubKey\":\"" + id.publicKeyB64() + "\""
            + ",\"name\":\"" + esc(info.name) + "\""
            + ",\"mode\":\"" + esc(info.mode) + "\""
            + ",\"gameVersion\":\"" + esc(info.gameVersion) + "\""
            + ",\"serverVersion\":\"" + esc(info.serverVersion) + "\""
            + ",\"address\":\"" + esc(address) + "\""
            + ",\"infoUrl\":\"" + esc(infoUrl) + "\""
            + ",\"online\":" + info.online
            + ",\"maxOnline\":" + info.maxOnline
            + ",\"openTime\":" + info.openTime
            + ",\"issuedAt\":" + issuedAt
            + ",\"signature\":\"" + sig + "\"}";
        String url = trim(directoryUrl) + "/functions/v1/register-server";
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer " + anonKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    private static String trim(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(' '); else b.append(c);
            }
        }
        return b.toString();
    }
}
