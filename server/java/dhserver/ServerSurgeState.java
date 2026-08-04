package dhserver;

import com.perblue.grunt.translate.util.GruntInputStream;
import com.perblue.grunt.translate.util.GruntOutputStream;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.data.item.StatType;
import com.perblue.heroes.game.objects.IndividualUser;
import com.perblue.heroes.game.objects.UnitData;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.network.messages.Avatar;
import com.perblue.heroes.network.messages.BasicUserInfo;
import com.perblue.heroes.network.messages.DistrictType;
import com.perblue.heroes.network.messages.ExtendedHeroSummary;
import com.perblue.heroes.network.messages.IndividualUserExtra;
import com.perblue.heroes.network.messages.LineupSummary;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.SurgeData;
import com.perblue.heroes.network.messages.SurgeMemberSummary;
import com.perblue.heroes.network.messages.SurgeOpponentSummary;
import com.perblue.heroes.network.messages.SurgeResultInfo;
import com.perblue.heroes.network.messages.SurgeScoringInfo;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.UserExtra;
import com.perblue.heroes.network.messages.UserInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.sql.SQLException;

/**
 * SURGE (#72) incrément 2 — ÉTAT PARTAGÉ DE GUILDE (persisté, multi-serveur §5).
 *
 * <p>Un surge est partagé par toute la guilde : un {@link SurgeData} par (guilde, surgeID). On le stocke en
 * octets wire dans la table {@code shard_state} (clé {@code surge:<guildID>}), comme les autres états opérateur
 * (contests, ancre d'horloge, graines de guerre) — plutôt que dans le blob versionné de {@link ServerGuild},
 * pour rester ISOLÉ et REMIS À ZÉRO proprement à chaque nouveau surge (cf. {@code loadOrReset}).
 *
 * <p>Le calendrier/identité vient de {@link ServerSurge} (100 % code du jeu). Les MEMBRES sont dérivés du ROSTER
 * de la guilde (une entrée {@link SurgeMemberSummary} par membre, identité = {@code BasicUserInfo}). À ce stade
 * (incrément 2) l'état est le SOCLE : membres + fenêtre + conteneurs vides ; adversaires/districts/objectifs/
 * scoring/récompenses arrivent aux incréments suivants (cf. docs/SURGE.md), peuplés via le code du jeu (§3/§4),
 * jamais inventés.
 */
public final class ServerSurgeState {

  private static final int VERSION = 1;

  private ServerSurgeState() {}

  static String key(long guildID) { return "surge:" + guildID; }

  /**
   * Charge le {@link SurgeData} partagé de la guilde pour le surge COURANT ; le (re)construit si absent ou si le
   * surgeID a changé (nouveau surge → remise à zéro, comme {@code InvasionHelper.resetUserInvasion} / la bascule
   * d'état de guerre). Persiste et renvoie l'état à jour.
   */
  public static SurgeData loadOrReset(UserStore store, ServerGuild guild, long now) throws SQLException {
    ServerContext.init();
    long curID = ServerSurge.currentSurgeID(now);
    byte[] blob = store.loadShardState(guild.shardID, key(guild.guildID));
    if (blob != null && blob.length > 0) {
      long storedID = peekSurgeID(blob);
      if (storedID == curID) return decode(blob);      // même surge → on conserve la progression persistée
    }
    SurgeData fresh = buildFresh(store, guild, now, curID);
    store.saveShardState(guild.shardID, key(guild.guildID), encode(curID, fresh));
    return fresh;
  }

  /** Écrit l'état à jour (après une mutation d'un incrément ultérieur : combat/raid/objectif). */
  public static void save(UserStore store, ServerGuild guild, long surgeID, SurgeData data) throws SQLException {
    store.saveShardState(guild.shardID, key(guild.guildID), encode(surgeID, data));
  }

  /** SurgeData VIDE wire-sûr (joueur hors guilde : pas de surge — réponse fidèle, aucun membre). */
  public static SurgeData emptySurge(long now) {
    ServerContext.init();
    SurgeData d = new SurgeData();
    d.surgeID = ServerSurge.currentSurgeID(now);
    d.raidEndTime = ServerSurge.surgeEndTime(now);
    d.nextRaidStartTime = ServerSurge.nextSurgeStartTime(now);
    d.members = new java.util.ArrayList<>();
    d.opponents = new java.util.ArrayList<>();
    d.log = new java.util.ArrayList<>();
    d.waveRegionsCleared = new java.util.ArrayList<>();
    d.objectives = new java.util.HashMap<>();
    d.unclaimedRewards = new java.util.HashMap<>();
    d.surgeScoringInfo = new SurgeScoringInfo();
    d.previousResults = new SurgeResultInfo();
    return d;
  }

  /** Construit un {@link SurgeData} NEUF pour {@code surgeID} : fenêtre (ServerSurge) + membres (roster) + conteneurs
   *  vides non nuls (wire-sûr). Aucune valeur inventée : timings du code du jeu, identités du roster. */
  static SurgeData buildFresh(UserStore store, ServerGuild guild, long now, long surgeID) throws SQLException {
    SurgeData d = new SurgeData();
    d.surgeID = surgeID;
    // Fenêtre depuis le calendrier du jeu (ServerSurge = SurgeHelper). raidStartTime affiné à l'incrément raids.
    d.raidEndTime = ServerSurge.surgeEndTime(now);
    d.nextRaidStartTime = ServerSurge.nextSurgeStartTime(now);
    // Conteneurs NON nuls (sinon NPE à l'écriture wire — défaut nº3). Peuplés aux incréments suivants.
    d.members = buildMembers(store, guild);
    d.opponents = buildOpponents(store, guild);
    d.totalWaves = ServerSurgeMap.activeDistricts().size();
    d.log = new java.util.ArrayList<>();
    d.waveRegionsCleared = new java.util.ArrayList<>();
    d.objectives = new java.util.HashMap<>();
    d.unclaimedRewards = new java.util.HashMap<>();
    // Sous-messages NON nuls (idem) — structure vide, remplie au scoring/résultats (incréments 4/7).
    d.surgeScoringInfo = new SurgeScoringInfo();
    d.previousResults = new SurgeResultInfo();
    return d;
  }

  /** Membres du surge = ROSTER de la guilde. Une entrée par membre, identité {@code BasicUserInfo} (avatar non nul
   *  pour le wire) ; progression à zéro au démarrage. Le membre absent du store est ignoré (fidèle). */
  static java.util.List<SurgeMemberSummary> buildMembers(UserStore store, ServerGuild guild) throws SQLException {
    java.util.List<SurgeMemberSummary> out = new java.util.ArrayList<>();
    for (Long id : guild.memberIDs) {
      ServerUser u = store.loadIfExists(id, guild.shardID);
      if (u == null) continue;
      SurgeMemberSummary m = new SurgeMemberSummary();
      BasicUserInfo who = u.basicInfo();
      if (who != null && who.avatar == null) who.avatar = new Avatar();   // wire-sûr (sous-message non nul)
      m.user = who != null ? who : new BasicUserInfo();
      m.objectiveProgress = new java.util.HashMap<>();
      out.add(m);
    }
    return out;
  }

  // ---- ADVERSAIRES (incrément 4b-ii) : un par district actif, pool réel du shard + repli synthétique ----

  /**
   * Un {@link SurgeOpponentSummary} par district actif ({@link ServerSurgeMap}). Modèle ARÈNE #43 : on tire des
   * JOUEURS RÉELS du shard (hors membres de la guilde) et on prend leur équipe (roster) comme lineup adverse ;
   * si le pool est vide, repli SYNTHÉTIQUE déterministe (bot par district). Le combat reste client-autoritatif
   * (le lineup n'est qu'un instantané d'affichage + l'équipe à affronter).
   */
  static java.util.List<SurgeOpponentSummary> buildOpponents(UserStore store, ServerGuild guild) throws SQLException {
    java.util.List<DistrictType> districts = ServerSurgeMap.activeDistricts();
    java.util.List<Long> pool = store.listUserIDs(guild.shardID, 0L);
    pool.removeAll(guild.memberIDs);                        // pas d'adversaires de sa propre guilde
    java.util.List<SurgeOpponentSummary> out = new java.util.ArrayList<>();
    int pi = 0;
    for (DistrictType d : districts) {
      SurgeOpponentSummary o = new SurgeOpponentSummary();
      o.district = d;
      o.clearedThisWave = false;
      o.lockExpiration = 0L;
      o.modifyCount = 0L;
      o.points = 0L;                                        // points réels calculés par recordOutcome à la défaite
      ServerUser src = null;
      if (!pool.isEmpty()) { src = store.loadIfExists(pool.get(pi % pool.size()), guild.shardID); pi++; }
      if (src != null) {
        BasicUserInfo who = src.basicInfo();
        o.attackerID = src.userID;
        o.attackerName = who != null && who.name != null ? who.name : "";
        o.lineup = lineupFromUser(src.gameUser());
      }
      if (o.lineup == null || o.lineup.lineup == null || o.lineup.lineup.isEmpty()) {
        o.attackerID = o.attackerID != 0 ? o.attackerID : 0L;
        if (o.attackerName == null || o.attackerName.isEmpty()) o.attackerName = "Rival " + d.name();
        o.lineup = syntheticLineup(guild.shardID, d.ordinal() + 1L);
      }
      out.add(o);
    }
    return out;
  }

  /** Équipe d'un joueur (≤ 5 héros de son roster) en {@link LineupSummary} (mêmes convertisseurs que l'arène). */
  static LineupSummary lineupFromUser(User u) {
    LineupSummary ls = new LineupSummary();
    ls.lineup = new java.util.ArrayList<>();
    ls.combatModifiers = new java.util.HashMap<>();
    ls.power = 0L;
    try {
      int n = 0;
      for (Object o : u.getHeroes()) {
        if (n >= 5) break;
        UnitData ud = (UnitData) o;
        ls.lineup.add(extended(ud));
        try { ls.power += Math.max(0L, ud.getPower(0)); } catch (Throwable t) { /* ignore */ }
        n++;
      }
    } catch (Throwable t) { /* roster vide → lineup vide, repli synthétique en amont */ }
    return ls;
  }

  /** {@link ExtendedHeroSummary} d'un héros (résumé + PV), comme {@code ServerArena.extended}. */
  static ExtendedHeroSummary extended(UnitData ud) {
    ExtendedHeroSummary e = new ExtendedHeroSummary();
    e.summary = ClientNetworkStateConverter.getHeroSummary(ud);
    try { e.health = (long) ud.getCachedStat(StatType.HP_MAX, ud.getLevel()); } catch (Throwable t) { e.health = 0; }
    e.energy = 0;
    return e;
  }

  /** Repli SYNTHÉTIQUE (bot) déterministe par district — utilisé seulement si aucun joueur réel sur le shard.
   *  Équipe générée par la logique du jeu ({@code createAndAddHero}), pas de stats inventées. */
  static LineupSummary syntheticLineup(int shardID, long seed) {
    LineupSummary ls = new LineupSummary();
    ls.lineup = new java.util.ArrayList<>();
    ls.combatModifiers = new java.util.HashMap<>();
    ls.power = 0L;
    try {
      User bot = botUser(shardID, seed);
      java.util.List<UnitType> pool = new java.util.ArrayList<>(java.util.Arrays.asList(
          UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE));
      for (UnitType t : pool) {
        try {
          if (bot.getHero(t) == null) bot.createAndAddHero(t, Rarity.ORANGE, 40, 1, new String[]{"surge-bot"});
          UnitData ud = (UnitData) bot.getHero(t);
          if (ud != null) { ls.lineup.add(extended(ud)); ls.power += Math.max(0L, ud.getPower(0)); }
        } catch (Throwable perHero) { /* héros refusé → suivant */ }
      }
    } catch (Throwable t) { /* jamais fatal */ }
    return ls;
  }

  /** Bot lié au contexte de jeu (TL élevé, shard du joueur) — comme {@code ServerArena.botUser}. */
  static User botUser(int shardID, long id) {
    UserInfo ui = new UserInfo();
    ui.shardID = shardID;
    ui.basicInfo = new BasicUserInfo();
    ui.basicInfo.teamLevel = 65;
    User bot = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(ui, new UserExtra(), "surge-bot");
    IndividualUser iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        new IndividualUserExtra(), id, 0, "surge-bot");
    ServerContext.bind(bot, iu);
    return bot;
  }

  // ---- (dé)sérialisation : version + surgeID + octets wire du SurgeData ----

  static byte[] encode(long surgeID, SurgeData data) {
    try {
      GruntOutputStream gout = new GruntOutputStream();
      data.writeAll(gout);
      byte[] wire = gout.getBytes();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(VERSION);
      o.writeLong(surgeID);
      o.writeInt(wire.length);
      o.write(wire);
      o.flush();
      return bos.toByteArray();
    } catch (Exception ex) { throw new RuntimeException("sérialisation surge échouée", ex); }
  }

  static long peekSurgeID(byte[] b) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
      in.readInt();                 // version
      return in.readLong();         // surgeID
    } catch (Exception ex) { return Long.MIN_VALUE; }
  }

  static SurgeData decode(byte[] b) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
      in.readInt();                 // version (compat future)
      in.readLong();                // surgeID (déjà connu par l'appelant)
      byte[] wire = new byte[in.readInt()];
      in.readFully(wire);
      return (SurgeData) MessageFactory.getInstance().readMessage(new GruntInputStream(wire));
    } catch (Exception ex) { throw new RuntimeException("lecture surge échouée", ex); }
  }
}
