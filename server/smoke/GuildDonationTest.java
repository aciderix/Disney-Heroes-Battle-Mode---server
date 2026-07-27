import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #55a — DONS / GUILD AID : poster une demande d'aide STAMINA est autoritatif (valide+charge via la logique
 * du jeu, ressource {@code GUILD_DONATION_REQUEST_STAMINA} régénérée), synthétise une demande opérateur
 * (GuildDonationRequestRow) persistée dans la guilde, et l'écran GUILD AID la voit (GuildDonationRequests).
 * L'état survit au round-trip DB ; la demande est REFUSÉE quand la ressource est épuisée (anti-triche fidèle).
 */
public final class GuildDonationTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "AidGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-aid", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk(), gid);
      store.saveGuild(g); store.save(su);

      // 1) Poste une demande STAMINA (le joueur possède la ressource régénérée au cap) → succès.
      GuildDonationRequestRow row = su.postGuildStaminaRequest(g);
      if (row == null) throw new AssertionError("demande non créée");
      if (row.requestID <= 0) throw new AssertionError("requestID non assigné");
      if (row.type != GuildDonationRequestType.STAMINA) throw new AssertionError("type incorrect");
      if (row.member == null || row.member.iD != 1L) throw new AssertionError("demandeur incorrect");
      if (row.donation == null || row.donation.itemType != ItemType.STAMINA_CONSUMABLE)
        throw new AssertionError("récompense de don incorrecte (attendu STAMINA_CONSUMABLE)");
      if (row.totalRequestedDonations <= 0 || row.remainingDonations != row.totalRequestedDonations)
        throw new AssertionError("compte de dons incohérent");
      if (row.expiration <= com.perblue.heroes.util.TimeUtil.serverTimeNow())
        throw new AssertionError("expiration passée");
      if (g.donationRequestsWire.size() != 1) throw new AssertionError("demande non archivée");
      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      System.out.println("[guild] demande STAMINA #" + row.requestID + " : " + row.totalRequestedDonations
          + " dons attendus (1 STAMINA_CONSUMABLE chacun), expire dans " + ((row.expiration - now)/3600000) + "h");

      // 2) L'écran GUILD AID voit la demande.
      GuildDonationRequests view = su.buildGuildDonationRequests(g);
      if (view.requests.size() != 1) throw new AssertionError("GUILD AID ne voit pas la demande");
      if (view.guildID != gid) throw new AssertionError("guildID incorrect dans la réponse");
      System.out.println("[guild] GUILD AID rend " + view.requests.size() + " demande(s)");

      // 3) Round-trip DB : la demande persiste.
      store.saveGuild(g); store.save(su);
      ServerGuild rg = store.loadGuild(1, gid);
      if (rg.donationRequestsWire.size() != 1) throw new AssertionError("demande non persistée");
      if (rg.nextRequestID != g.nextRequestID) throw new AssertionError("nextRequestID non persisté");
      java.util.List<GuildDonationRequestRow> reload = rg.donationRequests();
      if (reload.size() != 1 || reload.get(0).type != GuildDonationRequestType.STAMINA)
        throw new AssertionError("relecture demande échouée");
      System.out.println("[guild] round-trip DB OK : demande persistée, nextRequestID=" + rg.nextRequestID);

      // 4) Anti-triche : poster jusqu'à épuisement de la ressource → REFUS fidèle (NOT_ENOUGH_...).
      int extra = 0; boolean refused = false;
      for (int i = 0; i < 50 && !refused; i++) {
        try { su.postGuildStaminaRequest(rg); extra++; }
        catch (Throwable e) {
          if (e instanceof com.perblue.heroes.ClientErrorCodeException) refused = true; else throw e;
        }
      }
      if (!refused) throw new AssertionError("la demande devrait finir par être refusée (ressource épuisée)");
      System.out.println("[guild] " + (extra + 1) + " demande(s) jusqu'à épuisement puis REFUS fidèle (anti-triche)");

      // 5) Purge des demandes expirées : une demande expirée n'est pas rendue.
      rg.donationRequestsWire.clear(); rg.donationsByUser.clear();
      GuildDonationRequestRow old = new GuildDonationRequestRow();
      old.requestID = 999L; old.type = GuildDonationRequestType.STAMINA;
      old.expiration = com.perblue.heroes.util.TimeUtil.serverTimeNow() - 1000L;
      old.remainingDonations = 5; old.totalRequestedDonations = 5;
      com.perblue.grunt.translate.util.GruntOutputStream go = new com.perblue.grunt.translate.util.GruntOutputStream();
      old.writeAll(go); rg.addDonationRequestWire(go.getBytes());
      if (!rg.donationRequests().isEmpty()) throw new AssertionError("demande expirée non purgée");
      System.out.println("[guild] purge des demandes expirées OK");

      System.out.println("GUILD DONATION TEST OK");
    }
  }
}
