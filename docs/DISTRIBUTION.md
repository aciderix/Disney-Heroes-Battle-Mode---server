# DISTRIBUTION & IDENTITÉ JOUEUR — décisions de design (consignées, à implémenter Phase 2)

> Décisions de cadrage demandées par l'utilisateur. Rien n'est encore implémenté ici : ce document FIXE la direction
> (le « comment on fera ») pour les chantiers Phase 2 concernés (C launcher, D backend/admin, E intégration APK).
> Cohérent avec `docs/PRINCIPLES.md` : §4 (tout vient de l'APK, par extraction/outil), §7 (artefacts lourds régénérables,
> non committés), §5 (multi-serveur / self-host).

## 1. Modèle de distribution : on livre le LOGICIEL, jamais le jeu

**On ne distribue jamais le jeu ni ses fichiers** (assets, `.tab`, bytecode, natifs — tout est la propriété de PerBlue).
On livre **uniquement notre logiciel** (la couche plateforme + serveur + outillage). **L'utilisateur fournit lui-même l'APK**
*Disney Heroes: Battle Mode* dans **sa dernière version publiée** (qu'il télécharge de son côté). Le logiciel se charge alors
de **générer à la demande**, à partir de CET APK, ce que l'utilisateur choisit :

| Cible choisie | Ce que le logiciel génère (depuis l'APK fourni) |
|---|---|
| **Port desktop Windows** | `game.jar` (`tools/decompile.sh`) → reframe (`game-framed.jar` / `game-logic-framed.jar`) + `game-data/` (`tools/extract_game_data.sh`) + natifs → build du client desktop |
| **Port desktop Linux** | idem, cible Linux |
| **Serveur (self-host)** | `game-framed.jar` + `game-data/stats/*` + compilation `server/java` → serveur autoritatif prêt |

Principes tenus :
- **§4 / §7** : ces artefacts sont **régénérés par script** depuis l'APK, **jamais committés/distribués** (cf. `.gitignore`,
  `docs/ASSETS.md`). Le logiciel = de la glue + des outils ; le contenu du jeu vient toujours de l'APK de l'utilisateur.
- **Version-agnostique** : la pipeline **re-extrait / re-décompile** depuis l'APK fourni → pas de valeur du jeu figée dans notre
  code. **Version PUBLIQUE de référence = 8.0** (nom marketing de l'app ; l'APK de référence dans le repo est nommé
  `game/disney-heroes-12.1.0.apk` — numérotation interne/build distincte du numéro public). L'utilisateur fournit la dernière
  version publiée au moment de l'installation ; le pipeline s'y adapte (d'éventuels écarts de schéma `.tab`/bytecode sont traités
  comme des faits à corriger, §8, jamais contournés).
- **Ergonomie cible (chantier C, launcher)** : un assistant unique « fournis ton APK → choisis (Port PC / Port Linux / Serveur)
  → le reste est généré » (encapsule `decompile.sh` / `reframe` / `extract_game_data.sh` / build).

## 2. Identité & login joueur : phrase de MOTS ALÉATOIRES (mnémonique type crypto)

**Direction retenue** : l'identité d'un compte joueur repose sur une **seed = suite de mots aléatoires** (à la manière d'une
*seed phrase* de portefeuille crypto / BIP39), **et non** un couple identifiant + mot de passe classique.

Pourquoi (ce que ça « cadre ») :
- **Zéro PII** : pas d'e-mail/téléphone à collecter → simple, respectueux, adapté au self-host (§5).
- **Déterministe & portable** : la phrase **dérive** l'identité du compte (et une clé) → le joueur **restaure** son compte sur
  n'importe quel serveur/instance avec sa seule phrase (pas de « mot de passe oublié »).
- **Cadre l'implémentation multi-serveur** : chaque serveur self-host applique la même règle de dérivation → cohérent.

À DÉTAILLER lors de l'implémentation (Phase 2 D) — non figé ici, mais les axes :
- **Wordlist & longueur** : liste de mots fixe (style BIP39, ~2048 mots) ; N mots (p. ex. 12) = entropie suffisante.
- **Dérivation** : `seed phrase → (userID, clé)` par fonction de hachage déterministe (à spécifier) ; le `userID` reste l'entier
  attendu par le protocole du jeu.
- **Stockage serveur** : **jamais la phrase en clair** — seulement un dérivé vérifiable (hash) ; la phrase vit chez le joueur.
- **Compat protocole** : s'insère dans le handshake existant (`ClientInfo` → `BootData`) sans réécrire la logique du jeu (§3).

**Statut** : décision de design **consignée**. Implémentation ultérieure (aucune ligne de login-mnémonique écrite à ce stade).
