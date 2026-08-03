# Vérification HEADLESS maximale via le code du jeu — plan directeur + SUIVI

> **Pour l'agent (surtout après compression) : LIS CE DOCUMENT EN ENTIER avant de continuer ce chantier.**
> Il définit l'objectif, l'architecture de vérification, ce qui est FAIT / EN COURS / À FAIRE (§SUIVI, à
> maintenir à jour à CHAQUE incrément), et les règles incontournables. Ne rien réinventer : suivre le plan.

## Objectif

On a le **code du jeu** (`libs/game.jar`, décompilé) : côté serveur ET côté client. On l'utilise déjà pour la
LOGIQUE serveur (PRINCIPLES §3). **On veut l'utiliser aussi pour EXÉCUTER les vérifications du CLIENT contre nos
réponses/état, headless**, afin de rattraper AVANT l'in-game tout ce qu'un contrat statique d'un seul écran ne
voit pas. But : **monter la barre du « vert » headless au maximum, réduire l'in-game au strictement RENDU-only.**

La vérif EN JEU reste **obligatoire** (règle utilisateur) mais devient le **dernier filet** (rendu/GL), pas le
premier. Statut : headless prouvé = 🟢 ; ✅ seulement après l'in-game.

## Pile de vérification (du moins cher au plus cher)

| Niveau | Outil | Attrape | État |
|---|---|---|---|
| 0. Contrat statique | `tools/screentool/contract.sh` (#73) | champs à peupler, handlers manquants, gate | ✅ fait |
| 1. Round-trip wire | `server/smoke/WireCheck` (#73) | typage wire faux (explose à l'écriture) | ✅ fait |
| 2. **Oracle CLIENT headless** | `server/smoke/ClientOracle` (#74) | l'état/réponse serveur fait **planter ou refuser** le code CLIENT (requirements, gates, validations d'envoi) | 🔨 en cours |
| 3. Combat headless | `HeadlessCombat` (#27, `CombatSpikeDriver`) | logique de combat client (issue, dégâts) | ✅ fait (existant) |
| 4. **EN JEU (rendu/GL)** | client réel piloté | rendu visible/cliquable, layout, VFX, **erreurs de la voie de rendu** (ex. `spawnParticles`) | ♾️ irréductible |

## Les 3 leviers (ce chantier #74)

**A. Contrat COMPLET du mode (statique).** Graphe de références des messages sur TOUT le jar → pour chaque
message, qui l'ENVOIE / le LIT → regrouper automatiquement tous les écrans + helpers d'un mode. Supprime la
« Limite 1 » (portée par-classe) de `SCREEN_PIPELINE.md` : plus besoin de lister les classes à la main.

**B. Oracle CLIENT headless (le gros gain).** Exécuter, sur NOTRE `User`/réponse, les vérifs **côté client** que
le jeu contient et qui prennent un `IUser`/état :
- **requirements/gates** (`QuestHelper.getUnlockedDailyQuests(IUser)`, `hasUnclaimedDailyQuests(IUser)`,
  `isUnlocked(int, IUser)`, `Unlockable`…) — **aurait attrapé le crash R1** (`HasEnoughCollectionHeroes.isSatisfied`
  → `list.get(hero.getStars())` hors bornes, cf. g55) ;
- **validations d'ENVOI** du client (`ChestHelper.validateChestPurchase`, `WarClientHelper.doStartWarAttack`…) =
  miroir anti-triche + « le client accepterait-il / planterait-il ? ».
Un état serveur qui fait lever une de ces méthodes = **défaut attrapé headless**, sans in-game.

**C. Logique du jeu headless là où c'est GL-free.** `HeadlessCombat` (déjà). Étendre aux `*Helper`/modèle des
modes quand séparable du GL.

## Frontière IRRÉDUCTIBLE (reste in-game)

Le **rendu GL** : champ mappé sur un acteur **visible/cliquable**, layout, cibles de tap, VFX, et les erreurs de
la **voie de rendu** (le crash `spawnParticles` vivait UNIQUEMENT dans l'`AttackScreen` rendu ; `HeadlessCombat`
ne l'a jamais appelé). Aucune analyse du code du jeu ne le remplace.

## Règles incontournables (rappel)

- §3/§4 : on EXÉCUTE le code du jeu, on n'invente JAMAIS une règle/valeur. L'oracle client EXÉCUTE les vrais
  prédicats du jeu, il ne les réimplémente pas.
- La vérif EN JEU reste obligatoire (dernier filet rendu). Headless = 🟢, pas ✅.
- Round-trip DB (persistance) dans chaque test de handler. Resync des champs hors `this.extra`.

---

## SUIVI DES TRAVAUX (à mettre à jour à CHAQUE incrément)

Légende : ✅ fait · 🔨 en cours · ⬜ à faire

### Levier B — Oracle CLIENT headless (`ClientOracle`)
- ✅ **B1. Harnais `ClientOracle`** (`server/smoke/ClientOracle.java`) : batterie de vérifs client sur un `User`,
  capture les exceptions, `assertClientRenders(u)` lève en listant les échecs. **Découverte + shim** : les stats
  du jeu (`QuestStats.getDailyQuestIDs`…) ont un garde `currentThread == GameMain.MAIN_THREAD` (nul headless →
  garde violé). `becomeMainThread()` pose le champ static sur le thread courant (shim de HARNAIS, §4) → le vrai
  code client tourne headless.
- 🔨 **B2. Vérifs « le hub rend sans planter »** : ✅ STABLES headless = `getUnlockedAchievements`,
  `getWeeklyDailyQuestsComplete` (self-test vert, intégré régression). ⏳ En attente fixture ↓.
- ⬜ **B2b. FIXTURE utilisateur complète** : `getUnlockedDailyQuests`/`hasUnclaimedDailyQuests` (LA voie du crash
  R1, à fort intérêt) NPE car notre `User` headless n'a pas les `IUserChallengeData` (données de défi/City Watch
  que le BootData réel fournit). Construire une fixture User complète (challenge data + ce que le vrai boot pose)
  → déplacer ces 2 vérifs de `HUB_RENDER_PENDING_FIXTURE` vers `HUB_RENDER`. **Alors l'oracle attrape R1 headless.**
- ⬜ **B3. Preuve anti-régression R1** : test héros 6★ + ère R1 → l'oracle DOIT flaguer le crash (gate sur B2b).
- ⬜ **B4. Miroir des validations d'ENVOI** : par action de mode, exécuter la validation cliente
  (`validateChestPurchase`, `doStartWarAttack`…) sur notre état → « le client enverrait-il / planterait-il ».
- ⬜ **B5. Intégration** : `assertClientRenders(user)` dans les tests de handler (fait pour le self-test/régression).

### Levier A — Contrat complet du mode (graphe de messages)
- ⬜ **A1. `ModeGraph`** : scan de TOUT le jar → map message → {émetteurs, lecteurs} ; regroupement par mode.
- ⬜ **A2. Intégration** : `contract.sh --mode <nom>` → union automatique de tous les écrans/helpers du mode.

### Levier C — Logique headless étendue
- ⬜ **C1. Recenser** les `*Helper`/modèles de mode exécutables headless (hors GL) par mode.
- ⬜ **C2. Harnais** de « traversée de réponse » : passer notre réponse dans le modèle client GL-free du mode.

### Application aux modes (#4) — après la pile de vérif
- ⬜ SURGE · ⬜ CITY WATCH · ⬜ CHALLENGES · ⬜ HEIST (chacun : contrat → scaffold → logique jeu → WireCheck +
  ClientOracle + HeadlessCombat → in-game).

### Historique des incréments
- 2026-08-03 (g58) : création de ce document + démarrage B1 (harnais `ClientOracle`).
