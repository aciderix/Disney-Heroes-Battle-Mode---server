import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * FRIENDSHIPS (#72) incrément 1 — LIVRAISON BootData. Le client rend l'écran MISSIONS depuis
 * {@code BootData.friendshipOffsetData} (config d'échelle, lue par {@code FriendshipOffsets.setOffsets} au boot —
 * NULL ⇒ NPE) + l'état du joueur dans {@code IndividualUserExtra} (friendships & co, persisté par write-through).
 * Vérifie que tous ces conteneurs sont livrés NON-NULL et wire-sûrs (défaut nº3).
 */
public final class FriendshipBootTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[friendship-boot] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(44L, 1);
    BootData bd = su.bootData();

    // friendshipOffsetData : non-null + listes non-null (setOffsets itère friendships/levelOffsets/rarityOffsets).
    FriendshipOffsetData off = bd.friendshipOffsetData;
    check(off != null, "BootData.friendshipOffsetData non-null (sinon NPE FriendshipOffsets.setOffsets au boot)");
    check(off.friendships != null && off.levelOffsets != null && off.rarityOffsets != null,
        "listes d'offsets non-null (parcourues en parallèle par setOffsets)");
    check(off.friendships.size() == off.levelOffsets.size() && off.friendships.size() == off.rarityOffsets.size(),
        "listes d'offsets de MÊME longueur (accès parallèle par index)");

    // État du joueur (IndividualUserExtra) : conteneurs d'amitié non-null (wire-sûr, écran non planté).
    IndividualUserExtra iue = bd.individualUserExtra;
    check(iue.friendships != null, "individualUserExtra.friendships non-null");
    check(iue.friendshipCampaignProgress != null, "friendshipCampaignProgress non-null");
    check(iue.friendshipMissionData != null, "friendshipMissionData non-null");
    check(iue.favoriteFriendships != null, "favoriteFriendships non-null");
    check(iue.inProgressFriendshipMissions != null, "inProgressFriendshipMissions non-null");
    check(iue.lastFriendRequestTimes != null, "lastFriendRequestTimes non-null");

    // Le client EXÉCUTE setOffsets(friendshipOffsetData) au boot : on rejoue ce chemin CLIENT headless (ne doit pas lever).
    com.perblue.heroes.game.data.friendships.FriendshipOffsets.setOffsets(off);

    // Round-trip wire (le client décode tout le BootData au boot).
    WireCheck.assertRoundTrips(off);
    WireCheck.assertRoundTrips(bd);

    System.out.println("[friendship-boot] OK — friendshipOffsetData + conteneurs IndividualUserExtra livrés non-null, "
        + "setOffsets rejoué, round-trip BootData — #72 FRIENDSHIPS incrément 1");
  }
}
