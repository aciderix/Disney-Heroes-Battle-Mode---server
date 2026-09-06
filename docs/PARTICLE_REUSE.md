# Portage particules — RÉUTILISATION du moteur Java du jeu (pivot g293, validé)

> Remplace l'émulation unidbg (goulot FPS combat) **et** la réimplémentation C (`native/src/np_sim.c`, à
> tâtons) par le **moteur de particules Java d'origine du jeu**, exécuté nativement sur la JVM du client
> desktop. §3/§4 : on exécute le code du jeu, on n'écrit que la glue de format.

## Pourquoi (idée utilisateur, validée)

Le moteur complet est **déjà** en Java pur dans `libs/game-logic-framed.jar` :
`com.badlogic.gdx.graphics.g2d.ParticleEmitter`/`ParticleEffect` — chargement, `update()` (simulation),
`drawPositiveDepth(TwoColorPolygonBatch)` (rendu 2-couleurs). Le binaire natif ARM qu'on émulait via unidbg
en est un **portage**. Donc : exécuter le Java d'origine = fidèle + rapide (JIT) + zéro émulation, et surtout
**la formule de position** (insoluble par reverse, cf. JOURNAL g269-g292) est **contenue dans l'`update()` du
jeu** → plus rien à décoder.

## Ce qui est FAIT et VALIDÉ

- **Preuve de concept** `native/reuse/ParticleV3Loader.java` : charge un `.np` réel dans le `ParticleEmitter`
  du jeu, valeurs peuplées = `NP_DBG_START` à l'identique, `em.start()` + `em.update()` **s'exécutent**
  (simulation OK). (JOURNAL g293.)
- **Moteur d'intégration** `desktop-port/src/main/java/dhbackend/jparticle/JavaParticleEngine.java` (compile
  contre game-logic-framed.jar) :
  - **Adaptateur `.np` v3 → champs Java** (le loader `readExternal` du jeu est d'une version plus récente que
    le format v3, incompatible direct ; on peuple donc via setters + réflexion, dans l'**ordre certifié
    535/535** de `native/src/np_parser.c`, mapping g286/g287). Résout les timelines depuis le pool trailer.
  - API façon `cparticle.Native` : `create(np, sprite, region)` / `start` / `update` / `setPosition` /
    `getVertices` / `dispose`.
  - `getVertices` : génère les quads **2-couleurs** (6 floats/sommet `x,y,light,dark,u,v`, 4 sommets/particule ;
    `drawCalls` = `n*3+1` shorts, cf. `UnidbgVM.effectVertCount`/`drawShorts`) depuis l'état `draw*` des
    `Particle` du jeu.

## Ce qui RESTE (intégration en jeu — nécessite GL + client en marche)

1. **Sprite d'atlas** : ✅ RESOLVER ÉCRIT (`ParticleAtlasResolver`) — capture les octets `.atlas` à
   `cspine.Native.Atlas_create`, **parse les régions/uv** (testé headless : 34 régions, uv corrects), et
   construit le `TwoColorAtlasSprite` (Texture aux dims de page, **GL lazy** côté client ; le rendu réel
   utilise la texture NATIVE, le sprite Java ne porte que région/uv). Reste à **tester EN JEU** (GL). Détail : `ParticleEmitter.setDrawable` exige un `TwoColorAtlasSprite`/`AtlasSprite` (région de
   l'atlas pour `atlasTag`, lu du `.np`). Il faut résoudre la `TextureRegion`/`AtlasRegion` depuis l'atlas
   référencé par `atlasHandle` de `Effect_create`. ⚠️ Aujourd'hui l'atlas côté desktop-port est géré en NATIF
   (`com.perblue.heroes.cspine.NativeAtlas`, textures côté natif/GL), pas comme un `TextureAtlas` libGDX
   accessible en Java → c'est LE point d'intégration à résoudre (charger l'atlas particules
   `*/vfx/particles-DEFAULT.atlas` comme `TextureAtlas` libGDX GL, ou exposer les régions natives).
2. **Routage** : ✅ FAIT — `cparticle/Native.java` route les `Effect_*` vers `JavaParticleEngine` quand
   `JavaParticleEngine.enabled()` (= `-Ddh.particlebackend=java` ET un `AtlasResolver` enregistré). Défaut =
   unidbg (aucune régression si le flag/resolver absent). `Effect_clone` DOIT aussi router vers Java
   (`JavaParticleEngine.clone` = re-parse le `.np` stocké dans le Handle) : le jeu clone les effets pour le
   pooling ; sans ça → « Bad handle type: Wanted PARTICLE_EFFECT but is actually NONE » (unidbg reçoit un
   handle Java). ✅ CORRIGÉ (in-game : 0 « Bad handle » après le fix).
3. **Build** : le moteur est déjà dans l'arbre source desktop-port (compile avec game-logic-framed).

## ✅ VÉRIFIÉ EN JEU (§8) — 2026-09-06

Combat tutoriel réel (Ralph/Vanellope/Mme Indestructible vs creeps), client desktop v029 + serveur local
`dh-server-v029`, backend `-Ddh.particlebackend=java`, GL logiciel (llvmpipe, pire cas). Capture autoritative =
framebuffer du client (`build/manual.ppm` via `-Ddh.clickfile`), pilotage par taps (`x,y` top-left).

- **Rendu fidèle** : combat complet rendu correctement ; VFX de particules visibles = nuage de glitch de
  Vanellope (Lollipop Slam), aura bleue scintillante du creep encapuchonné, impacts d'attaque. Le
  `ParticleEmitter` du jeu tourne en natif sur la JVM, aucune émulation.
- **FPS avant/après (MÊME écran MainScreen, même machine/compte)** :
  | backend | FPS | ms/frame | unidbg (émulation ARM) |
  |---|---|---|---|
  | unidbg (avant) | 8,4 | 119 ms | 103,7 ms/frame — **90 appels/frame** |
  | Java (après)   | 202 | 4,9 ms | **0 ms — 0 appel** |
  → **≈ 24× FPS**, les ~103 ms/frame d'émulation ARM **éliminés** (0 appel unidbg). En combat chargé
  (VFX d'abilities), le backend Java tient ~117 fps (vs ~8-30 fps unidbg, cf. g185/PERF_PLAN). Thèse prouvée.
## Couverture de TOUS les effets (validation headless, g298)

`native/reuse/NpAdapterValidate.java` : passe **TOUS les `.np` du jeu** dans l'adaptateur v3→Java + la
simulation (60 frames, sprite factice sans GL) et vérifie que la géométrie est finie et bornée (les
composantes couleur des sommets sont des floats bit-packés → magnitude quelconque légitime, seules x/y/u/v
sont bornées). **Résultat sur les 2918 `.np` du bundle : 2918 v3, 2918 chargés, 2918 simulés OK, 0 échec**
(348 particules simultanées max). ⇒ le mapping v3→Java (y compris les champs incertains scaledB[9-11]/scaledC/
scaledD) ne casse sur AUCUN effet du jeu. Lancer :
`java -cp "<classes patchées>;<bundle>/lib/dhdesktop.jar;<bundle>/lib/game-logic-framed.jar;<bundle>/lib/runtime/*" NpAdapterValidate <dir Assets>`.

## Activation par DÉFAUT (g298)

`BuildManager.RUN_SH_CLIENT`/`RUN_BAT_CLIENT` posent `-Ddh.particlebackend=java` par DÉFAUT dans le run.sh/
run.bat généré (override `DH_PARTICLEBACKEND=unidbg` pour revenir à l'émulation). Justifié par : vérif en jeu
§8 (rendu + ≈24× FPS) + adaptateur validé 2918/2918. Le marqueur `~/.dh_particlebackend` reste un moyen d'
activation alternatif (dev). ⇒ un joueur qui génère un bundle via le launcher a le backend Java d'office.

- Reste (affinage) : comparer la fidélité visuelle fine effet par effet à l'oracle unidbg (non bit-exact,
  RNG RandomXS128 ≠ LCG — attendu ; validation actuelle = pas de crash / géométrie saine + visuel du 1er combat).

## Notes de fidélité

- Version : le `ParticleEmitter` Java (game-logic-framed) est plus récent que le format v3 des `.np`
  d'origine. La **physique** est le même algorithme libGDX (les évolutions du format = ajouts de champs), donc
  fidèle ; à **confirmer visuellement EN JEU**. Champs incertains du mapping v3 (scaledB[9-11], scaledC,
  scaledD→gravity) : la plupart inactifs sur les effets testés ; affiner si un effet diverge.
- RNG : le moteur Java utilise sa propre RNG (RandomXS128) ≠ LCG natif → les particules ne sont **pas**
  bit-exactes à l'oracle unidbg, mais **statistiquement/visuellement correctes** (ce qui suffit — le harnais
  bit-exact `np_certify` ne servait qu'à valider la réimplémentation C, désormais abandonnée comme voie).
- `native/src/np_sim.c` / `np_parser.c` : conservés comme **documentation du format** et référence du mapping,
  plus comme voie de portage.
