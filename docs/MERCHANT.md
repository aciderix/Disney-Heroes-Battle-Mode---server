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

### COÛT des objets — RÉSOLU (data-driven, §4-OK)
`getItemCost` = `getMerchantItemPrice(type, itemType, resType, currency, mi.getCost())` × remise guilde ; le coût de BASE
`mi.getCost()` vient de la génération. **Source du prix trouvée (leçon pity §8 : « c'est dans la data » — 3ᵉ fois)** :
`items.tab` a les colonnes **`VEND_VALUE`, `GOLD_PRICE`, `DIAMOND_PRICE`, `TOKEN_PRICE`**, lues via
**`ItemStats.getStat(itemType, StatType.{GOLD,DIAMOND,TOKEN}_PRICE)`**. Coût de base = prix de l'objet pour la monnaie du
marchand × quantité. Monnaie = `PriceType` du drop (marchands annotés) sinon `getMerchantPrimaryCurrency(type)`
(GEAR→GEAR_TOKENS, jetons→`TOKEN_PRICE`). Vérifié : EYE_OF_FATES TOKEN_PRICE=65320, HISSY_FIT 32085 GEAR_TOKENS, etc.
Les upsells (`MERCHANT_SLOTS_PERK_UPSELL`) ont prix 0 (slots verrouillés — fidèle).

## Plan d'incréments
1. 🟢 **Génération du stock (blob serveur-autoritatif) — HEADLESS FAIT** : `ServerUser.generateMerchant(type)` roule
   `MerchantStats.<TYPE>_DROP_STATS` (via `merchantTable` réflexion) + `UserDTContext` → `MerchantData` (objets + coût =
   prix `items.tab` × qté + monnaie) → write-through `individualUserExtra.merchantData` (EnumMap). `MerchantGenTest`
   (GEAR 10 objets, coûts = items.tab × qté, round-trip wire + DB, 2 marchands coexistent).
   - ✅ **incr. 1b — push + affichage EN JEU** : `ServerUser.bootMerchantUpdates()` (génère si absent les marchands
     DISPONIBLES+débloqués, réutilise le persisté sinon) → `LoginServer` pousse les `MerchantUpdate` **après le
     `REFRESH_SPECIAL_EVENTS` post-boot** (PAS dans la rafale de boot : le `reset()` du BootData efface sinon les
     marchands appliqués — ils vivent sur l'`IndividualUser` reconstruit ; cf. SocialHistory). **✅ VÉRIFIÉ EN JEU (id=1)**
     : serveur `MerchantUpdate x8` → client `GEAR : 10 objets` → écran **BADGE BAZAAR** affiche les objets + prix (CUTE-ING
     STAR 5 290, DINOCO 400 87 515 en rouge=trop cher, etc.) + timer de refresh — capture `build/merchant_gear_ingame.png`.
     Pilote `merchantscreen <TYPE>`.
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
