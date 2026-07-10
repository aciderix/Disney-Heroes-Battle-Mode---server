# Étude de faisabilité — animation Spine (blocage #SPINE) sur desktop

Le jeu anime les personnages avec **Spine**. En natif, ça passe par `libspine-native64.so`
(`com.perblue.heroes.cspine.*`) — indisponible pour desktop x86_64. Étude des issues.

## Faits établis (recon)
- **Version de Spine : 3.6.41** (en-tête d'un `.skel`) — format **standard** Spine, pas un
  format PerBlue custom.
- **`cspine` = fine surcouche JNI au-dessus du runtime Spine-C standard** : les méthodes natives
  (`Native.java`) mappent 1:1 les concepts Spine standard (`AnimationState_*`, `SkeletonData_
  create(byte[])`, `Atlas_create(byte[])`, `Skeleton_getVertices(FloatBuffer…)`, `Skeleton_
  setColor`…).
- **Empreinte PETITE et centralisée** : 18 classes, ~24 références, toutes dans
  `com.perblue.heroes.cspine.*` (`NativeSkeleton`, `NativeSkeletonData`, `NativeAnimationState`,
  `NativeAtlas`, `NativeSkeletonRenderer`, loaders…). Le reste du jeu utilise CETTE API comme
  abstraction → point d'intégration unique.
- **Runtime Spine Java NON embarqué** dans l'APK (seul `spine/utils/TwoColorPolygonBatch` est
  présent) → à prendre sur Maven.
- **`spine-libgdx:3.6.53.1` disponible sur Maven Central** (dernière 3.6) → lit les `.skel`/
  `.atlas` 3.6 du jeu. ✅
- **Aucun natif x86/x86_64 public** : APKPure ne propose que `armeabi-v7a` et `arm64-v8a` (ARM).
  Et un natif **Android** (bionic) ne se charge pas sur un desktop **glibc** de toute façon.

## Options

### A — Spine en Java (shadow de `cspine` par `spine-libgdx`)  ★ RECOMMANDÉ
Réimplémenter les 18 classes `com.perblue.heroes.cspine.*` avec **la même API publique**, mais
adossées au runtime **Java `spine-libgdx 3.6.53.1`** (Maven), et les **shadow** sur le classpath
(technique déjà prouvée : `android.os.SystemClock`). Le jeu appelle la même API → **aucun patch
bytecode**, aucun natif.
- **Faisabilité : HAUTE.** Format standard + lib Maven qui le lit + empreinte petite/centralisée.
- **Portabilité : totale** — Windows, Linux **et** Android avec **un seul** code, zéro natif par
  architecture. C'est la seule option « un exécutable pour tout joueur ».
- **Testable ici** (PC Linux headless).
- **Effort : MODÉRÉ** — mapper chaque classe cspine → équivalent spine-libgdx (`NativeSkeletonData`
  →`SkeletonData`, `NativeSkeleton`→`Skeleton`, `NativeAnimationState`→`AnimationState`,
  `NativeAtlas`→`TextureAtlas`, `NativeSkeletonRenderer`→`SkeletonRenderer`/`SkeletonMeshRenderer`).
- **Risques** : reproduire fidèlement quelques spécificités du jeu — teinte 2 couleurs
  (`TwoColorPolygonBatch`, présent), effet « glitched » (`Skeleton_getVerticesAndBoundsGlitched`),
  `VertexWeightReport`. Le cœur (pose, anim, rendu) est standard.

### B — Android (natif ARM)  — bonus quasi-gratuit, mais Android seulement
Sur un vrai appareil/émulateur Android, le natif ARM (`armeabi-v7a`/`arm64-v8a`) est le bon.
- **Faisabilité : HAUTE** pour Android. Peu d'effort côté anim.
- **Limites** : ce n'est pas un `.exe` PC ; non testable dans cet environnement (PC Linux) ; il
  faut des splits **de la même version** que le base APK (12.1.0) — ⚠️ APKPure liste une autre
  version (build 5555120) → à ne pas mélanger.

### C' — reconstruire un natif desktop  — non recommandé
Écrire une lib C implémentant l'ABI JNI (~40 méthodes de `Native.java`) au-dessus du **spine-c
3.6** open source, compilée x86_64 Linux/Windows.
- **Faisabilité : MOYENNE-FAIBLE.** ABI connue, spine-c dispo, mais build C par plateforme, ABI à
  matcher, et il faut réimplémenter les bits custom PerBlue (glitch, vertex weight). Fragile.
- **Seul gain vs A : la vitesse.** Pas rentable pour ce jeu.

## ✅ VALIDÉ concrètement (test de dé-risque)
`spine-libgdx 3.6.53.1` (Maven) charge **un vrai `.skel` du jeu** en entier
(`world/units/soulless_brute/spine/soulless_brute.skel`) : version **3.6.41** reconnue,
**70 os, 31 slots, 1 skin, 6 animations** (attack, death, hit, idle, victory, walk). Aucun
« version mismatch ». ⇒ **le format des animations du jeu est 100 % lisible par le runtime
Java standard.** L'option A est confirmée faisable. (Test : `desktop-port/diag/SpineParseTest.java`.)

Le format de sommets du renderer du jeu (`NativeSkeletonRenderer`) = **teinte 2 couleurs**
(`a_position`, `a_light`, `a_dark`, `a_texCoord0`), exactement ce que produit
`com.esotericsoftware.spine.utils.TwoColorPolygonBatch` (déjà présent dans l'APK). ⇒ le rendu
Java est aligné avec le pipeline du jeu.

## Recommandation
**Option A.** C'est la seule qui livre **les 3 cibles (Windows/Linux/Android) avec un seul code
portable, sans natif**, testable ici, et sa faisabilité est confirmée HAUTE (Spine 3.6 standard,
`spine-libgdx` sur Maven, empreinte cspine petite et centralisée). Option B reste un bonus Android
parallèle. Ce n'est pas une rustine : on lit les **fichiers d'animation standards du jeu** avec une
bibliothèque **standard licenciée**, via la **même API** que le jeu attend.
