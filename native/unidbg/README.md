# unidbg — exécuter le VRAI `libspine-native.so` d'origine (câblage, pas récréation)

Piste **la plus fidèle** au projet : au lieu de reconstruire spine (glue C écrite main) ou de
rétro-ingénierer le format `.np` des particules, on **exécute le binaire ARM d'origine** de PerBlue
(`native/reference/libspine-native.so`) dans la JVM desktop via **unidbg** (émulation ARM + bionic/JNI
virtuel). Le code Java d'origine `cspine.Native`/`cparticle.Native` serait remplacé par un **shim mince**
de dispatch vers unidbg (plomberie plateforme, PAS de logique de jeu — conforme PRINCIPLES §4).

## Résultats du prototype (2026-07-11) — PIPELINE PARTICULES COMPLET VALIDÉ

- **Chargement OK** : unidbg charge `libspine-native.so` et exécute ses fonctions JNI d'origine
  (`Spine_init`, `getLastSpineError` → "").
- **`Effect_create` parse un vrai `.np` sans erreur** (`particleErr=""`, handle valide) →
  **#NP-V3 résolu par EXÉCUTION du code d'origine, zéro RE du format.**
- **Simulation par-frame** (`Effect_update`, `updateParticles`) : **~46 µs/appel** (backend unicorn).
- **RENDU par-frame** (`Effect_getVertices`) : renvoie de **vrais sommets 2-couleurs** (6 floats/sommet :
  x, y, light, dark, u, v) générés par le moteur d'origine. `update + getVertices` = **~141 µs/frame/effet**
  → budget 60 fps (16 667 µs) = **~118 effets/frame**. Une scène en a 5-30 → **large**.
- **Trou unidbg corrigé** : `GetDirectBufferAddress`/`GetDirectBufferCapacity` (JNI 230/231) ne sont pas
  implémentés par unidbg (throw `UnsupportedOperationException`). On les **implémente** (≈15 lignes,
  `SpineVerts2.java`) en écrasant ces slots de la table `JNIEnv` par un `ArmSvc` qui renvoie le pointeur
  émulé porté par le `DvmObject`. Plomberie plateforme standard, pas de logique jeu.
- **dynarmic** (JIT plus rapide) : plante sur NEON (`vldr/vcvt`) dans `loadImages` — **inutile**,
  unicorn suffit.

⇒ **Le moteur de particules D'ORIGINE tourne entièrement (parse + simulation + sommets) à perf viable.**
  Par extension, spine (même lib, même schéma de buffers) devrait suivre. La stratégie « câblage du
  binaire d'origine » est **validée** pour spine ET particules.

## Reste à faire pour l'intégration réelle
1. Tester `Skeleton_getVertices` (spine) avec un vrai `.skel` (haute confiance, même mécanisme).
2. Shim `cspine.Native`/`cparticle.Native` → dispatch vers une VM unidbg **persistante** (chargée une
   fois). Un seul thread appelant (thread de rendu) — les VM unidbg ne sont pas thread-safe.
3. Buffers émulés alloués UNE fois et réutilisés par frame ; recopie vers les `FloatBuffer` du jeu.
4. Rendre à l'écran et **comparer aux captures du jeu original** (PRINCIPLES §4bis).
5. Committer le binaire hôte unicorn (déjà dans le jar unidbg) — rien à rebâtir.

## Fichiers
- `SpineUnidbg.java` : charge la lib + `Spine_init` + chrono basique.
- `SpineLoad.java`   : `Atlas_create` + `Effect_create` sur de vrais assets (prouve le parse `.np`).
- `SpineBench.java`  : benchmark `Effect_update` (unicorn vs dynarmic).
- `build.gradle`     : dépendances unidbg 0.9.8 (`unidbg-android`, `unidbg-dynarmic`) via Maven Central.

## Lancer
```
cd native/unidbg && gradle -q cp        # résout le classpath -> build/runtime.cp
CP=$(cat build/runtime.cp)
javac -cp "$CP" *.java
A=../../desktop-port/build/apk/assets/ETC1/world/units/finnick/vfx
java -cp "$CP:." SpineBench ../reference/libspine-native.so \
     "$A/particles-DEFAULT.atlas" "$A/finnick_skill3_ground.np" unicorn
```

## Décision d'archi (à trancher après le test getVertices)
- Si le rendu passe et reste rapide → **basculer spine ET particules sur unidbg** (le vrai code
  d'origine tourne ; on supprime le rebuild `spine-native64.so` et tout le chantier RE particules).
- Sinon → garder le rebuild natif rapide pour le rendu spine, et unidbg comme **oracle** fidèle
  (remplace l'oracle qemu) pour valider/dériver le reste.

## Perf SPINE (2026-07-11) — le point dur
`SpineSkel.java` valide le pipeline spine d'origine (Atlas/SkeletonData/Skeleton/AnimationState +
`Skeleton_getVertices`) — **tout parse et rend correctement via le code d'origine**. Mais le coût :
- soulless_brute (héros complexe) : update 70 + apply 361 + worldTransform 524 + **getVertices 1218** =
  ~2111 µs/squelette → **~7 squelettes/frame @60fps**.
- soulless_sword (plus simple) : ~1467 µs → ~11/frame.
- **dynarmic** (JIT) : crash/hang (NEON) → pas de gain facile.

⇒ **Particules = unidbg viable. Spine = trop lent sur l'interpréteur** pour le combat (10 héros) et le
MainScreen (~12 persos). Architecture pragmatique proposée :
- **Particules → unidbg** (moteur d'origine, résout #NP-V3, ~118 effets/frame).
- **Spine → rebuild natif x86_64** (déjà rapide et rendu vérifié vs capture d'origine ; banding corrigé).
Alternatives : (a) réparer dynarmic → spine 100% d'origine rapide ; (b) tout unidbg mais viser 30fps /
scènes légères ; (c) optimiser (cache squelettes idle, LOD).
