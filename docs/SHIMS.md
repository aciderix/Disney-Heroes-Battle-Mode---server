# SHIMS — registre des substitutions & contraintes de chargement

Principe (voir [`PRINCIPLES.md`](PRINCIPLES.md) §2) : **un shim doit être fonctionnel**. On
ne répond jamais « oui oui » au jeu. Ce fichier liste tout ce qui n'est pas le comportement
natif d'origine, avec fidélité et risque. Légende : **RÉEL** (équivalent), **PARTIEL**,
**NO-OP**, **FACTICE**.

## Contraintes de chargement du bytecode (décisions de build, pas des shims)

| Élément | Détail / justification |
|---|---|
| **`-Xverify:none` au chargement** | Le bytecode issu de **dex2jar** n'a pas les *stackmap frames* exigées par le vérificateur Java 7+ (`VerifyError: Expecting a stackmap frame`). La vérification est un contrôle de **chargement**, pas d'exécution → la désactiver ne change **rien** au comportement. Alternative durable : recalculer les frames via ASM `COMPUTE_FRAMES` lors d'un remap (comme DragonSoul `build-remap.sh`). Vérifié : codec du jeu OK sous `-Xverify:none`. |
| **`commons-logging` au classpath** | `com.perblue.common.logging.LogSource` référence `org.apache.commons.logging.LogFactory`. Fourni par `commons-logging:commons-logging:1.2` (Maven Central), copié en `libs/commons-logging.jar` par `tools/decompile.sh`. |
| **`libs/game.jar` régénérable** | Produit par `tools/decompile.sh <apk>` (dex2jar `de.femtopedia.dex2jar` via Maven). **Non committé** (copyright + taille) ; régénérable → cf. PRINCIPLES §7. |
| **Remap de noms (si nécessaire)** | Disney Heroes n'est **pas obfusqué** (`com.perblue.heroes.*` en clair) → a priori **pas** de collisions de noms à remapper (contrairement à DragonSoul `b_`/`c_`). À revérifier si un chargement échoue. |
| **`GruntServerFactory` (same-package)** | **RÉEL.** `GruntNIOTCPServer` (serveur NIO DU JEU) est *package-private* sans fabrique publique → on ajoute `com.perblue.grunt.translate.GruntServerFactory` (nouvelle classe, **pas** une modif du jeu) pour l'instancier. Le ctor crée le thread NIO mais **ne l'active pas** (`running=false`, thread non démarré) : le factory lève `running` (réflexion) et démarre le thread — bootstrap réel de la boucle réseau native, aucune rustine. Vérifié : handshake TCP OK. |

## Vérifié (smoke tests)

| Test | Statut | Détail |
|---|---|---|
| `server/smoke/CodecRoundTrip` | ✅ **RÉEL** | Charge `DHXORConnectionWrapper` (= `Stacked(Deflate, XOR(KEY))`) du jeu et round-trip `wrapOut`/`wrapIn` OK sur JVM desktop. Prouve que le **codec réseau du jeu est réutilisable côté serveur** sans réimplémentation. Wire observé : commence par `78 9C` (en-tête zlib/Deflate). |
| `server/smoke/MessageRoundTrip` | ✅ **RÉEL** | `BootData.writeAll` → `MessageFactory.readMessage` round-trip OK ; `MessageFactory`/`BootData` chargent **sans libGDX**. |
| `server/smoke/HandshakeRoundTrip` | ✅ **RÉEL** | Handshake login **bout-en-bout sur socket TCP** : `LoginServer` (réutilise `GruntNIOTCPServer` + codec + `MessageFactory` du jeu) reçoit un `ClientInfo1` et répond un `BootData1` que le client (`GruntBuilder`) accepte. Toute la pile réseau du jeu tourne sur JVM desktop headless. |

## Serveur — exécution de la logique du jeu headless (`ServerContext`, handlers)

Le serveur **exécute le code du jeu** (PRINCIPLES §3 « lire & exécuter »). Contexte + substitutions :

| Élément | Statut | Détail / risque |
|---|---|---|
| **`ServerStats`** (ouvreur `.tab`) | ✅ **RÉEL** | Installe `StatFileHelper.setExt` lisant `game-data/stats/` → les classes de données du jeu (`ChestStats`, `UnitStats`…) se peuplent avec les **vraies données**. Miroir serveur de `dhbackend.DhStatFileExt`. |
| **`DH.app` (shim `GameMain` headless)** | ✅ **RÉEL** pour les getters utilisés | Beaucoup de classes passent par `DH.app` (ex. `User.getIndividual()` = `DH.app.getYourIndividualUser()`). On alloue un `GameMain` **sans constructeur** (`Unsafe.allocateInstance`) et on pose `user`/`individualUser` → les getters simples répondent. Couche plateforme (§4). **Risque** : les chemins de logique qui touchent d'AUTRES champs de `GameMain` (non posés) lèveront NPE → à traiter au cas par cas (cf. TODO ci-dessous). |
| **joda-time (données de fuseaux)** | ✅ **RÉEL** | `game.jar` a les classes joda mais pas la donnée `org/joda/time/tz/data/*` (requise par `TimeUtil`). Jar standard fourni (classes ombrées par game.jar, seule la ressource utilisée) = donnée du jeu. |
| **`ServerUser.openChest`** | ✅ **RÉEL** (coffre gratuit) | Exécute `ChestStats`/`DropTable`/`ChestHelper.giveChestRewards` sur un `User` bâti sur nos objets wire ; resync héros. Vérifié wire (`ChestWireTest`) : `BuyChests(GOLD)`→`LootResults{Frozone}`. |

### TODO suivis (dette technique — quoi faire quand on y arrivera)

1. **`updateChestCounters` (compteurs QUOTIDIENS d'ouverture / limites d'achat) — DIFFÉRÉ.**
   *Où* : `ServerUser.openChest` (appel commenté). *Pourquoi* : passe par `getDailyUses` →
   `DailyActivityHelper`/`PrizeWallHelper` → `SpecialEventsHelper.helper` **null** headless → NPE.
   **À faire** : initialiser la couche évènements spéciaux **comme `GameMain.handleBootData`** (créer le
   `SpecialEventsHelper.helper` puis `SpecialEventsHelper.setSpecialEvents(new SpecialEventsRaw(), user,
   shardID)`) dans `ServerContext.bind`, puis réactiver `updateChestCounters`. *Risque actuel* : les
   **limites d'achat quotidiennes** ne sont pas comptées côté serveur (OK pour le tuto = coffre gratuit
   sans limite).

2. **Coffres PAYANTS (débit de la monnaie, ex. diamants) — NON couvert.**
   *Où* : `ServerUser.openChest` (`wasFree` ; pas de charge). *Pourquoi* : `IndividualUser.setResource`
   pour `DIAMONDS` appelle `DH.app.getUserBattlePassV2().setProgress(...)` → NPE (champ `battlePassV2` du
   shim non posé). **À faire** : étoffer le shim `DH.app` (poser un `IBattlePassV2Data` réel/minimal via
   `getUserBattlePassV2`), puis charger le coût (`ChestHelper.getBasePurchaseCost` + `setResource`) et
   remplir `LootResults.costs`. *Risque actuel* : ouvrir un coffre **payant** lèverait NPE (le tuto n'ouvre
   que des coffres **gratuits** → non bloquant).

3. **Sérialisation `User→wire`** : couverte pour l'ouverture de coffre (héros + `chestUpgradeXP` resync ;
   le reste via `this.extra` partagé). **À étendre** au fur et à mesure des handlers : chaque nouveau
   champ qu'un handler mute **hors `this.extra`** doit être resync + validé par un round-trip.

4. **Smoke tests obsolètes** : `HandshakeRoundTrip` utilise l'ancien constructeur `LoginServer(port, provider)`
   (changé en `LoginServer(port, ServerUser, UserStore)`) → à mettre à jour.

## Couche plateforme desktop (`dhbackend/`, à venir — miroir DragonSoul `dsbackend/`)

À remplir au fur et à mesure (Application/Graphics/Input/Files/Audio/GL/Net/Preferences/
DeviceInfo/bridges). Voir le registre SHIMS de DragonSoul comme modèle. Chaque entrée devra
préciser fidélité + risque. **Rien pour l'instant** (le port desktop n'est pas commencé).
