# GUILD WAR (#68) — suivi d'implémentation

> Document de SUIVI vivant, même forme que [`INVASION.md`](INVASION.md). Règle de travail :
> **on n'invente aucune valeur** (`PRINCIPLES.md` §4) et **on ne suppose rien** (§8) — chaque ligne
> ci-dessous est adossée à un fait (bytecode, `.tab`, chaîne localisée, ou exécution).
>
> Régression : `bash server/smoke/regression.sh` (seul échec toléré = flake connu `ChestWireTest`).

---

## 1. Ce que le jar contient VRAIMENT (vérifié, pas supposé)

### 1.1 La logique est CÔTÉ CLIENT — même configuration favorable qu'INVASION

| Classe | Rôle | Conséquence pour le serveur |
|---|---|---|
| `com.perblue.heroes.game.data.war.WarStats` | lit les 8 tables `war_*.tab` | le serveur consomme ses accesseurs, ne recopie rien |
| `com.perblue.heroes.game.logic.WarHelper` | saisons, ligues, voitures, bonus, reset joueur | logique réutilisable telle quelle |
| `com.perblue.heroes.game.logic.WarCombatHelper` | applique bonus de voiture + sabotages aux `Unit`/`UnitData` | **purement combat client** — hors périmètre serveur (comme l'arène) |
| `com.perblue.heroes.ui.war.WarClientHelper` | 40+ helpers d'écran **+ les validations d'action** | **source de vérité du protocole** : c'est lui qui décide ce qui est légal |

⇒ Comme pour l'arène et l'invasion, le serveur n'a qu'à **produire les bons messages** et
**ré-exécuter les mêmes validations** ; il ne réécrit aucune règle.

### 1.2 Données (`game-data/stats/`)

`war_constants.tab`, `war_cars.tab`, `guild_war_car_bonuses_v2.tab`, `war_league_brackets.tab`,
`war_posters.tab`, `war_sabotage_cost.tab`, `war_sabotage_effects.tab`, `war_box_options.tab`,
`war_merchant_drops.tab`.

Valeurs lues **au runtime** (sonde, pas lecture de fichier) :

* Ligues (`war_league_brackets.tab` via `WarStats.getLeagueRange`) :
  `UNRANKED 0` · `COPPER 1-199` · `BRONZE 200-399` · `SILVER 400-599` · `GOLD 600-799` ·
  `PLATINUM 800-999` · `CHALLENGER 1000-1199` · `LEGENDARY 1200+`
* `ENABLED_SERVERS = 1-25` → **le shard 1 est activé** (`WarStats.isWarEnabledForServer(1)=true`,
  `(99)=false`) : rien à forcer côté serveur.
* `BASE_CAR_SIZE=3`, `TIMEOUTS=3`, `SABOTAGE_PHASE_LENGTH=24 h`, `SABOTAGE_BAN_PHASE_LENGTH=12 h`,
  `GUILD_WAR_SPARS=TRUE`, `GUILD_WAR_BAN_PROTECT=TRUE`, `RESET_HOUR=11`.
* Coûts de sabotage (`war_sabotage_cost.tab`) : 67, 133, 200, 267, 333 … (×15).
* Les **prévisualisations de récompenses sortent bien des tables** (vérifié à l'exécution) :
  `getPromotionRewardPreviews(GOLD,…)` → `BADGE_CHEST_1X ×2`, `WAR_TOKENS 2500`, `MEMORY_TOKENS 900`,
  `GENERIC_STONES 40`, `GOLD 2 253 330`… ; `getSeasonRewardsPreview(GOLD,1,…)` → palier 4.
  **⚠️ Correction d'une lecture initiale erronée** : le paramètre entier de ces méthodes n'est PAS un
  « scalaire » mais **`L` = le NIVEAU D'ÉQUIPE** du joueur (`RewardDropExpression.convert0` fait
  `setVariable("L", n)`). C'est pourquoi mon premier relevé montrait des quantités négatives : j'avais
  passé 1. Conséquences mesurées en §étape 6.

### 1.3 Le jeu DOCUMENTE lui-même ses règles (`game-data/strings/HowToPlay.properties`)

Découverte importante : les cartes d'aide `WAR_CARD_*` **énoncent explicitement** les règles, et
elles **correspondent une à une aux constantes**. Ce n'est donc pas de l'inférence :

| Règle énoncée par le jeu | Constante correspondante |
|---|---|
| « Defeating an enemy Hero team earns your Guild **one point** » | `POINTS_PER_LINEUP = 1` |
| « Every enemy room your Guild clears … is worth **100 points** » | `POINTS_PER_CAR = 100` |
| « Rooms that have **no defenders** are automatically defeated and worth 100 points to the other side » | — (règle de résolution) |
| « **Clean sweep** = win all 3 rounds » (si la voiture *Sharky* survit) | `WarCarType.POINT_PER_CLEAN_SWEEP` |
| « **Defensive win** = un ennemi n'a pas battu un lineup » (si *Off Roader* survit) | `POINT_PER_DEFENSE_WIN` |
| « **Clean defense** = les 3 lineups gardent ≥1 héros debout » (si *Roller Diva* survit) | `POINT_PER_FULL_DEFENSE` |
| « Day One : sabotage **24 h**, bans pendant les **12 premières heures** » | `SABOTAGE_PHASE_LENGTH` / `SABOTAGE_BAN_PHASE_LENGTH` |
| « **one free attack per War** », attaque supplémentaire réservée aux officiers+ (réglable par le chef) | `doStartWarAttack` (max 2) + `EditGuildWarSettings.extraAttackRank` |
| « 3 équipes de 5 en défense, 3 équipes de 5 en attaque » | `HeroLineupType.WAR_DEFENSE_1..3` / `WAR_ATTACK_1..3` |
| « Les héros défenseurs battus sont **KO pour le reste de la guerre** » | `WarHeroSummary.defeated` |
| « Le coût de sabotage **monte** quand on cible le même joueur » | `WarStats.getSabotageCost(n)` (n = 1..15) |
| « Les tokens dépensés sont **remboursés si vous perdez** » | `WarGuildInfo.refundedAttacks` / `sabotageCurrency` |
| « **Top 10** (MMR) → repartent en **Gold** » | `TOP_X_GUILDS_TO_BASE=10`, `TOP_GUILDS_BASE_RATING=700`, `TOP_GUILDS_RATING_DECREMENT=10` |
| « Les autres sont replacées **de Copper à Silver** » | `NORMALIZE_RATING_TO=599` (= haut exact de SILVER) |
| « Une guilde **ne peut pas être rétrogradée** d'une ligue atteinte dans la saison » | `UserFlag.WAR_PROMOTIONS` + `WarHelper.updatePromotionFlag` (masque de bits par ligue) |

La cohérence est totale : `TOP_GUILDS_BASE_RATING=700` avec un décrément de 10 place les 10 premières
guildes entre **700 et 610**, c'est-à-dire **entièrement dans la plage GOLD (600-799)** ; et
`NORMALIZE_RATING_TO=599` est **exactement** le plafond de SILVER. Les constantes ont été choisies
pour produire le texte d'aide.

### 1.4 Ce qui est INDISCUTABLEMENT au serveur (preuve par scan du pool de constantes)

Méthode : extraction des 20 341 classes `com/perblue/**` et recherche du nom de chaque constante.

**28 constantes de `war_constants.tab` n'apparaissent QUE dans leur propre déclaration**
(`WarStats$Constants.class`) et **dans aucune autre classe** : `ELO_K`, `ELO_N`,
`ELO_LOSS_BUFFER_THRESHOLD`, `WIN/LOSE/DRAW_COEFFICIENT`, `NORMALIZE_RATING_TO`, `STARTING_MMR`,
`TOP_GUILDS_BASE_RATING`, `TOP_X_GUILDS_TO_BASE`, `TOP_GUILDS_RATING_DECREMENT`, `BYE_RATING_GAIN`,
`REMATCH_THRESHOLD`, `REMATCH_COST`, `WORST/BEST_REMATCH_SCALE`, `MAX_PREVIOUS_WARS`,
`POINTS_PER_LINEUP`, `POINTS_PER_CAR`, `NUM_SEASON_BOXES`, `LEAGUE_COST`, `RESET_HOUR`,
`BAN_COOLDOWN_LENGTH`, `BAN_MAX_STREAK`, `BAN_SCALING_PER_RANK_DIFF`, `PROTECT_COOLDOWN_LENGTH`,
`PROTECT_MAX_STREAK`, `PROTECT_SCALING_PER_RANK_DIFF`.

**Témoin de contrôle** (le discriminant est valide) : les constantes qui ONT un accesseur
apparaissent bien dans ≥ 2 classes — `BASE_CAR_SIZE` 2, `TIMEOUTS` 3 (dont `WarLogsWindow`),
`SABOTAGE_PHASE_LENGTH` 2, `GUILD_WAR_SPARS` 2.

⇒ **Même signature que `MERCENARY_COST` et `INVASION_BASE_DATE`** : ces constantes sont parsées pour
que le **SERVEUR** les utilise. Matchmaking, MMR/ELO, phases, points et rematch sont du calcul
serveur ; c'est cohérent avec `WarHelper.isWarActive` qui délègue à une `WarHelperExtension`
**absente côté client** (seul `CodeLocationHelper.isOnClient()` est câblé).

---

## 2. Protocole EXACT (relevé dans le client — source de vérité)

### 2.1 Messages dédiés client→serveur (7)

| Message | Champs | Réponse attendue |
|---|---|---|
| `GetWarInfo` | `warID` | `WarInfo` |
| `GetWarsList` | — | `WarsList{league, mMR, rank, wars:List<WarSummary>, unopenedBoxes}` |
| `GetWarRankings` | `league` | `WarRankings{league, seasonID, rankingRows, yourGuild, rewards, posterKey}` |
| `GetWarSeasonsList` | `guildID` | `WarSeasonsList{currentLeague, currentMMR, currentRank, seasons, unopenedBoxes}` |
| `RequestWarLogs` | `warID` | `WarLogs{attacks, defenses, members, yourSummary, enemySummary}` |
| `WarAttack` | `base:AttackBase, battles, defendingUserID` | (fire-and-forget + `WarPointsUpdate`) |
| `EditGuildWarSettings` | `guildID, existingInfo, extraAttackRank` | `UserGuildUpdate` |

### 2.2 Commandes `Action` (11) — extras relevés dans `ClientActionHelper`

| `CommandType` | Extras envoyés |
|---|---|
| `CHANGE_WAR_QUEUE` | `TYPE=WarQueueState` |
| `ASSIGN_WAR_CAR` | `ID=userID`, `TYPE=WarCarType` |
| `CHANGE_WAR_TARGET` | `GUILD_ID=ennemi`, `SLOT=WarCarType`, `TYPE=boolean` |
| `WAR_SABOTAGE_DEFENDER` | *unitType en 2ᵉ arg*, `GUILD_ID`, `ID=userID visé`, `TYPE=WarSabotageType`, `RESOURCE`, `INDEX=n` (rang de coût) |
| `START_WAR_ATTACK` | `ID=défenseur`, `GUILD_ID`, `COUNT`, `TIME=serverTimeNow` → `StartWarAttackResponse` |
| `WAR_SPAR_TARGET` | `GUILD_ID`, `ID=cible`, `INDEX=lineup` |
| `WAR_EDIT_BAN_PROTECT` | `GUILD_ID`, `SLOT=liste de héros (String)`, `INDEX=isBan`, `LEVEL=warID` |
| `CLAIM_WAR_BOX_REWARD` | `ID=boxID`, `INDEX=choix` |
| `GET_WAR_MEMBER_INFO` | `ID=userID` → `WarMemberInfo` |
| `GET_WAR_MOMENTS` | — → `WarMoments` |
| `RECORD_PHONY_WAR_ACTIVITY` | — |

### 2.3 Validations que le serveur doit RÉ-EXÉCUTER (relevées dans `WarClientHelper`)

* file d'attente → `GuildHelper.canQueueForWar(role, state)` + `Unlockables.isUnlocked(WAR, user)` (TL 45)
* ciblage → `GuildHelper.canWarTarget(role)`
* attaque → membre présent dans `yourGuild.members` (sinon `WAR_JOINED_LATE`) ; compteur
  `UserFlag.WAR_ATTACKS_USED` remis à zéro quand `TimeType.WAR_START_TIME_LAST_ATTACK != warInfo.startTime` ;
  **max 2** ; la 2ᵉ exige `GuildHelper.canUseExtraWarAttacks(role, guildInfo)` **et**
  `extraAttacksRemaining > 0` (sinon `WAR_EXTRA_ATTACK_ALREADY_USED` / `WAR_EXTRA_ATTACKS_DEPLETED`)
* sabotage → `UserHelper.chargeUser(resource, WarStats.getSabotageCost(n))` + `incDailyUses("war_activity")`
* ban/protect → `WarClientHelper.tryEditWarBanProtect` ; spar → `WarClientHelper.trySpar`

### 2.4 Acquis GRATUIT

Les **lineups de défense de guerre** arrivent déjà par `HeroLineupUpdate` avec
`HeroLineupType.WAR_DEFENSE_1/2/3` et sont **déjà persistés** par
`ServerUser.applyHeroLineupUpdate` (câblé pour l'arène). Rien à écrire.

---

## 3. Étapes

| # | Étape | Statut |
|---|---|---|
| 1 | **Calendrier de saison + ligues + modèle MMR** (`ServerWar`) | ✅ FAIT |
| 2 | **État de guerre persisté (`ServerWarState`, table `wars`) + file d'attente** | ✅ FAIT |
| 3 | **Matchmaking (appariement par MMR, anti-rematch, BYE) + phases** | ✅ FAIT |
| 4 | **Voitures : affectations, étoiles, portes de garage** | ✅ FAIT |
| 5 | **Attaques + scoring + journaux** | ✅ FAIT |
| 6 | **Fin de guerre : issue, delta MMR, boîtes** | ✅ FAIT (remboursements → dépendent du sabotage) |
| 7 | Sabotage / bans / protections / spars | ⬜ **PAS FAIT** |
| 8 | Fin de saison : distribution des boîtes + stockage/réclamation | ⬜ |

### Étape 1 — calendrier, ligues, MMR ✅

`dhserver.ServerWar` :

* `seasonIDAt(now)` / `seasonStartTime(id)` / `seasonEndTime(id)` — délègue à
  `WarHelper.getSeasonID/getSeasonYear/getSeasonMonth/getSeasonStartTime` (donc au fuseau serveur et
  à `RESET_HOUR=11`). Vérifié : `2018/1 → id 1`, `2019/1 → id 13`, `2026/7 → id 103`, aller-retour exact.
* `leagueForMMR(mmr)` — balaye `WarStats.getLeagueRange(league)` (données du jeu), aucune table recopiée.
* `startingMMR()` et les autres constantes — lues par **réflexion** sur `WarStats$Constants`
  (champs *package-private* → `getDeclaredField` + `setAccessible`, le piège déjà rencontré sur
  `GuildStats$Constants` où `getField` retombait silencieusement sur les défauts).
* `seasonResetMMR(rangFinal, mmrFinal)` — applique **la règle énoncée par le jeu** (§1.3) :
  rang ≤ `TOP_X_GUILDS_TO_BASE` → `TOP_GUILDS_BASE_RATING − (rang−1) × TOP_GUILDS_RATING_DECREMENT`
  (⇒ 700…610, tout GOLD) ; sinon → MMR **écrêté à `NORMALIZE_RATING_TO`** (⇒ ≤ 599, Copper→Silver).
* `highestLeagueReached` / `leagueFloor` — le plancher de ligue de la saison, d'après
  `UserFlag.WAR_PROMOTIONS` (masque de bits alimenté par `WarHelper.updatePromotionFlag`), qui
  implémente « pas de rétrogradation d'une ligue déjà atteinte ».

**⚠️ LECTURE STRUCTURELLE ASSUMÉE (isolée, comme `ServerInvasion.teamForRotation`)** :
`ServerWar.ratingChange(...)`. Le jeu **dit** que le MMR monte/descend selon victoire ou défaite et
**fournit** `ELO_K`, `ELO_N`, `WIN/LOSE/DRAW_COEFFICIENT`, mais **aucune table n'écrit la formule**.
On applique donc l'algorithme **que ces noms désignent** — l'Elo standard, avec `N` en échelle
logistique au lieu de 400 et `K` en facteur :
`E = 1 / (1 + 10^((Radv − Rmoi)/N))`, `ΔR = K × (coefficient(issue) − E)`.
C'est **isolé dans une seule méthode** pour être corrigé d'un seul endroit si une preuve apparaît.
Tout le reste (constantes, bornes de ligue, règle de reset, plancher de MMR) vient des données.

**🔎 Fait mesuré (propriété des constantes, pas un choix)** : `LOSE_COEFFICIENT = 0.4 > 0` implique
qu'une **défaite contre un adversaire suffisamment plus fort rapporte quand même du MMR**. Le point de
bascule se dérive : `N × log10((1−LOSE)/LOSE)` ≈ **352 points d'écart**. C'est manifestement voulu — un
Elo « pur » mettrait 0 pour une défaite, et la table n'aurait alors aucune raison de porter un
coefficient de défaite. Exposé par `ServerWar.defeatBreakEvenGap()` et couvert par le test.

**❌ Erreur de ma part, corrigée par le test** : j'avais d'abord câblé `ELO_LOSS_BUFFER_THRESHOLD`
comme « sous ce MMR, une défaite ne coûte rien ». Le test l'a invalidée immédiatement — et l'analyse a
montré que c'était **doublement fautif** : (a) c'était une règle **inventée** (rien dans le jar ni dans
les textes ne dit ce que cette valeur tamponne), et (b) elle était **sans effet** puisqu'à ce niveau de
MMR la défaite était déjà positive par la propriété ci-dessus. La constante est donc **lue et exposée
mais délibérément NON APPLIQUÉE**, avec une assertion dédiée qui vérifie qu'elle ne change rien — le
jour où sa sémantique sera établie, le test bougera.

Test : `server/smoke/WarSeasonTest` — identité de saison et aller-retour, ancrage sur `RESET_HOUR` au
fuseau serveur, continuité des plages de ligue **relues dans les données**, activation du shard,
cohérence des constantes, reset de saison (top 10 → GOLD, autres écrêtés au plafond SILVER **vérifié
égal à `NORMALIZE_RATING_TO`**), plancher de ligue non rétrogradable avec l'encodage de bits du jeu,
propriétés du delta de MMR + point de bascule dérivé + bornage au plancher de la table de ligues.
Sortie : shard 1 activé, saison 103 (2026/7), top 10 → 700..610 (tout GOLD), bascule à 352.

### Étape 2 — état de guerre persisté + file d'attente ✅

**`dhserver.ServerWarState`** — une GUERRE = l'appariement de deux guildes.

* Stockage conforme à PRINCIPLES §4/§6 : les deux camps sont des `WarGuildInfo`, **objets du jeu rangés
  en octets wire** ; on n'invente aucun schéma pour voitures, membres, bans/protections, cooldowns,
  points ni attaques supplémentaires. Seuls les scalaires d'appariement sont en format compact versionné.
* **État symétrique, deux lectures.** On ne stocke PAS un `WarInfo` tel quel : ce message est *relatif au
  spectateur* (`yourGuild`/`enemyGuild`). L'état canonique est donc côté A / côté B, et `toWarInfo(guildID)`
  produit la vue du demandeur. Un seul état ⇒ aucune divergence possible entre les deux guildes. Prouvé
  par le test : A voit `137 vs 42`, B voit `42 vs 137`, scalaires identiques.
* `toSummary(guildID)` pour `WarsList`/`WarMoments` ; `isBye()` pour une guerre sans adversaire.

**Table `wars`** (`shardID, warID, guildA, guildB, seasonID, startTime, endTime, state, data`) avec deux
index `(shard, guilde, startTime DESC)` — table dédiée plutôt que `shard_state` parce qu'on doit
l'*interroger* (« la guerre en cours de X », « les N dernières de X »).
`saveWar` **attribue le `warID` sous le même verrou que l'insertion** : la séquence « lire max+1 » puis
« insérer » est atomique.

**`ServerGuild` v8** — ce qui appartient durablement à la guilde : `warQueueState` + `warQueuedTime`,
`warMMR` + `warSeasonID`, `warPromotionMask` (plancher de ligue), `currentWarID`,
`previousWarOpponents` (anti-rematch, borné par `MAX_PREVIOUS_WARS`), `warExtraAttackRank`
(`EditGuildWarSettings`), bilan `warsWon/Lost/Completed`, et l'historique des saisons achevées en
**octets wire de `WarSeasonSummary`** (objet du jeu).

**File d'attente** (`ServerWar.changeQueueState`) : ré-exécute exactement les contrôles du client —
`GuildHelper.canQueueForWar` / `canUnQueueForWar` selon le sens, puis `Unlockables.isUnlocked(WAR)`.
`queueStateAfterMatch` distingue `QUEUED_SINGLE` (sort de la file) de `QUEUED_PERSISTENT` (y reste) —
c'est la différence que portent les noms mêmes de l'enum.

**Bascule de saison** (`rollOverSeason`) : idempotente, archive le bilan écoulé en `WarSeasonSummary`,
re-sème le MMR via `seasonResetMMR`, remet les compteurs à zéro et repositionne le plancher de ligue.

Test : `server/smoke/WarStateTest`. Sortie : refus MEMBER, refus par le gate TL45 du jeu, vues miroir,
round-trip DB de la guerre et de la guilde v8, anti-rematch borné à 20 sans doublon, bascule
saison 103→104 avec MMR 110 → 700 (GOLD, rang 1) et archive `1V @ MMR 110` persistée.

**🐛 Défaut trouvé PAR ce test — CORRIGÉ** : `UserStore.nextGuildID` se contentait de LIRE
`MAX(guildID)+1`. Or le handler `CreateGuild` enchaîne « lire l'identifiant », « créer la guilde »,
« enregistrer » en trois temps : deux créations concurrentes lisaient donc le **même** identifiant, et
la seconde **écrasait** la première (`upsert` sur la clé primaire) — une guilde disparaissait et son
fondateur se retrouvait pointé sur celle d'un autre. La méthode **alloue** désormais réellement, en
persistant un compteur dans `shard_state` **dans le même bloc synchronisé** que la lecture ; elle est
monotone et reprend au-delà du `MAX` existant, donc aucune migration n'est nécessaire. Les guerres
n'ont jamais été exposées (`saveWar` attribue le `warID` sous le même verrou que l'insertion).
Couvert par `WarStateTest` : deux allocations **sans enregistrement intercalé** doivent différer.

### Étape 3 — appariement, ouverture des guerres, phases ✅

**`dhserver.ServerWarMatchmaker`**. L'appariement est du traitement **purement opérateur** : le jar
client n'en a aucune trace (il affiche la guerre qu'il reçoit), et les constantes qui le pilotent ne
sont référencées par aucune classe cliente (§1.4).

**Chronologie des phases : reprise EXACTE du client, pas une invention.** Deux méthodes du client la
fixent mot pour mot :
* `WarClientHelper.checkForEndOfSabotage` — « si `state == SABOTAGE` et `stateEndTime` dépassé →
  `state = ACTIVE` et `stateEndTime = endTime` » ;
* `WarHelper.isBanPhase` — « `state == SABOTAGE` et `extraStateEndTime` non dépassé ».

D'où l'ouverture en `SABOTAGE` avec `stateEndTime = début + SABOTAGE_PHASE_LENGTH` (24 h) et
`extraStateEndTime = début + SABOTAGE_BAN_PHASE_LENGTH` (12 h), puis `ACTIVE` jusqu'à `endTime` —
ce qui reproduit exactement l'aide du jeu (« sabotage throughout the full 24 hours. During the first
12 hours … set the Guild's Bans », puis « Day Two is all about clearing rooms »).
`WarHelper.isProtectPhase == isQueued` confirme de son côté que la protection se joue pendant la file
d'attente (« During the Queue Phase, Protect your Heroes »).

**Seul point non nommé par une constante** : la durée du jour 2. Elle se déduit de « two-day long
Wars » et du jour 1 à 24 h → `warDuration() = 2 × SABOTAGE_PHASE_LENGTH`, isolée en une méthode.

**Appariement** : coût = |ΔMMR| + pénalité d'anti-rematch **des deux côtés**. Déterministe (tri MMR
décroissant, identifiant pour départager, puis coût minimal). Effectif impair → **BYE**, enregistré
comme une vraie guerre en état `BYE` (les données prévoient explicitement `BYE_RATING_GAIN`).

**⚠️ LECTURE STRUCTURELLE ASSUMÉE (isolée)** : `rematchPenalty`. `REMATCH_THRESHOLD=7` délimite
« adversaire récent », `REMATCH_COST=200` est le prix d'un re-match, et `WORST_REMATCH_SCALE=1` /
`BEST_REMATCH_SCALE=0` encadrent visiblement une **échelle** appliquée à ce prix — mais aucune table
n'écrit la formule. La lecture retenue est la seule qui utilise les quatre : interpolation par
ancienneté, l'adversaire de la guerre précédente au prix plein (200), celui d'il y a 7 guerres à zéro,
rien au-delà. Mesuré : rang 0 → 200, rang 1 → 167, rang 6 → 0.

Test : `server/smoke/WarMatchmakingTest`. Sortie : `Mille(1000)` vs `NeufCentQuatreVingtDix(990)` et
`QuatreCentDix(410)` vs `QuatreCents(400)` (coût 10 chacun) ; **anti-rematch effectif** — à MMR
identique, A(500) évite B(500) affronté juste avant et prend C(480) ; 3 guildes → 1 paire + 1 BYE ;
ouverture SABOTAGE 24 h (ban les 12 premières) puis ACTIVE à +48 h ; transition conforme au client ;
file `QUEUED_SINGLE` → `NOT_QUEUED` ; guerre relue avec les deux vues correctes et les ligues tenant
compte du plancher de saison ; BYE persisté sans adversaire mémorisé.

### Étape 4 — voitures, affectations, étoiles, portes ✅

**`dhserver.ServerWarCars`**. Tout vient du client, rien n'est déduit :

* **Source de vérité** : le client reconstruit lui-même `cars[].members` depuis
  `members[].assignedCar` (`WarClientHelper.collectWarInfoCarMembers`). L'état canonique du serveur est
  donc la carte `members` ; `rebuildCars` en **dérive** les voitures de la même façon, ce qui rend toute
  divergence impossible.
* **Étoile = lineup** : `WarHelper.hasRemainingLineups` et `getDefeatedEnemyCarTypes` testent tous deux
  `starsEarned >= starsTotal`. Donc `starsTotal` = lineups postés dans la salle, `starsEarned` = lineups
  battus — cohérent avec `POINTS_PER_LINEUP=1` et `POINTS_PER_CAR=100`. Corollaire **vérifié** : une
  salle **sans défenseur** satisfait `0 >= 0` et compte comme prise, ce qui est exactement la phrase de
  l'aide « Rooms that have no defenders are automatically defeated ».
* **Portes de garage** : miroir de `WarClientHelper.getClosedGarageDoors` — étage 1 toujours ouvert,
  étage 2 ouvert dès qu'**une** voiture de l'étage 1 est prise, étage 3 dès qu'une de l'étage 2 l'est
  (« You must steal a car to open the next floor »). Pendant `SABOTAGE`, son **propre** garage est
  entièrement fermé, celui de l'ennemi non.
* **Ordre du garage** : `GARAGE_ORDER` relevé dans le client, et **vérifié** cohérent avec
  `WarHelper.getFloorNumber` (0-2 étage 1, 3-5 étage 2, 6-8 étage 3).
* **`ASSIGN_WAR_CAR`** : capacité = `WarHelper.getMaxCarSize` (= `BASE_CAR_SIZE` + perk de taille).

**🔎 Règle de permission corrigée par les faits.** J'avais gaté l'auto-placement sur
`GuildHelper.canMoveWarLineups`. Deux faits l'ont invalidé : (1) `WarCarLineupsTable:579` teste
`if (isNextWarState && (c'estVous || canMoveWarLineups(rôle)))` — on édite donc **toujours** sa propre
place, cette permission n'étant que le droit *supplémentaire* d'éditer la carte d'un autre ; (2) sonde
de la table de permissions : **`WAR_MOVE_LINEUPS` n'est accordé à AUCUN rôle** dans ce build, pas même
`RULER` — le gate aurait rendu l'auto-placement impossible pour tout le monde. Seul
`canMoveOthersWarLineups` (RULER/OFFICER/CHAMPION) gate le déplacement d'autrui. Assertion dédiée dans
le test pour que le jour où ce fait change, il se voie.

**🐛 Double source de vérité — corrigée.** `GuildInfo`, l'objet **du jeu**, porte déjà
`warQueueState`, `warStartTime`, `warEndTime`, `warExtraAttackRank`, `warExtraAttacksRemaining` et
`warMembers` — persistés en octets wire **et lus par le client** (`WarHelper.isWarActive` compare
`getYourGuildInfo().warEndTime` à l'heure serveur ; `GuildHelper.canUseExtraWarAttacks` lit
`warExtraAttackRank`). Ma v8 les avait **dupliqués** côté serveur : deux sources de vérité, exactement
ce qu'interdisent §4/§6 — et surtout, sans écrire `warEndTime` dans le `GuildInfo`, **le client
n'aurait jamais vu de guerre active**. v8 ne les stocke plus ; `ServerGuild` les expose par des
accesseurs pointant sur `info`, et `openWar` renseigne la fenêtre. Vérifié : aucun blob v8 n'existait
en base (la base réelle est en v4), la correction est donc sans migration.

**⚠️ Piège d'API documenté** : `sideOf` **décode** (objet neuf) et `putSide` **fige un instantané**.
Muter l'objet rendu sans rappeler `putSide` perd la mutation **sans erreur** — le test l'a rencontré.
Motif imposé : « `sideOf` → muter → `putSide` ».

Test : `server/smoke/WarCarsTest`. Sortie : ordre/étages conformes au jeu, salle vide comptée prise,
auto-placement autorisé, déplacement d'autrui refusé à un MEMBER, capacité 3 respectée, 3 membres × 3
lineups = 9 étoiles puis salle prise à 9/9, étage 2 puis 3 ouverts par vol de voiture, garage propre
fermé en SABOTAGE, round-trip DB reconstituant affectations, étoiles et portes.

### Étape 5 — attaques, conséquences, score ✅

**`ServerWarAttack` + `ServerWarScoring`**, journaux dans `ServerWarState` v2.

**Le barème n'est pas inventé : le client l'écrit.** `WarOutcomeWindow` affiche chaque ligne du
récapitulatif en faisant `n14 = compte × scalar; total += n14` — les cinq couples
`X`/`Xscalar` de `WarOutcomeSummary` SONT le barème, et le total est leur somme. Aucune classe cliente
ne *remplit* ces champs (seuls `WarLogsWindow` et `WarOutcomeWindow` les lisent) : c'est au serveur.
Les cinq catégories correspondent une à une à la carte « Scoring » de l'aide, et les trois bonus de
voiture ne rapportent **que si la voiture survit** — d'où un barème mis à zéro quand la salle est prise.

**Validations** : reprise de `doStartWarAttack` (mode débloqué, membre présent sinon `WAR_JOINED_LATE`,
**2 attaques max**, la seconde exigeant `canUseExtraWarAttacks` **et** `extraAttacksRemaining > 0`),
plus deux contrôles que seul le serveur peut faire (phase de bataille, salle ouverte). La remise à zéro
du compteur passe par la méthode du jeu `WarHelper.tryResetUserWarState`.

**Combat client-autoritatif**, comme la campagne et l'arène : le client renvoie
`WarAttack.battles = List<AttackStageResult>`. Le serveur est autoritatif sur le **droit** d'attaquer,
la cible et **toutes les conséquences** (héros KO pour le reste de la guerre, étoiles, portes, score).

**🐛 Bug PRÉ-EXISTANT trouvé et corrigé (#64, mercenaires).** Sonde : `setTime` persiste (il écrit dans
`this.extra.times`) mais **`setCount(UserFlag)` NON** — les compteurs vivent dans `User.counts`, une
carte interne hors `this.extra`. Or `creditMercenaryHireReward` faisait `setCount` **sans**
`resyncCounts`, et son commentaire affirmait exactement le contraire (« auto-persisté this.extra ») :
le compteur hebdomadaire « earned this week » repartait donc à zéro à chaque round-trip.
`GuildMercRewardTest` ne l'avait pas vu parce qu'il n'assertait que la monnaie `SOCIAL_BUCKS`, qui
elle persiste par un autre chemin. Corrigé, et le compteur de guerre passe désormais par
`ServerUser.warAttacksUsed`/`consumeWarAttack`, qui portent la discipline de re-synchronisation.

**🔎 Trois fois, le test a eu tort et la règle du jeu raison** — chaque fois autour de la même phrase,
« Rooms that have no defenders are automatically defeated and worth 100 points to the other side » :
1. une salle vide étant déjà « prise », un étage 1 dégarni **ouvre immédiatement l'étage 2** ;
2. les salles laissées vides valent **déjà 100 points chacune à l'adversaire** avant la moindre
   attaque (mesuré : 5 salles vides = 500 points) ;
3. dans le scénario du test, le camp attaquant garnit *moins* de salles que sa cible et **perd** la
   guerre malgré ses attaques réussies (608 contre 700).
Ces trois faits sont désormais **assertés explicitement** plutôt que contournés — ils décrivent une
mécanique de jeu réelle et forte : remplir ses salles est prioritaire sur attaquer.

Test : `server/smoke/WarAttackTest`. Sortie : refus (phase, étage fermé, retardataire, défenseur
inconnu, 3ᵉ attaque, MEMBER sans droit, crédit épuisé), lineup KO définitif, salle prise au balayage,
étage 2 ouvert, barème `3×1 + 6×100 + 1×0 = 603`, bonus de balayage `0 → 5` selon que la voiture
survit, issue cohérente avec les totaux dans les deux sens, DRAW à égalité, BYE, journaux et score
recalculés à l'identique après round-trip DB.

### Étape 6 — clôture d'une guerre ✅

**`dhserver.ServerWarEnd`**. À l'échéance : issue depuis les totaux, variation de MMR des deux camps,
plancher de ligue, libération des guildes, persistance. **Idempotent** — une guerre déjà close ne se
recompte pas (assertion dédiée).

Point de correction pris en compte : **les deux variations se calculent sur les MMR d'AVANT**. Les
appliquer l'une après l'autre noterait le second camp contre un adversaire déjà mis à jour, et
l'échange ne serait plus symétrique.

Un BYE ne se compare à personne : le camp encaisse `BYE_RATING_GAIN` (mesuré : MMR 10 → 60).

**⚠️ Fait mesuré, et garde-fou qui en découle.** La variable des expressions de récompense est
**`L` = le niveau d'équipe du joueur**. Or :

| Table | Protection dans les données | Comportement mesuré |
|---|---|---|
| **Promotion** (`war_box_options.tab`) | `max(…,1)` partout | jamais ≤ 0 — vérifié sur **7 ligues × 4 niveaux (84 boîtes)** |
| **Saison** | **aucune** | **quantités NÉGATIVES** en dessous de **TL 289** (COPPER, GOLD) et **TL 282** (LEGENDARY) |

Le barème de saison a manifestement été calibré pour la population du jeu live (niveaux d'équipe très
élevés). Passer une quantité négative à `RewardHelper.giveReward` **retirerait** des ressources au
joueur — l'inverse d'une « End-of-Season Reward ». `ServerWarEnd.keepPositive` écarte donc les
quantités non strictement positives. Ce n'est pas une valeur inventée : on ne fabrique aucune
récompense, on refuse seulement d'en retirer — et c'est la seule lecture compatible avec les
`max(…,1)` que les concepteurs ont mis partout ailleurs. Le test **exerce réellement** le garde-fou :
il vérifie d'abord qu'à TL 45 la table produit bien 3 quantités ≤ 0 (sinon l'assertion elle-même est à
revoir), puis qu'aucune ne sort de la boîte ; et qu'à TL 565 la même table donne
`GENERIC_STONES×247, EPIC_CHIP×1522, GENERIC_MEGABIT×225909`.

Test : `server/smoke/WarEndTest`. Sortie : rien avant l'échéance ; clôture `A VICTORY 972 pts
(MMR 900 → 1011)` / `B DEFEAT 0 pts (MMR 1100 → 1069)` avec deltas calculés sur les MMR d'avant ;
plancher de ligue respecté ; guildes libérées ; 2ᵉ clôture sans effet ; round-trip DB ; BYE +50 ;
84 boîtes de promotion sans quantité ≤ 0 ; garde-fou de saison exercé aux deux extrêmes.

### ⚠️ Ce qui reste — énoncé sans arrondi

* **Sabotage, bans, protections, spars : PAS FAITS.** C'est un pan entier (`WAR_SABOTAGE_DEFENDER`,
  `WAR_EDIT_BAN_PROTECT`, `WAR_SPAR_TARGET`, `war_sabotage_effects.tab`, cooldowns et streaks
  `BAN_*`/`PROTECT_*`). Le **remboursement des tokens au perdant** (« Tokens spent are refunded if you
  lose the War ») en dépend directement : sans comptabilité des frais de sabotage, il n'y a rien à
  rembourser. Je ne l'ai donc pas simulé.
* **Stockage et réclamation des boîtes** : `ServerWarEnd` sait les *générer* depuis les tables du jeu ;
  il reste à les attribuer par joueur, les persister et câbler `CLAIM_WAR_BOX_REWARD`.
* **Handlers réseau** : aucun des 7 messages ni des 11 `CommandType` n'est encore branché dans
  `LoginServer` — tout le moteur est testé unitairement, rien n'est joignable depuis le client.
* **Vérification EN JEU** : nulle. Statut 🟢, pas ✅.
