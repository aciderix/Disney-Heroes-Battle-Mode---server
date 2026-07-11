# Couche native `spine-native` — plan (code d'origine, PAS de réécriture Java)

## Découverte clé (2026-07-10)

Disney Heroes **diffère fondamentalement de DragonSoul** :
- **DragonSoul** embarque le runtime Spine **Java** (`com.esotericsoftware.spine.Skeleton/SkeletonBinary/
  SkeletonRenderer` dans `classes1.jar`) → le port fait tourner le code d'origine tel quel, en ne
  remplaçant QUE la couche plateforme (`dsbackend/`). Pas de réécriture de sous-système.
- **Disney Heroes** utilise du **natif C** : `com.perblue.heroes.cspine.Native` et
  `com.perblue.heroes.cparticle.Native` déclarent des méthodes `static native` chargées d'une lib
  **`spine-native`**. Il n'y a **aucun** `SkeletonRenderer` Java dans le jeu.

Le `<clinit>` de `cspine.Native` (décompilé) :
```
SharedLibraryLoader loader = new SharedLibraryLoader();
String os = System.getProperty("os.name");
if (BuildOptions.TOOL_MODE == ToolType.COMBAT_AUTOMATOR || os.startsWith("Windows")) {
    System.out.println("Loading fast spine binary");
    try { loader.load("spine-native-fast"); }
    catch (Throwable t) { t.printStackTrace(); System.out.println("Falling back to debug binary"); loader.load("spine-native"); }
} else {
    loader.load("spine-native");
}
Spine_init();
```
⇒ **PerBlue a construit `spine-native` pour DESKTOP** (branche « Windows », mode outil
`COMBAT_AUTOMATOR` = leur automate de combat *headless desktop* — exactement le pipeline évoqué pour
DragonSoul). `cparticle.Native.ensureLoaded()` appelle `cspine.Native.ensureLoaded()` → **une SEULE
lib** contient le natif spine ET particules.

## Conséquence : la voie FIDÈLE

Fournir `libspine-native64.so` (Linux x86_64) implémentant l'interface JNI EXACTE de PerBlue, au-dessus
du **runtime spine-c OFFICIEL** (Esoteric, v3.6). Le `SharedLibraryLoader` de libGDX le charge depuis le
classpath (`spine-native64.so`). Alors le **code Java d'origine `cspine.*`/`cparticle.*` du jeu tourne
INCHANGÉ** — on supprime les shadows Java (réécriture) du module `desktop-port/.../cspine/`.

`SharedLibraryLoader.load("spine-native")` cherche, selon l'OS/l'archi : `spine-native64.so` (Linux 64),
`spine-native64.dll` (Windows), `spine-native64.dylib` (Mac) — extrait depuis un jar du classpath ou
chargé du disque. On produira `spine-native64.so` et on le mettra sur le classpath.

## Interface native à implémenter (extraite du code du jeu — `javap`)

### cspine.Native (47 méthodes) — handles entiers (table handle→pointeur spine-c)
Spine_init(); getLastSpineError():String; printUsedHandleReport();
Atlas_create(byte[],bool)->int; Atlas_dispose(int); Atlas_getParams(int,int,int[])->bool; Atlas_getTexture(int,int)->String;
SkeletonData_create(byte[] skelBytes, int atlasHandle)->int; SkeletonData_dispose(int);
SkeletonData_getAnimation{Durations(int)->float[], ID(int,String)->int, Names(int)->String[]};
SkeletonData_getBoneID/Names, getSkinNames, getSlotNames, getStats(int,NativeSkeletonDataStats)->bool,
SkeletonData_getVertexWeightReport(int,int)->VertexWeightReportEntry[];
Skeleton_create(int dataHandle)->int; Skeleton_dispose(int); update(int,float); updateWorldTransform(int);
setToSetupPose(int); setColor(int,r,g,b,a); setTintBlack(int,r,g,b); setSkin(int,String)->bool; setSlotEyeState(int,int,int)->bool;
getBoneTransform(int,int,float[7],int); getBoneTransforms(int,int[],int,float[],int); setBoneTransform(int,int,x,y,rot,sx,sy,shx,shy);
getPosedBounds(int,float[4]);
Skeleton_getVertices(int, FloatBuffer, ShortBuffer, ShortBuffer)->int;                    // pose -> maillage
Skeleton_getVerticesAndBounds(int, FloatBuffer, ShortBuffer, ShortBuffer, float[4])->int;  // + bounds
Skeleton_getVerticesAndBoundsGlitched(...);
AnimationStateData_create(int dataHandle, float defaultMix)->int; dispose(int); setMix(int,fromAnim,toAnim,dur);
AnimationState_create(int asdHandle)->int; dispose(int); update(int,float); apply(int, skeletonHandle);
setAnimation(int,track,animID,loop)->int; addAnimation(int,track,animID,loop,delay)->int; clearTracks(int);
getCurrentAnimationID(int,track)->int; getCurrentAnimationTime(int,track)->float; nextEvent(int, int[])->bool;

### cparticle.Native (18 méthodes) — même lib
Effect_create(byte[] npBytes, int atlasHandle)->int; clone(int)->int; dispose(int);
Effect_getVertices(int, FloatBuffer, ShortBuffer)->int; getVerticesAboveZ/BelowZ(int,float,FloatBuffer,ShortBuffer)->int;
Effect_start/reset/kill/stopEmitting(int); setPositionXY/XYZ; setRotation; setScale; update(int,float)->bool(complete);
usesMultiply(int)->bool; usesZOffsets(int)->bool; getLastParticleError():String;

## Format de sommets (déjà RE — cf. NativeSkeletonRenderer/NativeParticleEffectRenderer)
Maillage **2-couleurs** : `a_position`(2f) + `a_light`(couleur empaquetée, 1f) + `a_dark`(couleur
empaquetée, 1f) + `a_texCoord0`(2f) = **6 floats/sommet**. `drawCalls` (ShortBuffer) groupe les tris
par page de texture d'atlas. `getVertices` renvoie le nombre de groupes ; remplit verts + (indices) +
drawCalls. Détail exact à confirmer sur le rendu du jeu (et/ou désassemblage de la lib ARM d'origine).

## ⭐ Source de vérité obtenue : `libspine-native.so` ARM d'origine (NON strippée)

Récupérée via `gdown` (dossier Drive ARM partagé) → split `config.armeabi_v7a.apk` →
`lib/armeabi-v7a/libspine-native.so` (297 Ko, elf32 ARM). **Copiée en `native/reference/` (gitignored :
binaire PerBlue copyrighté, NE PAS committer)**. Symboles **conservés** → RE ciblée facile. Confirme :
- **cspine = spine-c** (`spRegionAttachment_computeWorldVertices`, `spSkeletonBinary_readSkin`, …) →
  notre base spine-c officielle est la bonne. 75 fonctions `Java_com_perblue_heroes_c*` exportées.
- **cparticle = C++ PerBlue** : classes `ParticleEffect` / `ParticleEmitter`, structures
  **`TwoColorVertex`** (sommet 2-couleurs) et **`ParticleDrawCall`** (groupe de rendu). Fonctions clés
  (petites → RE tractable) :
  - `ParticleEffect::load(uchar*, uint)` = **396 o** → **le parser `.np` EXACT** (résout #NP-V3).
  - `ParticleEffect::getTCVertices(TwoColorVertex*, uint&, ParticleDrawCall*, ...)` = **264 o** →
    **format sommets + drawCalls EXACT** (résout le banding cspine ET le rendu cparticle).
  - `ParticleEmitter::updateParticles(float,float)` = 3428 o → la simulation.

⇒ Désassembler ces fonctions (capstone/objdump ARM) pour **transcrire fidèlement** (pas inventer) :
le format `.np`, la struct `ParticleDrawCall` (→ `Skeleton_getVertices` ET `Effect_getVertices`), la
simulation. C'est de l'**extraction** conforme à PRINCIPLES §4.

## Diff officiel ↔ modifié (extraction par commande — `native/tools/`)

`readelf` sur `libspine-native.so` + headers spine-c officiels :
- **spine-c** : 246 fonctions officielles ; leur `.so` en a 311. Les 65 « en plus » sont surtout des
  **utilitaires PerBlue** (`spArrayFloatArray_*`, `spArrayShortArray_*`, `spFloatArray_*`,
  `spAtlas_findRegionIgnoreCase/findRegions/disposeRegions`…). Le **cœur** (Skeleton/Bone/Animation/
  déformation) reste **standard** → notre spine-c officiel est la bonne base ; le *banding* vient plus
  probablement de NOTRE glue `getVertices` (regroupement drawCalls) que d'un écart spine-c.
- **Moteur particules = C++ propriétaire PerBlue** (aucune source publiée) : classes `ParticleEffect`/
  `ParticleEmitter`, structs `TwoColorVertex`, `OneColorVertex`, `ParticleDrawCall`, `ParticleCursor`,
  `ScaledNumericValue`. C'est un portage de `com.badlogic.gdx…ParticleEmitter` (Java, EN CLAIR dans
  game.jar) → source de reconstruction. Format `.np` = `ParticleEffect::load` (magie [0,3]=v3).

## Oracle d'exécution (émulation ARM) — fidélité bit-à-bit par commande

qemu-arm-static + cross-compilo ARM **installés**. Plan : faire tourner le `.so` d'origine sous qemu
comme **oracle** → capturer ses sorties EXACTES sur de vrais `.np`/`.skel` (sommets, drawCalls, structs
parsées) → (a) **extraire** les formats sans deviner, (b) **valider bit-à-bit** notre rebuild x86_64.
**Obstacle** : la lib est **Android** (bionic) — `NEEDED libandroid/liblog/libm/libdl/libc.so`,
symboles `__android_log_print`, `__aeabi_*`, `__errno`, `@LIBC`. → fournir une **poignée de shims
bionic** (log no-op, `__aeabi_mem*` via libgcc, `__errno`→glibc) + un harnais ARM (dlopen + appels).
Le livrable desktop reste un rebuild x86_64, mais **vérifié identique** à l'oracle.

## Étapes
1. [x] Récupérer spine-c 3.6 officiel (build script, non committé — licence Spine Runtimes).
1b.[x] Récupérer la lib ARM d'origine (source de vérité, `native/reference/`, gitignored).
2. [ ] JNI glue `cspine_jni.c` : table de handles, Spine_init, erreurs, Atlas/SkeletonData/Skeleton/
   AnimationState(Data) sur spine-c ; `Skeleton_getVertices` au format 2-couleurs du jeu.
3. [ ] `cparticle_jni.c` : moteur de particules (le `.np` = ParticleEmitter libGDX ; à porter en C —
   le natif d'origine est propriétaire, mais le comportement = ParticleEmitter, cf. NP_FORMAT.md).
4. [ ] Compiler `spine-native64.so`, le mettre sur le classpath, SUPPRIMER les shadows Java cspine,
   faire tourner le **code d'origine** `cspine.*`/`cparticle.*` du jeu. Valider visuellement.
5. [ ] (Fidélité) désassembler la lib ARM d'origine pour lever les ambiguïtés (format drawCalls, etc.).
```
