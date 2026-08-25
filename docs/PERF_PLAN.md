# PERF_PLAN — Chantier B (performance combat) — ÉTUDE DÉTAILLÉE

> Objectif utilisateur : **pouvoir JOUER et TESTER** (finir l'audit A du plan Phase 2 exige de jouer). Cible **≥ 30 fps
> en combat** (viser 60 si possible). **Contrainte ABSOLUE : AUCUNE destruction de l'expérience VISUELLE ni du GAMEPLAY**
> vs le jeu original. Toute optimisation qui diverge = **bug**, pas une approximation (§2/§4bis).
>
> Ce document est une ÉTUDE (pas une implémentation). Il consolide les faits déjà mesurés (cf. `native/NATIVE_PLAN.md`,
> `native/unidbg/README.md`, `JOURNAL.md` 2026-07-11/16) et ordonne le travail restant.

## 1. Diagnostic — où passe le temps (faits mesurés, §8)

Le client d'origine rend le **spine** (squelettes animés) et les **particules** via du **natif C** (`com.perblue.heroes.cspine.Native`
/ `cparticle.Native` → lib `spine-native`). Notre port exécute la lib **ARM d'origine sous unidbg** (interpréteur ARM in-process).

- **Particules (`cparticle`) sous unidbg = VIABLE** : ~118 effets/frame @60fps ; une scène en a 5-30 → large marge. **Pas le goulot.**
- **Spine (`cspine`) = LE goulot** : ~**2111 µs/squelette** émulé (update 70 + apply 361 + worldTransform 524 + **getVertices 1218**)
  → ~**7 squelettes/frame @60fps**. Or un combat = **10 héros** (+ MainScreen ~12 persos) → budget explosé.
- **fps combat mesuré (profileur `dh.fps`)** : ~**9 fps** (unidbg spine+particules ~50 ms/frame ; GL llvmpipe headless = pire cas).
- **Ratio backend mesuré (per-call, hors `getVertices`, sur 2919 ticks / 3 combats)** :
  **unidbg (ARM émulé) 16 900 ms vs JNI natif x86 337 ms → ~50× plus rapide** ;
  soit ~**5,8 ms/frame** (unidbg) vs ~**0,12 ms/frame** (JNI natif) rien que pour l'anim squelettique.
  ⇒ avec le natif, **spine n'est plus le goulot** ; le coût résiduel devient le GL (llvmpipe logiciel en dev ; **GPU réel** sur
  poste joueur). Le 50× est CONSERVATEUR (exclut `getVertices`, l'appel le plus lourd, où l'émulation pénalise encore plus).

## 2. Architecture retenue (déjà DÉCIDÉE et largement BÂTIE)

| Préoccupation | Backend | Pourquoi |
|---|---|---|
| **Rendu spine (desktop, jouer/afficher)** | **spine natif x86_64** : VRAI runtime **spine-c officiel 3.6** (celui du jeu) + colle JNI d'origine (`native/src/cspine_jni.c`) compilés hôte (`libhostspine64.so`), **JNI réel** (pas d'émulation, pas de réécriture Java). `-Ddh.spinebackend=jni`. | Fidélité PAR CONSTRUCTION (vrai spine-c : mixing, tracks, déformation corrects d'office). ~50× plus rapide. |
| **Particules** | **unidbg** (moteur d'origine) | Déjà viable (~118/frame) → **identique** au jeu, rien à changer. |
| **Autorité de combat (SERVEUR)** | **unidbg** (ARM bit-exact) | La dérive flottante ARM↔x86 rend le JNI **non bit-identique** → rejouer l'issue dessus pourrait diverger (chaos). L'issue vient du **serveur** (§3) → **gameplay identique garanti**. |
| **Certification** | `-Ddh.spinebackend=compare` | Exécute les DEUX (unidbg=oracle RENDU + JNI=candidat COMPARÉ) → rapport 0-diff (sommets/positions sous tolérance). |

⇒ **desktop = natif pour l'AFFICHAGE (fidèle à l'œil) ; serveur = unidbg pour l'AUTORITÉ (fidèle au bit)**. Les deux exécutent du
**vrai code/données PerBlue** (§4), rien de réécrit (§2). C'est la clé de « zéro divergence » : le joueur voit du natif rapide,
mais l'issue du combat (dégâts, RNG, morts, récompenses) est celle du binaire ARM d'origine rejoué côté serveur.

**Composants EXISTANTS** : `dhbackend/jnispine/HostSpine.java` (backend JNI), `dhbackend/spine/CompareBackend.java` (certif
différentielle), `dhbackend/spine/JavaSpineBackend.java` (alternative Java, plus lourde à certifier), `native/build.sh` +
`build-hostspine.sh` (recette), oracle unidbg (`HeadlessCombat`), captures de référence `native/reference/shots/`.

## 3. Ce qui reste à faire (ORDONNÉ)

### B1 — Reproductibilité du backend natif ✅ (de-risqué le 2026-08-25)
`native/build.sh` (clone spine-c 3.6 officiel, compile objets + en-têtes JNI depuis `game.jar`) puis `build-hostspine.sh` (rename
mécanique des symboles JNI + unification des tables de handles → `libhostspine64.so`). **Vérifié ce jour en conteneur neuf** :
`libhostspine64.so` (250 Ko, 47 symboles HostSpine) + `spine-native64.so` (252 Ko) produits par gcc 13. Reste : **pinner** spine-c
sur le tag 3.6 exact, documenter prérequis (gcc, JDK) ; `.so` gitignoré/regénérable (§7). **Trou connu** : `spine-libgdx-perblue.jar`
(backend Java alternatif) n'a **aucune recette de build** → soit documenter sa provenance, soit ne retenir que le JNI natif.

### B2 — Boot JNI-autonome — **2 blocages trouvés EN JEU (§8, 2026-08-25)** ; #1 RÉSOLU, #2 root-causé
En lançant réellement `-Ddh.spinebackend=jni` (lib rebâtie), deux blocages distincts sont apparus l'un après l'autre :

- **Blocage #1 — ATLAS partagé cross-backend → ✅ RÉSOLU (g183).** Un atlas est utilisé par le spine (HostSpine/x86) ET les
  particules (unidbg/ARM). Créé seulement côté HostSpine, il manquait à la table unidbg → la lib ARM émettait
  « Bad handle type: Wanted ATLAS but is actually NONE » (crash boot sur un `.np`). **Fix** (`AtlasBridge`, glue plateforme, §1) :
  dual-create de l'atlas dans les deux moteurs + traduction du handle pour le chemin particules. **Boot passe, atteint MainScreen.**

- **Blocage #2 — `getVertices` multi-page → ⬜ ROOT-CAUSÉ, à corriger.** Trace EN JEU :
  `IllegalArgumentException: newPosition > limit (27 > 3)` dans `NativeSkeletonRenderer.renderPreparedVertices` → `Mesh.render`.
  Instrumentation (`DH_SPINEDBG`) : les squelettes à **1 seul draw call** rendent bien (ii=918) ; le crash est un squelette
  **multi-pages** (drawCount=3, counts 27/6/6, ii=39) sur le **2ᵉ draw call** (`position(27)`, mais limite=3). Avec la bonne
  limite (39) les 3 rendus devraient passer → notre `buildVertices`/glue ne laisse PAS le buffer d'indices du mesh dans l'état où
  la lib ARM le laisse pour un squelette multi-pages (contrat `getVertices`↔`Mesh` à répliquer exactement). **Jamais exercé avant**
  car le mode `compare` rend la sortie unidbg, pas celle du JNI. **Fix = matcher l'état/contrat des buffers de la lib ARM d'origine**
  (désassemblage `native/reference/libspine-native.so` = étape 5, §4 ; pas de devinette). Outil de diag ajouté (`DH_SPINEDBG`).

### B3 — Certification différentielle (large matrice) → 0 diff visuel
Rejouer `CompareBackend` sur une **matrice héros × niveaux × graines**, combats **serrés** (RNG discriminant, cf. #24/#28) →
rapport **0 diff** (sommets/positions sous tolérance flottante). Documenter la tolérance ARM↔x86 (acceptable pour l'AFFICHAGE ;
**jamais** pour l'autorité). Industrialiser en harnais reproductible (comme `regression.sh`).

### B4 — Scène combat combinée (spine natif + particules unidbg)
Vérifier que la scène combat COMPLÈTE (spine natif + particules unidbg + GL) tient le budget et que la **cohabitation des deux
backends dans le même process** est stable (mémoire, chargement des libs, cycles de vie des handles).

### B5 — Couverture au-delà du sous-ensemble COMBAT (Phase 0)
`HostSpine` couvre le **sous-ensemble combat**. Or d'autres écrans utilisent cspine : **MainScreen (~12 persos)**, HeroView,
vitrines… **Inventorier tous les écrans spine** et étendre le backend JNI pour les couvrir. Ajouter les méthodes omises au besoin :
`setSlotEyeState` (expressions des yeux = fidélité cosmétique §4bis), `getStats`/`getVertexWeightReport` (diag). Sinon ces écrans
resteraient sur unidbg (lents mais fonctionnels) — à trancher écran par écran.

### B6 — Mesure fps EN JEU bout-en-bout (§8) → cible ≥ 30 fps
Avec spine natif + particules unidbg + **GL réel**, mesurer le fps combat (`-Ddh.fps` : fps glissants + chrono unidbg vs reste).
⚠️ **Sur ce conteneur, GL = llvmpipe (logiciel, pire cas)** → peut ne pas atteindre 30 même avec le spine natif (le GL devient
dominant). Sur **GPU réel (poste joueur)** le GL est ~gratuit → ≥30 (voire 60) attendu. ⇒ mesurer sur **matériel représentatif** ;
documenter le caveat GL et ne PAS conclure « échec perf » sur un rendu logiciel.

### B7 — Optimisations résiduelles (SEULEMENT si < 30 fps après natif)
- **`getVertices`** (appel le plus lourd) : cache des squelettes idle, LOD, réduction des appels/frame.
- **JIT** : re-tester **C2** (plantait sur le bytecode dex2jar ; `game-framed` corrige les frames → re-tester par étapes) ;
  **dynarmic** (crash NEON `vldr d16`) réparable pour accélérer l'émulation restante (particules) — non requis si natif suffit.
- **GL** : garantir le chemin GPU réel côté joueur (llvmpipe = dev only) ; qualité spine réglable (chantier C, paramètres).

### B8 — Garde-fous de fidélité (contrainte user : ZÉRO divergence)
- **Gameplay** : l'issue de combat = **serveur unidbg** (bit-exact) → dégâts/RNG/morts/récompenses **identiques**.
- **Visuel** : certif sommets vs oracle (B3) **+** comparaison de captures combat vs `native/reference/shots/` (§4bis). Tout écart
  visuel = bug à corriger à la source, jamais toléré.
- **Particules** = moteur d'origine (unidbg) → **identiques**.

## 4. Chemin critique & risques

**Chemin critique jouable** : B1 ✅ → **B2 (boot JNI-autonome)** → B6 (mesure GPU réel) → jouable ≥30. B3 (certif) + B5
(couverture) + B8 (garde-fous) en parallèle avant de basculer `jni` par défaut. B7 seulement si nécessaire.

**Risques / points durs** :
1. **B2** = le vrai bloquant technique restant (registre de handles au boot natif).
2. **Dérive flottante** ARM↔x86 → borner une tolérance VISUELLE (jamais pour l'autorité) ; risque de banding/écarts subtils à
   certifier (déjà travaillé : layout `[a,c,b,d,x,y]`, drawCalls).
3. **GL headless (llvmpipe)** fausse la mesure fps en conteneur → conclure uniquement sur GPU réel.
4. **Couverture** : ne pas oublier MainScreen et les autres écrans spine (pas que le combat).
5. **Reproductibilité** : pinner spine-c 3.6 ; recette manquante du jar Java (si on garde ce backend).

## 5. Verdict

L'essentiel du chantier B est **déjà bâti et le gain est prouvé par la mesure** (natif ~50× ⇒ 60 fps atteignable, spine sorti du
chemin critique). Ce n'est PAS une réécriture : c'est le **même spine-c que le jeu**, compilé au lieu d'être émulé, l'autorité
restant sur le binaire ARM d'origine (serveur) → **fidélité visuelle ET gameplay préservées par construction**. Le travail restant
est surtout **B2 (débloquer le boot natif)** puis **mesurer/certifier/couvrir** — pas d'invention, pas d'approximation.
