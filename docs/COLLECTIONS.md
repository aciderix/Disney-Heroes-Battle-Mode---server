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
2. ✅ **Maîtrise de combat (`recordHeroMastery`) — LIVRÉ (headless).** Fait établi : `CollectionMasteryUsesUpdate`
   est **OUTBOUND** (serveur→client : `GameMain` enregistre un handler, aucun envoi client) → le SERVEUR accumule la
   maîtrise en re-exécutant un combat, puis notifie. SurgeHelper/InvasionHelper `recordOutcome` l'accumulent déjà en
   interne ; **CampaignHelper.recordOutcome NON** (gap). Corrigé : `ServerUser.recordCampaignAttack`, sur
   `CombatOutcome.WIN` (miroir du client `ExpeditionAttackScreen`), passe `m.base.attackers`
   (Collection<AttackLineupSummary>) à `CollectionHelper.recordHeroMastery(user, attackers, mode)` (mode CAMPAIGN /
   ELITE_CAMPAIGN) → `incCollectionHeroMasteryUses` (write-through `individualUserExtra.collectionMasteryUses`,
   auto-persisté ; filtre `MIN_HERO_STARS_REQUIRED` interne). `CollectionMasteryTest` : WIN → maîtrise
   ELASTIGIRL/DAMAGE/BRONZE 0→1 (cumul sur 2 WIN) ; DÉFAITE → aucune accumulation ; persistance wire + DB.
   Régression 113 tests.
   - **✅ VÉRIFIÉ EN JEU — delta 0→1 NET (g106).** `ExpAdminCollectionFight` : team DAMAGE 6★ (MOANA, MERIDA,
     JACK_SPARROW, BEAST, BELLE) + lineup NORMAL_CAMPAIGN + niveau 1-1 pré-3★ (via héros jetable OLAF 1★, maîtrise
     non touchée) + **baseline PROPRE = maîtrise DAMAGE/BRONZE de TOUS les héros DAMAGE remise à 0**. En jeu : pilotes
     `campfight 1 1` (pousse `CampaignHeroChooserScreen`) → `campquick` (sélectionne les 5 héros via `unitSelected` +
     **`doQuickCombat()`** — l'exécuteur réel, qui NE gate PAS sur le bouton `canStartQuickFight`) → client joue le
     combat + envoie `CampaignAttack` → serveur **`CampaignAttack NORMAL 1-1 outcome=WIN → recordOutcome appliqué
     [persisté]`** → **DB : les 5 héros combattants passent maîtrise DAMAGE/BRONZE 0 → 1** (exactement +1 = un combat,
     baseline garantie 0). Pilotes DEV `campfight`/`campquick`, outil `ExpAdminCollectionFight`.
     ⇒ **COLLECTIONS #72 incr. 1 + 2 vérifiés EN JEU.**
3. ✅ **Mastery shop — ACHAT d'AVATAR (`BUY_COLLECTION_AVATAR`) — LIVRÉ + VÉRIFIÉ EN JEU (g107).** `Action
   BUY_COLLECTION_AVATAR{itemType=avatar}` → `LoginServer` → `ServerUser.applyBuyCollectionAvatar` ré-exécute
   `CollectionHelper.buyCollectionAvatar` : gate `getCumulativeCollectionLevel >=
   getCumulativeCollectionLevelRequiredForPortrait` (sinon `COLLECTION_AVATAR_LOCKED`) + débit **MASTERY_TOKENS**
   (`getAvatarCost`) + `giveUser(avatar item)`. **Sink des MASTERY_TOKENS gagnés par les claims (incr. 1)**. Persistance
   items/ressources write-through. Faits (sondes) : `COLLECTION_AVATAR_DAMAGE` = 100 tokens, requiert cumLevel(DAMAGE)>=8
   (Σ highestClaimed/tier, max 3/tier). `CollectionAvatarTest` : achat → MASTERY_TOKENS −100 + avatar possédé ;
   anti-triche verrou (`COLLECTION_AVATAR_LOCKED`, aucun débit) + tokens insuffisants ; persist wire+DB. Régression 114.
   - **✅ VÉRIFIÉ EN JEU + VISUEL (g107, id=1)** : `ExpAdminAvatar` (cumLevel DAMAGE=9 + 1000 tokens) → `buyavatar
     COLLECTION_AVATAR_DAMAGE` (chemin client réel `ClientActionHelper.buyCollectionAvatar`) → serveur
     `BUY_COLLECTION_AVATAR(COLLECTION_AVATAR_DAMAGE) appliqué [persisté]` → **DB : MASTERY_TOKENS 1000→900 (−100),
     avatar possédé=1** ; écran **HERO MASTERY SHOP** ré-ouvert affiche le solde **900** (chargé depuis notre serveur ;
     `build/avatar_shop_ingame.png`). Pilotes DEV `buyavatar`/`shopscreen`, outil `ExpAdminAvatar`.
   - ⬜ **`CLAIM_COSMETIC_COLLECTION`** (collections d'EMOJIS) : **logique serveur ABSENTE du jar 12.1.0** (aucun
     helper/consommateur — comme le filtre de profanité / `TOGGLE_HERO_FILTER`) ; le stockage est un `Set` runtime
     `claimedCosmeticCollections` (resync possible) mais la RÈGLE d'éligibilité (avoir toute la collection d'emojis)
     n'est pas exécutable sans l'inventer (§4). Documenté comme gap honnête (§2), non implémenté (pas « optionnel » —
     absent du jar, à revalider si une source de la règle apparaît).

⇒ **COLLECTIONS #72 : incr. 1 (claim) + 2 (maîtrise de combat) + 3 (mastery shop avatar) TOUS vérifiés EN JEU.**

## Notes §3/§4
- Zéro invention : états/récompenses = `CollectionHelper`/`ContentStats` ; anti-triche = les levées du jeu.
- **Round-trip profond** (leçon EXPEDITION/LINEUPS) : vérifier le CONTENU (mastery uses par héros, highest claimed
  par (type,tier)) après round-trip, pas juste le type wire.
