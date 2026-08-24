import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.logic.ChestHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.*;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composant <b>EXTRA_CHEST</b> ({@code ExtraChest}) : coffre BONUS temporaire sur l'écran CRATES,
 * acheté avec une monnaie, avec sa PROPRE table de drops.
 *
 * <p>Via la LOGIQUE DU JEU (§3) : {@code ServerEvents.buildExtraChestEvent} bâtit un {@code SpecialEventInfo} EXTRA_CHEST
 * (schéma <b>Format B</b> tout-inline relevé au bytecode {@code EventChestData.<init>} — pas de {@code preset}) dont le
 * {@code config} est une TABLE DE DROPS au format du jeu ({@code chests.tab}). Le coffre s'achète comme un
 * {@code ChestType.EVENT} : coût/monnaie/limites via la logique du jeu sur le snapshot opérateur, roll serveur-autoritatif
 * de la table. (1) le snapshot expose le coffre ({@code getSingleEventChest}) avec le bon coût/monnaie ; (2) la table
 * roule du VRAI loot ; (3) OUVERTURE end-to-end via {@code openChest(EVENT)} → loot crédité + monnaie débitée du coût ;
 * (4) round-trip de la spec. Coût/monnaie/buyX/limites/loot = <b>params ADMIN</b>.
 */
public final class ExtraChestTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[extrachest] " + m); }

  static com.perblue.heroes.game.objects.User bind(ServerUser su, BootData bd) {
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "ec");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "ec");
    ServerContext.bind(u, iu); return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    BootData bd = su.bootData();
    bd.userInfo.basicInfo.teamLevel = 60;
    bd.userInfo.diamonds = 100_000;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // EXTRA_CHEST : coût 100 DIAMONDS, freeBuys 0, loot = pool pondéré (GOLD x100000 poids 3 / GEAR_TOKENS x50 poids 1).
    // NB : le loot NE contient PAS de DIAMONDS (la monnaie d'achat) → le débit net = exactement le coût (mesure propre).
    List<ServerEvents.ChestDrop> drops = Arrays.asList(
        new ServerEvents.ChestDrop("GOLD", "100000", 3),
        new ServerEvents.ChestDrop("GEAR_TOKENS", "50", 1));
    SpecialEventInfo ev = ServerEvents.buildExtraChestEvent(950_010L, 100, ResourceType.DIAMONDS,
        10, 50, 5, 0, true, "Supply Crate", "Contains resources!", drops, 1, now - 1000, now + 86_400_000L);
    // Enregistrer via OPERATOR_EVENTS (chemin PERSISTANT, comme AdminEvents) : chaque ServerContext.bind (dont celui
    // INTERNE à openChest) réinstalle depuis OPERATOR_EVENTS — un simple install() serait effacé au prochain bind.
    ServerEvents.setOperatorEvents(Collections.singletonList(ev));
    ServerEvents.installBootDefaults();
    SpecialEventSnapshot snap = ServerEvents.snapshot();

    // (1) le snapshot expose le coffre event avec le bon coût/monnaie (logique du jeu getBasePurchaseCost/getPurchaseCurrency).
    com.perblue.common.specialevent.components.pieces.EventChestData ecd = snap.getSingleEventChest();
    check(ecd != null, "getSingleEventChest non null après install");
    var u = bind(su, bd);
    int cost = ChestHelper.getPurchaseCost(u, ChestType.EVENT, 1, snap);
    ResourceType cur = ChestHelper.getPurchaseCurrency(ChestType.EVENT, snap);
    check(cost == 100, "coût EVENT = 100 (event data) (" + cost + ")");
    check(cur == ResourceType.DIAMONDS, "monnaie EVENT = DIAMONDS (" + cur + ")");
    System.out.println("[extrachest] snapshot expose le coffre : coût=" + cost + " " + cur + " ✔");

    // (2) la table de drops inline roule du VRAI loot (données du jeu, pas une liste inventée).
    java.util.List<?> rolled = ((com.perblue.heroes.game.data.chest.EventChestStats) ecd.getStats()).rollNodeSimpleDrops("ROOT", 1);
    check(!rolled.isEmpty(), "la table ROOT roule au moins 1 drop (" + rolled.size() + ")");
    System.out.println("[extrachest] roll ROOT x1 = " + rolled.size() + " drop(s): " + rolled + " ✔");

    // (3) OUVERTURE end-to-end via openChest(EVENT) → loot crédité + DIAMONDS débités du coût.
    long diamBefore = bd.userInfo.diamonds;
    BuyChests buy = new BuyChests();
    buy.chestType = ChestType.EVENT; buy.count = 1; buy.cost = cost;
    LootResults r = su.openChest(buy);
    long diamAfter = bd.userInfo.diamonds;
    check(r.lootDrops != null && !r.lootDrops.isEmpty(), "loot crédité (openChest EVENT) (" + (r.lootDrops == null ? "null" : r.lootDrops.size()) + ")");
    check(!r.wasFree, "achat PAYANT (freeBuys=0) → wasFree=false (" + r.wasFree + ")");
    check(diamBefore - diamAfter == cost, "débit = coût (" + (diamBefore - diamAfter) + " vs " + cost + ")");
    System.out.println("[extrachest] openChest EVENT : " + r.lootDrops.size() + " loot | DIAMONDS " + diamBefore + "→" + diamAfter
        + " (débit=" + (diamBefore - diamAfter) + ") ✔");

    // (4) round-trip de la spec persistée (config admin).
    String spec = ServerEvents.specJsonExtraChest(950_010L, 100, ResourceType.DIAMONDS, 10, 50, 5, 0, true,
        "Supply Crate", "Contains resources!", drops, 1, now - 1000, now + 86_400_000L);
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(ServerEvents.writeConfig(Collections.singletonList(spec)));
    check(rebuilt.size() == 1 && rebuilt.get(0).getID() == 950_010L, "spec EXTRA_CHEST round-trip (" + rebuilt.size() + ")");
    ServerEvents.install(rebuilt);
    SpecialEventSnapshot snap2 = ServerEvents.snapshot();
    check(snap2.getSingleEventChest() != null, "event reconstruit → coffre exposé");
    check(ChestHelper.getPurchaseCost(bind(su, bd), ChestType.EVENT, 1, snap2) == 100, "event reconstruit → même coût");
    System.out.println("[extrachest] spec round-trip → coffre reconstruit (coût 100 DIAMONDS) ✔");

    ServerEvents.install(new ArrayList<>());
    System.out.println("[extrachest] OK — EXTRA_CHEST objet+table du jeu, coût/monnaie/loot = params admin, chemin openChest(EVENT) réel. [headless]");
  }
}
