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

### Point de reprise
Prochaine étape : décompiler l'APK (dex2jar/jadx) pour extraire la clé XOR et les
adresses `ServerType`, puis résoudre le RISQUE #1, puis serveur de contenu v0.
Voir MEMORY.md §7.
