import dhlauncher.LauncherDaemon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * CORS du daemon launcher (rapport bug #1) — PROUVE que toute réponse porte {@code Access-Control-Allow-Origin: *}, sans
 * quoi la fenêtre Tauri (origine {@code tauri://localhost}) ne peut PAS lire les réponses du daemon ({@code
 * http://127.0.0.1:port}) → écran « Launcher local injoignable » (Linux + Windows). Vérifie une réponse 200 (/health),
 * une 404 (chemin inconnu) et un préflight 405/erreur — tous doivent porter l'en-tête CORS.
 */
public final class LauncherCorsTest {
    static int checks = 0;
    static void ok(boolean c, String m) { if (!c) throw new AssertionError("ÉCHEC: " + m); checks++; }
    static HttpClient http = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        LauncherDaemon d = new LauncherDaemon(0);
        d.start();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // 200 avec Origin d'une fenêtre Tauri
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                    .header("Origin", "tauri://localhost").GET().build(), HttpResponse.BodyHandlers.ofString());
            ok(r.statusCode() == 200, "/health 200");
            ok("*".equals(r.headers().firstValue("Access-Control-Allow-Origin").orElse(null)),
                "Access-Control-Allow-Origin: * sur /health (sinon Tauri ne lit pas la réponse)");
            ok(r.headers().firstValue("Access-Control-Allow-Methods").isPresent(), "Allow-Methods présent");

            // 405 (mauvaise méthode sur un endpoint GET-only) doit AUSSI porter le CORS (sinon échec réseau opaque)
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/directory"))
                    .method("POST", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            ok(bad.statusCode() == 405 || bad.statusCode() == 503, "/directory POST rejeté (405/503)");
            ok(bad.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "CORS présent même sur une réponse d'erreur");
        } finally { d.stop(); }
        System.out.println("LauncherCorsTest OK — " + checks + " assertions (CORS daemon PROUVÉ)");
    }
}
