import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * ARÈNE #41 — la DÉFENSE d'arène que le joueur POSE ({@code HeroLineupUpdate}) doit être ENREGISTRÉE par la logique
 * du jeu ({@code User.setHeroLineup}), PERSISTÉE ({@code userExtra.heroLineups}, survit au round-trip wire) et
 * RELUE par {@code ServerArena} (ta row d'arène montre TA défense, pas le placeholder roster).
 */
public final class ArenaDefenseTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE,
        UnitType.MERIDA, UnitType.MAUI, UnitType.MOANA})
      su.grantHero(t);

    // Le joueur POSE sa défense COLISEUM_DEFENSE_1 = [MOANA, MERIDA, MAUI] (ordre CHOISI, ≠ ordre roster).
    HeroLineup hl = new HeroLineup();
    hl.heroes = new java.util.ArrayList<>(java.util.Arrays.asList(
        UnitType.MOANA, UnitType.MERIDA, UnitType.MAUI));
    hl.mercenaryType = UnitType.DEFAULT;
    HeroLineupUpdate u = new HeroLineupUpdate();
    u.type = HeroLineupType.COLISEUM_DEFENSE_1;
    u.iD = 0L;
    u.lineup = hl;
    u.customName = "";
    u.emeraldStatSlotChoices = new java.util.HashMap<>();
    u.realGearOptions = new java.util.HashMap<>();

    if (!su.applyHeroLineupUpdate(u)) throw new AssertionError("applyHeroLineupUpdate a échoué");
    System.out.println("[def] défense COLISEUM_DEFENSE_1 posée = [MOANA, MERIDA, MAUI]");

    // PERSISTANCE : recharger depuis les octets wire (comme la DB) → la défense doit survivre.
    ServerUser reloaded = ServerUser.fromWire(1L, 1,
        su.userInfoWire(), su.userExtraWire(), su.individualWire());

    ArenaInfo ai = reloaded.arenaInfo(ArenaType.COLISEUM);
    ArenaRow you = (ArenaRow) ai.yourLeague.players.get(ai.yourLeague.players.size() - 1);
    if (you.lineups == null || you.lineups.isEmpty())
      throw new AssertionError("ta row n'a aucune équipe de défense après reload");
    LineupSummary firstTeam = (LineupSummary) you.lineups.get(0);
    java.util.List<UnitType> got = new java.util.ArrayList<>();
    for (Object o : firstTeam.lineup) got.add(((ExtendedHeroSummary) o).summary.type);
    System.out.println("[def] après reload, ta 1re équipe d'arène = " + got);

    java.util.List<UnitType> want = java.util.Arrays.asList(UnitType.MOANA, UnitType.MERIDA, UnitType.MAUI);
    if (!got.equals(want))
      throw new AssertionError("la défense relue " + got + " ≠ la défense posée " + want
          + " (défense non persistée ou non relue par ServerArena)");

    // Chaque héros porte des PV (ExtendedHeroSummary.health = HP_MAX, logique du jeu) → non nul.
    for (Object o : firstTeam.lineup)
      if (((ExtendedHeroSummary) o).health <= 0)
        throw new AssertionError("PV nuls dans la défense relue");

    System.out.println("[def] OK — défense d'arène POSÉE → PERSISTÉE (round-trip wire) → RELUE par ServerArena");
  }
}
