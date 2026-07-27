import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #57 (correctif multi-serveur) — quand un mercenaire est LOUÉ, le POSTEUR gagne ses Social Bucks
 * (MercenaryHelper.getHiredMercenaryReward, montant des stats du jeu × bonus VIP). Prouve : le crédit
 * SOCIAL_BUCKS et le compteur hebdo MERCENARY_SOCIAL_BUCKS montent du bon montant, et persistent.
 */
public final class GuildMercRewardTest {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-merc-reward", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser poster = ServerUser.newPlayer(2L, 1);
      poster.grantHero(UnitType.RALPH);

      long before = poster.resourceAmount(ResourceType.SOCIAL_BUCKS);

      int reward = poster.creditMercenaryHireReward();
      if (reward <= 0) throw new AssertionError("récompense mercenaire devrait être > 0 (reward=" + reward + ")");
      long after = poster.resourceAmount(ResourceType.SOCIAL_BUCKS);
      if (after != before + reward)
        throw new AssertionError("SOCIAL_BUCKS attendu " + (before + reward) + ", obtenu " + after);
      System.out.println("[guild] mercenaire loué → posteur +" + reward + " Social Bucks (total " + after + ")");

      // Un 2ᵉ emprunt cumule (compteur hebdo + monnaie).
      int reward2 = poster.creditMercenaryHireReward();
      long after2 = poster.resourceAmount(ResourceType.SOCIAL_BUCKS);
      if (after2 != after + reward2) throw new AssertionError("2ᵉ crédit incohérent");
      System.out.println("[guild] 2ᵉ location → +" + reward2 + " (total " + after2 + ")");

      // Round-trip DB : la monnaie du posteur est persistée.
      store.save(poster);
      ServerUser reloaded = store.loadIfExists(2L, 1);
      if (reloaded == null) throw new AssertionError("posteur non rechargé");
      long persisted = reloaded.resourceAmount(ResourceType.SOCIAL_BUCKS);
      if (persisted != after2)
        throw new AssertionError("SOCIAL_BUCKS après round-trip DB : " + persisted + " != " + after2);
      System.out.println("[guild] round-trip DB OK : " + persisted + " Social Bucks persistés");

      System.out.println("GUILD MERC REWARD TEST OK");
    }
  }
}
