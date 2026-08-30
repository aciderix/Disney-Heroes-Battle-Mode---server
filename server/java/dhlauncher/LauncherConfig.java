package dhlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Config LOCALE du launcher (chantier C2a-2) — favoris de serveurs persistés sur la machine du joueur, dans le
 * dossier standard par OS ({@code docs/LAUNCHER.md} §1bis). Format interne simple (une ligne pipe-séparée par
 * serveur → aucun parseur JSON requis, robuste) ; l'endpoint {@code /servers} du daemon en produit du JSON pour le
 * front. Pur JDK, aucune dépendance au jeu (le launcher-core reste distribuable sans le game jar).
 */
public final class LauncherConfig {
    private final Path dir;
    private final Path serversFile;

    public LauncherConfig(Path dir) { this.dir = dir; this.serversFile = dir.resolve("servers.txt"); }
    public LauncherConfig() { this(defaultDir()); }

    /** Dossier de config standard par OS (surchargé par {@code -Ddh.launcher.config} pour les tests). */
    public static Path defaultDir() {
        String override = System.getProperty("dh.launcher.config");
        if (override != null && !override.isEmpty()) return Path.of(override);
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return Path.of(appdata != null && !appdata.isEmpty() ? appdata : home, "DisneyHeroesPort");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "DisneyHeroesPort");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg != null && !xdg.isEmpty() ? xdg : home + "/.config", "disney-heroes-port");
    }

    /** Un serveur favori. Ports par défaut = convention du stack (content 8080, jeu 8081, auth 8082). */
    public static final class Server {
        public String id, name, host;
        public int contentPort = 8080, gamePort = 8081, authPort = 8082;
        public String authUrl() { return "http://" + host + ":" + authPort; }
        public String serverProp() { return host + ":" + contentPort; } // pour -Ddh.server (redirige ServerType.LIVE)
        String toJson() {
            return "{\"id\":\"" + id + "\",\"name\":\"" + esc(name) + "\",\"host\":\"" + esc(host)
                    + "\",\"contentPort\":" + contentPort + ",\"gamePort\":" + gamePort + ",\"authPort\":" + authPort + "}";
        }
    }

    /** Charge les favoris (liste vide si le fichier n'existe pas). */
    public synchronized List<Server> load() throws IOException {
        List<Server> out = new ArrayList<>();
        if (!Files.exists(serversFile)) return out;
        for (String line : Files.readAllLines(serversFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 6) continue;
            Server s = new Server();
            s.id = p[0]; s.name = p[1]; s.host = p[2];
            s.contentPort = parse(p[3], 8080); s.gamePort = parse(p[4], 8081); s.authPort = parse(p[5], 8082);
            out.add(s);
        }
        return out;
    }

    private synchronized void save(List<Server> servers) throws IOException {
        Files.createDirectories(dir);
        StringBuilder b = new StringBuilder();
        for (Server s : servers)
            b.append(s.id).append('|').append(s.name).append('|').append(s.host).append('|')
             .append(s.contentPort).append('|').append(s.gamePort).append('|').append(s.authPort).append('\n');
        Files.writeString(serversFile, b.toString(), StandardCharsets.UTF_8);
    }

    /** Ajoute un favori (id attribué), persiste, renvoie l'entrée. name/host validés (pas de {@code | " \\} ni saut de ligne). */
    public synchronized Server add(String name, String host, int contentPort, int gamePort, int authPort) throws IOException {
        if (!safe(name) || !safe(host)) throw new IllegalArgumentException("name/host invalide");
        Server s = new Server();
        s.id = UUID.randomUUID().toString(); s.name = name.trim(); s.host = host.trim();
        s.contentPort = contentPort; s.gamePort = gamePort; s.authPort = authPort;
        List<Server> all = load(); all.add(s); save(all);
        return s;
    }

    /** Supprime un favori par id ; renvoie true si retiré. */
    public synchronized boolean remove(String id) throws IOException {
        List<Server> all = load();
        boolean removed = all.removeIf(s -> s.id.equals(id));
        if (removed) save(all);
        return removed;
    }

    public Server get(String id) throws IOException {
        for (Server s : load()) if (s.id.equals(id)) return s;
        return null;
    }

    /** JSON array des favoris (pour l'endpoint /servers). */
    public String toJsonArray() throws IOException {
        StringBuilder b = new StringBuilder("[");
        List<Server> all = load();
        for (int i = 0; i < all.size(); i++) { if (i > 0) b.append(','); b.append(all.get(i).toJson()); }
        return b.append(']').toString();
    }

    private static boolean safe(String s) {
        return s != null && !s.isBlank() && s.indexOf('|') < 0 && s.indexOf('"') < 0 && s.indexOf('\\') < 0
                && s.indexOf('\n') < 0 && s.indexOf('\r') < 0;
    }
    private static String esc(String s) { return s; } // name/host déjà validés sans " ni \
    private static int parse(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
}
