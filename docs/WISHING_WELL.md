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
2. ⬜ **WISH (`ChestType.WISH`)** : vérifier que le tirage du puits (coffre WISH, handler openChest existant) respecte
   la cible/poids + met à jour la pity ; persistance des poids. Vérif en jeu (souhait → shards + cible/pity).

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

## Contrat industriel (ModeGraph `--mode com/perblue/heroes/ui/wishingwell/`) — pour l'incrément 2
- **Gate** : `Unlockable.WISH_CHEST` (TL 30). Écran en **lecture seule** côté messages (la cible passe par un `Action`,
  pas un message de mode → normal que ScreenContract ne détecte « aucun message client→serveur »).
- **A/B (serveur→client, à peupler par le WISH)** : `LootResults{ .lootDrops, .oldWishHeroChipsWeight,
  .oldWishJackpotWeight }` + `RewardDrop{ .flags, .itemType }`. Les deux `oldWish*Weight` = **poids de pity AVANT** le
  souhait (l'écran anime la progression de pity) → l'incr. 2 devra les renseigner dans la réponse d'ouverture du coffre
  `ChestType.WISH`. Messages référencés : `ChestType`, `ItemType`, `LootResults`, `ResourceType`, `RewardDrop`, `UnitType`.

## Notes §3/§4
- Le tirage réutilise le codec+RNG de coffre du jeu (client-autoritatif partiel #25/§4bis, comme les autres loots).
- Round-trip profond : vérifier `wishingWellHero` + poids de pity après round-trip, pas juste le type.
