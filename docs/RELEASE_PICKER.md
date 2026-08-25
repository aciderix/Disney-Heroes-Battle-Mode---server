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
