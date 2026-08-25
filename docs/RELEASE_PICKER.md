# RELEASE PICKER — choix de l'ère de contenu (chantier D)

> Outil admin `AdminRelease` : choisir l'**ère de contenu** par NOM DE RELEASE (R1…R102) au lieu d'une date brute.
> Wrapper mince d'`AdminClock` (cf. `docs/PHASE2_TRACKING.md` étape 3). **Ce document DÉFINIT précisément ce que le choix de
> release gouverne — et ce qu'il NE gouverne PAS — face aux `.tab`.**

## 1. Ce qu'est une « release »

Une release = une **colonne temporelle** d'une `.tab` de type `TimeTable`, sélectionnée par l'heure serveur
(`serverTimeNow()`). Choisir une release = **poser l'heure de jeu** à la date de début de cette colonne (ancre d'horloge
persistée `clock_offset_ms`, la MÊME qu'`AdminClock`). La logique datée du jeu suit alors (§2, aucune règle contournée).

`content.<shard>.tab` compte **372 colonnes** (R1 = 2016-09-06 / Max TL 50 → R102 = 2026-04-20 / Max TL 565).

## 2. Ce que le choix de release GOUVERNE (tabs TIME-VERSIONNÉES)

Seules **les `.tab` de type `TimeTable`** ont un axe temporel → seules elles changent quand on déplace l'heure serveur :

### a) `content.<shard>.tab` — `ContentStats` / `ContentColumn` (l'ÈRE, 38 dimensions)
Chaque colonne expose l'état « live-ops » de l'époque. Regroupées :

- **Caps de progression** : `getMaxTeamLevel`, `getMaxRarity`, `getMaxGearRarity`, `getMaxGuildLevel`,
  `getNumChaptersAvailable`, `getPortDifficultyCap`, `getTrialDifficultyCap`, `getInvasionMaxTeamLevel`, `getInvasionRarity`.
- **Disponibilité / dating des sorties** : `isHeroAvailableAtTime`, `isItemReleasedAtTime`, `getAvailableHeroes`,
  `getNewHeroes`, `getNonExclusiveHeroes`, `getHeroRecency`.
- **Rosters rotatifs** (quels héros apparaissent où) : `getArena/Coliseum/Expedition/Heist/Surge/War MerchantHeroes`,
  `getArena/Coliseum WeeklyHeroes`, `getEventHeroes`, `getBattlePassExclusiveHeroes`, `getGoldChestExclusiveHeroes`,
  `getSecondGoldChestHeroes`, `getSocialChestHeroes`, `getSoulChestDailyHeroes`, `getCurrentSoulChestSpecialHero`,
  `getCurrent/NextMonthlySigninHero`, `getPrizeWallHero`, `getSigninVersion`.
- **Drops de pierres d'âme datés** : `getCampaignSoulStone`, `getCampaignStoneDrops`, `getLevelsWithStone`.
- **Versioning** : `getContentUpdate` / `getInvasionContentUpdate` / `getMapping`.

### b) `patched_heroes*` — 2 `TimeTable`
`PatchStats$FranchiseSeasonMappingStats` + `PatchStats$GameModeFranchisesMappingStats` : le **mapping franchise ↔ saison**
et **franchises par mode** utilisés par les Trials. Datés → suivent l'offset d'ère (le client applique aussi
`contentStatsTimeOffset` à `PatchStats.debugSetUserOffset`).

### c) Valeurs de `.tab` INDEXÉES PAR RELEASE (`ContentUpdate`) — **13 getters**
Certaines valeurs d'équilibrage sont **paramétrées par la release** (colonne `ContentUpdate` de l'ère courante) → elles
**changent avec l'ère**. Les principales : **stamina** (`StaminaStats.getHardCap` / `getRegenAmount` / `getRegenInterval` /
`getBuyAmount` / `getDailyCheckIn` / `getStaminaRow`), `getMaxChest`, `getSupplyPackageMaxLevel`, `getWeeklyQuestRewardPerTier`,
`getMaxStarsForRelease`, `getMaxGearRarity`. ⇒ p. ex. **le plafond de stamina EST plus bas aux premières ères** (répond au « on
n'accumulait peut-être pas autant au début » : vrai pour ces axes-là).

## 3. Ce que le choix de release NE gouverne PAS (snapshot unique de l'APK)

**La grande majorité des `.tab` ne sont PAS versionnées dans le temps** : ce sont un **snapshot unique**, celui de l'APK
installé (dernière version). Le release-picker **ne les touche pas**. En particulier **NE changent PAS** avec la release :

- Équilibrage & entités : `unit_stats`, `unit_abilities(.tabb)`, `skills`, `gear`/`mods`/`real_gear`, `enchanting`, `chests`,
  `campaign` (layout des niveaux), la plupart des prix/coûts, tables de drop, `codebase_*`, etc.

⇒ **Conséquence clé (fidélité, §4bis)** : choisir R50 met l'ère « comme en mai 2022 » pour les **caps, disponibilités, rosters,
drops datés ET les ~13 valeurs indexées par release** (§2c : stamina, weekly quest rewards…). Mais **NE remet PAS** les *nombres
d'équilibrage NON indexés* (stats des héros, dégâts des skills, coûts de la plupart des tabs) à leur valeur de 2022 — ils restent
ceux de l'APK courant. Une rétro-version *complète* du build n'existe pas dans les données : seuls `content.<shard>.tab`,
`patched_heroes` et les colonnes `ContentUpdate` portent un historique daté.

## 4. Découplage ère ↔ timers — RÉSOLU (correctif 2026-08-25)

**L'ancienne conclusion « on ne peut pas découpler sans modifier le client » (étape 3) était FAUSSE.** Le jeu/le client
supportent nativement un **canal de contenu séparé** : `BootData.contentStatsTimeOffset` (distinct de `serverTime`). Au boot,
`GameMain` lit ce champ et applique `ContentStats.setUserOffset(user, offset)` + `PatchStats.debugSetUserOffset` → le client
résout **son contenu daté** par `serverTimeNow() + offset` (ère), MAIS garde ses **timers** (resets, cooldowns, régén,
horodatages de sauvegarde) sur `serverTimeNow()` **BRUT**.

⇒ **`AdminRelease` règle donc l'offset d'ÈRE DÉCOUPLÉ** (`ServerContext.setContentOffsetMillis` → méta `content_offset_ms` →
`bootData().contentStatsTimeOffset`), PAS l'horloge. **Changer d'ère ne casse NI les sauvegardes NI les timers.** (Si on veut au
contraire déplacer *tout* le monde dans le temps, timers compris, c'est `AdminClock` — outil distinct.) Prouvé : `ReleaseOffsetTest`
(régression) — `contentStatsTimeOffset` reflète l'ère, `serverTime`/`serverTimeNow()` restent au présent.

## 4bis. Intention ADMIN — ce que l'opérateur peut vraiment régler (faits vérifiés)

Deux usages admin distincts sont souvent confondus ; ils passent par des leviers **différents** :

### A) « Définir qu'on est à telle ère » → PLAFONDS de progression — via `AdminRelease` ✅
**VÉRIFIÉ** : une ère (colonne `content.<shard>.tab`) porte bien des **plafonds** que le choix de release applique réellement :
`maxTeamLevel` (R1=50 → R102=565), `maxRarity`, `maxGearRarity`, `maxGuildLevel`, `maxChapter`, `portDifficultyCap`,
`trialsDifficultyCap`, `invasionMaxTeamLevel`/`invasionRarity`. ⇒ **Oui : au début (R1…) on démarrait à des paliers plus bas**, et
`AdminRelease --set-release Rxx` les rétablit fidèlement.

**Nuance sur les NOMBRES** : l'ère contrôle DEUX sortes de valeurs : (i) les **caps** de `ContentColumn` (ci-dessus), et (ii) les
**~13 valeurs indexées par release** (§2c — dont la **stamina** : cap/régén/achat, plus max chest, weekly quest rewards…), qui
**sont bien plus basses aux premières ères**. En revanche l'ère **ne touche PAS** les nombres NON indexés (stats héros, dégâts
skills, la plupart des coûts) = snapshot APK courant. Donc R1 = plafonds + stamina + quelques récompenses « du début », mais le
gros de l'équilibrage reste à l'échelle actuelle (pas de rétro-version complète sans données historiques absentes de l'APK).

- *Granularité alternative* : le jeu expose un **offset de contenu PAR JOUEUR** (`ContentStats.setUserOffset(user, offset)`) →
  décaler l'ère d'un seul compte sans toucher l'horloge globale. Utile pour tester, MAIS même caveat d'affichage client que §4
  (le client date le contenu via `BootData.serverTime`) → à réserver au serveur, à valider avant tout usage joueur.

### B) « Définir telle ère pour la gestion PRÉCISE des événements » → c'est `AdminEvents`, PAS le release-picker
**VÉRIFIÉ** : les **événements live-ops** (rotations, coffres bonus, contests, remises…) sont déjà pilotés par la **couche
opérateur `AdminEvents`** (overrides `SpecialEvents` avec fenêtres `start`/`end` explicites), **indépendante de l'ère de contenu**.
C'est LE bon outil pour « gérer précisément les événements » — inutile de déplacer l'ère pour ça.

Sur les **timers (reset)** : **VÉRIFIÉ** qu'ils sont **serveur-autoritatifs** — calculés depuis `TimeUtil.serverTimeNow()` +
`computeTimeForDay` (fuseau serveur), donc **découplés de l'horloge de l'APPAREIL du joueur** (uniformes pour tous, pas la « montre
locale » de chacun). ✅ C'est bien ce que tu pensais. **Et depuis le correctif §4, ils sont AUSSI découplés de l'ère** :
`AdminRelease` déplace l'ère via `contentStatsTimeOffset` (contenu seulement), pas via l'horloge → **les timers ne bougent pas**
quand on change de release. ⇒ Règle : **ère/plafonds/stamina** → `AdminRelease` (timers préservés) ; **gestion fine des
événements** → `AdminEvents` (indépendant de l'ère) ; **déplacer TOUT le monde dans le temps** (timers compris) → `AdminClock`.

## 5. Outil `AdminRelease`

```
AdminRelease [--db …] [--shard 1] --list                    # liste les releases : nom, date, Max TL
AdminRelease [--db …] [--shard 1] --status                  # release courante
AdminRelease [--db …] [--shard 1] --set-release <nom|#idx>  # ancre l'horloge sur cette release (ex. R50, #12)
AdminRelease [--db …] --reset                               # heure réelle → release courante réelle
```
Persiste `clock_offset_ms` (méta DB, ré-appliqué au boot par `LoginServer`) → **redémarrer le serveur** après un changement.
Vérifié : `--set-release R50` → heure de jeu 2022-05-17, release R50 Max TL 305 (persisté, relu en process neuf) ; `--reset` →
2026 réel R102 Max TL 565.
