package dhserver;

import com.perblue.heroes.game.objects.IBossClaimStatus;
import com.perblue.heroes.game.objects.IInvasionUser;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.UserInvasionData;

/**
 * INVASION (#69) — implémentation SERVEUR de {@link IInvasionUser}, simple ADAPTATEUR au-dessus du
 * {@link UserInvasionData} persisté (table {@code user_invasion}).
 *
 * <p>Pourquoi : la logique du jeu réclame un {@code IInvasionUser} — notamment
 * {@code InvasionHelper.claimGuildRankRewards} / {@code claimUserRankRewards} (récompenses de fin de ligue) et
 * {@code recordBossFightOutcome}. Le client a son {@code ClientInvasionUser}, adossé à {@code DH.app} ; côté
 * serveur on expose la même interface sur notre état autoritatif. Les écritures modifient directement l'objet
 * {@code UserInvasionData} — il suffit donc de le re-persister après l'appel pour conserver les effets.
 */
public final class ServerInvasionUser implements IInvasionUser {

  private final UserInvasionData d;

  public ServerInvasionUser(UserInvasionData data) { this.d = data; }

  /** L'état sous-jacent (à re-persister après un appel qui écrit). */
  public UserInvasionData data() { return d; }

  // --- Identité / progression ---
  @Override public long getUserID()      { return d.userID; }
  @Override public long getInvasionID()  { return d.invasionID; }
  @Override public long getPoints()      { return d.points; }
  @Override public void setPoints(long points, long l2, String... reason) { d.points = points; }
  @Override public long getLastClaimedPoints() { return d.lastClaimedPoints; }
  @Override public void setLastClaimedPoints(long v) { d.lastClaimedPoints = v; }
  @Override public int getUserLevelSnapshot()  { return d.userLevelSnapshot; }

  // --- Compteurs de combat ---
  @Override public int getBossBattlesFought()  { return d.bossBattlesFought; }
  @Override public void setBossBattlesFought(int v) { d.bossBattlesFought = v; }
  @Override public int getBossBattlesWon()     { return d.bossBattlesWon; }
  @Override public void setBossBattlesWon(int v) { d.bossBattlesWon = v; }
  @Override public int getBreakerBattlesFought() { return d.breakerBattlesFought; }
  @Override public void setBreakerBattlesFought(int v) { d.breakerBattlesFought = v; }
  @Override public int getBreakerBattlesWon()  { return d.breakerBattlesWon; }
  @Override public void setBreakerBattlesWon(int v) { d.breakerBattlesWon = v; }
  @Override public int getBreakersGained()     { return d.breakersGained; }
  @Override public void setBreakersGained(int v) { d.breakersGained = v; }
  @Override public int getBreakersSpent()      { return d.breakersSpent; }
  @Override public void setBreakersSpent(int v) { d.breakersSpent = v; }
  @Override public int getDailyGuildBossDefeated() { return d.dailyGuildBossDefeated; }
  @Override public void setDailyGuildBossDefeated(int v) { d.dailyGuildBossDefeated = v; }

  // --- Consommables / empowerments ---
  @Override public int getEmpowerStoneCount()  { return d.empowerStoneCount; }
  @Override public void setEmpowerStoneCount(int v) { d.empowerStoneCount = v; }
  @Override public int getStaminaPurchases()   { return d.invasionStaminaPurchases; }
  @Override public void setStaminaPurchases(int v) { d.invasionStaminaPurchases = v; }
  @Override public int getSupplyPackageCount() { return d.supplyPackageCount; }
  @Override public void recordTeamEmpower()    { d.teamEmpowerments++; }

  @Override public int getUnitEmpowerment(UnitType unit) {
    if (d.unitEmpowerment == null || unit == null) return 0;
    Object v = d.unitEmpowerment.get(unit);
    return v instanceof Number ? ((Number) v).intValue() : 0;
  }

  @Override @SuppressWarnings("unchecked")
  public void setUnitEmpowerment(UnitType unit, int value, String... reason) {
    if (unit == null) return;
    if (d.unitEmpowerment == null) d.unitEmpowerment = new java.util.HashMap<>();
    ((java.util.Map<Object, Object>) d.unitEmpowerment).put(unit, value);
  }

  // --- Récompenses de rang (fin de ligue) ---
  @Override public boolean hasGuildRankRewards() { return d.hasGuildRankRewards; }
  @Override public void setGuildRankRewards(boolean v) { d.hasGuildRankRewards = v; }
  @Override public boolean hasUserRankRewards()  { return d.hasUserRankRewards; }
  @Override public void setUserRankRewards(boolean v) { d.hasUserRankRewards = v; }
  @Override public boolean hasViewedFeaturedContent() { return d.viewedFeaturedContent; }

  /** Statut de réclamation d'un boss, stocké dans {@code UserInvasionData.bossClaimStatus} (objets du jeu). */
  @Override public IBossClaimStatus getBossClaimStatus(long bossID) {
    if (d.bossClaimStatus == null) d.bossClaimStatus = new java.util.HashMap<>();
    Object v = d.bossClaimStatus.get(bossID);
    if (v instanceof IBossClaimStatus) return (IBossClaimStatus) v;
    return new SimpleClaimStatus();          // statut neuf : rien de réclamé
  }

  /** Statut « rien réclamé » minimal, quand le boss n'a pas encore d'entrée. */
  static final class SimpleClaimStatus implements IBossClaimStatus {
    private boolean rewards, escape;
    private final java.util.List<Object> earned = new java.util.ArrayList<>();
    @Override public java.util.List getRewardsEarned() { return earned; }
    @Override public boolean isRewardsClaimed() { return rewards; }
    @Override public void setRewardsClaimed(boolean v) { rewards = v; }
    @Override public boolean isEscapeClaimed() { return escape; }
    @Override public void setEscapeClaimed(boolean v) { escape = v; }
  }
}
