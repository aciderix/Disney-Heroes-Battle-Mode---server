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
| **APK Disney Heroes** (base, ~96 Mo, v12.1.0) | Google Drive : `https://drive.google.com/file/d/1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF/view` — **non committé** (voir [`docs/ASSETS.md`](docs/ASSETS.md)) |
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
- **ServerType** : `com.perblue.heroes.ServerType` (gameHost/gamePort/contentLocation).
  LIVE contenu = `http://content.disneyheroesgame.com/live/index.txt`. Login =
  `login.disneyheroesgame.com` (+ staging). Adresses de jeu **à extraire** (décompilation).
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

> **RISQUE OUVERT #1 — cohérence version APK ↔ assets archivés.** L'APK est en `12.1.0`,
> mais `index.txt` annonce `GameVersion 7.8.1 / Revision 325-326`. À **vérifier** : la
> révision de contenu que cet APK exige (à extraire de l'`AssetUpdater`) correspond-elle
> aux assets archivés ? Sinon, trouver l'APK correspondant OU les assets de la bonne
> révision. **Ne pas rustiner** le contrôle de révision. (Voir JOURNAL 2026-07-09.)

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
tools/
  extract_game_data.sh    <- extrait stats/strings de l'APK vers game-data/ (source de vérité)
game-data/
  stats/*.tab             <- 274 tables d'équilibrage (extraites de l'APK)
  strings/**/*.properties <- 325 fichiers de textes localisés (extraits de l'APK)
server/                   <- (à venir) serveur Java réutilisant les classes du jeu
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

### À faire (ordre conseillé)
1. [ ] **Décompiler l'APK** (dex2jar/jadx) → `libs/game-remapped.jar` régénérable par
   script ; extraire la **clé XOR** (`DHXORConnectionWrapper`) et les valeurs `ServerType`
   (gameHost/gamePort). Documenter dans PROTOCOL.md.
2. [ ] **Résoudre le RISQUE #1** : révision de contenu exigée par l'APK vs assets archivés.
3. [ ] **Serveur de contenu v0** : servir `index.txt` + rediriger les archives vers
   archive.org / copie locale (débloquer l'AssetUpdater sans toucher au jeu).
4. [ ] **Backend desktop** (LWJGL3) minimal pour lancer le jeu (miroir `dsbackend/`).
5. [ ] **Serveur login v1** : `ClientInfo1` → `BootData1` via les classes du jeu.
6. [ ] **Persistance** (SQLite) + **multi-serveur** (liste, mot de passe).
7. [ ] **Outil d'extraction data → format serveur** (les `.tab` chargés tels quels).

---

## 8. Conventions

- Commits/pushes **réguliers** sur `claude/disney-heroes-port-rhhtuj` (le conteneur peut
  être reset — ne jamais perdre de travail). Artefacts lourds **régénérables par script**.
- **Ne jamais** faire apparaître l'identifiant du modèle dans un commit/artefact.
- Toute décision technique se conforme à `docs/PRINCIPLES.md`.
- À chaque étape : mettre à jour **JOURNAL.md** (détaillé) et l'historique court + « État
  courant » de **MEMORY.md**.
