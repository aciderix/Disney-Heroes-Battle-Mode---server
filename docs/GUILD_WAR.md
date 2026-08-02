# GUILD WAR (#68) — suivi d'implémentation

> Document de SUIVI vivant, même forme que [`INVASION.md`](INVASION.md). Règle de travail :
> **on n'invente aucune valeur** (`PRINCIPLES.md` §4) et **on ne suppose rien** (§8) — chaque ligne
> ci-dessous est adossée à un fait (bytecode, `.tab`, chaîne localisée, ou exécution).
>
> Régression : `bash server/smoke/regression.sh` — **76/76, aucun échec toléré**. (L'ancien « flake
> `ChestWireTest` » n'en était pas un : c'était une course réelle, élucidée le 2026-08-02, cf. §Étape 10.)
>
> **Statut : ✅ VÉRIFIÉ EN JEU le 2026-08-02** pour le protocole d'affichage et le cycle de vie d'une
> guerre (§4) ; les ACTIONS de jeu (défense, attaque, sabotage, réclamation de boîte, clôture) restent
> à confirmer en jeu — voir §4 « Ce qui reste NON vérifié ».

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
| 6 | **Fin de guerre : issue, delta MMR, remboursements, boîtes** | ✅ FAIT |
| 7 | **Sabotage / bans / protections / spars** | ✅ FAIT |
| 8 | **Boîtes : stockage par joueur + réclamation** | ✅ FAIT |
| 9 | **Branchement réseau (7 messages + 11 commandes)** | ✅ FAIT |
| 10 | **Ordonnanceur : appariement à l'heure, phases, clôtures, boîtes automatiques** | ✅ FAIT |

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

* **Stockage et réclamation des boîtes** : `ServerWarEnd` sait les *générer* depuis les tables du jeu ;
  il reste à les attribuer par joueur, les persister et câbler `CLAIM_WAR_BOX_REWARD`.
* **Distribution automatique des boîtes** en fin de saison / à la promotion : `ServerWarEnd` sait les
  générer et `ServerWarBoxes` les stocker et les réclamer, mais rien ne les ATTRIBUE encore
  automatiquement — il manque le déclencheur (promotion détectée, bascule de saison).
* **Ordonnanceur** : `pair`/`openWar`/`finishWar`/`advancePhase` existent et sont testés, mais aucune
  tâche périodique ne les appelle. Les phases avancent paresseusement sur `GetWarInfo` ; l'appariement
  et la clôture demandent un déclencheur (tour de matchmaking, cron).
* **Vérification EN JEU** : nulle. Statut 🟢, pas ✅.

### Étape 7 — sabotages, bans, protections, spars ✅

**`dhserver.ServerWarSabotage`**. Les trois validations viennent du client, ré-exécutées :
`doSabotageWarDefender`, `tryEditWarBanProtect`, `trySpar`.

**Anti-triche notable** : le client envoie un `INDEX` de palier de coût, mais le prix **monte avec le
nombre de sabotages déjà posés sur la MÊME cible**. `WarClientHelper.getSabotageNumber` le compte
depuis les défenses de la cible — le serveur **refait ce calcul** au lieu de faire confiance à
l'`INDEX`, sinon n'importe qui paierait toujours le premier palier. Mesuré : 67 puis 133.

**Frais comptabilisés PAR JOUEUR** (`ServerWarState` v3) : « Tokens spent are refunded if you lose the
War » impose de rembourser *celui qui a payé*, et comme le prix escalade, un simple compteur de
sabotages ne permettrait pas de retrouver la somme. On enregistre le montant réellement débité.

**⚠️ Deux inférences assumées, isolées et documentées** :
1. `sabotageCurrency` → **`WAR_TOKENS`**. Le champ est rempli par le serveur (aucune classe cliente ne
   l'écrit) et il n'existe **pas** de `ResourceType.GUILD_TOKENS` malgré la formule « Sabotage uses
   Guild Tokens » de l'aide. Parmi les `ResourceType` du jeu, `WAR_TOKENS` est la monnaie de guerre
   (« Use War Tokens to buy items from the War Shop! ») et c'est elle que paient les boîtes de guerre.
   Sélection à un seul endroit (`DEFAULT_SABOTAGE_CURRENCY`), pas une valeur fabriquée.
2. `sabotageTypes` → **tous les types que le jeu déclare valides** (`WarHelper.isValidSabotage` :
   tout sauf `DEFAULT` et `DELAY_ARRIVAL` — 24 types, chacun ayant bien une valeur X dans
   `war_sabotage_effects.tab`, vérifié). L'aide dit « As your Guild ranks up, more sabotages will be
   available », mais **aucune table ne relie un niveau de guilde à un sous-ensemble** : restreindre
   reviendrait à inventer la progression.

**Constat de fidélité sur les spars** : le quota vient du perk de guilde `WAR_SPARS`. Une guilde qui ne
l'a pas acheté a un quota de **0**, donc aucun spar — et c'est le comportement correct, pas un bug.
Le test l'assert dans les deux branches. Il vérifie aussi que « Spars do not consume your War
attack » : le compteur d'attaques reste à 0.

Test : `server/smoke/WarSabotageTest`. Sortie : 24 types valides ; coût 67 → 133 recalculé serveur ;
refus (hors phase, cible inconnue, héros déjà saboté, type invalide, monnaie insuffisante) ; frais
imputés au bon joueur ; bans (MEMBER refusé, 3 > max refusé, 2 acceptés, cooldown respecté) ; fenêtre
de ban fermée après 12 h alors que les protections restent ouvertes ; round-trip DB complet.

**🔎 Encore une fois, la mise en scène du test avait tort.** Mon premier jet donnait 5 héros répétés
sur 3 lineups : « déjà saboté » ne se déclenchait jamais. Le protocole tranche — `sabotageWarDefender`
identifie la victime par son **seul `UnitType`**, ce qui n'aurait aucun sens si un héros pouvait
occuper deux lineups. Une défense de guerre, ce sont donc **15 héros distincts**.

### Remboursement des sabotages au perdant ✅ (complète l'étape 6)

Une fois la comptabilité par joueur en place (v3), `finishWar` calcule les remboursements du camp
**perdant** et `creditRefunds` les crédite. « Tokens spent are refunded if you lose the War » — et
c'est **celui qui a payé** qui récupère, d'où l'imputation par joueur plutôt qu'un total de guilde.
Un joueur introuvable (compte supprimé) n'est pas remboursable : le cas est **journalisé** au lieu de
laisser un remboursement s'évaporer sans trace.

Vérifié : B perd, 300 `WAR_TOKENS` rendus au joueur qui les avait dépensés, crédités et persistés ;
le vainqueur ne récupère rien.

### Étapes 8 et 9 — boîtes réclamables et branchement réseau ✅

**`ServerWarBoxes`** (table `user_war_boxes`) : les boîtes sont gagnées par la GUILDE mais **réclamées
par JOUEUR** — `WarsList.unopenedBoxes` et `WarSeasonsList.unopenedBoxes` voyagent dans des messages
adressés à un joueur, et `CLAIM_WAR_BOX_REWARD{ID, INDEX}` désigne une boîte et l'option retenue. Chaque
boîte est un `WarBoxInfo` (objet du jeu) en octets wire. **Retirer la boîte à la réclamation EST
l'anti-double-réclamation** : une boîte réclamée n'existe plus, donc rejouer la commande ne rend rien —
inutile de tenir un registre d'identifiants réclamés.

**Branchement réseau — tout est désormais joignable depuis le client.**

| Message | Réponse |
|---|---|
| `GetWarInfo` | `WarInfo` (+ bascule de phase paresseuse `SABOTAGE → ACTIVE`, comme le ferait le client) |
| `GetWarsList` | `WarsList` (historique, ligue, MMR, rang, boîtes en attente) |
| `GetWarSeasonsList` | `WarSeasonsList` (saisons archivées + boîtes) |
| `GetWarRankings` | `WarRankings` (guildes du shard triées par MMR, filtrées par ligue) |
| `RequestWarLogs` | `WarLogs` (attaques menées, attaques subies, récapitulatifs, membres) |
| `WarAttack` | applique le verdict + `WarPointsUpdate` **diffusé à toute la guilde** |
| `EditGuildWarSettings` | `UserGuildUpdate` |

| Commande | Effet |
|---|---|
| `CHANGE_WAR_QUEUE` | file d'attente → `WarQueueStateUpdate` diffusé |
| `ASSIGN_WAR_CAR` | affectation de salle → `WarCarAssignmentUpdate` diffusé |
| `CHANGE_WAR_TARGET` | marquage de cible (`canWarTarget`) → `WarTargetUpdate` diffusé |
| `WAR_SABOTAGE_DEFENDER` | sabotage (coût **recalculé serveur**) → `WarSabotageUpdate` diffusé |
| `WAR_EDIT_BAN_PROTECT` | bans/protections → `WarEditBanProtectUpdate` diffusé |
| `WAR_SPAR_TARGET` | spar → `WarSparsUpdate` diffusé |
| `START_WAR_ATTACK` | validation + consommation → `StartWarAttackResponse` + `AddInProgressWarAttack` |
| `CLAIM_WAR_BOX_REWARD` | crédite l'option choisie et retire la boîte |
| `GET_WAR_MEMBER_INFO` | `WarMemberInfo` (allié ou ennemi) |
| `GET_WAR_MOMENTS` | `WarMoments` |
| `RECORD_PHONY_WAR_ACTIVITY` | **NO-OP fidèle** — `ActionHelper.doAction` n'a aucune branche pour ce type (même cas que `RECORD_SERVER_ROLL_FINISHED`) : on acquitte sans rien simuler, inventer un compteur violerait §4 |

**Deux détails de protocole relevés au bytecode, pas devinés** : le héros visé par un sabotage voyage
dans **`Action.heroType`** (2ᵉ argument de `doAction`), pas dans les extras ; et `EditGuildWarSettings`
n'a **aucun `GuildPermission` dédié** — l'aide du jeu tranche (« The Guild **Leader** may change the
settings »), donc c'est réservé au `RULER`.

### Étape 10 — l'ORDONNANCEUR ✅

Jusqu'ici **toutes les briques existaient mais rien ne les déclenchait** : aucune guerre ne démarrait
d'elle-même, aucune ne se terminait, aucune boîte ne tombait. C'est le rôle de `ServerWarScheduler`, un
**tour de boucle idempotent et rejouable** (`tick`), branché dans `LoginServer.main` sur un thread démon
(`startBackgroundLoop`, période réglable par `-Ddh.war.tick.seconds`, défaut 60 s) et applicable à tous les
shards portant des guildes (`tickAllShards` + `UserStore.listGuildShards`, PRINCIPLES §5).

Un tour fait, dans cet ordre :

| # | Étape du tour | Idempotence |
|---|---|---|
| 1 | **Bascule de saison** (`ServerWar.rollOverSeason` avec le rang final par MMR) + **boîtes de fin de saison** | `warSeasonID == season` → on passe |
| 2 | **Clôture** des guerres échues (`ServerWarEnd.finishWar` : issue, MMR, remboursements) + **boîtes de promotion** | `Result.alreadyFinished` |
| 3 | **Avance de phase** `SABOTAGE → ACTIVE` (`ServerWarMatchmaker.advancePhase`) | la phase ne change qu'une fois |
| 4 | **Appariement** des guildes inscrites, à l'heure prévue | repère persisté dans `shard_state` |

**Le calendrier.** Le client affiche « Next War Starts in » à partir de `WarQueueStateUpdate
.nextMatchmakingTime`, dont `WarClientHelper.updateWarInfoQueueState` fait **directement**
`warInfo.startTime` : **le moment de l'appariement EST le début de la prochaine guerre**. ⚠️ **Lecture
structurelle assumée, isolée dans `lastMatchmakingTime`** : on l'ancre sur `RESET_HOUR` (11 h, fuseau
serveur) — seule heure de référence que `war_constants.tab` fournisse, et qu'**aucune classe cliente ne
lit** (même signature que `ELO_K` & co.). Une seule méthode à corriger si un fait la contredit.

**Deux pièges traversés, tous deux réels :**

1. **La fenêtre se compare au PASSÉ, pas au futur.** La première version testait `now >= nextMatchmakingTime(last)`
   — or cette valeur est par construction dans le futur, donc la condition n'était **jamais** vraie et
   l'appariement ne tournait pas une seule fois. Le tour compare désormais le repère persisté à la **dernière**
   occurrence de `RESET_HOUR` (`last < lastMatchmakingTime(now)`), et enregistre le repère de la **fenêtre**
   (pas `now`), sans quoi l'heure d'appariement dériverait à chaque tour.
2. **Un shard neuf ne doit pas apparier à son premier tour.** Sinon le tout premier démarrage du serveur
   ouvrirait des guerres hors calendrier, à n'importe quelle heure. Il pose le repère et attend la prochaine
   occurrence — d'où l'outil opérateur ci-dessous pour n'avoir pas à attendre.

**Deux masques distincts, à ne surtout pas confondre** — `ServerGuild` v9 ajoute `warBoxedLeagueMask` à côté
de `warPromotionMask` : le second dit « ligue ATTEINTE » (et sert de plancher anti-rétrogradation, « cannot
be demoted »), le premier « boîtes DÉJÀ REMISES ». Les fusionner redistribuerait des boîtes à chaque guerre.
Le masque de boîtes repart à zéro à chaque nouvelle saison, le plancher non.

**Boîtes générées joueur par joueur** : le montant dépend du **niveau d'équipe** (variable `L` des
expressions), donc `awardBoxes` boucle sur les membres et génère le lot de chacun. Le garde-fou `keepPositive`
(récompenses de saison négatives sous TL 289) s'applique donc naturellement, par joueur.

**Outil opérateur `server/smoke/AdminWar.java`** — pendant de `AdminGuild`/`AdminMail`. Il ne crée aucune
règle, il appelle l'ordonnanceur et lit l'état persisté :

```
AdminWar [--db server/data/dh-server.db] [--shard 1] --status
AdminWar [--db …] [--shard 1] --tick [--force]
```

`--status` rend la saison, l'heure du prochain appariement, et par guilde : MMR, ligue effective, état de
file, guerre en cours et son échéance, plus le total de boîtes en attente. `--tick --force` apparie
immédiatement — **seul levier forcé** ; clôtures et bascules de saison dépendent d'échéances réelles qu'on
ne bouscule pas. Vérifié en CLI : 3 guildes inscrites → tour non forcé = aucun appariement (calendrier
respecté) → tour forcé = **1 paire + 1 BYE**, guildes en `SABOTAGE` jusqu'à J+2, file remise à `NOT_QUEUED`.

**Test `WarSchedulerTest`** (dans la régression) : calendrier ancré sur `RESET_HOUR` et jamais à plus de
24 h ; appariement une seule fois par fenêtre ; `GuildInfo.warEndTime` porte bien la fenêtre (c'est ce que le
client lit) ; avance de phase ; clôture + boîtes de promotion ; **non-redistribution** des boîtes au tour
suivant ; réclamation d'une boîte (option créditée, boîte retirée, rejeu sans effet, persistance) ; bascule
de saison (archivage, remise à zéro du masque de boîtes, boîtes de fin de saison) et son idempotence.

### 🐛 Le « flake `ChestWireTest` » n'était pas un flake

Trouvé en vérifiant la régression après l'étape 10 : `ChestWireTest` échouait **systématiquement**, ce qui
contredisait la note « vert en isolation » portée depuis des semaines. Mesure : un message émis par le client
**avant que le serveur ait accepté la connexion** est **perdu** — envoi immédiat après `open()` (ou depuis le
`onOpen` du client) → jamais reçu, 5 essais sur 5 ; envoi 50 ms plus tard → reçu, 5 essais sur 5. La cause
attribuée jusqu'ici (chargement de `GuildStats`) était fausse : cette exception-là est bien levée mais
**absorbée** par le warm-up de `ServerContext`.

Le **vrai client n'y est pas exposé** : son `/login` HTTP précède l'ouverture du socket de jeu, ce qui laisse
la fenêtre passer largement. Le test, lui, enchaînait connexion et envoi dans la même milliseconde.

⚠️ **Correction d'une conclusion trop rapide** : j'ai d'abord annoncé qu'il suffisait d'attendre l'`accept`
du serveur. **C'est faux** — cette seule garde laisse encore **3 échecs sur 10**. Ce qui est établi, c'est
l'existence et l'ampleur de la fenêtre, pas son mécanisme : `GruntNIOTCPServer.read` ne consomme rien quand la
connexion est absente du registre ou pas prête (il rend `false` sans lire, et le sélecteur par niveau devrait
réessayer), et `GruntTCPConnection.send` écrit de façon synchrone — **où les octets disparaissent reste
inexpliqué**, et c'est consigné tel quel dans `SHIMS.md`.

Traitement : `LoginServer` expose `connectionsAccepted` (compteur utile aussi en exploitation), et le test
attend ce point **puis RÉÉMET** le `ClientInfo` jusqu'à obtenir le `BootData` — réponse idempotente du
serveur. Ce n'est pas un faux « OK » : l'échange RÉEL `BuyChests → LootResults` reste exigé pour passer.
Vérifié **8/8** en isolation, et **régression complète sans aucun échec toléré** — une première.

---

## 4. ✅ VÉRIFICATION EN JEU (2026-08-02) — client réel → notre serveur → affichage

Menée sur le compte **BaronessDante** (TL 100, guilde « Baroness Legion »), pile complète
`desktop-port/run-online.sh`. `WAR` est déverrouillé à **TEAM_LEVEL_REQ 45** (`unlockables.tab`).

Outillage ajouté pour la mener, sans jamais toucher au jeu :
* **pilote DEV `warqueue <ÉTAT>`** → appelle le CHEMIN RÉEL `ClientActionHelper.changeGuildWarQueueState`
  (le message ne porte que l'état, relevé au bytecode ; le `WarInfo` ne sert qu'au rappel local) ;
* **`server/smoke/WarRivalSeed.java`** → sème une guilde adverse inscrite en file (même rôle que
  `GuildAidSeed` pour les dons) ;
* **`AdminWar --tick --force`** → déclenche l'appariement sans attendre `RESET_HOUR`.

| # | Ce qui a été fait | Ce que le serveur a répondu | Ce que le CLIENT a affiché |
|---|---|---|---|
| 1 | `nav WAR` | `GetWarsList → WarsList (0 guerres, COPPER, MMR 10)` | écran **GUILD WAR** : `COPPER · RANK #1 · MMR 10`, « Rank up to earn more War Boxes! » |
| 2 | `warqueue QUEUED_SINGLE` | `Action CHANGE_WAR_QUEUE{TYPE=QUEUED_SINGLE}` → `QUEUED_SINGLE [persisté]` | — (fire-and-forget) |
| 3 | `AdminWar --tick --force` | `tour FORCÉ : 1 guerre ouverte` → guerre #1 Baroness Legion **vs** Rival Syndicate, `SABOTAGE` jusqu'au J+2 | — |
| 4 | redémarrage du client | `WarsList (1 guerres…)` | porte de garage **« VS RIVAL SYNDICATE — BAN PHASE — Ends in 11h 58m 46s »**, `COPPER · RANK #2` |
| 5 | tap sur la guerre | `GetWarInfo → WarInfo (guerre #1, état SABOTAGE)` | écran **CURRENT WAR** complet (voir ci-dessous) |
| 6 | WAR LOGS | `RequestWarLogs → WarLogs (0 attaque, 0 défense)` | le **barème** ligne par ligne |
| 7 | ALL LEAGUES | (données locales `war_league_brackets.tab`) | `COPPER 1-199 · BRONZE 200-399 · SILVER 400-599`, **3 boîtes** par ligue |
| 8 | RANKS (COPPER) | `GetWarRankings(COPPER) → WarRankings (2 guildes)` | **Aug 2026** · Rival Syndicate **1ST** (MMR 30) · Baroness Legion **2ND** (MMR 10) |

**Ce que ces captures CONFIRMENT, au-delà du simple « ça rend » :**

1. **La chronologie des phases est la bonne.** Le client affiche « **BAN PHASE — Ends in 11h 58m** » : c'est
   SON `WarHelper.isBanPhase` qui lit notre `extraStateEndTime`, posé par `openWar` à
   `début + SABOTAGE_BAN_PHASE_LENGTH` (12 h). Le décompte est la preuve directe de la valeur envoyée.
2. **Le garage est correctement dérivé.** L'écran CURRENT WAR montre **9 salles réparties sur 3 étages**
   numérotés 3/2/1, exactement `ServerWarCars.GARAGE_ORDER`, avec les **9 voitures** de la guilde adverse.
3. **Le barème de score est le bon.** WAR LOGS affiche `Lineups Defeated ×1`, `Rooms Defeated ×100`,
   `Clean Sweeps ×0`, `Defensive Wins ×0`, `Clean Defenses ×0` — soit `POINTS_PER_LINEUP=1`,
   `POINTS_PER_CAR=100`, et les scalaires liés aux voitures à 0 **parce qu'aucune voiture n'est détenue**
   (`ServerWarScoring.carPointScalar`). Le client réaffiche nos multiplicateurs tels quels.
4. **Les tranches de ligue et le nombre de boîtes** affichés (`1-199 / 200-399 / 400-599`, 3 boîtes) sont
   ceux de `leagueForMMR` et `NUM_SEASON_BOXES`.
5. **La saison se résout en date.** L'écran de classement titre « **Aug 2026** » à partir du `seasonID` 104
   calculé par `ServerWar.seasonIDAt`.
6. **`GuildInfo.warEndTime` était bien indispensable** (correctif de l'étape 2) : sans lui le client
   n'aurait affiché aucune guerre active — l'écran serait resté sur l'état « pas de guerre ».

**Une bizarrerie observée puis EXPLIQUÉE (ce n'est pas un défaut serveur).** Sur l'écran de classement, la
guilde du joueur apparaît **deux fois**. Vérifié au bytecode : `WarRankingsScreen.addPosterContent` ajoute une
ligne « ta guilde » en **en-tête**, et cette ligne est conditionnée par `isYourGuildInRankings()` — c'est-à-dire
qu'elle ne s'affiche **que si** la guilde figure déjà dans `rankingRows`. La liste, elle, est rendue
intégralement par `addRankingsContent`. Le doublon est donc le rendu d'origine ; **retirer notre guilde de
`rankingRows` supprimerait au contraire l'en-tête**. On ne touche à rien (PRINCIPLES §1/§4bis).

**Captures** : `desktop-port/build/war1.png` (écran WARS), `war2.png` (porte de garage, BAN PHASE),
`war3.png` (CURRENT WAR, garage 3×3), `war4.png` (WAR LOGS + barème), `war5.png` (ALL LEAGUES),
`war6.png` (classement COPPER).

### ⚠️ Ce qui reste NON vérifié en jeu

Honnêteté du statut (PRINCIPLES §8) : la vérification ci-dessus couvre **le protocole d'affichage et le cycle
de vie d'une guerre**, pas les actions de jeu. Restent à confirmer en jeu :

| Action | Pourquoi pas encore |
|---|---|
| Placer les **lineups de défense** | une défense de guerre = **15 héros DISTINCTS** ; le compte de test n'en a que **7** |
| **Attaquer** une salle, KO définitif, points marqués | dépend du point précédent (il faut des défenseurs en face) |
| **Sabotages / bans / protections / spars** | exigent des `WAR_TOKENS` et une guilde adverse peuplée |
| **Réclamer une boîte** de promotion / fin de saison | exige une guerre menée à terme (2 jours) ou un forçage d'horloge |
| **Clôture** + variation de MMR + bascule de saison | idem — testé headless (`WarEndTest`, `WarSchedulerTest`), pas en jeu |

Ces points sont couverts par les tests headless ; ils passent en ✅ le jour où un compte à 15 héros et une
guerre menée à terme les exercent pour de vrai.


---

## 5. Vérification EN JEU des ACTIONS (2026-08-02, 2ᵉ session) — 4 défauts RÉELS trouvés

Consigne : « tout vérifier en jeu, sans exception ». Cette session a exercé les ACTIONS (et non plus
seulement l'affichage). Elle a coûté **quatre défauts réels**, dont trois qu'aucun test headless ne pouvait
voir. C'est la meilleure justification possible de PRINCIPLES §8.

### 5.1 🐛 Personne ne participait jamais à une guerre (`ServerWarMembers`)

**Rien ne créait de {@code WarMemberInfo}** : `WarGuildInfo.members` restait vide pour toujours. Conséquences
en chaîne : `assignCar` répondait « ce joueur ne participe pas à cette guerre », aucune attaque n'avait de
cible, et l'écran affichait 0/0 partout. **Les tests headless ne pouvaient pas le voir : ils fabriquaient
eux-mêmes les `WarMemberInfo` qu'ils testaient ensuite.**

Correctif : `ServerWarMembers` construit les membres depuis les lineups `WAR_DEFENSE_1..3` réellement posés
(chemin du jeu `getHeroLineup` → `getHero` → `getHeroSummary`, comme `ServerArena.readDefenseTeam`), à
l'ouverture de la guerre (`openWar`) **et** à chaque `HeroLineupUpdate(WAR_DEFENSE_*)`. L'état de guerre déjà
acquis (héros KO, sabotages) est REPORTÉ héros par héros, apparié par `UnitType` — sinon changer sa défense
effacerait les faits de la guerre en cours.

### 5.2 🐛 `grantHero` inversait ÉTOILES et NIVEAU → client qui plante au hub

Relevé au bytecode : `User.createAndAddHero(type, rarity, i3, i4, …)` transmet `(i3, i4)` à
`CombatHelper.createUnitData`, qui fait `setStars(i3)` puis `setLevel(i4)` — donc **(ÉTOILES, NIVEAU)**.
`ServerUser.grantHero(type, rarity, level, stars)` passait `(level, stars)` : un héros « niveau 40 » recevait
**40 étoiles**. Mesuré en jeu : `HasEnoughCollectionHeroes.isSatisfied` indexe une liste dimensionnée par
`UnitStats.getMaxStars` avec `hero.getStars()` → `IndexOutOfBoundsException: Index 40 out of bounds for
length 7`, levée dans `showDailyQuestMenuDot` au rendu du menu latéral : **le compte devenait injouable**.
`SkillUpgradeTest`/`SkillSetup` produisaient la même corruption sans la détecter (ils n'assertaient pas les
étoiles).

### 5.3 🐛 `StartWarAttackResponse` ne se sérialisait pas → attaque impossible en jeu

`WarDefense.defenders` attend des **`WarHeroData`** (le héros complet, pour le combat), pas les
`WarHeroSummary` de l'état de guerre. Recopier la liste telle quelle **compilait** (dex2jar efface les
génériques) mais levait, à l'écriture sur le fil, `ClassCastException: WarHeroSummary cannot be cast to
WarHeroData` dans `WarHeroData.writeListed`. Le serveur journalisait pourtant « START_WAR_ATTACK … [persisté] » :
**le défaut n'était visible que du côté client, qui ne recevait jamais la réponse.** Un test headless ne
l'attrape pas — il n'écrit pas le message sur le fil. Corrigé dans `ServerWarAttack.toDefense`
(conversion via `ClientNetworkStateConverter.getHeroData`, sabotage reporté).

### 5.4 ⚠️ `createGuild` n'exige pas que le joueur soit sans guilde

Constaté en semant l'adversaire : un membre de « Baroness Legion » a pu créer « Rival Syndicate » **sans
quitter** la première, qui a gardé son identifiant dans son roster. Le serveur autoritatif doit refuser (ou
faire quitter d'abord). **Non corrigé à ce stade — inscrit ici pour ne pas le perdre.**

### 5.5 Ce qui EST vérifié en jeu, action par action

| Action | Message / commande | Résultat en jeu |
|---|---|---|
| S'inscrire en file | `CHANGE_WAR_QUEUE` | ✅ accepté, persisté (rôle RULER validé) |
| Poser sa défense | `HeroLineupUpdate(WAR_DEFENSE_1..3)` | ✅ ×3, persistée, **et resynchronisée dans la guerre** |
| S'affecter à une salle | `ASSIGN_WAR_CAR` | ✅ accepté ; l'écran passe la salle à **3/3** avec l'écusson de défense |
| Voir la défense adverse | `GetWarInfo` | ✅ salle adverse à **0/15 · 3/3** (15 héros, 3 équipes) |
| Saboter un défenseur | `WAR_SABOTAGE_DEFENDER` | ✅ `REDUCE_HP_PERCENT` sur STITCH → **coût 67, palier 1 RECALCULÉ serveur** (l'`INDEX` du client est ignoré : anti-triche vérifié en conditions réelles) |
| Bannir un héros | `WAR_EDIT_BAN_PROTECT` | ⛔ **refusé par le CLIENT** : `WAR_BAN_PROTECT_MAX_PROTECT_SIZE` — la taille de ban/protect vient d'un perk de guilde, et la guilde de test est **niveau 0**. Gate FIDÈLE du jeu, pas un défaut serveur. |
| S'entraîner | `WAR_SPAR_TARGET` | ⛔ **refusé par le CLIENT** : `WAR_SPARS_NOT_ENOUTH` (sic) — quota de spars = perk `WAR_SPARS`, nul au niveau 0. Même conclusion. |
| Attaquer | `START_WAR_ATTACK` | ⚠️ **partiellement vérifié** : la commande atteint le serveur, qui valide et répond (salle `REDUCE_ATTACKER_HP_FLAT`), et le défaut de sérialisation §5.3 est corrigé. Mais après passage en phase ACTIVE, le client n'a plus ré-émis la commande (le pilote la construit, `GameStateManager.startAction` ne l'envoie pas). **Cause non élucidée — à reprendre.** |

### 5.6 Restent NON vérifiés en jeu

* **Attaque menée à son terme** (3 vagues → `WarAttack` → points) — bloquée par le point ci-dessus.
* **Bans / protections / spars** — nécessitent une guilde avec des **perks** (niveau > 0), donc l'économie
  d'influence ; le gate est celui du jeu, les handlers serveur sont couverts headless.
* **Clôture de guerre, MMR, boîtes de promotion/saison, réclamation d'une boîte** — l'outil
  `AdminWar --end` existe pour ramener l'échéance à maintenant, la séquence n'a pas encore été jouée.
* **`GetWarSeasonsList`, `EditGuildWarSettings`, `CHANGE_WAR_TARGET`, `GET_WAR_MEMBER_INFO`,
  `GET_WAR_MOMENTS`, `RECORD_PHONY_WAR_ACTIVITY`, `CLAIM_WAR_BOX_REWARD`** — non exercés.

### 5.7 Outillage ajouté (aucune modification du jeu)

* Pilote DEV : `warqueue`, `wardefense <1|2|3>`, `warassign <CAR>`, `warsabotage <hero> <type>`,
  `warban <hero> [protect]`, `warspar`, `warattack`, `wartarget <CAR>` — tous passent par les **API clientes
  d'origine** (`ClientActionHelper.*`, `WarClientHelper.try*`), jamais par une coordonnée devinée. Les
  commandes `warban`/`warspar` demandent d'abord **son verdict au client** et l'affichent : c'est ainsi que
  les deux refus ci-dessus ont été identifiés comme des gates du jeu et non des silences.
* `server/smoke/WarSetup.java` : héros, jetons de guerre, défense et affectation de salle d'un compte.
* `server/smoke/WarRivalSeed.java` : guilde adverse inscrite en file.
* `AdminWar` : `--status`, `--tick [--force]`, `--resync`, `--advance`, `--end`.
