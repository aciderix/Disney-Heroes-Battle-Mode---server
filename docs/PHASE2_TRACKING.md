# PHASE 2 — SUIVI (tracker vivant)

> **Document de SUIVI** (demandé par l'utilisateur, 2026-08-24). Le **plan détaillé** reste dans
> [`PHASE2_PLAN.md`](PHASE2_PLAN.md) (chantiers A→G) ; CE fichier est le **tableau de bord** qu'on met à jour au fil de l'eau :
> statut de chaque étape, liens vers les rapports auto-générés, décisions. **À maintenir en permanence** (comme MEMORY/JOURNAL).
>
> Règles inchangées (`PRINCIPLES.md` §1-§8) : serveur exécute le code du jeu · rien d'inventé (tab/bytecode) · vérif EN JEU ·
> perfs non destructrices de la fidélité · identifiant de modèle JAMAIS dans un artefact. **Rien n'est « fait » sans preuve.**

## Légende de statut
⬜ non engagé · 🚧 en cours · 🟢 prouvé headless (pas encore en jeu) · ✅ prouvé EN JEU / validé · 📄 rapport auto-généré · ⏸️ en attente décision utilisateur

---

## Vue d'ensemble des chantiers (détail : `PHASE2_PLAN.md`)

| # | Chantier | Statut | Note |
|---|---|---|---|
| **A** | **Vérification globale & chasse aux oublis** | 🚧 **EN COURS** | audit auto 4 axes (ci-dessous) |
| B | Performance du portage (non destructive) | ⬜ | combat unidbg = point chaud ; Opt.3 spine Java certifié oracle |
| C | Front-end joueur (launcher : liste/join serveurs, login) | ⬜ | redirection `LIVE` aujourd'hui en dur 127.0.0.1 |
| D | Backend/front d'hébergement (panneau opérateur, multi-shard) | ⬜ | unifier AdminClock/War/Invasion/Mail/Events |
| E | Tests & intégration APK mobile | ⬜ | même wire 12.1.0 ; vrai client Android → serveur |
| F | Tests inter-machines réels (Internet, NAT/TLS, charge) | ⬜ | soak, multi-région, sécurité réseau |
| G | Qualité/outillage transverse (CI, repro, doc self-hoster, légal) | ⬜ | CI = régression + WireCheck/ClientOracle |

---

## CHANTIER A — VÉRIFICATION GLOBALE (audit automatisé, 4 axes)

**Objectif utilisateur** : auditer TOUT le jeu d'un coup, via les outils d'industrialisation, pour vérifier —
1. qu'aucun **écran** n'a été oublié ;
2. que tout est bien **câblé** (chaque message envoyé par un écran a un handler serveur) ;
3. qu'aucune **valeur en dur** ne traîne dans le serveur alors qu'elle est dans le code du jeu / `.tab` / admin ;
4. qu'il n'y a pas d'**erreurs client** (scan des logs en jeu).
→ **les manques sont auto-loggés** dans des rapports `docs/AUDIT_*.md` pour suivi.

**Outil** : `tools/audit/audit.sh` (réutilise `tools/screentool` : `ModeGraph` + `ScreenContract`). Régénère les rapports.
Périmètre mesuré au départ : **179 écrans** client (`*Screen`), ~37 packages UI.

| Axe | Description | Outil | Rapport | Statut |
|---|---|---|---|---|
| **A1** | Inventaire exhaustif des écrans + couverture (aucun oublié) | `audit.sh a1` (unzip + `EXPLORATION.md`) | 📄 `docs/AUDIT_SCREENS.md` | 🚧 179 écrans listés, triage en cours |
| **A2** | Câblage : messages envoyés par écran non routés par `LoginServer` | `audit.sh a2` (`ScreenContract` §C) | 📄 `docs/AUDIT_WIRING.md` | ✅ 14 triés — 3 implémentés (invasion rank ✅ en jeu, blocked, save) ; reste = retiré/debug/étape4/différé/Phase2C/faible |
| **A3** | Valeurs en dur serveur qui devraient venir de `.tab`/jeu/admin | `audit.sh a3` (heuristique) | 📄 `docs/AUDIT_HARDCODED.md` | ✅ **0 candidat** (§4 respecté) |
| **A4** | Erreurs client : scan des logs en jeu (exceptions/NPE/GL) | `audit.sh a4` (parseur logs) | 📄 `docs/AUDIT_CLIENT_ERRORS.md` | ✅ **0 erreur non-bénigne** |
| **A5** | Couverture des `.tab` : data d'un mode/feature non câblé + orphelines + carte `.tab`→classe | `audit.sh a5` (carte bytecode) | 📄 `docs/AUDIT_TABS.md` | 🚧 272 `.tab` mappées → triage (voir notes) |

**Méthode par manque trouvé** : chaque entrée de rapport = `[GAP]` avec (écran/mode, message/valeur, gravité proposée,
piste de correction). On tranche ensuite un par un (§8 : corriger + vérifier EN JEU, ou documenter PARTIEL/NO-OP avec risque
dans `SHIMS.md`, ou décision utilisateur). Les faux positifs sont marqués `[OK-connu]` avec justification (ne pas re-signaler).

### Notes / décisions A

**A3 (valeurs en dur)** : ✅ 0 candidat détecté — le serveur ne code pas les valeurs de RÈGLE en dur (elles viennent de
`.tab`/bytecode) ; les défauts de config restent dans les outils `Admin*` (légitime). Discipline §4 tenue.

**A4 (erreurs client)** : ✅ 0 erreur non-bénigne sur 8 logs scannés. Bénins CONNUS confirmés (documentés) : stat-parse
`NumberFormatException ""`, `PatchTalent.PREDICTIVE_FORTIFICATION`, `black_market_merchant_drops.tab row 18 "Can't assign
auto weight → defaulting to 1"` (absorbé, BLACK_MARKET/MEGA_MART fonctionnels), trader INVASION, + env/layout/audio headless
(`XDG_RUNTIME_DIR`, `auto weight`, `sound not loaded`).

**A2 (câblage) — triage + RÉSOLUTION des 14 manques** (`AUDIT_WIRING.md`) — ÉTAPE 2 (2026-08-24, g173) :
| Message (émetteur) | Verdict | Détail |
|---|---|---|
| `GetGMemInvasionRankInfo` (InvasionRankingsScreen) | **✅ IMPLÉMENTÉ + EN JEU** | rang invasion par MEMBRE de guilde (données réelles `user_invasion`) ; `ServerInvasion.guildMemberRanking` + handler. Vérifié en jeu : onglet GUILD MEMBERS rend 2 membres (avant : LOADING). Les 2 autres onglets (User/Guild league) étaient déjà servis. |
| `GetBlockedList` (BlockedPlayersWindow) | **✅ IMPLÉMENTÉ** (headless+wire) | `BlockedList` VIDE — le blocage n'est pas implémenté (communautaire) → 0 bloqué = réponse FIDÈLE (pas un faux endpoint). `WiringGapsTest` + round-trip wire. In-game : fenêtre sociale (sous-menu), non pilotée — bas risque. |
| `GetUserSaveData` (SaveRestoreUserWindow) | **✅ IMPLÉMENTÉ** (headless+wire) | `UserSaveData{info,extra,individualUserExtra}` = sauvegarde du compte DU DEMANDEUR (= ce qu'on persiste, via `bootData()`), avec GARDE anti-fuite (userID doit être le sien). Données RÉELLES. `WiringGapsTest`. In-game : fenêtre settings, non pilotée — bas risque. |
| `GetHeist`, `StartHeist`, `KickHeistParticipant` (heist) | **[OK-connu]** | HEIST **retiré du jeu** (💤) — écran inatteignable, rien à câbler. |
| `GetChestConsumableHistory` (**Debug**ChestConsumablesScreen) | **[OUT OF SCOPE]** | écran **DEBUG dev-only**, non atteignable par le joueur. |
| `GetCodebaseAttackLogs` (CodebaseAttackLogScreen) | **[→ étape 4]** | fait partie du mode **codebase** (GAP feature A5) — à trancher avec la feature codebase. |
| `GetPrizeWallData` (PrizeWallScreen) | **[DIFFÉRÉ — feature event]** | Prize Wall = feature d'EVENT ; `PrizeWallState` n'a AUCUN état « inactif » (IN_PROGRESS/REWARD_PREVIEW/STAGE_COMPLETE) → **pas de réponse vide fidèle** ; renvoyer un faux mur violerait « pas de faux endpoint ». Nécessite un vrai builder d'event prize-wall (comme Contest/ExtraChest) → chantier live-ops ultérieur. `nav PRIZE_WALL` : à vérifier s'il hang ou est gaté. |
| `GetServers` (windows) | **[Phase 2 C]** | liste de serveurs = launcher multi-serveur (chantier C). |
| `RequestResync` (powerpromote/pvp/windows) | **[OK-faible]** | requête générique fire-and-forget (le serveur resync déjà après chaque mutation) ; pas de hang. Documenté, non implémenté. |

⇒ **Bilan A2 (étape 2)** : sur 14 — **3 IMPLÉMENTÉS** (invasion member rank ✅ en jeu ; blocked + save : réel/fidèle, headless+wire,
`WiringGapsTest`, régression **156/156**) ; **3 non-gaps** (HEIST retiré) ; **1 debug-only** (ChestConsumableHistory) ; **1 →
étape 4** (CodebaseAttackLogs) ; **1 différé** (PrizeWall = feature event, pas de faux endpoint) ; **1 Phase 2 C** (GetServers) ;
**3 faibles** (RequestResync, fire-and-forget). **Aucun faux endpoint créé** — seules des réponses RÉELLES ou FIDÈLES (vide =
état correct du serveur) ont été ajoutées. Re-run A2 : les 3 messages implémentés ont disparu des manques.

**A1 (inventaire)** : 179 écrans listés dans `AUDIT_SCREENS.md`. La majorité des MODES est déjà ✅ en jeu (cf.
`EXPLORATION.md`) ; le croisement fin « chaque écran individuel rejoué » se fait via le balayage en jeu (méthode §7.4 HUB_NAV).
Aucun MODE oublié détecté ; le reste = sous-écrans/onglets d'un mode déjà couvert (à cocher au balayage).

**A5 (couverture des `.tab`)** — `AUDIT_TABS.md` : **272 `.tab` sur disque, 265 référencées, 67 classes `Stats`**.
- **Q : le code du jeu associe-t-il chaque `.tab` à une partie du jeu ? → OUI.** Chaque `.tab` est déclarée par une classe
  `Stats` (le PACKAGE = le mode/feature). Carte complète auto-générée dans `AUDIT_TABS.md` (ex. `arena_*.tab`→`ArenaStats`,
  `battle_pass_v2_*`→`BattlePassV2Stats`, `invasion_*`→`InvasionStats`…).
- **Orphelines sur disque** : `content.{1,13,14,21,23,25,99}.tab` (⚠️ **PAS orphelines** — chargées par nom CONSTRUIT
  `content.<shard>.tab` via `ContentStats`) + `invasion_boss_rewards.tab` (loot tiré CÔTÉ CLIENT, §SHIMS #25 — normal).
- **~~Référencée mais absente : `unit_abilities.tab`~~ → RÉSOLU (2026-08-24, étape 1) : FAUX POSITIF de l'outil, PAS un gap.**
  L'APK ne livre `unit_abilities` et `friendship_campaign` qu'en variante **BINAIRE `.tabb`** (double « b » ; les 2 seuls
  binaires) — bien extraites sur disque. Le jeu les charge **binaire-d'abord** (`ServerStats.forceText()=false` → « essaie
  `.tabb` puis `.tab` » ; `StatFileHelper`). **Preuve** : `friendship_campaign.tabb` alimente la campagne d'amitié ✅ vérifiée
  en jeu → la résolution `.tabb` marche, donc `unit_abilities.tabb` l'est aussi. L'outil A5 flagguait à tort car (a) le regex
  `\.tab` tronquait `.tabb`, (b) le glob `*.tab` ratait `.tabb` → **corrigé** (regex `\.tabb?` + normalisation `.tabb`→`.tab`).
  **Besoin serveur** : le serveur ne nomme JAMAIS `AbilityStats`/`unit_abilities` (grep server/java = 0) → **client-only**
  (données d'aptitudes des héros, lues par la SIMULATION de combat côté CLIENT, qui est client-autoritative via unidbg ; le
  serveur ne rejoue que la progression `recordOutcome`, pas la simulation). Aucun ajout artificiel — le fichier est présent et
  chargeable si un chemin serveur venait à en avoir besoin. ⇒ **[OK-connu]**, non-gap.
- **⚠️ « Nommée serveur » est APPROXIMATIF** : beaucoup de features à `—` sont en réalité UTILISÉES via la logique du jeu que
  le serveur exécute (sans que notre glue nomme la classe) — **CONFIRMÉ implémentées** : `CampaignStats`, `FriendshipStats`/
  `FriendshipCampaignStats`, `CollectionStats` (CollectionClaim/Mastery/Avatar ✅), `GuildCheckInStats` (GuildCheckInTest ✅),
  `SpotlightTrialStats`/`TeamTrialsStats` (✅ en jeu), `EnchantingStats` (✅), `PrizeWallStats` (MEDALS ✅ via COMPLETE_QUEST),
  `PortStats`, `RealGearStats`, `ModStats`, `CosmeticCollectionStats`… → **faux négatifs**, à NE PAS re-signaler.
- **VRAIS candidats « data présente, mode/feature NON câblé »** (à trancher un par un — implémenter, ou documenter « hors
  scope/retiré » avec justification) :
  | Feature | Classe `Stats` (.tab) | Verdict / note |
  |---|---|---|
  | `heist` | `HeistStats` (4) | **[OK-connu]** mode RETIRÉ du jeu (EXPLORATION 💤) — data résiduelle, rien à câbler |
  | `codebase` | `CodebaseStats` (4) + `codebase_use` | **[GAP mode]** « The Codebase » non implémenté (cohérent A2 `GetCodebaseAttackLogs`) |
  | `campaign.reinfection` | `CampaignReinfectionStats` (4) | **[GAP feature]** variante campagne « reinfection » non câblée |
  | `chest.upgrade` | `ChestUpgradeStats` (3) | **[GAP feature]** pistes d'amélioration de coffre (chest upgrade tracks) |
  | `emerald` | `EmeraldStats` (4) | **[à vérifier]** gear/monnaie émeraude — les slots emerald sont déjà gérés en lineup ; la FEATURE dédiée ? |
  | `airdrop` | `AirDropStats` (2) | **[GAP feature]** air drop (récompenses larguées) |
  | `herospotlight` | `HeroSpotlightStats` (1) | **[à vérifier]** hero spotlight (distinct du spotlight trial ✅) |
  | `marketing`/`misc.Offerwall`/`video`/`content.StarterDeal`/`DeepLink`/`misc.DisneyEmoji`/`misc.SupportLinks` | (1 ch.) | **[hors scope]** marketing/IAP/périphérique (store FERMÉ, cf. audit store §7.3) |

  ⇒ **Vrais GAPS de mode/feature à décision** : `codebase`, `campaign reinfection`, `chest upgrade`, `airdrop`
  (+ `emerald`/`herospotlight` à vérifier). Les autres = retirés (heist) ou hors scope (marketing/IAP).

#### ÉTAPE 4 — triage factuel des GAPS features A5 (2026-08-24, g175) — CLASSIFICATION (aucune impl)

Pour chaque feature : data présente ? code client ? messages serveur ? exposée/jouable ? → verdict **IMPLEMENT / INVESTIGATE /
OUT OF SCOPE**. (Preuves : écrans UI, destinations `nav`, messages réseau, points d'entrée, usage serveur existant.)

| Feature | data (.tab) | code client | messages réseau | exposée / point d'entrée | verdict |
|---|---|---|---|---|---|
| **codebase** | ✅ 4 | ✅ **UI complète** (Attack/Detail/HeroChooser/AttackLog) | ✅ `CodebaseAttack`/`CodebaseWeakness`/`CodebaseAttackInfo`/`GetCodebaseAttackLogs`/`CodebaseMinorBuffType` | **nav permanente** via `Destination.TEAM_TRIALS` → `TeamTrialsChooserScreen.doCodebaseButtonPress` ; gate = chapitre 41 ; rotation 3 j déterministe (les Acts ne sont que le tutoriel) | **INVESTIGATE → voir étape 4bis (g176) : PARTIALLY RESTORABLE, effort MODÉRÉ.** _(⚠️ correctif : mode de DIFFICULTÉ rotatif, PAS un event scripté)_ |
| **emerald** | ✅ 4 | (stat de gear) | `EmeraldStatSlot`/`EmeraldStatTier` (enums) | **DÉJÀ UTILISÉ** côté serveur (`ServerUser.emeraldStatSlotChoices`, lineups) | **OUT OF SCOPE** — sous-système de STAT de gear **déjà intégré** (faux négatif A5 : chargé via la logique du jeu). Rien à faire. |
| **airdrop** | ✅ 2 | `AirdropHelper` (+`StatsProvider`), **aucun écran** | `AirDropClaimStatus` (1) | piloté par **stat-sync** (`SyncStatDataClientHelper`), pas d'UI joueur | **OUT OF SCOPE** — feature de config/stat-sync sans mode joueur ; pas d'écran ni d'action de réclamation exposée. |
| **campaign reinfection** | ✅ 4 | aucun écran | **aucun** | sous-mécanique de campagne (`REINFECTIONS_CLEANSED` = tâche contest) | **OUT OF SCOPE** — variante/modificateur de campagne ; passe par le chemin campagne existant (`recordCampaignAttack`), aucun travail serveur distinct. |
| **chest upgrade** | ✅ 3 | aucun écran | `ChestUpgradeTrackType` (enum) | pas de message/écran distinct | **OUT OF SCOPE** — data alimentant la logique de coffres ; aucune feature/handler distinct à câbler. |
| **herospotlight** | ✅ 1 | aucun écran | **aucun** | data-only (héros vedette) | **OUT OF SCOPE** — données seules (distinct du SPOTLIGHT_TRIAL, déjà ✅). |

⇒ **Bilan étape 4** : **1 INVESTIGATE** (`codebase` → investigué en étape 4bis/g176 = **PARTIALLY RESTORABLE, effort modéré**, mode
de difficulté rotatif) ; **5 OUT OF
SCOPE** (`emerald` déjà intégré ; `airdrop` stat-sync sans UI ; `reinfection`/`chest upgrade` sous-mécaniques sans message ;
`herospotlight` data-only). **0 IMPLEMENT immédiat.** Aucune logique serveur créée sur la seule existence d'une `.tab`/classe
`Stats` (consigne respectée : on distingue les vraies features restaurables des reliquats/data-only).
**Décision utilisateur** : veux-tu qu'on INVESTIGUE `codebase` plus loin (faisabilité complète d'une restauration event-mode) ?

#### ÉTAPE 4bis — INVESTIGATION APPROFONDIE `codebase` (2026-08-24, g176) — CLASSIFICATION : **PARTIALLY RESTORABLE (effort MODÉRÉ)**

Investigation factuelle complète (bytecode `libs/game.jar`, `.tab`, code serveur), même méthode que A2. **⚠️ Correction d'un
fait de l'étape 4** : Codebase n'est **PAS** un « mode événementiel scripté sans nav permanente ». C'est un **MODE DE DIFFICULTÉ
ROTATIF** (comme Team Trials / Spotlight / Port), les Acts n'étant que le TUTORIEL d'intro. Preuves ci-dessous.

**1. Point d'entrée exact.** Grep jar complet : seul `com.perblue.heroes.ui.screens.TeamTrialsChooserScreen` référence
`CodebaseDetailScreen` + `CodebaseHelper`. Le chooser expose un bouton Codebase (`doCodebaseButtonPress`, champs
`lastCodebaseOpen/lastCodebaseTime/nextCodebaseEndTime/onCooldownCodebase`, `TeamTrialsChooserScreen$CodebaseError`). Le chooser
est atteint par la destination nav **permanente** `UINavHelper.Destination.TEAM_TRIALS`. ⇒ **entrée = nav permanente**, pas un Act.
`CodebaseActV1`/`CodebaseIntroActV1` = actes de **tutoriel** (déclenchés par `TutorialHelper`), pas la porte d'activation.

**2. Nature « difficulty-mode » (décisif).** `GameMode.CODEBASE` existe ; `DifficultyModeHelper.getCooldownType(CODEBASE) =
CooldownType.CODEBASE_ATTACK` (voisin de `SPOTLIGHT_TRIAL_ATTACK`) ; `VIPFeature.CODEBASE_COOLDOWN`. ⇒ Codebase est traité
EXACTEMENT comme les autres modes de difficulté qu'on a déjà livrés (Port/Trials/Surge). `docs/PORT.md` = la référence de méthode.

**3. Activation / gating (data-gated, déterministe, PAS d'event opérateur requis).**
  - `isFeatureEnabled(IUser)` = `Unlockables.isUnlocked(getUnlockableForChapter(CodebaseStats.getRequiredCampaignChapter()))` →
    **chapitre de campagne 41** (`codebase_constants.tab: REQUIRED_CAMPAIGN_CHAPTER=41`). Verrou permanent, déblocable.
  - `isOpen(IUser)` = booléen court (fenêtre d'itération).
  - `getCurrentIterationID(long,int)` = **rotation déterministe** depuis `SCHEDULING_EPOCH=2035-02-08T12:00Z` + `AVAILABLE_DAYS=3`
    + `TimeUtil.computeTimeForDay` → itérations de **3 jours**, calculées de `serverTime`+data (comme la rotation Invasion/Surge).
    `getNextEndTime`/`getStartTimeOfCurrentIteration`/`getNextResetTime` fournissent les timers. **Aucun event AdminEvents requis.**

**4. Requêtes/réponses réseau — toutes ROUTABLES (enregistrées `MessageFactory`).**
  - `CodebaseAttack` (envoyée par `CodebaseAttackScreen`, sous-classe de `LootAttackScreen`) : `{base:AttackBase, codebaseID,
    weakness:CodebaseWeakness, minorBuffs:List, finalWeaknessCount, finalScore, megavirusTotalDamageTaken, attackEndTime,
    lootEarned:List}` → **combat client-autoritatif** (le client joue, envoie l'issue ; le serveur ré-exécute `recordOutcome`).
  - `GetCodebaseAttackLogs` → réponse `CodebaseAttackLogs {logs: Map<iterationID, CodebaseAttackLog>}` ;
    `CodebaseAttackLog {topScores:List<CodebaseAttackInfo>, recent:List<CodebaseAttackInfo>}` ;
    `CodebaseAttackInfo {lineup:List, rageLevel, score, attackTime}`. `CodebaseWeakness`/`CodebaseMinorBuffType` = enums.

**5. Mapping écrans → serveur.** `CodebaseDetailScreen` (overview : lit l'état user + timers, calcule le boss/faiblesse en local) ;
  `CodebaseHeroChooserScreen` (choix lineup, local) ; `CodebaseAttackScreen` (combat → **envoie `CodebaseAttack`**) ;
  `CodebaseAttackLogScreen` (**envoie `GetCodebaseAttackLogs`** → rend `CodebaseAttackLogs`, tri top/recent).

**6. Données/`.tab` nécessaires : TOUTES PRÉSENTES & chargeables.** `codebase_constants.tab` (SCHEDULING_EPOCH, AVAILABLE_DAYS=3,
  BASE_CHANCES=3, BATTLE_LENGTH=90, REQUIRED_CAMPAIGN_CHAPTER=41, DAMAGE_TO_SCORE_MULT, VULNERABILITY_*, MEGABIT_*_WEIGHT,
  ATTACK_LOG_MAX_*) ; `codebase_rage_levels.tab` ; `codebase_minor_buffs.tab` ; `codebase_iterations.tab` (WEAKNESS_OVERRIDE).
  Logique de jeu **entièrement présente et serveur-exécutable** : `CodebaseHelper.recordOutcome(...)` (autoritatif : valide cooldown
  `GAME_MODE_COOLDOWN`, chances `GAME_MODE_CHANCES_GONE`, héros jaune requis `CODEBASE_REQUIRES_YELLOW_HERO`, ouverture/verrou ;
  crédite le loot client-reporté ; `tryUpdateHighScores` ; `ContestHelper.onDifficultyModeAttack` ; `UserActivityTracker`),
  `makeMegaVirus(iter)`/`getMegaVirusWeakness(iter)`/`getMegaVirusLevel`, `CodebaseLootCalculator`, timing d'itération.

**7. État serveur pour reproduire le mode.**
  - **Progression per-user = DÉJÀ dans le modèle wire + AUTO-PERSISTÉE (write-through §3).** `IndividualUserExtra` porte
    `currentCodebaseID, currentCodebaseHighScore, currentCodebaseHighRageLevel, lifetimeCodebaseHighScore,
    lifetimeCodebaseHighRageLevel` ; `IndividualUser.setCurrentCodebaseHighScore` fait `extra.currentCodebaseHighScore = …`
    (putfield sur `extra`). ⇒ `recordOutcome`/`tryUpdateHighScores` persistent **sans nouvelle colonne** (au plus un `resync`).
  - **SEUL état manquant = le blob leaderboard/logs** (`CodebaseAttackLogs` = Map<iterationID,{topScores,recent}>), **serveur-
    autoritatif** — exactement le patron déjà fait pour Arena ladder / Invasion ranking / Surge (blob per-shard).

**8. Dépendance event/rotation/date/état :** rotation 3 j déterministe (serverTime+data), gate chapitre 41. **Aucun** déclencheur
  opérateur. Cohérent avec les modes déjà livrés (pas d'AdminEvents nécessaire ; `AdminClock` bougerait la rotation comme le reste).

**9. Traces dans NOTRE serveur :** grep `server/java` = **0** (seule mention `codebase` = `ServerEvents` sans rapport). Aucun handler
  partiel. `GetCodebaseAttackLogs` déjà listé comme GAP A2 (étape 2, différé ici). Donc : **rien de câblé, mais rien à défaire.**

**⇒ CLASSIFICATION : PARTIALLY RESTORABLE — effort MODÉRÉ (niveau d'un mode de difficulté type Surge/Trial, PAS un chantier majeur).**
Ce n'est **pas** un simple « unwire » (il manque un handler d'attaque + le blob leaderboard + l'exposition du bouton), mais il n'y a
**aucune règle ni donnée à réécrire/inventer** (§3/§4 tenables intégralement). Briques manquantes précises :

  | # | Brique manquante (serveur, glue uniquement) | Patron existant à copier | Effort |
  |---|---|---|---|
  | 1 | Handler `CodebaseAttack` : rebuild `User` du wire → `CodebaseHelper.recordOutcome(user, outcome, attackEndTime, codebaseID, lootEarned, minorBuffs, weakness-count coll., finalWeaknessCount, finalScore, snapshot)` → resync `individualUserExtra` codebase + `store.save` | `recordDifficultyModeAttack` (Port/Trials) + hook contest déjà en place | petit |
  | 2 | Blob leaderboard per-shard `CodebaseAttackLogs` (Map<iter,{topScores≤10, recent≤10}>) : alimenté à chaque `CodebaseAttack`, servi sur `GetCodebaseAttackLogs` ; bornes `ATTACK_LOG_MAX_TOP_ROWS/RECENT_ROWS=10` | Arena ladder / `ServerInvasion.guildMemberRanking` (blob per-shard + tri) | moyen |
  | 3 | Exposition du bouton Codebase dans le chooser Team Trials : fournir cooldown `CODEBASE_ATTACK` + timers d'itération (`getCurrentIterationID/getNextEndTime`) au client ; le reste (boss/faiblesse) est calculé client via `CodebaseHelper` | timers Port/Trials déjà servis | petit |
  | 4 | Vérif EN JEU (§8, obligatoire) : compte de test **débloqué au chapitre 41** (`REQUIRED_CAMPAIGN_CHAPTER`) — le seul vrai point dur logistique | outils admin (grant chapters / SetFlag / SkillSetup) | moyen (setup) |

Non-bloquants (déjà résolus par l'archi) : codec/routing (messages enregistrés), persistance progression (write-through),
intégration contest (`onDifficultyModeAttack` déjà branché via `ServerContestData.prepare`/deliver), rotation (déterministe data).

**Verdict final** : Codebase est **historiquement complet côté logique+data+UI** et **partiellement restaurable** avec un effort
**modéré** (2 petites briques + 1 blob leaderboard + setup de vérif chapitre 41), **sans réécrire aucune règle**. Ce n'est ni un
« simple câblage » (le blob leaderboard + le handler sont un vrai incrément), ni un « chantier majeur ».

#### ÉTAPE 4ter — RESTAURATION `codebase` (2026-08-24, g177) — DÉCISION UTIL. « Restaure » → **incr.1 LIVRÉ (headless 🟢)**

Briques 1-3 de l'étape 4bis **implémentées** (glue serveur, aucune règle réécrite ; détail `docs/CODEBASE.md`, `JOURNAL.md` g177) :
- **Brique 1** ✅ handler `CodebaseAttack` → `ServerUser.recordCodebaseAttack` → `CodebaseHelper.recordOutcome` (args relevés au
  bytecode du vrai appelant) + resync + `store.save`. Progression per-user auto-persistée (write-through `IndividualUserExtra`).
- **Brique 2** ✅ blob leaderboard per-shard `ServerCodebase` (`CodebaseAttackLogs` **clé = `CodebaseWeakness`**, top≤10/recent≤10
  bornes `.tab`) + handler `GetCodebaseAttackLogs` (gap A2 `GetCodebaseAttackLogs` CLOS).
- **Brique 3** ✅ le mode passe par `DifficultyModeHelper` (cooldown `CODEBASE_ATTACK`, timers d'itération déterministes) — exposé
  via le chooser Team Trials (rendu client).
- Test `CodebaseTest` (régression **157/157**) : classement (tri/bornes/wire/DB) + anti-triche réelle (`GAME_MODE_LOCKED`) + high
  score nominal (chapitre 41 + héros jaune → 0→750, persisté).

**Brique 4** ✅ **vérif EN JEU (§8) FAITE** (incr.2, g178) : `CodebaseUnlock` (TL 300 + chapitre 41 + héros JAUNE) → client réel →
pilote `codebaseattack` (VRAI `CodebaseAttack`, attaquant jaune réel) → serveur **1re acceptée** (recordOutcome + classement
persistés), **2e refusée `GAME_MODE_COOLDOWN`** ; DB relue : `currentCodebaseHighScore=500`/lifetime=500, classement per-shard
BLIND top=1/500. **⇒ CODEBASE RESTAURÉ & vérifié de bout en bout (client → serveur → persistance). Mode COMPLET.**

### ÉTAPE 3 — analyse `content.N.tab` : détermination de l'ère + faisabilité « choix d'ère » (2026-08-24, g174) — ANALYSE, PAS d'implémentation

**Comment l'ère est déterminée (faits, bytecode)** :
- `ContentStats extends TimeTable<String, ContentColumn>` : `getColumn(date)` = la colonne (release Rn) dont la date ≤ `date`
  (table = `content.<shard>.tab`, chargée par `ContentHelper.setShardID(shard, {})`).
- `getServerColumn()` = `getColumn(serverTimeNow())` ; `getServerColumn(IUser)` = `getColumn(serverTimeNow() + getUserOffset(userID))`.
  ⇒ **l'ère = résolution DATE→colonne** ; un offset de contenu PAR-USER existe nativement (`setUserOffset`/`getUserOffset`).
- Le serveur envoie `BootData.serverTime = serverTimeNow()` (ServerUser ~l.212).

**AdminClock vs AdminSeason (déjà en place)** :
- `AdminClock` (`CLOCK_OFFSET`→`serverTimeNow`) : bouge ÈRE (getServerColumn) + SAISON + **TOUS les timers joueur** + `BootData.serverTime`
  de façon COHÉRENTE (« le monde entier à la date X ») → client consistant. ✅ vérifié §8. *Effet* : impossible d'isoler l'ère des
  timers (viser la date de R95 recule aussi resets/cooldowns).
- `AdminSeason` (`SEASON_ANCHOR_OFFSET`→`seasonTimeNow`) : bouge UNIQUEMENT la sélection de saison des trials (interne serveur) ;
  **ne touche PAS l'ère de contenu**.

**Faisabilité + DÉPENDANCE CACHÉE** :
- **Levier natif** : mapper une release → sa date (ligne « dates » de `content.N.tab`) et régler l'horloge dessus = **wrapper mince
  au-dessus d'AdminClock** (release Rn → date → `CLOCK_OFFSET`). Aucun mécanisme nouveau requis.
- **⚠️ DÉPENDANCE CACHÉE** : `BootData.serverTime` pilote À LA FOIS (a) la résolution du CONTENU DATÉ côté CLIENT et (b) l'AFFICHAGE
  des timers (resets/cooldowns) côté client. Pas de champ « date de contenu » distinct de l'horloge dans `BootData`. ⇒ On ne peut PAS
  décaler le CONTENU sans décaler l'HORLOGE PERÇUE par le client (donc l'affichage des timers). Le serveur, lui, ENFORCE les timers
  sur son horloge réelle (g151) → gameplay non cassé, mais AFFICHAGE client des resets suivrait l'ère choisie.
- `ContentStats.setUserOffset` NE règle PAS le problème : il décale le contenu CÔTÉ SERVEUR seulement ; le client résout par
  `BootData.serverTime` (non décalé) → désynchro affichage (client R102 / serveur R95), sauf à décaler aussi `BootData.serverTime`
  (ré-introduit le couplage timers).

**VERDICT étape 3 (documenté)** — ⚠️ **CORRIGÉ le 2026-08-25 (g181), voir ci-dessous** :
- ~~Release-picker faisable UNIQUEMENT comme wrapper AdminClock (l'affichage timers suit l'ère)~~ ;
- ~~« Choix d'ère SANS toucher les timers » PAS réalisable sans modifs client (couplage via l'unique `BootData.serverTime`)~~.

**CORRECTIF g181 (le verdict ci-dessus était FAUX)** : le jeu N'utilise PAS que `BootData.serverTime` pour le contenu — il y a un
champ SÉPARÉ **`BootData.contentStatsTimeOffset`** que `GameMain` (boot) applique à `ContentStats.setUserOffset` +
`PatchStats.debugSetUserOffset`. Donc l'ère se décale **sans** toucher les timers, **sans modif client**. ⇒ Release-picker
**IMPLÉMENTÉ découplé** (`ServerContext.setContentOffsetMillis` → méta `content_offset_ms` → `bootData().contentStatsTimeOffset`),
outil `AdminRelease` (cf. `docs/RELEASE_PICKER.md`), test `ReleaseOffsetTest`. **Changer d'ère ne casse ni sauvegardes ni timers.**
AdminClock reste l'outil distinct pour déplacer *tout* le monde dans le temps (timers compris).

---

**`content.N.tab` — richesse pour l'admin (ta 2ᵉ question)** : c'est une **TimeTable d'ère** (colonnes = DATES de release
R102→R1 ; lignes = clés de contenu). Contient par release : **Max Chapter, Max TL, Max GL, Max Rarity, Max Trials/Port
Difficulty**, nœuds de chapitre (`CH_100_NODE_*`), exclusivités battle-pass, sorties de héros… Déjà UTILISÉE pour résoudre
l'ère (`ContentStats.getServerColumn(date)` ; `AdminClock`/`AdminSeason` décalent la date → change l'ère servie). **Piste
admin (chantier D)** : surfacer ces données pour **choisir l'ère/release** à servir (et donc le plafond de contenu — chapitres,
TL, raretés, difficultés) via le panneau opérateur, plutôt que par décalage de date brut. À planifier en D (panneau opérateur).

---

## Autres chantiers — sous-étapes (à engager après A)

### B. Performance (non destructive)
- [ ] Profilage combat (unidbg/frame) — mesures avant/après, A/B bit-fidèle.
- [ ] Opt.3 : backend spine **Java** certifié contre l'oracle unidbg (matrice héros/graines).
- [ ] Profilage serveur (sérialisation wire, verrous store↔user), warm-ups.
- [ ] Temps de boot client (assets/spine) — cache/lazy-load, mesuré.

### C. Front-end joueur (launcher)
- [ ] Liste de serveurs (favoris, ping, nb joueurs, version) + création/login multi-serveur.
- [ ] Redirection `ServerType.LIVE` → serveur choisi (aujourd'hui 127.0.0.1 en dur via `content_server.py`).
- [ ] UX self-hosting (« héberger » = lance serveur local + launcher).

### D. Backend/hébergement
- [ ] Panneau opérateur unifié (englobe Admin*).
- [ ] Multi-shard (création/isolation, bus inter-shard), découverte, observabilité, sauvegarde/restore, rechargement à chaud.

### E. Intégration APK
- [ ] Vrai client APK Android → serveur (redirection `/login`, boot, jeu).
- [ ] Parité desktop↔APK ; versionnage multi-APK.

### F. Tests inter-machines réels
- [ ] Réseau réel (latence/perte/MTU), NAT/pare-feu/UPnP, TLS, DNS/annuaire.
- [ ] Charge/stress (N joueurs × N shards), soak 24 h+, reconnexions.
- [ ] Multi-région (FR↔autre continent), sécurité réseau (rejeu/forge, rate-limit, bans).

### G. Qualité/outillage
- [ ] CI (régression + WireCheck/ClientOracle), reproductibilité build pinnée.
- [ ] Doc self-hoster (install/dépannage), cadre légal usage privé.
- [ ] Migration de sauvegardes inter-schémas (généraliser + tester).

---

## Journal des mises à jour de ce suivi
- **2026-08-24 (g170)** — création du doc de suivi + cadrage de l'audit global (chantier A, 4 axes) et de l'outil `tools/audit`.
