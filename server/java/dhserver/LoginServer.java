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
  /** État serveur autoritaire (un seul compte pour l'instant). */
  private final ServerUser user;
  /** Persistance SQLite (octets wire des objets du jeu). */
  private final UserStore store;
  /** ARÈNE (vrai PvP) — source d'adversaires RÉELS (autres comptes du shard) adossée à la base. */
  private final ServerArena.OpponentSource oppSrc;

  public LoginServer(int port, ServerUser user, UserStore store) {
    this.port = port; this.user = user; this.store = store;
    this.oppSrc = new StoreOpponentSource(store);
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
              if (user.inGuild()) {
                try {
                  ServerGuild g = store.loadGuild(user.shardID, user.currentGuildID());
                  if (g != null) bd.guildInfo = g.info;
                } catch (Exception e) { System.out.println("[login]     ! chargement guilde (boot) échoué: " + e); }
              }
              bd.setAsReplyTo(m);
              c.send(bd);
              System.out.println("[login] ==> BootData (reply) : "
                  + bd.individualUserExtra.tutorialActs.size() + " actes de tuto");
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
                System.out.println("[login] <== CampaignAttack : " + ca.campaignType + " " + ca.chapter
                    + "-" + ca.level + " outcome=" + (ca.base == null ? "?" : ca.base.outcome)
                    + " → recordOutcome appliqué [persisté]");
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
                // MERCENAIRES — héros à louer postés dans la guilde (écran MERCENARIES attend HeroesForHire).
                ServerGuild g = currentGuild(user);
                com.perblue.heroes.network.messages.HeroesForHire hf =
                    new com.perblue.heroes.network.messages.HeroesForHire();
                hf.guildID = user.currentGuildID();
                hf.mercenaries = new java.util.ArrayList<>();
                Object ep = act.extra == null ? null
                    : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.ENTRY_POINT);
                hf.forJobBoard = ep != null && Boolean.parseBoolean(ep.toString());
                hf.setAsReplyTo(m);
                c.send(hf);
                System.out.println("[login] <== GET_HEROES_FOR_HIRE → ==> HeroesForHire (0 merc)");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_GUILD_RANKINGS) {
                // CLASSEMENT DES GUILDES — bouton graphique de l'écran de guilde. Une seule guilde sur le shard →
                // rang 1. topGuilds vide (le client affiche ta guilde via yourGuildRank/Value).
                com.perblue.heroes.network.messages.GuildRankings gr =
                    new com.perblue.heroes.network.messages.GuildRankings();
                Object rt = act.extra == null ? null
                    : act.extra.get(com.perblue.heroes.network.messages.ActionExtraType.TYPE);
                try { gr.rankType = rt == null ? com.perblue.heroes.network.messages.RankType.TOTAL_POWER
                    : com.perblue.heroes.network.messages.RankType.valueOf(rt.toString()); }
                catch (Throwable t) { gr.rankType = com.perblue.heroes.network.messages.RankType.TOTAL_POWER; }
                gr.topGuilds = new java.util.ArrayList<>();
                gr.yourGuildRank = user.inGuild() ? 1 : 0;
                gr.yourGuildValue = 0L;
                gr.setAsReplyTo(m);
                c.send(gr);
                System.out.println("[login] <== GET_GUILD_RANKINGS(" + gr.rankType + ") → ==> GuildRankings (rang "
                    + gr.yourGuildRank + ")");
              } else if (act.command == com.perblue.heroes.network.messages.CommandType.GET_CONTEST_RANKINGS
                  || act.command == com.perblue.heroes.network.messages.CommandType.GET_GUILD_CONTEST_RANKINGS) {
                // CONTESTS de guilde — aucun contest hébergé (cf. évènements) → classement vide (l'écran rend « pas de contest »).
                com.perblue.heroes.network.messages.GuildContestRankings gc =
                    new com.perblue.heroes.network.messages.GuildContestRankings();
                gc.topGuilds = new java.util.ArrayList<>();
                gc.setAsReplyTo(m);
                c.send(gc);
                System.out.println("[login] <== " + act.command + " → ==> GuildContestRankings (vide)");
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
                System.out.println("[login] <== RequestExtendedGuildInfo #" + req.guildID
                    + " → ==> ExtendedGuildInfo (" + egi.members.size() + " membre(s))");
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
              } else if (g.info.newMemberPolicy != com.perblue.heroes.network.messages.GuildNewMemberPolicy.OPEN) {
                System.out.println("[login]     ⛔ JoinGuild REFUSÉ : guilde #" + jg.guildID
                    + " non ouverte (" + g.info.newMemberPolicy + ") — candidature requise (à venir)");
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
              // GUILD AID — liste des demandes de don en attente (écran GUILD AID). Aucune demande ouverte → liste
              // vide = réponse autoritative (l'écran rend « aucune demande » au lieu de « LOADING… »).
              com.perblue.heroes.network.messages.GuildDonationRequests resp =
                  new com.perblue.heroes.network.messages.GuildDonationRequests();
              resp.guildID = user.currentGuildID();
              resp.requests = new java.util.ArrayList<>();
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== GetGuildDonationRequests → ==> GuildDonationRequests (0 demande)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetGuildGiftRewards) {
              // GUILD CRATE / cadeaux de guilde — récompenses de cadeau en attente. Aucune → vide.
              com.perblue.heroes.network.messages.GetGuildGiftRewards req =
                  (com.perblue.heroes.network.messages.GetGuildGiftRewards) m;
              com.perblue.heroes.network.messages.GuildGiftRewards resp =
                  new com.perblue.heroes.network.messages.GuildGiftRewards();
              resp.eventID = req.eventID;
              resp.gifters = new java.util.ArrayList<>();
              resp.rewards = new java.util.ArrayList<>();
              resp.lastGiftTime = 0L;
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== GetGuildGiftRewards(" + req.eventID + ") → ==> GuildGiftRewards (0)");
            } else if (m instanceof com.perblue.heroes.network.messages.GetUnlockedGuildAvatars) {
              // Avatars/emblèmes de guilde débloqués — liste (vide = aucun débloqué).
              com.perblue.heroes.network.messages.UnlockedGuildAvatars resp =
                  new com.perblue.heroes.network.messages.UnlockedGuildAvatars();
              resp.avatars = new java.util.ArrayList<>();
              resp.setAsReplyTo(m);
              c.send(resp);
              System.out.println("[login] <== GetUnlockedGuildAvatars → ==> UnlockedGuildAvatars (0)");
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
      public void onClose(GruntConnection conn) { System.out.println("[login] onClose " + conn); }

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
