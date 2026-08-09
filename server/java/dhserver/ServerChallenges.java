package dhserver;

import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.data.misc.Unlockable;
import com.perblue.heroes.game.data.misc.Unlockables;
import com.perblue.heroes.game.data.stickerbook.ClientChallengeHandle;
import com.perblue.heroes.game.data.stickerbook.ClientUserChallengeData;
import com.perblue.heroes.game.logic.StickerHelper;
import com.perblue.heroes.game.objects.ChallengeHandle;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.ChallengeHandleExtra;
import com.perblue.heroes.network.messages.ChallengeSlots;
import com.perblue.heroes.network.messages.StickerType;
import com.perblue.heroes.network.messages.UserChallengeDataExtra;
import java.lang.reflect.Field;

/**
 * CHALLENGES (#72) — mode « Sticker Challenges » (défis idle → stickers). Cf. docs/CHALLENGES.md.
 *
 * <p><b>Modèle (§3 « lire &amp; exécuter », §4 zéro invention).</b> L'état de défis d'un joueur est un
 * {@link UserChallengeDataExtra} PERSISTÉ ({@code ServerUser.challengeData}). Toute mutation :
 * <ol>
 *   <li>charge l'état persisté en objet du jeu — {@link #toClient} = {@code ClientNetworkStateConverter
 *       .getUserChallengeData} ;</li>
 *   <li>exécute la LOGIQUE DU JEU sur cet objet ({@code StickerHelper.setupStarterChallenges} /
 *       {@code createHandleExtra} / {@code claimSticker} / {@code cancelChallenge}) — durées/récompenses/stickers
 *       viennent de {@code challenge_stickers.tab}, jamais inventés ;</li>
 *   <li>re-sérialise l'objet muté en message — {@link #toMessage} (jeu de champs FERMÉ, validé par round-trip :
 *       le jeu n'a PAS de sérialiseur inverse client→message, exactement comme le sync héros de {@code User}, §3).</li>
 * </ol>
 * L'appelant ({@code LoginServer}) persiste ({@code store.save}) après mutation.
 *
 * <p>Prérequis headless : {@code DH.app.historicWeeklyChallenges} non-null (fixture posée dans
 * {@link ServerContext#init()} — la valeur du ctor du jeu) sinon l'extension sticker du jeu NPE.
 */
public final class ServerChallenges {

  private ServerChallenges() {}

  // ---- réflexion FERMÉE : le jeu n'expose pas ces champs (aucun getter) — glue plateforme (§4), lecture seule.
  private static final Field F_NEXT_ID, F_ATTEMPT_ID, F_HANDLE_UID;
  static {
    try {
      F_NEXT_ID = ClientUserChallengeData.class.getDeclaredField("nextChallengeID"); F_NEXT_ID.setAccessible(true);
      F_ATTEMPT_ID = ClientChallengeHandle.class.getDeclaredField("attemptID"); F_ATTEMPT_ID.setAccessible(true);
      F_HANDLE_UID = ClientChallengeHandle.class.getDeclaredField("userID"); F_HANDLE_UID.setAccessible(true);
    } catch (NoSuchFieldException e) { throw new ExceptionInInitializerError(e); }
  }

  /** État de défis FRAIS et wire-sûr pour {@code userID} : conteneurs non-null, aucun slot en cours. */
  public static UserChallengeDataExtra freshData(long userID) {
    UserChallengeDataExtra d = new UserChallengeDataExtra();
    d.userID = userID;
    d.slots = new java.util.HashMap<ChallengeSlots, ChallengeHandleExtra>();
    d.completionTime = new java.util.HashMap<>();
    d.purchaseTime = new java.util.HashMap<>();
    d.completedChapters = new java.util.ArrayList<>();
    d.nextChallengeID = 0;
    return d;
  }

  /** État persisté du joueur, ou frais si absent. */
  public static UserChallengeDataExtra load(ServerUser su) {
    UserChallengeDataExtra d = su.challengeDataOrNull();
    return d != null ? d : freshData(su.userID);
  }

  /** message persisté → objet du jeu (convertisseur du jeu). */
  private static ClientUserChallengeData toClient(UserChallengeDataExtra msg) {
    return ClientNetworkStateConverter.getUserChallengeData(msg);
  }

  /**
   * objet du jeu → message persisté. Jeu de champs FERMÉ (le jeu n'a pas de sérialiseur inverse — le client ne
   * renvoie jamais tout l'état). Validé par round-trip ({@code ChallengeLoopTest}). Miroir du sync héros de §3.
   */
  static UserChallengeDataExtra toMessage(ClientUserChallengeData c) {
    try {
      UserChallengeDataExtra m = new UserChallengeDataExtra();
      m.userID = c.getUserID();
      m.nextChallengeID = F_NEXT_ID.getInt(c);
      m.completedChapters = new java.util.ArrayList<>(c.getCompletedChapters());
      m.completionTime = new java.util.HashMap<>();
      m.purchaseTime = new java.util.HashMap<>();
      for (StickerType t : StickerType.valuesCached()) {
        long ct = c.getCompletionTime(t); if (ct > 0) m.completionTime.put(t, ct);
        long pt = c.getPurchaseTime(t); if (pt > 0) m.purchaseTime.put(t, pt);
      }
      m.slots = new java.util.HashMap<ChallengeSlots, ChallengeHandleExtra>();
      for (Object o : c.allHandles()) {
        ClientChallengeHandle h = (ClientChallengeHandle) o;
        ChallengeHandleExtra e = new ChallengeHandleExtra();
        e.userID = F_HANDLE_UID.getLong(h);
        e.attemptID = F_ATTEMPT_ID.getInt(h);
        e.type = h.getType();
        e.endTime = h.getEndTime();
        e.currentProgress = h.getCurrentProgress();
        e.maxProgress = h.getMaxProgress();
        e.claimed = h.isClaimed();
        e.lastViewedProgress = h.getLastViewedProgress();
        e.data = h.getData() == null ? new java.util.HashMap<>() : new java.util.HashMap<>(h.getData());
        m.slots.put(h.getSlot(), e);
      }
      return m;
    } catch (IllegalAccessException e) { throw new RuntimeException("re-sérialisation défis", e); }
  }

  /**
   * AUTO-POPULATION par le jeu : {@code StickerHelper.setupStarterChallenges} (défi STARTER = 1er sticker de
   * catégorie STARTER non complété, choisi par le jeu depuis {@code challenge_stickers.tab}) + {@code
   * setupWeeklyChallenges} (défis hebdo actifs — vide tant qu'aucune rotation n'est poussée). GATÉ
   * {@code Unlockable.CHALLENGES} (TL 20, règle du jeu). Renvoie {@code true} si l'état a changé (à persister).
   */
  public static boolean ensureSetup(ServerUser su) {
    ServerContext.init();
    User user = su.gameUser();                                   // lie DH.app (serverTime + gate d'unlock)
    if (!Unlockables.isUnlocked(Unlockable.CHALLENGES, user)) return false;
    UserChallengeDataExtra msg = load(su);
    ClientUserChallengeData c = toClient(msg);
    int before = c.allHandles().size();
    StickerHelper.setupStarterChallenges(c);                     // code du jeu (idempotent : ne fait rien si STARTER pris)
    StickerHelper.setupWeeklyChallenges(c);                      // code du jeu (no-op tant que pas de rotation hebdo)
    if (c.allHandles().size() == before) return false;
    su.setChallengeData(toMessage(c));
    return true;
  }

  /**
   * START d'un défi sticker demandé par le joueur (Action {@code START_STICKER_CHALLENGE{TYPE, TIME}}, sans SLOT).
   * Le serveur choisit un slot VALIDE ({@code canStart}, code du jeu) parmi les libres, crée le handle
   * ({@code createHandleExtra} — durée/maxProgress depuis la data), le pose. Renvoie le slot choisi, ou {@code null}
   * si aucun slot ne peut démarrer ce sticker (sticker verrouillé / slot occupé). Ne persiste pas (appelant).
   */
  public static ChallengeSlots applyStart(ServerUser su, StickerType type) {
    ServerContext.init();
    su.gameUser();                                              // lie DH.app (serverTime pour createHandleExtra)
    UserChallengeDataExtra msg = load(su);
    if (msg.slots == null) msg.slots = new java.util.HashMap<ChallengeSlots, ChallengeHandleExtra>();
    ClientUserChallengeData c = toClient(msg);                  // pour canStart (règle du jeu, lecture seule)
    ChallengeSlots slot = null;
    for (ChallengeSlots s : SLOTS) {
      if (c.getHandle(s) != null) continue;                     // slot occupé
      try { if (StickerHelper.canStart(c, type, s)) { slot = s; break; } } catch (Throwable ignore) {}
    }
    if (slot == null) return null;
    // createHandleExtra RETOURNE un ChallengeHandleExtra (message) : endTime = serverTime()+getDuration(),
    // maxProgress = getMaxProgress(), attemptID = l'id passé (données challenge_stickers.tab, §4). On le pose au
    // niveau MESSAGE (le serveur choisit l'attemptID = nextChallengeID courant, comme il attribue les ids).
    int attemptID = msg.nextChallengeID;
    ChallengeHandleExtra he = StickerHelper.createHandleExtra(su.userID, type, attemptID);
    msg.slots.put(slot, he);
    msg.nextChallengeID = attemptID + 1;
    su.setChallengeData(msg);
    return slot;
  }

  /** Ordre de recherche de slot (canStart/slotAccepts du jeu tranchent la validité par catégorie). */
  private static final ChallengeSlots[] SLOTS = {
      ChallengeSlots.STARTER, ChallengeSlots.NORMAL_1, ChallengeSlots.NORMAL_2,
      ChallengeSlots.WEEKLY_1, ChallengeSlots.WEEKLY_2 };

  /**
   * CLAIM d'un défi COMPLÉTÉ : exécute {@code StickerHelper.claimSticker} (autoritatif — crédite sticker cosmétique
   * + {@code CHALLENGE_TOKENS} + bonus de livre au joueur, pose {@code completionTime}), puis re-sérialise. Renvoie
   * {@code true} si réclamé (sinon défi non prêt / déjà réclamé). Ne persiste pas (appelant).
   */
  public static boolean applyClaim(ServerUser su, StickerType type, ChallengeSlots slot) {
    ServerContext.init();
    User user = su.gameUser();
    UserChallengeDataExtra msg = load(su);
    ClientUserChallengeData c = toClient(msg);
    ChallengeHandle before = slot != null ? c.getHandle(slot) : StickerHelper.getHandle(c, type);
    if (before == null || !before.isReadyToClaim() || before.isClaimed()) return false;  // garde-fou (comme claimSticker)
    try {
      StickerHelper.claimSticker(user, c, su.userID, type, slot);
    } catch (Throwable t) {
      System.out.println("[challenge] claim refusé (" + type + "/" + slot + ") : " + t);
      return false;
    }
    su.setChallengeData(toMessage(c));
    su.resyncDiamonds(user);          // récompenses (tokens dans this.extra auto-persisté ; diamants champ dédié)
    return true;
  }

  /** CANCEL d'un défi en cours ({@code StickerHelper.cancelChallenge}, code du jeu). Ne persiste pas (appelant). */
  public static boolean applyCancel(ServerUser su, StickerType type, ChallengeSlots slot) {
    ServerContext.init();
    User user = su.gameUser();
    UserChallengeDataExtra msg = load(su);
    ClientUserChallengeData c = toClient(msg);
    if ((slot != null ? c.getHandle(slot) : StickerHelper.getHandle(c, type)) == null) return false;
    try {
      StickerHelper.cancelChallenge(user, c, su.userID, type, slot);
    } catch (Throwable t) {
      System.out.println("[challenge] cancel refusé (" + type + "/" + slot + ") : " + t);
      return false;
    }
    su.setChallengeData(toMessage(c));
    return true;
  }
}
