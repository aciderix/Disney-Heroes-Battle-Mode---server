# MEMORY — Disney Heroes: Battle Mode — Portage Desktop + Serveur

> **Document de récupération de contexte.** À lire en premier par n'importe quel
> agent reprenant le projet (notamment après compression de contexte ou reset du
> conteneur). Il contient l'état courant, l'architecture, les liens, la hiérarchie
> des fichiers et un historique **court**. L'historique **détaillé** est dans
> [`JOURNAL.md`](JOURNAL.md). **Maintenir ce fichier à jour en permanence.**

> ## ⚠️ PROCÉDURE DE REPRISE APRÈS COMPRESSION (obligatoire, demandée par l'utilisateur)
> **À CHAQUE compression de contexte / reprise, exécuter dans l'ordre AVANT de continuer :**
> 1. **Relire en ENTIER** : `MEMORY.md` (ce fichier), les **derniers commits** (`git log --oneline -20`),
>    les **dernières entrées** de `JOURNAL.md`, **`docs/SHIMS.md` en entier**, `docs/PROTOCOL.md`,
>    `docs/PRINCIPLES.md`, `docs/SERVER_PLAN.md`, `docs/ARCHITECTURE.md` (+ `docs/TUTO_WALKTHROUGH.md`,
>    `docs/SIGNIN_EVENTS.md`, `docs/HUB_NAV.md` selon le sujet en cours).
> 2. **Énumérer les RÈGLES DE TRAVAIL** (elles sont et resteront **incontournables**), + les **astuces,
>    méthodologies et commandes documentées** (cf. §6bis/6ter ci-dessous), pour les avoir en contexte.
> 3. **Faire le point** sur l'état courant ET sur ce qui a été transmis lors de la compression, PUIS enchaîner.
> Ne PAS sauter cette procédure : c'est la condition pour reprendre dans de bonnes conditions.

Dernière mise à jour : **2026-07-19 (g7)** — **Test écrans hub « ! » : MEDALS OK ; QUESTS CRASHAIT le client (battle-pass-v2) → CORRIGÉ**.
Demande user : tester les écrans du hub en commençant par ceux avec « ! ». **MEDALS ✅** (voir g6, `COMPLETE_QUEST`). **QUESTS ❌→✅** : ouvrir QUESTS **crashait le client** — `QuestsScreen.showDot` → `BattlePassV2Helper.hasUnclaimedRewards(user, DH.app.getUserBattlePassV2())` → `computeRewards(type)` **lève** `IllegalArgumentException: Battle Pass types other than 'Quest' haven't been implemented` (le 12.1.0 ne gère QUE le type `QUEST`). **Cause précise** : `BattlePassType` n'a que `{DEFAULT, QUEST}` ; le serveur **n'envoyait PAS** `BootData.battlePassV2Data` → défaut `type=DEFAULT` → `computeRewards(DEFAULT)` crash. **PAS un décalage d'ère** (juste un état non initialisé). **Corrigé** (`ServerUser.bootData`) : envoyer `battlePassV2Data{type=QUEST, startTime=BattlePassV2Stats.getSeasonStartTime(), userID}` (logique du jeu, contenu-dérivé ; progress=0 compte neuf, non persisté pour l'instant). **Vérifié EN JEU** : QUESTS **rend sans crash** (WEEKLY QUEST tiers 21/42/…/105, BATTLE PASS, STAMINA BOOST). Bonus trouvé : mes **10 fragments Vanellope** (g6) ont **déclenché l'acte tuto `UNLOCK_HERO`** (Ralph « chip things with Vanellope's face » → flèche HEROES → carte Vanellope → SUMMON confirm). Fichiers : `server/java/dhserver/ServerUser.java`. **Reste** : finir UNLOCK_HERO (débloquer Vanellope) ; tester CAMPAIGN/CRATES (« ! », déjà OK au parcours manuel g5) + ITEMS/MAILBOX/EVENTS/HEROES.

Dernière mise à jour : **2026-07-19 (g6)** — **HERO_FILTERS terminé (vrai DONE) + GAP `COMPLETE_QUEST` trouvé en jeu & corrigé (+ shim `INative`)**.
Suite « finir le tuto ». **(a) HERO_FILTERS TERMINÉ** (vrai) : décompilé `HeroFiltersActV1` = 8 steps (`INITIAL,DIALOG_1,WAIT,DIALOG_2..5,DONE`), **DONE=ordinal 7**, `getMaxStep()=DONE.ordinal()=7` ; piloté en semi-auto (`drive` suit le pointeur du tuto) jusqu'à **step 7 = DONE** (vérifié sur l'ENUM, plus sur le champ `maxStep`=highest-seen). **⚠️ Portée du « tuto »** : `TutorialHelper.NEW_USER_ACTS` = **122 entrées** ; le tuto GUIDÉ forcé (INTRO+INTRO_FEATURES) est **fini** (nav libre = plus de `TUTORIAL_CANT_DO_THAT_YET`, l'autopilote **idle** au hub) ; les acts restants sont **contextuels/gated par progression** (GUILDS/COLISEUM/ELITE/HEIST… = TL20-60+, milieu de jeu). L'autopilote est un pilote de TUTO (suit les flèches), **pas un grinder** → post-tuto il idle. **(b) GAP `COMPLETE_QUEST`** (trouvé en cliquant MEDALS→« THANKS! ») : réclamer une quête/achievement envoie `Action{COMPLETE_QUEST, extra={ID}}`, **non géré** → récompenses (fragments Vanellope…) non créditées. **Corrigé** : handler `applyCommand COMPLETE_QUEST` → `QuestHelper.completeQuest(id, user)` (logique du jeu : `isReadyToComplete` = prérequis vérifié contre l'état SERVEUR = **anti-triche RÉEL** ; `RewardHelper.giveReward` ; compteurs). **Persistance** : l'état de quête (`completedQuests`/`questCompletionTimes`/`questStartTimes`/`questCounters`) vit dans `IndividualUser` **copié** de `individualUserExtra` (IntIntMap gdx) → HORS this.extra → **resync ciblé** de la quête complétée vers le wire (sinon re-réclamation au reload). **Shim `INative`** requis : `GameMain.getNativeAccess()` était null → NPE quand la logique de quête appelle `handleSilentException` (gestionnaire « exception silencieuse récupérable » du jeu) → posé un **proxy no-op** (champ `nativeAccess` par réflexion ; PAS `setNativeAccess()` qui fait `createPurchasingInterface().initializePreNetwork()`). **NB CodeLocation** : `setCodeLocation(SERVER)` (tentation pour aligner isOnClient=false) **CASSE tout** (`ContentHelper.extension` null headless) → on reste client-location et on ÉVITE les API de découverte d'UI (`getUnlockedAchievements`→`getAllQuestIDs` = thread-check + cast gdx Array) ; le handler cible par ID. Vérifié `server/smoke/CompleteQuestTest` (compteur persiste) + **régression 14/14 verte**. **✅ CONFIRMÉ EN JEU** : écran MEDALS → CLAIM (NEAR CENTER STATION, `ID=1200`) → serveur « récompense créditée + complétion persistée » ; **CLAIM ALL** = lot `COMPLETE_QUEST` (1200/1201/1202) tous traités, **0 non gérée** → inventaire persisté **STONE_VANELLOPE ×10** (= de quoi débloquer Vanellope, `UNLOCK_HERO` en demande 10 !) + **STONE_YAX ×2**. Checkpoint `dh-snapshot-quests-0719.db`. Fichiers : `server/java/dhserver/{ServerUser,ServerContext}.java`, `server/smoke/CompleteQuestTest.java`.

Dernière mise à jour : **2026-07-19 (g5)** — **PRISE DE CONTRÔLE MANUELLE : 3 écrans confirmés EN JEU au clic réel (CHOOSE NAME, SIGN IN claim, HERO_FILTERS)**.
Suite du (g4) : depuis le save compte-neuf (au hub, **autotap OFF + clickfile ON**), j'ai piloté à la main (via `dh.clickfile`) pour confirmer **en jeu** 3 écrans à handlers déjà bâtis mais non vérifiés au clic réel — tous **OK client↔serveur↔persistance** :
> 1. **CHOOSE NAME** : profil joueur (Team Level 2, **Max TL 565**, Account ID 1, Server « (1) Number One Dime », Client 12.1.0) → crayon → **CHANGE NAME** → bouton **RANDOM** (« Baroness Dante », pas de clavier headless) → **CHANGE NAME** → serveur `<== SetPlayerName` `[setname] nom → 'Baroness Dante' appliqué [persisté]` → **affiché « BARONESS DANTE »** au profil ET au hub.
> 2. **SIGN IN claim** : bâtiment SIGN IN → `Action REFRESH_SPECIAL_EVENTS` → serveur `==> SpecialEventsRaw (31 jours)` → écran **DAILY SIGN-IN** (valeurs exactes `.tab` : j1 226,25M or, j2 50 diamants, j4 35305…) → tap jour 1 → popup **CLAIM** → serveur `<== CLAIM_SIGNIN_REWARD {INDEX=0}` `[action] CLAIM_SIGNIN jour 0 → GOLD 226 250 000 appliqué [persisté]` → **or hub 3 476 → 226,25 M** (persisté, vérifié `DbInspect`=**226 253 476**).
> 3. **HERO_FILTERS** (acte CONTEXTUEL, là où l'auto-pilote calait) : menu HEROES → HeroListScreen (3 héros niv.2 W + héros verrouillé) → bulle tuto « Hero Filters… » → **FILTER** (`FILTER_BUTTON`) → **HERO FILTERS window** (catégories GENERAL/EFFECTS/±STATS/TALENTS, Sources, Team…) → navigation catégories → **acte progressé step 1→4** (le blocage historique du pilote = getPointers vide headless est **débloqué par le contrôle manuel/semi-auto** ; commandes `drive`/`center` du (g3) OK : log `[semiauto] center → tap`). ⚠️ **NON TERMINÉ** : `HeroFiltersActV1` a **11 étapes** (jusqu'à `DONE`) ; je me suis arrêté au **step 4** (le tuto demandait encore de **toggle un filtre** — visible sur la capture). **CORRECTION D'UNE ERREUR** : j'avais écrit « 4/4 COMPLET » = FAUX — mauvaise lecture du champ persisté `maxStep` (= **plus haut step VU**, `ServerUser.java:169`, PAS le total de l'acte). L'écran et son flux client↔serveur sont **confirmés fonctionnels** ; l'acte lui-même reste **à finir** (toggle→…→DONE).
**⇒ Le serveur alimente/valide/persiste les écrans post-tuto testés (nom, sign-in, écran filtres) sur le client 100 % d'origine.** ⚠️ **Distinction importante** : le **tuto onboarding principal** (INTRO + INTRO_FEATURES) EST **terminé** — corroboré NON par un champ mais par le COMPORTEMENT (le jeu est passé à la campagne 1-1→1-7 + actes STORY/ACHIEVEMENTS/TEAM_LEVEL_UP, gatés derrière la fin d'INTRO_FEATURES). Mais des **mini-tutos CONTEXTUELS** (HERO_FILTERS, et d'autres déclenchés par écran) ne sont **pas tous finis**. Checkpoint : `server/data/dh-snapshot-manualtests-0719.db` (nom changé + or 226M + HERO_FILTERS **au step 4, non fini**). **Reste** : finir HERO_FILTERS (toggle→DONE) ; explorer ITEMS/QUESTS/MEDALS/MAILBOX/EVENTS, PROMOTE_HERO, UNLOCK_HERO (Vanellope), campagne au-delà de 1-7. **Leçon** : ne PAS déduire « acte fini » du champ `maxStep` (highest-seen) — vérifier `step == DONE` de l'enum de l'acte, ou le comportement du jeu. Fichiers : `MEMORY.md`, `JOURNAL.md`.

Dernière mise à jour : **2026-07-19 (g4)** — **TUTO TERMINÉ EN JEU SUR COMPTE NEUF (autopilote) → campagne 1-7, tout persisté + fix méthodo snapshot WAL**.
Demande user : « repartir d'un compte frais, commence en auto puis prends le contrôle ; ce save servira pour les tests ». Anciennes saves **archivées** (`server/data/archive-0719/`). Run frais (`DH_AUTOTAP=1 DH_AUTOFIGHT=1`, détaché) : l'autopilote a joué **tout le tuto de bout en bout** — **INTRO 51/51 ✅ + INTRO_FEATURES 29/29 ✅ (DONE)** — coffres GOLD+SILVER (roster **3 héros** Ralph+Elastigirl+Frozone), équip Badge Frozone slot SIX, **1ᵉʳ combat de campagne GAGNÉ** (`CampaignAttack NORMAL 1-1 WIN`), puis **enchaîné 1-2→1-7 (tous WIN)** + actes post-tuto (STORY, ACHIEVEMENTS, TEAM_LEVEL_UP, SKILL_USE, AUTO_FIGHT, FAST_FORWARD). **État persisté vérifié** (`DbInspect`, snapshot WAL-inclus) : **TL2, 3 héros niv.2, campagne 1-1→1-7 toutes 3★, gold 3476, 11 types d'objets, stamina 120/120**. **0 message serveur non géré / refusé** sur tout le run (les `NumberFormatException ""` = parse tolérant intrinsèque de `guild_perk_levels.tab`, non fatal). ⇒ **le serveur tient le flux complet nouveau-joueur → tuto → campagne, autoritatif + persisté, sur compte neuf.** **Checkpoints** : `server/data/dh-snapshot-{tuto-done? non,supprimé},campaign-1to5-0719,campaign-1to7-0719}.db` (+`-wal`+`-shm`). **FIX MÉTHODO** : DB en **WAL** → snapshot = copier `.db`+`.db-wal`+`.db-shm` (le `.db` seul est périmé ; bug vécu : snapshot lu à 2 héros/TL1). **Reste (prochain = « prendre le contrôle »)** : run **autotap OFF + clickfile/semi-auto ON** depuis le snapshot pour tester manuellement les écrans à handlers déjà bâtis mais non confirmés au clic en jeu : **SIGN IN claim**, **CHOOSE NAME**, **HERO_FILTERS**. Fichiers : `MEMORY.md`, `server/smoke/TutoState.java` (probe actes de tuto).

Dernière mise à jour : **2026-07-19 (g3)** — **Pilote SEMI-AUTO : commandes clic-fichier `drive`/`auto`/`center`**.
Demande user : rendre l'auto-pilote **semi-auto** (« pouvoir l'activer pendant le combat, utiliser une de ces fonctionnalités pour accéder à telle partie lorsque ça ne reçoit pas ton clic manuel »). Ajouté à `injectManualClicks` (`DesktopLauncher`, outillage DEV lanceur, aucune modif jeu/serveur) : au lieu d'un `x,y`, une **commande** appelle UNE fonction du pilote pour la frame — **`drive`** = `TutorialDriver.driveOnce` (tape la cible désignée du tuto), **`auto`** = auto-combat d'origine (`setAutoAttack`) à activer PENDANT un combat, **`center`**/`tapc` = tap central `(W/2,H/2)` pour avancer un « TAP TO CONTINUE » de la scène de combat (input scène, non scene2d → un `x,y` ne suffit pas toujours). Compile OK (`gradle compileJava`). Doc `docs/TUTO_WALKTHROUGH.md` §4bis. Fichiers : `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java`, `docs/TUTO_WALKTHROUGH.md`.

Dernière mise à jour : **2026-07-19 (g2)** — **Coffres PAYANTS : DÉBIT DÉMONTRÉ END-TO-END (bug du 4ᵉ param corrigé) + correction d'une affirmation fausse sur « TL1 »**.
Demande user : « vérifier les coffres payants avec des team levels plus hauts pour vérifier tes dires ». En creusant, le débit payant était en réalité **INATTEIGNABLE** : `openChest` passait **`0` en dur** en 4ᵉ param de `validateChestPurchase`. **Relevé au bytecode (`ChestHelper.openChestInner`)** : le client appelle `validateChestPurchase(user, type, count, n2, item, snapshot)` où **`n2` = le COÛT**, ET pose `buyChests.cost = n2` → **le 4ᵉ param == `BuyChests.cost`** (0 pour un gratuit, le coût réel pour un payant). La branche PAYANTE fait `if (coûtRecalculéServeur > coûtDéclaréClient) throw ERROR` = **ANTI-TAMPER**. Avec `0` en dur : `288 > 0` → **ERROR sur TOUT achat payant**. **Fix RÉEL** : passer **`m.cost`** (miroir exact du client). **✅ DÉMONTRÉ** (`server/smoke/ChestPaidDebitTest`, **team level 40**) : gratuit consommé → ouverture GOLD **PAYANTE** (coût 288 déclaré) → **-288 DIAMONDS débités, persiste au wire** → coût **sous-déclaré (287) REFUSÉ** (anti-tamper). **⚠️ CORRIGE une affirmation antérieure FAUSSE** (« à TL1 le jeu refuse tout achat payant ») : `validateChestPurchase` n'a **AUCUN gate de team level** pour SILVER/GOLD ; le verrou tuto `REBLOCK_SILVER_BUY_ONE` est **côté CLIENT**. Le blocage était le bug du 4ᵉ param, pas le niveau. Régression 8/8 verte (`ChestValidate/FreeChest/ChestCharge/ChestWire/Equip/Resource/SigninMultiDay/ChestPaidDebit`). Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/{ChestPaidDebitTest,ChestChargeTest}.java`, `docs/SHIMS.md`.

Dernière mise à jour : **2026-07-19 (g)** — **Coffres PAYANTS : débit de la monnaie (économie autoritative) — clôt SHIMS TODO #2 / tâche #15**.
Suite logique de l'anti-triche coffres : avec `validateChestPurchase`, un joueur solvable **passe** la validation d'un coffre payant, mais `openChest` **ne débitait pas** → coffre payant **gratuit** (trou d'économie ; la « charge » optimiste du client était perdue au reload). **Corrigé** : branche `else` (non gratuit) de `openChest` débite `getPurchaseCurrency(type)` de `getPurchaseCost(user,type,count)` (monnaie par coffre : SILVER→GOLD 10000, GOLD→DIAMONDS 288, SOUL→DIAMONDS 74, SOCIAL→SOCIAL_BUCKS 250). GOLD auto-persisté (this.extra), DIAMONDS via `resyncDiamonds`. **L'ancien NPE `battlePassV2` (TODO #2) ne se produit pas** (`setResource(DIAMONDS)` OK headless). **Gardé derrière `validateChestPurchase`** → ne s'exécute que pour un achat **légitime**. **À TL1 (tuto), le jeu REFUSE tout achat payant** (limite/feature/monnaie) → débit = **safety-net jeu avancé**, non atteint dans le tuto (gratuit only). Vérifié `server/smoke/ChestChargeTest` (coûts/monnaies sains + rejet TL1) ; régression `Validate/FreeChest/Equip/ChestWire` OK. Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/ChestChargeTest.java`, `docs/SHIMS.md`.

Dernière mise à jour : **2026-07-19 (f)** — **ANTI-TRICHE coffres : validation serveur (validateChestPurchase) + réponse « cooldown 24h »**.
Question user : « comment le décompte 24h est validé ? le joueur peut-il tricher en avançant l'heure du mobile ? ». **Réponse (mécanisme prouvé au bytecode)** : la dispo du coffre gratuit = une **ressource régénérée** ; le serveur stocke/persiste `lastResourceGenerationTime` et recalcule via `updateAndGetResource` avec **`TimeUtil.serverTimeNow()`**. Formule : `serverTimeNow() = System.currentTimeMillis() − CLOCK_OFFSET` ; **`CLOCK_OFFSET` n'est posé que côté CLIENT** (`initClock(serverTime, deviceTime)` au login+Ping) — **le serveur ne l'appelle jamais → `CLOCK_OFFSET=0` → `serverTimeNow` serveur = l'horloge réelle de la machine serveur**. ⇒ l'état autoritatif (ressource+horodatage) est calculé avec l'horloge du SERVEUR ; le mobile ne calibre que SON affichage (re-synchronisé à chaque Ping) → **avancer l'heure du mobile ne contourne rien**. **MAIS l'anti-triche ne tient que si le serveur REFUSE** : `openChest` **n'appelait pas** `validateChestPurchase` → **enforcement ajouté** : `ChestHelper.validateChestPurchase(user, type, count, 0, usedItem, NONE)` (appel canonique de `SilverChestDetailScreen` — 4ᵉ param `int B = 0`, ItemType=null ; headless-safe : `Unlockables`+`getResource`) AVANT d'accorder → **lève `ClientErrorCodeException`** (checkée, extends Exception, non déclarée throws par dex2jar → catchée via `Throwable`+instanceof) si illégitime → `LoginServer` n'accorde/n'envoie RIEN (« ⛔ REFUSÉ »). Vérifié `server/smoke/ChestValidateTest` : ouverture gratuite **légitime accordée**, 2ᵉ ouverture (hors cooldown, 0 monnaie) **REFUSÉE** (`NOT_ENOUGH_GOLD`), état sain ; **n'a pas cassé le tuto** (`EquipTest` ouvre GOLD+SILVER gratuits → équipe OK). Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/ChestValidateTest.java`, `docs/SHIMS.md`.

Dernière mise à jour : **2026-07-19 (e)** — **3 diagnostics (questions user) : coffre gratuit non consommé (GAP CORRIGÉ), « seulement Frozone » = snapshot périmé, progression tuto cartographiée**.
**Parcours manuel du tuto** (outil de clic `dh.clickfile` + **dump du clic** `TutorialDriver.dumpClickTarget` : acteur touché+tag+listeners+écran) : tout le flux **crates→équip→filtre→campagne→combat** relevé clic à clic, **tout marche client↔serveur** (`docs/TUTO_WALKTHROUGH.md`). **Q1 — « Crates Left: 5/5 » après ouverture gratuite** : (a) « 5/5 » = `getAvailableChestPurchases` = limite d'**achats PAYANTS**, **correctement inchangée** par une ouverture gratuite ; (b) MAIS **vrai gap trouvé** : `openChest` **ne consommait PAS** le coffre gratuit (ressource régénérée `getFreeChestResource`) → `FREE NOW` restait dispo (coffre **farmable**). Le jeu ne consomme pas dans `ChestHelper` (client, lecture seule) — action serveur-autoritative. **Corrigé** : si `lr.wasFree`, `setResource(getFreeChestResource(type), cur-count)` (le `setResource` du jeu ré-ancre l'horloge → cooldown « Free in 23h »). Vérifié `server/smoke/FreeChestTest` (SILVER 1→0/`hasFreeChest`=false, GOLD intact, persiste). **Q2 — « seulement Frozone en combat »** : le snapshot post-équip date du **12/07**, le roster de départ (**Ralph+Elastigirl**) a été ajouté le **13/07** → snapshot **périmé** ; un **compte neuf a 3 héros** (Ralph+Elastigirl+Frozone, vérifié). **Q3 — progression tuto** : **INTRO ✅**, **INTRO_FEATURES ~90 %** (étapes de `IntroFeaturesActV2` = crates→équip→campagne→combat, mappées 1:1 à mon parcours ; reste : finir le combat → `CAMPAIGN_CLOSE_COMBAT_RESULTS` → DIALOG_1..6 → `DONE`). Ordre global des actes = `TutorialHelper.NEW_USER_ACTS` (INTRO, INTRO_FEATURES, ACHIEVEMENTS, UNLOCK_HERO, PROMOTE_HERO, DAILY_QUEST…). `HERO_FILTERS` = acte **contextuel** séparé (pas dans INTRO_FEATURES). Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/FreeChestTest.java`, `docs/{SHIMS,TUTO_WALKTHROUGH}.md`.

Dernière mise à jour : **2026-07-19 (d)** — **SIGN-IN CONFIRMÉ EN JEU (flux serveur) + tous types de récompense + frontière tuto = HERO_FILTERS (pilote)**.
**EN JEU (run tuto depuis snapshot post-équip)** : le pilote atteint le hub, et **quand le tuto lève son verrou de nav**, ouvre le bâtiment SIGN IN (`UINavHelper.navigateTo(SIGN_IN)`, hook `dh.gosignin`) → `SignInScreen` s'ouvre → envoie `REFRESH_SPECIAL_EVENTS` → **le serveur répond `SpecialEventsRaw (31 jours de sign-in)`** = le bâtiment SIGN IN est **alimenté par notre serveur en jeu** (capture hub : SIGN IN badge « ! », CHOOSE NAME en attente, stamina 39,96 M/120 authentique). **TOUS les types de récompense** (`server/smoke/SigninAllRewardsTest`) : 31 jours = 15 types (GOLD/DIAMONDS/GEAR_JUICE + 12 objets : EXP_COLOSSAL, DOUBLE_CAMPAIGN_*, COSMETIC/GURANTEE_COSMETIC_CHEST, GOLD_CHEST_ROLL_X1/X10, RED_SKILL_CHEST, BADGE_CHEST) se donnent via `RewardHelper.giveReward` sans exception et persistent. **NON confirmé en jeu** : le tap auto du bouton CLAIM (SignInScreen=`UIScreen`, `driveOnce` bute sur `getRootStack` avant la branche + `claim_button` est un **nom de son**, pas un tag) → finition pilote, le handler CLAIM est prouvé par tests. **FRONTIÈRE TUTO ACTUELLE = `HERO_FILTERS` step 2** (le pilote cale) : **0 action serveur non gérée** → PAS un manque serveur. Le tuto `HeroFiltersActV1` veut taper `UIComponentName.FILTER_BUTTON` (tag « FILTER_BUTTON ») sur l'écran HeroList, puis interagir dans la `HeroFilterWindow` (risque : le drainage de popups du pilote la ferme, comme jadis la CraftingWindow d'équip). ⇒ prochain chantier = **pilote HERO_FILTERS** (méthode capture→observer→câbler), itératif. **Note env** : `exit 144` (SIGSTKFLT superviseur) tue les runs/commandes longs et `pkill` → lancer détaché (`setsid`), éviter `pkill`. Commits : `71e60b4`..`10d3ba1`.

Dernière mise à jour : **2026-07-19 (c)** — **SIGN-IN MULTI-JOUR (j2/j3) vérifié + GAP DIAMANTS corrigé**.
Question user : « à j2/j3 le joueur aura-t-il les bonnes récompenses, bien créditées et disponibles ? ». Vérifié (`server/smoke/SigninMultiDayTest`) : le jour actif est piloté par `getMonthlySignins()` + chance quotidienne `getDailyChances("daily_signin")` (**reset LAZY** par le jeu — `DailyActivityHelper.checkAndUpdateDailyValues`, appelé à chaque lecture de `getDailyChances`, compare `serverTimeNow` vs `LAST_USER_DAILY_RESET` via `isSameUserDay`, remet la chance à son max ; sur `this.extra.dailyChances` persisté). j1 GOLD crédité → j2 (nouveau jour) DIAMONDS crédité → `monthlySignins` 1→2, tout persiste. **VRAI GAP TROUVÉ & CORRIGÉ** : les **diamants** vivent dans un champ dédié `IndividualUser.diamonds` (init depuis `userInfo.diamonds`, **hors `this.extra`**) → non auto-persisté → toute récompense en diamants (sign-in j1/j7/j16/j23 **et tout gain de diamants**) était **perdue au round-trip wire**. Corrigé : `ServerUser.resyncDiamonds` (`userInfo.diamonds = user.getResource(DIAMONDS)`) appelé après action/coffre/campagne (même schéma que team-level/nom). Régression 7/7 verte. **Réponse honnête aux 3 questions user** : (1) pas encore testé via le CLIENT complet in-game (le tuto verrouille la nav libre jusqu'à l'étape sign-in ; côté serveur tout est prouvé par tests) ; (2) j2/j3 OK, récompenses créditées & disponibles (diamants corrigés) ; (3) l'ext serveur `isNameLegalExt` est posé dans le **JVM serveur seulement** → **AUCUN impact sur les vrais joueurs** (le client desktop/android garde sa propre vérif police). Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/SigninMultiDayTest.java`.

Dernière mise à jour : **2026-07-19 (b)** — **CHOOSE NAME ✅ (SetPlayerName) + pilote `dh.gosignin`**.
**CHOOSE NAME** (onboarding, choix du nom) : relevé au bytecode (`ChangeNamePrompt.changeNameInner`), le client applique `UserHelper.changeName(user,name)` en local puis envoie **`SetPlayerName{name}`** fire-and-forget. **Serveur** : `LoginServer` branche `SetPlayerName` → `ServerUser.setPlayerName` ré-exécute `UserHelper.changeName` (légalité + coût, 1ᵉʳ gratuit via `FREE_NAME_CHANGE`), **re-sync** `userInfo.basicInfo.name`/`previousName` (car `User.userName` vit HORS `this.extra`, comme le team-level) et persiste. **Blocage headless résolu** : `NameChangeHelper.isNameLegal` fait le cœur PUIS un ext CLIENT `isNameLegalExt` qui vérifie le **rendu POLICE** (`DisplayStringUtil`→`LanguageHelper.getPreferredLanguage`→`Gdx.app.getPreferences` → NPE headless) ; on pose un **ext SERVEUR** `s->true` dans `ServerContext.init` (le cœur s'exécute ; la vérif police, sans objet serveur, déjà faite par le client — patron `ServerSpecialEventsExt`, pas une rustine). Vérifié `server/smoke/SetNameTest` (nom appliqué + persiste au wire) ; régressions `Signin/Resource` OK. **Pilote DEV `dh.gosignin`** (`TutorialDriver`) : ouvre le bâtiment SIGN IN au hub (`UINavHelper.navigateTo(SIGN_IN)`) mais **RESPECTE le verrou de nav du tuto** (`canNavigateTo=false` mid-tuto = fidèle, non contourné) → le SIGN IN live s'atteint quand le tuto le libère. Fichiers : `server/java/dhserver/{ServerUser,LoginServer,ServerContext}.java`, `server/smoke/SetNameTest.java`, `desktop-port/src/main/java/dhdesktop/TutorialDriver.java`, `run-online.sh`/`run-desktop.sh`.

Dernière mise à jour : **2026-07-19** — **SIGN-IN (récompense de connexion quotidienne) ✅ CONSTRUIT + RÉCLAMATION (feature de JEU, pas admin)**.
Manque trouvé en progressant le tuto : le client spammait `Action{REFRESH_SPECIAL_EVENTS}` et le bâtiment **SIGN IN** restait vide. C'est une REQUÊTE attendant un `SpecialEventsRaw{changed, events, signinRewards}`. **Correction de cadrage (user) : le sign-in n'est PAS une feature admin** — les récompenses sont **définies par la donnée** (`signin_rewards.tab`, extraite de l'APK) et la réclamation est **du code du jeu** (`SigninHelper.claim`) ; le serveur ne fait qu'**exécuter** (construire + claim), zéro invention (le seul « angle admin » = éditer un `.tab`, commun à TOUT le jeu). **Implémenté** : `ServerUser.buildSigninRewards()` construit `SigninRewards` en **roulant** `SigninStats.REWARDS_TABLE.getTable().rollNode("ROOT", new SigninContext(dayIndex, monthStart))` (une `RewardDrop`/jour, `DropConverter`) pour thisMonth/lastMonth/nextMonth (bornes de mois via `Calendar` sur `TimeUtil.getUserServerTime`), `signinHero` via `ContentColumn.getCurrentMonthlySigninHero()` ; **`SigninContext` instancié par réflexion** (classe imbriquée `protected` hors package). `LoginServer` (handler `REFRESH_SPECIAL_EVENTS`) pose `raw.signinRewards = user.buildSigninRewards()`. **Réclamation** : `applyCommand` cases `CLAIM_SIGNIN_REWARD`/`CLAIM_SIGNIN_WITH_VIDEO` → `SigninHelper.setData(...)` (claim lit `DATA`) → `isClaimable` → `SigninHelper.claim(user, index, withVideo)` (donne l'objet + compteurs, auto-persistés dans `this.extra`). **Vérifié `server/smoke/SigninTest`** : 31 jours roulés = valeurs exactes de la `.tab` scalées à l'ère R102 (L=565 : jour 0 GOLD 226 250 000, jour 1 50 DIAMONDS, jour 3 GEAR_JUICE 35305…), bornes cohérentes, réclamation crédite. Régressions `Resource/Equip/ViewedChests` OK. Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/SigninTest.java`, `docs/SIGNIN_EVENTS.md`. **Reste** : vérif EN JEU (SIGN IN affiche/réclame), puis CHOOSE NAME.

Dernière mise à jour : **2026-07-18 (nuit)** — **STAMINA : RETRAIT DU CLAMP `applyEffectiveResourceCap` (fidélité)**.
Question user : « le serveur reclampe 114/120 au lieu de 39 M — naturel ou ajouté par nous ? » → **ajouté par NOUS** (méthode `ServerUser.applyEffectiveResourceCap`, écrivait `min(getResource, cap)` + réancrait l'horloge avant chaque combat). Le JEU, lui, **ne clampe pas** : `updateAndGetResource` laisse STAMINA **déborder** (branche non-capée) et **STOCKE la valeur brute** (39,96 M à R102) — comportement authentique (débordement dépensable = « cadeau » de fin de vie, confirmé sur gameplay réel). Le clamp était donc une **déviation** (masquait le débordement, limitait la dépense à 120). Décision user : **« être fidèle au jeu »** → **`applyEffectiveResourceCap` RETIRÉ** (méthode + appel dans `recordCampaignAttack`). Le compte neuf reste à 120 (`initNewPlayerResources` ancre `lastResourceGenerationTime=creation` → 0 tick → pas de débordement au démarrage — CONSERVÉ, c'est le comportement d'un vrai serveur à la création, pas un clamp). Vérifié : `ResourceTest` (neuf=120), `CampaignAttackTest` (120→114), `CampaignPersistTest` (persiste) verts. NB : le « refill +20 en boucle » qui avait motivé le clamp était en réalité le bug de re-sync du **niveau d'équipe** (corrigé séparément), pas la stamina. Fichier : `server/java/dhserver/ServerUser.java`.

Dernière mise à jour : **2026-07-18 (soir)** — **ÉTAPE ÉQUIPEMENT DU TUTO ✅ DE BOUT EN BOUT (manque serveur trouvé & corrigé + pilote auto-équip)**.
En progressant le tuto (méthode clic MANUEL — nouvel outil `dh.clickfile` : injecte un tap via l'input réel + capture `build/manual.ppm` + monitoring serveur, car pas de xdotool ; câblé `run-desktop.sh`/`run-online.sh`), j'ai trouvé le flux d'équip réel : **bâtiment CRATES → FREE NOW → burger ☰ → HEROES → carte héros → slot GEAR → CraftingWindow → EQUIP**. **MANQUE SERVEUR #1 (RÉEL, CORRIGÉ)** : `EQUIP_ITEM` répondait `ClientErrorCodeException: WRONG_ITEM` → le handler **ignorait `extra[SLOT]`** et devinait via `getSlotThatCanEquip` = **1er** slot équipable (ONE) quand plusieurs le sont (Frozone a d'autres gear) → tentait le Badge en slot ONE. Fix (`ServerUser.applyCommand EQUIP_ITEM`) : **honorer le SLOT du client** (repli `getSlotThatCanEquip`). Vérifié `server/smoke/EquipSlotTest` + régressions (`EquipTest`/`ResourceTest`) + **EN JEU** : `EQUIP_ITEM FROZONE BADGE_OF_FRIENDSHIP SLOT=SIX` → **appliquée [persisté]** (DB : `FROZONE gear[SIX=BADGE_OF_FRIENDSHIP]`, `ELASTIGIRL gear[ONE=SUGAR_RUSH]`). **« Manque #2 » (Badge absent → DONT_HAVE_ITEM) = artefact de save polluée** (SILVER chest rouvert → tirage non-riggé) ; sur compte propre le SILVER riggé donne le Badge (`EquipTest`). **PILOTE AUTO-ÉQUIP (`dh.autoequip`, `TutorialDriver`)** : détection LOGIQUE (`HeroHelper.hasItemsToEquip` = le « +equip vert » ; `getSlotThatCanEquip` ; `getGearState`=READY_TO_EQUIP — probe `server/smoke/EquipDetectProbe`), suppression du rush campagne tant qu'un équip est en attente (`needEquip` gate `enterCampaignLevel`/FIGHT/enchaînement), navigation laissée aux **flèches du tuto** (suivies par le bloc « cible désignée ») + `equipDrive` en **dernier recours** (menu HÉROS → carte `HERO_LIST`+hero → HeroDetail → slot `HERO_GEAR_SLOT_ <SLOT>` [ESPACE] → CraftingWindow → EQUIP), **garde-fou anti-boucle** : une `ErrorWindow` (équip refusé, item non dispo/craft) → fermer + marquer le slot échoué (`failedEquipSlots`) → sauter (plus de re-EQUIP infini). **Vérifié en jeu** : le pilote enchaîne coffres→équip, le serveur accepte, 1 seul tap EQUIP. Fichiers : `server/java/dhserver/ServerUser.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`, `server/smoke/{EquipSlotTest,EquipDetectProbe}.java`, `docs/SHIMS.md`.

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
- **Snapshot** aux points sûrs pour restaurer une frontière connue. ⚠️ **La DB est en WAL** (`UserStore`
  fait `PRAGMA journal_mode=WAL`) → un `cp server/data/dh-server.db` **seul** capture un état **PÉRIMÉ**
  (les écritures récentes sont dans `dh-server.db-wal`, pas encore dans le `.db`). **Copier les TROIS
  fichiers** (`.db` + `.db-wal` + `.db-shm`) sous le même nom de snapshot, OU checkpointer d'abord. Vérifier
  un snapshot avec `DbInspect <snapshot.db>` (il lit le WAL committé si les 3 fichiers sont présents). Bug
  vécu 2026-07-19 : snapshot « tuto-done » lu à 2 héros/TL1 (état du boot) car seul le `.db` copié.

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
