import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #63 — DONS HERO_XP + COÛT MERCENAIRE, valeurs DÉRIVÉES DU JEU (pas inventées).
 *
 * HERO_XP : la demande porte sur l'XP manquant du héros ({@code canRequestHeroXPHelp} refuse si l'XP est plein) ;
 * la part par don ({@code DONATIONS_PER_HELP_REQUEST}) est convertie en items d'XP RÉELS par
 * {@code ItemHelper.convertHeroXPToItems} (données EXP_ITEMS_LARGE_TO_SMALL / EXP_GIVEN), plafonnée par
 * {@code HERO_XP_DONATION_MAX_QTY}. MERCENAIRE : coût = formule du jeu {@code user_values.tab MERCENARY_COST
 * = min(2500+(0.5*P), 2e9)} évaluée par l'évaluateur d'expressions du jeu.
 */
public final class GuildHeroXPDonationTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "XPGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static boolean refused(Runnable r) {
    try { r.run(); return false; }
    catch (Throwable e) { if (e instanceof com.perblue.heroes.ClientErrorCodeException) return true; throw (RuntimeException) e; }
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ---- COÛT MERCENAIRE : formule du jeu, exacte pour plusieurs puissances ----
    for (long p : new long[]{0L, 1000L, 100000L, 5000000L}) {
      long expected = Math.min(2500L + p / 2, 2000000000L);
      long got = ServerUser.mercenaryCost(p);
      if (got != expected) throw new AssertionError("MERCENARY_COST(P=" + p + ") = " + got + " ≠ " + expected);
    }
    System.out.println("[guild] coût mercenaire = formule du jeu min(2500+0.5P, 2e9) : vérifié (P=0→2500, P=5M→2502500)");

    java.io.File tmp = java.io.File.createTempFile("dh-guild-xp", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser req = ServerUser.newPlayer(1L, 1);
      req.grantHero(UnitType.RALPH); req.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = req.createGuild(mk(), gid);

      // Demande HERO_XP : la validation du jeu s'applique (héros possédé, XP non plein…).
      GuildDonationRequestRow row = req.postGuildHeroXPRequest(g, UnitType.RALPH);
      if (row == null) throw new AssertionError("demande HERO_XP nulle");
      if (row.type != GuildDonationRequestType.HERO_XP) throw new AssertionError("type incorrect");
      if (row.hero == null || row.hero.type != UnitType.RALPH) throw new AssertionError("HeroSummary absent (l'UI en a besoin)");
      if (row.donation == null || row.donation.itemType == ItemType.DEFAULT)
        throw new AssertionError("le don HERO_XP doit être un ITEM d'XP réel, obtenu " + row.donation);
      int maxQty = 4;   // HERO_XP_DONATION_MAX_QTY (guild_constants)
      if (row.donation.quantity < 1 || row.donation.quantity > maxQty)
        throw new AssertionError("quantité hors plafond du jeu (1.." + maxQty + ") : " + row.donation.quantity);
      System.out.println("[guild] demande HERO_XP → don dérivé = " + row.donation.quantity + " × "
          + row.donation.itemType + " (item d'XP du jeu, ≤ HERO_XP_DONATION_MAX_QTY=" + maxQty + ")");
      store.saveGuild(g);

      java.util.Map<Long, Integer> byUser = g.donationsByUser.get(row.requestID);
      int total = row.totalRequestedDonations;

      // Refus : don à soi-même.
      if (!refused(() -> req.donateToGuildRequest(row, byUser, null)))
        throw new AssertionError("don à soi-même devrait être refusé");
      System.out.println("[guild] don à soi-même → refusé");

      // N donneurs possédant l'item d'XP → la demande se remplit ; le donneur est débité (non séquestré).
      for (int i = 0; i < total; i++) {
        ServerUser d = ServerUser.newPlayer(100L + i, 1);
        d.grantHero(UnitType.RALPH);
        d.giveItem(row.donation.itemType, (int) row.donation.quantity + 2);
        int before = d.itemAmount(row.donation.itemType);
        RewardDrop given = d.donateToGuildRequest(row, byUser, null);
        if (given == null || given.itemType != row.donation.itemType)
          throw new AssertionError("don HERO_XP incorrect");
        int after = d.itemAmount(row.donation.itemType);
        if (after != before - (int) row.donation.quantity)
          throw new AssertionError("donneur non débité (" + before + "→" + after + ")");
      }
      if (row.remainingDonations != 0) throw new AssertionError("demande HERO_XP non remplie");
      System.out.println("[guild] " + total + " dons → demande HERO_XP REMPLIE (donneurs débités)");

      // Livraison au demandeur : nbDons × la quantité du don, en items d'XP, par courrier.
      long delivered = req.deliverDonationResult(row, true);
      long expected = (long) total * row.donation.quantity;
      if (delivered != expected) throw new AssertionError("livré " + delivered + " ≠ " + expected);
      MailMessage aid = null;
      for (MailMessage mm : req.mailPersisted()) if (mm.type == MailType.GUILD_DONATION_SUCCESS) aid = mm;
      if (aid == null || aid.extra == null || aid.extra.attachments == null || aid.extra.attachments.isEmpty())
        throw new AssertionError("courrier HERO_XP absent");
      RewardDrop att = (RewardDrop) aid.extra.attachments.get(0);
      if (att.itemType != row.donation.itemType || att.quantity != expected)
        throw new AssertionError("récompense courrier HERO_XP incorrecte");
      System.out.println("[guild] demandeur reçoit " + delivered + " × " + att.itemType + " par courrier");

      // Round-trip DB.
      store.save(req);
      ServerUser rreq = store.loadIfExists(1L, 1);
      boolean hasMail = false;
      for (MailMessage mm : rreq.mailPersisted()) if (mm.type == MailType.GUILD_DONATION_SUCCESS) hasMail = true;
      if (!hasMail) throw new AssertionError("courrier HERO_XP non persisté");
      System.out.println("[guild] round-trip DB OK");

      System.out.println("GUILD HERO XP DONATION TEST OK");
    }
  }
}
