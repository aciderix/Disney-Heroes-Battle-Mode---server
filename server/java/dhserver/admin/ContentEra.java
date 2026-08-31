package dhserver.admin;

import com.perblue.heroes.game.data.content.ContentHelper;
import com.perblue.heroes.game.data.content.ContentStats.ContentColumn;
import com.perblue.heroes.util.TimeUtil;
import dhserver.ServerContext;
import dhserver.UserStore;

import java.util.List;

/**
 * ADMIN — ÈRE DE CONTENU (release picker), SOURCE UNIQUE de la logique (chantier D, {@code docs/RELEASE_PICKER.md}).
 * Utilisé À LA FOIS par l'{@link AdminService} (à chaud, dans la JVM du serveur) ET par le CLI {@code AdminRelease}
 * (hors ligne) → aucune divergence de formule (§3).
 *
 * <p>Le levier est l'offset d'ère DÉCOUPLÉ ({@link ServerContext#setContentOffsetMillis}) : régler l'ère NE touche NI
 * les sauvegardes NI les timers joueur (contrairement à l'horloge entière). Persisté en méta {@code content_offset_ms},
 * ré-appliqué au boot par {@code LoginServer}, émis dans {@code bootData().contentStatsTimeOffset}. Appliqué à chaud,
 * il prend effet au prochain {@code BootData} (reconnexion), sans redémarrage — c'est l'avantage sur le CLI.
 */
public final class ContentEra {
    private ContentEra() {}

    @SuppressWarnings("unchecked")
    public static List<ContentColumn> columns(int shard) {
        ContentHelper.get().setShardID(shard, new java.util.HashMap<>()); // idempotent (le serveur le fait à chaque bind)
        return (List<ContentColumn>) (List<?>) ContentHelper.getRawStats().getColumns();
    }

    public static String name(ContentColumn c) {
        try { return String.valueOf(c.getContentUpdate()); } catch (Throwable t) { return "?"; }
    }

    public static String fmt(long ms) {
        return new org.joda.time.DateTime(ms, TimeUtil.getServerDateTimeZone()).toString("yyyy-MM-dd");
    }

    /** L'ère courante = colonne à (serverTimeNow + offset de contenu). Timers restent sur serverTimeNow. */
    public static ContentColumn currentEra(int shard) {
        columns(shard);
        long eraTime = TimeUtil.serverTimeNow() + ServerContext.contentOffsetMillis();
        return ContentHelper.getRawStats().getColumn(eraTime);
    }

    /** Résout un sélecteur (nom de release ex. {@code R50}, ou {@code #index}) → colonne, ou {@code null} si introuvable. */
    public static ContentColumn resolve(int shard, String sel) {
        List<ContentColumn> cols = columns(shard);
        if (sel == null || sel.isEmpty()) return null;
        if (sel.startsWith("#")) {
            try { int i = Integer.parseInt(sel.substring(1).trim()); if (i >= 1 && i <= cols.size()) return cols.get(i - 1); }
            catch (NumberFormatException ignore) {}
            return null;
        }
        for (ContentColumn c : cols) if (name(c).equalsIgnoreCase(sel)) return c;
        return null;
    }

    /** Règle l'ère sur {@code target} : offset tel que {@code serverTimeNow + offset == target.startTime}. Persiste + applique. */
    public static void applyRelease(UserStore store, ContentColumn target) throws Exception {
        long off = target.startTime - TimeUtil.serverTimeNow();
        store.setMetaLong("content_offset_ms", off);
        ServerContext.setContentOffsetMillis(off);
    }

    /** Ère = date réelle (offset 0). Persiste + applique. */
    public static void resetRelease(UserStore store) throws Exception {
        store.setMetaLong("content_offset_ms", 0L);
        ServerContext.setContentOffsetMillis(0L);
    }

    // ---- JSON (pour l'AdminService) ----

    /** {@code {"offsetMs":..,"releases":[{index,name,date,maxTeamLevel,current}]}} — liste complète R1…Rn du shard. */
    public static String listJson(int shard) {
        List<ContentColumn> cols = columns(shard);
        long eraTime = TimeUtil.serverTimeNow() + ServerContext.contentOffsetMillis();
        ContentColumn cur = ContentHelper.getRawStats().getColumn(eraTime);
        String curName = (cur == null) ? null : name(cur);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"offsetMs\":").append(ServerContext.contentOffsetMillis()).append(",\"releases\":[");
        for (int i = 0; i < cols.size(); i++) {
            ContentColumn c = cols.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i + 1)
              .append(",\"name\":").append(jsonStr(name(c)))
              .append(",\"date\":").append(jsonStr(fmt(c.startTime)))
              .append(",\"maxTeamLevel\":").append(c.getMaxTeamLevel())
              .append(",\"current\":").append(curName != null && curName.equals(name(c))).append('}');
        }
        return sb.append("]}").toString();
    }

    /** {@code {"offsetMs":..,"timersDate":..,"eraName":..,"eraDate":..,"maxTeamLevel":..}} — ère courante + heure des timers. */
    public static String statusJson(int shard) {
        long real = TimeUtil.serverTimeNow();
        long off = ServerContext.contentOffsetMillis();
        ContentColumn col = currentEra(shard);
        return "{\"offsetMs\":" + off
             + ",\"timersDate\":" + jsonStr(fmt(real))
             + ",\"eraName\":" + (col == null ? "null" : jsonStr(name(col)))
             + ",\"eraDate\":" + jsonStr(fmt(real + off))
             + ",\"maxTeamLevel\":" + (col == null ? 0 : col.getMaxTeamLevel()) + "}";
    }

    static String jsonStr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
