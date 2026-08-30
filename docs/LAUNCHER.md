# LAUNCHER — architecture & contenu exact (Phase 2, chantier C)

> Décidé avec l'utilisateur (2026-08-30). Le launcher est le **front du logiciel qu'on livre** (jamais le jeu, cf.
> `docs/DISTRIBUTION.md`). Il orchestre : import/build depuis l'APK fourni, identité mnémonique, choix/hébergement de
> serveur, lancement du client. **Rien n'est encore codé côté UI** ; ce doc FIXE l'archi + l'inventaire d'écrans.

## 1. Architecture : Tauri + React (shell) ⟶ launcher-core (Java)

**Séparation stricte présentation / logique**, pour ne JAMAIS dupliquer la crypto ou la logique jeu (§4) :

- **`launcher-core/` (Java, headless)** = **source unique de vérité**. Tout ce qui touche au jeu/à la crypto vit ici et
  réutilise le code existant :
  - **Identité** : réutilise `dhserver.auth.MnemonicIdentity` (générer/valider phrase, dériver userID+clé, **signer** un
    challenge). La clé privée reste **locale**, dérivée de la phrase à la demande (ou cache local chiffré par un mot de
    passe optionnel « se souvenir de moi ») — jamais transmise.
  - **Serveurs** : liste de favoris (fichier config local), `ping`, statut (version, nb joueurs en ligne).
  - **Session/login** : orchestre le **défi-réponse** au `/login` (récupère le nonce, signe, soumet, reçoit l'adresse
    du serveur de jeu + la session) — cf. chantier C1c.
  - **Cycle de vie** : `host` (lance serveur local + `content_server.py`), `stop`, `status`.
  - **Build depuis l'APK** : encapsule `tools/decompile.sh` / reframe / `tools/extract_game_data.sh` / build client &
    serveur (cf. `docs/DISTRIBUTION.md` §1) — `import-apk`, `build-client <win|linux>`, `build-server`.
  - **Jouer** : écrit la redirection `ServerType.LIVE` → serveur choisi (remplace le `127.0.0.1:8080` en dur) et lance
    le client Java (équivalent `run-desktop.sh`) avec la session authentifiée + les réglages (qualité spine, résolution…).
  - **Interface** : exposé comme **CLI sous-commandes / protocole JSON** (stdin-stdout ou petit HTTP localhost) — c'est
    ce que Tauri invoque.
- **Tauri (Rust) + React (UI)** : présentation + orchestration uniquement. Les commandes Tauri **invoquent
  launcher-core** (sous-process Java, protocole JSON). Binaire minuscule (webview système, pas d'Electron). **Zéro
  crypto/logique jeu côté Rust/JS.**

Raison du choix : meilleure UX web moderne pour le front livré, tout en gardant **une seule** implémentation
(Ed25519/BIP39, extraction APK, gestion process) en Java — pas de second moteur crypto à maintenir en phase (§4).

## 2. Contenu exact — écrans & flux

1. **Setup / Onboarding** (1ᵉʳ lancement) : « Fournis ton APK *Disney Heroes* (dernière version) » (sélecteur de
   fichier) → choix de cible (**Port PC / Port Linux / Serveur / tout**) → barre de progression
   (decompile → reframe → extract → build) → terminé. Encapsule `docs/DISTRIBUTION.md` §1.
2. **Compte** (Nouveau / Restaurer) :
   - **Nouveau** : bouton « Générer » → affiche les **8 mots** (grands, numérotés, bouton copier) + avertissement
     « note-les : c'est ta seule clé, pas de récupération » + case « je les ai notés » → crée le compte
     (register clé publique, cf. C1b) → connecte.
   - **Restaurer** : 8 champs avec **autocomplétion BIP39** + validation **checksum en direct** → connecte.
   - Option **« se souvenir de moi »** : cache local de la phrase/clé chiffré par un mot de passe optionnel.
3. **Liste de serveurs** : favoris (nom, adresse, **ping**, version, **nb joueurs en ligne**, statut), boutons
   **Rejoindre / Ajouter / Héberger**. « Héberger » = lance le serveur local + `content_server.py` et s'y connecte.
4. **Jouer** : bouton **Jouer** (lance le client sur le serveur sélectionné, compte authentifié) + état (connecté,
   version, latence).
5. **Réglages** : résolution, **qualité spine (jni/unidbg)**, langue, chemin de l'APK, mot de passe local optionnel,
   gestion des comptes locaux, rebuild.
6. **Panneau d'hébergement** (si on héberge) : statut serveur, nb joueurs, logs, bouton arrêter — version minimale ici
   (le panneau opérateur complet = chantier D).

**Flux** : 1ᵉʳ run → Setup(APK) → Compte(Nouveau) → Serveurs(localhost/héberger) → Jouer. Retour → Compte(auto si « se
souvenir ») → Serveurs → Jouer.

## 3. Ordre de construction

1. **Login core headless d'abord** (chantiers **C1a ✅ → C1b → C1c → C1d**) : identité, vérifieur, défi-réponse au
   `/login`, create/restore de bout en bout + EN JEU. **Tech-agnostique** (le launcher l'appellera tel quel).
2. **launcher-core Java** : sous-commandes servers/session/host/build/play au-dessus de (1).
3. **Shell Tauri+React** : les 6 écrans ci-dessus, appelant launcher-core.

**Statut** : archi + contenu **figés** ici. Implémentation UI = après le login core (C1b→C1d).
