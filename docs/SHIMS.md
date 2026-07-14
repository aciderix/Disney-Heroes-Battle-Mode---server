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
| **`ServerUser.openChest`** | ✅ **RÉEL** (coffre gratuit) | Exécute `ChestStats`/`DropTable`/`ChestHelper.giveChestRewards`+`updateChestCounters` sur un `User` bâti sur nos objets wire ; resync héros. Vérifié wire (`ChestWireTest`) : `BuyChests(GOLD)`→`LootResults{Frozone}` ; unitaire : 3 coffres d'affilée (dont un à récompense d'objet) sans NPE. |
| **`ServerUser.initNewPlayerResources`** (ressources compte neuf) | ✅ **RÉEL** | Un `new IndividualUserExtra()` laisse `getLastResourceGenerationTime(STAMINA)=0` → le jeu calcule la stamina courante = régénération **depuis l'époque 1970** → **millions d'énergie** (« 39,96 M / 120 »). Correctif comme un serveur à la création : **ancre** `setLastResourceGenerationTime(rt, creationTime)` ET met **chaque ressource régénérée à son cap** (`UserHelper.getResourceCap`) pour chaque `UserHelper.resourceGenerates(rt)` — STAMINA=120, **GOLD_CHEST=1, SILVER_CHEST=1** (les coffres gratuits sont des ResourceType régénérées : sans ça le coffre gratuit du tuto est « Free in ~48h » → clic sans effet → Frozone jamais accordé → tuto bloqué). Valeurs du jeu, non inventées. Vérifié (`server/smoke/ResourceTest`) : STAMINA=120/120, GOLD_CHEST=1, `hasFreeChest(GOLD)`=true, GOLD/DIAMONDS=0, persiste au wire. |
| **`ServerUser.recordCampaignAttack`** (combat de campagne) | ⚠️ **RÉEL** (cœur) **+ PARTIEL** (bords) | Exécute la logique AUTORITATIVE du jeu `CampaignHelper.recordOutcome(user, user, CampaignLevel.of(mode, ch, lvl), outcome, stars, stagesCleared, loot, mem, base.attackers, base.defenders, snapshot)` : **consomme la stamina** (`getStaminaCost`+`chargeUser`), **donne loot/gold/XP** (`giveLoot`/`giveGold`/`giveTeamXP`), **met à jour la progression** (`ICampaignLevelStatus`), puis **resync héros ET progression campagne** + persiste. Fire-and-forget (aucun listener client pour `CampaignAttack` au bytecode ; **confirmé EN JEU** : le pilote atteint le combat, gagne, le client envoie `CampaignAttack(WIN)` → `recordOutcome appliqué [persisté]`). **Re-sync progression (correctif clé)** : les statuts de niveau vivent HORS `this.extra` (`ClientCampaignLevelStatus` en mémoire, construits depuis `individualUserExtra.levelStatuses` au chargement) ; `recordOutcome` les mute EN MÉMOIRE seulement → sans re-sync, étoiles/complétion **perdues au round-trip** (1-2 ne se débloque JAMAIS). `resyncCampaign` reconstruit `individualUserExtra.levelStatuses` depuis `iu.getCampaignLevels()` (comme `resyncHeroes`). Vérifié (`server/smoke/CampaignAttackTest` : énergie -6, or +340, 3★ ; **`CampaignPersistTest` : après save+reload SQLite → 1-1 à 3★, 1-2 DÉBLOQUÉ, or/stamina OK**). **PARTIEL** : (1) `SpecialEventSnapshot.NONE` → **aucun bonus d'évènement** (headless) ; (2) `outcome`/`stars` = ceux du **client** (combat client-autoritatif, pas de re-simulation serveur) ; (3) `lastWinTime` non re-syncé (pas de getter public ; non requis pour le déblocage). **Stamina de la content update R102 (cause RÉELLE trouvée, gérée fidèlement)** : le serveur tourne sur la content update la plus récente **R102** (globale, correcte). `stamina_values.tab` y définit `REGEN_AMOUNT=39 965 650` (cap dur 79 Md — scaling end-game réel). Dans `UserHelper.updateAndGetResource`, STAMINA est dans la branche **NON-capée** : quand `stamina<cap`, un intervalle ajoute 39,96 M puis la boucle sort → `getResource` renvoie la valeur BRUTE (~39,96 M). Le jeu utilise la valeur **effective = `min(getResource, getResourceCap)`** à l'affichage/dépense (le client montre donc 120/120). `applyEffectiveResourceCap` (avant `recordOutcome`) applique la MÊME règle du jeu : fixe chaque ressource régénérée à `min(getResource, cap)` + horloge=maintenant → un joueur neuf inactif régénère à **120** (plein, régén PRÉSERVÉE, pas figée), puis le combat débite (120→114). Pas d'invention, valeurs 100% du jeu. Vérifié : `EffTest` (inactif 1h → régén 120 → combat 114), `CampaignPersistTest`/`CampaignAttackTest`/`ResourceTest` OK. |
| **Roster de départ** (héros de base compte neuf) | ✅ **RÉEL** (fidélité vidéo) | Un compte neuf **possède déjà des héros AVANT le coffre** — vérifié sur une vidéo de gameplay (PRINCIPLES §4bis) : **Ralph + Elastigirl** (les héros contrôlés dès l'intro). L'intro combat les crée en SYNTHÉTIQUE (`createUnitDatas`→`new User()`+`CombatSimHelper`) et ne les ajoute PAS au roster ; le grant du roster est une décision de **création de compte** (serveur). `initNewPlayerResources` les crée via la **méthode du jeu** `User.createAndAddHero(type, WHITE, 1, 1, …)` (état défaut « nouveau héros » = celui de Frozone-coffre), puis resync wire. Frozone arrive ENSUITE via le coffre GOLD. Vérifié (`server/smoke/RosterTest`) : {Ralph, Elastigirl} WHITE niv.1 → +Frozone → persiste au wire. **Confirmé (vidéo)** : Vanellope est dans le 1ᵉʳ combat tuto (synthétique) mais **PAS possédée ensuite** → roster de départ = Ralph + Elastigirl seulement (elle sera débloquée plus tard via `UnlockHeroActV1`) ; rang/niveau **WHITE niv.1** validé. |
| **`ServerSpecialEventsExt`** (couche évènements) | ✅ **RÉEL** | `GameMain.create()` fait `SpecialEventsHelper.init(new ClientEventUserProvider(), new ClientSpecialEventsHelperExt())`. L'extension **cliente** touche libGDX (`Gdx.app` → « not available » headless) car elle **pousse au serveur** les temps de visionnage (`UpdateEventViewTimes`). On fournit l'**équivalent serveur** (`dhserver.ServerSpecialEventsExt`) : `sendEventRewards` reproduit à l'identique la logique d'état cliente (PREMIUM_STAMINA_CONSUMABLE → `convertTimeLimitedItems`+`setTime`, sans libGDX) ; `trySetEventViewed` conserve l'inscription **autoritative** (`getEventViewTimes().put`) et omet la poussée réseau client→serveur (dénuée de sens sur le serveur). `ServerContext.init` appelle `SpecialEventsHelper.init(...)`, `ServerContext.bind` appelle `setSpecialEvents(new SpecialEventsRaw(), user, shardID)` (comme `handleBootData`). Débloque le don d'objet des coffres (`giveChestRewards`→`onItemEarn`→`getActiveContestsWithTask`) et `updateChestCounters`. |

### TODO suivis (dette technique — quoi faire quand on y arrivera)

1. **`updateChestCounters` (compteurs QUOTIDIENS d'ouverture / limites d'achat) — ✅ RÉSOLU (2026-07-12).**
   La couche évènements spéciaux est désormais initialisée dans `ServerContext` (`SpecialEventsHelper.init`
   + `setSpecialEvents`, extension serveur `ServerSpecialEventsExt`) comme `GameMain` → `SpecialEventsHelper
   .helper` non-null → `updateChestCounters` **réactivé** dans `ServerUser.openChest`. Découvert par un run
   client réel : le **2ᵉ** `BuyChests` (récompense d'objet) plantait en NPE via `giveChestRewards` →
   `RewardHelper.giveReward` → `ContestHelper.onItemEarn` → `getActiveContestsWithTask`. Vérifié : 3 coffres
   d'affilée (dont objet) sans NPE + `ChestWireTest` OK.

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

5. **Handler `Action` (équiper/voir/promouvoir…) — `EQUIP_ITEM` ✅ VÉRIFIÉ ; autres commandes à ajouter.**
   *Où* : `ServerUser.applyAction`/`applyCommand` + `LoginServer` (branche `Action`).
   **`EQUIP_ITEM` ✅ FONCTIONNEL & VÉRIFIÉ IN-GAME (wire)** : `HeroHelper.getSlotThatCanEquip` (valide
   niveau/released/craft) puis `HeroHelper.equipItem(heroType, itemType, slot, user)` — **logique d'origine,
   sans contournement**. **Vérifié** (a) `server/smoke/EquipTest` (round-trip wire) ET (b) **EN JEU** : le vrai
   client émet `Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, extra={SLOT=SIX}}` (séquence tuto enregistrée
   `HERO_GEAR_SLOT_SIX`→`CRAFTING_WINDOW_EQUIP_BUTTON`), le serveur répond `action EQUIP_ITEM appliquée
   [persisté]` et le tuto INTRO_FEATURES avance. NB : le client passe le slot dans `extra={SLOT=SIX}` (le
   handler le recalcule via `getSlotThatCanEquip`, concordant ; préférer l'`extra` quand présent).

   - **Couche CONTENU (colonnes de release) ✅ RÉSOLU** : `ContentHelper` démarre **vide**
     (`ContentStats.getColumns()=0` → `getColumn(now)=DEFAULT` → `isItemReleased`=false pour TOUT → casse
     `getSlotThatCanEquip` et **toute logique gatée « contenu released »**). Le jeu charge le contenu du
     shard via `ShardStats.setShardID(shard, map)` (→ `parseStats("content.<shard>.tab", opener)`).
     **Corrigé** : `ServerContext.bind` appelle `ContentHelper.get().setShardID(user.getShardID(),
     new HashMap())` → charge `content.<shard>.tab` (372 colonnes pour shard 1) → `isItemReleased`=true.
     C'est un **unlock générique** (pas que l'équipement).
   **Décisions/faits établis (2026-07-12) :**
   - **`GuildStats` n'est PAS bloquant** (mon 1ᵉʳ diagnostic était faux — « illusion de crash », comme
     EVIL_QUEEN) : `guild_perk_levels.tab` a des `CONTENT_TL` vides (lignes `TIMED_*`) → `parseInt("")` lève,
     mais `GeneralStats.parseStats` l'**attrape** (`onStatError` LOGue + saute la ligne). Vérifié :
     `GuildStats` **se charge OK** headless. La stack imprimée était celle **loguée**, pas fatale.
   - **`DH.app.guildInfo` requis** : beaucoup de chemins passent par `getYourGuildInfo()` (ex.
     `GameStateManager.startAction` → `GuildPerkHelper.updateGuildInfoTimedPerks` lit `guildInfo.perkEndTimes`).
     **Corrigé** : `ServerContext.bind` pose un `new GuildInfo()` (nouveau joueur) sur le shim.
   - **`ActionHelper.doAction` = chemin CLIENT « appliquer + UI »** : il appelle `getScreenManager()
     .getScreen()` (×4) → NPE headless (pas d'écran). ⇒ **on N'UTILISE PAS `doAction`** côté serveur. Comme
     `openChest` (qui appelle `ChestStats`/`DropTable`, pas un flux « acheter » client), on appelle la
     **logique cœur** par commande (`RealGearHelper.equipGear`, `HeroHelper.equipItem`, …). Aiguillage écrit,
     règle exécutée.
   - **L'objet « Badge of Friendship » N'est PAS du real gear** (`ItemStats.getRealGearType(BADGE_OF_FRIENDSHIP)
     = DEFAULT`) → la commande d'équipement du tuto n'est **pas** `EQUIP_REAL_GEAR` mais probablement
     `EQUIP_ITEM` (`HeroHelper.equipItem(heroType, itemType, HeroEquipSlot, user)`), à confirmer.
   - **Commandes de bookkeeping léger — ✅ TRAITÉES (2026-07-13), plus « PARTIEL ».**
     - **`VIEWED_CHESTS` → RÉEL** : logique d'origine EXACTE de `ActionHelper.doAction` (branche extraite au
       bytecode) = `user.setTime(TimeType.LAST_CHESTS_VIEW_TIME, Long.parseLong((String) extra.get(TIME)))`.
       `setTime` écrit dans `this.extra.times` (`UserExtra` partagé) → **persiste** via `this.extra` (§3).
       Efface la pastille « nouveau » sur les coffres.
     - **`RECORD_SERVER_ROLL_FINISHED` → NO-OP FIDÈLE** (pas une rustine) : le code **client** du jeu ne mute
       aucun état — `ClientActionHelper.recordServerRollFinished` construit l'extra et appelle
       `ActionHelper.doAction(RECORD_SERVER_ROLL_FINISHED, …)`, or `doAction` **n'a AUCUNE branche** pour ce
       `CommandType` (vérifié au bytecode) → pure notification client→serveur. Le comptage **autoritatif** des
       rolls est déjà fait par `openChest` (`ChestHelper.updateChestRollCounters`). On ACQUITTE sans rien
       simuler ; inventer un registre de `rollId` violerait §4. **Vérifié** (`server/smoke/ViewedChestsTest`) :
       `VIEWED_CHESTS` persiste `LAST_CHESTS_VIEW_TIME` au round-trip wire ; `RECORD_SERVER_ROLL_FINISHED`
       renvoie `true` sans lever.
   **À faire** : ajouter les autres commandes du hub au fur et à mesure (`applyCommand` **log les commandes
   non gérées** = cartographie). *Risque résiduel* : une commande non routée n'est pas persistée → au reload
   le tuto peut caler sur l'étape correspondante.
   - **Pilote DEV — pop-ups modales EMPILÉES ✅ (2026-07-13)** : `TutorialDriver` draine désormais les
     modales résiduelles (ex. `ChestReadyWindow` « CRATE READY » restée par-dessus l'écran héros). Il raisonne
     sur la fenêtre du dessus : cible tuto dedans → taper ; cible ailleurs → fermer la modale (`hide()`, une
     par frame) ; aucune cible → attendre (récompense=fermer, interactive=VIEW). **Vérifié en jeu** : le tuto
     franchit la frontière équipement et atteint le **hub principal propre** (nouveau joueur), tuto suivant
     `HERO_FILTERS` en attente (capture `desktop-port/build/herofilters.png`).

## Couche plateforme desktop (`dhbackend/`, lanceur)

| Élément | Statut | Détail / risque |
|---|---|---|
| **Locale UTF-8 (`LC_ALL=C.utf8`)** | ✅ **RÉEL** (correctif d'env) | Le conteneur démarre en **POSIX/ASCII** (`sun.jnu.encoding=ANSI_X3.4-1968`). L'extraction d'assets (zip4j) échoue alors sur un nom de fichier **Unicode** (ex. `.../launchpad_mcquack<char>.skel`) : `applyFileAttributes` → `new File(nom).toPath()` lève `InvalidPathException: unmappable characters` → la **tâche d'extraction entière est avortée** → catégorie SOUND « Missing or Halfway » → **le tuto ne démarre jamais**. `run-desktop.sh` exporte `LC_ALL=C.utf8` (dispo) → `sun.jnu.encoding=UTF-8`, les noms Unicode s'encodent, extraction complète. Correctif de **plateforme** (lanceur), aucune modif du jeu. (Les runs antérieurs ne le voyaient pas car les assets étaient déjà entièrement en cache.) |

À compléter au fur et à mesure (Application/Graphics/Input/Files/Audio/GL/Net/Preferences/
DeviceInfo/bridges) — voir le registre SHIMS de DragonSoul comme modèle.
