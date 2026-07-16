package dhserver;

import com.perblue.common.droptable.DropTable;
import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.data.chest.ChestContext;
import com.perblue.heroes.game.data.chest.ChestStats;
import com.perblue.heroes.game.logic.ChestHelper;
import com.perblue.heroes.game.logic.DropConverter;
import com.perblue.heroes.network.messages.Action;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.BuyChests;
import com.perblue.heroes.network.messages.CampaignAttack;
import com.perblue.heroes.network.messages.CampaignLevelStatus;
import com.perblue.heroes.network.messages.CampaignType;
import com.perblue.heroes.network.messages.ChangeTutorialStep;
import com.perblue.heroes.network.messages.ChestType;
import com.perblue.heroes.network.messages.GameMode;
import com.perblue.heroes.game.data.campaign.CampaignLevel;
import com.perblue.heroes.game.logic.CampaignHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import com.perblue.heroes.network.messages.IndividualUserExtra;
import com.perblue.heroes.network.messages.LootResults;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.ServerRollResponse;
import com.perblue.heroes.network.messages.TutorialAct;
import com.perblue.heroes.network.messages.UserExtra;
import com.perblue.heroes.network.messages.UserInfo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

/**
 * État serveur AUTORITAIRE d'un joueur (docs/PRINCIPLES.md §3/§6), détenu comme des <b>objets du
 * jeu</b> : {@link UserInfo} (identité), {@link UserExtra} (héros, ressources, réglages…) et
 * {@link IndividualUserExtra} (tutoriels, quêtes…). La persistance (étape 5) sérialise ces objets
 * en <b>octets wire</b> (identiques au réseau) via {@code writeAll} / {@code MessageFactory} — aucun
 * schéma inventé pour les données du jeu.
 *
 * <p>Le client pilote, le serveur valide/persiste : la progression du tutoriel
 * ({@code individualUserExtra.tutorialActs}) est avancée par {@link ChangeTutorialStep}.
 */
public final class ServerUser {

  public final long userID;
  public final int shardID;

  // État autoritaire = objets du jeu (mutables, persistés en octets wire).
  private final UserInfo userInfo;
  private final UserExtra userExtra;
  private final IndividualUserExtra individualUserExtra;

  private ServerUser(long userID, int shardID,
                     UserInfo userInfo, UserExtra userExtra, IndividualUserExtra individualUserExtra) {
    this.userID = userID;
    this.shardID = shardID;
    this.userInfo = userInfo;
    this.userExtra = userExtra;
    this.individualUserExtra = individualUserExtra;
  }

  /** NOUVEAU joueur : objets du jeu neufs + tutoriels de NEW_USER_ACTS à step 0 (registre du jeu). */
  public static ServerUser newPlayer(long userID, int shardID) {
    UserInfo ui = new UserInfo();                 // tous champs non-null (constructeur du jeu)
    ui.shardID = shardID;
    ui.basicInfo.iD = userID;
    long creation = System.currentTimeMillis();
    ui.basicInfo.creationTime = creation;
    ui.basicInfo.teamLevel = 1;                   // un compte neuf démarre au niveau d'équipe 1
    UserExtra ue = new UserExtra();
    IndividualUserExtra iue = new IndividualUserExtra();
    iue.tutorialActs = NewUserState.newUserTutorialActs();
    ServerUser su = new ServerUser(userID, shardID, ui, ue, iue);
    su.initNewPlayerResources(creation);
    return su;
  }

  /**
   * Initialise les RESSOURCES d'un compte neuf (docs/PRINCIPLES.md §3 « lire & exécuter »). Sans ça,
   * un {@code new IndividualUserExtra()} laisse {@code getLastResourceGenerationTime(...)=0} : le jeu
   * calcule alors la stamina courante = régénération depuis l'ÉPOQUE (1970) → des <b>millions</b>
   * d'énergie affichés (bug « 39,96 M / 120 »). Le serveur autoritatif, comme à la création d'un compte,
   * <b>ancre l'horloge de génération</b> de chaque ressource régénérée à la création et met la
   * <b>stamina au cap</b> du jeu (via {@code UserHelper.getResourceCap} = {@code MAX_STAMINA} de
   * {@code team_levels.tab}, 120 au niveau 1). Valeurs issues de la logique/données du jeu, non inventées.
   */
  private void initNewPlayerResources(long creation) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "newuser");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "newuser");
    ServerContext.bind(user, iu);                 // DH.app requis par setResource/getResourceCap
    // Un compte NEUF démarre chaque ressource régénérée À SON CAP, l'horloge de génération ancrée à la
    // création (docs/PRINCIPLES.md §3 « lire & exécuter » : à l'instant t=création, aucun temps ne s'est
    // écoulé → chaque réserve est pleine). Sans ça, getLastResourceGenerationTime=0 → « génération depuis
    // 1970 » (bug énergie 39,96 M) ET les COFFRES GRATUITS (GOLD_CHEST/SILVER_CHEST, eux aussi des
    // ResourceType qui se régénèrent) restent à 0 → coffre « gratuit dans 1j 23h » indisponible → le clic
    // du tuto n'envoie aucun BuyChests → Frozone jamais accordé → tuto bloqué à l'étape du coffre GOLD.
    // Caps au niveau 1 (team_levels/ressources du jeu, non inventés) : STAMINA=120, GOLD_CHEST=1,
    // SILVER_CHEST=1, SOCIAL_CHEST=1, SKILL_POINTS=50, SOUL_CHEST=0, FRIEND_STAMINA=175, INVASION=80…
    // setResource écrit resources.put + ré-ancre le gen-time ; sa branche battlePassV2 ne concerne QUE
    // les diamants (sûr headless). getResourceCap gère le gating (feature verrouillée → cap 0).
    for (com.perblue.heroes.network.messages.ResourceType rt
        : com.perblue.heroes.network.messages.ResourceType.values()) {
      if (!com.perblue.heroes.game.logic.UserHelper.resourceGenerates(rt)) continue;
      iu.setLastResourceGenerationTime(rt, creation);
      long cap = com.perblue.heroes.game.logic.UserHelper.getResourceCap(rt, user);
      user.setResource(rt, cap, "newuser");
    }

    // ROSTER DE DÉPART : un compte neuf possède déjà des héros AVANT le coffre (fidélité vérifiée sur la
    // vidéo de gameplay, PRINCIPLES §4bis) : **Ralph + Elastigirl** (les héros contrôlés dès l'intro).
    // Frozone arrive ENSUITE via le coffre GOLD. On les crée au même état de base qu'un héros de coffre
    // (WHITE, niveau 1, 1 étoile) via la méthode du jeu User.createAndAddHero (type = observé, état =
    // défaut « nouveau héros » ; aucune valeur inventée). resyncHeroes → wire (userExtra.heroes).
    com.perblue.heroes.network.messages.Rarity white = com.perblue.heroes.network.messages.Rarity.WHITE;
    user.createAndAddHero(com.perblue.heroes.network.messages.UnitType.RALPH, white, 1, 1, new String[]{"new_user"});
    user.createAndAddHero(com.perblue.heroes.network.messages.UnitType.ELASTIGIRL, white, 1, 1, new String[]{"new_user"});
    resyncHeroes(user);
  }

  /** Charge un joueur depuis ses octets wire persistés (round-trip symétrique de {@link #wire}). */
  public static ServerUser fromWire(long userID, int shardID,
                                    byte[] userInfoWire, byte[] userExtraWire, byte[] individualWire) {
    UserInfo ui = read(userInfoWire);
    UserExtra ue = read(userExtraWire);
    IndividualUserExtra iue = read(individualWire);
    return new ServerUser(userID, shardID, ui, ue, iue);
  }

  /**
   * Construit le {@link BootData} reflétant l'état courant. {@code new BootData()} initialise tout
   * (constructeurs du jeu) ; on branche nos objets autoritatifs + les champs transitoires (heure).
   */
  public synchronized BootData bootData() {
    BootData bd = new BootData();
    long now = System.currentTimeMillis();
    bd.serverTime = now;
    bd.currentServer.shardID = shardID;
    userInfo.lastLoginTime = now;
    bd.userInfo = userInfo;
    bd.userExtra = userExtra;
    bd.individualUserExtra = individualUserExtra;
    return bd;
  }

  /**
   * Applique une progression de tutoriel reçue du client ({@code step} absolu ; {@code maxStep} =
   * plus haut pas vu). Renvoie {@code true} si un acte a été mis à jour/ajouté.
   */
  @SuppressWarnings("unchecked")
  public synchronized boolean applyTutorialStep(ChangeTutorialStep m) {
    for (Object o : individualUserExtra.tutorialActs) {
      TutorialAct a = (TutorialAct) o;             // le champ du jeu est une List brute
      if (a.type == m.type) {
        a.step = m.step;
        if (m.step > a.maxStep) a.maxStep = m.step;
        return true;
      }
    }
    int version = NewUserState.latestVersion(m.type);   // registre du jeu, jamais inventé
    if (version < 0) return false;
    TutorialAct a = new TutorialAct();
    a.type = m.type; a.version = version; a.step = m.step; a.maxStep = m.step;
    individualUserExtra.tutorialActs.add(a);
    return true;
  }

  /** Nombre d'actes de tuto (diagnostic). */
  public synchronized int tutorialActCount() { return individualUserExtra.tutorialActs.size(); }

  /** Nombre de héros possédés (diagnostic). */
  public synchronized int heroCount() { return userExtra.heroes.size(); }

  /** Ajoute un héros au roster (état de base WHITE niv.1, comme un compte neuf) via la logique du jeu
   *  ({@code User.createAndAddHero}) + resync wire. Idempotent (ne double pas un héros déjà possédé). */
  public synchronized void grantHero(com.perblue.heroes.network.messages.UnitType type) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "grant");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "grant");
    ServerContext.bind(user, iu);
    if (user.getHero(type) == null)
      user.createAndAddHero(type, com.perblue.heroes.network.messages.Rarity.WHITE, 1, 1, new String[]{"grant"});
    resyncHeroes(user);
  }

  /**
   * Ouvre un coffre en <b>exécutant la logique du jeu</b> (docs/PRINCIPLES.md §3) : construit un
   * {@link User}/{@link IndividualUser} de jeu SUR nos objets wire (références partagées → la plupart
   * des mutations persistent d'elles-mêmes), roule la vraie table ({@code ChestStats}/{@code DropTable}),
   * donne les récompenses ({@code ChestHelper.giveChestRewards}) et met à jour les compteurs. Renvoie le
   * {@link LootResults} à envoyer au client. Les champs gardés hors {@code this.extra} (héros,
   * {@code chestUpgradeXP}) sont re-synchronisés dans le wire.
   */
  @SuppressWarnings("unchecked")
  public synchronized LootResults openChest(BuyChests m) {
    ServerContext.init();
    // User de jeu SUR nos objets wire (getUser fait this.extra = userExtra → mutations partagées).
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "chest");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "chest");
    ServerContext.bind(user, iu);                 // DH.app.getYourIndividualUser() → iu

    ChestType type = m.chestType;
    int count = Math.max(1, m.count);
    DropTable dt = dropTable(type);
    ChestContext ctx = new ChestContext(user);
    ctx.setChestType(type);
    ctx.setCount(count);
    List<?> drops = dt.rollNode("ROOT", ctx, new Random());   // vrai roll de la table du jeu

    LootResults lr = new LootResults();
    lr.lootDrops = new DropConverter(user).convert(drops);
    lr.wasFree = freeChest(user, type, count);
    // Donne les récompenses au joueur autoritatif + remplit heroesUnlocked (bl=true) — code du jeu.
    ChestHelper.giveChestRewards(user, type, lr, null, m.eventID, true, count);
    ChestHelper.updateChestRollCounters(user, type, count, m.usedItem, lr.wasFree, m.hasBulkBonus);
    // Compteurs QUOTIDIENS d'ouverture (limites d'achat + tâches de contest sur don d'objet). Passe par
    // la couche évènements spéciaux (SpecialEventsHelper.helper) — initialisée dans ServerContext (comme
    // GameMain.create()) → plus de NPE. Le don d'objet des coffres (RewardHelper.giveReward →
    // ContestHelper.onItemEarn → getActiveContestsWithTask) fonctionne aussi grâce à cette couche.
    ChestHelper.updateChestCounters(user, type, count, m.usedItem, lr.wasFree, m.hasBulkBonus);

    if (m.roll != null) {                          // réponse de roll attendue par le client
      ServerRollResponse rr = new ServerRollResponse();
      rr.rollId = m.roll.rollId;
      rr.channel = m.roll.channel;
      rr.nextModID = user.getNextModID();
      lr.roll = rr;
    }

    // Re-synchronise les champs hors this.extra vers le wire (persistance complète).
    resyncHeroes(user);
    individualUserExtra.chestUpgradeXP = iu.getChestUpgradeXP();
    return lr;
  }

  /**
   * Enregistre l'issue d'un combat de CAMPAGNE (docs/PRINCIPLES.md §3 : on EXÉCUTE la logique du jeu).
   * Le client joue le combat (client-side, spine unidbg), construit le {@link CampaignAttack} via
   * {@code ClientNetworkStateConverter.getCampaignAttack} (qui roule {@code CampaignHelper.recordOutcome}
   * de SON côté, optimiste) puis l'envoie <b>fire-and-forget</b>. Le serveur AUTORITATIF ré-exécute la
   * MÊME logique du jeu sur son état : {@code CampaignHelper.recordOutcome} <b>consomme la stamina</b>
   * ({@code getStaminaCost}+{@code chargeUser}), <b>donne loot/gold/XP</b> ({@code giveLoot}/{@code giveGold}
   * /{@code giveTeamXP}) et <b>met à jour la progression</b> ({@code ICampaignLevelStatus}). Vérifié par
   * {@code server/smoke/CampaignAttackTest} (énergie -6, or +340, niveau 1-1 à 3★).
   *
   * <p><b>PARTIEL (cf. SHIMS)</b> : (1) {@code SpecialEventSnapshot.NONE} → aucun bonus d'évènement
   * appliqué (headless sans évènement live) ; (2) l'{@code outcome} est CELUI du client (combat
   * client-autoritatif comme dans le jeu d'origine) ; (3) réponse : fire-and-forget (aucun listener
   * client pour {@code CampaignAttack} au bytecode) — à reconfirmer quand le pilote atteindra le combat.
   */
  @SuppressWarnings("unchecked")
  public synchronized void recordCampaignAttack(CampaignAttack m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "campaign");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "campaign");
    ServerContext.bind(user, iu);

    // Normalise les ressources régénérées à leur valeur EFFECTIVE du jeu (régén incluse, capée) + horloge
    // = maintenant, AVANT que recordOutcome ne débite la stamina. Cf. applyEffectiveResourceCap.
    applyEffectiveResourceCap(user, iu);

    CampaignType type = m.campaignType == null ? CampaignType.NORMAL : m.campaignType;
    GameMode mode = type == CampaignType.ELITE ? GameMode.ELITE_CAMPAIGN : GameMode.CAMPAIGN;
    CampaignLevel level = CampaignLevel.of(mode, m.chapter, m.level);

    java.util.List<Object> lootEarned = new java.util.ArrayList<>();
    java.util.List<Object> memoryChanges = new java.util.ArrayList<>();
    // base : attackers/defenders = Collection de AttackLineupSummary, outcome + stars remplis par le client.
    CampaignHelper.recordOutcome(user, user, level, m.base.outcome, m.base.stars, m.stagesCleared,
        lootEarned, memoryChanges, m.base.attackers, m.base.defenders, SpecialEventSnapshot.NONE);

    resyncHeroes(user);   // héros (XP/état) → wire ; stamina/or sont dans this.extra (auto).
    resyncCampaign(iu);   // progression campagne (statuts de niveau) → wire (hors this.extra, comme les héros).
    // Niveau d'équipe : User.teamLevel est un CHAMP de User (hors this.extra) — getUser le lit depuis
    // userInfo.basicInfo.teamLevel, mais setTeamLevel (montée de niveau via giveTeamXP) ne l'écrit QUE sur
    // l'objet User. Sans re-sync vers le wire, le niveau reste BLOQUÉ à 1 : l'équipe « remonte 1→2 » à chaque
    // palier d'XP (18) et ré-accorde STAMINA_GAIN_ON_LEVEL (+20) EN BOUCLE (au lieu de progresser vers le
    // palier suivant). Même schéma que resyncHeroes/resyncCampaign (§6 persistance complète).
    userInfo.basicInfo.teamLevel = user.getTeamLevel();
  }

  /**
   * Normalise chaque ressource régénérée à sa valeur <b>EFFECTIVE du jeu</b> — {@code min(getResource, cap)}
   * — puis fixe la stamina persistée à cette valeur avec l'horloge de génération = maintenant, AVANT qu'un
   * débit ({@code chargeUser} dans {@code recordOutcome}) ne s'applique. <b>Pourquoi</b> : la régén de la
   * <b>content update courante (R102)</b> vaut {@code REGEN_AMOUNT=39 965 650} (vraie donnée
   * {@code stamina_values.tab}, cap dur 79 Md — scaling end-game). Dans {@code updateAndGetResource},
   * STAMINA est dans la branche NON-capée : quand {@code stamina < cap}, un seul intervalle ajoute
   * 39,96 M puis la boucle sort (dépasse le cap) → {@code getResource} renvoie la valeur BRUTE
   * (~39,96 M). Le jeu utilise la valeur <b>effective = min(brut, getResourceCap)</b> à l'affichage/dépense
   * (d'où le client à 120/120). On applique donc la MÊME règle du jeu avant de persister : pour un joueur
   * neuf après régén, {@code min(39,96M, 120)=120} (plein — la régén est bien prise en compte, pas figée),
   * puis le combat débite (120→114). Ancrer l'horloge à maintenant évite que {@code chargeUser} ne
   * re-dépasse (elapsed=0 → pas de régén → débit depuis la valeur effective). Sans ça la stamina persistée
   * serait corrompue à ~39,96 M dès le 2ᵉ combat. Valeurs 100% du jeu, rien d'inventé.
   */
  private void applyEffectiveResourceCap(User user, IndividualUser iu) {
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    for (com.perblue.heroes.network.messages.ResourceType rt
        : com.perblue.heroes.network.messages.ResourceType.values()) {
      if (!com.perblue.heroes.game.logic.UserHelper.resourceGenerates(rt)) continue;
      long cap = com.perblue.heroes.game.logic.UserHelper.getResourceCap(rt, user);
      long effective = Math.min(user.getResource(rt), cap);   // valeur EFFECTIVE (régén incluse, capée)
      iu.setLastResourceGenerationTime(rt, now);
      user.setResource(rt, effective, "effective-cap");
    }
  }

  /**
   * Re-synchronise la PROGRESSION de campagne vers le wire. Les statuts de niveau vivent en mémoire
   * ({@code ClientCampaignLevelStatus} construits depuis {@code individualUserExtra.levelStatuses} au
   * chargement) ; {@code recordOutcome} les mute EN MÉMOIRE mais n'écrit PAS la liste wire → sans ce
   * re-sync, étoiles/complétion sont perdues au round-trip (1-2 ne se débloque jamais). On reconstruit
   * {@code individualUserExtra.levelStatuses} depuis {@code iu.getCampaignLevels()} (champs mappés 1:1 ;
   * {@code lastWinTime} sans getter public → laissé à 0, non requis pour le déblocage). Même schéma que
   * {@code resyncHeroes} (état gardé hors {@code this.extra}). Ensemble fermé, validé par round-trip.
   */
  @SuppressWarnings("unchecked")
  private void resyncCampaign(IndividualUser iu) {
    java.util.List<CampaignLevelStatus> out = new java.util.ArrayList<>();
    for (Object o : iu.getCampaignLevels()) {
      com.perblue.heroes.game.objects.ClientCampaignLevelStatus c =
          (com.perblue.heroes.game.objects.ClientCampaignLevelStatus) o;
      CampaignLevelStatus w = new CampaignLevelStatus();
      w.campaignType = c.getCampaignType();
      w.chapter = c.getChapter();
      w.level = c.getLevel();
      w.stars = c.getStars();
      w.claimedOneTimeReward = c.claimedOneTimeReward();
      w.infectionLevel = c.getInfectionLevel();
      w.reinfectionTime = c.getReinfectionTime();
      w.totalAttempts = c.getTotalAttempts();
      w.totalWins = c.getTotalWins();
      w.winsAtCurrentStars = c.getWinsAtCurrentStars();
      out.add(w);
    }
    individualUserExtra.levelStatuses = out;
  }

  /**
   * Applique une {@link Action} (commande générique du jeu : équiper du gear, promouvoir, vendre…) en
   * exécutant la <b>logique cœur du jeu</b> (docs/PRINCIPLES.md §3), construite sur nos objets wire.
   *
   * <p><b>Pourquoi PAS {@code ActionHelper.doAction} ?</b> {@code doAction} est le chemin <b>CLIENT</b>
   * « appliquer + UI » : il touche {@code GameMain.getScreenManager().getScreen()} (×4) et l'état d'action
   * client ({@code GameStateManager}). Côté serveur il n'y a pas d'écran → on appelle directement les
   * <b>helpers de logique du jeu</b> (comme {@code openChest} utilise {@code ChestStats}/{@code DropTable}
   * et non un flux « acheter un coffre » client). Chaque commande route vers son helper d'origine ;
   * on n'écrit que l'aiguillage, jamais la règle. Renvoie {@code true} si l'action a été appliquée.
   */
  public synchronized boolean applyAction(Action m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "action");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "action");
    ServerContext.bind(user, iu);
    boolean applied;
    try {
      applied = applyCommand(m, user);
    } catch (Throwable t) {
      System.out.println("[action] " + m.command + " échec : " + t);
      return false;
    }
    if (applied) resyncHeroes(user);
    return applied;
  }

  /** Aiguille une commande vers la logique cœur du jeu. Le nom est comparé en String (l'enum du jeu
   *  a des annotations dex2jar qui gênent un switch). Étendu au fur et à mesure des commandes du jeu. */
  private boolean applyCommand(Action m, User user) {
    String cmd = m.command == null ? "" : m.command.name();
    switch (cmd) {
      case "EQUIP_ITEM": {
        // Équiper un objet d'équipement sur un héros — logique d'origine : HeroHelper.getSlotThatCanEquip
        // (valide niveau/released/craft via la couche CONTENU, désormais chargée par ServerContext) puis
        // HeroHelper.equipItem.
        com.perblue.heroes.game.objects.IHero hero = user.getHero(m.heroType);
        if (hero == null) { System.out.println("[action] EQUIP_ITEM: héros absent " + m.heroType); return false; }
        com.perblue.heroes.network.messages.HeroEquipSlot slot =
            com.perblue.heroes.game.logic.HeroHelper.getSlotThatCanEquip(user, hero);
        if (slot == null) { System.out.println("[action] EQUIP_ITEM: aucun slot équipable pour " + m.heroType); return false; }
        com.perblue.heroes.game.logic.HeroHelper.equipItem(m.heroType, m.itemType, slot, user);
        return true;
      }
      case "EQUIP_REAL_GEAR": {
        // Le jeu mappe l'objet → RealGearType puis équipe (RealGearHelper = logique d'origine).
        com.perblue.heroes.network.messages.RealGearType rg =
            com.perblue.heroes.game.data.item.ItemStats.getRealGearType(m.itemType);
        if (rg == null) return false;
        if (!com.perblue.heroes.game.logic.RealGearHelper.canEquipGear(user, rg)) {
          System.out.println("[action] EQUIP_REAL_GEAR refusé (canEquipGear=false) : " + rg);
          return false;
        }
        com.perblue.heroes.game.logic.RealGearHelper.equipGear(user, rg);
        return true;
      }
      case "VIEWED_CHESTS": {
        // Logique d'origine EXACTE (ActionHelper.doAction, branche VIEWED_CHESTS) :
        //   user.setTime(LAST_CHESTS_VIEW_TIME, Long.parseLong((String) extra.get(TIME)))
        // Marque l'horodatage « dernière consultation des coffres » → efface la pastille « nouveau ».
        // setTime écrit dans this.extra.times (UserExtra partagé) → persiste automatiquement (§3).
        Object t = m.extra == null ? null
            : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TIME);
        if (t == null) { System.out.println("[action] VIEWED_CHESTS: pas de TIME dans l'extra"); return false; }
        user.setTime(com.perblue.heroes.network.messages.TimeType.LAST_CHESTS_VIEW_TIME,
            Long.parseLong((String) t));
        return true;
      }
      case "RECORD_SERVER_ROLL_FINISHED":
        // NO-OP FIDÈLE (pas une rustine). Le code CLIENT du jeu ne mute AUCUN état pour cette
        // commande : ClientActionHelper.recordServerRollFinished ne fait que construire l'extra et
        // appeler ActionHelper.doAction(RECORD_SERVER_ROLL_FINISHED, …) — or doAction n'a AUCUNE
        // branche pour ce CommandType (vérifié au bytecode) → notification pure client→serveur.
        // Le comptage AUTORITATIF des rolls est déjà effectué par openChest (ChestHelper
        // .updateChestRollCounters) au moment du BuyChests. On ACQUITTE donc sans rien simuler ;
        // inventer un registre de rollId violerait PRINCIPLES §4 (ne rien inventer). Cf. SHIMS #5.
        return true;
      default:
        System.out.println("[action] commande non encore gérée: " + cmd + " (hero=" + m.heroType
            + " item=" + m.itemType + ") — à ajouter (helper de logique du jeu)");
        return false;
    }
  }

  /** Re-sync des héros (état hors {@code this.extra}) vers le wire — persistance complète. */
  @SuppressWarnings("unchecked")
  private void resyncHeroes(User user) {
    userExtra.heroes.clear();
    for (Object o : user.getHeroes()) {
      UnitData ud = (UnitData) o;
      userExtra.heroes.put(ud.getType(), ClientNetworkStateConverter.getHeroData(ud));
    }
  }

  /** Coffre gratuit ? (logique du jeu ; défaut prudent = gratuit si l'appel échoue headless). */
  private static boolean freeChest(User user, ChestType type, int count) {
    try { return ChestHelper.hasFreeChest(user, type, null, count); }
    catch (Throwable t) { return true; }
  }

  /** Table de butin du coffre (accesseur du jeu {@code ChestStats.getDropTable}, privé → réflexion). */
  private static DropTable dropTable(ChestType type) {
    try {
      Method get = ChestStats.class.getDeclaredMethod("getDropTable", ChestType.class);
      get.setAccessible(true);
      return (DropTable) get.invoke(null, type);
    } catch (Throwable t) { throw new RuntimeException("table de coffre introuvable: " + type, t); }
  }

  // --- Sérialisation wire (octets identiques au réseau) pour la persistance ---
  public synchronized byte[] userInfoWire()   { return wire(userInfo); }
  public synchronized byte[] userExtraWire()  { return wire(userExtra); }
  public synchronized byte[] individualWire() { return wire(individualUserExtra); }

  private static byte[] wire(GruntMessage m) {
    GruntOutputStream out = new GruntOutputStream();
    m.writeAll(out);                              // en-tête (nom) + données = format réseau exact
    return out.getBytes();
  }

  @SuppressWarnings("unchecked")
  private static <T extends GruntMessage> T read(byte[] bytes) {
    return (T) MessageFactory.getInstance().readMessage(new GruntInputStream(bytes));
  }
}
