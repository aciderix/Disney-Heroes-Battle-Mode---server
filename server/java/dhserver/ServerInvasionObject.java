package dhserver;

import com.perblue.heroes.game.objects.IInvasion;
import com.perblue.heroes.network.messages.HeroTeam;
import com.perblue.heroes.network.messages.InvasionData;
import com.perblue.heroes.network.messages.InvasionLeague;
import com.perblue.heroes.network.messages.ModPrimaryType;
import com.perblue.heroes.network.messages.ModSecondaryType;
import com.perblue.heroes.network.messages.UnitType;

/**
 * INVASION (#69) — implémentation SERVEUR de {@link IInvasion}, adossée à l'{@link InvasionData} calculé par
 * {@link ServerInvasion} et aux TABLES DU JEU ({@code InvasionStats}).
 *
 * <p>Pourquoi elle est nécessaire : la logique du jeu réclame un {@code IInvasion} (le contexte de drop-table
 * des compositions de breakers appelle {@code getStartTime()}, {@code InvasionHelper} en prend un en paramètre…).
 * Le client a son {@code ClientInvasion}, mais il est adossé à {@code DH.app} (état d'écran) : côté serveur on
 * fournit la même interface à partir de notre état autoritatif. Aucune règle n'est réimplémentée — les
 * récompenses/algorithmes sont DÉLÉGUÉS aux tables du jeu.
 */
public final class ServerInvasionObject implements IInvasion, ServerInvasion.IInvasionProvider {

  @Override public IInvasion asGameInvasion() { return this; }

  private final InvasionData data;

  public ServerInvasionObject(InvasionData data) { this.data = data; }

  /** L'invasion couvrant {@code now} (fenêtre + rotation calculées depuis les données). */
  public static ServerInvasionObject at(long now) { return new ServerInvasionObject(ServerInvasion.buildData(now)); }

  public InvasionData data() { return data; }

  @Override public long getID() { return data.invasionID; }
  @Override public long getStartTime() { return data.startTime; }
  @Override public long getEndTime() { return data.endTime; }
  @Override public HeroTeam getTeam() { return data.team; }
  @Override public ModPrimaryType getPrimaryMod() { return data.primaryMod; }
  @Override public ModSecondaryType getSecondaryMod() { return data.secondaryMod; }
  @Override public int getPowerUpHeroEmpowerment() { return data.powerUpHeroEmpowerment; }
  @Override public int getPowerUpTeamEmpowerment() { return data.powerUpTeamEmpowerment; }

  @Override public java.util.List getFeaturedHeroes() {
    return data.featuredHeroes == null ? new java.util.ArrayList<>() : data.featuredHeroes;
  }

  @Override public java.util.Set getTeamHeroes() {
    java.util.Set<UnitType> s = new java.util.LinkedHashSet<>();
    if (data.teamHeroes != null) for (Object o : data.teamHeroes) if (o instanceof UnitType) s.add((UnitType) o);
    return s;
  }

  /** Nom d'équipe personnalisé : aucun (le client retombe sur le libellé localisé de l'équipe). */
  @Override public String getCustomTeamName(com.perblue.heroes.util.localization.Language language) { return null; }
  @Override public String getCustomTeamShortName(com.perblue.heroes.util.localization.Language language) { return null; }

  // --- Récompenses : DÉLÉGUÉES aux tables du jeu (jamais recalculées ici) ---

  @Override public com.perblue.heroes.game.data.invasion.InvasionBossReward getBossReward(
      com.perblue.heroes.network.messages.InvasionBossRewardType type,
      com.perblue.heroes.network.messages.InvasionBossType boss) {
    try { return com.perblue.heroes.game.data.invasion.InvasionStats.getBossReward(type, boss); }
    catch (Throwable t) { System.out.println("[invasion] getBossReward: " + t); return null; }
  }

  @Override public java.util.NavigableMap getLeagueGuildRankRewards(InvasionLeague league) {
    return leagueRewards(league);
  }

  @Override public java.util.NavigableMap getLeagueUserRankRewards(InvasionLeague league) {
    return leagueRewards(league);
  }

  private static java.util.NavigableMap leagueRewards(InvasionLeague league) {
    try {
      java.util.NavigableMap m = com.perblue.heroes.game.data.invasion.InvasionStats.getLeagueRewards(league);
      if (m != null) return m;
    } catch (Throwable t) { System.out.println("[invasion] getLeagueRewards: " + t); }
    return new java.util.TreeMap<>();
  }

  @Override public java.util.NavigableMap getProgressRewards() {
    try {
      java.util.NavigableMap m = com.perblue.heroes.game.data.invasion.InvasionStats.getDynamicProgressRewards(this);
      if (m != null) return m;
    } catch (Throwable t) { System.out.println("[invasion] getProgressRewards: " + t); }
    return new java.util.TreeMap<>();
  }

  @Override public com.perblue.heroes.game.objects.InvasionEmpowerChoice.InvasionEmpowerChoiceAlgorithm
      getEmpowerChoiceAlgorithm(com.perblue.heroes.network.messages.EmpowerChoiceID id) {
    return null;                                   // aucun algorithme personnalisé : comportement par défaut
  }

  /** Horaires de rafraîchissement du marchand : aucun programmé côté serveur pour l'instant. */
  @Override public Iterable merchantRefreshTimes() { return java.util.Collections.emptyList(); }
}
