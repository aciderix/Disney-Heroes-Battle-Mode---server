package dhserver;

import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.BasicUserInfo;
import com.perblue.heroes.network.messages.DefenderData;
import com.perblue.heroes.network.messages.ExpeditionRunData;
import com.perblue.heroes.network.messages.ExpeditionWeeklyInfo;
import com.perblue.heroes.network.messages.GetExpeditionResponse;
import com.perblue.heroes.network.messages.HeroData;
import com.perblue.heroes.network.messages.IndividualUserExtra;
import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.UserExtra;
import com.perblue.heroes.network.messages.UserInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * EXPEDITION (#72) — mode « Expédition » (solo progression + combat). Cf. docs/EXPEDITION.md.
 *
 * <p><b>Incrément 1</b> — handler {@code GetExpedition}→{@code GetExpeditionResponse} (rafraîchissement d'un run actif).
 * <p><b>Incrément 2</b> — {@code ResetExpedition} : GÉNÈRE le run (le vrai point d'entrée en jeu ; l'écran reste sur
 * « SCANNING CITY MAP » tant que le serveur ne renvoie pas le run). Le RUN ({@code ExpeditionRunData}) est un état
 * SERVEUR-autoritatif persisté à part (blob {@code expedition}) — seul {@code expeditionID} vit dans l'extra.
 *
 * <p><b>Génération des nœuds/defenders (§3/§4bis, patron Arène/Surge)</b> : le builder d'origine est BACKEND (absent
 * du jar client, comme {@code ArenaInfo}). On génère donc les {@code defenders} côté serveur à partir des DONNÉES du
 * jeu (aucune invention des valeurs §4) : le POOL d'ennemis vient de {@code expedition_easy_heroes.tab}, le nombre de
 * nœuds de {@code expedition_nodes.tab}, l'échelle de {@code ExpeditionStats.getExtraEnemyLevels(difficulty)}. La
 * COMPOSITION exacte (5 héros/nœud, tirés de façon DÉTERMINISTE via {@code expeditionID}) + le niveau de base sont une
 * CALIBRATION serveur documentée (comme les bots d'arène) — le combat étant client-autoritatif ({@code ExpeditionAttack}),
 * l'issue reste fidèle. À affiner en incr. 3 (combat) si la vérif en jeu montre un écart de puissance.
 */
public final class ServerExpedition {

  private ServerExpedition() {}

  private static final int HEROES_PER_NODE = 5;

  private static volatile List<UnitType> EASY_POOL;   // pool d'ennemis (expedition_easy_heroes.tab)
  private static volatile int NODE_COUNT = -1;        // nombre de nœuds (expedition_nodes.tab)

  /** Pool d'ennemis lu depuis la donnée EXTRAITE du jeu {@code expedition_easy_heroes.tab} (§4, jamais inventé). */
  private static List<UnitType> easyPool() {
    if (EASY_POOL != null) return EASY_POOL;
    List<UnitType> pool = new ArrayList<>();
    try {
      java.nio.file.Path p = java.nio.file.Paths.get(
          System.getProperty("dh.stats", "game-data/stats"), "expedition_easy_heroes.tab");
      for (String line : java.nio.file.Files.readAllLines(p)) {
        String t = line.trim();
        if (!t.startsWith(">")) continue;             // lignes de valeur = « >\t<UNITTYPE> »
        String name = t.substring(1).trim();
        try { pool.add(UnitType.valueOf(name)); } catch (Throwable ignore) { /* type inconnu → sauté */ }
      }
    } catch (Throwable t) { System.out.println("[expedition] lecture easy_heroes: " + t); }
    EASY_POOL = pool;
    return pool;
  }

  /** Nombre de nœuds lu depuis {@code expedition_nodes.tab} (§4). */
  private static int nodeCount() {
    if (NODE_COUNT >= 0) return NODE_COUNT;
    int n = 0;
    try {
      java.nio.file.Path p = java.nio.file.Paths.get(
          System.getProperty("dh.stats", "game-data/stats"), "expedition_nodes.tab");
      for (String line : java.nio.file.Files.readAllLines(p))
        if (line.matches("^[0-9].*")) n++;            // lignes de nœud = « <index>\t… »
    } catch (Throwable t) { System.out.println("[expedition] lecture nodes: " + t); }
    NODE_COUNT = n > 0 ? n : 15;
    return NODE_COUNT;
  }

  /**
   * {@code ResetExpedition} — génère (ou régénère) le run d'expédition à la difficulté choisie et le persiste sur
   * {@code su}. Renvoie le run généré, ou {@code null} si refusé (économie de reset). L'appelant persiste + répond.
   */
  public static ExpeditionRunData resetRun(ServerUser su, int difficulty, List<?> desiredWard, boolean firstEver) {
    ServerContext.init();
    User user = su.gameUser();
    if (difficulty < 1) difficulty = 1;
    // Économie du jeu (§3) : le 1er reset est GRATUIT ; les suivants passent par chargeForReset (lève si refusé).
    if (!firstEver) {
      try {
        com.perblue.heroes.game.logic.ExpeditionHelper.chargeForReset(
            user, difficulty, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE, false);
      } catch (Throwable t) {
        System.out.println("[expedition] reset refusé (diff=" + difficulty + ") : " + t);
        return null;
      }
    }
    try { com.perblue.heroes.game.logic.ExpeditionHelper.enableDifficulty(user, difficulty); }
    catch (Throwable t) { /* non fatal */ }

    long expeditionID = su.expeditionIDPersisted() + 1;   // nouveau run (graine de la carte côté client)
    ExpeditionRunData run = new ExpeditionRunData();
    run.difficulty = difficulty;
    run.nodesDefeated = 0;
    run.chestsOpened = 0;
    run.ticketsEarned = 0;
    run.totalGoldEarned = 0;
    // Étoiles des ennemis = MAX de l'ère de contenu (donnée du jeu §4, jamais inventé ; TL100/R102 → 6).
    int enemyStars = 1;
    try { enemyStars = Math.max(1, com.perblue.heroes.game.data.unit.UnitStats.getMaxStars(user)); }
    catch (Throwable t) { /* défaut 1 */ }
    // Niveau de BASE des ennemis = niveau d'équipe du joueur (calibration serveur documentée ; le CLIENT ajoute
    // ExpeditionStats.getExtraEnemyLevels(difficulty) au combat — cf. ExpeditionAttackScreen.createStageDefenders —
    // donc le serveur envoie la BASE, jamais base+extra). Fidèle à la progression, valide pour createAndAddHero.
    int baseLevel = Math.max(1, user.getTeamLevel());
    run.defenders = buildDefenders(user.getShardID(), expeditionID, enemyStars, baseLevel);
    // Récompenses PROSPECTIVES par nœud = méthode du jeu ExpeditionHelper.createRewards (§3) : 15 NodeReward{OR}. Le
    // client LIT nodeRewards.get(nodeIndex) au combat (createStageDefenders) → doit être peuplé dès le reset (sinon
    // IndexOutOfBounds à l'ouverture du 1er nœud). Enrichi des objets (rollExpeditionDrops) au crédit du nœud (recordAttack).
    try {
      run.nodeRewards = com.perblue.heroes.game.logic.ExpeditionHelper.createRewards(user);
    } catch (Throwable t) { System.out.println("[expedition] createRewards: " + t); run.nodeRewards = new ArrayList<>(); }
    if (desiredWard != null) run.weeklyWards = new ArrayList<Object>(desiredWard);

    su.setExpeditionRun(run);
    su.setExpeditionIDPersisted(expeditionID);
    // Compteurs/flags du jeu mutés par enableDifficulty/charge → re-sync vers le wire.
    su.resyncCounts(user);
    su.resyncDiamonds(user);
    return run;
  }

  /** Génère les {@code nodeCount()} nœuds ({@code DefenderData{user, lineup}}) — ennemis tirés du pool EASY_HEROES,
   *  DÉTERMINISTES par {@code (expeditionID, node)}, au niveau de BASE {@code level} et à {@code stars} étoiles (bornés
   *  aux valeurs valides de l'ère, §4). Le CLIENT ajoute {@code getExtraEnemyLevels(difficulty)} au combat.
   *  ⚠️ {@code createAndAddHero(type, rarity, ÉTOILES, NIVEAU)} : l'ordre des deux entiers est (étoiles, niveau) — cf.
   *  {@code ServerUser.grantHero} (relevé au bytecode : {@code createUnitData} fait {@code setStars(a)}/{@code setLevel(b)}).
   *  Le bot est à un {@code teamLevel} ≥ {@code level} pour que {@code createAndAddHero} n'écrête pas le niveau. */
  @SuppressWarnings("unchecked")
  private static List<Object> buildDefenders(int shardID, long expeditionID, int stars, int level) {
    List<Object> defenders = new ArrayList<>();
    List<UnitType> pool = easyPool();
    int nodes = nodeCount();
    for (int node = 0; node < nodes; node++) {
      DefenderData d = new DefenderData();
      d.user = new BasicUserInfo();
      d.user.name = "Expedition";
      d.lineup = new ArrayList<Object>();
      try {
        User bot = botUser(shardID, expeditionID * 1000L + node, level);
        java.util.Random rng = new java.util.Random(expeditionID * 131L + node);
        List<UnitType> shuffled = new ArrayList<>(pool);
        java.util.Collections.shuffle(shuffled, rng);
        int added = 0;
        for (UnitType t : shuffled) {
          if (added >= HEROES_PER_NODE) break;
          try {
            if (bot.getHero(t) == null) bot.createAndAddHero(t, Rarity.ORANGE, stars, level, new String[]{"exp"});
            ((List<Object>) d.lineup).add(ClientNetworkStateConverter.getHeroData((UnitData) bot.getHero(t)));
            added++;
          } catch (Throwable perHero) { /* héros refusé → suivant */ }
        }
      } catch (Throwable t) { System.out.println("[expedition] node " + node + " defenders: " + t); }
      defenders.add(d);
    }
    return defenders;
  }

  /** Utilisateur synthétique (bot) pour fabriquer des {@code HeroData} ennemis (patron {@code ServerArena.botUser}).
   *  {@code teamLevel} ≥ niveau ennemi visé pour que {@code createAndAddHero} n'écrête pas ({@code getMaxHeroLevel}). */
  private static User botUser(int shardID, long id, int teamLevel) {
    UserInfo ui = new UserInfo();
    ui.shardID = shardID;
    ui.basicInfo = new BasicUserInfo();
    ui.basicInfo.teamLevel = Math.max(1, teamLevel);
    User bot = ClientNetworkStateConverter.getUser(ui, new UserExtra(), "expbot");
    ServerContext.bind(bot, ClientNetworkStateConverter.getIndividualUser(new IndividualUserExtra(), id, 0, "expbot"));
    return bot;
  }

  /**
   * {@code ExpeditionAttack} — issue d'un combat de nœud (client-autoritatif, patron {@code CampaignAttack}). Le
   * serveur RÉ-EXÉCUTE l'autorité : anti-triche sur le nœud, puis sur VICTOIRE avance la progression, ROULE la
   * récompense de nœud ({@code ExpeditionStats.rollExpeditionDrops}) et la CRÉDITE ({@code ExpeditionHelper.giveLoot} :
   * or mis à l'échelle de la difficulté + objets), met à jour le run persisté. Renvoie {@code true} si appliqué.
   *
   * <p><b>PARTIEL §4bis</b> : les {@code epicChips} (chip « red hero ») sont CLIENT-reportés (comme le loot #25, graine
   * non rejouée) → enregistrés dans {@code droppedEpicChips} ; les tickets de raid sont suivis ({@code ticketsEarned})
   * mais crédités à la complétion (incr. 4/7). Ne persiste pas (appelant).
   */
  @SuppressWarnings("unchecked")
  public static boolean recordAttack(ServerUser su, com.perblue.heroes.network.messages.ExpeditionAttack m) {
    ServerContext.init();
    if (m == null || m.base == null) return false;
    ExpeditionRunData run = su.expeditionRunOrNull();
    if (run == null) { System.out.println("[expedition] attack refusé : aucun run actif"); return false; }
    // ANTI-TRICHE : on ne peut jouer QUE le nœud courant (= nombre de nœuds déjà vaincus).
    if (m.nodeIndex != run.nodesDefeated) {
      System.out.println("[expedition] attack refusé : nœud " + m.nodeIndex + " ≠ courant " + run.nodesDefeated);
      return false;
    }
    if (m.base.outcome != com.perblue.heroes.network.messages.CombatOutcome.WIN) {
      System.out.println("[expedition] attack nœud " + m.nodeIndex + " : "
          + m.base.outcome + " (pas de progression, run inchangé)");
      return true;   // défaite = pas de progression (le client montre ExpeditionDefeatWindow) ; rien à créditer
    }
    User user = su.gameUser();
    int difficulty = run.difficulty;
    int node = m.nodeIndex;
    com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap =
        com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
    long goldGiven = 0;
    try {
      // Récompense PROSPECTIVE du nœud, pré-générée au reset par ExpeditionHelper.createRewards (OR ; §3). On l'ENRICHIT
      // des objets tirés par la table du jeu (rollExpeditionDrops) puis on CRÉDITE le tout via la méthode du jeu
      // ExpeditionHelper.giveLoot(user, nodeReward, node, difficulty, snap) — qui applique modifyGoldForDifficulty à
      // l'OR et donne objets/tickets. Zéro crédit à la main (§3). nodeRewards[node] reste le record de ce nœud.
      com.perblue.heroes.network.messages.NodeReward nr;
      if (run.nodeRewards != null && node < run.nodeRewards.size()) {
        nr = (com.perblue.heroes.network.messages.NodeReward) run.nodeRewards.get(node);
      } else {   // run legacy sans nodeRewards pré-générés → repli : reward gonflé à la volée
        nr = new com.perblue.heroes.network.messages.NodeReward();
        nr.rewardDrops = new ArrayList<>();
        if (run.nodeRewards == null) run.nodeRewards = new ArrayList<>();
        while (run.nodeRewards.size() <= node) ((java.util.List<Object>) run.nodeRewards).add(nr);
      }
      if (nr.rewardDrops == null) nr.rewardDrops = new ArrayList<>();
      // Objets du nœud (table de butin du jeu §4), déterministes par (expeditionID, node).
      java.util.Random rng = new java.util.Random(su.expeditionIDPersisted() * 977L + node);
      java.util.List wards = run.weeklyWards != null ? run.weeklyWards : new ArrayList<>();
      java.util.List drops = com.perblue.heroes.game.data.expedition.ExpeditionStats.rollExpeditionDrops(
          user, rng, difficulty, node, wards, snap);
      if (drops != null && !drops.isEmpty()) ((java.util.List<Object>) nr.rewardDrops).addAll(drops);
      long goldBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
      com.perblue.heroes.game.logic.ExpeditionHelper.giveLoot(user, nr, node, difficulty, snap);   // crédit AUTORITAIRE (§3)
      goldGiven = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD) - goldBefore;
    } catch (Throwable t) { System.out.println("[expedition] récompense de nœud: " + t); }

    run.nodesDefeated = m.nodeIndex + 1;
    run.totalGoldEarned += goldGiven;
    // epic chips CLIENT-reportés (PARTIEL §4bis) : enregistrés pour l'affichage (crédit hero-chip = incr. reward).
    if (m.epicChips != null && m.epicChips.quantity > 0) {
      if (run.droppedEpicChips == null) run.droppedEpicChips = new ArrayList<>();
      ((java.util.List<Object>) run.droppedEpicChips).add(m.epicChips);
    }
    // PROGRESSION DE DIFFICULTÉ : au dernier nœud (run complet), le jeu ACTIVE la difficulté suivante (mirroir
    // EXACT du client ExpeditionAttackScreen : `if (nodesDefeated >= 15) enableDifficulty(user, difficulty+1)`).
    // Débloque la difficulté supérieure ET rend la difficulté courante RAIDABLE (isDifficultyRaidable, incr. 4).
    if (run.nodesDefeated >= nodeCount()) {
      try { com.perblue.heroes.game.logic.ExpeditionHelper.enableDifficulty(user, difficulty + 1); }
      catch (Throwable t) { System.out.println("[expedition] enableDifficulty(" + (difficulty + 1) + "): " + t); }
    }

    su.setExpeditionRun(run);
    su.resyncDiamonds(user);
    su.resyncHeroes(user);
    su.resyncCounts(user);
    System.out.println("[expedition] nœud " + node + " VAINCU → nodesDefeated=" + run.nodesDefeated
        + ", or +" + goldGiven + " (total " + run.totalGoldEarned + ")"
        + (run.nodesDefeated >= nodeCount() ? " [EXPÉDITION COMPLÈTE → diff " + (difficulty + 1) + " activée]" : ""));
    return true;
  }

  /**
   * {@code ExpeditionRaid} — RAID (saute le combat, complète TOUTE l'expédition d'un coup). Client-autoritatif : le
   * client exécute {@code doRaid} localement (se crédite le butin), avance son run et envoie {@code ExpeditionRaid{
   * rewards, difficulty}}. Le serveur RÉ-EXÉCUTE l'autorité via la MÊME méthode du jeu (§3)
   * {@code ExpeditionHelper.doRaid(user, difficulty, 1, snap, finisher, [])} : gate {@code isDifficultyRaidable}
   * (anti-triche : refuse si la difficulté n'a pas été débloquée), DÉBITE le coût en tickets de raid
   * ({@code getRaidCost}×{@code getRaidTicketType}, lève {@code DONT_HAVE_ITEM} si insuffisant → anti-triche),
   * {@code chargeForReset}, crédite les récompenses de TOUS les nœuds ({@code createRewards} + drops/epic chips roulés),
   * {@code incDailyUses}. Le {@code finisher} marque notre run persisté COMPLET. Renvoie {@code true} si appliqué.
   */
  public static boolean recordRaid(ServerUser su, com.perblue.heroes.network.messages.ExpeditionRaid m) {
    ServerContext.init();
    if (m == null) return false;
    ExpeditionRunData run = su.expeditionRunOrNull();
    if (run == null) { System.out.println("[expedition] raid refusé : aucun run actif"); return false; }
    User user = su.gameUser();
    final int difficulty = m.difficulty > 0 ? m.difficulty : run.difficulty;
    final int nodes = nodeCount();
    com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap =
        com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
    long goldBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
    try {
      // Finisher : marque le run COMPLET côté serveur (le client fait de même sur son ExpeditionClientData).
      com.perblue.heroes.game.logic.ExpeditionHelper.ExpeditionFinisher finisher =
          (numNodes, cost) -> { run.nodesDefeated = nodes; };
      // doRaid(user, difficulté, nodesDefeated, snap, finisher, rewardsClient) — args EXACTS du client
      // (doRaidFromClient) : 3e arg = progression courante (0 = run frais → pas de check reset/chargeForReset).
      // 6e arg = liste des récompenses du client à COMPARER (anti-tamper → INVALID_LOOT si écart). Le client passe
      // NULL (aload 5 ifnull → saute compareDrops) → le serveur ROULE et CRÉDITE son PROPRE butin autoritatif, sans
      // rejet faux sur divergence RNG (même choix que le loot de campagne #25/§4bis). On passe donc null aussi.
      // Lève ClientErrorCodeException (anti-triche) si non raidable / tickets insuffisants → on refuse proprement.
      com.perblue.heroes.game.logic.ExpeditionHelper.doRaid(
          user, difficulty, run.nodesDefeated, snap, finisher, null);
    } catch (Throwable t) {
      // doRaid lève ClientErrorCodeException (anti-triche : non raidable / tickets insuffisants / resets épuisés).
      boolean antiCheat = t instanceof com.perblue.heroes.ClientErrorCodeException;
      System.out.println("[expedition] raid " + (antiCheat ? "REFUSÉ (anti-triche)" : "échec") + " : " + t);
      return false;
    }
    long goldGiven = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD) - goldBefore;
    run.nodesDefeated = nodes;
    run.totalGoldEarned += goldGiven;
    su.setExpeditionRun(run);
    su.resyncDiamonds(user);
    su.resyncHeroes(user);
    su.resyncCounts(user);
    System.out.println("[expedition] RAID diff=" + difficulty + " → expédition complète (nodesDefeated=" + nodes
        + "), or +" + goldGiven);
    return true;
  }

  /**
   * Réponse à {@code GetExpedition} : renvoie le run PERSISTÉ du joueur (rafraîchissement) ou, à défaut, un run vide
   * (le client enverra {@code ResetExpedition} pour en générer un). Ne persiste pas (lecture seule).
   */
  public static GetExpeditionResponse response(ServerUser su) {
    ServerContext.init();
    GetExpeditionResponse r = new GetExpeditionResponse();
    r.expeditionID = su.expeditionIDPersisted();
    ExpeditionRunData run = su.expeditionRunOrNull();
    // Le codec écrit currentExpedition.writeSingle SANS garde null → non-null obligatoire.
    r.currentExpedition = run != null ? run : new ExpeditionRunData();
    r.wasReset = false;
    r.weeklyWardInfo = weeklyWardInfo(com.perblue.heroes.util.TimeUtil.serverTimeNow());   // incr. 5
    return r;
  }

  // --- EXPEDITION #72 incr. 5 : WARDS HEBDOMADAIRES ------------------------------------------------------------
  // Les wards (CombatModifier) sont des MODIFICATEURS DE COMBAT qui tournent CHAQUE SEMAINE et s'appliquent aux
  // difficultés ≥ 3 (HARD/EPIC ; getWardsFor renvoie EMPTY pour diff < 3). Le POOL vient de la DONNÉE du jeu
  // (ExpeditionStats$WardStats.wardsByDifficulty : diff 3 et 4 → 13 wards chacun ; §4). La ROTATION exacte
  // (quel ward chaque semaine) est calculée par le BACKEND (absente du jar client, comme ArenaInfo/Surge) → on la
  // GÉNÈRE serveur, DÉTERMINISTE par l'INDICE DE SEMAINE DU JEU (TimeUtil.getServerWeek) — calibration serveur
  // documentée (patron incr. 2 : pool = donnée du jeu, arrangement = serveur). getWardsFor(info, diff) tranche :
  // diff 3 → currentWards[0] ; diff 4 → currentWards[0..1]. On expose donc 2 wards (HARD partagé + EPIC additionnel).
  private static volatile List<Object> WARD_POOL_3, WARD_POOL_4;

  @SuppressWarnings("unchecked")
  private static void loadWardPools() {
    if (WARD_POOL_3 != null) return;
    List<Object> p3 = new ArrayList<>(), p4 = new ArrayList<>();
    try {
      java.lang.reflect.Field wf = com.perblue.heroes.game.data.expedition.ExpeditionStats.class
          .getDeclaredField("WARD_STATS"); wf.setAccessible(true);
      Object ward = wf.get(null);
      java.lang.reflect.Field wbd = ward.getClass().getDeclaredField("wardsByDifficulty"); wbd.setAccessible(true);
      List<?> byDiff = (List<?>) wbd.get(ward);
      if (byDiff.size() > 3 && byDiff.get(3) != null) p3.addAll((java.util.Collection<Object>) byDiff.get(3));
      if (byDiff.size() > 4 && byDiff.get(4) != null) p4.addAll((java.util.Collection<Object>) byDiff.get(4));
    } catch (Throwable t) { System.out.println("[expedition] lecture pool de wards: " + t); }
    WARD_POOL_3 = p3; WARD_POOL_4 = p4;
  }

  /** Sélection DÉTERMINISTE de 2 wards pour une semaine donnée (index de semaine du jeu) : un ward HARD (pool diff 3)
   *  + un ward EPIC additionnel DIFFÉRENT (pool diff 4). Renvoie une liste vide si le pool est vide. */
  private static List<Object> wardsForWeek(int week) {
    loadWardPools();
    List<Object> out = new ArrayList<>();
    if (WARD_POOL_3 != null && !WARD_POOL_3.isEmpty())
      out.add(WARD_POOL_3.get(Math.floorMod(week, WARD_POOL_3.size())));
    if (WARD_POOL_4 != null && !WARD_POOL_4.isEmpty())
      out.add(WARD_POOL_4.get(Math.floorMod(week + 3, WARD_POOL_4.size())));   // offset → EPIC ≠ HARD
    return out;
  }

  /** Construit l'{@code ExpeditionWeeklyInfo} : wards de la semaine courante + suivante (pool du jeu, rotation par
   *  index de semaine du jeu) + bornes de la semaine ({@code currentWardExpiration}/{@code nextWardStartTime} =
   *  prochain reset hebdomadaire, aligné sur {@code MILLIS_PER_WEEK}). */
  @SuppressWarnings("unchecked")
  public static ExpeditionWeeklyInfo weeklyWardInfo(long now) {
    ExpeditionWeeklyInfo w = new ExpeditionWeeklyInfo();
    int week = com.perblue.heroes.util.TimeUtil.getServerWeek(now);
    w.currentWards = wardsForWeek(week);
    w.nextWards = wardsForWeek(week + 1);
    long weekMs = com.perblue.heroes.util.TimeUtil.MILLIS_PER_WEEK;
    long boundary = weekMs > 0 ? ((now / weekMs) + 1) * weekMs : now;   // prochaine frontière hebdo
    w.currentWardExpiration = boundary;
    w.nextWardStartTime = boundary;
    return w;
  }

  /**
   * Réponse à {@code ResetExpedition} : un {@code ResetExpeditionResponse} (type DÉDIÉ — le client a un handler propre,
   * {@code GameMain.lambda$setupPostClientInfoHandlers$55}, qui fait le NETTOYAGE de reset : {@code clearModePersistentData}
   * /{@code clearMercenaryHero}/{@code clearKoHiredMercenaries} sur les héros, {@code enableDifficulty}, {@code onExpeditionReset},
   * en plus de poser {@code getExpeditionData()} depuis {@code currentExpedition}). Répondre un {@code GetExpeditionResponse}
   * peuplerait bien la carte mais SAUTERAIT ce nettoyage (incorrect sur un 2ᵉ run) → on renvoie le bon type (§3).
   *
   * <p>{@code resetsDone} (compteur de resets) et {@code eventExtraResets} = incrément 6 (économie de reset) : baseline
   * sûre (0 / liste vide) pour le 1ᵉʳ run — {@code firstEverReset} n'en consomme aucun.
   */
  public static com.perblue.heroes.network.messages.ResetExpeditionResponse resetResponse(ServerUser su, ExpeditionRunData run) {
    com.perblue.heroes.network.messages.ResetExpeditionResponse r =
        new com.perblue.heroes.network.messages.ResetExpeditionResponse();
    r.expeditionID = su.expeditionIDPersisted();
    r.currentExpedition = run != null ? run : new ExpeditionRunData();
    r.resetsDone = 0;
    r.eventExtraResets = new ArrayList<>();
    return r;
  }
}
