# Inventaire — ce que le jeu exige pour tourner NATIF desktop (couche minimale)

Objectif : **zéro modification du jeu**, c'est le jeu original qui tourne 100% natif (Win/Linux), on
branche seulement la couche plateforme + les binaires natifs qu'il attend. Modèle de référence :
`dsbackend/` du port DragonSoul (remplace UNIQUEMENT la plateforme, fait tourner le code d'origine).

Audit réalisé par `javap`/désassemblage de `libs/game.jar` (le vrai code du jeu).

## 1. Bibliothèques natives chargées par le jeu (binaires à FOURNIR, pas à réécrire)

| Lib (SharedLibraryLoader/loadLibrary) | Chargée par | Rôle | Statut desktop |
|---|---|---|---|
| **`gdx`** | `GdxNativesLoader` | natif cœur libGDX (Gdx2DPixmap, matrices, buffers) | ✅ extrait de `gdx-platform:1.9.7:natives-desktop` (`libgdx64.so`) |
| **`gdx-freetype`** | `graphics.g2d.freetype.FreeType` | **rendu des polices** (FreeType) | ⬜ à fournir : `gdx-freetype-platform:1.9.7:natives-desktop` (`libgdx-freetype64.so`) |
| **`spine-native`** / `-fast` | `cspine.Native` (+`cparticle` partage) | **squelettes Spine + particules** (C) | 🔨 à BÂTIR : `spine-native64.so` (glue JNI sur spine-c officiel 3.6). Voir `native/NATIVE_PLAN.md` |
| `gdx-opensl` / `ogg` | `backends.opensl.OpenSLAudio` | audio **Android** natif | ❌ non requis : le desktop fournit son propre backend Audio (OpenAL, cf. `DsAudio`) |
| `adcolony` (`com.adcolony.sdk`) | SDK pub AdColony | publicités | ❌ non requis : service plateforme absent desktop (no-op) |

## 2. Interfaces plateforme attendues (fournies par le lanceur — comme `dsbackend`)

`GameMain` expose 6 setters de services plateforme :
- `setNativeAccess(INative)` — **INative = 35 méthodes** (orientation, notifs, permissions, IAP factory,
  sondes réseau, logs/exceptions…). Impl desktop RÉELLE façon `DsNative` : diagnostics → sortie réelle,
  reste no-op honnête. `createPurchasingInterface()` → `IPurchasing` desktop (no-op réel, pas de proxy).
- `setAnalytics(IAnalytics)`, `setSupportManager(ISupport)`, `setTapjoyOfferwall(ITapjoyOfferwall)`,
  `setPlaybackRewards(IPlaybackRewards)`, `setSocialNetworkManager(SocialNetworkManager)` — services
  absents desktop → impls **NO-OP mais RÉELLES** (classes explicites, pas de `Proxy` réflexion).

Constructeur : `new GameMain(DeviceInfo)`.

## 3. Couche backend libGDX (plateforme, fournie par le lanceur)

Application, Graphics (+GL20/GL30), Input, Files, Audio, Net, Preferences, Clipboard — surface = les
méthodes réellement appelées par le jeu (LWJGL3). Déjà écrit dans `dhbackend/` (à conserver/nettoyer).

## 4. Stubs Android (résolution de types ; méthode réellement appelée = shim FONCTIONNEL)

`com.google.android:android` fournit des stubs qui lèvent « Stub! ». Toute méthode réellement appelée
au boot doit avoir un shim fonctionnel (classes `android.*` placées avant sur le classpath) :
- `android.os.SystemClock` (elapsedRealtimeNanos…) ✅ ; `android.os.Process` ✅ ;
- `android.os.StrictMode` (`allowThreadDiskReads`/`setThreadPolicy`) ⬜ — appelé sur le chemin de
  téléchargement du contenu (via Firebase perf) ; shim no-op fonctionnel = correctif propre.

## 5. Normalisation de build (décisions de chargement, PAS des modifs de jeu)

- `game-logic.jar` = `game.jar` sans `org/lwjgl/**` ni `com/badlogic/gdx/backends/**` (le LWJGL2 +
  backends bundlés masqueraient nos classes LWJGL3). Le cœur libGDX PerBlue est CONSERVÉ.
- Frames de vérif : le bytecode dex2jar n'a pas de StackMapTable → soit `-Xverify:none`, soit reframe
  ASM `COMPUTE_FRAMES` (métadonnées de vérif seulement, sémantique inchangée — cf. SHIMS DragonSoul).

## 6. À RETIRER (mes déviations — réécritures du jeu, contraires à l'objectif)

Une fois `spine-native64.so` en place, **supprimer** :
- `desktop-port/src/main/java/com/perblue/heroes/cspine/*` (réimplémentation Java du natif) ;
- le patch `spine-libgdx-perblue.jar` + la dépendance spine-libgdx ;
- le shadow `com/badlogic/gdx/utils/DataInput` (n'était utile qu'à spine-libgdx Java) ;
- le stub `com/perblue/heroes/cparticle/Native` ;
- le shadow `FirebasePerfUrlConnection` → remplacé par le shim Android `StrictMode` (correctif propre)
  si suffisant ; sinon fournir un `IAnalytics`/service perf no-op réel.
- `DhBridges` (proxy réflexion) → remplacé par des impls INative/IPurchasing/… réelles.
