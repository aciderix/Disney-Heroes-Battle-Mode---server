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
  - **Interface : DAEMON HTTP LOCAL** (décidé 2026-08-30). Le core tourne EN PERMANENCE (petit `HttpServer` JDK lié à
    `127.0.0.1`), le front lui parle en HTTP/JSON. Choix vs « CLI jetable » : le launcher a besoin d'un **état vivant**
    (serveur hébergé en cours, client de jeu lancé à surveiller, session authentifiée, progression de build streamée) —
    impossible avec un process CLI neuf à chaque appel (JVM froide, sans mémoire). Le daemon garde l'état + JVM chaude.
    ⚠️ **Deux services HTTP distincts** : le **launcher-core** = daemon sur la machine du JOUEUR (backend du front) ; il
    APPELLE l'`AuthService` (`:8082`) du **serveur de jeu distant** pour s'authentifier. Le daemon écrit son port/token
    dans le dossier de config local ; il n'accepte QUE `127.0.0.1` (+ token de session optionnel).
- **Tauri (Rust) + React (UI)** : présentation + orchestration uniquement. Tauri **appelle le daemon launcher-core**
  en HTTP local. Binaire minuscule (webview système, pas d'Electron). **Zéro crypto/logique jeu côté Rust/JS.**

Raison du choix : meilleure UX web moderne pour le front livré, tout en gardant **une seule** implémentation
(Ed25519/BIP39, extraction APK, gestion process) en Java — pas de second moteur crypto à maintenir en phase (§4).

### 1bis. Emplacement de la config locale (machine du joueur)
Dossier standard par OS (résolu par le core) : **Windows** `%APPDATA%\DisneyHeroesPort`, **Linux**
`$XDG_CONFIG_HOME/disney-heroes-port` (défaut `~/.config/disney-heroes-port`), **macOS**
`~/Library/Application Support/DisneyHeroesPort`. Contenu : `servers.json` (favoris), `settings.json` (résolution,
qualité spine, langue, chemin APK), `daemon.json` (port + token du daemon), et — optionnel — un cache « se souvenir de
moi » (phrase/clé) **chiffré** (par mot de passe local ; détail à la sous-étape correspondante). La **phrase en clair
n'est jamais persistée** par défaut.

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

## 3. Ordre de construction & étapes planifiées

**Login core headless** — **C1a ✅ → C1b ✅ → C1c ✅ → C1d ✅ (headless)** : identité, vérifieur, défi-réponse,
create/restore de bout en bout. Fait. La vérif EN JEU en mode strict est **débloquée par le launcher-core** (ci-dessous).

**C2a — launcher-core (daemon HTTP local, Java)** — endpoints JSON sur `127.0.0.1` :

| Sous-étape | Endpoints | But |
|---|---|---|
| **C2a-1** squelette daemon + **identité** | `GET /health` ; `POST /identity/generate` ; `POST /identity/login {phrase, serverAuthUrl}` (challenge→sign→verify → `{userID, loginRequestID}`) ; `POST /identity/register {phrase, serverAuthUrl}` | dérive/**signe** la clé (jamais transmise), appelle l'`AuthService` distant → session authentifiée. **Test headless** : daemon → vrai `AuthService` → login/register. |
| **C2a-2** serveurs + **play** | `GET/POST/DELETE /servers` (favoris `servers.json`) ; `GET /servers/{id}/ping` ; `POST /play {serverId, loginRequestID}` | redirige `ServerType.LIVE` → serveur choisi + lance le client authentifié → **boucle la vérif EN JEU strict de C1d**. |
| **C2a-3** **host** | `POST /host/start`, `POST /host/stop`, `GET /host/status` | lance/arrête le serveur local + `content_server.py` (process gérés par le daemon). |
| **C2a-4** **build** depuis l'APK | `POST /build {apkPath, target}` (SSE progression) | encapsule `decompile/reframe/extract` + build client/serveur. |

**C2b — front Tauri+React** : les 6 écrans (§2), appelant le daemon. Puis **vérif EN JEU strict de bout en bout**
(login mnémonique → boot → jouer → restaurer sur client neuf → même compte).

**Périmètre validé (utilisateur, 2026-08-30)** : 1ᵉʳ incrément = **identité + play + host** (C2a-1→C2a-3) → permet de
JOUER authentifié en local et de boucler la vérif en jeu strict ; **build** (C2a-4) ensuite ; **front** (C2b) après.

**Statut** : archi + protocole (**daemon HTTP local**) + étapes **figés**. En cours : **C2a-1**.
