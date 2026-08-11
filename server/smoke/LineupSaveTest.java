import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * SAVED_LINEUPS (#72) incrément 1 — sauvegarde/mise à jour d'un lineup nommé ({@code HeroLineupUpdate}).
 * Le serveur ré-exécute {@code User.setHeroLineup} (expiration=Long.MAX_VALUE, §4) et persiste par
 * {@code resyncLineups}. Round-trip PROFOND : plusieurs types/ids distincts survivent (type + id + nom + héros +
 * mercenaire), pas juste le type wire (angle mort clé→data, leçon EXPEDITION).
 */
public final class LineupSaveTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[lineup] " + m); }

  /** Nombre de lineups dans la Map runtime privée {@code User.lineups} (réflexion). */
  static int lineupCount(ServerUser su) throws Exception {
    java.lang.reflect.Field lf = com.perblue.heroes.game.objects.User.class.getDeclaredField("lineups");
    lf.setAccessible(true);
    return ((Map<?, ?>) lf.get(su.gameUser())).size();
  }

  static HeroLineup lineup(UnitType merc, UnitType... heroes) {
    HeroLineup l = new HeroLineup();
    l.heroes = new ArrayList<>(Arrays.asList(heroes));
    l.mercenaryType = merc != null ? merc : UnitType.DEFAULT;   // sentinelle « pas de merc » du jeu (jamais null sur le wire)
    return l;
  }
  static HeroLineupUpdate upd(HeroLineupType type, long id, String name, HeroLineup l) {
    HeroLineupUpdate u = new HeroLineupUpdate();
    u.type = type; u.iD = id; u.customName = name; u.lineup = l;
    u.emeraldStatSlotChoices = new HashMap<>(); u.realGearOptions = new HashMap<>();
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9500L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;

    // 3 lineups à id=0 (types distincts) + 1 à id non-nul (dimension id).
    HeroLineup l1 = lineup(null, UnitType.RALPH, UnitType.VANELLOPE, UnitType.ELASTIGIRL);
    HeroLineup l2 = lineup(UnitType.ELSA, UnitType.MERLIN, UnitType.HERCULES);
    HeroLineup l3 = lineup(null, UnitType.MOANA, UnitType.MAUI);
    HeroLineup lId = lineup(null, UnitType.STITCH, UnitType.ANGEL);

    check(su.applyHeroLineupUpdate(upd(HeroLineupType.SAVED_1, 0, "Team Alpha", l1)), "SAVED_1 sauvé");
    check(su.applyHeroLineupUpdate(upd(HeroLineupType.SAVED_2, 0, "Team Bravo", l2)), "SAVED_2 sauvé");
    check(su.applyHeroLineupUpdate(upd(HeroLineupType.EXPEDITION, 0, "Expé", l3)), "EXPEDITION sauvé");
    check(su.applyHeroLineupUpdate(upd(HeroLineupType.SAVED_3, 42L, "Ohana", lId)), "SAVED_3#42 sauvé");

    // Lecture immédiate (runtime).
    check(su.gameUser().getHeroLineup(HeroLineupType.SAVED_1, 0).heroes.size() == 3, "SAVED_1 3 héros");
    check(su.gameUser().getHeroLineupName(HeroLineupType.SAVED_2).equals("Team Bravo"), "SAVED_2 nom");
    check(su.gameUser().getHeroLineup(HeroLineupType.SAVED_2, 0).mercenaryType == UnitType.ELSA, "SAVED_2 merc ELSA");
    check(su.gameUser().getHeroLineup(HeroLineupType.SAVED_3, 42L).heroes.contains(UnitType.STITCH), "SAVED_3#42 STITCH");

    // Le User runtime porte bien 4 lineups (Map lineups, réflexion).
    check(lineupCount(su) == 4, "4 lineups en mémoire (=" + lineupCount(su) + ")");

    // --- Round-trip PROFOND wire : tout survit avec la bonne CLÉ (type + id) ---
    ServerUser rl = ServerUser.fromWire(9500L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    var u = rl.gameUser();
    check(u.getHeroLineup(HeroLineupType.SAVED_1, 0).heroes.equals(Arrays.asList(UnitType.RALPH, UnitType.VANELLOPE, UnitType.ELASTIGIRL)),
        "SAVED_1 héros survivent (ordre inclus)");
    check(u.getHeroLineupName(HeroLineupType.SAVED_1).equals("Team Alpha"), "SAVED_1 nom survit");
    check(u.getHeroLineup(HeroLineupType.SAVED_2, 0).mercenaryType == UnitType.ELSA, "SAVED_2 merc survit");
    check(u.getHeroLineupName(HeroLineupType.SAVED_2).equals("Team Bravo"), "SAVED_2 nom survit");
    check(u.getHeroLineup(HeroLineupType.EXPEDITION, 0).heroes.size() == 2, "EXPEDITION lineup survit (type par-mode)");
    // La dimension ID : SAVED_3#42 ne doit PAS collapser sur (SAVED_3,0) ni (DEFAULT,0).
    check(u.getHeroLineup(HeroLineupType.SAVED_3, 42L).heroes.contains(UnitType.STITCH), "SAVED_3#42 survit (id non-nul)");
    check(u.getHeroLineup(HeroLineupType.SAVED_3, 0).heroes.isEmpty(), "SAVED_3#0 reste VIDE (pas de collapse d'id)");
    check(lineupCount(rl) == 4, "4 lineups après round-trip (=" + lineupCount(rl) + ")");
    System.out.println("[lineup] round-trip wire : 4 lineups distincts (type+id) survivent ✔");

    // --- Persistance DB ---
    java.io.File db = java.io.File.createTempFile("lineup", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9500L, 1);
    check(fromDb != null, "compte relu DB");
    var ud = fromDb.gameUser();
    check(ud.getHeroLineup(HeroLineupType.SAVED_1, 0).heroes.size() == 3, "DB : SAVED_1 3 héros");
    check(ud.getHeroLineupName(HeroLineupType.SAVED_2).equals("Team Bravo"), "DB : SAVED_2 nom");
    check(ud.getHeroLineup(HeroLineupType.SAVED_3, 42L).heroes.contains(UnitType.STITCH), "DB : SAVED_3#42 STITCH");
    check(ud.getHeroLineup(HeroLineupType.EXPEDITION, 0).heroes.size() == 2, "DB : EXPEDITION lineup");
    store.close(); db.delete();

    // --- Mise à jour d'un lineup existant (écrase, ne duplique pas) ---
    HeroLineup l1b = lineup(null, UnitType.RALPH, UnitType.MOANA);
    check(su.applyHeroLineupUpdate(upd(HeroLineupType.SAVED_1, 0, "Team Alpha v2", l1b)), "SAVED_1 mis à jour");
    check(lineupCount(su) == 4, "toujours 4 lineups (pas de doublon après update)");
    check(su.gameUser().getHeroLineup(HeroLineupType.SAVED_1, 0).heroes.size() == 2, "SAVED_1 écrasé (2 héros)");
    check(su.gameUser().getHeroLineupName(HeroLineupType.SAVED_1).equals("Team Alpha v2"), "SAVED_1 nom mis à jour");
    System.out.println("[lineup] update en place (pas de doublon) ✔");

    System.out.println("[lineup] OK — sauvegarde de lineups nommés + persistance profonde (type+id) vérifiées (headless).");
  }
}
