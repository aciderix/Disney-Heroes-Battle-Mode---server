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
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.BuyChests;
import com.perblue.heroes.network.messages.ChangeTutorialStep;
import com.perblue.heroes.network.messages.ChestType;
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
    ui.basicInfo.creationTime = System.currentTimeMillis();
    ui.basicInfo.teamLevel = 1;                   // un compte neuf démarre au niveau d'équipe 1
    UserExtra ue = new UserExtra();
    IndividualUserExtra iue = new IndividualUserExtra();
    iue.tutorialActs = NewUserState.newUserTutorialActs();
    return new ServerUser(userID, shardID, ui, ue, iue);
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
    userExtra.heroes.clear();
    for (Object o : user.getHeroes()) {
      UnitData ud = (UnitData) o;
      userExtra.heroes.put(ud.getType(), ClientNetworkStateConverter.getHeroData(ud));
    }
    individualUserExtra.chestUpgradeXP = iu.getChestUpgradeXP();
    return lr;
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
