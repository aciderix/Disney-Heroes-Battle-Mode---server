package dhserver;

import com.perblue.heroes.game.data.misc.MapDistrictStats;
import com.perblue.heroes.game.data.surge.SurgeStats;
import com.perblue.heroes.network.messages.DistrictType;
import com.perblue.heroes.network.messages.EnvironmentType;

/**
 * SURGE (#72) incrément 4b-i — CARTE DES DISTRICTS, 100 % code+données du jeu (§3/§4, rien d'inventé).
 *
 * <p>La carte SURGE est définie par les DONNÉES du jeu : {@code map_districts.tab} associe chaque
 * {@link DistrictType} actif à un {@code EnvironmentType} (les 27 districts BLACK_MARKET/ESPORTS/SUBWAY/
 * HACKER_ENCLAVE) ; {@code creep_surge_nodes.tab} donne le POINT_MULTIPLIER par district (3.5 → 1). On lit ces
 * données via les classes du jeu : {@code MapDistrictStats.getEnvironment} (district → environnement, {@code
 * DEFAULT} = hors carte) et {@code SurgeStats.getMultiplier} (multiplicateur du district). Le QG
 * ({@code SurgeStats.getHQDistrict} = FF) est exclu (ce n'est pas un district à combattre).
 *
 * <p>Sert de base à la POSE des adversaires (incrément 4b-ii) : un adversaire par district actif, et au scoring
 * (le multiplicateur du district est déjà appliqué par {@code recordOutcome}, cf. ServerSurgeCombat).
 */
public final class ServerSurgeMap {

  private ServerSurgeMap() {}

  /** Les districts ACTIFS de la carte (env ≠ DEFAULT, hors QG), triés par multiplicateur décroissant (ordre des
   *  nœuds : les plus « profonds » d'abord). 100 % données du jeu. */
  public static java.util.List<DistrictType> activeDistricts() {
    ServerContext.init();
    DistrictType hq = SurgeStats.getHQDistrict();
    java.util.List<DistrictType> out = new java.util.ArrayList<>();
    for (DistrictType d : DistrictType.values()) {
      if (d == hq) continue;
      EnvironmentType env = MapDistrictStats.getEnvironment(d);
      if (env == null || env == EnvironmentType.DEFAULT) continue;   // hors carte courante
      out.add(d);
    }
    out.sort((a, b) -> Double.compare(SurgeStats.getMultiplier(b), SurgeStats.getMultiplier(a)));
    return out;
  }

  /** Multiplicateur de points du district (données `creep_surge_nodes.tab` via le jeu). */
  public static double multiplier(DistrictType d) { ServerContext.init(); return SurgeStats.getMultiplier(d); }

  /** Environnement du district (données `map_districts.tab` via le jeu). */
  public static EnvironmentType environment(DistrictType d) { ServerContext.init(); return MapDistrictStats.getEnvironment(d); }
}
