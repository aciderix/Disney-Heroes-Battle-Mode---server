import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.game.specialevent.MultiplierType;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composants <b>MISC_BONUS</b> / <b>MISC_DISCOUNT</b> (multiplicateurs « divers » : ALCHEMY, STAMINA…).
 *
 * <p>Via la LOGIQUE DU JEU (§3), sur le chemin RÉEL de l'ALCHIMIE ({@code UserHelper.buyGold} lit {@code snapshot.getAlchemyPrice}
 * / {@code getAlchemyAmount}) :
 * (1) MISC_DISCOUNT(DISCOUNT_ALCHEMY, −50 %) → {@code getAlchemyPrice(100)} = 50 (achat d'or moins cher) ;
 * (2) MISC_BONUS(BONUS_ALCHEMY, +100 %) → {@code getAlchemyAmount(1000)} = 2000 (plus d'or par achat) ;
 * (3) round-trip des specs. Types + valeur = <b>params ADMIN</b> ({@code AdminEvents --misc-discount/--misc-bonus --mult <TYPE> --misc-value N}).
 */
public final class MiscMultipliersTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[misc] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9295L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    SpecialEventSnapshot none = SpecialEventSnapshot.NONE;
    check(none.getAlchemyPrice(100) == 100 && none.getAlchemyAmount(1000) == 1000, "base ALCHEMY sans event (prix 100, montant 1000)");
    System.out.println("[misc] base : getAlchemyPrice(100)=100 getAlchemyAmount(1000)=1000 ✔");

    // MISC_DISCOUNT DISCOUNT_ALCHEMY −50 %
    SpecialEventInfo evD = ServerEvents.buildMiscDiscountEvent(700_080L, Collections.singletonList(MultiplierType.DISCOUNT_ALCHEMY), 50, now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(evD));
    SpecialEventSnapshot snapD = ServerEvents.snapshot();
    check(snapD.getAlchemyPrice(100) == 50, "DISCOUNT_ALCHEMY −50 % → getAlchemyPrice(100)=50 (" + snapD.getAlchemyPrice(100) + ")");
    System.out.println("[misc] MISC_DISCOUNT(DISCOUNT_ALCHEMY 50%) : getAlchemyPrice(100)=" + snapD.getAlchemyPrice(100) + " ✔");

    // MISC_BONUS BONUS_ALCHEMY +100 %
    SpecialEventInfo evB = ServerEvents.buildMiscBonusEvent(700_081L, Collections.singletonList(MultiplierType.BONUS_ALCHEMY), 100, now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(evB));
    SpecialEventSnapshot snapB = ServerEvents.snapshot();
    check(snapB.getAlchemyAmount(1000) == 2000, "BONUS_ALCHEMY +100 % → getAlchemyAmount(1000)=2000 (" + snapB.getAlchemyAmount(1000) + ")");
    System.out.println("[misc] MISC_BONUS(BONUS_ALCHEMY 100) : getAlchemyAmount(1000)=" + snapB.getAlchemyAmount(1000) + " ✔");

    // Round-trip des specs.
    String specD = ServerEvents.specJsonMisc("MISC_DISCOUNT", 700_080L, Collections.singletonList(MultiplierType.DISCOUNT_ALCHEMY), 50, now - 1000, now + 86_400_000L);
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(ServerEvents.writeConfig(Collections.singletonList(specD)));
    check(rebuilt.size() == 1 && rebuilt.get(0).getID() == 700_080L, "spec MISC_DISCOUNT round-trip");
    ServerEvents.install(rebuilt);
    check(ServerEvents.snapshot().getAlchemyPrice(100) == 50, "event reconstruit → même remise ALCHEMY");
    System.out.println("[misc] spec round-trip → remise ALCHEMY préservée ✔");

    ServerEvents.install(new ArrayList<>());
    System.out.println("[misc] OK — MISC_BONUS + MISC_DISCOUNT objets du jeu, MultiplierType + valeur = params admin. [headless]");
  }
}
