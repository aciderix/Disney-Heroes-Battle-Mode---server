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
3. [ ] **BootData nouveau joueur complet** — `new BootData()` + `UserInfo/UserExtra` nouveau joueur (aucun
   flag de tuto) via classes du jeu → le client route vers `IntroTutorialActV1`. Critère : le tuto démarre.
4. [ ] **Handlers du tuto** — journaliser puis traiter les messages émis pendant le tuto (le serveur
   valide/persiste la progression ; réponses via classes du jeu). Critère : tuto jouable de bout en bout.
5. [ ] **Persistance** (SQLite) — tout l'état joueur sérialisé via les classes du jeu (octets = wire).
6. [ ] **Monde / hub post-tuto** — handlers des requêtes du hub (héros, items, campagne…) au fur et à
   mesure, pilotés par le client. Critère : navigation stable, fidèle aux captures d'origine.
7. [ ] **Multi-serveur / passerelle** (liste, mot de passe optionnel) — cf. ARCHITECTURE.md.

## Journalisation / vérification
`dhserver.LoginServer` journalise chaque message reçu (client = source de vérité). Chaque étape est
vérifiée en jeu (run-online.sh, capture) + comparée aux captures du jeu original (§4bis).
