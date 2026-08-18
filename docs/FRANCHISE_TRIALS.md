# FRANCHISE_TRIALS / TEAM_TRIALS (#72) — sous-système « Trials » — recon COMPLÈTE + plan

> Recon menée au pipeline industriel #73/#74 (`contract.sh --mode`, `ModeGraph --logic`) **et** lecture bytecode
> COMPLÈTE (leçon g128/§8 : aucune conclusion sur lecture partielle). Chaque incrément : recon → logique du jeu (§3)
> → test headless (round-trip + DB, `WireCheck`) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## 0. Ce que le mode EST (recon bytecode, 2026-08-17)

Le « Trials » est le sous-système des **épreuves** (rangée 19 d'EXPLORATION : FRANCHISE_TRIALS / TEAM_TRIALS, gate
`Unlockable.TEAM_TRIALS` = **TL 55**). Il regroupe **DEUX familles** qui partagent le même cœur wire :

### Famille A — ÉVÉNEMENT / FRANCHISE TRIALS (piloté par un ÉVÉNEMENT SPÉCIAL)
- Un trial d'événement est **DÉFINI par un composant d'ÉVÉNEMENT SPÉCIAL** : `com/perblue/heroes/game/specialevent/
  TrialEventInfo` (implémente `IEventComponent`, comme `ModesOpen`/`DropBonus`), enregistré sous la clé **"trial"**
  (`SpecialEventsHelper` : `registerComponent("trial", new TrialEventInfoFactory())`). ⇒ **FRANCHISE_TRIALS est un
  composant du moteur SPECIAL_EVENTS** — il PROLONGE directement `ServerEvents` (même patron que `buildDropBonusEvent`).
- `TrialEventInfo` porte TOUTE la définition du trial (bytecode) : `getEnemyLevel/Rarity/Stars/Lineups` (config ennemis
  par sous-trial + `TrialEventDupeBehavior`), `getCombatModifiers`, **`getFranchises`** (franchise(s) imposée(s) — d'où
  « franchise trial »), **`getGatingCriteria`** (règles de héros autorisés), `getMultiWins`, `getChancesPerReset`,
  `getMaxDailyResets/PaidResets/MaxPaidDailyResets`, `getActiveDays`, `getCard{Image,Background,UnitType}`,
  `getHowToPlayText`, `getEnhancedPrimeBadgeLevelRequirement`.
- Runtime : `GenericTrial` (interface) construit depuis le `TrialEventInfo` ; nœuds = `GenericTrialNode`.
- Écrans : `TrialEventSubTrialChooserScreen` → `TrialEventHeroChooserScreen` → **`TrialEventAttackScreen`** (extends
  `LootAttackScreen`). Boîtes `FranchiseTrialEventBox`/`TrialEventBox`/`TrialEventSubTrialBox`.
- **Lien HÉROS PATCHÉS** : à la victoire, `PatchedHeroesHelper.handleFranchiseTrialCompletion` — la complétion d'un
  franchise trial alimente la progression **patched heroes** (d'où `getEnhancedPrimeBadgeLevelRequirement`). (PATCHED_HEROES
  est gaté TL185 — rangée 23 EXPLORATION 🔒 ; à vérifier si le franchise trial l'exige.)

### Famille B — TEAM TRIALS (BLUE/RED/YELLOW) + SPOTLIGHT (piloté par les DONNÉES + rotation par jour)
- `GameMode` : **`TEAM_TRIALS_BLUE` / `TEAM_TRIALS_RED` / `TEAM_TRIALS_YELLOW` / `SPOTLIGHT_TRIAL` / `EVENT_TRIAL`**.
- Data-driven : `game/data/teamtrials/TeamTrialsStats` / `SpotlightTrialStats` / `EventTrialStats` (+ `EventTrialEnemyConfigStats`).
- **Rotation par jour = DÉFAUT du jeu** (fait §8 g130) via `TrialsHelper.{SPOTLIGHT,BLUE,YELLOW,RED}_OPEN_DAYS` (branche du
  `switch` de `DifficultyModeHelper.getOpenDays`). Ouvrables hors planning via un override opérateur (`AdminEvents`, SPECIAL_EVENTS).
- Écran : `com/perblue/heroes/ui/screens/TeamTrialsChooserScreen` ; fenêtre `TeamTrialsInfoWindow`.
- **SPOTLIGHT** : état persistant DÉJÀ dans `individualUserExtra` — `spotlightTrialEventID:long` + `spotlightTrialUses:int`
  (getters/setters `IndividualUser.get/setSpotlightTrialEventID`/`Uses`, write-through).

## 1. Contrat wire (messages) — #73/#74

| Message | Sens | Rôle | Handler serveur |
|---|---|---|---|
| **`GetTrialEventData{ eventID:long }`** | client→serveur (requête) | demande l'état per-user du trial | **[MANQUE]** — à implémenter (défaut n°1 : sans lui, écran vide) |
| **`TrialEventData{ eventID, chancesUsed:int, dailyResetsUsed:int, lastChancesResetTime:long, paidChancesRemaining:int, paidResetsUsed:int, subtrials:Map<?,TrialEventSubtrialData> }`** | serveur→client (réponse) | état per-user (progression par sous-trial, chances, resets) | **builder ABSENT du client** (comme `ArenaInfo`/`MerchantData` — état backend PerBlue) → à construire+persister côté serveur |
| **`TrialEventAttack{ base:AttackBase, eventID, nodeNumber:int, subtrialNumber:int, stagesCleared:int, lootEarned:List<RewardDrop>, attackEndTime:long }`** | client→serveur (fire-and-forget) | issue de combat (client-autoritatif, façon `DifficultyModeAttack`) | **[à implémenter]** — rejouer l'issue : crédit loot + avance nœud/sous-trial + conso chance + persistance |
| Reset payant | via `Action`/`GenericTrial.doPaidReset` | reset des chances (coût `getResetCost(index)` en `getResourceCostType`) | à câbler |

- `TrialEventSubtrialData` = valeur de la Map `subtrials` (progression d'un sous-trial : nœuds complétés/étoiles).
  ⚠️ **Recon à finir (incr. 0)** : champs exacts de `TrialEventSubtrialData` (round-trip `WireCheck` OBLIGATOIRE avant tout).

## 2. Logique du jeu (§3 — points d'entrée à EXÉCUTER, jamais réécrire)
- **`ClientTrialEventHelper`** (statique, prend `GenericTrial`/`IUser`) : `isOpen(IUser, snap, GenericTrial)`,
  `getGameMode(GenericTrial)`, `getLineupType(GenericTrial)`, `getChancesLeft`/`getMaxChances`/`hasChancesLeft`/
  `noChancesRemaining(GenericTrial)`, `getSpotlightHero(GenericTrial, snap, IUser, int)`,
  `userCanMeetRequirements(IUser, GenericTrialNode)` (gating de héros), `getNextOpenDay(IUser, GenericTrial)`.
- **`TrialsHelper.resetTrialEvent(IUser, GenericTrial)`** (reset).
- **`SpotlightTrialHelper`** (famille SPOTLIGHT).
- **`GenericTrialNode.recordOutcome(...)`** (avance du nœud à l'issue — le CLIENT le fait localement ; le serveur doit
  ré-exécuter/mirroir sur SON état). **`BaseEventTrial.resetInit`**.
- **`PatchedHeroesHelper.handleFranchiseTrialCompletion(...)`** (récompense de complétion franchise → patched heroes).
- **`GenericTrial`** (interface) : `getOpenDays`/`getActiveDays`/`getChancesRemaining`/`getDailyResetsRemaining`/
  `getPaidChancesRemaining`/`getCooldownEndTime`/`getEndTime`/`getStartTime`/`getEventID`/`getResetCost`/`getResourceCostType`/
  `canUseResetItems`/`checkForDailyReset`/`doPaidReset`/`getQuestCategory`…

## 3. Données / persistance (§4/§6)
- **Définition du trial (Famille A)** : `TrialEventInfo` = composant d'un ÉVÉNEMENT SPÉCIAL → construit via la fabrique DU
  JEU `SpecialEventBuilder.createComponent("trial")` (comme `createComponent("dropBonus")`) puis `load` → **extension de
  `ServerEvents`** (`buildTrialEvent(...)`). ⚠️ **Recon à finir (incr. 0)** : contrat `TrialEventInfoFactory`/`TrialEventInfo.load`
  (schéma JSON riche : ennemis, modifiers, franchises, gating, sous-trials) — le plus gros inconnu de la famille A.
- **Définition du trial (Famille B, data-driven)** : `TeamTrialsStats`/`SpotlightTrialStats`/`EventTrialStats` (`.tab`,
  extraits par `extract_game_data.sh`) — pas d'événement requis, rotation `getOpenDays`.
- **État per-user** :
  - SPOTLIGHT : `individualUserExtra.spotlightTrialEventID`/`spotlightTrialUses` (write-through, DÉJÀ persisté).
  - Général (`TrialEventData` : chances/resets/subtrials) : **AUCUN champ direct dans `IndividualUserExtra`** → **blob
    SERVEUR-AUTORITATIF à ajouter** (per-user, per-event ; catégorie Arena ladder / Surge / Expedition run — état sans
    builder client, généré+persisté serveur). Table dédiée ou `shard_state`/colonne user (à décider incr. 1).
- **Horloge/rotation** : jours d'ouverture = horloge serveur (comme PORT) ; le défaut du jeu (`getOpenDays`) fait la rotation.

## 4. Lien avec SPECIAL_EVENTS (moteur déjà en place, g125-131)
FRANCHISE_TRIALS (famille A) **est** un composant SPECIAL_EVENTS (`TrialEventInfo`, clé "trial"). On RÉUTILISE le moteur :
- `ServerEvents.buildTrialEvent(id, …)` (nouveau builder, même patron que `buildModesOpenEvent`/`buildDropBonusEvent` :
  `createComponent("trial")` + `load`, provider/generics câblés §4).
- `AdminEvents --open-trial <franchise|config>` (nouvel override opérateur : planifier un franchise/event trial).
- ⇒ cohérent avec « on finira les autres composants SPECIAL_EVENTS quand FRANCHISE_TRIALS sera validé » : c'en est un.

## 5. Gate / conditions
- **`Unlockable.TEAM_TRIALS`** (TL 55) — gate réel du jeu, atteint via l'état légitime (`SetTeamLevel`), jamais désactivé (§8).
- Ouverture d'un mode trial : `ClientTrialEventHelper.isOpen` → `getOpenDays` (défaut jour) OU override event (SPECIAL_EVENTS).
- Gating de héros (`getGatingCriteria`/`userCanMeetRequirements`) : franchise imposée, rôle, etc. — validé côté serveur au record.

## 6. Plan d'incréments (proposé — à jouer par brique, vérif EN JEU à chaque étape)
0. **Recon-completion + contrat wire** : `WireCheck` round-trip `TrialEventData`/`TrialEventAttack`/`TrialEventSubtrialData`
   (typage Map/List, défaut n°3) ; contrat `TrialEventInfoFactory.load` (famille A) ; structure `TeamTrialsStats` (famille B) ;
   chemin de record serveur (`GenericTrialNode.recordOutcome` mirroir). **Aucune conclusion sans lecture complète (§8).**
1. **`GetTrialEventData` → `TrialEventData`** (cœur partagé) : construire l'état per-user (chances/resets/subtrials) depuis
   l'état persisté (défaut = frais), gate `TEAM_TRIALS`. Persistance blob serveur-autoritatif. Test round-trip + DB.
2. **`TrialEventAttack`** → record : `ServerUser.recordTrialEventAttack` — anti-triche (`isOpen`, chances, gating héros),
   crédit loot (client-reporté §4bis/#25), avance nœud/sous-trial (`subtrials`), conso chance, persistance. Test + DB.
3. **Reset** (quotidien gratuit + payant) : `resetTrialEvent`/`doPaidReset`, débit `getResourceCostType` × `getResetCost`.
4. **Famille B — TEAM_TRIALS/SPOTLIGHT (data-driven)** : `GenericTrial` depuis `TeamTrialsStats`/`SpotlightTrialStats`,
   rotation `getOpenDays` (défaut du jeu). SPOTLIGHT : write-through `spotlightTrial*`. **Candidat POINT DE DÉPART EN JEU**
   (pas de schéma d'événement riche à construire — plus simple à mener de bout en bout d'abord).
5. **Famille A — FRANCHISE/EVENT TRIAL (event-driven)** : `ServerEvents.buildTrialEvent` (composant "trial") + `AdminEvents
   --open-trial`. Le plus riche (schéma `TrialEventInfo`). Combat via le même `recordTrialEventAttack` (incr. 2).
6. **Complétion franchise** (`PatchedHeroesHelper.handleFranchiseTrialCompletion`) — récompense/patched heroes (vérifier le
   gate TL185 PATCHED_HEROES : la récompense peut être créditée même si l'écran patched heroes est verrouillé).
7. **Vérif EN JEU** de chaque brique (client réel → serveur → persistance → affichage), captures.

## 7. Points durs / questions ouvertes (à trancher avec l'utilisateur au fil des incréments)
- **Schéma JSON de `TrialEventInfo`** (famille A) : riche (ennemis, modifiers, franchises, gating, sous-trials). Voie objet
  via `createComponent("trial")`+`load` (comme la carte/DropBonus) ; à cadrer en incr. 0/5. Si trop lourd : commencer par la
  famille B (data-driven) et documenter la A.
- **Récompenses** : loot client-reporté (§4bis/#25) comme les autres modes de combat ; la complétion franchise = patched heroes.
- **`totalRequestedDonations`-like** décisions opérateur : a priori AUCUNE ici (tout vient de `TrialEventInfo`/stats).
- **Où persister le blob `TrialEventData`** : colonne user dédiée vs `shard_state`/table `user_trials` (per-user, per-event).

## 8. Incrément 0 — recon-completion (FAIT, 2026-08-17)
- **Contrat WIRE confirmé** (`server/smoke/TrialsWireTest.java`, régression) — défaut nº3 écarté par round-trip `writeAll`→`read` :
  - `TrialEventData.subtrials` = **`Map<Integer, TrialEventSubtrialData>`** (clé = numéro de sous-trial).
  - `TrialEventSubtrialData.nodeLevelStatuses` = **`Map<Integer, CampaignLevelStatus>`** (clé = numéro de nœud ; **chaque nœud =
    un niveau façon campagne** : `stars`/complétion/`totalWins`…). ⇒ un trial = des sous-trials, chacun = des nœuds « campagne ».
  - `TrialEventAttack{base:AttackBase, eventID, nodeNumber, subtrialNumber, stagesCleared, lootEarned:List<RewardDrop>, attackEndTime}`.
- **Hiérarchie d'objets** (`game/objects/trials/`) : interfaces `GenericTrial`/`GenericSubtrial`/`GenericTrialNode` ;
  implémentations **`BaseEventTrial`/`BaseEventSubtrial`/`BaseEventTrialNode`** (logique PARTAGÉE = serveur-utilisable §3) +
  `ClientEventTrial`/… (client). **Gating de héros** riche : `HeroFranchiseGatingCriterion`, `HeroCollectionGatingCriterion`,
  `HeroLevel/Rarity/Recency/Role/Stars/TeamGatingCriterion`, `SpecificHeroesGatingCriterion`, `CombinedGatingCriterion`.
- **Record (§3)** : `GenericTrialNode.recordOutcome(CombatOutcome, stagesCleared:int, Collection, Collection, Collection, time:long,
  snapshot)` + `rollDrops()` → le serveur **reconstruit** le trial (`BaseEventTrial…` depuis `TrialEventInfo`/stats + l'état
  persisté `TrialEventData`) et **exécute `recordOutcome`** (avance nœud/sous-trial, conso chance), puis re-sérialise vers
  `TrialEventData` — MÊME patron que `DifficultyModeHelper.recordOutcome` (PORT). Aucune règle réécrite.
- **Données extraites** (`game-data/stats/`) : `spotlight_trial_{constants,difficulties,enemies,enemy_config}.tab`,
  `event_trial_{arena_rules,constants,rewards}.tab`, `patched_heroes_{base_trial_config,franchise_trials_enemy_config}.tab`.
- **Chances** : `ClientTrialEventHelper.getChancesLeft/hasChancesLeft` → `GenericTrial.getChancesRemaining` (dérivé de
  `TrialEventData.chancesUsed` + `TrialEventInfo.getChancesPerReset`/resets). SPOTLIGHT : `spotlightTrialUses`.

**RESTE incr. 0 → à finir AU MOMENT de l'incrément concerné** (pas de reverse premature, mais méthodes exactes à lire ALORS,
COMPLÈTES) : signatures de construction `BaseEventTrial(...)` (incr. 1/2), contrat `TrialEventInfoFactory.load` JSON (incr. 5),
build d'un `GenericTrial` SPOTLIGHT depuis `SpotlightTrialStats` (incr. 4).

## 9. FAIT DÉCISIF (incr. 0, 2026-08-17) — TOUT trial EST un ÉVÉNEMENT SPÉCIAL → ordre des incréments RÉVISÉ
- **`ClientTrialEventHelper.createTrial(SpecialEventInfo)`** : le `GenericTrial` runtime est créé DEPUIS un `SpecialEventInfo`
  (composant `TrialEventInfo`, clé "trial"). ⇒ **il n'y a PAS de trial « data-driven sans event »** : SPOTLIGHT / TEAM / EVENT /
  FRANCHISE sont TOUS des événements spéciaux. `GetTrialEventData` est envoyé par `ClientTrialEventHelper`.
- **Conséquence** : rien n'est jouable EN JEU tant qu'un **event trial** n'est pas construit + poussé. Donc le PRÉREQUIS =
  **`ServerEvents.buildTrialEvent(...)`** (composant "trial", `createComponent("trial")` + `load`), PAS l'incr. 5. Le trial
  s'appuie sur le moteur SPECIAL_EVENTS déjà en place (g131) — cohérent avec « FRANCHISE_TRIALS est un composant SPECIAL_EVENTS ».
- **SPOTLIGHT = le plus SIMPLE** (server-usable `SpotlightTrialHelper`) : `getSpecialEvent(snap)`, `isSpotlightTrialActive(user,
  snap)`, `getTotalEventUses`/`setTotalEventUses(user, n, snap)`, `onSpotlightTrialUse(user, snap)`, `rollLoot(user, snap)`.
  État per-user = **compteur unique `spotlightTrialUses`** (DÉJÀ persisté `individualUserExtra`), PAS la structure `subtrials`
  riche. ⇒ **candidat POINT DE DÉPART** une fois un event trial constructible.
- **Schéma `TrialEventInfo.load(info, full, node)`** (clés JSON relevées) : `kind`, `nodeCount`, `nodeText`, `enemyLevel`,
  `enemyRarity`, `enemyStars`, `enemyLineups`, `enemyLineupDups`, `combatModifiers`, `combatModifierDups`, `chances`,
  `chancesBehavior` (DAILY/FIXED/UNLIMITED), `activeDays`, `day`, `gatingCriteria`, `gatingCriteriaDups`, `multiWins`,
  `background`, `image`, `preset`, `presetLineups`, `questCategory`, `environment`, `enableRaiding`, `enableStatSlots`,
  `resetStaminaCost`, `maxDailyResets`, `maxPaidDailyResets`, `maxPaidResets`, `enhancedPrimeBadgeLevelReq`/`patchLevelReq`/
  `primeBadgeLevelReq`. Riche mais STRUCTURÉ → constructible (patron `buildDropBonusEvent`). ⚠️ sous-schémas (types de
  `enemyLevel`/`gatingCriteria`, valeurs d'enum) à lire COMPLETS au build (incr. 1-révisé).

## Plan RÉVISÉ (trial = event)
1. **`ServerEvents.buildTrialEvent(...)`** (PRÉREQUIS) : construire un event trial MINIMAL mais VALIDE (via `createComponent
   ("trial")` + `load` d'un JSON `TrialEventInfo` minimal : `kind`, `nodeCount`, ennemis fixes, `chances`, `activeDays`, sans
   gating) → l'injecter (`ServerEvents.install`) → `isSpotlightTrialActive`/`isOpen`=true headless. + `AdminEvents --open-trial`.
2. **`GetTrialEventData` → `TrialEventData`** : servir l'état per-user (SPOTLIGHT : `spotlightTrialUses` ; général : blob
   subtrials). Handler `LoginServer`.
3. **`TrialEventAttack`** → record (`GenericTrialNode.recordOutcome` reconstruit / `SpotlightTrialHelper.onSpotlightTrialUse`
   + `rollLoot`), crédit loot, conso chance, persistance.
4. **Reset** (quotidien/payant). 5. **Gating héros** (`userCanMeetRequirements`) + franchises. 6. **Complétion franchise**
   (`PatchedHeroesHelper`). 7. **Vérif EN JEU** par brique (dès qu'un event trial est poussé au client).

## 10. POINT DUR (incr. 1-révisé, 2026-08-17) — construire le `TrialEventInfo` (schéma riche, PAS de vérité terrain client)
- **Fait établi** : le trial jouable = un `SpecialEventInfo` avec composant "trial" (`TrialEventInfo`). Le client ne fait que le
  LIRE (`createTrial(eventInfo)`) — **il n'embarque AUCUN JSON d'event trial** (recon : ni assets, ni jar, ni tutoriel [= texte
  UI seulement] ; ils venaient du backend PerBlue). `EventPresets.properties` fournit un preset wildcard vide `*.trial.none`
  (comme `*.eventCard.none`) → utilisable pour `preset`, mais PAS la définition du trial.
- **Schéma `TrialEventInfo.load`** riche et NICHÉ (objets à discriminateur `kind` : `chancesBehavior:{kind:DAILY/FIXED/UNLIMITED}`,
  `image:{kind:MATCH/IMAGE/UNIT,…}`, `activeDays:{day:…}`, `enemyLevel/Rarity/Stars/Lineups` = listes typées, `gatingCriteria`,
  `combatModifiers`, `chances`:int, `nodeCount`, `resetStaminaCost`/`resourceCostType`, `maxDailyResets`/`maxPaidResets`…).
  ⇒ **le construire À LA MAIN = l'anti-pattern proscrit** (mur `eventCardDisplay`, « industrialisé = pas à la main »).
- **Données du jeu disponibles (§4)** : `spotlight_trial_{constants,difficulties,enemies,enemy_config}.tab`,
  `event_trial_{arena_rules,constants,rewards}.tab`, `patched_heroes_{base_trial_config,franchise_trials_enemy_config}.tab` —
  contiennent la config RÉELLE (ennemis, difficultés, récompenses) qu'un event référence, mais PAS la structure d'event elle-même.

### Options (décision utilisateur — comme le point dur SPECIAL_EVENTS)
- **(A) Object-path INDUSTRIEL** : `createComponent("trial")` + remplissage GÉNÉRIQUE PAR TYPE des champs de `TrialEventInfo`
  (patron `buildMinimalCard` : listes→élément valide, int→défaut sensé, enum→défaut, preset→"none"), en s'appuyant SUR les `.tab`
  pour le contenu ennemis (extrait, non inventé §4). Avantage : pas de JSON à la main, réutilise la fabrique du jeu. Risque : le
  trial produit est « minimal/synthétique » tant que le contenu n'est pas tiré finement des `.tab`.
- **(B) Reconstruire depuis les `.tab` (le plus fidèle §4)** : assembler un `TrialEventInfo` SPOTLIGHT à partir de
  `spotlight_trial_*.tab` (ennemis/difficultés/récompenses réels du jeu) — le contenu vient à 100 % de la donnée du jeu.
  Plus lourd (mapper chaque `.tab` → champ), mais 0 invention.
- **(C) Vérité terrain externe** : si l'utilisateur peut fournir un VRAI JSON d'event trial (capture backend/version), on
  construit dessus (fidélité maximale, comme l'aurait fait le serveur d'origine).
- **(D) PAUSE trials, faire d'abord les composants SPECIAL_EVENTS simples** (discounts/Contest — même moteur), revenir aux
  trials ensuite. (La recon trials reste acquise et documentée.)

**Reco** : (A) object-path industriel adossé aux `.tab` pour un premier trial jouable EN JEU (dérisque tout le reste :
`GetTrialEventData`/`TrialEventAttack`/spotlight uses), puis affiner le contenu via (B). (C) si une vérité terrain est dispo.

## 11. FAIT DÉCISIF (incr. 1, 2026-08-17) — SPOTLIGHT/TEAM = DifficultyModes (RÉUTILISENT PORT) ; seuls EVENT/FRANCHISE = TrialEventInfo
Vérifié au bytecode (§8) : **`DifficultyModeHelper.getOpenDays` gère `TEAM_*` et `SPOTLIGHT`** (branches `TrialsHelper.*_OPEN_DAYS`) ;
`SpotlightTrialHelper.getSpecialEvent` cherche un event **MODES_OPEN ouvrant `GameMode.SPOTLIGHT`** et `isSpotlightTrialActive` =
`DifficultyModeHelper.isOpen(GameMode.SPOTLIGHT)` + `SpotlightTrialStats` (`.tab`) ; **`TeamTrialsChooserScreen` utilise
`DifficultyModeHelper` + le MÊME `ModePreviewScreen` que PORT**. ⇒ **REVISION MAJEURE** :
- **TEAM_TRIALS_{BLUE,RED,YELLOW} + SPOTLIGHT_TRIAL = des DifficultyModes** (comme PORT_DOCKS/WAREHOUSE). Ils réutilisent
  **TOUTE l'infra PORT déjà livrée** : ouverture via MODES_OPEN (`AdminEvents --open TEAM_TRIALS_BLUE`) OU rotation `getOpenDays`
  par défaut ; combat `DifficultyModeAttack` → `ServerUser.recordDifficultyModeAttack` (mode = paramètre) ; planning
  `ModePreviewScreen`/`DifficultyModeHeroChooserScreen`. **Aucun nouveau code combat** (comme WAREHOUSE). SPOTLIGHT ajoute la
  conso `spotlightTrialUses` (`SpotlightTrialHelper.onSpotlightTrialUse`) — à câbler si besoin.
- **EVENT_TRIAL / FRANCHISE trials = le seul sous-système NOUVEAU** (riche `TrialEventInfo` + `GetTrialEventData`/
  `TrialEventAttack`/subtrials). C'est là que `ServerEvents.buildTrialEvent` (object-path, option A — LIVRÉ) s'applique.
  ⚠️ `ClientTrialEventHelper.createTrial` est CLIENT-only (envoie `GetTrialEventData` → NPE headless) → vérif via serveur.

### Plan RE-RÉVISÉ (par ordre de simplicité)
1. **TEAM_TRIALS_BLUE via le chemin PORT (QUICK WIN)** : `AdminEvents --open TEAM_TRIALS_BLUE` → `isOpen` → combat
   `recordDifficultyModeAttack` (déjà en place). Test headless + **vérif EN JEU** (vitrine `TeamTrialsChooserScreen`/`ModePreview`
   → ENTER → combat → victoire → `recordOutcome [persisté]`). Zéro nouveau code serveur (validation de la réutilisation).
2. **SPOTLIGHT_TRIAL** : idem + conso `spotlightTrialUses` (`SpotlightTrialHelper.onSpotlightTrialUse`) si le combat ne la fait pas.
3. **EVENT_TRIAL / FRANCHISE** (le vrai nouveau) : `buildTrialEvent` (TrialEventInfo, contenu ennemis tiré des `.tab` §4) +
   `GetTrialEventData`→`TrialEventData` (blob) + `TrialEventAttack` record + subtrials + gating + complétion `PatchedHeroesHelper`.
   Le plus lourd — après validation de 1-2.

## 12. TEAM_TRIALS_BLUE ✅ VÉRIFIÉ EN JEU (2026-08-18, g134) — le QUICK WIN validé de bout en bout
- **Headless** (`server/smoke/TeamTrialsAttackTest.java`, régression 128) : `getOpenDays(BLUE)=[7,4,1]` (branche `TrialsHelper.BLUE_OPEN_DAYS`,
  même `switch` que PORT — vérifié au bytecode) ; ouverture via override opérateur MODES_OPEN (`ServerEvents`, chemin `AdminEvents --open`) ;
  combat `DifficultyModeAttack` → `recordDifficultyModeAttack` → `recordOutcome` (+6000 GOLD + cooldown `TEAM_TRIALS_BLUE_ATTACK`) ; persistance wire+DB.
  **ZÉRO nouveau code combat** (le `GameMode` n'est qu'un paramètre).
- **⚠️ FAIT §8 (lecture COMPLÈTE de `isOpen`)** : `isOpen` calcule le jour-de-semaine depuis **`snapshot.snapshotTime`** (PAS `serverTimeNow()`) →
  `SpecialEventSnapshot.NONE` (snapshotTime≈epoch) donne un jour SANS rapport avec le jour serveur. C'était un défaut de déterminisme LATENT des
  3 tests PORT (choix/vérif d'ouverture avec NONE = faux positif jour-epoch, puis `recordOutcome` échouait le VRAI jour hors rotation). **Corrigé** :
  ouverture DÉTERMINISTE via override opérateur + assertion avec `ServerEvents.snapshot()` (temps réel, le même que `recordOutcome`).
- **✅ EN JEU (id=1, TL200)** : `AdminEvents --open TEAM_TRIALS_BLUE` (persisté shard) → client `isOpen(snapClient)=true` → pilote `portpress
  TEAM_TRIALS_BLUE` → **`ModePreviewScreen` « BLUE TEAM » rendu** (mêmes ennemis lvl 15 que `team_trials_blue_enemies.tab` : MR_INCREDIBLE/NICK_WILDE,
  LOOT) → `portpreviewattack` → `DifficultyModeHeroChooserScreen` (5 héros Blue Team) → `portteam` → **combat AUTO → VICTOIRE → REWARDS (coffre +
  Hero XP ×5)** → serveur **`DifficultyModeAttack : TEAM_TRIALS_BLUE diff=1 outcome=WIN → recordOutcome appliqué [persisté]`** → **DB : cooldown
  `TEAM_TRIALS_BLUE_ATTACK` ACTIF (+499s), combat persisté**. Captures `build/tt_preview.png` / `tt_result.png`. **Même infra PORT (ModePreviewScreen/
  DifficultyModeHeroChooserScreen/recordOutcome), confirmée EN JEU.** ⇒ TEAM_TRIALS_{RED,YELLOW} = MÊME code (mode=paramètre) ; SPOTLIGHT ajoute la
  conso `spotlightTrialUses`. Pilote `teamtrialsscreen` (planning côté client, NON-fatal — ne pousse pas le `TeamTrialsChooserScreen` brut dont
  `updateScreenUI` exige un `cardContent` bâti par le cycle show()).

## 13. LES 3 COULEURS ✅ VÉRIFIÉES EN JEU + fidélité §4bis confirmée vs la communauté (2026-08-18, g135)
- **RED + YELLOW ✅ EN JEU** (après BLUE) — même chemin exact (`portpress <MODE>`→`ModePreviewScreen`→`portpreviewattack`→
  `DifficultyModeHeroChooserScreen`→`portteam`→combat→VICTOIRE→REWARDS) → serveur `DifficultyModeAttack : TEAM_TRIALS_{RED,YELLOW}
  diff=1 outcome=WIN → recordOutcome appliqué [persisté]`. **Chaque couleur restreint les héros à SA team** (gating vérifié EN JEU :
  les 5 héros fieldés diffèrent visiblement par couleur ; message d'écran « Heroes from the {Blue,Red,Yellow} Team battle in this
  mode! »), et donne un **Badge Bit** distinct (coffre bleu / chariot rouge / pêche jaune = les `SHARD_*` de `team_trials_*_loot.tab`).
  Setup : `TeamTrialsRosterBoost` (roster complet RED 100 6★ pour couvrir les 3 couleurs) + `AdminEvents --open TEAM_TRIALS_{RED,YELLOW}`.
- **Fidélité §4bis (vérité-terrain communauté)** : `getOpenDays` (extrait des `.tab`) correspond EXACTEMENT au wiki (numérotation
  `1=Dim…7=Sam`) : RED `{6,3,1}`=Dim/Mar/Ven ✓, BLUE `{7,4,1}`=Dim/Mer/Sam ✓, YELLOW `{5,2,1}`=Dim/Lun/Jeu ✓. `SPOTLIGHT_TRIAL`
  `getOpenDays=[]` (aucun jour par défaut → purement event-driven, cohérent §11). Récompense = Badge Bits (confirmé). NB gate : wiki
  dit TL20, notre `.tab` v12.1.0 = `Unlockable.TEAM_TRIALS` TL55 (écart de version ; notre valeur fait foi pour 12.1.0).
- **Leçon piloting** : entre deux combats, TOUJOURS revenir au hub (`nav HOME`) et laisser l'écran REWARDS se fermer AVANT de relancer
  un `portpress` — sinon le combat se lance par-dessus l'overlay REWARDS non fermé et le rendu se fige (faux « hang » = erreur de
  séquençage, PAS un bug moteur : YELLOW rejoué proprement seul → WIN immédiat).

## 14. SPOTLIGHT_TRIAL ✅ VÉRIFIÉ EN JEU (2026-08-18, g136) — 2ᵉ QUICK WIN (recordOutcome + spotlightTrialUses)
- **Fait §8 (bytecode)** : `recordOutcome`/`recordRaidOutcome` (offset 206/166) appellent EUX-MÊMES `SpotlightTrialHelper.onSpotlightTrialUse`
  (incrémente `spotlightTrialUses` = `getTotalEventUses`+1, clé = eventID). SPOTLIGHT est un DifficultyMode AUTO-SUFFISANT (cooldown
  `SPOTLIGHT_TRIAL_ATTACK`, VIP `SPOTLIGHT_TRIAL_COOLDOWN`) → **zéro nouveau code combat**. `getOpenDays(SPOTLIGHT)=[]` (event-driven
  pur) → ouvrable QUE par override MODES_OPEN (`SpotlightTrialHelper.getSpecialEvent` cherche justement un MODES_OPEN ouvrant SPOTLIGHT).
- **Aucun hardcode (§4)** : SPOTLIGHT n'a qu'UNE difficulté valide par shard = `SpotlightTrialStats.getDifficultyForShard(shardID)` (=SIX
  pour shard 1) — `isVisible` l'EXIGE (diff ≠ celle du shard → GAME_MODE_LOCKED). Héros vedette = `SpotlightTrialStats.getSpotlightHero()`
  (=FOZZIE, `spotlight_trial_constants.tab: SPOTLIGHT_HERO`). Les DEUX lus du jeu, jamais en dur.
- **Headless** `server/smoke/SpotlightTrialTest.java` (régression 129) : override MODES_OPEN → `isOpen`+`isSpotlightTrialActive`=true ;
  combat WIN (diff lue) → butin + cooldown + `spotlightTrialUses` 0→1 ; persistance wire+DB.
- **✅ EN JEU (id=1)** : `AdminEvents --open SPOTLIGHT_TRIAL` → `portpress SPOTLIGHT_TRIAL` (isOpen=true) → **`ModePreviewScreen` « HAPPY
  ANNIVERSARY! Earn some Hero Chips by helping Fozzie fight the Creeps »** (héros vedette FOZZIE, ennemis lvl 240 = diff SIX, LOOT = chips
  FOZZIE 0/100) → `portpreviewattack` → `portteam` (**3 héros** = wiki « team of 3 total », FOZZIE auto-maxé niv 565) → combat AUTO →
  VICTOIRE → REWARDS (**chips FOZZIE ×2** + 3 héros niv 565 auto-maxés) → serveur `DifficultyModeAttack : SPOTLIGHT_TRIAL diff=6 WIN →
  recordOutcome appliqué [persisté]` → **DB `spotlightTrialUses`=1 + `spotlightTrialEventID`=1795779**. Captures
  `build/tt_spotlight_{preview,result}.png`. Conforme au wiki (équipe de 3, héros maxés en combat, chips du vedette, pas de raid
  [`canRaid=isRaidingAllowed`=false]).

## 15. EVENT / FRANCHISE trials — recon COMPLÈTE du sous-système NOUVEAU + feasibility PROUVÉE + plan (2026-08-18, g137)
Le seul sous-système « trial » restant, distinct des DifficultyMode-trials (§12-14). **Serveur-autoritatif à blob** (patron
Arena/Surge/Expedition : état per-user sans builder client — `GameMain` REÇOIT `TrialEventData`).

### Cartographie (bytecode COMPLET, §8)
- **Flux wire** : client envoie `GetTrialEventData{eventID}` → **serveur** répond `TrialEventData{chancesUsed, dailyResetsUsed,
  lastChancesResetTime, paidChancesRemaining, paidResetsUsed, subtrials:Map<Integer,TrialEventSubtrialData>}` (**handler MANQUANT**) ;
  `TrialEventSubtrialData{nodeLevelStatuses:Map<Integer,CampaignLevelStatus>}`. Combat client-autoritatif → client envoie
  `TrialEventAttack{base, eventID, nodeNumber, subtrialNumber, stagesCleared, lootEarned:List<RewardDrop>, attackEndTime}`
  (**handler MANQUANT** ; construit par `ClientNetworkStateConverter.getTrialEventAttack`).
- **Structure vs état** : la STRUCTURE (subtrials, nœuds, ennemis, franchises, gating, chances/resets) = le composant d'event
  `game.specialevent.TrialEventInfo` (clé "trial", `TrialEventInfoFactory`) ; l'ÉTAT per-user = le blob `TrialEventData`. Le
  runtime = `ClientEventTrial extends BaseEventTrial` (+ `ClientEventSubtrial`/`ClientEventTrialNode`) : `setUserData(TrialEventData)`,
  `getChancesRemaining` (lit `userData.chancesUsed` vs `getChancesPerReset`), `getSubtrials()`.
- **Logique serveur-utilisable (§3)** : **`BaseEventTrialNode.recordOutcome(CombatOutcome, stagesCleared:int, Collection,
  Collection, Collection, time:long, snapshot)`** existe et fait `ICampaignLevelStatus.setStars(...)` = la transition d'état
  AUTORITATIVE (avance le nœud, façon campagne). `BaseEventTrial.checkForDailyReset`/`doDailyReset`/`doPaidReset` = chances/resets.

### ✅ FEASIBILITY PROUVÉE (spike headless, /tmp/TrialFeasSpike, jetable)
**`new ClientEventTrial(user, eventInfo)` se construit CÔTÉ SERVEUR sans réseau ni GL** (ctor vérifié au bytecode : `BaseEventTrial.init()`
+ build subtrials + `DailyActivityHelper`, AUCUN `sendMessage`/`Gdx`). Lu OK : `eventID`, `getChancesPerReset`. ⇒ **le serveur peut
EXÉCUTER la vraie logique du jeu** (`ClientEventTrial` + `node.recordOutcome`), §3-conforme — PAS de réimplémentation. (`createTrial`
du helper reste client-only car IL envoie `GetTrialEventData` ; le ctor, lui, est pur.)

### ⚠️ POINT DUR CONFIRMÉ (§10) — la STRUCTURE du trial est BACKEND-AUTHORED, pas dans les `.tab`
Le spike montre `subtrials=0` : le filler générique de `buildTrialEvent` (g133) ne peuple PAS les nœuds. Or **`EventTrialStats`
(`event_trial_*.tab`) ne contient QUE les RÉCOMPENSES + arena-rules + constantes — PAS la structure** (ennemis/nœuds/franchises).
La structure d'un event trial venait du **JSON d'event du backend PerBlue** (comme la carte `eventCardDisplay`). ⇒ **option B (reconstruire
la structure depuis les `.tab`) NON VIABLE**. Forks réels (décision utilisateur, comme SPECIAL_EVENTS) :
- **(A) Synthèse object-path d'une structure MINIMALE VALIDE** : peupler programmatiquement `TrialEventInfo` (nodeCount≥1, 1 subtrial,
  ennemis tirés d'une source du jeu — campagne/`.tab` existants, §4 : extraits, pas inventés ; franchise choisie). Trial JOUABLE mais
  « synthétique ». Risque : bien cadrer les ennemis pour rester fidèle (pas d'invention).
- **(C) Vérité terrain** : un VRAI JSON d'event trial (capture backend/version) → fidélité maximale, 0 invention. Idéal si dispo.
- **(D) PAUSE** : les 4 DifficultyMode-trials sont livrés+vérifiés EN JEU (le gros du mode « Trials ») ; passer aux composants
  SPECIAL_EVENTS restants, revenir à EVENT/FRANCHISE avec une vérité terrain.

### Plan d'incréments EVENT/FRANCHISE (une fois le fork tranché)
0. ✅ wire (`TrialsWireTest`) + ✅ feasibility (spike). 1. **Structure** : `TrialEventInfo` avec ≥1 subtrial/nœud (fork A ou C) →
   `ClientEventTrial.getSubtrials()>0`. 2. **`GetTrialEventData`** : blob per-user serveur-autoritatif (défaut frais) + persistance +
   handler `LoginServer`. 3. **`TrialEventAttack`** : `node.recordOutcome` (avance `nodeLevelStatuses`) + conso chance + loot client-reporté
   + persistance. 4. resets (quotidien/payant `doDailyReset`/`doPaidReset`). 5. gating héros (`getGatingCriteria`) + franchises. 6.
   complétion franchise (`PatchedHeroesHelper.handleFranchiseTrialCompletion`). 7. vérif EN JEU.

## Statut : incr. 1 ✅ **TEAM_TRIALS_{BLUE,RED,YELLOW} + SPOTLIGHT_TRIAL VÉRIFIÉS EN JEU** (4 DifficultyMode-trials, réutilisent PORT). EVENT/FRANCHISE : recon COMPLÈTE + feasibility PROUVÉE (serveur construit `ClientEventTrial`) ; **POINT DUR = source de la structure du trial → décision utilisateur (A synthèse / C vérité terrain / D pause)**.
