package dhserver;

import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.RewardDrop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SAISON DE CONTEST (#67) — état OPÉRATEUR au niveau du SHARD (aucune guilde ne la possède), persisté dans
 * {@code shard_state} sous la clé {@code "contest"}.
 *
 * <p>Le jeu d'origine tirait la programmation des contests du back-office live-ops de PerBlue : le jar client
 * ne contient QUE la façade (page de perk {@code GUILD_CONTESTS}, ressource {@code GUILD_CONTEST_POINTS},
 * messages de classement) — jamais le calendrier ni les récompenses. Sur ce serveur communautaire, c'est donc
 * l'ADMIN qui programme la saison (décision produit du 2026-07-28) : fenêtre, nom, et récompenses par PALIER DE
 * RANG. Les récompenses sont des {@link RewardDrop} du jeu (format wire), jamais un schéma inventé.
 *
 * <p>À la clôture (échéance atteinte, ou {@code --contest-end}), le serveur distribue les récompenses aux
 * membres des guildes selon le rang final de leur guilde (classement par {@code GuildInfo.contestPoints}),
 * par COURRIER — le même canal autoritatif que les dons et le panneau admin.
 */
public final class ServerContest {

  /** Un palier : les rangs {@code [minRank..maxRank]} (1 = premier) reçoivent {@code rewards}. */
  public static final class Tier {
    public int minRank, maxRank;
    public final List<RewardDrop> rewards = new ArrayList<>();
    public Tier() {}
    public Tier(int minRank, int maxRank) { this.minRank = minRank; this.maxRank = maxRank; }
    public boolean covers(int rank) { return rank >= minRank && rank <= maxRank; }
  }

  public String name = "";
  /** Fenêtre d'activité (horloge serveur, ms). */
  public long startTime, endTime;
  /** Récompenses déjà distribuées ? (idempotence : on ne paie jamais deux fois). */
  public boolean paidOut;
  public final List<Tier> tiers = new ArrayList<>();

  public boolean isActive(long now) { return now >= startTime && now < endTime; }
  public boolean isOver(long now) { return now >= endTime; }

  /** Le palier qui couvre {@code rank}, ou {@code null}. */
  public Tier tierFor(int rank) {
    for (Tier t : tiers) if (t.covers(rank)) return t;
    return null;
  }

  public byte[] toBytes() {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(1);                       // version
      o.writeUTF(name == null ? "" : name);
      o.writeLong(startTime);
      o.writeLong(endTime);
      o.writeBoolean(paidOut);
      o.writeInt(tiers.size());
      for (Tier t : tiers) {
        o.writeInt(t.minRank);
        o.writeInt(t.maxRank);
        o.writeInt(t.rewards.size());
        for (RewardDrop r : t.rewards) {
          GruntOutputStream go = new GruntOutputStream();
          r.writeAll(go);
          byte[] w = go.getBytes();
          o.writeInt(w.length);
          o.write(w);
        }
      }
      o.flush();
      return bos.toByteArray();
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation contest échouée", ex);
    }
  }

  public static ServerContest fromBytes(byte[] b) {
    if (b == null || b.length == 0) return null;
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
      in.readInt();                        // version
      ServerContest c = new ServerContest();
      c.name = in.readUTF();
      c.startTime = in.readLong();
      c.endTime = in.readLong();
      c.paidOut = in.readBoolean();
      int nt = in.readInt();
      for (int i = 0; i < nt; i++) {
        Tier t = new Tier(in.readInt(), in.readInt());
        int nr = in.readInt();
        for (int j = 0; j < nr; j++) {
          byte[] w = new byte[in.readInt()];
          in.readFully(w);
          t.rewards.add((RewardDrop) MessageFactory.getInstance().readMessage(new GruntInputStream(w)));
        }
        c.tiers.add(t);
      }
      return c;
    } catch (Exception ex) {
      throw new RuntimeException("lecture contest échouée", ex);
    }
  }

  /** Résumé lisible pour le panneau admin. */
  public String describe(long now) {
    StringBuilder sb = new StringBuilder();
    sb.append("« ").append(name).append(" » ");
    if (isActive(now)) sb.append("ACTIVE");
    else if (now < startTime) sb.append("À VENIR");
    else sb.append(paidOut ? "TERMINÉE (payée)" : "TERMINÉE (paiement en attente)");
    sb.append(" ; du ").append(new java.util.Date(startTime))
      .append(" au ").append(new java.util.Date(endTime))
      .append(" ; ").append(tiers.size()).append(" palier(s)");
    return sb.toString();
  }
}
