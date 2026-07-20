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
      System.out.println("[ctx] DH.app headless + données du jeu + couche évènements spéciaux");
    } catch (Throwable t) {
      throw new RuntimeException("échec init contexte serveur (DH.app)", t);
    }
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
