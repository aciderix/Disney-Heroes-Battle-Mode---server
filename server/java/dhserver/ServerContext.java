package dhserver;

import com.perblue.heroes.DH;
import com.perblue.heroes.GameMain;
import com.perblue.heroes.game.data.content.ContentHelper;
import com.perblue.heroes.game.logic.SpecialEventsHelper;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.specialevent.ClientEventUserProvider;
import com.perblue.heroes.network.messages.GuildInfo;
import com.perblue.heroes.network.messages.SpecialEventsRaw;

import java.lang.reflect.Field;

/**
 * Contexte d'exécution serveur pour faire tourner la LOGIQUE du jeu headless (docs/PRINCIPLES.md §3
 * « lire & exécuter »). Deux choses :
 * <ol>
 *   <li>charge la couche données du jeu ({@link ServerStats#install()} → les {@code .tab}) ;</li>
 *   <li>fournit un {@code DH.app} — beaucoup de classes du jeu passent par ce singleton client
 *       ({@code GameMain}), ex. {@code User.getIndividual()} = {@code DH.app.getYourIndividualUser()}.
 *       On alloue un {@code GameMain} <b>sans constructeur</b> (Unsafe) et on pose {@code user}/
 *       {@code individualUser} par réflexion → les getters répondent. C'est de la couche plateforme,
 *       pas de la logique de jeu.</li>
 * </ol>
 * Un seul compte pour l'instant → un seul {@code DH.app} lié au joueur courant.
 */
public final class ServerContext {

  private static GameMain app;              // GameMain headless (DH.app)
  private static Field userField, individualField, guildInfoField, battlePassField;

  private ServerContext() {}

  /** Initialise la couche données + le shim {@code DH.app} (idempotent). */
  public static synchronized void init() {
    if (app != null) return;
    ServerStats.install();
    try {
      Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Object unsafe = theUnsafe.get(null);
      // GameMain alloué SANS exécuter son constructeur (aucune init client/libGDX).
      app = (GameMain) unsafe.getClass().getMethod("allocateInstance", Class.class)
          .invoke(unsafe, GameMain.class);
      Field appF = DH.class.getDeclaredField("app"); appF.setAccessible(true); appF.set(null, app);
      // Accès natif (INative) : GameMain.getNativeAccess() est null sur le shim → NPE dès qu'un chemin du jeu
      // appelle handleSilentException (son propre gestionnaire « exception attrapée & journalisée, on continue »).
      // Ex. QuestHelper (getUnlockedAchievements/isReadyToComplete/completeQuest) attrape des exceptions internes
      // et les remonte via DH.app.getNativeAccess().handleSilentException(t). Le vrai client a un INative (couche
      // Android/unidbg) ; côté serveur on pose un INative NO-OP (proxy dynamique) : handleSilentException LOGue
      // (visibilité), tout le reste renvoie le défaut (null/0/false). C'est de la COUCHE PLATEFORME (comme
      // dhbackend/), PAS de la logique de jeu : on reproduit le chemin « silencieux/récupérable » d'origine
      // (le jeu attrape et continue) sans le reporting crash, qui est client-only. Non-null → plus de NPE.
      com.perblue.heroes.INative nativeShim = (com.perblue.heroes.INative) java.lang.reflect.Proxy.newProxyInstance(
          com.perblue.heroes.INative.class.getClassLoader(),
          new Class[]{com.perblue.heroes.INative.class},
          (proxy, method, margs) -> {
            if ("handleSilentException".equals(method.getName()) && margs != null && margs.length > 0
                && margs[0] instanceof Throwable) {
              System.out.println("[ctx] (INative.handleSilentException, silencieux/récupérable) "
                  + margs[0].getClass().getSimpleName() + ": " + ((Throwable) margs[0]).getMessage());
            }
            Class<?> rt = method.getReturnType();
            if (!rt.isPrimitive()) return null;
            if (rt == boolean.class) return false;
            if (rt == void.class) return null;
            if (rt == long.class) return 0L; if (rt == int.class) return 0;
            if (rt == float.class) return 0f; if (rt == double.class) return 0d;
            if (rt == short.class) return (short) 0; if (rt == byte.class) return (byte) 0;
            if (rt == char.class) return (char) 0;
            return null;
          });
      // On pose le CHAMP directement (réflexion) et PAS via setNativeAccess() : le setter fait plus qu'assigner
      // (il appelle nativeAccess.createPurchasingInterface().initializePreNetwork() → NPE avec un proxy no-op).
      // On veut seulement que getNativeAccess() renvoie un non-null pour handleSilentException.
      Field nativeF = field(GameMain.class, "nativeAccess");
      nativeF.set(app, nativeShim);
      // Mode HEADLESS/OFFLINE du jeu : SERVER_TYPE=NONE est le propre commutateur du jeu qui DÉSACTIVE
      // l'instrumentation RNG client→serveur (InstrumentedRandom : resetRandom/getRandom testent
      // `SERVER_TYPE == NONE` pour SAUTER l'envoi de RandomEvents). Sans ça, rouler un flux RNG (ex. le loot
      // autoritaire #25 via user.getRandom(LOOT)) tente `getNetworkProvider().sendMessage(...)` → NPE headless.
      // N'affecte QUE l'envoi d'événements, PAS les valeurs RNG (même graine → même séquence). Valeur du jeu,
      // pas une rustine (c'est le chemin offline prévu par PerBlue).
      com.perblue.heroes.BuildOptions.SERVER_TYPE = com.perblue.heroes.ServerType.NONE;
      applyClockOffset();
      // NB : on NE pose PAS CodeLocationHelper.SERVER — le jeu initialise ContentHelper/stats en client-location
      // (extension null en SERVER → NPE ShardStats.getStats). On reste en client-location ; les rares chemins
      // client-only fragiles headless (ex. QuestStats.getAllQuestIDs = thread-check + copie gdx Array) sont
      // ÉVITÉS (on n'appelle pas les API de DÉCOUVERTE d'UI ; on exécute les logiques ciblées par ID).
      userField = field(GameMain.class, "user");
      individualField = field(GameMain.class, "individualUser");
      guildInfoField = field(GameMain.class, "guildInfo");
      // BATTLE PASS V2 : le vrai client pose DH.app.userBattlePassV2 = new BattlePassV2DataWrapper(data) à la
      // réception du BootData/d'un push BattlePassV2Data (GameMain.lambda$setupPostClientInfoHandlers). Côté
      // serveur on fait pareil (bindBattlePass) → getUserBattlePassV2() répond au lieu de NPE. Crucial : la
      // PROGRESSION du battle pass EST une ressource du jeu (ResourceType.QUEST_POINTS) — IndividualUser.
      // setResource(QUEST_POINTS) route vers getUserBattlePassV2().setProgress, et getResource(QUEST_POINTS)
      // lit getProgress (prouvé au bytecode). Donc en liant le wrapper sur NOTRE BattlePassV2Data persisté,
      // la progression s'accumule TOUTE SEULE via le code du jeu quand une quête donne des QUEST_POINTS
      // (zéro glue — PRINCIPLES §3), et les claims/progress écrivent directement dans le message persisté.
      battlePassField = field(GameMain.class, "userBattlePassV2");
      // Couche évènements spéciaux — comme GameMain.create() :
      // SpecialEventsHelper.init(new ClientEventUserProvider(), extension). L'extension CLIENTE touche
      // libGDX (« Gdx.app not available » headless) → on fournit l'équivalent SERVEUR (ServerSpecialEventsExt).
      // Sans ça, SpecialEventsHelper.helper est null → NPE dès qu'un don d'objet enregistre une tâche de
      // contest (ChestHelper.giveChestRewards → RewardHelper.giveReward → ContestHelper.onItemEarn).
      SpecialEventsHelper.init(new ClientEventUserProvider(), new ServerSpecialEventsExt());
      // BATTLE PASS — SAISON ROULANTE MENSUELLE (choix d'opérateur : serveurs d'achats fermés). La saison BP
      // est une fenêtre datée FIXE dans la donnée (battle_pass_v2_constants.tab : SEASON_START 2026-04-07,
      // HIDE_BATTLE_PASS_AFTER 2026-04-30) → passée à notre date (2026+), donc `battlePassHidden()` = vrai
      // (now >= HIDE) → BP inactif. Pour un serveur ré-hébergé, on ANCRE la saison sur le MOIS COURANT
      // (SEASON_START = 1er du mois, HIDE = 1er du mois suivant) → le BP est TOUJOURS actif, roulant chaque
      // mois, en réutilisant les paliers/récompenses de la `.tab`. On modifie les constantes parsées
      // (`BattlePassV2Stats.CONSTANT_STATS.getStats()` → champs `Constants.SEASON_START_TIME`/
      // `HIDE_BATTLE_PASS_AFTER`, longs epoch-ms) par réflexion — couche plateforme/config, PAS de la logique
      // de jeu (le calcul de récompenses/paliers reste celui du jeu). N'affecte QUE le BP (pas la date globale
      // → stamina/contenu inchangés). Combiné au `boughtBattlePass=1` du BootData (premium pour tous).
      anchorBattlePassSeason();   // ancre la saison sur le mois COURANT (re-appelé dynamiquement, cf. méthode)
      // Légalité du nom (SetPlayerName / CHOOSE NAME) : NameChangeHelper.isNameLegal fait le CŒUR (noms
      // interdits ILLEGAL_NAMES, codepoints valides, alphabétique/chiffre/idéographique) PUIS délègue à
      // isNameLegalExt (Predicate CLIENTE) qui vérifie le rendu POLICE (DisplayStringUtil.
      // containsUnsupportedCharacters → LanguageHelper.getPreferredLanguage → Gdx.app.getPreferences) → NPE
      // headless (Gdx.app null). Le rendu police est une préoccupation CLIENTE — le client l'a DÉJÀ validée
      // avant d'envoyer SetPlayerName (ChangeNamePrompt appelle changeName localement d'abord). On pose donc
      // un ext SERVEUR qui renvoie true après le cœur (comme ServerSpecialEventsExt omet la poussée réseau
      // cliente) : PAS une rustine — la légalité de fond s'exécute, seule la vérif de police (sans objet
      // serveur) est omise. isNameLegalExt = champ statique privé (com.badlogic.gdx.utils.Predicate).
      try {
        Field ext = com.perblue.heroes.game.logic.NameChangeHelper.class.getDeclaredField("isNameLegalExt");
        ext.setAccessible(true);
        ext.set(null, (com.badlogic.gdx.utils.Predicate<String>) s -> true);
      } catch (Throwable t) { System.out.println("[ctx] isNameLegalExt (serveur) non posé: " + t); }
      // CHALLENGES (#72) — le vrai GameMain(ctor) pose historicWeeklyChallenges = new HistoricWeeklyChallenges()
      // (conteneur VIDE non-null, offset 370 du ctor). Notre shim (alloué SANS ctor) le laisse null → l'extension
      // du jeu StickerHelper$1.getHistoricChallenges() (= DH.app.getHistoricWeeklyChallenges()) NPE dès qu'une
      // logique sticker CLIENTE tourne headless (canStart/isUnlocked/createHandleExtra/setupStarterChallenges).
      // On pose la MÊME valeur que le ctor du jeu — couche plateforme (§4), zéro donnée inventée (conteneur vide
      // ⇒ getCurrentChallenges/getNextChallenges/getHistoricChallenges rendent des collections vides = aucune
      // règle changée). Contrairement à userChallengeData (RÉSERVÉ à l'oracle, g59 — le poser globalement réactive
      // la cascade notifyChallenges → setupWeeklyChallenges à chaque action), CE champ n'est que LU (aucune cascade)
      // → sûr globalement, et REQUIS par les opérations serveur de défis ({@code ServerChallenges}).
      try {
        Field hwc = field(GameMain.class, "historicWeeklyChallenges");
        if (hwc.get(app) == null)
          hwc.set(app, new com.perblue.heroes.game.data.stickerbook.HistoricWeeklyChallenges());
      } catch (Throwable t) { System.out.println("[ctx] fixture historicWeeklyChallenges non posée: " + t); }
      // WARM-UP GuildStats (guildes #7) : la 1ʳᵉ lecture des perks de guilde déclenche le PARSE PARESSEUX de
      // guild_perk_levels.tab, dont les lignes TIMED_* ont un CONTENT_TL vide (parseInt("") lève, journalisé
      // SEVERE par onStatError pendant le chargement). Sous accès concurrent (l'écran de guilde demande le
      // check-in ET le clic manuel), ce parse paresseux non ré-entrant lève par intermittence un
      // NumberFormatException jusqu'à ce que le chargement soit terminé. On force le chargement UNE fois ici
      // (mono-thread), en absorbant les erreurs de cellule, pour que l'accès runtime soit ensuite stable.
      try {
        com.perblue.heroes.game.objects.GuildInfoPerkProvider warm =
            new com.perblue.heroes.game.objects.GuildInfoPerkProvider(new GuildInfo());
        com.perblue.heroes.game.logic.GuildCheckInHelper.getMaxDailyCheckIns(warm);
      } catch (Throwable t) { System.out.println("[ctx] warm-up GuildStats (perks): " + t); }
      // WARM-UP PatchStats (héros patchés) — MÊME classe de bug que GuildStats ci-dessus. Le combat des modes
      // « difficulty » (PORT_DOCKS/WAREHOUSE) passe par doChecks → DailyActivityHelper.getMaxDailyUsesRaw, qui
      // déclenche le PARSE PARESSEUX de patched_heroes_talent_assignments.tab. Une ligne (EVIL_QUEEN, talent
      // PREDICTIVE_FORTIFICATION ABSENT de l'enum PatchTalent 12.1.0) fait lever saveRow ; en CHARGEMENT MONO-THREAD
      // ici (stats déjà ouvertes), le parse se termine (cellule fautive absorbée) et PatchStats charge PROPREMENT.
      // Sous accès RUNTIME concurrent (combat PORT en jeu), ce parse non ré-entrant se POISONNAIT
      // (ExceptionInInitializerError → NPE sur la classe empoisonnée). On force donc le <clinit> UNE fois ici.
      // (FAIT vérifié g118 : Class.forName réussit à ce stade — cf. sonde PatchProbe ; corrige le combat PORT en jeu.)
      try {
        Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats");
      } catch (Throwable t) { System.out.println("[ctx] warm-up PatchStats: " + t); }
      // CONTEST (gap C) : installe l'extension serveur de ContestHelper (crédit des tâches de contest de GUILDE — sinon
      // le champ statique `extension` est null en headless et le crédit de guilde est perdu ; le solo reste crédité à
      // l'identique, sans double-compte — vérifié). §3 : nous sommes le backend, les méthodes record* par défaut du jeu
      // font le vrai barème.
      ServerContestExtension.install();
      System.out.println("[ctx] DH.app headless + données du jeu + couche évènements spéciaux");
    } catch (Throwable t) {
      throw new RuntimeException("échec init contexte serveur (DH.app)", t);
    }
  }

  /**
   * DÉCALAGE D'HORLOGE de l'opérateur ({@code -Ddh.clock.offset.hours=<h>}, défaut 0).
   *
   * <p>Le serveur est la SOURCE DE L'HEURE : le client se cale dessus (`TimeUtil.initClock(BootData
   * .serverTime, deviceTime)` au login puis à chaque `Ping`). Décaler l'horloge SERVEUR décale donc
   * l'ensemble de façon cohérente — serveur et client voient la même date, et toutes les mécaniques datées
   * (fenêtre d'INVASION lundi 12 h → samedi 12 h, `RESET_HOUR` des guerres, saisons, cooldowns) suivent
   * <b>leur propre logique</b>, inchangée.
   *
   * <p>Ce n'est donc PAS un contournement de règle (PRINCIPLES §2) : aucune vérification n'est court-circuitée,
   * on avance la pendule. Indispensable pour vérifier EN JEU un mode qui n'est ouvert que certains jours,
   * sans attendre le bon jour de la semaine. À 0 (défaut) le serveur utilise l'heure réelle.
   *
   * <p>Mise en œuvre : on pose {@code TimeUtil.CLOCK_OFFSET} (champ privé) de sorte que
   * {@code serverTimeNow() = System.currentTimeMillis() - CLOCK_OFFSET} rende l'heure décalée ; tout ce qui
   * date côté serveur passe par ce même accesseur.
   */
  private static void applyClockOffset() {
    long hours = Long.getLong("dh.clock.offset.hours", 0L);
    if (hours == 0L) return;
    // -Ddh.clock.offset.hours n'est qu'un BOOTSTRAP (dev). La SOURCE ROBUSTE est l'ancre PERSISTÉE en DB
    // (clockOffsetMillis, appliquée par LoginServer au boot). serverTimeNow = now − OFFSET → OFFSET négatif = avance.
    setClockOffsetMillis(-hours * 3600_000L);
  }

  /** Pose {@code TimeUtil.CLOCK_OFFSET} (ms) au RUNTIME. {@code serverTimeNow() = currentTimeMillis() − OFFSET}
   *  (OFFSET négatif = heure de jeu AVANCÉE ; positif = RECULÉE). C'est l'unique horloge de JEU : l'ère de contenu
   *  (getServerColumn), la fenêtre d'invasion, les saisons, tous les décomptes la suivent. Persistée en DB par
   *  l'opérateur (cf. {@code AdminClock}) pour survivre aux redémarrages sans dérive. */
  public static void setClockOffsetMillis(long offsetMs) {
    try {
      Field f = com.perblue.heroes.util.TimeUtil.class.getDeclaredField("CLOCK_OFFSET");
      f.setAccessible(true);
      f.setLong(null, offsetMs);
      System.out.println("[ctx] ⏱ horloge de jeu : offset " + offsetMs + " ms → "
          + new java.util.Date(com.perblue.heroes.util.TimeUtil.serverTimeNow()));
    } catch (Throwable t) {
      System.out.println("[ctx] décalage d'horloge impossible : " + t);
    }
  }

  /** Offset d'horloge courant (ms) — {@code currentTimeMillis() − serverTimeNow()}. 0 = heure réelle. */
  public static long clockOffsetMillis() {
    try {
      Field f = com.perblue.heroes.util.TimeUtil.class.getDeclaredField("CLOCK_OFFSET");
      f.setAccessible(true);
      return f.getLong(null);
    } catch (Throwable t) { return 0L; }
  }

  // ─── ANCRE DE SAISON (config ADMIN), DÉCOUPLÉE des timers joueur ──────────────────────────────────────
  // L'HORLOGE (serverTimeNow, CLOCK_OFFSET) pilote deux préoccupations distinctes qu'il faut SÉPARER :
  //   • TIMERS JOUEUR (resets quotidiens, cooldowns, régén, horodatages…) → doivent suivre le temps RÉEL ;
  //   • SÉLECTION DE SAISON live-ops (quels FRANCHISE_TRIALS de saison sont actifs) → choix ÉDITORIAL admin.
  // Aujourd'hui les deux lisent serverTimeNow → couplés : reculer la date pour changer de saison décalerait
  // aussi tous les timers joueur. On introduit une ANCRE DE SAISON = un OFFSET ADDITIONNEL, appliqué UNIQUEMENT
  // à la sélection de saison (ServerEvents.seasonTrialConfigs via seasonTimeNow()), JAMAIS aux timers joueur.
  // Défaut 0 → seasonTimeNow() == serverTimeNow() (comportement historique, aucun changement). L'admin le règle
  // (outil AdminSeason), il est persisté en méta `season_anchor_offset_ms` et ré-appliqué au boot par LoginServer.
  // NB (choix de fidélité) : l'ÈRE DE CONTENU (R1…R102, ContentStats.getServerColumn) reste couplée à serverTimeNow
  // À DESSEIN — le client synchronise son horloge sur BootData.serverTime et résout SON contenu daté par cette date ;
  // découpler l'ère provoquerait un affichage incohérent côté client. Seule la saison (poussée par NOUS, invisible
  // au calendrier client) est découplée ici. (Le jeu modélise d'ailleurs un offset de contenu PAR JOUEUR via
  // ContentStats.setUserOffset/getServerColumn(IUser) — non requis pour ce découplage-ci.)
  private static volatile long SEASON_ANCHOR_OFFSET_MS = 0L;

  /** Offset d'ancre de saison courant (ms), ADDITIONNÉ à {@code serverTimeNow()} pour la sélection de saison. 0 = suit la date réelle. */
  public static long seasonAnchorOffsetMillis() { return SEASON_ANCHOR_OFFSET_MS; }

  /** Règle l'ancre de saison (ms). Découplée de l'horloge : n'affecte QUE {@code seasonTimeNow()} (sélection de saison), pas les timers joueur. */
  public static void setSeasonAnchorOffsetMillis(long offsetMs) {
    SEASON_ANCHOR_OFFSET_MS = offsetMs;
    System.out.println("[ctx] ⏱ ancre de saison : offset " + offsetMs + " ms → saison résolue à "
        + new java.util.Date(seasonTimeNow()));
  }

  /** Heure de RÉFÉRENCE POUR LA SAISON = {@code serverTimeNow() + ancre de saison}. Découplée des timers joueur (qui gardent serverTimeNow). */
  public static long seasonTimeNow() {
    return com.perblue.heroes.util.TimeUtil.serverTimeNow() + SEASON_ANCHOR_OFFSET_MS;
  }

  /** Lie le joueur courant au shim {@code DH.app} (getYourUser/getYourIndividualUser). */
  public static synchronized void bind(User user, IndividualUser individualUser) {
    init();
    try {
      userField.set(app, user); individualField.set(app, individualUser);
      // guildInfo : beaucoup de chemins passent par DH.app.getYourGuildInfo() (ex.
      // GameStateManager.startAction → GuildPerkHelper.updateGuildInfoTimedPerks lit guildInfo.perkEndTimes).
      // Nouveau joueur sans guilde = new GuildInfo() (champs non-null par le constructeur, perkEndTimes = map
      // vide) → plus de NPE. À remplacer par le vrai GuildInfo du joueur quand les guildes seront gérées.
      if (guildInfoField.get(app) == null) guildInfoField.set(app, new GuildInfo());
      // Couche de CONTENU (colonnes de release) : ContentHelper est vide tant qu'on n'a pas chargé le
      // contenu du shard. Sans ça, ContentStats.getColumns()=0 → getColumn(now)=DEFAULT → isItemReleased
      // renvoie false pour TOUT → toute logique gatée « contenu released » casse (ex. getSlotThatCanEquip).
      // setShardID(shard, {}) charge `content.<shard>.tab` via notre ouvreur de stats (comme le boot du jeu).
      if (ContentHelper.get() != null)
        ContentHelper.get().setShardID(user.getShardID(), new java.util.HashMap());
      // Charge les évènements du joueur dans la couche — comme GameMain.handleBootData
      // (SpecialEventsHelper.setSpecialEvents). Nouveau joueur sans évènement live = raw vide → aucun
      // contest actif (getActiveContestsWithTask renvoie une liste vide au lieu de NPE).
      SpecialEventsHelper.setSpecialEvents(new SpecialEventsRaw(), user, user.getShardID());
      // SPECIAL_EVENTS (live-ops opérateur) : après le raw vide, on POSE les événements opérateur (objets du jeu,
      // cf. ServerEvents). Incr. 1 : ouverture des modes PORT (DOCKS + WAREHOUSE) → le serveur autoritatif les accepte.
      // Idempotent (ré-installé à chaque bind ; l'état est global à la couche événements). Cf. docs/SPECIAL_EVENTS.md.
      try { ServerEvents.installBootDefaults(); } catch (Throwable t) { System.out.println("[ctx] events install: " + t); }
    } catch (Throwable t) { throw new RuntimeException("échec bind DH.app", t); }
  }

  /**
   * Lie l'état battle pass du joueur courant au shim {@code DH.app.getUserBattlePassV2()} — comme le vrai
   * client à la réception d'un {@code BattlePassV2Data}. On enveloppe le message PERSISTÉ dans un
   * {@link com.perblue.heroes.game.data.battlepass.BattlePassV2DataWrapper} (writes-through : progress/claims
   * mutent directement le message → persistés). {@code null} ⇒ pas de battle pass lié (chemins hors BP).
   */
  public static synchronized void bindBattlePass(
      com.perblue.heroes.network.messages.BattlePassV2Data data) {
    init();
    try {
      battlePassField.set(app, data == null ? null
          : new com.perblue.heroes.game.data.battlepass.BattlePassV2DataWrapper(data));
    } catch (Throwable t) { throw new RuntimeException("échec bind battle pass DH.app", t); }
  }

  /**
   * FIXTURE de RENDU CLIENT — réservée à l'ORACLE headless ({@code ClientOracle}, #74 B2b). Pose sur le shim
   * {@code DH.app} les structures que le CLIENT a en mémoire pour RENDRE LE HUB mais que le serveur n'a pas
   * (il ne rend rien) : le conteneur de DÉFIS ({@code userChallengeData}, lu par les daily quests via
   * {@code getYourChallengeData}) et le CATALOGUE IAP ({@code iapProducts}, itéré par {@code PurchaseHelper}
   * depuis les daily quests). Ce sont des conteneurs VIDES du jeu (ctor no-arg = ce que le boot remplirait) —
   * couche plateforme (§4), aucune donnée/règle inventée.
   *
   * <p>⚠️ NE PAS poser ces champs dans {@link #bind} (chemin serveur global) : le sous-système de défis
   * (City Watch / stickerbook, #72) n'est PAS implémenté côté serveur — le rendre non-nul GLOBALEMENT réactive
   * {@code UserActivityTracker.notifyChallenges → StickerHelper.setupWeeklyChallenges} sur CHAQUE action serveur
   * (createGuild, combat…), qui exige une {@code StickerHelperExtension} absente headless → NPE (cascade de shim,
   * violerait §2 « pas d'état cassé plus tard »). C'est donc RÉSERVÉ à l'oracle, qui simule le rendu CLIENT du
   * hub. Idempotent (ne pose que si nul).
   */
  public static synchronized void installClientHubRenderFixtures() {
    init();
    try {
      Field cd = field(GameMain.class, "userChallengeData");
      if (cd.get(app) == null)
        cd.set(app, new com.perblue.heroes.game.data.stickerbook.ClientUserChallengeData());
      Field ip = field(GameMain.class, "iapProducts");
      if (ip.get(app) == null)
        ip.set(app, new com.perblue.heroes.network.messages.IAPProducts());
    } catch (Throwable t) { throw new RuntimeException("échec fixture de rendu client (oracle)", t); }
  }

  private static long anchoredSeasonStart = 0;

  /**
   * ANCRE la saison battle pass sur le MOIS COURANT (SEASON_START = 1er du mois à 00:00, HIDE = 1er du mois
   * suivant) en écrivant les constantes parsées ({@code Constants.SEASON_START_TIME}/{@code HIDE_BATTLE_PASS_AFTER}).
   *
   * <p><b>Pourquoi DYNAMIQUE (et pas seulement à l'init).</b> Fait établi : les constantes ne sont écrites qu'une
   * fois ; si on ne ré-ancre jamais, un serveur qui tourne de juillet à août SANS redémarrer garderait
   * {@code getSeasonStartTime()} = 1er juillet → la saison ne « roulerait » jamais (et le reset de rollover ne
   * se déclencherait jamais). On ré-ancre donc à CHAQUE {@code refreshBattlePass} (appelé par action) : dès que
   * le mois réel change, {@code getSeasonStartTime()} renvoie le nouveau mois → {@code refreshBattlePass}
   * détecte {@code bp.startTime != seasonStart} et effectue le rollover. Coût négligeable (Calendar + 2 champs).
   * Idempotent tant que le mois ne change pas (ne logge que sur changement).
   */
  public static synchronized void anchorBattlePassSeason() {
    try {
      java.util.Calendar cal = java.util.Calendar.getInstance();
      cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
      cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0);
      cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
      long monthStart = cal.getTimeInMillis();
      cal.add(java.util.Calendar.MONTH, 1);
      long monthEnd = cal.getTimeInMillis();                 // 1er du mois suivant → toujours > now
      Field csF = com.perblue.heroes.game.data.battlepass.BattlePassV2Stats.class.getDeclaredField("CONSTANT_STATS");
      csF.setAccessible(true);
      Object constStats = csF.get(null);                      // DHConstantStats
      Object constants = com.perblue.common.stats.ConstantStats.class.getMethod("getStats").invoke(constStats);
      Class<?> cc = constants.getClass();
      setLongField(cc, constants, "SEASON_START_TIME", monthStart);
      setLongField(cc, constants, "HIDE_BATTLE_PASS_AFTER", monthEnd);
      if (monthStart != anchoredSeasonStart) {                // ne logge qu'au (re)ancrage effectif
        anchoredSeasonStart = monthStart;
        System.out.println("[ctx] battle pass : saison ancrée au mois courant (start=" + monthStart
            + " hide=" + monthEnd + ") → toujours actif, roulant");
      }
    } catch (Throwable t) { System.out.println("[ctx] override saison battle pass non posé: " + t); }
  }

  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    for (Class<?> k = c; k != null; k = k.getSuperclass()) {
      try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; }
      catch (NoSuchFieldException e) { /* remonter */ }
    }
    throw new NoSuchFieldException(name);
  }

  /** Pose un champ {@code long} (par réflexion, en remontant la hiérarchie) — override de constantes parsées. */
  private static void setLongField(Class<?> c, Object target, String name, long value) throws Exception {
    field(c, name).setLong(target, value);
  }
}
