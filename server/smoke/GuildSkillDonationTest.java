import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #63 — DONS SKILL_LEVEL. Un don de skill est SÉQUESTRÉ (isDonationEscrowed(SKILL_LEVEL)=true) : le
 * donneur remet 1 SKILL_POINT_CONSUMABLE qui va au demandeur. Prouve : (1) postGuildSkillRequest exécute la
 * VALIDATION du jeu (héros sans skill → refus fidèle) ; (2) doDonation en mode escrow retire l'item du donneur
 * et décrémente ; (3) à saturation, le demandeur reçoit nbDons × SKILL_POINT_CONSUMABLE par courrier.
 */
public final class GuildSkillDonationTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "SkillGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static boolean refused(Runnable r) {
    try { r.run(); return false; }
    catch (Throwable e) { if (e instanceof com.perblue.heroes.ClientErrorCodeException) return true; throw (RuntimeException) e; }
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-skill", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser req = ServerUser.newPlayer(1L, 1);
      req.grantHero(UnitType.RALPH); req.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = req.createGuild(mk(), gid);

      // (1) VALIDATION du jeu : un héros WHITE niveau 1 n'a pas de skill améliorable → refus fidèle
      // (canRequestSkillLevelHelp lève DONT_HAVE_SKILL / SKILL_AT_HERO_LEVEL). Prouve le câblage requestHelp.
      if (!refused(() -> req.postGuildSkillRequest(g, UnitType.RALPH, SkillSlot.WHITE)))
        throw new AssertionError("demande SKILL sur héros sans skill améliorable devrait être refusée");
      System.out.println("[guild] demande SKILL (héros sans skill) → refusée (validation du jeu câblée)");

      // (2)+(3) Escrow + livraison : on construit la demande (comme le fait l'opérateur/postGuildSkillRequest :
      // don = 1 SKILL_POINT_CONSUMABLE, total = DONATIONS_PER_HELP_REQUEST), et on exerce le flux donate→reward.
      int total = 3;
      GuildDonationRequestRow row = new GuildDonationRequestRow();
      row.requestID = g.nextRequestID++;
      row.member = req.basicInfo();
      row.type = GuildDonationRequestType.SKILL_LEVEL;
      row.skill = SkillSlot.WHITE;
      row.donation = com.perblue.heroes.game.logic.RewardHelper.createDrop(ItemType.SKILL_POINT_CONSUMABLE, 1L);
      row.totalRequestedDonations = total;
      row.remainingDonations = total;
      row.expiration = com.perblue.heroes.util.TimeUtil.serverTimeNow()
          + com.perblue.heroes.game.data.guild.GuildStats.getHelpRequestDuration();
      java.util.Map<Long, Integer> byUser = new java.util.LinkedHashMap<>();

      for (int i = 0; i < total; i++) {
        ServerUser d = ServerUser.newPlayer(100L + i, 1);
        d.grantHero(UnitType.RALPH);
        d.giveItem(ItemType.SKILL_POINT_CONSUMABLE, 2);
        int before = d.itemAmount(ItemType.SKILL_POINT_CONSUMABLE);
        RewardDrop given = d.donateToGuildRequest(row, byUser, null);
        if (given == null || given.itemType != ItemType.SKILL_POINT_CONSUMABLE)
          throw new AssertionError("don SKILL incorrect");
        int after = d.itemAmount(ItemType.SKILL_POINT_CONSUMABLE);
        if (after != before - 1) throw new AssertionError("donneur non débité (escrow) " + before + "→" + after);
        final ServerUser dd = d;
        if (!refused(() -> dd.donateToGuildRequest(row, byUser, null)))
          throw new AssertionError("2ᵉ don du même donneur devrait être refusé (cap 1)");
        System.out.println("[guild] don skill #" + (i + 1) + " → restant " + row.remainingDonations + " (cap 1 respecté)");
      }
      if (row.remainingDonations != 0) throw new AssertionError("demande SKILL non remplie");

      // REMPLIE → le demandeur reçoit total × SKILL_POINT_CONSUMABLE par courrier.
      long delivered = req.deliverDonationResult(row, true);
      if (delivered != total) throw new AssertionError("récompense skill livrée " + delivered + " ≠ " + total);
      MailMessage aid = null;
      for (MailMessage mm : req.mailPersisted()) if (mm.type == MailType.GUILD_DONATION_SUCCESS) aid = mm;
      if (aid == null || aid.extra == null || aid.extra.attachments == null || aid.extra.attachments.isEmpty())
        throw new AssertionError("courrier de don SKILL absent/sans récompense");
      RewardDrop att = (RewardDrop) aid.extra.attachments.get(0);
      if (att.itemType != ItemType.SKILL_POINT_CONSUMABLE || att.quantity != total)
        throw new AssertionError("récompense courrier SKILL incorrecte (" + att.itemType + " ×" + att.quantity + ")");
      System.out.println("[guild] demande SKILL REMPLIE → demandeur reçoit " + delivered
          + " SKILL_POINT_CONSUMABLE par courrier");

      // Round-trip DB : le courrier persiste.
      store.save(req);
      ServerUser rreq = store.loadIfExists(1L, 1);
      boolean hasMail = false;
      for (MailMessage mm : rreq.mailPersisted()) if (mm.type == MailType.GUILD_DONATION_SUCCESS) hasMail = true;
      if (!hasMail) throw new AssertionError("courrier de don SKILL non persisté");
      System.out.println("[guild] round-trip DB OK : courrier de don SKILL persisté");

      System.out.println("GUILD SKILL DONATION TEST OK");
    }
  }
}
