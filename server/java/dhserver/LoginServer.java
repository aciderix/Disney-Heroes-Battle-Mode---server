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

  public LoginServer(int port, ServerUser user, UserStore store) {
    this.port = port; this.user = user; this.store = store;
    this.oppSrc = new StoreOpponentSource(store);
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
                raw.changed = false;
                raw.events = new java.util.ArrayList<>();
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
                System.out.println("[login]     ==> SpecialEventsRaw (reply, 0 évènement, "
                    + nDays + " jours de sign-in)");
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
                // CONTEST DES GUILDES (#67) — leaderboard RÉEL : guildes du shard triées par GuildInfo.contestPoints.
                com.perblue.heroes.network.messages.GuildContestRankings gc = buildGuildContestRankings(user);
                gc.setAsReplyTo(m);
                c.send(gc);
                System.out.println("[login] <== GET_GUILD_CONTEST_RANKINGS → ==> GuildContestRankings ("
                    + gc.topGuilds.size() + " guilde(s))");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_CONTEST_RANKINGS) {
                // CONTEST DES JOUEURS (#67) — membres de la guilde triés par leurs points de contest
                // (ressource GUILD_CONTEST_POINTS) + ta ligne.
                com.perblue.heroes.network.messages.ContestRankings cr = buildContestRankings(user);
                cr.setAsReplyTo(m);
                c.send(cr);
                System.out.println("[login] <== GET_CONTEST_RANKINGS → ==> ContestRankings ("
                    + cr.guildMembers.size() + " membre(s), ton rang " + (cr.yourInfo == null ? 0 : cr.yourInfo.rank) + ")");
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
            } else if (m instanceof com.perblue.heroes.network.messages.InvasionBreakerAttackStart) {
              // INVASION #69 — le client OUVRE un combat de breaker : le serveur lui renvoie la COMPOSITION
              // adverse, tirée de la table de drop DU JEU (invasion_breaker_fight_comp.tab) DANS LE CONTEXTE
              // DU JOUEUR (sans quoi la table retombe sur des mobs génériques). Graine dérivée de
              // (invasion, room, joueur) → composition STABLE tant que la salle et l'invasion ne changent pas.
              com.perblue.heroes.network.messages.InvasionBreakerAttackStart bs =
                  (com.perblue.heroes.network.messages.InvasionBreakerAttackStart) m;
              long bnow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              ServerInvasionObject sinv = ServerInvasionObject.at(bnow);
              long seed = sinv.getID() * 1_000_003L + bs.room * 31L + user.userID;
              java.util.List<?> comp = ServerInvasion.rollBreakerComposition(user, bs.room, sinv, seed);
              com.perblue.heroes.network.messages.BreakerUserFightData bd =
                  new com.perblue.heroes.network.messages.BreakerUserFightData();
              bd.index = bs.room;
              bd.breakerDefenders = new java.util.ArrayList<>();
              bd.wardLineups = new java.util.ArrayList<>();
              bd.setAsReplyTo(m);
              c.send(bd);
              System.out.println("[login] <== InvasionBreakerAttackStart room=" + bs.room + " ward=" + bs.ward
                  + " → ==> BreakerUserFightData (composition " + comp.size() + " unité(s) tirée des données)");
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
              // INVASION (#69) — calendrier + identité de l'invasion courante, CALCULÉS depuis les données du jeu
              // (invasion_constants : START/END jour+heure, INVASION_BASE_DATE/ROTATION ; UnitStats.getTeam pour
              // les héros de l'équipe vedette). Le client enveloppe ça dans son ClientInvasion.
              long inow = com.perblue.heroes.util.TimeUtil.serverTimeNow();
              com.perblue.heroes.network.messages.InvasionInfo ii = ServerInvasion.buildInfo(inow);
              // État JOUEUR : relu depuis la base, REMIS À ZÉRO si la rotation a changé (comme
              // InvasionHelper.resetUserInvasion), puis re-persisté. L'énergie d'invasion elle-même est une
              // ressource du jeu (INVASION_STAMINA, régén gérée par la mécanique existante).
              if (ii.currentInvasion != null && ii.currentInvasion.invasion != null) {
                try {
                  long invID = ii.currentInvasion.invasion.invasionID;
                  byte[] prev = store.loadUserInvasion(user.shardID, user.userID);
                  com.perblue.heroes.network.messages.UserInvasionData ud =
                      ServerInvasion.loadOrResetUserData(prev, user.userID, user.currentGuildID(), invID);
                  ii.currentInvasion.yourData = ud;
                  store.saveUserInvasion(user.shardID, user.userID, ServerInvasion.userDataToBytes(ud));
                } catch (Exception e) { System.out.println("[login]     ! état invasion joueur : " + e); }
              }
              ii.setAsReplyTo(m);
              c.send(ii);
              System.out.println("[login] <== GetInvasionInfo → ==> InvasionInfo : "
                  + ServerInvasion.describe(com.perblue.heroes.util.TimeUtil.serverTimeNow()));
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
            } else if (m instanceof Ping) {
              // Écho de latence/keepalive : le client mesure le RTT et surveille l'activité serveur.
              // Sans réponse, son chien de garde ferme la connexion (« Reconnecting… »).
              Ping in = (Ping) m;
              long now = System.currentTimeMillis();
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

      /** CONTEST DES JOUEURS (#67) — membres de la guilde du joueur classés par leurs points de contest
       *  (ressource {@code GUILD_CONTEST_POINTS}) ; {@code yourInfo} = la ligne du joueur. */
      private com.perblue.heroes.network.messages.ContestRankings buildContestRankings(ServerUser u) {
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
    ServerUser user = store.loadOrCreate(/*userID*/ 1L, /*shardID*/ 1);
    System.out.println("[login] compte id=1 chargé/créé (" + user.tutorialActCount()
        + " actes de tuto) — DB " + dbPath);
    new LoginServer(port, user, store).start();
    Thread.currentThread().join();
  }
}
