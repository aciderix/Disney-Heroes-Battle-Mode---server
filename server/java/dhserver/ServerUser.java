package dhserver;

import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.ChangeTutorialStep;
import com.perblue.heroes.network.messages.IndividualUserExtra;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.TutorialAct;
import com.perblue.heroes.network.messages.UserExtra;
import com.perblue.heroes.network.messages.UserInfo;

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
