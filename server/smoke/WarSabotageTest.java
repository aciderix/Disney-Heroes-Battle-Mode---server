import com.perblue.heroes.network.messages.*;
import dhserver.*;

import java.util.Arrays;

/**
 * GUILD WAR #68 — ÉTAPE 7 : sabotages, bans, protections, spars (le jour 1).
 *
 * <p>Prouve : (a) le coût de sabotage est RECALCULÉ par le serveur et MONTE à chaque sabotage sur la même
 * cible, (b) la monnaie est réellement débitée et le refus tombe si elle manque, (c) le sabotage se pose
 * sur le bon héros et n'est pas rejouable, (d) bans/protections suivent
 * {@code tryEditWarBanProtect} (droit, taille max, cooldowns, fenêtre), (e) les spars suivent
 * {@code trySpar} (quota du perk {@code WAR_SPARS}) sans consommer d'attaque, (f) les frais sont
 * comptabilisés PAR JOUEUR pour le remboursement, et tout persiste.
 */
public final class WarSabotageTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  /** Un défenseur avec 3 lineups de 5 héros distincts, non sabotés. */
  static WarMemberInfo member(long userID, WarCarType car, UnitType[] heroes) {
    WarMemberInfo m = new WarMemberInfo();
    BasicUserInfo bi = new BasicUserInfo();
    bi.iD = userID; bi.name = "U" + userID; bi.teamLevel = 45;
    m.userInfo = bi; m.assignedCar = car;
    int k = 0;
    for (int i = 0; i < 3; i++) {
      WarLineupSummary l = new WarLineupSummary();
      for (int h = 0; h < 5; h++) {
        WarHeroSummary hs = new WarHeroSummary();
        hs.defeated = false;
        hs.sabotage = WarSabotageType.DEFAULT;
        HeroSummary sum = new HeroSummary();
        sum.type = heroes[k % heroes.length];
        k++;
        hs.hero = sum;
        l.heroes.add(hs);
      }
      m.defenses.add(l);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  static void addMember(WarGuildInfo side, WarMemberInfo m) { side.members.put(m.userInfo.iD, m); }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-war-sab", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      int reqTL = com.perblue.heroes.game.data.misc.Unlockables.getTeamLevelReq(
          com.perblue.heroes.game.data.misc.Unlockable.WAR, 1);
      int season = ServerWar.seasonIDAt(now);

      ServerUser ra = ServerUser.newPlayer(1L, 1);
      ra.giveResource(ResourceType.GOLD, 5000);
      ra.basicInfo().teamLevel = reqTL;
      ServerGuild ga = ra.createGuild(mk("Saboteurs"), store.nextGuildID(1));
      ServerWar.rollOverSeason(ga, season, 0);
      ServerUser rb = ServerUser.newPlayer(2L, 1);
      rb.giveResource(ResourceType.GOLD, 5000);
      rb.basicInfo().teamLevel = reqTL;
      ServerGuild gb = rb.createGuild(mk("Cibles"), store.nextGuildID(1));
      ServerWar.rollOverSeason(gb, season, 0);
      store.saveGuild(ga); store.saveGuild(gb); store.save(ra); store.save(rb);

      // Guerre en phase de SABOTAGE, fenêtre de ban ouverte.
      ServerWarState w = new ServerWarState();
      w.shardID = 1; w.seasonID = season;
      w.startTime = now; w.endTime = now + ServerWarMatchmaker.warDuration();
      w.state = WarSummaryState.SABOTAGE;
      w.stateEndTime = now + com.perblue.heroes.game.data.war.WarStats.getSabotagePhaseLength();
      w.extraStateEndTime = now + com.perblue.heroes.game.data.war.WarStats.getSabotageBanPhaseLenght();
      w.guildAID = ga.guildID; w.guildBID = gb.guildID;

      // 15 héros DISTINCTS : une défense de guerre = 3 lineups × 5 héros, tous différents. Ce n'est pas un
      // détail de mise en scène — le protocole lui-même identifie la victime d'un sabotage par son SEUL
      // UnitType (`ClientActionHelper.sabotageWarDefender(…, unitType, …)`), ce qui n'aurait pas de sens si
      // un même héros pouvait occuper deux lineups. (1er jet de ce test : 5 héros répétés 3 fois → « déjà
      // saboté » ne se déclenchait pas, et c'était la mise en scène qui était fausse, pas le serveur.)
      java.util.List<UnitType> pool = new java.util.ArrayList<>();
      for (UnitType u : UnitType.values()) {
        if (u == UnitType.DEFAULT) continue;
        pool.add(u);
        if (pool.size() == 15) break;
      }
      check(pool.size() == 15, "il faut 15 héros distincts pour une défense de guerre, obtenu " + pool.size());
      UnitType[] cast = pool.toArray(new UnitType[0]);
      UnitType heroA = cast[0], heroB = cast[1], heroC = cast[2], heroD = cast[3], heroE = cast[4];
      WarGuildInfo sideA = new WarGuildInfo();
      sideA.guildInfo = ga.info.basicInfo;
      sideA.sabotageCurrency = ServerWarSabotage.DEFAULT_SABOTAGE_CURRENCY;
      sideA.maxBanAmt = 2; sideA.maxProtectAmt = 2;
      sideA.sabotageTypes.addAll(ServerWarSabotage.availableSabotageTypes());
      addMember(sideA, member(1L, ServerWarCars.GARAGE_ORDER.get(0), cast));
      ServerWarCars.rebuildCars(sideA);

      WarGuildInfo sideB = new WarGuildInfo();
      sideB.guildInfo = gb.info.basicInfo;
      addMember(sideB, member(2L, ServerWarCars.GARAGE_ORDER.get(0), cast));
      ServerWarCars.rebuildCars(sideB);

      w.putSide(ga.guildID, sideA); w.putSide(gb.guildID, sideB);

      // ---------------------------------------------------------------------------------------
      // 1. TYPES DISPONIBLES — exactement ceux que le jeu déclare valides.
      // ---------------------------------------------------------------------------------------
      java.util.List<WarSabotageType> types = ServerWarSabotage.availableSabotageTypes();
      check(!types.contains(WarSabotageType.DEFAULT), "DEFAULT n'est pas un sabotage valide");
      check(!types.contains(WarSabotageType.DELAY_ARRIVAL),
          "DELAY_ARRIVAL est explicitement exclu par WarHelper.isValidSabotage");
      for (WarSabotageType t : types) {
        check(com.perblue.heroes.game.logic.WarHelper.isValidSabotage(t),
            t + " ne devrait pas être proposé");
        // Chaque type proposé doit avoir une valeur d'effet dans les données du jeu.
        check(com.perblue.heroes.game.data.war.WarStats.getXValue(t) != 0,
            "le type " + t + " doit porter une valeur X dans war_sabotage_effects.tab");
      }
      System.out.println("[war] " + types.size() + " types de sabotage valides (DEFAULT et DELAY_ARRIVAL exclus)");

      // ---------------------------------------------------------------------------------------
      // 2. COÛT CROISSANT, RECALCULÉ PAR LE SERVEUR.
      // ---------------------------------------------------------------------------------------
      ra.giveResource(ResourceType.WAR_TOKENS, 10_000);
      long before = ra.resourceAmount(ResourceType.WAR_TOKENS);
      WarMemberInfo target = (WarMemberInfo) w.enemySideOf(ga.guildID).members.get(2L);
      check(ServerWarSabotage.sabotageNumber(target) == 1, "aucun sabotage posé → rang 1");
      int cost1 = ServerWarSabotage.nextSabotageCost(target);
      check(cost1 == com.perblue.heroes.game.data.war.WarStats.getSabotageCost(1),
          "le 1er coût doit venir de war_sabotage_cost.tab");

      ServerWarSabotage.SabotageResult r1 = ServerWarSabotage.sabotage(
          w, ga, ra, 2L, heroA, WarSabotageType.REDUCE_HP_PERCENT, now);
      check(r1.ok(), "le 1er sabotage doit passer : " + r1.error);
      check(r1.number == 1 && r1.cost == cost1, "rang/coût du 1er sabotage");
      check(ra.resourceAmount(ResourceType.WAR_TOKENS) == before - cost1,
          "la monnaie doit être débitée (" + cost1 + ")");

      ServerWarSabotage.SabotageResult r2 = ServerWarSabotage.sabotage(
          w, ga, ra, 2L, heroB, WarSabotageType.REDUCE_ARMOR_PERCENT, now);
      check(r2.ok(), "le 2e sabotage doit passer : " + r2.error);
      check(r2.number == 2 && r2.cost > r1.cost,
          "le prix doit MONTER sur la même cible (" + r1.cost + " → " + r2.cost + ")");
      System.out.println("[war] coût croissant sur la même cible : " + r1.cost + " puis " + r2.cost
          + " (recalculé serveur, jamais l'INDEX du client)");

      // Le sabotage est bien posé, et n'est pas rejouable sur le même héros.
      WarGuildInfo bAfter = w.enemySideOf(ga.guildID);
      WarMemberInfo t2 = (WarMemberInfo) bAfter.members.get(2L);
      int sabotaged = 0;
      for (Object ol : t2.defenses) {
        for (Object oh : ((WarLineupSummary) ol).heroes) {
          WarHeroSummary h = (WarHeroSummary) oh;
          if (com.perblue.heroes.game.logic.WarHelper.isValidSabotage(h.sabotage)) {
            sabotaged++;
            check(h.sabotagedByUser == ra.userID, "le sabotage doit porter son auteur");
          }
        }
      }
      check(sabotaged == 2, "2 héros sabotés, obtenu " + sabotaged);
      // Le compteur du saboteur monte.
      check(((WarMemberInfo) w.sideOf(ga.guildID).members.get(1L)).sabotagesDealt == 2,
          "sabotagesDealt doit suivre");

      // ---------------------------------------------------------------------------------------
      // 3. REFUS : hors phase, cible inconnue, monnaie insuffisante.
      // ---------------------------------------------------------------------------------------
      w.state = WarSummaryState.ACTIVE;
      check(!ServerWarSabotage.sabotage(w, ga, ra, 2L, heroC,
          WarSabotageType.REDUCE_HP_PERCENT, now).ok(), "pas de sabotage en phase de bataille");
      w.state = WarSummaryState.SABOTAGE;
      check(!ServerWarSabotage.sabotage(w, ga, ra, 999L, heroC,
          WarSabotageType.REDUCE_HP_PERCENT, now).ok(), "cible inconnue refusée");
      check(!ServerWarSabotage.sabotage(w, ga, ra, 2L, heroA,
          WarSabotageType.REDUCE_HP_PERCENT, now).ok(), "un héros déjà saboté n'est pas re-sabotable");
      check(!ServerWarSabotage.sabotage(w, ga, ra, 2L, heroC,
          WarSabotageType.DELAY_ARRIVAL, now).ok(), "un type invalide est refusé");

      // Monnaie épuisée.
      ServerUser pauvre = ServerUser.newPlayer(3L, 1);
      pauvre.basicInfo().teamLevel = reqTL;
      pauvre.joinGuildAs(ga.guildID, GuildRole.MEMBER);
      WarGuildInfo aSide = w.sideOf(ga.guildID);
      addMember(aSide, member(3L, ServerWarCars.GARAGE_ORDER.get(0), cast));
      w.putSide(ga.guildID, aSide);
      ServerWarSabotage.SabotageResult broke = ServerWarSabotage.sabotage(
          w, ga, pauvre, 2L, heroC, WarSabotageType.REDUCE_HP_PERCENT, now);
      check(!broke.ok(), "sans monnaie, le sabotage doit être refusé");
      System.out.println("[war] refus corrects : hors phase, cible inconnue, déjà saboté, type invalide, "
          + "monnaie insuffisante (" + broke.error + ")");

      // ---------------------------------------------------------------------------------------
      // 4. FRAIS COMPTABILISÉS PAR JOUEUR (pour le remboursement au perdant).
      // ---------------------------------------------------------------------------------------
      check(w.totalSabotageFees(ga.guildID) == r1.cost + r2.cost,
          "le total des frais doit valoir la somme débitée");
      check(w.sabotageFeesOf(ga.guildID).get(ra.userID) == r1.cost + r2.cost,
          "les frais doivent être imputés au JOUEUR qui a payé");
      check(w.totalSabotageFees(gb.guildID) == 0, "l'autre camp n'a rien dépensé");
      System.out.println("[war] frais de sabotage : " + w.totalSabotageFees(ga.guildID)
          + " imputés au joueur " + ra.userID + " (base du remboursement en cas de défaite)");

      // ---------------------------------------------------------------------------------------
      // 5. BANS / PROTECTIONS — règles de tryEditWarBanProtect.
      // ---------------------------------------------------------------------------------------
      // Un MEMBER n'a pas le droit (canEditWarBanProtect).
      check(!com.perblue.heroes.game.logic.GuildHelper.canEditWarBanProtect(GuildRole.MEMBER),
          "témoin : un MEMBER ne peut pas éditer les bans");
      check(ServerWarSabotage.editBanProtect(w, ga, pauvre,
          Arrays.asList(heroA), true, now) != null, "un MEMBER doit être refusé");

      // Le chef le peut, dans la limite de maxBanAmt.
      check(ServerWarSabotage.editBanProtect(w, ga, ra,
          Arrays.asList(heroA, heroC, heroE), true, now) != null,
          "3 bans pour un maximum de 2 doit être refusé");
      check(ServerWarSabotage.editBanProtect(w, ga, ra,
          Arrays.asList(heroA, heroC), true, now) == null,
          "2 bans dans la limite doivent passer");
      check(w.sideOf(ga.guildID).bannedHeroes.size() == 2, "les bans doivent être enregistrés");

      // Cooldown : un héros en cooldown ne peut pas être banni.
      aSide = w.sideOf(ga.guildID);
      aSide.banCooldowns.put(heroD, 1);
      w.putSide(ga.guildID, aSide);
      check(ServerWarSabotage.editBanProtect(w, ga, ra,
          Arrays.asList(heroD), true, now) != null,
          "un héros en cooldown de ban doit être refusé");
      System.out.println("[war] bans : MEMBER refusé, 3>max refusé, 2 acceptés, cooldown respecté");

      // Fenêtre de ban : au-delà de extraStateEndTime, c'est fermé.
      check(ServerWarSabotage.editBanProtect(w, ga, ra, Arrays.asList(heroA), true,
          w.extraStateEndTime + 1) != null, "la fenêtre de ban doit se refermer");
      // Les protections restent possibles pendant le jour 1.
      check(ServerWarSabotage.editBanProtect(w, ga, ra,
          Arrays.asList(heroE), false, w.extraStateEndTime + 1) == null,
          "les protections ne sont pas limitées à la fenêtre de ban");
      check(w.sideOf(ga.guildID).protectedHeroes.size() == 1, "la protection doit être enregistrée");
      System.out.println("[war] fenêtre : ban fermé après "
          + (com.perblue.heroes.game.data.war.WarStats.getSabotageBanPhaseLenght() / 3600000)
          + " h, protections encore ouvertes");

      // ---------------------------------------------------------------------------------------
      // 6. SPARS — quota du perk WAR_SPARS, sans consommer d'attaque.
      // ---------------------------------------------------------------------------------------
      long quota = ServerWarSabotage.sparQuota(ga);
      System.out.println("[war] quota de spars (perk WAR_SPARS, guilde sans perk) : " + quota);
      String sparErr = ServerWarSabotage.spar(w, ga, ra, 2L);
      if (quota <= 0) {
        check(sparErr != null, "sans perk WAR_SPARS, le quota est nul → refus attendu");
        System.out.println("[war] spar refusé faute de quota (" + sparErr + ") — fidèle : "
            + "le perk WAR_SPARS n'est pas acheté");
      } else {
        check(sparErr == null, "le spar doit passer tant que le quota le permet : " + sparErr);
        check(((WarMemberInfo) w.sideOf(ga.guildID).members.get(1L)).sparsDealt == 1,
            "sparsDealt doit monter");
      }
      // Un spar ne consomme JAMAIS d'attaque de guerre.
      check(ServerWarAttack.attacksUsed(w, ra) == 0,
          "« Spars do not consume your War attack » : le compteur d'attaques doit rester à 0");
      check(ServerWarSabotage.spar(w, ga, ra, 999L) != null, "une cible hors guerre doit être refusée");

      // ---------------------------------------------------------------------------------------
      // 7. PERSISTANCE (v3 : frais de sabotage inclus).
      // ---------------------------------------------------------------------------------------
      store.saveWar(w);
      ServerWarState rw = store.loadWar(1, w.warID);
      check(rw.totalSabotageFees(ga.guildID) == r1.cost + r2.cost, "les frais doivent persister");
      check(rw.sabotageFeesOf(ga.guildID).get(ra.userID) == r1.cost + r2.cost,
          "l'imputation par joueur doit persister");
      WarMemberInfo rt = (WarMemberInfo) rw.enemySideOf(ga.guildID).members.get(2L);
      check(ServerWarSabotage.sabotageNumber(rt) == 3,
          "les sabotages posés doivent persister (rang suivant = 3), obtenu "
              + ServerWarSabotage.sabotageNumber(rt));
      check(rw.sideOf(ga.guildID).bannedHeroes.size() == 2, "les bans doivent persister");
      check(rw.sideOf(ga.guildID).protectedHeroes.size() == 1, "les protections doivent persister");
      System.out.println("[war] round-trip DB : sabotages, bans, protections et frais persistés");

      System.out.println("WAR SABOTAGE TEST OK");
    }
  }
}
