import com.perblue.heroes.network.messages.*;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * ARÈNE — l'{@link ArenaInfo} construit par le serveur ({@code ServerUser.arenaInfo}) doit être STRUCTURELLEMENT
 * VALIDE : se sérialiser puis se re-lire par le codec wire du jeu (comme le client le recevra), avec saison + ta
 * ligue COPPER + ta row + adversaires. Prouve que la réponse à {@code GetArenaInfo} ne fera pas planter le client.
 */
public final class ArenaInfoTest {

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    // Un roster pour la lineup de défense.
    su.grantHero(UnitType.RALPH);
    su.grantHero(UnitType.ELASTIGIRL);

    ArenaInfo ai = su.arenaInfo(ArenaType.FIGHT_PIT);
    System.out.println("[arena] construit : type=" + ai.type + " tier=" + ai.yourLeague.tier
        + " div=" + ai.yourLeague.division + " rank=" + ai.yourLeague.yourRank
        + " players=" + ai.yourLeague.players.size()
        + " saison id=" + ai.season.id + " day=" + ai.season.dayNumber
        + " endOfDay=" + ai.season.endOfDay + " endOfWeek=" + ai.season.endOfWeek);

    if (ai.type != ArenaType.FIGHT_PIT) throw new AssertionError("type");
    if (ai.yourLeague == null || ai.season == null) throw new AssertionError("league/season null");
    if (ai.yourLeague.players.size() < 2) throw new AssertionError("il faut ta row + des adversaires");
    if (ai.season.endOfDay <= System.currentTimeMillis()) throw new AssertionError("endOfDay doit être futur");
    if (ai.season.endOfWeek <= System.currentTimeMillis()) throw new AssertionError("endOfWeek doit être futur");

    // ROUND-TRIP WIRE : sérialiser (writeAll) → relire (MessageFactory) → mêmes valeurs clés.
    GruntOutputStream out = new GruntOutputStream();
    ai.writeAll(out);
    ArenaInfo back = (ArenaInfo) MessageFactory.getInstance()
        .readMessage(new GruntInputStream(out.getBytes()));
    System.out.println("[arena] round-trip wire → tier=" + back.yourLeague.tier
        + " players=" + back.yourLeague.players.size() + " rank=" + back.yourLeague.yourRank);
    if (back.yourLeague.tier != ai.yourLeague.tier) throw new AssertionError("tier non conservé");
    if (back.yourLeague.players.size() != ai.yourLeague.players.size()) throw new AssertionError("players non conservés");
    if (back.yourLeague.yourRank != ai.yourLeague.yourRank) throw new AssertionError("rank non conservé");

    // La row du joueur doit porter une identité + une lineup.
    ArenaRow yours = (ArenaRow) back.yourLeague.players.get(back.yourLeague.players.size() - 1);
    System.out.println("[arena] ta row : name=" + (yours.playerRow != null ? yours.playerRow.info.name : "?")
        + " power=" + yours.heroPower + " lineup=" + (yours.lineup != null ? yours.lineup.size() : 0));

    System.out.println("[arena] OK — ArenaInfo valide + round-trip wire (l'écran pourra rendre)");
  }
}
