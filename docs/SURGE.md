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
5. 🟢 **Raids** (mécanique HQ) — **PROTOCOLE RÉSOLU (2026-08-09, g72e) : le blocage §4 est levé.** Recon combat +
   disasm de `SurgeHeroChooserScreen.doRaidSurge` → `ClientActionHelper.raidSurge` (offsets prouvés) + observation
   EN JEU partielle. **Le raid envoie TROIS messages, dans l'ordre** :
   1. **`HeroLineupUpdate{type=SURGE, lineup=équipe de raid}`** — équipe SURGE (déjà géré serveur : `applyHeroLineupUpdate`,
      journalisé « HeroLineupUpdate(SURGE) → lineup enregistrée [persistée] » EN JEU ✅).
   2. **`Action{command=SET_SEED, TYPE=SURGE, ID=<graine>}`** — graine RNG du combat de raid (déjà géré : handler
      SET_SEED, observé EN JEU ✅).
   3. **`Action{command=RAID_SURGE, extra={TYPE=<nom du district>, COUNT=<long>, UPSELL=<bool>,
      MODE=AUTO_SELECT|MANUAL_SELECT}}`** — l'ISSUE du raid (c'était le « message manquant »). Construit par
      `raidSurge(district, count, upsell, autoSelect, snap, listener)` : `withType(district)`→`extra[TYPE]=district.name()`,
      `COUNT=count`, `UPSELL=upsell`, `MODE=` selon auto/manuel. **⇒ le serveur doit ajouter un handler d'Action
      `RAID_SURGE`** qui rejoue `SurgeHelper.recordRaid` (params RÉSOLUS ci-dessous) sur le membre.
   **`recordRaid` (10 params, prouvés)** : `recordRaid(user, member, surgeID, district, false, RAID_TEAM_POWER, 0L,
   GOLD, raidHEROES, snapshot)` — `district`=`extra[TYPE]`, équipe/`raidHEROES`/`RAID_TEAM_POWER` depuis la SURGE
   `HeroLineup` persistée (msg 1), `GOLD`=`getGoldForSurgeRaid(user, heroLineup, opponent.lineup, raidHeroes, snap)`,
   nb de raids borné par `getMaxRaidsPerSurge(user, perkProvider)`.
   **✅ HANDLER SERVEUR LIVRÉ (g72f, headless)** : `ServerSurgeCombat.applyRaidOutcome` (recordRaid autoritatif,
   params ci-dessus prouvés au site d'appel `doRaid` offsets 181-218 : `RAID_TEAM_POWER`=Σ`getPower(hero,0)`,
   `GOLD`=`getGoldForSurgeRaid`, ordre `false, TEAM_POWER, 0L, GOLD` confirmé) + `ServerSurgeState.applyRaid`
   (recordRaid + incrémente le compteur PARTAGÉ `raidsUsed`, car recordRaid ne mute que le compteur quotidien du
   joueur via `incDailyUses`) + handler `Action RAID_SURGE` dans `LoginServer` (lit `extra[TYPE]`=district, applique,
   persiste, diffuse `SurgeUpdate`). `SurgeRaidTest` (équipe SURGE posée d'abord, raid headless, `raidsUsed++`,
   or crédité par `storeGold`, persistance + round-trip wire). **89/89.**
   **RESTE (§8)** : **vérif EN JEU d'un raid COMPLET** — bloquée le run g72e car le combat de raid a coupé la
   connexion client (`Socket closed`/`Connection refused`) avant l'`Action RAID_SURGE` (stabilité du combat de raid
   à régler, distincte du protocole). Sémantique `COUNT`+clear-district à confirmer sur un raid abouti EN JEU (le
   handler traite 1 raid par Action ; le clear-district n'est pas présumé). **Pilote** : `surgeraid`.
6. 🟢 **Récompenses & bascule** (`ServerSurgeRewards` + `ServerSurgeState.rollover/personalize/claimRewards`,
   handler `SurgeClaimRewards`) — **montants 100 % code du jeu, zéro invention (§3/§4)** :
   - **tokens** (`CRYPT_TOKENS`) = `SurgeClientHelper.getPlayerSurgeCoins(surge)` = `getTokensPerClearedRegion()×régions
     + getBaseTokens()` (régions = `wavesCompleted×3 + waveRegionsCleared.size()`). Sondé : base **30**, /région **14**.
   - **or** (`GOLD`) = `member.storedGold` (accumulé par `recordOutcome→storeGold`).
   - **influence** de GUILDE (`GUILD_INFLUENCE`) = `SurgeHelper.getInfluenceProgress(surge) + SurgeStats.getBaseInfluence()`
     — la somme EXACTE affichée par `SurgeClearedWindow` (disasm offsets 96-102). Sondé : base **5000**, /région **1350**.
   - **Flux client PROUVÉ** (disasm `SurgeScreen.checkAnimations` + `SurgeResultsWindow.lambda$createRewardsContent$1`) :
     le serveur pose un `SurgeRewards` par membre dans `SurgeData.unclaimedRewards[surgeID_terminé]` (clé = `previousResults.surgeID`,
     livré par `GetSurge`) ; le client ouvre la fenêtre si `totalGold≠0 || totalTokens≠0`, et au clic envoie
     `SurgeClaimRewards{surgeID}` **puis** se crédite localement `UserHelper.giveUser(CRYPT_TOKENS,totalTokens)` +
     `(GOLD,totalGold)` (`RewardSourceType.NORMAL`). Le serveur applique le MÊME crédit de façon autoritative,
     **une seule fois** (anti double-réclamation via le registre `surgeprev:<guildID>`).
   - **Bascule (rollover)** : au changement de surgeID (`loadOrReset`), l'état du surge terminé est figé (registre
     résultats + récompense par membre + set réclamé), l'influence est créditée UNE fois à la guilde (plafond
     `getMaxGuildInfluence`), et le `SurgeData` neuf embarque `previousResults`. Paresseux, comme la bascule de saison
     de guerre. `SurgeClaimTest` : montants (128 tokens/or), crédit persistant, anti-double, influence (+14450), round-trip.
   - **⚠️ 1 inférence de PLACEMENT (documentée, §4)** : le MONTANT d'influence est 100 % code du jeu ; le MOMENT de crédit
     (à la bascule, une fois) est un choix d'ingénierie cohérent avec war/contest — **à confirmer EN JEU** (incr. 8).
     Le crédit tokens/or personnel est, lui, DÉFINITIF (disasm). `achievedTier` de `SurgeResultInfo` laissé à 0 (non prouvé).
7. 🟢 **Ordonnanceur** — la bascule de surge (fin de fenêtre → `SurgeResultInfo` + récompenses → nouveau surge) est
   assurée PARESSEUSEMENT par `loadOrReset` (incrément 6 ci-dessus), au prochain accès après changement de surgeID —
   même patron que le rollover de saison de guerre/contest. Pas de tâche de fond séparée nécessaire.
8. ✅ **Vérif EN JEU** (compte TL100/guilde `dh-snapshot-postwar-0803.db`, surge ACTIF) :
   - ✅ **RENDU CONFIRMÉ** (2026-08-04, g72) : le vrai client `nav SURGE` → **`GetSurge → SurgeData`** sur le fil
     (serveur : `membres=2`), l'écran **`SurgeScreen` « CREEP SURGE »** s'ouvre sans crash, affiche les **27
     districts** (nos `activeDistricts`), le compte à rebours (16:00 UTC), le palier, et l'entête **30 tokens /
     5 000 influence = nos constantes `getBaseTokens`/`getBaseInfluence`**. Capture `desktop-port/build/surge.ppm`,
     `dumpscreen`=`SurgeScreen`. Reproduction : `cp server/data/dh-snapshot-postwar-0803.db server/data/dh-server.db`
     puis `DH_CLICKFILE=<fichier avec "nav SURGE"> ./run-online.sh` (restaurer `dh-server.db` après).
   - ✅ **COMBAT DE DISTRICT CONFIRMÉ EN JEU** (2026-08-09, g72d, compte reconstruit `SurgeAcctSetup`) : sélection
     d'équipe (bouton AUTO du jeu → `autoSelectHeroes`) + quick-fight → **`StartSurgeAttack(O) → StartSurgeAttackResponse`
     (3 défenseurs) → `SurgeAttack(O, WIN) → SurgeUpdate` (district vaincu, +0 pts au tier 0 = fidèle, or→coffre 9,22 M,
     influence→banque de guilde 80 552)**, enregistré autoritativement (`recordOutcome`) + **persisté**. Écran
     « DISTRICT 2 CLEARED! » (capture). Pilote : commandes clickfile `surgefight`/`surgeteamfight` (chemins réels par
     réflexion : `SurgeScreen.fightPressed` → `SurgeHeroChooserScreen` → auto-sélection → `quickFightPressed`).
   - 🐛 **BUG SERVEUR TROUVÉ & CORRIGÉ EN JEU (raison d'être de §8)** : le serveur ne posait pas `SurgeData.youAreInRaid`
     pour un membre → `SurgeScreen.fightPressed` refusait TOUT combat (`CRYPT_JOINED_LATE_ERROR`). Champ 100 %
     serveur-autoritaire (aucun flux client ne l'écrit). Corrigé : `ServerSurgeState.personalize` pose
     `youAreInRaid = true` pour le membre participant. Sans cette vérif EN JEU, le mode était injouable.
   - ✅ **RAID CONFIRMÉ EN JEU** (2026-08-09, g72g) : `surgeraid` (auto-équipe + `doRaidSurge`) → le vrai client
     envoie `HeroLineupUpdate{SURGE}` + `Action RAID_SURGE{extra TYPE=P, COUNT=0, UPSELL=false, MODE=MANUAL_SELECT}`
     → **notre handler** `Action RAID_SURGE(P) → SurgeUpdate (+9 225 000 or, raidsUsed=1) [persisté]`. Écran avec
     bouton « RAID (1 LEFT) ». `COUNT=0` confirme que ce n'est PAS un multiplicateur (1 raid/Action — pas d'invention).
     La connexion TIENT (l'ancien serveur sans handler RAID_SURGE coupait — d'où la coupure g72e).
   - ✅ **RÉCOMPENSES / RÉCLAMATION CONFIRMÉES EN JEU** (2026-08-09, g72g) : horloge serveur avancée (+13h,
     `DH_SERVER_OPTS=-Ddh.clock.offset.hours=13`) → bascule → `GetSurge` livre `unclaimedRewards` → le client ouvre
     **« SURGE REWARDS »** (capture) affichant **MY REWARDS 18,45 M or + 30 tokens** et **GUILD 5 000 influence** —
     valeurs EXACTES de notre serveur (`storedGold` combat+raid, `getPlayerSurgeCoins`=30, `getBaseInfluence`=5000).
     `surgeclaim` → `SurgeClaimRewards{surgeID} → SurgeRewards (+30 tokens, +18 450 000 or)`, **crédité + persisté**
     (`CRYPT_TOKENS=30`, GOLD+18,45 M vérifié en DB). **Anti-double CONFIRMÉ EN JEU** : 2ᵉ claim → `(+0 tokens, +0 or)`.
     ⇒ l'inférence de placement du crédit d'influence (à la bascule) est ainsi VALIDÉE en jeu.
   - **✅ SURGE 100 % VÉRIFIÉ EN JEU** : rendu + combat de district + raid + bascule/récompenses/réclamation +
     anti-double + fix `youAreInRaid`. Pilote complet : `nav SURGE`/`surgestate`/`surgenav`/`surgefight`/
     `surgeteamfight`/`surgequick`/`surgeraid`/`surgeclaim`.

### Restaurer le client pour la vérif EN JEU (après un environnement neuf / reprovision)
Les artefacts de build du client sont git-ignorés (dérivés). Recette (tout depuis les sources committées) :
1. `tools/build_spine_jar.sh` → `libs/spine-libgdx-perblue.jar` (spine-runtimes 3.6 + gdx-1.9.7). Sinon le module
   desktop ne compile pas.
2. `tools/fetch_assets.sh` → assets ETC1 (world/ui) depuis l'archive.org du projet (le base APK n'a pas les textures).
3. `SurgeAcctSetup server/data/dh-server.db` → compte apte à SURGE (TL100 + roster + guilde + **tutoriel complété**,
   sinon `canNavigateTo(SURGE)=false`).
4. `desktop-port/run-online.sh` (compile serveur+client, lance) ; piloter via `DH_CLICKFILE` : `nav SURGE`,
   `surgestate`, `surgefight`, `surgeteamfight`, `surgeraid`, `surgeclaim`. NB : `nav SURGE` doit être émis APRÈS que le hub est
   pleinement chargé (l'état guilde/tuto arrive un peu après le boot ; sinon `canNavigateTo` est momentanément faux).

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
- 2026-08-04 (g72) : incréments 6+7 — RÉCOMPENSES & BASCULE. `ServerSurgeRewards` (tokens=`getPlayerSurgeCoins`,
  or=`storedGold`, influence=`getInfluenceProgress+getBaseInfluence` — 100 % code du jeu) + registre `surgeprev:<guildID>`.
  `ServerSurgeState.rollover` (fige résultats + récompense/membre, crédite l'influence guilde une fois), `personalize`
  (`GetSurge` livre `unclaimedRewards`/`yourRaidsUsed` par-viewer), `claimRewards` (handler `SurgeClaimRewards` :
  crédit autoritatif `CRYPT_TOKENS`+`GOLD` miroir du client, anti-double). `SurgeClaimTest` (128 tokens/12345 or,
  influence +14450, persistance, anti-double, round-trip). **88/88.** Reste : raids (protocole EN JEU) + vérif EN JEU.
