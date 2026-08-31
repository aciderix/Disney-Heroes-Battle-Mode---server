import dhserver.directory.ServerIdentity;
import dhserver.directory.ServerInfo;
import dhserver.directory.ServerRegistration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ANNUAIRE (brique 2) — SONDE DEV (réseau, EXCLUE de regression). Prouve la chaîne RÉELLE contre l'annuaire Supabase :
 * (1) inscription signée → Edge Function {@code register-server} → 200 ; (2) relecture REST publique → la fiche est là ;
 * (3) signature falsifiée → 401 (la fonction refuse). URL + clé anon lus dans l'env (PROJECT_URL, ANON_PUBLIC).
 *
 * Lancement : java ... DirectoryProbe   (nécessite PROJECT_URL + ANON_PUBLIC dans l'env)
 */
public final class DirectoryProbe {
    public static void main(String[] args) throws Exception {
        String url = env("PROJECT_URL");
        String anon = env("ANON_PUBLIC");
        if (url == null || anon == null) { System.out.println("DirectoryProbe : PROJECT_URL/ANON_PUBLIC absents — sonde non exécutable"); return; }

        Path idFile = Files.createTempFile("probeid", ".txt"); Files.delete(idFile);
        ServerIdentity id = ServerIdentity.loadOrCreate(idFile);
        String pub = id.publicKeyB64();
        ServerInfo info = new ServerInfo("Probe " + (System.currentTimeMillis() % 100000), "open", "12.1.0", "0.2.0", 1, 30, System.currentTimeMillis());
        String address = "127.0.0.1:8080";
        String infoUrl = "http://127.0.0.1:8082";

        // (1) inscription signée
        int rc = ServerRegistration.register(url, anon, id, info, address, infoUrl);
        System.out.println("(1) register → HTTP " + rc + (rc == 200 ? " ✅" : " ✗"));

        // (2) relecture REST publique
        HttpClient http = HttpClient.newHttpClient();
        String q = trim(url) + "/rest/v1/servers?select=pub_key,name,mode,online,max_online&pub_key=eq." + pub;
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(q))
                .header("apikey", anon).header("Authorization", "Bearer " + anon).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        boolean found = r.statusCode() == 200 && r.body().contains(info.name);
        System.out.println("(2) read REST → HTTP " + r.statusCode() + (found ? " ✅ fiche présente" : " ✗") + " : " + r.body());

        // (3) signature falsifiée → 401
        int bad = postBadSignature(http, url, anon, pub, info, address, infoUrl);
        System.out.println("(3) signature falsifiée → HTTP " + bad + (bad == 401 ? " ✅ refusée" : " ✗ (attendu 401)"));

        System.out.println("DirectoryProbe : " + ((rc == 200 && found && bad == 401) ? "TOUT VERT ✅" : "ÉCHEC ✗"));
    }

    /** POST une charge dont la signature est corrompue (1 caractère changé) → la fonction doit répondre 401. */
    static int postBadSignature(HttpClient http, String url, String anon, String pub, ServerInfo info, String address, String infoUrl) throws Exception {
        long issuedAt = System.currentTimeMillis();
        // signature bidon (base64url valide mais fausse)
        String badSig = "AAAA" + "BBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRR";
        String body = "{\"pubKey\":\"" + pub + "\",\"name\":\"" + info.name + "\",\"mode\":\"open\""
            + ",\"gameVersion\":\"12.1.0\",\"serverVersion\":\"0.2.0\",\"address\":\"" + address + "\",\"infoUrl\":\"" + infoUrl + "\""
            + ",\"online\":1,\"maxOnline\":30,\"openTime\":" + info.openTime + ",\"issuedAt\":" + issuedAt + ",\"signature\":\"" + badSig + "\"}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(trim(url) + "/functions/v1/register-server"))
                .header("Content-Type", "application/json").header("apikey", anon).header("Authorization", "Bearer " + anon)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    static String env(String k) { String v = System.getenv(k); return (v == null || v.isBlank()) ? null : v.trim(); }
    static String trim(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
