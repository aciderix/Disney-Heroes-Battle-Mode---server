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

## 16. CORRECTION §8 (g138) — la structure des FRANCHISE trials EST data-driven (`.tab`), PAS backend-authored
⚠️ **Le §15 concluait à tort** que la structure venait du backend PerBlue (lecture partielle : je n'avais regardé que `EventTrialStats`,
pas `PatchStats`). **FAIT (données extraites + `PatchStats`)** : la structure des **FRANCHISE trials est ENTIÈREMENT dans les `.tab`** —
- `patched_heroes_franchise_season_mapping.tab` : **calendrier des SAISONS** (colonnes = dates de début) → `TRIAL$0_FRANCHISE_0`
  (= la franchise vedette PAR SAISON : WILDCARD/CARS/FROZEN/THE_LION_KING/KIM_POSSIBLE/…), `TRIAL$0_ACTIVE_DAYS` (Lun/Jeu/Dim), patch caps.
- `patched_heroes_base_trial_config.tab` : `NODE_COUNT=14`, `WAVE_COUNT=3`, `MAX_DAILY_RESETS=60`, `FRANCHISES`, gating (`PRIME_BADGE_LEVEL_REQ`…),
  `ENABLE_RAIDING`, `ENABLE_STAT_SLOTS`.
- `patched_heroes_franchise_trials_enemy_config.tab` : **14 stages** (stars/rarity/level/rewards) — Badge Bits (bas) → **PATCH_ESSENCE** (haut).
- Ennemis = héros de la franchise (`PatchStats.getFranchiseTrialEnemyPoolForSeason` / `HeroHelper.getAllHeroesInFranchise`).
- Logique du jeu PRÊTE (§3) : `PatchStats.getPatchableFranchisesForSeason`/`getFranchiseTrialEnemyPoolForSeason`/`getGameModeFranchises`/
  `getFranchiseTrialsStageNumber` ; `PatchedHeroesHelper.franchiseTrialsUnlocked`/`handleFranchiseTrialCompletion`/`getPatchEssenceTier`/
  `spendPatchEssence`. (Pas de `GameMode.FRANCHISE_TRIALS` : ce sont de PURS event trials `TrialEventInfo`, umbrella `EVENT_TRIAL`.)

⇒ **AUCUNE invention, AUCUN JSON backend requis (§4)** : on construit le franchise trial en EXÉCUTANT `PatchStats` (franchises de la saison +
config ennemis/stages + héros de franchise). **Rôle admin = activer/planifier** (décision utilisateur « défini par le serveur → à l'admin de
les définir ») — fidèle au calendrier de saison du jeu, override admin possible (patron SPECIAL_EVENTS `AdminEvents`).

### Plan RÉVISÉ (fully faithful, data-driven — plus de fork « invention »)
1. **Build `TrialEventInfo` franchise** depuis `PatchStats` (saison → franchise(s) + `base_trial_config` [nodeCount/waves/gating] +
   `franchise_trials_enemy_config` [stages] + ennemis = héros de la franchise). Vérifier si un builder DU JEU existe (client) → l'exécuter
   (§3) ; sinon object-path en peuplant depuis `PatchStats` (données du jeu, pas inventées). Cible : `ClientEventTrial.getSubtrials()>0`.
2. `GetTrialEventData` → blob per-user serveur-autoritatif (chances/resets/subtrials) + persistance + handler.
3. `TrialEventAttack` → `BaseEventTrialNode.recordOutcome` (avance nœud) + conso chance + loot client-reporté + persistance.
4. resets (`doDailyReset`/`doPaidReset`). 5. gating franchise (`getGatingCriteria` = seuls héros de la franchise). 6. complétion
   `PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence). 7. `AdminEvents --open-trial <FRANCHISE|saison>`. 8. vérif EN JEU.

## 17. EVENT/FRANCHISE incr. 1 (structure) — RECETTE DE CONSTRUCTION PROUVÉE (g139)
**Flux `init()` (bytecode `BaseEventTrial.init`)** : itère `TrialEventInfo.getSubtrials()` (List<`TrialEventSubtrialInfo`>) → `addSubtrial`,
PUIS `TrialEventInfo.getNodeCounts()` (List<`TrialEventNodeCount`>) → pour chaque, si `subtrialMatches(i)` → `subtrial.createNodes(getValue())`.

**Recette VÉRIFIÉE (spike `/tmp/TrialStructSpike`) — `ClientEventTrial.getSubtrials()=1`, `nodes(sub0)=14`** :
- champ `TrialEventInfo.subtrials` = `[ new TrialEventSubtrialInfo(info, JSON("{\"title\":{},\"preset\":\"none\"}")) ]` (1 par franchise).
- champ `TrialEventInfo.nodeCount` = `[ new TrialEventNodeCount(JSON("{\"nodeCount\":N,\"scope\":{}}"), namedRangesMap) ]`
  (⚠ clés EXACTES : `nodeCount` (pas `value`) + `scope` OBLIGATOIRE ; `scope:{}` = ALL → s'applique à subtrial 0. `TrialEventScope`
  lit `subtrialNumber`/`nodeNumber`/`waveNumber`/`resetNumber` = `SparseRange` (ex "1-14"), défaut ALL. `scope:{subtrialNumber:"0"}` a
  donné nodes=0 → à creuser ; `scope:{}` marche).
- pièces normalement chargées d'un JSON (`TrialEventNodeCount(JsonValue,Map)`, `TrialEventEnemyLineup(JsonValue,Map)`,
  `TrialEventScope`, `TrialEventGatingCriterion`…) → on assemble de PETITS fragments JSON dont le CONTENU vient des `.tab` (§4).

**Accesseurs de contenu du jeu (§3/§4, lus — pas en dur)** : `PatchStats.getPatchableFranchisesForSeason(u)` (franchises de la saison,
~70), `getFranchiseTrialEnemyPoolForSeason(u)` (283 héros ce cycle), `validEnemyUnitTypeForSeason`, `getPatchLevelHardCapForSeason()=25`,
`getFranchiseTrialsStageNumber()=5`. `HeroHelper.getAllHeroesInFranchise(Franchise)` = héros d'une franchise (ennemis + gating).

**⚠ QUESTION OUVERTE (à trancher AVANT de figer, §4/§8)** : le NB DE NŒUDS de l'event. 3 valeurs candidates : `base_trial_config.tab`
`NODE_COUNT=14` ; `getFranchiseTrialsStageNumber()=5` ; wiki « Nodes 1-7 ». Ne PAS deviner → lire la bonne source (probable `NODE_COUNT`
du base_trial_config via son `DHConstantStats`, ou la structure réelle par franchise). Idem : combien de subtrials (1/franchise ? les 4
`FRANCHISES` du base_trial_config `WILDCARD,THE_JUNGLE_BOOK,THE_LITTLE_MERMAID,MOANA` = la saison courante, ≠ la liste patchable complète).

### RESTE incr. 1 (contenu) puis 2-8 : enemyLineups (héros de franchise, stars/rarity/level des 14 stages `franchise_trials_enemy_config.tab`),
gatingCriteria (franchise), rewards (Badge Bits→Patch Essence), chances (`base_trial_config` MAX_DAILY_RESETS=60, WAVE_COUNT=3) → puis
`GetTrialEventData` blob → `TrialEventAttack` record (`BaseEventTrialNode.recordOutcome`) → resets → gating → complétion Patch Essence →
`AdminEvents --open-trial <FRANCHISE>` → **EN JEU**.

### ✅ incr. 1a LIVRÉ (g139) — `ServerEvents.buildFranchiseTrialEvent(id,start,end)` : STRUCTURE FIDÈLE data-driven
Lit `base_trial_config` via `PatchStats.BASE_TRIAL_CONFIG_STATS.getStats()` (0 en dur, §4) : `NODE_COUNT`, `FRANCHISES` (franchises de
la saison), `MAX_DAILY_RESETS`, gating levels. Bâtit **1 sous-trial par franchise × `NODE_COUNT` nœuds**. `FranchiseTrialStructTest`
(régression 130) prouve EN HEADLESS : `new ClientEventTrial(u, info)` → **subtrials=4** (WILDCARD/THE_JUNGLE_BOOK/THE_LITTLE_MERMAID/MOANA)
**× 14 nœuds** chacun, franchises = saison. Nb de nœuds tranché = `NODE_COUNT` (14) du base_trial_config (pas `getFranchiseTrialsStageNumber`).

### incr. 1b (CONTENU ennemis) — SCHÉMA MAPPÉ (g139) ; build intriqué à mener méthodiquement
Données `patched_heroes_franchise_trials_enemy_config.tab` (14 stages) : `STARS` 2→6, `RARITY` 7→76, `LEVELS` 55→435,
`ASSIGN_REAL_GEAR` (TRUE dès stage 5), `REWARDS`/`BONUSES` (RANDOM_BADGE stages 1-4 → PATCH_ESSENCE_1..5 stages 5-14).
Schéma des pièces (fragments JSON, ctor `(JsonValue, Map)`) — **clés EXACTES relevées** :
- `TrialEventEnemyLevel`/`EnemyRarity`/`EnemyStars` = `{"expr":"<val>","random":{"kind":"NORMAL"},"scope":{…}}` (⚠ `random` OBLIGATOIRE
  → `TrialEventRandomMode{kind:NORMAL|PER, node/reset/subtrial/wave}` sinon NPE ; `expr` = bycep expression = la valeur du stage).
- `TrialEventEnemyLineup` = `{"kind":"AUTO"|"MANUAL","categories":{"kind":"FRANCHISE","franchises":["MOANA"]}|…,"random":{…},"scope":{…}}`
  (AUTO tire des héros filtrés ; `TrialEventHeroFilter` kinds : FRANCHISE/HERO/COLLECTION/RECENCY/role/team, clés `franchise(s)`/`min`/`max`).
- `scope` = `TrialEventScope{subtrialNumber/nodeNumber/waveNumber/resetNumber = SparseRange}` (défaut ALL).
- **POINT OUVERT (spike)** : avec level/rarity/stars/lineup ALL-scopés, `node.createEnemies()` = **0 ennemis** → il MANQUE la couche
  vagues/compte (`WAVE_COUNT=3` du base_trial_config ; `TrialEventEnemyLineup.addHeroes(Array, ContentColumn, Random, int,int,int, info)`
  = 3 int probables count/wave/node ; `createWaves(int)`/`getRawWaveEnemies(wave)`). À élucider AVANT de figer (§8, pas deviner).
  ⇒ incr. 1b = build méthodique (14 stages × level/rarity/stars scopés par nœud + lineup AUTO franchise par sous-trial + vagues) →
  `node.createEnemies()>0` avec héros de la franchise. **Combat = client-autoritatif** : ce contenu sert au RENDU client (le serveur
  ne combat pas) → nécessaire pour la vérif EN JEU, mais l'autorité serveur (incr. 2-3) peut se tester AVANT (headless).

### ✅ incr. 1b LIVRÉ (g140) — CONTENU ennemis FIDÈLE, data-driven, SCHÉMA COMPLET découvert (parseur du jeu = oracle)
`buildFranchiseTrialEvent` peuple EN BOUCLE depuis les 14 stages (`FRANCHISE_TRIALS_ENEMY_CONFIG_STATS.stageToEnemyConfigs`) :
`enemyLevel/Rarity/Stars` = 14 pièces `{expr:<val du stage>, random:{kind:NORMAL}, scope:{nodeNumber:<stage>}}` ; `enemyLineups` =
1/sous-trial, chaque `{kind:MANUAL, units:[5× héros], scope:{subtrialNumber:<i+1>}}`, chaque unit =
`{kind:RANDOM_HERO, categories:[{kind:FRANCHISE, franchises:[{franchise:<F>}]}], realGear:{kind:NORMAL}}` (WILDCARD → `categories:[]`).
**Schéma EXACT découvert industriellement** (chaque erreur du parseur du jeu = clé suivante) : `TrialEventEnemyLineup` champ
`manualHeroes` (clé JSON `units`) ; `TrialEventHeroFilter` FRANCHISE = `franchises:[{franchise:NAME}]` (tableau d'objets) ;
`TrialEventEnemyHero` requiert `kind`(RANDOM_HERO/RANDOM_NPC/SPECIFIC_UNIT) + `categories`(tableau) + `realGear:{kind:NORMAL|DISABLE}` ;
`scope`=`TrialEventScope` (SparseRange 1-based : `subtrialNumber`/`nodeNumber`/…). `FranchiseTrialContentTest` (régression 131) : event
bien formé (parseur du jeu l'accepte), 14 enemyLevel/Rarity/Stars, 4 lineups × 5 units, enemyLevel[0]=niveau stage 1, runtime 4×14.
**⚠ La GÉNÉRATION effective des ennemis (createWaves/createEnemies) + le combat = CLIENT-AUTORITATIF → vérifiés EN JEU (§8)** (headless,
`createEnemies` renvoie les vagues mais ne les peuple pas hors contexte combat client).

### ✅ incr. 2 LIVRÉ (g141) — AUTORITÉ SERVEUR : `GetTrialEventData` → `TrialEventData` (blob per-user serveur-autoritatif)
`ServerTrials.getData(su, eventID)` sert l'état per-user (frais si nouveau/nouvelle saison, sinon persisté ; keyé par eventID) ;
`freshData` = 0 chance / 0 reset / subtrials vide. **Persistance** : nouvelle colonne BLOB `trialEventData` (patron `expeditionRun`)
— `ServerUser.trialEventWire`/`setTrialEventWire`/`trialEventDataOrNull`/`setTrialEventData` + migration + save/load `UserStore`.
**Handler** `LoginServer` : `GetTrialEventData{eventID}` → `ServerTrials.getData` → persiste → répond `TrialEventData`
(builder ABSENT du jar client → serveur-autoritatif, patron `ArenaInfo`/`GetExpeditionResponse`). `ServerTrialsDataTest`
(régression 132) : frais/persisté, round-trip wire + DB, changement d'eventID → frais.

### ✅ incr. 3 LIVRÉ (g142) — RECORD d'un combat de trial : `TrialEventAttack` → `BaseEventTrialNode.recordOutcome`
Le client joue le combat du nœud (client-autoritatif) et envoie `TrialEventAttack{eventID, subtrialNumber, nodeNumber,
stagesCleared, base{outcome,stars,attackers,defenders}, lootEarned}` (fire-and-forget). `ServerUser.recordTrialEventAttack` :
1. reconstruit l'event trial DÉTERMINISTE (`ServerEvents.buildFranchiseTrialEvent(eventID,…)`, depuis les `.tab`) + branche
   l'état per-user persisté (`new ClientEventTrial(user, info).setUserData(blob)`, blob = `ServerTrials.getData`) ;
2. retrouve le sous-trial (`getSubtrialNumber()`) puis le nœud (`getNodeNumber()`) — lève `ClientErrorCodeException` si absent ;
3. exécute la logique DU JEU (§3) : **`node.recordOutcome(outcome, stagesCleared, loot, attackers, defenders, attackEndTime,
   snapshot)`** — qui fait TOUT : anti-triche (chances/resets restants), avance le statut du nœud (étoiles, façon campagne),
   **consomme une chance** (`recordChanceUsed` → `userData.chancesUsed`++), crédite les récompenses (`RewardHelper.giveRewards`) ;
4. **reflète** le statut calculé PAR LE JEU (`((BaseEventTrialNode) node).getLevelStatus()` → étoiles/niveau) dans le blob
   serveur-autoritatif `TrialEventData.subtrials[sub].nodeLevelStatuses[node]` (glue §3 : `recordOutcome` avance l'objet runtime
   côté client mais N'écrit pas le statut dans le blob wire — le SERVEUR tient l'état) ; puis `setTrialEventData` + resync.
`GenericTrialNode` est une INTERFACE (`getLevelStatus` déclaré sur `BaseEventTrialNode`, concret `ClientEventTrialNode`) → cast.
**Handler** `LoginServer` : `TrialEventAttack` (fire-and-forget, patron `DifficultyModeAttack`) → `recordTrialEventAttack` →
`store.save` ; anti-triche = `ClientErrorCodeException` (rien accordé). `TrialEventRecordTest` (régression 133) : victoire
(sous-trial 1, nœud 1) → nœud dans `subtrials` avec étoiles=3, `chancesUsed` 0→1, persistance wire + DB (étoiles + chances).
**⚠ Correctif déterminisme (§8, découvert en régression)** : `SpecialEventsRotationTest` dépendait du jour serveur réel (jour 1 =
seul jour où DOCKS **et** WAREHOUSE ouverts → aucun mode fermé pour tester l'override) → **ancré** à un jour où DOCKS est fermé
via nouveau helper `ServerEvents.snapshotAt(long)` (constructeur DU JEU `SpecialEventSnapshot(snapshotRaw(), time)`), déterministe.

**RESTE** : 4 resets (`checkForDailyReset`/`doPaidReset`) → 5 gating franchise (`getGatingCriteria` = seuls héros de la franchise)
→ 6 complétion `PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence) → 7 `AdminEvents --open-trial <FRANCHISE|saison>`
(push event) → 8 vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence).

### ✅ incr. 4 LIVRÉ (g143) — RESETS de chances (quotidien gratuit + payant, fidèlement gatés)
Deux mécanismes, exécutés par la logique DU JEU (§3, 0 règle réécrite) :
- **Reset quotidien GRATUIT (auto)** : `ClientEventTrial.checkForDailyReset(now)` → si un nouveau jour est franchi (et
  `dailyResetsRemaining>0`), `doDailyReset` remet `chancesUsed=0`, incrémente `dailyResetsUsed`, réancre `lastChancesResetTime`.
  Appliqué serveur-autoritativement sur le chemin `GetTrialEventData` (`ServerUser.refreshTrialDailyReset` — les chances se
  rafraîchissent chaque jour) ET avant chaque `recordTrialEventAttack`. Franchise trial : `chancesPerReset=2`, `maxDailyResets=60`.
- **Reset PAYANT (`Command RESET_TRIAL_EVENT_PAID`)** : le client envoie `Action{command=RESET_TRIAL_EVENT_PAID,
  extra={ID:eventID, COST:cost}}`. `ServerUser.resetTrialEventPaid(eventID)` exécute `TrialsHelper.resetTrialEvent(user, trial)`
  (§3) — anti-triche (peut reset ? quota resets payants restant ? chances encore dispo → lève) + débit (`UserHelper.chargeUser`,
  coût `getResetCost`) + `doPaidReset` (`paidChancesRemaining=chancesPerReset`, `paidResetsUsed++`). Handler `LoginServer`
  (dispatch `act.command`, lit `extra[ID]`). **⚠ FAIT §4/§8** : pour un FRANCHISE trial les données du jeu DÉSACTIVENT le reset
  payant (`canUseResetItems=false`, `maxPaidResets=0`, `resetCost=-1`, `resourceCostType=DEFAULT`) → `resetTrialEvent` lève
  `ClientErrorCodeException` (anti-triche du JEU, fidèle) ; le serveur n'accorde rien. Le chemin est implémenté+branché (pour
  les modes trials qui l'activent), franchise = refusé fidèlement. `TrialResetTest` (régression 134) : reset quotidien
  (chancesUsed 1→0, dailyResetsUsed 0→1) + persistance wire+DB + reset payant refusé. Helper privé `boundTrial` factorisé
  (record/reset/refresh). Probe DEV `TrialResetProbe` (hors régression) : valeurs de reset lues du jeu.

**RESTE** : 5 gating franchise (`getGatingCriteria` = seuls héros de la franchise) → 6 complétion
`PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence) → 7 `AdminEvents --open-trial <FRANCHISE|saison>` (push event)
→ 8 vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence).

### ✅ incr. 5 LIVRÉ (g144) — GATING FRANCHISE serveur-autoritatif (seuls les héros de la franchise du sous-trial)
Un sous-trial de franchise n'autorise QUE les héros de sa franchise. Le combat est client-autoritatif mais la **légitimité du
lineup** est revalidée par le serveur (§3) : `ServerUser.validateTrialFranchiseGating` (appelé dans `recordTrialEventAttack` avant
`recordOutcome`) lit la franchise du sous-trial (`ServerEvents.franchiseForSubtrial(subtrialNumber)` = `base_trial_config.FRANCHISES`,
1 sous-trial/franchise dans l'ordre, §4) et l'ensemble des héros de la franchise (`ClientTrialEventHelper.getAllHeroesInFranchise`,
données du jeu) ; chaque attaquant (`base.attackers` = `AttackLineupSummary.units[].type`) hors ensemble ⇒ `ClientErrorCodeException`
(rien accordé). `WILDCARD` (joker) ⇒ aucune restriction. `TrialGatingTest` (régression 135) : sous-trial THE_JUNGLE_BOOK → BALOO
accepté, URSULA (Little Mermaid) rejeté, refus = nœud non enregistré. Helpers `ServerEvents.franchiseNamesInOrder`/`franchiseForSubtrial`.
**⚠ FAIT §4/§8** : le JSON de gating client (`TrialEventInfo.gatingCriteria` : style INCLUSIVE/EXCLUSIVE + `heroCount`) est
BACKEND-AUTHORED (pas dans les `.tab`) → on ne l'INVENTE PAS (§4) ; le serveur applique la restriction franchise à partir des
données du jeu (fidèle et anti-triche). Le FILTRE d'affichage du sélecteur côté client (qui lit `getGatingCriteria`) reste à
alimenter le jour où une vérité terrain (event JSON) est disponible — sera vérifié/complété EN JEU (§8).

**RESTE** : 6 complétion `PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence) → 7 `AdminEvents --open-trial
<FRANCHISE|saison>` (push event) → 8 vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence).

### ✅ incr. 6 LIVRÉ (g145) — COMPLÉTION : récompense (Patch Essence) créditée + hook + correctif étoiles (§8)
- **Récompense créditée (modèle client-autoritatif §4bis)** : `BaseEventTrialNode.recordOutcome` passe le `loot` (3ᵉ param =
  `m.lootEarned`, drops client-reportés) à `RewardHelper.giveRewards` → Badge Bits / **Patch Essence** crédités (comme PORT/campagne ;
  le combat est joué côté client, le loot rapporté). Déjà en place depuis incr. 3 ; prouvé bout-en-bout ici (`RewardDrop{PATCH_ESSENCE_1}`
  → `getItemAmount` +N, persiste DB).
- **⚠ Correctif §8 (étoiles)** : le 2ᵉ param de `recordOutcome` sont les **ÉTOILES** (bytecode `TrialEventAttackScreen`:
  `calculateStars()` ; `recordOutcome` fait `setStars`), PAS `stagesCleared`. incr. 3 passait `m.stagesCleared` (masqué car les tests
  mettaient les deux à 3) → corrigé en `m.base.stars`. `TrialCompletionTest` le prouve (`stagesCleared=0`, `base.stars=3` → nœud 3★).
- **Hook de complétion** : après `recordOutcome`, on appelle `PatchedHeroesHelper.handleFranchiseTrialCompletion(user, trial, snap,
  nodeNumber, stars)` (parité avec `TrialEventAttackScreen`) — recordDailyUse (`unity/major_merge_trials_completed`) + `handleStageCompletion`
  (flag `FRANCHISE_TRIALS_STAGE_X_BEATEN` si nœud≥`getFranchiseTrialsStageNumber` ET 3★). `getQuestCategory()` du build data-driven
  = `NONE` (enum valide, pas de NPE ; le `questType` précis est backend-authored, non inventé §4) → le suivi de quête est un no-op
  fidèle tant qu'une vérité terrain ne le définit pas ; la récompense principale (Patch Essence) passe par le loot de nœud. `TrialCompletionTest`
  (régression 136).

**RESTE** : 7 `AdminEvents --open-trial <FRANCHISE|saison>` (push event, patron SPECIAL_EVENTS `AdminEvents`) → 8 **vérif EN JEU**
(vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence). Le headless du sous-système EVENT/FRANCHISE est complet
(structure + contenu + autorité + record + resets + gating + complétion) ; reste le push admin + la vérif en jeu (§8, obligatoire).

### ✅ incr. 7 LIVRÉ (g146) — PUSH ADMIN de l'event trial (`AdminEvents --open-trial`) + correctif §8 realGear
Un opérateur active le franchise trial (patron SPECIAL_EVENTS) : spec `TRIAL_FRANCHISE{id,start,end}` persistée en config
`operator_events` (`ServerEvents.specJsonTrialFranchise`) → reconstruite au boot par `LoginServer` (`eventsFromConfig` →
nouvelle branche `TRIAL_FRANCHISE` → `buildFranchiseTrialEvent(id,…)`) → poussée au client via `REFRESH_SPECIAL_EVENTS`
(`toRaw(bootDefaultEvents())`). `id` de la spec = l'eventID stable que le client renvoie (`GetTrialEventData`/`TrialEventAttack`).
`AdminEvents --open-trial [eventID]` (défaut 900001) / `--close-trial`. `TrialAdminPushTest` (régression 137) : round-trip config
→ event data-driven (subtrials>0, eventID préservé) → `toRaw` (push client).
**⚠ CORRECTIF §8 (fidélité g140)** : `RealGearMode` ∈ {FIRST, NONE, RANDOM, SECOND} — **il n'existe PAS de "NORMAL"** (le schéma
noté g140 était FAUX). `EnumHelper.tryValueOf(RealGearMode,"NORMAL")` est LENIENT → renvoyait `null` SILENCIEUSEMENT (le parse
passait, `FranchiseTrialContentTest` vert) mais `TrialEventEnemyHero.toJson` faisait NPE (`rg.name()`) au PUSH client. Corrigé :
per-hero `realGear:{kind:NONE}` (valeur VALIDE, neutre, non inventée §4). L'ASSIGNATION réelle du real gear (`ASSIGN_REAL_GEAR`
par stage ; `assignRealGear` au niveau lineup) est un raffinement à calibrer EN JEU (§8 ; granularité par-stage vs lineup par-sous-trial).

### ✅ incr. 8 LIVRÉ (g147) — VÉRIFIÉ EN JEU (§8) : franchise trial joué de bout en bout (client réel → serveur → persistance)
`AdminEvents --open-trial` (eventID 900001) sur la DB serveur + `run-online.sh` → **VÉRIFIÉ EN JEU (id=1, TL200)** :
1. **Vitrine** `TrialEventSubTrialChooserScreen` : **4 sous-trials** (WILDCARD/JUNGLE_BOOK/LITTLE_MERMAID/MOANA) + **CHANCES 2/2**
   (= `chancesPerReset`) + bouton ENTER (captures `trial_vitrine.png`).
2. **Sous-trial** `TrialEventSubTrialScreen` : **STAGE 1/14** (nodeCount), **Enemies 1/3** (waveCount), **5 ennemis niv 55 à 2★**
   (= stage 1 de `franchise_trials_enemy_config`, DATA-DRIVEN), chemin 1→14 (`trial_subtrial.png`).
3. **Sélecteur** `TrialEventHeroChooserScreen` (CHOOSE YOUR HEROES, 0/5, FIGHT) → **combat** `TrialEventAttackScreen` rendu
   (3/3 vagues, équipes qui combattent, `trial_combat.png`) → **VICTOIRE** (`trial_victory.png`).
4. **Serveur** : `<== GetTrialEventData(900001)` + `HeroLineupUpdate(EVENT_TRIAL)` + `<== TrialEventAttack : event=900001
   sous-trial=1 nœud=1 outcome=WIN → recordOutcome appliqué [persisté]`. **DB** : `trialEventData` eventID=900001, `chancesUsed`=1,
   sous-trial 1 → **nœud 1 à 3★** (tentatives=1) — server-autoritatif, persisté.
5. **Gating serveur PROUVÉ EN JEU** : sur le sous-trial 2 (THE_JUNGLE_BOOK) avec un lineup NON-franchise → `⛔ TrialEventAttack
   REFUSÉ (anti-triche) : ERROR` (le serveur rejette, rien accordé). NB nœud 1 = stage 1 → **Badge Bits** (Patch Essence dès stage 5).

**⚠ 3 correctifs §8 découverts EN JEU (invisibles en headless jusqu'au push/rendu réel)** :
- **`waveCount`** manquant → `TrialEventSubTrialScreen.getCampaignEnemiesViewV2` : **/ by zero** (divise par le nb de vagues).
  Ajout `WAVE_COUNT` (base_trial_config, data-driven) → `{waveCount:N, scope:{}}`.
- **Carte `image`** : `toJson` d'un card kind=UNIT écrit la clé `image` mais `load` relit `unitType` (ASYMÉTRIE du jeu) → event
  rejeté au re-parse client (`Named value not found: unitType`). `fillTrialFields` laissait `cardUnitType`=DEFAULT (branche UNIT).
  Fix : `cardUnitType`=null + `cardImage`=null → `toJson` émet `{kind:MATCH_DISPLAY}` (round-trip propre, sans asset).
- **`gatingCriteria`** : sans lui, `TrialEventInfo.franchises`=null au `load` (dérivé du `specificFranchise` du filtre de gating) et
  le client ne connaît pas la franchise. Ajout d'1 critère/sous-trial (`{scope:{subtrialNumber}, random:{kind:NORMAL},
  criteria:[{style:{kind:INCLUSIVE, heroCount:5}, criterion:{kind:CATEGORIES, categories:[{kind:FRANCHISE, franchises:[{franchise:F}]}]}}]}`).
  (`heroCount` est DANS `style` ; `random` requis car ScopedConfigurable.)

**Pilotes EN JEU (B-bis, API réelle)** : `trialscreen` (vitrine via `createTrial`+`TrialEventSubTrialChooserScreen`), `trialsub <n>`,
`trialattack <sub> <node>` (`TrialEventHeroChooserScreen`), `trialteam` (sélection + `startBattleInner`). **Leçon** : l'overlay
RESULTS (victoire) bloque la consommation du clickfile → pour un 2ᵉ combat, redémarrer frais (le serveur enregistre dès réception,
la persistance ne dépend pas du dismiss client).

**⇒ MODE « TRIALS » COMPLET & VÉRIFIÉ EN JEU** : les 4 DifficultyMode-trials (TEAM_TRIALS_{BLUE,RED,YELLOW}+SPOTLIGHT, g134-g136)
**+ EVENT/FRANCHISE trials** (incr. 1a→8). Reste (hors trials) : finir les composants SPECIAL_EVENTS restants, puis Phase 2.

### ✅ incr. 9 LIVRÉ (g148) — COMBLE LES MANQUES relevés en jeu par l'utilisateur (titres, chances, RÉCOMPENSES) — data-driven + params admin
Les captures incr. 8 montraient un mode PARTIEL : « NONE.TITLE » partout, CHANCES 2/2 (hardcodé !), Rules/Rewards VIDES (vitrine, stage,
victoire). Diagnostic RIGOUREUX (bytecode + `.tab`) :
- **Récompenses (le gros manque)** : ELLES SONT DATA-DRIVEN dans `franchise_trials_enemy_config.tab` (colonnes **REWARDS/BONUSES** par
  stage, ex. `RANDOM_BADGE 7-11 8,RANDOM_BADGE 7-11 8` / `PATCH_ESSENCE_1 36`) — jamais lues. Ajout : `rewardTypes` peuplé (14 stages)
  via `parseRewardList` (convertit le format `.tab` → pièces `TrialEventReward` du jeu : RANDOM_BADGE → `{kind, quantity, minRarity,
  maxRarity}` [expressions] ; PATCH_ESSENCE_n → `{kind:ITEM, itemType, quantity}`). Le client génère alors le loot + l'affiche, le
  serveur le crédite (recordOutcome→giveRewards). **§4, 0 invention.**
- **Chances** : j'avais un **HARDCODE `chancesPerReset=2`** dans `fillTrialFields` (**violation §4**) — SUPPRIMÉ. La valeur n'est dans
  AUCUN `.tab` (ni base_trial_config ni event_trial_constants) → **backend-authored → paramètre ADMIN** (`AdminEvents --open-trial
  --chances N`, défaut `DEFAULT_TRIAL_CHANCES=10` = vérité terrain « CHANCES LEFT: 10/10 »). **Consommation PROUVÉE** : DB `chancesUsed`
  1 après victoire → `getChancesRemaining` 10→9, persisté (`TrialRewardsTest` + DB en jeu).
- **Titres** : `EventString.unlocalized(info, texte)` (libellé littéral, plus « NONE.TITLE ») : titre principal = param admin
  `--title` (défaut « FRANCHISE TRIALS ») ; **titres sous-trials = nom de franchise** (data-driven : WILDCARD/THE JUNGLE BOOK/…).
- **Cohérence serveur** : `ServerEvents.activeTrialEvent(eventID)` — `ServerUser` rejoue le combat sur l'event INSTALLÉ (mêmes
  chances/rewards admin que le client), pas une reconstruction aux params par défaut.

**✅ RE-VÉRIFIÉ EN JEU (captures `trial_vitrine2 / trial_stage_rewards / trial_after_win`)** : vitrine = **« FRANCHISE TRIALS » +
CHANCES 10/10 + WILDCARD/THE JUNGLE BOOK/THE LITTLE MERMAID + Final Stage Rewards (Patch Essence 46/25/16/13/10 = stage 14 du `.tab`)** ;
écran de stage = **Rules (gating 5:) + Enemies 1/3 + Rewards (badges 8/8 + BONUS 6 = stage 1 du `.tab`)** ; écran de victoire =
**REWARDS/ITEMS (8/8 + BONUS 6)**. Combat WILDCARD → serveur `recordOutcome appliqué [persisté]`, DB `chancesUsed=1`, nœud 1 à 3★.
`AdminEvents --open-trial [--chances N] [--title "…"]`. Régression 138 (`TrialRewardsTest`). **Honnête (§8)** : les couleurs/icônes exactes
de Rules (combat modifiers) et les libellés localisés fins restent un raffinement d'affichage ; le mode est FONCTIONNEL et data-driven.

### ✅ incr. 10 LIVRÉ (g149) — SOUS-TRIALS DÉFINIS PAR LA SAISON (franchise_season_mapping) + questType data-driven
**Question utilisateur : « comment sont définis les sous-trials disponibles ? est-ce l'admin ? ».** Réponse (modèle du jeu) :
- **C'est DATA-DRIVEN par la SAISON**, pas l'admin. `patched_heroes_franchise_season_mapping.tab` est un `TimeTable` (colonnes = dates
  de début de saison). Pour la date courante, la colonne active définit **jusqu'à 3 TRIALS** (`TRIAL$0/1/2`), chacun un
  `FranchiseTrialConfig{franchises (= les sous-trials), questType, activeDays}`. **Auto-rotation par date** (nouvelle saison ≈ toutes
  les 4 semaines). Ex. saison courante : Trial 0 = [WILDCARD] (Lun/Jeu/Dim, MAJOR) ; Trial 1 = [THE_JUNGLE_BOOK] (Mar/Ven/Dim, MAJOR) ;
  Trial 2 = [THE_LITTLE_MERMAID, MOANA] (Mer/Sam/Dim, MERGE) → **un trial peut avoir plusieurs franchises = plusieurs sous-trials**.
- **Rôle de l'ADMIN (sur NOTRE serveur)** = ACTIVER quel trial de saison pousser (`AdminEvents --open-trial --trial N`, N=index de
  saison) + surcharger les params non-data (chances, titre, dates). Les franchises/sous-trials/questType/jours viennent de la SAISON.
- **`PATCHABLE_FRANCHISE$0..11`** (même `.tab`) = les franchises dans lesquelles on peut *patcher* des héros cette saison (système Patch
  au sens large, cf. wiki `Patch`) — distinct des sous-trials du trial.

**Correctif de fidélité (§4/§8)** : j'utilisais `base_trial_config.FRANCHISES` (gabarit STATIQUE : WILDCARD/JUNGLE_BOOK/LITTLE_MERMAID/MOANA
fusionnés, questType NONE) → FAUX. Remplacé par la **saison** (`ServerEvents.seasonTrialConfigs/seasonTrialFranchises/seasonTrialQuestType`,
lus via `FRANCHISE_SEASON_MAPPING_STATS`, §3). `buildFranchiseTrialEvent(…, trialIndex)` : sous-trials = franchises du trial de saison,
`questType` posé (MAJOR/MERGE → `handleFranchiseTrialCompletion` non-no-op). Gating par eventID (`TRIAL_FRANCHISES_BY_EVENT`,
`franchiseForSubtrial(eventID, sub)`). `base_trial_config` reste pour NODE_COUNT/WAVE_COUNT/gating levels/maxDailyResets (ceux-ci y sont).
Fix bug : `AdminEvents` accumulait les specs (JsonValue.toString() sans guillemets) → `removeIf(contains("TRIAL_FRANCHISE"))`.

**✅ VÉRIFIÉ EN JEU** (`trial2_vitrine.png`) : `--open-trial --trial 2` → vitrine **« THE LITTLE MERMAID » + « MOANA »** (2 sous-trials =
saison trial 2), CHANCES 10/10, Final Stage Rewards. `--trial 0` → 1 sous-trial (WILDCARD) ; `--trial 1` → THE JUNGLE BOOK. Régression 138.

**Combat modifiers (« Rules » icônes rouges)** : les DÉFINITIONS existent (`event_trial_arena_rules.tab` : ARMOR±, etc.) mais leur
ASSIGNATION par nœud est backend-authored (pas dans les `.tab`) → resterait un **paramètre admin optionnel** (non inventé §4). La ligne
« Rules » affiche déjà le gating franchise (« 5: » + catégorie). Ce point est le seul raffinement d'affichage restant, non bloquant.

## Statut : 4 DifficultyMode-trials ✅ EN JEU. EVENT/FRANCHISE : 1a STRUCTURE ✅ + 1b CONTENU ✅ + 2 AUTORITÉ SERVEUR (`GetTrialEventData` blob) ✅ (régression 132). **Prochaine action = incr. 3 `TrialEventAttack` → record (`BaseEventTrialNode.recordOutcome` : avance nœud + conso chance + loot) sur le blob per-user ; puis push event (`AdminEvents --open-trial`) + vérif EN JEU.**
