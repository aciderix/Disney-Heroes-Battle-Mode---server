# SAVED_LINEUPS (#72 mode suivant) — « Lineups enregistrés » (compositions d'équipe nommées) — suivi

> Attaqué au pipeline industrialisé #73/#74 (`ModeGraph`/`ScreenContract` + recon bytecode), comme
> SURGE/CHALLENGES/FRIENDSHIPS/EXPEDITION/ENCHANTING. Chaque incrément : recon → logique du jeu (§3) → test headless
> (WireCheck **profond** + round-trip DB) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon bytecode)

Système de **compositions d'équipe enregistrées** : le joueur sauve des lineups nommés (20 slots `SAVED_1..SAVED_20`)
ET des lineups **par mode** (`NORMAL_CAMPAIGN`, `EXPEDITION`, `WAR_*`, `COLISEUM_*`, `FIGHT_PIT_*`, `SURGE`…). Chaque
lineup = jusqu'à 5 héros + un mercenaire + les **options de real-gear** et les **choix de slots de stats émeraude** par
héros. **Feature cœur** (pas de gate `Unlockable` trouvé — atteignable partout). UI = `SavedLineupHeroChooserScreen`
(bouton SAVE + nommage). `HeroLineupType` a aussi des valeurs non-sauvegardables (DEFAULT, HERO_LIST_SCREEN…) filtrées
par `SavedLineupHelper.isSavedLineupType`.

### Messages
- **`HeroLineupUpdate{ type:HeroLineupType, iD:long, lineup:HeroLineup, customName:String, realGearOptions:Map,
  emeraldStatSlotChoices:Map }`** (top-level, fire-and-forget) — sauvegarde/mise à jour d'un lineup.
  `HeroLineup{ heroes:List<UnitType>, mercenaryType:UnitType }`.
- **`CheckLineupName{ name }` → `CheckLineupNameResult{ isValid, name }`** — validation du nom (requête/réponse).

### Logique du jeu (§3 — points d'entrée à EXÉCUTER)
- **`IUser.setHeroLineup(type, id, lineup, expiration, customName, emeraldStatSlotChoices, realGearOptions)`** —
  écrit dans la Map runtime `User.lineups` (clé `HeroLineupKey{lineupType, id}`, valeur `UserHeroLineupData`).
  **Fait §4 (bytecode `ClientActionHelper.saveHeroLineup`)** : après l'envoi, le client applique localement avec
  **`expiration = Long.MAX_VALUE`** (9223372036854775807 = permanent). Le serveur miroite EXACTEMENT cette valeur.
  Effets de bord PvP (cooldowns `ArenaHelper.setHeroLineupCooldown` pour `FIGHT_PIT_DEFENSE`/`COLISEUM_DEFENSE_3`) =
  variantes de défense, incrément ultérieur.
- Lecture : `User.getHeroLineup(type[, id])`, `getHeroLineupData(type)`, `getHeroLineupName(type)`.

### Données / état & persistance
- **Foyer wire = `UserExtra.heroLineups`** (`List<UserHeroLineupData>`). Le converter charge cette liste dans
  `User.lineups` via `setHeroLineups(List)` (re-clé par `data.lineupType`/`data.iD`). L'état vit **hors `this.extra`**
  (Map runtime privée) → **persistance par RESYNC** (`ServerUser.resyncLineups`), pas write-through.
- ⚠️ **ANGLE MORT (leçon EXPEDITION) confirmé au bytecode** : `setHeroLineup` NE pose PAS `data.lineupType`/`data.iD`
  sur le `UserHeroLineupData` (il les garde dans la clé `HeroLineupKey` de la Map). Un resync naïf `values()→list`
  collapserait TOUT sur `(DEFAULT, 0)` au reload (`setHeroLineups` re-clé par ces champs). ⇒ `resyncLineups` doit
  **recopier la clé → data.lineupType/data.iD** (HeroLineupKey a `id`+`lineupType` publics) AVANT d'ajouter à la liste.
  Test = round-trip PROFOND (plusieurs types/ids distincts survivent, pas juste le type wire).

## État de départ (découvert en recon)
Le CŒUR existait déjà via **ARÈNE #41** : `LoginServer` route `HeroLineupUpdate` → `ServerUser.applyHeroLineupUpdate`
(→ `User.setHeroLineup(type, iD, lineup, 0L, customName, realGearOptions, emeraldStatSlotChoices)` — **ordre des Maps
testé** : realGearOptions PUIS emeraldStatSlotChoices, sinon ClassCast à la sérialisation) + `resyncLineups` +
persistance. Vérifié en jeu pour les lineups de défense arène (id=0). **Ce mode #72 étend/durcit** : lineups
SAVED_* nommés + ids non-nuls + validation de nom.

## Plan d'incréments
1. ✅ **`resyncLineups` DURCI (angle mort ids non-nuls) + `HeroLineupUpdate` pour SAVED_* — LIVRÉ (headless)** :
   l'ancien `resyncLineups` itérait `HeroLineupType.values()` via `getHeroLineupData(t)` qui **hardcode id=0** →
   il RATAIT les lineups à id non-nul (perte de persistance). Réécrit pour **itérer la Map runtime `User.lineups`**
   (réflexion) et **recopier la clé `HeroLineupKey{type,id}` → `data.lineupType`/`data.iD`** (le loader `setHeroLineups`
   re-clé par ces champs). `LineupSaveTest` : 4 lineups (SAVED_1/SAVED_2 nommés + EXPEDITION par-mode + **SAVED_3#42
   à id non-nul**), round-trip wire + DB PROFOND (type+id+nom+héros+merc survivent ; SAVED_3#42 ne collapse pas sur
   `(SAVED_3,0)` ni `(DEFAULT,0)`), update en place (pas de doublon). Régression 109 tests. Fait §4 : `new
   HeroLineup().mercenaryType = UnitType.DEFAULT` (sentinelle « pas de merc », jamais null sur le wire).
2. ✅ **`CheckLineupName` → `CheckLineupNameResult` — LIVRÉ (headless)** : handler requête/réponse `LoginServer`. La
   validation est SERVEUR (absente du jar client) → on RÉUTILISE la logique du jeu `NameChangeHelper.isNameLegal`
   (codepoints valides + `ILLEGAL_NAMES` réservés) + non-vide, plutôt qu'inventer (§3/§4). **PARTIEL honnête (§2)** :
   le **filtre de profanité** n'est PAS dans le jar 12.1.0 (service serveur externe ; ex. « fuck » passe `isNameLegal`)
   — on valide ce que le jeu expose réellement ; les noms de lineup sont personnels/cosmétiques. Sans réponse, la
   fenêtre de nommage resterait bloquée en jeu.
3. ✅ **VÉRIFIÉ EN JEU (g102, compte id=1 TL100)** : `ExpAdminLineup` (héros RALPH/VANELLOPE/ELASTIGIRL possédés) →
   `savelineup SAVED_1 MyTeam RALPH+VANELLOPE+ELASTIGIRL` (chemin client réel `ClientActionHelper.saveHeroLineup`) →
   client `HeroLineupUpdate` → serveur **`HeroLineupUpdate(SAVED_1) → lineup enregistrée [persistée]`** → **DB** :
   SAVED_1 nom=« MyTeam » héros=[RALPH, VANELLOPE, ELASTIGIRL]. 2ᵉ save `SAVED_2 Bravo VANELLOPE+ELASTIGIRL` →
   coexiste en DB (nom=« Bravo »). **`checkname MyDefense`** (`CheckLineupName`) → serveur
   **`CheckLineupName("MyDefense") → isValid=true`** (répond `CheckLineupNameResult`). Rechargé depuis la DB = survit
   au reload. Pilotes DEV `savelineup`/`checkname`, outil `ExpAdminLineup`. ⇒ **SAVED_LINEUPS #72 vérifié en jeu**
   (sauvegarde nommée multi-lineups + validation de nom + persistance).
   - ⬜ Reste OPTIONNEL (non bloquant) : cooldowns de défense PvP (`FIGHT_PIT_DEFENSE`/`COLISEUM_DEFENSE_3` →
     `setHeroLineupCooldown`) si un flux arène/coliseum les exerce (déjà couverts par ARÈNE #41 pour la défense).

## Notes §3/§4
- Zéro invention : `setHeroLineup` = logique du jeu ; expiration 0L (ARÈNE #41 testé ; le client applique
  Long.MAX_VALUE localement mais la lecture ignore l'expiration → équivalent pour la persistance).
- **WireCheck profond** : vérifier le CONTENU (type/id/nom/héros par lineup) après round-trip, pas juste le type.
