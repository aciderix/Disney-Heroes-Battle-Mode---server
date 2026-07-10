package dhbackend;

import com.badlogic.gdx.Net;
import com.badlogic.gdx.net.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Backend Net desktop — HTTP RÉEL via {@code java.net.HttpURLConnection} (porté de DragonSoul
 * `DsNet`). C'est le canal utilisé par le jeu pour le login ({@code Gdx.net.sendHttpRequest} →
 * {@code POST /login}). Exécuté sur un thread de fond (comme libGDX). Suit les redirections.
 *
 * NB : le **téléchargement du contenu** (assets) n'utilise PAS ce backend — le
 * {@code FileDownloader} du jeu ouvre lui-même {@code HttpURLConnection} (java.net) et suit les
 * redirections → il télécharge automatiquement depuis notre serveur de contenu → archive.org.
 * Le socket de jeu TCP passe, lui, par {@code java.net.Socket} (hors Net). Fidélité : RÉEL.
 */
public final class DhNet implements Net {

    @Override
    @SuppressWarnings("unchecked")
    public void sendHttpRequest(final Net.HttpRequest request, final Net.HttpResponseListener listener) {
        final String url = request.getUrl();
        final String method = request.getMethod() != null ? request.getMethod() : "GET";
        final Map<String, String> headers = request.getHeaders();
        final String content = request.getContent();
        final int timeout = request.getTimeOut();

        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod(method);
                conn.setInstanceFollowRedirects(request.getFollowRedirects());
                if (timeout > 0) { conn.setConnectTimeout(timeout); conn.setReadTimeout(timeout); }
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet())
                    conn.setRequestProperty(h.getKey(), h.getValue());

                boolean doOutput = content != null && !content.isEmpty()
                        && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method));
                if (doOutput) {
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(content.getBytes("UTF-8"));
                    }
                }

                final int code = conn.getResponseCode();
                InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
                final String body = readAll(is);
                final HttpStatus status = new HttpStatus(code);

                listener.handleHttpResponse(new Net.HttpResponse() {
                    @Override public String getResultAsString() { return body; }
                    @Override public HttpStatus getStatus() { return status; }
                });
            } catch (Throwable e) {
                listener.failed(e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "DhNet-HTTP");
        t.setDaemon(true);
        t.start();
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }
}
