package dhserver.admin;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import dhserver.UserStore;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADMIN — MODÉRATION (chantier D E, {@code docs/LAUNCHER_UI.md} §4.6 E). N'EXISTAIT PAS dans le jeu ({@code KickFromGuild}
 * = expulsion de guilde, pas modération opérateur) → CONSTRUIT ici comme sous-système serveur minimal, faithful :
 * <ul>
 *   <li><b>BAN</b> : liste de userID rejetés AU LOGIN (à côté de l'auth stricte, dans {@code LoginServer}) — aucun BootData.</li>
 *   <li><b>MUTE</b> : drapeau anti-chat — un {@code SendChat} d'un userID muté est ignoré (ni archivé ni diffusé).</li>
 *   <li><b>KICK</b> : ferme la connexion vive via {@code LoginServer.online} (pas de persistance ; le joueur peut se reconnecter).</li>
 * </ul>
 * État PERSISTÉ dans {@code shard_state} (clé {@code moderation}) et chargé au boot par {@code LoginServer} dans des sets
 * statiques que {@code LoginServer} consulte (même JVM). Kick n'est pas persistant (action ponctuelle).
 */
public final class Moderation {
    private Moderation() {}

    private static final Set<Long> BANS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> MUTES = ConcurrentHashMap.newKeySet();

    /** Consulté par {@code LoginServer} au login (rejet) et à {@code SendChat} (anti-chat). */
    public static boolean isBanned(long uid) { return BANS.contains(uid); }
    public static boolean isMuted(long uid) { return MUTES.contains(uid); }

    /** Charge bans/mutes depuis {@code shard_state/moderation} — appelé au BOOT par {@code LoginServer}. */
    public static void loadFrom(UserStore store, int shard) {
        BANS.clear(); MUTES.clear();
        try {
            byte[] blob = store.loadShardState(shard, "moderation");
            if (blob == null || blob.length == 0) return;
            JsonValue root = new JsonReader().parse(new String(blob, StandardCharsets.UTF_8));
            fill(root.get("bans"), BANS);
            fill(root.get("mutes"), MUTES);
        } catch (Exception ignore) { /* défaut : aucune modération */ }
    }

    private static void fill(JsonValue arr, Set<Long> set) {
        if (arr != null) for (JsonValue v = arr.child; v != null; v = v.next) set.add(v.asLong());
    }

    private static void persist(UserStore store, int shard) throws Exception {
        store.saveShardState(shard, "moderation", listJson().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code {"bans":[…],"mutes":[…]}} (triés, déterministe). */
    public static String listJson() {
        return "{\"bans\":" + arr(new TreeSet<>(BANS)) + ",\"mutes\":" + arr(new TreeSet<>(MUTES)) + "}";
    }

    public static String addBan(UserStore store, int shard, long uid) throws Exception { BANS.add(uid); persist(store, shard); return listJson(); }
    public static String removeBan(UserStore store, int shard, long uid) throws Exception { BANS.remove(uid); persist(store, shard); return listJson(); }
    public static String addMute(UserStore store, int shard, long uid) throws Exception { MUTES.add(uid); persist(store, shard); return listJson(); }
    public static String removeMute(UserStore store, int shard, long uid) throws Exception { MUTES.remove(uid); persist(store, shard); return listJson(); }

    private static String arr(Set<Long> xs) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Long x : xs) { if (!first) sb.append(','); first = false; sb.append(x); }
        return sb.append(']').toString();
    }
}
