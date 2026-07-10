package android.os;

/**
 * Implémentation FONCTIONNELLE de {@code android.os.SystemClock} pour desktop.
 *
 * Les stubs {@code com.google.android:android:4.1.1.4} (API 16) lèvent "Stub!" et n'ont pas
 * {@code elapsedRealtimeNanos()} (API 17). Le jeu l'appelle → cette classe la fournit
 * (System.nanoTime()). Placée en tête du classpath (build/classes avant le jar de stubs), elle
 * SHADOW le stub. Fidélité : RÉEL (horloge monotone via System.nanoTime/currentTimeMillis).
 * Voir desktop-port/BACKEND_STATUS.md #ANDROIDSTUBS.
 */
public final class SystemClock {
    private SystemClock() {}

    public static long uptimeMillis() { return System.nanoTime() / 1_000_000L; }
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long currentThreadTimeMillis() { return System.nanoTime() / 1_000_000L; }
    public static long currentThreadTimeMicro() { return System.nanoTime() / 1_000L; }
    public static long currentTimeMicro() { return System.currentTimeMillis() * 1000L; }
    public static boolean setCurrentTimeMillis(long millis) { return false; }
    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
