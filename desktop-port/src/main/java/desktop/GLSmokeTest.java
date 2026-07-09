package desktop;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Smoke test : obtenir une fenêtre GLFW + contexte OpenGL sous Xvfb/Mesa (llvmpipe),
 *  dessiner une frame et l'écrire en PPM → prouve le pipeline de rendu headless. */
public final class GLSmokeTest {
    public static void main(String[] args) throws Exception {
        int w = 640, h = 480;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
        long win = glfwCreateWindow(w, h, "smoke", NULL, NULL);
        if (win == NULL) throw new IllegalStateException("createWindow failed");
        glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("GL_VERSION  = " + glGetString(GL_VERSION));
        System.out.println("GL_RENDERER = " + glGetString(GL_RENDERER));
        System.out.println("GL_VENDOR   = " + glGetString(GL_VENDOR));
        System.out.println("GLSL        = " + glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
        glClearColor(0.2f, 0.4f, 0.8f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        glFinish();
        int err = glGetError();

        // Lecture des pixels + écriture PPM (preuve visuelle du rendu headless).
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 3);
        glReadPixels(0, 0, w, h, GL_RGB, GL_UNSIGNED_BYTE, buf);
        String out = args.length > 0 ? args[0] : "build/gl-smoke.ppm";
        java.io.File f = new java.io.File(out);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f))) {
            os.write(("P6\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
            byte[] row = new byte[w * 3];
            for (int y = h - 1; y >= 0; y--) { buf.position(y * w * 3); buf.get(row); os.write(row); }
        }
        System.out.println("SMOKE OK: frame rendue, glError=" + err + ", capture=" + f.getPath());
        glfwDestroyWindow(win);
        glfwTerminate();
    }
}
