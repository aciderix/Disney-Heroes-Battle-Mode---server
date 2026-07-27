import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #55b — DON / GUILD AID : d'autres membres AIDENT une demande STAMINA. {@code doDonation} débite le
 * donateur (1 STAMINA_CONSUMABLE), décrémente la demande, suit les dons par utilisateur (cap 1/demande) ; à
 * saturation la demande est REMPLIE → le demandeur reçoit la récompense accumulée par COURRIER
 * (GUILD_DONATION_SUCCESS = nbDons × getStaminaConsumableReward() STAMINA). Refus fidèles : don à soi-même,
 * 2ᵉ don du même donateur, donateur sans consommable.
 */
public final class GuildDonateTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "DonateGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static ServerUser donor(long id, int consumables) {
    ServerUser d = ServerUser.newPlayer(id, 1);
    d.grantHero(UnitType.RALPH);
    if (consumables > 0) d.giveItem(ItemType.STAMINA_CONSUMABLE, consumables);
    return d;
  }
  static boolean refused(Runnable r) {
    try { r.run(); return false; }
    catch (Throwable e) { if (e instanceof com.perblue.heroes.ClientErrorCodeException) return true; throw (RuntimeException) e; }
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-donate", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // Demandeur (membre 1) poste une demande STAMINA (total = getStaminaHelpAmount(maxTL)).
      ServerUser req = ServerUser.newPlayer(1L, 1);
      req.grantHero(UnitType.RALPH); req.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = req.createGuild(mk(), gid);
      GuildDonationRequestRow row = req.postGuildStaminaRequest(g);
      int total = row.totalRequestedDonations;
      store.saveGuild(g); store.save(req);
      System.out.println("[guild] demande STAMINA #" + row.requestID + " : " + total + " dons requis");

      java.util.Map<Long, Integer> byUser = g.donationsByUser.get(row.requestID);

      // Refus : le demandeur ne peut pas s'aider lui-même.
      if (!refused(() -> req.donateToGuildRequest(row, byUser, null)))
        throw new AssertionError("don à soi-même devrait être refusé");
      System.out.println("[guild] don à soi-même → refusé (CANT_DONATE_TO_SELF)");

      // Refus : donateur SANS consommable.
      ServerUser broke = donor(99L, 0);
      if (!refused(() -> broke.donateToGuildRequest(row, byUser, null)))
        throw new AssertionError("donateur sans consommable devrait être refusé");
      System.out.println("[guild] donateur sans consommable → refusé (NOT_ENOUGH_TO_DONATE)");

      // N donateurs distincts aident (cap 1/demande) → la demande se remplit.
      for (int i = 0; i < total; i++) {
        ServerUser d = donor(100L + i, 2);
        int before = d.itemAmount(ItemType.STAMINA_CONSUMABLE);
        RewardDrop given = d.donateToGuildRequest(row, byUser, null);
        if (given == null || given.itemType != ItemType.STAMINA_CONSUMABLE)
          throw new AssertionError("don incorrect");
        int after = d.itemAmount(ItemType.STAMINA_CONSUMABLE);
        if (after != before - 1) throw new AssertionError("donateur non débité (" + before + "→" + after + ")");
        if (byUser.getOrDefault(100L + i, 0) != 1) throw new AssertionError("don par utilisateur non suivi");
        // 2ᵉ don du MÊME donateur → refusé (cap 1/demande).
        final ServerUser dd = d;
        if (!refused(() -> dd.donateToGuildRequest(row, byUser, null)))
          throw new AssertionError("2ᵉ don du même donateur devrait être refusé");
        System.out.println("[guild] don #" + (i + 1) + " par " + (100 + i) + " → restant "
            + row.remainingDonations + " ; 2ᵉ don refusé (cap 1)");
      }
      if (row.remainingDonations != 0) throw new AssertionError("demande non remplie");

      // REMPLIE → livraison au demandeur (comme le fait LoginServer) : STAMINA = total × 60 par courrier.
      long delivered = req.deliverDonationResult(row, true);
      int expected = total * com.perblue.heroes.game.logic.ItemHelper.getStaminaConsumableReward();
      if (delivered != expected) throw new AssertionError("récompense livrée " + delivered + " ≠ " + expected);
      java.util.List<MailMessage> box = req.mailPersisted();
      MailMessage aid = null;
      for (MailMessage mm : box) if (mm.type == MailType.GUILD_DONATION_SUCCESS) aid = mm;
      if (aid == null) throw new AssertionError("courrier GUILD_DONATION_SUCCESS absent");
      if (aid.extra == null || aid.extra.attachments == null || aid.extra.attachments.isEmpty())
        throw new AssertionError("courrier sans récompense");
      RewardDrop att = (RewardDrop) aid.extra.attachments.get(0);
      if (att.resourceType != ResourceType.STAMINA || att.quantity != expected)
        throw new AssertionError("récompense courrier incorrecte");
      System.out.println("[guild] demande REMPLIE → demandeur reçoit " + delivered
          + " STAMINA par courrier GUILD_DONATION_SUCCESS");

      // Round-trip DB : la demande remplie est retirée par LoginServer ; ici on vérifie que le courrier persiste.
      store.save(req);
      ServerUser rreq = store.loadIfExists(1L, 1);
      boolean hasMail = false;
      for (MailMessage mm : rreq.mailPersisted()) if (mm.type == MailType.GUILD_DONATION_SUCCESS) hasMail = true;
      if (!hasMail) throw new AssertionError("courrier de don non persisté");
      System.out.println("[guild] round-trip DB OK : courrier de don persisté");

      System.out.println("GUILD DONATE TEST OK");
    }
  }
}
