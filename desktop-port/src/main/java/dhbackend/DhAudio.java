package dhbackend;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALAudio;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALSound;
import com.badlogic.gdx.files.FileHandle;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Backend Audio desktop — <b>RÉEL</b> (OpenAL via le backend audio de libGDX 1.9.7, la version
 * même du jeu). PRINCIPLES §3/§4 : on <b>exécute le code d'origine</b> de libGDX (décodage OGG/WAV/MP3
 * + lecture OpenAL des classes {@code com.badlogic.gdx.backends.lwjgl3.audio.*}) au lieu d'en réécrire
 * une ligne. Ce backend est empaqueté à part (jar régénérable {@code libs/gdx-lwjgl3-audio.jar}, cf.
 * {@code run-desktop.sh}) ; le paquet {@code audio/**} est auto-contenu (ne référence que le core gdx
 * + {@code org.lwjgl.openal}).
 *
 * <p>PerBlue a <b>réduit</b> les interfaces gdx {@code Audio}/{@code Sound}/{@code Music}. Les ensembles
 * PerBlue sont des <b>sous-ensembles</b> des interfaces stock → {@code OpenALSound}/{@code OpenALMusic}
 * (compilés contre le stock) satisfont les interfaces PerBlue telles quelles. Seule {@code Audio} diffère
 * assez (méthodes {@code getStereoSoundsSupported}/{@code setStereoSoundsSupported}/{@code stopAllSounds},
 * pas de {@code newAudioDevice}) pour ne pas être implémentable directement par {@code OpenALAudio} →
 * {@code DhAudio} reste l'implémentation de l'interface du jeu et <b>délègue</b> à un {@code OpenALAudio}.
 *
 * <p><b>Repli muet SANS planter</b> : si aucun périphérique audio n'est disponible (serveur/CI headless,
 * poste sans carte son), le ctor OpenAL échoue ; on retombe alors sur des objets no-op (comme avant) et
 * le jeu tourne sans son. Ce n'est pas une rustine sur une vérification (§2) : l'audio n'est pas requis
 * pour le rendu/boot, et l'indisponibilité est tracée dans les logs.
 */
public final class DhAudio implements Audio {
    /** Le VRAI backend audio OpenAL de libGDX 1.9.7 (ouvre le device ALC dans son ctor). {@code null} si indispo. */
    private final OpenALAudio al;
    /** Sons créés — pour {@link #stopAllSounds()} (le backend gdx n'expose pas d'arrêt global). Refs faibles. */
    private final Set<OpenALSound> sounds = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private int stereo = 1;

    public DhAudio() {
        OpenALAudio a = null;
        try {
            // Ctor par défaut : 16 sources simultanées, enregistre les décodeurs ogg/wav/mp3, ouvre le device ALC.
            a = new OpenALAudio();
            System.out.println("[DhAudio] OpenAL initialisé (backend audio libGDX 1.9.7)");
        } catch (Throwable t) {
            // Aucun périphérique audio (headless/CI) → muet, sans planter.
            System.out.println("[DhAudio] OpenAL indisponible → audio muet (" + t + ")");
            a = null;
        }
        this.al = a;
    }

    /** À appeler à CHAQUE frame par la boucle de rendu (streaming des musiques), comme le fait Lwjgl3Application. */
    public void update() {
        if (al != null) al.update();
    }

    @Override public Sound newSound(FileHandle file) {
        if (al == null) return noop(Sound.class);
        OpenALSound s = al.newSound(file);
        if (s != null) sounds.add(s);
        return s;
    }

    @Override public Music newMusic(FileHandle file) {
        return al != null ? al.newMusic(file) : noop(Music.class);
    }

    @Override public int getStereoSoundsSupported() { return stereo; }
    @Override public void setStereoSoundsSupported(int n) { this.stereo = n; }

    @Override public void stopAllSounds() {
        if (al == null) return;
        // Le backend gdx n'expose pas d'arrêt global : on arrête chaque son créé (OpenALSound.stop() arrête
        // toutes les instances de ce son). Réel — pas un no-op.
        synchronized (sounds) { for (OpenALSound s : sounds) if (s != null) s.stop(); }
    }

    @Override public void dispose() {
        if (al != null) al.dispose();
    }

    // Hors interface RÉDUITE de PerBlue (leur Audio ne les déclare pas) — on délègue quand même,
    // au cas où une variante de l'interface les exige, sinon inoffensif.
    public AudioDevice newAudioDevice(int samplingRate, boolean isMono) {
        return al != null ? al.newAudioDevice(samplingRate, isMono) : noop(AudioDevice.class);
    }
    public AudioRecorder newAudioRecorder(int samplingRate, boolean isMono) {
        return al != null ? al.newAudioRecorder(samplingRate, isMono) : noop(AudioRecorder.class);
    }

    /** Repli no-op (aucun périphérique audio) : toutes les méthodes renvoient des valeurs par défaut. */
    private static <T> T noop(Class<T> iface) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
            (proxy, method, args) -> {
                Class<?> r = method.getReturnType();
                if (r == long.class) return 0L;
                if (r == int.class) return 0;
                if (r == float.class) return 0f;
                if (r == boolean.class) return false;
                return null;
            }));
    }
}
