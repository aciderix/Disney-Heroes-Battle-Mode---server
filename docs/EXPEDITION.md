# EXPEDITION (#72 mode suivant) — mode « Expédition » — suivi d'implémentation

> Attaqué au pipeline industrialisé #73/#74 (`contract.sh --mode Expedition` + `ModeGraph --logic`), comme
> SURGE/CHALLENGES/FRIENDSHIPS. Chaque incrément : contrat → logique du jeu (§3) → test headless (WireCheck +
> ClientOracle) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon pipeline + bytecode)

Mode **SOLO de progression + combat**, gaté **`Unlockable.EXPEDITION` = TL 25** (`unlockables.tab`). Une **expédition**
= une suite de **nœuds** à une **difficulté** donnée : on choisit une difficulté, on affronte les nœuds l'un après
l'autre (combat client-autoritatif) OU on **raid** (saute le combat), en gagnant or/tickets/récompenses/epic chips, avec
des **wards hebdomadaires** (modificateurs de combat qui tournent chaque semaine) et une **remise à zéro** (reset).

Écran principal **`ExpeditionScreen`** (Destination **`EXPEDITION`** + **`EXPEDITION_DIFFICULTY`**). Fenêtres :
`ExpeditionDifficultyWindowV2`, `ExpeditionConfirmRaidWindow`, `ExpeditionCompleteWindow`, `ExpeditionDefeatWindow`,
`ExpeditionRewardsWindow`, `ExpeditionVictoryRetryWindow`. Hero choosers : `ExpeditionHeroChooserScreen`,
`RaidExpeditionHeroChooserScreen`. Combat : `ExpeditionAttackScreen`.

### Messages (client ↔ serveur)
- **`GetExpedition`** (requête, vide) → **`GetExpeditionResponse{ currentExpedition:ExpeditionRunData, expeditionID:long,
  wasReset:boolean, weeklyWardInfo:ExpeditionWeeklyInfo }`** — patron `GetSurge`. **[MANQUE] handler `LoginServer`.**
- **`ExpeditionAttack{ base:AttackBase, attackerHeroes, defenderHeroes, nodeIndex, epicChips:EpicChipDrop, acceptBattle }`**
  — issue d'un combat de nœud (client-autoritatif → serveur re-exécute, patron `CampaignAttack`/`FriendshipCampaignAttack`).
- **`ExpeditionRaid`** — raid (saute le combat, `doRaidFromClient`).
- **`ResetExpedition`** — remise à zéro du run (économie `chargeForReset`, resets hebdo).

### Données / état
- **`ExpeditionRunData`** (le run) : `difficulty`, `defenders` (lineups des nœuds), `nodesDefeated`, `chestsOpened`,
  `nodeRewards` (List<NodeReward{rewardDrops}>), `ticketsEarned`, `totalGoldEarned`, `droppedEpicChips`, `weeklyWards`.
- **`ExpeditionWeeklyInfo`** : `currentWards` (List<CombatModifier>), `currentWardExpiration`, `nextWards`,
  `nextWardStartTime` — **wards hebdomadaires** (modificateurs de combat rotatifs, calendrier à calculer serveur).
- **Persistance** : seul **`individualUserExtra.expeditionID`** (long) vit dans l'extra ; **le RUN (`ExpeditionRunData`)
  est un ÉTAT SERVEUR-AUTORITATIF** (généré/suivi côté serveur — le builder n'est PAS dans le jar client, comme
  `ArenaInfo`/Surge). ⇒ persistance dédiée à décider (blob par joueur type CHALLENGES, ou shard_state).

### Logique du jeu (§3 — points d'entrée à EXÉCUTER, `ExpeditionHelper` statiques IUser)
`doRaidFromClient(user, difficulty, snap)` · `doRaid(...)` · `giveLoot(user, NodeReward, …)` · `createRewards(user)` ·
`openChest(user, snap, …)` · `chargeForReset(user, diff, snap, bool)` · `getResetsRemaining` · `enableDifficulty` ·
`getMaxDifficulty`/`getMaxEnabledDifficulty`/`isDifficultyAvailable`/`isDifficultyRaidable` · `getRaidCost` ·
`getEpicKeyCost` · `getMaxRaidTickets`/`getRaidTicketReward`/`giveRaidTickets` · `getMinHeroLevel` ·
`calculateNumChipsEpicRaid`/`rollEpicChipsForRound`/`selectRedHero`/`unitQualifiesForEpicReward` ·
`getNextWardsFor(weeklyInfo, diff)`. **Génération des nœuds/defenders** : à établir (ExpeditionStats +
`expedition_*.tab` ; probablement backend → à générer serveur comme Surge/Arena).

## Plan d'incréments (prévisionnel, à affiner)
1. ⬜ **Boot / rendu** : gate `EXPEDITION` + handler `GetExpedition`→`GetExpeditionResponse` pour un état **FRAIS**
   (aucun run actif : `currentExpedition=null`, `expeditionID` persisté, `weeklyWardInfo` non-null sûr) → l'écran s'ouvre
   sans NPE + sélection de difficulté. `ExpeditionBootTest` (WireCheck). **Vérif en jeu : `nav EXPEDITION` rend.**
2. ⬜ **Génération du run + difficulté** : `enableDifficulty` + génération serveur de `ExpeditionRunData` (nœuds/
   defenders depuis `ExpeditionStats`/`.tab`) pour la difficulté choisie ; persistance du run.
3. ⬜ **Combat de nœud** : `ExpeditionAttack` → re-exécution autoritative (patron `recordOutcome`) → progression
   (`nodesDefeated`), récompenses de nœud (`giveLoot`/`NodeReward`), epic chips ; persistance.
4. ⬜ **Raid** : `ExpeditionRaid`/`doRaidFromClient` (saute le combat, débit coût, `getRaidTicketReward`).
5. ⬜ **Wards hebdomadaires** : `ExpeditionWeeklyInfo` (currentWards/nextWards + calendrier de rotation) via les stats.
6. ⬜ **Reset** : `ResetExpedition` (`chargeForReset`, `getResetsRemaining`, resets hebdo).
7. ⬜ **Récompenses / coffres** : `createRewards`/`openChest` (coffres d'expédition, epic chips → héros).
8. ⬜ **Vérif EN JEU complète** (solo, compte TL100) : difficulté → combat → récompenses → raid → reset.

## Notes §3/§4
- Combat client-autoritatif (comme campagne/amitié) : loot d'objets = client-reporté (PARTIEL §4bis/#25) ; or/tickets/
  progression/epic chips = calculés SERVEUR via `ExpeditionHelper`. Zéro invention (§4) : tout des `expedition_*.tab`.
- Le run est un état serveur (pas dans l'extra) → persistance dédiée (à trancher en incr. 2).
