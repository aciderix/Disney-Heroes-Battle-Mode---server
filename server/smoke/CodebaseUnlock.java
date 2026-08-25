import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.data.codebase.CodebaseStats;
import com.perblue.heroes.game.data.campaign.CampaignStats;
import dhserver.*;

/**
 * OUTIL DEV — débloque le mode CODEBASE sur le compte persisté (pour la vérif EN JEU §8) : team level, chapitre requis
 * (NORMAL {@code REQUIRED_CAMPAIGN_CHAPTER}=41) terminé (= {@code Unlockable.CODEBASE}), et un roster avec ≥1 héros JAUNE
 * (exigence {@code CODEBASE_REQUIRES_YELLOW_HERO}). Usage : CodebaseUnlock [db] [userID].
 */
public final class CodebaseUnlock {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    try (UserStore s = new UserStore(db)) {
      ServerUser su = s.loadOrCreate(uid, 1);
      su.bootData().userInfo.basicInfo.teamLevel = 300;

      int chap = CodebaseStats.getRequiredCampaignChapter();
      int maxIdx = CampaignStats.getMaxLevelIndex(CampaignType.NORMAL, chap);
      su.grantCampaignLevel(CampaignType.NORMAL, chap, maxIdx, 3);

      // Roster combat : 5 héros JAUNE (au moins 1 requis ; 5 = équipe pleine pour le chooser).
      UnitType[] team = { UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MR_INCREDIBLE, UnitType.YAX };
      int granted = 0;
      for (UnitType t : team) {
        try { su.grantHero(t, Rarity.YELLOW, 200, 6); granted++; } catch (Throwable ignore) {}
      }
      s.save(su);

      ServerUser rl = s.loadOrCreate(uid, 1);
      System.out.println("[codebase-unlock] user=" + uid + " TL=" + rl.bootData().userInfo.basicInfo.teamLevel
          + " chapitre " + chap + " terminé (level " + maxIdx + ") + " + granted + " héros JAUNE → [persisté]");
    }
  }
}
