# unidbg — exécuter le VRAI `libspine-native.so` d'origine (câblage, pas récréation)

Piste **la plus fidèle** au projet : au lieu de reconstruire spine (glue C écrite main) ou de
rétro-ingénierer le format `.np` des particules, on **exécute le binaire ARM d'origine** de PerBlue
(`native/reference/libspine-native.so`) dans la JVM desktop via **unidbg** (émulation ARM + bionic/JNI
virtuel). Le code Java d'origine `cspine.Native`/`cparticle.Native` serait remplacé par un **shim mince**
de dispatch vers unidbg (plomberie plateforme, PAS de logique de jeu — conforme PRINCIPLES §4).

## Résultats du prototype (2026-07-11)

- **Chargement OK** : unidbg charge `libspine-native.so` et exécute ses fonctions JNI d'origine
  (`Spine_init`, `getLastSpineError` → "").
- **`Effect_create` parse un vrai `.np` sans erreur** (`particleErr=""`, handle valide) →
  **#NP-V3 résolu par EXÉCUTION du code d'origine, zéro RE du format.**
- **Perf simulation par-frame** (`Effect_update`, la physique `updateParticles`) : **~46 µs/appel**
  (backend unicorn). Budget frame 60 fps = 16 667 µs → ~365 updates/frame possibles. **Très large.**
- **dynarmic** (JIT plus rapide) : plante sur NEON (`vldr/vcvt`) dans `loadImages` — **inutile**,
  unicorn suffit.
- **Reste à valider** : marshaling des `FloatBuffer`/`ShortBuffer` directs de
  `Skeleton_getVertices`/`Effect_getVertices` (chemin de rendu) + coût de recopie par frame.

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
