import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * Outil DEV (hors régression) — sème dans la guilde du joueur 1 un 2ᵉ membre « Sidekick » (userID=2) QUI POSTE
 * une demande d'aide STAMINA, et donne au joueur 1 un STAMINA_CONSUMABLE, pour tester le DON en jeu (le joueur 1,
 * connecté, aide la demande de Sidekick — on ne peut pas s'aider soi-même). Usage : GuildAidSeed [db].
 */
public final class GuildAidSeed {
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore store = new UserStore(db)) {
      ServerUser p1 = store.loadOrCreate(1L, 1);
      long gid = p1.currentGuildID();
      if (gid == 0) { System.out.println("[aidseed] joueur 1 sans guilde — abandon"); return; }
      ServerGuild g = store.loadGuild(1, gid);
      if (g == null) { System.out.println("[aidseed] guilde introuvable — abandon"); return; }

      // 2ᵉ membre Sidekick (userID=2), dans la guilde.
      ServerUser p2 = store.loadOrCreate(2L, 1);
      p2.basicInfo().name = "Sidekick";
      p2.joinGuildAs(gid, GuildRole.MEMBER);
      if (!g.memberIDs.contains(2L)) g.memberIDs.add(2L);

      // Sidekick poste une demande STAMINA.
      GuildDonationRequestRow row = p2.postGuildStaminaRequest(g);

      // Le joueur 1 (donateur) reçoit de quoi donner.
      p1.giveItem(ItemType.STAMINA_CONSUMABLE, 3);

      store.saveGuild(g); store.save(p2); store.save(p1);
      System.out.println("[aidseed] Sidekick(2) dans la guilde " + gid + " ; demande STAMINA #" + row.requestID
          + " (" + row.totalRequestedDonations + " dons) ; joueur 1 +3 STAMINA_CONSUMABLE [persisté].");
      System.out.println("[aidseed]   → en jeu : nav GUILD_AID (voir la demande de Sidekick), puis"
          + " clickfile: guilddonate " + row.requestID + " 2");
    }
  }
}
