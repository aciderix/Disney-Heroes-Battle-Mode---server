import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * SAVED_LINEUPS (#72) — round-trip PROFOND des champs {@code realGearOptions} + {@code emeraldStatSlotChoices}
 * NON VIDES. Angle mort (leçon EXPEDITION + note ARÈNE #41) : l'ordre des deux {@code Map} passées à
 * {@code setHeroLineup} n'était vérifiable QU'AVEC DU CONTENU (le bug d'inversion → ClassCast à la sérialisation ne
 * se déclenche PAS sur des Maps vides). Les tests précédents utilisaient des Maps vides → ce test les PEUPLE :
 * <ul>
 *   <li>{@code realGearOptions = {RALPH: RealGearType.CALHOUN_ENERGY}}</li>
 *   <li>{@code emeraldStatSlotChoices = {RALPH: HeroStatSlotChoices{FIRST: [ARMOR_NEGATION]}}}</li>
 * </ul>
 * et vérifie qu'après round-trip wire + DB : (1) pas de ClassCast (ordre correct), (2) le contenu survit et n'est
 * PAS SWAPPÉ (realGearOptions garde le RealGearType, emeraldStatSlotChoices garde le HeroStatSlotChoices).
 */
public final class LineupFieldsTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[lineup-fields] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9700L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;

    HeroLineup l = new HeroLineup();
    l.heroes = new ArrayList<>(Arrays.asList(UnitType.RALPH));
    l.mercenaryType = UnitType.DEFAULT;

    // realGearOptions : Map<UnitType, RealGearType> NON VIDE
    Map<UnitType, RealGearType> rg = new HashMap<>();
    rg.put(UnitType.RALPH, RealGearType.CALHOUN_ENERGY);

    // emeraldStatSlotChoices : Map<UnitType, HeroStatSlotChoices{Map<EmeraldStatSlot, List<CombatStatType>>}> NON VIDE
    HeroStatSlotChoices hsc = new HeroStatSlotChoices();
    hsc.statSlotChoices = new HashMap<>();          // Map<EmeraldStatSlot, CombatStatType> (valeur = enum simple)
    hsc.statSlotChoices.put(EmeraldStatSlot.FIRST, CombatStatType.ARMOR_NEGATION);
    Map<UnitType, HeroStatSlotChoices> em = new HashMap<>();
    em.put(UnitType.RALPH, hsc);

    HeroLineupUpdate u = new HeroLineupUpdate();
    u.type = HeroLineupType.SAVED_1; u.iD = 0; u.customName = "Deep";
    u.lineup = l; u.realGearOptions = rg; u.emeraldStatSlotChoices = em;

    check(su.applyHeroLineupUpdate(u), "SAVED_1 (champs peuplés) sauvé sans crash");

    // Round-trip wire (sérialisation réelle — c'est ICI que l'inversion d'ordre planterait en ClassCast).
    ServerUser rl = ServerUser.fromWire(9700L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    UserHeroLineupData d = rl.gameUser().getHeroLineupData(HeroLineupType.SAVED_1);
    check(d != null, "lineup relu non-null");

    // (1) realGearOptions garde son RealGearType (PAS swappé avec emeraldStatSlotChoices).
    check(d.realGearOptions != null && d.realGearOptions.get(UnitType.RALPH) == RealGearType.CALHOUN_ENERGY,
        "realGearOptions[RALPH]=CALHOUN_ENERGY survit (=" + (d.realGearOptions == null ? "null" : d.realGearOptions.get(UnitType.RALPH)) + ")");

    // (2) emeraldStatSlotChoices garde son HeroStatSlotChoices avec le bon contenu profond.
    check(d.emeraldStatSlotChoices != null, "emeraldStatSlotChoices non-null");
    Object v = d.emeraldStatSlotChoices.get(UnitType.RALPH);
    check(v instanceof HeroStatSlotChoices, "emeraldStatSlotChoices[RALPH] est bien un HeroStatSlotChoices (pas swappé) (=" + (v == null ? "null" : v.getClass().getSimpleName()) + ")");
    HeroStatSlotChoices back = (HeroStatSlotChoices) v;
    check(back.statSlotChoices.get(EmeraldStatSlot.FIRST) == CombatStatType.ARMOR_NEGATION,
        "statSlotChoices[FIRST]=ARMOR_NEGATION (contenu profond survit) (=" + back.statSlotChoices.get(EmeraldStatSlot.FIRST) + ")");
    System.out.println("[lineup-fields] round-trip wire : realGearOptions + emeraldStatSlotChoices NON VIDES survivent, non swappés ✔");

    // Persistance DB.
    java.io.File db = java.io.File.createTempFile("lfields", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9700L, 1);
    UserHeroLineupData dd = fromDb.gameUser().getHeroLineupData(HeroLineupType.SAVED_1);
    check(dd.realGearOptions.get(UnitType.RALPH) == RealGearType.CALHOUN_ENERGY, "DB : realGearOptions survit");
    check(dd.emeraldStatSlotChoices.get(UnitType.RALPH) instanceof HeroStatSlotChoices, "DB : emeraldStatSlotChoices survit (bon type)");
    store.close(); db.delete();

    System.out.println("[lineup-fields] OK — champs realGearOptions/emeraldStatSlotChoices non vides persistés (ordre des Maps validé) (headless).");
  }
}
