# FRIENDSHIPS / MISSIONS (#72) — mode « Amitiés » — suivi d'implémentation

> Attaqué au pipeline industrialisé #73/#74 (`contract.sh --mode Friendship` + `ModeGraph --logic`), comme SURGE/CHALLENGES.
> Chaque incrément : contrat (ScreenContract) → logique du jeu (§3) → test headless (WireCheck + ClientOracle) →
> **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon pipeline + bytecode)

Deux systèmes liés, gatés **`Unlockable.FRIENDSHIPS` / `MISSIONS` = TL 24** (`unlockables.tab`) :
- **Amitiés (disks)** : chaque **paire de héros** (`FriendPairID{primary, secondary}`, `friendship_pairs.tab`) a une
  amitié qui **monte en niveau** (`empowerment`) → débloque un **disk** (capacité). XP gagnée via le combat/campagne d'amitié.
- **MISSIONS = campagne d'amitié** : une mini-campagne PAR paire (chapitres/nœuds/étages, `friendship_campaign*.tab`),
  combat via `recordOutcome`, récompenses de chapitre.

Écran principal **`MissionsMainScreen`** (Destination **`MISSIONS`**). Fenêtres : `FriendshipCampaignWindow`,
`FriendshipDiskUnlockWindow`, `FriendshipWallWindow` (mur/historique), `FriendFinderWindow`,
`FriendshipCampaignCompleteWindow`, `FriendshipUnlockedAnimationWindow`, `FriendshipLockedWindow`.
Hero chooser : `FriendCampaignHeroChooserScreen`.

### Données / persistance — **DÉJÀ dans `IndividualUserExtra` (persisté par write-through)**
- `friendships` (Map `FriendPairID→FriendPairData{empowerment, campaignBitsEarned, history, lastBattle,
  lastHistoryViewTime, viewedUnlockAnimation}`)
- `friendshipCampaignProgress` (Map), `friendshipMissionData` (Map), `inProgressFriendshipMissions` (List),
  `favoriteFriendships` (List), `lastFriendRequestTimes` (Map).
- `IndividualUser.getFriendship(pair)` / `getFriendships()` / `getFriendshipCampaignProgress(pair)` construisent
  depuis l'extra ; `ClientFriendship` = impl `IFriendship`. **⇒ mutations via IndividualUser = auto-persistées**
  (gros avantage vs CHALLENGES qui avait un blob dédié).
- **`BootData.friendshipOffsetData`** (`FriendshipOffsetData{shardID, contentTime, friendships[], levelOffsets[],
  rarityOffsets[]}`) = **config d'échelle CONTENU** (dérive de version), lue au boot par `FriendshipOffsets.setOffsets`.
  Défaut `new BootData()` = **non-null vide** (offsets vides ⇒ `getLevelOffset/getRarityOffset = 0` = pas de dérive,
  baseline fidèle pour une version de contenu figée). Le catalogue des paires est de la donnée CLIENTE
  (`friendship_pairs.tab`) → le client connaît déjà les paires ; le serveur livre l'ÉTAT du joueur.

### Logique du jeu (§3 — points d'entrée à EXÉCUTER)
- `FriendshipHelper` : `empowerFriendship(user, pair, int)` (montée de niveau), `buyFriendStamina(user)`,
  `setFavoritedFriendship(user, pair, bool)`, `viewedWall`/`viewedUnlockAnimation`, `getUnlockStatus`,
  `getVisibleFriends`.
- `FriendshipCampaignHelper` : `recordOutcome(user, pair, chapter, outcome, …)` (combat de campagne d'amitié =
  autoritatif, patron `CampaignAttack`/`SurgeCombat`), `giveChapterRewards`, `getRewardsForChapter`, `doNodeUpdate`,
  `getChapterStatus`, `getDifficulty`, `calculateGoldEarned`, `getFriendCampaignPower`.

### Actions client→serveur (`ClientActionHelper`)
- `empowerFriendship(pair, int)` · `buyFriendStamina()` · `setFavoriteFriendship(pair, bool)`.
- Combat de campagne d'amitié : `FriendshipCampaignAttack` (issue, patron des attaques de campagne).
- (À confirmer au bytecode/en jeu : messages de mur/historique, requêtes d'amis.)

## ⚠️ DÉCOUVERTE EN JEU (g83) — le cœur de l'écran MISSIONS = MISSIONS IDLE (non implémenté !)
La vérif EN JEU de l'incr. 4 a révélé que l'écran **MISSIONS** (`MissionsMainScreen`) ne pilote PAS le combat de
campagne, mais un **système de MISSIONS IDLE temporisées** : on choisit une **paire** (fenêtre `MissionsSelectFriendsWindow` :
sélectionne un héros → ses partenaires valides ✓) puis un **TYPE de mission** (`MissionsChooseWindow` :
**POWER-UPS / MEMORIES / DISK POWER**, chacune avec un timer « Every Xd Yh » + « MISSION SPEED +60,5 % »). START →
mission en cours → au bout du temps, réclamation des récompenses. **Actions** (`ClientActionHelper` + `CommandType`) :
`ADD_MISSION{MissionType, FriendPairID}` · `CLAIM_MISSION_REWARDS` · `CANCEL_MISSION` · `SPEEDUP_MISSION` ·
`UPDATE_MISSION` · `SET_MISSION_ITEM_COST_LIMIT`. **MissionType** : `POWER_UP_MISSION`, `MEMORY_MISSION`,
`DISK_POWER_MISSION`. **Logique du jeu (§3, `com.perblue.heroes.game.missions.MissionHelper`, statiques IUser)** :
`addMission(user, type, pair, time)`, `canStartMission(user, type, pair)→MissionFailType` (anti-triche),
`claimMissionRewards(user, time)`, `cancelMission(user, mission, time)`, `calculateMissionSpeed`, `canAffordMissionCosts`.
État persisté : `individualUserExtra.friendshipMissionData` + `inProgressFriendshipMissions` (write-through à valider).
**⇒ C'est REQUIS (pas optionnel — cf. consigne utilisateur) → nouvel incrément 3c**, non couvert par 2/3a/3b.
Corollaire : **empower** (disks) et le **combat de campagne** (`FriendshipCampaignAttack`, incr. 3b) ont leurs propres
points d'entrée UI à LOCALISER en jeu (empower = vue FRIENDSHIPS/détail d'une amitié ; le combat de campagne n'apparaît
pas sur l'écran MISSIONS de 12.1.0 — à confirmer : legacy ou accessible ailleurs). 2/3a/3b restent prouvés HEADLESS.

## Plan d'incréments
1. ✅ **Livraison / rendu — LIVRÉ (g79) + VÉRIFIÉ EN JEU** : `BootData.friendshipOffsetData` + conteneurs
   `IndividualUserExtra` non-null (**déjà OK par les défauts `new BootData()`/`new IndividualUserExtra()`** — aucun
   changement serveur requis). `FriendshipBootTest` (non-null + listes d'offsets de même longueur + `setOffsets`
   rejoué headless sans NPE + round-trip wire). **✅ EN JEU (compte TL100)** : `nav MISSIONS` → écran **MISSIONS**
   rend « **0/1 missions** », **ADD MISSION**, « No rewards to claim yet! » / CLAIM ALL — état frais correct, aucun
   NPE `FriendshipOffsets.setOffsets`. Le catalogue de paires est de la donnée CLIENTE (`friendship_pairs.tab`).
2. 🟢 **Favori + stamina — LIVRÉ HEADLESS (g80)** : handlers `LoginServer` + `ServerFriendships`, code du jeu (§3),
   zéro invention (§4). Protocoles PROUVÉS au bytecode (`ClientActionHelper`) :
   - `SET_FAVORITE_FRIENDSHIP{TYPE=FriendPairID.getAsLong(), COUNT=0/1}` → `FriendshipHelper.setFavoritedFriendship`
     (= `IndividualUser.setFavoriteFriendship`, aucun verrou). Persistance : l'ensemble `favoriteFriendships` est un
     champ de `IndividualUser` COPIÉ de l'extra au chargement → **`ServerUser.resyncFriendFavorites`** ré-écrit la
     `List<Long>` dans `individualUserExtra` (patron flags/counts).
   - `BUY_FRIEND_STAMINA{}` → `FriendshipHelper.buyFriendStamina` (débit **DIAMONDS**=getFriendStaminaBuyCost + crédit
     **FRIEND_STAMINA**=getFriendStaminaBuyAmount, dans les limites/plafond du jeu ; `FRIEND_STAMINA` dans
     `individualUserExtra.resources` = write-through). `resyncDiamonds` pour les diamants.
   - `FriendshipShopTest` : favori set/persist/reload/unfavorite ✅ ; buyStamina — chemin de refus (compte frais au
     plafond) géré ; **succès (débit/crédit) à exercer EN JEU** (stamina consommée par la campagne). **Vérif EN JEU restante.**
3a. 🟢 **Empower — LIVRÉ HEADLESS (g81)** : `EMPOWER_FRIENDSHIP{TYPE=FriendPairID.getAsLong(), COUNT=<nb pierres>}` →
   `FriendshipHelper.empowerFriendship` (code du jeu §3) : exige la paire **DÉBLOQUÉE** (`getUnlockStatus`==UNLOCKED,
   sinon `FRIENDSHIP_NOT_UNLOCKED`), `count>=1`, **CONSOMME `count` × `FRIENDSHIP_EMPOWER_STONE`** (`useItem`) puis
   `empowerment += getEmpowermentPerConsumable*count`. **Anti-triche** : `useItem`→`removeItem` NE lève PAS sur stock
   insuffisant (client-autoritatif) → `ServerFriendships.applyEmpower` MIROITE la garde cliente (`getItemAmount>=count`,
   donnée du jeu) → refus autoritatif si pierres insuffisantes. **Persistance** : nouveau **`ServerUser.resyncFriendships`**
   (map `friendships` : `ClientFriendship`→`FriendPairData`, `FriendshipEvent`↔`FriendshipEventData` mêmes champs ;
   ⚠️ ne pas écraser `lastBattle` avec null — `new FriendPairData()` l'initialise non-null et `getClientFriendship`
   lit `lastBattle.serverTime` sans garde) ; items consommés dans `individualUserExtra.items` (write-through).
   `FriendshipEmpowerTest` : déblocage (2 héros grantés) + empower (consomme 2 pierres) + anti-triche (refus sans
   pierre) + persistance DB. **Vérif EN JEU restante.**
3b. 🟢 **Campagne d'amitié (combat) — LIVRÉ HEADLESS (g82)** : message `FriendshipCampaignAttack{base, friendPairID,
   nodeNumber, lootEarned, memoryChanges, stagesCleared}` (PAS de handshake Start) → handler `LoginServer` →
   `ServerUser.recordFriendCampaignAttack` → `FriendshipCampaignHelper.recordOutcome(user, pair, node, outcome,
   loot, attackers, defenders, snap, chapter, level, false)` (code du jeu §3, autoritatif). **Gates du jeu**
   (anti-triche) : `FRIEND_STAMINA >= getStaminaCost(node)` (=6/nœud), `getLevelLockStatus==UNLOCKED`
   (⚠️ **nœuds 1-indexés** : jouable = `getFriendshipCampaignProgress(pair)+1`), `canUseHeroes(pair,node,attackers)`.
   **Chapitre/niveau normaux** (échelle XP) DÉRIVÉS par le code du jeu : `getNormalCampaignChapter(user)` +
   `getNormalCampaignLevel(pair, node, chapter)` (mapping du call-site client). Loot = client (PARTIEL §4bis/#25).
   Persistance via `resyncFriendships` (+héros/diamants/compteurs). `FriendshipCampaignTest` : paire débloquée +
   FRIEND_STAMINA → combat WIN nœud 1 → **-6 stamina, lastBattle{node=1, won=true}**, persistance DB. **Vérif EN
   JEU restante.** (`giveChapterRewards` = réclamation de chapitre, Action séparée — à câbler si besoin.)
3c. ✅ **MISSIONS IDLE (cœur de l'écran MISSIONS — REQUIS, révélé en jeu g83) — LIVRÉ + VÉRIFIÉ EN JEU (g84/g85)** :
   nouveau `ServerMissions` + handlers `LoginServer`, code du jeu (§3, `com.perblue.heroes.game.missions.MissionHelper`),
   zéro invention (§4). Protocoles PROUVÉS au bytecode (`ClientActionHelper`) :
   - `ADD_MISSION{TYPE=MissionType, ID=FriendPairID.getAsLong(), TIME}` → `MissionHelper.addMission(user, type, pair,
     serverTimeNow())`. `addMission` LÈVE sur la plupart des gates (paire non débloquée, héros déjà en mission,
     limites, bits max) MAIS **pas** sur coûts insuffisants (`chargeMissionCosts`=`removeItem`, client-autoritatif) →
     **anti-triche** : on MIROITE la garde cliente complète via `canStartMission(user, type, pair)` (prédicat pur,
     `null`=OK ; `CANT_AFFORD`/`FRIEND_ON_MISSION`/`MISSION_LIMIT`/`FRIEND_PAIR_LOCKED`/`DISK_AT_MAX_STARS`).
   - `CLAIM_MISSION_REWARDS{TIME}` → `claimMissionRewards(user, serverTimeNow())` = `updateAllMissions(time)` (fait
     avancer par le temps ; un timer à zéro produit une `MissionClaimData` — récompenses `MissionStats.getOtherRewards`,
     empowerment `getEmpowermentReward` — via `addMissionClaimData`) PUIS applique chaque `MissionClaimData`
     (`setEmpowerment` + `RewardHelper.giveRewards`) et `clearMissionClaimData`.
   - `CANCEL_MISSION{heroType=friendship.getPrimary(), TIME}` → `cancelMissionByHero(user, hero, serverTimeNow())`
     (retrouve la mission portant ce héros, rembourse, retire).
   - **Persistance** : la liste runtime `IndividualUser.missions` (`List<ClientMission>`) est bâtie au chargement
     depuis `individualUserExtra.missions` (`List<MissionData>`, cf. `setExtra`→`setMissions`) ; `addMission`/
     `removeMission` ne touchent QUE le runtime → **`ServerUser.resyncMissions`** ré-écrit `extra.missions` en
     extrayant le `MissionData` sous-jacent de chaque `ClientMission` (write-through wrapper, réflexion sur `data`).
     `missionClaimData` est écrit DIRECTEMENT dans `extra` par `addMissionClaimData`/`clearMissionClaimData`
     (write-through) → aucun resync. Empowerment via `resyncFriendships` ; récompenses items/ressources write-through ;
     diamants/héros/compteurs via resyncs standard.
   - **Faits du jeu (sondés)** : POWER_UP = sans coût, empReward=1, dur=60h ; MEMORY = coûte 1 STONE_VANELLOPE
     (→ `CANT_AFFORD` sur compte frais) ; DISK_POWER = sans coût, otherRewards=GEAR_JUICE 100 ; **limite combinée=1**.
   - `MissionLoopTest` : ADD POWER_UP → 1 mission (persiste DB) ; 2ᵉ ADD refusé (limite/coût, anti-triche) ; CANCEL
     par héros primaire → 0 (persiste) ; avance du temps (`debugHurryAllMissions`, méthode DEBUG du jeu, via l'outil
     `ServerUser.debugHurryMissions`) → CLAIM → **empowerment +1** + `missionClaimData` vidé, persistance DB.
     Régression **98/98**.
   - **✅ VÉRIFIÉ EN JEU (g85, userID=1 TL100, RALPH+VANELLOPE ORANGE 60/5)** — client réel → NOTRE serveur :
     `nav MISSIONS` rend « **0/1 missions** » + ADD MISSION + « No rewards to claim yet! ». **ADD** (chemin client réel
     `ClientActionHelper.addMission`, pilote `missionadd POWER_UP_MISSION RALPH VANELLOPE`) → serveur
     `ADD_MISSION(POWER_UP_MISSION RALPH-VANELLOPE) appliqué [persisté]` → **après redémarrage** le client relit l'état
     PERSISTÉ : « **1/1 missions** » + carte **« SUGAR RUSHED »** (Ralph+Vanellope, « On Completion **+1** » empowerment
     = empReward sondé) + timer répétable. **CLAIM** (mission avancée par l'outil DEV `MissionHurry` → « CLAIM ALL » +
     reward en attente rendus) → pilote `missionclaim` (`CLAIM_MISSION_REWARDS` réel) → serveur `appliqué [persisté]`,
     `claimEnAttente` 1→0. **CANCEL** → pilote `missioncancel` (`CANCEL_MISSION` réel, heroType=primaire) → serveur
     `appliqué [persisté]`. **État PERSISTÉ relu en DB** (`FriendMissionDump`) : **empowerment=1** (crédité par CLAIM),
     **missions=0** (retirée par CANCEL), **claimsEnAttente=0**. Pilotes DEV ajoutés (chemin `ClientActionHelper` réel,
     méthode B-bis) : `missionadd`/`missionclaim`/`missioncancel`/`missiondump` ; outils DEV `MissionHurry` (avance les
     timers persistés) + `FriendMissionDump` (lecture état persisté).
   - `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT` = à câbler si le flux en jeu les exerce (non bloquant, mêmes patrons).
4. **Vérif EN JEU** : ✅ **missions idle (3c) faite (g85)** — ADD→(hurry)→CLAIM + CANCEL, tout persisté. **RESTE** :
   empower → disk et campagne (3b) : **localiser les points d'entrée UI** (l'écran MISSIONS = missions idle, PAS empower
   ni combat ; empower = vue FRIENDSHIPS/détail d'une amitié ; campagne = peut-être legacy en 12.1.0). 2/3a/3b restent
   prouvés HEADLESS (97/97) — leur vérif en jeu attend la localisation de ces écrans.

## Notes §3/§4
- Persistance quasi-gratuite (état dans `individualUserExtra` write-through). Zéro invention : niveaux/récompenses/
  difficulté/puissance viennent des `friendship_*.tab` via les Helpers.
- `friendshipOffsetData` vide = baseline fidèle (aucune dérive de contenu) ; à enrichir depuis le contenu SI une
  vérif en jeu montre un écart (§8).
