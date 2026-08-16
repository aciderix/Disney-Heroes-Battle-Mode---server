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
  // CHALLENGES #72 (Sticker Challenges) : progression des défis (UserChallengeDataExtra : slots en cours,
  // completedChapters, nextChallengeID, times). Hors userExtra/individualUserExtra → persisté à part (colonne BLOB).
  // Nullable → état frais (aucun défi) recréé au boot par ServerChallenges.freshData.
  private com.perblue.heroes.network.messages.UserChallengeDataExtra challengeData;

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
    long creation = com.perblue.heroes.util.TimeUtil.serverTimeNow();  // « membre depuis » = heure de JEU (cohérence d'ère)
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
    // L'HEURE DU SERVEUR fait autorité et le client s'y cale (TimeUtil.initClock). On envoie donc
    // `serverTimeNow()` — qui vaut l'horloge réelle par défaut, et l'horloge DÉCALÉE si l'opérateur a posé
    // `-Ddh.clock.offset.hours` (cf. ServerContext.applyClockOffset). Envoyer `currentTimeMillis()` en dur
    // désynchroniserait le client du serveur dès qu'un décalage est en place.
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    bd.serverTime = now;
    bd.currentServer.shardID = shardID;
    userInfo.lastLoginTime = now;
    // « Vu pour la dernière fois » (roster de guilde, tri ONLINE) = BasicUserInfo.userLastActive. Le joueur se
    // connecte MAINTENANT → on l'actualise (sinon 0 = « il y a 20660 j » dans l'écran MEMBERS).
    if (userInfo.basicInfo != null) userInfo.basicInfo.userLastActive = now;
    bd.userInfo = userInfo;
    bd.userExtra = userExtra;
    bd.individualUserExtra = individualUserExtra;
    // CHALLENGES (#72) — l'écran « Sticker Challenges » (TL20) lit BootData.userChallengeDataExtra. On livre l'état
    // PAR JOUEUR : la progression PERSISTÉE (slots/défis en cours) si présente, sinon un état frais (userID correct).
    // historicWeeklyChallenges reste le défaut non-null (wire-sûr).
    bd.userChallengeDataExtra = challengeData != null ? challengeData : ServerChallenges.freshData(userID);
    // BATTLE PASS V2 : le client pose DH.app.userBattlePassV2 depuis bd.battlePassV2Data. Un défaut
    // `new BattlePassV2Data()` a `type = BattlePassType.DEFAULT` → l'écran QUESTS (QuestsScreen.showDot →
    // BattlePassV2Helper.hasUnclaimedRewards → computeRewards) LÈVE « Battle Pass types other than 'Quest'
    // haven't been implemented » (computeRewards ne gère QUE QUEST) → CRASH CLIENT au rendu (trouvé en jeu en
    // ouvrant QUESTS). Le battle pass v2 = le type **QUEST** avec la saison active (contenu). On l'initialise
    // avec la logique du jeu (`BattlePassV2Stats.getSeasonStartTime()` ; type QUEST). progress=0 pour un compte
    // neuf (la progression s'accumule via les quêtes ; non persistée pour l'instant — champ hors userExtra).
    // NB `BattlePassType` n'a que {DEFAULT, QUEST} → ce n'est PAS un décalage d'ère, juste un état non initialisé.
    ServerContext.init();
    bd.battlePassV2Data = refreshBattlePass();   // ancre la saison sur le mois courant (côté serveur)
    // ÈRE DE CONTENU — STAT-SYNC (override opérateur). Le client applique BootData.statDataTxt au boot via
    // SyncStatDataClientHelper.updateStats → GeneralStats.updateStats(map) → parseStats(nom, contenu) pour chaque
    // fichier de `parsedFiles` présent dans la map (vérifié au bytecode). BattlePassV2Stats.getStatClasses() est
    // enregistré dans GENERAL_STAT_FILE_CLASSES → CONSTANT_STATS (`battle_pass_v2_constants.tab`) est re-parsé.
    // Sans ça le client garde SES stats embarquées de l'APK (SEASON_START/HIDE = 2026-04, PASSÉES) → il croit la
    // saison terminée → onglet BATTLE PASS grisé, aucun `BattlePassV2GetData` envoyé. On pousse donc un
    // battle_pass_v2_constants à SAISON COURANTE (mêmes bornes que l'ancre serveur) pour que le client voie une
    // saison active. Clé = nom exact de `parsedFiles` (« .tab » inclus). Faute = pas d'override (dégradé propre).
    String bpConstants = battlePassConstantsStatOverride();
    if (bpConstants != null) {
      if (bd.statDataTxt == null) bd.statDataTxt = new java.util.HashMap<>();
      bd.statDataTxt.put("battle_pass_v2_constants.tab", bpConstants);
    }
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

  /**
   * OUTILLAGE DE COMPTE (dev) — marque TOUS les tutoriels du joueur comme TERMINÉS ({@code step ≥ maxStep}).
   * <p>Sert à rendre un compte de test COHÉRENT avec un niveau d'équipe monté artificiellement : un vrai joueur à
   * TL65 a DÉJÀ fait tous les tutoriels de déblocage des niveaux inférieurs. Sans ça, un tuto « en attente » (ex.
   * {@code SAVED_LINEUPS}, débloqué à TL20) se déclenche sur un écran qui n'a pas sa cible (l'écran de défense
   * d'arène n'a pas le bouton « saved lineups » — {@code SavedLineupHelper.isSavedLineupType} l'exclut) et DEADLOCKE.
   * <p><b>Ce n'est PAS un comportement serveur</b> (contrairement à une suppression dans {@code bootData}, qui
   * priverait de vrais joueurs d'un tuto légitime) : c'est une mise en état du COMPTE, comme {@code SetTeamLevel}.
   * @return nombre d'actes marqués terminés.
   */
  public synchronized int completeAllTutorials() {
    int n = 0;
    if (individualUserExtra.tutorialActs == null) return 0;
    for (Object o : individualUserExtra.tutorialActs) {
      try {
        TutorialAct a = (TutorialAct) o;
        if (a.step < 99999) { a.step = 99999; a.maxStep = 99999; n++; }   // step ≥ maxStep(registre) ⇒ terminé
      } catch (Throwable t) { /* ignoré */ }
    }
    return n;
  }

  /** Nombre de héros possédés (diagnostic). */
  public synchronized int heroCount() { return userExtra.heroes.size(); }

  /** #25 (test) — nb d'entrées de mémoire de pitié + pool d'XP persistés (distinguent crédit autoritaire vs repli). */
  public synchronized int lootMemorySize() {
    return individualUserExtra.lootMemory == null ? 0 : individualUserExtra.lootMemory.size();
  }
  public synchronized int expLootPoolPersisted() { return individualUserExtra.expLootPool; }

  /** EXPEDITION #72 — ID d'expédition PERSISTÉ ({@code individualUserExtra.expeditionID}, seul champ d'expédition dans
   *  l'extra ; le run est un état serveur). Package-private : {@link ServerExpedition}. */
  public synchronized long expeditionIDPersisted() { return individualUserExtra.expeditionID; }
  synchronized void setExpeditionIDPersisted(long id) { individualUserExtra.expeditionID = id; }

  /** ARÈNE #41 — lineups persistées ({@code userExtra.heroLineups}), pour vérification (défense/attaque relues). */
  public synchronized java.util.List<com.perblue.heroes.network.messages.UserHeroLineupData> heroLineupsPersisted() {
    return userExtra.heroLineups == null
        ? java.util.Collections.emptyList() : userExtra.heroLineups;
  }

  /** ARÈNE (vrai PvP) — le {@link User} du jeu de CE compte, lié au contexte (lecture de sa défense par un autre
   *  joueur qui l'attaque). C'est le MÊME chemin que la lecture de sa propre défense — aucune régénération. */
  public synchronized User gameUser() {
    ServerContext.init();
    User u = ClientNetworkStateConverter.getUser(userInfo, userExtra, "opp");
    ServerContext.bind(u, ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "opp"));
    return u;
  }

  /** Identité de base (id/nom/TL) pour peupler une entrée de classement d'arène. */
  public synchronized com.perblue.heroes.network.messages.BasicUserInfo basicInfo() {
    return userInfo.basicInfo;
  }

  /** Outillage TEST : crédite une ressource (ex. GOLD pour tester la création de guilde). GOLD vit dans this.extra
   *  (auto-persisté) ; DIAMONDS via resyncDiamonds (champ dédié). Valeurs via la logique du jeu (setResource). */
  public synchronized void giveResource(com.perblue.heroes.network.messages.ResourceType rt, long amount) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "give");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "give");
    ServerContext.bind(user, iu);
    user.setResource(rt, user.getResource(rt) + amount, "test");
    resyncDiamonds(user);
  }

  /** Outillage TEST/DEV (#72 incr. 3c) : avance les MISSIONS IDLE de {@code cycles} cycles via la méthode DEBUG
   *  DU JEU {@code MissionHelper.debugHurryAllMissions} (complète les timers → génère les {@code MissionClaimData}),
   *  puis re-synchronise la liste des missions. Utile pour vérifier la réclamation sans attendre les heures réelles
   *  (headless ET en jeu). Ne persiste pas (appelant). */
  public synchronized void debugHurryMissions(int cycles) {
    ServerContext.init();
    User user = gameUser();
    com.perblue.heroes.game.missions.MissionHelper.debugHurryAllMissions(user, cycles);
    resyncMissions(user.getIndividual());
  }

  /** Lie CE joueur au contexte de jeu ({@code DH.app}) pour les appels de logique du jeu qui consultent
   *  l'utilisateur courant de façon implicite — notamment les TABLES DE DROP (le tirage des compositions de
   *  breakers d'invasion produit de vrais héros du joueur si un utilisateur est lié, des mobs génériques sinon).
   *  Rend le contexte EXPLICITE au lieu de dépendre du dernier appel effectué. */
  public synchronized void bindGameContext() {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "bind");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "bind");
    ServerContext.bind(user, iu);
  }

  /** REPRISE D'UNE GUILDE INACTIVE (#70) — un gradé peut prendre la direction si le CHEF est resté inactif
   *  assez longtemps. Le seuil vient de la LOGIQUE DU JEU : {@code GuildHelper.getClaimLeaderInactiveTime(rôle)}
   *  — CHAMPION 7 jours, OFFICER 21 jours, les autres rôles {@code -1} (interdit). L'inactivité est mesurée sur
   *  {@code BasicUserInfo.userLastActive} du chef (mis à jour à chaque connexion).
   *
   *  <p>Renvoie {@code null} si la reprise est accordée (le demandeur devient RULER, l'ancien chef redevient
   *  MEMBER, le roster est réordonné), sinon un motif de refus lisible. L'appelant persiste guilde + joueurs. */
  public synchronized String claimInactiveGuild(ServerGuild g, ServerUser leader, long now) {
    if (g == null) return "guilde introuvable";
    if (!inGuild() || currentGuildID() != g.guildID) return "vous n'êtes pas membre de cette guilde";
    com.perblue.heroes.network.messages.GuildRole myRole = currentGuildRole();
    long need = com.perblue.heroes.game.logic.GuildHelper.getClaimLeaderInactiveTime(myRole);
    if (need <= 0) return "votre rôle (" + myRole + ") ne permet pas de reprendre la guilde";
    if (leader == null) return "chef introuvable";
    if (leader.userID == userID) return "vous êtes déjà le chef";
    if (leader.currentGuildRole() != com.perblue.heroes.network.messages.GuildRole.RULER)
      return "le joueur visé n'est pas le chef";
    long lastActive = leader.basicInfo() == null ? 0L : leader.basicInfo().userLastActive;
    long idle = now - lastActive;
    if (idle < need)
      return "le chef n'est pas assez inactif (" + (idle / 86400000L) + " j < " + (need / 86400000L) + " j)";
    // Transfert de direction.
    leader.setGuildRoleDirect(com.perblue.heroes.network.messages.GuildRole.MEMBER);
    userInfo.basicInfo.guildRole = com.perblue.heroes.network.messages.GuildRole.RULER;
    g.memberIDs.remove(userID);
    g.memberIDs.add(0, userID);                  // le CHEF est en tête du roster (convention ServerGuild)
    return null;
  }

  /** Force le rôle de guilde de CE joueur (transferts de direction, promotions autoritatives). */
  public synchronized void setGuildRoleDirect(com.perblue.heroes.network.messages.GuildRole role) {
    if (userInfo.basicInfo != null && role != null) userInfo.basicInfo.guildRole = role;
  }

  /** CRÉDITE des récompenses à CE joueur via la logique du jeu ({@code RewardHelper.giveRewards}) et
   *  resynchronise l'état wire. Utilisé par les réclamations autoritatives (boss d'invasion…). */
  public synchronized void grantRewards(java.util.List<com.perblue.heroes.network.messages.RewardDrop> rewards) {
    if (rewards == null || rewards.isEmpty()) return;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "grantrw");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "grantrw");
    ServerContext.bind(user, iu);
    try {
      com.perblue.heroes.game.logic.RewardHelper.giveRewards(user, rewards,
          com.perblue.heroes.game.logic.RewardSourceType.NORMAL, new String[]{"invasion boss"});
      resyncDiamonds(user); resyncHeroes(user);
    } catch (Throwable t) { System.out.println("[invasion] grantRewards : " + t); }
  }

  /** RÉCOMPENSES DE RANG DE LIGUE (#69) — fin d'invasion. Délègue à la LOGIQUE DU JEU
   *  ({@code InvasionHelper.claimGuildRankRewards} / {@code claimUserRankRewards}, adossées aux tables
   *  {@code invasion_{guild,user}_rank_league_rewards}). Le drapeau anti-double-réclamation vit dans
   *  {@code UserInvasionData} ({@code hasGuildRankRewards}/{@code hasUserRankRewards}) : l'appelant DOIT
   *  re-persister l'état après l'appel. Renvoie {@code true} si une réclamation a bien eu lieu. */
  public synchronized boolean claimInvasionRankRewards(
      com.perblue.heroes.game.objects.IInvasion inv,
      com.perblue.heroes.network.messages.UserInvasionData ud,
      com.perblue.heroes.network.messages.InvasionLeague league, int rank, boolean guildSide) {
    if (inv == null || ud == null || league == null || rank <= 0) return false;
    // Anti-double-réclamation : le drapeau du jeu doit être ARMÉ (récompense en attente).
    if (guildSide ? !ud.hasGuildRankRewards : !ud.hasUserRankRewards) return false;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "rankrw");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "rankrw");
    ServerContext.bind(user, iu);
    ServerInvasionUser siu = new ServerInvasionUser(ud);
    try {
      if (guildSide) {
        com.perblue.heroes.game.logic.InvasionHelper.claimGuildRankRewards(inv, user, siu, league, rank,
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
      } else {
        com.perblue.heroes.game.logic.InvasionHelper.claimUserRankRewards(inv, user, siu, league, rank,
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
      }
      resyncDiamonds(user); resyncHeroes(user);
      return true;
    } catch (Throwable t) {
      Throwable c = t.getCause() != null ? t.getCause() : t;
      System.out.println("[invasion] claim" + (guildSide ? "Guild" : "User") + "RankRewards : " + c);
      return false;
    }
  }

  /** RÉCOMPENSES DE BOSS D'INVASION (#69) — tirées par la LOGIQUE DU JEU
   *  ({@code InvasionHelper.rollBossRewardLoot}, adossée aux tables {@code invasion_boss_rewards*}) selon le
   *  RÔLE du joueur sur ce boss ({@code PARTICIPANT}, {@code FINDER}, {@code FINISHER}, {@code MOST_DAMAGE}…).
   *  Aucune table n'est relue ici : on délègue. */
  public synchronized java.util.List<?> rollInvasionBossRewards(
      com.perblue.heroes.game.objects.IInvasion inv, int bossLevel, int multiplier,
      com.perblue.heroes.network.messages.InvasionBossRewardType type,
      com.perblue.heroes.network.messages.InvasionBossType bossType) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "bossloot");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "bossloot");
    ServerContext.bind(user, iu);
    try {
      // Le snapshot d'évènements spéciaux NE PEUT PAS être null : createDrop appelle
      // getLootResourceMultiplier() dessus (NPE sinon, silencieuse pour l'appelant). SpecialEventSnapshot.NONE
      // = « aucun évènement actif », le neutre déjà utilisé ailleurs (achats de coffres).
      java.util.List<?> loot = com.perblue.heroes.game.logic.InvasionHelper.rollBossRewardLoot(
          inv, user, bossLevel, multiplier, type, bossType,
          com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
      return loot == null ? java.util.Collections.emptyList() : loot;
    } catch (Throwable t) {
      Throwable c = t.getCause() != null ? t.getCause() : t;
      System.out.println("[invasion] rollBossRewardLoot(" + type + ") : " + c);
      return java.util.Collections.emptyList();
    }
  }

  /** Outillage TEST : lit le montant d'une ressource (logique du jeu {@code User.getResource}). */
  public synchronized long resourceAmount(com.perblue.heroes.network.messages.ResourceType rt) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "read");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "read");
    ServerContext.bind(user, iu);
    return user.getResource(rt);
  }

  // FAIT ÉTABLI (#67, bytecode User) : les « points de contest » du joueur NE sont PAS un stock séparé —
  //   getGuildContestPoints() { return DH.app.getYourGuildInfo().contestPoints; }
  //   setGuildContestPoints(n,…) { DH.app.getYourGuildInfo().contestPoints = n; }
  // La SOURCE DE VÉRITÉ est donc GuildInfo.contestPoints, envoyé par le SERVEUR (nous) dans GuildInfo et
  // persisté ici. La ressource ResourceType.GUILD_CONTEST_POINTS n'est qu'un canal d'ÉVÉNEMENT UI
  // (UserProperty.get(...)), pas un stock : c'est pourquoi setResource dessus est un no-op.
  // La VENTILATION par membre (qui a apporté quoi) n'existe nulle part côté client → état serveur (v6).

  /** SCORING contest (#67) — ajoute des points de contest à la GUILDE ({@code GuildInfo.contestPoints} = la
   *  valeur que le jeu lit comme « guild contest points ») ET les impute à CE joueur dans la ventilation par
   *  membre ({@code contestPointsByUser}, qui alimente {@code ContestRankings}). Persisté via {@code saveGuild}. */
  public synchronized void awardGuildContestPoints(ServerGuild g, int points) {
    if (g == null || points <= 0) return;
    g.info.contestPoints += points;
    g.contestPointsByUser.merge(userID, (long) points, Long::sum);
  }

  /** Points de contest apportés par CE joueur à sa guilde (ventilation serveur). */
  public synchronized long contestPointsIn(ServerGuild g) {
    return g == null ? 0L : g.contestPointsByUser.getOrDefault(userID, 0L);
  }

  /** CLÔTURE DE SAISON (#67) — livre à CE joueur les récompenses du palier atteint par SA guilde, par COURRIER
   *  (canal autoritatif, comme les dons et l'admin). Renvoie le nombre de récompenses livrées. */
  public synchronized int deliverContestSeasonReward(String seasonName, int guildRank, ServerContest.Tier tier) {
    if (tier == null || tier.rewards.isEmpty()) return 0;
    deliverMail(com.perblue.heroes.network.messages.MailType.SYSTEM_MESSAGE, "Guild Contest",
        "Contest results: rank #" + guildRank,
        "Your guild finished #" + guildRank + " in « " + seasonName + " ». Congratulations!",
        new java.util.ArrayList<>(tier.rewards));
    return tier.rewards.size();
  }

  // ===================== GUILDES #7 =====================
  // L'appartenance de guilde du joueur vit dans BasicUserInfo (guildID/guildRole), déjà persisté (userInfo BLOB)
  // et relu par le client au boot (ClientNetworkStateConverter → User.setGuildID/Role). L'état de la GUILDE
  // elle-même (roster, nom, perks…) est un objet à part (ServerGuild), persisté dans la table `guilds`.

  /** Le joueur est-il déjà dans une guilde ? ({@code BasicUserInfo.guildID > 0}, cf. GuildHelper.isInGuild). */
  public synchronized boolean inGuild() {
    return userInfo.basicInfo != null && userInfo.basicInfo.guildID > 0;
  }

  /** guildID courant du joueur (0 = aucune). */
  public synchronized long currentGuildID() {
    return userInfo.basicInfo == null ? 0L : userInfo.basicInfo.guildID;
  }

  /** Rôle courant du joueur dans sa guilde ({@code NONE} si aucune). */
  public synchronized com.perblue.heroes.network.messages.GuildRole currentGuildRole() {
    return userInfo.basicInfo == null || userInfo.basicInfo.guildRole == null
        ? com.perblue.heroes.network.messages.GuildRole.NONE : userInfo.basicInfo.guildRole;
  }

  /**
   * CRÉATION DE GUILDE (message {@code CreateGuild}). Le serveur AUTORITATIF débite le coût via la logique du jeu
   * ({@code GuildHelper.chargeForCreation} = 2000 GOLD ; lève {@code ClientErrorCodeException} si insuffisant →
   * REFUS anti-triche), construit la guilde ({@link ServerGuild#create}) et pose l'appartenance du fondateur
   * (RULER) dans {@code BasicUserInfo} (persisté via userInfo). {@code guildID} = id libre fourni par le store.
   * La GOLD vit dans this.extra (auto-persisté) — cf. openChest.
   */
  public synchronized ServerGuild createGuild(com.perblue.heroes.network.messages.CreateGuild m, long guildID) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "guild");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "guild");
    ServerContext.bind(user, iu);
    com.perblue.heroes.game.logic.GuildHelper.chargeForCreation(user);   // débit 2000 GOLD (anti-triche)
    ServerGuild g = ServerGuild.create(guildID, shardID, userID, m);
    userInfo.basicInfo.guildID = guildID;
    userInfo.basicInfo.guildRole = com.perblue.heroes.network.messages.GuildRole.RULER;
    userExtra.guildJoinTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    resyncCounts(user);     // drapeaux/compteurs éventuels
    resyncDiamonds(user);
    return g;
  }

  /** ÉDITE les réglages de guilde ({@code EditGuild}) sur {@code g.info}, selon les permissions du RÔLE du joueur
   *  (logique du jeu {@code GuildHelper.canEdit*}). Renvoie true si au moins un champ a été modifié. */
  public synchronized boolean editGuild(ServerGuild g, com.perblue.heroes.network.messages.EditGuild m) {
    if (g == null || g.info == null) return false;
    com.perblue.heroes.network.messages.GuildRole role = currentGuildRole();
    com.perblue.heroes.network.messages.GuildInfo gi = g.info;
    boolean changed = false;
    if (com.perblue.heroes.game.logic.GuildHelper.canEditDescription(role)) {
      gi.motto = m.motto == null ? "" : m.motto; changed = true;
      gi.autoPostAidRequests = m.autoPostAidRequests;
      gi.ignoreKickedPlayersList = m.ignoreKickedPlayersList;
      gi.tacticiansSeeOfficerChat = m.tacticiansSeeOfficerChat;
      if (m.avatar != null && gi.basicInfo != null) gi.basicInfo.avatar = m.avatar;
    }
    if (com.perblue.heroes.game.logic.GuildHelper.canEditMinTeamLevel(role)) { gi.minTeamLevel = m.minLevel; changed = true; }
    if (com.perblue.heroes.game.logic.GuildHelper.canEditGuildPrivacy(role) && m.newMemberPolicy != null) { gi.newMemberPolicy = m.newMemberPolicy; changed = true; }
    if (com.perblue.heroes.game.logic.GuildHelper.canEditCountry(role) && m.country != null) { gi.country = m.country; changed = true; }
    if (com.perblue.heroes.game.logic.GuildHelper.canEditTimeZone(role) && m.timeZone != null) { gi.timeZone = m.timeZone; changed = true; }
    return changed;
  }

  /** RENOMME la guilde ({@code SetGuildName}) si le rôle le permet ({@code GuildHelper.canRenameGuild}). */
  public synchronized boolean renameGuild(ServerGuild g, String name) {
    if (g == null || g.info == null || g.info.basicInfo == null || name == null || name.isEmpty()) return false;
    if (!com.perblue.heroes.game.logic.GuildHelper.canRenameGuild(currentGuildRole())) return false;
    g.info.basicInfo.previousName = g.info.basicInfo.name;
    g.info.basicInfo.name = name;
    return true;
  }

  /** Retire le joueur de sa guilde (départ / dissolution). Efface l'appartenance persistée (userInfo). */
  public synchronized void leaveGuild() {
    if (userInfo.basicInfo == null) return;
    userExtra.previousGuildID = userInfo.basicInfo.guildID;
    userInfo.basicInfo.guildID = 0L;
    userInfo.basicInfo.guildRole = com.perblue.heroes.network.messages.GuildRole.NONE;
    userExtra.guildJoinTime = 0L;
  }

  /** Change UNIQUEMENT le rôle de guilde du joueur (promotion/rétrogradation), persisté via userInfo. */
  public synchronized void setGuildRoleOnly(com.perblue.heroes.network.messages.GuildRole role) {
    if (userInfo.basicInfo != null) userInfo.basicInfo.guildRole = role;
  }

  /** Rejoint une guilde existante en tant que MEMBER (politique OPEN). Pose l'appartenance persistée. */
  public synchronized void joinGuildAs(long guildID, com.perblue.heroes.network.messages.GuildRole role) {
    userInfo.basicInfo.guildID = guildID;
    userInfo.basicInfo.guildRole = role;
    userExtra.guildJoinTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
  }

  /** Construit le {@code UserGuildUpdate} annonçant au client sa nouvelle appartenance (create/join/leave). */
  public synchronized com.perblue.heroes.network.messages.UserGuildUpdate buildUserGuildUpdate(
      ServerGuild g, com.perblue.heroes.network.messages.GuildRole role,
      com.perblue.heroes.network.messages.GuildUpdateReason reason) {
    com.perblue.heroes.network.messages.UserGuildUpdate u =
        new com.perblue.heroes.network.messages.UserGuildUpdate();
    u.guildID = g == null ? 0L : g.guildID;
    u.guildRole = role;
    u.guildInfo = g == null ? new com.perblue.heroes.network.messages.GuildInfo() : g.info;
    u.reason = reason;
    return u;
  }

  /** GUILD CHECK-IN (écran CHECK IN) — état d'émargement du jour. Max quotidien = logique du jeu
   *  ({@code GuildCheckInHelper.getMaxDailyCheckIns} sur les perks de la guilde) ; reset quotidien = horloge serveur. */
  public synchronized com.perblue.heroes.network.messages.GuildCheckInInfo buildGuildCheckInInfo(ServerGuild g) {
    ServerContext.init();
    com.perblue.heroes.network.messages.GuildCheckInInfo ci =
        new com.perblue.heroes.network.messages.GuildCheckInInfo();
    ci.guildID = g == null ? 0L : g.guildID;
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g == null ? new com.perblue.heroes.network.messages.GuildInfo() : g.info);
    // getMaxDailyCheckIns lit les perks de guilde ; le parse paresseux de guild_perk_levels.tab (lignes TIMED_*
    // à CONTENT_TL vide) peut lever par intermittence tant qu'il n'est pas chaud (cf. warm-up ServerContext).
    // On réessaie une fois puis on retombe sur la base du jeu (7 au niveau de perk 0) pour toujours rendre l'écran.
    int max;
    try { max = com.perblue.heroes.game.logic.GuildCheckInHelper.getMaxDailyCheckIns(perks); }
    catch (Throwable t1) {
      try { max = com.perblue.heroes.game.logic.GuildCheckInHelper.getMaxDailyCheckIns(perks); }
      catch (Throwable t2) { max = 7; System.out.println("[guild] getMaxDailyCheckIns indispo (parse perks) → défaut 7"); }
    }
    ci.maxCheckInsToday = max;
    // nextDailyResetTime = PROCHAINE borne de reset. ⚠️ getLastCheckinResetTime(long) prend un TIMESTAMP de
    // référence (le « maintenant »), PAS le guildID — lui passer guildID donne une valeur 1970 aberrante que le
    // client relit ensuite (info.nextDailyResetTime) et qui casse son check-in. On passe donc serverTimeNow.
    long nowRef = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long lastReset = com.perblue.heroes.game.logic.GuildCheckInHelper.getLastCheckinResetTime(nowRef);
    ci.nextDailyResetTime = lastReset + 24L * 60L * 60L * 1000L;
    ci.totalCheckInsToday = g == null ? 0 : g.checkInsToday();
    ci.totalCheckInsYesterday = 0;
    ci.rewardsClaimed = new java.util.ArrayList<>();
    return ci;
  }

  /**
   * AVATARS de guilde débloqués ({@code GetUnlockedGuildAvatars}) — calculés depuis le NIVEAU de guilde
   * ({@code GuildHelper.getGuildLevel} = le plus haut perk {@code GLn} acheté : GL1→1 … GL6→6, aucun→0) et la
   * table {@code guild_avatars.tab} (colonne {@code REQUIRED_GUILD_LEVEL}). La table du jeu ({@code GuildStats
   * .GUILD_AVATAR_STATS.avatars[]}, indexée par niveau 0..99) est CUMULATIVE : {@code avatars[L]} contient déjà
   * tous les avatars de niveau ≤ L (le parse post-traite en ajoutant les niveaux inférieurs, et pose
   * {@code avatars[0]=avatars[1]}). Donc un seul accès {@code avatars[level].getList()} suffit. On lit la table
   * du jeu par réflexion (champ statique privé), aucune donnée réécrite (§4). Renvoie la liste vide si la table
   * n'est pas chargée (jamais d'échec d'écran). */
  public synchronized java.util.List<com.perblue.heroes.network.messages.Avatar> unlockedGuildAvatars(ServerGuild g) {
    ServerContext.init();
    java.util.List<com.perblue.heroes.network.messages.Avatar> out = new java.util.ArrayList<>();
    if (g == null) return out;
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info);
    int level;
    try { level = com.perblue.heroes.game.logic.GuildHelper.getGuildLevel(perks); }
    catch (Throwable t) { level = 0; }
    try {
      java.lang.reflect.Field statsF =
          com.perblue.heroes.game.data.guild.GuildStats.class.getDeclaredField("GUILD_AVATAR_STATS");
      statsF.setAccessible(true);
      Object stats = statsF.get(null);
      java.lang.reflect.Field arrF = stats.getClass().getDeclaredField("avatars");
      arrF.setAccessible(true);
      Object[] arr = (Object[]) arrF.get(stats);   // GuildStats.GuildAvatarSet[]
      int idx = Math.max(0, Math.min(level, arr.length - 1));
      Object set = arr[idx];
      if (set != null) {
        java.lang.reflect.Method getList = set.getClass().getMethod("getList");
        getList.setAccessible(true);   // méthode publique sur classe imbriquée non publique
        @SuppressWarnings("unchecked")
        java.util.List<com.perblue.heroes.network.messages.Avatar> list =
            (java.util.List<com.perblue.heroes.network.messages.Avatar>) getList.invoke(set);
        if (list != null) out.addAll(list);
      }
    } catch (Throwable t) {
      System.out.println("[guild] unlockedGuildAvatars indispo (table avatars) : " + t);
    }
    return out;
  }

  /**
   * CHECK-IN de guilde ({@code CHECK_IN_TO_GUILD}) — le joueur émarge une fois par jour. AUTORITATIF via la
   * logique du jeu : {@code GuildCheckInHelper.canCheckIn} (garde quotidienne, horloge serveur), {@code checkIn}
   * (état individuel), {@code addIndividualRewards} (récompenses au joueur) ; côté GUILDE on enregistre le membre
   * du jour et on crédite l'INFLUENCE ({@code getInfluenceReward}, plafonnée par {@code getMaxGuildInfluence}).
   * Renvoie la liste des récompenses données, ou {@code null} si déjà émargé aujourd'hui / non autorisé.
   */
  public synchronized java.util.List<com.perblue.heroes.network.messages.RewardDrop> checkInToGuild(ServerGuild g) {
    if (g == null) return null;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "guild");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "guild");
    ServerContext.bind(user, iu);
    // RESET QUOTIDIEN — borne du jeu {@code GuildCheckInHelper.getLastCheckinResetTime(now)} (reset à 5h dans le
    // fuseau serveur). ⚠️ ce helper prend un TIMESTAMP de référence (le « maintenant »), PAS le guildID (le passer
    // guildID donne une valeur 1970 aberrante — bug corrigé). Le set checkedInToday par guilde est purgé quand la
    // borne de reset avance ; garde SERVEUR-AUTORITATIVE (horloge serveur CLOCK_OFFSET=0, non contournable par la
    // date du mobile — même principe que le cooldown des coffres).
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long resetBoundary = com.perblue.heroes.game.logic.GuildCheckInHelper.getLastCheckinResetTime(now);
    if (g.lastCheckInResetTime < resetBoundary) { g.checkedInToday.clear(); g.lastCheckInResetTime = resetBoundary; }
    if (g.checkedInToday.contains(userID)) return null;                 // déjà émargé aujourd'hui
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info);
    g.checkedInToday.add(userID);
    int count = g.checkInsToday();                                      // n-ième émargeur du jour
    long infl;
    try { infl = com.perblue.heroes.game.logic.GuildCheckInHelper.getInfluenceReward(perks, count); }
    catch (Throwable t) { infl = 0; }
    java.util.List<com.perblue.heroes.network.messages.RewardDrop> given = new java.util.ArrayList<>();
    try { com.perblue.heroes.game.logic.GuildCheckInHelper.addIndividualRewards(user, perks, count, given); }
    catch (Throwable t) { System.out.println("[guild] addIndividualRewards: " + t); }
    // État individuel de check-in. NB : GuildCheckInHelper.checkIn() ré-valide via canCheckIn qui dépend de
    // getLastCheckinResetTime (infra de fuseau de guilde indispo headless → lève ALREADY_CHECKED_IN à tort). On
    // reproduit donc DIRECTEMENT les mêmes effets que checkIn() : horodatage LAST_GUILD_CHECK_IN + guildCheckInGuildID
    // + suivi d'activité. La garde quotidienne autoritative est notre set checkedInToday (persisté dans la guilde).
    user.setTime(com.perblue.heroes.network.messages.TimeType.LAST_GUILD_CHECK_IN, now);
    try { com.perblue.heroes.game.logic.UserActivityTracker.onGuildCheckIn(user); } catch (Throwable t) {}
    long max;
    try { max = com.perblue.heroes.game.logic.GuildPerkHelper.getMaxGuildInfluence(perks); }
    catch (Throwable t) { max = Long.MAX_VALUE; }
    g.info.influence = Math.min(g.info.influence + infl, max);
    // resync état joueur (récompenses + état individuel de check-in) vers le wire
    resyncHeroes(user); resyncDiamonds(user); resyncCounts(user);
    individualUserExtra.guildCheckInGuildID = g.guildID;   // checkIn() a posé guildCheckInGuildID = guildID
    System.out.println("[guild] check-in : +" + infl + " influence guilde (total " + g.info.influence
        + "/" + max + "), " + given.size() + " récompense(s) au joueur");
    return given;
  }

  /**
   * UPGRADE d'un perk de guilde ({@code UPGRADE_GUILD_PERK}) — AUTORITATIF via la logique du jeu :
   * {@code GuildPerkHelper.getUpgradeError} (niveau max, influence suffisante…) puis débit de l'influence de
   * guilde ({@code getUpgradeCost}) et incrément du niveau. Le {@code GuildInfoPerkProvider(g.info)} 1-arg mute
   * DIRECTEMENT {@code g.info.perkLevels} (pas de clone). Renvoie le nouveau niveau, ou -1 si refusé.
   */
  public synchronized int upgradeGuildPerk(ServerGuild g, com.perblue.heroes.network.messages.GuildPerkType type) {
    if (g == null || type == null) return -1;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "guild");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "guild");
    ServerContext.bind(user, iu);
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info);
    int cur = perks.getPerkLevel(type);
    com.perblue.heroes.util.localization.ClientErrorCode err;
    try { err = com.perblue.heroes.game.logic.GuildPerkHelper.getUpgradeError(user, perks, type, cur + 1); }
    catch (Throwable t) { System.out.println("[guild] getUpgradeError: " + t); return -1; }
    if (err != null) { System.out.println("[guild] upgrade " + type + " refusé: " + err); return -1; }
    long cost = com.perblue.heroes.game.logic.GuildPerkHelper.getUpgradeCost(perks, type);
    g.info.influence = Math.max(0, g.info.influence - cost);
    perks.setPerkLevel(type, cur + 1);
    System.out.println("[guild] perk " + type + " " + cur + "→" + (cur + 1) + " (-" + cost
        + " influence, reste " + g.info.influence + ")");
    return cur + 1;
  }

  /** PERK TEMPORISÉ (#54) — active un perk timed ({@code GuildPerkHelper.activateTimedGuildPerk}) après validation
   *  ({@code getActivationError}). Pose une fin dans {@code guildInfo.perkEndTimes}. Renvoie true si activé. */
  public synchronized boolean activateTimedGuildPerk(ServerGuild g,
      com.perblue.heroes.network.messages.GuildPerkType type, int amount) {
    if (g == null || type == null) return false;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "guild");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "guild");
    ServerContext.bind(user, iu);
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info);
    com.perblue.heroes.util.localization.ClientErrorCode err;
    try { err = com.perblue.heroes.game.logic.GuildPerkHelper.getActivationError(user, perks, type, amount); }
    catch (Throwable t) { System.out.println("[guild] getActivationError: " + t); return false; }
    if (err != null) { System.out.println("[guild] activation timed " + type + " refusée: " + err); return false; }
    try { com.perblue.heroes.game.logic.GuildPerkHelper.activateTimedGuildPerk(user, perks, type, amount); }
    catch (Throwable t) { System.out.println("[guild] activateTimedGuildPerk: " + t); return false; }
    System.out.println("[guild] perk timed " + type + " activé (×" + amount + ")");
    return true;
  }

  // ===================== CHAT de guilde (#59) =====================
  // Le client (ChatWindow.sendChatMessage) envoie un SendChat{message, room=GUILD, time, toUserID} SANS
  // ajouter le message localement pour les salons de guilde (GUILD/GUILD_WALL/GUILD_OFFICER/GUILD_WAR) — il
  // ATTEND que le serveur lui renvoie le Chat. Le serveur construit le Chat autoritatif (id, expéditeur, guilde,
  // horodatage), l'ARCHIVE dans la guilde (octets wire, persisté) et le renvoie ; GameMain.addGruntListener(Chat)
  // → SocialDataManager.addChat l'affiche. Broadcast aux autres membres connectés = extension multi-connexion.

  /** Construit le Chat autoritatif d'un SendChat de guilde, l'archive dans {@code g} (wire, à persister par
   *  l'appelant), et le renvoie pour diffusion. {@code null} si le message est vide/invalide. */
  public synchronized com.perblue.heroes.network.messages.Chat buildAndStoreGuildChat(
      ServerGuild g, com.perblue.heroes.network.messages.SendChat m) {
    if (g == null || m == null) return null;
    String msg = m.message == null ? "" : m.message.trim();
    if (msg.isEmpty()) return null;
    com.perblue.heroes.network.messages.Chat c = new com.perblue.heroes.network.messages.Chat();
    c.chatID = g.nextChatID++;
    c.room = m.room;
    c.message = msg;
    c.sender = userInfo.basicInfo;   // identité de l'expéditeur (id/nom/TL/avatar/guilde)
    c.guildInfo = g.info == null ? null : g.info.basicInfo;
    c.time = new java.util.Date(com.perblue.heroes.util.TimeUtil.serverTimeNow());
    c.type = m.type != null ? m.type : com.perblue.heroes.network.messages.ChatType.NORMAL;
    // Archive (octets wire de l'objet du jeu — PRINCIPLES §4/§6).
    com.perblue.grunt.translate.util.GruntOutputStream gout = new com.perblue.grunt.translate.util.GruntOutputStream();
    c.writeAll(gout);
    g.addChatWire(gout.getBytes());
    return c;
  }

  /** Historique social (chat de guilde) — envoyé au boot si en guilde. On utilise le message {@code SocialHistory}
   *  DÉDIÉ du jeu (et non un ChatRoomResync brut) : le client le met en TAMPON ({@code delayedSocialHistory}) tant
   *  que le BootData n'est pas traité, puis l'applique APRÈS ({@code checkForDelayedSocialHistory}) — sinon le
   *  {@code socialDataManager.reset()} du BootData EFFACERAIT l'historique (race observée en jeu : 0 message).
   *  {@code lastViewTime}=maintenant → aucun message archivé n'est marqué « nouveau ». */
  public synchronized com.perblue.heroes.network.messages.SocialHistory buildGuildSocialHistory(ServerGuild g) {
    com.perblue.heroes.network.messages.SocialHistory sh =
        new com.perblue.heroes.network.messages.SocialHistory();
    com.perblue.heroes.network.messages.ChatList list =
        new com.perblue.heroes.network.messages.ChatList();
    list.chats = new java.util.ArrayList<>(g == null ? java.util.Collections.emptyList() : g.chatHistory());
    list.lastViewTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    sh.chatLists.put(com.perblue.heroes.network.messages.ChatRoomType.GUILD, list);
    return sh;
  }

  /** Outillage TEST : crédite un OBJET (ex. STAMINA_CONSUMABLE pour tester un don). Via la logique du jeu
   *  ({@code IndividualUser.addItem} → {@code individualUserExtra.items}, auto-persisté). */
  public synchronized void giveItem(com.perblue.heroes.network.messages.ItemType type, int amount) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "give");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "give");
    ServerContext.bind(user, iu);
    iu.addItem(type, amount, false, com.perblue.heroes.game.logic.RewardSourceType.NORMAL, "test");
  }

  /** Outillage TEST : quantité d'un objet en inventaire (pour vérifier un débit de don). */
  public synchronized int itemAmount(com.perblue.heroes.network.messages.ItemType type) {
    ServerContext.init();
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "read");
    return iu.getItemAmount(type);
  }

  /** ÉCONOMIE D'INFLUENCE (#54) — brûler de la stamina en combat de campagne fait GAGNER de l'influence à la
   *  guilde ({@code coût stamina × getStaminaBurnInfluenceMultiplier}, plafonné {@code getMaxGuildInfluence}).
   *  C'est la source PASSIVE qui fait monter l'influence (→ perks achetables). Renvoie l'influence ajoutée. */
  public synchronized long applyStaminaBurnInfluence(ServerGuild g,
      com.perblue.heroes.network.messages.CampaignType type, int chapter, int level) {
    if (g == null || g.info == null || type == null) return 0L;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "infl");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "infl");
    ServerContext.bind(user, iu);
    int cost;
    try { cost = com.perblue.heroes.game.logic.CampaignHelper.getStaminaCost(user, type, chapter, level); }
    catch (Throwable t) { return 0L; }
    long gain = (long) cost * com.perblue.heroes.game.data.guild.GuildStats.getStaminaBurnInfluenceMultiplier();
    if (gain <= 0) return 0L;
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info);
    long max;
    try { max = com.perblue.heroes.game.logic.GuildPerkHelper.getMaxGuildInfluence(perks); }
    catch (Throwable t) { max = com.perblue.heroes.game.data.guild.GuildStats.getBaseInfluenceCap(); }
    long before = g.info.influence;
    g.info.influence = Math.min(g.info.influence + gain, max);
    return g.info.influence - before;
  }

  // ===================== MERCENAIRES (#57) =====================
  // Un membre POSTE des héros (Action POST_HERO) → stockés dans userExtra.recentlyPostedHeroes (auto-persisté),
  // marqué mercenariesPostedAtGuildID. GET_HEROES_FOR_HIRE agrège les héros postés de TOUS les membres de la
  // guilde. HIRE_HERO : un autre membre emprunte un héros pour un combat (gratuit ; le POSTEUR gagne des
  // SOCIAL_BUCKS au reset — getHired/UnhiredMercenaryReward).

  /** Poste un héros comme mercenaire (logique du jeu {@code User.addRecentlyPostedMercenary}, borné par
   *  {@code getMaxPostedHeroesPerUser}). Le héros doit être possédé. */
  public synchronized void postMercenary(com.perblue.heroes.network.messages.UnitType type) {
    if (type == null) return;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "merc");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "merc");
    ServerContext.bind(user, iu);
    if (user.getHero(type) == null) return;             // héros non possédé → ignoré (le client ne le poste pas)
    user.addRecentlyPostedMercenary(type);              // mute userExtra.recentlyPostedHeroes (auto-persisté)
    userExtra.mercenariesPostedAtGuildID = currentGuildID();
  }

  /** COÛT GOLD d'emprunt d'un mercenaire — FORMULE DU JEU : {@code user_values.tab} →
   *  {@code MERCENARY_COST = min(2500+(0.5*P), 2000000000)}, avec {@code P} = puissance du héros.
   *  VÉRIFIÉ : AUCUNE classe cliente n'évalue cette expression (seul {@code UserValue} la déclare, scan du pool
   *  de constantes de tout le jar) → elle est faite pour être évaluée par le SERVEUR, qui remplit
   *  {@code MercenaryHeroData.cost}. On l'évalue avec l'évaluateur d'expressions DU JEU (aucune valeur codée). */
  public static long mercenaryCost(long power) {
    com.perblue.common.bycep.SimpleExpressionContext ctx =
        com.perblue.common.bycep.SimpleExpressionContext.get(true);
    try {
      ctx.setVariable("P", (double) Math.max(0L, power));
      return (long) com.perblue.heroes.game.data.misc.UserValues.getExpression(
          com.perblue.heroes.game.data.misc.UserValue.MERCENARY_COST)
          .evaluate((com.perblue.common.bycep.EvaluationState) ctx);
    } catch (Throwable t) {
      System.out.println("[merc] évaluation MERCENARY_COST échouée: " + t);
      return 0L;
    } finally {
      try { ctx.reset(true); } catch (Throwable ignore) {}
    }
  }

  /** Les mercenaires postés par CE joueur (pour l'agrégation du pool de guilde). */
  public synchronized java.util.List<com.perblue.heroes.network.messages.MercenaryHeroData> postedMercenaries() {
    java.util.List<com.perblue.heroes.network.messages.MercenaryHeroData> out = new java.util.ArrayList<>();
    if (userExtra.recentlyPostedHeroes == null || userExtra.mercenariesPostedAtGuildID != currentGuildID()) return out;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "merc");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "merc");
    ServerContext.bind(user, iu);
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    for (Object o : userExtra.recentlyPostedHeroes) {
      com.perblue.heroes.network.messages.UnitType t = (com.perblue.heroes.network.messages.UnitType) o;
      Object hd = userExtra.heroes == null ? null : userExtra.heroes.get(t);
      if (!(hd instanceof com.perblue.heroes.network.messages.HeroData)) continue;
      com.perblue.heroes.network.messages.MercenaryHeroData md =
          new com.perblue.heroes.network.messages.MercenaryHeroData();
      md.heroData = (com.perblue.heroes.network.messages.HeroData) hd;
      md.ownerID = userID;
      md.ownerName = userInfo.basicInfo == null ? "" : userInfo.basicInfo.name;
      md.postTime = now;
      // COÛT (#57/#64) : puissance du héros (logique du jeu getPower) → formule MERCENARY_COST du jeu.
      long power = 0L;
      try {
        com.perblue.heroes.game.objects.IHero h = user.getHero(t);
        if (h != null) power = Math.max(0L, h.getPower(0));
      } catch (Throwable ignore) {}
      md.cost = (int) Math.min(Integer.MAX_VALUE, mercenaryCost(power));
      out.add(md);
    }
    return out;
  }

  /**
   * CRÉDITE le POSTEUR (ce joueur) quand SON mercenaire est loué (#57, correctif multi-serveur). Montant =
   * logique du jeu {@code MercenaryHelper.getHiredMercenaryReward} (SocialBuckStats × bonus VIP). On respecte la
   * mécanique du jeu : {@code getAndUpdateSocialBucks} remet le compteur hebdo à zéro au changement de semaine
   * serveur ({@code UserFlag.MERCENARY_SOCIAL_BUCKS} = « earned this week »), puis on incrémente ce compteur ET on
   * crédite la MONNAIE {@code ResourceType.SOCIAL_BUCKS}. Le montant vient des stats du jeu, jamais inventé (§4).
   * Renvoie le montant crédité. */
  public synchronized int creditMercenaryHireReward() {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "merc-reward");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "merc-reward");
    ServerContext.bind(user, iu);
    int reward = com.perblue.heroes.game.logic.MercenaryHelper.getHiredMercenaryReward(user);
    // Reset hebdo du compteur d'affichage si nouvelle semaine serveur (mécanique du jeu).
    com.perblue.heroes.game.logic.MercenaryHelper.getAndUpdateSocialBucks(user);
    com.perblue.heroes.game.objects.UserFlag flag = com.perblue.heroes.game.objects.UserFlag.MERCENARY_SOCIAL_BUCKS;
    user.setCount(flag, user.getCount(flag) + reward);
    // ⚠️ CORRECTIF (2026-08-02) : les compteurs UserFlag vivent dans `User.counts`, une carte interne HORS
    // `this.extra` — le commentaire précédent (« auto-persisté this.extra ») était FAUX, et le compteur hebdo
    // « earned this week » repartait donc à zéro à chaque round-trip wire. Mesuré : setTime persiste bien,
    // setCount NON. resyncCounts recopie `User.counts`/`flags` dans `userExtra` (mécanisme déjà en place pour
    // les quêtes et les cartes mensuelles) — il manquait simplement ici.
    resyncCounts(user);
    giveResource(com.perblue.heroes.network.messages.ResourceType.SOCIAL_BUCKS, reward);   // monnaie réelle
    return reward;
  }

  // ===================== GUILD WAR (#68) — compteur d'attaques du joueur =====================

  /**
   * Attaques de guerre déjà consommées par ce joueur dans la guerre commencée à {@code warStartTime}.
   *
   * <p>Le compteur est {@code UserFlag.WAR_ATTACKS_USED} et il est remis à zéro par la logique DU JEU
   * ({@code WarHelper.tryResetUserWarState}) dès que {@code TimeType.WAR_START_TIME_LAST_ATTACK} diffère du
   * début de la guerre courante — c'est ainsi que le client distingue « 0 attaque dans CETTE guerre » de
   * « compteur d'une guerre précédente ».
   */
  public synchronized int warAttacksUsed(long warStartTime) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "war-attacks");
    ServerContext.bind(user, ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "war-attacks"));
    if (user.getTime(com.perblue.heroes.network.messages.TimeType.WAR_START_TIME_LAST_ATTACK) != warStartTime) {
      return 0;
    }
    return user.getCount(com.perblue.heroes.game.objects.UserFlag.WAR_ATTACKS_USED);
  }

  /**
   * Consomme une attaque de guerre : applique d'abord la remise à zéro du jeu si la guerre a changé, puis
   * incrémente {@code WAR_ATTACKS_USED} — et RE-SYNCHRONISE, car ces compteurs vivent hors {@code this.extra}
   * (cf. correctif ci-dessus).
   *
   * @return le nombre d'attaques consommées APRÈS incrément
   */
  public synchronized int consumeWarAttack(long warStartTime) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "war-attacks");
    ServerContext.bind(user, ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "war-attacks"));
    com.perblue.heroes.game.logic.WarHelper.tryResetUserWarState(user, warStartTime);
    com.perblue.heroes.game.objects.UserFlag flag =
        com.perblue.heroes.game.objects.UserFlag.WAR_ATTACKS_USED;
    int used = user.getCount(flag) + 1;
    user.setCount(flag, used);
    // tryResetUserWarState pose aussi WAR_START_TIME_LAST_ATTACK (un TimeType, lui partagé avec this.extra),
    // mais le COMPTEUR exige le re-sync explicite.
    user.setTime(com.perblue.heroes.network.messages.TimeType.WAR_START_TIME_LAST_ATTACK, warStartTime);
    resyncHeroes(user);
    resyncCounts(user);
    return used;
  }

  /** OPÉRATEUR/TEST : remet à zéro le compteur d'attaques de guerre ({@code WAR_ATTACKS_USED} +
   *  {@code WAR_START_TIME_LAST_ATTACK}) — rend au joueur son attaque de base pour re-jouer un combat en jeu.
   *  Ne contourne aucune règle du jeu : c'est l'équivalent d'un changement de guerre (le jeu remet lui-même à
   *  zéro quand {@code WAR_START_TIME_LAST_ATTACK} diffère du début de guerre). */
  public synchronized void resetWarAttacks() {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "war-attacks-reset");
    ServerContext.bind(user, ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "war-attacks-reset"));
    user.setCount(com.perblue.heroes.game.objects.UserFlag.WAR_ATTACKS_USED, 0);
    user.setTime(com.perblue.heroes.network.messages.TimeType.WAR_START_TIME_LAST_ATTACK, 0L);
    resyncCounts(user);
  }

  // ===================== DONS / GUILD AID (#55) =====================
  // Le client (ClientActionHelper.requestStamina) envoie Action{REQUEST_GUILD_DONATION, TYPE=STAMINA}. Le serveur
  // AUTORITATIF valide+charge via la logique du jeu (GuildDonationHelper.requestHelp) puis SYNTHÉTISE la demande
  // opérateur (GuildDonationRequestRow — builder absent du jar client, comme ArenaInfo) et la persiste dans la
  // guilde. La composition STAMINA est dérivée des STATS DU JEU (pas inventée, §4) : chaque don = 1
  // STAMINA_CONSUMABLE ; nombre total de dons = GuildStats.getStaminaHelpAmount(maxTL) (= le total d'aide attendu) ;
  // expiration = now + GuildStats.getHelpRequestDuration().

  /** Poste une demande d'aide STAMINA (autoritatif). Renvoie la {@code GuildDonationRequestRow} créée, ou lève une
   *  {@code ClientErrorCodeException} (déjà une demande active, ressource épuisée, cap de niveau…) — refus fidèle. */
  public synchronized com.perblue.heroes.network.messages.GuildDonationRequestRow postGuildStaminaRequest(ServerGuild g) {
    if (g == null) return null;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "donreq");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "donreq");
    ServerContext.bind(user, iu);
    // 1) VALIDATION + CHARGE (logique du jeu ; lève ClientErrorCodeException si illégitime) — 1× GUILD_DONATION_REQUEST_STAMINA.
    com.perblue.heroes.game.logic.GuildDonationHelper.requestHelp(user,
        com.perblue.heroes.network.messages.GuildDonationRequestType.STAMINA,
        com.perblue.heroes.network.messages.UnitType.DEFAULT, com.perblue.heroes.network.messages.SkillSlot.DEFAULT);
    // 2) Composition de la demande (valeurs des STATS du jeu).
    int maxTL = com.perblue.heroes.game.data.content.ContentHelper.getCurrent(user).getMaxTeamLevel();
    int total = Math.max(1, com.perblue.heroes.game.data.guild.GuildStats.getStaminaHelpAmount(maxTL));
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    com.perblue.heroes.network.messages.GuildDonationRequestRow row =
        new com.perblue.heroes.network.messages.GuildDonationRequestRow();
    row.requestID = g.nextRequestID++;
    row.member = userInfo.basicInfo;
    row.type = com.perblue.heroes.network.messages.GuildDonationRequestType.STAMINA;
    row.donation = com.perblue.heroes.game.logic.RewardHelper.createDrop(
        com.perblue.heroes.network.messages.ItemType.STAMINA_CONSUMABLE, 1L);
    row.totalRequestedDonations = total;
    row.remainingDonations = total;
    row.yourDonations = 0;
    row.expiration = now + com.perblue.heroes.game.data.guild.GuildStats.getHelpRequestDuration();
    // 3) Marqueur côté demandeur (individu, auto-persisté this.extra) — le client interdit les doublons via ceci.
    com.perblue.heroes.network.messages.GuildDonationRequestUserData ud =
        new com.perblue.heroes.network.messages.GuildDonationRequestUserData();
    ud.iD = row.requestID;
    ud.type = com.perblue.heroes.network.messages.GuildDonationRequestType.STAMINA;
    ud.unit = com.perblue.heroes.network.messages.UnitType.DEFAULT;
    ud.skill = com.perblue.heroes.network.messages.SkillSlot.DEFAULT;
    ud.expiration = row.expiration;
    iu.addGuildDonationRequest(ud);
    // 4) Archive dans la guilde (octets wire).
    com.perblue.grunt.translate.util.GruntOutputStream gout = new com.perblue.grunt.translate.util.GruntOutputStream();
    row.writeAll(gout);
    g.addDonationRequestWire(gout.getBytes());
    g.donationsByUser.put(row.requestID, new java.util.LinkedHashMap<>());
    return row;
  }

  /** Lit un ENTIER de {@code guild_constants.tab} par réflexion sur {@code GuildStats.CONSTANT_STATS.getStats()}
   *  (le jeu ne fournit pas de getter pour {@code DONATIONS_PER_HELP_REQUEST}). VALEUR du jeu, jamais inventée
   *  (§4) ; repli documenté si absent. */
  private static int guildConstantInt(String field, int fallback) {
    try {
      java.lang.reflect.Field cs =
          com.perblue.heroes.game.data.guild.GuildStats.class.getDeclaredField("CONSTANT_STATS");
      cs.setAccessible(true);
      Object constStats = cs.get(null);
      Object stats = constStats.getClass().getMethod("getStats").invoke(constStats);
      // Les champs de GuildStats$Constants sont PACKAGE-PRIVATE → getDeclaredField + setAccessible
      // (getField ne voit que les publics et échouerait silencieusement sur le défaut).
      java.lang.reflect.Field f = stats.getClass().getDeclaredField(field);
      f.setAccessible(true);
      return ((Number) f.get(stats)).intValue();
    } catch (Throwable t) {
      System.out.println("[guild] constante " + field + " indispo → défaut " + fallback + " (" + t + ")");
      return fallback;
    }
  }

  /** Poste une demande d'aide SKILL_LEVEL (#55b/#63, autoritatif). Le donneur met en SÉQUESTRE un
   *  {@code SKILL_POINT_CONSUMABLE} ({@code isDonationEscrowed(SKILL_LEVEL)=true}) qui ira au demandeur.
   *  Validation/charge via la logique du jeu ({@code requestHelp} → {@code canRequestSkillLevelHelp} : héros
   *  possédé, skill non au max, cap, ressource {@code GUILD_DONATION_REQUEST_SKILL}). Total de dons =
   *  {@code DONATIONS_PER_HELP_REQUEST} (guild_constants). Lève {@code ClientErrorCodeException} = refus fidèle. */
  public synchronized com.perblue.heroes.network.messages.GuildDonationRequestRow postGuildSkillRequest(
      ServerGuild g, com.perblue.heroes.network.messages.UnitType unit,
      com.perblue.heroes.network.messages.SkillSlot skill) {
    if (g == null || unit == null || skill == null) return null;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "skreq");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "skreq");
    ServerContext.bind(user, iu);
    // 1) VALIDATION + CHARGE (logique du jeu) — 1× GUILD_DONATION_REQUEST_SKILL.
    com.perblue.heroes.game.logic.GuildDonationHelper.requestHelp(user,
        com.perblue.heroes.network.messages.GuildDonationRequestType.SKILL_LEVEL, unit, skill);
    // 2) Composition (valeurs du jeu). Le don = 1 SKILL_POINT_CONSUMABLE (séquestré → remis au demandeur).
    int total = Math.max(1, guildConstantInt("DONATIONS_PER_HELP_REQUEST", 5));
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    com.perblue.heroes.network.messages.GuildDonationRequestRow row =
        new com.perblue.heroes.network.messages.GuildDonationRequestRow();
    row.requestID = g.nextRequestID++;
    row.member = userInfo.basicInfo;
    row.type = com.perblue.heroes.network.messages.GuildDonationRequestType.SKILL_LEVEL;
    row.skill = skill;
    row.hero = heroSummary(user.getHero(unit), unit);    // l'UI affiche le héros ciblé (niveau/rareté/étoiles)
    row.donation = com.perblue.heroes.game.logic.RewardHelper.createDrop(
        com.perblue.heroes.network.messages.ItemType.SKILL_POINT_CONSUMABLE, 1L);
    row.totalRequestedDonations = total;
    row.remainingDonations = total;
    row.yourDonations = 0;
    row.expiration = now + com.perblue.heroes.game.data.guild.GuildStats.getHelpRequestDuration();
    // 3) Marqueur côté demandeur (individu, auto-persisté) — le client interdit les doublons via ceci.
    com.perblue.heroes.network.messages.GuildDonationRequestUserData ud =
        new com.perblue.heroes.network.messages.GuildDonationRequestUserData();
    ud.iD = row.requestID;
    ud.type = com.perblue.heroes.network.messages.GuildDonationRequestType.SKILL_LEVEL;
    ud.unit = unit;
    ud.skill = skill;
    ud.expiration = row.expiration;
    iu.addGuildDonationRequest(ud);
    // 4) Archive dans la guilde (octets wire).
    com.perblue.grunt.translate.util.GruntOutputStream gout = new com.perblue.grunt.translate.util.GruntOutputStream();
    row.writeAll(gout);
    g.addDonationRequestWire(gout.getBytes());
    g.donationsByUser.put(row.requestID, new java.util.LinkedHashMap<>());
    return row;
  }

  /** Résumé du héros ciblé par une demande d'aide ({@code GuildDonationRequestRow.hero}) — l'UI y lit
   *  type/niveau/rareté/étoiles pour dessiner la vignette. */
  private static com.perblue.heroes.network.messages.HeroSummary heroSummary(
      com.perblue.heroes.game.objects.IHero h, com.perblue.heroes.network.messages.UnitType unit) {
    com.perblue.heroes.network.messages.HeroSummary hs = new com.perblue.heroes.network.messages.HeroSummary();
    hs.type = unit;
    if (h != null) {
      hs.level = h.getLevel();
      hs.rarity = h.getRarity();
      hs.stars = h.getStars();
    }
    return hs;
  }

  /** Le DON attendu pour une demande HERO_XP, DÉRIVÉ des fonctions + données du jeu (#63) :
   *  <ul><li>la demande porte sur l'XP qui MANQUE au héros pour son prochain niveau — prouvé par
   *  {@code canRequestHeroXPHelp}, qui REFUSE la demande quand {@code hero.getEXP() == getEXPToNextLevel(level)} ;</li>
   *  <li>{@code DONATIONS_PER_HELP_REQUEST} (=5) dons remplissent la demande → part par don ;</li>
   *  <li>{@code ItemHelper.convertHeroXPToItems} (données {@code ItemStats.EXP_ITEMS_LARGE_TO_SMALL} +
   *  {@code EXP_GIVEN}) convertit cette part en items d'XP RÉELS ;</li>
   *  <li>quantité plafonnée par {@code HERO_XP_DONATION_MAX_QTY} (=4) de guild_constants.</li></ul>
   *  Toutes les valeurs viennent du jeu ; la seule LECTURE STRUCTURELLE est « la part = XP manquant / nb de dons »
   *  (aucune table du jar ne l'énonce — balayage exhaustif des 274 .tab). Documenté dans docs/GUILD_GAPS.md. */
  private static com.perblue.heroes.network.messages.RewardDrop heroXPDonationDrop(
      com.perblue.heroes.game.objects.IHero h) {
    int perDonationCap = Math.max(1, guildConstantInt("HERO_XP_DONATION_MAX_QTY", 4));
    int donations = Math.max(1, guildConstantInt("DONATIONS_PER_HELP_REQUEST", 5));
    long missing = 0L;
    if (h != null) {
      long toNext = com.perblue.heroes.game.data.unit.UnitStats.getEXPToNextLevel(h.getLevel());
      missing = Math.max(0L, toNext - h.getEXP());
    }
    long share = missing / donations;
    java.util.List<?> items = java.util.Collections.emptyList();
    try {
      items = com.perblue.heroes.game.logic.ItemHelper.convertHeroXPToItems(share, java.math.RoundingMode.DOWN);
    } catch (Throwable t) { System.out.println("[guild] convertHeroXPToItems: " + t); }
    for (Object o : items) {                      // 1ᵉ = plus GROSSE dénomination (EXP_ITEMS_LARGE_TO_SMALL)
      if (!(o instanceof com.perblue.heroes.network.messages.RewardDrop)) continue;
      com.perblue.heroes.network.messages.RewardDrop d = (com.perblue.heroes.network.messages.RewardDrop) o;
      if (d.quantity <= 0) continue;
      return com.perblue.heroes.game.logic.RewardHelper.createDrop(
          d.itemType, Math.min(d.quantity, perDonationCap));
    }
    // Part trop petite pour la plus petite dénomination → 1 item d'XP le plus petit (donnée du jeu).
    return com.perblue.heroes.game.logic.RewardHelper.createDrop(smallestEXPItem(), 1L);
  }

  /** Le plus PETIT item d'XP selon les données du jeu ({@code ItemStats.EXP_ITEMS_LARGE_TO_SMALL}, dernier). */
  private static com.perblue.heroes.network.messages.ItemType smallestEXPItem() {
    try {
      java.lang.reflect.Field f =
          com.perblue.heroes.game.data.item.ItemStats.class.getDeclaredField("EXP_ITEMS_LARGE_TO_SMALL");
      f.setAccessible(true);
      java.util.List<?> l = (java.util.List<?>) f.get(null);
      if (l != null && !l.isEmpty())
        return (com.perblue.heroes.network.messages.ItemType) l.get(l.size() - 1);
    } catch (Throwable t) { System.out.println("[guild] EXP_ITEMS_LARGE_TO_SMALL: " + t); }
    return com.perblue.heroes.network.messages.ItemType.EXP_FLASK;
  }

  /** Poste une demande d'aide HERO_XP (#63, autoritatif). Validation/charge par la logique du jeu
   *  ({@code requestHelp} → {@code canRequestHeroXPHelp} : héros possédé, niveau sous plafond, XP non plein,
   *  pas de doublon, ressource {@code GUILD_DONATION_REQUEST_HERO_XP}). Don dérivé par
   *  {@link #heroXPDonationDrop}. Lève {@code ClientErrorCodeException} = refus fidèle. */
  public synchronized com.perblue.heroes.network.messages.GuildDonationRequestRow postGuildHeroXPRequest(
      ServerGuild g, com.perblue.heroes.network.messages.UnitType unit) {
    if (g == null || unit == null) return null;
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "xpreq");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "xpreq");
    ServerContext.bind(user, iu);
    // 1) VALIDATION + CHARGE (logique du jeu) — 1× GUILD_DONATION_REQUEST_HERO_XP.
    com.perblue.heroes.game.logic.GuildDonationHelper.requestHelp(user,
        com.perblue.heroes.network.messages.GuildDonationRequestType.HERO_XP, unit, null);
    // 2) Composition (dérivée des données du jeu).
    com.perblue.heroes.game.objects.IHero hero = user.getHero(unit);
    int total = Math.max(1, guildConstantInt("DONATIONS_PER_HELP_REQUEST", 5));
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    com.perblue.heroes.network.messages.GuildDonationRequestRow row =
        new com.perblue.heroes.network.messages.GuildDonationRequestRow();
    row.requestID = g.nextRequestID++;
    row.member = userInfo.basicInfo;
    row.type = com.perblue.heroes.network.messages.GuildDonationRequestType.HERO_XP;
    row.hero = heroSummary(hero, unit);
    row.donation = heroXPDonationDrop(hero);
    row.totalRequestedDonations = total;
    row.remainingDonations = total;
    row.yourDonations = 0;
    row.expiration = now + com.perblue.heroes.game.data.guild.GuildStats.getHelpRequestDuration();
    // 3) Marqueur côté demandeur (anti-doublon côté client).
    com.perblue.heroes.network.messages.GuildDonationRequestUserData ud =
        new com.perblue.heroes.network.messages.GuildDonationRequestUserData();
    ud.iD = row.requestID;
    ud.type = com.perblue.heroes.network.messages.GuildDonationRequestType.HERO_XP;
    ud.unit = unit;
    ud.expiration = row.expiration;
    iu.addGuildDonationRequest(ud);
    // 4) Archive dans la guilde (octets wire).
    com.perblue.grunt.translate.util.GruntOutputStream xout = new com.perblue.grunt.translate.util.GruntOutputStream();
    row.writeAll(xout);
    g.addDonationRequestWire(xout.getBytes());
    g.donationsByUser.put(row.requestID, new java.util.LinkedHashMap<>());
    return row;
  }

  /** DON (#55b) — CE joueur (donateur) donne à la demande {@code row} (adossée à {@code byUser}). Exécute la
   *  logique AUTORITATIVE du jeu {@code GuildDonationHelper.doDonation} : débite le donateur (useItem/removeItem/
   *  chargeUser) + vérifie les gardes (pas soi-même, active, cap/utilisateur, assez à donner) + mute la demande
   *  (dons restants−1, +1 pour le donateur). Renvoie le {@code RewardDrop} effectivement donné, ou lève une
   *  {@code ClientErrorCodeException} (refus fidèle). {@code offered} = l'offre du client (peut être {@code null}). */
  public synchronized com.perblue.heroes.network.messages.RewardDrop donateToGuildRequest(
      com.perblue.heroes.network.messages.GuildDonationRequestRow row,
      java.util.Map<Long, Integer> byUser,
      com.perblue.heroes.network.messages.RewardDrop offered) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "donate");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "donate");
    ServerContext.bind(user, iu);
    ServerDonationRequest req = new ServerDonationRequest(row, byUser);
    com.perblue.heroes.network.messages.RewardDrop given =
        com.perblue.heroes.game.logic.GuildDonationHelper.doDonation(user, req, offered);
    return given;
  }

  /** Livre au DEMANDEUR (CE joueur) la récompense d'aide accumulée, par COURRIER (comme le backend PerBlue) :
   *  {@code GUILD_DONATION_SUCCESS} (rempli) ou {@code GUILD_DONATION_EXPIRED} (partiel). Pour STAMINA, le total =
   *  nombre de dons reçus × {@code getStaminaConsumableReward()} points d'énergie. Renvoie le montant livré (0 = rien). */
  public synchronized long deliverDonationResult(
      com.perblue.heroes.network.messages.GuildDonationRequestRow row, boolean fulfilled) {
    int donationsReceived = Math.max(0, row.totalRequestedDonations - row.remainingDonations);
    if (donationsReceived <= 0) return 0L;
    // Récompense selon le type (valeurs du jeu). STAMINA : dons × getStaminaConsumableReward() points d'énergie.
    // SKILL_LEVEL (#63) : les SKILL_POINT_CONSUMABLE séquestrés des donneurs vont au demandeur (1/don).
    long amount;
    com.perblue.heroes.network.messages.RewardDrop reward;
    String subject, body;
    switch (row.type) {
      case STAMINA:
        amount = (long) donationsReceived * com.perblue.heroes.game.logic.ItemHelper.getStaminaConsumableReward();
        reward = com.perblue.heroes.game.logic.RewardHelper.createDrop(
            com.perblue.heroes.network.messages.ResourceType.STAMINA, amount);
        subject = fulfilled ? "Stamina help fulfilled" : "Stamina help expired";
        body = donationsReceived + " guildmate(s) donated stamina.";
        break;
      case SKILL_LEVEL:
        amount = donationsReceived;   // 1 SKILL_POINT_CONSUMABLE séquestré par don
        reward = com.perblue.heroes.game.logic.RewardHelper.createDrop(
            com.perblue.heroes.network.messages.ItemType.SKILL_POINT_CONSUMABLE, amount);
        subject = fulfilled ? "Skill help fulfilled" : "Skill help expired";
        body = donationsReceived + " guildmate(s) donated skill points.";
        break;
      case HERO_XP:
        // Le don HERO_XP n'est PAS séquestré (isDonationEscrowed(HERO_XP)=false → useItem chez le donneur) :
        // le demandeur reçoit l'équivalent en items d'XP, nbDons × le drop de la demande (dérivé du jeu).
        if (row.donation == null) return 0L;
        amount = (long) donationsReceived * Math.max(1L, row.donation.quantity);
        reward = com.perblue.heroes.game.logic.RewardHelper.createDrop(row.donation.itemType, amount);
        subject = fulfilled ? "Hero XP help fulfilled" : "Hero XP help expired";
        body = donationsReceived + " guildmate(s) donated hero XP.";
        break;
      default:
        return 0L;
    }
    com.perblue.heroes.network.messages.MailType type = fulfilled
        ? com.perblue.heroes.network.messages.MailType.GUILD_DONATION_SUCCESS
        : com.perblue.heroes.network.messages.MailType.GUILD_DONATION_EXPIRED;
    deliverMail(type, "Guild Aid", subject, body, java.util.Collections.singletonList(reward));
    return amount;
  }

  /** Construit la réponse {@code GuildDonationRequests} (écran GUILD AID) à partir des demandes actives de la guilde,
   *  en marquant {@code yourDonations} pour CE joueur. */
  public synchronized com.perblue.heroes.network.messages.GuildDonationRequests buildGuildDonationRequests(ServerGuild g) {
    com.perblue.heroes.network.messages.GuildDonationRequests resp =
        new com.perblue.heroes.network.messages.GuildDonationRequests();
    resp.guildID = g == null ? currentGuildID() : g.guildID;
    java.util.List<com.perblue.heroes.network.messages.GuildDonationRequestRow> rows = new java.util.ArrayList<>();
    if (g != null) {
      for (com.perblue.heroes.network.messages.GuildDonationRequestRow r : g.donationRequests()) {
        java.util.LinkedHashMap<Long, Integer> byUser = g.donationsByUser.get(r.requestID);
        r.yourDonations = byUser == null ? 0 : byUser.getOrDefault(userID, 0);
        rows.add(r);
      }
    }
    resp.requests = rows;
    return resp;
  }

  // ===================== CADEAUX / GUILD CRATE (#58/#66) =====================
  // Aucun GuildGiftHelper côté client → la génération est 100% OPÉRATEUR (comme le courrier admin #37). Un cadeau
  // = 1 offreur (BasicUserInfo) + 1 horodatage + N récompenses (RewardDrop). Persisté dans ServerGuild (v5).
  // Réclamation autoritative : chaque joueur reçoit les cadeaux plus récents que sa marque (anti-double-claim).

  /** Encode une liste de {@code RewardDrop} en blob : [int n]([int len][octets wire])×n. */
  private static byte[] encodeRewards(java.util.List<com.perblue.heroes.network.messages.RewardDrop> rewards) throws java.io.IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream o = new java.io.DataOutputStream(bos);
    o.writeInt(rewards.size());
    for (com.perblue.heroes.network.messages.RewardDrop r : rewards) {
      com.perblue.grunt.translate.util.GruntOutputStream go = new com.perblue.grunt.translate.util.GruntOutputStream();
      r.writeAll(go);
      byte[] w = go.getBytes();
      o.writeInt(w.length); o.write(w);
    }
    o.flush();
    return bos.toByteArray();
  }

  /** Décode le blob produit par {@link #encodeRewards} → liste de {@code RewardDrop} (objets du jeu). */
  private static java.util.List<com.perblue.heroes.network.messages.RewardDrop> decodeRewards(byte[] blob) throws java.io.IOException {
    java.util.List<com.perblue.heroes.network.messages.RewardDrop> out = new java.util.ArrayList<>();
    if (blob == null || blob.length == 0) return out;
    java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(blob));
    int n = in.readInt();
    for (int i = 0; i < n; i++) {
      byte[] w = new byte[in.readInt()]; in.readFully(w);
      out.add((com.perblue.heroes.network.messages.RewardDrop)
          com.perblue.heroes.network.messages.MessageFactory.getInstance().readMessage(
              new com.perblue.grunt.translate.util.GruntInputStream(w)));
    }
    return out;
  }

  /** GÉNÈRE un cadeau de guilde (capacité OPÉRATEUR/admin, comme le courrier admin #37 ; dans le vrai jeu,
   *  déclenché par un ACHAT d'un membre). L'offreur = CE joueur, les récompenses iront à TOUS les membres qui
   *  réclament. Persiste dans {@code ServerGuild} (v5). */
  public synchronized void grantGuildGift(ServerGuild g, java.util.List<com.perblue.heroes.network.messages.RewardDrop> rewards, long time) {
    if (g == null || rewards == null || rewards.isEmpty()) return;
    ServerContext.init();
    try {
      com.perblue.grunt.translate.util.GruntOutputStream go = new com.perblue.grunt.translate.util.GruntOutputStream();
      userInfo.basicInfo.writeAll(go);
      g.addGift(go.getBytes(), time, encodeRewards(rewards));
    } catch (java.io.IOException e) { throw new RuntimeException("encodage cadeau guilde", e); }
  }

  /** Construit {@code GuildGiftRewards} (écran GUILD CRATE) : offreurs (BasicUserInfo) + récompenses AGRÉGÉES de
   *  tous les cadeaux + dernier horodatage. */
  public synchronized com.perblue.heroes.network.messages.GuildGiftRewards buildGuildGiftRewards(ServerGuild g) {
    com.perblue.heroes.network.messages.GuildGiftRewards resp = new com.perblue.heroes.network.messages.GuildGiftRewards();
    resp.gifters = new java.util.ArrayList<>();
    resp.rewards = new java.util.ArrayList<>();
    resp.eventID = g == null ? 0L : g.giftEventID;
    resp.lastGiftTime = 0L;
    if (g == null) return resp;
    try {
      for (int i = 0; i < g.giftGifterWire.size(); i++) {
        resp.gifters.add(com.perblue.heroes.network.messages.MessageFactory.getInstance().readMessage(
            new com.perblue.grunt.translate.util.GruntInputStream(g.giftGifterWire.get(i))));
        resp.rewards.addAll(decodeRewards(g.giftRewardsBlob.get(i)));
        resp.lastGiftTime = Math.max(resp.lastGiftTime, g.giftTimes.get(i));
      }
    } catch (Exception e) { System.out.println("[guild] buildGuildGiftRewards: " + e); }
    return resp;
  }

  /** RÉCLAME les cadeaux (CLAIM_GUILD_GIFT_REWARDS) : crédite à CE joueur (logique du jeu {@code RewardHelper
   *  .giveRewards}, source {@code PURCHASE}) les récompenses des cadeaux plus récents que sa marque
   *  {@code giftClaimTimes[userID]} ; avance la marque ; renvoie les récompenses accordées (pour l'Update). */
  public synchronized java.util.List<com.perblue.heroes.network.messages.RewardDrop> claimGuildGifts(ServerGuild g) {
    java.util.List<com.perblue.heroes.network.messages.RewardDrop> granted = new java.util.ArrayList<>();
    if (g == null) return granted;
    long since = g.giftClaimTimes.getOrDefault(userID, 0L);
    long newest = since;
    try {
      for (int i = 0; i < g.giftGifterWire.size(); i++) {
        long t = g.giftTimes.get(i);
        if (t <= since) continue;                      // déjà réclamé
        granted.addAll(decodeRewards(g.giftRewardsBlob.get(i)));
        newest = Math.max(newest, t);
      }
      if (!granted.isEmpty()) {
        ServerContext.init();
        User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "gift");
        IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
            individualUserExtra, userID, userInfo.diamonds, "gift");
        ServerContext.bind(user, iu);
        com.perblue.heroes.game.logic.RewardHelper.giveRewards(user, granted,
            com.perblue.heroes.game.logic.RewardSourceType.PURCHASE, new String[]{"guild gift"});
        resyncDiamonds(user); resyncHeroes(user);
        g.giftClaimTimes.put(userID, newest);
      }
    } catch (Exception e) { System.out.println("[guild] claimGuildGifts: " + e); }
    return granted;
  }

  /** Une ligne de roster ({@code PlayerGuildRow}) pour CE joueur (écran membres, ExtendedGuildInfo). */
  public synchronized com.perblue.heroes.network.messages.PlayerGuildRow buildPlayerGuildRow() {
    com.perblue.heroes.network.messages.PlayerGuildRow row =
        new com.perblue.heroes.network.messages.PlayerGuildRow();
    com.perblue.heroes.network.messages.PlayerRow pr =
        new com.perblue.heroes.network.messages.PlayerRow();
    pr.info = userInfo.basicInfo;
    row.playerRow = pr;
    row.joinGuildTime = userExtra.guildJoinTime;
    return row;
  }

  /** ARÈNE (vrai PvP) — ce compte a-t-il une DÉFENSE posée pour {@code type} (donc éligible comme adversaire réel) ?
   *  Vrai ssi au moins une lineup de défense du mode est non vide dans l'état persisté. */
  public synchronized boolean hasArenaDefense(com.perblue.heroes.network.messages.ArenaType type) {
    if (userExtra.heroLineups == null) return false;
    java.util.Set<String> want = new java.util.HashSet<>();
    if (type == com.perblue.heroes.network.messages.ArenaType.COLISEUM) {
      want.add("COLISEUM_DEFENSE_1"); want.add("COLISEUM_DEFENSE_2"); want.add("COLISEUM_DEFENSE_3");
    } else { want.add("FIGHT_PIT_DEFENSE"); }
    for (Object o : userExtra.heroLineups) {
      com.perblue.heroes.network.messages.UserHeroLineupData d =
          (com.perblue.heroes.network.messages.UserHeroLineupData) o;
      if (d != null && d.lineupType != null && want.contains(d.lineupType.name())
          && d.lineup != null && d.lineup.heroes != null && !d.lineup.heroes.isEmpty()) return true;
    }
    return false;
  }

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
   * Outillage TEST : ajoute (ou remonte) un héros à un rang/niveau/étoiles donnés + resync wire. Sert à créer
   * un état exploitable pour tester une feature (ex. un héros avec de la marge de niveau pour SKILL_UPGRADE).
   *
   * <p><b>⚠️ CORRECTIF (2026-08-02) — l'ordre des deux entiers du jeu est (ÉTOILES, NIVEAU), pas l'inverse.</b>
   * Relevé au bytecode : {@code User.createAndAddHero(type, rarity, i3, i4, …)} passe {@code (i3, i4)} à
   * {@code CombatHelper.createUnitData(type, rarity, i3, i4, mode)}, qui fait {@code setStars(i3)} et
   * {@code setLevel(i4)}. Cette méthode passait {@code (level, stars)} → le héros recevait le NIVEAU comme
   * NOMBRE D'ÉTOILES. Conséquence mesurée EN JEU : un héros « niveau 40 » se retrouvait à <b>40 étoiles</b> et
   * le client PLANTAIT au hub — {@code HasEnoughCollectionHeroes.isSatisfied} indexe une liste dimensionnée par
   * {@code UnitStats.getMaxStars} avec {@code hero.getStars()} → {@code IndexOutOfBounds: Index 40 out of
   * bounds for length 7}, dans {@code showDailyQuestMenuDot} au rendu du menu latéral. Le compte devenait
   * inutilisable. On passe désormais {@code (stars, level)} et on repose explicitement les deux.
   */
  public synchronized void grantHero(com.perblue.heroes.network.messages.UnitType type,
      com.perblue.heroes.network.messages.Rarity rarity, int level, int stars) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "grant");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "grant");
    ServerContext.bind(user, iu);
    if (user.getHero(type) == null)
      user.createAndAddHero(type, rarity, stars, level, new String[]{"grant"});   // (ÉTOILES, NIVEAU)
    com.perblue.heroes.game.objects.UnitData ud = (com.perblue.heroes.game.objects.UnitData) user.getHero(type);
    if (ud != null) { ud.setRarity(rarity); ud.setStars(stars); ud.setLevel(level); }
    resyncHeroes(user);
  }

  /** DEV — équipe l'ÉQUIPEMENT COMPLET du rang d'un héros ({@code HeroHelper.giveFullGear}) + persiste. Sert à
   *  préparer un héros avec du gear ENCHANTABLE (les héros grantés n'ont pas de gear par défaut). */
  public synchronized void debugGiveFullGear(com.perblue.heroes.network.messages.UnitType type) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "gear");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "gear");
    ServerContext.bind(user, iu);
    com.perblue.heroes.game.objects.IHero hero = user.getHero(type);
    if (hero != null) com.perblue.heroes.game.logic.HeroHelper.giveFullGear(user, hero, true);
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

    LootResults lr = new LootResults();
    List<?> drops;
    if (type == ChestType.WISH) {
      // PUITS AUX SOUHAITS (#72 incr. 2) — tirage BIAISÉ par le héros CIBLE. Le WISH a son PROPRE contexte
      // (WishingWellDTContext, lit wishingWellHero + poids de pity + rareté/mod max du joueur) et sa PROPRE table
      // (ChestStats.WISHING_WELL_DROPS, hors getDropTable) : c'est le CODE DU JEU (§3, miroir exact de
      // ChestStats.rollWishingWellDisplay). Voir docs/WISHING_WELL.md.
      com.perblue.heroes.game.data.wishingwell.WishingWellStats.WeightConstants wc =
          com.perblue.heroes.game.logic.WishingWellHelper.getWeightConstants(user);
      float oldJ = iu.getWishingWellJackpotWeight();
      float oldHC = iu.getWishingWellHeroChipsWeight();
      // Plancher des poids (code du jeu) : JACKPOT_BASE / HERO_CHIPS_BASE si sous le minimum.
      com.perblue.heroes.game.logic.WishingWellHelper.checkMinWeights(iu, wc);
      java.util.Random rnd = new Random();
      com.perblue.heroes.game.data.wishingwell.WishingWellDTContext wctx =
          new com.perblue.heroes.game.data.wishingwell.WishingWellDTContext(user, rnd);
      wctx.setChestType(type);
      wctx.setCount(count);
      drops = wishingWellTable().rollNode("ROOT", wctx, rnd);          // tirage biaisé cible/poids (code du jeu)
      lr.lootDrops = new DropConverter(user).convertHeroes(true).convert(drops);
      lr.oldWishJackpotWeight = oldJ;
      lr.oldWishHeroChipsWeight = oldHC;
      // GAP §4 PROUVÉ AU BYTECODE : la RAMPE de pity par tirage (nouveau poids après un souhait) N'EST PAS dans le
      // jar client — les setters setWishingWell*Weight ne sont invoqués que par updateWishingWellWeights (applique
      // une valeur FOURNIE) et setTargetHero/checkMinWeights (PLANCHER) ; doPreRollUpdates ne fait que réinitialiser
      // des compteurs d'évènement. C'était la logique serveur autoritative de PerBlue, absente de l'APK → non
      // réimplémentable sans l'INVENTER (§4, même catégorie que CLAIM_COSMETIC_COLLECTION). On expose donc les poids
      // PLANCHERÉS (checkMinWeights) sans rampe (les probas de base getProbabilities restent correctes). Documenté
      // dans docs/SHIMS.md + docs/WISHING_WELL.md.
      lr.newWishJackpotWeight = iu.getWishingWellJackpotWeight();
      lr.newWishHeroChipsWeight = iu.getWishingWellHeroChipsWeight();
    } else {
      DropTable dt = dropTable(type);
      ChestContext ctx = new ChestContext(user);
      ctx.setChestType(type);
      ctx.setCount(count);
      drops = dt.rollNode("ROOT", ctx, new Random());   // vrai roll de la table du jeu
      lr.lootDrops = new DropConverter(user).convert(drops);
    }
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
    // #25 — LOOT AUTORITAIRE (garde-fou §4bis). Le serveur ROULE le butin (graine LOOT reproductible = hash
    // userID, cf. SeedHelper.getDefaultSeed / SET_SEED #23) et le COMPARE au client. Si ÉGAL → l'autorité est
    // CONFIRMÉE : on crédite le tirage SERVEUR et on AVANCE l'état évolutif (pool d'XP + pitié) pour que les
    // combats suivants restent en phase (le loot dépend de cet état — prouvé LootDeterminismTest). Si DIVERGENCE
    // (ou pas de graine) → repli sur loot CLIENT + log (jamais léser un joueur honnête ; signal de triche/écart de
    // repro). Un compte joué DEPUIS la création sur ce serveur reste en phase (même graine userID + même avance)
    // → autorité effective ; le repli protège les états désynchronisés hérités. Voir creditLootAuthoritative.
    boolean[] advancedState = new boolean[1];
    java.util.List lootEarned = (m.base != null && m.base.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN)
        ? creditLootAuthoritative(user, iu, type, m.chapter, m.level, clientLoot, advancedState)
        : clientLoot;
    java.util.List shownDelta = new java.util.ArrayList<>();
    // base : attackers/defenders = Collection de AttackLineupSummary, outcome + stars remplis par le client.
    CampaignHelper.recordOutcome(user, user, level, m.base.outcome, m.base.stars, m.stagesCleared,
        lootEarned, shownDelta, m.base.attackers, m.base.defenders, SpecialEventSnapshot.NONE);

    // COLLECTIONS #72 incr. 2 — MAÎTRISE de combat. Contrairement à SurgeHelper/InvasionHelper, CampaignHelper
    // .recordOutcome n'accumule PAS la maîtrise → on la déclenche ici, en miroir du client (ExpeditionAttackScreen :
    // recordHeroMastery sur CombatOutcome.WIN uniquement). On passe les lineups d'attaque DIRECTEMENT
    // (base.attackers = Collection<AttackLineupSummary> ; recordHeroMastery → AttackHelper.forEachUnit extrait les
    // héros) et on exécute la logique du jeu (§3) CollectionHelper.recordHeroMastery(user, attackers, mode) : elle
    // incrémente collectionMasteryUses (write-through individualUserExtra, auto-persisté), en filtrant elle-même par
    // MIN_HERO_STARS_REQUIRED / collections dispo.
    if (m.base != null && m.base.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN
        && m.base.attackers != null && !m.base.attackers.isEmpty()) {
      try {
        com.perblue.heroes.game.logic.CollectionHelper.recordHeroMastery(user, m.base.attackers, mode);
        System.out.println("[collection] maîtrise de combat accumulée (mode " + mode + ") [persisté]");
      } catch (Throwable t) { System.out.println("[collection] recordHeroMastery: " + t); }
    }

    resyncHeroes(user);   // héros (XP/état) → wire ; stamina/or sont dans this.extra (auto).
    resyncDiamonds(user); // diamants (champ dédié hors this.extra)
    resyncCounts(user);   // compteurs/drapeaux UserFlag (hors this.extra)
    resyncCampaign(iu);   // progression campagne (statuts de niveau) → wire (hors this.extra, comme les héros).
    // Mémoire de pitié : si l'autorité a avancé l'état (updateMemoryUnconditional), NE PAS ré-appliquer les
    // deltas client (double-comptage) ; sinon (repli client) on applique les deltas client comme avant.
    if (!advancedState[0]) applyLootMemory(m);
    // Niveau d'équipe : User.teamLevel est un CHAMP de User (hors this.extra) — getUser le lit depuis
    // userInfo.basicInfo.teamLevel, mais setTeamLevel (montée de niveau via giveTeamXP) ne l'écrit QUE sur
    // l'objet User. Sans re-sync vers le wire, le niveau reste BLOQUÉ à 1 : l'équipe « remonte 1→2 » à chaque
    // palier d'XP (18) et ré-accorde STAMINA_GAIN_ON_LEVEL (+20) EN BOUCLE (au lieu de progresser vers le
    // palier suivant). Même schéma que resyncHeroes/resyncCampaign (§6 persistance complète).
    userInfo.basicInfo.teamLevel = user.getTeamLevel();
  }

  /**
   * <b>CAMPAGNE D'AMITIÉ</b> (#72, MISSIONS) — message {@link com.perblue.heroes.network.messages.FriendshipCampaignAttack}
   * {@code {base:AttackBase, friendPairID:long, nodeNumber, lootEarned, memoryChanges, stagesCleared}}. Le combat est
   * joué CÔTÉ CLIENT (client-autoritatif, patron campagne) ; le serveur AUTORITATIF ré-exécute
   * {@code FriendshipCampaignHelper.recordOutcome} : il VALIDE (assez de {@code FRIEND_STAMINA} = {@code getStaminaCost
   * (node)}, {@code getLevelLockStatus==UNLOCKED}, {@code canUseHeroes(pair,node,attackers)} — sinon no-op = anti-triche)
   * puis débite l'énergie, progresse le nœud ({@code doNodeUpdate}), pose {@code lastBattle}, crédite le loot reçu
   * ({@code RewardHelper.giveRewards}) + XP. Chapitre/niveau normaux (pour l'XP) DÉRIVÉS par le code du jeu :
   * {@code getNormalCampaignChapter(user)} + {@code getNormalCampaignLevel(pair, node, chapter)} (mapping du call-site
   * client, §3/§4). Loot = client (PARTIEL, cohérent §4bis/#25 — graine non rejouée). Persistance via
   * {@code resyncFriendships} (map amitiés) + héros/diamants/compteurs.
   */
  public synchronized void recordFriendCampaignAttack(
      com.perblue.heroes.network.messages.FriendshipCampaignAttack m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "friendcamp");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "friendcamp");
    ServerContext.bind(user, iu);
    if (m == null || m.base == null) return;
    com.perblue.heroes.game.objects.FriendPairID pair =
        com.perblue.heroes.game.objects.FriendPairID.from(m.friendPairID);
    int node = m.nodeNumber;
    int chapter = com.perblue.heroes.game.logic.FriendshipCampaignHelper.getNormalCampaignChapter(user);
    int level = com.perblue.heroes.game.logic.FriendshipCampaignHelper.getNormalCampaignLevel(pair, node, chapter);
    java.util.List loot = m.lootEarned != null ? m.lootEarned : new java.util.ArrayList<>();
    com.perblue.heroes.game.logic.FriendshipCampaignHelper.recordOutcome(
        user, pair, node, m.base.outcome, loot, m.base.attackers, m.base.defenders,
        SpecialEventSnapshot.NONE, chapter, level, false);
    resyncFriendships(iu);   // amitié (campaignBitsEarned/lastBattle/history/progression) → wire
    resyncHeroes(user);      // XP héros
    resyncDiamonds(user);
    resyncCounts(user);
    userInfo.basicInfo.teamLevel = user.getTeamLevel();
  }

  /**
   * <b>RAID de campagne</b> (écran ELITE_CAMPAIGN / raid d'un niveau NORMAL ou ELITE) — message
   * {@link com.perblue.heroes.network.messages.RaidCampaign}{@code {campaignType, chapter, level, raidCount,
   * outcomes:List<RaidOutcome>, rewards}}. Le RAID rejoue le niveau {@code raidCount} fois <b>sans combat</b>
   * (ticket de raid ou énergie), contre des tickets/énergie, pour du loot/gold/XP/progression.
   *
   * <p><b>Séquence CLIENT reproduite fidèlement</b> (relevée au bytecode : {@code CampaignPreviewScreen}/
   * {@code RaidTicketOutcomeWindow}/{@code CampaignScreen}) — le client fait DEUX appels AUTORITATIFS en local
   * avant d'envoyer le message ; le serveur AUTORITATIF les REJOUE sur son propre {@code User} :
   * <ol>
   *   <li>{@code CampaignHelper.chargeForRaid(user, user, type, chapter, level, raidCount, snap, useTickets)}
   *       — VALIDE (assez de tickets/énergie ; VIP {@code RAID_10} si {@code raidCount>1} ; chances quotidiennes
   *       ELITE ; niveau 3★ ou {@code CAMPAIGN_UNLOCKED} → sinon lève {@code ClientErrorCodeException} = anti-triche)
   *       et DÉBITE : {@code RAID_TICKET} via {@code useItem} si {@code useTickets}, sinon la <i>raid tax</i> ;
   *       <b>puis l'énergie TOUJOURS</b> ({@code chargeUser(STAMINA, staminaCost×raidCount)}) ; crédite aussi le
   *       XP d'équipe ({@code giveTeamXP}) + XP poolé. Le booléen {@code useTickets} = {@code
   *       !VIPStats.isUnlocked(RAID_WITHOUT_TICKETS, user)} (EXACTEMENT comme le client).</li>
   *   <li>{@code CampaignHelper.recordRaidOutcome(user, user, type, chapter, level, raidCount, loot, perkBonus,
   *       snap)} — progression ({@code winsAtCurrentStars/totalWins/lastWinTime/battleCount += raidCount},
   *       {@code dailyUses/dailyChances} ELITE), GOLD, objets d'XP, gear juice, et <b>crédite le loot passé</b>
   *       ({@code giveLoot}). Son {@code getTeamXP} interne ne fait que RAPPORTER (pas de double-crédit du XP
   *       d'équipe, déjà donné par {@code chargeForRaid}).</li>
   * </ol>
   *
   * <p><b>Loot = client (PARTIEL, cohérent §4bis/#25)</b> : le client roule le loot par raid (flux RNG {@code LOOT},
   * graine non rejouée côté serveur) et l'envoie dans {@code outcomes[i].loot}. On agrège ces listes et on les
   * passe en 1ᵉʳ paramètre List de {@code recordRaidOutcome} (le loot À CRÉDITER), comme {@code recordCampaignAttack}
   * fait confiance à {@code m.lootEarned}. 2ᵉ paramètre List = {@code perkBonusLoot} (bonus de perk de guilde) —
   * VIDE headless (pas de guilde). La <b>mémoire de loot</b> (pitié) des outcomes est appliquée à part
   * ({@code applyLootMemory}), comme en campagne.
   */
  public synchronized void recordRaidCampaign(com.perblue.heroes.network.messages.RaidCampaign m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "raid");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "raid");
    ServerContext.bind(user, iu);
    applyRaidLevel(user, iu, m);
    resyncAfterRaid(user, iu);
  }

  /**
   * <b>RAID ALL</b> (raid de plusieurs niveaux en une fois) — message
   * {@link com.perblue.heroes.network.messages.RaidAllCampaign}{@code {results:List<RaidCampaign>}}. Le client
   * construit un {@code RaidCampaign} par niveau raidé (même sous-flux que ci-dessus) et les emballe dans
   * {@code results}. Le serveur rejoue CHAQUE {@code RaidCampaign} (charge + record) puis re-synchronise une fois.
   */
  public synchronized void recordRaidAllCampaign(com.perblue.heroes.network.messages.RaidAllCampaign m) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "raidall");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "raidall");
    ServerContext.bind(user, iu);
    if (m.results != null) {
      for (Object o : m.results) applyRaidLevel(user, iu, (com.perblue.heroes.network.messages.RaidCampaign) o);
    }
    resyncAfterRaid(user, iu);
  }

  /** Applique UN {@code RaidCampaign} (charge + record) sur le {@code User} déjà bindé. Voir {@link #recordRaidCampaign}. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void applyRaidLevel(User user, IndividualUser iu, com.perblue.heroes.network.messages.RaidCampaign m) {
    CampaignType type = m.campaignType == null ? CampaignType.NORMAL : m.campaignType;
    int chapter = m.chapter, level = m.level, raidCount = Math.max(1, m.raidCount);
    // Booléen useTickets = EXACTEMENT comme le client (CampaignPreviewScreen) : sans le VIP RAID_WITHOUT_TICKETS,
    // le raid consomme des RAID_TICKET (+ énergie) ; avec, énergie seule (raid tax).
    boolean useTickets = !com.perblue.heroes.game.data.misc.VIPStats.isUnlocked(
        com.perblue.heroes.game.data.misc.VIPFeature.RAID_WITHOUT_TICKETS, user);
    try {
      // (1) DÉBIT + validation anti-triche (lève ClientErrorCodeException si illégitime).
      CampaignHelper.chargeForRaid(user, user, type, chapter, level, raidCount, SpecialEventSnapshot.NONE, useTickets);
    } catch (Throwable t) {
      System.out.println("[raid] REFUSÉ " + type + " " + chapter + "-" + level + " ×" + raidCount + " : " + t);
      return;   // état inchangé (comme un vrai refus serveur) — cohérent avec le client qui a validé de son côté.
    }
    // (2) Loot CLIENT agrégé (somme des outcomes[i].loot). Perk bonus = vide (pas de guilde headless).
    java.util.List clientLoot = new java.util.ArrayList();
    if (m.outcomes != null) {
      for (Object o : m.outcomes) {
        com.perblue.heroes.network.messages.RaidOutcome ro = (com.perblue.heroes.network.messages.RaidOutcome) o;
        if (ro.loot != null && !ro.loot.isEmpty())
          com.perblue.heroes.game.logic.RewardHelper.mergeRewards(clientLoot, ro.loot);
      }
    }
    java.util.List perkBonus = new java.util.ArrayList();
    // (3) LOOT AUTORITAIRE (garde-fou §4bis, comme la campagne) : le serveur roule le butin des raidCount raids
    // et, si == client, crédite SON tirage (la mémoire de pitié est déjà avancée par le roll) ; sinon repli client.
    boolean[] advancedState = new boolean[1];
    java.util.List lootToGive = creditRaidLootAuthoritative(user, iu, type, chapter, level, raidCount, clientLoot, advancedState);
    // RECORD : progression + gold + XP-items + crédit du loot (serveur si autorité confirmée, sinon client).
    CampaignHelper.recordRaidOutcome(user, user, type, chapter, level, raidCount, lootToGive, perkBonus, SpecialEventSnapshot.NONE);
    // (4) Mémoire de pitié : si l'autorité a avancé l'état (updateMemoryUnconditional par raid), NE PAS ré-appliquer
    // les deltas client (double-comptage) ; sinon (repli), appliquer les deltas client des outcomes comme avant.
    if (!advancedState[0] && m.outcomes != null) {
      for (Object o : m.outcomes) {
        com.perblue.heroes.network.messages.RaidOutcome ro = (com.perblue.heroes.network.messages.RaidOutcome) o;
        applyLootMemory(ro.memoryChanges);
      }
    }
    System.out.println("[raid] " + type + " " + chapter + "-" + level + " ×" + raidCount
        + " → appliqué (tickets=" + useTickets + ", loot=" + lootToGive.size()
        + ", autorité=" + advancedState[0] + ") [persisté]");
  }

  /**
   * #25 — CRÉDIT DE LOOT RAID AUTORITAIRE (garde-fou §4bis). Reproduit EXACTEMENT la séquence RNG raid du client
   * (relevé au bytecode {@code RaidTicketOutcomeWindow}) : {@code resetRandom(LOOT)} UNE fois, puis par raid
   * {@code getLoot(...)} + {@code updateMemoryUnconditional} (le flux LOOT AVANCE entre raids ; les raids ne
   * touchent PAS le pool d'XP combat — XP raid séparée), {@code returnRandom(LOOT)} à la fin. Agrège le butin des
   * {@code raidCount} raids et le compare au client : si ÉGAL → crédite le tirage SERVEUR (mémoire déjà avancée) ;
   * si DIVERGENCE → RESTAURE la mémoire (snapshot) et retombe sur le loot CLIENT (jamais léser un honnête).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private java.util.List creditRaidLootAuthoritative(User user, IndividualUser iu, CampaignType type,
      int chapter, int level, int raidCount, java.util.List clientLoot, boolean[] advancedState) {
    advancedState[0] = false;
    java.util.Map memSnapshot = individualUserExtra.lootMemory == null
        ? new java.util.HashMap() : new java.util.HashMap(individualUserExtra.lootMemory);
    try {
      // Graine = CHAÎNE SERVEUR (étude A) : resetRandom UNE fois (le flux LOOT AVANCE entre raids), PAS de
      // getPendingSeed (off-by-one). La mémoire s'avance par raid (updateMemoryUnconditional). En fin de fournée,
      // on avance la chaîne d'UN cran (comme le returnRandom UNIQUE du client) via advanceLootSeedChain (nextLong
      // + setSeed "boot" — évite le NPE réseau de returnRandom headless), INCONDITIONNEL (le client l'avance
      // toujours → on reste en phase). État (mémoire) gardé SEULEMENT sur match ; sur divergence on RESTAURE + repli.
      user.resetRandom(com.perblue.heroes.network.messages.RandomSeedType.LOOT);
      com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
          new com.perblue.heroes.game.objects.GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo());
      java.util.List serverLoot = new java.util.ArrayList();
      for (int i = 0; i < raidCount; i++) {
        com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot cl =
            com.perblue.heroes.game.logic.CampaignLootHelper.getLoot(
                user, type, 0, chapter, level, SpecialEventSnapshot.NONE, perks, true);
        if (cl == null) { individualUserExtra.lootMemory = memSnapshot; return clientLoot; }
        com.perblue.heroes.game.logic.CampaignLootHelper.updateMemoryUnconditional(user, cl, chapter);
        if (cl.combinedLoot != null)
          com.perblue.heroes.game.logic.RewardHelper.mergeRewards(serverLoot, cl.combinedLoot);
      }
      advanceLootSeedChain(user, iu);   // un cran (returnRandom-équivalent, sans réseau) — INCONDITIONNEL
      if (lootMultiset(serverLoot).equals(lootMultiset(clientLoot))) {
        advancedState[0] = true;   // mémoire avancée par les rolls = gardée (autorité)
        System.out.println("[loot-authoritative] #25 RAID AUTORITAIRE ✅ crédit=serveur (==client) " + lootMultiset(serverLoot));
        return serverLoot;
      }
      individualUserExtra.lootMemory = memSnapshot;   // divergence → restaure la mémoire, repli (deltas client via l'appelant)
      System.out.println("[loot-authoritative] #25 RAID DIVERGENCE ⚠️ repli loot CLIENT — serveur="
          + lootMultiset(serverLoot) + " client=" + lootMultiset(clientLoot));
      return clientLoot;
    } catch (Throwable t) {
      individualUserExtra.lootMemory = memSnapshot;
      System.out.println("[loot-authoritative] RAID roll serveur échoué (" + t + ") → confiance client");
      return clientLoot;
    }
  }

  /** Re-synchro complète après un/des raid(s) : héros, diamants, compteurs, progression campagne, niveau d'équipe. */
  private void resyncAfterRaid(User user, IndividualUser iu) {
    resyncHeroes(user);
    resyncDiamonds(user);
    resyncCounts(user);
    resyncCampaign(iu);
    if (userInfo.basicInfo != null) userInfo.basicInfo.teamLevel = user.getTeamLevel();
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
    com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot cl =
        rollAuthoritativeLootFull(user, iu, type, m.chapter, m.level);
    return cl == null ? null : cl.combinedLoot;
  }

  /**
   * #25 — ROLL AUTORITAIRE (objet complet). Reproduit EXACTEMENT le tirage client (flux RNG {@code LOOT}, séparé
   * du combat) : {@code resetRandom(LOOT)} + {@code CampaignLootHelper.getLoot(...)}. <b>Graine = CHAÎNE SERVEUR</b>
   * (étude A, 2026-07-26) : on N'UTILISE PLUS {@code getPendingSeed} (= la graine POST-tirage que le client
   * annonce via {@code SET_SEED REASON=return}, ce qui causait un OFF-BY-ONE → divergence en jeu). On roule avec
   * la graine STOCKÉE du serveur ({@code getSeed(LOOT)}, par défaut {@code SeedHelper.getDefaultSeed(userID)} =
   * hash FNV de l'userID) ; l'appelant AVANCE ensuite la chaîne ({@code returnRandom}-équivalent) pour rester en
   * phase avec le client à chaque combat (prouvé {@code LootSeedChainTest}). Roll PUR ici (aucune avance d'état/
   * graine) — l'appelant décide (garde-fou §4bis). @return le {@code CampaignLoot} roulé, ou {@code null}.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot rollAuthoritativeLootFull(
      User user, IndividualUser iu, CampaignType type, int chapter, int level) {
    user.resetRandom(com.perblue.heroes.network.messages.RandomSeedType.LOOT);   // graine = chaîne serveur (getSeed/défaut userID)
    // GuildInfoPerkProvider sur le GuildInfo du joueur (shim ServerContext ; vide = pas de bonus de perk de
    // guilde, exact pour un joueur sans guilde). SpecialEventSnapshot.NONE (serveur sans évènement, cf. §F).
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo());
    return com.perblue.heroes.game.logic.CampaignLootHelper.getLoot(
        user, type, 0, chapter, level, SpecialEventSnapshot.NONE, perks, true);
  }

  /** #25 (étude A) — AVANCE la chaîne de graines LOOT du serveur comme {@code returnRandom} du client : tire un
   *  {@code nextLong()} du flux LOOT (après {@code getLoot}) → nouvelle graine, stockée ({@code setSeed} reason
   *  "boot" = SANS envoi réseau headless). Garde la chaîne serveur EN PHASE avec le client (S0→S1→…). */
  private void advanceLootSeedChain(User user, IndividualUser iu) {
    long next = user.getRandom(com.perblue.heroes.network.messages.RandomSeedType.LOOT).nextLong();
    iu.setSeed(com.perblue.heroes.network.messages.RandomSeedType.LOOT, next, "boot");
  }

  /**
   * #25 — CRÉDIT DE LOOT AUTORITAIRE (garde-fou §4bis). Roule le butin côté serveur, compare au client, et :
   *  <ul><li><b>si serveur == client</b> → l'autorité est <b>confirmée</b> : on crédite le tirage SERVEUR et on
   *  AVANCE l'état évolutif ({@code setExpLootPool(newExpLootPool)} + {@code updateMemoryUnconditional}) pour que
   *  les combats suivants restent en phase (le loot dépend de cet état — prouvé par {@code LootDeterminismTest}) ;
   *  </li><li><b>si divergence</b> (ou pas de graine) → on RETOMBE sur le loot CLIENT + on logue (jamais léser un
   *  joueur honnête ; signal de triche/écart de repro).</li></ul>
   * @return la liste de {@code RewardDrop} à créditer, et indique via {@code advancedState[0]} si l'état a été
   *         avancé côté serveur (→ l'appelant ne doit PAS ré-appliquer la mémoire client).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private java.util.List creditLootAuthoritative(User user, IndividualUser iu, CampaignType type,
      int chapter, int level, java.util.List clientLoot, boolean[] advancedState) {
    advancedState[0] = false;
    com.perblue.heroes.game.logic.CampaignLootHelper.CampaignLoot sl;
    try {
      sl = rollAuthoritativeLootFull(user, iu, type, chapter, level);
    } catch (Throwable t) {
      System.out.println("[loot-authoritative] roll serveur échoué (" + t + ") → confiance client"); return clientLoot;
    }
    if (sl == null || sl.combinedLoot == null) return clientLoot;   // roll impossible → client (documenté)
    // AVANCE de la CHAÎNE de graine INCONDITIONNELLE : le client fait un returnRandom(LOOT) après CHAQUE combat
    // (match ou non) → le serveur doit avancer sa graine pareil pour rester EN PHASE au combat suivant (sinon
    // décalage cumulatif). L'avance d'ÉTAT (pool/pitié), elle, n'est faite QUE sur match (autorité confirmée) ;
    // sur divergence on retombe sur le loot ET la mémoire CLIENT (comportement d'avant, jamais léser l'honnête).
    advanceLootSeedChain(user, iu);
    boolean match = lootMultiset(sl.combinedLoot).equals(lootMultiset(clientLoot));
    if (match) {
      user.setExpLootPool(sl.newExpLootPool);                                   // avance le pool d'XP (persisté this.extra)
      com.perblue.heroes.game.logic.CampaignLootHelper.updateMemoryUnconditional(user, sl, chapter); // avance la pitié
      advancedState[0] = true;
      System.out.println("[loot-authoritative] #25 AUTORITAIRE ✅ crédité=serveur (==client) " + lootMultiset(sl.combinedLoot)
          + " poolXP→" + sl.newExpLootPool);
      return sl.combinedLoot;
    }
    System.out.println("[loot-authoritative] #25 DIVERGENCE ⚠️ repli sur loot CLIENT (jamais léser l'honnête) — "
        + "serveur=" + lootMultiset(sl.combinedLoot) + " client=" + lootMultiset(clientLoot));
    return clientLoot;
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
    applyLootMemory(m.memoryChanges);
  }

  /** Applique une liste de {@code UserLootMemoryChange} (pitié) vers {@code lootMemory}. Partagé campagne/raid. */
  @SuppressWarnings("unchecked")
  private void applyLootMemory(java.util.List memoryChanges) {
    if (memoryChanges == null || memoryChanges.isEmpty()) return;
    if (individualUserExtra.lootMemory == null) individualUserExtra.lootMemory = new java.util.HashMap();
    for (Object o : memoryChanges) {
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

  /**
   * <b>LANGUE du joueur</b> ({@code SetLanguage{language}}, relevé dans les logs Windows du 2026-08-02 comme
   * reçu mais non traité). Fire-and-forget : le client applique son choix localement et informe le serveur.
   *
   * <p>Le jeu a un champ POUR ÇA : {@code UserExtra.language} (code de langue), écrit par le setter d'origine
   * {@code User.setLanguage(Language)}. Comme ce champ vit <b>dans {@code this.extra}</b>, il est
   * <b>auto-persisté</b> (PRINCIPLES §3) — aucun re-sync à écrire. On résout le code reçu par la méthode du
   * jeu {@code Language.getLanguage(code)} et on appelle le setter : zéro règle réécrite.
   *
   * @return {@code true} si la langue a été appliquée
   */
  public synchronized boolean setLanguage(com.perblue.heroes.network.messages.SetLanguage m) {
    ServerContext.init();
    if (m == null || m.language == null || m.language.isEmpty()) {
      System.out.println("[lang] message vide → ignoré"); return false;
    }
    com.perblue.heroes.util.localization.Language lang;
    try {
      lang = com.perblue.heroes.util.localization.Language.getLanguage(m.language);
    } catch (Throwable t) {
      System.out.println("[lang] code inconnu '" + m.language + "' → ignoré (" + t + ")"); return false;
    }
    if (lang == null) { System.out.println("[lang] code inconnu '" + m.language + "' → ignoré"); return false; }
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "lang");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "lang");
    ServerContext.bind(user, iu);
    user.setLanguage(lang);                                  // setter d'origine → this.extra.language
    System.out.println("[lang] langue → " + lang + " (code '" + userExtra.language + "')");
    return true;
  }

  /** La langue persistée du joueur (code du jeu), ou {@code null} si jamais reçue. */
  public String language() { return userExtra != null ? userExtra.language : null; }

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
   * ENCHANTING #72 — enchante l'équipement d'un héros ({@code EnchantItem{hero, slot, itemsUsed, useDiamonds}},
   * message DÉDIÉ). Le serveur RÉ-EXÉCUTE la logique d'origine (§3) {@code EnchantingHelper.enchantItem} : consomme
   * les matériaux d'{@code itemsUsed}, débite l'OR ({@code getEnchantGoldCost}, lève {@code NOT_ENOUGH_GOLD}) + les
   * DIAMANTS optionnels ({@code useDiamonds}), monte les étoiles/points d'enchant de l'objet (borné par
   * {@code EnchantingStats.getMaxStars}). Anti-triche = les levées du jeu ({@code NOT_ENOUGH_GOLD}/{@code DONT_HAVE_ITEM}/
   * plafond) → refus autoritatif. Persiste via {@code resyncHeroes} (l'objet vit sur le héros) + {@code resyncDiamonds}.
   * Zéro invention (§4). Renvoie {@code true} si appliqué.
   */
  public synchronized boolean applyEnchantItem(com.perblue.heroes.network.messages.EnchantItem m) {
    ServerContext.init();
    if (m == null || m.hero == null || m.slot == null) return false;
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "enchant");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "enchant");
    ServerContext.bind(user, iu);
    ServerContext.bindBattlePass(refreshBattlePass());
    java.util.Map<?, ?> itemsUsed = m.itemsUsed != null ? m.itemsUsed : new java.util.HashMap<>();
    long goldBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
    long diaBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.DIAMONDS);
    try {
      com.perblue.heroes.game.logic.EnchantingHelper.enchantItem(
          user, m.hero, m.slot, (java.util.Map) itemsUsed, m.useDiamonds,
          com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
    } catch (Throwable t) {
      boolean antiCheat = t instanceof com.perblue.heroes.ClientErrorCodeException;
      System.out.println("[enchant] " + m.hero + "/" + m.slot
          + (antiCheat ? " REFUSÉ (anti-triche)" : " échec") + " : " + t);
      return false;
    }
    resyncHeroes(user); resyncDiamonds(user); resyncCounts(user);
    long goldSpent = goldBefore - user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
    long diaSpent = diaBefore - user.getResource(com.perblue.heroes.network.messages.ResourceType.DIAMONDS);
    System.out.println("[enchant] " + m.hero + "/" + m.slot + " enchanté (or -" + goldSpent
        + (diaSpent > 0 ? ", diamants -" + diaSpent : "") + ") [persisté]");
    return true;
  }

  /**
   * MAX-UPGRADE PRIME BADGES d'un héros (bouton « MAX » de l'écran d'enchant : enchante d'UN COUP TOUS les slots
   * enchantables du héros jusqu'à leur plafond, en consommant les matériaux auto-activés — dont les prime badges,
   * cf. {@code isPrimeBadgeEnchantingItem} — et l'OR). Message {@code EnhanceMaxPrimeBadge{unitType, perBadgeItems,
   * totalItems, executionOrder, specialEvents}} (le client déclare son plan, fire-and-forget).
   *
   * <p>Le serveur est AUTORITATIF (§3) : il IGNORE le plan déclaré par le client et **RÉ-DÉRIVE le plan depuis
   * l'état PERSISTÉ** via {@code EnchantingHelper.buildMaxUpgradePlanForHero(user, type, snap)}, puis l'applique
   * via {@code applyMaxUpgradePlanForHero} (un {@code enchantItem} par slot). C'est là toute l'anti-triche : le
   * message client (plan déclaré) n'est jamais lu — un tricheur ne peut rien fausser, le serveur recalcule tout
   * depuis son état. Zéro invention (§4).
   *
   * <p><b>Le plan est AUTO-LIMITANT (fait vérifié, §8 — {@code GoldAwareProbe}).</b> {@code buildMaxUpgradePlanForHero}
   * ne planifie QUE ce que le joueur peut réellement payer : il plafonne au barème {@code getMaxStars}, n'utilise que
   * les matériaux POSSÉDÉS ({@code getItemAmount}) ET s'arrête à l'OR DISPONIBLE (ex. mesuré : 5 M or → 3 slots ;
   * 9 M → 5 slots ; 9,14 M → 6 slots ; 0 → plan vide). Donc {@code applyMaxUpgradePlanForHero} ne peut PAS lever
   * {@code NOT_ENOUGH_GOLD}/{@code ENCHANT_ALL_ENOUGH_RESOUCES} sur un plan RE-DÉRIVÉ serveur (jamais d'application
   * partielle) — inutile d'ajouter un garde-fou OR (ce serait du code mort, §2). Un compte sans ressource obtient un
   * plan vide → no-op (refus propre). Persistance {@code resyncHeroes}/{@code resyncDiamonds}/{@code resyncCounts}
   * (l'enchant vit sur les objets équipés du héros). Renvoie {@code true} si appliqué (à persister par l'appelant).
   */
  public synchronized boolean applyMaxPrimeBadge(com.perblue.heroes.network.messages.EnhanceMaxPrimeBadge m) {
    ServerContext.init();
    if (m == null || m.unitType == null) return false;
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "prime-badge");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "prime-badge");
    ServerContext.bind(user, iu);
    ServerContext.bindBattlePass(refreshBattlePass());
    long goldBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
    com.perblue.heroes.game.logic.EnchantingHelper.HeroMaxUpgradePlan plan;
    try {
      plan = com.perblue.heroes.game.logic.EnchantingHelper.buildMaxUpgradePlanForHero(
          user, m.unitType, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
      if (plan == null || plan.isEmpty()) {
        System.out.println("[prime-badge] " + m.unitType + " : rien à enchanter (plan vide — ressources/gear) [aucun effet]");
        return false;   // rien à faire (compte sans ressource ou gear au max) → l'appelant ne persiste pas
      }
      com.perblue.heroes.game.logic.EnchantingHelper.applyMaxUpgradePlanForHero(
          user, plan, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
    } catch (Throwable t) {
      boolean antiCheat = t instanceof com.perblue.heroes.ClientErrorCodeException;
      System.out.println("[prime-badge] " + m.unitType
          + (antiCheat ? " REFUSÉ (anti-triche)" : " échec") + " : " + t);
      return false;
    }
    resyncHeroes(user); resyncDiamonds(user); resyncCounts(user);
    long goldSpent = goldBefore - user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
    System.out.println("[prime-badge] " + m.unitType + " max-upgrade (" + plan.executionOrder.size()
        + " slot(s), or -" + goldSpent + ") [persisté]");
    return true;
  }

  /**
   * COLLECTIONS (#72) — RÉCLAME les récompenses d'un niveau de palier de collection
   * ({@code Action CLAIM_COLLECTION_REWARDS{TYPE,TIER,LEVEL}}). Le serveur ré-exécute la logique du jeu (§3)
   * {@code CollectionHelper.claimCollectionRewards(user, type, tier, level)} : elle vérifie
   * {@code getCollectionState==CLAIMABLE} sinon lève {@code ClientErrorCodeException(ERROR)} (anti-triche = on ne
   * réclame pas un palier non atteint ou déjà pris) ; sinon crédite les récompenses (`getCollectionRewards`) et monte
   * le niveau réclamé. Persistance : {@code collectionsClaimed} write-through (`individualUserExtra`) + récompenses via
   * {@code resyncHeroes}/{@code resyncDiamonds}/{@code resyncCounts}. Zéro invention (§4). Renvoie {@code true} si
   * appliqué (à persister par l'appelant).
   */
  public synchronized boolean applyClaimCollection(com.perblue.heroes.network.messages.CollectionType type,
      com.perblue.heroes.network.messages.CollectionTier tier, int level) {
    ServerContext.init();
    if (type == null || tier == null) return false;
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "collection");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "collection");
    ServerContext.bind(user, iu);
    ServerContext.bindBattlePass(refreshBattlePass());
    int before = iu.getHighestClaimedCollectionLevel(type, tier);
    try {
      com.perblue.heroes.game.logic.CollectionHelper.claimCollectionRewards(user, type, tier, level);
    } catch (Throwable t) {
      boolean antiCheat = t instanceof com.perblue.heroes.ClientErrorCodeException;
      System.out.println("[collection] " + type + "/" + tier + " niv." + level
          + (antiCheat ? " REFUSÉ (anti-triche : non CLAIMABLE)" : " échec") + " : " + t);
      return false;
    }
    resyncHeroes(user); resyncDiamonds(user); resyncCounts(user);
    int after = iu.getHighestClaimedCollectionLevel(type, tier);
    System.out.println("[collection] " + type + "/" + tier + " niv." + level
        + " réclamé (highest " + before + "→" + after + ") [persisté]");
    return true;
  }

  /**
   * COLLECTIONS (#72) incr. 3 — ACHAT d'un AVATAR de collection (« mastery shop » : {@code Action
   * BUY_COLLECTION_AVATAR{itemType=avatar}}). Le serveur ré-exécute la logique du jeu (§3)
   * {@code CollectionHelper.buyCollectionAvatar(user, itemType)} : gate {@code getCumulativeCollectionLevel >=
   * getCumulativeCollectionLevelRequiredForPortrait} sinon lève {@code COLLECTION_AVATAR_LOCKED} (anti-triche = on
   * n'achète pas un avatar dont le palier de collection n'est pas atteint) ; débite {@code MASTERY_TOKENS}
   * ({@code getAvatarCost} ; lève si insuffisant) ; donne l'avatar ({@code giveUser(itemType, 1)} → items
   * write-through). C'est le SINK des MASTERY_TOKENS gagnés par les claims (incr. 1). Persistance : items/ressources
   * write-through + {@code resyncDiamonds}/{@code resyncCounts}. Zéro invention (§4). Renvoie {@code true} si appliqué.
   */
  public synchronized boolean applyBuyCollectionAvatar(com.perblue.heroes.network.messages.ItemType avatar) {
    ServerContext.init();
    if (avatar == null) return false;
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "collection-avatar");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "collection-avatar");
    ServerContext.bind(user, iu);
    ServerContext.bindBattlePass(refreshBattlePass());
    long tokBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.MASTERY_TOKENS);
    try {
      com.perblue.heroes.game.logic.CollectionHelper.buyCollectionAvatar(user, avatar);
    } catch (Throwable t) {
      boolean antiCheat = t instanceof com.perblue.heroes.ClientErrorCodeException;
      System.out.println("[collection] achat avatar " + avatar
          + (antiCheat ? " REFUSÉ (anti-triche : verrouillé/tokens insuffisants)" : " échec") + " : " + t);
      return false;
    }
    resyncHeroes(user); resyncDiamonds(user); resyncCounts(user);
    long tokSpent = tokBefore - user.getResource(com.perblue.heroes.network.messages.ResourceType.MASTERY_TOKENS);
    System.out.println("[collection] avatar " + avatar + " acheté (MASTERY_TOKENS -" + tokSpent + ") [persisté]");
    return true;
  }

  /**
   * WISHING_WELL (#72) — fixe le HÉROS CIBLE du puits aux souhaits ({@code Action SET_WISHING_WELL_TARGET_HERO{
   * heroType=cible}}). Le serveur ré-exécute la logique du jeu (§3) {@code WishingWellHelper.setTargetHero(user,
   * hero)} : valide {@code hero ∈ getAllEligibleHeroes} (héros non éligible → aucun effet = anti-triche), pose
   * {@code setWishingWellHero} (write-through {@code individualUserExtra.wishingWellHero}), horodate/compte le
   * changement ({@code setTime}/{@code setCount(UserFlag)}) et ajuste les poids de pity ({@code getWeightConstants}/
   * {@code checkMinWeights}). Persistance : write-through + {@code resyncCounts} (compteur de changement de cible).
   * Zéro invention (§4). Renvoie {@code true} si la cible a bien été posée.
   */
  public synchronized boolean applySetWishingWellTarget(com.perblue.heroes.network.messages.UnitType hero) {
    ServerContext.init();
    if (hero == null) return false;
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "wishing-well");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "wishing-well");
    ServerContext.bind(user, iu);
    try {
      com.perblue.heroes.game.logic.WishingWellHelper.setTargetHero(user, hero);
    } catch (Throwable t) {
      System.out.println("[wishing-well] cible " + hero + " échec : " + t);
      return false;
    }
    resyncCounts(user);
    com.perblue.heroes.network.messages.UnitType now = iu.getWishingWellHero();
    boolean ok = now == hero;
    System.out.println("[wishing-well] cible = " + now + (ok ? "" : " (demandé " + hero + " NON posé : non éligible ?)")
        + " [persisté]");
    return ok;
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

  /**
   * ARÈNE #41 — enregistre une <b>lineup d'arène</b> (défense/attaque) que le client SAUVEGARDE via
   * {@code HeroLineupUpdate{type, iD, lineup, …}}. On appelle la logique du jeu {@code User.setHeroLineup}
   * (stocke dans les lineups du User) puis on <b>re-synchronise</b> vers {@code userExtra.heroLineups}
   * (colonne persistée) → la défense d'arène SURVIT aux redémarrages (PRINCIPLES §6). Le modèle d'état est
   * <b>celui du jeu</b> ({@code HeroLineupType} COLISEUM_DEFENSE_1/2/3, FIGHT_PIT_DEFENSE…), pas un schéma inventé.
   */
  public synchronized boolean applyHeroLineupUpdate(
      com.perblue.heroes.network.messages.HeroLineupUpdate u) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "lineup");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "lineup");
    ServerContext.bind(user, iu);
    try {
      // Signature RÉELLE du jeu (relevée par bytecode — les deux Map sont brutes, l'ordre n'est PAS déductible
      // du prototype) : setHeroLineup(type, iD, lineup, expiration, customName, realGearOptions, emeraldStatSlotChoices).
      // NB : inversé au départ → les HeroStatSlotChoices (émeraude) atterrissaient dans realGearOptions et la
      // sérialisation de userExtra (UserHeroLineupData.writeListed → packEnumList) plantait en ClassCastException.
      user.setHeroLineup(u.type, u.iD, u.lineup, 0L, u.customName,
          u.realGearOptions, u.emeraldStatSlotChoices);
    } catch (Throwable t) {
      System.out.println("[lineup] setHeroLineup refusé/échec : " + t);
      return false;
    }
    // COOLDOWN de mise à jour de DÉFENSE PvP — miroir FIDÈLE du client (bytecode ClientActionHelper.saveHeroLineup) :
    // après setHeroLineup, poser le cooldown anti-abus qui empêche de re-changer sa défense trop souvent. FIGHT_PIT →
    // FIGHT_PIT_LINEUP_UPDATE ; COLISEUM → COLISEUM_LINEUP_UPDATE (uniquement au 3ᵉ/dernier slot COLISEUM_DEFENSE_3,
    // comme le client). setCooldownEnd write-through → individualUserExtra.cooldowns (persisté §6). Autorité serveur
    // (§3) : sans ça, un client modifié pourrait re-changer sa défense en boucle. Zéro invention (durée =
    // ArenaHelper.getNextDefenseCooldown, donnée du jeu).
    try {
      if (u.type == com.perblue.heroes.network.messages.HeroLineupType.FIGHT_PIT_DEFENSE) {
        com.perblue.heroes.game.logic.ArenaHelper.setHeroLineupCooldown(user,
            com.perblue.heroes.network.messages.ArenaType.FIGHT_PIT,
            com.perblue.heroes.network.messages.CooldownType.FIGHT_PIT_LINEUP_UPDATE);
      } else if (u.type == com.perblue.heroes.network.messages.HeroLineupType.COLISEUM_DEFENSE_3) {
        com.perblue.heroes.game.logic.ArenaHelper.setHeroLineupCooldown(user,
            com.perblue.heroes.network.messages.ArenaType.COLISEUM,
            com.perblue.heroes.network.messages.CooldownType.COLISEUM_LINEUP_UPDATE);
      }
    } catch (Throwable t) { System.out.println("[lineup] cooldown défense PvP: " + t); }
    resyncLineups(user);
    int n = (u.lineup != null && u.lineup.heroes != null) ? u.lineup.heroes.size() : 0;
    System.out.println("[lineup] " + u.type + " (" + n + " héros) enregistrée [persistée]");
    return true;
  }

  /**
   * Re-synchronise les lineups du {@link User} vers {@code userExtra.heroLineups} (persistance complète, §6).
   * L'état vit dans la Map runtime PRIVÉE {@code User.lineups} ({@code HeroLineupKey{lineupType,id}} →
   * {@code UserHeroLineupData}), HORS {@code this.extra} → on itère cette Map (réflexion) et on ré-écrit chaque
   * entrée dans la liste wire.
   * <p>⚠️ <b>Angle mort (leçon EXPEDITION), corrigé.</b> (1) {@code setHeroLineup} NE pose PAS
   * {@code data.lineupType}/{@code data.iD} sur le {@code UserHeroLineupData} (il les garde dans la CLÉ) ; or
   * {@code setHeroLineups} (le loader) re-clé PAR ces champs → on RECOPIE la clé → {@code data.lineupType}/{@code iD}
   * avant d'ajouter, sinon tous les lineups collapseraient sur {@code (DEFAULT,0)} au reload. (2) L'ancienne version
   * lisait via {@code getHeroLineupData(type)} qui HARDCODE {@code id=0} → elle RATAIT les lineups à id non-nul ;
   * itérer la Map les capte tous.
   */
  @SuppressWarnings("unchecked")
  private void resyncLineups(User user) {
    java.util.List<com.perblue.heroes.network.messages.UserHeroLineupData> out = new java.util.ArrayList<>();
    try {
      java.lang.reflect.Field lf = User.class.getDeclaredField("lineups");
      lf.setAccessible(true);
      java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) lf.get(user);
      for (java.util.Map.Entry<Object, Object> e : map.entrySet()) {
        com.perblue.heroes.game.objects.HeroLineupKey key =
            (com.perblue.heroes.game.objects.HeroLineupKey) e.getKey();
        com.perblue.heroes.network.messages.UserHeroLineupData d =
            (com.perblue.heroes.network.messages.UserHeroLineupData) e.getValue();
        if (d == null) continue;
        // Copie défensive + report de la CLÉ (type+id) sur la data (le wire l'exige ; la Map les garde à part).
        com.perblue.heroes.network.messages.UserHeroLineupData c =
            new com.perblue.heroes.network.messages.UserHeroLineupData();
        c.lineupType = key.lineupType;
        c.iD = key.id;
        c.expiration = d.expiration;
        c.lineup = d.lineup;
        c.customName = d.customName;
        c.realGearOptions = d.realGearOptions;
        c.emeraldStatSlotChoices = d.emeraldStatSlotChoices;
        out.add(c);
      }
    } catch (Throwable t) { System.out.println("[resync] lineups: " + t); }
    userExtra.heroLineups = out;
  }

  /** Aiguille une commande vers la logique cœur du jeu. Le nom est comparé en String (l'enum du jeu
   *  a des annotations dex2jar qui gênent un switch). Étendu au fur et à mesure des commandes du jeu. */
  private boolean applyCommand(Action m, User user) {
    String cmd = m.command == null ? "" : m.command.name();
    switch (cmd) {
      case "SELL_ITEM": {
        // Écran ITEMS (inventaire) : VENDRE un objet contre de l'or. Le client envoie SELL_ITEM{itemType,
        // COUNT} (ClientActionHelper.sellItem, fire-and-forget). Logique d'origine EXACTE UserHelper.sellItem :
        // or gagné = VEND_VALUE (ItemStats) × count ; anti-triche RÉEL (objet non vendable → CANT_SELL_ITEM ;
        // pas assez d'exemplaires → CANT_SELL_ITEM ; dépassement d'or → SELL_ITEM_GOLD_OVERFLOW). retire les
        // objets (individualUserExtra.items, auto-persisté) + donne l'or (this.extra, auto-persisté).
        com.perblue.heroes.network.messages.ItemType sellType = m.itemType;
        if (sellType == null || sellType == com.perblue.heroes.network.messages.ItemType.DEFAULT) {
          System.out.println("[action] SELL_ITEM: objet manquant"); return false;
        }
        Object cntO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.COUNT);
        int cnt = cntO == null ? 1 : Integer.parseInt(cntO.toString());
        com.perblue.heroes.game.logic.UserHelper.sellItem(sellType, cnt, user);
        System.out.println("[action] SELL_ITEM " + sellType + " ×" + cnt + " → vendu contre or (logique du jeu)");
        return true;
      }
      case "USE_ITEM": {
        // Utiliser un CONSOMMABLE de l'inventaire. Le client envoie USE_ITEM{itemType, COUNT, MODE=gameMode}
        // (ClientActionHelper.useItem). Logique d'origine EXACTE ItemHelper.useItem (applique l'effet du
        // consommable + le décompte). SpecialEventSnapshot.NONE (pas de bonus évènement, comme recordOutcome).
        com.perblue.heroes.network.messages.ItemType useType = m.itemType;
        if (useType == null || useType == com.perblue.heroes.network.messages.ItemType.DEFAULT) {
          System.out.println("[action] USE_ITEM: objet manquant"); return false;
        }
        Object cntO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.COUNT);
        int cnt = cntO == null ? 1 : Integer.parseInt(cntO.toString());
        Object modeO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.MODE);
        com.perblue.heroes.network.messages.GameMode gm;
        try { gm = modeO == null ? com.perblue.heroes.network.messages.GameMode.DEFAULT
            : com.perblue.heroes.network.messages.GameMode.valueOf(modeO.toString()); }
        catch (Throwable t) { gm = com.perblue.heroes.network.messages.GameMode.DEFAULT; }
        com.perblue.heroes.game.logic.ItemHelper.useItem(user, user, useType, cnt, gm, false,
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
        System.out.println("[action] USE_ITEM " + useType + " ×" + cnt + " mode=" + gm + " → consommé (logique du jeu)");
        return true;
      }
      case "UPDATE_TIME": {
        // Setter de TEMPS générique (extra={TYPE=<TimeType>, TIME=<epoch-ms>}). Envoyé par de NOMBREUX écrans
        // pour marquer « vu à l'instant t » et éteindre leur pastille « ! » : LAST_EVENT_VIEW_TIME (écran
        // EVENTS, trouvé en jeu), LAST_CHESTS_VIEW_TIME, LAST_CONTEST_VIEW_TIME, LAST_MERCHANT_VIEW_TIME,
        // LAST_PRIZE_WALL_VIEW_TIME… Logique du jeu : IUser.setTime(type, t) écrit dans this.extra.times
        // (PARTAGÉ avec userExtra → AUTO-PERSISTÉ). Générique → couvre tous ces badges d'un coup.
        Object tyO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
        Object tO  = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TIME);
        if (tyO == null || tO == null) { System.out.println("[action] UPDATE_TIME: TYPE/TIME manquant"); return false; }
        try {
          com.perblue.heroes.network.messages.TimeType tt =
              com.perblue.heroes.network.messages.TimeType.valueOf(tyO.toString());
          user.setTime(tt, Long.parseLong(tO.toString()));
          System.out.println("[action] UPDATE_TIME " + tt + "=" + tO + " → marqué (pastille éteinte, persisté)");
          return true;
        } catch (Throwable t) {
          System.out.println("[action] UPDATE_TIME: TYPE inconnu " + tyO + " : " + t); return false;
        }
      }
      case "SET_FLAG": {
        // Setter de FLAG booléen générique (extra={TYPE=<UserFlag>, COUNT=<0/1>}). Envoyé par divers écrans pour
        // mémoriser un état côté joueur — ex. FREE_NAME_CHANGE_SEEN à l'entrée de l'arène (trouvé EN JEU). Logique
        // du jeu (ActionHelper.doAction branche SET_FLAG, relevé au bytecode) : User.setFlag(UserFlag.valueOf(TYPE),
        // COUNT != 0). Les flags vivent dans User.flags (copiés de userExtra au chargement) → persistés par
        // resyncCounts (déjà appelé par applyAction, cf. fix g8). Générique → couvre TOUS les SET_FLAG d'un coup.
        Object fyO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
        if (fyO == null) { System.out.println("[action] SET_FLAG: TYPE manquant"); return false; }
        Object fcO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.COUNT);
        boolean val = fcO != null && !"0".equals(fcO.toString());
        try {
          com.perblue.heroes.game.objects.UserFlag flag =
              com.perblue.heroes.game.objects.UserFlag.valueOf(fyO.toString());
          user.setFlag(flag, val);
          System.out.println("[action] SET_FLAG " + flag + "=" + val + " → posé (User.flags, persisté)");
          return true;
        } catch (Throwable t) {
          System.out.println("[action] SET_FLAG: flag inconnu " + fyO + " : " + t); return false;
        }
      }
      case "VIEWED_CONSUMABLE_ITEM": {
        // Marquer un consommable comme VU (efface la pastille « nouveau »). setViewedConsumableItem écrit dans
        // this.extra (individualUserExtra) → auto-persisté (patron VIEW_DAILY_QUESTS).
        com.perblue.heroes.network.messages.ItemType vType = m.itemType;
        if (vType == null || vType == com.perblue.heroes.network.messages.ItemType.DEFAULT) {
          System.out.println("[action] VIEWED_CONSUMABLE_ITEM: objet manquant"); return false;
        }
        ((com.perblue.heroes.game.objects.IndividualUser) user.getIndividual()).setViewedConsumableItem(vType);
        System.out.println("[action] VIEWED_CONSUMABLE_ITEM " + vType + " → marqué vu");
        return true;
      }
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
      case "UPGRADE_SKILL": {
        // Écran SKILL_UPGRADE : MONTER le niveau d'une compétence d'un héros. Le client envoie
        // UPGRADE_SKILL{hero=<UnitType>, extra={SKILL=<SkillSlot>, COUNT=<n>}} (ClientActionHelper.upgradeSkill →
        // ActionHelper.doAction, relevé au bytecode). Logique d'origine EXACTE HeroHelper.upgradeSkill(heroType,
        // slot, count, user) : anti-triche RÉEL (canLevelUpSkill = niveau MAX du skill + gate rang/niveau du héros ;
        // NOT_ENOUGH_SKILL_POINTS si pas assez de points), débite GOLD (getTotalGoldCost) + SKILL_POINTS puis pose
        // setSkillLevel(slot, niveau) sur le héros. Persistance : GOLD & SKILL_POINTS vivent dans this.extra
        // (SKILL_POINTS n'est PAS une ressource spéciale — hors switch IndividualUser$1 DIAMONDS/QUEST_POINTS/
        // FREE/PAID_DIAMONDS → auto-persistée) ; le niveau de skill vit dans le héros → capté par resyncHeroes
        // (getHeroData sérialise HeroData.skills), appliqué à TOUTE action. VIP SKILL_POINT_COST_FREE respecté par
        // la logique du jeu. Aucune règle réécrite (glue + logique cœur).
        com.perblue.heroes.network.messages.UnitType skHero = m.heroType;
        if (skHero == null || skHero == com.perblue.heroes.network.messages.UnitType.DEFAULT) {
          System.out.println("[action] UPGRADE_SKILL: héros manquant"); return false;
        }
        Object slotO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.SKILL);
        if (slotO == null) { System.out.println("[action] UPGRADE_SKILL: slot SKILL manquant"); return false; }
        com.perblue.heroes.network.messages.SkillSlot slot;
        try { slot = com.perblue.heroes.network.messages.SkillSlot.valueOf(slotO.toString()); }
        catch (Throwable t) { System.out.println("[action] UPGRADE_SKILL: slot invalide " + slotO); return false; }
        Object cntO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.COUNT);
        int cnt = cntO == null ? 1 : Integer.parseInt(cntO.toString());
        if (user.getHero(skHero) == null) { System.out.println("[action] UPGRADE_SKILL: héros non possédé " + skHero); return false; }
        com.perblue.heroes.game.objects.IHero sh =
            com.perblue.heroes.game.logic.HeroHelper.upgradeSkill(skHero, slot, cnt, user);
        System.out.println("[action] UPGRADE_SKILL " + skHero + " " + slot + " ×" + cnt
            + " → compétence montée (coût GOLD + SKILL_POINTS, logique du jeu)");
        return sh != null;
      }
      case "BUY_GOLD": {
        // Écran ALCHEMY (achat d'or contre DIAMANTS). Le client envoie BUY_GOLD{extra={COUNT=<tier>}}
        // (ClientActionHelper.buyGold → ActionHelper.doEventAction, relevé au bytecode ; COUNT = l'index d'achat
        // du jour, 0-based). Logique d'origine EXACTE UserHelper.buyGold(count, user, snapshot) : anti-triche RÉEL
        // — gate Unlockable.ALCHEMY (TL12), limite quotidienne (getDailyUses("buy_gold") vs VIP DAILY_GOLD_BUYS,
        // sinon GOLD_PURCHASES_USED) — débite les DIAMONDS (MidasStats.getCost) et crédite l'OR
        // (getPreCritGold(count, teamLevel) × crit RNG ALCHEMY) + incrémente le compteur quotidien. Persistance :
        // DIAMONDS via resyncDiamonds (champ dédié) + compteurs via resyncCounts (this.extra) + OR auto (this.extra) —
        // TOUS déjà appelés par applyAction. PARTIEL documenté (comme le loot #25) : le multiplicateur CRIT est un
        // tirage RNG (graine ALCHEMY) — le serveur roule le SIEN (autoritatif) ; il peut différer du montant affiché
        // par le client (qui a roulé le sien). Aligner via SET_SEED(ALCHEMY) relève des Partiels D/E. L'anti-triche
        // (gate + limite quotidienne + coût diamants) est, lui, RÉEL.
        Object cO = m.extra == null ? null : m.extra.get(com.perblue.heroes.network.messages.ActionExtraType.COUNT);
        int buyIdx = cO == null ? 0 : Integer.parseInt(cO.toString());
        long diamondsBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.DIAMONDS);
        long goldBefore = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD);
        com.perblue.heroes.game.logic.UserHelper.buyGold(buyIdx, user,
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
        long dSpent = diamondsBefore - user.getResource(com.perblue.heroes.network.messages.ResourceType.DIAMONDS);
        long gGain = user.getResource(com.perblue.heroes.network.messages.ResourceType.GOLD) - goldBefore;
        System.out.println("[action] BUY_GOLD tier=" + buyIdx + " → −" + dSpent + " DIAMONDS, +" + gGain
            + " GOLD (logique du jeu ; crit serveur-autoritatif)");
        return true;
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
  void resyncDiamonds(User user) {   // package-private : ServerChallenges resynchronise après un claim de sticker
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
  /** Re-sync de la MAP DES AMITIÉS (#72) vers le wire. {@code IndividualUser.friendships}
   *  ({@code Map<FriendPairID, ClientFriendship>}) est construit depuis {@code individualUserExtra.friendships}
   *  ({@code Map<Long, FriendPairData>}) au chargement ; {@code ClientFriendship} a ses PROPRES champs (il ne
   *  wrappe pas {@code FriendPairData}) → les mutations ({@code empowerment}/{@code campaignBitsEarned}/
   *  {@code lastBattle}/{@code history}…) restent en mémoire. On ré-sérialise l'objet du jeu → wire (jeu de champs
   *  FERMÉ, {@code FriendshipEvent}↔{@code FriendshipEventData} = mêmes champs). Clé = {@code getAsLong()}.
   *  Package-private : appelé par {@link ServerFriendships} après empower / combat de campagne d'amitié. */
  @SuppressWarnings("unchecked")
  void resyncFriendships(IndividualUser iu) {
    try {
      java.util.Map<Object, Object> fs = (java.util.Map<Object, Object>) (java.util.Map<?, ?>) iu.getFriendships();
      individualUserExtra.friendships.clear();
      for (java.util.Map.Entry<Object, Object> e : fs.entrySet()) {
        com.perblue.heroes.game.objects.FriendPairID pair = (com.perblue.heroes.game.objects.FriendPairID) e.getKey();
        com.perblue.heroes.game.objects.IFriendship cf = (com.perblue.heroes.game.objects.IFriendship) e.getValue();
        com.perblue.heroes.network.messages.FriendPairData d = new com.perblue.heroes.network.messages.FriendPairData();
        d.empowerment = cf.getEmpowerment();
        d.campaignBitsEarned = cf.getCampaignBitsEarned();
        d.viewedUnlockAnimation = cf.viewedUnlockAnimation();
        d.lastHistoryViewTime = cf.getLastHistoryViewTime();
        // NE PAS écraser avec null : new FriendPairData() initialise lastBattle = new FriendshipBattleInfo()
        // (non-null), et getClientFriendship lit lastBattle.serverTime SANS garde → NPE si null. Un fresh
        // ClientFriendship (jamais combattu) renvoie getLastBattle()==null → on garde le défaut non-null du wire.
        if (cf.getLastBattle() != null) d.lastBattle = cf.getLastBattle();
        d.history = new java.util.ArrayList<>();
        Iterable<?> hist = cf.getHistory();
        if (hist != null) for (Object o : hist) {
          com.perblue.heroes.game.data.friendships.FriendshipEvent fe =
              (com.perblue.heroes.game.data.friendships.FriendshipEvent) o;
          com.perblue.heroes.network.messages.FriendshipEventData ed =
              new com.perblue.heroes.network.messages.FriendshipEventData();
          ed.level = fe.level; ed.missionNumber = fe.missionNumber; ed.storyNoteNumber = fe.storyNoteNumber;
          ed.time = fe.time; ed.type = fe.type;
          d.history.add(ed);
        }
        individualUserExtra.friendships.put(pair.getAsLong(), d);
      }
    } catch (Throwable t) { System.out.println("[resync] friendships: " + t); }
  }

  /** Re-sync des MISSIONS IDLE d'amitié (#72 incr. 3c) : la liste runtime {@code IndividualUser.missions}
   *  ({@code List<ClientMission>}) est CONSTRUITE au chargement depuis {@code individualUserExtra.missions}
   *  ({@code List<MissionData>}, cf. {@code setExtra}→{@code setMissions}) ; {@code addMission}/{@code removeMission}
   *  ne touchent QUE la liste runtime → à re-sérialiser vers le wire. {@code ClientMission} est un simple
   *  wrapper write-through de {@code MissionData} (getters/setters lisent/écrivent {@code data}), donc pour une
   *  mission EXISTANTE {@code data} EST déjà l'instance du wire ; pour une mission AJOUTÉE c'est une instance neuve.
   *  On reconstruit {@code extra.missions} depuis les {@code data} sous-jacents (réflexion, aucune règle réécrite).
   *  {@code missionClaimData} est écrit DIRECTEMENT dans {@code extra} par {@code addMissionClaimData}/
   *  {@code clearMissionClaimData} (write-through) → aucun resync nécessaire pour lui. Package-private : {@link ServerMissions}. */
  @SuppressWarnings("unchecked")
  void resyncMissions(IndividualUser iu) {
    try {
      java.lang.reflect.Field df =
          com.perblue.heroes.game.missions.ClientMission.class.getDeclaredField("data");
      df.setAccessible(true);
      java.util.List<Object> out = new java.util.ArrayList<>();
      Iterable<?> missions = iu.getMissions();
      if (missions != null) for (Object cm : missions) out.add(df.get(cm));
      individualUserExtra.missions.clear();
      individualUserExtra.missions.addAll((java.util.List) out);
    } catch (Throwable t) { System.out.println("[resync] missions: " + t); }
  }

  /** Re-sync des FAVORIS d'amitié (#72) : {@code IndividualUser.favoriteFriendships} est un {@code Set<FriendPairID>}
   *  COPIÉ depuis {@code individualUserExtra.favoriteFriendships} au chargement (comme flags/counts) → les mutations
   *  ({@code setFavoriteFriendship}) restent en mémoire ; on ré-écrit l'ensemble dans le wire ({@code List<Long>} via
   *  {@code getAsLong}). Package-private : appelé par {@link ServerFriendships}. */
  @SuppressWarnings("unchecked")
  void resyncFriendFavorites(IndividualUser iu) {
    try {
      java.lang.reflect.Field f = IndividualUser.class.getDeclaredField("favoriteFriendships");
      f.setAccessible(true);
      java.util.Set<Object> favs = (java.util.Set<Object>) f.get(iu);
      individualUserExtra.favoriteFriendships.clear();
      for (Object p : favs)
        individualUserExtra.favoriteFriendships.add(((com.perblue.heroes.game.objects.FriendPairID) p).getAsLong());
    } catch (Throwable t) { System.out.println("[resync] friend favorites: " + t); }
  }

  @SuppressWarnings("unchecked")
  void resyncCounts(User user) {   // package-private : ServerChallenges resynchronise les flags après un achat sticker
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

  /** Re-sync des héros (état hors {@code this.extra}) vers le wire — persistance complète.
   *  Package-private : {@link ServerMissions} resynchronise les récompenses de mission éventuelles. */
  @SuppressWarnings("unchecked")
  void resyncHeroes(User user) {
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

  /** Table de drop du PUITS AUX SOUHAITS (champ statique privé {@code ChestStats.WISHING_WELL_DROPS}, hors
   *  {@code getDropTable}) → sa {@code DropTable}. Code+données du jeu (§3, cf. {@code rollWishingWellDisplay}). */
  private static DropTable wishingWellTable() {
    try {
      java.lang.reflect.Field f = ChestStats.class.getDeclaredField("WISHING_WELL_DROPS");
      f.setAccessible(true);
      Object wwd = f.get(null);
      Method getTable = wwd.getClass().getMethod("getTable");
      return (DropTable) getTable.invoke(wwd);
    } catch (Throwable t) { throw new RuntimeException("table du puits aux souhaits introuvable", t); }
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

  /** CHALLENGES #72 — octets wire du {@code UserChallengeDataExtra} persisté (NULL si aucun défi en cours). */
  public synchronized byte[] challengeWire() {
    return challengeData == null ? null : wire(challengeData);
  }
  /** Restaure la progression de défis persistée (au chargement DB ; NULL = état frais recréé au boot). */
  public synchronized void setChallengeWire(byte[] bytes) {
    if (bytes != null && bytes.length > 0) challengeData = read(bytes);
  }
  /** État de défis courant (persisté), ou {@code null} si frais. Accès pour {@link ServerChallenges}. */
  public synchronized com.perblue.heroes.network.messages.UserChallengeDataExtra challengeDataOrNull() { return challengeData; }
  /** Remplace l'état de défis (après START/CLAIM/CANCEL) — l'appelant persiste ensuite via {@code store.save}. */
  public synchronized void setChallengeData(com.perblue.heroes.network.messages.UserChallengeDataExtra d) { challengeData = d; }

  // EXPEDITION #72 — le RUN (ExpeditionRunData) est un état serveur (hors userExtra/individualUserExtra ; seul
  // expeditionID vit dans l'extra) → persisté à part (colonne BLOB `expedition`). NULL = aucun run (l'écran envoie
  // ResetExpedition pour en générer un via ServerExpedition).
  private com.perblue.heroes.network.messages.ExpeditionRunData expeditionRun;
  /** Octets wire du run d'expédition persisté (NULL si aucun run). */
  public synchronized byte[] expeditionWire() { return expeditionRun == null ? null : wire(expeditionRun); }
  /** Restaure le run d'expédition persisté (au chargement DB ; NULL = aucun run). */
  public synchronized void setExpeditionWire(byte[] bytes) {
    if (bytes != null && bytes.length > 0) expeditionRun = read(bytes);
  }
  /** Run d'expédition courant (persisté), ou {@code null}. Accès pour {@link ServerExpedition}. */
  public synchronized com.perblue.heroes.network.messages.ExpeditionRunData expeditionRunOrNull() { return expeditionRun; }
  /** Remplace le run d'expédition (après ResetExpedition / combat) — l'appelant persiste via {@code store.save}. */
  public synchronized void setExpeditionRun(com.perblue.heroes.network.messages.ExpeditionRunData r) { expeditionRun = r; }
  /** Sticker FAVORI (SET_FAVORITE_STICKER) : posé dans {@code userExtra} (source lue par {@code getUser}) + miroir
   *  {@code BasicUserInfo} — {@code User.setFavoriteSticker} n'écrit PAS dans extra (champ User seul). Auto-persisté. */
  public synchronized void setFavoriteSticker(com.perblue.heroes.network.messages.StickerType type) {
    userExtra.favoriteSticker = type;
    if (userInfo != null && userInfo.basicInfo != null) userInfo.basicInfo.favoriteSticker = type;
  }
  public synchronized com.perblue.heroes.network.messages.StickerType favoriteSticker() { return userExtra.favoriteSticker; }

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

  /** Courriers persistés (mailbox), pour vérification. */
  public synchronized java.util.List<com.perblue.heroes.network.messages.MailMessage> mailPersisted() {
    return mail == null ? java.util.Collections.emptyList() : mail;
  }

  /** Prochain identifiant de courrier (per-joueur, croissant : max existant + 1 ; 1 si mailbox vide). */
  public synchronized long nextMailID() {
    long max = 0;
    if (mail != null) for (com.perblue.heroes.network.messages.MailMessage m : mail) if (m.iD > max) max = m.iD;
    return max + 1;
  }

  /**
   * Construit + DÉPOSE un courrier serveur (format wire authentique du jeu) : sujet/texte + pièces jointes
   * (récompenses arbitraires via {@code RewardHelper.createDrop}). Sert au rapport de défense d'arène et au
   * panneau admin (courrier global/ciblé). ID auto (croissant), expiration ~10 ans, non lu, non persistant.
   * @return l'ID attribué.
   */
  public synchronized long deliverMail(com.perblue.heroes.network.messages.MailType type, String from,
      String subject, String body,
      java.util.List<com.perblue.heroes.network.messages.RewardDrop> attachments) {
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();  // horodatage courrier = heure de JEU (cohérence d'ère)
    com.perblue.heroes.network.messages.MailMessage m = new com.perblue.heroes.network.messages.MailMessage();
    m.iD = nextMailID();
    m.type = type;
    m.fromSender = from == null ? "Disney Heroes" : from;
    m.subject = subject == null ? "" : subject;
    m.message = body == null ? "" : body;
    m.sentDate = now;
    m.expiration = now + 3650L * 24L * 3600L * 1000L;   // ~10 ans (0 = traité comme EXPIRÉ par la mailbox)
    m.opened = false;
    m.persistent = false;
    m.translatable = false;                             // texte LITTÉRAL (pas une clé i18n)
    m.extra = new com.perblue.heroes.network.messages.MailExtra();
    m.extra.attachments = (attachments == null)
        ? new java.util.ArrayList<>() : new java.util.ArrayList<>(attachments);
    addMail(m);
    return m.iD;
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

  /**
   * ARÈNE PvP — construit l'{@link com.perblue.heroes.network.messages.ArenaInfo} (réponse à {@code GetArenaInfo}).
   * Lie un {@link User} de jeu sur nos objets wire puis délègue à {@link ServerArena} (construction fidèle depuis
   * {@code arena_*.tab}/{@code ArenaHelper}). Cf. tâches arène #41-44.
   */
  public synchronized com.perblue.heroes.network.messages.ArenaInfo arenaInfo(
      com.perblue.heroes.network.messages.ArenaType type) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "arena");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "arena");
    ServerContext.bind(user, iu);
    return ServerArena.buildArenaInfo(user, userInfo, type);
  }

  /** Points d'arène accordés par victoire (valeur OPÉRATEUR §3 pour l'affichage ; le fight pit classe par RANG). */
  private static final int ARENA_WIN_POINTS = 30;

  /**
   * ARÈNE #44 — START d'une attaque. Le client envoie {@code Action{START_FIGHT_PIT_ATTACK/START_COLISEUM_ATTACK,
   * extra={ID=defenderID,…}}} et RESTE bloqué sur « LOADING… » tant qu'il n'a pas la réponse (observé en jeu). On
   * répond {@code Start(Arena|Coliseum)AttackResponse} avec les héros du DÉFENSEUR (HeroData complet → le client
   * rejoue le combat), + saison/tier/division. Défenseur bot → régénéré déterministiquement (même équipe qu'affichée).
   */
  public synchronized com.perblue.grunt.translate.GruntMessage startArenaAttack(
      com.perblue.heroes.network.messages.ArenaType type, long defenderID, ServerArenaLadder ladder) {
    return startArenaAttack(type, defenderID, ladder, null);
  }

  /** Variante vrai PvP : {@code src} sert les héros de défense RÉELS d'un vrai défenseur (sinon bot synthétique). */
  public synchronized com.perblue.grunt.translate.GruntMessage startArenaAttack(
      com.perblue.heroes.network.messages.ArenaType type, long defenderID, ServerArenaLadder ladder,
      ServerArena.OpponentSource src) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "arena-start");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "arena-start");
    ServerContext.bind(user, iu);
    com.perblue.heroes.network.messages.ArenaInfo ai = ServerArena.buildArenaInfo(user, userInfo, type, ladder, src);
    ServerArenaLadder.Entry def = null;
    for (ServerArenaLadder.Entry e : ladder.entries()) if (e.id == defenderID) { def = e; break; }
    String defName = def != null ? def.name : "Rival";
    int shard = user.getShardID();
    if (type == com.perblue.heroes.network.messages.ArenaType.COLISEUM) {
      com.perblue.heroes.network.messages.StartColiseumAttackResponse r =
          new com.perblue.heroes.network.messages.StartColiseumAttackResponse();
      r.defendingUserID = defenderID; r.defendingUserName = defName;
      r.defendingUserAvatar = new com.perblue.heroes.network.messages.Avatar();
      r.defenderFriendships = new java.util.ArrayList<>();
      r.division = ai.yourLeague.division; r.tier = ai.yourLeague.tier; r.season = ai.season;
      r.combatModifiers = new java.util.HashMap<>();
      r.defendingLineups = def != null ? ServerArena.defenderLineups(def, shard, 3, src) : new java.util.ArrayList<>();
      return r;
    }
    com.perblue.heroes.network.messages.StartArenaAttackResponse r =
        new com.perblue.heroes.network.messages.StartArenaAttackResponse();
    r.defendingUserID = defenderID; r.defendingUserName = defName;
    r.defendingUserAvatar = new com.perblue.heroes.network.messages.Avatar();
    r.defenderFriendships = new java.util.ArrayList<>();
    r.division = ai.yourLeague.division; r.tier = ai.yourLeague.tier; r.season = ai.season;
    r.combatModifiers = new java.util.HashMap<>();
    r.heroes = def != null ? ServerArena.defenderHeroData(def, shard, src) : new java.util.ArrayList<>();
    return r;
  }

  /**
   * ARÈNE #44 — RÉSOLUTION autoritative d'une attaque (résultat rapporté par le client, patron CampaignAttack #19).
   * Décrémente les chances de combat ; sur VICTOIRE, applique la mécanique de RANG du fight pit (battre un mieux
   * classé = prendre sa place, swap dans le ladder) + crédite des points. Mute le classement PERSISTANT (#41) et
   * renvoie {@code ArenaUpdate} (nouveau classement). Accorde aussi l'<b>XP d'arène</b> (héros attaquants) via la
   * logique du jeu ({@code ArenaHelper.giveArenaEXP}). Le combat lui-même est joué côté client (re-sim serveur = #24/#25).
   */
  public synchronized com.perblue.heroes.network.messages.ArenaUpdate resolveArenaAttack(
      long defenderID, boolean win, com.perblue.heroes.network.messages.ArenaType type, ServerArenaLadder ladder) {
    return resolveArenaAttack(defenderID, win, type, ladder, null, null);
  }

  public synchronized com.perblue.heroes.network.messages.ArenaUpdate resolveArenaAttack(
      long defenderID, boolean win, com.perblue.heroes.network.messages.ArenaType type, ServerArenaLadder ladder,
      ServerArena.OpponentSource src) {
    return resolveArenaAttack(defenderID, win, type, ladder, src, null);
  }

  /** Variante vrai PvP + XP : {@code src} rend les rows des vrais joueurs (défense réelle) ; {@code attackers}
   *  ({@code ArenaAttack.base.attackers}) reçoit l'XP d'arène (héros attaquants, {@code giveArenaEXP}).
   *  La mécanique de RANG (swap) déplace À LA FOIS l'attaquant (monte) ET le défenseur (descend) dans le classement
   *  PARTAGÉ+persistant → les DEUX côtés voient le résultat (le défenseur à sa prochaine ouverture). */
  public synchronized com.perblue.heroes.network.messages.ArenaUpdate resolveArenaAttack(
      long defenderID, boolean win, com.perblue.heroes.network.messages.ArenaType type, ServerArenaLadder ladder,
      ServerArena.OpponentSource src, java.util.List<?> attackers) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "arena-attack");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "arena-attack");
    ServerContext.bind(user, iu);
    long myID = userInfo.basicInfo != null ? userInfo.basicInfo.iD : 1L;
    ServerArena.maybeDailyReset(ladder, type, com.perblue.heroes.util.TimeUtil.serverTimeNow());   // régén (heure de JEU)
    int myIdx = ladder.indexOf(myID);
    int defIdx = ladder.indexOf(defenderID);
    if (myIdx >= 0) {
      ServerArenaLadder.Entry me = ladder.entries().get(myIdx);
      me.remainingFightChances = Math.max(0, me.remainingFightChances - 1);   // 1 combat consommé
    }
    if (win) {
      if (myIdx >= 0 && defIdx >= 0 && defIdx < myIdx) {   // le défenseur était MIEUX classé → je prends sa place
        ladder.swap(myIdx, defIdx);
        myIdx = defIdx;
      }
      if (myIdx >= 0) {
        ServerArenaLadder.Entry me = ladder.entries().get(myIdx);
        me.points += ARENA_WIN_POINTS;
        me.pointsTiebreaker = com.perblue.heroes.util.TimeUtil.serverTimeNow();
        if (me.points > me.bestScore) me.bestScore = me.points;
      }
    }
    // XP D'ARÈNE — les héros ATTAQUANTS gagnent de l'XP (logique du jeu ArenaHelper.giveArenaEXP :
    // ArenaStats.getHeroEXPGiven(teamLevel) × multiplicateur d'évènement, sur chaque unité). L'XP modifie l'état
    // des héros → resyncHeroes pour persister (§6). Best-effort : jamais fatal pour la résolution du combat.
    if (attackers != null && !attackers.isEmpty()) {
      try {
        // Snapshot d'évènements = NONE : le serveur n'héberge AUCUN évènement spécial (§F) → multiplicateur d'XP de
        // base (1×). NB : SpecialEventsHelper.snapshot() touche l'UI (Gdx.graphics) → inutilisable en headless ; NONE
        // est la valeur « aucun bonus » du jeu, sans dépendance graphique.
        com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap =
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
        int exp = com.perblue.heroes.game.logic.ArenaHelper.giveArenaEXP(user, attackers, type, snap);
        resyncHeroes(user);                                  // XP héros → wire (userExtra.heroes)
        System.out.println("[arena] XP d'arène accordée aux héros attaquants : +" + exp);
      } catch (Throwable t) {
        System.out.println("[arena] giveArenaEXP échec (PARTIEL) : " + t);
      }
    }
    com.perblue.heroes.network.messages.ArenaInfo ai = ServerArena.buildArenaInfo(user, userInfo, type, ladder, src);
    com.perblue.heroes.network.messages.ArenaUpdate up = new com.perblue.heroes.network.messages.ArenaUpdate();
    up.type = type;
    up.season = ai.season;
    up.yourLeague = ai.yourLeague;
    return up;
  }

  /** Résultat d'un {@link #arenaInfoWithLadder} : l'{@code ArenaInfo} à renvoyer + le classement (à persister). */
  public static final class ArenaResult {
    public final com.perblue.heroes.network.messages.ArenaInfo info;
    public final ServerArenaLadder ladder;
    public ArenaResult(com.perblue.heroes.network.messages.ArenaInfo info, ServerArenaLadder ladder) {
      this.info = info; this.ladder = ladder;
    }
  }

  /**
   * ARÈNE #41 — comme {@link #arenaInfo} mais à partir d'un CLASSEMENT PERSISTANT ({@link ServerArenaLadder}).
   * {@code loaded} = classement chargé de la DB, ou {@code null} → on en GÉNÈRE un (première ouverture). Renvoie
   * l'{@code ArenaInfo} + le classement (que l'appelant persiste : nouveau, ou raffraîchi).
   */
  public synchronized ArenaResult arenaInfoWithLadder(
      com.perblue.heroes.network.messages.ArenaType type, ServerArenaLadder loaded) {
    return arenaInfoWithLadder(type, loaded, null);
  }

  /** Variante vrai PvP : {@code src} peuple le classement avec les VRAIS joueurs du shard (génération OU fusion des
   *  nouveaux dans un ladder chargé), et bâtit leurs rows depuis leur défense réelle. */
  public synchronized ArenaResult arenaInfoWithLadder(
      com.perblue.heroes.network.messages.ArenaType type, ServerArenaLadder loaded, ServerArena.OpponentSource src) {
    ServerContext.init();
    User user = ClientNetworkStateConverter.getUser(userInfo, userExtra, "arena");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        individualUserExtra, userID, userInfo.diamonds, "arena");
    ServerContext.bind(user, iu);
    long myID = userInfo.basicInfo != null ? userInfo.basicInfo.iD : 1L;
    ServerArenaLadder ladder;
    if (loaded != null) {
      ladder = loaded;
      ServerArena.mergeRealOpponents(ladder, src, user.getShardID(), myID, type);   // garde le ladder à jour
    } else {
      ladder = ServerArena.generateLadder(user, userInfo, type, src);
    }
    ServerArena.maybeDailyReset(ladder, type, com.perblue.heroes.util.TimeUtil.serverTimeNow());           // régén combats (heure de JEU)
    com.perblue.heroes.network.messages.ArenaInfo info =
        ServerArena.buildArenaInfo(user, userInfo, type, ladder, src);
    return new ArenaResult(info, ladder);
  }

  /**
   * Construit le contenu {@code battle_pass_v2_constants.tab} à <b>saison courante</b> à pousser au client via
   * {@code BootData.statDataTxt} (cf. bootData()). On <b>réutilise le vrai fichier du jeu</b> (game-data/stats,
   * source de vérité — docs/PRINCIPLES.md §4 : on ne réécrit pas la donnée) et on ne remplace QUE les deux lignes
   * datées {@code SEASON_START_TIME}/{@code HIDE_BATTLE_PASS_AFTER} par les <b>mêmes bornes que l'ancre serveur</b>
   * ({@code BattlePassV2Stats.getSeasonStartTime()}/{@code getBattlePassHiddenTime()}, déjà ré-ancrées au mois
   * courant par refreshBattlePass()). Le client re-parse ce fichier (converter TIME → {@code StatUtil}) et voit
   * alors une saison active → l'onglet s'active et il envoie {@code BattlePassV2GetData}. Renvoie {@code null} en
   * cas de souci (aucun override poussé — dégradé propre, le client garde ses stats).
   */
  private static String battlePassConstantsStatOverride() {
    try {
      long start = com.perblue.heroes.game.data.battlepass.BattlePassV2Stats.getSeasonStartTime();
      long hide  = com.perblue.heroes.game.data.battlepass.BattlePassV2Stats.getBattlePassHiddenTime();
      if (start <= 0 || hide <= 0) return null;
      // Format IDENTIQUE au fichier d'origine (ex. « 2026-04-07T05:00:00.000-05:00 ») avec offset explicite →
      // instant absolu indépendant du fuseau du client. SSS = ms, XXX = offset ±hh:mm.
      java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
      String startStr = fmt.format(new java.util.Date(start));
      String hideStr  = fmt.format(new java.util.Date(hide));
      String path = System.getProperty("dh.stats", "game-data/stats");
      java.io.File f = new java.io.File(new java.io.File(path).getAbsoluteFile(), "battle_pass_v2_constants.tab");
      if (!f.isFile()) return null;
      String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
      StringBuilder out = new StringBuilder(content.length() + 16);
      for (String line : content.split("\n", -1)) {
        if (line.startsWith("SEASON_START_TIME\t"))          line = "SEASON_START_TIME\t" + startStr;
        else if (line.startsWith("HIDE_BATTLE_PASS_AFTER\t")) line = "HIDE_BATTLE_PASS_AFTER\t" + hideStr;
        if (out.length() > 0) out.append('\n');
        out.append(line);
      }
      return out.toString();
    } catch (Throwable t) {
      System.out.println("[bp] override stat-sync battle_pass_v2_constants non construit: " + t);
      return null;
    }
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
