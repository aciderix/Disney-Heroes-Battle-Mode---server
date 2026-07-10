package android.os;

/**
 * Stub FONCTIONNEL minimal de {@code android.os.Process} pour desktop (les stubs Android lèvent
 * "Stub!"/UnsatisfiedLinkError). Seules les méthodes appelées par le jeu/SDK (ex. Firebase perf
 * {@code myPid()}) sont fournies. Voir desktop-port/BACKEND_STATUS.md #ANDROIDSTUBS.
 */
public final class Process {
    private Process() {}
    public static int myPid() { return (int) ProcessHandle.current().pid(); }
    public static int myTid() { return (int) Thread.currentThread().getId(); }
    public static int myUid() { return 0; }
    public static void killProcess(int pid) { }
    public static void setThreadPriority(int priority) { }
    public static void setThreadPriority(int tid, int priority) { }
    public static long getElapsedCpuTime() { return 0; }
}
