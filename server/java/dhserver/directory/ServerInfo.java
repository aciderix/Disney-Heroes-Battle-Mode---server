package dhserver.directory;

import java.nio.charset.StandardCharsets;

/**
 * ANNUAIRE (brique 1) — FICHE PUBLIQUE d'un serveur, telle qu'exposée par {@code GET /info} et publiée dans l'annuaire.
 * Immuable, game-free. Le point crucial est {@link #canonical(String)} : la chaîne EXACTE qui est SIGNÉE côté serveur et
 * RE-COMPOSÉE côté launcher pour vérifier la signature — les deux côtés DOIVENT produire des octets identiques, donc le
 * format est figé (champs dans un ordre fixe, séparés par le caractère US {@code }, puis le nonce du défi). Toute
 * évolution du format = un nouveau {@code PROTOCOL}.
 */
public final class ServerInfo {
    /** Version du format de fiche/signature. Incrémenter si {@link #canonical} change (compat launcher). */
    public static final int PROTOCOL = 1;
    /** Séparateur d'unité (US, 0x1F) — hors du jeu de caractères des champs affichables → délimiteur non ambigu. */
    private static final char SEP = '\u001F';

    public final String name;          // nom affiché du serveur (choisi par l'hébergeur)
    public final String mode;          // "strict" (login mnémonique requis) | "open" (permissif)
    public final String gameVersion;   // version du JEU servie (APK), ex. "12.1.0" — FAIT (§4), pas inventé
    public final String serverVersion; // version du LOGICIEL serveur (notre port)
    public final int online;           // joueurs connectés à l'instant
    public final int maxOnline;        // capacité (0 = non annoncée / illimitée)
    public final long openTime;        // date d'ouverture du serveur (ms epoch ; 0 = inconnue)

    public ServerInfo(String name, String mode, String gameVersion, String serverVersion,
                      int online, int maxOnline, long openTime) {
        this.name = name; this.mode = mode; this.gameVersion = gameVersion; this.serverVersion = serverVersion;
        this.online = online; this.maxOnline = maxOnline; this.openTime = openTime;
    }

    public boolean full() { return maxOnline > 0 && online >= maxOnline; }

    /**
     * Chaîne CANONIQUE signée = {@code PROTOCOL␟name␟mode␟gameVersion␟serverVersion␟online␟maxOnline␟openTime␟nonce}
     * (␟ = US 0x1F). Le {@code nonce} (défi frais fourni par le vérifieur) rend chaque signature UNIQUE → anti-rejeu (on
     * ne peut pas resservir une vieille signature). Produit des octets identiques côté serveur (signature) et launcher
     * (vérification).
     */
    public byte[] canonical(String nonce) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(PROTOCOL).append(SEP)
          .append(name).append(SEP)
          .append(mode).append(SEP)
          .append(gameVersion).append(SEP)
          .append(serverVersion).append(SEP)
          .append(online).append(SEP)
          .append(maxOnline).append(SEP)
          .append(openTime).append(SEP)
          .append(nonce);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Sérialise la fiche + preuve en JSON pour la réponse {@code /info}. {@code pubKey}/{@code nonce}/{@code sig} en base64url. */
    public String toJson(String pubKey, long serverId, String nonce, String sig) {
        return "{\"protocol\":" + PROTOCOL
            + ",\"serverId\":" + serverId
            + ",\"name\":\"" + esc(name) + "\""
            + ",\"mode\":\"" + esc(mode) + "\""
            + ",\"gameVersion\":\"" + esc(gameVersion) + "\""
            + ",\"serverVersion\":\"" + esc(serverVersion) + "\""
            + ",\"online\":" + online
            + ",\"maxOnline\":" + maxOnline
            + ",\"full\":" + full()
            + ",\"openTime\":" + openTime
            + ",\"pubKey\":\"" + pubKey + "\""
            + ",\"nonce\":\"" + nonce + "\""
            + ",\"sig\":\"" + sig + "\"}";
    }

    /** Échappe le minimum JSON (le seul champ libre = le nom, déjà validé sans guillemets/backslash côté config). */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(' '); else b.append(c);
            }
        }
        return b.toString();
    }
}
