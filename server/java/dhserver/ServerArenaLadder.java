package dhserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ARÈNE #41 — <b>classement (ladder) PERSISTANT</b> d'une ligue d'arène, par {@code (shard, ArenaType)}.
 *
 * <p>C'est l'<b>état serveur-autoritatif partagé</b> que le backend PerBlue maintenait et qui n'existe PAS dans le
 * jar client : la liste ORDONNÉE des participants (le rang = la position dans la liste) avec leurs points/fights.
 * On le persiste en DB (table {@code arena_ladder}, une ligne par {@code (shard, type)}) pour qu'il SURVIVE aux
 * redémarrages et soit cohérent entre instances serveur (PRINCIPLES §5 multi-serveur). Ce n'est pas de la
 * <b>donnée de jeu</b> (rien n'est réécrit d'un {@code .tab}) mais de l'<b>état opérateur</b> — comme la liste de
 * courriers de la mailbox — donc un format d'octets compact et stable est légitime.
 *
 * <p>Les LINEUPS ne sont pas stockées ici : pour un bot elles se REGÉNÈRENT déterministiquement depuis son id
 * (graine) + rareté/niveau ; pour le joueur elles viennent de sa défense RÉELLE persistée ({@code userExtra}).
 */
public final class ServerArenaLadder {

  /** Une entrée du classement (un participant). Le RANG = l'index dans {@link #entries}. */
  public static final class Entry {
    public long id;                 // userID réel, ou id de bot (>= BOT_ID_BASE)
    public String name;
    public int teamLevel;
    public boolean bot;
    public int botRarityOrdinal;    // rareté des héros du bot (pour régénérer sa défense)
    public int botLevel;            // niveau des héros du bot
    public int points;              // points d'arène (COLISEUM) — ArenaRowExtra.points
    public long pointsTiebreaker;   // départage (ancienneté du score)
    public int remainingFightChances;
    public int bestScore;
  }

  public static final long BOT_ID_BASE = 900000L;

  private final List<Entry> entries = new ArrayList<>();

  public List<Entry> entries() { return entries; }

  /** Position (0-based) de l'id dans le classement, ou -1. */
  public int indexOf(long id) {
    for (int i = 0; i < entries.size(); i++) if (entries.get(i).id == id) return i;
    return -1;
  }

  /** Échange deux positions (mécanique de rang du fight pit : battre un mieux classé → prendre sa place). */
  public void swap(int a, int b) {
    if (a < 0 || b < 0 || a >= entries.size() || b >= entries.size() || a == b) return;
    Entry t = entries.get(a); entries.set(a, entries.get(b)); entries.set(b, t);
  }

  /** Sérialise l'état en octets (format opérateur compact, versionné). */
  public byte[] toBytes() {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(1);                       // version
      o.writeInt(entries.size());
      for (Entry e : entries) {
        o.writeLong(e.id);
        o.writeUTF(e.name == null ? "" : e.name);
        o.writeInt(e.teamLevel);
        o.writeBoolean(e.bot);
        o.writeInt(e.botRarityOrdinal);
        o.writeInt(e.botLevel);
        o.writeInt(e.points);
        o.writeLong(e.pointsTiebreaker);
        o.writeInt(e.remainingFightChances);
        o.writeInt(e.bestScore);
      }
      o.flush();
      return bos.toByteArray();
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation ladder échouée", ex);
    }
  }

  /** Relit un classement depuis des octets ({@code null}/vide → classement vide). */
  public static ServerArenaLadder fromBytes(byte[] b) {
    ServerArenaLadder l = new ServerArenaLadder();
    if (b == null || b.length == 0) return l;
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
      in.readInt();                        // version (1)
      int n = in.readInt();
      for (int i = 0; i < n; i++) {
        Entry e = new Entry();
        e.id = in.readLong();
        e.name = in.readUTF();
        e.teamLevel = in.readInt();
        e.bot = in.readBoolean();
        e.botRarityOrdinal = in.readInt();
        e.botLevel = in.readInt();
        e.points = in.readInt();
        e.pointsTiebreaker = in.readLong();
        e.remainingFightChances = in.readInt();
        e.bestScore = in.readInt();
        l.entries.add(e);
      }
    } catch (Exception ex) {
      throw new RuntimeException("lecture ladder échouée", ex);
    }
    return l;
  }
}
