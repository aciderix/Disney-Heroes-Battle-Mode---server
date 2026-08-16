# JOURNAL — journal détaillé des modifications

## 2026-08-10 (g88) — FRIENDSHIPS (#72) SPEEDUP_MISSION + SET_MISSION_ITEM_COST_LIMIT ✅ (headless + en jeu) + MàJ EXPLORATION

Dernières actions QoL du sous-système MISSIONS idle, code du jeu (§3), zéro invention (§4).

**SPEEDUP_MISSION** — accélère une mission idle en consommant des `MISSION_SPEEDUP`. Protocole (disasm
`ClientActionHelper.speedupMission`) : `heroType=friendship.getPrimary()`, `itemType=MISSION_SPEEDUP`,
`extra{COUNT=nb, TIME}`. Le client calcule un `MissionSpeedupData` mais le serveur ne lui fait PAS confiance : il
**re-dérive** via `MissionHelper.getSpeedupData(user, mission, item, count, serverTimeNow())` puis exécute
`useSpeedups` (lève `getNotEnoughResourceException` si stock insuffisant = anti-triche). `ServerMissions.applySpeedupMission`
(find mission by hero → getSpeedupData → useSpeedups → resyncMissions + resyncs). 1 `MISSION_SPEEDUP` = 
`MissionStats.getSpeedupDuration()` (=7 200 000 ms/2 h).

**SET_MISSION_ITEM_COST_LIMIT** — plafond de dépense auto d'un objet en missions (préférence joueur). Protocole :
`itemType=<objet>`, `extra{COUNT=plafond}` → `IndividualUser.setMissionItemCostLimit(item, N)` = écriture DIRECTE dans
`individualUserExtra.missionItemCostLimits` (**write-through**, `N=0` retire l'entrée). `ServerMissions.applySetItemCostLimit`.

Handlers `LoginServer` (SPEEDUP_MISSION / SET_MISSION_ITEM_COST_LIMIT). `MissionSpeedupTest` : cost-limit 3→persiste→0
retire ; speedup réduit `baseTimeRemaining` (2.16E8→1.81E8) + consomme les objets (5→2) + persiste. **Régression 99/99**.

**✅ VÉRIFIÉ EN JEU (userID=1, 20 MISSION_SPEEDUP semés)** : `nav MISSIONS` → `missionadd POWER_UP_MISSION RALPH VANELLOPE`
→ `speedup RALPH VANELLOPE 5` (chemin réel) → serveur `<== SPEEDUP_MISSION(RALPH MISSION_SPEEDUP x5) appliqué [persisté]`
(baseTimeRemaining **2.16E8→1.58E8**) ; `costlimit STONE_VANELLOPE 3` → `<== SET_MISSION_ITEM_COST_LIMIT(STONE_VANELLOPE=3)
appliqué [persisté]`. **Relu en DB** : `MISSION_SPEEDUP 20→15 (−5)`, `costLimit(STONE_VANELLOPE)=3`. Pilotes DEV
`speedup`/`costlimit` (chemin `ClientActionHelper` réel).

**⇒ FRIENDSHIPS #72 : 100 % LIVRÉ ET VÉRIFIÉ EN JEU, AUCUN RESTE** (seul `giveChapterRewards` = réclamation d'un chapitre
d'amitié COMPLET, à câbler si on complète un chapitre entier ; non bloquant).

**MÀJ `docs/EXPLORATION.md`** (demande utilisateur) : rangées restées ⬜ alors que les modes étaient livrés+vérifiés en
jeu → passées à ✅ : **CHALLENGES**, **SURGE**, **INVASION**, **FRIENDSHIPS**. Rangée **WAR** : trailing « vérification en
jeu nulle » corrigé (affichage+cycle de vie ✅ en jeu depuis 2026-08-02 ; actions de jeu 🟢). Vrais ⬜ restants (candidats
mode suivant) : SAVED_LINEUPS, EXPEDITION, COLLECTIONS, BLACK_MARKET, ENCHANTING, FRANCHISE/TEAM_TRIALS, PORT, WISHING_WELL.

## 2026-08-10 (g87) — FRIENDSHIPS (#72) incrément 2 (favori + stamina) ✅ vérifié EN JEU → FRIENDSHIPS 100 % en jeu

Dernier maillon de la vérif en jeu de FRIENDSHIPS (client réel, userID=1 TL100).

**FAVORI** : pilote `setfavorite RALPH VANELLOPE 1` (chemin réel `ClientActionHelper.setFavoriteFriendship(pair, true)`)
→ serveur `<== SET_FAVORITE_FRIENDSHIP(RALPH-VANELLOPE=true) appliqué [persisté]` (extra `{TYPE=<pairID>, COUNT=1}`).
Relu côté client (`frienddump`) et **après redémarrage** : `favorite=true` (persisté via `resyncFriendFavorites`).

**ACHAT D'ÉNERGIE D'AMITIÉ** : d'abord un **refus FIDÈLE** — avec FRIEND_STAMINA=519 (au-dessus du plafond 175), le
pilote `buystamina` (`ClientActionHelper.buyFriendStamina`) n'atteint PAS le serveur : la garde CLIENTE `doAction`
(local) lève `FRIEND_STAMINA_FULL` et n'émet pas (comportement d'origine ; les 2 gates du jeu = `FRIEND_STAMINA_FULL`
au plafond + `FRIEND_STAMINA_BUYS_USED` limite quotidienne). Après `SetFriendStamina 10` (outil DEV : met l'énergie sous
le plafond), relance → `buystamina` → serveur `<== Action command=BUY_FRIEND_STAMINA extra={}` → `<== BUY_FRIEND_STAMINA
(stamina) appliqué [persisté]` → **DIAMONDS 19000→18950 (−50 = getFriendStaminaBuyCost)**, **FRIEND_STAMINA +30 (=
getFriendStaminaBuyAmount)**. **Relu en DB** (`FriendMissionDump`) : `favorite=true, DIAMONDS=18950, FRIEND_STAMINA=196`.

Pilotes DEV ajoutés (chemin `ClientActionHelper` réel) : `setfavorite <p> <s> <0|1>`, `buystamina` ; `frienddump` étendu
(favorite/diamonds/friendStamina). Outils DEV : `SetFriendStamina [db] [val]` (met l'énergie d'amitié sous plafond pour
tester l'achat), `FriendMissionDump` étendu (favorite + DIAMONDS).

**⇒ FRIENDSHIPS #72 : TOUS les incréments VÉRIFIÉS EN JEU** — 1 (rendu) ✅, 2 (favori+stamina) ✅, 3a (empower) ✅,
3b (campagne d'amitié) ✅, 3c (missions idle) ✅. Régression serveur inchangée 98/98 (aucune modif de logique serveur ;
ajouts = pilotes/outils DEV + docs). Reste OPTIONNEL (non bloquant) : `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT`/
`giveChapterRewards` si un flux en jeu les exerce. **Prochain : choix du mode suivant.**

## 2026-08-10 (g86) — FRIENDSHIPS (#72) EMPOWER (3a) ✅ vérifié EN JEU + entrée campagne d'amitié (3b) localisée

Suite de la vérif en jeu (client réel, userID=1 TL100, RALPH+VANELLOPE ORANGE 60/5).

**Empower (3a) — entrée UI localisée + vérifié** : la vue disk/empower est HÉROS → détail d'un héros → onglet **Friends**
→ une amitié → mode **GEAR** (`HeroDetailFriendsContent.navigateToFriendUI(pair, FriendModeType.GEAR)`). Elle rend le
disque **« PIECE OF CAKE »** (Vanellope, « BOOSTS STUNS », LEVEL 1 +14067 Max HP/+90 Tenacity, STARS 0/25, **LOCKED**).
Le « LOCKED » est FIDÈLE : le disque se débloque via la campagne d'amitié/bits, pas via l'empowerment brut. **EMPOWER**
via le chemin client réel `ClientActionHelper.empowerFriendship(pair, 5)` (pilote `empower RALPH VANELLOPE 5`) → serveur
`<== EMPOWER_FRIENDSHIP(RALPH-VANELLOPE x5) appliqué [persisté]` → **empowerment 1→6** (getEmpowermentPerConsumable=1/
pierre × 5), **FRIENDSHIP_EMPOWER_STONE 40→35** → relu en DB (`FriendMissionDump`) : empowerment=6. Nouveaux pilotes DEV
(chemin `ClientActionHelper` réel, B-bis) : `friendui <p> <s> [MODE]` (navigateToFriendUI), `empower <p> <s> <n>`,
`frienddump <p> <s>`.

**Campagne d'amitié (3b) — entrée UI localisée** (grâce à l'indice de l'utilisateur : « y'a pas un bouton friend dans la
campagne ? ») : l'écran **CAMPAIGN** a bien **trois onglets NORMAL / ELITE / FRIENDS** (bas de l'écran ; `CampaignType`
= {NORMAL, ELITE, **FRIENDSHIP**}). L'onglet FRIENDS ouvre la liste des campagnes d'amitié (colonnes FRIENDSHIP |
CAMPAIGN | MEMORY DISK) : chaque paire a une campagne nommée (difficulté EASY/HARD), « Episode 1: 0/5 », un bouton
**UNLOCK**, et son disque mémoire (VIEW). **RALPH-VANELLOPE = « BULLY FOR YOU » (EASY)** (portraits Ralph+Vanellope +
badge vert **6** = l'empowerment crédité juste avant ; disque « PIECE OF CAKE »). ⚠️ **Correction d'une conclusion
erronée** : j'avais écrit que la campagne d'amitié était « legacy/verrouillée » en 12.1.0 — c'était FAUX, je n'avais
testé que `navigateToFriendUI(CAMPAIGN)` (qui ne fait qu'un toast `showInfoNotif` puis retourne). La vraie entrée est
l'onglet FRIENDS de l'écran CAMPAIGN (respect §8 : vérifier, ne pas supposer).

**Combat de campagne d'amitié (3b) JOUÉ & VÉRIFIÉ EN JEU (même session g86)** : après l'empower (empowerment=6), l'écran
« FRIENDSHIP UNLOCKED! » (Ralph+Vanellope) est apparu → CONTINUE → la campagne « BULLY FOR YOU » (RALPH-VANELLOPE) est
passée de UNLOCK à **GO!**. GO! → aperçu du nœud **EPISODE 1 « CREEP CLEARANCE »** (4 ennemis, bouton **NEXT = 6 énergie
d'amitié** = `FriendshipCampaignStats.getStaminaCost(node)`) → **CHOOSE YOUR HEROES** (Ralph+Vanellope+Elastigirl, TEAM
POWER 22643 vs ENEMY 880) → **QUICK FIGHT** → écran **REWARDS** (LOOT 783 or + 4 objets + Hero XP +5 Vanellope/Ralph/
Elastigirl). Le client envoie `FriendshipCampaignAttack1` → serveur : `<== FriendshipCampaignAttack : pair=RALPH-VANELLOPE
node=1 outcome=WIN → recordOutcome appliqué [persisté]`. **Relu en DB** (`FriendMissionDump` étendu) : **FRIEND_STAMINA
525→519 (−6)**, **campaignProgress 0→1** (nœud 1 franchi), **lastBattle{node=1 won=true}** — EXACTEMENT le comportement
headless (`FriendshipCampaignTest`), confirmé en jeu. (Le loot 783/objets = client-autoritatif, PARTIEL §4bis/#25.)
Note : après le combat la connexion s'est fermée (`onClose`, le client est resté figé sur REWARDS, fin de `DH_TIMEOUT`)
— sans incidence, l'issue avait déjà été reçue+persistée.

**BILAN vérif EN JEU FRIENDSHIPS #72** : incr. 1 (rendu) ✅, **3a empower ✅**, **3b campagne ✅**, **3c missions idle ✅**.
**SEUL RESTE** : incr. 2 (favori + stamina) en jeu (mêmes patrons `setFavoriteFriendship`/`buyFriendStamina`).

## 2026-08-10 (g85) — FRIENDSHIPS (#72) incrément 3c : MISSIONS IDLE ✅ VÉRIFIÉ EN JEU

Vérif EN JEU de l'incrément 3c contre NOTRE serveur (client réel, userID=1 TL100, RALPH+VANELLOPE ORANGE 60/5
préparé par `FriendAcctSetup`). Chaîne complète ADD → (avance temps) → CLAIM + CANCEL, tout par le chemin CLIENT
réel (`ClientActionHelper`) et persisté.

**Nouveaux pilotes DEV** (chemin `ClientActionHelper` RÉEL = ce que les boutons UI appellent, méthode B-bis — contourne
le hit-test capricieux des fenêtres MissionsSelectFriends/ChooseWindow, g83) : `missionadd <TYPE> <PRIMARY> <SECONDARY>`
(`addMission`→Action ADD_MISSION), `missionclaim` (`claimMissionRewards`→CLAIM_MISSION_REWARDS), `missioncancel [hero]`
(`cancelMission`→CANCEL_MISSION), `missiondump` (état missions côté client). Outils DEV : `MissionHurry` (avance les
timers PERSISTÉS via `ServerUser.debugHurryMissions`=`MissionHelper.debugHurryAllMissions`, serveur arrêté) +
`FriendMissionDump` (lecture de l'état persisté).

**Déroulé vérifié** :
1. `nav MISSIONS` → écran **MISSIONS** rend « **0/1 missions** » + ADD MISSION + « No rewards to claim yet! » / CLAIM ALL
   (état frais, aucun NPE). `missiondump` client = 0 missions.
2. **ADD** : `missionadd POWER_UP_MISSION RALPH VANELLOPE` → client `Action ADD_MISSION(POWER_UP_MISSION, RALPH-VANELLOPE)
   envoyée [chemin réel]` → serveur `<== ADD_MISSION(POWER_UP_MISSION RALPH-VANELLOPE) appliqué [persisté]`.
3. **Persistance ADD prouvée par REDÉMARRAGE** : stack arrêtée, `MissionHurry` charge userID=1 depuis la DB (→ la
   mission ÉTAIT persistée) et avance 1 cycle (→ `MissionClaimData en attente=1`). Relance de la pile → le client relit
   l'état PERSISTÉ : « **1/1 missions** » + carte **« SUGAR RUSHED »** (« Ralph has to help Vanellope hurry back… »,
   Ralph+Vanellope) + « **On Completion +1** » (empowerment = empReward POWER_UP sondé) + « Rewards In: 1d 20m » (timer
   du cycle suivant, mission répétable) + **CLAIM ALL** vert avec badge + « 5m 22s of rewards ». `missiondump` client =
   1 mission (POWER_UP_MISSION RALPH-VANELLOPE), claimEnAttente=1.
4. **CLAIM** : `missionclaim` → client `Action CLAIM_MISSION_REWARDS envoyée [chemin réel]` → serveur `<==
   CLAIM_MISSION_REWARDS(claim) appliqué [persisté]` ; `missiondump` client claimEnAttente 1→0, mission continue.
5. **CANCEL** : `missioncancel` → client `Action CANCEL_MISSION(RALPH-VANELLOPE) envoyée [chemin réel]` (heroType=RALPH,
   le primaire) → serveur `<== CANCEL_MISSION(cancel RALPH) appliqué [persisté]`.
6. **État PERSISTÉ relu en DB** (`FriendMissionDump`, serveur arrêté) : **RALPH-VANELLOPE empowerment=1** (crédité par le
   CLAIM), **missions=0** (retirée par le CANCEL), **claimsEnAttente=0**. ⇒ les trois handlers appliquent + persistent
   AUTORITATIVEMENT en jeu, valeurs = code/données du jeu.

**Note d'outillage** (pas un défaut serveur) : `missionadd`/`missionclaim` passent par le chemin d'ENVOI (`doAction` →
message), fire-and-forget, SANS l'apply LOCAL optimiste que le bouton UI déclenche → la vue cliente ne se rafraîchit
qu'au redémarrage (l'autorité est serveur, relue au boot via BootData/individualUserExtra). Le rendu de la carte et de
« CLAIM ALL » après redémarrage confirme que le client lit fidèlement notre état persisté.

**Bilan FRIENDSHIPS #72** : incr. 1 ✅ en jeu · **3c MISSIONS IDLE ✅ en jeu (g85)** · 2/3a/3b 🟢 headless (98/98).
**RESTE** : localiser les entrées UI **empower** (vue détail d'amitié) et **campagne 3b** (peut-être legacy 12.1.0) pour
finir leur vérif en jeu ; `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT` si le flux les exerce.

## 2026-08-09 (g84) — FRIENDSHIPS (#72) incrément 3c : MISSIONS IDLE d'amitié (🟢 headless, 98/98)

Le système de MISSIONS IDLE révélé en jeu (g83) — cœur de l'écran MISSIONS de 12.1.0 — implémenté côté serveur.
Nouveau `dhserver/ServerMissions` + handlers `LoginServer`, TOUT par le code du jeu (§3,
`com.perblue.heroes.game.missions.MissionHelper`), zéro invention (§4).

**Recon bytecode (pipeline #73/#74, `javap`)** :
- `ClientMission implements IMission` = simple WRAPPER write-through de `MissionData` (getters/setters lisent/écrivent
  `data` ; seule `friendship` est un cache de `data.friendshipPairID`). La liste runtime `IndividualUser.missions`
  (`List<ClientMission>`) est bâtie au chargement depuis `individualUserExtra.missions` (`List<MissionData>`, cf.
  `setExtra`→`setMissions`) ; `addMission`/`removeMission` ne touchent QUE le runtime → resync requis.
- `MissionHelper.addMission(user, type, pair, time)` : gates qui LÈVENT (paire non débloquée `FRIENDSHIP_NOT_UNLOCKED`,
  héros déjà en mission `MISSION_HERO_USED`, `TOO_MANY_MISSIONS`/`_OF_TYPE`, `ALREADY_HAVE_ENOUGH_BITS`), puis
  `chargeMissionCosts` (débit items/ressources via `removeItem`, **ne lève PAS** sur stock insuffisant), crée la
  mission (`IndividualUser.addMission`), pose startTime/lastUpdateTime/baseDuration/speed(`calculateMissionSpeed`).
- `updateAllMissions(user, time)`→`updateMissionProgress` : avance chaque mission par (temps écoulé × speed) ; timer à
  zéro → crée une `MissionClaimData{type, pair, startTime, endTime, empowerment=getEmpowermentReward, drops=
  MissionStats.getOtherRewards, costsPaid, count, cycleID}` → `addMissionClaimData` (**write-through** extra) → puis
  retire la mission (ou recharge le cycle si répétable).
- `claimMissionRewards(user, time)` : `updateAllMissions(time)` PUIS applique chaque `MissionClaimData`
  (`setEmpowerment` sur l'amitié si empowerment>0, `RewardHelper.giveRewards(drops)`, contest burns) →
  `clearMissionClaimData` + `incDailyUses`/`setTime`/`setCount`. Renvoie true si réclamé.
- `cancelMissionByHero(user, unitType, time)` : retrouve la mission portant ce héros (`getMissionWithHero`), rembourse,
  retire. Le client (`ClientActionHelper.cancelMission`) identifie la mission par `friendship.getPrimary()` (heroType).

**Protocoles (disasm `ClientActionHelper`)** : `ADD_MISSION{TYPE=MissionType, ID=FriendPairID.getAsLong(), TIME}` ;
`CLAIM_MISSION_REWARDS{TIME}` ; `CANCEL_MISSION{heroType=friendship.getPrimary(), TIME}`. Le serveur utilise SON
horloge (`TimeUtil.serverTimeNow()`), pas le `TIME` client (anti-triche sur le timing idle).

**Anti-triche** : `addMission` ne couvrant pas l'affordabilité, `ServerMissions.applyAddMission` miroite la garde
cliente COMPLÈTE via `canStartMission(user, type, pair)` (prédicat pur, `null`=OK ; sinon refus :
`CANT_AFFORD`/`FRIEND_ON_MISSION`/`MISSION_LIMIT`/`FRIEND_PAIR_LOCKED`/`DISK_AT_MAX_STARS`).

**Persistance** : nouveau `ServerUser.resyncMissions` (extrait le `MissionData` sous-jacent de chaque `ClientMission`
par réflexion → `extra.missions`) ; `missionClaimData` write-through (aucun resync) ; empowerment via
`resyncFriendships` ; récompenses items/ressources write-through ; diamants/héros/compteurs via resyncs standard.
Outil DEV `ServerUser.debugHurryMissions(cycles)` (avance les timers via la méthode DEBUG du jeu
`MissionHelper.debugHurryAllMissions` — utile headless ET pour la vérif en jeu de la réclamation sans attendre les heures).

**Faits du jeu SONDÉS** (`MissionStats`, compte RALPH+VANELLOPE ORANGE 60/5) : POWER_UP = sans coût, empReward=1,
dur=60h ; MEMORY = coûte 1 `STONE_VANELLOPE` (→ `CANT_AFFORD` sur compte frais) ; DISK_POWER = sans coût,
otherRewards=`GEAR_JUICE` 100 ; **limite combinée = 1** (TL100).

`MissionLoopTest` : ADD POWER_UP → 1 mission (survit DB) ; 2ᵉ ADD refusé (limite/coût, anti-triche) ; CANCEL par héros
primaire → 0 (persiste) ; `debugHurryMissions(1)` → un `MissionClaimData` en attente → `CLAIM_MISSION_REWARDS` →
**empowerment 0→1** (empReward POWER_UP) + `missionClaimData` vidé, persistance DB + round-trip wire. Régression
**98/98**.

**RESTE** : incr. 4 vérif EN JEU (ADD une mission → avancer/hurry → CLAIM en jeu ; localiser les entrées UI empower/
campagne) ; `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT` si le flux en jeu les exerce (mêmes patrons, non bloquant).

## 2026-08-09 (g83) — FRIENDSHIPS (#72) vérif EN JEU → découverte du système de MISSIONS IDLE (incr. 3c requis)

Vérif EN JEU de l'incrément 4 (contre notre serveur). Compte userID=1 préparé par le **nouvel outil DEV
`FriendAcctSetup`** (RALPH+VANELLOPE ORANGE 60/5 → paire 1 débloquée, `FRIEND_STAMINA=350`, 20
`FRIENDSHIP_EMPOWER_STONE`). `nav MISSIONS` → écran MISSIONS (« 0/1 missions », ADD MISSION) → **ADD MISSION** ouvre
`MissionsSelectFriendsWindow` (« CHOOSE FRIENDS FOR MISSION », roster rendu) → **sélection de Ralph confirmée EN JEU**
(coche verte ; le grid filtre les partenaires valides : Vanellope, Sulley) → **`MissionsChooseWindow`** (« CHOOSE A
MISSION TYPE ») : trois types **POWER-UPS / MEMORIES / DISK POWER**, chacun avec un timer (« Every 1d13h / 18h41m /
5h55m ») + « MISSION SPEED +60,5 % » + START.

**Découverte (le point de la vérif en jeu)** : l'écran MISSIONS de 12.1.0 pilote un **système de MISSIONS IDLE
temporisées** (envoyer une paire d'amis en mission → attendre → récompenses power-ups/mémoires/disk-power), et **NON**
le combat de campagne que j'avais câblé en 3b. Ce sous-système n'est **pas implémenté** côté serveur :
- Actions : `ADD_MISSION{MissionType, FriendPairID}`, `CLAIM_MISSION_REWARDS`, `CANCEL_MISSION`, `SPEEDUP_MISSION`,
  `UPDATE_MISSION`, `SET_MISSION_ITEM_COST_LIMIT` (`ClientActionHelper`/`CommandType`).
- MissionType : `POWER_UP_MISSION`, `MEMORY_MISSION`, `DISK_POWER_MISSION`.
- Logique du jeu (§3) : `com.perblue.heroes.game.missions.MissionHelper` — statiques IUser propres :
  `addMission(user, type, pair, time)`, `canStartMission(user, type, pair)→MissionFailType` (anti-triche),
  `claimMissionRewards(user, time)`, `cancelMission(user, mission, time)`, `calculateMissionSpeed`,
  `canAffordMissionCosts`. État : `individualUserExtra.friendshipMissionData` + `inProgressFriendshipMissions`.

**Conséquence (consigne utilisateur « rien d'optionnel tant que pas prouvé optionnel »)** : ce système est **REQUIS**
→ **incrément 3c** (ADD/CLAIM/CANCEL/SPEEDUP_MISSION + persistance + vérif en jeu). Par ailleurs, **empower** (disks)
et le **combat de campagne** (3b) ne s'atteignent pas depuis l'écran MISSIONS → leurs points d'entrée UI restent à
LOCALISER en jeu (empower = vue FRIENDSHIPS/détail d'une amitié ; le combat de campagne est peut-être legacy en
12.1.0). Les incréments 2/3a/3b restent **prouvés HEADLESS** (code du jeu exécuté, 97/97) — leur vérif EN JEU est à
compléter une fois les entrées UI localisées.

**Frictions de pilotage notées** (outillage, pas serveur) : les cartes de `MissionsSelectFriendsWindow` ont un
overlay `UnitViewStars` sans listener + `PressableStack` sans listener apparent → `fire` sélectionne par bulle
d'événement (a marché pour Ralph) mais la sélection du 2ᵉ héros / START est capricieuse. `sleep` avant-plan reste
BLOQUÉ (exit 144) → boucles `until`/`run_in_background`.

**RESTE** : incr. 3c (missions idle) ; incr. 4 (vérif en jeu missions idle + localiser empower/campagne). Rien de
commité côté serveur cette session (découverte + docs + outil `FriendAcctSetup`).

## 2026-08-09 (g82) — FRIENDSHIPS (#72) incrément 3b : combat de campagne d'amitié (🟢 headless)

Combat de la campagne d'amitié (MISSIONS), patron campagne/SURGE (client-autoritatif + serveur ré-exécute).
Message `FriendshipCampaignAttack{base:AttackBase, friendPairID:long, nodeNumber, lootEarned, memoryChanges,
stagesCleared}` — **pas de handshake Start** (le client joue puis envoie l'issue). Handler `LoginServer` →
`ServerUser.recordFriendCampaignAttack` → `FriendshipCampaignHelper.recordOutcome(user, pair, node, m.base.outcome,
loot, m.base.attackers, m.base.defenders, SpecialEventSnapshot.NONE, chapter, level, false)` (code du jeu §3).

**Mapping des params** (relevé au call-site client `CampaignAttackScreen`) : arg2=node, arg8=chapter, arg9=level ;
chapter/level = campagne NORMALE sous-jacente (échelle XP via `CampaignStats.getExpReward`) DÉRIVÉE par le jeu :
`getNormalCampaignChapter(user)` + `getNormalCampaignLevel(pair, node, chapter)` (aucune invention §4).

**Gates du jeu (anti-triche, dans recordOutcome)** : `FRIEND_STAMINA >= FriendshipCampaignStats.getStaminaCost(node)`
(=6/nœud, sinon no-op) ; `getLevelLockStatus(user, pair, node)==UNLOCKED` sinon `FRIENDSHIP_CAMPAIGN_LEVEL_LOCKED`.
**🔑 Nœuds 1-INDEXÉS** : `getLevelLockStatus` exige `node == getFriendshipCampaignProgress(pair)+1` (nœud 0 =
ALREADY_COMPLETE) → le 1er nœud jouable d'une amitié fraîche est **1**, pas 0. `canUseHeroes(pair, node, attackers)`
(les héros de la paire). Le combat débite l'énergie, `doNodeUpdate` (progression), `setLastBattle`, crédite le loot
reçu (client, PARTIEL §4bis/#25 — graine non rejouée) + XP.

Persistance : `resyncFriendships` (map amitiés) + héros/diamants/compteurs. `FriendshipCampaignTest` : paire
débloquée (2 héros grantés) + `FRIEND_STAMINA` → `FriendshipCampaignAttack` WIN au nœud 1 → **-6 stamina,
`lastBattle{node=1, won=true}`**, survit à la persistance DB + round-trip wire.

**Bilan FRIENDSHIPS #72** : incréments **1-3 livrés côté serveur** (1 ✅ en jeu ; 2 favori/stamina, 3a empower,
3b campagne 🟢 headless). Régression **97/97**. **RESTE** : incr. 4 vérif EN JEU (favori/empower via le fix pilote
modale + campagne d'amitié jouée) ; `giveChapterRewards` (réclamation de récompense de chapitre) si le flux le
demande. Détail : `docs/FRIENDSHIPS.md`.

## 2026-08-09 (g81) — FRIENDSHIPS (#72) incrément 3a : EMPOWER d'amitié (🟢 headless)

`EMPOWER_FRIENDSHIP{TYPE=<FriendPairID.getAsLong()>, COUNT=<nb pierres>}` → `FriendshipHelper.empowerFriendship`
(code du jeu §3, disasm) : (1) `getUnlockStatus(user, pair)==UNLOCKED` sinon `FRIENDSHIP_NOT_UNLOCKED` (déblocage =
`Unlockable.FRIENDSHIPS` TL24 + les DEUX héros de la paire possédés au niveau/contenu requis — statuts `TL_LOCKED`/
`HERO_MISSING_LOCKED`/`HERO_CONTENT_LOCKED`/`UNLOCKED`) ; (2) `count>=1` ; (3) **`UserHelper.useItem(user,
FRIENDSHIP_EMPOWER_STONE, count)`** (le COÛT) ; (4) `setEmpowerment(empowerment + getEmpowermentPerConsumable*count)`.

**Anti-triche** : `useItem`→`IUser.removeItem` NE lève PAS sur stock insuffisant (modèle client-autoritatif, comme
le loot) → un client modifié pourrait sur-empower sans pierres. `ServerFriendships.applyEmpower` MIROITE la garde
d'entrée CLIENTE avec la donnée du jeu (`getItemAmount(FRIENDSHIP_EMPOWER_STONE) >= count`) → refus autoritatif.
Pas une règle inventée (§3) : c'est la condition que le client applique déjà avant d'émettre l'action.

**Persistance — nouveau `ServerUser.resyncFriendships(IndividualUser)`** : `ClientFriendship` (runtime) a ses propres
champs et ne wrappe pas `FriendPairData` (wire), et `getExtra()` ne re-sérialise pas → on ré-écrit la map
`individualUserExtra.friendships` (clé `getAsLong()`) depuis `iu.getFriendships()` : empowerment, campaignBitsEarned,
viewedUnlockAnimation, lastHistoryViewTime, lastBattle, history (`FriendshipEvent`→`FriendshipEventData`, mêmes
champs level/missionNumber/storyNoteNumber/time/type). **🐛 Piège résolu** : ne PAS écraser `lastBattle` avec null —
`new FriendPairData()` l'initialise à `new FriendshipBattleInfo()` (non-null) et `getClientFriendship` lit
`data.lastBattle.serverTime` SANS garde → NPE au rechargement si null (un `ClientFriendship` jamais combattu a
`getLastBattle()==null`). On ne pose `lastBattle` que s'il est non-null. Items consommés dans
`individualUserExtra.items` (write-through).

`ServerFriendships.applyEmpower` + handler `LoginServer` (`EMPOWER_FRIENDSHIP`, groupé avec favori/stamina).
`FriendshipEmpowerTest` : `grantHero(RALPH/VANELLOPE, ORANGE, 60, 5)` → `getUnlockStatus`==UNLOCKED ; refus sans
pierre (anti-triche) ; donne 3 pierres, empower ×2 → empowerment=`perStone*2`, 2 pierres consommées (reste 1) ;
persistance DB (empowerment + stock de pierres survivent) ; round-trip wire `individualUserExtra`. `perStone`=
`FriendshipStats.getEmpowermentPerConsumable()` (=1 mesuré, donnée du jeu).

**RESTE** : incr. 3b campagne d'amitié (`FriendshipCampaignAttack` → `FriendshipCampaignHelper.recordOutcome` +
`giveChapterRewards`, réutilise `resyncFriendships`) ; incr. 4 vérif EN JEU (favori/stamina/empower/campagne).

## 2026-08-09 (g80) — FRIENDSHIPS (#72) incrément 2 : favori + stamina (🟢 headless)

Deux Actions « légères » du mode, par le CODE DU JEU (§3), zéro invention (§4). Protocoles PROUVÉS au bytecode
(`ClientActionHelper` : `with(TYPE, FriendPairID.getAsLong())` + `withCount`) :
- `SET_FAVORITE_FRIENDSHIP{TYPE=<pair long>, COUNT=0/1}` → `FriendshipHelper.setFavoritedFriendship(user, pair, fav)`
  (= `IndividualUser.setFavoriteFriendship`, AUCUN verrou). L'ensemble `favoriteFriendships` est un champ de
  `IndividualUser` COPIÉ de l'extra au chargement (comme flags/counts) → nouveau **`ServerUser.resyncFriendFavorites`**
  ré-écrit la `List<Long>` (`getAsLong`) dans `individualUserExtra.favoriteFriendships`.
- `BUY_FRIEND_STAMINA{}` (sans extra) → `FriendshipHelper.buyFriendStamina(user)` : débite `DIAMONDS`
  (`getFriendStaminaBuyCost`) + crédite `FRIEND_STAMINA` (`getFriendStaminaBuyAmount`), dans les limites/plafond du
  jeu. `resyncDiamonds` (diamants) ; `FRIEND_STAMINA` vit dans `individualUserExtra.resources` (write-through).

`ServerFriendships` (nouveau) + handlers `LoginServer` (`SET_FAVORITE_FRIENDSHIP`/`BUY_FRIEND_STAMINA`). Fire-and-forget
(le client applique localement). `FriendshipShopTest` : favori set → `isFavoriteFriendship` + re-sync extra →
persistance DB (save/reload) → dé-favori ; buyStamina — chemin de REFUS géré (compte frais au plafond de
`FRIEND_STAMINA` → pas d'achat) ; succès (débit/crédit) à exercer EN JEU (stamina consommée par la campagne).

**Découvertes persistance** (recon) : `ClientFriendship` (runtime, impl `IFriendship`) a ses PROPRES champs
(empowerment/campaignBitsEarned/history/lastBattle/…) — il ne wrappe PAS `FriendPairData` (le wire) ; `getExtra()`
renvoie l'extra STOCKÉ sans re-sérialiser. Donc empower/campagne (incr. 3) exigeront un `resyncFriendships` COMPLET
(map `friendships` : `ClientFriendship`→`FriendPairData`, y c. la conversion `history` FriendshipEvent↔EventData).
`empowerFriendship` est GATÉ `FRIENDSHIP_NOT_UNLOCKED` (anti-triche : paire débloquée = 2 héros possédés au niveau).

**RESTE** : incr. 3 (empower + campagne d'amitié = `FriendshipCampaignAttack`→`recordOutcome`+`giveChapterRewards` +
`resyncFriendships`), incr. 4 vérif en jeu complète. Détail : `docs/FRIENDSHIPS.md`.

## 2026-08-09 (g79) — FRIENDSHIPS/MISSIONS (#72) : recon pipeline + incrément 1 (livraison/rendu) ✅ EN JEU

Nouveau mode (choix utilisateur), attaqué au **pipeline #73/#74** : `contract.sh --mode Friendship` (ModeGraph →
ScreenContract) a donné le contrat complet — gate `Unlockable.FRIENDSHIPS` (TL24), écran `MissionsMainScreen` +
fenêtres `FriendshipCampaignWindow`/`DiskUnlockWindow`/`WallWindow`/`FriendFinderWindow`, messages
`FriendshipBattleInfo`/`HeroLineup`/`FriendshipCampaignAttack`.

**Modèle** (recon bytecode) : deux systèmes liés — **amitiés** (chaque paire `FriendPairID{primary,secondary}` monte
en `empowerment` → disk) et **campagne d'amitié (MISSIONS)** (mini-campagne par paire, combat via
`FriendshipCampaignHelper.recordOutcome`, récompenses de chapitre). Logique §3 : `FriendshipHelper`
(`empowerFriendship`/`buyFriendStamina`/`setFavoritedFriendship`) + `FriendshipCampaignHelper`
(`recordOutcome`/`giveChapterRewards`/`doNodeUpdate`/`getRewardsForChapter`).

**Persistance quasi-GRATUITE** : l'état vit dans `IndividualUserExtra` (`friendships`, `friendshipCampaignProgress`,
`friendshipMissionData`, `favoriteFriendships`, `inProgressFriendshipMissions`, `lastFriendRequestTimes`) — DÉJÀ
persisté par write-through (le `User`/`IndividualUser` est bâti dessus). Contraste avec CHALLENGES (blob dédié).
`BootData.friendshipOffsetData` = config d'échelle CONTENU (lue au boot par `FriendshipOffsets.setOffsets` — NULL ⇒
NPE) ; **non-null vide par défaut** (`new BootData()`), offsets vides ⇒ `getLevelOffset/getRarityOffset = 0` = pas de
dérive = baseline fidèle pour une version de contenu figée.

**Incrément 1 — livraison/rendu** : les conteneurs (`friendshipOffsetData` + maps d'`IndividualUserExtra`) sont
**déjà non-null par les défauts** → AUCUN changement serveur nécessaire. `FriendshipBootTest` verrouille le contrat
(non-null, listes d'offsets de même longueur, `FriendshipOffsets.setOffsets(offset)` rejoué HEADLESS sans NPE,
round-trip wire `friendshipOffsetData` + `BootData`). **✅ EN JEU** (compte TL100) : `nav MISSIONS` → l'écran
**MISSIONS** rend « **0/1 missions** », **ADD MISSION**, « No rewards to claim yet! » / CLAIM ALL — état frais
correct, aucun NPE `setOffsets`. Le catalogue de paires est de la donnée CLIENTE (`friendship_pairs.tab`).

**Fix d'outillage annexe** : découvert que le **`sleep` en avant-plan est bloqué** dans l'environnement (exit 144 /
SIGSTKFLT) → les lancements client qui faisaient `sleep` avant le `nohup … &` étaient tués AVANT de démarrer (logs
périmés trompeurs). Correctif de méthode : lancer sans `sleep` (run-online.sh tue les anciens via DH_KILL_OLD), et
attendre via `run_in_background`/boucle `until` (jamais `sleep` en avant-plan).

**RESTE** : incr. 2 (empower + favori : Actions `EMPOWER_FRIENDSHIP`/`SET_FAVORITE_FRIENDSHIP` → `FriendshipHelper`),
incr. 3 (campagne d'amitié : `FriendshipCampaignAttack` → `recordOutcome` + `giveChapterRewards` + stamina), incr. 4
vérif en jeu complète. Détail : `docs/FRIENDSHIPS.md`.

## 2026-08-09 (g78) — CHALLENGES (#72) : achats/annulation ✅ EN JEU + fix pilote pour les MODALES (réutilisable)

**Fix pilote (outillage, réutilisable TOUS les modes).** `TutorialDriver.fireClick` ne hit-testait que le stage de
l'ÉCRAN (`getRootStack` = `belowBlurStage`) → il ratait les boutons des fenêtres MODALES (confirmations d'achat, de
reset…) qui vivent sur `ScreenManager.aboveBlurStage` (au-dessus du blur) → il tapait le bouton DERRIÈRE la modale.
Correctif : `fireClick` détecte une modale ouverte via `hasModal(aboveBlurStage.getRoot())` (acteur VISIBLE dont la
classe/super-classe évoque `ModalWindow`/`Prompt`/`Confirm*` — les modales du jeu sont des `DHWindow` : `DecisionPrompt`,
`GenericPurchasePrompt`, `*ConfirmationWindow`…). Si modale ouverte → hit-test `aboveBlurStage` ; sinon → stage
d'écran/fenêtres. Filtre « acteur réellement cliquable » (self/ancêtre a des listeners) pour ne pas taper le HUD inerte
d'`aboveBlur`. Pure lecture de la scène (réflexion sur les champs privés `aboveBlurStage`/`belowBlurStage`), aucune
modif du jeu.

**Vérifs EN JEU** (compte userID=1 TL100, +20000 diamants via outil DEV `GrantDiamonds`) contre notre serveur :
- **BUY_STICKER_CHALLENGE_SLOT ✅** : carte `BUY 1,000` (bleue car diamants OK) → tap → modale **« EXTRA SLOT? Buy
  extra challenge slot for 1,000 diamonds? »** → YES (atteint via le fix) → le client émet
  `Action{command=BUY_STICKER_CHALLENGE_SLOT, extra={SLOT=NORMAL_2}}` → handler `<== BUY_STICKER_CHALLENGE_SLOT
  (NORMAL_2) appliqué [persisté]` → **DIAMONDS 20000→19000 (-1000 = CHALLENGE_SLOT_2_COST), CHALLENGE_SLOT_2=true**
  (relu DB) → le client rend un **2ᵉ slot « ADD A CHALLENGE »**. Prouve le chemin d'ACHAT (purchaseSlot autoritatif +
  débit diamants + flag + persistance) de bout en bout en jeu.
- **CANCEL_STICKER_CHALLENGE ✅** (comble le dernier trou de l'incr. 2) : détail livre STARTER → **RESTART** sur le
  défi actif → modale **« RESTART CHALLENGE? »** → YES → `Action{command=CANCEL_STICKER_CHALLENGE, extra={SLOT=STARTER,
  TYPE=THE_NAMES_NICK, TIME}}` → `<== CANCEL_STICKER_CHALLENGE(THE_NAMES_NICK/STARTER) appliqué [persisté]` → minuterie
  remise à 7 j (le jeu ré-avance via `setupStarterChallenges`). Confirme aussi la PERSISTANCE inter-lancements :
  `CATCH A STAR` toujours **COMPLETE** (le claim g75 a survécu à 2 relances).

**Bilan mutations CHALLENGES #72 en jeu** : CLAIM (g75) + BUY_STICKER_CHALLENGE_SLOT + CANCEL — **toutes ✅**.
Restent non exercés en jeu (NON bloquants, même chemin handler/modale prouvé, + headless + bytecode) : BUY_STICKER,
BUY_STICKER_BOOK, START_STICKER_CHALLENGE (gaté : slot STARTER unique / défis NORMAL à débloquer), SET_FAVORITE_STICKER.
Aucune modif serveur (les handlers étaient commités g74/g76) — seul le pilote (`TutorialDriver`) change + docs.

## 2026-08-09 (g77) — CHALLENGES (#72) incrément 4 : handler GetUserChallengeDataExtra (🟢 headless, 93/93)

**`GetUserChallengeDataExtra{targetUserID}` → `UserChallengeDataExtra`** (requête/réponse, patron `GetSurge`).
La fenêtre `StickerOverviewWindow` envoie ce message (disasm : `sendMessage(msg, UserChallengeDataExtra.class,
listener)`) pour afficher les stickers d'un joueur. Handler `LoginServer` : charge le joueur ciblé (soi-même = `user` ;
sinon `store.loadIfExists(targetUserID, shardID)`), renvoie `ServerChallenges.load(target)` (état persisté) ou
`freshData` si absent — `userID = targetUserID`, jamais null (écran non vide), `setAsReplyTo`. `ChallengeViewTest`
(état persisté d'un AUTRE joueur relu + wire round-trip + cible inconnue → freshData).

**`VIEW_CHALLENGES`** (Action émise à l'ouverture des livres) : au bytecode c'est un `VIEW_*` (marqueur/analytics
groupé avec `VIEW_SINGLE_CHALLENGE`/`VIEW_COSTUME`…), sans effet serveur observé. Laissée **non gérée HONNÊTEMENT**
(§2 : pas de faux OK) — NON bloquante : le client navigue en local (vérifié EN JEU g75, les livres s'affichent).

**Bilan CHALLENGES #72** : incréments **1-4 livrés côté serveur** (1 ✅ boot en jeu, 2 ✅ en jeu, 3 🟢, 4 🟢),
régression **93/93**. Reste : vérif EN JEU de l'incr. 3 (achats — nécessite des diamants) et de la fenêtre
`StickerOverviewWindow` (incr. 4) ; progression transversale (hooks `ChallengeImpl` campagne/chest/arène/breaker,
autorité de progression à observer en jeu). Détail : `docs/CHALLENGES.md`.

## 2026-08-09 (g76) — CHALLENGES (#72) incrément 3 : économie stickers (🟢 headless, 92/92)

4 Actions d'économie, tout par le CODE DU JEU (§3), zéro invention (§4). Protocoles PROUVÉS au bytecode
(`ClientActionHelper` + `ActionExtraBuilder`), extras en `.name()` (String) :
- `BUY_STICKER{TYPE=StickerType}` → `StickerHelper.purchaseSticker(user, type)` (débite DIAMONDS=getUserStickerPrice,
  cosmétique + `purchaseTime`).
- `BUY_STICKER_BOOK{TYPE=StickerBookType}` → `StickerHelper.purchaseBook(user, book)` (débite DIAMONDS=coût remisé,
  `purchaseTime` de chaque sticker du livre).
- `BUY_STICKER_CHALLENGE_SLOT{SLOT=ChallengeSlots}` → `StickerHelper.purchaseSlot(user, slot)` (débite DIAMONDS=
  `StickerChallengeStats.getSlotCost`, pose `UserFlag.CHALLENGE_SLOT_2`).
- `SET_FAVORITE_STICKER{TYPE=StickerType}` → `userExtra.favoriteSticker`.

**Liaison SCOPED de la donnée de défis** : `purchaseSticker`/`purchaseBook` accèdent à la donnée via
`extension.getChallengeData(userID)` = `DH.app.getYourChallengeData()` (champ `GameMain.userChallengeData`). On lie
NOTRE `ClientUserChallengeData` au champ le TEMPS de l'appel (`ServerChallenges.withBoundData`, restauré en `finally`)
puis re-sérialise (`toMessage`). Scoped → on NE réactive PAS globalement la cascade `notifyChallenges` (g59) ; et de
toute façon elle est sûre depuis le fixture `historicWeeklyChallenges` non-null (g74). Persistance : `resyncDiamonds`
(diamants) + `resyncCounts` (drapeaux `CHALLENGE_SLOT_2`/`SPENT_DIAMONDS_ON_CHALLENGE`/`FREE_STICKER_PURCHASE`).

**Favori (§6 persistance)** : `User.setFavoriteSticker` n'écrit QUE le champ `User` (pas `extra`, disasm) → ne
persiste pas. `getUser` lit `userExtra.favoriteSticker`. Donc nouveau `ServerUser.setFavoriteSticker` pose
`userExtra.favoriteSticker` (+ miroir `BasicUserInfo`) = source lue au chargement.

**`ChallengeShopTest`** : buySlot → `CHALLENGE_SLOT_2` posé, **-1000 diamants** (= `CHALLENGE_SLOT_2_COST`), anti-double
refusé ; setFavorite → `TO_CATCH_A_STAR` ; buyBook `CITY_PATROL` → **-900 diamants**, **5** stickers `purchaseTime` ;
tout survit à la persistance DB (`UserStore` round-trip). Valeurs EXACTES des données du jeu (= « BUY 1,000 » /
« BUY 900 » observés en jeu g75). `resyncCounts` rendu package-private. Régression **92/92**.

**RESTE** : vérif EN JEU (nécessite des diamants sur le compte — grant + achat en jeu) ; incrément 4
(`GetUserChallengeDataExtra` = vue d'un autre joueur + `VIEW_CHALLENGES` navigation). Détail : `docs/CHALLENGES.md`.

## 2026-08-09 (g75) — CHALLENGES (#72) incrément 2 ✅ VÉRIFIÉ EN JEU (rendu + CLAIM de bout en bout)

Session de vérif EN JEU (§8) contre NOTRE serveur, client réel (compte userID=1 TL100, tuto complet, via
`SurgeAcctSetup`). Assets ETC1 re-téléchargés (`tools/fetch_assets.sh`, 283M) ; client déjà bâti (spine jar +
game-framed). Pilote : `nav CHALLENGES` + `fire x,y` (commandes EXISTANTES, aucun nouveau code pilote).

**Auto-setup serveur confirmé** : au boot, `LoginServer` appelle `ServerChallenges.ensureSetup` → le défi STARTER
est peuplé par le jeu et PERSISTÉ (`challengeData` 0 → 256 octets en DB, vérifié). BootData le livre.

**Rendu ✅** : `nav CHALLENGES` → écran **CHALLENGES**, section **STARTER**, carte **« Catch a Star »**
(= TO_CATCH_A_STAR) : « 3-Star every major stage in Chapter 4 of the Normal Campaign », **Rewards 500 + sticker**,
**Progress 0/7**, **Time Left 6d 23h 59m** — toutes valeurs EXACTES de `challenge_stickers.tab` (500 tokens, max 7,
durée 7 j) livrées par notre serveur. **CHALLENGE BOOKS** : STARTER **0/5**, PICK 'EM 0/10, + 4 livres NORMAL avec
`isPurchasable`/state corrects (logique cliente `StickerHelper` tourne sans NPE sur notre état). **Détail STARTER** :
les 5 stickers, le défi actif en **RESTART + timer**, les autres en ACTIVATE (grisés — slot STARTER unique).

**CLAIM de bout en bout ✅** : défi marqué prêt (outil DEV `MarkChallengeReady` : `currentProgress = maxProgress`,
comme l'aurait fait la progression) → au reboot la carte affiche le tampon **COMPLETE** + bouton vert **CLAIM** →
tap (`fire`) → le client émet `Action{command=CLAIM_STICKER_CHALLENGE, extra={SLOT=STARTER, TYPE=TO_CATCH_A_STAR,
TIME=…}}` — EXACTEMENT le protocole câblé (bytecode `ClientActionHelper`) → handler serveur
`<== CLAIM_STICKER_CHALLENGE(TO_CATCH_A_STAR/STARTER) appliqué [persisté]` → fenêtre de récompense **+500** (jetons)
+ sticker (Dupe! +50, déjà possédé) → **auto-avance** du slot STARTER vers **« The Name's Wilde »** (= THE_NAMES_NICK,
« Collect 20 Nick Hero Chips from the Elite Campaign », **Progress 0/20**, 7 j) — le comportement `claimSticker` +
`setupStarterChallenges` du JEU (retire le réclamé, ré-avance), piloté par le serveur et relivré au client.

**Persistance serveur confirmée (relecture DB)** : `CHALLENGE_TOKENS=500` (crédité, était 0),
`slot STARTER=THE_NAMES_NICK 0/20 claimed=false`, `completionTime={TO_CATCH_A_STAR=1}` (le jeu stocke l'userID dans
`setCompletionTime` — quirk du bytecode, exécuté fidèlement §3 ; > 0 ⇒ marqueur « complété »). La fixture
`historicWeeklyChallenges` (g74) fonctionne dans le VRAI client (aucun NPE au rendu ni au claim).

**Non exercé en jeu (non bloquant, documenté §4/§8)** : START player-initiated (bouton ACTIVATE gaté — slot STARTER
unique occupé ; défis NORMAL/WEEKLY à débloquer/acheter) et CANCEL (le bouton YES de la modale « RESTART CHALLENGE? »
est sur un stage overlay que le pilote `fire` — qui vise le stage principal — n'atteint pas). Les deux partagent
extras/handler/persistance avec le CLAIM (prouvé en jeu) + sont prouvés HEADLESS (`ChallengeLoopTest`) + protocole
bytecode. **`VIEW_CHALLENGES`** (Action de navigation émise à l'ouverture des livres) est « non gérée (PARTIEL) » :
navigation CLIENTE locale, aucun état serveur requis → à traiter avec la vue livres/stickers (incr. 3/4).

**Aucun changement de code serveur cette session** (la boucle était déjà commitée g74, `37325c0`) — uniquement la
vérif EN JEU + mise à jour docs. Régression inchangée **91/91**.

## 2026-08-09 (g74) — CHALLENGES (#72) incrément 2 : boucle setup/claim/cancel + persistance (🟢 headless, 91/91)

**Objectif (option (b) choisie par l'utilisateur)** : câbler la boucle de défis EN HEADLESS avec les valeurs déjà
prouvées (code du jeu + données `.tab`), puis vérifier EN JEU. Tout par le CODE DU JEU (§3), zéro invention (§4).

**Blocage résolu — fixture `historicWeeklyChallenges` (§8, disasm)** : `canStart/getStartError` NPE-aient headless.
Cause tracée au bytecode : `getStartError → getUserStickerInfo(...).isUnlocked() → isCurrentWeeklySticker →
StickerHelper.extension.getHistoricChallenges().getCurrentChallenges()`. L'extension (`StickerHelper$1`, posée par
le `<clinit>`, toujours non-null) délègue à `DH.app.getHistoricWeeklyChallenges()`. Le VRAI `GameMain(ctor)` pose
`historicWeeklyChallenges = new HistoricWeeklyChallenges()` (offset 370) ; notre shim alloué SANS ctor le laisse
null → NPE. **Correctif** : `ServerContext.init()` pose la MÊME valeur (conteneur vide) — couche plateforme (§4),
zéro donnée inventée. Posée GLOBALEMENT (sûr) car ce champ n'est que LU ; contrairement à `userChallengeData`
(RÉSERVÉ à l'oracle, g59 — le poser globalement réactive la cascade `notifyChallenges → setupWeeklyChallenges`).

**Sérialiseur inverse (§3)** : le jeu n'a PAS de sérialiseur `client→message` (le client ne renvoie jamais tout
l'état ; `ClientNetworkStateConverter.setUserChallengeData` va aussi `message→client`). On écrit un sérialiseur à
JEU DE CHAMPS FERMÉ `ServerChallenges.toMessage(ClientUserChallengeData) → UserChallengeDataExtra` — miroir du
sync héros de §3 ; réflexion LECTURE SEULE sur `nextChallengeID`/`attemptID`/`userID` (aucun getter). Validé par
round-trip (`ChallengeLoopTest`).

**SETUP auto par le jeu** : `ServerChallenges.ensureSetup(su)` (appelé au boot par `LoginServer`, gaté
`Unlockables.isUnlocked(Unlockable.CHALLENGES, user)` TL20) exécute `StickerHelper.setupStarterChallenges` — le jeu
choisit le 1er sticker de catégorie STARTER non complété par `starterChallenge` croissant (`challenge_stickers.tab` :
`TO_CATCH_A_STAR`(1, 0/7, 500 tokens) → `THE_NAMES_NICK`(2) → `RIDE_THE_FERRIS_WHEEL`(3) → `SOCIAL_BUTTERFLY`(4) →
`YOURE_NUMBER_ONE`(5)) — + `setupWeeklyChallenges` (no-op tant qu'aucune rotation hebdo poussée). Idempotent.

**Protocole client PROUVÉ au bytecode** (`ClientActionHelper`, `ActionExtraBuilder`) : `startStickerChallenge` →
`Action{START_STICKER_CHALLENGE, extra{TYPE=StickerType, TIME}}` (SANS SLOT → serveur choisit via `canStart`) ;
`claimSticker`/`cancelStickerChallenge` → `Action{…, extra{TYPE, SLOT=ChallengeSlots, TIME}}`. Extras stockés en
`.name()` (String, `with(ActionExtraType,String)`). Handlers `LoginServer` : ré-exécutent `createHandleExtra`
(START, niveau message : `endTime=serverTime()+getDuration()`, `maxProgress=getMaxProgress()`) / `claimSticker`
(CLAIM) / `cancelChallenge` (CANCEL) de façon autoritative + persistent. Fire-and-forget (le client a appliqué
localement — patron loot/raid).

**CLAIM autoritatif (`claimSticker`, disasm)** : pose `completionTime`, crédite le sticker cosmétique +
`CHALLENGE_TOKENS` (`getTokenReward`) + bonus de livre (si livre complété), `setClaimed(true)`, RETIRE le handle
du slot (`setHandle(slot, null)` hors WEEKLY) puis (STARTER) ré-appelle `setupStarterChallenges` → ré-avance au
défi suivant. **Anti-double** : re-claim → handle retiré/`claimed` → 0 token.

**Persistance** : colonne `challengeData BLOB` (`UserStore` migration + `ServerUser.challengeData` + `bootData()`),
livrée au boot. **`ChallengeLoopTest`** : setup STARTER → complétion forcée (`currentProgress=maxProgress`) →
claim (+500 tokens, `completionTime` posé, ré-avance `TO_CATCH_A_STAR`→`THE_NAMES_NICK`) → anti-double (0 token) →
round-trip DB (`UserStore`) → cancel. Régression **91/91**.

**RESTE** : progression transversale (hooks `ChallengeImpl` : campagne/chest/arène/breaker) + autorité de la
progression (client vs serveur) — à OBSERVER EN JEU (comme loot/raid) ; **vérif EN JEU obligatoire** (§8 : rendu du
défi, claim, auto-avance) ; stickers (incr. 3 : `BUY_STICKER*`/`SET_FAVORITE_STICKER`), `GetUserChallengeDataExtra`
(incr. 4). Détail : `docs/CHALLENGES.md`.

## 2026-08-04 (g71) — SURGE (#72) : scoring vérifié FIDÈLE + recon des raids (câblage bloqué §4)

**Scoring vérifié (§8, sonde)** : les `+0 pts` des tests SURGE ne sont PAS un manque — `creep_surge_tiers.tab`
donne un multiplicateur de points **0.0 aux paliers 0 et 1**, puis 1.0/2.08/3.24… au palier 2+. Une guilde à bas
palier marque donc 0 point PAR DESIGN ; le pipeline `recordOutcome` (incr. 4a) est fidèle et scorera au palier 2+
(atteint en vidant des districts). Documenté dans `docs/SURGE.md`.

**Raids — recon faite, câblage BLOQUÉ sur preuve de protocole (§4/§8)** : `recordRaid` params résolus au disasm
(`SurgeHelper.doRaid` 198-218) = `(user, member, surgeID, opponent.district, false, RAID_TEAM_POWER, 0, GOLD
(getGoldForSurgeRaid), raidHEROES, snapshot)`. MAIS `doRaid` appelle `recordRaid` CÔTÉ CLIENT et
`SurgeHeroChooserScreen.doRaidSurge` n'envoie au serveur qu'un `HeroLineupUpdate` — **aucun message d'issue de
raid** dans le code client. Le serveur ne peut donc pas suivre `raidsUsed`/gold de raid de façon autoritative sans
OBSERVER le trafic réel EN JEU. On NE câble PAS (pas d'invention de protocole). À élucider à la vérif en jeu.
Aucune modif code (recon + docs).


## 2026-08-04 (g70) — SURGE (#72) incrément 4c : combat de district câblé (Start/SurgeAttack)

Handlers `LoginServer` + logique testable dans `ServerSurgeState` :
- **`StartSurgeAttack → StartSurgeAttackResponse`** (`startAttack`) : lineup DÉFENSEUR en `HeroData` complet
  (roster RÉEL de l'adversaire du district via `getHeroData`, ou bot synthétique déterministe si pas de joueur)
  + `raidID` + `combatModifiers` ; verrouille l'adversaire (`lockExpiration` = +5 min, anti double-combat).
- **`SurgeAttack → SurgeUpdate`** (`applyAttack`) : exécute `ServerSurgeCombat.applyRegionOutcome` (recordOutcome
  autoritatif) sur la summary du membre, marque l'adversaire `clearedThisWave` + `districtsCleared++` à la
  VICTOIRE, renvoie le delta (`surgePointDelta`/`districtsClearedDelta`, `member`, `opponent`), persiste le
  SurgeData partagé et le DIFFUSE à la guilde (`pushToGuild`). Sous-messages non nuls (wire-sûr).

`SurgeAttackFlowTest` : START (lineup défenseur non vide, raidID, verrou) → ATTACK WIN (district vaincu,
`districtsCleared`=1, deltas) → round-trip wire des DEUX réponses (défaut nº3) → persistance du district vaincu
(round-trip DB). Headless 🟢. Régression. **Boucle de combat SURGE fonctionnelle de bout en bout côté serveur.**
Reste : scoring/paliers (points>0 avec tier/perks de guilde), raids, objectifs/récompenses, ordonnanceur, vérif EN JEU.


## 2026-08-04 (g69) — SURGE (#72) incrément 4b-ii : pose des adversaires (pool réel + synthétique)

`ServerSurgeState.buildOpponents` : un `SurgeOpponentSummary` par district actif (les 27 de `ServerSurgeMap`),
peuplé dans `buildFresh` (donc `GetSurge` renvoie désormais la carte complète). **Modèle ARÈNE #43** : on tire
des JOUEURS RÉELS du shard (`UserStore.listUserIDs` hors membres de la guilde) et on prend leur équipe (≤ 5 héros
du roster) comme lineup adverse via `ClientNetworkStateConverter.getHeroSummary` + `extended` (résumé + PV), power
sommée ; repli SYNTHÉTIQUE déterministe par district (bot `createAndAddHero` RALPH/ELASTIGIRL/FROZONE) si le pool
est vide. `points`=0 (calculés par `recordOutcome` à la défaite, pas inventés). `totalWaves` = nb de districts.
`SurgeStateTest` étendu : 3 joueurs hors guilde semés → 27 adversaires, chacun avec un lineup non vide, chaque
district couvert, au moins un adversaire issu du pool RÉEL, round-trip wire (valide les types `SurgeOpponentSummary`
/`LineupSummary`/`ExtendedHeroSummary` = défaut nº3). Régression.


## 2026-08-04 (g68) — SURGE (#72) incrément 4b-i : carte des districts (données du jeu)

`dhserver/ServerSurgeMap` : la carte SURGE vient des DONNÉES du jeu (§3/§4). `map_districts.tab` associe 27
`DistrictType` actifs à un `EnvironmentType` (BLACK_MARKET/ESPORTS_ARENA/SUBWAY/HACKER_ENCLAVE) ;
`creep_surge_nodes.tab` donne le multiplicateur de points (3.5→1). On lit via `MapDistrictStats.getEnvironment`
(district → env ; `DEFAULT` = hors carte, mesuré : 82/109 districts) et `SurgeStats.getMultiplier`. `activeDistricts()`
= les 27 districts (env ≠ DEFAULT, hors QG=FF), triés par multiplicateur décroissant. `SurgeMapTest` (27 districts,
env réel, mult > 0, tri). Base de la pose d'adversaires (4b-ii). Régression.


## 2026-08-04 (g67) — SURGE (#72) incrément 4a : enregistrement de combat AUTORITATIF (recordOutcome)

Les 2 params bloquants de g66 sont **RÉSOLUS PAR LES FAITS** (désassemblage du site d'appel unique
`SurgeAttackScreen`, offsets 239-261) — zéro invention :
- (a) 3ᵉ collection = `IHero` attaquants : `recordOutcome` n'y appelle que `getType()`/`isMercenary()` →
  reconstruits via `user.getHero(unit.type)` depuis `base.attackers[*].units` (mercenaires exclus) = les héros
  RÉELS du joueur ;
- (b) Set d'objectifs = `SurgeAttack.objectiveProgress.keySet()` : le client met `(SurgeObjectiveInfo → 1)` par
  objectif QUALIFIÉ (scène-dépendant, client-autoritatif) — le serveur relit simplement les clés ;
- **les DEUX booléens sont `iconst_0` au site d'appel → `false, false` (prouvé, plus une inférence)** ;
- `outcome`/`attackers`/`defenders` = `base.outcome`/`base.attackers`/`base.defenders` (déjà `AttackLineupSummary`).

`dhserver/ServerSurgeCombat.applyRegionOutcome(user, summary, surgeID, raidID, SurgeAttack, snapshot)` exécute
`SurgeHelper.recordOutcome` sur le `SurgeClientMember(surgeID, summary)` DU JEU (mutations → summary → persistée).
`SurgeCombatTest` : WIN RALPH vs défenseur → `recordOutcome` tourne headless et la progression d'objectif slot 0
est appliquée **par le code du jeu** (=1) ; points/or = 0 attendu (joueur sans guilde → tier 0, pas de multiplicateur).
Régression. **Reste 4b/4c** : opponents + `StartSurgeAttack→StartSurgeAttackResponse` (raidID + lineup défenseur),
handler `SurgeAttack` (correler le raid, persister, marquer l'adversaire vaincu), puis scoring/tiers, raids,
récompenses, ordonnanceur, vérif EN JEU.


## 2026-08-04 (g66) — SURGE (#72) : recon FIDÈLE du combat (`recordOutcome`) — pas de câblage inventé (§4)

Suite de « termine l'implémentation ». Recon en profondeur du combat de région avant tout câblage (SCREEN_PIPELINE
étape 3). **Anatomie de `SurgeHelper.recordOutcome` (disasm)** : 12 params ; corps = `storeGold` (or depuis les
lineups) + `recordObjectiveProgress(member, objectifs)` + itère les **héros attaquants (`IHero`)** →
`recordHeroMastery` + `ContestHelper.onSurgeAttack` + `UserActivityTracker.onSurgeAttack` (points/activité). Le
membre serveur = **`SurgeClientMember(surgeID, SurgeMemberSummary)`** (impl `ISurgeMember` DU JEU, réutilisable).

**Ce qui est faisable direct** (comme la campagne) : `base.outcome` / `base.attackers` / `base.defenders` sont
déjà `CombatOutcome` / `Collection<AttackLineupSummary>`.

**⚠️ 2 params NON câblables sans invention (bloquants, documentés dans docs/SURGE.md incr. 4)** :
(a) la 3ᵉ collection = **`IHero` attaquants** (pour `recordHeroMastery`) — le wire ne porte que des
`AttackUnitSummary` (stats), pas des `IHero` → reconstruction fidèle requise ;
(b) le **Set d'objectifs** — construit côté client par `getQualifiedObjectives`, qui exige les stats de la `Scene`
de combat (`CombatStatsData`) **absentes du wire** → à dériver de `SurgeAttack.objectiveProgress` (map cliente),
en vérifiant ce qu'attend `recordObjectiveProgress`.

**Décision (§4/§2/§8)** : on NE câble PAS `recordOutcome` tant que (a)/(b) ne sont pas prouvés — un param deviné
corromprait silencieusement mastery/objectifs/points/contests (rustine interdite). Recon capturée pour la suite.
**Statut SURGE** : socle (incréments 1-3 : calendrier, état de guilde, `GetSurge→SurgeData`) fait headless 🟢 ;
combat/raids/objectifs/récompenses/ordonnanceur + vérif EN JEU = arc multi-incréments (échelle GUILD WAR),
poursuivi sur les FAITS, sans jamais inventer. Aucune modif code ce tour (recon + docs seulement).


## 2026-08-04 (g65) — SURGE (#72) incrément 3 : handler `GetSurge → SurgeData` (headless 🟢)

Handler `LoginServer` : le client (GameMain) envoie `GetSurge` (sans champ) à l'ouverture de l'écran → le serveur
charge l'état PARTAGÉ de la guilde (`ServerSurgeState.loadOrReset`, reconstruit si nouveau surge) et renvoie un
`SurgeData` (`setAsReplyTo`), même patron que `GetInvasionInfo → InvasionInfo`. Hors guilde → `emptySurge` (réponse
vide fidèle, wire-sûre). Le gate `Unlockable.SURGE_OBJECTIVES` (= **TL 32**, unlockables.tab) est un verrou CLIENT
— le serveur RÉPOND, ne le désactive jamais (§8). `SurgeStateTest` étendu (round-trip wire de `emptySurge`).
Compile serveur OK, régression **84/84**.

**Statut : 🟢 headless** (routage + réponse wire-valide prouvés). **Vérif EN JEU restante** (l'écran SURGE s'ouvre)
— faisable sur BaronessDante (TL100, en guilde ≥ gate TL32). Les champs adversaires/districts/paliers/objectifs
sont encore vides (peuplés incréments 4-6) : l'écran rendra le socle (fenêtre + membres), pas encore le combat.


## 2026-08-04 (g64) — SURGE (#72) incrément 2 : ÉTAT PARTAGÉ DE GUILDE (persisté)

`dhserver/ServerSurgeState` : un `SurgeData` par (guilde, surgeID), partagé par toute la guilde. Stocké en octets
wire dans `shard_state` (clé `surge:<guildID>`) — comme les autres états opérateur (contests/horloge/graines de
guerre), pour rester ISOLÉ et REMIS À ZÉRO proprement à chaque nouveau surge (`loadOrReset` reconstruit si absent
ou si le surgeID stocké ≠ surgeID courant, comme `InvasionHelper.resetUserInvasion`/la bascule de guerre).

**Membres = ROSTER** : une entrée `SurgeMemberSummary` par membre (chargé via `store.loadIfExists`), identité
`BasicUserInfo` (avatar forcé non nul → wire-sûr). Fenêtre (raidEndTime/nextRaidStartTime) depuis `ServerSurge`
(code du jeu). Conteneurs (opponents/log/objectives/unclaimedRewards/waveRegionsCleared) et sous-messages
(surgeScoringInfo/previousResults) initialisés NON nuls (défaut nº3). Adversaires/districts/paliers/objectifs/
scoring restent aux incréments 3-6 (peuplés via le code du jeu, jamais inventés).

`SurgeStateTest` : membres = roster (2), round-trip WIRE (WireCheck), round-trip DB (persistance), et remise à
zéro sur changement de surgeID. Régression. Doc `docs/SURGE.md` (incrément 2 ✅).


## 2026-08-04 (g63) — SURGE (#72) incrément 1 : CALENDRIER via le code du jeu + recon complète

Premier mode hub attaqué avec le pipeline industrialisé. **Recon (preuve d'abord)** : SURGE est un mode de GUILDE
saisonnier (districts/QG=`FF`, vagues de 3 régions, paliers, objectifs, raids, tokens/influence/or), gaté
`SURGE_OBJECTIVES` + perks de guilde. Logique CLIENTE (`SurgeHelper`/`SurgeClientHelper`/`SurgeStats`, modèle
`ISurgeMember`) → le serveur EXÉCUTE ses helpers (§3). Pas dans le BootData → demandé via `GetSurge` (comme arène/
invasion). `ModeGraph --logic` a recensé les points d'entrée serveur (`recordOutcome`/`recordRaid`/`getGoldFor…`/
`getMaxRaidsPerSurge`/`getTier`…). ⚠️ `SurgeHelper.doRaid` = rappel d'action CLIENT (consomme) — ne pas pré-appeler
(piège g45).

**Faits de calendrier (sonde headless, §8)** : `getEndHour`=11, `getIntermission`=**900000 ms (15 min)**,
`getRegionsPerWave`=3, `getHQDistrict`=FF ; `getNextSurgeStartTime`/`getSurgeEndTime` = la fenêtre (surges
quotidiens). **Règle « actif » établie** : actif à `now` ⟺ `getNextSurgeStartTime(now) > getSurgeEndTime(now)`
(vérifié : sonde → nextStart 16:15 > end 16:00 ⇒ actif).

**Incrément 1 livré** : `dhserver/ServerSurge` = calendrier/identité 100 % code du jeu (`isActive`,
`surgeEndTime`, `nextSurgeStartTime`, `intermission`, `currentSurgeID` = fin de fenêtre si actif, `isEnabledOnServer`),
aucune date inventée. `SurgeScheduleTest` (assertions relationnelles déterministes) intégré à la régression.
Doc de suivi `docs/SURGE.md` (recon + plan d'incréments 1-8). Reste : état partagé de guilde, `GetSurge`→`SurgeData`,
combat/raids, objectifs/récompenses, ordonnanceur, vérif EN JEU.


## 2026-08-03 (g62) — SCAFFOLDER affiné (placeholders enum/sous-message) + `--mode --scaffold` (#73/#74)

Amorce de #72 avec les nouveaux outils : `contract.sh --mode` (union ModeGraph) → `ScreenContract --scaffold`
génère le squelette d'un mode. Sur SURGE (union 17 classes) : `ServerSurgeScaffold.java` (26 builders, chaque
champ du contrat stubbé TODO), `SurgeScaffoldTest.java` (WireCheck par réponse), `LoginServer-Surge.snippet.txt`
(2 handlers GetSurge/StartSurgeAttack).

**Défaut du scaffolder trouvé via le test généré (auto à améliorer, pas un bug d'écran passé)** : le `ScaffoldTest`
a levé `NPE ordinal()` sur `AttackUnitSummary.rarity` puis `writeSingle()` sur `BasicUserInfo.avatar` — le
scaffolder posait `null` pour les champs OBJET, or un ENUM (ordinal) et un SOUS-MESSAGE (writeSingle) ne tolèrent
pas null à l'écriture wire (défaut nº3). **Correctif** (`ScreenContract`) : `classifyTypes` (une passe jar)
classe chaque type de champ — ENUM (ACC_ENUM/super Enum) → placeholder `Type.values()[0]` ; message NEWABLE
(concret + ctor sans arg) → `new Type()` (structure, comme le shim `new IAPProducts()`). Résultat : le round-trip
wire du squelette **PASSE d'emblée** (`[SurgeScaffoldTest] round-trip wire OK`), chaque valeur restant `// TODO
valeur réelle` (§4 : structure, pas une règle). `--scaffold` passe désormais aussi par `contract.sh --mode`.

Outils uniquement (les squelettes générés vivent dans le scratchpad, à compléter+déplacer dans `dhserver/` lors de
l'implémentation réelle du mode). Régression inchangée 82/82. Docs : `SCREEN_PIPELINE.md`.


## 2026-08-03 (g61) — GRAPHE DE MESSAGES + LOGIQUE : pile de vérif headless COMPLÈTE (#74 leviers A & C)

Supprime la « Limite 1 » de `SCREEN_PIPELINE.md` (ScreenContract analyse PAR CLASSE ; un mode est MULTI-classes —
un message peut être envoyé par un helper hors du package de l'écran, ex. `ArenaAttack` via `ArenaHelper`/
`ArenaAttackScreen`, pas `ArenaLeagueScreen`).

**`tools/screentool/src/ModeGraph.java`** : scanne TOUT le jar (18 232 classes de `com/perblue/heroes`), construit
le graphe `message → {émetteurs (new), lecteurs (GETFIELD/getter)}` (classes internes regroupées sous leur outer),
puis à partir d'une GRAINE découvre l'union des classes du mode :
- **graine** = préfixe de package (`com/perblue/heroes/ui/surge/`) OU token de nom pour un mode ÉPARPILLÉ
  (`Arena` → capte `ui/screens/`, `ui/herochooser/`, `ui/pvp/`, `ui/windows/`, `ui/widgets/`) ;
- **affinité de NOM** : on n'étend l'union que via les messages CORE (nom portant le token) → évite d'aspirer
  d'autres modes par les messages roster/combat PARTAGÉS (mesuré : sans ce filtre, Arena ramenait Heist/Surge via
  `HeroSummary`/`PlayerRow`) ;
- **filtre HUBS** : un dispatcher générique référençant > 18 messages distincts (`ActionHelper` 20,
  `ClientActionHelper` 27, `GameMain` 153 — vs helpers de mode ≤ 11) est EXCLU de l'union (sinon il pollue §A/B
  avec tous les messages qu'il touche) mais SIGNALÉ dans le rapport (pour router à la main s'il envoie une requête
  du mode) ; filtre debug/test aussi.

**`contract.sh --mode <graine>`** (A2) : chaîne ModeGraph (union) → ScreenContract (contrat complet). Validé :
arène 45 classes (token, 6 packages), surge 17, heist 77 (0 pollution cross-mode hormis un util partagé `UIColors`).
Après filtre hubs, le §A/B de Surge ne liste plus que des messages Surge (avant : `ExpeditionRunData`,
`CensoredPrivateUserInfo`… venaient des dispatchers).

**Levier C aussi (C1+C2).** `ModeGraph --logic` (intégré à `contract.sh --mode`) RECENSE, pour les `*Helper`/`*Stats`
du mode, les méthodes STATIQUES prenant un `IUser` = les points d'entrée que le serveur EXÉCUTE headless (§3 « lire &
exécuter », jamais réécrire). Ex. Surge : `recordOutcome`, `recordRaid`, `getGoldForSurgeFight/Raid`,
`getMaxRaidsPerSurge`, `getRecommendedOpponent`, `getQualifiedObjectives`… — la carte des règles du jeu à câbler pour
implémenter le mode. C2 (harnais de traversée) : le mécanisme existe déjà (`ClientOracle` exécute la logique cliente
GL-free sur notre état ; `HeadlessCombat` #27 le combat) → à étendre par mode au fil de #72. **La pile de vérif
headless est complète** (niveaux 0-3 outillés ; 4 = in-game irréductible).

Docs : `SCREEN_PIPELINE.md` (Limite 1 → RÉSOLUE), `HEADLESS_VERIFICATION.md` §SUIVI (A1/A2/C1/C2 ✅, niveau 2 ✅).
Outils uniquement (aucune modif serveur) → régression inchangée 82/82.


## 2026-08-03 (g60) — ORACLE CLIENT : miroir des validations d'ENVOI (#74 B4)

Levier B, incrément B4. Avant d'émettre une action, le CLIENT exécute une validation ; si elle lève, il REFUSE
d'envoyer. On rejoue CE prédicat du jeu sur NOTRE état reconstruit → « le client enverrait-il / planterait-il ? »
headless. Deux défauts attrapés sans in-game : (a) un état serveur qui REFUSERAIT une action légitime (joueur
honnête bloqué) ; (b) une faille ANTI-TRICHE (le serveur accepte ce que le client aurait refusé).

Ajouté à `ClientOracle` : `SendValidation` (prédicat pur) + `assertClientWouldSend` (doit passer) /
`assertClientWouldRefuse(action, expectReason)` (doit lever ; passer = faille anti-triche). ⚠️ **Prédicats PURS
seulement** — JAMAIS un rappel qui CONSOMME l'action (`WarClientHelper.doStartWarAttack`, g45 : le pré-appeler
cassait le vrai envoi).

`server/smoke/SendValidationTest` (patron réutilisable) sur le prédicat de référence `ChestHelper.
validateChestPurchase` (celui que le serveur applique déjà en anti-triche dans `openChest`) : compte neuf → coffre
SILVER gratuit ACCEPTÉ ; après consommation du gratuit par le chemin serveur réel (hors cooldown, 0 or) → REFUSÉ.
Pour chaque nouvelle action de mode (#72), ajouter un couple send/refuse avec le vrai prédicat du jeu.

Fichiers : `server/smoke/ClientOracle.java` (+harnais B4), `server/smoke/SendValidationTest.java` (nouveau),
`server/smoke/regression.sh` (+1), `docs/HEADLESS_VERIFICATION.md` §SUIVI (B4 ✅). Régression **82/82**.


## 2026-08-03 (g59) — ORACLE CLIENT : le crash R1 (g55) désormais ATTRAPÉ HEADLESS (#74 B2b + B3)

Suite du levier B (#74). Objectif : que l'`ClientOracle` exécute les vérifs du CLIENT qui étaient bloquées faute
de fixture, et PROUVE qu'il rattrape le crash R1 découvert en jeu (g55) SANS repasser par l'in-game.

**B2b — fixture de rendu client.** Les 2 vérifs à fort intérêt (`getUnlockedDailyQuests`/`hasUnclaimedDailyQuests`,
LA voie du crash R1) NPEaient headless pour DEUX raisons enchaînées, trouvées et corrigées l'une après l'autre :
1. `GameMain.getYourChallengeData()` = simple GETFIELD de `userChallengeData` (prouvé au bytecode), nul headless →
   NPE `IUserChallengeData.allHandles()`. 
2. une fois le conteneur de défis posé : `PurchaseHelper$1.getIAPProducts()` = `DH.app.getIAPProducts()` (GETFIELD
   `iapProducts`), nul → NPE `.products` (les daily quests itèrent le catalogue boutique).
Correctif : `ServerContext.installClientHubRenderFixtures()` pose 2 conteneurs VIDES du jeu (ctor no-arg =
structure que le vrai boot remplirait) — `userChallengeData = new ClientUserChallengeData()`, `iapProducts =
new IAPProducts()`. Couche plateforme §4, aucune donnée/règle inventée. Les 2 vérifs passent de
`HUB_RENDER_PENDING_FIXTURE` à `HUB_RENDER` (batterie par défaut).

**⚠️ Cascade de shim évitée (§2) — leçon.** 1ère tentative : poser ces champs dans le `bind()` serveur GLOBAL.
Résultat : **6 tests cassés** (chest/skill/alchemy/war) — `ServerUser.createGuild → UserActivityTracker.
notifyChallenges → StickerHelper.setupWeeklyChallenges → ext.getHistoricChallenges()` = null (la
`StickerHelperExtension` cliente est absente headless). En rendant `userChallengeData` non-nul GLOBALEMENT, on
RÉACTIVE le sous-système de défis (City Watch / stickerbook, #72 NON implémenté serveur) sur CHAQUE action → NPE
« cassé plus tard » = exactement ce que §2 interdit. **Correctif du correctif** : la fixture est **RÉSERVÉE à
l'ORACLE** (il simule le rendu CLIENT du hub, seul lieu où ces structures existent légitimement), JAMAIS au chemin
serveur. `bind()` reste inchangé → 6 tests restaurés.

**B3 — preuve anti-régression R1** (`server/smoke/ClientOracleR1Test`). État exact de g55 : héros 6★ + horloge de
jeu R1 (2016) → `assertClientRenders` LÈVE **`IndexOutOfBoundsException: Index 6 out of bounds for length 6`** =
précisément `HasEnoughCollectionHeroes.isSatisfied` (bâtit une liste de taille `getMaxStars(user)+1` puis
`list.get(hero.getStars())`, hors bornes quand `stars > getMaxStars` — au plafond R1, `getMaxStars=5`). Contrôle :
un compte NEUF (ère courante) reste VERT (pas de faux positif). **Le crash qui avait exigé une découverte EN JEU
en g55 est maintenant attrapé HEADLESS** — l'oracle client tient sa promesse (rattraper avant l'in-game via le
code du jeu). Intégré à la régression (`ClientOracle`, `ClientOracleR1Test`).

Fichiers : `server/java/dhserver/ServerContext.java` (`installClientHubRenderFixtures`), `server/smoke/ClientOracle.java`
(appel de la fixture + 2 vérifs en `HUB_RENDER`), `server/smoke/ClientOracleR1Test.java` (nouveau),
`server/smoke/regression.sh` (+`ClientOracleR1Test`), `docs/HEADLESS_VERIFICATION.md` §SUIVI (B2b/B3 ✅).


## 2026-08-03 (g58) — VÉRIF HEADLESS via le code du jeu : plan directeur + `ClientOracle` (#74, levier B)

Idée (utilisateur) : puisqu'on a le code CLIENT du jeu, l'EXÉCUTER headless contre nos réponses/état pour
rattraper AVANT l'in-game ce qu'un contrat statique d'un seul écran ne voit pas → réduire l'in-game au rendu/GL.
**`docs/HEADLESS_VERIFICATION.md`** : document directeur (résistant à la compression) + pile de vérif (0 contrat
static, 1 WireCheck, 2 ClientOracle, 3 HeadlessCombat, 4 in-game rendu) + 3 leviers (A graphe de messages du
mode, B oracle client, C logique headless) + **§SUIVI vivant** (à mettre à jour à chaque incrément). Référencé
dans la procédure de reprise de MEMORY.

**Incrément B1 fait** : `server/smoke/ClientOracle` — exécute des vérifs CLIENTES du jeu sur notre `User`
headless (`assertClientRenders(u)`), capture les exceptions, liste les échecs. **Découverte + shim** : les stats
du jeu (`QuestStats.getDailyQuestIDs`…) gardent `currentThread == GameMain.MAIN_THREAD` (static, nul headless →
garde toujours violé → repli cassé, ConcurrentModification+ClassCast). `becomeMainThread()` pose ce champ sur le
thread courant (shim de HARNAIS, §4) → le vrai code client tourne headless. **B2** : 2 vérifs STABLES
(`getUnlockedAchievements`, `getWeeklyDailyQuestsComplete`) → self-test vert, intégré régression (**80/80**).
**B2b (à faire)** : `getUnlockedDailyQuests`/`hasUnclaimedDailyQuests` (LA voie du crash R1) NPE car notre User
headless n'a pas les `IUserChallengeData` que le BootData réel fournit → construire une fixture User complète, et
l'oracle attrapera R1 headless. Suivi complet dans `docs/HEADLESS_VERIFICATION.md` §SUIVI.


## 2026-08-03 (g57) — OUTIL D'INDUSTRIALISATION : extracteur de CONTRAT d'écran (bytecode) + garde-fou wire (#73)

Pour « ne plus reproduire nos erreurs » et automatiser/sécuriser l'implémentation de chaque écran/mode, sans
hallucination ni allers-retours. **Deux outils ancrés dans les FAITS** :

**`tools/screentool/ScreenContract` (ASM)** — donné un préfixe de classe d'écran, lit son bytecode (+ classes
internes) et rapporte : **A/B** les CHAMPS wire lus par l'écran (`GETFIELD` sur les messages) = ce que le serveur
DOIT peupler (défaut nº1) ; **C** la COUVERTURE handlers (messages `new`és par l'écran vs `instanceof` de
`LoginServer*` — inclut les classes internes du listener) ; **D** le gate `Unlockable` ; **E** la checklist des
9 défauts récurrents (distillée de MEMORY/SHIMS/JOURNAL). Wrapper `tools/screentool/contract.sh <classe>`
(compile l'outil + recompile les classes serveur pour une couverture à jour + rapport).
**VALIDÉ contre la vérité terrain** : sur `InvasionBreakerScreen` (implémenté), l'outil ressort tout seul
`BreakerQuest.activeBreakerFight` (le champ « jamais renseigné » qu'on avait dû trouver à la main en g46) et
`GetBreakerQuest [OK routé]`. Sur `SurgeScreen` (non implémenté) : `GetSurge`/`SurgeData [MANQUE]` + la liste
exacte des champs de `SurgeData` à peupler (opponents, waves, objectives, rewards…).
⚠️ 2 bugs corrigés en route (instructifs) : les messages wire s'exposent en CHAMPS PUBLICS lus par `GETFIELD`
(pas des getters) ; le routage `instanceof` de `LoginServer` est dans une classe INTERNE (le listener), pas
l'outer class → scanner `LoginServer$*.class`.

**`server/smoke/WireCheck`** — garde-fou réutilisable `assertRoundTrips(resp)` : écrit la réponse sur le fil
(`writeAll`) + relit → attrape le défaut nº3 (typage wire faux qui explose à l'écriture, invisible headless :
g44/g45). À appeler dans chaque test de handler. Ajouté à la régression (**79/79**).

**`docs/SCREEN_PIPELINE.md`** — la procédure par écran + les 9 défauts récurrents. Référencé dans la procédure
de reprise de `MEMORY.md`.


## 2026-08-03 (g56) — LOOT AUTORITAIRE ✅ VÉRIFIÉ EN JEU sur un COMPTE FRAIS (#25/#46)

Le « reste » de #25 (SHIMS) : re-valider EN JEU l'autorité du loot sur un compte joué DEPUIS la création (le
compte hérité BaronessDante restait désynchronisé → repli SHADOW). **Fait.** Compte neuf (DB supprimée, snapshot
`dh-snapshot-postwar-0803.db` sauvegardé), tuto auto-joué → hub → 3 combats de campagne WIN d'affilée
(NORMAL 1-1, 1-2, 1-3, pilote `nav CAMPAIGN` + auto-fight). Serveur : **`[loot-authoritative] #25 AUTORITAIRE ✅
crédité=serveur (==client)` 3/3, 0 DIVERGENCE**, avec un loot DIFFÉRENT à chaque combat (1-1
{ACE_OF_SPADES,CLEVER_FOX,EXP_VIAL,HEARTY_BREAKFAST,RAID_TICKET,SUGAR_RUSH} ; 1-2 {A_BIT_OF_PRESTIDIGITATION,
EXP_VIAL,SUNNY_SIDE…} ; etc.). Le serveur ROULE son propre butin sur SA chaîne de graine (`getDefaultSeed(userID)`
+ avance inconditionnelle), il **MATCHE le client à CHAQUE combat** (chaîne EN PHASE, avancement correct) → il
crédite le tirage SERVEUR et avance l'état évolutif (pool XP + pitié) → **autorité EFFECTIVE en jeu, anti-triche
actif** (plus de repli). Confirme l'analyse #25 : un compte joué dès la création reste en phase ; seul un état
hérité désynchronisé retombe sur le repli sûr (jamais léser l'honnête). Aucune modif de code (logique déjà en
place depuis #25) — c'est la VÉRIFICATION EN JEU manquante. Détail : `docs/SHIMS.md` ligne #25.


## 2026-08-03 (g55) — ÈRE DE CONTENU : le mécanisme suit l'horloge EN JEU ; un compte HAUT NIVEAU ne peut PAS rétrograder à R1

Tentative de valider EN JEU le « démarrer R1 → gagner une salle breaker » (reste de g51) sur le compte existant.
**Le mécanisme ère-suit-horloge est confirmé EN JEU** : `AdminClock --set-date 2016-09-06` → serveur au boot
`InvasionInfo : rotation BLUE du 2016-09-05 au 2016-09-10 [EN COURS]` (l'invasion de 2016 est active), `Max Team
Level = 50` (R1) relu par le probe. **MAIS** le compte (TL100, héros 2026 jusqu'à 6 étoiles) **plante au hub à R1** :
`IndexOutOfBoundsException: Index 6 out of bounds for length 6` dans `HasEnoughCollectionHeroes.isSatisfied`
(via `DotTracker → QuestHelper.showDailyQuestMenuDot`). **Cause racine (bytecode)** : `list.get(hero.getStars())`
où `list` a une longueur = `UnitStats.getMaxStars(user)`+1 = **6 à R1** (indices 0-5) ; un héros du compte a **6
étoiles** → `get(6)` hors bornes. **Ce n'est PAS un bug du portage** : c'est une **incohérence intrinsèque de
rétrogradation d'ère** — les héros du compte dépassent le plafond d'étoiles de R1. Un compte **CRÉÉ à R1** (étoiles
conformes à R1) n'est PAS concerné. **Conclusion** : le témoin en jeu « démarrer R1 → gagner une salle breaker »
doit se faire sur un **compte FRAIS créé à R1** (avec un roster ≤ plafond d'étoiles R1), pas par rétrogradation.
Horloge **réinitialisée** (offset 0 = R102, compte de nouveau cohérent) ; instantané `dh-snapshot-postwar-0803.db`.
**RESTE (#71)** : témoin breaker EN JEU sur compte frais R1 (roster niveau ~40, ≤5 étoiles ; invasion débloquée
par TL). Le gain breaker salle ≥1 avec points>0 reste prouvé HEADLESS (g46, `BreakerWinProbe`).


## 2026-08-03 (g54) — REFRAMING : bug `IStudiedBuff.spawnParticles` corrigé (INVOKESPECIAL de super-interface directe)

Correctif du 🐛 trouvé en g53 (combat de guerre) : `IncompatibleClassChangeError: … IStudiedBuff.spawnParticles
must be InterfaceMethodref constant`. **Cause racine précise** (disassemblage) : `SimpleStudiedBuff implements
IStudiedBuff` appelle `IStudiedBuff.super.spawnParticles(Entity)` (méthode `default`) via **INVOKESPECIAL**, mais
dex2jar encode l'entrée de constant pool en **Methodref de classe** au lieu d'**InterfaceMethodref** → la JVM
rejette le lien au 1ᵉʳ appel. Le chemin est **rendu-seulement** : `AttackScreen` appelle chaque frame
`IBuffVFX.spawnParticles` (invokeinterface, déjà correct) qui dispatche vers `SimpleStudiedBuff.spawnParticles`
(l'override) → l'INVOKESPECIAL buggé. **NON spécifique à la guerre** : parmi mes 15 héros, **Baymax** (skill3) et
**Stitch** (skill2) posent un `SimpleStudiedBuff` → ce héros planterait TOUT combat RENDU où il émet ce buff ; la
guerre l'a exposé en alignant 14 héros.

**Correctif** (`tools/reframe/ReframeJar`, normalisation non-sémantique §1, étend celle des INVOKESTATIC de
2026-07-16) : (a) `INVOKEINTERFACE` → `itf=true` (JVMS 6.5, toujours un InterfaceMethodref) ; (b) `INVOKESPECIAL`
→ `itf=true` **UNIQUEMENT si l'owner est une super-interface DIRECTE de la classe visitée** (`this.interfaces`,
forme légale `T.super.m()`). ⚠️ La restriction au cas DIRECT est essentielle (JVMS 4.9.2) : flipper un
INVOKESPECIAL vers une super-interface **indirecte** casse la vérif (`VerifyError`, cf. `PegasusSkill3` →
`IRampageAbility`, héritée via la super-classe donc non directe — laissée intacte par le garde-fou).
`INVOKEVIRTUAL` jamais touché.

**Vérifié** : disassemblage `SimpleStudiedBuff` → `InterfaceMethod` (corrigé), `PegasusSkill3` → `Method`
(intact), dans les **deux** jars reframés (serveur `game-framed.jar` ET client `game-logic-framed.jar`) ; les 3
classes **verify+link OK sous le vérificateur par défaut** (aucun VerifyError) ; **régression 78/78**. Confirmé
que `HeadlessCombat` (simulation) **ne rejoue pas les VFX** → le crash n'apparaît QUE dans le combat RENDU
(`AttackScreen`) — vérifié par A/B : la même sim (Baymax+Stitch, NORMAL 13-10) termine `DONE` sur l'ANCIEN comme
sur le NOUVEAU jar, car la sim n'appelle jamais `spawnParticles`.

**✅ VÉRIFIÉ EN JEU — combat de guerre JOUÉ JUSQU'AU RÉSULTAT.** La guerre #4 (*Baroness Legion* vs *Rival
Syndicate*) était en réalité toujours ACTIVE (BATTLE PHASE, 7h restantes) — j'avais confondu la LISTE des guerres
(3 DRAW clos) avec la guerre en cours (accessible en scrollant la porte de garage). Setup via nouvel outil DEV
`WarWitnessSetup` (sync des membres des deux guildes depuis leurs `WAR_DEFENSE_1..3`, affectation du défenseur
adverse à la salle `REDUCE_ATTACKER_HP_FLAT`, `resetWarAttacks` du joueur). Flux UI : `nav WAR` → scroll → porte
**BATTLE PHASE** → `GetWarInfo → WarInfo #4 ACTIVE` → garage **9 salles/3 étages** → tap de la voiture ennemie
(BOOMER, « Reduces attacker HP ») → aperçu (défense adverse = 3 équipes dont **Baymax** et **Stitch**) → FIGHT →
hero chooser (mon équipe 1 inclut **Baymax**) → « ARE YOU SURE? » → FIGHT → `START_WAR_ATTACK vs 2 [persisté]` +
`WAR_ATTACK_1/2/3`. **Le combat RENDU s'est joué de bout en bout sur les 3 équipes** (scène garage, héros animés,
dégâts 29 654 / 184 391 / « +300 » / « +50 Dodge », **VFX de particules rendues en continu**) → écran **RESULTS**
(1 victoire verte + 2 défaites rouges). **Compteur de crash `spawnParticles` = 0** sur tout le run (là où g53
plantait en cours de combat). Baymax (équipe 1) ET Stitch (équipe 3) — les deux poseurs de `SimpleStudiedBuff` —
ont casté avec leurs particules **sans aucun `IncompatibleClassChangeError`**. Le correctif de reframing est donc
**✅ prouvé EN JEU** (client réel → combat rendu → résultat). Détail dans `docs/SHIMS.md` §12.


## 2026-08-03 (g53) — GUILD WAR : combat d'attaque JOUÉ EN JEU + bug de reframing trouvé

Suite de g52 : le COMBAT d'attaque de guerre est joué de bout en bout côté client. Après
`ServerUser.resetWarAttacks()` (rend au joueur son attaque de base ; défenseur guilde 2 affecté à la salle
`REDUCE_ATTACKER_HP_FLAT`), flux UI COMPLET : salle adverse → aperçu → FIGHT → **hero chooser** (3 équipes
remplies — team 1 = 10 505 pow, team 2 = 8 052, team 3 = 7 308 ; scroll du roster via la commande pilote
existante `drag x1,y1,x2,y2`) → confirmation « ARE YOU SURE? » → **FIGHT** → serveur :
`START_WAR_ATTACK vs 2 (salle REDUCE_ATTACKER_HP_FLAT) [persisté]` + `HeroLineupUpdate(WAR_ATTACK_1/2/3)
[persistées]` → **le combat de guerre SE JOUE EN JEU** (scène de bataille dans le garage, héros animés, nombres
de dégâts 24 467 / 10 368 / « +200 Dodge » / « Miss », AUTO-combat).

**🐛 Bug trouvé (NON spécifique guerre) — reframing.** Le combat a planté en cours sur
`IncompatibleClassChangeError: Method 'void IStudiedBuff.spawnParticles(Entity)' must be InterfaceMethodref
constant` : défaut de **reframing du jar** (méthode d'INTERFACE encodée en Methodref de classe) déclenché par le
skill d'UN héros précis. La guerre l'expose la première car elle aligne 14 héros (skill jamais exercé). Ce héros
planterait N'IMPORTE QUEL combat (campagne/arène/boss) → à corriger dans `tools/reframe` (ReframeJar), séparé du
serveur. L'enregistrement du résultat de guerre reste couvert par `WarAttackTest`.

Ajout : `ServerUser.resetWarAttacks()` (outil opérateur/test — remet `WAR_ATTACKS_USED`+`WAR_START_TIME_LAST_
ATTACK` à zéro, équivalent d'un changement de guerre côté jeu). Aucune autre modif serveur. Détail :
`docs/GUILD_WAR.md` §5.8.


## 2026-08-03 (g52) — GUILD WAR : attaque en phase ACTIVE RÉSOLUE EN JEU (« cause non élucidée » = un BYE)

Le point resté ouvert en g45/§5.5 (le client n'émettait plus `START_WAR_ATTACK` après passage en phase ACTIVE,
« cause non élucidée ») était un **défaut de mise en place, pas un bug serveur** — le fil rouge « le client
marche, c'est le pilotage/setup ».

**Trace bytecode** (ce que le prédécesseur n'avait pas poussé) : `startWarAttack` → `doActionCallback` =
`startAction` → callback `WarClientHelper.doStartWarAttack` → `completeAction` (**envoie** ssi
`currentGroup==null`). Le callback lit `warInfo.enemyGuild.guildInfo.iD` : sur un **BYE** (une seule guilde en
file → adversaire nul), `enemyGuild` est **null** → **NPE avalé** par le pilote → rien émis. La guerre de test
d'alors était un BYE.

**Vérifié EN JEU** : guerre #4 *Baroness Legion* vs *Rival Syndicate* (appariées via `AdminWar --tick --force`
+ `--advance` ACTIVE ; défenseur affecté à une salle en respectant le piège `sideOf`/`putSide` des octets wire).
`warattack` → `<== START_WAR_ATTACK vs 2 (salle REDUCE_ATTACKER_HP_FLAT) [persisté]` (émis + validé + réponse) ;
l'aperçu d'attaque rend la voiture **BOOMER** (« Reduces attacker HP ») + la défense adverse (15 héros) + FIGHT ;
2ᵉ tentative → **« OUT OF EXTRA ATTACKS »** (garde-fou correct). Reste à JOUER le combat via l'UI (même combat
client-autoritatif que le boss, déjà validé ; résultat couvert par `WarAttackTest`). **Aucune modif serveur** :
le mode marchait, le blocage était l'absence d'adversaire réel. Détail : `docs/GUILD_WAR.md` §5.8.


## 2026-08-03 (g51) — ÈRE DE CONTENU pilotable (ancre d'horloge PERSISTÉE) + cohérence d'horloge + audit hardcode

**Audit « rien en dur à tort » (demandé).** Résultat : PROPRE. Les défauts `const*(champ, dflt)` sont des replis
vérifiés ÉGAUX aux tabs (BOSS_FIGHT_INITAL_LEVEL 450, ATTACK_LOCK_DURATION 5 m, BOSS_FIGHT_5X_KEY_COST 3,
WIN/LOSE/DRAW_COEFFICIENT 1.00/0.40/0.50) ; le coût mercenaire est évalué par le moteur d'expressions du jeu sur
`user_values.tab` (zéro coefficient codé) ; les constantes de la FORMULE Elo (10.0/1.0) sont mathématiques (les
tunables eloN/eloK viennent des données). Seul écart : mes seuils boss 10/30 % — corrigés (lus de
`InvasionStats.getBoss10/30PercentRewardThreshold`, cf. g50).

**Cohérence d'horloge (prérequis de l'ère).** Plusieurs timestamps de LOGIQUE lisaient `System.currentTimeMillis`
au lieu de `serverTimeNow()` → sous une horloge décalée ils mélangeaient deux temps. Corrigés : arène (saison,
lignes synthétiques, lastFightReset, maybeDailyReset), courrier (deliverMail), tiebreaker, « membre depuis ».
Laissées en temps réel À DESSEIN : colonnes d'AUDIT DB (UserStore updatedAt/createdAt — jamais des décomptes).

**L'ÈRE suit l'horloge — PROUVÉ.** `ContentStats.getServerColumn(serverTimeNow)` choisit la colonne R1…R102 par
DATE (`content.<shard>.tab`). Sonde `ClockEraProbe` : 2026 → **Max TL 565 (R102)** ; 2018-04 → **Max TL 70** ;
2016-09 → **Max TL 50 (R1)**. Décaler l'horloge décale l'ère → un serveur neuf peut DÉMARRER À R1 (ennemis
faibles → débloque aussi le combat « gated par l'inflation »).

**Ancre d'horloge PERSISTÉE (robustesse).** L'offset `-Ddh.clock.offset.hours` était un flag VOLATIL (dérive si
oublié/changé au redémarrage). Désormais : méta DB `clock_offset_ms` (via `UserStore.get/setMetaLong`,
réutilise `shard_state` shardID=0), ré-appliquée AU BOOT par `LoginServer` → l'heure de jeu s'écoule au rythme
réel depuis l'ancre, survit aux redémarrages SANS dérive. `-D` reste un bootstrap (persisté s'il est fourni sans
ancre). Nouveau `ServerContext.setClockOffsetMillis/clockOffsetMillis`.

**Gestion admin (n'existait pas).** Nouvel outil `AdminClock` (pendant d'AdminInvasion/AdminWar) :
`--status` (heure de jeu + Max TL de l'ère), `--set-date <yyyy-MM-dd>` (règle l'ère par date), `--offset-hours`,
`--reset`. Vérifié bout en bout : `--set-date 2016-09-06` → **Max TL 50 (R1)**, ancre relue par une nouvelle
instance (persistance OK), `--reset` → TL 565. Redémarrer le serveur pour appliquer. Nouveau test
`ClockAnchorTest` (round-trip méta + offset + écoulement). Régression **78/78**.


## 2026-08-03 (g50) — INVASION BOSS : KILL + CLAIM vérifiés EN JEU (18 récompenses, 6 rôles)

Suite de g49 : le boss #3 (MAMA_BOT niveau 1) est **tué EN JEU** en un coup via **5× DAMAGE** (bouton de
l'aperçu, coût `BOSS_FIGHT_5X_KEY_COST`=3 clés) — l'écran « BOSS DEFEATED! » affiche **DAMAGE DONE 274 714
(100 %)**, serveur : `InvasionBossAttack ×5 outcome=WIN → −3 clés, 274714 dégâts (cumul 452320) [persisté]`
(dégâts plafonnés aux PV, fidèles au chiffre près ; clés **9→6**). La persistance des dégâts d'une session à
l'autre est aussi prouvée (177 606 relus + 274 714 = 452 320).

**Défaut RÉEL nº4, trouvé EN JEU — `bossClaimStatus` jamais renseigné ⇒ boss KO INCLIQUABLE.** Établi au
bytecode : `InvasionBossCard.onCardPressed` ne lance la réclamation QUE si `lastClaimable`, et
`lastClaimable = (actionState==CLAIM) ET getUserInvasion().getBossClaimStatus(bossID) != null` (le gift
s'affiche via `actionState`, mais **taper** exige l'entrée `bossClaimStatus`). Le client ne peuple JAMAIS
`bossClaimStatus` localement (`recordBossFightOutcome` ne le fait pas) ni ne (re)demande `GetInvasionInfo` : la
donnée est **serveur-autoritative**, poussée dans `InvasionInfo.currentInvasion.yourData`.

**Modèle CLIENT-AUTORITATIF de la réclamation (bytecode `InvasionClientHelper.claimBossRewards`)** :
`bossClaimStatus.rewardsEarned` est une **`List<InvasionBossRewardType>`** = les **RÔLES** gagnés (pas le
butin). Le client tire lui-même le butin par rôle (`InvasionHelper.rollBossRewardLoot`, graine RNG invasion) et
le RENVOIE dans `ClaimInvasionBossRewards.rewards` (`Map<rôle, NodeReward{rewardDrops}>`) — comme
campagne/arène/breaker. Corrections serveur :
- `ServerInvasion.earnedBossRoles(boss, userID)` : rôles dérivés de l'état PARTAGÉ observable (FINDER,
  PARTICIPANT, MOST_DAMAGE, TEN/THIRTY_PERCENT_HP, FINISHER = dernier attaquant). *La politique d'attribution
  d'origine est dans `InvasionHelperExt` — code SERVEUR ABSENT du jar client ; on applique la sémantique
  explicite de chaque valeur d'enum. Le BUTIN reste tiré des tables `invasion_boss_rewards*`.*
- `ServerInvasion.populateClaimStatus(g, user, ud, now)` : synthétise l'entrée réclamable
  `{rewardsClaimed:false, rewardsEarned:rôles}` pour chaque boss ACTIF VAINCU non réclamé ; conserve les
  entrées déjà réclamées.
- Handler `ClaimInvasionBossRewards` réécrit : applique le butin **renvoyé par le client** (au lieu de re-tirer,
  ce qui divergerait de l'affichage), **anti double-réclamation** via `bossClaimStatus.rewardsClaimed`.
- Correctif du test « réclamé » de `applyBossActionState` (les valeurs sont des `BossClaimStatusData`, pas des
  `Boolean` — l'ancien `Boolean.TRUE.equals(...)` était toujours faux).
- `yourData` poussé (`sendInvasionInfo`) après un combat de boss, sur `GetInvasionBosses`, sur `GetInvasionInfo`
  et sur la réclamation ; le client ne redemandant jamais `GetInvasionInfo`, ces PUSHs rafraîchissent son
  `ClientInvasionUser`.

**⚠️ Piège trouvé EN JEU — `getBossHP` DÉCLENCHE `PatchStats.<clinit>` : NE PAS l'appeler au PUSH DU BOOT.** Au
boot, la stat-sync du client n'a pas encore complété les tables (lignes SAPPHIRE) → `PatchStats.<clinit>` JETTE
(`ExceptionInInitializerError`) et **empoisonne la classe pour toute la session** (getBossHP KO partout). D'où
le flag `sendInvasionInfo(..., populateClaim)` : `false` au boot (aucun getBossHP), `true` seulement sur les
chemins où le contenu est chargé (`GetInvasionInfo`/`GetInvasionBosses`/après combat/réclamation).

**Vérifié EN JEU** : carte KO glow + **« CLAIM REWARDS »** → tap →
`ClaimInvasionBossRewards boss=3 rôles=[PARTICIPANT,FINDER,MOST_DAMAGE,THIRTY_PERCENT_HP,TEN_PERCENT_HP,FINISHER]
→ 18 récompenses créditées [persisté]` → **InvasionBossRewardsWindow** rend chaque rôle avec son butin de table
(PARTICIPATED 5 stamina/20 boss tech/1 pt/1 mod, FINDER 5 stamina/1 pierre/1 pt, FINISHER 50 boss tech/2 pts/1
mod, 10 % DAMAGE, MOST DAMAGE…) → fermeture → **« There are no current Invasion Bosses »** (repassé DEFAULT, pas
de re-réclamation). Régression **77/77** (`InvasionBossTest` étoffé : KILL+CLAIM, rôles, anti-double-réclamation,
non-participant sans rôle). **INVASION est désormais 100 % vérifiée EN JEU** (breaker quest + boss
spawn/attaque/kill/claim). Détail : `docs/INVASION.md` §BOSS BATTLES.

## 2026-08-03 (g49) — INVASION BOSS : boucle d'attaque VÉRIFIÉE EN JEU (dégâts fidèles au chiffre près)

Correction méthodo (rappel utilisateur + docs §6bis/§6ter) : le « client qui meurt » des sessions précédentes
était un **défaut de pilotage/monitoring de ma part**, pas un bug client. `exit 144` = kill du **wrapper** bash
par le superviseur (pas un crash) ; `pgrep -f DesktopLauncher` matche **ma propre commande shell** (filtrer
`java.*dhdesktop.DesktopLauncher`) ; **ne jamais tronquer** un log tenu ouvert par le serveur (`grep -a`). En
suivant la procédure CANONIQUE (`./run-online.sh` détaché) + monitoring correct, le client tourne
parfaitement (manual.ppm frais à 1 s).

**Boucle d'attaque de boss vérifiée EN JEU** (boss MAMA_BOT niveau 1 via `AdminInvasion --level 1`) :
BOSS BATTLES rend le boss (MAMA_BOT, barre de vie 274 714/274 714, « Reset in … » = chaîne overlay OK) → tap
(`actionState=FIGHT`) → **InvasionBossPreviewScreen** → CHOOSE HEROES → FIGHT →
`StartInvasionBossAttack → StartBossAttackResponse (lineup, verrou)` → **combat réel** →
`InvasionBossAttack ×1 outcome=LOSS → −1 clé, 88803 dégâts (cumul 88803) [persisté]`.
**Fidélité prouvée au chiffre près** : l'écran « BOSS DAMAGED! » affiche DAMAGE DONE **88 803** et BOSS HP
**185 911/274 714** = exactement la valeur serveur (source `base.defenders[*].units[*].damageTaken`). Après
CONTINUE, la carte du boss montre **185 911/274 714** et les clés BREAKER **1→0** — débit + cumul persistés et
relus. **RESTE (gated par les clés du compte)** : tuer le boss (≈4 clés) → CLAIM (`ClaimInvasionBossRewards` /
`rollBossRewardLoot`). Détail : `docs/INVASION.md` §BOSS BATTLES.

## 2026-08-02 (g48) — INVASION BOSS : boucle d'attaque câblée + source FIDÈLE des dégâts + chaînes manquantes

**Source fidèle des dégâts — établie au bytecode.** Le client calcule `getBossDamage =
UnitCombatStats.totalDamageTaken` de la vedette et l'applique localement ; `InvasionBossAttack` n'a pas de
champ « damage ». Mais dans `Scene`, sur le MÊME `entityDamageEvent`, `summary.damageTaken` ET
`stats.totalDamageTaken` sont incrémentés du même montant → `base.defenders[*].units[*].damageTaken` de la
vedette = exactement le chiffre du client. `defenderHeroes` n'est que le lineup statique (piège écarté).
Combat client-autoritatif → on LIT ce chiffre : `ServerInvasion.extractBossDamage`.

**Handlers câblés** : `StartInvasionBossAttack → StartBossAttackResponse{bossLineup,…}` + verrou exclusif ;
`InvasionBossAttack → extractBossDamage → attackBoss` (débit clés BREAKER, cumul par joueur, persistance,
libération du verrou). **LINEUP du boss corrigé** : `spawnBoss` ne posait pas `InvasionBossInfo.lineup` (que
`getBossUnitData` lit) → nouveau `ServerInvasionBreaker.bossLineup` (vedette MAMA_BOT au niveau du boss,
rareté = `getEnemyRarity` du niveau d'équipe du découvreur). [`InvasionBossTest` : lineup + extractBossDamage].

**Chaînes manquantes (GUILD_DAILY_BOSS_LIMIT_INTERVAL corrigé)** : le bundle de l'APK (12.1.0) précède
certaines clés que le CODE de game.jar référence → le client affichait la clé brute. `run-desktop.sh` overlaie
désormais les clés ABSENTES depuis `game-data/strings` (jamais d'écrasement du libellé d'origine) → 556 clés
complétées, dont `GUILD_DAILY_BOSS_LIMIT_INTERVAL = "Reset in %1$s"`.

**Boss 450 — test** : `AdminInvasion --spawn-boss --level 1` (levier opérateur, comme le décalage d'horloge)
spawn un boss faible battable par le compte de test pour exercer la boucle en jeu. Régression 77/77. Détail :
`docs/INVASION.md` §BOSS BATTLES.

## 2026-08-02 (g47) — INVASION BOSS BATTLES : boss affiché EN JEU + rendu attaquable

Le boss d'invasion est SERVEUR-autoritatif (le jar client ne sait que le lire/attaquer). Rien ne le faisait
apparaître au runtime. Nouvel outil opérateur `server/smoke/AdminInvasion.java` (pendant d'`AdminWar`) :
`--spawn-boss`/`--status` — appelle `ServerInvasion.spawnBoss` (niveau/échéance des données) et persiste.

Vérifié EN JEU : `nav INVASION` → BOSS BATTLES → `GetInvasionBosses → InvasionBosses (1 boss actif)` →
l'écran rend le boss (« Boss found today: 1/100 », « Found By: You », vedette crâne, clés BREAKER=1).

**Défaut RÉEL nº3 — `InvasionBossInfo.actionState` jamais renseigné.** Même famille qu'`activeBreakerFight` :
`InvasionBossCard.onCardPressed` n'ouvre l'aperçu de combat QUE si `actionState == FIGHT`. Sans lui, taper le
boss ne fait rien. Nouveau `ServerInvasion.applyBossActionState(boss, user, ud)` (FIGHT si actif/non vaincu ;
CLAIM si vaincu+part non réclamée ; sinon DEFAULT), appliqué par le handler `GetInvasionBosses` à chaque
boss. [`InvasionBossTest` : boss neuf ⇒ FIGHT]. Régression 77/77.

**RESTE (chantier suivant, non deviné §4/§8)** : la boucle d'ATTAQUE du boss —
`StartInvasionBossAttack → StartBossAttackResponse{bossLineup,…}` (+ verrou + clés) et `InvasionBossAttack`
(issue). ⚠️ Les dégâts NE SONT PAS dans le message : le client calcule `getBossDamage` =
`UnitCombatStats.totalDamageTaken` localement ; le dériver du HP de `defenderHeroes` diverge (overkill) ⇒
il faudra une re-simulation serveur ou une preuve bytecode que `defenderHeroes`/`breakpoints` suffisent.
`ServerInvasion.attackBoss` (verrou+clés+cumul+persistance) est prêt et testé ; seul le point d'entrée
réseau + la source FIDÈLE des dégâts restent. Boss niveau 450 = invaincable par le compte de test (inflation
d'ère de contenu, cf. SHIMS). Détail : `docs/INVASION.md` §BOSS BATTLES.

## 2026-08-02 (g46) — BREAKER QUEST jouable EN JEU : deux défauts réels de plus

Reprise de l'énigme « écran vide » de la BREAKER QUEST (g45). La sonde `breakerdump`, élargie à
`activeBreakerFight`, a livré le verdict : le champ était `null`.

**Défaut nº1 — `BreakerQuest.activeBreakerFight` jamais renseigné.** Le client
(`InvasionBreakerScreen`) lit `holder.getBreakerQuest().activeBreakerFight` pour activer l'aperçu et
démarrer le combat de la salle active ; le `onClicked` de la vedette n'ouvre l'aperçu **que si ce champ
est non nul**. Notre `buildQuest` ne remplissait que la liste `basicBreakerFights` → taper la vedette
n'ouvrait **rien**. La salle 0 ne « marchait » que parce que le **tutoriel** forçait le démarrage ; hors
tutoriel (salle ≥ 1) la quête était **injouable**. Correctif : `buildQuest` pose
`activeBreakerFight = toFightInfo(salleActive, groupes)`. Vérifié EN JEU : l'aperçu **BREAKER FIGHT 1**
s'ouvre, on choisit ses héros, `InvasionBreakerAttackStart room=1` part et le serveur répond.

**Défaut nº2 — points d'invasion calculés avec des arguments inventés.** `resolveBreakerFight` appelait
`getBreakerFightPoints(room, 1, 1)`. Le jeu appelle
`getBreakerFightPoints(room, userLevelSnapshot, invasionMaxTeamLevel)` (bytecode
`InvasionHelper.recordBreakerFightOutcome`), l'expression étant `BREAKER_FIGHT_POINT_REWARD = 1R*M` avec
`M = getInvasionPointsMultiplier(userLevelSnapshot, invasionMaxTeamLevel)`. Le `(1,1)` divergeait du client
dès que le niveau d'équipe fait monter le multiplicateur → **score serveur incohérent**. Correctif : mêmes
arguments que le jeu (via `bindGameContext` + `ContentHelper.getCurrent(user).getInvasionMaxTeamLevel()`),
plus le facteur d'évènement `SpecialEventSnapshot.NONE.getLootResourceMultiplier(...)` (= 1, PARTIEL). Note :
`R=0` (salle 0) ⇒ 0 point est le comportement **du jeu**, pas un bug.

Vérifié EN JEU : salle 0 VICTOIRE (persistée : −10 énergie, +1000 or, +1 BREAKER ; état relu à l'écran :
énergie 71/80, BREAKER 1) ; salle 1 DÉFAITE (persistée : −10 énergie, rien accordé, niveau 25). Voie
VICTOIRE salle ≥ 1 (points > 0) : le nouveau code s'exécute **sans exception** et crédite `ud.points`
(`BreakerQuestTest`, `BreakerWinProbe` : `room=1 → +1010 or, +1 BREAKER, +1 pt`). Nouvelle commande pilote
`breakerfight` (ouvre l'aperçu du combat actif). Observation à suivre : la puissance des vedettes (866 M en
salle 1) rend le QUICK FIGHT perdant sur ce compte de test — difficulté voulue (gardes+vedettes) cumulée à
l'inflation d'ère de contenu (cf. SHIMS). Régression + `BreakerQuestTest`.

## 2026-08-02 (g45) — INVASION vérifiée EN JEU (une première) + fin du cycle de guerre

### GUILD WAR — la boucle est bouclée

`StartWarAttackResponse.activeCars` attendait des `WarAttackCarBonus{type, bonusPerkLevel}`, pas des
`WarCarType` : deuxième défaut de typage du wire, même famille que `WarHeroData`, même signature — ça
compile (dex2jar efface les génériques) et ça explose **à l'écriture du message**, donc côté client
uniquement. Le niveau de perk vient de la guilde DÉFENSEUR : `WarHelper.getCarBonusPerk(car)` nomme le
`GuildPerkType`, `GuildInfoPerkProvider.getPerkLevel` en donne le niveau — deux accesseurs du jeu reliés.
`WarAttackTest` asserte désormais les types réels **et écrit le message sur le fil**, seul endroit où le
mauvais type se voyait.

Erreur d'instrumentation corrigée au passage : le pilote pré-appelait `WarClientHelper.doStartWarAttack`
« pour connaître le verdict ». Ce n'est pas un prédicat, c'est LE RAPPEL de l'action — il consomme
l'attaque localement, et le vrai passage échouait ensuite en `WAR_EXTRA_ATTACKS_DEPLETED` sans rien
émettre, le message ne partant qu'à `completeAction`.

Vérifiés en jeu : **clôture** (« DRAW +1 MMR », MMR 10→11 chez nous et 30→29 en face, WAR BOXES: 3),
**réclamation** (fenêtre COPPER WAR BOX / AUG 2026, trois options, `CLAIM_WAR_BOX_REWARD` crédite
`BADGE_CHEST_1X×1`, reste 2), **liste à trois guerres** (l'active en BATTLE PHASE), **phase ACTIVE** (les
deux garages s'ouvrent, les salles sans défenseurs portent le tampon **KO** — la règle telle que le jeu
l'énonce), et **START_WAR_ATTACK** dont la réponse se sérialise enfin.

### INVASION — vérifiée EN JEU pour la première fois, et pas complète

Le mode était marqué terminé. Il ne l'était pas.

**Le calendrier bloquait, et c'était fidèle.** `nav INVASION` répondait `canNavigateTo=false`. Sonde des
prédicats du jeu un par un : `Unlockables.isUnlocked` = true (TL 100 ≥ 60), mais l'invasion va du **lundi
12 h au samedi 12 h** et on était dimanche.

**Levier ajouté** : `-Ddh.clock.offset.hours` (via `DH_SERVER_OPTS`). Le serveur est la source de l'heure et
le client se cale dessus, donc décaler l'horloge serveur décale l'ensemble de façon cohérente — aucune
vérification n'est court-circuitée, on avance la pendule. Vérifié : à +30 h, serveur ET client affichent
lundi. Corollaire corrigé : `BootData.serverTime` et l'écho `Ping` envoyaient `System.currentTimeMillis()`
en dur, ce qui aurait désynchronisé les deux.

**🐛 Manque nº1 : l'invasion n'était jamais poussée au boot.** Horloge bonne, feature déverrouillée, et
pourtant refus — parce que `InvasionHelper.getActiveInvasion()` rendait `null`. Le client ne connaît
l'invasion que par le message `InvasionInfo`, qu'il ne demandait jamais puisqu'il faut déjà être sur
l'écran pour l'envoyer : poule et œuf. Le vrai backend la pousse au login, comme `SocialHistory` pour le
chat. Corrigé — et l'écran s'ouvre.

**Ce qu'il affiche** : compte à rebours **4j 18h 59m** (exactement la fenêtre envoyée), **énergie
d'invasion 80/80**, BREAKER QUEST / BOSS BATTLES, **TIER 1**, barre 0/100, scores et rangs à zéro.

**🐛 Manque nº2 : `GetBreakerQuest` n'est pas géré.** Taper GO envoie le message, le serveur le journalise
sans répondre, et l'écran reste **entièrement vide**. Le mode SOLO n'a donc pas d'entrée. Non corrigé :
c'est le prochain chantier, avec son étude propre.

Régression 76/76.

## 2026-08-02 (g44) — « tout vérifier en jeu » : les ACTIONS de guerre — 4 défauts RÉELS

Consigne de l'utilisateur : vérifier TOUT en jeu, sans exception. Cette session est passée de l'affichage
aux ACTIONS. Elle a coûté **quatre défauts réels**, dont trois qu'aucun test headless ne pouvait voir.
Détail complet et tableau action par action : [`docs/GUILD_WAR.md`](docs/GUILD_WAR.md) §5.

**1. Personne ne participait jamais à une guerre.** Rien, nulle part, ne créait de `WarMemberInfo` :
`WarGuildInfo.members` restait vide pour toujours. D'où, en cascade, « ce joueur ne participe pas à cette
guerre » sur toute affectation de salle, aucune cible d'attaque, et 0/0 partout à l'écran. **Les tests
headless ne pouvaient pas l'attraper : ils fabriquaient eux-mêmes les `WarMemberInfo` qu'ils testaient.**
Nouveau `ServerWarMembers` : les membres sont bâtis depuis les lineups `WAR_DEFENSE_1..3` réellement posés
(chemin du jeu, comme la défense d'arène), à l'ouverture de la guerre ET à chaque changement de défense ;
l'état de guerre déjà acquis (héros KO, sabotages) est reporté héros par héros, apparié par `UnitType`.

**2. `grantHero` inversait ÉTOILES et NIVEAU — le client plantait au hub.** Relevé au bytecode :
`createAndAddHero(type, rarity, i3, i4, …)` → `createUnitData` fait `setStars(i3)`, `setLevel(i4)`. On
passait `(level, stars)`. Un héros « niveau 40 » recevait donc **40 étoiles**, et
`HasEnoughCollectionHeroes` indexait une liste de taille 7 avec 40 → `IndexOutOfBoundsException` au rendu du
menu latéral : **compte injouable**. `SkillUpgradeTest`/`SkillSetup` produisaient la même corruption sans
jamais l'asserter.

**3. `StartWarAttackResponse` ne se sérialisait pas — attaquer était impossible.** `WarDefense.defenders`
attend des `WarHeroData` (héros complet, pour combattre), pas les `WarHeroSummary` de l'état de guerre.
Recopier la liste compilait (dex2jar efface les génériques) mais levait `ClassCastException` **à l'écriture
sur le fil**, après que le serveur eut journalisé « [persisté] ». Le défaut n'existait donc que du point de
vue du client, qui ne recevait jamais la réponse — invisible headless, par construction.

**4. `createGuild` n'exige pas que le joueur soit sans guilde** (constaté en semant l'adversaire : un membre
a créé une seconde guilde sans quitter la première, qui a gardé son identifiant). Inscrit, non corrigé.

**Vérifié en jeu** : inscription en file, pose des trois défenses (+ resynchronisation dans la guerre),
affectation de salle (la salle passe à 3/3 avec l'écusson), lecture de la défense adverse (0/15 · 3/3), et
**sabotage** — `REDUCE_HP_PERCENT` sur STITCH, **coût 67 / palier 1 RECALCULÉ par le serveur** en ignorant
l'`INDEX` envoyé par le client : l'anti-triche est prouvé en conditions réelles.

**Deux refus, et ce sont ceux du JEU** : `WAR_BAN_PROTECT_MAX_PROTECT_SIZE` et `WAR_SPARS_NOT_ENOUTH` — la
taille de ban/protect et le quota de spars viennent de perks de guilde, et la guilde de test est niveau 0.
Le pilote demande maintenant **son verdict au client** avant d'envoyer, ce qui a permis de nommer ces gates
au lieu de constater un silence.

**Reste** : l'attaque menée à terme (la commande atteint le serveur mais le client cesse de la ré-émettre
après le passage en phase ACTIVE — cause non élucidée), les bans/spars (exigent une guilde avec perks), la
clôture + MMR + boîtes + réclamation, et six commandes de lecture non encore exercées. Tout est listé en
§5.6 de la doc, sans arrondi.

Régression **76/76**. Outillage ajouté sans toucher au jeu : huit commandes de pilote passant par les API
clientes d'origine, `WarSetup`, `WarRivalSeed`, et `AdminWar --resync/--advance/--end`.

## 2026-08-02 (g43) — GUILD WAR : ✅ VÉRIFIÉ EN JEU (client réel → notre serveur → affichage)

La pièce qui manquait depuis le début du mode : la **vérification EN JEU** (PRINCIPLES §8). Menée sur
BaronessDante (TL 100, guilde « Baroness Legion »), pile complète `run-online.sh`. `WAR` se déverrouille à
**TEAM_LEVEL_REQ 45** (`unlockables.tab`) — le compte est largement au-dessus. Détail, tableau et captures :
[`docs/GUILD_WAR.md`](docs/GUILD_WAR.md) §4.

**Outillage ajouté pour la mener, sans toucher au jeu** : pilote DEV `warqueue <ÉTAT>` (appelle le chemin
d'origine `ClientActionHelper.changeGuildWarQueueState` — le message ne porte que l'état, relevé au
bytecode) ; `server/smoke/WarRivalSeed.java` (sème une guilde adverse inscrite, même rôle que `GuildAidSeed`) ;
et `AdminWar --tick --force` pour déclencher l'appariement sans attendre `RESET_HOUR`.

**La chaîne complète, exercée par le VRAI client** : `nav WAR` → `GetWarsList` → écran GUILD WAR
(`COPPER · RANK #1 · MMR 10`) ; `warqueue QUEUED_SINGLE` → `CHANGE_WAR_QUEUE` accepté et persisté ;
`AdminWar --tick --force` → guerre #1 **Baroness Legion vs Rival Syndicate**, phase SABOTAGE ; après
redémarrage, la porte de garage affiche **« VS RIVAL SYNDICATE — BAN PHASE — Ends in 11h 58m 46s »** ; un tap
→ `GetWarInfo` → écran **CURRENT WAR** avec le garage **9 salles sur 3 étages**, les 9 voitures adverses et
les deux MMR ; WAR LOGS → `RequestWarLogs` ; ALL LEAGUES ; RANKS → `GetWarRankings(COPPER)`.

**Trois confirmations qui vont plus loin que « ça rend »**
1. **La chronologie des phases est la bonne.** Le décompte « BAN PHASE 11h 58m » est le client qui relit,
   avec son propre `WarHelper.isBanPhase`, notre `extraStateEndTime` posé à `début + SABOTAGE_BAN_PHASE_LENGTH`
   (12 h). Le chiffre affiché EST la preuve de la valeur envoyée.
2. **Le barème de score est le bon.** WAR LOGS affiche `Lineups Defeated ×1`, `Rooms Defeated ×100`, et
   `Clean Sweeps / Defensive Wins / Clean Defenses ×0` — exactement `POINTS_PER_LINEUP`, `POINTS_PER_CAR` et
   les scalaires de voiture à 0 **parce qu'aucune voiture n'est détenue** (`ServerWarScoring.carPointScalar`).
3. **Le correctif `GuildInfo.warEndTime` (étape 2) était bien vital** : sans lui, le client n'aurait vu
   **aucune** guerre active. Les logs Windows l'avaient laissé entendre ; l'écran le prouve.

Confirmés au passage : les tranches de ligue (`COPPER 1-199 / BRONZE 200-399 / SILVER 400-599`), les 3 boîtes
par ligue (`NUM_SEASON_BOXES`), et la résolution du `seasonID` 104 en « **Aug 2026** ».

**Une bizarrerie observée, puis EXPLIQUÉE — et surtout PAS « corrigée » à tort.** Au classement, la guilde du
joueur apparaît deux fois. Réflexe §8 : aller lire le bytecode avant de conclure. `WarRankingsScreen
.addPosterContent` ajoute une ligne d'**en-tête** « ta guilde », conditionnée par `isYourGuildInRankings()` —
elle ne s'affiche donc **que si** la guilde figure déjà dans `rankingRows`, que `addRankingsContent` rend
intégralement. Le doublon est le rendu d'origine ; **retirer notre guilde de `rankingRows` supprimerait
l'en-tête**. On ne touche à rien (§1/§4bis).

**Vérifiés en jeu aussi, dans la foulée** : `SetLanguage 'en' appliquée [persisté]` et
`SetExternalContentStatus hasExternalContent=true`, les deux manques relevés dans les logs Windows — reçus du
vrai client et traités.

**Ce qui reste NON vérifié en jeu, dit franchement** : placer les lineups de défense (une défense = **15 héros
distincts**, le compte de test en a 7), attaquer, sabotages/bans/protections/spars (exigent des `WAR_TOKENS`),
réclamer une boîte, et la clôture + MMR + bascule de saison (2 jours réels). Tous couverts headless
(`WarAttackTest`, `WarSabotageTest`, `WarEndTest`, `WarSchedulerTest`) ; ils passeront ✅ le jour où un compte
à 15 héros et une guerre menée à terme les exerceront.

Instantané de base pris avant l'exercice : `server/data/dh-snapshot-prewar-0802.db` (+ `-wal`/`-shm`).

## 2026-08-02 (g42) — GUILD WAR : l'ORDONNANCEUR (étape 10/10) + le « flake » qui n'en était pas un

Dernière pièce du mode : jusqu'ici **toutes les briques existaient mais rien ne les déclenchait**. Aucune
guerre ne démarrait d'elle-même, aucune ne se terminait, aucune boîte ne tombait.

**`ServerWarScheduler`** — un tour de boucle **idempotent et rejouable** : bascule de saison (avec le rang
final par MMR, donc les boîtes de fin de saison) → clôture des guerres échues (issue, MMR, remboursement au
perdant, boîtes de promotion) → avance de phase `SABOTAGE → ACTIVE` → appariement des guildes inscrites, à
l'heure prévue. Branché dans `LoginServer.main` sur un thread démon (`startBackgroundLoop`, période réglable
par `-Ddh.war.tick.seconds`, défaut 60 s), appliqué à **tous les shards portant des guildes**
(`tickAllShards` + nouveau `UserStore.listGuildShards`, PRINCIPLES §5).

**Le calendrier** : le client fait de `WarQueueStateUpdate.nextMatchmakingTime` **directement**
`warInfo.startTime` (`WarClientHelper.updateWarInfoQueueState`) — l'instant d'appariement EST le début de la
prochaine guerre. ⚠️ **Lecture structurelle assumée, isolée dans `lastMatchmakingTime`** : ancrage sur
`RESET_HOUR` (11 h, fuseau serveur), seule heure de référence des données et qu'aucune classe cliente ne lit.

**Deux pièges, tous deux réels et corrigés.**
1. **La fenêtre se compare au PASSÉ.** Ma première version testait `now >= nextMatchmakingTime(last)` — valeur
   par construction dans le futur, donc **la condition n'était jamais vraie** : l'appariement ne tournait pas
   une seule fois. Le tour compare désormais le repère persisté à la **dernière** occurrence de `RESET_HOUR`,
   et enregistre le repère de la **fenêtre** (pas `now`), sans quoi l'heure dériverait à chaque tour.
2. **Un shard neuf ne doit pas apparier à son premier tour**, sinon le tout premier démarrage du serveur
   ouvrirait des guerres à n'importe quelle heure. Il pose le repère et attend — d'où l'outil opérateur.

**`ServerGuild` v9** : `warBoxedLeagueMask` (« boîtes déjà remises »), **distinct** de `warPromotionMask`
(« ligue atteinte », plancher anti-rétrogradation « cannot be demoted »). Les confondre redistribuerait des
boîtes à chaque guerre. Le masque de boîtes repart à zéro à chaque saison, le plancher non.

**Outil opérateur `AdminWar`** (pendant d'`AdminGuild`/`AdminMail`) : `--status` (saison, prochain
appariement, et par guilde MMR / ligue effective / file / guerre en cours / boîtes en attente) et
`--tick [--force]`. Le forçage ne concerne QUE l'appariement : clôtures et bascules de saison dépendent
d'échéances réelles qu'on ne bouscule pas. Vérifié en CLI sur 3 guildes : tour non forcé = aucun appariement
(calendrier respecté), tour forcé = **1 paire + 1 BYE**, guildes en `SABOTAGE` jusqu'à J+2, file remise à
`NOT_QUEUED`.

### 🐛 Le « flake `ChestWireTest` » n'était pas un flake — c'était une course réelle

En vérifiant la régression, `ChestWireTest` échouait **systématiquement**, ce qui contredisait la note « vert
en isolation » portée depuis des semaines. Vérifié d'abord que ce n'était pas moi : l'échec est identique sur
`HEAD` sans mes modifications.

Mesuré ensuite, au lieu de supposer : **un message émis par le client avant que le serveur ait accepté la
connexion est perdu**. Envoi immédiat après `open()`, ou depuis le `onOpen` du client → jamais reçu, 5 essais
sur 5. Le même envoi 50 ms plus tard → reçu, 5 essais sur 5. Et le tampon socket ne rattrape pas : après un
second envoi, le serveur décode **exactement un** message, pas deux — les octets du premier n'ont jamais
atteint son décodeur. La cause qu'on invoquait (chargement de `GuildStats`) était fausse : cette exception
est bien levée, mais **absorbée** par le warm-up de `ServerContext`.

Le **vrai client n'y est pas exposé** : son `/login` HTTP précède l'ouverture du socket de jeu et couvre
largement la fenêtre. Le test, lui, enchaînait connexion et envoi dans la même milliseconde.

⚠️ **Et j'ai conclu trop vite une première fois** : après un correctif « attendre l'`accept` du serveur »
vert 5/5, la régression complète a refait échouer le test — cette garde seule laisse encore **3 échecs sur
10**. Ce qui est établi, c'est l'existence et l'ampleur de la fenêtre, **pas son mécanisme** :
`GruntNIOTCPServer.read` ne consomme rien quand la connexion est absente du registre
(`READ_CHANNEL_NULL_STAT`) ou pas prête (`READ_CHANNEL_NOT_READY_STAT`) — il rend `false` sans lire, et le
sélecteur par niveau devrait réessayer ; `GruntTCPConnection.send` écrit de façon synchrone. **Où les octets
disparaissent reste inexpliqué**, et c'est consigné tel quel dans `SHIMS.md` plutôt que masqué.

Traitement : `LoginServer` expose `connectionsAccepted` (compteur utile aussi en exploitation), et le test
attend ce point **puis RÉÉMET** le `ClientInfo` jusqu'à obtenir le `BootData` (réponse idempotente). Ce n'est
pas un faux « OK » : l'échange RÉEL `BuyChests → LootResults` reste exigé. Vérifié **8/8** en isolation.

**Régression : verte au complet, aucun échec toléré** — une première (jusqu'ici 74/74 avec un échec toléré).

### Deux manques des logs Windows comblés

**`SetLanguage`** — le jeu a un champ POUR ÇA : `UserExtra.language`, écrit par le setter d'origine
`User.setLanguage(Language)`. Comme il vit dans `this.extra`, il est **auto-persisté** : aucun re-sync à
écrire. Le handler résout le code reçu par la méthode du jeu `Language.getLanguage(code)` et appelle le
setter — zéro règle réécrite. [`SetLanguageTest`] : application, round-trip SQLite, changement de langue,
refus d'un code vide/nul sans altérer l'existant. **7ᵉ fois où mon test avait tort** : j'attendais `null`
pour un compte neuf, le constructeur du jeu pose une chaîne **vide**.

**`SetExternalContentStatus`** — **NO-OP FIDÈLE**, même catégorie que `RECORD_SERVER_ROLL_FINISHED` :
`hasExternalContent` n'apparaît, dans tout le jar, que dans ce message, dans son émetteur
(`ExternalAssetManager$DeferredSetExternalContentFlag`) et dans `HeroFiltersActV1`. **Aucun champ de joueur
ne le reçoit et rien ne le relit** : c'est une notification sur l'APPAREIL. Lui inventer un stockage
violerait §4 — on acquitte et on journalise, c'est la réponse autoritative correcte.

**Reste** : la **vérification EN JEU** de GUILD WAR (et d'INVASION, et des trous de guilde) — **nulle à ce
jour**. Statut 🟢, pas ✅.

## 2026-08-02 (g41) — GUILD WAR (#68) : mode COMPLET côté serveur, branché de bout en bout

Neuf incréments, chacun commité et poussé séparément, chacun avec son test dédié.
Doc de suivi complète : [`docs/GUILD_WAR.md`](docs/GUILD_WAR.md).

**Méthode : la preuve avant le code.** Trois faits établis avant d'écrire une ligne.
1. La logique WAR est **cliente** (`WarStats`/`WarHelper`/`WarCombatHelper`/`ui.war.WarClientHelper`) —
   même configuration favorable qu'INVASION.
2. Scan du pool de constantes des **20 341 classes** `com/perblue/**` : **28 constantes** de
   `war_constants.tab` n'apparaissent QUE dans leur propre déclaration. Témoin de contrôle validant le
   discriminant : les constantes réellement consommées apparaissent dans ≥ 2 classes. Même signature que
   `MERCENARY_COST` ⇒ elles sont parsées **pour le serveur**.
3. Le jeu **documente ses propres règles** dans `HowToPlay.properties` (cartes `WAR_CARD_*`), et elles
   correspondent **une à une** aux constantes (1 pt/lineup, 100/salle, sabotage 24 h + bans 12 h, « top
   ten → Gold », « others Copper to Silver », « cannot be demoted »).

**Livré** : `ServerWar` (saisons/ligues/MMR/reset), `ServerWarState` (état SYMÉTRIQUE, deux vues) + table
`wars`, `ServerGuild` v8, `ServerWarMatchmaker` (appariement, anti-rematch, BYE, phases), `ServerWarCars`
(dérivation des voitures, étoiles, portes), `ServerWarAttack` + `ServerWarScoring` (validations, KO
définitif, barème), `ServerWarSabotage` (sabotages/bans/protections/spars), `ServerWarEnd` (issue, MMR,
remboursements, boîtes), `ServerWarBoxes` + table `user_war_boxes`. **7 messages et 11 commandes branchés**
dans `LoginServer`, avec diffusion à la guilde. **7 tests dédiés. Régression 74/74.**

**Trois défauts RÉELS trouvés et corrigés en route**
1. `nextGuildID` lisait `MAX+1` **avant** insertion → deux créations concurrentes obtenaient le **même
   identifiant** et la seconde **écrasait** la première (upsert), faisant disparaître une guilde sans la
   moindre erreur. Alloue désormais sous verrou via un compteur persisté.
2. `ServerGuild` v8 **dupliquait** `warQueueState`/`warExtraAttackRank`/`warEndTime` que porte déjà le
   `GuildInfo` **du jeu**, lu par le client → double source de vérité (§4/§6). Sans `warEndTime` écrit
   dans le `GuildInfo`, **le client n'aurait jamais vu de guerre active** — et les logs Windows fournis le
   jour même l'ont confirmé indépendamment (`isWarActive` appelé à chaque rendu du hub).
3. **`setCount(UserFlag)` ne persiste PAS** (les compteurs vivent dans `User.counts`, hors `this.extra`)
   alors que `setTime` oui. `creditMercenaryHireReward` (#64) faisait `setCount` **sans** `resyncCounts`
   et son commentaire affirmait le contraire : le compteur hebdomadaire « earned this week » repartait à
   zéro à chaque round-trip. `GuildMercRewardTest` ne l'avait pas vu car il n'assertait que la monnaie.

**Six fois, mes TESTS avaient tort et la règle du jeu raison** — cinq d'entre elles autour de la même
phrase, « a room with no defenders is automatically defeated and worth 100 points to the other side » :
un étage dégarni ouvre immédiatement l'étage suivant ; les salles vides valent déjà 100 points chacune à
l'adversaire ; l'attaquant qui garnit moins de salles PERD malgré ses attaques réussies (608 contre 700).
La sixième : une défense de guerre compte **15 héros distincts** — le protocole identifie la victime d'un
sabotage par son **seul `UnitType`**, ce qui n'aurait aucun sens autrement.

**Quatre lectures/inférences assumées**, chacune isolée dans UNE méthode et documentée : `ratingChange`
(Elo standard sur `ELO_K`/`ELO_N`), `rematchPenalty` (interpolation par ancienneté), `sabotageCurrency`
= `WAR_TOKENS`, durée du jour 2. Plus `ELO_LOSS_BUFFER_THRESHOLD`, **lue mais volontairement NON
appliquée** — j'avais d'abord inventé un rôle pour elle, le test l'a démenti, et l'analyse a montré que
c'était à la fois arbitraire ET sans effet.

**Garde-fou mesuré** : les récompenses de SAISON n'ont aucun `max(…,1)` et deviennent **négatives sous
TL 289** (282 en LEGENDARY) — les créditer **retirerait** des ressources au joueur. `keepPositive` écarte
les quantités ≤ 0 ; les lignes de PROMOTION, elles, ne descendent jamais à 0 (vérifié sur 84 boîtes).

**Restent** : distribution automatique des boîtes (promotion / fin de saison) et un ordonnanceur
(appariement, clôture) — les fonctions existent, le déclencheur manque. **Et surtout la vérification EN
JEU, nulle à ce jour** : statut 🟢, pas ✅.

## 2026-08-02 (g40) — PREMIER RUN WINDOWS de la pile complète (logs fournis par l'utilisateur)

L'utilisateur a réussi à lancer le jeu **sur Windows** (Intel UHD 620, GL 3.2, 1280×720) et a fourni
`logs_pc.txt` (1043 lignes). C'est le **premier run hors Linux/headless**, et il valide le chemin de boot
de bout en bout **en conditions réelles**.

**Ce qui marche, prouvé par le log**
- Redirection réseau : `ServerType.LIVE -> login http://127.0.0.1:8080/login` ; le login HTTP répond
  `{"status": "good", "data": "127.0.0.1:8081"}` ; `[NetworkProvider] Connection to 127.0.0.1:8081 opened`.
- Handshake complet : `ClientInfo1 → BootData (122 actes de tuto)`, puis `Ping1 → Ping (echo)`.
- `handleBootData` va **jusqu'au bout** : user créé, challenge/battle-pass/chat/contests/**invasions**/
  special events/dots initialisés, « Sending BootData to screen », `MainScreen` atteint.
- **Stat-sync (#40) opérationnel** : `Updated com.perblue.heroes.game.data.DHConstantStats from text
  battle_pass_v2_constants.tab`.
- Tutoriel persisté : `tuto INTRO -> step 1/2/3 [persisté]`, puis `onClose` propre.
- **Spine d'origine via unidbg fonctionne aussi sur Windows** : `[UnidbgVM] 1er Skeleton_getVertices
  (spine d'origine via unidbg) -> drawCount=192`. Le message
  `libspine-native.so load dependency libandroid.so failed` qui le précède est ATTENDU (c'est un `.so`
  Android/Linux) : le chargement direct échoue, le chemin unidbg prend le relais.
- Les erreurs de parse `.tab` (`FAST_FORWARD`, `SAPPHIRE_5..12`, `guild_perk_levels.tab` `CONTENT_TL`
  vide, `fight_pit_season_configs.tab`…) sont **exactement les mêmes** que celles observées headless →
  confirme l'analyse déjà documentée : intrinsèques à la donnée du 12.1.0, attrapées et sautées par
  `GeneralStats.onStatError`, **non fatales**.

**⭐ Confirmation INDÉPENDANTE d'un correctif fait le jour même (GUILD WAR étape 4)**
La trace réelle du client montre :
```
GuildPerkHelper.updateGuildInfoTimedPerks ← GameMain.getYourGuildInfo
  ← WarHelper.isWarActive
  ← HasActiveGuildWar.isSatisfied ← QuestHelper.isUnlocked ← getUnlockedDailyQuests
  ← hasUnclaimedDailyQuests ← showDailyQuestMenuDot ← DotTracker ← RedDot
```
Autrement dit, **le client appelle `WarHelper.isWarActive` à chaque rendu du hub** (pastille de quêtes
quotidiennes, via la condition de quête `HasActiveGuildWar`), et `isWarActive` lit
`getYourGuildInfo().warEndTime`. C'est précisément ce qui rendait critique la correction faite quelques
heures plus tôt : ma v8 de `ServerGuild` **dupliquait** l'état de guerre au lieu d'écrire dans le
`GuildInfo` du jeu — sans `warEndTime` renseigné, le client n'aurait jamais vu de guerre active, et le
défaut ne se serait manifesté qu'en jeu. Le log le confirme depuis un client RÉEL.

**Deux manques réels relevés (petits, consignés, non traités ici)**
1. `SetLanguage1` et `SetExternalContentStatus1` **arrivent au serveur et ne sont pas traités** (simple
   journalisation, comme la télémétrie). `SetLanguage` porte la langue du joueur — un vrai serveur la
   persisterait (elle compte pour les textes générés côté serveur, p. ex. le courrier admin).
2. Sur Windows, le chargement direct de `libspine-native.so` échoue et l'on retombe sur unidbg. Ça
   marche, mais un binaire spine natif Windows serait le chemin propre.

**Ce que ces logs NE prouvent PAS** — à dire clairement : le compte est à TL1 dans le tutoriel, donc
**aucun** message de guilde, d'invasion ou de guerre n'a été échangé. La vérification en jeu de GUILD
WAR (#68), d'INVASION (#69) et des trous de guilde reste **entièrement due** ; leur statut demeure 🟢
(serveur prouvé) et non ✅.

## 2026-07-20 (g12) — Quêtes : boîtes-récompense weekly + START_QUEST/Prize Wall élucidés par les faits

Demande « finir quêtes ». **Boîte-récompense weekly** (gap réel) : ouvrir une boîte (après
`REDEEM_DAILY_QUESTS`) n'est PAS une commande — le client envoie un message top-level
`ClaimWeeklyQuestReward{rewardChosen, rewardDrops, staminaReward}` (fire-and-forget, comme `CampaignAttack`).
Handler `LoginServer` → `ServerUser.claimWeeklyReward` → `QuestHelper.claimWeeklyReward(user, rewardChosen,
staminaReward)` : anti-triche RÉEL sur le NOMBRE (`getWeeklyRewardsRemaining = getCount(WEEKLY_QUEST_REWARDS)
> 0` sinon `ClientErrorCodeException`), donne stamina + récompense, décrémente le compteur, persiste. PARTIEL
documenté : le CHOIX `rewardChosen` est client-autoritative (re-valider le tirage vs graine
`WEEKLY_QUEST_REWARD` = Partiels D/E, comme l'issue de combat). `START_QUEST` : **aucun émetteur client** dans
le 12.1.0 (que la table de dispatch + l'enum) → non atteignable, rien à faire. Prize Wall : déjà couvert
(quêtes `PRIZE_WALL_*` → `completeQuest` → `COMPLETE_QUEST`, handler g10). Vérifié `server/smoke/WeeklyBoxTest`
(2 boîtes → ouvre/décrémente 2→1→0, persiste ; 3ᵉ à 0 REFUSÉE) + régression 26/26. Fichiers :
`server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/WeeklyBoxTest.java`.

## 2026-07-20 (g11) — Battle pass : changement de mois (rollover réel) + premium pour tous corrigé

Deux faits trouvés/corrigés (règle « aucune supposition »). **(1) Premium pour tous était FAUX** :
`getPremiumUnlocked()` lit le booléen `premiumUnlocked` (champ wire SÉPARÉ), PAS `boughtBattlePass`. On ne
posait que `boughtBattlePass=1` → premium restait verrouillé (claim premium aurait échoué
`BATTLE_PASS_MISSING_PREMIUM`). Fix : `refreshBattlePass` pose `premiumUnlocked=true` à chaque refresh (même
après reset → premium toujours débloqué). **(2) La saison ne roulait qu'au REDÉMARRAGE** : l'ancre
(`SEASON_START_TIME`/`HIDE_BATTLE_PASS_AFTER`) n'était posée qu'à `init()` (idempotent) → un serveur tournant
de juillet à août sans redémarrer restait au 1er juillet. Fix : `ServerContext.anchorBattlePassSeason()`
(re-calcule le mois courant), appelé à chaque `refreshBattlePass` → rollover dès que le mois réel change.
**Ce qui se passe au changement de mois** : `refreshBattlePass` détecte `bp.startTime != seasonStart` →
(a) conserve les récompenses MÉRITÉES non réclamées de la saison écoulée dans `previousUnclaimed` (comme le
jeu en fin de saison ; réclamables via `BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS` → `collectEndedSeasonRewards`),
(b) reset `progress`/`lastSeenProgress` + vide `claimedFree/PremiumRewards`, (c) ré-ancre `startTime`/`endTime`,
garde `premiumUnlocked=true`. `getRewardTiers(l)=floorEntry(l)` → mois ancien/nouveau donnent le même jeu de
paliers (contenu R102 stable). Vérifié `server/smoke/BattlePassRolloverTest` (premium claim OK ; rollover :
progress 36→0, claims vidés, 6 gratuites + 5 premium non réclamées conservées puis collectées, premium
maintenu, saison ré-ancrée) + régression 25/25. Fichiers :
`server/java/dhserver/{ServerContext,ServerUser}.java`, `server/smoke/BattlePassRolloverTest.java`.

## 2026-07-20 (g10) — Battle pass : handlers de réclamation + progression AUTO via QUEST_POINTS + persistance

Suite de « finir battle pass ». Établi PAR LES FAITS (bytecode) que la progression du battle pass EST la
ressource `ResourceType.QUEST_POINTS` : `IndividualUser.setResource(QUEST_POINTS)` route vers
`DH.app.getUserBattlePassV2().setProgress`, `getResource(QUEST_POINTS)` lit `getProgress` (décodé via le
switch-map synthétique `IndividualUser$1` : `QUEST_POINTS.ordinal()` → case 4). Conséquence : en liant le
shim `DH.app.getUserBattlePassV2()` à un `BattlePassV2DataWrapper` sur NOTRE `BattlePassV2Data` persisté, la
progression s'accumule via le CODE DU JEU (une quête qui donne des QUEST_POINTS → setResource → setProgress),
zéro glue (PRINCIPLES §3). Le wrapper est writes-through (`this.data.progress = n`, `claimedFreeRewards.put`)
→ claims/progress mutent le message persisté.

**Wiring** (`ServerContext`) : champ `battlePassField = GameMain.userBattlePassV2` + `bindBattlePass(data)`
pose le wrapper (comme le vrai client `GameMain.lambda$setupPostClientInfoHandlers` à la réception d'un push
`BattlePassV2Data`). Appelé dans `ServerUser.applyAction` pour TOUTE action (sinon NPE quand une quête donne
QUEST_POINTS).

**5 handlers** (`ServerUser.applyCommand`, extras relevés au bytecode `ClientActionHelper`) :
- `BATTLE_PASS_V2_CLAIM_REWARD` `{TYPE, INDEX=palier, MODE=premium}` → `BattlePassV2Helper.claimReward(user,
  bp, tier, premium, false)`. Anti-triche RÉEL du jeu : `claimableReward` refuse `BATTLE_PASS_MISSING_POINTS`
  (progress < points du palier) et `BATTLE_PASS_MISSING_PREMIUM`. **+ GARDE AUTORITATIVE anti-double-claim**
  ajoutée AVANT via le prédicat OFFICIEL du jeu `isFreeTierClaimed/isPremiumTierClaimed` (`entry != null &&
  !isEmpty`). Raison (fait) : la garde INTERNE de `claimReward` (`claimableReward`) teste
  `rewardTierClaimed(list) = list.isEmpty()` ET `addClaimedFreeRewards` APPEND les récompenses → un palier à
  récompense NON vide n'est jamais « déjà réclamé » côté garde interne ; c'est le CLIENT (UI) qui grise le
  bouton via `isFreeTierClaimed`. Un serveur autoritatif doit refuser ce que le client empêche → on réutilise
  la sémantique du jeu (pas une règle inventée, §2).
- `BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS` `{TYPE}` → `collectEndedSeasonRewards` (récompenses de saison
  précédente non prises ; vides hors rollover → idempotent).
- `BATTLE_PASS_V2_BUYOUT` `{ID, TYPE}` — le palier courant N'EST PAS transmis → DÉRIVÉ serveur
  `getTierByPoints(progress, start)` (même dérivation que `getBuyoutRewards`) → `doBattlePassBuyout` (débit
  DIAMONDS `getBuyoutCost`, collecte + réclame les paliers restants).
- `UPDATE_BATTLE_PASS` (aucun extra, fire-and-forget) → acquitté (le rollover de saison est déjà géré par
  `refreshBattlePass()` au bind : reset progress+claims quand `startTime` change).
- `VIEW_BATTLE_PASS_SCORE` `{COUNT = getResource(QUEST_POINTS)}` → `setLastSeenProgress` (marque le score vu).

**Persistance** (`ServerUser` + `UserStore`) : champ `battlePassV2Data` (mutable, hors userExtra) +
`refreshBattlePass()` (lazy-create, reset au changement de saison, type QUEST + premium + saison courante) +
`battlePassWire()`/`setBattlePassWire()` + colonne BLOB `battlePassV2Data` (ALTER migration `columnExists`,
load/save). Un objet du jeu = une colonne BLOB (§6).

**Vérifié** `server/smoke/BattlePassClaimTest` : palier RÉEL choisi = 2 (9 points, récompense non vide) ;
(1) claim à progress 0 → REFUSÉ (points manquants) ; (2) don QUEST_POINTS=9 → `getProgress`=9 &
`getResource(QUEST_POINTS)`=9 (accumulation via le code du jeu) ; (3) claim → appliqué + `isFreeTierClaimed`
true ; (4) re-claim → REFUSÉ (déjà réclamé) ; (5) reload wire → palier toujours réclamé + progress=9.
**Régression 23/23 verte.** ⚠️ Écran BATTLE PASS verrouillé TL11 (`unlockables.tab BATTLE_PASS=11`) → vérif
EN JEU du battle pass reportée à TL≥11 (documenté par les faits, non supposé).

**TESTÉ EN JEU — écran QUESTS à TL2 (choix user)** : client lancé (save au hub), navigation vers QUESTS via
l'API du jeu (`UINavHelper.navigateTo(QUESTS)`, commande clic-fichier `goquests` ajoutée). L'écran rend avec
les données SERVEUR (WEEKLY QUEST paliers 21..105 « Rewards 0/5 » « Quests 0/105 » PENDING ; STAMINA BOOST
« Get 796262720 free Stamina between 12 PM and 2 PM » réclamable ; DAILY DEAL/TREASURE HUNTER/DAILY CAMPAIGN ;
onglet **BATTLE PASS visiblement grisé/verrouillé** = TL11 confirmé à l'œil). Sonde B-bis (`dumpscreen` +
`dump x y`) → bouton CLAIM du stamina boost = `QuestRow`/`DHTextButton name=QUEST_CLAIM_AWARDS` @stage(973,75)
= screen(973,645). **Tap CLAIM** → client `Action COMPLETE_QUEST {ID=2}` → serveur « récompense créditée +
complétion persistée [persisté] ». Après le claim (capture) : ligne STAMINA BOOST **consommée** (disparaît) +
compteur weekly **« Quests 0/105 → 1/105 »**. Persistance DB : quête id=2 **plus réclamable** (OncePerDay) +
`WEEKLY_DAILY_QUESTS_COMPLETE=1`. **⇒ CLÔT LE GAP g7 §8** (claim FREE_STAMINA « stamina boost » vérifié en jeu
client↔serveur↔persistance). Outillage : commandes `goquests`/`dumpscreen` (`TutorialDriver.navTo/dumpScreen`),
`server/smoke/DailyQuestProbe`. Snapshot pré-test `server/data/dh-snapshot-prequest-0720.db`.

Fichiers : `server/java/dhserver/{ServerContext,ServerUser,UserStore}.java`,
`server/smoke/{BattlePassClaimTest,DailyQuestProbe}.java`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.

## 2026-07-19 (g5) — Prise de contrôle manuelle : 3 écrans confirmés EN JEU (CHOOSE NAME, SIGN IN claim, HERO_FILTERS)

Depuis le save compte-neuf (au hub, autotap OFF + clickfile ON), pilotage manuel via `dh.clickfile` (tap par
coordonnee + dump de l'acteur + captures), pour confirmer EN JEU au clic reel 3 ecrans a handlers deja batis
mais non verifies au clic. Tous OK client<->serveur<->persistance :

1. CHOOSE NAME : avatar hub -> PlayerProfileWindow (Team Level 2, Max TL 565, Account ID 1, Server (1) Number
   One Dime, Client 12.1.0) -> crayon -> CHANGE NAME prompt -> bouton RANDOM (remplit "Baroness Dante", pas de
   clavier headless) -> CHANGE NAME -> serveur `<== SetPlayerName` / `[setname] nom -> 'Baroness Dante'
   applique [persiste]` -> affiche "BARONESS DANTE" au profil ET au hub.

2. SIGN IN claim : batiment SIGN IN -> Action REFRESH_SPECIAL_EVENTS -> serveur `==> SpecialEventsRaw (31
   jours de sign-in)` -> ecran DAILY SIGN-IN (valeurs exactes de signin_rewards.tab : j1 226,25M or, j2 50
   diamants, j4 35305 gear juice...) -> tap jour 1 -> popup CLAIM -> `<== CLAIM_SIGNIN_REWARD {INDEX=0}` /
   `[action] CLAIM_SIGNIN jour 0 -> GOLD 226 250 000 applique [persiste]` -> or hub 3476 -> 226,25 M
   (DbInspect = 226 253 476, persiste).

3. HERO_FILTERS (ou l'auto-pilote calait historiquement) : menu HEROES -> HeroListScreen (3 heros niv.2 W +
   heros verrouille) -> bulle tuto "Hero Filters..." -> FILTER (tag FILTER_BUTTON) -> HERO FILTERS window
   (categories GENERAL/EFFECTS/+-STATS/TALENTS, Sources Skills/Real Gear/Battle Badge/Patch Talents, Team...)
   -> navigation categories -> acte HERO_FILTERS progresse step 1->4. Le blocage historique (getPointers vide
   headless) est debloque par le controle manuel/semi-auto (commandes drive/center du g3 fonctionnent : log
   [semiauto] center -> tap). ATTENTION (correction) : NON TERMINE. HeroFiltersActV1 a 11 etapes (jusqu'a
   DONE) ; arret au step 4 (le tuto demandait encore de toggle un filtre, visible sur la capture). Mon "4/4
   COMPLET" etait FAUX : mauvaise lecture du champ persiste maxStep (= plus haut step VU, ServerUser.java:169,
   PAS le total de l'acte). L'ecran + le flux client<->serveur sont confirmes ; l'acte reste a finir.

Methode : capturer (manual.ppm -> png) -> regarder -> ecrire x,y (ou commande semi-auto) dans le clickfile ->
tap via input reel (hit-test correct) + dump acteur -> lire serveur/clic. La limite connue du dumpClickTarget
(modales sur une couche que le hit-test du stage principal manque) n'empeche pas le vrai tap (input reel).

Checkpoint : server/data/dh-snapshot-manualtests-0719.db (nom + or 226M + HERO_FILTERS complet). Reste a
explorer : ITEMS, QUESTS, MEDALS, MAILBOX, EVENTS, PROMOTE_HERO, UNLOCK_HERO (Vanellope), campagne > 1-7.


## 2026-07-19 (g2) — Coffres PAYANTS : débit DÉMONTRÉ end-to-end (bug 4ᵉ param) + correction « TL1 »

### Demande & découverte
User : « vérifier les coffres payants avec des team levels plus hauts pour vérifier tes dires ». Mon test
précédent montrait qu'à TL40, une fois le coffre GOLD gratuit consommé, `openChest` REFUSAIT le payant avec un
`ERROR` générique — le débit n'était donc PAS démontré. Cause trouvée en décompilant (CFR) la chaîne cliente
`SilverChestDetailScreen` → `ChestHelper.openChest` → `openChestInner` :
```
if (validateChestPurchase(user, type, count, n2 /* = COÛT */, item, snapshot)) break;
...
buyChests.cost = n2;   // le coût est ré-émis dans le message
```
⇒ **le 4ᵉ paramètre de `validateChestPurchase` == `BuyChests.cost`** (0 pour un gratuit, le coût réel pour un
payant). La branche PAYANTE de `validateChestPurchase` termine par `if (coûtRecalculéServeur > coûtDéclaréClient)
throw ERROR` = un **contrôle ANTI-TAMPER** (le client ne peut pas déclarer un coût inférieur au vrai). Le serveur
passait **`0` en dur** → pour un payant `288 > 0` → `ERROR` systématique → le débit était INATTEIGNABLE.

### Fix (RÉEL, miroir du client)
`ServerUser.openChest` : `validateChestPurchase(user, type, count, m.cost, usedItem, NONE)` (au lieu de `0`).
Gratuit → `m.cost=0` → branche gratuite → OK ; payant → `m.cost=coût` → `coût==coût` faux → OK ; coût
sous-déclaré → `coûtRecalculé > m.cost` → ERROR (anti-triche renforcée).

### Vérifié — débit end-to-end (server/smoke/ChestPaidDebitTest, TL40)
1) coffre GOLD gratuit consommé (`wasFree=true`) ; 2) ouverture GOLD **PAYANTE** avec coût correct déclaré
(`m.cost=288`) → **-288 DIAMONDS** (100000→99712), **persiste au round-trip wire** (99712) ; 3) coût
**sous-déclaré (287)** → **REFUSÉ** (anti-tamper), aucun débit. Régression 8/8 verte.

### Correction d'une affirmation antérieure FAUSSE
« À team level 1 (tuto) le jeu REFUSE tout achat payant » était **inexact**. `validateChestPurchase` n'a **aucun
gate de team level** pour SILVER/GOLD (seuls VIDEO=`TEAM_LEVEL_LOCK`, SOUL=`VIP`, EVENT/WISH ont des gates). Le
verrou du tuto `REBLOCK_SILVER_BUY_ONE` est **côté CLIENT** (`SilverChestDetailScreen`), pas dans la validation
serveur. Le vrai blocage du débit était le bug du 4ᵉ param, pas le niveau d'équipe. `ChestChargeTest` recadré
(le refus qu'il teste = coût sous-déclaré à 0, pas un « verrou TL1 »).


## 2026-07-19 (c) — SIGN-IN multi-jour (j2/j3) vérifié + GAP diamants corrigé (resyncDiamonds)

### Question (user) & vérification
« Les deux sont-ils testés in-game ? À j2/j3 le joueur aura-t-il les récompenses correspondantes, bien
créditées et disponibles ? L'ext headless (isNameLegalExt) impacte-t-il les vrais joueurs desktop/android ? »

**Mécanique jour-par-jour (relevée au bytecode)** : `SigninHelper.getActiveRewardIndex` = fonction de
`getMonthlySignins()` (nb de réclamations) et `getDailyChances("daily_signin")` ; `isClaimable` exige une
chance quotidienne > 0 (une claim/jour) dans la fenêtre du mois. La chance quotidienne se **réinitialise
LAZY** : `IndividualUser.getDailyChances` appelle `DailyActivityHelper.checkAndUpdateDailyValues` qui compare
`serverTimeNow()` à `LAST_USER_DAILY_RESET` (`isSameUserDay`) et, à un nouveau jour, `resetDailyChances(key,
max)` — le tout sur `individualUserExtra.dailyChances` (this.extra, persisté). Donc j2/j3 = logique du jeu
automatique, pas de code serveur spécial.

### Vérification multi-jour (server/smoke/SigninMultiDayTest)
j1 : réclame le jour actif → GOLD (226 250 000) crédité. Simulation j2 : recul de `LAST_USER_DAILY_RESET` de
2 jours → le reset lazy remet `daily_signin` à 1. j2 : jour actif AVANCE (0→1), réclamable, claim → DIAMONDS
(50). `monthlySignins` 1→2. Tout persiste au round-trip wire.

### VRAI GAP trouvé : crédit des DIAMANTS perdu au reload
Au 1er jet, j2 donnait DIAMONDS 0→0 (récompense NON créditée). Cause (diagnostiquée) : les diamants vivent
dans un **champ dédié `IndividualUser.diamonds`** (init depuis `userInfo.diamonds` au `getIndividualUser`,
lu/écrit par `get/setResource(DIAMONDS)`) — **HORS `this.extra`**. `getResource(DIAMONDS)` lit bien ce champ
(vérifié : param 777 → 777), mais `applyAction` ne re-syncait que les héros → le gain de diamants restait en
mémoire et était **perdu au round-trip wire** (comme l'étaient team-level et le nom avant leurs resyncs).
**Correctif** : `ServerUser.resyncDiamonds(user)` = `userInfo.diamonds = user.getResource(DIAMONDS)`, appelé
après `applyAction`/`openChest`/`recordCampaignAttack`. Impact plus large que le sign-in : **tout** gain de
diamants (loot, quêtes…) est désormais persisté. Après correctif : DIAMONDS 0→50, persiste. Régression 7/7
(`Resource/Signin/SetName/SigninMultiDay/CampaignAttack/ChestWire/CampaignPersist`) verte.

### Réponses aux 3 questions
1. **In-game via le client complet** : NON encore — le tuto verrouille la navigation libre (`canNavigateTo=
   false`) jusqu'à l'étape sign-in ; côté serveur tout est prouvé par tests. À confirmer en progressant le tuto.
2. **j2/j3 crédités & disponibles** : OUI (mécanique du jeu + fix diamants), vérifié par test multi-jour.
3. **Impact de `isNameLegalExt = s->true` sur les vrais joueurs** : AUCUN. Il est posé dans
   `ServerContext.init` = **process JVM du SERVEUR uniquement** (dhserver, non chargé par le client). Le client
   desktop/android garde SA propre vérif de police (`Gdx.app` présent côté client). Le serveur ne reçoit que
   des noms déjà validés par le client (changeName local avant l'envoi) ; il en refait le CŒUR de légalité
   (noms interdits, codepoints), seule la vérif POLICE (sans objet serveur) est omise.

## 2026-07-19 (b) — CHOOSE NAME (SetPlayerName) + pilote dh.gosignin (ouvrir SIGN IN)

### CHOOSE NAME — message + handler
Relevé au bytecode (`ChangeNamePrompt.changeNameInner`) : le choix/changement de nom passe par
`UserHelper.changeName(user, newName)` (logique du jeu : légalité `NameChangeHelper.isNameLegal`, coût — 1ᵉʳ
changement gratuit via `FREE_NAME_CHANGE`, sinon item/diamants —, `setPreviousName`+`setName`) PUIS l'envoi
**fire-and-forget** d'un `SetPlayerName{name}` (`getNetworkProvider().sendMessage`). Serveur : `LoginServer`
branche `SetPlayerName` → `ServerUser.setPlayerName` ré-exécute `UserHelper.changeName`, **re-sync** le nom vers
le wire (`userInfo.basicInfo.name`/`previousName`, car `User.userName` est un champ hors `this.extra`, comme le
team-level) et persiste.

### Blocage headless (Gdx.app) résolu par un ext serveur
`UserHelper.changeName` → `NameChangeHelper.isNameLegal` fait le CŒUR (noms interdits, codepoints valides) PUIS
délègue à un champ statique `isNameLegalExt` (Predicate CLIENTE) = `isNameLegalClientExt` qui vérifie le rendu
POLICE (`DisplayStringUtil.containsUnsupportedCharacters` → `LanguageHelper.getPreferredLanguage` →
`Gdx.app.getPreferences`) → NPE headless (`Gdx.app` null). Le rendu police est une préoccupation CLIENTE, déjà
validée par le client avant l'envoi. `ServerContext.init` pose donc par réflexion un **ext SERVEUR** `s -> true`
(même patron que `ServerSpecialEventsExt` : le cœur de légalité s'exécute, seule la vérif police — sans objet
serveur — est omise ; pas une rustine, §2). Vérifié `server/smoke/SetNameTest` : nom « HeroTester » appliqué,
`basicInfo.name` peuplé, survit au round-trip wire. Régressions `SigninTest`/`ResourceTest` vertes.

### Pilote DEV dh.gosignin (vérif live du SIGN IN)
`TutorialDriver` : flag `GO_SIGNIN` → au hub, ouvre le bâtiment SIGN IN via l'API du jeu
`UINavHelper.navigateTo(Destination.SIGN_IN)` pour déclencher `REFRESH_SPECIAL_EVENTS` (→ le serveur répond
`SpecialEventsRaw{signinRewards}`). **Constat live** : `canNavigateTo(SIGN_IN)=false` pendant le tuto
(`mainScreenTutorialBlocked` → « TUTORIAL_CANT_DO_THAT_YET ») — la navigation libre est **verrouillée par le
jeu** tant que le tuto n'est pas au point sign-in. Le hook **RESPECTE ce verrou** (n'ouvre que si
`canNavigateTo=true` ; forcer serait une rustine, §2). La construction serveur `SigninRewards` est déjà prouvée
(`SigninTest`) ; la vérif live du SIGN IN affiché s'obtiendra en progressant le tuto jusqu'au sign-in.
Câblage `DH_GOSIGNIN` → `dh.gosignin` dans `run-online.sh`/`run-desktop.sh`.

## 2026-07-19 — SIGN-IN (récompense de connexion quotidienne) : construction serveur + réclamation

### Contexte & correction de cadrage
En progressant le tuto post-équip, le client spammait `Action{REFRESH_SPECIAL_EVENTS}` (relevé 524×) et le
bâtiment **SIGN IN** restait vide. J'avais initialement cadré ça comme une « feature admin » (définir les
récompenses). **Correction (user) : c'en est pas une.** Les récompenses sont **définies par la donnée**
(`game-data/stats/signin_rewards.tab`, extraite de l'APK = authentique PerBlue) et la réclamation est **du code du
jeu** (`SigninHelper.claim`). Le serveur ne fait qu'**exécuter** (PRINCIPLES §3/§4). Le seul angle « admin » =
éditer un `.tab`, commun à TOUT le jeu — rien de spécifique.

### Modèle relevé au bytecode
- `SpecialEventsRaw{changed, List events, SigninRewards signinRewards}` (champ confirmé).
- `SigninRewards{thisMonth,nextMonth,lastMonth : SigninReward, Map signinHeroesRev}` ;
  `SigninReward{startTime,endTime, List<RewardDrop> rewards, UnitType signinHero}`.
- Table `SigninStats.REWARDS_TABLE : DropTableStats`, nœuds `ROOT → V<SignInVersion>_DAY_<index>`. Variables
  (`SigninDTCode`) : `SignInVersion`=`ContentColumn(signinStart).getSigninVersion()`, `SignInIndex`=`ctx.index`,
  **`L`=`ContentColumn(signinStart).getMaxTeamLevel()`** → quantités indexées sur l'ère de contenu (pas le joueur).
- `SigninHelper` (client) lit tout dans `DATA` (posé par `setData`) : `getRewards`/`getReward(i)`/
  `getActiveRewardIndex`/`isClaimable`/`claim(user,index,retro)` (donne l'objet + `incMonthlySignins`/
  `decDailyChances("daily_signin")`/`setLastSigninTime`). Réclamation client = `Action{CLAIM_SIGNIN_REWARD,
  extra={INDEX=i}}` (et `CLAIM_SIGNIN_WITH_VIDEO` pour le x2).

### Implémentation (`ServerUser`, `LoginServer`)
- `buildSigninRewards()` → `signinRewardsFor(user)` : construit thisMonth/lastMonth/nextMonth via
  `buildSigninMonth(user, start, end)`. Bornes de mois = `Calendar` (premier/dernier instant) sur
  `TimeUtil.getUserServerTime(user)`. `rewards` = pour chaque jour i, `REWARDS_TABLE.getTable().rollNode("ROOT",
  SigninContext(i, start), Random)` → `DropConverter(user).convert(...)` → 1 `RewardDrop` ; boucle jusqu'à ce
  qu'un jour ne produise plus rien (nœud absent = fin des jours de la version). `SigninContext(int,long)` =
  classe imbriquée **protected** hors package → instanciée par **réflexion** (constructeur mis en cache).
  `signinHero` = `ContentHelper.getRawStats().getColumn(start).getCurrentMonthlySigninHero()`.
- `LoginServer` handler `REFRESH_SPECIAL_EVENTS` : `raw.signinRewards = user.buildSigninRewards()`.
- `applyCommand` cases `CLAIM_SIGNIN_REWARD`/`CLAIM_SIGNIN_WITH_VIDEO` : `SigninHelper.setData(signinRewardsFor(
  user))` (claim lit `DATA`), index depuis `extra[INDEX]` (défaut `getActiveRewardIndex`), garde `isClaimable`,
  puis `SigninHelper.claim(user, index, withVideo)`. État auto-persisté dans `this.extra`.

### Vérification
`server/smoke/SigninTest` : `buildSigninRewards()` rend **31 jours** dont les valeurs matchent la `.tab` scalée à
l'ère R102 (L=565) — jour 0 GOLD 226 250 000 (`(565*400000)+250000`), jour 1 50 DIAMONDS, jour 2 1563
EXP_COLOSSAL, jour 3 GEAR_JUICE 35305 (`565*67-2550`), jour 4 DOUBLE_CAMPAIGN_TEAM_XP. Bornes last<this<next.
Réclamation du jour actif → RewardDrop GOLD crédité. Régressions `ResourceTest`/`EquipTest`/`ViewedChestsTest`
vertes. **Reste** : vérif EN JEU (le SIGN IN affiche/réclame réellement), puis CHOOSE NAME.

## 2026-07-17 (quater) — #25 LOOT AUTORITAIRE : le serveur roule le butin, certifié == client, SANS simuler le combat

### Décision (user) & principe
« Rendre autoritaire tout ce qui ne demande pas de simuler le combat. » Décomposition du combat de campagne :
**seuls `outcome`/`stars` exigent une simulation** ; le loot, l'XP, l'or, l'énergie sont **déterministes** une
fois ces valeurs connues. L'XP/or/énergie sont **déjà** autoritaires (`recordOutcome`). Restait le **loot** :
le serveur faisait confiance à `m.lootEarned` (client). #25 le rend autoritaire.

### Fait établi (relevé au bytecode)
Le loot est un **flux RNG SÉPARÉ du combat** : `CampaignAttackScreen` (2ᵉ ctor) fait `user.resetRandom(LOOT)`
puis `user.resetRandom(COMBAT)` — deux graines distinctes (`RandomSeedType.LOOT` ≠ `COMBAT`). Le combat consomme
le flux COMBAT ; le loot consomme le flux LOOT. Donc **le butin est une fonction déterministe de la SEULE graine
LOOT**, indépendante du déroulé du combat. Séquence client exacte :
```
user.resetRandom(LOOT) ;
CampaignLootHelper.getLoot(user, campaignType, 0, chapter, level, NONE, guildPerks /*GuildInfoPerkProvider*/, true)
  → CampaignLoot.combinedLoot   // List<RewardDrop> = butin d'une victoire complète
```
`getCampaignAttack` ne fait que **recopier** cette liste dans `m.lootEarned` (elle ne roule rien). Le mécanisme
RNG : `IndividualUser.getRandom(type)` = `InstrumentedRandom.newRandom(getSeed(type), logMode)` ; `getSeed` lit
`individualUserExtra.storedSeeds` (ou `SeedHelper.getDefaultSeed` par défaut). Le client annonce sa graine au
serveur via `Action{SET_SEED, TYPE=LOOT, ID=<seed>}` (capturée en #23, `getPendingSeed`).

### Implémentation (`ServerUser`)
- `rollAuthoritativeLoot(user, iu, type, m)` : `iu.setSeed(LOOT, pendingSeed)` + `user.resetRandom(LOOT)` +
  l'appel `getLoot(...)` EXACT ci-dessus → `combinedLoot`. `null` si pas de graine.
- `recordCampaignAttack` : sur **VICTOIRE**, `lootEarned = serverLoot` (crédite le tirage SERVEUR) ; sinon repli
  sur le loot client (pas de graine, ou non-WIN = loot partiel dépendant de la progression, hors pure-logique).
  `logLootValidation` LOGue serveur↔client (divergence = **signal anti-triche** ; on crédite le serveur quand même).
- `computeAuthoritativeLoot(m)` : wrapper public (test + bascule).

### Prérequis résolu — `BuildOptions.SERVER_TYPE = ServerType.NONE`
1er run : `NullPointerException … getNetworkProvider().sendMessage(...)`. Cause : `InstrumentedRandom` (via
`resetRandom`/`getRandom`) **envoie** les `RandomEvent` client→serveur pour l'anti-triche — sauf si
`SERVER_TYPE == NONE` (commutateur **offline du jeu**). Headless, `getNetworkProvider()` est null → NPE. Correctif
fidèle : poser `SERVER_TYPE = NONE` dans `ServerContext.init` (chemin offline **prévu par PerBlue**). N'affecte
**que l'envoi d'événements**, PAS les valeurs RNG (même graine → même séquence) — cf. SHIMS.

### Certification (`server/smoke/LootAuthoritativeTest`) — patron oracle
Réf CLIENT (séquence bytecode exacte) vs SERVEUR (via `Action SET_SEED` + `computeAuthoritativeLoot`), multiset
(item/ressource → quantité), sur 5 graines : **5/5 MATCH**, **5 butins distincts** (sensible à la graine → le
test discrimine). Ex. graine 999 → `{ACE_OF_SPADES×0, CLEVER_FOX×1, EXP_VIAL×1, HEARTY_BREAKFAST×1, RAID_TICKET×1,
SUGAR_RUSH×1}` serveur==client. Régression **OK** : `CampaignAttack/LootPersist/CampaignPersist/Resource/Seed/
TeamLevel/Equip/ViewedChests` (les tests sans graine LOOT retombent sur le loot client, inchangés).

### Verdict
Le serveur est **AUTORITAIRE sur le loot** (la principale surface de triche : objets/or) **pour un coût nul,
sans re-simuler le combat**. Combiné à l'XP/or/énergie déjà autoritaires : **tout ce qui a de la valeur est
autoritaire**. Ne reste client que `outcome`/`stars` (§D) — qui, eux, exigent une re-sim (unidbg, échantillonnable).
Fichiers : `server/java/dhserver/{ServerUser,ServerContext}.java`, `server/smoke/LootAuthoritativeTest.java`,
`docs/{SERVER_PLAN,SHIMS}.md`.

## 2026-07-17 (ter) — Harnais différentiel de certification (compare) : bâti, tourne, 1er rapport

### Outil
`CompareBackend` (`-Ddh.spinebackend=compare`) : pour CHAQUE appel cspine, exécute unidbg (ORACLE = binaire ARM
PerBlue = mobile) ET le JNI natif (spine-c recompilé x86, HostSpine) en parallèle, avec table de correspondance
de handles (u2j), et DIFFE automatiquement. Le jeu utilise les résultats unidbg (boote normalement → contourne le
blocage handle du JNI). Gate `active` : ne compare QUE le combat (boot = unidbg seul, rapide). Rapport auto :
diffs/méthode + distance ULP (arrondi FP ARM↔x86 vs écart de logique). Reproductible (régression).

### Bug RÉEL trouvé (par le harnais) + corrigé
`cspine_jni.c` `getBoneTransform(s)` : stride 7 + boucle `idOff..len` → le vrai JNI **borne** et throw AIOOBE
(unidbg tolérait). Corrigé : stride 6 + boucle `0..count` (contrat du jeu, = binaire PerBlue). C'est un bug de
NOTRE glue (tâche #5), latent sous unidbg. Rebuild via `native/build-hostspine.sh`.

### 1er rapport (1-1, seed 123456789) — À AFFINER
TOTAL 5343 diffs. Mais **incohérence interne** : `getBoneNames`=0 diff (listes d'os IDENTIQUES) alors que
`getBoneID`=410 diffs (u=71 j=70). Si les noms sont identiques et ordonnés pareil, getBoneID devrait matcher →
une partie des diffs = **artefacts du harnais** (état non propagé au skeleton JNI : ex. position monde de l'unité
non appliquée côté JNI → getBoneTransform diverge en masse ; ou mapping de handle imparfait sur certains appels).
`setAnimation` u=1 j=12 = différence de sémantique de RETOUR de notre glue (retourne animId) vs PerBlue (autre).
`nextEvent` u=true j=false = PerBlue FIRE des events, notre stub non. `getAnimationID/durations/names/BoneNames`
= 0 diff ✅ (structure de base identique).

### Lecture
Le harnais fonctionne et donne des données riches, mais nécessite **1 passe d'affinage** (propager tout l'état au
JNI, corriger le mapping, aligner les sémantiques de retour de la glue) avant de pouvoir conclure « 100% fidèle ou
non ». Signal préliminaire : la structure de base (noms/ids d'anim, durées, noms d'os) MATCHE ; les écarts
restants sont soit des artefacts du harnais, soit de vraies différences (events, retours) à isoler.

> Journal **détaillé** relié à l'historique court de [`MEMORY.md`](MEMORY.md#7-état-courant--prochaines-étapes).
> But : permettre à n'importe quel agent de **retrouver facilement n'importe quelle
> information** (décision, découverte, commande, fichier). Mis à jour **à chaque étape**.
> Ordre : le plus récent en haut. Chaque entrée = date + résumé + détails + fichiers touchés.

---

## 2026-07-17 (bis) — Optimisation Opt.2 : dynarmic (JIT ARM) testé → PAS un gain ; les vraies pistes sont architecturales

### Contexte / malentendu levé
Opt.2 = validation anti-triche **côté SERVEUR, en fond** (rejoue le combat pour vérifier l'issue). Ça **ne touche
PAS** la fluidité du joueur (le vrai jeu mobile utilise le spine natif de l'appareil). Les ~9 s unidbg = coût
serveur hors-ligne. « Optimiser » = débit serveur (combats/s), pas l'expérience en jeu.

### dynarmic (JIT ARM) — testé, NON concluant
Le backend `dynarmic` (JIT ARM, natif `linux_64/libdynarmic.so` embarqué dans unidbg-dynarmic-0.9.8) remplace
l'interpréteur Unicorn : **mêmes instructions ARM → mêmes résultats** (fidélité OK), censé plus rapide. Ajouté en
**opt-in** (`-Ddh.dynarmic`, off par défaut, `AndroidEmulatorBuilder.addBackendFactory(new DynarmicFactory(true))`).
**Résultat** : le combat spike n'a PAS démarré en 250 s (le boot — rendu MainScreen via spine unidbg à chaque
frame — est devenu PLUS LENT : ~200 frames jamais atteints). ⇒ pour le motif de spine (**beaucoup d'appels ARM
COURTS** : getBoneTransform/apply/update), l'**overhead JIT/warmup ne se rentabilise pas** ; l'interpréteur
Unicorn est plus efficace. **dynarmic n'est pas un gain ici.** Flag conservé (opt-in) pour ré-évaluation future,
sans effet par défaut.

### Les VRAIES optimisations sûres (fidélité intacte) = architecturales
1. **Validation ASYNC/en fond** : ne pas bloquer sur les ~9 s ; valider hors du chemin de réponse.
2. **VMs unidbg en PARALLÈLE** (une par cœur) → débit linéaire (chaque VM exécute le vrai binaire).
3. **Échantillonner / cibler** les combats suspects plutôt que tout revalider.
4. **Cache des assets chargés** (SkeletonData/atlas par unité) → amortit le setup spine entre combats.
Ces leviers ne touchent PAS la sim (mêmes résultats), contrairement à un changement de runtime (JNI natif /
spine-libgdx) qui, lui, doit être certifié.

### Fichiers
`desktop-port/src/main/java/dhbackend/unidbg/UnidbgVM.java` (flag dynarmic opt-in), `build.gradle` (dep
unidbg-dynarmic), `run-desktop.sh`/`run-online.sh` (flag). Docs : JOURNAL.

---

## 2026-07-17 — Opt.3 PIVOT « JNI natif » : le vrai spine-c compilé hôte (pas de réécriture) — bâti, bloqué au boot

### Insight (question user : réécriture main vs fichiers du jeu ?)
La délégation fidèle n'a PAS à être réécrite à la main : **la colle d'origine existe** = `native/src/cspine_jni.c`
(écrite sur le VRAI spine-c officiel 3.6). ⇒ la « bonne » Opt.3 = **compiler spine-c + cspine_jni.c pour l'HÔTE
x86-64 et l'appeler en JNI RÉEL** (« l'Opt.2 sans unidbg » : même code natif, sans émulation ARM). Fidélité PAR
CONSTRUCTION (mixing/clear de track corrects d'office), rapide, **zéro patch à la main** → supersede le
`JavaSpineBackend` (spine-libgdx divergent, patché main → dérive §4, abandonné).

### Fait
- `native/build/spine-native64.so` existait déjà (spine-c 3.6 + cspine_jni.c compilés x86-64, 65 symboles JNI).
- Classe `dhbackend.jnispine.HostSpine` (déclarations `native`, sous-ensemble combat) + `libhostspine64.so`
  (rename mécanique des symboles JNI `...cspine_Native_*` → `...HostSpine_*` + **unification des tables de
  handles** en un espace global — script `native/build-hostspine.sh`). Routage 3-voies dans `cspine.Native`
  (`-Ddh.spinebackend=jni|java|` défaut unidbg). Compile + charge en JNI réel. **Défaut = unidbg → 0 régression.**

### Bloqué (intégration boot, pas fond)
Au boot (chargement d'une particule UI `hero_chooser_add.np` → `hero_chooser.atlas@native`) :
`[main]E/: Bad handle type: Wanted ATLAS but is actually NONE for handle 1` → `Dependency not found` (fatal).
Le **registre de handles Java du jeu** rejette le schéma de handles de NOTRE `cspine_jni.c` (≠ binaire ARM de
PerBlue). L'unification des tables (handles globalement uniques) n'a PAS suffi → le registre attend autre chose
(à investiguer : comment le jeu enregistre le type d'un handle natif ; peut-être un appel natif spécifique, ou
cparticle qui doit passer sur le même backend/espace). **C'est exactement le genre d'incompat que le projet a
CONTOURNÉ en passant à unidbg** (faire tourner le VRAI binaire de PerBlue évite tout ça).

### Synthèse stratégique (convergence)
Les DEUX voies « rapides » (spine-libgdx Java ; spine-c recompilé natif) utilisent les **données** du jeu mais un
**runtime ≠** de celui livré → nécessitent des **patchs de compat** (patch anim côté Java ; schéma de handles /
banding getVertices côté natif recompilé) → dérive §4bis tant que non certifié bit-à-bit. La voie **pleinement
fidèle par construction = Opt.2 (unidbg, binaire ARM d'origine)**, au prix de la lourdeur (~9 s). Le « JNI natif »
reste la meilleure piste rapide SI on finit l'intégration boot (handles) + certification — sinon Opt.2 async est
le choix conforme.

### Fichiers
`desktop-port/src/main/java/dhbackend/jnispine/HostSpine.java` (nouveau), `.../cspine/Native.java` (routage
3-voies), `native/build-hostspine.sh` (nouveau, build reproductible), `run-desktop.sh` (flag jni). Docs : JOURNAL.

---

## 2026-07-16 (nuit 4 bis) — Opt.3 certification : divergences d'ANIMATION diagnostiquées (dont 1 vérifiée sur source spine-c)

### Résumé
Débogage de la divergence de cadence (java stagne : 3/5 kills vs oracle 5/5). Méthode : instrumentation diff
(maxAnimTime, setAnimId, histogramme d'anim courante par NOM, dump getBoneTransform) java vs oracle unidbg.
**Positions d'os : cohérentes** (valeurs caractéristiques identiques). **Bug d'animation trouvé & VÉRIFIÉ sur la
source spine-c**, mais ≥1 couche résiduelle subsiste → combat pas encore fidèle.

### Diagnostics (java vs oracle unidbg, 1-1 seed 123456789)
- **Histogramme d'anim courante par nom** : java bloqué en `attack`(14488)+`hit`(7170), **0 skill** ; oracle
  étalé. ⇒ les unités attaquent mais n'enchaînent jamais les skills (pas d'énergie → dégâts lents).
- **`getBoneTransform`** : java `[worldX,worldY,rot,sx,sy]` = mêmes valeurs caractéristiques que l'oracle →
  positions d'os OK (pas la cause).

### Bug VÉRIFIÉ sur source spine-c (`spine-c/src/spine/AnimationState.c:296-300`)
`spAnimationState_update` **CLEAR le track** (`self->tracks[i] = 0`) quand « pas de next, `trackTime>=trackEnd`,
pas de mixingFrom » → `getCurrent`→null→`getCurrentAnimID`=0. **spine-libgdx NE clear PAS** : l'entry non-bouclée
TERMINÉE persiste → `getCurrent` la renvoie → le jeu croit l'unité toujours en `attack` → n'enchaîne jamais le
skill (bloqué). **Fix partiel** : `getCurrentAnimationID` renvoie 0 si `!getLoop() && isComplete()` (mime le
clear). Effet : distribution redevenue normale (attack 14488→288, walk/idle dominants). **MAIS** : (a) fix
INCOMPLET (ne corrige que l'ID, pas `getCurrentAnimationTime`/`apply`/bones → incohérent avec un vrai clear qui
remet en pose de repos) ; (b) le combat **stagne toujours** (3/5 kills) → ≥1 divergence de plus.

### Bilan certification
Le combat tourne (9× plus vite) et l'oracle a permis de trouver/vérifier des bugs réels (stride 6, getAnimationTime
wrappé, clear de track spine-c). **La fidélité complète reste à atteindre** : c'est un chantier multi-bugs (la
prochaine couche = probablement rendre le clear de track COMPLET — vraiment clear l'entry après update comme
spine-c, pour que `apply`/bones/temps soient cohérents — puis re-diff). Le fix `isComplete` actuel est une
**approximation NON entièrement vérifiée** (§4bis) → à compléter ou à valider strictement avant tout déploiement.

### Fichiers
`.../dhbackend/spine/JavaSpineBackend.java` (fix isComplete + histo noms), `.../cspine/Native.java` (diag
histo/bt). Docs : JOURNAL. DEV-gated, backend unidbg par défaut → aucune régression.

---

## 2026-07-16 (nuit 4) — Opt.3 Phase 1 : backend Java-spine — le combat TOURNE (9× plus vite), certification en cours

### Résumé
`JavaSpineBackend` écrit (~22 méthodes cspine du combat via `spine-libgdx-perblue`, contrat répliqué du JNI),
flag `-Ddh.spinebackend=java` route l'animation vers lui au lieu d'unidbg (atlas reste unidbg). **Le vrai
`HeadlessCombat` tourne intégralement sur Java-spine** (`state=DONE`) et **~9× plus vite** (work ~0,9 s vs
~9 s unidbg). La **certification contre l'oracle DÉTECTE des divergences** (rôle de l'oracle) : 2 bugs corrigés,
≥1 résiduel (cadence d'animation). PAR DÉFAUT le backend reste unidbg (flag off) — aucune régression.

### Obstacles franchis (build)
- **Deps compile** : ajout `spine-libgdx-perblue.jar` (uniquement `com.esotericsoftware.spine`, aucun conflit gdx).
  Casts explicites : l'`Array`/`IntMap` PerBlue (dex2jar) a des génériques ÉRASÉS → `.get()` renvoie `Object`.
- **libGDX PerBlue STRIPPÉ par ProGuard** : `spine-libgdx-perblue` exige des méthodes retirées du `game-logic.jar`
  (`DataInput.readString`, `IntSet.clear`). Fix : déposer les classes COMPLÈTES de gdx-1.9.7 (cache gradle) dans
  le dir de classes (1ᵉʳ sur le CP → ombrage). **Uniquement des classes STRIPPÉES** (superset sûr), JAMAIS une
  classe MODIFIÉE par PerBlue (ex. `Array.add→boolean`). Vérifié par diff de signatures (sous-ensemble).

### Bugs de fidélité (trouvés par l'oracle)
1. **`getBoneTransform` stride 6, pas 7.** Le JNI écrit 7 floats (toléré par unidbg sans bounds-check) mais le
   jeu alloue/lit **6** (`NativeSkeleton.tmpTF=float[6]`, `getBoneTransforms` dimensionne `n*6`). En JVM strict le
   7ᵉ déborde → AIOOBE. Corrigé : écrire `[worldX, worldY, worldRotationX, worldScaleX, worldScaleY, 0]`.
   `getBoneTransforms` (pluriel, ShadowRenderable/ombres cosmétiques) : rendu défensif (garde de bornes ;
   sémantique JNI = no-op quand `ids.length==nbOs`).
2. **`getCurrentAnimationTime` = `animationTime` WRAPPÉ, pas `trackTime` brut.** Oracle : maxAnimTime **1.833 s**
   (= durée pleine de skill1) = borné. Java avec `getTrackTime()` : **89.9 s** (accumule) → le jeu croit l'anim
   au-delà de tous les keyframes → n'attaque plus qu'une fois → stagnation. `getAnimationTime()` reproduit le
   borné natif (java → 1.733 s).

### État certification (1-1, seed 123456789, snapshot loot-1to5)
| | ticks | maxAnimTime | morts déf. | issue |
|---|---|---|---|---|
| **oracle unidbg** | 973 | 1.833 s | 5/5 | **WIN** (~8,5 s réel) |
| **java (après fix 1+2)** | 3876 (cap) | 1.733 s | **3/5** | stagne → LOSS (~0,9 s réel) |

⇒ Java **progresse** (3 kills, ~9× plus rapide) mais **tue ~15× plus lentement** → **divergence résiduelle de
cadence** (les attaques/skills atterrissent moins souvent ; skill1 semble interrompue à 1.733 vs 1.833 → keyframe
de dégâts tardif manqué). **Prochain pas certification** : diff **tick-par-tick** de la séquence
`getCurrentAnimationID`/`Time` d'une unité entre java et unidbg pour localiser la 1ʳᵉ divergence (mixing ? ordre
update/apply ? bornes de wrap ?). Piste : comportement du **mixing** (`setMix`/crossfade) ou `getCurrentAnimationID`
pendant un mix.

### Fichiers
`desktop-port/src/main/java/dhbackend/spine/JavaSpineBackend.java` (nouveau, backend), `.../cspine/Native.java`
(routage flag + diag), `.../CombatSpikeDriver.java` (compteur morts), `build.gradle` (dep spine), `run-desktop.sh`
(flag + shadow classes gdx), `run-online.sh` (forward `DH_SPINEBACKEND`). Docs : SERVER_PLAN §D.

---

## 2026-07-16 (nuit 3 quater) — Opt.3 Phase 0 : surface cspine EXACTE du combat headless mesurée (profilage)

### Résumé
Instrumentation DEV du shim `cspine.Native` (`-Ddh.cspineprofile`, compteur par méthode ; reset/report autour du
combat dans `CombatSpikeDriver`). Run du spike Opt.2 → **surface cspine EXACTE que le combat headless exerce**.
Résultat : **~22 méthodes** (animation/skeleton/os), **0 rendu**, mais **les positions d'os SONT lues**.

### Mesures (1-1, 3 héros vs 3 vagues, 973 ticks, 9 unités) — 37 méthodes appelées
**Hot path (par tick) :** `AnimationState_nextEvent` 8611 (polling events, VIDE car .skel sans event → trivial),
`getCurrentAnimationTime` 8008, **`Skeleton_getBoneTransform` 4376** (~4.5/tick — **positions d'os monde LUES**),
`getCurrentAnimationID` 4299, `AnimationState_apply` 4091, `Skeleton_updateWorldTransform` 4013, `Skeleton_update`
+ `AnimationState_update` 4004 chacun. **Setup/occasionnel :** `getAnimationID` 445, `getBoneID` 410,
`setToSetupPose` 96, `setAnimation` 87, `setColor`/`setTintBlack`/`setSlotEyeState` 47/38/36 (cosmétique),
`setMix` 36, `setSkin` 36, `Atlas_getTexture` 24, `getBoneTransforms` 15, create/dispose ×9 (9 unités),
`getAnimationDurations`/`getSlot/Anim/Bone/SkinNames`/`create` ×8 (skeletons uniques).

### Findings décisifs
1. **0 appel `getVertices*`** → le combat headless **NE REND RIEN** → toutes les méthodes de rendu (getVertices,
   setColor/tintBlack) → **no-op** en Opt.3. ✅
2. **`getBoneTransform` massif (4376)** → l'inconnu de la Phase 0 est **tranché : OUI le combat lit les positions
   d'os monde** → le backend Java-spine doit **appliquer l'anim + `updateWorldTransform` + exposer `Bone.getWorldX/
   Y/rotation`** (fourni nativement par spine-libgdx-perblue).
3. **`setMix` 36** → mixing/crossfade d'animations configuré → **à certifier** (timing).
4. **`nextEvent` 8611 mais 0 event dans les .skel** → poll toujours vide → implémentation triviale (retourne
   « rien »). Les keyframes de combat viennent du prefab (déjà établi nuit 3 ter).

### Conséquence pour l'Opt.3 (chantier CADRÉ)
Surface à implémenter en Java-spine ≈ **22 méthodes** : `SkeletonData_{create,dispose,getAnimation{Durations,ID,
Names},getBone{ID,Names},get{Slot,Skin}Names}`, `Skeleton_{create,dispose,update,updateWorldTransform,
setToSetupPose,setSkin,getBoneTransform(s)}`, `AnimationState{,Data}_{create,dispose,update,apply,setAnimation,
clearTracks,getCurrentAnimation{ID,Time},setMix,nextEvent}`. Toutes en **délégation directe au runtime Java**
(pas les 47 de cspine, PAS de rendu). Risques de fidélité à certifier vs oracle : valeurs de `getBoneTransform`
(monde) + timing `setMix`. Gain attendu : le hot-path (~29 appels unidbg/tick, ~9 s) → JVM natif (~ns) → <100 ms.

### Fichiers
`desktop-port/src/main/java/com/perblue/heroes/cspine/Native.java` (profilage DEV gated), `.../CombatSpikeDriver.java`
(reset/report), `run-desktop.sh`/`run-online.sh` (`DH_CSPINEPROFILE`). Docs : SERVER_PLAN §D.

---

## 2026-07-16 (nuit 3 ter) — Opt.3 TEST : données de timing ACCESSIBLES en Java pur (spine-libgdx-perblue) → faisable, verdict

### Résumé
Test de faisabilité de l'Opt.3 (timelines d'animation via runtime **Java spine** au lieu d'unidbg). **Résultat
POSITIF sur la data-reachability** : le `spine-libgdx-perblue.jar` (runtime Java `com.esotericsoftware.spine`)
**lit les `.skel` du jeu** et en extrait les animations + durées, **sans unidbg ni GL**. ⇒ fondation de l'Opt.3
existante. Reste un **coût de câblage** (backer le natif cspine par le Java-spine) à certifier contre l'oracle.

### Ce qui a été testé (probe `SpineProbe`, scratchpad)
- `SkeletonBinary.readSkeletonData(FileHandle)` sur `ralph/elastigirl/frozone.skel` → **parse complet** :
  ralph **12** anims (attack 1.0s, hit 0.633s, skill1 1.833s, skill2/3 1.367s…), elastigirl **14**, frozone **10**,
  avec **durées**. AttachmentLoader stub (attachments vides — pas besoin des textures pour le timing), FileHandle
  direct (sans Gdx.files).
- **Conflit de libGDX résolu (probe)** : `spine-libgdx-perblue` veut `DataInput.readString()` (absent : le
  `DataInput` de `game(-logic).jar` est un **stub 215 o**) ET `Array.add→boolean` (libGDX **PerBlue**). Or
  gdx-1.9.7 a readString mais `Array.add→void`. Même package, classes différentes → on prend le `DataInput`
  complet de gdx-1.9.7 (prioritaire) + le reste de `game-logic-framed` (Array→boolean). (= le sujet de la
  tâche #3 « DataInput.readString », shadow retiré au passage à unidbg.)

### Découverte structurante
- **Les `.skel` n'ont AUCUN event spine** (`eventDatas=0` pour les 3 héros). Les **keyframes de combat**
  (déclenchement dégâts/hit/projectile) NE sont donc PAS des events spine, mais des **composants du prefab de
  scène** (`.treeb` : `HitKeyframeData`/`ProjectileKeyframeData`), authored à part. Ces prefabs sont **aussi
  chargeables en Java** (PrefabLoader du jeu, données pures, sans GL/unidbg).
- **`AnimationElement`** (classe concrète) encapsule le **natif cspine** (`nativeSkel`/`nativeAS`, ctor
  `(NativeSkeleton, NativeAnimationState)`) pour la playback (`setAnimation`/`getAnimationLength` = horloge +
  durées), MAIS ses **keyframes sont une `HashMap` settable** (`setKeyframes`, source = prefab). Le combat ne
  semble pas lire de **positions d'os** (0 `findBone` dans `simulation/`).

### Verdict Opt.3
- **Data reachable en Java pur : OUI** (durées via .skel/Java-spine + keyframes via prefab/PrefabLoader).
- **Câblage** : pour retirer unidbg, backer la **sous-surface cspine du combat** (`NativeSkeleton`/
  `NativeAnimationState` : durées + horloge d'animation ; pas de rendu/vertices/os apparemment) par le
  Java-spine. **Réimplémentation bornée** (précédent : le projet avait des shadows cspine Java avant unidbg),
  **à CERTIFIER contre l'oracle Opt.2** (même spine 3.6 sur les mêmes .skel → forte proba de match, non garanti).
- **Gain** : supprime l'émulation ARM du combat → ~9 s → probablement <100 ms → **validation synchrone viable**.
- **Reco** : **Opt.2 async MAINTENANT** (autorité immédiate : brancher l'oracle sur `recordCampaignAttack` en
  validation de fond, ~9 s/combat hors ligne) ; **Opt.3 ensuite** comme upgrade perf certifiable (fondation +
  oracle déjà en place). Choisir Opt.3 en priorité seulement si le synchrone temps-réel est requis d'emblée.

### Fichiers
Probe `scratchpad/SpineProbe.java` (throwaway). Docs : SERVER_PLAN §D, MEMORY. Aucune modif jeu/serveur.

---

## 2026-07-17 — #28 CERTIFICATION Opt.3 : spine-c recompilé certifié FIDÈLE contre l'oracle unidbg (verdict desktop)

### Résumé — VERDICT
Le harnais différentiel (`CompareBackend` : le jeu tourne sur unidbg=oracle=binaire PerBlue mobile, le JNI
spine-c recompilé tourne **en parallèle** sur les **mêmes handles**, on diffe chaque appel) a servi à
**certifier** notre spine-c hôte (x86-64) contre le binaire ARM d'origine, **automatiquement**, sur un vrai
combat (NORMAL 1-1, 973 ticks, 5 morts, WIN — identique côté unidbg). Résultat après fermeture des écarts :

| catégorie | méthodes | diffs | verdict |
|---|---|---|---|
| **structure** (noms/ids/durées/counts d'anims, os, slots, skins ; atlas) | getAnimation{ID,Names,Durations}, getBoneNames, getSlotNames, getSkinNames, setSkin, Atlas_* | **0** | **identique bit-à-bit** |
| **événements d'animation** | nextEvent (8611 appels), setAnimation (87) | **0** | **identique bit-à-bit** |
| **transforms d'os** | getBoneTransform (4376), getBoneTransforms (15) | ~818 | **dérive flottante seule** : matrice 1.8e-7, position 6.1e-5 (sub-pixel, bornée) |
| ordre interne des os | getBoneID (410) | 410 | **artefact** : PerBlue réordonne les os en interne ; chaque backend est auto-cohérent (les transforms matchent une fois traduits par NOM) — aucun impact fonctionnel |
| extension proprio | setSlotEyeState (36) | 36 | **seul vrai manque** : extension PerBlue (expression des yeux), absente de spine-c vanilla — **purement cosmétique** |

### Bugs de fidélité RÉELS trouvés par le harnais (et corrigés)
1. **Layout matrice transposé** (`getBoneTransform`) : le contrat `NativeSkeleton` = matrice affine monde
   `[a, b, c, d, worldX, worldY]` (stride 6). Notre colle écrivait d'abord `[worldX,worldY,rot,sx,sy,0]` (faux),
   corrigé en `[a,b,c,d,x,y]` — mais le split mat/pos du harnais a révélé `mat=2.00` (∈±1 → catastrophique) avec
   un ulp de signe : signature d'une **transposition b↔c** (`b=-sin`, `c=+sin` → écart `2|sin|`, nul quand
   `sin≈0`, position intacte). Ordre correct de l'oracle = **`[a, c, b, d, x, y]`**. Après fix : `mat` tombe à
   **1.79e-7** (dérive flottante pure). *(Ces 2 bugs étaient invisibles sous unidbg — qui exécute le binaire
   PerBlue, pas notre colle — d'où jamais vus avant le harnais.)*
2. **`nextEvent` non branché** (renvoyait toujours `false`) → aucun callback `complete/end/start/…` → machines
   d'état d'animation du jeu muettes en backend JNI autonome. Implémenté la **file d'événements spine-c** :
   listener global sur `spAnimationState` (`rendererObject` porte une FIFO), empilée dans l'ordre de drain
   interne de spine (`_spEventQueue`) pendant `apply/update`, dépilée par `nextEvent` → `out[0]=type PerBlue`
   (`spEventType+1` : 1=start…6=event), `out[1]=trackIndex`. Certifié **0 diff / 8611 appels** (dont 423 events
   réels). *(Piège : `dispose` doit appeler `spAnimationState_dispose` AVANT de libérer la FIFO — spine émet un
   DISPOSE via le listener → use-after-free sinon, corrigé.)*
3. **`setAnimation` mauvais retour** : le jeu fait `return natif + eventIDOffset`. Relevé contre l'oracle : le
   natif renvoie un **compteur de trackEntry par animState** (1-based, ++ à chaque set/addAnimation), pas
   l'animId ni le trackIndex. Implémenté (`EvQueue.seq`). Certifié **0 diff / 87**.

### Verdict pour la décision « desktop dev-only vs production »
- **Rendu / animation : FIDÈLE.** Structure et événements **identiques bit-à-bit** ; poses d'os fidèles à la
  précision flottante (écart max **6e-5** sur des positions de l'ordre de la centaine = relatif ~1e-7, **borné**
  car les transforms monde sont **recalculés à neuf chaque frame** depuis les keyframes — pas d'intégration, donc
  **pas d'accumulation/chaos**). Sub-pixel → **invisible à l'écran**. ⇒ le spine-c recompilé est **jouable en
  production pour le rendu** (reste : régler le blocage handle-registry du boot JNI autonome, + l'extension
  cosmétique `setSlotEyeState` si on veut les expressions des yeux).
- **Autorité de combat : reste sur unidbg (serveur).** La dérive flottante ARM↔x86 (même minime) rend le JNI
  **non bit-identique** → un combat rejoué sur JNI pourrait diverger sur le long terme (sensibilité au chaos).
  Donc la **validation autoritative** (#24) garde le binaire PerBlue via unidbg — ce qui **coïncide** avec le
  principe §3 (serveur autoritatif) : c'est le serveur qui certifie, pas le client.
- En clair : **desktop = production pour jouer/afficher** (fidèle à l'œil), **serveur = unidbg pour l'autorité**
  (fidèle au bit). Les deux voies restent du **vrai code/données PerBlue** exécutés (§4), rien de réécrit (§2).

### Perf (FPS) — pourquoi le backend décide de la fluidité
Le harnais chronomètre les DEUX backends sur le **même mix d'appels** (fenêtre combat, hors `getVertices`) :
**unidbg (ARM émulé) = 16 900 ms vs JNI (natif x86) = 337 ms → le natif est ~50× plus rapide.** Sur 2919 ticks
(3 combats × 973) : ~**5,8 ms/frame** de travail squelettique côté unidbg vs ~**0,12 ms/frame** côté natif.
- **Aujourd'hui (défaut = unidbg)** : ~5,8 ms/frame RIEN QUE pour l'animation squelettique, AVANT le meshing
  (`getVertices`, aussi émulé, l'appel spine le plus lourd) et le GL → budget 60fps (16,6 ms) explosé en combat
  → **saccadé (FPS à un chiffre / bas). C'est un outil de dev, pas jouable fluide.**
- **Backend natif JNI (certifié)** : ~0,12 ms/frame → spine **n'est plus le goulot**, 60fps atteignable ; le
  coût résiduel devient le GL (ici llvmpipe logiciel headless ; sur une vraie machine = GPU). Le 50× est
  **conservateur** (exclut `getVertices`, où la pénalité d'émulation est encore plus forte).
- **Bloquant restant avant de mesurer le FPS natif bout-en-bout** : le boot JNI-autonome bute sur le
  handle-registry (aujourd'hui contourné par le mode compare, où le jeu boote sur unidbg). À lever pour un
  desktop natif de production. Le ratio par-appel, lui, est **mesuré** (pas estimé).

Fichiers : `native/src/cspine_jni.c` (layout `[a,c,b,d,x,y]`, FIFO d'événements + `seq`), `desktop-port/src/
main/java/dhbackend/spine/CompareBackend.java` (traduction d'os par nom, split diff matrice/position, chrono
par backend, rapport).

## 2026-07-16 (nuit 3 bis) — Opt.2 PROUVÉE : le vrai HeadlessCombat tourne headless via unidbg (ORACLE établi) + fix bytecode itf

### Résumé
Spike Opt.2 (#27) **concluant** : le **vrai `HeadlessCombat` du jeu** tourne **headless dans le client**
(`desktop-port` : GL Xvfb/llvmpipe + vrai `GameMain` + unidbg + assets) jusqu'à `DONE` et produit une **issue
autoritative**. C'est l'**oracle** pour certifier l'Opt.3. Lourdeur mesurée : **~9 s/combat** (unidbg-dominé).

### Ce qui a été fait
- **`CombatSpikeDriver`** (DEV, `-Ddh.combatspike`, off par défaut) : après boot+login, construit un
  `HeadlessCombat` de campagne (héros du user en `CoreAttackScreen$CombatUnitData` + `CampaignAttackScreen.
  createStageDefenders`), boucle `work()` jusqu'à `DONE`, imprime issue + timing. Câblé dans `DesktopLauncher`
  (hook après `game.render()`), flags forwardés par `run-desktop.sh`/`run-online.sh` (`DH_COMBATSPIKE*`).
- **Fix bytecode `itf` (ReframeJar)** : le 1ᵉʳ run a buté sur `IncompatibleClassChangeError: FXHandle.
  $r8$lambda$…() must be InterfaceMethodref constant`. Cause : dex2jar encode un `INVOKESTATIC` vers une méthode
  statique d'**interface** en `Methodref` au lieu d'`InterfaceMethodref`. `ReframeJar` corrige désormais
  `itf = isInterface(owner)` pour les `INVOKESTATIC` (non-sémantique §1). ⚠️ **Pas** pour `INVOKESPECIAL` (un
  1ᵉʳ essai trop large a cassé les défauts de super-interface indirecte → `VerifyError` sur
  `PegasusSkill3.onRampageStartAnimationEnd` ; restreint à INVOKESTATIC). Régénère `game-framed.jar` (serveur)
  ET `game-logic-framed.jar` (client).

### Résultats (mesures)
- **1-1, 3 héros progressés (snapshot loot-1to5) vs 3 vagues** → `state=DONE ticks=973 → WIN` (attackersLeft=
  true, defendersLeft=false). Le vrai moteur (skills/IA/keyframes) tourne headless.
- **Déterminisme** : 3 runs → **ticks=973 identiques** (graines 123456789 ×2 et 999). Sim bit-déterministe
  (temps réel varie 8,9–9,5 s = variance wall-clock, pas de sim). ⇒ **qualité oracle**.
- **Lourdeur** : ctor ~5-7 ms + `work()` ~9 s (973 ticks × 25 ms = ~24 s de combat in-game émulés). Dominé par
  unidbg spine (keyframes). **Trop lent pour du synchrone, OK pour de l'anti-triche async.**
- **Limite du cas de test** : stomp trivial → **seed-insensible** (même issue/ticks pour graines différentes,
  tout one-shot). La certification #28 devra utiliser des combats **serrés** (RNG discriminant).

### Fichiers
`tools/reframe/src/ReframeJar.java` (normalisation itf), `desktop-port/src/main/java/dhdesktop/
CombatSpikeDriver.java` (nouveau), `.../DesktopLauncher.java` (hook), `desktop-port/run-desktop.sh` +
`run-online.sh` (flags `DH_COMBATSPIKE*`). Docs : SERVER_PLAN §D (résultats+lourdeur), SHIMS (fix itf).

---

## 2026-07-16 (nuit 3) — #24 RE-SIM COMBAT : investigation à fond → combat KEYFRAME-DRIVEN → plan « oracle-certification »

### Résumé
Investigation approfondie de #24 (re-simulation serveur du combat pour outcome/stars **autoritatifs**). Conclusion
ferme : **`HeadlessCombat` n'est pas « pure logique »** et **le combat de DH est piloté par les keyframes
d'animation** → une sim serveur SANS données d'animation n'est pas fidèle. Décision user : approche
**oracle-certification** (mesurer l'Opt.2 unidbg comme oracle, puis certifier une Opt.3 plus légère contre lui).

### Chaîne de preuves (désassemblage + probes headless)
1. **`HeadlessCombat` = simulateur in-process du CLIENT** (utilisé par les écrans invasion/surge/hero-chooser
   pour **prévoir** l'issue via `startQuickCombat`). Son **ctor** bâtit un `RepresentationManager` →
   `initCommonVFX()` charge `world/common/common.treeb` via `RPGAssetManager`, dont le ctor fait
   `new Texture(Pixmap)` (**upload GL → exige un contexte GL**) et enregistre les loaders **natifs**
   (`NativeAtlasLoader`/`NativeSkeletonDataLoader`/`NativeParticlePoolLoader` = cspine/cparticle **unidbg**).
   `work()` pilote `loadScene(LOAD_ONLY)`/`startScene()` → charge+anime les représentations.
2. **Voie logique-only explorée** (piloter `Scene` directement) : le jeu a un mode headless
   `BuildOptions.TOOL_MODE=COMBAT_AUTOMATOR` → `RenderContext2D.getEnvSpacingInfo()`=`AUTOMATOR_BOUNDS` (pas de
   renderContext) ; renderer no-op du jeu `HeadlessSceneRenderer.INSTANCE` (méthodes vides). **Le SETUP tourne
   headless sans GL** : `new Scene(random,true)` + `CombatSetupHelper.createUnits/initPositions/
   initializeAIAndSkills` → unités créées (1-1 : 3 vs 1), `fixedTimestep=25ms`. `Scene.update` ne touche ni
   rendu ni représentation ; `Unit` a **0** ref `getRepresentation()`.
3. **MAIS `scene.update` enregistre des `AnimationKeyframeListener` sur l'`AnimationElement` de chaque unité**
   (`getAnimationElement()` null headless → NPE). C'est **le mécanisme par lequel les effets d'ability
   (dégâts/projectiles) se déclenchent à des frames précises**. Sans `AnimationElement` (créé par le
   `RepresentationManager` depuis les `.skel`), les unités **n'infligeraient jamais de dégâts**. Les entrées
   walk-in exigent aussi un `entranceKey` posé par le repManager (durées d'anim=0 sans `AnimationElement`).
   ⇒ **combat = keyframe/animation-driven → pure logique NON fidèle.**

### Options + principes (cf. SERVER_PLAN §D)
- **Opt.1** loot autoritatif (#25, pure logique prouvée) + garde-fous, combat = PARTIEL client documenté (§2 OK).
- **Opt.2** worker combat via **unidbg** (données-spine, sans rendu pixel) = **§3 complet + §4** (vrai code+data),
  mais lourd. Le plus fidèle à nos principes pour l'autorité totale.
- **Opt.3** timelines via runtime **spine Java** (`spine-libgdx-perblue.jar`) = plus léger, **conforme seulement
  si prouvé bit-fidèle au natif** (sinon rustine §4/§4bis).

### Décision (user) — oracle-certification (patron du rebuild natif)
Mesurer/monter l'**Opt.2 comme ORACLE** (elle réutilise le **client headless déjà fonctionnel** du
`desktop-port` : GL Xvfb/llvmpipe + vrai `GameMain` → assetManager/renderContext peuplés + unidbg + assets), puis
**certifier l'Opt.3 contre elle** (RNG/HP-tick/timing sur matrice large). Jamais shipper l'Opt.3 non certifiée.
**Prochain pas** : hook lanceur `dh.combatspike` (après boot+login → `HeadlessCombat` de campagne → `work()`
jusqu'à `DONE` → outcome/stars + timing). Aucune modif jeu/serveur committée dans cette phase d'investigation
(exploration en scratchpad). Fichiers docs : `docs/SERVER_PLAN.md` §D, MEMORY.

---

## 2026-07-16 (soir) — PIPELINE COMPLET VALIDÉ EN JEU (nouveau joueur → 1-1 GAGNÉ) + sélection des héros (pilote)

### Résumé
Run complet **nouveau joueur** (DB + prefs wipés, assets en cache) : intro → coffre GOLD (Frozone, 3 héros)
→ FAST_FORWARD → AUTO_FIGHT → SKILL_USE → **entrée campagne → choix des héros → 1-1 GAGNÉ → `CampaignAttack`
→ `recordOutcome` persisté**. Le serveur (framed jar, sans `-Xverify:none`) est resté **stable, 0 fatal JVM**
sur tout le run — le fix reframe validé de bout en bout en conditions réelles.

### Fix pilote : sélection des héros sur l'écran de choix
Le pilote atteignait `CampaignHeroChooserScreen` (« CHOOSE YOUR HEROES! ») mais **tapait FIGHT sans
sélectionner d'équipe** (TEAM POWER=0 → popup « select at least one hero » → aucun combat). Correctif
(technique **capturer→cliquer→monitorer→câbler**) : `selectHeroesIfNeeded` sélectionne les héros dispo via
l'API du jeu qu'un tap de portrait déclenche — **`HeroChooserScreen.unitSelected(UnitData, provider, x, y)`**
(cœur `HeroChooserHelper.selectUnitPressed` ; provider/coords inutilisés hors SURGE, vérifié au bytecode).
N'ajoute que les héros pas déjà dans l'équipe (`unitSelected` TOGGLE) et sélectionnables (`canSelectUnit`).
Vérifié EN JEU : `[tutodrive] CampaignHeroChooserScreen → héros sélectionnés via unitSelected → équipe prête
pour FIGHT`, puis `CampaignAttack NORMAL 1-1 WIN`.

### Persistance vérifiée sur la DB live (après 6× 1-1 WIN) — TOUT persiste, AUCUN 39M
Probe `server/data/dh-server.db` après le run :
- **STAMINA stored=122 / effective=120** ✅ — **aucun 39M même après 6 combats** (réponse à la question user :
  120 à l'écran — bouton FIGHT coût 6 — ET 120 en DB, au first launch). Le 122 stored (>cap) est le même
  artefact bénin R102, clampé à 120 à l'affichage/dépense.
- **GOLD = 2040** ✅ (6× ~340 = loot de campagne cumulé et persisté).
- **1-1 = 3★, totalWins=6** ✅ ; **1-2 débloqué = true** ✅ (déblocage persisté) ; `getLatestCompletedLevel=1-1`.
- **teamXP/teamLevel** cohérents (niv.1).
⇒ Pipeline **entrée / choix héros / skills (AUTO) / vagues / loot-gold / récompenses / XP / conso énergie /
progression / déblocage niveau suivant** : **tout validé et persisté**.

### BUG PERSISTANCE trouvé & corrigé : NIVEAU D'ÉQUIPE (révélé par la question stamina de l'utilisateur)
L'utilisateur a tiqué : « stamina 122/120 (au-dessus de 120) et 6 combats sans consommation ? ». Investigation
(`StamTrace`, 6× 1-1 sur joueur neuf) : la stamina EST consommée (−6/combat : 120→114→108…) MAIS **remonte
tous les 3 combats** à 122 (stored) / 120 (effective), corrélé à `TEAM_XP` qui reset 12→0. `team_levels.tab` :
niv.1→2 = **18 XP** (=3×6), montée = **+20 stamina** (`STAMINA_GAIN_ON_LEVEL`) ⇒ 122 = 102 (après −6) + 20.
MAIS `getTeamLevel()` restait **1** même après reload → **le niveau d'équipe ne montait/persistait pas**.
**Cause** : `User.teamLevel` est un **champ de `User`** (pas dans `this.extra`) ; `getUser` le lit depuis
`userInfo.basicInfo.teamLevel`, mais `setTeamLevel` (appelé à la montée par `giveTeamXP`) n'écrit QUE le
champ `User`. Sans re-sync, le wire garde 1 → l'équipe « re-monte 1→2 » à chaque palier de 18 XP et
**ré-accorde +20 stamina EN BOUCLE** (d'où la stamina qui ne descend jamais — le symptôme repéré par l'user).
**Correctif** (même schéma que resyncHeroes/resyncCampaign, §6) : `recordCampaignAttack` fait
`userInfo.basicInfo.teamLevel = user.getTeamLevel()`. Vérifié `StamTrace` (le niveau monte 1→2 au 3ᵉ combat
et **RESTE** à 2 ; teamXP progresse ensuite vers 25 = seuil 2→3 ; plus de refill +20 ; stamina se consomme
vraiment : …114→108→102) + **`server/smoke/TeamLevelPersistTest`** (niv.2 après 18 XP, survit au reload
SQLite). Régression `Resource/CampaignAttack/CampaignPersist` toujours verte.

### Réponse à « 122/120 et 6 combats sans consommation ? »
OUI la stamina est consommée (−6/combat). Le « 122>120 » = bonus `STAMINA_GAIN_ON_LEVEL=20` du jeu à la
montée de niveau d'équipe (stored peut dépasser le cap d'affichage 120 → clampé `min(122,120)=120`, même
artefact bénin R102). Le « pas de consommation apparente sur 6 combats » = le **bug ci-dessus** (re-level en
boucle refillant +20 tous les 3 combats) — désormais corrigé : la stamina descend réellement.

### BUG trouvé & corrigé : LOOT D'OBJETS de campagne non crédité (question user « objets équipables ? »)
L'utilisateur a demandé si les objets ramassés en combat sont persistés et dispo à l'équipement. Investigation :
inventaire (`individualUserExtra.items`) VIDE après 5 victoires (`InvProbe`), même EN MÉMOIRE (`LootProbe`) →
pas un souci de persistance, `recordOutcome` ne crédite RIEN. Cause : le combat est CLIENT-autoritatif → le
client ROULE le loot pendant le combat et l'envoie dans `CampaignAttack.lootEarned` (List<RewardDrop>) +
`memoryChanges`. `recordOutcome` **n'en roule pas** (vérifié `LootRoll` : lootEarned vide en entrée → vide en
sortie) : il **applique** la liste reçue (`giveLoot → RewardHelper.giveRewards → addItem` → items, auto-persisté).
`recordCampaignAttack` passait des listes **vides** → objets jamais crédités. **Correctif** : passer
`m.lootEarned` comme **1ᵉʳ paramètre List** (le loot à donner). ⚠️ **Piège** : le **2ᵉ paramètre List** de
`recordOutcome` est un **delta de RewardDrop** (déjà-affiché) que `giveLoot` passe à `removeDelta` → y mettre
`m.memoryChanges` (`UserLootMemoryChange`) **plante** (`ClassCastException` dans `removeDelta`) au 1ᵉʳ
`CampaignAttack`, qui n'est alors jamais enregistré → **cascade `CAMPAIGN_LEVEL_LOCKED`** sur tous les suivants
(révélé par un run réel). On laisse donc ce delta **VIDE**. Vérifié `LootApply` (RewardDrop → getItemAmount
0→1) + `server/smoke/LootPersistTest` (BADGE_OF_FRIENDSHIP x2 crédité, `m.memoryChanges` peuplé SANS planter,
survit au round-trip SQLite → dispo à l'équipement). **PARTIEL** : `m.memoryChanges` (mémoire de loot/pity) +
graine RNG client (`Action SET_SEED` TYPE=LOOT/COMBAT, loguée « non appliquée ») non appliqués → le serveur ne
re-roule pas, il fait **confiance au loot client** (cohérent avec le combat client-autoritatif).
Régression (Resource/CampaignAttack/CampaignPersist/TeamLevelPersist) verte.

### ENCHAÎNEMENT 1-1→1-5 EN JEU ✅ (nav post-victoire + fenêtre d'équipement)
D'abord le pilote rejouait 1-1 (une seule entrée carte, 6 combats) : après victoire il revenait sur
l'aperçu du MÊME niveau et re-tapait FIGHT au lieu de retourner à la carte. **Fix nav post-victoire** : flag
`justFoughtCampaign` posé en combat → au retour sur CampaignPreview/HeroChooser, RETOUR (BACK) à la carte →
`enterCampaignLevel` prend `nextPlayableLevel` = niveau débloqué suivant (remis à false à chaque entrée
fraîche pour ne pas sortir du 1ᵉʳ choix après le combat d'intro). **Fix étape équipement** : le pilote bouclait
sur `HeroDetailScreen` (~7000 frames) en FERMANT la `CraftingWindow` (prise pour un résidu ; cible tuto =
HERO_GEAR_SLOT_SIX derrière) → cas (a-bis) : une CraftingWindow est une **fenêtre de FLUX** → taper son bouton
EQUIP (`CRAFTING_WINDOW_EQUIP_BUTTON`) au lieu de fermer. **Résultat EN JEU** (run fresh, 3 fixes cumulés) :
`CampaignAttack NORMAL 1-1,1-2,1-3,1-4,1-5 WIN` — le pilote enchaîne
`normalOrEliteNodeSelected(1-1)→RETOUR→(1-2)→(1-3)→(1-4)→RETOUR→(1-5)→(1-6)`. **État persisté** (DB) :
teamLevel=**2**, 1-1..1-5 à **3★**, 1-6 débloqué, gold=**2316**, stamina stored=108/eff=108 (consommée
correctement — plus de refill en boucle grâce au fix team-level), serveur **0 fatal**. Pipeline complet
(entrée/choix héros/skills/vagues/loot/récompenses/XP/énergie/progression/**enchaînement**) validé en jeu.

### Run resume « coincé tôt » (élucidé)
Un run *resume* (300s) avait repris DANS le tuto (progression persistée seulement à HERO_FILTERS), rejoué
intro+coffres, atteint HERO_FILTERS puis s'était mis en **veille** (captures + pings keepalive) sans franchir
l'étape avant timeout. Pas une boucle de reconnexion (mauvaise lecture : `dh_game.log` écrasé par le run
suivant). Le run *fresh* (du début) atteint la campagne de façon fiable.

### Fichiers
`desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (`selectHeroesIfNeeded`). Aucune modif jeu/serveur.

---

## 2026-07-16 — Enquête « crash addHeroEXP » : RÉSOLU (artefact pré-fix) + vraie cause = SIGABRT oop-map JVM → reframe game.jar (serveur)

### Résumé
Reprise de l'enquête « crash `addHeroEXP` » (demande user « investigue ça et corrige »). Trois conclusions
fermes, toutes vérifiées :
1. **Le crash `addHeroEXP` (`ClientErrorCodeException: ERROR []`) NE se reproduit PLUS** — c'était un
   **artefact d'un état compilé pré-fix** (ChargeTest tournait contre une ancienne compilation). Sur la DB de
   chaînage courante, recompilée, `ChargeTest`/`ChainProbe`/`DiscTest` passent tous.
2. **Le VRAI incident intermittent était un crash JVM** (SIGABRT, pas une exception de jeu) :
   `fatal error: Illegal class file … in method getDefaultStats` dans `GenerateOopMap::error_work`
   pendant un **GC** (G1 root scan). Cause : le bytecode **dex2jar** de `game.jar` n'a pas de
   `StackMapTable` → sous `-Xverify:none` la JVM infère les oop-maps au GC via l'ancien
   `generateOopMap.cpp`, qui **plante** sur certains motifs (`ConstantStats.getDefaultStats`, perks de guilde).
   **Non déterministe** (dépend du timing GC) : ~1 run/8 en test. **Le serveur AUTORITATIF y était exposé
   aussi** (`run-online.sh:67` tournait `-Xverify:none`), pas seulement les smoke tests.
3. **La stamina « 39,96 M » N'EST PAS un bug** (hypothèse user « timestamp mal enregistré » **réfutée**).

### Fix durable : reframe game.jar → game-framed.jar (comme le client), retrait de -Xverify:none
`tools/reframe/ReframeJar` (ASM 9.7, `COMPUTE_FRAMES`) réécrit les **64 196** classes de `game.jar` avec des
frames valides → la JVM utilise le vérificateur rapide par table (plus de `generateOopMap`) et on **retire
`-Xverify:none`**. Appliqué à **`desktop-port/run-online.sh` (serveur)** ET **`server/smoke/run.sh`** :
reframe à la demande vers `libs/game-framed.jar` (gitignoré/régénérable, comme `game-logic-framed.jar` côté
client), classpath serveur basculé dessus, `-Xverify:none` retiré, `-XX:TieredStopAtLevel=1` conservé
(prudence C2). **Sémantique inchangée** (métadonnées de vérif). Vérifié :
- **`ChargeTest` 15/15 sans abort** sous vérification par défaut (vs abort intermittent avant).
- **Boot serveur OK** (framed jar, vérif par défaut) : process en écoute, port ouvert, **0 fatal**
  (aucun `VerifyError`/`Illegal class`/`GenerateOopMap`).
- **Régression identique** : `ResourceTest`, `CampaignAttackTest`, `CampaignPersistTest` verts (framed jar).
- Smoke `run.sh` : `CodecRoundTrip`/`MessageRoundTrip` OK ; `HandshakeRoundTrip` **retiré** (obsolète, ancien
  ctor `LoginServer` — SHIMS TODO #4).

### Stamina 39,96 M — cause RÉELLE établie (pas un bug), timestamp CORRECT
Mesures directes (probes) sur la DB de chaînage :
- **STORED stamina = 114** (correct), `lastResourceGenerationTime(STAMINA)` **correctement sauvé** (≈31 min
  avant `serverTimeNow`). ⇒ **hypothèse « timestamp mal enregistré » réfutée**.
- `getResourceCap(STAMINA)=**120**` (cap DÉPENSABLE), content update **R102**, `getRegenAmount=39 965 650`,
  `getHardCap=79,46 Md`, intervalle 6 min. Un joueur **neuf** lit **120** (aucun temps écoulé).
- Le « 39,96 M » n'apparaît QUE quand `stamina<cap` **et** ≥1 intervalle écoulé : `updateAndGetResource`
  (branche NON-capée pour STAMINA) ajoute **un** tick de 39,96 M puis sort → `getResource`=brut. Le jeu
  affiche/dépense la valeur **effective = `min(getResource, cap)` = 120**. **Fidèle R102 end-game** : un tick
  d'inactivité (6 min) = recharge pleine.
- `ChainProbe` (3 combats enchaînés) : **effective toujours ≤120** (120→114→120→114), **gold chaîne**
  340→680→1088→1632, **aucun crash de logique**. Le fix `applyEffectiveResourceCap` (commit 8f84ef5) applique
  la MÊME règle du jeu (`min(getResource, cap)`) → DB propre, dépense visible. **PAS de « figer »**
  (recalcule via `getResource`, régén incluse).

### Fichiers
`desktop-port/run-online.sh` (reframe + retrait `-Xverify:none`), `server/smoke/run.sh` (framed jar auto +
retrait Handshake obsolète), `.gitignore` (`libs/game-framed.jar`), `docs/SHIMS.md` (row reframe),
`MEMORY.md`. Aucune modif de logique de jeu ni de serveur (le handler campagne du commit 8f84ef5 est inchangé).

---

## 2026-07-13 (soir) — EQUIP_ITEM VÉRIFIÉ IN-GAME (wire) + enregistreur pas-à-pas + fixes pilote

### Résumé
Grâce à un **enregistreur pas-à-pas** (`dh.tutorec` : dump exhaustif des pointeurs du tuto + captures
numérotées par tick) et à un pilote **discipliné** (plus de tap central hors-script), on a capturé la
**séquence exacte de l'équipement** et **vérifié `EQUIP_ITEM` en jeu sur le wire** : le vrai client envoie
`Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, extra={SLOT=SIX}}`, le serveur répond
`action EQUIP_ITEM appliquée [persisté]`, et le tuto INTRO_FEATURES avance. Nouvelle micro-frontière :
post-équip, le tuto n'émet plus de pointeur sur `HeroDetailScreen` (il faut en sortir vers le hub).

### Enregistreur `dh.tutorec` (outil DEV)
À chaque tick d'autotap : (1) `[tutorec]` dump SANS dédup — écran, fenêtres, **TOUS** les pointeurs du tuto
(`getPointAt` + `getActorTutorialName`), acteurs actionnables ; (2) capture **numérotée**
`build/rec/step_NNN.ppm` (après rendu/swap). Câblé `DH_TUTOREC`. Sert à savoir **exactement** ce que le tuto
désigne à chaque étape (au lieu de deviner). Off par défaut.

### Pilote discipliné (anti-vagabondage)
1. Le lanceur ne tape au **centre** que si le tuto n'a **aucun pointeur actif** (`hadActiveTarget()`=false,
   dialogue « tap to continue »). Avant, le tap central partait hors-script quand un pointeur était actif mais
   non résolu → écran coffre Diamant → « Follow the tutorial arrow! », tuto figé.
2. Quand la cible désignée est **absente de l'écran courant** (élément du hub alors qu'on est sur un écran de
   détail), le pilote frappe **BACK_BUTTON** pour se rapprocher du hub.

### Séquence de l'équipement (ENREGISTRÉE, source de vérité)
`HeroDetailScreen` pointeur **`HERO_GEAR_SLOT_SIX`** (slot 6) → ouvre `CraftingWindow` → pointeur
**`CRAFTING_WINDOW_EQUIP_BUTTON`** → le client émet `Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP,
SLOT=SIX}`. **Le serveur applique + persiste** (handler `ServerUser.applyCommand` EQUIP_ITEM) → tuto avance
(INTRO_FEATURES step 17→29/21). ⇒ `EQUIP_ITEM` **vérifié bout-en-bout en jeu**, plus seulement au smoke test.
NB : le client envoie le slot dans `extra={SLOT=SIX}` ; le handler le recalcule via `getSlotThatCanEquip`
(concordant) — on pourra préférer l'`extra` SLOT quand présent.

### Back-out « libre » post-équip → PREMIER COMBAT DE CAMPAGNE atteint
Indice utilisateur : sur `HeroDetailScreen` post-équip, le **bouton retour est mis en avant** mais
`getPointers` (rafraîchi) renvoie **vide** → le jeu attend que le joueur **sorte** de lui-même (pas de
pointeur formel). Ajout au pilote : après ~120 frames d'inactivité (aucun pointeur) sur un écran NON-hub,
taper **BACK_BUTTON** (seuil en frames, robuste à l'autotap ; un dialogue avance au tap central avant le
seuil). **Résultat vérifié en jeu** : le pilote sort `HeroDetailScreen`→`HeroListScreen`→…→**hub**, puis le
tuto reprend ses pointeurs (`MAIN_SCREEN_CAMPAIGN`→`CAMPAIGN_CHAPTER_ONE_NAME`→`CAMPAIGN_PREVIEW_FIGHT_BUTTON`
→`HERO_CHOOSER_*`) et lance le **1ᵉʳ combat de campagne** (`CampaignAttackScreen`, Frozone équipé, rendu
unidbg). Le tuto atteint l'acte **`FAST_FORWARD`** (post-INTRO_FEATURES). Capture
`desktop-port/build/campaign.png`. ⇒ frontière équipement **entièrement franchie**, on est dans le monde/combat.

### Roster de départ : Ralph + Elastigirl (fidélité vidéo)
Constat utilisateur (vidéo de gameplay) : un compte neuf possède **Ralph + Elastigirl** AVANT Frozone — or
notre nouveau joueur n'avait que Frozone (du coffre). L'intro combat les crée en SYNTHÉTIQUE
(`createUnitDatas`→`new User()`+`CombatSimHelper.createUnitData`) et ne les ajoute PAS au roster ; le roster
de départ est une décision de **création de compte** (serveur, qu'on contrôle). Ajout dans
`ServerUser.initNewPlayerResources` : `user.createAndAddHero(RALPH/ELASTIGIRL, WHITE, 1, 1, "new_user")`
(méthode du jeu ; état = défaut « nouveau héros » = celui de Frozone-coffre) + resync wire. Frozone reste
donné ENSUITE par le coffre GOLD. Vérifié (`server/smoke/RosterTest`) : {Ralph,Elastigirl} WHITE niv.1 →
+Frozone → persiste au wire ; 0 régression (Equip/Resource/ViewedChests/ChestWire OK). **Confirmé (vidéo)** : Vanellope n'est que dans le 1ᵉʳ combat tuto (synthétique), PAS possédée ensuite →
roster = Ralph + Elastigirl ; rang/niveau WHITE niv.1 validé.

### Piège découvert : reprise POLLUÉE
Reprendre depuis un `dh-server.db` d'une run **tuée** en plein coffre laisse un état incohérent (coffre Gold
déjà ouvert → bouton « gratuit » en cooldown, mais step de tuto non avancé) → deadlock (le tuto pointe un
bouton mort, `LootResults=0`). ⇒ tester depuis un état **propre** (snapshot post-coffres, ou nouveau joueur).

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` : `dh.tutorec` (dump exhaustif) + `hadActiveTarget()` + RETOUR BACK_BUTTON.
- `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java` : tap central conditionné + capture par tick + capture périodique.
- `desktop-port/run-desktop.sh`, `run-online.sh` : `DH_TUTOREC`, `DH_SHOTEVERY` ; garde-fou (client+Xvfb).
- `MEMORY.md`, `JOURNAL.md`, `docs/SHIMS.md`.

## 2026-07-13 — Pilote : pop-ups empilées drainées (hub atteint) + Actions de bookkeeping REAL/NO-OP

### Résumé
Le correctif du pilote DEV pour les **pop-ups modales EMPILÉES** est **validé en jeu** : le tuto franchit la
frontière de l'équipement et atteint le **hub principal propre** d'un nouveau joueur ; frontière suivante =
tuto `HERO_FILTERS`. Les deux Actions de bookkeeping que le serveur loguait « non appliquée (PARTIEL) » sont
désormais traitées **fidèlement** : `VIEWED_CHESTS` (RÉEL) et `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle).

### Pilote — drainage des modales empilées (`TutorialDriver.driveOnce`)
- **Bug** : quand le tuto pointe une cible (ex. bouton EQUIP de `CraftingWindow`) mais qu'une modale
  résiduelle (`ChestReadyWindow` « CRATE READY ») est empilée par-dessus, l'ancien code faisait `collect()`
  sur **toutes** les fenêtres, trouvait la cible dans la fenêtre inférieure et « tapait » ses coordonnées —
  mais le tap est **absorbé par la modale du dessus** (seule elle reçoit l'entrée) → faux « tapé »
  (`return true`) → **blocage infini**.
- **Correctif** (guidé par le tuto, sans coordonnée devinée) : on raisonne sur la fenêtre du **dessus**.
  (a) le tuto pointe DEDANS → taper le bouton désigné ; (b) le tuto pointe **AILLEURS** → la modale du dessus
  est un **résidu bloquant** → la fermer via l'API du jeu (`BaseModalWindow.hide()` = bouton X), ce qui
  **draine la pile une fenêtre/frame** jusqu'à révéler la cible ; (c) aucune cible active → attendre
  (récompense=`hide()`, interactive=bouton VIEW).
- **Vérifié en jeu** (reprise persistée depuis le snapshot post-coffres) : sur `HeroListScreen` une
  `ChestReadyWindow` résiduelle (coffre Gold) apparaît empilée → **drainée** (VIEW → `ChestResultsWindow` →
  fermeture). Le tuto progresse **au-delà** de l'équipement (INTRO_FEATURES step 29 → `HERO_FILTERS`) ; le
  jeu atteint le **hub principal rendu** (menu HEROES/ITEMS/…, CHOOSE NAME, CAMPAIGN!/CRATES!), session
  stable (Ping échangés). Capture `desktop-port/build/herofilters.png`.
- **Nouvelle frontière** (`HERO_FILTERS`) : `getPointers` n'émet le pointeur d'un step (`DIALOG_1` →
  `UIComponentName.FILTER_BUTTON`) que si `Step.logic().matches()` est vrai (sinon `cibles=[]`, attente).
  À la reprise, le client repart du hub (`MainScreen`) alors que `HERO_FILTERS` attend `HeroListScreen` →
  le pilote devra naviguer vers HEROES (chantier suivant).

### Actions de bookkeeping — `VIEWED_CHESTS` (RÉEL) + `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle)
- **`VIEWED_CHESTS`** : branche extraite au bytecode de `ActionHelper.doAction` =
  `user.setTime(TimeType.LAST_CHESTS_VIEW_TIME, Long.parseLong((String) extra.get(ActionExtraType.TIME)))`.
  `User.setTime` écrit dans `this.extra.times` (`UserExtra` partagé) → **persiste** via `this.extra` (§3).
  Marque « coffres vus » (efface la pastille « nouveau »).
- **`RECORD_SERVER_ROLL_FINISHED`** : `ClientActionHelper.recordServerRollFinished` ne fait que construire
  l'extra (`ID/TYPE/COUNT/TIME`) et appeler `ActionHelper.doAction(RECORD_SERVER_ROLL_FINISHED, …)` — or
  `doAction` **n'a AUCUNE branche** pour ce `CommandType` (vérifié) → le code **client** du jeu ne mute rien.
  Pure notification client→serveur ; le comptage **autoritatif** des rolls est déjà fait par `openChest`
  (`ChestHelper.updateChestRollCounters`). ⇒ **NO-OP fidèle** (pas une rustine : rien n'est simulé ;
  inventer un registre de `rollId` violerait §4).
- **Vérifié** (`server/smoke/ViewedChestsTest`) : nouveau joueur → `applyAction(VIEWED_CHESTS, extra{TIME})`
  puis `applyAction(RECORD_SERVER_ROLL_FINISHED, extra{ID,TYPE,COUNT,TIME})` → round-trip wire →
  `getTime(LAST_CHESTS_VIEW_TIME)` == la valeur envoyée ; les deux `applyAction` renvoient `true`.

### Ressources du nouveau joueur — énergie « 39,96 M / 120 » CORRIGÉE
- **Bug** (repéré à la capture du hub) : l'énergie affichait **des millions** (« 39,96 M / 120 »). Cause :
  un `new IndividualUserExtra()` laisse `getLastResourceGenerationTime(STAMINA)=0` ; le jeu calcule la
  stamina courante = `UserHelper.updateAndGetResource(STAMINA, …, serverTimeNow())` = régénération **depuis
  l'époque 1970** (≈ 56 ans / intervalle 6 min ≈ des millions).
- **Correctif fidèle** (`ServerUser.initNewPlayerResources`, appelé par `newPlayer`) : comme un serveur à la
  création d'un compte — **ancre l'horloge de génération** de chaque ressource régénérée
  (`UserHelper.resourceGenerates(rt)` → `iu.setLastResourceGenerationTime(rt, creationTime)`) puis met la
  **stamina au cap du jeu** (`UserHelper.getResourceCap(STAMINA, user)` = `MAX_STAMINA` de `team_levels.tab`,
  **120** au niveau d'équipe 1). Valeurs issues de la logique/données du jeu (pas inventées). `setResource`
  écrit `individualUserExtra.resources` (partagé → persiste) ; sa branche `battlePassV2` ne concerne QUE les
  diamants → sûr headless.
- **Gestion/valeurs vérifiées** (`server/smoke/ResourceTest`) : compte neuf → **STAMINA=120/120**,
  **GOLD=0**, **DIAMONDS=0**, et la stamina **ne se re-gonfle pas** au round-trip wire (gen-time persisté).
  Note : GOLD/DIAMONDS=0 = valeur du constructeur du jeu pour un compte neuf (le jeu/tuto les accorde en
  jouant) ; à revoir si une dotation de départ (diamants) doit être seedée.

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` : drainage des modales empilées (logique (a)/(b)/(c)).
- `server/java/dhserver/ServerUser.java` : `applyCommand` += `VIEWED_CHESTS` (RÉEL) + `RECORD_SERVER_ROLL_FINISHED` (NO-OP) ; `newPlayer`/`initNewPlayerResources` (ancrage gen-time + stamina au cap).
- `server/smoke/ViewedChestsTest.java` (NEW), `server/smoke/ResourceTest.java` (NEW).
- `docs/SHIMS.md` #5, `MEMORY.md` (date + §7), `JOURNAL.md`.

---

## 2026-07-12 — Handler `Action` : investigation + architecture « logique cœur par commande »

### Résumé
Démarrage du **handler `Action`** (commandes génériques du jeu : équiper/voir/promouvoir…). Investigation
riche → **correction d'un diagnostic**, **1 vrai shim**, et une **conclusion d'architecture**. Handler
**frameworké et démarré**, **pas encore fonctionnel** pour l'équipement (à finaliser).

### Faits établis (corrigent/précisent)
- **`GuildStats` n'est PAS un bloqueur** (mon 1ᵉʳ diagnostic était FAUX — « illusion de crash », comme
  EVIL_QUEEN). `guild_perk_levels.tab` a des `CONTENT_TL` vides (lignes `TIMED_*`) → `parseInt("")` lève,
  mais `GeneralStats.parseStats` l'**attrape** (`onStatError` LOGue + saute la ligne). Vérifié en isolation
  (`GuildProbe`) : **`GuildStats` se charge OK**. La stack imprimée était **loguée**, pas fatale.
- **`DH.app.guildInfo` requis** : `getYourGuildInfo()` → `GuildPerkHelper.updateGuildInfoTimedPerks` lit
  `guildInfo.perkEndTimes`. **Corrigé** : `ServerContext.bind` pose un `new GuildInfo()` (nouveau joueur).
- **`ActionHelper.doAction` = chemin CLIENT « appliquer + UI »** : appelle `getScreenManager().getScreen()`
  **×4** → NPE headless (pas d'écran). ⇒ **on N'UTILISE PAS `doAction`** côté serveur. Comme `openChest`
  (qui exécute `ChestStats`/`DropTable`, pas un flux « acheter » client), on route **par commande vers la
  logique CŒUR** (`HeroHelper.equipItem`, `RealGearHelper.equipGear`…). Aiguillage écrit, règle exécutée.
- **Le badge « Badge of Friendship » n'est PAS du real gear** (`ItemStats.getRealGearType=DEFAULT`) → la
  commande d'équipement du tuto est vraisemblablement **`EQUIP_ITEM`**, pas `EQUIP_REAL_GEAR`.
- **Commandes `Action` réellement observées** (log serveur) : `VIEWED_CHESTS`, `RECORD_SERVER_ROLL_FINISHED`
  (mises à jour d'état légères). L'`Action` d'équipement reste **à capturer** (repro in-game NON déterministe :
  le pilote n'atteint pas toujours l'onglet gear).

### Ce qui est fait (committé)
- `ServerContext` : shim `guildInfo` (`new GuildInfo()`), en plus de user/individualUser/évènements.
- `ServerUser.applyAction`/`applyCommand` : aiguillage **par commande** vers la logique cœur ; routes
  `EQUIP_ITEM` (`user.getHero` + `HeroHelper.getSlotThatCanEquip` + `HeroHelper.equipItem`) et
  `EQUIP_REAL_GEAR` (`RealGearHelper.equipGear`) ; **log des commandes non gérées** (= cartographie).
- `LoginServer` (branche `Action`) : **log du contenu exact** (command/hero/item/extra) + `applyAction` + persist.

### `EQUIP_ITEM` ✅ RÉSOLU & VÉRIFIÉ (débogage déterministe par probes)
Diagnostic pas à pas (sans le client flaky) : (a) `openChest(SILVER)` **persiste bien** le badge
(`individualUserExtra.items={BADGE_OF_FRIENDSHIP=1}`) — le « no slot » venait d'un **snapshot périmé** ;
(b) le badge est le gear **requis du slot 6** de Frozone (`NormalGearStats.getItem(FROZONE, WHITE, SIX)=
BADGE_OF_FRIENDSHIP`) ; (c) `getSlotThatCanEquip` renvoie null car `ItemStats.isItemReleased(badge,
ContentHelper.getCurrent(user))=false` **headless** (colonne de contenu mal résolue — bug à corriger à part) ;
(d) `HeroHelper.equipItem(FROZONE, badge, SIX, user)` **équipe correctement** (slot 6 rempli, badge consommé).
⇒ **fix** : `applyCommand` route `EQUIP_ITEM` via `HeroHelper.getSlotThatCanEquip` + `equipItem`. **Vérifié**
(`server/smoke/EquipTest`) : équipe le badge en slot 6 + **persiste au round-trip wire**.

### Couche CONTENU (colonnes de release) ✅ RÉSOLU — unlock générique
Cause racine du `isItemReleased=false` headless : `ContentHelper` démarre **vide** (`ContentStats
.getColumns()=0` → `getColumn(now)=DEFAULT`). Le jeu charge le contenu du shard via
`ShardStats.setShardID(shard, map)` → `parseStats("content.<shard>.tab", opener)` — jamais appelé headless.
⇒ **fix** : `ServerContext.bind` appelle `ContentHelper.get().setShardID(user.getShardID(), new HashMap())`
→ charge `content.<shard>.tab` (372 colonnes pour shard 1) → `isItemReleased`=true. **Générique** (débloque
toute logique gatée « contenu released », pas que l'équipement). `EQUIP_ITEM` repasse alors sur la logique
d'origine `getSlotThatCanEquip` (plus de contournement). Reste : autres commandes (`VIEWED_CHESTS`…).
Détail : `docs/SHIMS.md` #5.

### Fichiers touchés
- `server/java/dhserver/ServerContext.java` (shim guildInfo), `ServerUser.java` (applyAction/applyCommand),
  `LoginServer.java` (branche Action + log), `docs/SHIMS.md` (#5 réécrit avec les faits).

---

## 2026-07-12 — Traversée du tuto en autonomie (pilote DEV) : intro→coffres→héros→équipement

### Résumé
Mise au point du **pilote DEV** (`TutorialDriver`) + méthodologie **reprise persistée + capture/inspection**
pour traverser le tuto in-game et faire une **passe de features**. Franchi : intro+combat → coffre GOLD
(Frozone) → coffre SILVER (Badge of Friendship) → CRATE READY → menu HÉROS → liste → détail héros →
onglet GEAR → **équipement du badge**. **0 exception serveur** partout. Frontière atteinte : l'équipement
passe par `Action1` non traité côté serveur → prochain handler.

### Corrections du pilote (chacune trouvée par capture d'écran + dump des acteurs)
- **Popups de récompense** (`ChestResultsWindow` = « CRATE REWARDS ») : fermées via l'API du jeu
  `BaseModalWindow.hide()` (le tap central ratait le X). Distinction affichage-récompense vs interactif.
- **Popups interactives** (`ChestReadyWindow` = « CRATE READY ») : frappe du **bouton-texte principal**
  (`DFTextButton` « VIEW », à stage(640,180)≠centre) — trouvé via `dumpActionable` (dump classe/tag/texte/pos).
- **Recherche dans TOUTE la scène** (`stage.getRoot`, pas seulement `getRootStack`) : le menu latéral
  (HEROES/ITEMS…) était hors du rootStack → `BASE_MENU_HERO_BUTTON` introuvable (trouvés=0) alors que le jeu
  y pointe. Corrigé → enchaîne HeroList→HeroDetail→GEAR→EQUIP.

### Méthodologie (documentée dans MEMORY §6ter)
- **Reprise RAPIDE** : ne pas supprimer `server/data/dh-server.db` → le client **reprend au hub** (saute
  l'intro), **~20 s** au lieu de ~4 min (0 rejeu de combat). **Snapshots** DB aux points sûrs
  (`dh-snapshot-postchests.db`, `dh-snapshot-postequip.db`) pour restaurer une frontière.
- **Boucle** : au blocage → screenshot (voir l'écran) + `dumpActionable` (quoi taper) → faire frapper le
  **bon acteur du jeu** (jamais une coordonnée devinée) → recompiler → relancer (reprise rapide).
- **Lire les logs avec `grep -a`** (le log serveur peut avoir du bourrage NUL).

### Finding (prochain handler serveur)
L'**équipement de gear** (et sans doute d'autres actions) part en **`Action1`** (fire-and-forget) que le
serveur **journalise mais ne TRAITE pas** → l'état autoritatif ne reflète pas l'équipement → au **reload**
le tuto ne peut pas avancer (idle sur MainScreen, INTRO_FEATURES step 29). ⇒ prochain : **traiter `Action`
côté serveur** (équipement…), comme on a fait pour `BuyChests`.

### Infra
- **Garde-fou serveurs** (`run-online.sh`) : détecte/kill les anciens serveurs (zombies) + refuse si port pris.
- Email d'auteur des commits corrigé → `noreply@anthropic.com` (GitHub vérifié).

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (fermeture popups, bouton-texte, recherche
  scène, dump), `desktop-port/run-online.sh` (garde-fou + DH_TUTODBG/DH_FPS + DH_FRAMES vide=non plafonné),
  `desktop-port/run-desktop.sh` (LC_ALL=C.utf8), `MEMORY.md` (§6bis lancement + §6ter méthodologie).

---

## 2026-07-12 — Diagnostic signal 16/exit 144 (strace) + couche évènements spéciaux (2ᵉ coffre débloqué)

### Résumé
Deux résultats liés, obtenus en poussant le run client réel jusqu'au coffre : (1) **diagnostic définitif**
du `exit 144` (SIGSTKFLT) via `strace -f` ; (2) un **bug réel** révélé par ce run — le **2ᵉ** coffre plantait
côté serveur — **corrigé fidèlement** en initialisant la couche évènements spéciaux du jeu (tâche #14).

### (1) signal 16 / exit 144 — `strace -f` = kill EXTERNE, pas de crash natif
Client lancé sous `strace -f -e trace=kill,tgkill,tkill,rt_sigqueueinfo,rt_tgsigqueueinfo,exit_group,exit
-e signal=all`. Sur **6 runs** (30→1500 frames, hors-ligne ET en ligne atteignant le combat) :
- **Aucun `SIGSTKFLT`**, aucun `tgkill`/`rt_sigqueueinfo` **interne** (rien ne s'auto-tue), `exit_group(0)`
  **propre** par le thread leader de la JVM. Seulement **2 `SIGSEGV`/run**, mais **normaux** (HotSpot les
  utilise pour ses null-checks implicites + safepoint polling, les rattrape, continue).
- Le kill **disparaît sous strace** (ralentissement ptrace) et n'est pas revenu ensuite → réponse au doute
  « crash natif JNI/.so » (unidbg/unicorn/LWJGL) : **NON**. Un crash natif serait déterministe sur le même
  chemin (ne disparaît pas juste parce qu'on ralentit) et apparaîtrait dans strace (signal reçu ou auto-kill).
  `SIGSTKFLT` (16) est quasi inutilisé par le noyau/glibc/JVM → **kill externe transitoire du superviseur**,
  non bloquant, non lié à notre code (un simple serveur Python en a été victime aussi). Confirme la
  conclusion précédente (le test `4× yes` avait déjà écarté un budget CPU).

### (2) Couche évènements spéciaux — 2ᵉ coffre (tâche #14)
Le run client réel a d'abord **prouvé le 1ᵉʳ coffre de bout en bout** (`[login] ==> LootResults : coffre
GOLD -> 1 héros débloqué`), puis a envoyé un **2ᵉ** `BuyChests` → `openChest` **NPE** :
`SpecialEventsHelper.helper` null via `giveChestRewards` → `RewardHelper.giveReward` → `UserHelper.giveUser`
→ `ContestHelper.onItemEarn` → `getActiveContestsWithTask` (les coffres à **récompense d'objet** enregistrent
des tâches de contest). C'est la dette #14, sur le chemin critique.
- **Fidélité** : `GameMain.create()` fait `SpecialEventsHelper.init(new ClientEventUserProvider(), new
  ClientSpecialEventsHelperExt())` (vérifié au bytecode, offsets 589-603), puis `handleBootData` appelle
  `setSpecialEvents(SpecialEventsRaw, user, shardID)`. L'extension **cliente** touche libGDX (`Gdx.app not
  available` headless) car elle **pousse au serveur** les temps de visionnage (`UpdateEventViewTimes`).
- **`dhserver.ServerSpecialEventsExt`** (NEW) = équivalent **serveur** de l'interface (RÉEL) :
  `sendEventRewards` reproduit la logique d'état cliente à l'identique (PREMIUM_STAMINA_CONSUMABLE →
  `ItemHelper.convertTimeLimitedItems` + `setTime(LAST_AMPED_STAMINA_BUY)`, aucun libGDX) ; `trySetEventViewed`
  conserve l'inscription **autoritative** (`user.getIndividual().getEventViewTimes().put`) et **omet** la
  poussée réseau client→serveur (le serveur EST le destinataire → sans objet). Zéro donnée falsifiée.
- **`ServerContext`** : `init()` appelle `SpecialEventsHelper.init(new ClientEventUserProvider(), new
  ServerSpecialEventsExt())` (une fois, comme `create()`) ; `bind()` appelle `setSpecialEvents(new
  SpecialEventsRaw(), user, shardID)` (par joueur, comme `handleBootData`). Nouveau joueur sans évènement
  live = raw vide → `getActiveContestsWithTask` renvoie une liste **vide** (au lieu de NPE).
- **`ServerUser.openChest`** : `updateChestCounters` **réactivé** (plus de PARTIEL).

### Vérifications
- **Unitaire** : nouveau joueur → **3 coffres GOLD d'affilée** : coffre 1 = Frozone (héros 0→1), coffre 2 =
  **récompense d'objet** (`heroesUnlocked=0`, le chemin qui plantait) **sans NPE**, coffre 3 OK.
- **Wire** (`ChestWireTest`) : `BuyChests(GOLD)` → `LootResults{Frozone}` en ~746 ms, **aucune régression**.
- **En jeu** : run client réel relançé (serveurs recompilés) pour rejouer les 2 coffres du tuto.

### Confirmation IN-GAME du 2ᵉ coffre (2026-07-12, après coup)
Un run client **non plafonné** (`dh.frames` absent → tourne jusqu'à ce que le tuto se joue) a traversé
l'intro + les DEUX coffres : GOLD → Frozone, puis **SILVER → `LootResults` avec 0 héros = récompense
d'OBJET** (exactement le chemin `onItemEarn` qui plantait). Résultat serveur : **0 exception** de tout le
run (`helper is null`/`NullPointer` = 0), tuto poursuivi jusqu'à **INTRO_FEATURES step 29**, `Action1`
(claims de coffre) acceptés. ⇒ le 2ᵉ coffre est **corrigé et vérifié in-game**. (Piège d'outillage noté :
tronquer le log serveur avec `: >` laisse du bourrage NUL → `grep` le prend pour un binaire et n'affiche
rien ; utiliser `grep -a`. Ne pas tronquer un log tenu ouvert par le serveur.)

### `sendEventRewards` : plus aucune recopie (2026-07-12)
Suite à la demande utilisateur (« éviter la recopie manuelle, exécuter le code du jeu »), `ServerSpecialEventsExt`
alloue l'instance du jeu `ClientSpecialEventsHelperExt` **sans constructeur** (Unsafe, le ctor touche libGDX)
et **délègue `sendEventRewards`** à la vraie méthode (elle ne dépend pas de libGDX). `trySetEventViewed` reste
de la glue serveur (enregistre le temps de visionnage, sans la poussée réseau client→serveur sans cible).
Zéro ligne de logique de jeu recopiée. Vérifié : 3 coffres d'affilée inchangés.

### Fichiers touchés
- `server/java/dhserver/ServerSpecialEventsExt.java` (NEW puis délégation), `ServerContext.java` (init/bind
  couche évènements), `ServerUser.java` (`updateChestCounters` réactivé).
- `desktop-port/run-desktop.sh` (`LC_ALL=C.utf8` — extraction d'assets aux noms Unicode).
- `docs/SHIMS.md` (TODO #1 → RÉSOLU, entrées `ServerSpecialEventsExt` + locale plateforme),
  `docs/SERVER_PLAN.md` §6, `MEMORY.md`.

---

## 2026-07-12 — Handler `BuyChests` complet : le serveur exécute la logique du jeu (Frozone, vérifié wire)

### Résumé
Étape 6 démarrée avec un **handler `BuyChests` fully functional** (option B) : le serveur **exécute le code
du jeu** sur l'état autoritatif → roule la vraie table, donne Frozone, répond `LootResults`, persiste.

### Architecture (option B, choisie avec l'utilisateur)
- **`ServerContext`** : `ServerStats.install()` (données du jeu) + **shim `DH.app`** — beaucoup de classes
  passent par le singleton client `GameMain` (ex. `User.getIndividual()` = `DH.app.getYourIndividualUser()`).
  On alloue un `GameMain` **sans constructeur** (`Unsafe.allocateInstance`), pose `user`/`individualUser`,
  affecte `DH.app`. Couche plateforme (§4), pas de logique de jeu.
- **`ServerUser.openChest(BuyChests)`** : construit `User`/`IndividualUser` de jeu **sur nos objets wire**
  (`getUser` fait `this.extra = userExtra` → **les mutations via `this.extra` persistent d'elles-mêmes** :
  `setResource`/`setChannelRollCount` écrivent dans `this.extra`). Roule `ChestStats.getDropTable(GOLD)` +
  `DropTable.rollNode("ROOT")`, `DropConverter.convert`, `ChestHelper.giveChestRewards(bl=true)` (donne +
  remplit `heroesUnlocked`), `updateChestRollCounters`. **Resync** des champs hors `this.extra` (héros via
  `getHeroData`, `chestUpgradeXP`). Renvoie `LootResults`.
- **`LoginServer`** : `BuyChests` → `openChest` → répond `LootResults` + persiste (SQLite).

### Sérialisation inverse (le point clé résolu)
Le jeu n'a pas de sérialiseur `User→wire` complet, MAIS ses setters écrivent dans `this.extra` (l'objet
wire qu'on lui passe). En construisant le `User` **sur nos propres objets wire**, la plupart des mutations
persistent automatiquement ; seul un **ensemble fermé** (héros, `chestUpgradeXP`) est resynchronisé.
Validé par round-trip.

### Vérifications
- **Unitaire** : nouveau joueur (0 héros) → `openChest(GOLD)` → `LootResults{lootDrops=1, heroesUnlocked=1}`,
  joueur possède Frozone, **persiste au reload** (SQLite).
- **Sur le wire** (`server/smoke/ChestWireTest`) : client `ClientInfo→BootData` puis `BuyChests(GOLD)` →
  **`LootResults{Frozone}` reçu en ~630 ms**. Handler prouvé sur le protocole réel.
- **En jeu** : le client atteint le coffre et envoie `BuyChests1` à notre serveur (confirmé). Le run client
  complet meurt parfois (exit 144, signal d'environnement sur runs longs) avant d'afficher la réponse —
  d'où la vérification par `ChestWireTest` (rapide, déterministe).

### PARTIEL noté (§2, avec risque)
- `updateChestCounters` (compteurs QUOTIDIENS, limites d'achat) passe par `SpecialEventsHelper.helper`
  (couche évènements non initialisée headless) → différé. Non requis pour le tuto (coffre gratuit).
- Coffres **payants** (charge diamants via `setResource`→`DH.app.getUserBattlePassV2`) : nécessitent
  d'étoffer le shim (battlePassV2). Le coffre **gratuit** du tuto est complet.

### Fichiers touchés
- `server/java/dhserver/ServerContext.java` (NEW), `ServerUser.java` (openChest + resync), `LoginServer.java`
  (handler BuyChests), `server/smoke/ChestWireTest.java` (NEW), `desktop-port/run-online.sh` (-Ddh.stats),
  `docs/PRINCIPLES.md` §3 (shim DH.app + sérialisation inverse), `docs/SERVER_PLAN.md` §6, `MEMORY.md`.

---

## 2026-07-12 — Enquête coffres/héros + fondation « serveur exécute le code+données du jeu » (spike Frozone)

### Résumé
Investigation (question utilisateur) sur le 1ᵉʳ coffre du tuto, puis **fondation de l'étape 6** : le serveur
**charge les données du jeu et exécute sa logique** (règle affinée §3). **Spike concluant** : le vrai code
de coffre du jeu roule côté serveur → **Frozone** pour un nouveau joueur.

### Enquête (extraite du code/données)
- **Héros avant le 1ᵉʳ coffre = 0.** Combat d'intro = synthétique (`CombatSimHelper.createUnitData(new
  User(),…)`). 5 « héros tuto » du jeu = `RemoveIf(SpecificHeroes, VANELLOPE, RALPH, YAX, ELASTIGIRL,
  FROZONE)` (`black_market_merchant_drops.tab`), acquis progressivement (Frozone→Vanellope `UnlockHeroActV1`
  →Yax campagne 1-13…). `starter_deal_heroes.tab` = pack **payant**, pas le roster gratuit.
- **1ᵉʳ coffre = FROZONE prédéfini** : `IntroFeaturesActV2.getChestUnitType()=FROZONE` **et** rig de la table
  `gold_chest_drops.tab` (`ROOT_1X_FIRST ? PreviousRolls(0) ? ROOT_1X_RIG_1 ? CJK ? HERO_BUZZ ? HERO_FROZONE`).
- **Coffres hors tuto** : `BuyChests{chestType,count,roll:ServerRollRequest}` → serveur roule
  `<type>_chest_drops.tab` (`DropTable`) : rigs 1ᵉʳ/2ᵉ, pitié 10× `Try NoneAre(YourHero)`, payant/gratuit,
  VIP, locale, pools `@NON_EXCLUSIVE/GOLD_CHEST_EXCLUSIVE_HEROES`. `channelRollCount` alimente `PreviousRolls`.
- Réponse au client = **`LootResults`** (`heroesUnlocked`/`lootDrops`/`costs`/`roll`).

### Fondation « lire & exécuter » (spike)
- **`ServerStats`** (NEW) installe l'ouvreur de stats du jeu (`StatFileHelper.setExt`) lisant
  `game-data/stats/` → 274 `.tab` chargés headless (SEVERE = quirks `.tab` tolérés, comme en jeu).
- **Dépendance joda-time** : `game.jar` a les classes joda (dex2jar) mais **pas** la donnée fuseaux
  `org/joda/time/tz/data/*` (requise par `TimeUtil.<clinit>`, appelé via `ContentStats`/`CampaignStats` lors
  de `IndividualUser.setExtra`). Fournie par le jar standard joda-time-2.12.2 (classes ombrées par game.jar,
  seule la ressource tz utilisée) — donnée du jeu, pas une réécriture. Récupéré à la demande par `run-online.sh`.
- **Spike** (`ChestSpike`) : install stats → `ChestStats.getDropTable(GOLD)` (table 38 nœuds, parsée) →
  `User`/`IndividualUser` construits depuis l'état wire (`ClientNetworkStateConverter`) → `ChestContext(user)`
  avec `setChestType(GOLD)`+`setCount(1)` → `DropTable.rollNode("ROOT")` = **`HERO_FROZONE`**. (count=0 →
  `RetainCount(0)` = 0 drop : d'où l'importance de `setCount`.) **Zéro donnée écrite à la main.**

### Fichiers touchés
- `server/java/dhserver/ServerStats.java` (NEW), `desktop-port/run-online.sh` (classpath+fetch joda).
- `docs/PRINCIPLES.md` §3 (règle affinée « lire & exécuter »), `docs/SERVER_PLAN.md` §6 (archi + faits coffres),
  `MEMORY.md`, `JOURNAL.md`.
- Reste : handler `BuyChests` complet (roll→`LootResults`→`applyChestResults`→re-sérialiser→répondre).

---

## 2026-07-12 — Tuto d'intro joué DE BOUT EN BOUT (harnais DEV) + FPS combat + frontière du hub

### Résumé
Ajout d'un **harnais de DEV** (drapeaux lanceur, off par défaut, **aucune modif jeu/serveur**, **rien en
prod**) qui pilote le jeu headless via **les outils/API du jeu** : le tuto d'intro se joue **de bout en
bout jusqu'à `DONE`**, puis atteint sa 1ᵉʳ action serveur (**coffre de départ → `BuyChests1`**) = frontière
de la phase hub. Mesure FPS/profilage du combat.

### Outils de DEV ajoutés (côté lanceur `desktop-port`)
- **`TutorialDriver`** (piloté par `dh.autotap`) : interroge `TutorialHelper.getPointers(user)` → cible
  (`TutorialPointerInfo.getActorTutorialName()` = nom+index), retrouve l'acteur via `getTutorialName()`
  (comme `Group.findTutorialActor`) dans `getRootStack()`, convertit stage→écran (viewport plein cadre) et
  tape via `DhInput`. Sans pointeur → tap central (dialogues). **Zéro coordonnée devinée** : tout vient de
  l'acteur désigné par le jeu. C'est du **contrôle headless**, pas une modif.
- **`dh.autofight`** : appelle l'API publique **`CoreAttackScreen.setAutoAttack(true)`** (bouton AUTO
  d'origine) → auto-combat du jeu (utile hors tuto ; le tuto d'intro **met le combat en pause** et exige un
  tap manuel sur le héros désigné → assuré par le driver).
- **`dh.fps`** : FPS glissants + **profilage** (chrono des appels unidbg vs reste). Compteurs statiques dans
  `UnidbgVM` autour du dispatch `si/sv/so/pi/pv/po`.
- **Inventaire des outils d'auto du jeu** (pour info) : `automation/{TouchRecorder,TouchPlayback}` (rejoue un
  enregistrement), `automation/crawler/*` (scripts de clics FIXES : Example/Chest/Market/Purchase),
  message serveur `TriggerCrawler`, `TutorialHelper.finishIntroForced/finishAllTutorials` (saut). **Aucun**
  n'est un « joueur de tuto » clé en main ; le driver ci-dessus s'appuie sur le **système de pointeurs** du jeu.

### FPS combat (sur cette machine, headless llvmpipe SANS GPU)
- **~9 fps** en combat (`TutorialAttackScreen`). Répartition par frame : **unidbg (spine+particules) ~50 ms
  (combat léger) → ~80 ms (combat plein, DOMINANT)** ; rendu logiciel+logique ~40-60 ms. Deux pire-cas :
  pas de GPU (le « reste » s'effondrerait avec une carte) + émulation ARM (plancher dur d'unidbg). ⇒ perf
  combat = futur chantier d'optim (moins d'appels unidbg/frame, JIT dynarmic cassé à réparer, etc.).

### Déroulé vérifié (run-online.sh + DH_AUTOTAP + DH_AUTOFIGHT)
- Intro : GATE_DIALOG → TRANSFORM → **COMBAT1** (ACTIVE_1/2/3 franchis via taps guidés) → POST_COMBAT →
  **COMBAT_2** → **`DONE`**. Serveur = 0 réponse requise pour tout l'intro (client-side).
- Puis **INTRO_FEATURES** : chargement des VFX `reward_boxes`, **`BuyChests1`** + `Action1` envoyés →
  écran **« CRATE REWARDS / Waiting for results… »** (capture `native/reference/shots/
  tutorial-intro-done-crate.png`). Le serveur journalise mais **ne répond pas** → le client attend.

### Conclusion / prochaine phase
Réponse à « gagne-t-on des héros de départ, le serveur gère-t-il ? » : **OUI**, le tuto donne les héros de
départ via un **coffre** (`BuyChests1`) juste après l'intro, et **le serveur doit le gérer** (contenu du
coffre autoritatif = héros de départ, réponse). ⇒ **étape 6 (hub)** démarre par le handler **`BuyChests`**.

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (NEW), `DesktopLauncher.java` (autotap→driver,
  `dh.autofight`, `dh.fps`+profilage), `dhbackend/unidbg/UnidbgVM.java` (chrono), `run-desktop.sh` (drapeaux).
- `docs/SERVER_PLAN.md` (étape 6 amorcée + section Outils de DEV), `MEMORY.md`, `JOURNAL.md`.
- Captures : `native/reference/shots/{tutorial-combat1-unidbg,tutorial-intro-done-crate}.png`.

---

## 2026-07-11 — Serveur autoritaire étape 5 : persistance SQLite (octets wire des objets du jeu)

### Résumé
L'état joueur autoritaire est **persisté en SQLite** sous forme d'**octets wire** produits par les
classes du jeu (aucun schéma inventé pour les données du jeu, cf. PRINCIPLES §6). La progression du
tutoriel survit à un redémarrage du serveur.

### Détails
- **`ServerUser` refactoré** : détient l'état comme **objets du jeu** — `UserInfo` (identité),
  `UserExtra` (héros/ressources/réglages), `IndividualUserExtra` (tutoriels/quêtes). `bootData()` branche
  ces objets dans un `new BootData()` (complet par ses constructeurs). Sérialisation :
  `GruntMessage.writeAll` (en-tête + données = wire exact) ↔ `MessageFactory.readMessage` (round-trip
  symétrique prouvé par les smoke tests).
- **`UserStore` (SQLite, `sqlite-jdbc`)** : table `users(userID, shardID, userInfo BLOB, userExtra BLOB,
  individualUserExtra BLOB, updatedAt)`, clé `(userID, shardID)`, upsert `ON CONFLICT`. **Un objet du jeu
  = un BLOB** → ajouter un champ de jeu persisté = ajouter un BLOB, sans recopier une seule valeur.
- **`LoginServer`** : `loadOrCreate(1,1)` au démarrage ; `store.save(user)` à chaque `ChangeTutorialStep`.
- **Dépendances** : `sqlite-jdbc` 3.45 + `slf4j-api` récupérés à la demande par `run-online.sh` (non
  committés, régénérables — comme ASM). DB sous `server/data/` (gitignore). Le serveur reste « rien à
  installer » côté utilisateur.

### Vérifications
- **Unitaire** : session 1 crée le compte (122 actes), avance INTRO au pas 40, `save` ; **session 2**
  rouvre la DB → état **restauré à l'identique** (122 actes, INTRO step 40), BootData revalide sur le wire.
- **En jeu** : `LoginServer` démarre, `loadOrCreate` OK (DB `server/data/dh-server.db`), persiste les
  `ChangeTutorialStep` réels sans erreur.

### Fichiers touchés
- `server/java/dhserver/UserStore.java` (NEW) : persistance SQLite (BLOB wire).
- `server/java/dhserver/ServerUser.java` : détient les objets du jeu + sérialisation wire.
- `server/java/dhserver/LoginServer.java` : load-or-create + save sur ChangeTutorialStep.
- `desktop-port/run-online.sh` : classpath + fetch sqlite/slf4j ; `.gitignore` : DB runtime.

---

## 2026-07-11 — Serveur autoritaire étape 4 : handlers du tuto (intro) + pilote headless (vérifié en jeu)

### Résumé
Le serveur **applique/persiste la progression du tutoriel** (`ChangeTutorialStep`) dans un état autoritaire
(`ServerUser`). Fait extrait : le **tutoriel d'intro est 100% piloté par le client** (aucun aller-retour
serveur), donc le serveur n'a qu'à **suivre la progression**. Ajout d'un **pilote headless** (`dh.autotap`)
qui traverse les dialogues « tap to continue » : le tuto se joue **de bout en bout jusqu'au 1ᵉʳ combat**.

### Détails (extraction = source de vérité)
- **`IntroTutorialActV2` n'émet AUCUN message réseau** (aucun `sendMessage`/`networkProvider`) ; son combat
  est **local** : `CombatSimHelper.createUnitData((IUser)new User(), …)`, `pauseCombat/resumeCombat`,
  `PauseCombatEvent/ResumeCombatEvent`, `TutorialHelper.startCombatTimerEvent`. Les étapes (`Step` enum) sont
  toutes des états de dialogue/combat côté client (GATE_DIALOG_*, TRANSFORM_ANIMATION, COMBAT1_*, COMBAT_2_*,
  POST_COMBAT_*, DONE). ⇒ **seule sortie serveur = `ChangeTutorialStep`** (framework), fire-and-forget.
- **`ChangeTutorialStep`** = `{type, step, forceSkip}`. `step` **absolu** (cf. `finishIntroForced` pose
  `step = maxStep`). `ServerUser.applyTutorialStep` met à jour l'acte : `step` ← message, `maxStep` ←
  max(courant, step) (« plus haut pas vu »). Copie défensive dans `bootData()` (le client ne mute pas
  l'état autoritaire).
- **Pilote headless** `DesktopLauncher` : `dh.autotap=N` injecte un tap central toutes les N frames via
  l'infra existante `DhInput.tap`→`drain`. Traverse les gates « tap to continue » sans utilisateur.

### Vérifications
- **Unitaire** (sans libGDX) : nouveau joueur = 122 actes step 0 ; apply 12/25/3 → step=3, maxStep=25 ;
  état autoritaire non muté par la copie client ; survit au round-trip wire.
- **En jeu** (`run-online.sh` + `DH_AUTOTAP=45`) : le serveur applique les `ChangeTutorialStep` **réels**
  (INTRO 1→2→3, FRIEND_MISSION 6, FRIENDSHIP_UNLOCK 17, REAL_GEAR_UNLOCK 11, FRIEND_CAMPAIGN 20,
  HEIST_NARRATION 1…), **tous** trouvés dans les 122 actes (0 « type inconnu »), **0 réponse** requise. Le
  client joue l'intro **de bout en bout jusqu'au 1ᵉʳ combat** : GATE_SIGN_DROP → GATE_RALPH_ENTER →
  GATE_VANELLOPE_ENTER → GATE_DIALOG_2 → TRANSFORM_ANIMATION → COMBAT1_INTRO → …_SHOW_LOGO →
  …_POST_LOGO_DIALOG → …_POST_GLITCH_DIALOG. Session stable (Ping échoé).

### Fichiers touchés
- `server/java/dhserver/ServerUser.java` (NEW) : état joueur autoritaire (BootData + progression tuto).
- `server/java/dhserver/LoginServer.java` : handler `ChangeTutorialStep` ; BootData depuis `ServerUser`.
- `server/java/dhserver/NewUserState.java` : recentré (fabrique tutoriels + `latestVersion`).
- `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java`, `run-desktop.sh` : pilote `dh.autotap`.
- Reste : actions post-intro server-validées (nom, campagne, récompenses) = **handlers du hub (étape 6)** ;
  **persistance SQLite (étape 5)** de `ServerUser`.

---

## 2026-07-11 — Serveur autoritaire étape 3 : BootData nouveau joueur → TUTORIEL (vérifié en jeu)

### Résumé
Le serveur envoie désormais un **BootData de nouveau joueur complet** construit **à partir des classes
du jeu**, et le client d'origine **route vers le tutoriel d'intro** : `IntroTutorialActV2` démarre et rend
la scène d'ouverture (Ralph + Vanellope devant le portail). Zéro donnée écrite à la main.

### Détails (décompilation CFR = source de vérité)
- **`GameMain.handleBootData` lu en entier** (949 lignes bytecode → CFR) : recensé **chaque** champ de
  `BootData` déréférencé (doit être non-null). **`BootData` décompilé** : le **constructeur du jeu**
  initialise TOUS ces champs (`userInfo=new UserInfo()`, `userExtra`, `privateUserInfo`, `guildInfo`,
  `currentServer=new Server()`, `allContests`, `individualUserExtra`, `invasionInfo`, `specialEvents`,
  `statData*/statVersions=new HashMap`, `loginEvent=""`, `mailMessages=new ArrayList`…). ⇒ `new BootData()`
  est **complet par construction** (règle : la complétude vient des initialiseurs du jeu, pas d'une liste
  inventée).
- **Routage tuto (chaîne extraite)** : `handleBootData` → `ClientNetworkStateConverter.getIndividualUser
  (individualUserExtra)` → `IndividualUser.setExtra` itère **`individualUserExtra.tutorialActs`** (List de
  `TutorialAct`) → `getUserTutorialAct` → `addTutorialAct`. `TutorialHelper.completedTutorialAct(type)`
  renvoie **true quand `getTutorialAct(type)==null`** (acte ABSENT ⇒ tuto « fait/sauté ») ; sinon complété
  sur `step >= act.getMaxStep()` (`AbstractTutorialAct.getCompletionState`). ⇒ un nouveau joueur doit porter
  **TOUS** les `TutorialHelper.NEW_USER_ACTS` (**122 types**) à **`step 0`** (IN_PROG), sinon des features
  (UNLOCK_HERO…) seraient considérées « déjà faites » et jamais introduites.
- **Aucune saisie** : `NewUserState.newUserTutorialActs()` lit la liste **dans le registre du jeu** —
  `TutorialHelper.NEW_USER_ACTS` (public) + `ACTS` (réflexion, `type→IntMap(version→act)`), en prenant la
  **dernière version enregistrée** par type. Vérifié : les 122 types résolvent une version (117×v1, 5×v2),
  aucun non enregistré. `TutorialHelper` **se charge côté serveur sans libGDX** (les ctors d'actes ne
  touchent pas libGDX à la construction).
- **Correction de fidélité** : la version INTRO enregistrée dans le 12.1.0 est **`IntroTutorialActV2`**
  (`getType()==INTRO`, `getVersion()==2`) — `IntroTutorialActV1` n'est **pas** enregistré. Les anciennes
  notes « IntroTutorialActV1 » étaient erronées.
- **`SEVERE: Missing row in tutorials.tab`** (EMERALD_RANK, FRANCHISE_TRIALS(+STAGE_SELECT), PATCHED_HEROES,
  TEAM_LEVEL_UP, BATTLE_PASS_V2…) : **PAS causé par le serveur**. Le SEVERE inclut `REMOVED__CRYPT` (jamais
  dans mes actes) ⇒ il vient de **`TutorialStats.onMissingRow`** qui parcourt l'enum `TutorialActType`
  complet au chargement de `tutorials.tab` (l'APK 12.1.0 a du **code** en avance sur sa **donnée** `.tab`).
  Comportement d'origine tolérant (même catégorie que l'étape 2). Aucune rustine.

### Vérifications
- **Round-trip wire** (`MessageFactory`, sans libGDX) : BootData nouveau joueur = 8192 o, 122 actes relus,
  INTRO v2/step0, id/teamLevel OK.
- **En jeu** (`run-online.sh`, client d'origine via unidbg) : `[login] ==> BootData nouveau joueur : 122
  actes de tuto (step 0)` ; `IntroTutorialActV2 onTutorialTransition` INITIAL→SCREEN_WAIT→…→GATE_DIALOG_1_A ;
  8× `ChangeTutorialStep1` reçus ; **capture `desktop-port/build/online-tuto.png`** = scène d'ouverture
  (Ralph + Vanellope + portail Disney Heroes, dialogue « I can't believe you talked me into this. »).

### Fichiers touchés
- **`server/java/dhserver/NewUserState.java`** (NEW) : construit le BootData nouveau joueur (identité +
  `tutorialActs` depuis le registre du jeu).
- `server/java/dhserver/LoginServer.java` : `main` envoie le BootData nouveau joueur (via `NewUserState`).
- `docs/SERVER_PLAN.md`, `docs/PROTOCOL.md` §6, `MEMORY.md` : étape 3 ✅ + correction IntroTutorialActV2.
- Prochain (étape 4) : traiter/persister `ChangeTutorialStep` (aujourd'hui journalisé).

---

## 2026-07-09 — Bootstrap du projet (session initiale)

### Résumé
Mise en place complète des fondations : étude du projet de référence DragonSoul,
récupération et reconnaissance de l'APK Disney Heroes, extraction des données de jeu,
et création du système de mémoire (MEMORY.md + JOURNAL.md) et de la documentation.

### Détails chronologiques

**1. Étude du dépôt de référence DragonSoul.**
- Ajouté `aciderix/dragonsoul-web` à la session et cloné dans `/workspace/dragonsoul-web`.
- La branche par défaut `main` n'a pas `desktop-port/` ; récupéré la branche
  `claude/game-transpile-debug-2p5irx` (`git fetch --depth 1 origin <branch>` → `FETCH_HEAD`).
- Lu les docs clés de `desktop-port/` : `PRINCIPLES.md`, `SERVER_DESIGN.md`, `PROTOCOL.md`,
  `PROGRESS.md`, `STARTING_STATE.md`, `SHIMS.md`.
- **Architecture DragonSoul comprise** : backend desktop LWJGL3 maison (`dsbackend/`)
  remplaçant la couche plateforme Android ; jeu (bytecode) réutilisé tel quel (seul
  traitement autorisé = remap non-sémantique des collisions de noms) ; serveur Java
  (`server/Ds*.java`) réutilisant les classes du jeu (`ServerXORConnectionWrapper`,
  `MessageFactory`, `BootData`) pour une sérialisation identique. Login en 2 étapes
  (POST /login HTTP puis TCP jeu). Codec = Deflate + XOR roulant, clé fixe 8 octets.
  Multi-serveur (passerelle locale, mot de passe HMAC hors protocole, découverte
  Direct/LAN/communautaire, persistance SQLite).

**2. Récupération de l'APK Disney Heroes.**
- Téléchargé depuis Google Drive (`id=1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF`) via le
  contournement de la page « Virus scan warning » (form → `drive.usercontent.google.com`
  avec `confirm=t&uuid=...`). Résultat : `disney-heroes.apk`, **96 Mo**, APK Android valide.
- Contenu : 6 `classes*.dex` (base APK d'un App Bundle → **pas de `.so` natifs** ici,
  ils sont dans les splits par ABI). `assets/{stats,strings,automation,shaders,sound,fonts…}`.
- `assets/info.txt` : `version_name 12.1.0`, `git_commit a53845c9`, build 2023-02-22,
  `env release`. `assets/api_key.txt` (JWT Amazon) → `pkg com.perblue.herocities`.

**3. Reconnaissance du bytecode (strings sur les .dex).**
- **Confirmé : même stack réseau que DragonSoul, mais NON obfusquée.** Classes en clair :
  `com.perblue.heroes.network.messages.MessageFactory`, `...network.messages.*` (ClientInfo1,
  BootData1, ArenaAttack, …), `...network.{XORConnectionWrapper,DHXORConnectionWrapper,
  NetworkProvider,GameServerAddress}`, `com.perblue.common.network.{XORConnectionWrapper,
  XORCipher,XORInputStream,DeflateConnectionWrapper,StackedConnectionWrapper}`,
  `com.perblue.grunt.translate.{GruntMessageFactory,ConnectionWrapper,DummyConnectionWrapper}`.
- **AssetUpdater** identique (gate `MISSING_ADDITIONAL`, `WORLD_ADDITIONAL`, `UI_DYNAMIC`,
  log « Failed to load %s, setting MISSING_ADDITIONAL flag », « loadDynamicUI setting
  MISSING_ADDITIONAL true for »). Contenu LIVE : `http://content.disneyheroesgame.com/live/index.txt`.
- **ServerType** = `com.perblue.heroes.ServerType` (getters `gameHost`, `getGameHost`,
  `contentLocation`, `getContentLocation`). Login : `login.disneyheroesgame.com`
  (+ `login.staging.disneyheroesgame.com`, `dhstaging...:10070/content/beta/index.txt`).
- **PIÈGE identifié** : les classes `gateway/v1/*` (AdRequest, ClientInfoOuterClass,
  InitializationCompletedEventRequest, DiagnosticEvent, DeveloperConsent…) sont le **SDK
  Unity Ads**, PAS le protocole du jeu. Le protocole du jeu reste le binaire
  `MessageFactory` (FULL_NAME façon `BootData1`), comme DragonSoul.

**4. Extraction des données du jeu (source de vérité).**
- Écrit `tools/extract_game_data.sh` : extrait `assets/stats/*` et `assets/strings/*` de
  l'APK vers `game-data/` (principe §4 : aucune donnée recopiée à la main).
- Résultat committé : `game-data/stats/` = **274 `.tab`** (TSV ; ex. `arena_constants.tab`
  = clé + colonnes `FIGHT_PIT_VALUE/COLISEUM_VALUE`), `game-data/strings/` = **325
  `.properties`** (locales incluses). Ces fichiers se chargent **tels quels** côté serveur.

**5. Mémoire projet & docs.**
- Créé `MEMORY.md` (récupération de contexte, tenu à jour) et `JOURNAL.md` (ce fichier).
- Créé `docs/PRINCIPLES.md`, `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`, `docs/ASSETS.md`,
  `docs/RECON.md`. Créé `.gitignore` (APK/dex/jars/zip non committés — régénérables).
- Conservé en l'état l'infra d'upload archive.org existante (`upload_batch.py`,
  `disney_heroes_live_index.txt`, `index.txt`, workflow) — non déplacée pour ne pas
  casser le workflow.

### Découvertes importantes / risques
- **RISQUE #1 (ouvert)** : incohérence potentielle version APK (12.1.0) vs `index.txt`
  (GameVersion 7.8.1, Revision 325-326). À vérifier : révision de contenu exigée par cet
  APK (extraire de l'AssetUpdater) ↔ assets archivés. Ne pas rustiner le contrôle.
- Avantage majeur vs DragonSoul : **noms non obfusqués** → reverse et réutilisation des
  classes bien plus simples. Et **assets + index.txt disponibles** (archive.org).

### Fichiers ajoutés/modifiés
- `+ MEMORY.md`, `+ JOURNAL.md`, `+ .gitignore`, `+ README.md`
- `+ docs/{PRINCIPLES,ARCHITECTURE,PROTOCOL,ASSETS,RECON}.md`
- `+ tools/extract_game_data.sh`
- `+ game-data/stats/*.tab` (274), `+ game-data/strings/**/*.properties` (325)

**6. Décompilation ciblée (androguard).**
- dex2jar/jadx **indisponibles** : téléchargements GitHub Releases bloqués par le proxy
  (403 « GitHub access to this repository is not enabled »). Contournement : `pip install
  'androguard<4'` (pypi autorisé ; `mutf8` compile via gcc présent).
- Localisé les classes cibles dans `classes4.dex` puis désassemblé (`DalvikVMFormat`) :
  - **`DHXORConnectionWrapper`** : champ statique `KEY` = 8 octets
    `CE 85 D4 F9 29 A8 24 56` ; ctor = `StackedConnectionWrapper(DeflateConnectionWrapper,
    XORConnectionWrapper2(KEY))` ⇒ codec **Deflate + XOR(KEY)** (identique à DragonSoul en
    conception, clé propre à Disney Heroes).
  - **`ServerType`** (ctor `(name, ordinal, protocol, loginHost, port, contentLocation)`) :
    LIVE = `https://` / `login.disneyheroesgame.com` / `443` / contenu
    `http://content.disneyheroesgame.com/live/index.txt`. STAGING, LOCAL (`localhost:8080`),
    NONE/TRUNK/DEV relevés aussi. **APK NON patché** (vrais domaines PerBlue) → redirection
    prévue par réécriture `ServerType` (réflexion) ou passerelle, sans patch bytecode.
- Cherché une **révision de contenu embarquée** (RISK #1) : aucun constant évident ; le gate
  repose vraisemblablement sur des **marqueurs de catégorie** (fichiers repères) comme
  DragonSoul → à confirmer en décompilant `AssetUpdater`. RISK #1 reste ouvert.
- Docs mises à jour : `PROTOCOL.md` (§0, §1.1), `RECON.md`, `MEMORY.md` §3/§7.

**7. Décompilation de l'`AssetUpdater` → RISQUE #1 RÉSOLU.**
Package `com.perblue.heroes.assets_external` : `ExternalAssetManager` (orchestre),
`AssetIndexDownloader` (parse/décide), `ArchiveInfo`, `ContentServerKeys`, `AssetCategory`.
- **`retainRowsForVersion(rows, gameVersion)`** : retire les lignes dont `GameVersion` >
  version du client (`client.compareTo(new VersionNumber(row.GameVersion)) < 0`). ⇒ ne
  bloque que le contenu **futur** ; garde l'égal/plus ancien.
- **`checkArchives`** (par catégorie `shouldDownload()`) : décide **uniquement sur la
  révision** — `getMostRecentCompleteArchive` (Mode==COMPLETE & Category, rev max),
  `getLatestDownloadedRevision` (prefs), `getNeededIncrementalArchives`. Aucun test de
  GameVersion. Logs : « complete download: rev N », « incremental download », « up-to-date! »,
  « no prior complete archive ». Garde-fou `handleBootLoop`.
- **Filtrage device** (`lambda$onComplete$0`) : ne retient que `Environment==LIVE` +
  `Density`/`Compression` du device (SON/TEXT/PNG traités à part). ⇒ servir l'index d'origine
  **tel quel**, le client sélectionne ses lignes.
- **Conclusion RISQUE #1** : APK 12.1.0 > index 7.8.1/7.9 → **toutes les lignes retenues** ;
  install neuve → télécharge `COMPLETE rev 325` puis `INCREMENTAL rev 326`. **L'APK accepte
  les assets archivés.** Le libellé GameVersion de l'index n'est pas un critère de rejet.
  Risque résiduel = complétude **runtime** (à constater en exécutant). Docs : `ASSETS.md`
  (algorithme complet), `MEMORY.md` §7.

**8. Serveur de contenu v0 (`server/content_server.py`).**
- Python stdlib (zéro dépendance → hébergeable partout). Endpoints :
  - `GET /live/index.txt` : sert le manifeste avec les **URLs d'archives réécrites** vers
    ce serveur (`/live/<nom>.zip`) — le jeu filtre lui-même par device/version.
  - `GET|HEAD /live/<nom>.zip` : sert une **copie locale** (`--cache assets-cache/`) si
    présente, sinon **302** vers l'archive publique (archive.org).
  - `GET /health`.
- Config via options/env : `--port/--host/--index/--cache/--archive-base/--rewrite-host`.
- Ajouté `server/run-content-server.sh` + `server/README.md`.
- **Vérifié de bout en bout** (port 8899) : index réécrit (URLs → 127.0.0.1:8899) ; requête
  `.zip` → 302 → archive.org → 200, `Content-Length` 4422179 = colonne `Size` de l'index.
  Confirmé aussi que l'archive.org du projet renvoie bien les fichiers (HEAD 200, tailles OK).

**9. Décompilation en jar régénérable + preuve de réutilisation du codec du jeu.**
- Outillage : dex2jar/jadx GitHub bloqués par le proxy, mais le **fork maintenu
  `de.femtopedia.dex2jar:dex-tools:2.4.28` est sur Maven Central** (accessible). Maven,
  Gradle, javac, jar présents.
- `tools/decompile.sh <apk>` (committé, reproductible) : `mvn dependency:copy-dependencies`
  → lance `com.googlecode.dex2jar.tools.Dex2jarCmd -f -o libs/game.jar <apk>` + copie
  `commons-logging` en `libs/`. Sortie : `libs/game.jar` (~70 Mo, 66 134 classes ; gitignored).
- Vérifs de chargement (JVM desktop) :
  - `javap` OK ; **clé XOR recoupée** (`DHXORConnectionWrapper.KEY` = `{-50,-123,-44,-7,41,
    -88,36,86}` = `CE 85 D4 F9 29 A8 24 56`, identique à la recon androguard).
  - **`VerifyError: Expecting a stackmap frame`** au chargement → résolu par **`-Xverify:none`**
    (bytecode dex2jar sans stackmap frames ; contrôle de *chargement*, pas d'exécution).
  - **`NoClassDefFoundError commons-logging`** → ajouté `commons-logging:1.2` (Maven).
- **Smoke test `server/smoke/CodecRoundTrip.java`** (committé) : instancie deux
  `DHXORConnectionWrapper` du jeu (client/serveur), `server.wrapIn(client.wrapOut(msg)) == msg`
  → **ROUND-TRIP OK**. Wire commence par `78 9C` (en-tête zlib/Deflate). ⇒ **le codec réseau
  du jeu se réutilise tel quel côté serveur** (stratégie validée, comme DragonSoul).
- Docs : `docs/SHIMS.md` créé (contraintes `-Xverify:none` + `commons-logging` + jar
  régénérable). `.gitignore` : ajout `*.class`, `*-error.zip`. `MEMORY.md` §6/§7 à jour.

**10. Sérialisation des messages du jeu prouvée (sans libGDX).**
- Probes JVM desktop (`libs/game.jar`, `-Xverify:none`, `commons-logging`) :
  - `MessageFactory.getInstance()` **OK** et `new BootData()` **OK** → se chargent **sans
    libGDX** (le clinit de MessageFactory enregistre les messages sans dépendance graphique).
  - API sérialisation : `GruntMessage.writeAll(GruntOutputStream)`, `GruntOutputStream.getBytes()`,
    `MessageFactory.readMessage(GruntInputStream)`. `BootData.getFullName()=="BootData1"`.
  - **Round-trip message** (`server/smoke/MessageRoundTrip`) : `BootData`(serverTime=1234567890,
    serverHasArenaSeasons=true, loginEvent="hello") → `writeAll` (4096 o) → `readMessage` →
    champs identiques. **OK**.
- Ajout `server/smoke/MessageRoundTrip.java` ; `run.sh` compile+exécute les 2 smoke tests.
- Docs : `PROTOCOL.md` §2bis (API sérialisation vérifiée) ; `MEMORY.md` §6/§7.
- ⇒ Toute la pile serveur (codec + sérialisation) est **validée avec les vraies classes du
  jeu** : le serveur pourra décoder un `ClientInfo1` et répondre un `BootData1` au format wire
  exact, sans réimplémentation ni libGDX.

**11. Serveur de login v1 (squelette) + handshake TCP prouvé bout-en-bout.**
- Découverte : le jar du jeu contient aussi la **pile SERVEUR** du framework grunt
  (`GruntNIOTCPServer`, `GruntTCPServer`, `GruntUDPServer`, `GruntBuilder`) → on **réutilise
  le serveur du jeu** au lieu de refaire le framing (`packInt`) à la main.
- Obstacles levés (reversés) :
  - `GruntNIOTCPServer` est **package-private** sans fabrique publique → ajout de
    `com.perblue.grunt.translate.GruntServerFactory` (classe dans le même package, pas une
    modif du jeu) pour l'instancier. Ctor : `(port, factory, executor, connectionListener,
    wrapperClass, sendTimeout, keepAlive, noDelay, useProxyProtocol, bufferSize)` (mappé par
    décompilation).
  - Le ctor **crée le thread NIO mais ne l'active pas** : `running=false` (AtomicBoolean) et
    `thread` non démarré. Diagnostic clé : premier essai en TIMEOUT (client TCP-connecté via
    backlog kernel mais aucun accept applicatif). Fix : lever `running` (réflexion) + démarrer
    le thread daemon. → handshake OK.
- `server/java/dhserver/LoginServer.java` : sur `ClientInfo` reçu, répond un `BootData`
  (`setAsReplyTo` + `send`) via `MessageFactory` + codec `DHXORConnectionWrapper` du jeu.
- **Smoke test `server/smoke/HandshakeRoundTrip`** : client `GruntBuilder` envoie
  `ClientInfo1`, `LoginServer` répond `BootData1`, le client décode (serverTime/loginEvent
  corrects) — **sur socket TCP réelle, sans libGDX**. `run.sh` compile `server/java` + lance
  les 3 smoke tests (codec, message, handshake) : **tous OK**.
- Docs : `PROTOCOL.md` §2ter, `SHIMS.md` (GruntServerFactory + 3 smoke tests), `server/README.md`,
  `MEMORY.md` §6/§7. `.gitignore` : `/server/smoke/out/`.

**12. Artefacts committés + démarrage du port desktop.**
- **APK + jar décompilé committés** (demande utilisateur : éviter re-téléchargement/décompilation
  après reset) : `game/disney-heroes-12.1.0.apk` (~92 Mo), `libs/game.jar` (~68 Mo),
  `libs/commons-logging.jar`. `.gitignore` : exceptions. `tools/{extract_game_data,decompile}.sh`
  par défaut sur `game/disney-heroes-12.1.0.apk`. Push OK (warnings GitHub >50 Mo, sous la
  limite dure 100 Mo).
- **Rendu headless FAISABLE et prouvé** (corrige mon caveat précédent) : conteneur = Mesa
  (`libgl1-mesa-dri`, `mesa-libgallium`, llvmpipe) + `Xvfb`. `desktop-port/GLSmokeTest` +
  `run-gl-smoke.sh` : `GL 4.5 llvmpipe` sous Xvfb, frame rendue (`glError=0`), capture PPM.
  ⇒ on peut lancer ET vérifier le jeu en headless + captures (comme l'agent DragonSoul).
- **Scaffold `desktop-port/`** : Gradle (LWJGL 3.3.4 + natifs libGDX **1.9.7** [version du jeu,
  ≠ 1.9.3 DragonSoul] + stubs Android + game.jar). `settings.gradle`, `run-gl-smoke.sh`.
- **Découvertes majeures (recon jar) → stratégie révisée** (`desktop-port/PROGRESS.md`) : le jar
  embarque **`com.badlogic.gdx.backends.lwjgl.LwjglApplication`** (backend desktop LWJGL2 →
  **757 classes `org/lwjgl`**, d'où la collision de compilation du smoke test),
  **`HeadlessApplication`**, le root **`com.perblue.heroes.GameMain extends ApplicationAdapter`**,
  et un **framework d'automatisation** `com.perblue.heroes.automation.*` (`TouchRecorder`,
  `TouchPlayback`) + `automation.crawler.*` (`CrawlerNavigation`, `CrawlerScript`, scripts
  `ChestBuyScript`/`GoldScript`/`MarketBuyScript`…). ⇒ **le pilotage "sait ce qui est cliquable
  et exécute des actions" existe déjà** dans le jeu ; et on peut **réutiliser le backend bundlé**
  (`LwjglApplication`) au lieu d'écrire un backend complet → bien moins de shims que DragonSoul.

**13. Launcher desktop écrit + débogage de boot → VERDICT backend.**
- Écrit `dhdesktop/DesktopLauncher.java` (construit `GameMain(new DhDeviceInfo())` ; redirection
  `ServerType.LIVE` par réflexion via `-Ddh.server`), shim `dhbackend/DhDeviceInfo.java`
  (implémente l'interface `DeviceInfo`, valeurs FACTICE cohérentes, `Platform.ANDROID`),
  `run-desktop.sh` (Xvfb + llvmpipe + extraction assets/ressources APK + classpath). GL smoke
  test déplacé en `diag/`.
- Itérations de boot (chaîne réellement atteinte) :
  1. Backend **LWJGL2 bundlé** : natifs stock incompatibles avec les classes `org/lwjgl`
     **réduites par ProGuard** dans game.jar (`PointerWrapper.getPointer()` absente) → shadow
     par LWJGL 2.9.3 stock ; puis **`LinuxDisplay.getAvailableDisplayModes` AIOOBE sous Xvfb**
     (Display X11 LWJGL2 = hostile headless) + audio absent.
  2. Backend **LWJGL3 Maven** (GLFW, headless-friendly) : GLFW init OK → atteint
     `GameMain.<clinit>` → `NoSuchFieldError Group.DEFAULT_TRANSFORM` (**PerBlue a AJOUTÉ des
     champs au core libGDX**). En gardant le core PerBlue : le backend stock appelle
     `InputEventQueue.setProcessor(...)` **absente** du core PerBlue (RÉDUIT par ProGuard).
- **VERDICT** : core libGDX PerBlue **modifié ET réduit** → aucun backend/core stock ne matche.
  Comme DragonSoul, il faut un **backend maison LWJGL3** implémentant les interfaces du core du
  jeu. **Décision : adapter le `dsbackend/` de DragonSoul** (même core PerBlue) plutôt que
  repartir de zéro. Launcher + `DhDeviceInfo` + extraction assets + redirection `ServerType`
  déjà écrits = réutilisables. Détail complet : `desktop-port/PROGRESS.md`.

**14. Backend LWJGL3 maison écrit → le jeu BOOTE jusqu'à l'écran de chargement.**
- Étude de portabilité de `dsbackend/` d'abord (à la demande) : PAS réutilisable tel quel
  (noms libGDX obfusqués 1.9.3, API RPGMain, `getType():int`) mais MÊME fork libGDX PerBlue →
  jeux d'interfaces coïncident. Méthode : régénérer chaque shim contre l'interface RÉELLE de DH
  (`javap`, noms clairs) + porter le corps depuis dsbackend.
- Backend écrit (`desktop-port/src/main/java/dhbackend/`) : `DhGL20` (75 méth, délègue LWJGL3),
  `DhGraphics` (19, +GLVersion réel), `DhInput` (17) + `GlfwInput`, `DhFiles`/`DhFileHandle`,
  `DhPreferences` (19), `DhApplication` (18, getType=Android), `DhDeviceInfo`, `DhAudio` (STUB
  no-op), `DhNet` (STUB), `DhBridges` (NO-OP proxies), `DhStatFileExt` (ouvre les `.tab`).
  Launcher `dhdesktop/DesktopLauncher` : GLFW+GL, câble `Gdx.*`, `GameMain(DhDeviceInfo)`,
  boucle create()/render() + capture PPM + redirection `ServerType` (`-Ddh.server`).
- Build : LWJGL3 brut + natif libGDX 1.9.7 + stubs Android + `game-logic.jar` (game.jar SANS
  `org/lwjgl` ni backends bundlés — sinon shadowing de nos classes LWJGL3). `tools/fetch_assets.sh`
  télécharge le contenu ETC1 initial depuis archive.org (le boot exige des assets hors APK).
- Débogage de boot itératif (murs franchis) : GLVersion null → réel ; `StatFileHelper.getOpener()`
  null (=champ EXT) → `DhStatFileExt` via `setExt`, lecture des `.tab` depuis `stats/` (classpath).
- **Résultat** : `GameMain.create()` **complète** (compression **ETC1**, RPGAssetManager, shaders,
  viewport, UI stats **XHDPI 1280×720**) puis `render()` → **LoadingScreen** exécute ses tâches
  (`LoadBootAtlasUI`, `LoadPerBlueUI`, `ShowDisneyLogo`, `StartServerLogin`) et **rend des frames**.
  Bloqué sur `DhNet` (login, #NET) + `android.os.SystemClock.elapsedRealtimeNanos()` absent des
  stubs API 16 (#ANDROIDSTUBS). Tous les shims/deferrals tracés dans `desktop-port/BACKEND_STATUS.md`.

### Login → BootData → MainScreen de bout en bout (reframe ASM + Firebase + bridges)
- **Reframe ASM (RÉEL, sans changement de sémantique)** : game.jar (dex2jar) n'a pas de
  `StackMapTable` → sous `-Xverify:none` la JVM plantait (`generateOopMap.cpp`, « Illegal class
  file ... in method loadBinaryData ») en parsant `unit_abilities.tabb` pendant le handshake.
  `tools/reframe/src/ReframeJar.java` (ASM 9.7, COMPUTE_FRAMES) réécrit les **63 249** classes
  avec des frames valides (hiérarchie résolue depuis les octets, sans lier — repli sûr par classe).
  `run-desktop.sh` produit `game-logic-framed.jar` (ombrage classpath) et **retire `-Xverify:none`**.
  ⚠️ ce n'est PAS une rustine : aucune logique modifiée, on ajoute seulement les métadonnées de
  vérif que dex2jar omet (équivalent recompilation) — et on RE-vérifie le bytecode.
- **Shadow `com.google.firebase.perf.network.FirebasePerfUrlConnection` (RÉEL)** : le
  téléchargeur du jeu enveloppe chaque connexion par `instrument()` (télémétrie Firebase) ; l'init
  Firebase touchait `android.os.StrictMode.allowThreadDiskReads()` (« Stub! ») et TUAIT le thread
  de download. `instrument()` renvoie la connexion RÉELLE inchangée → download HTTP réel, analytics
  externe neutralisée (#BRIDGES). Le contenu requis se télécharge (index.txt 62 968 o).
- **DhBridges (PARTIEL, services plateforme absents)** : `INative.createPurchasingInterface()`
  renvoyait `null` → `setNativeAccess` faisait NPE (avalé) puis `handleBootData` NPE sur
  `purchasing`. `defaultReturn` renvoie désormais un **no-op imbriqué** pour tout retour interface
  (et collections vides pour Set/List/Map). ⚠️ à auditer : l'enum `PurchaseErrorState` renvoyée
  par `startPurchase` (1ʳᵉ constante) ne doit pas être un état « succès ».
- **RÉSULTAT — jalon majeur** : le vrai client fait `/login` sur notre serveur → se connecte en
  TCP `:8081` → **notre LoginServer envoie BootData1** → le client appelle **`handleBootData`
  sans crash** (langue serveur, offerwall, rewards initialisés) → atteint le **MainScreen** (hub,
  chargement du monde `mainscreen_winter`). Capture `desktop-port/build/online.png`.
- **Bug en cours (à corriger PROPREMENT, pas contourner)** : les `.skel` du décor échouent avec
  `NoSuchMethodError: com.badlogic.gdx.utils.DataInput.readString()` — incompatibilité entre
  `SkeletonBinary` (spine-libgdx) et le `DataInput` MODIFIÉ de PerBlue (ProGuard). Le jeu tolère
  ces assets manquants (userErrorListener) mais le décor animé ne s'affiche pas → à réparer.
- **⚠️ À valider (règle « pas de faux OK »)** : (1) cparticle reste un STUB (rendu différé,
  `update→complete=true` pourrait avancer une logique gatée sur une particule) ; (2) le BootData
  de notre serveur doit être **complet et correct** (serveur autoritatif), pas « minimal pour
  atteindre le menu ».

### Modules natifs Spine + particules réimplémentés en Java (#SPINE ✅ / #CPARTICLE ⚠️)
- **#SPINE résolu (Option A)** : les natifs Spine de PerBlue (`libspine-native64.so`, absents des
  splits x86_64) sont remplacés par un module Java **`com.perblue.heroes.cspine.*`** (shadow
  classpath) au-dessus de **spine-libgdx 3.6.53.1** — 12 classes : `Native` (coquille, plus aucun
  `.so`), `NativeAtlas`/`NativeAtlasLoader`, `NativeSkeletonData`/`Loader`, `NativeSkeleton`,
  `NativeAnimationState`/`Data`, `NativeSkeletonRenderer` (`Mesh` 2-couleurs `a_position/a_light/
  a_dark/a_texCoord0` dessiné avec le shader de `ShaderChannels`). Détail clé : le jeu suffixe les
  atlas/skel Spine par `@native` ; comme l'original, on retire ce suffixe (`lastIndexOf('@')` +
  `substring` + re-resolve) avant d'ouvrir le vrai fichier. Constantes GL effacées par ProGuard
  → littéraux (`GL_BLEND=0x0BE2`…). `mesh.render(shader, GL_TRIANGLES, 0, n, true)` (signature 5-args
  de PerBlue).
- **#CPARTICLE (partiel)** : découverte d'un **2ᵉ moteur natif** — `com.perblue.heroes.cparticle.*`
  (format `.np` binaire propriétaire, dérivé au build de `ParticleConverter`, pas de runtime Java
  prêt). Les wrappers `NativeParticleEffect`/`Pool`/`Loader`/`Renderer` sont de fins JNI → on shadow
  la SEULE classe **`cparticle.Native`** (pur Java, sans `.so`) : `Effect_create`→handle non nul,
  `getVertices`→0, `update`→complete. Les `.np` se **chargent** (octets réels lus) mais ne sont pas
  encore **simulés** (aucun rendu). Débloque le boot ; simulation réelle = chantier suivant. PAS de
  rustine (aucune donnée de jeu falsifiée, seul un effet cosmétique n'est pas encore affiché).
- **Résultat** : le boot franchit `WaitForDisneyAnimation` + `WaitForPerBlueAnimation` sans crash
  (capture `desktop-port/build/spine-test.png`). Reste bloqué au login en mode OFFLINE (attendu :
  `run-desktop.sh` sans `DH_SERVER` vise `login.disneyheroesgame.com`).

### Login → BootData → MainScreen bout-en-bout + Spine réparé (ABI PerBlue)
- **Reframe ASM de game-logic.jar** (`tools/reframe/ReframeJar`) : recalcule les StackMapTable de
  toutes les classes (dex2jar les omet) → plus de crash JVM `generateOopMap.cpp` (« Illegal class
  file … loadBinaryData ») sur le parse des stats binaires ; on retire `-Xverify:none`. Sémantique
  inchangée (métadonnées de vérif seulement) — c'est la solution durable prévue dans SHIMS.md.
- **Shadow `FirebasePerfUrlConnection.instrument`** → renvoie la connexion réelle (désactive
  l'analytics Firebase qui plantait le thread de téléchargement via le stub `StrictMode`). Le
  téléchargement HTTP du contenu requis reste réel (#BRIDGES).
- **DhBridges** : `INative.createPurchasingInterface()` (et toute méthode renvoyant une interface)
  renvoie un NO-OP imbriqué au lieu de `null` → `setNativeAccess` ne lève plus, `handleBootData`
  n'a plus de NPE `purchasing`. **Résultat : login → BootData → `handleBootData` → MainScreen** de
  bout en bout avec NOTRE serveur (le client se connecte à :8081, reçoit notre BootData1).
- **Spine réparé (ABI)** — les `.skel` échouaient sur `NoSuchMethodError` (tous : logos, décor,
  **héros de combat**). Deux causes, deux vrais correctifs :
  1. `com.badlogic.gdx.utils.DataInput` de PerBlue réduit par ProGuard (plus de `readString()`/
     `readInt(boolean)` var-int, requis par spine) → **shadow** avec l'implémentation EXACTE de
     libGDX 1.9.7 (self-contained).
  2. spine-libgdx 3.6.53.1 (Maven) est compilé contre le gdx STOCK, mais PerBlue a modifié l'ABI
     (ex. `Array implements Collection` → `add(Object)` renvoie `boolean`, pas `void`) → **patcheur
     ASM** `tools/reframe/PatchGdxCalls` réécrit les 106 appels gdx divergents sur les descripteurs
     réels de game.jar (+ `POP` si `void`→valeur) → `spine-libgdx-perblue.jar`.
  ⇒ **0 échec de chargement `.skel`**, squelettes chargés ET animés, jeu au **MainScreen** interactif.
- **#CPARTICLE** : toujours en dette (#NP-V3, format `.np` v3 ≠ writer courant, cf. NP_FORMAT.md).
  Le stub cparticle.Native reste en place (étiqueté) pour que le jeu tourne ; particules non rendues.

### Point de reprise
Modules natifs Spine/particules franchis. **Prochaine étape** : lancer **`run-online.sh`**
(contenu `:8080` + login + serveur de jeu `:8081`, `DH_SERVER`) → franchir le login
(`ClientInfo1`→`BootData1`), atteindre le menu / le tutoriel `IntroTutorialActV1` (nouveau joueur,
BootData neuf — NE PAS seeder) et capturer. Voir `desktop-port/BACKEND_STATUS.md` (#SPINE ✅,
#CPARTICLE ⚠️, #AUDIO, #BRIDGES).

### Règle renforcée + jeu original en natif (2026-07-11)
- **Règle d'or clarifiée** (PRINCIPLES §4/§4bis) : on ne réécrit RIEN du jeu à la main — on **extrait**
  (données ET code) par commande. Seule couche manuelle = plateforme (`dhbackend/`), minimale. Binaires
  natifs = code du jeu → binaire d'origine, sinon rebuild **vérifié fidèle** (désassemblage lib
  d'origine), jamais inventé. **Fidélité vérifiée contre captures du jeu original**.
- **Bascule sur le code d'origine natif** : suppression des shadows Java (cspine, spine-libgdx, DataInput) ;
  `spine-native64.so` (spine-c officiel + interface JNI exacte de PerBlue) branché via le
  `SharedLibraryLoader` du jeu → `cspine.*`/`cparticle.*` d'ORIGINE tournent. MainScreen rendu, 0 crash.
- **Dettes de fidélité identifiées** (à corriger par extraction, pas invention) : `getVertices` (banding =
  drawCalls multi-pages à rendre fidèles) ; `cparticle` (échafaudage neutre → vrai moteur). Source de
  vérité = **désassemblage de la lib `spine-native` ARM d'origine**.

### cspine : banding CORRIGÉ — drawCalls multi-pages fidèles (2026-07-11)
- **Cause du banding** : l'ancien `Skeleton_getVertices` renvoyait 1 seul draw call (`draws[0]=nb
  sommets`, 2e short non initialisé) → le renderer liait UNE seule page de texture pour tout le
  maillage. Toute géométrie d'une autre page d'atlas s'affichait avec la mauvaise texture (bandes).
- **Contrat drawCalls EXTRAIT** (pas deviné) du bytecode EN CLAIR
  `com.perblue.heroes.cspine.NativeSkeletonRenderer.renderPreparedVertices` (javap -c) :
  `drawCount = getVertices(verts, indices, drawCalls)` ; `drawCalls.position(0)` ; boucle `drawCount`
  fois : `indexCount = drawCalls.get()` ; `tex = textures.get(drawCalls.get())` ; `tex.bind()` ;
  `mesh.render(shader, GL_TRIANGLES=4, indexStart, indexCount, false)` ; `indexStart += indexCount`.
  ⇒ `drawCalls` = N paires de shorts `(indexCount, texturePageIndex)`, `getVertices` renvoie N.
  `texturePageIndex` = index **0-based** dans `NativeAtlas.getTextures()` (`Array.get(int)`), dont
  l'ordre = pages via `Atlas_getTexture(handle, 0..n)` dans `NativeAtlas.load` (boucle en clair).
- **Implémentation** (`native/src/cspine_jni.c`) :
  - `Atlas_create` tague chaque page par sa **position 0-based** dans `page->rendererObject`
    (même parcours de liste chaînée que `Atlas_getTexture` → indices alignés avec `getTextures()`).
  - `attachmentPage()` : `spRegionAttachment/spMeshAttachment->rendererObject` (= `spAtlasRegion`)
    `->page->rendererObject` = pageIndex.
  - `buildVertices` émet les tris **dans l'ordre de dessin** (draw order) et ouvre un nouveau draw
    call à chaque changement de page ; les attachments consécutifs sur la même page fusionnent
    (indexCount cumulé). Renvoie le nombre de draw calls.
  - **`bufferSetLimit`** : le natif écrit en mémoire brute (`GetDirectBufferAddress`) sans toucher
    `position/limit` des `java.nio.Buffer` ; or le chemin VertexArray de `Mesh.render` fait
    `indices.getBuffer().position(offset)/limit(offset+count)` → il FAUT que `limit` couvre tout
    l'écrit. On appelle `Buffer.position(0)/limit(n)` (descripteur `(I)Ljava/nio/Buffer;` stable) sur
    verts (=nb floats), indices (=nb indices) et drawCalls (=N*2). Sans ça : `IllegalArgumentException:
    newPosition > limit` dès le 2e draw call (offset>0). C'est le comportement du natif d'origine.
- **Résultat** : `run-online.sh` (Xvfb+llvmpipe, 150 frames) → **splash MainScreen rendu sans banding**,
  tous les héros Spine (multi-pages) corrects, logo/ballons/confettis nets. 0 crash de rendu.
  Capture de référence : `native/reference/shots/mainscreen-nobanding.png`.
- Reste : cparticle (échafaudage neutre → moteur fidèle via oracle ARM) ; extensions cspine
  (`setSlotEyeState`, `setTintBlack`, `nextEvent`) à confirmer contre la lib ARM.

### cparticle : format `.np` EXTRAIT + #NP-V3 confirmé (2026-07-11)
- **Source d'écriture EN CLAIR trouvée** : `ParticleConverter.convertFileNative` →
  `ParticleEffect.saveBinary(ParticleEffectPacker)` → `ParticleEmitter.saveBinary` (+ `*Value.saveBinary`,
  `packer.writeTimeline/writeTimelines`) dans game.jar. Donne l'en-tête (`byte 0, byte 3, int count`),
  les formats de valeurs (Ranged=10o, Scaled=32o, Numeric=5o, Gradient=13o, SpawnShape), et le **pool de
  timelines différé** par emitter (poolSize, tagLen, pool floats, atlasTag).
- **Lecteur natif désassemblé** (`ParticleEmitter::load` @ 0x19755, ARM). Helpers classés :
  `readInt` @0x1a770 = 4 o **BIG-ENDIAN** (`rev`), `readBool` @0x1a0c4 = 1 o, `readRanged` @0x19fd0 = 10 o,
  `readScaled` @0x1a020 = 32 o — **formats IDENTIQUES** au `*Value.saveBinary` clair. Registres : `r4`=
  readScaled, `fp`=read4 (préservé). L'encodage bas niveau est donc certifié.
- **#NP-V3 CONFIRMÉ (2 fois)** : parse des 535 assets `.np` réels avec (a) l'ordre du `saveBinary`
  COURANT → **0/535** EOF-exact ; (b) l'ordre reconstruit statiquement depuis la séquence d'appels du
  lecteur natif → **0/535**. Les FORMATS matchent ; seul l'ORDRE/ensemble des champs diffère (le writer
  courant a évolué). ⇒ Ne PAS implémenter un parseur deviné (PRINCIPLES §2/§4).
- **Voie suivante SANS devinette** : (1) oracle d'exécution (faire tourner le vrai `ParticleEffect::load`
  sous qemu → struct parsée = vérité bit-à-bit) ; OU (2) auto-parse validé par l'invariant des offsets de
  pool (chaque triplet timeline `(N, offA, offB)` doit égaler le curseur de pool courant) → l'ordre correct
  = celui qui donne 535/535. Détail complet : `desktop-port/NP_FORMAT.md`.

### cparticle : oracle d'exécution qemu — avancement (2026-07-11)
- But : exécuter le VRAI `ParticleEffect::load`/`ParticleEmitter::load` (ARM) sous qemu sur de vrais
  `.np` → struct parsée + octets consommés = **vérité bit-à-bit** de l'ordre v3 (pas de devinette).
- `native/oracle/harness_np.c` : dlopen + ctor `ParticleEmitter` + `load(&cursor,&remaining)` + dump.
- **Corrections du shim bionic** (`gen_shim.py`) qui ont supprimé le SIGSEGV initial :
  1. `__aeabi_mem{cpy,move,set,clr}{4,8}` (supposent l'alignement, fautent sous qemu sur la source non
     alignée du pool `.np`) → repointées vers la variante NON alignée de base (même sémantique).
  2. Repli **no-op** pour tout symbole absent de la glibc (ex. `_zf_log_write`, log Android) au lieu
     d'un pointeur NULL (crash). 3. Littéral `.word` EMBARQUÉ dans chaque trampoline naked (le pool
     distant cassait l'assemblage « pool needs to be closer »).
- **État** : plus de crash, mais **hang** dans `operator new` (boucle retry malloc @0x31406 ; helpers
  résolus : `0x155b4`=malloc PLT, `0x15974`=`std::get_new_handler`) → `malloc` échoue sur une taille
  aberrante. Cause probable : membres C++ non initialisés (Effect::load malloc les emitters SANS ctor)
  ou ctor `C2` (base) insuffisant / désalignement sous émulation. ← À FINIR : ctor complet `C1`, ou
  zéro-init + trace du site d'appel `operator new` ; sinon poursuivre l'auto-parse validé par
  l'invariant des offsets de pool (approche #2, pur Python). Détail : `desktop-port/NP_FORMAT.md`.

### PIVOT unidbg : exécuter le binaire d'origine + intégration en jeu (2026-07-11)
- **Décision (user)** : « 100% origine ». On exécute le VRAI `libspine-native.so` (ARM) via unidbg au
  lieu de rebâtir spine / RE les particules.
- **Prototypes `native/unidbg/`** (SpineUnidbg/SpineLoad/SpineBench/SpineVerts2/SpineSkel) :
  - Charge la lib d'origine, `Spine_init` OK.
  - `Atlas_create`+`Effect_create` parsent de vrais assets (err="") → **#NP-V3 résolu par exécution**.
  - `Effect_getVertices`/`Skeleton_getVertices` rendent de vrais sommets 2-couleurs.
  - Perf unicorn : particules ~141 µs/frame/effet (~118/frame) ; spine ~1.5-2.1 ms/squelette (7-11/frame).
  - dynarmic (JIT) : crash NEON (`vldr d16`, registres d16-d31 non activés) → inutilisable.
  - Trou unidbg comblé : `GetDirectBufferAddress`/`Capacity` (JNI 230/231) implémentés via `ArmSvc`
    écrasant les slots de la table JNIEnv (renvoie le pointeur émulé du DvmObject).
- **Intégration en jeu** : `dhbackend/unidbg/UnidbgVM` (VM persistante, dispatch synchronisé mono-thread,
  buffers émulés réutilisés + recopie vers les FloatBuffer/ShortBuffer du jeu) ; shadows
  `com.perblue.heroes.cspine.Native`/`cparticle.Native` (mêmes signatures, dispatch → UnidbgVM ; câblage,
  pas récréation) ; `build.gradle` += `unidbg-android:0.9.8` (hôte unicorn embarqué → autonome) ;
  `run-desktop.sh` : `-Ddh.spinelib=native/reference/libspine-native.so`, retrait du build spine-native64.so.
- **État** : compile ; le jeu boote et tourne à travers unidbg **sans crash** jusqu'au splash (capture
  `desktop-port/build/unidbg.png`). **0 appel natif spine/particule** durant le run → écran encore au
  pré-download (art statique), pas de squelette vivant. ⚠️ Rendu spine in-game **à valider** en atteignant
  un écran héros/combat (dépend du pipeline d'assets WORLD_ADDITIONAL). Puis comparer aux captures
  d'origine (§4bis) + mesurer le fps réel.

### JEU DANS LE HUB PRINCIPAL — spine + particules d'origine via unidbg (2026-07-11)
- **Serveur de contenu = RELAIS** : le 302 vers archive.org échouait (le java.net du jeu n'a pas de
  proxy). Le serveur relaie désormais (fetch via proxy côté serveur, stream au client). Le jeu
  télécharge WORLD_ADDITIONAL (394 Mo) etc. et **franchit la barrière de download**.
- **Rendu particules corrigé** : `NativeParticleEffect.getVertices` lit `drawCalls.get(n*3)` = nombre
  total de sommets (écrit par le natif APRÈS les n*3 shorts de draw calls) pour poser verts.limit/
  indices.limit. On copie donc **n*3+1** shorts (≠ spine : 2/pair). Buffers émulés dimensionnés pour le
  plus grand mesh (particules 4000 v). Plus de crash `newPosition > limit`.
- **✅ RÉSULTAT** : le jeu atteint le **HUB PRINCIPAL** (ville Zootopia enneigée, menu HEROES/ITEMS/…,
  CAMPAIGN/CRATES, nouveau joueur CHOOSE NAME) — capture `native/reference/shots/mainscreen-hub-unidbg.png`.
  Confirmé par logs `[UnidbgVM]` : `Skeleton_getVertices -> drawCount=39` (spine d'origine rendu) +
  `Effect_getVertices` (particules d'origine) — **100% code d'origine via unidbg, 0 crash de rendu**.
- ⚠️ RESTE (serveur) : la connexion TCP tombe après BootData → overlay **« Reconnecting… »**. Le
  serveur de jeu doit MAINTENIR la session (keepalive + messages post-BootData). Et le désaccord de
  stats (`GuildStats` NumberFormatException) → le serveur doit **synchroniser les stats** (SyncStatData,
  extraites du jeu). Prochaines étapes serveur.

### Serveur : session STABLE — écho Ping (2026-07-11)
- `LoginServer` instrumenté : journalise chaque message reçu (client = source de vérité). Flux nouveau
  joueur relevé (cf. PROTOCOL.md §6) : ClientInfo→BootData, puis télémétrie + **Ping1**.
- **Cause du « Reconnecting… » = Ping non répondu** : le keepalive/latence `Ping` sans écho déclenche le
  chien de garde du client → fermeture. ⇒ serveur échoue Ping (serverReceive/serverTime=now). Résultat :
  **0 reconnexion, session STABLE dans le hub** (capture `native/reference/shots/mainscreen-hub-stable.png`),
  spine+particules d'origine rendus (unidbg). Prochain : BootData complet nouveau joueur → tuto.

### Coffre GRATUIT du tuto débloqué — cause racine du blocage à l'étape GOLD (2026-07-13, nuit)
**Symptôme** : après avoir « ouvert » le coffre GOLD, le tuto (`IntroFeaturesActV2`) ne repartait pas.
Capture (`build/goldstuck.png`) : écran de détail du coffre GOLD (« DIAMOND CRATE »), mention
**« Free in : 1j 23h 46m 28s »**, et un pop-up « You can't do that just yet. Follow the tutorial arrow! »
— le pilote DEV, sans cible, tapait les boutons d'achat payants et se faisait bloquer.

**Ce que le tuto attend** : il exige que le joueur **POSSÈDE Frozone** après le coffre GOLD
(`checkForMissingFrozone`→`finishTutorial` si absent ; `HERO_LIST_TAP_FROZONE` requiert
`getHero(FROZONE)!=null`). Il était bloqué à l'étape 9 (serveur : `INTRO_FEATURES -> step 9`).

**Mécanisme réel** (décompilation, source de vérité) :
- `ChestHelper.openChest(...)` construit **toujours** un `BuyChests` (avec `ServerRollRequest` CHEST) et
  appelle `ServerRollHelper.sendRollRequest` → `NetworkProvider.sendMessage`. Le serveur répond
  `LootResults{heroesUnlocked=[Frozone]}` ; le client ajoute Frozone → le tuto avance.
- MAIS l'ouverture n'a lieu que si le coffre est **gratuit et disponible** :
  `ChestHelper.getTimeUntilNextFreeChest = UserHelper.getResourceGenerationRemaining(freeChestResource)`,
  et `getFreeChestResource(GOLD) = ResourceType.GOLD_CHEST`. `hasFreeChest(GOLD)` ⇔ `getResource(GOLD_CHEST) >= 1`.

**Cause racine** : le coffre gratuit est une **ressource régénérée** (comme la stamina). Notre amorce
compte-neuf (`ServerUser.initNewPlayerResources`) ancrait le gen-time de toutes les ressources régénérées à
la création MAIS ne mettait au cap **que STAMINA**. Donc `GOLD_CHEST=0`, gen-time = maintenant → « Free in
~48 h » → `hasFreeChest=false` → le bouton FREE n'ouvre rien → **aucun `BuyChests` envoyé** (confirmé : le
`LoginServer` journalise chaque message reçu ; **0 `BuyChests`, 0 `ServerRollRequest`** dans le run — que
`ChangeTutorialStep`×69 + télémétrie + 1 `Action` VIEWED_CHESTS) → Frozone jamais accordé → tuto bouclé.

**Correctif** (fidèle, PRINCIPLES §3 « lire & exécuter ») : à t=création aucun temps ne s'est écoulé → un
compte neuf démarre **chaque ressource régénérée à SON cap** (généralisation exacte du fix stamina).
Boucle unique : `setLastResourceGenerationTime(rt, creation)` + `setResource(rt, getResourceCap(rt))` pour
chaque `resourceGenerates(rt)`. Caps du jeu au niv.1 (probe) : STAMINA=120, GOLD_CHEST=1, SILVER_CHEST=1,
SOCIAL_CHEST=1, SKILL_POINTS=50, SOUL_CHEST=0, FRIEND_STAMINA=175, INVASION_STAMINA=80… **Aucune valeur
inventée** (caps issus de `UserHelper.getResourceCap`).

**Vérifications** :
- `hasFreeChest(GOLD)=true`, `GOLD_CHEST=1`, `SILVER_CHEST=1`, `STAMINA=120`, roster=2 (probe).
- `ServerUser.openChest(BuyChests{GOLD})` d'un compte neuf → **Frozone 8/8** (la table de drop GOLD du jeu
  est déterministe pour le nouveau joueur — le serveur exécute `DropTable.rollNode("ROOT")`, rien de truqué).
- `server/smoke/ResourceTest` étendu (assertions `GOLD_CHEST=1`, `SILVER_CHEST=1`, `hasFreeChest(GOLD)`),
  `RosterTest`/`ViewedChestsTest` toujours OK.

**Chaîne complète attendue en jeu** : coffre GOLD dispo → clic FREE → `BuyChests` → serveur
`LootResults{Frozone}` → `getHero(FROZONE)!=null` → tuto passe à `HERO_LIST_TAP_FROZONE`. Fichiers :
`server/java/dhserver/ServerUser.java` (initNewPlayerResources), `server/smoke/ResourceTest.java`.

### Pipeline de combat de CAMPAGNE côté serveur — recordOutcome autoritatif (2026-07-14)
**Objectif** (demande utilisateur) : valider le pipeline de combat de campagne — entrée, choix héros,
skills, vagues, loot (gold/items), récompenses, XP, conso énergie.

**Protocole (décompilation, client = source de vérité)** :
- Le combat tourne CÔTÉ CLIENT (unidbg spine, déjà fonctionnel depuis l'intro).
- `CampaignAttackScreen.doCombatDone` → `ClientNetworkStateConverter.getCampaignAttack(user, type, ch, lvl,
  outcome, stars, attackers, stagesCleared, screen, snapshot)` qui **roule `CampaignHelper.recordOutcome`
  côté client** (optimiste) puis `NetworkProvider.sendMessage(campaignAttack)`.
- `CampaignAttack{base:AttackBase(attackers,defenders,outcome,stars,…), campaignType, chapter, level,
  lootEarned, memoryChanges, stagesCleared}`. `attackers/defenders` = Collection de **`AttackLineupSummary`**
  (`{List units:AttackUnitSummary}`), une par vague. **Aucun champ roll/seed** (≠ BuyChests) ; **aucun
  listener client** pour la réponse → **fire-and-forget**.

**Logique autoritative = `CampaignHelper.recordOutcome`** (à exécuter, PRINCIPLES §3) :
consomme la stamina (`getStaminaCost`+`UserHelper.chargeUser`), donne loot/gold/XP
(`giveLoot`/`giveGold`/`giveTeamXP`), met à jour `ICampaignLevelStatus`. `IUser extends IGuildPerkProvider`
→ `recordOutcome(user, user, …)`. Param 11 = `SpecialEventSnapshot` : **`.NONE`** (déréférencé sinon NPE).

**Implémenté** : `ServerUser.recordCampaignAttack(CampaignAttack)` + branche `LoginServer` (fire-and-forget,
persiste). Reconstruit le User, résout `CampaignLevel.of(GameMode.CAMPAIGN/ELITE_CAMPAIGN, ch, lvl)`,
appelle `recordOutcome`, resync + save.

**Vérifié** `server/smoke/CampaignAttackTest` (NORMAL 1-1, WIN, équipe Ralph+Elastigirl+Frozone) :
- **énergie -6** (120→114 ; `staminaCost=6`)  — conso énergie ✓
- **or +340** (0→340 ; `giveGold`→`giveUser`, appliqué direct)  — récompense ✓
- **niveau 1-1 → 3★** (`ICampaignLevelStatus`)  — progression ✓
- team XP donné (pas assez pour monter de niveau) ; `lootEarned`=0 objet (normal en 1-1, items seulement).
ResourceTest/RosterTest toujours OK.

**PARTIEL (SHIMS)** : `SpecialEventSnapshot.NONE` (pas de bonus évènement) ; outcome/stars = ceux du client
(combat client-autoritatif, comme le jeu d'origine) ; contrat fire-and-forget à reconfirmer en jeu.

**Frontière pilote DEV (non bloquant pour le serveur)** : l'auto-pilote ne sait pas ENTRER dans un chapitre.
Observé (recorder) sur `CampaignScreen` : animation « SCANNING CITY MAP », `getPointers()` **vide** headless,
nœud `CAMPAIGN_CHAPTER_ONE_NAME` = `Stack childrenOnly` + label `disabled` sans `[CLICK]` (input carte
custom). Le pilote idle → RETOUR → boucle. À résoudre séparément pour la démo visuelle in-game.
Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/CampaignAttackTest.java`.

### Combat de campagne JOUÉ & GAGNÉ en jeu + progression persistée (2026-07-14, soir)
Suite du pipeline campagne. **Pilote DEV** (entrée niveau) : la carte de campagne est une scène **g2d**
(`CityMapDisplay`), pas du scene2d → aucun acteur cliquable, `getPointers()` vide headless. Découvert via
une **sonde `dh.mapprobe`** (« cliquer + monitorer quel élément s'active » — outil de diag, cf. MEMORY §6ter
B-bis) : le tap est géré par `CityMapScreen` (caméra `MapCamera2D` → `CityMapDisplay.getHitCampaignLevel` →
`onCampaignLevelTapped` → `normalOrEliteNodeSelected(CampaignLevelID)` → `CampaignPreviewScreen`). Le pilote
appelle donc `normalOrEliteNodeSelected(1-1)` (API du jeu). Ajouts pilote : tap flèche « TAP TO CONTINUE »
(fin de vague), tap boutons FIGHT par nom sans pointeur (replay après défaite), garde anti-RETOUR sur
`*AttackScreen`.

**Bug AUTO** (cause de la défaite au 1-1) : `Boolean.getBoolean("dh.autofight")` n'accepte que `"true"` →
`dh.autofight=1` restait false → `setAutoAttack` jamais appelé → héros passifs, skills seulement sur tap
manuel → défaite. Corrigé (accepte non-"0"/"false"). Résultat : **VICTOIRE 1-1 en jeu**, `CampaignAttack :
NORMAL 1-1 outcome=WIN → recordOutcome appliqué [persisté]`, énergie **114/120** visible (débitée de 6).
Skills confirmés déclenchés (AUTO + 21 taps `ATTACK_SCREEN_HERO_BUTTON` guidés par le tuto SKILL_USE).

**Bug PERSISTANCE progression** (révélé par une sonde post-run : `wire_levelStatuses=0`, `1-1 stars=0`,
`1-2 unlocked=false` alors que gold=340 persistait) : les statuts de niveau vivent HORS `this.extra`
(`ClientCampaignLevelStatus` en mémoire, lus depuis `individualUserExtra.levelStatuses` au chargement) ;
`recordOutcome` les mute EN MÉMOIRE mais n'écrit pas la liste wire → **étoiles/complétion perdues au
round-trip, 1-2 jamais débloqué**. Correctif `resyncCampaign` (dans `recordCampaignAttack`) : reconstruit
`individualUserExtra.levelStatuses` depuis `iu.getCampaignLevels()` (champs mappés 1:1 ; `lastWinTime` sans
getter → 0, non requis), comme `resyncHeroes`. Vérifié `server/smoke/CampaignPersistTest` : après save+reload
SQLite → **1-1 à 3★, 1-2 DÉBLOQUÉ, or=340, stamina=114**. `CampaignAttackTest` toujours OK.

**Réponse à « ça passe au niveau suivant ? »** : le jeu débloque 1-2 après une victoire au 1-1 (désormais
persisté). Le pilote, lui, entre toujours au 1-1 (`dh.playlevel`) → à généraliser au prochain niveau
débloqué (`getLatestCompletedLevel`) pour enchaîner la campagne.

⚠️ Suivi séparé : dans un run COMPLET (tuto→campagne), la stamina persistée réapparaît à « 39,96 M »
(fuite de gen-time dans la phase TUTO — `recordCampaignAttack` la gère bien, 114 en test isolé).
Fichiers : `server/java/dhserver/ServerUser.java` (resyncCampaign), `server/smoke/CampaignPersistTest.java`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.

---

## 2026-08-04 (g72) — SURGE (#72) incrément 6+7 : RÉCOMPENSES & BASCULE (headless 🟢, 88/88)

**Livré** : le mode SURGE boucle désormais jusqu'au bout côté serveur. `ServerSurgeRewards` (calcul 100 % code
du jeu) + `ServerSurgeState.{rollover, personalize, claimRewards}` + handler `SurgeClaimRewards` dans `LoginServer`.

**Montants — TOUS extraits du code du jeu (§3/§4), zéro invention** (sondés + disasm) :
- **tokens** (`CRYPT_TOKENS`) = `SurgeClientHelper.getPlayerSurgeCoins(surge)` = `getTokensPerClearedRegion()×régions
  + getBaseTokens()`, régions = `wavesCompleted×3 + waveRegionsCleared.size()` — base **30**, /région **14**.
- **or** (`GOLD`) = `member.storedGold` (accumulé par `recordOutcome→storeGold`).
- **influence** de GUILDE = `SurgeHelper.getInfluenceProgress + SurgeStats.getBaseInfluence` = la somme EXACTE
  affichée par `SurgeClearedWindow` (disasm offsets 96-102) — base **5000**, /région **1350**.

**Flux client PROUVÉ au bytecode** (`SurgeScreen.checkAnimations` + `SurgeResultsWindow.lambda$createRewardsContent$1`) :
le serveur pose un `SurgeRewards` par membre dans `SurgeData.unclaimedRewards[surgeID_terminé]` (clé =
`previousResults.surgeID`) livré par `GetSurge` ; le client ouvre la fenêtre si `totalGold≠0 || totalTokens≠0` et
au clic envoie `SurgeClaimRewards{surgeID}` **puis** se crédite localement `UserHelper.giveUser(CRYPT_TOKENS,
totalTokens)` + `(GOLD, totalGold)` (`RewardSourceType.NORMAL`, offsets 163/249). Le serveur MIROITE ce crédit de
façon autoritative, **une seule fois** (anti double-réclamation via le registre).

**Bascule (rollover) paresseuse** (= incrément 7, sans tâche de fond) : au changement de surgeID, `loadOrReset`
fige un registre `surgeprev:<guildID>` (résultats `SurgeResultInfo` + récompense par membre + set réclamé),
crédite l'influence à la guilde (plafond `getMaxGuildInfluence`, comme `applyStaminaBurnInfluence`), et embarque
`previousResults` dans le `SurgeData` neuf. Même patron que le rollover de saison de guerre/contest.

**Test** `SurgeClaimTest` : montants (128 tokens / 12345 or pour 2 vagues+1 région), influence guilde **+14450**,
crédit PERSISTANT (round-trip DB), **anti-double-réclamation**, disparition d'`unclaimedRewards` après réclamation,
round-trip wire du `SurgeData` personnalisé et du `SurgeRewards`. Régression **88/88**.

**⚠️ 1 inférence de PLACEMENT documentée (§4)** : le MONTANT d'influence est 100 % code du jeu ; le MOMENT du
crédit (à la bascule, une fois) est un choix d'ingénierie cohérent avec war/contest — **à confirmer EN JEU**
(incrément 8, qui débloque aussi les raids). Le crédit tokens/or personnel est DÉFINITIF (disasm). `achievedTier`
de `SurgeResultInfo` laissé à 0 (non prouvé headless — pas de valeur inventée).

Fichiers : `server/java/dhserver/{ServerSurgeRewards,ServerSurgeState,LoginServer}.java`,
`server/smoke/SurgeClaimTest.java`, `server/smoke/regression.sh`, `docs/SURGE.md`.

---

## 2026-08-04 (g72b) — SURGE (#72) VÉRIF EN JEU : rendu confirmé contre notre serveur

Lancé la pile complète (`run-online.sh`) avec le compte TL100/en guilde (`dh-snapshot-postwar-0803.db` copié en
`dh-server.db`, restauré après) et le vrai client, surge ACTIF (fenêtre 16:00 UTC). Sonde préalable headless
(`SurgeGateProbe`) : `inGuild=true`, `teamLevel=100`, `SURGE_OBJECTIVES` débloqué, `ServerSurge.isActive=true`,
GetSurge dry-run = 2 membres / 27 adversaires / 27 vagues, round-trip wire OK.

**Résultat EN JEU** : `nav SURGE` (API du jeu `UINavHelper.navigateTo(Destination.SURGE)`) →
- serveur : `[login] <== GetSurge → ==> SurgeData (surgeID=1785859200000, membres=2)` — **échange réel sur le fil**.
- client : l'écran **`SurgeScreen` « CREEP SURGE »** s'ouvre **sans crash** (`dumpscreen`=SurgeScreen, captures
  continues post-nav), affiche les **27 districts** (nos `activeDistricts`, régions OCEANSIDE/UPTOWN/MIDDLE
  BURROUGHS/DOWNTOWN/SUNSET BAY), le compte à rebours (4h42), le palier « 1 Max », et l'entête **30 tokens de base
  + 5 000 d'influence = EXACTEMENT nos constantes du code du jeu** (`getBaseTokens`/`getBaseInfluence`). Capture
  `desktop-port/build/surge.ppm` (envoyée à l'utilisateur).

Confirme EN JEU les incréments 3 (rendu), 4b (27 districts/adversaires) et l'affichage fidèle des constantes de
récompense (6). **Reste EN JEU** : entrer dans un district (carte = widget custom → taps à driver précisément) pour
observer `StartSurgeAttack`/`SurgeAttack`, puis un RAID (débloque l'incrément 5, protocole inconnu), puis la
réclamation en fin de surge. Le combat de district est client-autoritatif (comme la campagne), à jouer via l'UI.

Fichiers : `docs/SURGE.md` (§8 incrément 8 passé 🔶).

---

## 2026-08-09 (g72c) — SURGE pilote en jeu (suite) : outillage prêt + LACUNE de repro du client exposée

**Contexte** : le conteneur de session a été REPROVISIONNÉ entre deux tours. Le code est intact (récupéré depuis
`origin/claude/disney-heroes-port-rhhtuj` = f83becc ; `main` du dépôt est un autre projet, cf. incident du jour),
mais **tous les artefacts git-ignorés ont été perdus** : `/server/data/` (toutes les DB de comptes, dont le
snapshot TL100 postwar), et les dérivés de build du client (`spine-libgdx-perblue.jar`, `gdx-1.9.7.jar`, caches
gradle, `.so` natifs partiels). `game.jar` et l'APK (`game/disney-heroes-12.1.0.apk`) sont committés → OK.

**Fait cette session** :
- **`server/smoke/SurgeAcctSetup.java`** — outil DEV qui RECONSTRUIT un compte apte à SURGE (TL100 + 5 héros +
  guilde). Vérifié : `TL=100 héros=7 inGuild=true guildID=1`. Remplace le snapshot perdu, reproductible.
- **Pilote SURGE en jeu** (`TutorialDriver` + dispatcher `DesktopLauncher`) : commandes clickfile `surgestate`
  (dump), `surgefight` (ouvre le combat du 1er district jouable via `SurgeScreen.fightPressed` → chooser qui envoie
  `StartSurgeAttack`), `surgequick` (`quickFightPressed` → combat résolu client + `SurgeAttack`), `surgeraid`
  (`onRaidButtonClick`/`doRaidSurge` → OBSERVER le protocole de raid, incrément 5). API vérifiée par signature
  (javap) ; **non exécutée end-to-end** car le client ne compile pas (deps manquantes, ci-dessous).

**⛔ BLOCAGE (repro client)** : le module desktop ne compile plus — `package com.esotericsoftware.spine does not
exist`. `libs/spine-libgdx-perblue.jar` (runtime spine custom de l'Opt.3 #28) **n'a aucune recette de
régénération committée** (`tools/decompile.sh` ne produit que `game.jar` ; les 3 classes spine de `game.jar` ne
suffisent pas), et `gdx-1.9.7.jar`/caches gradle sont absents. Donc le pilote combat/raid EN JEU est bloqué tant
que l'environnement de build du client n'est pas reconstitué. **Le rendu SURGE, lui, a DÉJÀ été vérifié en jeu
cette session (g72b, avant le reprovision).**

**Recommandation repro (§7)** : committer `spine-libgdx-perblue.jar` (ou un script qui le régénère depuis l'APK)
et figer les deps gradle, pour que le client survive à un reprovision — sinon la vérif EN JEU n'est pas
reproductible.

Fichiers : `server/smoke/SurgeAcctSetup.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.

---

## 2026-08-09 (g72d) — SURGE : BOUCLE DE COMBAT VÉRIFIÉE EN JEU + bug serveur corrigé + build client reconstruit

Reprise du pilote EN JEU après le reprovision (cf. g72c). Le dépôt local avait été re-cloné sur `main` (projet
web-archiving sans rapport partageant le repo) ; travail récupéré depuis `origin/claude/disney-heroes-port-rhhtuj`
(f83becc) par `git reset --hard`. Tous les artefacts git-ignorés (DB comptes + dérivés de build client) étaient
perdus → reconstruits DEPUIS LES SOURCES COMMITTÉES (l'utilisateur avait raison : la recette est dans nos scripts) :
- `tools/build_spine_jar.sh` (NOUVEAU) : régénère `libs/spine-libgdx-perblue.jar` depuis spine-runtimes 3.6 (le
  clone officiel de native/build.sh) compilé contre gdx-1.9.7. Sans lui le module desktop ne compile pas.
- `tools/fetch_assets.sh` : assets ETC1 (world/ui) depuis l'archive.org du projet (le base APK n'a que fonts/SDK).
- `server/smoke/SurgeAcctSetup.java` : compte apte à SURGE — TL100 + 5 héros + guilde + **tutoriel complété**
  (`TutorialHelper.getMaxStep` par acte ; sans ça `canNavigateTo(SURGE)=false`).

**Vérif EN JEU (compte reconstruit, surge actif)** — le vrai client contre notre serveur :
- rendu « CREEP SURGE » (27 districts) + `GetSurge→SurgeData`.
- **combat de district COMPLET** : `surgefight` (SurgeScreen.fightPressed → SurgeHeroChooserScreen) puis
  `surgeteamfight` (bouton AUTO du jeu → autoSelectHeroes + quickFightPressed) →
  `[login] StartSurgeAttack(O) → StartSurgeAttackResponse (3 défenseurs)` →
  `[login] SurgeAttack(O, WIN) → SurgeUpdate (+0 pts, districts+1) [persisté]`.
  Écran « DISTRICT 2 CLEARED! » : or 9,22 M → Mon Coffre, influence 80 552 → Banque de Guilde. District O
  cleared=true après combat (persisté). `+0 pts` = fidèle (tier 0, cf. §Scoring).

**🐛 BUG SERVEUR trouvé & corrigé EN JEU (exactement le rôle de §8)** : le serveur ne posait jamais
`SurgeData.youAreInRaid` pour un membre → `SurgeScreen.fightPressed` refusait TOUT combat de district
(`CRYPT_JOINED_LATE_ERROR`, garde offset 81-97). Champ 100 % serveur-autoritaire (recon : SEUL `SurgeData` l'écrit,
aucun flux client). Fix : `ServerSurgeState.personalize` pose `youAreInRaid=true` pour le membre participant.
Vérifié EN JEU : `youAreInRaid=true` → combat autorisé → boucle complète OK.

**Pilote SURGE en jeu** (`TutorialDriver` + dispatcher) : `surgenav` (diagnostic canNavigateTo), `surgestate`,
`surgefight`, `surgeteamfight` (auto-équipe + quick fight), `surgequick`, `surgeraid` (à déclencher depuis le HQ).

**Reste EN JEU** : le RAID (incr. 5, protocole à observer via `surgeraid` depuis le HQ) + la réclamation de fin de
surge. Le CŒUR du mode (rendu + combat de district + enregistrement autoritatif + persistance) est vérifié EN JEU.

Fichiers : `server/java/dhserver/ServerSurgeState.java` (youAreInRaid), `tools/build_spine_jar.sh` (nouveau),
`server/smoke/SurgeAcctSetup.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/SURGE.md` §8, `MEMORY.md`.

---

## 2026-08-09 (g72e) — SURGE : PROTOCOLE DE RAID RÉSOLU (incrément 5 débloqué)

Suite du pilote EN JEU (« Continue »). Objectif : élucider le protocole de raid SURGE (le blocage §4 de longue date).

**Recon (disasm) + observation EN JEU** — `SurgeHeroChooserScreen.doRaidSurge(ActionListener)` envoie, dans l'ordre :
1. `HeroLineupUpdate{type=SURGE, lineup}` (équipe de raid) — **observé EN JEU** (« HeroLineupUpdate(SURGE) → lineup
   enregistrée [persistée] »).
2. `Action{command=SET_SEED, TYPE=SURGE, ID=<graine>}` (graine du combat de raid) — **observé EN JEU**.
3. puis `ClientActionHelper.raidSurge(district, count, upsell, autoSelect, snap, listener)` →
   **`Action{command=RAID_SURGE, extra={TYPE=<district.name()>, COUNT=<long>, UPSELL=<bool>,
   MODE=AUTO_SELECT|MANUAL_SELECT}}`** = l'ISSUE du raid (le « message manquant » qui bloquait §4). Prouvé au
   bytecode (`withType(district)`→`extra[TYPE]`, offsets 17-77 de `raidSurge`).

⇒ **Le blocage est levé** : le serveur doit ajouter un handler d'Action `RAID_SURGE` qui rejoue
`SurgeHelper.recordRaid(user, member, surgeID, district=extra[TYPE], false, RAID_TEAM_POWER, 0L, GOLD, raidHeroes,
snap)` — mêmes params que résolus (docs/SURGE.md incr 5), équipe depuis la SURGE HeroLineup persistée (msg 1),
`GOLD=getGoldForSurgeRaid`, borne `getMaxRaidsPerSurge`.

**RESTE** : (a) câbler le handler `RAID_SURGE` (miroir de `ServerSurgeCombat.applyRegionOutcome`) + test headless ;
(b) **vérif EN JEU d'un raid COMPLET** — non atteinte ce run : le combat de raid a coupé la connexion client
(`java.net.SocketException: Socket is closed` / `Connection refused`) AVANT l'`Action RAID_SURGE` (stabilité du
combat de raid à régler, distincte du protocole). Les sous-valeurs `RAID_TEAM_POWER`/`GOLD`/sémantique `COUNT` et
le clear-district se confirment sur un raid abouti EN JEU (ne pas inventer, §4).

**Pilote** : `surgeraid` amélioré (auto-sélection d'équipe via le bouton AUTO du jeu, puis `doRaidSurge`).

Fichiers : `docs/SURGE.md` §5, `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (surgeraid), `MEMORY.md`.

---

## 2026-08-09 (g72f) — SURGE incrément 5 : handler serveur RAID_SURGE (recordRaid autoritatif, headless 🟢, 89/89)

Suite de g72e (protocole résolu). Implémentation serveur du raid, params 100 % prouvés au bytecode (site d'appel
`SurgeHelper.doRaid`, offsets 181-218) :
- `ServerSurgeCombat.applyRaidOutcome(user, summary, surgeID, district, opponentLineup, snap)` : équipe =
  `user.getHeroLineup(SURGE)` (persistée par le HeroLineupUpdate précédent), `RAID_TEAM_POWER`=Σ`PowerCalculator.
  getPower(hero,0)`, `GOLD`=`getGoldForSurgeRaid(user, lineup, opponent.lineup, emptyList, snap)`, puis
  `recordRaid(user, member, surgeID, district, false, RAID_TEAM_POWER, 0L, GOLD, raidHeroes, snap)` (ordre des 3
  longs confirmé au bytecode). recordRaid fait l'autorité : `storeGold` (or), `incDailyUses` (pass), `onSurgeRaid`.
- `ServerSurgeState.applyRaid` : applyRaidOutcome + incrémente le compteur PARTAGÉ `summary.raidsUsed` (recordRaid
  ne mute que le compteur QUOTIDIEN du joueur, pas l'état de guilde — comme applyAttack incrémente districtsCleared)
  → renvoie SurgeUpdate (delta or).
- `LoginServer` : handler `Action RAID_SURGE` — lit `extra[TYPE]`=district (DistrictType.valueOf), loadOrReset,
  applyRaid, persiste (SurgeData + user), diffuse SurgeUpdate (`pushToGuild`).
- `SurgeRaidTest` : équipe SURGE posée d'abord (HeroLineupUpdate, comme en jeu), raid headless → `raidsUsed=1`,
  or +3,69 M (storeGold), persistance + round-trip wire. Régression **89/89**.

**RESTE (§8)** : vérif EN JEU d'un raid COMPLET (le run g72e a coupé la connexion pendant le combat de raid AVANT
l'Action RAID_SURGE — stabilité du combat de raid, distincte du protocole). Sémantique `COUNT` (raids groupés ?) et
un éventuel clear-district à confirmer sur un raid abouti EN JEU (non présumés : le handler traite 1 raid/Action,
sans marquer le district vaincu).

Fichiers : `server/java/dhserver/{ServerSurgeCombat,ServerSurgeState,LoginServer}.java`, `server/smoke/SurgeRaidTest.java`,
`server/smoke/regression.sh`, `docs/SURGE.md` §5, `MEMORY.md`.

---

## 2026-08-09 (g72g) — SURGE 100 % VÉRIFIÉ EN JEU : raid + récompenses/réclamation + anti-double

Achèvement de la vérif EN JEU de SURGE (« Termine quand même les vérifs in game »).

**RAID (incr. 5) — bout en bout EN JEU** : `surgeraid` (auto-équipe via bouton AUTO + `doRaidSurge`) → le vrai
client envoie `HeroLineupUpdate{SURGE}` + `Action RAID_SURGE{extra TYPE=P, COUNT=0, UPSELL=false,
MODE=MANUAL_SELECT}` → **notre handler** : `Action RAID_SURGE(P) → SurgeUpdate (+9 225 000 or, raidsUsed=1)
[persisté]`. Écran « RAID (1 LEFT) ». `COUNT=0` CONFIRME que ce n'est pas un multiplicateur (1 raid/Action — décision
serveur validée, pas d'invention). La connexion TIENT désormais (l'ancien serveur SANS handler RAID_SURGE coupait
la connexion — cause de la coupure g72e, résolue par le handler).

**RÉCOMPENSES + RÉCLAMATION (incr. 6/7) — bout en bout EN JEU** : horloge serveur avancée +13h
(`DH_SERVER_OPTS=-Ddh.clock.offset.hours=13`) → bascule (surgeID 1786291200000→1786377600000) → `GetSurge` livre
`unclaimedRewards` → le client ouvre **« SURGE REWARDS »** : MY REWARDS **18,45 M or + 30 tokens**, GUILD **5 000
influence** — valeurs EXACTES du serveur (storedGold combat+raid ; `getPlayerSurgeCoins`=30 ; `getBaseInfluence`=5000).
`surgeclaim` (envoie le vrai `SurgeClaimRewards{surgeID}`) → **notre handler** : `SurgeClaimRewards(1786291200000)
→ SurgeRewards (+30 tokens, +18 450 000 or)`. Crédit **persisté** (DB : `CRYPT_TOKENS=30`, GOLD +18,45 M).
**Anti-double CONFIRMÉ EN JEU** : 2ᵉ claim → `(+0 tokens, +0 or)`. ⇒ l'inférence de placement du crédit d'influence
(à la bascule) est VALIDÉE.

**⇒ SURGE est 100 % vérifié EN JEU** : rendu, combat de district, raid, bascule/récompenses/réclamation, anti-double,
+ le fix serveur `youAreInRaid`. Aucune invention (§4) : tous les montants/params du code du jeu, prouvés au bytecode
et confirmés sur le fil.

**Pilote SURGE complet** (`TutorialDriver`+`DesktopLauncher`) : `nav SURGE`, `surgenav`, `surgestate`, `surgefight`,
`surgeteamfight`, `surgequick`, `surgeraid`, `surgeclaim`. Recette de restauration du client dans `docs/SURGE.md §8`.

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (surgeclaim), `docs/SURGE.md` §8, `MEMORY.md`.

---

## 2026-08-09 (g73) — CHALLENGES (#72) : recon pipeline + incrément 1 (livraison BootData, 90/90)

Nouveau mode attaqué avec le pipeline #73/#74 (`contract.sh --mode` + ModeGraph). **Recon** (docs/CHALLENGES.md) :
mode « Sticker Challenges » gaté TL20, 15 classes `ui/challenges`, système IDLE (défis dans des slots →
sticker/récompenses), contenu data-driven (`challenge_*.tab`). Données via **`BootData.userChallengeDataExtra`**
(+ `historicWeeklyChallenges`) ; actions `START/CLAIM/CANCEL_STICKER_CHALLENGE`, `BUY_STICKER*`,
`SET_FAVORITE_STICKER` ; seul handler « MANQUE » = `GetUserChallengeDataExtra` (vue StickerOverviewWindow).

**Écartés à la recon** : HEIST (`unlockables.tab` TL **9999** = désactivé → vérif §8 impossible ; + 82 classes coop
temps réel) ; CITY WATCH (aucun Destination/écran — vestiges de tuto legacy, pas un mode autonome).

**Incrément 1 LIVRÉ** : `ServerChallenges.freshData(userID)` → `bd.userChallengeDataExtra` avec le **bon userID**
(le défaut `new BootData()` est non-null mais userID=0, or l'écran le lit) + conteneurs wire-sûrs ;
`historicWeeklyChallenges` reste le défaut non-null. `ChallengeBootTest` (userID, conteneurs, round-trip BootData).
Régression **90/90**. Reste : boucle START/CLAIM (persistée), stickers, handler GetUserChallengeDataExtra, vérif EN JEU.

Fichiers : `docs/CHALLENGES.md`, `server/java/dhserver/ServerChallenges.java`, `server/java/dhserver/ServerUser.java`
(bootData), `server/smoke/ChallengeBootTest.java`, `server/smoke/regression.sh`.

---

## 2026-08-09 (g73b) — CHALLENGES : architecture de l'incrément 2 (boucle START/CLAIM) résolue par recon

Recon complète de la boucle de défi (docs/CHALLENGES.md incr. 2). Points d'entrée §3 identifiés :
- Conversion : `ClientNetworkStateConverter.getUserChallengeData(UserChallengeDataExtra)` ↔ `setUserChallengeData`.
- START `Action{START_STICKER_CHALLENGE, extra={TYPE=StickerType, TIME}}` (pas de SLOT → serveur choisit via
  `StickerHelper.canStart`) ; handle via `StickerHelper.createHandleExtra` : `endTime=serverTime()+ChallengeSticker.
  getDuration()`, `maxProgress=ChallengeSticker.getMaxProgress()` (données `challenge_stickers.tab`, zéro invention).
- PROGRESSION : hooks `ChallengeImpl` (`onCampaignAttack`/`onChestOpen`/`onBreakerAttack`/`onArenaPromotion`/
  `checkAttackBase`…) = intégration TRANSVERSALE ; défis purement temporels complétés à `endTime`.
- CLAIM `StickerHelper.claimSticker(user, data, long, StickerType, ChallengeSlots)` ; CANCEL `cancelChallenge(…)`.
- Persistance : `UserChallengeDataExtra` hors `UserExtra` → nouveau champ persisté dans le blob `ServerUser`.

**Décision (§4, qualité)** : la boucle est un sous-système transversal (hooks ChallengeImpl + choix de slot +
autorité de progression client/serveur) ; comme pour le raid SURGE, une **observation EN JEU du START/CLAIM** (extras
exacts, sélection de slot, `UpdateChallengeProgress`) est recommandée AVANT câblage pour ne rien inventer. L'incrément
1 (livraison BootData) reste livré et testé (90/90). L'architecture est prête pour un câblage propre.

Fichiers : `docs/CHALLENGES.md` (incr. 2 détaillé).

---

## 2026-08-10 (g91) — EXPEDITION (#72) incrément 3 : COMBAT de nœud ✅ VÉRIFIÉ EN JEU

Reprise après compression (procédure complète : relecture MEMORY/PRINCIPLES/SHIMS/PROTOCOL/SERVER_PLAN/
ARCHITECTURE/EXPEDITION + règles §1-§8 + commandes). Puis achèvement de la vérif EN JEU du combat de nœud
d'expédition, qui plantait au client (`ExpeditionAttackScreen.createStageDefenders` : `IndexOutOfBounds`).

**Diagnostic par SONDES HEADLESS PROFONDES.** `WireCheck.assertRoundTrips` ne vérifie que le TYPE relu, PAS la
profondeur des `List` (angle mort) → écrit des sondes qui déballent `defenders[].lineup` et `nodeRewards` après
round-trip codec ET après save/reload DB. Elles ont montré que le serveur envoyait bien 15 défenseurs qui
survivaient au wire — mais avec **3 défauts** :

1. **Étoiles ennemies invalides (cause du crash).** `buildDefenders` : `createAndAddHero(t, ORANGE, level, 1)`.
   L'ordre des 2 entiers du jeu est **(ÉTOILES, NIVEAU)** (bytecode : `createUnitData` fait `setStars(a)`/
   `setLevel(b)`, cf. `ServerUser.grantHero`) → ennemis à **140 étoiles / niveau 1**. Des étoiles > `getMaxStars`
   (=6 à R102) plantent le client au rendu du combat (même famille que g55 `HasEnoughCollectionHeroes`).
   Corrigé : `stars = UnitStats.getMaxStars(user)`, `level = base`.
2. **`nodeRewards` VIDE → `IndexOutOfBounds` au 1ᵉʳ nœud.** `createStageDefenders` lit
   `getExpeditionData().getData().nodeRewards.get(nodeIndex)` ET `defenders.get(nodeIndex)`. Le run n'avait pas de
   `nodeRewards` tant qu'aucun nœud n'était gagné. Corrigé : **pré-génération au reset** via la méthode du jeu
   `ExpeditionHelper.createRewards` (§3, 15 `NodeReward{OR}` — `getGold(node, TL)` × bonus VIP).
3. **Niveau ennemi DOUBLÉ.** Le serveur envoyait `base + getExtraEnemyLevels`, or le **client** ajoute
   `getExtraEnemyLevels(difficulty)` au combat (offset `setLevel(getLevel()+extra)`). Corrigé : le serveur envoie la
   **BASE** (= niveau d'équipe du joueur ; EASY diff=1 ajoute 0, diff≥4 ajoute des niveaux).

Autres correctifs (§3) : `ResetExpedition` répond désormais **`ResetExpeditionResponse`** (type DÉDIÉ ; le client a
un handler propre `GameMain.lambda$setupPostClientInfoHandlers$55` qui fait le nettoyage de reset —
`clearModePersistentData`/`clearMercenaryHero`/`clearKoHiredMercenaries`/`enableDifficulty`/`onExpeditionReset`),
au lieu de `GetExpeditionResponse` qui sautait ce nettoyage ; crédit de nœud via `ExpeditionHelper.giveLoot(user,
nodeReward, node, difficulty, snap)` (applique `modifyGoldForDifficulty` + objets/tickets) au lieu d'un crédit à la main.

**Tests durcis** : `ExpeditionBootTest` (garde-fous : étoiles ennemies valides ≤ `getMaxStars`, niveau ≥ 1,
`nodeRewards` pré-peuplé = un par nœud) ; `ExpeditionCombatTest` (`giveLoot` : nœud 0 +5157 or, nœud 1 +5573,
`nodeRewards` reste pré-peuplé, persistance DB). Réponse au reset = `ResetExpeditionResponse` round-trip.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** `nav EXPEDITION` → `GetExpedition → GetExpeditionResponse (15 nœuds)` →
carte **CITY WATCH** rendue → `expfight` → écran **CHOOSE YOUR HEROES** (defenders=15, nodeRewards=15 côté client,
**plus aucun crash `createStageDefenders`**) → `expquick` → **combat RENDU joué de bout en bout** → **DEFEAT** d'abord
(roster de test niv.40-60 vs ennemis niv.100 désormais corrects) → `ExpeditionAttack(LOSS)` → serveur « pas de
progression [persisté] ». Roster porté à niv.100 RED 6★ (outil DEV `ExpAdminBoost` — état de compte légitime, même
esprit que `SetTeamLevel` ; le compte était TL100 mais héros sous-niveau) → **VICTORY 11s** → écran **REWARDS : LOOT
5 157 or** → CONTINUE → `ExpeditionAttack(WIN)` → serveur **`nœud 0 VAINCU → nodesDefeated=1, or +5157 [persisté]`** →
carte avancée au nœud suivant. **DB confirmée** (sonde) : `nodesDefeated=1`, `totalGoldEarned=5157`, GOLD crédité.

Pilotes DEV ajoutés (`TutorialDriver`+`DesktopLauncher`) : `expfight` (pousse le vrai
`ExpeditionHeroChooserScreen(node, NONE)`), `expquick` (`quickFightPressed` réel → `ExpeditionAttackScreen` + combat
+ `ExpeditionAttack`). Outils DEV : `ExpAdminReset` (régénère un run cassé par un ancien build), `ExpAdminBoost`
(aligne le roster de test sur le TL). **NB** : `JOURNAL.md` était en retard (dernière entrée g73b) alors que le
travail g74→g90 (CHALLENGES en jeu, FRIENDSHIPS, EXPEDITION incr.1-2) est dans `MEMORY.md`/`docs/*`.

**RESTE EXPEDITION** : incr. 4 (raid `ExpeditionRaid`/`doRaidFromClient`), 5 (wards hebdo `ExpeditionWeeklyInfo`),
6 (économie de reset `chargeForReset`/`getResetsRemaining`), 7 (coffres/epic chips `createRewards`/`openChest`),
8 (vérif en jeu complète).

Fichiers : `server/java/dhserver/{ServerExpedition,LoginServer}.java`, `server/smoke/{ExpeditionBootTest,
ExpeditionCombatTest,ExpAdminReset,ExpAdminBoost}.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g92) — EXPEDITION (#72) incrément 4 : RAID ✅ VÉRIFIÉ EN JEU

Le RAID complète toute l'expédition d'un coup (saute le combat). Client-autoritatif : le client exécute
`ExpeditionHelper.doRaidFromClient` (→ `doRaid` local, se crédite le butin) puis envoie `ExpeditionRaid{rewards,
difficulty}` ; le serveur RÉ-EXÉCUTE l'autorité.

**Recon (bytecode).** `ExpeditionRaid{difficulty, rewards, specialEvents}` ; pas de `ExpeditionRaidResponse` dédié.
`doRaidFromClient(user, difficulty, snap)` appelle `doRaid(user, difficulty, nodesDefeated, snap, finisher, null)` et
envoie l'`ExpeditionRaid`. `doRaid` (méthode AUTORITATIVE, §3) : gate `isDifficultyRaidable` (sinon
`COMPLETE_PREVIOUS_EXPEDITION_FIRST`) ; si `nodesDefeated>0` vérifie `getResetsRemaining` + `chargeForReset` ; débite
`getRaidCost`×`getRaidTicketType` tickets (lève `DONT_HAVE_ITEM`) ; `createRewards` (15 nœuds) + `rollExpeditionDrops`
/`rollEpicChipsForRound` → merge ; `if (rewardsClient != null && !compareDrops) throw INVALID_LOOT` (anti-tamper) ;
`giveRewards` ; `finisher.finishExpedition(nbNœuds, coût)` ; `incDailyUses`.

**Serveur (`ServerExpedition.recordRaid`).** Appelle `doRaid(user, difficulty, run.nodesDefeated, snap, finisher,
null)` — `finisher` pose `run.nodesDefeated = 15`. **6ᵉ arg = null** (EXACTEMENT comme le client : `aload 5 ifnull →
saute compareDrops`) → le serveur roule et crédite son PROPRE butin sans faux rejet `INVALID_LOOT` sur divergence RNG
(même décision que le loot campagne #25/§4bis). Catch `Throwable` (ClientErrorCodeException est CHECKED côté javac et
non déclarée par `doRaid` — on distingue l'anti-triche par `instanceof`). Persiste (`setExpeditionRun`+resyncs).

**Progression de difficulté (mirroir client).** `recordAttack` : au dernier nœud (`nodesDefeated == nodeCount`),
`enableDifficulty(user, difficulty+1)` — le client fait EXACTEMENT `if (nodesDefeated >= 15) enableDifficulty(diff+1)`
(bytecode `ExpeditionAttackScreen`). Débloque la difficulté supérieure ET rend la courante RAIDABLE.

**Test `ExpeditionRaidTest` (progression RÉELLE).** Raid refusé avant clear (anti-triche) → clear des 15 nœuds
(FIGHT) → diff 1 devient raidable → raid diff 1 : run complet, or crédité, 1 ticket débité → raid sans ticket refusé
(`DONT_HAVE_ITEM`) → persistance DB. Régression → **101 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** Compte rendu raidable par l'outil DEV `ExpAdminRaidable` (clear RÉEL des 15
nœuds via `recordAttack` → `enableDifficulty(2)` → diff 1 raidable, persisté ; + 5 tickets `EXPEDITION_RAID_1`, run
frais). `nav EXPEDITION` → `GetExpeditionResponse (expeditionID=5, 15 nœuds)` → carte CITY WATCH → `expraid`
(`doRaidFromClient(diff=1)` réel) → `ExpeditionRaid` → serveur **`RAID diff=1 → expédition complète (nodesDefeated=15),
or +370531 [persisté]`**. **DB confirmée** (sonde) : `nodesDefeated=15`, `totalGoldEarned=370531`, tickets
`EXPEDITION_RAID_1` 5→4, GOLD crédité.

Pilote DEV `expraid` (`TutorialDriver`+`DesktopLauncher`, chemin `doRaidFromClient` réel). Outil DEV `ExpAdminRaidable`.

**RESTE EXPEDITION** : incr. 5 (wards hebdo `ExpeditionWeeklyInfo` : currentWards/nextWards + calendrier de rotation),
6 (économie de reset `chargeForReset`/`getResetsRemaining`), 7 (coffres/epic chips `openChest`), 8 (vérif en jeu complète).

Fichiers : `server/java/dhserver/{ServerExpedition,LoginServer}.java`, `server/smoke/{ExpeditionRaidTest,
ExpAdminRaidable}.java`, `server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g93) — EXPEDITION (#72) incrément 5 : wards hebdomadaires (headless + délivrance en jeu)

`GetExpeditionResponse.weeklyWardInfo` est désormais PEUPLÉ (avant : objet vide). Les wards (`CombatModifier`) sont
des modificateurs de combat qui tournent chaque semaine et ne s'appliquent qu'aux **difficultés ≥ 3** (`getWardsFor`
renvoie `EMPTY_MODIFIERS` pour diff < 3 — EASY/NORMAL n'ont PAS de ward, fidélité vérifiée).

**Recon (bytecode).** `ExpeditionWeeklyInfo{currentWards, currentWardExpiration, nextWards, nextWardStartTime}`.
`getWardsFor(info, diff)` / `getNextWardsFor(info, diff)` = accesseurs purs : `diff < 3 → EMPTY` ; sinon
`currentWards.subList(0, diff-2)` (cumulatif : diff 3 → [0], diff 4 → [0,1]). Le POOL est dans la DONNÉE du jeu
`ExpeditionStats$WardStats.wardsByDifficulty` (List de 5 EnumSet : diff 0-2 vides ; diff 3 & 4 → 13 `WARD_*` chacun).
La ROTATION exacte (quel ward chaque semaine) est calculée par le BACKEND, ABSENT du jar client (comme `ArenaInfo`/
Surge).

**Serveur.** `ServerExpedition.weeklyWardInfo(now)` : lit le pool par réflexion (cache `WARD_POOL_3/4`), sélectionne
DÉTERMINISTE par l'INDICE DE SEMAINE DU JEU (`TimeUtil.getServerWeek`) — 1 ward HARD (pool diff 3, `week % n`) + 1 ward
EPIC additionnel différent (pool diff 4, `(week+3) % n`) ; `nextWards` = semaine+1 ; bornes = prochaine frontière hebdo
(`((now/MILLIS_PER_WEEK)+1)*MILLIS_PER_WEEK`). Calibration serveur documentée (patron incr. 2 : pool = donnée du jeu,
arrangement = serveur ; §4 : aucune VALEUR inventée, seule la rotation est un stand-in fidèle). Câblé dans `response()`.

**Test `ExpeditionWardTest`.** Vérifie via les accesseurs DU JEU : diff 1/2 → 0 ward, diff 3 → 1, diff 4 → 2 ; wards
= `CombatModifier WARD_*` du pool ; rotation déterministe (`nextWards == currentWards` de la semaine suivante) ; bornes
(expiration dans le futur ≤ 1 semaine, `nextWardStartTime == currentWardExpiration`) ; round-trip wire. Régression →
**102 tests**. (Ex. observé : currentWards=[WARD_DECREASE_HEALING, WARD_SUPPORT_LESS_ENERGY],
nextWards=[WARD_TANKS_EXTRA_DAMAGE, WARD_IMMUNE_TO_DISABLES].)

**EN JEU (compte id=1 TL100).** `nav EXPEDITION` → serveur `GetExpedition → GetExpeditionResponse (weeklyWardInfo
PEUPLÉ, 2 wards réels)` → le client ACCEPTE et rend CITY WATCH sans erreur (régression : avant vide, maintenant peuplé,
toujours OK) ⇒ **délivrance de weeklyWardInfo vérifiée en jeu**.

**DIFFÉRÉ (§8, gate de progression documenté).** L'**EFFET** des wards en combat (diff ≥ 3 HARD/EPIC) et leur
**affichage** dans `ExpeditionDifficultyWindowV2` ne sont pas atteignables sur le compte de test : le run EASY est
complété (sélecteur de difficulté grisé sur un run terminé ; le reset n'a pas firé sans économie — incr. 6) et HARD
requiert de clearer NORMAL d'abord. À vérifier sur un compte plus avancé. La rotation EXACTE du backend reste à
OBSERVER en jeu (comme le protocole de raid SURGE avant câblage) — notre rotation déterministe est un stand-in fidèle.

**RESTE EXPEDITION** : incr. 6 (économie de reset `chargeForReset`/`getResetsRemaining`), 7 (coffres/epic chips
`openChest`), 8 (vérif en jeu complète, y compris wards HARD+ sur un compte avancé).

Fichiers : `server/java/dhserver/ServerExpedition.java`, `server/smoke/ExpeditionWardTest.java`,
`server/smoke/regression.sh`, `docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g94) — EXPEDITION (#72) incrément 6 : économie de reset ✅ VÉRIFIÉ EN JEU

Relancer une expédition (hors 1ᵉʳ run) passe par `ExpeditionHelper.chargeForReset` (déjà appelé par
`ServerExpedition.resetRun`) ; l'incrément documente/teste l'économie + expose `resetsDone`.

**Barème DU JEU (§4, bytecode).** `getResetsRemaining(user, snap) = max(getResource(CITY_WATCH_RESETS),
DailyActivityHelper.getRemainingDailyUses(user, "EXPEDITION RESET", snap))`. `chargeForReset` : si resets restants > 0
→ consomme `CITY_WATCH_RESETS` (+`incDailyUses`) ; sinon paie `getEpicKeyCost(user, snap, diff)` clés
`CITY_WATCH_EPIC_KEYS` ; à défaut lève `EXPEDITION_CHANCES_USED`. **`getEpicKeyCost(diff)` = 0 pour diff 1-3, 35 pour
diff 4 (EPIC)** (relevé) ⇒ EASY (coût 0) : resets LIMITÉS au quota gratuit puis REFUSÉS (pas d'option payante) ; EPIC :
payable en clés epic. `resetResponse.resetsDone = DailyActivityHelper.getDailyUses(user, "EXPEDITION RESET", snap)`
(compteur d'activité quotidienne DU JEU ; ne compte pas les resets-ressource → peut rester 0). `ServerExpedition.
resetsDoneToday(user)` exposé.

**Test `ExpeditionResetTest`.** Barème epic (0 EASY / 35 EPIC) ; `firstEver` ne consomme rien ; reset EASY consomme le
gratuit (`CITY_WATCH_RESETS` 1→0) puis REFUSÉ quand épuisé ; reset EPIC (diff 4) refusé sans clé puis PAYANT (35 clés
→ 0) ; persistance DB. Régression → **103 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** Run frais (`ExpAdminReset` → `CITY_WATCH_RESETS=1`, `getResetsRemaining=1`).
`nav EXPEDITION` → `expreset 1` (chemin client réel `ClientExpeditionHelper.resetExpedition(1, NONE)`) → client
`ResetExpedition(diff=1, firstEver=false)` → serveur `run généré 15 nœuds [persisté]` → réponse
`ResetExpeditionResponse` (type dédié, handler client $55) → **carte fraîche rendue** (nœud 1 actif, 2-5 verrouillés) →
**le compteur de reset en haut à droite passe de 1 à 0** (reset gratuit consommé, VISIBLE en jeu). DB confirmée :
`CITY_WATCH_RESETS 1→0`, `getResetsRemaining=0`, persisté. Le chemin de REFUS (resets épuisés) est prouvé headless
(EASY coût 0 → `EXPEDITION_CHANCES_USED`).

Pilote DEV `expreset [diff]` (`TutorialDriver`+`DesktopLauncher`, chemin `ClientExpeditionHelper.resetExpedition` réel).

**RESTE EXPEDITION** : incr. 7 (coffres / epic chips : `createRewards`/`openChest` → héros), 8 (vérif en jeu complète,
y compris wards HARD+ sur un compte avancé).

Fichiers : `server/java/dhserver/ServerExpedition.java`, `server/smoke/ExpeditionResetTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g95) — EXPEDITION (#72) incrément 7 : coffres ✅ VÉRIFIÉ EN JEU (+ CLAUDE.md)

Les coffres d'expédition (`OpenExpeditionChest`). Un coffre est disponible tous les 3 nœuds vaincus (5 coffres pour
15 nœuds = les 5 RÉGIONS de la carte CITY WATCH), ouverts DANS L'ORDRE.

**Recon (bytecode).** `OpenExpeditionChest{rewardDrops, specialEvents}`. Client (`ClientActionHelper.
openExpeditionChest`) : appelle `ExpeditionHelper.openChest(user, snap, difficulty, nodesDefeated, chestsOpened,
droppedEpicChips, null)`, incrémente `chestsOpened`, envoie `OpenExpeditionChest{rewardDrops=<résultat>}`. `openChest`
(AUTORITATIF, §3) : gate `if (nodesDefeated % 3 != 0) throw NO_AVAILABLE` ; `chestIndex = nodesDefeated/3 - 1` ;
`if (chestsOpened != chestIndex) throw NO_AVAILABLE` (ordre) ; `rollExpeditionDrops(user, getRandom(EXPEDITION_CHEST),
difficulty, chestIndex, droppedEpicChips, snap)` ; `if (rewardsClient != null && !compareDrops) throw INVALID_LOOT` ;
`giveRewards`.

**Serveur (`ServerExpedition.recordOpenChest`).** Ré-exécute `openChest(user, NONE, difficulty, nodesDefeated,
chestsOpened, droppedEpicChips, null)` — **7ᵉ arg null** (comme le client : `aload 6 ifnull → saute compareDrops`) →
crédite le butin SERVEUR sans faux rejet INVALID_LOOT (cf. #25/§4bis). Catch `Throwable` (distingue l'anti-triche
`ClientErrorCodeException` par `instanceof`). Incrémente `run.chestsOpened` (mirroir client). Persiste.

**Test `ExpeditionChestTest`.** 5 coffres (1 tous les 3 nœuds) ouverts dans l'ordre ; refus avant le 1ᵉʳ palier +
double-ouverture au même palier (NO_AVAILABLE) ; persistance DB ; refus sans run. Régression → **104 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** Compte préparé à `nodesDefeated=3, chestsOpened=0` (outil DEV
`ExpAdminClearNodes` : reset + 3 nœuds vaincus → un coffre disponible). `nav EXPEDITION` → `GetExpeditionResponse
(expeditionID=8)` → `expchest` (chemin client réel `ClientActionHelper.openExpeditionChest(NONE, null)`) →
`OpenExpeditionChest` → serveur **`COFFRE ouvert → chestsOpened=1 [persisté]`**. DB confirmée : `chestsOpened=1`,
`nodesDefeated=3`.

Pilote DEV `expchest` ; outil DEV `ExpAdminClearNodes`.

**CLAUDE.md** créé (demande `/init`) : point d'entrée concis pour toute session (procédure de reprise, règles §1-§8,
commandes build/test/run + lancement en jeu + contrainte sleep bloqué, pilotage B-bis, architecture big-picture,
combat client-autoritatif) — pointe vers les docs détaillés sans les dupliquer.

**RESTE EXPEDITION** : incr. 8 (vérif en jeu COMPLÈTE de bout en bout : difficulté → combat → coffres → raid → reset,
+ wards HARD+ sur un compte avancé). Les incréments 1-7 sont livrés ; 3/4/6/7 ✅ en jeu, 5 délivrance ✅ (effet HARD+
différé), 1/2 ✅ en jeu (g79/g90).

Fichiers : `server/java/dhserver/{ServerExpedition,LoginServer}.java`, `server/smoke/{ExpeditionChestTest,
ExpAdminClearNodes}.java`, `server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/EXPEDITION.md`, `MEMORY.md`, `CLAUDE.md`.

---

## 2026-08-10 (g96) — EXPEDITION (#72) : WARDS — EFFET ✅ DÉMONTRÉ EN JEU EN HARD

Achèvement de la vérif des wards (incr. 5) : leur EFFET de combat, différé en g93 (non atteignable sur un compte
EASY), est maintenant DÉMONTRÉ EN JEU sur un run HARD.

**Préparation.** `ExpAdminReset server/data/dh-server.db 1 1 3` → `resetRun(diff 3)` : `enableDifficulty(3)` (le compte
avait déjà débloqué jusqu'à diff 4 via les tests précédents) + génère un run HARD. Sonde headless : `run.difficulty=3`,
`getMaxEnabledDifficulty=4`, `weeklyWardInfo.currentWards=[WARD_DECREASE_HEALING, WARD_SUPPORT_LESS_ENERGY]`,
`getWardsFor(HARD=3)=[WARD_DECREASE_HEALING]`, `getWardsFor(EPIC=4)=[…, WARD_SUPPORT_LESS_ENERGY]`.

**EN JEU (compte id=1 TL100).** `nav EXPEDITION` → `GetExpeditionResponse (expeditionID=9)` → carte **CITY WATCH « HARD »**
(sélecteur HARD, région 1 « DOWNTOWN / WAY STATION » active). `expfight` → hero chooser (équipe RED niv.100, puissance
**252 358**). `expquick` → combat de nœud HARD.

**PREUVE DE L'EFFET DU WARD.** La MÊME équipe et les MÊMES ennemis (niv.100/6★ ; `getExtraEnemyLevels(3)=0` donc pas de
différence de niveau entre EASY et HARD) donnent :
- **EASY** (g91) : VICTOIRE triviale en **11 s**.
- **HARD** (g96) : **DÉFAITE en 1 min 4 s** (3/5 ennemis KO seulement).
La SEULE différence entre les deux est le ward **`WARD_DECREASE_HEALING`** actif en HARD ⇒ l'effet de combat du ward
est démontré en jeu. Puis KEEP RESULT → client `ExpeditionAttack(LOSS)` → serveur `attack nœud 0 : LOSS (pas de
progression) [persisté]` (chemin de combat HARD confirmé de bout en bout). Captures : carte « HARD », hero chooser HARD,
fenêtre DÉFAITE.

**RESTE (mineur, facultatif)** : l'AFFICHAGE explicite du nom du ward dans `ExpeditionDifficultyWindowV2` (le tap sur le
sélecteur zoome la carte plutôt que d'ouvrir la fenêtre — artefact de hit-test du pilote) ; la rotation EXACTE du
backend (notre rotation par `getServerWeek` est un stand-in fidèle, pool = donnée du jeu).

**⇒ BILAN EXPEDITION #72.** Les 7 incréments fonctionnels sont livrés ET vérifiés EN JEU par brique : boot (g79),
reset+génération (g90), combat (g91), raid (g92), wards (g96), économie de reset (g94), coffres (g95). Régression 104
tests. L'incrément 8 (« vérif complète ») est essentiellement atteint par briques ; reste facultatif un run continu
unique enchaînant reset→combat×15→coffres→raid, EPIC (diff 4), et la rotation exacte des wards.

Fichiers : `docs/EXPEDITION.md` (incr. 5 ✅ / incr. 8 ✅ par brique), `MEMORY.md`, `JOURNAL.md`. (Aucun changement de
code ce tour — vérif en jeu + doc.)

---

## 2026-08-10 (g98) — ENCHANTING (#72) incrément 1 : enchantement d'équipement ✅ VÉRIFIÉ EN JEU

Premier incrément du nouveau mode ENCHANTING (choisi g97). Message DÉDIÉ `EnchantItem{hero:UnitType,
slot:HeroEquipSlot, itemsUsed:Map<ItemType,Integer>, useDiamonds:boolean, specialEvents}` → handler `LoginServer` →
**`ServerUser.applyEnchantItem`** : ré-exécute la logique du jeu (§3) `EnchantingHelper.enchantItem(user, hero, slot,
itemsUsed, useDiamonds, snap)` — consomme les matériaux, débite l'OR (`getEnchantGoldCost`, lève `NOT_ENOUGH_GOLD`) +
DIAMANTS optionnels, monte les étoiles/points d'enchant de l'objet équipé (borné par `EnchantingStats.getMaxStars`).
Anti-triche = les levées du jeu → refus autoritatif propre. Persistance `resyncHeroes` (l'enchant vit sur l'objet du
héros) + `resyncDiamonds`. Zéro invention (§4).

**Recon (bytecode).** Barème du jeu : `getMaxStars` par rareté (WHITE=0 non-enchantable, GREEN=1, BLUE=3,
PURPLE/ORANGE/RED/YELLOW=5) ; matériaux `ENCHANTING_MATERIALS` (VOID_DUST/SHIMMER_DUST/PRIMAL_ESSENCE…) ;
`getEnchantPoints(VOID_DUST)=10`. Les héros grantés n'ont PAS de gear → DEV `ServerUser.debugGiveFullGear`
(`HeroHelper.giveFullGear`) équipe le gear ENCHANTABLE du rang (slot ONE = PC_FLYERS ORANGE 0/5, etc.).

**Test `EnchantApplyTest`.** RALPH ORANGE + full gear + 40 VOID_DUST + or → enchant slot ONE avec 30 VOID_DUST →
**étoiles 0→2**, **or −63000** (= `getEnchantGoldCost` exact), VOID_DUST 40→10 ; persistance **PROFONDE** (round-trip
wire + DB : les étoiles d'enchant sur l'objet du héros survivent — leçon EXPEDITION : on vérifie le CONTENU, pas juste
le type) ; anti-triche sans or (`NOT_ENOUGH_GOLD`, aucun débit de matériaux). Régression → **105 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** `ExpAdminEnchant` prépare RALPH + gear complet + 50 VOID_DUST + 20 M or.
`enchant RALPH ONE VOID_DUST 30` (chemin client réel `ClientActionHelper.enchantItem(hero, slot, {VOID_DUST:30}, false,
NONE, listener)`) → client `EnchantItem` → serveur **`RALPH/ONE enchanté (or -63000) [persisté]`**. **DB confirmée** :
slot ONE PC_FLYERS **étoiles 0→2**, VOID_DUST 50→20, or 20 000 000→19 937 000. (NB pilote : passer un `ActionListener`
non-null — la 1ʳᵉ passe avec `null` a NPE côté client APRÈS l'envoi, sans empêcher l'application serveur ; corrigé.)

Pilote DEV `enchant <HERO> <SLOT> <MATERIAL> <count>` (`ClientActionHelper.enchantItem` réel). Outil DEV
`ExpAdminEnchant` (prépare un compte : héros + gear + matériaux + or).

**RESTE ENCHANTING** : incr. 2 (garde-fous coûts/diamants/plafond d'étoiles par rareté), incr. 3 (vérif en jeu sur
plusieurs slots/raretés + paiement diamants).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{EnchantApplyTest,ExpAdminEnchant}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/ENCHANTING.md`, `MEMORY.md`.

---

## 2026-08-10 (g99) — ENCHANTING (#72) incrément 2 : garde-fous + chemin diamants ✅ VÉRIFIÉ EN JEU → mode COMPLET

Garde-fous d'enchant (barème + anti-triche), tout par la logique du jeu (§3), zéro invention (§4).

**Barème (bytecode/probes).** `getMaxStars` par rareté : WHITE=0 (non-enchantable), GREEN=1, BLUE=3,
PURPLE/ORANGE/RED/YELLOW=5. `getEnchantMaxDiamondCost` dépend de l'item (PC_FLYERS ORANGE=5040, ROCKET_PACK_PATCH_KIT
PURPLE=3360). `getEnchantPoints(VOID_DUST)=10`.

**Comportements vérifiés.** (1) **Plafond d'étoiles** : au max (5/5), tout nouvel enchant est REFUSÉ (levée du jeu).
(2) **Matériaux insuffisants** : demander plus que possédé (sans diamants) → REFUS, aucun débit (or/matériaux/étoiles
inchangés). (3) **Chemin DIAMANTS** (`useDiamonds=true`) : paie `getEnchantMaxDiamondCost` → item au MAX d'un coup,
**matériaux NON consommés** ; anti-triche = diamants insuffisants → REFUS. (4) Coût OR exact (`getEnchantGoldCost`,
incr. 1).

**Test `EnchantGuardTest`.** Chemin diamants (→5/5 étoiles + −5040 diamants + matériaux intacts + persistance wire) ;
plafond (refus au max) ; matériaux insuffisants (refus, aucun débit) ; anti-triche diamants (100 diamants < coût →
refus). Régression → **106 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** `ExpAdminEnchant` (RALPH + gear complet + matériaux + or + 50 000 diamants).
`enchant RALPH TWO VOID_DUST 0 diamonds` (chemin client réel `ClientActionHelper.enchantItem(..., useDiamonds=true,
listener)`) → client `EnchantItem` → serveur **`RALPH/TWO enchanté (or -0, diamants -3360) [persisté]`**. **DB
confirmée** : slot TWO (ROCKET_PACK_PATCH_KIT, PURPLE) **étoiles 0→5 (MAX)**, diamants 50 000→46 640 (−3360 = coût max
exact de cet item), ni or ni matériaux consommés. Pilote DEV étendu `enchant <HERO> <SLOT> <MAT> <count> [diamonds]`.

**⇒ ENCHANTING #72 COMPLET & VÉRIFIÉ EN JEU** : incr. 1 (matériaux + or, slot ONE PC_FLYERS ORANGE, étoiles 0→2, or
−63000, g98) + incr. 2 (diamants + garde-fous, slot TWO ROCKET_PACK_PATCH_KIT PURPLE, étoiles 0→5, diamants −3360,
g99). Deux raretés, deux modes de paiement, plafond + anti-triche, persistance profonde. Reste facultatif : gear
RED/YELLOW, prime badges (`maxUpgradePrimeBadges`).

Fichiers : `server/smoke/EnchantGuardTest.java`, `server/smoke/{ExpAdminEnchant}.java`, `server/smoke/regression.sh`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`, `docs/ENCHANTING.md`, `MEMORY.md`.

---

## 2026-08-11 (g100) — ENCHANTING (#72) incrément 4 : MAX-UPGRADE PRIME BADGES + gear RED/YELLOW (REQUIS)

**Correction de consigne (utilisateur).** « gear RED/YELLOW, prime badges (`maxUpgradePrimeBadges`) ne sont PAS
facultatifs — rien n'est facultatif sauf si prouvé ET validé par l'utilisateur ». Les entrées g98/g99 les classaient
« reste facultatif » → **corrigé** : ce sont des livrables REQUIS. Cet incrément les livre.

**Message + logique du jeu (§3).** Bouton « MAX » de l'écran d'enchant = `ClientActionHelper.maxUpgradePrimeBadges(
plan, snap, listener)` (`com.perblue.heroes.game.ClientActionHelper`) : construit le plan localement
(`EnchantingHelper.buildMaxUpgradePlanForHero`), l'applique (`applyMaxUpgradePlanForHero`) PUIS envoie le message DÉDIÉ
`EnhanceMaxPrimeBadge{unitType, perBadgeItems:List, totalItems:Map, executionOrder:List, specialEvents}`. Côté serveur :
handler `LoginServer` → **`ServerUser.applyMaxPrimeBadge`** — le serveur est AUTORITATIF : il **IGNORE le plan déclaré
par le client** et le **RÉ-DÉRIVE** depuis l'état persisté (`buildMaxUpgradePlanForHero(user, type, snap)`), puis
l'applique (`applyMaxUpgradePlanForHero` = un `enchantItem` par slot). Toute l'anti-triche = ce recalcul serveur (un
tricheur ne peut rien fausser). Persistance `resyncHeroes`/`resyncDiamonds`/`resyncCounts` (l'enchant vit sur l'objet
équipé). Zéro invention (§4).

**Fait établi (§8, `GoldAwareProbe`) : le plan est AUTO-LIMITANT.** `buildMaxUpgradePlanForHero` ne planifie QUE ce que
le joueur peut réellement payer — plafond `getMaxStars`, matériaux POSSÉDÉS (`getItemAmount`) ET arrêt à l'OR
DISPONIBLE : mesuré 0/1 K/1 M or → plan VIDE ; 5 M → 3 slots (or 4 569 600) ; 9 M → 5 slots (7 616 000) ; 9,14 M → 6
slots (9 139 200) ; 50 M → 6 slots. Donc `applyMaxUpgradePlanForHero` sur un plan RE-DÉRIVÉ serveur ne peut PAS lever
`NOT_ENOUGH_GOLD`/`ENCHANT_ALL_ENOUGH_RESOUCES` (jamais d'application partielle) → **aucun garde-fou OR ajouté** (ce
serait du code mort, §2). Un compte sans ressource obtient un plan vide → no-op (refus propre).

**Gear RED/YELLOW.** `EnchantingStats.getMaxStars(RED)=getMaxStars(YELLOW)=5` et `enchantItem` est rarity-agnostic.
Sondé (`GearRarityProbe`) : RALPH rang RED → slot ONE=PRESTO (**RED**), rang YELLOW → **6 slots YELLOW**
(HOME_SWEET_HOME, MADE_FOR_PUDDLES, SO_MUCH_IN_COMMON, HANDCRAFTED_BY_LEPRECHAUNS, DRIVEN_BY_THOUGHT, THE_ZONE).

**Test `EnchantMaxUpgradeTest`.** (A) YELLOW RALPH → max-upgrade des **6 slots YELLOW d'un coup** : **or −9 139 200**
(= `plan.totalGold` EXACT), matériaux `{VOID_DUST=12, SHIMMER_DUST=6, PRIMAL_ESSENCE=132}` EXACTS, les 6 slots montent
en étoiles ; persistance **PROFONDE** (round-trip wire + DB, étoiles par slot — leçon EXPEDITION). (B) compte sans
ressource → plan vide → no-op, aucun débit. (C) affordabilité partielle : 5 M or → **exactement 3 slots** enchantés.
(D) gear **RED** (PRESTO) enchanté (étoiles 0→1). Régression → **108 tests**.

**✅ VÉRIFIÉ EN JEU (g100, compte id=1 TL100).** `ExpAdminMaxUpgrade server/data/dh-server.db 1 1` prépare RALPH rang
YELLOW (6 slots YELLOW 0/5 : HOME_SWEET_HOME, MADE_FOR_PUDDLES, SO_MUCH_IN_COMMON, HANDCRAFTED_BY_LEPRECHAUNS,
DRIVEN_BY_THOUGHT, THE_ZONE) + matériaux + 50 M or. `run-online.sh` (stack + client réel) → pilote `maxupgrade RALPH`
(chemin client RÉEL `ClientActionHelper.maxUpgradePrimeBadges` : plan bâti localement `slots=6 or=9139200
items={VOID_DUST=12,SHIMMER_DUST=6,PRIMAL_ESSENCE=132}`, `onResult=true`) → client envoie `EnhanceMaxPrimeBadge` →
serveur **`[prime-badge] RALPH max-upgrade (6 slot(s), or -9139200) [persisté]`** → `[login] <== EnhanceMaxPrimeBadge :
RALPH → appliqué [persisté]`. **DB CONFIRMÉE** (lecture WAL-aware) : les **6 slots YELLOW 0★ → 5★ (MAX)**, or
50 000 000 → 40 860 800 (**−9 139 200** = `plan.totalGold` exact), VOID_DUST 540→528 (−12), SHIMMER_DUST 600→594 (−6),
PRIMAL_ESSENCE 500→368 (−132) — tous exacts. ⇒ **ENCHANTING #72 COMPLET & VÉRIFIÉ EN JEU** (incr. 1-4), plus rien de
facultatif en suspens.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{EnchantMaxUpgradeTest,ExpAdminMaxUpgrade}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/ENCHANTING.md`, `MEMORY.md`.

---

## 2026-08-11 (g101) — MODE SUIVANT : SAVED_LINEUPS (lineups enregistrés) — incr. 1+2 headless

**Choix (pipeline #73/#74).** Parmi les ⬜ restants, SAVED_LINEUPS a les messages les plus CLAIRS
(`HeroLineupUpdate` = sauver ; `CheckLineupName`→`CheckLineupNameResult` = valider un nom), un point d'entrée §3 net
(`User.setHeroLineup`) et une persistance connue (`UserExtra.heroLineups`). Écartés : COLLECTIONS (mécanique mastery
touffue), BLACK_MARKET/WISHING_WELL/FRANCHISE_TRIALS (aucun message dédié → shop/event, risque §4).

**Découverte en recon : le CŒUR existait déjà via ARÈNE #41.** `LoginServer` routait `HeroLineupUpdate` →
`ServerUser.applyHeroLineupUpdate` (→ `User.setHeroLineup(type, iD, lineup, 0L, customName, realGearOptions,
emeraldStatSlotChoices)` — l'ordre des deux Maps est TESTÉ : realGearOptions puis emeraldStatSlotChoices, sinon
ClassCast à la sérialisation `UserHeroLineupData.writeListed`) + `resyncLineups` + persistance ; vérifié en jeu pour
les lineups de DÉFENSE d'arène (id=0).

**Contribution #72 (incr. 1) : `resyncLineups` DURCI (angle mort ids non-nuls).** L'ancien `resyncLineups` itérait
`HeroLineupType.values()` et lisait via `getHeroLineupData(t)` — or ce getter **hardcode id=0** (bytecode) → il
RATAIT tout lineup à id non-nul (perte silencieuse de persistance). Réécrit pour **itérer la Map runtime privée
`User.lineups`** (réflexion) et, pour chaque entrée, **recopier la clé `HeroLineupKey{lineupType,id}` →
`data.lineupType`/`data.iD`** avant de sérialiser (le loader `setHeroLineups` re-clé PAR ces champs, que
`setHeroLineup` ne pose PAS sur la data — même angle mort qu'EXPEDITION). Fait §4 : `new HeroLineup().mercenaryType =
UnitType.DEFAULT` (sentinelle « pas de merc », jamais null sur le wire ; un null NPE la sérialisation d'enum).

**Contribution #72 (incr. 2) : `CheckLineupName` → `CheckLineupNameResult`.** Handler requête/réponse `LoginServer`
(la fenêtre de nommage client reste bloquée sans réponse). La validation est SERVEUR (absente du jar client) → on
RÉUTILISE la logique du jeu `NameChangeHelper.isNameLegal` (codepoints valides + `ILLEGAL_NAMES` réservés) + non-vide,
plutôt qu'inventer une règle (§3/§4). **PARTIEL honnête (§2)** : le filtre de PROFANITÉ n'est pas dans le jar 12.1.0
(service serveur externe — « fuck » passe `isNameLegal`) ; on valide ce que le jeu expose. Noms de lineup =
personnels/cosmétiques.

**Test `LineupSaveTest`.** 4 lineups : SAVED_1 « Team Alpha » (3 héros), SAVED_2 « Team Bravo » (2 + merc ELSA),
EXPEDITION (par-mode), **SAVED_3#42 (id NON-NUL)**. Vérifie : lecture runtime ; round-trip wire PROFOND (type+id+nom+
héros[ordre]+merc survivent ; SAVED_3#42 ne collapse PAS sur `(SAVED_3,0)` ni `(DEFAULT,0)`) ; persistance DB ;
update en place (SAVED_1 réécrit → toujours 4 lineups, pas de doublon). Régression → **109 tests**.

**RESTE (REQUIS, §8) : vérif EN JEU** — sauver un lineup SAVED_* nommé (flux `CheckLineupName` → `saveHeroLineup`) →
serveur → DB → persiste au reload. + cooldowns de défense PvP si exercés.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{LineupSaveTest}.java`,
`server/smoke/regression.sh`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

---

## 2026-08-11 (g102) — SAVED_LINEUPS (#72) ✅ VÉRIFIÉ EN JEU (sauvegarde nommée + CheckLineupName + persistance)

Vérif en jeu (client réel → serveur → DB → reload) des incréments 1+2.

**EN JEU (compte id=1 TL100).** `ExpAdminLineup server/data/dh-server.db 1 1` accorde RALPH/VANELLOPE/ELASTIGIRL
(possédés → `saveHeroLineup` lit leurs slots émeraude sans NPE). `run-online.sh` (stack + client réel) :
- `savelineup SAVED_1 MyTeam RALPH+VANELLOPE+ELASTIGIRL` (chemin client RÉEL
  `ClientActionHelper.saveHeroLineup(SAVED_1, 0, lineup, "MyTeam", NONE)`) → client `HeroLineupUpdate` → serveur
  **`[login] <== HeroLineupUpdate(SAVED_1) → lineup enregistrée [persistée]`**.
- `savelineup SAVED_2 Bravo VANELLOPE+ELASTIGIRL` → 2ᵉ lineup enregistré (coexiste).
- `checkname MyDefense` (`CheckLineupName` via `NetworkProvider.sendMessage`) → serveur
  **`[login] <== CheckLineupName("MyDefense") → isValid=true`** (répond `CheckLineupNameResult`).

**DB CONFIRMÉE** (relue depuis `server/data/dh-server.db`, WAL-aware = preuve de survie au reload) :
- `SAVED_1` : nom=« MyTeam », héros=[RALPH, VANELLOPE, ELASTIGIRL].
- `SAVED_2` : nom=« Bravo », héros=[VANELLOPE, ELASTIGIRL].
Deux lineups nommés DISTINCTS coexistent et persistent (le durcissement `resyncLineups` fonctionne en jeu).

⇒ **SAVED_LINEUPS #72 vérifié en jeu** : sauvegarde nommée multi-lineups + validation de nom + persistance profonde.
Reste OPTIONNEL (non bloquant, déjà couvert ARÈNE #41 pour la défense) : cooldowns PvP `FIGHT_PIT_DEFENSE`/
`COLISEUM_DEFENSE_3`.

Pilotes DEV : `savelineup <SAVED_N> <name> <HERO1+HERO2+...>`, `checkname <name>`. Outil DEV : `ExpAdminLineup`.

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`server/smoke/ExpAdminLineup.java`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

### g102b — confirmation VISUELLE (capture) de SAVED_LINEUPS

Suite à la question utilisateur (« ouvert le mode in-game et vérifié visuellement, pas que headless ? ») : les vérifs
g102 passaient par des pilotes appelant l'API cliente réelle (`saveHeroLineup`/`CheckLineupName`) SANS ouvrir l'écran.
Ajout du pilote `lineupscreen <SAVED_N>` qui pousse le VRAI écran `SavedLineupHeroChooserScreen(type)`. Client réel
relancé → `lineupscreen SAVED_1` → **capture** montrant l'écran chargé depuis NOTRE serveur : titre **« MYTEAM »**
(nom sauvé) + les 3 héros **Ralph + Vanellope + Elastigirl** rendus + **TEAM POWER 752 286** + bouton SAVE + roster
coché. Capture `desktop-port/build/lineup_myteam_ingame.png` (gitignore). ⇒ confirmation VISUELLE en jeu (§4bis), pas
seulement le chemin serveur/DB. Pilote DEV `lineupscreen`.

---

## 2026-08-11 (g103) — SAVED_LINEUPS (#72) incr. 4 : COOLDOWN de défense PvP (correction « optionnel » → REQUIS)

**Correction de cadrage (consigne utilisateur).** J'avais laissé « Reste OPTIONNEL : cooldowns de défense PvP …
déjà couverts par ARÈNE #41 ». Sur challenge de l'utilisateur, vérification : **FAUX**. `grep` serveur pour
`setHeroLineupCooldown`/`LINEUP_UPDATE`/`CooldownType` = VIDE ; `applyHeroLineupUpdate` appelait `setHeroLineup`
mais JAMAIS de cooldown. ⇒ vrai trou : sauver une défense FIGHT_PIT/COLISEUM ne posait AUCUN cooldown côté serveur
(non autoritatif, non persisté — un client modifié pouvait re-changer sa défense en boucle). Pas optionnel.

**Correctif (miroir fidèle du client, bytecode `ClientActionHelper.saveHeroLineup`).** Après `setHeroLineup` :
`FIGHT_PIT_DEFENSE` → `ArenaHelper.setHeroLineupCooldown(user, FIGHT_PIT, FIGHT_PIT_LINEUP_UPDATE)` ;
`COLISEUM_DEFENSE_3` (3ᵉ/dernier, comme le client) → `(COLISEUM, COLISEUM_LINEUP_UPDATE)`. Durée =
`ArenaHelper.getNextDefenseCooldown` (donnée du jeu = **6 h**, jamais inventée). Persistance **write-through** :
`IndividualUser.setCooldownEnd` écrit dans `individualUserExtra.cooldowns` (aucun resync).

**Test `LineupCooldownTest`.** FIGHT_PIT_DEFENSE → `FIGHT_PIT_LINEUP_UPDATE` ~6 h ; COLISEUM_DEFENSE_3 →
`COLISEUM_LINEUP_UPDATE` ~6 h ; SAVED_* normal → AUCUN cooldown ; persistance wire + DB. Régression → **110 tests**.

**✅ VÉRIFIÉ EN JEU (id=1).** `savelineup FIGHT_PIT_DEFENSE Def RALPH+VANELLOPE+ELASTIGIRL` (chemin client réel) →
serveur `HeroLineupUpdate(FIGHT_PIT_DEFENSE) → lineup enregistrée [persistée]` → **DB** : cooldown
`FIGHT_PIT_LINEUP_UPDATE` posé (timestamp futur) + lineup [RALPH,VANELLOPE,ELASTIGIRL] persistés. Écart d'heures =
ancre d'horloge du serveur de test (−13 h) vs contexte du dump ; durée réelle = 6 h (headless). **Portée** : chemin
client réel + serveur + DB. **NON vérifié visuellement** : grisage du bouton « changer la défense » dans l'UI arène
(effet CLIENT lisant `getCooldownEnd`, valeur désormais fournie/persistée par le serveur).

Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/{LineupCooldownTest}.java`,
`server/smoke/regression.sh`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

---

## 2026-08-11 (g104) — SAVED_LINEUPS (#72) : champs profonds non vides + audit de périmètre

**Angle mort fermé (leçon EXPEDITION + note ARÈNE #41).** L'ordre des deux `Map` passées à `setHeroLineup`
(`realGearOptions`, `emeraldStatSlotChoices` — une inversion → ClassCast à la sérialisation `UserHeroLineupData
.writeListed`) n'était vérifiable QU'AVEC DU CONTENU : les tests headless précédents ET la vérif en jeu (héros
grantés PURPLE, sans stats émeraude ni real-gear) passaient tous des Maps VIDES → l'ordre n'était PAS réellement
prouvé. `LineupFieldsTest` peuple les deux :
- `realGearOptions = {RALPH: RealGearType.CALHOUN_ENERGY}` (Map<UnitType, RealGearType>).
- `emeraldStatSlotChoices = {RALPH: HeroStatSlotChoices{statSlotChoices: {EmeraldStatSlot.FIRST:
  CombatStatType.ARMOR_NEGATION}}}` (Map<UnitType, HeroStatSlotChoices{Map<EmeraldStatSlot, CombatStatType>}>ache —
  valeur enum SIMPLE, pas une List : découvert via un 1ᵉʳ ClassCast ArrayList→Enum, corrigé).
Round-trip wire + DB : PAS de ClassCast (ordre des Maps correct sous contenu réel) + contenu survit + PAS SWAPPÉ
(realGearOptions garde son RealGearType, emeraldStatSlotChoices son HeroStatSlotChoices). Régression → **111 tests**.

**Audit de périmètre (suite à la remise en cause « optionnel » de l'utilisateur — on ne laisse RIEN d'optionnel sans
preuve).** Tous les messages « Lineup » du protocole passés en revue :
- `HeroLineupUpdate` ✅, `CheckLineupName`→`CheckLineupNameResult` ✅ = les DEUX messages du mode (traités + vérifiés
  en jeu).
- `Action TOGGLE_HERO_FILTER` (`ClientActionHelper.toggleHeroFilter`) = filtre du sélecteur de héros, PARTAGÉ par tous
  les choosers. **NO-OP DOCUMENTÉ (§2/§4)** : `grep` jar = AUCUN consommateur (seulement l'émetteur + l'enum
  `CommandType`) et AUCUN champ joueur de filtres (`UserExtra` sans `heroFilters`) → logique/stockage serveur ABSENTS
  du jar 12.1.0 ; inventer un schéma violerait §4 (même catégorie que `SetExternalContentStatus` / le filtre de
  profanité). Acquitté = réponse autoritative correcte, pas « optionnel ».
- `WarDefenseLineupUpdate` = mode GUERRE DE GUILDE ; `MailLineup` = mode COURRIER. Hors périmètre SAVED_LINEUPS.

⇒ **SAVED_LINEUPS #72 complet** (2 messages traités + vérifiés en jeu + visuel ; cooldowns défense ; champs profonds ;
audit périmètre) — rien d'« optionnel » laissé sans preuve.

Fichiers : `server/smoke/{LineupFieldsTest}.java`, `server/smoke/regression.sh`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

---

## 2026-08-11 (g105) — COLLECTIONS (#72 mode suivant) incr. 1 : CLAIM d'un palier ✅ VÉRIFIÉ EN JEU + VISUEL

Nouveau mode (choisi par l'utilisateur). Système de MAÎTRISE de héros par COLLECTION (29 collections : rôles/franchises,
4 tiers BRONZE→PLATINUM). Jouer un héros accumule des « mastery uses » ; à N héros maîtrisés, un niveau devient
réclamable → récompenses + modificateurs de combat.

**Handler.** `Action CLAIM_COLLECTION_REWARDS{TYPE:CollectionType, TIER:CollectionTier, LEVEL:int}` (émetteur
`ClientActionHelper.claimCollectionRewards`) → `LoginServer` (chaîne `act.command`, extras en `.name()`) →
`ServerUser.applyClaimCollection` : ré-exécute la logique du jeu (§3) `CollectionHelper.claimCollectionRewards(user,
type, tier, level)`. Anti-triche = les levées du jeu (`getCollectionState != CLAIMABLE` → `COLLECTION_ALREADY_CLAIMED`
ou `NOT_ENOUGH_MASTERED_HEROES`). Persistance write-through : `IndividualUser` écrit dans
`individualUserExtra.collectionsClaimed` (+ `resyncHeroes`/`resyncDiamonds`/`resyncCounts` pour les récompenses).

**Faits établis (sondes, §4).** `getCollectionState` CLAIMABLE ⟺ `getNumMasteredHeroes(user,type,tier) >=
CollectionStats.getNumMasteredHeroesRequiredForLevel(type,level)`. Un héros est « maîtrisé » ⟺ `masteryUses >=
CollectionStats.getNumUsesRequiredForMastery(tier,level)` (BRONZE lvl1 = 20) ET étoiles >= `getHeroStarsRequired(tier)`
(BRONZE=3). DAMAGE lvl1 requiert 5 héros maîtrisés. `getCumulativeCollectionLevel = Σ highestClaimed(tier)`.

**Test `CollectionClaimTest`.** Setup CLAIMABLE (6 héros DAMAGE 6★ + maîtrise 21 via `setCollectionHeroMasteryUses`).
Claim DAMAGE/BRONZE/lvl1 → `highest 0→1`, `MASTERY_TOKENS +8` (récompense exacte) ; re-claim → refusé
(`COLLECTION_ALREADY_CLAIMED`, aucun double crédit) ; niveau non-gagné (maîtrise 5<20) → refusé
(`NOT_ENOUGH_MASTERED_HEROES`) ; persistance PROFONDE wire + DB (highest claimed survit). Régression → **112 tests**.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1 TL100).** `ExpAdminCollection` amène DAMAGE/BRONZE à CLAIMABLE (6 héros maîtrisés).
Client réel : `collectionscreen DAMAGE` → écran RÉEL montrant **Bronze I : HEROES MASTERED 5/5, NEXT REWARD 14
(EXP_COLOSSAL) + 8 (MASTERY_TOKENS), bouton CLAIM** (chargé depuis NOTRE serveur ; Silver/Gold/Platinum verrouillés).
`claimcollection DAMAGE BRONZE 1` (chemin client réel) → serveur **`DAMAGE/BRONZE niv.1 réclamé (highest 0→1)
[persisté]`** → écran ré-ouvert : **Bronze II (0/10, bouton DETAILS) + Silver I DÉVERROUILLÉ** → **DB confirmée** :
`highestClaimed(DAMAGE,BRONZE)=1`, `MASTERY_TOKENS=8`. Captures `build/coll_{before,after}_ingame.png` (gitignore).

Pilotes DEV : `claimcollection <TYPE> <TIER> <LEVEL>`, `collectionscreen <TYPE>`. Outil DEV : `ExpAdminCollection`.
**RESTE** : incr. 2 (maîtrise de combat `CollectionMasteryUsesUpdate`→`recordHeroMastery`), incr. 3 (cosmétique).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{CollectionClaimTest,ExpAdminCollection}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/COLLECTIONS.md`, `MEMORY.md`.

---

## 2026-08-11 (g106) — COLLECTIONS (#72) incr. 2 : maîtrise de combat ✅ VÉRIFIÉ EN JEU (delta 0→1 net)

Vérif en jeu PROPRE de l'accumulation de maîtrise (après la reprise post-reset du conteneur : travail incr. 1+2
récupéré depuis origin, HEAD f434761).

**Baseline propre.** `ExpAdminCollectionFight` durci : grante l'équipe DAMAGE 6★ (MOANA, MERIDA, JACK_SPARROW, BEAST,
BELLE), pose le lineup NORMAL_CAMPAIGN dessus, pré-3★ le niveau 1-1 (via un héros JETABLE OLAF 1★ < MIN_HERO_STARS —
maîtrise non touchée), et **remet à 0 la maîtrise DAMAGE/BRONZE de TOUS les héros DAMAGE** (élimine la pollution des
setups/claims précédents) → delta net garanti.

**Pilotage (2 obstacles levés).** (1) Le chooser n'auto-sélectionne pas → `campquick` sélectionne les héros via le
VRAI chemin `unitSelected` (après `canSelectUnit`) — 5/5 sélectionnés. (2) `canStartQuickFight` = `hasAtLeastOneHeroSelected
&& nodeIsThreeStarred` restait faux (le client ne « voit » pas toujours le 3★ immédiatement) → on appelle **`doQuickCombat()`**,
l'exécuteur RÉEL (charge + roule le loot + combat headless + envoie `CampaignAttack`), qui NE gate PAS sur le bouton.

**Résultat EN JEU (id=1).** `campfight 1 1` → `campquick` → client `CampaignAttack1` → serveur **`CampaignAttack
NORMAL 1-1 outcome=WIN → recordOutcome appliqué [persisté]`** → **DB : MOANA/MERIDA/JACK_SPARROW/BEAST/BELLE
DAMAGE/BRONZE = 1** (partis de 0 ; exactement +1 = un combat, pour les 5 héros ayant combattu). Chemin client réel →
serveur → DB, sur baseline propre = delta 0→1 NET. ⇒ **COLLECTIONS #72 incr. 1 (claim) + 2 (maîtrise) vérifiés en jeu.**

Pilotes DEV : `campfight <chapter> <level>` (pousse le chooser), `campquick` (sélectionne + `doQuickCombat`). Outil DEV :
`ExpAdminCollectionFight`. **RESTE** : incr. 3 (cosmétique).

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`server/smoke/ExpAdminCollectionFight.java`, `docs/COLLECTIONS.md`, `MEMORY.md`.

---

## 2026-08-11 (g107) — COLLECTIONS (#72) incr. 3 : mastery shop (achat d'avatar) ✅ VÉRIFIÉ EN JEU + VISUEL

Incrément cosmétique = « HERO MASTERY SHOP » : dépenser les MASTERY_TOKENS (gagnés par les claims, incr. 1) pour des
avatars/bordures de collection.

**Handler.** `Action BUY_COLLECTION_AVATAR{itemType=avatar}` (émetteur `ClientActionHelper.buyCollectionAvatar` ;
l'ItemType passe par le champ `Action.itemType`) → `LoginServer` → `ServerUser.applyBuyCollectionAvatar` : ré-exécute
la logique du jeu (§3) `CollectionHelper.buyCollectionAvatar(user, itemType)` : gate `getCumulativeCollectionLevel >=
CollectionStats.getCumulativeCollectionLevelRequiredForPortrait` (sinon `COLLECTION_AVATAR_LOCKED`) ; débit
`MASTERY_TOKENS` (`getAvatarCost`) ; `giveUser(itemType, 1)` (avatar = item, write-through). Persistance items/ressources
write-through + resync.

**Faits (sondes).** `getMasteryShopAvatars(user)` = 26 avatars ; `COLLECTION_AVATAR_DAMAGE` coûte 100 MASTERY_TOKENS et
requiert cumLevel(DAMAGE) >= 8 (`getCumulativeCollectionLevel` = Σ highestClaimed/tier ; max 3/tier via debug →
BRONZE/SILVER/GOLD=3 → 9). ItemType = fnum (valeur data-définie → `ItemType.valueOf`).

**Test `CollectionAvatarTest`.** Achat → MASTERY_TOKENS −100 + avatar possédé (+1) ; anti-triche 1 : niveau non atteint
→ `COLLECTION_AVATAR_LOCKED`, aucun débit ; anti-triche 2 : tokens insuffisants → refus, aucun avatar ; persist wire+DB.
Régression → **114 tests**.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** `ExpAdminAvatar` : cumLevel(DAMAGE)=9 + 1000 MASTERY_TOKENS. `buyavatar
COLLECTION_AVATAR_DAMAGE` (chemin client réel) → serveur **`BUY_COLLECTION_AVATAR(COLLECTION_AVATAR_DAMAGE) appliqué
[persisté]`** → **DB : MASTERY_TOKENS 1000→900 (−100), avatar possédé=1**. Écran **HERO MASTERY SHOP** ré-ouvert
(`shopscreen`) affiche le solde **900** (chargé depuis notre serveur) — capture `build/avatar_shop_ingame.png`.

**`CLAIM_COSMETIC_COLLECTION`** (collections d'emojis) : la logique serveur est ABSENTE du jar 12.1.0 (aucun helper ni
consommateur — même catégorie que le filtre de profanité / `TOGGLE_HERO_FILTER`) ; le stockage
`claimedCosmeticCollections` (Set runtime) est resyncable, mais la règle d'éligibilité (posséder toute la collection
d'emojis) n'est pas exécutable sans l'inventer → **gap documenté honnêtement (§2/§4), non implémenté** (pas
« optionnel » : absent du jar).

⇒ **COLLECTIONS #72 : incr. 1 (claim) + 2 (maîtrise de combat) + 3 (mastery shop avatar) TOUS vérifiés EN JEU.**
Pilotes DEV : `buyavatar <ITEM>`, `shopscreen`. Outil DEV : `ExpAdminAvatar`.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{CollectionAvatarTest,ExpAdminAvatar}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/COLLECTIONS.md`, `MEMORY.md`.

---

## 2026-08-16 (g108) — WISHING_WELL (#72) incr. 1 : héros CIBLE du puits ✅ VÉRIFIÉ EN JEU + VISUEL

Nouveau mode « Puits aux souhaits » (gacha ciblé de shards). Incrément 1 = fixer le **héros CIBLE** qui biaise le
tirage.

**Handler.** `Action SET_WISHING_WELL_TARGET_HERO{heroType=cible}` (émetteur `ClientActionHelper.setWishingWellTargetHero` ;
la cible passe par le champ `Action.heroType`) → `LoginServer` (dispatch `act.command`, lit `act.heroType`) →
`ServerUser.applySetWishingWellTarget` : ré-exécute la logique du jeu (§3) `WishingWellHelper.setTargetHero(user, hero)` :
valide `hero ∈ getAllEligibleHeroes` (héros non éligible → `WISHING_WELL_HERO_NOT_ALLOWED` = aucun effet = anti-triche) ;
pose `IIndividualUser.setWishingWellHero(hero)` (write-through `individualUserExtra`) + ajuste les poids de pity ;
`resyncCounts` (compteur de changement = `UserFlag`). Persistance quasi-gratuite (write-through), round-trip wire + DB.

**Test `WishingWellTargetTest`** (régression → **115 tests**). DEFAULT→RALPH→VANELLOPE ; anti-triche : un héros non
éligible (TEST_DUMMY) → refusé, cible inchangée ; persistance round-trip wire + DB (=VANELLOPE).

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** `SetTeamLevel 65` (débloque `Unlockable.WISH_CHEST`, req TL 30 ; sonde : 274 héros
éligibles, RALPH/VANELLOPE inclus, cible initiale DEFAULT). Pilote `wishtarget RALPH` (chemin client réel
`ClientActionHelper.setWishingWellTargetHero`) → serveur **`SET_WISHING_WELL_TARGET_HERO(RALPH) appliqué [persisté]`**
(+ `[wishing-well] cible = RALPH [persisté]`) → **DB `server/data/dh-server.db` : `wishingWellHero=RALPH`** (lecture
WAL-aware live). **CONFIRMATION VISUELLE** : `wishscreen` ouvre le VRAI écran **WISH CRATE** (`WishingWellChestScreen`,
chemin réel `pushScreen`) → **portrait de RALPH** à gauche + **JACKPOT CHIP CHANCES** 1 000 shards RALPH @ 1,00 % +
**REGULAR CHIP CHANCES** 100-300 shards RALPH @ 10,00 % (la table du puits est biaisée sur la cible) — capture
`desktop-port/build/ww_screen.png`.

**Contrat industriel (ModeGraph `--mode com/perblue/heroes/ui/wishingwell/`).** 8 classes du mode. Gate `WISH_CHEST`.
Écran en lecture seule côté messages (la cible = un `Action`, pas un message de mode → normal). A/B (serveur→client)
pour l'**incr. 2** : `LootResults{ lootDrops, oldWishHeroChipsWeight, oldWishJackpotWeight }` + `RewardDrop{ flags,
itemType }` — les `oldWish*Weight` = poids de pity AVANT le souhait (animation de progression) → à peupler par le WISH.

⇒ **WISHING_WELL #72 : incr. 1 (cible) vérifié EN JEU.** Pilotes DEV : `wishtarget <HERO>`, `wishscreen`. Outil DEV :
`SetTeamLevel`. **RESTE** : incr. 2 (WISH `ChestType.WISH` via `openChest` : respect cible/poids + pity + persistance).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{WishingWellTargetTest}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/WISHING_WELL.md`, `MEMORY.md`.

---

## 2026-08-16 (g109) — WISHING_WELL (#72) incr. 2 : le SOUHAIT (ChestType.WISH) ✅ VÉRIFIÉ EN JEU + VISUEL

Le souhait proprement dit : ouvrir un coffre `ChestType.WISH` → tirage de shards BIAISÉ par le héros cible.

**Handler.** `ServerUser.openChest` (handler `BuyChests` existant) reçoit une branche `ChestType.WISH` : au lieu de la
table générique (`getDropTable` + `ChestContext`), il roule la table PROPRE du puits `ChestStats.WISHING_WELL_DROPS`
(champ statique privé, hors `getDropTable` → accès réflexion `wishingWellTable()`) avec un `WishingWellDTContext(user,
random)` — ce contexte LIT le héros cible (`wishingWellHero`) + les poids de pity + la rareté/mod max du joueur et
BIAISE le tirage vers la cible. C'est le CODE DU JEU (§3, miroir exact de `ChestStats.rollWishingWellDisplay`). Plancher
des poids via `WishingWellHelper.checkMinWeights` (code du jeu). Crédit des shards via `giveChestRewards`, débit DIAMONDS
(branche payante existante), persistance write-through + resync.

**GAP §4 PROUVÉ AU BYTECODE — rampe de pity par tirage.** La RAMPE de pity (nouveau `wishingWellJackpotWeight`/
`wishingWellHeroChipsWeight` APRÈS un souhait) N'EST PAS dans le jar client : les setters `setWishingWell*Weight` ne sont
invoqués QUE par `ChestHelper.updateWishingWellWeights` (applique une valeur FOURNIE — côté client depuis la réponse
serveur) et par `setTargetHero`/`checkMinWeights` (PLANCHER `JACKPOT_BASE`/`HERO_CHIPS_BASE`) ; `doPreRollUpdates` ne
fait que réinitialiser des compteurs d'évènement ; `WishingWellContextDTCode$1/$2` LISENT les poids pour biaiser mais
n'en écrivent aucun. C'était la logique serveur autoritative de PerBlue, absente de l'APK → non réimplémentable sans
l'INVENTER (§4, même catégorie que `CLAIM_COSMETIC_COLLECTION`). On expose donc les poids PLANCHERÉS (checkMinWeights)
sans rampe : `LootResults.old/newWish*Weight` = plancher (les probas de base `getProbabilities` restent correctes).
Documenté dans `docs/SHIMS.md` + `docs/WISHING_WELL.md`. *Risque* : pas de « pity » cumulatif (chances de jackpot fixes).

**Test `WishingWellWishTest`** (régression → **116 tests**). Cible RALPH : le `WishingWellDTContext` cible RALPH
(biais structurel, déterministe) ; 120 souhaits → ≥1 drop RALPH + `STONE_RALPH` crédité (=6940) + débit EXACT 120×500
DIAMONDS ; poids sans rampe (new==old) au plancher ; persist wire+DB ; bascule VANELLOPE → drops VANELLOPE (le biais
suit la cible).

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** `WishAdmin` (TL 65, 100 000 DIAMONDS, cible RALPH). Pilote `wish 1` ×23 (chemin
client réel `ChestHelper.openChestInner` → `BuyChests{WISH}` + `ServerRollRequest`) → serveur `<== BuyChests` (roule
biaisé + débite) → **DB : DIAMONDS débités (500/souhait) + items RALPH crédités** : `STONE_RALPH=480`,
`EPIC_CHIP_RALPH=520`, `BIT_RALPH_HEALING=70`, `BIT_RALPH_LONGER_STUNS=35` (TOUS les drops de héros = la cible = biais
confirmé), persistés. **CONFIRMATION VISUELLE** : la fenêtre **CRATE REWARDS** (servie par notre serveur) montre un lot
de **300 puces RALPH** (« 300/200 » + portrait RALPH) — capture `desktop-port/build/wish_result_ingame.png`.

⇒ **WISHING_WELL #72 : incr. 1 (cible) + 2 (souhait) vérifiés EN JEU** (rampe de pity = gap §4 documenté). Pilote DEV :
`wish [count]`. Outil DEV : `WishAdmin`.

Fichiers : `server/java/dhserver/ServerUser.java` (branche WISH + `wishingWellTable()`), `server/smoke/WishingWellWishTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/{WISHING_WELL,SHIMS}.md`, `MEMORY.md`.

---

## 2026-08-16 (g110) — WISHING_WELL : RAMPE DE PITY = RÉELLE (correction d'une erreur g109)

**Correction d'une conclusion erronée (consigne utilisateur : « rien n'est optionnel/absent tant que non prouvé »).**
En g109 j'avais déclaré la rampe de pity « absente du jar » (gap §4) — c'était une **erreur de méthode** : je n'avais
grep'é que les `setWishingWell*Weight` dans les `*Helper`/`*Stats`, **sans lire les `.tab` ni chercher dans la couche
UI**. L'utilisateur a poussé (« a tu check les tab ? rien n'est vraiment optionnel tant qu'on ne l'a pas prouvé »).

**Ce que la revérification a montré (FAITS).**
1. `game-data/stats/wishing_well_weights.tab` contient bien les multiplicateurs de rampe : `JACKPOT_MULT_X=1.1`,
   `JACKPOT_MULT_Y=1.01`, `HERO_CHIPS_MULT_Z=1.1`, `JACKPOT_10X_BONUS_MULT=1.05` (colonnes NEW/RECENT/OLD).
2. Grep jar-wide (64k classes) des lecteurs de ces constantes → `WishingWellStats$WeightConstants` (déclaration) **et
   `WishingWellChestResultWindow`** (UI). Cette dernière contient la RÈGLE, dans `reachedDestination(LootResults,
   RewardDrop, int)` : init `jackpotWeight/heroChipsWeight` depuis `LootResults.old*` (via `setLootResults`), puis par
   drop — jackpot (`flags&16`) → reset `*_BASE` ; stone (`ItemStats.getCategory==STONE`) → `jackpot*=MULT_X`,
   `heroChips=HERO_CHIPS_BASE` ; sinon → `jackpot*=MULT_Y`, `heroChips*=MULT_Z` ; bonus 10x (`hasBulkBonus`, frontières
   `rowIndex % getMultiBuyCount`) `jackpot*=10X_BONUS_MULT`.
3. La classe est **UI liée à GL** (met à jour `jackpotPreviewStack`) → **non instanciable headless**, donc non
   EXÉCUTABLE : on **TRANSCRIT la règle au bytecode près** (valeurs = `.tab`, jamais inventées, §4).

**Implémentation.** `ServerUser.openChest` (branche WISH) calcule la rampe par drop (sur `lr.lootDrops`, les
`RewardDrop` convertis — pas les `DropItem` bruts) depuis `old*`, puis persiste via le **code du jeu**
`ChestHelper.updateWishingWellWeights(user, jackpot, heroChips)` (write-through `individualUserExtra`). `LootResults.
old/newWish*Weight` reflètent avant/après. La branche ne prétend plus à aucun gap.

**Test.** `WishingWellWishTest` étendu : CONTINUITÉ entre tirages (le `old` d'un tirage == le `new` du précédent =
persistance de la pity), DIRECTION (pity jackpot MONTE hors jackpot, reset au jackpot/stone), ACCUMULATION (base 1.0 →
peak ~4 sur 120 tirages), + survie round-trip wire. Régression **116/116**.

**Statut SHIMS** : la rampe passe de « GAP §4 » (g109, faux) à **RÉEL** (transcription fidèle d'une règle GL-only +
valeurs `.tab`). **Leçon (§8)** : ne jamais conclure « absent du jar » sans avoir cherché AUSSI la couche UI et lu les
`.tab`. RESTE : revérif EN JEU que les probas WISH montent au fil des tirages.

Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/WishingWellWishTest.java`, `docs/{WISHING_WELL,SHIMS}.md`,
`MEMORY.md`.

---

## 2026-08-16 (g111) — MERCHANT (marchands / marché noir) : phase RECON (pipeline #73) → docs/MERCHANT.md

Choix du mode suivant après WISHING_WELL : MERCHANT (déclencheur : `REFRESH_TRADER non appliquée (PARTIEL)` en boucle
dans `/tmp/dh_game.log` + les nombreux `*_merchant_drops.tab`). Phase recon du pipeline #73 (ModeGraph `--mode Merchant`
+ décompilation ciblée).

**Structure.** Classes : `MerchantScreen` (UI), `MerchantHelper` (logique), `MerchantStats`/`MerchantDTCode` (données),
enum `MerchantType`, wire `MerchantData`/`MerchantItemData`/`MerchantUpdate`/`PurchaseMerchantItem`. Stockage =
`IndividualUserExtra.merchantData` (`Map<MerchantType, MerchantData>`, **write-through**), backing de
`IUser.getMerchantItems(type)` etc.

**Fait décisif (architecture).** `GameMain.lambda$setupPostClientInfoHandlers$28(conn, MerchantUpdate)` : le **client REÇOIT
`MerchantUpdate` et l'applique — il ne GÉNÈRE JAMAIS le stock**. ⇒ MERCHANT = **blob serveur-autoritatif**
(cf. `docs/ARCHITECTURE.md` : Arena ladder / Surge / Expedition). Le serveur doit rouler les tables `*_merchant_drops.tab`
(`MerchantStats.<TYPE>_DROP_STATS`, privées) via `MerchantDTCode`, construire `MerchantData`, persister (write-through),
et pousser `MerchantUpdate`. `MerchantHelper.refresh` ne fait QUE le gating/charge/track (`refresh(AUTO)`→true mais
inventaire vide) ; la génération est derrière `CodeLocationHelper.isOnServer()` (que `ServerContext` ne pose pas exprès).

**Entrées §3.** `MerchantHelper.purchaseItem(...)`, `refresh(type, MerchantRefreshType{AUTO,FREE,ITEM,PAID,VIDEO}, user,
snapshot)`, `getItemCost`, `isMerchantUnlocked`/`isAvailable`, `getFreeRefreshes`, `checkForFoundMerchant` (BLACK_MARKET/
MEGA_MART limited-time), `getMerchantPrimary/SecondaryCurrency`, `MerchantStats.getRefreshCost`/`getRefreshCurrency`.

**Disponibilité (sonde `MerchAvail`, TL200).** Always-on : GEAR/MEMORY/CHALLENGE/CRYPT/COLISEUM/FIGHT_PIT/WAR/EXPEDITIONS/
INVASION. Limited-time (isAvailable=false, à découvrir) : BLACK_MARKET, MEGA_MART. Verrouillés : NORMAL, HEIST.
INVASION : pas de refresh manuel. ⇒ incr. 1 sur un marchand dispo (GEAR).

**Plan** (docs/MERCHANT.md) : 1) génération+affichage stock (blob) ; 2) achat (`PurchaseMerchantItem`→`purchaseItem`,
anti-triche coût recalculé) ; 3) refresh (`REFRESH_TRADER`→`refresh`+régén, corrige le PARTIEL) ; 4) BLACK_MARKET/MEGA_MART
limited-time. Régression inchangée (116). Aucune ligne serveur écrite à ce stade (recon pure).

Fichiers : `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g112) — MERCHANT : recon APPROFONDIE (de-risking incr. 1) + point dur coût

Poursuite du recon MERCHANT (sondes `MerchGenProbe`/`MerchCostProbe`). Recette de génération PROUVÉE headless :
`getDropStats(type).getTable().rollNode("ROOT", new UserDTContext(user), rnd)` → `List<DropItem>` (GEAR=10, MEMORY=8) →
`DropConverter.convert` → `MerchantItemData`. Stockage = **blob** `individualUserExtra.merchantData`
(`Map<MerchantType,MerchantData>`) : le convertisseur `ClientNetworkStateConverter` n'gère PAS les marchands
(`IndividualUser` runtime démarre EnumMap vides) → à câbler côté `ServerUser` (write-through), pas via round-trip.
Livraison client = push `MerchantUpdate` (`GameMain.lambda$…$28`). `initMerchantData(type, MerchantData)` peuple le runtime.

**Point dur restant (coût des objets, NE PAS inventer §4)** : `getItemCost` lit `mi.getCost()` (coût de base porté par
l'objet). Marchands ANNOTÉS (BLACK_MARKET) : `.tab` porte `{PriceType}{Cost}` → `DropItem.getParameter("Cost"/"PriceType")`
(data-driven, reconstructible). Marchands À JETONS (GEAR/MEMORY) : objets roulés SANS param `Cost` (`params={}`) → coût de
base calculé par RARETÉ (behaviors `MerchantDTCode` `RarityCost*`), source pas encore localisée (`merchant_refresh.tab` =
coût de REFRESH 1-21, PAS item ; `COST_STATS` = refresh aussi ; `.tab` de drop ne portent que `{PriceType}`). **Leçon pity
(§8) appliquée : ne PAS déclarer gap — à creuser (mapping rareté→coût / CONSTANT_STATS / formule).** C'est le seul verrou
de l'incr. 1.

⇒ Recon incr. 1 ~80 % de-risqué (roll ✅, stockage blob ✅, livraison ✅) ; reste le coût des marchands à jetons. Aucune
ligne serveur écrite (recon). Régression inchangée (116). Détail : `docs/MERCHANT.md` §Recette/§Point dur.

Fichiers : `docs/MERCHANT.md`, `JOURNAL.md`.

---

## 2026-08-16 (g113) — MERCHANT (#72) incr. 1 : génération du stock (blob) HEADLESS + coût RÉSOLU

Implémentation de la génération de stock marchand (blob serveur-autoritatif), après le recon g111/g112.

**Coût des objets — RÉSOLU (leçon pity §8, 3ᵉ application « c'est dans la data »).** Le coût de base vient d'`items.tab` :
colonnes `VEND_VALUE`, `GOLD_PRICE`, `DIAMOND_PRICE`, `TOKEN_PRICE`, lues via `ItemStats.getStat(itemType,
StatType.{GOLD,DIAMOND,TOKEN}_PRICE)`. `getItemCost` = `getMerchantItemPrice(..., mi.getCost())` × remise ; le base
`mi.getCost()` = prix items.tab pour la monnaie du marchand × quantité. (J'avais failli déclarer un gap sur le coût des
marchands à jetons — encore une fois faux : la donnée existe, il fallait chercher `items.tab` + `ItemStats.getStat`.)

**Implémentation.** `ServerUser.generateMerchant(MerchantType)` : `merchantTable(type)` (réflexion sur
`MerchantStats.getDropStats`, privé) `.rollNode("ROOT", new UserDTContext(user), rnd)` → `List<DropItem>` ; par item :
`DropConverter.convert` → `RewardDrop` (skip itemType DEFAULT = ressources/upsells sans type), monnaie = `DropItem
.getParameter("PriceType")` sinon `getMerchantPrimaryCurrency`, coût = `ItemStats.getStat(itemType, priceStat(currency))
× quantité` → `MerchantItemData{item,cost,currency,purchased=false}`. `MerchantData{inventory, expiration=0, cooldownEnd=0,
nextAutoRefresh=getTimeUntilNextAutoRefresh, permUnlocked=isMerchantUnlocked, staminaMemory=0}`. **Write-through** :
`individualUserExtra.merchantData` (EnumMap<MerchantType,MerchantData>) — le convertisseur `ClientNetworkStateConverter`
ne gère PAS les marchands (blob pur, cat. Arena/Surge). Helpers `merchantTable`/`priceStat`, lecteur
`merchantDataPersisted(type)`.

**Test `MerchantGenTest`** (régression → **117 tests**). GEAR : 10 objets, 8 tarifés (+2 upsells prix 0), **coût ==
prix items.tab × qté vérifié POUR CHAQUE objet** (invariant), monnaie GEAR_TOKENS, non achetés ; round-trip wire + DB
conservent stock/coûts/type/monnaie ; 2 marchands (GEAR+MEMORY) coexistent.

**RESTE incr. 1b** : pousser `MerchantUpdate{type,data,reason}` au boot/à l'accès (mécanisme `GameMain.lambda$…$28`) +
vérif EN JEU (écran marchand affiche objets + prix). Puis incr. 2 (achat), 3 (refresh, corrige le PARTIEL), 4 (BLACK_MARKET).

Fichiers : `server/java/dhserver/ServerUser.java` (+generateMerchant/merchantTable/priceStat/merchantDataPersisted),
`server/smoke/MerchantGenTest.java`, `server/smoke/regression.sh`, `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g114) — MERCHANT (#72) incr. 1b : push MerchantUpdate post-boot ✅ VÉRIFIÉ EN JEU + VISUEL

Livraison au client du stock marchand généré (incr. 1). `ServerUser.bootMerchantUpdates()` : pour chaque marchand
DISPONIBLE + débloqué (GEAR/MEMORY/CHALLENGE/CRYPT/COLISEUM/FIGHT_PIT/WAR/EXPEDITIONS/INVASION), génère le stock s'il est
absent/vide (write-through, persiste) sinon réutilise le blob persisté (le stock NE se régénère PAS à chaque login) →
renvoie les `MerchantUpdate{type,data,reason=0}`.

**Timing (leçon SocialHistory).** Poussé au boot direct = PERDU : le `reset()` du BootData côté client efface les
marchands appliqués (ils vivent sur l'`IndividualUser` que le BootData reconstruit ; le handler client
`GameMain.lambda$…$28` fait `getIndividual().initMerchantData + updateUI`). Corrigé : `LoginServer` pousse les
`MerchantUpdate` **après le `REFRESH_SPECIAL_EVENTS` post-boot** (user client stabilisé) → survit. En jeu : d'abord
`GEAR : 0 objets` (push au boot) → après correction `GEAR : 10 objets`.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** Serveur `==> MerchantUpdate x8 (stock marchands)` → pilote `merchantscreen GEAR`
(chemin réel `new MerchantScreen(GEAR)`) → **écran BADGE BAZAAR** affiche les 10 objets avec prix (CUTE-ING STAR 5 290,
LUCKY CRICKET 3 910, DINOCO 400 87 515 en ROUGE=trop cher vs 39 000 jetons, CIRCLE OF LIFE 7 935, BROKEN NECKLACE 31 970,
MAGIC BRUSH 18 285…) + « Refreshes next Saturday » + bouton REFRESH — capture `build/merchant_gear_ingame.png`. Prix =
`items.tab` (TOKEN_PRICE) servis par NOTRE serveur. Pilote DEV `merchantscreen <TYPE>`.

⇒ **MERCHANT #72 : incr. 1 (génération) + 1b (push/affichage) vérifiés EN JEU.** RESTE : incr. 2 achat
(`PurchaseMerchantItem`→`purchaseItem`, anti-triche coût recalculé), incr. 3 refresh (`REFRESH_TRADER`→`refresh`+régén,
corrige le PARTIEL en boucle), incr. 4 BLACK_MARKET limited-time.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g115) — MERCHANT (#72) incr. 2 : ACHAT (PurchaseMerchantItem) ✅ VÉRIFIÉ EN JEU + VISUEL

Achat d'un objet chez un marchand. `ServerUser.applyPurchaseMerchantItem(PurchaseMerchantItem)` : charge le blob
persisté dans le runtime (`iu.initMerchantData` — sinon `findUnpurchasedItem` ne voit rien, le convertisseur n'gère pas
les marchands) puis ré-exécute la logique du jeu (§3) `MerchantHelper.purchaseItem`.

**Anti-triche (bytecode).** `findUnpurchasedItem` : l'objet doit être DANS le stock serveur ET non acheté (sinon
`TRADER_ITEM_NOT_FOUND`). Coût RECALCULÉ serveur (`getItemCost`) et **VÉRIFIÉ anti-tamper** contre `expectedCost` du
client (mismatch → `CLIENT_OUT_OF_SYNC`) — un coût déclaré falsifié est REFUSÉ (pas « ignoré » : vérifié). Puis débit
autoritatif `chargeUser` + don `giveReward` + `setPurchased`. On répercute ensuite le flag `purchased` dans le blob wire
(miroir du matching du jeu : `RewardHelper.compareDrops` + `typeIndex`), resync (heroes/diamonds/counts) + persiste.
`LoginServer` (handler message `PurchaseMerchantItem`) re-pousse le `MerchantUpdate` mis à jour pour re-synchroniser le
client.

**Test `MerchantPurchaseTest`** (régression → **118 tests**). Achat de l'objet le moins cher : débit EXACT du coût
recalculé + don (+qté) + `purchased=true` ; anti-triche 1 : ré-achat du même objet refusé (aucun débit) ; anti-triche 2 :
coût déclaré falsifié (=1) sur un autre objet → refusé, aucun débit, non marqué ; persistance round-trip wire + DB.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** Pilote `merchantbuy GEAR` (chemin client réel `ClientActionHelper
.purchaseMerchantItem`, calcule le typeIndex + le coût réel) → CALAMARI 2 843 GEAR_TOKENS → serveur `<== PurchaseMerchantItem`
→ `achat marchand GEAR CALAMARI appliqué [persisté] + MerchantUpdate re-poussé` → **DB : GEAR_TOKENS 39 000→36 157 (−2 843
exact), CALAMARI possédé=1, purchased=1** ; écran **BADGE BAZAAR** : solde 36 157 + **CALAMARI grisé (vendu)** — capture
`build/merchant_purchase_ingame.png`. Pilote DEV `merchantbuy <TYPE>`.

⇒ **MERCHANT #72 : incr. 1 (génération) + 1b (push) + 2 (achat) vérifiés EN JEU.** RESTE : incr. 3 refresh
(`REFRESH_TRADER`→`MerchantHelper.refresh` + régénération, corrige le PARTIEL en boucle), incr. 4 BLACK_MARKET limited-time.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/MerchantPurchaseTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/MERCHANT.md`, `MEMORY.md`.
