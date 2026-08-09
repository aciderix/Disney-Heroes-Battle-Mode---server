package dhserver;

import com.perblue.heroes.network.messages.ChallengeSlots;
import com.perblue.heroes.network.messages.ChallengeHandleExtra;
import com.perblue.heroes.network.messages.UserChallengeDataExtra;

/**
 * CHALLENGES (#72) — mode « Sticker Challenges » (défis idle → stickers). Cf. docs/CHALLENGES.md.
 *
 * <p>Incrément 1 — LIVRAISON BootData : le client lit {@code BootData.userChallengeDataExtra} au boot pour peupler
 * son {@code ClientUserChallengeData} (l'écran CHALLENGES rend depuis lui + le contenu data-driven
 * {@code challenge_*.tab}). {@code new UserChallengeDataExtra()} initialise déjà les conteneurs non-null vides, mais
 * avec {@code userID=0} — or l'écran lit {@code userChallengeDataExtra.userID}. On construit donc l'état PAR JOUEUR
 * (userID correct, conteneurs wire-sûrs). La progression (slots/completedChapters) sera persistée à l'incrément 2
 * (boucle START/CLAIM) ; à ce stade, état frais = aucun défi en cours (rendu correct pour tout compte).
 */
public final class ServerChallenges {

  private ServerChallenges() {}

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
}
