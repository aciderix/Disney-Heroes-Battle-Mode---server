import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.logic.ChestHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.ChestType;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composant <b>CHEST_DISCOUNT</b> (remise sur le coût d'ouverture des coffres).
 *
 * <p>Prouve, via la LOGIQUE DU JEU (§3), que {@code ServerEvents.buildChestDiscountEvent} (composant du jeu
 * {@code ChestDiscount}) réduit le coût du chemin RÉEL d'{@code openChest} : {@code ChestHelper.getPurchaseCost(user,
 * type, count, snapshot)} = {@code getChestPrice(type, base)} avec le multiplicateur de {@code ChestDiscountSnapshot}.
 * (1) coffre VISÉ (GOLD, −50 %) → coût REMISÉ ; (2) coffre NON visé (SILVER) → coût INCHANGÉ (filtre respecté) ;
 * (3) sans event → coût = base (défaut sûr) ; (4) round-trip de la spec persistée (admin) → 1 event reconstruit.
 * Le pourcentage + les coffres = <b>paramètres ADMIN</b> ({@code AdminEvents --chest-discount --chest X --percent N}).
 */
public final class ChestDiscountTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[chestdiscount] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9931L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    // Base (sans event) — via le chemin RÉEL getPurchaseCost.
    int goldBase   = ChestHelper.getPurchaseCost(su.gameUser(), ChestType.GOLD,   1, SpecialEventSnapshot.NONE);
    int silverBase = ChestHelper.getPurchaseCost(su.gameUser(), ChestType.SILVER, 1, SpecialEventSnapshot.NONE);
    check(goldBase > 0, "coût de base GOLD > 0 (" + goldBase + ")");
    System.out.println("[chestdiscount] base : GOLD=" + goldBase + " SILVER=" + silverBase + " ✔");

    // Event CHEST_DISCOUNT : GOLD −50 % (SILVER non visé).
    SpecialEventInfo ev = ServerEvents.buildChestDiscountEvent(
        700_050L, Collections.singletonList(ChestType.GOLD), 50, now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(ev));
    SpecialEventSnapshot snap = ServerEvents.snapshot();

    int goldDisc   = ChestHelper.getPurchaseCost(su.gameUser(), ChestType.GOLD,   1, snap);
    int silverDisc = ChestHelper.getPurchaseCost(su.gameUser(), ChestType.SILVER, 1, snap);
    check(goldDisc < goldBase, "GOLD remisé < base (" + goldDisc + " vs " + goldBase + ")");
    check(goldDisc == goldBase / 2, "GOLD remisé = base/2 à −50 % (" + goldDisc + " vs " + (goldBase / 2) + ")");
    check(silverDisc == silverBase, "SILVER (non visé) INCHANGÉ (" + silverDisc + " vs " + silverBase + ")");
    System.out.println("[chestdiscount] event GOLD −50 % : GOLD=" + goldDisc + " (base " + goldBase
        + ") | SILVER=" + silverDisc + " (inchangé) ✔");

    // Round-trip de la spec persistée (config admin).
    String spec = ServerEvents.specJsonChestDiscount(700_050L, Collections.singletonList(ChestType.GOLD), 50, now - 1000, now + 86_400_000L);
    byte[] blob = ServerEvents.writeConfig(Collections.singletonList(spec));
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(blob);
    check(rebuilt.size() == 1, "1 event reconstruit depuis la config (" + rebuilt.size() + ")");
    check(rebuilt.get(0).getID() == 700_050L, "eventID préservé (" + rebuilt.get(0).getID() + ")");
    // L'event reconstruit applique la MÊME remise. NB : on CAPTURE le snapshot une fois (comme openChest) — appeler
    // ServerEvents.snapshot() à répétition n'est pas idempotent (getChestPrice marque dirtyType → un snapshot REFRAÎCHI
    // ensuite perd la remise). openChest capture chestSnap une fois et le réutilise (validation + débit) → correct.
    ServerEvents.install(rebuilt);
    SpecialEventSnapshot snap2 = ServerEvents.snapshot();
    int goldDisc2 = ChestHelper.getPurchaseCost(su.gameUser(), ChestType.GOLD, 1, snap2);
    check(goldDisc2 == goldBase / 2, "event reconstruit → même remise (" + goldDisc2 + ")");
    System.out.println("[chestdiscount] spec round-trip → event reconstruit applique la remise (GOLD=" + goldDisc2 + ") ✔");

    // Nettoyage (évite d'influencer d'autres tests de la même JVM).
    ServerEvents.install(new ArrayList<>());
    System.out.println("[chestdiscount] OK — CHEST_DISCOUNT data/objet du jeu, filtre + % = params admin, chemin openChest réel. [headless]");
  }
}
