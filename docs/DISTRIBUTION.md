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

### Spécification FIGÉE (décidée 2026-08-30 avec l'utilisateur) + statut d'implémentation
- **Wordlist** : **BIP-0039 anglaise** (2048 mots, standard public — sha256 `2f5eed…dbda`), embarquée via
  `tools/gen_bip39.sh` → `server/java/dhserver/auth/Bip39Wordlist.java` (régénérable, §7). Mots familiers/interopérables.
- **Longueur** : **8 mots** = 88 bits = **80 bits d'entropie** (10 octets) + 8 bits de checksum (détecte les fautes de
  frappe). Octet-aligné, jeu-approprié (pas d'argent réel). Constante `MnemonicIdentity.WORDS` (dialable).
- **Dérivation** : phrase → seed (**BIP39 PBKDF2-HMAC-SHA512**, salt `"mnemonic"`, 2048 iters) → **paire Ed25519
  DÉTERMINISTE** (32 premiers octets = graine privée ; JDK 21 natif, 0 dépendance). `userID` (long positif du
  protocole) = 8 octets de SHA-256(clé publique).
- **Auth = DÉFI-RÉPONSE ASYMÉTRIQUE** (le mieux) : le serveur envoie un nonce, le launcher le **signe** (Ed25519), le
  serveur vérifie avec la **clé publique** stockée. **Aucun secret côté serveur** (DB peut fuiter sans compromettre les
  comptes) ; la clé privée ne quitte jamais le joueur ; rien de sensible ne transite → robuste **même sans TLS** (le TLS
  du chantier F reste un complément). Anti-usurpation : impossible de réclamer un userID sans la phrase.
- **Stockage serveur** : `userID → clé publique` (le vérifieur), **jamais la phrase** — cf. chantier C1b (table
  `UserStore`).
- **Compat protocole** : s'insère dans le handshake existant (`ClientInfo.loginRequestID` corrèle `/login` HTTP et le
  socket ; `ClientInfo.userID`) **sans réécrire la logique du jeu** (§3) — auth au niveau plateforme (`/login` + table
  de session ; `LoginServer` lie le socket à la session authentifiée). Cf. chantier C1c.

**Statut** : **C1a ✅ + C1b ✅ + C1c ✅ (headless)**.
- **C1a** `MnemonicIdentity` (`MnemonicIdentityTest`, 13 assert.).
- **C1b** vérifieur `UserStore.accounts` userID→clé publique (`AccountStoreTest`, 12 assert.).
- **C1c** `SessionStore` (défi-réponse : nonce usage unique, TTL, verify Ed25519 vs clé stockée, liaison
  `loginRequestID→userID`) + `AuthService` HTTP (`:8082`, `/auth/challenge` + `/auth/verify`) + gate `LoginServer`
  (mode STRICT `-Ddh.auth=on`, **défaut permissif** = compat DEV) — `SessionAuthTest` (12) + `AuthServiceTest` (6,
  round-trip HTTP réel) ; boot vérifié (AuthService démarre, client boote en permissif, 0 régression).
- **C1d ✅ (headless)** : `/auth/register` (preuve de possession : userID doit dériver de la clé fournie + signature)
  + flux **create/restore** de bout en bout (`AuthFlowTest`, 9 assert., HTTP réel — création, restauration par la
  seule phrase [même userID déterministe], gardes de sécurité). La **vérif EN JEU en mode strict** est **gated sur le
  launcher-core** (le client de jeu ne fait pas le défi-réponse — c'est le launcher qui l'orchestre).
- **Reste** : le launcher (chantier C2 : core Java d'abord, cf. `docs/LAUNCHER.md`), qui débloquera la vérif en jeu.
