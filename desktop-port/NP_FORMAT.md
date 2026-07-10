# Format `.np` (particules natives PerBlue) — analyse de rétro-ingénierie

But : réimplémenter le moteur de particules natif (`com.perblue.heroes.cparticle.*`, lib C absente
des splits x86_64) **pour de vrai** — PAS en stub. Approche : lire le `.np` → reconstruire un
`com.badlogic.gdx.graphics.g2d.ParticleEffect` **libGDX du jeu** (qui possède déjà la simulation
`update()` ET le rendu 2-couleurs `drawPositiveDepth/NegativeDepth(TwoColorPolygonBatch)`), puis
piloter ce moteur depuis les shadows cparticle. Aucune réimplémentation de physique.

## Mécanique validée (octet à octet sur les vrais `.np`)

Le `.np` est produit au build par `ParticleConverter` → `ParticleEffect.saveBinary(ParticleEffectPacker)`
(libGDX MODIFIÉ PerBlue). Structure globale :

```
byte 0x00, byte 0x03                 # magie + VERSION (=3)
int  emitterCount
emitterCount × emitter :
    int minParticleCount, int maxParticleCount
    <suite de valeurs>               # Ranged/Scaled/Numeric/SpawnShape/GradientColor
    float frameDuration
    bool attached, continuous, aligned
    byte flags                       # bit0=additive, bit1=premultipliedAlpha, bit2=multiply
    bool behind
    # section pool de timelines (writeTimelines) :
    int  poolSize                    # nb de floats
    int  atlasTagLen                 # longueur du nom de région (UTF-8)
    float[poolSize] pool
    byte[atlasTagLen] atlasTag       # nom de région d'atlas de l'emitter
```

Encodage des valeurs (`*Value.saveBinary`) :
- `ParticleValue`        : `bool active`
- `RangedNumericValue`   : super + `float lowMin, float lowMax, bool lowUsesLinkedRange`  (10 o)
- `ScaledNumericValue`   : Ranged + `float highMin, float highMax, bool highUsesLinkedRange, bool relative`
                           + timeline `(int stride, int idxA, int idxB)`                 (32 o)
- `NumericValue`         : super + `float value`                                          (5 o)
- `SpawnShapeValue`      : super + `byte code` [+ si code==3 (ellipse) : `bool edges, byte side`]
- `GradientColorValue`   : super + timeline stride 3 `(int 3, int idxA, int idxB)`        (13 o)

**Timelines (dédup)** : chaque valeur Scaled/Gradient écrit EN LIGNE `(stride, idxA, idxB)`.
`idxA` = compteur courant du pool (invariant d'ancrage FORT), `idxB = idxA + longueur(premier tableau)`.
Le pool est vidé par emitter. Résolution : `scaling/colors = pool[idxA .. idxB)` (longueur `idxB-idxA`),
`timeline = pool[idxB .. idxB + (idxB-idxA)/stride)`.

Vérifié sur `hero_chooser_add.np` : magie [0,3], 6 emitters, refs consécutifs `(0,1)(2,3)(4,5)…`,
`poolSize`/`atlasTag` cohérents, tail = 9 o.

## ⚠️ Blocage : le jeu de champs V3 ≠ writer courant (#NP-V3)

L'octet de version vaut **3**. Or les assets `.np` (v3) n'ont PAS le même ensemble/ordre de champs
que le `saveBinary` COURANT de game.jar (le code a évolué sans changer l'octet de version). Constats
empiriques (carte des refs de `hero_chooser_add.np`, emitter 0, offsets 14–777) :

- Après `lifeOffset` (ref 4,5) le champ suivant est **directement** un Scaled (ref 6,7) → les champs
  `xOffset, yOffset, zOffset, zToYMultiplier, spawnShape` du writer courant sont **absents/déplacés**.
- Séquence : **8 valeurs Scaled** (refs 0..15) puis **1 dégradé** (`stride 3`, pool +8 = 2 couleurs +
  2 timeline) puis d'autres Scaled (refs 24..) puis un **2ᵉ dégradé** en fin → cohérent avec le mode
  **2-couleurs** de PerBlue (tint clair + tint sombre), non reflété par le writer courant.
- Un **champ de ~7 octets** non identifié apparaît entre le bloc `size` et `velocity`.

⇒ L'ordre EXACT des champs de la **v3** doit être établi avant de peupler correctement les emitters
(sinon on lit « wind » à la place de « velocity », etc. = simulation fausse = INTERDIT, cf.
PRINCIPLES §2). Pistes : (a) retrouver un `saveBinary` d'une version antérieure du jeu (APK plus
ancien) dont l'ordre correspond à la v3 ; (b) dériver l'ordre v3 empiriquement via la carte des refs
sur un large échantillon de `.np` + recoupement avec l'ordre historique de libGDX `ParticleEmitter` ;
(c) confirmer le 2ᵉ dégradé (2-couleurs) et le champ 7 o.

`NpUnpacker.java` implémente la mécanique + l'ordre du writer COURANT (décalé pour la v3) : à NE PAS
câbler tant que #NP-V3 n'est pas résolu.
