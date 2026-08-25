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

### b) `patched_heroes*` — 2 `TimeTable` (via `serverTimeNow`)
`PatchStats$FranchiseSeasonMappingStats` + `PatchStats$GameModeFranchisesMappingStats` : le **mapping franchise ↔ saison**
et **franchises par mode** utilisés par les Trials. Datés → suivent aussi l'heure serveur.

## 3. Ce que le choix de release NE gouverne PAS (snapshot unique de l'APK)

**Toutes les autres ~270 `.tab` ne sont PAS versionnées dans le temps** : ce sont un **snapshot unique**, celui de l'APK
installé (la dernière version). Le release-picker **ne les touche pas**. En particulier **NE changent PAS** avec la release :

- Équilibrage & entités : `unit_stats`, `unit_abilities(.tabb)`, `skills`, `gear`/`mods`/`real_gear`, `enchanting`, `chests`,
  `campaign` (layout des niveaux), prix/coûts, tables de drop, `codebase_*`, etc.

⇒ **Conséquence clé (fidélité, §4bis)** : choisir R50 met l'ère « comme en mai 2022 » pour les **caps, disponibilités, rosters
et drops datés** (ce que `ContentStats` porte historiquement), mais **NE remet PAS** les *nombres d'équilibrage* (stats des
héros, dégâts des skills, coûts…) à leur valeur de 2022 — ils restent ceux de l'APK courant. Une « vraie » rétro-version
complète du build n'existe pas dans les données : seul `content.<shard>.tab` (+ patched_heroes) est un historique daté.

## 4. Dépendance cachée (couplage horloge) — documentée étape 3

Release = offset d'horloge (via `serverTime`). Or `BootData.serverTime` pilote À LA FOIS le contenu daté ET l'**affichage des
timers** côté client. Déplacer l'ère **décale donc aussi l'horloge perçue** (timers d'événements, cooldowns affichés) et la
**saison** (sauf ancre `AdminSeason` posée séparément — cf. `docs/PHASE2_TRACKING.md` étape 3). Compromis assumé d'`AdminClock` ;
un découplage contenu↔timers exigerait de modifier le client (hors §1).

## 4bis. Intention ADMIN — ce que l'opérateur peut vraiment régler (faits vérifiés)

Deux usages admin distincts sont souvent confondus ; ils passent par des leviers **différents** :

### A) « Définir qu'on est à telle ère » → PLAFONDS de progression — via `AdminRelease` ✅
**VÉRIFIÉ** : une ère (colonne `content.<shard>.tab`) porte bien des **plafonds** que le choix de release applique réellement :
`maxTeamLevel` (R1=50 → R102=565), `maxRarity`, `maxGearRarity`, `maxGuildLevel`, `maxChapter`, `portDifficultyCap`,
`trialsDifficultyCap`, `invasionMaxTeamLevel`/`invasionRarity`. ⇒ **Oui : au début (R1…) on démarrait à des paliers plus bas**, et
`AdminRelease --set-release Rxx` les rétablit fidèlement.

**MAIS — VÉRIFIÉ aussi : l'ère NE contrôle PAS l'échelle des NOMBRES** (ni cap de ressources, ni magnitude des gains/coûts).
Aucun champ « ressources/monnaie/cap » dans `ContentColumn` ; l'accumulation (« des milliards ») vient des `.tab` d'ÉQUILIBRAGE
(gains/coûts), qui sont le **snapshot unique de l'APK courant**. Donc choisir R1 donne des **plafonds bas** mais des **nombres à
l'échelle actuelle** — on ne peut pas « revenir aux petits chiffres du début » sans données d'équilibrage historiques (absentes de
l'APK). C'est la frontière de fidélité (§2/§3 ci-dessus).

- *Granularité alternative* : le jeu expose un **offset de contenu PAR JOUEUR** (`ContentStats.setUserOffset(user, offset)`) →
  décaler l'ère d'un seul compte sans toucher l'horloge globale. Utile pour tester, MAIS même caveat d'affichage client que §4
  (le client date le contenu via `BootData.serverTime`) → à réserver au serveur, à valider avant tout usage joueur.

### B) « Définir telle ère pour la gestion PRÉCISE des événements » → c'est `AdminEvents`, PAS le release-picker
**VÉRIFIÉ** : les **événements live-ops** (rotations, coffres bonus, contests, remises…) sont déjà pilotés par la **couche
opérateur `AdminEvents`** (overrides `SpecialEvents` avec fenêtres `start`/`end` explicites), **indépendante de l'ère de contenu**.
C'est LE bon outil pour « gérer précisément les événements » — inutile de déplacer l'ère pour ça.

Sur les **timers (reset)** : **VÉRIFIÉ** qu'ils sont **serveur-autoritatifs** — calculés depuis `TimeUtil.serverTimeNow()` +
`computeTimeForDay` (fuseau serveur), donc **découplés de l'horloge de l'APPAREIL du joueur** (uniformes pour tous, pas la « montre
locale » de chacun). ✅ C'est bien ce que tu pensais. **Nuance importante** : ils ne sont PAS découplés de l'**ère** — reset et
fenêtres d'événements lisent le MÊME `serverTimeNow()` que l'ère. Donc **déplacer l'ère (release-picker = offset d'horloge) décale
aussi ces timers** (cf. §4). ⇒ Règle : pour l'**ère/plafonds** → `AdminRelease` ; pour la **gestion fine des événements** →
`AdminEvents` (sans bouger l'ère). Un vrai découplage ère↔timers exigerait de modifier le client (hors §1).

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
