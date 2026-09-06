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

1. **Sprite d'atlas** : `ParticleEmitter.setDrawable` exige un `TwoColorAtlasSprite`/`AtlasSprite` (région de
   l'atlas pour `atlasTag`, lu du `.np`). Il faut résoudre la `TextureRegion`/`AtlasRegion` depuis l'atlas
   référencé par `atlasHandle` de `Effect_create`. ⚠️ Aujourd'hui l'atlas côté desktop-port est géré en NATIF
   (`com.perblue.heroes.cspine.NativeAtlas`, textures côté natif/GL), pas comme un `TextureAtlas` libGDX
   accessible en Java → c'est LE point d'intégration à résoudre (charger l'atlas particules
   `*/vfx/particles-DEFAULT.atlas` comme `TextureAtlas` libGDX GL, ou exposer les régions natives).
2. **Routage** : ✅ FAIT — `cparticle/Native.java` route les `Effect_*` vers `JavaParticleEngine` quand
   `JavaParticleEngine.enabled()` (= `-Ddh.particlebackend=java` ET un `AtlasResolver` enregistré). Défaut =
   unidbg (aucune régression si le flag/resolver absent). Reste à enregistrer un `AtlasResolver`
   (`JavaParticleEngine.setResolver(...)`) côté client/launcher (avec GL) : `spriteFor`/`regionFor(atlasHandle,
   atlasTag)` — c'est le point (1) ci-dessus (résoudre la région d'atlas).
3. **Build** : le moteur est déjà dans l'arbre source desktop-port (compile avec game-logic-framed).
4. **Vérification EN JEU (§8, obligatoire)** : lancer un combat réel, comparer visuellement à l'unidbg
   (oracle) et mesurer le FPS (le gain attendu = même levier que spine g257).

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
