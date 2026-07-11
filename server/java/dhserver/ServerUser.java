package dhserver;

import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.ChangeTutorialStep;
import com.perblue.heroes.network.messages.TutorialAct;

import java.util.ArrayList;
import java.util.List;

/**
 * État serveur AUTORITAIRE d'un joueur (docs/PRINCIPLES.md §3). Un seul compte pour l'instant
 * (nouveau joueur), en mémoire ; la persistance SQLite = étape 5 (docs/SERVER_PLAN.md).
 *
 * <p>Détient l'état que le serveur fait autorité et qu'il renvoie dans le {@link BootData}, et
 * qu'il met à jour d'après les messages du client (le client pilote, le serveur valide/persiste) :
 * ici la <b>progression du tutoriel</b> ({@code individualUserExtra.tutorialActs}), avancée par
 * {@link ChangeTutorialStep}.
 *
 * <p>La structure du BootData vient entièrement des classes du jeu (constructeurs) ; la liste des
 * tutoriels d'un nouveau joueur vient du registre du jeu via {@link NewUserState} — aucune donnée
 * écrite à la main.
 */
public final class ServerUser {

  public final long userID;
  public final int shardID;
  public final long creationTime;
  public int teamLevel = 1;                       // un compte neuf démarre au niveau d'équipe 1

  /** Progression du tutoriel — état autoritatif (avancé par ChangeTutorialStep). */
  private final List<TutorialAct> tutorialActs;

  /** Crée un NOUVEAU joueur (tous les tutoriels de NEW_USER_ACTS à step 0). */
  public ServerUser(long userID, int shardID) {
    this.userID = userID;
    this.shardID = shardID;
    this.creationTime = System.currentTimeMillis();
    this.tutorialActs = NewUserState.newUserTutorialActs();
  }

  /**
   * Construit un {@link BootData} complet reflétant l'état courant. {@code new BootData()} initialise
   * TOUS les champs non-null (constructeurs du jeu) ; on ne renseigne que l'identité + la progression.
   */
  public synchronized BootData bootData() {
    BootData bd = new BootData();
    long now = System.currentTimeMillis();
    bd.serverTime = now;
    bd.currentServer.shardID = shardID;
    bd.userInfo.shardID = shardID;
    bd.userInfo.lastLoginTime = now;
    bd.userInfo.basicInfo.iD = userID;
    bd.userInfo.basicInfo.creationTime = creationTime;
    bd.userInfo.basicInfo.teamLevel = teamLevel;

    // Copie des actes → le client reçoit l'état courant sans partager nos objets autoritatifs.
    List<TutorialAct> copy = new ArrayList<>(tutorialActs.size());
    for (TutorialAct a : tutorialActs) copy.add(clone(a));
    bd.individualUserExtra.tutorialActs = copy;
    return bd;
  }

  /**
   * Applique une progression de tutoriel reçue du client. {@code step} est absolu (cf.
   * {@code TutorialHelper.finishIntroForced} qui pose {@code step = maxStep}). On met à jour le pas
   * courant et le « plus haut pas vu » ({@code TutorialAct.maxStep}). Renvoie {@code true} si un acte
   * a été mis à jour.
   */
  public synchronized boolean applyTutorialStep(ChangeTutorialStep m) {
    for (TutorialAct a : tutorialActs) {
      if (a.type == m.type) {
        a.step = m.step;
        if (m.step > a.maxStep) a.maxStep = m.step;
        return true;
      }
    }
    // Type absent de la liste (hors NEW_USER_ACTS) : le client fait autorité du protocole → on
    // l'ajoute avec la dernière version enregistrée (registre du jeu, pas de valeur inventée).
    int version = NewUserState.latestVersion(m.type);
    if (version < 0) return false;                // type sans acte enregistré dans cet APK
    TutorialAct a = new TutorialAct();
    a.type = m.type;
    a.version = version;
    a.step = m.step;
    a.maxStep = m.step;
    tutorialActs.add(a);
    return true;
  }

  /** Copie superficielle d'un {@link TutorialAct} (champs publics type/version/step/maxStep). */
  private static TutorialAct clone(TutorialAct src) {
    TutorialAct a = new TutorialAct();
    a.type = src.type;
    a.version = src.version;
    a.step = src.step;
    a.maxStep = src.maxStep;
    return a;
  }
}
