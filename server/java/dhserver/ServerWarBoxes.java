package dhserver;

import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.RewardDrop;
import com.perblue.heroes.network.messages.WarBoxInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GUILD WAR (#68) — BOÎTES EN ATTENTE d'un joueur.
 *
 * <p>Les boîtes sont gagnées par la GUILDE (promotion de ligue, fin de saison) mais **se réclament par
 * JOUEUR** : {@code WarsList.unopenedBoxes} et {@code WarSeasonsList.unopenedBoxes} voyagent dans des
 * messages adressés à un joueur, et {@code CLAIM_WAR_BOX_REWARD{ID, INDEX}} désigne une boîte et l'option
 * choisie. On les persiste donc par {@code (shard, userID)}, comme l'état d'invasion.
 *
 * <p>Chaque boîte est un {@link WarBoxInfo} — objet DU JEU — rangé en <b>octets wire</b> (PRINCIPLES §4/§6).
 * On ne garde à côté que la liste des identifiants déjà réclamés, qui est de l'état opérateur pur.
 */
public final class ServerWarBoxes {

  private static final int VERSION = 1;

  /** Boîtes non encore réclamées (octets wire de {@link WarBoxInfo}). */
  public final List<byte[]> boxesWire = new ArrayList<>();
  /** Prochain identifiant de boîte pour ce joueur. */
  public long nextBoxID = 1L;

  /** Les boîtes en attente, relues en objets du jeu (les illisibles sont écartées). */
  public List<WarBoxInfo> boxes() {
    List<WarBoxInfo> out = new ArrayList<>();
    java.util.Iterator<byte[]> it = boxesWire.iterator();
    while (it.hasNext()) {
      try {
        out.add((WarBoxInfo) MessageFactory.getInstance().readMessage(new GruntInputStream(it.next())));
      } catch (Exception ignore) { it.remove(); }
    }
    return out;
  }

  /** Ajoute une boîte, en lui attribuant l'identifiant suivant de ce joueur. */
  public void add(WarBoxInfo box) {
    box.iD = nextBoxID++;
    try {
      GruntOutputStream out = new GruntOutputStream();
      box.writeAll(out);
      boxesWire.add(out.getBytes());
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation WarBoxInfo échouée", ex);
    }
  }

  /** La boîte {@code boxID}, ou {@code null}. */
  public WarBoxInfo find(long boxID) {
    for (WarBoxInfo b : boxes()) if (b.iD == boxID) return b;
    return null;
  }

  /**
   * RÉCLAME une boîte : la retire du lot et rend l'option choisie.
   *
   * <p>Retirer la boîte EST l'anti-double-réclamation : une boîte réclamée n'existe plus, donc rejouer le
   * même {@code CLAIM_WAR_BOX_REWARD} ne rend rien. Inutile de tenir un registre d'identifiants réclamés.
   *
   * @param optionIndex l'option retenue par le joueur
   * @return la récompense, ou {@code null} si la boîte ou l'option n'existe pas
   */
  public RewardDrop claim(long boxID, int optionIndex) {
    for (int i = 0; i < boxesWire.size(); i++) {
      WarBoxInfo b;
      try {
        b = (WarBoxInfo) MessageFactory.getInstance().readMessage(new GruntInputStream(boxesWire.get(i)));
      } catch (Exception ignore) { continue; }
      if (b.iD != boxID) continue;
      if (optionIndex < 0 || optionIndex >= b.rewardOptions.size()) return null;
      RewardDrop chosen = (RewardDrop) b.rewardOptions.get(optionIndex);
      boxesWire.remove(i);
      return chosen;
    }
    return null;
  }

  public int size() { return boxesWire.size(); }

  public byte[] toBytes() {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(VERSION);
      o.writeLong(nextBoxID);
      o.writeInt(boxesWire.size());
      for (byte[] w : boxesWire) { o.writeInt(w.length); o.write(w); }
      o.flush();
      return bos.toByteArray();
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation des boîtes de guerre échouée", ex);
    }
  }

  public static ServerWarBoxes fromBytes(byte[] raw) {
    ServerWarBoxes b = new ServerWarBoxes();
    if (raw == null || raw.length == 0) return b;
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
      in.readInt();                       // version
      b.nextBoxID = in.readLong();
      int n = in.readInt();
      for (int i = 0; i < n; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); b.boxesWire.add(w); }
      return b;
    } catch (Exception ex) {
      throw new RuntimeException("lecture des boîtes de guerre échouée", ex);
    }
  }
}
