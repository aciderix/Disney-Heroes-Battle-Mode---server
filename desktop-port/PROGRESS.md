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

## Prochaines étapes
1. [ ] Launcher desktop : `LwjglApplication(new GameMain(…), config)` sous Xvfb ; récupérer
   les natifs LWJGL2 + libgdx 1.9.7 ; extraire assets/ressources de l'APK (comme DragonSoul).
2. [ ] Fournir les **services plateforme** attendus par `GameMain`/`AndroidLauncher` (shims).
3. [ ] Rediriger `ServerType.LIVE` → serveur de contenu + login locaux ; franchir le boot.
4. [ ] Brancher l'**automation crawler** pour le pilotage/tests headless + captures.
