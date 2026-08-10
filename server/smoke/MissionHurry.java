import dhserver.*;

/**
 * OUTIL DEV (#72 incr. 3c) — avance les MISSIONS IDLE du compte userID=1 de {@code cycles} cycles (via la méthode
 * DEBUG DU JEU {@code MissionHelper.debugHurryAllMissions}, cf. {@code ServerUser.debugHurryMissions}) → les timers
 * arrivent à zéro → {@code MissionClaimData} en attente. Sert à VÉRIFIER EN JEU la réclamation ({@code CLAIM_MISSION_REWARDS})
 * sans attendre les heures réelles. Usage : MissionHurry [db] [cycles]  (défauts : server/data/dh-server.db, 1).
 *
 * <p>⚠️ À lancer SERVEUR ARRÊTÉ (accès exclusif à la DB) puis relancer le client → l'écran MISSIONS relit l'état
 * PERSISTÉ et montre « CLAIM ALL ».
 */
public final class MissionHurry {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    int cycles = a.length > 1 ? Integer.parseInt(a[1]) : 1;
    UserStore store = new UserStore(db);
    ServerUser u = store.loadIfExists(1L, 1);
    if (u == null) { System.out.println("RESULT ERR — userID=1 introuvable (lancer FriendAcctSetup d'abord)"); store.close(); return; }
    u.debugHurryMissions(cycles);
    store.save(u);
    int pending = 0;
    for (Object o : u.gameUser().getIndividual().getMissionClaimData()) pending++;
    store.close();
    System.out.println("RESULT OK — missions avancées de " + cycles + " cycle(s) ; MissionClaimData en attente=" + pending);
  }
}
