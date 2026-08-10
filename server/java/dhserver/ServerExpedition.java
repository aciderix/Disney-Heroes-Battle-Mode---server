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
  private static final int BASE_ENEMY_LEVEL = 140;   // calibration documentée (base ; +extra levels par difficulté)

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
    run.defenders = buildDefenders(user.getShardID(), expeditionID, difficulty);
    if (desiredWard != null) run.weeklyWards = new ArrayList<Object>(desiredWard);

    su.setExpeditionRun(run);
    su.setExpeditionIDPersisted(expeditionID);
    // Compteurs/flags du jeu mutés par enableDifficulty/charge → re-sync vers le wire.
    su.resyncCounts(user);
    su.resyncDiamonds(user);
    return run;
  }

  /** Génère les {@code nodeCount()} nœuds ({@code DefenderData{user, lineup}}) — ennemis tirés du pool EASY_HEROES,
   *  DÉTERMINISTES par {@code (expeditionID, node)}, au niveau {@code BASE + getExtraEnemyLevels(difficulty)}. */
  @SuppressWarnings("unchecked")
  private static List<Object> buildDefenders(int shardID, long expeditionID, int difficulty) {
    List<Object> defenders = new ArrayList<>();
    List<UnitType> pool = easyPool();
    int nodes = nodeCount();
    int extra = 0;
    try { extra = com.perblue.heroes.game.data.expedition.ExpeditionStats.getExtraEnemyLevels(difficulty); }
    catch (Throwable t) { /* défaut 0 */ }
    int level = Math.max(1, BASE_ENEMY_LEVEL + extra);
    for (int node = 0; node < nodes; node++) {
      DefenderData d = new DefenderData();
      d.user = new BasicUserInfo();
      d.user.name = "Expedition";
      d.lineup = new ArrayList<Object>();
      try {
        User bot = botUser(shardID, expeditionID * 1000L + node);
        java.util.Random rng = new java.util.Random(expeditionID * 131L + node);
        List<UnitType> shuffled = new ArrayList<>(pool);
        java.util.Collections.shuffle(shuffled, rng);
        int added = 0;
        for (UnitType t : shuffled) {
          if (added >= HEROES_PER_NODE) break;
          try {
            if (bot.getHero(t) == null) bot.createAndAddHero(t, Rarity.ORANGE, level, 1, new String[]{"exp"});
            ((List<Object>) d.lineup).add(ClientNetworkStateConverter.getHeroData((UnitData) bot.getHero(t)));
            added++;
          } catch (Throwable perHero) { /* héros refusé → suivant */ }
        }
      } catch (Throwable t) { System.out.println("[expedition] node " + node + " defenders: " + t); }
      defenders.add(d);
    }
    return defenders;
  }

  /** Utilisateur synthétique (bot) pour fabriquer des {@code HeroData} ennemis (patron {@code ServerArena.botUser}). */
  private static User botUser(int shardID, long id) {
    UserInfo ui = new UserInfo();
    ui.shardID = shardID;
    ui.basicInfo = new BasicUserInfo();
    ui.basicInfo.teamLevel = 100;
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
      com.perblue.heroes.network.messages.NodeReward nr = new com.perblue.heroes.network.messages.NodeReward();
      nr.rewardDrops = new ArrayList<>();
      // (1) OR de base du nœud = getGold(node, L=teamLevel) × getGoldMultiplier(difficulty) (données du jeu §4) —
      //     crédité DIRECTEMENT (setResource, patron campagne/alchimie). giveLoot ne crédite PAS un RewardDrop GOLD.
      int baseGold = com.perblue.heroes.game.data.expedition.ExpeditionStats.getGold(node, user.getTeamLevel());
      float goldMult = com.perblue.heroes.game.data.expedition.ExpeditionStats.getGoldMultiplier(difficulty);
      goldGiven = (long) (baseGold * goldMult);
      if (goldGiven > 0) {
        user.setResource(com.perblue.heroes.network.messages.ResourceType.GOLD,
            user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD) + goldGiven, "expedition");
        com.perblue.heroes.network.messages.RewardDrop g = new com.perblue.heroes.network.messages.RewardDrop();
        g.resourceType = com.perblue.heroes.network.messages.ResourceType.GOLD;
        g.quantity = goldGiven;
        ((java.util.List<Object>) nr.rewardDrops).add(g);   // pour l'affichage de la récompense de nœud
      }
      // (2) Drops d'OBJETS du nœud = rollExpeditionDrops (table de butin du jeu §4) → crédités via RewardHelper.
      java.util.Random rng = new java.util.Random(su.expeditionIDPersisted() * 977L + node);
      java.util.List wards = run.weeklyWards != null ? run.weeklyWards : new ArrayList<>();
      java.util.List drops = com.perblue.heroes.game.data.expedition.ExpeditionStats.rollExpeditionDrops(
          user, rng, difficulty, node, wards, snap);
      if (drops != null && !drops.isEmpty()) {
        com.perblue.heroes.game.logic.RewardHelper.giveRewards(
            user, drops, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "expedition");
        ((java.util.List<Object>) nr.rewardDrops).addAll(drops);
      }
      if (run.nodeRewards == null) run.nodeRewards = new ArrayList<>();
      ((java.util.List<Object>) run.nodeRewards).add(nr);
    } catch (Throwable t) { System.out.println("[expedition] récompense de nœud: " + t); }

    run.nodesDefeated = m.nodeIndex + 1;
    run.totalGoldEarned += goldGiven;
    // epic chips CLIENT-reportés (PARTIEL §4bis) : enregistrés pour l'affichage (crédit hero-chip = incr. reward).
    if (m.epicChips != null && m.epicChips.quantity > 0) {
      if (run.droppedEpicChips == null) run.droppedEpicChips = new ArrayList<>();
      ((java.util.List<Object>) run.droppedEpicChips).add(m.epicChips);
    }

    su.setExpeditionRun(run);
    su.resyncDiamonds(user);
    su.resyncHeroes(user);
    su.resyncCounts(user);
    System.out.println("[expedition] nœud " + node + " VAINCU → nodesDefeated=" + run.nodesDefeated
        + ", or +" + goldGiven + " (total " + run.totalGoldEarned + ")");
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
    r.weeklyWardInfo = new ExpeditionWeeklyInfo();   // wards réels = incrément 5 (baseline non-null sûre)
    return r;
  }

  /** Réponse après un {@code ResetExpedition} (run fraîchement généré) : {@code wasReset=true}. */
  public static GetExpeditionResponse resetResponse(ServerUser su, ExpeditionRunData run) {
    GetExpeditionResponse r = new GetExpeditionResponse();
    r.expeditionID = su.expeditionIDPersisted();
    r.currentExpedition = run != null ? run : new ExpeditionRunData();
    r.wasReset = true;
    r.weeklyWardInfo = new ExpeditionWeeklyInfo();
    return r;
  }
}
