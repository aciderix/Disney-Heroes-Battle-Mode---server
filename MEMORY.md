# MEMORY — Disney Heroes: Battle Mode — Portage Desktop + Serveur

> **Document de récupération de contexte.** À lire en premier par n'importe quel
> agent reprenant le projet (notamment après compression de contexte ou reset du
> conteneur). Il contient l'état courant, l'architecture, les liens, la hiérarchie
> des fichiers et un historique **court**. L'historique **détaillé** est dans
> [`JOURNAL.md`](JOURNAL.md). **Maintenir ce fichier à jour en permanence.**

Dernière mise à jour : **2026-07-09** (bootstrap du projet).

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
   Stats `.tab` chargées via `DhStatFileExt`. Contenu fourni par `tools/fetch_assets.sh`
   (archive.org). **Bloqué sur** : `DhNet` (login, #NET) + `SystemClock.elapsedRealtimeNanos`
   absent des stubs Android (#ANDROIDSTUBS). Détail + deferrals : `desktop-port/BACKEND_STATUS.md`.
   ← PROCHAINE ÉTAPE : #ANDROIDSTUBS puis #NET + serveurs → franchir le login.
7. [ ] **Persistance** (SQLite) + **passerelle/multi-serveur** (liste, mot de passe).
8. [ ] **Outil d'extraction data → format serveur** (les `.tab` chargés tels quels).

---

## 8. Conventions

- Commits/pushes **réguliers** sur `claude/disney-heroes-port-rhhtuj` (le conteneur peut
  être reset — ne jamais perdre de travail). Artefacts lourds **régénérables par script**.
- **Ne jamais** faire apparaître l'identifiant du modèle dans un commit/artefact.
- Toute décision technique se conforme à `docs/PRINCIPLES.md`.
- À chaque étape : mettre à jour **JOURNAL.md** (détaillé) et l'historique court + « État
  courant » de **MEMORY.md**.
