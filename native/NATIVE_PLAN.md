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

## Étapes
1. [~] Récupérer spine-c 3.6 officiel (build script, non committé — licence Spine Runtimes).
2. [ ] JNI glue `cspine_jni.c` : table de handles, Spine_init, erreurs, Atlas/SkeletonData/Skeleton/
   AnimationState(Data) sur spine-c ; `Skeleton_getVertices` au format 2-couleurs du jeu.
3. [ ] `cparticle_jni.c` : moteur de particules (le `.np` = ParticleEmitter libGDX ; à porter en C —
   le natif d'origine est propriétaire, mais le comportement = ParticleEmitter, cf. NP_FORMAT.md).
4. [ ] Compiler `spine-native64.so`, le mettre sur le classpath, SUPPRIMER les shadows Java cspine,
   faire tourner le **code d'origine** `cspine.*`/`cparticle.*` du jeu. Valider visuellement.
5. [ ] (Fidélité) désassembler la lib ARM d'origine pour lever les ambiguïtés (format drawCalls, etc.).
```
