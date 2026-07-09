# JOURNAL — journal détaillé des modifications

> Journal **détaillé** relié à l'historique court de [`MEMORY.md`](MEMORY.md#7-état-courant--prochaines-étapes).
> But : permettre à n'importe quel agent de **retrouver facilement n'importe quelle
> information** (décision, découverte, commande, fichier). Mis à jour **à chaque étape**.
> Ordre : le plus récent en haut. Chaque entrée = date + résumé + détails + fichiers touchés.

---

## 2026-07-09 — Bootstrap du projet (session initiale)

### Résumé
Mise en place complète des fondations : étude du projet de référence DragonSoul,
récupération et reconnaissance de l'APK Disney Heroes, extraction des données de jeu,
et création du système de mémoire (MEMORY.md + JOURNAL.md) et de la documentation.

### Détails chronologiques

**1. Étude du dépôt de référence DragonSoul.**
- Ajouté `aciderix/dragonsoul-web` à la session et cloné dans `/workspace/dragonsoul-web`.
- La branche par défaut `main` n'a pas `desktop-port/` ; récupéré la branche
  `claude/game-transpile-debug-2p5irx` (`git fetch --depth 1 origin <branch>` → `FETCH_HEAD`).
- Lu les docs clés de `desktop-port/` : `PRINCIPLES.md`, `SERVER_DESIGN.md`, `PROTOCOL.md`,
  `PROGRESS.md`, `STARTING_STATE.md`, `SHIMS.md`.
- **Architecture DragonSoul comprise** : backend desktop LWJGL3 maison (`dsbackend/`)
  remplaçant la couche plateforme Android ; jeu (bytecode) réutilisé tel quel (seul
  traitement autorisé = remap non-sémantique des collisions de noms) ; serveur Java
  (`server/Ds*.java`) réutilisant les classes du jeu (`ServerXORConnectionWrapper`,
  `MessageFactory`, `BootData`) pour une sérialisation identique. Login en 2 étapes
  (POST /login HTTP puis TCP jeu). Codec = Deflate + XOR roulant, clé fixe 8 octets.
  Multi-serveur (passerelle locale, mot de passe HMAC hors protocole, découverte
  Direct/LAN/communautaire, persistance SQLite).

**2. Récupération de l'APK Disney Heroes.**
- Téléchargé depuis Google Drive (`id=1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF`) via le
  contournement de la page « Virus scan warning » (form → `drive.usercontent.google.com`
  avec `confirm=t&uuid=...`). Résultat : `disney-heroes.apk`, **96 Mo**, APK Android valide.
- Contenu : 6 `classes*.dex` (base APK d'un App Bundle → **pas de `.so` natifs** ici,
  ils sont dans les splits par ABI). `assets/{stats,strings,automation,shaders,sound,fonts…}`.
- `assets/info.txt` : `version_name 12.1.0`, `git_commit a53845c9`, build 2023-02-22,
  `env release`. `assets/api_key.txt` (JWT Amazon) → `pkg com.perblue.herocities`.

**3. Reconnaissance du bytecode (strings sur les .dex).**
- **Confirmé : même stack réseau que DragonSoul, mais NON obfusquée.** Classes en clair :
  `com.perblue.heroes.network.messages.MessageFactory`, `...network.messages.*` (ClientInfo1,
  BootData1, ArenaAttack, …), `...network.{XORConnectionWrapper,DHXORConnectionWrapper,
  NetworkProvider,GameServerAddress}`, `com.perblue.common.network.{XORConnectionWrapper,
  XORCipher,XORInputStream,DeflateConnectionWrapper,StackedConnectionWrapper}`,
  `com.perblue.grunt.translate.{GruntMessageFactory,ConnectionWrapper,DummyConnectionWrapper}`.
- **AssetUpdater** identique (gate `MISSING_ADDITIONAL`, `WORLD_ADDITIONAL`, `UI_DYNAMIC`,
  log « Failed to load %s, setting MISSING_ADDITIONAL flag », « loadDynamicUI setting
  MISSING_ADDITIONAL true for »). Contenu LIVE : `http://content.disneyheroesgame.com/live/index.txt`.
- **ServerType** = `com.perblue.heroes.ServerType` (getters `gameHost`, `getGameHost`,
  `contentLocation`, `getContentLocation`). Login : `login.disneyheroesgame.com`
  (+ `login.staging.disneyheroesgame.com`, `dhstaging...:10070/content/beta/index.txt`).
- **PIÈGE identifié** : les classes `gateway/v1/*` (AdRequest, ClientInfoOuterClass,
  InitializationCompletedEventRequest, DiagnosticEvent, DeveloperConsent…) sont le **SDK
  Unity Ads**, PAS le protocole du jeu. Le protocole du jeu reste le binaire
  `MessageFactory` (FULL_NAME façon `BootData1`), comme DragonSoul.

**4. Extraction des données du jeu (source de vérité).**
- Écrit `tools/extract_game_data.sh` : extrait `assets/stats/*` et `assets/strings/*` de
  l'APK vers `game-data/` (principe §4 : aucune donnée recopiée à la main).
- Résultat committé : `game-data/stats/` = **274 `.tab`** (TSV ; ex. `arena_constants.tab`
  = clé + colonnes `FIGHT_PIT_VALUE/COLISEUM_VALUE`), `game-data/strings/` = **325
  `.properties`** (locales incluses). Ces fichiers se chargent **tels quels** côté serveur.

**5. Mémoire projet & docs.**
- Créé `MEMORY.md` (récupération de contexte, tenu à jour) et `JOURNAL.md` (ce fichier).
- Créé `docs/PRINCIPLES.md`, `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`, `docs/ASSETS.md`,
  `docs/RECON.md`. Créé `.gitignore` (APK/dex/jars/zip non committés — régénérables).
- Conservé en l'état l'infra d'upload archive.org existante (`upload_batch.py`,
  `disney_heroes_live_index.txt`, `index.txt`, workflow) — non déplacée pour ne pas
  casser le workflow.

### Découvertes importantes / risques
- **RISQUE #1 (ouvert)** : incohérence potentielle version APK (12.1.0) vs `index.txt`
  (GameVersion 7.8.1, Revision 325-326). À vérifier : révision de contenu exigée par cet
  APK (extraire de l'AssetUpdater) ↔ assets archivés. Ne pas rustiner le contrôle.
- Avantage majeur vs DragonSoul : **noms non obfusqués** → reverse et réutilisation des
  classes bien plus simples. Et **assets + index.txt disponibles** (archive.org).

### Fichiers ajoutés/modifiés
- `+ MEMORY.md`, `+ JOURNAL.md`, `+ .gitignore`, `+ README.md`
- `+ docs/{PRINCIPLES,ARCHITECTURE,PROTOCOL,ASSETS,RECON}.md`
- `+ tools/extract_game_data.sh`
- `+ game-data/stats/*.tab` (274), `+ game-data/strings/**/*.properties` (325)

**6. Décompilation ciblée (androguard).**
- dex2jar/jadx **indisponibles** : téléchargements GitHub Releases bloqués par le proxy
  (403 « GitHub access to this repository is not enabled »). Contournement : `pip install
  'androguard<4'` (pypi autorisé ; `mutf8` compile via gcc présent).
- Localisé les classes cibles dans `classes4.dex` puis désassemblé (`DalvikVMFormat`) :
  - **`DHXORConnectionWrapper`** : champ statique `KEY` = 8 octets
    `CE 85 D4 F9 29 A8 24 56` ; ctor = `StackedConnectionWrapper(DeflateConnectionWrapper,
    XORConnectionWrapper2(KEY))` ⇒ codec **Deflate + XOR(KEY)** (identique à DragonSoul en
    conception, clé propre à Disney Heroes).
  - **`ServerType`** (ctor `(name, ordinal, protocol, loginHost, port, contentLocation)`) :
    LIVE = `https://` / `login.disneyheroesgame.com` / `443` / contenu
    `http://content.disneyheroesgame.com/live/index.txt`. STAGING, LOCAL (`localhost:8080`),
    NONE/TRUNK/DEV relevés aussi. **APK NON patché** (vrais domaines PerBlue) → redirection
    prévue par réécriture `ServerType` (réflexion) ou passerelle, sans patch bytecode.
- Cherché une **révision de contenu embarquée** (RISK #1) : aucun constant évident ; le gate
  repose vraisemblablement sur des **marqueurs de catégorie** (fichiers repères) comme
  DragonSoul → à confirmer en décompilant `AssetUpdater`. RISK #1 reste ouvert.
- Docs mises à jour : `PROTOCOL.md` (§0, §1.1), `RECON.md`, `MEMORY.md` §3/§7.

**7. Décompilation de l'`AssetUpdater` → RISQUE #1 RÉSOLU.**
Package `com.perblue.heroes.assets_external` : `ExternalAssetManager` (orchestre),
`AssetIndexDownloader` (parse/décide), `ArchiveInfo`, `ContentServerKeys`, `AssetCategory`.
- **`retainRowsForVersion(rows, gameVersion)`** : retire les lignes dont `GameVersion` >
  version du client (`client.compareTo(new VersionNumber(row.GameVersion)) < 0`). ⇒ ne
  bloque que le contenu **futur** ; garde l'égal/plus ancien.
- **`checkArchives`** (par catégorie `shouldDownload()`) : décide **uniquement sur la
  révision** — `getMostRecentCompleteArchive` (Mode==COMPLETE & Category, rev max),
  `getLatestDownloadedRevision` (prefs), `getNeededIncrementalArchives`. Aucun test de
  GameVersion. Logs : « complete download: rev N », « incremental download », « up-to-date! »,
  « no prior complete archive ». Garde-fou `handleBootLoop`.
- **Filtrage device** (`lambda$onComplete$0`) : ne retient que `Environment==LIVE` +
  `Density`/`Compression` du device (SON/TEXT/PNG traités à part). ⇒ servir l'index d'origine
  **tel quel**, le client sélectionne ses lignes.
- **Conclusion RISQUE #1** : APK 12.1.0 > index 7.8.1/7.9 → **toutes les lignes retenues** ;
  install neuve → télécharge `COMPLETE rev 325` puis `INCREMENTAL rev 326`. **L'APK accepte
  les assets archivés.** Le libellé GameVersion de l'index n'est pas un critère de rejet.
  Risque résiduel = complétude **runtime** (à constater en exécutant). Docs : `ASSETS.md`
  (algorithme complet), `MEMORY.md` §7.

**8. Serveur de contenu v0 (`server/content_server.py`).**
- Python stdlib (zéro dépendance → hébergeable partout). Endpoints :
  - `GET /live/index.txt` : sert le manifeste avec les **URLs d'archives réécrites** vers
    ce serveur (`/live/<nom>.zip`) — le jeu filtre lui-même par device/version.
  - `GET|HEAD /live/<nom>.zip` : sert une **copie locale** (`--cache assets-cache/`) si
    présente, sinon **302** vers l'archive publique (archive.org).
  - `GET /health`.
- Config via options/env : `--port/--host/--index/--cache/--archive-base/--rewrite-host`.
- Ajouté `server/run-content-server.sh` + `server/README.md`.
- **Vérifié de bout en bout** (port 8899) : index réécrit (URLs → 127.0.0.1:8899) ; requête
  `.zip` → 302 → archive.org → 200, `Content-Length` 4422179 = colonne `Size` de l'index.
  Confirmé aussi que l'archive.org du projet renvoie bien les fichiers (HEAD 200, tailles OK).

### Point de reprise
Serveur de contenu v0 opérationnel. **Prochaine étape** : décompiler l'APK en jar
régénérable (`libs/game-remapped.jar`) pour réutiliser les classes du jeu côté serveur
(codec `DHXORConnectionWrapper`, `MessageFactory`, `BootData`), puis backend desktop minimal
+ réécriture `ServerType.LIVE` (réflexion) vers notre serveur de contenu. Voir MEMORY.md §7.
