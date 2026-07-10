package dhbackend;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ponts de services plateforme du jeu (normalement fournis par l'AndroidLauncher) :
 * {@code INative}, {@code IAnalytics}, {@code ISupport}, {@code SocialNetworkManager},
 * {@code ITapjoyOfferwall}, {@code IPlaybackRewards}. Sur desktop, ces services ne
 * s'appliquent pas → implémentations **NO-OP** (proxy dynamique renvoyant des valeurs par
 * défaut ; pour un retour enum, la 1ʳᵉ constante pour éviter les NPE).
 *
 * ⚠️ DEFERRED (BACKEND_STATUS.md #BRIDGES) : NO-OP assumé. Certaines méthodes (ex.
 * {@code INative.getOrientation()}) pourraient nécessiter une vraie valeur → à affiner au
 * cas par cas si le jeu en dépend au boot. Ce n'est pas une rustine sur une vérification :
 * ce sont des services absents sur desktop, explicitement tracés.
 */
public final class DhBridges {
    private DhBridges() {}

    /** Construit un NO-OP pour une interface de service plateforme du jeu, par nom. */
    public static Object noop(String ifaceName) {
        try {
            Class<?> iface = Class.forName(ifaceName);
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, DhBridges::defaultReturn);
        } catch (ClassNotFoundException e) {
            System.out.println("[bridges] interface absente: " + ifaceName + " (" + e + ")");
            return null;
        }
    }

    private static Object defaultReturn(Object proxy, Method method, Object[] args) {
        Class<?> r = method.getReturnType();
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == byte.class) return (byte) 0;
        if (r == short.class) return (short) 0;
        if (r == char.class) return (char) 0;
        if (r.isEnum()) { Object[] cs = r.getEnumConstants(); return cs.length > 0 ? cs[0] : null; }
        if (r == String.class) return "";
        // Collections : instances vides (jamais null → pas de NPE côté appelant qui itère).
        if (r == Set.class) return new HashSet<>();
        if (r == List.class || r == Collection.class || r == Iterable.class) return new ArrayList<>();
        if (r == Map.class) return new HashMap<>();
        // Autre interface de service (ex. INative.createPurchasingInterface() -> IPurchasing) :
        // renvoyer un NO-OP imbriqué plutôt que null (le jeu appelle directement dessus au boot).
        if (r.isInterface()) {
            return Proxy.newProxyInstance(r.getClassLoader(), new Class<?>[]{r}, DhBridges::defaultReturn);
        }
        return null;
    }
}
