# desktop-port — état & stratégie

Portage desktop de Disney Heroes (miroir de la démarche DragonSoul `desktop-port/`, mais
**adaptée** à ce que contient réellement l'APK). Voir [`../docs/PRINCIPLES.md`](../docs/PRINCIPLES.md),
[`../docs/SHIMS.md`](../docs/SHIMS.md), [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

## Ce qui est fait
- **Scaffold Gradle** (`build.gradle`, `settings.gradle`) : LWJGL 3.3.4 + natifs libGDX
  **1.9.7** (version embarquée par le jeu — ABI JNI ; ≠ DragonSoul 1.9.3) + stubs Android
  (`com.google.android:android:4.1.1.4`) + `org.json` + `../libs/game.jar`.
- **Rendu headless prouvé** : `src/main/java/desktop/GLSmokeTest.java` + `run-gl-smoke.sh`
  (Xvfb + Mesa **llvmpipe**, `LIBGL_ALWAYS_SOFTWARE=1`). Résultat vérifié dans ce conteneur :
  `GL 4.5 (Compatibility) Mesa llvmpipe`, frame rendue, `glError=0`, capture PPM écrite.
  ⇒ **le pipeline de rendu + capture headless fonctionne** (comme pour DragonSoul).

## Découvertes majeures (recon du jar) → stratégie RÉVISÉE
Contrairement à DragonSoul (jar obfusqué sans backend desktop exploitable → backend LWJGL3
maison `dsbackend/` intégral), **le jar de Disney Heroes embarque déjà** :

1. **Le backend desktop libGDX** : `com.badlogic.gdx.backends.lwjgl.LwjglApplication`,
   `LwjglGraphics`, … (backend **LWJGL 2**, d'où les **757 classes `org/lwjgl`** — collision
   avec notre LWJGL 3.3.4 sur le classpath du smoke test).
2. **Le backend headless** : `com.badlogic.gdx.backends.headless.HeadlessApplication`.
3. **Le root du jeu** : `com.perblue.heroes.GameMain extends ApplicationAdapter` (l'équivalent
   de `RPGMain`). C'est l'`ApplicationListener` à lancer.
4. **Un framework d'automatisation/pilotage intégré** : `com.perblue.heroes.automation.*`
   (`TouchRecorder`, `TouchPlayback`, `AutoTouchEvent`) + `automation.crawler.*`
   (`CrawlerNavigation`, `CrawlerScript`, `CrawlingTask`, scripts prêts : `ChestBuyScript`,
   `GoldScript`, `MarketBuyScript`…). ⇒ **le pilotage headless "sait ce qui est cliquable et
   exécute des actions" existe déjà dans le jeu** — à réutiliser pour tests/débogage.

### Conséquence : approche du port
- **Réutiliser le backend `LwjglApplication` (LWJGL2) bundlé** plutôt que réécrire un backend
  complet. Lancer `new LwjglApplication(new GameMain(...), config)` avec les natifs LWJGL2
  (Maven) + libgdx 1.9.7. Option **HeadlessApplication** pour le serveur/l'automatisation pure.
- **Shims restants** = seulement les **services plateforme** que l'`AndroidLauncher` fournit à
  `GameMain` (DeviceInfo, réseau, achats NO-OP, social/analytics NO-OP…), pas la couche
  GL/Graphics/Input/Audio (fournie par LwjglApplication). Beaucoup moins de travail que `dsbackend`.
- **Collision LWJGL** : pour un backend LWJGL2 bundlé, on **n'ajoute pas** LWJGL3 au classpath
  du launcher (on garde le LWJGL2 du jar + ses natifs). Le smoke test GL3, lui, exclut game.jar.
  À trancher : LWJGL2 (bundlé, fidèle) vs LWJGL3 (`gdx-backend-lwjgl3` externe) — **défaut =
  LWJGL2 bundlé** (moins de divergence, principe « réutiliser l'existant »).
- **Rediriger `ServerType.LIVE`** (réflexion, sans patch bytecode) vers nos serveurs
  (contenu + login) au démarrage du launcher.

## Tentative de lancement — chaîne de boot atteinte & VERDICT

Launcher écrit (`dhdesktop/DesktopLauncher` + shim `dhbackend/DhDeviceInfo`), compile et
s'exécute sous Xvfb. Itérations de débogage du boot :

1. **Backend LWJGL2 bundlé** (`LwjglApplication`) → 2 murs *headless* :
   - natifs LWJGL2 stock incompatibles avec les classes `org/lwjgl` de game.jar (RÉDUITES
     par ProGuard : `PointerWrapper.getPointer()` supprimée) — contourné en shadowant avec
     LWJGL 2.9.3 stock ;
   - puis **`LinuxDisplay.getAvailableDisplayModes` → AIOOBE** sous Xvfb (le `Display` X11 de
     LWJGL2 n'énumère pas de modes) + audio absent. LWJGL2 est **hostile au headless**.
2. **Backend LWJGL3 de Maven** (`Lwjgl3Application`, GLFW — *headless-friendly*, cf. GL smoke) :
   GLFW s'initialise, on atteint `GameMain.<clinit>` **puis** la création de la fenêtre/entrée.
   Deux incompatibilités **révélatrices** :
   - `com.badlogic.gdx.scenes.scene2d.Group` **n'a pas** `DEFAULT_TRANSFORM` en libGDX stock
     → **PerBlue a AJOUTÉ des champs au core libGDX** (core modifié) ;
   - avec le core PerBlue conservé, le backend stock appelle
     `com.badlogic.gdx.InputEventQueue.setProcessor(...)` **absente** du core PerBlue (RÉDUIT
     par ProGuard) → le backend stock ne matche pas non plus.

**VERDICT** : le core libGDX de Disney Heroes est **modifié ET réduit** → ni le core stock,
ni le backend stock ne sont compatibles. On ne peut donc **pas** réutiliser un backend Maven
tel quel. C'est exactement la situation DragonSoul → il faut un **backend maison LWJGL3**
implémentant les interfaces du core libGDX **du jeu** (Application/Graphics/Input/Files/Audio/
GL20), comme `dsbackend/`. Le backend LWJGL2 bundlé, lui, matcherait le core mais est
inutilisable en headless.

### Décision : backend maison LWJGL3 (adapter `dsbackend/` de DragonSoul)
DragonSoul et Disney Heroes partagent le **même core libGDX PerBlue** → on **adapte le
`dsbackend/` de DragonSoul** (déjà un backend LWJGL3 headless contre un core PerBlue) plutôt
que repartir de zéro. Le launcher, le shim `DhDeviceInfo`, l'extraction assets/ressources et la
redirection `ServerType` déjà écrits **restent valables**.

## Analyse de portabilité de `dsbackend/` — PAS utilisable tel quel

Vérification faite (lecture du source + comparaison des interfaces). `dsbackend/` est une
**excellente référence** mais **ne se recopie pas** : il cible le libGDX de DragonSoul
**obfusqué** (1.9.3), avec l'API `RPGMain`. Différences concrètes à traiter :

| Aspect | DragonSoul (dsbackend) | Disney Heroes | Travail |
|---|---|---|---|
| Noms des interfaces libGDX | **obfusqués** (`com.badlogic.gdx.a`=Application, `f`=Graphics, `g`=Input, `e`=Files, `d`=Audio, `l`=Net, `m`=Preferences, `c_`=ApplicationListener, `k`=LifecycleListener) | **clairs** (`Application`, `Graphics`, …) | remap mécanique des `implements`/types |
| Certaines méthodes | obfusquées (`getType$2826c76()` renvoie `int`) | claires (`getType()` renvoie `Application$ApplicationType`) | corriger signatures (ex. renvoyer `ApplicationType.Android`) |
| Version libGDX | 1.9.3 | 1.9.7 | reconciler les deltas d'interface (mineurs) |
| API plateforme du jeu | `RPGMain` | `GameMain` | idem structure (voir ci-dessous) |
| Rustines contenu | marqueurs vides (assets perdus) | **inutile** (on sert le vrai contenu via archive.org) | supprimer |

**Bonne nouvelle** : les **jeux d'interfaces coïncident** (même fork libGDX PerBlue). Ex.
`com.badlogic.gdx.Application` de DH = **exactement les 18 méthodes** que `DsApplication`
implémente ; `GL20` = 75/75 méthodes ; les setters plateforme de `GameMain`
(`setNativeAccess(INative)`, `setAnalytics(IAnalytics)`, `setSocialNetworkManager(...)`,
`setSupportManager(ISupport)`, `setTapjoyOfferwall(...)`, `setPlaybackRewards(...)`)
**reproduisent** ceux de `RPGMain`. Donc les **corps de méthodes** (appels GL LWJGL, mapping
clavier/souris GLFW, décodage OGG, FileHandles) sont **réutilisables presque tels quels** ;
seul le « habillage » (noms de types, quelques signatures) change.

### Méthode de port retenue (sûre, pas de copier-coller aveugle)
Pour chaque shim : **partir de l'interface RÉELLE de DH** (`javap com.badlogic.gdx.<I>` →
liste exacte des méthodes à implémenter, noms clairs), puis **porter le corps** depuis
`dsbackend/`. Ainsi on colle au 1.9.7 exact (deltas gérés) et on récupère la logique éprouvée.
Le launcher wire les shims dans **`com.badlogic.gdx.Gdx`** (champs clairs `Gdx.graphics/audio/
input/files/net/gl/gl20/app`) au lieu du singleton obfusqué `com.badlogic.gdx.utils.b.a`.

## Prochaines étapes
1. [ ] Adapter le backend LWJGL3 maison depuis `dsbackend/` (Application/Graphics/Input/Files/
   Audio/GL20/Net/Preferences) contre le core libGDX du jeu (interfaces standard).
2. [ ] Fournir les **services plateforme** restants attendus par `GameMain`/`AndroidLauncher`.
3. [ ] Rediriger `ServerType.LIVE` → serveur de contenu + login locaux ; franchir le boot ;
   captures via glReadPixels (pipeline headless déjà prouvé).
4. [ ] Brancher l'**automation crawler** du jeu pour le pilotage/tests headless.

## Acquis réutilisables (déjà écrits)
- `dhdesktop/DesktopLauncher.java` : construit `GameMain(DhDeviceInfo)`, redirection
  `ServerType.LIVE` par réflexion (`-Ddh.server=host:port`), config fenêtre.
- `dhbackend/DhDeviceInfo.java` : shim `DeviceInfo` (FACTICE cohérent, `Platform.ANDROID`).
- `run-desktop.sh` : Xvfb + Mesa llvmpipe + extraction assets/ressources APK + fabrication
  d'un `game-logic.jar` (sans `org/lwjgl` ni backends bundlés) + classpath.
