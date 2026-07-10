package com.perblue.heroes.cparticle;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * SHADOW de {@code com.perblue.heroes.cparticle.Native} (le moteur de particules NATIF de PerBlue,
 * dont le {@code <clinit>} d'origine chargeait la lib {@code .so} — absente des splits x86_64).
 *
 * <p>Contrairement à {@code cspine} (réimplémenté sur spine-libgdx), le format de particules
 * {@code .np} de PerBlue n'a pas de runtime Java tout-prêt. Cette coquille fournit une
 * implémentation Java <b>partielle</b> : les wrappers d'origine ({@link NativeParticleEffect},
 * {@link NativeParticlePool}, {@link NativeParticlePoolLoader}, {@link NativeParticleEffectRenderer})
 * tournent INCHANGÉS au-dessus d'elle — ce sont de fins wrappers JNI qui n'atteignent le natif que
 * par cette classe. Les effets se <i>chargent</i> réellement (les octets {@code .np} sont lus par le
 * loader) mais ne sont pas encore <i>simulés</i> : {@code getVertices} renvoie 0 → aucun rendu.
 *
 * <p>Objectif : franchir le boot / login / menu / tutoriel (là où la fidélité de gameplay compte)
 * sans crash, puis remonter le vrai moteur de particules (parser {@code .np} binaire dérivé de
 * {@link com.perblue.common.assets.ParticleConverter}) en second temps. Suivi : #CPARTICLE.
 * Fidélité : PARTIEL (chargement réel, simulation/rendu différés — PAS un « OK » factice :
 * aucune donnée de jeu n'est falsifiée, seul un effet cosmétique n'est pas encore affiché).
 */
public class Native {
    public Native() {}

    // Handle non nul : le wrapper considère l'effet « valide » (hasValidHandle). Constante car
    // toutes les opérations sont no-op — pas de table de handles nécessaire.
    static int Effect_create(byte[] data, int atlasHandle) { return 1; }
    static int Effect_clone(int handle) { return 1; }
    static void Effect_dispose(int handle) {}

    // Aucune géométrie produite (simulation différée) → 0 sommet, rien à dessiner.
    static int Effect_getVertices(int handle, FloatBuffer verts, ShortBuffer indices) { return 0; }
    static int Effect_getVerticesAboveZ(int handle, float z, FloatBuffer verts, ShortBuffer indices) { return 0; }
    static int Effect_getVerticesBelowZ(int handle, float z, FloatBuffer verts, ShortBuffer indices) { return 0; }

    static void Effect_kill(int handle) {}
    static void Effect_reset(int handle) {}
    static void Effect_start(int handle) {}
    static void Effect_stopEmitting(int handle) {}
    static void Effect_setPositionXY(int handle, float x, float y) {}
    static void Effect_setPositionXYZ(int handle, float x, float y, float z) {}
    static void Effect_setRotation(int handle, float rot) {}
    static void Effect_setScale(int handle, float scale) {}

    // « complete » = true : les effets one-shot se terminent proprement et sont rendus au pool
    // (pas de fuite de handles). Les effets en boucle sont relancés par le jeu mais n'affichent rien.
    static boolean Effect_update(int handle, float dt) { return true; }

    static boolean Effect_usesMultiply(int handle) { return false; }
    static boolean Effect_usesZOffsets(int handle) { return false; }

    public static String getLastParticleError() { return null; }
}
