package dhserver.admin;

import com.badlogic.gdx.utils.JsonValue;
import dhserver.ServerEvents;
import dhserver.UserStore;

import java.util.ArrayList;
import java.util.List;

/**
 * ADMIN — ÉVÉNEMENTS live-ops (chantier D, {@code docs/LAUNCHER_UI.md} §4.6 A). RÉUTILISE intégralement le format de
 * config du jeu-glue existant (specs JSON {@code {kind,…}} persistées dans {@code shard_state/operator_events},
 * reconstruites par nos builders {@code ServerEvents.*}) — même mécanisme que le CLI {@code AdminEvents} (§3, zéro
 * réécriture). Dans la JVM serveur → applique à CHAUD ({@code setOperatorEvents}), visible au prochain
 * {@code REFRESH_SPECIAL_EVENTS} du client.
 *
 * <p>Contrat backend : {@code GET /admin/events} (liste), {@code POST /admin/events} (ajoute une spec, VALIDÉE),
 * {@code POST /admin/events/remove} (par index), {@code POST /admin/events/clear} (tout), {@code GET /admin/enums}
 * (listes d'enums RÉELLES pour l'éditeur — jamais codées en dur côté front). La spec ajoutée est CONSTRUITE côté front
 * au format {@code {kind,…}} (le CLI la produit via {@code ServerEvents.specJson*}) et VALIDÉE ici en la reconstruisant
 * (une spec invalide est rejetée → 400, jamais persistée : pas de faux OK §2).
 */
public final class EventsAdmin {
    private EventsAdmin() {}

    /** Kinds supportés (composants live-ops livrés). Ce N'EST pas un enum du jeu → liste explicite. */
    static final String[] KINDS = {
        "MODES_OPEN", "DROP_BONUS", "CHEST_DISCOUNT", "INCREASED_CHANCES", "TRADER_DISCOUNT",
        "TRADER_REFRESH_DISCOUNT", "MISC_BONUS", "MISC_DISCOUNT", "FLAG_USER_ON_LOGIN", "TEAM_LEVEL",
        "TRIAL_FRANCHISE", "EXTRA_CHEST", "CONTEST"
    };

    static List<String> loadSpecs(UserStore store, int shard) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonValue sp : ServerEvents.configSpecs(store.loadShardState(shard, "operator_events"))) out.add(sp.toString());
        return out;
    }

    /** Persiste la config (shard_state) ET l'applique à CHAUD (holder statique lu par le prochain SpecialEventsRaw). */
    static void persistAndApply(UserStore store, int shard, List<String> specs) throws Exception {
        byte[] blob = ServerEvents.writeConfig(specs);
        store.saveShardState(shard, "operator_events", blob);
        ServerEvents.setOperatorEvents(ServerEvents.eventsFromConfig(blob));
    }

    /** {@code {"count":n,"events":[<spec>,…]}} — les specs opérateur persistées (JSON brut). */
    public static String listJson(UserStore store, int shard) throws Exception {
        List<String> specs = loadSpecs(store, shard);
        StringBuilder sb = new StringBuilder("{\"count\":").append(specs.size()).append(",\"events\":[");
        for (int i = 0; i < specs.size(); i++) { if (i > 0) sb.append(','); sb.append(specs.get(i)); }
        return sb.append("]}").toString();
    }

    /** Une spec est-elle VALIDE ? kind reconnu (whitelist) ET reconstruit exactement 1 event via nos builders.
     *  (Le check kind est nécessaire : {@code eventFromSpec} retombe silencieusement sur MODES_OPEN pour un kind inconnu.) */
    static boolean validSpec(String specJson) {
        try {
            String kind = new com.badlogic.gdx.utils.JsonReader().parse(specJson).getString("kind", "");
            boolean known = false;
            for (String k : KINDS) if (k.equals(kind)) { known = true; break; }
            if (!known) return false;
            return ServerEvents.eventsFromConfig(ServerEvents.writeConfig(List.of(specJson))).size() == 1;
        } catch (Throwable t) { return false; }
    }

    /** Ajoute une spec (validée) → persiste + applique + renvoie la liste. {@code null} = spec invalide (→ 400 appelant). */
    public static String addSpec(UserStore store, int shard, String specJson) throws Exception {
        if (specJson == null || specJson.trim().isEmpty() || !validSpec(specJson)) return null;
        List<String> specs = loadSpecs(store, shard);
        specs.add(specJson);
        persistAndApply(store, shard, specs);
        return listJson(store, shard);
    }

    /** Retire la spec à {@code index} → persiste + applique + renvoie la liste. {@code null} = index hors bornes (→ 404). */
    public static String removeAt(UserStore store, int shard, int index) throws Exception {
        List<String> specs = loadSpecs(store, shard);
        if (index < 0 || index >= specs.size()) return null;
        specs.remove(index);
        persistAndApply(store, shard, specs);
        return listJson(store, shard);
    }

    /** Retire TOUS les overrides → rotation par défaut du jeu. */
    public static String clear(UserStore store, int shard) throws Exception {
        persistAndApply(store, shard, new ArrayList<>());
        return listJson(store, shard);
    }

    /** Listes d'enums RÉELLES pour l'éditeur (dropdowns) — reflétées depuis les enums du jeu (§4 : jamais codées en dur). */
    public static String enumsJson() {
        StringBuilder sb = new StringBuilder("{\"kinds\":").append(arr(KINDS));
        sb.append(",\"GameMode\":").append(enumArr(com.perblue.heroes.network.messages.GameMode.class));
        sb.append(",\"ChestType\":").append(enumArr(com.perblue.heroes.network.messages.ChestType.class));
        sb.append(",\"MerchantType\":").append(enumArr(com.perblue.heroes.network.messages.MerchantType.class));
        sb.append(",\"MultiplierType\":").append(enumArr(com.perblue.heroes.game.specialevent.MultiplierType.class));
        sb.append(",\"UserFlag\":").append(enumArr(com.perblue.heroes.game.objects.UserFlag.class));
        sb.append(",\"ResourceType\":").append(enumArr(com.perblue.heroes.network.messages.ResourceType.class));
        sb.append(",\"GenericTrialType\":").append(enumArr(com.perblue.heroes.network.messages.GenericTrialType.class));
        return sb.append("}").toString();
    }

    private static String enumArr(Class<? extends Enum<?>> c) {
        Object[] vals = c.getEnumConstants();
        String[] names = new String[vals == null ? 0 : vals.length];
        for (int i = 0; i < names.length; i++) names[i] = ((Enum<?>) vals[i]).name();
        return arr(names);
    }

    private static String arr(String[] xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.length; i++) { if (i > 0) sb.append(','); sb.append(ContentEra.jsonStr(xs[i])); }
        return sb.append("]").toString();
    }
}
