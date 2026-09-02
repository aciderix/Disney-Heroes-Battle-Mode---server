# Piloter le launcher POUR DE VRAI sous Windows (contrôle total, sans capture d'écran ni proxy de dev)

> Objectif (demande utilisateur) : quand une session tourne **sur le PC Windows**, pouvoir **utiliser l'app
> réellement** — vrais clics, vraie saisie, lecture du DOM et de la console — sur le **VRAI `.exe` packagé**
> (donc le vrai pont Tauri↔daemon), pas via un proxy Vite ni des captures d'écran.

## Pourquoi ça marche
L'app est une app **Tauri** : sous Windows, son interface tourne dans **WebView2**, le moteur **Edge/Chromium**
de Windows. Comme tout Chromium, WebView2 peut exposer le **protocole DevTools (CDP)** sur un port local si on
lui passe `--remote-debugging-port`. On s'y attache et on pilote le DOM + les entrées **du vrai exe**.

- Le front détecte Tauri via `window.__TAURI_INTERNALS__` (`tauriBridge.ts`) → en pilotant le vrai exe, on
  exerce le **vrai IPC Tauri→daemon**, contrairement au test « `npm run dev` + navigateur » de g252 (qui
  retombait sur du HTTP relatif, un chemin différent).
- **Aucune modif de l'app, aucun impact joueur** : le port CDP ne s'ouvre QUE si la variable d'environnement
  est posée. Un joueur qui double-clique l'exe nu n'a rien d'ouvert.

## Prérequis
- **Node ≥ 21** dans le PATH (pour `WebSocket`/`fetch` natifs — le pilote n'a **aucune dépendance npm**).
  (Node est déjà présent sur la machine de dev Windows utilisée jusqu'ici.)
- Le package launcher Windows (contenant `DisneyHeroesLauncher.exe`), et ce dépôt (pour `tools/`).

## 1) Lancer l'app en mode debug (port CDP ouvert)
```powershell
powershell -ExecutionPolicy Bypass -File tools\dh-debug-launch.ps1
# ou en pointant l'exe explicitement :
powershell -ExecutionPolicy Bypass -File tools\dh-debug-launch.ps1 -Exe "C:\Users\<toi>\Desktop\launcher-windows\DisneyHeroesLauncher.exe" -Port 9222
```
Le script pose `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS="--remote-debugging-port=9222"` puis lance l'exe (au
premier plan → on voit les logs `[launcher]`/`[content]`). Équivalent manuel :
```powershell
$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS="--remote-debugging-port=9222"; & ".\DisneyHeroesLauncher.exe"
```

## 2) Piloter — `tools/cdp_drive.mjs`
Vérifie d'abord la connexion :
```powershell
node tools\cdp_drive.mjs targets            # liste les pages (doit montrer l'UI du launcher)
```
Commandes principales :
```
eval "<js>"                 évalue du JS dans la page (await supporté) → imprime le résultat
dump                        liste les éléments interactifs visibles (boutons/liens/inputs + leur texte)
clicktext "<texte>"         clique le bouton/onglet/lien VISIBLE contenant ce texte (pas besoin du sélecteur)
click "<sélecteur CSS>"     vrai clic souris au centre de l'élément
type "<sélecteur>" "<txt>"  saisie compatible React (setter natif + events input/change)
press "<sélecteur>" Enter   touche clavier réelle
text "<sélecteur>"          innerText de l'élément
wait "<sélecteur>" visible  attend l'apparition (+ visibilité)
console [ms]                écoute console + exceptions + logs pendant ms (défaut 8000)
shot capture.png            capture d'écran PNG de la page
repl                        évalue du JS ligne par ligne (exploration interactive)
```
Options : `--port 9222`, `--url <sous-chaîne>` (choisir la cible), `--timeout <ms>`, `--json` (sortie scriptable).

### Découvrir l'UI quand on n'a pas les sélecteurs
```powershell
node tools\cdp_drive.mjs dump                       # montre "button.xxx :: Héberger", "a :: Compte", ...
node tools\cdp_drive.mjs text "body"                # tout le texte visible
node tools\cdp_drive.mjs eval "location.hash"       # écran courant (routing)
```
`clicktext` suffit le plus souvent : il cherche par **texte visible** et pose un marqueur temporaire pour
cliquer exactement le bon élément.

## 3) Recette : flux Héberger → Compte → Jouer (à adapter avec `dump`)
```powershell
# écoute la console en tâche de fond (autre fenêtre) pour tout voir :
node tools\cdp_drive.mjs console 120000

# Héberger un bundle serveur déjà généré (onglet « Générer » au préalable) :
node tools\cdp_drive.mjs clicktext "Héberger"
node tools\cdp_drive.mjs dump                                  # repérer le champ "dossier du bundle serveur"
node tools\cdp_drive.mjs type "input[type=text]" "C:\Users\<toi>\Desktop\server"
node tools\cdp_drive.mjs clicktext "Démarrer"                  # ou le libellé réel du bouton
node tools\cdp_drive.mjs eval "await (await fetch('/host/status')).text()"   # via le vrai bridge : running/ports

# Compte (créer/restaurer) :
node tools\cdp_drive.mjs clicktext "Compte"
node tools\cdp_drive.mjs clicktext "Créer"                     # génère la phrase mnémonique
node tools\cdp_drive.mjs text "body"                           # relever la phrase affichée

# Jouer :
node tools\cdp_drive.mjs clicktext "Jouer"
node tools\cdp_drive.mjs shot jouer.png                        # état visuel (optionnel, pour toi — pas pour piloter)
```
> Les libellés/sélecteurs exacts se lisent avec `dump`. `eval "await (await fetch('/<endpoint>')).text()"`
> interroge le daemon **via le vrai contexte de l'app** (utile pour vérifier `/host/status`, `/servers`, etc.).

## 4) Capturer le crash du daemon (bug #3, g252)
Symptôme : en cours d'hébergement, `DisneyHeroesLauncher.exe` **disparaît** alors que le serveur de jeu (java)
et le contenu (python) tournent encore → l'UI croit le serveur « arrêté ». La cause n'a pas encore été prise
sur le fait. Pour la choper EN DIRECT :
```powershell
# fenêtre A : lance l'app (debug ou non)
powershell -ExecutionPolicy Bypass -File tools\dh-debug-launch.ps1
# fenêtre B : surveille en continu, AVANT de reproduire l'hébergement
powershell -ExecutionPolicy Bypass -File tools\dh-watch-daemon.ps1
# fenêtre C : reproduis héberger→… (via cdp_drive) ; quand le launcher disparaît, la fenêtre B imprime
#   l'instant exact + le journal Application (erreurs) + Windows Error Reporting autour de ce moment.
```
Le log est aussi écrit dans `%TEMP%\dh-daemon-watch.log`. Récupère ce dump + les dernières lignes de la console
`[launcher]` de la fenêtre A → c'est ce qui permettra de corriger la cause (et non seulement le symptôme, §2).

## Alternative « officielle » (plus lourde) : `tauri-driver` + WebDriver
Pour une suite E2E formelle, Tauri fournit `tauri-driver` (pilote WebDriver via `msedgedriver`). Plus robuste
mais nécessite un build webdriver + le driver Edge. Le CDP ci-dessus suffit pour un pilotage réel immédiat sans
rebuild.

## Notes
- Le port CDP est **local** (127.0.0.1) et éphémère (ouvert seulement pour la session de debug). Ne pas
  l'activer pour un joueur.
- `clicktext` pose un attribut `data-dhcdp` temporaire sur l'élément visé (inoffensif, disparaît au reload).
- Tout ceci pilote la **WebView** (toute l'UI du launcher). Une éventuelle boîte de dialogue **native Windows**
  (hors WebView) demanderait un outil UIA (WinAppDriver) — non nécessaire ici, l'UI est entièrement web.
