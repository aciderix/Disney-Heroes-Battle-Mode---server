# PORT (#72 mode suivant) — « Port / Docks & Entrepôt » (PvE planifié à butin) — suivi

> Pipeline #73/#74 (recon bytecode). Chaque incrément : recon → logique du jeu (§3) → test headless (round-trip + DB)
> → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon bytecode, g118)

Le **Port** est un mode **PvE planifié à butin**, membre du **sous-système générique `DifficultyModeHelper`** (mêmes
rouages que d'autres modes « difficulty » : HEIST, etc.). Deux `GameMode` : **`PORT_DOCKS`** (jours ouverts 6,4,2,1) et
**`PORT_WAREHOUSE`** (7,5,3,1). On choisit une **difficulté** (`ModeDifficulty`), on combat des étages (enemies/loot des
`.tab`), le butin est crédité, avec un **cooldown** entre attaques et un **reset** payant, plus une **récompense double**
(`CLAIM_DOUBLE_PORT_REWARDS`). Combat CLIENT-autoritatif (le client joue → envoie l'issue → serveur ré-exécute).

### ⚠️ C'est un SOUS-SYSTÈME GÉNÉRIQUE (DifficultyModeHelper)
Implémenter PORT via `DifficultyModeHelper` couvre TOUT le sous-système difficulty (PORT_DOCKS/WAREHOUSE + siblings). Le
serveur ne le gère PAS encore (aucun `DifficultyModeAttack`/`recordOutcome` difficulty côté serveur).

### Wire (messages)
- **`DifficultyModeAttack{ base:AttackBase, gameMode:GameMode, modeDifficulty:int, lootEarned:List<RewardDrop>, stagesCleared:int, attackEndTime:long }`** (client→serveur, combat ; fire-and-forget façon `CampaignAttack`).
- **`RaidDifficultyMode{ gameMode, modeDifficulty:int, outcomes:List, raidTime, specialEvents }`** (client→serveur, raid).
- **`Action CLAIM_DOUBLE_PORT_REWARDS`** (émetteur `ClientActionHelper.claimDoublePortRewards`) → double récompense.

### Logique du jeu (§3 — points d'entrée à EXÉCUTER)
- **`DifficultyModeHelper.recordOutcome(user, GameMode, ModeDifficulty, CombatOutcome, stagesCleared:int, Collection, Collection, Collection, long, snapshot)`** : ré-exécute l'issue (avance/uses/cooldown + crédit).
- **`DifficultyModeHelper.recordRaidOutcome(user, GameMode, ModeDifficulty, int, Collection, long, snapshot)`** (raid).
- **`DifficultyModeHelper.rollLoot(user, GameMode, ModeDifficulty, snapshot)`** → butin (drop table `port_docks_loot.tab`).
- **`DifficultyModeHelper.claimDoubleRewards(user, snapshot)`** → récompense double.
- Planning/état : `isOpen(mode,user,snap)`, `getOpenDays`, `getNextOpenDay`, `gameModeOffCooldown`, `getCooldownType`/`getCooldownDuration`, `hasChallengeChances`, `isResetAvailable`, `getHighestAvailableDifficulty`, `getStageEnemies`/`getEnemyLevel`/`getEnemyRarity`/`getEnemyStars` (config ennemis), `getExpReward`, `getPossibleLoot`. `isPortMode(mode)`, `PortHelper.hasAttacksAvailable`.

### Données / état & persistance
- **Cooldown** d'attaque : `CooldownType` (`getCooldownType(mode)`) → write-through `individualUserExtra.cooldowns` (comme les cooldowns arène/lineup). **Uses/quota** : `getUseKey`/`getResetUseKey` (compteurs `DailyActivity`/`UserFlag` → resyncCounts). Butin (héros/objets/ressources) : write-through / resync* selon le type (comme campagne/surge).
- Pas de blob dédié a priori (contrairement à MERCHANT) : l'état = cooldowns + compteurs + inventaire, déjà persistés.
- Planning basé sur le jour serveur (open days) — horloge serveur ancrée.

## Plan d'incréments
1. ✅ **COMBAT (`DifficultyModeAttack` → `DifficultyModeHelper.recordOutcome`)** : handler `LoginServer` →
   `ServerUser.recordDifficultyModeAttack` (convertit `modeDifficulty` int→`ModeDifficulty.get`, passe
   `base.outcome`/`stagesCleared`/`lootEarned`/`base.attackers`/`base.defenders`), crédite (`giveLoot`) + pose cooldown +
   persiste. Test `PortAttackTest` (WIN → +5000 GOLD + cooldown `PORT_DOCKS_ATTACK`, round-trip + DB). **✅ VÉRIFIÉ EN JEU**
   (id=1) : pilote `portattack PORT_DOCKS` (envoie le VRAI `DifficultyModeAttack` via `getNetworkProvider().sendMessage`)
   → serveur `recordOutcome appliqué [persisté]` → **DB GOLD +5000 + cooldown posé** ; attaque répétée →
   **`DifficultyModeAttack REFUSÉ (anti-triche) : GAME_MODE_COOLDOWN`** (cooldown anti-triche OK). Pilote `portattack <MODE>`.
   - **Correctif PatchStats (g118)** : le 1er combat en jeu poisonnait `PatchStats.<clinit>` (`doChecks` →
     `getMaxDailyUsesRaw` → parse paresseux de `patched_heroes_talent_assignments.tab` ; ligne EVIL_QUEEN /
     `PREDICTIVE_FORTIFICATION` absent de l'enum → parse non ré-entrant poisonné sous accès concurrent). **Warm-up
     mono-thread ajouté dans `ServerContext.init`** (comme GuildStats) : force le `<clinit>` UNE fois (cellule fautive
     absorbée) → chargé proprement → combat PORT OK en jeu. Cf. `docs/SHIMS.md`.
2. ✅ **RAID (`RaidDifficultyMode` → `useRaidTickets` + `recordRaidOutcome`)** : handler `LoginServer` →
   `ServerUser.recordRaidDifficultyMode`. Le client construit `RaidDifficultyMode{outcomes:List<RaidOutcome{expEarned,
   loot}>, raidTime}` (un `RaidOutcome` par raid, loot roulé par le client via `rollLoot`) ; le serveur ré-exécute, dans
   l'ordre du client (miroir `RaidTicketOutcomeWindow`) : (1) `DifficultyModeHelper.useRaidTickets(user, mode, diff,
   raidCount, snap)` = `doChecks` (ouvert/cooldown/quota) + gate `isAutoAttackAvailable` (3★ → sinon `NEEDS_THREE_STARS`)
   + gate **VIP `getRaidFeature(PORT)=RAID_PORT`** (sinon `FEATURE_NOT_UNLOCKED`) + débit `RAID_TICKET` **sauf** VIP
   `RAID_WITHOUT_TICKETS` ; (2) `recordRaidOutcome(user, mode, diff, raidCount, loot, raidTime, snap)` = crédit du butin
   agrégé (`giveLoot`), XP×raidCount, `recordDailyUse`×raidCount + compteur `port_any`, **pose cooldown** + tracker.
   `raidCount = outcomes.size()` ; `loot = merge(outcomes[i].loot)` (client-reporté §4bis/#25, comme campagne/expédition).
   - **FAIT §4 (gate VIP)** : `getRaidFeature(PORT_DOCKS/WAREHOUSE) = RAID_PORT` débloqué au **VIP 4** ; or
     `RAID_WITHOUT_TICKETS` s'active dès le **VIP 3** → **tout raid PORT LÉGITIME (VIP 4+) est SANS ticket** (le cas
     `NOT_ENOUGH_RAID_TICKETS` est inatteignable pour PORT). Le raid PORT est donc VIP-gaté (VIP 4) et gratuit en tickets.
   - Accesseur `ServerUser.gameIndividual()` ajouté (IndividualUser vivant, write-through) pour poser `setDifficultyModeStars`.
   Test `PortRaidTest` (mode ouvert du jour ; 3★ + VIP4 ; RAID ×3 → +15000 GOLD, tickets inchangés, cooldown posé ;
   re-raid pendant cooldown → `GAME_MODE_COOLDOWN` refusé, état inchangé ; round-trip wire + DB). **✅ VÉRIFIÉ EN JEU**
   (id=1) : outil DEV `PortRaidAdmin` (VIP4 + PORT 3★ + cooldowns purgés) → pilote `portraid PORT_DOCKS 3` (VRAI
   `RaidDifficultyMode` via `getNetworkProvider().sendMessage`) → serveur `recordRaidOutcome appliqué [persisté]` →
   **DB GOLD +15000 + cooldown posé** ; re-raid → **REFUSÉ `GAME_MODE_COOLDOWN`**. Pilote `portraid <MODE> [raids]`.
3. ⬜ **RÉCOMPENSE DOUBLE (`Action CLAIM_DOUBLE_PORT_REWARDS` → `claimDoubleRewards`)** : anti-triche (dispo/quota),
   crédit. Test + en jeu.
4. ⬜ **PLANNING/ÉTAT** : `isOpen`/cooldown/difficulté lus par `PortChooserScreen` — vérifier que l'écran s'ouvre le bon
   jour avec les difficultés + cooldown corrects (poussée au boot si nécessaire, façon InvasionInfo).

## Notes §3/§4
- Combat client-autoritatif : le serveur ré-exécute `recordOutcome` sur SON état ; le loot suit #25/§4bis (client-reporté
  partiel, le serveur roule `rollLoot` mais retombe sur le loot client si divergence RNG, comme campagne/expédition).
- `ModeDifficulty` = enum ; le wire porte un `int` (ordinal/niveau) → conversion serveur via `ModeDifficulty.values()` ou
  l'API du jeu (à confirmer à l'implémentation).
- Round-trip profond : vérifier cooldown + compteurs + inventaire après round-trip (pas juste « appliqué »).
- Planning open-days : horloge serveur (jour) — PORT_DOCKS/WAREHOUSE ouverts des jours différents.
