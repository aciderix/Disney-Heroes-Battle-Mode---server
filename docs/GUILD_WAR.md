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
  `GENERIC_STONES 40`, `GOLD 2 253 330`… ; `getSeasonRewardsPreview(GOLD,1,1,…)` → palier 4.

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
| 3 | Matchmaking (appariement par MMR, anti-rematch, BYE) | ⬜ |
| 4 | Phases (queue → sabotage 24 h / ban 12 h → bataille) + voitures/affectations | ⬜ |
| 5 | Attaques + scoring + logs | ⬜ |
| 6 | Fin de guerre : issue, delta MMR, remboursements, boîtes | ⬜ |
| 7 | Fin de saison : reset MMR (top 10 → Gold, autres ≤ 599), récompenses | ⬜ |

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

**🐛 Défaut trouvé PAR ce test (hors périmètre WAR, suivi à part)** : `UserStore.nextGuildID` lit
`MAX(guildID)+1` **avant** l'insertion. Allouer deux identifiants sans enregistrer entre les deux les
rend identiques — et le handler `CreateGuild` de `LoginServer` a exactement ce motif
(`nextGuildID` puis `createGuild` puis `saveGuild` en trois temps), donc deux créations concurrentes
peuvent collisionner. Les guerres n'y sont pas exposées (`saveWar` alloue sous verrou). À corriger sur
le chemin guilde.
