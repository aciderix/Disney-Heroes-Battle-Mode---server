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
  // BATTLE PASS V2 : état PERSISTÉ (progress + paliers réclamés claimedFree/PremiumRewards + boughtBattlePass).
  // Vit dans le message BattlePassV2Data (HORS userExtra/individualUserExtra) → persisté à part (colonne BLOB).
  // On garde la PROGRESSION/les claims à travers les boots ; seule la SAISON (start/end/type/premium) est
  // rafraîchie à chaque bootData (elle est temporelle : saison roulante mensuelle, cf. ServerContext). Nullable
  // → créé paresseusement (compte neuf ou DB pré-migration).
  private com.perblue.heroes.network.messages.BattlePassV2Data battlePassV2Data;
  // MAILBOX : liste de courriers REÇUS du serveur (jamais composés par le joueur). Chaque MailMessage vit HORS
  // userExtra/individualUserExtra (le User les COPIE via setMailMessages) → persisté à part (colonne BLOB, liste
  // sérialisée). Le serveur d'origine génère ces courriers sur événements (récompenses coliseum/guilde,
  // remboursements, admin GLOBAL…) ; ré-hébergés, on n'a pas encore ces événements → on livre au moins le
  // courrier d'ONBOARDING NEW_USER_WELCOME (geste opérateur, type authentique). Nullable → liste vide.
  private java.util.List<com.perblue.heroes.network.messages.MailMessage> mail;

  // Graines RNG que le client annonce (Action SET_SEED) avant chaque combat, pour que le serveur puisse
  // REPRODUIRE/valider le tirage (combat COMBAT, loot LOOT ; cf. SERVER_PLAN §Partiels C→E). État de SESSION
  // (éphémère, non persisté) : consommé au CampaignAttack suivant. Cf. handler SET_SEED + getPendingSeed.
  private final java.util.Map<com.perblue.heroes.network.messages.RandomSeedType, Long> pendingSeeds =
      new java.util.EnumMap<>(com.perblue.heroes.network.messages.RandomSeedType.class);

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
    su.seedWelcomeMail(creation);
    return su;
  }

  /**
   * Dépose le courrier d'ONBOARDING {@code NEW_USER_WELCOME} dans la mailbox d'un compte NEUF (le serveur
   * d'origine envoie ce type de courrier à l'inscription). Contenu = choix d'OPÉRATEUR (serveur ré-hébergé) :
   * texte de bienvenue + une petite récompense en pièce jointe (500 diamants via {@code RewardHelper.createDrop},
   * un {@link RewardDrop} de RESSOURCE — logique/format du jeu, valeur au choix de l'opérateur, comme le premium
   * pour tous). PAS une donnée de jeu inventée : c'est un message opérateur au format wire authentique.
   */
  private void seedWelcomeMail(long now) {
    com.perblue.heroes.network.messages.MailMessage m = new com.perblue.heroes.network.messages.MailMessage();
    m.iD = 1;                                     // 1er courrier du compte (IDs per-joueur croissants)
    m.type = com.perblue.heroes.network.messages.MailType.NEW_USER_WELCOME;
    m.fromSender = "Disney Heroes";
    m.subject = "Welcome!";
    m.message = "Welcome to Disney Heroes: Battle Mode! Here's a gift to get you started. Have fun!";
    m.sentDate = now;
    // EXPIRATION = une VRAIE date future. FAIT (bytecode MailboxWindow) : la mailbox CACHE tout courrier dont
    // `getExpiration() < serverTimeNow()` → un `expiration=0` (époque 1970) est traité comme EXPIRÉ (invisible).
    // Le jeu n'a pas de « 0 = jamais » ; on met donc un délai généreux (~10 ans) pour un courrier d'onboarding.
    m.expiration = now + 3650L * 24L * 3600L * 1000L;   // ~10 ans
    m.opened = false;
    m.persistent = false;                         // non persistant → supprimé à la fermeture une fois vidé
    m.translatable = false;
    m.extra = new com.perblue.heroes.network.messages.MailExtra();
    m.extra.attachments = new java.util.ArrayList<>();
    m.extra.attachments.add(
        com.perblue.heroes.game.logic.RewardHelper.createDrop(
            com.perblue.heroes.network.messages.ResourceType.DIAMONDS, 500L));
    mail = new java.util.ArrayList<>();
    mail.add(m);
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
    // BATTLE PASS V2 : le client pose DH.app.userBattlePassV2 depuis bd.battlePassV2Data. Un défaut
    // `new BattlePassV2Data()` a `type = BattlePassType.DEFAULT` → l'écran QUESTS (QuestsScreen.showDot →
    // BattlePassV2Helper.hasUnclaimedRewards → computeRewards) LÈVE « Battle Pass types other than 'Quest'
    // haven't been implemented » (computeRewards ne gère QUE QUEST) → CRASH CLIENT au rendu (trouvé en jeu en
    // ouvrant QUESTS). Le battle pass v2 = le type **QUEST** avec la saison active (contenu). On l'initialise
    // avec la logique du jeu (`BattlePassV2Stats.getSeasonStartTime()` ; type QUEST). progress=0 pour un compte
    // neuf (la progression s'accumule via les quêtes ; non persistée pour l'instant — champ hors userExtra).
    // NB `BattlePassType` n'a que {DEFAULT, QUEST} → ce n'est PAS un décalage d'ère, juste un état non initialisé.
    ServerContext.init();
    bd.battlePassV2Data = refreshBattlePass();
    // MAILBOX : livrer les courriers du joueur (le client les copie via User.setMailMessages). knownMailIDs =
    // vide (le client re-signalera ce qu'il connaît via GetNewMailMessages en session).
    if (mail != null && !mail.isEmpty()) {
      bd.mailMessages = new java.util.ArrayList<>(mail);
    }
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

    // ANTI-TRICHE (serveur autoritatif) : VALIDER l'achat AVANT d'accorder, avec la logique du jeu
    // ChestHelper.validateChestPurchase (headless-safe : Unlockables + getResource). Elle LÈVE une
    // ClientErrorCodeException si l'ouverture est illégitime : coffre gratuit HORS cooldown ET pas assez de
    // monnaie (NOT_ENOUGH_GOLD/DIAMONDS), feature verrouillée (FEATURE_NOT_UNLOCKED), niveau d'équipe
    // (TEAM_LEVEL_LOCK), objet requis absent (DONT_HAVE_ITEM), limite d'achats (CANT_BUY_THAT_MANY_CHESTS)…
    // On laisse l'exception REMONTER → le LoginServer n'accorde/n'envoie RIEN (le tricheur n'a pas de coffre).
    // C'est LE point d'enforcement du cooldown 24h : la dispo se calcule sur l'ÉTAT SERVEUR (ressource +
    // horodatage de régénération persistés) avec l'HORLOGE DU SERVEUR (serverTimeNow = System.currentTimeMillis
    // côté serveur, CLOCK_OFFSET=0 jamais synchronisé sur un appareil) → avancer l'heure du mobile ne contourne
    // rien.
    //
    // 4ᵉ paramètre = LE COÛT DÉCLARÉ PAR LE CLIENT (m.cost), pas 0 en dur. Relevé au bytecode
    // (ChestHelper.openChestInner) : le client appelle validateChestPurchase(user, type, count, n2, item,
    // snapshot) où n2 = le coût, ET pose buyChests.cost = n2 → le 4ᵉ param de validate == BuyChests.cost
    // (0 pour un GRATUIT, le coût réel pour un PAYANT). La branche PAYANTE de validate fait « if (coût
    // recalculé serveur > coût déclaré client) throw ERROR » = ANTI-TAMPER (le tricheur ne peut pas déclarer
    // un coût inférieur). En passant 0 en dur, TOUT achat payant levait ERROR (288 > 0) → le débit payant
    // n'était jamais atteignable. Passer m.cost mirror le client exactement (gratuit → 0 → branche gratuite ;
    // payant → coût → « coût==coût » faux → OK), et RENFORCE l'anti-triche (compare au coût déclaré).
    com.perblue.heroes.network.messages.ItemType usedItem =
        (m.usedItem == null || m.usedItem == com.perblue.heroes.network.messages.ItemType.DEFAULT) ? null : m.usedItem;
    ChestHelper.validateChestPurchase(user, type, count, m.cost, usedItem, SpecialEventSnapshot.NONE);

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

    // CONSOMMER le coffre GRATUIT (fidélité — gap trouvé en jeu 2026-07-19 : « FREE NOW » restait dispo après
    // ouverture). Le coffre gratuit est une ResourceType RÉGÉNÉRÉE (getFreeChestResource) ; l'ouvrir doit la
    // décrémenter → le coffre passe en « Free in 23h » et régénère sur getTimeUntilNextFreeChest. Le jeu ne le
    // fait PAS dans ChestHelper (client) : les 4 usages de getFreeChestResource y sont en LECTURE
    // (hasFreeChest/getTimeUntilNextFreeChest/validateChestPurchase) → c'était une action SERVEUR-autoritative
    // de PerBlue. On la reproduit via le setResource DU JEU (qui ré-ancre l'horloge de génération). Sans ça, la
    // ressource reste à son cap → coffre gratuit « farmable » (hasFreeChest toujours vrai). Valeurs du jeu.
    if (lr.wasFree) {
      com.perblue.heroes.network.messages.ResourceType fr = ChestHelper.getFreeChestResource(type);
      if (fr != null && fr != com.perblue.heroes.network.messages.ResourceType.DEFAULT) {
        long cur = user.getResource(fr);
        user.setResource(fr, Math.max(0, cur - count), "free chest consumed");
      }
    } else {
      // COFFRE PAYANT : DÉBITER la monnaie (fidélité + économie autoritative). validateChestPurchase (ci-dessus)
      // a déjà confirmé la SOLVABILITÉ (sinon elle aurait levé). Monnaie + montant via la logique du jeu :
      // getPurchaseCurrency (SILVER→GOLD, GOLD/SOUL→DIAMONDS…) + getPurchaseCost. Sans ça, un coffre PAYANT
      // serait accordé GRATUITEMENT (le serveur ne débitait pas → la « charge » optimiste du client était
      // perdue au reload, et le serveur autoritatif ne faisait pas payer). GOLD est dans this.extra (auto),
      // DIAMONDS via resyncDiamonds (champ dédié). Valeurs du jeu, non inventées.
      com.perblue.heroes.network.messages.ResourceType cur =
          ChestHelper.getPurchaseCurrency(type, SpecialEventSnapshot.NONE);
      int cost = ChestHelper.getPurchaseCost(user, type, count, SpecialEventSnapshot.NONE);
      if (cur != null && cur != com.perblue.heroes.network.messages.ResourceType.DEFAULT && cost > 0) {
        user.setResource(cur, Math.max(0, user.getResource(cur) - cost), "chest purchase");
        System.out.println("[chest] coffre PAYANT " + type + " x" + count + " : -" + cost + " " + cur);
      }
    }

    if (m.roll != null) {                          // réponse de roll attendue par le client
      ServerRollResponse rr = new ServerRollResponse();
      rr.rollId = m.roll.rollId;
      rr.channel = m.roll.channel;
      rr.nextModID = user.getNextModID();
      lr.roll = rr;
    }

    // Re-synchronise les champs hors this.extra vers le wire (persistance complète).
    resyncHeroes(user);
    resyncDiamonds(user);   // coffre payant → débit diamants (hors this.extra)
    resyncCounts(user);     // compteurs/drapeaux UserFlag (hors this.extra)
    individualUserExtra.chestUpgradeXP = iu.getChestUpgradeXP();
    return lr;
  }

  /**
   * Construit le message {@link com.perblue.heroes.network.messages.SigninRewards} attendu par le client
   * (bâtiment SIGN IN / récompense de connexion quotidienne). <b>100 % code + données du jeu</b>
   * (docs/PRINCIPLES.md §3/§4) : les récompenses ne sont PAS écrites à la main — elles sont <b>roulées</b>
   * depuis la table de drop du jeu {@code SigninStats.REWARDS_TABLE} (fichier {@code signin_rewards.tab},
   * extrait de l'APK), et le héros mensuel vient du calendrier de contenu ({@code content.<shard>.tab}).
   *
   * <p>Le client applique la réponse via {@code SigninHelper.setData(signinRewards)} puis lit tout depuis
   * ce message ({@code getRewards}/{@code getActiveRewardIndex}/{@code isClaimable}/{@code claim}). Le champ
   * {@code thisMonth.rewards} = la <b>liste des récompenses journalières du mois</b> (une {@code RewardDrop}
   * par jour), obtenue en roulant le nœud {@code ROOT} de la table avec un
   * {@code SigninStats.SigninContext(dayIndex, signinStart)} — la table est <b>riggée par index</b> (nœuds
   * {@code V<version>_DAY_<i>}), donc déterministe (pas d'aléa). Envoyé dans
   * {@code SpecialEventsRaw.signinRewards} (réponse à {@code Action{REFRESH_SPECIAL_EVENTS}}).
   */
  public synchronized com.perblue.heroes.network.messages.SigninRewards buildSigninRewards() {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "signin");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "signin");
    ServerContext.bind(user, iu);
    return signinRewardsFor(user);
  }

  /**
   * Construit {@code SigninRewards} pour un {@code user} DÉJÀ lié au contexte (DH.app). Utilisé par
   * {@link #buildSigninRewards()} et par le handler de réclamation ({@code applyCommand CLAIM_SIGNIN_REWARD}),
   * qui a besoin de poser {@code SigninHelper.setData(...)} avant d'appeler {@code SigninHelper.claim}.
   */
  private static com.perblue.heroes.network.messages.SigninRewards signinRewardsFor(User user) {
    com.perblue.heroes.network.messages.SigninRewards out =
        new com.perblue.heroes.network.messages.SigninRewards();
    long now = com.perblue.heroes.util.TimeUtil.getUserServerTime(user);
    // Bornes des trois mois (le client sélectionne thisMonth/lastMonth/nextMonth par comparaison de temps :
    // cf. SigninHelper.getCurrentSigninReward). Calendar sur l'heure serveur du user.
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.setTimeInMillis(now);
    long thisStart = monthStart(cal, now);
    long thisEnd = monthEnd(cal, now);
    out.thisMonth = buildSigninMonth(user, thisStart, thisEnd);
    out.lastMonth = buildSigninMonth(user, monthStart(cal, thisStart - 1L), monthEnd(cal, thisStart - 1L));
    out.nextMonth = buildSigninMonth(user, monthStart(cal, thisEnd + 1L), monthEnd(cal, thisEnd + 1L));
    out.signinHeroesRev = new java.util.HashMap<>();  // récence des héros de sign-in : vide (non affiché ici)
    return out;
  }

  /** Premier instant du mois contenant {@code millis} (00:00:00.000). */
  private static long monthStart(java.util.Calendar cal, long millis) {
    cal.setTimeInMillis(millis);
    cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    cal.set(java.util.Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  /** Dernier instant du mois contenant {@code millis} (23:59:59.999). */
  private static long monthEnd(java.util.Calendar cal, long millis) {
    cal.setTimeInMillis(millis);
    cal.set(java.util.Calendar.DAY_OF_MONTH, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH));
    cal.set(java.util.Calendar.HOUR_OF_DAY, 23);
    cal.set(java.util.Calendar.MINUTE, 59);
    cal.set(java.util.Calendar.SECOND, 59);
    cal.set(java.util.Calendar.MILLISECOND, 999);
    return cal.getTimeInMillis();
  }

  /**
   * Un {@code SigninReward} = les récompenses journalières d'UN mois : pour chaque jour {@code i}, on roule
   * le nœud {@code ROOT} de {@code SigninStats.REWARDS_TABLE} avec un {@code SigninContext(i, signinStart)}
   * (déterministe, riggé par index) → une {@code RewardDrop}. Le héros mensuel vient du calendrier de contenu.
   */
  @SuppressWarnings("unchecked")
  private static com.perblue.heroes.network.messages.SigninReward buildSigninMonth(
      User user, long start, long end) {
    com.perblue.heroes.network.messages.SigninReward r =
        new com.perblue.heroes.network.messages.SigninReward();
    r.startTime = start;
    r.endTime = end;
    r.rewards = new java.util.ArrayList<>();
    DropTable table = com.perblue.heroes.game.data.signin.SigninStats.REWARDS_TABLE.getTable();
    DropConverter conv = new DropConverter(user);
    // La table a un nœud par jour (V<ver>_DAY_0..DAY_N). On roule jusqu'à ce qu'un jour ne produise plus rien
    // (nœud absent → la version a moins de jours) — borne de sécurité à 60 (un mois en compte ≤31).
    for (int i = 0; i < 60; i++) {
      java.util.List<com.perblue.heroes.network.messages.RewardDrop> dayDrops;
      try {
        // SigninContext(index, signinStart) — classe imbriquée PROTECTED (hors package) → réflexion.
        // Les variables de la table (SignInVersion/SignInIndex/L) la castent pour lire index/signinStart.
        Object ctx = newSigninContext(i, start);
        List<?> drops = table.rollNode("ROOT", (com.perblue.common.droptable.DTContext) ctx, new Random());
        dayDrops = conv.convert(drops);
      } catch (Throwable t) {
        break;  // nœud V<ver>_DAY_<i> inexistant → fin des jours de cette version
      }
      if (dayDrops == null || dayDrops.isEmpty()) break;
      // Une RewardDrop par jour (le modèle du jeu indexe getReward(user,i) = rewards.get(i)).
      r.rewards.add(dayDrops.get(0));
    }
    // Héros mensuel de sign-in (piloté par content.<shard>.tab, daté) — via le calendrier de contenu.
    try {
      r.signinHero = com.perblue.heroes.game.data.content.ContentHelper.getRawStats()
          .getColumn(start).getCurrentMonthlySigninHero();
    } catch (Throwable ignore) { /* pas de héros mensuel → laissé null (champ optionnel) */ }
    return r;
  }

  /** Cache du constructeur {@code SigninStats$SigninContext(int, long)} (classe imbriquée protected). */
  private static java.lang.reflect.Constructor<?> SIGNIN_CTX_CTOR;

  /** Instancie un {@code SigninStats.SigninContext(index, signinStart)} par réflexion (protected hors package). */
  private static Object newSigninContext(int index, long signinStart) throws Exception {
    if (SIGNIN_CTX_CTOR == null) {
      Class<?> c = Class.forName("com.perblue.heroes.game.data.signin.SigninStats$SigninContext");
      SIGNIN_CTX_CTOR = c.getDeclaredConstructor(int.class, long.class);
      SIGNIN_CTX_CTOR.setAccessible(true);
    }
    return SIGNIN_CTX_CTOR.newInstance(index, signinStart);
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

    // NB (fidélité) : on NE clampe PAS la stamina ici. Le jeu (updateAndGetResource) laisse la STAMINA
    // DÉBORDER le cap (branche non-capée) et STOCKE la valeur brute (ex. 39,96 M à R102) — comportement
    // authentique (débordement dépensable = « cadeau » de fin de vie, confirmé sur gameplay réel). recordOutcome
    // ci-dessous débite via la logique d'origine (getStaminaCost + chargeUser, régén-à-la-lecture incluse).
    // (Un ancien applyEffectiveResourceCap forçait min(getResource, cap) : c'était NOTRE ajout, pas le jeu →
    // retiré pour rester fidèle. Le compte neuf reste à 120 car initNewPlayerResources ancre l'horloge à la
    // création → aucun intervalle écoulé → pas de débordement au démarrage.)
    CampaignType type = m.campaignType == null ? CampaignType.NORMAL : m.campaignType;
    GameMode mode = type == CampaignType.ELITE ? GameMode.ELITE_CAMPAIGN : GameMode.CAMPAIGN;
    CampaignLevel level = CampaignLevel.of(mode, m.chapter, m.level);

    // Loot d'OBJETS : le combat est joué CÔTÉ CLIENT (client-autoritatif, comme outcome/stars), qui ROULE
    // le loot pendant le combat et l'envoie dans m.lootEarned (List<RewardDrop>). recordOutcome N'EN ROULE PAS
    // (vérifié : lootEarned vide → 0 objet) : il APPLIQUE la liste reçue (giveLoot → RewardHelper.giveRewards →
    // IndividualUser.addItem → individualUserExtra.items, AUTO-PERSISTÉ). On passe donc m.lootEarned en 1ᵉʳ
    // paramètre List (= le loot À DONNER). Le 2ᵉ paramètre List est un DELTA de RewardDrop (déjà-affiché) que
    // giveLoot passe à removeDelta ; il DOIT rester vide/RewardDrop — y mettre m.memoryChanges
    // (List<UserLootMemoryChange>) fait planter removeDelta (ClassCastException). On laisse donc ce delta VIDE
    // → tout m.lootEarned est crédité. PARTIEL (SHIMS) : seed client (Action SET_SEED) non appliquée → le
    // serveur ne re-roule pas, il fait confiance au loot client. La mémoire de loot (m.memoryChanges) EST
    // appliquée plus bas (auto-persistée), voir applyLootMemory.
    java.util.List clientLoot = m.lootEarned != null ? m.lootEarned : new java.util.ArrayList<>();
    // #25 — LOOT AUTORITAIRE (mode OMBRE — PAS ENCORE crédité). Le serveur ROULE le butin avec la graine LOOT
    // (flux RNG séparé du combat) et le COMPARE au client, mais crédite ENCORE le loot CLIENT.
    // POURQUOI PAS ENCORE AUTORITAIRE : en jeu réel multi-combat, le tirage serveur DIVERGE du client légitime
    // (relevé au run frais 1-5 : les items EXP et la pitié divergent). Le loot dépend d'un ÉTAT ÉVOLUTIF que le
    // serveur ne reproduit pas encore fidèlement : le POOL D'XP (`IUser.getExpLootPool`/`CampaignLoot.
    // newExpLootPool`, non suivi/mis à jour côté serveur) et la MÉMOIRE DE LOOT (pitié) au moment du tirage. Le
    // test 5/5 ne l'avait pas vu (état FRAIS identique des 2 côtés). Créditer un tirage divergent donnerait au
    // joueur HONNÊTE un mauvais butin (régression §4bis) → tant que le serveur ne reproduit pas le client dans
    // TOUS les cas légitimes, on garde le loot client et on LOGue la divergence (diagnostic). Bascule
    // autoritative UNIQUEMENT une fois l'état (pool XP + mémoire) reproduit fidèlement (== client en jeu réel).
    java.util.List serverLoot = rollAuthoritativeLoot(user, iu, type, m);
    if (serverLoot != null && m.base != null
        && m.base.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN)
      logLootValidation(serverLoot, clientLoot);
    java.util.List lootEarned = clientLoot;
    java.util.List shownDelta = new java.util.ArrayList<>();
    // base : attackers/defenders = Collection de AttackLineupSummary, outcome + stars remplis par le client.
    CampaignHelper.recordOutcome(user, user, level, m.base.outcome, m.base.stars, m.stagesCleared,
        lootEarned, shownDelta, m.base.attackers, m.base.defenders, SpecialEventSnapshot.NONE);

    resyncHeroes(user);   // héros (XP/état) → wire ; stamina/or sont dans this.extra (auto).
    resyncDiamonds(user); // diamants (champ dédié hors this.extra)
    resyncCounts(user);   // compteurs/drapeaux UserFlag (hors this.extra)
    resyncCampaign(iu);   // progression campagne (statuts de niveau) → wire (hors this.extra, comme les héros).
    applyLootMemory(m);   // mémoire de loot (pitié) → individualUserExtra.lootMemory (auto-persistée).
    // Niveau d'équipe : User.teamLevel est un CHAMP de User (hors this.extra) — getUser le lit depuis
    // userInfo.basicInfo.teamLevel, mais setTeamLevel (montée de niveau via giveTeamXP) ne l'écrit QUE sur
    // l'objet User. Sans re-sync vers le wire, le niveau reste BLOQUÉ à 1 : l'équipe « remonte 1→2 » à chaque
    // palier d'XP (18) et ré-accorde STAMINA_GAIN_ON_LEVEL (+20) EN BOUCLE (au lieu de progresser vers le
    // palier suivant). Même schéma que resyncHeroes/resyncCampaign (§6 persistance complète).
    userInfo.basicInfo.teamLevel = user.getTeamLevel();
  }

  /**
   * #25 — Loot AUTORITAIRE. Le serveur ROULE lui-même le butin avec la graine LOOT du client (capturée via
   * {@code Action SET_SEED}, cf. #23), au lieu de faire confiance à {@code m.lootEarned}. Le loot est un flux
   * RNG <b>SÉPARÉ du combat</b> ({@code RandomSeedType.LOOT} ≠ {@code COMBAT}) → fonction déterministe de la
   * SEULE graine LOOT, <b>aucune simulation de combat requise</b>. Reproduit EXACTEMENT l'appel client
   * (relevé au bytecode, {@code CampaignAttackScreen} 2ᵉ ctor) :
   * <pre>user.resetRandom(LOOT) ; CampaignLootHelper.getLoot(user, type, 0, chapter, level, NONE, guildPerks, true)</pre>
   * → {@code CampaignLoot.combinedLoot} = la liste de {@code RewardDrop} d'une VICTOIRE complète.
   * @return la liste roulée serveur, ou {@code null} si aucune graine LOOT connue (→ on retombe sur le client).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private java.util.List rollAuthoritativeLoot(User user, IndividualUser iu, CampaignType type, CampaignAttack m) {
    Long lootSeed = getPendingSeed(com.perblue.heroes.network.messages.RandomSeedType.LOOT);
    if (lootSeed == null) return null;   // pas de graine → confiance client (documenté), non bloquant
    // Ancre la graine LOOT du client puis reseed le flux (getLoot consomme user.getRandom(LOOT) en interne).
    iu.setSeed(com.perblue.heroes.network.messages.RandomSeedType.LOOT, lootSeed, "");
    user.resetRandom(com.perblue.heroes.network.messages.RandomSeedType.LOOT);
    // GuildInfoPerkProvider sur le GuildInfo du joueur (shim ServerContext ; vide = pas de bonus de perk de
    // guilde, exact pour un joueur sans guilde). SpecialEventSnapshot.NONE (serveur sans évènement, cf. §F).
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo());
    com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot cl =
        com.perblue.heroes.game.logic.CampaignLootHelper.getLoot(
            user, type, 0, m.chapter, m.level, SpecialEventSnapshot.NONE, perks, true);
    return cl == null ? null : cl.combinedLoot;
  }

  /** Expose le tirage de loot AUTORITAIRE (reconstruit user/iu depuis l'état courant). Utilisé par le test de
   *  certification (comparer au loot client) et prêt pour la bascule autoritative. {@code null} si pas de graine. */
  @SuppressWarnings("rawtypes")
  public synchronized java.util.List computeAuthoritativeLoot(CampaignAttack m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "loot-auth");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "loot-auth");
    ServerContext.bind(user, iu);
    CampaignType type = m.campaignType == null ? CampaignType.NORMAL : m.campaignType;
    return rollAuthoritativeLoot(user, iu, type, m);
  }

  /** VALIDATION anti-triche : compare le loot roulé serveur (crédité) au loot annoncé par le client — multiset
   *  (item/ressource → quantité). Un écart = butin client falsifié (le serveur crédite SON tirage de toute façon). */
  @SuppressWarnings("rawtypes")
  private void logLootValidation(java.util.List serverLoot, java.util.List clientLoot) {
    java.util.Map<String, Long> s = lootMultiset(serverLoot), c = lootMultiset(clientLoot);
    boolean match = s.equals(c);
    System.out.println("[loot-authoritative] #25 OMBRE : " + (match ? "OK (serveur==client) ✅"
        : "DIVERGE (état pool XP/pitié non reproduit — on crédite le client) ⚠️")
        + "  serveur=" + s + "  client=" + c);
  }

  @SuppressWarnings("rawtypes")
  private java.util.Map<String, Long> lootMultiset(java.util.List drops) {
    java.util.Map<String, Long> map = new java.util.TreeMap<>();
    if (drops == null) return map;
    for (Object o : drops) {
      com.perblue.heroes.network.messages.RewardDrop d = (com.perblue.heroes.network.messages.RewardDrop) o;
      map.merge("I:" + d.itemType + "/R:" + d.resourceType, d.quantity, Long::sum);
    }
    return map;
  }


  /**
   * Re-synchronise la PROGRESSION de campagne vers le wire. Les statuts de niveau vivent en mémoire
   * ({@code ClientCampaignLevelStatus} construits depuis {@code individualUserExtra.levelStatuses} au
   * chargement) ; {@code recordOutcome} les mute EN MÉMOIRE mais n'écrit PAS la liste wire → sans ce
   * re-sync, étoiles/complétion sont perdues au round-trip (1-2 ne se débloque jamais). On reconstruit
   * {@code individualUserExtra.levelStatuses} depuis {@code iu.getCampaignLevels()} (champs mappés 1:1 ;
   * {@code lastWinTime} lu par réflexion — cf. {@code readLastWinTime}). Même schéma que
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
      w.lastWinTime = readLastWinTime(c);   // pas de getter public → lecture réflexion du champ privé (§6 complet)
      out.add(w);
    }
    individualUserExtra.levelStatuses = out;
  }

  /**
   * Lit {@code ClientCampaignLevelStatus.lastWinTime} (champ privé {@code long}, setter public mais PAS de
   * getter) par réflexion, pour compléter la re-synchro campagne (§6 persistance complète). En cas d'échec
   * (obfuscation/refonte), renvoie 0 — non requis pour le déblocage, dégradation sûre.
   */
  private static long readLastWinTime(com.perblue.heroes.game.objects.ClientCampaignLevelStatus c) {
    try {
      java.lang.reflect.Field f = com.perblue.heroes.game.objects.ClientCampaignLevelStatus.class
          .getDeclaredField("lastWinTime");
      f.setAccessible(true);
      return f.getLong(c);
    } catch (Throwable t) {
      return 0L;
    }
  }

  /**
   * Applique la MÉMOIRE DE LOOT (« pitié » : drop garanti après N essais). Le combat client roule le loot et
   * met à jour la loot memory, puis envoie les deltas dans {@code CampaignAttack.memoryChanges}
   * ({@code List<UserLootMemoryChange>{itemType, startingMemory, endingMemory}}). On écrit l'état final dans
   * {@code individualUserExtra.lootMemory} ({@code Map<ItemType, Float>}, dans {@code this.extra} → AUTO-persisté).
   * <b>NB</b> : ces changements NE doivent PAS être passés à {@code recordOutcome} (son 2ᵉ paramètre List est
   * un delta de {@code RewardDrop} → {@code removeDelta} lèverait {@code ClassCastException}) — on les applique
   * À PART ici. Sans re-roll serveur (cf. SERVER_PLAN §Partiels D/E), on fait confiance à la mémoire client
   * (cohérent avec le combat client-autoritatif).
   */
  @SuppressWarnings("unchecked")
  private void applyLootMemory(CampaignAttack m) {
    if (m.memoryChanges == null || m.memoryChanges.isEmpty()) return;
    if (individualUserExtra.lootMemory == null) individualUserExtra.lootMemory = new java.util.HashMap();
    for (Object o : m.memoryChanges) {
      com.perblue.heroes.network.messages.UserLootMemoryChange ch =
          (com.perblue.heroes.network.messages.UserLootMemoryChange) o;
      if (ch.itemType != null) individualUserExtra.lootMemory.put(ch.itemType, ch.endingMemory);
    }
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
  /**
   * Choix / changement du <b>nom du joueur</b> (étape onboarding « CHOOSE NAME » + écran Réglages).
   * Le client applique {@code UserHelper.changeName} de son côté puis envoie <b>fire-and-forget</b> un
   * {@link com.perblue.heroes.network.messages.SetPlayerName}{@code {name}} (relevé au bytecode :
   * {@code ChangeNamePrompt.changeNameInner} → {@code UserHelper.changeName} + {@code sendMessage(SetPlayerName)}).
   * Le serveur AUTORITATIF ré-exécute la MÊME logique du jeu ({@code UserHelper.changeName} : légalité via
   * {@code NameChangeHelper.isNameLegal}, coût — 1ᵉʳ changement gratuit via {@code FREE_NAME_CHANGE}, sinon
   * item/diamants —, {@code setPreviousName}+{@code setName}), puis re-sync le nom vers le wire et persiste.
   * Renvoie {@code true} si le nom a été appliqué.
   */
  public synchronized boolean setPlayerName(com.perblue.heroes.network.messages.SetPlayerName m) {
    ServerContext.init();
    if (m == null || m.name == null || m.name.isEmpty()) {
      System.out.println("[setname] message vide → ignoré"); return false;
    }
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "setname");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "setname");
    ServerContext.bind(user, iu);
    try {
      com.perblue.heroes.game.logic.UserHelper.changeName(user, m.name);   // logique d'origine (légalité+coût)
    } catch (Throwable t) {
      System.out.println("[setname] changeName refusé (" + m.name + ") : " + t);
      return false;
    }
    // Le nom vit dans le champ User.userName (HORS this.extra) → re-sync vers le wire (basicInfo), comme
    // le niveau d'équipe. previousName suit (changeName l'a posé). Diamants/compteurs sont dans this.extra.
    if (userInfo.basicInfo != null) {
      userInfo.basicInfo.name = user.getName();
      userInfo.basicInfo.previousName = user.getPreviousName();
    }
    System.out.println("[setname] nom → '" + user.getName() + "' (précédent '" + user.getPreviousName() + "')");
    return true;
  }

  public synchronized boolean applyAction(Action m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "action");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "action");
    ServerContext.bind(user, iu);
    // Battle pass : lier le wrapper sur NOTRE BattlePassV2Data persisté (créé si besoin) → getUserBattlePassV2()
    // répond, la progression (ResourceType.QUEST_POINTS) s'accumule via le code du jeu, et claims/progress
    // mutent le message persisté (cf. ServerContext.bindBattlePass). Fait pour TOUTE action (pas seulement les
    // commandes BP) : une quête qui donne des QUEST_POINTS doit trouver le wrapper (sinon NPE).
    ServerContext.bindBattlePass(refreshBattlePass());
    boolean applied;
    try {
      applied = applyCommand(m, user);
    } catch (Throwable t) {
      System.out.println("[action] " + m.command + " échec : " + t);
      return false;
    }
    if (applied) { resyncHeroes(user); resyncDiamonds(user); resyncCounts(user); }
    return applied;
  }

  /**
   * Graine RNG annoncée par le client pour {@code type} (via {@code Action SET_SEED}), ou {@code null} si
   * aucune n'a été reçue. Destinée à la re-simulation/re-roll autoritatif (SERVER_PLAN §Partiels D/E).
   */
  public Long getPendingSeed(com.perblue.heroes.network.messages.RandomSeedType type) {
    return pendingSeeds.get(type);
  }

  /**
   * Ouverture d'une BOÎTE-RÉCOMPENSE HEBDOMADAIRE (écran QUESTS, après {@code REDEEM_DAILY_QUESTS} qui convertit
   * la progression en N boîtes). Message top-level {@code ClaimWeeklyQuestReward} (fire-and-forget, comme
   * {@code CampaignAttack}) : le client a ROULÉ les options ({@code rewardDrops}, graine
   * {@code WEEKLY_QUEST_REWARD}), le joueur en a CHOISI une ({@code rewardChosen}) + un bonus stamina
   * ({@code staminaReward}). Le serveur AUTORITATIF ré-exécute la logique d'origine {@code QuestHelper
   * .claimWeeklyReward(user, rewardChosen, staminaReward)} :
   * <ul>
   *   <li><b>anti-triche RÉEL</b> : {@code getWeeklyRewardsRemaining>0} sinon {@code ClientErrorCodeException}
   *       → on ne peut ouvrir QUE le nombre de boîtes GAGNÉES (décrémente {@code WEEKLY_QUEST_REWARDS}) ;</li>
   *   <li>donne la stamina bonus + la récompense, décrémente le compteur, rend la graine RNG.</li>
   * </ul>
   * PARTIEL (documenté, cf. SHIMS) : le CHOIX ({@code rewardChosen}) est celui du client — la re-validation du
   * tirage contre la graine {@code WEEKLY_QUEST_REWARD} (roll autoritatif de {@code weekly_quest_rewards.tab})
   * relève des Partiels D/E (comme l'issue de combat, client-autoritative). L'anti-triche du NOMBRE de boîtes
   * est, lui, RÉEL. Renvoie {@code true} si appliqué (à persister par l'appelant).
   */
  public synchronized boolean claimWeeklyReward(com.perblue.heroes.network.messages.ClaimWeeklyQuestReward m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "weekly-box");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "weekly-box");
    ServerContext.bind(user, iu);
    ServerContext.bindBattlePass(refreshBattlePass());       // au cas où la récompense touche QUEST_POINTS
    try {
      com.perblue.heroes.game.logic.QuestHelper.claimWeeklyReward(user, m.rewardChosen, m.staminaReward);
    } catch (Throwable t) {
      System.out.println("[weekly-box] claimWeeklyReward refusé/échec (boîtes épuisées ?) : " + t);
      return false;
    }
    resyncHeroes(user); resyncDiamonds(user); resyncCounts(user);
    System.out.println("[weekly-box] boîte weekly ouverte → récompense « " + m.rewardChosen
        + " » + " + m.staminaReward + " stamina créditées (logique du jeu ; boîtes restantes décrémentées)");
    return true;
  }

  /** Aiguille une commande vers la logique cœur du jeu. Le nom est comparé en String (l'enum du jeu
   *  a des annotations dex2jar qui gênent un switch). Étendu au fur et à mesure des commandes du jeu. */
  private boolean applyCommand(Action m, User user) {
    String cmd = m.command == null ? "" : m.command.name();
    switch (cmd) {
      case "EQUIP_ITEM": {
        // Équiper un objet d'équipement sur un héros — logique d'origine (HeroHelper.equipItem).
        // ⚠ SLOT = celui CHOISI PAR LE CLIENT (Action.extra[SLOT]) : le joueur a tapé un slot précis dans
        // l'UI (ex. BADGE_OF_FRIENDSHIP → slot SIX). Se fier à getSlotThatCanEquip(user,hero) est FAUX quand
        // PLUSIEURS slots sont équipables : il renvoie le PREMIER (ex. ONE) → equipItem tente d'y mettre un
        // objet qui n'y va pas → ClientErrorCodeException WRONG_ITEM (bug observé en jeu : Frozone a d'autres
        // gear dispo après les coffres → slot ONE renvoyé, mais le Badge va en SIX). On honore donc le SLOT
        // du client ; getSlotThatCanEquip = repli seulement si le client n'a pas précisé (compat).
        com.perblue.heroes.game.objects.IHero hero = user.getHero(m.heroType);
        if (hero == null) { System.out.println("[action] EQUIP_ITEM: héros absent " + m.heroType); return false; }
        com.perblue.heroes.network.messages.HeroEquipSlot slot = null;
        Object slotO = m.extra == null ? null
            : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.SLOT);
        if (slotO instanceof com.perblue.heroes.network.messages.HeroEquipSlot)
          slot = (com.perblue.heroes.network.messages.HeroEquipSlot) slotO;
        else if (slotO != null)
          try { slot = com.perblue.heroes.network.messages.HeroEquipSlot.valueOf(slotO.toString()); }
          catch (IllegalArgumentException ignore) {}
        if (slot == null) slot = com.perblue.heroes.game.logic.HeroHelper.getSlotThatCanEquip(user, hero);
        if (slot == null) { System.out.println("[action] EQUIP_ITEM: aucun slot équipable pour " + m.heroType); return false; }
        com.perblue.heroes.game.logic.HeroHelper.equipItem(m.heroType, m.itemType, slot, user);
        System.out.println("[action] EQUIP_ITEM " + m.heroType + " " + m.itemType + " slot=" + slot + " (client)");
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
      case "SET_SEED": {
        // Le client (combat client-autoritatif) annonce la GRAINE RNG qu'il a utilisée, AVANT le combat :
        //   extra = { ID = <graine long, en String>, TYPE = <RandomSeedType, ex. COMBAT|LOOT>, REASON = … }.
        // On la stocke en état de SESSION (éphémère) → le prochain CampaignAttack pourra REPRODUIRE le combat
        // (HeadlessCombat, §Partiel D) et/ou le loot (drop tables, §Partiel E) de façon déterministe et
        // AUTORITATIVE. Pour l'instant on ne fait que MÉMORISER (les re-rolls D/E sont des tâches à venir).
        if (m.extra == null) return true;
        Object idO = m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
        Object tyO = m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
        if (idO == null || tyO == null) { System.out.println("[action] SET_SEED: ID/TYPE manquant"); return true; }
        try {
          long seed = Long.parseLong(idO.toString());
          com.perblue.heroes.network.messages.RandomSeedType ty =
              com.perblue.heroes.network.messages.RandomSeedType.valueOf(tyO.toString());
          pendingSeeds.put(ty, seed);
        } catch (Throwable t) {
          System.out.println("[action] SET_SEED: extra illisible (" + idO + "/" + tyO + ") : " + t);
        }
        return true;
      }
      case "CLAIM_SIGNIN_REWARD":
      case "CLAIM_SIGNIN_WITH_VIDEO": {
        // Réclamation d'une récompense de connexion quotidienne — logique d'origine EXACTE
        // (SigninHelper.claim, code du jeu). Le client optimiste applique claim de son côté (doAction) et
        // envoie Action{CLAIM_SIGNIN_REWARD, extra={INDEX=i}} ; le serveur AUTORITATIF ré-exécute la même
        // logique. claim() lit ses données dans SigninHelper.DATA → on POSE d'abord les récompenses du mois
        // (setData), construites depuis la table du jeu (signinRewardsFor). claim() : isClaimable →
        // getReward(i) → RewardHelper.giveReward (donne l'objet, auto-persisté dans this.extra) +
        // incMonthlySignins/decDailyChances(daily_signin)/setLastSigninTime/setTime. Le drapeau vidéo
        // (CLAIM_SIGNIN_WITH_VIDEO) double la récompense (multiplicateur VIP), comme dans le jeu.
        boolean withVideo = cmd.equals("CLAIM_SIGNIN_WITH_VIDEO");
        com.perblue.heroes.game.logic.SigninHelper.setData(signinRewardsFor(user));
        Object idxO = m.extra == null ? null
            : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.INDEX);
        int index;
        try {
          index = idxO == null
              ? com.perblue.heroes.game.logic.SigninHelper.getActiveRewardIndex(user)  // défaut : jour actif
              : Integer.parseInt(idxO.toString());
        } catch (NumberFormatException e) {
          System.out.println("[action] CLAIM_SIGNIN: INDEX illisible " + idxO); return false;
        }
        if (!com.perblue.heroes.game.logic.SigninHelper.isClaimable(user, index)) {
          System.out.println("[action] CLAIM_SIGNIN: jour " + index + " non réclamable (déjà pris / futur)");
          return false;
        }
        com.perblue.heroes.network.messages.RewardDrop d =
            com.perblue.heroes.game.logic.SigninHelper.claim(user, index, withVideo);
        System.out.println("[action] CLAIM_SIGNIN jour " + index + (withVideo ? " (vidéo x2)" : "")
            + " → " + d);
        return true;
      }
      case "UNLOCK_HERO": {
        // Débloquer un HÉROS (ex. Vanellope avec 10 fragments STONE_VANELLOPE — flux amorcé par le tuto
        // UNLOCK_HERO, cf. g7). Le client envoie UNLOCK_HERO{heroType} (sans extra ; ClientActionHelper
        // .unlockHero → doAction(UNLOCK_HERO, unitType, …)). Logique d'origine EXACTE : HeroHelper.unlock —
        // qui porte TOUT l'anti-triche RÉEL et l'effet : assez de fragments (getItemAmount ≥ getUnlockStones
        // sinon NOT_ENOUGH_STONES), héros pas déjà possédé (sinon ALREADY_HAVE_HERO), PUIS débite le coût GOLD
        // (chargeUser, this.extra → auto-persisté), CONSOMME les fragments (useItem → individualUserExtra.items,
        // auto-persisté) et AJOUTE le héros au roster (createAndAddHero → resyncHeroes par applyAction).
        // canUnlock = pré-check (héros dispo dans le contenu, stoneType défini) → refus propre sans exception.
        com.perblue.heroes.network.messages.UnitType hero = m.heroType;
        if (hero == null || hero == com.perblue.heroes.network.messages.UnitType.DEFAULT) {
          System.out.println("[action] UNLOCK_HERO: héros manquant"); return false;
        }
        if (!com.perblue.heroes.game.logic.HeroHelper.canUnlock(hero, user)) {
          System.out.println("[action] UNLOCK_HERO: " + hero
              + " REFUSÉ (canUnlock=false — déjà possédé / indisponible / pas de fragment)"); return false;
        }
        com.perblue.heroes.game.objects.IHero h = com.perblue.heroes.game.logic.HeroHelper.unlock(hero, user);
        System.out.println("[action] UNLOCK_HERO " + hero
            + " → héros débloqué (coût GOLD + fragments consommés, roster mis à jour, logique du jeu)");
        return h != null;
      }
      case "COMPLETE_QUEST": {
        // Réclamation d'une QUÊTE / ACHIEVEMENT — logique d'origine EXACTE (QuestHelper.completeQuest, code du
        // jeu). Gap trouvé EN JEU (écran MEDALS → « THANKS! » envoie Action{COMPLETE_QUEST, extra={ID=<questID>}}
        // pour chaque quête ; le serveur les DROPPAIT → récompenses (fragments de héros, diamants…) non créditées.
        // Le client (ActionHelper.doAction) complète la quête localement et l'envoie ; le serveur AUTORITATIF
        // ré-exécute la même logique. completeQuest(id, user) : isReadyToComplete (isUnlocked +
        // QuestStats.getCompleteRequirement(id).isSatisfied contre l'ÉTAT SERVEUR — campagne/team-level/héros,
        // tout persisté → anti-triche RÉEL : lève ClientErrorCodeException QUEST_REQUIREMENTS_NOT_SATISFIED si
        // non mérité, attrapée par applyAction → refus) → RewardHelper.giveReward (donne l'objet/fragment/diamants)
        // + setQuestCompletedCount/setQuestLastCompletedTime/removeQuestCounters + updateClientAchievement. Les
        // récompenses hors this.extra (héros/diamants) sont re-synchronisées par applyAction (resyncHeroes/Diamonds).
        Object qidO = m.extra == null ? null
            : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
        if (qidO == null) { System.out.println("[action] COMPLETE_QUEST: pas d'ID dans l'extra"); return false; }
        int questID;
        try { questID = Integer.parseInt(qidO.toString()); }
        catch (NumberFormatException e) { System.out.println("[action] COMPLETE_QUEST: ID illisible " + qidO); return false; }
        com.perblue.heroes.game.logic.QuestHelper.completeQuest(questID, user);   // lève si non mérité (anti-triche)
        // PERSISTANCE de l'état de quête : IndividualUser copie completedQuests/questCompletionTimes/… depuis
        // individualUserExtra au setExtra (IntIntMap gdx) → HORS this.extra → les mutations de completeQuest
        // (setQuestCompletedCount/setQuestLastCompletedTime/setQuestStartedTime(0)/removeQuestCounters) sont
        // perdues au round-trip wire sans re-sync (même schéma que diamants/héros). Sinon : au reload la quête
        // réapparaît « à réclamer » → re-réclamation / duplication de récompense. On re-synchronise la SEULE
        // quête complétée (les récompenses gold/items = this.extra auto ; diamants/héros = resync par applyAction).
        com.perblue.heroes.game.objects.IndividualUser giu =
            (com.perblue.heroes.game.objects.IndividualUser) user.getIndividual();
        individualUserExtra.completedQuests.put(questID, giu.getQuestCompletionCount(questID));
        individualUserExtra.questCompletionTimes.put(questID, giu.getQuestLastCompletedTime(questID));
        individualUserExtra.questStartTimes.put(questID, giu.getQuestStartedTime(questID));
        if (individualUserExtra.questCounters != null) {
          final int qid = questID;
          individualUserExtra.questCounters.removeIf(qc ->
              ((com.perblue.heroes.network.messages.QuestCounterData) qc).questID == qid);
        }
        System.out.println("[action] COMPLETE_QUEST id=" + questID + " → récompense créditée + complétion persistée (logique du jeu)");
        return true;
      }
      case "VIEW_DAILY_QUESTS": {
        // Le client (QuestsScreen, à l'ouverture) envoie Action{VIEW_DAILY_QUESTS} (extra vide). Le client NE
        // marque PAS les quêtes vues localement (vérifié au bytecode : il ne fait que doAction) → c'est au
        // SERVEUR de marquer les quêtes QUOTIDIENNES débloquées comme VUES (efface la pastille « nouveau »).
        // Logique du jeu : IndividualUser.setViewedDailyQuest(id) écrit dans this.extra.viewedDailyQuests
        // (= individualUserExtra, PARTAGÉ → auto-persisté). On énumère les IDs via QuestStats.getQuest (évite
        // QuestHelper.getUnlockedDailyQuests → QuestStats.getAllQuestIDs, chemin CLIENT fragile headless :
        // thread-check + cast gdx Array). On ne marque que les DAILY_QUEST DÉBLOQUÉES (isUnlocked) — marquer
        // une quête verrouillée la ferait « déjà vue » à son futur déblocage (pastille perdue).
        com.perblue.heroes.game.objects.IndividualUser vgiu =
            (com.perblue.heroes.game.objects.IndividualUser) user.getIndividual();
        int marked = 0;
        for (int id = 1; id <= 5000; id++) {
          try {
            if (com.perblue.heroes.game.data.quests.QuestStats.getQuest(id) == null) continue;
            if (com.perblue.heroes.game.data.quests.QuestStats.getType(id)
                != com.perblue.heroes.game.data.quests.QuestType.DAILY_QUEST) continue;
            if (!com.perblue.heroes.game.logic.QuestHelper.isUnlocked(id, user)) continue;
            if (!vgiu.hasViewedDailyQuest(id)) { vgiu.setViewedDailyQuest(id); marked++; }
          } catch (Throwable ignore) {}
        }
        System.out.println("[action] VIEW_DAILY_QUESTS → " + marked + " quête(s) quotidienne(s) marquée(s) vue(s)");
        return true;
      }
      case "REDEEM_DAILY_QUESTS": {
        // Réclamation des récompenses WEEKLY (barre « Rewards 0/5 » de l'écran QUESTS, « Come back Monday »).
        // Logique d'origine EXACTE : QuestHelper.redeemWeeklyRewards(user, checkDay=true). Elle vérifie
        // (anti-triche RÉEL) : LUNDI (getUserDailyActivityDayOfWeek==2 → sinon WEEKLY_QUEST_WRONG_DAY) + pas
        // déjà réclamé (dailyUses "claim_weekly_quest_rewards"==0 ET LAST_REDEEMED+2j ≤ now → sinon
        // WEEKLY_QUEST_ALREADY_CLAIMED). Puis convertit le nb de quêtes quotidiennes faites cette semaine
        // (WEEKLY_DAILY_QUESTS_COMPLETE = le « X/105 ») en N boîtes-récompense (getWeeklyQuestRewardsForQuestsCompleted)
        // → WEEKLY_QUEST_REWARDS += N, reset le compteur, incDailyUses, setTime. Tout en UserFlag/times
        // (this.extra → AUTO-PERSISTÉ). Les boîtes se réclament ensuite via le flux d'ouverture (claimWeeklyReward,
        // roll de weekly_quest_rewards.tab). L'exception (mauvais jour / déjà réclamé) remonte → applyAction refuse.
        com.perblue.heroes.game.logic.QuestHelper.redeemWeeklyRewards(user, true);
        System.out.println("[action] REDEEM_DAILY_QUESTS → récompenses weekly converties en boîtes (logique du jeu)");
        return true;
      }
      case "BATTLE_PASS_V2_CLAIM_REWARD": {
        // Réclamer la récompense d'UN palier du battle pass. extra = {TYPE, INDEX=palier, MODE=premium?}
        // (relevé au bytecode : ClientActionHelper.claimBattlePassRewards). Logique d'origine EXACTE :
        // BattlePassV2Helper.claimReward(user, bp, tier, isPremium, false). Elle vérifie (anti-triche RÉEL,
        // claimableReward) : progress ≥ points du palier (BATTLE_PASS_MISSING_POINTS), premium débloqué si
        // demandé (BATTLE_PASS_MISSING_PREMIUM), palier pas déjà réclamé (BATTLE_PASS_TIER_ALREADY_CLAIMED) →
        // lève ClientErrorCodeException si illégitime (remonte → applyAction refuse). Sinon RewardHelper
        // .giveRewards (héros/diamants/objets → resync par applyAction ; or → this.extra auto) + bp
        // .addClaimedFree/PremiumRewards (marque le palier réclamé DANS notre BattlePassV2Data persisté via le
        // wrapper writes-through). NB progress = ResourceType.QUEST_POINTS, accumulé par les quêtes.
        com.perblue.heroes.game.objects.IBattlePassV2Data bp = com.perblue.heroes.DH.app.getUserBattlePassV2();
        if (bp == null) { System.out.println("[action] BATTLE_PASS_V2_CLAIM_REWARD: battle pass non lié"); return false; }
        Object tierO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.INDEX);
        Object premO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.MODE);
        if (tierO == null) { System.out.println("[action] BATTLE_PASS_V2_CLAIM_REWARD: INDEX (palier) manquant"); return false; }
        int tier = Integer.parseInt(tierO.toString());
        boolean premium = premO != null && Boolean.parseBoolean(premO.toString());
        // GARDE AUTORITATIVE anti-double-claim, avec la SÉMANTIQUE DU JEU (pas une règle inventée) :
        // isFreeTierClaimed/isPremiumTierClaimed est LE prédicat « palier réclamé » du jeu (celui que l'UI
        // utilise pour griser un palier déjà pris). Le client empêche le re-claim en cachant le bouton ; mais
        // la garde INTERNE de claimReward (claimableReward) teste `rewardTierClaimed = list.isEmpty()`, donc ne
        // rebloque PAS un palier à récompense NON vide → double-claim possible si on ne l'exécute qu'elle. Un
        // serveur AUTORITATIF doit refuser ce que le client empêche : on refuse via le prédicat OFFICIEL du jeu.
        boolean already = premium
            ? com.perblue.heroes.game.logic.BattlePassV2Helper.isPremiumTierClaimed(user, bp, tier)
            : com.perblue.heroes.game.logic.BattlePassV2Helper.isFreeTierClaimed(user, bp, tier);
        if (already) {
          System.out.println("[action] BATTLE_PASS_V2_CLAIM_REWARD palier=" + tier + " premium=" + premium
              + " → REFUSÉ (déjà réclamé — anti-triche, prédicat isFreeTierClaimed/isPremiumTierClaimed du jeu)");
          return false;
        }
        com.perblue.heroes.game.logic.BattlePassV2Helper.claimReward(user, bp, tier, premium, false);
        System.out.println("[action] BATTLE_PASS_V2_CLAIM_REWARD palier=" + tier + " premium=" + premium
            + " → récompense créditée + palier réclamé (logique du jeu, anti-triche progress≥points)");
        return true;
      }
      case "BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS": {
        // Réclamer les récompenses NON réclamées d'une saison TERMINÉE. extra = {TYPE} (bytecode :
        // ClientActionHelper.collectEndedSeasonRewards). Logique d'origine : BattlePassV2Helper
        // .collectEndedSeasonRewards(user, bp) → giveRewards(previousUnclaimedFree/Premium) + clear. Ces
        // listes ne sont peuplées qu'au changement de saison côté serveur PerBlue (report des paliers non pris) ;
        // sur notre saison roulante elles restent vides sauf rollover → don idempotent (rien à donner = no-op).
        com.perblue.heroes.game.objects.IBattlePassV2Data bp = com.perblue.heroes.DH.app.getUserBattlePassV2();
        if (bp == null) { System.out.println("[action] BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS: battle pass non lié"); return false; }
        com.perblue.heroes.game.logic.BattlePassV2Helper.collectEndedSeasonRewards(user, bp);
        System.out.println("[action] BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS → récompenses de saison précédente données (logique du jeu)");
        return true;
      }
      case "BATTLE_PASS_V2_BUYOUT": {
        // Achat des paliers restants (« buyout ») en DIAMANTS. extra = {ID, TYPE} — le palier courant n'est
        // PAS transmis (bytecode : ClientActionHelper.doBattlePassBuyout) → le SERVEUR le DÉRIVE de la
        // progression : getTierByPoints(progress, startTime) (même dérivation que getBuyoutRewards du jeu).
        // Logique d'origine : BattlePassV2Helper.doBattlePassBuyout(user, bp, tierCourant) → collecte les
        // paliers déjà atteints, DÉBITE getBuyoutCost en DIAMONDS (UserHelper.chargeUser → resync par
        // applyAction), pose progress = points max, réclame tous les paliers restants. buyoutAvailable faux →
        // NO_BATTLE_PASS_BUYOUT (remonte → refus). Diamants insuffisants → chargeUser lève (refus).
        com.perblue.heroes.game.objects.IBattlePassV2Data bp = com.perblue.heroes.DH.app.getUserBattlePassV2();
        if (bp == null) { System.out.println("[action] BATTLE_PASS_V2_BUYOUT: battle pass non lié"); return false; }
        int currentTier = com.perblue.heroes.game.logic.BattlePassV2Helper.getTierByPoints(bp.getProgress(), bp.getStartTime());
        com.perblue.heroes.game.logic.BattlePassV2Helper.doBattlePassBuyout(user, bp, currentTier);
        System.out.println("[action] BATTLE_PASS_V2_BUYOUT palierCourant=" + currentTier
            + " → paliers restants achetés (diamants débités, récompenses créditées — logique du jeu)");
        return true;
      }
      case "UPDATE_BATTLE_PASS": {
        // Notification client→serveur (aucun extra, fire-and-forget ; bytecode : BattlePassTab
        // .handleUnclaimedRewardsFromPreviousBattlePass appelle updateBattlePass() quand une NOUVELLE saison a
        // démarré sans récompense en attente). Côté serveur : refreshBattlePass() (exécuté au bind de chaque
        // action) gère DÉJÀ le rollover de saison (reset progress + claims quand startTime change) → on ACQUITTE.
        // La progression (QUEST_POINTS) est déjà autoritative et persistée. (Un push live de BattlePassV2Data
        // pour rafraîchir l'affichage client est reporté — feature verrouillée TL11, non testable en jeu ici.)
        System.out.println("[action] UPDATE_BATTLE_PASS → acquitté (saison rafraîchie au bind ; progress persisté)");
        return true;
      }
      case "VIEW_BATTLE_PASS_SCORE": {
        // Le joueur a REGARDÉ son score battle pass → marquer le score vu (efface l'indicateur « nouveaux
        // points »). extra = {COUNT=score vu} = getResource(QUEST_POINTS) = la progression (bytecode :
        // BattlePassTab l.246). Logique fidèle : bp.setLastSeenProgress(count) (écrit dans notre message
        // persisté via le wrapper). Repli sur la progression courante si COUNT absent.
        com.perblue.heroes.game.objects.IBattlePassV2Data bp = com.perblue.heroes.DH.app.getUserBattlePassV2();
        if (bp == null) { System.out.println("[action] VIEW_BATTLE_PASS_SCORE: battle pass non lié"); return false; }
        Object cntO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.COUNT);
        int seen = cntO != null ? (int) Long.parseLong(cntO.toString()) : bp.getProgress();
        bp.setLastSeenProgress(seen);
        System.out.println("[action] VIEW_BATTLE_PASS_SCORE → lastSeenProgress=" + seen + " (marqué vu, persisté)");
        return true;
      }
      case "MARK_MAIL_OPENED": {
        // Marquer un courrier comme LU (extra={ID}). État de la mailbox (hors userExtra) → persisté à part.
        Object idO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
        if (idO == null) { System.out.println("[action] MARK_MAIL_OPENED: ID manquant"); return false; }
        com.perblue.heroes.network.messages.MailMessage mm = findMail(Long.parseLong(idO.toString()));
        if (mm == null) { System.out.println("[action] MARK_MAIL_OPENED: courrier introuvable " + idO); return false; }
        mm.opened = true;
        System.out.println("[action] MARK_MAIL_OPENED id=" + mm.iD + " → marqué lu");
        return true;
      }
      case "TAKE_MAIL_ATTACHMENTS": {
        // Réclamer les PIÈCES JOINTES (récompenses) d'un courrier (extra={ID}). Logique : on DONNE chaque
        // RewardDrop via RewardHelper.giveReward (héros/diamants/objets → resync par applyAction ; gold/items
        // this.extra auto), PUIS on VIDE les attachments (anti-re-claim RÉEL : un 2ᵉ TAKE ne redonne rien).
        // Un courrier non persistant vidé sera supprimé par le client à la fermeture (shouldDeleteOnClose).
        Object idO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
        if (idO == null) { System.out.println("[action] TAKE_MAIL_ATTACHMENTS: ID manquant"); return false; }
        com.perblue.heroes.network.messages.MailMessage mm = findMail(Long.parseLong(idO.toString()));
        if (mm == null) { System.out.println("[action] TAKE_MAIL_ATTACHMENTS: courrier introuvable " + idO); return false; }
        java.util.List<?> att = mm.extra == null ? null : mm.extra.attachments;
        if (att == null || att.isEmpty()) {
          System.out.println("[action] TAKE_MAIL_ATTACHMENTS id=" + mm.iD + " → REFUSÉ (aucune pièce jointe / déjà prises)");
          return false;
        }
        int given = 0;
        for (Object o : att) {
          com.perblue.heroes.game.logic.RewardHelper.giveReward(user,
              (com.perblue.heroes.network.messages.RewardDrop) o,
              com.perblue.heroes.game.logic.RewardSourceType.NORMAL, false, "mail", Long.toString(mm.iD));
          given++;
        }
        mm.extra.attachments = new java.util.ArrayList<>();   // vidées → plus rien à reprendre
        mm.opened = true;
        System.out.println("[action] TAKE_MAIL_ATTACHMENTS id=" + mm.iD + " → " + given
            + " pièce(s) jointe(s) créditée(s) (logique du jeu) + vidées");
        return true;
      }
      case "DELETE_MAIL_MESSAGE": {
        // Supprimer un courrier de la mailbox (extra={ID}).
        Object idO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
        if (idO == null) { System.out.println("[action] DELETE_MAIL_MESSAGE: ID manquant"); return false; }
        long id = Long.parseLong(idO.toString());
        com.perblue.heroes.network.messages.MailMessage mm = findMail(id);
        if (mm == null) { System.out.println("[action] DELETE_MAIL_MESSAGE: courrier introuvable " + id); return false; }
        mail.remove(mm);
        System.out.println("[action] DELETE_MAIL_MESSAGE id=" + id + " → supprimé (reste " + mail.size() + ")");
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

  /**
   * Re-sync des <b>diamants</b> vers le wire. Les diamants vivent dans un champ dédié
   * {@code IndividualUser.diamonds} (initialisé depuis {@code userInfo.diamonds} au moment du
   * {@code getIndividualUser}, lu/écrit par {@code get/setResource(DIAMONDS)}) — <b>HORS {@code this.extra}</b>,
   * donc NON auto-persisté. Toute logique du jeu qui crédite/débite des diamants (récompense de sign-in,
   * loot, achat) mute ce champ en mémoire ; sans re-sync le gain est perdu au round-trip wire. Même schéma
   * que le niveau d'équipe / le nom. Vérifié `server/smoke/SigninMultiDayTest` (récompense DIAMONDS créditée).
   */
  private void resyncDiamonds(User user) {
    userInfo.diamonds = user.getResource(com.perblue.heroes.network.messages.ResourceType.DIAMONDS);
  }

  /**
   * Re-sync des <b>compteurs et drapeaux</b> ({@code UserFlag}) vers le wire. Comme les diamants/héros, l'objet
   * de jeu {@code User} <b>COPIE</b> ces états depuis {@code userExtra} au chargement (relevé au bytecode :
   * {@code User.setCounts}/{@code setFlags} vident puis re-remplissent des maps internes, en convertissant les
   * clés String↔enum {@code UserFlag}) → les mutations (ex. {@code setCount(WEEKLY_DAILY_QUESTS_COMPLETE)} de
   * {@code completeQuest}, {@code redeemWeeklyRewards}, monthly cards, etc.) restent <b>en mémoire</b> et sont
   * perdues au round-trip wire sans re-sync. On lit les maps privées {@code User.counts} (clés {@code UserFlag}
   * → Integer) et {@code User.flags} ({@code EnumSet<UserFlag>}) par réflexion et on les ré-écrit dans
   * {@code userExtra.counts}/{@code flags} (clés String = {@code name()}). (Les <b>times</b> {@code TimeType}
   * sont, eux, partagés avec {@code this.extra.times} → déjà persistés, cf. VIEWED_CHESTS.)
   */
  @SuppressWarnings("unchecked")
  private void resyncCounts(User user) {
    try {
      java.lang.reflect.Field cf = User.class.getDeclaredField("counts");
      cf.setAccessible(true);
      java.util.Map<Object, Integer> uc = (java.util.Map<Object, Integer>) cf.get(user);
      userExtra.counts.clear();
      for (java.util.Map.Entry<Object, Integer> e : uc.entrySet())
        userExtra.counts.put(((Enum<?>) e.getKey()).name(), e.getValue());
      java.lang.reflect.Field ff = User.class.getDeclaredField("flags");
      ff.setAccessible(true);
      java.util.Set<Object> fl = (java.util.Set<Object>) ff.get(user);
      userExtra.flags.clear();
      for (Object flag : fl) userExtra.flags.put(((Enum<?>) flag).name(), Boolean.TRUE);
    } catch (Throwable t) { System.out.println("[resync] counts/flags: " + t); }
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
  /** État battle pass persisté (progress + paliers réclamés), ou {@code null} si non initialisé. */
  public synchronized byte[] battlePassWire() {
    return battlePassV2Data == null ? null : wire(battlePassV2Data);
  }
  /** Restaure l'état battle pass persisté (au chargement DB ; {@code null}/vide = compte neuf / pré-migration). */
  public synchronized void setBattlePassWire(byte[] bytes) {
    if (bytes != null && bytes.length > 0) battlePassV2Data = read(bytes);
  }

  /**
   * Sérialise la MAILBOX (liste de {@link com.perblue.heroes.network.messages.MailMessage}) en un BLOB :
   * {@code int count} puis, pour chaque courrier, {@code int len + octets wire} (chaque message porte son
   * en-tête de nom → {@link #read} le reconstruit). {@code null} si la mailbox est vide.
   */
  public synchronized byte[] mailWire() {
    if (mail == null || mail.isEmpty()) return null;
    try {
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
      dos.writeInt(mail.size());
      for (com.perblue.heroes.network.messages.MailMessage m : mail) {
        byte[] b = wire(m);
        dos.writeInt(b.length);
        dos.write(b);
      }
      dos.flush();
      return bos.toByteArray();
    } catch (java.io.IOException e) { throw new RuntimeException("sérialisation mailbox", e); }
  }

  /** Restaure la mailbox persistée (au chargement DB ; {@code null}/vide = pas de courrier). */
  public synchronized void setMailWire(byte[] bytes) {
    mail = new java.util.ArrayList<>();
    if (bytes == null || bytes.length == 0) return;
    try {
      java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
      int n = dis.readInt();
      for (int i = 0; i < n; i++) {
        int len = dis.readInt();
        byte[] b = new byte[len];
        dis.readFully(b);
        mail.add(read(b));
      }
    } catch (java.io.IOException e) { throw new RuntimeException("désérialisation mailbox", e); }
  }

  /**
   * Dépose un courrier dans la mailbox (API pour les courriers GÉNÉRÉS PAR LE SERVEUR — récompenses de mode,
   * cadeaux de guilde, remboursements, messages admin… le joueur n'en compose jamais). Persisté au prochain save.
   */
  public synchronized void addMail(com.perblue.heroes.network.messages.MailMessage m) {
    if (mail == null) mail = new java.util.ArrayList<>();
    mail.add(m);
  }

  /** Courrier de la mailbox portant l'{@code id}, ou {@code null}. */
  private com.perblue.heroes.network.messages.MailMessage findMail(long id) {
    if (mail == null) return null;
    for (com.perblue.heroes.network.messages.MailMessage m : mail) if (m.iD == id) return m;
    return null;
  }

  /**
   * Renvoie le {@link com.perblue.heroes.network.messages.BattlePassV2Data} PERSISTÉ (progress + paliers
   * réclamés conservés à travers les boots), en RAFRAÎCHISSANT la partie temporelle : type QUEST (seul
   * implémenté par le 12.1.0), <b>premium pour tous</b> ({@code boughtBattlePass=1}), et la SAISON courante
   * ({@code startTime}/{@code endTime} = fenêtre du mois, ancrée dans {@code ServerContext}). Créé
   * paresseusement (compte neuf / DB pré-migration). NB : quand la saison CHANGE de mois, la progression du mois
   * précédent devrait être remise à 0 — géré ici en comparant {@code startTime} (si la saison a bougé → reset
   * progress + claims, comme le jeu à un changement de saison).
   */
  public synchronized com.perblue.heroes.network.messages.BattlePassV2Data refreshBattlePass() {
    ServerContext.init();
    // Ré-ancre la saison sur le MOIS COURANT à CHAQUE refresh → saison réellement ROULANTE même sans redémarrage
    // du serveur (sinon l'ancre, posée une seule fois à l'init, resterait sur le mois de démarrage → pas de
    // rollover en franchissant un mois). Dès que le mois réel change, getSeasonStartTime() renvoie le nouveau
    // mois → le bloc « nouvelle saison » ci-dessous se déclenche.
    ServerContext.anchorBattlePassSeason();
    long seasonStart, seasonEnd;
    try {
      seasonStart = com.perblue.heroes.game.data.battlepass.BattlePassV2Stats.getSeasonStartTime();
      seasonEnd   = com.perblue.heroes.game.data.battlepass.BattlePassV2Stats.getBattlePassHiddenTime();
    } catch (Throwable t) { seasonStart = 0; seasonEnd = 0; }
    if (battlePassV2Data == null) {
      battlePassV2Data = new com.perblue.heroes.network.messages.BattlePassV2Data();
    }
    com.perblue.heroes.network.messages.BattlePassV2Data bp = battlePassV2Data;
    // ROLLOVER — NOUVELLE SAISON (le mois a changé). Comme le jeu à la fin d'une saison : les récompenses
    // MÉRITÉES (progress ≥ points) mais NON réclamées de la saison écoulée sont conservées dans
    // previousUnclaimed (réclamables ensuite via collectEndedSeasonRewards / BATTLE_PASS_V2_COLLECT_UNCLAIMED_
    // REWARDS → le joueur ne PERD pas ce qu'il a gagné), PUIS progress + paliers réclamés sont remis à zéro.
    if (bp.startTime != 0 && bp.startTime != seasonStart) {
      try {
        com.perblue.heroes.game.objects.IUser boundU = com.perblue.heroes.DH.app.getYourUser();
        if (boundU != null) {
          com.perblue.heroes.game.data.battlepass.BattlePassV2DataWrapper old =
              new com.perblue.heroes.game.data.battlepass.BattlePassV2DataWrapper(bp);  // saison écoulée (startTime encore ancien)
          java.util.List<?> free = com.perblue.heroes.game.logic.BattlePassV2Helper.getUnclaimedFreeRewards(boundU, old, false);
          java.util.List<?> prem = old.getPremiumUnlocked()
              ? com.perblue.heroes.game.logic.BattlePassV2Helper.getUnclaimedPremiumRewards(boundU, old, false)
              : new java.util.ArrayList<>();
          if (bp.previousUnclaimedFreeRewards == null) bp.previousUnclaimedFreeRewards = new java.util.ArrayList<>();
          if (bp.previousUnclaimedPremiumRewards == null) bp.previousUnclaimedPremiumRewards = new java.util.ArrayList<>();
          if (free != null) com.perblue.heroes.game.logic.RewardHelper.mergeRewards(bp.previousUnclaimedFreeRewards, (java.util.List) free);
          if (prem != null) com.perblue.heroes.game.logic.RewardHelper.mergeRewards(bp.previousUnclaimedPremiumRewards, (java.util.List) prem);
          System.out.println("[bp] rollover saison → " + (free == null ? 0 : free.size()) + " récompense(s) gratuite(s) + "
              + (prem == null ? 0 : prem.size()) + " premium non réclamées conservées (previousUnclaimed)");
        }
      } catch (Throwable t) { System.out.println("[bp] rollover: préservation unclaimed ignorée (reset seul): " + t); }
      bp.progress = 0; bp.lastSeenProgress = 0;
      if (bp.claimedFreeRewards != null) bp.claimedFreeRewards.clear();
      if (bp.claimedPremiumRewards != null) bp.claimedPremiumRewards.clear();
    }
    bp.type = com.perblue.heroes.network.messages.BattlePassType.QUEST;
    bp.userID = userID;
    // PREMIUM POUR TOUS (serveurs d'achats fermés, aucun achat réel) : le vrai gate du track premium est le
    // booléen premiumUnlocked (getPremiumUnlocked() = data.premiumUnlocked, prouvé au bytecode) — PAS
    // boughtBattlePass (qui n'est que le compteur « acheté »/état d'upsell). On pose donc premiumUnlocked=true
    // (track premium débloqué → claims premium OK) ET boughtBattlePass=1 (pas d'upsell « acheter »). Posé à
    // CHAQUE refresh (après un éventuel reset de saison) → premium TOUJOURS débloqué, même à une nouvelle saison.
    bp.premiumUnlocked = true;
    bp.boughtBattlePass = 1;
    bp.startTime = seasonStart;
    bp.endTime = seasonEnd;
    return bp;
  }

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
