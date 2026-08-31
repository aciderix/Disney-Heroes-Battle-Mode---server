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

## État (incréments, cf. `../docs/LAUNCHER_UI.md` §9)
- **Inc. 1 ✅** : squelette — daemonClient (15 endpoints typés), thème neutre, **DisclaimerGate** (avertissement à lire+accepter), coquille + navigation.
- Inc. 2 : Compte + Serveurs. Inc. 3 : Héberger + Générer. Inc. 4 : Jouer (`/play`). Inc. 5 : Réglages. Inc. 6 : Admin.
