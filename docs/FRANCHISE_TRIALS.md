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

## Statut : RECON FAITE + incr. 0 (contrat wire ✔ WireCheck). Prochaine action = incr. 1 (`GetTrialEventData` → `TrialEventData`,
## en démarrant par la famille B / SPOTLIGHT data-driven, la plus simple à mener EN JEU).
