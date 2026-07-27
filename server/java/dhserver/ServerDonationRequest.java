package dhserver;

import com.perblue.heroes.game.objects.IGuildDonationRequest;
import com.perblue.heroes.network.messages.GuildDonationRequestRow;
import com.perblue.heroes.network.messages.GuildDonationRequestType;
import com.perblue.heroes.network.messages.RewardDrop;

import java.util.Map;

/**
 * DONS / GUILD AID (#55b) — adaptateur serveur d'une demande d'aide vers l'interface {@link IGuildDonationRequest}
 * du jeu, adossé à l'ÉTAT OPÉRATEUR de la guilde ({@link ServerGuild}) : la {@code GuildDonationRequestRow}
 * persistée (dons restants, expiration…) + la carte {@code userID → nombre de dons} (suivi par utilisateur, que la
 * {@code GuildDonationRequestRow} seule ne porte pas — elle n'a que {@code yourDonations}).
 *
 * <p>Sert à exécuter la logique AUTORITATIVE du jeu {@code GuildDonationHelper.doDonation(donateur, CETTE demande,
 * offre)} : elle débite le donateur, vérifie les gardes (pas soi-même, demande active, cap par utilisateur,
 * assez à donner) et mute la demande via {@code setDonationsRemaining}/{@code incDonationsByUser} — qu'on
 * répercute directement sur la row + la carte (donc persistés par {@link ServerGuild}).
 */
public final class ServerDonationRequest implements IGuildDonationRequest {

  public final GuildDonationRequestRow row;
  private final Map<Long, Integer> donationsByUser;

  public ServerDonationRequest(GuildDonationRequestRow row, Map<Long, Integer> donationsByUser) {
    this.row = row;
    this.donationsByUser = donationsByUser;
  }

  @Override public RewardDrop getDonation() { return row.donation; }
  @Override public int getDonationsByUser(long userID) {
    Integer n = donationsByUser.get(userID); return n == null ? 0 : n;
  }
  @Override public int getDonationsRemaining() { return row.remainingDonations; }
  @Override public long getExpiration() { return row.expiration; }
  @Override public GuildDonationRequestType getType() { return row.type; }
  @Override public long getUserID() { return row.member == null ? 0L : row.member.iD; }
  @Override public void incDonationsByUser(long userID) {
    donationsByUser.merge(userID, 1, Integer::sum);
  }
  @Override public void setDonationsRemaining(int n) { row.remainingDonations = n; }
}
