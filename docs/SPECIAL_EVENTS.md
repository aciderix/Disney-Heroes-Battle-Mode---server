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

### ⭐ FAIT §8 (g128, bytecode `DifficultyModeHelper.isOpen`) — DEUX leviers d'ouverture, ROTATION = `DropBonus`
`isOpen(mode, user, snap) = Unlockables.isUnlocked(mode, user) && (`
  `snap.isModeOpen(mode)`  **← MODES_OPEN = override, ouvre le mode QUEL QUE SOIT LE JOUR (ce qu'on utilise en incr. 1/2)**
  ` || ( snap.isModeDropBonusActive(mode) && getOpenDays(mode).contains(TimeUtil.getUserDailyActivityDayOfWeek(user)) ) )`
- `isModeDropBonusActive(mode)` = le `DropBonusSnapshot.getMultipliers()` **contient `mode` en clé** (bytecode `BaseEventSnapshot`).
- `getOpenDays(mode)` = **DONNÉE DU JEU** : `PortHelper.DOCKS_OPEN_DAYS`/`WAREHOUSE_OPEN_DAYS` (DOCKS [6,4,2,1] / WAREHOUSE
  [7,5,3,1]), `TrialsHelper.*_OPEN_DAYS` pour FRANCHISE_TRIALS.
⇒ **La ROTATION QUOTIDIENNE FIDÈLE n'est PAS un MODES_OPEN par jour** : c'est un event **`DropBonus`** déclarant les modes PORT
  → `isModeDropBonusActive`=true → **le jeu applique lui-même sa table `getOpenDays`** (DOCKS et WAREHOUSE alternent
  naturellement). MODES_OPEN reste l'override opérateur « forcer ouvert ».
- **Contrat `DropBonus.load(info, full, node)`** (bytecode) : lit `gameModeFilter`{include:[{gameMode:…}]} (via `EnumFilter`),
  `stuffFilter` (via `StuffFilter`), `bonus`:int (→ multiplier). Ctor `DropBonus(ISpecialEventType, Class<G> gameModeType,
  IStuffProvider)` → **construire via la fabrique du jeu `SpecialEventBuilder.createComponent("dropBonus")`** (comme la carte),
  puis `load` — provider/generics câblés par le jeu, rien à la main (§4).

3. ⬜ **`DropBonus` (ROTATION fidèle par jour) — PROCHAIN.** `ServerEvents.buildDropBonusEvent(id, modes, bonus, start, end)`
   (même patron que `buildModesOpenEvent`, composant via `createComponent("dropBonus")`) ; `bootDefaultEvents` bascule de
   « MODES_OPEN les 2 modes toujours » vers « DropBonus PORT → rotation `getOpenDays` ». Vérif EN JEU : avancer l'horloge
   serveur (`AdminClock`) sur un jour DOCKS puis un jour WAREHOUSE → seul le mode du jour ouvre. + **persistance shard** (config
   opérateur dans `shard_state` au lieu de ré-installer au bind).
4. ⬜ discounts marchands/coffres (`ChestDiscount`/merchant). 5. ⬜ `AdditionalChances`, `Contest`/`TeamLevel`. (Un builder par
   composant, même patron.)
