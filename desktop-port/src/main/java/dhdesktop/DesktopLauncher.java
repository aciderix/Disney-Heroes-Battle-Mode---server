package dhdesktop;

import com.badlogic.gdx.Gdx;
import com.perblue.heroes.GameMain;
import dhbackend.*;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Launcher desktop de Disney Heroes avec **backend LWJGL3 maison** (dhbackend/*), miroir de
 * `dsbackend/` de DragonSoul mais contre le core libGDX (clair) du jeu. On crée une fenêtre
 * GLFW + contexte GL, on câble les shims dans le singleton {@code com.badlogic.gdx.Gdx}, on
 * instancie {@link GameMain} et on pilote son cycle create()/render() nous-mêmes.
 *
 * Props : dh.server=host:port (redirige ServerType.LIVE) ; dh.frames=N (rendre N frames puis
 * quitter, pour capture headless) ; dh.shot=fichier.ppm ; dh.w/dh.h ; dh.gdxnative=libgdx64.so ;
 * dh.rundir=dossier inscriptible (prefs/external/local).
 */
public final class DesktopLauncher {

    public static void main(String[] args) throws Exception {
        int W = Integer.getInteger("dh.w", 1280);
        int H = Integer.getInteger("dh.h", 720);
        int maxFrames = Integer.getInteger("dh.frames", 0);
        File runDir = new File(System.getProperty("dh.rundir", "build/run"));
        runDir.mkdirs();

        // Natif libGDX (Matrix4/BufferUtils JNI) — extrait par run-desktop.sh.
        String gdxNative = System.getProperty("dh.gdxnative");
        if (gdxNative != null && new File(gdxNative).exists()) {
            System.load(new File(gdxNative).getAbsolutePath());
            System.out.println("[launcher] natif libGDX chargé: " + gdxNative);
        }

        maybeRedirectServer(System.getProperty("dh.server"));

        // --- fenêtre GLFW + contexte GL (Xvfb + Mesa llvmpipe en conteneur) ---
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, "1".equals(System.getProperty("dh.visible")) ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
        long win = glfwCreateWindow(W, H, "Disney Heroes (desktop)", NULL, NULL);
        if (win == NULL) throw new IllegalStateException("glfwCreateWindow failed");
        glfwMakeContextCurrent(win);
        glfwSwapInterval(0);
        GL.createCapabilities();
        System.out.println("[launcher] GL " + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));

        // --- backend maison ---
        DhGL20 gl = new DhGL20();
        DhGraphics graphics = new DhGraphics(gl, W, H);
        DhInput input = new DhInput();
        new GlfwInput(win, input); // callbacks GLFW réels -> DhInput
        DhAudio audio = new DhAudio();
        DhFiles files = new DhFiles(new File(runDir, "external").getPath(), new File(runDir, "local").getPath());
        DhNet net = new DhNet();
        DhDeviceInfo device = new DhDeviceInfo();

        // --- câblage du singleton Gdx (noms clairs) ---
        GameMain game = new GameMain(device);
        DhApplication app = new DhApplication(game, graphics, input, audio, new File(runDir, "prefs"));
        Gdx.app = app;
        Gdx.graphics = graphics;
        Gdx.audio = audio;
        Gdx.input = input;
        Gdx.files = files;
        Gdx.net = net;
        Gdx.gl = gl;
        Gdx.gl20 = gl;
        System.out.println("[launcher] singleton Gdx câblé");

        // --- services plateforme (NO-OP, cf. DhBridges / BACKEND_STATUS.md #BRIDGES) ---
        wireBridges(game);

        // --- ouvreur des fichiers de stats .tab (normalement posé par l'AndroidLauncher) ---
        try { DhStatFileExt.install(); System.out.println("[launcher] StatFileHelper.ext installé"); }
        catch (Throwable t) { System.out.println("[launcher] StatFileExt échec: " + t); }

        // --- consentement enregistré (évite le dialogue d'accord bloquant en headless) ---
        // Écriture des prefs d'accord = ce que le handler « J'accepte » du jeu écrit (consentement
        // réel, pas une fausse réponse). ⚠️ DEFERRED (BACKEND_STATUS.md #CONSENT) : clés/valeurs
        // exactes à confirmer par décompilation de GameMain (noms empruntés à DragonSoul).
        preseedConsent(new File(runDir, "prefs"));

        System.out.println("[launcher] game.create() ...");
        game.create();
        System.out.println("[launcher] game.create() OK");
        game.resize(W, H);

        // --- boucle de rendu ---
        long frames = 0;
        double last = glfwGetTime();
        String shot = System.getProperty("dh.shot");
        // DEV : capture PÉRIODIQUE (dh.shotevery=N frames, 0=off) → on écrase `shot` toutes les N frames.
        // Utile quand le superviseur tue la run avant la fin (exit 144/SIGSTKFLT) : la dernière frame
        // atteinte reste sur disque, même sans arrêt gracieux de la boucle. Aucun effet en prod.
        int shotEvery = Integer.getInteger("dh.shotevery", 0);
        // DEV : ENREGISTREUR pas-à-pas (dh.tutorec) — une capture NUMÉROTÉE par tick d'autotap dans
        // build/rec/step_NNN.ppm, synchronisée avec les dumps [tutorec] du pilote → reconstitution exacte
        // de ce que le tuto déclenche étape par étape. Off par défaut, aucun effet en prod.
        boolean tutoRec = System.getProperty("dh.tutorec") != null
                && !"0".equals(System.getProperty("dh.tutorec"));
        int recCount = 0;
        boolean recTickPending = false;
        if (tutoRec) new java.io.File("build/rec").mkdirs();
        // Pilotage headless : dh.autotap=N injecte un tap au centre toutes les N frames (0 = off).
        // Sert à FAIRE AVANCER le tutoriel (dialogues « tap to continue ») sans utilisateur, pour
        // vérifier « tuto jouable de bout en bout » et observer ce que le client envoie ensuite.
        int autotap = Integer.getInteger("dh.autotap", 0);
        // Pilotage headless de DEV uniquement (off par défaut) : dh.autofight=1 active l'AUTO-COMBAT
        // D'ORIGINE du jeu (appel de son API publique setAutoAttack) quand on est dans un écran de
        // combat → les héros combattent seuls (l'IA lance les compétences). AUCUNE modif du jeu, AUCUN
        // effet en prod (le joueur lance sans ce drapeau) ni côté serveur. Sert à tester le jeu headless.
        // NB : Boolean.getBoolean n'accepte QUE "true" → "dh.autofight=1" le laissait à false (AUTO jamais
        // activé → héros passifs, skills seulement sur tap manuel → défaite). On accepte toute valeur non
        // "0"/"false" (cohérent avec dh.autotap/dh.tutodrive.debug).
        String autofightProp = System.getProperty("dh.autofight");
        boolean autofight = autofightProp != null && !"0".equals(autofightProp)
            && !"false".equalsIgnoreCase(autofightProp);
        // Mesure FPS : moyenne glissante toutes les N frames (dh.fps=N, 0=off), avec l'écran courant
        // → permet de relever les FPS EN COMBAT (screen=CoreAttackScreen/…).
        int fpsWindow = Integer.getInteger("dh.fps", 0);
        double fpsWindowStart = glfwGetTime();
        while (!glfwWindowShouldClose(win) && (maxFrames == 0 || frames < maxFrames)) {
            double now = glfwGetTime();
            graphics.deltaTime = (float) (now - last);
            graphics.frameId = frames;
            last = now;

            if (autotap > 0 && frames > 90 && frames % autotap == 0) {
                // DEV : d'abord taper la cible désignée par le tutoriel (bouton héros, etc.) via les API du
                // jeu. Le tap central de secours n'est légitime QUE pour un dialogue « tap to continue »
                // (aucun pointeur actif) : si le tuto a une cible non résolue sur l'écran courant
                // (hadActiveTarget), NE PAS taper au centre — ça partirait hors-script (coffre Diamant →
                // « Follow the tutorial arrow! »). Le pilote gère alors le retour vers le hub lui-même.
                if (!TutorialDriver.driveOnce(game, input, W, H) && !TutorialDriver.hadActiveTarget())
                    input.tap(W / 2, H / 2);
                // Le recorder DÉCIME lui-même (toutes RECEVERY frames) : on capture quand le pilote le
                // signale → on peut piloter à chaque frame (autotap=1, fiable) sans des milliers de captures.
                recTickPending = tutoRec && TutorialDriver.recCaptureRequested();
            }
            if (autofight && frames % 20 == 0) enableAutoCombat(game);  // DEV : bouton AUTO d'origine
            input.drain();          // input synthétique (pilotage) sur le thread render
            app.drainRunnables();   // Gdx.app.postRunnable
            game.render();
            glfwSwapBuffers(win);
            glfwPollEvents();
            frames++;

            if (shot != null && shotEvery > 0 && frames % shotEvery == 0) capture(W, H, shot);
            if (recTickPending) { capture(W, H, String.format("build/rec/step_%03d.ppm", recCount++)); recTickPending = false; }

            if (fpsWindow > 0 && frames % fpsWindow == 0) {
                double t = glfwGetTime();
                double fps = fpsWindow / (t - fpsWindowStart);
                fpsWindowStart = t;
                // Part du temps passée DANS l'émulation unidbg (spine+particules) vs le reste
                // (rasterisation logicielle llvmpipe, logique de jeu) → attribution du coût.
                double emuMs = dhbackend.unidbg.UnidbgVM.emuNanos() / 1e6 / fpsWindow;
                long emuCalls = dhbackend.unidbg.UnidbgVM.emuCalls() / fpsWindow;
                dhbackend.unidbg.UnidbgVM.emuReset();
                System.out.printf("[fps] frame %d: %.1f fps (%.1f ms/frame)  unidbg=%.1f ms/frame (%d appels)  reste=%.1f ms  screen=%s%n",
                    frames, fps, 1000.0 / fps, emuMs, emuCalls, Math.max(0, 1000.0 / fps - emuMs), currentScreen(game));
            }
        }

        if (shot != null) capture(W, H, shot);
        System.out.println("[launcher] arrêt après " + frames + " frames");
        glfwDestroyWindow(win);
        glfwTerminate();
    }

    /**
     * DEV : active l'AUTO-COMBAT d'origine du jeu si l'écran courant l'expose (API publique
     * {@code setAutoAttack} de {@code CoreAttackScreen}). Réflexion → aucune dépendance de compilation,
     * aucune modif du jeu. No-op hors combat. Idempotent (n'appelle que si pas déjà en auto).
     */
    private static boolean autoCombatLogged = false;
    private static void enableAutoCombat(GameMain game) {
        try {
            Object sm = game.getClass().getMethod("getScreenManager").invoke(game);
            Object screen = sm.getClass().getMethod("getScreen").invoke(sm);
            if (screen == null) return;
            java.lang.reflect.Method isAuto = findMethod(screen.getClass(), "isAutoAttack");
            java.lang.reflect.Method setAuto = findMethod(screen.getClass(), "setAutoAttack", boolean.class);
            if (isAuto == null || setAuto == null) return;   // pas un écran de combat
            if (!((Boolean) isAuto.invoke(screen))) {
                setAuto.invoke(screen, true);
                if (!autoCombatLogged) { autoCombatLogged = true;
                    System.out.println("[dev] auto-combat d'origine activé (setAutoAttack) sur "
                        + screen.getClass().getSimpleName()); }
            }
        } catch (Throwable ignore) { /* écran sans auto → no-op */ }
    }

    /** Trouve une méthode (par nom+params) en remontant la hiérarchie. */
    private static java.lang.reflect.Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { java.lang.reflect.Method m = k.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException e) { /* remonter */ }
        }
        return null;
    }

    /** Nom (simple) de l'écran courant, par réflexion — best-effort pour l'étiquette FPS. */
    private static String currentScreen(GameMain game) {
        try {
            Object sm = game.getClass().getMethod("getScreenManager").invoke(game);
            Object screen = sm.getClass().getMethod("getScreen").invoke(sm);
            return screen == null ? "null" : screen.getClass().getSimpleName();
        } catch (Throwable t) { return "?"; }
    }

    private static void wireBridges(GameMain game) {
        callSetter(game, "setNativeAccess", "com.perblue.heroes.INative");
        callSetter(game, "setAnalytics", "com.perblue.heroes.IAnalytics");
        callSetter(game, "setSocialNetworkManager", "com.perblue.heroes.social.SocialNetworkManager");
        callSetter(game, "setSupportManager", "com.perblue.heroes.ISupport");
        callSetter(game, "setTapjoyOfferwall", "com.perblue.heroes.ITapjoyOfferwall");
        callSetter(game, "setPlaybackRewards", "com.perblue.heroes.IPlaybackRewards");
    }

    private static void callSetter(GameMain game, String setter, String ifaceName) {
        try {
            Class<?> iface = Class.forName(ifaceName);
            Object noop = DhBridges.noop(ifaceName);
            game.getClass().getMethod(setter, iface).invoke(game, noop);
        } catch (Throwable t) {
            System.out.println("[launcher] bridge " + setter + " ignoré (" + t + ")");
        }
    }

    private static void preseedConsent(File prefsDir) {
        try {
            DhPreferences p = new DhPreferences(prefsDir, "rpgPrefs");
            p.putInteger("agreedPrivacyPolicyVersion", 999);
            p.putInteger("agreedTermsOfServiceVersion", 999);
            p.flush();
            System.out.println("[launcher] consentement pré-enregistré (rpgPrefs) — clés à vérifier (#CONSENT)");
        } catch (Throwable t) { System.out.println("[launcher] preseedConsent échec: " + t); }
    }

    private static void capture(int w, int h, String out) throws Exception {
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 3);
        glReadPixels(0, 0, w, h, GL_RGB, GL_UNSIGNED_BYTE, buf);
        File f = new File(out);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f))) {
            os.write(("P6\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
            byte[] row = new byte[w * 3];
            for (int y = h - 1; y >= 0; y--) { buf.position(y * w * 3); buf.get(row); os.write(row); }
        }
        System.out.println("[launcher] capture: " + f.getPath());
    }

    /**
     * Réécrit ServerType.LIVE vers notre serveur, par réflexion (SANS patch bytecode) :
     *  - gameHost/gamePort = hôte HTTP de login (le client POST {gameHost}:{gamePort}/login) ;
     *  - contentLocation = notre index.txt.
     * gameHost inclut le protocole (ex. "http://127.0.0.1") car l'URL de login est construite
     * comme {gameHost} + ":" + {gamePort} + "/login". Le serveur de JEU (TCP) est renvoyé par
     * la réponse JSON de /login ("data":"host:port"), pas fixé ici.
     */
    private static void maybeRedirectServer(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) return;
        try {
            String host = hostPort;
            int port = 8080;
            int c = hostPort.lastIndexOf(':');
            if (c > 0) { host = hostPort.substring(0, c); port = Integer.parseInt(hostPort.substring(c + 1)); }

            Class<?> st = Class.forName("com.perblue.heroes.ServerType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object live = Enum.valueOf((Class) st, "LIVE");
            set(st, live, "gameHost", "http://" + host);
            setInt(st, live, "gamePort", port);
            set(st, live, "contentLocation", "http://" + hostPort + "/live/index.txt");
            System.out.println("[launcher] ServerType.LIVE -> login http://" + hostPort + "/login, content http://" + hostPort + "/live/index.txt");
        } catch (Throwable t) {
            System.out.println("[launcher] redirection ServerType impossible (" + t + ")");
        }
    }

    private static void set(Class<?> cls, Object inst, String field, String v) throws Exception {
        Field f = cls.getDeclaredField(field); f.setAccessible(true); f.set(inst, v);
    }
    private static void setInt(Class<?> cls, Object inst, String field, int v) throws Exception {
        Field f = cls.getDeclaredField(field); f.setAccessible(true); f.setInt(inst, v);
    }
}
