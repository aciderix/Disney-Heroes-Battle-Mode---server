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
    long storedID = Long.MIN_VALUE;
    if (blob != null && blob.length > 0) {
      storedID = peekSurgeID(blob);
      if (storedID == curID) return decode(blob);      // même surge → on conserve la progression persistée
    }
    // Le surgeID a changé → BASCULE : on fige l'état du surge terminé (registre + influence de guilde), puis
    // on (re)construit un état neuf embarquant les résultats précédents (comme la bascule de saison de guerre).
    SurgeResultInfo prev = null;
    if (blob != null && blob.length > 0 && storedID != 0 && storedID != Long.MIN_VALUE) {
      prev = rollover(store, guild, decode(blob), storedID);
    }
    SurgeData fresh = buildFresh(store, guild, now, curID);
    if (prev != null) fresh.previousResults = prev;
    store.saveShardState(guild.shardID, key(guild.guildID), encode(curID, fresh));
    return fresh;
  }

  /**
   * BASCULE d'un surge terminé (§6/§7) : fige un {@link ServerSurgeRewards.Ledger} ({@code surgeprev:<guildID>})
   * avec les résultats et la récompense PAR MEMBRE (or stocké + tokens calculés par le jeu), et crédite UNE fois
   * l'influence de guilde ({@code getInfluenceProgress + getBaseInfluence}, plafonnée). Idempotente de fait :
   * n'est appelée qu'au changement de surgeID, avant réécriture de l'état neuf. Renvoie les résultats à embarquer
   * dans {@code previousResults}.
   */
  static SurgeResultInfo rollover(UserStore store, ServerGuild guild, SurgeData old, long oldSurgeID)
      throws SQLException {
    ServerSurgeRewards.Ledger led = new ServerSurgeRewards.Ledger();
    led.prevSurgeID = oldSurgeID;
    led.result = ServerSurgeRewards.resultFor(old);
    if (old.members != null) {
      for (Object o : old.members) {
        SurgeMemberSummary mem = (SurgeMemberSummary) o;
        if (mem == null || mem.user == null) continue;
        led.rewards.put(mem.user.iD, ServerSurgeRewards.rewardFor(old, mem));
      }
    }
    store.saveShardState(guild.shardID, ServerSurgeRewards.ledgerKey(guild.guildID), led.encode());
    creditGuildInfluence(store, guild, old);
    return led.result;
  }

  /** Crédite l'influence de surge à la guilde (plafond via {@code getMaxGuildInfluence}, comme
   *  {@code applyStaminaBurnInfluence}). Amount = {@link ServerSurgeRewards#guildInfluenceFor} (100 % code du jeu). */
  static void creditGuildInfluence(UserStore store, ServerGuild guild, SurgeData old) throws SQLException {
    if (guild == null || guild.info == null) return;
    long gain = ServerSurgeRewards.guildInfluenceFor(old);
    if (gain <= 0) return;
    com.perblue.heroes.game.objects.GuildInfoPerkProvider perks =
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(guild.info);
    long max;
    try { max = com.perblue.heroes.game.logic.GuildPerkHelper.getMaxGuildInfluence(perks); }
    catch (Throwable t) { max = com.perblue.heroes.game.data.guild.GuildStats.getBaseInfluenceCap(); }
    guild.info.influence = Math.min(guild.info.influence + gain, max);
    store.saveGuild(guild);
  }

  /**
   * Personnalise un {@link SurgeData} PARTAGÉ pour le VIEWER (champs « your… ») avant l'envoi par {@code GetSurge} :
   * pose la récompense NON réclamée du surge précédent dans {@code unclaimedRewards[surgeID]} (le client ouvre la
   * fenêtre de résultats si {@code totalGold≠0 || totalTokens≠0}) et renseigne {@code yourRaidsUsed}. Ne mute pas
   * l'état partagé persisté (copie par référence dans un champ par-viewer uniquement).
   */
  public static void personalize(UserStore store, ServerGuild guild, SurgeData data, long userID)
      throws SQLException {
    if (data.unclaimedRewards == null) data.unclaimedRewards = new java.util.HashMap<>();
    SurgeMemberSummary mine = memberSummary(data, userID);
    if (mine != null) {
      data.yourRaidsUsed = mine.raidsUsed;
      // PARTICIPATION : le membre d'une guilde EST dans le surge courant. Le client exige {@code youAreInRaid}
      // pour autoriser le combat de district (SurgeScreen.fightPressed : sinon « JOINED_LATE_ERROR »). Champ
      // 100 % serveur-autoritaire (aucun flux client ne l'écrit) → prouvé EN JEU (§8) : sans lui, aucun combat.
      data.youAreInRaid = true;
    }
    byte[] lb = store.loadShardState(guild.shardID, ServerSurgeRewards.ledgerKey(guild.guildID));
    if (lb == null || lb.length == 0) return;
    ServerSurgeRewards.Ledger led = ServerSurgeRewards.Ledger.decode(lb);
    if (led.prevSurgeID == 0 || led.claimed.contains(userID)) return;
    com.perblue.heroes.network.messages.SurgeRewards r = led.rewards.get(userID);
    if (r == null || (r.totalGold == 0 && r.totalTokens == 0)) return;   // rien à afficher (client n'ouvrirait pas)
    data.unclaimedRewards.put(led.prevSurgeID, r);
    if (led.result != null) data.previousResults = led.result;
  }

  /**
   * RÉCLAMATION autoritative des récompenses du surge précédent ({@code SurgeClaimRewards{surgeID}}) : crédite
   * l'or + les tokens ({@code CRYPT_TOKENS}) EXACTEMENT comme le client (mêmes montants figés au registre), une
   * seule fois (anti double-réclamation via le set {@code claimed}), persiste le joueur et le registre. Renvoie le
   * {@link SurgeRewards} crédité (récompense vide si rien à réclamer — surgeID inconnu, déjà réclamé, ou nul).
   */
  public static com.perblue.heroes.network.messages.SurgeRewards claimRewards(
      UserStore store, ServerGuild guild, ServerUser attacker, long surgeID) throws SQLException {
    com.perblue.heroes.network.messages.SurgeRewards empty = new com.perblue.heroes.network.messages.SurgeRewards();
    empty.surgeID = surgeID;
    byte[] lb = store.loadShardState(guild.shardID, ServerSurgeRewards.ledgerKey(guild.guildID));
    if (lb == null || lb.length == 0) return empty;
    ServerSurgeRewards.Ledger led = ServerSurgeRewards.Ledger.decode(lb);
    if (led.prevSurgeID != surgeID) return empty;                        // ne réclame QUE le surge précédent
    if (led.claimed.contains(attacker.userID)) return empty;             // déjà réclamé (anti-triche)
    com.perblue.heroes.network.messages.SurgeRewards r = led.rewards.get(attacker.userID);
    if (r == null) return empty;
    if (r.totalTokens > 0)
      attacker.giveResource(com.perblue.heroes.network.messages.ResourceType.CRYPT_TOKENS, r.totalTokens);
    if (r.totalGold > 0)
      attacker.giveResource(com.perblue.heroes.network.messages.ResourceType.GOLD, r.totalGold);
    store.save(attacker);
    led.claimed.add(attacker.userID);
    store.saveShardState(guild.shardID, ServerSurgeRewards.ledgerKey(guild.guildID), led.encode());
    return r;
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

  // ---- COMBAT DE RÉGION (incrément 4c) : démarrage (défenseur) + issue (recordOutcome + delta) ----

  /** Adversaire posé dans un district (ou {@code null}). */
  public static SurgeOpponentSummary opponentFor(SurgeData data, DistrictType district) {
    if (data.opponents == null) return null;
    for (Object o : data.opponents) {
      SurgeOpponentSummary op = (SurgeOpponentSummary) o;
      if (op.district == district) return op;
    }
    return null;
  }

  /** Summary du membre (par userID) dans le SurgeData (ou {@code null}). */
  public static SurgeMemberSummary memberSummary(SurgeData data, long userID) {
    if (data.members == null) return null;
    for (Object o : data.members) {
      SurgeMemberSummary m = (SurgeMemberSummary) o;
      if (m.user != null && m.user.iD == userID) return m;
    }
    return null;
  }

  /** Héros DÉFENSEURS d'un district en {@link com.perblue.heroes.network.messages.HeroData} COMPLET (le client
   *  rejoue le combat avec) : roster RÉEL de l'adversaire si c'est un vrai joueur, sinon bot synthétique
   *  déterministe. Miroir de {@code ServerArena.defenderHeroData}. */
  static java.util.List<com.perblue.heroes.network.messages.HeroData> defenderHeroData(
      UserStore store, ServerGuild guild, SurgeData data, DistrictType district) throws SQLException {
    java.util.List<com.perblue.heroes.network.messages.HeroData> out = new java.util.ArrayList<>();
    SurgeOpponentSummary op = opponentFor(data, district);
    if (op != null && op.attackerID > 0) {
      ServerUser d = store.loadIfExists(op.attackerID, guild.shardID);
      if (d != null) {
        User du = d.gameUser();
        int n = 0;
        for (Object o : du.getHeroes()) {
          if (n >= 5) break;
          out.add(ClientNetworkStateConverter.getHeroData((UnitData) o)); n++;
        }
      }
    }
    if (out.isEmpty()) {                                    // adversaire synthétique → bot déterministe
      User bot = botUser(guild.shardID, district.ordinal() + 1L);
      for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE}) {
        try {
          if (bot.getHero(t) == null) bot.createAndAddHero(t, Rarity.ORANGE, 40, 1, new String[]{"surge-bot"});
          UnitData ud = (UnitData) bot.getHero(t);
          if (ud != null) out.add(ClientNetworkStateConverter.getHeroData(ud));
        } catch (Throwable ignore) {}
      }
    }
    return out;
  }

  /**
   * DÉMARRAGE d'une attaque de district : réponse {@link com.perblue.heroes.network.messages.StartSurgeAttackResponse}
   * (lineup défenseur + raidID + modificateurs) ; verrouille l'adversaire (anti double-combat concurrent).
   */
  public static com.perblue.heroes.network.messages.StartSurgeAttackResponse startAttack(
      UserStore store, ServerGuild guild, SurgeData data, DistrictType district, long now) throws SQLException {
    com.perblue.heroes.network.messages.StartSurgeAttackResponse r =
        new com.perblue.heroes.network.messages.StartSurgeAttackResponse();
    r.district = district;
    r.combatModifiers = new java.util.HashMap<>();
    r.raidID = now;                                        // identifiant de session (unique par attaque)
    r.heroes = defenderHeroData(store, guild, data, district);
    SurgeOpponentSummary op = opponentFor(data, district);
    if (op != null) { op.lockExpiration = now + 5 * 60_000L; op.modifyCount++; }
    return r;
  }

  /**
   * ISSUE d'une attaque de district : exécute la logique autoritative ({@link ServerSurgeCombat#applyRegionOutcome})
   * sur la summary du membre, marque l'adversaire vaincu à la VICTOIRE, et renvoie le delta
   * {@link com.perblue.heroes.network.messages.SurgeUpdate} (à diffuser à la guilde). Ne persiste pas (l'appelant
   * sauvegarde le SurgeData).
   */
  public static com.perblue.heroes.network.messages.SurgeUpdate applyAttack(
      ServerGuild guild, SurgeData data, ServerUser attacker, com.perblue.heroes.network.messages.SurgeAttack m) {
    SurgeMemberSummary summary = memberSummary(data, attacker.userID);
    if (summary == null) {
      summary = new SurgeMemberSummary();
      BasicUserInfo who = attacker.basicInfo();
      if (who != null && who.avatar == null) who.avatar = new Avatar();
      summary.user = who != null ? who : new BasicUserInfo();
      summary.objectiveProgress = new java.util.HashMap<>();
      data.members.add(summary);
    }
    long pointsBefore = summary.totalPointsGained;
    User u = attacker.gameUser();
    // CONTEST : prepare AVANT — SurgeHelper.recordOutcome (dans applyRegionOutcome) appelle onSurgeAttack en interne (§3)
    // → crédite le blob per-user (rendu blob-backed par prepare). deliver après pour les paliers. L'appelant persiste l'attaquant.
    ServerContestData.prepare(attacker, u);
    ServerSurgeCombat.applyRegionOutcome(u, summary, data.surgeID, 0L, m,
        com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
    ServerContestData.deliverEarnedProgressRewards(attacker, u);

    SurgeOpponentSummary op = opponentFor(data, m.district);
    int districtsDelta = 0;
    boolean win = m.base != null && m.base.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN;
    if (win && op != null && !op.clearedThisWave) {
      op.clearedThisWave = true; op.lockExpiration = 0L; op.modifyCount++;
      summary.districtsCleared++; districtsDelta = 1;
    }

    com.perblue.heroes.network.messages.SurgeUpdate up = new com.perblue.heroes.network.messages.SurgeUpdate();
    up.member = summary;
    up.opponent = op != null ? op : freshOpponent(m.district);   // non nul (wire-sûr)
    up.surgePointDelta = summary.totalPointsGained - pointsBefore;
    up.districtsClearedDelta = districtsDelta;
    up.waveRegionsClearedDelta = 0;
    up.logEntry = new com.perblue.heroes.network.messages.SurgeLogData();
    return up;
  }

  /**
   * ISSUE d'un RAID (incrément 5) : exécute {@link ServerSurgeCombat#applyRaidOutcome} (recordRaid autoritatif :
   * or + consommation du pass) sur la summary du membre, incrémente le compteur PARTAGÉ {@code raidsUsed} (comme
   * {@code applyAttack} incrémente {@code districtsCleared} — recordRaid ne touche que le compteur quotidien du
   * joueur, pas l'état partagé de guilde), et renvoie le delta {@link com.perblue.heroes.network.messages.SurgeUpdate}.
   * Ne persiste pas (l'appelant sauvegarde). {@code district} vient de l'{@code Action RAID_SURGE} (extra TYPE).
   */
  public static com.perblue.heroes.network.messages.SurgeUpdate applyRaid(
      ServerGuild guild, SurgeData data, ServerUser attacker, DistrictType district) {
    SurgeMemberSummary summary = memberSummary(data, attacker.userID);
    if (summary == null) {
      summary = new SurgeMemberSummary();
      BasicUserInfo who = attacker.basicInfo();
      if (who != null && who.avatar == null) who.avatar = new Avatar();
      summary.user = who != null ? who : new BasicUserInfo();
      summary.objectiveProgress = new java.util.HashMap<>();
      data.members.add(summary);
    }
    long goldBefore = summary.storedGold;
    User u = attacker.gameUser();
    SurgeOpponentSummary op = opponentFor(data, district);
    LineupSummary oppLineup = op != null && op.lineup != null ? op.lineup : new LineupSummary();
    ServerSurgeCombat.applyRaidOutcome(u, summary, data.surgeID, district, oppLineup,
        com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
    summary.raidsUsed++;                       // compteur PARTAGÉ (recordRaid ne mute que le quotidien du joueur)

    com.perblue.heroes.network.messages.SurgeUpdate up = new com.perblue.heroes.network.messages.SurgeUpdate();
    up.member = summary;
    up.opponent = op != null ? op : freshOpponent(district);   // non nul (wire-sûr)
    up.surgePointDelta = summary.storedGold - goldBefore;       // or du raid (crédité par storeGold)
    up.districtsClearedDelta = 0;
    up.waveRegionsClearedDelta = 0;
    up.logEntry = new com.perblue.heroes.network.messages.SurgeLogData();
    return up;
  }

  /** Adversaire vide wire-sûr (district sans adversaire — ne devrait pas arriver hors désync). */
  static SurgeOpponentSummary freshOpponent(DistrictType d) {
    SurgeOpponentSummary o = new SurgeOpponentSummary();
    o.district = d; o.attackerName = ""; o.lineup = new LineupSummary();
    o.lineup.lineup = new java.util.ArrayList<>(); o.lineup.combatModifiers = new java.util.HashMap<>();
    return o;
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
