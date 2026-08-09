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
      // Migration : colonne challengeData (BLOB nullable = UserChallengeDataExtra sérialisé, mode Sticker
      // Challenges #72). NULL = état frais recréé au boot (aucun défi en cours). Un objet du jeu = une colonne.
      if (!columnExists(s, "users", "challengeData"))
        s.execute("ALTER TABLE users ADD COLUMN challengeData BLOB");
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
      // ÉTAT SHARD générique (clé → BLOB) : état opérateur qui n'appartient à AUCUN joueur ni guilde —
      // saison de contest (#67), et à venir les états INVASION/WAR (ligues, boss). Même esprit qu'arena_ladder,
      // mais générique pour éviter une table par fonctionnalité.
      s.execute("CREATE TABLE IF NOT EXISTS shard_state ("
          + "shardID INTEGER NOT NULL, key TEXT NOT NULL, value BLOB NOT NULL, "
          + "updatedAt INTEGER NOT NULL, PRIMARY KEY (shardID, key))");
      // INVASION #69 : état d'invasion PAR JOUEUR (octets wire du UserInvasionData du jeu). Séparé de la ligne
      // `users` car il est remis à zéro à chaque nouvelle invasion (rotation hebdomadaire).
      s.execute("CREATE TABLE IF NOT EXISTS user_invasion ("
          + "shardID INTEGER NOT NULL, userID INTEGER NOT NULL, data BLOB NOT NULL, "
          + "updatedAt INTEGER NOT NULL, PRIMARY KEY (shardID, userID))");
      // GUILD WAR #68 : une GUERRE par (shard, warID). Table dédiée plutôt que `shard_state` parce qu'on doit
      // l'INTERROGER — « la guerre en cours de la guilde X », « les N dernières guerres de X » — d'où les deux
      // colonnes de guilde indexées. BLOB = octets wire des deux WarGuildInfo du jeu + scalaires d'appariement.
      s.execute("CREATE TABLE IF NOT EXISTS wars ("
          + "shardID INTEGER NOT NULL, warID INTEGER NOT NULL, guildA INTEGER NOT NULL, "
          + "guildB INTEGER NOT NULL, seasonID INTEGER NOT NULL, startTime INTEGER NOT NULL, "
          + "endTime INTEGER NOT NULL, state TEXT NOT NULL, data BLOB NOT NULL, "
          + "updatedAt INTEGER NOT NULL, PRIMARY KEY (shardID, warID))");
      s.execute("CREATE INDEX IF NOT EXISTS wars_by_guildA ON wars (shardID, guildA, startTime DESC)");
      s.execute("CREATE INDEX IF NOT EXISTS wars_by_guildB ON wars (shardID, guildB, startTime DESC)");
      // GUILD WAR #68 : boîtes de guerre EN ATTENTE, par joueur. Gagnées par la guilde (promotion, fin de
      // saison) mais réclamées individuellement (`CLAIM_WAR_BOX_REWARD`), d'où une table par joueur —
      // même forme que `user_invasion`.
      s.execute("CREATE TABLE IF NOT EXISTS user_war_boxes ("
          + "shardID INTEGER NOT NULL, userID INTEGER NOT NULL, data BLOB NOT NULL, "
          + "updatedAt INTEGER NOT NULL, PRIMARY KEY (shardID, userID))");
    }
  }

  /**
   * GUILD WAR #68 — enregistre (ou met à jour) une guerre.
   *
   * <p>Si {@code w.warID == 0}, l'identifiant est ATTRIBUÉ ICI, sous le même verrou que l'insertion : la
   * séquence « lire max+1 » puis « insérer » est ainsi atomique. Le faire en deux appels séparés laisserait
   * deux appariements concurrents obtenir le même identifiant (PRINCIPLES §5, multi-serveur).
   */
  public synchronized void saveWar(ServerWarState w) throws SQLException {
    if (w.warID <= 0) w.warID = nextWarID(w.shardID);
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO wars (shardID, warID, guildA, guildB, seasonID, startTime, endTime, state, data, updatedAt) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?) "
            + "ON CONFLICT(shardID, warID) DO UPDATE SET guildA=excluded.guildA, guildB=excluded.guildB, "
            + "seasonID=excluded.seasonID, startTime=excluded.startTime, endTime=excluded.endTime, "
            + "state=excluded.state, data=excluded.data, updatedAt=excluded.updatedAt")) {
      ps.setInt(1, w.shardID);
      ps.setLong(2, w.warID);
      ps.setLong(3, w.guildAID);
      ps.setLong(4, w.guildBID);
      ps.setInt(5, w.seasonID);
      ps.setLong(6, w.startTime);
      ps.setLong(7, w.endTime);
      ps.setString(8, w.state == null ? "DEFAULT" : w.state.name());
      ps.setBytes(9, w.toBytes());
      ps.setLong(10, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  /** GUILD WAR #68 — charge une guerre, ou {@code null}. */
  public synchronized ServerWarState loadWar(int shardID, long warID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT data FROM wars WHERE shardID=? AND warID=?")) {
      ps.setInt(1, shardID);
      ps.setLong(2, warID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return ServerWarState.fromBytes(rs.getBytes(1));
      }
    }
    return null;
  }

  /** GUILD WAR #68 — les {@code limit} dernières guerres de {@code guildID}, la plus RÉCENTE en tête
   *  ({@code GetWarsList}, {@code WarMoments}). Une guilde peut être d'un côté comme de l'autre. */
  public synchronized java.util.List<ServerWarState> listWarsForGuild(int shardID, long guildID, int limit)
      throws SQLException {
    java.util.List<ServerWarState> out = new java.util.ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT data FROM wars WHERE shardID=? AND (guildA=? OR guildB=?) ORDER BY startTime DESC LIMIT ?")) {
      ps.setInt(1, shardID);
      ps.setLong(2, guildID);
      ps.setLong(3, guildID);
      ps.setInt(4, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ServerWarState w = ServerWarState.fromBytes(rs.getBytes(1));
          if (w != null) out.add(w);
        }
      }
    }
    return out;
  }

  /** GUILD WAR #68 — boîtes de guerre en attente de {@code (shard, userID)} (jamais {@code null}). */
  public synchronized ServerWarBoxes loadWarBoxes(int shardID, long userID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT data FROM user_war_boxes WHERE shardID=? AND userID=?")) {
      ps.setInt(1, shardID);
      ps.setLong(2, userID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return ServerWarBoxes.fromBytes(rs.getBytes(1));
      }
    }
    return new ServerWarBoxes();
  }

  /** GUILD WAR #68 — enregistre les boîtes en attente d'un joueur. */
  public synchronized void saveWarBoxes(int shardID, long userID, ServerWarBoxes boxes) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO user_war_boxes (shardID, userID, data, updatedAt) VALUES (?,?,?,?) "
            + "ON CONFLICT(shardID, userID) DO UPDATE SET data=excluded.data, updatedAt=excluded.updatedAt")) {
      ps.setInt(1, shardID);
      ps.setLong(2, userID);
      ps.setBytes(3, boxes.toBytes());
      ps.setLong(4, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  /** GUILD WAR #68 — prochain warID libre du shard (max+1, base 1). */
  public synchronized long nextWarID(int shardID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT COALESCE(MAX(warID),0)+1 FROM wars WHERE shardID=?")) {
      ps.setInt(1, shardID);
      try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
    }
    return 1L;
  }

  /** INVASION #69 — octets wire du {@code UserInvasionData} de {@code (shard, userID)}, ou {@code null}. */
  public synchronized byte[] loadUserInvasion(int shardID, long userID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT data FROM user_invasion WHERE shardID=? AND userID=?")) {
      ps.setInt(1, shardID);
      ps.setLong(2, userID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getBytes(1);
      }
    }
    return null;
  }

  /** INVASION #69 — TOUS les états d'invasion d'un shard (userID → octets wire), pour les CLASSEMENTS.
   *  Le classement d'invasion se calcule sur les points de chaque joueur, stockés dans {@code user_invasion}. */
  public synchronized java.util.LinkedHashMap<Long, byte[]> listUserInvasions(int shardID) throws SQLException {
    java.util.LinkedHashMap<Long, byte[]> out = new java.util.LinkedHashMap<>();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT userID, data FROM user_invasion WHERE shardID=?")) {
      ps.setInt(1, shardID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.put(rs.getLong(1), rs.getBytes(2));
      }
    }
    return out;
  }

  /** INVASION #69 — écrit (upsert) l'état d'invasion de {@code (shard, userID)}. */
  public synchronized void saveUserInvasion(int shardID, long userID, byte[] data) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO user_invasion (shardID, userID, data, updatedAt) VALUES (?,?,?,?) "
        + "ON CONFLICT(shardID, userID) DO UPDATE SET data=excluded.data, updatedAt=excluded.updatedAt")) {
      ps.setInt(1, shardID);
      ps.setLong(2, userID);
      ps.setBytes(3, data);
      ps.setLong(4, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  /** ÉTAT SHARD — lit la valeur brute de {@code (shard, key)}, ou {@code null} si absente. */
  public synchronized byte[] loadShardState(int shardID, String key) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT value FROM shard_state WHERE shardID=? AND key=?")) {
      ps.setInt(1, shardID);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getBytes(1);
      }
    }
    return null;
  }

  /** ÉTAT SHARD — écrit (upsert) la valeur de {@code (shard, key)}. */
  public synchronized void saveShardState(int shardID, String key, byte[] value) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO shard_state (shardID, key, value, updatedAt) VALUES (?,?,?,?) "
        + "ON CONFLICT(shardID, key) DO UPDATE SET value=excluded.value, updatedAt=excluded.updatedAt")) {
      ps.setInt(1, shardID);
      ps.setString(2, key);
      ps.setBytes(3, value);
      ps.setLong(4, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  /** MÉTA GLOBALE (clé-valeur, non lié à un shard) — réutilise {@code shard_state} avec {@code shardID=0}.
   *  Sert p. ex. à persister l'ANCRE D'HORLOGE ({@code clock_offset_ms}) pour survivre aux redémarrages. */
  public synchronized Long getMetaLong(String key) throws SQLException {
    byte[] raw = loadShardState(0, key);
    return raw != null && raw.length == 8 ? java.nio.ByteBuffer.wrap(raw).getLong() : null;
  }

  /** Écrit une méta globale {@code long} (cf. {@link #getMetaLong}). */
  public synchronized void setMetaLong(String key, long value) throws SQLException {
    saveShardState(0, key, java.nio.ByteBuffer.allocate(8).putLong(value).array());
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

  /**
   * Les shards qui portent au moins une guilde — c'est l'ensemble sur lequel l'ordonnanceur de GUERRE
   * (`ServerWarScheduler`) doit tourner. On ne tient pas de registre de shards à part : un shard sans
   * aucune guilde n'a par définition rien à apparier ni à clôturer (PRINCIPLES §5, multi-serveur).
   */
  public synchronized java.util.List<Integer> listGuildShards() throws SQLException {
    java.util.List<Integer> out = new java.util.ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT shardID FROM guilds ORDER BY shardID");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) out.add(rs.getInt(1));
    }
    return out;
  }

  /** Clé du compteur d'identifiants de guilde dans {@code shard_state}. */
  private static final String GUILD_ID_SEQ = "guild_id_seq";

  /**
   * GUILDES #7 — <b>ALLOUE</b> le prochain guildID du shard. {@code guildID > 0} = « en guilde » côté client.
   *
   * <p><b>Correctif de concurrence (défaut trouvé par {@code WarStateTest})</b> : cette méthode se contentait
   * de LIRE {@code MAX(guildID)+1}. Or le handler {@code CreateGuild} enchaîne « lire l'identifiant », « créer
   * la guilde », « enregistrer » en trois temps : deux créations concurrentes lisaient donc le MÊME
   * identifiant et la seconde ÉCRASAIT la première (l'{@code upsert} sur la clé primaire), faisant disparaître
   * une guilde et laissant son fondateur pointer vers celle d'un autre. Elle ALLOUE désormais réellement, en
   * persistant un compteur dans {@code shard_state} <b>dans le même bloc synchronisé</b> que la lecture :
   * deux appels successifs ne peuvent plus rendre la même valeur, même sans enregistrement intercalé.
   *
   * <p>Le compteur est initialisé sur le {@code MAX} existant, donc une base déjà peuplée reprend la suite
   * sans migration, et il ne recule jamais (on prend le maximum des deux sources).
   */
  public synchronized long nextGuildID(int shardID) throws SQLException {
    long fromTable = 0L;
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT COALESCE(MAX(guildID),0) FROM guilds WHERE shardID=?")) {
      ps.setInt(1, shardID);
      try (ResultSet rs = ps.executeQuery()) { if (rs.next()) fromTable = rs.getLong(1); }
    }
    long fromCounter = 0L;
    byte[] raw = loadShardState(shardID, GUILD_ID_SEQ);
    if (raw != null && raw.length == 8) {
      fromCounter = java.nio.ByteBuffer.wrap(raw).getLong();
    }
    long allocated = Math.max(fromTable, fromCounter) + 1L;
    saveShardState(shardID, GUILD_ID_SEQ, java.nio.ByteBuffer.allocate(8).putLong(allocated).array());
    return allocated;
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
        "SELECT userInfo, userExtra, individualUserExtra, battlePassV2Data, mail, challengeData FROM users WHERE userID=? AND shardID=?")) {
      ps.setLong(1, userID);
      ps.setInt(2, shardID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          ServerUser su = ServerUser.fromWire(userID, shardID, rs.getBytes(1), rs.getBytes(2), rs.getBytes(3));
          su.setBattlePassWire(rs.getBytes(4));
          su.setMailWire(rs.getBytes(5));
          su.setChallengeWire(rs.getBytes(6));
          return su;
        }
      }
    }
    return null;
  }

  /** Charge le joueur (userID,shardID) s'il existe, sinon en crée un NOUVEAU et le persiste. */
  public synchronized ServerUser loadOrCreate(long userID, int shardID) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT userInfo, userExtra, individualUserExtra, battlePassV2Data, mail, challengeData FROM users WHERE userID=? AND shardID=?")) {
      ps.setLong(1, userID);
      ps.setInt(2, shardID);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          ServerUser su = ServerUser.fromWire(userID, shardID,
              rs.getBytes(1), rs.getBytes(2), rs.getBytes(3));
          su.setBattlePassWire(rs.getBytes(4));   // état battle pass persisté (NULL si pré-migration)
          su.setMailWire(rs.getBytes(5));         // mailbox persistée (NULL si vide / pré-migration)
          su.setChallengeWire(rs.getBytes(6));    // défis sticker persistés (NULL si aucun / pré-migration)
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
    byte[] bpW = u.battlePassWire(), mlW = u.mailWire(), chW = u.challengeWire();
    long now = System.currentTimeMillis();
    // 2) écriture DB, sous le verrou du store uniquement
    synchronized (this) {
      try (PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO users (userID, shardID, userInfo, userExtra, individualUserExtra, battlePassV2Data, mail, challengeData, updatedAt) "
          + "VALUES (?,?,?,?,?,?,?,?,?) "
          + "ON CONFLICT(userID, shardID) DO UPDATE SET "
          + "userInfo=excluded.userInfo, userExtra=excluded.userExtra, "
          + "individualUserExtra=excluded.individualUserExtra, battlePassV2Data=excluded.battlePassV2Data, "
          + "mail=excluded.mail, challengeData=excluded.challengeData, updatedAt=excluded.updatedAt")) {
        ps.setLong(1, id);
        ps.setInt(2, shard);
        ps.setBytes(3, uiW);
        ps.setBytes(4, ueW);
        ps.setBytes(5, iuW);
        ps.setBytes(6, bpW);          // NULL si battle pass non initialisé (recréé au boot)
        ps.setBytes(7, mlW);          // NULL si mailbox vide
        ps.setBytes(8, chW);          // NULL si aucun défi sticker en cours
        ps.setLong(9, now);
        ps.executeUpdate();
      }
    }
  }

  @Override public synchronized void close() throws SQLException { conn.close(); }
}
