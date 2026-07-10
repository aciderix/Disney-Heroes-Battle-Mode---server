package dhbackend;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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
        return null;
    }
}
