# RECON — reconnaissance de l'APK Disney Heroes

Findings bruts issus de l'analyse de l'APK (v12.1.0). Source de vérité = le jeu ;
ce document consigne ce qui a été **observé**, pas supposé. Méthode de cette 1ʳᵉ passe :
`strings` sur les `.dex` (pas encore de décompilation complète).

## Identité de l'APK
- Fichier : `disney-heroes.apk`, ~96 Mo (base APK d'un App Bundle → pas de `.so` dans la base).
- `assets/info.txt` : `name: Singular-v12.1.0-a53845c9.master`, `env: release`,
  `git_commit: a53845c9`, `git_branch: master`, `version_name: 12.1.0`, `version_code: 1`,
  `build_date: Wed, 22 Feb 2023 13:31:59 +0200`.
- `assets/api_key.txt` : JWT Amazon Appstore → `"pkg":"com.perblue.herocities"` (nom de
  code interne *heroCities*).
- DEX : `classes.dex` … `classes6.dex` (+ `assets/audience_network.dex` = SDK Meta/Audience Network).

## Packages
- **Logique du jeu** : `com.perblue.heroes.*` (analogue à `com.perblue.rpg.*` de DragonSoul).
- **Couche Android** : `com.perblue.dragonsoul.android.*` (réutilisée du studio ; ex.
  `RPGFirebaseMessagingService`, `ErrorReporting`).
- **Commun / framework** : `com.perblue.common.*`, `com.perblue.grunt.*`.

## Réseau (protocole du jeu) — NON obfusqué
Classes observées (en clair) :
```
com.perblue.heroes.network.messages.MessageFactory        # registre des messages
com.perblue.heroes.network.messages.*                      # ClientInfo1, BootData1, ArenaAttack, ... (des centaines)
com.perblue.heroes.network.XORConnectionWrapper
com.perblue.heroes.network.DHXORConnectionWrapper          # "DH" = Disney Heroes ; porte la clé XOR
com.perblue.heroes.network.NetworkProvider                 # connect / sendMessage / adresse
com.perblue.heroes.network.GameServerAddress
com.perblue.heroes.network.NetworkLog
com.perblue.common.network.XORConnectionWrapper / XORConnectionWrapper2
com.perblue.common.network.XORCipher / XORInputStream / XOROutputStream
com.perblue.common.network.DeflateConnectionWrapper / StackedConnectionWrapper
com.perblue.grunt.translate.GruntMessageFactory
com.perblue.grunt.translate.ConnectionWrapper / DummyConnectionWrapper
```
⇒ **Même conception que DragonSoul** (framing longueur + codec XOR+Deflate + MessageFactory),
mais lisible directement. FULL_NAME façon `ClientInfo1` / `BootData1` (nom + version).
Log observé : « Beginning GameMain handleBootData » (le client attend `BootData` du serveur).

Débogage intéressant : `com.perblue.heroes.ui.windows.debug.SaveRestoreUserWindow` avec
`XORInputStream`/`XOROutputStream` internes → mécanisme de **sauvegarde/restauration d'un
utilisateur** intégré au jeu (utile pour la persistance).

## ServerType & endpoints
```
com.perblue.heroes.ServerType   (getters: gameHost/getGameHost, contentLocation/getContentLocation, ...)
Contenu LIVE : http://content.disneyheroesgame.com/live/index.txt
Login        : login.disneyheroesgame.com  (staging: login.staging.disneyheroesgame.com)
Staging beta : http://dhstaging.disneyheroesgame.com:10070/content/beta/index.txt
Server status: http://serverstatus.perblue.com/dhServerDown_
Regex hôtes  : ^https?://([a-z]+\.)?(perblue.com|disneyheroesgame.com)/?(.*)$
```
### ✅ Décompilé (androguard, `classes4.dex`)
- **Clé XOR** — `DHXORConnectionWrapper.KEY` (8 octets) :
  `CE 85 D4 F9 29 A8 24 56` (signés : `{-50,-123,-44,-7,41,-88,36,86}`).
  Codec = `StackedConnectionWrapper(DeflateConnectionWrapper, XORConnectionWrapper2(KEY))`.
- **`ServerType`** (ctor `(name, ordinal, protocol, loginHost, port, contentLocation)`) :
  - LIVE : `https://` `login.disneyheroesgame.com` `:443` — contenu
    `http://content.disneyheroesgame.com/live/index.txt`.
  - STAGING : `https://` `login.staging.disneyheroesgame.com` `:443` — contenu
    `http://dhstaging.disneyheroesgame.com:10070/content/beta/index.txt`.
  - LOCAL : `http://` `localhost` `:8080`. NONE/TRUNK/DEV : vides.
  - **APK NON patché** (LIVE = vrais domaines PerBlue) ; pas d'IP loopback. Le serveur de
    jeu TCP n'est pas dans `ServerType` → vient de la réponse de login (2 étapes).

### AssetUpdater (`com.perblue.heroes.assets_external`) — décompilé
- Décision de téléchargement **basée révision** (`checkArchives` → `getMostRecentCompleteArchive`
  / `getNeededIncrementalArchives`), **pas** sur `GameVersion`. `retainRowsForVersion` ne retire
  que le contenu *plus récent* que le client. Filtrage device via `Environment`/`Density`/
  `Compression`. ⇒ **RISK #1 résolu** (APK 12.1.0 accepte l'index/assets rev 325/326). Détail
  complet dans [`ASSETS.md`](ASSETS.md).

### Reste à extraire par décompilation
- Séquence de login exacte (`RPGMain`/`GameMain`, `NetworkProvider`) + champs `BootData1`.
- Complétude **runtime** des assets rev 325/326 pour le code 12.1.0 (à constater en exécutant).

## Mise à jour de contenu (AssetUpdater)
- Même gate que DragonSoul. Logs observés :
  « Failed to load %s, setting MISSING_ADDITIONAL flag », « loadDynamicUI setting
  MISSING_ADDITIONAL true for … ». Catégories : `WORLD_ADDITIONAL`, `UI_DYNAMIC`, `SOUND`,
  `TEXT` (colonnes de l'index).
- Manifeste `index.txt` (présent dans le dépôt) — colonnes observées :
  `Mode  Category  Environment  Density  Compression  GameVersion  Revision  URL  Size`
  (TSV avec en-tête). Modes `COMPLETE` (rev 325) et `INCREMENTAL` (rev 326).

## Données de jeu embarquées (assets/)
- `assets/stats/` : **274 `.tab`** (TSV). Ex. `arena_constants.tab` :
  ```
  \tFIGHT_PIT_VALUE\tCOLISEUM_VALUE
  LEAGUE_SIZE\t100\t50
  PROMOTION_POSITIONS\t5\t5
  ...
  ```
  → tables clé→valeurs par colonne. C'est **l'équilibrage complet** (héros, coffres,
  battle pass, items, skills, arène, expéditions…). **Source de vérité du serveur.**
- `assets/strings/` : **325 `.properties`** (textes localisés, sous-dossiers de locale).
- `assets/automation/Intro-S4.json` : script d'automatisation (tuto/intro) — utile pour
  le pilotage/tests.
- Autres : `assets/{shaders,sound,fonts,joda,helpshift,stats,strings}`.

## Piège
- `gateway/v1/*` (ClientInfoOuterClass, AdRequest, DiagnosticEvent, DeveloperConsent,
  InitializationCompletedEventRequest…) + `com.google.protobuf.*` = **SDK Unity Ads**,
  **PAS** le protocole du jeu. Ne pas confondre `gateway.v1.ClientInfo` (Unity) avec
  `com.perblue.heroes.network.messages.ClientInfo` (jeu).

## À faire (prochaine passe de recon)
- [ ] dex2jar/jadx → jar décompilé ; lire `ServerType`, `DHXORConnectionWrapper` (clé XOR),
      `AssetUpdater` (révision de contenu embarquée), `RPGMain`/`GameMain` (séquence de boot).
- [ ] Confirmer la séquence login (POST /login ? handshake TCP ?) sur cette version.
- [ ] Recenser les splits APK (ABI `.so` libGDX) nécessaires au port desktop/Android.
