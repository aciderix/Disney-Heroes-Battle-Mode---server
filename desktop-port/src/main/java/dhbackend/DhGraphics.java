package dhbackend;

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.glutils.GLVersion;

/**
 * Backend Graphics desktop ({@code com.badlogic.gdx.Graphics} — 19 méthodes ; l'interface de
 * PerBlue est RÉDUITE : pas de moniteurs/curseurs/liste de modes). Porté de DragonSoul
 * `DsGraphics`. Rapporte la taille de la fenêtre GLFW + le GL20 ; timings mis à jour chaque
 * frame par le launcher. Densité = 2.0 (XHDPI) pour matcher les assets ETC/XHDPI de l'APK.
 * Fidélité : RÉEL (partiel sur GL30/GLVersion — voir BACKEND_STATUS.md).
 */
public final class DhGraphics implements Graphics {
    public volatile int width, height;
    public volatile float deltaTime;
    public volatile long frameId;
    public volatile int fps;
    private float density = 2.0f;
    private final GL20 gl20;

    public DhGraphics(GL20 gl20, int width, int height) {
        this.gl20 = gl20; this.width = width; this.height = height;
    }

    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public int getBackBufferWidth() { return width; }
    @Override public int getBackBufferHeight() { return height; }
    @Override public float getDeltaTime() { return deltaTime; }
    @Override public float getRawDeltaTime() { return deltaTime; }
    @Override public int getFramesPerSecond() { return fps; }
    @Override public float getDensity() { return density; }
    @Override public float getTargetDensity() { return density; }
    @Override public float getPpiX() { return 96f; }
    @Override public float getPpiY() { return 96f; }

    @Override public GL20 getGL20() { return gl20; }
    // DEFERRED (BACKEND_STATUS.md #GL30) : pas de GL30 pour l'instant. Graphics (réduit) n'a
    // pas isGL30Available ; null acceptable tant que le jeu ne déréférence pas getGL30().
    @Override public GL30 getGL30() { return null; }

    // GLVersion RÉEL (requis : GameMain.initTextureCompressionType() appelle
    // getDebugVersionString()). Construit une fois le contexte GL courant, depuis glGetString.
    // ApplicationType.Android pour rester sur le chemin de détection Android du jeu.
    private volatile GLVersion glVersion;
    @Override public GLVersion getGLVersion() {
        if (glVersion == null) {
            String ver = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            String vendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String rend = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            glVersion = new GLVersion(com.badlogic.gdx.Application.ApplicationType.Android,
                    ver != null ? ver : "2.0", vendor != null ? vendor : "", rend != null ? rend : "");
        }
        return glVersion;
    }

    // DisplayMode a un ctor protégé (w,h,refreshRate,bpp) — on en construit un réel par
    // réflexion pour que le jeu lise width/height (déréférencé sur le chemin Android de
    // scene2d ; renvoyer null y provoque un NPE de toute l'UI).
    private Graphics.DisplayMode displayMode() {
        try {
            java.lang.reflect.Constructor<Graphics.DisplayMode> c =
                Graphics.DisplayMode.class.getDeclaredConstructor(int.class, int.class, int.class, int.class);
            c.setAccessible(true);
            return c.newInstance(width, height, 60, 32);
        } catch (Exception e) { throw new RuntimeException("cannot build DisplayMode", e); }
    }
    @Override public Graphics.DisplayMode getDisplayMode() { return displayMode(); }

    @Override public boolean isContinuousRendering() { return true; }
    @Override public void setContinuousRendering(boolean b) { }
    @Override public void requestRendering() { }
    // DragonSoul renvoyait false (aucune extension déclarée). Suffisant tant que le jeu ne
    // dépend pas d'une extension GL précise ; à affiner via GL.getCapabilities() au besoin.
    @Override public boolean supportsExtension(String name) { return false; }
}
