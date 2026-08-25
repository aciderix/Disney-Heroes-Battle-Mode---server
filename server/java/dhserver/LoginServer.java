package dhserver;

import com.perblue.grunt.translate.GruntConnection;
import com.perblue.grunt.translate.GruntConnectionListener;
import com.perblue.grunt.translate.GruntListener;
import com.perblue.grunt.translate.GruntMessage;
import com.perblue.grunt.translate.GruntServerFactory;
import com.perblue.heroes.network.DHXORConnectionWrapper;
import com.perblue.heroes.network.messages.BootData;
import com.perblue.heroes.network.messages.Action;
import com.perblue.heroes.network.messages.BuyChests;
import com.perblue.heroes.network.messages.CampaignAttack;
import com.perblue.heroes.network.messages.ChangeTutorialStep;
import com.perblue.heroes.network.messages.ClientInfo;
import com.perblue.heroes.network.messages.LootResults;
import com.perblue.heroes.network.messages.MessageFactory;
import com.perblue.heroes.network.messages.Ping;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Serveur de jeu TCP — réutilise INTÉGRALEMENT la pile réseau du jeu (GruntNIOTCPServer via
 * {@link GruntServerFactory}, codec {@link DHXORConnectionWrapper}, registre {@link MessageFactory}).
 * Aucune réimplémentation binaire (docs/PRINCIPLES.md §3 : serveur autoritaire basé sur le jeu).
 *
 * Cette version INSTRUMENTE : elle journalise CHAQUE message reçu du client (le client = source de
 * vérité) afin d'établir empiriquement le flux post-BootData (nouveau joueur → tuto) avant d'écrire
 * les handlers. Répond BootData au ClientInfo ; les autres messages sont journalisés (handlers à venir).
 */
public final class LoginServer {

  private final int port;
  /** Compte PAR DÉFAUT (repli avant ClientInfo, et pilote DEV). En multi-user, chaque connexion résout SON
   *  {@code ServerUser} depuis {@code ClientInfo.userID} (voir {@code connUsers} + le shadow dans onReceive). */
  private final ServerUser user;
  /** Persistance SQLite (octets wire des objets du jeu). */
  private final UserStore store;
  /** ARÈNE (vrai PvP) — source d'adversaires RÉELS (autres comptes du shard) adossée à la base. */
  private final ServerArena.OpponentSource oppSrc;
  /** MULTI-USER (#65) — compte résolu PAR CONNEXION (depuis {@code ClientInfo.userID}). Une entrée par socket. */
  private final java.util.concurrent.ConcurrentHashMap<GruntConnection, ServerUser> connUsers =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** MULTI-USER (#65) — registre des connexions EN LIGNE (userID → socket) pour le PUSH serveur (broadcast chat,
   *  et toute livraison temps réel aux autres membres). Rempli à ClientInfo, vidé à onClose. */
  private final java.util.concurrent.ConcurrentHashMap<Long, GruntConnection> online =
      new java.util.concurrent.ConcurrentHashMap<>();
  /**
   * Nombre de connexions ACCEPTÉES depuis le démarrage (observabilité).
   *
   * <p>Il sert aussi de point de rendez-vous aux tests sur socket : <b>un message émis par un client AVANT
   * que le serveur ait accepté la connexion est PERDU</b> — fait mesuré (2026-08-02), pas une supposition :
   * un envoi immédiatement après {@code open()} (ou depuis le {@code onOpen} du client) n'arrive JAMAIS,
   * alors qu'un envoi 50 ms plus tard passe systématiquement. Le vrai client n'y est pas exposé (son
   * {@code /login} HTTP précède l'ouverture du socket de jeu, ce qui laisse largement la fenêtre passer),
   * mais un test qui enchaîne connexion et envoi dans la même milliseconde l'était : c'est la vraie cause du
   * « flake ChestWireTest », longtemps attribuée à tort au chargement de {@code GuildStats}.
   */
  public final java.util.concurrent.atomic.AtomicInteger connectionsAccepted =
      new java.util.concurrent.atomic.AtomicInteger();

  public LoginServer(int port, ServerUser user, UserStore store) {
    this.port = port; this.user = user; this.store = store;
    this.oppSrc = new StoreOpponentSource(store);
  }

  /**
   * INVASION (#69) — envoie l'{@code InvasionInfo} courant : calendrier et identité de l'invasion, CALCULÉS
   * depuis les données du jeu ({@code invasion_constants} : jour/heure de début et de fin,
   * {@code INVASION_BASE_DATE}/{@code ROTATION} ; {@code UnitStats.getTeam} pour l'équipe vedette), plus
   * l'état JOUEUR relu en base et remis à zéro si la rotation a changé (comme
   * {@code InvasionHelper.resetUserInvasion}). L'énergie d'invasion reste une ressource du jeu
   * ({@code INVASION_STAMINA}, régénérée par la mécanique existante).
   *
   * <p>Appelé à deux moments : en RÉPONSE à {@code GetInvasionInfo}, et <b>spontanément au BOOT</b> — sans
   * cette poussée, {@code InvasionHelper.getActiveInvasion()} reste {@code null} côté client et la
   * navigation vers l'écran est refusée, donc le joueur ne peut jamais déclencher la requête (cf. le
   * commentaire au point d'appel).
   *
   * @param replyTo le message auquel répondre, ou {@code null} pour une poussée spontanée
   */
  private void sendInvasionInfo(GruntConnection c, ServerUser user, GruntMessage replyTo) {
    sendInvasionInfo(c, user, replyTo, false);
  }

  /** @param populateClaim renseigne {@code yourData.bossClaimStatus} (récompenses de boss réclamables). Coûteux :
   *  déclenche {@code InvasionHelper.getBossHP} → chargement des stats patchées (PatchStats). À NE PAS activer sur
   *  le PUSH SPONTANÉ AU BOOT : à ce stade la stat-sync du client n'a pas encore complété les tables (lignes
   *  SAPPHIRE), donc {@code PatchStats.<clinit>} JETTE (ExceptionInInitializerError) et « empoisonne » la classe pour
   *  toute la session — cassant getBossHP partout. On ne le fait donc que sur les chemins où le CONTENU est chargé
   *  (GetInvasionInfo/GetInvasionBosses/après un combat de boss). Défaut trouvé EN JEU (2026-08-03). */
  private void sendInvasionInfo(GruntConnection c, ServerUser user, GruntMessage replyTo, boolean populateClaim) {
    try {
      long inow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      com.perblue.heroes.network.messages.InvasionInfo ii = ServerInvasion.buildInfo(inow);
      if (ii.currentInvasion != null && ii.currentInvasion.invasion != null) {
        try {
          long invID = ii.currentInvasion.invasion.invasionID;
          byte[] prev = store.loadUserInvasion(user.shardID, user.userID);
          com.perblue.heroes.network.messages.UserInvasionData ud =
              ServerInvasion.loadOrResetUserData(prev, user.userID, user.currentGuildID(), invID);
          // Vue PAR JOUEUR des récompenses de boss réclamables : sans une entrée bossClaimStatus non-nulle,
          // taper un boss VAINCU ne déclenche RIEN côté client (cf. ServerInvasion.populateClaimStatus). Réservé
          // aux chemins « contenu chargé » (populateClaim) — JAMAIS au boot (poison PatchStats, voir javadoc).
          // sendInvasionInfo est sur la classe EXTERNE (currentGuild est sur le handler interne) → charge direct.
          if (populateClaim) {
            ServerGuild pcg = user.inGuild() ? store.loadGuild(user.shardID, user.currentGuildID()) : null;
            ServerInvasion.populateClaimStatus(pcg, user, ud, inow);
          }
          ii.currentInvasion.yourData = ud;
          store.saveUserInvasion(user.shardID, user.userID, ServerInvasion.userDataToBytes(ud));
        } catch (Exception e) { System.out.println("[login]     ! état invasion joueur : " + e); }
      }
      if (replyTo != null) ii.setAsReplyTo(replyTo);
      c.send(ii);
      System.out.println("[login] " + (replyTo != null ? "<== GetInvasionInfo → " : "")
          + "==> InvasionInfo : " + ServerInvasion.describe(inow));
    } catch (Exception e) {
      System.out.println("[login]     ! InvasionInfo échoué: " + e);
    }
  }

  /** Pousse un message à tous les MEMBRES d'une guilde actuellement EN LIGNE, sauf {@code exceptUserID}
   *  (typiquement l'émetteur, déjà servi). Base du temps réel multi-user (#65). Ne lève jamais (best-effort). */
  private void pushToGuild(ServerGuild g, long exceptUserID, GruntMessage msg) {
    if (g == null || g.memberIDs == null) return;
    for (Long mid : g.memberIDs) {
      if (mid == null || mid == exceptUserID) continue;
      GruntConnection oc = online.get(mid);
      if (oc == null) continue;
      try { oc.send(msg); System.out.println("[login]     ↳ push à membre en ligne " + mid); }
      catch (Throwable t) { System.out.println("[login]     ! push membre " + mid + ": " + t); }
    }
  }

  /** Toutes les classes de message du jeu (dérivées du registre MessageFactory.messageIndex). */
  @SuppressWarnings("unchecked")
  private static Set<Class<? extends GruntMessage>> allMessageClasses() {
    Set<Class<? extends GruntMessage>> out = new LinkedHashSet<>();
    try {
      Field f = MessageFactory.class.getDeclaredField("messageIndex");
      f.setAccessible(true);
      Map<Object, Object> idx = (Map<Object, Object>) f.get(null);
      for (Object key : idx.keySet()) {
        String full = String.valueOf(key);            // ex. "UpdateStats1"
        String base = full.replaceAll("\\d+$", "");    // retire la version en suffixe
        try {
          Class<?> c = Class.forName("com.perblue.heroes.network.messages." + base);
          if (GruntMessage.class.isAssignableFrom(c)) out.add((Class<? extends GruntMessage>) c);
        } catch (Throwable ignore) { /* nom non résoluble → ignoré */ }
      }
    } catch (Throwable t) { System.out.println("[login] enum messages échec: " + t); }
    return out;
  }

  public void start() throws Exception {
    final Executor exec = Executors.newCachedThreadPool();
    final Set<Class<? extends GruntMessage>> msgClasses = allMessageClasses();
    System.out.println("[login] " + msgClasses.size() + " classes de message enregistrées (log)");

    GruntConnectionListener listener = new GruntConnectionListener() {
      public void onOpen(final GruntConnection conn) {
        System.out.println("[login] onOpen " + conn);
        connectionsAccepted.incrementAndGet();
        // Handler de LOG universel : journalise chaque message, et répond BootData au ClientInfo.
        GruntListener<GruntMessage> logger = new GruntListener<GruntMessage>() {
          public void onReceive(GruntConnection c, GruntMessage m) {
            String name = m.getFullName();
            System.out.println("[login] <== " + name);
            // MULTI-USER (#65) — RÉSOUT le compte de CETTE connexion et SHADOW le champ `user` : tous les handlers
            // ci-dessous utilisent ce local (par connexion), pas le compte par défaut. À ClientInfo, on (re)charge
            // le compte depuis ClientInfo.userID (identité fournie par le client, comme le vrai serveur) et on
            // l'enregistre (connUsers + online) pour le PUSH temps réel. Repli = compte par défaut (pilote DEV,
            // et 1ᵉ message avant ClientInfo). Le shadow rend le refactor SÛR : aucune des ~140 références à `user`
            // n'est modifiée, le compilateur garantit la cohérence.
            ServerUser user = connUsers.getOrDefault(c, LoginServer.this.user);
            if (m instanceof ClientInfo) {
              long uid = ((ClientInfo) m).userID;
              if (uid > 0) {
                try {
                  user = store.loadOrCreate(uid, LoginServer.this.user.shardID);
                  connUsers.put(c, user);
                  online.put(uid, c);
                  System.out.println("[login] connexion ← compte " + uid + " (multi-user, "
                      + online.size() + " en ligne)");
                } catch (Exception e) { System.out.println("[login]     ! loadOrCreate(" + uid + "): " + e); }
              }
            }
            // ISOLATION TRANSPORT — un handler qui échoue ne DOIT PAS tuer la connexion. Sans ce garde, une
            // exception non rattrapée remonte au routeur NIO grunt qui FERME la socket → le keepalive Ping meurt
            // aussi → le client « Reconnecting… » (instabilité observée). On isole l'échec d'UN message du
            // transport : la session (Ping, autres écrans) survit. Ce N'EST PAS une rustine — on journalise
            // BRUYAMMENT la pile (un handler qui lève = un TROU à corriger, jamais masqué) ; et une REQUÊTE
            // restée sans réponse laissera son écran en attente, ce qui rend le trou VISIBLE en jeu aussi.
            try {
            if (m instanceof ClientInfo) {
              // CHALLENGES (#72) — AUTO-POPULATION du défi STARTER par le jeu (StickerHelper.setupStarterChallenges),
              // gaté Unlockable.CHALLENGES (TL20). Fait AVANT bootData() pour que l'écran « Sticker Challenges »
              // rende le défi en cours dès le boot ; persiste si l'état a changé (colonne challengeData). Idempotent.
              try {
                if (ServerChallenges.ensureSetup(user)) store.save(user);
              } catch (Throwable t) { System.out.println("[login]     ! setup défis (boot) échoué: " + t); }
              BootData bd = user.bootData();
              // GUILDES #7 — si le joueur est en guilde, LIVRER son GuildInfo au boot (bd.guildInfo). Sans ça le
              // client sait « en guilde » (guildID>0 persisté) mais sans données de guilde → écran vide au
              // démarrage tant qu'il n'a pas re-demandé. Chargé depuis le store (état persistant multi-serveur).
              ServerGuild bootGuild = null;
              if (user.inGuild()) {
                try {
                  bootGuild = store.loadGuild(user.shardID, user.currentGuildID());
                  if (bootGuild != null) bd.guildInfo = bootGuild.info;
                } catch (Exception e) { System.out.println("[login]     ! chargement guilde (boot) échoué: " + e); }
              }
              bd.setAsReplyTo(m);
              c.send(bd);
              System.out.println("[login] ==> BootData (reply) : "
                  + bd.individualUserExtra.tutorialActs.size() + " actes de tuto");
              // CHAT de guilde (#59) : livrer l'historique du salon GUILD dès le boot via le message SocialHistory
              // DÉDIÉ (le client le met en tampon jusqu'à la fin du BootData, évitant que son reset() l'efface).
              if (bootGuild != null) {
                try {
                  com.perblue.heroes.network.messages.SocialHistory sh = user.buildGuildSocialHistory(bootGuild);
                  c.send(sh);
                  System.out.println("[login] ==> SocialHistory (GUILD chat) : "
                      + bootGuild.guildChatWire.size() + " message(s) d'historique");
                } catch (Exception e) { System.out.println("[login]     ! SocialHistory (boot) échoué: " + e); }
              }
              // INVASION (#69) — POUSSER l'invasion courante DÈS LE BOOT quand il y en a une.
              //
              // ⚠️ Manque RÉEL trouvé EN JEU (2026-08-02) : le client refusait la navigation vers INVASION
              // (`UINavHelper.canNavigateTo` = false) alors que la feature est bien déverrouillée
              // (`Unlockables.isUnlocked` = true, TL 100 ≥ 60) et que le calendrier était dans la fenêtre.
              // Cause : `InvasionHelper.getActiveInvasion()` rendait `null` — le client ne connaît l'invasion
              // que par le message `InvasionInfo`, qu'il ne demandait jamais puisqu'il faut déjà être sur
              // l'écran pour l'envoyer. Poule et œuf : le VRAI backend la pousse au login, comme
              // `SocialHistory` pour le chat. On fait pareil.
              sendInvasionInfo(c, user, null);
              // MARCHANDS (#72 incr. 1b) : le stock est POUSSÉ après le REFRESH_SPECIAL_EVENTS post-boot (et non
              // dans la rafale de boot) — sinon le reset() du BootData côté client efface les marchands appliqués
              // (ils vivent sur l'IndividualUser que le BootData reconstruit ; cf. SocialHistory tamponné). Voir
              // le handler REFRESH_SPECIAL_EVENTS plus bas.
            } else if (m instanceof ChangeTutorialStep) {
              // Progression du tutoriel : le serveur est autoritaire → on met à jour l'état ET on
              // PERSISTE (SQLite, octets wire). Fire-and-forget côté client (aucune réponse attendue).
              ChangeTutorialStep cts = (ChangeTutorialStep) m;
              boolean applied = user.applyTutorialStep(cts);
              if (applied) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance échouée: " + e); } }
              System.out.println("[login]     tuto " + cts.type + " -> step " + cts.step
                  + (cts.forceSkip ? " (forceSkip)" : "") + (applied ? " [persisté]" : " [type inconnu, ignoré]"));
            } else if (m instanceof BuyChests) {
              // Ouverture de coffre : le serveur EXÉCUTE la logique du jeu (roll table + give) sur
              // l'état autoritatif, répond LootResults (le client applique de son côté), et persiste.
              try {
                BuyChests bc = (BuyChests) m;
                LootResults lr = user.openChest(bc);
                lr.setAsReplyTo(m);
                c.send(lr);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] ==> LootResults : coffre " + bc.chestType
                    + " -> " + lr.heroesUnlocked.size() + " héros débloqué(s), joueur en possède "
                    + user.heroCount() + " [persisté]");
              } catch (Throwable t) {
                // ClientErrorCodeException (checkée, non déclarée throws par dex2jar) = REFUS anti-triche
                // (validateChestPurchase) : ouverture illégitime (coffre gratuit hors cooldown & pas de monnaie,
                // feature/team-level verrouillé, limite d'achats…). On N'ACCORDE RIEN, on ne persiste pas — pas
                // une erreur serveur (un client légitime n'y arrive jamais). Autre throwable = vraie erreur.
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ BuyChests REFUSÉ (anti-triche) : " + t.getMessage()
                      + " — aucun coffre accordé");
                } else {
                  System.out.println("[login]     ! openChest échec: " + t);
                  t.printStackTrace();
                }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.PurchaseMerchantItem) {
              // MARCHAND (#72 incr. 2) — ACHAT d'un objet. Le serveur EXÉCUTE MerchantHelper.purchaseItem sur
              // l'état autoritatif (anti-triche : objet dans le stock serveur + non acheté ; coût recalculé et
              // vérifié anti-tamper contre l'expectedCost du client ; débit + don + marque purchased), persiste,
              // et RE-POUSSE le MerchantUpdate mis à jour (flag purchased) pour re-synchroniser le client.
              try {
                com.perblue.heroes.network.messages.PurchaseMerchantItem pm =
                    (com.perblue.heroes.network.messages.PurchaseMerchantItem) m;
                com.perblue.heroes.network.messages.MerchantData data = user.applyPurchaseMerchantItem(pm);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance achat marchand échouée: " + e); }
                com.perblue.heroes.network.messages.MerchantUpdate mu = new com.perblue.heroes.network.messages.MerchantUpdate();
                mu.type = pm.merchantType; mu.data = data; mu.reason = 0;
                c.send(mu);
                System.out.println("[login] ==> achat marchand " + pm.merchantType + " " + pm.itemToPurchase.itemType
                    + " appliqué [persisté] + MerchantUpdate re-poussé");
              } catch (Throwable t) {
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ PurchaseMerchantItem REFUSÉ (anti-triche) : " + t.getMessage());
                } else { System.out.println("[login]     ! achat marchand échec: " + t); t.printStackTrace(); }
              }
            } else if (m instanceof CampaignAttack) {
              // Combat de campagne : le client a joué le combat (client-side) et envoie l'issue
              // (fire-and-forget). Le serveur AUTORITATIF ré-exécute recordOutcome (stamina, loot/gold/
              // XP, progression) sur son état et persiste. Pas de réponse (aucun listener client).
              try {
                CampaignAttack ca = (CampaignAttack) m;
                user.recordCampaignAttack(ca);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                // ÉCONOMIE D'INFLUENCE (#54) — la stamina brûlée fait gagner de l'influence à la guilde (passif).
                long inflGain = 0;
                if (user.inGuild()) {
                  ServerGuild g = currentGuild(user);
                  if (g != null) {
                    inflGain = user.applyStaminaBurnInfluence(g, ca.campaignType, ca.chapter, ca.level);
                    if (inflGain > 0) { store.saveGuild(g);
                      com.perblue.heroes.network.messages.GuildInfluenceDiff idf =
                          new com.perblue.heroes.network.messages.GuildInfluenceDiff();
                      idf.guildID = g.guildID; idf.influence = g.info.influence; c.send(idf); }
                  }
                }
                System.out.println("[login] <== CampaignAttack : " + ca.campaignType + " " + ca.chapter
                    + "-" + ca.level + " outcome=" + (ca.base == null ? "?" : ca.base.outcome)
                    + " → recordOutcome appliqué [persisté]"
                    + (inflGain > 0 ? " (+" + inflGain + " influence guilde)" : ""));
              } catch (Throwable t) {
                System.out.println("[login]     ! recordCampaignAttack échec: " + t);
                t.printStackTrace();
              }
            } else if (m instanceof com.perblue.heroes.network.messages.DifficultyModeAttack) {
              // PORT (#72) — combat d'un mode « difficulty » (PORT_DOCKS/PORT_WAREHOUSE, etc.). Le client a joué
              // (client-autoritatif) et envoie l'issue (fire-and-forget). Le serveur ré-exécute DifficultyModeHelper
              // .recordOutcome (anti-triche : ouvert/cooldown/quota ; crédit butin + cooldown + uses + progression)
              // et persiste. Anti-triche = ClientErrorCodeException (fermé/cooldown) → on n'accorde rien.
              try {
                com.perblue.heroes.network.messages.DifficultyModeAttack da = (com.perblue.heroes.network.messages.DifficultyModeAttack) m;
                user.recordDifficultyModeAttack(da);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== DifficultyModeAttack : " + da.gameMode + " diff=" + da.modeDifficulty
                    + " outcome=" + (da.base == null ? "?" : da.base.outcome) + " → recordOutcome appliqué [persisté]");
              } catch (Throwable t) {
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ DifficultyModeAttack REFUSÉ (anti-triche) : " + t.getMessage());
                } else { System.out.println("[login]     ! recordDifficultyModeAttack échec: " + t); t.printStackTrace(); }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.RaidDifficultyMode) {
              // PORT (#72) incr. 2 — RAID d'un mode « difficulty ». Le client a validé+chargé en local (useRaidTickets)
              // puis crédité (recordRaidOutcome) et envoie l'issue (fire-and-forget). Le serveur AUTORITATIF ré-exécute
              // useRaidTickets (anti-triche ouvert/cooldown/quota + gate 3★ + débit RAID_TICKET) PUIS recordRaidOutcome
              // (crédit butin + XP + compteurs + cooldown) et persiste. Anti-triche = ClientErrorCodeException.
              try {
                com.perblue.heroes.network.messages.RaidDifficultyMode rd =
                    (com.perblue.heroes.network.messages.RaidDifficultyMode) m;
                user.recordRaidDifficultyMode(rd);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== RaidDifficultyMode : " + rd.gameMode + " diff=" + rd.modeDifficulty
                    + " ×" + (rd.outcomes == null ? 0 : rd.outcomes.size()) + " → recordRaidOutcome appliqué [persisté]");
              } catch (Throwable t) {
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ RaidDifficultyMode REFUSÉ (anti-triche) : " + t.getMessage());
                } else { System.out.println("[login]     ! recordRaidDifficultyMode échec: " + t); t.printStackTrace(); }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.CodebaseAttack) {
              // CODEBASE (« The Codebase ») — combat du mode de difficulté CODEBASE. Le client a joué (client-autoritatif) et
              // envoie l'issue (fire-and-forget). Le serveur ré-exécute CodebaseHelper.recordOutcome (anti-triche : chapitre 41 /
              // ouvert / héros jaune / quota / cooldown ; crédit butin ; high scores per-user write-through ; hook contest), puis
              // insère l'attaque dans le CLASSEMENT/journal per-shard serveur-autoritatif (topScores + recent), et persiste.
              // Anti-triche = ClientErrorCodeException → on n'accorde rien. codebaseID (message) = l'ID d'itération courant.
              try {
                com.perblue.heroes.network.messages.CodebaseAttack cba = (com.perblue.heroes.network.messages.CodebaseAttack) m;
                com.perblue.heroes.network.messages.CodebaseAttackInfo entry = user.recordCodebaseAttack(cba);
                try {
                  com.perblue.heroes.network.messages.CodebaseAttackLogs logs = ServerCodebase.loadLogs(store, user.shardID);
                  ServerCodebase.recordAttack(logs, cba.weakness, entry.lineup, entry.rageLevel, entry.score, entry.attackTime);
                  ServerCodebase.saveLogs(store, user.shardID, logs);
                } catch (Exception e) { System.out.println("[login]     ! journal codebase non mis à jour: " + e); }
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== CodebaseAttack : iter=" + cba.codebaseID + " score=" + cba.finalScore
                    + " rage=" + entry.rageLevel + " outcome=" + (cba.base == null ? "?" : cba.base.outcome)
                    + " → recordOutcome + classement [persisté]");
              } catch (Throwable t) {
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ CodebaseAttack REFUSÉ (anti-triche) : " + t.getMessage());
                } else { System.out.println("[login]     ! recordCodebaseAttack échec: " + t); t.printStackTrace(); }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetCodebaseAttackLogs) {
              // CODEBASE — CodebaseAttackLogScreen : classement/journal (top scores + attaques récentes) par itération. Réponse
              // serveur-autoritative depuis le blob per-shard. Sans ce handler l'écran restait sur LOADING (gap A2). Vide = fidèle
              // (aucune attaque encore enregistrée sur ce shard).
              com.perblue.heroes.network.messages.CodebaseAttackLogs logs = ServerCodebase.loadLogs(store, user.shardID);
              logs.setAsReplyTo(m);
              c.send(logs);
              System.out.println("[login] <== GetCodebaseAttackLogs → ==> CodebaseAttackLogs ("
                  + (logs.logs == null ? 0 : logs.logs.size()) + " itération(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.TrialEventAttack) {
              // FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 3 — combat d'un nœud de trial. Le client a joué (client-autoritatif)
              // et envoie l'issue (fire-and-forget). Le serveur ré-exécute BaseEventTrialNode.recordOutcome (anti-triche :
              // chances/resets restants ; avance le statut du nœud [étoiles] ; consomme une chance ; crédite les récompenses),
              // reflète le statut calculé dans le blob TrialEventData serveur-autoritatif et persiste. Anti-triche =
              // ClientErrorCodeException (plus de chance / event fermé) → on n'accorde rien.
              try {
                com.perblue.heroes.network.messages.TrialEventAttack ta = (com.perblue.heroes.network.messages.TrialEventAttack) m;
                user.recordTrialEventAttack(ta);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== TrialEventAttack : event=" + ta.eventID + " sous-trial=" + ta.subtrialNumber
                    + " nœud=" + ta.nodeNumber + " outcome=" + (ta.base == null ? "?" : ta.base.outcome)
                    + " → recordOutcome appliqué [persisté]");
              } catch (Throwable t) {
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ TrialEventAttack REFUSÉ (anti-triche) : " + t.getMessage());
                } else { System.out.println("[login]     ! recordTrialEventAttack échec: " + t); t.printStackTrace(); }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.FriendshipCampaignAttack) {
              // FRIENDSHIPS #72 incr. 3b — combat de CAMPAGNE D'AMITIÉ (MISSIONS). Le client a joué le combat
              // (client-autoritatif) et envoie l'issue (fire-and-forget). Le serveur AUTORITATIF ré-exécute
              // FriendshipCampaignHelper.recordOutcome (valide FRIEND_STAMINA/déblocage/canUseHeroes, progresse le
              // nœud, pose lastBattle, crédite loot+XP) puis persiste (resyncFriendships). Pas de réponse.
              try {
                com.perblue.heroes.network.messages.FriendshipCampaignAttack fca =
                    (com.perblue.heroes.network.messages.FriendshipCampaignAttack) m;
                user.recordFriendCampaignAttack(fca);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== FriendshipCampaignAttack : pair="
                    + com.perblue.heroes.game.objects.FriendPairID.from(fca.friendPairID) + " node=" + fca.nodeNumber
                    + " outcome=" + (fca.base == null ? "?" : fca.base.outcome) + " → recordOutcome appliqué [persisté]");
              } catch (Throwable t) {
                System.out.println("[login]     ! recordFriendCampaignAttack échec: " + t); t.printStackTrace();
              }
            } else if (m instanceof com.perblue.heroes.network.messages.RaidCampaign) {
              // RAID d'un niveau (ELITE_CAMPAIGN / raid d'un niveau) : le client a rejoué le niveau raidCount
              // fois en local (charge tickets/énergie + record) et envoie l'issue (fire-and-forget). Le serveur
              // AUTORITATIF REJOUE chargeForRaid + recordRaidOutcome sur son état et persiste. Pas de réponse.
              try {
                com.perblue.heroes.network.messages.RaidCampaign rc =
                    (com.perblue.heroes.network.messages.RaidCampaign) m;
                user.recordRaidCampaign(rc);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== RaidCampaign : " + rc.campaignType + " " + rc.chapter
                    + "-" + rc.level + " ×" + rc.raidCount + " → recordRaidOutcome appliqué [persisté]");
              } catch (Throwable t) {
                System.out.println("[login]     ! recordRaidCampaign échec: " + t);
                t.printStackTrace();
              }
            } else if (m instanceof com.perblue.heroes.network.messages.RaidAllCampaign) {
              // RAID ALL (plusieurs niveaux en une fois) : results = List<RaidCampaign>. Même traitement, en boucle.
              try {
                com.perblue.heroes.network.messages.RaidAllCampaign ra =
                    (com.perblue.heroes.network.messages.RaidAllCampaign) m;
                user.recordRaidAllCampaign(ra);
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); }
                System.out.println("[login] <== RaidAllCampaign : "
                    + (ra.results == null ? 0 : ra.results.size()) + " niveau(x) → appliqué [persisté]");
              } catch (Throwable t) {
                System.out.println("[login]     ! recordRaidAllCampaign échec: " + t);
                t.printStackTrace();
              }
            } else if (m instanceof Action) {
              // Commande générique du jeu (équiper, promouvoir, vendre…). La plupart sont fire-and-forget
              // (le client applique de son côté) ; certaines sont des REQUÊTES attendant une réponse.
              Action act = (Action) m;
              System.out.println("[login] <== Action : command=" + act.command
                  + " hero=" + act.heroType + " item=" + act.itemType + " iD=" + act.iD
                  + " extra=" + (act.extra == null ? "{}" : act.extra));
              // REFRESH_SPECIAL_EVENTS = REQUÊTE (pas fire-and-forget) : le client attend un SpecialEventsRaw
              // (events + signinRewards). Sans réponse il RE-DEMANDE en boucle (observé 524×) et cale au
              // SignInScreen. On répond avec l'état d'évènements du serveur — aucun évènement hébergé (§F du
              // SERVER_PLAN) → liste vide, changed=false — pour débloquer le client (il appelle
              // SpecialEventsHelper.setSpecialEvents sur la réponse).
              if (act.command == com.perblue.heroes.network.messages.CommandType.REFRESH_SPECIAL_EVENTS) {
                com.perblue.heroes.network.messages.SpecialEventsRaw raw =
                    new com.perblue.heroes.network.messages.SpecialEventsRaw();
                // SPECIAL_EVENTS (live-ops) : on POUSSE au client les événements opérateur (sérialisés par le JEU via
                // ServerEvents.toRaw → SpecialEventInfo.toJson). Le client les re-parse (buildEvent) et AFFICHE leurs
                // effets (ex. PORT_WAREHOUSE ouvert dans PortChooserScreen). Cf. docs/SPECIAL_EVENTS.md incr. 2.
                raw.changed = true;
                try {
                  raw.events = ServerEvents.toRaw(ServerEvents.bootDefaultEvents()).events;
                } catch (Throwable t) {
                  System.out.println("[login]     ! events toRaw échec: " + t); raw.events = new java.util.ArrayList<>();
                }
                // Récompenses de connexion quotidienne (bâtiment SIGN IN) : construites depuis la table du
                // jeu (signin_rewards.tab) — le client applique via SigninHelper.setData. Cf. ServerUser.
                try {
                  raw.signinRewards = user.buildSigninRewards();
                } catch (Throwable t) {
                  System.out.println("[login]     ! buildSigninRewards échec: " + t);
                }
                raw.setAsReplyTo(m);
                c.send(raw);
                int nDays = raw.signinRewards == null || raw.signinRewards.thisMonth == null
                    || raw.signinRewards.thisMonth.rewards == null ? 0
                    : raw.signinRewards.thisMonth.rewards.size();
                System.out.println("[login]     ==> SpecialEventsRaw (reply, "
                    + (raw.events == null ? 0 : raw.events.size()) + " évènement(s), "
                    + nDays + " jours de sign-in)");
                // MARCHANDS (#72 incr. 1b) — POUSSER le stock ICI (post-boot, user client stabilisé → survit au
                // reset du BootData). Génère si absent (persiste), sinon réutilise le blob persisté. Le client
                // applique via GameMain→MerchantUpdate (initMerchantData + updateUI). Idempotent.
                try {
                  java.util.List<com.perblue.heroes.network.messages.MerchantUpdate> mus = user.bootMerchantUpdates();
                  for (com.perblue.heroes.network.messages.MerchantUpdate mu : mus) c.send(mu);
                  if (!mus.isEmpty()) { try { store.save(user); } catch (Exception e) {
                    System.out.println("[login]     ! persistance marchands échouée: " + e); } }
                  System.out.println("[login]     ==> MerchantUpdate x" + mus.size() + " (stock marchands)");
                } catch (Throwable t) { System.out.println("[login]     ! push marchands échoué: " + t); }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.START_FIGHT_PIT_ATTACK
                  || act.command == com.perblue.heroes.network.messages.CommandType.START_COLISEUM_ATTACK) {
                // ARÈNE #44 — START d'attaque : le client ATTEND Start(Arena|Coliseum)AttackResponse (héros du
                // défenseur) sinon il reste figé sur « LOADING… » (observé en jeu). defenderID lu dans extra.ID.
                com.perblue.heroes.network.messages.ArenaType at =
                    act.command == com.perblue.heroes.network.messages.CommandType.START_COLISEUM_ATTACK
                        ? com.perblue.heroes.network.messages.ArenaType.COLISEUM
                        : com.perblue.heroes.network.messages.ArenaType.FIGHT_PIT;
                long defID = -1;
                try {
                  Object ido = act.extra == null ? null
                      : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
                  if (ido != null) defID = Long.parseLong(ido.toString());
                } catch (Throwable t) { /* ID absent/illisible → -1 (repli) */ }
                ServerArenaLadder ladder = loadOrCreateLadder(user, at);
                com.perblue.grunt.translate.GruntMessage resp = user.startArenaAttack(at, defID, ladder, oppSrc);
                resp.setAsReplyTo(m);
                c.send(resp);
                System.out.println("[login] <== START " + at + " attaque défenseur=" + defID
                    + " → ==> Start*AttackResponse (héros du défenseur envoyés)");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.RESET_TRIAL_EVENT_PAID) {
                // FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 4 — RESET PAYANT d'un trial (Command). Le client envoie
                // Action{command=RESET_TRIAL_EVENT_PAID, extra={ID:eventID, COST:cost}}. Le serveur EXÉCUTE la logique DU JEU
                // (TrialsHelper.resetTrialEvent) : anti-triche (quota resets payants / chances encore dispo → lève) + débit
                // (chargeUser) + doPaidReset (paidChancesRemaining). Anti-triche = ClientErrorCodeException → rien accordé.
                long evID = -1;
                try {
                  Object ido = act.extra == null ? null
                      : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ID);
                  if (ido != null) evID = Long.parseLong(ido.toString());
                } catch (Throwable t) { /* ID absent/illisible → -1 */ }
                try {
                  user.resetTrialEventPaid(evID);
                  try { store.save(user); } catch (Exception e) {
                    System.out.println("[login]     ! persistance échouée: " + e); }
                  System.out.println("[login] <== RESET_TRIAL_EVENT_PAID : event=" + evID + " → resetTrialEvent appliqué [persisté]");
                } catch (Throwable t) {
                  if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                    System.out.println("[login]     ⛔ RESET_TRIAL_EVENT_PAID REFUSÉ (anti-triche) : " + t.getMessage());
                  } else { System.out.println("[login]     ! resetTrialEventPaid échec: " + t); t.printStackTrace(); }
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_GUILD_CHECK_IN_INFO) {
                // GUILD CHECK-IN — état d'émargement du jour (écran CHECK IN attend un GuildCheckInInfo, sinon LOADING).
                ServerGuild g = currentGuild(user);
                com.perblue.heroes.network.messages.GuildCheckInInfo ci = user.buildGuildCheckInInfo(g);
                ci.setAsReplyTo(m);
                c.send(ci);
                System.out.println("[login] <== GET_GUILD_CHECK_IN_INFO → ==> GuildCheckInInfo (today="
                    + ci.totalCheckInsToday + "/" + ci.maxCheckInsToday + ")");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_HEROES_FOR_HIRE) {
                // MERCENAIRES (#57) — pool RÉEL : héros postés par tous les membres de la guilde.
                Object ep = act.extra == null ? null
                    : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ENTRY_POINT);
                com.perblue.heroes.network.messages.HeroesForHire hf =
                    buildHeroesForHire(user, ep != null && Boolean.parseBoolean(ep.toString()));
                hf.setAsReplyTo(m);
                c.send(hf);
                System.out.println("[login] <== GET_HEROES_FOR_HIRE → ==> HeroesForHire (" + hf.mercenaries.size() + " merc)");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.POST_HERO) {
                // MERCENAIRES (#57) — poster un héros à louer (Action POST_HERO, heroType). Ajoute au pool, persiste,
                // renvoie le pool à jour.
                com.perblue.heroes.network.messages.UnitType t = act.heroType;
                if (!user.inGuild()) {
                  System.out.println("[login]     ⛔ POST_HERO : joueur sans guilde");
                } else {
                  try {
                    user.postMercenary(t);
                    try { store.save(user); } catch (Exception e) { System.out.println("[login]     ! save: " + e); }
                    com.perblue.heroes.network.messages.HeroesForHire hf = buildHeroesForHire(user, false);
                    hf.setAsReplyTo(m);
                    c.send(hf);
                    System.out.println("[login] <== POST_HERO " + t + " → pool " + hf.mercenaries.size() + " merc [persisté]");
                  } catch (Throwable tt) {
                    if (tt instanceof com.perblue.heroes.ClientErrorCodeException)
                      System.out.println("[login]     ⛔ POST_HERO REFUSÉ : " + tt.getMessage());
                    else { System.out.println("[login]     ! postMercenary échec: " + tt); tt.printStackTrace(); }
                  }
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.HIRE_HERO) {
                // MERCENAIRES (#57) — louer un héros du pool (Action HIRE_HERO : heroType, ID=ownerID, LEVEL=mode).
                // On renvoie HeroHired{hero} pour l'utiliser en combat ET on CRÉDITE le POSTEUR de ses Social Bucks
                // (getHiredMercenaryReward), persisté — correctif multi-serveur (#64). Le coût GOLD à l'emprunt
                // (MercenaryHeroData.cost) est fixé à la construction du pool (opérateur), non simulé ici.
                com.perblue.heroes.network.messages.UnitType t = act.heroType;
                long ownerID = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                com.perblue.heroes.network.messages.MercenaryHeroData picked = null;
                ServerUser owner = null;
                try {
                  owner = ownerID == user.userID ? user : store.loadIfExists(ownerID, user.shardID);
                  if (owner != null) for (com.perblue.heroes.network.messages.MercenaryHeroData md : owner.postedMercenaries())
                    if (md.heroData != null && md.heroData.type == t) { picked = md; break; }
                } catch (Exception e) { System.out.println("[login]     ! HIRE_HERO load owner: " + e); }
                if (picked == null) {
                  System.out.println("[login]     ⛔ HIRE_HERO : mercenaire " + t + " (owner " + ownerID + ") introuvable");
                } else {
                  picked.hireTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
                  picked.hiredByName = user.basicInfo() == null ? "" : user.basicInfo().name;
                  com.perblue.heroes.network.messages.HeroHired hh = new com.perblue.heroes.network.messages.HeroHired();
                  hh.hero = picked;
                  hh.setAsReplyTo(m);
                  c.send(hh);
                  // Crédite le POSTEUR (Social Bucks) — sauf s'il se loue lui-même (owner == hirer).
                  int mercReward = 0;
                  if (owner != null && ownerID != user.userID) {
                    try { mercReward = owner.creditMercenaryHireReward(); store.save(owner); }
                    catch (Exception e) { System.out.println("[login]     ! crédit posteur merc: " + e); }
                  }
                  System.out.println("[login] <== HIRE_HERO " + t + " (owner " + ownerID + ") → ==> HeroHired"
                      + (mercReward > 0 ? " (+"+mercReward+" Social Bucks au posteur [persisté])" : ""));
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_GUILD_RANKINGS) {
                // CLASSEMENT DES GUILDES (#60) — leaderboard RÉEL du shard : toutes les guildes triées par la valeur
                // de la métrique demandée (RankType → champ de GuildInfo), + ton rang/valeur.
                com.perblue.heroes.network.messages.GuildRankings gr =
                    new com.perblue.heroes.network.messages.GuildRankings();
                Object rt = act.extra == null ? null
                    : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                try { gr.rankType = rt == null ? com.perblue.heroes.network.messages.RankType.TOTAL_POWER
                    : com.perblue.heroes.network.messages.RankType.valueOf(rt.toString()); }
                catch (Throwable t) { gr.rankType = com.perblue.heroes.network.messages.RankType.TOTAL_POWER; }
                final com.perblue.heroes.network.messages.RankType rkt = gr.rankType;
                java.util.List<ServerGuild> all = store.listGuilds(user.shardID, null, 200);
                all.sort((x, y) -> Long.compare(rankValue(y.info, rkt), rankValue(x.info, rkt)));   // décroissant
                gr.topGuilds = new java.util.ArrayList<>();
                gr.yourGuildRank = 0; gr.yourGuildValue = 0L;
                long myGid = user.currentGuildID();
                int idx = 0;
                for (ServerGuild sg : all) {
                  idx++;
                  if (gr.topGuilds.size() < 100) {
                    com.perblue.heroes.network.messages.GuildRow row = new com.perblue.heroes.network.messages.GuildRow();
                    row.guildInfo = sg.info; gr.topGuilds.add(row);
                  }
                  if (sg.guildID == myGid) { gr.yourGuildRank = idx; gr.yourGuildValue = rankValue(sg.info, rkt); }
                }
                gr.setAsReplyTo(m);
                c.send(gr);
                System.out.println("[login] <== GET_GUILD_RANKINGS(" + gr.rankType + ") → ==> GuildRankings ("
                    + gr.topGuilds.size() + " guilde(s), ton rang " + gr.yourGuildRank + "/" + all.size()
                    + " valeur " + gr.yourGuildValue + ")");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_GUILD_CONTEST_RANKINGS) {
                // CONTEST DE GUILDE — l'action porte l'ID du contest (extra ID). Si l'ID = un contest de GUILDE actif du
                // composant SPECIAL_EVENTS `Contest` → leaderboard agrégé serveur-autoritatif (somme des points des membres,
                // gap C) ; sinon fallback #67 (guildes triées par GuildInfo.contestPoints).
                long gContestID = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                com.perblue.heroes.network.messages.GuildContestRankings gc =
                    activeGuildContest(user, gContestID) != null
                        ? ServerContestData.guildRankings(store, user, gContestID)
                        : buildGuildContestRankings(user);
                gc.setAsReplyTo(m);
                c.send(gc);
                System.out.println("[login] <== GET_GUILD_CONTEST_RANKINGS(id=" + gContestID + ") → ==> GuildContestRankings ("
                    + gc.topGuilds.size() + " guilde(s), ta guilde "
                    + (gc.yourGuildInfo == null ? "-" : ("score " + gc.yourGuildInfo.points + " rang " + gc.yourGuildInfo.rank)) + ")");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_CONTEST_RANKINGS) {
                // CONTEST DES JOUEURS — l'action porte l'ID du contest (extra ID). Deux régimes :
                //  (a) contest SOLO du composant SPECIAL_EVENTS `Contest` (leaderboard SERVEUR-AUTORITATIF, ladder per-shard) ;
                //  (b) sinon fallback #67 (membres de la guilde triés par leurs points de contest de guilde).
                long contestID = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                com.perblue.heroes.network.messages.ContestRankings cr = buildContestRankings(user, contestID);
                cr.setAsReplyTo(m);
                c.send(cr);
                System.out.println("[login] <== GET_CONTEST_RANKINGS(id=" + contestID + ") → ==> ContestRankings ("
                    + cr.topPlayers.size() + " joueur(s), ton score " + (cr.yourInfo == null ? 0 : cr.yourInfo.points)
                    + " rang " + (cr.yourInfo == null ? 0 : cr.yourInfo.rank) + ")");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.UPGRADE_GUILD_PERK) {
                // PERK — upgrade autoritatif (débit influence de guilde + niveau+1). Le type de perk est dans extra.
                System.out.println("[login]     UPGRADE_GUILD_PERK extra=" + act.extra);
                ServerGuild g = currentGuild(user);
                com.perblue.heroes.network.messages.GuildPerkType type = parseGuildPerkType(act);
                int newLvl = (g == null || type == null) ? -1 : user.upgradeGuildPerk(g, type);
                if (newLvl < 0) {
                  System.out.println("[login]     ⛔ UPGRADE_GUILD_PERK refusé (type=" + type + ")");
                } else {
                  store.saveGuild(g);
                  com.perblue.heroes.network.messages.GuildPerkUpgraded up =
                      new com.perblue.heroes.network.messages.GuildPerkUpgraded();
                  up.guildID = g.guildID; up.perk = type; up.perkLevel = newLvl; up.influence = g.info.influence;
                  up.setAsReplyTo(m);
                  c.send(up);
                  com.perblue.heroes.network.messages.GuildInfluenceDiff idf =
                      new com.perblue.heroes.network.messages.GuildInfluenceDiff();
                  idf.guildID = g.guildID; idf.influence = g.info.influence;
                  c.send(idf);
                  System.out.println("[login] <== UPGRADE_GUILD_PERK " + type + " → niv." + newLvl
                      + " [persisté] ==> GuildPerkUpgraded + GuildInfluenceDiff");
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.ACTIVATE_TIMED_GUILD_PERK) {
                // PERK TEMPORISÉ (#54) — active un perk timed (boost temporaire). extra[TYPE]=perk, COUNT=quantité.
                ServerGuild g = currentGuild(user);
                com.perblue.heroes.network.messages.GuildPerkType type = parseGuildPerkType(act);
                int amount = (int) extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.COUNT, 1);
                boolean ok = (g != null && type != null) && user.activateTimedGuildPerk(g, type, amount);
                if (ok) {
                  store.saveGuild(g);
                  com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                      g, user.currentGuildRole(), com.perblue.heroes.network.messages.GuildUpdateReason.DEFAULT);
                  up.setAsReplyTo(m);
                  c.send(up);
                  System.out.println("[login] <== ACTIVATE_TIMED_GUILD_PERK " + type + " ×" + amount + " [persisté]");
                } else {
                  System.out.println("[login]     ⛔ ACTIVATE_TIMED_GUILD_PERK refusé (type=" + type + ")");
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.INVASION_CLAIM_GUILD_RANK_REWARD) {
                // INVASION #69 — récompenses de RANG DE LIGUE (fin d'invasion). Le rang vient du classement
                // SERVEUR (somme des points des membres pour la guilde) ; les récompenses sont tirées par la
                // logique du jeu (tables invasion_guild_rank_league_rewards). Anti-double-réclamation via le
                // drapeau hasGuildRankRewards de UserInvasionData, re-persisté après l'appel.
                long rnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
                long rinv = ServerInvasion.rotation(ServerInvasion.invasionStart(rnow));
                try {
                  com.perblue.heroes.network.messages.UserInvasionData rud = ServerInvasion.loadOrResetUserData(
                      store.loadUserInvasion(user.shardID, user.userID), user.userID, user.currentGuildID(), rinv);
                  // Rang de MA guilde dans le classement d'invasion.
                  int myRank = 0;
                  long myGid = user.currentGuildID();
                  for (com.perblue.heroes.network.messages.InvasionRankingRow row
                      : ServerInvasion.guildRanking(store, user.shardID, rinv, 10000)) {
                    if (row.guild != null && row.guild.iD == myGid) { myRank = row.rank; break; }
                  }
                  if (myRank <= 0) {
                    System.out.println("[login]     ⛔ INVASION_CLAIM_GUILD_RANK_REWARD : guilde non classée");
                  } else {
                    com.perblue.heroes.network.messages.InvasionLeague lg =
                        rud.league == null ? com.perblue.heroes.network.messages.InvasionLeague.BRONZE : rud.league;
                    boolean ok = user.claimInvasionRankRewards(
                        ServerInvasionObject.at(rnow), rud, lg, myRank, true);
                    if (ok) {
                      store.saveUserInvasion(user.shardID, user.userID, ServerInvasion.userDataToBytes(rud));
                      store.save(user);
                    }
                    System.out.println("[login] <== INVASION_CLAIM_GUILD_RANK_REWARD rang=" + myRank
                        + " ligue=" + lg + " → " + (ok ? "récompenses créditées [persisté]"
                        : "rien à réclamer (déjà pris ou aucune récompense)"));
                  }
                } catch (Exception e) {
                  System.out.println("[login]     ! INVASION_CLAIM_GUILD_RANK_REWARD : " + e);
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.CLAIM_GUILD_GIFT_REWARDS) {
                // CADEAUX DE GUILDE (#58/#66) — RÉCLAME les cadeaux non encore pris par ce joueur (autoritatif) :
                // ServerUser.claimGuildGifts crédite les récompenses (RewardHelper.giveRewards) + avance la marque
                // anti-double-claim, persisté. Réponse GuildGiftRewardsUpdate avec les récompenses accordées.
                ServerGuild gg = currentGuild(user);
                com.perblue.heroes.network.messages.GuildGiftRewardsUpdate up =
                    new com.perblue.heroes.network.messages.GuildGiftRewardsUpdate();
                up.eventID = gg == null ? extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID, 0) : gg.giftEventID;
                java.util.List<com.perblue.heroes.network.messages.RewardDrop> got =
                    gg == null ? new java.util.ArrayList<>() : user.claimGuildGifts(gg);
                if (gg != null && !got.isEmpty()) {
                  try { store.saveGuild(gg); store.save(user); }
                  catch (Exception e) { System.out.println("[login]     ! persistance cadeaux: " + e); }
                }
                up.lastGiftTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
                up.newGifters = new java.util.ArrayList<>();
                up.newRewards = got;
                up.setAsReplyTo(m);
                c.send(up);
                System.out.println("[login] <== CLAIM_GUILD_GIFT_REWARDS → ==> GuildGiftRewardsUpdate ("
                    + got.size() + " récompense(s) créditée(s)" + (got.isEmpty() ? "" : " [persisté]") + ")");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.REQUEST_GUILD_DONATION) {
                // DONS / GUILD AID (#55) — le joueur demande de l'aide. extra[TYPE] = HERO_XP|SKILL_LEVEL|STAMINA.
                // #55a : STAMINA (pilotable sans navigation gear) ; HERO_XP/SKILL à venir (#55b, dérivation reward).
                Object tv = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                String reqType = tv == null ? "" : tv.toString();
                ServerGuild g = currentGuild(user);
                if (g == null) {
                  System.out.println("[login]     ! REQUEST_GUILD_DONATION : joueur sans guilde");
                } else if ("STAMINA".equals(reqType) || "SKILL_LEVEL".equals(reqType) || "HERO_XP".equals(reqType)) {
                  try {
                    com.perblue.heroes.network.messages.GuildDonationRequestRow row;
                    if ("STAMINA".equals(reqType)) {
                      row = user.postGuildStaminaRequest(g);
                    } else if ("HERO_XP".equals(reqType)) {
                      // HERO_XP (#63) : cible = act.heroType ; le don est dérivé de l'XP manquant du héros.
                      row = user.postGuildHeroXPRequest(g, act.heroType);
                    } else {
                      // SKILL_LEVEL (#63) : cible = act.heroType + slot dans extra[SKILL] (SkillSlot ou son nom).
                      com.perblue.heroes.network.messages.SkillSlot slot = parseSkillSlot(act);
                      row = user.postGuildSkillRequest(g, act.heroType, slot);
                    }
                    store.saveGuild(g);
                    try { store.save(user); } catch (Exception e) {
                      System.out.println("[login]     ! persistance joueur échouée: " + e); }
                    // Rafraîchit l'écran GUILD AID du demandeur (listener global GuildDonationRequests).
                    com.perblue.heroes.network.messages.GuildDonationRequests resp = user.buildGuildDonationRequests(g);
                    resp.setAsReplyTo(m);
                    c.send(resp);
                    System.out.println("[login] <== REQUEST_GUILD_DONATION " + reqType + " → demande #" + row.requestID
                        + " (" + row.totalRequestedDonations + " dons attendus, expire " + row.expiration
                        + ") [persisté] ==> GuildDonationRequests(" + resp.requests.size() + ")");
                  } catch (Throwable t) {
                    if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                      System.out.println("[login]     ⛔ REQUEST_GUILD_DONATION " + reqType + " REFUSÉ (anti-triche) : " + t.getMessage());
                    } else { System.out.println("[login]     ! postGuild"+reqType+"Request échec: " + t); t.printStackTrace(); }
                  }
                } else {
                  System.out.println("[login]     ~ REQUEST_GUILD_DONATION type=" + reqType + " inconnu");
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.CHECK_IN_TO_GUILD) {
                // CHECK-IN — émargement quotidien : crédite l'influence de guilde + récompenses au joueur (autoritatif,
                // 1×/jour horloge serveur). Persiste guilde + joueur. Répond GuildCheckInInfo (état mis à jour) +
                // UserGuildUpdate(DEFAULT) (influence de guilde rafraîchie).
                ServerGuild g = currentGuild(user);
                if (g == null) {
                  System.out.println("[login]     ! CHECK_IN_TO_GUILD : joueur sans guilde");
                } else {
                  java.util.List<com.perblue.heroes.network.messages.RewardDrop> rewards = user.checkInToGuild(g);
                  if (rewards == null) {
                    System.out.println("[login]     ⛔ CHECK_IN_TO_GUILD : déjà émargé aujourd'hui / non autorisé");
                  } else {
                    store.saveGuild(g);
                    try { store.save(user); } catch (Exception e) {
                      System.out.println("[login]     ! persistance joueur échouée: " + e); }
                    com.perblue.heroes.network.messages.GuildCheckInInfo ci = user.buildGuildCheckInInfo(g);
                    ci.setAsReplyTo(m);
                    c.send(ci);
                    com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                        g, user.currentGuildRole(), com.perblue.heroes.network.messages.GuildUpdateReason.DEFAULT);
                    c.send(up);
                    System.out.println("[login] <== CHECK_IN_TO_GUILD → émargé (" + ci.totalCheckInsToday + "/"
                        + ci.maxCheckInsToday + ", influence " + g.info.influence + ") [persisté]");
                  }
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.DISBAND_GUILD) {
                // DISSOLUTION — le RULER dissout la guilde (GuildHelper.canDisband). Efface l'appartenance de TOUS les
                // membres (chargés du store) + supprime la guilde. Répond UserGuildUpdate(DISBAND) au dissolveur.
                ServerGuild g = currentGuild(user);
                if (g == null) {
                  System.out.println("[login]     ! DISBAND_GUILD : joueur sans guilde");
                } else if (!com.perblue.heroes.game.logic.GuildHelper.canDisband(user.currentGuildRole())) {
                  System.out.println("[login]     ⛔ DISBAND_GUILD REFUSÉ : rôle " + user.currentGuildRole() + " insuffisant");
                } else {
                  long gid = g.guildID;
                  for (Long mid : new java.util.ArrayList<>(g.memberIDs)) {
                    ServerUser mu = mid == user.userID ? user : store.loadIfExists(mid, user.shardID);
                    if (mu != null) { mu.leaveGuild(); try { store.save(mu); } catch (Exception e) {} }
                  }
                  store.deleteGuild(user.shardID, gid);
                  com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                      null, com.perblue.heroes.network.messages.GuildRole.NONE,
                      com.perblue.heroes.network.messages.GuildUpdateReason.DISBAND);
                  up.setAsReplyTo(m);
                  c.send(up);
                  System.out.println("[login] <== DISBAND_GUILD #" + gid + " dissoute (" + g.memberCount()
                      + " membre(s) libérés) [persisté] ==> UserGuildUpdate(DISBAND)");
                }
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.VIEWED_GUILD_WALL
                  || act.command == com.perblue.heroes.network.messages.CommandType.VIEWED_CONTEST_POINTS) {
                // Marqueurs « vu » (pastille) — informationnels, pas d'état à modifier. On acquitte (pas de LOADING).
                System.out.println("[login]     action " + act.command + " (marqueur vu, no-op)");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.CHANGE_WAR_QUEUE) {
                // GUILD WAR (#68) — inscription / retrait de la file. Contrôles du client ré-exécutés.
                ServerGuild g = currentGuild(user);
                com.perblue.heroes.network.messages.WarQueueState want = enumExtra(act,
                    com.perblue.heroes.network.messages.ActionExtraType.TYPE,
                    com.perblue.heroes.network.messages.WarQueueState.class,
                    com.perblue.heroes.network.messages.WarQueueState.NOT_QUEUED);
                String err = g == null ? "joueur sans guilde"
                    : ServerWar.changeQueueState(g, user, want,
                        com.perblue.heroes.util.TimeUtil.serverTimeNow());
                if (err != null) {
                  System.out.println("[login]     ⛔ CHANGE_WAR_QUEUE REFUSÉ : " + err);
                } else {
                  store.saveGuild(g);
                  com.perblue.heroes.network.messages.WarQueueStateUpdate up =
                      new com.perblue.heroes.network.messages.WarQueueStateUpdate();
                  up.guildID = g.guildID; up.newState = g.warQueueState();
                  c.send(up); pushToGuild(g, user.userID, up);
                  System.out.println("[login] <== CHANGE_WAR_QUEUE → " + up.newState + " [persisté]");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.ASSIGN_WAR_CAR) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                long targetID = extraLong(act,
                    com.perblue.heroes.network.messages.ActionExtraType.ID, user.userID);
                com.perblue.heroes.network.messages.WarCarType car = enumExtra(act,
                    com.perblue.heroes.network.messages.ActionExtraType.TYPE,
                    com.perblue.heroes.network.messages.WarCarType.class,
                    com.perblue.heroes.network.messages.WarCarType.DEFAULT);
                String err = w == null ? "aucune guerre en cours"
                    : ServerWarCars.assignCar(g, w, user.userID, user.currentGuildRole(), targetID, car);
                if (err != null) {
                  System.out.println("[login]     ⛔ ASSIGN_WAR_CAR REFUSÉ : " + err);
                } else {
                  store.saveWar(w);
                  com.perblue.heroes.network.messages.WarCarAssignmentUpdate up =
                      new com.perblue.heroes.network.messages.WarCarAssignmentUpdate();
                  up.guildID = g.guildID; up.userID = targetID; up.assignedCar = car;
                  c.send(up); pushToGuild(g, user.userID, up);
                  System.out.println("[login] <== ASSIGN_WAR_CAR " + targetID + " → " + car + " [persisté]");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.CHANGE_WAR_TARGET) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                com.perblue.heroes.network.messages.WarCarType car = enumExtra(act,
                    com.perblue.heroes.network.messages.ActionExtraType.SLOT,
                    com.perblue.heroes.network.messages.WarCarType.class,
                    com.perblue.heroes.network.messages.WarCarType.DEFAULT);
                boolean targeted = Boolean.parseBoolean(String.valueOf(
                    act.extra == null ? "false"
                        : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE)));
                if (w == null) {
                  System.out.println("[login]     ⛔ CHANGE_WAR_TARGET : aucune guerre en cours");
                } else if (!com.perblue.heroes.game.logic.GuildHelper.canWarTarget(user.currentGuildRole())) {
                  System.out.println("[login]     ⛔ CHANGE_WAR_TARGET REFUSÉ : rôle "
                      + user.currentGuildRole());
                } else {
                  com.perblue.heroes.network.messages.WarGuildInfo enemy = w.enemySideOf(g.guildID);
                  com.perblue.heroes.network.messages.WarCarInfo info =
                      enemy.cars == null ? null : (com.perblue.heroes.network.messages.WarCarInfo)
                          enemy.cars.get(car);
                  if (info == null) {
                    System.out.println("[login]     ⛔ CHANGE_WAR_TARGET : salle " + car + " inconnue");
                  } else {
                    info.targeted = targeted;
                    w.putSide(w.opponentOf(g.guildID), enemy);
                    store.saveWar(w);
                    com.perblue.heroes.network.messages.WarTargetUpdate up =
                        new com.perblue.heroes.network.messages.WarTargetUpdate();
                    up.guildID = g.guildID; up.car = car; up.targeted = targeted;
                    c.send(up); pushToGuild(g, user.userID, up);
                    System.out.println("[login] <== CHANGE_WAR_TARGET " + car + " → " + targeted
                        + " [persisté]");
                  }
                }

              } else if (act.command
                  == com.perblue.heroes.network.messages.CommandType.WAR_SABOTAGE_DEFENDER) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                long targetID = extraLong(act,
                    com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                com.perblue.heroes.network.messages.WarSabotageType type = enumExtra(act,
                    com.perblue.heroes.network.messages.ActionExtraType.TYPE,
                    com.perblue.heroes.network.messages.WarSabotageType.class,
                    com.perblue.heroes.network.messages.WarSabotageType.DEFAULT);
                // Le héros visé voyage dans le 2e argument de doAction — c'est `Action.heroType`, PAS un
                // extra (relevé dans ClientActionHelper.sabotageWarDefender).
                com.perblue.heroes.network.messages.UnitType hero = act.heroType;
                if (w == null) {
                  System.out.println("[login]     ⛔ WAR_SABOTAGE_DEFENDER : aucune guerre en cours");
                } else {
                  ServerWarSabotage.SabotageResult sr = ServerWarSabotage.sabotage(w, g, user, targetID,
                      hero, type, com.perblue.heroes.util.TimeUtil.serverTimeNow());
                  if (!sr.ok()) {
                    System.out.println("[login]     ⛔ WAR_SABOTAGE_DEFENDER REFUSÉ : " + sr.error);
                  } else {
                    store.saveWar(w); store.save(user);
                    com.perblue.heroes.network.messages.WarSabotageUpdate up =
                        new com.perblue.heroes.network.messages.WarSabotageUpdate();
                    up.guildID = g.guildID; up.userID = targetID; up.hero = hero;
                    up.sabotageType = type; up.sabotagedByUserID = user.userID;
                    c.send(up); pushToGuild(g, user.userID, up);
                    System.out.println("[login] <== WAR_SABOTAGE_DEFENDER " + hero + " de " + targetID
                        + " → " + type + " (coût " + sr.cost + ", palier " + sr.number + ") [persisté]");
                  }
                }

              } else if (act.command
                  == com.perblue.heroes.network.messages.CommandType.WAR_EDIT_BAN_PROTECT) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                boolean isBan = Boolean.parseBoolean(String.valueOf(
                    act.extra == null ? "false"
                        : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.INDEX)));
                java.util.List<com.perblue.heroes.network.messages.UnitType> heroes =
                    parseUnitList(act.extra == null ? null
                        : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.SLOT));
                String err = w == null ? "aucune guerre en cours"
                    : ServerWarSabotage.editBanProtect(w, g, user, heroes, isBan,
                        com.perblue.heroes.util.TimeUtil.serverTimeNow());
                if (err != null) {
                  System.out.println("[login]     ⛔ WAR_EDIT_BAN_PROTECT REFUSÉ : " + err);
                } else {
                  store.saveWar(w);
                  com.perblue.heroes.network.messages.WarEditBanProtectUpdate up =
                      new com.perblue.heroes.network.messages.WarEditBanProtectUpdate();
                  up.guildID = g.guildID; up.userID = user.userID; up.isBan = isBan; up.warID = w.warID;
                  up.heroes.addAll(heroes);
                  c.send(up); pushToGuild(g, user.userID, up);
                  System.out.println("[login] <== WAR_EDIT_BAN_PROTECT " + (isBan ? "bans" : "protections")
                      + " = " + heroes + " [persisté]");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.WAR_SPAR_TARGET) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                long targetID = extraLong(act,
                    com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                String err = w == null ? "aucune guerre en cours"
                    : ServerWarSabotage.spar(w, g, user, targetID);
                if (err != null) {
                  System.out.println("[login]     ⛔ WAR_SPAR_TARGET REFUSÉ : " + err);
                } else {
                  store.saveWar(w);
                  com.perblue.heroes.network.messages.WarSparsUpdate up =
                      new com.perblue.heroes.network.messages.WarSparsUpdate();
                  up.guildID = g.guildID; up.userID = user.userID; up.targetUserID = targetID;
                  com.perblue.heroes.network.messages.WarMemberInfo me =
                      (com.perblue.heroes.network.messages.WarMemberInfo)
                          w.sideOf(g.guildID).members.get(user.userID);
                  up.sparsDealt = me == null ? 0 : me.sparsDealt;
                  c.send(up); pushToGuild(g, user.userID, up);
                  System.out.println("[login] <== WAR_SPAR_TARGET " + targetID + " → "
                      + up.sparsDealt + "/" + ServerWarSabotage.sparQuota(g) + " [persisté]");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.START_WAR_ATTACK) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                long defenderID = extraLong(act,
                    com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                if (w == null) {
                  System.out.println("[login]     ⛔ START_WAR_ATTACK : aucune guerre en cours");
                } else {
                  ServerWarAttack.StartResult sr = ServerWarAttack.validateStart(w, g, user, defenderID,
                      com.perblue.heroes.util.TimeUtil.serverTimeNow());
                  if (!sr.ok()) {
                    System.out.println("[login]     ⛔ START_WAR_ATTACK REFUSÉ : " + sr.error);
                  } else {
                    ServerWarAttack.consumeAttack(w, g, user, sr.usesExtraAttack);
                    store.saveWar(w); store.save(user);
                    // Le garage attaqué est celui de l'ADVERSAIRE : c'est SON niveau de perk qui alimente
                    // les bonus de salle envoyés à l'attaquant (WarAttackCarBonus.bonusPerkLevel).
                    ServerGuild defG = null;
                    try { defG = store.loadGuild(user.shardID, w.opponentOf(g.guildID)); }
                    catch (Exception e) { System.out.println("[login]     ! guilde adverse illisible: " + e); }
                    com.perblue.heroes.network.messages.StartWarAttackResponse resp =
                        ServerWarAttack.buildStartResponse(w, g.guildID, defenderID, defG);
                    resp.setAsReplyTo(m);
                    c.send(resp);
                    com.perblue.heroes.network.messages.AddInProgressWarAttack add =
                        new com.perblue.heroes.network.messages.AddInProgressWarAttack();
                    add.attackerGuildID = g.guildID; add.attackerUserID = user.userID;
                    add.defenderUserID = defenderID; add.usedExtraAttack = sr.usesExtraAttack;
                    pushToGuild(g, user.userID, add);
                    System.out.println("[login] <== START_WAR_ATTACK vs " + defenderID + " (salle "
                        + resp.currentCar + (sr.usesExtraAttack ? ", attaque BONUS" : "") + ") [persisté]");
                  }
                }

              } else if (act.command
                  == com.perblue.heroes.network.messages.CommandType.CLAIM_WAR_BOX_REWARD) {
                long boxID = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                int index = (int) extraLong(act,
                    com.perblue.heroes.network.messages.ActionExtraType.INDEX, 0);
                ServerWarBoxes boxes = store.loadWarBoxes(user.shardID, user.userID);
                com.perblue.heroes.network.messages.RewardDrop chosen = boxes.claim(boxID, index);
                if (chosen == null) {
                  System.out.println("[login]     ⛔ CLAIM_WAR_BOX_REWARD : boîte " + boxID
                      + " / option " + index + " introuvable (déjà réclamée ?)");
                } else {
                  user.grantRewards(java.util.Collections.singletonList(chosen));
                  store.saveWarBoxes(user.shardID, user.userID, boxes);
                  store.save(user);
                  System.out.println("[login] <== CLAIM_WAR_BOX_REWARD #" + boxID + " option " + index
                      + " → " + (chosen.itemType != com.perblue.heroes.network.messages.ItemType.DEFAULT
                          ? chosen.itemType : chosen.resourceType) + "×" + chosen.quantity
                      + " (reste " + boxes.size() + " boîte(s)) [persisté]");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_WAR_MEMBER_INFO) {
                ServerGuild g = currentGuild(user);
                ServerWarState w = warOf(g, 0);
                long who = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID,
                    user.userID);
                com.perblue.heroes.network.messages.WarMemberInfo mi = null;
                if (w != null) {
                  com.perblue.heroes.network.messages.WarGuildInfo side = w.sideOf(g.guildID);
                  if (side != null && side.members != null) {
                    mi = (com.perblue.heroes.network.messages.WarMemberInfo) side.members.get(who);
                  }
                  if (mi == null) {
                    com.perblue.heroes.network.messages.WarGuildInfo enemy = w.enemySideOf(g.guildID);
                    if (enemy.members != null) {
                      mi = (com.perblue.heroes.network.messages.WarMemberInfo) enemy.members.get(who);
                    }
                  }
                }
                if (mi == null) {
                  System.out.println("[login]     ~ GET_WAR_MEMBER_INFO " + who + " : introuvable");
                } else {
                  mi.setAsReplyTo(m);
                  c.send(mi);
                  System.out.println("[login] <== GET_WAR_MEMBER_INFO " + who + " → WarMemberInfo (salle "
                      + mi.assignedCar + ")");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_WAR_MOMENTS) {
                // Les « moments » sont les guerres récemment commencées/terminées de la guilde.
                ServerGuild g = currentGuild(user);
                com.perblue.heroes.network.messages.WarMoments wm =
                    new com.perblue.heroes.network.messages.WarMoments();
                if (g != null) {
                  for (ServerWarState w : store.listWarsForGuild(user.shardID, g.guildID, 10)) {
                    if (ServerWarEnd.isFinished(w)) wm.endedWars.add(w.toSummary(g.guildID));
                    else wm.startedWars.add(w.toSummary(g.guildID));
                  }
                  wm.seasons.addAll(g.warSeasonHistory());
                }
                wm.setAsReplyTo(m);
                c.send(wm);
                System.out.println("[login] <== GET_WAR_MOMENTS → " + wm.startedWars.size()
                    + " en cours, " + wm.endedWars.size() + " terminée(s)");

              } else if (act.command
                  == com.perblue.heroes.network.messages.CommandType.RECORD_PHONY_WAR_ACTIVITY) {
                // Notification cliente pure : `ActionHelper.doAction` n'a AUCUNE branche pour ce type
                // (même cas que RECORD_SERVER_ROLL_FINISHED). On acquitte sans rien simuler — inventer un
                // compteur ici violerait §4.
                System.out.println("[login] <== RECORD_PHONY_WAR_ACTIVITY (notification, sans effet serveur)");

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.RAID_SURGE) {
                // SURGE #72 incrément 5 — ISSUE d'un RAID (mécanique HQ). Protocole prouvé (disasm + EN JEU) :
                // le client envoie d'abord HeroLineupUpdate{SURGE} (équipe, déjà persistée) + SET_SEED{SURGE},
                // puis cette Action RAID_SURGE avec extra{TYPE=<district>, COUNT, UPSELL, MODE}. Le serveur rejoue
                // SurgeHelper.recordRaid (autoritatif) via ServerSurgeState.applyRaid, persiste, diffuse le delta.
                com.perblue.heroes.network.messages.DistrictType rdist = null;
                try {
                  String dn = act.extra == null ? null
                      : (String) act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                  if (dn != null) rdist = com.perblue.heroes.network.messages.DistrictType.valueOf(dn);
                } catch (Throwable t) { System.out.println("[login]     ! RAID_SURGE district illisible: " + t); }
                ServerGuild rg = currentGuild(user);
                if (rg != null && rdist != null) {
                  long rnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
                  com.perblue.heroes.network.messages.SurgeData rd = ServerSurgeState.loadOrReset(store, rg, rnow);
                  com.perblue.heroes.network.messages.SurgeUpdate up = ServerSurgeState.applyRaid(rg, rd, user, rdist);
                  ServerSurgeState.save(store, rg, rd.surgeID, rd);
                  try { store.save(user); } catch (Exception e) { System.out.println("[login]     ! persist user: " + e); }
                  up.setAsReplyTo(m);
                  c.send(up);
                  pushToGuild(rg, user.userID, up);
                  System.out.println("[login] <== Action RAID_SURGE(" + rdist + ") → ==> SurgeUpdate (+"
                      + up.surgePointDelta + " or, raidsUsed=" + (up.member != null ? up.member.raidsUsed : -1) + ") [persisté]");
                } else {
                  System.out.println("[login] <== Action RAID_SURGE ignoré (guilde=" + (rg != null) + ", district=" + rdist + ")");
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.CLAIM_COLLECTION_REWARDS) {
                // COLLECTIONS #72 — réclame les récompenses d'un niveau de palier. Protocole client (disasm
                // ClientActionHelper.claimCollectionRewards) : Action{CLAIM_COLLECTION_REWARDS, extra{TYPE=CollectionType,
                // TIER=CollectionTier, LEVEL=int}}. Fire-and-forget (client local). Le serveur RÉ-EXÉCUTE
                // CollectionHelper.claimCollectionRewards (anti-triche = levée si non CLAIMABLE) + persiste.
                com.perblue.heroes.network.messages.CollectionType ct = null;
                com.perblue.heroes.network.messages.CollectionTier tr = null;
                try {
                  Object tn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                  if (tn != null) ct = com.perblue.heroes.network.messages.CollectionType.valueOf(tn.toString());
                  Object trn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TIER);
                  if (trn != null) tr = com.perblue.heroes.network.messages.CollectionTier.valueOf(trn.toString());
                } catch (Throwable t) { System.out.println("[login]     ! CLAIM_COLLECTION_REWARDS extras illisibles: " + t); }
                int level = (int) extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.LEVEL, 0);
                boolean ok = ct != null && tr != null && user.applyClaimCollection(ct, tr, level);
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist collection: " + e); } }
                System.out.println("[login] <== Action CLAIM_COLLECTION_REWARDS(" + ct + "/" + tr + " niv." + level + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.BUY_COLLECTION_AVATAR) {
                // COLLECTIONS #72 incr. 3 — ACHAT d'avatar de collection (mastery shop). Protocole client (disasm
                // ClientActionHelper.buyCollectionAvatar) : Action{BUY_COLLECTION_AVATAR, itemType=avatar}. Le serveur
                // ré-exécute CollectionHelper.buyCollectionAvatar (gate COLLECTION_AVATAR_LOCKED + débit MASTERY_TOKENS).
                com.perblue.heroes.network.messages.ItemType avatar = act.itemType;
                boolean ok = avatar != null && user.applyBuyCollectionAvatar(avatar);
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist collection avatar: " + e); } }
                System.out.println("[login] <== Action BUY_COLLECTION_AVATAR(" + avatar + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.SET_WISHING_WELL_TARGET_HERO) {
                // WISHING_WELL #72 — fixe le héros cible du puits. Protocole client (disasm ClientActionHelper) :
                // Action{SET_WISHING_WELL_TARGET_HERO, heroType=cible}. Le serveur ré-exécute WishingWellHelper
                // .setTargetHero (valide éligibilité + pose cible/poids/cooldown) + persiste.
                com.perblue.heroes.network.messages.UnitType target = act.heroType;
                boolean ok = target != null && user.applySetWishingWellTarget(target);
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist wishing-well: " + e); } }
                System.out.println("[login] <== Action SET_WISHING_WELL_TARGET_HERO(" + target + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.REFRESH_TRADER) {
                // MARCHAND (#72 incr. 3) — RAFRAÎCHIT le stock d'un marchand. Protocole client (disasm
                // ClientActionHelper.refreshMerchant) : Action{REFRESH_TRADER, extra{TYPE=MerchantType,
                // REASON=MerchantRefreshType}}. Le serveur ré-exécute MerchantHelper.refresh (gate + facture :
                // quota gratuit/jour, monnaie payante, item, vidéo — anti-triche) puis RE-GÉNÈRE le stock et
                // re-pousse le MerchantUpdate. Corrige l'ancien « REFRESH_TRADER non appliquée (PARTIEL) ».
                com.perblue.heroes.network.messages.MerchantType mt = null;
                com.perblue.heroes.game.logic.MerchantHelper.MerchantRefreshType rt =
                    com.perblue.heroes.game.logic.MerchantHelper.MerchantRefreshType.FREE;
                try {
                  Object tn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                  if (tn != null) mt = com.perblue.heroes.network.messages.MerchantType.valueOf(tn.toString());
                  Object rn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.REASON);
                  if (rn != null) rt = com.perblue.heroes.game.logic.MerchantHelper.MerchantRefreshType.valueOf(rn.toString());
                } catch (Throwable t) { System.out.println("[login]     ! REFRESH_TRADER extras illisibles: " + t); }
                if (mt == null) { System.out.println("[login]     ! REFRESH_TRADER sans MerchantType"); }
                else {
                  try {
                    com.perblue.heroes.network.messages.MerchantData data = user.applyRefreshMerchant(mt, rt);
                    try { store.save(user); } catch (Exception e) {
                      System.out.println("[login]     ! persistance refresh marchand échouée: " + e); }
                    com.perblue.heroes.network.messages.MerchantUpdate mu = new com.perblue.heroes.network.messages.MerchantUpdate();
                    mu.type = mt; mu.data = data; mu.reason = 0;
                    c.send(mu);
                    System.out.println("[login] <== Action REFRESH_TRADER(" + mt + "," + rt + ") appliqué [persisté] + MerchantUpdate re-poussé");
                  } catch (Throwable t) {
                    if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                      System.out.println("[login]     ⛔ REFRESH_TRADER REFUSÉ (anti-triche) : " + t.getMessage());
                    } else { System.out.println("[login]     ! refresh marchand échec: " + t); t.printStackTrace(); }
                  }
                }

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.START_STICKER_CHALLENGE
                  || act.command == com.perblue.heroes.network.messages.CommandType.CLAIM_STICKER_CHALLENGE
                  || act.command == com.perblue.heroes.network.messages.CommandType.CANCEL_STICKER_CHALLENGE) {
                // CHALLENGES #72 incrément 2 — boucle sticker. Protocole client (disasm ClientActionHelper) :
                //   START  Action{START_STICKER_CHALLENGE,  extra{TYPE=StickerType, TIME}}          (sans SLOT → serveur choisit)
                //   CLAIM  Action{CLAIM_STICKER_CHALLENGE,  extra{TYPE=StickerType, SLOT=ChallengeSlots, TIME}}
                //   CANCEL Action{CANCEL_STICKER_CHALLENGE, extra{TYPE=StickerType, SLOT=ChallengeSlots, TIME}}
                // Le serveur RÉ-EXÉCUTE la logique du jeu (StickerHelper) de façon autoritative + persiste. Le client
                // a déjà appliqué localement (patron loot/raid) — fire-and-forget. Extras stockés en .name() (String).
                com.perblue.heroes.network.messages.StickerType st = null;
                com.perblue.heroes.network.messages.ChallengeSlots slot = null;
                try {
                  Object tn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                  if (tn != null) st = com.perblue.heroes.network.messages.StickerType.valueOf(tn.toString());
                  Object sn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.SLOT);
                  if (sn != null) slot = com.perblue.heroes.network.messages.ChallengeSlots.valueOf(sn.toString());
                } catch (Throwable t) { System.out.println("[login]     ! " + act.command + " extras illisibles: " + t); }
                boolean ok = false;
                if (st != null) {
                  if (act.command == com.perblue.heroes.network.messages.CommandType.START_STICKER_CHALLENGE) {
                    ok = ServerChallenges.applyStart(user, st) != null;
                  } else if (act.command == com.perblue.heroes.network.messages.CommandType.CLAIM_STICKER_CHALLENGE) {
                    ok = ServerChallenges.applyClaim(user, st, slot);
                  } else {
                    ok = ServerChallenges.applyCancel(user, st, slot);
                  }
                }
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist défi: " + e); } }
                System.out.println("[login] <== " + act.command + "(" + st + (slot != null ? "/" + slot : "") + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.BUY_STICKER
                  || act.command == com.perblue.heroes.network.messages.CommandType.BUY_STICKER_BOOK
                  || act.command == com.perblue.heroes.network.messages.CommandType.BUY_STICKER_CHALLENGE_SLOT
                  || act.command == com.perblue.heroes.network.messages.CommandType.SET_FAVORITE_STICKER) {
                // CHALLENGES #72 incrément 3 — ÉCONOMIE stickers. Protocole client (disasm ClientActionHelper) :
                //   BUY_STICKER               extra{TYPE=StickerType}       → StickerHelper.purchaseSticker
                //   BUY_STICKER_BOOK          extra{TYPE=StickerBookType}   → StickerHelper.purchaseBook
                //   BUY_STICKER_CHALLENGE_SLOT extra{SLOT=ChallengeSlots}   → StickerHelper.purchaseSlot
                //   SET_FAVORITE_STICKER      extra{TYPE=StickerType}       → userExtra.favoriteSticker
                // Serveur AUTORITATIF (débite DIAMONDS via le code du jeu) + persiste. Fire-and-forget (client local).
                boolean ok = false; String dbg = "";
                try {
                  Object tn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                  Object sn = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.SLOT);
                  if (act.command == com.perblue.heroes.network.messages.CommandType.BUY_STICKER_CHALLENGE_SLOT) {
                    com.perblue.heroes.network.messages.ChallengeSlots slot = sn == null ? null
                        : com.perblue.heroes.network.messages.ChallengeSlots.valueOf(sn.toString());
                    dbg = String.valueOf(slot);
                    ok = slot != null && ServerChallenges.applyBuySlot(user, slot);
                  } else if (act.command == com.perblue.heroes.network.messages.CommandType.BUY_STICKER_BOOK) {
                    com.perblue.heroes.network.messages.StickerBookType book = tn == null ? null
                        : com.perblue.heroes.network.messages.StickerBookType.valueOf(tn.toString());
                    dbg = String.valueOf(book);
                    ok = book != null && ServerChallenges.applyBuyBook(user, book);
                  } else {
                    com.perblue.heroes.network.messages.StickerType t = tn == null ? null
                        : com.perblue.heroes.network.messages.StickerType.valueOf(tn.toString());
                    dbg = String.valueOf(t);
                    if (act.command == com.perblue.heroes.network.messages.CommandType.BUY_STICKER)
                      ok = t != null && ServerChallenges.applyBuySticker(user, t);
                    else
                      ok = t != null && ServerChallenges.applySetFavorite(user, t);
                  }
                } catch (Throwable t) { System.out.println("[login]     ! " + act.command + " extras illisibles: " + t); }
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist défi (shop): " + e); } }
                System.out.println("[login] <== " + act.command + "(" + dbg + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.SET_FAVORITE_FRIENDSHIP
                  || act.command == com.perblue.heroes.network.messages.CommandType.BUY_FRIEND_STAMINA
                  || act.command == com.perblue.heroes.network.messages.CommandType.EMPOWER_FRIENDSHIP) {
                // FRIENDSHIPS #72 incr. 2-3 — favori / stamina / empower. Protocole client (disasm ClientActionHelper) :
                //   SET_FAVORITE_FRIENDSHIP  extra{TYPE=<FriendPairID.getAsLong()>, COUNT=<0/1>} → setFavoritedFriendship
                //   BUY_FRIEND_STAMINA       (sans extra)                                         → buyFriendStamina
                //   EMPOWER_FRIENDSHIP       extra{TYPE=<FriendPairID.getAsLong()>, COUNT=<nb pierres>} → empowerFriendship
                // Serveur AUTORITATIF (débite DIAMONDS/pierres via le code du jeu) + persiste. Fire-and-forget (client local).
                boolean ok = false; String dbg = "";
                if (act.command == com.perblue.heroes.network.messages.CommandType.BUY_FRIEND_STAMINA) {
                  dbg = "stamina"; ok = ServerFriendships.applyBuyStamina(user);
                } else {
                  long pairLong = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.TYPE, 0);
                  int count = (int) extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.COUNT, 0);
                  com.perblue.heroes.game.objects.FriendPairID pair =
                      com.perblue.heroes.game.objects.FriendPairID.from(pairLong);
                  if (act.command == com.perblue.heroes.network.messages.CommandType.EMPOWER_FRIENDSHIP) {
                    dbg = pair + " x" + count; ok = ServerFriendships.applyEmpower(user, pair, count);
                  } else {
                    dbg = pair + "=" + (count != 0); ok = ServerFriendships.applySetFavorite(user, pair, count != 0);
                  }
                }
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist amitié: " + e); } }
                System.out.println("[login] <== " + act.command + "(" + dbg + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.SPEEDUP_MISSION) {
                // FRIENDSHIPS #72 incr. 3c — accélérer une mission idle. Protocole client (disasm) :
                //   SPEEDUP_MISSION  heroType=friendship.getPrimary(), itemType=<objet accél>, extra{COUNT=nb, TIME}
                // Serveur re-dérive MissionSpeedupData (getSpeedupData) puis useSpeedups (§3, lève si stock insuffisant).
                int count = (int) extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.COUNT, 0);
                boolean ok = ServerMissions.applySpeedupMission(user, act.heroType, act.itemType, count);
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist mission: " + e); } }
                System.out.println("[login] <== SPEEDUP_MISSION(" + act.heroType + " " + act.itemType + " x" + count
                    + ")" + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.SET_MISSION_ITEM_COST_LIMIT) {
                // FRIENDSHIPS #72 incr. 3c — plafond de dépense auto d'un objet en missions. Protocole client :
                //   SET_MISSION_ITEM_COST_LIMIT  itemType=<objet>, extra{COUNT=plafond}  (write-through, N=0 retire)
                int limit = (int) extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.COUNT, 0);
                boolean ok = ServerMissions.applySetItemCostLimit(user, act.itemType, limit);
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist mission: " + e); } }
                System.out.println("[login] <== SET_MISSION_ITEM_COST_LIMIT(" + act.itemType + "=" + limit + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else if (act.command == com.perblue.heroes.network.messages.CommandType.ADD_MISSION
                  || act.command == com.perblue.heroes.network.messages.CommandType.CLAIM_MISSION_REWARDS
                  || act.command == com.perblue.heroes.network.messages.CommandType.CANCEL_MISSION) {
                // FRIENDSHIPS #72 incr. 3c — MISSIONS IDLE d'amitié (cœur de l'écran MISSIONS 12.1.0). Protocole
                // client (disasm ClientActionHelper) :
                //   ADD_MISSION            extra{TYPE=MissionType, ID=FriendPairID.getAsLong(), TIME=serverTimeNow} → MissionHelper.addMission
                //   CLAIM_MISSION_REWARDS  extra{TIME}                                                              → claimMissionRewards
                //   CANCEL_MISSION         heroType=friendship.getPrimary(), extra{TIME}                            → cancelMissionByHero
                // Serveur AUTORITATIF (durées/coûts/récompenses = code+données du jeu ; horloge SERVEUR). Fire-and-forget.
                boolean ok = false; String dbg = "";
                if (act.command == com.perblue.heroes.network.messages.CommandType.CLAIM_MISSION_REWARDS) {
                  dbg = "claim"; ok = ServerMissions.applyClaimMissionRewards(user);
                } else if (act.command == com.perblue.heroes.network.messages.CommandType.CANCEL_MISSION) {
                  dbg = "cancel " + act.heroType; ok = ServerMissions.applyCancelMission(user, act.heroType);
                } else {
                  long pairLong = extraLong(act, com.perblue.heroes.network.messages.ActionExtraType.ID, 0);
                  com.perblue.heroes.game.objects.FriendPairID pair =
                      com.perblue.heroes.game.objects.FriendPairID.from(pairLong);
                  Object tv = act.extra == null ? null
                      : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                  com.perblue.heroes.network.messages.MissionType type = null;
                  try { if (tv != null)
                      type = com.perblue.heroes.network.messages.MissionType.valueOf(tv.toString()); }
                  catch (Throwable t) { type = null; }
                  if (type == null) {
                    System.out.println("[login]     ! ADD_MISSION type manquant/invalide: " + tv);
                  } else {
                    dbg = type + " " + pair; ok = ServerMissions.applyAddMission(user, type, pair);
                  }
                }
                if (ok) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist mission: " + e); } }
                System.out.println("[login] <== " + act.command + "(" + dbg + ")"
                    + (ok ? " appliqué [persisté]" : " refusé"));

              } else {
                boolean applied = user.applyAction(act);
                if (applied) { try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persistance échouée: " + e); } }
                System.out.println("[login]     action " + act.command
                    + (applied ? " appliquée [persisté]" : " non appliquée (PARTIEL)"));
              }
            } else if (m instanceof com.perblue.heroes.network.messages.ActionGroup) {
              // LOT d'actions envoyé en UN message (trouvé en jeu : le « COLLECT ALL » de la mailbox groupe
              // plusieurs TAKE_MAIL_ATTACHMENTS + MARK_MAIL_OPENED dans un ActionGroup ; idem opérations de masse).
              // Le serveur applique CHAQUE action via applyAction (logique + anti-triche PAR action) et persiste
              // une fois. Sans ça, un lot entier était ignoré (récompenses non créditées).
              com.perblue.heroes.network.messages.ActionGroup ag =
                  (com.perblue.heroes.network.messages.ActionGroup) m;
              int applied = 0, total = 0;
              if (ag.actions != null) {
                for (Object o : ag.actions) {
                  total++;
                  try { if (user.applyAction((Action) o)) applied++; }
                  catch (Throwable t) { System.out.println("[login]     ! action de groupe échouée: " + t); }
                }
              }
              if (applied > 0) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance échouée: " + e); } }
              System.out.println("[login] <== ActionGroup : " + applied + "/" + total
                  + " action(s) appliquée(s)" + (applied > 0 ? " [persisté]" : ""));
            } else if (m instanceof com.perblue.heroes.network.messages.SetPlayerName) {
              // Choix / changement du nom du joueur (onboarding « CHOOSE NAME » + Réglages). Fire-and-forget :
              // le client a déjà appliqué UserHelper.changeName de son côté ; le serveur AUTORITATIF ré-exécute
              // la même logique (légalité + coût) et PERSISTE. Aucune réponse attendue.
              com.perblue.heroes.network.messages.SetPlayerName spn =
                  (com.perblue.heroes.network.messages.SetPlayerName) m;
              boolean applied = user.setPlayerName(spn);
              if (applied) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance échouée: " + e); } }
              System.out.println("[login]     SetPlayerName '" + spn.name + "'"
                  + (applied ? " appliqué [persisté]" : " refusé"));
            } else if (m instanceof com.perblue.heroes.network.messages.SetLanguage) {
              // LANGUE du joueur — relevée « reçue mais non traitée » dans les logs Windows (2026-08-02).
              // Le jeu a un champ pour ça (`UserExtra.language`, écrit par `User.setLanguage`) et il vit dans
              // `this.extra` → auto-persisté. Fire-and-forget, aucune réponse attendue.
              com.perblue.heroes.network.messages.SetLanguage sl =
                  (com.perblue.heroes.network.messages.SetLanguage) m;
              boolean applied = user.setLanguage(sl);
              if (applied) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance échouée: " + e); } }
              System.out.println("[login]     SetLanguage '" + sl.language + "'"
                  + (applied ? " appliquée [persisté]" : " ignorée"));
            } else if (m instanceof com.perblue.heroes.network.messages.SetExternalContentStatus) {
              // NO-OP FIDÈLE (même catégorie que RECORD_SERVER_ROLL_FINISHED) : `hasExternalContent` dit au
              // backend si l'APPAREIL a téléchargé le pack d'assets externe. Vérifié dans le jar : le champ
              // n'apparaît QUE dans ce message, dans son émetteur (`ExternalAssetManager
              // $DeferredSetExternalContentFlag`) et dans `HeroFiltersActV1` — AUCUN champ de joueur ne le
              // reçoit et rien ne le relit. Lui inventer un stockage violerait §4 : on acquitte et on
              // journalise, c'est la réponse autoritative correcte.
              com.perblue.heroes.network.messages.SetExternalContentStatus se =
                  (com.perblue.heroes.network.messages.SetExternalContentStatus) m;
              System.out.println("[login]     SetExternalContentStatus hasExternalContent="
                  + se.hasExternalContent + " (notification appareil, aucun état de joueur associé)");
            } else if (m instanceof com.perblue.heroes.network.messages.ClaimWeeklyQuestReward) {
              // Ouverture d'une BOÎTE-RÉCOMPENSE HEBDOMADAIRE (écran QUESTS). Fire-and-forget (le client a
              // appliqué QuestHelper.claimWeeklyReward de son côté). Le serveur AUTORITATIF ré-exécute la même
              // logique (anti-triche RÉEL sur le NOMBRE de boîtes restantes) et PERSISTE. Cf. ServerUser.
              com.perblue.heroes.network.messages.ClaimWeeklyQuestReward cw =
                  (com.perblue.heroes.network.messages.ClaimWeeklyQuestReward) m;
              boolean applied = user.claimWeeklyReward(cw);
              if (applied) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance échouée: " + e); } }
              System.out.println("[login] <== ClaimWeeklyQuestReward"
                  + (applied ? " → récompense weekly créditée [persisté]" : " refusé (boîtes épuisées)"));
            } else if (m instanceof com.perblue.heroes.network.messages.BattlePassV2GetData) {
              // REQUÊTE d'état du battle pass — envoyée par QuestsScreen quand l'onglet BATTLE PASS est
              // DÉVERROUILLÉ (TL≥11) & la saison ACTIVE (requestBattlePassV2Data). Sans réponse, le client ne
              // pose jamais userBattlePassV2 → l'onglet reste inerte (listener non ajouté). On répond avec
              // NOTRE BattlePassV2Data (refreshBattlePass : type QUEST, saison courante, premium, progress/claims
              // persistés) → GameMain.lambda$setupPostClientInfoHandlers pose userBattlePassV2 = wrapper + active
              // l'onglet. Gap trouvé EN JEU à TL65 (onglet grisé/inerte malgré déblocage).
              com.perblue.heroes.network.messages.BattlePassV2Data d = user.refreshBattlePass();
              d.setAsReplyTo(m);
              c.send(d);
              System.out.println("[login] <== BattlePassV2GetData → ==> BattlePassV2Data (type=" + d.type
                  + " premium=" + d.premiumUnlocked + " progress=" + d.progress + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.CheckLineupName) {
              // SAVED_LINEUPS #72 — VALIDATION du NOM d'un lineup (requête/réponse). Le client (SavedLineupHeroChooser)
              // demande, le serveur répond CheckLineupNameResult{name, isValid}. La validation est SERVEUR (absente du
              // jar client) → on RÉUTILISE la logique de nom DU JEU NameChangeHelper.isNameLegal (ILLEGAL_NAMES +
              // codepoints valides) + non-vide, plutôt qu'inventer une règle (§3/§4). Sans réponse, la fenêtre de
              // nommage resterait bloquée en jeu.
              com.perblue.heroes.network.messages.CheckLineupName cln =
                  (com.perblue.heroes.network.messages.CheckLineupName) m;
              com.perblue.heroes.network.messages.CheckLineupNameResult res =
                  new com.perblue.heroes.network.messages.CheckLineupNameResult();
              res.name = cln.name;
              boolean valid;
              try {
                valid = cln.name != null && !cln.name.trim().isEmpty()
                    && com.perblue.heroes.game.logic.NameChangeHelper.isNameLegal(cln.name);
              } catch (Throwable t) { valid = false; System.out.println("[login]     ! CheckLineupName: " + t); }
              res.isValid = valid;
              res.setAsReplyTo(m);
              c.send(res);
              System.out.println("[login] <== CheckLineupName(\"" + cln.name + "\") → isValid=" + valid);
            } else if (m instanceof com.perblue.heroes.network.messages.HeroLineupUpdate) {
              // SAUVEGARDE d'une LINEUP (arène #41 : défense/attaque COLISEUM_DEFENSE_1/2/3, FIGHT_PIT_DEFENSE,
              // mais aussi équipes de campagne, etc.). Fire-and-forget (le client a déjà mis à jour son état local) :
              // le serveur AUTORITATIF ré-applique setHeroLineup et PERSISTE (userExtra.heroLineups) → la défense
              // d'arène survit aux redémarrages. Modèle d'état = celui du jeu (HeroLineupType), pas inventé.
              com.perblue.heroes.network.messages.HeroLineupUpdate hlu =
                  (com.perblue.heroes.network.messages.HeroLineupUpdate) m;
              boolean applied = user.applyHeroLineupUpdate(hlu);
              if (applied) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance échouée: " + e); } }
              System.out.println("[login] <== HeroLineupUpdate(" + hlu.type + ")"
                  + (applied ? " → lineup enregistrée [persistée]" : " refusée"));
              // GUERRE DE GUILDE : une défense de guerre change la GUERRE EN COURS, pas seulement le joueur.
              // On resynchronise SON entrée dans l'état partagé (l'état de guerre déjà acquis — héros KO,
              // sabotages — est reporté par ServerWarMembers) et on rediffuse aux membres en ligne.
              if (applied && hlu.type != null && hlu.type.name().startsWith("WAR_DEFENSE")) {
                ServerGuild wg = currentGuild(user);
                ServerWarState ww = warOf(wg, 0);
                if (ww != null && ServerWarMembers.syncOne(ww, wg, user)) {
                  try {
                    store.saveWar(ww);
                    com.perblue.heroes.network.messages.WarInfo wi = ww.toWarInfo(wg.guildID);
                    pushToGuild(wg, 0L, wi);
                    System.out.println("[login]     défense de guerre resynchronisée (guerre #" + ww.warID
                        + ") [persistée]");
                  } catch (Exception e) {
                    System.out.println("[login]     ! resynchro défense de guerre échouée: " + e);
                  }
                }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetArenaInfo) {
              // OUVERTURE DE L'ARÈNE (FIGHT_PIT/COLISEUM) — le client (ArenaLeagueScreen) envoie GetArenaInfo{type}
              // et attend un ArenaInfo pour rendre l'écran (sinon « LOADING… » infini — trouvé EN JEU). Le builder
              // d'ArenaInfo n'existe PAS dans le jar client (backend PerBlue) → on le construit serveur-autoritativement
              // (ServerArena : saison via ArenaHelper/arena_*.tab, ligue COPPER, ta row + adversaires). Palier 1.
              com.perblue.heroes.network.messages.GetArenaInfo req =
                  (com.perblue.heroes.network.messages.GetArenaInfo) m;
              com.perblue.heroes.network.messages.ArenaType at =
                  req.type == null ? com.perblue.heroes.network.messages.ArenaType.FIGHT_PIT : req.type;
              // #41 : CLASSEMENT PERSISTANT — on charge le ladder de (shard, type) ; absent → généré à la 1re
              // ouverture ; on le PERSISTE (rangs/points/fights durables entre redémarrages, cohérent multi-serveur).
              ServerArenaLadder loaded = null;
              try { loaded = store.loadArenaLadder(user.shardID, at.name()); }
              catch (Exception e) { System.out.println("[login]     ! lecture ladder échouée: " + e); }
              ServerUser.ArenaResult ar = user.arenaInfoWithLadder(at, loaded, oppSrc);
              try { store.saveArenaLadder(user.shardID, at.name(), ar.ladder); }
              catch (Exception e) { System.out.println("[login]     ! persistance ladder échouée: " + e); }
              com.perblue.heroes.network.messages.ArenaInfo ai = ar.info;
              ai.setAsReplyTo(m);
              c.send(ai);
              System.out.println("[login] <== GetArenaInfo(" + at + ") → ==> ArenaInfo (tier="
                  + ai.yourLeague.tier + " div=" + ai.yourLeague.division + " rank=" + ai.yourLeague.yourRank
                  + " players=" + ai.yourLeague.players.size() + ", ladder="
                  + (loaded == null ? "GÉNÉRÉ" : "chargé") + " [persisté])");
            } else if (m instanceof com.perblue.heroes.network.messages.ArenaAttack
                || m instanceof com.perblue.heroes.network.messages.ColiseumAttack) {
              // ARÈNE #44 — RÉSULTAT du combat rapporté par le client (ArenaAttack=FIGHT_PIT / ColiseumAttack=COLISEUM).
              // Résolution AUTORITATIVE : décrément fights + swap de rang sur victoire + points → mute le ladder
              // PERSISTANT (#41) → répond ArenaUpdate (nouveau classement). Patron CampaignAttack #19.
              com.perblue.heroes.network.messages.ArenaType at;
              long defID; boolean win;
              com.perblue.heroes.network.messages.AttackBase base;
              if (m instanceof com.perblue.heroes.network.messages.ColiseumAttack) {
                com.perblue.heroes.network.messages.ColiseumAttack ca =
                    (com.perblue.heroes.network.messages.ColiseumAttack) m;
                at = com.perblue.heroes.network.messages.ArenaType.COLISEUM;
                defID = ca.defendingUserID; win = outcomeWin(ca.base, ca.stats); base = ca.base;
              } else {
                com.perblue.heroes.network.messages.ArenaAttack aa =
                    (com.perblue.heroes.network.messages.ArenaAttack) m;
                at = com.perblue.heroes.network.messages.ArenaType.FIGHT_PIT;
                defID = aa.defendingUserID; win = outcomeWin(aa.base, aa.stats); base = aa.base;
              }
              java.util.List<?> attackers = base == null ? null : base.attackers;
              ServerArenaLadder ladder = loadOrCreateLadder(user, at);
              com.perblue.heroes.network.messages.ArenaUpdate up =
                  user.resolveArenaAttack(defID, win, at, ladder, oppSrc, attackers);   // + XP d'arène
              try { store.saveArenaLadder(user.shardID, at.name(), ladder); } catch (Exception e) {
                System.out.println("[login]     ! persistance ladder échouée: " + e); }
              try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance joueur échouée: " + e); }
              // RAPPORT DE DÉFENSE — si le défenseur est un VRAI joueur, on lui dépose un courrier (FIGHT_PIT_DEFENSE /
              // COLISEUM_DEFENSE) : qui l'a attaqué + issue (défense tenue / vaincue). Comme dans le vrai jeu.
              String attackerName = user.basicInfo() != null && user.basicInfo().name != null
                  ? user.basicInfo().name : "Un rival";
              deliverDefenseMail(defID, user.shardID, at, win, attackerName);
              up.setAsReplyTo(m);
              c.send(up);
              System.out.println("[login] <== " + (m instanceof com.perblue.heroes.network.messages.ColiseumAttack
                  ? "ColiseumAttack" : "ArenaAttack") + " défenseur=" + defID + " win=" + win
                  + " → ==> ArenaUpdate (rank=" + up.yourLeague.yourRank + ") [persisté]");
            } else if (m instanceof com.perblue.heroes.network.messages.ListRecommendedGuilds) {
              // GUILDES #7 — écran FIND A GUILD : le client (GuildSearchScreen) envoie ListRecommendedGuilds et
              // attend un ListRecGuildsResponse pour rendre la liste (sinon « LOADING… » infini — trouvé EN JEU).
              // On liste les guildes RÉELLES du shard (table `guilds`, état persistant multi-serveur). Aucune guilde
              // → liste vide = réponse AUTORITATIVE correcte (l'écran affiche NO_RECOMMENDABLE_GUILDS_FOUND).
              com.perblue.heroes.network.messages.ListRecommendedGuilds req =
                  (com.perblue.heroes.network.messages.ListRecommendedGuilds) m;
              com.perblue.heroes.network.messages.ListRecGuildsResponse resp =
                  new com.perblue.heroes.network.messages.ListRecGuildsResponse();
              resp.parameters = req.parameters;
              resp.startIndex = req.startIndex;
              resp.guilds = guildRows(store.listGuilds(user.shardID, null, 20));
              resp.endIndex = req.startIndex + resp.guilds.size();
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== ListRecommendedGuilds → ==> ListRecGuildsResponse ("
                  + resp.guilds.size() + " guilde(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.SearchGuilds) {
              // GUILDES #7 — recherche par NOM (champ « Search Guilds »). Répond SearchGuildsResponse (LIKE sur le nom).
              com.perblue.heroes.network.messages.SearchGuilds req =
                  (com.perblue.heroes.network.messages.SearchGuilds) m;
              com.perblue.heroes.network.messages.SearchGuildsResponse resp =
                  new com.perblue.heroes.network.messages.SearchGuildsResponse();
              resp.guilds = guildRows(store.listGuilds(user.shardID, req.nameSearch, 20));
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== SearchGuilds '" + req.nameSearch
                  + "' → ==> SearchGuildsResponse (" + resp.guilds.size() + " guilde(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.CreateGuild) {
              // GUILDES #7 — CRÉATION : le serveur AUTORITATIF débite le coût (GuildHelper.chargeForCreation = 2000
              // GOLD, lève si insuffisant), crée la guilde (id libre du shard), pose l'appartenance du fondateur
              // (RULER) et PERSISTE (guilde + joueur). Répond UserGuildUpdate(reason=CREATE) → le client bascule
              // « en guilde » et ouvre l'écran de guilde.
              com.perblue.heroes.network.messages.CreateGuild cg =
                  (com.perblue.heroes.network.messages.CreateGuild) m;
              try {
                if (user.inGuild()) {
                  System.out.println("[login]     ⛔ CreateGuild REFUSÉ : joueur déjà dans une guilde ("
                      + user.currentGuildID() + ")");
                } else {
                  long gid = store.nextGuildID(user.shardID);
                  ServerGuild g = user.createGuild(cg, gid);
                  store.saveGuild(g);
                  store.save(user);
                  com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                      g, com.perblue.heroes.network.messages.GuildRole.RULER,
                      com.perblue.heroes.network.messages.GuildUpdateReason.CREATE);
                  up.setAsReplyTo(m);
                  c.send(up);
                  System.out.println("[login] <== CreateGuild '" + cg.name + "' → guilde #" + gid
                      + " créée (fondateur RULER) [persisté] ==> UserGuildUpdate(CREATE)");
                }
              } catch (Throwable t) {
                if (t instanceof com.perblue.heroes.ClientErrorCodeException) {
                  System.out.println("[login]     ⛔ CreateGuild REFUSÉ (anti-triche) : " + t.getMessage()
                      + " — aucune guilde créée");
                } else {
                  System.out.println("[login]     ! createGuild échec: " + t);
                  t.printStackTrace();
                }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.RequestExtendedGuildInfo) {
              // GUILDES #7 — écran GUILDE : détails complets (roster). Répond ExtendedGuildInfo (guildInfo + membres).
              com.perblue.heroes.network.messages.RequestExtendedGuildInfo req =
                  (com.perblue.heroes.network.messages.RequestExtendedGuildInfo) m;
              ServerGuild g = store.loadGuild(user.shardID, req.guildID);
              if (g == null) {
                System.out.println("[login]     ! RequestExtendedGuildInfo : guilde #" + req.guildID + " introuvable");
              } else {
                com.perblue.heroes.network.messages.ExtendedGuildInfo egi =
                    new com.perblue.heroes.network.messages.ExtendedGuildInfo();
                egi.guildInfo = g.info;
                egi.members = new java.util.ArrayList<>();
                for (Long mid : g.memberIDs) {
                  ServerUser mu = mid == user.userID ? user : store.loadIfExists(mid, user.shardID);
                  if (mu != null) egi.members.add(mu.buildPlayerGuildRow());
                }
                egi.newestGuildDonationRequest = 0L;
                egi.setAsReplyTo(m);
                c.send(egi);
                // Synchronise l'INFLUENCE côté client (User.getGuildInfluence, séparé de guildInfo.influence) — sinon
                // le compteur affiche 0 et le client BLOQUE les upgrades de perk. GuildInfluenceDiff = message dédié.
                com.perblue.heroes.network.messages.GuildInfluenceDiff idf =
                    new com.perblue.heroes.network.messages.GuildInfluenceDiff();
                idf.guildID = g.guildID; idf.influence = g.info.influence;
                c.send(idf);
                System.out.println("[login] <== RequestExtendedGuildInfo #" + req.guildID
                    + " → ==> ExtendedGuildInfo (" + egi.members.size() + " membre(s)) + GuildInfluenceDiff("
                    + g.info.influence + ")");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.JoinGuild) {
              // GUILDES #7 — rejoindre une guilde OUVERTE (OPEN). Politiques APPLICATION_ONLY/PRIVATE = flux de
              // candidature (AcceptGuildMember, à venir). Répond UserGuildUpdate(JOIN).
              com.perblue.heroes.network.messages.JoinGuild jg =
                  (com.perblue.heroes.network.messages.JoinGuild) m;
              ServerGuild g = store.loadGuild(user.shardID, jg.guildID);
              int maxMembers = com.perblue.heroes.game.logic.GuildHelper.getMaxMembers();
              if (g == null) {
                System.out.println("[login]     ! JoinGuild : guilde #" + jg.guildID + " introuvable");
              } else if (user.inGuild()) {
                System.out.println("[login]     ⛔ JoinGuild REFUSÉ : joueur déjà dans une guilde");
              } else if (g.memberCount() >= maxMembers) {
                System.out.println("[login]     ⛔ JoinGuild REFUSÉ : guilde #" + jg.guildID + " pleine");
              } else if (g.info.newMemberPolicy == com.perblue.heroes.network.messages.GuildNewMemberPolicy.PRIVATE) {
                System.out.println("[login]     ⛔ JoinGuild REFUSÉ : guilde #" + jg.guildID + " PRIVÉE (sur invitation)");
              } else if (g.info.newMemberPolicy == com.perblue.heroes.network.messages.GuildNewMemberPolicy.APPLICATION_ONLY) {
                // CANDIDATURE — la guilde exige une validation par un officier. On enregistre le postulant (nom depuis
                // BasicUserInfo) ; un officier l'accepte/refuse via AcceptGuildMember. Pas d'UserGuildUpdate (pas encore membre).
                String nm = user.basicInfo() != null && user.basicInfo().name != null ? user.basicInfo().name : ("#" + user.userID);
                g.applicants.put(user.userID, nm);
                store.saveGuild(g);
                System.out.println("[login] <== JoinGuild #" + jg.guildID + " → CANDIDATURE enregistrée ('" + nm
                    + "', " + g.applicants.size() + " en attente) [persisté]");
              } else {
                user.joinGuildAs(jg.guildID, com.perblue.heroes.network.messages.GuildRole.MEMBER);
                g.memberIDs.add(user.userID);
                g.info.memberCount = g.memberCount();
                store.saveGuild(g);
                store.save(user);
                com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                    g, com.perblue.heroes.network.messages.GuildRole.MEMBER,
                    com.perblue.heroes.network.messages.GuildUpdateReason.JOIN);
                up.setAsReplyTo(m);
                c.send(up);
                System.out.println("[login] <== JoinGuild #" + jg.guildID
                    + " → membre ajouté [persisté] ==> UserGuildUpdate(JOIN)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.LeaveGuild) {
              // GUILDES #7 — DÉPART. Retire le joueur du roster ; guilde vidée → dissoute. Répond UserGuildUpdate(LEAVE).
              long gid = user.currentGuildID();
              if (gid <= 0) {
                System.out.println("[login]     ! LeaveGuild : joueur sans guilde");
              } else {
                ServerGuild g = store.loadGuild(user.shardID, gid);
                user.leaveGuild();
                if (g != null) {
                  g.memberIDs.remove(Long.valueOf(user.userID));
                  g.info.memberCount = g.memberCount();
                  if (g.memberIDs.isEmpty()) { store.deleteGuild(user.shardID, gid);
                    System.out.println("[login]     guilde #" + gid + " dissoute (dernier membre parti)"); }
                  else store.saveGuild(g);
                }
                store.save(user);
                com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                    null, com.perblue.heroes.network.messages.GuildRole.NONE,
                    com.perblue.heroes.network.messages.GuildUpdateReason.LEAVE);
                up.setAsReplyTo(m);
                c.send(up);
                System.out.println("[login] <== LeaveGuild #" + gid + " [persisté] ==> UserGuildUpdate(LEAVE)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.KickFromGuild) {
              // GUILD — EXPULSION d'un membre. Autoritatif : GuildHelper.canKickMember(monRôle, rôleCible). Retire la
              // cible du roster + efface SON appartenance (chargée du store) + persiste. Courrier d'info à l'expulsé.
              com.perblue.heroes.network.messages.KickFromGuild kf =
                  (com.perblue.heroes.network.messages.KickFromGuild) m;
              ServerGuild g = currentGuild(user);
              ServerUser target = g == null ? null : store.loadIfExists(kf.userToKick, user.shardID);
              if (g == null) {
                System.out.println("[login]     ! KickFromGuild : joueur sans guilde");
              } else if (target == null || !g.memberIDs.contains(kf.userToKick)) {
                System.out.println("[login]     ! KickFromGuild : cible #" + kf.userToKick + " pas dans la guilde");
              } else if (!com.perblue.heroes.game.logic.GuildHelper.canKickMember(user.currentGuildRole(), target.currentGuildRole())) {
                System.out.println("[login]     ⛔ KickFromGuild REFUSÉ : " + user.currentGuildRole()
                    + " ne peut pas expulser " + target.currentGuildRole());
              } else {
                g.memberIDs.remove(Long.valueOf(kf.userToKick));
                g.applicants.remove(kf.userToKick);
                g.checkedInToday.remove(kf.userToKick);
                g.info.memberCount = g.memberCount();
                target.leaveGuild();
                store.saveGuild(g);
                try { store.save(target); } catch (Exception e) {}
                // notifie l'expulsé (GuildMemberRankChange KICKED) — le client le retire de sa guilde au prochain contact
                System.out.println("[login] <== KickFromGuild #" + kf.userToKick + " expulsé de #" + g.guildID
                    + " [persisté] (" + g.memberCount() + " membre(s))");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.AcceptGuildMember) {
              // GUILD — un officier ACCEPTE/REFUSE une candidature (guilde APPLICATION_ONLY). GuildHelper.canAcceptMembers.
              com.perblue.heroes.network.messages.AcceptGuildMember ag =
                  (com.perblue.heroes.network.messages.AcceptGuildMember) m;
              ServerGuild g = currentGuild(user);
              if (g == null) {
                System.out.println("[login]     ! AcceptGuildMember : joueur sans guilde");
              } else if (!com.perblue.heroes.game.logic.GuildHelper.canAcceptMembers(user.currentGuildRole())) {
                System.out.println("[login]     ⛔ AcceptGuildMember REFUSÉ : rôle " + user.currentGuildRole() + " insuffisant");
              } else if (!g.applicants.containsKey(ag.userID)) {
                System.out.println("[login]     ! AcceptGuildMember : #" + ag.userID + " pas candidat");
              } else if (!ag.isAccept) {
                g.applicants.remove(ag.userID);
                store.saveGuild(g);
                System.out.println("[login] <== AcceptGuildMember #" + ag.userID + " REFUSÉ (candidature retirée)");
              } else if (g.memberCount() >= com.perblue.heroes.game.logic.GuildHelper.getMaxMembers()) {
                System.out.println("[login]     ⛔ AcceptGuildMember : guilde pleine");
              } else {
                ServerUser applicant = store.loadIfExists(ag.userID, user.shardID);
                g.applicants.remove(ag.userID);
                if (applicant != null && !applicant.inGuild()) {
                  applicant.joinGuildAs(g.guildID, com.perblue.heroes.network.messages.GuildRole.MEMBER);
                  g.memberIDs.add(ag.userID);
                  g.info.memberCount = g.memberCount();
                  try { store.save(applicant); } catch (Exception e) {}
                }
                store.saveGuild(g);
                System.out.println("[login] <== AcceptGuildMember #" + ag.userID + " ACCEPTÉ dans #" + g.guildID
                    + " [persisté] (" + g.memberCount() + " membre(s))");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.PromoteToOfficer
                || m instanceof com.perblue.heroes.network.messages.DemoteFromOfficer) {
              // GUILD — PROMOTION / RÉTROGRADATION d'un membre d'un cran (le libellé « to Veteran/Officer » dépend du
              // rôle courant ; le rang cible = getNextHigher/LowerRole). Autoritatif : GuildHelper.canPromote/canDemote
              // (monRôle, rôleCible). Persiste le rôle de la cible + répond GuildMemberRankChange (le client rafraîchit).
              boolean promote = m instanceof com.perblue.heroes.network.messages.PromoteToOfficer;
              long targetID = promote
                  ? ((com.perblue.heroes.network.messages.PromoteToOfficer) m).userToPromote
                  : ((com.perblue.heroes.network.messages.DemoteFromOfficer) m).userToDemote;
              ServerGuild g = currentGuild(user);
              ServerUser target = g == null ? null : store.loadIfExists(targetID, user.shardID);
              com.perblue.heroes.network.messages.GuildRole myRole = user.currentGuildRole();
              if (g == null || target == null || !g.memberIDs.contains(targetID)) {
                System.out.println("[login]     ! " + (promote ? "Promote" : "Demote") + " : cible #" + targetID + " hors guilde");
              } else {
                com.perblue.heroes.network.messages.GuildRole targetRole = target.currentGuildRole();
                boolean allowed = promote
                    ? com.perblue.heroes.game.logic.GuildHelper.canPromote(myRole, targetRole)
                    : com.perblue.heroes.game.logic.GuildHelper.canDemote(myRole, targetRole);
                if (!allowed) {
                  System.out.println("[login]     ⛔ " + (promote ? "Promote" : "Demote") + " REFUSÉ : " + myRole
                      + (promote ? " ne peut promouvoir " : " ne peut rétrograder ") + targetRole);
                } else {
                  com.perblue.heroes.network.messages.GuildRole newRole = promote
                      ? com.perblue.heroes.game.logic.GuildHelper.getNextHigherRole(targetRole)
                      : com.perblue.heroes.game.logic.GuildHelper.getNextLowerRole(targetRole);
                  target.setGuildRoleOnly(newRole);
                  try { store.save(target); } catch (Exception e) {}
                  com.perblue.heroes.network.messages.GuildMemberRankChange rc =
                      new com.perblue.heroes.network.messages.GuildMemberRankChange();
                  rc.guildID = g.guildID; rc.memberID = targetID; rc.guildRole = newRole;
                  rc.newGuildMemberCount = g.memberCount();
                  rc.reason = promote ? com.perblue.heroes.network.messages.GuildUpdateReason.PROMOTE
                      : com.perblue.heroes.network.messages.GuildUpdateReason.DEMOTE;
                  rc.setAsReplyTo(m);
                  c.send(rc);
                  System.out.println("[login] <== " + (promote ? "PromoteToOfficer" : "DemoteFromOfficer") + " #" + targetID
                      + " : " + targetRole + " → " + newRole + " [persisté] ==> GuildMemberRankChange");
                }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.EditGuild) {
              // GUILD SETTINGS — édition des réglages (motto, min level, politique, pays, fuseau, drapeaux). Autoritatif
              // selon le RÔLE (GuildHelper.canEdit*). Persiste + répond UserGuildUpdate(DEFAULT) pour rafraîchir le client.
              com.perblue.heroes.network.messages.EditGuild eg =
                  (com.perblue.heroes.network.messages.EditGuild) m;
              ServerGuild g = currentGuild(user);
              if (g == null || g.guildID != eg.guildID) {
                System.out.println("[login]     ! EditGuild : guilde introuvable / non membre");
              } else {
                boolean changed = user.editGuild(g, eg);
                if (changed) store.saveGuild(g);
                com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                    g, user.currentGuildRole(), com.perblue.heroes.network.messages.GuildUpdateReason.DEFAULT);
                up.setAsReplyTo(m);
                c.send(up);
                System.out.println("[login] <== EditGuild #" + eg.guildID + (changed ? " appliqué [persisté]" : " (aucun droit)")
                    + " ==> UserGuildUpdate(DEFAULT)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.SetGuildName) {
              // GUILD SETTINGS — renommer la guilde. Autoritatif (GuildHelper.canRenameGuild). Persiste + refresh client.
              com.perblue.heroes.network.messages.SetGuildName sn =
                  (com.perblue.heroes.network.messages.SetGuildName) m;
              ServerGuild g = currentGuild(user);
              if (g == null) {
                System.out.println("[login]     ! SetGuildName : joueur sans guilde");
              } else {
                boolean ok = user.renameGuild(g, sn.name);
                if (ok) store.saveGuild(g);
                com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                    g, user.currentGuildRole(), com.perblue.heroes.network.messages.GuildUpdateReason.DEFAULT);
                up.setAsReplyTo(m);
                c.send(up);
                System.out.println("[login] <== SetGuildName '" + sn.name + "'"
                    + (ok ? " appliqué [persisté]" : " refusé (rôle)") + " ==> UserGuildUpdate(DEFAULT)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetGuildDonationRequests) {
              // GUILD AID (#55) — liste des demandes d'aide ACTIVES de la guilde. On BALAIE d'abord les expirées :
              // livraison du partiel au demandeur (courrier GUILD_DONATION_EXPIRED) + retrait.
              ServerGuild g = currentGuild(user);
              if (g != null) sweepExpiredDonations(g);
              com.perblue.heroes.network.messages.GuildDonationRequests resp = g == null
                  ? new com.perblue.heroes.network.messages.GuildDonationRequests()
                  : user.buildGuildDonationRequests(g);
              if (g == null) { resp.guildID = user.currentGuildID(); resp.requests = new java.util.ArrayList<>(); }
              else { store.saveGuild(g); }   // le balayage des demandes expirées est persisté
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== GetGuildDonationRequests → ==> GuildDonationRequests ("
                  + resp.requests.size() + " demande(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.ClaimInactiveGuild) {
              // GUILD #70 — reprise d'une guilde dont le CHEF est inactif. Seuil = logique du jeu
              // (GuildHelper.getClaimLeaderInactiveTime : CHAMPION 7 j, OFFICER 21 j, autres interdits),
              // inactivité mesurée sur BasicUserInfo.userLastActive du chef.
              com.perblue.heroes.network.messages.ClaimInactiveGuild ci =
                  (com.perblue.heroes.network.messages.ClaimInactiveGuild) m;
              long clnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild clg = currentGuild(user);
              String refus = null;
              ServerUser oldLeader = null;
              if (clg == null || clg.guildID != ci.guildID) {
                refus = "guilde inconnue";
              } else {
                try {
                  oldLeader = clg.memberIDs.isEmpty() ? null
                      : store.loadIfExists(clg.memberIDs.get(0), user.shardID);
                } catch (Exception e) { refus = "chef illisible : " + e; }
                if (refus == null) refus = user.claimInactiveGuild(clg, oldLeader, clnow);
              }
              if (refus == null) {
                try {
                  store.saveGuild(clg); store.save(user);
                  if (oldLeader != null) store.save(oldLeader);
                } catch (Exception e) { System.out.println("[login]     ! persistance reprise : " + e); }
                // L'écran de guilde du demandeur doit refléter son nouveau rôle.
                com.perblue.heroes.network.messages.UserGuildUpdate ug =
                    new com.perblue.heroes.network.messages.UserGuildUpdate();
                ug.guildInfo = clg.info;
                ug.setAsReplyTo(m);
                c.send(ug);
                System.out.println("[login] <== ClaimInactiveGuild guilde=" + ci.guildID
                    + " → " + user.userID + " devient RULER (ancien chef "
                    + (oldLeader == null ? "?" : oldLeader.userID) + " rétrogradé) [persisté]");
              } else {
                System.out.println("[login]     ⛔ ClaimInactiveGuild REFUSÉ : " + refus);
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetUserInvasionLeagueInfo
                || m instanceof com.perblue.heroes.network.messages.GetGuildInvasionLeagueInfo) {
              // INVASION #69 — CLASSEMENTS de ligue. Score joueur = points d'invasion (table user_invasion) ;
              // score guilde = somme des points de ses membres. Divisions/seuils viennent des données
              // (LEAGUE_DESIRED_SIZE=50, promotion ≤5, relégation ≥60).
              long lnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              long linv = ServerInvasion.rotation(ServerInvasion.invasionStart(lnow));
              boolean guildSide = m instanceof com.perblue.heroes.network.messages.GetGuildInvasionLeagueInfo;
              if (guildSide) {
                com.perblue.heroes.network.messages.GuildInvasionLeagueInfo gi =
                    new com.perblue.heroes.network.messages.GuildInvasionLeagueInfo();
                gi.invasionID = linv;
                gi.division = 1;
                gi.league = com.perblue.heroes.network.messages.InvasionLeague.BRONZE;
                gi.guilds = new java.util.ArrayList<>(
                    ServerInvasion.guildRanking(store, user.shardID, linv, ServerInvasion.leagueDesiredSize()));
                gi.setAsReplyTo(m);
                c.send(gi);
                System.out.println("[login] <== GetGuildInvasionLeagueInfo → ==> GuildInvasionLeagueInfo ("
                    + gi.guilds.size() + " guilde(s))");
              } else {
                com.perblue.heroes.network.messages.UserInvasionLeagueInfo ui =
                    new com.perblue.heroes.network.messages.UserInvasionLeagueInfo();
                ui.invasionID = linv;
                ui.division = 1;
                ui.league = com.perblue.heroes.network.messages.InvasionLeague.BRONZE;
                ui.users = new java.util.ArrayList<>(
                    ServerInvasion.userRanking(store, user.shardID, linv, ServerInvasion.leagueDesiredSize()));
                ui.setAsReplyTo(m);
                c.send(ui);
                System.out.println("[login] <== GetUserInvasionLeagueInfo → ==> UserInvasionLeagueInfo ("
                    + ui.users.size() + " joueur(s))");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetGMemInvasionRankInfo) {
              // INVASION #69 — onglet « rangs par MEMBRE de guilde » de InvasionRankingsScreen (les 2 autres onglets,
              // GetUser/GuildInvasionLeagueInfo, sont déjà servis ci-dessus). Membres de la guilde triés par points
              // d'invasion (même source user_invasion). Sans ce handler, l'onglet restait sur LOADING (gap A2).
              com.perblue.heroes.network.messages.GetGMemInvasionRankInfo gm =
                  (com.perblue.heroes.network.messages.GetGMemInvasionRankInfo) m;
              long gmnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              long gminv = ServerInvasion.rotation(ServerInvasion.invasionStart(gmnow));
              long gmGuild = gm.guildID > 0 ? gm.guildID : user.currentGuildID();
              com.perblue.heroes.network.messages.GuildMemberInvasionRankInfo gmr =
                  new com.perblue.heroes.network.messages.GuildMemberInvasionRankInfo();
              gmr.guildID = gmGuild; gmr.invasionID = gminv;
              gmr.users = new java.util.ArrayList<>(gmGuild <= 0 ? java.util.Collections.emptyList()
                  : ServerInvasion.guildMemberRanking(store, user.shardID, gmGuild, gminv, 50));
              gmr.setAsReplyTo(m);
              c.send(gmr);
              System.out.println("[login] <== GetGMemInvasionRankInfo(guilde=" + gmGuild + ") → ==> GuildMemberInvasionRankInfo ("
                  + gmr.users.size() + " membre(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.GetBlockedList) {
              // SOCIAL — liste des joueurs BLOQUÉS (BlockedPlayersWindow). Le blocage n'est pas implémenté côté serveur
              // (communautaire) → réponse FIDÈLE = liste VIDE (aucun joueur bloqué). Débloque la fenêtre (gap A2).
              com.perblue.heroes.network.messages.BlockedList bl =
                  new com.perblue.heroes.network.messages.BlockedList();
              bl.users = new java.util.ArrayList<>();
              bl.setAsReplyTo(m);
              c.send(bl);
              System.out.println("[login] <== GetBlockedList → ==> BlockedList (0 — blocage non implémenté)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetUserSaveData) {
              // SAUVEGARDE DE COMPTE (SaveRestoreUserWindow) — UserSaveData = {info, extra, individualUserExtra} = EXACTEMENT
              // ce que le serveur persiste. On ne renvoie QUE les données du DEMANDEUR (garde : userID doit être le sien —
              // ne jamais divulguer la sauvegarde d'autrui). Données réelles (bootData les construit déjà). Gap A2.
              com.perblue.heroes.network.messages.GetUserSaveData gsd =
                  (com.perblue.heroes.network.messages.GetUserSaveData) m;
              if (gsd.userID != 0 && gsd.userID != user.userID) {
                System.out.println("[login]     ⛔ GetUserSaveData REFUSÉ (userID " + gsd.userID + " ≠ " + user.userID + ")");
              } else {
                com.perblue.heroes.network.messages.BootData bd = user.bootData();
                com.perblue.heroes.network.messages.UserSaveData sd =
                    new com.perblue.heroes.network.messages.UserSaveData();
                sd.info = bd.userInfo; sd.extra = bd.userExtra; sd.individualUserExtra = bd.individualUserExtra;
                sd.setAsReplyTo(m);
                c.send(sd);
                System.out.println("[login] <== GetUserSaveData → ==> UserSaveData (compte " + user.userID + ")");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.ClaimInvasionBossRewards) {
              // INVASION #69 — réclamation des récompenses de boss. MODÈLE CLIENT-AUTORITATIF (comme campagne/
              // arène/breaker) établi au bytecode (2026-08-03) : le CLIENT tire lui-même le butin par rôle gagné
              // (InvasionHelper.rollBossRewardLoot, graine RNG invasion du joueur) et l'ENVOIE dans cb.rewards
              // (Map<InvasionBossRewardType, NodeReward{rewardDrops}>) — exactement ce que le joueur a VU. Le
              // serveur LIT ce butin, le crédite, et MARQUE le boss réclamé (anti double-réclamation via
              // bossClaimStatus.rewardsClaimed) au lieu de re-tirer (ce qui divergerait de l'affichage client).
              com.perblue.heroes.network.messages.ClaimInvasionBossRewards cb =
                  (com.perblue.heroes.network.messages.ClaimInvasionBossRewards) m;
              long cnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild cg = currentGuild(user);
              java.util.List<com.perblue.heroes.network.messages.RewardDrop> given = new java.util.ArrayList<>();
              com.perblue.heroes.network.messages.InvasionBossInfo target = null;
              if (cg != null)
                for (com.perblue.heroes.network.messages.InvasionBossInfo b : ServerInvasion.activeBosses(cg, cnow))
                  if (b.bossID == cb.bossID) target = b;
              if (target == null) {
                System.out.println("[login]     ⛔ ClaimInvasionBossRewards : boss " + cb.bossID + " inconnu/expiré");
              } else {
                // ANTI-CHEAT : le joueur doit avoir un DROIT réel (découvreur ou contributeur) sur ce boss vaincu.
                boolean isFinder = target.foundByUser != null && target.foundByUser.iD == user.userID;
                Object dd = target.damageDone == null ? null : target.damageDone.get(user.userID);
                boolean participated = dd instanceof com.perblue.heroes.network.messages.InvasionBossDamageData
                    && ((com.perblue.heroes.network.messages.InvasionBossDamageData) dd).damage > 0;
                long invID = ServerInvasion.rotation(ServerInvasion.invasionStart(cnow));
                com.perblue.heroes.network.messages.UserInvasionData cud =
                    ServerInvasion.loadOrResetUserData(store.loadUserInvasion(user.shardID, user.userID),
                        user.userID, user.currentGuildID(), invID);
                if (cud.bossClaimStatus == null) cud.bossClaimStatus = new java.util.HashMap<>();
                Object prevCs = cud.bossClaimStatus.get(cb.bossID);
                boolean already = prevCs instanceof com.perblue.heroes.network.messages.BossClaimStatusData
                    && ((com.perblue.heroes.network.messages.BossClaimStatusData) prevCs).rewardsClaimed;
                java.util.List<com.perblue.heroes.network.messages.InvasionBossRewardType> roles =
                    ServerInvasion.earnedBossRoles(target, user.userID);
                if (!isFinder && !participated) {
                  System.out.println("[login]     ⛔ ClaimInvasionBossRewards : aucune participation à ce boss");
                } else if (already) {
                  System.out.println("[login]     ⛔ ClaimInvasionBossRewards : boss " + cb.bossID + " déjà réclamé");
                } else {
                  // Butin RENVOYÉ par le client (par rôle) — on agrège tous les NodeReward.rewardDrops.
                  if (cb.rewards != null)
                    for (Object v : ((java.util.Map<?, ?>) cb.rewards).values())
                      if (v instanceof com.perblue.heroes.network.messages.NodeReward
                          && ((com.perblue.heroes.network.messages.NodeReward) v).rewardDrops != null)
                        for (Object o : ((com.perblue.heroes.network.messages.NodeReward) v).rewardDrops)
                          if (o instanceof com.perblue.heroes.network.messages.RewardDrop)
                            given.add((com.perblue.heroes.network.messages.RewardDrop) o);
                  if (!given.isEmpty()) user.grantRewards(given);
                  // MARQUE réclamé (persisté) → l'aperçu repasse en DEFAULT et une 2e réclamation est refusée.
                  com.perblue.heroes.network.messages.BossClaimStatusData ncs =
                      new com.perblue.heroes.network.messages.BossClaimStatusData();
                  ncs.rewardsClaimed = true;
                  ncs.escapeClaimed = false;
                  ncs.rewardsEarned = new java.util.ArrayList<>(roles);
                  ((java.util.Map<Object, Object>) cud.bossClaimStatus).put(cb.bossID, ncs);
                  try {
                    store.saveUserInvasion(user.shardID, user.userID, ServerInvasion.userDataToBytes(cud));
                    if (!given.isEmpty()) store.save(user);
                  } catch (Exception e) {
                    System.out.println("[login]     ! persistance récompenses boss : " + e); }
                  System.out.println("[login] <== ClaimInvasionBossRewards boss=" + cb.bossID + " rôles=" + roles
                      + " → " + given.size() + " récompense(s) créditée(s) [persisté]");
                }
              }
              com.perblue.heroes.network.messages.ClaimInvasionBossRewards resp =
                  new com.perblue.heroes.network.messages.ClaimInvasionBossRewards();
              resp.bossID = cb.bossID;
              resp.rewards = new java.util.HashMap<>();
              resp.setAsReplyTo(m);
              c.send(resp);
              // PUSH de l'état à jour (bossClaimStatus.rewardsClaimed=true) → la carte du boss repasse en DEFAULT.
              sendInvasionInfo(c, user, null, true);
            } else if (m instanceof com.perblue.heroes.network.messages.GetBreakerQuest) {
              // INVASION #69 — ENTRÉE du mode SOLO. Manque RÉEL trouvé EN JEU : ce message arrivait sans
              // handler, l'écran BREAKER QUEST restait donc entièrement VIDE. Cf. ServerInvasionBreaker.
              long qnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerInvasionObject qinv = ServerInvasionObject.at(qnow);
              long qid = ServerInvasion.rotation(ServerInvasion.invasionStart(qnow));
              com.perblue.heroes.network.messages.UserInvasionData qud = null;
              try {
                qud = ServerInvasion.loadOrResetUserData(
                    store.loadUserInvasion(user.shardID, user.userID), user.userID,
                    user.currentGuildID(), qid);
              } catch (Exception e) { System.out.println("[login]     ! état invasion (quête) : " + e); }
              com.perblue.heroes.network.messages.BreakerQuest bq =
                  ServerInvasionBreaker.buildQuest(user, qud, qinv, qid);
              // Forme requête/réponse, comme tous les autres handlers. RÉSOLU EN JEU (2026-08-02) : la quête
              // s'affiche ET se joue dès que `buildQuest` renseigne `activeBreakerFight` (le champ que le client
              // lit pour activer l'aperçu/START du combat de la salle active). Cf. docs/INVASION.md.
              bq.setAsReplyTo(m);
              c.send(bq);
              System.out.println("[login] <== GetBreakerQuest → ==> BreakerQuest ("
                  + bq.basicBreakerFights.size() + " combat(s), à partir de la salle "
                  + (qud != null ? qud.breakerBattlesWon : 0) + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.GetUserChallengeDataExtra) {
              // CHALLENGES #72 incrément 4 — VUE des stickers d'un joueur (StickerOverviewWindow) : le client envoie
              // GetUserChallengeDataExtra{targetUserID} et attend un UserChallengeDataExtra en réponse. Le serveur
              // charge l'état de défis PERSISTÉ du joueur ciblé (soi-même ou un autre membre) et le renvoie
              // (freshData si le joueur n'a pas encore d'état). Requête/réponse (patron GetSurge).
              com.perblue.heroes.network.messages.GetUserChallengeDataExtra gq =
                  (com.perblue.heroes.network.messages.GetUserChallengeDataExtra) m;
              ServerUser target = user;
              if (gq.targetUserID != user.userID) {
                try { target = store.loadIfExists(gq.targetUserID, user.shardID); }
                catch (Exception e) { System.out.println("[login]     ! chargement joueur défis: " + e); target = null; }
              }
              com.perblue.heroes.network.messages.UserChallengeDataExtra reply =
                  target != null ? ServerChallenges.load(target) : ServerChallenges.freshData(gq.targetUserID);
              reply.userID = gq.targetUserID;
              reply.setAsReplyTo(m);
              c.send(reply);
              System.out.println("[login] <== GetUserChallengeDataExtra(" + gq.targetUserID
                  + ") → ==> UserChallengeDataExtra (" + (reply.slots == null ? 0 : reply.slots.size()) + " slots)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetTrialEventData) {
              // FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 2 — le client envoie GetTrialEventData{eventID} et attend un
              // TrialEventData (état per-user : chances/resets/sous-trials) pour rendre l'écran du trial. Builder ABSENT du
              // jar client (état backend PerBlue) → construit+persisté serveur-autoritativement (ServerTrials, patron ArenaInfo).
              com.perblue.heroes.network.messages.GetTrialEventData req =
                  (com.perblue.heroes.network.messages.GetTrialEventData) m;
              // incr. 4 : applique le reset quotidien GRATUIT (auto, checkForDailyReset) → chances rafraîchies chaque jour (autorité).
              com.perblue.heroes.network.messages.TrialEventData td = user.refreshTrialDailyReset(req.eventID);
              try { store.save(user); } catch (Exception e) { System.out.println("[login]     ! persist trial: " + e); }
              td.setAsReplyTo(m);
              c.send(td);
              System.out.println("[login] <== GetTrialEventData(" + req.eventID + ") → ==> TrialEventData (chancesUsed="
                  + td.chancesUsed + ", sous-trials=" + (td.subtrials == null ? 0 : td.subtrials.size()) + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.GetAllContestData) {
              // CONTEST incr. 2 — le client envoie GetAllContestData et attend un AllContestData (Map<contestID, ContestData> :
              // points de progression/rang + compteurs de tâches) pour rendre l'écran CONTESTS. Builder ABSENT du jar client
              // (état backend PerBlue) → construit+persisté serveur-autoritativement (ServerContest, patron TrialEventData/ArenaInfo).
              com.perblue.heroes.network.messages.AllContestData acd = ServerContestData.response(user, store);
              try { store.save(user); } catch (Exception e) { System.out.println("[login]     ! persist contest: " + e); }
              acd.setAsReplyTo(m);
              c.send(acd);
              System.out.println("[login] <== GetAllContestData → ==> AllContestData (" + (acd.contests == null ? 0 : acd.contests.size()) + " contest(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.GetContestHallOfFame) {
              // CONTEST incr. 5 — l'écran CONTESTS (onglet HALL OF FAME) requiert ces 3 réponses au chargement, sinon il
              // reste bloqué sur « LOADING … ». Pas d'historique de vainqueurs sur ce serveur communautaire → réponses VIDES
              // (affichage « pas encore de hall of fame »), ce qui débloque l'écran vers la vue du contest actif.
              com.perblue.heroes.network.messages.ContestHallOfFames r = new com.perblue.heroes.network.messages.ContestHallOfFames();
              r.contestHallOfFames = new java.util.ArrayList<>(); r.startIndex = 0;
              r.setAsReplyTo(m); c.send(r);
              System.out.println("[login] <== GetContestHallOfFame → ==> ContestHallOfFames (vide)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetLastContestWinners) {
              com.perblue.heroes.network.messages.LastContestWinners r = new com.perblue.heroes.network.messages.LastContestWinners();
              r.lastWinnerGuildInfo = new java.util.ArrayList<>(); r.lastWinnerUserInfo = new java.util.ArrayList<>();
              r.setAsReplyTo(m); c.send(r);
              System.out.println("[login] <== GetLastContestWinners → ==> LastContestWinners (vide)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetHallOfFameRanks) {
              com.perblue.heroes.network.messages.HallOfFameRanks r = new com.perblue.heroes.network.messages.HallOfFameRanks();
              r.ranks = new java.util.ArrayList<>();
              r.setAsReplyTo(m); c.send(r);
              System.out.println("[login] <== GetHallOfFameRanks → ==> HallOfFameRanks (vide)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetExpedition) {
              // EXPEDITION #72 incr. 1 — rafraîchissement d'un run ACTIF : le client envoie GetExpedition, le serveur
              // répond GetExpeditionResponse (patron GetSurge) avec le run PERSISTÉ (ou vide → sélection de difficulté).
              // Gate Unlockable.EXPEDITION (TL25) = verrou CLIENT ; le serveur répond, ne désactive rien.
              com.perblue.heroes.network.messages.GetExpeditionResponse er = ServerExpedition.response(user);
              er.setAsReplyTo(m);
              c.send(er);
              System.out.println("[login] <== GetExpedition → ==> GetExpeditionResponse (expeditionID=" + er.expeditionID
                  + ", nœuds=" + (er.currentExpedition == null || er.currentExpedition.defenders == null
                      ? 0 : er.currentExpedition.defenders.size()) + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.ResetExpedition) {
              // EXPEDITION #72 incr. 2 — VRAI point d'entrée en jeu : sur l'ouverture d'un compte sans run, le client
              // envoie ResetExpedition{difficulty, desiredWard, firstEverReset} et attend le run généré (l'écran reste
              // « SCANNING CITY MAP » sinon). Le serveur GÉNÈRE le run (ServerExpedition.resetRun), persiste, répond.
              com.perblue.heroes.network.messages.ResetExpedition re =
                  (com.perblue.heroes.network.messages.ResetExpedition) m;
              com.perblue.heroes.network.messages.ExpeditionRunData run =
                  ServerExpedition.resetRun(user, re.difficulty, re.desiredWard, re.firstEverReset);
              if (run != null) {
                try { store.save(user); } catch (Exception e) {
                  System.out.println("[login]     ! persist expedition: " + e); }
                com.perblue.heroes.network.messages.ResetExpeditionResponse rr =
                    ServerExpedition.resetResponse(user, run);
                rr.setAsReplyTo(m);
                c.send(rr);
                System.out.println("[login] <== ResetExpedition(diff=" + re.difficulty + ", firstEver="
                    + re.firstEverReset + ") → run généré " + run.defenders.size() + " nœuds [persisté]");
              } else {
                System.out.println("[login] <== ResetExpedition(diff=" + re.difficulty + ") refusé (économie)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.ExpeditionAttack) {
              // EXPEDITION #72 incr. 3 — combat de nœud (client-autoritatif, patron CampaignAttack). Le client joue
              // le combat et envoie l'issue ; le serveur ré-exécute l'autorité (anti-triche nœud, progression, loot).
              com.perblue.heroes.network.messages.ExpeditionAttack ea =
                  (com.perblue.heroes.network.messages.ExpeditionAttack) m;
              boolean ok;
              try { ok = ServerExpedition.recordAttack(user, ea); }
              catch (Throwable t) { ok = false; System.out.println("[login]     ! ExpeditionAttack: " + t); }
              if (ok) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persist expedition: " + e); } }
              System.out.println("[login] <== ExpeditionAttack : nœud=" + ea.nodeIndex + " outcome="
                  + (ea.base == null ? "?" : ea.base.outcome) + (ok ? " → appliqué [persisté]" : " refusé"));
            } else if (m instanceof com.perblue.heroes.network.messages.ExpeditionRaid) {
              // EXPEDITION #72 incr. 4 — RAID (saute le combat, complète toute l'expédition). Client-autoritatif :
              // le client exécute doRaid localement puis envoie ExpeditionRaid{rewards, difficulty} ; le serveur
              // RÉ-EXÉCUTE l'autorité (même doRaid : gate raidable, débit tickets, crédit tous nœuds, complétion).
              com.perblue.heroes.network.messages.ExpeditionRaid er =
                  (com.perblue.heroes.network.messages.ExpeditionRaid) m;
              boolean ok;
              try { ok = ServerExpedition.recordRaid(user, er); }
              catch (Throwable t) { ok = false; System.out.println("[login]     ! ExpeditionRaid: " + t); }
              if (ok) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persist expedition: " + e); } }
              System.out.println("[login] <== ExpeditionRaid : diff=" + er.difficulty
                  + (ok ? " → appliqué [persisté]" : " refusé"));
            } else if (m instanceof com.perblue.heroes.network.messages.EnchantItem) {
              // ENCHANTING #72 — enchantement d'équipement (message dédié). Le serveur ré-exécute l'autorité
              // (EnchantingHelper.enchantItem : débit or/diamants + matériaux, montée d'enchant), persiste.
              com.perblue.heroes.network.messages.EnchantItem ei =
                  (com.perblue.heroes.network.messages.EnchantItem) m;
              boolean ok;
              try { ok = user.applyEnchantItem(ei); }
              catch (Throwable t) { ok = false; System.out.println("[login]     ! EnchantItem: " + t); }
              if (ok) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persist enchant: " + e); } }
              System.out.println("[login] <== EnchantItem : " + ei.hero + "/" + ei.slot
                  + (ok ? " → appliqué [persisté]" : " refusé"));
            } else if (m instanceof com.perblue.heroes.network.messages.EnhanceMaxPrimeBadge) {
              // ENCHANTING #72 — MAX-UPGRADE PRIME BADGES (bouton « MAX » : enchante TOUS les slots d'un coup).
              // Serveur autoritatif : ré-dérive le plan depuis l'état persisté (buildMaxUpgradePlanForHero) puis
              // l'applique (applyMaxUpgradePlanForHero), garde-fou OR agrégé (plan.totalGold), persiste.
              com.perblue.heroes.network.messages.EnhanceMaxPrimeBadge pb =
                  (com.perblue.heroes.network.messages.EnhanceMaxPrimeBadge) m;
              boolean ok;
              try { ok = user.applyMaxPrimeBadge(pb); }
              catch (Throwable t) { ok = false; System.out.println("[login]     ! EnhanceMaxPrimeBadge: " + t); }
              if (ok) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persist prime-badge: " + e); } }
              System.out.println("[login] <== EnhanceMaxPrimeBadge : " + pb.unitType
                  + (ok ? " → appliqué [persisté]" : " refusé"));
            } else if (m instanceof com.perblue.heroes.network.messages.OpenExpeditionChest) {
              // EXPEDITION #72 incr. 7 — coffre d'expédition. Client-autoritatif : le client ouvre localement puis
              // envoie OpenExpeditionChest{rewardDrops} ; le serveur RÉ-EXÉCUTE l'autorité (openChest : roule + crédite).
              com.perblue.heroes.network.messages.OpenExpeditionChest oc =
                  (com.perblue.heroes.network.messages.OpenExpeditionChest) m;
              boolean ok;
              try { ok = ServerExpedition.recordOpenChest(user, oc); }
              catch (Throwable t) { ok = false; System.out.println("[login]     ! OpenExpeditionChest: " + t); }
              if (ok) { try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persist expedition: " + e); } }
              System.out.println("[login] <== OpenExpeditionChest"
                  + (ok ? " → appliqué [persisté]" : " refusé"));
            } else if (m instanceof com.perblue.heroes.network.messages.GetSurge) {
              // SURGE #72 — OUVERTURE de l'écran : le client (GameMain) envoie GetSurge, le serveur renvoie l'état
              // PARTAGÉ de la guilde (ServerSurgeState, reconstruit si nouveau surge). GetSurge → SurgeData
              // (même patron que GetInvasionInfo → InvasionInfo). Le gate Unlockable.SURGE_OBJECTIVES est un
              // verrou CLIENT (le client n'envoie pas GetSurge s'il est gaté) — le serveur RÉPOND, ne désactive rien.
              long snow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild sg = currentGuild(user);
              com.perblue.heroes.network.messages.SurgeData sd =
                  sg != null ? ServerSurgeState.loadOrReset(store, sg, snow) : ServerSurgeState.emptySurge(snow);
              // Personnalisation par-viewer (§6) : récompenses NON réclamées du surge précédent + yourRaidsUsed.
              if (sg != null) ServerSurgeState.personalize(store, sg, sd, user.userID);
              sd.setAsReplyTo(m);
              c.send(sd);
              System.out.println("[login] <== GetSurge → ==> SurgeData (surgeID=" + sd.surgeID
                  + ", membres=" + (sd.members == null ? 0 : sd.members.size()) + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.StartSurgeAttack) {
              // SURGE #72 — DÉMARRAGE d'un combat de district : le client (SurgeAttackScreen) envoie son lineup +
              // le district ; le serveur renvoie le lineup DÉFENSEUR (roster réel de l'adversaire ou bot) + un
              // raidID + les modificateurs, et verrouille l'adversaire. Combat client-autoritatif (issue via SurgeAttack).
              com.perblue.heroes.network.messages.StartSurgeAttack ssa =
                  (com.perblue.heroes.network.messages.StartSurgeAttack) m;
              long ssnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild ssg = currentGuild(user);
              if (ssg != null) {
                com.perblue.heroes.network.messages.SurgeData ssd = ServerSurgeState.loadOrReset(store, ssg, ssnow);
                com.perblue.heroes.network.messages.StartSurgeAttackResponse resp =
                    ServerSurgeState.startAttack(store, ssg, ssd, ssa.district, ssnow);
                ServerSurgeState.save(store, ssg, ssd.surgeID, ssd);
                resp.setAsReplyTo(m);
                c.send(resp);
                System.out.println("[login] <== StartSurgeAttack(" + ssa.district + ") → ==> StartSurgeAttackResponse (raid="
                    + resp.raidID + ", defenseurs=" + (resp.heroes == null ? 0 : resp.heroes.size()) + ")");
              } else {
                System.out.println("[login] <== StartSurgeAttack ignoré (joueur hors guilde)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.SurgeAttack) {
              // SURGE #72 — ISSUE d'un combat de district : le serveur exécute la logique autoritative
              // (SurgeHelper.recordOutcome via ServerSurgeCombat), marque l'adversaire vaincu à la victoire,
              // persiste l'état PARTAGÉ, et diffuse le delta (SurgeUpdate) à la guilde.
              com.perblue.heroes.network.messages.SurgeAttack sa =
                  (com.perblue.heroes.network.messages.SurgeAttack) m;
              long sanow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild sag = currentGuild(user);
              if (sag != null) {
                com.perblue.heroes.network.messages.SurgeData sad = ServerSurgeState.loadOrReset(store, sag, sanow);
                com.perblue.heroes.network.messages.SurgeUpdate up = ServerSurgeState.applyAttack(sag, sad, user, sa);
                ServerSurgeState.save(store, sag, sad.surgeID, sad);
                store.save(user);   // CONTEST : persiste le crédit des tâches de surge (blob per-user muté par applyAttack)
                up.setAsReplyTo(m);
                c.send(up);
                pushToGuild(sag, user.userID, up);          // diffusion temps réel aux autres membres en ligne
                System.out.println("[login] <== SurgeAttack(" + sa.district + ", " + (sa.base != null ? sa.base.outcome : null)
                    + ") → ==> SurgeUpdate (+" + up.surgePointDelta + " pts, districts+" + up.districtsClearedDelta + ") [persisté]");
              } else {
                System.out.println("[login] <== SurgeAttack ignoré (joueur hors guilde)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.SurgeClaimRewards) {
              // SURGE #72 — RÉCLAMATION des récompenses du surge précédent : le client (SurgeResultsWindow) envoie
              // SurgeClaimRewards{surgeID} PUIS se crédite localement CRYPT_TOKENS + GOLD ; le serveur applique le
              // MÊME crédit de façon autoritative (montants figés au registre de bascule), une seule fois.
              com.perblue.heroes.network.messages.SurgeClaimRewards scr =
                  (com.perblue.heroes.network.messages.SurgeClaimRewards) m;
              ServerGuild scg = currentGuild(user);
              if (scg != null) {
                com.perblue.heroes.network.messages.SurgeRewards r =
                    ServerSurgeState.claimRewards(store, scg, user, scr.surgeID);
                r.setAsReplyTo(m);
                c.send(r);
                System.out.println("[login] <== SurgeClaimRewards(surge=" + scr.surgeID + ") → ==> SurgeRewards (+"
                    + r.totalTokens + " tokens, +" + r.totalGold + " or)");
              } else {
                System.out.println("[login] <== SurgeClaimRewards ignoré (joueur hors guilde)");
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetInvasionBosses) {
              // INVASION #69 — boss PARTAGÉS de la guilde (état opérateur persisté, v7) : les expirés
              // (au-delà de BOSS_FIGHT_TIME_LIMIT) sont retirés à la lecture.
              com.perblue.heroes.network.messages.GetInvasionBosses gb =
                  (com.perblue.heroes.network.messages.GetInvasionBosses) m;
              long bnow2 = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild bg = currentGuild(user);
              com.perblue.heroes.network.messages.InvasionBosses ib =
                  new com.perblue.heroes.network.messages.InvasionBosses();
              ib.invasionID = gb.invasionID != 0 ? gb.invasionID
                  : ServerInvasion.rotation(ServerInvasion.invasionStart(bnow2));
              java.util.List<com.perblue.heroes.network.messages.InvasionBossInfo> activeBosses =
                  ServerInvasion.activeBosses(bg, bnow2);
              // Vue PAR JOUEUR : sans actionState=FIGHT, taper le boss n'ouvre pas l'aperçu de combat côté
              // client (InvasionBossCard.onCardPressed). On la renseigne pour chaque boss servi.
              com.perblue.heroes.network.messages.UserInvasionData bud = null;
              try {
                long binv = ServerInvasion.rotation(ServerInvasion.invasionStart(bnow2));
                bud = ServerInvasion.loadOrResetUserData(
                    store.loadUserInvasion(user.shardID, user.userID), user.userID, user.currentGuildID(), binv);
              } catch (Exception ignore) {}
              for (com.perblue.heroes.network.messages.InvasionBossInfo bi : activeBosses)
                ServerInvasion.applyBossActionState(bi, user, bud);
              ib.bosses = new java.util.ArrayList<>(activeBosses);
              ib.bossFeed = new java.util.ArrayList<>();
              if (bg != null) { try { store.saveGuild(bg); } catch (Exception ignore) {} }
              ib.setAsReplyTo(m);
              c.send(ib);
              System.out.println("[login] <== GetInvasionBosses → ==> InvasionBosses ("
                  + ib.bosses.size() + " boss actif(s))");
              // Le joueur est SUR l'écran boss (contenu chargé) → on POUSSE l'état invasion avec bossClaimStatus
              // renseigné, pour qu'un boss VAINCU d'une session précédente devienne réclamable sans re-login.
              sendInvasionInfo(c, user, null, true);
            } else if (m instanceof com.perblue.heroes.network.messages.StartInvasionBossAttack) {
              // INVASION #69 — le client OUVRE un combat de BOSS : réponse StartBossAttackResponse (exigée par
              // le ctor InvasionBossAttackScreen) avec le LINEUP du boss, + acquisition du VERROU exclusif.
              com.perblue.heroes.network.messages.StartInvasionBossAttack sba =
                  (com.perblue.heroes.network.messages.StartInvasionBossAttack) m;
              long snow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild sg = currentGuild(user);
              com.perblue.heroes.network.messages.InvasionBossInfo sboss = null;
              if (sg != null)
                for (com.perblue.heroes.network.messages.InvasionBossInfo b : ServerInvasion.activeBosses(sg, snow))
                  if (b.bossID == sba.bossID) sboss = b;
              com.perblue.heroes.network.messages.StartBossAttackResponse sresp =
                  new com.perblue.heroes.network.messages.StartBossAttackResponse();
              sresp.bossID = sba.bossID;
              sresp.damageMultiplier = sba.damageMultiplier;
              sresp.selectedBoosts = new java.util.ArrayList<>();
              sresp.bossLineup = new java.util.ArrayList<>();
              if (sboss != null) {
                if (sboss.lineup != null) ((java.util.List<Object>) sresp.bossLineup).addAll(sboss.lineup);
                sg.lockBoss(sba.bossID, user.userID, snow, ServerInvasion.attackLockDuration());
                try { store.saveGuild(sg); } catch (Exception ignore) {}
              }
              sresp.setAsReplyTo(m);
              c.send(sresp);
              System.out.println("[login] <== StartInvasionBossAttack bossID=" + sba.bossID + " → ==> "
                  + "StartBossAttackResponse (" + (sboss == null ? "boss introuvable" : "lineup "
                  + (sboss.lineup == null ? 0 : sboss.lineup.size()) + ", verrou acquis") + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.InvasionBossAttack) {
              // INVASION #69 — ISSUE d'un combat de boss. Le serveur AUTORITATIF débite les clés (BREAKER),
              // cumule les DÉGÂTS DU JOUEUR (source fidèle = base.defenders[].units[].damageTaken de la vedette,
              // = getBossDamage du client, cf. ServerInvasion.extractBossDamage) et persiste l'état partagé.
              com.perblue.heroes.network.messages.InvasionBossAttack ba =
                  (com.perblue.heroes.network.messages.InvasionBossAttack) m;
              long banow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerGuild bg2 = currentGuild(user);
              try {
                long invID = ServerInvasion.rotation(ServerInvasion.invasionStart(banow));
                com.perblue.heroes.network.messages.UserInvasionData ud = ServerInvasion.loadOrResetUserData(
                    store.loadUserInvasion(user.shardID, user.userID), user.userID, user.currentGuildID(), invID);
                com.perblue.heroes.network.messages.InvasionBossInfo boss = null;
                if (bg2 != null)
                  for (com.perblue.heroes.network.messages.InvasionBossInfo b : ServerInvasion.activeBosses(bg2, banow))
                    if (b.bossID == ba.bossID) boss = b;
                com.perblue.heroes.network.messages.UnitType bossType = null;
                if (boss != null) {
                  try { bossType = com.perblue.heroes.game.logic.InvasionHelper.getBossUnitData(boss).getType(); }
                  catch (Throwable ignore) {}
                }
                long dmg = ServerInvasion.extractBossDamage(ba, bossType);
                ServerInvasion.BossOutcome bo =
                    ServerInvasion.attackBoss(bg2, user, ud, ba.bossID, ba.damageMultiplier, dmg, banow);
                // CONTEST : le chemin serveur d'invasion accumule les dégâts MANUELLEMENT (n'appelle pas
                // InvasionHelper.recordBossFightOutcome qui, lui, déclencherait onInvasionBossAttack) → on appelle le hook
                // EXPLICITEMENT (§3) avec les lineups du wire. Tâche INVASION/BATTLE_* → blob per-user (persisté par store.save).
                if (ba.base != null && ba.base.outcome != null) {
                  final com.perblue.heroes.network.messages.CombatOutcome io = ba.base.outcome;
                  final java.util.Collection ia = ba.base.attackers != null ? ba.base.attackers : new java.util.ArrayList<>();
                  final java.util.Collection idf = ba.base.defenders != null ? ba.base.defenders : new java.util.ArrayList<>();
                  com.perblue.heroes.game.objects.User iGameUser = user.gameUser();
                  ServerContestData.record(user, iGameUser, u ->
                      com.perblue.heroes.game.logic.ContestHelper.onInvasionBossAttack(u, io, ia, idf));
                }
                store.saveUserInvasion(user.shardID, user.userID, ServerInvasion.userDataToBytes(ud));
                store.save(user);
                if (bg2 != null) store.saveGuild(bg2);
                System.out.println("[login] <== InvasionBossAttack bossID=" + ba.bossID + " ×" + ba.damageMultiplier
                    + " outcome=" + (ba.base == null ? "?" : ba.base.outcome) + " → " + bo + " [persisté]");
                // PUSH de l'état invasion à jour (yourData.bossClaimStatus) : le client ne (re)demande jamais
                // GetInvasionInfo, et son ClientInvasionUser n'est peuplé qu'au boot. Sans ce push, un boss
                // qu'on vient de VAINCRE reste non-réclamable (taper le boss KO ne ferait rien). Mirroir du
                // push spontané au boot (cf. sendInvasionInfo). RÉSOLU EN JEU (2026-08-03). populateClaim=true :
                // après un combat, le contenu (stats patchées) est chargé → getBossHP sûr.
                sendInvasionInfo(c, user, null, true);
              } catch (Exception e) {
                System.out.println("[login]     ! InvasionBossAttack : " + e);
              }
            } else if (m instanceof com.perblue.heroes.network.messages.InvasionBreakerAttackStart) {
              // INVASION #69 — le client OUVRE un combat de breaker : le serveur lui renvoie la COMPOSITION
              // adverse, tirée de la table de drop DU JEU (invasion_breaker_fight_comp.tab) DANS LE CONTEXTE
              // DU JOUEUR (sans quoi la table retombe sur des mobs génériques). Graine dérivée de
              // (invasion, room, joueur) → composition STABLE tant que la salle et l'invasion ne changent pas.
              com.perblue.heroes.network.messages.InvasionBreakerAttackStart bs =
                  (com.perblue.heroes.network.messages.InvasionBreakerAttackStart) m;
              long bnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerInvasionObject sinv = ServerInvasionObject.at(bnow);
              // ⚠️ La réponse est un BreakerUserFightInfo (que GameMain$119 range dans
              // BreakerQuest.activeBreakerFight et dont InvasionClientHelper.getBreakerDefenderLineup tire les
              // défenseurs), PAS un BreakerUserFightData — ce dernier attend des HeroBattleData et sa
              // sérialisation levait ClassCastException à l'écriture (défaut invisible headless). La MÊME
              // graine que la liste (fightSeed) garantit que le combat entré == celui montré dans la liste.
              long seed = ServerInvasionBreaker.fightSeed(sinv.getID(), bs.room, user);
              java.util.List<?> comp = ServerInvasion.rollBreakerComposition(user, bs.room, sinv, seed);
              java.util.List<ServerInvasionBreaker.Group> bgroups = ServerInvasionBreaker.groups(user, comp);
              com.perblue.heroes.network.messages.BreakerUserFightInfo fi =
                  ServerInvasionBreaker.toFightInfo(bs.room, bgroups);
              fi.setAsReplyTo(m);
              c.send(fi);
              System.out.println("[login] <== InvasionBreakerAttackStart room=" + bs.room + " ward=" + bs.ward
                  + " → ==> BreakerUserFightInfo (" + fi.breakerLineup.size() + " breaker(s) + "
                  + fi.wardLineups.size() + " garde(s), composition de " + comp.size() + " unités)");
            } else if (m instanceof com.perblue.heroes.network.messages.InvasionBreakerAttack) {
              // INVASION #69 — issue d'un combat de breaker. Le serveur AUTORITATIF débite l'énergie
              // d'invasion et accorde or/points/BREAKER selon les FORMULES DU JEU, met à jour l'état
              // d'invasion du joueur et persiste (comme CampaignAttack).
              com.perblue.heroes.network.messages.InvasionBreakerAttack ba =
                  (com.perblue.heroes.network.messages.InvasionBreakerAttack) m;
              long anow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              boolean won = ba.base != null
                  && ba.base.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN;
              try {
                long invID = ServerInvasion.rotation(ServerInvasion.invasionStart(anow));
                com.perblue.heroes.network.messages.UserInvasionData ud = ServerInvasion.loadOrResetUserData(
                    store.loadUserInvasion(user.shardID, user.userID), user.userID, user.currentGuildID(), invID);
                ServerInvasion.BreakerOutcome bo =
                    ServerInvasion.resolveBreakerFight(user, ud, ba.room, won, anow);
                store.saveUserInvasion(user.shardID, user.userID, ServerInvasion.userDataToBytes(ud));
                store.save(user);
                System.out.println("[login] <== InvasionBreakerAttack room=" + ba.room
                    + " outcome=" + (ba.base == null ? "?" : ba.base.outcome) + " → " + bo + " [persisté]");
              } catch (Exception e) {
                System.out.println("[login]     ! InvasionBreakerAttack : " + e);
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetInvasionInfo) {
              sendInvasionInfo(c, user, m, true);
            } else if (m instanceof com.perblue.heroes.network.messages.GetGuildGiftRewards) {
              // GUILD CRATE / cadeaux de guilde (#58/#66) — cadeaux accumulés de la guilde (offreurs + récompenses).
              com.perblue.heroes.network.messages.GetGuildGiftRewards req =
                  (com.perblue.heroes.network.messages.GetGuildGiftRewards) m;
              ServerGuild gg = currentGuild(user);
              com.perblue.heroes.network.messages.GuildGiftRewards resp = user.buildGuildGiftRewards(gg);
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== GetGuildGiftRewards(" + req.eventID + ") → ==> GuildGiftRewards ("
                  + resp.rewards.size() + " récompense(s), " + resp.gifters.size() + " offreur(s))");
            } else if (m instanceof com.perblue.heroes.network.messages.GetUnlockedGuildAvatars) {
              // Avatars/bordures de guilde débloqués — CALCULÉS depuis le niveau de guilde (ServerUser
              // .unlockedGuildAvatars lit guild_avatars.tab via la table cumulative du jeu). Vide = guilde
              // sans avatar au niveau courant (la table est cumulative, aucun accès manuel aux données).
              ServerGuild ag = user.inGuild() ? store.loadGuild(user.shardID, user.currentGuildID()) : null;
              com.perblue.heroes.network.messages.UnlockedGuildAvatars resp =
                  new com.perblue.heroes.network.messages.UnlockedGuildAvatars();
              resp.avatars = user.unlockedGuildAvatars(ag);
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== GetUnlockedGuildAvatars → ==> UnlockedGuildAvatars (" + resp.avatars.size() + ")");
            } else if (m instanceof com.perblue.heroes.network.messages.SendChat) {
              // CHAT de guilde (#59) : le client (ChatWindow) envoie un SendChat pour le salon GUILD, SANS l'afficher
              // localement (contrairement au chat global) → il attend que le serveur lui RENVOIE le Chat. Le serveur
              // construit le Chat autoritatif, l'ARCHIVE dans la guilde (persisté), et le renvoie ; le listener
              // GameMain(Chat) → SocialDataManager.addChat l'affiche. (Salons non-guilde : ignorés pour l'instant —
              // GLOBAL/VIP nécessitent un bus inter-shard, cf. #59 note ; seul GUILD est natif ici.)
              com.perblue.heroes.network.messages.SendChat sc = (com.perblue.heroes.network.messages.SendChat) m;
              com.perblue.heroes.network.messages.ChatRoomType room = sc.room;
              boolean guildRoom = room == com.perblue.heroes.network.messages.ChatRoomType.GUILD;
              ServerGuild g = guildRoom ? currentGuild(user) : null;
              if (!guildRoom) {
                System.out.println("[login]     ~ SendChat salon " + room + " ignoré (seul GUILD est natif ici)");
              } else if (g == null) {
                System.out.println("[login]     ⛔ SendChat GUILD REFUSÉ : joueur sans guilde");
              } else {
                com.perblue.heroes.network.messages.Chat chat = user.buildAndStoreGuildChat(g, sc);
                if (chat == null) {
                  System.out.println("[login]     ~ SendChat GUILD : message vide, ignoré");
                } else {
                  store.saveGuild(g);
                  c.send(chat);   // renvoyé à l'expéditeur (affichage)
                  // BROADCAST temps réel (#65) : pousse le MÊME Chat autoritatif à tous les autres membres de la
                  // guilde actuellement EN LIGNE (leur listener GameMain(Chat)→SocialDataManager.addChat l'affiche).
                  pushToGuild(g, user.userID, chat);
                  System.out.println("[login] <== SendChat GUILD « " + chat.message + " » (#" + chat.chatID
                      + ") ==> Chat [archivé " + g.guildChatWire.size() + ", persisté, diffusé]");
                }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GuildDonation) {
              // DON / GUILD AID (#55b) : un membre AIDE une demande. doDonation débite le donateur + décrémente la
              // demande ; à saturation (dons restants=0) → demande REMPLIE : on livre la récompense au DEMANDEUR par
              // COURRIER (GUILD_DONATION_SUCCESS) et on retire la demande. Réponse GuildDonationRequestUpdate au donateur.
              com.perblue.heroes.network.messages.GuildDonation gd = (com.perblue.heroes.network.messages.GuildDonation) m;
              ServerGuild g = currentGuild(user);
              com.perblue.heroes.network.messages.GuildDonationRequestRow row = null;
              if (g != null) for (com.perblue.heroes.network.messages.GuildDonationRequestRow r : g.allDonationRequests())
                if (r.requestID == gd.requestID) { row = r; break; }
              if (g == null || row == null) {
                System.out.println("[login]     ⛔ GuildDonation : demande #" + gd.requestID + " introuvable");
              } else {
                java.util.Map<Long, Integer> byUser = g.donationsByUser.computeIfAbsent(gd.requestID, k -> new java.util.LinkedHashMap<>());
                try {
                  com.perblue.heroes.network.messages.RewardDrop given = user.donateToGuildRequest(row, byUser, gd.donation);
                  boolean fulfilled = row.remainingDonations <= 0;
                  g.updateDonationRequest(gd.requestID, fulfilled ? null : row);
                  try { store.save(user); } catch (Exception e) { System.out.println("[login]     ! save donateur: " + e); }
                  long delivered = 0;
                  if (fulfilled) {
                    // livre la récompense accumulée au DEMANDEUR (chargé du store ; hors ligne = courrier en attente).
                    ServerUser req = row.member.iD == user.userID ? user : store.loadIfExists(row.member.iD, user.shardID);
                    if (req != null) { delivered = req.deliverDonationResult(row, true);
                      try { store.save(req); } catch (Exception e) { System.out.println("[login]     ! save demandeur: " + e); } }
                  }
                  store.saveGuild(g);
                  com.perblue.heroes.network.messages.GuildDonationRequestUpdate up =
                      new com.perblue.heroes.network.messages.GuildDonationRequestUpdate();
                  up.requestID = gd.requestID; up.remainingDonations = fulfilled ? 0 : row.remainingDonations;
                  up.setAsReplyTo(m);
                  c.send(up);
                  System.out.println("[login] <== GuildDonation #" + gd.requestID + " par " + user.userID
                      + " (don " + (given == null ? "?" : given.itemType) + ") → restant " + up.remainingDonations
                      + (fulfilled ? " REMPLIE → +" + delivered + " STAMINA au demandeur (courrier)" : "") + " [persisté]");
                } catch (Throwable t) {
                  if (t instanceof com.perblue.heroes.ClientErrorCodeException)
                    System.out.println("[login]     ⛔ GuildDonation REFUSÉ (anti-triche) : " + t.getMessage());
                  else { System.out.println("[login]     ! donateToGuildRequest échec: " + t); t.printStackTrace(); }
                }
              }
            } else if (m instanceof com.perblue.heroes.network.messages.GetWarInfo) {
              // GUILD WAR (#68) — l'écran de guerre. L'état est SYMÉTRIQUE (ServerWarState) : `toWarInfo`
              // produit la vue du demandeur, avec son propre camp dans `yourGuild`.
              ServerGuild g = currentGuild(user);
              ServerWarState w = warOf(g, ((com.perblue.heroes.network.messages.GetWarInfo) m).warID);
              com.perblue.heroes.network.messages.WarInfo wi =
                  w != null ? w.toWarInfo(g.guildID) : new com.perblue.heroes.network.messages.WarInfo();
              if (w != null) {
                // Bascule de phase paresseuse : SABOTAGE → ACTIVE, exactement comme le client la ferait.
                if (ServerWarMatchmaker.advancePhase(w, com.perblue.heroes.util.TimeUtil.serverTimeNow())) {
                  try { store.saveWar(w); } catch (Exception e) { System.out.println("[login] ! save guerre: " + e); }
                  wi = w.toWarInfo(g.guildID);
                }
              }
              wi.setAsReplyTo(m);
              c.send(wi);
              System.out.println("[login] <== GetWarInfo → ==> WarInfo (guerre #" + wi.warID
                  + ", état " + wi.state + ")");

            } else if (m instanceof com.perblue.heroes.network.messages.GetWarsList) {
              // Historique des guerres de la guilde + ligue/MMR/rang courants + boîtes en attente.
              ServerGuild g = currentGuild(user);
              com.perblue.heroes.network.messages.WarsList wl =
                  new com.perblue.heroes.network.messages.WarsList();
              if (g != null) {
                ServerWar.rollOverSeason(g, ServerWar.seasonIDAt(
                    com.perblue.heroes.util.TimeUtil.serverTimeNow()), 0);
                wl.mMR = ServerWar.currentMMR(g);
                wl.league = ServerWar.effectiveLeague(wl.mMR, g.warPromotionMask);
                wl.rank = warRankOf(g);
                for (ServerWarState w : store.listWarsForGuild(user.shardID, g.guildID,
                    ServerWar.maxPreviousWars())) {
                  wl.wars.add(w.toSummary(g.guildID));
                }
                wl.unopenedBoxes.addAll(store.loadWarBoxes(user.shardID, user.userID).boxes());
                store.saveGuild(g);
              }
              wl.setAsReplyTo(m);
              c.send(wl);
              System.out.println("[login] <== GetWarsList → ==> WarsList (" + wl.wars.size()
                  + " guerres, ligue " + wl.league + " MMR " + wl.mMR + ", " + wl.unopenedBoxes.size()
                  + " boîte(s))");

            } else if (m instanceof com.perblue.heroes.network.messages.GetWarSeasonsList) {
              ServerGuild g = currentGuild(user);
              com.perblue.heroes.network.messages.WarSeasonsList sl =
                  new com.perblue.heroes.network.messages.WarSeasonsList();
              if (g != null) {
                sl.guildID = g.guildID;
                sl.currentMMR = ServerWar.currentMMR(g);
                sl.currentLeague = ServerWar.effectiveLeague(sl.currentMMR, g.warPromotionMask);
                sl.currentRank = warRankOf(g);
                sl.seasons.addAll(g.warSeasonHistory());
                sl.unopenedBoxes.addAll(store.loadWarBoxes(user.shardID, user.userID).boxes());
              }
              sl.setAsReplyTo(m);
              c.send(sl);
              System.out.println("[login] <== GetWarSeasonsList → ==> WarSeasonsList ("
                  + sl.seasons.size() + " saison(s) archivée(s), ligue " + sl.currentLeague + ")");

            } else if (m instanceof com.perblue.heroes.network.messages.GetWarRankings) {
              // Classement des guildes du shard par MMR, dans la ligue demandée.
              com.perblue.heroes.network.messages.GetWarRankings gr =
                  (com.perblue.heroes.network.messages.GetWarRankings) m;
              com.perblue.heroes.network.messages.WarRankings wr =
                  new com.perblue.heroes.network.messages.WarRankings();
              wr.league = gr.league;
              wr.seasonID = ServerWar.seasonIDAt(com.perblue.heroes.util.TimeUtil.serverTimeNow());
              ServerGuild mine = currentGuild(user);
              java.util.List<ServerGuild> all = store.listGuilds(user.shardID, null, 10_000);
              all.sort((x, y) -> Integer.compare(ServerWar.currentMMR(y), ServerWar.currentMMR(x)));
              int rank = 0;
              for (ServerGuild g : all) {
                int mmr = ServerWar.currentMMR(g);
                com.perblue.heroes.network.messages.WarLeague lg =
                    ServerWar.effectiveLeague(mmr, g.warPromotionMask);
                rank++;
                if (gr.league != null && gr.league != com.perblue.heroes.network.messages.WarLeague.UNRANKED
                    && lg != gr.league) continue;
                com.perblue.heroes.network.messages.WarRankingRow row =
                    new com.perblue.heroes.network.messages.WarRankingRow();
                row.guild = g.info.basicInfo;
                row.mmr = mmr;
                row.rank = rank;
                wr.rankingRows.add(row);
                if (mine != null && g.guildID == mine.guildID) wr.yourGuild = row;
              }
              wr.setAsReplyTo(m);
              c.send(wr);
              System.out.println("[login] <== GetWarRankings(" + gr.league + ") → ==> WarRankings ("
                  + wr.rankingRows.size() + " guilde(s))");

            } else if (m instanceof com.perblue.heroes.network.messages.RequestWarLogs) {
              ServerGuild g = currentGuild(user);
              ServerWarState w = warOf(g, ((com.perblue.heroes.network.messages.RequestWarLogs) m).warID);
              com.perblue.heroes.network.messages.WarLogs logs =
                  new com.perblue.heroes.network.messages.WarLogs();
              if (w != null) {
                logs.attacks.addAll(w.attacksBy(g.guildID));
                logs.defenses.addAll(w.attacksAgainst(g.guildID));
                logs.yourSummary = ServerWarScoring.summaryFor(w, g.guildID);
                logs.enemySummary = ServerWarScoring.summaryFor(w, w.opponentOf(g.guildID));
                com.perblue.heroes.network.messages.WarGuildInfo side = w.sideOf(g.guildID);
                if (side != null && side.members != null) {
                  for (Object o : side.members.values()) {
                    com.perblue.heroes.network.messages.WarMemberInfo mi =
                        (com.perblue.heroes.network.messages.WarMemberInfo) o;
                    com.perblue.heroes.network.messages.WarLogMember lm =
                        new com.perblue.heroes.network.messages.WarLogMember();
                    lm.info = mi.userInfo;
                    lm.sabotagesDealt = mi.sabotagesDealt;
                    lm.sparsDealt = mi.sparsDealt;
                    logs.members.add(lm);
                  }
                }
              }
              logs.setAsReplyTo(m);
              c.send(logs);
              System.out.println("[login] <== RequestWarLogs → ==> WarLogs (" + logs.attacks.size()
                  + " attaque(s), " + logs.defenses.size() + " défense(s))");

            } else if (m instanceof com.perblue.heroes.network.messages.WarAttack) {
              // Résultat d'attaque (fire-and-forget, comme CampaignAttack) : le client a joué les 3 vagues,
              // le serveur applique le verdict à l'état PARTAGÉ et rediffuse le score.
              com.perblue.heroes.network.messages.WarAttack wa =
                  (com.perblue.heroes.network.messages.WarAttack) m;
              ServerGuild g = currentGuild(user);
              ServerWarState w = warOf(g, 0);
              if (w == null) {
                System.out.println("[login]     ⛔ WarAttack : aucune guerre en cours");
              } else {
                try {
                  boolean extra = ServerWarAttack.attacksUsed(w, user) > 1;
                  ServerWarAttack.recordAttack(w, g.guildID, user.basicInfo(), wa.defendingUserID,
                      wa.battles, extra, com.perblue.heroes.util.TimeUtil.serverTimeNow());
                  store.saveWar(w);
                  // CONTEST : onWarAttack est piloté par l'écran client (WarHelper ne l'appelle pas) → hook EXPLICITE (§3)
                  // avec les lineups du wire (wa.base). Tâche WAR_ATTACK/BATTLE_* → blob per-user, persisté ci-dessous.
                  if (wa.base != null && wa.base.outcome != null) {
                    final com.perblue.heroes.network.messages.CombatOutcome wo = wa.base.outcome;
                    final java.util.Collection waAtk = wa.base.attackers != null ? wa.base.attackers : new java.util.ArrayList<>();
                    final java.util.Collection waDef = wa.base.defenders != null ? wa.base.defenders : new java.util.ArrayList<>();
                    com.perblue.heroes.game.objects.User wGameUser = user.gameUser();
                    ServerContestData.record(user, wGameUser, u ->
                        com.perblue.heroes.game.logic.ContestHelper.onWarAttack(u, wo, waAtk, waDef));
                    store.save(user);
                  }
                  com.perblue.heroes.network.messages.WarPointsUpdate up =
                      ServerWarScoring.toPointsUpdate(w, g.guildID);
                  c.send(up);
                  pushToGuild(g, user.userID, up);          // le score est celui de TOUTE la guilde
                  System.out.println("[login] <== WarAttack vs " + wa.defendingUserID + " ("
                      + (wa.battles == null ? 0 : wa.battles.size()) + " vagues) → " + up.totalPoints
                      + " points [persisté]");
                } catch (Throwable t) {
                  System.out.println("[login]     ! WarAttack échec : " + t);
                }
              }

            } else if (m instanceof com.perblue.heroes.network.messages.EditGuildWarSettings) {
              // Le chef ouvre (ou restreint) les attaques supplémentaires. Le rang vit dans le GuildInfo DU
              // JEU (`warExtraAttackRank`), que `GuildHelper.canUseExtraWarAttacks` relit.
              com.perblue.heroes.network.messages.EditGuildWarSettings es =
                  (com.perblue.heroes.network.messages.EditGuildWarSettings) m;
              ServerGuild g = currentGuild(user);
              if (g == null) {
                System.out.println("[login]     ⛔ EditGuildWarSettings : joueur sans guilde");
              } else if (user.currentGuildRole()
                  != com.perblue.heroes.network.messages.GuildRole.RULER) {
                // Aucun `GuildPermission` dédié n'existe pour ce réglage ; l'aide du jeu est explicite —
                // « The Guild LEADER may change the settings to allow any Guild members to use Extra
                // Attacks » — donc on le réserve au RULER.
                System.out.println("[login]     ⛔ EditGuildWarSettings REFUSÉ : réservé au chef (rôle "
                    + user.currentGuildRole() + ")");
              } else {
                g.setWarExtraAttackRank(es.extraAttackRank);
                store.saveGuild(g);
                com.perblue.heroes.network.messages.UserGuildUpdate up = user.buildUserGuildUpdate(
                    g, user.currentGuildRole(),
                    com.perblue.heroes.network.messages.GuildUpdateReason.DEFAULT);
                up.setAsReplyTo(m);
                c.send(up);
                System.out.println("[login] <== EditGuildWarSettings → attaques bonus à partir de "
                    + es.extraAttackRank + " [persisté]");
              }

            } else if (m instanceof Ping) {
              // Écho de latence/keepalive : le client mesure le RTT et surveille l'activité serveur.
              // Sans réponse, son chien de garde ferme la connexion (« Reconnecting… »).
              Ping in = (Ping) m;
              long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();   // heure SERVEUR (suit -Ddh.clock.offset.hours)
              Ping pong = new Ping();
              pong.timestamp = in.timestamp;     // renvoyé tel quel (le client calcule le RTT)
              pong.serverReceive = now;
              pong.serverTime = now;
              pong.serverDelay = 0;
              pong.setAsReplyTo(m);
              c.send(pong);
              System.out.println("[login] ==> Ping (echo)");
            }
            } catch (Throwable t) {
              // Trou de handler : on GARDE la connexion vivante et on journalise la pile complète (jamais masqué).
              System.out.println("[login] ✖ handler « " + name + " » a levé — connexion PRÉSERVÉE (keepalive intact) :");
              t.printStackTrace();
            }
          }
        };
        for (Class<? extends GruntMessage> c : msgClasses) {
          try { conn.setListener(c, logger); } catch (Throwable ignore) {}
        }
      }
      public void onClose(GruntConnection conn) {
        // MULTI-USER (#65) — désenregistre la connexion des deux registres (compte par socket + en ligne).
        connUsers.remove(conn);
        online.values().remove(conn);
        System.out.println("[login] onClose " + conn + " (" + online.size() + " en ligne)");
      }

      /** Charge le classement de {@code (shard, type)} ; absent → le GÉNÈRE et le persiste (idem GetArenaInfo). */
      private ServerArenaLadder loadOrCreateLadder(ServerUser u,
          com.perblue.heroes.network.messages.ArenaType at) {
        ServerArenaLadder ladder = null;
        try { ladder = store.loadArenaLadder(u.shardID, at.name()); } catch (Exception e) {}
        if (ladder == null) {
          ladder = u.arenaInfoWithLadder(at, null, oppSrc).ladder;
          try { store.saveArenaLadder(u.shardID, at.name(), ladder); } catch (Exception e) {}
        }
        return ladder;
      }

      /** Victoire = {@code CombatOutcome.WIN} dans la base d'attaque OU les stats (résultat rapporté par le client). */
      private boolean outcomeWin(com.perblue.heroes.network.messages.AttackBase base,
          com.perblue.heroes.network.messages.ArenaAttackStats stats) {
        if (base != null && base.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN) return true;
        if (stats != null && stats.outcome == com.perblue.heroes.network.messages.CombatOutcome.WIN) return true;
        return false;
      }

      /** RAPPORT DE DÉFENSE — dépose un courrier au DÉFENSEUR (uniquement si c'est un VRAI joueur, pas un bot) :
       *  qui l'a attaqué + issue de SA défense (tenue si l'attaquant a perdu, vaincue s'il a gagné). Type de courrier
       *  du jeu (FIGHT_PIT_DEFENSE / COLISEUM_DEFENSE). Informationnel (pas de récompense). Chargé+sauvé via le store. */
      private void deliverDefenseMail(long defenderID, int shard,
          com.perblue.heroes.network.messages.ArenaType at, boolean attackerWon, String attackerName) {
        if (defenderID <= 0 || defenderID >= ServerArenaLadder.BOT_ID_BASE) return;   // bot → pas de courrier
        try {
          ServerUser defender = store.loadIfExists(defenderID, shard);
          if (defender == null) return;
          boolean coli = at == com.perblue.heroes.network.messages.ArenaType.COLISEUM;
          com.perblue.heroes.network.messages.MailType type = coli
              ? com.perblue.heroes.network.messages.MailType.COLISEUM_DEFENSE
              : com.perblue.heroes.network.messages.MailType.FIGHT_PIT_DEFENSE;
          String arene = coli ? "Coliseum" : "Fight Pit";
          String subject = arene + " Defense";
          String body = attackerWon
              ? (attackerName + " attacked your " + arene + " team and won. Your defense was defeated.")
              : (attackerName + " attacked your " + arena(coli) + " team but your defense held!");
          long id = defender.deliverMail(type, attackerName, subject, body, null);
          try { store.save(defender); } catch (Exception e) {
            System.out.println("[login]     ! persistance courrier défenseur échouée: " + e); }
          System.out.println("[login]     ✉ rapport de défense → joueur " + defenderID
              + " (courrier #" + id + ", " + (attackerWon ? "défense vaincue" : "défense tenue") + ")");
        } catch (Exception e) {
          System.out.println("[login]     ! rapport de défense échoué: " + e);
        }
      }
      private String arena(boolean coli) { return coli ? "Coliseum" : "Fight Pit"; }

      /** GUILDES #7 — extrait le {@code GuildPerkType} d'une action UPGRADE/ACTIVATE (clé d'extra tolérante :
       *  TYPE/INDEX/ID selon l'encodage client — on essaie chaque valeur comme nom d'enum). */
      private com.perblue.heroes.network.messages.GuildPerkType parseGuildPerkType(
          com.perblue.heroes.network.messages.Action act) {
        if (act.extra == null) return null;
        com.perblue.heroes.network.messages.ActionExtraType[] keys = {
            com.perblue.heroes.network.messages.ActionExtraType.TYPE,
            com.perblue.heroes.network.messages.ActionExtraType.INDEX,
            com.perblue.heroes.network.messages.ActionExtraType.ID };
        for (com.perblue.heroes.network.messages.ActionExtraType k : keys) {
          Object v = act.extra.get(k);
          if (v == null) continue;
          try { return com.perblue.heroes.network.messages.GuildPerkType.valueOf(v.toString()); }
          catch (Throwable ignore) {}
        }
        return null;
      }

      /** DONS #55b — balaie les demandes EXPIRÉES d'une guilde : livre le partiel accumulé au demandeur par
       *  courrier (GUILD_DONATION_EXPIRED) puis retire la demande. Le guilde n'est PAS persisté ici (l'appelant le fait). */
      private void sweepExpiredDonations(ServerGuild g) {
        long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
        for (com.perblue.heroes.network.messages.GuildDonationRequestRow r :
             new java.util.ArrayList<>(g.allDonationRequests())) {
          if (r.expiration > now && r.remainingDonations > 0) continue;   // encore active
          try {
            if (r.remainingDonations < r.totalRequestedDonations) {   // au moins un don reçu → livrer le partiel
              ServerUser req = store.loadIfExists(r.member.iD, g.shardID);
              if (req != null) { req.deliverDonationResult(r, false); try { store.save(req); } catch (Exception e) {} }
            }
          } catch (Exception e) { System.out.println("[login]     ! livraison don expiré: " + e); }
          g.updateDonationRequest(r.requestID, null);
          System.out.println("[login]     ~ demande d'aide #" + r.requestID + " expirée → retirée"
              + (r.remainingDonations < r.totalRequestedDonations ? " (+partiel au demandeur)" : ""));
        }
      }

      /** Lit une valeur numérique de l'extra d'une Action (les long/int passent par extra ID/COUNT/TIME, pas par act.iD). */
      private long extraLong(com.perblue.heroes.network.messages.Action act,
          com.perblue.heroes.network.messages.ActionExtraType key, long dflt) {
        if (act.extra == null) return dflt;
        Object v = act.extra.get(key);
        if (v == null) return dflt;
        try { return Long.parseLong(v.toString().trim()); } catch (Throwable t) { return dflt; }
      }

      /** DONS SKILL #63 — le slot de compétence ciblé, depuis {@code extra[SKILL]} (SkillSlot ou son nom).
       *  Défaut {@code WHITE} (1ᵉ slot réel ; DEFAULT/RED sont exclus des demandes d'aide côté jeu). */
      private com.perblue.heroes.network.messages.SkillSlot parseSkillSlot(com.perblue.heroes.network.messages.Action act) {
        Object v = act.extra == null ? null : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.SKILL);
        if (v instanceof com.perblue.heroes.network.messages.SkillSlot) return (com.perblue.heroes.network.messages.SkillSlot) v;
        if (v != null) {
          try { return com.perblue.heroes.network.messages.SkillSlot.valueOf(v.toString().trim()); }
          catch (Throwable t) { /* repli ci-dessous */ }
        }
        return com.perblue.heroes.network.messages.SkillSlot.WHITE;
      }

      /** MERCENAIRES #57 — pool de la guilde : héros postés par CE joueur + tous les autres membres. */
      private com.perblue.heroes.network.messages.HeroesForHire buildHeroesForHire(ServerUser u, boolean forJobBoard) {
        com.perblue.heroes.network.messages.HeroesForHire hf =
            new com.perblue.heroes.network.messages.HeroesForHire();
        hf.guildID = u.currentGuildID();
        hf.forJobBoard = forJobBoard;
        hf.mercenaries = new java.util.ArrayList<>();
        ServerGuild g = currentGuild(u);
        if (g != null) {
          for (Long mid : g.memberIDs) {
            try {
              ServerUser mu = (mid == u.userID) ? u : store.loadIfExists(mid, u.shardID);
              if (mu != null) hf.mercenaries.addAll(mu.postedMercenaries());
            } catch (Exception e) { System.out.println("[login]     ! merc membre " + mid + ": " + e); }
          }
        }
        return hf;
      }

      /** CLASSEMENT #60 — valeur d'une guilde pour une métrique de classement (champ de {@code GuildInfo}). */
      private long rankValue(com.perblue.heroes.network.messages.GuildInfo gi,
          com.perblue.heroes.network.messages.RankType rt) {
        if (gi == null || rt == null) return 0L;
        switch (rt) {
          case TOTAL_POWER: case LEGACY_COLISEUM: return gi.totalPower;   // puissance totale (défaut)
          case TEAM_POWER: return gi.teamPower;
          case TOTAL_STARS: return gi.totalStars;
          case FIGHT_PIT: case LEGACY_FIGHT_PIT: return gi.fightPitWins;
          case COLISEUM: return gi.bestSurgeScore;                        // pas de champ colisée dédié → meilleur proxy
          case CONTEST_RANK: return gi.contestPoints;
          default: return gi.totalPower;
        }
      }

      /** CONTEST DES GUILDES (#67) — classement RÉEL des guildes du shard par {@code GuildInfo.contestPoints}. */
      private com.perblue.heroes.network.messages.GuildContestRankings buildGuildContestRankings(ServerUser u) {
        com.perblue.heroes.network.messages.GuildContestRankings gc =
            new com.perblue.heroes.network.messages.GuildContestRankings();
        gc.topGuilds = new java.util.ArrayList<>();
        java.util.List<ServerGuild> all;
        try { all = store.listGuilds(u.shardID, null, 200); }
        catch (Exception e) { System.out.println("[login]     ! listGuilds contest: " + e); return gc; }
        all.sort((x, y) -> Long.compare(y.info.contestPoints, x.info.contestPoints));   // décroissant
        int idx = 0;
        for (ServerGuild sg : all) {
          idx++;
          if (gc.topGuilds.size() >= 100) break;
          com.perblue.heroes.network.messages.GuildContestRankingRow row =
              new com.perblue.heroes.network.messages.GuildContestRankingRow();
          row.guildInfo = sg.info.basicInfo;         // BasicGuildInfo
          row.points = sg.info.contestPoints;
          row.rank = idx; row.contestRankIndex = idx - 1;
          gc.topGuilds.add(row);
        }
        return gc;
      }

      /** L'{@code SpecialEventInfo} du contest SOLO (composant {@code Contest}, {@code !isGuildContest()}) d'ID {@code contestID}
       *  ACTIF pour {@code u}, ou {@code null} si aucun (⇒ fallback contest de guilde #67). {@code u} doit être bindé. */
      @SuppressWarnings("unchecked")
      private com.perblue.common.specialevent.SpecialEventInfo activeSoloContest(ServerUser u, long contestID) {
        if (contestID <= 0) return null;
        try {
          com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
          for (Object o : snap.getActiveEvents()) {
            com.perblue.common.specialevent.SpecialEventInfo e = (com.perblue.common.specialevent.SpecialEventInfo) o;
            if (e.getID() != contestID) continue;
            com.perblue.common.specialevent.components.Contest ct =
                (com.perblue.common.specialevent.components.Contest) e.getComponent(com.perblue.common.specialevent.components.Contest.class);
            if (ct != null && !ct.isGuildContest()) return e;
          }
        } catch (Throwable t) { System.out.println("[login]     ! activeSoloContest: " + t); }
        return null;
      }

      /** L'{@code SpecialEventInfo} du contest de GUILDE (composant {@code Contest}, {@code isGuildContest()}) d'ID
       *  {@code contestID} ACTIF pour {@code u}, ou {@code null} (⇒ fallback contest de guilde #67). {@code u} doit être bindé. */
      @SuppressWarnings("unchecked")
      private com.perblue.common.specialevent.SpecialEventInfo activeGuildContest(ServerUser u, long contestID) {
        if (contestID <= 0) return null;
        try {
          com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = ServerEvents.snapshot();
          for (Object o : snap.getActiveEvents()) {
            com.perblue.common.specialevent.SpecialEventInfo e = (com.perblue.common.specialevent.SpecialEventInfo) o;
            if (e.getID() != contestID) continue;
            com.perblue.common.specialevent.components.Contest ct =
                (com.perblue.common.specialevent.components.Contest) e.getComponent(com.perblue.common.specialevent.components.Contest.class);
            if (ct != null && ct.isGuildContest()) return e;
          }
        } catch (Throwable t) { System.out.println("[login]     ! activeGuildContest: " + t); }
        return null;
      }

      /**
       * CONTEST DES JOUEURS — {@code yourInfo} = la ligne du joueur, {@code topPlayers} = le classement.
       * <p>Si {@code contestID} désigne un contest SOLO actif (composant SPECIAL_EVENTS {@code Contest}), le classement est
       * SERVEUR-AUTORITATIF, construit depuis le ladder per-shard de {@link ServerContestData} (points = {@code rankPoints}
       * du blob per-user, rang départagé par horodatage). Sinon, fallback contest de GUILDE (#67 : membres de la guilde du
       * joueur classés par leurs points de contest de guilde).
       */
      private com.perblue.heroes.network.messages.ContestRankings buildContestRankings(ServerUser u, long contestID) {
        com.perblue.common.specialevent.SpecialEventInfo solo = activeSoloContest(u, contestID);
        if (solo != null) return ServerContestData.soloRankings(store, u, contestID);   // leaderboard serveur-autoritatif
        return buildGuildMemberContestRankings(u);
      }

      /** CONTEST DE GUILDE (#67) — membres de la guilde du joueur classés par leurs points de contest de guilde
       *  (ventilation SERVEUR par membre) ; {@code yourInfo} = la ligne du joueur. */
      private com.perblue.heroes.network.messages.ContestRankings buildGuildMemberContestRankings(ServerUser u) {
        com.perblue.heroes.network.messages.ContestRankings cr =
            new com.perblue.heroes.network.messages.ContestRankings();
        cr.guildMembers = new java.util.ArrayList<>();
        cr.topPlayers = new java.util.ArrayList<>();
        ServerGuild g = currentGuild(u);
        if (g == null) return cr;
        java.util.List<com.perblue.heroes.network.messages.ContestRankingRow> rows = new java.util.ArrayList<>();
        for (Long mid : g.memberIDs) {
          try {
            ServerUser mu = (mid == u.userID) ? u : store.loadIfExists(mid, u.shardID);
            if (mu == null) continue;
            com.perblue.heroes.network.messages.ContestRankingRow row =
                new com.perblue.heroes.network.messages.ContestRankingRow();
            com.perblue.heroes.network.messages.PlayerRow pr = new com.perblue.heroes.network.messages.PlayerRow();
            pr.info = mu.basicInfo();
            row.playerRow = pr;
            // Points = ventilation SERVEUR par membre (#67). La ressource GUILD_CONTEST_POINTS n'est PAS un
            // stock (getGuildContestPoints() lit GuildInfo.contestPoints) → la lire donnerait toujours 0.
            row.points = mu.contestPointsIn(g);
            rows.add(row);
          } catch (Exception e) { System.out.println("[login]     ! contest membre " + mid + ": " + e); }
        }
        rows.sort((x, y) -> Long.compare(y.points, x.points));   // décroissant
        int idx = 0;
        for (com.perblue.heroes.network.messages.ContestRankingRow row : rows) {
          idx++; row.rank = idx; row.contestRankIndex = idx - 1;
          if (row.playerRow != null && row.playerRow.info != null && row.playerRow.info.iD == u.userID) cr.yourInfo = row;
        }
        cr.guildMembers = rows;
        cr.topPlayers = new java.util.ArrayList<>(rows);   // top = mêmes membres (contest de guilde)
        return cr;
      }

      /** GUILDES #7 — la guilde courante du joueur (ou {@code null} s'il n'en a pas / introuvable). */
      /** Lit un enum dans les extras d'une {@code Action} (le client les envoie en texte). */
      private <E extends Enum<E>> E enumExtra(com.perblue.heroes.network.messages.Action a,
          com.perblue.heroes.network.messages.ActionExtraType key, Class<E> type, E dflt) {
        if (a.extra == null) return dflt;
        Object v = a.extra.get(key);
        if (v == null) return dflt;
        if (type.isInstance(v)) return type.cast(v);
        try { return Enum.valueOf(type, String.valueOf(v)); } catch (Exception e) { return dflt; }
      }

      /** Lit une liste de héros depuis un extra ({@code WAR_EDIT_BAN_PROTECT} l'envoie en texte). */
      private java.util.List<com.perblue.heroes.network.messages.UnitType> parseUnitList(Object raw) {
        java.util.List<com.perblue.heroes.network.messages.UnitType> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof java.util.Collection) {
          for (Object o : (java.util.Collection<?>) raw) {
            try {
              out.add(o instanceof com.perblue.heroes.network.messages.UnitType
                  ? (com.perblue.heroes.network.messages.UnitType) o
                  : com.perblue.heroes.network.messages.UnitType.valueOf(String.valueOf(o).trim()));
            } catch (Exception ignore) { }
          }
          return out;
        }
        for (String part : String.valueOf(raw).split("[,;\\s]+")) {
          if (part.isEmpty()) continue;
          try { out.add(com.perblue.heroes.network.messages.UnitType.valueOf(part.trim())); }
          catch (Exception ignore) { }
        }
        return out;
      }

      /** GUILD WAR (#68) — la guerre demandée, ou celle EN COURS de la guilde ({@code warID <= 0}). */
      private ServerWarState warOf(ServerGuild g, long warID) {
        if (g == null) return null;
        long id = warID > 0 ? warID : g.currentWarID;
        if (id <= 0) return null;
        try { return store.loadWar(g.shardID, id); }
        catch (Exception e) { System.out.println("[login]     ! chargement guerre échoué: " + e); return null; }
      }

      /** GUILD WAR (#68) — rang de la guilde sur le shard, par MMR décroissant (1 = première). */
      private int warRankOf(ServerGuild g) {
        if (g == null) return 0;
        try {
          int mine = ServerWar.currentMMR(g), rank = 1;
          for (ServerGuild other : store.listGuilds(g.shardID, null, 10_000)) {
            if (other.guildID != g.guildID && ServerWar.currentMMR(other) > mine) rank++;
          }
          return rank;
        } catch (Exception e) { return 0; }
      }

      private ServerGuild currentGuild(ServerUser u) {
        if (!u.inGuild()) return null;
        try { return store.loadGuild(u.shardID, u.currentGuildID()); }
        catch (Exception e) { System.out.println("[login]     ! chargement guilde échoué: " + e); return null; }
      }

      /** GUILDES #7 — enveloppe une liste de {@link ServerGuild} en {@code List<GuildRow>} (élément de liste attendu
       *  par ListRecGuildsResponse et SearchGuildsResponse). */
      private java.util.List<com.perblue.heroes.network.messages.GuildRow> guildRows(
          java.util.List<ServerGuild> guilds) {
        java.util.List<com.perblue.heroes.network.messages.GuildRow> out = new java.util.ArrayList<>();
        for (ServerGuild g : guilds) {
          com.perblue.heroes.network.messages.GuildRow row = new com.perblue.heroes.network.messages.GuildRow();
          row.guildInfo = g.info;
          out.add(row);
        }
        return out;
      }
    };

    GruntServerFactory.startNioTcp(port, MessageFactory.getInstance(), exec, listener,
        DHXORConnectionWrapper.class, 5000, true, true, false, 65536);
    System.out.println("[login] écoute sur " + port + " (protocole de jeu, codec DHXOR)");
  }

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
    // Étapes 3-5 : compte autoritaire → tutoriel d'intro, progression du tuto appliquée ET PERSISTÉE
    // (SQLite, octets wire des objets du jeu). Structure/valeurs 100% classes du jeu (PRINCIPLES §4/§6).
    // Un seul compte pour l'instant (id=1) ; DB régénérable (recréée si absente).
    ServerContext.init();                 // charge les données du jeu + shim DH.app (logique headless)
    String dbPath = System.getProperty("dh.db", "server/data/dh-server.db");
    new java.io.File(dbPath).getAbsoluteFile().getParentFile().mkdirs();
    UserStore store = new UserStore(dbPath);
    // ANCRE D'HORLOGE PERSISTÉE (ère de contenu R1…R102 + tous les décomptes). La DB fait AUTORITÉ pour survivre
    // aux redémarrages sans dérive : à chaque boot on ré-applique l'offset persisté (l'offset est constant → l'heure
    // de jeu s'écoule au rythme réel depuis l'ancre). Un -Ddh.clock.offset.hours n'est qu'un bootstrap : s'il est
    // fourni sans ancre en DB, on le PERSISTE. Réglé par l'opérateur via AdminClock. Cf. ServerContext.setClockOffsetMillis.
    try {
      Long persisted = store.getMetaLong("clock_offset_ms");
      if (persisted != null) {
        ServerContext.setClockOffsetMillis(persisted);
        System.out.println("[login] ⏱ ancre d'horloge PERSISTÉE appliquée (offset " + persisted + " ms) — heure de jeu "
            + new java.util.Date(com.perblue.heroes.util.TimeUtil.serverTimeNow()));
      } else if (ServerContext.clockOffsetMillis() != 0L) {
        store.setMetaLong("clock_offset_ms", ServerContext.clockOffsetMillis());
        System.out.println("[login] ⏱ bootstrap -Ddh.clock.offset.hours PERSISTÉ (offset "
            + ServerContext.clockOffsetMillis() + " ms)");
      }
    } catch (Exception e) { System.out.println("[login] ! ancre d'horloge : " + e); }
    // ANCRE DE SAISON PERSISTÉE (config ADMIN) — DÉCOUPLÉE de l'horloge : n'affecte QUE la sélection de saison des
    // FRANCHISE_TRIALS (ServerEvents.seasonTrialConfigs → ServerContext.seasonTimeNow), PAS les timers joueur. Défaut
    // (absente/0) → la saison suit la date réelle. Réglée par l'opérateur via AdminSeason. Cf. ServerContext.setSeasonAnchorOffsetMillis.
    try {
      Long seasonAnchor = store.getMetaLong("season_anchor_offset_ms");
      if (seasonAnchor != null && seasonAnchor != 0L) {
        ServerContext.setSeasonAnchorOffsetMillis(seasonAnchor);
        System.out.println("[login] ⏱ ancre de saison PERSISTÉE appliquée (offset " + seasonAnchor + " ms)");
      }
    } catch (Exception e) { System.out.println("[login] ! ancre de saison : " + e); }
    // ÉVÉNEMENTS OPÉRATEUR (live-ops) — chargés depuis shard_state (clé "operator_events") dans le holder statique de
    // ServerEvents. Défaut = VIDE → aucune ouverture forcée → le jeu applique sa ROTATION par défaut (getOpenDays). Un
    // opérateur AJOUTE des overrides (MODES_OPEN/DropBonus) via l'outil AdminEvents ; ils survivent aux redémarrages.
    try {
      byte[] evBlob = store.loadShardState(/*shardID*/ 1, "operator_events");
      java.util.List<com.perblue.common.specialevent.SpecialEventInfo> ops = ServerEvents.eventsFromConfig(evBlob);
      ServerEvents.setOperatorEvents(ops);
      System.out.println("[login] événements opérateur chargés : " + ops.size()
          + (ops.isEmpty() ? " (rotation par défaut du jeu)" : " override(s) live-ops"));
    } catch (Exception e) { System.out.println("[login] ! événements opérateur : " + e); }
    ServerUser user = store.loadOrCreate(/*userID*/ 1L, /*shardID*/ 1);
    System.out.println("[login] compte id=1 chargé/créé (" + user.tutorialActCount()
        + " actes de tuto) — DB " + dbPath);
    new LoginServer(port, user, store).start();
    // GUILD WAR #68 — l'ordonnanceur : appariement à l'heure, avance des phases, clôture des guerres
    // échues, bascule de saison et distribution des boîtes. C'est ce que le backend faisait tourner tout
    // seul ; sans lui, aucune guerre ne démarre ni ne se termine jamais.
    ServerWarScheduler.startBackgroundLoop(store);
    Thread.currentThread().join();
  }
}
