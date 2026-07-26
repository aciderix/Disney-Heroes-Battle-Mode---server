package dhserver;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Persistance SQLite de l'état joueur (docs/PRINCIPLES.md §6 : persistance complète et fidèle).
 *
 * <p>On stocke l'état comme des <b>BLOB d'octets wire</b> produits par les <b>classes du jeu</b>
 * ({@link ServerUser#userInfoWire()} etc.) — jamais un schéma inventé pour les données du jeu.
 * Un objet du jeu = une colonne BLOB ; ajouter un champ de jeu persisté = ajouter un BLOB, sans
 * recopier/retranscrire la moindre valeur. Les octets sont exactement ceux du réseau.
 *
 * <p>Clé = {@code (userID, shardID)}. Un seul compte pour l'instant, mais le schéma est multi-joueur.
 */
public final class UserStore implements AutoCloseable {

  private final Connection conn;

  public UserStore(String dbPath) throws SQLException {
    // sqlite-jdbc s'enregistre tout seul (Class.forName inutile depuis JDBC 4).
    conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    try (Statement s = conn.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("CREATE TABLE IF NOT EXISTS users ("
          + "userID INTEGER NOT NULL, shardID INTEGER NOT NULL, "
          + "userInfo BLOB NOT NULL, userExtra BLOB NOT NULL, individualUserExtra BLOB NOT NULL, "
          + "updatedAt INTEGER NOT NULL, PRIMARY KEY (userID, shardID))");
      // Migration : colonne battlePassV2Data (BLOB nullable) ajoutée après coup → ALTER si absente. Les lignes
      // pré-migration ont NULL (état battle pass recréé au prochain boot : saison/premium, progress 0). Un
      // objet du jeu = une colonne BLOB (§ doc ci-dessus), sans schéma inventé.
      if (!columnExists(s, "users", "battlePassV2Data"))
        s.execute("ALTER TABLE users ADD COLUMN battlePassV2Data BLOB");
      // Migration : colonne mail (BLOB nullable = liste de MailMessage sérialisée). NULL = mailbox vide.
      if (!columnExists(s, "users", "mail"))
        s.execute("ALTER TABLE users ADD COLUMN mail BLOB");
      // ARÈNE #41 : classement (ladder) PERSISTANT par (shard, type). État opérateur partagé (pas de la donnée
      // de jeu), une ligne par ligue → survit aux redémarrages + cohérent multi-serveur (PRINCIPLES §5).
      s.execute("CREATE TABLE IF NOT EXISTS arena_ladder ("
          + "shardID INTEGER NOT NULL, arenaType TEXT NOT NULL, ladder BLOB NOT NULL, "
          + "updatedAt INTEGER NOT NULL, PRIMARY KEY (shardID, arenaType))");
      // GUILDES #7 : une guilde par (shard, guildID). État opérateur partagé (comme arena_ladder) → survit aux
      // redémarrages + cohérent multi-serveur (PRINCIPLES §5). BLOB = octets wire du GuildInfo du jeu + roster.
      s.execute("CREATE TABLE IF NOT EXISTS guilds ("
          + "shardID INTEGER NOT NULL, guildID INTEGER NOT NULL, guild BLOB NOT NULL, "
          + "name TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY (shardID, guildID))");
    }
  }

  /** GUILDES #7 — charge la guilde {@code (shard, guildID)}, ou {@code null} si absente. */
  public synchronized ServerGuild loadGuild(int shardID, long guildID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT guild FROM guilds WHERE shardID=? AND guildID=?")) {
      ps.setInt(1, shardID);
      ps.setLong(2, guildID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return ServerGuild.fromBytes(rs.getBytes(1));
      }
    }
    return null;
  }

  /** GUILDES #7 — écrit (upsert) la guilde ; {@code name} dupliqué en colonne pour la recherche par nom. */
  public synchronized void saveGuild(ServerGuild g) throws SQLException {
    String name = g.info != null && g.info.basicInfo != null ? g.info.basicInfo.name : null;
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO guilds (shardID, guildID, guild, name, updatedAt) VALUES (?,?,?,?,?) "
        + "ON CONFLICT(shardID, guildID) DO UPDATE SET guild=excluded.guild, name=excluded.name, "
        + "updatedAt=excluded.updatedAt")) {
      ps.setInt(1, g.shardID);
      ps.setLong(2, g.guildID);
      ps.setBytes(3, g.toBytes());
      ps.setString(4, name);
      ps.setLong(5, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  /** GUILDES #7 — supprime une guilde (dissolution : dernier membre parti). */
  public synchronized void deleteGuild(int shardID, long guildID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "DELETE FROM guilds WHERE shardID=? AND guildID=?")) {
      ps.setInt(1, shardID);
      ps.setLong(2, guildID);
      ps.executeUpdate();
    }
  }

  /** GUILDES #7 — liste les guildes du shard (recommandations / recherche). {@code nameLike}=null → toutes. */
  public synchronized java.util.List<ServerGuild> listGuilds(int shardID, String nameLike, int limit)
      throws SQLException {
    java.util.List<ServerGuild> out = new java.util.ArrayList<>();
    String sql = "SELECT guild FROM guilds WHERE shardID=?"
        + (nameLike != null ? " AND name LIKE ? COLLATE NOCASE" : "")
        + " ORDER BY updatedAt DESC LIMIT ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int i = 1;
      ps.setInt(i++, shardID);
      if (nameLike != null) ps.setString(i++, "%" + nameLike + "%");
      ps.setInt(i, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ServerGuild g = ServerGuild.fromBytes(rs.getBytes(1));
          if (g != null) out.add(g);
        }
      }
    }
    return out;
  }

  /** GUILDES #7 — prochain guildID libre du shard (max+1, base 1). guildID>0 = « en guilde » côté client. */
  public synchronized long nextGuildID(int shardID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT COALESCE(MAX(guildID),0)+1 FROM guilds WHERE shardID=?")) {
      ps.setInt(1, shardID);
      try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
    }
    return 1L;
  }

  /** ARÈNE #41 — charge le classement persisté de {@code (shard, type)}, ou {@code null} s'il n'existe pas encore. */
  public synchronized ServerArenaLadder loadArenaLadder(int shardID, String arenaType) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ladder FROM arena_ladder WHERE shardID=? AND arenaType=?")) {
      ps.setInt(1, shardID);
      ps.setString(2, arenaType);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return ServerArenaLadder.fromBytes(rs.getBytes(1));
      }
    }
    return null;
  }

  /** ARÈNE #41 — écrit (upsert) le classement de {@code (shard, type)}. */
  public synchronized void saveArenaLadder(int shardID, String arenaType, ServerArenaLadder ladder)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO arena_ladder (shardID, arenaType, ladder, updatedAt) VALUES (?,?,?,?) "
        + "ON CONFLICT(shardID, arenaType) DO UPDATE SET ladder=excluded.ladder, updatedAt=excluded.updatedAt")) {
      ps.setInt(1, shardID);
      ps.setString(2, arenaType);
      ps.setBytes(3, ladder.toBytes());
      ps.setLong(4, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  private static boolean columnExists(Statement s, String table, String col) throws SQLException {
    try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) if (col.equalsIgnoreCase(rs.getString("name"))) return true;
    }
    return false;
  }

  /** ARÈNE (vrai PvP) — IDs des AUTRES joueurs d'un shard (matchmaking sur de vrais comptes). Exclut {@code excludeID}. */
  public synchronized java.util.List<Long> listUserIDs(int shardID, long excludeID) throws SQLException {
    java.util.List<Long> out = new java.util.ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT userID FROM users WHERE shardID=? AND userID<>? ORDER BY userID")) {
      ps.setInt(1, shardID);
      ps.setLong(2, excludeID);
      try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getLong(1)); }
    }
    return out;
  }

  /** Charge le joueur (userID,shardID) s'il EXISTE, sinon {@code null} (ne crée RIEN — pour lire un adversaire). */
  public synchronized ServerUser loadIfExists(long userID, int shardID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT userInfo, userExtra, individualUserExtra, battlePassV2Data, mail FROM users WHERE userID=? AND shardID=?")) {
      ps.setLong(1, userID);
      ps.setInt(2, shardID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          ServerUser su = ServerUser.fromWire(userID, shardID, rs.getBytes(1), rs.getBytes(2), rs.getBytes(3));
          su.setBattlePassWire(rs.getBytes(4));
          su.setMailWire(rs.getBytes(5));
          return su;
        }
      }
    }
    return null;
  }

  /** Charge le joueur (userID,shardID) s'il existe, sinon en crée un NOUVEAU et le persiste. */
  public synchronized ServerUser loadOrCreate(long userID, int shardID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT userInfo, userExtra, individualUserExtra, battlePassV2Data, mail FROM users WHERE userID=? AND shardID=?")) {
      ps.setLong(1, userID);
      ps.setInt(2, shardID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          ServerUser su = ServerUser.fromWire(userID, shardID,
              rs.getBytes(1), rs.getBytes(2), rs.getBytes(3));
          su.setBattlePassWire(rs.getBytes(4));   // état battle pass persisté (NULL si pré-migration)
          su.setMailWire(rs.getBytes(5));         // mailbox persistée (NULL si vide / pré-migration)
          return su;
        }
      }
    }
    ServerUser fresh = ServerUser.newPlayer(userID, shardID);
    save(fresh);
    return fresh;
  }

  /**
   * Écrit (upsert) l'état courant du joueur en octets wire.
   * <p><b>Ordre des verrous</b> : on sérialise les octets wire (qui verrouillent le {@link ServerUser}) AVANT de
   * prendre le verrou du store — jamais {@code store→user} pendant que d'autres chemins font {@code user→store}
   * (ex. {@code startArenaAttack} synchronisé sur l'user qui charge un adversaire via {@code loadIfExists}). Sans ça,
   * inversion d'ordre = INTERBLOCAGE (observé en jeu sur le vrai PvP). La méthode n'est donc PAS {@code synchronized} :
   * seule l'écriture DB l'est.
   */
  public void save(ServerUser u) throws SQLException {
    // 1) sérialisation (verrouille l'user), HORS du verrou du store
    long id = u.userID; int shard = u.shardID;
    byte[] uiW = u.userInfoWire(), ueW = u.userExtraWire(), iuW = u.individualWire();
    byte[] bpW = u.battlePassWire(), mlW = u.mailWire();
    long now = System.currentTimeMillis();
    // 2) écriture DB, sous le verrou du store uniquement
    synchronized (this) {
      try (PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO users (userID, shardID, userInfo, userExtra, individualUserExtra, battlePassV2Data, mail, updatedAt) "
          + "VALUES (?,?,?,?,?,?,?,?) "
          + "ON CONFLICT(userID, shardID) DO UPDATE SET "
          + "userInfo=excluded.userInfo, userExtra=excluded.userExtra, "
          + "individualUserExtra=excluded.individualUserExtra, battlePassV2Data=excluded.battlePassV2Data, "
          + "mail=excluded.mail, updatedAt=excluded.updatedAt")) {
        ps.setLong(1, id);
        ps.setInt(2, shard);
        ps.setBytes(3, uiW);
        ps.setBytes(4, ueW);
        ps.setBytes(5, iuW);
        ps.setBytes(6, bpW);          // NULL si battle pass non initialisé (recréé au boot)
        ps.setBytes(7, mlW);          // NULL si mailbox vide
        ps.setLong(8, now);
        ps.executeUpdate();
      }
    }
  }

  @Override public synchronized void close() throws SQLException { conn.close(); }
}
