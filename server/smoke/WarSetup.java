import com.perblue.heroes.network.messages.HeroLineup;
import com.perblue.heroes.network.messages.HeroLineupType;
import com.perblue.heroes.network.messages.HeroLineupUpdate;
import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.UnitType;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OUTIL DEV — prépare un compte pour EXERCER une guerre de guilde EN JEU.
 *
 * <p>Une défense de guerre demande <b>15 héros distincts</b> (trois équipes de cinq) et les sabotages coûtent
 * des <b>WAR_TOKENS</b> : un compte de tuto n'a ni l'un ni l'autre. Cet outil met le compte dans un état
 * légitime pour jouer, sans rien contourner — les héros sont créés par la méthode DU JEU
 * ({@code User.createAndAddHero}, via {@code ServerUser.grantHero}) et les lineups posées par le chemin
 * autoritatif normal ({@code applyHeroLineupUpdate} → {@code User.setHeroLineup}), exactement ce que fait le
 * client quand le joueur place sa défense.
 *
 * <pre>
 * Usage :
 *   WarSetup &lt;db&gt; &lt;userID&gt; heroes [n]        — donne n héros distincts (défaut 15), niveau/rang lisibles
 *   WarSetup &lt;db&gt; &lt;userID&gt; tokens [n]        — donne n WAR_TOKENS (défaut 5000)
 *   WarSetup &lt;db&gt; &lt;userID&gt; defense           — POSE les 3 équipes WAR_DEFENSE (pour un adversaire à attaquer)
 *   WarSetup &lt;db&gt; &lt;userID&gt; room &lt;CAR&gt;   — AFFECTE le joueur à une salle de sa guerre en cours
 *   WarSetup &lt;db&gt; &lt;userID&gt; show              — état : héros, jetons, défenses posées
 * </pre>
 *
 * <p><b>Le `defense` n'est à utiliser QUE pour l'adversaire</b> : côté joueur, la pose de défense doit être
 * faite EN JEU (c'est précisément ce qu'on vérifie).
 */
public final class WarSetup {

  /** Héros du pool de base, dans l'ordre — assez pour trois équipes de cinq. */
  static final UnitType[] POOL = {
      UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.GENIE, UnitType.STITCH,
      UnitType.HERCULES, UnitType.SULLEY, UnitType.WOODY, UnitType.MERLIN, UnitType.BELLE,
      UnitType.JACK_SPARROW, UnitType.MIKE, UnitType.MOANA, UnitType.MAUI, UnitType.BAYMAX,
      UnitType.JASMINE, UnitType.ALADDIN, UnitType.SHANK, UnitType.YAX, UnitType.DASH,
  };

  static List<UnitType> owned(ServerUser u) {
    List<UnitType> out = new ArrayList<>();
    try {
      for (Object o : u.gameUser().getHeroes()) {
        out.add(((com.perblue.heroes.game.objects.UnitData) o).getType());
      }
    } catch (Throwable t) { System.out.println("[setup] lecture du roster : " + t); }
    return out;
  }

  static void heroes(ServerUser u, int n) {
    int before = owned(u).size();
    int given = 0;
    for (UnitType t : POOL) {
      if (owned(u).size() >= n) break;
      try { u.grantHero(t, Rarity.BLUE_1, 40, 3); given++; }
      catch (Throwable ex) { System.out.println("[setup] " + t + " refusé : " + ex); }
    }
    System.out.println("[setup] héros : " + before + " → " + owned(u).size() + " (+" + given + ")");
  }

  /** Pose les 3 équipes WAR_DEFENSE par le CHEMIN AUTORITATIF (celui qu'emprunte le client). */
  static void defense(ServerUser u) {
    List<UnitType> all = owned(u);
    HeroLineupType[] types = {
        HeroLineupType.WAR_DEFENSE_1, HeroLineupType.WAR_DEFENSE_2, HeroLineupType.WAR_DEFENSE_3,
    };
    int idx = 0;
    for (int t = 0; t < types.length; t++) {
      HeroLineupUpdate up = new HeroLineupUpdate();
      up.type = types[t];
      up.iD = 0L;
      up.customName = "";
      up.realGearOptions = new HashMap<>();
      up.emeraldStatSlotChoices = new HashMap<>();
      HeroLineup hl = new HeroLineup();
      @SuppressWarnings("unchecked") List<Object> heroes = (List<Object>) hl.heroes;
      while (heroes.size() < 5 && idx < all.size()) heroes.add(all.get(idx++));
      up.lineup = hl;
      boolean ok = u.applyHeroLineupUpdate(up);
      System.out.println("[setup] " + types[t] + " ← " + heroes.size() + " héros" + (ok ? "" : " (REFUSÉE)"));
    }
  }

  static void show(ServerUser u) {
    List<UnitType> all = owned(u);
    System.out.println("[setup] joueur " + u.userID + " · " + all.size() + " héros · WAR_TOKENS="
        + u.gameUser().getResource(ResourceType.WAR_TOKENS));
    for (HeroLineupType t : new HeroLineupType[]{HeroLineupType.WAR_DEFENSE_1,
        HeroLineupType.WAR_DEFENSE_2, HeroLineupType.WAR_DEFENSE_3}) {
      HeroLineup hl = u.gameUser().getHeroLineup(t, 0L);
      int n = hl == null || hl.heroes == null ? 0 : hl.heroes.size();
      System.out.println("[setup]   " + t + " : " + n + " héros" + (n > 0 ? " " + hl.heroes : ""));
    }
  }

  /** Affecte le joueur à une salle de la guerre en cours de SA guilde — par la logique serveur
   *  ({@code ServerWarCars.assignCar}), celle-là même qu'exécute la commande ASSIGN_WAR_CAR. Sert à donner
   *  une DÉFENSE ADVERSE à attaquer ; côté joueur, l'affectation se fait EN JEU. */
  static void room(UserStore store, ServerUser u, String car) throws Exception {
    if (car == null) { System.out.println("[setup] usage : room <WarCarType>"); return; }
    dhserver.ServerGuild g = store.loadGuild(1, u.basicInfo().guildID);
    if (g == null || g.currentWarID <= 0) { System.out.println("[setup] pas de guerre en cours"); return; }
    dhserver.ServerWarState w = store.loadWar(1, g.currentWarID);
    if (w == null) { System.out.println("[setup] guerre introuvable"); return; }
    String err = dhserver.ServerWarCars.assignCar(g, w, u.userID, u.basicInfo().guildRole, u.userID,
        com.perblue.heroes.network.messages.WarCarType.valueOf(car.trim().toUpperCase()));
    if (err != null) { System.out.println("[setup] refus : " + err); return; }
    store.saveWar(w);
    System.out.println("[setup] joueur " + u.userID + " affecté à " + car + " (guerre #" + w.warID + ")");
  }

  public static void main(String[] a) throws Exception {
    if (a.length < 3) { System.out.println("Usage : WarSetup <db> <userID> heroes|tokens|defense|show [n]"); return; }
    ServerContext.init();
    long uid = Long.parseLong(a[1]);
    String cmd = a[2];
    // Le 4e argument est un ENTIER pour heroes/tokens, mais un WarCarType pour `room` → on ne le
    // convertit que quand c'est un nombre (sinon `room REDUCE_ATTACKER_HP_FLAT` plantait au parse).
    int n = -1;
    if (a.length > 3) { try { n = Integer.parseInt(a[3]); } catch (NumberFormatException ignore) {} }
    try (UserStore store = new UserStore(a[0])) {
      ServerUser u = store.loadIfExists(uid, 1);
      if (u == null) { System.out.println("[setup] joueur " + uid + " introuvable"); return; }
      switch (cmd) {
        case "heroes":  heroes(u, n > 0 ? n : 15); break;
        case "tokens":  u.giveResource(ResourceType.WAR_TOKENS, n > 0 ? n : 5000);
                        System.out.println("[setup] WAR_TOKENS → " + u.gameUser().getResource(ResourceType.WAR_TOKENS));
                        break;
        case "defense": defense(u); break;
        case "room":    room(store, u, a.length > 3 ? a[3] : null); return;   // écrit l'état de guerre
        case "show":    show(u); return;                     // lecture seule
        default: System.out.println("[setup] commande inconnue : " + cmd); return;
      }
      store.save(u);
      System.out.println("[setup] sauvé.");
      show(u);
    }
  }
}
