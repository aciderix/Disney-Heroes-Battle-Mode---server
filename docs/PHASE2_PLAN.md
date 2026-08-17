# PHASE 2 — plan global (après FRANCHISE_TRIALS + fin des composants SPECIAL_EVENTS)

> **Document de PLANIFICATION** (demandé par l'utilisateur, 2026-08-17). La Phase 2 **ne nous concerne pas dans l'immédiat**
> — on ne bâcle pas les tâches en cours (FRANCHISE_TRIALS puis les composants SPECIAL_EVENTS restants). Mais on la fixe ICI
> pour ne rien oublier. Liste **enrichie** de points non cités explicitement par l'utilisateur mais nécessaires (il a demandé
> de compléter). **Toutes** les tâches Phase 2 respectent les mêmes règles (`docs/PRINCIPLES.md` §1-§8 ; vérif EN JEU ;
> perfs NON destructrices de la fidélité du portage). Rien n'est « fait » tant que non prouvé + validé.

## Où on en est (fin de Phase 1, à la création de ce doc)
- Hub + quasi tous les modes #72 livrés & **vérifiés EN JEU** (cf. `docs/EXPLORATION.md` : seul FRANCHISE_TRIALS reste ⬜).
- Moteur SPECIAL_EVENTS live-ops (rotation fidèle + overrides `AdminEvents`, persistance shard).
- Pile complète fonctionnelle : client d'origine (spine/particules unidbg) → serveur autoritatif → persistance SQLite → affichage.

## Séquence restante avant Phase 2 (tâches ACTUELLES — prioritaires, non bâclées)
1. **FRANCHISE_TRIALS / TEAM_TRIALS** (cf. `docs/FRANCHISE_TRIALS.md`) — incréments 0→7, vérif EN JEU.
2. **Composants SPECIAL_EVENTS restants** (une fois trials validé) : `DropBonus` opérateur (fait, builder dispo), discounts
   marchands/coffres (`ChestDiscount`), `AdditionalChances`, `Contest`, `TeamLevel`, `ExtraChest`, etc. — un builder par
   composant, même patron `ServerEvents` + `AdminEvents`.
3. **Bilan de couverture** : re-parcours d'`EXPLORATION.md` — tout ⬜/🟢/🚧/PARTIEL résiduel tranché (prouvé ou documenté §8).

---

## PHASE 2 — chantiers (planifiés, pas encore engagés)

### A. VÉRIFICATION GLOBALE & CHASSE AUX OUBLIS (fondation de la Phase 2)
- **Audit de couverture exhaustif** : chaque écran/mode/onglet du hub + menu ☰ rejoué de bout en bout ; tout PARTIEL/🟢/NO-OP
  du registre `docs/SHIMS.md` re-statué (RÉEL, ou PARTIEL documenté avec risque, ou corrigé). Aucune supposition (§8).
- **Audit ANTI-TRICHE** : revue systématique des chemins client-autoritatifs (combat/loot #25, coûts, chances, resets) —
  le serveur RECALCULE et VALIDE avant d'accorder ; lister ce qui reste « client-trusted » (loot §4bis) et son risque.
- **Audit PERSISTANCE (§6)** : pour chaque handler, tout champ muté hors `this.extra` est-il resync ? round-trip wire+DB
  systématique (`WireCheck`) ; migrations de schéma DB testées (chargement d'anciennes lignes).
- **Audit FIDÉLITÉ (§4bis)** : comparaison écran par écran à des captures du jeu d'origine ; aucun écart visuel/fonctionnel toléré.
- **Registre des GAPS** : consolider les gaps connus (logique absente du jar 12.1.0 : `CLAIM_COSMETIC_COLLECTION`, profanité,
  filtres, etc.) — documentés §2/§4, décision utilisateur sur chacun (accepter / contourner fidèlement / attendre).

### B. PERFORMANCE DU PORTAGE (Windows / Linux) — NON DESTRUCTIVE (fidélité intacte)
- **Combat = point chaud connu** : `unidbg` (émulation ARM spine/particules) domine (~80 ms/frame ; ~9 fps headless sans GPU).
  Pistes NON destructrices (aucune divergence vs jeu d'origine, §4/§4bis) :
  - **Oracle-certification Opt.3** (déjà cadré `docs/SERVER_PLAN.md` §D) : backend spine **Java** (runtime `spine-libgdx-perblue.jar`)
    remplaçant la sous-surface cspine du combat, **CERTIFIÉ bit-fidèle contre l'oracle unidbg** (matrice héros/niveaux/graines,
    combats serrés RNG-sensibles). Gain visé ~9 s → <100 ms/combat. **Jamais shipper non certifié.**
  - Réduction des appels unidbg/frame (cache de transformations d'os stables, batch), JIT/warm-up, C2 (prudence : plantait sur
    le bytecode dex2jar → à re-tester par étape).
  - GPU réel quand disponible (le headless llvmpipe est le pire cas ; sur poste joueur, GPU natif).
- **Serveur** : profilage des handlers (sérialisation wire, verrous store↔user — cf. fix interblocage arène), pool de connexions,
  éviter les rechargements de stats à chaud (warm-ups déjà en place : GuildStats/PatchStats).
- **Démarrage** : temps de boot client (extraction assets, chargement spine) — cache, lazy-load ; mesuré, non deviné.
- **Contrainte** : toute optimisation est validée par A/B (comportement identique) ; une divergence = bug, pas une approximation.

### C. FRONT-END JOUEUR — intégration des fonctionnalités serveur au LAUNCHER du portage
- **Sélection / connexion serveur** intégrée au launcher desktop (`desktop-port`) : héberger son serveur, **lister / rejoindre**,
  **mot de passe optionnel**, **mode sécurisé** (multi-serveur prévu dès l'origine, §5 / `docs/ARCHITECTURE.md`).
- **Écran de liste de serveurs** (favoris, ping, nb joueurs, version), **création de compte / login** multi-serveur.
- **Paramètres** : résolution, perfs (qualité spine), langue, redirection `ServerType.LIVE` → serveur choisi (aujourd'hui
  câblé en dur `127.0.0.1:8080` via `content_server.py`).
- **UX d'onboarding** self-hosting : bouton « héberger » qui lance le serveur local + le launcher, guide pas à pas.

### D. BACKEND / FRONT-END d'HÉBERGEMENT & GESTION DE SERVEURS
- **Panneau opérateur** (web ou CLI unifié) englobant les outils admin existants (`AdminClock`, `AdminWar`, `AdminInvasion`,
  `AdminMail`, `AdminEvents`, `PortEnterAdmin`…) : horloge/ère de contenu, événements live-ops, guerres/invasions, courrier,
  modération, sauvegardes.
- **Gestion multi-shard** : création/suppression de shards, isolation des données, bus inter-shard (chat global/VIP, classements).
- **Découverte de serveurs** (annuaire optionnel) + enregistrement/heartbeat ; sécurité (auth, TLS, rate-limit).
- **Observabilité** : logs structurés, métriques (latence handler, RNG divergences #25, erreurs), alerting, tableaux de bord.
- **Sauvegarde / restauration** des DB (snapshots, migrations versionnées, rollback) ; **arrêt gracieux** + rechargement à chaud
  de la config opérateur (aujourd'hui : redémarrage serveur requis après `AdminEvents` — à améliorer).

### E. TESTS & INTÉGRATION À LA VERSION APK (mobile d'origine)
- **Compatibilité protocole** : le serveur parle le wire de l'APK 12.1.0 (codec/`MessageFactory` du jeu, déjà réutilisés) →
  vérifier qu'un **vrai client APK Android** (émulateur ou device) se connecte au serveur (redirection `/login`), boot, joue.
- **Parité client desktop ↔ APK** : mêmes messages, même persistance ; différences de plateforme (spine natif ARM sur device
  vs unidbg sur desktop) — le serveur est agnostique (il ne rend rien).
- **Versionnage** : gérer d'autres versions d'APK (le service multi-ère `content.<shard>.tab` + stat-sync ouvre la voie —
  cf. `docs/EXPLORATION.md` backlog « service multi-ère »).

### F. TESTS EN CONDITIONS RÉELLES — SERVEURS INTER-MACHINES via INTERNET
- **Réseau réel** : latence/perte/MTU (la « fenêtre de démarrage » qui perdait des messages est documentée `docs/SHIMS.md` —
  le vrai client passe par `/login` HTTP d'abord ; à re-valider sur Internet réel, pas seulement loopback).
- **NAT / pare-feu / port-forwarding / UPnP** ; **TLS** de bout en bout ; **DNS**/annuaire.
- **Charge / stress** : N joueurs simultanés par shard, N shards, combats concurrents (le fix interblocage arène + la
  sérialisation hors verrou sont des prérequis) ; **soak tests** (24 h+), reconnexions, coupures.
- **Multi-région** : deux machines distantes (ex. FR ↔ autre continent), mesure de latence in-game, cohérence PvP/arène/guerre.
- **Sécurité en conditions réelles** : tentatives de triche réseau (rejouer/forger des messages), rate-limit, bans.

### G. QUALITÉ / OUTILLAGE TRANSVERSE (enrichissements)
- **CI** : exécuter `server/smoke/regression.sh` (126+ tests) + `WireCheck`/`ClientOracle` automatiquement (garde-fou non
  destructif) ; artefacts régénérables par script (§7).
- **Reproductibilité build** : `decompile.sh`/`extract_game_data.sh`/`reframe` documentés + pinning des versions.
- **Documentation self-hoster** : guide d'installation/hébergement, prérequis, dépannage.
- **Légal / copyright** : le serveur réutilise les classes+données du jeu (non committées, régénérables §7) — cadre d'usage
  PRIVÉ à documenter clairement (pas de redistribution d'assets protégés).
- **Migration de sauvegardes** joueur entre versions de schéma (déjà : colonnes BLOB ajoutées par `ALTER` — à généraliser +
  tester).

## Principes Phase 2 (rappel, incontournables)
- **§1** modifs minimales du jeu ; **§2** aucune rustine (perf certifiée, pas « approximée ») ; **§3** serveur exécute le code
  du jeu ; **§4/§4bis** rien d'inventé, fidélité vérifiée ; **§5** multi-serveur ; **§6** persistance complète ; **§7**
  reproductibilité (identifiant de modèle JAMAIS dans un artefact) ; **§8** vérif EN JEU / conditions réelles obligatoire.

## Statut : PLANIFIÉ (2026-08-17). Non engagé — priorité aux tâches Phase 1 en cours (FRANCHISE_TRIALS puis SPECIAL_EVENTS).
