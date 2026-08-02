package dhserver;

import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarInfo;
import com.perblue.heroes.network.messages.WarSummary;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * GUILD WAR (#68) — une GUERRE : l'appariement de DEUX guildes sur une fenêtre de temps.
 *
 * <p>C'est de l'<b>état opérateur partagé</b> (le backend PerBlue le tenait ; le jar client ne sait que
 * l'afficher), donc persisté en base par {@code (shard, warID)} comme le ladder d'arène ou les guildes —
 * PRINCIPLES §5.
 *
 * <p><b>Forme du stockage</b> (PRINCIPLES §4/§6) : les deux côtés sont des {@link WarGuildInfo}, objets DU
 * JEU, rangés tels quels en <b>octets wire</b> ; on n'invente aucun schéma pour leurs champs (voitures,
 * membres, bans/protections, cooldowns, points, attaques supplémentaires…). Seuls les scalaires de
 * l'appariement (identifiants, fenêtre, phase) sont de l'état opérateur pur, en format compact versionné.
 *
 * <p><b>Pourquoi ce n'est PAS un {@link WarInfo} stocké tel quel</b> : {@code WarInfo} est
 * <i>relatif au spectateur</i> ({@code yourGuild} / {@code enemyGuild}). L'état canonique est donc symétrique
 * — côté A et côté B — et {@link #toWarInfo(long)} produit la vue du demandeur en plaçant le bon côté dans
 * {@code yourGuild}. Un seul état, deux lectures : aucune divergence possible entre les deux guildes.
 */
public final class ServerWarState {

  /** Version du format compact (état opérateur). v2 = journaux d'attaques ; v3 = frais de sabotage. */
  private static final int VERSION = 3;

  public long warID;
  public int shardID;
  public int seasonID;

  /** Fenêtre de la guerre (horloge serveur). */
  public long startTime, endTime;
  /** Phase courante et son échéance ({@code stateEndTime}), plus l'échéance de la sous-phase
   *  ({@code extraStateEndTime} = fin de la fenêtre de BAN à l'intérieur de la phase de sabotage). */
  public WarSummaryState state = WarSummaryState.NOT_QUEUED;
  public long stateEndTime, extraStateEndTime;

  /** Les deux camps. {@code guildBID == 0} ⇒ guerre à vide (BYE) : aucun adversaire apparié. */
  public long guildAID, guildBID;
  /** Octets wire des deux {@link WarGuildInfo} (objets du jeu). */
  public byte[] guildAWire, guildBWire;

  /**
   * v2 — JOURNAUX D'ATTAQUES, une liste par camp : les attaques MENÉES par A, celles menées par B.
   * Chaque entrée est un {@link com.perblue.heroes.network.messages.WarLogAttack} — objet DU JEU en octets
   * wire (attaquant, défenseur, salle visée, modificateurs de combat des deux côtés, vagues, attaque
   * supplémentaire ou non). C'est la matière première de {@code RequestWarLogs} ET du calcul de score :
   * balayages parfaits et défenses victorieuses s'en déduisent, on ne tient donc aucun compteur en double.
   */
  public final java.util.List<byte[]> attacksAWire = new java.util.ArrayList<>();
  public final java.util.List<byte[]> attacksBWire = new java.util.ArrayList<>();

  /**
   * v3 — FRAIS DE SABOTAGE payés par chaque joueur, par camp ({@code userID → total dépensé}).
   *
   * <p>« Tokens spent are refunded if you lose the War » (aide du jeu) : le remboursement doit revenir à
   * CELUI qui a payé, et le prix monte à chaque sabotage sur la même cible — un simple compteur de
   * sabotages ne suffirait donc pas à retrouver la somme. On enregistre le montant réellement débité.
   */
  public final java.util.LinkedHashMap<Long, Integer> sabotageFeesA = new java.util.LinkedHashMap<>();
  public final java.util.LinkedHashMap<Long, Integer> sabotageFeesB = new java.util.LinkedHashMap<>();

  /** Frais de sabotage du camp de {@code guildID} ({@code null} si la guilde n'est pas dans la guerre). */
  public java.util.LinkedHashMap<Long, Integer> sabotageFeesOf(long guildID) {
    if (guildID == guildAID) return sabotageFeesA;
    if (guildID == guildBID) return sabotageFeesB;
    return null;
  }

  /** Enregistre un débit de sabotage, pour un éventuel remboursement en cas de défaite. */
  public void addSabotageFee(long guildID, long userID, int cost) {
    java.util.LinkedHashMap<Long, Integer> fees = sabotageFeesOf(guildID);
    if (fees == null) throw new IllegalArgumentException("guilde " + guildID + " hors de la guerre " + warID);
    fees.merge(userID, cost, Integer::sum);
  }

  /** Total dépensé en sabotages par ce camp. */
  public int totalSabotageFees(long guildID) {
    java.util.LinkedHashMap<Long, Integer> fees = sabotageFeesOf(guildID);
    int total = 0;
    if (fees != null) for (Integer v : fees.values()) total += v;
    return total;
  }

  /** Journal des attaques MENÉES par {@code guildID} ({@code null} si la guilde n'est pas dans la guerre). */
  public java.util.List<byte[]> attackLogOf(long guildID) {
    if (guildID == guildAID) return attacksAWire;
    if (guildID == guildBID) return attacksBWire;
    return null;
  }

  /** Attaques menées par {@code guildID}, relues en objets du jeu (les illisibles sont écartées). */
  public java.util.List<com.perblue.heroes.network.messages.WarLogAttack> attacksBy(long guildID) {
    java.util.List<com.perblue.heroes.network.messages.WarLogAttack> out = new java.util.ArrayList<>();
    java.util.List<byte[]> raw = attackLogOf(guildID);
    if (raw == null) return out;
    java.util.Iterator<byte[]> it = raw.iterator();
    while (it.hasNext()) {
      try {
        out.add((com.perblue.heroes.network.messages.WarLogAttack)
            MessageFactory.getInstance().readMessage(new GruntInputStream(it.next())));
      } catch (Exception ignore) { it.remove(); }
    }
    return out;
  }

  /** Attaques SUBIES par {@code guildID} = celles menées par l'adversaire. */
  public java.util.List<com.perblue.heroes.network.messages.WarLogAttack> attacksAgainst(long guildID) {
    long other = opponentOf(guildID);
    return other > 0 ? attacksBy(other) : new java.util.ArrayList<>();
  }

  /** Ajoute une attaque au journal de {@code guildID}. */
  public void addAttack(long guildID, com.perblue.heroes.network.messages.WarLogAttack log) {
    java.util.List<byte[]> raw = attackLogOf(guildID);
    if (raw == null) throw new IllegalArgumentException("guilde " + guildID + " hors de la guerre " + warID);
    try {
      GruntOutputStream out = new GruntOutputStream();
      log.writeAll(out);
      raw.add(out.getBytes());
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation WarLogAttack échouée", ex);
    }
  }

  public ServerWarState() {}

  /** Une guerre est-elle un BYE (aucun adversaire apparié) ? */
  public boolean isBye() { return guildBID <= 0; }

  /** Cette guerre concerne-t-elle {@code guildID} ? */
  public boolean involves(long guildID) { return guildID == guildAID || guildID == guildBID; }

  /** L'identifiant de l'adversaire de {@code guildID} ({@code 0} si BYE ou guilde étrangère). */
  public long opponentOf(long guildID) {
    if (guildID == guildAID) return guildBID;
    if (guildID == guildBID) return guildAID;
    return 0L;
  }

  private static WarGuildInfo read(byte[] wire) {
    if (wire == null || wire.length == 0) return new WarGuildInfo();
    try {
      return (WarGuildInfo) MessageFactory.getInstance().readMessage(new GruntInputStream(wire));
    } catch (Exception ex) {
      return new WarGuildInfo();
    }
  }

  private static byte[] write(WarGuildInfo info) {
    try {
      GruntOutputStream out = new GruntOutputStream();
      (info == null ? new WarGuildInfo() : info).writeAll(out);
      return out.getBytes();
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation WarGuildInfo échouée", ex);
    }
  }

  /**
   * ⚠️ <b>Sémantique d'INSTANTANÉ — à connaître.</b> {@link #sideOf} DÉCODE les octets et rend un objet
   * NEUF ; {@link #putSide} RE-ENCODE et fige l'état à cet instant. Muter l'objet rendu par {@code sideOf}
   * ne modifie donc RIEN tant qu'on n'a pas rappelé {@code putSide}. C'est volontaire (l'état canonique
   * reste les octets wire, PRINCIPLES §6) mais c'est un piège : oublier le {@code putSide} final perd la
   * mutation SANS erreur. Le motif correct est toujours
   * « {@code side = sideOf(id)} → muter → {@code putSide(id, side)} ».
   */
  /** Le côté de {@code guildID}, relu en objet du jeu ({@code null} si la guilde n'est pas dans la guerre). */
  public WarGuildInfo sideOf(long guildID) {
    if (guildID == guildAID) return read(guildAWire);
    if (guildID == guildBID) return read(guildBWire);
    return null;
  }

  /** Le côté ADVERSE de {@code guildID} (vide si BYE). */
  public WarGuildInfo enemySideOf(long guildID) {
    if (guildID == guildAID) return read(guildBWire);
    if (guildID == guildBID) return read(guildAWire);
    return new WarGuildInfo();
  }

  /** Réécrit le côté de {@code guildID} après mutation. */
  public void putSide(long guildID, WarGuildInfo info) {
    if (guildID == guildAID) guildAWire = write(info);
    else if (guildID == guildBID) guildBWire = write(info);
    else throw new IllegalArgumentException("guilde " + guildID + " hors de la guerre " + warID);
  }

  /**
   * Vue de la guerre POUR {@code guildID} : c'est le message {@link WarInfo} que le client attend, avec son
   * propre camp dans {@code yourGuild}. Deux guildes lisent le MÊME état et obtiennent deux vues cohérentes.
   */
  public WarInfo toWarInfo(long guildID) {
    WarInfo w = new WarInfo();
    w.warID = warID;
    w.seasonID = seasonID;
    w.startTime = startTime;
    w.endTime = endTime;
    w.state = state;
    w.stateEndTime = stateEndTime;
    w.extraStateEndTime = extraStateEndTime;
    WarGuildInfo mine = sideOf(guildID);
    w.yourGuild = mine != null ? mine : new WarGuildInfo();
    w.enemyGuild = enemySideOf(guildID);
    return w;
  }

  /** Résumé de la guerre POUR {@code guildID} (élément de {@code WarsList} / {@code WarMoments}). */
  public WarSummary toSummary(long guildID) {
    WarSummary s = new WarSummary();
    s.warID = warID;
    s.startTime = startTime;
    s.endTime = endTime;
    s.state = state;
    s.stateEndTime = stateEndTime;
    s.extraStateEndTime = extraStateEndTime;
    WarGuildInfo mine = sideOf(guildID);
    WarGuildInfo theirs = enemySideOf(guildID);
    s.enemyGuild = theirs.guildInfo;
    s.yourMmrDelta = mine != null ? mine.mmrDelta : 0;
    s.enemyMmrDelta = theirs.mmrDelta;
    return s;
  }

  // ---------------------------------------------------------------------------------------------
  // Persistance
  // ---------------------------------------------------------------------------------------------

  public byte[] toBytes() {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(VERSION);
      o.writeLong(warID);
      o.writeInt(shardID);
      o.writeInt(seasonID);
      o.writeLong(startTime);
      o.writeLong(endTime);
      o.writeUTF(state == null ? WarSummaryState.DEFAULT.name() : state.name());
      o.writeLong(stateEndTime);
      o.writeLong(extraStateEndTime);
      o.writeLong(guildAID);
      o.writeLong(guildBID);
      byte[] a = guildAWire == null ? write(new WarGuildInfo()) : guildAWire;
      byte[] b = guildBWire == null ? write(new WarGuildInfo()) : guildBWire;
      o.writeInt(a.length); o.write(a);
      o.writeInt(b.length); o.write(b);
      // v2 : journaux d'attaques (octets wire des WarLogAttack du jeu)
      o.writeInt(attacksAWire.size());
      for (byte[] w : attacksAWire) { o.writeInt(w.length); o.write(w); }
      o.writeInt(attacksBWire.size());
      for (byte[] w : attacksBWire) { o.writeInt(w.length); o.write(w); }
      // v3 : frais de sabotage par joueur, par camp
      o.writeInt(sabotageFeesA.size());
      for (java.util.Map.Entry<Long, Integer> e : sabotageFeesA.entrySet()) {
        o.writeLong(e.getKey()); o.writeInt(e.getValue());
      }
      o.writeInt(sabotageFeesB.size());
      for (java.util.Map.Entry<Long, Integer> e : sabotageFeesB.entrySet()) {
        o.writeLong(e.getKey()); o.writeInt(e.getValue());
      }
      o.flush();
      return bos.toByteArray();
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation de guerre échouée", ex);
    }
  }

  public static ServerWarState fromBytes(byte[] raw) {
    if (raw == null || raw.length == 0) return null;
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
      int version = in.readInt();
      ServerWarState s = new ServerWarState();
      s.warID = in.readLong();
      s.shardID = in.readInt();
      s.seasonID = in.readInt();
      s.startTime = in.readLong();
      s.endTime = in.readLong();
      String st = in.readUTF();
      try { s.state = WarSummaryState.valueOf(st); } catch (Exception e) { s.state = WarSummaryState.DEFAULT; }
      s.stateEndTime = in.readLong();
      s.extraStateEndTime = in.readLong();
      s.guildAID = in.readLong();
      s.guildBID = in.readLong();
      s.guildAWire = new byte[in.readInt()]; in.readFully(s.guildAWire);
      s.guildBWire = new byte[in.readInt()]; in.readFully(s.guildBWire);
      if (version >= 2) {
        int na = in.readInt();
        for (int i = 0; i < na; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); s.attacksAWire.add(w); }
        int nb = in.readInt();
        for (int i = 0; i < nb; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); s.attacksBWire.add(w); }
      }
      if (version >= 3) {
        int fa = in.readInt();
        for (int i = 0; i < fa; i++) { long uid = in.readLong(); s.sabotageFeesA.put(uid, in.readInt()); }
        int fb = in.readInt();
        for (int i = 0; i < fb; i++) { long uid = in.readLong(); s.sabotageFeesB.put(uid, in.readInt()); }
      }
      return s;
    } catch (Exception ex) {
      throw new RuntimeException("lecture de guerre échouée", ex);
    }
  }
}
