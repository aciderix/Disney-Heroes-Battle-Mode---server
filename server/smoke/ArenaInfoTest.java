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

    // Les ADVERSAIRES doivent avoir des équipes NON VIDES, DISTINCTES et de puissances VARIÉES (classées décroissant).
    int nonEmpty = 0; long minP = Long.MAX_VALUE, maxP = 0; long prev = Long.MAX_VALUE; boolean sorted = true;
    java.util.Set<String> signatures = new java.util.HashSet<>();
    for (int i = 0; i < back.yourLeague.players.size() - 1; i++) {   // tous sauf toi (dernier)
      ArenaRow r = (ArenaRow) back.yourLeague.players.get(i);
      int sz = r.lineup != null ? r.lineup.size() : 0;
      if (sz > 0) nonEmpty++;
      minP = Math.min(minP, r.heroPower); maxP = Math.max(maxP, r.heroPower);
      if (r.heroPower > prev) sorted = false; prev = r.heroPower;
      // VARIÉTÉ = compositions distinctes, mesurées par le TYPE des héros (identité de composition ; le heroId
      // est un id d'instance, non l'identité du héros).
      StringBuilder sig = new StringBuilder();
      if (r.lineup != null) for (Object h : r.lineup) sig.append(((HeroSummary) h).type).append(',');
      signatures.add(sig.toString());
      System.out.println("[arena]   adversaire " + (i + 1) + " '" + r.playerRow.info.name + "' power="
          + r.heroPower + " lineup=" + sz);
    }
    if (nonEmpty < back.yourLeague.players.size() - 1) throw new AssertionError("tous les adversaires doivent avoir une équipe");
    if (signatures.size() < 3) throw new AssertionError("les équipes adverses doivent être VARIÉES (distinctes)");
    if (minP == maxP) throw new AssertionError("les puissances adverses doivent varier (échelle)");
    if (!sorted) throw new AssertionError("les adversaires doivent être classés par puissance décroissante");
    System.out.println("[arena] adversaires : " + nonEmpty + " équipes non vides, " + signatures.size()
        + " compositions distinctes, puissance " + minP + "→" + maxP + " (classées)");

    System.out.println("[arena] OK — ArenaInfo valide + round-trip wire + adversaires synthétiques variés/classés");

    // COLISEUM : le rendu (PVPRow.createColiUnitTable) INDEXE ArenaRow.lineups → chaque row DOIT porter des
    // équipes de défense NON VIDES (sinon get(-1) plante le client, cf. IndexOutOfBounds observé en jeu). On
    // vérifie 3 lignes de défense par adversaire (COLISEUM_DEFENSE_1/2/3) et un round-trip wire.
    ArenaInfo coli = su.arenaInfo(ArenaType.COLISEUM);
    GruntOutputStream out2 = new GruntOutputStream();
    coli.writeAll(out2);
    ArenaInfo cback = (ArenaInfo) MessageFactory.getInstance()
        .readMessage(new GruntInputStream(out2.getBytes()));
    if (cback.type != ArenaType.COLISEUM) throw new AssertionError("type coliseum");
    int oppCount = cback.yourLeague.players.size() - 1;
    for (int i = 0; i < oppCount; i++) {
      ArenaRow r = (ArenaRow) cback.yourLeague.players.get(i);
      int nT = r.lineups != null ? r.lineups.size() : 0;
      if (nT < 1) throw new AssertionError("adversaire coliseum " + (i + 1) + " sans équipe de défense (lineups vide → crash client)");
      for (Object lo : r.lineups) {
        LineupSummary ls = (LineupSummary) lo;
        if (ls.lineup == null || ls.lineup.isEmpty())
          throw new AssertionError("équipe de défense coliseum vide (adversaire " + (i + 1) + ")");
      }
      if (i == 0) System.out.println("[arena] coliseum adversaire 1 : " + nT + " équipes de défense, tailles="
          + coliSizes(r));
    }
    // Ta row (dernière) doit aussi porter au moins une équipe non vide (sinon crash au rendu de ta ligne).
    ArenaRow yoursColi = (ArenaRow) cback.yourLeague.players.get(cback.yourLeague.players.size() - 1);
    if (yoursColi.lineups == null || yoursColi.lineups.isEmpty())
      throw new AssertionError("ta row coliseum sans équipe (lineups vide → crash client)");
    System.out.println("[arena] OK — COLISEUM : " + oppCount + " adversaires avec équipes de défense multiples, round-trip wire valide");
  }

  private static String coliSizes(ArenaRow r) {
    StringBuilder sb = new StringBuilder("[");
    for (Object lo : r.lineups) sb.append(((LineupSummary) lo).lineup.size()).append(' ');
    return sb.append(']').toString();
  }
}
