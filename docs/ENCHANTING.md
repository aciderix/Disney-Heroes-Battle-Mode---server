# ENCHANTING (#72 mode suivant) — « Enchantement » d'équipement — suivi d'implémentation

> Attaqué au pipeline industrialisé #73/#74 (`contract.sh --mode Enchant` + `ModeGraph --logic`), comme
> SURGE/CHALLENGES/FRIENDSHIPS/EXPEDITION. Chaque incrément : contrat → logique du jeu (§3) → test headless
> (WireCheck **profond** + ClientOracle) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon bytecode)

Système de **progression d'équipement** : on **enchante** une pièce d'équipement d'un héros (par slot) en consommant
des **matériaux d'enchantement** (+ **OR**, et **DIAMANTS** optionnels pour combler), ce qui augmente les **étoiles /
points d'enchant** de l'objet → **bonus de stats**. Gaté **`Unlockable.ENCHANTING` = TL 35** (`unlockables.tab`).
Écrans UI côté héros (détail héros → slot d'équipement → ENCHANT). **Reachable à TL100** (compte de test).

### Message (client → serveur)
- **`EnchantItem{ hero:UnitType, slot:HeroEquipSlot, itemsUsed:Map<ItemType,Integer>, useDiamonds:boolean,
  specialEvents:SpecialEventsUsed }`** — le client déclare quel héros/slot, quels matériaux consommer, et s'il paie le
  débordement en diamants. **Pas de message de réponse dédié** : l'enchant vit sur l'objet équipé du héros (renvoyé au
  boot via les héros / `HeroUpdate`). Client-autoritatif partiel : le serveur RÉ-EXÉCUTE l'autorité.

### Logique du jeu (§3 — point d'entrée à EXÉCUTER)
- **`EnchantingHelper.enchantItem(user, hero, slot, itemsUsed:Map, useDiamonds:boolean, snap)` → `IEquippedItem`** :
  récupère `hero.getItem(slot)` ; itère `itemsUsed` (matériaux) ; **`getEnchantGoldCost(...)` → lève `NOT_ENOUGH_GOLD`**
  si insuffisant ; contrôle le plafond `EnchantingStats.getMaxStars(rarity)` vs `item.getStars()` ; débite **DIAMANTS**
  (`getEnchantMaxDiamondCost`, si `useDiamonds`/débordement) + **OR** (`chargeUser(GOLD, cost)`) ; consomme les matériaux
  (`useItem`/`removeItem`) et monte l'enchant (étoiles/points) de l'objet.
- Coûts/données (§4, jamais inventé) : `getEnchantGoldCost`, `getEnchantMaxDiamondCost`, `getEnchantPoints(ItemType,…)`,
  `EnchantingStats` (`PointStats`, `StatIncreaseStats`, `getMaxStars`, `Constants`). Matériaux : `ENCHANTING_MATERIALS`
  / `PRIME_ENCHANTING_MATERIALS`.

### Données / état & persistance
- L'état d'enchant est porté par l'**objet équipé** du héros (`IEquippedItem` : `getStars()`/points d'enchant) — donc
  dans les **héros** de l'utilisateur. **Persistance quasi-gratuite** : `ServerUser.resyncHeroes` (déjà en place)
  recopie les héros (et leurs items) vers `individualUserExtra.heroes` ; l'OR/DIAMANTS via `resyncDiamonds` +
  write-through `resources`. Aucun blob dédié ni état backend à générer (contrairement aux wards/shop).

## Plan d'incréments
1. ✅ **Handler `EnchantItem` + `ServerUser.applyEnchantItem` — LIVRÉ + VÉRIFIÉ EN JEU (g98)** : ré-exécute
   `EnchantingHelper.enchantItem` (débite OR/diamants + matériaux, monte l'enchant), anti-triche = les levées du jeu
   (`NOT_ENOUGH_GOLD`, `DONT_HAVE_ITEM`, plafond d'étoiles) → refus autoritatif propre ; persiste
   (`resyncHeroes`/`resyncDiamonds`). DEV : `ServerUser.debugGiveFullGear` (`HeroHelper.giveFullGear`) pour équiper
   du gear enchantable (les héros grantés n'ont pas de gear).
   - `EnchantApplyTest` : slot ONE (PC_FLYERS ORANGE) enchanté avec 30 VOID_DUST → **étoiles 0→2**, **or −63000**
     (coût exact `getEnchantGoldCost`), matériaux débités ; persistance **PROFONDE** (round-trip wire + DB — les étoiles
     d'enchant sur l'objet du héros survivent) ; anti-triche sans or (`NOT_ENOUGH_GOLD`, pas de débit). Régression 105 tests.
   - **✅ VÉRIFIÉ EN JEU (g98, compte TL100)** : compte préparé (`ExpAdminEnchant` : RALPH + gear complet + 50 VOID_DUST
     + or) → `enchant RALPH ONE VOID_DUST 30` (chemin client réel `ClientActionHelper.enchantItem`) → client `EnchantItem`
     → serveur **`RALPH/ONE enchanté (or -63000) [persisté]`** → **DB confirmée** : slot ONE PC_FLYERS **étoiles 0→2**,
     VOID_DUST 50→20, or −63000. Pilote DEV `enchant <HERO> <SLOT> <MATERIAL> <count>`.
2. ✅ **Coûts & garde-fous — LIVRÉ + VÉRIFIÉ EN JEU (g99)** : barème DU JEU (§4) : `getMaxStars` par rareté
   (WHITE=0/GREEN=1/BLUE=3/PURPLE/ORANGE/RED/YELLOW=5) ; **plafond d'étoiles** enforced (au max → refus) ;
   **matériaux insuffisants** (demande > possédé, sans diamants) → refus, aucun débit ; **coût OR exact**
   (`getEnchantGoldCost`) ; **chemin DIAMANTS** (`useDiamonds=true` → paie `getEnchantMaxDiamondCost` → item au MAX
   d'un coup, matériaux NON consommés) ; anti-triche diamants insuffisants → refus. `EnchantGuardTest`. Régression 106.
   - **✅ VÉRIFIÉ EN JEU (g99)** : `enchant RALPH TWO VOID_DUST 0 diamonds` (`useDiamonds=true`) → serveur
     `RALPH/TWO enchanté (or -0, diamants -3360) [persisté]` → DB : slot TWO (ROCKET_PACK_PATCH_KIT, PURPLE)
     **étoiles 0→5 (MAX)**, diamants 50000→46640 (−3360 = coût max exact de CET item), ni or ni matériaux.
3. ✅ **Vérif EN JEU multi-slots/raretés — COUVERTE (g98-g99)** : slot ONE (PC_FLYERS, ORANGE) via MATÉRIAUX+OR (g98) ;
   slot TWO (ROCKET_PACK_PATCH_KIT, PURPLE) via DIAMANTS (g99).
4. **MAX-UPGRADE PRIME BADGES + gear RED/YELLOW — REQUIS (non facultatif)** : message dédié `EnhanceMaxPrimeBadge{
   unitType, perBadgeItems:List, totalItems:Map, executionOrder:List, specialEvents}` (bouton « MAX » de l'écran
   d'enchant : enchante d'UN COUP TOUS les slots enchantables d'un héros jusqu'à leur plafond). Handler `LoginServer`
   → **`ServerUser.applyMaxPrimeBadge`** : serveur AUTORITATIF **ré-dérive le plan depuis l'état persisté**
   (`EnchantingHelper.buildMaxUpgradePlanForHero(user, type, snap)`, qui n'utilise que les items possédés + le barème
   du jeu) puis l'applique (`applyMaxUpgradePlanForHero` = un `enchantItem` par slot). **Le message client (plan
   déclaré) est IGNORÉ** → toute l'anti-triche = re-calcul serveur. **Fait §8 (`GoldAwareProbe`) : le plan est
   AUTO-LIMITANT** — il ne planifie que le FINANÇABLE (or) avec les matériaux POSSÉDÉS (ex. mesuré : 5 M or → 3 slots ;
   9 M → 5 slots ; 9,14 M → 6 slots ; 0 → plan vide → no-op). ⇒ pas de garde-fou OR ajouté (ce serait du code mort, §2).
   - **Gear RED/YELLOW** (`getMaxStars(RED)=getMaxStars(YELLOW)=5`) : `enchantItem` est rarity-agnostic ; vérifié via
     RALPH rang RED (slot ONE = PRESTO **RED**) et RALPH rang YELLOW (**6 slots YELLOW**).
   - `EnchantMaxUpgradeTest` : YELLOW RALPH → **6 slots enchantés d'un coup**, **or −9 139 200** (= `plan.totalGold`
     exact) + matériaux `{VOID_DUST=12, SHIMMER_DUST=6, PRIMAL_ESSENCE=132}` EXACTS ; persistance **PROFONDE**
     (round-trip wire + DB, étoiles par slot) ; plan vide sans ressource (no-op) ; **affordabilité partielle**
     (5 M or → 3 slots exacts) ; gear RED (PRESTO) enchanté (étoiles 0→1). DEV : `ExpAdminMaxUpgrade` (prépare un
     compte YELLOW RALPH + gear + matériaux + or), pilote `maxupgrade <HERO>`. Régression 108 tests.
   - ⬜ **RESTE : vérif EN JEU** (client réel → `maxupgrade RALPH` → `EnhanceMaxPrimeBadge` → serveur → DB) — §8.

⇒ **ENCHANTING #72** : incr. 1 (matériaux/or) + 2 (diamants/garde-fous) + 3 (multi-slots/raretés) ✅ vérifiés en jeu ;
incr. 4 (max-upgrade prime badges + RED/YELLOW) livré + headless, **vérif en jeu à faire** (REQUIS).

## Notes §3/§4
- Client-autoritatif partiel (le client choisit les matériaux `itemsUsed`) mais le serveur RÉ-EXÉCUTE `enchantItem`
  (débit + montée) → autorité effective, anti-triche par les levées du jeu. Zéro invention (§4) : coûts/effets des
  `enchanting`-stats.
- Attention **WireCheck profond** (leçon EXPEDITION) : vérifier le CONTENU des héros/items après round-trip, pas juste
  le type.
