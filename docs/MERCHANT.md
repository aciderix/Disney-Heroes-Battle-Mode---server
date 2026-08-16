# MERCHANT (#72 mode suivant) — « Marchands / Marché noir » (boutiques à stock tournant) — suivi

> Pipeline #73/#74 (recon bytecode). Chaque incrément : recon → logique du jeu (§3) → test headless (round-trip + DB)
> → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon bytecode, g111)

Système de **boutiques à stock tournant**. Chaque `MerchantType` (GEAR, MEMORY, CHALLENGE, CRYPT, COLISEUM, FIGHT_PIT,
WAR, EXPEDITIONS, INVASION, NORMAL, HEIST, **BLACK_MARKET**, **MEGA_MART**) vend un petit inventaire d'objets contre une
monnaie de mode (jetons). On **achète** un objet (`PurchaseMerchantItem`) et on peut **rafraîchir** le stock
(`Action REFRESH_TRADER` / `MerchantHelper.refresh`, gratuit N fois/jour puis payant/objet/vidéo).

### ⚠️ Architecture = BLOB SERVEUR-AUTORITATIF (pas de builder client)
**Fait décisif (bytecode)** : `GameMain.lambda$setupPostClientInfoHandlers$28(conn, MerchantUpdate)` — le **client REÇOIT
`MerchantUpdate` du serveur et l'applique ; il ne GÉNÈRE JAMAIS le stock**. La génération (roll des tables de drop) était
100 % serveur-autoritative (PerBlue). ⇒ MERCHANT rejoint la catégorie « blob serveur-autoritatif » (`docs/ARCHITECTURE.md` :
Arena ladder / Surge / Expedition) : **le serveur roule les tables `*_merchant_drops.tab` → construit `MerchantData` →
stocke (write-through `individualUserExtra.merchantData`) → pousse `MerchantUpdate`**. Le jar client contient les tables
(`MerchantStats.<TYPE>_DROP_STATS` privées) + `MerchantDTCode`, donc la génération est **reconstructible** (§3/§4), mais
l'ORCHESTRATION (nb de slots, coût/monnaie par objet, expiration/auto-refresh) est à reconstituer au bytecode (pas de
méthode publique unique). `MerchantHelper.refresh` ne fait QUE le gating/charge/track — **il ne génère pas le stock**
(vérifié : `refresh(AUTO)` renvoie true, inventaire toujours vide ; la génération est derrière `CodeLocationHelper
.isOnServer()`, que `ServerContext` ne pose pas exprès).

### Wire (messages)
- **`MerchantData{ inventory:List<MerchantItemData>, cooldownEnd, expiration, nextAutoRefresh, permUnlocked, staminaMemory }`**
- **`MerchantItemData{ item:RewardDrop, cost:long, costOld:int, currency:ResourceType, purchased:bool }`**
- **`MerchantUpdate{ type:MerchantType, data:MerchantData, reason:int }`** (serveur→client, push d'un marchand)
- **`PurchaseMerchantItem{ merchantType, itemToPurchase:RewardDrop, typeIndex:int, expectedCost:long, expectedQuantity:int, specialEvents }`** (client→serveur)
- **`Action REFRESH_TRADER`** (client→serveur ; actuellement **PARTIEL/NO-OP** côté serveur — visible en boucle dans `/tmp/dh_game.log`)

### Stockage / persistance
- **`IndividualUserExtra.merchantData`** = `Map<MerchantType, MerchantData>` (**write-through** → persistance quasi-gratuite,
  et envoyé au client). Backing de `IUser.getMerchantItems(type)` + `getMerchant{CooldownEnd,Expiration,AutoRefreshTime,
  StaminaMemory}` / `isMerchantPermUnlocked`. Mutateurs : `IndividualUser.setMerchantItems(type, List)` (privé/interne),
  `setMerchant{Expiration,CooldownEnd,AutoRefreshTime,StaminaMemory}`.

### Logique du jeu (§3 — points d'entrée à EXÉCUTER)
- **`MerchantHelper.purchaseItem(MerchantType, RewardDrop, IUser, int typeIndex, long cost, int qty, snapshot)` → Pair** : achat (débit + don + marque `purchased`).
- **`MerchantHelper.refresh(MerchantType, MerchantRefreshType{AUTO,FREE,ITEM,PAID,VIDEO}, IUser, snapshot)` → boolean** : gating + charge (free/quota, item, coût `MerchantStats.getRefreshCost`+`getRefreshCurrency`, vidéo) + `UserActivityTracker.onMerchantRefresh`. **NE génère pas le stock** (à coupler à la génération).
- **`getItemCost`, `isMerchantUnlocked`, `isAvailable`, `getMerchantUnlockLevel/Unlockable`, `canManuallyRefresh`, `getFreeRefreshes`, `isPaidRefreshEnabled`, `getMerchantPrimary/SecondaryCurrency`, `checkForFoundMerchant` (BLACK_MARKET/MEGA_MART limité), `getTimeUntilNextAutoRefresh`, `getAutoRefreshTimes`.**
- Génération : `MerchantStats.<TYPE>_DROP_STATS` (DropTableStats privées) + `MerchantDTCode` (contexte `UserDTContext`) → à rouler serveur-side, puis `setMerchantItems`.

### Matrice de disponibilité (sonde `MerchAvail`, TL200)
- **Disponibles (always-on, monnaie de mode)** : GEAR, MEMORY, CHALLENGE, CRYPT, COLISEUM, FIGHT_PIT, WAR, EXPEDITIONS, INVASION.
- **Limité dans le temps (à « découvrir »/planifier, `isAvailable=false`)** : BLACK_MARKET, MEGA_MART (`isLimitedTime=true`).
- **Verrouillés (TL/déblocage)** : NORMAL, HEIST. `INVASION` : `canManuallyRefresh=false` (auto seulement).
- ⇒ pour l'incr. 1, utiliser un marchand **disponible** (ex. **GEAR**, unlock TL42) ; BLACK_MARKET viendra après (planif/découverte).

## Recette de génération (recon approfondie g111 — de-risking)
Prouvé headless (`MerchGenProbe`/`MerchCostProbe`) :
1. **Roll** : `getDropStats(type)` (réflexion, `MerchantStats.getDropStats` privé) → `.getTable().rollNode("ROOT",
   new UserDTContext(user), random)` → `List<DropItem>` (ex. GEAR = 10 items, MEMORY = 8). ✅ marche.
2. **Items** : `new DropConverter(user).convert(drop)` → `RewardDrop` ; wrap `ClientMerchantItem`/`MerchantItemData`
   (`item`, `currency`, `purchased=false`).
3. **Stockage (BLOB)** : le convertisseur `ClientNetworkStateConverter` **n'gère PAS** les marchands (l'`IndividualUser`
   runtime démarre avec des EnumMap vides) → MERCHANT est un **pur blob** dans `individualUserExtra.merchantData`
   (`Map<MerchantType, MerchantData>`, write-through). `IndividualUser.initMerchantData(type, MerchantData)` peuple le
   runtime (via `ClientNetworkStateConverter.getMerchantItems`), mais la **persistance** = écrire dans
   `individualUserExtra.merchantData` (à câbler côté `ServerUser`, pas via le round-trip du convertisseur).
4. **Livraison client** : `GameMain.lambda$…$28(conn, MerchantUpdate)` → push `MerchantUpdate{type, data, reason}`
   (boot/on-demand). Pas dans BootData.

### ⚠️ Point dur restant — COÛT des objets (à finir, NE PAS inventer §4)
`getItemCost` lit le coût de BASE porté par l'objet (`mi.getCost()`) puis applique remise event/guilde. Ce coût de base
vient de la génération :
- **Marchands ANNOTÉS** (ex. BLACK_MARKET) : le `.tab` porte `{PriceType=DIAMONDS}{Cost=…}` → `DropItem.getParameter(
  "Cost"/"PriceType")` après roll (data-driven, §4-OK, reconstructible). Behaviors `MerchantDTCode` : `Cost`, `PriceType`,
  `CostScalar`, `RarityCostScalar`, `CostRarityScalar`.
- **Marchands À JETONS** (GEAR/MEMORY…) : les objets roulés sortent **sans param `Cost`** (`params={}`) → le coût de base
  est calculé côté serveur par **rareté** (behaviors `RarityCost*`), source pas encore localisée dans le jar/`.tab`
  visible. **NE PAS conclure « gap »** avant d'avoir cherché (leçon pity §8) : à vérifier — table de coût par rareté,
  `COST_STATS` (semble = coût de REFRESH indexé, pas item), ou formule item-value. **C'est le seul verrou restant de
  l'incr. 1.**

## Plan d'incréments
1. ⏳ **Génération + affichage du stock (blob serveur-autoritatif)** : roll ✅ + stockage blob (à câbler
   `individualUserExtra.merchantData`) + `MerchantUpdate` push. **Bloqué** sur le coût de base des marchands à jetons
   (voir §Point dur). Option : démarrer par un marchand à coûts ANNOTÉS. Test headless (stock non vide + coûts + round-trip
   wire + DB). Vérif EN JEU : écran marchand affiche des objets avec prix.
2. ⬜ **ACHAT (`PurchaseMerchantItem` → `MerchantHelper.purchaseItem`)** : anti-triche (coût/quantité RECALCULÉS serveur,
   `expectedCost` ignoré), débit monnaie + don objet + marque `purchased`, persistance. Vérif en jeu.
3. ⬜ **REFRESH (`Action REFRESH_TRADER` → `MerchantHelper.refresh` + régénération)** : corrige le PARTIEL actuel ;
   gratuit N/jour puis payant ; reroll du stock + persistance. Vérif en jeu.
4. ⬜ (option, à prouver) **BLACK_MARKET / MEGA_MART** limités : `checkForFoundMerchant` (découverte/planif) + expiration.

## Notes §3/§4
- Blob serveur-autoritatif : la génération roule les VRAIES tables `.tab` + `MerchantDTCode` (jamais inventer coûts/poids).
- `isOnServer()` : `ServerContext` ne pose pas `CodeLocationHelper.SERVER` (init stats en client-location) → la génération
  n'est PAS atteignable via le chemin `refresh` gardé ; on roule la table nous-mêmes (glue) + `setMerchantItems`, comme le
  blob (pas de rustine : on exécute la table + les helpers du jeu, on n'écrit que l'orchestration wire).
- Round-trip profond : vérifier `merchantData` (inventory + coûts + purchased) après round-trip wire, pas juste le type.
- Anti-triche (§ checklist) : coûts RECALCULÉS serveur (`getItemCost`/`getRefreshCost`), `expectedCost` client ignoré.
