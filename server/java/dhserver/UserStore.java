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
    }
  }

  private static boolean columnExists(Statement s, String table, String col) throws SQLException {
    try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) if (col.equalsIgnoreCase(rs.getString("name"))) return true;
    }
    return false;
  }

  /** Charge le joueur (userID,shardID) s'il existe, sinon en crée un NOUVEAU et le persiste. */
  public synchronized ServerUser loadOrCreate(long userID, int shardID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT userInfo, userExtra, individualUserExtra, battlePassV2Data FROM users WHERE userID=? AND shardID=?")) {
      ps.setLong(1, userID);
      ps.setInt(2, shardID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          ServerUser su = ServerUser.fromWire(userID, shardID,
              rs.getBytes(1), rs.getBytes(2), rs.getBytes(3));
          su.setBattlePassWire(rs.getBytes(4));   // état battle pass persisté (NULL si pré-migration)
          return su;
        }
      }
    }
    ServerUser fresh = ServerUser.newPlayer(userID, shardID);
    save(fresh);
    return fresh;
  }

  /** Écrit (upsert) l'état courant du joueur en octets wire. */
  public synchronized void save(ServerUser u) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO users (userID, shardID, userInfo, userExtra, individualUserExtra, battlePassV2Data, updatedAt) "
        + "VALUES (?,?,?,?,?,?,?) "
        + "ON CONFLICT(userID, shardID) DO UPDATE SET "
        + "userInfo=excluded.userInfo, userExtra=excluded.userExtra, "
        + "individualUserExtra=excluded.individualUserExtra, battlePassV2Data=excluded.battlePassV2Data, "
        + "updatedAt=excluded.updatedAt")) {
      ps.setLong(1, u.userID);
      ps.setInt(2, u.shardID);
      ps.setBytes(3, u.userInfoWire());
      ps.setBytes(4, u.userExtraWire());
      ps.setBytes(5, u.individualWire());
      ps.setBytes(6, u.battlePassWire());          // NULL si battle pass non initialisé (recréé au boot)
      ps.setLong(7, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  @Override public synchronized void close() throws SQLException { conn.close(); }
}
