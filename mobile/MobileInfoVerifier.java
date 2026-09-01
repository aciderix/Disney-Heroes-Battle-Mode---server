package com.perblue.dhlauncher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VÉRIFIEUR de fiche serveur côté MOBILE (V3 brique 4) — MIROIR de {@code dhlauncher.ServerInfoVerifier}, avec l'{@link
 * Ed25519} pur-Java. Interroge {@code <infoUrl>/info?nonce=<défi>} → RECOMPOSE la chaîne canonique EXACTE
 * ({@code PROTOCOL␟name␟mode␟gameVersion␟serverVersion␟online␟maxOnline␟openTime␟nonce}, ␟ = US 0x1F, identique à
 * {@code dhserver.directory.ServerInfo#canonical}) → VÉRIFIE la signature Ed25519 avec la clé publique ANNONCÉE. Si OK :
 * la fiche est authentique + le serveur est VIVANT et détient la clé privée (nonce frais = anti-rejeu). Mesure aussi la
 * latence (ping) du round-trip. Pur JDK/Android (HttpURLConnection, Base64 URL-safe — dès API 26).
 */
public final class MobileInfoVerifier {
    private MobileInfoVerifier() {}

    public static final int PROTOCOL = 1;
    private static final char SEP = '\u001F';
    private static final SecureRandom RND = new SecureRandom();

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final long serverId, openTime, pingMs;
        public final String name, mode, gameVersion, serverVersion;
        public final int online, maxOnline;
        Result(boolean ok, String message, long serverId, String name, String mode, String gv, String sv,
               int online, int maxOnline, long openTime, long pingMs) {
            this.ok = ok; this.message = message; this.serverId = serverId; this.name = name; this.mode = mode;
            this.gameVersion = gv; this.serverVersion = sv; this.online = online; this.maxOnline = maxOnline;
            this.openTime = openTime; this.pingMs = pingMs;
        }
        static Result fail(String msg, long ping) { return new Result(false, msg, 0, null, null, null, null, 0, 0, 0, ping); }
    }

    /** Récupère + VÉRIFIE la fiche de {@code infoUrl} (base du /info). Bloquant (à appeler hors UI). */
    public static Result verify(String infoUrl) {
        byte[] n = new byte[18]; RND.nextBytes(n);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        String base = infoUrl.replaceAll("/+$", "");
        long t0 = System.currentTimeMillis();
        long ping = -1;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/info?nonce=" + nonce).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            int code = c.getResponseCode();
            ping = System.currentTimeMillis() - t0;
            if (code != 200) return Result.fail("/info HTTP " + code, ping);
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            for (String ln; (ln = r.readLine()) != null; ) sb.append(ln);
            r.close();
            String b = sb.toString();

            if (num(b, "protocol") != PROTOCOL) return Result.fail("protocole de fiche inconnu", ping);
            if (!nonce.equals(str(b, "nonce"))) return Result.fail("nonce non renvoyé (rejeu ?)", ping);
            String name = str(b, "name"), mode = str(b, "mode"), gv = str(b, "gameVersion"), sv = str(b, "serverVersion");
            int online = (int) num(b, "online"), maxOnline = (int) num(b, "maxOnline");
            long openTime = num(b, "openTime"), serverId = num(b, "serverId");
            String pub = str(b, "pubKey"), sig = str(b, "sig");

            byte[] pubBytes = Base64.getUrlDecoder().decode(pub);
            byte[] raw = pubBytes.length == 44 ? java.util.Arrays.copyOfRange(pubBytes, 12, 44) : pubBytes; // strip SPKI
            byte[] sigBytes = Base64.getUrlDecoder().decode(sig);
            byte[] canon = canonical(name, mode, gv, sv, online, maxOnline, openTime, nonce);
            if (!Ed25519.verify(raw, canon, sigBytes)) return Result.fail("signature INVALIDE (usurpation/altération)", ping);
            if (MobileIdentity.userIdOf(pubBytes) != serverId) return Result.fail("serverId incohérent avec la clé", ping);

            return new Result(true, "vérifié", serverId, name, mode, gv, sv, online, maxOnline, openTime, ping);
        } catch (Exception e) {
            return Result.fail("injoignable : " + e.getMessage(), ping);
        }
    }

    /** Chaîne canonique — DOIT être identique octet-pour-octet à {@code ServerInfo.canonical}. */
    private static byte[] canonical(String name, String mode, String gv, String sv,
                                    int online, int maxOnline, long openTime, String nonce) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(PROTOCOL).append(SEP).append(name).append(SEP).append(mode).append(SEP)
          .append(gv).append(SEP).append(sv).append(SEP).append(online).append(SEP)
          .append(maxOnline).append(SEP).append(openTime).append(SEP).append(nonce);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

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
    private static String junesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { b.append(c); continue; }
            char nx = s.charAt(++i);
            switch (nx) {
                case '"': b.append('"'); break; case '\\': b.append('\\'); break; case '/': b.append('/'); break;
                case 'n': b.append('\n'); break; case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                default: b.append(nx);
            }
        }
        return b.toString();
    }
}
