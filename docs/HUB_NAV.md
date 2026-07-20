# HUB_NAV — navigation du hub, flèche de tuto & badges "actions dispo" (recon 2026-07-17)

Recon de l'écran d'accueil (hub) et de ses systèmes de navigation/attention, pour guider l'implémentation
des écrans post-tuto (serveur) et l'autonomie du pilote DEV. **Tout est du code du jeu** (relevé au bytecode),
zéro invention. Voir [`PROTOCOL.md`](PROTOCOL.md), [`SERVER_PLAN.md`](SERVER_PLAN.md).

## 1. Le hub = `MainScreen` (métaphore ville)

`com.perblue.heroes.ui.mainscreen.MainScreen` (+ `MainScreenDisplay`/`MainScreenHelper`/`MainScreenHitArea`).
C'est une **ville** rendue en **g2d** (pas scene2d) : les **bâtiments SONT les boutons** (on tape un bâtiment
pour entrer dans un mode). Capture de référence : `desktop-port/build/hub_now.png` (thème hivernal ; menu ☰
en haut-droite avec "!" rouge, flèche d'objectif jaune, dialogue de tuto « badge »).

## 2. Navigation — tout converge vers `UINavHelper`

- **Catalogue** = enum **`UINavHelper.Destination`** (~57) : liste canonique de TOUT écran navigable
  (`CAMPAIGN, HERO_MANAGEMENT, CHESTS, ITEMS, QUESTS, MISSIONS, GUILDS, WAR, RANKINGS, COLISEUM, INVASION,
  SURGE, EXPEDITION, HEIST, FIGHT_PIT, TEAM_TRIALS, ENCHANTING, ALCHEMY, COLLECTIONS, MERCHANT, BLACK_MARKET,
  MEGA_MART, CHAT, MAILBOX, VIP_BENEFITS, PURCHASING, PROMOS, DAILY_DEAL, PORT, HOME, …`).
- **Routeur central** = `UINavHelper.navigateTo(Destination, reason, params…)` → `navigateInner(...)` = un
  gros switch qui **instancie l'écran concret** et le pousse. Mapping relevé (extrait) :

  | Destination | Écran |
  |---|---|
  | CAMPAIGN | `campaign.CampaignScreen` / `CampaignPreviewScreen` |
  | HERO_MANAGEMENT | `herolist.HeroListScreen` |
  | CHESTS | `screens.ChestsScreen` |
  | ITEMS | `screens.ItemsScreen` |
  | QUESTS / MISSIONS | `screens.QuestsScreen` / `friendship.MissionsMainScreen` |
  | GUILDS | `screens.GuildScreen` |
  | RANKINGS | `screens.RankingScreen` / `ArenaLeagueScreen` |
  | INVASION / SURGE / EXPEDITION | `invasion.InvasionScreen` / `surge.SurgeScreen` / `expedition.ExpeditionScreen` |
  | ENCHANTING / COLLECTIONS | `screens.EnchantingScreen` / `collections.CollectionsScreen` |
  | VIP_BENEFITS / PURCHASING | `screens.VIPBenefitsScreen` / `screens.PurchasingScreen` |
  | MAILBOX / SIGN_IN | `windows.MailboxWindow` / `windows.SignInScreen` |

- **Contrôle d'accès** = `UINavHelper.canNavigateTo(Destination, …)` : `Unlockable.isUnlocked` (déblocage
  **piloté par les données** `.tab` via `MainScreenHelper.getUnlockable(MainIconType)`, ex. niveau d'équipe),
  **blocage tuto** (`mainScreenTutorialBlocked`), **contenu externe** (`checkExternalContent` charge les assets).

## 3. Les DEUX surfaces d'UI qui alimentent le routeur

- **Bâtiments de la ville** — enum **`MainIconType`** (23) : `CAMPAIGN, CHESTS, COLLECTIONS, CHALLENGES,
  COLISEUM, ENHANCEMENT, EXPEDITION, FIGHT_PIT, GUILDS, HEIST, INVASION, MEGA_MART, MISSIONS, PORT, RANKINGS,
  SURGE, TEAM_TRIALS, TRADER, VIDEO, VIP, WAR, BLACK_MARKET, SIGN_IN`. Chaque bâtiment →
  `MainScreenHelper.getDestination(MainIconType)` → une `Destination`. Tap capté par `MainScreenHitArea`
  (zones g2d, **pas** des boutons scene2d → `getPointers()` vide, cf. sonde `mapprobe`).
- **Menu latéral ☰** — chaque entrée = **`SideMenuIconData(texte, icône, pad, Destination)`**. Contenu = enum
  **`MenuIconType`** : `HEROES, ITEMS, QUESTS, MAILBOX, MEDALS, EVENTS` (+ `DEBUG`).

## 4. Flèche de tuto — cible définie par le PAS de tuto (le pilote la suit déjà)

- Le pas de tutoriel nomme un acteur par **tag** (`Actor.getTutorialName()`, ex. `CRAFTING_WINDOW_EQUIP_BUTTON`).
- `TutorialHelper.getPointers(IUser)` → liste de `TutorialPointerInfo`, chacun avec `getActorTutorialName()`
  = le tag ciblé → la flèche se pose sur l'acteur portant ce tag.
- **`TutorialDriver` l'utilise DÉJÀ** : lit `getPointers()` → `findTutorialActor(tag)` → tape.
- **Angle mort** : sur la carte de campagne (g2d) `getPointers()` est vide → entrée de niveau câblée par
  l'API du jeu (`normalOrEliteNodeSelected`).

## 5. Badges rouges "!" = « action dispo » — `DotTracker` (le pilote NE l'utilise PAS encore)

Manager central : **`com.perblue.heroes.ui.data.DotTracker`** (singleton `INSTANCE`). Il tient tous les points
d'attention du hub, **pilotés par la vraie logique du jeu** (quelque chose à réclamer/faire) — **hors tuto
compris, et plusieurs à la fois** :

| Champ | Contenu |
|---|---|
| `MAIN_RED_DOTS : Map<MainIconType, Dot>` | un point par **bâtiment** |
| `MENU_RED_DOTS : Map<MenuIconType, Dot>` | un point par **entrée du ☰** |
| `MENU_DOT` | le "!" **agrégé du bouton burger** (dépend des enfants du menu) |
| `MAIN_BLUE_DOTS`, `HERO_RED_DOTS`, `CHAT/CONTESTS/EVENTS/PRIZE_WALL/EMAIL_COLLECTION_…` | points spécifiques |

- Chaque `DotTracker$Dot` est **interrogeable** : **`boolean showDot()`** (calculé par un prédicat
  `RedDot.CalculateDot`, avec cache `nextUpdate`/`cacheDuration`), `update()`, `invalidate()`.
- `MainScreen.updateRedDots()` → `DotTracker.updateFromMainScreen()` rafraîchit.
- ⚠️ À NE PAS confondre avec `game.logic.notifs.NotificationHelper`/`NotificationType` = **notifications
  push/locales OS** (temporisées : free stamina, chest ready…), un système distinct.

**Opportunité (autonomie pilote post-tuto)** : énumérer `MAIN_RED_DOTS` + `MENU_RED_DOTS`, appeler `.showDot()`
sur chacun → liste des bâtiments/menus **actionnables** → naviguer via `UINavHelper.navigateTo(
MainScreenHelper.getDestination(iconType))`, traiter, recommencer. Deux signaux complémentaires pour le pilote,
tous deux **API du jeu** : `getPointers()` (guidage tuto, déjà branché) + `DotTracker` (actions générales, à
brancher).

## 6. Implication serveur

La navigation est **100 % côté client** (le serveur ne pilote rien). « Ajouter un écran du hub » côté serveur =
**implémenter les handlers des messages que cet écran émet** (comme Campaign→`CampaignAttack`, Chests→`BuyChests`,
Equip→`Action`). Écrans en lecture seule (VIP, rankings d'affichage…) → souvent rien à faire (données déjà dans
le BootData). Reco d'ordre (flux naturel post-campagne) : **HERO_MANAGEMENT (`HeroListScreen`) → ITEMS → QUESTS/
MISSIONS (claims) → coffres payants (#15)**. Méthode : « observer le protocole » (journaliser ce que l'écran
envoie) puis écrire le handler.

---

## 7. État des ÉCRANS (hub + menu ☰) — testés / à tester + gating niveau d'équipe (2026-07-20)

Liste canonique de **tous** les écrans navigables (`UINavHelper.Destination`) avec leur **déblocage par
niveau d'équipe** (source de vérité : `game-data/stats/unlockables.tab`, colonne `TEAM_LEVEL_REQ`) et le
**statut de test** dans le portage. Le compte de test est actuellement **TL2** → seuls les écrans « early
game » sont atteignables EN JEU ; les autres sont **légitimement verrouillés** (badge/onglet grisé, prouvé).

### 7.1 Écrans EARLY-GAME (TL1-2) — TESTÉS ✅

| Écran (Destination) | Déblocage | Serveur | En jeu | Notes |
|---|---|---|---|---|
| CAMPAIGN | TL1 | ✅ | ✅ | combat `recordOutcome` (tuto + 1-1/1-2) |
| HERO_MANAGEMENT (HEROES) | TL1 | ✅ | ✅ | `UNLOCK_HERO` (Vanellope), équip |
| CHESTS | TL1 | ✅ | ✅ | coffres gratuit + payant (anti-triche) |
| ITEMS (inventaire) | TL1 | ✅ | ✅ | `SELL_ITEM`/`USE_ITEM`/`VIEWED_CONSUMABLE_ITEM` |
| QUESTS | TL1 | ✅ | ✅ | complete/view/redeem + boîtes weekly |
| MEDALS (prize wall) | TL1 | ✅ | ✅ | `COMPLETE_QUEST` (achievements) |
| MAILBOX | TL1 | ✅ | ✅ | livraison + open/take/delete + `ActionGroup` |
| EVENTS | TL1 | ✅ | ✅ | rend (horaires quotidiens) + `UPDATE_TIME` ; events live-ops vides |
| SIGN_IN | TL1 | ✅ | ✅ | `CLAIM_SIGNIN_REWARD` (31 jours) |
| SETTINGS / CHOOSE NAME | TL1 | ✅ | ✅ | `SetPlayerName` |
| BATTLE_PASS | **TL11** | ✅ | 🔒 | serveur complet (claim/collect/buyout/rollover/premium) ; **vérif en jeu reportée TL11** |

### 7.2 Écrans MID/LATE-GAME — VERROUILLÉS au TL courant (NON testés, à débloquer)

Déblocage `TEAM_LEVEL_REQ` de `unlockables.tab`. **Pour les tester → monter le compte au niveau requis**
(idée user : passer le joueur à un **niveau élevé/max** pour tout débloquer d'un coup, puis balayer les écrans).

| Écran / feature | TL requis | Type |
|---|---|---|
| AUTO_FIGHT | 2 | combat auto (déjà utilisé par le pilote DEV) |
| SKILL_UPGRADE | 8 | montée de compétences héros |
| RANKINGS (arena league) | 10 | classement |
| FIGHT_PIT | 10 | arène PvP |
| BATTLE_PASS | 11 | (serveur fait) |
| ELITE_CAMPAIGN | 11 | campagne élite |
| ALCHEMY | 12 | achat d'or |
| GUILDS | 15 | guildes (+ chest social, dons, mercenaires…) |
| CHALLENGES | 20 | défis |
| SAVED_LINEUPS | 20 | line-ups sauvegardés |
| FRIENDSHIPS | 24 | amitiés (campagnes d'amitié, disks) |
| MISSIONS | 24 | missions d'amitié |
| EXPEDITION | 25 | expédition |
| COLLECTIONS | 26 | collections (cosmétiques) |
| CRYPT_RAID / SURGE | 30 | crypt raid |
| BLACK_MARKET | 31 | marché noir (store) |
| ENCHANTING | 35 | enchantement d'équipement |
| COLISEUM | 40 | coliseum PvP |
| MEGA_MART | 41 | store |
| GEAR_MARKET / MEMORY_MARKET | 42 | stores |
| WAR (guild war) | 45 | guerre de guilde |
| FRANCHISE_TRIALS | 55 | trials de franchise |
| INVASION | 60 | invasion (guilde) |
| CRYPT/PORT/TEAM_TRIALS/WISH_CHEST | divers | à mapper précisément |
| PATCHED_HEROES | 185 | héros patchés (très haut niveau) |
| HEIST | 9999 | **désactivé** (retiré du jeu) |

### 7.3 Écrans STORE / ACHATS — volontairement FERMÉS

`PURCHASING`, `VIP_BENEFITS`, `DAILY_DEAL`, `MEGA_DAILY_DEAL`, `PROMOS` (TL3), `DIRECT_PURCHASE`, l'onglet
**DEALS** de l'écran EVENTS : offres d'**achat**. **Serveurs d'achats FERMÉS** (aucun IAP réel — choix du
projet, cf. PRINCIPLES). Actuellement l'onglet DEALS est **vide** (aucune offre : ni event live-ops, ni store).

### 7.4 Stratégie de test des écrans restants
1. **Débloquer par le niveau d'équipe** : la plupart des écrans non testés sont gatés `TEAM_LEVEL_REQ`.
   → outil de dev pour **fixer le team level du compte** (ou monter en jouant la campagne) afin d'ouvrir en
   masse (RANKINGS/FIGHT_PIT à TL10, BATTLE_PASS/ELITE à TL11, GUILDS TL15, COLISEUM TL40, INVASION TL60…).
2. Puis **balayer chaque écran** avec la méthode habituelle (capturer → observer → trouver+combler le gap
   serveur → vérifier en jeu + persistance), comme QUESTS/HEROES/MAILBOX/ITEMS/EVENTS.

---

## 8. BACKLOG FONCTIONNEL (features à faire — hors dette technique de `SHIMS.md`)

Suivi des features de plus haut niveau demandées/identifiées (les TODO de **dette technique** restent dans
[`SHIMS.md`](SHIMS.md) « TODO suivis » ; le combat autoritatif #24/#25 y est décrit).

1. **Panneau ADMIN serveur** (futur, demandé). Réutiliser `ServerUser.addMail(MailMessage)` (prouvé en jeu :
   courrier `GLOBAL` texte libre + récompenses arbitraires rendu + réclamé) : interface pour **composer**
   (subject/message/fromSender + attachments `RewardDrop` arbitraires) et **envoyer** à un joueur ciblé OU en
   **broadcast** (type `GLOBAL`) à tous les joueurs du shard. À exposer via commande/console/route admin.

2. **DEALS / EVENTS — achats en MONNAIE DU JEU** (à évaluer). L'onglet DEALS et les events live-ops sont vides
   (store fermé + pas de live-ops). Certaines commandes d'achat n'utilisent PAS d'argent réel et **pourraient
   être rendues fonctionnelles** : `BUY_GOLD` (or ↔ diamants), `BUY_STAMINA`/`BUY_FRIEND_STAMINA`/
   `BUY_INVASION_STAMINA` (énergie ↔ diamants), `BUY_POWER_POINTS`… (logique du jeu à câbler, comme SELL_ITEM).
   Les offres en **argent réel** (`BUY_DIAMOND_BUNDLE_EVENT`, packs, DAILY_DEAL) restent **fermées**. **Events
   serveur** : `SpecialEventsRaw.events = List<SpecialEventRaw{eventID, jsonString}>` → livraison **admin-
   contrôlée en JSON** (comme le courrier), MAIS chaque **type** d'event (contest/invasion/trial/vente) a son
   rendu + ses actions serveur propres → rendre un event fonctionnel = définir le JSON **+** supporter sa
   logique par type (gros). Le panneau « FREE REWARDS EVERY DAY! » de l'écran EVENTS est du **contenu client**
   (horaires de reset), PAS un event serveur.

3. **Écrans mid/late-game à tester** après montée de niveau (cf. §7.2) : RANKINGS, FIGHT_PIT, ELITE_CAMPAIGN,
   ALCHEMY, GUILDS, CHALLENGES, FRIENDSHIPS/MISSIONS, EXPEDITION, COLLECTIONS, SURGE, ENCHANTING, COLISEUM,
   WAR, INVASION… + **battle pass EN JEU à TL11** (serveur déjà fait).
