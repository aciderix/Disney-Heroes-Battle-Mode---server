# WISHING_WELL (#72 mode suivant) — « Puits aux souhaits » (gacha ciblé de shards) — suivi

> Pipeline #73/#74 (recon bytecode). Chaque incrément : recon → logique du jeu (§3) → test headless (round-trip + DB)
> → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon bytecode)

Gacha de **shards de héros** ciblé : on **souhaite** (dépense une monnaie) → tirage pondéré de shards (héros
éligibles) ; on peut fixer un **héros CIBLE** qui biaise le tirage, avec un système de **pity** (poids qui montent :
`wishingWellHeroChipsWeight` / `wishingWellJackpotWeight`). Le **tirage lui-même passe par le système de COFFRES
existant** (`ChestType.WISH` → `ChestStats.getDropTable` + `DropTable.roll`, déjà géré par `ServerUser.openChest`) — la
cible/poids du puits biaisent la table. `WishingWellHelper.isUnlocked` gate.

### Action (client → serveur)
- **`Action SET_WISHING_WELL_TARGET_HERO{ heroType=cible }`** (émetteur `ClientActionHelper` ; la cible passe par le
  champ `Action.heroType`) → `WishingWellHelper.setTargetHero(user, hero)`.
- **Le WISH** = ouverture d'un coffre `ChestType.WISH` (message `BuyChests`, handler `ServerUser.openChest` EXISTANT) :
  le drop est biaisé par `wishingWellHero`/poids ; les poids de pity sont mis à jour par la logique de tirage du jeu.

### Logique du jeu (§3 — point d'entrée à EXÉCUTER)
- **`WishingWellHelper.setTargetHero(user, hero)`** : valide `hero ∈ getAllEligibleHeroes` (sinon rejet/no-op) ; pose
  `IIndividualUser.setWishingWellHero(hero)` ; horodate (`setTime`) + compteur de changement (`setCount(UserFlag)`) ;
  ajuste les poids (`getWeightConstants`/`checkMinWeights`). Zéro invention (§4) : probabilités/poids = `WishingWellStats`.
- Lecture : `getTargetHero`, `getProbabilities(user)`, `getAllEligibleHeroes`, `isUnlocked`.

### Données / état & persistance
- **Write-through** : `IndividualUserExtra.wishingWellHero` (UnitType) + `wishingWellHeroChipsWeight` +
  `wishingWellJackpotWeight` (float, pity). Compteur de changement de cible = `UserFlag` (→ `resyncCounts`).
  ⇒ persistance quasi-gratuite. Aucun blob dédié.

## Plan d'incréments
1. ✅ **`SET_WISHING_WELL_TARGET_HERO` + `ServerUser.applySetWishingWellTarget`** : ré-exécute
   `WishingWellHelper.setTargetHero` (valide éligibilité, pose la cible + poids + cooldown), persiste (write-through +
   `resyncCounts`). Anti-triche = héros non éligible → rejet. Test `WishingWellTargetTest`. **✅ VÉRIFIÉ EN JEU + VISUEL**
   (voir §Vérif en jeu incr.1).
2. ✅ **WISH (`ChestType.WISH`)** : le souhait (coffre WISH via `openChest`) roule la table du puits BIAISÉE par la
   cible + crédite les shards + débite les DIAMONDS + **fait monter la RAMPE de pity** + persiste. **✅ VÉRIFIÉ EN JEU
   + VISUEL** (voir §Vérif en jeu incr.2 + §Rampe de pity).

## Vérif en jeu — incrément 1 (✅ EN JEU + VISUEL, id=1)
- Setup : `SetTeamLevel 65` (débloque `WISH_CHEST` req TL 30). id=1 : TL 65, puits débloqué, cible=DEFAULT, 274 héros
  éligibles (RALPH/VANELLOPE inclus).
- Pilote `wishtarget RALPH` (chemin client réel `ClientActionHelper.setWishingWellTargetHero`) → serveur
  **`SET_WISHING_WELL_TARGET_HERO(RALPH) appliqué [persisté]`** (+ `[wishing-well] cible = RALPH [persisté]`).
- **DB `server/data/dh-server.db` : `wishingWellHero=RALPH`** (lecture WAL-aware live).
- **CONFIRMATION VISUELLE** : `wishscreen` ouvre le VRAI écran **WISH CRATE** (`WishingWellChestScreen`, chemin réel) →
  portrait de **RALPH** à gauche + **JACKPOT CHIP CHANCES** = 1 000 shards RALPH @ 1,00 % + **REGULAR CHIP CHANCES** =
  100-300 shards RALPH @ 10,00 % (table biaisée vers la cible) — capture `desktop-port/build/ww_screen.png`. Pilotes DEV
  `wishtarget <HERO>` / `wishscreen`, outil `SetTeamLevel`.

## Vérif en jeu — incrément 2 (✅ EN JEU + VISUEL, id=1)
- Setup : `WishAdmin` (TL 65, 100 000 DIAMONDS, cible=RALPH). Pilote `wish 1` (chemin client réel
  `ChestHelper.openChestInner` → `BuyChests{chestType=WISH}` + `ServerRollRequest`, sans boîte de confirmation).
- 23 souhaits → serveur `<== BuyChests` (roule `WISHING_WELL_DROPS` biaisée cible + débite DIAMONDS) → **DB : DIAMONDS
  débités (500/souhait), items RALPH crédités** : `STONE_RALPH=480`, `EPIC_CHIP_RALPH=520`, `BIT_RALPH_HEALING=70`,
  `BIT_RALPH_LONGER_STUNS=35` — **TOUS les drops de héros sont RALPH (la cible)** = biais confirmé. Persistés en DB.
- **CONFIRMATION VISUELLE** : la fenêtre de résultat **CRATE REWARDS** (servie par notre serveur) montre un lot de
  **300 puces RALPH** (« 300/200 » + portrait RALPH) — capture `desktop-port/build/wish_result_ingame.png`. Pilote DEV
  `wish [count]`. Outil `WishAdmin`.

## Rampe de pity — RÉELLE (correction : ce N'ÉTAIT PAS un gap)
> **Erreur initiale (g109) corrigée (g110).** J'avais conclu que la rampe de pity était « absente du jar » (gap §4)
> après n'avoir cherché que les `*Helper`/`*Stats` — **sans lire les `.tab` ni la couche UI**. C'était FAUX. Les `.tab`
> (`wishing_well_weights.tab`) contiennent les multiplicateurs de pity, ET la règle qui les applique existe dans le
> jar. Leçon (§8) : rien n'est un gap tant que ce n'est pas prouvé — chercher AUSSI l'UI + les `.tab`.

La **rampe de pity** est appliquée par tirage. La RÈGLE vit dans `WishingWellChestResultWindow.reachedDestination(
LootResults, RewardDrop, int)` — une classe **UI liée à GL**, donc **non instanciable headless** : on ne peut pas
l'EXÉCUTER, on la **TRANSCRIT fidèlement au bytecode près**. Les **valeurs** viennent des `.tab` (jamais inventées, §4) :
`wishing_well_weights.tab` → `WeightConstants` `JACKPOT_MULT_X=1.1`, `JACKPOT_MULT_Y=1.01`, `HERO_CHIPS_MULT_Z=1.1`,
`JACKPOT_10X_BONUS_MULT=1.05`, bases `JACKPOT_BASE=1`, `HERO_CHIPS_BASE` (2.02/6.06/10.11 selon NEW/RECENT/OLD).

Règle (init depuis `LootResults.old*` comme `setLootResults`), pour chaque drop dans l'ordre :
- **bonus 10x** (`hasBulkBonus`, aux frontières de lot `rowIndex % getMultiBuyCount == 0`) : `jackpot *= JACKPOT_10X_BONUS_MULT` ;
- **drop JACKPOT** (`RewardDrop.flags & 16`) → `jackpot = JACKPOT_BASE` ; `heroChips = HERO_CHIPS_BASE` (**reset**) ;
- **drop STONE** (`ItemStats.getCategory(itemType) == STONE`, hors jackpot) → `jackpot *= JACKPOT_MULT_X` ; `heroChips = HERO_CHIPS_BASE` ;
- **sinon (générique)** → `jackpot *= JACKPOT_MULT_Y` ; `heroChips *= HERO_CHIPS_MULT_Z`.

Persistance via le **code du jeu** `ChestHelper.updateWishingWellWeights(user, jackpot, heroChips)` (write-through
`individualUserExtra`). ⇒ la pity **monte réellement** au fil des tirages malchanceux et se **réinitialise au jackpot**,
comme le jeu ; l'écran WISH voit ses probas augmenter. Vérifié `WishingWellWishTest` (direction, continuité entre
tirages = persistance, accumulation base 1.0 → peak ~4). Statut SHIMS = **RÉEL** (transcription fidèle GL-only + `.tab`).

## Contrat industriel (ModeGraph `--mode com/perblue/heroes/ui/wishingwell/`)
- **Gate** : `Unlockable.WISH_CHEST` (TL 30). Écran en **lecture seule** côté messages (la cible passe par un `Action`,
  pas un message de mode → normal que ScreenContract ne détecte « aucun message client→serveur »).
- **A/B (serveur→client, à peupler par le WISH)** : `LootResults{ .lootDrops, .oldWishHeroChipsWeight,
  .oldWishJackpotWeight }` + `RewardDrop{ .flags, .itemType }`. Les deux `oldWish*Weight` = **poids de pity AVANT** le
  souhait (l'écran anime la progression de pity) → l'incr. 2 devra les renseigner dans la réponse d'ouverture du coffre
  `ChestType.WISH`. Messages référencés : `ChestType`, `ItemType`, `LootResults`, `ResourceType`, `RewardDrop`, `UnitType`.

## Notes §3/§4
- Le tirage réutilise le codec+RNG de coffre du jeu (client-autoritatif partiel #25/§4bis, comme les autres loots).
- Round-trip profond : vérifier `wishingWellHero` + poids de pity après round-trip, pas juste le type.
