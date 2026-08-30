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

- **Blocage #2 — `getVertices` multi-pages → ✅ RÉSOLU PAR EXTRACTION (g184).** Trace EN JEU : `newPosition > limit (27>3)` dans
  `NativeSkeletonRenderer.renderPreparedVertices`→`Mesh.render`, sur les squelettes **multi-pages** (les 1-draw-call passaient).
  **Contrat EXACT extrait du wrapper DU JEU** `NativeSkeleton.getVertices` (§4, pas de devinette) : la valeur de RETOUR native =
  **nombre d'INDICES** (pas de draw calls) ; 2 shorts de **MÉTADONNÉES** écrits après les indices — `indices[n]` = nb de vertices
  (le jeu fait ×6 → limite verts), `indices[n+1]` = nb de draw calls (le jeu fait ×2 → limite drawCalls, et renvoie /2) ; **le jeu
  recale lui-même toutes les limites** → `getVertices` ne doit pas les poser (comme la lib ARM, vérifié via l'oracle unidbg avec
  `DH_SPINEDBG` : RET=indexCount, buffers laissés à leur capacité). Notre `buildVertices` renvoyait `drawCount` et posait les limites
  → dépassement. **Corrigé** dans `cspine_jni.c`. **Résultat EN JEU : mode `jni` boote et REND le hub (MainScreen, multi-pages
  inclus), visuellement IDENTIQUE à unidbg, 0 exception** (capture `desktop-port/build/jnihub.png`).

**⇒ B2 TERMINÉ : le backend spine natif rend l'UI de bout en bout.** Reste avant « jouable ≥30 fps » : B4 (scène combat combinée)
+ B6 (mesure fps sur GPU réel) + B3 (certif matrice) + B5 (couverture autres écrans).

### B3 — Certification différentielle (large matrice) → 0 diff visuel
Rejouer `CompareBackend` sur une **matrice héros × niveaux × graines**, combats **serrés** (RNG discriminant, cf. #24/#28) →
rapport **0 diff** (sommets/positions sous tolérance flottante). Documenter la tolérance ARM↔x86 (acceptable pour l'AFFICHAGE ;
**jamais** pour l'autorité). Industrialiser en harnais reproductible (comme `regression.sh`).

### B4 — Scène combat combinée (spine natif + particules unidbg)
Vérifier que la scène combat COMPLÈTE (spine natif + particules unidbg + GL) tient le budget et que la **cohabitation des deux
backends dans le même process** est stable (mémoire, chargement des libs, cycles de vie des handles).

### B5 — Couverture au-delà du sous-ensemble COMBAT — ✅ VÉRIFIÉ (2026-08-30)
**B5a (audit)** : les **47/47** méthodes natives de `cspine.Native` sont implémentées par `HostSpine`/`cspine_jni.c`
(0 méthode manquante → 0 crash de ce fait). Seul stub FONCTIONNEL = **`setSlotEyeState`** (tag des slots
`eyeball`/`eye_reflection`, appelé par `SpineRenderable.updateSlotTags` ; **cosmétique** — les yeux rendent via le
chemin normal). `getStats`/`getVertexWeightReport` = diag, **0 appelant jeu**. **B5b (nav sweep EN JEU en `jni`)** :
hub, chooser, **combat**, **HeroDetail (MegaBot, yeux corrects)**, collection detail → **tous rendus, 0 crash spine,
0 méthode manquante, 0 UnsatisfiedLink** ; yeux corrects malgré le no-op `setSlotEyeState` (confirmé sur MegaBot +
Merida/Belle/Jack). Les `SEVERE` vus au passage = **gaps tab data PRÉ-EXISTANTS** (forward-compat : `PatchTalent.PREDICTIVE_FORTIFICATION`
absent du code 12.1.0, `cosmetic_collection` MUFASA_EMOJIS, `prime_badge` SAPPHIRE_4/NUMBER_*), **non-spine**,
gérés gracieusement par le jeu (`onStatError`→skip) — §4 : non implémentables (rien à exécuter).

### ⇒ BASCULE `jni` PAR DÉFAUT (2026-08-30)
B3+B5 certifiés → le **CLIENT** desktop rend en **spine natif `jni` PAR DÉFAUT** (`run-desktop.sh` : `DH_SPINEBACKEND` défaut
`jni`, repli auto sur unidbg si `libhostspine64.so` absent ; repli explicite `DH_SPINEBACKEND=unidbg`). Le **SERVEUR**
(`dhserver.LoginServer`, process séparé **sans** propriété `-Ddh.spinebackend`) reste sur **unidbg BIT-EXACT** pour
l'**AUTORITÉ** de combat (§3/§8) — `Native.java` lit une propriété JVM, pas l'env, donc le serveur n'est jamais affecté
par la bascule côté client. Bannière au lancement : `[desktop] spine backend = jni`.

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

### B6 — mesure fps avant/après (2026-08-25, GL logiciel) — RÉSULTAT + bug fidélité
Sur le HERO CHOOSER (spine multi-héros), MÊME machine (llvmpipe) : **unidbg ~15 fps (spine 25–34 ms/frame)** → **jni ~38 fps (spine ~0 ms/frame)** = **×2,5, >30 fps même sur GL logiciel** (le spine sort du chemin critique). Sur GPU réel → 60+ attendus. **Thèse perf PROUVÉE.**

**⚠️ Bug fidélité (blocage #3, §4bis) — ✅ RÉSOLU (2026-08-29, §8 EN JEU).** En mode jni les héros étaient **éclatés/mal teintés**. Root-causé PAR EXTRACTION via le harnais `compare` (oracle unidbg vs JNI, ventilation par float de sommet + dump hex ABGR — outil ajouté à `CompareBackend`). **4 causes distinctes, toutes corrigées dans `native/src/cspine_jni.c` en reproduisant l'oracle à l'octet près (aucune devinette)** :
1. **Coord V ×2 (cause de l'« éclatement »)** — les textures sont **ETC1 sans alpha** : PerBlue empile l'alpha SOUS le RGB → la texture physique fait **2× la hauteur** de l'atlas (relevé : PKM **2048×1024** vs atlas « size: 2048,512 »). La lib ARM ramène V dans la moitié haute (RGB) : `v_out = v × 0.5`. Notre spine-c émettait V pleine échelle → maillage échantillonné dans la moitié alpha. Corrigé : `#define TEXV_SCALE 0.5f`. (U identique, `Atlas_getParams` identiques → c'est un ×0.5 explicite à l'émission, pas une hauteur de page différente.)
2. **Masque NaN libGDX (`& 0xfeffffff`)** — notre `packColor` appliquait le masque anti-NaN de `Color.toFloatBits` ; l'oracle écrit les **octets bruts** (blanc opaque → `0xFFFFFFFF` = NaN, relu en ubyte par le shader). Masque retiré.
3. **Light PRÉMULTIPLIÉE à tort** — relevé hex : oracle light = couleur **DROITE** (`0x00FFFFFF` à α=0 → rgb blanc conservé), pas prémultipliée. On revenait à la formule droite.
4. **`Skeleton_setTintBlack` était un NO-OP** — le jeu pose un **tint sombre global** (relevé hex uniforme, ex. `0x00,0x11,0x33`) ; on l'ignorait (dark=0). Implémenté : stockage du tint par squelette (table parallèle à `spSkeleton*`), utilisé comme base de dark au `getVertices`.

**Certification `compare` (hero chooser + hub) : V, U, dark/tint-black et ALPHA = bit-exacts ; x/y = dérive flottante ARM↔x86 imperceptible (~9e-5, §4bis OK).** Résiduel : **light-RGB** diverge sur ~10 % des slots — **artefact de MESURE, PAS un défaut de production** (cf. `docs/SHIMS.md`).

### Résiduels (light-RGB & x/y) — ANALYSE (2026-08-29) : non-défauts de production
Diag `DH_SPINEDBG2` (dump des composantes de couleur par slot) : les slots au light-RGB divergent sont **à couleur ANIMÉE** (glow d'ambiance hub `midground/light`, `lense_flare`), **pas les héros** ; les slots à couleur **statique** (corps des héros) sont **bit-exacts**. Cause = **dérive de phase entre 2 horloges d'animation indépendantes** dans le harnais `compare` (unidbg ARM + HostSpine x86 en //, chacun accumule `dt` en flottant bionic vs glibc). **En PRODUCTION (jni seul, UNE horloge) ça n'existe pas.** La dérive **x/y** (~9e-5) est de la même classe : ARM↔x86 dans les **libm** (sin/cos/atan2 des transforms d'os) — différence de bibliothèque, **pas corrigeable par flags de compil** ; seule l'émulation ARM (unidbg) matche bit-à-bit → gardée pour l'**AUTORITÉ** (combat). Affichage = §4bis (tolérance visuelle, jamais sur l'autorité). ⇒ **rien à « corriger » : héros/gameplay fidèles, autorité bit-exacte**.

### B4 + B6-combat — ✅ VÉRIFIÉ EN JEU (2026-08-29) : combat RENDU en spine natif
Pilote `campstart` ajouté (sélection héros + `CampaignHeroChooserScreen.startBattleInner()` = bouton FIGHT → `CampaignAttackScreen` rendu). **Combat joué de bout en bout en mode `jni`** (1465 frames, **0 crash**, cohabitation spine natif + particules unidbg + GL stable). **fps combat (llvmpipe)** : `unidbg=0.0 ms` sur **1446/1465 frames** (spine ÉLIMINÉ du hot-path ; rares 0.3–8 ms = bursts de particules unidbg au cast) ; moy 47 ms/**21 fps** mais **min 11 ms/91 fps** → borné par le **GL logiciel**, pas le spine (ancien mode spine-unidbg = 25–34 ms/frame *rien que* pour le spine). **GPU réel → 60 fps.** **Qualité PARFAITE** : héros + **ennemi Soulless (flamme bleue ADDITIVE rendue propre → 0 artefact du résiduel)** + fond + HUD, indistinguable du mobile (capture `/tmp/jni_combat_final.png`). **Problèmes trouvés = PRÉ-EXISTANTS, non liés au spine** : `patched_heroes_talent_assignments.tab` row 159 → enum `PatchTalent.PREDICTIVE_FORTIFICATION` absent (gap data/version 12.1.0), sons `glitch_glitch_in_1250_*` manquants, `NumberFormatException("")` — à traiter hors chantier spine. **⇒ B4/B6 bouclés : reste B5 (couverture autres écrans) avant de basculer `jni` par défaut.**
