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
2. ⬜ **État partagé de guilde** : `SurgeData` par (guilde, surgeID) persisté (`ServerGuild`/table dédiée, comme
   WAR/INVASION) — membres (roster de guilde → `SurgeMemberSummary`), districts/régions, paliers, objectifs.
3. ⬜ **`GetSurge` → `SurgeData`** (l'écran rend) : peupler TOUS les champs du contrat depuis l'état ; pousser au
   besoin ; gate `SURGE_OBJECTIVES`. Vérif EN JEU (écran s'ouvre).
4. ⬜ **Combat de région** : `StartSurgeAttack → StartSurgeAttackResponse` + `SurgeAttack` (issue) →
   `SurgeHelper.recordOutcome` (autoritatif, client-combat) + scoring/tiers + persistance ; anti-triche (recalc).
5. ⬜ **Raids** : `recordRaid` + `getMaxRaidsPerSurge` (perks) + `getGoldForSurgeRaid`.
6. ⬜ **Objectifs & récompenses** : progression d'objectifs, `SurgeClaimRewards` (tokens/influence/or), unclaimed.
7. ⬜ **Ordonnanceur** : bascule de surge (fin de fenêtre → résultats `SurgeResultInfo` → nouveau surge), comme
   `ServerWarScheduler`.
8. ⬜ **Vérif EN JEU complète** (guilde + surge actif + adversaires) : rendu, combat, raid, réclamation.

## Historique
- 2026-08-04 (g63) : incrément 1 — `ServerSurge` calendrier/identité (code du jeu) + `SurgeScheduleTest`. Régression.
