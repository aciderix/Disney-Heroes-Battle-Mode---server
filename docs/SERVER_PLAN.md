# SERVER_PLAN — serveur de jeu autoritaire (plan ordonné)

But : compléter le **serveur autoritaire** pour qu'un **nouveau joueur** parte du **tuto** puis joue,
avec le **client 100% d'origine** (rendu spine+particules via unidbg, cf. `native/unidbg/`). On avance
**dans l'ordre**, une étape validée avant la suivante.

## Règles NON NÉGOCIABLES (rappel — détail : [`PRINCIPLES.md`](PRINCIPLES.md))
1. **Modifs minimales du jeu** ; comprendre le fonctionnement réel, pas contourner.
2. **Aucune rustine** — jamais de faux OK/bypass/état forcé ; un shim est RÉEL ou noté PARTIEL/NO-OP **avec
   son risque**. On résout les causes.
3. **Serveur = source de vérité autoritatif** ; **aucune donnée recopiée à la main**.
4. **On n'écrit RIEN à la main — on EXTRAIT (données ET code) par commande.** Le serveur **inclut les
   classes du jeu** (`game.jar`) et les **utilise directement** ; les données sont **extraites** dans des
   fichiers régénérables (`game-data/`). Les valeurs viennent des **initialiseurs/enums/classes du jeu**,
   jamais inventées. Le **client est la source de vérité** du protocole (on journalise ce qu'il envoie).
5. **Fidélité vérifiée** contre le jeu original (captures).
6. **Persistance complète et fidèle** (tout l'état, sérialisé via les classes du jeu).
7. **Reproductibilité** : commit/push réguliers ; jamais l'identifiant de modèle dans un artefact.

## Faits EXTRAITS (base du plan — cf. PROTOCOL.md §6)
- Pile réseau = **classes du jeu** : `GruntNIOTCPServer` (via `GruntServerFactory`), codec
  `DHXORConnectionWrapper` (Deflate+XOR), registre `MessageFactory` (610 messages). Zéro réimplémentation.
- Flux nouveau joueur (journalisé) : `ClientInfo1 → BootData1`, puis télémétrie
  (`ClockChange/SettingsSync/RecordNetworkEvents/LoadTime/PerfReport`) + **`Ping1`**.
- **Keepalive** : `Ping` DOIT être échoué (serverReceive/serverTime=now) sinon le client ferme
  (« Reconnecting… »). ✅ FAIT → session stable dans le hub.
- **BootData** (structure relevée) : ~25 champs (objets `UserInfo/UserExtra/GuildInfo/AllContestData/…`)
  + Maps **`statDataTxt`/`statDataBin`/`statVersions`** = **stat-sync serveur-autoritaire**.
- **Tuto** : piloté par des **flags de tutoriel** dans l'état joueur ; `TutorialHelper.NEW_USER_ACTS` vs
  `EXISTING_USER_ACTS`. Nouveau joueur = **aucun flag** → `IntroTutorialActV1`.
- **Incohérence stats `.tab`** : interne à l'APK 12.1.0 (donnée `.tab` ≠ enum du code, ex. EVIL_QUEEN
  `PREDICTIVE_FORTIFICATION`). Résolution CONFORME = le serveur peuple `statDataTxt` (données extraites),
  PAS d'édition de `.tab`. Aucune rustine.

## Méthode BootData (complet, sans réécriture)
`new BootData()` (complet par les initialiseurs du jeu) → peupler via les **classes du jeu**
(`new UserInfo()`, `new UserExtra()`, enums…). On lit `GameMain.handleBootData` (949 lignes) **en entier**
pour garantir que **chaque objet déréférencé est non-null** — pas de « minimal », pas de « plus tard ».

## Étapes ORDONNÉES (une validée avant la suivante)
1. [ ] **Session stable** — écho `Ping`. ✅ FAIT (hub stable, 0 reconnexion).
2. [x] **Stat-sync — NON-PROBLÈME (investigation « creuse d'abord »).** Les erreurs `.tab` (ex. EVIL_QUEEN
   `PREDICTIVE_FORTIFICATION`) sont **attrapées et loguées** par le propre `RowGeneralStats.parseStats`/
   `onStatError` du jeu, puis la ligne est **sautée** (0 `ExceptionInInitializerError`). Ma lecture « crash
   `<clinit>` » était fausse (une exception **loguée** ≠ fatale). ⇒ rien à corriger, aucune rustine ; c'est
   le comportement d'origine (tolère la `.tab` bootstrap). `statDataTxt/Bin` pourra servir plus tard pour
   l'équilibrage live, non bloquant.
3. [x] **BootData nouveau joueur complet — ✅ FAIT & VÉRIFIÉ EN JEU.** `new BootData()` (tous champs
   non-null par les initialiseurs du jeu — vérifié sur le constructeur décompilé) + identité de compte neuf
   (iD, creationTime, teamLevel=1). Routage tuto : `handleBootData` → `getIndividualUser(individualUserExtra)`
   → `IndividualUser.setExtra` itère **`individualUserExtra.tutorialActs`**. `completedTutorialAct` renvoie
   **true si l'acte est ABSENT** (tuto « sauté ») et se complète sur `step >= act.getMaxStep()`. Donc un
   nouveau joueur porte **TOUS les `TutorialHelper.NEW_USER_ACTS`** (122 types, lus **dans le registre du
   jeu**, dernière version enregistrée, `step 0`) — `server/java/dhserver/NewUserState.java`. Aucune donnée
   écrite à la main (régénérable). **Correction de fidélité** : la version INTRO enregistrée dans le 12.1.0
   est **`IntroTutorialActV2`** (pas V1). **Vérifié** : le client lance IntroTutorialActV2, rend la scène
   d'ouverture (Ralph + Vanellope + portail, dialogue `TUT_DIALOG`/`GATE_DIALOG_1_A`) et émet des
   `ChangeTutorialStep`. Les `SEVERE: Missing row in tutorials.tab` (EMERALD_RANK, FRANCHISE_TRIALS,
   PATCHED_HEROES, TEAM_LEVEL_UP, BATTLE_PASS_V2…) sont **intrinsèques au chargement de `tutorials.tab`**
   (le SEVERE inclut `REMOVED__CRYPT`, jamais dans mes actes ⇒ ça vient de `TutorialStats.onMissingRow`
   sur l'enum complet, pas de mon BootData) — comportement d'origine tolérant, comme l'étape 2. Aucune rustine.
4. [x] **Handlers du tuto — ✅ FAIT (intro) & VÉRIFIÉ EN JEU.** Fait extrait : le **tutoriel d'intro est
   entièrement piloté par le client** — `IntroTutorialActV2` n'émet **aucun** message réseau, et son combat
   est **local** (`CombatSimHelper.createUnitData(new User(), …)`, `pause/resumeCombat`, timers) ; la seule
   sortie serveur est **`ChangeTutorialStep`** (type/step/forceSkip, fire-and-forget). ⇒ le serveur **valide/
   persiste la progression** et n'a **aucune réponse** à fournir pendant l'intro. `ServerUser` (état
   autoritaire) applique `ChangeTutorialStep` (step absolu ; `maxStep` = plus haut pas vu) → le BootData de
   reconnexion reflète la progression. **Vérifié en jeu** (pilote headless `dh.autotap`) : le tuto se joue de
   bout en bout de la scène d'ouverture jusqu'au **1ᵉʳ combat** (GATE_DIALOG → TRANSFORM_ANIMATION →
   COMBAT1_… → révélation du logo), le serveur appliquant tous les `ChangeTutorialStep` reçus (INTRO 1→2→3…,
   FRIEND_MISSION, FRIENDSHIP_UNLOCK…), 0 réponse requise, 0 message inconnu. Reste (hors intro, avec la
   suite du monde) : les actions post-intro server-validées (choix du nom, 1ᵉʳ combat de campagne réel,
   claim de récompenses) relèvent des **handlers du hub (étape 6)**.
5. [x] **Persistance (SQLite) — ✅ FAIT & VÉRIFIÉ.** `ServerUser` détient l'état comme des **objets du
   jeu** (`UserInfo`/`UserExtra`/`IndividualUserExtra`) ; `UserStore` (SQLite via `sqlite-jdbc`) les stocke
   en **BLOB d'octets wire** produits par les classes du jeu (`GruntMessage.writeAll` ↔
   `MessageFactory.readMessage`) — **aucun schéma inventé** pour les données du jeu ; un objet du jeu = un
   BLOB (extensible sans recopie). Clé `(userID, shardID)`. `LoginServer` **charge-ou-crée** le compte au
   démarrage et **persiste** à chaque `ChangeTutorialStep`. Vérifié : progression écrite en session 1, DB
   fermée, **rechargée à l'identique en session 2** (INTRO step 40, 122 actes, BootData revalide sur le
   wire). `sqlite-jdbc`/`slf4j-api` récupérés à la demande (non committés, régénérables) ; DB sous
   `server/data/` (gitignore).
6. [~] **Monde / hub post-tuto — EN COURS.** Tuto d'intro joué **de bout en bout** jusqu'à `DONE` (harnais
   DEV), puis le tuto (INTRO_FEATURES) ouvre le **COFFRE DE DÉPART** → envoie **`BuyChests1`** (+ `Action1`)
   et **attend la réponse** (« Waiting for results… », `native/reference/shots/tutorial-intro-done-crate.png`).

   **Architecture (règle affinée §3 « lire & exécuter ») :** le serveur **charge les données du jeu**
   (`ServerStats` → `.tab`), **reconstruit un `User` de jeu** (`ClientNetworkStateConverter`) et **exécute
   la logique du jeu** ; il n'écrit que la glue. **Spike validé** : `ChestStats.getDropTable(GOLD)` +
   `DropTable.rollNode("ROOT")` sur `ChestContext(user, count=1)` → **`HERO_FROZONE`** (rig `PreviousRolls(0)`).

   **Faits coffres/héros (extraits) :**
   - **Avant le 1ᵉʳ coffre : 0 héros possédé.** Le combat d'intro (Ralph/Vanellope/Elastigirl) est
     **scripté/synthétique** (`CombatSimHelper.createUnitData(new User(), …)`). Les 5 « héros tuto » du jeu
     (`black_market_merchant_drops.tab` → `RemoveIf(SpecificHeroes, VANELLOPE, RALPH, YAX, ELASTIGIRL,
     FROZONE)`) sont acquis **progressivement** : Frozone (1ᵉʳ, coffre), Vanellope (`UnlockHeroActV1`, éclats),
     Yax (campagne 1-13 `HERO_YAX`), etc. Pas de roster de départ (`starter_deal_heroes.tab` = pack payant).
   - **1ᵉʳ coffre = FROZONE, prédéfini** (`IntroFeaturesActV2.getChestUnitType()=FROZONE`), garanti par la
     **table** : `gold_chest_drops.tab` `ROOT_1X_FIRST ? PreviousRolls(0) ? ROOT_1X_RIG_1` puis
     `? CJK ? HERO_BUZZ ? HERO_FROZONE` (Buzz en locale CJK). Puis coffre SILVER → objet d'équipement.
   - **Coffres hors tuto = 100% serveur** : `BuyChests{chestType, count, roll:ServerRollRequest{channelRollCount…}}`
     → le serveur roule `<type>_chest_drops.tab` via `DropTable` (rig 1ᵉʳ/2ᵉ tirage, pitié 10× `Try
     NoneAre(YourHero)`, payant/gratuit, VIP, locale, pools `@NON_EXCLUSIVE_HEROES`/`@GOLD_CHEST_EXCLUSIVE_HEROES`).
     `channelRollCount` (dans `IndividualUserExtra`) alimente `PreviousRolls` → à persister.

   **Handler `BuyChests` — ✅ FAIT & VÉRIFIÉ SUR LE WIRE.** `ServerContext` (données du jeu + shim
   `DH.app`) + `ServerUser.openChest` : construit un `User` de jeu SUR les objets wire → roule la vraie
   table (`ChestStats`/`DropTable.rollNode("ROOT")`) → `DropConverter` → `ChestHelper.giveChestRewards`
   (donne + remplit `heroesUnlocked`) → `updateChestRollCounters` → resync héros (`getHeroData`) → répond
   **`LootResults`**. Vérifié : unitaire (nouveau joueur 0 héros → `openChest(GOLD)` → Frozone, **persiste au
   reload**) ET **sur le wire** (`server/smoke/ChestWireTest` : `BuyChests(GOLD)` → `LootResults{Frozone}` en
   ~630 ms). **Couche évènements spéciaux ✅ (2026-07-12)** : un run client réel a révélé que le **2ᵉ**
   `BuyChests` (récompense d'objet) plantait en NPE (`giveChestRewards`→`RewardHelper.giveReward`→
   `ContestHelper.onItemEarn`→`SpecialEventsHelper.getActiveContestsWithTask`, `helper` null headless).
   Corrigé fidèlement : `ServerContext` initialise la couche comme `GameMain` (`SpecialEventsHelper.init`
   + `setSpecialEvents`) avec une **extension serveur** (`ServerSpecialEventsExt` : logique d'état d'origine,
   sans la poussée réseau cliente qui exige libGDX). `updateChestCounters` **réactivé**. Vérifié : 3 coffres
   d'affilée (dont objet) sans NPE + `ChestWireTest`. **PARTIEL restant (§2)** : coffres **payants** (charge
   diamants via `setResource`→`DH.app.getUserBattlePassV2`) nécessitent d'étoffer le shim `DH.app`
   (battlePassV2) — le tuto n'ouvre que des coffres **gratuits**. Le run client complet meurt parfois
   (exit 144 = SIGSTKFLT, **kill externe du superviseur**, confirmé sous `strace -f` : aucun crash natif
   JNI/.so, `exit_group(0)` propre, disparaît sous ptrace) — le handler est prouvé sur le protocole.
   Reste, pilotés par le client : `Action`, choix du nom, 1ᵉʳ combat de campagne réel, claims… Critère :
   navigation stable, fidèle aux captures d'origine.
7. [ ] **Multi-serveur / passerelle** (liste, mot de passe optionnel) — cf. ARCHITECTURE.md.

## Outils de DEV (jamais actifs en prod ; aucune modif du jeu ni du serveur)
Drapeaux du **lanceur** (`desktop-port`), **off par défaut** — en prod le joueur lance sans, jeu normal :
- `dh.autotap=N` : tap périodique (dialogues « tap to continue »).
- **`TutorialDriver`** (piloté par `dh.autotap`) : interroge `TutorialHelper.getPointers` pour taper la
  **cible désignée par le tuto** (retrouvée via `getTutorialName`/`findTutorialActor`), conversion
  stage→écran. Joue le tuto **avec le guidage du jeu**, sans coordonnée devinée.
- `dh.autofight=1` : active l'**auto-combat d'origine** (`CoreAttackScreen.setAutoAttack`) hors tuto (le tuto
  d'intro met le combat en pause et exige un tap manuel → géré par le driver, pas par l'auto).
- `dh.fps=N` : FPS glissants + **profilage unidbg vs rasterisation**. **Constat combat (headless, SANS GPU)**
  : ~9 fps ; en combat plein, **unidbg (émulation spine) domine ~80 ms/frame**, rendu logiciel ~40 ms. Pire-cas
  (pas de GPU + émulation ARM) → chantier « perf combat » (réduire les appels unidbg/frame, JIT, etc.).
Le **serveur n'automatise jamais** (pas de `TriggerCrawler`/`combatAutoSettings` forcés) : il envoie le même
BootData à tous. L'automatisation est **exclusivement** le harnais de dev côté lanceur.

## Journalisation / vérification
`dhserver.LoginServer` journalise chaque message reçu (client = source de vérité). Chaque étape est
vérifiée en jeu (run-online.sh, capture) + comparée aux captures du jeu original (§4bis).
