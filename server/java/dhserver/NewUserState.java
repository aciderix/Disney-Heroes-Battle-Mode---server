package dhserver;

import com.badlogic.gdx.utils.IntMap;
import com.perblue.heroes.game.tutorial.TutorialHelper;
import com.perblue.heroes.network.messages.BasicUserInfo;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.TutorialAct;
import com.perblue.heroes.network.messages.TutorialActType;
import com.perblue.heroes.network.messages.UserInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Construit l'état d'un NOUVEAU joueur, de sorte que le client d'origine le route vers le
 * TUTORIEL D'INTRODUCTION (IntroTutorialActV2, cf. {@code TutorialHelper}).
 *
 * <p>PRINCIPE (docs/PRINCIPLES.md §3/§4) : aucune donnée écrite à la main. Toute la structure
 * vient des <b>classes du jeu</b> : {@link BootData} et ses sous-objets sont complets par leurs
 * propres initialiseurs (constructeur), et la liste des tutoriels d'un nouveau joueur est lue
 * <b>directement dans le registre du jeu</b> {@code TutorialHelper.NEW_USER_ACTS} + {@code ACTS}
 * (type → version). Régénérable : si l'APK change, la liste suit automatiquement.
 *
 * <p>FLUX ÉTABLI (extraction, cf. docs/PROTOCOL.md §6) :
 * {@code GameMain.handleBootData} → {@code ClientNetworkStateConverter.getIndividualUser(userExtra)}
 * → {@code IndividualUser.setExtra} itère {@code individualUserExtra.tutorialActs} →
 * {@code getTutorialAct(type)}. {@code TutorialHelper.completedTutorialAct} renvoie {@code true}
 * quand l'acte est ABSENT (⇒ tutoriel « déjà fait/sauté »). Donc un nouveau joueur DOIT porter
 * TOUS les actes de {@code NEW_USER_ACTS} à {@code step = 0} (IN_PROG) — sinon les fonctionnalités
 * (UNLOCK_HERO, etc.) ne seraient jamais introduites. La complétion se décide sur
 * {@code step >= act.getMaxStep()} (le maxStep du registre, pas celui du message).
 */
public final class NewUserState {

  private NewUserState() {}

  /** Peuple {@code bd} (issu de {@code new BootData()}) comme un nouveau joueur → tutoriel d'intro. */
  public static void fillNewPlayer(BootData bd, long userID, int shardID) {
    long now = System.currentTimeMillis();
    bd.serverTime = now;

    // Serveur courant (lu par handleBootData : currentServer.shardID pour la sync des stats).
    bd.currentServer.shardID = shardID;

    // Identité minimale d'un compte neuf (le reste est complet par le constructeur du jeu).
    UserInfo ui = bd.userInfo;
    ui.shardID = shardID;
    ui.lastLoginTime = now;
    BasicUserInfo bi = ui.basicInfo;
    bi.iD = userID;
    bi.creationTime = now;
    bi.teamLevel = 1;                 // un compte neuf démarre au niveau d'équipe 1

    // Actes de tutoriel du nouveau joueur — DIRECTEMENT depuis le registre du jeu (aucune saisie).
    bd.individualUserExtra.tutorialActs = newUserTutorialActs();
  }

  /**
   * Liste des {@link TutorialAct} d'un nouveau joueur : chaque type de
   * {@code TutorialHelper.NEW_USER_ACTS}, à la <b>dernière version enregistrée</b> (registre
   * {@code TutorialHelper.ACTS}), {@code step = 0} (IN_PROG), {@code maxStep = 0} (aucun pas encore vu).
   */
  @SuppressWarnings("unchecked")
  public static List<TutorialAct> newUserTutorialActs() {
    List<TutorialAct> out = new ArrayList<>();
    try {
      Field fNew = TutorialHelper.class.getDeclaredField("NEW_USER_ACTS");
      fNew.setAccessible(true);
      List<TutorialActType> types = (List<TutorialActType>) fNew.get(null);

      Field fActs = TutorialHelper.class.getDeclaredField("ACTS"); // EnumObjMap<type, IntMap<version,act>>
      fActs.setAccessible(true);
      Object acts = fActs.get(null);
      java.lang.reflect.Method get = acts.getClass().getMethod("get", Enum.class);

      for (TutorialActType type : types) {
        IntMap<?> versions = (IntMap<?>) get.invoke(acts, type);
        int latest = latestVersion(versions);
        if (latest < 0) continue;     // aucun acte enregistré pour ce type dans cet APK → ignoré
        TutorialAct a = new TutorialAct();
        a.type = type;
        a.version = latest;
        a.step = 0;                   // IN_PROG (0 < maxStep) → le tutoriel se joue
        a.maxStep = 0;                // « plus haut pas vu » = 0 pour un compte neuf
        out.add(a);
      }
    } catch (Throwable t) {
      throw new RuntimeException("échec de construction des tutoriels nouveau joueur", t);
    }
    return out;
  }

  /** Plus haute clé (version) d'une {@code IntMap} de versions ; -1 si vide. */
  private static int latestVersion(IntMap<?> versions) throws Exception {
    int latest = -1;
    for (Object entry : versions) {              // IntMap implémente Iterable<Entry>
      int key = entry.getClass().getField("key").getInt(entry);
      if (key > latest) latest = key;
    }
    return latest;
  }
}
