# Format `.np` (particules natives PerBlue) — rétro-ingénierie par EXTRACTION

But : réimplémenter FIDÈLEMENT le lecteur `.np` du moteur natif `cparticle` (lib C++ propriétaire,
absente en x86_64), pour que le code Java d'origine `com.perblue.heroes.cparticle.*` tourne INCHANGÉ.
**Règle** (PRINCIPLES §4) : rien de deviné/inventé — tout est **extrait** (désassemblage de la lib ARM
d'origine = source de vérité) et **vérifié bit-à-bit** contre de vrais assets.

## Sources de vérité (dans l'APK)
- **Lecteur natif** `libspine-native.so` (ARM, non strippé, `native/reference/`) — LIT les `.np` livrés.
  - `ParticleEffect::load(uchar*, uint)` @ `0x1a5e5` — en-tête + boucle emitters.
  - `ParticleEmitter::load(uchar*&, uint&)` @ `0x19755` (2132 o) — **LES CHAMPS d'un emitter** (v3).
- **Écrivain** `game.jar` EN CLAIR : `ParticleConverter.convertFileNative` → `ParticleEffect.saveBinary`
  → `ParticleEmitter.saveBinary(ParticleEffectPacker)` (+ `*Value.saveBinary`, `packer.writeTimeline*`).
  ⚠️ C'est le writer **COURANT** ; son ORDRE de champs **ne correspond PAS** aux assets v3 livrés (#NP-V3).

## En-tête de fichier (EXTRAIT + confirmé natif) — `ParticleEffect.saveBinary` / `::load`
```
byte 0x00, byte 0x03        # magie + VERSION = 3   (natif : exige len>5, teste data[0]==0 && data[1]==3)
int  emitterCount           # big-endian
emitterCount × emitter      # natif : alloue count × 0x904 (2308) octets/emitter
```

## Encodage bas niveau (EXTRAIT du natif — décisif)
- **Entiers/flottants = 4 octets BIG-ENDIAN.** `readInt` @ `0x1a770` : `ldr; ldr [],#4; rev` (byte-swap
  BE→hôte LE). Donc `struct '>i'`/`'>f'` en Python. `readBool` @ `0x1a0c4` : 1 octet, normalisé 0/1.
- Un `.np` ne contient **aucun nom de champ** : que des valeurs binaires en séquence fixe.

## Formats de valeurs (EXTRAITS du natif — IDENTIQUES au `*Value.saveBinary` clair)
Vérifiés par désassemblage des sous-lecteurs (offsets de stockage struct entre crochets) :
- `readRanged` @ `0x19fd0` (**10 o**) : `active(bool)`, `lowMin(f)`, `lowMax(f)`, `lowUsesLinkedRange(bool)`.
- `readScaled` @ `0x1a020` (**32 o**) : `readRanged`(10) + `highMin(f)` + `highMax(f)` + `highLinked(bool)`
  + `relative(bool)` + **timeline** `(int N, int offTimeline, int offScaling)`.
- `readNumeric` (**5 o**) : `active(bool)` + `value(f)`.
- `readGradient` (**13 o**) : `active(bool)` + timeline `(int N, int offColors, int offTimeline)`.
- `readSpawnShape` : `active(bool)` + `code(byte)` [+ si ellipse : `edges(bool)`, `side(byte)`].

## Timelines : pool différé par emitter (EXTRAIT de `ParticleEffectPacker`)
Chaque valeur Scaled/Gradient n'écrit EN LIGNE que le triplet `(N, offA, offB)` = longueur + 2 offsets
dans un **pool de flottants** propre à l'emitter. `addTimeline` renvoie l'offset AVANT d'ajouter ; pour
un Scaled il ajoute `timeline[N]` puis `scaling[N]` (offB=offA+N). Le pool + le nom d'atlas sont
sérialisés à la FIN de l'emitter (`writeTimelines`) :
```
int  poolSize               # nb de flottants du pool
int  atlasTagLen            # longueur UTF-8 du nom de région
float[poolSize]             # le pool (données de toutes les timelines de l'emitter)
byte[atlasTagLen]           # nom de région d'atlas
```
Le natif lit ce bloc via malloc+memcpy (PLT en fin de `ParticleEmitter::load`).

## #NP-V3 — l'ordre des champs v3 ≠ writer courant (CONFIRMÉ 2 fois)
- Parse des 535 `.np` réels avec l'ordre EXACT du `saveBinary` COURANT → **0/535** EOF-exact.
- Parse avec l'ordre reconstruit statiquement depuis la séquence d'appels de `ParticleEmitter::load`
  → **0/535** (reconstruction encore imprécise : bools/valeurs intercalés au milieu, cf. ci-dessous).
- Les **formats** de valeurs correspondent (readRanged/readScaled = *Value.saveBinary) ; seul l'**ordre /
  l'ensemble** des champs diffère (le writer courant a évolué : ajout `velocityZ, zOffset,
  tangential*, centripetal*` absents/différents en v3).

### Séquence d'appels classée de `ParticleEmitter::load` (à finaliser)
Helpers résolus : `read4`=`0x19fa8`(4 o), `readBool`=`0x1a0c4`, `readRanged`=`0x19fd0`,
`readScaled`=`0x1a020` ; registres `r4`=readScaled (gros du travail), `fp`=read4 (préservé).
Ordre observé (offsets struct entre parenthèses) :
```
read4(0x7c0), read4(0x7c4),            # min/maxParticleCount
readRanged(0x3d8), readRanged(0x410),  # delay, duration
readScaled ×6,
readBoolInto(0x660), read4(0x664),     # NumericValue ? (active + value)
readBoolInto(0x6b8), readBool,         # (spawnShape ? à confirmer)
readScaled ×12,
readRanged, readScaled ×2, readRanged, readScaled,
readBoolInto, read4 ×3,                # GradientColorValue ? (active + timeline)
readScaled, read4,                     # (à confirmer)
readBool ×4,                           # attached/continuous/aligned/behind
<trailer writeTimelines : poolSize, tagLen, pool, tag>
```
⚠️ Cette séquence donne 0/535 EOF-exact → **imprécise**. NE PAS l'implémenter en l'état
(interdiction de deviner, PRINCIPLES §2/§4).

## Étape suivante (extraction SANS devinette)
1. **Oracle d'exécution** : faire tourner le vrai `ParticleEffect::load` sous qemu (harnais
   `native/oracle/`, dlopen déjà OK) sur des `.np` réels → lire la struct parsée (2308 o) et la longueur
   consommée = **vérité bit-à-bit** de l'ordre/tailles des champs. **OU**
2. **Auto-parse validé par les offsets de pool** : parser chaque valeur en vérifiant le triplet
   `(N, offA, offB)` contre le compteur de pool courant → détecte le type à chaque position sans
   supposer l'ordre ; l'ordre correct = celui qui donne **535/535** EOF-exact + offsets cohérents.
3. Une fois l'ordre CERTIFIÉ (535/535), implémenter `cparticle_jni.c` `Effect_create` fidèle,
   puis la simulation (via `ParticleEmitter.update` clair = comportement identique à `updateParticles`)
   et le rendu 2-couleurs (`getTwoColorSprite`), validés contre l'oracle.
```
