import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.Map;

/**
 * Outil DEV (g54) : prépare la guerre ACTIVE existante pour REJOUER l'attaque EN JEU (témoin du correctif de
 * reframing spawnParticles). Idempotent : (1) synchronise les membres de la guilde adverse depuis leurs défenses
 * WAR_DEFENSE_1..3, (2) affecte le défenseur adverse à une SALLE (assignedCar) s'il n'en a pas, (3) remet à zéro
 * l'attaque de guerre du joueur (resetWarAttacks). Aucune règle réécrite — mêmes chemins que ServerWarMembers.
 * Usage : WarWitnessSetup <db> <shard> <myUserID> <myGuildID> <enemyGuildID>
 */
public final class WarWitnessSetup {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a[0]; int shard = Integer.parseInt(a[1]);
    long myUser = Long.parseLong(a[2]); long myGuild = Long.parseLong(a[3]); long enemyGuild = Long.parseLong(a[4]);
    try (UserStore store = new UserStore(db)) {
      ServerGuild mg = store.loadGuild(shard, myGuild);
      ServerGuild eg = store.loadGuild(shard, enemyGuild);
      long warID = mg.currentWarID;
      ServerWarState w = store.loadWar(shard, warID);
      System.out.println("[setup] guerre #" + warID + " état=" + w.state + " (mine=" + myGuild + " vs enemy=" + enemyGuild + ")");

      // 1) sync members of BOTH guilds from their WAR_DEFENSE lineups
      ServerWarMembers.syncAll(store, shard, w, mg);
      ServerWarMembers.syncAll(store, shard, w, eg);

      // 2) ensure the enemy leader has a room (assignedCar) so there is an attackable target
      WarGuildInfo eside = w.sideOf(enemyGuild);
      System.out.println("[setup] côté ennemi : " + eside.members.size() + " membre(s), " + eside.cars.size() + " voiture(s)");
      for (Object o : eside.members.entrySet()) {
        Map.Entry<?,?> e = (Map.Entry<?,?>) o;
        WarMemberInfo m = (WarMemberInfo) e.getValue();
        boolean hasDef = ServerWarMembers.hasDefense(m);
        System.out.println("   membre " + e.getKey() + " assignedCar=" + m.assignedCar + " hasDefense=" + hasDef
            + " defenses=" + (m.defenses == null ? 0 : m.defenses.size()));
        if (hasDef && (m.assignedCar == null || m.assignedCar == WarCarType.DEFAULT)) {
          m.assignedCar = WarCarType.REDUCE_ATTACKER_HP_FLAT;
          System.out.println("     -> affecté à la salle REDUCE_ATTACKER_HP_FLAT");
        }
      }
      w.putSide(enemyGuild, eside);   // ⚠️ re-encode (piège sideOf/putSide)
      store.saveWar(w);

      // 3) reset my war attacks
      ServerUser me = store.loadOrCreate(myUser, shard);
      me.resetWarAttacks();
      store.save(me);
      System.out.println("[setup] attaques de guerre du joueur " + myUser + " remises à zéro ; guerre sauvegardée.");
    }
  }
}
