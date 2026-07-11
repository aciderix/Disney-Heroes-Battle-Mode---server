package dhserver;

import com.badlogic.gdx.utils.IntMap;
import com.perblue.heroes.game.tutorial.TutorialHelper;
import com.perblue.heroes.network.messages.TutorialAct;
import com.perblue.heroes.network.messages.TutorialActType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabrique des tutoriels d'un NOUVEAU joueur, lus <b>directement dans le registre du jeu</b>
 * ({@code TutorialHelper.NEW_USER_ACTS} + {@code ACTS} : type → version), afin que le client
 * d'origine route vers le TUTORIEL D'INTRODUCTION (IntroTutorialActV2).
 *
 * <p>PRINCIPE (docs/PRINCIPLES.md §3/§4) : aucune donnée écrite à la main ; régénérable (si l'APK
 * change, la liste suit). La structure du {@code BootData} vient des constructeurs du jeu
 * (assemblée par {@link ServerUser#bootData()}).
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
        int latest = latestVersion(type);
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

  /**
   * Dernière version enregistrée pour {@code type} dans le registre du jeu
   * {@code TutorialHelper.ACTS} ; -1 si le type n'a aucun acte enregistré dans cet APK.
   */
  public static int latestVersion(TutorialActType type) {
    try {
      Field fActs = TutorialHelper.class.getDeclaredField("ACTS");
      fActs.setAccessible(true);
      Object acts = fActs.get(null);
      java.lang.reflect.Method get = acts.getClass().getMethod("get", Enum.class);
      IntMap<?> versions = (IntMap<?>) get.invoke(acts, type);
      int latest = -1;
      if (versions != null) {
        for (Object entry : versions) {          // IntMap implémente Iterable<Entry>
          int key = entry.getClass().getField("key").getInt(entry);
          if (key > latest) latest = key;
        }
      }
      return latest;
    } catch (Throwable t) {
      throw new RuntimeException("échec de résolution de version pour " + type, t);
    }
  }
}
