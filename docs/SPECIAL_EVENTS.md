# SPECIAL_EVENTS (live-ops opérateur) — sous-système serveur — RECON + PLAN

> But : gérer les **événements spéciaux** côté serveur (admin opérateur), fidèlement (§2/§3), pour piloter TOUT ce qui
> en dépend — **pas seulement Warehouse**. Incr. 1 visé = composant `ModesOpen` (ouvrir PORT_WAREHOUSE + débloquer
> FRANCHISE_TRIALS). Ce document = état de la recon (à maintenir), architecture cible, et le point dur à trancher.

## Pourquoi (fait établi §8)

L'ouverture des modes (et bien d'autres features) est pilotée par le **planning d'événements spéciaux**, pas par un
simple cycle hebdo. `DifficultyModeHelper.isOpen` (ordinaux PORT) → `BaseEventSnapshot.isModeOpen` →
`ModesOpenSnapshot.getOpenModes()`. Notre serveur pousse actuellement un `SpecialEventsRaw` **VIDE**
(`ServerContext` : `setSpecialEvents(new SpecialEventsRaw(), user, shard)`) → défaut = DOCKS ouvert, WAREHOUSE jamais.
Cf. `docs/PORT.md` §« Vérif ENTRÉE COMPLÈTE ».

## Réutilisabilité — composants d'événement (leviers)

Un même event porte des composants (`com/perblue/common/specialevent/components/*`). Snapshots dispo :
`ModesOpen` (ouverture/rotation de modes), `DropBonus`, `AdditionalChances`, `ChestDiscount`, `ExtraChest`,
`MerchantDiscount`, `MerchantRefreshDiscount`, `Contest`, `TeamLevel`, `MiscMultipliers`, `FlagUserOnLogin`.
⇒ le même sous-système admin sert TOUT le live-ops.

## Pipeline (bytecode, décodé)

```
SpecialEventsHelper.setSpecialEvents(SpecialEventsRaw raw, IUser, shard)   ← point d'entrée serveur (déjà appelé)
  → Helper.setSpecialEvents → buildEvents(raw) → fillEventList(...)
      pour chaque SpecialEventRaw{eventID:long, jsonString:String}:
        → SpecialEventsHelper.buildEvent(jsonString, user, shard)
            → SpecialEventBuilder.buildEvent(json)
                → new JsonReader().parse(json) → SpecialEventInfo.load(JsonValue)
      → SpecialEvents.setEvents(List<SpecialEventInfo>) ; AtomicReference<SpecialEvents> set
  → snapshot() [UI-gated headless] / snapshotWithoutRefresh() [OK headless] construit l'état interrogé par isModeOpen
```

Chaque event = **JSON** (`SpecialEventRaw.jsonString`). `SpecialEventInfo.toJson()` existe (sérialiseur) — d'où l'idée
« construire via l'API du jeu → toJson() » (JSON canonique, §4).

## Schéma JSON — décodé (partiel, format COMPOSANT = `formatVersion` ≥ 1)

`SpecialEventInfo.load` : lit `kind` (=`SpecialEventType`, ex. `MODES_OPEN`), `id`, `disabled`, `QAApproved`,
`formatVersion`. Si `formatVersion==0` → `loadFlatFormat` (ancien) ; sinon → `loadComponentFormat` (composants
explicites = enfants top-level, PAS de wrapper `components`). Puis `checkUnitType()` (exige un `eventCardDisplay`).

Décodé et VÉRIFIÉ (via le parseur du jeu) en format composant :
- **`visibility`** : **tableau direct** de fenêtres `VisibilityRange` :
  `[{ "serverFilter":"1-999999", "start":<epochMs>, "end":{ "kind":"TIME", "endTime":<epochMs> } }]`
  (`serverFilter` = `SparseRange` numérique, ex. `"1-999999"` ; `end.kind` ∈ {`TIME`+`endTime`, `DURATION`+`days`/`hours`}).
- **`modesOpen`** : `{ "gameModeFilter": { "include":[{"gameMode":"PORT_WAREHOUSE"}], "exclude":[...] } }`
  (EnumFilter : objet `include`/`exclude` = tableaux d'objets `{"gameMode":"<NOM>"}` ; défaut = tous, moins `exclude`).
- **`eventCardDisplay`** : **POINT DUR** — carte d'affichage UI, MAIS **requise** (`checkUnitType`). Champs requis
  successifs observés : `sortIndex`(int), `title`, `summary`, `preset`, … `preset` refuse `""`/`NONE`/`DEFAULT` avec
  `Named value not found: preset` (comportement non-trivial : preset = template qui conditionne d'autres champs).
  Autres champs : `hidden, text, details, image, previewImage, action, button, buttonText, buttonDest, badgeText,
  badgeColor, backgroundColor, altBackgroundColor, interstitial, relatedEventID, unitType{kind:HERO_TYPE|MATCH_REWARDS|
  USER_AD_CAMPAIGN}`.

## Point dur (à trancher — pourquoi on ne bricole pas le JSON à la main)

Reproduire le JSON À LA MAIN oblige à reconstituer un schéma d'AFFICHAGE (`eventCardDisplay`) sans rapport avec
l'ouverture de modes, aux exigences obscures (`preset`…). Le deviner par essais = fragile + risque §4 (invention). ~28
essais headless ont décodé le pipeline + la majorité du schéma, mais pas `eventCardDisplay`.

## ✅ PERCÉE (2026-08-17) — la voie OBJET + `toJson()` FONCTIONNE

Contrat de chargement **définitif** (bytecode `SpecialEventInfo.newComponent`) :
`component.load(info, fullJson, fullJson.get(key))` → **param2 = event complet**, **param3 = sous-nœud `fullJson.get(key)`**.
`EventVisibility.load` branche sur `info.getFormatVersion()` : **fv=0 (flat)** → param3 = **tableau `timeRange`** ;
fv≥1 → `{timeRanges:[…]}`.

**Recette PROUVÉE (headless, `BuildProbe`)** — construire l'event en OBJETS du jeu (fv=0) puis injecter :
```
full = JsonReader.parse("{kind:MODES_OPEN, id, formatVersion:0, timeRange:[{serverFilter:'1-999999',start,end:{kind:TIME,endTime}}], gameModeFilter:{include:[{gameMode:'PORT_WAREHOUSE'}]}}")
info = new SpecialEventInfo(SpecialEventType.class) ; set id/type=MODES_OPEN/formatVersion=0 (réflexion)
EventVisibility vis = new EventVisibility(new int[]{}) ; vis.load(info, full, full.get("timeRange")) ; addComponent(vis)
ModesOpen mo = new ModesOpen(MODES_OPEN, GameMode.class) ; mo.load(info, full, full) ; addComponent(mo)
SpecialEvents se = new SpecialEvents() ; se.setEvents([info])
→ injecter dans EventHelperInner.SPECIAL_EVENTS (+ vider SNAPSHOT_CACHE) [réflexion]
```
**Résultat : `DifficultyModeHelper.isOpen(PORT_WAREHOUSE, snapshotWithoutRefresh()) = TRUE`** (mécanisme fidèle confirmé,
sans debug). `SpecialEventInfo.toJson()` émet le **JSON canonique** (format composant fv=1) :
`{kind:MODES_OPEN, id, disabled:false, formatVersion:1, QAApproved:false, visibility:{timeRanges:[{serverFilter,start,end:{kind:TIME,endTime}}]}, modesOpen:{gameModeFilter:{include:[{gameMode:PORT_WAREHOUSE}]}}}`.

**Reste (client)** : le JSON poussé au client est re-parsé par `buildEvent` → `checkUnitType()` **exige un composant
`eventCardDisplay`** (sinon NPE). Il faut donc AUSSI construire un `eventCardDisplay` (fabrique du jeu
`SpecialEventBuilder.createComponent("eventCardDisplay")` OK) et le charger — son `load` répartit ses champs entre param2
(top-level) et param3 (`displayInfo`) de façon non triviale (`preset` reste à localiser). Pour l'INJECTION SERVEUR pure,
`eventCardDisplay` n'est PAS requis (pas de `checkUnitType`) → **le serveur autoritatif ouvre déjà WAREHOUSE**. Le client
a besoin du JSON complet (avec `eventCardDisplay`) pour AFFICHER/entrer le mode.

## Voies FIDÈLES (recommandation)

1. **Construire l'event via l'API OBJET du jeu → `toJson()`** (JSON canonique par construction, zéro devinette, §4).
   Bloquant : les composants/pieces (`EnumFilter`, `VisibilityRange`) se construisent depuis un `JsonValue` — on peut
   soit bâtir de petits `JsonValue` ciblés (libGDX `JsonValue` API), soit poser les champs privés par réflexion
   (`filter`, `modeFilter`, ranges) — patron « couche plateforme » (comme l'alloc sans ctor de `GameMain`). Puis
   `SpecialEventInfo.toJson()` donne le gabarit exact ; on le persiste/rejoue.
2. **Obtenir un EXEMPLE RÉEL** d'event JSON (capture serveur PerBlue / asset) = vérité terrain, puis paramétrer.

## Architecture cible (industrialisée, bien organisée)

- **`server/java/dhserver/ServerEvents.java`** : module serveur-autoritatif. Construit des `SpecialEventRaw`
  (via voie 1) pour un CATALOGUE d'events opérateur ; expose `buildModesOpenEvent(modes, window)` + (à venir)
  `buildDropBonusEvent(...)`, etc. (un builder par composant, ajoutés au fil des besoins).
- **Persistance** : `shard_state` (BLOB par shard, patron `AdminInvasion`/`AdminWar`/`arena_ladder`) — la liste
  d'events opérateur, ancrée à l'horloge (`AdminClock`). Survit aux redémarrages, cohérent multi-serveur (§5).
- **Boot** : `ServerContext`/`LoginServer` pousse le `SpecialEventsRaw` peuplé (au lieu du vide) via
  `setSpecialEvents` (déjà le point d'entrée).
- **Admin** : outil DEV `AdminEvents` (créer/planifier/lister des events opérateur), pendant d'`AdminInvasion`.
- **Vérif** : test headless (event → `isModeOpen(WAREHOUSE)=true`) + EN JEU (entrer & jouer WAREHOUSE, boucle la
  demande PORT).

## ✅ MOTEUR LIVRÉ (`server/java/dhserver/ServerEvents.java`)

Approche **industrielle** (rien à la main) : on construit les événements avec les **classes DU JEU** et on les injecte
dans la **machinerie DU JEU** — c'est elle qui calcule `isModeOpen`/snapshots.
- `buildModesOpenEvent(id, modes, startMs, endMs)` → `SpecialEventInfo` : construit `EventVisibility` + `ModesOpen` via
  leur `load(info, full, full.get(key))` (contrat définitif), format flat (`formatVersion=0`). Zéro schéma JSON à la main
  (l'entrée par composant est minimale et paramétrée ; `SpecialEventInfo.toJson()` en donnerait la forme canonique).
- `install(events)` : pose la liste dans `SpecialEventsHelper` (remplace `SPECIAL_EVENTS`, invalide le cache,
  `refresh(true)`) → le serveur autoritatif voit l'effet. `snapshot()` = `snapshotWithoutRefresh()` (sûr headless).
- `installBootDefaults()` : ouvre **PORT_DOCKS + PORT_WAREHOUSE**, appelé dans **`ServerContext.bind`** (après le raw
  vide) → à chaque bind, l'état opérateur est garanti (idempotent, global à la couche).
- **Snapshot RÉEL passé aux handlers PORT** (`recordDifficultyModeAttack`/`recordRaidDifficultyMode`/double) au lieu de
  `NONE` → `doChecks`/`isOpen` voient les événements (corrige aussi le bug « NONE = planning à l'époque »).

**Vérifié headless** (`SpecialEventsModesOpenTest`) : sans événement → WAREHOUSE fermé ; événement MODES_OPEN → WAREHOUSE
ouvert ; défauts opérateur → DOCKS+WAREHOUSE ouverts. **Pas de flag debug (§2).**

**Carte UI client (`eventCardDisplay`)** : NON requise pour l'AUTORITÉ serveur (l'injection ne passe pas par
`checkUnitType`). Elle n'est nécessaire que pour POUSSER le JSON au client (affichage/entrée par la vitrine). Le champ
`preset` d'`eventCardDisplay` référence un preset RÉEL de `assets/strings/EventPresets.properties` (pas `""`) ; il
n'existe **aucun preset `MODES_OPEN`** → un event d'ouverture de mode est « technique » (pas de panneau visible). Le push
client est un incrément ultérieur (construire une carte minimale via la fabrique du jeu + toJson).

## Plan d'incréments

1. ✅ **`ModesOpen` (moteur + autorité serveur) — VÉRIFIÉ EN JEU.** `ServerEvents` construit et injecte l'événement ;
   WAREHOUSE ouvert côté serveur (headless ✅ `SpecialEventsModesOpenTest`). **EN JEU (id=1)** : pilote
   `portenter PORT_WAREHOUSE` → `portteam` → combat rendu (THE WAREHOUSE, étages) → **VICTOIRE** → écran REWARDS →
   serveur **`DifficultyModeAttack : PORT_WAREHOUSE diff=1 outcome=WIN → recordOutcome appliqué [persisté]`** (avant :
   `GAME_MODE_NOT_OPEN`). Capture `build/port_warehouse_played_ingame.ppm`. **RESTE incr. 1 (raffinements)** : persistance
   shard (au lieu de re-poser au bind) et rotation fidèle par jour (`getOpenDays`).
2. ✅ **Push client — VÉRIFIÉ EN JEU.** `ServerEvents.buildMinimalCard(info)` : carte `eventCardDisplay` cachée construite
   via la FABRIQUE du jeu (`createComponent`) + remplissage **GÉNÉRIQUE PAR TYPE** (pas champ-par-champ : String→"" sauf
   `preset`="none" [wildcard réel `*.eventCard.none`] ; `EventString`→vide ; `UnitTypeLookup`→`FixedUnitTypeLookup(DEFAULT)`)
   → `SpecialEventInfo.toJson()` produit un JSON **RE-PARSABLE par le client** (`checkUnitType` satisfait). `toRaw(events)`
   → `SpecialEventsRaw` (chaque `jsonString` = `toJson`). `LoginServer` répond au `REFRESH_SPECIAL_EVENTS` avec
   `ServerEvents.toRaw(bootDefaultEvents())` (au lieu du raw vide). **EN JEU (id=1)** : serveur `SpecialEventsRaw (reply,
   1 évènement(s))` → `PortChooserScreen` affiche **THE WAREHOUSE avec bouton ENTER** (au lieu de « OPENS TOMORROW »),
   CHANCES 2/2, comme THE DOCKS → entrée par la vitrine normale. Capture `build/port_warehouse_open_client_ingame.ppm`.
   Débloque aussi FRANCHISE_TRIALS (même levier). RESTE : persistance shard + rotation fidèle par jour.
2bis. ✅ **THE WAREHOUSE entré PAR LA VITRINE & joué de bout en bout EN JEU (g128).** Bouton ENTER réel → `ModePreviewScreen`
   → ATTACK → sélecteur → combat DANS THE WAREHOUSE → VICTOIRE → serveur `recordOutcome [persisté]` + persistance DB prouvée
   (cooldown + chance `dailyUses port*_use=1`). Pilotes `portpress`/`portpreviewattack`, outils `PortEnterAdmin`/`PortStateProbe`.
   Détail : `docs/PORT.md` §« ENTRÉE COMPLÈTE ». **Confirme que la boucle event→client→entrée→combat→autorité est complète.**

### ⭐⭐ FAIT §8 (g130, bytecode COMPLET `DifficultyModeHelper.isOpen`) — la ROTATION par jour est le DÉFAUT (corrige g124 ET g128)
**Vraie structure relevée au bytecode** (une lecture antérieure — g128 — était INCOMPLÈTE ; correction) :
```
isOpen(mode, user, snap) =
     snap.isModeOpen(mode)                     ← MODES_OPEN = OVERRIDE (ouvre quel que soit le jour)
  OR snap.isModeDropBonusActive(mode)          ← DropBonus = OVERRIDE AUSSI (PAS gaté par le jour !)
  OR getOpenDays(mode).contains(dayOfWeek)     ← DÉFAUT : rotation par jour, table DU JEU, SANS AUCUN event
```
(les 3 sont des **OU indépendants** ; le `dayOfWeek` = `TimeUtil.getUserDailyActivityDayOfWeek(user, snap.snapshotTime)`.)
- `getOpenDays(mode)` = **DONNÉE DU JEU** : `PortHelper.DOCKS_OPEN_DAYS`/`WAREHOUSE_OPEN_DAYS` (DOCKS **[6,4,2,1]** / WAREHOUSE
  **[7,5,3,1]**), `TrialsHelper.*_OPEN_DAYS` pour FRANCHISE_TRIALS.
- `isModeDropBonusActive(mode)` = `DropBonusSnapshot.getMultipliers()` **contient `mode` en clé** (peuplé par `DropBonus.refresh`
  pour chaque mode du `gameModeFilter`).

⇒ **La ROTATION QUOTIDIENNE FIDÈLE (DOCKS/WAREHOUSE alternent par jour) est le comportement PAR DÉFAUT du jeu — AUCUN événement
  requis.** MODES_OPEN et DropBonus sont deux **OVERRIDES OPÉRATEUR** (live-ops : forcer un mode ouvert un jour HORS son planning).
- **CORRECTION g124** : « WAREHOUSE jamais ouvert sans event » était **FAUX** — WAREHOUSE s'ouvre par défaut ses jours [7,5,3,1] ;
  il était simplement fermé les autres jours (dont le jour de test). **CORRECTION g128** : la rotation n'est PAS « un DropBonus » ;
  DropBonus est un override, pas le moteur de rotation.
- **CONSÉQUENCE sur `bootDefaultEvents()`** : il ouvre en dur les DEUX modes PORT en permanence (MODES_OPEN) → **écrase la rotation
  naturelle** = le point NON-FIDÈLE (a). Fidèle = **NE PAS forcer** (laisser `getOpenDays` faire) et réserver l'engine aux
  overrides opérateur (config admin persistée). Prouvé headless `SpecialEventsRotationTest` : sans event, `isOpen == getOpenDays.
  contains(jour)` ; override MODES_OPEN/DropBonus ouvre un mode fermé ce jour ; retrait → refermé.
- **`buildDropBonusEvent(id, modes, bonus, start, end)`** LIVRÉ (fabrique du jeu `createComponent("dropBonus")` + `load` ;
  provider/generics câblés §4) : disponible comme **override opérateur** (un DropBonus porte aussi un vrai bonus de drop).

3. ⬜ **RENDRE `bootDefaults` FIDÈLE + config admin (PROCHAIN)** : ne plus forcer les 2 modes PORT ouverts en permanence (laisser
   la rotation `getOpenDays` du jeu) ; l'engine (MODES_OPEN/DropBonus) devient un **OVERRIDE opérateur** piloté par un outil admin
   (`AdminEvents` : ouvrir/fermer un mode, planifier une fenêtre) + **persistance shard** (`shard_state`, survit aux redémarrages).
   Vérif EN JEU : sans override, WAREHOUSE fermé les jours hors [7,5,3,1] (« OPENS TOMORROW ») et ouvert ses jours ; override →
   forcé ouvert. **Décision utilisateur** : basculer le défaut vers la rotation fidèle (WAREHOUSE non-jouable hors planning) ?
4. ✅ **Composants live-ops légers LIVRÉS & vérifiés EN JEU** (g152-157) : `ChestDiscount` (remise coffre), `IncreasedChances`
   (chances quotidiennes), `MerchantDiscount`+`MerchantRefreshDiscount` (remise marchand/refresh), `MiscBonus`+`MiscDiscount`
   (multiplicateurs ALCHEMY/STAMINA), `FlagUserOnLogin` (flags au login), `FREE_STUFF_AT/EVERY_X_TEAM_LEVEL` (récompenses au palier).
   Tous : builder `ServerEvents` (objet du jeu + fabrique/ctor direct) + spec persistée + flags `AdminEvents` + snapshot opérateur
   branché sur le chemin serveur réel + test régression. **Schéma reward-content CRACKÉ** : `EventRewards` → `rewards:[{kind:ITEM,
   itemType:X,quantity:N}]` (drop via `RewardDropProvider`, kinds ITEM/UNIT/MOD/AVATAR/BORDER/COSMETIC/UNIT_SKILL).

## RECON + FEASIBILITY des 2 composants LOURDS restants (ExtraChest, Contest) — à implémenter en incréments dédiés

Décision utilisateur (g158) : **recon + feasibility documentées** (comme pour les trials avant de les attaquer), pas d'implémentation
immédiate — ce sont des composants de CONTENU, pas « un builder de plus ».

> **APPROFONDISSEMENT (g159, demande user « regarde bien ce que c'est » + `.tab`/code/outils)** — findings ci-dessous intégrés.

### A. ExtraChest (EXTRA_CHEST) — coffre BONUS temporaire sur l'écran CRATES — ✅ LIVRÉ & VÉRIFIÉ EN JEU (g160)
> **LIVRÉ + ✅ EN JEU (g160)** — `ServerEvents.buildExtraChestEvent` + `specJsonExtraChest` + branche `eventFromSpec` + `AdminEvents
> --extra-chest` + branche EVENT de `ServerUser.openChest` (roll serveur-autoritatif). `ExtraChestTest` (146) PROUVE bout-en-bout headless :
> snapshot expose le coffre (coût/monnaie du jeu), la table inline roule du VRAI loot, **`openChest(EVENT)` débite exactement le
> coût + crédite le loot**, free buys (wasFree, 0 débit), round-trip spec. **Point dur `preset` RÉSOLU** via le **schéma FORMAT B** (voir
> ci-dessous) — pas de rustine (§2). **✅ EN JEU** : `nav CHESTS` (coffre bonus sur CRATES) + `nav EVENT_CRATE` (« SUPPLY CRATE » + info +
> « FREE NOW! » + « Crates Left: 50/50 » = mes params admin) + ouverture (tap FREE NOW → `BuyChests(EVENT)` → serveur roule MA table
> inline → « CRATE REWARDS » 50 GEAR_TOKENS/20 DIAMONDS, `LootResults [persisté]`). Captures `manual/eventcrate/ec_after/ec_free.png`.
> **CORRECTIF §8 trouvé en jeu** : `freeChest()` passait `null` à `hasFreeChest` → la branche EVENT (lit `getFreeBuys()` vs
> `getEventCompletionCount(id)`) ne voyait pas les free buys → « FREE NOW » facturé à tort ; fix = passer le snapshot opérateur.
> **CORRECTIF §4bis (grille de loot)** : l'aperçu « loot possible » de l'écran de détail vient du nœud **`DISPLAY`** de la table
> (`getPossibleDrops`→`getPossibleLoot`), pas de `ROOT`. `extraChestDropTsv` génère les DEUX nœuds (DISPLAY=aperçu de tous les items,
> ROOT=tirage pondéré), comme `expedition_chest_drops.tab`. Vérifié en jeu (grille GOLD/GEAR_TOKENS/DIAMONDS).
>
> **Schéma EXACT (bytecode `EventChestData.<init>`)** : le discriminant est `if (eventChestData.has("text")) …` — DEUX formats.
> **Format A** (`text` présent) : `text.preset` (String REQUIS) → résout les libellés d'écran via `EventPresets.properties` (le
> point dur historique). **Format B** (PAS de `text`, celui qu'on utilise) : `preset=""`, tous les libellés INLINE via 3 sous-objets
> REQUIS `selectionCard{title,info}`, `detailsScreen{title,info}`, `info{title,heading1,content1,heading2,content2[]}` (chaque
> `title`/`info` = `getString` requis) → **AUCUNE dépendance `preset`/bundle** (auto-suffisant, §4). Communs aux 2 : `cost`
> (getInt requis), `buyXNumber` (requis), `currency` (ResourceType, défaut ""), `maxBuys`/`maxPurchases`/`freeBuys` (défauts),
> `featured` (défaut), et **`config` = la TABLE DE DROPS inline** (String requis → `EventChestStats(String)` = `DHDropTableStats`
> avec DTCodes `ROOT`/`DISPLAY`). La carte `EventCardDisplay` doit PRÉCÉDER l'ExtraChest (lit `getComponent(EventCardDisplay).getImage()`).
> **Consommation** : `ChestType.EVENT` — coût/monnaie/limites/validation = logique du jeu (`getBasePurchaseCost`/`getPurchaseCurrency`/
> `getPurchaseCost`/`validateChestPurchase` branche EVENT → `getSingleEventChest`) sur le snapshot opérateur ; le loot NE vient PAS de
> `chests.tab` (`getDropTable(EVENT)`=null) mais de `getSingleEventChest().getStats().getTable().rollNode("ROOT", ChestContext(user))`
> (1 roll/coffre acheté). `giveChestRewards` reçoit le snapshot (sinon `getPurchaseCurrency(EVENT,null)` NPE). **Enregistrement =
> `OPERATOR_EVENTS`** (chemin persistant AdminEvents) : chaque `ServerContext.bind` (dont l'INTERNE à `openChest`) réinstalle depuis
> lui — un simple `install()` serait effacé au bind suivant.

**Ce que C'EST vraiment (g159)** : un COFFRE bonus complet (comme GOLD/DIAMOND CRATE) affiché temporairement sur l'écran CRATES, acheté
avec une monnaie (DIAMONDS par défaut), avec des free-buys/max-buys, et sa PROPRE table de drops. **Le contenu N'EST PAS une simple liste
de récompenses** : `EventChestStats extends DHDropTableStats` (ctor `EventChestStats(String)`) → le champ `content` de `eventChestData` est une
**TABLE DE DROPS pondérée au format `chests.tab`** (`NODE / WEIGHT / QUANTITY / RESULT / BEHAVIOR`, ex. `gold_chest_drops.tab`), pas
`[{kind:ITEM}]`. `EventChestDataDH.getStats()` renvoie cet `EventChestStats` ; `rollNode*` tire le loot. **Conséquence §4** : le drop-table du
coffre event est backend-authored (inline dans le JSON de l'event), au format table du jeu — le modéliser sur un coffre existant (`chests.tab`),
jamais inventer les poids/loot.
- **Structure** : `EventVisibility` + `EventCardDisplay` (carte, REQUISE) + `ExtraChest` (via `SpecialEventBuilder.createComponent("eventChestData")`
  — la fabrique câble `IEventChestStatsFactory` ; ctor direct impossible, arg = classe anonyme). Le sous-objet `eventChestData` = `EventChestData`.
- **Schéma `eventChestData` (clés relevées au bytecode)** : `content` (= drops `[{kind:ITEM,itemType,quantity}]`, réutilise le schéma reward
  cracké), `cost`, `currency` (ResourceType), `freeBuys`, `maxBuys`, `maxPurchases`, `buyXNumber`, `featured`, `title`/`heading`/`text`, + sous-écrans
  UI `detailsScreen`/`detailsScreenInfo`/`infoScreenContent`/`selectionCard`/`selectionCardInfo` avec un **`preset`** chacun.
- **POINT DUR = `preset`** (RÉCURRENT) : chaque écran référence un preset de `assets/strings/EventPresets.properties` (comme la carte de trial,
  §incr.8 — `preset` refuse ""/NONE/DEFAULT). Feasibility de la voie objet PROUVÉE : la carte + `createComponent` + le parsing content/cost/currency
  passent ; il reste à fournir des presets valides (ou wildcards « none.* ») pour les 3 écrans imbriqués. Parseur-oracle = la méthode (chaque
  `Named value not found: <clé>` = clé suivante), déjà démarré (bloqué sur `preset` d'un sous-écran).
- **Consommation** : `BaseEventSnapshot.getEventChests()`/`getSingleEventChest()` → l'écran CRATES affiche le coffre bonus ; l'achat passe par
  `ChestHelper` (type de coffre event, `getPurchaseCost`/`openChest`).
- **Plan d'incréments** : (1) résoudre les presets des 3 sous-écrans (trouver les noms réels ou un wildcard, comme MATCH_DISPLAY pour la carte) ;
  (2) `buildExtraChestEvent(id, drops, cost, currency, freeBuys, maxBuys, …)` + spec + AdminEvents ; (3) snapshot → CRATES (getSingleEventChest) ;
  (4) chemin d'achat/ouverture du coffre event (`openChest`) + crédit des drops ; (5) vérif EN JEU (CRATES montre le coffre bonus, achat/ouverture).

### B. Contest (CONTEST) — LEADERBOARD de tâches (solo ou guilde) = mode SERVEUR-AUTORITATIF — ✅ COMPLET & VÉRIFIÉ EN JEU (g161→g165)
> **✅ LIVRÉ & EN JEU (g165)** — les 5 incréments : (1) structure/builder ; (2) état per-user (`ServerContestData`, blob `AllContestData`,
> `GetAllContestData`) ; (3) wiring (`ServerContestData.prepare/record` + `ContestHelper.on*` sur campagne/coffre) ; (4) classement (ladder
> per-shard `contest_ladder`) + réclamation (progressRewards par courrier + rankRewards `--contest-end`) ; (5) EN JEU (écran CONTESTS rend
> titre/résumé/paliers/récompenses/rang). Handlers hall-of-fame vides (débloquent l'écran). `AdminEvents --contest …` (title/summary/task/
> progress/rank[-unit]/guild/end). 6 tests contest. **Autres hooks (surge/war/expedition/burn) = même patron `record` au besoin.**

### B(archive recon). Contest (CONTEST) — LEADERBOARD de tâches (solo ou guilde) = mode SERVEUR-AUTORITATIF complet — 🚧 EN COURS (incr.1 ✅ headless, g161)
> **Incr.1 LIVRÉ (g161, headless)** : schéma `Contest.load` cracké (formatVersion 0 → `contestInformation{guild,aggregate}` +
> `contestTask[]` [`ContestTaskInfo`: maxTimes/maxDailyTimes/pointsEarned/taskIndex + taskItem{taskData,taskData2,countNeeded,type,hidden}]
> + `contestProgressRewards[]` [`{pointsRequired, rewarditem}`] + `contestRankRewards[]` [`{kind:PERCENT|NUMBER, rank, rewarditem}`] ;
> **`rewarditem` en v0 = un drop ou un TABLEAU de drops** (`RewardGroup` isStatic=true), PAS `{rewardTarget,rewards}`). Ctor direct
> `new Contest(type, ContestTaskType.class)` — **ContestTaskType chargé par RÉFLEXION** (`Class.forName`), car dex2jar y laisse un
> attribut d'annotation de paramètre corrompu qui casse la compilation source. `buildContestEvent`+`specJsonContest`+`AdminEvents --contest`
> + `ContestTest` (structure/snapshot/round-trip). **Reste incr.2-5** (blob progression `IContestData`, wiring `ContestHelper.on*`, classement,
> réclamation, en jeu). **API repérée** : `ContestHelper.on*`(campaign/chest/item/resource/surge/war/expedition…)+`recordTasks` = crédit du jeu ;
> `IContestData`(getProgressPoints/getRankPoints/getCompletedCount/…) = l'état par-joueur.

### B. Contest (CONTEST) — LEADERBOARD de tâches (solo ou guilde) = mode SERVEUR-AUTORITATIF complet
**Mécanique RÉELLE (g159, helpshift PerBlue + communauté ; wiki fandom bloqué 402)** — cf. `perblue.helpshift.com/.../178-what-are-progress-and-rank-rewards`.
Un contest = event à durée limitée où le joueur gagne des POINTS en accomplissant un `ContestTaskType` (dépenser/gagner Gold/Diamonds/Mémoires/
Disk Power, gagner des combats Arena/Coliseum/Surge/City Watch, brûler des ressources…). **34 `ContestTaskType`** relevés : BATTLE_WON,
ENEMY_DEFEATED, BATTLE_POWER_DEFEATED(_ABOVE_OWN), BATTLE_WON_WITH_HERO_ROLE, BATTLE_HEROES_LEFT, OPEN_CHEST, ITEM_BURN/CRAFTED/EARN_*/ENCHANT_*/
GEAR_EQUIP, RARITY_*, RESOURCE_BURN/EARN, HERO_PROMOTED/EVOLVED/LEVELS_GAINED/SKILLS_LEVELED/MISSION_COMPLETE, FRIENDSHIP_MISSION_COMPLETE,
EXPEDITION_FINISHED, REAL_GEAR_*, REINFECTIONS_CLEANSED, WAR_ATTACK/SABOTAGE. **Deux familles de récompenses** :
- **progressRewards** = **5 paliers** (~375 000 / 430 000 / 500 000 / 750 000 / 1 000 000 points) → livrés IMMÉDIATEMENT par COURRIER dès le palier atteint.
- **rankRewards** = à la FIN du contest, selon le RANG (percentiles Top 1 % / 5 % / 10 % / 25 % / 50 %, + bonus rangs 1-10).
**1 contest sur 3 = GUILDE** (guildes vs guildes du shard ; membre >24 h pour toucher ; tous les membres = même reward). Rarement server-wide
(milestones collectifs). ⇒ colle au composant : `progressRewards`/`rankRewards` (schéma reward cracké), `guild`/`aggregate` booléens, `tasks`
(ContestTaskType). `ServerUser.deliverContestSeasonReward(seasonName, guildRank, tier)` existe DÉJÀ (courrier de fin, guild contest).
**Calendrier + featured hero (copie wiki fournie par l'user, g159)** : contest **HEBDOMADAIRE, du VENDREDI au JEUDI**. Le scoring tourne
souvent autour de dépenser/gagner des ressources (Gold/Disk Power/Memories/Diamonds) + jouer 3-5 héros SÉLECTIONNÉS en Arena/Coliseum/Surge/
City Watch. **⭐ Les RANK REWARDS sont la PREMIÈRE SORTIE d'un NOUVEAU HÉROS** (ex. Franny Robinson, Professor Ratigan, Penny Proud, Anne Boonchuy,
Chernabog) → le lot ultime du classement = des chips/le héros vedette exclusif (drop `kind:UNIT`). C'est un levier live-ops MAJEUR (sortie de héros
gatée par le rang de contest) → à modéliser fidèlement (le rankRewards top = `UNIT`/chips du héros vedette, calendrier hebdo Ven→Jeu).
- **Structure** : `new Contest(SpecialEventType.CONTEST, ContestTaskType.class)` (ctor direct) + `load` lit `tasks` (contestTask), `progressRewards`,
  `rankRewards` (réutilisent le schéma reward cracké), `contestInformation`, `aggregate`/`guild` (booléens solo/guilde/agrégé).
- **`ContestTaskType` (tâches)** : BATTLE_WON, ENEMY_DEFEATED, BATTLE_POWER_DEFEATED, ITEM_BURN, HERO_PROMOTED/EVOLVED/LEVELS_GAINED,
  EXPEDITION_FINISHED, FRIENDSHIP/HERO_MISSION_COMPLETE… = les actions qui font gagner des POINTS de concours.
- **Consommation** : `ContestHelper.onItemEarn(user, item, …)` (DÉJÀ appelé dans `openChest` via `giveChestRewards`) + `getActiveContestsWithTask` ;
  `ContestSnapshot.getTaskTypeMap()`. La PROGRESSION par-joueur + le CLASSEMENT + la RÉCLAMATION (progressRewards par palier, rankRewards par rang)
  sont **serveur-autoritatifs** (état persistant par-joueur/guilde, comme le ladder d'arène / Surge).
- **POINT DUR = c'est un MODE, pas un builder** : il faut (a) un BLOB serveur-autoritatif par-joueur (progression) + par-shard (classement), (b) le
  WIRING des déclencheurs de tâches (BATTLE_WON → combat, ITEM_BURN → burn, etc.) vers `ContestHelper.addProgress`, (c) la RÉCLAMATION
  (progressRewards + rankRewards). Feasibility du COMPOSANT : builds via ctor direct + schéma reward cracké ; mais l'effort = niveau ARÈNE/SURGE.
- **Plan d'incréments** : (1) `buildContestEvent` (tasks + progress/rank rewards) + spec + AdminEvents ; (2) blob progression par-joueur
  (`ServerContest`, patron `expeditionRun`/`arena_ladder`) + `GetContestData` ; (3) wiring des tâches (au moins BATTLE_WON via recordOutcome +
  ITEM_BURN, déjà partiel via onItemEarn) → addProgress ; (4) classement serveur-autoritatif + réclamation progress/rank ; (5) vérif EN JEU
  (écran CONTESTS : progression, paliers, classement, réclamation). NB : un `deliverContestSeasonReward` existe déjà côté `ServerUser` (guild contest).

**Ordre suggéré** : ExtraChest d'abord (plus court, effet visible CRATES, débloqué dès les presets résolus), puis Contest (chantier mode-sized).
