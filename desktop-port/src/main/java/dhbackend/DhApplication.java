package dhbackend;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.utils.Clipboard;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Backend Application desktop ({@code com.badlogic.gdx.Application} — 18 méthodes). Porté de
 * DragonSoul `DsApplication` (dé-obfusqué ; {@code getType$2826c76():int} → {@code getType():
 * ApplicationType}). Logs → stdout/stderr ; {@code postRunnable} met en file sur le thread
 * render (drainé par le launcher). {@code getType()=Android} : l'APK ne fournit que le jeu
 * d'assets Android (ETC/XHDPI) → on prend le chemin d'assets Android du jeu. Fidélité : RÉEL
 * (presse-papiers = null, voir BACKEND_STATUS.md #CLIPBOARD).
 */
public final class DhApplication implements Application {
    private final ApplicationListener listener;
    private final Graphics graphics;
    private final Input input;
    private final Audio audio;
    private final File prefsDir;
    private final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();

    public DhApplication(ApplicationListener listener, Graphics graphics, Input input, Audio audio, File prefsDir) {
        this.listener = listener; this.graphics = graphics; this.input = input;
        this.audio = audio; this.prefsDir = prefsDir;
        prefsDir.mkdirs();
    }

    @Override public ApplicationListener getApplicationListener() { return listener; }
    @Override public Graphics getGraphics() { return graphics; }
    @Override public Input getInput() { return input; }
    @Override public Audio getAudio() { return audio; }
    @Override public Preferences getPreferences(String name) { return new DhPreferences(prefsDir, name); }
    @Override public Application.ApplicationType getType() { return Application.ApplicationType.Android; }
    // DEFERRED (BACKEND_STATUS.md #CLIPBOARD) : presse-papiers non branché.
    @Override public Clipboard getClipboard() { return null; }

    @Override public void log(String tag, String msg) { System.out.println("[" + tag + "] " + msg); }
    @Override public void log(String tag, String msg, Throwable t) { System.out.println("[" + tag + "] " + msg); t.printStackTrace(System.out); }
    @Override public void debug(String tag, String msg) { System.out.println("[DEBUG " + tag + "] " + msg); }
    @Override public void error(String tag, String msg) { System.err.println("[ERROR " + tag + "] " + msg); }
    @Override public void error(String tag, String msg, Throwable t) { System.err.println("[ERROR " + tag + "] " + msg); t.printStackTrace(System.err); }

    @Override public void postRunnable(Runnable r) { runnables.add(r); }
    @Override public void exit() { }
    @Override public void addLifecycleListener(LifecycleListener l) { }
    @Override public void removeLifecycleListener(LifecycleListener l) { }
    @Override public void resetKeyboardSuggestions() { }
    @Override public boolean supportsAndroidEditables() { return false; }

    /** Draine les runnables mis en file, sur le thread render. Appelé chaque frame par le launcher. */
    public void drainRunnables() {
        Runnable r;
        while ((r = runnables.poll()) != null) {
            try { r.run(); } catch (Throwable t) { t.printStackTrace(System.out); }
        }
    }
}
