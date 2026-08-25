# CODEBASE — « The Codebase » (mode de difficulté rotatif)

> Restauration serveur-autoritative du mode **The Codebase** (`GameMode.CODEBASE`). Investigation complète :
> `docs/PHASE2_TRACKING.md` §étape 4bis (classification PARTIALLY RESTORABLE). Ce document = l'implémentation.
> Référence de méthode « mode de difficulté » : `docs/PORT.md` (Codebase en hérite via `DifficultyModeHelper`).

## Ce que c'est (faits, bytecode + `.tab`)

Mode de difficulté **rotatif** : on combat un **méga-virus** (boss) dont la **faiblesse** (`CodebaseWeakness`) et les
**megabits/minor-buffs** changent par itération. On inflige des dégâts → un **score** + un **niveau de rage** ; le score bat
un **high score** (courant d'itération + à vie). Un **classement/journal** par faiblesse liste top scores + attaques récentes.

- **Entrée** : nav **permanente** `Destination.TEAM_TRIALS` → `TeamTrialsChooserScreen` (bouton Codebase,
  `doCodebaseButtonPress`). Les Acts `CodebaseActV1`/`CodebaseIntroActV1` ne sont que le **tutoriel** d'intro.
- **Gating** (déterministe, aucun event opérateur) : `CodebaseHelper.isFeatureEnabled` = `Unlockable.CODEBASE` =
  **chapitre NORMAL 41** terminé (`codebase_constants.tab: REQUIRED_CAMPAIGN_CHAPTER=41`) + team-level requis ;
  `isOpen` = **`true`** (toujours ouvert) ; `getCurrentIterationID(now, shard)` = rotation **3 jours** déterministe
  (`SCHEDULING_EPOCH` + `AVAILABLE_DAYS=3` + `TimeUtil.computeTimeForDay`).
- **`.tab`** : `codebase_constants` (barème/bornes), `codebase_rage_levels` (dégâts→rage), `codebase_minor_buffs`,
  `codebase_iterations` (WEAKNESS_OVERRIDE). Toutes chargées par `StatFileHelper` (aucune valeur en dur, §4).

## Combat client-autoritatif (comme campagne/trials/surge)

Le client joue le combat (`CodebaseAttackScreen`) et envoie **`CodebaseAttack`** (fire-and-forget) :
`{base:AttackBase, codebaseID(=itération), weakness:CodebaseWeakness, minorBuffs, finalWeaknessCount, finalScore,
megavirusTotalDamageTaken, attackEndTime, lootEarned}`. Le serveur **ré-exécute la logique du jeu** (§3).

**Point d'entrée serveur** = `CodebaseHelper.recordOutcome(user, outcome, finalScore, rageLevel, lootEarned, attackers,
defenders, codebaseID, attackEndTime, snapshot)` — mappage relevé au **bytecode du vrai appelant**
`CodebaseAttackScreen.handleBattleOutcome`. Il fait TOUT (anti-triche + crédit + high scores + hook contest) :

| Garde (ordre) | `ClientErrorCode` si échec |
|---|---|
| `Unlockables.isUnlocked(CODEBASE)` (chapitre 41) | `GAME_MODE_LOCKED` |
| `isOpen` | `GAME_MODE_NOT_OPEN` |
| ≥1 **héros JAUNE** dans les attaquants (`getHero(type).getRarity() ≥ YELLOW`) | `CODEBASE_REQUIRES_YELLOW_HERO` |
| quota quotidien (`DailyActivityHelper`, `BASE_CHANCES=3`) | `GAME_MODE_CHANCES_GONE` |
| cooldown `CODEBASE_ATTACK` | `GAME_MODE_COOLDOWN` |

Puis : `RewardHelper.giveRewards` (butin client-reporté), `recordDailyUse`, `setCooldownEnd`, **`tryUpdateHighScores`**,
`ContestHelper.onDifficultyModeAttack`, `UserActivityTracker`.

**Détails de fidélité** (§4, jamais inventés) :
- `attackers`/`defenders` passés **tels quels** (`List<AttackLineupSummary>`) — `recordOutcome` aplatit lui-même les units.
- `rageLevel` **non porté** par le message (calculé en combat) → reconstruit par la formule DU JEU
  `CodebaseStats.getRageLevelFromDamageDealt(megavirusTotalDamageTaken)` (table `codebase_rage_levels.tab`).

## Persistance

- **Progression per-user = write-through §3, AUTO-persistée.** `IndividualUser.setCurrentCodebaseHighScore` etc. écrivent dans
  `IndividualUserExtra.{current,lifetime}Codebase{HighScore,HighRageLevel}` + `currentCodebaseID` (déjà dans le wire/DB). Aucune
  nouvelle colonne.
- **Classement/journal = blob SERVEUR-AUTORITATIF per-shard** (le seul état sans builder client), patron Arena ladder / Invasion
  ranking : `CodebaseAttackLogs{logs: Map<CodebaseWeakness, CodebaseAttackLog{topScores, recent}>}` de
  `CodebaseAttackInfo{lineup:List<HeroSummary>, rageLevel, score, attackTime}`. **Clé = la FAIBLESSE** (l'écran de journal
  affiche les tops de la faiblesse courante), pas l'itération. Stocké via `UserStore.loadShardState/saveShardState(shardID,
  "codebase_logs")` (round-trip wire). Bornes lues des `.tab` (`ATTACK_LOG_MAX_TOP_ROWS/RECENT_ROWS=10`).

## Code (glue serveur — aucune règle réécrite)

- **`dhserver.ServerCodebase`** : `currentIteration`, `loadLogs`/`saveLogs` (blob per-shard), `lineupOf` (roster CODEBASE →
  `HeroSummary` via `ClientNetworkStateConverter.getHeroSummary`), `recordAttack` (insère dans top trié↓/borné + recent
  en-tête/borné, par faiblesse).
- **`dhserver.ServerUser.recordCodebaseAttack(CodebaseAttack)`** : rebuild user/iu, bind, `ServerContestData.prepare` →
  `CodebaseHelper.recordOutcome` → `deliver` + resync ; renvoie l'entrée de journal calculée. (+ `grantCampaignLevel` = support
  test/admin pour amener un compte au chapitre requis.)
- **`dhserver.LoginServer`** : handler `CodebaseAttack` (→ `recordCodebaseAttack` + `ServerCodebase.recordAttack` + `store.save`) ;
  handler `GetCodebaseAttackLogs` (→ blob per-shard, débloque `CodebaseAttackLogScreen`).

## Vérification

- **Headless** : `server/smoke/CodebaseTest.java` (régression) — classement (tri↓/bornes/round-trip/DB) ; anti-triche RÉELLE
  (compte verrouillé → `GAME_MODE_LOCKED`) ; chemin nominal (chapitre 41 + héros jaune → high score mis à jour + persisté). 🟢
- **EN JEU (§8)** ✅ **VÉRIFIÉ** (incr. 2) : compte débloqué (`CodebaseUnlock` : TL 300 + chapitre 41 + 5 héros JAUNE) → client
  réel → pilote `codebaseattack` envoie le VRAI `CodebaseAttack` (iter=-1030, weakness=BLIND, attaquant RALPH/YELLOW réel du
  roster). Serveur : **1re attaque ACCEPTÉE** (`recordOutcome + classement [persisté]`), **2e REFUSÉE `GAME_MODE_COOLDOWN`**
  (anti-triche réelle). Persistance relue en DB après arrêt : `currentCodebaseHighScore=500`, `lifetimeCodebaseHighScore=500`,
  `currentCodebaseID=-1030` ; classement per-shard = 1 faiblesse (BLIND) top=1/recent=1 topScore=500. ⇒ mode restauré de bout en
  bout (client → serveur → persistance).
