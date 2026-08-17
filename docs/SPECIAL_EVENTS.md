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

## Plan d'incréments

1. ⏳ **`ModesOpen`** (ce doc) : voie 1 (objet→toJson) OU exemple réel → `ServerEvents.buildModesOpenEvent` →
   persistance shard + push boot → **ouvrir PORT_WAREHOUSE en jeu (entrer & jouer)**. + FRANCHISE_TRIALS (même levier).
2. ⬜ `DropBonus` / `AdditionalChances` (bonus de butin / chances). 3. ⬜ discounts marchands/coffres. 4. ⬜ `Contest`/`TeamLevel`.
