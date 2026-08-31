# launcher-ui — front du launcher (Tauri + React + TS)

Front du launcher Disney Heroes (port privé). **Ne contient AUCUN code du jeu.** Il parle UNIQUEMENT au
**launcher-core** (daemon HTTP local, loopback) via `src/api/daemonClient.ts`. Spec : `../docs/LAUNCHER_UI.md`.

## Séparation front / back (pour re-styler sans casser la logique)
- `src/api/` — **seul** point de contact réseau (`daemonClient`, `types`, `tauriBridge`). Ne pas dupliquer de `fetch` ailleurs.
- `src/views/` — écrans (un par section). `src/components/` — briques réutilisables **sans réseau**.
- `src/theme/tokens.css` — **tous** les tokens visuels (couleurs, espacements). **Un designer ne touche que `theme/` + `components/`.**
- `src/i18n/` — libellés (fr/en).

## Développer
```bash
cd launcher-ui
npm install
npm run typecheck      # tsc --noEmit (vérifie les types, sans toolchain native)
npm run dev            # front web seul sur http://localhost:1420 (daemon attendu sur VITE_DAEMON_PORT ou 8090)
npm run tauri dev      # app native complète (démarre le daemon Java + fenêtre) — requiert Rust + webview système
```
En **dev navigateur** (`npm run dev`), lancer le daemon à part :
`java -cp <…>/dhlauncher.jar dhlauncher.LauncherDaemon --port 8090 --project <repo>`.

## Build / distribution
`npm run tauri build` produit l'app native. Le shell (`src-tauri/src/main.rs`) démarre le daemon Java embarqué
(`runtime/jdk/bin/java -cp dhlauncher.jar dhlauncher.LauncherDaemon`) sur un **port libre**, et l'expose au front
(`get_daemon_port`). L'intégration avec le package clé-en-main (`tools/build_launcher.sh`) = étape d'assemblage finale.

## Principe (non négociable)
**Tout ce que l'UI affiche comme fonctionnalité correspond à un endpoint réellement implémenté ET testé.** Pas de
bouton « à venir » qui ferait croire qu'une fonction existe : une section n'apparaît que quand son écran est livré.

## Vérification E2E (navigateur réel → daemon réel)
`e2e/smoke.mjs` (Playwright, Chromium pré-installé) pilote le vrai front contre un vrai daemon (proxy Vite) :
DisclaimerGate → ajout serveur (`/servers`) → génération de phrase (`/identity/generate`). Orchestration :
```bash
# 1) daemon (config isolée)
java -cp <…>/dhlauncher.jar -Ddh.launcher.config=/tmp/cfg dhlauncher.LauncherDaemon --port 18091 --project <repo>
# 2) front (proxy vers le daemon)  →  3) e2e
VITE_DAEMON_PORT=18091 npx vite --port 1420 &
DH_URL=http://localhost:1420 npm run e2e
```

## État (incréments, cf. `../docs/LAUNCHER_UI.md` §9)
- **Inc. 1 ✅** : squelette — daemonClient (15 endpoints typés), thème neutre, **DisclaimerGate**, coquille.
- **Inc. 2 ✅** : **Serveurs** (list/add/remove/ping) + **Compte** (generate/register/login, grille 8 mots, saisie BIP39
  autocomplétée). Vérifié `tsc` + `vite build` + **E2E Playwright**.
- Inc. 3 : Héberger + Générer. Inc. 4 : Jouer (`/play`). Inc. 5 : Réglages. Inc. 6 : Admin.
