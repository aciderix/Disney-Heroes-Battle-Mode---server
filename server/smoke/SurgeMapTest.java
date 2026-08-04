import com.perblue.heroes.network.messages.DistrictType;
import com.perblue.heroes.network.messages.EnvironmentType;
import dhserver.ServerContext;
import dhserver.ServerSurgeMap;

/**
 * SURGE (#72) incrément 4b-i — CARTE DES DISTRICTS : la liste des districts actifs vient des DONNÉES du jeu
 * (map_districts.tab = 27 districts non-QG), chacun avec un environnement réel et un multiplicateur > 0, trié
 * par multiplicateur décroissant. Aucune donnée inventée.
 */
public final class SurgeMapTest {

  static void check(boolean c, String m) { if (!c) throw new AssertionError("[surge-map] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.util.List<DistrictType> ds = ServerSurgeMap.activeDistricts();

    // map_districts.tab décrit 27 districts actifs (BLACK_MARKET/ESPORTS_ARENA/SUBWAY/HACKER_ENCLAVE).
    check(ds.size() == 27, "27 districts actifs attendus (map_districts.tab), obtenu " + ds.size());

    double prev = Double.MAX_VALUE;
    for (DistrictType d : ds) {
      EnvironmentType env = ServerSurgeMap.environment(d);
      check(env != null && env != EnvironmentType.DEFAULT, d + " doit avoir un environnement réel, a " + env);
      double m = ServerSurgeMap.multiplier(d);
      check(m > 0, d + " doit avoir un multiplicateur > 0, a " + m);
      check(m <= prev, "tri par multiplicateur décroissant rompu à " + d + " (" + m + " > " + prev + ")");
      prev = m;
    }
    // Le QG (FF) n'est pas un district à combattre → absent de la liste.
    check(!ds.contains(com.perblue.heroes.game.data.surge.SurgeStats.getHQDistrict()), "le QG ne doit pas figurer");

    System.out.println("[surge-map] OK — " + ds.size() + " districts actifs (données du jeu), triés par "
        + "multiplicateur ; ex. tête=" + ds.get(0) + " (×" + ServerSurgeMap.multiplier(ds.get(0)) + ") — #72 incrément 4b-i");
  }
}
