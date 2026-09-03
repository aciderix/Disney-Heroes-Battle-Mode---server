package dhbackend.audiocompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Réimplémentation FIDÈLE de {@code com.badlogic.gdx.utils.FloatArray.insert(int,float)} (stock
 * libGDX 1.9.7), méthode ABSENTE du cœur libGDX RÉDUIT du jeu (PerBlue) — cf. PatchGdxAudio.java.
 * SEULEMENT le cas {@code insert(0, value)} (prépend), le seul exercé par le backend audio
 * (OpenALMusic.renderedSecondsQueue — vérifié par décompilation, unique appel, index toujours
 * littéral 0). Implémentée en RÉFLEXION PURE (pas de dépendance de compilation à FloatArray, qui
 * n'existe qu'à l'exécution via le classpath du jeu) sur les champs PUBLICS {@code size}/{@code
 * items} et la méthode PUBLIQUE {@code ensureCapacity(int)} — TOUS présents et identiques côté
 * PerBlue (vérifiés par décompilation). Sémantique EXACTE du stock : décale les éléments existants
 * d'un cran vers la droite, place la nouvelle valeur en tête, incrémente size. Appelée rarement
 * (une fois par cycle de remplissage de tampon audio, pas par frame) → coût réflexion négligeable.
 */
public final class FloatArrayCompat {
    public static void insert0(Object arr, float value) {
        try {
            Class<?> c = arr.getClass();
            Field sizeField = c.getField("size");
            int n = sizeField.getInt(arr);
            Method ensureCapacity = c.getMethod("ensureCapacity", int.class);
            float[] items = (float[]) ensureCapacity.invoke(arr, 1);
            for (int i = n; i > 0; i--) items[i] = items[i - 1];
            items[0] = value;
            sizeField.setInt(arr, n + 1);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("FloatArrayCompat.insert0", ex);
        }
    }
}
