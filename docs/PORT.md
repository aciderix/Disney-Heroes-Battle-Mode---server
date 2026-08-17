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
3. ✅ **RÉCOMPENSE DOUBLE (`Action CLAIM_DOUBLE_PORT_REWARDS` → `claimDoubleRewards`)** : « regarder une vidéo pour
   doubler » le butin du dernier combat/raid. Mécanique (bytecode) : `giveLoot` (appelé par recordOutcome/recordRaid)
   — si VIP **`DOUBLE_PORT_REWARDS`** (VIP 4) → butin ×2 **inline** ; SINON → butin ×1 + pose un
   `DoubleVideoLootContainer{loot, mode, diff}` sur l'IndividualUser runtime. `claimDoubleRewards(user, snap)` crédite
   ce container (`giveRewards`) puis le VIDE ; container absent → **`DOUBLE_REWARDS_NOT_AVAILABLE`** (anti-triche).
   - **FAIT §6 (persistance)** : `DoubleVideoLootContainer` n'est référencé par **AUCUNE** classe de message/BootData
     (vérifié) → **purement runtime, non persisté par le jeu** (perdu au restart). Fidélité = **in-session** (le client
     montre la popup juste après le combat). Comme le converter reconstruit un IndividualUser frais par requête, on
     MÉMORISE le container sur le `ServerUser` (champ de session `pendingDoubleLoot`, posé dans `recordDifficultyModeAttack`
     /`recordRaidDifficultyMode` via `getVideoDoubleLoot`), le `ServerUser` étant caché par connexion (`LoginServer.connUsers`)
     → le CLAIM (requête séparée) le retrouve. AUCUN schéma DB inventé (§2/§4).
   - Handler : `applyCommand` case `CLAIM_DOUBLE_PORT_REWARDS` (restaure `setVideoDoubleLoot(pendingDoubleLoot)` →
     `claimDoubleRewards` → vide `pendingDoubleLoot`) ; routé par le fallback générique `applyAction` de `LoginServer`.
   Test `PortDoubleRewardTest` (VIP 0 ; combat +5000 → CLAIM +5000 = ×2 ; re-claim → `DOUBLE_REWARDS_NOT_AVAILABLE`
   refusé ; claim sans combat refusé ; GOLD persiste wire+DB). **✅ VÉRIFIÉ EN JEU** (id=1) : `portattack PORT_DOCKS`
   (combat, pose le container) → `portdouble` (VRAIE `Action CLAIM_DOUBLE_PORT_REWARDS`) → serveur `récompense double
   créditée [logique du jeu]` → **DB GOLD doublé** ; re-`portdouble` → **REFUSÉ `DOUBLE_REWARDS_NOT_AVAILABLE`**.
   Pilote `portdouble`.
4. ✅ **PLANNING/ÉTAT (`PortChooserScreen`)** — **RENDU-ONLY, aucun code serveur requis**. Contrat industriel (#73,
   `contract.sh --mode Port`) : **0 message envoyé, 0 champ wire lu, 0 gate `Unlockable`** → l'écran lit TOUT via les
   helpers du jeu (`DifficultyModeHelper.isOpen`×7, `getCooldownEnd`, `getRemainingDailyUses`, `isResetAvailable`,
   `hasChallengeChances`, `getUseKey`/`getChallengeKey`/`getCooldownType`) sur l'**état PERSISTÉ + l'horloge serveur**.
   Rien à pousser au boot (contrairement à InvasionInfo). **✅ VÉRIFIÉ EN JEU + VISUEL** (id=1, jour serveur 1) : pilote
   `portscreen` (chemin réel `pushScreen(new PortChooserScreen())`) → écran **THE PORT** : **THE DOCKS** (PORT_DOCKS)
   « EARN XP / ENEMIES HAVE FANTASTIC IMMUNITY / **ENTER** / CHANCES LEFT: 0/2 » (quota lu de l'état persisté = combats/
   raids consommés) ; **THE WAREHOUSE** (PORT_WAREHOUSE) « EARN GOLD / ENEMIES HAVE NORMAL IMMUNITY / **OPENS TOMORROW** »
   (fermé le jour serveur → prochain jour d'ouverture calculé des open-days). Capture `build/port_chooser_ingame.ppm`.
   Pilote `portscreen`.

## ⇒ PORT #72 COMPLET (incr. 1 combat + 2 raid + 3 récompense double + 4 planning — tous ✅ VÉRIFIÉS EN JEU).

## Vérif « ENTRÉE COMPLÈTE » du mode (jouer de bout en bout via le VRAI flux d'UI)

Test demandé : **entrer** dans THE DOCKS et THE WAREHOUSE et les jouer (un test complet = jouer le mode en entier).

- **THE DOCKS ✅ JOUÉ DE BOUT EN BOUT EN JEU.** Flux réel : bouton ENTER (`ModeData.handleButtonPress` → `ModePreviewScreen`)
  → `DifficultyModeHeroChooserScreen(PORT_DOCKS, ONE)` (écran **« CHOOSE YOUR HEROES! »**) → `unitSelected`×5 +
  `startBattleInner()` → **`DifficultyModeAttackScreen`** (combat rendu, auto-combat `dh.autofight`) → **VICTOIRE** →
  écran **REWARDS** (Hero XP +33 ×5, items ×12, bouton **GET 2X REWARDS! 📺** = l'entrée de l'incr. 3) → client envoie
  `DifficultyModeAttack` → serveur `PORT_DOCKS diff=1 outcome=WIN → recordOutcome appliqué [persisté]`. Setup : équipe
  boostée (`ExpAdminBoost`, RED 100 6★) + horloge à un jour DOCKS-ouvert avec chances fraîches (`AdminClock`). Pilotes
  `portenter <MODE>` (→ sélecteur d'équipe, = ce que `ModePreviewScreen.doAttack` construit) + `portteam` (sélection +
  `startBattleInner`). Captures `build/port_chooser_ingame.ppm`, `build/port_docks_played_ingame.ppm`.

- **THE WAREHOUSE — EVENT-GATÉ (fait établi §8, PAS un bug).** En tentant d'entrer, découverte : l'OUVERTURE des modes PORT
  est pilotée par le **planning d'ÉVÉNEMENTS SPÉCIAUX**, pas par le simple jour-de-semaine. `DifficultyModeHelper.isOpen`
  (ordinaux PORT) → `BaseEventSnapshot.isModeOpen(mode)` → `ModesOpenSnapshot.getOpenModes()` (l'ensemble des modes ouverts,
  peuplé par le composant d'événement `ModesOpen`). Notre serveur ré-hébergé n'a **pas d'événements live** → `getOpenModes`
  par défaut = **DOCKS seulement**, WAREHOUSE jamais (vérifié sur tous les jours, avec vrai snapshot). L'« OPENS TOMORROW »
  de `PortChooserScreen` vient de `getOpenDays`/`getNextOpenDay` (affichage), indépendant du gate réel `isModeOpen`. ⇒
  **THE WAREHOUSE est event-gaté comme FRANCHISE_TRIALS** ; on ne le FORCE PAS en prod (le flag debug DU JEU
  `BaseEventSnapshot.debugAllModesOpen` existe mais l'utiliser en serveur autoritatif = « faux OK » §2 interdit).
  - **Logique serveur PROUVÉE (§8, « rien d'absent sans preuve »)** : `PortWarehouseTest` LÈVE le gate via
    `debugAllModesOpen` (réservé au test, remis à false) → `isOpen(PORT_WAREHOUSE)=true` → combat WAREHOUSE WIN → **+7000
    GOLD + cooldown `PORT_WAREHOUSE_ATTACK`** + persistance wire+DB. THE WAREHOUSE emprunte le MÊME `recordOutcome` que
    THE DOCKS (le mode n'est qu'un paramètre) → couvert par la vérif en jeu de DOCKS ; seul son gate d'ouverture (live
    events) l'empêche d'être entré en jeu sur notre serveur.
  - Le test asserte AUSSI qu'au défaut (gate non levé) WAREHOUSE est fermé (documente l'event-gate).

## Notes §3/§4
- Combat client-autoritatif : le serveur ré-exécute `recordOutcome` sur SON état ; le loot suit #25/§4bis (client-reporté
  partiel, le serveur roule `rollLoot` mais retombe sur le loot client si divergence RNG, comme campagne/expédition).
- `ModeDifficulty` = enum ; le wire porte un `int` (ordinal/niveau) → conversion serveur via `ModeDifficulty.values()` ou
  l'API du jeu (à confirmer à l'implémentation).
- Round-trip profond : vérifier cooldown + compteurs + inventaire après round-trip (pas juste « appliqué »).
- Planning open-days : horloge serveur (jour) — PORT_DOCKS/WAREHOUSE ouverts des jours différents.
