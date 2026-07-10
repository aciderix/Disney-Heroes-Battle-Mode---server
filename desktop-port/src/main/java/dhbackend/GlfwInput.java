package dhbackend;

import org.lwjgl.glfw.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Relaie les vrais événements clavier/souris/molette GLFW vers {@link DhInput}, en mappant
 * les codes touches GLFW vers les codes libGDX (Android). La souris agit comme pointeur touch 0.
 * Les callbacks sont gardés en champs pour que les pointeurs natifs GLFW restent valides.
 * Porté de DragonSoul `GlfwInput` (mapping keycodes identique, indépendant de la version).
 */
public final class GlfwInput {
    private double cx, cy;
    private final GLFWCursorPosCallback cursorCb;
    private final GLFWMouseButtonCallback buttonCb;
    private final GLFWScrollCallback scrollCb;
    private final GLFWKeyCallback keyCb;
    private final GLFWCharCallback charCb;

    public GlfwInput(long win, DhInput input) {
        cursorCb = GLFWCursorPosCallback.create((w, x, y) -> { cx = x; cy = y; input.moved((int) x, (int) y); });
        buttonCb = GLFWMouseButtonCallback.create((w, button, action, mods) -> {
            if (action == GLFW_PRESS) input.touchDown((int) cx, (int) cy, button);
            else if (action == GLFW_RELEASE) input.touchUp((int) cx, (int) cy, button);
        });
        scrollCb = GLFWScrollCallback.create((w, dx, dy) -> input.scrolled((int) -Math.signum(dy)));
        keyCb = GLFWKeyCallback.create((w, key, scancode, action, mods) -> {
            int gdx = mapKey(key);
            if (gdx < 0) return;
            if (action == GLFW_PRESS) input.keyDown(gdx);
            else if (action == GLFW_RELEASE) input.keyUp(gdx);
        });
        charCb = GLFWCharCallback.create((w, codepoint) -> input.keyTyped((char) codepoint));

        glfwSetCursorPosCallback(win, cursorCb);
        glfwSetMouseButtonCallback(win, buttonCb);
        glfwSetScrollCallback(win, scrollCb);
        glfwSetKeyCallback(win, keyCb);
        glfwSetCharCallback(win, charCb);
    }

    /** Code touche GLFW → code libGDX (Android) Input.Keys ; -1 si non mappé. */
    public static int mapKey(int k) {
        if (k >= GLFW_KEY_A && k <= GLFW_KEY_Z) return 29 + (k - GLFW_KEY_A);   // A=29..Z=54
        if (k >= GLFW_KEY_0 && k <= GLFW_KEY_9) return 7 + (k - GLFW_KEY_0);    // NUM_0=7..9=16
        switch (k) {
            case GLFW_KEY_SPACE: return 62;
            case GLFW_KEY_ENTER: case GLFW_KEY_KP_ENTER: return 66;
            case GLFW_KEY_BACKSPACE: return 67;
            case GLFW_KEY_ESCAPE: return 131;   // ESCAPE (utilisé comme Back)
            case GLFW_KEY_TAB: return 61;
            case GLFW_KEY_LEFT: return 21;
            case GLFW_KEY_RIGHT: return 22;
            case GLFW_KEY_UP: return 19;
            case GLFW_KEY_DOWN: return 20;
            case GLFW_KEY_LEFT_SHIFT: case GLFW_KEY_RIGHT_SHIFT: return 59;
            case GLFW_KEY_LEFT_CONTROL: case GLFW_KEY_RIGHT_CONTROL: return 129;
            case GLFW_KEY_COMMA: return 55;
            case GLFW_KEY_PERIOD: return 56;
            default: return -1;
        }
    }
}
