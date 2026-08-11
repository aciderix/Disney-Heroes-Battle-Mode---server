# COLLECTIONS (#72 mode suivant) — « Collections » (maîtrise de héros par collection) — suivi

> Pipeline #73/#74 (recon bytecode + `ModeGraph`). Chaque incrément : recon → logique du jeu (§3) → test headless
> (round-trip profond + DB) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon bytecode)

Système de **maîtrise (mastery) de héros regroupés en COLLECTIONS** : chaque héros appartient à des collections
(`CollectionType` — 29 : rôles TANK/DAMAGE/SUPPORT/CONTROL, franchises ZOOTOPIA/INCREDIBLES/…, tags STUN/CHARM/…).
Jouer un héros en combat accumule des **« mastery uses »** ; cumulées, elles montent le **niveau de collection**, qui
franchit des **paliers (tiers)** `BRONZE < SILVER < GOLD < PLATINUM` → on **réclame des récompenses** + la collection
donne des **modificateurs de combat** (`getCollectionCombatModifiers`). Gaté `COLLECTIONS` (`unlockables.tab` :
`TRUE 26 25 26 19`, TL~19-26 → atteignable TL100). UI `com.perblue.heroes.ui.collections.*`.
Collections **cosmétiques** séparées (emojis, `CosmeticCollectionType` — 862, avatars/bordures).

### Messages / Actions (client → serveur)
- **`Action CLAIM_COLLECTION_REWARDS{ TYPE:CollectionType, TIER:CollectionTier, LEVEL:int }`** — réclame les
  récompenses d'un niveau de palier atteint. (émetteur `ClientActionHelper.claimCollectionRewards(type, tier, level)`).
- **`CollectionMasteryUsesUpdate{ updates:Map<CollectionType, CollectionMasteryUses> }`** — accumulation de maîtrise
  après combat (`CollectionHelper.recordHeroMastery`). `CollectionMasteryUses{ heroes:Map<UnitType,Integer> }`.
- **`Action CLAIM_COSMETIC_COLLECTION`** / **`BUY_COLLECTION_AVATAR`** — cosmétique (emoji/avatar/bordure).
- DEBUG (dev) : `DEBUG_SET_MASTERY_USES`, `DEBUG_RECORD_MASTERY_USES`, `DEBUG_SET_HIGHEST_CLAIMED_COLLECTION_LEVEL`.

### Logique du jeu (§3 — points d'entrée à EXÉCUTER)
- **`CollectionHelper.claimCollectionRewards(user, type, tier, level)` → List** : `getCollectionState(user, type,
  tier, level)` ; si l'état n'est pas **`CLAIMABLE`** → `throw ClientErrorCodeException(ERROR)` (anti-triche = on ne
  réclame pas un palier non atteint / déjà pris) ; sinon crédite les récompenses (`getCollectionRewards`) + monte le
  niveau réclamé.
- **`CollectionState`** : `LOCKED_FEATURE_DISABLED / LOCKED_LEVEL_NOT_AVAILABLE / LOCKED_NOT_ENOUGH_STARS /
  LOCKED_TL_TOO_LOW / LOCKED_PREVIOUS_TIER_INCOMPLETE / LOCKED_PREVIOUS_LEVEL_INCOMPLETE / IN_PROGRESS / CLAIMABLE /
  COMPLETED`.
- `recordHeroMastery(user, unit|collection, GameMode, update)` (mastery de combat) ; `getCumulativeCollectionLevel`,
  `getCollectionRewards`, `getCollectionCombatModifiers`, `getHeroStarsRequired(tier)`. DEV
  `debugSetHeroMasteryUses`/`debugSetHighestClaimedLevel`.

### Données / état & persistance
- **Write-through** : accesseurs `IndividualUser` (`getCollectionHeroMasteryUses`/`incCollectionHeroMasteryUses`/
  `getHighestClaimedCollectionLevel`/`clearCollectionHeroMasteryUses`) écrivent DANS
  `individualUserExtra.collectionMasteryUses` (Map<clé(type,tier), CollectionMasteryUses{heroes:Map<UnitType,Int>}>)
  et `individualUserExtra.collectionsClaimed` (highest claimed level par (type,tier)). `cosmeticCollectionsClaimed`
  (List). ⇒ **persistance quasi-gratuite** (comme cooldowns/amitiés) ; récompenses via `resyncHeroes`/`resyncDiamonds`/
  `resyncCounts`. Aucun blob dédié.

## Plan d'incréments
1. ✅ **CLAIM d'un palier (`CLAIM_COLLECTION_REWARDS`) — LIVRÉ + VÉRIFIÉ EN JEU (g105).** Handler `LoginServer`
   (dispatch `act.command`, extras `TYPE`/`TIER`/`LEVEL`) → `ServerUser.applyClaimCollection` ré-exécute
   `CollectionHelper.claimCollectionRewards` (anti-triche = levée du jeu si non `CLAIMABLE` :
   `COLLECTION_ALREADY_CLAIMED` / `NOT_ENOUGH_MASTERED_HEROES`), crédite + persiste (write-through `collectionsClaimed`
   + resync récompenses). **Faits (sondes)** : niveau claimable = `getNumMasteredHeroes >=
   getNumMasteredHeroesRequiredForLevel(type,level)` ; héros maîtrisé = `masteryUses >=
   getNumUsesRequiredForMastery(tier)=20` + étoiles >= `getHeroStarsRequired(BRONZE)=3` ; DAMAGE/BRONZE/lvl1 = 5 héros.
   `CollectionClaimTest` : claim → highest 0→1 + `MASTERY_TOKENS +8` ; re-claim refusé (`COLLECTION_ALREADY_CLAIMED`,
   pas de double crédit) ; non-gagné refusé (`NOT_ENOUGH_MASTERED_HEROES`) ; persistance wire + DB. Régression 112 tests.
   - **✅ VÉRIFIÉ EN JEU + VISUEL (g105, id=1)** : `ExpAdminCollection` → DAMAGE/BRONZE CLAIMABLE (6 héros maîtrisés).
     `collectionscreen DAMAGE` → écran RÉEL **« Bronze I : HEROES MASTERED 5/5, NEXT REWARD 14+8, bouton CLAIM »**
     (chargé depuis NOTRE serveur ; captures `build/coll_before_ingame.png`). `claimcollection DAMAGE BRONZE 1`
     (chemin client réel `ClientActionHelper.claimCollectionRewards`) → serveur `DAMAGE/BRONZE niv.1 réclamé
     (highest 0→1) [persisté]` → **écran ré-ouvert : Bronze II (0/10, DETAILS) + Silver I DÉVERROUILLÉ**
     (`coll_after_ingame.png`) → **DB : highestClaimed(DAMAGE,BRONZE)=1, MASTERY_TOKENS=8**. Pilotes DEV
     `claimcollection`/`collectionscreen`, outil `ExpAdminCollection`.
2. ⬜ **Maîtrise de combat (`CollectionMasteryUsesUpdate` → `recordHeroMastery`)** : accumulation persistée.
3. ⬜ **Cosmétique (`CLAIM_COSMETIC_COLLECTION`/`BUY_COLLECTION_AVATAR`)** — évalué, pas présumé optionnel.

## Notes §3/§4
- Zéro invention : états/récompenses = `CollectionHelper`/`ContentStats` ; anti-triche = les levées du jeu.
- **Round-trip profond** (leçon EXPEDITION/LINEUPS) : vérifier le CONTENU (mastery uses par héros, highest claimed
  par (type,tier)) après round-trip, pas juste le type wire.
