package dhbackend.jnispine;

import java.util.concurrent.ConcurrentHashMap;

/**
 * PONT D'ATLAS entre les deux moteurs d'origine, pour le backend {@code -Ddh.spinebackend=jni}.
 *
 * <p>Fait établi (log en jeu) : un ATLAS est une ressource PARTAGÉE entre le SPINE (squelettes) et les PARTICULES.
 * En mode {@code jni}, le spine tourne sur {@link HostSpine} (spine-c x86, table de handles propre) tandis que les
 * particules restent sur le VRAI moteur d'origine émulé (unidbg, {@code libspine-native.so} ARM, sa propre table).
 * Le jeu crée l'atlas via {@code cspine.Native.Atlas_create} (→ HostSpine) et passe le MÊME handle à
 * {@code cparticle.Native.Effect_create} (→ unidbg). Sans pont, unidbg ne connaît pas ce handle →
 * « Bad handle type: Wanted ATLAS but is actually NONE » (émis par la lib ARM).
 *
 * <p>Solution SANS réécriture (glue plateforme, §1) : en mode {@code jni}, l'atlas est créé dans les DEUX moteurs
 * (HostSpine pour le spine, unidbg pour les particules) ; ce pont mémorise la correspondance handle HostSpine →
 * handle unidbg. Le shadow {@code cparticle.Native} traduit le handle d'atlas AVANT d'appeler unidbg. Les deux
 * exécutent du VRAI code PerBlue (spine-c officiel + moteur ARM d'origine) — rien n'est réimplémenté.
 *
 * <p>Hors mode {@code jni} le pont est vide → {@link #toUnidbg} renvoie le handle inchangé (aucun effet).
 */
public final class AtlasBridge {
    private AtlasBridge() {}

    /** handle atlas HostSpine (rendu au jeu) → handle atlas unidbg (pour les particules). */
    private static final ConcurrentHashMap<Integer, Integer> HOST2UNIDBG = new ConcurrentHashMap<>();

    /** Enregistre la correspondance créée par le dual-create d'atlas (mode jni). */
    public static void map(int hostHandle, int unidbgHandle) { HOST2UNIDBG.put(hostHandle, unidbgHandle); }

    /** Oublie la correspondance (à la libération de l'atlas). */
    public static void unmap(int hostHandle) { HOST2UNIDBG.remove(hostHandle); }

    /** Traduit un handle d'atlas (HostSpine) vers son équivalent unidbg s'il est ponté ; sinon renvoie tel quel
     *  (mode unidbg pur : le handle EST déjà celui d'unidbg). Sûr et transparent dans les deux modes. */
    public static int toUnidbg(int atlasHandle) {
        Integer u = HOST2UNIDBG.get(atlasHandle);
        return u == null ? atlasHandle : u.intValue();
    }
}
