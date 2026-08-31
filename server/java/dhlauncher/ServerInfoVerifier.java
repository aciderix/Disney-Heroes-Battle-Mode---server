package dhlauncher;

import dhserver.auth.MnemonicIdentity;
import dhserver.directory.ServerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANNUAIRE (brique 1) — VÉRIFIEUR de fiche serveur, côté LAUNCHER (game-free). Interroge {@code <baseUrl>/info} avec un
 * DÉFI frais (nonce), puis VÉRIFIE que la signature Ed25519 couvre bien {fiche + nonce} avec la clé publique annoncée
 * (via {@link MnemonicIdentity#verify}, la même crypto que les comptes). Si la vérification passe : la fiche est
 * authentique (non falsifiée) ET le serveur est VIVANT et détient la clé privée (le nonce frais interdit le rejeu).
 *
 * <p>Ce que ça garantit : « le détenteur de CETTE clé publique garantit CETTE fiche, à l'instant ». Ce que ça ne garantit
 * PAS seul : que cette clé = tel serveur « officiel » (règle premier-arrivé/annuaire, cf. {@code docs/SERVER_EXPLORER.md}).
 */
public final class ServerInfoVerifier {
    private static final SecureRandom RND = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Fiche vérifiée (immuable). {@code pubKeyB64} = l'identité prouvée du serveur. */
    public static final class Verified {
        public final long serverId; public final String name, mode, gameVersion, serverVersion, pubKeyB64;
        public final int online, maxOnline; public final boolean full; public final long openTime;
        Verified(long id, String name, String mode, String gv, String sv, int on, int max, boolean full, long open, String pub) {
            this.serverId = id; this.name = name; this.mode = mode; this.gameVersion = gv; this.serverVersion = sv;
            this.online = on; this.maxOnline = max; this.full = full; this.openTime = open; this.pubKeyB64 = pub;
        }
        public String toJson() {
            return "{\"serverId\":" + serverId + ",\"name\":\"" + jesc(name) + "\",\"mode\":\"" + jesc(mode) + "\""
                + ",\"gameVersion\":\"" + jesc(gameVersion) + "\",\"serverVersion\":\"" + jesc(serverVersion) + "\""
                + ",\"online\":" + online + ",\"maxOnline\":" + maxOnline + ",\"full\":" + full + ",\"openTime\":" + openTime
                + ",\"pubKey\":\"" + pubKeyB64 + "\",\"verified\":true}";
        }
    }

    /** Récupère + VÉRIFIE la fiche de {@code baseUrl} (ex. {@code http://host:8082}). Renvoie la fiche prouvée, ou lève. */
    public Verified verify(String baseUrl) throws Exception {
        byte[] n = new byte[18]; RND.nextBytes(n);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        String url = trim(baseUrl) + "/info?nonce=" + nonce;
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("/info → HTTP " + r.statusCode());
        String b = r.body();

        int protocol = (int) num(b, "protocol");
        if (protocol != ServerInfo.PROTOCOL) throw new IllegalStateException("protocole de fiche inconnu : " + protocol);
        String echoNonce = str(b, "nonce");
        if (!nonce.equals(echoNonce)) throw new IllegalStateException("nonce non renvoyé (rejeu ?) : " + echoNonce);

        String name = str(b, "name"), mode = str(b, "mode"), gv = str(b, "gameVersion"), sv = str(b, "serverVersion");
        int online = (int) num(b, "online"), maxOnline = (int) num(b, "maxOnline");
        long openTime = num(b, "openTime");
        long serverId = num(b, "serverId");
        String pub = str(b, "pubKey"), sig = str(b, "sig");

        // RECOMPOSE la chaîne canonique à partir des champs RENVOYÉS + le nonce ENVOYÉ, et vérifie la signature.
        // Si un seul champ a été altéré, la signature ne colle plus → rejet (c'est là toute la protection).
        ServerInfo info = new ServerInfo(name, mode, gv, sv, online, maxOnline, openTime);
        byte[] pubBytes = Base64.getUrlDecoder().decode(pub);
        byte[] sigBytes = Base64.getUrlDecoder().decode(sig);
        if (!MnemonicIdentity.verify(pubBytes, info.canonical(nonce), sigBytes))
            throw new IllegalStateException("signature de fiche INVALIDE (usurpation ou altération)");
        // Cohérence : serverId annoncé == dérivé de la clé publique (évite un serverId trompeur).
        if (MnemonicIdentity.userIdOf(pubBytes) != serverId)
            throw new IllegalStateException("serverId incohérent avec la clé publique");

        return new Verified(serverId, name, mode, gv, sv, online, maxOnline, info.full(), openTime, pub);
    }

    private static String trim(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    // --- extraction minimale de champs JSON (format PRODUIT PAR NOUS ; robustesse : lève si absent) ---
    private static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("champ numérique manquant : " + key);
        return Long.parseLong(m.group(1));
    }
    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("champ texte manquant : " + key);
        return junesc(m.group(1));
    }
    /** Dé-échappe une chaîne JSON (\" \\ \/ \n \r \t \b \f \\uXXXX) → texte brut (celui qui a été SIGNÉ). */
    private static String junesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case '"': b.append('"'); break;   case '\\': b.append('\\'); break; case '/': b.append('/'); break;
                case 'n': b.append('\n'); break;  case 'r': b.append('\r'); break;  case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;  case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                default: b.append(n);
            }
        }
        return b.toString();
    }
    private static String jesc(String s) {
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
