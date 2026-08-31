import dhserver.LoginServer;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;
import dhserver.admin.AdminService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

/**
 * ADMIN inc.6a — l'{@code AdminService} (dans la JVM du serveur) sert le monitoring LECTURE SEULE, protégé par un
 * JETON opérateur. Vérifie : garde de jeton (401 sans / mauvais jeton, 200 avec, via {@code Authorization: Bearer}
 * ET {@code X-Admin-Token}), et le contenu de {@code /admin/monitor} (état réel : 0 en ligne, 0 connexion, uptime,
 * mode). Référence {@code LoginServer} → auto-détecté ISOLÉ par {@code regression.sh}.
 */
public final class AdminMonitorTest {
    static int passed = 0, failed = 0;
    static void ok(boolean c, String m) { if (c) { passed++; } else { failed++; System.out.println("  ✗ " + m); } }

    public static void main(String[] args) throws Exception {
        ServerContext.init();
        String db = Files.createTempFile("admintest", ".db").toString();
        System.setProperty("dh.db", db);   // le journal d'audit s'écrit à côté de la DB (dossier temp, pas le projet)
        UserStore store = new UserStore(db);
        ServerUser user = store.loadOrCreate(1L, 1);
        LoginServer server = new LoginServer(0, user, store);  // pas de socket : on teste juste l'AdminService

        String tok = "secret-operator-token";
        AdminService admin = new AdminService("127.0.0.1", 0, tok, server, store);
        admin.start();
        String base = "http://127.0.0.1:" + admin.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            // garde de jeton
            ok(get(http, base + "/admin/monitor", null, null).statusCode() == 401, "monitor sans jeton → 401");
            ok(get(http, base + "/admin/monitor", "X-Admin-Token", "mauvais").statusCode() == 401, "monitor mauvais jeton → 401");

            // jeton correct via X-Admin-Token
            HttpResponse<String> r = get(http, base + "/admin/monitor", "X-Admin-Token", tok);
            ok(r.statusCode() == 200, "monitor bon jeton (X-Admin-Token) → 200");
            ok(r.body().contains("\"onlineCount\":0"), "monitor : 0 joueur en ligne");
            ok(r.body().contains("\"connectionsAccepted\":0"), "monitor : 0 connexion acceptée");
            ok(r.body().contains("\"online\":[]"), "monitor : liste online vide");
            ok(r.body().contains("\"uptimeMs\":"), "monitor : uptime présent");
            ok(r.body().contains("\"strict\":false"), "monitor : mode permissif par défaut");

            // jeton correct via Authorization: Bearer
            ok(get(http, base + "/admin/monitor", "Authorization", "Bearer " + tok).statusCode() == 200,
                    "monitor bon jeton (Authorization: Bearer) → 200");

            // ping (vérif de jeton) : 401 sans, ok avec
            ok(get(http, base + "/admin/ping", null, null).statusCode() == 401, "ping sans jeton → 401");
            HttpResponse<String> p = get(http, base + "/admin/ping", "X-Admin-Token", tok);
            ok(p.statusCode() == 200 && p.body().contains("\"ok\":true"), "ping bon jeton → {ok:true}");

            // jetons aléatoires distincts et non vides
            String a = AdminService.randomToken(), b = AdminService.randomToken();
            ok(a != null && !a.isEmpty() && !a.equals(b), "randomToken() non vide et distinct");

            // ère de contenu : la liste des releases est servie (gardée par le jeton) et non vide
            ok(get(http, base + "/admin/releases", null, null).statusCode() == 401, "releases sans jeton → 401");
            HttpResponse<String> rel = get(http, base + "/admin/releases", "X-Admin-Token", tok);
            ok(rel.statusCode() == 200 && rel.body().contains("\"releases\":["), "releases : liste servie");
            ok(rel.body().contains("\"maxTeamLevel\":"), "releases : Max TL présent par release");

            // GESTION DES JOUEURS (compte de test userID 1)
            ok(post(http, base + "/admin/player/lookup", null, "userID=1").statusCode() == 401, "player sans jeton → 401");
            HttpResponse<String> look = post(http, base + "/admin/player/lookup", tok, "userID=1");
            ok(look.statusCode() == 200 && look.body().contains("\"userID\":1"), "player/lookup → résumé du compte");
            ok(look.body().contains("\"gold\":") && look.body().contains("\"teamLevel\":"), "player/lookup : or + TL présents");

            // giveResource GOLD +500 (compte neuf = 0 → 500)
            HttpResponse<String> gr = post(http, base + "/admin/player/giveResource", tok, "userID=1&type=GOLD&amount=500");
            ok(gr.statusCode() == 200 && gr.body().contains("\"gold\":500"), "giveResource GOLD +500 → or=500");
            ok(post(http, base + "/admin/player/giveResource", tok, "userID=1&type=NOPE&amount=1").statusCode() == 400, "type invalide → 400");

            // setTeamLevel 50
            HttpResponse<String> tl = post(http, base + "/admin/player/setTeamLevel", tok, "userID=1&level=50");
            ok(tl.statusCode() == 200 && tl.body().contains("\"teamLevel\":50"), "setTeamLevel 50 → TL=50");

            // grantHero (défaut) → héros +1
            int before = jsonInt(look.body(), "heroCount");
            HttpResponse<String> gh = post(http, base + "/admin/player/grantHero", tok, "userID=1&hero=STITCH");
            ok(gh.statusCode() == 200 && jsonInt(gh.body(), "heroCount") >= before + 1, "grantHero STITCH → +1 héros");

            // journal d'audit : les mutations sont tracées
            HttpResponse<String> aud = get(http, base + "/admin/audit", "X-Admin-Token", tok);
            ok(aud.statusCode() == 200 && aud.body().contains("giveResource") && aud.body().contains("setTeamLevel"),
                    "audit : giveResource + setTeamLevel tracés");
            ok(get(http, base + "/admin/audit", null, null).statusCode() == 401, "audit sans jeton → 401");

            // EVENTS live-ops : enums réelles + cycle liste/ajout/validation/retrait
            HttpResponse<String> enums = get(http, base + "/admin/enums", "X-Admin-Token", tok);
            ok(enums.statusCode() == 200 && enums.body().contains("\"GameMode\":[") && enums.body().contains("\"kinds\":["),
                    "enums : GameMode + kinds servis (réels)");
            ok(get(http, base + "/admin/events", "X-Admin-Token", tok).body().contains("\"count\":0"), "events : 0 au départ");
            String spec = dhserver.ServerEvents.specJson("MODES_OPEN",
                    java.util.List.of(com.perblue.heroes.network.messages.GameMode.PORT_DOCKS), 0,
                    dhserver.ServerEvents.defaultStart(), dhserver.ServerEvents.defaultEnd());
            HttpResponse<String> add = post(http, base + "/admin/events", tok,
                    "spec=" + java.net.URLEncoder.encode(spec, java.nio.charset.StandardCharsets.UTF_8));
            ok(add.statusCode() == 200 && add.body().contains("\"count\":1"), "events : ajout spec valide → count 1");
            ok(post(http, base + "/admin/events", tok,
                    "spec=" + java.net.URLEncoder.encode("{\"kind\":\"BOGUS\"}", java.nio.charset.StandardCharsets.UTF_8))
                    .statusCode() == 400, "events : spec invalide → 400 (non persistée)");
            HttpResponse<String> rm = post(http, base + "/admin/events/remove", tok, "index=0");
            ok(rm.statusCode() == 200 && rm.body().contains("\"count\":0"), "events : retrait index 0 → count 0");

            // MODÉRATION : bans (gate login) / mutes (anti-chat) / kick (connexion vive)
            ok(get(http, base + "/admin/moderation", "X-Admin-Token", tok).body().contains("\"bans\":[]"), "modération : vide au départ");
            ok(post(http, base + "/admin/moderation/ban", null, "userID=42").statusCode() == 401, "ban sans jeton → 401");
            HttpResponse<String> ban = post(http, base + "/admin/moderation/ban", tok, "userID=42");
            ok(ban.statusCode() == 200 && ban.body().contains("[42]"), "ban 42 → présent dans bans");
            ok(dhserver.admin.Moderation.isBanned(42L), "Moderation.isBanned(42) = true (consulté par le gate login)");
            HttpResponse<String> mute = post(http, base + "/admin/moderation/mute", tok, "userID=7");
            ok(mute.body().contains("\"mutes\":[7]") && dhserver.admin.Moderation.isMuted(7L), "mute 7 → anti-chat actif");
            ok(post(http, base + "/admin/moderation/kick", tok, "userID=42").body().contains("\"kicked\":false"), "kick 42 (hors ligne) → kicked:false");
            ok(post(http, base + "/admin/moderation/unban", tok, "userID=42").body().contains("\"bans\":[]"), "unban 42 → bans vide");
        } finally {
            admin.stop();
        }

        System.out.println("AdminMonitorTest : " + passed + " ok, " + failed + " échec(s)");
        if (failed > 0) System.exit(1);
    }

    static HttpResponse<String> get(HttpClient h, String url, String header, String value) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (header != null) b.header(header, value);
        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient h, String url, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("X-Admin-Token", token);
        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Extrait un entier JSON {@code "key":<int>} (test-only, tolérant). */
    static int jsonInt(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
