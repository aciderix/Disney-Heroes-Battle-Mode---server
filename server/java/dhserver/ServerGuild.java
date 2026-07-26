package dhserver;

import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.network.messages.BasicGuildInfo;
import com.perblue.heroes.network.messages.CreateGuild;
import com.perblue.heroes.network.messages.GuildInfo;
import com.perblue.heroes.network.messages.GuildNewMemberPolicy;
import com.perblue.heroes.network.messages.GuildPerkSnapshot;
import com.perblue.heroes.network.messages.MessageFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GUILDE (écran GUILDS, #7) — <b>état serveur-autoritatif partagé</b> d'une guilde du shard.
 *
 * <p>Comme le classement d'arène ({@link ServerArenaLadder}), une guilde est de l'<b>état opérateur</b> que le
 * backend PerBlue maintenait et qui n'existe PAS dans le jar client : on la persiste en DB (table {@code guilds},
 * une ligne par {@code (shard, guildID)}) pour qu'elle SURVIVE aux redémarrages et soit cohérente entre instances
 * serveur (PRINCIPLES §5 multi-serveur).
 *
 * <p>La <b>donnée de jeu</b> — l'objet {@link GuildInfo} du jeu — est stockée telle quelle en <b>octets wire</b>
 * (produits par la classe du jeu via {@code writeAll}, relus via {@link MessageFactory}), jamais un schéma inventé
 * pour ses champs (PRINCIPLES §4/§6). Seul le <b>roster</b> (la liste des userID membres, chef en tête) est de
 * l'état opérateur pur, écrit en format compact versionné.
 */
public final class ServerGuild {

  public long guildID;
  public int shardID;
  /** État de guilde = objet du jeu (source de vérité de nom/motto/politique/perks…). */
  public GuildInfo info;
  /** Roster : userID des membres, le CHEF (RULER) en tête. Détails résolus depuis le store à l'affichage. */
  public final List<Long> memberIDs = new ArrayList<>();

  // --- État de guilde MUTABLE (état opérateur ; persisté v2) ---
  /** CHECK-IN : userID des membres ayant émargé aujourd'hui + horodatage du dernier reset quotidien (horloge serveur). */
  public final java.util.Set<Long> checkedInToday = new java.util.LinkedHashSet<>();
  public long lastCheckInResetTime;
  /** Candidatures en attente (guildes APPLICATION_ONLY) : userID → nom. */
  public final java.util.LinkedHashMap<Long, String> applicants = new java.util.LinkedHashMap<>();

  public ServerGuild() {}

  public int checkInsToday() { return checkedInToday.size(); }

  /**
   * Crée une guilde à partir d'un {@link CreateGuild} (message du jeu) — fondateur = {@code founderID} (RULER).
   * On part d'un {@code new GuildInfo()} (tous champs non-null par le constructeur du jeu) et on ne pose que les
   * champs choisis par le fondateur ; le reste (puissance, rangs, perks) reste au défaut, recalculé à l'affichage.
   */
  public static ServerGuild create(long guildID, int shardID, long founderID, CreateGuild m) {
    ServerGuild g = new ServerGuild();
    g.guildID = guildID;
    g.shardID = shardID;
    GuildInfo gi = new GuildInfo();
    BasicGuildInfo bi = gi.basicInfo;      // instancié par le constructeur GuildInfo()
    bi.iD = guildID;
    bi.name = m.name;
    bi.avatar = m.avatar;
    gi.motto = m.motto == null ? "" : m.motto;
    gi.minTeamLevel = m.minLevel;
    gi.newMemberPolicy = m.newMemberPolicy == null ? GuildNewMemberPolicy.OPEN : m.newMemberPolicy;
    gi.country = m.country;
    gi.timeZone = m.timeZone;
    gi.autoPostAidRequests = m.autoPostAidRequests;
    gi.ignoreKickedPlayersList = m.ignoreKickedPlayersList;
    gi.tacticiansSeeOfficerChat = m.tacticiansSeeOfficerChat;
    gi.memberCount = 1;
    GuildPerkSnapshot snap = gi.perkLevels; // instancié par le constructeur GuildInfo()
    if (snap != null) snap.guildID = guildID;
    g.info = gi;
    g.memberIDs.add(founderID);
    return g;
  }

  public int memberCount() { return memberIDs.size(); }

  /** Sérialise : octets wire de {@link GuildInfo} (objet du jeu) + roster (état opérateur). */
  public byte[] toBytes() {
    try {
      GruntOutputStream gout = new GruntOutputStream();
      info.writeAll(gout);                 // format réseau exact de l'objet de jeu
      byte[] infoWire = gout.getBytes();

      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(2);                       // version (2 = + check-in + candidatures)
      o.writeLong(guildID);
      o.writeInt(shardID);
      o.writeInt(infoWire.length);
      o.write(infoWire);
      o.writeInt(memberIDs.size());
      for (Long id : memberIDs) o.writeLong(id);
      // v2 : état mutable
      o.writeLong(lastCheckInResetTime);
      o.writeInt(checkedInToday.size());
      for (Long id : checkedInToday) o.writeLong(id);
      o.writeInt(applicants.size());
      for (java.util.Map.Entry<Long, String> e : applicants.entrySet()) {
        o.writeLong(e.getKey());
        o.writeUTF(e.getValue() == null ? "" : e.getValue());
      }
      o.flush();
      return bos.toByteArray();
    } catch (Exception ex) {
      throw new RuntimeException("sérialisation guilde échouée", ex);
    }
  }

  public static ServerGuild fromBytes(byte[] b) {
    if (b == null || b.length == 0) return null;
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
      int version = in.readInt();
      ServerGuild g = new ServerGuild();
      g.guildID = in.readLong();
      g.shardID = in.readInt();
      int len = in.readInt();
      byte[] infoWire = new byte[len];
      in.readFully(infoWire);
      g.info = (GuildInfo) MessageFactory.getInstance().readMessage(new GruntInputStream(infoWire));
      int n = in.readInt();
      for (int i = 0; i < n; i++) g.memberIDs.add(in.readLong());
      if (version >= 2) {
        g.lastCheckInResetTime = in.readLong();
        int c = in.readInt();
        for (int i = 0; i < c; i++) g.checkedInToday.add(in.readLong());
        int a = in.readInt();
        for (int i = 0; i < a; i++) { long id = in.readLong(); g.applicants.put(id, in.readUTF()); }
      }
      return g;
    } catch (Exception ex) {
      throw new RuntimeException("lecture guilde échouée", ex);
    }
  }
}
