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

  // --- CHAT de guilde (écran CHAT, salon GUILD ; #59) ---
  /** Historique du chat de guilde : octets wire de chaque {@link com.perblue.heroes.network.messages.Chat}
   *  (objet du jeu, jamais un schéma inventé — PRINCIPLES §4/§6). Le plus ANCIEN en tête, borné à
   *  {@link #MAX_CHAT_HISTORY}. Le serveur PerBlue gardait cet historique côté opérateur (absent du jar client). */
  public final List<byte[]> guildChatWire = new ArrayList<>();
  /** Prochain identifiant de message (unique par guilde ; le client dédoublonne les Chat par (salon, chatID)). */
  public long nextChatID = 1L;
  /** Le client borne l'affichage du salon GUILD (SocialDataManager.MAX_HISTORY) ; on borne le stockage de même. */
  public static final int MAX_CHAT_HISTORY = 100;

  // --- DONS / GUILD AID (écran GUILD AID ; #55) ---
  /** Demandes d'aide ACTIVES : octets wire de chaque {@link com.perblue.heroes.network.messages.GuildDonationRequestRow}
   *  (objet du jeu). Le builder de demande n'existe PAS dans le jar client (comme ArenaInfo — état opérateur
   *  PerBlue) → synthétisé et persisté ici (multi-serveur §5). */
  public final List<byte[]> donationRequestsWire = new ArrayList<>();
  /** Prochain identifiant de demande (unique par guilde). */
  public long nextRequestID = 1L;
  /** Suivi des dons par demande : requestID → (userID donateur → nombre de dons). État opérateur (#55b). */
  public final java.util.LinkedHashMap<Long, java.util.LinkedHashMap<Long, Integer>> donationsByUser =
      new java.util.LinkedHashMap<>();

  // ===== v5 : CADEAUX / GUILD CRATE (#58/#66) — état OPÉRATEUR (aucun GuildGiftHelper client) =====
  /** Cadeaux de guilde accumulés (3 listes PARALLÈLES). Un cadeau = 1 offreur + 1 horodatage + N récompenses. */
  public final List<byte[]> giftGifterWire = new ArrayList<>();     // BasicUserInfo (octets wire) par cadeau
  public final List<Long> giftTimes = new ArrayList<>();            // horodatage serveur par cadeau
  public final List<byte[]> giftRewardsBlob = new ArrayList<>();    // [int n][int len + RewardDrop wire]×n par cadeau
  /** Suivi anti-double-réclamation : userID → horodatage du dernier cadeau réclamé. */
  public final java.util.LinkedHashMap<Long, Long> giftClaimTimes = new java.util.LinkedHashMap<>();
  /** Identifiant d'évènement cadeau (flux de la guilde). */
  public long giftEventID = 1L;
  public static final int MAX_GIFT_HISTORY = 100;

  // ===== v6 : CONTEST (#67) — contribution PAR MEMBRE (état OPÉRATEUR) =====
  /** Points de contest apportés par chaque membre : userID → points. Le TOTAL de la guilde est
   *  {@code info.contestPoints} (= ce que le jeu appelle « guild contest points » : {@code User
   *  .getGuildContestPoints()} retourne {@code getYourGuildInfo().contestPoints}). La ventilation par
   *  membre n'existe QUE côté serveur (le client ne la calcule pas) → elle alimente {@code ContestRankings}. */
  public final java.util.LinkedHashMap<Long, Long> contestPointsByUser = new java.util.LinkedHashMap<>();

  // ===== v7 : INVASION (#69) — BOSS partagés de la guilde =====
  /** Boss d'invasion actifs de la guilde : octets wire de chaque
   *  {@link com.perblue.heroes.network.messages.InvasionBossInfo} (objet du jeu, qui porte lui-même les dégâts
   *  par joueur, le niveau, l'échéance…). Le boss est de l'état OPÉRATEUR partagé : trouvé par un membre, il est
   *  attaquable par toute la guilde jusqu'à {@code BOSS_FIGHT_TIME_LIMIT}. */
  public final List<byte[]> invasionBossWire = new ArrayList<>();
  /** Verrou d'attaque (`ATTACK_LOCK_DURATION`=5 min) : bossID → (userID, expiration). Empêche deux membres
   *  d'attaquer le même boss simultanément ({@code BOSS_SIMULTANEOUS_ATTACKS_COUNT}=1). */
  public final java.util.LinkedHashMap<Long, long[]> bossAttackLocks = new java.util.LinkedHashMap<>();
  /** Prochain identifiant de boss (unique par guilde). */
  public long nextBossID = 1L;

  // ===== v8 : GUILD WAR (#68) — état de guerre PROPRE À LA GUILDE =====
  // Une GUERRE (l'appariement de deux guildes) vit dans sa propre table `wars` ({@link ServerWarState}) ;
  // ici on ne garde que ce qui appartient DURABLEMENT à la guilde et lui survit d'une guerre à l'autre.

  /** File d'attente : {@code NOT_QUEUED} / {@code QUEUED_SINGLE} / {@code QUEUED_PERSISTENT}. */
  public com.perblue.heroes.network.messages.WarQueueState warQueueState =
      com.perblue.heroes.network.messages.WarQueueState.NOT_QUEUED;
  /** Instant de mise en file (horloge serveur) — sert à l'ordre d'appariement. */
  public long warQueuedTime;
  /** Note de matchmaking courante. {@code warSeasonID == 0} ⇒ jamais initialisée (guilde neuve). */
  public int warMMR;
  /** Saison à laquelle {@link #warMMR} se rapporte : un changement déclenche la remise à zéro de saison. */
  public int warSeasonID;
  /** Masque des ligues ATTEINTES dans la saison (encodage {@code WarHelper.updatePromotionFlag}) :
   *  implémente « une guilde ne peut pas être rétrogradée d'une ligue déjà atteinte ». */
  public int warPromotionMask;
  /** Guerre en cours ({@code 0} = aucune). */
  public long currentWarID;
  /** Adversaires récents, le plus RÉCENT en tête, borné par {@code MAX_PREVIOUS_WARS} — anti-rematch.
   *  C'est ce que le jeu appelle {@code WarMatchmakingGuildInfo.previousOpponents}. */
  public final List<Long> previousWarOpponents = new ArrayList<>();
  /** Rang minimal autorisé à consommer une attaque supplémentaire ({@code EditGuildWarSettings
   *  .extraAttackRank} — « The Guild Leader may change the settings to allow any Guild members to use
   *  Extra Attacks »). */
  public com.perblue.heroes.network.messages.GuildRole warExtraAttackRank =
      com.perblue.heroes.network.messages.GuildRole.OFFICER;
  /** Bilan de la saison EN COURS. */
  public int warsWon, warsLost, warsCompleted;
  /** Saisons ACHEVÉES : octets wire de chaque {@link com.perblue.heroes.network.messages.WarSeasonSummary}
   *  (objet du jeu, jamais un schéma inventé — PRINCIPLES §4/§6). Alimente {@code GetWarSeasonsList}. */
  public final List<byte[]> warSeasonHistoryWire = new ArrayList<>();
  /** Le client borne l'affichage de l'historique ; on borne le stockage de même. */
  public static final int MAX_WAR_SEASON_HISTORY = 24;

  /** Mémorise un adversaire (le plus récent en tête, sans doublon, borné). */
  public void rememberWarOpponent(long opponentGuildID, int maxPrevious) {
    previousWarOpponents.remove(opponentGuildID);
    previousWarOpponents.add(0, opponentGuildID);
    while (previousWarOpponents.size() > Math.max(1, maxPrevious)) {
      previousWarOpponents.remove(previousWarOpponents.size() - 1);
    }
  }

  /** Nombre de guerres écoulées depuis la dernière rencontre avec {@code opponentGuildID}
   *  ({@code -1} = jamais rencontré). 0 = adversaire de la guerre précédente. */
  public int warsSinceOpponent(long opponentGuildID) {
    int i = previousWarOpponents.indexOf(opponentGuildID);
    return i;
  }

  /** Ajoute un résumé de saison achevée (octets wire), borné. */
  public void addWarSeasonSummary(byte[] wire) {
    warSeasonHistoryWire.add(0, wire);
    while (warSeasonHistoryWire.size() > MAX_WAR_SEASON_HISTORY) {
      warSeasonHistoryWire.remove(warSeasonHistoryWire.size() - 1);
    }
  }

  /** Relit l'historique de saisons en objets du jeu (les illisibles sont écartés). */
  public List<com.perblue.heroes.network.messages.WarSeasonSummary> warSeasonHistory() {
    List<com.perblue.heroes.network.messages.WarSeasonSummary> out = new ArrayList<>();
    java.util.Iterator<byte[]> it = warSeasonHistoryWire.iterator();
    while (it.hasNext()) {
      try {
        out.add((com.perblue.heroes.network.messages.WarSeasonSummary)
            MessageFactory.getInstance().readMessage(new GruntInputStream(it.next())));
      } catch (Exception ignore) { it.remove(); }
    }
    return out;
  }

  /** Ajoute un boss (octets wire) au pool de la guilde. */
  public void addInvasionBoss(byte[] wire) { invasionBossWire.add(wire); }

  /** Relit les boss stockés en objets du jeu (les illisibles sont écartés). */
  public List<com.perblue.heroes.network.messages.InvasionBossInfo> invasionBosses() {
    List<com.perblue.heroes.network.messages.InvasionBossInfo> out = new ArrayList<>();
    java.util.Iterator<byte[]> it = invasionBossWire.iterator();
    while (it.hasNext()) {
      try {
        out.add((com.perblue.heroes.network.messages.InvasionBossInfo)
            MessageFactory.getInstance().readMessage(new GruntInputStream(it.next())));
      } catch (Exception ignore) { it.remove(); }
    }
    return out;
  }

  /** Remplace (ou supprime si {@code null}) le boss {@code bossID} par sa version wire à jour. */
  public void replaceInvasionBoss(long bossID, com.perblue.heroes.network.messages.InvasionBossInfo updated) {
    for (int i = 0; i < invasionBossWire.size(); i++) {
      try {
        com.perblue.heroes.network.messages.InvasionBossInfo b =
            (com.perblue.heroes.network.messages.InvasionBossInfo)
                MessageFactory.getInstance().readMessage(new GruntInputStream(invasionBossWire.get(i)));
        if (b.bossID != bossID) continue;
        if (updated == null) { invasionBossWire.remove(i); bossAttackLocks.remove(bossID); return; }
        GruntOutputStream go = new GruntOutputStream();
        updated.writeAll(go);
        invasionBossWire.set(i, go.getBytes());
        return;
      } catch (Exception ignore) { }
    }
  }

  /** Tente de POSER le verrou d'attaque sur un boss pour {@code userID}. {@code false} = déjà verrouillé
   *  par quelqu'un d'autre (verrou expiré = repris). */
  public boolean lockBoss(long bossID, long userID, long now, long lockDuration) {
    long[] cur = bossAttackLocks.get(bossID);
    if (cur != null && cur[1] > now && cur[0] != userID) return false;
    bossAttackLocks.put(bossID, new long[]{userID, now + lockDuration});
    return true;
  }

  /** Lève le verrou d'attaque si {@code userID} le détient. */
  public void unlockBoss(long bossID, long userID) {
    long[] cur = bossAttackLocks.get(bossID);
    if (cur != null && cur[0] == userID) bossAttackLocks.remove(bossID);
  }

  /** Ajoute un cadeau (offreur + récompenses) au flux de la guilde, borné à {@code MAX_GIFT_HISTORY}. */
  public void addGift(byte[] gifterWire, long time, byte[] rewardsBlob) {
    giftGifterWire.add(gifterWire); giftTimes.add(time); giftRewardsBlob.add(rewardsBlob);
    while (giftGifterWire.size() > MAX_GIFT_HISTORY) {
      giftGifterWire.remove(0); giftTimes.remove(0); giftRewardsBlob.remove(0);
    }
  }

  public ServerGuild() {}

  public int checkInsToday() { return checkedInToday.size(); }

  /** Ajoute un message au chat (octets wire), en bornant l'historique. */
  public void addChatWire(byte[] wire) {
    guildChatWire.add(wire);
    while (guildChatWire.size() > MAX_CHAT_HISTORY) guildChatWire.remove(0);
  }

  /** Ajoute une demande d'aide (octets wire d'un {@link com.perblue.heroes.network.messages.GuildDonationRequestRow}). */
  public void addDonationRequestWire(byte[] wire) { donationRequestsWire.add(wire); }

  /** Relit TOUTES les demandes d'aide stockées en objets du jeu (sans retrait — le retrait des expirées/complétées
   *  + la livraison de la récompense au demandeur sont gérés par le serveur qui a accès au store). */
  public List<com.perblue.heroes.network.messages.GuildDonationRequestRow> allDonationRequests() {
    List<com.perblue.heroes.network.messages.GuildDonationRequestRow> out = new ArrayList<>();
    java.util.Iterator<byte[]> it = donationRequestsWire.iterator();
    while (it.hasNext()) {
      try {
        out.add((com.perblue.heroes.network.messages.GuildDonationRequestRow)
            MessageFactory.getInstance().readMessage(new GruntInputStream(it.next())));
      } catch (Exception ignore) { it.remove(); }
    }
    return out;
  }

  /** Demandes ACTIVES pour l'affichage (non expirées, dons restants > 0). */
  public List<com.perblue.heroes.network.messages.GuildDonationRequestRow> donationRequests() {
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    List<com.perblue.heroes.network.messages.GuildDonationRequestRow> out = new ArrayList<>();
    for (com.perblue.heroes.network.messages.GuildDonationRequestRow r : allDonationRequests())
      if (r.expiration > now && r.remainingDonations > 0) out.add(r);
    return out;
  }

  /** Remplace/supprime la demande {@code requestID} par sa version wire à jour ({@code null} = suppression). */
  public void updateDonationRequest(long requestID, com.perblue.heroes.network.messages.GuildDonationRequestRow updated) {
    for (int i = 0; i < donationRequestsWire.size(); i++) {
      try {
        com.perblue.heroes.network.messages.GuildDonationRequestRow r =
            (com.perblue.heroes.network.messages.GuildDonationRequestRow)
                MessageFactory.getInstance().readMessage(new GruntInputStream(donationRequestsWire.get(i)));
        if (r.requestID != requestID) continue;
        if (updated == null) { donationRequestsWire.remove(i); donationsByUser.remove(requestID); return; }
        GruntOutputStream go = new GruntOutputStream(); updated.writeAll(go);
        donationRequestsWire.set(i, go.getBytes());
        return;
      } catch (Exception ignore) {}
    }
  }

  /** Relit l'historique en objets {@link com.perblue.heroes.network.messages.Chat} du jeu (pour resync/broadcast). */
  public List<com.perblue.heroes.network.messages.Chat> chatHistory() {
    List<com.perblue.heroes.network.messages.Chat> out = new ArrayList<>();
    for (byte[] w : guildChatWire) {
      try {
        out.add((com.perblue.heroes.network.messages.Chat)
            MessageFactory.getInstance().readMessage(new GruntInputStream(w)));
      } catch (Exception ignore) { /* message illisible → ignoré (jamais fatal pour l'écran) */ }
    }
    return out;
  }

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

  /** Relit un enum du jeu par son nom, avec repli si la valeur n'existe plus (tolérance de version). */
  private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E dflt) {
    try { return Enum.valueOf(type, name); } catch (Exception ignore) { return dflt; }
  }

  /** Sérialise : octets wire de {@link GuildInfo} (objet du jeu) + roster (état opérateur). */
  public byte[] toBytes() {
    try {
      GruntOutputStream gout = new GruntOutputStream();
      info.writeAll(gout);                 // format réseau exact de l'objet de jeu
      byte[] infoWire = gout.getBytes();

      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream o = new DataOutputStream(bos);
      o.writeInt(8);                       // version (…6 contest/membre ; 7 boss d'invasion ; 8 guild war)
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
      // v3 : chat de guilde
      o.writeLong(nextChatID);
      o.writeInt(guildChatWire.size());
      for (byte[] w : guildChatWire) { o.writeInt(w.length); o.write(w); }
      // v4 : dons / GUILD AID
      o.writeLong(nextRequestID);
      o.writeInt(donationRequestsWire.size());
      for (byte[] w : donationRequestsWire) { o.writeInt(w.length); o.write(w); }
      o.writeInt(donationsByUser.size());
      for (java.util.Map.Entry<Long, java.util.LinkedHashMap<Long, Integer>> e : donationsByUser.entrySet()) {
        o.writeLong(e.getKey());
        o.writeInt(e.getValue().size());
        for (java.util.Map.Entry<Long, Integer> d : e.getValue().entrySet()) { o.writeLong(d.getKey()); o.writeInt(d.getValue()); }
      }
      // v5 : cadeaux / guild crate
      o.writeLong(giftEventID);
      o.writeInt(giftGifterWire.size());
      for (int i = 0; i < giftGifterWire.size(); i++) {
        byte[] gw = giftGifterWire.get(i), rb = giftRewardsBlob.get(i);
        o.writeLong(giftTimes.get(i));
        o.writeInt(gw.length); o.write(gw);
        o.writeInt(rb.length); o.write(rb);
      }
      o.writeInt(giftClaimTimes.size());
      for (java.util.Map.Entry<Long, Long> e : giftClaimTimes.entrySet()) { o.writeLong(e.getKey()); o.writeLong(e.getValue()); }
      // v6 : contribution de contest par membre
      o.writeInt(contestPointsByUser.size());
      for (java.util.Map.Entry<Long, Long> e : contestPointsByUser.entrySet()) { o.writeLong(e.getKey()); o.writeLong(e.getValue()); }
      // v7 : boss d'invasion partagés + verrous d'attaque
      o.writeLong(nextBossID);
      o.writeInt(invasionBossWire.size());
      for (byte[] w : invasionBossWire) { o.writeInt(w.length); o.write(w); }
      o.writeInt(bossAttackLocks.size());
      for (java.util.Map.Entry<Long, long[]> e : bossAttackLocks.entrySet()) {
        o.writeLong(e.getKey()); o.writeLong(e.getValue()[0]); o.writeLong(e.getValue()[1]);
      }
      // v8 : guild war (état propre à la guilde ; la guerre elle-même est dans la table `wars`)
      o.writeUTF(warQueueState == null ? "NOT_QUEUED" : warQueueState.name());
      o.writeLong(warQueuedTime);
      o.writeInt(warMMR);
      o.writeInt(warSeasonID);
      o.writeInt(warPromotionMask);
      o.writeLong(currentWarID);
      o.writeUTF(warExtraAttackRank == null ? "OFFICER" : warExtraAttackRank.name());
      o.writeInt(warsWon); o.writeInt(warsLost); o.writeInt(warsCompleted);
      o.writeInt(previousWarOpponents.size());
      for (Long id : previousWarOpponents) o.writeLong(id);
      o.writeInt(warSeasonHistoryWire.size());
      for (byte[] w : warSeasonHistoryWire) { o.writeInt(w.length); o.write(w); }
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
      if (version >= 3) {
        g.nextChatID = in.readLong();
        int cc = in.readInt();
        for (int i = 0; i < cc; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); g.guildChatWire.add(w); }
      }
      if (version >= 4) {
        g.nextRequestID = in.readLong();
        int dr = in.readInt();
        for (int i = 0; i < dr; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); g.donationRequestsWire.add(w); }
        int nd = in.readInt();
        for (int i = 0; i < nd; i++) {
          long reqID = in.readLong();
          int m = in.readInt();
          java.util.LinkedHashMap<Long, Integer> byUser = new java.util.LinkedHashMap<>();
          for (int j = 0; j < m; j++) { long uid = in.readLong(); byUser.put(uid, in.readInt()); }
          g.donationsByUser.put(reqID, byUser);
        }
      }
      if (version >= 5) {
        g.giftEventID = in.readLong();
        int ng = in.readInt();
        for (int i = 0; i < ng; i++) {
          long t = in.readLong();
          byte[] gw = new byte[in.readInt()]; in.readFully(gw);
          byte[] rb = new byte[in.readInt()]; in.readFully(rb);
          g.giftGifterWire.add(gw); g.giftTimes.add(t); g.giftRewardsBlob.add(rb);
        }
        int nc = in.readInt();
        for (int i = 0; i < nc; i++) { long uid = in.readLong(); g.giftClaimTimes.put(uid, in.readLong()); }
      }
      if (version >= 6) {
        int np = in.readInt();
        for (int i = 0; i < np; i++) { long uid = in.readLong(); g.contestPointsByUser.put(uid, in.readLong()); }
      }
      if (version >= 7) {
        g.nextBossID = in.readLong();
        int nb = in.readInt();
        for (int i = 0; i < nb; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); g.invasionBossWire.add(w); }
        int nl = in.readInt();
        for (int i = 0; i < nl; i++) {
          long bid = in.readLong();
          g.bossAttackLocks.put(bid, new long[]{in.readLong(), in.readLong()});
        }
      }
      if (version >= 8) {
        g.warQueueState = enumOr(com.perblue.heroes.network.messages.WarQueueState.class, in.readUTF(),
            com.perblue.heroes.network.messages.WarQueueState.NOT_QUEUED);
        g.warQueuedTime = in.readLong();
        g.warMMR = in.readInt();
        g.warSeasonID = in.readInt();
        g.warPromotionMask = in.readInt();
        g.currentWarID = in.readLong();
        g.warExtraAttackRank = enumOr(com.perblue.heroes.network.messages.GuildRole.class, in.readUTF(),
            com.perblue.heroes.network.messages.GuildRole.OFFICER);
        g.warsWon = in.readInt(); g.warsLost = in.readInt(); g.warsCompleted = in.readInt();
        int no = in.readInt();
        for (int i = 0; i < no; i++) g.previousWarOpponents.add(in.readLong());
        int nh = in.readInt();
        for (int i = 0; i < nh; i++) { byte[] w = new byte[in.readInt()]; in.readFully(w); g.warSeasonHistoryWire.add(w); }
      }
      return g;
    } catch (Exception ex) {
      throw new RuntimeException("lecture guilde échouée", ex);
    }
  }
}
