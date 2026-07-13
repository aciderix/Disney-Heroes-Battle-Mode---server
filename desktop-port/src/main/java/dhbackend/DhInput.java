package dhbackend;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Backend Input desktop ({@code com.badlogic.gdx.Input} — 17 méthodes). Porté de DragonSoul
 * `DsInput` (dont les méthodes étaient obfusquées a()/b()/c()… ; ici noms clairs). Les vrais
 * événements arrivent des callbacks GLFW ({@link GlfwInput}) sur le thread render ; des
 * événements synthétiques peuvent être injectés de n'importe quel thread via {@link #inject}
 * (pour le pilotage CLI) et sont drainés chaque frame. Constantes boutons libGDX/Android :
 * LEFT=0, RIGHT=1, MIDDLE=2 (identiques à GLFW). Fidélité : RÉEL (capteurs = 0, voir STATUS).
 */
public final class DhInput implements Input {
    private volatile InputProcessor processor;
    private volatile int mouseX, mouseY;
    private volatile int deltaX, deltaY;
    private final boolean[] buttons = new boolean[8];
    private final Set<Integer> keys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> justPressed = ConcurrentHashMap.newKeySet();
    private volatile long eventTime;
    private final ConcurrentLinkedQueue<Runnable> injected = new ConcurrentLinkedQueue<>();

    private boolean anyButton() { for (boolean b : buttons) if (b) return true; return false; }

    // --- interface Input (noms clairs 1.9.7) ---
    @Override public int getX() { return mouseX; }
    @Override public int getY() { return mouseY; }
    @Override public int getDeltaX() { return deltaX; }
    @Override public int getDeltaY() { return deltaY; }
    @Override public boolean isTouched() { return anyButton(); }
    @Override public boolean isTouched(int pointer) { return pointer == 0 && anyButton(); }
    @Override public long getCurrentEventTime() { return eventTime; }
    @Override public boolean isButtonPressed(int button) { return button >= 0 && button < buttons.length && buttons[button]; }
    // ANY_KEY (= -1) : renvoie vrai si une touche quelconque est enfoncée.
    @Override public boolean isKeyPressed(int keycode) { return keycode == -1 ? !keys.isEmpty() : keys.contains(keycode); }
    @Override public boolean isKeyJustPressed(int keycode) { return keycode == -1 ? !justPressed.isEmpty() : justPressed.contains(keycode); }
    @Override public InputProcessor getInputProcessor() { return processor; }
    @Override public void setInputProcessor(InputProcessor p) { this.processor = p; }
    // DEFERRED (BACKEND_STATUS.md #INPUT) : capteurs/pression tactile non applicables desktop.
    @Override public double getLastForceTouch() { return 0; }
    @Override public boolean isPeripheralAvailable(Input.Peripheral p) { return p == Input.Peripheral.HardwareKeyboard; }
    // NO-OP : pas de touche back/clavier écran/capture curseur en headless.
    @Override public void setCatchBackKey(boolean b) { }
    @Override public void setCursorCatched(boolean b) { }
    @Override public void setOnscreenKeyboardVisible(boolean b) { }

    // --- livraison d'événements (thread render) ---
    public void touchDown(int x, int y, int button) {
        deltaX = x - mouseX; deltaY = y - mouseY; mouseX = x; mouseY = y; eventTime = System.nanoTime();
        if (button >= 0 && button < buttons.length) buttons[button] = true;
        InputProcessor p = processor; if (p != null) p.touchDown(x, y, 0, button);
    }
    public void touchUp(int x, int y, int button) {
        deltaX = x - mouseX; deltaY = y - mouseY; mouseX = x; mouseY = y; eventTime = System.nanoTime();
        if (button >= 0 && button < buttons.length) buttons[button] = false;
        InputProcessor p = processor; if (p != null) p.touchUp(x, y, 0, button);
    }
    public void moved(int x, int y) {
        deltaX = x - mouseX; deltaY = y - mouseY; mouseX = x; mouseY = y; eventTime = System.nanoTime();
        InputProcessor p = processor; if (p == null) return;
        if (anyButton()) p.touchDragged(x, y, 0); else p.mouseMoved(x, y);
    }
    public void scrolled(int amount) {
        eventTime = System.nanoTime();
        InputProcessor p = processor; if (p != null) p.scrolled(amount);
    }
    public void keyDown(int keycode) {
        keys.add(keycode); justPressed.add(keycode); eventTime = System.nanoTime();
        InputProcessor p = processor; if (p != null) p.keyDown(keycode);
    }
    public void keyUp(int keycode) {
        keys.remove(keycode); eventTime = System.nanoTime();
        InputProcessor p = processor; if (p != null) p.keyUp(keycode);
    }
    public void keyTyped(char c) {
        eventTime = System.nanoTime();
        InputProcessor p = processor; if (p != null) p.keyTyped(c);
    }

    // --- injection synthétique (pilotage CLI), drainée chaque frame ---
    public void inject(Runnable r) { injected.add(r); }
    // touchUp DIFFÉRÉS : pour un press-relâche RÉEL (down maintenant, up après N drains, comme un doigt).
    private final java.util.List<int[]> pendingUp = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public void drain() {
        justPressed.clear(); // "just pressed" = valable pour la frame courante
        Runnable r; while ((r = injected.poll()) != null) { try { r.run(); } catch (Throwable t) { t.printStackTrace(); } }
        synchronized (pendingUp) {
            for (java.util.Iterator<int[]> it = pendingUp.iterator(); it.hasNext();) {
                int[] d = it.next();
                if (--d[3] <= 0) { touchUp(d[0], d[1], 0); it.remove(); }
            }
        }
    }
    /** Tap complet (down+up) en un point, en injection (même frame). */
    public void tap(int x, int y) { inject(() -> { touchDown(x, y, 0); touchUp(x, y, 0); }); }
    /** Press-relâche RÉEL : touchDown maintenant, touchUp après {@code holdFrames} drains (comme un doigt). */
    public void tapHold(int x, int y, int holdFrames) {
        inject(() -> touchDown(x, y, 0));
        synchronized (pendingUp) { pendingUp.add(new int[]{x, y, 0, Math.max(1, holdFrames)}); }
    }
}
