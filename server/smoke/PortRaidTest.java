import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import com.perblue.heroes.game.data.ModeDifficulty;
import dhserver.*;
import java.util.*;

/**
 * PORT (#72) incrément 2 — RAID d'un mode « difficulty » ({@code RaidDifficultyMode} → {@code recordRaidOutcome}).
 * Le serveur ré-exécute la logique du jeu (§3) : {@code useRaidTickets} (anti-triche mode ouvert/cooldown/quota + gate
 * 3★ {@code isAutoAttackAvailable} + débit {@code RAID_TICKET}×raidCount) puis {@code recordRaidOutcome} (crédit du
 * butin agrégé + XP + compteurs + cooldown). Persistance round-trip wire + DB.
 *
 * <p>Setup fidèle : on rend un étage RAIDABLE via {@code setDifficultyModeStars(mode, diff, 3)} (comme un clear 3★
 * préalable), on dote des {@code RAID_TICKET}, et on choisit le mode PORT OUVERT le jour serveur (union DOCKS/WAREHOUSE
 * = tous les jours). Cas anti-triche : sans ticket → {@code NOT_ENOUGH_RAID_TICKETS} ; sur cooldown → {@code GAME_MODE_COOLDOWN}.
 */
public final class PortRaidTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[portraid] " + m); }

  static RaidDifficultyMode buildRaid(GameMode mode, int diffIdx, int raids, long gold) {
    RaidDifficultyMode rd = new RaidDifficultyMode();
    rd.gameMode = mode;
    rd.modeDifficulty = diffIdx;
    rd.raidTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    rd.outcomes = new ArrayList<>();
    for (int i = 0; i < raids; i++) {
      RaidOutcome ro = new RaidOutcome();
      ro.expEarned = 10;
      ro.loot = new ArrayList<>();
      RewardDrop d = new RewardDrop();
      d.resourceType = ResourceType.GOLD; d.itemType = ItemType.DEFAULT; d.quantity = gold;
      ro.loot.add(d);
      rd.outcomes.add(ro);
    }
    return rd;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8710L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    // Le raid AUTO est une fonctionnalité VIP (gate réel du jeu §7) : getRaidFeature(PORT_*)=RAID_PORT, débloqué au VIP 4.
    su.bootData().userInfo.basicInfo.vIPLevel = 4;
    var NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;

    // Choisir le mode PORT ouvert AUJOURD'HUI (indépendant du jour serveur).
    GameMode mode = null;
    for (GameMode c : new GameMode[]{GameMode.PORT_DOCKS, GameMode.PORT_WAREHOUSE}) {
      if (DifficultyModeHelper.isOpen(c, su.gameUser(), NONE)) { mode = c; break; }
    }
    check(mode != null, "au moins un mode PORT ouvert le jour serveur");
    ModeDifficulty diff = ModeDifficulty.ONE;
    int diffIdx = diff.getIndex();
    System.out.println("[portraid] mode ouvert = " + mode + " (diff index " + diffIdx + ")");

    // Setup : étage 3★ (raidable) + 5 RAID_TICKET (dotés pour vérifier qu'ils ne sont PAS consommés — cf. ci-dessous).
    su.gameIndividual().setDifficultyModeStars(mode, diff, 3);
    check(su.gameIndividual().isAutoAttackAvailable(mode, diff), "étage rendu raidable (3★)");
    su.gameUser().addItem(ItemType.RAID_TICKET, 5, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
    check(su.gameUser().getItemAmount(ItemType.RAID_TICKET) == 5, "5 RAID_TICKET dotés");
    // FAIT (§4) : le raid PORT est VIP-gaté à RAID_PORT (VIP 4), or RAID_WITHOUT_TICKETS s'active dès VIP 3 → tout
    // raid PORT LÉGITIME (VIP 4+) est SANS ticket (useRaidTickets ne consomme rien). Le cas NOT_ENOUGH_RAID_TICKETS
    // est donc inatteignable pour PORT. On vérifie donc que les tickets restent INCHANGÉS.

    CooldownType cd = DifficultyModeHelper.getCooldownType(mode);
    check(su.gameUser().getCooldownEnd(cd) <= com.perblue.heroes.util.TimeUtil.serverTimeNow(), "pas de cooldown au départ");
    long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

    // --- RAID ×3 (3 outcomes → raidCount=3, 5000 GOLD chacun = 15000) ---
    su.recordRaidDifficultyMode(buildRaid(mode, diffIdx, 3, 5000));

    long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
    check(goldAfter == goldBefore + 15000, "butin agrégé crédité (3×5000) : " + goldBefore + "→" + goldAfter);
    int tickAfter = su.gameUser().getItemAmount(ItemType.RAID_TICKET);
    check(tickAfter == 5, "RAID_TICKET INCHANGÉS (VIP RAID_WITHOUT_TICKETS) : reste " + tickAfter);
    long cdEnd = su.gameUser().getCooldownEnd(cd);
    check(cdEnd > com.perblue.heroes.util.TimeUtil.serverTimeNow(), "cooldown de raid posé (" + cd + ")");
    System.out.println("[portraid] RAID ×3 : +15000 GOLD, tickets inchangés (VIP free-raid), cooldown " + cd + " posé ✔");

    // --- Anti-triche : re-raid pendant le cooldown → GAME_MODE_COOLDOWN, état inchangé ---
    boolean refused = false;
    try { su.recordRaidDifficultyMode(buildRaid(mode, diffIdx, 1, 5000)); }
    catch (com.perblue.heroes.ClientErrorCodeException e) {
      refused = String.valueOf(e.getMessage()).contains("GAME_MODE_COOLDOWN")
             || String.valueOf(e).contains("GAME_MODE_COOLDOWN");
    }
    check(refused, "re-raid pendant cooldown refusé (GAME_MODE_COOLDOWN)");
    check(su.gameUser().getResource(ResourceType.GOLD) == goldAfter, "GOLD inchangé après refus");
    check(su.gameUser().getItemAmount(ItemType.RAID_TICKET) == 5, "tickets inchangés après refus");
    System.out.println("[portraid] anti-triche cooldown ✔");

    // --- Persistance round-trip wire + DB : gold + tickets + cooldown ---
    ServerUser rl = ServerUser.fromWire(8710L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getResource(ResourceType.GOLD) == goldAfter, "GOLD survit au round-trip wire");
    check(rl.gameUser().getItemAmount(ItemType.RAID_TICKET) == 5, "tickets survivent au round-trip wire");
    check(rl.gameUser().getCooldownEnd(cd) == cdEnd, "cooldown survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("portraid", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8710L, 1);
    check(fromDb != null && fromDb.gameUser().getResource(ResourceType.GOLD) == goldAfter
        && fromDb.gameUser().getItemAmount(ItemType.RAID_TICKET) == 5
        && fromDb.gameUser().getCooldownEnd(cd) == cdEnd, "GOLD + tickets + cooldown persistés en DB");
    store.close(); db.delete();
    System.out.println("[portraid] persistance (wire + DB) : GOLD + tickets + cooldown ✔");

    System.out.println("[portraid] OK — raid mode difficulty (useRaidTickets + recordRaidOutcome) + anti-triche + persistance (headless).");
  }
}
