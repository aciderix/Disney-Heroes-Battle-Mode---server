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
1. ⬜ **Handler `EnchantItem` + `ServerEnchanting.applyEnchant`** : ré-exécute `EnchantingHelper.enchantItem`
   (débite OR/diamants + matériaux, monte l'enchant), anti-triche = les levées du jeu (`NOT_ENOUGH_GOLD`,
   `DONT_HAVE_ITEM`, plafond d'étoiles) → refus autoritatif propre ; persiste (`resyncHeroes`/`resyncDiamonds`/items).
   Test `EnchantBootTest`/`EnchantApplyTest` (enchant d'un slot → étoiles/points montent, OR + matériaux débités,
   round-trip **profond** + DB). **Vérif EN JEU** : HÉROS → détail → slot → ENCHANT → stats montées + coûts débités + persiste.
2. ⬜ **Coûts & garde-fous** : vérifier `getEnchantGoldCost`/`getEnchantMaxDiamondCost` exacts + plafond d'étoiles par
   rareté (barème du jeu) ; cas diamants (`useDiamonds`) ; anti-triche (matériaux insuffisants / plafond atteint).
3. ⬜ **Vérif EN JEU complète** (compte TL100) : enchanter plusieurs slots/raretés, y compris paiement diamants.

## Notes §3/§4
- Client-autoritatif partiel (le client choisit les matériaux `itemsUsed`) mais le serveur RÉ-EXÉCUTE `enchantItem`
  (débit + montée) → autorité effective, anti-triche par les levées du jeu. Zéro invention (§4) : coûts/effets des
  `enchanting`-stats.
- Attention **WireCheck profond** (leçon EXPEDITION) : vérifier le CONTENU des héros/items après round-trip, pas juste
  le type.
