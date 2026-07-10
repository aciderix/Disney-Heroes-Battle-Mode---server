# BACKEND_STATUS — état du backend desktop & ce qui reste à faire

Suivi **exhaustif** des shims du backend LWJGL3 maison (`src/main/java/dhbackend/`) et de tout
ce qui est **différé / stubbé** (pour ne rien oublier). Légende fidélité (cf. `../docs/SHIMS.md`) :
**RÉEL** (équivalent à l'origine) · **PARTIEL** · **NO-OP** · **STUB/DEFERRED** (à implémenter).

> Mis à jour à chaque avancée. Relié à `PROGRESS.md` (récit) et aux `// DEFERRED (#TAG)` du code.

## Progrès de boot atteint (2026-07-10)
Le jeu **rend l'écran de chargement/splash complet** (logos Disney + PerBlue, **splash UI
"Disney Heroes: Battle Mode" avec barre Loading** — capture `build/boot.png`). Séquence de
tâches franchie : `LoadBootAtlasUI`, `LoadPerBlueUI`, `StartServerLogin` (**login POST envoyé
via `DhNet`**), `ShowDisneyLogo`, `WaitForDisneyAnimation`, `ShowPerBlueUI`,
`WaitForPerBlueAnimation`, `LoadNetworkEvents`, `FinishLoadSplashUI`, `ShowSplashUI`. 120 frames
rendues + capture. `GameMain.create()` OK (ETC1, XHDPI 1280×720), stats `.tab` chargées.

Corrigé depuis : **#ANDROIDSTUBS** (`SystemClock` + `Process` fonctionnels), **#NET** (`DhNet`
HTTP réel → login envoyé), **JIT** (`-XX:TieredStopAtLevel=1` : le C2 plantait sur le bytecode
dex2jar — `GraphKit::use_exception_state`).

### Mise à jour — LOGIN fonctionnel de bout en bout (2026-07-10)
Flux de connexion RÉEL vérifié (`desktop-port/run-online.sh` : contenu+login Python :8080 +
serveur de jeu TCP Java :8081 + client) :
- `ServerType.LIVE` redirigé (réflexion, sans patch) : login `http://127.0.0.1:8080/login`,
  contenu idem. Le client **POST/GET son login** → réponse **`{"status":"good","data":
  "127.0.0.1:8081"}`** → **`[NetworkProvider] Connection to 127.0.0.1:8081 opened`** : le vrai
  client **se connecte à NOTRE serveur de jeu**. #LOGIN (HTTP) = **fait**.
- Stats `.tab`/`.tabb` : `forceText()=false` → le jeu tente le binaire (`.tabb`, ex.
  `unit_abilities`) puis retombe sur le texte (`.tab`) ; les 274 se chargent.

**Bloqué ensuite sur #SPINE** — `libspine-native64.so` (natif d'animation Spine de PerBlue,
`com.perblue.heroes.cspine.Native`) est requis dès `WaitForDisneyAnimation` (logo Disney animé)
→ crash du thread render. Ce `.so` **n'est pas dans le base APK** (les natifs sont dans les
splits APK par ABI). **À fournir** (`libspine-native64.so` x86_64) → cf. #SPINE. Ensuite :
handshake `ClientInfo1`→`BootData1` (serveur prêt) puis, pour un nouveau joueur, `isNewUser`
→ **tutoriel `IntroTutorialActV1`** (le jeu accorde lui-même héros/ressources — NE PAS seeder).

## Shims implémentés

| Shim | Interface du jeu | Fidélité | Notes |
|---|---|---|---|
| `DhGL20` | `graphics.GL20` (75) | **RÉEL** | Délègue à LWJGL3 (GL11/13/14/15/20/30). Porté de `DsGL20`. |
| `DhGraphics` | `Graphics` (19) | **RÉEL** | Taille fenêtre + GL20 ; **`GLVersion` réel** (requis par `initTextureCompressionType`). GL30=null (#GL30). |
| `DhInput` | `Input` (17) | **RÉEL** | Événements GLFW + injection CLI. Capteurs=0 (#INPUT). |
| `GlfwInput` | — | **RÉEL** | Callbacks GLFW → `DhInput`, mapping keycodes Android. |
| `DhFiles` / `DhFileHandle` | `Files` (8) | **RÉEL** | Assets = handles Classpath à chemin relatif (clés AssetManager). |
| `DhPreferences` | `Preferences` (19) | **RÉEL** | `.prefs` XML sur disque. |
| `DhApplication` | `Application` (18) | **RÉEL** | Logs stdout, `postRunnable`→file drainée, `getType()=Android`. Clipboard=null (#CLIPBOARD). |
| `DhDeviceInfo` | `util.DeviceInfo` | **FACTICE** | Valeurs device cohérentes, `Platform.ANDROID`. |
| `DhStatFileExt` | `StatFileHelper$StatFileHelperExt` | **RÉEL** | Ouvre les `.tab` du jeu depuis `stats/` (classpath). Hashes vides (#STATHASH). |
| `DhBridges` | INative/IAnalytics/… | **NO-OP** | Proxies renvoyant des défauts (#BRIDGES). |
| `DhAudio` | `Audio` (6) | **STUB** | **#AUDIO** — no-op (Sound/Music proxies). À porter (OpenAL+STB de `DsAudio`). |
| `DhNet` | `Net` (1) | **STUB** | **#NET** — `sendHttpRequest` échoue proprement. À porter (`HttpURLConnection` de `DsNet`). Requis pour le login. |

## DEFERRED — à implémenter (référencé par `// DEFERRED (#TAG)` dans le code)

- **#NET** (bloquant login) — `DhNet.sendHttpRequest` réel (HTTP via `java.net.HttpURLConnection`,
  suit les redirections). C'est le canal du `POST /login`. Porter depuis DragonSoul `DsNet`.
- **#ANDROIDSTUBS** (bloquant boot) — `android.os.SystemClock.elapsedRealtimeNanos()` (et
  potentiellement d'autres méthodes d'API récentes) absent de `com.google.android:android:4.1.1.4`
  (API 16). Fournir une classe `android.os.SystemClock` fonctionnelle (→ `System.nanoTime()`)
  **avant** les stubs sur le classpath, ou un jar de stubs d'API plus récente. Un crash JVM a
  suivi ce `NoSuchMethodError` (thread réseau) → à confirmer une fois corrigé.
- **#LOGIN** (bloquant progression) — rediriger l'**hôte de login** de `ServerType.LIVE` (pas
  seulement `contentLocation`) vers notre serveur, et servir **`POST /login`** (réponse : statut
  + adresse du serveur de jeu) ; puis le handshake TCP `ClientInfo1`→`BootData1` (serveur déjà
  prêt : `server/java/dhserver/LoginServer.java`). Sans ça → fallback OFFLINE.
- **#SPINE** — `libspine-native64.so` (natif d'animation squelettique Spine de PerBlue,
  `com.perblue.heroes.cspine.Native`) absent (les `.so` sont dans les splits APK par ABI, pas
  dans le base APK). À extraire d'un split APK / de la version Google Play et fournir sur le
  classpath (SharedLibraryLoader). Sans lui : animations cassées (erreurs non fatales au boot).
- **#AUDIO** — backend audio réel (OpenAL LWJGL + décodage OGG STB Vorbis), depuis `DsAudio`.
  Actuellement muet (no-op) ; non requis pour le rendu.
- **#CONSENT** — vérifier les **clés/valeurs exactes** des prefs d'accord (confidentialité/CGU)
  dans `GameMain` (empruntées à DragonSoul `agreedPrivacyPolicyVersion`/`agreedTermsOfServiceVersion`).
  Sinon un dialogue d'accord peut bloquer en headless.
- **#BRIDGES** — services plateforme en NO-OP (proxies). `setNativeAccess` lève une
  `InvocationTargetException` à l'installation (le proxy ou le setter appelle qqch) → à
  investiguer et fournir un `INative` minimal réel si le jeu en dépend au boot.
- **#GL30** — `DhGraphics.getGL30()` = null. Fournir un GL30 (délégué LWJGL3) si le jeu le
  déréférence (rendu avancé).
- **#GLVERSION** — ✅ fait (GLVersion réel). Note : Mesa renvoie "4.5 …" non parsé → le jeu le
  classe GLES 2.0 (chemin Android voulu) ; OK.
- **#INPUT** — capteurs (accéléromètre…), pression tactile = 0 ; non applicables desktop.
- **#CLIPBOARD** — presse-papiers non branché (null).
- **#STATHASH** — `getStatFileHashes()` vide (sert à valider le contenu téléchargé).
- **#CONTENT** — le boot exige le **contenu téléchargé** (WORLD/UI/… ETC1) absent de l'APK.
  Fourni localement par `tools/fetch_assets.sh` (archive.org) → extrait dans les assets classpath.
  À terme : le jeu télécharge via l'AssetUpdater + notre serveur de contenu (nécessite #NET).

## Prochaines étapes (ordre)
1. **#ANDROIDSTUBS** : `android.os.SystemClock` fonctionnel → franchir le crash boot.
2. **#NET** : `DhNet` réel + lancer le **serveur de contenu** (`server/content_server.py`) et le
   **serveur de login** ; rediriger `ServerType.LIVE` (`DH_SERVER=host:port`).
3. Franchir le login (`ClientInfo1`→`BootData1`) → atteindre le **menu principal** ; capturer.
4. **#AUDIO** réel, **#BRIDGES** au besoin, brancher l'**automation crawler** (pilotage/tests).
