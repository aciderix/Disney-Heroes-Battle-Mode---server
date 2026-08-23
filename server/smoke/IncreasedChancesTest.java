import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.logic.DailyActivityHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composant <b>INCREASED_CHANCES</b> (chances de combat quotidiennes supplémentaires).
 *
 * <p>Prouve, via la LOGIQUE DU JEU (§3), que {@code ServerEvents.buildIncreasedChancesEvent} (composant du jeu
 * {@code IncreasedChances}) augmente le max de chances quotidiennes d'un mode : {@code DailyActivityHelper
 * .getMaxDailyUses(user, chanceType, snapshot)} = {@code BaseEventSnapshot.getChances(chanceType, base)} = base + additional.
 * (1) mode VISÉ (portDocks_use +3) → max +3 ; (2) mode NON visé (portWarehouse_use) → INCHANGÉ ; (3) round-trip de la
 * spec persistée (admin). {@code chanceType} + nombre = <b>params ADMIN</b> ({@code AdminEvents --chances-boost
 * --chance-type <TYPE> --additional N}).
 */
public final class IncreasedChancesTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[chancesboost] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9198L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    int docksBase = DailyActivityHelper.getMaxDailyUses(su.gameUser(), "portDocks_use", SpecialEventSnapshot.NONE);
    int warehouseBase = DailyActivityHelper.getMaxDailyUses(su.gameUser(), "portWarehouse_use", SpecialEventSnapshot.NONE);
    check(docksBase > 0, "base portDocks_use > 0 (" + docksBase + ")");
    System.out.println("[chancesboost] base : portDocks_use=" + docksBase + " portWarehouse_use=" + warehouseBase + " ✔");

    Map<String, Integer> mods = new LinkedHashMap<>();
    mods.put("portDocks_use", 3);
    SpecialEventInfo ev = ServerEvents.buildIncreasedChancesEvent(700_060L, mods, now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(ev));
    SpecialEventSnapshot snap = ServerEvents.snapshot();   // capturé une fois (patron sûr, cf. CHEST_DISCOUNT)

    int docksBoost = DailyActivityHelper.getMaxDailyUses(su.gameUser(), "portDocks_use", snap);
    int warehouseBoost = DailyActivityHelper.getMaxDailyUses(su.gameUser(), "portWarehouse_use", snap);
    check(docksBoost == docksBase + 3, "portDocks_use +3 (" + docksBoost + " vs " + (docksBase + 3) + ")");
    check(warehouseBoost == warehouseBase, "portWarehouse_use (non visé) INCHANGÉ (" + warehouseBoost + " vs " + warehouseBase + ")");
    System.out.println("[chancesboost] event portDocks_use +3 : portDocks_use=" + docksBoost + " (base " + docksBase
        + ") | portWarehouse_use=" + warehouseBoost + " (inchangé) ✔");

    // Round-trip de la spec persistée (config admin).
    String spec = ServerEvents.specJsonIncreasedChances(700_060L, mods, now - 1000, now + 86_400_000L);
    byte[] blob = ServerEvents.writeConfig(Collections.singletonList(spec));
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(blob);
    check(rebuilt.size() == 1, "1 event reconstruit (" + rebuilt.size() + ")");
    check(rebuilt.get(0).getID() == 700_060L, "eventID préservé (" + rebuilt.get(0).getID() + ")");
    ServerEvents.install(rebuilt);
    SpecialEventSnapshot snap2 = ServerEvents.snapshot();
    int docksBoost2 = DailyActivityHelper.getMaxDailyUses(su.gameUser(), "portDocks_use", snap2);
    check(docksBoost2 == docksBase + 3, "event reconstruit → même boost (" + docksBoost2 + ")");
    System.out.println("[chancesboost] spec round-trip → event reconstruit applique le boost (portDocks_use=" + docksBoost2 + ") ✔");

    ServerEvents.install(new ArrayList<>());
    System.out.println("[chancesboost] OK — INCREASED_CHANCES objet du jeu, chanceType + nombre = params admin. [headless]");
  }
}
