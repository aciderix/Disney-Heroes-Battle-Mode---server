import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.ModeDifficulty;
import dhserver.*;

/**
 * Outil DEV (opérateur) — prépare le compte (id=1) pour ENTRER et JOUER un mode PORT via la VITRINE en jeu :
 *   • roster boosté (RED niv.100 6★) pour gagner le combat ;
 *   • pour PORT_DOCKS et PORT_WAREHOUSE : purge du cooldown d'attaque + CHANCES QUOTIDIENNES FRAÎCHES
 *     (compteurs {@code getUseKey}/{@code getChallengeKey}/{@code getResetUseKey} remis à 0 via {@code setDailyUses}).
 * L'OUVERTURE de WAREHOUSE vient de l'ÉVÉNEMENT poussé (ServerEvents) — RIEN ici ne force le gate (§2).
 * État de compte légitime (même esprit que ExpAdminBoost/PortRaidAdmin). Usage : PortEnterAdmin [db].
 */
public final class PortEnterAdmin {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore s = new UserStore(db)) {
      ServerUser su = s.loadOrCreate(1L, 1);
      BootData bd = su.bootData();
      if (bd.userInfo.basicInfo.teamLevel < 200) bd.userInfo.basicInfo.teamLevel = 200;
      // Roster boosté (pour la VICTOIRE)
      UnitType[] team = { UnitType.RALPH, UnitType.HERCULES, UnitType.MAUI, UnitType.SULLEY, UnitType.VANELLOPE };
      for (UnitType t : team) {
        try { su.grantHero(t, Rarity.RED, 100, 6); System.out.println("[portenteradmin] " + t + " → RED niv.100 6★"); }
        catch (Throwable e) { System.out.println("[portenteradmin] " + t + " échec: " + e); }
      }
      var iu = su.gameIndividual();
      for (GameMode mode : new GameMode[]{GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE}) {
        CooldownType cd = com.perblue.heroes.game.logic.DifficultyModeHelper.getCooldownType(mode);
        if (cd != null) iu.setCooldownEnd(cd, 0L);                                   // purge cooldown d'attaque
        for (String key : new String[]{
            com.perblue.heroes.game.logic.DifficultyModeHelper.getUseKey(mode),
            com.perblue.heroes.game.logic.DifficultyModeHelper.getChallengeKey(mode),
            com.perblue.heroes.game.logic.DifficultyModeHelper.getResetUseKey(mode)}) {
          if (key != null && !key.isEmpty()) { try { iu.setDailyUses(key, 0); } catch (Throwable ignore) {} }
        }
      }
      s.save(su);
      ServerUser v = s.loadOrCreate(1L, 1);
      var u2 = v.gameUser();
      var NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
      for (GameMode mode : new GameMode[]{GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE}) {
        String uk = com.perblue.heroes.game.logic.DifficultyModeHelper.getUseKey(mode);
        int rem = com.perblue.heroes.game.logic.DailyActivityHelper.getRemainingDailyUses(u2, uk, NONE);
        System.out.println("[portenteradmin] " + mode + " chances restantes=" + rem
            + " (max=" + com.perblue.heroes.game.logic.DailyActivityHelper.getMaxDailyUses(u2, uk, NONE) + ") [persisté]");
      }
    }
  }
}
