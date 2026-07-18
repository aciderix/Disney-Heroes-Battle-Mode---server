# MEMORY — Disney Heroes: Battle Mode — Portage Desktop + Serveur

> **Document de récupération de contexte.** À lire en premier par n'importe quel
> agent reprenant le projet (notamment après compression de contexte ou reset du
> conteneur). Il contient l'état courant, l'architecture, les liens, la hiérarchie
> des fichiers et un historique **court**. L'historique **détaillé** est dans
> [`JOURNAL.md`](JOURNAL.md). **Maintenir ce fichier à jour en permanence.**

Dernière mise à jour : **2026-07-18** — **STAMINA « 39,96 M / 120 » = DÉFINITIVEMENT AUTHENTIQUE (résolu, preuve croisée gameplay réel)** + **versions clarifiées**.
**Cause (chaîne prouvée au bytecode + probes committés `server/smoke/StaminaEraProbe`+`StaminaCalcProbe`)** : le client cale son horloge sur le serveur (`GameMain`→`TimeUtil.initClock(BootData.serverTime / Ping.serverTime, deviceTime)`) → `serverTimeNow()` = la date **envoyée par le serveur** → `ContentStats.getColumn()` résout la **content release R** → `StaminaStats.getRegenAmount(R)` (par palier). `UserHelper.getGenerationAmount(user,STAMINA)` = `RegenerationRate(∞, getRegenAmount(R))` — **indexé sur la content release (DATE), JAMAIS sur le niveau d'équipe**. Dans `updateAndGetResource`, STAMINA est la branche **NON-capée** (pas de `Math.min(cap,…)`, contrairement aux autres ressources) → un seul tick ajoute le montant ENTIER puis la boucle sort → `getResource` **déborde** le cap. `ResourceMeter.updateUI` affiche `getResource` **BRUT** (offset 73, **aucun `Math.min`**) → le joueur voit le débordement. **⚠️ CORRIGE des entrées MEMORY/SHIMS antérieures FAUSSES** (« affiché `min(brut,120)=120` » / « le client montre 120/120 ») : l'affichage n'est **pas** clampé. **PREUVE CROISÉE (gameplay réel, fournie par l'utilisateur)** : un niveau 1 en tuto **il y a ~7 mois** = **~823 000/120** ⇒ colle à **R96 (01/12/2025), regen 815 540 → 815 660/120**. Progression : R95(nov.2025)=709K, R96=815K, R98(janv.2026)=1,37M, **R102(20/04/2026)=39 965 650**. **⇒ 39,96 M est le comportement RÉEL de PerBlue** (débordement d'un tick, regen qui croît avec la date). Notre serveur envoie `System.currentTimeMillis()` (2026) dans `bd.serverTime` (`ServerUser.java:150`) + `pong.serverTime` (`LoginServer.java:149`) → R102 → 39,96 M = **fidèle à avril 2026**. **Décision de fond OUVERTE (choix d'authenticité, pas un bug)** : (a) garder l'ère fin de vie 2026/R102 (authentique) ; (b) reculer `serverTime` vers une ère ancienne (corrige stamina **et tout** le contenu daté) ; (c) surcharger `stamina_values.tab` via **stat-sync** (`BootData.statDataTxt`) pour un regen sain **sans** toucher la date (garde héros/chapitres 2026 authentiques). **VERSIONS clarifiées** : le magasin numérote **7.11 / 8.0** (Play Store `version_code` 5555117/5555120), le **build moteur interne** est **12.1.0 / git a53845c9 / 22 fév. 2023** (`assets/info.txt`) — les DEUX versions magasin partagent ce **même build** ⇒ **aucun patch de code en fin de vie** (ResourceMeter/régén gelés depuis fév. 2023 ; seules les données `.tab` changent par révision). **v8.0 (dernière) = notre contenu exact** (R102, Max TL 565, calendrier→20/04/2026). Bonus : le bundle XAPK v7a fournit `libspine-native.so` **byte-identique** à `native/reference/` (confirme l'authenticité de notre lib de référence). « Singular » (`Singular-v12.1.0-…`) = label de build PerBlue + SDK d'attribution `com.singular.sdk`, **pas** un repo GitHub public (source PerBlue = privée).

Dernière mise à jour : **2026-07-17 (nuit)** — **RECON NAVIGATION DU HUB → `docs/HUB_NAV.md`** (+ vérif
persistance de bout en bout OK, + #25 loot RÉVERTÉ en ombre). **Vérif e2e** : compte frais → campagne 1-1→1-6
en autopilote (client **unidbg**), **restart = état IDENTIQUE** (teamLevel/gold/stamina/héros/campagne 3★/
inventaire préservés ; `DbInspect` = load à froid du BLOB SQLite le prouve). **#25 loot** : le run frais a
révélé qu'en jeu réel le tirage serveur **diverge** du client (pool XP non suivi + pitié double-comptée) → **
révérté en mode OMBRE** (crédite client = fidèle ; bascule autoritative reportée, cf. SERVER_PLAN §E). **Recon
hub** (`docs/HUB_NAV.md`) : le hub = `MainScreen` (ville g2d, bâtiments = boutons) ; navigation centralisée
`UINavHelper.navigateTo(Destination)` (~57 destinations) ; 2 surfaces = bâtiments `MainIconType`(23)→
`getDestination` + menu ☰ `SideMenuIconData(...,Destination)` (`MenuIconType`={HEROES,ITEMS,QUESTS,MAILBOX,
MEDALS,EVENTS}) ; accès gated par `canNavigateTo`/`Unlockable`. **Flèche de tuto** = `TutorialHelper.getPointers`
→ tag d'acteur (le pilote la suit DÉJÀ). **Badges rouges "!"** = `DotTracker` (singleton) : `MAIN_RED_DOTS`/
`MENU_RED_DOTS`/`MENU_DOT` (burger), chaque `Dot.showDot()` interrogeable = signal « action dispo » (hors tuto,
plusieurs à la fois) — **le pilote NE l'utilise PAS encore** (opportunité d'autonomie post-tuto). **Prochain** :
suivre le tuto (mène aux écrans suivants) + implémenter les handlers serveur au fur et à mesure (HeroList→Items→
Quests). Outil ajouté : `server/smoke/DbInspect` (dump état persisté).

Dernière mise à jour : **2026-07-17 (soir)** — **#25 LOOT AUTORITAIRE FAIT & CERTIFIÉ**. Le serveur **roule
lui-même le butin** avec la graine LOOT du client (flux RNG **SÉPARÉ du combat** → fonction déterministe de la
seule graine, **AUCUNE simulation requise**), reproduisant la séquence client EXACTE relevée au bytecode
(`CampaignAttackScreen` : `user.resetRandom(LOOT)` ; `CampaignLootHelper.getLoot(user, type, 0, chapter, level,
NONE, guildPerks, true).combinedLoot`). Sur une VICTOIRE, `ServerUser.recordCampaignAttack` CRÉDITE le tirage
SERVEUR (au lieu de `m.lootEarned`) → **autoritaire/anti-triche** ; divergence = signal de triche loggé ; repli
client si pas de graine ou non-WIN. **Certifié** `server/smoke/LootAuthoritativeTest` : **5/5 graines
serveur==client** (5 butins distincts). Prérequis : `BuildOptions.SERVER_TYPE=NONE` dans `ServerContext.init`
(commutateur HEADLESS du jeu qui coupe l'instrumentation RNG client→serveur, sinon NPE `getNetworkProvider()` ;
n'affecte QUE l'envoi, pas les valeurs RNG). Régression OK (8 smoke tests). **⇒ le serveur est AUTORITAIRE sur
tout ce qui a de la valeur (loot/or/XP/progression) SANS simuler le combat** ; ne reste client que `outcome`/
`stars` (§D, nécessitent une re-sim échantillonnable). Fichiers : `server/java/dhserver/{ServerUser,ServerContext}
.java`, `server/smoke/LootAuthoritativeTest.java`. SERVER_PLAN §E ✅.

Dernière mise à jour : **2026-07-17** — **#28 CERTIFICATION Opt.3 FAITE + perf desktop mesurée**. Le **harnais
différentiel** (`CompareBackend` : le jeu boote sur unidbg=oracle=binaire PerBlue mobile, notre **spine-c recompilé
natif** tourne en parallèle sur les MÊMES handles, on diffe chaque appel — mode `DH_SPINEBACKEND=compare`) a servi à
**certifier automatiquement** le spine-c hôte (x86-64) contre le binaire ARM d'origine, sur un vrai combat (1-1, 973
ticks, WIN). **Résultat** : **structure + événements d'animation identiques bit-à-bit** (`nextEvent` 0/8611,
`setAnimation` 0/87, tous les noms/ids/durées 0 diff) ; **poses d'os fidèles à la précision flottante** (matrice
1.8e-7, position 6.1e-5 — sub-pixel, bornées car recalculées à neuf chaque frame → pas d'accumulation). **3 vrais
bugs de fidélité trouvés PAR le harnais et corrigés** dans `native/src/cspine_jni.c` : (1) **layout matrice transposé**
→ ordre correct de l'oracle `[a, c, b, d, worldX, worldY]` (le split mat/pos du harnais a montré la signature b↔c) ;
(2) **`nextEvent` non branché** → file d'événements spine-c implémentée (listener global + FIFO sur `rendererObject`,
drain `_spEventQueue`, `out[0]=spEventType+1`, `out[1]=trackIndex` ; dispose AVANT free sinon use-after-free) ;
(3) **`setAnimation`** renvoie un **compteur de trackEntry par animState** (1-based), pas l'animId. Restent : `getBoneID`
(PerBlue réordonne les os en interne = artefact auto-cohérent, 0 impact) + `setSlotEyeState` (extension **cosmétique**
PerBlue absente de spine-c vanilla). **PERF (chrono par backend, même mix d'appels)** : **unidbg (ARM émulé)=16900 ms
vs JNI natif=337 ms → ~50× plus rapide** (~5,8 ms/frame unidbg vs ~0,12 ms/frame natif ; 50× conservateur car exclut
`getVertices`). **VERDICT** : rendu/animation = **fidèle** (identique à l'œil, dérive flottante invisible) → desktop
**jouable en production POUR LE RENDU** via le backend natif ; **autorité de combat reste sur unidbg côté serveur**
(non bit-identique = §3 serveur autoritatif). **Bloquants restants avant desktop natif de prod** : (a) le **boot
JNI-autonome** bute sur le handle-registry (contourné aujourd'hui par le mode compare, le jeu bootant sur unidbg) ;
(b) `setSlotEyeState` (yeux) si on veut les expressions. Détail : JOURNAL 2026-07-17 + `docs/SERVER_PLAN.md`.
Fichiers : `native/src/cspine_jni.c`, `desktop-port/src/main/java/dhbackend/spine/CompareBackend.java`,
`dhdesktop/CombatSpikeDriver.java` (`DH_COMBATSPIKE`). Commits `a0dc9de` (certif) + `312b8c7` (perf).

Dernière mise à jour : **2026-07-16 (nuit 3)** — **#24 RE-SIM COMBAT : investigué à fond → plan « oracle-certification »**.
Conclusion ferme : **`HeadlessCombat` n'est PAS pure logique** (son ctor bâtit un `RepresentationManager` →
`RPGAssetManager` dont le ctor exige un **contexte GL** + loaders **natifs** cspine/cparticle unidbg) et **le
combat de DH est piloté par les KEYFRAMES d'animation** (`scene.update` pose des `AnimationKeyframeListener` sur
l'`AnimationElement` de chaque unité = mécanisme de déclenchement des dégâts/projectiles). Sans données
d'animation skeleton (`.skel` via spine natif), les unités n'infligent jamais de dégâts → **une sim serveur pure
logique n'est PAS fidèle**. Le SETUP, lui, tourne headless (mode `BuildOptions.TOOL_MODE=COMBAT_AUTOMATOR` →
`AUTOMATOR_BOUNDS` ; renderer no-op du jeu `HeadlessSceneRenderer.INSTANCE` ; `CombatSetupHelper.createUnits/
initPositions/initializeAIAndSkills` OK ; `fixedTimestep=25ms`). **Décision (user)** : approche
**oracle-certification** (patron du rebuild natif §4) — monter l'**Opt.2** (vrai `HeadlessCombat` via le **client
headless unidbg** déjà fonctionnel du `desktop-port`) comme **ORACLE** + mesurer sa lourdeur, puis **certifier**
une **Opt.3** plus légère (timelines via runtime **spine Java** `spine-libgdx-perblue.jar`) contre l'oracle
(RNG/HP-tick/timing, matrice large) — jamais shippée non certifiée. Sinon garder l'Opt.2. **Opt.1** (loot
autoritatif #25, pure logique) reste faisable et cible la surface de triche. Détail : `docs/SERVER_PLAN.md` §D +
JOURNAL 2026-07-16 nuit 3. **✅ OPT.2 PROUVÉE & MESURÉE (oracle établi)** : hook lanceur DEV `dh.combatspike`
(`CombatSpikeDriver`) → le **vrai `HeadlessCombat` tourne headless dans le client** (GL+unidbg+assets) jusqu'à
`DONE` : **1-1 → `ticks=973` WIN, déterministe** (ticks identiques 3 runs = qualité oracle), **lourdeur ~9 s
wall-clock/combat** (unidbg spine dominant → trop lent pour synchrone, OK pour anti-triche **async**). A exigé un
**fix bytecode** : `ReframeJar` normalise l'`itf` des `INVOKESTATIC` d'interface (FXHandle `$r8$lambda`, dex2jar
Methodref→InterfaceMethodref ; PAS les INVOKESPECIAL — VerifyED super-interface indirecte). **Prochain** : #28
(certifier une Opt.3 spine-Java contre cet oracle, matrice de combats **serrés** car le stomp 1-1 est
seed-insensible).

Dernière mise à jour : **2026-07-16 (nuit 2)** — **LOOT D'OBJETS crédité & persisté EN JEU ✅**. Question user (« objets ramassés dispo à l'équipement ? ») → bug : `recordCampaignAttack` jetait `m.lootEarned` (loot roulé CLIENT, combat client-autoritatif) → inventaire vide. Corrigé : passer `m.lootEarned` (1ᵉʳ param List = loot à donner ; le 2ᵉ param est un DELTA RewardDrop laissé VIDE — y mettre `m.memoryChanges` plante `removeDelta` en ClassCastException → cascade CAMPAIGN_LEVEL_LOCKED). **Confirmé EN JEU** : 0 crash, CampaignAttack 1-1→1-5 persistés, **inventaire = 11 types d'objets** (SUNNY_SIDE/CLEVER_FOX/ACE_OF_SPADES gear + EXP_*/HEARTY_BREAKFAST/SUGAR_RUSH/RAID_TICKET conso) dans `individualUserExtra.items` (= écran héros/équip). Test `server/smoke/LootPersistTest`. PARTIEL : memoryChanges + graine SET_SEED non appliqués (confiance loot client). **Suivi des PARTIELs à résoudre** (mémoire de loot, lastWinTime, SET_SEED, re-sim combat/loot authoritatif, PatchStats) → `docs/SERVER_PLAN.md` §Partiels + tâches #21–#26.

Dernière mise à jour : **2026-07-16 (nuit)** — **ENCHAÎNEMENT CAMPAGNE 1-1→1-5 EN JEU ✅**. Run fresh nouveau
joueur (3 fixes pilote cumulés : sélection héros + fenêtre équipement + nav post-victoire) : le pilote enchaîne
`normalOrEliteNodeSelected(1-1)→RETOUR carte→(1-2)→(1-3)→(1-4)→RETOUR→(1-5)` → **5× `CampaignAttack WIN`
persistés**. **État DB** : teamLevel=**2**, 1-1..1-5 à **3★**, 1-6 débloqué, gold=**2316**, stamina
stored=108/eff=108 (**consommée correctement** — plus de refill en boucle depuis le fix team-level). Serveur
**0 fatal**. Fixes : (1) nav post-victoire (flag `justFoughtCampaign` → RETOUR carte après combat →
`nextPlayableLevel`) ; (2) étape équipement (CraftingWindow = fenêtre de FLUX → taper EQUIP, pas fermer) ;
(3) [serveur] persistance team-level (`userInfo.basicInfo.teamLevel = user.getTeamLevel()` — révélé par la
remarque user « stamina 122/120 sans consommation »). **Pipeline campagne complet validé & persisté en jeu.**

Dernière mise à jour : **2026-07-16 (soir)** — **PIPELINE COMPLET VALIDÉ EN JEU (nouveau joueur → 1-1 GAGNÉ)**.
Run complet nouveau joueur : intro → coffre GOLD (Frozone) → **entrée campagne → choix des héros → 1-1 GAGNÉ
→ `CampaignAttack` → `recordOutcome` persisté**. Serveur (framed jar) **0 fatal** sur tout le run. **Fix pilote
`selectHeroesIfNeeded`** : sur `CampaignHeroChooserScreen`, sélectionne les héros via l'API du jeu
`unitSelected` (sinon TEAM POWER=0 → « select at least one hero » → aucun combat). **Persistance vérifiée
(DB live, après 6× 1-1 WIN)** : STAMINA stored=122/**eff=120 (AUCUN 39M, écran ET DB)**, **GOLD=2040** (loot
campagne cumulé), **1-1=3★ totalWins=6, 1-2 débloqué=true** — tout persiste. **Reste** : navigation pilote
POST-VICTOIRE (rejoue 1-1 au lieu d'aller en 1-2 : revient sur l'aperçu et re-tape FIGHT au lieu de retourner
à la carte ; la logique `nextPlayableLevel`/déblocage serveur est correcte — non bloquant serveur).

Dernière mise à jour : **2026-07-16** — **Enquête « crash addHeroEXP » close + stabilité JVM du serveur**.
(1) Le **crash `addHeroEXP`** (`ClientErrorCodeException: ERROR []`) **NE se reproduit plus** = artefact d'un
état compilé pré-fix. (2) Le vrai incident intermittent était un **crash JVM SIGABRT** (`Illegal class file …
in method getDefaultStats`, `generateOopMap.cpp`, pendant un GC) : le bytecode dex2jar de `game.jar` sans
`StackMapTable` sous `-Xverify:none` fait planter l'inférence oop-map au GC (~1/8), **exposant le serveur
autoritatif aussi**. **Fix durable** : reframe `game.jar`→`game-framed.jar` (ASM `COMPUTE_FRAMES`, 64 196
classes, comme le client) + **retrait `-Xverify:none`** dans `run-online.sh` (serveur) et `server/smoke/run.sh`.
Vérifié : `ChargeTest` **15/15 sans abort**, boot serveur 0 fatal, régression (`Resource/CampaignAttack/
CampaignPersist`) identique. (3) **Stamina « 39,96 M » = fidèle R102, PAS un bug** : STORED=114 + timestamp
**correctement sauvé** (hypothèse « timestamp mal enregistré » **réfutée**) ; `getResourceCap(STAMINA)=120`
(cap dépensable) ; le brut 39,96 M = un tick de régén R102 non-capé, **affiché `min(brut,120)=120`**.
`ChainProbe` : effective toujours ≤120 (120→114→120→114), gold chaîne 340→680→1088→1632, 0 crash logique.
Le fix `applyEffectiveResourceCap` applique la règle du jeu `min(getResource,cap)` (pas de « figer »).
**Prochain** : run complet tuto→campagne (enchaînement + persistance loots) puis autres écrans.

Dernière mise à jour : **2026-07-14 (soir)** — **COMBAT DE CAMPAGNE JOUÉ & GAGNÉ EN JEU + progression
PERSISTÉE**. Le pilote DEV entre dans le niveau (`CampaignScreen.normalOrEliteNodeSelected(1-1)` — la
carte est une scène g2d `CityMapDisplay`, pas du scene2d ; découvert via la sonde `dh.mapprobe`),
gère l'aperçu + choix héros + flèche de fin de vague + boutons FIGHT par nom (robuste au replay). **Bug
AUTO corrigé** : `Boolean.getBoolean("dh.autofight")` n'acceptait que `"true"` → `dh.autofight=1` ignoré,
AUTO jamais activé → skills passifs → **défaite** ; corrigé → **VICTOIRE 1-1 en jeu** (énergie 114/120
visible, `CampaignAttack(WIN)` reçu par le serveur, `recordOutcome` appliqué). **Bug persistance corrigé** :
la progression campagne (statuts de niveau) vit HORS `this.extra` → `recordCampaignAttack` la re-synchronise
maintenant vers `individualUserExtra.levelStatuses` (`resyncCampaign`, comme les héros) SINON 1-2 ne se
débloque jamais. Vérifié `server/smoke/CampaignPersistTest` : après save+reload → **1-1 à 3★, 1-2 DÉBLOQUÉ**.
**Deux bugs corrigés (2026-07-14 nuit)** : (1) **chaînage niveaux** — le pilote entre désormais au **prochain
niveau débloqué** (`nextPlayableLevel` via `getLatestCompletedLevel`/`isLevelUnlocked`) → enchaîne 1-1→1-2→…
(override `dh.playlevel`). (2) **stamina 39,96 M** — cause RÉELLE (établie 2026-07-16) : content update
**R102**, `getRegenAmount=39 965 650`, STAMINA dans la branche NON-capée de `updateAndGetResource` → un tick
de régén renvoie le brut 39,96 M, que le jeu affiche/dépense en **effective `min(getResource, cap=120)`=120**
(fidèle end-game, PAS un bug ; STORED=114 et timestamp corrects). Fix `applyEffectiveResourceCap` (commit
8f84ef5, remplace l'ancien `anchorGeneratingResources`) applique cette règle du jeu AVANT le débit → 120→114.
Vérifié `ChargeTest`/`ChainProbe`.

Dernière mise à jour : **2026-07-14** — **PIPELINE DE COMBAT DE CAMPAGNE (serveur) ✅ VÉRIFIÉ**. Le
combat tourne côté CLIENT (unidbg spine) ; le client construit `CampaignAttack{base(attackers,outcome,
stars), campaignType, chapter, level, stagesCleared}` via `ClientNetworkStateConverter.getCampaignAttack`
(qui roule `CampaignHelper.recordOutcome` de son côté) et l'envoie **fire-and-forget**. Nouveau handler
serveur AUTORITATIF **`ServerUser.recordCampaignAttack`** + branche `LoginServer` : ré-exécute
`CampaignHelper.recordOutcome` sur l'état serveur → **consomme la stamina** (`getStaminaCost`+`chargeUser`),
**donne loot/gold/XP** (`giveLoot`/`giveGold`/`giveTeamXP`), **met à jour la progression**
(`ICampaignLevelStatus`), persiste. Vérifié `server/smoke/CampaignAttackTest` : NORMAL 1-1 WIN →
**énergie -6 (120→114), or +340, niveau à 3★** — pure logique du jeu, rien d'inventé. PARTIEL (SHIMS) :
`SpecialEventSnapshot.NONE` (pas de bonus évènement), outcome/stars = ceux du client (combat
client-autoritatif), contrat fire-and-forget à reconfirmer en jeu.
**Frontière pilote (DEV, #17)** : l'auto-pilote NE sait pas encore ENTRER dans un chapitre. La carte
`CampaignScreen` (a) joue une animation « SCANNING CITY MAP » gating l'interactivité, (b) `getPointers()`
renvoie **vide** headless (le pointeur CHAPTER 1 n'est pas émis), (c) le nœud `CAMPAIGN_CHAPTER_ONE_NAME`
est un `Stack childrenOnly` à label `disabled` sans `[CLICK]` (input carte custom, pas un bouton scene2d).
→ le pilote idle puis RETOUR, boucle MainScreen↔CampaignScreen. À résoudre pour la démo in-game (le
pipeline serveur, lui, est prouvé indépendamment).

Dernière mise à jour : **2026-07-13 (nuit)** — **COFFRE GRATUIT DU TUTO DÉBLOQUÉ ✅ (cause racine du
blocage à l'étape coffre GOLD trouvée)**. Symptôme : après l'ouverture, le tuto ne repartait pas ;
l'écran montrait le détail du coffre GOLD (« DIAMOND CRATE ») avec **« Free in : 1j 23h 46m »** et le
pilote, faute de cible, vagabondait sur les boutons payants (« You can't do that just yet »). Cause :
`getFreeChestResource(GOLD)=ResourceType.GOLD_CHEST` — le coffre gratuit est une **ressource régénérée**
comme la stamina ; `hasFreeChest(GOLD)` = `getResource(GOLD_CHEST) >= 1`. Notre amorce compte-neuf ne
mettait au cap **que STAMINA** → `GOLD_CHEST=0` → coffre indisponible ~48 h → le clic n'envoyait **aucun
`BuyChests`** (0 reçu côté serveur, log confirmé) → Frozone jamais accordé → tuto bouclé. Correctif
(fidèle, §3) : un compte neuf démarre **chaque ressource régénérée à son cap** (généralisation exacte du
fix stamina), caps du jeu au niv.1 : `GOLD_CHEST=1, SILVER_CHEST=1, SOCIAL_CHEST=1, SKILL_POINTS=50,
STAMINA=120…`. Vérifié : `hasFreeChest(GOLD)=true`, et `ServerUser.openChest(GOLD)` d'un compte neuf rend
**Frozone 8/8** (la table de drop GOLD du jeu est déterministe pour le nouveau joueur — aucune triche
inventée). `server/smoke/ResourceTest` étendu (GOLD_CHEST=1 + `hasFreeChest`). Chaîne complète OK :
coffre dispo → `BuyChests` envoyé → serveur `LootResults{Frozone}` → `getHero(FROZONE)!=null` → le tuto
avance vers `HERO_LIST_TAP_FROZONE`. Voir §7 + JOURNAL.

Dernière mise à jour : **2026-07-13 (soir 2)** — **Roster de départ ✅** : un compte neuf possède
**Ralph + Elastigirl** (WHITE niv.1) AVANT le coffre (fidélité vidéo ; Frozone arrive au coffre GOLD ;
Vanellope = combat d'intro synthétique, débloquée plus tard). `ServerUser.createAndAddHero` + `RosterTest`.
**Back-out post-équip ✅** (heuristique pilote) → le pilote traverse jusqu'au **1ᵉʳ combat de campagne**
(acte `FAST_FORWARD`). Voir plus bas.

Dernière mise à jour : **2026-07-13 (soir)** — **`EQUIP_ITEM` ✅ VÉRIFIÉ IN-GAME (wire)** : vrai client →
`Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, SLOT=SIX}` → serveur « appliquée [persisté] » → tuto avance
(séquence enregistrée `HERO_GEAR_SLOT_SIX`→`CRAFTING_WINDOW_EQUIP_BUTTON`). **Enregistreur pas-à-pas**
`dh.tutorec` (dump des pointeurs + captures numérotées) + **pilote discipliné** (tap central seulement sans
pointeur actif ; RETOUR BACK_BUTTON) → plus de vagabondage sur le coffre Diamant. Micro-frontière suivante :
post-équip, sortir de `HeroDetailScreen` (pas de pointeur). Piège : reprise depuis une DB de run TUÉE =
état pollué (coffre déjà ouvert, bouton mort) → tester sur état propre.
Plus tôt le 2026-07-13 : pop-ups EMPILÉES drainées ; `VIEWED_CHESTS` REAL + `RECORD_SERVER_ROLL_FINISHED`
NO-OP ; énergie « 39,96 M » CORRIGÉE (stamina cap 120, gen-time ancré). Vérifs
`server/smoke/{EquipTest,ViewedChestsTest,ResourceTest}`. Cf. §7 + JOURNAL + SHIMS #5.

---

## 1. But du projet

Reproduire, pour **Disney Heroes: Battle Mode** (PerBlue, serveurs fermés), l'approche
déjà réalisée pour **DragonSoul** : faire tourner le jeu original (quasi non modifié)
sur Windows/Linux/Android avec un **serveur autoritatif** ré-hébergeable par n'importe
qui, en réutilisant au maximum le code et les données du jeu.

### Objectifs
- Exécuter le jeu sur **Windows, Linux, Android**.
- Développer un **serveur complet** (comme DragonSoul), **autoritatif** = source de vérité.
- Outils de **pilotage / tests automatisés / débogage**.
- Architecture propre : **auto-hébergement**, liste de serveurs au démarrage, choix du
  serveur, **mode sécurisé**, **protection par mot de passe** optionnelle.
- **Remplacer le téléchargement des assets** pour les récupérer depuis l'archive
  (archive.org) ou une copie locale, sans modifier inutilement le jeu.

### Principes non négociables (détail : [`docs/PRINCIPLES.md`](docs/PRINCIPLES.md))
1. **Modifications minimales** du jeu ; comprendre le vrai fonctionnement plutôt que
   contourner.
2. **Aucune rustine** : jamais de faux « OK » / bypass / état forcé. Résoudre les
   causes, jamais masquer les symptômes.
3. **Le serveur est la source de vérité** (autoritatif) ; **aucune** donnée du jeu
   recopiée à la main.
4. **Aucune réécriture manuelle des données** : tout est **extrait automatiquement**
   depuis l'APK/le code (outils dédiés générant des fichiers exploitables).

---

## 2. Liens & ressources

| Ressource | Lien / emplacement |
|---|---|
| **Ce dépôt** (serveur/port Disney Heroes) | `aciderix/Disney-Heroes-Battle-Mode---server`, branche de travail `claude/disney-heroes-port-rhhtuj` |
| **Dépôt de référence** (portage DragonSoul + serveur) | `aciderix/dragonsoul-web`, branche `claude/game-transpile-debug-2p5irx`, dossier `desktop-port/` (cloné en session sous `/workspace/dragonsoul-web`) |
| **APK Disney Heroes** (base, ~92 Mo, v12.1.0) | **committé** : `game/disney-heroes-12.1.0.apk` (source : Google Drive `https://drive.google.com/file/d/1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF/view`). Jar décompilé aussi committé : `libs/game.jar` (+ `libs/commons-logging.jar`). Voir [`docs/ASSETS.md`](docs/ASSETS.md). |
| **Assets live archivés** | archive.org : `https://archive.org/download/disney-heroes-battle-mode-live-assets` |
| **Manifeste d'assets** (`index.txt`) | À la racine du dépôt (`index.txt` == `disney_heroes_live_index.txt`) |
| **Serveur de contenu d'origine** | `http://content.disneyheroesgame.com/live/index.txt` (hors ligne) |

---

## 3. Faits techniques établis (recon APK — voir [`docs/RECON.md`](docs/RECON.md))

- **Package jeu** : `com.perblue.heroes.*` (logique), couche Android `com.perblue.dragonsoul.android.*`.
  Nom de code interne : *heroCities*. Package public : `com.perblue.disneyheroes`.
- **Version APK** : `12.1.0` (build `Singular-v12.1.0-a53845c9.master`, 2023-02-22). Moteur **libGDX**.
- **Même architecture réseau que DragonSoul, mais NON OBFUSQUÉE** (gros avantage) :
  - `com.perblue.heroes.network.messages.MessageFactory` — registre des messages.
  - `com.perblue.heroes.network.messages.*` — centaines de messages (`ClientInfo1`, `BootData1`, …).
  - `com.perblue.heroes.network.{XORConnectionWrapper,DHXORConnectionWrapper}` — codec XOR.
  - `com.perblue.common.network.{XORConnectionWrapper,XORCipher,Deflate…}` — codec bas niveau.
  - `com.perblue.grunt.translate.{GruntMessageFactory,ConnectionWrapper}` — framework « grunt » (EN CLAIR ici ; c'était `com.perblue.a.a.*` obfusqué chez DragonSoul).
  - `com.perblue.heroes.network.{NetworkProvider,GameServerAddress}` — connexion.
- **AssetUpdater** identique : gate `MISSING_ADDITIONAL`, catégories `WORLD_ADDITIONAL`,
  `UI_DYNAMIC`, `SOUND`, `TEXT`. Télécharge `index.txt` puis les archives manquantes.
- **ServerType** `com.perblue.heroes.ServerType` ✅ décompilé. LIVE = login **https**
  `login.disneyheroesgame.com:443`, contenu `http://content.disneyheroesgame.com/live/index.txt`.
  **APK NON patché** (vrais domaines) ; l'adresse du serveur de jeu TCP vient de la réponse
  de login (séquence 2 étapes). Redirection prévue par réécriture `ServerType` (réflexion) /
  passerelle, **sans patcher le bytecode**.
- **Clé XOR** ✅ décompilée — `DHXORConnectionWrapper.KEY` = `CE 85 D4 F9 29 A8 24 56`
  (8 octets). Codec = `Stacked(Deflate, XOR(KEY))`.
- **Données de jeu embarquées** (source de vérité, extraites → `game-data/`) :
  - `game-data/stats/` : **274 fichiers `.tab`** (TSV) = tout l'équilibrage (héros, arène,
    coffres, battle pass, items, skills…).
  - `game-data/strings/` : **325 fichiers `.properties`** (textes localisés).
- ⚠️ **`gateway/v1/*` = SDK Unity Ads**, PAS le protocole du jeu (piège à éviter).

---

## 4. Différence clé avec DragonSoul → stratégie assets

Chez DragonSoul, `index.txt` était **perdu** et les assets additionnels **manquaient**
(gate `MISSING_ADDITIONAL` bloquant le boot). Ici :
- `index.txt` **est disponible** (dans ce dépôt).
- Les assets live sont **archivés sur archive.org**.

⇒ Stratégie : **servir `index.txt` + relayer/rediriger les téléchargements d'assets vers
l'archive (ou une copie locale)**, pour que l'`AssetUpdater` du jeu se déroule
normalement, **sans modifier le jeu**. Détails : [`docs/ASSETS.md`](docs/ASSETS.md).

> **RISQUE #1 — ✅ RÉSOLU (2026-07-09).** L'`AssetUpdater` décompilé (`assets_external`)
> décide **uniquement sur la révision**, jamais sur `GameVersion`. `retainRowsForVersion`
> ne retire que le contenu **plus récent** que le client ; comme l'APK (12.1.0) est plus
> récent que l'index (7.8.1/7.9), **toutes les lignes archivées sont conservées** →
> l'APK **accepte les assets archivés** (install neuve : `COMPLETE 325` puis `INCREMENTAL 326`).
> Détail + algorithme complet : [`docs/ASSETS.md`](docs/ASSETS.md). Risque résiduel = complétude
> **runtime** (à constater en exécutant, pas un blocage du gate de téléchargement).

---

## 5. Architecture cible (miroir de DragonSoul — [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md))

```
[ Jeu (libGDX, com.perblue.heroes.*) ]
   |  couche plateforme remplacée par un backend desktop LWJGL3 maison (shims)
   |  réseau : Socket TCP brut + codec XOR+Deflate + MessageFactory
   v
[ Passerelle locale 127.0.0.1 ] --relaie--> [ Serveur hébergeable (Java, réutilise les classes du jeu) ]
   - sert le contenu HTTP (index.txt) + redirige assets -> archive.org/copie locale
   - auth mot de passe hors protocole jeu
   - login (ClientInfo -> BootData) ; monde/état ; persistance (SQLite)
```

Le serveur **réutilise les classes du jeu** (remap non-sémantique si besoin) pour une
sérialisation/chiffrement **identiques** au client → zéro réimplémentation binaire.

---

## 6. Hiérarchie des fichiers (ce dépôt)

```
MEMORY.md                 <- CE fichier (récupération de contexte, tenu à jour)
JOURNAL.md                <- journal détaillé des modifications (relié à l'historique court)
README.md
index.txt                 <- manifeste d'assets live (== disney_heroes_live_index.txt)
disney_heroes_live_index.txt
upload_batch.py           <- upload des assets vers archive.org (utilisé par le workflow)
.github/workflows/upload_to_ia.yml
docs/
  PRINCIPLES.md           <- principes de dev (règles non négociables)
  ARCHITECTURE.md         <- architecture cible (port desktop + serveur), miroir DragonSoul
  PROTOCOL.md             <- protocole réseau & contenu (recon bytecode)
  ASSETS.md               <- pipeline assets (index.txt + archive.org) + comment obtenir l'APK
  RECON.md                <- findings bruts de reconnaissance de l'APK
  SHIMS.md                <- registre substitutions + contraintes de chargement (-Xverify:none…)
  HUB_NAV.md              <- recon navigation du hub (MainScreen/UINavHelper/DotTracker/flèche de tuto)
game/
  disney-heroes-12.1.0.apk <- APK du jeu (committé, ~92 Mo) — évite le re-téléchargement
tools/
  extract_game_data.sh    <- extrait stats/strings de l'APK vers game-data/ (source de vérité)
  decompile.sh            <- APK → libs/game.jar (dex2jar via Maven) ; régénérable
libs/                     <- game.jar + commons-logging.jar (committés — évitent la re-décompilation)
desktop-port/            <- port desktop (scaffold Gradle ; rendu headless prouvé)
  build.gradle, settings.gradle, run-gl-smoke.sh
  src/main/java/desktop/GLSmokeTest.java  <- preuve rendu GL headless (Xvfb+llvmpipe)
  PROGRESS.md            <- état + STRATÉGIE (réutiliser LwjglApplication bundlé + crawler)
server/java/
  com/perblue/grunt/translate/GruntServerFactory.java  <- fabrique du serveur NIO du jeu (same-package)
  dhserver/LoginServer.java                            <- serveur de jeu TCP : ClientInfo1 -> BootData1
server/smoke/
  CodecRoundTrip.java     <- prouve la réutilisation du codec du jeu (Deflate+XOR)
  MessageRoundTrip.java   <- prouve la sérialisation BootData/MessageFactory (sans libGDX)
  HandshakeRoundTrip.java <- handshake login TCP bout-en-bout (ClientInfo1 -> BootData1)
  run.sh                  <- compile server/java + exécute les 3 smoke tests
game-data/
  stats/*.tab             <- 274 tables d'équilibrage (extraites de l'APK)
  strings/**/*.properties <- 325 fichiers de textes localisés (extraits de l'APK)
server/
  content_server.py       <- serveur de contenu v0 (index.txt + redirection assets) ✅
  run-content-server.sh   <- lanceur
  README.md               <- doc des composants serveur
  (à venir) login + protocole de jeu TCP (Java, réutilise les classes du jeu)
```

Dépôt de référence (`/workspace/dragonsoul-web`, branche `claude/game-transpile-debug-2p5irx`) :
`desktop-port/` contient le backend (`src/main/java/dsbackend/`), le serveur
(`server/Ds*.java`), et les docs (`PRINCIPLES/SERVER_DESIGN/PROTOCOL/SHIMS/PROGRESS.md`).

---

## 6bis. Lancement (procédure CANONIQUE — à suivre, ne pas improviser)

**Pile complète (serveur + client) — la voie normale :**
```bash
cd desktop-port
./run-online.sh              # contenu :8080 + jeu :8081 (LoginServer) + client (redirigé vers notre serveur)
```
- **Garde-fou intégré** : `run-online.sh` détecte les process DÉJÀ en cours de CE projet — serveurs (PID
  `dhserver.LoginServer`/`content_server.py` ou ports 8080/8081 occupés) **ET client jeu**
  (`dhdesktop.DesktopLauncher`) **ET `Xvfb :99`** — et les **arrête tous** avant de relancer (mettre
  `DH_KILL_OLD=0` pour seulement alerter et abandonner). Évite les **serveurs/clients zombies** (plusieurs
  LoginServer sur le même port → le client parle à l'un, on lit le log de l'autre ; client orphelin qui
  fausse les captures). NB : `pgrep -f DesktopLauncher` matche **aussi ta propre commande shell** (la
  chaîne est dans la ligne de commande) → pour compter les VRAIS jeux, filtrer `java.*dhdesktop.DesktopLauncher`.
- **Variables** : `DH_FRAMES` non défini → 120 frames ; **`DH_FRAMES=` (vide) → NON plafonné** (joue tout
  le tuto, borné par `DH_TIMEOUT`). `DH_AUTOTAP=1`/`DH_AUTOFIGHT=1` = pilote DEV (tuto/combat auto, off en
  prod). Ex. traverser tout le tuto : `DH_FRAMES= DH_TIMEOUT=600 DH_AUTOTAP=1 DH_AUTOFIGHT=1 ./run-online.sh`.

**Client seul** (`./run-desktop.sh`) : suppose un serveur déjà lancé (sinon → serveur PerBlue hors ligne).
Exporte `LC_ALL=C.utf8` (sinon l'extraction d'assets aux noms Unicode plante, cf. SHIMS). Sans GPU : ~9 fps.

**Serveur seul** (rare) :
```bash
java -Xverify:none -XX:TieredStopAtLevel=1 -Ddh.db=server/data/dh-server.db \
     -Ddh.stats=game-data/stats -cp "<CP>:desktop-port/build/server-classes" dhserver.LoginServer 8081
```
Classpath serveur `<CP>` = `libs/{game,commons-logging,sqlite-jdbc,slf4j-api,joda-time}.jar` (les 3
dernières récupérées à la demande par `run-online.sh`). **Nouveau joueur** : supprimer `server/data/dh-server.db`.

**Pièges à éviter :**
- **NE PAS tronquer** (`: > /tmp/dh_game.log`) un log tenu ouvert par le serveur → bourrage NUL → `grep`
  le prend pour un binaire et n'affiche RIEN. Lire avec **`grep -a`**, ou redémarrer proprement le serveur.
- Le `exit 144` (SIGSTKFLT) = **kill externe du superviseur** (pas un crash) ; il peut tuer les runs longs
  ET les commandes bash. Lancer serveurs/clients en **tâche détachée** pour survivre au kill du wrapper.

## 6ter. Méthodologie DEV — traverser le tuto vite et sûrement (À NE PAS OUBLIER)

**A. Reprise RAPIDE par la persistance (gain énorme).** La progression (tuto/héros) est **persistée**
(SQLite `server/data/dh-server.db`). ⇒ entre deux itérations, **NE PAS supprimer la DB** : le client
**reprend au hub** (saute l'intro cinématique+combat) → **~20 s** jusqu'à la frontière au lieu de **~4 min**.
Vérifié : reprise `LoadingScreen→MainScreen→ChestsScreen→SilverChestDetailScreen`, **0 rejeu de combat**.
- Reset (`rm server/data/dh-server.db`) **uniquement** pour un vrai test « nouveau joueur ».
- **Snapshot** aux points sûrs (`cp server/data/dh-server.db server/data/dh-<étape>.db`) pour restaurer
  une frontière connue si le pilote corrompt l'état.

**B. Boucle « capture → inspecter ce qui est cliquable → corriger le pilote ».** Pour franchir un écran de
hub où l'auto-tap cale :
1. Lancer avec `DH_TUTODBG=1` (+ `DH_SHOT`). Au blocage, **convertir le `.ppm` en `.png` et REGARDER**
   l'écran (source de vérité visuelle).
2. Le pilote **dumpe les acteurs actionnables** de la popup (`TutorialDriver.dumpActionable` : classe,
   `getTutorialName()`, texte du `Label`, **position stage**, `[CLICK]`). Ça dit **exactement** quoi taper.
3. Faire frapper par le pilote le **bon acteur du jeu** (pointeur tuto ; sinon `DFTextButton` principal
   « VIEW/OPEN/OK » ; sinon bouton par nom) — **jamais une coordonnée devinée**, toujours l'acteur/API du jeu.
4. Recompiler (`gradle -q compileJava`), relancer (reprise rapide via A).

**B-bis. ⭐ TECHNIQUE DE DÉBLOCAGE UNIVERSELLE — « capturer → cliquer au bon endroit → monitorer ce que
le clic déclenche → câbler dans le pipeline ». À APPLIQUER DÈS QU'ON BLOQUE SUR UN ÉCRAN.**
C'est **LA** méthode quand on ne sait pas quel élément actionner (surtout si l'écran n'a **aucun acteur
`[CLICK]`** scene2d exploitable — ex. carte de campagne = scène **g2d** `CityMapDisplay`, `getPointers()`
vide headless). On ne **devine jamais** une coordonnée : on agit **comme un vrai doigt** et on **observe la
réaction du jeu**. Les 4 étapes :
1. **CAPTURER l'écran** (`DH_SHOT` → `.ppm`→`.png`) et **REGARDER** où un vrai joueur cliquerait (source de
   vérité visuelle).
2. **CLIQUER manuellement à cet endroit** (hit-test) et **MONITORER ce que le clic déclenche** : l'acteur
   effectivement touché, le **1er ancêtre porteur de listener**, les **types de listeners** (`getListeners()`),
   la **méthode/API du jeu** appelée, et la **transition d'écran** qui en résulte. Sonde intégrée
   `TutorialDriver.mapProbe` (activée `DH_MAPPROBE=1`) : suspend le RETOUR, hit-teste une grille autour de la
   cible, tape, journalise l'acteur+listeners+transition.
3. **IDENTIFIER l'API du jeu** révélée (pas la coordonnée). Ex. carte campagne : le tap n'atteint aucun
   bouton scene2d → géré par `CityMapScreen` (caméra `MapCamera2D` → `CityMapDisplay.getHitCampaignLevel(x,y)`
   → `CampaignLevelID` → `onCampaignLevelTapped(id)` → `normalOrEliteNodeSelected`).
4. **CÂBLER cette API dans le pipeline** (pilote DEV appelle l'API du jeu trouvée ; **jamais** une coordonnée
   devinée), recompiler, relancer (reprise rapide via A).
**Généralisable à tout écran bloquant** (hub, popups, mini-jeux, cartes custom…). Voir JOURNAL 2026-07-14
(entrée en chapitre élucidée exactement ainsi).

**C. Comportement du pilote** (`TutorialDriver.driveOnce`, ordre) : (1) popup ouverte + pointeur tuto DEDANS
→ taper dedans ; (2) popup d'**affichage de récompense** (`*Result*`/`*Reward*` : `ChestResultsWindow`) →
**fermer** (`BaseModalWindow.hide()`) ; (3) popup **interactive** (`ChestReadyWindow` « CRATE READY ») →
frapper le **`DFTextButton`** principal (VIEW…) ; (4) sinon pointeur tuto sur l'écran de base → taper
l'acteur ; (5) rien → tap central (lanceur). Conversion stage→écran : `sx=v.x/stageW*w ; sy=h−v.y/stageH*h`.

**D. Lire les logs** : **toujours `grep -a`** (NUL possible). Serveur = `/tmp/dh_game.log` (ou la sortie de
tâche du LoginServer réellement à l'écoute — vérifier `readlink /proc/<pid>/fd/1` en cas de doute).

---

## 7. État courant & prochaines étapes

### Fait (2026-07-09, bootstrap)
- [x] Clone + étude du dépôt de référence DragonSoul (architecture comprise).
- [x] Téléchargement de l'APK Disney Heroes (v12.1.0) + reconnaissance (voir RECON.md).
- [x] Confirmation : même stack réseau que DragonSoul, **non obfusquée**.
- [x] Extraction des données du jeu (`game-data/stats` + `strings`) via `tools/extract_game_data.sh`.
- [x] Mise en place de la mémoire projet (MEMORY.md + JOURNAL.md) et des docs.
- [x] Décompilation ciblée (androguard) → **clé XOR** + config **`ServerType`** extraites,
  APK confirmé **non patché**. (Décompilation nécessaire car dex2jar/jadx bloqués par le proxy ;
  androguard installé via pip.)
- [x] **RISQUE #1 résolu** : décompilation de l'`AssetUpdater` → décision par révision (pas
  par GameVersion) → l'APK 12.1.0 accepte les assets archivés (rev 325/326).
- [x] **Serveur de contenu v0** (`server/content_server.py`) : sert `index.txt` + redirige
  les `.zip` vers archive.org (ou copie locale). Testé de bout en bout.
- [x] **Décompilation en jar régénérable** (`tools/decompile.sh` → `libs/game.jar` via
  dex2jar/Maven) + **preuve de réutilisation des classes du jeu** (JVM desktop,
  `-Xverify:none` + `commons-logging` ; cf. `docs/SHIMS.md`) :
  - `server/smoke/CodecRoundTrip` : codec `DHXORConnectionWrapper` (Deflate+XOR) round-trip OK.
  - `server/smoke/MessageRoundTrip` : `BootData.writeAll` ↔ `MessageFactory.readMessage` OK ;
    `MessageFactory`/`BootData` se chargent **sans libGDX**. ⇒ le serveur peut construire/décoder
    les messages au format wire exact du jeu.
- [x] **Serveur de login v1 (squelette) + handshake TCP prouvé bout-en-bout** :
  `server/java/dhserver/LoginServer.java` réutilise le serveur NIO du jeu
  (`GruntNIOTCPServer` via `GruntServerFactory`, même package) + codec + `MessageFactory`.
  `server/smoke/HandshakeRoundTrip` : `ClientInfo1 → BootData1` sur socket réelle, sans libGDX.

### À faire (ordre conseillé)
1. [x] ~~Extraire clé XOR + `ServerType`~~ (fait via androguard — voir RECON/PROTOCOL).
   Reste : décompiler en `libs/game-remapped.jar` régénérable (pour réutiliser les classes).
2. [x] ~~**RISQUE #1**~~ ✅ résolu : l'APK accepte les assets archivés (décision par révision,
   pas par GameVersion). Algorithme AssetUpdater documenté dans `docs/ASSETS.md`.
3. [x] ~~**Serveur de contenu v0**~~ ✅ `server/content_server.py` : sert `index.txt`
   (URLs réécrites) + copie locale/302 vers archive.org. Testé de bout en bout.
4. [x] ~~**Décompiler l'APK en jar**~~ ✅ `tools/decompile.sh` → `libs/game.jar` (dex2jar/Maven).
   Codec du jeu réutilisable prouvé (`server/smoke/CodecRoundTrip`). Voir `docs/SHIMS.md`.
5. [x] ~~**Serveur login v1**~~ (squelette) ✅ handshake `ClientInfo1 → BootData1` prouvé sur
   socket TCP (`server/java/dhserver/LoginServer.java`). Framing/codec gérés par les classes
   du jeu (pas de `packInt` à reverser). Reste : (a) champs **minimaux** de `BootData1` exigés
   par le **vrai** client ; (b) `POST /login` HTTP (format à reverser dans `RPGMain`/`GameMain`).
6. [~] **Backend/port desktop LWJGL3 maison — le jeu BOOTE jusqu'à l'écran de chargement.**
   Backend complet écrit (`desktop-port/src/main/java/dhbackend/` : Dh{GL20,Graphics,Input,
   Files,Preferences,Application,DeviceInfo,Audio,Net,Bridges,StatFileExt} + `GlfwInput`),
   porté du `dsbackend/` de DragonSoul contre le core libGDX **clair** du jeu. Launcher
   `dhdesktop/DesktopLauncher` : GLFW+GL, câble `Gdx.*`, `new GameMain(DhDeviceInfo)`, pilote
   create()/render(). **`GameMain.create()` OK** (compression ETC1, assets, shaders, UI XHDPI
   1280×720) → **LoadingScreen rend** (LoadBootAtlasUI, ShowDisneyLogo, StartServerLogin…).
   Stats `.tab`/`.tabb` chargées via `DhStatFileExt` (274). **LOGIN FONCTIONNEL** : le vrai
   client fait son `/login` sur notre serveur (`content_server.py` étendu) → réponse JSON
   `{"status":"good","data":"host:port"}` → **se connecte à notre serveur de jeu TCP**
   (`run-online.sh` : contenu+login :8080 + jeu :8081 + client). Contenu = **auto** via le
   FileDownloader du jeu (java.net) pointé sur notre serveur ; `fetch_assets.sh` = cache offline.
   **✅ LE JEU ORIGINAL TOURNE EN NATIF JUSQU'AU MAINSCREEN INTERACTIF** de bout en bout avec NOTRE
   serveur (capture `desktop-port/build/online.png`). Chaîne : `/login` → BootData1 (notre
   LoginServer) → `handleBootData` → MainScreen. Correctifs (aucune réécriture de jeu) : **reframe
   ASM** de game-logic.jar (StackMapTable → plus de crash JVM sur les stats binaires, retrait
   `-Xverify:none`) ; shadow **FirebasePerfUrlConnection** (analytics off, download réel — à
   remplacer par un shim Android `StrictMode`) ; **DhBridges** no-op (à refaire en INative réel).

   **ARCHITECTURE NATIVE (voie fidèle — cf. `native/NATIVE_PLAN.md`, `desktop-port/INVENTORY.md`)** :
   Disney Heroes ≠ DragonSoul. DragonSoul = Spine **Java** (tourne tel quel). Disney Heroes =
   **natif C** : `cspine.Native`/`cparticle.Native` chargent la lib `spine-native` (PerBlue l'a
   bâtie pour desktop, mode `COMBAT_AUTOMATOR`). ⇒ On fournit **`spine-native64.so`** (rebâti sur
   spine-c **officiel** 3.6, interface JNI EXACTE de PerBlue via `native/`), et le **code d'origine
   `cspine.*`/`cparticle.*` du jeu tourne INCHANGÉ**. On a **SUPPRIMÉ** les réécritures Java
   (shadows cspine, spine-libgdx, shadow DataInput). Libs natives requises (cf. INVENTORY) :
   `gdx` ✅, `gdx-freetype` ⬜ (polices), `spine-native` 🔨, opensl/adcolony non requis.
   État : **cspine natif fonctionne, banding CORRIGÉ** (2026-07-11). `Skeleton_getVertices` regroupe
   désormais les triangles par **page d'atlas** et émet `drawCalls` = N paires `(indexCount, pageIndex
   0-based)`, renvoie N — contrat **extrait** du renderer Java EN CLAIR `NativeSkeletonRenderer.
   renderPreparedVertices` (`textures.get(pageIndex).bind()`; `mesh.render(shader,4,indexStart,indexCount,
   false)`; `indexStart+=indexCount`) et de `NativeAtlas.load` (ordre des pages via `Atlas_getTexture`
   0..n). De plus le natif recale `position/limit` des buffers Java remplis (le chemin VertexArray fait
   `buffer.position(offset)/limit(offset+count)`). ⇒ **splash MainScreen rendu proprement, 0 banding**
   (vérifié vs jeu original : tous les héros Spine multi-pages OK). Reste `cparticle = échafaudage neutre`
   (à rebâtir fidèlement) + extensions cspine à confirmer (setSlotEyeState/setTintBlack/nextEvent).
   ⚠️ cparticle DOIT être rendu fidèle par **extraction/désassemblage de la lib ARM d'origine** (source de
   vérité), PAS par du code deviné (cf. PRINCIPLES §4/§4bis).
   **⭐ PIVOT ARCHITECTURE (2026-07-11) — unidbg : on exécute le VRAI binaire d'origine.** Au lieu de
   rebâtir spine (mon code C) ou de RE les particules, on fait tourner `native/reference/libspine-native.so`
   (ARM, PerBlue, committé) IN-PROCESS via **unidbg** (émulation ARM + bionic/JNI virtuel). Prototypes
   `native/unidbg/` CONCLUANTS : `Effect_create` parse un vrai `.np` (résout #NP-V3 par exécution),
   `Skeleton_getVertices`/`Effect_getVertices` rendent de vrais sommets 2-couleurs. Perf (unicorn) :
   particules ~141 µs/frame/effet (~118/frame, OK) ; spine ~1.5-2.1 ms/squelette (~7-11/frame, limite
   pour le combat). dynarmic (JIT) inutilisable (crash NEON d16-d31). Trou unidbg comblé :
   GetDirectBufferAddress/Capacity (JNI 230/231) implémentés via un ArmSvc (plomberie). **Intégré en
   jeu** : `dhbackend/unidbg/UnidbgVM` (VM persistante, dispatch synchronisé) + shadows
   `com.perblue.heroes.cspine/cparticle.Native` (câblage) ; `build.gradle` dep `unidbg-android:0.9.8`
   (binaire hôte unicorn embarqué → **build autonome, rien à installer**) ; `run-desktop.sh -Ddh.spinelib`.
   **Compile + boote SANS crash.** ✅ **Rendu spine EN COMBAT VALIDÉ (2026-07-11)** : le tutoriel d'intro
   atteint le 1ᵉʳ combat (via serveur nouveau joueur + pilote `dh.autotap`) et **tous les héros/ennemis Spine
   se rendent correctement via unidbg** (Ralph, Vanellope, Elastigirl, ennemis « glitch », + particules/VFX)
   — capture `native/reference/shots/tutorial-combat1-unidbg.png`. Le doute « spine in-game pas exercé » est
   levé. `spine-native64.so` (rebuild) = ABANDONNÉ pour desktop (conservé comme oracle/référence).
   Détail : `native/unidbg/README.md`.

   **cparticle — avancement `.np`** (désormais moot via unidbg, gardé pour référence) : lecteur natif `ParticleEmitter::load` @0x19755 désassemblé (helpers
   classés : readInt 4o BIG-ENDIAN, readBool 1o, readRanged 10o, readScaled 32o — formats IDENTIQUES au
   `ParticleEmitter.saveBinary` clair de game.jar). En-tête `byte0,byte3,int count` confirmé. **#NP-V3
   CONFIRMÉ** : les 535 `.np` réels NE matchent NI l'ordre du saveBinary courant NI la reconstruction
   statique (0/535) → l'ORDRE des champs v3 diffère ; à extraire SANS devinette via (1) oracle d'exécution
   qemu (struct parsée) ou (2) auto-parse validé par l'invariant des offsets de pool (535/535). Détail :
   `desktop-port/NP_FORMAT.md`.
   ← PROCHAINES ÉTAPES : (a) extraire l'ordre v3 CERTIFIÉ (oracle/auto-parse) → `Effect_create` + sim +
   rendu 2-couleurs cparticle fidèles ; confirmer extensions cspine ; (b)
   `gdx-freetype` natif ; (c) refaire DhBridges → INative réel + shim `StrictMode` ; (d) routage
   nouveau joueur → tutoriel `IntroTutorialActV1` + **BootData complet** ; (e) persistance.
   Détail : `desktop-port/BACKEND_STATUS.md`, `desktop-port/INVENTORY.md`, `native/NATIVE_PLAN.md`.
7. [~] **⭐ PHASE SERVEUR AUTORITAIRE — plan ordonné dans [`docs/SERVER_PLAN.md`](docs/SERVER_PLAN.md).**
   Le client tourne 100% d'origine (spine+particules via unidbg) et atteint le **hub STABLE** (écho
   `Ping` = keepalive ✅). Étapes ordonnées (une validée avant la suivante) : (1) session stable ✅ ;
   (2) **stat-sync** ✅ non-problème (incohérence `.tab` = comportement tolérant d'origine) ;
   (3) **BootData nouveau joueur complet → TUTO ✅ VÉRIFIÉ EN JEU (2026-07-11)** : `new BootData()` est
   complet par les initialiseurs du jeu (vérifié sur le ctor décompilé) ; le routage tuto passe par
   `individualUserExtra.tutorialActs` — un nouveau joueur porte **TOUS les `TutorialHelper.NEW_USER_ACTS`
   (122)** à step 0, **lus dans le registre du jeu** (`NewUserState.java`, zéro saisie). Le client lance
   **`IntroTutorialActV2`** (correction : v2, pas V1) et rend la scène d'ouverture (Ralph + Vanellope +
   portail) — capture `desktop-port/build/online-tuto.png` ; émet `ChangeTutorialStep`. Les SEVERE
   `Missing row in tutorials.tab` sont intrinsèques au chargement de la `.tab` (incluent `REMOVED__CRYPT`,
   hors de mes actes), pas causés par le serveur.
   **(4) Handlers du tuto ✅ (intro) VÉRIFIÉ EN JEU** : le tuto d'intro est **100% client** (IntroTutorialActV2
   n'émet aucun message ; combat local `CombatSimHelper`) ; seule sortie serveur = `ChangeTutorialStep`.
   `ServerUser` (état autoritaire) l'applique (step absolu ; maxStep=plus haut vu) → reconnexion à jour.
   Pilote headless `dh.autotap` : intro jouable **de bout en bout jusqu'au 1ᵉʳ combat** (GATE→TRANSFORM→
   COMBAT1 + logo), serveur = 0 réponse.
   **(6) Hub EN COURS — archi « serveur exécute le code+données du jeu » (règle affinée §3, 2026-07-12)** :
   `ServerStats` charge les `.tab` (`StatFileHelper.setExt`) → la logique du jeu tourne headless ; on
   reconstruit un `User` de jeu (`ClientNetworkStateConverter`) et on appelle la logique d'origine. **Spike
   OK** : `ChestStats.getDropTable(GOLD)`+`DropTable.rollNode("ROOT")` → **Frozone** (1ᵉʳ coffre nouveau
   joueur, rig `PreviousRolls(0)`). Dépendance : **joda-time** (données fuseaux hors `game.jar`, fournie).
   Faits : 0 héros avant le coffre (intro synthétique) ; Frozone prédéfini ; coffres hors-tuto = roll serveur
   des `<type>_chest_drops.tab`. Réponse client = `LootResults`. **Handler `BuyChests` ✅ FAIT & VÉRIFIÉ WIRE** (ServerContext = données+shim DH.app ; ServerUser.openChest exécute le code du jeu ; `ChestWireTest` : BuyChests(GOLD)→LootResults{Frozone} ~630ms ; persiste). **Couche évènements spéciaux ✅ (2026-07-12)** : `ServerContext` initialise `SpecialEventsHelper` (init+setSpecialEvents) avec une **extension serveur** (`ServerSpecialEventsExt`, l'extension cliente exige libGDX) → `updateChestCounters` réactivé + le **2ᵉ coffre** (récompense d'objet → `onItemEarn`→contest) ne plante plus (révélé par run client réel). PARTIEL restant : coffres **payants** (shim DH.app battlePassV2 à étoffer). **exit 144 = SIGSTKFLT = kill externe du superviseur** (confirmé sous `strace -f` : 0 crash natif, exit_group(0) propre, disparaît sous ptrace).
   **Tuto traversé in-game par le pilote DEV (2026-07-12)** jusqu'à l'équipement : intro→2 coffres (GOLD/Frozone,
   SILVER/**Badge of Friendship**)→CRATE READY→menu HÉROS→détail→onglet GEAR→équip. Correctifs pilote
   (`TutorialDriver`) : ferme les popups de récompense (`hide()`), tape le bouton-texte principal (VIEW),
   **cherche l'acteur dans TOUTE la scène** (menu latéral hors rootStack). **Reprise persistée** (ne pas
   supprimer la DB → ~20 s au hub au lieu de ~4 min ; snapshots `server/data/dh-snapshot-*.db`). Méthodo dans §6ter.
   **Handler `Action` : `EQUIP_ITEM` ✅ VÉRIFIÉ (2026-07-12)** — logique **CŒUR par commande**
   (`ServerUser.applyCommand`), **PAS `doAction`** (chemin CLIENT couplé UI : `getScreenManager().getScreen()`
   ×4 → NPE headless). `EQUIP_ITEM` = `HeroHelper.getSlotThatCanEquip` + `equipItem` (logique d'origine, **sans contournement**).
   Vérifié (`server/smoke/EquipTest`) : nouveau joueur → coffres GOLD+SILVER → équipe le Badge en slot 6 de
   Frozone, consomme l'objet, **persiste au round-trip wire**. **Couche CONTENU ✅ (unlock générique)** :
   `ContentHelper` démarrait vide (`getColumns()=0` → `isItemReleased`=false pour tout → cassait
   `getSlotThatCanEquip` et toute logique gatée « contenu released ») → `ServerContext.bind` appelle
   `ContentHelper.get().setShardID(shard, {})` (charge `content.<shard>.tab`, 372 colonnes). Correctifs
   annexes : **`GuildStats` PAS bloquant** (illusion de crash), shim **`DH.app.guildInfo`**. `LoginServer`
   log+applique chaque `Action`.
   Autres commandes à ajouter au fur et à mesure (`VIEWED_CHESTS`/`RECORD_SERVER_ROLL_FINISHED` = état léger).
   Cf. SHIMS #5.
   **(4bis) Tuto d'intro joué JUSQU'À `DONE` (harnais DEV, 2026-07-12)** : `TutorialDriver` (guidé par
   `TutorialHelper.getPointers`) + `dh.autofight` (auto-combat d'origine `setAutoAttack`) — **outils DEV
   côté lanceur, off par défaut, aucune modif jeu/serveur, rien en prod**. Intro complet (COMBAT1+COMBAT_2)
   puis **coffre de départ → `BuyChests1`+`Action1`** → écran « Waiting for results… ». ⇒ **frontière du
   hub** : le serveur doit gérer `BuyChests` (héros de départ). **FPS combat ~9** (headless SANS GPU ;
   unidbg spine ~80 ms/frame dominant en combat plein → futur chantier perf). Captures shots/*.
   **(5) Persistance SQLite ✅ FAIT & VÉRIFIÉ** : `ServerUser` détient l'état comme **objets du jeu**
   (`UserInfo`/`UserExtra`/`IndividualUserExtra`) ; `UserStore` (sqlite-jdbc) les stocke en **BLOB d'octets
   wire** (`writeAll`↔`MessageFactory.readMessage`), 1 objet = 1 BLOB, aucun schéma inventé. `LoginServer`
   charge-ou-crée au boot + persiste à chaque `ChangeTutorialStep`. Vérifié : reload cross-session (INTRO
   step 40) + session en jeu réelle (DB reflète la progression). Reste : (6) handlers du hub (nom, campagne
   réelle, récompenses = actions post-intro server-validées) ; (7) multi-serveur. Serveur = classes du jeu
   (GruntNIOTCPServer/codec/MessageFactory), client = source de vérité.
8. [ ] **Outil d'extraction data → format serveur** (les `.tab` chargés tels quels).

---

## 8. Conventions

- **RÈGLE D'OR : on ne réécrit RIEN du jeu à la main — on EXTRAIT (données ET code) par commande**,
  dans des fichiers régénérables. La seule couche écrite à la main = la **plateforme** (`dhbackend/`),
  minimale, sans logique de jeu. Binaires natifs = code du jeu → binaire d'origine, ou rebuild
  **vérifié fidèle** (désassemblage lib d'origine), jamais **inventé**. Cf. `docs/PRINCIPLES.md` §4.
- **Fidélité vérifiée** contre des **captures du jeu original** : tout écart de rendu/UI = **bug à
  corriger** (retour au comportement d'origine), pas une approximation. Cf. PRINCIPLES §4bis.
- Commits/pushes **réguliers** sur `claude/disney-heroes-port-rhhtuj` (le conteneur peut
  être reset — ne jamais perdre de travail). Artefacts lourds **régénérables par script**.
- **Ne jamais** faire apparaître l'identifiant du modèle dans un commit/artefact.
- Toute décision technique se conforme à `docs/PRINCIPLES.md`.
- À chaque étape : mettre à jour **JOURNAL.md** (détaillé) et l'historique court + « État
  courant » de **MEMORY.md**.
