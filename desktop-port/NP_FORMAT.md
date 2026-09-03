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

## ⭐ RÉSOLU (2026-09-03, g261ter) — Oracle d'exécution via unidbg (pivot qemu→unidbg, §4/§8)

qemu indisponible sur cette machine Windows (déjà noté ailleurs, cf. MEMORY §7 pivot historique) →
**unidbg sert d'oracle** à la place (même principe : exécuter le VRAI binaire ARM, lire ce qu'il fait
réellement, zéro supposition). Outil : `native/unidbg/NpFormatOracle.java`.

**Méthode (bien plus directe que la reconstruction manuelle du désassemblage complet, qui avait donné
0/535 deux fois)** : au lieu de lire à la main les ~2132 octets de `ParticleEmitter::load` et
reconstituer la séquence d'appels (source d'erreur), on pose des **BREAKPOINTS unidbg**
(`Emulator.attach().addBreakPoint(Module, offset, callback)`, API réelle d'unidbg — `com.github.unidbg
.debugger.Debugger`) sur les 2 SEULES primitives atomiques de lecture, dont la sémantique de registres a
été confirmée par désassemblage CIBLÉ (quelques instructions, haute confiance, pas la fonction entière) :
- **`read4`** (int/float 4o BE) @ `0x1a770` : `ldr r2,[r0]; ldr r3,[r2],#4; str r2,[r0]; ldr r0,[r1]; subs
  r0,#4; str r0,[r1]; rev r0,r3; bx lr`. **r0 = adresse de la variable locale "curseur"** (`uint8_t*
  data`, dans la pile de l'appelant) ; `*r0` **avant** l'appel = position courante dans le buffer.
- **`readByte`** (bool, 1o) @ `0x1a0d4` : même convention, `r0` pointe vers la paire adjacente
  `{data, len}` (mêmes variables locales, accès en un seul pointeur au lieu de deux arguments séparés).

Dans les DEUX cas, il suffit de lire `*r0` À CHAQUE hit (dans l'ordre d'exécution réel, capturé par
unidbg) pour obtenir : (a) le TYPE de lecture (int/float 4o vs bool 1o, selon quelle adresse a déclenché
le hit), (b) la POSITION FICHIER exacte lue (offset relatif au tout premier hit), (c) la VALEUR lue —
**sans avoir besoin de comprendre `readRanged`/`readScaled`/`readGradient` en interne** (ce sont des
compositions des 2 primitives atomiques, transparentes pour cette méthode).

**Calibration vérifiée par recoupement direct contre les octets du fichier réel** (`arena_promote.np`,
1056 o) : les 2 premiers octets (magie `0x00`+version `0x03`) sont consommés par un test direct
`data[0]==0 && data[1]==3` dans `ParticleEffect::load`, **PAS** via `read4`/`readByte` → le 1ᵉʳ hit
observé (à `*r0` = début du curseur suivi par l'oracle) correspond en réalité à l'octet fichier **2**
(pas 0) → correction `-2` appliquée. Après correction, **chaque valeur observée dans l'émulation
correspond EXACTEMENT à la valeur lue directement dans le fichier réel à l'offset annoncé** (vérifié
octet-à-octet sur les 24 premiers hits + spot-checks plus loin dans le fichier).

**Premier décodage certifié** (`arena_promote.np`, emitter #0) :
```
off=2  int=1         emitterCount (lu par ParticleEffect::load, PAS dans l'emitter)
off=6  int=1         minParticleCount  (struct 0x7c0)
off=10 int=4         maxParticleCount  (struct 0x7c4)
off=14 bool=0        delay.active      ┐
off=15 int=0.0       delay.lowMin      │ readRanged(0x3d8) = 10 o EXACT (14..24)
off=19 int=0.0       delay.lowMax      │
off=23 bool=0        delay.lowUsesLinkedRange ┘
off=24 bool=1        duration.active   ┐
off=25 int=1500.0    duration.lowMin   │ readRanged(0x410) = 10 o EXACT (24..34)
off=29 int=1500.0    duration.lowMax   │
off=33 bool=0        duration.lowUsesLinkedRange ┘
```
**Confirme EXACTEMENT** l'ordre déjà pressenti par le désassemblage statique partiel de
`NATIVE_PLAN.md`/§Séquence ci-dessus (`read4×2` puis `readRanged×2` = delay, duration) — mais cette fois
**certifié par exécution réelle + valeurs sémantiquement cohérentes** (effet à durée fixe 1.5s, sans
délai), pas une lecture d'assembleur risquant l'erreur d'attribution.

**Trailer décodé** (`poolSize`/`tagLen` sont eux-mêmes lus via `read4` — donc DÉJÀ dans la séquence
capturée, ce sont simplement les 2 DERNIERS hits `int` contigus avant le trou — seuls le pool de floats
qui suit et le nom de région (`tag`, UTF-8) sont copiés en bloc/`memcpy`, invisibles aux hooks) :
pour `arena_promote.np`, `poolSize=62` `tagLen=11` → `tag="fireworks_b"` (un VRAI nom de région d'atlas,
vérifié) → `poolEnd + tagLen` tombe EXACTEMENT sur `EOF` (1056).

**3 sites de lecture INLINE trouvés et câblés** (en scannant TOUT `ldrb rX,[rY],#1` dans
`ParticleEmitter::load`, 0x19755..0x19fc9, via `disasm.py`) — le parseur ne lit PAS 100% de ses octets
via le helper partagé `readByte` : 3 endroits lisent un octet directement en ligne (`0x19848`, `0x19874`,
`0x19a2c`), conditionnels (visibles seulement si un branchement précédent les atteint) — cohérent avec
des champs OPTIONNELS de `readSpawnShape` (`code`, puis `edges`/`side` si ellipse). Sans ces 3 hooks,
la séquence capturée avait des trous d'1 octet non expliqués → **une fois ajoutés, `arena_promote.np`
ET `battlepass_claimable.np` (4 emitters) passent EOF-exact.**

## ⭐⭐ CERTIFIÉ 535/535 EOF-exact (2026-09-03, g261quater)

`NpFormatOracle verify <racine assets>` (parcours RÉCURSIF, un seul atlas bidon réutilisé pour tous les
fichiers — la structure ne dépend pas du contenu de l'atlas, seule la résolution de région APRÈS notre
fenêtre d'observation en a besoin) sur **les 535 `.np` réels de l'APK** (tous, toutes les 29
sous-arborescences confondues — UI, unités/héros, environnements) :
```
=== RÉSULTAT : 535/535 EOF-exact ===
```
**Le format `.np` v3 est CERTIFIÉ** au sens du critère posé dès l'origine de ce document (§ »Étape
suivante », point 2 : « l'ordre correct = celui qui donne 535/535 EOF-exact + offsets cohérents »).
Vérification AUTOMATIQUE (`eofExactMultiEmitter`, `native/unidbg/NpFormatOracle.java`) : pour chaque
fichier, la séquence de lectures scalaires + les trailers déduits (poolSize/tagLen/pool/tag) doivent
couvrir l'INTÉGRALITÉ du fichier sans reste ni chevauchement, y compris multi-emitters (le trailer d'un
emitter doit retomber EXACTEMENT sur le 1ᵉʳ octet du suivant, ou sur EOF pour le dernier) — un ordre/une
taille de champ erronée aurait échoué sur au moins un des 535 fichiers réels (tailles/contenus tous
différents), donc ce résultat n'est PAS un hasard statistique.

**Portée de ce qui reste** (chantier substantiel, PAS traité dans cet incrément) :
1. Nommer précisément CHAQUE champ de la séquence (actuellement on sait où sont les frontières et les
   TYPES bruts — bool/int/pool/tag — mais pas encore l'attribution complète à chaque nom sémantique du
   struct `0x904` ; delay/duration/minCount/maxCount déjà faits, le reste est mécanique — même méthode,
   juste du temps à dérouler la séquence complète + croiser avec la struct 0x904 octets/emitter).
2. Écrire `cparticle_jni.c::Effect_create` fidèle (parsing certifié), la simulation (`updateParticles`,
   portée depuis `com.badlogic.gdx...ParticleEmitter` EN CLAIR dans `game.jar`, comportement identique
   par construction) et le rendu 2-couleurs (`getTCVertices`), le tout validé en CONTINU contre CET
   ORACLE (comme `CompareBackend` pour spine) — le blocage qui empêchait de commencer est levé.
3. Câblage build (`native/build-hostspine.sh`/`-win.sh`) + routage Java (`cparticle.Native` → natif,
   miroir de `cspine.Native`→`HostSpine`) + certification différentielle en combat réel + gain FPS mesuré.
```
