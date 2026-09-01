package com.perblue.dhlauncher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HANDSHAKE d'AUTHENTIFICATION mnémonique côté MOBILE (V3 brique 3a) — défi-réponse Ed25519 contre l'{@code AuthService}
 * du serveur, via le proxy {@code /auth/*} de {@code content_server} (le picker ne connaît QUE l'adresse content/login).
 *
 * <p>Flux (miroir du launcher desktop) : {@code POST /auth/challenge{userID}} → nonce ; on le SIGNE avec la clé dérivée de
 * la phrase ; {@code POST /auth/register{userID,loginRequestID,pubKey,nonce,signature}} → le serveur vérifie que
 * {@code userID == userIdOf(pubKey)} + la signature, ENREGISTRE la clé (idempotent : rejette une AUTRE clé pour ce userID)
 * et marque le compte « récemment authentifié ». Au {@code /login} suivant, {@code content_server} frappe le billet
 * nominatif ({@code /auth/mint}) pour CE userID → le serveur strict lie le socket. {@code register} sert à la fois à créer
 * et à re-connecter (même clé). Pur JDK/Android (HttpURLConnection, Base64 URL-safe — présents dès API 26), aucune dépendance.
 */
public final class MobileAuth {
    private MobileAuth() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    /** Authentifie {@code id} auprès de {@code baseUrl} (ex. {@code http://host:port}). Bloquant (à appeler hors UI). */
    public static Result authenticate(String baseUrl, MobileIdentity.Identity id) {
        String base = baseUrl.replaceAll("/+$", "");
        try {
            // 1) défi
            String chJson = post(base + "/auth/challenge", "userID=" + id.userID);
            String nonceB64 = field(chJson, "nonce");
            if (nonceB64 == null) return new Result(false, "défi refusé (compte/serveur ?)");
            byte[] nonce = Base64.getUrlDecoder().decode(nonceB64);

            // 2) preuve de possession
            byte[] sig = id.sign(nonce);
            String body = "userID=" + id.userID
                + "&loginRequestID=" + UUID.randomUUID()
                + "&pubKey=" + b64url(id.publicKey)
                + "&nonce=" + nonceB64
                + "&signature=" + b64url(sig);

            // 3) enregistrement/liaison (idempotent — crée ou reconnecte la MÊME clé)
            int code = postCode(base + "/auth/register", body);
            if (code == 200) return new Result(true, "compte authentifié");
            if (code == 401) return new Result(false, "clé refusée (ce nom de compte appartient à une autre phrase ?)");
            return new Result(false, "auth HTTP " + code);
        } catch (Exception e) {
            return new Result(false, "serveur d'auth injoignable : " + e.getMessage());
        }
    }

    // ---- HTTP (form-encoded) -----------------------------------------------------------------------------------
    private static String post(String url, String form) throws Exception {
        HttpURLConnection c = open(url, form);
        int code = c.getResponseCode();
        java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String ln; (ln = r.readLine()) != null; ) sb.append(ln);
            r.close();
        }
        return sb.toString();
    }
    private static int postCode(String url, String form) throws Exception {
        HttpURLConnection c = open(url, form);
        int code = c.getResponseCode();
        c.getInputStream().close();
        return code;
    }
    private static HttpURLConnection open(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] b = form.getBytes(StandardCharsets.UTF_8);
        OutputStream os = c.getOutputStream(); os.write(b); os.close();
        return c;
    }

    private static String b64url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
