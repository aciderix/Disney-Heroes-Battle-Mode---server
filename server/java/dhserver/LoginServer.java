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

  public LoginServer(int port, ServerUser user, UserStore store) {
    this.port = port; this.user = user; this.store = store;
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
            if (m instanceof ClientInfo) {
              BootData bd = user.bootData();
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
                com.perblue.grunt.translate.GruntMessage resp = user.startArenaAttack(at, defID, ladder);
                resp.setAsReplyTo(m);
                c.send(resp);
                System.out.println("[login] <== START " + at + " attaque défenseur=" + defID
                    + " → ==> Start*AttackResponse (héros du défenseur envoyés)");
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
              ServerUser.ArenaResult ar = user.arenaInfoWithLadder(at, loaded);
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
              if (m instanceof com.perblue.heroes.network.messages.ColiseumAttack) {
                com.perblue.heroes.network.messages.ColiseumAttack ca =
                    (com.perblue.heroes.network.messages.ColiseumAttack) m;
                at = com.perblue.heroes.network.messages.ArenaType.COLISEUM;
                defID = ca.defendingUserID; win = outcomeWin(ca.base, ca.stats);
              } else {
                com.perblue.heroes.network.messages.ArenaAttack aa =
                    (com.perblue.heroes.network.messages.ArenaAttack) m;
                at = com.perblue.heroes.network.messages.ArenaType.FIGHT_PIT;
                defID = aa.defendingUserID; win = outcomeWin(aa.base, aa.stats);
              }
              ServerArenaLadder ladder = loadOrCreateLadder(user, at);
              com.perblue.heroes.network.messages.ArenaUpdate up = user.resolveArenaAttack(defID, win, at, ladder);
              try { store.saveArenaLadder(user.shardID, at.name(), ladder); } catch (Exception e) {
                System.out.println("[login]     ! persistance ladder échouée: " + e); }
              try { store.save(user); } catch (Exception e) {
                System.out.println("[login]     ! persistance joueur échouée: " + e); }
              up.setAsReplyTo(m);
              c.send(up);
              System.out.println("[login] <== " + (m instanceof com.perblue.heroes.network.messages.ColiseumAttack
                  ? "ColiseumAttack" : "ArenaAttack") + " défenseur=" + defID + " win=" + win
                  + " → ==> ArenaUpdate (rank=" + up.yourLeague.yourRank + ") [persisté]");
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
          ladder = u.arenaInfoWithLadder(at, null).ladder;
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
