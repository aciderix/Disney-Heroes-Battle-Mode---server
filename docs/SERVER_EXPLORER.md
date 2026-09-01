# SERVER EXPLORER — explorateur de serveurs + patch APK (EXPLORATION / faisabilité)

> **Statut : EXPLORATION** (demande util. « pour l'instant explorer comment faire »). Aucune implémentation ici — que des
> FAITS relevés dans le bytecode du jeu (`libs/game.jar`, APK 12.1.0) + le point sur ce qui existe déjà, et les options
> chiffrées par effort/risque. À valider avant d'engager un incrément.

## État d'avancement (annuaire communautaire)

Décision util. : un **annuaire de serveurs communautaires** — le launcher PC (puis l'APK patché) affiche la liste des
serveurs dispos + infos, on choisit → **Jouer**. Support retenu : **table Supabase** (inscription signée + lecture ;
keep-alive par GitHub Action pour éviter la mise en pause du projet gratuit). Sécurité = **signature Ed25519** de chaque
fiche (le launcher vérifie sans faire confiance à Supabase). Tout **paramétrable dans le launcher** (opt-in publication +
nom/mode côté Héberger ; navigateur côté Serveurs ; adresse d'annuaire modifiable côté Réglages).

| Brique | Contenu | Statut |
|---|---|---|
| **1** | Identité serveur **signée** + `GET /info` vérifiable (`ServerIdentity`/`ServerInfo`/`AuthService /info`/`ServerInfoVerifier`) | 🟢 **FAIT** (g222, `ServerInfoTest` 10/10, régression 177/177) — vérif stack réelle à la brique 3 |
| **2** | Table **Supabase** : inscription signée par le serveur + lecture + **keep-alive** GitHub Action | 🟢 **FAIT** (g223, prouvé LIVE `DirectoryProbe` : register 200 / read 200 / faux 401) |
| **3** | **Navigateur de serveurs** dans le launcher PC (lit l'annuaire, vérifie la signature, ping, Jouer) | 🟢 **FAIT** (g224 : daemon `/directory` + `/directory/verify` ; front panneau communautaire ; E2E 10/10 + chemin LIVE prouvé) |
| **4a** | **Patch APK — redirection + re-signature** (`tools/patch_apk.sh`) | 🟢 **FAIT** (g226, PROUVÉ sur 12.1.0 : LIVE→serveur choisi, signé v2/v3) |
| **4b** | Câbler le patch APK dans le launcher (cible « apk » de `BuildManager` + écran Générer) | 🟢 **FAIT** (g227 : `/build/start target=apk`, écran Générer host/port, prouvé via `ApkBuildProbe`) |
| **4c-1** | Fondations de l'écran in-app (Activity picker + `ServerType.setLive` + toolchain) | 🟢 **PROUVÉ** (g228 : picker compile→dex, setLive réassemble, apktool round-trip) |
| **4c-2** | Orchestration : hook boot + manifeste LAUNCHER + repackage + re-signe | ⬜ suivant |
| indép. | Brancher le `GetServers` natif = sélecteur de **SHARD** in-game (Niveau 2) | ⬜ optionnel |

## Annuaire Supabase — schéma technique (brique 2, LIVRÉ)

- **Projet** : Supabase « Dhbm » (org perso). **Table `public.servers`** — clé `pub_key` (clé publique Ed25519 base64url) ;
  colonnes `name, mode, game_version, server_version, address` (host:port de connexion), `info_url` (base du `/info`),
  `online, max_online, open_time, issued_at, signature, created_at, updated_at`.
- **RLS** : `SELECT` public (clé anon) ; **aucune** policy d'écriture → écriture directe impossible.
- **Edge Function `register-server`** (Deno, `verify_jwt=false`) : SEUL écrivain. Vérifie la **signature Ed25519** de la
  charge (chaîne canonique `REG1␟pubKey␟name␟mode␟gameVersion␟serverVersion␟address␟infoUrl␟online␟maxOnline␟openTime␟issuedAt`,
  US 0x1F) + fraîcheur `issuedAt` (±5 min), puis **upsert** (service role). Charge invalide → 400/401.
- **Côté serveur** (`dhserver.directory.ServerRegistration`, game-free) : `register()` signe la charge et la POST. Publication
  **opt-in** dans `LoginServer.main` : `dh.server.publish=1` (ou `DH_SERVER_PUBLISH=1`) + `DH_DIRECTORY_URL` +
  `DH_DIRECTORY_ANON_KEY` (clé **publique**) + `DH_SERVER_ADDRESS` + `DH_SERVER_INFO_URL` → thread démon inscrit/rafraîchit
  (`dh.directory.refresh.minutes`, défaut 10).
- **Lecture** (launcher/APK, brique 3-4) : `GET <url>/rest/v1/servers?select=*&order=updated_at.desc` avec l'en-tête
  `apikey: <clé anon>`. Puis **re-vérifier chaque fiche** via `/info` (brique 1) avant de l'afficher (ne jamais faire
  confiance à la table seule).
- **Keep-alive** : `.github/workflows/directory-keepalive.yml` (secrets `SUPABASE_URL` + `SUPABASE_ANON_KEY`), tous les 3 j.
- **Packaging (brique 3)** : le launcher distribué embarque un fichier **`directory.env`** (URL + clé anon PUBLIQUES) écrit
  par `build_launcher.sh` au build depuis `DH_DIRECTORY_URL`/`DH_DIRECTORY_ANON_KEY` (fournis par `launcher-release.yml` via
  les secrets `SUPABASE_URL`/`SUPABASE_ANON_KEY`). Les `run-launcher.sh/.bat` le chargent au démarrage (une variable
  d'environnement déjà posée a la priorité → l'utilisateur peut pointer sur SON propre annuaire). Absent → annuaire non
  préconfiguré (gating honnête dans l'UI). Jamais de `service_role` ici.
- **Secrets** : `service_role` et la chaîne de connexion directe NE sont JAMAIS committées ni exposées ; le runtime n'utilise
  que l'URL projet + la clé **anon/publishable** (publiques par conception). Le setup DB a été fait via le connecteur MCP.

## Écran de sélection in-app (brique 4c) — architecture retenue + état

**But** : au lancement de l'appli mobile, afficher D'ABORD un écran de choix de serveur (liste de l'annuaire + saisie
manuelle) ; « Jouer » lance le jeu sur le serveur choisi. Le contenu se télécharge ensuite via l'`index.txt` du serveur
retenu (`ServerType.contentLocation`, servi par `content_server.py` — même mécanisme que le desktop).

**Architecture (Option C, la plus tractable)** — une Activity Android SÉPARÉE en amont, pas de refonte de l'UI libGDX :
1. **`mobile/DhServerPicker.java`** (nouvelle Activity, UI programmatique) : lit l'annuaire (REST Supabase, clé anon
   PUBLIQUE injectée au patch) → liste de boutons + saisie manuelle ; au choix, écrit `host`/`port` dans les
   `SharedPreferences` « dhserver » puis démarre `com.perblue.heroes.android.AndroidLauncher` (le jeu).
2. **`ServerType.setLive(host, port)`** (méthode statique ajoutée en smali) : pose `gameHost="http://"+host`,
   `gamePort=port`, `contentLocation="http://host:port/live/index.txt"` sur `LIVE`.
3. **Hook boot** (smali, dans `AndroidLauncher.onCreate`, après `super.onCreate`) : lit les prefs « dhserver » ; si
   présentes → `ServerType.setLive(host, port)` AVANT que le jeu ne lise son serveur.
4. **Manifeste** : `DhServerPicker` devient le LAUNCHER (`MAIN`/`LAUNCHER`) ; on retire cet intent d'`AndroidLauncher`
   (qui reste démarrable par le picker). `INTERNET` déjà accordé.
5. **Repackage** : dex du picker (`d8`) + dex jeu patchés (baksmali/smali) + manifeste édité (apktool) → **re-signe**.

**Toolchain (téléchargée, gitignorée `libs/apktools/`, §7)** : apktool (manifeste, round-trip **prouvé** sur 12.1.0) +
**r8/d8 8.3.37** + **android.jar** (API 33) pour compiler l'Activity + baksmali/smali + uber-apk-signer.

**État 4c-1 (g228) — fondations PROUVÉES** : `DhServerPicker` **compile** (javac+android.jar) et **dexe** (d8) ;
`ServerType.setLive` **réassemble** (smali) ; **apktool rebuild** de l'APK OK. **Reste 4c-2** : le hook `AndroidLauncher`
+ l'édition du manifeste + l'orchestration `tools/apk_inject_picker.sh` (repackage + re-signe) + vérif SUR APPAREIL.

## 0. Ce que veut l'utilisateur

Au lancement du jeu : voir les **serveurs disponibles**, en **choisir un**, appuyer sur **Jouer** → le jeu démarre
**connecté au serveur choisi**. Côté **desktop** (déjà porté) ET côté **mobile** (APK, à patcher).

## 1. FAIT CLÉ — comment le client choisit son serveur (bytecode)

Le client résout son serveur via **une seule enum** : `com.perblue.heroes.ServerType`. Chaque valeur porte 2 champs privés :

| champ | rôle |
|---|---|
| `gameHost` (String) + `gamePort` (int) | hôte HTTP de **login** : le client fait `POST {gameHost}:{gamePort}/login` |
| `contentLocation` (String) | URL de l'**index.txt** de contenu (assets, données) |

Valeurs relevées (`javap -c ServerType`) :

| ServerType | gameHost | contentLocation |
|---|---|---|
| **LIVE** (défaut RELEASE) | `https://login.disneyheroesgame.com` | `http://content.disneyheroesgame.com/live/index.txt` |
| STAGING | `https://login.staging.disneyheroesgame.com` | `http://dhstaging…:10070/content/beta/index.txt` |
| LOCAL | `http://localhost` | *(vide)* |
| DEV / TRUNK / NONE | *(vide)* | *(vide)* |

`BuildOptions.SERVER_TYPE = ServerType.LIVE` en dur (clinit, build RELEASE). **La réponse JSON du `/login`** renvoie
l'adresse du **serveur de JEU (TCP)** dans `"data":"host:port"` — donc changer `gameHost` suffit à rediriger TOUT (login
HTTP → puis le serveur de jeu est celui que NOTRE `/login` annonce).

⇒ **Rediriger le client = écraser `ServerType.LIVE.gameHost/gamePort/contentLocation`.** Rien d'autre à toucher.

### Consommateurs de ces champs (pour info)
`GameMain` (connexion login), `AssetIndexDownloader` / `ExternalAssetManager` / `AssetCategoryUpdater` (téléchargement de
contenu). Tous lisent `getGameHost()` / `getContentLocation()` — **au runtime**, donc une valeur écrasée AVANT le login est
prise en compte.

## 2. DEUX niveaux de « serveur » — ne pas les confondre

- **Niveau 1 — le SERVEUR PHYSIQUE** (un hôte : notre serveur, celui d'un ami auto-hébergé, un cloud…). C'est `ServerType`.
  **Le jeu n'a AUCUN sélecteur natif pour ça** (un seul serveur officiel à l'origine). → C'est le rôle de **notre launcher**
  (desktop) ou du **patch APK** (mobile).
- **Niveau 2 — le SHARD** (monde/instance À L'INTÉRIEUR d'un serveur). **Le jeu a un sélecteur NATIF** : `ShardsWindow`
  envoie `GetServers` → reçoit `Servers{List<Server>}` où chaque `Server` = `{shardID, descriptionKey, full, haveAccount,
  maxTeamLevel, numChaptersAvailable, icon, maxRarity, openTime, serverUnavailable}`. Notre serveur est multi-shard (§5) →
  on peut **répondre à `GetServers`** et le **`ShardsWindow` du jeu affiche la liste** (aucune UI à écrire).
  **⚠️ Non câblé aujourd'hui** : `LoginServer` ne gère pas encore `GetServers` (grep = 0 handler).

L'explorateur « complet » = **Niveau 1** (launcher/patch : choisir l'hôte) **puis** **Niveau 2** (in-game natif : choisir le
shard sur cet hôte). Les deux sont indépendants et livrables séparément.

## 3. DESKTOP — déjà à 90 %

Le porté desktop **fait déjà** la redirection Niveau 1, **sans patch bytecode**, par **réflexion** :
`DesktopLauncher.maybeRedirectServer(host:port)` (lu depuis `-Ddh.server` / env `DH_SERVER`) écrase par réflexion
`ServerType.LIVE.gameHost = http://host`, `gamePort = port`, `contentLocation = http://host:port/live/index.txt`
(`desktop-port/.../DesktopLauncher.java:815`). Et le **launcher** l'alimente déjà : `PlayManager` pose `DH_SERVER=host:port`
au lancement (`PlayManager.java:55`), et l'écran **Serveurs** du front gère list/add/remove/ping (`/servers`).

⇒ **Desktop : l'explorateur EXISTE en pièces détachées.** Reste (petit incrément UX, pas de nouveau mécanisme) : un écran
« au lancement, choisis un serveur dans la liste → Jouer » qui enchaîne `Serveurs (pick)` → `PlayManager(serverChoisi)`.
Effort **faible**, risque **nul** (réflexion déjà prouvée EN JEU, cf. g191/g199).

## 4. MOBILE (APK) — options de patch

Sur Android il n'y a **pas** de wrapper JVM où faire la réflexion : le dex s'exécute tel quel. Il faut donc **modifier l'APK**.
L'APK 12.1.0 est **multidex** (`classes.dex`…`classes6.dex`) ; `ServerType` est dans l'un d'eux ; natifs `lib/arm*`.

**Chaîne d'outils nécessaire (NON installée ici — seuls `java`+`keytool` présents)** : `apktool` (dé/recompile smali),
`zipalign` + `apksigner` (Android build-tools) pour ré-aligner et **re-signer** (un APK modifié DOIT être re-signé ; la
signature d'origine casse → perte de Play Integrity, **acceptable pour un serveur privé**, le joueur installe hors Store).

### Option A — redirection FIXE (le plus simple, ~1 j)
Patch smali de `ServerType.<clinit>` : remplacer les 3 constantes de `LIVE` par NOTRE hôte (ex.
`gameHost="https://mon-serveur"`, `content=".../live/index.txt"`). Rebuild + re-sign. **Résultat** : l'APK se connecte
**toujours** à ce serveur. Pas de choix, mais trivial et robuste. Bon pour « un APK dédié à mon serveur ».

### Option B — redirection CONFIGURABLE par fichier (~2-3 j)
Patch : au boot, lire un fichier (ex. `/sdcard/dhserver.txt` ou un `assets/servers.json` embarqué) et écraser les champs
`ServerType.LIVE` (setter injecté en smali, ou champ rendu non-final). Le joueur change de serveur en éditant le fichier /
en choisissant une entrée. Pas d'UI custom (ou une mini-liste). **Compromis** effort/souplesse.

### Option C — explorateur IN-APP au lancement (~1-2 sem)
Injecter un **écran de sélection** avant le login (ou détourner un écran existant) listant des serveurs (depuis un
**annuaire** HTTP qu'on héberge, ou saisis manuellement) → au choix, écrire `ServerType.LIVE.*` puis lancer le login normal.
C'est l'équivalent mobile de notre launcher desktop. Le plus proche de la vision « au lancement je vois les serveurs », mais
c'est du **dev Android smali** (le plus lourd/risqué).

### Recommandation mobile
**A d'abord** (débloque « jouer sur mon serveur depuis le tel », prouve la chaîne patch+resign EN JEU), **puis** B si un
choix simple suffit, C seulement si on veut vraiment l'explorateur riche intégré. Dans TOUS les cas : le **Niveau 2**
(shard picker natif via `GetServers`) marche dès qu'on câble le handler serveur — indépendant du patch.

## 5. Increments proposés (à valider — RIEN n'est engagé)

| # | Incrément | Niveau | Effort | Dépend de |
|---|---|---|---|---|
| E1 | **Handler `GetServers` serveur** → `Servers{Server(shardID…)}` (le `ShardsWindow` natif affiche nos shards) + vérif EN JEU | 2 | faible | rien (multi-shard existe) |
| E2 | **Desktop : écran « choisir un serveur au lancement »** (enchaîne Serveurs→Play) | 1 | faible | launcher (fait) |
| E3 | **APK Option A** : patch redirection fixe + re-sign + **vérif EN JEU** (vrai APK Android → notre serveur) | 1 | moyen | toolchain apktool/apksigner |
| E4 | APK Option B/C (config/explorateur in-app) | 1 | élevé | E3 |

## 6. Points ouverts / à décider avec l'utilisateur
- **« Serveurs disponibles » = quoi ?** Des serveurs PHYSIQUES (annuaire d'hôtes, façon liste de serveurs Minecraft) ou des
  SHARDS de NOTRE serveur ? (Change tout : Niveau 1 annuaire vs Niveau 2 natif.)
- **Annuaire** : si liste de serveurs physiques, où vit-elle ? (fichier local éditable / petit service d'annuaire qu'on
  héberge / saisie manuelle IP\:port comme le launcher desktop actuel).
- **Mobile** : accepte-t-on la **re-signature** (installation hors Play Store, side-load) ? (obligatoire pour tout APK patché).
- **Légal** : un APK patché redistribué embarque le jeu → **distribution = le joueur fournit/patch SON APK** (comme pour le
  serveur), on ne redistribue pas les binaires du jeu (cf. PRINCIPLES §7, modèle déjà retenu pour le launcher).

## 7. Résumé exécutable
- Rediriger le client = **écraser `ServerType.LIVE.gameHost/gamePort/contentLocation`** (fait maison desktop par réflexion,
  APK par patch smali). Le `/login` fait le reste (il annonce le serveur de jeu TCP).
- **Desktop : quasi fait** (réflexion + launcher) → E2 = petit UX.
- **Mobile : à patcher** (A→B→C par effort croissant) → E3 = 1ère brique, exige apktool + apksigner + re-sign.
- **Shard picker natif** (`GetServers`/`ShardsWindow`) = **gratuit côté UI**, il suffit d'un handler serveur (E1).
