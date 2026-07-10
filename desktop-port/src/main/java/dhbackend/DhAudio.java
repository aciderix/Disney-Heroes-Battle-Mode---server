package dhbackend;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;

import java.lang.reflect.Proxy;

/**
 * Backend Audio desktop — **NO-OP pour l'instant** (audio différé).
 *
 * ⚠️ DEFERRED (BACKEND_STATUS.md #AUDIO) : implémentation RÉELLE à porter depuis DragonSoul
 * `DsAudio` (OpenAL LWJGL + décodage OGG via STB Vorbis). Ici {@code newSound}/{@code newMusic}
 * renvoient des objets **no-op** (via Proxy dynamique : toutes les méthodes renvoient des
 * valeurs par défaut) pour éviter tout NPE au boot ; le jeu tourne mais **sans son**. Ce n'est
 * PAS une rustine sur une vérification : l'audio n'est pas requis pour franchir le rendu/boot,
 * et le manque est explicitement tracé. À remplacer par le vrai backend avant "prod".
 */
public final class DhAudio implements Audio {
    private int stereo = 1;

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

    @Override public Sound newSound(FileHandle file) { return noop(Sound.class); }
    @Override public Music newMusic(FileHandle file) { return noop(Music.class); }
    @Override public int getStereoSoundsSupported() { return stereo; }
    @Override public void setStereoSoundsSupported(int n) { this.stereo = n; }
    @Override public void stopAllSounds() { }
    @Override public void dispose() { }

    // Les méthodes ci-dessous ne sont PAS dans l'interface réduite de PerBlue (gardées au cas
    // où une variante de l'interface les exige — sinon inoffensives).
    public AudioDevice newAudioDevice(int samplingRate, boolean isMono) { return noop(AudioDevice.class); }
    public AudioRecorder newAudioRecorder(int samplingRate, boolean isMono) { return noop(AudioRecorder.class); }
}
