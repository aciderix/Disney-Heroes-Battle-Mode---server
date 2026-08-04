# SURGE (#72) — mode de guilde saisonnier — suivi d'implémentation

> Mode hub non implémenté, attaqué avec le pipeline industrialisé (#73/#74). **Lire avant de continuer.**
> Chaque incrément : contrat (ScreenContract) → logique du jeu (SurgeHelper/SurgeStats, §3) → test headless
> (WireCheck + ClientOracle/SendValidation) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que SURGE est (recon bytecode + sonde headless)

Mode **de GUILDE** saisonnier : districts (dont le **QG** = `SurgeStats.getHQDistrict()` = `FF`), vagues de
**régions** (`getRegionsPerWave()` = 3), **paliers** (tiers), **objectifs** (rareté/type), **raids**, monnaie
**tokens**/**influence**/or. Gaté par `Unlockable.SURGE_OBJECTIVES` + perks de guilde
(`getMaxUnlockedTier/Difficulty(IGuildPerkProvider)`, `getMaxRaidsPerSurge(IUser, IGuildPerkProvider)`).

**La logique est CLIENTE** (`SurgeHelper`, `SurgeClientHelper`, `SurgeStats`, modèle `ISurgeMember`/
`SurgeClientMember`) → le serveur EXÉCUTE ces helpers (§3), il ne réinvente rien. Pas dans le BootData : le client
demande via **`GetSurge`** (comme `GetArenaInfo`/`GetInvasionInfo`).

### Faits de calendrier (sonde headless, §8)
- `SurgeStats.getEndHour()` = 11 · `getIntermission()` = **900000 ms (15 min)** · `getRegionsPerWave()` = 3 · `getHQDistrict()` = FF
- `SurgeHelper.getNextSurgeStartTime(now)` / `getSurgeEndTime(now)` = la fenêtre (surges quotidiens, entracte 15 min).
- **Règle « actif » (établie)** : un surge est ACTIF à `now` ⟺ `getNextSurgeStartTime(now) > getSurgeEndTime(now)`
  (pendant un surge, le prochain départ tombe après la fin courante ; pendant l'entracte, l'inverse). Vérifié.

### Points d'entrée logiques serveur (recensés par `ModeGraph --logic`)
`SurgeHelper.recordOutcome(IUser, ISurgeMember, surgeID, DistrictType, boolean, CombatOutcome, …)` (issue d'un
combat de région), `recordRaid(IUser, ISurgeMember, …)` (raid), `getGoldForSurgeFight/Raid`, `getMaxRaidsPerSurge`,
`getMaxUnlockedTier/Difficulty`, `getTier`, `getPointsNeededForNextTier`, `getRecommendedOpponent`,
`getInfluenceProgress`. ⚠️ `SurgeHelper.doRaid(...)` est le RAPPEL d'action CLIENT (consomme) — NE PAS le
pré-appeler côté serveur (piège g45 `doStartWarAttack`).

### Contrat (ScreenContract via `contract.sh --mode`)
- Union = 17 classes (surge screens + `SurgeHelper`). Messages CORE : `SurgeData` (état principal, 20 champs),
  `SurgeAttack`, `StartSurgeAttackResponse`, `SurgeClaimRewards`, `SurgeOpponentSummary`, `SurgeMemberSummary`,
  `SurgeObjectiveInfo`, `SurgeLogData`, `SurgeResultInfo`, `SurgeRewards`, `SurgeUpdate*`, `SurgeWaveUpdate`…
- Handlers **MANQUE** : `GetSurge`, `StartSurgeAttack` (+ `SurgeAttack`, `SurgeClaimRewards` à câbler).
- Squelette généré par le scaffolder (scratchpad) : 26 builders + 2 handlers + WireCheck (round-trip vert).

## Plan d'incréments

1. ✅ **Calendrier & identité** (`ServerSurge` : `isActive`/`surgeEndTime`/`nextSurgeStartTime`/`intermission`/
   `currentSurgeID`, 100 % code du jeu) + `SurgeScheduleTest`. Régression.
2. ✅ **État partagé de guilde** (`ServerSurgeState`) : `SurgeData` par (guilde, surgeID) persisté dans
   `shard_state` (clé `surge:<guildID>`), **membres = roster** (`SurgeMemberSummary`, identité `BasicUserInfo`),
   **remis à zéro** quand le surgeID change (`loadOrReset`). Conteneurs/sous-messages non nuls (wire-sûr).
   `SurgeStateTest` : membres=roster, round-trip wire + DB, reset. Districts/régions/paliers/objectifs peuplés
   aux incréments 3-6.
3. 🟢 **`GetSurge` → `SurgeData`** (handler `LoginServer`) : charge l'état via `ServerSurgeState.loadOrReset`
   (ou `emptySurge` hors guilde), renvoie `SurgeData` (patron `GetInvasionInfo → InvasionInfo`). Gate
   `SURGE_OBJECTIVES` = **TL 32** (unlockables.tab), verrou CLIENT respecté (serveur répond, ne désactive pas).
   Headless-prouvé (routage + réponse wire-valide). **⬜ Vérif EN JEU restante** (écran s'ouvre) — faisable sur le
   compte BaronessDante (TL100, en guilde). Reste : peupler adversaires/districts/paliers/objectifs (incr. 4-6).
4. 🧭 **Combat de région** : `StartSurgeAttack → StartSurgeAttackResponse` + `SurgeAttack` (issue) →
   `SurgeHelper.recordOutcome`. **ANATOMIE de `recordOutcome` (disasm, faits) — 12 params** :
   `(IUser, ISurgeMember, raidID, DistrictType, boolean, CombatOutcome, Collection attackerLineupSummaries,
   Collection defenderLineupSummaries, Collection attackerHEROES, Set objectifs, boolean, SpecialEventSnapshot)`.
   Son corps : `storeGold` (or, depuis les lineups) · `recordObjectiveProgress(member, objectifs)` ·
   itère **attackerHEROES (`IHero`)** → `CollectionHelper.recordHeroMastery` · `ContestHelper.onSurgeAttack` ·
   `UserActivityTracker.onSurgeAttack` (points/activité). Membre = **réutiliser `SurgeClientMember(surgeID,
   SurgeMemberSummary)`** (impl `ISurgeMember` du jeu, mute la summary → persistée). ✅ **FAisable direct** :
   outcome/attacker/defender = `base.outcome`/`base.attackers`/`base.defenders` (déjà `AttackLineupSummary`,
   comme la campagne). ⚠️ **2 params à résoudre AVANT câblage (ne pas inventer §4)** : (a) la Collection
   **attackerHEROES** = `IHero` reconstruits depuis `base.attackers[*].units` (convertisseur du jeu) ; (b) le
   **Set d'objectifs** — le client le calcule via `getQualifiedObjectives` qui exige les stats de `Scene`
   (`CombatStatsData`) ABSENTES du wire → à dériver de `SurgeAttack.objectiveProgress` (map cliente).
   **✅ (a)/(b) RÉSOLUS PAR LES FAITS (disasm site d'appel, offsets 239-261) — zéro invention** : (a) `IHero`
   reconstruits via `user.getHero(unit.type)` depuis `base.attackers[*].units` (mercenaires exclus) ; (b) Set =
   `SurgeAttack.objectiveProgress.keySet()` (le client met `(SurgeObjectiveInfo→1)` par objectif QUALIFIÉ) ;
   **les DEUX booléens sont `iconst_0` au site d'appel → `false, false` (prouvé)**.
   **Incrément 4a LIVRÉ** (`ServerSurgeCombat.applyRegionOutcome`) : exécute `SurgeHelper.recordOutcome` headless
   (membre = `SurgeClientMember` du jeu) ; `SurgeCombatTest` prouve la progression d'objectif appliquée par le code
   du jeu (slot0=1 ; points/or=0 attendu pour un joueur sans guilde/tier). **Reste 4b/4c** : opponents +
   `StartSurgeAttack→StartSurgeAttackResponse` (raidID + lineup défenseur) puis handler `SurgeAttack` (correler le
   raid, `applyRegionOutcome`, persister le SurgeData, marquer l'adversaire vaincu).
4b-ii. ✅ **Adversaires** (`ServerSurgeState.buildOpponents`) : un par district actif (pool réel du shard +
   repli synthétique), lineup via `getHeroSummary`/`extended`. Peuplé dans `buildFresh` → `GetSurge` renvoie
   désormais les 27 districts avec adversaires.
4c. ✅ **Combat de district câblé** (handlers `LoginServer` + `ServerSurgeState.startAttack`/`applyAttack`) :
   `StartSurgeAttack → StartSurgeAttackResponse` (lineup défenseur en `HeroData` = roster réel de l'adversaire ou
   bot, + raidID + verrou) puis `SurgeAttack → SurgeUpdate` (`applyRegionOutcome` autoritatif + district vaincu à
   la victoire + delta points/districts), persisté, diffusé à la guilde (`pushToGuild`). `SurgeAttackFlowTest`
   (round-trip wire des 2 réponses + district vaincu + persistance). Headless 🟢.
5. 🧭 **Raids** (mécanique HQ) — recon faite, **câblage BLOQUÉ sur preuve de protocole (§4/§8)**.
   **`recordRaid` params RÉSOLUS** (disasm `SurgeHelper.doRaid`, offsets 198-218) :
   `recordRaid(user, member, surgeID, opponent.district, false, RAID_TEAM_POWER, 0L, GOLD (getGoldForSurgeRaid),
   raidHEROES (IHero pour mastery), snapshot)` — `getMaxRaidsPerSurge(user, perkProvider)` borne le nb de raids.
   **⚠️ BLOQUANT** : `doRaid` appelle `recordRaid` **CÔTÉ CLIENT** et `SurgeHeroChooserScreen.doRaidSurge`
   n'envoie au serveur qu'un **`HeroLineupUpdate`** (l'équipe SURGE) — **aucun message d'ISSUE de raid** visible
   dans le code client. Donc le serveur ne peut pas suivre `raidsUsed`/gold de façon autoritative sans OBSERVER le
   trafic réel pendant un raid EN JEU (peut-être un `Action` générique ou une suite de `SurgeAttack`). On NE câble
   PAS tant que le protocole n'est pas prouvé (sinon invention interdite). → à élucider lors de la vérif EN JEU.
6. ⬜ **Objectifs & récompenses** : progression d'objectifs, `SurgeClaimRewards` (tokens/influence/or), unclaimed.
7. ⬜ **Ordonnanceur** : bascule de surge (fin de fenêtre → résultats `SurgeResultInfo` → nouveau surge), comme
   `ServerWarScheduler`.
8. ⬜ **Vérif EN JEU complète** (guilde + surge actif + adversaires) : rendu, combat, raid, réclamation.

## Scoring — vérifié FIDÈLE (données du jeu, pas un manque)

Les points d'un combat = puissance vaincue × multiplicateur de district × **multiplicateur de PALIER**
(`creep_surge_tiers.tab`, via `SurgeStats.getBasePointMultiplierForTier`). Fait mesuré : **tier 0 et 1 → mult
0.0 (0 point)**, tier 2 → 1.0, tier 3 → 2.08, tier 4 → 3.24… ; goldMult idem (0 aux tiers 0/1, puis 0.1/0.2…).
Donc une guilde à bas palier marque **0 point par design** (les points s'accumulent au palier 2+, atteint en
vidant des districts / via les perks). Le `+0 pts` des tests `SurgeCombatTest`/`SurgeAttackFlowTest` est donc le
comportement CORRECT du jeu (joueur/guilde sans palier), pas un bug — le pipeline `recordOutcome` est fidèle.

## Historique
- 2026-08-04 (g63) : incrément 1 — `ServerSurge` calendrier/identité (code du jeu) + `SurgeScheduleTest`. Régression.
- 2026-08-04 (g64) : incrément 2 — `ServerSurgeState` (SurgeData partagé par guilde, membres=roster, persisté
  `shard_state`, reset sur surgeID) + `SurgeStateTest` (roster + round-trip wire/DB + reset). Régression.
- 2026-08-04 (g65) : incrément 3 — handler `GetSurge → SurgeData` (LoginServer) + `emptySurge` (hors guilde).
  Headless 🟢 (routage + réponse wire-valide) ; vérif EN JEU restante.
- 2026-08-04 (g67) : incrément 4a — `ServerSurgeCombat.applyRegionOutcome` (recordOutcome autoritatif, params
  prouvés au bytecode) + `SurgeCombatTest`. Recon combat entièrement résolue (a/b/booléens). Régression.
- 2026-08-04 (g68) : incrément 4b-i — `ServerSurgeMap` (27 districts actifs via `MapDistrictStats.getEnvironment`
  + `SurgeStats.getMultiplier`, données `map_districts.tab`/`creep_surge_nodes.tab`, §3/§4) + `SurgeMapTest`.
  Base de la pose d'adversaires (4b-ii). Régression.
- 2026-08-04 (g69) : incrément 4b-ii — `ServerSurgeState.buildOpponents` : un `SurgeOpponentSummary` par district
  actif, tiré du POOL RÉEL du shard (`listUserIDs` hors membres de guilde) → lineup depuis leur roster
  (`getHeroSummary`/`extended`, comme l'arène #43), repli SYNTHÉTIQUE déterministe (bot `createAndAddHero`) si pool
  vide. `SurgeStateTest` étendu (27 adversaires, lineups non vides, pool réel utilisé, round-trip wire). Régression.
