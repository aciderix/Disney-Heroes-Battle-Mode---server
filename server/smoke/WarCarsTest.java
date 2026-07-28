import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.ServerWar;
import dhserver.ServerWarCars;
import dhserver.ServerWarState;
import dhserver.UserStore;

/**
 * GUILD WAR #68 — ÉTAPE 4 : les voitures (salles), affectations, étoiles, portes de garage.
 *
 * <p>Prouve : (a) l'ordre et les étages du garage correspondent à ceux du jeu
 * ({@code WarHelper.getFloorNumber}), (b) les voitures se DÉRIVENT des membres comme le fait le client,
 * (c) une étoile est un lineup et une salle vide compte comme prise — exactement ce que dit l'aide du jeu,
 * (d) les portes suivent la règle « il faut voler une voiture pour ouvrir l'étage suivant », (e)
 * {@code ASSIGN_WAR_CAR} respecte les droits de rôle et la capacité de salle, (f) tout survit au wire.
 */
public final class WarCarsTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  /** Un membre de guerre avec {@code n} lineups de 5 héros, tous debout. */
  static WarMemberInfo member(long userID, String name, int lineups) {
    WarMemberInfo m = new WarMemberInfo();
    BasicUserInfo bi = new BasicUserInfo();
    bi.iD = userID; bi.name = name; bi.teamLevel = 45;
    m.userInfo = bi;
    m.assignedCar = WarCarType.DEFAULT;
    for (int i = 0; i < lineups; i++) {
      WarLineupSummary l = new WarLineupSummary();
      for (int h = 0; h < 5; h++) {
        WarHeroSummary hs = new WarHeroSummary();
        hs.defeated = false;
        hs.sabotage = WarSabotageType.DEFAULT;
        l.heroes.add(hs);
      }
      m.defenses.add(l);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  static void addMember(WarGuildInfo side, WarMemberInfo m) {
    side.members.put(m.userInfo.iD, m);
  }

  /** Marque tous les héros d'un lineup comme battus. */
  static void defeatLineup(WarMemberInfo m, int index) {
    WarLineupSummary l = (WarLineupSummary) m.defenses.get(index);
    for (Object o : l.heroes) ((WarHeroSummary) o).defeated = true;
  }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-war-cars", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser ruler = ServerUser.newPlayer(1L, 1);
      ruler.giveResource(ResourceType.GOLD, 5000);
      ServerGuild g = ruler.createGuild(mk("Garage Legion"), store.nextGuildID(1));
      ServerWar.rollOverSeason(g, ServerWar.seasonIDAt(now), 0);
      store.saveGuild(g);

      // ---------------------------------------------------------------------------------------
      // 1. ORDRE DU GARAGE — conforme aux étages du jeu.
      // ---------------------------------------------------------------------------------------
      check(ServerWarCars.GARAGE_ORDER.size() == ServerWarCars.GARAGE_SIZE,
          "le garage doit compter " + ServerWarCars.GARAGE_SIZE + " salles");
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        WarCarType car = ServerWarCars.GARAGE_ORDER.get(i);
        int expectedFloor = i / 3 + 1;
        int gameFloor = com.perblue.heroes.game.logic.WarHelper.getFloorNumber(car);
        check(gameFloor == expectedFloor,
            "salle " + i + " (" + car + ") : le jeu la met à l'étage " + gameFloor
                + ", l'ordre du garage à l'étage " + expectedFloor);
        check(ServerWarCars.garageIndex(car) == i, "index de garage incohérent pour " + car);
      }
      // Les 9 salles sont toutes des types distincts, et DEFAULT n'en fait pas partie.
      check(new java.util.HashSet<>(ServerWarCars.GARAGE_ORDER).size() == 9, "salles dupliquées");
      check(!ServerWarCars.GARAGE_ORDER.contains(WarCarType.DEFAULT), "DEFAULT n'est pas une salle");
      System.out.println("[war] garage : 9 salles, 3 par étage, ordre conforme à WarHelper.getFloorNumber");

      // ---------------------------------------------------------------------------------------
      // 2. LES VOITURES SE DÉRIVENT DES MEMBRES (comme collectWarInfoCarMembers).
      // ---------------------------------------------------------------------------------------
      ServerWarState w = new ServerWarState();
      w.shardID = 1; w.seasonID = ServerWar.seasonIDAt(now);
      w.startTime = now; w.endTime = now + 2 * 86_400_000L;
      w.state = WarSummaryState.ACTIVE; w.stateEndTime = w.endTime;
      w.guildAID = g.guildID; w.guildBID = 0;

      WarGuildInfo side = new WarGuildInfo();
      side.guildInfo = g.info.basicInfo;
      WarMemberInfo m1 = member(1L, "Chef", 3);
      WarMemberInfo m2 = member(2L, "Second", 3);
      WarMemberInfo m3 = member(3L, "Troisieme", 3);
      addMember(side, m1); addMember(side, m2); addMember(side, m3);
      w.putSide(g.guildID, side);

      ServerWarCars.rebuildCars(side);
      check(side.cars.size() >= 9, "les 9 salles doivent exister même vides, obtenu " + side.cars.size());
      WarCarType floor1a = ServerWarCars.GARAGE_ORDER.get(0);
      WarCarInfo c0 = (WarCarInfo) side.cars.get(floor1a);
      check(c0 != null && c0.members.isEmpty(), "aucun membre affecté au départ");
      check(c0.starsTotal == 0 && c0.starsEarned == 0, "une salle vide n'a aucune étoile");
      // « Rooms that have no defenders are automatically defeated » : starsEarned >= starsTotal (0 >= 0).
      check(ServerWarCars.carDefeated(c0), "une salle SANS défenseur compte comme prise (règle du jeu)");
      System.out.println("[war] salle vide → comptée comme prise (0 étoile sur 0), conforme à l'aide du jeu");

      // ---------------------------------------------------------------------------------------
      // 3. AFFECTATION — droits de rôle et capacité.
      // ---------------------------------------------------------------------------------------
      int max = ServerWarCars.maxCarSize(g, floor1a);
      check(max == com.perblue.heroes.game.data.war.WarStats.getBaseCarSize(),
          "sans perk, la capacité vaut BASE_CAR_SIZE (" + com.perblue.heroes.game.data.war.WarStats.getBaseCarSize()
              + "), obtenu " + max);

      // RÈGLE DU CLIENT (WarCarLineupsTable) : on édite TOUJOURS sa propre place — `canMoveWarLineups` n'est
      // que le droit SUPPLÉMENTAIRE d'éditer la carte d'un autre. Fait mesuré : dans la table de permissions
      // de ce build, WAR_MOVE_LINEUPS n'est accordé à AUCUN rôle, pas même RULER — gater l'auto-placement
      // dessus l'aurait rendu impossible pour tout le monde.
      for (GuildRole r : GuildRole.values()) {
        check(!com.perblue.heroes.game.logic.GuildHelper.canMoveWarLineups(r),
            "fait attendu de ce build : WAR_MOVE_LINEUPS n'est accordé à personne (or " + r + " l'a)");
      }
      String err = ServerWarCars.assignCar(g, w, 2L, GuildRole.MEMBER, 2L, floor1a);
      check(err == null, "un membre doit toujours pouvoir choisir SA propre salle : " + err);
      System.out.println("[war] auto-placement autorisé pour tout participant (règle de WarCarLineupsTable)");

      // Déplacer QUELQU'UN D'AUTRE demande un droit supérieur.
      boolean othersAllowed = com.perblue.heroes.game.logic.GuildHelper.canMoveOthersWarLineups(GuildRole.MEMBER);
      err = ServerWarCars.assignCar(g, w, 2L, GuildRole.MEMBER, 3L, floor1a);
      check((err == null) == othersAllowed,
          "déplacer autrui doit suivre canMoveOthersWarLineups(MEMBER)=" + othersAllowed);
      if (!othersAllowed) System.out.println("[war] MEMBER ne peut pas déplacer autrui → refusé (" + err + ")");

      // Le chef le peut.
      check(com.perblue.heroes.game.logic.GuildHelper.canMoveOthersWarLineups(GuildRole.RULER),
          "le chef doit pouvoir déplacer les lineups");
      err = ServerWarCars.assignCar(g, w, 1L, GuildRole.RULER, 1L, floor1a);
      check(err == null, "le chef doit pouvoir se placer : " + err);
      err = ServerWarCars.assignCar(g, w, 1L, GuildRole.RULER, 2L, floor1a);
      check(err == null, "le chef doit pouvoir placer un autre membre : " + err);
      err = ServerWarCars.assignCar(g, w, 1L, GuildRole.RULER, 3L, floor1a);
      check(err == null, "3e affectation (capacité " + max + ") : " + err);

      // Capacité atteinte → la 4e est refusée.
      side = w.sideOf(g.guildID);
      WarMemberInfo m4 = member(4L, "Quatrieme", 3);
      addMember(side, m4);
      w.putSide(g.guildID, side);
      err = ServerWarCars.assignCar(g, w, 1L, GuildRole.RULER, 4L, floor1a);
      check(err != null, "la salle est pleine (" + max + ") : la 4e affectation doit être refusée");
      System.out.println("[war] capacité de salle " + max + " respectée → 4e affectation refusée (" + err + ")");

      // Un joueur hors de la guerre n'est pas affectable.
      err = ServerWarCars.assignCar(g, w, 1L, GuildRole.RULER, 999L, floor1a);
      check(err != null, "un joueur hors de la guerre ne doit pas être affectable");

      // ---------------------------------------------------------------------------------------
      // 4. ÉTOILES — un lineup = une étoile.
      // ---------------------------------------------------------------------------------------
      side = w.sideOf(g.guildID);
      ServerWarCars.rebuildCars(side);
      WarCarInfo car0 = (WarCarInfo) side.cars.get(floor1a);
      check(car0.members.size() == 3, "3 membres dans la salle, obtenu " + car0.members.size());
      check(car0.starsTotal == 9, "3 membres × 3 lineups = 9 étoiles, obtenu " + car0.starsTotal);
      check(car0.starsEarned == 0, "aucune étoile prise au départ");
      check(!ServerWarCars.carDefeated(car0), "une salle défendue n'est pas prise");

      // On abat les lineups un à un.
      WarMemberInfo occupant = (WarMemberInfo) side.members.get(1L);
      defeatLineup(occupant, 0);
      ServerWarCars.rebuildCars(side);
      car0 = (WarCarInfo) side.cars.get(floor1a);
      check(car0.starsEarned == 1, "1 lineup battu = 1 étoile, obtenu " + car0.starsEarned);
      check(!ServerWarCars.carDefeated(car0), "8 étoiles restantes → salle non prise");

      for (long uid : new long[]{1L, 2L, 3L}) {
        WarMemberInfo m = (WarMemberInfo) side.members.get(uid);
        for (int i = 0; i < m.defenses.size(); i++) defeatLineup(m, i);
      }
      ServerWarCars.rebuildCars(side);
      car0 = (WarCarInfo) side.cars.get(floor1a);
      check(car0.starsEarned == 9 && ServerWarCars.carDefeated(car0),
          "tous les lineups battus → salle PRISE (" + car0.starsEarned + "/" + car0.starsTotal + ")");
      System.out.println("[war] étoiles : 3 membres × 3 lineups = 9 · salle prise à 9/9");

      // ---------------------------------------------------------------------------------------
      // 5. PORTES DE GARAGE — « voler une voiture ouvre l'étage suivant ».
      // ---------------------------------------------------------------------------------------
      // Toutes les salles sauf celle de l'étage 1 sont vides → donc « prises » — on repart d'un état net
      // en plaçant un défenseur dans une salle de chaque étage.
      ServerWarState w2 = new ServerWarState();
      w2.shardID = 1; w2.guildAID = g.guildID; w2.guildBID = 0;
      w2.state = WarSummaryState.ACTIVE;
      WarGuildInfo s2 = new WarGuildInfo();
      s2.guildInfo = g.info.basicInfo;
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        WarMemberInfo m = member(100L + i, "D" + i, 1);
        m.assignedCar = ServerWarCars.GARAGE_ORDER.get(i);
        addMember(s2, m);
      }
      ServerWarCars.rebuildCars(s2);
      w2.putSide(g.guildID, s2);

      boolean[] doors = ServerWarCars.closedGarageDoors(s2, WarSummaryState.ACTIVE, false);
      check(!doors[0] && !doors[1] && !doors[2], "l'étage 1 est toujours ouvert");
      check(doors[3] && doors[4] && doors[5], "l'étage 2 doit être fermé tant qu'aucune voiture n'est volée");
      check(doors[6] && doors[7] && doors[8], "l'étage 3 aussi");
      System.out.println("[war] portes au départ : étage 1 ouvert, étages 2 et 3 fermés");

      // On prend UNE voiture de l'étage 1 → l'étage 2 s'ouvre, pas l'étage 3.
      WarMemberInfo d0 = (WarMemberInfo) s2.members.get(100L);
      defeatLineup(d0, 0);
      ServerWarCars.rebuildCars(s2);
      w2.putSide(g.guildID, s2);   // putSide fige un INSTANTANÉ : sans ce rappel, la mutation serait perdue
      doors = ServerWarCars.closedGarageDoors(s2, WarSummaryState.ACTIVE, false);
      check(!doors[3] && !doors[4] && !doors[5], "voler UNE voiture de l'étage 1 doit ouvrir l'étage 2");
      check(doors[6] && doors[7] && doors[8], "l'étage 3 doit rester fermé");
      System.out.println("[war] 1 voiture volée à l'étage 1 → étage 2 OUVERT, étage 3 encore fermé");

      // Puis une de l'étage 2 → l'étage 3 s'ouvre.
      WarMemberInfo d3 = (WarMemberInfo) s2.members.get(103L);
      defeatLineup(d3, 0);
      ServerWarCars.rebuildCars(s2);
      w2.putSide(g.guildID, s2);
      doors = ServerWarCars.closedGarageDoors(s2, WarSummaryState.ACTIVE, false);
      check(!doors[6] && !doors[7] && !doors[8], "voler une voiture de l'étage 2 doit ouvrir l'étage 3");
      check(ServerWarCars.isCarOpen(s2, ServerWarCars.GARAGE_ORDER.get(8), WarSummaryState.ACTIVE),
          "la dernière salle doit être attaquable");
      System.out.println("[war] 1 voiture volée à l'étage 2 → étage 3 OUVERT");

      // Pendant le SABOTAGE : son PROPRE garage est entièrement fermé, celui de l'ennemi non.
      boolean[] mine = ServerWarCars.closedGarageDoors(s2, WarSummaryState.SABOTAGE, true);
      boolean[] theirs = ServerWarCars.closedGarageDoors(s2, WarSummaryState.SABOTAGE, false);
      for (int i = 0; i < ServerWarCars.GARAGE_SIZE; i++) {
        check(mine[i], "en phase de SABOTAGE, son propre garage est fermé (salle " + i + ")");
        check(!theirs[i], "en phase de SABOTAGE, le garage ENNEMI reste visible (salle " + i + ")");
      }
      System.out.println("[war] phase SABOTAGE : garage propre fermé, garage ennemi ouvert (règle du client)");

      // ---------------------------------------------------------------------------------------
      // 6. PERSISTANCE — affectations et étoiles survivent au wire.
      // ---------------------------------------------------------------------------------------
      // Piège d'API vérifié ici : `putSide` fige un INSTANTANÉ. Muter l'objet rendu par `sideOf` sans
      // rappeler `putSide` perdrait la mutation SANS la moindre erreur — le motif est toujours
      // « sideOf → muter → putSide ».
      store.saveWar(w2);
      ServerWarState rw = store.loadWar(1, w2.warID);
      WarGuildInfo rs = rw.sideOf(g.guildID);
      check(rs.members.size() == ServerWarCars.GARAGE_SIZE, "les membres doivent persister");
      WarMemberInfo rm = (WarMemberInfo) rs.members.get(100L);
      check(rm.assignedCar == ServerWarCars.GARAGE_ORDER.get(0), "l'affectation doit persister");
      ServerWarCars.rebuildCars(rs);
      check(ServerWarCars.carDefeated((WarCarInfo) rs.cars.get(ServerWarCars.GARAGE_ORDER.get(0))),
          "la voiture prise doit le rester après round-trip");
      boolean[] rdoors = ServerWarCars.closedGarageDoors(rs, WarSummaryState.ACTIVE, false);
      check(!rdoors[3] && !rdoors[6], "l'ouverture des étages doit se recalculer à l'identique");
      System.out.println("[war] round-trip DB : affectations, étoiles et portes reconstituées à l'identique");

      System.out.println("WAR CARS TEST OK");
    }
  }
}
