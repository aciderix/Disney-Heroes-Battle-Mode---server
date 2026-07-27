import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #58/#66 — CADEAUX / GUILD CRATE. Génération OPÉRATEUR (grantGuildGift) → persisté dans la guilde (v5).
 * Réclamation autoritative : chaque membre reçoit les cadeaux plus récents que sa marque (RewardHelper.giveRewards),
 * marque avancée (anti-double-claim). Prouve : build (offreurs+récompenses), claim crédite, 2ᵉ claim = rien,
 * round-trip DB (cadeaux + marques persistent ; un NOUVEAU membre peut encore réclamer).
 */
public final class GuildGiftTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "GiftGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-gift", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser founder = ServerUser.newPlayer(1L, 1);
      founder.grantHero(UnitType.RALPH); founder.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = founder.createGuild(mk(), gid);

      // GÉNÈRE un cadeau (opérateur) : 500 GOLD + 3 STAMINA_CONSUMABLE.
      java.util.List<RewardDrop> rewards = new java.util.ArrayList<>();
      rewards.add(com.perblue.heroes.game.logic.RewardHelper.createDrop(ResourceType.GOLD, 500L));
      rewards.add(com.perblue.heroes.game.logic.RewardHelper.createDrop(ItemType.STAMINA_CONSUMABLE, 3L));
      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      founder.grantGuildGift(g, rewards, now);
      store.saveGuild(g);
      System.out.println("[guild] cadeau généré (offreur=founder) : 500 GOLD + 3 STAMINA_CONSUMABLE");

      // L'écran GUILD CRATE (build) montre 1 offreur + 2 récompenses.
      GuildGiftRewards view = founder.buildGuildGiftRewards(g);
      if (view.gifters.size() != 1 || view.rewards.size() != 2)
        throw new AssertionError("build cadeaux : " + view.gifters.size() + " offreur(s), " + view.rewards.size() + " récompense(s)");
      System.out.println("[guild] GUILD CRATE affiche " + view.gifters.size() + " offreur(s), " + view.rewards.size() + " récompense(s)");

      // Un membre (id=2) réclame → crédité.
      ServerUser m2 = ServerUser.newPlayer(2L, 1);
      long goldBefore = m2.resourceAmount(ResourceType.GOLD);
      int stamBefore = m2.itemAmount(ItemType.STAMINA_CONSUMABLE);
      java.util.List<RewardDrop> got = m2.claimGuildGifts(g);
      if (got.size() != 2) throw new AssertionError("réclamation devrait donner 2 récompenses, obtenu " + got.size());
      long goldAfter = m2.resourceAmount(ResourceType.GOLD);
      int stamAfter = m2.itemAmount(ItemType.STAMINA_CONSUMABLE);
      if (goldAfter != goldBefore + 500) throw new AssertionError("GOLD " + goldBefore + "→" + goldAfter + " (attendu +500)");
      if (stamAfter != stamBefore + 3) throw new AssertionError("STAMINA_CONSUMABLE " + stamBefore + "→" + stamAfter + " (attendu +3)");
      System.out.println("[guild] membre 2 réclame → +500 GOLD, +3 STAMINA_CONSUMABLE crédités");

      // 2ᵉ réclamation → rien (marque avancée).
      if (!m2.claimGuildGifts(g).isEmpty()) throw new AssertionError("2ᵉ réclamation devrait être vide");
      System.out.println("[guild] 2ᵉ réclamation du même membre → rien (anti-double-claim)");

      // Round-trip DB : cadeaux + marques persistent. Reload : membre 2 ne peut plus réclamer, membre 3 oui.
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, gid);
      if (rg.giftGifterWire.size() != 1) throw new AssertionError("cadeau non persisté");
      if (!m2.claimGuildGifts(rg).isEmpty()) throw new AssertionError("marque de réclamation non persistée");
      ServerUser m3 = ServerUser.newPlayer(3L, 1);
      if (m3.claimGuildGifts(rg).size() != 2) throw new AssertionError("nouveau membre devrait pouvoir réclamer");
      System.out.println("[guild] round-trip DB OK : cadeau + marques persistés (membre 3 réclame, membre 2 non)");

      System.out.println("GUILD GIFT TEST OK");
    }
  }
}
