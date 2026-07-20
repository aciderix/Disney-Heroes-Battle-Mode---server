import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.game.data.battlepass.BattlePassV2Stats;
import com.perblue.heroes.game.data.SyncStatDataClientHelper;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * ÈRE DE CONTENU / STAT-SYNC — vérifie que le BootData d'un compte neuf pousse un
 * {@code battle_pass_v2_constants.tab} à SAISON COURANTE dans {@code statDataTxt}, et que si un client applique
 * ce fichier (comme au boot via {@code SyncStatDataClientHelper.updateStats}), les constantes du battle pass
 * (SEASON_START/HIDE) se déplacent au mois courant → {@code battlePassHidden()} = false (saison active EN JEU).
 */
public final class StatSyncProbe {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    BootData bd = su.bootData();

    // 1) statDataTxt contient bien l'override, clé = nom exact de parsedFiles (« .tab » inclus).
    if (bd.statDataTxt == null) throw new AssertionError("statDataTxt null — override non poussé");
    Object content = bd.statDataTxt.get("battle_pass_v2_constants.tab");
    if (!(content instanceof String)) throw new AssertionError("override absent/non-String pour battle_pass_v2_constants.tab");
    String tab = (String) content;
    System.out.println("[statsync] override poussé (" + tab.length() + " octets) :\n" + tab);
    if (!tab.contains("SEASON_START_TIME\t") || !tab.contains("HIDE_BATTLE_PASS_AFTER\t"))
      throw new AssertionError("override ne contient pas les 2 lignes datées");
    // Les autres constantes (réutilisées du fichier du jeu) doivent rester présentes (on ne réécrit rien d'autre).
    if (!tab.contains("MAX_DAILY_POINTS\t15") || !tab.contains("BUYOUT_AVAILABLE\t10"))
      throw new AssertionError("override a perdu des constantes du fichier d'origine");

    // 2) Simuler le client : appliquer l'override (updateStats re-parse les fichiers présents dans la map).
    long start0 = BattlePassV2Stats.getSeasonStartTime();
    long hide0  = BattlePassV2Stats.getBattlePassHiddenTime();
    java.util.Map<String, Object> map = new java.util.HashMap<>();
    map.put("battle_pass_v2_constants.tab", tab);
    SyncStatDataClientHelper.updateStats(1, map, new java.util.HashMap<>());
    long start1 = BattlePassV2Stats.getSeasonStartTime();
    long hide1  = BattlePassV2Stats.getBattlePassHiddenTime();
    System.out.println("[statsync] après updateStats(client) : start=" + start1 + " hide=" + hide1);

    long now = System.currentTimeMillis();
    if (!(start1 <= now && now < hide1))
      throw new AssertionError("saison NON active après application : start=" + start1 + " now=" + now + " hide=" + hide1);
    boolean hidden = com.perblue.heroes.game.logic.BattlePassV2Helper.battlePassHidden();
    System.out.println("[statsync] battlePassHidden() = " + hidden + " (attendu false)");
    if (hidden) throw new AssertionError("battlePassHidden() devrait être false (saison courante active)");

    System.out.println("[statsync] OK — le client verrait une saison ACTIVE (onglet BATTLE PASS activable)");
  }
}
