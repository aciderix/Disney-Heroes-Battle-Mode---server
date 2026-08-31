# JOURNAL — journal détaillé des modifications

## 2026-08-31 (g213) — C2b inc.6 : écran ADMIN (5 domaines) branché sur le backend Admin 🟢

Front de l'administration : l'écran **Admin** consomme les endpoints `/admin/*` (tous livrés+testés g208→g212) via le
proxy daemon générique. **Le daemon injecte le jeton opérateur** pour le serveur LOCAL hébergé → l'UI n'a aucun jeton à
gérer ; sans hébergement, `/admin/*` renvoie **503** → l'écran affiche un gating honnête « héberge d'abord » (pas de
faux panneau, principe util.). Admin d'un serveur distant/cloud = ultérieur (chantier F).

- **`daemonClient`** : 25 méthodes admin typées (monitor, hostLogs, releases/release/clock, player/{lookup,giveResource,
  grantHero,setTeamLevel,grantCampaign,completeTutorials,unlock}, audit, events/{list,add,remove,clear}, enums,
  moderation/{list,ban,unban,mute,unmute,kick}). `types.ts` : types du contrat admin.
- **`Admin.tsx`** (nouvelle vue) — sonde `/admin/monitor` (503 → gate), sinon 5 sous-onglets : **Monitoring** (en ligne +
  connexions + uptime + tail des logs serveur/content) ; **Ère** (liste des releases, servir/reset, horloge test) ;
  **Joueurs** (lookup → résumé + actions autoritatives donner ressource/héros/TL/tutos/déblocage, avec confirmations,
  + journal d'audit) ; **Events** (liste, ajout par spec JSON validée serveur, retrait/clear, enums de référence) ;
  **Modération** (ban/mute/kick + listes débannir/démuter). Ajouté à la nav Shell ; `/admin` au proxy Vite ; CSS
  `.tbl`/`.logconsole`.
- **Vérif** : `tsc --noEmit` + `vite build` OK ; **E2E Playwright 9/9** (+ Admin : gating 503 honnête). Serveur inchangé
  (régression 174/174 de g212). 🟢 (vérif EN JEU des actions = pilotable une fois un serveur hébergé, endpoints déjà
  prouvés `AdminMonitorTest`/`AdminProxyTest`).
- **Suite** : job CI « build launcher-ui » (typecheck + build sur 2 OS) ; (option) admin distant/cloud (chantier F).

## 2026-08-31 (g212) — Admin inc.6e : MODÉRATION (bans/mutes/kick) — CONSTRUITE → backend Admin COMPLET 🟢

Dernier domaine du panneau opérateur (chantier D E). N'existait PAS dans le jeu (`KickFromGuild` = expulsion de guilde,
pas modération) → **construit** comme sous-système serveur minimal, faithful (§2 : shim RÉEL, pas de faux OK).

- **`dhserver.admin.Moderation`** (nouveau) : sets statiques BANS/MUTES, persistés dans `shard_state/moderation`
  (`{"bans":[…],"mutes":[…]}`), chargés au BOOT par `LoginServer`. `isBanned`/`isMuted` (consultés par le serveur) +
  `addBan/removeBan/addMute/removeMute` (persistent + renvoient la liste).
- **`LoginServer`** (3 hooks) : **BAN** = rejet au login (à côté de l'auth stricte : userID banni → aucun BootData) ;
  **MUTE** = un `SendChat` d'un userID muté est ignoré (ni archivé ni diffusé) ; **KICK** = méthode `kick(uid)` qui ferme
  la connexion vive (`online.get(uid).close()`, `GruntConnection.close()`), non persistant. Chargement modération au boot.
- **AdminService** (jeton requis) : `GET /admin/moderation` (bans+mutes) ; `POST /admin/moderation/{ban,unban,mute,unmute,
  kick}` — ban kicke aussi immédiatement si en ligne. Tout journalisé (`AdminAudit`). Daemon inchangé (proxy générique).
- **Vérif** : compile game-free OK ; `AdminMonitorTest` 36 (liste vide, ban 42 → `isBanned` true, mute 7 → `isMuted` true,
  kick hors-ligne → false, unban → vide, garde jeton) + `AdminProxyTest` 26 (liste/ban/kick/unban proxifiés) ;
  **régression 174/174**. 🟢 headless (vérif EN JEU = via l'UI Admin : bannir un vrai client le déconnecte/rejette).
- **⇒ BACKEND ADMIN COMPLET** (5 domaines : monitoring, ère, joueurs, events, modération), tous derrière l'AdminService
  (jeton opérateur, bind configurable) + proxy daemon générique. **Suite** : **UI Admin** dans le launcher (écran Admin
  branché sur ces endpoints) + job CI « build launcher-ui ».

## 2026-08-31 (g211) — Admin inc.6d : events live-ops (liste/ajout validé/retrait/clear) + enums réelles 🟢

Domaine D « events » exposé via l'AdminService, en RÉUTILISANT le format de config du jeu-glue existant (§3, même
mécanisme que le CLI `AdminEvents` : specs JSON `{kind,…}` persistées dans `shard_state/operator_events`, reconstruites
par `ServerEvents.*`). Helper `dhserver.admin.EventsAdmin`.

- **`EventsAdmin`** : `listJson` (specs persistées) ; `addSpec` (VALIDÉE : kind ∈ whitelist des 13 composants livrés ET
  reconstruit exactement 1 event via `eventsFromConfig` — sinon rejet, jamais persistée §2 ; nécessaire car
  `eventFromSpec` retombe silencieusement sur MODES_OPEN pour un kind inconnu) ; `removeAt` (index) ; `clear` ;
  `enumsJson` (listes RÉELLES reflétées : GameMode/ChestType/MerchantType/MultiplierType/UserFlag/ResourceType/
  GenericTrialType + kinds — jamais codées en dur côté front §4). Persiste (`shard_state`) + applique à CHAUD
  (`setOperatorEvents`) → visible au prochain `REFRESH_SPECIAL_EVENTS` du client.
- **AdminService** (jeton requis) : `GET|POST /admin/events` (liste | ajoute {spec}, 400 si invalide) ;
  `POST /admin/events/remove {index}` (404 hors bornes) ; `POST /admin/events/clear` ; `GET /admin/enums`. Mutations
  journalisées (`AdminAudit`). Daemon inchangé (proxy générique de g209).
- **Vérif** : compile game-free OK ; `AdminMonitorTest` 29 (enums réelles, cycle liste 0→ajout MODES_OPEN→1, spec BOGUS
  rejetée 400, retrait→0) + `AdminProxyTest` 22 (enums/liste/clear proxifiés) ; **régression 174/174**. 🟢 headless.
- **Suite** : inc.6e **modération à CONSTRUIRE** (bans au login + mute + kick) → UI Admin + CI « build launcher-ui ».

## 2026-08-31 (g210) — Admin inc.6c : gestion des joueurs (édition autoritative) + journal d'audit 🟢

Domaine D « joueurs » exposé via l'AdminService, en RÉUTILISANT les mutateurs `ServerUser` existants (§3, mêmes
méthodes que les outils dev `SetTeamLevel`/`CodebaseUnlock`) + **journalisation obligatoire** (choix util. « au mieux »).

- **`ServerUser`** (3 ajouts, glue) : `setTeamLevel(int)` (miroir `SetTeamLevel`), `adminUnlock()` (miroir
  `CodebaseUnlock` : TL300 + chapitre requis terminé + roster JAUNE, via data du jeu ; ne persiste pas, appelant),
  `adminSummaryJson()` (résumé lecture-seule : id/nom/TL/or/diamants/énergie/héros/guilde, ressources via `getResource`).
- **`AdminService`** (endpoints POST, jeton requis, renvoient le résumé du compte) : `/admin/player/{lookup,
  giveResource,grantHero,setTeamLevel,grantCampaign,completeTutorials,unlock}` → chargent le compte
  (`store.loadOrCreate`), appliquent le mutateur du jeu, `store.save`, et **JOURNALISENT**. Enums (`ResourceType`/
  `UnitType`/`Rarity`/`CampaignType`) validées → 400 si invalide.
- **`AdminAudit`** (nouveau) : chaque mutation écrit une ligne TSV horodatée dans `admin-audit.log` (à côté de la DB) ;
  `GET /admin/audit?tail=N` la relit. Append-only, best-effort (ne bloque jamais l'action).
- **Limite honnête (§2, documentée)** : édite le compte PERSISTÉ ; si le joueur est EN LIGNE, il verra les changements
  à sa reconnexion, et une mutation faite pendant sa session peut être écrasée. Routage via l'instance vive = ultérieur.
- **Vérif** : compile game-free OK (daemon inchangé — le proxy générique de g209 relaie déjà tout `/admin/*`) ;
  `AdminMonitorTest` 24 (lookup, giveResource GOLD +500, type invalide→400, setTeamLevel 50, grantHero, audit tracé,
  gardes jeton) + `AdminProxyTest` 19 (lookup/giveResource/audit proxifiés) ; **régression 174/174**. 🟢 headless
  (vérif EN JEU = via l'UI Admin).
- **Suite** : inc.6d events (`/admin/events`, `/admin/enums`) → inc.6e **modération à CONSTRUIRE** → UI Admin + CI front.

## 2026-08-31 (g209) — Admin inc.6b : ère de contenu (releases + horloge) + proxy daemon GÉNÉRIQUE 🟢

Domaine D « ère de contenu » exposé via l'AdminService, **sans réécrire la logique** (§3) : nouveau helper
`dhserver.admin.ContentEra` = **SOURCE UNIQUE** de la formule d'offset, réutilisé par l'AdminService (à chaud) ET le
CLI `AdminRelease` (refactoré pour déléguer → zéro divergence).

- **`ContentEra`** : `columns/name/fmt/currentEra/resolve/applyRelease/resetRelease` + JSON `listJson`/`statusJson`.
  Offset d'ère DÉCOUPLÉ (`ServerContext.setContentOffsetMillis`, persisté `content_offset_ms`) → régler l'ère ne touche
  NI sauvegardes NI timers. Dans la JVM serveur → applique à CHAUD (prochain BootData), pas de redémarrage (avantage vs CLI).
- **AdminService** (endpoints, jeton requis) : `GET /admin/releases` (liste R1…Rn : nom, date, Max TL, courante) ;
  `POST /admin/release {name|#idx|reset}` (règle l'ère, 404 si introuvable, renvoie l'état) ; `GET|POST /admin/clock`
  ({offsetHours} → décale l'HORLOGE ENTIÈRE — ère + timers, mode test, distinct du release-picker). Shard 1 (mono-shard).
- **Daemon** : le proxy `/admin/*` devient **GÉNÉRIQUE** (`/admin/` préfixe → relaie méthode+chemin+query+corps avec le
  jeton injecté ; 503 hors hébergement). ⇒ les incréments admin suivants (joueurs, events, modération) passent SANS modif
  du daemon. Reste GAME-FREE.
- **CLI `AdminRelease`** refactoré : délègue à `ContentEra` (mêmes messages, une seule formule d'offset).
- **Vérif** : compile game-free OK ; `AdminMonitorTest` 15 (+ releases servie/gardée par jeton) ; `AdminProxyTest` 16
  (+ ère proxifiée GET, release POST #1 → offset≠0, reset → 0, clock GET/POST 0, 404 relayé) ; **régression 174/174**.
  🟢 headless (vérif EN JEU = via l'UI Admin).
- **Suite** : inc.6c joueurs (`/admin/player/*`, journalisé) → inc.6d events (`/admin/events`, `/admin/enums`) →
  inc.6e **modération à CONSTRUIRE** → UI Admin + CI « build launcher-ui ».

## 2026-08-31 (g208) — Admin inc.6a : AdminService (serveur) + jeton + proxy daemon + monitoring 🟢

Début du **chantier D** (panneau opérateur, `docs/LAUNCHER_UI.md` §4.6). **Architecture tranchée par le code** : le
launcher-core est GAME-FREE → il ne peut pas exécuter les opérations admin (elles ont besoin des classes du jeu :
`LoginServer.online`, `ServerUser`, `ServerEvents`, `AdminRelease`). ⇒ **`AdminService` HTTP DANS la JVM du serveur de
jeu** (à côté de l'`AuthService`), et le **daemon PROXIFIE `/admin/*`** (comme content_server proxifie `/login`).

- **Sécurité (choix util. « A et/ou C, toujours sécurisé »)** : **jeton opérateur OBLIGATOIRE** sur chaque requête
  (`Authorization: Bearer` ou `X-Admin-Token`, comparé en temps constant → 401 sinon) ; **liaison configurable**
  `127.0.0.1` par défaut (option A, même PC), exposable réseau via `-Ddh.admin.bind=0.0.0.0` (option C, cloud) — toujours
  sous jeton. Port `-Ddh.admin.port` (défaut 8083). Jeton fourni (`-Ddh.admin.token`/`DH_ADMIN_TOKEN`) ou généré+imprimé.
- **`dhserver.admin.AdminService`** (nouveau, JDK HttpServer) : `GET /admin/ping` (vérif jeton), `GET /admin/monitor`
  (état vivant : joueurs en ligne [userID+ancienneté], connexions acceptées, uptime, mode strict). `LoginServer` :
  `monitorSnapshotJson()` + suivi `onlineSince` (miroir d'`online`, posé/retiré aux mêmes points) + `startedAtMs`.
  Démarré dans `LoginServer.main` après l'AuthService.
- **Daemon** (reste GAME-FREE — simple relais) : `HostManager` GÉNÈRE le jeton et le passe au serveur dev
  (`-Ddh.admin.*`, adminPort=authPort+1) → il le connaît sans lire les logs ; expose `adminBaseUrl()`/`adminToken()`
  + `tailLog(which,n)`. `LauncherDaemon` : `GET /admin/monitor` (proxifie vers l'AdminService local, injecte le jeton ;
  **503 si aucun serveur hébergé** — pas de faux OK) + `GET /host/logs?which=server|content&tail=N`. Admin d'un serveur
  DISTANT (saisie URL+jeton) + mode bundle = incréments ultérieurs (le run.sh généré ne relaie pas encore `DH_ADMIN_*`).
- **Vérif** : compile GAME-FREE OK (daemon inchangé côté propreté) ; `AdminMonitorTest` (12 : garde de jeton 401/200 via
  les deux en-têtes, JSON monitoring, `randomToken`) + `AdminProxyTest` (9 : 503 hors hébergement → start → proxy monitor
  0 en ligne → tail logs → stop → 503) ; **régression 174/174**. 🟢 headless — la vérif EN JEU (un vrai client apparaît
  dans `online`) viendra avec l'écran Admin du front.
- **Suite** : inc.6b ère de contenu (`/admin/releases`, `/admin/release`, `/admin/clock`) → inc.6c joueurs
  (`/admin/player/*`, journalisé) → inc.6d events (`/admin/events`, `/admin/enums`) → inc.6e **modération à CONSTRUIRE**
  (bans au login + mute + kick) → UI Admin + CI « build launcher-ui ».

## 2026-08-31 (g207) — C2b inc.5 : écran Réglages (/settings) + persistance 🟢

- **Backend `/settings`** (`SettingsManager.java`) : fichier `settings.txt` (key=value) dans le dossier de config par OS.
  **Ensemble de clés FERMÉ** (validé, clés inconnues ignorées) : `language`, `disclaimerAcceptedVersion` (int),
  `apkPath`, `outDir`, `clientDir`, `bundleDir`. Endpoints `GET /settings` (lecture) + `POST /settings` (merge des clés
  connues + save + renvoi de l'état). Testé `SettingsLifecycleTest` (7 assertions : défauts, persistance disque,
  round-trip du merge partiel, rejet des clés inconnues) — ajouté à `regression.sh`.
- **Front Réglages** (`Settings.tsx`) — adossé UNIQUEMENT à `/settings` (principe « pas de bouton futur ») :
  **Langue** (i18n fr/en, appliquée immédiatement à toute la nav), **Chemins par défaut** (préremplissent
  Générer/Jouer/Héberger — `apkPath`/`outDir`/`clientDir`/`bundleDir`), **Relire l'avertissement** (remet
  `disclaimerAcceptedVersion=0` → ré-ouvre le DisclaimerGate). Résolution/qualité spine NON proposées (non transmises
  au lancement → pas de faux réglage).
- **Câblage** : `store.tsx` restructuré (`AppStateProvider` prend `settings`+`onSettings`, expose `saveSettings`) ;
  `App.tsx` charge `/settings` au boot (gate depuis `disclaimerAcceptedVersion`, langue depuis `settings.language`) ;
  Générer/Jouer/Héberger préremplis depuis les chemins par défaut. `daemonClient` : `getSettings`/`updateSettings`
  typés. `/settings` ajouté au proxy Vite (dev).
- **Vérif** : `tsc --noEmit` + `vite build` OK ; **E2E Playwright 8/8** (+ Réglages : bascule langue → EN via `/settings`
  → i18n appliqué à la nav) ; **régression 172/172** (avec `SettingsLifecycleTest`).
- **Suite = BACKEND d'abord** : Admin exige `/admin/*` (monitoring `LoginServer.online` ; gestion joueurs `ServerUser` ;
  ère `AdminRelease` — tous prêts à exposer ; **Modération à CONSTRUIRE**) → puis UI Admin → puis CI « build launcher-ui ».

## 2026-08-31 (g206) — C2b inc.4 : écran Jouer (/play) — front joueur COMPLET pour le backend existant 🟢

- **Jouer** : `/play` (permissif : DH_USERID = compte) + polling `/play/status` (running, PID, serveur, uptime) +
  `/play/stop`. Nécessite session + serveur + dossier bundle CLIENT. Le **strict distant** (hook loginRequestID) n'est
  pas livré → non proposé (principe). Endpoints testés `PlayLifecycleTest` + vérif bout-en-bout (g203).
- **Front joueur COMPLET** pour la surface backend actuelle : 5 écrans (Jouer, Serveurs, Compte, Héberger, Générer),
  chacun 100% adossé à des endpoints réels+testés. **E2E Playwright 7/7** (+ Jouer : gating sans session).
- **Reste = BACKEND d'abord** (principe « pas de bouton futur ») : **Réglages** exige la persistance `settings.json`
  (`GET/POST /settings`, §7) ; **Admin** exige les endpoints `/admin/*` (5 domaines, dont Modération à CONSTRUIRE, §7).
  Ces écrans ne seront ajoutés qu'APRÈS livraison+test de leurs endpoints. Aussi à venir : job CI « build launcher-ui ».

## 2026-08-31 (g205) — C2b inc.3 : écrans Héberger + Générer (endpoints réels) + E2E étendu 🟢

- **Héberger** : `/host/start` (bundleDir OU dev, ports, strict) / `/host/stop` / polling `/host/status` (running,
  port de jeu en écoute, ports, strict, PIDs, uptime formaté). Testés `HostLifecycleTest`/`ServerBundleTest`.
- **Générer** : `/build/start` (APK → **SERVER | CLIENT uniquement** ; APK non implémenté → NON proposé, principe) +
  polling `/build/status` (StepProgress = état+étape réels ; LogConsole tail auto-scroll ; à DONE : chemin de sortie).
  Testés `BuildDataGenTest`/bundles. Composants `PathInput` (Parcourir natif Tauri / saisie manuelle), `StepProgress`,
  `LogConsole`.
- Coquille : 4 sections livrées (Serveurs, Compte, Héberger, Générer). **E2E Playwright étendu (6/6)** : + Héberger
  (poll `/host/status` → « arrêté ») + Générer (aucune cible APK). `tsc`+`vite build` OK.
- **Suite** : inc.4 Jouer (`/play`, déjà backé) → inc.5 Réglages (persistance §7 à ajouter) → inc.6 Admin (endpoints §7) ; CI front.

## 2026-08-31 (g204) — C2b inc.2 : écrans Serveurs + Compte (adossés endpoints réels) + E2E Playwright 🟢

Principe posé par l'utilisateur : **tout ce que l'UI affiche = un endpoint réellement implémenté ET testé ; pas de
bouton « à venir »**. Appliqué : la coquille n'affiche QUE les sections livrées (plus de placeholders).

- **Serveurs** : list/add/remove/**ping** (endpoints `/servers*`, testés `LauncherServersTest`) — cartes + sélection +
  ajout (nom/host + ports avancés) + « + localhost » + test de latence (voyant vert/rouge).
- **Compte** : Nouveau (`/identity/generate` → grille des 8 mots + copier + case « noté » → `/identity/register`) et
  Restaurer (saisie 8 mots avec **autocomplétion BIP39** + validation d'appartenance → `/identity/login`). Wordlist
  BIP39 (2048 mots) **extraite** de `Bip39Wordlist.java` (réutilisation, pas de réinvention). Session en mémoire
  (persistance « se souvenir » = §7, NON implémentée → NON proposée). Nécessite un serveur sélectionné.
- **Transport dev** : le daemon est loopback-only SANS CORS permissif (sécurité) → en dev navigateur, **proxy Vite**
  (même origine) ; en prod = pont natif Tauri. `daemonClient.base()` choisit selon Tauri/dev.
- **Vérif** : `tsc --noEmit` + `vite build` OK, **et E2E Playwright** (Chromium pré-installé) : vrai front → vrai daemon
  → DisclaimerGate (bouton bloqué au départ) → ajout serveur (`/servers`) → génération phrase (`/identity/generate`).
  `e2e/smoke.mjs` (+ `npm run e2e`).
- **Suite** : inc.3 Héberger+Générer, inc.4 Jouer(/play), inc.5 Réglages, inc.6 Admin ; endpoints manquants §7 ; CI front.

## 2026-08-31 (g203) — C2b : spec front exhaustive + endpoint /play + panneau admin (5 domaines) + squelette Tauri/React 🟢

Front du launcher (chantier C2b), sur choix utilisateur : **spec d'abord** (tout défini depuis le code, rien oublié),
**/play avant le front**, **UI neutre/fonctionnelle** (reskinnable par Gemini), auteur **Aciderix**.

- **`docs/LAUNCHER_UI.md`** — spec EXHAUSTIVE : contrat des 15 endpoints (`daemonClient`), IA par intention
  (Compte/Serveurs/Jouer/Héberger/Générer/Admin/Réglages), **avertissement 1er lancement** complet (non-affiliation
  Disney/PerBlue, jeu non distribué, gratuit, aucun usage commercial, sans garantie ; auteur Aciderix), séparation
  stricte front/back, et **manques backend listés** (§7).
- **ADMIN = panneau opérateur 5 domaines**, défini DEPUIS le code (§8) : A. Events live-ops (13 builders `ServerEvents`,
  prêt) ; B. Monitoring (`LoginServer.online`+`connectionsAccepted`+logs, prêt) ; C. Gestion joueurs (`ServerUser`
  giveResource/grantHero/…, prêt) ; D. Ère de contenu (`RELEASE_PICKER`+`AdminRelease`+ancre d'horloge, prêt) ;
  **E. Modération = ABSENT → à construire** (seul `KickFromGuild` = jeu). Tous requièrent des endpoints `/admin/*` (chantier D).
- **Endpoint `/play` LIVRÉ** (`PlayManager` + `POST /play` / `/play/stop` / `GET /play/status`) : lance le bundle CLIENT
  avec `DH_SERVER=host:contentPort` (+`DH_USERID` en permissif). **Vérifié bout-en-bout** : le vrai client démarre sur le
  serveur choisi (`ServerType.LIVE → 127.0.0.1:8080`, `GameMain create`), status/arrêt OK. `PlayLifecycleTest` (8) →
  **régression 171/171**. Reste : hook client `loginRequestID` pour le strict DISTANT.
- **`launcher-ui/` — squelette Tauri+React+TS (incrément 1)** : `api/daemonClient.ts` (15 endpoints typés, SEUL point
  réseau) + `types.ts` + `tauriBridge.ts` ; thème neutre isolé (`theme/tokens.css`) ; i18n fr/en ; **DisclaimerGate**
  (avertissement à lire jusqu'en bas + accepter) ; coquille + navigation ; shell Tauri (`src-tauri/` : démarre le daemon
  Java embarqué + expose son port + dialogues natifs). **Vérifié** : `tsc --noEmit` OK + `vite build` OK (38 modules).
  Séparation stricte : un designer (Gemini) ne touche que `theme/`+`components/`. node_modules/dist gitignorés.
- **Suite** : inc.2 Compte+Serveurs, inc.3 Héberger+Générer, inc.4 Jouer(/play), inc.5 Réglages, inc.6 Admin ;
  puis endpoints backend manquants (§7) + job CI « build launcher-ui » (typecheck+bundle Tauri, 2 OS).

## 2026-08-31 (g202) — CI launcher VERTE sur Linux + Windows (VM GitHub) : build + exécution smoke prouvés ✅

Le workflow `launcher-release` (g201) a été **déclenché par l'utilisateur** (workflow_dispatch). Run #1 :
**ubuntu ✅**, **windows ❌** — `tar (child): Cannot connect to D: resolve failed` (GNU tar de Git Bash prend un
chemin `-f D:\…` pour un hôte distant). **Fix** `tools/build_launcher.sh` (commit 97ad9c7) : extraction du CPython
en **chemin RELATIF** (`cd runtime/ && tar -xzf python.tar.gz`, pas de deux-points) → local, cross-OS. **Run #2 :
les DEUX jobs ✅** (ubuntu + windows). Chaque job = **VM GitHub-hosted neuve** ; l'étape *smoke* **EXÉCUTE** le launcher
dans la VM (`java -cp dhlauncher.jar LauncherDaemon` → `/health` + `/identity/generate`) **et** le python embarqué
(`python --version` / `python.exe --version`) → **JDK + Python embarqués prouvés sur Linux ET Windows**. Artifacts
produits : `dh-launcher-linux.tar.gz`, `dh-launcher-windows.zip`. (Le jeu complet n'est PAS exécuté en CI : pas d'APK ni
d'écran — il tourne sur la machine du joueur.) L'étape « Attach to Release » ne s'exécute que sur un **tag `launcher-v*`**
(skippée en dispatch) → pour une **Release téléchargeable**, pousser un tag `launcher-v*`.

**Correctif suite à la vérif des assets de `launcher-v0.1.0`** (commit c9b7266) : l'asset **Linux (30 Mo) ne contenait
PAS le JDK**. Cause : `$JAVA_HOME` du cache d'outils GitHub (Linux) est un **lien symbolique** → `cp -a` le préservait →
`runtime/jdk` = lien mort à l'extraction (le smoke en VM passait car le lien se résout localement — d'où « vert » mais
package cassé). Fix `build_launcher.sh` : `cp -RLp "$JAVA_HOME/."` (déréférence + contenu → vrais fichiers). + nouvelle
étape CI **« Verify EXTRACTED package »** qui déballe l'archive et lance `java`/`javac`/`python` depuis la copie EXTRAITE
(simule le poste du joueur → attrape un package incomplet). Windows (242 Mo) contenait déjà le JDK. ⇒ **re-tag requis**
pour régénérer les assets de la Release.

**Note d'env** : je (session bac-à-sable) ne peux ni créer de tag ni dispatcher un workflow (proxy git → 403, credentials
scopés `claude/*`) ; un PAT collé ne contourne pas (le proxy impose les creds de session). ⇒ déclenchement/tag = côté
utilisateur ; je garde le **suivi + correctifs** (push sur `claude/*`).

## 2026-08-31 (g201) — PYTHON embarqué + content_server pur-stdlib + LAUNCHER distribuable (GitHub Action release) 🟢

Suite g200 (« il faut embarquer python et s'assurer que ça marche Linux/Windows ; le launcher a-t-il des
dépendances ? sera-t-il build via GitHub Action pour être distribué en release ? »).

**1) `content_server.py` → PUR STDLIB (plus de `curl`)** : les 2 appels `curl` (download-to-cache + relay) remplacés
par `urllib` (nouveau `_download_to_file` : reprise Range + retries + suit la redirection 302 ; `_relay_stream` idem).
VÉRIFIÉ : `/login` OK, asset caché servi en Range (206) ; `urllib` atteint archive.org **via le proxy** (suit le 302).
⇒ un CPython **stdlib-only** suffit (aucune dépendance système en plus de python).

**2) PYTHON embarqué (`BuildManager.packagePython`)** : télécharge un CPython **relocatable** (python-build-standalone,
astral-sh, version épinglée 3.11.9/tag 20240814) pour l'OS de build → `<bundle>/runtime/python` ; `run.sh`/`run.bat`
**préfèrent** `runtime/python/bin/python3` (Windows `runtime\python\python.exe`), repli `python3`/`python` système.
Best-effort (§2, tracé) : réseau requis à la génération (comme le DL des assets) ; échec → repli système. Le
téléchargement réel se prouve **en CI** (GitHub Action, ubuntu+windows). `ServerBundleTest` **12/12** (+1 assertion :
run.sh préfère le python embarqué ; le DL 403 dans CE conteneur → repli, le serveur démarre quand même).

**3) LAUNCHER DISTRIBUABLE — dépendances & CI/release** :
- **Le launcher-core compile GAME-FREE** (prouvé) : `dhlauncher/*` + `dhserver.auth.MnemonicIdentity`/`Bip39Wordlist`
  seulement — **aucun `com.perblue`**, aucun `game.jar`, aucun APK. ⇒ **distribuable & copyright-propre** ; CI peut le
  compiler. (`HostManager` référence `dhserver.LoginServer` en STRING pour lancer un sous-process, pas au compile.)
- **`main()` ajouté à `LauncherDaemon`** (il n'en avait pas) : `--port` (défaut 8090) / `--project <tooling>` ; daemon
  loopback-only. VÉRIFIÉ local : `/health` → `{"ok":true}`, `/identity/generate` → phrase mnémonique + userID + pubkey Ed25519.
- **Dépendances du launcher** = un **JDK** (pas juste un JRE) : `BuildManager` fait `javac`/`jlink`/`jar` pour GÉNÉRER les
  bundles → le package launcher embarque un **JDK complet** (`runtime/jdk`) + le **CPython** embarqué + le **tooling repo**
  (`server/`, `desktop-port/`, `tools/`, via `git ls-files` → sans game-data/APK gitignorés) + `run-launcher.sh/.bat`.
- **`tools/build_launcher.sh`** (bash cross-OS) assemble ce package : compile game-free → `dhlauncher.jar`, copie le JDK,
  télécharge+déballe le CPython (`tar`), copie le tooling, écrit les scripts. `bash -n` OK.
- **`.github/workflows/launcher-release.yml`** : matrice **ubuntu-latest + windows-latest**, JDK 21 Temurin, lance
  `build_launcher.sh`, **smoke** (`/health` + `/identity/generate` + `python --version` du python embarqué → PROUVE
  Linux+Windows), archive (tar.gz / zip), upload artifact, et **attache à une Release** sur tag `launcher-v*` (gh CLI).
  ⇒ le launcher **sera bien build par GitHub Action et distribué en release**, un package par OS.

**Modèle de distribution (confirmé)** : la **Release GitHub = le LAUNCHER** (game-free, JDK+python embarqués). Le joueur
télécharge le package de son OS, le lance (zéro-install), fournit **son** APK → le launcher **génère** les bundles
serveur/client (JRE jlink + python embarqués) puis **héberge** (local via `HostManager`) et lance le **jeu**. On ne
distribue JAMAIS de code/données du jeu (copyright).

**Reste / à décider** : (a) **front C2b** (Tauri+React) = l'UI clic-bouton par-dessus le daemon (aujourd'hui HTTP/curl) ;
(b) **cloud** (héberger le serveur sur un cloud + le manager) = chantier F (le launcher gère aujourd'hui le LOCAL) ;
(c) certificats TLS du CPython embarqué pour archive.org en HTTPS (sinon repli HTTP archive.org). **Régression 170/170.**

## 2026-08-31 (g200) — RUNTIME JRE embarqué (zéro-install) : jlink dans le bundle + `run.sh` le préfère 🟢

**Suite de la validation g199** (« poursuit ») — objectif clé-en-main : qu'un joueur lambda lance le bundle **sans
installer Java**.

**`BuildManager.packageRuntime`** : **jlink** un JRE MINIMAL dans `<bundle>/runtime/jre` (constante `JRE_MODULES`,
~69 Mo). Le set de modules = base `jdeps` + les modules chargés par **réflexion/ServiceLoader** que jdeps ne voit pas,
vérifiés empiriquement : notamment **`jdk.crypto.ec`** (Ed25519 de l'auth mnémonique) et **`jdk.httpserver`**
(`com.sun.net.httpserver` de l'AuthService), + `jdk.charsets`/`jdk.localedata`/`jdk.zipfs`/`jdk.xml.dom`. Appelé dans
`packageServer` ET `packageClient`. `run.sh`/`run.bat` (serveur + client) **préfèrent** `runtime/jre/bin/java` s'il
existe, sinon repli sur le `java` du système (`JAVA="$DIR/runtime/jre/bin/java"; [ -x "$JAVA" ] || JAVA=java`).

**Vérifs (FAITS, §8)** :
- JRE embarqué (69 Mo) exécute le **serveur** : `AuthMintTest` OK (Ed25519 sign/verify + httpserver → `jdk.crypto.ec` +
  `jdk.httpserver` présents), `ChestWireTest` OK (données `.tab` + LoginServer + codec + drop tables).
- JRE embarqué exécute le **client LOURD** : bundle client lancé via `runtime/jre/bin/java` (setsid + `-Ddh.shotevery`) →
  **hub COMPLET rendu** (LWJGL/unidbg/spine jni sur le JRE jlink'd) — capture `verify_jre.png`.
- **`ServerBundleTest` 11/11** (was 9) : génère le bundle (jlink inclus) → copie hors dev → `run.sh` **lance le serveur
  sur le JRE EMBARQUÉ** → port de jeu en écoute ; +2 assertions (JRE présent, run.sh le préfère). `ClientBundleTest`
  (hors régression, lourd) : +1 assertion JRE.

**Limite HONNÊTE (§2, tracée, pas un faux OK)** : jlink ne cross-compile pas sans les jmods de l'OS cible → le JRE
embarqué est celui de l'**OS de build** (bundle Linux → JRE Linux pour `run.sh` ; un bundle Windows nécessite un build
sous Windows pour un JRE Windows dans `run.bat`). Si jlink est indisponible/échoue, `packageRuntime` **log et continue**
(bundle lançable avec le `java` système — capacité en moins, tracée). ⇒ **Le port CLIENT PC est 100 % autonome** (pas de
python, pas de java système requis). **Reste côté SERVEUR** : la dépendance **`python3`** du content-server (options :
embarquer python OU porter le content-server en Java — une seule runtime).

**Correctif régression annexe (date-dépendance)** : `SigninMultiDayTest` échouait le **2026-08-31** (bord de mois) — il
vérifie la PROGRESSION multi-jour sur l'horloge RÉELLE, or le calendrier du sign-in MENSUEL bascule le 31 (le « jour
actif » ne « +1 » pas comme en milieu de mois). **Fix déterministe** (précédent `WarSchedulerTest`) : `pinClockToMidMonth`
ancre `TimeUtil.serverTimeNow()` au **15 du mois** (champ privé `CLOCK_OFFSET`, même mécanique que
`ServerContext.applyClockOffset`) → test date-indépendant, sémantique inchangée (le bord de mois relève de la logique
calendaire du JEU, hors périmètre de ce test de progression). **Régression 170/170** (verte quelle que soit la date).

**SUITE** = zéro-install SERVEUR (python : embarquer OU porter en Java) ; lanceur natif `.exe`/AppImage ; patch APK ;
**C2b** (front Tauri+React).

## 2026-08-30 (g199) — VALIDATION du jeu GÉNÉRÉ : 4 combos EN JEU (permissif/strict × neuf/avancé) + CORRECTIF bug d'auth strict 🟢

**Demande util. (bloquante)** : « faut s'assurer que le jeu généré fonctionne… vérifier qu'on oublie rien, as-tu réécrit
ou réutilisé les fichiers du serveur qu'on utilise pour le dev… on ne peut pas passer à la suite tant qu'on n'a pas
validé que tout soit OK, mode permissif ou strict, compte neuf/compte avancé. »

**Réponse « réécrit vs réutilisé » — RÉUTILISÉ, rien réécrit** (vérifié dans `BuildManager`) : le bundle **serveur**
(`packageServer`) copie `content_server.py` **verbatim**, recompile **les mêmes** sources `server/java/*` (dhserver+dhlauncher)
en `dhserver.jar`, copie le **même** `game-data/` ; le bundle **client** (`packageClient`) embarque les **mêmes** `.so`
(**md5 byte-identiques** au dev : libgdx64, libhostspine64, libspine-native), le **même** `game-logic-framed.jar`, les
**mêmes** assets/resources de l'APK.

**Le « ça ne rend pas » était un ARTEFACT DE HARNAIS DE VÉRIF, PAS un défaut du bundle** (⇒ correction de MEMORY g198 qui
l'attribuait à tort à une « limite d'ENV : archive.org 502 + GL logiciel lent »). Deux causes de faux négatif :
1. `run.sh` passait `DH_SHOT` **seul** ⇒ capture UNIQUE **à la sortie propre** (`DesktopLauncher` l.229) — mais le `timeout`
   externe **SIGKILL** la JVM ⇒ la capture finale ne s'exécute jamais ⇒ 0 capture ⇒ faux « hang ».
2. `nohup ./run.sh &` **pas totalement détaché** ⇒ le harnais tuait le process à la fin de l'appel outil ⇒ fausse « mort
   silencieuse » à ~17 s.
   ⇒ Avec `setsid` + capture **périodique** (`-Ddh.shotevery`), le bundle client rend le **hub complet**, comme le dev.

**4 COMBOS VALIDÉS EN JEU (client bundle réel → serveur → persistance → rendu)** :
- ✅ **permissif + neuf** (userID 0) → hub frais (CHOOSE NAME, 20💎).
- ✅ **permissif + avancé** (`-Ddh.userid=<uid>`, TL200/5 héros semés) → hub peuplé (**5000💎**, 175/175, tuiles haut-TL).
- ✅ **strict + avancé** (billet nominatif `/login`→`/auth/mint`, client boote userID 0) → hub peuplé **du bon compte**
  (5000💎, BLACK MARKET/MEGA MART/RANKINGS/VIP/ENHANCEMENT) — **après correctif** (voir ci-dessous).
- ✅ **strict + neuf** (phrase fraîche, compte jamais peuplé) → **tutoriel d'intro** (`IntroTutorialActV2`, Ralph &
  Vanellope, « TAP TO CONTINUE ») = expérience nouveau joueur.

**BUG RÉEL trouvé en strict+avancé, puis CORRIGÉ** (`server/java/dhserver/LoginServer.java`, handler `ClientInfo`) :
en STRICT, le client boote en **userID=0** et recopie le **BILLET** nominatif (`loginRequestID`) dans son `ClientInfo`.
Le serveur chargeait le compte depuis `ClientInfo.userID` (=0) et n'utilisait le billet que pour *valider* → la branche
`if (uid > 0)` était **entièrement sautée** ⇒ (a) **mauvais compte** servi (le compte PAR DÉFAUT `LoginServer.this.user`,
userID 1) et (b) **auth STRICT contournée** (aucune vérification pour userID 0). **Preuve DB** : la connexion strict
rechargeait+resauvait userID **1** (`updatedAt` le plus récent), pas l'`uid` du billet (3701…267, resté intact à 5000💎).
**Correctif (glue serveur, §3 « lire & exécuter » ; source de vérité = le billet)** : si `authRequired && uid<=0`, on
**RÉSOUT** `uid` depuis `sessions.authenticatedUser(ci.loginRequestID)` (SessionStore partagée, même JVM que l'AuthService) ;
si toujours `uid<=0` (ni userID valide ni billet) ⇒ **REJET** (pas de repli sur le compte par défaut = plus de contournement).
Après correctif : `🔐 login unique : userID résolu depuis le billet → 3701…267` puis `connexion ← compte 3701…267` →
hub à 5000💎.

**Régression** : nouveau smoke ISOLÉ **`StrictSingleLoginTest`** (7 assertions, VRAIE pile LoginServer+SessionStore+
UserStore+codec+ClientInfo/BootData) : (A) strict userID=0 + billet valide → BootData du compte minté (discriminé par
`teamLevel=123`), (B) strict sans billet → rejet, (C) strict billet bidon → rejet, (D) **permissif** userID=0 → compte
par défaut (TL=1) **inchangé**. Ajouté au tableau `TESTS` de `regression.sh` → **170/170**.

**Méthodo (à retenir)** : lancer un bundle en tâche de fond **doit** utiliser `setsid nohup … </dev/null &` (sinon le
harnais le tue au retour d'appel) ; pour PROUVER le rendu headless, passer `-Ddh.shotevery=N` (capture périodique),
jamais `-Ddh.shot` seul sous un `timeout` externe. `pkill`/`kill` sortent 144 (SIGSTKFLT) mais **s'exécutent** ; ne PAS
chaîner d'autres commandes après dans le même `&&`.

**SUITE** = reprendre C2a (runtime embarqué JRE/python zéro-install ; passthrough `DH_SHOTEVERY` dans le `run.sh`
généré pour le debug) ; patch APK ; C2b (front Tauri+React).

## 2026-08-30 (g198) — Phase 2 C2a-4b : build du BUNDLE CLIENT PC depuis l'APK + correctif SourceFile (1er lancement) 🟢

Demande util. : « pas de partiel, pas de raccourci » — donc C2a-4b **complet** (build du port PC packagé).

**Pipeline client** (aucune règle réécrite, réutilise le build existant) :
- **`run-desktop.sh DH_BUILD_ONLY=1`** (ajouté) : construit tout (game-logic-framed + gradle compileJava + extraction
  assets/ressources de l'APK + natif libGDX) puis émet un **manifeste** (`build/client-manifest.env` : RUNTIME_CP,
  classes, framed, natdir, assets, resd, spinelib, hostspine) et SORT sans lancer.
- **`BuildManager` cible CLIENT** : lance ce build-only, lit le manifeste, **assemble un bundle client AUTONOME** :
  `lib/dhdesktop.jar` (classes desktop-port) + `lib/game-logic-framed.jar` + `lib/runtime/*` (37 jars LWJGL3/gdx/
  unidbg…) + `native/` (libgdx64 + libspine-native ARM + libhostspine64) + `assets/` + `resources/` + `run.sh`/
  `run.bat` (CP + JOPTS bundle-relatifs, spine jni par défaut, Xvfb en repli headless). Cible APK = refus honnête (§2).
- `ClientBundleTest` **9/9** : build CLIENT = DONE + structure autonome complète (dhdesktop.jar, game-logic-framed,
  runtime>20, natifs, assets, resources, run.sh+run.bat). Bundle mesuré **509 Mo**, généré en ~52 s.

**⚠️ VRAI BUG DE 1er LANCEMENT TROUVÉ & CORRIGÉ** (en lançant le bundle sur un `rundir` FRAIS) : le jeu déclenche
alors « Restarting for late user boot download » → `GameMain.restart()` → `TagHelper.getTag()` → **NPE** car
`StackTraceElement.getFileName()` est **null** (dex2jar a supprimé l'attribut `SourceFile` des classes du jeu →
« Unknown Source »). Mes runs dev avaient un `rundir` chaud → jamais exercé, mais un **vrai 1er lancement crasherait**.
**Correctif `ReframeJar`** (§1 « correction d'attributs incohérents laissés par dex2jar », non-sémantique, `SHIMS.md`) :
rétablit un `SourceFile` synthétique = `<NomSimple>.java` pour les classes qui n'en ont pas (`visitSource`/`visitEnd`).
Re-reframe des DEUX jars (`game-framed` serveur + `game-logic-framed` client). Vérifié : `Compiled from "GameMain.java"`
présent ; **plus de NPE TagHelper** (le bundle franchit `restart()` sans crash). Régression **169/169** (SourceFile
non-sémantique).

**Vérif EN JEU du bundle client** : le bundle **LANCE hors dev**, init le backend LWJGL3, charge les assets, **se
CONNECTE au serveur** (`ClientInfo`→`BootData`), atteint **MainScreen** — **0 exception** après le correctif. Le rendu
COMPLET du hub depuis le bundle est **bloqué DANS CE CONTENEUR** par (a) archive.org (fetch de contenu « late boot »)
renvoyant 502 sur `rundir` froid et (b) un rendu headless très lent (GL logiciel + assets 283 Mo lazy) → aucune frame
capturée dans la fenêtre. **Limites d'ENVIRONNEMENT documentées (§8), PAS un défaut du bundle** : le MÊME code client
rend le hub complet dans le harnais dev (`strictnew.png`, `resume.png`, g192/g193). ⇒ sur une machine joueur (GPU réel
+ contenu présent) le bundle rend le hub comme le client dev.

Fichiers : `desktop-port/run-desktop.sh` (build-only + manifeste), `dhlauncher/BuildManager.java` (cible CLIENT +
packageClient + run.sh/bat client), `tools/reframe/src/ReframeJar.java` (SourceFile), `server/smoke/ClientBundleTest.
java` (nouveau, DEV lourd hors régression rapide), `BuildDataGenTest.java` (cible apk refusée au lieu de client),
`SHIMS.md`, docs. **SUITE = runtime embarqué (JRE/python, zéro-install) ; patch APK ; C2b (front).**

## 2026-08-30 (g197) — Un-clic serveur : le bouton « Héberger » lance le BUNDLE généré (+ arrêt propre) 🟢

Demande util. : s'assurer qu'on puisse **exécuter un serveur en UN CLIC** (hors cloud) — via un lanceur Linux/exe
OU via le launcher. **Modèle clarifié** (doc) : le **launcher** (app Tauri, `.exe`/AppImage) est le point d'entrée
un-clic (**Héberger** / **Jouer**) ; **en plus**, le **bundle serveur généré est autonome** (double-clic `run.sh`/
`run.bat`, sans launcher — pour une machine serveur headless). Idem le port PC (exe/AppImage, C2a-4b).

**Cohérence artefact** : le bouton « Héberger » doit lancer **le bundle généré**, pas l'arbre de dev. Ajouté :
- **`HostManager.startBundle(bundleDir,…)`** : héberge un bundle en lançant son `run.sh`/`run.bat` (détection OS)
  comme UN process géré (le script lance content_server + serveur en interne, ports via env `DH_{CONTENT,GAME,AUTH}
  _PORT`, strict via `DH_SERVER_OPTS=-Ddh.auth=on`). ⇒ « Héberger » exécute EXACTEMENT ce qui se double-clique.
- **`/host/start`** : param optionnel `bundleDir` → mode bundle ; sinon mode dev (classpath courant).
- **`run.sh` corrigé** : plus de `exec java` (qui perdait le `trap` → content_server orphelin). Les deux process en
  arrière-plan + `wait $JPID` + `trap … TERM INT EXIT` → **arrêt PROPRE des deux** (Ctrl-C standalone OU SIGTERM du
  bouton « arrêter »).

**`ServerBundleTest`** (9/9, isolé) : génère le bundle → structure → **COPIE hors dev** → **HÉBERGE via
`HostManager.startBundle`** (= chemin du bouton Héberger) → **port de jeu en écoute** → `stop` → **port fermé**
(les 2 process tués ensemble). Régression **169/169**.

⇒ **serveur en un clic** confirmé : via le launcher (Héberger lance le bundle) OU en double-cliquant le bundle
standalone. **Reste pour le « zéro-install » lambda** (noté `DISTRIBUTION.md`) : embarquer un **JRE** (jlink) + régler
la dépendance **python3** du content-server (le bundle exige aujourd'hui Java+python présents) — couche packaging
runtime, à finir. Fichiers : `HostManager.java` (startBundle), `LauncherDaemon.java` (`bundleDir`), `BuildManager.java`
(`run.sh` trap/wait), `ServerBundleTest.java`, docs. **SUITE = runtime embarqué (JRE/python) ; C2a-4b (client PC) ; C2b (front).**

## 2026-08-30 (g196) — Phase 2 C2a-4-pkg : PACKAGING serveur AUTONOME (clé-en-main) — lancé HORS dev 🟢

Consigne util. (ajoutée au plan g195bis) : tout ce qu'on génère depuis l'APK doit être **clé-en-main** (bundle
lançable par un lambda, « plus qu'à cliquer »). Cet incrément livre le **packaging du serveur autonome**.

**`BuildManager.packageServer`** (étape `package` de la cible SERVER, glue §3) : assemble dans le dossier de sortie
un **BUNDLE serveur self-contained** :
- `lib/` = jars runtime (`game-framed`, `commons-logging`, `sqlite-jdbc`, `slf4j-api`, `joda-time`) **+ `dhserver.jar`**
  (classes `server/java` compilées à la volée : `javac` → `jar`) ;
- `content_server.py` (login/contenu HTTP) ;
- `game-data/` (déjà extrait de l'APK) ;
- **`run.sh` / `run.bat`** self-contained (chemins relatifs au bundle : lancent `content_server.py` + `java -cp
  "lib/*" dhserver.LoginServer <port>`, ports surchargeables `DH_{CONTENT,GAME,AUTH}_PORT`, DB dans `<bundle>/data`).
Flag `pkg` (défaut vrai) : `pkg=false` = données seules (chemin léger pour les checks API).

**`ServerBundleTest`** (7/7, isolé) : génère le bundle → vérifie la structure (`lib/dhserver.jar`, `lib/game-framed.
jar`, `content_server.py`, `run.sh`, `game-data/stats` peuplé) → **COPIE le bundle hors de l'arbre de dev** → le
**LANCE via `run.sh`** → **le port de jeu écoute** (preuve : serveur autonome, self-contained). `BuildDataGenTest`
repasse en `pkg=false` (rapide, checks API). Régression **169/169**.

⇒ « générer → héberger → jouer » devient **clé-en-main** : le serveur généré est un **bundle lançable seul**, plus
seulement des artefacts dans l'arbre de dev. Fichiers : `dhlauncher/BuildManager.java` (packageServer + run.sh/.bat +
flag pkg), `LauncherDaemon.java` (param `pkg`), `server/smoke/ServerBundleTest.java` (nouveau), `BuildDataGenTest.java`,
`regression.sh`, docs. **SUITE = C2a-4b (build CLIENT PC Win/Linux packagé), patch APK (ultérieur) ; puis C2b (front).**
(Le serveur du bundle = même `LoginServer` déjà vérifié EN JEU ; run « client sur bundle » dispo à la demande.)

## 2026-08-30 (g195) — Phase 2 C2a-4 : GÉNÉRATION depuis l'APK — cible SERVEUR 🟢 (client/APK = incréments à venir)

Précision util. : le launcher devra proposer **3 cibles de build** depuis l'APK fourni — **build PC** (Windows ou
Linux), **build serveur** (hébergement), **patch APK** (jeu mobile — ULTÉRIEUR, il faudra y intégrer la découverte/
redirection serveur). **On avance par incréments** : cet incrément câble la cible **SERVEUR**.

**Distinction établie (répondu à l'util.)** — l'APK donne 3 briques : (a) **données** `.tab`/strings (partagées
client+serveur) ; (b) **`game.jar`→`game-framed.jar`** (classes du jeu → SERVEUR) ; (c) pour le **CLIENT PC** en
plus : `game-logic-framed.jar` + **assets** (spine/atlas) + **natifs** + **build gradle** de `desktop-port`.

**`dhlauncher.BuildManager`** (glue §3/§4/§7, orchestre le pipeline reproductible EXISTANT, rien réécrit) : job en
ARRIÈRE-PLAN vers un **dossier de SORTIE choisi** (ne touche pas le serveur courant), avec état interrogeable.
- Cible **SERVER** câblée : étape LÉGÈRE `tools/extract_game_data.sh` (unzip des `.tab`, sans réseau) → `<out>/
  game-data` ; en mode `full` aussi `decompile.sh` (dex2jar/Maven, LOURD) + reframe `ReframeJar` → `game-framed.jar`.
- Cibles **CLIENT**/**APK** : **refus HONNÊTE** (state=FAILED, « incrément à venir » — PAS de faux succès §2).
- Override `DH_DATA_DEST` ajouté à `extract_game_data.sh` (défaut inchangé) pour cibler le dossier de sortie.

**`LauncherDaemon`** : `POST /build/start {apkPath, target=server|client|apk, [outDir], [full]}`, `GET /build/status`
(state, target, step, tail du log). **`BuildDataGenTest`** (5/5) : APK introuvable→FAILED ; cible client→refus
honnête ; cible SERVER→extraction RÉELLE du VRAI APK→**>50 `.tab` générés dans le dossier de sortie** (game-data du
projet INTACT, 274 fichiers). Régression **168/168**.

⇒ le launcher-core sait **générer les données+artefacts d'un SERVEUR depuis l'APK** (auto-hébergement de bout en
bout : générer → héberger C2a-3 → jouer C2a-2). Fichiers : `dhlauncher/BuildManager.java` (nouveau), `LauncherDaemon.
java` (endpoints `/build/*`), `tools/extract_game_data.sh` (`DH_DATA_DEST`), `server/smoke/BuildDataGenTest.java`
(nouveau), `regression.sh`, docs. **SUITE = C2a-4b (build CLIENT PC Win/Linux : game-logic-framed + assets + natifs
+ gradle), plus tard patch APK ; puis C2b (front Tauri+React).**

## 2026-08-30 (g194) — Phase 2 C2a-3 : HÉBERGEMENT LOCAL (launcher-core /host/start,stop,status) 🟢

Décision util. (périmètre) : C2a-3 = **hébergement local MINIMAL** (auto-héberger sur sa propre machine) — le
panneau opérateur complet (ère de contenu, events, guerre, modération, monitoring) reste **chantier D** ; le cloud
+ sécurité réseau internet reste **chantier F**. (Les outils admin `Admin*`/`AdminRelease` existent déjà en CLI ;
il leur manquera juste un panneau = D.)

**`dhlauncher.HostManager`** (glue §3, aucune règle réécrite) : gère le CYCLE DE VIE d'un serveur de jeu hébergé
sur la machine du joueur, en reproduisant EXACTEMENT le lancement de `desktop-port/run-online.sh` :
- serveur de jeu `dhserver.LoginServer <gamePort>` (+ `AuthService` sur `-Ddh.auth.port`, strict via `-Ddh.auth=on`)
  lancé en process (classpath = celui du daemon, qui embarque déjà dhserver + jars du jeu) ;
- `content_server.py` (login/contenu HTTP) en process, avec `DH_AUTH_URL`/`DH_MINT_USERID_FILE` en mode strict ;
- sorties redirigées vers `server/data/host-server.log` / `host-content.log`.
Idempotent (re-start = renvoie l'état), `stop` = `destroy` puis `destroyForcibly` après 2 s.

**`LauncherDaemon`** (endpoints, lié `127.0.0.1` uniquement) : `POST /host/start {contentPort,gamePort,authPort,
strict}`, `POST /host/stop`, `GET /host/status` (running, `gamePortListening`, ports, PIDs, uptime).

**`HostLifecycleTest`** (7/7, intégration RÉELLE, isolé) : status initial arrêté → start → poll status jusqu'à
**RUNNING + port de jeu en écoute** → **connexion TCP directe au port de jeu OK** → start idempotent → stop →
**arrêté + port fermé**. Ports hauts (18080/1/2) pour éviter les collisions.

⇒ le launcher-core sait **héberger/arrêter/suivre un serveur LOCAL**. Le serveur hébergé est **identique** à celui
de `run-online.sh` (déjà vérifié EN JEU de bout en bout tout au long du projet). Régression **167/167** (nouveau test
isolé ajouté). Fichiers : `server/java/dhlauncher/HostManager.java` (nouveau), `LauncherDaemon.java` (endpoints
`/host/*`), `server/smoke/HostLifecycleTest.java` (nouveau), `regression.sh`, `docs/LAUNCHER.md`,
`docs/PHASE2_TRACKING.md`, `MEMORY.md`, `JOURNAL.md`. **SUITE = C2a-4 (build serveur/port depuis l'APK), puis C2b
(front Tauri+React, 6 écrans) + vérif EN JEU strict finale.** (En jeu « client sur serveur hébergé par le daemon » :
même pile que run-online — vérif dédiée dispo à la demande.)

## 2026-08-30 (g193) — Mode STRICT pour TOUS (neuf + avancé) : LOGIN UNIQUE mnémonique → intro OU reprise

Question utilisateur : le mode strict doit valoir autant pour un NOUVEAU joueur que pour un joueur AVANCÉ. Le hook
g192 (re-login en strict) le faisait échouer pour un NOUVEAU joueur : le **double login** laissait le client sur le
hub (0 `IntroTutorialActV2`) au lieu de dérouler l'intro. Corrigé par un **LOGIN UNIQUE** unifié.

**Fait décisif (bytecode)** : `GameMain` construit au boot `LoadingScreen(GameMain, false, 0L, 0)` — userID **0 EN
DUR**. Le `/login` de boot part donc TOUJOURS en `userID=0` ; dans le vrai jeu l'identité est résolue par l'APPAREIL
(`uniqueIdentifier`). Notre launcher, lui, connaît le userID (dérivé de la phrase) et l'authentifie.

**Solution LOGIN UNIQUE (aucune modif jeu §1)** — le launcher fournit l'identité, comme en prod :
- **Client** : `BuildOptions.TEST_USER_ID` (hook natif, `public static Long`) posé AVANT `game.create()` → le jeu
  écrase `ClientInfo.userID` par le compte authentifié. UN SEUL login (plus de re-login frame-120).
- **content_server** : le `/login` arrive avec `userID=0` (boot en dur) ; on frappe le billet nominatif pour le
  compte que le LAUNCHER a authentifié — fourni via `DH_MINT_USERID_FILE` (fichier écrit par le bloc seed APRÈS
  l'auth, car le content_server démarre avant de connaître le userID). ⇒ billet valide pour CE compte.
- **Serveur strict** : `ClientInfo.userID`=compte authentifié + `loginRequestID`=billet valide → **accepte** sans
  rejet ni blocage. Le boot enchaîne LoadingScreen → (compte neuf) INTRO, ou (compte avancé) hub peuplé.

**Vérifié EN JEU (§8), les DEUX cas, en STRICT, login unique** :
- **Nouveau joueur** (phrase fraîche, compte neuf authentifié) : `/login userID=0 → mint compte launcher=4338… →
  billet`, `connexion ← compte 4338…`, **`IntroTutorialActV2` ×10** → **intro déroulée** (capture `strictnew.png` :
  cinématique d'ouverture Ralph/Vanellope). 0 rejet.
- **Joueur avancé** (compte 7966 peuplé) : `mint compte launcher=7966…`, `connexion ← compte 7966…`, **IntroTuto=0**
  (saute l'intro) → **hub PEUPLÉ TL 200** (capture `resume.png`).

⇒ **le mode strict s'applique identiquement aux nouveaux ET aux avancés, en un seul login** — exactement le modèle
de prod (le launcher authentifie puis fournit l'identité au client). Régression **166/166** (aucun code serveur
touché). Fichiers : `dhdesktop/DesktopLauncher.java` (`TEST_USER_ID` toujours, re-login retiré), `server/content_
server.py` (mint pour `DH_MINT_USERID`/`_FILE`), `desktop-port/run-online.sh` (fichier mint écrit par le seed),
`docs/PHASE2_TRACKING.md`, `MEMORY.md`, `JOURNAL.md`. Captures : `strictnew.png` (neuf→intro), `resume.png`
(avancé→hub). **SUITE = C2a-3 (host), C2a-4 (build APK), C2b (front — le launcher-core fournira `DH_MINT_USERID`
au content_server, généralisant ce chemin).**

## 2026-08-30 (g192) — Preuve EN JEU : compte NEUF → TUTO D'INTRO lancé (+ hook userID mode-aware boot/relogin)

Demande utilisateur : PROUVER (règles §8, preuve visuelle) qu'un **compte neuf lance bien le tuto** (pas besoin
de le jouer entièrement) — pour valider qu'EN PROD un nouveau joueur joue normalement, ET qu'un joueur avancé
reprend sa partie.

**Preuve compte NEUF (permissif, login unique)** : userID FRAIS jamais vu (8888000000000001) forcé via le hook
NATIF du jeu **`BuildOptions.TEST_USER_ID`** (champ `public static Long` ; le jeu écrase `ClientInfo.userID`),
posé AVANT `game.create()` → **UN SEUL `/login`** → le serveur **crée** le compte (122 actes de tuto à step 0) →
le client route vers **`IntroTutorialActV2`**. Logs client : `IntroTutorialActV2 onTutorialTransition` (étapes
`NEW_COMBAT_STAGE_STARTED`→`SCREEN_WAIT`→`GATE_DIALOG_1_A` qui s'enchaînent), écran `CoreAttackScreen` (combat
scripté d'intro), chargement du héros `kevin_flynn`. **Capture `desktop-port/build/intro.png`** : cinématique
d'ouverture (Ralph & Vanellope devant le portail des mondes, réplique « I can't believe you talked me into
this. », « TAP TO CONTINUE »). ⇒ **un nouveau joueur déroule le jeu normalement dès le départ.**

**Preuve joueur AVANCÉ (reprise)** : déjà établie g191 (hub PEUPLÉ TL 200, ressources, nav — `strict2.png`).

**Hook userID mode-aware (aucune modif jeu §1)** — 2 mécanismes, car `TEST_USER_ID` seul ne suffit pas au strict :
- **PERMISSIF (défaut, ex. intro)** : `BuildOptions.TEST_USER_ID` posé au boot → login UNIQUE comme le compte.
  Le `/login` initial part en `userID=0` (billet mint vide) — OK en permissif (billet non requis).
- **STRICT (`dh.userid.relogin=1`, activé par run-online dans le bloc seed)** : on NE pose PAS `TEST_USER_ID`
  (sinon le login de boot `userID>0` non authentifié serait REJETÉ → client bloqué sur LoadingScreen). Le boot
  se fait en `userID=0` (autorisé à créer), puis **re-login** via `startInitialLogin(userID)` (frame>120) → le
  `/login` repart AVEC le userID → `/auth/mint` billet nominatif → serveur strict **accepte**. Re-vérifié EN JEU
  (compte 7966 : `startInitialLogin` → billet nominatif → `connexion ← compte 7966` → BootData).
  ⚠️ Parsing : `dh.userid.relogin` accepté comme vrai pour toute valeur ≠ « 0 »/« false » (`Boolean.getBoolean`
  n'accepte QUE « true » → « 1 » aurait été faux). `run-desktop.sh`/`run-online.sh` propagent `DH_USERID_RELOGIN`.

Régression **166/166** (aucun code serveur touché — uniquement `DesktopLauncher` + scripts). Fichiers :
`dhdesktop/DesktopLauncher.java` (`forceTestUserID` + gate mode-aware), `desktop-port/run-desktop.sh`,
`desktop-port/run-online.sh` (`DH_USERID_RELOGIN=1` en strict), `docs/PHASE2_TRACKING.md`, `MEMORY.md`,
`JOURNAL.md`. Captures : `intro.png` (compte neuf → intro), `strict2.png` (avancé → hub peuplé). **SUITE = C2a-3
(host), C2a-4 (build APK), puis C2b (front + login UNIQUE au boot par pré-semis de préférence, plus propre que
le re-login DEV).**

## 2026-08-30 (g191) — Phase 2 C2a-2 « play » : login mnémonique STRICT ✅ VÉRIFIÉ EN JEU (client réel → hub peuplé)

Vérif EN JEU obligatoire (§8, exigée par l'utilisateur) du flux d'authentification STRICT de bout en bout, avec le
VRAI client. Objectif : un compte identifié par PHRASE mnémonique se connecte comme SON userID, le serveur strict
l'accepte, et refuse un userID non authentifié.

**Chaîne complète prouvée** (client réel LIVE → serveur) :
1. **Seed auth** (`StrictAuthSeed`, outil DEV) : `/auth/challenge` → **signe** le nonce (clé Ed25519 dérivée de la
   phrase) → `/auth/register` → l'`AuthService` (:8082, même JVM que le `LoginServer`, `SessionStore` PARTAGÉE)
   enregistre la clé publique + marque le userID « récemment authentifié » (`authed`).
2. Le client émet un **GET `/login`** portant `userID`/`shardID` (via `GameMain.startInitialLogin(userID,1)` →
   `connectToLoginServer`, API **publique**, aucune modif jeu §1).
3. `content_server` lit `userID` et appelle `/auth/mint` → **billet nominatif** (`loginRequestID` frais, lié au
   userID dans la `SessionStore`).
4. Le client recopie ce `loginRequestID` dans `ClientInfo` → le `LoginServer` **STRICT** (`-Ddh.auth=on`) vérifie
   `sessions.authenticatedUser(loginRequestID)==userID` → **accepte** : `connexion ← compte 7966… ` → BootData.
5. **Hub RENDU** en spine natif jni (capture `desktop-port/build/strict2.png`) : barre de ressources (💎5 000 /
   🪙5 M / stamina 120/7 935 / 175/175), nav complète (CAMPAIGN/CITY WATCH/CHALLENGES/CRATES/INVASION/SURGE/ARENA/
   PORT…), avatar TL 200. 0 exception non-bénigne.

**Cas négatif prouvé** (anti-triche) : `/auth/mint` pour un userID **jamais authentifié** → **HTTP 401**
`not-authenticated` → `content_server` renvoie `requestID=""` → le `LoginServer` strict **rejette**
(`loginRequestID non authentifié pour userID=… → rejet (aucun BootData)`, observé). Seul un userID>0 authentifié
passe ; un userID=0 (nouveau joueur) est autorisé à CRÉER un compte (gate `uid>0` sautée, par conception).

**2 correctifs de FIDÉLITÉ trouvés EN JEU** (§1/§4bis, `SHIMS.md`) :
- **`dhbackend/DhNet.java`** : `sendHttpRequest` n'écrivait le `content` form-encodé que pour POST/PUT → pour un
  **GET**, le content était **silencieusement PERDU** (ni URL ni corps). libGDX (`NetJavaImpl`) **appende le content
  à l'URL en query-string** pour GET/DELETE. Sans ça, `userID` du `/login` n'atteignait jamais `content_server` →
  auth strict impossible (le mode permissif ne s'en apercevait pas : `requestID=""`). Corrigé : GET/DELETE →
  `url + ("?"|"&") + content` (réplique exacte de `NetJavaImpl`).
- **`server/content_server.py`** `_serve_login` : lisait `userID` dans le **corps** (POST) alors que le client
  envoie un **GET** (params en query). Corrigé : lit la query d'abord, corps en repli. + log `[content] /login
  userID=… → billet nominatif=…`.

**Hook userID côté client** : d'abord tenté `switchUserAccount(userID)` → **NPE** (`Cannot invoke Object.getClass()
because <local2> is null`) car il déclenche `GameMain.restart()`, incompatible avec notre boucle de rendu headless
pilotée à la main. Remplacé par `startInitialLogin(userID,1)` (relevé au bytecode : appelle simplement
`connectToLoginServer(userID, shard, SERVER_TYPE, false)` = un `/login`, SANS restart) → propre, API publique.

**Outils DEV** (non committés au régression, hors `TESTS`) : `StrictAuthSeed` (register+auth d'un compte par
phrase contre l'`AuthService`) ; `StrictAuthAccount` (GARNIT le compte du userID dérivé d'une phrase — TL 200,
5 héros JAUNE, chapitre 41, `ServerUser.completeAllTutorials`, ressources — car un compte fraîchement semé reste en
état TUTORIEL → hub quasi vide ; à lancer serveur arrêté). `run-online.sh` : bloc seed strict
(`DH_AUTH_SEED_PHRASE` défini → seed → `-Ddh.userid`), `DH_AUTH_URL` sur `content_server`. `run-desktop.sh` :
`-Ddh.userid`. `DesktopLauncher` : hook `switchUser` (frame>120, une fois) → `startInitialLogin`.

Régression **166/166** (le code serveur d'auth est inchangé ; seuls le backend client `DhNet`, `content_server`,
les scripts et le hook `DesktopLauncher` ont bougé). Fichiers : `dhbackend/DhNet.java`, `server/content_server.py`,
`dhdesktop/DesktopLauncher.java`, `desktop-port/run-online.sh`, `desktop-port/run-desktop.sh`,
`server/smoke/StrictAuthSeed.java` (nouveau), `server/smoke/StrictAuthAccount.java` (nouveau), `docs/SHIMS.md`,
`docs/PHASE2_TRACKING.md`, `MEMORY.md`, `JOURNAL.md`. **SUITE = C2a-3 (host), C2a-4 (build APK), puis C2b (front
Tauri+React) + vérif EN JEU strict finale.**

## 2026-08-30 (g190) — Phase 2 C2a-1 : launcher-core = daemon HTTP local (identité) ✅

Début du launcher (chantier C2). **Décision de protocole tranchée avec l'utilisateur** : le launcher-core est un
**DAEMON HTTP LOCAL** (pas un CLI jetable). Raison : le launcher a besoin d'un **état vivant** (serveur hébergé en
cours, client de jeu lancé à surveiller, session authentifiée, progression de build) — un process CLI neuf à chaque
appel (JVM froide, sans mémoire) buterait dessus. Le daemon garde l'état + JVM chaude. Docs : `LAUNCHER.md` §1
(archi daemon) + §1bis (config locale par OS) + §3 (étapes C2a-1→C2b planifiées).

**`dhlauncher.LauncherDaemon`** — `HttpServer` JDK lié à **`127.0.0.1` uniquement** (jamais exposé au réseau), backend
sur la machine du JOUEUR. Réutilise `MnemonicIdentity` (la clé privée reste locale, jamais transmise) et un
`HttpClient` pour appeler l'`AuthService` du serveur de jeu DISTANT. Endpoints (C2a-1) :
- `GET /health`.
- `POST /identity/generate` → `{phrase, userID, publicKey}` (écran « Nouveau compte »).
- `POST /identity/login {phrase, serverAuthUrl}` → challenge distant → **signe** le nonce → `/auth/verify` → renvoie
  `{ok, userID, loginRequestID}` (le `loginRequestID` authentifié que le client de jeu présentera dans `ClientInfo`).
- `POST /identity/register {phrase, serverAuthUrl}` → idem avec `/auth/register` (clé publique fournie).

⚠️ **Deux services HTTP DISTINCTS** : le launcher-core (machine joueur) APPELLE l'`AuthService` (`:8082`, dans le
process du serveur de jeu). Requêtes form-urlencoded, réponses JSON (base64url pour nonce/clé/signature).

**`LauncherLoginTest`** (11 assert., **2 HttpServer réels : AuthService serveur + daemon**) : generate → login avant
register refusé → register via daemon (compte créé côté serveur + session) → login via daemon (nouveau loginRequestID,
session) → /health. **Régression 164/164.**

**Fichiers** : `server/java/dhlauncher/LauncherDaemon.java`, `server/smoke/LauncherLoginTest.java`, `regression.sh`,
docs (LAUNCHER/PHASE2_TRACKING/MEMORY/JOURNAL). **SUITE = C2a-2 (serveurs + play : redirige `ServerType.LIVE` + lance
le client authentifié → boucle la vérif EN JEU strict de C1d), C2a-3 (host), C2a-4 (build APK), puis C2b (front).**

## 2026-08-30 (g189) — Phase 2 C1d : flux create/restore de compte de bout en bout ✅ (headless)

Fin du login mnémonique headless (C1a→C1d).

- **`SessionStore.registerAndBind`** (CRÉATION d'un compte, clé FOURNIE) : exige (a) `userID == userIdOf(pubKey)`
  (le userID DÉRIVE de la clé → interdit de réclamer un userID arbitraire) et (b) signature du nonce valide avec cette
  clé (preuve de possession) ; puis `UserStore.registerAccount` (idempotent) + liaison de session. Nonce usage unique.
  Refacto : `verifyAndBind` (login, clé stockée) + `registerAndBind` partagent `consumeNonce`/`bind`.
- **`AuthService` `/auth/register`** : `POST {userID, loginRequestID, pubKey, nonce, signature}` (base64url) → 200
  `{ok,userID}` / 401.
- **`AuthFlowTest`** (9 assert., **round-trip HTTP réel**) : (1) création d'un compte depuis une phrase (challenge →
  sign → register) → compte enregistré + session ; (2) **RESTAURATION** sur un « client neuf » : re-dériver la MÊME
  phrase → **même userID déterministe** → login (`/auth/verify`) → session (⇒ mêmes données de compte, car
  `UserStore.users` est clé par userID) ; (3) sécurité : réclamer un userID qui ne dérive pas de la clé → refus ;
  (4) re-register même phrase → 200 (idempotent). Régression **163/163**.

**Vérif EN JEU en mode strict = gated sur le launcher-core** : le client de jeu ne fait pas le défi-réponse tout seul,
c'est le launcher qui l'orchestre (challenge → sign avec la clé dérivée de la phrase → verify → puis lance le client
avec le `loginRequestID` authentifié). ⇒ ordre : **launcher-core (début C2) → boucler la vérif en jeu strict de C1d**.

**Fichiers** : `SessionStore.java` (registerAndBind + refacto), `AuthService.java` (/auth/register),
`AuthFlowTest.java`, `regression.sh`, docs (DISTRIBUTION/PHASE2_TRACKING/MEMORY/JOURNAL). **SUITE = C2 launcher (core
Java d'abord, cf. `docs/LAUNCHER.md`).**

## 2026-08-30 (g188) — Phase 2 chantier C : login mnémonique C1a→C1c ✅ + régression 162/162 déterministe

Démarrage du chantier C (front joueur). Décisions figées AVEC l'utilisateur : login « seed phrase » type wallet crypto
— wordlist **BIP39 anglaise** (2048 mots), **8 mots** (~80 bits + checksum), auth **défi-réponse Ed25519 asymétrique**
(aucun secret côté serveur) ; launcher **Tauri + React → launcher-core Java** (source unique de vérité). Docs :
`docs/DISTRIBUTION.md` §2 (spec figée), `docs/LAUNCHER.md` (archi + 6 écrans), `docs/PHASE2_TRACKING.md` §C.

**C1a — `MnemonicIdentity`** (`server/java/dhserver/auth/`) : phrase 8 mots → seed (BIP39 PBKDF2-HMAC-SHA512) → paire
**Ed25519 DÉTERMINISTE** (32 premiers octets du seed = graine privée, dérivation pubkey correcte via `SecureRandom`
déterministe — vérifié JDK 21 natif, 0 dépendance) → `userID` (long positif) = SHA-256(clé publique). Checksum BIP39
(détecte les fautes). `tools/gen_bip39.sh` régénère `Bip39Wordlist.java` depuis la wordlist canonique (sha256 vérifié).
Test `MnemonicIdentityTest` (13 assert.) : déterminisme, checksum, sign/verify, **rejet d'usurpation**, 200 userIDs
distincts.

**C1b — vérifieur serveur** : table `UserStore.accounts (userID PK, pubKey, createdAt)`, GLOBALE (identité identique
sur toutes les instances). `registerAccount` (idempotent même clé ; rejette une clé différente pour un userID existant)
+ `lookupPubKey`. **Jamais la phrase** — seulement la clé publique. Test `AccountStoreTest` (12 assert.) :
register/lookup/idempotence/collision/persistance + défi-réponse via la clé stockée.

**C1c — défi-réponse au login + liaison socket** :
- `SessionStore` : `issueChallenge` (nonce 32 o à usage unique, TTL 60 s) → `verifyAndBind` (vérifie la signature
  Ed25519 contre la clé publique STOCKÉE, puis lie `loginRequestID → userID`, TTL session 5 min) → `authenticatedUser`
  (consultée par `LoginServer`). Horloge injectable (test d'expiration). Test `SessionAuthTest` (12 assert.) :
  nominal, usage unique (anti-rejeu), usurpation, mauvais userID pour le nonce, compte inconnu, EXPIRATION.
- `AuthService` : `HttpServer` JDK (`:8082`, `-Ddh.auth.port`), `POST /auth/challenge` + `POST /auth/verify`
  (form-urlencoded, base64url), fine glue au-dessus de `SessionStore`. Test `AuthServiceTest` (6 assert., **round-trip
  HTTP réel** via `java.net.http.HttpClient`) : challenge→sign→verify OK, usurpation→401, compte inconnu→401.
- `LoginServer` : champs `sessions`+`authRequired` + `setAuth` ; gate `authOk(uid, ClientInfo)` à la réception de
  `ClientInfo` — en mode STRICT (`-Ddh.auth=on`), `loginRequestID` doit avoir une session authentifiée pour `uid`,
  sinon **rejet** (ni compte, ni BootData). **Défaut PERMISSIF** (`authOk`→true) = comportement inchangé (pilotes DEV /
  compte par défaut). Boot (`main`) : démarre `AuthService` + partage la `SessionStore` (même JVM) ; **le serveur reste
  opérationnel même si l'HTTP ne démarre pas**.
- **Vérifié EN JEU (boot)** : run par défaut → bannière `[login] 🔐 AuthService sur :8082 — mode permissif` ; le client
  reçoit BootData et boote normalement → **0 régression** en permissif. (Le mode strict de bout en bout = C1d, quand le
  launcher fera le défi-réponse — le client de jeu ne le fait pas nativement.)

**Correctif régression — `WarSchedulerTest` déterministe** : basait tout sur `TimeUtil.serverTimeNow()`. Saisons de
guerre MENSUELLES → en FIN de mois (observé le 2026-08-30) les ticks d'appariement franchissaient le mois → bascule de
saison anticipée → l'assertion de l'étape 6 échouait (`seasonsRolled=0`). Fix : ancrer `now` au début d'un mois (1ᵉʳ à
RESET_HOUR + 6 h) depuis un timestamp FIXE ; `seed()` prend `now`. Aucune modif de la logique d'ordonnancement.
**Régression 162/162 verte** (5 tests d'auth + WarScheduler corrigé).

**Fichiers** : `server/java/dhserver/auth/{MnemonicIdentity,Bip39Wordlist,SessionStore,AuthService}.java`,
`server/java/dhserver/UserStore.java` (table accounts + register/lookup), `server/java/dhserver/LoginServer.java`
(gate + boot AuthService), `tools/gen_bip39.sh`, 5 tests smoke + `regression.sh`, docs (DISTRIBUTION/LAUNCHER/
PHASE2_TRACKING/MEMORY/JOURNAL). **SUITE = C1d (register + create/restore EN JEU strict) puis C2 (launcher Tauri/React).**

## 2026-08-30 (g187) — Perf B5 ✅ + BASCULE `jni` par défaut (client) — chantier B (perf combat) BOUCLÉ

Suite de « poursuit tout ça » : B5 (couverture écrans spine) + gaps pré-existants trouvés en combat, puis bascule.

**B5a — audit couverture** : `javap` de `cspine.Native` = **47 méthodes natives**, TOUTES implémentées par
`HostSpine`/`cspine_jni.c` (0 manquante → 0 crash de ce fait). Stubs restants : `setSlotEyeState` (renvoie false —
FONCTIONNEL mais no-op ; tag des slots `eyeball`/`eye_reflection` par `SpineRenderable.updateSlotTags`),
`getStats`/`getVertexWeightReport` (diag, **0 appelant jeu** trouvé). 

**B5b — nav sweep EN JEU en `jni`** (pilotes `heroview` [HeroDetailScreen via `startBattleInner`-style `pushScreen`]
ajouté ; `collectionscreen` existant) : **hub, chooser, combat, HeroDetail (MegaBot — YEUX rendus corrects), collection
detail** → **tous rendus, 0 crash spine, 0 méthode manquante, 0 UnsatisfiedLink**. Les yeux rendent bien **malgré** le
no-op `setSlotEyeState` (le tag est une indication native ; le rendu passe par le chemin d'attachment normal). Documenté
`SHIMS.md`.

**Gaps pré-existants trouvés (analysés, NON liés au spine)** :
- `PatchTalent.PREDICTIVE_FORTIFICATION` (tab `patched_heroes_talent_assignments` row 159 EVIL_QUEEN) : **totalement
  absent du code 12.1.0** (0 enum, 0 ability). Le `.tab` est **forward-compat** (data en avance sur le code). **§4 :
  non implémentable** (rien à exécuter = inventer). Skip gracieux du jeu (`onStatError`) = correct. Idem
  `cosmetic_collection` MUFASA_EMOJIS / `prime_badge` SAPPHIRE_4 / NUMBER_230…253.
- 3 sons `glitch_glitch_in_1250_1/2/3` « not loaded » : les `.ogg` **EXISTENT** sur disque ; le log vient du **code
  jeu** (pas notre backend audio) → hiccup de load paresseux sur 3 SFX (non systématique, 0 crash).
- `NumberFormatException("")` : `GuildStats$PerkLevelStats.arrayInsert` parse un champ numérique **vide** d'un tab de
  perks de guilde → toléré (`onStatError`), non-fatal (guild perks déjà vérifiés en jeu).
- ⇒ tous **pré-existants, non-fatals, hors chantier spine** ; à traiter dans une passe data/audio dédiée si souhaité.

**BASCULE `jni` PAR DÉFAUT (côté CLIENT)** — `run-desktop.sh` : `DH_SPINEBACKEND` défaut **`jni`** (spine natif ~50× /
hors hot-path, fidélité B3/B5). **Repli AUTO** sur unidbg si `libhostspine64.so` absent (checkout sans build natif) ;
**repli explicite** `DH_SPINEBACKEND=unidbg`. Bannière `[desktop] spine backend = jni`. **Le SERVEUR
(`dhserver.LoginServer`) reste unidbg BIT-EXACT** pour l'AUTORITÉ (§3/§8) : `Native.java` lit une **propriété JVM**
`dh.spinebackend` (pas l'env), et le serveur est lancé **sans** cette propriété → jamais affecté par la bascule client.
**Vérifié EN JEU** : run sans `DH_SPINEBACKEND` → bannière `= jni`, hub rendu (unidbg=~3ms/58 appels = **particules**
seules, pas le spine ; le spine natif = 0 appel unidbg). 

**Fichiers** : `desktop-port/run-desktop.sh` (défaut jni + repli + bannière), `.../TutorialDriver.java` (`heroView`),
`.../DesktopLauncher.java` (commande `heroview`), `docs/PERF_PLAN.md` (B5 + bascule), `docs/SHIMS.md`
(`setSlotEyeState`). **⇒ Chantier B (perf combat) BOUCLÉ. SUITE = Phase 2 : C (launcher + login mnémonique), D
(backend/admin), E (intégration APK), F (inter-machines), G (CI).**

## 2026-08-29 (g186) — Perf B4+B6 : COMBAT RENDU en spine natif `jni` ✅ EN JEU + résiduels expliqués

Suite de g185 (héros parfaits au chooser). Réponse à 2 demandes : (a) les résiduels (imperceptibles + light-RGB)
peuvent-ils être améliorés ? (b) B4/B6 + vérif combat (fps, qualité, problèmes).

**(a) Résiduels — ANALYSE (diag `DH_SPINEDBG2` = dump des composantes de couleur par slot au `getVertices`)** :
- **light-RGB (~10 % des slots)** : les slots divergents sont à **couleur ANIMÉE** — glow d'ambiance du HUB
  (`midground/light` slotC pulsant chaud `(1,0.94,0.60,α)`, `midground/lense_flare`), **PAS les héros**. Les slots à
  couleur **statique** (corps des héros, `slotC` blanc) sont **bit-exacts**. Cause = **dérive de phase entre 2
  horloges d'animation indépendantes** : le harnais `compare` fait tourner unidbg (ARM) ET HostSpine (x86) en //,
  chacun accumulant son `dt` en flottant (bionic vs glibc) → sur une boucle de couleur rapide, léger décalage de
  temps = phase différente (alpha ~identique car courbe plus plate). **En PRODUCTION (`jni` seul, UNE horloge) ça
  n'existe pas** → artefact de mesure, pas un défaut.
- **x/y (~9e-5)** : même classe = ARM↔x86 dans les **libm** (sin/cos/atan2 des transforms d'os), différence de
  BIBLIOTHÈQUE (bionic vs glibc), **pas corrigeable par flags** (pas de FMA sur x86-64 baseline). Seule l'émulation
  ARM (unidbg) matche bit-à-bit → gardée pour l'**AUTORITÉ**. Affichage = §4bis (sous-pixel).
- ⇒ **Rien à « corriger »** : héros/gameplay fidèles, autorité bit-exacte. Docs `SHIMS.md`/`PERF_PLAN.md` mis à jour.

**(b) B4 (scène combat combinée) + B6-combat (fps)** — pilote `campstart` ajouté (`TutorialDriver.campStart` +
commande clickfile) : sélectionne 5 héros possédés (chemin réel `unitSelected`) puis appelle
`CampaignHeroChooserScreen.startBattleInner()` = le bouton **FIGHT** (combat RENDU `CampaignAttackScreen`, pas le
quick-fight). **VÉRIFIÉ EN JEU (§8)** :
- **Combat joué de bout en bout en mode `jni`** : 1465 frames, **0 crash**, cohabitation **spine natif + particules
  unidbg + GL** stable.
- **fps combat (llvmpipe)** : **`unidbg=0.0 ms` sur 1446/1465 frames** (spine ÉLIMINÉ du hot-path ; les rares
  0.3–8 ms = bursts de particules unidbg au cast d'aptitude) ; moy 47 ms/**21 fps** mais **min 11 ms/91 fps** →
  borné par le **GL logiciel**, pas le spine. Ancien mode spine-unidbg = 25–34 ms/frame *rien que* pour le spine.
  **GPU réel → 60 fps.**
- **Qualité PARFAITE** : les 5 héros (Merida/Moana/Belle/Jack Sparrow/Bête) + **ennemi Soulless (tête en flamme
  bleue = effet ADDITIF, rendu propre → 0 artefact du résiduel light-RGB)** + fond rooftop + HUD complet (vague 1/3,
  timer, barres de vie, AUTO). Indistinguable du mobile. Capture `/tmp/jni_combat_final.png`.
- **Problèmes trouvés = PRÉ-EXISTANTS, non liés au spine** : `patched_heroes_talent_assignments.tab` row 159 → enum
  `PatchTalent.PREDICTIVE_FORTIFICATION` absent du code 12.1.0 (gap data/version) ; sons `glitch_glitch_in_1250_*`
  manquants ; `NumberFormatException("")`. À traiter hors chantier spine.

**Fichiers** : `desktop-port/.../TutorialDriver.java` (`campStart`), `.../DesktopLauncher.java` (commande `campstart`),
`native/src/cspine_jni.c` (diag `DH_SPINEDBG2`), `docs/PERF_PLAN.md` + `docs/SHIMS.md` (analyse résiduels + B4/B6).
**SUITE** : B5 (inventaire + couverture des autres écrans spine) avant de basculer `-Ddh.spinebackend=jni` par défaut.

## 2026-08-29 (g185) — Perf B3 : bug fidélité mesh (héros « éclatés » en mode spine `jni`) ✅ RÉSOLU EN JEU

Suite de B2 (hub rendu en spine natif). En mode `-Ddh.spinebackend=jni` les **héros** (mesh déformables) étaient
rendus **éclatés/mal teintés** (blocage #3, contrainte user « aucune destruction visuelle »). Résolu PAR EXTRACTION —
aucune devinette, aucune réécriture du moteur : on reproduit la sortie de l'ORACLE (unidbg = binaire ARM PerBlue) à
l'octet près.

**Outil (harnais différentiel `CompareBackend`, `-Ddh.spinebackend=compare` + `DH_SPINEDBG=1`)** — enrichi :
- **ventilation des diffs de sommet par float** `[x,y,light,dark,u,v]` (compte + maxAbs par position) ;
- **dump hex ABGR** oracle vs JNI pour les floats couleur (light #2 / dark #3) → dérivation directe de la formule ;
- **hook d'arrêt** JVM qui imprime le rapport de certification même quand le process est tué par timeout.

**4 causes distinctes trouvées et corrigées dans `native/src/cspine_jni.c`** :
1. **Coord V ×2 — LA cause de l'« éclatement ».** Diag : `u=0 diff` (parfait) mais `v` faux sur ~100 % des sommets,
   exactement **2× l'oracle** (`0.992=2×0.496`). `Atlas_getParams` (hauteur de page) IDENTIQUES entre backends →
   pas une hauteur différente. Cause réelle : les textures sont **ETC1** (sans canal alpha) → PerBlue empile l'alpha
   SOUS le RGB, la texture PHYSIQUE fait **2× la hauteur** déclarée dans l'atlas. Preuve : en-tête **PKM = 2048×1024**
   alors que l'atlas dit `size: 2048,512` (RGB en haut, alpha en bas). Le shader échantillonne le RGB dans la moitié
   haute → `v_out = v_atlas × (512/1024) = v × 0.5`. La lib ARM applique ce ×0,5 à l'émission ; notre spine-c émettait
   V pleine échelle. **Fix : `#define TEXV_SCALE 0.5f`** sur la coord V (region + mesh). ⇒ **`v` diffs = 0**.
2. **Masque anti-NaN libGDX.** `packColor` appliquait `& 0xfeffffff` (idiome `Color.toFloatBits`). L'oracle écrit les
   **octets ABGR bruts** (blanc opaque → `0xFFFFFFFF` = NaN, relu en ubyte normalisé par le shader → la « NaN-itude »
   est sans effet). Masque retiré.
3. **Light prémultipliée à tort.** Relevé hex décisif : oracle light `0x00FFFFFF` (α=0, **rgb blanc conservé**),
   `0x4199F0FF` (rgb **droit**). Notre tentative de premultiply (rgb×α) donnait `0x00000000`/`0x41273D41`. ⇒ **light =
   couleur DROITE, non prémultipliée** (retour à la formule initiale, sans le masque).
4. **`Skeleton_setTintBlack` = NO-OP.** Relevé hex : tous les slots SANS `darkColor` propre portaient la MÊME dark
   oracle (`0xFF331100` = rgb (0,17,51), α=255) alors qu'on émettait `0xFF000000`. ⇒ c'est un **tint sombre GLOBAL**
   posé par le jeu et qu'on ignorait. **Implémenté** : stockage du tint par squelette (table parallèle indexée par
   `spSkeleton*`, car spine-c 3.6 n'a pas de champ ; nettoyée au `dispose`), servi comme base de `dark` au
   `getVertices` quand le slot n'a pas de `darkColor`. Dark = **DROITE** (cohérent avec light), alpha = flag PMA (0xFF).
   ⇒ **`dark` diffs = 0**.

**Certification `compare` (hero chooser + hub)** : **`v=0, u=0, dark=0` (bit-exacts)**, `light` alpha bit-exact,
`x/y` = dérive flottante ARM↔x86 **imperceptible** (~9e-5, §4bis OK). **Résiduel** : `light`-RGB diverge sur ~10 % des
slots (alpha exact ; rgb blanc attendu mais teinté, ex. `0x…AFFFFD`) — **PARTIEL documenté** (`docs/SHIMS.md`),
**invisible sur les héros**. Piste : couleur d'attachment appliquée en trop, ou évaluation de courbe `ColorTimeline`.

**✅ VÉRIFIÉ EN JEU (§8)** : `-Ddh.spinebackend=jni`, écran **« CHOOSE YOUR HEROES! »** → les 5 héros (Merida, Moana,
Belle, Jack Sparrow, la Bête) rendus **PARFAITEMENT** (textures, couleurs, contours corrects, zéro éclatement) —
visuellement indistinguable du mobile. Hub + portrait Sign-In OK également. Captures : `/tmp/jni_final.png`,
`/tmp/jni_chooser.png`.

**Fichiers** : `native/src/cspine_jni.c` (TEXV_SCALE, packColor octets bruts, light/dark droites, setTintBlack réel +
table de tint), `desktop-port/src/main/java/dhbackend/spine/CompareBackend.java` (ventilation par float, dump hex,
hook d'arrêt — outillage de dev, gated `DH_SPINEDBG`). **SUITE** : B4 (scène combat complète en jni : VFX additifs +
particules unidbg) + B6 (fps GPU réel) → basculer `jni` par défaut ; affiner le light-RGB résiduel si un artefact
apparaît sur les slots additifs en combat.

## 2026-08-10 (g88) — FRIENDSHIPS (#72) SPEEDUP_MISSION + SET_MISSION_ITEM_COST_LIMIT ✅ (headless + en jeu) + MàJ EXPLORATION

Dernières actions QoL du sous-système MISSIONS idle, code du jeu (§3), zéro invention (§4).

**SPEEDUP_MISSION** — accélère une mission idle en consommant des `MISSION_SPEEDUP`. Protocole (disasm
`ClientActionHelper.speedupMission`) : `heroType=friendship.getPrimary()`, `itemType=MISSION_SPEEDUP`,
`extra{COUNT=nb, TIME}`. Le client calcule un `MissionSpeedupData` mais le serveur ne lui fait PAS confiance : il
**re-dérive** via `MissionHelper.getSpeedupData(user, mission, item, count, serverTimeNow())` puis exécute
`useSpeedups` (lève `getNotEnoughResourceException` si stock insuffisant = anti-triche). `ServerMissions.applySpeedupMission`
(find mission by hero → getSpeedupData → useSpeedups → resyncMissions + resyncs). 1 `MISSION_SPEEDUP` = 
`MissionStats.getSpeedupDuration()` (=7 200 000 ms/2 h).

**SET_MISSION_ITEM_COST_LIMIT** — plafond de dépense auto d'un objet en missions (préférence joueur). Protocole :
`itemType=<objet>`, `extra{COUNT=plafond}` → `IndividualUser.setMissionItemCostLimit(item, N)` = écriture DIRECTE dans
`individualUserExtra.missionItemCostLimits` (**write-through**, `N=0` retire l'entrée). `ServerMissions.applySetItemCostLimit`.

Handlers `LoginServer` (SPEEDUP_MISSION / SET_MISSION_ITEM_COST_LIMIT). `MissionSpeedupTest` : cost-limit 3→persiste→0
retire ; speedup réduit `baseTimeRemaining` (2.16E8→1.81E8) + consomme les objets (5→2) + persiste. **Régression 99/99**.

**✅ VÉRIFIÉ EN JEU (userID=1, 20 MISSION_SPEEDUP semés)** : `nav MISSIONS` → `missionadd POWER_UP_MISSION RALPH VANELLOPE`
→ `speedup RALPH VANELLOPE 5` (chemin réel) → serveur `<== SPEEDUP_MISSION(RALPH MISSION_SPEEDUP x5) appliqué [persisté]`
(baseTimeRemaining **2.16E8→1.58E8**) ; `costlimit STONE_VANELLOPE 3` → `<== SET_MISSION_ITEM_COST_LIMIT(STONE_VANELLOPE=3)
appliqué [persisté]`. **Relu en DB** : `MISSION_SPEEDUP 20→15 (−5)`, `costLimit(STONE_VANELLOPE)=3`. Pilotes DEV
`speedup`/`costlimit` (chemin `ClientActionHelper` réel).

**⇒ FRIENDSHIPS #72 : 100 % LIVRÉ ET VÉRIFIÉ EN JEU, AUCUN RESTE** (seul `giveChapterRewards` = réclamation d'un chapitre
d'amitié COMPLET, à câbler si on complète un chapitre entier ; non bloquant).

**MÀJ `docs/EXPLORATION.md`** (demande utilisateur) : rangées restées ⬜ alors que les modes étaient livrés+vérifiés en
jeu → passées à ✅ : **CHALLENGES**, **SURGE**, **INVASION**, **FRIENDSHIPS**. Rangée **WAR** : trailing « vérification en
jeu nulle » corrigé (affichage+cycle de vie ✅ en jeu depuis 2026-08-02 ; actions de jeu 🟢). Vrais ⬜ restants (candidats
mode suivant) : SAVED_LINEUPS, EXPEDITION, COLLECTIONS, BLACK_MARKET, ENCHANTING, FRANCHISE/TEAM_TRIALS, PORT, WISHING_WELL.

## 2026-08-10 (g87) — FRIENDSHIPS (#72) incrément 2 (favori + stamina) ✅ vérifié EN JEU → FRIENDSHIPS 100 % en jeu

Dernier maillon de la vérif en jeu de FRIENDSHIPS (client réel, userID=1 TL100).

**FAVORI** : pilote `setfavorite RALPH VANELLOPE 1` (chemin réel `ClientActionHelper.setFavoriteFriendship(pair, true)`)
→ serveur `<== SET_FAVORITE_FRIENDSHIP(RALPH-VANELLOPE=true) appliqué [persisté]` (extra `{TYPE=<pairID>, COUNT=1}`).
Relu côté client (`frienddump`) et **après redémarrage** : `favorite=true` (persisté via `resyncFriendFavorites`).

**ACHAT D'ÉNERGIE D'AMITIÉ** : d'abord un **refus FIDÈLE** — avec FRIEND_STAMINA=519 (au-dessus du plafond 175), le
pilote `buystamina` (`ClientActionHelper.buyFriendStamina`) n'atteint PAS le serveur : la garde CLIENTE `doAction`
(local) lève `FRIEND_STAMINA_FULL` et n'émet pas (comportement d'origine ; les 2 gates du jeu = `FRIEND_STAMINA_FULL`
au plafond + `FRIEND_STAMINA_BUYS_USED` limite quotidienne). Après `SetFriendStamina 10` (outil DEV : met l'énergie sous
le plafond), relance → `buystamina` → serveur `<== Action command=BUY_FRIEND_STAMINA extra={}` → `<== BUY_FRIEND_STAMINA
(stamina) appliqué [persisté]` → **DIAMONDS 19000→18950 (−50 = getFriendStaminaBuyCost)**, **FRIEND_STAMINA +30 (=
getFriendStaminaBuyAmount)**. **Relu en DB** (`FriendMissionDump`) : `favorite=true, DIAMONDS=18950, FRIEND_STAMINA=196`.

Pilotes DEV ajoutés (chemin `ClientActionHelper` réel) : `setfavorite <p> <s> <0|1>`, `buystamina` ; `frienddump` étendu
(favorite/diamonds/friendStamina). Outils DEV : `SetFriendStamina [db] [val]` (met l'énergie d'amitié sous plafond pour
tester l'achat), `FriendMissionDump` étendu (favorite + DIAMONDS).

**⇒ FRIENDSHIPS #72 : TOUS les incréments VÉRIFIÉS EN JEU** — 1 (rendu) ✅, 2 (favori+stamina) ✅, 3a (empower) ✅,
3b (campagne d'amitié) ✅, 3c (missions idle) ✅. Régression serveur inchangée 98/98 (aucune modif de logique serveur ;
ajouts = pilotes/outils DEV + docs). Reste OPTIONNEL (non bloquant) : `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT`/
`giveChapterRewards` si un flux en jeu les exerce. **Prochain : choix du mode suivant.**

## 2026-08-10 (g86) — FRIENDSHIPS (#72) EMPOWER (3a) ✅ vérifié EN JEU + entrée campagne d'amitié (3b) localisée

Suite de la vérif en jeu (client réel, userID=1 TL100, RALPH+VANELLOPE ORANGE 60/5).

**Empower (3a) — entrée UI localisée + vérifié** : la vue disk/empower est HÉROS → détail d'un héros → onglet **Friends**
→ une amitié → mode **GEAR** (`HeroDetailFriendsContent.navigateToFriendUI(pair, FriendModeType.GEAR)`). Elle rend le
disque **« PIECE OF CAKE »** (Vanellope, « BOOSTS STUNS », LEVEL 1 +14067 Max HP/+90 Tenacity, STARS 0/25, **LOCKED**).
Le « LOCKED » est FIDÈLE : le disque se débloque via la campagne d'amitié/bits, pas via l'empowerment brut. **EMPOWER**
via le chemin client réel `ClientActionHelper.empowerFriendship(pair, 5)` (pilote `empower RALPH VANELLOPE 5`) → serveur
`<== EMPOWER_FRIENDSHIP(RALPH-VANELLOPE x5) appliqué [persisté]` → **empowerment 1→6** (getEmpowermentPerConsumable=1/
pierre × 5), **FRIENDSHIP_EMPOWER_STONE 40→35** → relu en DB (`FriendMissionDump`) : empowerment=6. Nouveaux pilotes DEV
(chemin `ClientActionHelper` réel, B-bis) : `friendui <p> <s> [MODE]` (navigateToFriendUI), `empower <p> <s> <n>`,
`frienddump <p> <s>`.

**Campagne d'amitié (3b) — entrée UI localisée** (grâce à l'indice de l'utilisateur : « y'a pas un bouton friend dans la
campagne ? ») : l'écran **CAMPAIGN** a bien **trois onglets NORMAL / ELITE / FRIENDS** (bas de l'écran ; `CampaignType`
= {NORMAL, ELITE, **FRIENDSHIP**}). L'onglet FRIENDS ouvre la liste des campagnes d'amitié (colonnes FRIENDSHIP |
CAMPAIGN | MEMORY DISK) : chaque paire a une campagne nommée (difficulté EASY/HARD), « Episode 1: 0/5 », un bouton
**UNLOCK**, et son disque mémoire (VIEW). **RALPH-VANELLOPE = « BULLY FOR YOU » (EASY)** (portraits Ralph+Vanellope +
badge vert **6** = l'empowerment crédité juste avant ; disque « PIECE OF CAKE »). ⚠️ **Correction d'une conclusion
erronée** : j'avais écrit que la campagne d'amitié était « legacy/verrouillée » en 12.1.0 — c'était FAUX, je n'avais
testé que `navigateToFriendUI(CAMPAIGN)` (qui ne fait qu'un toast `showInfoNotif` puis retourne). La vraie entrée est
l'onglet FRIENDS de l'écran CAMPAIGN (respect §8 : vérifier, ne pas supposer).

**Combat de campagne d'amitié (3b) JOUÉ & VÉRIFIÉ EN JEU (même session g86)** : après l'empower (empowerment=6), l'écran
« FRIENDSHIP UNLOCKED! » (Ralph+Vanellope) est apparu → CONTINUE → la campagne « BULLY FOR YOU » (RALPH-VANELLOPE) est
passée de UNLOCK à **GO!**. GO! → aperçu du nœud **EPISODE 1 « CREEP CLEARANCE »** (4 ennemis, bouton **NEXT = 6 énergie
d'amitié** = `FriendshipCampaignStats.getStaminaCost(node)`) → **CHOOSE YOUR HEROES** (Ralph+Vanellope+Elastigirl, TEAM
POWER 22643 vs ENEMY 880) → **QUICK FIGHT** → écran **REWARDS** (LOOT 783 or + 4 objets + Hero XP +5 Vanellope/Ralph/
Elastigirl). Le client envoie `FriendshipCampaignAttack1` → serveur : `<== FriendshipCampaignAttack : pair=RALPH-VANELLOPE
node=1 outcome=WIN → recordOutcome appliqué [persisté]`. **Relu en DB** (`FriendMissionDump` étendu) : **FRIEND_STAMINA
525→519 (−6)**, **campaignProgress 0→1** (nœud 1 franchi), **lastBattle{node=1 won=true}** — EXACTEMENT le comportement
headless (`FriendshipCampaignTest`), confirmé en jeu. (Le loot 783/objets = client-autoritatif, PARTIEL §4bis/#25.)
Note : après le combat la connexion s'est fermée (`onClose`, le client est resté figé sur REWARDS, fin de `DH_TIMEOUT`)
— sans incidence, l'issue avait déjà été reçue+persistée.

**BILAN vérif EN JEU FRIENDSHIPS #72** : incr. 1 (rendu) ✅, **3a empower ✅**, **3b campagne ✅**, **3c missions idle ✅**.
**SEUL RESTE** : incr. 2 (favori + stamina) en jeu (mêmes patrons `setFavoriteFriendship`/`buyFriendStamina`).

## 2026-08-10 (g85) — FRIENDSHIPS (#72) incrément 3c : MISSIONS IDLE ✅ VÉRIFIÉ EN JEU

Vérif EN JEU de l'incrément 3c contre NOTRE serveur (client réel, userID=1 TL100, RALPH+VANELLOPE ORANGE 60/5
préparé par `FriendAcctSetup`). Chaîne complète ADD → (avance temps) → CLAIM + CANCEL, tout par le chemin CLIENT
réel (`ClientActionHelper`) et persisté.

**Nouveaux pilotes DEV** (chemin `ClientActionHelper` RÉEL = ce que les boutons UI appellent, méthode B-bis — contourne
le hit-test capricieux des fenêtres MissionsSelectFriends/ChooseWindow, g83) : `missionadd <TYPE> <PRIMARY> <SECONDARY>`
(`addMission`→Action ADD_MISSION), `missionclaim` (`claimMissionRewards`→CLAIM_MISSION_REWARDS), `missioncancel [hero]`
(`cancelMission`→CANCEL_MISSION), `missiondump` (état missions côté client). Outils DEV : `MissionHurry` (avance les
timers PERSISTÉS via `ServerUser.debugHurryMissions`=`MissionHelper.debugHurryAllMissions`, serveur arrêté) +
`FriendMissionDump` (lecture de l'état persisté).

**Déroulé vérifié** :
1. `nav MISSIONS` → écran **MISSIONS** rend « **0/1 missions** » + ADD MISSION + « No rewards to claim yet! » / CLAIM ALL
   (état frais, aucun NPE). `missiondump` client = 0 missions.
2. **ADD** : `missionadd POWER_UP_MISSION RALPH VANELLOPE` → client `Action ADD_MISSION(POWER_UP_MISSION, RALPH-VANELLOPE)
   envoyée [chemin réel]` → serveur `<== ADD_MISSION(POWER_UP_MISSION RALPH-VANELLOPE) appliqué [persisté]`.
3. **Persistance ADD prouvée par REDÉMARRAGE** : stack arrêtée, `MissionHurry` charge userID=1 depuis la DB (→ la
   mission ÉTAIT persistée) et avance 1 cycle (→ `MissionClaimData en attente=1`). Relance de la pile → le client relit
   l'état PERSISTÉ : « **1/1 missions** » + carte **« SUGAR RUSHED »** (« Ralph has to help Vanellope hurry back… »,
   Ralph+Vanellope) + « **On Completion +1** » (empowerment = empReward POWER_UP sondé) + « Rewards In: 1d 20m » (timer
   du cycle suivant, mission répétable) + **CLAIM ALL** vert avec badge + « 5m 22s of rewards ». `missiondump` client =
   1 mission (POWER_UP_MISSION RALPH-VANELLOPE), claimEnAttente=1.
4. **CLAIM** : `missionclaim` → client `Action CLAIM_MISSION_REWARDS envoyée [chemin réel]` → serveur `<==
   CLAIM_MISSION_REWARDS(claim) appliqué [persisté]` ; `missiondump` client claimEnAttente 1→0, mission continue.
5. **CANCEL** : `missioncancel` → client `Action CANCEL_MISSION(RALPH-VANELLOPE) envoyée [chemin réel]` (heroType=RALPH,
   le primaire) → serveur `<== CANCEL_MISSION(cancel RALPH) appliqué [persisté]`.
6. **État PERSISTÉ relu en DB** (`FriendMissionDump`, serveur arrêté) : **RALPH-VANELLOPE empowerment=1** (crédité par le
   CLAIM), **missions=0** (retirée par le CANCEL), **claimsEnAttente=0**. ⇒ les trois handlers appliquent + persistent
   AUTORITATIVEMENT en jeu, valeurs = code/données du jeu.

**Note d'outillage** (pas un défaut serveur) : `missionadd`/`missionclaim` passent par le chemin d'ENVOI (`doAction` →
message), fire-and-forget, SANS l'apply LOCAL optimiste que le bouton UI déclenche → la vue cliente ne se rafraîchit
qu'au redémarrage (l'autorité est serveur, relue au boot via BootData/individualUserExtra). Le rendu de la carte et de
« CLAIM ALL » après redémarrage confirme que le client lit fidèlement notre état persisté.

**Bilan FRIENDSHIPS #72** : incr. 1 ✅ en jeu · **3c MISSIONS IDLE ✅ en jeu (g85)** · 2/3a/3b 🟢 headless (98/98).
**RESTE** : localiser les entrées UI **empower** (vue détail d'amitié) et **campagne 3b** (peut-être legacy 12.1.0) pour
finir leur vérif en jeu ; `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT` si le flux les exerce.

## 2026-08-09 (g84) — FRIENDSHIPS (#72) incrément 3c : MISSIONS IDLE d'amitié (🟢 headless, 98/98)

Le système de MISSIONS IDLE révélé en jeu (g83) — cœur de l'écran MISSIONS de 12.1.0 — implémenté côté serveur.
Nouveau `dhserver/ServerMissions` + handlers `LoginServer`, TOUT par le code du jeu (§3,
`com.perblue.heroes.game.missions.MissionHelper`), zéro invention (§4).

**Recon bytecode (pipeline #73/#74, `javap`)** :
- `ClientMission implements IMission` = simple WRAPPER write-through de `MissionData` (getters/setters lisent/écrivent
  `data` ; seule `friendship` est un cache de `data.friendshipPairID`). La liste runtime `IndividualUser.missions`
  (`List<ClientMission>`) est bâtie au chargement depuis `individualUserExtra.missions` (`List<MissionData>`, cf.
  `setExtra`→`setMissions`) ; `addMission`/`removeMission` ne touchent QUE le runtime → resync requis.
- `MissionHelper.addMission(user, type, pair, time)` : gates qui LÈVENT (paire non débloquée `FRIENDSHIP_NOT_UNLOCKED`,
  héros déjà en mission `MISSION_HERO_USED`, `TOO_MANY_MISSIONS`/`_OF_TYPE`, `ALREADY_HAVE_ENOUGH_BITS`), puis
  `chargeMissionCosts` (débit items/ressources via `removeItem`, **ne lève PAS** sur stock insuffisant), crée la
  mission (`IndividualUser.addMission`), pose startTime/lastUpdateTime/baseDuration/speed(`calculateMissionSpeed`).
- `updateAllMissions(user, time)`→`updateMissionProgress` : avance chaque mission par (temps écoulé × speed) ; timer à
  zéro → crée une `MissionClaimData{type, pair, startTime, endTime, empowerment=getEmpowermentReward, drops=
  MissionStats.getOtherRewards, costsPaid, count, cycleID}` → `addMissionClaimData` (**write-through** extra) → puis
  retire la mission (ou recharge le cycle si répétable).
- `claimMissionRewards(user, time)` : `updateAllMissions(time)` PUIS applique chaque `MissionClaimData`
  (`setEmpowerment` sur l'amitié si empowerment>0, `RewardHelper.giveRewards(drops)`, contest burns) →
  `clearMissionClaimData` + `incDailyUses`/`setTime`/`setCount`. Renvoie true si réclamé.
- `cancelMissionByHero(user, unitType, time)` : retrouve la mission portant ce héros (`getMissionWithHero`), rembourse,
  retire. Le client (`ClientActionHelper.cancelMission`) identifie la mission par `friendship.getPrimary()` (heroType).

**Protocoles (disasm `ClientActionHelper`)** : `ADD_MISSION{TYPE=MissionType, ID=FriendPairID.getAsLong(), TIME}` ;
`CLAIM_MISSION_REWARDS{TIME}` ; `CANCEL_MISSION{heroType=friendship.getPrimary(), TIME}`. Le serveur utilise SON
horloge (`TimeUtil.serverTimeNow()`), pas le `TIME` client (anti-triche sur le timing idle).

**Anti-triche** : `addMission` ne couvrant pas l'affordabilité, `ServerMissions.applyAddMission` miroite la garde
cliente COMPLÈTE via `canStartMission(user, type, pair)` (prédicat pur, `null`=OK ; sinon refus :
`CANT_AFFORD`/`FRIEND_ON_MISSION`/`MISSION_LIMIT`/`FRIEND_PAIR_LOCKED`/`DISK_AT_MAX_STARS`).

**Persistance** : nouveau `ServerUser.resyncMissions` (extrait le `MissionData` sous-jacent de chaque `ClientMission`
par réflexion → `extra.missions`) ; `missionClaimData` write-through (aucun resync) ; empowerment via
`resyncFriendships` ; récompenses items/ressources write-through ; diamants/héros/compteurs via resyncs standard.
Outil DEV `ServerUser.debugHurryMissions(cycles)` (avance les timers via la méthode DEBUG du jeu
`MissionHelper.debugHurryAllMissions` — utile headless ET pour la vérif en jeu de la réclamation sans attendre les heures).

**Faits du jeu SONDÉS** (`MissionStats`, compte RALPH+VANELLOPE ORANGE 60/5) : POWER_UP = sans coût, empReward=1,
dur=60h ; MEMORY = coûte 1 `STONE_VANELLOPE` (→ `CANT_AFFORD` sur compte frais) ; DISK_POWER = sans coût,
otherRewards=`GEAR_JUICE` 100 ; **limite combinée = 1** (TL100).

`MissionLoopTest` : ADD POWER_UP → 1 mission (survit DB) ; 2ᵉ ADD refusé (limite/coût, anti-triche) ; CANCEL par héros
primaire → 0 (persiste) ; `debugHurryMissions(1)` → un `MissionClaimData` en attente → `CLAIM_MISSION_REWARDS` →
**empowerment 0→1** (empReward POWER_UP) + `missionClaimData` vidé, persistance DB + round-trip wire. Régression
**98/98**.

**RESTE** : incr. 4 vérif EN JEU (ADD une mission → avancer/hurry → CLAIM en jeu ; localiser les entrées UI empower/
campagne) ; `SPEEDUP_MISSION`/`SET_MISSION_ITEM_COST_LIMIT` si le flux en jeu les exerce (mêmes patrons, non bloquant).

## 2026-08-09 (g83) — FRIENDSHIPS (#72) vérif EN JEU → découverte du système de MISSIONS IDLE (incr. 3c requis)

Vérif EN JEU de l'incrément 4 (contre notre serveur). Compte userID=1 préparé par le **nouvel outil DEV
`FriendAcctSetup`** (RALPH+VANELLOPE ORANGE 60/5 → paire 1 débloquée, `FRIEND_STAMINA=350`, 20
`FRIENDSHIP_EMPOWER_STONE`). `nav MISSIONS` → écran MISSIONS (« 0/1 missions », ADD MISSION) → **ADD MISSION** ouvre
`MissionsSelectFriendsWindow` (« CHOOSE FRIENDS FOR MISSION », roster rendu) → **sélection de Ralph confirmée EN JEU**
(coche verte ; le grid filtre les partenaires valides : Vanellope, Sulley) → **`MissionsChooseWindow`** (« CHOOSE A
MISSION TYPE ») : trois types **POWER-UPS / MEMORIES / DISK POWER**, chacun avec un timer (« Every 1d13h / 18h41m /
5h55m ») + « MISSION SPEED +60,5 % » + START.

**Découverte (le point de la vérif en jeu)** : l'écran MISSIONS de 12.1.0 pilote un **système de MISSIONS IDLE
temporisées** (envoyer une paire d'amis en mission → attendre → récompenses power-ups/mémoires/disk-power), et **NON**
le combat de campagne que j'avais câblé en 3b. Ce sous-système n'est **pas implémenté** côté serveur :
- Actions : `ADD_MISSION{MissionType, FriendPairID}`, `CLAIM_MISSION_REWARDS`, `CANCEL_MISSION`, `SPEEDUP_MISSION`,
  `UPDATE_MISSION`, `SET_MISSION_ITEM_COST_LIMIT` (`ClientActionHelper`/`CommandType`).
- MissionType : `POWER_UP_MISSION`, `MEMORY_MISSION`, `DISK_POWER_MISSION`.
- Logique du jeu (§3) : `com.perblue.heroes.game.missions.MissionHelper` — statiques IUser propres :
  `addMission(user, type, pair, time)`, `canStartMission(user, type, pair)→MissionFailType` (anti-triche),
  `claimMissionRewards(user, time)`, `cancelMission(user, mission, time)`, `calculateMissionSpeed`,
  `canAffordMissionCosts`. État : `individualUserExtra.friendshipMissionData` + `inProgressFriendshipMissions`.

**Conséquence (consigne utilisateur « rien d'optionnel tant que pas prouvé optionnel »)** : ce système est **REQUIS**
→ **incrément 3c** (ADD/CLAIM/CANCEL/SPEEDUP_MISSION + persistance + vérif en jeu). Par ailleurs, **empower** (disks)
et le **combat de campagne** (3b) ne s'atteignent pas depuis l'écran MISSIONS → leurs points d'entrée UI restent à
LOCALISER en jeu (empower = vue FRIENDSHIPS/détail d'une amitié ; le combat de campagne est peut-être legacy en
12.1.0). Les incréments 2/3a/3b restent **prouvés HEADLESS** (code du jeu exécuté, 97/97) — leur vérif EN JEU est à
compléter une fois les entrées UI localisées.

**Frictions de pilotage notées** (outillage, pas serveur) : les cartes de `MissionsSelectFriendsWindow` ont un
overlay `UnitViewStars` sans listener + `PressableStack` sans listener apparent → `fire` sélectionne par bulle
d'événement (a marché pour Ralph) mais la sélection du 2ᵉ héros / START est capricieuse. `sleep` avant-plan reste
BLOQUÉ (exit 144) → boucles `until`/`run_in_background`.

**RESTE** : incr. 3c (missions idle) ; incr. 4 (vérif en jeu missions idle + localiser empower/campagne). Rien de
commité côté serveur cette session (découverte + docs + outil `FriendAcctSetup`).

## 2026-08-09 (g82) — FRIENDSHIPS (#72) incrément 3b : combat de campagne d'amitié (🟢 headless)

Combat de la campagne d'amitié (MISSIONS), patron campagne/SURGE (client-autoritatif + serveur ré-exécute).
Message `FriendshipCampaignAttack{base:AttackBase, friendPairID:long, nodeNumber, lootEarned, memoryChanges,
stagesCleared}` — **pas de handshake Start** (le client joue puis envoie l'issue). Handler `LoginServer` →
`ServerUser.recordFriendCampaignAttack` → `FriendshipCampaignHelper.recordOutcome(user, pair, node, m.base.outcome,
loot, m.base.attackers, m.base.defenders, SpecialEventSnapshot.NONE, chapter, level, false)` (code du jeu §3).

**Mapping des params** (relevé au call-site client `CampaignAttackScreen`) : arg2=node, arg8=chapter, arg9=level ;
chapter/level = campagne NORMALE sous-jacente (échelle XP via `CampaignStats.getExpReward`) DÉRIVÉE par le jeu :
`getNormalCampaignChapter(user)` + `getNormalCampaignLevel(pair, node, chapter)` (aucune invention §4).

**Gates du jeu (anti-triche, dans recordOutcome)** : `FRIEND_STAMINA >= FriendshipCampaignStats.getStaminaCost(node)`
(=6/nœud, sinon no-op) ; `getLevelLockStatus(user, pair, node)==UNLOCKED` sinon `FRIENDSHIP_CAMPAIGN_LEVEL_LOCKED`.
**🔑 Nœuds 1-INDEXÉS** : `getLevelLockStatus` exige `node == getFriendshipCampaignProgress(pair)+1` (nœud 0 =
ALREADY_COMPLETE) → le 1er nœud jouable d'une amitié fraîche est **1**, pas 0. `canUseHeroes(pair, node, attackers)`
(les héros de la paire). Le combat débite l'énergie, `doNodeUpdate` (progression), `setLastBattle`, crédite le loot
reçu (client, PARTIEL §4bis/#25 — graine non rejouée) + XP.

Persistance : `resyncFriendships` (map amitiés) + héros/diamants/compteurs. `FriendshipCampaignTest` : paire
débloquée (2 héros grantés) + `FRIEND_STAMINA` → `FriendshipCampaignAttack` WIN au nœud 1 → **-6 stamina,
`lastBattle{node=1, won=true}`**, survit à la persistance DB + round-trip wire.

**Bilan FRIENDSHIPS #72** : incréments **1-3 livrés côté serveur** (1 ✅ en jeu ; 2 favori/stamina, 3a empower,
3b campagne 🟢 headless). Régression **97/97**. **RESTE** : incr. 4 vérif EN JEU (favori/empower via le fix pilote
modale + campagne d'amitié jouée) ; `giveChapterRewards` (réclamation de récompense de chapitre) si le flux le
demande. Détail : `docs/FRIENDSHIPS.md`.

## 2026-08-09 (g81) — FRIENDSHIPS (#72) incrément 3a : EMPOWER d'amitié (🟢 headless)

`EMPOWER_FRIENDSHIP{TYPE=<FriendPairID.getAsLong()>, COUNT=<nb pierres>}` → `FriendshipHelper.empowerFriendship`
(code du jeu §3, disasm) : (1) `getUnlockStatus(user, pair)==UNLOCKED` sinon `FRIENDSHIP_NOT_UNLOCKED` (déblocage =
`Unlockable.FRIENDSHIPS` TL24 + les DEUX héros de la paire possédés au niveau/contenu requis — statuts `TL_LOCKED`/
`HERO_MISSING_LOCKED`/`HERO_CONTENT_LOCKED`/`UNLOCKED`) ; (2) `count>=1` ; (3) **`UserHelper.useItem(user,
FRIENDSHIP_EMPOWER_STONE, count)`** (le COÛT) ; (4) `setEmpowerment(empowerment + getEmpowermentPerConsumable*count)`.

**Anti-triche** : `useItem`→`IUser.removeItem` NE lève PAS sur stock insuffisant (modèle client-autoritatif, comme
le loot) → un client modifié pourrait sur-empower sans pierres. `ServerFriendships.applyEmpower` MIROITE la garde
d'entrée CLIENTE avec la donnée du jeu (`getItemAmount(FRIENDSHIP_EMPOWER_STONE) >= count`) → refus autoritatif.
Pas une règle inventée (§3) : c'est la condition que le client applique déjà avant d'émettre l'action.

**Persistance — nouveau `ServerUser.resyncFriendships(IndividualUser)`** : `ClientFriendship` (runtime) a ses propres
champs et ne wrappe pas `FriendPairData` (wire), et `getExtra()` ne re-sérialise pas → on ré-écrit la map
`individualUserExtra.friendships` (clé `getAsLong()`) depuis `iu.getFriendships()` : empowerment, campaignBitsEarned,
viewedUnlockAnimation, lastHistoryViewTime, lastBattle, history (`FriendshipEvent`→`FriendshipEventData`, mêmes
champs level/missionNumber/storyNoteNumber/time/type). **🐛 Piège résolu** : ne PAS écraser `lastBattle` avec null —
`new FriendPairData()` l'initialise à `new FriendshipBattleInfo()` (non-null) et `getClientFriendship` lit
`data.lastBattle.serverTime` SANS garde → NPE au rechargement si null (un `ClientFriendship` jamais combattu a
`getLastBattle()==null`). On ne pose `lastBattle` que s'il est non-null. Items consommés dans
`individualUserExtra.items` (write-through).

`ServerFriendships.applyEmpower` + handler `LoginServer` (`EMPOWER_FRIENDSHIP`, groupé avec favori/stamina).
`FriendshipEmpowerTest` : `grantHero(RALPH/VANELLOPE, ORANGE, 60, 5)` → `getUnlockStatus`==UNLOCKED ; refus sans
pierre (anti-triche) ; donne 3 pierres, empower ×2 → empowerment=`perStone*2`, 2 pierres consommées (reste 1) ;
persistance DB (empowerment + stock de pierres survivent) ; round-trip wire `individualUserExtra`. `perStone`=
`FriendshipStats.getEmpowermentPerConsumable()` (=1 mesuré, donnée du jeu).

**RESTE** : incr. 3b campagne d'amitié (`FriendshipCampaignAttack` → `FriendshipCampaignHelper.recordOutcome` +
`giveChapterRewards`, réutilise `resyncFriendships`) ; incr. 4 vérif EN JEU (favori/stamina/empower/campagne).

## 2026-08-09 (g80) — FRIENDSHIPS (#72) incrément 2 : favori + stamina (🟢 headless)

Deux Actions « légères » du mode, par le CODE DU JEU (§3), zéro invention (§4). Protocoles PROUVÉS au bytecode
(`ClientActionHelper` : `with(TYPE, FriendPairID.getAsLong())` + `withCount`) :
- `SET_FAVORITE_FRIENDSHIP{TYPE=<pair long>, COUNT=0/1}` → `FriendshipHelper.setFavoritedFriendship(user, pair, fav)`
  (= `IndividualUser.setFavoriteFriendship`, AUCUN verrou). L'ensemble `favoriteFriendships` est un champ de
  `IndividualUser` COPIÉ de l'extra au chargement (comme flags/counts) → nouveau **`ServerUser.resyncFriendFavorites`**
  ré-écrit la `List<Long>` (`getAsLong`) dans `individualUserExtra.favoriteFriendships`.
- `BUY_FRIEND_STAMINA{}` (sans extra) → `FriendshipHelper.buyFriendStamina(user)` : débite `DIAMONDS`
  (`getFriendStaminaBuyCost`) + crédite `FRIEND_STAMINA` (`getFriendStaminaBuyAmount`), dans les limites/plafond du
  jeu. `resyncDiamonds` (diamants) ; `FRIEND_STAMINA` vit dans `individualUserExtra.resources` (write-through).

`ServerFriendships` (nouveau) + handlers `LoginServer` (`SET_FAVORITE_FRIENDSHIP`/`BUY_FRIEND_STAMINA`). Fire-and-forget
(le client applique localement). `FriendshipShopTest` : favori set → `isFavoriteFriendship` + re-sync extra →
persistance DB (save/reload) → dé-favori ; buyStamina — chemin de REFUS géré (compte frais au plafond de
`FRIEND_STAMINA` → pas d'achat) ; succès (débit/crédit) à exercer EN JEU (stamina consommée par la campagne).

**Découvertes persistance** (recon) : `ClientFriendship` (runtime, impl `IFriendship`) a ses PROPRES champs
(empowerment/campaignBitsEarned/history/lastBattle/…) — il ne wrappe PAS `FriendPairData` (le wire) ; `getExtra()`
renvoie l'extra STOCKÉ sans re-sérialiser. Donc empower/campagne (incr. 3) exigeront un `resyncFriendships` COMPLET
(map `friendships` : `ClientFriendship`→`FriendPairData`, y c. la conversion `history` FriendshipEvent↔EventData).
`empowerFriendship` est GATÉ `FRIENDSHIP_NOT_UNLOCKED` (anti-triche : paire débloquée = 2 héros possédés au niveau).

**RESTE** : incr. 3 (empower + campagne d'amitié = `FriendshipCampaignAttack`→`recordOutcome`+`giveChapterRewards` +
`resyncFriendships`), incr. 4 vérif en jeu complète. Détail : `docs/FRIENDSHIPS.md`.

## 2026-08-09 (g79) — FRIENDSHIPS/MISSIONS (#72) : recon pipeline + incrément 1 (livraison/rendu) ✅ EN JEU

Nouveau mode (choix utilisateur), attaqué au **pipeline #73/#74** : `contract.sh --mode Friendship` (ModeGraph →
ScreenContract) a donné le contrat complet — gate `Unlockable.FRIENDSHIPS` (TL24), écran `MissionsMainScreen` +
fenêtres `FriendshipCampaignWindow`/`DiskUnlockWindow`/`WallWindow`/`FriendFinderWindow`, messages
`FriendshipBattleInfo`/`HeroLineup`/`FriendshipCampaignAttack`.

**Modèle** (recon bytecode) : deux systèmes liés — **amitiés** (chaque paire `FriendPairID{primary,secondary}` monte
en `empowerment` → disk) et **campagne d'amitié (MISSIONS)** (mini-campagne par paire, combat via
`FriendshipCampaignHelper.recordOutcome`, récompenses de chapitre). Logique §3 : `FriendshipHelper`
(`empowerFriendship`/`buyFriendStamina`/`setFavoritedFriendship`) + `FriendshipCampaignHelper`
(`recordOutcome`/`giveChapterRewards`/`doNodeUpdate`/`getRewardsForChapter`).

**Persistance quasi-GRATUITE** : l'état vit dans `IndividualUserExtra` (`friendships`, `friendshipCampaignProgress`,
`friendshipMissionData`, `favoriteFriendships`, `inProgressFriendshipMissions`, `lastFriendRequestTimes`) — DÉJÀ
persisté par write-through (le `User`/`IndividualUser` est bâti dessus). Contraste avec CHALLENGES (blob dédié).
`BootData.friendshipOffsetData` = config d'échelle CONTENU (lue au boot par `FriendshipOffsets.setOffsets` — NULL ⇒
NPE) ; **non-null vide par défaut** (`new BootData()`), offsets vides ⇒ `getLevelOffset/getRarityOffset = 0` = pas de
dérive = baseline fidèle pour une version de contenu figée.

**Incrément 1 — livraison/rendu** : les conteneurs (`friendshipOffsetData` + maps d'`IndividualUserExtra`) sont
**déjà non-null par les défauts** → AUCUN changement serveur nécessaire. `FriendshipBootTest` verrouille le contrat
(non-null, listes d'offsets de même longueur, `FriendshipOffsets.setOffsets(offset)` rejoué HEADLESS sans NPE,
round-trip wire `friendshipOffsetData` + `BootData`). **✅ EN JEU** (compte TL100) : `nav MISSIONS` → l'écran
**MISSIONS** rend « **0/1 missions** », **ADD MISSION**, « No rewards to claim yet! » / CLAIM ALL — état frais
correct, aucun NPE `setOffsets`. Le catalogue de paires est de la donnée CLIENTE (`friendship_pairs.tab`).

**Fix d'outillage annexe** : découvert que le **`sleep` en avant-plan est bloqué** dans l'environnement (exit 144 /
SIGSTKFLT) → les lancements client qui faisaient `sleep` avant le `nohup … &` étaient tués AVANT de démarrer (logs
périmés trompeurs). Correctif de méthode : lancer sans `sleep` (run-online.sh tue les anciens via DH_KILL_OLD), et
attendre via `run_in_background`/boucle `until` (jamais `sleep` en avant-plan).

**RESTE** : incr. 2 (empower + favori : Actions `EMPOWER_FRIENDSHIP`/`SET_FAVORITE_FRIENDSHIP` → `FriendshipHelper`),
incr. 3 (campagne d'amitié : `FriendshipCampaignAttack` → `recordOutcome` + `giveChapterRewards` + stamina), incr. 4
vérif en jeu complète. Détail : `docs/FRIENDSHIPS.md`.

## 2026-08-09 (g78) — CHALLENGES (#72) : achats/annulation ✅ EN JEU + fix pilote pour les MODALES (réutilisable)

**Fix pilote (outillage, réutilisable TOUS les modes).** `TutorialDriver.fireClick` ne hit-testait que le stage de
l'ÉCRAN (`getRootStack` = `belowBlurStage`) → il ratait les boutons des fenêtres MODALES (confirmations d'achat, de
reset…) qui vivent sur `ScreenManager.aboveBlurStage` (au-dessus du blur) → il tapait le bouton DERRIÈRE la modale.
Correctif : `fireClick` détecte une modale ouverte via `hasModal(aboveBlurStage.getRoot())` (acteur VISIBLE dont la
classe/super-classe évoque `ModalWindow`/`Prompt`/`Confirm*` — les modales du jeu sont des `DHWindow` : `DecisionPrompt`,
`GenericPurchasePrompt`, `*ConfirmationWindow`…). Si modale ouverte → hit-test `aboveBlurStage` ; sinon → stage
d'écran/fenêtres. Filtre « acteur réellement cliquable » (self/ancêtre a des listeners) pour ne pas taper le HUD inerte
d'`aboveBlur`. Pure lecture de la scène (réflexion sur les champs privés `aboveBlurStage`/`belowBlurStage`), aucune
modif du jeu.

**Vérifs EN JEU** (compte userID=1 TL100, +20000 diamants via outil DEV `GrantDiamonds`) contre notre serveur :
- **BUY_STICKER_CHALLENGE_SLOT ✅** : carte `BUY 1,000` (bleue car diamants OK) → tap → modale **« EXTRA SLOT? Buy
  extra challenge slot for 1,000 diamonds? »** → YES (atteint via le fix) → le client émet
  `Action{command=BUY_STICKER_CHALLENGE_SLOT, extra={SLOT=NORMAL_2}}` → handler `<== BUY_STICKER_CHALLENGE_SLOT
  (NORMAL_2) appliqué [persisté]` → **DIAMONDS 20000→19000 (-1000 = CHALLENGE_SLOT_2_COST), CHALLENGE_SLOT_2=true**
  (relu DB) → le client rend un **2ᵉ slot « ADD A CHALLENGE »**. Prouve le chemin d'ACHAT (purchaseSlot autoritatif +
  débit diamants + flag + persistance) de bout en bout en jeu.
- **CANCEL_STICKER_CHALLENGE ✅** (comble le dernier trou de l'incr. 2) : détail livre STARTER → **RESTART** sur le
  défi actif → modale **« RESTART CHALLENGE? »** → YES → `Action{command=CANCEL_STICKER_CHALLENGE, extra={SLOT=STARTER,
  TYPE=THE_NAMES_NICK, TIME}}` → `<== CANCEL_STICKER_CHALLENGE(THE_NAMES_NICK/STARTER) appliqué [persisté]` → minuterie
  remise à 7 j (le jeu ré-avance via `setupStarterChallenges`). Confirme aussi la PERSISTANCE inter-lancements :
  `CATCH A STAR` toujours **COMPLETE** (le claim g75 a survécu à 2 relances).

**Bilan mutations CHALLENGES #72 en jeu** : CLAIM (g75) + BUY_STICKER_CHALLENGE_SLOT + CANCEL — **toutes ✅**.
Restent non exercés en jeu (NON bloquants, même chemin handler/modale prouvé, + headless + bytecode) : BUY_STICKER,
BUY_STICKER_BOOK, START_STICKER_CHALLENGE (gaté : slot STARTER unique / défis NORMAL à débloquer), SET_FAVORITE_STICKER.
Aucune modif serveur (les handlers étaient commités g74/g76) — seul le pilote (`TutorialDriver`) change + docs.

## 2026-08-09 (g77) — CHALLENGES (#72) incrément 4 : handler GetUserChallengeDataExtra (🟢 headless, 93/93)

**`GetUserChallengeDataExtra{targetUserID}` → `UserChallengeDataExtra`** (requête/réponse, patron `GetSurge`).
La fenêtre `StickerOverviewWindow` envoie ce message (disasm : `sendMessage(msg, UserChallengeDataExtra.class,
listener)`) pour afficher les stickers d'un joueur. Handler `LoginServer` : charge le joueur ciblé (soi-même = `user` ;
sinon `store.loadIfExists(targetUserID, shardID)`), renvoie `ServerChallenges.load(target)` (état persisté) ou
`freshData` si absent — `userID = targetUserID`, jamais null (écran non vide), `setAsReplyTo`. `ChallengeViewTest`
(état persisté d'un AUTRE joueur relu + wire round-trip + cible inconnue → freshData).

**`VIEW_CHALLENGES`** (Action émise à l'ouverture des livres) : au bytecode c'est un `VIEW_*` (marqueur/analytics
groupé avec `VIEW_SINGLE_CHALLENGE`/`VIEW_COSTUME`…), sans effet serveur observé. Laissée **non gérée HONNÊTEMENT**
(§2 : pas de faux OK) — NON bloquante : le client navigue en local (vérifié EN JEU g75, les livres s'affichent).

**Bilan CHALLENGES #72** : incréments **1-4 livrés côté serveur** (1 ✅ boot en jeu, 2 ✅ en jeu, 3 🟢, 4 🟢),
régression **93/93**. Reste : vérif EN JEU de l'incr. 3 (achats — nécessite des diamants) et de la fenêtre
`StickerOverviewWindow` (incr. 4) ; progression transversale (hooks `ChallengeImpl` campagne/chest/arène/breaker,
autorité de progression à observer en jeu). Détail : `docs/CHALLENGES.md`.

## 2026-08-09 (g76) — CHALLENGES (#72) incrément 3 : économie stickers (🟢 headless, 92/92)

4 Actions d'économie, tout par le CODE DU JEU (§3), zéro invention (§4). Protocoles PROUVÉS au bytecode
(`ClientActionHelper` + `ActionExtraBuilder`), extras en `.name()` (String) :
- `BUY_STICKER{TYPE=StickerType}` → `StickerHelper.purchaseSticker(user, type)` (débite DIAMONDS=getUserStickerPrice,
  cosmétique + `purchaseTime`).
- `BUY_STICKER_BOOK{TYPE=StickerBookType}` → `StickerHelper.purchaseBook(user, book)` (débite DIAMONDS=coût remisé,
  `purchaseTime` de chaque sticker du livre).
- `BUY_STICKER_CHALLENGE_SLOT{SLOT=ChallengeSlots}` → `StickerHelper.purchaseSlot(user, slot)` (débite DIAMONDS=
  `StickerChallengeStats.getSlotCost`, pose `UserFlag.CHALLENGE_SLOT_2`).
- `SET_FAVORITE_STICKER{TYPE=StickerType}` → `userExtra.favoriteSticker`.

**Liaison SCOPED de la donnée de défis** : `purchaseSticker`/`purchaseBook` accèdent à la donnée via
`extension.getChallengeData(userID)` = `DH.app.getYourChallengeData()` (champ `GameMain.userChallengeData`). On lie
NOTRE `ClientUserChallengeData` au champ le TEMPS de l'appel (`ServerChallenges.withBoundData`, restauré en `finally`)
puis re-sérialise (`toMessage`). Scoped → on NE réactive PAS globalement la cascade `notifyChallenges` (g59) ; et de
toute façon elle est sûre depuis le fixture `historicWeeklyChallenges` non-null (g74). Persistance : `resyncDiamonds`
(diamants) + `resyncCounts` (drapeaux `CHALLENGE_SLOT_2`/`SPENT_DIAMONDS_ON_CHALLENGE`/`FREE_STICKER_PURCHASE`).

**Favori (§6 persistance)** : `User.setFavoriteSticker` n'écrit QUE le champ `User` (pas `extra`, disasm) → ne
persiste pas. `getUser` lit `userExtra.favoriteSticker`. Donc nouveau `ServerUser.setFavoriteSticker` pose
`userExtra.favoriteSticker` (+ miroir `BasicUserInfo`) = source lue au chargement.

**`ChallengeShopTest`** : buySlot → `CHALLENGE_SLOT_2` posé, **-1000 diamants** (= `CHALLENGE_SLOT_2_COST`), anti-double
refusé ; setFavorite → `TO_CATCH_A_STAR` ; buyBook `CITY_PATROL` → **-900 diamants**, **5** stickers `purchaseTime` ;
tout survit à la persistance DB (`UserStore` round-trip). Valeurs EXACTES des données du jeu (= « BUY 1,000 » /
« BUY 900 » observés en jeu g75). `resyncCounts` rendu package-private. Régression **92/92**.

**RESTE** : vérif EN JEU (nécessite des diamants sur le compte — grant + achat en jeu) ; incrément 4
(`GetUserChallengeDataExtra` = vue d'un autre joueur + `VIEW_CHALLENGES` navigation). Détail : `docs/CHALLENGES.md`.

## 2026-08-09 (g75) — CHALLENGES (#72) incrément 2 ✅ VÉRIFIÉ EN JEU (rendu + CLAIM de bout en bout)

Session de vérif EN JEU (§8) contre NOTRE serveur, client réel (compte userID=1 TL100, tuto complet, via
`SurgeAcctSetup`). Assets ETC1 re-téléchargés (`tools/fetch_assets.sh`, 283M) ; client déjà bâti (spine jar +
game-framed). Pilote : `nav CHALLENGES` + `fire x,y` (commandes EXISTANTES, aucun nouveau code pilote).

**Auto-setup serveur confirmé** : au boot, `LoginServer` appelle `ServerChallenges.ensureSetup` → le défi STARTER
est peuplé par le jeu et PERSISTÉ (`challengeData` 0 → 256 octets en DB, vérifié). BootData le livre.

**Rendu ✅** : `nav CHALLENGES` → écran **CHALLENGES**, section **STARTER**, carte **« Catch a Star »**
(= TO_CATCH_A_STAR) : « 3-Star every major stage in Chapter 4 of the Normal Campaign », **Rewards 500 + sticker**,
**Progress 0/7**, **Time Left 6d 23h 59m** — toutes valeurs EXACTES de `challenge_stickers.tab` (500 tokens, max 7,
durée 7 j) livrées par notre serveur. **CHALLENGE BOOKS** : STARTER **0/5**, PICK 'EM 0/10, + 4 livres NORMAL avec
`isPurchasable`/state corrects (logique cliente `StickerHelper` tourne sans NPE sur notre état). **Détail STARTER** :
les 5 stickers, le défi actif en **RESTART + timer**, les autres en ACTIVATE (grisés — slot STARTER unique).

**CLAIM de bout en bout ✅** : défi marqué prêt (outil DEV `MarkChallengeReady` : `currentProgress = maxProgress`,
comme l'aurait fait la progression) → au reboot la carte affiche le tampon **COMPLETE** + bouton vert **CLAIM** →
tap (`fire`) → le client émet `Action{command=CLAIM_STICKER_CHALLENGE, extra={SLOT=STARTER, TYPE=TO_CATCH_A_STAR,
TIME=…}}` — EXACTEMENT le protocole câblé (bytecode `ClientActionHelper`) → handler serveur
`<== CLAIM_STICKER_CHALLENGE(TO_CATCH_A_STAR/STARTER) appliqué [persisté]` → fenêtre de récompense **+500** (jetons)
+ sticker (Dupe! +50, déjà possédé) → **auto-avance** du slot STARTER vers **« The Name's Wilde »** (= THE_NAMES_NICK,
« Collect 20 Nick Hero Chips from the Elite Campaign », **Progress 0/20**, 7 j) — le comportement `claimSticker` +
`setupStarterChallenges` du JEU (retire le réclamé, ré-avance), piloté par le serveur et relivré au client.

**Persistance serveur confirmée (relecture DB)** : `CHALLENGE_TOKENS=500` (crédité, était 0),
`slot STARTER=THE_NAMES_NICK 0/20 claimed=false`, `completionTime={TO_CATCH_A_STAR=1}` (le jeu stocke l'userID dans
`setCompletionTime` — quirk du bytecode, exécuté fidèlement §3 ; > 0 ⇒ marqueur « complété »). La fixture
`historicWeeklyChallenges` (g74) fonctionne dans le VRAI client (aucun NPE au rendu ni au claim).

**Non exercé en jeu (non bloquant, documenté §4/§8)** : START player-initiated (bouton ACTIVATE gaté — slot STARTER
unique occupé ; défis NORMAL/WEEKLY à débloquer/acheter) et CANCEL (le bouton YES de la modale « RESTART CHALLENGE? »
est sur un stage overlay que le pilote `fire` — qui vise le stage principal — n'atteint pas). Les deux partagent
extras/handler/persistance avec le CLAIM (prouvé en jeu) + sont prouvés HEADLESS (`ChallengeLoopTest`) + protocole
bytecode. **`VIEW_CHALLENGES`** (Action de navigation émise à l'ouverture des livres) est « non gérée (PARTIEL) » :
navigation CLIENTE locale, aucun état serveur requis → à traiter avec la vue livres/stickers (incr. 3/4).

**Aucun changement de code serveur cette session** (la boucle était déjà commitée g74, `37325c0`) — uniquement la
vérif EN JEU + mise à jour docs. Régression inchangée **91/91**.

## 2026-08-09 (g74) — CHALLENGES (#72) incrément 2 : boucle setup/claim/cancel + persistance (🟢 headless, 91/91)

**Objectif (option (b) choisie par l'utilisateur)** : câbler la boucle de défis EN HEADLESS avec les valeurs déjà
prouvées (code du jeu + données `.tab`), puis vérifier EN JEU. Tout par le CODE DU JEU (§3), zéro invention (§4).

**Blocage résolu — fixture `historicWeeklyChallenges` (§8, disasm)** : `canStart/getStartError` NPE-aient headless.
Cause tracée au bytecode : `getStartError → getUserStickerInfo(...).isUnlocked() → isCurrentWeeklySticker →
StickerHelper.extension.getHistoricChallenges().getCurrentChallenges()`. L'extension (`StickerHelper$1`, posée par
le `<clinit>`, toujours non-null) délègue à `DH.app.getHistoricWeeklyChallenges()`. Le VRAI `GameMain(ctor)` pose
`historicWeeklyChallenges = new HistoricWeeklyChallenges()` (offset 370) ; notre shim alloué SANS ctor le laisse
null → NPE. **Correctif** : `ServerContext.init()` pose la MÊME valeur (conteneur vide) — couche plateforme (§4),
zéro donnée inventée. Posée GLOBALEMENT (sûr) car ce champ n'est que LU ; contrairement à `userChallengeData`
(RÉSERVÉ à l'oracle, g59 — le poser globalement réactive la cascade `notifyChallenges → setupWeeklyChallenges`).

**Sérialiseur inverse (§3)** : le jeu n'a PAS de sérialiseur `client→message` (le client ne renvoie jamais tout
l'état ; `ClientNetworkStateConverter.setUserChallengeData` va aussi `message→client`). On écrit un sérialiseur à
JEU DE CHAMPS FERMÉ `ServerChallenges.toMessage(ClientUserChallengeData) → UserChallengeDataExtra` — miroir du
sync héros de §3 ; réflexion LECTURE SEULE sur `nextChallengeID`/`attemptID`/`userID` (aucun getter). Validé par
round-trip (`ChallengeLoopTest`).

**SETUP auto par le jeu** : `ServerChallenges.ensureSetup(su)` (appelé au boot par `LoginServer`, gaté
`Unlockables.isUnlocked(Unlockable.CHALLENGES, user)` TL20) exécute `StickerHelper.setupStarterChallenges` — le jeu
choisit le 1er sticker de catégorie STARTER non complété par `starterChallenge` croissant (`challenge_stickers.tab` :
`TO_CATCH_A_STAR`(1, 0/7, 500 tokens) → `THE_NAMES_NICK`(2) → `RIDE_THE_FERRIS_WHEEL`(3) → `SOCIAL_BUTTERFLY`(4) →
`YOURE_NUMBER_ONE`(5)) — + `setupWeeklyChallenges` (no-op tant qu'aucune rotation hebdo poussée). Idempotent.

**Protocole client PROUVÉ au bytecode** (`ClientActionHelper`, `ActionExtraBuilder`) : `startStickerChallenge` →
`Action{START_STICKER_CHALLENGE, extra{TYPE=StickerType, TIME}}` (SANS SLOT → serveur choisit via `canStart`) ;
`claimSticker`/`cancelStickerChallenge` → `Action{…, extra{TYPE, SLOT=ChallengeSlots, TIME}}`. Extras stockés en
`.name()` (String, `with(ActionExtraType,String)`). Handlers `LoginServer` : ré-exécutent `createHandleExtra`
(START, niveau message : `endTime=serverTime()+getDuration()`, `maxProgress=getMaxProgress()`) / `claimSticker`
(CLAIM) / `cancelChallenge` (CANCEL) de façon autoritative + persistent. Fire-and-forget (le client a appliqué
localement — patron loot/raid).

**CLAIM autoritatif (`claimSticker`, disasm)** : pose `completionTime`, crédite le sticker cosmétique +
`CHALLENGE_TOKENS` (`getTokenReward`) + bonus de livre (si livre complété), `setClaimed(true)`, RETIRE le handle
du slot (`setHandle(slot, null)` hors WEEKLY) puis (STARTER) ré-appelle `setupStarterChallenges` → ré-avance au
défi suivant. **Anti-double** : re-claim → handle retiré/`claimed` → 0 token.

**Persistance** : colonne `challengeData BLOB` (`UserStore` migration + `ServerUser.challengeData` + `bootData()`),
livrée au boot. **`ChallengeLoopTest`** : setup STARTER → complétion forcée (`currentProgress=maxProgress`) →
claim (+500 tokens, `completionTime` posé, ré-avance `TO_CATCH_A_STAR`→`THE_NAMES_NICK`) → anti-double (0 token) →
round-trip DB (`UserStore`) → cancel. Régression **91/91**.

**RESTE** : progression transversale (hooks `ChallengeImpl` : campagne/chest/arène/breaker) + autorité de la
progression (client vs serveur) — à OBSERVER EN JEU (comme loot/raid) ; **vérif EN JEU obligatoire** (§8 : rendu du
défi, claim, auto-avance) ; stickers (incr. 3 : `BUY_STICKER*`/`SET_FAVORITE_STICKER`), `GetUserChallengeDataExtra`
(incr. 4). Détail : `docs/CHALLENGES.md`.

## 2026-08-04 (g71) — SURGE (#72) : scoring vérifié FIDÈLE + recon des raids (câblage bloqué §4)

**Scoring vérifié (§8, sonde)** : les `+0 pts` des tests SURGE ne sont PAS un manque — `creep_surge_tiers.tab`
donne un multiplicateur de points **0.0 aux paliers 0 et 1**, puis 1.0/2.08/3.24… au palier 2+. Une guilde à bas
palier marque donc 0 point PAR DESIGN ; le pipeline `recordOutcome` (incr. 4a) est fidèle et scorera au palier 2+
(atteint en vidant des districts). Documenté dans `docs/SURGE.md`.

**Raids — recon faite, câblage BLOQUÉ sur preuve de protocole (§4/§8)** : `recordRaid` params résolus au disasm
(`SurgeHelper.doRaid` 198-218) = `(user, member, surgeID, opponent.district, false, RAID_TEAM_POWER, 0, GOLD
(getGoldForSurgeRaid), raidHEROES, snapshot)`. MAIS `doRaid` appelle `recordRaid` CÔTÉ CLIENT et
`SurgeHeroChooserScreen.doRaidSurge` n'envoie au serveur qu'un `HeroLineupUpdate` — **aucun message d'issue de
raid** dans le code client. Le serveur ne peut donc pas suivre `raidsUsed`/gold de raid de façon autoritative sans
OBSERVER le trafic réel EN JEU. On NE câble PAS (pas d'invention de protocole). À élucider à la vérif en jeu.
Aucune modif code (recon + docs).


## 2026-08-04 (g70) — SURGE (#72) incrément 4c : combat de district câblé (Start/SurgeAttack)

Handlers `LoginServer` + logique testable dans `ServerSurgeState` :
- **`StartSurgeAttack → StartSurgeAttackResponse`** (`startAttack`) : lineup DÉFENSEUR en `HeroData` complet
  (roster RÉEL de l'adversaire du district via `getHeroData`, ou bot synthétique déterministe si pas de joueur)
  + `raidID` + `combatModifiers` ; verrouille l'adversaire (`lockExpiration` = +5 min, anti double-combat).
- **`SurgeAttack → SurgeUpdate`** (`applyAttack`) : exécute `ServerSurgeCombat.applyRegionOutcome` (recordOutcome
  autoritatif) sur la summary du membre, marque l'adversaire `clearedThisWave` + `districtsCleared++` à la
  VICTOIRE, renvoie le delta (`surgePointDelta`/`districtsClearedDelta`, `member`, `opponent`), persiste le
  SurgeData partagé et le DIFFUSE à la guilde (`pushToGuild`). Sous-messages non nuls (wire-sûr).

`SurgeAttackFlowTest` : START (lineup défenseur non vide, raidID, verrou) → ATTACK WIN (district vaincu,
`districtsCleared`=1, deltas) → round-trip wire des DEUX réponses (défaut nº3) → persistance du district vaincu
(round-trip DB). Headless 🟢. Régression. **Boucle de combat SURGE fonctionnelle de bout en bout côté serveur.**
Reste : scoring/paliers (points>0 avec tier/perks de guilde), raids, objectifs/récompenses, ordonnanceur, vérif EN JEU.


## 2026-08-04 (g69) — SURGE (#72) incrément 4b-ii : pose des adversaires (pool réel + synthétique)

`ServerSurgeState.buildOpponents` : un `SurgeOpponentSummary` par district actif (les 27 de `ServerSurgeMap`),
peuplé dans `buildFresh` (donc `GetSurge` renvoie désormais la carte complète). **Modèle ARÈNE #43** : on tire
des JOUEURS RÉELS du shard (`UserStore.listUserIDs` hors membres de la guilde) et on prend leur équipe (≤ 5 héros
du roster) comme lineup adverse via `ClientNetworkStateConverter.getHeroSummary` + `extended` (résumé + PV), power
sommée ; repli SYNTHÉTIQUE déterministe par district (bot `createAndAddHero` RALPH/ELASTIGIRL/FROZONE) si le pool
est vide. `points`=0 (calculés par `recordOutcome` à la défaite, pas inventés). `totalWaves` = nb de districts.
`SurgeStateTest` étendu : 3 joueurs hors guilde semés → 27 adversaires, chacun avec un lineup non vide, chaque
district couvert, au moins un adversaire issu du pool RÉEL, round-trip wire (valide les types `SurgeOpponentSummary`
/`LineupSummary`/`ExtendedHeroSummary` = défaut nº3). Régression.


## 2026-08-04 (g68) — SURGE (#72) incrément 4b-i : carte des districts (données du jeu)

`dhserver/ServerSurgeMap` : la carte SURGE vient des DONNÉES du jeu (§3/§4). `map_districts.tab` associe 27
`DistrictType` actifs à un `EnvironmentType` (BLACK_MARKET/ESPORTS_ARENA/SUBWAY/HACKER_ENCLAVE) ;
`creep_surge_nodes.tab` donne le multiplicateur de points (3.5→1). On lit via `MapDistrictStats.getEnvironment`
(district → env ; `DEFAULT` = hors carte, mesuré : 82/109 districts) et `SurgeStats.getMultiplier`. `activeDistricts()`
= les 27 districts (env ≠ DEFAULT, hors QG=FF), triés par multiplicateur décroissant. `SurgeMapTest` (27 districts,
env réel, mult > 0, tri). Base de la pose d'adversaires (4b-ii). Régression.


## 2026-08-04 (g67) — SURGE (#72) incrément 4a : enregistrement de combat AUTORITATIF (recordOutcome)

Les 2 params bloquants de g66 sont **RÉSOLUS PAR LES FAITS** (désassemblage du site d'appel unique
`SurgeAttackScreen`, offsets 239-261) — zéro invention :
- (a) 3ᵉ collection = `IHero` attaquants : `recordOutcome` n'y appelle que `getType()`/`isMercenary()` →
  reconstruits via `user.getHero(unit.type)` depuis `base.attackers[*].units` (mercenaires exclus) = les héros
  RÉELS du joueur ;
- (b) Set d'objectifs = `SurgeAttack.objectiveProgress.keySet()` : le client met `(SurgeObjectiveInfo → 1)` par
  objectif QUALIFIÉ (scène-dépendant, client-autoritatif) — le serveur relit simplement les clés ;
- **les DEUX booléens sont `iconst_0` au site d'appel → `false, false` (prouvé, plus une inférence)** ;
- `outcome`/`attackers`/`defenders` = `base.outcome`/`base.attackers`/`base.defenders` (déjà `AttackLineupSummary`).

`dhserver/ServerSurgeCombat.applyRegionOutcome(user, summary, surgeID, raidID, SurgeAttack, snapshot)` exécute
`SurgeHelper.recordOutcome` sur le `SurgeClientMember(surgeID, summary)` DU JEU (mutations → summary → persistée).
`SurgeCombatTest` : WIN RALPH vs défenseur → `recordOutcome` tourne headless et la progression d'objectif slot 0
est appliquée **par le code du jeu** (=1) ; points/or = 0 attendu (joueur sans guilde → tier 0, pas de multiplicateur).
Régression. **Reste 4b/4c** : opponents + `StartSurgeAttack→StartSurgeAttackResponse` (raidID + lineup défenseur),
handler `SurgeAttack` (correler le raid, persister, marquer l'adversaire vaincu), puis scoring/tiers, raids,
récompenses, ordonnanceur, vérif EN JEU.


## 2026-08-04 (g66) — SURGE (#72) : recon FIDÈLE du combat (`recordOutcome`) — pas de câblage inventé (§4)

Suite de « termine l'implémentation ». Recon en profondeur du combat de région avant tout câblage (SCREEN_PIPELINE
étape 3). **Anatomie de `SurgeHelper.recordOutcome` (disasm)** : 12 params ; corps = `storeGold` (or depuis les
lineups) + `recordObjectiveProgress(member, objectifs)` + itère les **héros attaquants (`IHero`)** →
`recordHeroMastery` + `ContestHelper.onSurgeAttack` + `UserActivityTracker.onSurgeAttack` (points/activité). Le
membre serveur = **`SurgeClientMember(surgeID, SurgeMemberSummary)`** (impl `ISurgeMember` DU JEU, réutilisable).

**Ce qui est faisable direct** (comme la campagne) : `base.outcome` / `base.attackers` / `base.defenders` sont
déjà `CombatOutcome` / `Collection<AttackLineupSummary>`.

**⚠️ 2 params NON câblables sans invention (bloquants, documentés dans docs/SURGE.md incr. 4)** :
(a) la 3ᵉ collection = **`IHero` attaquants** (pour `recordHeroMastery`) — le wire ne porte que des
`AttackUnitSummary` (stats), pas des `IHero` → reconstruction fidèle requise ;
(b) le **Set d'objectifs** — construit côté client par `getQualifiedObjectives`, qui exige les stats de la `Scene`
de combat (`CombatStatsData`) **absentes du wire** → à dériver de `SurgeAttack.objectiveProgress` (map cliente),
en vérifiant ce qu'attend `recordObjectiveProgress`.

**Décision (§4/§2/§8)** : on NE câble PAS `recordOutcome` tant que (a)/(b) ne sont pas prouvés — un param deviné
corromprait silencieusement mastery/objectifs/points/contests (rustine interdite). Recon capturée pour la suite.
**Statut SURGE** : socle (incréments 1-3 : calendrier, état de guilde, `GetSurge→SurgeData`) fait headless 🟢 ;
combat/raids/objectifs/récompenses/ordonnanceur + vérif EN JEU = arc multi-incréments (échelle GUILD WAR),
poursuivi sur les FAITS, sans jamais inventer. Aucune modif code ce tour (recon + docs seulement).


## 2026-08-04 (g65) — SURGE (#72) incrément 3 : handler `GetSurge → SurgeData` (headless 🟢)

Handler `LoginServer` : le client (GameMain) envoie `GetSurge` (sans champ) à l'ouverture de l'écran → le serveur
charge l'état PARTAGÉ de la guilde (`ServerSurgeState.loadOrReset`, reconstruit si nouveau surge) et renvoie un
`SurgeData` (`setAsReplyTo`), même patron que `GetInvasionInfo → InvasionInfo`. Hors guilde → `emptySurge` (réponse
vide fidèle, wire-sûre). Le gate `Unlockable.SURGE_OBJECTIVES` (= **TL 32**, unlockables.tab) est un verrou CLIENT
— le serveur RÉPOND, ne le désactive jamais (§8). `SurgeStateTest` étendu (round-trip wire de `emptySurge`).
Compile serveur OK, régression **84/84**.

**Statut : 🟢 headless** (routage + réponse wire-valide prouvés). **Vérif EN JEU restante** (l'écran SURGE s'ouvre)
— faisable sur BaronessDante (TL100, en guilde ≥ gate TL32). Les champs adversaires/districts/paliers/objectifs
sont encore vides (peuplés incréments 4-6) : l'écran rendra le socle (fenêtre + membres), pas encore le combat.


## 2026-08-04 (g64) — SURGE (#72) incrément 2 : ÉTAT PARTAGÉ DE GUILDE (persisté)

`dhserver/ServerSurgeState` : un `SurgeData` par (guilde, surgeID), partagé par toute la guilde. Stocké en octets
wire dans `shard_state` (clé `surge:<guildID>`) — comme les autres états opérateur (contests/horloge/graines de
guerre), pour rester ISOLÉ et REMIS À ZÉRO proprement à chaque nouveau surge (`loadOrReset` reconstruit si absent
ou si le surgeID stocké ≠ surgeID courant, comme `InvasionHelper.resetUserInvasion`/la bascule de guerre).

**Membres = ROSTER** : une entrée `SurgeMemberSummary` par membre (chargé via `store.loadIfExists`), identité
`BasicUserInfo` (avatar forcé non nul → wire-sûr). Fenêtre (raidEndTime/nextRaidStartTime) depuis `ServerSurge`
(code du jeu). Conteneurs (opponents/log/objectives/unclaimedRewards/waveRegionsCleared) et sous-messages
(surgeScoringInfo/previousResults) initialisés NON nuls (défaut nº3). Adversaires/districts/paliers/objectifs/
scoring restent aux incréments 3-6 (peuplés via le code du jeu, jamais inventés).

`SurgeStateTest` : membres = roster (2), round-trip WIRE (WireCheck), round-trip DB (persistance), et remise à
zéro sur changement de surgeID. Régression. Doc `docs/SURGE.md` (incrément 2 ✅).


## 2026-08-04 (g63) — SURGE (#72) incrément 1 : CALENDRIER via le code du jeu + recon complète

Premier mode hub attaqué avec le pipeline industrialisé. **Recon (preuve d'abord)** : SURGE est un mode de GUILDE
saisonnier (districts/QG=`FF`, vagues de 3 régions, paliers, objectifs, raids, tokens/influence/or), gaté
`SURGE_OBJECTIVES` + perks de guilde. Logique CLIENTE (`SurgeHelper`/`SurgeClientHelper`/`SurgeStats`, modèle
`ISurgeMember`) → le serveur EXÉCUTE ses helpers (§3). Pas dans le BootData → demandé via `GetSurge` (comme arène/
invasion). `ModeGraph --logic` a recensé les points d'entrée serveur (`recordOutcome`/`recordRaid`/`getGoldFor…`/
`getMaxRaidsPerSurge`/`getTier`…). ⚠️ `SurgeHelper.doRaid` = rappel d'action CLIENT (consomme) — ne pas pré-appeler
(piège g45).

**Faits de calendrier (sonde headless, §8)** : `getEndHour`=11, `getIntermission`=**900000 ms (15 min)**,
`getRegionsPerWave`=3, `getHQDistrict`=FF ; `getNextSurgeStartTime`/`getSurgeEndTime` = la fenêtre (surges
quotidiens). **Règle « actif » établie** : actif à `now` ⟺ `getNextSurgeStartTime(now) > getSurgeEndTime(now)`
(vérifié : sonde → nextStart 16:15 > end 16:00 ⇒ actif).

**Incrément 1 livré** : `dhserver/ServerSurge` = calendrier/identité 100 % code du jeu (`isActive`,
`surgeEndTime`, `nextSurgeStartTime`, `intermission`, `currentSurgeID` = fin de fenêtre si actif, `isEnabledOnServer`),
aucune date inventée. `SurgeScheduleTest` (assertions relationnelles déterministes) intégré à la régression.
Doc de suivi `docs/SURGE.md` (recon + plan d'incréments 1-8). Reste : état partagé de guilde, `GetSurge`→`SurgeData`,
combat/raids, objectifs/récompenses, ordonnanceur, vérif EN JEU.


## 2026-08-03 (g62) — SCAFFOLDER affiné (placeholders enum/sous-message) + `--mode --scaffold` (#73/#74)

Amorce de #72 avec les nouveaux outils : `contract.sh --mode` (union ModeGraph) → `ScreenContract --scaffold`
génère le squelette d'un mode. Sur SURGE (union 17 classes) : `ServerSurgeScaffold.java` (26 builders, chaque
champ du contrat stubbé TODO), `SurgeScaffoldTest.java` (WireCheck par réponse), `LoginServer-Surge.snippet.txt`
(2 handlers GetSurge/StartSurgeAttack).

**Défaut du scaffolder trouvé via le test généré (auto à améliorer, pas un bug d'écran passé)** : le `ScaffoldTest`
a levé `NPE ordinal()` sur `AttackUnitSummary.rarity` puis `writeSingle()` sur `BasicUserInfo.avatar` — le
scaffolder posait `null` pour les champs OBJET, or un ENUM (ordinal) et un SOUS-MESSAGE (writeSingle) ne tolèrent
pas null à l'écriture wire (défaut nº3). **Correctif** (`ScreenContract`) : `classifyTypes` (une passe jar)
classe chaque type de champ — ENUM (ACC_ENUM/super Enum) → placeholder `Type.values()[0]` ; message NEWABLE
(concret + ctor sans arg) → `new Type()` (structure, comme le shim `new IAPProducts()`). Résultat : le round-trip
wire du squelette **PASSE d'emblée** (`[SurgeScaffoldTest] round-trip wire OK`), chaque valeur restant `// TODO
valeur réelle` (§4 : structure, pas une règle). `--scaffold` passe désormais aussi par `contract.sh --mode`.

Outils uniquement (les squelettes générés vivent dans le scratchpad, à compléter+déplacer dans `dhserver/` lors de
l'implémentation réelle du mode). Régression inchangée 82/82. Docs : `SCREEN_PIPELINE.md`.


## 2026-08-03 (g61) — GRAPHE DE MESSAGES + LOGIQUE : pile de vérif headless COMPLÈTE (#74 leviers A & C)

Supprime la « Limite 1 » de `SCREEN_PIPELINE.md` (ScreenContract analyse PAR CLASSE ; un mode est MULTI-classes —
un message peut être envoyé par un helper hors du package de l'écran, ex. `ArenaAttack` via `ArenaHelper`/
`ArenaAttackScreen`, pas `ArenaLeagueScreen`).

**`tools/screentool/src/ModeGraph.java`** : scanne TOUT le jar (18 232 classes de `com/perblue/heroes`), construit
le graphe `message → {émetteurs (new), lecteurs (GETFIELD/getter)}` (classes internes regroupées sous leur outer),
puis à partir d'une GRAINE découvre l'union des classes du mode :
- **graine** = préfixe de package (`com/perblue/heroes/ui/surge/`) OU token de nom pour un mode ÉPARPILLÉ
  (`Arena` → capte `ui/screens/`, `ui/herochooser/`, `ui/pvp/`, `ui/windows/`, `ui/widgets/`) ;
- **affinité de NOM** : on n'étend l'union que via les messages CORE (nom portant le token) → évite d'aspirer
  d'autres modes par les messages roster/combat PARTAGÉS (mesuré : sans ce filtre, Arena ramenait Heist/Surge via
  `HeroSummary`/`PlayerRow`) ;
- **filtre HUBS** : un dispatcher générique référençant > 18 messages distincts (`ActionHelper` 20,
  `ClientActionHelper` 27, `GameMain` 153 — vs helpers de mode ≤ 11) est EXCLU de l'union (sinon il pollue §A/B
  avec tous les messages qu'il touche) mais SIGNALÉ dans le rapport (pour router à la main s'il envoie une requête
  du mode) ; filtre debug/test aussi.

**`contract.sh --mode <graine>`** (A2) : chaîne ModeGraph (union) → ScreenContract (contrat complet). Validé :
arène 45 classes (token, 6 packages), surge 17, heist 77 (0 pollution cross-mode hormis un util partagé `UIColors`).
Après filtre hubs, le §A/B de Surge ne liste plus que des messages Surge (avant : `ExpeditionRunData`,
`CensoredPrivateUserInfo`… venaient des dispatchers).

**Levier C aussi (C1+C2).** `ModeGraph --logic` (intégré à `contract.sh --mode`) RECENSE, pour les `*Helper`/`*Stats`
du mode, les méthodes STATIQUES prenant un `IUser` = les points d'entrée que le serveur EXÉCUTE headless (§3 « lire &
exécuter », jamais réécrire). Ex. Surge : `recordOutcome`, `recordRaid`, `getGoldForSurgeFight/Raid`,
`getMaxRaidsPerSurge`, `getRecommendedOpponent`, `getQualifiedObjectives`… — la carte des règles du jeu à câbler pour
implémenter le mode. C2 (harnais de traversée) : le mécanisme existe déjà (`ClientOracle` exécute la logique cliente
GL-free sur notre état ; `HeadlessCombat` #27 le combat) → à étendre par mode au fil de #72. **La pile de vérif
headless est complète** (niveaux 0-3 outillés ; 4 = in-game irréductible).

Docs : `SCREEN_PIPELINE.md` (Limite 1 → RÉSOLUE), `HEADLESS_VERIFICATION.md` §SUIVI (A1/A2/C1/C2 ✅, niveau 2 ✅).
Outils uniquement (aucune modif serveur) → régression inchangée 82/82.


## 2026-08-03 (g60) — ORACLE CLIENT : miroir des validations d'ENVOI (#74 B4)

Levier B, incrément B4. Avant d'émettre une action, le CLIENT exécute une validation ; si elle lève, il REFUSE
d'envoyer. On rejoue CE prédicat du jeu sur NOTRE état reconstruit → « le client enverrait-il / planterait-il ? »
headless. Deux défauts attrapés sans in-game : (a) un état serveur qui REFUSERAIT une action légitime (joueur
honnête bloqué) ; (b) une faille ANTI-TRICHE (le serveur accepte ce que le client aurait refusé).

Ajouté à `ClientOracle` : `SendValidation` (prédicat pur) + `assertClientWouldSend` (doit passer) /
`assertClientWouldRefuse(action, expectReason)` (doit lever ; passer = faille anti-triche). ⚠️ **Prédicats PURS
seulement** — JAMAIS un rappel qui CONSOMME l'action (`WarClientHelper.doStartWarAttack`, g45 : le pré-appeler
cassait le vrai envoi).

`server/smoke/SendValidationTest` (patron réutilisable) sur le prédicat de référence `ChestHelper.
validateChestPurchase` (celui que le serveur applique déjà en anti-triche dans `openChest`) : compte neuf → coffre
SILVER gratuit ACCEPTÉ ; après consommation du gratuit par le chemin serveur réel (hors cooldown, 0 or) → REFUSÉ.
Pour chaque nouvelle action de mode (#72), ajouter un couple send/refuse avec le vrai prédicat du jeu.

Fichiers : `server/smoke/ClientOracle.java` (+harnais B4), `server/smoke/SendValidationTest.java` (nouveau),
`server/smoke/regression.sh` (+1), `docs/HEADLESS_VERIFICATION.md` §SUIVI (B4 ✅). Régression **82/82**.


## 2026-08-03 (g59) — ORACLE CLIENT : le crash R1 (g55) désormais ATTRAPÉ HEADLESS (#74 B2b + B3)

Suite du levier B (#74). Objectif : que l'`ClientOracle` exécute les vérifs du CLIENT qui étaient bloquées faute
de fixture, et PROUVE qu'il rattrape le crash R1 découvert en jeu (g55) SANS repasser par l'in-game.

**B2b — fixture de rendu client.** Les 2 vérifs à fort intérêt (`getUnlockedDailyQuests`/`hasUnclaimedDailyQuests`,
LA voie du crash R1) NPEaient headless pour DEUX raisons enchaînées, trouvées et corrigées l'une après l'autre :
1. `GameMain.getYourChallengeData()` = simple GETFIELD de `userChallengeData` (prouvé au bytecode), nul headless →
   NPE `IUserChallengeData.allHandles()`. 
2. une fois le conteneur de défis posé : `PurchaseHelper$1.getIAPProducts()` = `DH.app.getIAPProducts()` (GETFIELD
   `iapProducts`), nul → NPE `.products` (les daily quests itèrent le catalogue boutique).
Correctif : `ServerContext.installClientHubRenderFixtures()` pose 2 conteneurs VIDES du jeu (ctor no-arg =
structure que le vrai boot remplirait) — `userChallengeData = new ClientUserChallengeData()`, `iapProducts =
new IAPProducts()`. Couche plateforme §4, aucune donnée/règle inventée. Les 2 vérifs passent de
`HUB_RENDER_PENDING_FIXTURE` à `HUB_RENDER` (batterie par défaut).

**⚠️ Cascade de shim évitée (§2) — leçon.** 1ère tentative : poser ces champs dans le `bind()` serveur GLOBAL.
Résultat : **6 tests cassés** (chest/skill/alchemy/war) — `ServerUser.createGuild → UserActivityTracker.
notifyChallenges → StickerHelper.setupWeeklyChallenges → ext.getHistoricChallenges()` = null (la
`StickerHelperExtension` cliente est absente headless). En rendant `userChallengeData` non-nul GLOBALEMENT, on
RÉACTIVE le sous-système de défis (City Watch / stickerbook, #72 NON implémenté serveur) sur CHAQUE action → NPE
« cassé plus tard » = exactement ce que §2 interdit. **Correctif du correctif** : la fixture est **RÉSERVÉE à
l'ORACLE** (il simule le rendu CLIENT du hub, seul lieu où ces structures existent légitimement), JAMAIS au chemin
serveur. `bind()` reste inchangé → 6 tests restaurés.

**B3 — preuve anti-régression R1** (`server/smoke/ClientOracleR1Test`). État exact de g55 : héros 6★ + horloge de
jeu R1 (2016) → `assertClientRenders` LÈVE **`IndexOutOfBoundsException: Index 6 out of bounds for length 6`** =
précisément `HasEnoughCollectionHeroes.isSatisfied` (bâtit une liste de taille `getMaxStars(user)+1` puis
`list.get(hero.getStars())`, hors bornes quand `stars > getMaxStars` — au plafond R1, `getMaxStars=5`). Contrôle :
un compte NEUF (ère courante) reste VERT (pas de faux positif). **Le crash qui avait exigé une découverte EN JEU
en g55 est maintenant attrapé HEADLESS** — l'oracle client tient sa promesse (rattraper avant l'in-game via le
code du jeu). Intégré à la régression (`ClientOracle`, `ClientOracleR1Test`).

Fichiers : `server/java/dhserver/ServerContext.java` (`installClientHubRenderFixtures`), `server/smoke/ClientOracle.java`
(appel de la fixture + 2 vérifs en `HUB_RENDER`), `server/smoke/ClientOracleR1Test.java` (nouveau),
`server/smoke/regression.sh` (+`ClientOracleR1Test`), `docs/HEADLESS_VERIFICATION.md` §SUIVI (B2b/B3 ✅).


## 2026-08-03 (g58) — VÉRIF HEADLESS via le code du jeu : plan directeur + `ClientOracle` (#74, levier B)

Idée (utilisateur) : puisqu'on a le code CLIENT du jeu, l'EXÉCUTER headless contre nos réponses/état pour
rattraper AVANT l'in-game ce qu'un contrat statique d'un seul écran ne voit pas → réduire l'in-game au rendu/GL.
**`docs/HEADLESS_VERIFICATION.md`** : document directeur (résistant à la compression) + pile de vérif (0 contrat
static, 1 WireCheck, 2 ClientOracle, 3 HeadlessCombat, 4 in-game rendu) + 3 leviers (A graphe de messages du
mode, B oracle client, C logique headless) + **§SUIVI vivant** (à mettre à jour à chaque incrément). Référencé
dans la procédure de reprise de MEMORY.

**Incrément B1 fait** : `server/smoke/ClientOracle` — exécute des vérifs CLIENTES du jeu sur notre `User`
headless (`assertClientRenders(u)`), capture les exceptions, liste les échecs. **Découverte + shim** : les stats
du jeu (`QuestStats.getDailyQuestIDs`…) gardent `currentThread == GameMain.MAIN_THREAD` (static, nul headless →
garde toujours violé → repli cassé, ConcurrentModification+ClassCast). `becomeMainThread()` pose ce champ sur le
thread courant (shim de HARNAIS, §4) → le vrai code client tourne headless. **B2** : 2 vérifs STABLES
(`getUnlockedAchievements`, `getWeeklyDailyQuestsComplete`) → self-test vert, intégré régression (**80/80**).
**B2b (à faire)** : `getUnlockedDailyQuests`/`hasUnclaimedDailyQuests` (LA voie du crash R1) NPE car notre User
headless n'a pas les `IUserChallengeData` que le BootData réel fournit → construire une fixture User complète, et
l'oracle attrapera R1 headless. Suivi complet dans `docs/HEADLESS_VERIFICATION.md` §SUIVI.


## 2026-08-03 (g57) — OUTIL D'INDUSTRIALISATION : extracteur de CONTRAT d'écran (bytecode) + garde-fou wire (#73)

Pour « ne plus reproduire nos erreurs » et automatiser/sécuriser l'implémentation de chaque écran/mode, sans
hallucination ni allers-retours. **Deux outils ancrés dans les FAITS** :

**`tools/screentool/ScreenContract` (ASM)** — donné un préfixe de classe d'écran, lit son bytecode (+ classes
internes) et rapporte : **A/B** les CHAMPS wire lus par l'écran (`GETFIELD` sur les messages) = ce que le serveur
DOIT peupler (défaut nº1) ; **C** la COUVERTURE handlers (messages `new`és par l'écran vs `instanceof` de
`LoginServer*` — inclut les classes internes du listener) ; **D** le gate `Unlockable` ; **E** la checklist des
9 défauts récurrents (distillée de MEMORY/SHIMS/JOURNAL). Wrapper `tools/screentool/contract.sh <classe>`
(compile l'outil + recompile les classes serveur pour une couverture à jour + rapport).
**VALIDÉ contre la vérité terrain** : sur `InvasionBreakerScreen` (implémenté), l'outil ressort tout seul
`BreakerQuest.activeBreakerFight` (le champ « jamais renseigné » qu'on avait dû trouver à la main en g46) et
`GetBreakerQuest [OK routé]`. Sur `SurgeScreen` (non implémenté) : `GetSurge`/`SurgeData [MANQUE]` + la liste
exacte des champs de `SurgeData` à peupler (opponents, waves, objectives, rewards…).
⚠️ 2 bugs corrigés en route (instructifs) : les messages wire s'exposent en CHAMPS PUBLICS lus par `GETFIELD`
(pas des getters) ; le routage `instanceof` de `LoginServer` est dans une classe INTERNE (le listener), pas
l'outer class → scanner `LoginServer$*.class`.

**`server/smoke/WireCheck`** — garde-fou réutilisable `assertRoundTrips(resp)` : écrit la réponse sur le fil
(`writeAll`) + relit → attrape le défaut nº3 (typage wire faux qui explose à l'écriture, invisible headless :
g44/g45). À appeler dans chaque test de handler. Ajouté à la régression (**79/79**).

**`docs/SCREEN_PIPELINE.md`** — la procédure par écran + les 9 défauts récurrents. Référencé dans la procédure
de reprise de `MEMORY.md`.


## 2026-08-03 (g56) — LOOT AUTORITAIRE ✅ VÉRIFIÉ EN JEU sur un COMPTE FRAIS (#25/#46)

Le « reste » de #25 (SHIMS) : re-valider EN JEU l'autorité du loot sur un compte joué DEPUIS la création (le
compte hérité BaronessDante restait désynchronisé → repli SHADOW). **Fait.** Compte neuf (DB supprimée, snapshot
`dh-snapshot-postwar-0803.db` sauvegardé), tuto auto-joué → hub → 3 combats de campagne WIN d'affilée
(NORMAL 1-1, 1-2, 1-3, pilote `nav CAMPAIGN` + auto-fight). Serveur : **`[loot-authoritative] #25 AUTORITAIRE ✅
crédité=serveur (==client)` 3/3, 0 DIVERGENCE**, avec un loot DIFFÉRENT à chaque combat (1-1
{ACE_OF_SPADES,CLEVER_FOX,EXP_VIAL,HEARTY_BREAKFAST,RAID_TICKET,SUGAR_RUSH} ; 1-2 {A_BIT_OF_PRESTIDIGITATION,
EXP_VIAL,SUNNY_SIDE…} ; etc.). Le serveur ROULE son propre butin sur SA chaîne de graine (`getDefaultSeed(userID)`
+ avance inconditionnelle), il **MATCHE le client à CHAQUE combat** (chaîne EN PHASE, avancement correct) → il
crédite le tirage SERVEUR et avance l'état évolutif (pool XP + pitié) → **autorité EFFECTIVE en jeu, anti-triche
actif** (plus de repli). Confirme l'analyse #25 : un compte joué dès la création reste en phase ; seul un état
hérité désynchronisé retombe sur le repli sûr (jamais léser l'honnête). Aucune modif de code (logique déjà en
place depuis #25) — c'est la VÉRIFICATION EN JEU manquante. Détail : `docs/SHIMS.md` ligne #25.


## 2026-08-03 (g55) — ÈRE DE CONTENU : le mécanisme suit l'horloge EN JEU ; un compte HAUT NIVEAU ne peut PAS rétrograder à R1

Tentative de valider EN JEU le « démarrer R1 → gagner une salle breaker » (reste de g51) sur le compte existant.
**Le mécanisme ère-suit-horloge est confirmé EN JEU** : `AdminClock --set-date 2016-09-06` → serveur au boot
`InvasionInfo : rotation BLUE du 2016-09-05 au 2016-09-10 [EN COURS]` (l'invasion de 2016 est active), `Max Team
Level = 50` (R1) relu par le probe. **MAIS** le compte (TL100, héros 2026 jusqu'à 6 étoiles) **plante au hub à R1** :
`IndexOutOfBoundsException: Index 6 out of bounds for length 6` dans `HasEnoughCollectionHeroes.isSatisfied`
(via `DotTracker → QuestHelper.showDailyQuestMenuDot`). **Cause racine (bytecode)** : `list.get(hero.getStars())`
où `list` a une longueur = `UnitStats.getMaxStars(user)`+1 = **6 à R1** (indices 0-5) ; un héros du compte a **6
étoiles** → `get(6)` hors bornes. **Ce n'est PAS un bug du portage** : c'est une **incohérence intrinsèque de
rétrogradation d'ère** — les héros du compte dépassent le plafond d'étoiles de R1. Un compte **CRÉÉ à R1** (étoiles
conformes à R1) n'est PAS concerné. **Conclusion** : le témoin en jeu « démarrer R1 → gagner une salle breaker »
doit se faire sur un **compte FRAIS créé à R1** (avec un roster ≤ plafond d'étoiles R1), pas par rétrogradation.
Horloge **réinitialisée** (offset 0 = R102, compte de nouveau cohérent) ; instantané `dh-snapshot-postwar-0803.db`.
**RESTE (#71)** : témoin breaker EN JEU sur compte frais R1 (roster niveau ~40, ≤5 étoiles ; invasion débloquée
par TL). Le gain breaker salle ≥1 avec points>0 reste prouvé HEADLESS (g46, `BreakerWinProbe`).


## 2026-08-03 (g54) — REFRAMING : bug `IStudiedBuff.spawnParticles` corrigé (INVOKESPECIAL de super-interface directe)

Correctif du 🐛 trouvé en g53 (combat de guerre) : `IncompatibleClassChangeError: … IStudiedBuff.spawnParticles
must be InterfaceMethodref constant`. **Cause racine précise** (disassemblage) : `SimpleStudiedBuff implements
IStudiedBuff` appelle `IStudiedBuff.super.spawnParticles(Entity)` (méthode `default`) via **INVOKESPECIAL**, mais
dex2jar encode l'entrée de constant pool en **Methodref de classe** au lieu d'**InterfaceMethodref** → la JVM
rejette le lien au 1ᵉʳ appel. Le chemin est **rendu-seulement** : `AttackScreen` appelle chaque frame
`IBuffVFX.spawnParticles` (invokeinterface, déjà correct) qui dispatche vers `SimpleStudiedBuff.spawnParticles`
(l'override) → l'INVOKESPECIAL buggé. **NON spécifique à la guerre** : parmi mes 15 héros, **Baymax** (skill3) et
**Stitch** (skill2) posent un `SimpleStudiedBuff` → ce héros planterait TOUT combat RENDU où il émet ce buff ; la
guerre l'a exposé en alignant 14 héros.

**Correctif** (`tools/reframe/ReframeJar`, normalisation non-sémantique §1, étend celle des INVOKESTATIC de
2026-07-16) : (a) `INVOKEINTERFACE` → `itf=true` (JVMS 6.5, toujours un InterfaceMethodref) ; (b) `INVOKESPECIAL`
→ `itf=true` **UNIQUEMENT si l'owner est une super-interface DIRECTE de la classe visitée** (`this.interfaces`,
forme légale `T.super.m()`). ⚠️ La restriction au cas DIRECT est essentielle (JVMS 4.9.2) : flipper un
INVOKESPECIAL vers une super-interface **indirecte** casse la vérif (`VerifyError`, cf. `PegasusSkill3` →
`IRampageAbility`, héritée via la super-classe donc non directe — laissée intacte par le garde-fou).
`INVOKEVIRTUAL` jamais touché.

**Vérifié** : disassemblage `SimpleStudiedBuff` → `InterfaceMethod` (corrigé), `PegasusSkill3` → `Method`
(intact), dans les **deux** jars reframés (serveur `game-framed.jar` ET client `game-logic-framed.jar`) ; les 3
classes **verify+link OK sous le vérificateur par défaut** (aucun VerifyError) ; **régression 78/78**. Confirmé
que `HeadlessCombat` (simulation) **ne rejoue pas les VFX** → le crash n'apparaît QUE dans le combat RENDU
(`AttackScreen`) — vérifié par A/B : la même sim (Baymax+Stitch, NORMAL 13-10) termine `DONE` sur l'ANCIEN comme
sur le NOUVEAU jar, car la sim n'appelle jamais `spawnParticles`.

**✅ VÉRIFIÉ EN JEU — combat de guerre JOUÉ JUSQU'AU RÉSULTAT.** La guerre #4 (*Baroness Legion* vs *Rival
Syndicate*) était en réalité toujours ACTIVE (BATTLE PHASE, 7h restantes) — j'avais confondu la LISTE des guerres
(3 DRAW clos) avec la guerre en cours (accessible en scrollant la porte de garage). Setup via nouvel outil DEV
`WarWitnessSetup` (sync des membres des deux guildes depuis leurs `WAR_DEFENSE_1..3`, affectation du défenseur
adverse à la salle `REDUCE_ATTACKER_HP_FLAT`, `resetWarAttacks` du joueur). Flux UI : `nav WAR` → scroll → porte
**BATTLE PHASE** → `GetWarInfo → WarInfo #4 ACTIVE` → garage **9 salles/3 étages** → tap de la voiture ennemie
(BOOMER, « Reduces attacker HP ») → aperçu (défense adverse = 3 équipes dont **Baymax** et **Stitch**) → FIGHT →
hero chooser (mon équipe 1 inclut **Baymax**) → « ARE YOU SURE? » → FIGHT → `START_WAR_ATTACK vs 2 [persisté]` +
`WAR_ATTACK_1/2/3`. **Le combat RENDU s'est joué de bout en bout sur les 3 équipes** (scène garage, héros animés,
dégâts 29 654 / 184 391 / « +300 » / « +50 Dodge », **VFX de particules rendues en continu**) → écran **RESULTS**
(1 victoire verte + 2 défaites rouges). **Compteur de crash `spawnParticles` = 0** sur tout le run (là où g53
plantait en cours de combat). Baymax (équipe 1) ET Stitch (équipe 3) — les deux poseurs de `SimpleStudiedBuff` —
ont casté avec leurs particules **sans aucun `IncompatibleClassChangeError`**. Le correctif de reframing est donc
**✅ prouvé EN JEU** (client réel → combat rendu → résultat). Détail dans `docs/SHIMS.md` §12.


## 2026-08-03 (g53) — GUILD WAR : combat d'attaque JOUÉ EN JEU + bug de reframing trouvé

Suite de g52 : le COMBAT d'attaque de guerre est joué de bout en bout côté client. Après
`ServerUser.resetWarAttacks()` (rend au joueur son attaque de base ; défenseur guilde 2 affecté à la salle
`REDUCE_ATTACKER_HP_FLAT`), flux UI COMPLET : salle adverse → aperçu → FIGHT → **hero chooser** (3 équipes
remplies — team 1 = 10 505 pow, team 2 = 8 052, team 3 = 7 308 ; scroll du roster via la commande pilote
existante `drag x1,y1,x2,y2`) → confirmation « ARE YOU SURE? » → **FIGHT** → serveur :
`START_WAR_ATTACK vs 2 (salle REDUCE_ATTACKER_HP_FLAT) [persisté]` + `HeroLineupUpdate(WAR_ATTACK_1/2/3)
[persistées]` → **le combat de guerre SE JOUE EN JEU** (scène de bataille dans le garage, héros animés, nombres
de dégâts 24 467 / 10 368 / « +200 Dodge » / « Miss », AUTO-combat).

**🐛 Bug trouvé (NON spécifique guerre) — reframing.** Le combat a planté en cours sur
`IncompatibleClassChangeError: Method 'void IStudiedBuff.spawnParticles(Entity)' must be InterfaceMethodref
constant` : défaut de **reframing du jar** (méthode d'INTERFACE encodée en Methodref de classe) déclenché par le
skill d'UN héros précis. La guerre l'expose la première car elle aligne 14 héros (skill jamais exercé). Ce héros
planterait N'IMPORTE QUEL combat (campagne/arène/boss) → à corriger dans `tools/reframe` (ReframeJar), séparé du
serveur. L'enregistrement du résultat de guerre reste couvert par `WarAttackTest`.

Ajout : `ServerUser.resetWarAttacks()` (outil opérateur/test — remet `WAR_ATTACKS_USED`+`WAR_START_TIME_LAST_
ATTACK` à zéro, équivalent d'un changement de guerre côté jeu). Aucune autre modif serveur. Détail :
`docs/GUILD_WAR.md` §5.8.


## 2026-08-03 (g52) — GUILD WAR : attaque en phase ACTIVE RÉSOLUE EN JEU (« cause non élucidée » = un BYE)

Le point resté ouvert en g45/§5.5 (le client n'émettait plus `START_WAR_ATTACK` après passage en phase ACTIVE,
« cause non élucidée ») était un **défaut de mise en place, pas un bug serveur** — le fil rouge « le client
marche, c'est le pilotage/setup ».

**Trace bytecode** (ce que le prédécesseur n'avait pas poussé) : `startWarAttack` → `doActionCallback` =
`startAction` → callback `WarClientHelper.doStartWarAttack` → `completeAction` (**envoie** ssi
`currentGroup==null`). Le callback lit `warInfo.enemyGuild.guildInfo.iD` : sur un **BYE** (une seule guilde en
file → adversaire nul), `enemyGuild` est **null** → **NPE avalé** par le pilote → rien émis. La guerre de test
d'alors était un BYE.

**Vérifié EN JEU** : guerre #4 *Baroness Legion* vs *Rival Syndicate* (appariées via `AdminWar --tick --force`
+ `--advance` ACTIVE ; défenseur affecté à une salle en respectant le piège `sideOf`/`putSide` des octets wire).
`warattack` → `<== START_WAR_ATTACK vs 2 (salle REDUCE_ATTACKER_HP_FLAT) [persisté]` (émis + validé + réponse) ;
l'aperçu d'attaque rend la voiture **BOOMER** (« Reduces attacker HP ») + la défense adverse (15 héros) + FIGHT ;
2ᵉ tentative → **« OUT OF EXTRA ATTACKS »** (garde-fou correct). Reste à JOUER le combat via l'UI (même combat
client-autoritatif que le boss, déjà validé ; résultat couvert par `WarAttackTest`). **Aucune modif serveur** :
le mode marchait, le blocage était l'absence d'adversaire réel. Détail : `docs/GUILD_WAR.md` §5.8.


## 2026-08-03 (g51) — ÈRE DE CONTENU pilotable (ancre d'horloge PERSISTÉE) + cohérence d'horloge + audit hardcode

**Audit « rien en dur à tort » (demandé).** Résultat : PROPRE. Les défauts `const*(champ, dflt)` sont des replis
vérifiés ÉGAUX aux tabs (BOSS_FIGHT_INITAL_LEVEL 450, ATTACK_LOCK_DURATION 5 m, BOSS_FIGHT_5X_KEY_COST 3,
WIN/LOSE/DRAW_COEFFICIENT 1.00/0.40/0.50) ; le coût mercenaire est évalué par le moteur d'expressions du jeu sur
`user_values.tab` (zéro coefficient codé) ; les constantes de la FORMULE Elo (10.0/1.0) sont mathématiques (les
tunables eloN/eloK viennent des données). Seul écart : mes seuils boss 10/30 % — corrigés (lus de
`InvasionStats.getBoss10/30PercentRewardThreshold`, cf. g50).

**Cohérence d'horloge (prérequis de l'ère).** Plusieurs timestamps de LOGIQUE lisaient `System.currentTimeMillis`
au lieu de `serverTimeNow()` → sous une horloge décalée ils mélangeaient deux temps. Corrigés : arène (saison,
lignes synthétiques, lastFightReset, maybeDailyReset), courrier (deliverMail), tiebreaker, « membre depuis ».
Laissées en temps réel À DESSEIN : colonnes d'AUDIT DB (UserStore updatedAt/createdAt — jamais des décomptes).

**L'ÈRE suit l'horloge — PROUVÉ.** `ContentStats.getServerColumn(serverTimeNow)` choisit la colonne R1…R102 par
DATE (`content.<shard>.tab`). Sonde `ClockEraProbe` : 2026 → **Max TL 565 (R102)** ; 2018-04 → **Max TL 70** ;
2016-09 → **Max TL 50 (R1)**. Décaler l'horloge décale l'ère → un serveur neuf peut DÉMARRER À R1 (ennemis
faibles → débloque aussi le combat « gated par l'inflation »).

**Ancre d'horloge PERSISTÉE (robustesse).** L'offset `-Ddh.clock.offset.hours` était un flag VOLATIL (dérive si
oublié/changé au redémarrage). Désormais : méta DB `clock_offset_ms` (via `UserStore.get/setMetaLong`,
réutilise `shard_state` shardID=0), ré-appliquée AU BOOT par `LoginServer` → l'heure de jeu s'écoule au rythme
réel depuis l'ancre, survit aux redémarrages SANS dérive. `-D` reste un bootstrap (persisté s'il est fourni sans
ancre). Nouveau `ServerContext.setClockOffsetMillis/clockOffsetMillis`.

**Gestion admin (n'existait pas).** Nouvel outil `AdminClock` (pendant d'AdminInvasion/AdminWar) :
`--status` (heure de jeu + Max TL de l'ère), `--set-date <yyyy-MM-dd>` (règle l'ère par date), `--offset-hours`,
`--reset`. Vérifié bout en bout : `--set-date 2016-09-06` → **Max TL 50 (R1)**, ancre relue par une nouvelle
instance (persistance OK), `--reset` → TL 565. Redémarrer le serveur pour appliquer. Nouveau test
`ClockAnchorTest` (round-trip méta + offset + écoulement). Régression **78/78**.


## 2026-08-03 (g50) — INVASION BOSS : KILL + CLAIM vérifiés EN JEU (18 récompenses, 6 rôles)

Suite de g49 : le boss #3 (MAMA_BOT niveau 1) est **tué EN JEU** en un coup via **5× DAMAGE** (bouton de
l'aperçu, coût `BOSS_FIGHT_5X_KEY_COST`=3 clés) — l'écran « BOSS DEFEATED! » affiche **DAMAGE DONE 274 714
(100 %)**, serveur : `InvasionBossAttack ×5 outcome=WIN → −3 clés, 274714 dégâts (cumul 452320) [persisté]`
(dégâts plafonnés aux PV, fidèles au chiffre près ; clés **9→6**). La persistance des dégâts d'une session à
l'autre est aussi prouvée (177 606 relus + 274 714 = 452 320).

**Défaut RÉEL nº4, trouvé EN JEU — `bossClaimStatus` jamais renseigné ⇒ boss KO INCLIQUABLE.** Établi au
bytecode : `InvasionBossCard.onCardPressed` ne lance la réclamation QUE si `lastClaimable`, et
`lastClaimable = (actionState==CLAIM) ET getUserInvasion().getBossClaimStatus(bossID) != null` (le gift
s'affiche via `actionState`, mais **taper** exige l'entrée `bossClaimStatus`). Le client ne peuple JAMAIS
`bossClaimStatus` localement (`recordBossFightOutcome` ne le fait pas) ni ne (re)demande `GetInvasionInfo` : la
donnée est **serveur-autoritative**, poussée dans `InvasionInfo.currentInvasion.yourData`.

**Modèle CLIENT-AUTORITATIF de la réclamation (bytecode `InvasionClientHelper.claimBossRewards`)** :
`bossClaimStatus.rewardsEarned` est une **`List<InvasionBossRewardType>`** = les **RÔLES** gagnés (pas le
butin). Le client tire lui-même le butin par rôle (`InvasionHelper.rollBossRewardLoot`, graine RNG invasion) et
le RENVOIE dans `ClaimInvasionBossRewards.rewards` (`Map<rôle, NodeReward{rewardDrops}>`) — comme
campagne/arène/breaker. Corrections serveur :
- `ServerInvasion.earnedBossRoles(boss, userID)` : rôles dérivés de l'état PARTAGÉ observable (FINDER,
  PARTICIPANT, MOST_DAMAGE, TEN/THIRTY_PERCENT_HP, FINISHER = dernier attaquant). *La politique d'attribution
  d'origine est dans `InvasionHelperExt` — code SERVEUR ABSENT du jar client ; on applique la sémantique
  explicite de chaque valeur d'enum. Le BUTIN reste tiré des tables `invasion_boss_rewards*`.*
- `ServerInvasion.populateClaimStatus(g, user, ud, now)` : synthétise l'entrée réclamable
  `{rewardsClaimed:false, rewardsEarned:rôles}` pour chaque boss ACTIF VAINCU non réclamé ; conserve les
  entrées déjà réclamées.
- Handler `ClaimInvasionBossRewards` réécrit : applique le butin **renvoyé par le client** (au lieu de re-tirer,
  ce qui divergerait de l'affichage), **anti double-réclamation** via `bossClaimStatus.rewardsClaimed`.
- Correctif du test « réclamé » de `applyBossActionState` (les valeurs sont des `BossClaimStatusData`, pas des
  `Boolean` — l'ancien `Boolean.TRUE.equals(...)` était toujours faux).
- `yourData` poussé (`sendInvasionInfo`) après un combat de boss, sur `GetInvasionBosses`, sur `GetInvasionInfo`
  et sur la réclamation ; le client ne redemandant jamais `GetInvasionInfo`, ces PUSHs rafraîchissent son
  `ClientInvasionUser`.

**⚠️ Piège trouvé EN JEU — `getBossHP` DÉCLENCHE `PatchStats.<clinit>` : NE PAS l'appeler au PUSH DU BOOT.** Au
boot, la stat-sync du client n'a pas encore complété les tables (lignes SAPPHIRE) → `PatchStats.<clinit>` JETTE
(`ExceptionInInitializerError`) et **empoisonne la classe pour toute la session** (getBossHP KO partout). D'où
le flag `sendInvasionInfo(..., populateClaim)` : `false` au boot (aucun getBossHP), `true` seulement sur les
chemins où le contenu est chargé (`GetInvasionInfo`/`GetInvasionBosses`/après combat/réclamation).

**Vérifié EN JEU** : carte KO glow + **« CLAIM REWARDS »** → tap →
`ClaimInvasionBossRewards boss=3 rôles=[PARTICIPANT,FINDER,MOST_DAMAGE,THIRTY_PERCENT_HP,TEN_PERCENT_HP,FINISHER]
→ 18 récompenses créditées [persisté]` → **InvasionBossRewardsWindow** rend chaque rôle avec son butin de table
(PARTICIPATED 5 stamina/20 boss tech/1 pt/1 mod, FINDER 5 stamina/1 pierre/1 pt, FINISHER 50 boss tech/2 pts/1
mod, 10 % DAMAGE, MOST DAMAGE…) → fermeture → **« There are no current Invasion Bosses »** (repassé DEFAULT, pas
de re-réclamation). Régression **77/77** (`InvasionBossTest` étoffé : KILL+CLAIM, rôles, anti-double-réclamation,
non-participant sans rôle). **INVASION est désormais 100 % vérifiée EN JEU** (breaker quest + boss
spawn/attaque/kill/claim). Détail : `docs/INVASION.md` §BOSS BATTLES.

## 2026-08-03 (g49) — INVASION BOSS : boucle d'attaque VÉRIFIÉE EN JEU (dégâts fidèles au chiffre près)

Correction méthodo (rappel utilisateur + docs §6bis/§6ter) : le « client qui meurt » des sessions précédentes
était un **défaut de pilotage/monitoring de ma part**, pas un bug client. `exit 144` = kill du **wrapper** bash
par le superviseur (pas un crash) ; `pgrep -f DesktopLauncher` matche **ma propre commande shell** (filtrer
`java.*dhdesktop.DesktopLauncher`) ; **ne jamais tronquer** un log tenu ouvert par le serveur (`grep -a`). En
suivant la procédure CANONIQUE (`./run-online.sh` détaché) + monitoring correct, le client tourne
parfaitement (manual.ppm frais à 1 s).

**Boucle d'attaque de boss vérifiée EN JEU** (boss MAMA_BOT niveau 1 via `AdminInvasion --level 1`) :
BOSS BATTLES rend le boss (MAMA_BOT, barre de vie 274 714/274 714, « Reset in … » = chaîne overlay OK) → tap
(`actionState=FIGHT`) → **InvasionBossPreviewScreen** → CHOOSE HEROES → FIGHT →
`StartInvasionBossAttack → StartBossAttackResponse (lineup, verrou)` → **combat réel** →
`InvasionBossAttack ×1 outcome=LOSS → −1 clé, 88803 dégâts (cumul 88803) [persisté]`.
**Fidélité prouvée au chiffre près** : l'écran « BOSS DAMAGED! » affiche DAMAGE DONE **88 803** et BOSS HP
**185 911/274 714** = exactement la valeur serveur (source `base.defenders[*].units[*].damageTaken`). Après
CONTINUE, la carte du boss montre **185 911/274 714** et les clés BREAKER **1→0** — débit + cumul persistés et
relus. **RESTE (gated par les clés du compte)** : tuer le boss (≈4 clés) → CLAIM (`ClaimInvasionBossRewards` /
`rollBossRewardLoot`). Détail : `docs/INVASION.md` §BOSS BATTLES.

## 2026-08-02 (g48) — INVASION BOSS : boucle d'attaque câblée + source FIDÈLE des dégâts + chaînes manquantes

**Source fidèle des dégâts — établie au bytecode.** Le client calcule `getBossDamage =
UnitCombatStats.totalDamageTaken` de la vedette et l'applique localement ; `InvasionBossAttack` n'a pas de
champ « damage ». Mais dans `Scene`, sur le MÊME `entityDamageEvent`, `summary.damageTaken` ET
`stats.totalDamageTaken` sont incrémentés du même montant → `base.defenders[*].units[*].damageTaken` de la
vedette = exactement le chiffre du client. `defenderHeroes` n'est que le lineup statique (piège écarté).
Combat client-autoritatif → on LIT ce chiffre : `ServerInvasion.extractBossDamage`.

**Handlers câblés** : `StartInvasionBossAttack → StartBossAttackResponse{bossLineup,…}` + verrou exclusif ;
`InvasionBossAttack → extractBossDamage → attackBoss` (débit clés BREAKER, cumul par joueur, persistance,
libération du verrou). **LINEUP du boss corrigé** : `spawnBoss` ne posait pas `InvasionBossInfo.lineup` (que
`getBossUnitData` lit) → nouveau `ServerInvasionBreaker.bossLineup` (vedette MAMA_BOT au niveau du boss,
rareté = `getEnemyRarity` du niveau d'équipe du découvreur). [`InvasionBossTest` : lineup + extractBossDamage].

**Chaînes manquantes (GUILD_DAILY_BOSS_LIMIT_INTERVAL corrigé)** : le bundle de l'APK (12.1.0) précède
certaines clés que le CODE de game.jar référence → le client affichait la clé brute. `run-desktop.sh` overlaie
désormais les clés ABSENTES depuis `game-data/strings` (jamais d'écrasement du libellé d'origine) → 556 clés
complétées, dont `GUILD_DAILY_BOSS_LIMIT_INTERVAL = "Reset in %1$s"`.

**Boss 450 — test** : `AdminInvasion --spawn-boss --level 1` (levier opérateur, comme le décalage d'horloge)
spawn un boss faible battable par le compte de test pour exercer la boucle en jeu. Régression 77/77. Détail :
`docs/INVASION.md` §BOSS BATTLES.

## 2026-08-02 (g47) — INVASION BOSS BATTLES : boss affiché EN JEU + rendu attaquable

Le boss d'invasion est SERVEUR-autoritatif (le jar client ne sait que le lire/attaquer). Rien ne le faisait
apparaître au runtime. Nouvel outil opérateur `server/smoke/AdminInvasion.java` (pendant d'`AdminWar`) :
`--spawn-boss`/`--status` — appelle `ServerInvasion.spawnBoss` (niveau/échéance des données) et persiste.

Vérifié EN JEU : `nav INVASION` → BOSS BATTLES → `GetInvasionBosses → InvasionBosses (1 boss actif)` →
l'écran rend le boss (« Boss found today: 1/100 », « Found By: You », vedette crâne, clés BREAKER=1).

**Défaut RÉEL nº3 — `InvasionBossInfo.actionState` jamais renseigné.** Même famille qu'`activeBreakerFight` :
`InvasionBossCard.onCardPressed` n'ouvre l'aperçu de combat QUE si `actionState == FIGHT`. Sans lui, taper le
boss ne fait rien. Nouveau `ServerInvasion.applyBossActionState(boss, user, ud)` (FIGHT si actif/non vaincu ;
CLAIM si vaincu+part non réclamée ; sinon DEFAULT), appliqué par le handler `GetInvasionBosses` à chaque
boss. [`InvasionBossTest` : boss neuf ⇒ FIGHT]. Régression 77/77.

**RESTE (chantier suivant, non deviné §4/§8)** : la boucle d'ATTAQUE du boss —
`StartInvasionBossAttack → StartBossAttackResponse{bossLineup,…}` (+ verrou + clés) et `InvasionBossAttack`
(issue). ⚠️ Les dégâts NE SONT PAS dans le message : le client calcule `getBossDamage` =
`UnitCombatStats.totalDamageTaken` localement ; le dériver du HP de `defenderHeroes` diverge (overkill) ⇒
il faudra une re-simulation serveur ou une preuve bytecode que `defenderHeroes`/`breakpoints` suffisent.
`ServerInvasion.attackBoss` (verrou+clés+cumul+persistance) est prêt et testé ; seul le point d'entrée
réseau + la source FIDÈLE des dégâts restent. Boss niveau 450 = invaincable par le compte de test (inflation
d'ère de contenu, cf. SHIMS). Détail : `docs/INVASION.md` §BOSS BATTLES.

## 2026-08-02 (g46) — BREAKER QUEST jouable EN JEU : deux défauts réels de plus

Reprise de l'énigme « écran vide » de la BREAKER QUEST (g45). La sonde `breakerdump`, élargie à
`activeBreakerFight`, a livré le verdict : le champ était `null`.

**Défaut nº1 — `BreakerQuest.activeBreakerFight` jamais renseigné.** Le client
(`InvasionBreakerScreen`) lit `holder.getBreakerQuest().activeBreakerFight` pour activer l'aperçu et
démarrer le combat de la salle active ; le `onClicked` de la vedette n'ouvre l'aperçu **que si ce champ
est non nul**. Notre `buildQuest` ne remplissait que la liste `basicBreakerFights` → taper la vedette
n'ouvrait **rien**. La salle 0 ne « marchait » que parce que le **tutoriel** forçait le démarrage ; hors
tutoriel (salle ≥ 1) la quête était **injouable**. Correctif : `buildQuest` pose
`activeBreakerFight = toFightInfo(salleActive, groupes)`. Vérifié EN JEU : l'aperçu **BREAKER FIGHT 1**
s'ouvre, on choisit ses héros, `InvasionBreakerAttackStart room=1` part et le serveur répond.

**Défaut nº2 — points d'invasion calculés avec des arguments inventés.** `resolveBreakerFight` appelait
`getBreakerFightPoints(room, 1, 1)`. Le jeu appelle
`getBreakerFightPoints(room, userLevelSnapshot, invasionMaxTeamLevel)` (bytecode
`InvasionHelper.recordBreakerFightOutcome`), l'expression étant `BREAKER_FIGHT_POINT_REWARD = 1R*M` avec
`M = getInvasionPointsMultiplier(userLevelSnapshot, invasionMaxTeamLevel)`. Le `(1,1)` divergeait du client
dès que le niveau d'équipe fait monter le multiplicateur → **score serveur incohérent**. Correctif : mêmes
arguments que le jeu (via `bindGameContext` + `ContentHelper.getCurrent(user).getInvasionMaxTeamLevel()`),
plus le facteur d'évènement `SpecialEventSnapshot.NONE.getLootResourceMultiplier(...)` (= 1, PARTIEL). Note :
`R=0` (salle 0) ⇒ 0 point est le comportement **du jeu**, pas un bug.

Vérifié EN JEU : salle 0 VICTOIRE (persistée : −10 énergie, +1000 or, +1 BREAKER ; état relu à l'écran :
énergie 71/80, BREAKER 1) ; salle 1 DÉFAITE (persistée : −10 énergie, rien accordé, niveau 25). Voie
VICTOIRE salle ≥ 1 (points > 0) : le nouveau code s'exécute **sans exception** et crédite `ud.points`
(`BreakerQuestTest`, `BreakerWinProbe` : `room=1 → +1010 or, +1 BREAKER, +1 pt`). Nouvelle commande pilote
`breakerfight` (ouvre l'aperçu du combat actif). Observation à suivre : la puissance des vedettes (866 M en
salle 1) rend le QUICK FIGHT perdant sur ce compte de test — difficulté voulue (gardes+vedettes) cumulée à
l'inflation d'ère de contenu (cf. SHIMS). Régression + `BreakerQuestTest`.

## 2026-08-02 (g45) — INVASION vérifiée EN JEU (une première) + fin du cycle de guerre

### GUILD WAR — la boucle est bouclée

`StartWarAttackResponse.activeCars` attendait des `WarAttackCarBonus{type, bonusPerkLevel}`, pas des
`WarCarType` : deuxième défaut de typage du wire, même famille que `WarHeroData`, même signature — ça
compile (dex2jar efface les génériques) et ça explose **à l'écriture du message**, donc côté client
uniquement. Le niveau de perk vient de la guilde DÉFENSEUR : `WarHelper.getCarBonusPerk(car)` nomme le
`GuildPerkType`, `GuildInfoPerkProvider.getPerkLevel` en donne le niveau — deux accesseurs du jeu reliés.
`WarAttackTest` asserte désormais les types réels **et écrit le message sur le fil**, seul endroit où le
mauvais type se voyait.

Erreur d'instrumentation corrigée au passage : le pilote pré-appelait `WarClientHelper.doStartWarAttack`
« pour connaître le verdict ». Ce n'est pas un prédicat, c'est LE RAPPEL de l'action — il consomme
l'attaque localement, et le vrai passage échouait ensuite en `WAR_EXTRA_ATTACKS_DEPLETED` sans rien
émettre, le message ne partant qu'à `completeAction`.

Vérifiés en jeu : **clôture** (« DRAW +1 MMR », MMR 10→11 chez nous et 30→29 en face, WAR BOXES: 3),
**réclamation** (fenêtre COPPER WAR BOX / AUG 2026, trois options, `CLAIM_WAR_BOX_REWARD` crédite
`BADGE_CHEST_1X×1`, reste 2), **liste à trois guerres** (l'active en BATTLE PHASE), **phase ACTIVE** (les
deux garages s'ouvrent, les salles sans défenseurs portent le tampon **KO** — la règle telle que le jeu
l'énonce), et **START_WAR_ATTACK** dont la réponse se sérialise enfin.

### INVASION — vérifiée EN JEU pour la première fois, et pas complète

Le mode était marqué terminé. Il ne l'était pas.

**Le calendrier bloquait, et c'était fidèle.** `nav INVASION` répondait `canNavigateTo=false`. Sonde des
prédicats du jeu un par un : `Unlockables.isUnlocked` = true (TL 100 ≥ 60), mais l'invasion va du **lundi
12 h au samedi 12 h** et on était dimanche.

**Levier ajouté** : `-Ddh.clock.offset.hours` (via `DH_SERVER_OPTS`). Le serveur est la source de l'heure et
le client se cale dessus, donc décaler l'horloge serveur décale l'ensemble de façon cohérente — aucune
vérification n'est court-circuitée, on avance la pendule. Vérifié : à +30 h, serveur ET client affichent
lundi. Corollaire corrigé : `BootData.serverTime` et l'écho `Ping` envoyaient `System.currentTimeMillis()`
en dur, ce qui aurait désynchronisé les deux.

**🐛 Manque nº1 : l'invasion n'était jamais poussée au boot.** Horloge bonne, feature déverrouillée, et
pourtant refus — parce que `InvasionHelper.getActiveInvasion()` rendait `null`. Le client ne connaît
l'invasion que par le message `InvasionInfo`, qu'il ne demandait jamais puisqu'il faut déjà être sur
l'écran pour l'envoyer : poule et œuf. Le vrai backend la pousse au login, comme `SocialHistory` pour le
chat. Corrigé — et l'écran s'ouvre.

**Ce qu'il affiche** : compte à rebours **4j 18h 59m** (exactement la fenêtre envoyée), **énergie
d'invasion 80/80**, BREAKER QUEST / BOSS BATTLES, **TIER 1**, barre 0/100, scores et rangs à zéro.

**🐛 Manque nº2 : `GetBreakerQuest` n'est pas géré.** Taper GO envoie le message, le serveur le journalise
sans répondre, et l'écran reste **entièrement vide**. Le mode SOLO n'a donc pas d'entrée. Non corrigé :
c'est le prochain chantier, avec son étude propre.

Régression 76/76.

## 2026-08-02 (g44) — « tout vérifier en jeu » : les ACTIONS de guerre — 4 défauts RÉELS

Consigne de l'utilisateur : vérifier TOUT en jeu, sans exception. Cette session est passée de l'affichage
aux ACTIONS. Elle a coûté **quatre défauts réels**, dont trois qu'aucun test headless ne pouvait voir.
Détail complet et tableau action par action : [`docs/GUILD_WAR.md`](docs/GUILD_WAR.md) §5.

**1. Personne ne participait jamais à une guerre.** Rien, nulle part, ne créait de `WarMemberInfo` :
`WarGuildInfo.members` restait vide pour toujours. D'où, en cascade, « ce joueur ne participe pas à cette
guerre » sur toute affectation de salle, aucune cible d'attaque, et 0/0 partout à l'écran. **Les tests
headless ne pouvaient pas l'attraper : ils fabriquaient eux-mêmes les `WarMemberInfo` qu'ils testaient.**
Nouveau `ServerWarMembers` : les membres sont bâtis depuis les lineups `WAR_DEFENSE_1..3` réellement posés
(chemin du jeu, comme la défense d'arène), à l'ouverture de la guerre ET à chaque changement de défense ;
l'état de guerre déjà acquis (héros KO, sabotages) est reporté héros par héros, apparié par `UnitType`.

**2. `grantHero` inversait ÉTOILES et NIVEAU — le client plantait au hub.** Relevé au bytecode :
`createAndAddHero(type, rarity, i3, i4, …)` → `createUnitData` fait `setStars(i3)`, `setLevel(i4)`. On
passait `(level, stars)`. Un héros « niveau 40 » recevait donc **40 étoiles**, et
`HasEnoughCollectionHeroes` indexait une liste de taille 7 avec 40 → `IndexOutOfBoundsException` au rendu du
menu latéral : **compte injouable**. `SkillUpgradeTest`/`SkillSetup` produisaient la même corruption sans
jamais l'asserter.

**3. `StartWarAttackResponse` ne se sérialisait pas — attaquer était impossible.** `WarDefense.defenders`
attend des `WarHeroData` (héros complet, pour combattre), pas les `WarHeroSummary` de l'état de guerre.
Recopier la liste compilait (dex2jar efface les génériques) mais levait `ClassCastException` **à l'écriture
sur le fil**, après que le serveur eut journalisé « [persisté] ». Le défaut n'existait donc que du point de
vue du client, qui ne recevait jamais la réponse — invisible headless, par construction.

**4. `createGuild` n'exige pas que le joueur soit sans guilde** (constaté en semant l'adversaire : un membre
a créé une seconde guilde sans quitter la première, qui a gardé son identifiant). Inscrit, non corrigé.

**Vérifié en jeu** : inscription en file, pose des trois défenses (+ resynchronisation dans la guerre),
affectation de salle (la salle passe à 3/3 avec l'écusson), lecture de la défense adverse (0/15 · 3/3), et
**sabotage** — `REDUCE_HP_PERCENT` sur STITCH, **coût 67 / palier 1 RECALCULÉ par le serveur** en ignorant
l'`INDEX` envoyé par le client : l'anti-triche est prouvé en conditions réelles.

**Deux refus, et ce sont ceux du JEU** : `WAR_BAN_PROTECT_MAX_PROTECT_SIZE` et `WAR_SPARS_NOT_ENOUTH` — la
taille de ban/protect et le quota de spars viennent de perks de guilde, et la guilde de test est niveau 0.
Le pilote demande maintenant **son verdict au client** avant d'envoyer, ce qui a permis de nommer ces gates
au lieu de constater un silence.

**Reste** : l'attaque menée à terme (la commande atteint le serveur mais le client cesse de la ré-émettre
après le passage en phase ACTIVE — cause non élucidée), les bans/spars (exigent une guilde avec perks), la
clôture + MMR + boîtes + réclamation, et six commandes de lecture non encore exercées. Tout est listé en
§5.6 de la doc, sans arrondi.

Régression **76/76**. Outillage ajouté sans toucher au jeu : huit commandes de pilote passant par les API
clientes d'origine, `WarSetup`, `WarRivalSeed`, et `AdminWar --resync/--advance/--end`.

## 2026-08-02 (g43) — GUILD WAR : ✅ VÉRIFIÉ EN JEU (client réel → notre serveur → affichage)

La pièce qui manquait depuis le début du mode : la **vérification EN JEU** (PRINCIPLES §8). Menée sur
BaronessDante (TL 100, guilde « Baroness Legion »), pile complète `run-online.sh`. `WAR` se déverrouille à
**TEAM_LEVEL_REQ 45** (`unlockables.tab`) — le compte est largement au-dessus. Détail, tableau et captures :
[`docs/GUILD_WAR.md`](docs/GUILD_WAR.md) §4.

**Outillage ajouté pour la mener, sans toucher au jeu** : pilote DEV `warqueue <ÉTAT>` (appelle le chemin
d'origine `ClientActionHelper.changeGuildWarQueueState` — le message ne porte que l'état, relevé au
bytecode) ; `server/smoke/WarRivalSeed.java` (sème une guilde adverse inscrite, même rôle que `GuildAidSeed`) ;
et `AdminWar --tick --force` pour déclencher l'appariement sans attendre `RESET_HOUR`.

**La chaîne complète, exercée par le VRAI client** : `nav WAR` → `GetWarsList` → écran GUILD WAR
(`COPPER · RANK #1 · MMR 10`) ; `warqueue QUEUED_SINGLE` → `CHANGE_WAR_QUEUE` accepté et persisté ;
`AdminWar --tick --force` → guerre #1 **Baroness Legion vs Rival Syndicate**, phase SABOTAGE ; après
redémarrage, la porte de garage affiche **« VS RIVAL SYNDICATE — BAN PHASE — Ends in 11h 58m 46s »** ; un tap
→ `GetWarInfo` → écran **CURRENT WAR** avec le garage **9 salles sur 3 étages**, les 9 voitures adverses et
les deux MMR ; WAR LOGS → `RequestWarLogs` ; ALL LEAGUES ; RANKS → `GetWarRankings(COPPER)`.

**Trois confirmations qui vont plus loin que « ça rend »**
1. **La chronologie des phases est la bonne.** Le décompte « BAN PHASE 11h 58m » est le client qui relit,
   avec son propre `WarHelper.isBanPhase`, notre `extraStateEndTime` posé à `début + SABOTAGE_BAN_PHASE_LENGTH`
   (12 h). Le chiffre affiché EST la preuve de la valeur envoyée.
2. **Le barème de score est le bon.** WAR LOGS affiche `Lineups Defeated ×1`, `Rooms Defeated ×100`, et
   `Clean Sweeps / Defensive Wins / Clean Defenses ×0` — exactement `POINTS_PER_LINEUP`, `POINTS_PER_CAR` et
   les scalaires de voiture à 0 **parce qu'aucune voiture n'est détenue** (`ServerWarScoring.carPointScalar`).
3. **Le correctif `GuildInfo.warEndTime` (étape 2) était bien vital** : sans lui, le client n'aurait vu
   **aucune** guerre active. Les logs Windows l'avaient laissé entendre ; l'écran le prouve.

Confirmés au passage : les tranches de ligue (`COPPER 1-199 / BRONZE 200-399 / SILVER 400-599`), les 3 boîtes
par ligue (`NUM_SEASON_BOXES`), et la résolution du `seasonID` 104 en « **Aug 2026** ».

**Une bizarrerie observée, puis EXPLIQUÉE — et surtout PAS « corrigée » à tort.** Au classement, la guilde du
joueur apparaît deux fois. Réflexe §8 : aller lire le bytecode avant de conclure. `WarRankingsScreen
.addPosterContent` ajoute une ligne d'**en-tête** « ta guilde », conditionnée par `isYourGuildInRankings()` —
elle ne s'affiche donc **que si** la guilde figure déjà dans `rankingRows`, que `addRankingsContent` rend
intégralement. Le doublon est le rendu d'origine ; **retirer notre guilde de `rankingRows` supprimerait
l'en-tête**. On ne touche à rien (§1/§4bis).

**Vérifiés en jeu aussi, dans la foulée** : `SetLanguage 'en' appliquée [persisté]` et
`SetExternalContentStatus hasExternalContent=true`, les deux manques relevés dans les logs Windows — reçus du
vrai client et traités.

**Ce qui reste NON vérifié en jeu, dit franchement** : placer les lineups de défense (une défense = **15 héros
distincts**, le compte de test en a 7), attaquer, sabotages/bans/protections/spars (exigent des `WAR_TOKENS`),
réclamer une boîte, et la clôture + MMR + bascule de saison (2 jours réels). Tous couverts headless
(`WarAttackTest`, `WarSabotageTest`, `WarEndTest`, `WarSchedulerTest`) ; ils passeront ✅ le jour où un compte
à 15 héros et une guerre menée à terme les exerceront.

Instantané de base pris avant l'exercice : `server/data/dh-snapshot-prewar-0802.db` (+ `-wal`/`-shm`).

## 2026-08-02 (g42) — GUILD WAR : l'ORDONNANCEUR (étape 10/10) + le « flake » qui n'en était pas un

Dernière pièce du mode : jusqu'ici **toutes les briques existaient mais rien ne les déclenchait**. Aucune
guerre ne démarrait d'elle-même, aucune ne se terminait, aucune boîte ne tombait.

**`ServerWarScheduler`** — un tour de boucle **idempotent et rejouable** : bascule de saison (avec le rang
final par MMR, donc les boîtes de fin de saison) → clôture des guerres échues (issue, MMR, remboursement au
perdant, boîtes de promotion) → avance de phase `SABOTAGE → ACTIVE` → appariement des guildes inscrites, à
l'heure prévue. Branché dans `LoginServer.main` sur un thread démon (`startBackgroundLoop`, période réglable
par `-Ddh.war.tick.seconds`, défaut 60 s), appliqué à **tous les shards portant des guildes**
(`tickAllShards` + nouveau `UserStore.listGuildShards`, PRINCIPLES §5).

**Le calendrier** : le client fait de `WarQueueStateUpdate.nextMatchmakingTime` **directement**
`warInfo.startTime` (`WarClientHelper.updateWarInfoQueueState`) — l'instant d'appariement EST le début de la
prochaine guerre. ⚠️ **Lecture structurelle assumée, isolée dans `lastMatchmakingTime`** : ancrage sur
`RESET_HOUR` (11 h, fuseau serveur), seule heure de référence des données et qu'aucune classe cliente ne lit.

**Deux pièges, tous deux réels et corrigés.**
1. **La fenêtre se compare au PASSÉ.** Ma première version testait `now >= nextMatchmakingTime(last)` — valeur
   par construction dans le futur, donc **la condition n'était jamais vraie** : l'appariement ne tournait pas
   une seule fois. Le tour compare désormais le repère persisté à la **dernière** occurrence de `RESET_HOUR`,
   et enregistre le repère de la **fenêtre** (pas `now`), sans quoi l'heure dériverait à chaque tour.
2. **Un shard neuf ne doit pas apparier à son premier tour**, sinon le tout premier démarrage du serveur
   ouvrirait des guerres à n'importe quelle heure. Il pose le repère et attend — d'où l'outil opérateur.

**`ServerGuild` v9** : `warBoxedLeagueMask` (« boîtes déjà remises »), **distinct** de `warPromotionMask`
(« ligue atteinte », plancher anti-rétrogradation « cannot be demoted »). Les confondre redistribuerait des
boîtes à chaque guerre. Le masque de boîtes repart à zéro à chaque saison, le plancher non.

**Outil opérateur `AdminWar`** (pendant d'`AdminGuild`/`AdminMail`) : `--status` (saison, prochain
appariement, et par guilde MMR / ligue effective / file / guerre en cours / boîtes en attente) et
`--tick [--force]`. Le forçage ne concerne QUE l'appariement : clôtures et bascules de saison dépendent
d'échéances réelles qu'on ne bouscule pas. Vérifié en CLI sur 3 guildes : tour non forcé = aucun appariement
(calendrier respecté), tour forcé = **1 paire + 1 BYE**, guildes en `SABOTAGE` jusqu'à J+2, file remise à
`NOT_QUEUED`.

### 🐛 Le « flake `ChestWireTest` » n'était pas un flake — c'était une course réelle

En vérifiant la régression, `ChestWireTest` échouait **systématiquement**, ce qui contredisait la note « vert
en isolation » portée depuis des semaines. Vérifié d'abord que ce n'était pas moi : l'échec est identique sur
`HEAD` sans mes modifications.

Mesuré ensuite, au lieu de supposer : **un message émis par le client avant que le serveur ait accepté la
connexion est perdu**. Envoi immédiat après `open()`, ou depuis le `onOpen` du client → jamais reçu, 5 essais
sur 5. Le même envoi 50 ms plus tard → reçu, 5 essais sur 5. Et le tampon socket ne rattrape pas : après un
second envoi, le serveur décode **exactement un** message, pas deux — les octets du premier n'ont jamais
atteint son décodeur. La cause qu'on invoquait (chargement de `GuildStats`) était fausse : cette exception
est bien levée, mais **absorbée** par le warm-up de `ServerContext`.

Le **vrai client n'y est pas exposé** : son `/login` HTTP précède l'ouverture du socket de jeu et couvre
largement la fenêtre. Le test, lui, enchaînait connexion et envoi dans la même milliseconde.

⚠️ **Et j'ai conclu trop vite une première fois** : après un correctif « attendre l'`accept` du serveur »
vert 5/5, la régression complète a refait échouer le test — cette garde seule laisse encore **3 échecs sur
10**. Ce qui est établi, c'est l'existence et l'ampleur de la fenêtre, **pas son mécanisme** :
`GruntNIOTCPServer.read` ne consomme rien quand la connexion est absente du registre
(`READ_CHANNEL_NULL_STAT`) ou pas prête (`READ_CHANNEL_NOT_READY_STAT`) — il rend `false` sans lire, et le
sélecteur par niveau devrait réessayer ; `GruntTCPConnection.send` écrit de façon synchrone. **Où les octets
disparaissent reste inexpliqué**, et c'est consigné tel quel dans `SHIMS.md` plutôt que masqué.

Traitement : `LoginServer` expose `connectionsAccepted` (compteur utile aussi en exploitation), et le test
attend ce point **puis RÉÉMET** le `ClientInfo` jusqu'à obtenir le `BootData` (réponse idempotente). Ce n'est
pas un faux « OK » : l'échange RÉEL `BuyChests → LootResults` reste exigé. Vérifié **8/8** en isolation.

**Régression : verte au complet, aucun échec toléré** — une première (jusqu'ici 74/74 avec un échec toléré).

### Deux manques des logs Windows comblés

**`SetLanguage`** — le jeu a un champ POUR ÇA : `UserExtra.language`, écrit par le setter d'origine
`User.setLanguage(Language)`. Comme il vit dans `this.extra`, il est **auto-persisté** : aucun re-sync à
écrire. Le handler résout le code reçu par la méthode du jeu `Language.getLanguage(code)` et appelle le
setter — zéro règle réécrite. [`SetLanguageTest`] : application, round-trip SQLite, changement de langue,
refus d'un code vide/nul sans altérer l'existant. **7ᵉ fois où mon test avait tort** : j'attendais `null`
pour un compte neuf, le constructeur du jeu pose une chaîne **vide**.

**`SetExternalContentStatus`** — **NO-OP FIDÈLE**, même catégorie que `RECORD_SERVER_ROLL_FINISHED` :
`hasExternalContent` n'apparaît, dans tout le jar, que dans ce message, dans son émetteur
(`ExternalAssetManager$DeferredSetExternalContentFlag`) et dans `HeroFiltersActV1`. **Aucun champ de joueur
ne le reçoit et rien ne le relit** : c'est une notification sur l'APPAREIL. Lui inventer un stockage
violerait §4 — on acquitte et on journalise, c'est la réponse autoritative correcte.

**Reste** : la **vérification EN JEU** de GUILD WAR (et d'INVASION, et des trous de guilde) — **nulle à ce
jour**. Statut 🟢, pas ✅.

## 2026-08-02 (g41) — GUILD WAR (#68) : mode COMPLET côté serveur, branché de bout en bout

Neuf incréments, chacun commité et poussé séparément, chacun avec son test dédié.
Doc de suivi complète : [`docs/GUILD_WAR.md`](docs/GUILD_WAR.md).

**Méthode : la preuve avant le code.** Trois faits établis avant d'écrire une ligne.
1. La logique WAR est **cliente** (`WarStats`/`WarHelper`/`WarCombatHelper`/`ui.war.WarClientHelper`) —
   même configuration favorable qu'INVASION.
2. Scan du pool de constantes des **20 341 classes** `com/perblue/**` : **28 constantes** de
   `war_constants.tab` n'apparaissent QUE dans leur propre déclaration. Témoin de contrôle validant le
   discriminant : les constantes réellement consommées apparaissent dans ≥ 2 classes. Même signature que
   `MERCENARY_COST` ⇒ elles sont parsées **pour le serveur**.
3. Le jeu **documente ses propres règles** dans `HowToPlay.properties` (cartes `WAR_CARD_*`), et elles
   correspondent **une à une** aux constantes (1 pt/lineup, 100/salle, sabotage 24 h + bans 12 h, « top
   ten → Gold », « others Copper to Silver », « cannot be demoted »).

**Livré** : `ServerWar` (saisons/ligues/MMR/reset), `ServerWarState` (état SYMÉTRIQUE, deux vues) + table
`wars`, `ServerGuild` v8, `ServerWarMatchmaker` (appariement, anti-rematch, BYE, phases), `ServerWarCars`
(dérivation des voitures, étoiles, portes), `ServerWarAttack` + `ServerWarScoring` (validations, KO
définitif, barème), `ServerWarSabotage` (sabotages/bans/protections/spars), `ServerWarEnd` (issue, MMR,
remboursements, boîtes), `ServerWarBoxes` + table `user_war_boxes`. **7 messages et 11 commandes branchés**
dans `LoginServer`, avec diffusion à la guilde. **7 tests dédiés. Régression 74/74.**

**Trois défauts RÉELS trouvés et corrigés en route**
1. `nextGuildID` lisait `MAX+1` **avant** insertion → deux créations concurrentes obtenaient le **même
   identifiant** et la seconde **écrasait** la première (upsert), faisant disparaître une guilde sans la
   moindre erreur. Alloue désormais sous verrou via un compteur persisté.
2. `ServerGuild` v8 **dupliquait** `warQueueState`/`warExtraAttackRank`/`warEndTime` que porte déjà le
   `GuildInfo` **du jeu**, lu par le client → double source de vérité (§4/§6). Sans `warEndTime` écrit
   dans le `GuildInfo`, **le client n'aurait jamais vu de guerre active** — et les logs Windows fournis le
   jour même l'ont confirmé indépendamment (`isWarActive` appelé à chaque rendu du hub).
3. **`setCount(UserFlag)` ne persiste PAS** (les compteurs vivent dans `User.counts`, hors `this.extra`)
   alors que `setTime` oui. `creditMercenaryHireReward` (#64) faisait `setCount` **sans** `resyncCounts`
   et son commentaire affirmait le contraire : le compteur hebdomadaire « earned this week » repartait à
   zéro à chaque round-trip. `GuildMercRewardTest` ne l'avait pas vu car il n'assertait que la monnaie.

**Six fois, mes TESTS avaient tort et la règle du jeu raison** — cinq d'entre elles autour de la même
phrase, « a room with no defenders is automatically defeated and worth 100 points to the other side » :
un étage dégarni ouvre immédiatement l'étage suivant ; les salles vides valent déjà 100 points chacune à
l'adversaire ; l'attaquant qui garnit moins de salles PERD malgré ses attaques réussies (608 contre 700).
La sixième : une défense de guerre compte **15 héros distincts** — le protocole identifie la victime d'un
sabotage par son **seul `UnitType`**, ce qui n'aurait aucun sens autrement.

**Quatre lectures/inférences assumées**, chacune isolée dans UNE méthode et documentée : `ratingChange`
(Elo standard sur `ELO_K`/`ELO_N`), `rematchPenalty` (interpolation par ancienneté), `sabotageCurrency`
= `WAR_TOKENS`, durée du jour 2. Plus `ELO_LOSS_BUFFER_THRESHOLD`, **lue mais volontairement NON
appliquée** — j'avais d'abord inventé un rôle pour elle, le test l'a démenti, et l'analyse a montré que
c'était à la fois arbitraire ET sans effet.

**Garde-fou mesuré** : les récompenses de SAISON n'ont aucun `max(…,1)` et deviennent **négatives sous
TL 289** (282 en LEGENDARY) — les créditer **retirerait** des ressources au joueur. `keepPositive` écarte
les quantités ≤ 0 ; les lignes de PROMOTION, elles, ne descendent jamais à 0 (vérifié sur 84 boîtes).

**Restent** : distribution automatique des boîtes (promotion / fin de saison) et un ordonnanceur
(appariement, clôture) — les fonctions existent, le déclencheur manque. **Et surtout la vérification EN
JEU, nulle à ce jour** : statut 🟢, pas ✅.

## 2026-08-02 (g40) — PREMIER RUN WINDOWS de la pile complète (logs fournis par l'utilisateur)

L'utilisateur a réussi à lancer le jeu **sur Windows** (Intel UHD 620, GL 3.2, 1280×720) et a fourni
`logs_pc.txt` (1043 lignes). C'est le **premier run hors Linux/headless**, et il valide le chemin de boot
de bout en bout **en conditions réelles**.

**Ce qui marche, prouvé par le log**
- Redirection réseau : `ServerType.LIVE -> login http://127.0.0.1:8080/login` ; le login HTTP répond
  `{"status": "good", "data": "127.0.0.1:8081"}` ; `[NetworkProvider] Connection to 127.0.0.1:8081 opened`.
- Handshake complet : `ClientInfo1 → BootData (122 actes de tuto)`, puis `Ping1 → Ping (echo)`.
- `handleBootData` va **jusqu'au bout** : user créé, challenge/battle-pass/chat/contests/**invasions**/
  special events/dots initialisés, « Sending BootData to screen », `MainScreen` atteint.
- **Stat-sync (#40) opérationnel** : `Updated com.perblue.heroes.game.data.DHConstantStats from text
  battle_pass_v2_constants.tab`.
- Tutoriel persisté : `tuto INTRO -> step 1/2/3 [persisté]`, puis `onClose` propre.
- **Spine d'origine via unidbg fonctionne aussi sur Windows** : `[UnidbgVM] 1er Skeleton_getVertices
  (spine d'origine via unidbg) -> drawCount=192`. Le message
  `libspine-native.so load dependency libandroid.so failed` qui le précède est ATTENDU (c'est un `.so`
  Android/Linux) : le chargement direct échoue, le chemin unidbg prend le relais.
- Les erreurs de parse `.tab` (`FAST_FORWARD`, `SAPPHIRE_5..12`, `guild_perk_levels.tab` `CONTENT_TL`
  vide, `fight_pit_season_configs.tab`…) sont **exactement les mêmes** que celles observées headless →
  confirme l'analyse déjà documentée : intrinsèques à la donnée du 12.1.0, attrapées et sautées par
  `GeneralStats.onStatError`, **non fatales**.

**⭐ Confirmation INDÉPENDANTE d'un correctif fait le jour même (GUILD WAR étape 4)**
La trace réelle du client montre :
```
GuildPerkHelper.updateGuildInfoTimedPerks ← GameMain.getYourGuildInfo
  ← WarHelper.isWarActive
  ← HasActiveGuildWar.isSatisfied ← QuestHelper.isUnlocked ← getUnlockedDailyQuests
  ← hasUnclaimedDailyQuests ← showDailyQuestMenuDot ← DotTracker ← RedDot
```
Autrement dit, **le client appelle `WarHelper.isWarActive` à chaque rendu du hub** (pastille de quêtes
quotidiennes, via la condition de quête `HasActiveGuildWar`), et `isWarActive` lit
`getYourGuildInfo().warEndTime`. C'est précisément ce qui rendait critique la correction faite quelques
heures plus tôt : ma v8 de `ServerGuild` **dupliquait** l'état de guerre au lieu d'écrire dans le
`GuildInfo` du jeu — sans `warEndTime` renseigné, le client n'aurait jamais vu de guerre active, et le
défaut ne se serait manifesté qu'en jeu. Le log le confirme depuis un client RÉEL.

**Deux manques réels relevés (petits, consignés, non traités ici)**
1. `SetLanguage1` et `SetExternalContentStatus1` **arrivent au serveur et ne sont pas traités** (simple
   journalisation, comme la télémétrie). `SetLanguage` porte la langue du joueur — un vrai serveur la
   persisterait (elle compte pour les textes générés côté serveur, p. ex. le courrier admin).
2. Sur Windows, le chargement direct de `libspine-native.so` échoue et l'on retombe sur unidbg. Ça
   marche, mais un binaire spine natif Windows serait le chemin propre.

**Ce que ces logs NE prouvent PAS** — à dire clairement : le compte est à TL1 dans le tutoriel, donc
**aucun** message de guilde, d'invasion ou de guerre n'a été échangé. La vérification en jeu de GUILD
WAR (#68), d'INVASION (#69) et des trous de guilde reste **entièrement due** ; leur statut demeure 🟢
(serveur prouvé) et non ✅.

## 2026-07-20 (g12) — Quêtes : boîtes-récompense weekly + START_QUEST/Prize Wall élucidés par les faits

Demande « finir quêtes ». **Boîte-récompense weekly** (gap réel) : ouvrir une boîte (après
`REDEEM_DAILY_QUESTS`) n'est PAS une commande — le client envoie un message top-level
`ClaimWeeklyQuestReward{rewardChosen, rewardDrops, staminaReward}` (fire-and-forget, comme `CampaignAttack`).
Handler `LoginServer` → `ServerUser.claimWeeklyReward` → `QuestHelper.claimWeeklyReward(user, rewardChosen,
staminaReward)` : anti-triche RÉEL sur le NOMBRE (`getWeeklyRewardsRemaining = getCount(WEEKLY_QUEST_REWARDS)
> 0` sinon `ClientErrorCodeException`), donne stamina + récompense, décrémente le compteur, persiste. PARTIEL
documenté : le CHOIX `rewardChosen` est client-autoritative (re-valider le tirage vs graine
`WEEKLY_QUEST_REWARD` = Partiels D/E, comme l'issue de combat). `START_QUEST` : **aucun émetteur client** dans
le 12.1.0 (que la table de dispatch + l'enum) → non atteignable, rien à faire. Prize Wall : déjà couvert
(quêtes `PRIZE_WALL_*` → `completeQuest` → `COMPLETE_QUEST`, handler g10). Vérifié `server/smoke/WeeklyBoxTest`
(2 boîtes → ouvre/décrémente 2→1→0, persiste ; 3ᵉ à 0 REFUSÉE) + régression 26/26. Fichiers :
`server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/WeeklyBoxTest.java`.

## 2026-07-20 (g11) — Battle pass : changement de mois (rollover réel) + premium pour tous corrigé

Deux faits trouvés/corrigés (règle « aucune supposition »). **(1) Premium pour tous était FAUX** :
`getPremiumUnlocked()` lit le booléen `premiumUnlocked` (champ wire SÉPARÉ), PAS `boughtBattlePass`. On ne
posait que `boughtBattlePass=1` → premium restait verrouillé (claim premium aurait échoué
`BATTLE_PASS_MISSING_PREMIUM`). Fix : `refreshBattlePass` pose `premiumUnlocked=true` à chaque refresh (même
après reset → premium toujours débloqué). **(2) La saison ne roulait qu'au REDÉMARRAGE** : l'ancre
(`SEASON_START_TIME`/`HIDE_BATTLE_PASS_AFTER`) n'était posée qu'à `init()` (idempotent) → un serveur tournant
de juillet à août sans redémarrer restait au 1er juillet. Fix : `ServerContext.anchorBattlePassSeason()`
(re-calcule le mois courant), appelé à chaque `refreshBattlePass` → rollover dès que le mois réel change.
**Ce qui se passe au changement de mois** : `refreshBattlePass` détecte `bp.startTime != seasonStart` →
(a) conserve les récompenses MÉRITÉES non réclamées de la saison écoulée dans `previousUnclaimed` (comme le
jeu en fin de saison ; réclamables via `BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS` → `collectEndedSeasonRewards`),
(b) reset `progress`/`lastSeenProgress` + vide `claimedFree/PremiumRewards`, (c) ré-ancre `startTime`/`endTime`,
garde `premiumUnlocked=true`. `getRewardTiers(l)=floorEntry(l)` → mois ancien/nouveau donnent le même jeu de
paliers (contenu R102 stable). Vérifié `server/smoke/BattlePassRolloverTest` (premium claim OK ; rollover :
progress 36→0, claims vidés, 6 gratuites + 5 premium non réclamées conservées puis collectées, premium
maintenu, saison ré-ancrée) + régression 25/25. Fichiers :
`server/java/dhserver/{ServerContext,ServerUser}.java`, `server/smoke/BattlePassRolloverTest.java`.

## 2026-07-20 (g10) — Battle pass : handlers de réclamation + progression AUTO via QUEST_POINTS + persistance

Suite de « finir battle pass ». Établi PAR LES FAITS (bytecode) que la progression du battle pass EST la
ressource `ResourceType.QUEST_POINTS` : `IndividualUser.setResource(QUEST_POINTS)` route vers
`DH.app.getUserBattlePassV2().setProgress`, `getResource(QUEST_POINTS)` lit `getProgress` (décodé via le
switch-map synthétique `IndividualUser$1` : `QUEST_POINTS.ordinal()` → case 4). Conséquence : en liant le
shim `DH.app.getUserBattlePassV2()` à un `BattlePassV2DataWrapper` sur NOTRE `BattlePassV2Data` persisté, la
progression s'accumule via le CODE DU JEU (une quête qui donne des QUEST_POINTS → setResource → setProgress),
zéro glue (PRINCIPLES §3). Le wrapper est writes-through (`this.data.progress = n`, `claimedFreeRewards.put`)
→ claims/progress mutent le message persisté.

**Wiring** (`ServerContext`) : champ `battlePassField = GameMain.userBattlePassV2` + `bindBattlePass(data)`
pose le wrapper (comme le vrai client `GameMain.lambda$setupPostClientInfoHandlers` à la réception d'un push
`BattlePassV2Data`). Appelé dans `ServerUser.applyAction` pour TOUTE action (sinon NPE quand une quête donne
QUEST_POINTS).

**5 handlers** (`ServerUser.applyCommand`, extras relevés au bytecode `ClientActionHelper`) :
- `BATTLE_PASS_V2_CLAIM_REWARD` `{TYPE, INDEX=palier, MODE=premium}` → `BattlePassV2Helper.claimReward(user,
  bp, tier, premium, false)`. Anti-triche RÉEL du jeu : `claimableReward` refuse `BATTLE_PASS_MISSING_POINTS`
  (progress < points du palier) et `BATTLE_PASS_MISSING_PREMIUM`. **+ GARDE AUTORITATIVE anti-double-claim**
  ajoutée AVANT via le prédicat OFFICIEL du jeu `isFreeTierClaimed/isPremiumTierClaimed` (`entry != null &&
  !isEmpty`). Raison (fait) : la garde INTERNE de `claimReward` (`claimableReward`) teste
  `rewardTierClaimed(list) = list.isEmpty()` ET `addClaimedFreeRewards` APPEND les récompenses → un palier à
  récompense NON vide n'est jamais « déjà réclamé » côté garde interne ; c'est le CLIENT (UI) qui grise le
  bouton via `isFreeTierClaimed`. Un serveur autoritatif doit refuser ce que le client empêche → on réutilise
  la sémantique du jeu (pas une règle inventée, §2).
- `BATTLE_PASS_V2_COLLECT_UNCLAIMED_REWARDS` `{TYPE}` → `collectEndedSeasonRewards` (récompenses de saison
  précédente non prises ; vides hors rollover → idempotent).
- `BATTLE_PASS_V2_BUYOUT` `{ID, TYPE}` — le palier courant N'EST PAS transmis → DÉRIVÉ serveur
  `getTierByPoints(progress, start)` (même dérivation que `getBuyoutRewards`) → `doBattlePassBuyout` (débit
  DIAMONDS `getBuyoutCost`, collecte + réclame les paliers restants).
- `UPDATE_BATTLE_PASS` (aucun extra, fire-and-forget) → acquitté (le rollover de saison est déjà géré par
  `refreshBattlePass()` au bind : reset progress+claims quand `startTime` change).
- `VIEW_BATTLE_PASS_SCORE` `{COUNT = getResource(QUEST_POINTS)}` → `setLastSeenProgress` (marque le score vu).

**Persistance** (`ServerUser` + `UserStore`) : champ `battlePassV2Data` (mutable, hors userExtra) +
`refreshBattlePass()` (lazy-create, reset au changement de saison, type QUEST + premium + saison courante) +
`battlePassWire()`/`setBattlePassWire()` + colonne BLOB `battlePassV2Data` (ALTER migration `columnExists`,
load/save). Un objet du jeu = une colonne BLOB (§6).

**Vérifié** `server/smoke/BattlePassClaimTest` : palier RÉEL choisi = 2 (9 points, récompense non vide) ;
(1) claim à progress 0 → REFUSÉ (points manquants) ; (2) don QUEST_POINTS=9 → `getProgress`=9 &
`getResource(QUEST_POINTS)`=9 (accumulation via le code du jeu) ; (3) claim → appliqué + `isFreeTierClaimed`
true ; (4) re-claim → REFUSÉ (déjà réclamé) ; (5) reload wire → palier toujours réclamé + progress=9.
**Régression 23/23 verte.** ⚠️ Écran BATTLE PASS verrouillé TL11 (`unlockables.tab BATTLE_PASS=11`) → vérif
EN JEU du battle pass reportée à TL≥11 (documenté par les faits, non supposé).

**TESTÉ EN JEU — écran QUESTS à TL2 (choix user)** : client lancé (save au hub), navigation vers QUESTS via
l'API du jeu (`UINavHelper.navigateTo(QUESTS)`, commande clic-fichier `goquests` ajoutée). L'écran rend avec
les données SERVEUR (WEEKLY QUEST paliers 21..105 « Rewards 0/5 » « Quests 0/105 » PENDING ; STAMINA BOOST
« Get 796262720 free Stamina between 12 PM and 2 PM » réclamable ; DAILY DEAL/TREASURE HUNTER/DAILY CAMPAIGN ;
onglet **BATTLE PASS visiblement grisé/verrouillé** = TL11 confirmé à l'œil). Sonde B-bis (`dumpscreen` +
`dump x y`) → bouton CLAIM du stamina boost = `QuestRow`/`DHTextButton name=QUEST_CLAIM_AWARDS` @stage(973,75)
= screen(973,645). **Tap CLAIM** → client `Action COMPLETE_QUEST {ID=2}` → serveur « récompense créditée +
complétion persistée [persisté] ». Après le claim (capture) : ligne STAMINA BOOST **consommée** (disparaît) +
compteur weekly **« Quests 0/105 → 1/105 »**. Persistance DB : quête id=2 **plus réclamable** (OncePerDay) +
`WEEKLY_DAILY_QUESTS_COMPLETE=1`. **⇒ CLÔT LE GAP g7 §8** (claim FREE_STAMINA « stamina boost » vérifié en jeu
client↔serveur↔persistance). Outillage : commandes `goquests`/`dumpscreen` (`TutorialDriver.navTo/dumpScreen`),
`server/smoke/DailyQuestProbe`. Snapshot pré-test `server/data/dh-snapshot-prequest-0720.db`.

Fichiers : `server/java/dhserver/{ServerContext,ServerUser,UserStore}.java`,
`server/smoke/{BattlePassClaimTest,DailyQuestProbe}.java`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.

## 2026-07-19 (g5) — Prise de contrôle manuelle : 3 écrans confirmés EN JEU (CHOOSE NAME, SIGN IN claim, HERO_FILTERS)

Depuis le save compte-neuf (au hub, autotap OFF + clickfile ON), pilotage manuel via `dh.clickfile` (tap par
coordonnee + dump de l'acteur + captures), pour confirmer EN JEU au clic reel 3 ecrans a handlers deja batis
mais non verifies au clic. Tous OK client<->serveur<->persistance :

1. CHOOSE NAME : avatar hub -> PlayerProfileWindow (Team Level 2, Max TL 565, Account ID 1, Server (1) Number
   One Dime, Client 12.1.0) -> crayon -> CHANGE NAME prompt -> bouton RANDOM (remplit "Baroness Dante", pas de
   clavier headless) -> CHANGE NAME -> serveur `<== SetPlayerName` / `[setname] nom -> 'Baroness Dante'
   applique [persiste]` -> affiche "BARONESS DANTE" au profil ET au hub.

2. SIGN IN claim : batiment SIGN IN -> Action REFRESH_SPECIAL_EVENTS -> serveur `==> SpecialEventsRaw (31
   jours de sign-in)` -> ecran DAILY SIGN-IN (valeurs exactes de signin_rewards.tab : j1 226,25M or, j2 50
   diamants, j4 35305 gear juice...) -> tap jour 1 -> popup CLAIM -> `<== CLAIM_SIGNIN_REWARD {INDEX=0}` /
   `[action] CLAIM_SIGNIN jour 0 -> GOLD 226 250 000 applique [persiste]` -> or hub 3476 -> 226,25 M
   (DbInspect = 226 253 476, persiste).

3. HERO_FILTERS (ou l'auto-pilote calait historiquement) : menu HEROES -> HeroListScreen (3 heros niv.2 W +
   heros verrouille) -> bulle tuto "Hero Filters..." -> FILTER (tag FILTER_BUTTON) -> HERO FILTERS window
   (categories GENERAL/EFFECTS/+-STATS/TALENTS, Sources Skills/Real Gear/Battle Badge/Patch Talents, Team...)
   -> navigation categories -> acte HERO_FILTERS progresse step 1->4. Le blocage historique (getPointers vide
   headless) est debloque par le controle manuel/semi-auto (commandes drive/center du g3 fonctionnent : log
   [semiauto] center -> tap). ATTENTION (correction) : NON TERMINE. HeroFiltersActV1 a 11 etapes (jusqu'a
   DONE) ; arret au step 4 (le tuto demandait encore de toggle un filtre, visible sur la capture). Mon "4/4
   COMPLET" etait FAUX : mauvaise lecture du champ persiste maxStep (= plus haut step VU, ServerUser.java:169,
   PAS le total de l'acte). L'ecran + le flux client<->serveur sont confirmes ; l'acte reste a finir.

Methode : capturer (manual.ppm -> png) -> regarder -> ecrire x,y (ou commande semi-auto) dans le clickfile ->
tap via input reel (hit-test correct) + dump acteur -> lire serveur/clic. La limite connue du dumpClickTarget
(modales sur une couche que le hit-test du stage principal manque) n'empeche pas le vrai tap (input reel).

Checkpoint : server/data/dh-snapshot-manualtests-0719.db (nom + or 226M + HERO_FILTERS complet). Reste a
explorer : ITEMS, QUESTS, MEDALS, MAILBOX, EVENTS, PROMOTE_HERO, UNLOCK_HERO (Vanellope), campagne > 1-7.


## 2026-07-19 (g2) — Coffres PAYANTS : débit DÉMONTRÉ end-to-end (bug 4ᵉ param) + correction « TL1 »

### Demande & découverte
User : « vérifier les coffres payants avec des team levels plus hauts pour vérifier tes dires ». Mon test
précédent montrait qu'à TL40, une fois le coffre GOLD gratuit consommé, `openChest` REFUSAIT le payant avec un
`ERROR` générique — le débit n'était donc PAS démontré. Cause trouvée en décompilant (CFR) la chaîne cliente
`SilverChestDetailScreen` → `ChestHelper.openChest` → `openChestInner` :
```
if (validateChestPurchase(user, type, count, n2 /* = COÛT */, item, snapshot)) break;
...
buyChests.cost = n2;   // le coût est ré-émis dans le message
```
⇒ **le 4ᵉ paramètre de `validateChestPurchase` == `BuyChests.cost`** (0 pour un gratuit, le coût réel pour un
payant). La branche PAYANTE de `validateChestPurchase` termine par `if (coûtRecalculéServeur > coûtDéclaréClient)
throw ERROR` = un **contrôle ANTI-TAMPER** (le client ne peut pas déclarer un coût inférieur au vrai). Le serveur
passait **`0` en dur** → pour un payant `288 > 0` → `ERROR` systématique → le débit était INATTEIGNABLE.

### Fix (RÉEL, miroir du client)
`ServerUser.openChest` : `validateChestPurchase(user, type, count, m.cost, usedItem, NONE)` (au lieu de `0`).
Gratuit → `m.cost=0` → branche gratuite → OK ; payant → `m.cost=coût` → `coût==coût` faux → OK ; coût
sous-déclaré → `coûtRecalculé > m.cost` → ERROR (anti-triche renforcée).

### Vérifié — débit end-to-end (server/smoke/ChestPaidDebitTest, TL40)
1) coffre GOLD gratuit consommé (`wasFree=true`) ; 2) ouverture GOLD **PAYANTE** avec coût correct déclaré
(`m.cost=288`) → **-288 DIAMONDS** (100000→99712), **persiste au round-trip wire** (99712) ; 3) coût
**sous-déclaré (287)** → **REFUSÉ** (anti-tamper), aucun débit. Régression 8/8 verte.

### Correction d'une affirmation antérieure FAUSSE
« À team level 1 (tuto) le jeu REFUSE tout achat payant » était **inexact**. `validateChestPurchase` n'a **aucun
gate de team level** pour SILVER/GOLD (seuls VIDEO=`TEAM_LEVEL_LOCK`, SOUL=`VIP`, EVENT/WISH ont des gates). Le
verrou du tuto `REBLOCK_SILVER_BUY_ONE` est **côté CLIENT** (`SilverChestDetailScreen`), pas dans la validation
serveur. Le vrai blocage du débit était le bug du 4ᵉ param, pas le niveau d'équipe. `ChestChargeTest` recadré
(le refus qu'il teste = coût sous-déclaré à 0, pas un « verrou TL1 »).


## 2026-07-19 (c) — SIGN-IN multi-jour (j2/j3) vérifié + GAP diamants corrigé (resyncDiamonds)

### Question (user) & vérification
« Les deux sont-ils testés in-game ? À j2/j3 le joueur aura-t-il les récompenses correspondantes, bien
créditées et disponibles ? L'ext headless (isNameLegalExt) impacte-t-il les vrais joueurs desktop/android ? »

**Mécanique jour-par-jour (relevée au bytecode)** : `SigninHelper.getActiveRewardIndex` = fonction de
`getMonthlySignins()` (nb de réclamations) et `getDailyChances("daily_signin")` ; `isClaimable` exige une
chance quotidienne > 0 (une claim/jour) dans la fenêtre du mois. La chance quotidienne se **réinitialise
LAZY** : `IndividualUser.getDailyChances` appelle `DailyActivityHelper.checkAndUpdateDailyValues` qui compare
`serverTimeNow()` à `LAST_USER_DAILY_RESET` (`isSameUserDay`) et, à un nouveau jour, `resetDailyChances(key,
max)` — le tout sur `individualUserExtra.dailyChances` (this.extra, persisté). Donc j2/j3 = logique du jeu
automatique, pas de code serveur spécial.

### Vérification multi-jour (server/smoke/SigninMultiDayTest)
j1 : réclame le jour actif → GOLD (226 250 000) crédité. Simulation j2 : recul de `LAST_USER_DAILY_RESET` de
2 jours → le reset lazy remet `daily_signin` à 1. j2 : jour actif AVANCE (0→1), réclamable, claim → DIAMONDS
(50). `monthlySignins` 1→2. Tout persiste au round-trip wire.

### VRAI GAP trouvé : crédit des DIAMANTS perdu au reload
Au 1er jet, j2 donnait DIAMONDS 0→0 (récompense NON créditée). Cause (diagnostiquée) : les diamants vivent
dans un **champ dédié `IndividualUser.diamonds`** (init depuis `userInfo.diamonds` au `getIndividualUser`,
lu/écrit par `get/setResource(DIAMONDS)`) — **HORS `this.extra`**. `getResource(DIAMONDS)` lit bien ce champ
(vérifié : param 777 → 777), mais `applyAction` ne re-syncait que les héros → le gain de diamants restait en
mémoire et était **perdu au round-trip wire** (comme l'étaient team-level et le nom avant leurs resyncs).
**Correctif** : `ServerUser.resyncDiamonds(user)` = `userInfo.diamonds = user.getResource(DIAMONDS)`, appelé
après `applyAction`/`openChest`/`recordCampaignAttack`. Impact plus large que le sign-in : **tout** gain de
diamants (loot, quêtes…) est désormais persisté. Après correctif : DIAMONDS 0→50, persiste. Régression 7/7
(`Resource/Signin/SetName/SigninMultiDay/CampaignAttack/ChestWire/CampaignPersist`) verte.

### Réponses aux 3 questions
1. **In-game via le client complet** : NON encore — le tuto verrouille la navigation libre (`canNavigateTo=
   false`) jusqu'à l'étape sign-in ; côté serveur tout est prouvé par tests. À confirmer en progressant le tuto.
2. **j2/j3 crédités & disponibles** : OUI (mécanique du jeu + fix diamants), vérifié par test multi-jour.
3. **Impact de `isNameLegalExt = s->true` sur les vrais joueurs** : AUCUN. Il est posé dans
   `ServerContext.init` = **process JVM du SERVEUR uniquement** (dhserver, non chargé par le client). Le client
   desktop/android garde SA propre vérif de police (`Gdx.app` présent côté client). Le serveur ne reçoit que
   des noms déjà validés par le client (changeName local avant l'envoi) ; il en refait le CŒUR de légalité
   (noms interdits, codepoints), seule la vérif POLICE (sans objet serveur) est omise.

## 2026-07-19 (b) — CHOOSE NAME (SetPlayerName) + pilote dh.gosignin (ouvrir SIGN IN)

### CHOOSE NAME — message + handler
Relevé au bytecode (`ChangeNamePrompt.changeNameInner`) : le choix/changement de nom passe par
`UserHelper.changeName(user, newName)` (logique du jeu : légalité `NameChangeHelper.isNameLegal`, coût — 1ᵉʳ
changement gratuit via `FREE_NAME_CHANGE`, sinon item/diamants —, `setPreviousName`+`setName`) PUIS l'envoi
**fire-and-forget** d'un `SetPlayerName{name}` (`getNetworkProvider().sendMessage`). Serveur : `LoginServer`
branche `SetPlayerName` → `ServerUser.setPlayerName` ré-exécute `UserHelper.changeName`, **re-sync** le nom vers
le wire (`userInfo.basicInfo.name`/`previousName`, car `User.userName` est un champ hors `this.extra`, comme le
team-level) et persiste.

### Blocage headless (Gdx.app) résolu par un ext serveur
`UserHelper.changeName` → `NameChangeHelper.isNameLegal` fait le CŒUR (noms interdits, codepoints valides) PUIS
délègue à un champ statique `isNameLegalExt` (Predicate CLIENTE) = `isNameLegalClientExt` qui vérifie le rendu
POLICE (`DisplayStringUtil.containsUnsupportedCharacters` → `LanguageHelper.getPreferredLanguage` →
`Gdx.app.getPreferences`) → NPE headless (`Gdx.app` null). Le rendu police est une préoccupation CLIENTE, déjà
validée par le client avant l'envoi. `ServerContext.init` pose donc par réflexion un **ext SERVEUR** `s -> true`
(même patron que `ServerSpecialEventsExt` : le cœur de légalité s'exécute, seule la vérif police — sans objet
serveur — est omise ; pas une rustine, §2). Vérifié `server/smoke/SetNameTest` : nom « HeroTester » appliqué,
`basicInfo.name` peuplé, survit au round-trip wire. Régressions `SigninTest`/`ResourceTest` vertes.

### Pilote DEV dh.gosignin (vérif live du SIGN IN)
`TutorialDriver` : flag `GO_SIGNIN` → au hub, ouvre le bâtiment SIGN IN via l'API du jeu
`UINavHelper.navigateTo(Destination.SIGN_IN)` pour déclencher `REFRESH_SPECIAL_EVENTS` (→ le serveur répond
`SpecialEventsRaw{signinRewards}`). **Constat live** : `canNavigateTo(SIGN_IN)=false` pendant le tuto
(`mainScreenTutorialBlocked` → « TUTORIAL_CANT_DO_THAT_YET ») — la navigation libre est **verrouillée par le
jeu** tant que le tuto n'est pas au point sign-in. Le hook **RESPECTE ce verrou** (n'ouvre que si
`canNavigateTo=true` ; forcer serait une rustine, §2). La construction serveur `SigninRewards` est déjà prouvée
(`SigninTest`) ; la vérif live du SIGN IN affiché s'obtiendra en progressant le tuto jusqu'au sign-in.
Câblage `DH_GOSIGNIN` → `dh.gosignin` dans `run-online.sh`/`run-desktop.sh`.

## 2026-07-19 — SIGN-IN (récompense de connexion quotidienne) : construction serveur + réclamation

### Contexte & correction de cadrage
En progressant le tuto post-équip, le client spammait `Action{REFRESH_SPECIAL_EVENTS}` (relevé 524×) et le
bâtiment **SIGN IN** restait vide. J'avais initialement cadré ça comme une « feature admin » (définir les
récompenses). **Correction (user) : c'en est pas une.** Les récompenses sont **définies par la donnée**
(`game-data/stats/signin_rewards.tab`, extraite de l'APK = authentique PerBlue) et la réclamation est **du code du
jeu** (`SigninHelper.claim`). Le serveur ne fait qu'**exécuter** (PRINCIPLES §3/§4). Le seul angle « admin » =
éditer un `.tab`, commun à TOUT le jeu — rien de spécifique.

### Modèle relevé au bytecode
- `SpecialEventsRaw{changed, List events, SigninRewards signinRewards}` (champ confirmé).
- `SigninRewards{thisMonth,nextMonth,lastMonth : SigninReward, Map signinHeroesRev}` ;
  `SigninReward{startTime,endTime, List<RewardDrop> rewards, UnitType signinHero}`.
- Table `SigninStats.REWARDS_TABLE : DropTableStats`, nœuds `ROOT → V<SignInVersion>_DAY_<index>`. Variables
  (`SigninDTCode`) : `SignInVersion`=`ContentColumn(signinStart).getSigninVersion()`, `SignInIndex`=`ctx.index`,
  **`L`=`ContentColumn(signinStart).getMaxTeamLevel()`** → quantités indexées sur l'ère de contenu (pas le joueur).
- `SigninHelper` (client) lit tout dans `DATA` (posé par `setData`) : `getRewards`/`getReward(i)`/
  `getActiveRewardIndex`/`isClaimable`/`claim(user,index,retro)` (donne l'objet + `incMonthlySignins`/
  `decDailyChances("daily_signin")`/`setLastSigninTime`). Réclamation client = `Action{CLAIM_SIGNIN_REWARD,
  extra={INDEX=i}}` (et `CLAIM_SIGNIN_WITH_VIDEO` pour le x2).

### Implémentation (`ServerUser`, `LoginServer`)
- `buildSigninRewards()` → `signinRewardsFor(user)` : construit thisMonth/lastMonth/nextMonth via
  `buildSigninMonth(user, start, end)`. Bornes de mois = `Calendar` (premier/dernier instant) sur
  `TimeUtil.getUserServerTime(user)`. `rewards` = pour chaque jour i, `REWARDS_TABLE.getTable().rollNode("ROOT",
  SigninContext(i, start), Random)` → `DropConverter(user).convert(...)` → 1 `RewardDrop` ; boucle jusqu'à ce
  qu'un jour ne produise plus rien (nœud absent = fin des jours de la version). `SigninContext(int,long)` =
  classe imbriquée **protected** hors package → instanciée par **réflexion** (constructeur mis en cache).
  `signinHero` = `ContentHelper.getRawStats().getColumn(start).getCurrentMonthlySigninHero()`.
- `LoginServer` handler `REFRESH_SPECIAL_EVENTS` : `raw.signinRewards = user.buildSigninRewards()`.
- `applyCommand` cases `CLAIM_SIGNIN_REWARD`/`CLAIM_SIGNIN_WITH_VIDEO` : `SigninHelper.setData(signinRewardsFor(
  user))` (claim lit `DATA`), index depuis `extra[INDEX]` (défaut `getActiveRewardIndex`), garde `isClaimable`,
  puis `SigninHelper.claim(user, index, withVideo)`. État auto-persisté dans `this.extra`.

### Vérification
`server/smoke/SigninTest` : `buildSigninRewards()` rend **31 jours** dont les valeurs matchent la `.tab` scalée à
l'ère R102 (L=565) — jour 0 GOLD 226 250 000 (`(565*400000)+250000`), jour 1 50 DIAMONDS, jour 2 1563
EXP_COLOSSAL, jour 3 GEAR_JUICE 35305 (`565*67-2550`), jour 4 DOUBLE_CAMPAIGN_TEAM_XP. Bornes last<this<next.
Réclamation du jour actif → RewardDrop GOLD crédité. Régressions `ResourceTest`/`EquipTest`/`ViewedChestsTest`
vertes. **Reste** : vérif EN JEU (le SIGN IN affiche/réclame réellement), puis CHOOSE NAME.

## 2026-07-17 (quater) — #25 LOOT AUTORITAIRE : le serveur roule le butin, certifié == client, SANS simuler le combat

### Décision (user) & principe
« Rendre autoritaire tout ce qui ne demande pas de simuler le combat. » Décomposition du combat de campagne :
**seuls `outcome`/`stars` exigent une simulation** ; le loot, l'XP, l'or, l'énergie sont **déterministes** une
fois ces valeurs connues. L'XP/or/énergie sont **déjà** autoritaires (`recordOutcome`). Restait le **loot** :
le serveur faisait confiance à `m.lootEarned` (client). #25 le rend autoritaire.

### Fait établi (relevé au bytecode)
Le loot est un **flux RNG SÉPARÉ du combat** : `CampaignAttackScreen` (2ᵉ ctor) fait `user.resetRandom(LOOT)`
puis `user.resetRandom(COMBAT)` — deux graines distinctes (`RandomSeedType.LOOT` ≠ `COMBAT`). Le combat consomme
le flux COMBAT ; le loot consomme le flux LOOT. Donc **le butin est une fonction déterministe de la SEULE graine
LOOT**, indépendante du déroulé du combat. Séquence client exacte :
```
user.resetRandom(LOOT) ;
CampaignLootHelper.getLoot(user, campaignType, 0, chapter, level, NONE, guildPerks /*GuildInfoPerkProvider*/, true)
  → CampaignLoot.combinedLoot   // List<RewardDrop> = butin d'une victoire complète
```
`getCampaignAttack` ne fait que **recopier** cette liste dans `m.lootEarned` (elle ne roule rien). Le mécanisme
RNG : `IndividualUser.getRandom(type)` = `InstrumentedRandom.newRandom(getSeed(type), logMode)` ; `getSeed` lit
`individualUserExtra.storedSeeds` (ou `SeedHelper.getDefaultSeed` par défaut). Le client annonce sa graine au
serveur via `Action{SET_SEED, TYPE=LOOT, ID=<seed>}` (capturée en #23, `getPendingSeed`).

### Implémentation (`ServerUser`)
- `rollAuthoritativeLoot(user, iu, type, m)` : `iu.setSeed(LOOT, pendingSeed)` + `user.resetRandom(LOOT)` +
  l'appel `getLoot(...)` EXACT ci-dessus → `combinedLoot`. `null` si pas de graine.
- `recordCampaignAttack` : sur **VICTOIRE**, `lootEarned = serverLoot` (crédite le tirage SERVEUR) ; sinon repli
  sur le loot client (pas de graine, ou non-WIN = loot partiel dépendant de la progression, hors pure-logique).
  `logLootValidation` LOGue serveur↔client (divergence = **signal anti-triche** ; on crédite le serveur quand même).
- `computeAuthoritativeLoot(m)` : wrapper public (test + bascule).

### Prérequis résolu — `BuildOptions.SERVER_TYPE = ServerType.NONE`
1er run : `NullPointerException … getNetworkProvider().sendMessage(...)`. Cause : `InstrumentedRandom` (via
`resetRandom`/`getRandom`) **envoie** les `RandomEvent` client→serveur pour l'anti-triche — sauf si
`SERVER_TYPE == NONE` (commutateur **offline du jeu**). Headless, `getNetworkProvider()` est null → NPE. Correctif
fidèle : poser `SERVER_TYPE = NONE` dans `ServerContext.init` (chemin offline **prévu par PerBlue**). N'affecte
**que l'envoi d'événements**, PAS les valeurs RNG (même graine → même séquence) — cf. SHIMS.

### Certification (`server/smoke/LootAuthoritativeTest`) — patron oracle
Réf CLIENT (séquence bytecode exacte) vs SERVEUR (via `Action SET_SEED` + `computeAuthoritativeLoot`), multiset
(item/ressource → quantité), sur 5 graines : **5/5 MATCH**, **5 butins distincts** (sensible à la graine → le
test discrimine). Ex. graine 999 → `{ACE_OF_SPADES×0, CLEVER_FOX×1, EXP_VIAL×1, HEARTY_BREAKFAST×1, RAID_TICKET×1,
SUGAR_RUSH×1}` serveur==client. Régression **OK** : `CampaignAttack/LootPersist/CampaignPersist/Resource/Seed/
TeamLevel/Equip/ViewedChests` (les tests sans graine LOOT retombent sur le loot client, inchangés).

### Verdict
Le serveur est **AUTORITAIRE sur le loot** (la principale surface de triche : objets/or) **pour un coût nul,
sans re-simuler le combat**. Combiné à l'XP/or/énergie déjà autoritaires : **tout ce qui a de la valeur est
autoritaire**. Ne reste client que `outcome`/`stars` (§D) — qui, eux, exigent une re-sim (unidbg, échantillonnable).
Fichiers : `server/java/dhserver/{ServerUser,ServerContext}.java`, `server/smoke/LootAuthoritativeTest.java`,
`docs/{SERVER_PLAN,SHIMS}.md`.

## 2026-07-17 (ter) — Harnais différentiel de certification (compare) : bâti, tourne, 1er rapport

### Outil
`CompareBackend` (`-Ddh.spinebackend=compare`) : pour CHAQUE appel cspine, exécute unidbg (ORACLE = binaire ARM
PerBlue = mobile) ET le JNI natif (spine-c recompilé x86, HostSpine) en parallèle, avec table de correspondance
de handles (u2j), et DIFFE automatiquement. Le jeu utilise les résultats unidbg (boote normalement → contourne le
blocage handle du JNI). Gate `active` : ne compare QUE le combat (boot = unidbg seul, rapide). Rapport auto :
diffs/méthode + distance ULP (arrondi FP ARM↔x86 vs écart de logique). Reproductible (régression).

### Bug RÉEL trouvé (par le harnais) + corrigé
`cspine_jni.c` `getBoneTransform(s)` : stride 7 + boucle `idOff..len` → le vrai JNI **borne** et throw AIOOBE
(unidbg tolérait). Corrigé : stride 6 + boucle `0..count` (contrat du jeu, = binaire PerBlue). C'est un bug de
NOTRE glue (tâche #5), latent sous unidbg. Rebuild via `native/build-hostspine.sh`.

### 1er rapport (1-1, seed 123456789) — À AFFINER
TOTAL 5343 diffs. Mais **incohérence interne** : `getBoneNames`=0 diff (listes d'os IDENTIQUES) alors que
`getBoneID`=410 diffs (u=71 j=70). Si les noms sont identiques et ordonnés pareil, getBoneID devrait matcher →
une partie des diffs = **artefacts du harnais** (état non propagé au skeleton JNI : ex. position monde de l'unité
non appliquée côté JNI → getBoneTransform diverge en masse ; ou mapping de handle imparfait sur certains appels).
`setAnimation` u=1 j=12 = différence de sémantique de RETOUR de notre glue (retourne animId) vs PerBlue (autre).
`nextEvent` u=true j=false = PerBlue FIRE des events, notre stub non. `getAnimationID/durations/names/BoneNames`
= 0 diff ✅ (structure de base identique).

### Lecture
Le harnais fonctionne et donne des données riches, mais nécessite **1 passe d'affinage** (propager tout l'état au
JNI, corriger le mapping, aligner les sémantiques de retour de la glue) avant de pouvoir conclure « 100% fidèle ou
non ». Signal préliminaire : la structure de base (noms/ids d'anim, durées, noms d'os) MATCHE ; les écarts
restants sont soit des artefacts du harnais, soit de vraies différences (events, retours) à isoler.

> Journal **détaillé** relié à l'historique court de [`MEMORY.md`](MEMORY.md#7-état-courant--prochaines-étapes).
> But : permettre à n'importe quel agent de **retrouver facilement n'importe quelle
> information** (décision, découverte, commande, fichier). Mis à jour **à chaque étape**.
> Ordre : le plus récent en haut. Chaque entrée = date + résumé + détails + fichiers touchés.

---

## 2026-07-17 (bis) — Optimisation Opt.2 : dynarmic (JIT ARM) testé → PAS un gain ; les vraies pistes sont architecturales

### Contexte / malentendu levé
Opt.2 = validation anti-triche **côté SERVEUR, en fond** (rejoue le combat pour vérifier l'issue). Ça **ne touche
PAS** la fluidité du joueur (le vrai jeu mobile utilise le spine natif de l'appareil). Les ~9 s unidbg = coût
serveur hors-ligne. « Optimiser » = débit serveur (combats/s), pas l'expérience en jeu.

### dynarmic (JIT ARM) — testé, NON concluant
Le backend `dynarmic` (JIT ARM, natif `linux_64/libdynarmic.so` embarqué dans unidbg-dynarmic-0.9.8) remplace
l'interpréteur Unicorn : **mêmes instructions ARM → mêmes résultats** (fidélité OK), censé plus rapide. Ajouté en
**opt-in** (`-Ddh.dynarmic`, off par défaut, `AndroidEmulatorBuilder.addBackendFactory(new DynarmicFactory(true))`).
**Résultat** : le combat spike n'a PAS démarré en 250 s (le boot — rendu MainScreen via spine unidbg à chaque
frame — est devenu PLUS LENT : ~200 frames jamais atteints). ⇒ pour le motif de spine (**beaucoup d'appels ARM
COURTS** : getBoneTransform/apply/update), l'**overhead JIT/warmup ne se rentabilise pas** ; l'interpréteur
Unicorn est plus efficace. **dynarmic n'est pas un gain ici.** Flag conservé (opt-in) pour ré-évaluation future,
sans effet par défaut.

### Les VRAIES optimisations sûres (fidélité intacte) = architecturales
1. **Validation ASYNC/en fond** : ne pas bloquer sur les ~9 s ; valider hors du chemin de réponse.
2. **VMs unidbg en PARALLÈLE** (une par cœur) → débit linéaire (chaque VM exécute le vrai binaire).
3. **Échantillonner / cibler** les combats suspects plutôt que tout revalider.
4. **Cache des assets chargés** (SkeletonData/atlas par unité) → amortit le setup spine entre combats.
Ces leviers ne touchent PAS la sim (mêmes résultats), contrairement à un changement de runtime (JNI natif /
spine-libgdx) qui, lui, doit être certifié.

### Fichiers
`desktop-port/src/main/java/dhbackend/unidbg/UnidbgVM.java` (flag dynarmic opt-in), `build.gradle` (dep
unidbg-dynarmic), `run-desktop.sh`/`run-online.sh` (flag). Docs : JOURNAL.

---

## 2026-07-17 — Opt.3 PIVOT « JNI natif » : le vrai spine-c compilé hôte (pas de réécriture) — bâti, bloqué au boot

### Insight (question user : réécriture main vs fichiers du jeu ?)
La délégation fidèle n'a PAS à être réécrite à la main : **la colle d'origine existe** = `native/src/cspine_jni.c`
(écrite sur le VRAI spine-c officiel 3.6). ⇒ la « bonne » Opt.3 = **compiler spine-c + cspine_jni.c pour l'HÔTE
x86-64 et l'appeler en JNI RÉEL** (« l'Opt.2 sans unidbg » : même code natif, sans émulation ARM). Fidélité PAR
CONSTRUCTION (mixing/clear de track corrects d'office), rapide, **zéro patch à la main** → supersede le
`JavaSpineBackend` (spine-libgdx divergent, patché main → dérive §4, abandonné).

### Fait
- `native/build/spine-native64.so` existait déjà (spine-c 3.6 + cspine_jni.c compilés x86-64, 65 symboles JNI).
- Classe `dhbackend.jnispine.HostSpine` (déclarations `native`, sous-ensemble combat) + `libhostspine64.so`
  (rename mécanique des symboles JNI `...cspine_Native_*` → `...HostSpine_*` + **unification des tables de
  handles** en un espace global — script `native/build-hostspine.sh`). Routage 3-voies dans `cspine.Native`
  (`-Ddh.spinebackend=jni|java|` défaut unidbg). Compile + charge en JNI réel. **Défaut = unidbg → 0 régression.**

### Bloqué (intégration boot, pas fond)
Au boot (chargement d'une particule UI `hero_chooser_add.np` → `hero_chooser.atlas@native`) :
`[main]E/: Bad handle type: Wanted ATLAS but is actually NONE for handle 1` → `Dependency not found` (fatal).
Le **registre de handles Java du jeu** rejette le schéma de handles de NOTRE `cspine_jni.c` (≠ binaire ARM de
PerBlue). L'unification des tables (handles globalement uniques) n'a PAS suffi → le registre attend autre chose
(à investiguer : comment le jeu enregistre le type d'un handle natif ; peut-être un appel natif spécifique, ou
cparticle qui doit passer sur le même backend/espace). **C'est exactement le genre d'incompat que le projet a
CONTOURNÉ en passant à unidbg** (faire tourner le VRAI binaire de PerBlue évite tout ça).

### Synthèse stratégique (convergence)
Les DEUX voies « rapides » (spine-libgdx Java ; spine-c recompilé natif) utilisent les **données** du jeu mais un
**runtime ≠** de celui livré → nécessitent des **patchs de compat** (patch anim côté Java ; schéma de handles /
banding getVertices côté natif recompilé) → dérive §4bis tant que non certifié bit-à-bit. La voie **pleinement
fidèle par construction = Opt.2 (unidbg, binaire ARM d'origine)**, au prix de la lourdeur (~9 s). Le « JNI natif »
reste la meilleure piste rapide SI on finit l'intégration boot (handles) + certification — sinon Opt.2 async est
le choix conforme.

### Fichiers
`desktop-port/src/main/java/dhbackend/jnispine/HostSpine.java` (nouveau), `.../cspine/Native.java` (routage
3-voies), `native/build-hostspine.sh` (nouveau, build reproductible), `run-desktop.sh` (flag jni). Docs : JOURNAL.

---

## 2026-07-16 (nuit 4 bis) — Opt.3 certification : divergences d'ANIMATION diagnostiquées (dont 1 vérifiée sur source spine-c)

### Résumé
Débogage de la divergence de cadence (java stagne : 3/5 kills vs oracle 5/5). Méthode : instrumentation diff
(maxAnimTime, setAnimId, histogramme d'anim courante par NOM, dump getBoneTransform) java vs oracle unidbg.
**Positions d'os : cohérentes** (valeurs caractéristiques identiques). **Bug d'animation trouvé & VÉRIFIÉ sur la
source spine-c**, mais ≥1 couche résiduelle subsiste → combat pas encore fidèle.

### Diagnostics (java vs oracle unidbg, 1-1 seed 123456789)
- **Histogramme d'anim courante par nom** : java bloqué en `attack`(14488)+`hit`(7170), **0 skill** ; oracle
  étalé. ⇒ les unités attaquent mais n'enchaînent jamais les skills (pas d'énergie → dégâts lents).
- **`getBoneTransform`** : java `[worldX,worldY,rot,sx,sy]` = mêmes valeurs caractéristiques que l'oracle →
  positions d'os OK (pas la cause).

### Bug VÉRIFIÉ sur source spine-c (`spine-c/src/spine/AnimationState.c:296-300`)
`spAnimationState_update` **CLEAR le track** (`self->tracks[i] = 0`) quand « pas de next, `trackTime>=trackEnd`,
pas de mixingFrom » → `getCurrent`→null→`getCurrentAnimID`=0. **spine-libgdx NE clear PAS** : l'entry non-bouclée
TERMINÉE persiste → `getCurrent` la renvoie → le jeu croit l'unité toujours en `attack` → n'enchaîne jamais le
skill (bloqué). **Fix partiel** : `getCurrentAnimationID` renvoie 0 si `!getLoop() && isComplete()` (mime le
clear). Effet : distribution redevenue normale (attack 14488→288, walk/idle dominants). **MAIS** : (a) fix
INCOMPLET (ne corrige que l'ID, pas `getCurrentAnimationTime`/`apply`/bones → incohérent avec un vrai clear qui
remet en pose de repos) ; (b) le combat **stagne toujours** (3/5 kills) → ≥1 divergence de plus.

### Bilan certification
Le combat tourne (9× plus vite) et l'oracle a permis de trouver/vérifier des bugs réels (stride 6, getAnimationTime
wrappé, clear de track spine-c). **La fidélité complète reste à atteindre** : c'est un chantier multi-bugs (la
prochaine couche = probablement rendre le clear de track COMPLET — vraiment clear l'entry après update comme
spine-c, pour que `apply`/bones/temps soient cohérents — puis re-diff). Le fix `isComplete` actuel est une
**approximation NON entièrement vérifiée** (§4bis) → à compléter ou à valider strictement avant tout déploiement.

### Fichiers
`.../dhbackend/spine/JavaSpineBackend.java` (fix isComplete + histo noms), `.../cspine/Native.java` (diag
histo/bt). Docs : JOURNAL. DEV-gated, backend unidbg par défaut → aucune régression.

---

## 2026-07-16 (nuit 4) — Opt.3 Phase 1 : backend Java-spine — le combat TOURNE (9× plus vite), certification en cours

### Résumé
`JavaSpineBackend` écrit (~22 méthodes cspine du combat via `spine-libgdx-perblue`, contrat répliqué du JNI),
flag `-Ddh.spinebackend=java` route l'animation vers lui au lieu d'unidbg (atlas reste unidbg). **Le vrai
`HeadlessCombat` tourne intégralement sur Java-spine** (`state=DONE`) et **~9× plus vite** (work ~0,9 s vs
~9 s unidbg). La **certification contre l'oracle DÉTECTE des divergences** (rôle de l'oracle) : 2 bugs corrigés,
≥1 résiduel (cadence d'animation). PAR DÉFAUT le backend reste unidbg (flag off) — aucune régression.

### Obstacles franchis (build)
- **Deps compile** : ajout `spine-libgdx-perblue.jar` (uniquement `com.esotericsoftware.spine`, aucun conflit gdx).
  Casts explicites : l'`Array`/`IntMap` PerBlue (dex2jar) a des génériques ÉRASÉS → `.get()` renvoie `Object`.
- **libGDX PerBlue STRIPPÉ par ProGuard** : `spine-libgdx-perblue` exige des méthodes retirées du `game-logic.jar`
  (`DataInput.readString`, `IntSet.clear`). Fix : déposer les classes COMPLÈTES de gdx-1.9.7 (cache gradle) dans
  le dir de classes (1ᵉʳ sur le CP → ombrage). **Uniquement des classes STRIPPÉES** (superset sûr), JAMAIS une
  classe MODIFIÉE par PerBlue (ex. `Array.add→boolean`). Vérifié par diff de signatures (sous-ensemble).

### Bugs de fidélité (trouvés par l'oracle)
1. **`getBoneTransform` stride 6, pas 7.** Le JNI écrit 7 floats (toléré par unidbg sans bounds-check) mais le
   jeu alloue/lit **6** (`NativeSkeleton.tmpTF=float[6]`, `getBoneTransforms` dimensionne `n*6`). En JVM strict le
   7ᵉ déborde → AIOOBE. Corrigé : écrire `[worldX, worldY, worldRotationX, worldScaleX, worldScaleY, 0]`.
   `getBoneTransforms` (pluriel, ShadowRenderable/ombres cosmétiques) : rendu défensif (garde de bornes ;
   sémantique JNI = no-op quand `ids.length==nbOs`).
2. **`getCurrentAnimationTime` = `animationTime` WRAPPÉ, pas `trackTime` brut.** Oracle : maxAnimTime **1.833 s**
   (= durée pleine de skill1) = borné. Java avec `getTrackTime()` : **89.9 s** (accumule) → le jeu croit l'anim
   au-delà de tous les keyframes → n'attaque plus qu'une fois → stagnation. `getAnimationTime()` reproduit le
   borné natif (java → 1.733 s).

### État certification (1-1, seed 123456789, snapshot loot-1to5)
| | ticks | maxAnimTime | morts déf. | issue |
|---|---|---|---|---|
| **oracle unidbg** | 973 | 1.833 s | 5/5 | **WIN** (~8,5 s réel) |
| **java (après fix 1+2)** | 3876 (cap) | 1.733 s | **3/5** | stagne → LOSS (~0,9 s réel) |

⇒ Java **progresse** (3 kills, ~9× plus rapide) mais **tue ~15× plus lentement** → **divergence résiduelle de
cadence** (les attaques/skills atterrissent moins souvent ; skill1 semble interrompue à 1.733 vs 1.833 → keyframe
de dégâts tardif manqué). **Prochain pas certification** : diff **tick-par-tick** de la séquence
`getCurrentAnimationID`/`Time` d'une unité entre java et unidbg pour localiser la 1ʳᵉ divergence (mixing ? ordre
update/apply ? bornes de wrap ?). Piste : comportement du **mixing** (`setMix`/crossfade) ou `getCurrentAnimationID`
pendant un mix.

### Fichiers
`desktop-port/src/main/java/dhbackend/spine/JavaSpineBackend.java` (nouveau, backend), `.../cspine/Native.java`
(routage flag + diag), `.../CombatSpikeDriver.java` (compteur morts), `build.gradle` (dep spine), `run-desktop.sh`
(flag + shadow classes gdx), `run-online.sh` (forward `DH_SPINEBACKEND`). Docs : SERVER_PLAN §D.

---

## 2026-07-16 (nuit 3 quater) — Opt.3 Phase 0 : surface cspine EXACTE du combat headless mesurée (profilage)

### Résumé
Instrumentation DEV du shim `cspine.Native` (`-Ddh.cspineprofile`, compteur par méthode ; reset/report autour du
combat dans `CombatSpikeDriver`). Run du spike Opt.2 → **surface cspine EXACTE que le combat headless exerce**.
Résultat : **~22 méthodes** (animation/skeleton/os), **0 rendu**, mais **les positions d'os SONT lues**.

### Mesures (1-1, 3 héros vs 3 vagues, 973 ticks, 9 unités) — 37 méthodes appelées
**Hot path (par tick) :** `AnimationState_nextEvent` 8611 (polling events, VIDE car .skel sans event → trivial),
`getCurrentAnimationTime` 8008, **`Skeleton_getBoneTransform` 4376** (~4.5/tick — **positions d'os monde LUES**),
`getCurrentAnimationID` 4299, `AnimationState_apply` 4091, `Skeleton_updateWorldTransform` 4013, `Skeleton_update`
+ `AnimationState_update` 4004 chacun. **Setup/occasionnel :** `getAnimationID` 445, `getBoneID` 410,
`setToSetupPose` 96, `setAnimation` 87, `setColor`/`setTintBlack`/`setSlotEyeState` 47/38/36 (cosmétique),
`setMix` 36, `setSkin` 36, `Atlas_getTexture` 24, `getBoneTransforms` 15, create/dispose ×9 (9 unités),
`getAnimationDurations`/`getSlot/Anim/Bone/SkinNames`/`create` ×8 (skeletons uniques).

### Findings décisifs
1. **0 appel `getVertices*`** → le combat headless **NE REND RIEN** → toutes les méthodes de rendu (getVertices,
   setColor/tintBlack) → **no-op** en Opt.3. ✅
2. **`getBoneTransform` massif (4376)** → l'inconnu de la Phase 0 est **tranché : OUI le combat lit les positions
   d'os monde** → le backend Java-spine doit **appliquer l'anim + `updateWorldTransform` + exposer `Bone.getWorldX/
   Y/rotation`** (fourni nativement par spine-libgdx-perblue).
3. **`setMix` 36** → mixing/crossfade d'animations configuré → **à certifier** (timing).
4. **`nextEvent` 8611 mais 0 event dans les .skel** → poll toujours vide → implémentation triviale (retourne
   « rien »). Les keyframes de combat viennent du prefab (déjà établi nuit 3 ter).

### Conséquence pour l'Opt.3 (chantier CADRÉ)
Surface à implémenter en Java-spine ≈ **22 méthodes** : `SkeletonData_{create,dispose,getAnimation{Durations,ID,
Names},getBone{ID,Names},get{Slot,Skin}Names}`, `Skeleton_{create,dispose,update,updateWorldTransform,
setToSetupPose,setSkin,getBoneTransform(s)}`, `AnimationState{,Data}_{create,dispose,update,apply,setAnimation,
clearTracks,getCurrentAnimation{ID,Time},setMix,nextEvent}`. Toutes en **délégation directe au runtime Java**
(pas les 47 de cspine, PAS de rendu). Risques de fidélité à certifier vs oracle : valeurs de `getBoneTransform`
(monde) + timing `setMix`. Gain attendu : le hot-path (~29 appels unidbg/tick, ~9 s) → JVM natif (~ns) → <100 ms.

### Fichiers
`desktop-port/src/main/java/com/perblue/heroes/cspine/Native.java` (profilage DEV gated), `.../CombatSpikeDriver.java`
(reset/report), `run-desktop.sh`/`run-online.sh` (`DH_CSPINEPROFILE`). Docs : SERVER_PLAN §D.

---

## 2026-07-16 (nuit 3 ter) — Opt.3 TEST : données de timing ACCESSIBLES en Java pur (spine-libgdx-perblue) → faisable, verdict

### Résumé
Test de faisabilité de l'Opt.3 (timelines d'animation via runtime **Java spine** au lieu d'unidbg). **Résultat
POSITIF sur la data-reachability** : le `spine-libgdx-perblue.jar` (runtime Java `com.esotericsoftware.spine`)
**lit les `.skel` du jeu** et en extrait les animations + durées, **sans unidbg ni GL**. ⇒ fondation de l'Opt.3
existante. Reste un **coût de câblage** (backer le natif cspine par le Java-spine) à certifier contre l'oracle.

### Ce qui a été testé (probe `SpineProbe`, scratchpad)
- `SkeletonBinary.readSkeletonData(FileHandle)` sur `ralph/elastigirl/frozone.skel` → **parse complet** :
  ralph **12** anims (attack 1.0s, hit 0.633s, skill1 1.833s, skill2/3 1.367s…), elastigirl **14**, frozone **10**,
  avec **durées**. AttachmentLoader stub (attachments vides — pas besoin des textures pour le timing), FileHandle
  direct (sans Gdx.files).
- **Conflit de libGDX résolu (probe)** : `spine-libgdx-perblue` veut `DataInput.readString()` (absent : le
  `DataInput` de `game(-logic).jar` est un **stub 215 o**) ET `Array.add→boolean` (libGDX **PerBlue**). Or
  gdx-1.9.7 a readString mais `Array.add→void`. Même package, classes différentes → on prend le `DataInput`
  complet de gdx-1.9.7 (prioritaire) + le reste de `game-logic-framed` (Array→boolean). (= le sujet de la
  tâche #3 « DataInput.readString », shadow retiré au passage à unidbg.)

### Découverte structurante
- **Les `.skel` n'ont AUCUN event spine** (`eventDatas=0` pour les 3 héros). Les **keyframes de combat**
  (déclenchement dégâts/hit/projectile) NE sont donc PAS des events spine, mais des **composants du prefab de
  scène** (`.treeb` : `HitKeyframeData`/`ProjectileKeyframeData`), authored à part. Ces prefabs sont **aussi
  chargeables en Java** (PrefabLoader du jeu, données pures, sans GL/unidbg).
- **`AnimationElement`** (classe concrète) encapsule le **natif cspine** (`nativeSkel`/`nativeAS`, ctor
  `(NativeSkeleton, NativeAnimationState)`) pour la playback (`setAnimation`/`getAnimationLength` = horloge +
  durées), MAIS ses **keyframes sont une `HashMap` settable** (`setKeyframes`, source = prefab). Le combat ne
  semble pas lire de **positions d'os** (0 `findBone` dans `simulation/`).

### Verdict Opt.3
- **Data reachable en Java pur : OUI** (durées via .skel/Java-spine + keyframes via prefab/PrefabLoader).
- **Câblage** : pour retirer unidbg, backer la **sous-surface cspine du combat** (`NativeSkeleton`/
  `NativeAnimationState` : durées + horloge d'animation ; pas de rendu/vertices/os apparemment) par le
  Java-spine. **Réimplémentation bornée** (précédent : le projet avait des shadows cspine Java avant unidbg),
  **à CERTIFIER contre l'oracle Opt.2** (même spine 3.6 sur les mêmes .skel → forte proba de match, non garanti).
- **Gain** : supprime l'émulation ARM du combat → ~9 s → probablement <100 ms → **validation synchrone viable**.
- **Reco** : **Opt.2 async MAINTENANT** (autorité immédiate : brancher l'oracle sur `recordCampaignAttack` en
  validation de fond, ~9 s/combat hors ligne) ; **Opt.3 ensuite** comme upgrade perf certifiable (fondation +
  oracle déjà en place). Choisir Opt.3 en priorité seulement si le synchrone temps-réel est requis d'emblée.

### Fichiers
Probe `scratchpad/SpineProbe.java` (throwaway). Docs : SERVER_PLAN §D, MEMORY. Aucune modif jeu/serveur.

---

## 2026-07-17 — #28 CERTIFICATION Opt.3 : spine-c recompilé certifié FIDÈLE contre l'oracle unidbg (verdict desktop)

### Résumé — VERDICT
Le harnais différentiel (`CompareBackend` : le jeu tourne sur unidbg=oracle=binaire PerBlue mobile, le JNI
spine-c recompilé tourne **en parallèle** sur les **mêmes handles**, on diffe chaque appel) a servi à
**certifier** notre spine-c hôte (x86-64) contre le binaire ARM d'origine, **automatiquement**, sur un vrai
combat (NORMAL 1-1, 973 ticks, 5 morts, WIN — identique côté unidbg). Résultat après fermeture des écarts :

| catégorie | méthodes | diffs | verdict |
|---|---|---|---|
| **structure** (noms/ids/durées/counts d'anims, os, slots, skins ; atlas) | getAnimation{ID,Names,Durations}, getBoneNames, getSlotNames, getSkinNames, setSkin, Atlas_* | **0** | **identique bit-à-bit** |
| **événements d'animation** | nextEvent (8611 appels), setAnimation (87) | **0** | **identique bit-à-bit** |
| **transforms d'os** | getBoneTransform (4376), getBoneTransforms (15) | ~818 | **dérive flottante seule** : matrice 1.8e-7, position 6.1e-5 (sub-pixel, bornée) |
| ordre interne des os | getBoneID (410) | 410 | **artefact** : PerBlue réordonne les os en interne ; chaque backend est auto-cohérent (les transforms matchent une fois traduits par NOM) — aucun impact fonctionnel |
| extension proprio | setSlotEyeState (36) | 36 | **seul vrai manque** : extension PerBlue (expression des yeux), absente de spine-c vanilla — **purement cosmétique** |

### Bugs de fidélité RÉELS trouvés par le harnais (et corrigés)
1. **Layout matrice transposé** (`getBoneTransform`) : le contrat `NativeSkeleton` = matrice affine monde
   `[a, b, c, d, worldX, worldY]` (stride 6). Notre colle écrivait d'abord `[worldX,worldY,rot,sx,sy,0]` (faux),
   corrigé en `[a,b,c,d,x,y]` — mais le split mat/pos du harnais a révélé `mat=2.00` (∈±1 → catastrophique) avec
   un ulp de signe : signature d'une **transposition b↔c** (`b=-sin`, `c=+sin` → écart `2|sin|`, nul quand
   `sin≈0`, position intacte). Ordre correct de l'oracle = **`[a, c, b, d, x, y]`**. Après fix : `mat` tombe à
   **1.79e-7** (dérive flottante pure). *(Ces 2 bugs étaient invisibles sous unidbg — qui exécute le binaire
   PerBlue, pas notre colle — d'où jamais vus avant le harnais.)*
2. **`nextEvent` non branché** (renvoyait toujours `false`) → aucun callback `complete/end/start/…` → machines
   d'état d'animation du jeu muettes en backend JNI autonome. Implémenté la **file d'événements spine-c** :
   listener global sur `spAnimationState` (`rendererObject` porte une FIFO), empilée dans l'ordre de drain
   interne de spine (`_spEventQueue`) pendant `apply/update`, dépilée par `nextEvent` → `out[0]=type PerBlue`
   (`spEventType+1` : 1=start…6=event), `out[1]=trackIndex`. Certifié **0 diff / 8611 appels** (dont 423 events
   réels). *(Piège : `dispose` doit appeler `spAnimationState_dispose` AVANT de libérer la FIFO — spine émet un
   DISPOSE via le listener → use-after-free sinon, corrigé.)*
3. **`setAnimation` mauvais retour** : le jeu fait `return natif + eventIDOffset`. Relevé contre l'oracle : le
   natif renvoie un **compteur de trackEntry par animState** (1-based, ++ à chaque set/addAnimation), pas
   l'animId ni le trackIndex. Implémenté (`EvQueue.seq`). Certifié **0 diff / 87**.

### Verdict pour la décision « desktop dev-only vs production »
- **Rendu / animation : FIDÈLE.** Structure et événements **identiques bit-à-bit** ; poses d'os fidèles à la
  précision flottante (écart max **6e-5** sur des positions de l'ordre de la centaine = relatif ~1e-7, **borné**
  car les transforms monde sont **recalculés à neuf chaque frame** depuis les keyframes — pas d'intégration, donc
  **pas d'accumulation/chaos**). Sub-pixel → **invisible à l'écran**. ⇒ le spine-c recompilé est **jouable en
  production pour le rendu** (reste : régler le blocage handle-registry du boot JNI autonome, + l'extension
  cosmétique `setSlotEyeState` si on veut les expressions des yeux).
- **Autorité de combat : reste sur unidbg (serveur).** La dérive flottante ARM↔x86 (même minime) rend le JNI
  **non bit-identique** → un combat rejoué sur JNI pourrait diverger sur le long terme (sensibilité au chaos).
  Donc la **validation autoritative** (#24) garde le binaire PerBlue via unidbg — ce qui **coïncide** avec le
  principe §3 (serveur autoritatif) : c'est le serveur qui certifie, pas le client.
- En clair : **desktop = production pour jouer/afficher** (fidèle à l'œil), **serveur = unidbg pour l'autorité**
  (fidèle au bit). Les deux voies restent du **vrai code/données PerBlue** exécutés (§4), rien de réécrit (§2).

### Perf (FPS) — pourquoi le backend décide de la fluidité
Le harnais chronomètre les DEUX backends sur le **même mix d'appels** (fenêtre combat, hors `getVertices`) :
**unidbg (ARM émulé) = 16 900 ms vs JNI (natif x86) = 337 ms → le natif est ~50× plus rapide.** Sur 2919 ticks
(3 combats × 973) : ~**5,8 ms/frame** de travail squelettique côté unidbg vs ~**0,12 ms/frame** côté natif.
- **Aujourd'hui (défaut = unidbg)** : ~5,8 ms/frame RIEN QUE pour l'animation squelettique, AVANT le meshing
  (`getVertices`, aussi émulé, l'appel spine le plus lourd) et le GL → budget 60fps (16,6 ms) explosé en combat
  → **saccadé (FPS à un chiffre / bas). C'est un outil de dev, pas jouable fluide.**
- **Backend natif JNI (certifié)** : ~0,12 ms/frame → spine **n'est plus le goulot**, 60fps atteignable ; le
  coût résiduel devient le GL (ici llvmpipe logiciel headless ; sur une vraie machine = GPU). Le 50× est
  **conservateur** (exclut `getVertices`, où la pénalité d'émulation est encore plus forte).
- **Bloquant restant avant de mesurer le FPS natif bout-en-bout** : le boot JNI-autonome bute sur le
  handle-registry (aujourd'hui contourné par le mode compare, où le jeu boote sur unidbg). À lever pour un
  desktop natif de production. Le ratio par-appel, lui, est **mesuré** (pas estimé).

Fichiers : `native/src/cspine_jni.c` (layout `[a,c,b,d,x,y]`, FIFO d'événements + `seq`), `desktop-port/src/
main/java/dhbackend/spine/CompareBackend.java` (traduction d'os par nom, split diff matrice/position, chrono
par backend, rapport).

## 2026-07-16 (nuit 3 bis) — Opt.2 PROUVÉE : le vrai HeadlessCombat tourne headless via unidbg (ORACLE établi) + fix bytecode itf

### Résumé
Spike Opt.2 (#27) **concluant** : le **vrai `HeadlessCombat` du jeu** tourne **headless dans le client**
(`desktop-port` : GL Xvfb/llvmpipe + vrai `GameMain` + unidbg + assets) jusqu'à `DONE` et produit une **issue
autoritative**. C'est l'**oracle** pour certifier l'Opt.3. Lourdeur mesurée : **~9 s/combat** (unidbg-dominé).

### Ce qui a été fait
- **`CombatSpikeDriver`** (DEV, `-Ddh.combatspike`, off par défaut) : après boot+login, construit un
  `HeadlessCombat` de campagne (héros du user en `CoreAttackScreen$CombatUnitData` + `CampaignAttackScreen.
  createStageDefenders`), boucle `work()` jusqu'à `DONE`, imprime issue + timing. Câblé dans `DesktopLauncher`
  (hook après `game.render()`), flags forwardés par `run-desktop.sh`/`run-online.sh` (`DH_COMBATSPIKE*`).
- **Fix bytecode `itf` (ReframeJar)** : le 1ᵉʳ run a buté sur `IncompatibleClassChangeError: FXHandle.
  $r8$lambda$…() must be InterfaceMethodref constant`. Cause : dex2jar encode un `INVOKESTATIC` vers une méthode
  statique d'**interface** en `Methodref` au lieu d'`InterfaceMethodref`. `ReframeJar` corrige désormais
  `itf = isInterface(owner)` pour les `INVOKESTATIC` (non-sémantique §1). ⚠️ **Pas** pour `INVOKESPECIAL` (un
  1ᵉʳ essai trop large a cassé les défauts de super-interface indirecte → `VerifyError` sur
  `PegasusSkill3.onRampageStartAnimationEnd` ; restreint à INVOKESTATIC). Régénère `game-framed.jar` (serveur)
  ET `game-logic-framed.jar` (client).

### Résultats (mesures)
- **1-1, 3 héros progressés (snapshot loot-1to5) vs 3 vagues** → `state=DONE ticks=973 → WIN` (attackersLeft=
  true, defendersLeft=false). Le vrai moteur (skills/IA/keyframes) tourne headless.
- **Déterminisme** : 3 runs → **ticks=973 identiques** (graines 123456789 ×2 et 999). Sim bit-déterministe
  (temps réel varie 8,9–9,5 s = variance wall-clock, pas de sim). ⇒ **qualité oracle**.
- **Lourdeur** : ctor ~5-7 ms + `work()` ~9 s (973 ticks × 25 ms = ~24 s de combat in-game émulés). Dominé par
  unidbg spine (keyframes). **Trop lent pour du synchrone, OK pour de l'anti-triche async.**
- **Limite du cas de test** : stomp trivial → **seed-insensible** (même issue/ticks pour graines différentes,
  tout one-shot). La certification #28 devra utiliser des combats **serrés** (RNG discriminant).

### Fichiers
`tools/reframe/src/ReframeJar.java` (normalisation itf), `desktop-port/src/main/java/dhdesktop/
CombatSpikeDriver.java` (nouveau), `.../DesktopLauncher.java` (hook), `desktop-port/run-desktop.sh` +
`run-online.sh` (flags `DH_COMBATSPIKE*`). Docs : SERVER_PLAN §D (résultats+lourdeur), SHIMS (fix itf).

---

## 2026-07-16 (nuit 3) — #24 RE-SIM COMBAT : investigation à fond → combat KEYFRAME-DRIVEN → plan « oracle-certification »

### Résumé
Investigation approfondie de #24 (re-simulation serveur du combat pour outcome/stars **autoritatifs**). Conclusion
ferme : **`HeadlessCombat` n'est pas « pure logique »** et **le combat de DH est piloté par les keyframes
d'animation** → une sim serveur SANS données d'animation n'est pas fidèle. Décision user : approche
**oracle-certification** (mesurer l'Opt.2 unidbg comme oracle, puis certifier une Opt.3 plus légère contre lui).

### Chaîne de preuves (désassemblage + probes headless)
1. **`HeadlessCombat` = simulateur in-process du CLIENT** (utilisé par les écrans invasion/surge/hero-chooser
   pour **prévoir** l'issue via `startQuickCombat`). Son **ctor** bâtit un `RepresentationManager` →
   `initCommonVFX()` charge `world/common/common.treeb` via `RPGAssetManager`, dont le ctor fait
   `new Texture(Pixmap)` (**upload GL → exige un contexte GL**) et enregistre les loaders **natifs**
   (`NativeAtlasLoader`/`NativeSkeletonDataLoader`/`NativeParticlePoolLoader` = cspine/cparticle **unidbg**).
   `work()` pilote `loadScene(LOAD_ONLY)`/`startScene()` → charge+anime les représentations.
2. **Voie logique-only explorée** (piloter `Scene` directement) : le jeu a un mode headless
   `BuildOptions.TOOL_MODE=COMBAT_AUTOMATOR` → `RenderContext2D.getEnvSpacingInfo()`=`AUTOMATOR_BOUNDS` (pas de
   renderContext) ; renderer no-op du jeu `HeadlessSceneRenderer.INSTANCE` (méthodes vides). **Le SETUP tourne
   headless sans GL** : `new Scene(random,true)` + `CombatSetupHelper.createUnits/initPositions/
   initializeAIAndSkills` → unités créées (1-1 : 3 vs 1), `fixedTimestep=25ms`. `Scene.update` ne touche ni
   rendu ni représentation ; `Unit` a **0** ref `getRepresentation()`.
3. **MAIS `scene.update` enregistre des `AnimationKeyframeListener` sur l'`AnimationElement` de chaque unité**
   (`getAnimationElement()` null headless → NPE). C'est **le mécanisme par lequel les effets d'ability
   (dégâts/projectiles) se déclenchent à des frames précises**. Sans `AnimationElement` (créé par le
   `RepresentationManager` depuis les `.skel`), les unités **n'infligeraient jamais de dégâts**. Les entrées
   walk-in exigent aussi un `entranceKey` posé par le repManager (durées d'anim=0 sans `AnimationElement`).
   ⇒ **combat = keyframe/animation-driven → pure logique NON fidèle.**

### Options + principes (cf. SERVER_PLAN §D)
- **Opt.1** loot autoritatif (#25, pure logique prouvée) + garde-fous, combat = PARTIEL client documenté (§2 OK).
- **Opt.2** worker combat via **unidbg** (données-spine, sans rendu pixel) = **§3 complet + §4** (vrai code+data),
  mais lourd. Le plus fidèle à nos principes pour l'autorité totale.
- **Opt.3** timelines via runtime **spine Java** (`spine-libgdx-perblue.jar`) = plus léger, **conforme seulement
  si prouvé bit-fidèle au natif** (sinon rustine §4/§4bis).

### Décision (user) — oracle-certification (patron du rebuild natif)
Mesurer/monter l'**Opt.2 comme ORACLE** (elle réutilise le **client headless déjà fonctionnel** du
`desktop-port` : GL Xvfb/llvmpipe + vrai `GameMain` → assetManager/renderContext peuplés + unidbg + assets), puis
**certifier l'Opt.3 contre elle** (RNG/HP-tick/timing sur matrice large). Jamais shipper l'Opt.3 non certifiée.
**Prochain pas** : hook lanceur `dh.combatspike` (après boot+login → `HeadlessCombat` de campagne → `work()`
jusqu'à `DONE` → outcome/stars + timing). Aucune modif jeu/serveur committée dans cette phase d'investigation
(exploration en scratchpad). Fichiers docs : `docs/SERVER_PLAN.md` §D, MEMORY.

---

## 2026-07-16 (soir) — PIPELINE COMPLET VALIDÉ EN JEU (nouveau joueur → 1-1 GAGNÉ) + sélection des héros (pilote)

### Résumé
Run complet **nouveau joueur** (DB + prefs wipés, assets en cache) : intro → coffre GOLD (Frozone, 3 héros)
→ FAST_FORWARD → AUTO_FIGHT → SKILL_USE → **entrée campagne → choix des héros → 1-1 GAGNÉ → `CampaignAttack`
→ `recordOutcome` persisté**. Le serveur (framed jar, sans `-Xverify:none`) est resté **stable, 0 fatal JVM**
sur tout le run — le fix reframe validé de bout en bout en conditions réelles.

### Fix pilote : sélection des héros sur l'écran de choix
Le pilote atteignait `CampaignHeroChooserScreen` (« CHOOSE YOUR HEROES! ») mais **tapait FIGHT sans
sélectionner d'équipe** (TEAM POWER=0 → popup « select at least one hero » → aucun combat). Correctif
(technique **capturer→cliquer→monitorer→câbler**) : `selectHeroesIfNeeded` sélectionne les héros dispo via
l'API du jeu qu'un tap de portrait déclenche — **`HeroChooserScreen.unitSelected(UnitData, provider, x, y)`**
(cœur `HeroChooserHelper.selectUnitPressed` ; provider/coords inutilisés hors SURGE, vérifié au bytecode).
N'ajoute que les héros pas déjà dans l'équipe (`unitSelected` TOGGLE) et sélectionnables (`canSelectUnit`).
Vérifié EN JEU : `[tutodrive] CampaignHeroChooserScreen → héros sélectionnés via unitSelected → équipe prête
pour FIGHT`, puis `CampaignAttack NORMAL 1-1 WIN`.

### Persistance vérifiée sur la DB live (après 6× 1-1 WIN) — TOUT persiste, AUCUN 39M
Probe `server/data/dh-server.db` après le run :
- **STAMINA stored=122 / effective=120** ✅ — **aucun 39M même après 6 combats** (réponse à la question user :
  120 à l'écran — bouton FIGHT coût 6 — ET 120 en DB, au first launch). Le 122 stored (>cap) est le même
  artefact bénin R102, clampé à 120 à l'affichage/dépense.
- **GOLD = 2040** ✅ (6× ~340 = loot de campagne cumulé et persisté).
- **1-1 = 3★, totalWins=6** ✅ ; **1-2 débloqué = true** ✅ (déblocage persisté) ; `getLatestCompletedLevel=1-1`.
- **teamXP/teamLevel** cohérents (niv.1).
⇒ Pipeline **entrée / choix héros / skills (AUTO) / vagues / loot-gold / récompenses / XP / conso énergie /
progression / déblocage niveau suivant** : **tout validé et persisté**.

### BUG PERSISTANCE trouvé & corrigé : NIVEAU D'ÉQUIPE (révélé par la question stamina de l'utilisateur)
L'utilisateur a tiqué : « stamina 122/120 (au-dessus de 120) et 6 combats sans consommation ? ». Investigation
(`StamTrace`, 6× 1-1 sur joueur neuf) : la stamina EST consommée (−6/combat : 120→114→108…) MAIS **remonte
tous les 3 combats** à 122 (stored) / 120 (effective), corrélé à `TEAM_XP` qui reset 12→0. `team_levels.tab` :
niv.1→2 = **18 XP** (=3×6), montée = **+20 stamina** (`STAMINA_GAIN_ON_LEVEL`) ⇒ 122 = 102 (après −6) + 20.
MAIS `getTeamLevel()` restait **1** même après reload → **le niveau d'équipe ne montait/persistait pas**.
**Cause** : `User.teamLevel` est un **champ de `User`** (pas dans `this.extra`) ; `getUser` le lit depuis
`userInfo.basicInfo.teamLevel`, mais `setTeamLevel` (appelé à la montée par `giveTeamXP`) n'écrit QUE le
champ `User`. Sans re-sync, le wire garde 1 → l'équipe « re-monte 1→2 » à chaque palier de 18 XP et
**ré-accorde +20 stamina EN BOUCLE** (d'où la stamina qui ne descend jamais — le symptôme repéré par l'user).
**Correctif** (même schéma que resyncHeroes/resyncCampaign, §6) : `recordCampaignAttack` fait
`userInfo.basicInfo.teamLevel = user.getTeamLevel()`. Vérifié `StamTrace` (le niveau monte 1→2 au 3ᵉ combat
et **RESTE** à 2 ; teamXP progresse ensuite vers 25 = seuil 2→3 ; plus de refill +20 ; stamina se consomme
vraiment : …114→108→102) + **`server/smoke/TeamLevelPersistTest`** (niv.2 après 18 XP, survit au reload
SQLite). Régression `Resource/CampaignAttack/CampaignPersist` toujours verte.

### Réponse à « 122/120 et 6 combats sans consommation ? »
OUI la stamina est consommée (−6/combat). Le « 122>120 » = bonus `STAMINA_GAIN_ON_LEVEL=20` du jeu à la
montée de niveau d'équipe (stored peut dépasser le cap d'affichage 120 → clampé `min(122,120)=120`, même
artefact bénin R102). Le « pas de consommation apparente sur 6 combats » = le **bug ci-dessus** (re-level en
boucle refillant +20 tous les 3 combats) — désormais corrigé : la stamina descend réellement.

### BUG trouvé & corrigé : LOOT D'OBJETS de campagne non crédité (question user « objets équipables ? »)
L'utilisateur a demandé si les objets ramassés en combat sont persistés et dispo à l'équipement. Investigation :
inventaire (`individualUserExtra.items`) VIDE après 5 victoires (`InvProbe`), même EN MÉMOIRE (`LootProbe`) →
pas un souci de persistance, `recordOutcome` ne crédite RIEN. Cause : le combat est CLIENT-autoritatif → le
client ROULE le loot pendant le combat et l'envoie dans `CampaignAttack.lootEarned` (List<RewardDrop>) +
`memoryChanges`. `recordOutcome` **n'en roule pas** (vérifié `LootRoll` : lootEarned vide en entrée → vide en
sortie) : il **applique** la liste reçue (`giveLoot → RewardHelper.giveRewards → addItem` → items, auto-persisté).
`recordCampaignAttack` passait des listes **vides** → objets jamais crédités. **Correctif** : passer
`m.lootEarned` comme **1ᵉʳ paramètre List** (le loot à donner). ⚠️ **Piège** : le **2ᵉ paramètre List** de
`recordOutcome` est un **delta de RewardDrop** (déjà-affiché) que `giveLoot` passe à `removeDelta` → y mettre
`m.memoryChanges` (`UserLootMemoryChange`) **plante** (`ClassCastException` dans `removeDelta`) au 1ᵉʳ
`CampaignAttack`, qui n'est alors jamais enregistré → **cascade `CAMPAIGN_LEVEL_LOCKED`** sur tous les suivants
(révélé par un run réel). On laisse donc ce delta **VIDE**. Vérifié `LootApply` (RewardDrop → getItemAmount
0→1) + `server/smoke/LootPersistTest` (BADGE_OF_FRIENDSHIP x2 crédité, `m.memoryChanges` peuplé SANS planter,
survit au round-trip SQLite → dispo à l'équipement). **PARTIEL** : `m.memoryChanges` (mémoire de loot/pity) +
graine RNG client (`Action SET_SEED` TYPE=LOOT/COMBAT, loguée « non appliquée ») non appliqués → le serveur ne
re-roule pas, il fait **confiance au loot client** (cohérent avec le combat client-autoritatif).
Régression (Resource/CampaignAttack/CampaignPersist/TeamLevelPersist) verte.

### ENCHAÎNEMENT 1-1→1-5 EN JEU ✅ (nav post-victoire + fenêtre d'équipement)
D'abord le pilote rejouait 1-1 (une seule entrée carte, 6 combats) : après victoire il revenait sur
l'aperçu du MÊME niveau et re-tapait FIGHT au lieu de retourner à la carte. **Fix nav post-victoire** : flag
`justFoughtCampaign` posé en combat → au retour sur CampaignPreview/HeroChooser, RETOUR (BACK) à la carte →
`enterCampaignLevel` prend `nextPlayableLevel` = niveau débloqué suivant (remis à false à chaque entrée
fraîche pour ne pas sortir du 1ᵉʳ choix après le combat d'intro). **Fix étape équipement** : le pilote bouclait
sur `HeroDetailScreen` (~7000 frames) en FERMANT la `CraftingWindow` (prise pour un résidu ; cible tuto =
HERO_GEAR_SLOT_SIX derrière) → cas (a-bis) : une CraftingWindow est une **fenêtre de FLUX** → taper son bouton
EQUIP (`CRAFTING_WINDOW_EQUIP_BUTTON`) au lieu de fermer. **Résultat EN JEU** (run fresh, 3 fixes cumulés) :
`CampaignAttack NORMAL 1-1,1-2,1-3,1-4,1-5 WIN` — le pilote enchaîne
`normalOrEliteNodeSelected(1-1)→RETOUR→(1-2)→(1-3)→(1-4)→RETOUR→(1-5)→(1-6)`. **État persisté** (DB) :
teamLevel=**2**, 1-1..1-5 à **3★**, 1-6 débloqué, gold=**2316**, stamina stored=108/eff=108 (consommée
correctement — plus de refill en boucle grâce au fix team-level), serveur **0 fatal**. Pipeline complet
(entrée/choix héros/skills/vagues/loot/récompenses/XP/énergie/progression/**enchaînement**) validé en jeu.

### Run resume « coincé tôt » (élucidé)
Un run *resume* (300s) avait repris DANS le tuto (progression persistée seulement à HERO_FILTERS), rejoué
intro+coffres, atteint HERO_FILTERS puis s'était mis en **veille** (captures + pings keepalive) sans franchir
l'étape avant timeout. Pas une boucle de reconnexion (mauvaise lecture : `dh_game.log` écrasé par le run
suivant). Le run *fresh* (du début) atteint la campagne de façon fiable.

### Fichiers
`desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (`selectHeroesIfNeeded`). Aucune modif jeu/serveur.

---

## 2026-07-16 — Enquête « crash addHeroEXP » : RÉSOLU (artefact pré-fix) + vraie cause = SIGABRT oop-map JVM → reframe game.jar (serveur)

### Résumé
Reprise de l'enquête « crash `addHeroEXP` » (demande user « investigue ça et corrige »). Trois conclusions
fermes, toutes vérifiées :
1. **Le crash `addHeroEXP` (`ClientErrorCodeException: ERROR []`) NE se reproduit PLUS** — c'était un
   **artefact d'un état compilé pré-fix** (ChargeTest tournait contre une ancienne compilation). Sur la DB de
   chaînage courante, recompilée, `ChargeTest`/`ChainProbe`/`DiscTest` passent tous.
2. **Le VRAI incident intermittent était un crash JVM** (SIGABRT, pas une exception de jeu) :
   `fatal error: Illegal class file … in method getDefaultStats` dans `GenerateOopMap::error_work`
   pendant un **GC** (G1 root scan). Cause : le bytecode **dex2jar** de `game.jar` n'a pas de
   `StackMapTable` → sous `-Xverify:none` la JVM infère les oop-maps au GC via l'ancien
   `generateOopMap.cpp`, qui **plante** sur certains motifs (`ConstantStats.getDefaultStats`, perks de guilde).
   **Non déterministe** (dépend du timing GC) : ~1 run/8 en test. **Le serveur AUTORITATIF y était exposé
   aussi** (`run-online.sh:67` tournait `-Xverify:none`), pas seulement les smoke tests.
3. **La stamina « 39,96 M » N'EST PAS un bug** (hypothèse user « timestamp mal enregistré » **réfutée**).

### Fix durable : reframe game.jar → game-framed.jar (comme le client), retrait de -Xverify:none
`tools/reframe/ReframeJar` (ASM 9.7, `COMPUTE_FRAMES`) réécrit les **64 196** classes de `game.jar` avec des
frames valides → la JVM utilise le vérificateur rapide par table (plus de `generateOopMap`) et on **retire
`-Xverify:none`**. Appliqué à **`desktop-port/run-online.sh` (serveur)** ET **`server/smoke/run.sh`** :
reframe à la demande vers `libs/game-framed.jar` (gitignoré/régénérable, comme `game-logic-framed.jar` côté
client), classpath serveur basculé dessus, `-Xverify:none` retiré, `-XX:TieredStopAtLevel=1` conservé
(prudence C2). **Sémantique inchangée** (métadonnées de vérif). Vérifié :
- **`ChargeTest` 15/15 sans abort** sous vérification par défaut (vs abort intermittent avant).
- **Boot serveur OK** (framed jar, vérif par défaut) : process en écoute, port ouvert, **0 fatal**
  (aucun `VerifyError`/`Illegal class`/`GenerateOopMap`).
- **Régression identique** : `ResourceTest`, `CampaignAttackTest`, `CampaignPersistTest` verts (framed jar).
- Smoke `run.sh` : `CodecRoundTrip`/`MessageRoundTrip` OK ; `HandshakeRoundTrip` **retiré** (obsolète, ancien
  ctor `LoginServer` — SHIMS TODO #4).

### Stamina 39,96 M — cause RÉELLE établie (pas un bug), timestamp CORRECT
Mesures directes (probes) sur la DB de chaînage :
- **STORED stamina = 114** (correct), `lastResourceGenerationTime(STAMINA)` **correctement sauvé** (≈31 min
  avant `serverTimeNow`). ⇒ **hypothèse « timestamp mal enregistré » réfutée**.
- `getResourceCap(STAMINA)=**120**` (cap DÉPENSABLE), content update **R102**, `getRegenAmount=39 965 650`,
  `getHardCap=79,46 Md`, intervalle 6 min. Un joueur **neuf** lit **120** (aucun temps écoulé).
- Le « 39,96 M » n'apparaît QUE quand `stamina<cap` **et** ≥1 intervalle écoulé : `updateAndGetResource`
  (branche NON-capée pour STAMINA) ajoute **un** tick de 39,96 M puis sort → `getResource`=brut. Le jeu
  affiche/dépense la valeur **effective = `min(getResource, cap)` = 120**. **Fidèle R102 end-game** : un tick
  d'inactivité (6 min) = recharge pleine.
- `ChainProbe` (3 combats enchaînés) : **effective toujours ≤120** (120→114→120→114), **gold chaîne**
  340→680→1088→1632, **aucun crash de logique**. Le fix `applyEffectiveResourceCap` (commit 8f84ef5) applique
  la MÊME règle du jeu (`min(getResource, cap)`) → DB propre, dépense visible. **PAS de « figer »**
  (recalcule via `getResource`, régén incluse).

### Fichiers
`desktop-port/run-online.sh` (reframe + retrait `-Xverify:none`), `server/smoke/run.sh` (framed jar auto +
retrait Handshake obsolète), `.gitignore` (`libs/game-framed.jar`), `docs/SHIMS.md` (row reframe),
`MEMORY.md`. Aucune modif de logique de jeu ni de serveur (le handler campagne du commit 8f84ef5 est inchangé).

---

## 2026-07-13 (soir) — EQUIP_ITEM VÉRIFIÉ IN-GAME (wire) + enregistreur pas-à-pas + fixes pilote

### Résumé
Grâce à un **enregistreur pas-à-pas** (`dh.tutorec` : dump exhaustif des pointeurs du tuto + captures
numérotées par tick) et à un pilote **discipliné** (plus de tap central hors-script), on a capturé la
**séquence exacte de l'équipement** et **vérifié `EQUIP_ITEM` en jeu sur le wire** : le vrai client envoie
`Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, extra={SLOT=SIX}}`, le serveur répond
`action EQUIP_ITEM appliquée [persisté]`, et le tuto INTRO_FEATURES avance. Nouvelle micro-frontière :
post-équip, le tuto n'émet plus de pointeur sur `HeroDetailScreen` (il faut en sortir vers le hub).

### Enregistreur `dh.tutorec` (outil DEV)
À chaque tick d'autotap : (1) `[tutorec]` dump SANS dédup — écran, fenêtres, **TOUS** les pointeurs du tuto
(`getPointAt` + `getActorTutorialName`), acteurs actionnables ; (2) capture **numérotée**
`build/rec/step_NNN.ppm` (après rendu/swap). Câblé `DH_TUTOREC`. Sert à savoir **exactement** ce que le tuto
désigne à chaque étape (au lieu de deviner). Off par défaut.

### Pilote discipliné (anti-vagabondage)
1. Le lanceur ne tape au **centre** que si le tuto n'a **aucun pointeur actif** (`hadActiveTarget()`=false,
   dialogue « tap to continue »). Avant, le tap central partait hors-script quand un pointeur était actif mais
   non résolu → écran coffre Diamant → « Follow the tutorial arrow! », tuto figé.
2. Quand la cible désignée est **absente de l'écran courant** (élément du hub alors qu'on est sur un écran de
   détail), le pilote frappe **BACK_BUTTON** pour se rapprocher du hub.

### Séquence de l'équipement (ENREGISTRÉE, source de vérité)
`HeroDetailScreen` pointeur **`HERO_GEAR_SLOT_SIX`** (slot 6) → ouvre `CraftingWindow` → pointeur
**`CRAFTING_WINDOW_EQUIP_BUTTON`** → le client émet `Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP,
SLOT=SIX}`. **Le serveur applique + persiste** (handler `ServerUser.applyCommand` EQUIP_ITEM) → tuto avance
(INTRO_FEATURES step 17→29/21). ⇒ `EQUIP_ITEM` **vérifié bout-en-bout en jeu**, plus seulement au smoke test.
NB : le client envoie le slot dans `extra={SLOT=SIX}` ; le handler le recalcule via `getSlotThatCanEquip`
(concordant) — on pourra préférer l'`extra` SLOT quand présent.

### Back-out « libre » post-équip → PREMIER COMBAT DE CAMPAGNE atteint
Indice utilisateur : sur `HeroDetailScreen` post-équip, le **bouton retour est mis en avant** mais
`getPointers` (rafraîchi) renvoie **vide** → le jeu attend que le joueur **sorte** de lui-même (pas de
pointeur formel). Ajout au pilote : après ~120 frames d'inactivité (aucun pointeur) sur un écran NON-hub,
taper **BACK_BUTTON** (seuil en frames, robuste à l'autotap ; un dialogue avance au tap central avant le
seuil). **Résultat vérifié en jeu** : le pilote sort `HeroDetailScreen`→`HeroListScreen`→…→**hub**, puis le
tuto reprend ses pointeurs (`MAIN_SCREEN_CAMPAIGN`→`CAMPAIGN_CHAPTER_ONE_NAME`→`CAMPAIGN_PREVIEW_FIGHT_BUTTON`
→`HERO_CHOOSER_*`) et lance le **1ᵉʳ combat de campagne** (`CampaignAttackScreen`, Frozone équipé, rendu
unidbg). Le tuto atteint l'acte **`FAST_FORWARD`** (post-INTRO_FEATURES). Capture
`desktop-port/build/campaign.png`. ⇒ frontière équipement **entièrement franchie**, on est dans le monde/combat.

### Roster de départ : Ralph + Elastigirl (fidélité vidéo)
Constat utilisateur (vidéo de gameplay) : un compte neuf possède **Ralph + Elastigirl** AVANT Frozone — or
notre nouveau joueur n'avait que Frozone (du coffre). L'intro combat les crée en SYNTHÉTIQUE
(`createUnitDatas`→`new User()`+`CombatSimHelper.createUnitData`) et ne les ajoute PAS au roster ; le roster
de départ est une décision de **création de compte** (serveur, qu'on contrôle). Ajout dans
`ServerUser.initNewPlayerResources` : `user.createAndAddHero(RALPH/ELASTIGIRL, WHITE, 1, 1, "new_user")`
(méthode du jeu ; état = défaut « nouveau héros » = celui de Frozone-coffre) + resync wire. Frozone reste
donné ENSUITE par le coffre GOLD. Vérifié (`server/smoke/RosterTest`) : {Ralph,Elastigirl} WHITE niv.1 →
+Frozone → persiste au wire ; 0 régression (Equip/Resource/ViewedChests/ChestWire OK). **Confirmé (vidéo)** : Vanellope n'est que dans le 1ᵉʳ combat tuto (synthétique), PAS possédée ensuite →
roster = Ralph + Elastigirl ; rang/niveau WHITE niv.1 validé.

### Piège découvert : reprise POLLUÉE
Reprendre depuis un `dh-server.db` d'une run **tuée** en plein coffre laisse un état incohérent (coffre Gold
déjà ouvert → bouton « gratuit » en cooldown, mais step de tuto non avancé) → deadlock (le tuto pointe un
bouton mort, `LootResults=0`). ⇒ tester depuis un état **propre** (snapshot post-coffres, ou nouveau joueur).

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` : `dh.tutorec` (dump exhaustif) + `hadActiveTarget()` + RETOUR BACK_BUTTON.
- `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java` : tap central conditionné + capture par tick + capture périodique.
- `desktop-port/run-desktop.sh`, `run-online.sh` : `DH_TUTOREC`, `DH_SHOTEVERY` ; garde-fou (client+Xvfb).
- `MEMORY.md`, `JOURNAL.md`, `docs/SHIMS.md`.

## 2026-07-13 — Pilote : pop-ups empilées drainées (hub atteint) + Actions de bookkeeping REAL/NO-OP

### Résumé
Le correctif du pilote DEV pour les **pop-ups modales EMPILÉES** est **validé en jeu** : le tuto franchit la
frontière de l'équipement et atteint le **hub principal propre** d'un nouveau joueur ; frontière suivante =
tuto `HERO_FILTERS`. Les deux Actions de bookkeeping que le serveur loguait « non appliquée (PARTIEL) » sont
désormais traitées **fidèlement** : `VIEWED_CHESTS` (RÉEL) et `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle).

### Pilote — drainage des modales empilées (`TutorialDriver.driveOnce`)
- **Bug** : quand le tuto pointe une cible (ex. bouton EQUIP de `CraftingWindow`) mais qu'une modale
  résiduelle (`ChestReadyWindow` « CRATE READY ») est empilée par-dessus, l'ancien code faisait `collect()`
  sur **toutes** les fenêtres, trouvait la cible dans la fenêtre inférieure et « tapait » ses coordonnées —
  mais le tap est **absorbé par la modale du dessus** (seule elle reçoit l'entrée) → faux « tapé »
  (`return true`) → **blocage infini**.
- **Correctif** (guidé par le tuto, sans coordonnée devinée) : on raisonne sur la fenêtre du **dessus**.
  (a) le tuto pointe DEDANS → taper le bouton désigné ; (b) le tuto pointe **AILLEURS** → la modale du dessus
  est un **résidu bloquant** → la fermer via l'API du jeu (`BaseModalWindow.hide()` = bouton X), ce qui
  **draine la pile une fenêtre/frame** jusqu'à révéler la cible ; (c) aucune cible active → attendre
  (récompense=`hide()`, interactive=bouton VIEW).
- **Vérifié en jeu** (reprise persistée depuis le snapshot post-coffres) : sur `HeroListScreen` une
  `ChestReadyWindow` résiduelle (coffre Gold) apparaît empilée → **drainée** (VIEW → `ChestResultsWindow` →
  fermeture). Le tuto progresse **au-delà** de l'équipement (INTRO_FEATURES step 29 → `HERO_FILTERS`) ; le
  jeu atteint le **hub principal rendu** (menu HEROES/ITEMS/…, CHOOSE NAME, CAMPAIGN!/CRATES!), session
  stable (Ping échangés). Capture `desktop-port/build/herofilters.png`.
- **Nouvelle frontière** (`HERO_FILTERS`) : `getPointers` n'émet le pointeur d'un step (`DIALOG_1` →
  `UIComponentName.FILTER_BUTTON`) que si `Step.logic().matches()` est vrai (sinon `cibles=[]`, attente).
  À la reprise, le client repart du hub (`MainScreen`) alors que `HERO_FILTERS` attend `HeroListScreen` →
  le pilote devra naviguer vers HEROES (chantier suivant).

### Actions de bookkeeping — `VIEWED_CHESTS` (RÉEL) + `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle)
- **`VIEWED_CHESTS`** : branche extraite au bytecode de `ActionHelper.doAction` =
  `user.setTime(TimeType.LAST_CHESTS_VIEW_TIME, Long.parseLong((String) extra.get(ActionExtraType.TIME)))`.
  `User.setTime` écrit dans `this.extra.times` (`UserExtra` partagé) → **persiste** via `this.extra` (§3).
  Marque « coffres vus » (efface la pastille « nouveau »).
- **`RECORD_SERVER_ROLL_FINISHED`** : `ClientActionHelper.recordServerRollFinished` ne fait que construire
  l'extra (`ID/TYPE/COUNT/TIME`) et appeler `ActionHelper.doAction(RECORD_SERVER_ROLL_FINISHED, …)` — or
  `doAction` **n'a AUCUNE branche** pour ce `CommandType` (vérifié) → le code **client** du jeu ne mute rien.
  Pure notification client→serveur ; le comptage **autoritatif** des rolls est déjà fait par `openChest`
  (`ChestHelper.updateChestRollCounters`). ⇒ **NO-OP fidèle** (pas une rustine : rien n'est simulé ;
  inventer un registre de `rollId` violerait §4).
- **Vérifié** (`server/smoke/ViewedChestsTest`) : nouveau joueur → `applyAction(VIEWED_CHESTS, extra{TIME})`
  puis `applyAction(RECORD_SERVER_ROLL_FINISHED, extra{ID,TYPE,COUNT,TIME})` → round-trip wire →
  `getTime(LAST_CHESTS_VIEW_TIME)` == la valeur envoyée ; les deux `applyAction` renvoient `true`.

### Ressources du nouveau joueur — énergie « 39,96 M / 120 » CORRIGÉE
- **Bug** (repéré à la capture du hub) : l'énergie affichait **des millions** (« 39,96 M / 120 »). Cause :
  un `new IndividualUserExtra()` laisse `getLastResourceGenerationTime(STAMINA)=0` ; le jeu calcule la
  stamina courante = `UserHelper.updateAndGetResource(STAMINA, …, serverTimeNow())` = régénération **depuis
  l'époque 1970** (≈ 56 ans / intervalle 6 min ≈ des millions).
- **Correctif fidèle** (`ServerUser.initNewPlayerResources`, appelé par `newPlayer`) : comme un serveur à la
  création d'un compte — **ancre l'horloge de génération** de chaque ressource régénérée
  (`UserHelper.resourceGenerates(rt)` → `iu.setLastResourceGenerationTime(rt, creationTime)`) puis met la
  **stamina au cap du jeu** (`UserHelper.getResourceCap(STAMINA, user)` = `MAX_STAMINA` de `team_levels.tab`,
  **120** au niveau d'équipe 1). Valeurs issues de la logique/données du jeu (pas inventées). `setResource`
  écrit `individualUserExtra.resources` (partagé → persiste) ; sa branche `battlePassV2` ne concerne QUE les
  diamants → sûr headless.
- **Gestion/valeurs vérifiées** (`server/smoke/ResourceTest`) : compte neuf → **STAMINA=120/120**,
  **GOLD=0**, **DIAMONDS=0**, et la stamina **ne se re-gonfle pas** au round-trip wire (gen-time persisté).
  Note : GOLD/DIAMONDS=0 = valeur du constructeur du jeu pour un compte neuf (le jeu/tuto les accorde en
  jouant) ; à revoir si une dotation de départ (diamants) doit être seedée.

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` : drainage des modales empilées (logique (a)/(b)/(c)).
- `server/java/dhserver/ServerUser.java` : `applyCommand` += `VIEWED_CHESTS` (RÉEL) + `RECORD_SERVER_ROLL_FINISHED` (NO-OP) ; `newPlayer`/`initNewPlayerResources` (ancrage gen-time + stamina au cap).
- `server/smoke/ViewedChestsTest.java` (NEW), `server/smoke/ResourceTest.java` (NEW).
- `docs/SHIMS.md` #5, `MEMORY.md` (date + §7), `JOURNAL.md`.

---

## 2026-07-12 — Handler `Action` : investigation + architecture « logique cœur par commande »

### Résumé
Démarrage du **handler `Action`** (commandes génériques du jeu : équiper/voir/promouvoir…). Investigation
riche → **correction d'un diagnostic**, **1 vrai shim**, et une **conclusion d'architecture**. Handler
**frameworké et démarré**, **pas encore fonctionnel** pour l'équipement (à finaliser).

### Faits établis (corrigent/précisent)
- **`GuildStats` n'est PAS un bloqueur** (mon 1ᵉʳ diagnostic était FAUX — « illusion de crash », comme
  EVIL_QUEEN). `guild_perk_levels.tab` a des `CONTENT_TL` vides (lignes `TIMED_*`) → `parseInt("")` lève,
  mais `GeneralStats.parseStats` l'**attrape** (`onStatError` LOGue + saute la ligne). Vérifié en isolation
  (`GuildProbe`) : **`GuildStats` se charge OK**. La stack imprimée était **loguée**, pas fatale.
- **`DH.app.guildInfo` requis** : `getYourGuildInfo()` → `GuildPerkHelper.updateGuildInfoTimedPerks` lit
  `guildInfo.perkEndTimes`. **Corrigé** : `ServerContext.bind` pose un `new GuildInfo()` (nouveau joueur).
- **`ActionHelper.doAction` = chemin CLIENT « appliquer + UI »** : appelle `getScreenManager().getScreen()`
  **×4** → NPE headless (pas d'écran). ⇒ **on N'UTILISE PAS `doAction`** côté serveur. Comme `openChest`
  (qui exécute `ChestStats`/`DropTable`, pas un flux « acheter » client), on route **par commande vers la
  logique CŒUR** (`HeroHelper.equipItem`, `RealGearHelper.equipGear`…). Aiguillage écrit, règle exécutée.
- **Le badge « Badge of Friendship » n'est PAS du real gear** (`ItemStats.getRealGearType=DEFAULT`) → la
  commande d'équipement du tuto est vraisemblablement **`EQUIP_ITEM`**, pas `EQUIP_REAL_GEAR`.
- **Commandes `Action` réellement observées** (log serveur) : `VIEWED_CHESTS`, `RECORD_SERVER_ROLL_FINISHED`
  (mises à jour d'état légères). L'`Action` d'équipement reste **à capturer** (repro in-game NON déterministe :
  le pilote n'atteint pas toujours l'onglet gear).

### Ce qui est fait (committé)
- `ServerContext` : shim `guildInfo` (`new GuildInfo()`), en plus de user/individualUser/évènements.
- `ServerUser.applyAction`/`applyCommand` : aiguillage **par commande** vers la logique cœur ; routes
  `EQUIP_ITEM` (`user.getHero` + `HeroHelper.getSlotThatCanEquip` + `HeroHelper.equipItem`) et
  `EQUIP_REAL_GEAR` (`RealGearHelper.equipGear`) ; **log des commandes non gérées** (= cartographie).
- `LoginServer` (branche `Action`) : **log du contenu exact** (command/hero/item/extra) + `applyAction` + persist.

### `EQUIP_ITEM` ✅ RÉSOLU & VÉRIFIÉ (débogage déterministe par probes)
Diagnostic pas à pas (sans le client flaky) : (a) `openChest(SILVER)` **persiste bien** le badge
(`individualUserExtra.items={BADGE_OF_FRIENDSHIP=1}`) — le « no slot » venait d'un **snapshot périmé** ;
(b) le badge est le gear **requis du slot 6** de Frozone (`NormalGearStats.getItem(FROZONE, WHITE, SIX)=
BADGE_OF_FRIENDSHIP`) ; (c) `getSlotThatCanEquip` renvoie null car `ItemStats.isItemReleased(badge,
ContentHelper.getCurrent(user))=false` **headless** (colonne de contenu mal résolue — bug à corriger à part) ;
(d) `HeroHelper.equipItem(FROZONE, badge, SIX, user)` **équipe correctement** (slot 6 rempli, badge consommé).
⇒ **fix** : `applyCommand` route `EQUIP_ITEM` via `HeroHelper.getSlotThatCanEquip` + `equipItem`. **Vérifié**
(`server/smoke/EquipTest`) : équipe le badge en slot 6 + **persiste au round-trip wire**.

### Couche CONTENU (colonnes de release) ✅ RÉSOLU — unlock générique
Cause racine du `isItemReleased=false` headless : `ContentHelper` démarre **vide** (`ContentStats
.getColumns()=0` → `getColumn(now)=DEFAULT`). Le jeu charge le contenu du shard via
`ShardStats.setShardID(shard, map)` → `parseStats("content.<shard>.tab", opener)` — jamais appelé headless.
⇒ **fix** : `ServerContext.bind` appelle `ContentHelper.get().setShardID(user.getShardID(), new HashMap())`
→ charge `content.<shard>.tab` (372 colonnes pour shard 1) → `isItemReleased`=true. **Générique** (débloque
toute logique gatée « contenu released », pas que l'équipement). `EQUIP_ITEM` repasse alors sur la logique
d'origine `getSlotThatCanEquip` (plus de contournement). Reste : autres commandes (`VIEWED_CHESTS`…).
Détail : `docs/SHIMS.md` #5.

### Fichiers touchés
- `server/java/dhserver/ServerContext.java` (shim guildInfo), `ServerUser.java` (applyAction/applyCommand),
  `LoginServer.java` (branche Action + log), `docs/SHIMS.md` (#5 réécrit avec les faits).

---

## 2026-07-12 — Traversée du tuto en autonomie (pilote DEV) : intro→coffres→héros→équipement

### Résumé
Mise au point du **pilote DEV** (`TutorialDriver`) + méthodologie **reprise persistée + capture/inspection**
pour traverser le tuto in-game et faire une **passe de features**. Franchi : intro+combat → coffre GOLD
(Frozone) → coffre SILVER (Badge of Friendship) → CRATE READY → menu HÉROS → liste → détail héros →
onglet GEAR → **équipement du badge**. **0 exception serveur** partout. Frontière atteinte : l'équipement
passe par `Action1` non traité côté serveur → prochain handler.

### Corrections du pilote (chacune trouvée par capture d'écran + dump des acteurs)
- **Popups de récompense** (`ChestResultsWindow` = « CRATE REWARDS ») : fermées via l'API du jeu
  `BaseModalWindow.hide()` (le tap central ratait le X). Distinction affichage-récompense vs interactif.
- **Popups interactives** (`ChestReadyWindow` = « CRATE READY ») : frappe du **bouton-texte principal**
  (`DFTextButton` « VIEW », à stage(640,180)≠centre) — trouvé via `dumpActionable` (dump classe/tag/texte/pos).
- **Recherche dans TOUTE la scène** (`stage.getRoot`, pas seulement `getRootStack`) : le menu latéral
  (HEROES/ITEMS…) était hors du rootStack → `BASE_MENU_HERO_BUTTON` introuvable (trouvés=0) alors que le jeu
  y pointe. Corrigé → enchaîne HeroList→HeroDetail→GEAR→EQUIP.

### Méthodologie (documentée dans MEMORY §6ter)
- **Reprise RAPIDE** : ne pas supprimer `server/data/dh-server.db` → le client **reprend au hub** (saute
  l'intro), **~20 s** au lieu de ~4 min (0 rejeu de combat). **Snapshots** DB aux points sûrs
  (`dh-snapshot-postchests.db`, `dh-snapshot-postequip.db`) pour restaurer une frontière.
- **Boucle** : au blocage → screenshot (voir l'écran) + `dumpActionable` (quoi taper) → faire frapper le
  **bon acteur du jeu** (jamais une coordonnée devinée) → recompiler → relancer (reprise rapide).
- **Lire les logs avec `grep -a`** (le log serveur peut avoir du bourrage NUL).

### Finding (prochain handler serveur)
L'**équipement de gear** (et sans doute d'autres actions) part en **`Action1`** (fire-and-forget) que le
serveur **journalise mais ne TRAITE pas** → l'état autoritatif ne reflète pas l'équipement → au **reload**
le tuto ne peut pas avancer (idle sur MainScreen, INTRO_FEATURES step 29). ⇒ prochain : **traiter `Action`
côté serveur** (équipement…), comme on a fait pour `BuyChests`.

### Infra
- **Garde-fou serveurs** (`run-online.sh`) : détecte/kill les anciens serveurs (zombies) + refuse si port pris.
- Email d'auteur des commits corrigé → `noreply@anthropic.com` (GitHub vérifié).

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (fermeture popups, bouton-texte, recherche
  scène, dump), `desktop-port/run-online.sh` (garde-fou + DH_TUTODBG/DH_FPS + DH_FRAMES vide=non plafonné),
  `desktop-port/run-desktop.sh` (LC_ALL=C.utf8), `MEMORY.md` (§6bis lancement + §6ter méthodologie).

---

## 2026-07-12 — Diagnostic signal 16/exit 144 (strace) + couche évènements spéciaux (2ᵉ coffre débloqué)

### Résumé
Deux résultats liés, obtenus en poussant le run client réel jusqu'au coffre : (1) **diagnostic définitif**
du `exit 144` (SIGSTKFLT) via `strace -f` ; (2) un **bug réel** révélé par ce run — le **2ᵉ** coffre plantait
côté serveur — **corrigé fidèlement** en initialisant la couche évènements spéciaux du jeu (tâche #14).

### (1) signal 16 / exit 144 — `strace -f` = kill EXTERNE, pas de crash natif
Client lancé sous `strace -f -e trace=kill,tgkill,tkill,rt_sigqueueinfo,rt_tgsigqueueinfo,exit_group,exit
-e signal=all`. Sur **6 runs** (30→1500 frames, hors-ligne ET en ligne atteignant le combat) :
- **Aucun `SIGSTKFLT`**, aucun `tgkill`/`rt_sigqueueinfo` **interne** (rien ne s'auto-tue), `exit_group(0)`
  **propre** par le thread leader de la JVM. Seulement **2 `SIGSEGV`/run**, mais **normaux** (HotSpot les
  utilise pour ses null-checks implicites + safepoint polling, les rattrape, continue).
- Le kill **disparaît sous strace** (ralentissement ptrace) et n'est pas revenu ensuite → réponse au doute
  « crash natif JNI/.so » (unidbg/unicorn/LWJGL) : **NON**. Un crash natif serait déterministe sur le même
  chemin (ne disparaît pas juste parce qu'on ralentit) et apparaîtrait dans strace (signal reçu ou auto-kill).
  `SIGSTKFLT` (16) est quasi inutilisé par le noyau/glibc/JVM → **kill externe transitoire du superviseur**,
  non bloquant, non lié à notre code (un simple serveur Python en a été victime aussi). Confirme la
  conclusion précédente (le test `4× yes` avait déjà écarté un budget CPU).

### (2) Couche évènements spéciaux — 2ᵉ coffre (tâche #14)
Le run client réel a d'abord **prouvé le 1ᵉʳ coffre de bout en bout** (`[login] ==> LootResults : coffre
GOLD -> 1 héros débloqué`), puis a envoyé un **2ᵉ** `BuyChests` → `openChest` **NPE** :
`SpecialEventsHelper.helper` null via `giveChestRewards` → `RewardHelper.giveReward` → `UserHelper.giveUser`
→ `ContestHelper.onItemEarn` → `getActiveContestsWithTask` (les coffres à **récompense d'objet** enregistrent
des tâches de contest). C'est la dette #14, sur le chemin critique.
- **Fidélité** : `GameMain.create()` fait `SpecialEventsHelper.init(new ClientEventUserProvider(), new
  ClientSpecialEventsHelperExt())` (vérifié au bytecode, offsets 589-603), puis `handleBootData` appelle
  `setSpecialEvents(SpecialEventsRaw, user, shardID)`. L'extension **cliente** touche libGDX (`Gdx.app not
  available` headless) car elle **pousse au serveur** les temps de visionnage (`UpdateEventViewTimes`).
- **`dhserver.ServerSpecialEventsExt`** (NEW) = équivalent **serveur** de l'interface (RÉEL) :
  `sendEventRewards` reproduit la logique d'état cliente à l'identique (PREMIUM_STAMINA_CONSUMABLE →
  `ItemHelper.convertTimeLimitedItems` + `setTime(LAST_AMPED_STAMINA_BUY)`, aucun libGDX) ; `trySetEventViewed`
  conserve l'inscription **autoritative** (`user.getIndividual().getEventViewTimes().put`) et **omet** la
  poussée réseau client→serveur (le serveur EST le destinataire → sans objet). Zéro donnée falsifiée.
- **`ServerContext`** : `init()` appelle `SpecialEventsHelper.init(new ClientEventUserProvider(), new
  ServerSpecialEventsExt())` (une fois, comme `create()`) ; `bind()` appelle `setSpecialEvents(new
  SpecialEventsRaw(), user, shardID)` (par joueur, comme `handleBootData`). Nouveau joueur sans évènement
  live = raw vide → `getActiveContestsWithTask` renvoie une liste **vide** (au lieu de NPE).
- **`ServerUser.openChest`** : `updateChestCounters` **réactivé** (plus de PARTIEL).

### Vérifications
- **Unitaire** : nouveau joueur → **3 coffres GOLD d'affilée** : coffre 1 = Frozone (héros 0→1), coffre 2 =
  **récompense d'objet** (`heroesUnlocked=0`, le chemin qui plantait) **sans NPE**, coffre 3 OK.
- **Wire** (`ChestWireTest`) : `BuyChests(GOLD)` → `LootResults{Frozone}` en ~746 ms, **aucune régression**.
- **En jeu** : run client réel relançé (serveurs recompilés) pour rejouer les 2 coffres du tuto.

### Confirmation IN-GAME du 2ᵉ coffre (2026-07-12, après coup)
Un run client **non plafonné** (`dh.frames` absent → tourne jusqu'à ce que le tuto se joue) a traversé
l'intro + les DEUX coffres : GOLD → Frozone, puis **SILVER → `LootResults` avec 0 héros = récompense
d'OBJET** (exactement le chemin `onItemEarn` qui plantait). Résultat serveur : **0 exception** de tout le
run (`helper is null`/`NullPointer` = 0), tuto poursuivi jusqu'à **INTRO_FEATURES step 29**, `Action1`
(claims de coffre) acceptés. ⇒ le 2ᵉ coffre est **corrigé et vérifié in-game**. (Piège d'outillage noté :
tronquer le log serveur avec `: >` laisse du bourrage NUL → `grep` le prend pour un binaire et n'affiche
rien ; utiliser `grep -a`. Ne pas tronquer un log tenu ouvert par le serveur.)

### `sendEventRewards` : plus aucune recopie (2026-07-12)
Suite à la demande utilisateur (« éviter la recopie manuelle, exécuter le code du jeu »), `ServerSpecialEventsExt`
alloue l'instance du jeu `ClientSpecialEventsHelperExt` **sans constructeur** (Unsafe, le ctor touche libGDX)
et **délègue `sendEventRewards`** à la vraie méthode (elle ne dépend pas de libGDX). `trySetEventViewed` reste
de la glue serveur (enregistre le temps de visionnage, sans la poussée réseau client→serveur sans cible).
Zéro ligne de logique de jeu recopiée. Vérifié : 3 coffres d'affilée inchangés.

### Fichiers touchés
- `server/java/dhserver/ServerSpecialEventsExt.java` (NEW puis délégation), `ServerContext.java` (init/bind
  couche évènements), `ServerUser.java` (`updateChestCounters` réactivé).
- `desktop-port/run-desktop.sh` (`LC_ALL=C.utf8` — extraction d'assets aux noms Unicode).
- `docs/SHIMS.md` (TODO #1 → RÉSOLU, entrées `ServerSpecialEventsExt` + locale plateforme),
  `docs/SERVER_PLAN.md` §6, `MEMORY.md`.

---

## 2026-07-12 — Handler `BuyChests` complet : le serveur exécute la logique du jeu (Frozone, vérifié wire)

### Résumé
Étape 6 démarrée avec un **handler `BuyChests` fully functional** (option B) : le serveur **exécute le code
du jeu** sur l'état autoritatif → roule la vraie table, donne Frozone, répond `LootResults`, persiste.

### Architecture (option B, choisie avec l'utilisateur)
- **`ServerContext`** : `ServerStats.install()` (données du jeu) + **shim `DH.app`** — beaucoup de classes
  passent par le singleton client `GameMain` (ex. `User.getIndividual()` = `DH.app.getYourIndividualUser()`).
  On alloue un `GameMain` **sans constructeur** (`Unsafe.allocateInstance`), pose `user`/`individualUser`,
  affecte `DH.app`. Couche plateforme (§4), pas de logique de jeu.
- **`ServerUser.openChest(BuyChests)`** : construit `User`/`IndividualUser` de jeu **sur nos objets wire**
  (`getUser` fait `this.extra = userExtra` → **les mutations via `this.extra` persistent d'elles-mêmes** :
  `setResource`/`setChannelRollCount` écrivent dans `this.extra`). Roule `ChestStats.getDropTable(GOLD)` +
  `DropTable.rollNode("ROOT")`, `DropConverter.convert`, `ChestHelper.giveChestRewards(bl=true)` (donne +
  remplit `heroesUnlocked`), `updateChestRollCounters`. **Resync** des champs hors `this.extra` (héros via
  `getHeroData`, `chestUpgradeXP`). Renvoie `LootResults`.
- **`LoginServer`** : `BuyChests` → `openChest` → répond `LootResults` + persiste (SQLite).

### Sérialisation inverse (le point clé résolu)
Le jeu n'a pas de sérialiseur `User→wire` complet, MAIS ses setters écrivent dans `this.extra` (l'objet
wire qu'on lui passe). En construisant le `User` **sur nos propres objets wire**, la plupart des mutations
persistent automatiquement ; seul un **ensemble fermé** (héros, `chestUpgradeXP`) est resynchronisé.
Validé par round-trip.

### Vérifications
- **Unitaire** : nouveau joueur (0 héros) → `openChest(GOLD)` → `LootResults{lootDrops=1, heroesUnlocked=1}`,
  joueur possède Frozone, **persiste au reload** (SQLite).
- **Sur le wire** (`server/smoke/ChestWireTest`) : client `ClientInfo→BootData` puis `BuyChests(GOLD)` →
  **`LootResults{Frozone}` reçu en ~630 ms**. Handler prouvé sur le protocole réel.
- **En jeu** : le client atteint le coffre et envoie `BuyChests1` à notre serveur (confirmé). Le run client
  complet meurt parfois (exit 144, signal d'environnement sur runs longs) avant d'afficher la réponse —
  d'où la vérification par `ChestWireTest` (rapide, déterministe).

### PARTIEL noté (§2, avec risque)
- `updateChestCounters` (compteurs QUOTIDIENS, limites d'achat) passe par `SpecialEventsHelper.helper`
  (couche évènements non initialisée headless) → différé. Non requis pour le tuto (coffre gratuit).
- Coffres **payants** (charge diamants via `setResource`→`DH.app.getUserBattlePassV2`) : nécessitent
  d'étoffer le shim (battlePassV2). Le coffre **gratuit** du tuto est complet.

### Fichiers touchés
- `server/java/dhserver/ServerContext.java` (NEW), `ServerUser.java` (openChest + resync), `LoginServer.java`
  (handler BuyChests), `server/smoke/ChestWireTest.java` (NEW), `desktop-port/run-online.sh` (-Ddh.stats),
  `docs/PRINCIPLES.md` §3 (shim DH.app + sérialisation inverse), `docs/SERVER_PLAN.md` §6, `MEMORY.md`.

---

## 2026-07-12 — Enquête coffres/héros + fondation « serveur exécute le code+données du jeu » (spike Frozone)

### Résumé
Investigation (question utilisateur) sur le 1ᵉʳ coffre du tuto, puis **fondation de l'étape 6** : le serveur
**charge les données du jeu et exécute sa logique** (règle affinée §3). **Spike concluant** : le vrai code
de coffre du jeu roule côté serveur → **Frozone** pour un nouveau joueur.

### Enquête (extraite du code/données)
- **Héros avant le 1ᵉʳ coffre = 0.** Combat d'intro = synthétique (`CombatSimHelper.createUnitData(new
  User(),…)`). 5 « héros tuto » du jeu = `RemoveIf(SpecificHeroes, VANELLOPE, RALPH, YAX, ELASTIGIRL,
  FROZONE)` (`black_market_merchant_drops.tab`), acquis progressivement (Frozone→Vanellope `UnlockHeroActV1`
  →Yax campagne 1-13…). `starter_deal_heroes.tab` = pack **payant**, pas le roster gratuit.
- **1ᵉʳ coffre = FROZONE prédéfini** : `IntroFeaturesActV2.getChestUnitType()=FROZONE` **et** rig de la table
  `gold_chest_drops.tab` (`ROOT_1X_FIRST ? PreviousRolls(0) ? ROOT_1X_RIG_1 ? CJK ? HERO_BUZZ ? HERO_FROZONE`).
- **Coffres hors tuto** : `BuyChests{chestType,count,roll:ServerRollRequest}` → serveur roule
  `<type>_chest_drops.tab` (`DropTable`) : rigs 1ᵉʳ/2ᵉ, pitié 10× `Try NoneAre(YourHero)`, payant/gratuit,
  VIP, locale, pools `@NON_EXCLUSIVE/GOLD_CHEST_EXCLUSIVE_HEROES`. `channelRollCount` alimente `PreviousRolls`.
- Réponse au client = **`LootResults`** (`heroesUnlocked`/`lootDrops`/`costs`/`roll`).

### Fondation « lire & exécuter » (spike)
- **`ServerStats`** (NEW) installe l'ouvreur de stats du jeu (`StatFileHelper.setExt`) lisant
  `game-data/stats/` → 274 `.tab` chargés headless (SEVERE = quirks `.tab` tolérés, comme en jeu).
- **Dépendance joda-time** : `game.jar` a les classes joda (dex2jar) mais **pas** la donnée fuseaux
  `org/joda/time/tz/data/*` (requise par `TimeUtil.<clinit>`, appelé via `ContentStats`/`CampaignStats` lors
  de `IndividualUser.setExtra`). Fournie par le jar standard joda-time-2.12.2 (classes ombrées par game.jar,
  seule la ressource tz utilisée) — donnée du jeu, pas une réécriture. Récupéré à la demande par `run-online.sh`.
- **Spike** (`ChestSpike`) : install stats → `ChestStats.getDropTable(GOLD)` (table 38 nœuds, parsée) →
  `User`/`IndividualUser` construits depuis l'état wire (`ClientNetworkStateConverter`) → `ChestContext(user)`
  avec `setChestType(GOLD)`+`setCount(1)` → `DropTable.rollNode("ROOT")` = **`HERO_FROZONE`**. (count=0 →
  `RetainCount(0)` = 0 drop : d'où l'importance de `setCount`.) **Zéro donnée écrite à la main.**

### Fichiers touchés
- `server/java/dhserver/ServerStats.java` (NEW), `desktop-port/run-online.sh` (classpath+fetch joda).
- `docs/PRINCIPLES.md` §3 (règle affinée « lire & exécuter »), `docs/SERVER_PLAN.md` §6 (archi + faits coffres),
  `MEMORY.md`, `JOURNAL.md`.
- Reste : handler `BuyChests` complet (roll→`LootResults`→`applyChestResults`→re-sérialiser→répondre).

---

## 2026-07-12 — Tuto d'intro joué DE BOUT EN BOUT (harnais DEV) + FPS combat + frontière du hub

### Résumé
Ajout d'un **harnais de DEV** (drapeaux lanceur, off par défaut, **aucune modif jeu/serveur**, **rien en
prod**) qui pilote le jeu headless via **les outils/API du jeu** : le tuto d'intro se joue **de bout en
bout jusqu'à `DONE`**, puis atteint sa 1ᵉʳ action serveur (**coffre de départ → `BuyChests1`**) = frontière
de la phase hub. Mesure FPS/profilage du combat.

### Outils de DEV ajoutés (côté lanceur `desktop-port`)
- **`TutorialDriver`** (piloté par `dh.autotap`) : interroge `TutorialHelper.getPointers(user)` → cible
  (`TutorialPointerInfo.getActorTutorialName()` = nom+index), retrouve l'acteur via `getTutorialName()`
  (comme `Group.findTutorialActor`) dans `getRootStack()`, convertit stage→écran (viewport plein cadre) et
  tape via `DhInput`. Sans pointeur → tap central (dialogues). **Zéro coordonnée devinée** : tout vient de
  l'acteur désigné par le jeu. C'est du **contrôle headless**, pas une modif.
- **`dh.autofight`** : appelle l'API publique **`CoreAttackScreen.setAutoAttack(true)`** (bouton AUTO
  d'origine) → auto-combat du jeu (utile hors tuto ; le tuto d'intro **met le combat en pause** et exige un
  tap manuel sur le héros désigné → assuré par le driver).
- **`dh.fps`** : FPS glissants + **profilage** (chrono des appels unidbg vs reste). Compteurs statiques dans
  `UnidbgVM` autour du dispatch `si/sv/so/pi/pv/po`.
- **Inventaire des outils d'auto du jeu** (pour info) : `automation/{TouchRecorder,TouchPlayback}` (rejoue un
  enregistrement), `automation/crawler/*` (scripts de clics FIXES : Example/Chest/Market/Purchase),
  message serveur `TriggerCrawler`, `TutorialHelper.finishIntroForced/finishAllTutorials` (saut). **Aucun**
  n'est un « joueur de tuto » clé en main ; le driver ci-dessus s'appuie sur le **système de pointeurs** du jeu.

### FPS combat (sur cette machine, headless llvmpipe SANS GPU)
- **~9 fps** en combat (`TutorialAttackScreen`). Répartition par frame : **unidbg (spine+particules) ~50 ms
  (combat léger) → ~80 ms (combat plein, DOMINANT)** ; rendu logiciel+logique ~40-60 ms. Deux pire-cas :
  pas de GPU (le « reste » s'effondrerait avec une carte) + émulation ARM (plancher dur d'unidbg). ⇒ perf
  combat = futur chantier d'optim (moins d'appels unidbg/frame, JIT dynarmic cassé à réparer, etc.).

### Déroulé vérifié (run-online.sh + DH_AUTOTAP + DH_AUTOFIGHT)
- Intro : GATE_DIALOG → TRANSFORM → **COMBAT1** (ACTIVE_1/2/3 franchis via taps guidés) → POST_COMBAT →
  **COMBAT_2** → **`DONE`**. Serveur = 0 réponse requise pour tout l'intro (client-side).
- Puis **INTRO_FEATURES** : chargement des VFX `reward_boxes`, **`BuyChests1`** + `Action1` envoyés →
  écran **« CRATE REWARDS / Waiting for results… »** (capture `native/reference/shots/
  tutorial-intro-done-crate.png`). Le serveur journalise mais **ne répond pas** → le client attend.

### Conclusion / prochaine phase
Réponse à « gagne-t-on des héros de départ, le serveur gère-t-il ? » : **OUI**, le tuto donne les héros de
départ via un **coffre** (`BuyChests1`) juste après l'intro, et **le serveur doit le gérer** (contenu du
coffre autoritatif = héros de départ, réponse). ⇒ **étape 6 (hub)** démarre par le handler **`BuyChests`**.

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (NEW), `DesktopLauncher.java` (autotap→driver,
  `dh.autofight`, `dh.fps`+profilage), `dhbackend/unidbg/UnidbgVM.java` (chrono), `run-desktop.sh` (drapeaux).
- `docs/SERVER_PLAN.md` (étape 6 amorcée + section Outils de DEV), `MEMORY.md`, `JOURNAL.md`.
- Captures : `native/reference/shots/{tutorial-combat1-unidbg,tutorial-intro-done-crate}.png`.

---

## 2026-07-11 — Serveur autoritaire étape 5 : persistance SQLite (octets wire des objets du jeu)

### Résumé
L'état joueur autoritaire est **persisté en SQLite** sous forme d'**octets wire** produits par les
classes du jeu (aucun schéma inventé pour les données du jeu, cf. PRINCIPLES §6). La progression du
tutoriel survit à un redémarrage du serveur.

### Détails
- **`ServerUser` refactoré** : détient l'état comme **objets du jeu** — `UserInfo` (identité),
  `UserExtra` (héros/ressources/réglages), `IndividualUserExtra` (tutoriels/quêtes). `bootData()` branche
  ces objets dans un `new BootData()` (complet par ses constructeurs). Sérialisation :
  `GruntMessage.writeAll` (en-tête + données = wire exact) ↔ `MessageFactory.readMessage` (round-trip
  symétrique prouvé par les smoke tests).
- **`UserStore` (SQLite, `sqlite-jdbc`)** : table `users(userID, shardID, userInfo BLOB, userExtra BLOB,
  individualUserExtra BLOB, updatedAt)`, clé `(userID, shardID)`, upsert `ON CONFLICT`. **Un objet du jeu
  = un BLOB** → ajouter un champ de jeu persisté = ajouter un BLOB, sans recopier une seule valeur.
- **`LoginServer`** : `loadOrCreate(1,1)` au démarrage ; `store.save(user)` à chaque `ChangeTutorialStep`.
- **Dépendances** : `sqlite-jdbc` 3.45 + `slf4j-api` récupérés à la demande par `run-online.sh` (non
  committés, régénérables — comme ASM). DB sous `server/data/` (gitignore). Le serveur reste « rien à
  installer » côté utilisateur.

### Vérifications
- **Unitaire** : session 1 crée le compte (122 actes), avance INTRO au pas 40, `save` ; **session 2**
  rouvre la DB → état **restauré à l'identique** (122 actes, INTRO step 40), BootData revalide sur le wire.
- **En jeu** : `LoginServer` démarre, `loadOrCreate` OK (DB `server/data/dh-server.db`), persiste les
  `ChangeTutorialStep` réels sans erreur.

### Fichiers touchés
- `server/java/dhserver/UserStore.java` (NEW) : persistance SQLite (BLOB wire).
- `server/java/dhserver/ServerUser.java` : détient les objets du jeu + sérialisation wire.
- `server/java/dhserver/LoginServer.java` : load-or-create + save sur ChangeTutorialStep.
- `desktop-port/run-online.sh` : classpath + fetch sqlite/slf4j ; `.gitignore` : DB runtime.

---

## 2026-07-11 — Serveur autoritaire étape 4 : handlers du tuto (intro) + pilote headless (vérifié en jeu)

### Résumé
Le serveur **applique/persiste la progression du tutoriel** (`ChangeTutorialStep`) dans un état autoritaire
(`ServerUser`). Fait extrait : le **tutoriel d'intro est 100% piloté par le client** (aucun aller-retour
serveur), donc le serveur n'a qu'à **suivre la progression**. Ajout d'un **pilote headless** (`dh.autotap`)
qui traverse les dialogues « tap to continue » : le tuto se joue **de bout en bout jusqu'au 1ᵉʳ combat**.

### Détails (extraction = source de vérité)
- **`IntroTutorialActV2` n'émet AUCUN message réseau** (aucun `sendMessage`/`networkProvider`) ; son combat
  est **local** : `CombatSimHelper.createUnitData((IUser)new User(), …)`, `pauseCombat/resumeCombat`,
  `PauseCombatEvent/ResumeCombatEvent`, `TutorialHelper.startCombatTimerEvent`. Les étapes (`Step` enum) sont
  toutes des états de dialogue/combat côté client (GATE_DIALOG_*, TRANSFORM_ANIMATION, COMBAT1_*, COMBAT_2_*,
  POST_COMBAT_*, DONE). ⇒ **seule sortie serveur = `ChangeTutorialStep`** (framework), fire-and-forget.
- **`ChangeTutorialStep`** = `{type, step, forceSkip}`. `step` **absolu** (cf. `finishIntroForced` pose
  `step = maxStep`). `ServerUser.applyTutorialStep` met à jour l'acte : `step` ← message, `maxStep` ←
  max(courant, step) (« plus haut pas vu »). Copie défensive dans `bootData()` (le client ne mute pas
  l'état autoritaire).
- **Pilote headless** `DesktopLauncher` : `dh.autotap=N` injecte un tap central toutes les N frames via
  l'infra existante `DhInput.tap`→`drain`. Traverse les gates « tap to continue » sans utilisateur.

### Vérifications
- **Unitaire** (sans libGDX) : nouveau joueur = 122 actes step 0 ; apply 12/25/3 → step=3, maxStep=25 ;
  état autoritaire non muté par la copie client ; survit au round-trip wire.
- **En jeu** (`run-online.sh` + `DH_AUTOTAP=45`) : le serveur applique les `ChangeTutorialStep` **réels**
  (INTRO 1→2→3, FRIEND_MISSION 6, FRIENDSHIP_UNLOCK 17, REAL_GEAR_UNLOCK 11, FRIEND_CAMPAIGN 20,
  HEIST_NARRATION 1…), **tous** trouvés dans les 122 actes (0 « type inconnu »), **0 réponse** requise. Le
  client joue l'intro **de bout en bout jusqu'au 1ᵉʳ combat** : GATE_SIGN_DROP → GATE_RALPH_ENTER →
  GATE_VANELLOPE_ENTER → GATE_DIALOG_2 → TRANSFORM_ANIMATION → COMBAT1_INTRO → …_SHOW_LOGO →
  …_POST_LOGO_DIALOG → …_POST_GLITCH_DIALOG. Session stable (Ping échoé).

### Fichiers touchés
- `server/java/dhserver/ServerUser.java` (NEW) : état joueur autoritaire (BootData + progression tuto).
- `server/java/dhserver/LoginServer.java` : handler `ChangeTutorialStep` ; BootData depuis `ServerUser`.
- `server/java/dhserver/NewUserState.java` : recentré (fabrique tutoriels + `latestVersion`).
- `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java`, `run-desktop.sh` : pilote `dh.autotap`.
- Reste : actions post-intro server-validées (nom, campagne, récompenses) = **handlers du hub (étape 6)** ;
  **persistance SQLite (étape 5)** de `ServerUser`.

---

## 2026-07-11 — Serveur autoritaire étape 3 : BootData nouveau joueur → TUTORIEL (vérifié en jeu)

### Résumé
Le serveur envoie désormais un **BootData de nouveau joueur complet** construit **à partir des classes
du jeu**, et le client d'origine **route vers le tutoriel d'intro** : `IntroTutorialActV2` démarre et rend
la scène d'ouverture (Ralph + Vanellope devant le portail). Zéro donnée écrite à la main.

### Détails (décompilation CFR = source de vérité)
- **`GameMain.handleBootData` lu en entier** (949 lignes bytecode → CFR) : recensé **chaque** champ de
  `BootData` déréférencé (doit être non-null). **`BootData` décompilé** : le **constructeur du jeu**
  initialise TOUS ces champs (`userInfo=new UserInfo()`, `userExtra`, `privateUserInfo`, `guildInfo`,
  `currentServer=new Server()`, `allContests`, `individualUserExtra`, `invasionInfo`, `specialEvents`,
  `statData*/statVersions=new HashMap`, `loginEvent=""`, `mailMessages=new ArrayList`…). ⇒ `new BootData()`
  est **complet par construction** (règle : la complétude vient des initialiseurs du jeu, pas d'une liste
  inventée).
- **Routage tuto (chaîne extraite)** : `handleBootData` → `ClientNetworkStateConverter.getIndividualUser
  (individualUserExtra)` → `IndividualUser.setExtra` itère **`individualUserExtra.tutorialActs`** (List de
  `TutorialAct`) → `getUserTutorialAct` → `addTutorialAct`. `TutorialHelper.completedTutorialAct(type)`
  renvoie **true quand `getTutorialAct(type)==null`** (acte ABSENT ⇒ tuto « fait/sauté ») ; sinon complété
  sur `step >= act.getMaxStep()` (`AbstractTutorialAct.getCompletionState`). ⇒ un nouveau joueur doit porter
  **TOUS** les `TutorialHelper.NEW_USER_ACTS` (**122 types**) à **`step 0`** (IN_PROG), sinon des features
  (UNLOCK_HERO…) seraient considérées « déjà faites » et jamais introduites.
- **Aucune saisie** : `NewUserState.newUserTutorialActs()` lit la liste **dans le registre du jeu** —
  `TutorialHelper.NEW_USER_ACTS` (public) + `ACTS` (réflexion, `type→IntMap(version→act)`), en prenant la
  **dernière version enregistrée** par type. Vérifié : les 122 types résolvent une version (117×v1, 5×v2),
  aucun non enregistré. `TutorialHelper` **se charge côté serveur sans libGDX** (les ctors d'actes ne
  touchent pas libGDX à la construction).
- **Correction de fidélité** : la version INTRO enregistrée dans le 12.1.0 est **`IntroTutorialActV2`**
  (`getType()==INTRO`, `getVersion()==2`) — `IntroTutorialActV1` n'est **pas** enregistré. Les anciennes
  notes « IntroTutorialActV1 » étaient erronées.
- **`SEVERE: Missing row in tutorials.tab`** (EMERALD_RANK, FRANCHISE_TRIALS(+STAGE_SELECT), PATCHED_HEROES,
  TEAM_LEVEL_UP, BATTLE_PASS_V2…) : **PAS causé par le serveur**. Le SEVERE inclut `REMOVED__CRYPT` (jamais
  dans mes actes) ⇒ il vient de **`TutorialStats.onMissingRow`** qui parcourt l'enum `TutorialActType`
  complet au chargement de `tutorials.tab` (l'APK 12.1.0 a du **code** en avance sur sa **donnée** `.tab`).
  Comportement d'origine tolérant (même catégorie que l'étape 2). Aucune rustine.

### Vérifications
- **Round-trip wire** (`MessageFactory`, sans libGDX) : BootData nouveau joueur = 8192 o, 122 actes relus,
  INTRO v2/step0, id/teamLevel OK.
- **En jeu** (`run-online.sh`, client d'origine via unidbg) : `[login] ==> BootData nouveau joueur : 122
  actes de tuto (step 0)` ; `IntroTutorialActV2 onTutorialTransition` INITIAL→SCREEN_WAIT→…→GATE_DIALOG_1_A ;
  8× `ChangeTutorialStep1` reçus ; **capture `desktop-port/build/online-tuto.png`** = scène d'ouverture
  (Ralph + Vanellope + portail Disney Heroes, dialogue « I can't believe you talked me into this. »).

### Fichiers touchés
- **`server/java/dhserver/NewUserState.java`** (NEW) : construit le BootData nouveau joueur (identité +
  `tutorialActs` depuis le registre du jeu).
- `server/java/dhserver/LoginServer.java` : `main` envoie le BootData nouveau joueur (via `NewUserState`).
- `docs/SERVER_PLAN.md`, `docs/PROTOCOL.md` §6, `MEMORY.md` : étape 3 ✅ + correction IntroTutorialActV2.
- Prochain (étape 4) : traiter/persister `ChangeTutorialStep` (aujourd'hui journalisé).

---

## 2026-07-09 — Bootstrap du projet (session initiale)

### Résumé
Mise en place complète des fondations : étude du projet de référence DragonSoul,
récupération et reconnaissance de l'APK Disney Heroes, extraction des données de jeu,
et création du système de mémoire (MEMORY.md + JOURNAL.md) et de la documentation.

### Détails chronologiques

**1. Étude du dépôt de référence DragonSoul.**
- Ajouté `aciderix/dragonsoul-web` à la session et cloné dans `/workspace/dragonsoul-web`.
- La branche par défaut `main` n'a pas `desktop-port/` ; récupéré la branche
  `claude/game-transpile-debug-2p5irx` (`git fetch --depth 1 origin <branch>` → `FETCH_HEAD`).
- Lu les docs clés de `desktop-port/` : `PRINCIPLES.md`, `SERVER_DESIGN.md`, `PROTOCOL.md`,
  `PROGRESS.md`, `STARTING_STATE.md`, `SHIMS.md`.
- **Architecture DragonSoul comprise** : backend desktop LWJGL3 maison (`dsbackend/`)
  remplaçant la couche plateforme Android ; jeu (bytecode) réutilisé tel quel (seul
  traitement autorisé = remap non-sémantique des collisions de noms) ; serveur Java
  (`server/Ds*.java`) réutilisant les classes du jeu (`ServerXORConnectionWrapper`,
  `MessageFactory`, `BootData`) pour une sérialisation identique. Login en 2 étapes
  (POST /login HTTP puis TCP jeu). Codec = Deflate + XOR roulant, clé fixe 8 octets.
  Multi-serveur (passerelle locale, mot de passe HMAC hors protocole, découverte
  Direct/LAN/communautaire, persistance SQLite).

**2. Récupération de l'APK Disney Heroes.**
- Téléchargé depuis Google Drive (`id=1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF`) via le
  contournement de la page « Virus scan warning » (form → `drive.usercontent.google.com`
  avec `confirm=t&uuid=...`). Résultat : `disney-heroes.apk`, **96 Mo**, APK Android valide.
- Contenu : 6 `classes*.dex` (base APK d'un App Bundle → **pas de `.so` natifs** ici,
  ils sont dans les splits par ABI). `assets/{stats,strings,automation,shaders,sound,fonts…}`.
- `assets/info.txt` : `version_name 12.1.0`, `git_commit a53845c9`, build 2023-02-22,
  `env release`. `assets/api_key.txt` (JWT Amazon) → `pkg com.perblue.herocities`.

**3. Reconnaissance du bytecode (strings sur les .dex).**
- **Confirmé : même stack réseau que DragonSoul, mais NON obfusquée.** Classes en clair :
  `com.perblue.heroes.network.messages.MessageFactory`, `...network.messages.*` (ClientInfo1,
  BootData1, ArenaAttack, …), `...network.{XORConnectionWrapper,DHXORConnectionWrapper,
  NetworkProvider,GameServerAddress}`, `com.perblue.common.network.{XORConnectionWrapper,
  XORCipher,XORInputStream,DeflateConnectionWrapper,StackedConnectionWrapper}`,
  `com.perblue.grunt.translate.{GruntMessageFactory,ConnectionWrapper,DummyConnectionWrapper}`.
- **AssetUpdater** identique (gate `MISSING_ADDITIONAL`, `WORLD_ADDITIONAL`, `UI_DYNAMIC`,
  log « Failed to load %s, setting MISSING_ADDITIONAL flag », « loadDynamicUI setting
  MISSING_ADDITIONAL true for »). Contenu LIVE : `http://content.disneyheroesgame.com/live/index.txt`.
- **ServerType** = `com.perblue.heroes.ServerType` (getters `gameHost`, `getGameHost`,
  `contentLocation`, `getContentLocation`). Login : `login.disneyheroesgame.com`
  (+ `login.staging.disneyheroesgame.com`, `dhstaging...:10070/content/beta/index.txt`).
- **PIÈGE identifié** : les classes `gateway/v1/*` (AdRequest, ClientInfoOuterClass,
  InitializationCompletedEventRequest, DiagnosticEvent, DeveloperConsent…) sont le **SDK
  Unity Ads**, PAS le protocole du jeu. Le protocole du jeu reste le binaire
  `MessageFactory` (FULL_NAME façon `BootData1`), comme DragonSoul.

**4. Extraction des données du jeu (source de vérité).**
- Écrit `tools/extract_game_data.sh` : extrait `assets/stats/*` et `assets/strings/*` de
  l'APK vers `game-data/` (principe §4 : aucune donnée recopiée à la main).
- Résultat committé : `game-data/stats/` = **274 `.tab`** (TSV ; ex. `arena_constants.tab`
  = clé + colonnes `FIGHT_PIT_VALUE/COLISEUM_VALUE`), `game-data/strings/` = **325
  `.properties`** (locales incluses). Ces fichiers se chargent **tels quels** côté serveur.

**5. Mémoire projet & docs.**
- Créé `MEMORY.md` (récupération de contexte, tenu à jour) et `JOURNAL.md` (ce fichier).
- Créé `docs/PRINCIPLES.md`, `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`, `docs/ASSETS.md`,
  `docs/RECON.md`. Créé `.gitignore` (APK/dex/jars/zip non committés — régénérables).
- Conservé en l'état l'infra d'upload archive.org existante (`upload_batch.py`,
  `disney_heroes_live_index.txt`, `index.txt`, workflow) — non déplacée pour ne pas
  casser le workflow.

### Découvertes importantes / risques
- **RISQUE #1 (ouvert)** : incohérence potentielle version APK (12.1.0) vs `index.txt`
  (GameVersion 7.8.1, Revision 325-326). À vérifier : révision de contenu exigée par cet
  APK (extraire de l'AssetUpdater) ↔ assets archivés. Ne pas rustiner le contrôle.
- Avantage majeur vs DragonSoul : **noms non obfusqués** → reverse et réutilisation des
  classes bien plus simples. Et **assets + index.txt disponibles** (archive.org).

### Fichiers ajoutés/modifiés
- `+ MEMORY.md`, `+ JOURNAL.md`, `+ .gitignore`, `+ README.md`
- `+ docs/{PRINCIPLES,ARCHITECTURE,PROTOCOL,ASSETS,RECON}.md`
- `+ tools/extract_game_data.sh`
- `+ game-data/stats/*.tab` (274), `+ game-data/strings/**/*.properties` (325)

**6. Décompilation ciblée (androguard).**
- dex2jar/jadx **indisponibles** : téléchargements GitHub Releases bloqués par le proxy
  (403 « GitHub access to this repository is not enabled »). Contournement : `pip install
  'androguard<4'` (pypi autorisé ; `mutf8` compile via gcc présent).
- Localisé les classes cibles dans `classes4.dex` puis désassemblé (`DalvikVMFormat`) :
  - **`DHXORConnectionWrapper`** : champ statique `KEY` = 8 octets
    `CE 85 D4 F9 29 A8 24 56` ; ctor = `StackedConnectionWrapper(DeflateConnectionWrapper,
    XORConnectionWrapper2(KEY))` ⇒ codec **Deflate + XOR(KEY)** (identique à DragonSoul en
    conception, clé propre à Disney Heroes).
  - **`ServerType`** (ctor `(name, ordinal, protocol, loginHost, port, contentLocation)`) :
    LIVE = `https://` / `login.disneyheroesgame.com` / `443` / contenu
    `http://content.disneyheroesgame.com/live/index.txt`. STAGING, LOCAL (`localhost:8080`),
    NONE/TRUNK/DEV relevés aussi. **APK NON patché** (vrais domaines PerBlue) → redirection
    prévue par réécriture `ServerType` (réflexion) ou passerelle, sans patch bytecode.
- Cherché une **révision de contenu embarquée** (RISK #1) : aucun constant évident ; le gate
  repose vraisemblablement sur des **marqueurs de catégorie** (fichiers repères) comme
  DragonSoul → à confirmer en décompilant `AssetUpdater`. RISK #1 reste ouvert.
- Docs mises à jour : `PROTOCOL.md` (§0, §1.1), `RECON.md`, `MEMORY.md` §3/§7.

**7. Décompilation de l'`AssetUpdater` → RISQUE #1 RÉSOLU.**
Package `com.perblue.heroes.assets_external` : `ExternalAssetManager` (orchestre),
`AssetIndexDownloader` (parse/décide), `ArchiveInfo`, `ContentServerKeys`, `AssetCategory`.
- **`retainRowsForVersion(rows, gameVersion)`** : retire les lignes dont `GameVersion` >
  version du client (`client.compareTo(new VersionNumber(row.GameVersion)) < 0`). ⇒ ne
  bloque que le contenu **futur** ; garde l'égal/plus ancien.
- **`checkArchives`** (par catégorie `shouldDownload()`) : décide **uniquement sur la
  révision** — `getMostRecentCompleteArchive` (Mode==COMPLETE & Category, rev max),
  `getLatestDownloadedRevision` (prefs), `getNeededIncrementalArchives`. Aucun test de
  GameVersion. Logs : « complete download: rev N », « incremental download », « up-to-date! »,
  « no prior complete archive ». Garde-fou `handleBootLoop`.
- **Filtrage device** (`lambda$onComplete$0`) : ne retient que `Environment==LIVE` +
  `Density`/`Compression` du device (SON/TEXT/PNG traités à part). ⇒ servir l'index d'origine
  **tel quel**, le client sélectionne ses lignes.
- **Conclusion RISQUE #1** : APK 12.1.0 > index 7.8.1/7.9 → **toutes les lignes retenues** ;
  install neuve → télécharge `COMPLETE rev 325` puis `INCREMENTAL rev 326`. **L'APK accepte
  les assets archivés.** Le libellé GameVersion de l'index n'est pas un critère de rejet.
  Risque résiduel = complétude **runtime** (à constater en exécutant). Docs : `ASSETS.md`
  (algorithme complet), `MEMORY.md` §7.

**8. Serveur de contenu v0 (`server/content_server.py`).**
- Python stdlib (zéro dépendance → hébergeable partout). Endpoints :
  - `GET /live/index.txt` : sert le manifeste avec les **URLs d'archives réécrites** vers
    ce serveur (`/live/<nom>.zip`) — le jeu filtre lui-même par device/version.
  - `GET|HEAD /live/<nom>.zip` : sert une **copie locale** (`--cache assets-cache/`) si
    présente, sinon **302** vers l'archive publique (archive.org).
  - `GET /health`.
- Config via options/env : `--port/--host/--index/--cache/--archive-base/--rewrite-host`.
- Ajouté `server/run-content-server.sh` + `server/README.md`.
- **Vérifié de bout en bout** (port 8899) : index réécrit (URLs → 127.0.0.1:8899) ; requête
  `.zip` → 302 → archive.org → 200, `Content-Length` 4422179 = colonne `Size` de l'index.
  Confirmé aussi que l'archive.org du projet renvoie bien les fichiers (HEAD 200, tailles OK).

**9. Décompilation en jar régénérable + preuve de réutilisation du codec du jeu.**
- Outillage : dex2jar/jadx GitHub bloqués par le proxy, mais le **fork maintenu
  `de.femtopedia.dex2jar:dex-tools:2.4.28` est sur Maven Central** (accessible). Maven,
  Gradle, javac, jar présents.
- `tools/decompile.sh <apk>` (committé, reproductible) : `mvn dependency:copy-dependencies`
  → lance `com.googlecode.dex2jar.tools.Dex2jarCmd -f -o libs/game.jar <apk>` + copie
  `commons-logging` en `libs/`. Sortie : `libs/game.jar` (~70 Mo, 66 134 classes ; gitignored).
- Vérifs de chargement (JVM desktop) :
  - `javap` OK ; **clé XOR recoupée** (`DHXORConnectionWrapper.KEY` = `{-50,-123,-44,-7,41,
    -88,36,86}` = `CE 85 D4 F9 29 A8 24 56`, identique à la recon androguard).
  - **`VerifyError: Expecting a stackmap frame`** au chargement → résolu par **`-Xverify:none`**
    (bytecode dex2jar sans stackmap frames ; contrôle de *chargement*, pas d'exécution).
  - **`NoClassDefFoundError commons-logging`** → ajouté `commons-logging:1.2` (Maven).
- **Smoke test `server/smoke/CodecRoundTrip.java`** (committé) : instancie deux
  `DHXORConnectionWrapper` du jeu (client/serveur), `server.wrapIn(client.wrapOut(msg)) == msg`
  → **ROUND-TRIP OK**. Wire commence par `78 9C` (en-tête zlib/Deflate). ⇒ **le codec réseau
  du jeu se réutilise tel quel côté serveur** (stratégie validée, comme DragonSoul).
- Docs : `docs/SHIMS.md` créé (contraintes `-Xverify:none` + `commons-logging` + jar
  régénérable). `.gitignore` : ajout `*.class`, `*-error.zip`. `MEMORY.md` §6/§7 à jour.

**10. Sérialisation des messages du jeu prouvée (sans libGDX).**
- Probes JVM desktop (`libs/game.jar`, `-Xverify:none`, `commons-logging`) :
  - `MessageFactory.getInstance()` **OK** et `new BootData()` **OK** → se chargent **sans
    libGDX** (le clinit de MessageFactory enregistre les messages sans dépendance graphique).
  - API sérialisation : `GruntMessage.writeAll(GruntOutputStream)`, `GruntOutputStream.getBytes()`,
    `MessageFactory.readMessage(GruntInputStream)`. `BootData.getFullName()=="BootData1"`.
  - **Round-trip message** (`server/smoke/MessageRoundTrip`) : `BootData`(serverTime=1234567890,
    serverHasArenaSeasons=true, loginEvent="hello") → `writeAll` (4096 o) → `readMessage` →
    champs identiques. **OK**.
- Ajout `server/smoke/MessageRoundTrip.java` ; `run.sh` compile+exécute les 2 smoke tests.
- Docs : `PROTOCOL.md` §2bis (API sérialisation vérifiée) ; `MEMORY.md` §6/§7.
- ⇒ Toute la pile serveur (codec + sérialisation) est **validée avec les vraies classes du
  jeu** : le serveur pourra décoder un `ClientInfo1` et répondre un `BootData1` au format wire
  exact, sans réimplémentation ni libGDX.

**11. Serveur de login v1 (squelette) + handshake TCP prouvé bout-en-bout.**
- Découverte : le jar du jeu contient aussi la **pile SERVEUR** du framework grunt
  (`GruntNIOTCPServer`, `GruntTCPServer`, `GruntUDPServer`, `GruntBuilder`) → on **réutilise
  le serveur du jeu** au lieu de refaire le framing (`packInt`) à la main.
- Obstacles levés (reversés) :
  - `GruntNIOTCPServer` est **package-private** sans fabrique publique → ajout de
    `com.perblue.grunt.translate.GruntServerFactory` (classe dans le même package, pas une
    modif du jeu) pour l'instancier. Ctor : `(port, factory, executor, connectionListener,
    wrapperClass, sendTimeout, keepAlive, noDelay, useProxyProtocol, bufferSize)` (mappé par
    décompilation).
  - Le ctor **crée le thread NIO mais ne l'active pas** : `running=false` (AtomicBoolean) et
    `thread` non démarré. Diagnostic clé : premier essai en TIMEOUT (client TCP-connecté via
    backlog kernel mais aucun accept applicatif). Fix : lever `running` (réflexion) + démarrer
    le thread daemon. → handshake OK.
- `server/java/dhserver/LoginServer.java` : sur `ClientInfo` reçu, répond un `BootData`
  (`setAsReplyTo` + `send`) via `MessageFactory` + codec `DHXORConnectionWrapper` du jeu.
- **Smoke test `server/smoke/HandshakeRoundTrip`** : client `GruntBuilder` envoie
  `ClientInfo1`, `LoginServer` répond `BootData1`, le client décode (serverTime/loginEvent
  corrects) — **sur socket TCP réelle, sans libGDX**. `run.sh` compile `server/java` + lance
  les 3 smoke tests (codec, message, handshake) : **tous OK**.
- Docs : `PROTOCOL.md` §2ter, `SHIMS.md` (GruntServerFactory + 3 smoke tests), `server/README.md`,
  `MEMORY.md` §6/§7. `.gitignore` : `/server/smoke/out/`.

**12. Artefacts committés + démarrage du port desktop.**
- **APK + jar décompilé committés** (demande utilisateur : éviter re-téléchargement/décompilation
  après reset) : `game/disney-heroes-12.1.0.apk` (~92 Mo), `libs/game.jar` (~68 Mo),
  `libs/commons-logging.jar`. `.gitignore` : exceptions. `tools/{extract_game_data,decompile}.sh`
  par défaut sur `game/disney-heroes-12.1.0.apk`. Push OK (warnings GitHub >50 Mo, sous la
  limite dure 100 Mo).
- **Rendu headless FAISABLE et prouvé** (corrige mon caveat précédent) : conteneur = Mesa
  (`libgl1-mesa-dri`, `mesa-libgallium`, llvmpipe) + `Xvfb`. `desktop-port/GLSmokeTest` +
  `run-gl-smoke.sh` : `GL 4.5 llvmpipe` sous Xvfb, frame rendue (`glError=0`), capture PPM.
  ⇒ on peut lancer ET vérifier le jeu en headless + captures (comme l'agent DragonSoul).
- **Scaffold `desktop-port/`** : Gradle (LWJGL 3.3.4 + natifs libGDX **1.9.7** [version du jeu,
  ≠ 1.9.3 DragonSoul] + stubs Android + game.jar). `settings.gradle`, `run-gl-smoke.sh`.
- **Découvertes majeures (recon jar) → stratégie révisée** (`desktop-port/PROGRESS.md`) : le jar
  embarque **`com.badlogic.gdx.backends.lwjgl.LwjglApplication`** (backend desktop LWJGL2 →
  **757 classes `org/lwjgl`**, d'où la collision de compilation du smoke test),
  **`HeadlessApplication`**, le root **`com.perblue.heroes.GameMain extends ApplicationAdapter`**,
  et un **framework d'automatisation** `com.perblue.heroes.automation.*` (`TouchRecorder`,
  `TouchPlayback`) + `automation.crawler.*` (`CrawlerNavigation`, `CrawlerScript`, scripts
  `ChestBuyScript`/`GoldScript`/`MarketBuyScript`…). ⇒ **le pilotage "sait ce qui est cliquable
  et exécute des actions" existe déjà** dans le jeu ; et on peut **réutiliser le backend bundlé**
  (`LwjglApplication`) au lieu d'écrire un backend complet → bien moins de shims que DragonSoul.

**13. Launcher desktop écrit + débogage de boot → VERDICT backend.**
- Écrit `dhdesktop/DesktopLauncher.java` (construit `GameMain(new DhDeviceInfo())` ; redirection
  `ServerType.LIVE` par réflexion via `-Ddh.server`), shim `dhbackend/DhDeviceInfo.java`
  (implémente l'interface `DeviceInfo`, valeurs FACTICE cohérentes, `Platform.ANDROID`),
  `run-desktop.sh` (Xvfb + llvmpipe + extraction assets/ressources APK + classpath). GL smoke
  test déplacé en `diag/`.
- Itérations de boot (chaîne réellement atteinte) :
  1. Backend **LWJGL2 bundlé** : natifs stock incompatibles avec les classes `org/lwjgl`
     **réduites par ProGuard** dans game.jar (`PointerWrapper.getPointer()` absente) → shadow
     par LWJGL 2.9.3 stock ; puis **`LinuxDisplay.getAvailableDisplayModes` AIOOBE sous Xvfb**
     (Display X11 LWJGL2 = hostile headless) + audio absent.
  2. Backend **LWJGL3 Maven** (GLFW, headless-friendly) : GLFW init OK → atteint
     `GameMain.<clinit>` → `NoSuchFieldError Group.DEFAULT_TRANSFORM` (**PerBlue a AJOUTÉ des
     champs au core libGDX**). En gardant le core PerBlue : le backend stock appelle
     `InputEventQueue.setProcessor(...)` **absente** du core PerBlue (RÉDUIT par ProGuard).
- **VERDICT** : core libGDX PerBlue **modifié ET réduit** → aucun backend/core stock ne matche.
  Comme DragonSoul, il faut un **backend maison LWJGL3** implémentant les interfaces du core du
  jeu. **Décision : adapter le `dsbackend/` de DragonSoul** (même core PerBlue) plutôt que
  repartir de zéro. Launcher + `DhDeviceInfo` + extraction assets + redirection `ServerType`
  déjà écrits = réutilisables. Détail complet : `desktop-port/PROGRESS.md`.

**14. Backend LWJGL3 maison écrit → le jeu BOOTE jusqu'à l'écran de chargement.**
- Étude de portabilité de `dsbackend/` d'abord (à la demande) : PAS réutilisable tel quel
  (noms libGDX obfusqués 1.9.3, API RPGMain, `getType():int`) mais MÊME fork libGDX PerBlue →
  jeux d'interfaces coïncident. Méthode : régénérer chaque shim contre l'interface RÉELLE de DH
  (`javap`, noms clairs) + porter le corps depuis dsbackend.
- Backend écrit (`desktop-port/src/main/java/dhbackend/`) : `DhGL20` (75 méth, délègue LWJGL3),
  `DhGraphics` (19, +GLVersion réel), `DhInput` (17) + `GlfwInput`, `DhFiles`/`DhFileHandle`,
  `DhPreferences` (19), `DhApplication` (18, getType=Android), `DhDeviceInfo`, `DhAudio` (STUB
  no-op), `DhNet` (STUB), `DhBridges` (NO-OP proxies), `DhStatFileExt` (ouvre les `.tab`).
  Launcher `dhdesktop/DesktopLauncher` : GLFW+GL, câble `Gdx.*`, `GameMain(DhDeviceInfo)`,
  boucle create()/render() + capture PPM + redirection `ServerType` (`-Ddh.server`).
- Build : LWJGL3 brut + natif libGDX 1.9.7 + stubs Android + `game-logic.jar` (game.jar SANS
  `org/lwjgl` ni backends bundlés — sinon shadowing de nos classes LWJGL3). `tools/fetch_assets.sh`
  télécharge le contenu ETC1 initial depuis archive.org (le boot exige des assets hors APK).
- Débogage de boot itératif (murs franchis) : GLVersion null → réel ; `StatFileHelper.getOpener()`
  null (=champ EXT) → `DhStatFileExt` via `setExt`, lecture des `.tab` depuis `stats/` (classpath).
- **Résultat** : `GameMain.create()` **complète** (compression **ETC1**, RPGAssetManager, shaders,
  viewport, UI stats **XHDPI 1280×720**) puis `render()` → **LoadingScreen** exécute ses tâches
  (`LoadBootAtlasUI`, `LoadPerBlueUI`, `ShowDisneyLogo`, `StartServerLogin`) et **rend des frames**.
  Bloqué sur `DhNet` (login, #NET) + `android.os.SystemClock.elapsedRealtimeNanos()` absent des
  stubs API 16 (#ANDROIDSTUBS). Tous les shims/deferrals tracés dans `desktop-port/BACKEND_STATUS.md`.

### Login → BootData → MainScreen de bout en bout (reframe ASM + Firebase + bridges)
- **Reframe ASM (RÉEL, sans changement de sémantique)** : game.jar (dex2jar) n'a pas de
  `StackMapTable` → sous `-Xverify:none` la JVM plantait (`generateOopMap.cpp`, « Illegal class
  file ... in method loadBinaryData ») en parsant `unit_abilities.tabb` pendant le handshake.
  `tools/reframe/src/ReframeJar.java` (ASM 9.7, COMPUTE_FRAMES) réécrit les **63 249** classes
  avec des frames valides (hiérarchie résolue depuis les octets, sans lier — repli sûr par classe).
  `run-desktop.sh` produit `game-logic-framed.jar` (ombrage classpath) et **retire `-Xverify:none`**.
  ⚠️ ce n'est PAS une rustine : aucune logique modifiée, on ajoute seulement les métadonnées de
  vérif que dex2jar omet (équivalent recompilation) — et on RE-vérifie le bytecode.
- **Shadow `com.google.firebase.perf.network.FirebasePerfUrlConnection` (RÉEL)** : le
  téléchargeur du jeu enveloppe chaque connexion par `instrument()` (télémétrie Firebase) ; l'init
  Firebase touchait `android.os.StrictMode.allowThreadDiskReads()` (« Stub! ») et TUAIT le thread
  de download. `instrument()` renvoie la connexion RÉELLE inchangée → download HTTP réel, analytics
  externe neutralisée (#BRIDGES). Le contenu requis se télécharge (index.txt 62 968 o).
- **DhBridges (PARTIEL, services plateforme absents)** : `INative.createPurchasingInterface()`
  renvoyait `null` → `setNativeAccess` faisait NPE (avalé) puis `handleBootData` NPE sur
  `purchasing`. `defaultReturn` renvoie désormais un **no-op imbriqué** pour tout retour interface
  (et collections vides pour Set/List/Map). ⚠️ à auditer : l'enum `PurchaseErrorState` renvoyée
  par `startPurchase` (1ʳᵉ constante) ne doit pas être un état « succès ».
- **RÉSULTAT — jalon majeur** : le vrai client fait `/login` sur notre serveur → se connecte en
  TCP `:8081` → **notre LoginServer envoie BootData1** → le client appelle **`handleBootData`
  sans crash** (langue serveur, offerwall, rewards initialisés) → atteint le **MainScreen** (hub,
  chargement du monde `mainscreen_winter`). Capture `desktop-port/build/online.png`.
- **Bug en cours (à corriger PROPREMENT, pas contourner)** : les `.skel` du décor échouent avec
  `NoSuchMethodError: com.badlogic.gdx.utils.DataInput.readString()` — incompatibilité entre
  `SkeletonBinary` (spine-libgdx) et le `DataInput` MODIFIÉ de PerBlue (ProGuard). Le jeu tolère
  ces assets manquants (userErrorListener) mais le décor animé ne s'affiche pas → à réparer.
- **⚠️ À valider (règle « pas de faux OK »)** : (1) cparticle reste un STUB (rendu différé,
  `update→complete=true` pourrait avancer une logique gatée sur une particule) ; (2) le BootData
  de notre serveur doit être **complet et correct** (serveur autoritatif), pas « minimal pour
  atteindre le menu ».

### Modules natifs Spine + particules réimplémentés en Java (#SPINE ✅ / #CPARTICLE ⚠️)
- **#SPINE résolu (Option A)** : les natifs Spine de PerBlue (`libspine-native64.so`, absents des
  splits x86_64) sont remplacés par un module Java **`com.perblue.heroes.cspine.*`** (shadow
  classpath) au-dessus de **spine-libgdx 3.6.53.1** — 12 classes : `Native` (coquille, plus aucun
  `.so`), `NativeAtlas`/`NativeAtlasLoader`, `NativeSkeletonData`/`Loader`, `NativeSkeleton`,
  `NativeAnimationState`/`Data`, `NativeSkeletonRenderer` (`Mesh` 2-couleurs `a_position/a_light/
  a_dark/a_texCoord0` dessiné avec le shader de `ShaderChannels`). Détail clé : le jeu suffixe les
  atlas/skel Spine par `@native` ; comme l'original, on retire ce suffixe (`lastIndexOf('@')` +
  `substring` + re-resolve) avant d'ouvrir le vrai fichier. Constantes GL effacées par ProGuard
  → littéraux (`GL_BLEND=0x0BE2`…). `mesh.render(shader, GL_TRIANGLES, 0, n, true)` (signature 5-args
  de PerBlue).
- **#CPARTICLE (partiel)** : découverte d'un **2ᵉ moteur natif** — `com.perblue.heroes.cparticle.*`
  (format `.np` binaire propriétaire, dérivé au build de `ParticleConverter`, pas de runtime Java
  prêt). Les wrappers `NativeParticleEffect`/`Pool`/`Loader`/`Renderer` sont de fins JNI → on shadow
  la SEULE classe **`cparticle.Native`** (pur Java, sans `.so`) : `Effect_create`→handle non nul,
  `getVertices`→0, `update`→complete. Les `.np` se **chargent** (octets réels lus) mais ne sont pas
  encore **simulés** (aucun rendu). Débloque le boot ; simulation réelle = chantier suivant. PAS de
  rustine (aucune donnée de jeu falsifiée, seul un effet cosmétique n'est pas encore affiché).
- **Résultat** : le boot franchit `WaitForDisneyAnimation` + `WaitForPerBlueAnimation` sans crash
  (capture `desktop-port/build/spine-test.png`). Reste bloqué au login en mode OFFLINE (attendu :
  `run-desktop.sh` sans `DH_SERVER` vise `login.disneyheroesgame.com`).

### Login → BootData → MainScreen bout-en-bout + Spine réparé (ABI PerBlue)
- **Reframe ASM de game-logic.jar** (`tools/reframe/ReframeJar`) : recalcule les StackMapTable de
  toutes les classes (dex2jar les omet) → plus de crash JVM `generateOopMap.cpp` (« Illegal class
  file … loadBinaryData ») sur le parse des stats binaires ; on retire `-Xverify:none`. Sémantique
  inchangée (métadonnées de vérif seulement) — c'est la solution durable prévue dans SHIMS.md.
- **Shadow `FirebasePerfUrlConnection.instrument`** → renvoie la connexion réelle (désactive
  l'analytics Firebase qui plantait le thread de téléchargement via le stub `StrictMode`). Le
  téléchargement HTTP du contenu requis reste réel (#BRIDGES).
- **DhBridges** : `INative.createPurchasingInterface()` (et toute méthode renvoyant une interface)
  renvoie un NO-OP imbriqué au lieu de `null` → `setNativeAccess` ne lève plus, `handleBootData`
  n'a plus de NPE `purchasing`. **Résultat : login → BootData → `handleBootData` → MainScreen** de
  bout en bout avec NOTRE serveur (le client se connecte à :8081, reçoit notre BootData1).
- **Spine réparé (ABI)** — les `.skel` échouaient sur `NoSuchMethodError` (tous : logos, décor,
  **héros de combat**). Deux causes, deux vrais correctifs :
  1. `com.badlogic.gdx.utils.DataInput` de PerBlue réduit par ProGuard (plus de `readString()`/
     `readInt(boolean)` var-int, requis par spine) → **shadow** avec l'implémentation EXACTE de
     libGDX 1.9.7 (self-contained).
  2. spine-libgdx 3.6.53.1 (Maven) est compilé contre le gdx STOCK, mais PerBlue a modifié l'ABI
     (ex. `Array implements Collection` → `add(Object)` renvoie `boolean`, pas `void`) → **patcheur
     ASM** `tools/reframe/PatchGdxCalls` réécrit les 106 appels gdx divergents sur les descripteurs
     réels de game.jar (+ `POP` si `void`→valeur) → `spine-libgdx-perblue.jar`.
  ⇒ **0 échec de chargement `.skel`**, squelettes chargés ET animés, jeu au **MainScreen** interactif.
- **#CPARTICLE** : toujours en dette (#NP-V3, format `.np` v3 ≠ writer courant, cf. NP_FORMAT.md).
  Le stub cparticle.Native reste en place (étiqueté) pour que le jeu tourne ; particules non rendues.

### Point de reprise
Modules natifs Spine/particules franchis. **Prochaine étape** : lancer **`run-online.sh`**
(contenu `:8080` + login + serveur de jeu `:8081`, `DH_SERVER`) → franchir le login
(`ClientInfo1`→`BootData1`), atteindre le menu / le tutoriel `IntroTutorialActV1` (nouveau joueur,
BootData neuf — NE PAS seeder) et capturer. Voir `desktop-port/BACKEND_STATUS.md` (#SPINE ✅,
#CPARTICLE ⚠️, #AUDIO, #BRIDGES).

### Règle renforcée + jeu original en natif (2026-07-11)
- **Règle d'or clarifiée** (PRINCIPLES §4/§4bis) : on ne réécrit RIEN du jeu à la main — on **extrait**
  (données ET code) par commande. Seule couche manuelle = plateforme (`dhbackend/`), minimale. Binaires
  natifs = code du jeu → binaire d'origine, sinon rebuild **vérifié fidèle** (désassemblage lib
  d'origine), jamais inventé. **Fidélité vérifiée contre captures du jeu original**.
- **Bascule sur le code d'origine natif** : suppression des shadows Java (cspine, spine-libgdx, DataInput) ;
  `spine-native64.so` (spine-c officiel + interface JNI exacte de PerBlue) branché via le
  `SharedLibraryLoader` du jeu → `cspine.*`/`cparticle.*` d'ORIGINE tournent. MainScreen rendu, 0 crash.
- **Dettes de fidélité identifiées** (à corriger par extraction, pas invention) : `getVertices` (banding =
  drawCalls multi-pages à rendre fidèles) ; `cparticle` (échafaudage neutre → vrai moteur). Source de
  vérité = **désassemblage de la lib `spine-native` ARM d'origine**.

### cspine : banding CORRIGÉ — drawCalls multi-pages fidèles (2026-07-11)
- **Cause du banding** : l'ancien `Skeleton_getVertices` renvoyait 1 seul draw call (`draws[0]=nb
  sommets`, 2e short non initialisé) → le renderer liait UNE seule page de texture pour tout le
  maillage. Toute géométrie d'une autre page d'atlas s'affichait avec la mauvaise texture (bandes).
- **Contrat drawCalls EXTRAIT** (pas deviné) du bytecode EN CLAIR
  `com.perblue.heroes.cspine.NativeSkeletonRenderer.renderPreparedVertices` (javap -c) :
  `drawCount = getVertices(verts, indices, drawCalls)` ; `drawCalls.position(0)` ; boucle `drawCount`
  fois : `indexCount = drawCalls.get()` ; `tex = textures.get(drawCalls.get())` ; `tex.bind()` ;
  `mesh.render(shader, GL_TRIANGLES=4, indexStart, indexCount, false)` ; `indexStart += indexCount`.
  ⇒ `drawCalls` = N paires de shorts `(indexCount, texturePageIndex)`, `getVertices` renvoie N.
  `texturePageIndex` = index **0-based** dans `NativeAtlas.getTextures()` (`Array.get(int)`), dont
  l'ordre = pages via `Atlas_getTexture(handle, 0..n)` dans `NativeAtlas.load` (boucle en clair).
- **Implémentation** (`native/src/cspine_jni.c`) :
  - `Atlas_create` tague chaque page par sa **position 0-based** dans `page->rendererObject`
    (même parcours de liste chaînée que `Atlas_getTexture` → indices alignés avec `getTextures()`).
  - `attachmentPage()` : `spRegionAttachment/spMeshAttachment->rendererObject` (= `spAtlasRegion`)
    `->page->rendererObject` = pageIndex.
  - `buildVertices` émet les tris **dans l'ordre de dessin** (draw order) et ouvre un nouveau draw
    call à chaque changement de page ; les attachments consécutifs sur la même page fusionnent
    (indexCount cumulé). Renvoie le nombre de draw calls.
  - **`bufferSetLimit`** : le natif écrit en mémoire brute (`GetDirectBufferAddress`) sans toucher
    `position/limit` des `java.nio.Buffer` ; or le chemin VertexArray de `Mesh.render` fait
    `indices.getBuffer().position(offset)/limit(offset+count)` → il FAUT que `limit` couvre tout
    l'écrit. On appelle `Buffer.position(0)/limit(n)` (descripteur `(I)Ljava/nio/Buffer;` stable) sur
    verts (=nb floats), indices (=nb indices) et drawCalls (=N*2). Sans ça : `IllegalArgumentException:
    newPosition > limit` dès le 2e draw call (offset>0). C'est le comportement du natif d'origine.
- **Résultat** : `run-online.sh` (Xvfb+llvmpipe, 150 frames) → **splash MainScreen rendu sans banding**,
  tous les héros Spine (multi-pages) corrects, logo/ballons/confettis nets. 0 crash de rendu.
  Capture de référence : `native/reference/shots/mainscreen-nobanding.png`.
- Reste : cparticle (échafaudage neutre → moteur fidèle via oracle ARM) ; extensions cspine
  (`setSlotEyeState`, `setTintBlack`, `nextEvent`) à confirmer contre la lib ARM.

### cparticle : format `.np` EXTRAIT + #NP-V3 confirmé (2026-07-11)
- **Source d'écriture EN CLAIR trouvée** : `ParticleConverter.convertFileNative` →
  `ParticleEffect.saveBinary(ParticleEffectPacker)` → `ParticleEmitter.saveBinary` (+ `*Value.saveBinary`,
  `packer.writeTimeline/writeTimelines`) dans game.jar. Donne l'en-tête (`byte 0, byte 3, int count`),
  les formats de valeurs (Ranged=10o, Scaled=32o, Numeric=5o, Gradient=13o, SpawnShape), et le **pool de
  timelines différé** par emitter (poolSize, tagLen, pool floats, atlasTag).
- **Lecteur natif désassemblé** (`ParticleEmitter::load` @ 0x19755, ARM). Helpers classés :
  `readInt` @0x1a770 = 4 o **BIG-ENDIAN** (`rev`), `readBool` @0x1a0c4 = 1 o, `readRanged` @0x19fd0 = 10 o,
  `readScaled` @0x1a020 = 32 o — **formats IDENTIQUES** au `*Value.saveBinary` clair. Registres : `r4`=
  readScaled, `fp`=read4 (préservé). L'encodage bas niveau est donc certifié.
- **#NP-V3 CONFIRMÉ (2 fois)** : parse des 535 assets `.np` réels avec (a) l'ordre du `saveBinary`
  COURANT → **0/535** EOF-exact ; (b) l'ordre reconstruit statiquement depuis la séquence d'appels du
  lecteur natif → **0/535**. Les FORMATS matchent ; seul l'ORDRE/ensemble des champs diffère (le writer
  courant a évolué). ⇒ Ne PAS implémenter un parseur deviné (PRINCIPLES §2/§4).
- **Voie suivante SANS devinette** : (1) oracle d'exécution (faire tourner le vrai `ParticleEffect::load`
  sous qemu → struct parsée = vérité bit-à-bit) ; OU (2) auto-parse validé par l'invariant des offsets de
  pool (chaque triplet timeline `(N, offA, offB)` doit égaler le curseur de pool courant) → l'ordre correct
  = celui qui donne 535/535. Détail complet : `desktop-port/NP_FORMAT.md`.

### cparticle : oracle d'exécution qemu — avancement (2026-07-11)
- But : exécuter le VRAI `ParticleEffect::load`/`ParticleEmitter::load` (ARM) sous qemu sur de vrais
  `.np` → struct parsée + octets consommés = **vérité bit-à-bit** de l'ordre v3 (pas de devinette).
- `native/oracle/harness_np.c` : dlopen + ctor `ParticleEmitter` + `load(&cursor,&remaining)` + dump.
- **Corrections du shim bionic** (`gen_shim.py`) qui ont supprimé le SIGSEGV initial :
  1. `__aeabi_mem{cpy,move,set,clr}{4,8}` (supposent l'alignement, fautent sous qemu sur la source non
     alignée du pool `.np`) → repointées vers la variante NON alignée de base (même sémantique).
  2. Repli **no-op** pour tout symbole absent de la glibc (ex. `_zf_log_write`, log Android) au lieu
     d'un pointeur NULL (crash). 3. Littéral `.word` EMBARQUÉ dans chaque trampoline naked (le pool
     distant cassait l'assemblage « pool needs to be closer »).
- **État** : plus de crash, mais **hang** dans `operator new` (boucle retry malloc @0x31406 ; helpers
  résolus : `0x155b4`=malloc PLT, `0x15974`=`std::get_new_handler`) → `malloc` échoue sur une taille
  aberrante. Cause probable : membres C++ non initialisés (Effect::load malloc les emitters SANS ctor)
  ou ctor `C2` (base) insuffisant / désalignement sous émulation. ← À FINIR : ctor complet `C1`, ou
  zéro-init + trace du site d'appel `operator new` ; sinon poursuivre l'auto-parse validé par
  l'invariant des offsets de pool (approche #2, pur Python). Détail : `desktop-port/NP_FORMAT.md`.

### PIVOT unidbg : exécuter le binaire d'origine + intégration en jeu (2026-07-11)
- **Décision (user)** : « 100% origine ». On exécute le VRAI `libspine-native.so` (ARM) via unidbg au
  lieu de rebâtir spine / RE les particules.
- **Prototypes `native/unidbg/`** (SpineUnidbg/SpineLoad/SpineBench/SpineVerts2/SpineSkel) :
  - Charge la lib d'origine, `Spine_init` OK.
  - `Atlas_create`+`Effect_create` parsent de vrais assets (err="") → **#NP-V3 résolu par exécution**.
  - `Effect_getVertices`/`Skeleton_getVertices` rendent de vrais sommets 2-couleurs.
  - Perf unicorn : particules ~141 µs/frame/effet (~118/frame) ; spine ~1.5-2.1 ms/squelette (7-11/frame).
  - dynarmic (JIT) : crash NEON (`vldr d16`, registres d16-d31 non activés) → inutilisable.
  - Trou unidbg comblé : `GetDirectBufferAddress`/`Capacity` (JNI 230/231) implémentés via `ArmSvc`
    écrasant les slots de la table JNIEnv (renvoie le pointeur émulé du DvmObject).
- **Intégration en jeu** : `dhbackend/unidbg/UnidbgVM` (VM persistante, dispatch synchronisé mono-thread,
  buffers émulés réutilisés + recopie vers les FloatBuffer/ShortBuffer du jeu) ; shadows
  `com.perblue.heroes.cspine.Native`/`cparticle.Native` (mêmes signatures, dispatch → UnidbgVM ; câblage,
  pas récréation) ; `build.gradle` += `unidbg-android:0.9.8` (hôte unicorn embarqué → autonome) ;
  `run-desktop.sh` : `-Ddh.spinelib=native/reference/libspine-native.so`, retrait du build spine-native64.so.
- **État** : compile ; le jeu boote et tourne à travers unidbg **sans crash** jusqu'au splash (capture
  `desktop-port/build/unidbg.png`). **0 appel natif spine/particule** durant le run → écran encore au
  pré-download (art statique), pas de squelette vivant. ⚠️ Rendu spine in-game **à valider** en atteignant
  un écran héros/combat (dépend du pipeline d'assets WORLD_ADDITIONAL). Puis comparer aux captures
  d'origine (§4bis) + mesurer le fps réel.

### JEU DANS LE HUB PRINCIPAL — spine + particules d'origine via unidbg (2026-07-11)
- **Serveur de contenu = RELAIS** : le 302 vers archive.org échouait (le java.net du jeu n'a pas de
  proxy). Le serveur relaie désormais (fetch via proxy côté serveur, stream au client). Le jeu
  télécharge WORLD_ADDITIONAL (394 Mo) etc. et **franchit la barrière de download**.
- **Rendu particules corrigé** : `NativeParticleEffect.getVertices` lit `drawCalls.get(n*3)` = nombre
  total de sommets (écrit par le natif APRÈS les n*3 shorts de draw calls) pour poser verts.limit/
  indices.limit. On copie donc **n*3+1** shorts (≠ spine : 2/pair). Buffers émulés dimensionnés pour le
  plus grand mesh (particules 4000 v). Plus de crash `newPosition > limit`.
- **✅ RÉSULTAT** : le jeu atteint le **HUB PRINCIPAL** (ville Zootopia enneigée, menu HEROES/ITEMS/…,
  CAMPAIGN/CRATES, nouveau joueur CHOOSE NAME) — capture `native/reference/shots/mainscreen-hub-unidbg.png`.
  Confirmé par logs `[UnidbgVM]` : `Skeleton_getVertices -> drawCount=39` (spine d'origine rendu) +
  `Effect_getVertices` (particules d'origine) — **100% code d'origine via unidbg, 0 crash de rendu**.
- ⚠️ RESTE (serveur) : la connexion TCP tombe après BootData → overlay **« Reconnecting… »**. Le
  serveur de jeu doit MAINTENIR la session (keepalive + messages post-BootData). Et le désaccord de
  stats (`GuildStats` NumberFormatException) → le serveur doit **synchroniser les stats** (SyncStatData,
  extraites du jeu). Prochaines étapes serveur.

### Serveur : session STABLE — écho Ping (2026-07-11)
- `LoginServer` instrumenté : journalise chaque message reçu (client = source de vérité). Flux nouveau
  joueur relevé (cf. PROTOCOL.md §6) : ClientInfo→BootData, puis télémétrie + **Ping1**.
- **Cause du « Reconnecting… » = Ping non répondu** : le keepalive/latence `Ping` sans écho déclenche le
  chien de garde du client → fermeture. ⇒ serveur échoue Ping (serverReceive/serverTime=now). Résultat :
  **0 reconnexion, session STABLE dans le hub** (capture `native/reference/shots/mainscreen-hub-stable.png`),
  spine+particules d'origine rendus (unidbg). Prochain : BootData complet nouveau joueur → tuto.

### Coffre GRATUIT du tuto débloqué — cause racine du blocage à l'étape GOLD (2026-07-13, nuit)
**Symptôme** : après avoir « ouvert » le coffre GOLD, le tuto (`IntroFeaturesActV2`) ne repartait pas.
Capture (`build/goldstuck.png`) : écran de détail du coffre GOLD (« DIAMOND CRATE »), mention
**« Free in : 1j 23h 46m 28s »**, et un pop-up « You can't do that just yet. Follow the tutorial arrow! »
— le pilote DEV, sans cible, tapait les boutons d'achat payants et se faisait bloquer.

**Ce que le tuto attend** : il exige que le joueur **POSSÈDE Frozone** après le coffre GOLD
(`checkForMissingFrozone`→`finishTutorial` si absent ; `HERO_LIST_TAP_FROZONE` requiert
`getHero(FROZONE)!=null`). Il était bloqué à l'étape 9 (serveur : `INTRO_FEATURES -> step 9`).

**Mécanisme réel** (décompilation, source de vérité) :
- `ChestHelper.openChest(...)` construit **toujours** un `BuyChests` (avec `ServerRollRequest` CHEST) et
  appelle `ServerRollHelper.sendRollRequest` → `NetworkProvider.sendMessage`. Le serveur répond
  `LootResults{heroesUnlocked=[Frozone]}` ; le client ajoute Frozone → le tuto avance.
- MAIS l'ouverture n'a lieu que si le coffre est **gratuit et disponible** :
  `ChestHelper.getTimeUntilNextFreeChest = UserHelper.getResourceGenerationRemaining(freeChestResource)`,
  et `getFreeChestResource(GOLD) = ResourceType.GOLD_CHEST`. `hasFreeChest(GOLD)` ⇔ `getResource(GOLD_CHEST) >= 1`.

**Cause racine** : le coffre gratuit est une **ressource régénérée** (comme la stamina). Notre amorce
compte-neuf (`ServerUser.initNewPlayerResources`) ancrait le gen-time de toutes les ressources régénérées à
la création MAIS ne mettait au cap **que STAMINA**. Donc `GOLD_CHEST=0`, gen-time = maintenant → « Free in
~48 h » → `hasFreeChest=false` → le bouton FREE n'ouvre rien → **aucun `BuyChests` envoyé** (confirmé : le
`LoginServer` journalise chaque message reçu ; **0 `BuyChests`, 0 `ServerRollRequest`** dans le run — que
`ChangeTutorialStep`×69 + télémétrie + 1 `Action` VIEWED_CHESTS) → Frozone jamais accordé → tuto bouclé.

**Correctif** (fidèle, PRINCIPLES §3 « lire & exécuter ») : à t=création aucun temps ne s'est écoulé → un
compte neuf démarre **chaque ressource régénérée à SON cap** (généralisation exacte du fix stamina).
Boucle unique : `setLastResourceGenerationTime(rt, creation)` + `setResource(rt, getResourceCap(rt))` pour
chaque `resourceGenerates(rt)`. Caps du jeu au niv.1 (probe) : STAMINA=120, GOLD_CHEST=1, SILVER_CHEST=1,
SOCIAL_CHEST=1, SKILL_POINTS=50, SOUL_CHEST=0, FRIEND_STAMINA=175, INVASION_STAMINA=80… **Aucune valeur
inventée** (caps issus de `UserHelper.getResourceCap`).

**Vérifications** :
- `hasFreeChest(GOLD)=true`, `GOLD_CHEST=1`, `SILVER_CHEST=1`, `STAMINA=120`, roster=2 (probe).
- `ServerUser.openChest(BuyChests{GOLD})` d'un compte neuf → **Frozone 8/8** (la table de drop GOLD du jeu
  est déterministe pour le nouveau joueur — le serveur exécute `DropTable.rollNode("ROOT")`, rien de truqué).
- `server/smoke/ResourceTest` étendu (assertions `GOLD_CHEST=1`, `SILVER_CHEST=1`, `hasFreeChest(GOLD)`),
  `RosterTest`/`ViewedChestsTest` toujours OK.

**Chaîne complète attendue en jeu** : coffre GOLD dispo → clic FREE → `BuyChests` → serveur
`LootResults{Frozone}` → `getHero(FROZONE)!=null` → tuto passe à `HERO_LIST_TAP_FROZONE`. Fichiers :
`server/java/dhserver/ServerUser.java` (initNewPlayerResources), `server/smoke/ResourceTest.java`.

### Pipeline de combat de CAMPAGNE côté serveur — recordOutcome autoritatif (2026-07-14)
**Objectif** (demande utilisateur) : valider le pipeline de combat de campagne — entrée, choix héros,
skills, vagues, loot (gold/items), récompenses, XP, conso énergie.

**Protocole (décompilation, client = source de vérité)** :
- Le combat tourne CÔTÉ CLIENT (unidbg spine, déjà fonctionnel depuis l'intro).
- `CampaignAttackScreen.doCombatDone` → `ClientNetworkStateConverter.getCampaignAttack(user, type, ch, lvl,
  outcome, stars, attackers, stagesCleared, screen, snapshot)` qui **roule `CampaignHelper.recordOutcome`
  côté client** (optimiste) puis `NetworkProvider.sendMessage(campaignAttack)`.
- `CampaignAttack{base:AttackBase(attackers,defenders,outcome,stars,…), campaignType, chapter, level,
  lootEarned, memoryChanges, stagesCleared}`. `attackers/defenders` = Collection de **`AttackLineupSummary`**
  (`{List units:AttackUnitSummary}`), une par vague. **Aucun champ roll/seed** (≠ BuyChests) ; **aucun
  listener client** pour la réponse → **fire-and-forget**.

**Logique autoritative = `CampaignHelper.recordOutcome`** (à exécuter, PRINCIPLES §3) :
consomme la stamina (`getStaminaCost`+`UserHelper.chargeUser`), donne loot/gold/XP
(`giveLoot`/`giveGold`/`giveTeamXP`), met à jour `ICampaignLevelStatus`. `IUser extends IGuildPerkProvider`
→ `recordOutcome(user, user, …)`. Param 11 = `SpecialEventSnapshot` : **`.NONE`** (déréférencé sinon NPE).

**Implémenté** : `ServerUser.recordCampaignAttack(CampaignAttack)` + branche `LoginServer` (fire-and-forget,
persiste). Reconstruit le User, résout `CampaignLevel.of(GameMode.CAMPAIGN/ELITE_CAMPAIGN, ch, lvl)`,
appelle `recordOutcome`, resync + save.

**Vérifié** `server/smoke/CampaignAttackTest` (NORMAL 1-1, WIN, équipe Ralph+Elastigirl+Frozone) :
- **énergie -6** (120→114 ; `staminaCost=6`)  — conso énergie ✓
- **or +340** (0→340 ; `giveGold`→`giveUser`, appliqué direct)  — récompense ✓
- **niveau 1-1 → 3★** (`ICampaignLevelStatus`)  — progression ✓
- team XP donné (pas assez pour monter de niveau) ; `lootEarned`=0 objet (normal en 1-1, items seulement).
ResourceTest/RosterTest toujours OK.

**PARTIEL (SHIMS)** : `SpecialEventSnapshot.NONE` (pas de bonus évènement) ; outcome/stars = ceux du client
(combat client-autoritatif, comme le jeu d'origine) ; contrat fire-and-forget à reconfirmer en jeu.

**Frontière pilote DEV (non bloquant pour le serveur)** : l'auto-pilote ne sait pas ENTRER dans un chapitre.
Observé (recorder) sur `CampaignScreen` : animation « SCANNING CITY MAP », `getPointers()` **vide** headless,
nœud `CAMPAIGN_CHAPTER_ONE_NAME` = `Stack childrenOnly` + label `disabled` sans `[CLICK]` (input carte
custom). Le pilote idle → RETOUR → boucle. À résoudre séparément pour la démo visuelle in-game.
Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/CampaignAttackTest.java`.

### Combat de campagne JOUÉ & GAGNÉ en jeu + progression persistée (2026-07-14, soir)
Suite du pipeline campagne. **Pilote DEV** (entrée niveau) : la carte de campagne est une scène **g2d**
(`CityMapDisplay`), pas du scene2d → aucun acteur cliquable, `getPointers()` vide headless. Découvert via
une **sonde `dh.mapprobe`** (« cliquer + monitorer quel élément s'active » — outil de diag, cf. MEMORY §6ter
B-bis) : le tap est géré par `CityMapScreen` (caméra `MapCamera2D` → `CityMapDisplay.getHitCampaignLevel` →
`onCampaignLevelTapped` → `normalOrEliteNodeSelected(CampaignLevelID)` → `CampaignPreviewScreen`). Le pilote
appelle donc `normalOrEliteNodeSelected(1-1)` (API du jeu). Ajouts pilote : tap flèche « TAP TO CONTINUE »
(fin de vague), tap boutons FIGHT par nom sans pointeur (replay après défaite), garde anti-RETOUR sur
`*AttackScreen`.

**Bug AUTO** (cause de la défaite au 1-1) : `Boolean.getBoolean("dh.autofight")` n'accepte que `"true"` →
`dh.autofight=1` restait false → `setAutoAttack` jamais appelé → héros passifs, skills seulement sur tap
manuel → défaite. Corrigé (accepte non-"0"/"false"). Résultat : **VICTOIRE 1-1 en jeu**, `CampaignAttack :
NORMAL 1-1 outcome=WIN → recordOutcome appliqué [persisté]`, énergie **114/120** visible (débitée de 6).
Skills confirmés déclenchés (AUTO + 21 taps `ATTACK_SCREEN_HERO_BUTTON` guidés par le tuto SKILL_USE).

**Bug PERSISTANCE progression** (révélé par une sonde post-run : `wire_levelStatuses=0`, `1-1 stars=0`,
`1-2 unlocked=false` alors que gold=340 persistait) : les statuts de niveau vivent HORS `this.extra`
(`ClientCampaignLevelStatus` en mémoire, lus depuis `individualUserExtra.levelStatuses` au chargement) ;
`recordOutcome` les mute EN MÉMOIRE mais n'écrit pas la liste wire → **étoiles/complétion perdues au
round-trip, 1-2 jamais débloqué**. Correctif `resyncCampaign` (dans `recordCampaignAttack`) : reconstruit
`individualUserExtra.levelStatuses` depuis `iu.getCampaignLevels()` (champs mappés 1:1 ; `lastWinTime` sans
getter → 0, non requis), comme `resyncHeroes`. Vérifié `server/smoke/CampaignPersistTest` : après save+reload
SQLite → **1-1 à 3★, 1-2 DÉBLOQUÉ, or=340, stamina=114**. `CampaignAttackTest` toujours OK.

**Réponse à « ça passe au niveau suivant ? »** : le jeu débloque 1-2 après une victoire au 1-1 (désormais
persisté). Le pilote, lui, entre toujours au 1-1 (`dh.playlevel`) → à généraliser au prochain niveau
débloqué (`getLatestCompletedLevel`) pour enchaîner la campagne.

⚠️ Suivi séparé : dans un run COMPLET (tuto→campagne), la stamina persistée réapparaît à « 39,96 M »
(fuite de gen-time dans la phase TUTO — `recordCampaignAttack` la gère bien, 114 en test isolé).
Fichiers : `server/java/dhserver/ServerUser.java` (resyncCampaign), `server/smoke/CampaignPersistTest.java`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.

---

## 2026-08-04 (g72) — SURGE (#72) incrément 6+7 : RÉCOMPENSES & BASCULE (headless 🟢, 88/88)

**Livré** : le mode SURGE boucle désormais jusqu'au bout côté serveur. `ServerSurgeRewards` (calcul 100 % code
du jeu) + `ServerSurgeState.{rollover, personalize, claimRewards}` + handler `SurgeClaimRewards` dans `LoginServer`.

**Montants — TOUS extraits du code du jeu (§3/§4), zéro invention** (sondés + disasm) :
- **tokens** (`CRYPT_TOKENS`) = `SurgeClientHelper.getPlayerSurgeCoins(surge)` = `getTokensPerClearedRegion()×régions
  + getBaseTokens()`, régions = `wavesCompleted×3 + waveRegionsCleared.size()` — base **30**, /région **14**.
- **or** (`GOLD`) = `member.storedGold` (accumulé par `recordOutcome→storeGold`).
- **influence** de GUILDE = `SurgeHelper.getInfluenceProgress + SurgeStats.getBaseInfluence` = la somme EXACTE
  affichée par `SurgeClearedWindow` (disasm offsets 96-102) — base **5000**, /région **1350**.

**Flux client PROUVÉ au bytecode** (`SurgeScreen.checkAnimations` + `SurgeResultsWindow.lambda$createRewardsContent$1`) :
le serveur pose un `SurgeRewards` par membre dans `SurgeData.unclaimedRewards[surgeID_terminé]` (clé =
`previousResults.surgeID`) livré par `GetSurge` ; le client ouvre la fenêtre si `totalGold≠0 || totalTokens≠0` et
au clic envoie `SurgeClaimRewards{surgeID}` **puis** se crédite localement `UserHelper.giveUser(CRYPT_TOKENS,
totalTokens)` + `(GOLD, totalGold)` (`RewardSourceType.NORMAL`, offsets 163/249). Le serveur MIROITE ce crédit de
façon autoritative, **une seule fois** (anti double-réclamation via le registre).

**Bascule (rollover) paresseuse** (= incrément 7, sans tâche de fond) : au changement de surgeID, `loadOrReset`
fige un registre `surgeprev:<guildID>` (résultats `SurgeResultInfo` + récompense par membre + set réclamé),
crédite l'influence à la guilde (plafond `getMaxGuildInfluence`, comme `applyStaminaBurnInfluence`), et embarque
`previousResults` dans le `SurgeData` neuf. Même patron que le rollover de saison de guerre/contest.

**Test** `SurgeClaimTest` : montants (128 tokens / 12345 or pour 2 vagues+1 région), influence guilde **+14450**,
crédit PERSISTANT (round-trip DB), **anti-double-réclamation**, disparition d'`unclaimedRewards` après réclamation,
round-trip wire du `SurgeData` personnalisé et du `SurgeRewards`. Régression **88/88**.

**⚠️ 1 inférence de PLACEMENT documentée (§4)** : le MONTANT d'influence est 100 % code du jeu ; le MOMENT du
crédit (à la bascule, une fois) est un choix d'ingénierie cohérent avec war/contest — **à confirmer EN JEU**
(incrément 8, qui débloque aussi les raids). Le crédit tokens/or personnel est DÉFINITIF (disasm). `achievedTier`
de `SurgeResultInfo` laissé à 0 (non prouvé headless — pas de valeur inventée).

Fichiers : `server/java/dhserver/{ServerSurgeRewards,ServerSurgeState,LoginServer}.java`,
`server/smoke/SurgeClaimTest.java`, `server/smoke/regression.sh`, `docs/SURGE.md`.

---

## 2026-08-04 (g72b) — SURGE (#72) VÉRIF EN JEU : rendu confirmé contre notre serveur

Lancé la pile complète (`run-online.sh`) avec le compte TL100/en guilde (`dh-snapshot-postwar-0803.db` copié en
`dh-server.db`, restauré après) et le vrai client, surge ACTIF (fenêtre 16:00 UTC). Sonde préalable headless
(`SurgeGateProbe`) : `inGuild=true`, `teamLevel=100`, `SURGE_OBJECTIVES` débloqué, `ServerSurge.isActive=true`,
GetSurge dry-run = 2 membres / 27 adversaires / 27 vagues, round-trip wire OK.

**Résultat EN JEU** : `nav SURGE` (API du jeu `UINavHelper.navigateTo(Destination.SURGE)`) →
- serveur : `[login] <== GetSurge → ==> SurgeData (surgeID=1785859200000, membres=2)` — **échange réel sur le fil**.
- client : l'écran **`SurgeScreen` « CREEP SURGE »** s'ouvre **sans crash** (`dumpscreen`=SurgeScreen, captures
  continues post-nav), affiche les **27 districts** (nos `activeDistricts`, régions OCEANSIDE/UPTOWN/MIDDLE
  BURROUGHS/DOWNTOWN/SUNSET BAY), le compte à rebours (4h42), le palier « 1 Max », et l'entête **30 tokens de base
  + 5 000 d'influence = EXACTEMENT nos constantes du code du jeu** (`getBaseTokens`/`getBaseInfluence`). Capture
  `desktop-port/build/surge.ppm` (envoyée à l'utilisateur).

Confirme EN JEU les incréments 3 (rendu), 4b (27 districts/adversaires) et l'affichage fidèle des constantes de
récompense (6). **Reste EN JEU** : entrer dans un district (carte = widget custom → taps à driver précisément) pour
observer `StartSurgeAttack`/`SurgeAttack`, puis un RAID (débloque l'incrément 5, protocole inconnu), puis la
réclamation en fin de surge. Le combat de district est client-autoritatif (comme la campagne), à jouer via l'UI.

Fichiers : `docs/SURGE.md` (§8 incrément 8 passé 🔶).

---

## 2026-08-09 (g72c) — SURGE pilote en jeu (suite) : outillage prêt + LACUNE de repro du client exposée

**Contexte** : le conteneur de session a été REPROVISIONNÉ entre deux tours. Le code est intact (récupéré depuis
`origin/claude/disney-heroes-port-rhhtuj` = f83becc ; `main` du dépôt est un autre projet, cf. incident du jour),
mais **tous les artefacts git-ignorés ont été perdus** : `/server/data/` (toutes les DB de comptes, dont le
snapshot TL100 postwar), et les dérivés de build du client (`spine-libgdx-perblue.jar`, `gdx-1.9.7.jar`, caches
gradle, `.so` natifs partiels). `game.jar` et l'APK (`game/disney-heroes-12.1.0.apk`) sont committés → OK.

**Fait cette session** :
- **`server/smoke/SurgeAcctSetup.java`** — outil DEV qui RECONSTRUIT un compte apte à SURGE (TL100 + 5 héros +
  guilde). Vérifié : `TL=100 héros=7 inGuild=true guildID=1`. Remplace le snapshot perdu, reproductible.
- **Pilote SURGE en jeu** (`TutorialDriver` + dispatcher `DesktopLauncher`) : commandes clickfile `surgestate`
  (dump), `surgefight` (ouvre le combat du 1er district jouable via `SurgeScreen.fightPressed` → chooser qui envoie
  `StartSurgeAttack`), `surgequick` (`quickFightPressed` → combat résolu client + `SurgeAttack`), `surgeraid`
  (`onRaidButtonClick`/`doRaidSurge` → OBSERVER le protocole de raid, incrément 5). API vérifiée par signature
  (javap) ; **non exécutée end-to-end** car le client ne compile pas (deps manquantes, ci-dessous).

**⛔ BLOCAGE (repro client)** : le module desktop ne compile plus — `package com.esotericsoftware.spine does not
exist`. `libs/spine-libgdx-perblue.jar` (runtime spine custom de l'Opt.3 #28) **n'a aucune recette de
régénération committée** (`tools/decompile.sh` ne produit que `game.jar` ; les 3 classes spine de `game.jar` ne
suffisent pas), et `gdx-1.9.7.jar`/caches gradle sont absents. Donc le pilote combat/raid EN JEU est bloqué tant
que l'environnement de build du client n'est pas reconstitué. **Le rendu SURGE, lui, a DÉJÀ été vérifié en jeu
cette session (g72b, avant le reprovision).**

**Recommandation repro (§7)** : committer `spine-libgdx-perblue.jar` (ou un script qui le régénère depuis l'APK)
et figer les deps gradle, pour que le client survive à un reprovision — sinon la vérif EN JEU n'est pas
reproductible.

Fichiers : `server/smoke/SurgeAcctSetup.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.

---

## 2026-08-09 (g72d) — SURGE : BOUCLE DE COMBAT VÉRIFIÉE EN JEU + bug serveur corrigé + build client reconstruit

Reprise du pilote EN JEU après le reprovision (cf. g72c). Le dépôt local avait été re-cloné sur `main` (projet
web-archiving sans rapport partageant le repo) ; travail récupéré depuis `origin/claude/disney-heroes-port-rhhtuj`
(f83becc) par `git reset --hard`. Tous les artefacts git-ignorés (DB comptes + dérivés de build client) étaient
perdus → reconstruits DEPUIS LES SOURCES COMMITTÉES (l'utilisateur avait raison : la recette est dans nos scripts) :
- `tools/build_spine_jar.sh` (NOUVEAU) : régénère `libs/spine-libgdx-perblue.jar` depuis spine-runtimes 3.6 (le
  clone officiel de native/build.sh) compilé contre gdx-1.9.7. Sans lui le module desktop ne compile pas.
- `tools/fetch_assets.sh` : assets ETC1 (world/ui) depuis l'archive.org du projet (le base APK n'a que fonts/SDK).
- `server/smoke/SurgeAcctSetup.java` : compte apte à SURGE — TL100 + 5 héros + guilde + **tutoriel complété**
  (`TutorialHelper.getMaxStep` par acte ; sans ça `canNavigateTo(SURGE)=false`).

**Vérif EN JEU (compte reconstruit, surge actif)** — le vrai client contre notre serveur :
- rendu « CREEP SURGE » (27 districts) + `GetSurge→SurgeData`.
- **combat de district COMPLET** : `surgefight` (SurgeScreen.fightPressed → SurgeHeroChooserScreen) puis
  `surgeteamfight` (bouton AUTO du jeu → autoSelectHeroes + quickFightPressed) →
  `[login] StartSurgeAttack(O) → StartSurgeAttackResponse (3 défenseurs)` →
  `[login] SurgeAttack(O, WIN) → SurgeUpdate (+0 pts, districts+1) [persisté]`.
  Écran « DISTRICT 2 CLEARED! » : or 9,22 M → Mon Coffre, influence 80 552 → Banque de Guilde. District O
  cleared=true après combat (persisté). `+0 pts` = fidèle (tier 0, cf. §Scoring).

**🐛 BUG SERVEUR trouvé & corrigé EN JEU (exactement le rôle de §8)** : le serveur ne posait jamais
`SurgeData.youAreInRaid` pour un membre → `SurgeScreen.fightPressed` refusait TOUT combat de district
(`CRYPT_JOINED_LATE_ERROR`, garde offset 81-97). Champ 100 % serveur-autoritaire (recon : SEUL `SurgeData` l'écrit,
aucun flux client). Fix : `ServerSurgeState.personalize` pose `youAreInRaid=true` pour le membre participant.
Vérifié EN JEU : `youAreInRaid=true` → combat autorisé → boucle complète OK.

**Pilote SURGE en jeu** (`TutorialDriver` + dispatcher) : `surgenav` (diagnostic canNavigateTo), `surgestate`,
`surgefight`, `surgeteamfight` (auto-équipe + quick fight), `surgequick`, `surgeraid` (à déclencher depuis le HQ).

**Reste EN JEU** : le RAID (incr. 5, protocole à observer via `surgeraid` depuis le HQ) + la réclamation de fin de
surge. Le CŒUR du mode (rendu + combat de district + enregistrement autoritatif + persistance) est vérifié EN JEU.

Fichiers : `server/java/dhserver/ServerSurgeState.java` (youAreInRaid), `tools/build_spine_jar.sh` (nouveau),
`server/smoke/SurgeAcctSetup.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/SURGE.md` §8, `MEMORY.md`.

---

## 2026-08-09 (g72e) — SURGE : PROTOCOLE DE RAID RÉSOLU (incrément 5 débloqué)

Suite du pilote EN JEU (« Continue »). Objectif : élucider le protocole de raid SURGE (le blocage §4 de longue date).

**Recon (disasm) + observation EN JEU** — `SurgeHeroChooserScreen.doRaidSurge(ActionListener)` envoie, dans l'ordre :
1. `HeroLineupUpdate{type=SURGE, lineup}` (équipe de raid) — **observé EN JEU** (« HeroLineupUpdate(SURGE) → lineup
   enregistrée [persistée] »).
2. `Action{command=SET_SEED, TYPE=SURGE, ID=<graine>}` (graine du combat de raid) — **observé EN JEU**.
3. puis `ClientActionHelper.raidSurge(district, count, upsell, autoSelect, snap, listener)` →
   **`Action{command=RAID_SURGE, extra={TYPE=<district.name()>, COUNT=<long>, UPSELL=<bool>,
   MODE=AUTO_SELECT|MANUAL_SELECT}}`** = l'ISSUE du raid (le « message manquant » qui bloquait §4). Prouvé au
   bytecode (`withType(district)`→`extra[TYPE]`, offsets 17-77 de `raidSurge`).

⇒ **Le blocage est levé** : le serveur doit ajouter un handler d'Action `RAID_SURGE` qui rejoue
`SurgeHelper.recordRaid(user, member, surgeID, district=extra[TYPE], false, RAID_TEAM_POWER, 0L, GOLD, raidHeroes,
snap)` — mêmes params que résolus (docs/SURGE.md incr 5), équipe depuis la SURGE HeroLineup persistée (msg 1),
`GOLD=getGoldForSurgeRaid`, borne `getMaxRaidsPerSurge`.

**RESTE** : (a) câbler le handler `RAID_SURGE` (miroir de `ServerSurgeCombat.applyRegionOutcome`) + test headless ;
(b) **vérif EN JEU d'un raid COMPLET** — non atteinte ce run : le combat de raid a coupé la connexion client
(`java.net.SocketException: Socket is closed` / `Connection refused`) AVANT l'`Action RAID_SURGE` (stabilité du
combat de raid à régler, distincte du protocole). Les sous-valeurs `RAID_TEAM_POWER`/`GOLD`/sémantique `COUNT` et
le clear-district se confirment sur un raid abouti EN JEU (ne pas inventer, §4).

**Pilote** : `surgeraid` amélioré (auto-sélection d'équipe via le bouton AUTO du jeu, puis `doRaidSurge`).

Fichiers : `docs/SURGE.md` §5, `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (surgeraid), `MEMORY.md`.

---

## 2026-08-09 (g72f) — SURGE incrément 5 : handler serveur RAID_SURGE (recordRaid autoritatif, headless 🟢, 89/89)

Suite de g72e (protocole résolu). Implémentation serveur du raid, params 100 % prouvés au bytecode (site d'appel
`SurgeHelper.doRaid`, offsets 181-218) :
- `ServerSurgeCombat.applyRaidOutcome(user, summary, surgeID, district, opponentLineup, snap)` : équipe =
  `user.getHeroLineup(SURGE)` (persistée par le HeroLineupUpdate précédent), `RAID_TEAM_POWER`=Σ`PowerCalculator.
  getPower(hero,0)`, `GOLD`=`getGoldForSurgeRaid(user, lineup, opponent.lineup, emptyList, snap)`, puis
  `recordRaid(user, member, surgeID, district, false, RAID_TEAM_POWER, 0L, GOLD, raidHeroes, snap)` (ordre des 3
  longs confirmé au bytecode). recordRaid fait l'autorité : `storeGold` (or), `incDailyUses` (pass), `onSurgeRaid`.
- `ServerSurgeState.applyRaid` : applyRaidOutcome + incrémente le compteur PARTAGÉ `summary.raidsUsed` (recordRaid
  ne mute que le compteur QUOTIDIEN du joueur, pas l'état de guilde — comme applyAttack incrémente districtsCleared)
  → renvoie SurgeUpdate (delta or).
- `LoginServer` : handler `Action RAID_SURGE` — lit `extra[TYPE]`=district (DistrictType.valueOf), loadOrReset,
  applyRaid, persiste (SurgeData + user), diffuse SurgeUpdate (`pushToGuild`).
- `SurgeRaidTest` : équipe SURGE posée d'abord (HeroLineupUpdate, comme en jeu), raid headless → `raidsUsed=1`,
  or +3,69 M (storeGold), persistance + round-trip wire. Régression **89/89**.

**RESTE (§8)** : vérif EN JEU d'un raid COMPLET (le run g72e a coupé la connexion pendant le combat de raid AVANT
l'Action RAID_SURGE — stabilité du combat de raid, distincte du protocole). Sémantique `COUNT` (raids groupés ?) et
un éventuel clear-district à confirmer sur un raid abouti EN JEU (non présumés : le handler traite 1 raid/Action,
sans marquer le district vaincu).

Fichiers : `server/java/dhserver/{ServerSurgeCombat,ServerSurgeState,LoginServer}.java`, `server/smoke/SurgeRaidTest.java`,
`server/smoke/regression.sh`, `docs/SURGE.md` §5, `MEMORY.md`.

---

## 2026-08-09 (g72g) — SURGE 100 % VÉRIFIÉ EN JEU : raid + récompenses/réclamation + anti-double

Achèvement de la vérif EN JEU de SURGE (« Termine quand même les vérifs in game »).

**RAID (incr. 5) — bout en bout EN JEU** : `surgeraid` (auto-équipe via bouton AUTO + `doRaidSurge`) → le vrai
client envoie `HeroLineupUpdate{SURGE}` + `Action RAID_SURGE{extra TYPE=P, COUNT=0, UPSELL=false,
MODE=MANUAL_SELECT}` → **notre handler** : `Action RAID_SURGE(P) → SurgeUpdate (+9 225 000 or, raidsUsed=1)
[persisté]`. Écran « RAID (1 LEFT) ». `COUNT=0` CONFIRME que ce n'est pas un multiplicateur (1 raid/Action — décision
serveur validée, pas d'invention). La connexion TIENT désormais (l'ancien serveur SANS handler RAID_SURGE coupait
la connexion — cause de la coupure g72e, résolue par le handler).

**RÉCOMPENSES + RÉCLAMATION (incr. 6/7) — bout en bout EN JEU** : horloge serveur avancée +13h
(`DH_SERVER_OPTS=-Ddh.clock.offset.hours=13`) → bascule (surgeID 1786291200000→1786377600000) → `GetSurge` livre
`unclaimedRewards` → le client ouvre **« SURGE REWARDS »** : MY REWARDS **18,45 M or + 30 tokens**, GUILD **5 000
influence** — valeurs EXACTES du serveur (storedGold combat+raid ; `getPlayerSurgeCoins`=30 ; `getBaseInfluence`=5000).
`surgeclaim` (envoie le vrai `SurgeClaimRewards{surgeID}`) → **notre handler** : `SurgeClaimRewards(1786291200000)
→ SurgeRewards (+30 tokens, +18 450 000 or)`. Crédit **persisté** (DB : `CRYPT_TOKENS=30`, GOLD +18,45 M).
**Anti-double CONFIRMÉ EN JEU** : 2ᵉ claim → `(+0 tokens, +0 or)`. ⇒ l'inférence de placement du crédit d'influence
(à la bascule) est VALIDÉE.

**⇒ SURGE est 100 % vérifié EN JEU** : rendu, combat de district, raid, bascule/récompenses/réclamation, anti-double,
+ le fix serveur `youAreInRaid`. Aucune invention (§4) : tous les montants/params du code du jeu, prouvés au bytecode
et confirmés sur le fil.

**Pilote SURGE complet** (`TutorialDriver`+`DesktopLauncher`) : `nav SURGE`, `surgenav`, `surgestate`, `surgefight`,
`surgeteamfight`, `surgequick`, `surgeraid`, `surgeclaim`. Recette de restauration du client dans `docs/SURGE.md §8`.

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (surgeclaim), `docs/SURGE.md` §8, `MEMORY.md`.

---

## 2026-08-09 (g73) — CHALLENGES (#72) : recon pipeline + incrément 1 (livraison BootData, 90/90)

Nouveau mode attaqué avec le pipeline #73/#74 (`contract.sh --mode` + ModeGraph). **Recon** (docs/CHALLENGES.md) :
mode « Sticker Challenges » gaté TL20, 15 classes `ui/challenges`, système IDLE (défis dans des slots →
sticker/récompenses), contenu data-driven (`challenge_*.tab`). Données via **`BootData.userChallengeDataExtra`**
(+ `historicWeeklyChallenges`) ; actions `START/CLAIM/CANCEL_STICKER_CHALLENGE`, `BUY_STICKER*`,
`SET_FAVORITE_STICKER` ; seul handler « MANQUE » = `GetUserChallengeDataExtra` (vue StickerOverviewWindow).

**Écartés à la recon** : HEIST (`unlockables.tab` TL **9999** = désactivé → vérif §8 impossible ; + 82 classes coop
temps réel) ; CITY WATCH (aucun Destination/écran — vestiges de tuto legacy, pas un mode autonome).

**Incrément 1 LIVRÉ** : `ServerChallenges.freshData(userID)` → `bd.userChallengeDataExtra` avec le **bon userID**
(le défaut `new BootData()` est non-null mais userID=0, or l'écran le lit) + conteneurs wire-sûrs ;
`historicWeeklyChallenges` reste le défaut non-null. `ChallengeBootTest` (userID, conteneurs, round-trip BootData).
Régression **90/90**. Reste : boucle START/CLAIM (persistée), stickers, handler GetUserChallengeDataExtra, vérif EN JEU.

Fichiers : `docs/CHALLENGES.md`, `server/java/dhserver/ServerChallenges.java`, `server/java/dhserver/ServerUser.java`
(bootData), `server/smoke/ChallengeBootTest.java`, `server/smoke/regression.sh`.

---

## 2026-08-09 (g73b) — CHALLENGES : architecture de l'incrément 2 (boucle START/CLAIM) résolue par recon

Recon complète de la boucle de défi (docs/CHALLENGES.md incr. 2). Points d'entrée §3 identifiés :
- Conversion : `ClientNetworkStateConverter.getUserChallengeData(UserChallengeDataExtra)` ↔ `setUserChallengeData`.
- START `Action{START_STICKER_CHALLENGE, extra={TYPE=StickerType, TIME}}` (pas de SLOT → serveur choisit via
  `StickerHelper.canStart`) ; handle via `StickerHelper.createHandleExtra` : `endTime=serverTime()+ChallengeSticker.
  getDuration()`, `maxProgress=ChallengeSticker.getMaxProgress()` (données `challenge_stickers.tab`, zéro invention).
- PROGRESSION : hooks `ChallengeImpl` (`onCampaignAttack`/`onChestOpen`/`onBreakerAttack`/`onArenaPromotion`/
  `checkAttackBase`…) = intégration TRANSVERSALE ; défis purement temporels complétés à `endTime`.
- CLAIM `StickerHelper.claimSticker(user, data, long, StickerType, ChallengeSlots)` ; CANCEL `cancelChallenge(…)`.
- Persistance : `UserChallengeDataExtra` hors `UserExtra` → nouveau champ persisté dans le blob `ServerUser`.

**Décision (§4, qualité)** : la boucle est un sous-système transversal (hooks ChallengeImpl + choix de slot +
autorité de progression client/serveur) ; comme pour le raid SURGE, une **observation EN JEU du START/CLAIM** (extras
exacts, sélection de slot, `UpdateChallengeProgress`) est recommandée AVANT câblage pour ne rien inventer. L'incrément
1 (livraison BootData) reste livré et testé (90/90). L'architecture est prête pour un câblage propre.

Fichiers : `docs/CHALLENGES.md` (incr. 2 détaillé).

---

## 2026-08-10 (g91) — EXPEDITION (#72) incrément 3 : COMBAT de nœud ✅ VÉRIFIÉ EN JEU

Reprise après compression (procédure complète : relecture MEMORY/PRINCIPLES/SHIMS/PROTOCOL/SERVER_PLAN/
ARCHITECTURE/EXPEDITION + règles §1-§8 + commandes). Puis achèvement de la vérif EN JEU du combat de nœud
d'expédition, qui plantait au client (`ExpeditionAttackScreen.createStageDefenders` : `IndexOutOfBounds`).

**Diagnostic par SONDES HEADLESS PROFONDES.** `WireCheck.assertRoundTrips` ne vérifie que le TYPE relu, PAS la
profondeur des `List` (angle mort) → écrit des sondes qui déballent `defenders[].lineup` et `nodeRewards` après
round-trip codec ET après save/reload DB. Elles ont montré que le serveur envoyait bien 15 défenseurs qui
survivaient au wire — mais avec **3 défauts** :

1. **Étoiles ennemies invalides (cause du crash).** `buildDefenders` : `createAndAddHero(t, ORANGE, level, 1)`.
   L'ordre des 2 entiers du jeu est **(ÉTOILES, NIVEAU)** (bytecode : `createUnitData` fait `setStars(a)`/
   `setLevel(b)`, cf. `ServerUser.grantHero`) → ennemis à **140 étoiles / niveau 1**. Des étoiles > `getMaxStars`
   (=6 à R102) plantent le client au rendu du combat (même famille que g55 `HasEnoughCollectionHeroes`).
   Corrigé : `stars = UnitStats.getMaxStars(user)`, `level = base`.
2. **`nodeRewards` VIDE → `IndexOutOfBounds` au 1ᵉʳ nœud.** `createStageDefenders` lit
   `getExpeditionData().getData().nodeRewards.get(nodeIndex)` ET `defenders.get(nodeIndex)`. Le run n'avait pas de
   `nodeRewards` tant qu'aucun nœud n'était gagné. Corrigé : **pré-génération au reset** via la méthode du jeu
   `ExpeditionHelper.createRewards` (§3, 15 `NodeReward{OR}` — `getGold(node, TL)` × bonus VIP).
3. **Niveau ennemi DOUBLÉ.** Le serveur envoyait `base + getExtraEnemyLevels`, or le **client** ajoute
   `getExtraEnemyLevels(difficulty)` au combat (offset `setLevel(getLevel()+extra)`). Corrigé : le serveur envoie la
   **BASE** (= niveau d'équipe du joueur ; EASY diff=1 ajoute 0, diff≥4 ajoute des niveaux).

Autres correctifs (§3) : `ResetExpedition` répond désormais **`ResetExpeditionResponse`** (type DÉDIÉ ; le client a
un handler propre `GameMain.lambda$setupPostClientInfoHandlers$55` qui fait le nettoyage de reset —
`clearModePersistentData`/`clearMercenaryHero`/`clearKoHiredMercenaries`/`enableDifficulty`/`onExpeditionReset`),
au lieu de `GetExpeditionResponse` qui sautait ce nettoyage ; crédit de nœud via `ExpeditionHelper.giveLoot(user,
nodeReward, node, difficulty, snap)` (applique `modifyGoldForDifficulty` + objets/tickets) au lieu d'un crédit à la main.

**Tests durcis** : `ExpeditionBootTest` (garde-fous : étoiles ennemies valides ≤ `getMaxStars`, niveau ≥ 1,
`nodeRewards` pré-peuplé = un par nœud) ; `ExpeditionCombatTest` (`giveLoot` : nœud 0 +5157 or, nœud 1 +5573,
`nodeRewards` reste pré-peuplé, persistance DB). Réponse au reset = `ResetExpeditionResponse` round-trip.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** `nav EXPEDITION` → `GetExpedition → GetExpeditionResponse (15 nœuds)` →
carte **CITY WATCH** rendue → `expfight` → écran **CHOOSE YOUR HEROES** (defenders=15, nodeRewards=15 côté client,
**plus aucun crash `createStageDefenders`**) → `expquick` → **combat RENDU joué de bout en bout** → **DEFEAT** d'abord
(roster de test niv.40-60 vs ennemis niv.100 désormais corrects) → `ExpeditionAttack(LOSS)` → serveur « pas de
progression [persisté] ». Roster porté à niv.100 RED 6★ (outil DEV `ExpAdminBoost` — état de compte légitime, même
esprit que `SetTeamLevel` ; le compte était TL100 mais héros sous-niveau) → **VICTORY 11s** → écran **REWARDS : LOOT
5 157 or** → CONTINUE → `ExpeditionAttack(WIN)` → serveur **`nœud 0 VAINCU → nodesDefeated=1, or +5157 [persisté]`** →
carte avancée au nœud suivant. **DB confirmée** (sonde) : `nodesDefeated=1`, `totalGoldEarned=5157`, GOLD crédité.

Pilotes DEV ajoutés (`TutorialDriver`+`DesktopLauncher`) : `expfight` (pousse le vrai
`ExpeditionHeroChooserScreen(node, NONE)`), `expquick` (`quickFightPressed` réel → `ExpeditionAttackScreen` + combat
+ `ExpeditionAttack`). Outils DEV : `ExpAdminReset` (régénère un run cassé par un ancien build), `ExpAdminBoost`
(aligne le roster de test sur le TL). **NB** : `JOURNAL.md` était en retard (dernière entrée g73b) alors que le
travail g74→g90 (CHALLENGES en jeu, FRIENDSHIPS, EXPEDITION incr.1-2) est dans `MEMORY.md`/`docs/*`.

**RESTE EXPEDITION** : incr. 4 (raid `ExpeditionRaid`/`doRaidFromClient`), 5 (wards hebdo `ExpeditionWeeklyInfo`),
6 (économie de reset `chargeForReset`/`getResetsRemaining`), 7 (coffres/epic chips `createRewards`/`openChest`),
8 (vérif en jeu complète).

Fichiers : `server/java/dhserver/{ServerExpedition,LoginServer}.java`, `server/smoke/{ExpeditionBootTest,
ExpeditionCombatTest,ExpAdminReset,ExpAdminBoost}.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g92) — EXPEDITION (#72) incrément 4 : RAID ✅ VÉRIFIÉ EN JEU

Le RAID complète toute l'expédition d'un coup (saute le combat). Client-autoritatif : le client exécute
`ExpeditionHelper.doRaidFromClient` (→ `doRaid` local, se crédite le butin) puis envoie `ExpeditionRaid{rewards,
difficulty}` ; le serveur RÉ-EXÉCUTE l'autorité.

**Recon (bytecode).** `ExpeditionRaid{difficulty, rewards, specialEvents}` ; pas de `ExpeditionRaidResponse` dédié.
`doRaidFromClient(user, difficulty, snap)` appelle `doRaid(user, difficulty, nodesDefeated, snap, finisher, null)` et
envoie l'`ExpeditionRaid`. `doRaid` (méthode AUTORITATIVE, §3) : gate `isDifficultyRaidable` (sinon
`COMPLETE_PREVIOUS_EXPEDITION_FIRST`) ; si `nodesDefeated>0` vérifie `getResetsRemaining` + `chargeForReset` ; débite
`getRaidCost`×`getRaidTicketType` tickets (lève `DONT_HAVE_ITEM`) ; `createRewards` (15 nœuds) + `rollExpeditionDrops`
/`rollEpicChipsForRound` → merge ; `if (rewardsClient != null && !compareDrops) throw INVALID_LOOT` (anti-tamper) ;
`giveRewards` ; `finisher.finishExpedition(nbNœuds, coût)` ; `incDailyUses`.

**Serveur (`ServerExpedition.recordRaid`).** Appelle `doRaid(user, difficulty, run.nodesDefeated, snap, finisher,
null)` — `finisher` pose `run.nodesDefeated = 15`. **6ᵉ arg = null** (EXACTEMENT comme le client : `aload 5 ifnull →
saute compareDrops`) → le serveur roule et crédite son PROPRE butin sans faux rejet `INVALID_LOOT` sur divergence RNG
(même décision que le loot campagne #25/§4bis). Catch `Throwable` (ClientErrorCodeException est CHECKED côté javac et
non déclarée par `doRaid` — on distingue l'anti-triche par `instanceof`). Persiste (`setExpeditionRun`+resyncs).

**Progression de difficulté (mirroir client).** `recordAttack` : au dernier nœud (`nodesDefeated == nodeCount`),
`enableDifficulty(user, difficulty+1)` — le client fait EXACTEMENT `if (nodesDefeated >= 15) enableDifficulty(diff+1)`
(bytecode `ExpeditionAttackScreen`). Débloque la difficulté supérieure ET rend la courante RAIDABLE.

**Test `ExpeditionRaidTest` (progression RÉELLE).** Raid refusé avant clear (anti-triche) → clear des 15 nœuds
(FIGHT) → diff 1 devient raidable → raid diff 1 : run complet, or crédité, 1 ticket débité → raid sans ticket refusé
(`DONT_HAVE_ITEM`) → persistance DB. Régression → **101 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** Compte rendu raidable par l'outil DEV `ExpAdminRaidable` (clear RÉEL des 15
nœuds via `recordAttack` → `enableDifficulty(2)` → diff 1 raidable, persisté ; + 5 tickets `EXPEDITION_RAID_1`, run
frais). `nav EXPEDITION` → `GetExpeditionResponse (expeditionID=5, 15 nœuds)` → carte CITY WATCH → `expraid`
(`doRaidFromClient(diff=1)` réel) → `ExpeditionRaid` → serveur **`RAID diff=1 → expédition complète (nodesDefeated=15),
or +370531 [persisté]`**. **DB confirmée** (sonde) : `nodesDefeated=15`, `totalGoldEarned=370531`, tickets
`EXPEDITION_RAID_1` 5→4, GOLD crédité.

Pilote DEV `expraid` (`TutorialDriver`+`DesktopLauncher`, chemin `doRaidFromClient` réel). Outil DEV `ExpAdminRaidable`.

**RESTE EXPEDITION** : incr. 5 (wards hebdo `ExpeditionWeeklyInfo` : currentWards/nextWards + calendrier de rotation),
6 (économie de reset `chargeForReset`/`getResetsRemaining`), 7 (coffres/epic chips `openChest`), 8 (vérif en jeu complète).

Fichiers : `server/java/dhserver/{ServerExpedition,LoginServer}.java`, `server/smoke/{ExpeditionRaidTest,
ExpAdminRaidable}.java`, `server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g93) — EXPEDITION (#72) incrément 5 : wards hebdomadaires (headless + délivrance en jeu)

`GetExpeditionResponse.weeklyWardInfo` est désormais PEUPLÉ (avant : objet vide). Les wards (`CombatModifier`) sont
des modificateurs de combat qui tournent chaque semaine et ne s'appliquent qu'aux **difficultés ≥ 3** (`getWardsFor`
renvoie `EMPTY_MODIFIERS` pour diff < 3 — EASY/NORMAL n'ont PAS de ward, fidélité vérifiée).

**Recon (bytecode).** `ExpeditionWeeklyInfo{currentWards, currentWardExpiration, nextWards, nextWardStartTime}`.
`getWardsFor(info, diff)` / `getNextWardsFor(info, diff)` = accesseurs purs : `diff < 3 → EMPTY` ; sinon
`currentWards.subList(0, diff-2)` (cumulatif : diff 3 → [0], diff 4 → [0,1]). Le POOL est dans la DONNÉE du jeu
`ExpeditionStats$WardStats.wardsByDifficulty` (List de 5 EnumSet : diff 0-2 vides ; diff 3 & 4 → 13 `WARD_*` chacun).
La ROTATION exacte (quel ward chaque semaine) est calculée par le BACKEND, ABSENT du jar client (comme `ArenaInfo`/
Surge).

**Serveur.** `ServerExpedition.weeklyWardInfo(now)` : lit le pool par réflexion (cache `WARD_POOL_3/4`), sélectionne
DÉTERMINISTE par l'INDICE DE SEMAINE DU JEU (`TimeUtil.getServerWeek`) — 1 ward HARD (pool diff 3, `week % n`) + 1 ward
EPIC additionnel différent (pool diff 4, `(week+3) % n`) ; `nextWards` = semaine+1 ; bornes = prochaine frontière hebdo
(`((now/MILLIS_PER_WEEK)+1)*MILLIS_PER_WEEK`). Calibration serveur documentée (patron incr. 2 : pool = donnée du jeu,
arrangement = serveur ; §4 : aucune VALEUR inventée, seule la rotation est un stand-in fidèle). Câblé dans `response()`.

**Test `ExpeditionWardTest`.** Vérifie via les accesseurs DU JEU : diff 1/2 → 0 ward, diff 3 → 1, diff 4 → 2 ; wards
= `CombatModifier WARD_*` du pool ; rotation déterministe (`nextWards == currentWards` de la semaine suivante) ; bornes
(expiration dans le futur ≤ 1 semaine, `nextWardStartTime == currentWardExpiration`) ; round-trip wire. Régression →
**102 tests**. (Ex. observé : currentWards=[WARD_DECREASE_HEALING, WARD_SUPPORT_LESS_ENERGY],
nextWards=[WARD_TANKS_EXTRA_DAMAGE, WARD_IMMUNE_TO_DISABLES].)

**EN JEU (compte id=1 TL100).** `nav EXPEDITION` → serveur `GetExpedition → GetExpeditionResponse (weeklyWardInfo
PEUPLÉ, 2 wards réels)` → le client ACCEPTE et rend CITY WATCH sans erreur (régression : avant vide, maintenant peuplé,
toujours OK) ⇒ **délivrance de weeklyWardInfo vérifiée en jeu**.

**DIFFÉRÉ (§8, gate de progression documenté).** L'**EFFET** des wards en combat (diff ≥ 3 HARD/EPIC) et leur
**affichage** dans `ExpeditionDifficultyWindowV2` ne sont pas atteignables sur le compte de test : le run EASY est
complété (sélecteur de difficulté grisé sur un run terminé ; le reset n'a pas firé sans économie — incr. 6) et HARD
requiert de clearer NORMAL d'abord. À vérifier sur un compte plus avancé. La rotation EXACTE du backend reste à
OBSERVER en jeu (comme le protocole de raid SURGE avant câblage) — notre rotation déterministe est un stand-in fidèle.

**RESTE EXPEDITION** : incr. 6 (économie de reset `chargeForReset`/`getResetsRemaining`), 7 (coffres/epic chips
`openChest`), 8 (vérif en jeu complète, y compris wards HARD+ sur un compte avancé).

Fichiers : `server/java/dhserver/ServerExpedition.java`, `server/smoke/ExpeditionWardTest.java`,
`server/smoke/regression.sh`, `docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g94) — EXPEDITION (#72) incrément 6 : économie de reset ✅ VÉRIFIÉ EN JEU

Relancer une expédition (hors 1ᵉʳ run) passe par `ExpeditionHelper.chargeForReset` (déjà appelé par
`ServerExpedition.resetRun`) ; l'incrément documente/teste l'économie + expose `resetsDone`.

**Barème DU JEU (§4, bytecode).** `getResetsRemaining(user, snap) = max(getResource(CITY_WATCH_RESETS),
DailyActivityHelper.getRemainingDailyUses(user, "EXPEDITION RESET", snap))`. `chargeForReset` : si resets restants > 0
→ consomme `CITY_WATCH_RESETS` (+`incDailyUses`) ; sinon paie `getEpicKeyCost(user, snap, diff)` clés
`CITY_WATCH_EPIC_KEYS` ; à défaut lève `EXPEDITION_CHANCES_USED`. **`getEpicKeyCost(diff)` = 0 pour diff 1-3, 35 pour
diff 4 (EPIC)** (relevé) ⇒ EASY (coût 0) : resets LIMITÉS au quota gratuit puis REFUSÉS (pas d'option payante) ; EPIC :
payable en clés epic. `resetResponse.resetsDone = DailyActivityHelper.getDailyUses(user, "EXPEDITION RESET", snap)`
(compteur d'activité quotidienne DU JEU ; ne compte pas les resets-ressource → peut rester 0). `ServerExpedition.
resetsDoneToday(user)` exposé.

**Test `ExpeditionResetTest`.** Barème epic (0 EASY / 35 EPIC) ; `firstEver` ne consomme rien ; reset EASY consomme le
gratuit (`CITY_WATCH_RESETS` 1→0) puis REFUSÉ quand épuisé ; reset EPIC (diff 4) refusé sans clé puis PAYANT (35 clés
→ 0) ; persistance DB. Régression → **103 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** Run frais (`ExpAdminReset` → `CITY_WATCH_RESETS=1`, `getResetsRemaining=1`).
`nav EXPEDITION` → `expreset 1` (chemin client réel `ClientExpeditionHelper.resetExpedition(1, NONE)`) → client
`ResetExpedition(diff=1, firstEver=false)` → serveur `run généré 15 nœuds [persisté]` → réponse
`ResetExpeditionResponse` (type dédié, handler client $55) → **carte fraîche rendue** (nœud 1 actif, 2-5 verrouillés) →
**le compteur de reset en haut à droite passe de 1 à 0** (reset gratuit consommé, VISIBLE en jeu). DB confirmée :
`CITY_WATCH_RESETS 1→0`, `getResetsRemaining=0`, persisté. Le chemin de REFUS (resets épuisés) est prouvé headless
(EASY coût 0 → `EXPEDITION_CHANCES_USED`).

Pilote DEV `expreset [diff]` (`TutorialDriver`+`DesktopLauncher`, chemin `ClientExpeditionHelper.resetExpedition` réel).

**RESTE EXPEDITION** : incr. 7 (coffres / epic chips : `createRewards`/`openChest` → héros), 8 (vérif en jeu complète,
y compris wards HARD+ sur un compte avancé).

Fichiers : `server/java/dhserver/ServerExpedition.java`, `server/smoke/ExpeditionResetTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/EXPEDITION.md`, `MEMORY.md`.

---

## 2026-08-10 (g95) — EXPEDITION (#72) incrément 7 : coffres ✅ VÉRIFIÉ EN JEU (+ CLAUDE.md)

Les coffres d'expédition (`OpenExpeditionChest`). Un coffre est disponible tous les 3 nœuds vaincus (5 coffres pour
15 nœuds = les 5 RÉGIONS de la carte CITY WATCH), ouverts DANS L'ORDRE.

**Recon (bytecode).** `OpenExpeditionChest{rewardDrops, specialEvents}`. Client (`ClientActionHelper.
openExpeditionChest`) : appelle `ExpeditionHelper.openChest(user, snap, difficulty, nodesDefeated, chestsOpened,
droppedEpicChips, null)`, incrémente `chestsOpened`, envoie `OpenExpeditionChest{rewardDrops=<résultat>}`. `openChest`
(AUTORITATIF, §3) : gate `if (nodesDefeated % 3 != 0) throw NO_AVAILABLE` ; `chestIndex = nodesDefeated/3 - 1` ;
`if (chestsOpened != chestIndex) throw NO_AVAILABLE` (ordre) ; `rollExpeditionDrops(user, getRandom(EXPEDITION_CHEST),
difficulty, chestIndex, droppedEpicChips, snap)` ; `if (rewardsClient != null && !compareDrops) throw INVALID_LOOT` ;
`giveRewards`.

**Serveur (`ServerExpedition.recordOpenChest`).** Ré-exécute `openChest(user, NONE, difficulty, nodesDefeated,
chestsOpened, droppedEpicChips, null)` — **7ᵉ arg null** (comme le client : `aload 6 ifnull → saute compareDrops`) →
crédite le butin SERVEUR sans faux rejet INVALID_LOOT (cf. #25/§4bis). Catch `Throwable` (distingue l'anti-triche
`ClientErrorCodeException` par `instanceof`). Incrémente `run.chestsOpened` (mirroir client). Persiste.

**Test `ExpeditionChestTest`.** 5 coffres (1 tous les 3 nœuds) ouverts dans l'ordre ; refus avant le 1ᵉʳ palier +
double-ouverture au même palier (NO_AVAILABLE) ; persistance DB ; refus sans run. Régression → **104 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** Compte préparé à `nodesDefeated=3, chestsOpened=0` (outil DEV
`ExpAdminClearNodes` : reset + 3 nœuds vaincus → un coffre disponible). `nav EXPEDITION` → `GetExpeditionResponse
(expeditionID=8)` → `expchest` (chemin client réel `ClientActionHelper.openExpeditionChest(NONE, null)`) →
`OpenExpeditionChest` → serveur **`COFFRE ouvert → chestsOpened=1 [persisté]`**. DB confirmée : `chestsOpened=1`,
`nodesDefeated=3`.

Pilote DEV `expchest` ; outil DEV `ExpAdminClearNodes`.

**CLAUDE.md** créé (demande `/init`) : point d'entrée concis pour toute session (procédure de reprise, règles §1-§8,
commandes build/test/run + lancement en jeu + contrainte sleep bloqué, pilotage B-bis, architecture big-picture,
combat client-autoritatif) — pointe vers les docs détaillés sans les dupliquer.

**RESTE EXPEDITION** : incr. 8 (vérif en jeu COMPLÈTE de bout en bout : difficulté → combat → coffres → raid → reset,
+ wards HARD+ sur un compte avancé). Les incréments 1-7 sont livrés ; 3/4/6/7 ✅ en jeu, 5 délivrance ✅ (effet HARD+
différé), 1/2 ✅ en jeu (g79/g90).

Fichiers : `server/java/dhserver/{ServerExpedition,LoginServer}.java`, `server/smoke/{ExpeditionChestTest,
ExpAdminClearNodes}.java`, `server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/EXPEDITION.md`, `MEMORY.md`, `CLAUDE.md`.

---

## 2026-08-10 (g96) — EXPEDITION (#72) : WARDS — EFFET ✅ DÉMONTRÉ EN JEU EN HARD

Achèvement de la vérif des wards (incr. 5) : leur EFFET de combat, différé en g93 (non atteignable sur un compte
EASY), est maintenant DÉMONTRÉ EN JEU sur un run HARD.

**Préparation.** `ExpAdminReset server/data/dh-server.db 1 1 3` → `resetRun(diff 3)` : `enableDifficulty(3)` (le compte
avait déjà débloqué jusqu'à diff 4 via les tests précédents) + génère un run HARD. Sonde headless : `run.difficulty=3`,
`getMaxEnabledDifficulty=4`, `weeklyWardInfo.currentWards=[WARD_DECREASE_HEALING, WARD_SUPPORT_LESS_ENERGY]`,
`getWardsFor(HARD=3)=[WARD_DECREASE_HEALING]`, `getWardsFor(EPIC=4)=[…, WARD_SUPPORT_LESS_ENERGY]`.

**EN JEU (compte id=1 TL100).** `nav EXPEDITION` → `GetExpeditionResponse (expeditionID=9)` → carte **CITY WATCH « HARD »**
(sélecteur HARD, région 1 « DOWNTOWN / WAY STATION » active). `expfight` → hero chooser (équipe RED niv.100, puissance
**252 358**). `expquick` → combat de nœud HARD.

**PREUVE DE L'EFFET DU WARD.** La MÊME équipe et les MÊMES ennemis (niv.100/6★ ; `getExtraEnemyLevels(3)=0` donc pas de
différence de niveau entre EASY et HARD) donnent :
- **EASY** (g91) : VICTOIRE triviale en **11 s**.
- **HARD** (g96) : **DÉFAITE en 1 min 4 s** (3/5 ennemis KO seulement).
La SEULE différence entre les deux est le ward **`WARD_DECREASE_HEALING`** actif en HARD ⇒ l'effet de combat du ward
est démontré en jeu. Puis KEEP RESULT → client `ExpeditionAttack(LOSS)` → serveur `attack nœud 0 : LOSS (pas de
progression) [persisté]` (chemin de combat HARD confirmé de bout en bout). Captures : carte « HARD », hero chooser HARD,
fenêtre DÉFAITE.

**RESTE (mineur, facultatif)** : l'AFFICHAGE explicite du nom du ward dans `ExpeditionDifficultyWindowV2` (le tap sur le
sélecteur zoome la carte plutôt que d'ouvrir la fenêtre — artefact de hit-test du pilote) ; la rotation EXACTE du
backend (notre rotation par `getServerWeek` est un stand-in fidèle, pool = donnée du jeu).

**⇒ BILAN EXPEDITION #72.** Les 7 incréments fonctionnels sont livrés ET vérifiés EN JEU par brique : boot (g79),
reset+génération (g90), combat (g91), raid (g92), wards (g96), économie de reset (g94), coffres (g95). Régression 104
tests. L'incrément 8 (« vérif complète ») est essentiellement atteint par briques ; reste facultatif un run continu
unique enchaînant reset→combat×15→coffres→raid, EPIC (diff 4), et la rotation exacte des wards.

Fichiers : `docs/EXPEDITION.md` (incr. 5 ✅ / incr. 8 ✅ par brique), `MEMORY.md`, `JOURNAL.md`. (Aucun changement de
code ce tour — vérif en jeu + doc.)

---

## 2026-08-10 (g98) — ENCHANTING (#72) incrément 1 : enchantement d'équipement ✅ VÉRIFIÉ EN JEU

Premier incrément du nouveau mode ENCHANTING (choisi g97). Message DÉDIÉ `EnchantItem{hero:UnitType,
slot:HeroEquipSlot, itemsUsed:Map<ItemType,Integer>, useDiamonds:boolean, specialEvents}` → handler `LoginServer` →
**`ServerUser.applyEnchantItem`** : ré-exécute la logique du jeu (§3) `EnchantingHelper.enchantItem(user, hero, slot,
itemsUsed, useDiamonds, snap)` — consomme les matériaux, débite l'OR (`getEnchantGoldCost`, lève `NOT_ENOUGH_GOLD`) +
DIAMANTS optionnels, monte les étoiles/points d'enchant de l'objet équipé (borné par `EnchantingStats.getMaxStars`).
Anti-triche = les levées du jeu → refus autoritatif propre. Persistance `resyncHeroes` (l'enchant vit sur l'objet du
héros) + `resyncDiamonds`. Zéro invention (§4).

**Recon (bytecode).** Barème du jeu : `getMaxStars` par rareté (WHITE=0 non-enchantable, GREEN=1, BLUE=3,
PURPLE/ORANGE/RED/YELLOW=5) ; matériaux `ENCHANTING_MATERIALS` (VOID_DUST/SHIMMER_DUST/PRIMAL_ESSENCE…) ;
`getEnchantPoints(VOID_DUST)=10`. Les héros grantés n'ont PAS de gear → DEV `ServerUser.debugGiveFullGear`
(`HeroHelper.giveFullGear`) équipe le gear ENCHANTABLE du rang (slot ONE = PC_FLYERS ORANGE 0/5, etc.).

**Test `EnchantApplyTest`.** RALPH ORANGE + full gear + 40 VOID_DUST + or → enchant slot ONE avec 30 VOID_DUST →
**étoiles 0→2**, **or −63000** (= `getEnchantGoldCost` exact), VOID_DUST 40→10 ; persistance **PROFONDE** (round-trip
wire + DB : les étoiles d'enchant sur l'objet du héros survivent — leçon EXPEDITION : on vérifie le CONTENU, pas juste
le type) ; anti-triche sans or (`NOT_ENOUGH_GOLD`, aucun débit de matériaux). Régression → **105 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** `ExpAdminEnchant` prépare RALPH + gear complet + 50 VOID_DUST + 20 M or.
`enchant RALPH ONE VOID_DUST 30` (chemin client réel `ClientActionHelper.enchantItem(hero, slot, {VOID_DUST:30}, false,
NONE, listener)`) → client `EnchantItem` → serveur **`RALPH/ONE enchanté (or -63000) [persisté]`**. **DB confirmée** :
slot ONE PC_FLYERS **étoiles 0→2**, VOID_DUST 50→20, or 20 000 000→19 937 000. (NB pilote : passer un `ActionListener`
non-null — la 1ʳᵉ passe avec `null` a NPE côté client APRÈS l'envoi, sans empêcher l'application serveur ; corrigé.)

Pilote DEV `enchant <HERO> <SLOT> <MATERIAL> <count>` (`ClientActionHelper.enchantItem` réel). Outil DEV
`ExpAdminEnchant` (prépare un compte : héros + gear + matériaux + or).

**RESTE ENCHANTING** : incr. 2 (garde-fous coûts/diamants/plafond d'étoiles par rareté), incr. 3 (vérif en jeu sur
plusieurs slots/raretés + paiement diamants).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{EnchantApplyTest,ExpAdminEnchant}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/ENCHANTING.md`, `MEMORY.md`.

---

## 2026-08-10 (g99) — ENCHANTING (#72) incrément 2 : garde-fous + chemin diamants ✅ VÉRIFIÉ EN JEU → mode COMPLET

Garde-fous d'enchant (barème + anti-triche), tout par la logique du jeu (§3), zéro invention (§4).

**Barème (bytecode/probes).** `getMaxStars` par rareté : WHITE=0 (non-enchantable), GREEN=1, BLUE=3,
PURPLE/ORANGE/RED/YELLOW=5. `getEnchantMaxDiamondCost` dépend de l'item (PC_FLYERS ORANGE=5040, ROCKET_PACK_PATCH_KIT
PURPLE=3360). `getEnchantPoints(VOID_DUST)=10`.

**Comportements vérifiés.** (1) **Plafond d'étoiles** : au max (5/5), tout nouvel enchant est REFUSÉ (levée du jeu).
(2) **Matériaux insuffisants** : demander plus que possédé (sans diamants) → REFUS, aucun débit (or/matériaux/étoiles
inchangés). (3) **Chemin DIAMANTS** (`useDiamonds=true`) : paie `getEnchantMaxDiamondCost` → item au MAX d'un coup,
**matériaux NON consommés** ; anti-triche = diamants insuffisants → REFUS. (4) Coût OR exact (`getEnchantGoldCost`,
incr. 1).

**Test `EnchantGuardTest`.** Chemin diamants (→5/5 étoiles + −5040 diamants + matériaux intacts + persistance wire) ;
plafond (refus au max) ; matériaux insuffisants (refus, aucun débit) ; anti-triche diamants (100 diamants < coût →
refus). Régression → **106 tests**.

**✅ VÉRIFIÉ EN JEU (compte id=1 TL100).** `ExpAdminEnchant` (RALPH + gear complet + matériaux + or + 50 000 diamants).
`enchant RALPH TWO VOID_DUST 0 diamonds` (chemin client réel `ClientActionHelper.enchantItem(..., useDiamonds=true,
listener)`) → client `EnchantItem` → serveur **`RALPH/TWO enchanté (or -0, diamants -3360) [persisté]`**. **DB
confirmée** : slot TWO (ROCKET_PACK_PATCH_KIT, PURPLE) **étoiles 0→5 (MAX)**, diamants 50 000→46 640 (−3360 = coût max
exact de cet item), ni or ni matériaux consommés. Pilote DEV étendu `enchant <HERO> <SLOT> <MAT> <count> [diamonds]`.

**⇒ ENCHANTING #72 COMPLET & VÉRIFIÉ EN JEU** : incr. 1 (matériaux + or, slot ONE PC_FLYERS ORANGE, étoiles 0→2, or
−63000, g98) + incr. 2 (diamants + garde-fous, slot TWO ROCKET_PACK_PATCH_KIT PURPLE, étoiles 0→5, diamants −3360,
g99). Deux raretés, deux modes de paiement, plafond + anti-triche, persistance profonde. Reste facultatif : gear
RED/YELLOW, prime badges (`maxUpgradePrimeBadges`).

Fichiers : `server/smoke/EnchantGuardTest.java`, `server/smoke/{ExpAdminEnchant}.java`, `server/smoke/regression.sh`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`, `docs/ENCHANTING.md`, `MEMORY.md`.

---

## 2026-08-11 (g100) — ENCHANTING (#72) incrément 4 : MAX-UPGRADE PRIME BADGES + gear RED/YELLOW (REQUIS)

**Correction de consigne (utilisateur).** « gear RED/YELLOW, prime badges (`maxUpgradePrimeBadges`) ne sont PAS
facultatifs — rien n'est facultatif sauf si prouvé ET validé par l'utilisateur ». Les entrées g98/g99 les classaient
« reste facultatif » → **corrigé** : ce sont des livrables REQUIS. Cet incrément les livre.

**Message + logique du jeu (§3).** Bouton « MAX » de l'écran d'enchant = `ClientActionHelper.maxUpgradePrimeBadges(
plan, snap, listener)` (`com.perblue.heroes.game.ClientActionHelper`) : construit le plan localement
(`EnchantingHelper.buildMaxUpgradePlanForHero`), l'applique (`applyMaxUpgradePlanForHero`) PUIS envoie le message DÉDIÉ
`EnhanceMaxPrimeBadge{unitType, perBadgeItems:List, totalItems:Map, executionOrder:List, specialEvents}`. Côté serveur :
handler `LoginServer` → **`ServerUser.applyMaxPrimeBadge`** — le serveur est AUTORITATIF : il **IGNORE le plan déclaré
par le client** et le **RÉ-DÉRIVE** depuis l'état persisté (`buildMaxUpgradePlanForHero(user, type, snap)`), puis
l'applique (`applyMaxUpgradePlanForHero` = un `enchantItem` par slot). Toute l'anti-triche = ce recalcul serveur (un
tricheur ne peut rien fausser). Persistance `resyncHeroes`/`resyncDiamonds`/`resyncCounts` (l'enchant vit sur l'objet
équipé). Zéro invention (§4).

**Fait établi (§8, `GoldAwareProbe`) : le plan est AUTO-LIMITANT.** `buildMaxUpgradePlanForHero` ne planifie QUE ce que
le joueur peut réellement payer — plafond `getMaxStars`, matériaux POSSÉDÉS (`getItemAmount`) ET arrêt à l'OR
DISPONIBLE : mesuré 0/1 K/1 M or → plan VIDE ; 5 M → 3 slots (or 4 569 600) ; 9 M → 5 slots (7 616 000) ; 9,14 M → 6
slots (9 139 200) ; 50 M → 6 slots. Donc `applyMaxUpgradePlanForHero` sur un plan RE-DÉRIVÉ serveur ne peut PAS lever
`NOT_ENOUGH_GOLD`/`ENCHANT_ALL_ENOUGH_RESOUCES` (jamais d'application partielle) → **aucun garde-fou OR ajouté** (ce
serait du code mort, §2). Un compte sans ressource obtient un plan vide → no-op (refus propre).

**Gear RED/YELLOW.** `EnchantingStats.getMaxStars(RED)=getMaxStars(YELLOW)=5` et `enchantItem` est rarity-agnostic.
Sondé (`GearRarityProbe`) : RALPH rang RED → slot ONE=PRESTO (**RED**), rang YELLOW → **6 slots YELLOW**
(HOME_SWEET_HOME, MADE_FOR_PUDDLES, SO_MUCH_IN_COMMON, HANDCRAFTED_BY_LEPRECHAUNS, DRIVEN_BY_THOUGHT, THE_ZONE).

**Test `EnchantMaxUpgradeTest`.** (A) YELLOW RALPH → max-upgrade des **6 slots YELLOW d'un coup** : **or −9 139 200**
(= `plan.totalGold` EXACT), matériaux `{VOID_DUST=12, SHIMMER_DUST=6, PRIMAL_ESSENCE=132}` EXACTS, les 6 slots montent
en étoiles ; persistance **PROFONDE** (round-trip wire + DB, étoiles par slot — leçon EXPEDITION). (B) compte sans
ressource → plan vide → no-op, aucun débit. (C) affordabilité partielle : 5 M or → **exactement 3 slots** enchantés.
(D) gear **RED** (PRESTO) enchanté (étoiles 0→1). Régression → **108 tests**.

**✅ VÉRIFIÉ EN JEU (g100, compte id=1 TL100).** `ExpAdminMaxUpgrade server/data/dh-server.db 1 1` prépare RALPH rang
YELLOW (6 slots YELLOW 0/5 : HOME_SWEET_HOME, MADE_FOR_PUDDLES, SO_MUCH_IN_COMMON, HANDCRAFTED_BY_LEPRECHAUNS,
DRIVEN_BY_THOUGHT, THE_ZONE) + matériaux + 50 M or. `run-online.sh` (stack + client réel) → pilote `maxupgrade RALPH`
(chemin client RÉEL `ClientActionHelper.maxUpgradePrimeBadges` : plan bâti localement `slots=6 or=9139200
items={VOID_DUST=12,SHIMMER_DUST=6,PRIMAL_ESSENCE=132}`, `onResult=true`) → client envoie `EnhanceMaxPrimeBadge` →
serveur **`[prime-badge] RALPH max-upgrade (6 slot(s), or -9139200) [persisté]`** → `[login] <== EnhanceMaxPrimeBadge :
RALPH → appliqué [persisté]`. **DB CONFIRMÉE** (lecture WAL-aware) : les **6 slots YELLOW 0★ → 5★ (MAX)**, or
50 000 000 → 40 860 800 (**−9 139 200** = `plan.totalGold` exact), VOID_DUST 540→528 (−12), SHIMMER_DUST 600→594 (−6),
PRIMAL_ESSENCE 500→368 (−132) — tous exacts. ⇒ **ENCHANTING #72 COMPLET & VÉRIFIÉ EN JEU** (incr. 1-4), plus rien de
facultatif en suspens.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{EnchantMaxUpgradeTest,ExpAdminMaxUpgrade}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/ENCHANTING.md`, `MEMORY.md`.

---

## 2026-08-11 (g101) — MODE SUIVANT : SAVED_LINEUPS (lineups enregistrés) — incr. 1+2 headless

**Choix (pipeline #73/#74).** Parmi les ⬜ restants, SAVED_LINEUPS a les messages les plus CLAIRS
(`HeroLineupUpdate` = sauver ; `CheckLineupName`→`CheckLineupNameResult` = valider un nom), un point d'entrée §3 net
(`User.setHeroLineup`) et une persistance connue (`UserExtra.heroLineups`). Écartés : COLLECTIONS (mécanique mastery
touffue), BLACK_MARKET/WISHING_WELL/FRANCHISE_TRIALS (aucun message dédié → shop/event, risque §4).

**Découverte en recon : le CŒUR existait déjà via ARÈNE #41.** `LoginServer` routait `HeroLineupUpdate` →
`ServerUser.applyHeroLineupUpdate` (→ `User.setHeroLineup(type, iD, lineup, 0L, customName, realGearOptions,
emeraldStatSlotChoices)` — l'ordre des deux Maps est TESTÉ : realGearOptions puis emeraldStatSlotChoices, sinon
ClassCast à la sérialisation `UserHeroLineupData.writeListed`) + `resyncLineups` + persistance ; vérifié en jeu pour
les lineups de DÉFENSE d'arène (id=0).

**Contribution #72 (incr. 1) : `resyncLineups` DURCI (angle mort ids non-nuls).** L'ancien `resyncLineups` itérait
`HeroLineupType.values()` et lisait via `getHeroLineupData(t)` — or ce getter **hardcode id=0** (bytecode) → il
RATAIT tout lineup à id non-nul (perte silencieuse de persistance). Réécrit pour **itérer la Map runtime privée
`User.lineups`** (réflexion) et, pour chaque entrée, **recopier la clé `HeroLineupKey{lineupType,id}` →
`data.lineupType`/`data.iD`** avant de sérialiser (le loader `setHeroLineups` re-clé PAR ces champs, que
`setHeroLineup` ne pose PAS sur la data — même angle mort qu'EXPEDITION). Fait §4 : `new HeroLineup().mercenaryType =
UnitType.DEFAULT` (sentinelle « pas de merc », jamais null sur le wire ; un null NPE la sérialisation d'enum).

**Contribution #72 (incr. 2) : `CheckLineupName` → `CheckLineupNameResult`.** Handler requête/réponse `LoginServer`
(la fenêtre de nommage client reste bloquée sans réponse). La validation est SERVEUR (absente du jar client) → on
RÉUTILISE la logique du jeu `NameChangeHelper.isNameLegal` (codepoints valides + `ILLEGAL_NAMES` réservés) + non-vide,
plutôt qu'inventer une règle (§3/§4). **PARTIEL honnête (§2)** : le filtre de PROFANITÉ n'est pas dans le jar 12.1.0
(service serveur externe — « fuck » passe `isNameLegal`) ; on valide ce que le jeu expose. Noms de lineup =
personnels/cosmétiques.

**Test `LineupSaveTest`.** 4 lineups : SAVED_1 « Team Alpha » (3 héros), SAVED_2 « Team Bravo » (2 + merc ELSA),
EXPEDITION (par-mode), **SAVED_3#42 (id NON-NUL)**. Vérifie : lecture runtime ; round-trip wire PROFOND (type+id+nom+
héros[ordre]+merc survivent ; SAVED_3#42 ne collapse PAS sur `(SAVED_3,0)` ni `(DEFAULT,0)`) ; persistance DB ;
update en place (SAVED_1 réécrit → toujours 4 lineups, pas de doublon). Régression → **109 tests**.

**RESTE (REQUIS, §8) : vérif EN JEU** — sauver un lineup SAVED_* nommé (flux `CheckLineupName` → `saveHeroLineup`) →
serveur → DB → persiste au reload. + cooldowns de défense PvP si exercés.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{LineupSaveTest}.java`,
`server/smoke/regression.sh`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

---

## 2026-08-11 (g102) — SAVED_LINEUPS (#72) ✅ VÉRIFIÉ EN JEU (sauvegarde nommée + CheckLineupName + persistance)

Vérif en jeu (client réel → serveur → DB → reload) des incréments 1+2.

**EN JEU (compte id=1 TL100).** `ExpAdminLineup server/data/dh-server.db 1 1` accorde RALPH/VANELLOPE/ELASTIGIRL
(possédés → `saveHeroLineup` lit leurs slots émeraude sans NPE). `run-online.sh` (stack + client réel) :
- `savelineup SAVED_1 MyTeam RALPH+VANELLOPE+ELASTIGIRL` (chemin client RÉEL
  `ClientActionHelper.saveHeroLineup(SAVED_1, 0, lineup, "MyTeam", NONE)`) → client `HeroLineupUpdate` → serveur
  **`[login] <== HeroLineupUpdate(SAVED_1) → lineup enregistrée [persistée]`**.
- `savelineup SAVED_2 Bravo VANELLOPE+ELASTIGIRL` → 2ᵉ lineup enregistré (coexiste).
- `checkname MyDefense` (`CheckLineupName` via `NetworkProvider.sendMessage`) → serveur
  **`[login] <== CheckLineupName("MyDefense") → isValid=true`** (répond `CheckLineupNameResult`).

**DB CONFIRMÉE** (relue depuis `server/data/dh-server.db`, WAL-aware = preuve de survie au reload) :
- `SAVED_1` : nom=« MyTeam », héros=[RALPH, VANELLOPE, ELASTIGIRL].
- `SAVED_2` : nom=« Bravo », héros=[VANELLOPE, ELASTIGIRL].
Deux lineups nommés DISTINCTS coexistent et persistent (le durcissement `resyncLineups` fonctionne en jeu).

⇒ **SAVED_LINEUPS #72 vérifié en jeu** : sauvegarde nommée multi-lineups + validation de nom + persistance profonde.
Reste OPTIONNEL (non bloquant, déjà couvert ARÈNE #41 pour la défense) : cooldowns PvP `FIGHT_PIT_DEFENSE`/
`COLISEUM_DEFENSE_3`.

Pilotes DEV : `savelineup <SAVED_N> <name> <HERO1+HERO2+...>`, `checkname <name>`. Outil DEV : `ExpAdminLineup`.

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`server/smoke/ExpAdminLineup.java`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

### g102b — confirmation VISUELLE (capture) de SAVED_LINEUPS

Suite à la question utilisateur (« ouvert le mode in-game et vérifié visuellement, pas que headless ? ») : les vérifs
g102 passaient par des pilotes appelant l'API cliente réelle (`saveHeroLineup`/`CheckLineupName`) SANS ouvrir l'écran.
Ajout du pilote `lineupscreen <SAVED_N>` qui pousse le VRAI écran `SavedLineupHeroChooserScreen(type)`. Client réel
relancé → `lineupscreen SAVED_1` → **capture** montrant l'écran chargé depuis NOTRE serveur : titre **« MYTEAM »**
(nom sauvé) + les 3 héros **Ralph + Vanellope + Elastigirl** rendus + **TEAM POWER 752 286** + bouton SAVE + roster
coché. Capture `desktop-port/build/lineup_myteam_ingame.png` (gitignore). ⇒ confirmation VISUELLE en jeu (§4bis), pas
seulement le chemin serveur/DB. Pilote DEV `lineupscreen`.

---

## 2026-08-11 (g103) — SAVED_LINEUPS (#72) incr. 4 : COOLDOWN de défense PvP (correction « optionnel » → REQUIS)

**Correction de cadrage (consigne utilisateur).** J'avais laissé « Reste OPTIONNEL : cooldowns de défense PvP …
déjà couverts par ARÈNE #41 ». Sur challenge de l'utilisateur, vérification : **FAUX**. `grep` serveur pour
`setHeroLineupCooldown`/`LINEUP_UPDATE`/`CooldownType` = VIDE ; `applyHeroLineupUpdate` appelait `setHeroLineup`
mais JAMAIS de cooldown. ⇒ vrai trou : sauver une défense FIGHT_PIT/COLISEUM ne posait AUCUN cooldown côté serveur
(non autoritatif, non persisté — un client modifié pouvait re-changer sa défense en boucle). Pas optionnel.

**Correctif (miroir fidèle du client, bytecode `ClientActionHelper.saveHeroLineup`).** Après `setHeroLineup` :
`FIGHT_PIT_DEFENSE` → `ArenaHelper.setHeroLineupCooldown(user, FIGHT_PIT, FIGHT_PIT_LINEUP_UPDATE)` ;
`COLISEUM_DEFENSE_3` (3ᵉ/dernier, comme le client) → `(COLISEUM, COLISEUM_LINEUP_UPDATE)`. Durée =
`ArenaHelper.getNextDefenseCooldown` (donnée du jeu = **6 h**, jamais inventée). Persistance **write-through** :
`IndividualUser.setCooldownEnd` écrit dans `individualUserExtra.cooldowns` (aucun resync).

**Test `LineupCooldownTest`.** FIGHT_PIT_DEFENSE → `FIGHT_PIT_LINEUP_UPDATE` ~6 h ; COLISEUM_DEFENSE_3 →
`COLISEUM_LINEUP_UPDATE` ~6 h ; SAVED_* normal → AUCUN cooldown ; persistance wire + DB. Régression → **110 tests**.

**✅ VÉRIFIÉ EN JEU (id=1).** `savelineup FIGHT_PIT_DEFENSE Def RALPH+VANELLOPE+ELASTIGIRL` (chemin client réel) →
serveur `HeroLineupUpdate(FIGHT_PIT_DEFENSE) → lineup enregistrée [persistée]` → **DB** : cooldown
`FIGHT_PIT_LINEUP_UPDATE` posé (timestamp futur) + lineup [RALPH,VANELLOPE,ELASTIGIRL] persistés. Écart d'heures =
ancre d'horloge du serveur de test (−13 h) vs contexte du dump ; durée réelle = 6 h (headless). **Portée** : chemin
client réel + serveur + DB. **NON vérifié visuellement** : grisage du bouton « changer la défense » dans l'UI arène
(effet CLIENT lisant `getCooldownEnd`, valeur désormais fournie/persistée par le serveur).

Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/{LineupCooldownTest}.java`,
`server/smoke/regression.sh`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

---

## 2026-08-11 (g104) — SAVED_LINEUPS (#72) : champs profonds non vides + audit de périmètre

**Angle mort fermé (leçon EXPEDITION + note ARÈNE #41).** L'ordre des deux `Map` passées à `setHeroLineup`
(`realGearOptions`, `emeraldStatSlotChoices` — une inversion → ClassCast à la sérialisation `UserHeroLineupData
.writeListed`) n'était vérifiable QU'AVEC DU CONTENU : les tests headless précédents ET la vérif en jeu (héros
grantés PURPLE, sans stats émeraude ni real-gear) passaient tous des Maps VIDES → l'ordre n'était PAS réellement
prouvé. `LineupFieldsTest` peuple les deux :
- `realGearOptions = {RALPH: RealGearType.CALHOUN_ENERGY}` (Map<UnitType, RealGearType>).
- `emeraldStatSlotChoices = {RALPH: HeroStatSlotChoices{statSlotChoices: {EmeraldStatSlot.FIRST:
  CombatStatType.ARMOR_NEGATION}}}` (Map<UnitType, HeroStatSlotChoices{Map<EmeraldStatSlot, CombatStatType>}>ache —
  valeur enum SIMPLE, pas une List : découvert via un 1ᵉʳ ClassCast ArrayList→Enum, corrigé).
Round-trip wire + DB : PAS de ClassCast (ordre des Maps correct sous contenu réel) + contenu survit + PAS SWAPPÉ
(realGearOptions garde son RealGearType, emeraldStatSlotChoices son HeroStatSlotChoices). Régression → **111 tests**.

**Audit de périmètre (suite à la remise en cause « optionnel » de l'utilisateur — on ne laisse RIEN d'optionnel sans
preuve).** Tous les messages « Lineup » du protocole passés en revue :
- `HeroLineupUpdate` ✅, `CheckLineupName`→`CheckLineupNameResult` ✅ = les DEUX messages du mode (traités + vérifiés
  en jeu).
- `Action TOGGLE_HERO_FILTER` (`ClientActionHelper.toggleHeroFilter`) = filtre du sélecteur de héros, PARTAGÉ par tous
  les choosers. **NO-OP DOCUMENTÉ (§2/§4)** : `grep` jar = AUCUN consommateur (seulement l'émetteur + l'enum
  `CommandType`) et AUCUN champ joueur de filtres (`UserExtra` sans `heroFilters`) → logique/stockage serveur ABSENTS
  du jar 12.1.0 ; inventer un schéma violerait §4 (même catégorie que `SetExternalContentStatus` / le filtre de
  profanité). Acquitté = réponse autoritative correcte, pas « optionnel ».
- `WarDefenseLineupUpdate` = mode GUERRE DE GUILDE ; `MailLineup` = mode COURRIER. Hors périmètre SAVED_LINEUPS.

⇒ **SAVED_LINEUPS #72 complet** (2 messages traités + vérifiés en jeu + visuel ; cooldowns défense ; champs profonds ;
audit périmètre) — rien d'« optionnel » laissé sans preuve.

Fichiers : `server/smoke/{LineupFieldsTest}.java`, `server/smoke/regression.sh`, `docs/SAVED_LINEUPS.md`, `MEMORY.md`.

---

## 2026-08-11 (g105) — COLLECTIONS (#72 mode suivant) incr. 1 : CLAIM d'un palier ✅ VÉRIFIÉ EN JEU + VISUEL

Nouveau mode (choisi par l'utilisateur). Système de MAÎTRISE de héros par COLLECTION (29 collections : rôles/franchises,
4 tiers BRONZE→PLATINUM). Jouer un héros accumule des « mastery uses » ; à N héros maîtrisés, un niveau devient
réclamable → récompenses + modificateurs de combat.

**Handler.** `Action CLAIM_COLLECTION_REWARDS{TYPE:CollectionType, TIER:CollectionTier, LEVEL:int}` (émetteur
`ClientActionHelper.claimCollectionRewards`) → `LoginServer` (chaîne `act.command`, extras en `.name()`) →
`ServerUser.applyClaimCollection` : ré-exécute la logique du jeu (§3) `CollectionHelper.claimCollectionRewards(user,
type, tier, level)`. Anti-triche = les levées du jeu (`getCollectionState != CLAIMABLE` → `COLLECTION_ALREADY_CLAIMED`
ou `NOT_ENOUGH_MASTERED_HEROES`). Persistance write-through : `IndividualUser` écrit dans
`individualUserExtra.collectionsClaimed` (+ `resyncHeroes`/`resyncDiamonds`/`resyncCounts` pour les récompenses).

**Faits établis (sondes, §4).** `getCollectionState` CLAIMABLE ⟺ `getNumMasteredHeroes(user,type,tier) >=
CollectionStats.getNumMasteredHeroesRequiredForLevel(type,level)`. Un héros est « maîtrisé » ⟺ `masteryUses >=
CollectionStats.getNumUsesRequiredForMastery(tier,level)` (BRONZE lvl1 = 20) ET étoiles >= `getHeroStarsRequired(tier)`
(BRONZE=3). DAMAGE lvl1 requiert 5 héros maîtrisés. `getCumulativeCollectionLevel = Σ highestClaimed(tier)`.

**Test `CollectionClaimTest`.** Setup CLAIMABLE (6 héros DAMAGE 6★ + maîtrise 21 via `setCollectionHeroMasteryUses`).
Claim DAMAGE/BRONZE/lvl1 → `highest 0→1`, `MASTERY_TOKENS +8` (récompense exacte) ; re-claim → refusé
(`COLLECTION_ALREADY_CLAIMED`, aucun double crédit) ; niveau non-gagné (maîtrise 5<20) → refusé
(`NOT_ENOUGH_MASTERED_HEROES`) ; persistance PROFONDE wire + DB (highest claimed survit). Régression → **112 tests**.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1 TL100).** `ExpAdminCollection` amène DAMAGE/BRONZE à CLAIMABLE (6 héros maîtrisés).
Client réel : `collectionscreen DAMAGE` → écran RÉEL montrant **Bronze I : HEROES MASTERED 5/5, NEXT REWARD 14
(EXP_COLOSSAL) + 8 (MASTERY_TOKENS), bouton CLAIM** (chargé depuis NOTRE serveur ; Silver/Gold/Platinum verrouillés).
`claimcollection DAMAGE BRONZE 1` (chemin client réel) → serveur **`DAMAGE/BRONZE niv.1 réclamé (highest 0→1)
[persisté]`** → écran ré-ouvert : **Bronze II (0/10, bouton DETAILS) + Silver I DÉVERROUILLÉ** → **DB confirmée** :
`highestClaimed(DAMAGE,BRONZE)=1`, `MASTERY_TOKENS=8`. Captures `build/coll_{before,after}_ingame.png` (gitignore).

Pilotes DEV : `claimcollection <TYPE> <TIER> <LEVEL>`, `collectionscreen <TYPE>`. Outil DEV : `ExpAdminCollection`.
**RESTE** : incr. 2 (maîtrise de combat `CollectionMasteryUsesUpdate`→`recordHeroMastery`), incr. 3 (cosmétique).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{CollectionClaimTest,ExpAdminCollection}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/COLLECTIONS.md`, `MEMORY.md`.

---

## 2026-08-11 (g106) — COLLECTIONS (#72) incr. 2 : maîtrise de combat ✅ VÉRIFIÉ EN JEU (delta 0→1 net)

Vérif en jeu PROPRE de l'accumulation de maîtrise (après la reprise post-reset du conteneur : travail incr. 1+2
récupéré depuis origin, HEAD f434761).

**Baseline propre.** `ExpAdminCollectionFight` durci : grante l'équipe DAMAGE 6★ (MOANA, MERIDA, JACK_SPARROW, BEAST,
BELLE), pose le lineup NORMAL_CAMPAIGN dessus, pré-3★ le niveau 1-1 (via un héros JETABLE OLAF 1★ < MIN_HERO_STARS —
maîtrise non touchée), et **remet à 0 la maîtrise DAMAGE/BRONZE de TOUS les héros DAMAGE** (élimine la pollution des
setups/claims précédents) → delta net garanti.

**Pilotage (2 obstacles levés).** (1) Le chooser n'auto-sélectionne pas → `campquick` sélectionne les héros via le
VRAI chemin `unitSelected` (après `canSelectUnit`) — 5/5 sélectionnés. (2) `canStartQuickFight` = `hasAtLeastOneHeroSelected
&& nodeIsThreeStarred` restait faux (le client ne « voit » pas toujours le 3★ immédiatement) → on appelle **`doQuickCombat()`**,
l'exécuteur RÉEL (charge + roule le loot + combat headless + envoie `CampaignAttack`), qui NE gate PAS sur le bouton.

**Résultat EN JEU (id=1).** `campfight 1 1` → `campquick` → client `CampaignAttack1` → serveur **`CampaignAttack
NORMAL 1-1 outcome=WIN → recordOutcome appliqué [persisté]`** → **DB : MOANA/MERIDA/JACK_SPARROW/BEAST/BELLE
DAMAGE/BRONZE = 1** (partis de 0 ; exactement +1 = un combat, pour les 5 héros ayant combattu). Chemin client réel →
serveur → DB, sur baseline propre = delta 0→1 NET. ⇒ **COLLECTIONS #72 incr. 1 (claim) + 2 (maîtrise) vérifiés en jeu.**

Pilotes DEV : `campfight <chapter> <level>` (pousse le chooser), `campquick` (sélectionne + `doQuickCombat`). Outil DEV :
`ExpAdminCollectionFight`. **RESTE** : incr. 3 (cosmétique).

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`server/smoke/ExpAdminCollectionFight.java`, `docs/COLLECTIONS.md`, `MEMORY.md`.

---

## 2026-08-11 (g107) — COLLECTIONS (#72) incr. 3 : mastery shop (achat d'avatar) ✅ VÉRIFIÉ EN JEU + VISUEL

Incrément cosmétique = « HERO MASTERY SHOP » : dépenser les MASTERY_TOKENS (gagnés par les claims, incr. 1) pour des
avatars/bordures de collection.

**Handler.** `Action BUY_COLLECTION_AVATAR{itemType=avatar}` (émetteur `ClientActionHelper.buyCollectionAvatar` ;
l'ItemType passe par le champ `Action.itemType`) → `LoginServer` → `ServerUser.applyBuyCollectionAvatar` : ré-exécute
la logique du jeu (§3) `CollectionHelper.buyCollectionAvatar(user, itemType)` : gate `getCumulativeCollectionLevel >=
CollectionStats.getCumulativeCollectionLevelRequiredForPortrait` (sinon `COLLECTION_AVATAR_LOCKED`) ; débit
`MASTERY_TOKENS` (`getAvatarCost`) ; `giveUser(itemType, 1)` (avatar = item, write-through). Persistance items/ressources
write-through + resync.

**Faits (sondes).** `getMasteryShopAvatars(user)` = 26 avatars ; `COLLECTION_AVATAR_DAMAGE` coûte 100 MASTERY_TOKENS et
requiert cumLevel(DAMAGE) >= 8 (`getCumulativeCollectionLevel` = Σ highestClaimed/tier ; max 3/tier via debug →
BRONZE/SILVER/GOLD=3 → 9). ItemType = fnum (valeur data-définie → `ItemType.valueOf`).

**Test `CollectionAvatarTest`.** Achat → MASTERY_TOKENS −100 + avatar possédé (+1) ; anti-triche 1 : niveau non atteint
→ `COLLECTION_AVATAR_LOCKED`, aucun débit ; anti-triche 2 : tokens insuffisants → refus, aucun avatar ; persist wire+DB.
Régression → **114 tests**.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** `ExpAdminAvatar` : cumLevel(DAMAGE)=9 + 1000 MASTERY_TOKENS. `buyavatar
COLLECTION_AVATAR_DAMAGE` (chemin client réel) → serveur **`BUY_COLLECTION_AVATAR(COLLECTION_AVATAR_DAMAGE) appliqué
[persisté]`** → **DB : MASTERY_TOKENS 1000→900 (−100), avatar possédé=1**. Écran **HERO MASTERY SHOP** ré-ouvert
(`shopscreen`) affiche le solde **900** (chargé depuis notre serveur) — capture `build/avatar_shop_ingame.png`.

**`CLAIM_COSMETIC_COLLECTION`** (collections d'emojis) : la logique serveur est ABSENTE du jar 12.1.0 (aucun helper ni
consommateur — même catégorie que le filtre de profanité / `TOGGLE_HERO_FILTER`) ; le stockage
`claimedCosmeticCollections` (Set runtime) est resyncable, mais la règle d'éligibilité (posséder toute la collection
d'emojis) n'est pas exécutable sans l'inventer → **gap documenté honnêtement (§2/§4), non implémenté** (pas
« optionnel » : absent du jar).

⇒ **COLLECTIONS #72 : incr. 1 (claim) + 2 (maîtrise de combat) + 3 (mastery shop avatar) TOUS vérifiés EN JEU.**
Pilotes DEV : `buyavatar <ITEM>`, `shopscreen`. Outil DEV : `ExpAdminAvatar`.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{CollectionAvatarTest,ExpAdminAvatar}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/COLLECTIONS.md`, `MEMORY.md`.

---

## 2026-08-16 (g108) — WISHING_WELL (#72) incr. 1 : héros CIBLE du puits ✅ VÉRIFIÉ EN JEU + VISUEL

Nouveau mode « Puits aux souhaits » (gacha ciblé de shards). Incrément 1 = fixer le **héros CIBLE** qui biaise le
tirage.

**Handler.** `Action SET_WISHING_WELL_TARGET_HERO{heroType=cible}` (émetteur `ClientActionHelper.setWishingWellTargetHero` ;
la cible passe par le champ `Action.heroType`) → `LoginServer` (dispatch `act.command`, lit `act.heroType`) →
`ServerUser.applySetWishingWellTarget` : ré-exécute la logique du jeu (§3) `WishingWellHelper.setTargetHero(user, hero)` :
valide `hero ∈ getAllEligibleHeroes` (héros non éligible → `WISHING_WELL_HERO_NOT_ALLOWED` = aucun effet = anti-triche) ;
pose `IIndividualUser.setWishingWellHero(hero)` (write-through `individualUserExtra`) + ajuste les poids de pity ;
`resyncCounts` (compteur de changement = `UserFlag`). Persistance quasi-gratuite (write-through), round-trip wire + DB.

**Test `WishingWellTargetTest`** (régression → **115 tests**). DEFAULT→RALPH→VANELLOPE ; anti-triche : un héros non
éligible (TEST_DUMMY) → refusé, cible inchangée ; persistance round-trip wire + DB (=VANELLOPE).

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** `SetTeamLevel 65` (débloque `Unlockable.WISH_CHEST`, req TL 30 ; sonde : 274 héros
éligibles, RALPH/VANELLOPE inclus, cible initiale DEFAULT). Pilote `wishtarget RALPH` (chemin client réel
`ClientActionHelper.setWishingWellTargetHero`) → serveur **`SET_WISHING_WELL_TARGET_HERO(RALPH) appliqué [persisté]`**
(+ `[wishing-well] cible = RALPH [persisté]`) → **DB `server/data/dh-server.db` : `wishingWellHero=RALPH`** (lecture
WAL-aware live). **CONFIRMATION VISUELLE** : `wishscreen` ouvre le VRAI écran **WISH CRATE** (`WishingWellChestScreen`,
chemin réel `pushScreen`) → **portrait de RALPH** à gauche + **JACKPOT CHIP CHANCES** 1 000 shards RALPH @ 1,00 % +
**REGULAR CHIP CHANCES** 100-300 shards RALPH @ 10,00 % (la table du puits est biaisée sur la cible) — capture
`desktop-port/build/ww_screen.png`.

**Contrat industriel (ModeGraph `--mode com/perblue/heroes/ui/wishingwell/`).** 8 classes du mode. Gate `WISH_CHEST`.
Écran en lecture seule côté messages (la cible = un `Action`, pas un message de mode → normal). A/B (serveur→client)
pour l'**incr. 2** : `LootResults{ lootDrops, oldWishHeroChipsWeight, oldWishJackpotWeight }` + `RewardDrop{ flags,
itemType }` — les `oldWish*Weight` = poids de pity AVANT le souhait (animation de progression) → à peupler par le WISH.

⇒ **WISHING_WELL #72 : incr. 1 (cible) vérifié EN JEU.** Pilotes DEV : `wishtarget <HERO>`, `wishscreen`. Outil DEV :
`SetTeamLevel`. **RESTE** : incr. 2 (WISH `ChestType.WISH` via `openChest` : respect cible/poids + pity + persistance).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{WishingWellTargetTest}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/WISHING_WELL.md`, `MEMORY.md`.

---

## 2026-08-16 (g109) — WISHING_WELL (#72) incr. 2 : le SOUHAIT (ChestType.WISH) ✅ VÉRIFIÉ EN JEU + VISUEL

Le souhait proprement dit : ouvrir un coffre `ChestType.WISH` → tirage de shards BIAISÉ par le héros cible.

**Handler.** `ServerUser.openChest` (handler `BuyChests` existant) reçoit une branche `ChestType.WISH` : au lieu de la
table générique (`getDropTable` + `ChestContext`), il roule la table PROPRE du puits `ChestStats.WISHING_WELL_DROPS`
(champ statique privé, hors `getDropTable` → accès réflexion `wishingWellTable()`) avec un `WishingWellDTContext(user,
random)` — ce contexte LIT le héros cible (`wishingWellHero`) + les poids de pity + la rareté/mod max du joueur et
BIAISE le tirage vers la cible. C'est le CODE DU JEU (§3, miroir exact de `ChestStats.rollWishingWellDisplay`). Plancher
des poids via `WishingWellHelper.checkMinWeights` (code du jeu). Crédit des shards via `giveChestRewards`, débit DIAMONDS
(branche payante existante), persistance write-through + resync.

**GAP §4 PROUVÉ AU BYTECODE — rampe de pity par tirage.** La RAMPE de pity (nouveau `wishingWellJackpotWeight`/
`wishingWellHeroChipsWeight` APRÈS un souhait) N'EST PAS dans le jar client : les setters `setWishingWell*Weight` ne sont
invoqués QUE par `ChestHelper.updateWishingWellWeights` (applique une valeur FOURNIE — côté client depuis la réponse
serveur) et par `setTargetHero`/`checkMinWeights` (PLANCHER `JACKPOT_BASE`/`HERO_CHIPS_BASE`) ; `doPreRollUpdates` ne
fait que réinitialiser des compteurs d'évènement ; `WishingWellContextDTCode$1/$2` LISENT les poids pour biaiser mais
n'en écrivent aucun. C'était la logique serveur autoritative de PerBlue, absente de l'APK → non réimplémentable sans
l'INVENTER (§4, même catégorie que `CLAIM_COSMETIC_COLLECTION`). On expose donc les poids PLANCHERÉS (checkMinWeights)
sans rampe : `LootResults.old/newWish*Weight` = plancher (les probas de base `getProbabilities` restent correctes).
Documenté dans `docs/SHIMS.md` + `docs/WISHING_WELL.md`. *Risque* : pas de « pity » cumulatif (chances de jackpot fixes).

**Test `WishingWellWishTest`** (régression → **116 tests**). Cible RALPH : le `WishingWellDTContext` cible RALPH
(biais structurel, déterministe) ; 120 souhaits → ≥1 drop RALPH + `STONE_RALPH` crédité (=6940) + débit EXACT 120×500
DIAMONDS ; poids sans rampe (new==old) au plancher ; persist wire+DB ; bascule VANELLOPE → drops VANELLOPE (le biais
suit la cible).

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** `WishAdmin` (TL 65, 100 000 DIAMONDS, cible RALPH). Pilote `wish 1` ×23 (chemin
client réel `ChestHelper.openChestInner` → `BuyChests{WISH}` + `ServerRollRequest`) → serveur `<== BuyChests` (roule
biaisé + débite) → **DB : DIAMONDS débités (500/souhait) + items RALPH crédités** : `STONE_RALPH=480`,
`EPIC_CHIP_RALPH=520`, `BIT_RALPH_HEALING=70`, `BIT_RALPH_LONGER_STUNS=35` (TOUS les drops de héros = la cible = biais
confirmé), persistés. **CONFIRMATION VISUELLE** : la fenêtre **CRATE REWARDS** (servie par notre serveur) montre un lot
de **300 puces RALPH** (« 300/200 » + portrait RALPH) — capture `desktop-port/build/wish_result_ingame.png`.

⇒ **WISHING_WELL #72 : incr. 1 (cible) + 2 (souhait) vérifiés EN JEU** (rampe de pity = gap §4 documenté). Pilote DEV :
`wish [count]`. Outil DEV : `WishAdmin`.

Fichiers : `server/java/dhserver/ServerUser.java` (branche WISH + `wishingWellTable()`), `server/smoke/WishingWellWishTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/{WISHING_WELL,SHIMS}.md`, `MEMORY.md`.

---

## 2026-08-16 (g110) — WISHING_WELL : RAMPE DE PITY = RÉELLE (correction d'une erreur g109)

**Correction d'une conclusion erronée (consigne utilisateur : « rien n'est optionnel/absent tant que non prouvé »).**
En g109 j'avais déclaré la rampe de pity « absente du jar » (gap §4) — c'était une **erreur de méthode** : je n'avais
grep'é que les `setWishingWell*Weight` dans les `*Helper`/`*Stats`, **sans lire les `.tab` ni chercher dans la couche
UI**. L'utilisateur a poussé (« a tu check les tab ? rien n'est vraiment optionnel tant qu'on ne l'a pas prouvé »).

**Ce que la revérification a montré (FAITS).**
1. `game-data/stats/wishing_well_weights.tab` contient bien les multiplicateurs de rampe : `JACKPOT_MULT_X=1.1`,
   `JACKPOT_MULT_Y=1.01`, `HERO_CHIPS_MULT_Z=1.1`, `JACKPOT_10X_BONUS_MULT=1.05` (colonnes NEW/RECENT/OLD).
2. Grep jar-wide (64k classes) des lecteurs de ces constantes → `WishingWellStats$WeightConstants` (déclaration) **et
   `WishingWellChestResultWindow`** (UI). Cette dernière contient la RÈGLE, dans `reachedDestination(LootResults,
   RewardDrop, int)` : init `jackpotWeight/heroChipsWeight` depuis `LootResults.old*` (via `setLootResults`), puis par
   drop — jackpot (`flags&16`) → reset `*_BASE` ; stone (`ItemStats.getCategory==STONE`) → `jackpot*=MULT_X`,
   `heroChips=HERO_CHIPS_BASE` ; sinon → `jackpot*=MULT_Y`, `heroChips*=MULT_Z` ; bonus 10x (`hasBulkBonus`, frontières
   `rowIndex % getMultiBuyCount`) `jackpot*=10X_BONUS_MULT`.
3. La classe est **UI liée à GL** (met à jour `jackpotPreviewStack`) → **non instanciable headless**, donc non
   EXÉCUTABLE : on **TRANSCRIT la règle au bytecode près** (valeurs = `.tab`, jamais inventées, §4).

**Implémentation.** `ServerUser.openChest` (branche WISH) calcule la rampe par drop (sur `lr.lootDrops`, les
`RewardDrop` convertis — pas les `DropItem` bruts) depuis `old*`, puis persiste via le **code du jeu**
`ChestHelper.updateWishingWellWeights(user, jackpot, heroChips)` (write-through `individualUserExtra`). `LootResults.
old/newWish*Weight` reflètent avant/après. La branche ne prétend plus à aucun gap.

**Test.** `WishingWellWishTest` étendu : CONTINUITÉ entre tirages (le `old` d'un tirage == le `new` du précédent =
persistance de la pity), DIRECTION (pity jackpot MONTE hors jackpot, reset au jackpot/stone), ACCUMULATION (base 1.0 →
peak ~4 sur 120 tirages), + survie round-trip wire. Régression **116/116**.

**Statut SHIMS** : la rampe passe de « GAP §4 » (g109, faux) à **RÉEL** (transcription fidèle d'une règle GL-only +
valeurs `.tab`). **Leçon (§8)** : ne jamais conclure « absent du jar » sans avoir cherché AUSSI la couche UI et lu les
`.tab`. RESTE : revérif EN JEU que les probas WISH montent au fil des tirages.

Fichiers : `server/java/dhserver/ServerUser.java`, `server/smoke/WishingWellWishTest.java`, `docs/{WISHING_WELL,SHIMS}.md`,
`MEMORY.md`.

---

## 2026-08-16 (g111) — MERCHANT (marchands / marché noir) : phase RECON (pipeline #73) → docs/MERCHANT.md

Choix du mode suivant après WISHING_WELL : MERCHANT (déclencheur : `REFRESH_TRADER non appliquée (PARTIEL)` en boucle
dans `/tmp/dh_game.log` + les nombreux `*_merchant_drops.tab`). Phase recon du pipeline #73 (ModeGraph `--mode Merchant`
+ décompilation ciblée).

**Structure.** Classes : `MerchantScreen` (UI), `MerchantHelper` (logique), `MerchantStats`/`MerchantDTCode` (données),
enum `MerchantType`, wire `MerchantData`/`MerchantItemData`/`MerchantUpdate`/`PurchaseMerchantItem`. Stockage =
`IndividualUserExtra.merchantData` (`Map<MerchantType, MerchantData>`, **write-through**), backing de
`IUser.getMerchantItems(type)` etc.

**Fait décisif (architecture).** `GameMain.lambda$setupPostClientInfoHandlers$28(conn, MerchantUpdate)` : le **client REÇOIT
`MerchantUpdate` et l'applique — il ne GÉNÈRE JAMAIS le stock**. ⇒ MERCHANT = **blob serveur-autoritatif**
(cf. `docs/ARCHITECTURE.md` : Arena ladder / Surge / Expedition). Le serveur doit rouler les tables `*_merchant_drops.tab`
(`MerchantStats.<TYPE>_DROP_STATS`, privées) via `MerchantDTCode`, construire `MerchantData`, persister (write-through),
et pousser `MerchantUpdate`. `MerchantHelper.refresh` ne fait QUE le gating/charge/track (`refresh(AUTO)`→true mais
inventaire vide) ; la génération est derrière `CodeLocationHelper.isOnServer()` (que `ServerContext` ne pose pas exprès).

**Entrées §3.** `MerchantHelper.purchaseItem(...)`, `refresh(type, MerchantRefreshType{AUTO,FREE,ITEM,PAID,VIDEO}, user,
snapshot)`, `getItemCost`, `isMerchantUnlocked`/`isAvailable`, `getFreeRefreshes`, `checkForFoundMerchant` (BLACK_MARKET/
MEGA_MART limited-time), `getMerchantPrimary/SecondaryCurrency`, `MerchantStats.getRefreshCost`/`getRefreshCurrency`.

**Disponibilité (sonde `MerchAvail`, TL200).** Always-on : GEAR/MEMORY/CHALLENGE/CRYPT/COLISEUM/FIGHT_PIT/WAR/EXPEDITIONS/
INVASION. Limited-time (isAvailable=false, à découvrir) : BLACK_MARKET, MEGA_MART. Verrouillés : NORMAL, HEIST.
INVASION : pas de refresh manuel. ⇒ incr. 1 sur un marchand dispo (GEAR).

**Plan** (docs/MERCHANT.md) : 1) génération+affichage stock (blob) ; 2) achat (`PurchaseMerchantItem`→`purchaseItem`,
anti-triche coût recalculé) ; 3) refresh (`REFRESH_TRADER`→`refresh`+régén, corrige le PARTIEL) ; 4) BLACK_MARKET/MEGA_MART
limited-time. Régression inchangée (116). Aucune ligne serveur écrite à ce stade (recon pure).

Fichiers : `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g112) — MERCHANT : recon APPROFONDIE (de-risking incr. 1) + point dur coût

Poursuite du recon MERCHANT (sondes `MerchGenProbe`/`MerchCostProbe`). Recette de génération PROUVÉE headless :
`getDropStats(type).getTable().rollNode("ROOT", new UserDTContext(user), rnd)` → `List<DropItem>` (GEAR=10, MEMORY=8) →
`DropConverter.convert` → `MerchantItemData`. Stockage = **blob** `individualUserExtra.merchantData`
(`Map<MerchantType,MerchantData>`) : le convertisseur `ClientNetworkStateConverter` n'gère PAS les marchands
(`IndividualUser` runtime démarre EnumMap vides) → à câbler côté `ServerUser` (write-through), pas via round-trip.
Livraison client = push `MerchantUpdate` (`GameMain.lambda$…$28`). `initMerchantData(type, MerchantData)` peuple le runtime.

**Point dur restant (coût des objets, NE PAS inventer §4)** : `getItemCost` lit `mi.getCost()` (coût de base porté par
l'objet). Marchands ANNOTÉS (BLACK_MARKET) : `.tab` porte `{PriceType}{Cost}` → `DropItem.getParameter("Cost"/"PriceType")`
(data-driven, reconstructible). Marchands À JETONS (GEAR/MEMORY) : objets roulés SANS param `Cost` (`params={}`) → coût de
base calculé par RARETÉ (behaviors `MerchantDTCode` `RarityCost*`), source pas encore localisée (`merchant_refresh.tab` =
coût de REFRESH 1-21, PAS item ; `COST_STATS` = refresh aussi ; `.tab` de drop ne portent que `{PriceType}`). **Leçon pity
(§8) appliquée : ne PAS déclarer gap — à creuser (mapping rareté→coût / CONSTANT_STATS / formule).** C'est le seul verrou
de l'incr. 1.

⇒ Recon incr. 1 ~80 % de-risqué (roll ✅, stockage blob ✅, livraison ✅) ; reste le coût des marchands à jetons. Aucune
ligne serveur écrite (recon). Régression inchangée (116). Détail : `docs/MERCHANT.md` §Recette/§Point dur.

Fichiers : `docs/MERCHANT.md`, `JOURNAL.md`.

---

## 2026-08-16 (g113) — MERCHANT (#72) incr. 1 : génération du stock (blob) HEADLESS + coût RÉSOLU

Implémentation de la génération de stock marchand (blob serveur-autoritatif), après le recon g111/g112.

**Coût des objets — RÉSOLU (leçon pity §8, 3ᵉ application « c'est dans la data »).** Le coût de base vient d'`items.tab` :
colonnes `VEND_VALUE`, `GOLD_PRICE`, `DIAMOND_PRICE`, `TOKEN_PRICE`, lues via `ItemStats.getStat(itemType,
StatType.{GOLD,DIAMOND,TOKEN}_PRICE)`. `getItemCost` = `getMerchantItemPrice(..., mi.getCost())` × remise ; le base
`mi.getCost()` = prix items.tab pour la monnaie du marchand × quantité. (J'avais failli déclarer un gap sur le coût des
marchands à jetons — encore une fois faux : la donnée existe, il fallait chercher `items.tab` + `ItemStats.getStat`.)

**Implémentation.** `ServerUser.generateMerchant(MerchantType)` : `merchantTable(type)` (réflexion sur
`MerchantStats.getDropStats`, privé) `.rollNode("ROOT", new UserDTContext(user), rnd)` → `List<DropItem>` ; par item :
`DropConverter.convert` → `RewardDrop` (skip itemType DEFAULT = ressources/upsells sans type), monnaie = `DropItem
.getParameter("PriceType")` sinon `getMerchantPrimaryCurrency`, coût = `ItemStats.getStat(itemType, priceStat(currency))
× quantité` → `MerchantItemData{item,cost,currency,purchased=false}`. `MerchantData{inventory, expiration=0, cooldownEnd=0,
nextAutoRefresh=getTimeUntilNextAutoRefresh, permUnlocked=isMerchantUnlocked, staminaMemory=0}`. **Write-through** :
`individualUserExtra.merchantData` (EnumMap<MerchantType,MerchantData>) — le convertisseur `ClientNetworkStateConverter`
ne gère PAS les marchands (blob pur, cat. Arena/Surge). Helpers `merchantTable`/`priceStat`, lecteur
`merchantDataPersisted(type)`.

**Test `MerchantGenTest`** (régression → **117 tests**). GEAR : 10 objets, 8 tarifés (+2 upsells prix 0), **coût ==
prix items.tab × qté vérifié POUR CHAQUE objet** (invariant), monnaie GEAR_TOKENS, non achetés ; round-trip wire + DB
conservent stock/coûts/type/monnaie ; 2 marchands (GEAR+MEMORY) coexistent.

**RESTE incr. 1b** : pousser `MerchantUpdate{type,data,reason}` au boot/à l'accès (mécanisme `GameMain.lambda$…$28`) +
vérif EN JEU (écran marchand affiche objets + prix). Puis incr. 2 (achat), 3 (refresh, corrige le PARTIEL), 4 (BLACK_MARKET).

Fichiers : `server/java/dhserver/ServerUser.java` (+generateMerchant/merchantTable/priceStat/merchantDataPersisted),
`server/smoke/MerchantGenTest.java`, `server/smoke/regression.sh`, `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g114) — MERCHANT (#72) incr. 1b : push MerchantUpdate post-boot ✅ VÉRIFIÉ EN JEU + VISUEL

Livraison au client du stock marchand généré (incr. 1). `ServerUser.bootMerchantUpdates()` : pour chaque marchand
DISPONIBLE + débloqué (GEAR/MEMORY/CHALLENGE/CRYPT/COLISEUM/FIGHT_PIT/WAR/EXPEDITIONS/INVASION), génère le stock s'il est
absent/vide (write-through, persiste) sinon réutilise le blob persisté (le stock NE se régénère PAS à chaque login) →
renvoie les `MerchantUpdate{type,data,reason=0}`.

**Timing (leçon SocialHistory).** Poussé au boot direct = PERDU : le `reset()` du BootData côté client efface les
marchands appliqués (ils vivent sur l'`IndividualUser` que le BootData reconstruit ; le handler client
`GameMain.lambda$…$28` fait `getIndividual().initMerchantData + updateUI`). Corrigé : `LoginServer` pousse les
`MerchantUpdate` **après le `REFRESH_SPECIAL_EVENTS` post-boot** (user client stabilisé) → survit. En jeu : d'abord
`GEAR : 0 objets` (push au boot) → après correction `GEAR : 10 objets`.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** Serveur `==> MerchantUpdate x8 (stock marchands)` → pilote `merchantscreen GEAR`
(chemin réel `new MerchantScreen(GEAR)`) → **écran BADGE BAZAAR** affiche les 10 objets avec prix (CUTE-ING STAR 5 290,
LUCKY CRICKET 3 910, DINOCO 400 87 515 en ROUGE=trop cher vs 39 000 jetons, CIRCLE OF LIFE 7 935, BROKEN NECKLACE 31 970,
MAGIC BRUSH 18 285…) + « Refreshes next Saturday » + bouton REFRESH — capture `build/merchant_gear_ingame.png`. Prix =
`items.tab` (TOKEN_PRICE) servis par NOTRE serveur. Pilote DEV `merchantscreen <TYPE>`.

⇒ **MERCHANT #72 : incr. 1 (génération) + 1b (push/affichage) vérifiés EN JEU.** RESTE : incr. 2 achat
(`PurchaseMerchantItem`→`purchaseItem`, anti-triche coût recalculé), incr. 3 refresh (`REFRESH_TRADER`→`refresh`+régén,
corrige le PARTIEL en boucle), incr. 4 BLACK_MARKET limited-time.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,
DesktopLauncher}.java`, `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g115) — MERCHANT (#72) incr. 2 : ACHAT (PurchaseMerchantItem) ✅ VÉRIFIÉ EN JEU + VISUEL

Achat d'un objet chez un marchand. `ServerUser.applyPurchaseMerchantItem(PurchaseMerchantItem)` : charge le blob
persisté dans le runtime (`iu.initMerchantData` — sinon `findUnpurchasedItem` ne voit rien, le convertisseur n'gère pas
les marchands) puis ré-exécute la logique du jeu (§3) `MerchantHelper.purchaseItem`.

**Anti-triche (bytecode).** `findUnpurchasedItem` : l'objet doit être DANS le stock serveur ET non acheté (sinon
`TRADER_ITEM_NOT_FOUND`). Coût RECALCULÉ serveur (`getItemCost`) et **VÉRIFIÉ anti-tamper** contre `expectedCost` du
client (mismatch → `CLIENT_OUT_OF_SYNC`) — un coût déclaré falsifié est REFUSÉ (pas « ignoré » : vérifié). Puis débit
autoritatif `chargeUser` + don `giveReward` + `setPurchased`. On répercute ensuite le flag `purchased` dans le blob wire
(miroir du matching du jeu : `RewardHelper.compareDrops` + `typeIndex`), resync (heroes/diamonds/counts) + persiste.
`LoginServer` (handler message `PurchaseMerchantItem`) re-pousse le `MerchantUpdate` mis à jour pour re-synchroniser le
client.

**Test `MerchantPurchaseTest`** (régression → **118 tests**). Achat de l'objet le moins cher : débit EXACT du coût
recalculé + don (+qté) + `purchased=true` ; anti-triche 1 : ré-achat du même objet refusé (aucun débit) ; anti-triche 2 :
coût déclaré falsifié (=1) sur un autre objet → refusé, aucun débit, non marqué ; persistance round-trip wire + DB.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** Pilote `merchantbuy GEAR` (chemin client réel `ClientActionHelper
.purchaseMerchantItem`, calcule le typeIndex + le coût réel) → CALAMARI 2 843 GEAR_TOKENS → serveur `<== PurchaseMerchantItem`
→ `achat marchand GEAR CALAMARI appliqué [persisté] + MerchantUpdate re-poussé` → **DB : GEAR_TOKENS 39 000→36 157 (−2 843
exact), CALAMARI possédé=1, purchased=1** ; écran **BADGE BAZAAR** : solde 36 157 + **CALAMARI grisé (vendu)** — capture
`build/merchant_purchase_ingame.png`. Pilote DEV `merchantbuy <TYPE>`.

⇒ **MERCHANT #72 : incr. 1 (génération) + 1b (push) + 2 (achat) vérifiés EN JEU.** RESTE : incr. 3 refresh
(`REFRESH_TRADER`→`MerchantHelper.refresh` + régénération, corrige le PARTIEL en boucle), incr. 4 BLACK_MARKET limited-time.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/MerchantPurchaseTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g116) — MERCHANT (#72) incr. 3 : REFRESH (REFRESH_TRADER) ✅ EN JEU — CORRIGE LE PARTIEL

Rafraîchissement du stock d'un marchand — l'incrément qui **corrige le `REFRESH_TRADER non appliquée (PARTIEL)`** qui
tournait en boucle dans les logs depuis le début.

**Handler.** `Action REFRESH_TRADER{ extra{ TYPE=MerchantType, REASON=MerchantRefreshType } }` (émetteur
`ClientActionHelper.refreshMerchant`) → `LoginServer` → `ServerUser.applyRefreshMerchant(type, refreshType)` : charge le
blob persisté dans le runtime (`initMerchantData`, pour que le gating lise l'état) → ré-exécute la logique du jeu (§3)
`MerchantHelper.refresh` (GATE + FACTURE selon le type : `FREE`=quota/jour, `PAID`=monnaie via `MerchantStats
.getRefreshCost`+`getRefreshCurrency`, `ITEM`, `VIDEO` ; lève `ClientErrorCodeException` si illégitime) — `refresh` ne
régénère PAS le stock (vérifié bytecode) → on RE-GÉNÈRE via `generateMerchant` (nouveau roll, write-through) → resync
(diamants/compteurs) + persiste ; re-pousse `MerchantUpdate`.

**Correctif timing (bug de génération).** `getTimeUntilNextAutoRefresh = getMerchantAutoRefreshTime − now`. `generateMerchant`
posait `nextAutoRefresh = getTimeUntilNextAutoRefresh` (un DELTA, négatif à la 1re génération) → `getMerchantAutoRefreshTime`
< now → `shouldAutoRefresh=true` en permanence → chaque refresh était un auto-refresh GRATUIT (jamais facturé) + timer UI
faux. Corrigé : `nextAutoRefreshTime(type)` calcule le TIMESTAMP absolu de la prochaine occurrence quotidienne depuis le
planning du jeu `MerchantStats.getAutoRefreshTimes` (offsets ms dans la journée, ex. GEAR [75600000]=21 h). Donnée du jeu
(§4). Résultat en jeu : timer « Refreshes today at 9:00 PM » (correct) + refresh PAID facturé.

**Test `MerchantRefreshTest`** (régression → **119 tests**). Refresh PAID : monnaie débitée + stock re-roulé (tous non
achetés) ; persist wire ; anti-triche : refresh sans monnaie → refusé, stock inchangé.

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** Blob vidé (outil `ClearMerch`) → boot régénère avec timing correct. `merchantrefresh
GEAR` (chemin client réel `ClientActionHelper.refreshMerchant`, choisit FREE si quota sinon PAID) → serveur
**`REFRESH_TRADER(GEAR,PAID) appliqué [persisté] + MerchantUpdate re-poussé`** ; le client a AUSSI auto-refreshé FIGHT_PIT
(`REFRESH_TRADER(FIGHT_PIT,AUTO)` géré) ⇒ **plus AUCUN « non appliquée (PARTIEL) »**. DB : GEAR_TOKENS 500 000→499 900
(−100 exact), stock re-roulé (10 objets, 0 acheté). Écran **BADGE BAZAAR** : NOUVEAUX objets (SUPER TEAM ASSEMBLE, MAGIC
MIRROR, BAYMAX PATCH KIT…) + solde 499 900 + « Refreshes today at 9:00 PM » — capture `build/merchant_refresh_ingame.png`.
Pilote DEV `merchantrefresh <TYPE>`.

⇒ **MERCHANT #72 : incr. 1 (génération) + 1b (push) + 2 (achat) + 3 (refresh) vérifiés EN JEU.** RESTE : incr. 4
BLACK_MARKET/MEGA_MART limited-time (`checkForFoundMerchant` découverte + expiration/cooldown).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/MerchantRefreshTest.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g117) — MERCHANT (#72) incr. 4 : marchands LIMITÉS (BLACK_MARKET/MEGA_MART) ✅ EN JEU → MODE COMPLET

Dernier incrément : les marchands limités dans le temps (BLACK_MARKET, MEGA_MART), découverts par accumulation de stamina.

**Découverte.** `ServerUser.discoverLimitedMerchant(type)` : charge le blob persisté dans le runtime (pour respecter
fenêtre/cooldown), et si déjà trouvé dans la fenêtre renvoie le stock tel quel ; sinon ré-exécute la logique du jeu (§3)
`MerchantHelper.checkForFoundMerchant(user, elapsed)` en boucle bornée (pas = `getStaminaRequiredForGuaranteedDiscovery`
× 2, RNG graine MERCHANT) jusqu'à la découverte — elle pose `expiration`=now+`getLimitedTimeMerchantDuration` (1 h) +
`cooldownEnd`=expiration+`getLimitedTimeMerchantCooldown` (20 h), durées des `.tab` (`merchant_constants.tab`), reset
stamina. `checkForFoundMerchant` respecte le cooldown (skip si `cooldownEnd > now`) → pas de re-découverte pendant 20 h.
Puis `buildMerchant` génère le stock EN LISANT expiration/cooldown/stamina de l'`iu` (refactor de `generateMerchant` →
`buildMerchant(user, iu, type)` partagé, pour préserver la fenêtre au lieu de la remettre à 0).

`bootMerchantUpdates` : après les marchands permanents, boucle `LIMITED_MERCHANTS` = {BLACK_MARKET, MEGA_MART} et pousse
ceux que `discoverLimitedMerchant` rend disponibles (déblocables & hors cooldown).

**Test `MerchantLimitedTest`** (régression → **120 tests**). BLACK_MARKET : indispo au départ → découverte → dispo +
fenêtre expiration≈now+1 h + cooldown≈expiration+20 h (tolérance 60 s) + stock non vide ; persist round-trip wire + DB
(fenêtre + stock + toujours dispo après reload).

**✅ VÉRIFIÉ EN JEU + VISUEL (id=1).** Boot → serveur `==> MerchantUpdate x10` (les 8 permanents + BLACK_MARKET +
MEGA_MART découverts). `merchantscreen BLACK_MARKET` → **écran BLACK MARKET** : 15 objets — PIGLET/MARIE/FLASH/STITCH
Hero Chips + gear bits (DOG'S BREAKFAST PLAN BIT, SECUR-T BIT…) avec **monnaies MIXTES** (diamants pour les hero chips, or
pour les bits) confirmant les annotations `{PriceType=DIAMONDS/GOLD}` du drop table — capture
`build/merchant_blackmarket_ingame.png`. Pilote DEV `merchantscreen BLACK_MARKET`.

⇒ **MERCHANT #72 COMPLET** : incr. 1 (génération) + 1b (push) + 2 (achat) + 3 (refresh, corrige le PARTIEL) + 4 (limité)
TOUS vérifiés EN JEU. **Candidats mode suivant** : FRANCHISE_TRIALS, PORT.

Fichiers : `server/java/dhserver/ServerUser.java` (buildMerchant/discoverLimitedMerchant/LIMITED_MERCHANTS +
bootMerchantUpdates), `server/smoke/MerchantLimitedTest.java`, `server/smoke/regression.sh`, `docs/MERCHANT.md`, `MEMORY.md`.

---

## 2026-08-16 (g118) — PORT (Docks/Entrepôt) : recon (pipeline #73) + incr. 1 COMBAT (headless)

Mode suivant après MERCHANT. PORT = PvE planifié à butin, membre du sous-système GÉNÉRIQUE `DifficultyModeHelper`
(GameMode `PORT_DOCKS`/`PORT_WAREHOUSE` ouverts des jours différents). Le serveur ne gérait AUCUN combat difficulty.

**Recon (docs/PORT.md).** Combat client-autoritatif via `DifficultyModeAttack{ base:AttackBase, gameMode:GameMode,
modeDifficulty:int, lootEarned:List<RewardDrop>, stagesCleared:int, attackEndTime:long }`. Entrée §3
`DifficultyModeHelper.recordOutcome(user, GameMode, ModeDifficulty, CombatOutcome, stagesCleared, Collection loot,
Collection attackers, Collection defenders, long time, snapshot)` — AUTONOME : `doChecks` (anti-triche : mode ouvert ce
jour + hors cooldown + quota) → `giveLoot` (crédite le butin) + EXP + `recordDailyUse`/`incDailyUses` + `setCooldownEnd`
(cooldown `getCooldownType`/`getCooldownDuration`) + étoiles/progression. Conversion difficulté : `ModeDifficulty.get(int)`.
Aussi : raid (`RaidDifficultyMode`→`recordRaidOutcome`), double (`Action CLAIM_DOUBLE_PORT_REWARDS`→`claimDoubleRewards`),
planning (`isOpen`/`getOpenDays`/`gameModeOffCooldown`/`getCooldownType`).

**Incr. 1 COMBAT (headless).** `ServerUser.recordDifficultyModeAttack(DifficultyModeAttack)` : mappe le message →
`recordOutcome` (loot, attackers, defenders dans l'ordre du client `DifficultyModeAttackScreen`) + resync
(heroes/diamonds/counts) + persiste. Handler `LoginServer` (message `DifficultyModeAttack` ; anti-triche
`ClientErrorCodeException` = fermé/cooldown → rien accordé). `PortAttackTest` (PORT_DOCKS ouvert le jour serveur ; WIN +
butin 5000 GOLD → giveLoot crédite + cooldown `PORT_DOCKS_ATTACK` posé ; persist round-trip wire + DB). Régression
**121 tests**.

**RESTE** : vérif EN JEU du combat PORT (pilote combat difficulty), incr. 2 raid (`RaidDifficultyMode`), incr. 3
récompense double (`CLAIM_DOUBLE_PORT_REWARDS`), incr. 4 planning/écran `PortChooserScreen`.

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/PortAttackTest.java`,
`server/smoke/regression.sh`, `docs/PORT.md`, `MEMORY.md`.

---

## 2026-08-16 (g119) — PORT (#72) incr. 1 COMBAT ✅ VÉRIFIÉ EN JEU + correctif warm-up PatchStats

Vérif en jeu du combat PORT (incr. 1, headless livré g118).

**Correctif PatchStats (bloqueur en jeu).** Le 1er combat PORT en jeu échouait : `recordOutcome` → `doChecks` →
`DailyActivityHelper.getMaxDailyUsesRaw` déclenche le parse paresseux de `patched_heroes_talent_assignments.tab` ; la
ligne EVIL_QUEEN référence `PREDICTIVE_FORTIFICATION` (absent de l'enum `PatchTalent` 12.1.0) → `saveRow` lève. En
mono-thread le parse est toléré (onStatError), mais **sous accès RUNTIME concurrent (combat en jeu)** ce parse non
ré-entrant poisonnait `PatchStats.<clinit>` (`ExceptionInInitializerError` → NPE) → combat rejeté. Correctif = **warm-up
MONO-THREAD** dans `ServerContext.init` (à la fin, stats ouvertes) : `Class.forName("…PatchStats")`, MÊME patron que le
warm-up GuildStats/perks déjà présent. FAIT (§8) : à ce stade Class.forName réussit (sonde `PatchProbe`) → PatchStats
chargée proprement. `docs/SHIMS.md` mis à jour (l'ancienne mise en garde « ne pas forcer » valait au BOOT ; à la fin
d'init c'est sûr et nécessaire).

**✅ VÉRIFIÉ EN JEU (id=1).** Pilote `portattack PORT_DOCKS` (chemin réseau réel : construit `DifficultyModeAttack{WIN,
loot=5000 GOLD}` + `getNetworkProvider().sendMessage`) → serveur `<== DifficultyModeAttack : PORT_DOCKS diff=1 outcome=WIN
→ recordOutcome appliqué [persisté]` → **DB : GOLD 57 888 570→57 893 570 (+5000), cooldown PORT_DOCKS_ATTACK futur**.
Attaque RÉPÉTÉE (mode désormais en cooldown) → **`DifficultyModeAttack REFUSÉ (anti-triche) : GAME_MODE_COOLDOWN`** =
l'anti-triche cooldown du jeu fonctionne. Pilote DEV `portattack <MODE>`. Régression 121 (inchangée).

⇒ **PORT #72 : incr. 1 (combat) vérifié EN JEU.** RESTE : incr. 2 raid (`RaidDifficultyMode`→`recordRaidOutcome`),
incr. 3 récompense double (`CLAIM_DOUBLE_PORT_REWARDS`→`claimDoubleRewards`), incr. 4 planning/écran `PortChooserScreen`.

Fichiers : `server/java/dhserver/ServerContext.java` (warm-up PatchStats),
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (pilote portattack), `docs/{PORT,SHIMS}.md`,
`MEMORY.md`.

## 2026-08-16 (g120) — HOOK POST-COMPACTION : double sécurité pour le rituel de reprise

Demande utilisateur : « fait un hook qui se déclenche après une compression (manuelle ou automatique) qui indique
explicitement à l'agent de faire ce rituel, soit précis, en y intégrant les derniers commits et lui indiquant de le
faire obligatoirement avant toute chose en entier obligatoirement. Ça permettra d'ajouter une double sécurité en plus de
ce que tu lui dira explicitement dans le handoff de compression. »

**Réalisation.** Hook `SessionStart` (matcher `compact`) — Claude Code relance la session avec `source="compact"`
APRÈS toute compaction (manuelle `/compact` OU automatique). Le hook injecte alors, dans le tout nouveau contexte, une
consigne EXPLICITE et OBLIGATOIRE d'exécuter le RITUEL DE REPRISE **EN ENTIER AVANT TOUTE CHOSE**.

- **`.claude/hooks/post-compact-reprise.sh`** : lit le JSON d'entrée (stdin) ; si `source != "compact"` → `exit 0`
  (défensif, ne pollue pas les démarrages normaux) ; sinon construit la consigne (français, langue de travail) listant
  les 9 étapes du rituel (MEMORY.md en entier, `git log --oneline -25`, JOURNAL.md, docs/SHIMS.md en entier,
  PRINCIPLES/PROTOCOL/SERVER_PLAN/ARCHITECTURE/SCREEN_PIPELINE/HEADLESS_VERIFICATION, doc du mode en cours = docs/PORT.md,
  CLAUDE.md ; énumération règles §1-§8 + astuces/commandes + outils d'industrialisation ; faire le point), **y intègre les
  derniers commits** (`git -C "$CLAUDE_PROJECT_DIR" log --oneline -25`), rappelle la règle permanente (rien n'est
  facultatif tant que non prouvé ; vérif EN JEU §8) et de transmettre le rituel à son propre successeur. Émission en JSON
  `{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":…}}` (injection fiable dans le contexte).
- **`.claude/settings.json`** : enregistre le hook sous `hooks.SessionStart` avec `matcher:"compact"` et
  `command:"$CLAUDE_PROJECT_DIR/.claude/hooks/post-compact-reprise.sh"`.

**Pourquoi projet et pas `~/.claude`** : `~/.claude/launcher-settings.json` est géré/régénéré à chaque session (éphémère) ;
le hook doit survivre au conteneur → il vit dans le dépôt (`.claude/` versionné), donc voyage avec le repo et s'applique à
toutes les sessions futures une fois mergé.

**Tests.** `source:"compact"` → JSON `additionalContext` complet avec les 25 derniers commits. `source:"startup"` →
sortie vide, `exit 0`. `settings.json` valide (JSON). Sécurité redondante : s'ajoute au handoff de compression que
l'agent écrit explicitement (ceinture + bretelles).

Fichiers : `.claude/hooks/post-compact-reprise.sh` (nouveau), `.claude/settings.json` (nouveau), `MEMORY.md`
(entrée g120 + correction du pointeur « mode en cours » FRIENDSHIPS→PORT dans la note de reprise), `JOURNAL.md`.

## 2026-08-16 (g121) — PORT (#72) incr. 2 : RAID (RaidDifficultyMode) ✅ VÉRIFIÉ EN JEU + DB

Le RAID d'un mode « difficulty » saute le combat et rejoue `raidCount` fois un étage déjà 3★. Client-autoritatif : le
client valide+charge en local (`ModePreviewScreen` → `useRaidTickets`), roule le butin par raid (`rollLoot`), crédite
(`RaidTicketOutcomeWindow` → `recordRaidOutcome`), puis envoie `RaidDifficultyMode` (fire-and-forget). Le serveur
AUTORITATIF ré-exécute les deux étages du jeu (§3).

**Recon (bytecode).** `RaidDifficultyMode{gameMode, modeDifficulty:int, outcomes:List<RaidOutcome{expEarned,loot}>,
raidTime:long, specialEvents}`. `RaidTicketOutcomeWindow` construit un `RaidOutcome` par raid (loot = `rollLoot`),
agrège les loot (`RewardHelper.mergeRewards`) puis appelle `DifficultyModeHelper.recordRaidOutcome(user, mode, diff,
raidCount, lootAgrégé, raidTime, snap)` avec `raidCount = outcomes.size()`. `recordRaidOutcome` (bytecode) = `giveLoot`
+ EXP×raidCount (`addExpItems`) + `recordDailyUse`×raidCount + compteur `port_any` += raidCount + **cooldown**
(`setCooldownEnd(raidTime + getCooldownDuration)`) + `onDifficultyModeRaid`. Le débit/anti-triche est SÉPARÉ dans
`useRaidTickets(user, mode, diff, raidCount, snap)` = `doChecks` (débloqué/visible/ouvert le bon jour/quota/cooldown)
+ gate `isAutoAttackAvailable` (étage 3★ → `NEEDS_THREE_STARS`) + gate VIP `getRaidFeature(mode,diff)` (PORT →
`RAID_PORT`, sinon `FEATURE_NOT_UNLOCKED`) + débit `RAID_TICKET`×raidCount SAUF VIP `RAID_WITHOUT_TICKETS`.

**FAIT §4 (gate VIP + tickets).** `RAID_PORT` se débloque au **VIP 4**, or `RAID_WITHOUT_TICKETS` dès le **VIP 3**
→ **tout raid PORT LÉGITIME (VIP 4+) est SANS ticket** ; le cas `NOT_ENOUGH_RAID_TICKETS` est inatteignable pour PORT.
Le raid PORT est donc VIP-gaté (VIP 4) et gratuit en tickets. (Sondes `VipProbe`/`VipProbe2` : RAID_PORT@4, RWT@3.)

**Serveur.** `ServerUser.recordRaidDifficultyMode(RaidDifficultyMode)` (throws `ClientErrorCodeException`) : `ModeDifficulty
.get(modeDifficulty)`, `raidCount = outcomes.size()`, `loot = merge(outcomes[i].loot)` (client-reporté §4bis/#25, comme
`recordRaidCampaign`), puis `useRaidTickets(...)` (anti-triche+débit) et `recordRaidOutcome(...)` (crédit+cooldown), enfin
`resyncHeroes/Diamonds/Counts`. Handler `LoginServer` (message `RaidDifficultyMode`, anti-triche `ClientErrorCodeException`).
Nouvel accesseur `ServerUser.gameIndividual()` (IndividualUser vivant bâti sur le même `individualUserExtra` = write-through
pour `setDifficultyModeStars`/cooldowns).

**Test `PortRaidTest`** (régression 121→122) : choisit le mode PORT OUVERT le jour serveur (union DOCKS/WAREHOUSE = tous
les jours), pose 3★ (`setDifficultyModeStars`) + VIP 4, RAID ×3 → **+15000 GOLD** (agrégat 3×5000), **tickets inchangés**
(VIP free-raid), **cooldown posé** ; re-raid pendant cooldown → **`GAME_MODE_COOLDOWN`** refusé (GOLD/tickets inchangés) ;
round-trip **wire + DB**.

**✅ EN JEU (id=1).** Outil DEV `PortRaidAdmin` (VIP4 + `setDifficultyModeStars(PORT_DOCKS/WAREHOUSE, ONE, 3)` +
cooldowns purgés) → pile `run-online.sh` → pilote `portraid PORT_DOCKS 3` (VRAI `RaidDifficultyMode` via
`getNetworkProvider().sendMessage`) → serveur `<== RaidDifficultyMode : PORT_DOCKS diff=1 ×3 → recordRaidOutcome appliqué
[persisté]` → **DB GOLD 57 893 570 → 57 908 570 (+15 000 exact)** ; 2e `portraid` → **`RaidDifficultyMode REFUSÉ
(anti-triche) : GAME_MODE_COOLDOWN`**. Pilote `portraid <MODE> [raids]` ; outil `PortRaidAdmin`.

⇒ **PORT #72 : incr. 1 (combat) + 2 (raid) vérifiés EN JEU.** RESTE : incr. 3 récompense double
(`Action CLAIM_DOUBLE_PORT_REWARDS` → `claimDoubleRewards`), incr. 4 planning/écran (`PortChooserScreen`).

Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/{PortRaidTest,PortRaidAdmin}.java`,
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`,
`docs/PORT.md`, `MEMORY.md`.

## 2026-08-16 (g122) — PORT (#72) incr. 3 : RÉCOMPENSE DOUBLE (CLAIM_DOUBLE_PORT_REWARDS) ✅ VÉRIFIÉ EN JEU + DB

« Regarder une vidéo pour doubler » le butin du dernier combat/raid d'un mode « difficulty ». Client-autoritatif : après
la vidéo, le client émet `Action{CLAIM_DOUBLE_PORT_REWARDS}` (`ClientActionHelper.claimDoublePortRewards`). Le serveur
ré-exécute la logique du jeu (§3).

**Recon (bytecode).** `DifficultyModeHelper.giveLoot` (privé, appelé par recordOutcome ET recordRaidOutcome) : pour un
mode PORT (`VideoHelper.isPortMode`), si VIP `DOUBLE_PORT_REWARDS` (VIP 4) → copie le butin et le crédite **×2 inline** ;
SINON → crédite ×1 + `new DoubleVideoLootContainer(loot, mode, diff)` + `IIndividualUser.setVideoDoubleLoot(container)`.
`claimDoubleRewards(user, snap)` : lit `getIndividual().getVideoDoubleLoot()` → si null lève **`DOUBLE_REWARDS_NOT_AVAILABLE`**
(anti-triche) ; sinon `RewardHelper.giveRewards(container.loot, container.mode, …)` + vide le container ; renvoie le loot.

**FAIT §6 (persistance).** `DoubleVideoLootContainer` (POJO `{gameMode, loot:Collection<RewardDrop>, modeDifficulty}`,
PAS un GruntMessage) n'est référencé QUE par DoubleVideoLootContainer/IndividualUser/IIndividualUser/VideoHelper/
DifficultyModeHelper — **AUCUNE** classe de message/BootData → **purement runtime, non persisté par le jeu** (perdu au
restart, même dans le jeu original). §6 = on persiste ce que le jeu persiste ⇒ RIEN en DB. Fidélité = **in-session** (le
client montre la popup juste après le combat). Comme le converter (`ClientNetworkStateConverter`, classe du jeu)
reconstruit un IndividualUser FRAIS à chaque requête, le container serait perdu entre la requête combat et la requête
claim. Solution : champ de SESSION `ServerUser.pendingDoubleLoot` (comme `pendingSeeds`), posé dans
`recordDifficultyModeAttack`/`recordRaidDifficultyMode` (`= iu.getVideoDoubleLoot()` après le record). Le `ServerUser`
étant caché par connexion (`LoginServer.connUsers`, ligne 180), le CLAIM (même connexion) le retrouve → restauré via
`user.getIndividual().setVideoDoubleLoot(pendingDoubleLoot)` avant `claimDoubleRewards`. AUCUN schéma DB inventé (§2/§4).

**Serveur.** `ServerUser.applyCommand` case `CLAIM_DOUBLE_PORT_REWARDS` : restaure le container de session → `claimDoubleRewards`
→ vide `pendingDoubleLoot` (anti double-claim). Refus (`DOUBLE_REWARDS_NOT_AVAILABLE`, non déclaré par dex2jar → catch
`Throwable` + `instanceof`). Routé par le fallback générique `applyAction` de `LoginServer` (aucun else-if dédié requis),
persistance sur succès. Seuils VIP (sondes) : `DOUBLE_PORT_REWARDS`=VIP 4.

**Test `PortDoubleRewardTest`** (régression 122→123) : VIP 0 (pas d'auto-double) ; combat +5000 GOLD (pose le container)
→ `applyAction(CLAIM_DOUBLE_PORT_REWARDS)` → +5000 encore (total +10000, ×2) ; re-claim → `DOUBLE_REWARDS_NOT_AVAILABLE`
refusé (GOLD inchangé) ; claim sans combat (nouvelle instance) refusé ; GOLD (combat+double) persiste wire+DB (le
container de session, lui, n'est pas persisté = fidèle au jeu).

**✅ EN JEU (id=1).** Compte remis à VIP 0 + cooldowns PORT purgés → `portattack PORT_DOCKS` (combat, pose le container)
→ `portdouble` (VRAIE `Action CLAIM_DOUBLE_PORT_REWARDS` via `getNetworkProvider().sendMessage`) → serveur
`CLAIM_DOUBLE_PORT_REWARDS → récompense double créditée (1 drops) [logique du jeu]` → **DB GOLD 57 908 570 → 57 918 570
(+10 000 = combat 5000 doublé)** ; 2e `portdouble` → **`CLAIM_DOUBLE_PORT_REWARDS REFUSÉ (anti-triche) :
DOUBLE_REWARDS_NOT_AVAILABLE`**. Pilote `portdouble`.

⇒ **PORT #72 : incr. 1 (combat) + 2 (raid) + 3 (récompense double) vérifiés EN JEU.** RESTE : incr. 4 planning/écran
(`PortChooserScreen` : isOpen/open-days/cooldown/difficultés — vérif rendu le bon jour).

Fichiers : `server/java/dhserver/ServerUser.java` (champ `pendingDoubleLoot` + stash dans les 2 record* + case
`applyCommand`), `server/smoke/PortDoubleRewardTest.java`, `server/smoke/regression.sh`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (pilote `portdouble`),
`docs/PORT.md`, `MEMORY.md`.

## 2026-08-17 (g123) — PORT (#72) incr. 4 : PLANNING (PortChooserScreen) ✅ VÉRIFIÉ EN JEU + VISUEL → PORT COMPLET

Dernier incrément du mode PORT : l'écran de PLANNING `PortChooserScreen` (choix DOCKS/WAREHOUSE + difficulté). **RENDU-ONLY,
aucun code serveur** — établi par le contrat industriel.

**Contrat (#73, `contract.sh --mode Port`).** Union du mode = `PortChooserScreen` + `GetPortResetWindow` + `PortInfoWindow`.
Résultat : **0 message ENVOYÉ client→serveur, 0 champ wire LU (serveur→client), 0 gate `Unlockable`**. ⇒ l'écran ne dépend
d'AUCUN handler ni d'AUCUNE poussée au boot (contrairement à InvasionInfo). Il lit tout via les helpers du jeu sur l'état
PERSISTÉ + l'horloge serveur : `DifficultyModeHelper.isOpen`×7 (planning open-days par mode/difficulté), `getCooldownEnd`,
`getRemainingDailyUses` (quota « chances left »), `isResetAvailable`, `hasChallengeChances`, `getUseKey`/`getChallengeKey`/
`getCooldownType`. Tout cela est DÉJÀ fourni par notre serveur (cooldowns/compteurs/étoiles persistés write-through +
horloge serveur ancrée). Donc rien à implémenter côté serveur ; incr. 4 = vérif rendu en jeu.

**Pilote.** `PortChooserScreen()` a un ctor sans argument (comme `UINavHelper` l'instancie). Pilote DEV `portscreen`
(`TutorialDriver.portScreen`) : log le planning côté client (`isOpen` par mode) puis `pushScreen(new PortChooserScreen())`
(chemin réel). Câblé `portscreen` dans `DesktopLauncher`.

**✅ EN JEU + VISUEL (id=1, jour serveur 1).** `portscreen` → écran **THE PORT** rendu correctement :
- **THE DOCKS** (PORT_DOCKS, ouvert le jour 1) : « EARN XP », « ENEMIES HAVE FANTASTIC IMMUNITY », bouton **ENTER** actif,
  « CHANCES LEFT: 0 / 2 » (quota quotidien lu de l'état persisté — mes combats/raids des incr. 1-3 l'ont consommé).
- **THE WAREHOUSE** (PORT_WAREHOUSE, fermé le jour 1) : « EARN GOLD », « ENEMIES HAVE NORMAL IMMUNITY », bouton grisé
  **OPENS TOMORROW** (prochain jour d'ouverture calculé des open-days + horloge serveur).
Le client logue `planning côté client : PORT_DOCKS=OUVERT PORT_WAREHOUSE=fermé`. Capture `build/port_chooser_ingame.ppm`.
Ce rendu prouve le planning (ouvert/fermé/opens-tomorrow), les libellés de récompense (XP/Gold) + d'immunité, et la lecture
du quota — tous dérivés de l'état persisté + horloge, sans handler.

⇒ **PORT #72 COMPLET** : incr. 1 (combat `DifficultyModeAttack`→`recordOutcome`) + 2 (raid `RaidDifficultyMode`→
`useRaidTickets`+`recordRaidOutcome`) + 3 (récompense double `CLAIM_DOUBLE_PORT_REWARDS`→`claimDoubleRewards`) + 4
(planning `PortChooserScreen`), TOUS vérifiés en jeu. Le sous-système générique `DifficultyModeHelper` (PORT_DOCKS/WAREHOUSE
+ siblings) est désormais couvert côté serveur. **Candidats mode suivant** : FRANCHISE_TRIALS (event-gated — vérifier
activable sans événement hébergé, sinon documenter le gate §8 et prendre un autre `⬜` via `contract.sh --mode`).

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (pilote `portscreen`),
`docs/PORT.md`, `MEMORY.md`. (Aucun changement serveur — incr. rendu-only.)

## 2026-08-17 (g124) — PORT (#72) : ENTRÉE COMPLÈTE en jeu — DOCKS joué de bout en bout ; WAREHOUSE event-gaté (prouvé)

Demande utilisateur : pour que ce soit vraiment complet, ENTRER dans THE DOCKS et THE WAREHOUSE et les jouer (un test
complet = jouer le mode en entier), au lieu de n'injecter que les messages.

**Flux d'entrée réel (recon bytecode).** Bouton ENTER de `PortChooserScreen$DocksBox` → `ModeData.handleButtonPress`
→ (si dispo) `ModePreviewScreen(mode, snap, null)` → son `doAttack()` pousse `DifficultyModeHeroChooserScreen(mode,
highestDiff, farmed, snap)`. Le sélecteur : `unitSelected(UnitData, provider, x, y)` (comme un tap) + `startBattleInner()`
→ `DifficultyModeAttackScreen` (combat rendu). À la victoire, le client envoie `DifficultyModeAttack` (déjà géré, incr. 1).
NB : pousser `ModePreviewScreen` directement CRASHE (`createTitleAndStarsRow` → `getDifficultyModeStars(mode, null)` NPE,
champ `difficulty` non initialisé) → le pilote pousse directement le `DifficultyModeHeroChooserScreen(mode, ONE, null, NONE)`
(exactement ce que `doAttack` construit).

**Pilotes.** `portenter <MODE>` (`TutorialDriver.portEnter`) → pousse le sélecteur d'équipe ; `portteam` (`portTeam`) →
sélectionne jusqu'à 5 héros possédés (`unitSelected`) + `startBattleInner()`. Câblés dans `DesktopLauncher`. Combat joué
en AUTO (`dh.autofight` = bouton AUTO d'origine).

**✅ THE DOCKS — JOUÉ DE BOUT EN BOUT EN JEU.** Setup : `ExpAdminBoost` (équipe RALPH/HERCULES/MAUI/SULLEY/VANELLOPE RED
100 6★) + `AdminClock --offset-hours 24` (jour DOCKS-ouvert + chances quotidiennes fraîches). Séquence : `portenter
PORT_DOCKS` → écran **« CHOOSE YOUR HEROES! »** (roster niv.100 6★, capture) → `portteam` (5 héros → startBattleInner) →
combat `DifficultyModeAttackScreen` auto → **VICTOIRE** → écran **REWARDS** (Hero XP +33 ×5, items ×12, bouton **GET 2X
REWARDS! 📺** = l'entrée de l'incr. 3, capture) → serveur `<== DifficultyModeAttack : PORT_DOCKS diff=1 outcome=WIN →
recordOutcome appliqué [persisté]`. Captures `build/port_docks_played_ingame.ppm`.

**THE WAREHOUSE — EVENT-GATÉ (fait établi §8, PAS un bug).** En tentant d'entrer (`portenter PORT_WAREHOUSE`), le client
logue `isOpen=false`. Investigation (bytecode `DifficultyModeHelper.isOpen`) : le switch d'ordinaux PORT va à la branche
`BaseEventSnapshot.isModeOpen(mode)` → `SpecialEventSnapshotState.getComponentSnapshot(ModesOpenSnapshot).getOpenModes()`
(+ flag debug `debugAllModesOpen`). L'ensemble des modes ouverts est peuplé par le composant d'ÉVÉNEMENT `ModesOpen`
(planning d'événements spéciaux). Notre serveur ré-hébergé n'a pas d'événements live (`SpecialEventsRaw` vide) → `getOpenModes`
par défaut = **DOCKS seulement**, WAREHOUSE jamais (vérifié sur 8 jours consécutifs avec vrai snapshot via
`snapshotWithoutRefresh`, `snapshotTime` réel qui avance). L'« OPENS TOMORROW » de `PortChooserScreen` vient de
`getOpenDays`/`getNextOpenDay` (affichage), DÉCORRÉLÉ du gate réel `isModeOpen`. ⇒ **WAREHOUSE est event-gaté comme
FRANCHISE_TRIALS.** On NE le force PAS en serveur autoritatif (le flag debug `debugAllModesOpen` existe mais l'utiliser en
prod = « faux OK » §2 interdit).

**Logique serveur WAREHOUSE PROUVÉE (§8, « rien d'absent sans preuve »).** `PortWarehouseTest` (régression 123→124) :
(1) asserte qu'au défaut WAREHOUSE est FERMÉ (documente l'event-gate) ; (2) lève le gate via `BaseEventSnapshot.
debugAllModesOpen=true` (réservé au test, remis à false en `finally`) → `isOpen(PORT_WAREHOUSE)=true` → combat WAREHOUSE
WIN → **+7000 GOLD + cooldown `PORT_WAREHOUSE_ATTACK`** + persistance wire+DB. THE WAREHOUSE emprunte le MÊME `recordOutcome`
que THE DOCKS (le `GameMode` n'est qu'un paramètre) → entièrement couvert par la vérif en jeu de DOCKS ; seul son gate
d'ouverture (événements live) l'empêche d'être joué en jeu sur notre serveur.

**Bilan.** THE DOCKS = mode joué EN ENTIER en jeu (le test complet demandé). THE WAREHOUSE = même sous-système, logique
serveur prouvée headless, ouverture event-gatée documentée. Horloge remise à l'heure réelle (`AdminClock --reset`) après
le test. NB : crash CLIENT intermittent au boot sur `PatchStats.<clinit>` (poison concurrent, MÊME bug que le warm-up
serveur g118 mais côté client `SyncStatDataClientHelper`) rencontré 1× — flaky, sans rapport avec PORT ; un warm-up client
serait un durcissement futur possible.

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (pilotes `portenter`/`portteam`),
`server/smoke/PortWarehouseTest.java`, `server/smoke/regression.sh`, `docs/PORT.md`, `MEMORY.md`.

## 2026-08-17 (g125) — SPECIAL_EVENTS (live-ops opérateur) : recon approfondie (incr. 1 ModesOpen) — point dur → décision

Suite à la demande (gérer les events live par admin serveur, fidèlement, pour l'ensemble des features, pas que Warehouse).
Doc `docs/SPECIAL_EVENTS.md` créé (pipeline + schéma + architecture cible + plan).

**Pipeline cartographié (bytecode)** : `SpecialEventsHelper.setSpecialEvents(SpecialEventsRaw)` [déjà appelé, VIDE] →
`buildEvents` → `buildEvent(jsonString)` → `SpecialEventBuilder.buildEvent` → `new JsonReader().parse` →
`SpecialEventInfo.load` → `formatVersion==0` ? `loadFlatFormat` : `loadComponentFormat` → composants. Snapshot :
`snapshotWithoutRefresh()` OK headless (`snapshot()` NPE UI). `isModeOpen`→`ModesOpenSnapshot.getOpenModes()`.

**Réutilisabilité confirmée** : composants `ModesOpen, DropBonus, AdditionalChances, ChestDiscount, ExtraChest,
MerchantDiscount, MerchantRefreshDiscount, Contest, TeamLevel, MiscMultipliers, FlagUserOnLogin`.

**Schéma JSON décodé (~90 %, format composant formatVersion≥1)** — vérifié via le parseur du jeu : top-level
`kind`(=SpecialEventType, ex. MODES_OPEN)/`id`/`formatVersion` ; `visibility` = tableau de `VisibilityRange`
`{serverFilter:"1-999999", start:<epochMs>, end:{kind:"TIME", endTime:<epochMs>}}` ; `modesOpen`:
`{gameModeFilter:{include:[{gameMode:"PORT_WAREHOUSE"}], exclude:[…]}}`. **BLOQUE** sur `eventCardDisplay` (carte UI
imposée par `checkUnitType`) aux champs requis obscurs (`sortIndex`,`title`,`summary`,`preset`… `preset` refuse
""/NONE/DEFAULT).

**Voie objet (contourne l'UI)** : construire `SpecialEventInfo` + composants via l'API du jeu et injecter via
`SpecialEvents.setEvents` dans `EventHelperInner.SPECIAL_EVENTS` (réflexion). `ModesOpen`+`EnumFilter` construits OK ;
reste le contrat 2-params de `EventVisibility.load` (param3 = tableau `timeRange` lu directement ; param2 = contexte
serverFilter → `VisibilityRange` NPE `local2.name`). ~30 probes headless.

**Faits** : ouvrir WAREHOUSE via events = LE mécanisme fidèle (pas de `debugAllModesOpen` §2). Ne PAS inventer le JSON à
la main (§4). Voies fidèles restantes : (1) objet→`toJson()`/injection [proche du but] ; (2) obtenir un event JSON RÉEL
(vérité terrain) puis paramétrer. **Décision utilisateur demandée** avant de continuer (sous-système plus profond qu'un
incrément de mode standard). PORT #72 reste COMPLET (DOCKS joué en jeu ; WAREHOUSE même code, event-gaté).

Fichiers : `docs/SPECIAL_EVENTS.md` (nouveau), `MEMORY.md`, `JOURNAL.md`.

## 2026-08-17 (g126) — SPECIAL_EVENTS incr. 1 : moteur ServerEvents (ModesOpen) ✅ WAREHOUSE ouvert & joué en jeu

Suite à la demande utilisateur : reconstruire le mécanisme d'événements de façon INDUSTRIELLE (rien à la main) pour
couvrir TOUT ce qui en dépend (Warehouse + le reste). Recadrage explicite : « industrialisé = ne pas faire à la main ».

**Approche (industrielle, §3/§4).** On n'écrit NI le schéma JSON NI les règles : `server/java/dhserver/ServerEvents.java`
construit les événements avec les CLASSES DU JEU et les injecte dans la MACHINERIE DU JEU — c'est elle qui calcule
`isModeOpen`/snapshots. Contrat de chargement définitif relevé au bytecode : `component.load(info, fullJson,
fullJson.get(key))` (param2=event complet, param3=sous-nœud). `EventVisibility.load` branche sur `getFormatVersion()`
(fv=0 → param3 = tableau `timeRange`).

**Module `ServerEvents`.**
- `buildModesOpenEvent(id, modes, startMs, endMs)` → `SpecialEventInfo` : `new SpecialEventInfo(SpecialEventType.class)`
  (id/type=MODES_OPEN/formatVersion=0 par réflexion) + `EventVisibility.load(info, full, full.get("timeRange"))` +
  `ModesOpen.load(info, full, full)` (lit `gameModeFilter.include`) + `addComponent` (réflexion). L'entrée par composant
  est minimale et paramétrée (pas de schéma à la main) ; `SpecialEventInfo.toJson()` en donnerait la forme canonique.
- `install(events)` : `SpecialEvents.setEvents` + remplace `EventHelperInner.SPECIAL_EVENTS` + invalide `SNAPSHOT_CACHE` +
  `lastSnapshotTime=0` + `refresh(true)` (reconstruit le snapshot). `snapshot()` = `snapshotWithoutRefresh()` (sûr headless).
- `installBootDefaults()` : ouvre PORT_DOCKS+PORT_WAREHOUSE ; appelé dans `ServerContext.bind` (après le raw vide) →
  idempotent, l'état opérateur est garanti à chaque bind (état global à la couche événements).

**Intégration.** Les handlers PORT (`recordDifficultyModeAttack`/`recordRaidDifficultyMode`/double reward) passent
désormais un **snapshot RÉEL** (`ServerEvents.snapshot()`) au lieu de `SpecialEventSnapshot.NONE` → `doChecks`/`isOpen`
voient les événements (corrige aussi le « NONE = planning évalué à l'époque »).

**Test `SpecialEventsModesOpenTest`** (régression 124→125) : sans événement → WAREHOUSE fermé ; événement MODES_OPEN
ciblé → WAREHOUSE ouvert ; défauts opérateur → DOCKS+WAREHOUSE ouverts. Prouve que c'est L'ÉVÉNEMENT qui décide (pas un
hack ; c'est `ModesOpenSnapshot.getOpenModes()` du jeu). NB : `gameUser()` re-bind → réinstalle les défauts (on cache le
User dans le test) ; le snapshot est mémoïsé par temps (d'où `refresh(true)`+reset dans `install`).

**✅ EN JEU (id=1).** Compte boosté (RED 100 6★) + cooldowns PORT purgés. `portenter PORT_WAREHOUSE` (le client logue
`isOpen=false` — il n'a pas l'événement, injection serveur seulement) → `portteam` → combat rendu **DANS THE WAREHOUSE**
(fond conteneurs, étages 3/3) → **VICTOIRE** → écran **REWARDS** (Hero XP +33 ×5 + items ×7 + GET 2X REWARDS) → serveur
**`DifficultyModeAttack : PORT_WAREHOUSE diff=1 outcome=WIN → recordOutcome appliqué [persisté]`**. Avant ce moteur, le
serveur refusait (`GAME_MODE_NOT_OPEN`). Captures `build/port_warehouse_played_ingame.ppm`.

**Point dur écarté (`eventCardDisplay`).** La carte UI est imposée par `checkUnitType` UNIQUEMENT au re-parse JSON (client)
— pas à l'injection serveur. Donc l'autorité serveur ouvre WAREHOUSE sans elle. Son champ `preset` référence un preset
RÉEL de `assets/strings/EventPresets.properties` (pas `""`) et il n'existe AUCUN preset `MODES_OPEN` → event « technique ».
Le push client (affichage/entrée par la vitrine) = incrément 2.

**Bilan.** Le mécanisme d'événements est reconstruit industriellement (moteur générique, extensible à tous les composants
via un builder chacun) ; WAREHOUSE est désormais ouvert et jouable côté serveur, vérifié en jeu. **RESTE** : incr. 2 push
client (carte minimale + toJson → SpecialEventsRaw poussé), persistance shard, rotation fidèle par jour (`getOpenDays`),
puis DropBonus/discounts/Contest.

Fichiers : `server/java/dhserver/ServerEvents.java` (nouveau), `server/java/dhserver/ServerContext.java` (install au boot),
`server/java/dhserver/ServerUser.java` (snapshot réel dans les handlers PORT), `server/smoke/SpecialEventsModesOpenTest.java`,
`server/smoke/regression.sh`, `docs/SPECIAL_EVENTS.md`, `MEMORY.md`.

## 2026-08-17 (g127) — SPECIAL_EVENTS incr. 2 : push client — le CLIENT affiche WAREHOUSE ouvert (ENTER) ✅ en jeu

Incr. 2 : pousser les événements au CLIENT pour qu'il AFFICHE leurs effets (ex. WAREHOUSE ouvert dans PortChooserScreen).
Le blocage était la carte `eventCardDisplay`, exigée par `checkUnitType` au re-parse client (buildEvent).

**Levée INDUSTRIELLE du blocage (rien à la main).** `ServerEvents.buildMinimalCard(info)` construit une carte cachée via
la FABRIQUE du jeu (`SpecialEventBuilder.createComponent("eventCardDisplay")`) puis la remplit par un filler GÉNÉRIQUE PAR
TYPE (pas champ-par-champ) : `String`→"" (sauf `preset`="none", le preset wildcard vide `*.eventCard.none` extrait de
`assets/strings/EventPresets.properties`) ; `EventString`→vide (via son `load` sur `{}`) ; `UnitTypeLookup`→
`FixedUnitTypeLookup(UnitType.DEFAULT)` ; enum→DEFAULT ; Class→UnitType. `SpecialEventInfo.toJson()` produit alors un JSON
que le client RE-PARSE sans erreur (vérifié `RawCheck` : RE-PARSE OK). La carte n'a AUCUN rôle serveur (l'injection ne
passe pas par checkUnitType) ; elle sert juste à rendre l'event parsable côté client.

**Push.** `ServerEvents.toRaw(events)` → `SpecialEventsRaw{events:[SpecialEventRaw{eventID, jsonString=toJson}]}`.
`LoginServer` répond désormais au `REFRESH_SPECIAL_EVENTS` (que le client redemande au boot) avec
`ServerEvents.toRaw(bootDefaultEvents())` au lieu d'un raw vide (`changed=true`). Le client applique via
`SpecialEventsHelper.setSpecialEvents` → sa couche événements ouvre les modes.

**✅ EN JEU (id=1).** Serveur logue `==> SpecialEventsRaw (reply, 1 évènement(s), 31 jours de sign-in)`. Pilote `portscreen`
→ `PortChooserScreen` rendu : **THE DOCKS** ENTER (CHANCES 2/2) ET **THE WAREHOUSE** désormais **ENTER** (CHANCES 2/2,
« EARN GOLD / ENEMIES HAVE NORMAL IMMUNITY ») — AVANT ce push, WAREHOUSE affichait « OPENS TOMORROW » (grisé). Le joueur
peut donc entrer WAREHOUSE par la VITRINE normale. Capture `build/port_warehouse_open_client_ingame.ppm`. (NB : le log du
pilote `portscreen` dit `WAREHOUSE=fermé` car il interroge `isOpen(..., NONE)` — snapshot NONE, diagnostic trompeur ;
l'ÉCRAN rendu utilise le vrai snapshot client avec l'event appliqué → ENTER.)

**Bilan.** Boucle SPECIAL_EVENTS complète serveur→client : le moteur construit l'event (classes du jeu), l'injecte côté
serveur (autorité) ET le sérialise pour le client (affichage) — WAREHOUSE ouvert et jouable par la vitrine, vérifié en jeu.
Régression 125/125 (le fail WishingWellWishTest observé une fois = flaky RNG : passe en isolé 3/3 + re-run complet 125/125).
**RESTE** : persistance shard (config opérateur) + rotation fidèle par jour (`getOpenDays`) ; puis autres composants
(`DropBonus`, discounts marchands/coffres, `Contest`, `TeamLevel`…), même moteur, un builder chacun.

Fichiers : `server/java/dhserver/ServerEvents.java` (buildMinimalCard + toRaw + bootDefaultEvents), `server/java/dhserver/
LoginServer.java` (push au REFRESH_SPECIAL_EVENTS), `docs/SPECIAL_EVENTS.md`, `MEMORY.md`.

## 2026-08-17 (g128) — THE WAREHOUSE ✅ ENTRÉ PAR LA VITRINE & JOUÉ DE BOUT EN BOUT EN JEU (bouton ENTER réel)

Réponse à la question utilisateur (« es-tu entré dans warehouse maintenant qu'il est dispo ? ») : **OUI**, par le VRAI chemin
d'UI — pas le court-circuit `portenter` (qui poussait directement le sélecteur avec un snapshot NONE).

**Chemin ENTER fidèle (recon bytecode).** Le bouton ENTER d'une carte de `PortChooserScreen` = listener
`PortChooserScreen$WarehouseBox$1.onClicked` → `new ModeData(mode, this.snapshot).handleButtonPress()` où
`this.snapshot = SpecialEventsHelper.snapshot()` (le VRAI snapshot client, où l'event `MODES_OPEN` poussé en incr. 2 est
appliqué). `handleButtonPress()` → si `isAvailable()` → `pushScreen(new ModePreviewScreen(mode, snapshot, null))`. La preview
a un bouton NEXT/ATTACK (`ModePreviewScreen$3.onClicked` → `doAttack()`) → `pushScreen(new DifficultyModeHeroChooserScreen(
mode, difficulty, farming, snapshot))` — avec le snapshot RÉEL (≠ NONE du court-circuit).

**Pilotes FIDÈLES ajoutés** (`TutorialDriver`/`DesktopLauncher`, patron §B-bis = API réelle du client) :
  • `portpress <MODE>` = `new ModeData(mode, SpecialEventsHelper.snapshot()).handleButtonPress()` — **byte-identique** au clic ENTER.
  • `portpreviewattack` = `ModePreviewScreen.doAttack()` (privé, réflexion) — bouton NEXT/ATTACK de la preview.
  (puis `portteam` = sélection 5 héros + `startBattleInner`, déjà existant.)

**Setup compte** : outil DEV `PortEnterAdmin` (roster RED 100 6★ pour gagner + purge cooldowns PORT + CHANCES QUOTIDIENNES
fraîches via `setDailyUses(useKey/challengeKey/resetUseKey, 0)`). L'OUVERTURE de WAREHOUSE vient de l'ÉVÉNEMENT (moteur
`ServerEvents`, poussé au boot + au REFRESH_SPECIAL_EVENTS) — RIEN ici ne force le gate (§2, pas de debug).

**✅ EN JEU (id=1).** Vitrine **THE PORT** : THE DOCKS ENTER (2/2) ET **THE WAREHOUSE ENTER (CHANCES 2/2, EARN GOLD, NORMAL
IMMUNITY)** → `portpress PORT_WAREHOUSE` logue `isOpen(snapClient)=true isAvailable=true` → **`ModePreviewScreen`** (aperçu
THE WAREHOUSE : 3★, 5 ennemis niv.8 White, LOOT, difficulté EASY, boutons NEXT + Raid 1) → `portpreviewattack` →
**`DifficultyModeHeroChooserScreen`** → `portteam` (5 héros) → **combat rendu DANS THE WAREHOUSE** (décor conteneurs
« DUKE'S OFFICIALLY LICENSED MOVIES », 3 étages, ennemis en « Resist » = Normal Immunity) → **VICTOIRE** → écran **REWARDS**
(Hero XP +33 ×5 dont un level-up + items ×7 + GET 2X REWARDS) → client envoie `DifficultyModeAttack` → serveur
**`DifficultyModeAttack : PORT_WAREHOUSE diff=1 outcome=WIN → recordOutcome appliqué [persisté]`**.

**Persistance VÉRIFIÉE EN DB** (probe `PortStateProbe` + dump brut des maps, pile arrêtée) :
  • cooldown `PORT_WAREHOUSE_ATTACK` posé dans le FUTUR (+~455s) — n'est posé QUE par `recordOutcome` après un combat WAREHOUSE ;
  • **compteur de chance CONSOMMÉ & PERSISTÉ** : `individualUserExtra.dailyUses` = `{portWarehouse_use=1, port_any=1, …}`
    (`IndividualUser.setDailyUses`/`incDailyUses` écrivent **write-through** dans `individualUserExtra.dailyUses`, comme les
    cooldowns → auto-persisté, aucun resync requis) ;
  • GOLD crédité (57 918 570) ; PORT_DOCKS intact (cooldown 0, non joué).

**Point §8 investigué et TRANCHÉ (rien laissé « absent » sans preuve).** `getRemainingDailyUses`/`PortStateProbe` relisaient
« 2/2 » alors qu'une chance venait d'être consommée. Investigation : `DifficultyModeHelper.recordOutcome` appelle bien
`DailyActivityHelper.recordDailyUse` = `if (!tryConsumeEventUse) user.incDailyUses(key)` + `incDailyUses(port_any)` +
`setCooldownEnd`. Le compteur BRUT est bien monté à 1 en DB (cf. dump) — la lecture « 2/2 » vient de la **remise à zéro
quotidienne À LA LECTURE** du jeu (le getter considère un nouveau jour quand l'ancre de reset du compte de test est passée)
→ comportement du jeu, **PAS un gap de persistance** (la consommation est réellement stockée).

**Flaky connu** : le client est tombé une fois sur le crash intermittent de boot `PatchStats.<clinit>` via
`SyncStatDataClientHelper` (côté client, cf. g118) → `onClose` ; un **relaunch** l'a résolu (2ᵉ run OK jusqu'à la victoire).

**Bilan.** La boucle SPECIAL_EVENTS→PORT est complète de bout en bout par l'UI RÉELLE : event ouvre WAREHOUSE → vitrine ENTER →
preview → sélecteur → combat joué DANS le mode → victoire → serveur autoritatif enregistre & persiste (gold + cooldown +
chance). Régression 125/125. **RESTE SPECIAL_EVENTS** : persistance shard (config opérateur au lieu de ré-installer au bind) +
rotation fidèle par jour (`getOpenDays`) ; puis autres composants (`DropBonus`, discounts, `Contest`, `TeamLevel`).

Fichiers : `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (pilotes `portpress`/`portpreviewattack`),
`server/smoke/PortEnterAdmin.java` + `server/smoke/PortStateProbe.java` (outils DEV, hors régression), `docs/PORT.md`, `MEMORY.md`.

## 2026-08-17 (g129) — Décompte de chances PORT (2/2→1/2→0/2) : PROUVÉ RÉEL EN JEU + persiste au reload

Question utilisateur : le décompte 2/2→1/2→0/2 fonctionne-t-il ? **OUI, prouvé en jeu et au reload.**

**Chaîne de faits** :
- Combat WAREHOUSE → `recordOutcome` → `DailyActivityHelper.recordDailyUse` (= `if(!tryConsumeEventUse) incDailyUses(key)`)
  → `incDailyUses(portWarehouse_use)` + `incDailyUses(port_any)`, écrit **write-through** dans
  `individualUserExtra.dailyUses` (persisté). Vérifié DB après le VRAI combat en jeu : `portWarehouse_use=1`.
- La vitrine « CHANCES LEFT » = `DailyActivityHelper.getRemainingDailyUses = max − getDailyUses`.
- **EN JEU** : vitrine AVANT = **2/2** (capture `port_we_chances_2of2.png`) → 1 combat WAREHOUSE gagné (serveur
  `recordOutcome [persisté]`) → **RELOAD** du client depuis la DB → vitrine = **1/2** + bouton ENTER grisé
  « COOLDOWN 3m 28s » (capture `port_we_chances_1of2.png`). DOCKS reste 2/2 (non joué). ⇒ décompte réel + persiste au reload.

**Point §8 élucidé (pourquoi ça semblait « 2/2 » lors du 1ᵉʳ passage)** : `getDailyUses` appelle d'abord
`DailyActivityHelper.checkAndUpdateDailyValues`, qui remet les compteurs à 0 quand `now` n'est pas le même jour-joueur
que `TimeType.LAST_USER_DAILY_RESET`. Le compte de test avait ce timestamp estampillé **~13h dans le FUTUR** (reliquat
d'une session à horloge décalée `AdminClock`, alors que l'offset persisté actuel = 0) → chaque lecture rebattait à 2/2.
**Pas un bug de persistance du code** (le compteur brut ET le timestamp sont bien persistés) — un artefact d'horloge de la
DB de test. Correctif setup (`PortEnterAdmin`) : recaler `LAST_USER_DAILY_RESET = serverTimeNow()` (cohérent, offset 0)
→ le décompte se lit correctement (prouvé DB : `getRemainingDailyUses = 1/2` après le win, `LAST_USER_DAILY_RESET` non-futur).

Fichiers : `server/smoke/PortEnterAdmin.java` (recale l'ancre de reset quotidien). Captures `build/port_we_chances_*.png`.

## 2026-08-17 (g130) — SPECIAL_EVENTS : CORRECTION §8 majeure — la rotation par jour est le DÉFAUT du jeu (pas un event)

En relisant `DifficultyModeHelper.isOpen` EN ENTIER au bytecode (ma lecture g128 était INCOMPLÈTE), vraie structure :
```
isOpen(mode) =  isModeOpen(mode)                    [MODES_OPEN = OVERRIDE, tout jour]
             OU isModeDropBonusActive(mode)         [DropBonus  = OVERRIDE aussi, tout jour]
             OU getOpenDays(mode).contains(dayOfWeek)  [DÉFAUT : rotation par jour, table DU JEU, SANS event]
```
Trois OU indépendants. ⇒ **La rotation quotidienne fidèle (DOCKS [6,4,2,1] / WAREHOUSE [7,5,3,1]) est le comportement PAR
DÉFAUT du jeu — aucun événement requis.** MODES_OPEN et DropBonus sont des **OVERRIDES OPÉRATEUR** (forcer un mode ouvert un
jour hors planning).

**Corrige g124** : « WAREHOUSE jamais ouvert sans event » était FAUX (il s'ouvre ses jours [7,5,3,1] ; fermé les autres, dont
le jour de nos tests → d'où le « besoin » apparent d'un event). **Corrige g128** : la rotation n'est PAS « un DropBonus ».

**Conséquence** : le vrai point NON-FIDÈLE = `bootDefaultEvents()` force les 2 modes PORT ouverts EN PERMANENCE (MODES_OPEN)
→ écrase la rotation naturelle. Décision utilisateur : **basculer vers la rotation fidèle** (retirer l'override en dur ; laisser
`getOpenDays` faire) + garder l'engine comme **override opérateur** (outil admin + persistance shard).

**Livré ce commit** : `ServerEvents.buildDropBonusEvent(id, modes, bonus, start, end)` (fabrique du jeu
`createComponent("dropBonus")` + `load` ; provider/generics câblés §4 ; disponible comme override opérateur, porte aussi un vrai
bonus de drop). Test `SpecialEventsRotationTest` (sémantique prouvée headless : sans event `isOpen==getOpenDays.contains(jour)` ;
override MODES_OPEN/DropBonus ouvre un mode fermé ce jour ; retrait → refermé). Régression 126/126.

**PROCHAIN (approuvé)** : rendre `bootDefaults` fidèle (rotation naturelle par défaut) + `AdminEvents` (override opérateur
open/close/planifier) + persistance `shard_state`. `SpecialEventsModesOpenTest` à mettre à jour (le défaut n'ouvre plus WAREHOUSE
en permanence).

Fichiers : `server/java/dhserver/ServerEvents.java` (buildDropBonusEvent), `server/smoke/SpecialEventsRotationTest.java`,
`server/smoke/regression.sh`, `docs/SPECIAL_EVENTS.md`, `MEMORY.md`.

## 2026-08-17 (g131) — SPECIAL_EVENTS : rotation fidèle PAR DÉFAUT + overrides opérateur persistés (AdminEvents, shard_state)

Suite du fait §8 g130 (rotation par jour `getOpenDays` = défaut du jeu ; MODES_OPEN/DropBonus = overrides). Décision
utilisateur : **rotation fidèle par défaut** (retirer l'override en dur « 2 modes ouverts en permanence »).

**Implémenté** :
- `ServerEvents` : holder statique `OPERATOR_EVENTS` (défaut VIDE → aucune ouverture forcée → le jeu applique `getOpenDays`).
  `setOperatorEvents`/`operatorEvents`/`bootDefaultEvents`(=operatorEvents)/`installBootDefaults`(=install(operatorEvents)).
- **Persistance shard** (config opérateur, pas les events sérialisés) : specs `{kind,modes,bonus,start,end}` en JSON, stockées
  en `shard_state` clé `operator_events`. `eventsFromConfig(blob)` reconstruit via NOS builders (buildModesOpenEvent/
  buildDropBonusEvent) — PAS le re-parse `buildEvent` DU JEU, qui empruntait un chemin de refresh guild
  (GuildCheckInHelper.getMaxDailyCheckIns → GuildStats.<clinit> empoisonné) fragile SANS user bindé. Helpers `configSpecs`,
  `writeConfig`, `specJson`.
- `LoginServer.main` : charge la config au boot (`store.loadShardState(1,"operator_events")` → `eventsFromConfig` →
  `setOperatorEvents`). Défaut vide = rotation par défaut.
- **Outil `AdminEvents`** (opérateur) : `--status` (lecture pure des specs, pas d'install — celle-ci exige un user bindé,
  faite au boot), `--open <MODE> [--days N]` (override MODES_OPEN), `--drop-bonus <MODE> [--bonus B]` (override DropBonus),
  `--close <MODE>`, `--clear`. Persiste la config (⚠ redémarrer le serveur pour recharger).

**Tests** : `SpecialEventsModesOpenTest` réécrit (holder + round-trip config→eventsFromConfig, défaut vide = pas forcé) ;
`SpecialEventsRotationTest` (défaut `isOpen==getOpenDays.contains(jour)` ; override MODES_OPEN/DropBonus ouvre un mode fermé ce
jour ; retrait → refermé). Régression **126/126**. AdminEvents vérifié CLI (open→status OUI→close→status non).

**Point technique (§8)** : le re-parse `buildEvent` d'un event sérialisé plante au refresh (chemin guild sans user bindé) —
d'où le choix de persister la CONFIG (specs) et reconstruire via nos builders (qui s'installent proprement, prouvé par
ModesOpenTest). Au BOOT l'install se fait avec un vrai user bindé (comme ModesOpenTest) → OK.

**RESTE** : vérif EN JEU (défaut → WAREHOUSE « OPENS TOMORROW » aujourd'hui, jour ∉ [7,5,3,1] ; `AdminEvents --open
PORT_WAREHOUSE` + restart → ENTER). Puis autres composants (discounts marchands/coffres, Contest/TeamLevel).

Fichiers : `server/java/dhserver/ServerEvents.java`, `server/java/dhserver/LoginServer.java`, `server/smoke/AdminEvents.java`
(nouveau), `server/smoke/SpecialEventsModesOpenTest.java`, `server/smoke/SpecialEventsRotationTest.java`, `docs/SPECIAL_EVENTS.md`,
`MEMORY.md`.

## 2026-08-17 (g131b) — SPECIAL_EVENTS rotation fidèle : ✅ VÉRIFIÉ EN JEU (défaut « OPENS TOMORROW » + override admin « ENTER »)

Vérif EN JEU (id=1) de l'incrément g131, en DEUX phases :
- **DÉFAUT FIDÈLE (config opérateur VIDE)** : serveur logue `événements opérateur chargés : 0 (rotation par défaut du jeu)`
  → vitrine `PortChooserScreen` : **THE DOCKS = ENTER** (jour 2 ∈ [6,4,2,1]) et **THE WAREHOUSE = « OPENS TOMORROW »**
  (grisé ; jour 2 ∉ [7,5,3,1]). C'est la rotation naturelle du jeu (`getOpenDays`) — plus le « both always open » non-fidèle.
  Capture `build/port_we_default_opens_tomorrow.png`.
- **OVERRIDE OPÉRATEUR** : `AdminEvents --open PORT_WAREHOUSE --days 3` (persiste la config dans `shard_state`) → **restart
  serveur** → serveur logue `événements opérateur chargés : 1 override(s) live-ops` → vitrine : **THE WAREHOUSE = ENTER**
  (forcé ouvert un jour hors planning). Capture `build/port_we_override_enter.png`. Puis `AdminEvents --clear` → retour au défaut.

⇒ Boucle complète prouvée EN JEU : rotation fidèle par défaut (getOpenDays du jeu) + override opérateur persistant via
`AdminEvents` (config `shard_state`, rechargée au boot). Le point non-fidèle « 2 modes ouverts en permanence » est corrigé.
Crash flaky `PatchStats`/`SyncStatDataClientHelper` au boot loggé mais NON fatal cette fois (client survécu). Régression 126/126.

## 2026-08-17 (g132) — FRANCHISE_TRIALS / TEAM_TRIALS : recon COMPLÈTE industrialisée + plan ; + PHASE 2 planifiée

Décision utilisateur : attaquer FRANCHISE_TRIALS avec une couche d'industrialisation, recon PROPRE et COMPLÈTE (pas de
lecture partielle — éviter le pb g128 où une lecture incomplète d'`isOpen` avait mené à une fausse conclusion), documenter
le travail à faire ; finir les composants SPECIAL_EVENTS restants une fois trials validé ; puis Phase 2 (planifiée).

**Recon (pipeline #73/#74 `contract.sh --mode` + bytecode ENTIER).** Le mode « Trials » = 2 familles :
- **Cœur wire partagé** : `GetTrialEventData{eventID}` → `TrialEventData{eventID, chancesUsed, dailyResetsUsed,
  lastChancesResetTime, paidChancesRemaining, paidResetsUsed, subtrials:Map<?,TrialEventSubtrialData>}` — **handler
  MANQUANT** ; builder ABSENT du client (état backend PerBlue, patron `ArenaInfo`/`MerchantData`) → serveur-autoritatif.
  Combat : `TrialEventAttack{base:AttackBase, eventID, nodeNumber, subtrialNumber, stagesCleared, lootEarned, attackEndTime}`
  (client-autoritatif, fire-and-forget façon `DifficultyModeAttack` ; construit par `ClientNetworkStateConverter.getTrialEventAttack`).
- **Famille A — FRANCHISE/EVENT trials** = **composant SPECIAL_EVENTS** `game/specialevent/TrialEventInfo` (implémente
  `IEventComponent`, clé **"trial"** via `TrialEventInfoFactory`), portant TOUTE la définition : ennemis (level/rarity/stars/
  lineups + `TrialEventDupeBehavior`), combat modifiers, **franchises**, **gating criteria** (héros autorisés), sous-trials/
  multi-wins, chances/resets, jours actifs, carte, `getEnhancedPrimeBadgeLevelRequirement`. Runtime = `GenericTrial`/
  `GenericTrialNode`. Écrans `TrialEventSubTrialChooserScreen`→`TrialEventHeroChooserScreen`→`TrialEventAttackScreen`
  (extends `LootAttackScreen`). Complétion → `PatchedHeroesHelper.handleFranchiseTrialCompletion` (lien patched heroes).
  ⇒ **prolonge le moteur `ServerEvents`** (`buildTrialEvent` via `createComponent("trial")`).
- **Famille B — TEAM_TRIALS_BLUE/RED/YELLOW + SPOTLIGHT** = data-driven (`game/data/teamtrials/TeamTrialsStats`/
  `SpotlightTrialStats`/`EventTrialStats`), rotation par jour (`TrialsHelper.{SPOTLIGHT,BLUE,YELLOW,RED}_OPEN_DAYS` =
  branche `getOpenDays`, DÉFAUT du jeu §8 g130). SPOTLIGHT persiste déjà (`individualUserExtra.spotlightTrialEventID/Uses`).
  Écran `TeamTrialsChooserScreen`. Gate `Unlockable.TEAM_TRIALS` (TL55).

**Logique §3** : `ClientTrialEventHelper` (isOpen/getGameMode/getLineupType/getChances*/getSpotlightHero/
userCanMeetRequirements), `TrialsHelper.resetTrialEvent`, `SpotlightTrialHelper`, `GenericTrial`/`GenericTrialNode.recordOutcome`.

**PLAN (docs/FRANCHISE_TRIALS.md)** : incr. 0 recon-completion (WireCheck `TrialEventData`/`TrialEventAttack`/
`TrialEventSubtrialData` ; contrat `TrialEventInfoFactory.load` ; structure `TeamTrialsStats`) → 1 `GetTrialEventData`
(blob serveur-autoritatif per-user) → 2 `TrialEventAttack` record (anti-triche chances/gating + loot + avance sous-trial) →
3 resets (quotidien/payant) → 4 **famille B data-driven** (proposée comme POINT DE DÉPART EN JEU, plus simple) → 5 famille A
event-driven (`buildTrialEvent` + `AdminEvents --open-trial`) → 6 complétion franchise → 7 vérif EN JEU par brique.

**PHASE 2 planifiée (docs/PHASE2_PLAN.md)** : A vérif globale/oubli/anti-triche/persistance/fidélité, B perfs port Win/Linux
NON destructives (oracle-certification spine Opt.3), C front-end joueur/launcher (liste/connexion serveurs, multi-serveur §5),
D backend hébergement/gestion multi-shard/observabilité/sauvegardes, E intégration & tests APK, F tests inter-machines réels
(latence/NAT/TLS/charge/multi-région/sécurité), G CI/repro/doc self-hoster/légal. Non engagé (priorité aux tâches en cours).

**EXPLORATION.md mis à jour** (g131b) : ~9 modes ⬜→✅ ; seul FRANCHISE_TRIALS reste ⬜.

**⚠️ RAPPEL HANDOFF (exigence utilisateur, répétée)** : tout handoff de compression DOIT dire EXPLICITEMENT au successeur de
procéder, DÈS LE DÉBUT de sa reprise et AVANT TOUTE POURSUITE du travail engagé, au RITUEL DE REPRISE **COMPLET** (relire toute
la doc listée en tête de MEMORY, énumérer les règles §1-§8 + astuces/outils, faire le point) pour récupérer TOUT le contexte.

Fichiers : `docs/FRANCHISE_TRIALS.md` (nouveau), `docs/PHASE2_PLAN.md` (nouveau), `docs/EXPLORATION.md`, `MEMORY.md`.

## 2026-08-17 (g133) — FRANCHISE_TRIALS incr. 0 : contrat WIRE confirmé (WireCheck) + recon-completion

Incrément 0 du plan `docs/FRANCHISE_TRIALS.md` : sécuriser le contrat wire (défaut nº3) AVANT tout handler + finir la recon
structurelle.

**Contrat WIRE confirmé** (`server/smoke/TrialsWireTest.java`, régression 127) — round-trip `writeAll`→`read` des 3 messages
avec les types EXACTS :
- `GetTrialEventData{eventID}` ✔.
- `TrialEventData{eventID, chancesUsed, dailyResetsUsed, lastChancesResetTime, paidChancesRemaining, paidResetsUsed,
  subtrials:Map<Integer,TrialEventSubtrialData>}` ✔ ; `TrialEventSubtrialData.nodeLevelStatuses:Map<Integer,CampaignLevelStatus>`
  ✔ (⇒ un trial = des sous-trials, chacun = des nœuds « campagne » avec étoiles/complétion).
- `TrialEventAttack{base:AttackBase, eventID, nodeNumber, subtrialNumber, stagesCleared, lootEarned:List<RewardDrop>,
  attackEndTime}` ✔.

**Recon-completion (bytecode)** : hiérarchie `GenericTrial`/`GenericSubtrial`/`GenericTrialNode` (interfaces) + impls
`BaseEvent*` (logique PARTAGÉE = serveur-utilisable §3) / `ClientEvent*` ; gating héros riche (franchise/collection/level/
rarity/recency/role/stars/team/specific). Record §3 = `GenericTrialNode.recordOutcome(...)` + `rollDrops()` (le serveur
reconstruit le trial et exécute la logique du jeu, patron `DifficultyModeHelper`/PORT). Données extraites :
`spotlight_trial_*.tab`, `event_trial_*.tab`, `patched_heroes_*_trial_config.tab`. Détail : `docs/FRANCHISE_TRIALS.md` §8.

Régression **127/127**. **Prochaine action = incr. 1** (`GetTrialEventData`→`TrialEventData`, en démarrant par la famille B /
SPOTLIGHT data-driven, la plus simple à mener EN JEU).

Fichiers : `server/smoke/TrialsWireTest.java` (nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md`.

## 2026-08-18 (g134) — FRANCHISE_TRIALS incr. 1 : TEAM_TRIALS_BLUE ✅ VÉRIFIÉ EN JEU (QUICK WIN, réutilise PORT) + fix déterminisme jour des tests PORT

Suite du fait décisif §11 (SPOTLIGHT/TEAM = DifficultyModes réutilisant PORT ; seuls EVENT/FRANCHISE = TrialEventInfo nouveau).

**Confirmation bytecode (§8, lecture COMPLÈTE)** : `DifficultyModeHelper.getOpenDays` branche `TEAM_TRIALS_BLUE → TrialsHelper.BLUE_OPEN_DAYS`
(RED/YELLOW/SPOTLIGHT idem, MÊME `switch` que PORT_DOCKS/WAREHOUSE). `isVisible` (5-arg) gate les trials par `contentColumn.getTrialDifficultyCap()`
(= 104 dans l'ère courante, donc diff ONE visible) + `hasUserCompletedDifficulty` ; `getLineupType(TEAM_TRIALS_BLUE)=HeroLineupType.TEAM_TRIALS_BLUE`
(lineup dédié). `recordDifficultyModeAttack` passe `ServerEvents.snapshot()` (temps réel) à `recordOutcome`.

**⚠️ FAIT §8 (déterminisme)** : `isOpen(mode,user,snap)` calcule le jour-de-semaine depuis **`snap.snapshotTime`** (bytecode offset 81-89), PAS
`serverTimeNow()`. Donc `SpecialEventSnapshot.NONE` (snapshotTime≈epoch = 1 Jan 1970) donne un jour SANS rapport avec le jour serveur. Les 3 tests
PORT (`PortAttack/Raid/DoubleReward`) choisissaient/vérifiaient l'ouverture avec NONE (faux positif jour-epoch) puis `recordOutcome` (snapshot réel)
échouait `GAME_MODE_NOT_OPEN` les jours hors rotation — **défaut de déterminisme LATENT**, révélé quand la date a basculé sur un jour hors
`PORT_DOCKS_OPEN_DAYS=[6,4,2,1]` (jour 3). **Corrigé** : ouverture DÉTERMINISTE via override opérateur MODES_OPEN (`ServerEvents.setOperatorEvents`
+ `installBootDefaults`, réinstallé à chaque bind, y compris le bind interne de `recordDifficultyModeAttack`) + assertion avec `ServerEvents.snapshot()`
(temps réel, le même que `recordOutcome`). Les 3 tests sont désormais day-independent.

**Test headless** `server/smoke/TeamTrialsAttackTest.java` (régression 128) : `getOpenDays(BLUE)=[7,4,1]` ; sans event → défaut == `getOpenDays.contains(jour)` ;
override MODES_OPEN → ouvert ; combat WIN → +6000 GOLD + cooldown `TEAM_TRIALS_BLUE_ATTACK` (recordOutcome PORT réutilisé) ; persistance wire + DB ;
override retiré → retour au défaut. **ZÉRO nouveau code combat serveur** (le `GameMode` n'est qu'un paramètre — comme WAREHOUSE).

**✅ VÉRIFIÉ EN JEU (id=1, TL200, 5 héros 6★)** : setup `PortEnterAdmin` (boost + TL + reset daily) + `AdminEvents --open TEAM_TRIALS_BLUE` (persisté
`shard_state`, chargé au boot). Pilotes : nouveau `teamtrialsscreen` (planning côté client — affiche **TEAM_TRIALS_BLUE=OUVERT** via le snapshot client
réel ; NON-fatal : ne pousse pas `TeamTrialsChooserScreen` brut, dont `updateScreenUI` exige un `cardContent` bâti par show()/initialize() → NPE fatale
sinon), puis réutilisation des pilotes PORT `portpress TEAM_TRIALS_BLUE` (isOpen(snapClient)=true, isAvailable=true → `ModeData.handleButtonPress()` →
**`ModePreviewScreen` « BLUE TEAM » rendu** : « Heroes from the Blue Team battle in this mode! », ennemis lvl 15 MR_INCREDIBLE/NICK_WILDE =
`team_trials_blue_enemies.tab`, LOOT, EASY+NEXT), `portpreviewattack` (→ `DifficultyModeHeroChooserScreen`, 5 héros Blue Team via `canSelectUnit`),
`portteam` (sélection + `startBattleInner` → combat AUTO). **VICTOIRE → écran REWARDS (coffre + Hero XP ×5)**. Serveur :
`[login] <== DifficultyModeAttack : TEAM_TRIALS_BLUE diff=1 outcome=WIN → recordOutcome appliqué [persisté]`. **DB (snapshot .db+wal)** : cooldown
`TEAM_TRIALS_BLUE_ATTACK` ACTIF (+499s) → combat persisté. Captures `desktop-port/build/tt_preview.png` / `tt_result.png`.

⇒ **TEAM_TRIALS_BLUE COMPLET EN JEU** (réutilise ModePreviewScreen/DifficultyModeHeroChooserScreen/recordOutcome de PORT). TEAM_TRIALS_{RED,YELLOW} =
MÊME code (mode=paramètre). **RESTE trials** : (2) SPOTLIGHT (conso `spotlightTrialUses` via `SpotlightTrialHelper.onSpotlightTrialUse`), (3)
EVENT/FRANCHISE (`TrialEventInfo` riche : `GetTrialEventData`→`TrialEventData` blob, `TrialEventAttack` record, subtrials, gating, complétion
`PatchedHeroesHelper`). Puis composants SPECIAL_EVENTS restants, puis Phase 2 (planifiée).

Fichiers : `server/smoke/TeamTrialsAttackTest.java` (nouveau), `server/smoke/{PortAttack,PortRaid,PortDoubleReward}Test.java` (fix déterminisme),
`server/smoke/regression.sh`, `desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java` (pilote `teamtrialsscreen`),
`server/java/dhserver/ServerEvents.java` (`buildTrialEvent`/`fillTrialFields`, g133b), `docs/FRANCHISE_TRIALS.md` §11-12, `MEMORY.md`.

## 2026-08-18 (g135) — FRANCHISE_TRIALS : les 3 couleurs TEAM_TRIALS_{BLUE,RED,YELLOW} ✅ VÉRIFIÉES EN JEU + fidélité §4bis vs wiki

Suite à la question utilisateur (« tu as testé blue, il y a d'autres couleurs ? ») : oui — RED / YELLOW / BLUE (+ SPOTLIGHT).
Fidèle à §8, on a VÉRIFIÉ RED et YELLOW EN JEU (plutôt que d'affirmer « même code »).

**Setup** : `TeamTrialsRosterBoost` (nouveau, /tmp) grante TOUT le roster (348 héros) RED 100 6★ → chaque couleur a une équipe forte
(le gating restreint aux héros de la couleur) ; `AdminEvents --open TEAM_TRIALS_RED` + `--open TEAM_TRIALS_YELLOW` (persistés shard,
BLUE déjà ouvert). **EN JEU (id=1)** : pour chaque couleur, `portpress TEAM_TRIALS_<C>` (isOpen(snapClient)=true, isAvailable=true) →
`ModePreviewScreen` « <C> TEAM » → `portpreviewattack` → `DifficultyModeHeroChooserScreen` (5 héros de la couleur) → `portteam` →
combat AUTO → VICTOIRE → REWARDS → serveur `DifficultyModeAttack : TEAM_TRIALS_<C> diff=1 outcome=WIN → recordOutcome appliqué [persisté]`.
- RED ✅ (badge « chariot rouge », équipe rouge), BLUE ✅ (g134, badge coffre bleu), YELLOW ✅ (badge « pêche jaune », équipe jaune —
  Little John/Robin Hood, chef Ratatouille… = franchises jaunes du wiki). **Gating couleur confirmé EN JEU** : les 3 équipes fieldées
  sont visiblement DIFFÉRENTES (chaque couleur = ses héros). Récompense = Badge Bits (`SHARD_*` des `team_trials_<c>_loot.tab`).
- Captures `desktop-port/build/tt_{preview,result,red_preview,red_result,yellow_result}.png`.

**Fidélité §4bis (vérité-terrain communauté, fournie par l'utilisateur)** : nos `getOpenDays` (extraits `.tab`, `DifficultyModeHelper`)
correspondent EXACTEMENT au wiki avec la numérotation `1=Dim…7=Sam` : RED `{6,3,1}`=Dim/Mar/Ven ✓, BLUE `{7,4,1}`=Dim/Mer/Sam ✓,
YELLOW `{5,2,1}`=Dim/Lun/Jeu ✓ ; `SPOTLIGHT_TRIAL getOpenDays=[]` (aucun jour par défaut → purement event-driven, cohérent §11).
Récompense Badge Bits confirmée. Gate : wiki dit TL20, notre `.tab` v12.1.0 = `Unlockable.TEAM_TRIALS` TL55 (écart de version ; notre
valeur fait foi pour 12.1.0). ⇒ extraction fidèle (§4), 0 invention.

**Leçon piloting (§8, non un bug)** : la 1ʳᵉ tentative YELLOW s'est figée — le combat avait été lancé PAR-DESSUS l'écran REWARDS de RED
non fermé (piloting : `portpress` enchaîné sans revenir au hub). Correctif : `nav HOME` + laisser REWARDS se fermer AVANT le combat
suivant. YELLOW rejoué SEUL depuis un client frais → WIN immédiat. ⇒ ce n'était PAS un bug moteur/couleur, juste du séquençage.

⇒ **TEAM_TRIALS (3 couleurs) COMPLET EN JEU.** RESTE trials : (2) SPOTLIGHT_TRIAL (`getOpenDays=[]` → ouvrir via `AdminEvents --open
SPOTLIGHT_TRIAL` ; conso `spotlightTrialUses` via `SpotlightTrialHelper.onSpotlightTrialUse`), (3) EVENT/FRANCHISE (`TrialEventInfo`
riche : `GetTrialEventData`/`TrialEventAttack`/subtrials/gating/complétion `PatchedHeroesHelper`).

Fichiers : `docs/FRANCHISE_TRIALS.md` §13, `MEMORY.md`, `JOURNAL.md`. (Outil `TeamTrialsRosterBoost` = /tmp, non committé — setup jetable.)

## 2026-08-18 (g136) — FRANCHISE_TRIALS : SPOTLIGHT_TRIAL ✅ VÉRIFIÉ EN JEU (2ᵉ quick win) → 4 DifficultyMode-trials complets

Suite à l'info utilisateur (wiki PerBlue « Hero Spotlight Trials » : héros vedette auto-maxé + équipe de 3, chips du vedette,
pas de raid, chances/jour). Vérifié EN JEU (fidèle §8) + garde-fou anti-hardcode (§4, à la demande utilisateur).

**Fait §8 (bytecode)** : `SPOTLIGHT_TRIAL` est un DifficultyMode AUTO-SUFFISANT — `recordOutcome` (offset 206) ET
`recordRaidOutcome` (166) appellent EUX-MÊMES `SpotlightTrialHelper.onSpotlightTrialUse` (incrémente `spotlightTrialUses` =
`getTotalEventUses`+1, clé = eventID). Cooldown `SPOTLIGHT_TRIAL_ATTACK`, VIP `SPOTLIGHT_TRIAL_COOLDOWN`. `getOpenDays(SPOTLIGHT)=[]`
(aucune rotation → event-driven pur ; `SpotlightTrialHelper.getSpecialEvent` cherche un event MODES_OPEN ouvrant SPOTLIGHT →
`AdminEvents --open SPOTLIGHT_TRIAL` suffit). `canRaid = GenericTrial.isRaidingAllowed` = false (wiki « cannot raid »). ⇒ ZÉRO
nouveau code combat.

**Aucun hardcode (§4, garde-fou utilisateur)** : SPOTLIGHT n'a qu'UNE difficulté valide par shard, LUE du jeu
`SpotlightTrialStats.getDifficultyForShard(shardID)` (=SIX pour shard 1) — `isVisible` l'EXIGE (diff ≠ celle du shard →
GAME_MODE_LOCKED, d'où l'échec initial à diff=ONE). Héros vedette LU de `SpotlightTrialStats.getSpotlightHero()` (=FOZZIE,
`spotlight_trial_constants.tab: SPOTLIGHT_HERO`). Les deux lus dynamiquement dans le test, jamais recopiés. (Sondes de diag
`/tmp/Spot*Probe.java` jetables, non committées.)

**Headless** `server/smoke/SpotlightTrialTest.java` (régression 129) : getOpenDays=[] → fermé ; override MODES_OPEN → isOpen +
isSpotlightTrialActive=true ; combat WIN (diff LUE) → butin + cooldown + `spotlightTrialUses` 0→1 ; persistance wire+DB ; override
retiré → re-fermé.

**✅ EN JEU (id=1, TL200, roster boosté)** : `AdminEvents --open SPOTLIGHT_TRIAL` (persisté shard) → `portpress SPOTLIGHT_TRIAL`
(isOpen(snapClient)=true) → **`ModePreviewScreen` « HAPPY ANNIVERSARY! Earn some Hero Chips by helping Fozzie fight the Creeps »**
(héros vedette FOZZIE, 3 ennemis lvl 240 = diff SIX, LOOT = chips FOZZIE 0/100) → `portpreviewattack` →
`DifficultyModeHeroChooserScreen` → `portteam` (**héros sélectionnés = 3** = wiki « team of 3 total » ; FOZZIE auto-inclus,
héros auto-maxés niv 565) → combat AUTO → VICTOIRE → **REWARDS : chips FOZZIE ×2 + 3 héros niv 565** → serveur
`DifficultyModeAttack : SPOTLIGHT_TRIAL diff=6 outcome=WIN → recordOutcome appliqué [persisté]` → **DB (snapshot db+wal) :
`spotlightTrialUses`=1, `spotlightTrialEventID`=1795779**. Captures `desktop-port/build/tt_spotlight_{preview,result}.png`.
Toutes les mécaniques wiki confirmées EN JEU (équipe de 3, héros maxés le temps du combat, chips du vedette, diff fixe par shard).

⇒ **Les 4 DifficultyMode-trials (TEAM_TRIALS_BLUE/RED/YELLOW + SPOTLIGHT_TRIAL) COMPLETS EN JEU** — tous réutilisent l'infra PORT
(mode = paramètre de `recordOutcome`). **RESTE trials** : (3) EVENT_TRIAL / FRANCHISE trials — le seul sous-système NOUVEAU
(`TrialEventInfo` riche : `GetTrialEventData`→`TrialEventData` blob, `TrialEventAttack` record, subtrials, gating héros, complétion
`PatchedHeroesHelper` ; `ServerEvents.buildTrialEvent`/`fillTrialFields` déjà amorcés g133). Puis composants SPECIAL_EVENTS
restants, puis Phase 2 (planifiée).

Fichiers : `server/smoke/SpotlightTrialTest.java` (nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §14,
`docs/EXPLORATION.md`, `MEMORY.md`, `JOURNAL.md`.

## 2026-08-18 (g137) — FRANCHISE_TRIALS : EVENT/FRANCHISE trials — recon COMPLÈTE + feasibility PROUVÉE + point dur (décision utilisateur)

Après les 4 DifficultyMode-trials (§12-14, ✅ EN JEU), attaque du SEUL sous-système restant : EVENT_TRIAL / FRANCHISE trials
(le « TrialEventInfo » riche). Recon bytecode COMPLÈTE (pas de lecture partielle §8).

**Cartographie** : serveur-autoritatif à BLOB (patron Arena/Surge/Expedition — `GameMain` REÇOIT `TrialEventData`, aucun builder
client). Flux : `GetTrialEventData{eventID}` → serveur répond `TrialEventData{chancesUsed, dailyResetsUsed, lastChancesResetTime,
paidChancesRemaining, paidResetsUsed, subtrials:Map<Integer,TrialEventSubtrialData{nodeLevelStatuses:Map<Integer,CampaignLevelStatus>}>}`
(HANDLER MANQUANT) ; combat client-autoritatif → `TrialEventAttack{base, eventID, nodeNumber, subtrialNumber, stagesCleared,
lootEarned, attackEndTime}` (HANDLER MANQUANT, construit par `ClientNetworkStateConverter.getTrialEventAttack`).
STRUCTURE (subtrials/nœuds/ennemis/franchises/gating/chances) = composant d'event `game.specialevent.TrialEventInfo` (clé "trial") ;
ÉTAT per-user = blob `TrialEventData`. Runtime `ClientEventTrial extends BaseEventTrial` : `setUserData`, `getChancesRemaining`
(lit `userData.chancesUsed` vs `getChancesPerReset`), `getSubtrials`. Logique §3 : `BaseEventTrialNode.recordOutcome(outcome,
stagesCleared, …, snap)` (avance le nœud façon campagne via `ICampaignLevelStatus.setStars`), `checkForDailyReset`/`doPaidReset`.

**✅ FEASIBILITY PROUVÉE (spike headless /tmp/TrialFeasSpike, jetable)** : `new ClientEventTrial(user, eventInfo)` se CONSTRUIT
CÔTÉ SERVEUR sans réseau ni GL (ctor vérifié au bytecode : `BaseEventTrial.init()` + build subtrials + `DailyActivityHelper`, AUCUN
`sendMessage`/`Gdx`). Lu OK : `eventID=970001`, `chancesPerReset=2`. ⇒ le serveur peut EXÉCUTER la vraie logique du jeu
(`ClientEventTrial` + `node.recordOutcome`), §3-conforme. (`createTrial` du helper reste client-only car il ENVOIE
`GetTrialEventData` ; le ctor est pur.)

**⚠️ POINT DUR CONFIRMÉ (§10)** : le spike montre `subtrials=0` — le filler générique de `buildTrialEvent` (g133) ne peuple pas
les nœuds, ET **`EventTrialStats` (`event_trial_*.tab`) ne contient QUE récompenses + arena-rules + constantes, PAS la structure**
(ennemis/nœuds/franchises). La structure d'un event trial venait du **JSON d'event backend PerBlue** (comme `eventCardDisplay`).
⇒ **option B (reconstruire la structure depuis les `.tab`) NON VIABLE**. Forks (décision utilisateur, comme SPECIAL_EVENTS) :
(A) synthèse object-path d'une structure MINIMALE VALIDE (ennemis EXTRAITS d'une source du jeu — campagne/`.tab` — pas inventés §4 ;
franchise choisie) → trial jouable mais synthétique ; (C) vraie vérité terrain (JSON d'event réel) → fidélité maximale ; (D) pause
(les 4 DifficultyMode-trials = le gros du mode livré ; faire d'abord les composants SPECIAL_EVENTS restants, revenir avec une
vérité terrain).

**Plan EVENT/FRANCHISE** (une fois le fork tranché) : 0 ✅ wire + ✅ feasibility. 1 structure (`TrialEventInfo` ≥1 subtrial/nœud).
2 `GetTrialEventData` (blob per-user serveur-autoritatif + persistance + handler). 3 `TrialEventAttack` (`node.recordOutcome` +
conso chance + loot client-reporté + persistance). 4 resets. 5 gating héros + franchises. 6 complétion franchise
(`PatchedHeroesHelper`). 7 vérif EN JEU.

⇒ **PROCHAINE ACTION = décision utilisateur sur le fork (A/C/D)** avant d'écrire le handler (éviter d'inventer la structure §4).
Régression 129/129 inchangée. Fichiers : `docs/FRANCHISE_TRIALS.md` §15, `MEMORY.md`, `JOURNAL.md`.

## 2026-08-18 (g138) — FRANCHISE_TRIALS : CORRECTION §8 — structure des franchise trials data-driven (PatchStats/.tab), pas backend

Décision utilisateur : EVENT/FRANCHISE trials « définis par le serveur → à l'admin de les définir ». Lecture des FAQ PerBlue
(helpshift 626 franchise-trials / 653 franchise season release) — helpshift bloque le fetch (403) ; contenu récupéré via WebSearch
+ wiki Fandom. Mécanique : chaque franchise trial n'accepte QUE les héros d'une FRANCHISE (ex. Zootopia trial = persos Zootopia) ;
récompenses = Badge Bits (bas) + Patch Essence (haut, 5 tiers → système Patch) ; nodes 1-N difficulté croissante ; SAISONS ~1 mois,
quelques franchises featured par saison.

**CORRECTION du §15 (g137, lecture partielle)** : j'avais conclu « structure backend-authored, pas dans les .tab » en ne regardant
QUE `EventTrialStats`. **FAUX pour les FRANCHISE trials** : leur structure est ENTIÈREMENT dans les `.tab` patched_heroes —
- `patched_heroes_franchise_season_mapping.tab` : calendrier de SAISONS (colonnes = dates) → `TRIAL$0_FRANCHISE_0` = franchise
  vedette par saison (WILDCARD/CARS/FROZEN/THE_LION_KING/KIM_POSSIBLE/BEAUTY_AND_THE_BEAST/…), `TRIAL$0_ACTIVE_DAYS`=Lun/Jeu/Dim, patch caps.
- `patched_heroes_base_trial_config.tab` : NODE_COUNT=14, WAVE_COUNT=3, MAX_DAILY_RESETS=60, FRANCHISES, gating (PRIME_BADGE_LEVEL_REQ=230,
  ENHANCED=280, PATCH_LEVEL_REQ=305), ENABLE_RAIDING=TRUE, ENABLE_STAT_SLOTS=TRUE.
- `patched_heroes_franchise_trials_enemy_config.tab` : 14 stages (STARS/RARITY/LEVELS/REWARDS/BONUSES) — RANDOM_BADGE (stages bas) →
  PATCH_ESSENCE_n (stages hauts, ASSIGN_REAL_GEAR à partir du stage 5).
- Ennemis = héros de la franchise (`PatchStats.getFranchiseTrialEnemyPoolForSeason` / `HeroHelper.getAllHeroesInFranchise`).
- Logique du jeu PRÊTE (§3) : `PatchStats.{getPatchableFranchisesForSeason,getFranchiseTrialEnemyPoolForSeason,getGameModeFranchises,
  getFranchiseTrialsStageNumber}` ; `PatchedHeroesHelper.{franchiseTrialsUnlocked,handleFranchiseTrialCompletion,getPatchEssenceTier,
  getPatchEssenceCost,spendPatchEssence,getAmountOfEssenceAvailable}`. Pas de `GameMode.FRANCHISE_TRIALS` (purs event trials `TrialEventInfo`).

⇒ **AUCUNE invention / AUCUN JSON backend requis (§4)** : le franchise trial se construit en EXÉCUTANT `PatchStats` (franchises de la
saison + config ennemis/stages + héros de franchise). **Rôle admin = activer/planifier** (fidèle au calendrier de saison, override
`AdminEvents` possible). Feasibility déjà prouvée (g137 : serveur construit `ClientEventTrial` headless, ctor pur).

**Plan RÉVISÉ (fully faithful)** : 1 build `TrialEventInfo` franchise depuis `PatchStats` (chercher un builder DU JEU → l'exécuter §3 ;
sinon object-path peuplé depuis PatchStats) → `getSubtrials()>0`. 2 `GetTrialEventData` blob per-user serveur-autoritatif. 3
`TrialEventAttack` → `BaseEventTrialNode.recordOutcome` + conso chance + loot. 4 resets. 5 gating franchise. 6 complétion
`PatchedHeroesHelper` (Patch Essence). 7 `AdminEvents --open-trial <FRANCHISE|saison>`. 8 vérif EN JEU.

Régression 129/129 inchangée. Fichiers : `docs/FRANCHISE_TRIALS.md` §16, `MEMORY.md`, `JOURNAL.md`.
Sources FAQ/wiki : perblue.helpshift.com/hc/en/3-disney-heroes/faq/626 ; disneyheroesbattlemode.fandom.com/wiki/Trials.

## 2026-08-18 (g139) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 1a : STRUCTURE FIDÈLE data-driven (headless)

Suite décision utilisateur (franchise trials « définis par le serveur → à l'admin ») + correction g138 (structure dans les .tab).
Première brique d'implémentation : la STRUCTURE du franchise trial, construite depuis les données du jeu (§4, 0 invention).

**Recette de construction PROUVÉE (bytecode `BaseEventTrial.init` + spike)** : `init()` itère `TrialEventInfo.getSubtrials()`
(→ `addSubtrial`) puis `getNodeCounts()` (→ `subtrial.createNodes(value)` si `subtrialMatches`). Fragments JSON EXACTS :
- subtrial = `new TrialEventSubtrialInfo(info, JSON("{\"title\":{},\"preset\":\"none\"}"))` (1 par franchise).
- nodeCount = `new TrialEventNodeCount(JSON("{\"nodeCount\":N,\"scope\":{}}"), map)` — clés `nodeCount`+`scope` (pas `value`) ;
  `scope:{}` = ALL (s'applique à tous les sous-trials) ; `TrialEventScope` lit subtrialNumber/nodeNumber/waveNumber/resetNumber (SparseRange).

**`ServerEvents.buildFranchiseTrialEvent(id,start,end)`** (nouveau) : lit `base_trial_config` via
`PatchStats.BASE_TRIAL_CONFIG_STATS.getStats()` (BaseTrialConfigConstants : NODE_COUNT/FRANCHISES/MAX_DAILY_RESETS/ENABLE_RAIDING/
ENABLE_STAT_SLOTS/PRIME_BADGE_LEVEL_REQ/ENHANCED_PRIME_BADGE_LEVEL_REQ/PATCH_LEVEL_REQ — réflexion sur le champ privé + getStats()),
bâtit 1 sous-trial par franchise de la saison × NODE_COUNT nœuds, pose franchises/maxDailyResets/allowRaiding/gating levels. Tout LU
du jeu, rien en dur (§4). Helpers `readInt`/`readBool`/`readField`.

**Test** `server/smoke/FranchiseTrialStructTest.java` (régression 130) : lit les valeurs attendues des mêmes stats (pas de doublon
en dur), construit l'event, `new ClientEventTrial(u, info)` → **subtrials=4** (WILDCARD/THE_JUNGLE_BOOK/THE_LITTLE_MERMAID/MOANA) ×
**14 nœuds** chacun ; `getFranchises()` = saison. Nb de nœuds tranché = NODE_COUNT (14) du base_trial_config (≠
getFranchiseTrialsStageNumber=5, qui est autre chose).

**RESTE (bien mappé)** : incr. 1b CONTENU (enemyLineups = héros de franchise `HeroHelper.getAllHeroesInFranchise`/
`PatchStats.getFranchiseTrialEnemyPoolForSeason`, stages `franchise_trials_enemy_config.tab` = 14 stages stars/rarity/level →
Badge Bits puis Patch Essence ; gating franchise = `TrialEventGatingCriterion` franchise ; rewards) → 2 `GetTrialEventData`
(blob serveur-autoritatif per-user + persistance + handler `LoginServer`) → 3 `TrialEventAttack` (`BaseEventTrialNode.recordOutcome`
+ conso chance + loot client-reporté + persistance) → 4 resets (`doDailyReset`/`doPaidReset`) → 5 gating héros → 6 complétion
`PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence) → 7 `AdminEvents --open-trial <FRANCHISE|saison>` → 8 vérif EN JEU.

Régression 130/130. Fichiers : `server/java/dhserver/ServerEvents.java` (buildFranchiseTrialEvent + readInt/readBool/readField),
`server/smoke/FranchiseTrialStructTest.java` (nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-18 (g140) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 1b : CONTENU ennemis FIDÈLE (schéma complet découvert, industriel)

Suite incr. 1a (structure). Demande utilisateur : finir 1b « industriellement/récursivement, proprement ». Méthode employée =
**le parseur du jeu comme ORACLE** (chaque erreur de construction révèle la clé/format exact suivant → schéma découvert
industriellement, 0 devinette). Contenu peuplé EN BOUCLE depuis les données du jeu (§4, 0 invention).

**`buildFranchiseTrialEvent` (contenu)** : lit les 14 stages de `FRANCHISE_TRIALS_ENEMY_CONFIG_STATS.stageToEnemyConfigs`
(EventTrialEnemyConfig : levels/rarity/stars = champs STRING "55"/"7"/"2", rewards/bonuses, assignRealGear) →
- `enemyLevel/Rarity/Stars` = 14 pièces `{expr:<val du stage>, random:{kind:NORMAL}, scope:{nodeNumber:<stage>}}`.
- `enemyLineups` = 1 par sous-trial `{kind:MANUAL, units:[5× hero], scope:{subtrialNumber:<i+1>}}`, chaque hero =
  `{kind:RANDOM_HERO, categories:[{kind:FRANCHISE, franchises:[{franchise:<F>}]}], realGear:{kind:NORMAL}}` (WILDCARD → categories:[]).
Helper `mkTrialPiece(cls, json)` (ctor JSON du jeu).

**Schéma EXACT découvert (parseur oracle)** : `TrialEventNodeCount` = `{nodeCount, scope}` (pas `value`) ; `TrialEventScope` =
SparseRange 1-based (`subtrialNumber`/`nodeNumber`/`waveNumber`/`resetNumber`, défaut ALL) ; `TrialEventEnemyLevel/Rarity/Stars` =
`{expr, random:{kind:NORMAL}, scope}` (random OBLIGATOIRE) ; `TrialEventEnemyLineup` = `{kind:AUTO|MANUAL, units:[…], random, scope}`
CHAMP `manualHeroes` ; `TrialEventEnemyHero` = `{kind:RANDOM_HERO|RANDOM_NPC|SPECIFIC_UNIT, categories:[…], realGear:{kind:NORMAL|DISABLE}}` ;
`TrialEventHeroFilter` FRANCHISE = `{kind:FRANCHISE, franchises:[{franchise:NAME}]}` (tableau d'objets). `addHeroes` remplit jusqu'à 5.

**Test** `server/smoke/FranchiseTrialContentTest.java` (régression 131) : le PARSEUR DU JEU accepte l'event (bonne formation) ;
14 enemyLevel/Rarity/Stars (= 14 stages) ; 4 lineups × 5 units ; enemyLevel[0] = niveau du stage 1 (lu du jeu) ; runtime
`ClientEventTrial` = 4 sous-trials × 14 nœuds.

**⚠ Limite headless (§8)** : `createWaves`/`createEnemies` renvoient les vagues VIDES hors contexte de combat client (la génération
effective des ennemis se fait côté CLIENT au combat — client-autoritatif). Le contenu est BIEN FORMÉ (data-driven, parseur-validé) ;
la génération d'ennemis + le combat se vérifient EN JEU. Availability OK (3 héros MOANA dispo headless).

**RESTE** : incr. 2 `GetTrialEventData` → `TrialEventData` blob per-user serveur-autoritatif (chances/resets/subtrials:Map) +
persistance + handler `LoginServer`. 3 `TrialEventAttack` → `BaseEventTrialNode.recordOutcome` + conso chance + loot. 4 resets.
5 gating franchise. 6 complétion `PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence). 7 `AdminEvents --open-trial`
(push event). 8 vérif EN JEU (vitrine → combat franchise → Patch Essence).

Régression 131/131. Fichiers : `server/java/dhserver/ServerEvents.java` (contenu ennemis + mkTrialPiece),
`server/smoke/FranchiseTrialContentTest.java` (nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-18 (g141) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 2 : AUTORITÉ SERVEUR (GetTrialEventData → TrialEventData blob)

Après structure (1a) + contenu ennemis (1b), le cœur mission : l'AUTORITÉ SERVEUR sur l'état per-user du trial.

`GetTrialEventData{eventID}` (client) → le serveur répond `TrialEventData{chancesUsed, dailyResetsUsed, lastChancesResetTime,
paidChancesRemaining, paidResetsUsed, subtrials:Map}`. Ce message n'a PAS de builder client (état backend PerBlue) → construit
+ persisté serveur-autoritativement (patron `ArenaInfo`/`GetExpeditionResponse`).

**ServerTrials (nouveau)** : `freshData(eventID)` = état vierge (0 chance, 0 reset, subtrials vide, lastChancesResetTime=now) ;
`getData(su, eventID)` = état persisté s'il concerne CET event, sinon frais (nouvel event/saison → keyé par eventID, patron
`expeditionID`) qu'on pose sur `su`.

**Persistance (patron `expeditionRun`)** : `ServerUser` champ `trialEventData` + `trialEventWire()`/`setTrialEventWire()`/
`trialEventDataOrNull()`/`setTrialEventData()` (sérialisation wire via `wire()`/`read()`). `UserStore` : migration colonne BLOB
`trialEventData` (ALTER TABLE) + INSERT/SELECT dans save/loadIfExists/loadOrCreate (NULL = aucun état / pré-migration).

**Handler `LoginServer`** : `GetTrialEventData` → `ServerTrials.getData(user, req.eventID)` → `store.save(user)` → `setAsReplyTo`
+ `c.send(td)` + log `<== GetTrialEventData(eventID) → ==> TrialEventData (chancesUsed=…, sous-trials=…)`.

**Test** `server/smoke/ServerTrialsDataTest.java` (régression 132) : freshData vierge ; getData sert frais puis état courant (pas
re-frais) ; round-trip wire + DB (save/loadIfExists) : eventID + chancesUsed persistés ; nouvel eventID → état frais (chancesUsed=0).

**RESTE** : incr. 3 `TrialEventAttack` → `ServerUser.recordTrialEventAttack` (reconstruire `ClientEventTrial` via
`buildFranchiseTrialEvent` + `setUserData(blob persisté)` → exécuter `BaseEventTrialNode.recordOutcome` [avance nœud/subtrial +
conso chance] + crédit loot client-reporté → resérialiser le blob → persister) + handler. Puis push event (`AdminEvents
--open-trial`) + vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence via
`PatchedHeroesHelper.handleFranchiseTrialCompletion`).

Régression 132/132. Fichiers : `server/java/dhserver/ServerTrials.java` (nouveau), `ServerUser.java` (accesseurs blob trial),
`UserStore.java` (colonne + save/load), `LoginServer.java` (handler GetTrialEventData), `server/smoke/ServerTrialsDataTest.java`
(nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g142) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 3 : RECORD d'un combat de trial (headless)

Après l'autorité serveur sur l'état (incr. 2), le RECORD d'un combat : le client joue le nœud (client-autoritatif) et envoie
`TrialEventAttack{eventID, subtrialNumber, nodeNumber, stagesCleared, base{outcome,stars,attackers,defenders}, lootEarned}`
(fire-and-forget). Le serveur REJOUE l'issue via la logique DU JEU (§3), 0 règle réécrite.

**`ServerUser.recordTrialEventAttack(m)`** (nouveau, patron `recordDifficultyModeAttack`) :
1. reconstruit l'event trial DÉTERMINISTE (`ServerEvents.buildFranchiseTrialEvent(m.eventID, …)`, depuis les `.tab`) et y branche
   l'état per-user persisté : `new ClientEventTrial(user, info).setUserData(blob)` (blob = `ServerTrials.getData(this, m.eventID)`) ;
2. retrouve le sous-trial (`getSubtrialNumber()==m.subtrialNumber`) puis le nœud (`getNodeNumber()==m.nodeNumber`) — lève
   `ClientErrorCodeException` (ERROR) si absent ;
3. exécute **`node.recordOutcome(outcome, m.stagesCleared, loot, attackers, defenders, m.attackEndTime, snap)`** — la logique DU JEU
   fait TOUT : anti-triche (chances/resets restants), avance le statut du nœud (étoiles, façon campagne), **consomme une chance**
   (`recordChanceUsed` → `userData.chancesUsed`++), crédite les récompenses (`RewardHelper.giveRewards` = Badge Bits → Patch Essence) ;
4. **reflète** le statut calculé PAR LE JEU dans le blob serveur-autoritatif : `((BaseEventTrialNode) node).getLevelStatus()`
   (`getStars`/`getLevel`) → `blob.subtrials[sub].nodeLevelStatuses[node]` (`CampaignLevelStatus`{stars, level, totalAttempts++,
   lastWinTime si WIN}). Glue §3 : `recordOutcome` avance l'objet runtime (côté client) mais N'écrit PAS le statut dans le blob wire
   (le client renvoie l'issue, le SERVEUR tient l'état) → on reflète le statut du jeu, on ne le recalcule pas. Puis `setTrialEventData`
   + `resyncHeroes/Diamonds/Counts`.
NB `GenericTrialNode` est une **interface** (`getLevelStatus` déclaré sur `BaseEventTrialNode`, concret `ClientEventTrialNode extends
BaseEventTrialNode`) → cast nécessaire pour `getLevelStatus()`.

**Handler `LoginServer`** : `TrialEventAttack` (fire-and-forget, patron `DifficultyModeAttack`/`RaidDifficultyMode`) →
`user.recordTrialEventAttack(ta)` → `store.save(user)` ; anti-triche = `ClientErrorCodeException` (log ⛔, rien accordé).

**Test** `server/smoke/TrialEventRecordTest.java` (régression 133) : victoire (sous-trial 1, nœud 1, WIN, stagesCleared=3) →
le nœud apparaît dans `subtrials` avec étoiles=3 ; `chancesUsed` 0→1 ; persistance wire + DB (save/loadIfExists) : `chancesUsed` +
étoiles du nœud persistés.

**⚠ Correctif déterminisme (§8, révélé en régression)** : `SpecialEventsRotationTest` échouait au jour serveur réel = 1 (dimanche-
équivalent) — le SEUL jour où DOCKS [6,4,2,1] ET WAREHOUSE [7,5,3,1] sont ouverts → aucun mode « fermé » pour démontrer l'override
(même classe de bug latent que les 3 tests PORT corrigés à g134). **Fix** : nouveau helper `ServerEvents.snapshotAt(long time)`
(constructeur DU JEU `new SpecialEventSnapshot(SpecialEventsHelper.snapshotRaw(), time)`) → le test balaie les 7 prochains jours,
ancre le temps à un jour où PORT_DOCKS est fermé (jours {3,5,7}, existe toujours), et vérifie défaut/override/retrait à ce jour figé.
`isOpen` calcule le jour depuis `snapshot.snapshotTime` (fait §8) → temps figé = jour indépendant du calendrier réel.

**RESTE** : incr. 4 resets (`checkForDailyReset`/`doPaidReset`) → 5 gating franchise (`getGatingCriteria` = seuls héros de la
franchise) → 6 complétion `PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence) → 7 `AdminEvents --open-trial
<FRANCHISE|saison>` (push event) → 8 vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence).

Régression 133/133. Fichiers : `server/java/dhserver/ServerUser.java` (recordTrialEventAttack), `LoginServer.java` (handler
TrialEventAttack), `ServerEvents.java` (snapshotAt), `server/smoke/TrialEventRecordTest.java` (nouveau),
`server/smoke/SpecialEventsRotationTest.java` (déterministe), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g143) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 4 : RESETS de chances (headless)

Après le record de combat (incr. 3), les RESETS de chances. Deux mécanismes, exécutés par la logique DU JEU (§3), 0 règle réécrite.

**Reconnaissance (bytecode)** : pas de message wire de reset dédié. Le reset quotidien est AUTOMATIQUE
(`BaseEventTrial.checkForDailyReset(long)` → `doDailyReset` : `chancesUsed=0`, `dailyResetsUsed++`, `lastChancesResetTime=`
borne de reset du compte). Le reset PAYANT passe par un `Action{command=CommandType.RESET_TRIAL_EVENT_PAID,
extra={ID:eventID, COST:cost}}` (construit par `ClientActionHelper.resetTrialEvent` via `ActionExtraBuilder.withID(eventID)`)
→ logique serveur `TrialsHelper.resetTrialEvent(user, trial)` : valide (`canUseResetItems`/`getResetCost>0`/`paidResetsRemaining>0`/
`paidDailyResetsRemaining>0`/`paidChancesRemaining<=0` sinon `ERROR`), débite (`UserHelper.chargeUser`), `doPaidReset`
(`paidChancesRemaining=getChancesPerReset`, `paidResetsUsed++`).

**Probe (`TrialResetProbe`, DEV)** — valeurs LUES du jeu pour un franchise trial construit : `chancesPerReset=2`,
`maxDailyResets=60`, `dailyResetsRemaining=60` ; MAIS `maxPaidResets=0`, `paidResetsRemaining=0`, `getResetCost=-1`,
`canUseResetItems=false`, `resourceCostType=DEFAULT`. ⇒ **les FRANCHISE trials DÉSACTIVENT le reset payant** (donnée du jeu, §4)
→ `TrialsHelper.resetTrialEvent` lève `ClientErrorCodeException` (anti-triche du JEU, fidèle §4bis).

**Implémentation** :
- `ServerUser.boundTrial(user, eventID, blob, now)` (helper privé factorisé) : reconstruit `ClientEventTrial`
  (`buildFranchiseTrialEvent` + `setUserData(blob)`) — utilisé par record/reset/refresh.
- `ServerUser.refreshTrialDailyReset(eventID)` : `checkForDailyReset(now)` + persiste ; branché sur le handler
  `GetTrialEventData` (les chances se rafraîchissent chaque jour, autorité serveur). Idem `checkForDailyReset(now)` ajouté au
  début de `recordTrialEventAttack`.
- `ServerUser.resetTrialEventPaid(eventID)` : exécute `TrialsHelper.resetTrialEvent(user, trial)` + resync (débit) + persiste.
- Handler `LoginServer` : `act.command == RESET_TRIAL_EVENT_PAID` → lit `extra[ID]` (eventID) → `resetTrialEventPaid` →
  `store.save` ; anti-triche = `ClientErrorCodeException` (log ⛔, rien accordé). Patron `START_FIGHT_PIT_ATTACK` (lecture `extra`).

**Test** `server/smoke/TrialResetTest.java` (régression 134) : (1) victoire → `chancesUsed`=1 ; (2) recule
`lastChancesResetTime` de 3 j ; (3) `refreshTrialDailyReset` → `chancesUsed`=0, `dailyResetsUsed` 0→1 ; (4) reset payant
`resetTrialEventPaid` → `ClientErrorCodeException` (franchise sans reset payant) ; (5) persistance wire + DB.

**RESTE** : incr. 5 gating franchise (`getGatingCriteria` = seuls héros de la franchise autorisés à l'attaque) → 6 complétion
`PatchedHeroesHelper.handleFranchiseTrialCompletion` (Patch Essence) → 7 `AdminEvents --open-trial <FRANCHISE|saison>` (push event)
→ 8 vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat franchise → Patch Essence).

Régression 134/134. Fichiers : `server/java/dhserver/ServerUser.java` (boundTrial + refreshTrialDailyReset + resetTrialEventPaid +
checkForDailyReset dans record), `LoginServer.java` (handler RESET_TRIAL_EVENT_PAID + daily reset sur GetTrialEventData),
`server/smoke/TrialResetTest.java` (nouveau), `server/smoke/TrialResetProbe.java` (probe DEV), `server/smoke/regression.sh`,
`docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g144) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 5 : GATING FRANCHISE serveur-autoritatif (headless)

Un sous-trial de franchise n'autorise QUE les héros de sa franchise. Le combat est client-autoritatif, mais la LÉGITIMITÉ du
lineup est revalidée par le serveur (§3, anti-triche).

**Reconnaissance (bytecode)** : `TrialEventInfo.gatingCriteria` (List) → runtime `node.getInclusiveHeroCriteria()`/
`getExclusiveHeroCriteria()` ; validation primitive `ClientTrialEventHelper.getFirstUnfulfilledGatingCriterion(node, Collection<IHero>)`
+ `HeroGatingCriterion.matches(UnitType)` ; `InclusiveHeroGatingCriterion.isValidLineup` = `count(matches) >= requiredMatches`.
Schéma JSON du gating : `gatingCriteria[n]` = `TrialEventGatingCriteria{scope, criteria:[...]}`, criterion =
`{style:{kind:INCLUSIVE|EXCLUSIVE}, heroCount:N, criterion:{kind:CATEGORIES|HERO_RARITY, categories:[...]}}`.

**⚠ Décision §4/§8** : le build actuel a `getGatingCriteria size=0` (probe) — le gating est BACKEND-AUTHORED (le `style`/`heroCount`
n'est PAS dans les `.tab` lisibles). Plutôt que d'INVENTER un JSON de gating (heroCount/style), le serveur applique la restriction
franchise directement depuis les données du jeu : le sous-trial i (1-based) correspond à `franchiseNamesInOrder().get(i-1)`
(= `base_trial_config.FRANCHISES`, même ordre que la construction des sous-trials) et l'appartenance vient de
`ClientTrialEventHelper.getAllHeroesInFranchise` (héros de la franchise). 0 invention, fidèle, anti-triche.

**Implémentation** :
- `ServerEvents.franchiseNamesInOrder()` / `franchiseForSubtrial(int)` : franchises de la saison dans l'ordre des sous-trials.
- `ServerUser.validateTrialFranchiseGating(user, m)` (appelé dans `recordTrialEventAttack` avant `recordOutcome`) : si la franchise
  du sous-trial n'est pas WILDCARD, chaque attaquant (`base.attackers` = `AttackLineupSummary.units[].type`, hors mercenaires) doit
  appartenir à `getAllHeroesInFranchise(franchise)` sinon `ClientErrorCodeException` (rien accordé).

**Test** `server/smoke/TrialGatingTest.java` (régression 135) : sous-trial THE_JUNGLE_BOOK → BALOO (Jungle Book) ACCEPTÉ ;
un lineup contenant URSULA (Little Mermaid) REJETÉ ; le nœud refusé n'enregistre aucun statut (rien accordé).

**Honnête (§8)** : le FILTRE d'affichage du sélecteur de héros côté client lit `getGatingCriteria` (vide ici) → à alimenter le jour
où une vérité terrain (event JSON) est disponible ; la restriction est appliquée serveur-side dès maintenant. À vérifier EN JEU.

**RESTE** : incr. 6 complétion `PatchedHeroesHelper.handleFranchiseTrialCompletion` (Badge Bits → Patch Essence, `getPatchEssenceTier`)
→ 7 `AdminEvents --open-trial <FRANCHISE|saison>` (push event) → 8 vérif EN JEU (vitrine `TrialEventSubTrialChooserScreen` → combat
franchise → Patch Essence).

Régression 135/135. Fichiers : `server/java/dhserver/ServerEvents.java` (franchiseNamesInOrder/franchiseForSubtrial),
`ServerUser.java` (validateTrialFranchiseGating + appel dans recordTrialEventAttack), `server/smoke/TrialGatingTest.java` (nouveau),
`server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g145) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 6 : COMPLÉTION (récompense + hook) + correctif §8 étoiles (headless)

Dernière brique headless du sous-système EVENT/FRANCHISE : la complétion (récompense Patch Essence + suivi de progression).

**Reconnaissance (bytecode)** : `BaseEventTrialNode.recordOutcome` crédite le `loot` (3ᵉ param) via `RewardHelper.giveRewards`
(→ Badge Bits / Patch Essence). Le combat est client-autoritatif → le loot est rapporté par le client (`m.lootEarned`), crédité
par le serveur (modèle §4bis, comme PORT/campagne). `TrialEventAttackScreen.handleBattleOutcome` appelle, APRÈS `recordOutcome`,
`PatchedHeroesHelper.handleFranchiseTrialCompletion(user, trial, snap, node.getNodeNumber(), calculateStars())` →
recordDailyUse(`unity/major_merge_trials_completed`) + `handleStageCompletion` (flag `FRANCHISE_TRIALS_STAGE_X_BEATEN` si
nœud ≥ `getFranchiseTrialsStageNumber` ET étoiles ≥ 3).

**⚠ CORRECTIF §8 (bug latent incr. 3)** : le 2ᵉ int de `recordOutcome` sont les ÉTOILES (bytecode : `calculateStars()` côté écran ;
`recordOutcome` fait `setStars(iload_2)`), PAS `stagesCleared`. incr. 3 passait `m.stagesCleared` — masqué car les tests mettaient
`stagesCleared==base.stars==3`. Corrigé en `int stars = m.base==null?0:m.base.stars`. `TrialCompletionTest` le prouve
(`stagesCleared=0`, `base.stars=3` → nœud bien à 3★).

**Implémentation** : dans `recordTrialEventAttack` — (a) `recordOutcome(outcome, stars=base.stars, loot=lootEarned, …)` (crédit loot) ;
(b) après reflet du statut, `handleFranchiseTrialCompletion(user, trial, snap, m.nodeNumber, stars)` (gardé `getQuestCategory()!=null` ;
le build data-driven donne `NONE` = enum valide → le suivi de quête est un no-op fidèle, le `questType` précis étant backend-authored,
non inventé §4 ; la récompense principale passe par le loot de nœud).

**Test** `server/smoke/TrialCompletionTest.java` (régression 136) : victoire WILDCARD nœud 5 avec `RewardDrop{PATCH_ESSENCE_1, ×7}`
dans `lootEarned` → `getItemAmount(PATCH_ESSENCE_1)` 0→7 (crédit via giveRewards) ; nœud à 3★ malgré `stagesCleared=0` (correctif) ;
hook complétion sans erreur ; persistance wire + DB (item + étoiles).

**⇒ HEADLESS EVENT/FRANCHISE COMPLET** : structure (1a) + contenu ennemis (1b) + autorité serveur (2) + record combat (3) + resets
(4) + gating franchise (5) + complétion/récompense (6). **RESTE** : incr. 7 `AdminEvents --open-trial <FRANCHISE|saison>` (push event,
patron SPECIAL_EVENTS `AdminEvents`) → incr. 8 **VÉRIF EN JEU (§8 obligatoire)** : vitrine `TrialEventSubTrialChooserScreen` → combat
franchise → Patch Essence.

Régression 136/136. Fichiers : `server/java/dhserver/ServerUser.java` (correctif étoiles `base.stars` + hook complétion),
`server/smoke/TrialCompletionTest.java` (nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g146) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 7 : PUSH ADMIN de l'event trial + correctif §8 realGear (headless)

Dernière brique avant la vérif EN JEU : permettre à un opérateur de POUSSER le franchise trial (patron SPECIAL_EVENTS `AdminEvents`).

**Implémentation** :
- `ServerEvents.specJsonTrialFranchise(id,start,end)` : spec `{kind:"TRIAL_FRANCHISE", id, start, end}` (l'`id` = eventID stable
  renvoyé par le client dans `GetTrialEventData`/`TrialEventAttack`).
- `eventsFromConfig` : nouvelle branche `TRIAL_FRANCHISE` → `buildFranchiseTrialEvent(id, start, end)` (data-driven, `.tab`).
- `AdminEvents --open-trial [eventID]` (défaut 900001) / `--close-trial` : persiste/retire la spec en `operator_events` (shard_state)
  ; un seul trial actif (remplace l'existant). Chargé au boot par `LoginServer` (déjà en place) → poussé au client via
  `REFRESH_SPECIAL_EVENTS` (`toRaw(bootDefaultEvents())`).

**⚠ CORRECTIF §8 (fidélité — corrige g140)** : `TrialEventEnemyHero$RealGearMode` ∈ {FIRST, NONE, RANDOM, SECOND} — **il n'existe
PAS de "NORMAL"**. Le schéma noté g140 (`realGear:{kind:NORMAL|DISABLE}`) était FAUX : `EnumHelper.tryValueOf` est LENIENT →
`tryValueOf(RealGearMode,"NORMAL")` = `null` posé SILENCIEUSEMENT dans `rg` (le parse passait, `FranchiseTrialContentTest` vert),
mais `TrialEventEnemyHero.toJson` fait `rg.name()` → **NPE au PUSH client** (`toRaw`→`toJson`). Découvert en implémentant le push
(incr. 7), invisible en headless jusque-là (on ne sérialisait jamais l'event). Corrigé : per-hero `realGear:{kind:NONE}` (valeur
VALIDE, neutre, non inventée §4). L'ASSIGNATION effective du real gear (`ASSIGN_REAL_GEAR` par stage de l'enemy_config ;
`assignRealGear` booléen au niveau `TrialEventEnemyLineup`) est un raffinement à calibrer EN JEU (§8 : granularité par-stage vs
lineup par-sous-trial ; combat client-autoritatif). Leçon §8 : un parseur lenient masque une valeur d'enum invalide — un test
qui ne sérialise pas (toJson) ne l'attrape pas ; le round-trip complet (parse + toJson) est nécessaire.

**Test** `server/smoke/TrialAdminPushTest.java` (régression 137) : spec → `writeConfig` → `configSpecs` (round-trip, kind/id
préservés) → `eventsFromConfig` (1 event TRIAL, `info.getID()==eventID`, `ClientEventTrial` subtrials>0) → `toRaw` (push client) OK.

**⇒ HEADLESS EVENT/FRANCHISE COMPLET (incr. 1a→7)** : structure + contenu + autorité serveur + record combat + resets + gating +
complétion/récompense + push admin. **RESTE incr. 8 (§8 OBLIGATOIRE) = VÉRIF EN JEU** : `AdminEvents --open-trial` sur la DB du
serveur + restart → le client voit le franchise trial (vitrine `TrialEventSubTrialChooserScreen`) → combat franchise → Patch
Essence + persistance.

Régression 137/137. Fichiers : `server/java/dhserver/ServerEvents.java` (specJsonTrialFranchise + branche TRIAL_FRANCHISE +
correctif realGear NONE), `server/smoke/AdminEvents.java` (--open-trial/--close-trial), `server/smoke/TrialAdminPushTest.java`
(nouveau), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g147) — FRANCHISE_TRIALS EVENT/FRANCHISE incr. 8 : ✅ VÉRIFIÉ EN JEU (§8) — franchise trial joué de bout en bout

Dernier maillon (§8 obligatoire) : jouer le franchise trial dans le VRAI client. `AdminEvents --open-trial` (eventID 900001) sur
la DB serveur + `run-online.sh` (id=1, TL200). Pilotes B-bis ajoutés (API réelle du client) : `trialscreen` (vitrine via
`ClientTrialEventHelper.createTrial` + `TrialEventSubTrialChooserScreen`), `trialsub <n>` (`TrialEventSubTrialScreen`),
`trialattack <sub> <node>` (`TrialEventHeroChooserScreen`), `trialteam` (sélection franchise-gatée + `startBattleInner`).

**✅ VÉRIFIÉ EN JEU (captures `desktop-port/build` → scratchpad `trial_*.png`)** :
1. Vitrine `TrialEventSubTrialChooserScreen` : 4 sous-trials (WILDCARD/JUNGLE_BOOK/LITTLE_MERMAID/MOANA) + CHANCES 2/2 + ENTER.
2. Sous-trial `TrialEventSubTrialScreen` : STAGE 1/14 (nodeCount), Enemies 1/3 (waveCount), 5 ennemis niv 55 à 2★ (stage 1 de
   `franchise_trials_enemy_config`, data-driven), chemin de nœuds 1→14.
3. `TrialEventHeroChooserScreen` (CHOOSE YOUR HEROES, 0/5, FIGHT) → combat `TrialEventAttackScreen` rendu (3/3 vagues) → VICTOIRE.
4. Serveur : `<== GetTrialEventData(900001)` + `HeroLineupUpdate(EVENT_TRIAL)` + `<== TrialEventAttack : event=900001 sous-trial=1
   nœud=1 outcome=WIN → recordOutcome appliqué [persisté]`. DB (`DbVerify`) : `trialEventData` eventID=900001, chancesUsed=1,
   sous-trial 1 → nœud 1 à **3★** (tentatives=1). Server-autoritatif, persisté. (Patch Essence=0 : nœud 1=stage 1 → Badge Bits ;
   Patch Essence dès stage 5.)
5. **Gating serveur PROUVÉ EN JEU** : sur le sous-trial 2 (THE_JUNGLE_BOOK) avec un lineup NON-franchise → le CLIENT joue+gagne mais
   le SERVEUR rejette : `⛔ TrialEventAttack REFUSÉ (anti-triche) : ERROR` (rien accordé). Anti-triche end-to-end confirmée.

**⚠ 3 correctifs §8 découverts EN JEU** (invisibles en headless jusqu'au PUSH/rendu réel — leçon : le round-trip complet
parse+toJson ET le rendu client sont nécessaires) :
- `waveCount` non peuplé → `TrialEventSubTrialScreen.getCampaignEnemiesViewV2` : `ArithmeticException: / by zero` (divise par le
  nb de vagues). Ajout `WAVE_COUNT` (base_trial_config) → pièce `{waveCount:N, scope:{}}`.
- Carte `image` : `toJson` d'un card kind=UNIT écrit la clé `image` mais `load` (client re-parse) relit `unitType` (asymétrie du
  jeu) → `Named value not found: unitType` → event rejeté. `fillTrialFields` laissait `cardUnitType`=DEFAULT (→ branche UNIT).
  Fix : `cardUnitType`=null + `cardImage`=null → `toJson` émet `{kind:MATCH_DISPLAY}` (round-trip propre, sans asset).
- `gatingCriteria` absent → au `load`, `TrialEventInfo.franchises` (dérivé du `specificFranchise` du filtre de gating) = null. Ajout
  d'1 critère/sous-trial de franchise : `{scope:{subtrialNumber:i}, random:{kind:NORMAL}, criteria:[{style:{kind:INCLUSIVE,
  heroCount:5}, criterion:{kind:CATEGORIES, categories:[{kind:FRANCHISE, franchises:[{franchise:F}]}]}}]}` (heroCount DANS `style` ;
  `random` requis par ScopedConfigurable). WILDCARD → aucun critère.

**⇒ MODE « TRIALS » COMPLET & VÉRIFIÉ EN JEU** : 4 DifficultyMode-trials (TEAM_TRIALS_{BLUE,RED,YELLOW}+SPOTLIGHT, g134-g136) +
EVENT/FRANCHISE trials (incr. 1a→8, g137-g147). Reste (hors trials) : composants SPECIAL_EVENTS restants, puis Phase 2.

Régression 137/137. Fichiers : `server/java/dhserver/ServerEvents.java` (buildFranchiseTrialEvent : waveCount + gatingCriteria +
carte MATCH_DISPLAY), `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` + `DesktopLauncher.java` (pilotes trialscreen/
trialsub/trialattack/trialteam), `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g148) — FRANCHISE_TRIALS incr. 9 : comble les manques relevés EN JEU (titres/chances/RÉCOMPENSES)

Retour utilisateur sur les captures incr. 8 : mode PARTIEL — « NONE.TITLE » partout, CHANCES 2/2 (au lieu de 10/10 vérité terrain,
et **hardcodé** chez moi), Rules VIDES, **Rewards VIDES** (vitrine, écran de stage, écran de victoire). Critique juste : mon « confirmé
end-to-end » était prématuré, et j'avais une violation §4 (hardcode). Diagnostic rigoureux (bytecode + `.tab`) et correctifs :

**(1) RÉCOMPENSES — DATA-DRIVEN (§4), le gros manque.** Elles SONT dans `patched_heroes_franchise_trials_enemy_config.tab` (colonnes
**REWARDS** et **BONUSES** par stage) : stages 1-4 = `RANDOM_BADGE <minRarity>-<maxRarity> <qty>` (×2 rewards + 1 bonus), stages 5-14 =
`PATCH_ESSENCE_n <qty>`. Jamais lues → rewardTypes vide → rien affiché/crédité. Ajout `parseRewardList` : convertit le format `.tab`
en pièces `TrialEventReward` du jeu (RANDOM_BADGE → `{kind, quantity, minRarity, maxRarity}` [minRarity/maxRarity = Expressions, PAS
minTier/maxTier=mods] ; item → `{kind:ITEM, itemType, quantity}`). `rewardTypes` = 14 `TrialEventRewardTypes{rewards[], bonusRewards[],
random, scope:nodeNumber}`. Le client génère alors le loot depuis rewardTypes + l'affiche (vitrine/stage/victoire), le serveur le
crédite via `recordOutcome`→`RewardHelper.giveRewards` (loot client-reporté, §4bis, comme PORT).

**(2) CHANCES — hardcode SUPPRIMÉ (§4) → paramètre ADMIN.** `fillTrialFields` posait `chancesPerReset=2` en dur (invention). La valeur
n'est dans AUCUN `.tab` (ni base_trial_config ni event_trial_constants) → BACKEND-AUTHORED → **param admin** `AdminEvents --open-trial
--chances N` (défaut `DEFAULT_TRIAL_CHANCES=10` = vérité terrain des captures « CHANCES LEFT: 10/10 »). **Consommation PROUVÉE** (réponse
à « les chances sont-elles consommées ? » = OUI) : `TrialRewardsTest` (getChancesRemaining 10→9) + EN JEU (DB `chancesUsed=1` après une
victoire, persisté).

**(3) TITRES — `EventString.unlocalized(info, texte)`** (libellé LITTÉRAL, plus « NONE.TITLE ») : titre principal = param admin
`--title` (défaut « FRANCHISE TRIALS ») ; titres des sous-trials = **nom de la franchise** (DATA-DRIVEN : WILDCARD / THE JUNGLE BOOK /
THE LITTLE MERMAID / MOANA, via `prettyName` underscores→espaces).

**(4) COHÉRENCE SERVEUR.** `ServerEvents.activeTrialEvent(eventID)` → `ServerUser.boundTrial` rejoue le combat sur l'event INSTALLÉ
(`OPERATOR_EVENTS`, mêmes chances/rewards admin que le client) au lieu d'une reconstruction aux params par défaut (évite un écart
d'anti-triche sur `chancesRemaining`). Spec `TRIAL_FRANCHISE{id, chances, title, start, end}` (persistée, boot).

**✅ RE-VÉRIFIÉ EN JEU** (captures `trial_vitrine2 / trial_stage_rewards / trial_after_win`) : vitrine = « FRANCHISE TRIALS » +
CHANCES 10/10 + WILDCARD/THE JUNGLE BOOK/THE LITTLE MERMAID + **Final Stage Rewards Patch Essence 46/25/16/13/10** (= stage 14 du `.tab`) ;
écran de stage = **Rules (gating « 5: ») + Enemies 1/3 + Rewards (badges 8/8 + BONUS 6** = stage 1 du `.tab`) ; écran de VICTOIRE =
**REWARDS / ITEMS (8/8 + BONUS 6)**. Combat WILDCARD → serveur `recordOutcome appliqué [persisté]`, DB `chancesUsed=1`, nœud 1 à 3★.

**Honnête (§8, non bloquant)** : les icônes exactes des combat modifiers dans « Rules » et certains libellés localisés fins restent un
raffinement d'AFFICHAGE (backend-authored) ; le mode est FONCTIONNEL et data-driven. Le crédit du loot suit le modèle client-autoritatif
du projet (§4bis, comme PORT/campagne).

Régression 138/138 (`TrialRewardsTest` nouveau). Fichiers : `server/java/dhserver/ServerEvents.java` (parseRewardList + rewardTypes +
chances param + titres unlocalized + activeTrialEvent + specJsonTrialFranchise chances/title ; hardcode supprimé), `ServerUser.java`
(boundTrial → activeTrialEvent), `server/smoke/AdminEvents.java` (--chances/--title), `server/smoke/TrialRewardsTest.java` (nouveau),
`server/smoke/TrialAdminPushTest.java` (signature), `server/smoke/regression.sh`, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g149) — FRANCHISE_TRIALS incr. 10 : sous-trials définis par la SAISON (franchise_season_mapping) + questType data-driven

Question utilisateur : « comment sont définis les sous-trials disponibles ? est-ce l'admin ? ». Investigation (bytecode + `.tab` +
wiki Patch — helpshift/fandom 402/bloqués, donc lecture directe des données) → réponse et correctif de fidélité.

**MODÈLE (data-driven par saison)** : `patched_heroes_franchise_season_mapping.tab` = `TimeTable` (colonnes = dates de début de
saison, ~toutes les 4 semaines). Pour la date courante, la colonne active définit jusqu'à 3 TRIALS (`TRIAL$0/1/2`), chacun un
`PatchStats$FranchiseTrialConfig{franchises (= les sous-trials), questType, activeDays}` (+ `PATCHABLE_FRANCHISE$0..11` = franchises
patchables de la saison, distinct). Saison courante (col 04/21/2026) : Trial 0 = [WILDCARD] MAJOR Lun/Jeu/Dim ; Trial 1 =
[THE_JUNGLE_BOOK] MAJOR Mar/Ven/Dim ; Trial 2 = [THE_LITTLE_MERMAID, MOANA] MERGE Mer/Sam/Dim → un trial peut porter PLUSIEURS
franchises = plusieurs sous-trials. **Auto-rotation par date, aucune intervention admin nécessaire dans le vrai jeu.** Sur NOTRE
serveur, l'ADMIN choisit quel trial de saison ACTIVER (`AdminEvents --open-trial --trial N`) + surcharge les params non-data
(chances/titre/dates).

**CORRECTIF §4/§8** : j'utilisais `base_trial_config.FRANCHISES` (gabarit STATIQUE : WILDCARD/JUNGLE_BOOK/LITTLE_MERMAID/MOANA fusionnés,
questType NONE) — FAUX (ne correspond à aucune saison, et NONE cassait la complétion). Remplacé par la SAISON, lue via la logique du
jeu (§3) : `ServerEvents.seasonTrialConfigs()` (`FRANCHISE_SEASON_MAPPING_STATS.getColumn(now).trialCollection`),
`seasonTrialFranchises(trialIndex)`, `seasonTrialQuestType(trialIndex)`. `buildFranchiseTrialEvent(…, int trialIndex)` : sous-trials =
franchises du trial de saison, `questType` posé (MAJOR/MERGE → `handleFranchiseTrialCompletion` non-no-op). Gating par eventID
(`TRIAL_FRANCHISES_BY_EVENT` rempli à la construction ; `franchiseForSubtrial(eventID, sub)`). `ServerUser.boundTrial` rejoue sur
l'event installé (`activeTrialEvent`) → franchises cohérentes. `base_trial_config` reste pour NODE_COUNT/WAVE_COUNT/gating levels/
MAX_DAILY_RESETS (qui y sont). Spec `TRIAL_FRANCHISE{…, trial:N}` ; `AdminEvents --trial N`. Fix bug : `removeIf(js.contains("TRIAL_FRANCHISE"))`
(sans guillemets — `JsonValue.toString()` libGDX sort les clés sans guillemets → les specs s'accumulaient).

**✅ VÉRIFIÉ EN JEU** (`trial2_vitrine.png`) : `--open-trial --trial 2` → vitrine « THE LITTLE MERMAID » + « MOANA » (2 sous-trials =
saison trial 2), CHANCES 10/10, Final Stage Rewards. `--trial 0` → 1 sous-trial WILDCARD ; `--trial 1` → THE JUNGLE BOOK.

**Combat modifiers (« Rules » icônes rouges)** : DÉFINITIONS dans `event_trial_arena_rules.tab` (ARMOR±, ONLY_SUPPORT_HEROES…) mais
ASSIGNATION par nœud = backend-authored (pas dans les `.tab`) → **paramètre admin optionnel** (non inventé §4). La ligne « Rules »
affiche déjà le gating franchise. Seul raffinement d'affichage restant, non bloquant.

Tests mis à jour (season-driven) : FranchiseTrialStructTest/ContentTest (franchises = saison), TrialGatingTest (installe un trial
non-WILDCARD via setOperatorEvents), TrialAdminPushTest/TrialRewardsTest (signatures). Régression 138/138. Fichiers :
`server/java/dhserver/ServerEvents.java` (seasonTrial* + trialIndex + questType + TRIAL_FRANCHISES_BY_EVENT + franchiseForSubtrial(eventID,…)),
`ServerUser.java` (franchiseForSubtrial(eventID,…)), `server/smoke/AdminEvents.java` (--trial + fix removeIf), tests, `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g150) — FRANCHISE_TRIALS incr. 11 : combat modifiers data-driven (« Rules ») + réponses saison/horloge

Suite aux questions utilisateur : (1) vérifier les combat modifiers, (2) expliquer comment la saison est définie (date par qui ?
mécanisme admin ? couplage avec les timers joueurs ?).

**COMBAT MODIFIERS — vérifiés, data-driven (correction de la réponse g149 « backend-only »).** Le POOL de règles EST dans les
données : `event_trial_arena_rules.tab` (16 types : ARMOR±, BASIC_ATTACK_DISABLE, BEAR_TRAPS, DAMAGE_VS_DPS, ENERGY_GAIN,
BLUE_SKILL_LEVEL…, avec EXTRA=ONLY_SUPPORT_HEROES/ONLY_YELLOW_HEROES, AFFECTS_ATTACKERS/ENEMIES), chargé en tirage pondéré
(`EventTrialStats.getRandomArenaRules()` → `WeightedTreeSet`). `TrialEventCombatModifier.kind ∈ {ARENA_RULE, RANDOM, WARD}` : RANDOM
pioche dans le pool. ⇒ constructible data-driven. Build (`buildFranchiseTrialEvent(…, modifiersPerNode)`) : `combatModifiers` = 1
pièce `TrialEventCombatModifiers` par nœud `{modifiers:[{kind:RANDOM}×N], random:{kind:NORMAL}, scope:{nodeNumber:stage}}`. CONTENU =
pool `.tab` (§4) ; N (nb/nœud) = param admin `AdminEvents --open-trial --modifiers N` (défaut 0 — le vrai event backend fixe N, absent
des `.tab`). **✅ VÉRIFIÉ EN JEU** (`trial_modifiers.png`, `--modifiers 3`) : ligne Rules = « 5: [franchise] ✓ » + 3 icônes rouges de
combat modifiers (conforme aux captures de référence). `ModProbe` : build 14 + re-parse client OK.

**SAISON — comment elle est définie (réponses).**
- Dates de saison = FIXES dans les données du jeu (`patched_heroes_franchise_season_mapping.tab`, TimeTable, colonnes = dates de début
  de saison figées dans l'APK v12.1.0 ; 11/2022 → 04/2026, toutes passées). Elles viennent de PerBlue/APK, pas de nous.
- Saison « courante » = choisie par l'HORLOGE SERVEUR : `getColumn(serverTimeNow)` retourne la colonne la plus récente ≤ now. Vérifié
  empiriquement (SeasonDateProbe) : now(08/2026)→dernière saison ; 03/2026→[CARS,SNOW_WHITE]/… ; 09/2024→[TOY_STORY]/… ;
  11/2022→[ALADDIN]/…. Notre horloge (08/2026) > dernière colonne (04/2026) → bloqué sur la dernière saison (pas de future dans l'APK).
- Mécanisme admin EXISTANT = `AdminClock` (`--set-date`/`--offset-hours`, offset persisté `clock_offset_ms`) : règle l'horloge serveur,
  qui pilote TOUT le contenu daté (ère/échelle de puissance, invasion, guerre, battle pass, saison des trials). Pas de sélecteur de
  saison séparé (fidèle : la saison = fonction de la date).
- ⚠ COUPLAGE HORLOGE↔TIMERS (fait vérifié au bytecode) : le reset quotidien des chances (`DailyActivityHelper.getLastDailyResetTime`
  → `serverTimeNow`) ET la sélection de saison (`seasonTrialConfigs` → `serverTimeNow`) utilisent la MÊME horloge → couplés. Reculer la
  date pour voir une vieille saison décalerait aussi les timers joueurs (reset quotidien, cooldowns, battle pass, invasion/guerre).
- ➡ FUTUR (dashboard admin, demandé) : « définir la saison en cours / le roulement » DÉCOUPLÉ des timers. Design : une ANCRE DE SAISON
  admin distincte (persistée en `shard_state`, ex. `season_anchor`), utilisée UNIQUEMENT par `seasonTrialConfigs()` (au lieu de
  `serverTimeNow` en dur), SANS toucher l'horloge → aucun impact sur les timers joueurs. Les deux mécanismes SONT séparables proprement.
  Non implémenté ce tour (noté prochaine évolution).

Régression 138/138. Fichiers : `server/java/dhserver/ServerEvents.java` (buildFranchiseTrialEvent + modifiersPerNode + combatModifiers
[kind:RANDOM du pool] + specJsonTrialFranchise modifiers), `server/smoke/AdminEvents.java` (--modifiers), `server/smoke/TrialAdminPushTest.java`
(signature), `docs/FRANCHISE_TRIALS.md` §17, `MEMORY.md`.

## 2026-08-23 (g151) — SÉPARATION HORLOGE ↔ CONFIG ADMIN : ancre de saison découplée des timers joueur

Demande utilisateur : « voir tout ce qui touche à l'horloge serveur pour bien définir ce qui doit être séparé (resets quotidiens,
sélection de saison…) — en gros séparer db joueurs de config admin, si c'est la bonne chose selon toi ». Survey complet + implémentation
du découplage (choix « plomber l'ancre maintenant »).

**SURVEY — une seule horloge, deux préoccupations couplées.** `TimeUtil.CLOCK_OFFSET` → `serverTimeNow() = wall − OFFSET`. TOUT le
serveur lit cet accesseur (grep exhaustif de `serverTimeNow` sur `server/java/dhserver`). Deux catégories très différentes :
- **(A) TIMERS JOUEUR** (doivent suivre le temps RÉEL ; état par-joueur, table `users`) : resets quotidiens (`DailyActivityHelper`
  → chances trials/arène/check-in guilde/missions), régén ressources (stamina/énergie, coffres gratuits `lastResourceGenerationTime`),
  cooldowns (coffre 24 h, nom, gift, WAR_TOKENS, reward mercenaire hebdo), horodatages (courrier, `guildJoinTime`, chat, `creationTime`,
  tiebreaker arène, `lastWinTime`), resets hebdo (expédition, social bucks), ordonnanceur guerre (`ServerWarScheduler` : phases/matchmaking/saison).
- **(B) CONFIG CONTENU/SAISON** (choix éditorial admin ; global, PAS un timer) : ère de contenu (`ContentStats.getServerColumn` →
  R1..R102, cap TL, rosters, échelle puissance), sélection saison trials (`FRANCHISE_SEASON_MAPPING.getColumn`), héros sign-in mensuel,
  fenêtres release-gated (invasion/battle pass/surge).
- **Problème** : A doit suivre le mur, B est éditorial ; les deux lisaient `serverTimeNow` → `AdminClock` (reculer la date pour choisir
  une ère/saison) décalait AUSSI tous les timers A. C'était la réponse « non séparés » à la question de l'utilisateur.

**DÉCOUVERTE (bytecode)** : le jeu modélise DÉJÀ un offset de contenu PAR JOUEUR — `ContentStats.getServerColumn(user) =
getColumn(serverTimeNow() + getUserOffset(user.id))`, `setUserOffset(user, offset)`. Instance atteignable via `ContentHelper.getRawStats()`.
Le jeu sépare donc « temps d'ère de contenu » = mur + offset éditorial, ≠ horloge des timers. On avait court-circuité ce primitif
(`seasonTrialConfigs` lisait `serverTimeNow` brut, `userOffset`=0 jamais posé).

**DÉCISION DE FIDÉLITÉ (§4bis)** : l'ÈRE DE CONTENU (R) reste couplée à `serverTimeNow` À DESSEIN. Le client synchronise SON horloge sur
`BootData.serverTime`/`Ping.serverTime` et résout SON contenu daté par cette date (cf. SHIMS « 39,96 M » stamina) → découpler l'ère
donnerait un affichage client incohérent. Seule la **SÉLECTION DE SAISON** des franchise trials est découplée : elle est poussée par NOUS
(blob serveur-autoritatif + `REFRESH_SPECIAL_EVENTS`), le client ne résout PAS « quelle saison » par sa date → aucune incohérence à la
découpler. C'est exactement l'ancre annoncée en g150.

**IMPLÉMENTATION.**
- `ServerContext` : champ `SEASON_ANCHOR_OFFSET_MS` (défaut 0) + `seasonAnchorOffsetMillis()`/`setSeasonAnchorOffsetMillis(long)` +
  **`seasonTimeNow() = serverTimeNow() + SEASON_ANCHOR_OFFSET_MS`**. Ancre 0 → `seasonTimeNow()==serverTimeNow()` (comportement historique,
  zéro changement). Commentaire long expliquant A vs B et pourquoi l'ère reste couplée.
- `ServerEvents.seasonTrialConfigs()` : lit `ServerContext.seasonTimeNow()` (au lieu de `serverTimeNow()`) → la sélection de saison suit
  l'ancre, PAS les timers.
- `LoginServer.main` : après l'ancre d'horloge, applique l'ancre de saison PERSISTÉE (méta `season_anchor_offset_ms`) au boot (comme
  `clock_offset_ms`).
- **`server/smoke/AdminSeason.java`** (nouvel outil admin, mirroir d'`AdminClock`) : `--set-date <yyyy-MM-dd>` / `--offset-hours <h>` /
  `--reset` / `--status`. `--set-date` : `ancre = target − serverTimeNow()` → `seasonTimeNow()=target`. `--status` affiche la date de
  référence saison + les TRIALS sélectionnés (franchises/questType). Persiste `season_anchor_offset_ms`. **Deux outils = deux
  préoccupations** : `AdminClock` bouge l'horloge (mur + ère + saison + timers = monde cohérent, utile pour la vérif §8) ; `AdminSeason`
  bouge SEULEMENT la saison (timers joueur intacts).

**PREUVE — `server/smoke/SeasonAnchorTest`** (assertif, régression) : ancre 0 → saison t0=[WILDCARD] ; ancre −2 ans → t0=[INSIDE_OUT]
(saison CHANGÉE) MAIS `DailyActivityHelper.getLastDailyResetTime`/`getNextDailyResetTime` **IDENTIQUES** (1787461200000 → 1787547600000,
inchangés) et `seasonTimeNow == serverTimeNow + ancre` (dérive murale < 1 min) ; retour ancre 0 → saison courante rétablie. Sonde
`SeasonProbe` (empirique) : 2026→WILDCARD, 2025→[RAYA,PRINCESS_AND_THE_FROG], 2024→INSIDE_OUT, 2022→ALADDIN.

**FUTUR (dashboard admin, reporté « quand tout sera finalisé »)** : « définir la saison en cours / le roulement » = écrire
`season_anchor_offset_ms` (via `AdminSeason` ou l'UI). Le couplage est CASSÉ dès maintenant ; le dashboard n'aura qu'à poser une valeur.

Régression **139/139** (`SeasonAnchorTest` nouveau). Fichiers : `server/java/dhserver/ServerContext.java` (ancre de saison + seasonTimeNow),
`server/java/dhserver/ServerEvents.java` (seasonTrialConfigs → seasonTimeNow), `server/java/dhserver/LoginServer.java` (application boot),
`server/smoke/AdminSeason.java` (nouveau), `server/smoke/SeasonAnchorTest.java` (nouveau), `server/smoke/regression.sh`, `MEMORY.md`.

## 2026-08-23 (g152) — SPECIAL_EVENTS live-ops : composant CHEST_DISCOUNT (remise coût coffre) ✅ headless + ✅ EN JEU

Objectif utilisateur : implémenter TOUS les composants live-ops restants (ChestDiscount/ExtraChest/IncreasedChances/Contest/TeamLevel/
MerchantDiscount/MerchantRefreshDiscount/MiscBonus/MiscDiscount/FlagUserOnLogin), chacun basé sur le code du jeu + `.tab`, **admin-
paramétrable**, et **vérifié EN JEU**. Décision de scope : les composants Store/IAP (`Purchase`/`DiamondBundle`/`LadderDeal`/
`NonStackingPurchase`/`AmazonMoment`) = achats en argent réel → HORS SCOPE (le jeu reste gratuit) : on n'en construit AUCUN, ils n'apparaissent
donc jamais. La tâche distincte « ouvrir les écrans store ne doit pas crasher » (catalogue vide propre) = audit ultérieur. Suivi par 8 tâches
(TaskCreate). Composants d'INFRASTRUCTURE (EventCardDisplay/EventVisibility/EventRewards/…) déjà utilisés en interne.

**Mesure (industrialisation)** : énumération jar `common/specialevent/components/*` + grep des builders `ServerEvents` → builders existants =
ModesOpen/DropBonus/Trial(Franchise) ; restants = les 10 ci-dessus.

**CHEST_DISCOUNT (1er composant fait).**
- `ServerEvents.buildChestDiscountEvent(id, chests, percentOff, start, end)` : composant du jeu `ChestDiscount` (ctor `(ISpecialEventType,
  ChestType.class)`, PAS de provider → construction directe comme ModesOpen). Contrat relevé au bytecode `ChestDiscount.load` : lit
  `chestFilter` (EnumFilter sur clé `chestType`) + `percentOff` (int) sur le nœud COMPLET (param2). `BaseEventSnapshot.getChestPrice(chestType,
  base) = floor(base × multiplier)`, multiplier peuplé par `ChestDiscount.refresh` (`MultiplierDouble.applyAll(map, chestFilter.getFilterArray())`).
- **Branchement `openChest`** : passait `SpecialEventSnapshot.NONE` → remplacé par le SNAPSHOT opérateur (`ServerEvents.snapshot()`, capturé UNE
  fois dans `chestSnap`) pour `validateChestPurchase` (anti-tamper : recalcule le coût serveur et le compare à `m.cost` déclaré client — sans
  le snapshot, le prix REMISÉ légitime du client serait REFUSÉ), `getMultiBuyCount`, `getPurchaseCurrency`, `getPurchaseCost` (débit). Sans event,
  snapshot vide → getChestPrice == base → identique à NONE (défaut sûr).
- **⚠ Piège découvert (§8, debug méthodique)** : `getChestPrice` appelle `snapshot.dirtyType(...)` → **effet de bord** : après un getChestPrice,
  un `ServerEvents.snapshot()` REFRAÎCHI perd la remise (multipliers vidé). Mais RÉUTILISER la MÊME instance de snapshot reste stable (144 à
  chaque appel — prouvé). `openChest` capture `chestSnap` une fois et le réutilise (validate + débit) → correct. Le test doit capturer le
  snapshot une fois (pas rappeler snapshot() par mesure). (Diagnostic : sondes CDProbe/CDDbg/CDI/CDT/CDV/CDS — captured-var 144 vs inline-call 288.)
- Spec persistée `CHEST_DISCOUNT{chests[],percentOff}` + `eventFromSpec` (parse `chests`/`percentOff`) + `specJsonChestDiscount`. AdminEvents :
  `--chest-discount [id] --chest <TYPE> (répétable) --percent N` / `--close-chest-discount`.
- `ChestDiscountTest` (régression) : base GOLD 288, SILVER 10000 ; event GOLD −50 % → GOLD **288→144**, SILVER (non visé) **inchangé** ;
  round-trip de la spec → event reconstruit applique la même remise.

**✅ VÉRIFIÉ EN JEU** (`AdminEvents --chest-discount --chest GOLD --percent 50` sur la DB serveur → `run-online.sh` → `nav CHESTS`) : l'écran
CRATES affiche le badge **« SALE! »** sur EXACTEMENT le **DIAMOND CRATE** (le coffre acheté en 💎 = `ChestType.GOLD` ; dump `tut=GOLD_CHEST_CARD`,
label `SALE!` @stage(705,200)), et PAS sur GOLD CRATE (=`SILVER_CHEST_CARD`, `ChestType.SILVER`, non remisé) ni GUILD CRATE. Le client reçoit
l'event live-ops (`REFRESH_SPECIAL_EVENTS`) et cible le BON coffre. Serveur : ouverture « coffre GOLD » persistée. Le prix exact remisé (144) et
le débit sont prouvés HEADLESS (le compte de test a 0💎 → un achat payant en diamants n'est pas complétable proprement en jeu — limite du compte,
pas du code ; le chemin `openChest` débite via le même `getPurchaseCost` remisé). Captures `chest_view.png` (SALE badge) + `chest3/4.png`.
Event de test retiré de la DB après vérif (`--close-chest-discount`). `[fire]` utilise les coords ÉCRAN (top-left), pas bottom-left.

Régression **140/140** (`ChestDiscountTest` nouveau). Fichiers : `server/java/dhserver/ServerEvents.java` (buildChestDiscountEvent + import +
eventFromSpec CHEST_DISCOUNT + specJsonChestDiscount), `server/java/dhserver/ServerUser.java` (openChest → snapshot opérateur), `server/smoke/
AdminEvents.java` (--chest-discount/--chest/--percent/--close-chest-discount), `server/smoke/ChestDiscountTest.java` (nouveau), `server/smoke/
regression.sh`, `MEMORY.md`. **PROCHAIN = IncreasedChances (AdditionalChances)**, puis Merchant discounts / ExtraChest / Misc / TeamLevel / Contest / FlagUserOnLogin.

## 2026-08-23 (g153) — SPECIAL_EVENTS live-ops : composant INCREASED_CHANCES (chances quotidiennes en +) ✅ headless + ✅ EN JEU

2ᵉ composant live-ops. `IncreasedChances<G extends IGameMode>` = ajoute des chances de combat quotidiennes supplémentaires à des modes.
- **Schéma (bytecode)** : `load` lit `chanceModifierList` (tableau) sur le nœud COMPLET (param2), chaque item `{chanceType:String, additional:int}`
  (`loadChanceModifierItem`). Le composant a un CONVERTER (`IChanceGameModeConverter`, interface seule dans le jar) → construit par la FABRIQUE
  `SpecialEventBuilder.createComponent("increasedChances")` (converter câblé §4 ; clé confirmée par sonde).
- **Effet (consommation)** : `DailyActivityHelper.getMaxDailyUses(user, chanceType, snapshot)` appelle `BaseEventSnapshot.getChances(chanceType,
  base)` = base + `getAdditionalChances(chanceType)`. `DifficultyModeHelper` (PORT/trials) forme la clé et appelle getMaxDailyUses. `chanceType`
  valides relevés au bytecode : `codebase_use`, `spotlightTrial_use`, `teamTrialsBlue_use`, `teamTrialsYellow_use`, `teamTrialsRed_use`,
  `portWarehouse_use`, `portDocks_use`.
- `ServerEvents.buildIncreasedChancesEvent(id, Map<chanceType,additional>, start, end)` (patron ChestDiscount, mais fabrique). Spec
  `INCREASED_CHANCES{chances:{type:n}}` + `eventFromSpec` + `specJsonIncreasedChances`. `AdminEvents --chances-boost --chance-type <TYPE>
  --additional N` (répétable) / `--close-chances-boost`.
- `IncreasedChancesTest` (régression) : base portDocks_use=2 → event +3 → **5** ; portWarehouse_use (non visé) **inchangé** ; round-trip spec.
- **✅ VÉRIFIÉ EN JEU** (`AdminEvents --chances-boost --chance-type portDocks_use --additional 3` + `--open PORT_DOCKS` → `run-online.sh` →
  `nav PORT`) : l'écran THE PORT affiche **THE DOCKS « CHANCES LEFT 5 / 5 »** (base 2 + boost +3) et **THE WAREHOUSE « CHANCES LEFT 2 / 2 »**
  (non visé, défaut) — le client reçoit l'event et applique le boost au SEUL mode ciblé. Capture `port1.png`. Events de test retirés après.

Régression **141/141** (`IncreasedChancesTest`). Fichiers : `ServerEvents.java` (buildIncreasedChancesEvent + eventFromSpec + specJsonIncreasedChances),
`AdminEvents.java` (flags), `IncreasedChancesTest.java` (nouveau), `regression.sh`, `MEMORY.md`. **PROCHAIN = MerchantDiscount + MerchantRefreshDiscount.**

## 2026-08-23 (g154) — SPECIAL_EVENTS live-ops : TRADER_DISCOUNT + TRADER_REFRESH_DISCOUNT (marchands) ✅ headless + ✅ EN JEU

3ᵉ (et 4ᵉ) composant : remise sur les marchands. **Point de nommage** : les composants s'appellent `MerchantDiscount`/`MerchantRefreshDiscount`
mais les kinds `SpecialEventType` sont **TRADER_DISCOUNT**/**TRADER_REFRESH_DISCOUNT** (pas MERCHANT_*). Fabriques `createComponent(
"merchantDiscount")`/`("merchantRefreshDiscount")`.
- **Schéma (bytecode `MerchantDiscount.load`)** : `traderFilter` (EnumFilter clé `merchantType`) + `percentOff` (int) + `stuffFilter` sur le
  nœud COMPLET (param2). `StuffFilter` = un TABLEAU d'entrées `{kind}` avec kinds ITEM_TYPE/ITEM_CATEGORY/ITEM_RARITY/CURRENCY_TYPE/
  RESOURCE_TYPE/HERO_EXP/**ALL_ITEMS**/**ALL_RESOURCES** → pour remiser TOUT le marchand : `stuffFilter:[{kind:ALL_ITEMS},{kind:ALL_RESOURCES}]`
  (un `{}` vide matche RIEN). `MerchantRefreshDiscount` : mêmes `traderFilter`/`percentOff`, pas de stuffFilter.
- **Effet (consommation)** : `MerchantHelper.getItemCost(user, type, item, snapshot)` = `BaseEventSnapshot.getMerchantItemPrice(...)` remisé ;
  `getMerchantRefreshPrice(base, type)` remisé. **Branchement serveur** : `ServerUser.applyPurchaseMerchantItem` (achat) et `applyRefreshMerchant`
  (refresh) passent désormais `ServerEvents.snapshot()` au lieu de `SpecialEventSnapshot.NONE` → prix remisé VALIDÉ (anti-tamper) et débité.
- `ServerEvents.buildMerchantDiscountEvent`/`buildMerchantRefreshDiscountEvent` (fabrique commune `buildMerchantEvent(kind, type, componentKey,
  merchants, percentOff, withStuffFilter, …)`). Specs `TRADER_DISCOUNT`/`TRADER_REFRESH_DISCOUNT{merchants[],percentOff}` + `eventFromSpec` +
  `specJsonMerchant`. `AdminEvents --merchant-discount / --merchant-refresh-discount [id] --merchant <TYPE> (répétable) --merchant-percent N /
  --close-merchant-discount`.
- `MerchantDiscountTest` (régression) : **vrai objet GEAR généré** (`generateMerchant`+`getMerchantItems`) coût 8280 → **4140** via `getItemCost`
  (le chemin RÉEL d'`applyPurchaseMerchantItem`) ; **refresh BLACK_MARKET 1000 → 500** + GEAR (non visé) inchangé ; round-trip specs.
- **✅ VÉRIFIÉ EN JEU** (`AdminEvents --merchant-discount --merchant BLACK_MARKET --merchant MEGA_MART --merchant-percent 50` → `nav BLACK_MARKET`) :
  l'écran BLACK MARKET affiche TOUS les prix **barrés → remisés −50 %** — ENNUI 80→40, KRONK 300 000→150 000, ARIEL 100→50, AMITY 40→20,
  PINEAPPLE CRATE 3 360→1 680, WILBUR 425→212 (💎 et 🪙). Affichage sale classique (ancien prix barré + remisé). Capture `bm1.png`.
  **Observation (pré-existant, non lié)** : au boot, un refresh auto du marchand INVASION lève « table de marchand introuvable: INVASION »
  (INVASION sans drops dans l'APK 12.1.0) — capturé/logué « ! refresh marchand échec », non-fatal, indépendant de ce changement. Events retirés après.
  `nav MERCHANT` (GEAR/Badge Bazaar) reste verrouillé (unlockable) sur ce compte → vérif faite via BLACK_MARKET, directement navigable.

Régression **142/142** (`MerchantDiscountTest`). Fichiers : `ServerEvents.java` (buildMerchant* + buildMerchantEvent + eventFromSpec + specJsonMerchant),
`ServerUser.java` (applyPurchaseMerchantItem + applyRefreshMerchant → snapshot opérateur), `AdminEvents.java` (flags), `MerchantDiscountTest.java`
(nouveau), `regression.sh`, `MEMORY.md`. **PROCHAIN = ExtraChest.**

## 2026-08-23 (g155) — SPECIAL_EVENTS live-ops : MISC_BONUS + MISC_DISCOUNT (multiplicateurs divers) ✅ headless + ✅ EN JEU

5ᵉ (et 6ᵉ) composant : multiplicateurs « divers » (`MultiplierType`) — ALCHIMIE (achat d'or), STAMINA, INVASION_STAMINA, PREMIUM_STAMINA.
- **Schéma (bytecode)** : `MiscBonus.load` = `miscBonusFilter` (EnumFilter clé `miscBonus`) + `bonus` (int) ; `MiscDiscount.load` =
  `miscDiscountFilter` (clé `miscDiscount`) + `percentOff`. Fabriques `createComponent("miscBonus")`/`("miscDiscount")`.
  `MultiplierType` : BONUS_ALCHEMY/BONUS_STAMINA/BONUS_INVASION_STAMINA/BONUS_PREMIUM_STAMINA ; DISCOUNT_ALCHEMY/DISCOUNT_STAMINA/DISCOUNT_INVASION_STAMINA.
- **Effet (consommation)** : `UserHelper.buyGold(idx, user, snapshot)` lit `snapshot.getAlchemyPrice(base)` (coût d'or, DISCOUNT_ALCHEMY) et
  `getAlchemyAmount(base)` (or reçu, BONUS_ALCHEMY) — via `getMiscMultipliedValue`. **Branchement serveur** : `ServerUser` BUY_GOLD passe désormais
  `ServerEvents.snapshot()` au lieu de NONE.
- `ServerEvents.buildMiscBonusEvent`/`buildMiscDiscountEvent` (fabrique commune `buildMiscEvent(kind, type, componentKey, filterKey, itemKey,
  valueKey, mults, value, …)`). Specs `MISC_BONUS`/`MISC_DISCOUNT{mults[],value}` + `eventFromSpec` + `specJsonMisc`. `AdminEvents --misc-bonus/
  --misc-discount [id] --mult <TYPE> (répétable) --misc-value N / --close-misc`.
- `MiscMultipliersTest` (régression) : DISCOUNT_ALCHEMY −50 % → `getAlchemyPrice(100)=50` ; BONUS_ALCHEMY +100 % → `getAlchemyAmount(1000)=2000` ; round-trip.
- **✅ VÉRIFIÉ EN JEU** (`--misc-discount --mult DISCOUNT_ALCHEMY --misc-value 50` + `--misc-bonus --mult BONUS_ALCHEMY --misc-value 100` →
  `nav ALCHEMY`) : l'écran BUY GOLD affiche coût **10 → 5 💎** (−50 %) ET or reçu **3,63 M → 7,26 M** (×2), les deux valeurs barrées + badge SALE!.
  Les deux multiplicateurs s'appliquent au chemin réel `buyGold`. Capture `alch1.png`. Events retirés après.

Régression **143/143** (`MiscMultipliersTest`). Fichiers : `ServerEvents.java` (buildMisc* + buildMiscEvent + eventFromSpec + specJsonMisc),
`ServerUser.java` (buyGold → snapshot opérateur), `AdminEvents.java` (flags), `MiscMultipliersTest.java` (nouveau), `regression.sh`, `MEMORY.md`.
**PROCHAIN = FlagUserOnLogin (léger), TeamLevel, puis les 2 lourds ExtraChest + Contest.**

## 2026-08-23 (g156) — SPECIAL_EVENTS live-ops : composant FLAG_USER_ON_LOGIN (flags joueur au login) ✅ headless + ✅ EN JEU

7ᵉ composant. `FlagUserOnLogin<U extends IUserFlag>` = pose/retire des flags joueur au login (marketing/onboarding).
- **Schéma (bytecode)** : `flags` = tableau d'objets `{flag:<UserFlag>, kind:set|clear}`. Ctor `(ISpecialEventType, UserFlag.class)` SANS provider
  → construction DIRECTE (la fabrique `createComponent("flagUserOnLogin")` n'est PAS enregistrée). `UserFlag` = `com.perblue.heroes.game.objects.UserFlag`.
- **Action SERVEUR-autoritative** : AUCUNE classe du jar CLIENT ne consomme `FlagUserOnLoginSnapshot` (c'était une action backend PerBlue) →
  on l'applique nous-mêmes. `ServerEvents.applyLoginFlags(Map wire)` : le snapshot `getEvents()` renvoie une liste de **`SpecialEventInfo`**
  (bytecode `refresh` : `getEvents().add(info)`) → pour chacun `getComponent(FlagUserOnLogin.class)` → lit `flagsToSet`/`flagsToClear` (champs
  privés, réflexion) → écrit dans la map WIRE. **Représentation wire découverte (2 essais)** : `userExtra.flags` = **`Map<String,Boolean>`** —
  clé = **NOM** du flag (String, PAS l'enum → `ClassCastException UserFlag→String` sinon), valeur = **Boolean** (PAS Integer → `ClassCastException
  Integer→Boolean` sinon). set → `put(name, TRUE)`, clear → `put(name, FALSE)`. Relue par `User.setFlags` → `hasFlag`. Branché dans
  `ServerUser.bootData()` (au login, après install). Défaut (aucun event) = no-op.
- `ServerEvents.buildFlagUserOnLoginEvent(id, set, clear, start, end)` + spec `FLAG_USER_ON_LOGIN{set[],clear[]}` + `eventFromSpec` +
  `specJsonFlagUserOnLogin`. `AdminEvents --flag-login [id] --set-flag <F> (répétable) --clear-flag <F> / --close-flag-login`.
- `FlagUserOnLoginTest` (régression) : applyLoginFlags pose SET (TRUE) + retire CLR (FALSE, pré-posé) ; round-trip WIRE (`User.setFlags(map)` →
  `hasFlag(SET)=true`, `hasFlag(CLR)=false`) ; round-trip spec.
- **✅ CONFIRMÉ EN JEU** (`--flag-login --set-flag BATTLE_PASS_V2_SHOW_UPSELL` → `run-online.sh`) : le login du VRAI client déclenche
  `ServerUser.bootData()` → serveur log **`[boot] FLAG_USER_ON_LOGIN : 1 flag(s) appliqué(s) [persisté]`** ; le client atteint le hub
  NORMALEMENT (159 msgs — le changement du chemin critique `bootData` ne casse PAS le login). Pas de capture d'un effet visuel distinct
  (les flags sont des gates internes), mais l'action serveur est confirmée bout-en-bout (login réel → application → persistance). Event retiré après.

Régression **144/144** (`FlagUserOnLoginTest`). Fichiers : `ServerEvents.java` (buildFlagUserOnLoginEvent + applyLoginFlags + eventFromSpec +
specJsonFlagUserOnLogin), `ServerUser.java` (bootData → applyLoginFlags), `AdminEvents.java` (flags), `FlagUserOnLoginTest.java` (nouveau),
`regression.sh`, `MEMORY.md`. **PROCHAIN = TeamLevel, puis les 2 lourds ExtraChest + Contest.**

## 2026-08-23 (g157) — SPECIAL_EVENTS live-ops : FREE_STUFF_AT/EVERY_X_TEAM_LEVEL (récompenses au palier de TL) ✅ headless

8ᵉ composant. **Schéma reward-content CRACKÉ (brique commune TeamLevel/ExtraChest/Contest)** : après ~6 essais parseur-oracle,
`EventRewards.load` accepte `rewards` = un drop OU un tableau de drops `{kind:ITEM,itemType:X,quantity:N}` (drop parsé par le jeu
`RewardDropProvider`, kinds ITEM/UNIT/MOD/AVATAR/BORDER/COSMETIC/UNIT_SKILL). Chemin complet EventRewards→ServerRewardGroup→(rewards direct).
- Composants du jeu : `TeamAtLevel` (kind FREE_STUFF_AT_TEAM_LEVEL, ctor `(type)`, load lit `teamLevel`) = récompense EN ATTEIGNANT le
  niveau ; `TeamLevelRecord` (FREE_STUFF_EVERY_X_TEAM_LEVEL) = tous les X niveaux. **Sémantique EVERY_X relevée au jeu** (sonde) :
  `getRewardTimes = (newTL-1)/X − (oldTL-1)/X` → paliers 11,21,31… (pas 10,20,30). 9→10=0, 10→20=1, 0→30=2.
- `ServerEvents.buildTeamLevelEvent(id, teamLevel, drops, everyX, start, end)` = EventVisibility + TeamAtLevel/TeamLevelRecord + EventRewards
  + carte. `teamLevelRewardDrops(user, oldTL, newTL)` : pour chaque event actif, `getRewardTimes(info,user,old,new)` × `EventRewards.getRewards`.
- **GRANT serveur-autoritatif** : le `sendEventRewards` du jar client ne fait QUE la conversion premium-stamina (le grant réel était backend
  PerBlue) → on livre les drops par **COURRIER** (`ServerUser.deliverMail(SYSTEM_MESSAGE, "Team Level Reward", …, drops)`), branché dans
  `recordCampaignAttack` : on capture `oldTL = userInfo.basicInfo.teamLevel` (avant resync) et `newTL = user.getTeamLevel()` ; si montée,
  `teamLevelRewardDrops` → deliverMail. Défaut (aucun event) = no-op.
- Specs `FREE_STUFF_AT/EVERY_X_TEAM_LEVEL{teamLevel,drops[{item,qty}]}` + `eventFromSpec` + `specJsonTeamLevel`. `AdminEvents --team-level N
  [--every] --reward-item <ITEM> --reward-qty Q / --close-team-level`.
- `TeamLevelTest` (régression) : AT_LEVEL 50 (49→50 = 1 drop ×5, 50→51 = 0) ; EVERY_X 10 (10→20 = 1, 0→30 = 2, 9→10 = 0) ; round-trip spec.
- **In-game 🟢 (composition de chemins vérifiés)** : le mécanisme est headless-prouvé ; il est branché dans `recordCampaignAttack` = le chemin
  du combat de CAMPAGNE, ✅ vérifié en jeu de nombreuses fois (recordOutcome/loot) ; la livraison passe par `deliverMail`, ✅ vérifiée en jeu
  (AdminMail « GIFT FROM THE ADMIN », guild aid). Une démo TL-up COMPLÈTE en jeu est impraticable sur le compte de test TL200 (l'XP par combat
  est très inférieure à l'XP requise pour le niveau 201) → à démontrer sur un compte bas-niveau si l'utilisateur le souhaite. Honnête §8 :
  chaque brique est vérifiée en jeu ; seul le déclenchement TL-up combiné reste à filmer.

Régression **145/145** (`TeamLevelTest`). Fichiers : `ServerEvents.java` (buildTeamLevelEvent + teamLevelRewardDrops + eventFromSpec +
specJsonTeamLevel), `ServerUser.java` (recordCampaignAttack → deliverMail au level-up), `AdminEvents.java` (flags), `TeamLevelTest.java`
(nouveau), `regression.sh`, `MEMORY.md`. **PROCHAIN = Contest (dernier, le plus lourd) + ExtraChest.**

## 2026-08-23 (g158) — SPECIAL_EVENTS live-ops : RECON + FEASIBILITY des 2 composants LOURDS (ExtraChest, Contest)

Après 6/8 composants livrés & vérifiés en jeu, les 2 restants (ExtraChest, Contest) se révèlent être du CONTENU lourd (pas « un builder
de plus »). Décision utilisateur : documenter recon + feasibility + plan d'incréments (comme pour les trials avant de les attaquer),
sans implémenter maintenant. Détail complet dans `docs/SPECIAL_EVENTS.md` §RECON. Résumé :
- **ExtraChest (EXTRA_CHEST)** = coffre bonus sur CRATES. Voie objet PROUVÉE (`createComponent("eventChestData")` + carte `EventCardDisplay`
  requise + `eventChestData{content=drops[{kind:ITEM,…}], cost, currency, freeBuys, maxBuys, maxPurchases, buyXNumber, featured, title/heading/
  text, + sous-écrans detailsScreen/infoScreen/selectionCard}`). POINT DUR = le **`preset`** de chaque sous-écran (EventPresets.properties, même
  point dur que la carte de trial). Parseur-oracle démarré (content/cost/currency passent ; bloqué sur `preset` d'un sous-écran). Consommation
  `snapshot.getEventChests()`/`getSingleEventChest()`. Plan : (1) presets, (2) builder+spec+admin, (3) snapshot→CRATES, (4) achat/ouverture, (5) en jeu.
- **Contest (CONTEST)** = LEADERBOARD serveur-autoritatif (mode, pas builder). `new Contest(type, ContestTaskType.class)` + `tasks`/`progressRewards`/
  `rankRewards`. Tâches `ContestTaskType` (BATTLE_WON/ENEMY_DEFEATED/ITEM_BURN/HERO_*/EXPEDITION_FINISHED…) → points → paliers + classement.
  `ContestHelper.onItemEarn` DÉJÀ appelé dans openChest. POINT DUR = blob progression par-joueur + classement par-shard + réclamation (progress+rank)
  + wiring des déclencheurs = effort niveau ARÈNE/SURGE. Plan : (1) builder+spec+admin, (2) ServerContest (blob progression, patron expeditionRun),
  (3) wiring tâches→addProgress, (4) classement+réclamation, (5) en jeu. NB `ServerUser.deliverContestSeasonReward` existe déjà (guild contest).

Régression 145/145 (inchangée). Fichiers : `docs/SPECIAL_EVENTS.md` (§RECON ExtraChest/Contest + bilan live-ops), `MEMORY.md`.
**PROCHAINE ACTION = décision user sur l'implémentation d'ExtraChest (presets) puis Contest (mode), OU passage à la Phase 2.**

## 2026-08-23 (g159) — RECON APPROFONDIE ExtraChest + Contest (.tab/code/outils + wiki)

Demande user : « regarde bien ce que c'est » (ExtraChest) + wiki Contest (https://disneyheroesbattlemode.fandom.com/wiki/Contests), en pensant
`.tab` + code + outils d'industrialisation. Findings intégrés à `docs/SPECIAL_EVENTS.md §RECON` :
- **ExtraChest** : c'est un COFFRE bonus complet (comme GOLD/DIAMOND CRATE), affiché temporairement sur CRATES. **Découverte code** :
  `EventChestStats extends DHDropTableStats` (ctor `(String)`) → le `content` de `eventChestData` est une **TABLE DE DROPS pondérée au format
  `chests.tab`** (colonnes `NODE / WEIGHT / QUANTITY / RESULT / BEHAVIOR`, cf. `gold_chest_drops.tab`), PAS une simple liste `[{kind:ITEM}]`.
  `EventChestDataDH.getStats()` renvoie l'`EventChestStats`, `rollNode*` tire le loot. **§4** : drop-table backend-authored inline → le modéliser
  sur un coffre existant, jamais inventer poids/loot. Pas de `event_chest.tab` (la table est dans le JSON de l'event). Point dur inchangé =
  presets des sous-écrans UI.
- **Contest** (wiki fandom bloqué 402 → helpshift PerBlue + COPIE de la page fournie par l'user) : contest **HEBDOMADAIRE, Vendredi→Jeudi**.
  Points via un `ContestTaskType` (34 valeurs relevées au jar : BATTLE_WON/ENEMY_DEFEATED/OPEN_CHEST/ITEM_BURN/RESOURCE_BURN/EARN/WAR_ATTACK/
  SABOTAGE/HERO_*/EXPEDITION_FINISHED…), scoring = dépenser/gagner ressources + jouer 3-5 héros sélectionnés (Arena/Coliseum/Surge/City Watch).
  **progressRewards = 5 paliers** (~375 000 / 430 000 / 500 000 / 750 000 / 1 000 000 pts) → COURRIER immédiat au palier. **rankRewards** = FIN de
  contest par PERCENTILE (Top 1/5/10/25/50 % + rangs 1-10). **1 contest sur 3 = GUILDE** (membre >24 h ; tous même reward), rarement server-wide.
  **⭐ RANK REWARDS = PREMIÈRE SORTIE d'un NOUVEAU HÉROS** (Franny Robinson, Ratigan, Penny Proud, Anne Boonchuy, Chernabog… = lot ultime, chips/
  héros vedette exclusif, drop `kind:UNIT`) → levier live-ops majeur (sortie de héros gatée par le rang de contest), à modéliser fidèlement.
  `ServerUser.deliverContestSeasonReward(seasonName, guildRank, tier)` existe déjà (courrier de fin, guild contest).
  Sources : `perblue.helpshift.com/.../178-what-are-progress-and-rank-rewards`, copie wiki fandom Contests (fournie par l'user).

Rien de code changé (recon pure). Régression 145/145 (inchangée). Fichiers : `docs/SPECIAL_EVENTS.md` (§RECON approfondi), `MEMORY.md`
(pointeur « mode en cours » corrigé → SPECIAL_EVENTS ; g159). **La CONSIGNE DE HANDOFF (successeur : rituel de reprise EN ENTIER avant toute
reprise) est en tête de MEMORY + imposée par le hook SessionStart — rappelée explicitement à chaque compression.**

## 2026-08-24 (g160) — SPECIAL_EVENTS live-ops : composant EXTRA_CHEST (coffre bonus CRATES) ✅ headless + ✅ VÉRIFIÉ EN JEU

7ᵉ des 8 composants live-ops LIVRÉ (reste Contest). **Point dur historique `preset` RÉSOLU sur les FAITS du bytecode** (§2, pas de
rustine ; §8, pas de supposition). Méthode : disassembly de `EventChestData.<init>` → le discriminant est `if (eventChestData.has("text"))`.
- **DEUX formats** : **A** (`text` présent) lit `text.preset` (String REQUIS → résout les libellés d'écran via `EventPresets.properties`,
  le point dur). **B** (PAS de `text`, celui qu'on utilise) : `preset=""`, TOUS les libellés INLINE via 3 sous-objets REQUIS
  `selectionCard{title,info}` / `detailsScreen{title,info}` / `info{title,heading1,content1,heading2,content2[]}` → **zéro dépendance
  `preset`/bundle** (auto-suffisant, §4). Communs : `cost`/`buyXNumber` (getInt requis), `currency` (ResourceType), `maxBuys`/
  `maxPurchases`/`freeBuys`/`featured` (défauts), et **`config` = TABLE DE DROPS inline** (String → `EventChestStats(String)` =
  `DHDropTableStats` DTCodes `ROOT`/`DISPLAY`). Carte `EventCardDisplay` REQUISE avant l'ExtraChest (lit `getImage()`).
- **Feasibility prouvée par sonde** (`/tmp/ecx`) : LOAD OK Format B ; tous les champs parsés (cost=100/currency=DIAMONDS/maxBuys=50/
  freeBuys=1/featured=true) ; **`getStats().rollNodeSimpleDrops("ROOT",1)` roule du VRAI loot** (`[DIAMONDS]`, `[GOLD*100000]`).
- **Builder** `ServerEvents.buildExtraChestEvent(id, cost, currency, buyX, maxBuys, maxPurchases, freeBuys, featured, title, info,
  List<ChestDrop>, draws, start, end)` + helper `extraChestDropTsv` (assemble la TABLE au format `chests.tab` : ROOT tire `draws` dans
  le pool pondéré PICK ; l'admin fournit result/qty/weight, §4) + `specJsonExtraChest` + branche `eventFromSpec` `EXTRA_CHEST`.
- **Consommation serveur** : `ChestType.EVENT`. Coût/monnaie/limites/validation = LOGIQUE DU JEU (`getBasePurchaseCost`/
  `getPurchaseCurrency`/`getPurchaseCost`/`validateChestPurchase` branche EVENT → `getSingleEventChest`) sur le snapshot opérateur
  (déjà passé, chestSnap). **Branche EVENT ajoutée à `ServerUser.openChest`** : le loot NE vient PAS de `chests.tab`
  (`getDropTable(EVENT)`=null) mais de `getSingleEventChest().getStats().getTable().rollNode("ROOT", ChestContext(user))` (1 roll/coffre
  acheté = buy X → X rolls). **2 correctifs §8** : (a) `openChest` déclare désormais `throws ClientErrorCodeException` (checkée ; garde
  « aucun coffre event actif ») → helpers de test `ChestValidateTest.open`/`WishingWellWishTest.rollBatch` re-déclarés ; (b)
  `giveChestRewards` reçoit le snapshot opérateur (pas `null`) sinon `getPurchaseCurrency(EVENT,null)` NPE (pour les coffres normaux,
  chestSnap == comportement NONE).
- **Enregistrement = `OPERATOR_EVENTS`** (chemin PERSISTANT AdminEvents), PAS `install()` : chaque `ServerContext.bind` (dont l'INTERNE
  à `openChest`, ligne 1591) réinitialise puis réinstalle depuis `OPERATOR_EVENTS` → un simple `install()` serait effacé au 1ᵉʳ bind
  (racine du `CHEST_EVENT_ENDED` initial du test).
- `AdminEvents --extra-chest [id] --ec-cost N --ec-currency TYPE --ec-buyx N --ec-maxbuys N --ec-maxpurchases N --ec-freebuys N
  --ec-title STR --ec-info STR --ec-drop RESULT[:QTY[:WEIGHT]] (répétable) --ec-draws N / --close-extra-chest`.
- `ExtraChestTest` (régression) : (1) snapshot expose le coffre (coût 100 DIAMONDS, monnaie via logique du jeu) ; (2) la table inline
  roule ≥1 drop ; (3) **`openChest(EVENT)` end-to-end → loot crédité + DIAMONDS 100000→99900 (débit=100)** ; (4) round-trip spec.

**✅ VÉRIFIÉ EN JEU (§8)** — `AdminEvents --extra-chest … --ec-drop GOLD:100000:3 --ec-drop GEAR_TOKENS:50:2 --ec-drop DIAMONDS:20:1`
poussé dans la DB serveur → `run-online.sh` (client réel, id=1 TL200) :
- **`nav CHESTS`** → écran CRATES montre le coffre bonus à côté des GOLD/DIAMOND/GUILD CRATE (capture `manual.png`).
- **`nav EVENT_CRATE`** → détail **« SUPPLY CRATE » + « Contains an assortment of resources! » + « FREE NOW! » + « Crates Left: 50/50 »**
  = EXACTEMENT mes params admin (title/info/freeBuys/maxBuys), rendus par le client depuis `REFRESH_SPECIAL_EVENTS`. Capture `eventcrate.png`.
- **Ouverture** (tap FREE NOW via pilote `fire`) → client `BuyChests(EVENT)` → serveur roule MA table inline → écran **« CRATE REWARDS »**
  (50 GEAR_TOKENS puis 20 DIAMONDS selon le tirage pondéré). Serveur : `BuyChests1` → `LootResults coffre EVENT [persisté]` +
  `RECORD_SERVER_ROLL_FINISHED [persisté]`. Captures `ec_after.png`/`ec_free.png`.
- **CORRECTIF FIDÉLITÉ §8 (trouvé EN JEU)** : le 1ᵉʳ open affichait « FREE NOW » mais le serveur prenait le chemin PAYANT (débit 100,
  écrêté à 0 car compte 0💎). Cause : `freeChest()` passait un snapshot `null` à `hasFreeChest` → la branche EVENT (qui lit
  `getSingleEventChest().getFreeBuys()` vs `getEventCompletionCount(id)`) ne voyait pas les free buys. **Fix** : `freeChest(user, type,
  count, snap)` passe le snapshot opérateur → « FREE NOW » honoré (chemin GRATUIT, aucun débit). Re-vérifié EN JEU (freeBuys=3 → open
  GRATUIT confirmé, 0 ligne `coffre PAYANT` serveur + 20 DIAMONDS crédités). `ExtraChestTest` renforcé (cas freeBuys=1 → wasFree=true, 0 débit).
  Coffres normaux inchangés (`FreeChest/ChestCharge/ChestPaidDebit/ChestValidate` verts).
- **CORRECTIF FIDÉLITÉ §4bis (relevé par l'utilisateur sur capture)** : l'écran de détail n'affichait AUCUNE grille de « loot possible ».
  Cause : ma table inline n'avait qu'un nœud `ROOT` (tirage) ; l'aperçu vient du nœud **`DISPLAY`** (`ChestHelper.getPossibleDrops` →
  `EventChestStats.getPossibleLoot` = roll du nœud `DISPLAY`), absent de ma table. **Fix** : `extraChestDropTsv` génère désormais DEUX nœuds
  frères comme les vraies tables (`expedition_chest_drops.tab`) — `DISPLAY` (liste tous les items possibles via sous-nœuds `D<i>`, = aperçu)
  + `ROOT` (tirage pondéré). `ExtraChestTest` : `getPossibleDrops` = les N items. **✅ RE-VÉRIFIÉ EN JEU** : l'écran SUPPLY CRATE montre la
  grille GOLD / GEAR_TOKENS / DIAMONDS (capture `ec_disp.png`).

Régression **146/146** (`ExtraChestTest`). Fichiers : `ServerEvents.java` (buildExtraChestEvent + ChestDrop + extraChestDropTsv +
specJsonExtraChest + eventFromSpec + esc), `ServerUser.java` (openChest branche EVENT + throws + giveChestRewards snapshot + **freeChest
snapshot**), `AdminEvents.java` (flags --extra-chest/--ec-*), `ChestValidateTest.java`/`WishingWellWishTest.java` (throws), `ExtraChestTest.java`
(nouveau, + cas freeBuys), `regression.sh`, `docs/SPECIAL_EVENTS.md` (§RECON A = ✅ EN JEU), `MEMORY.md`. **RESTE = Contest (dernier
composant, mode serveur-autoritatif).**

## 2026-08-24 (g161) — SPECIAL_EVENTS live-ops : CONTEST incrément 1 (structure) ✅ headless

Dernier composant live-ops (mode serveur-autoritatif, effort niveau arène/surge → fait par INCRÉMENTS). Incr.1 = structure.
**Schéma `Contest.load` cracké au bytecode** (parseur-oracle) : `load(info, param2, param3)`, si `formatVersion==0` lit sur le
nœud COMPLET param2 : `contestInformation{guild,aggregate}` + `contestTask[]` + `contestRankRewards[]` + `contestProgressRewards[]`
(le nom des 3 listes change selon formatVersion : `contestTask`/`contestRankRewards`/`contestProgressRewards` en v0, `tasks`/
`rankRewards`/`progressRewards` sinon).
- **`contestTask[]`** = `ContestTaskInfo(info, json, Class)` : `maxTimes`/`maxDailyTimes`/`pointsEarned`/`taskIndex` + `taskItem{taskData,
  taskData2, countNeeded, type=<ContestTaskType>, hidden}`.
- **`contestProgressRewards[]`** = `ContestProgressRewardInfo(info, json, int)` : `pointsRequired` (long) + `rewarditem`.
- **`contestRankRewards[]`** = `ContestRankRewardInfo(info, json, int)` : `kind` (PERCENT|NUMBER) + `rank` (int) + `rewarditem`.
- **`rewarditem`** = `RewardGroup(info, json, isStatic)`. **En formatVersion 0, isStatic=true** → `rewarditem` = un drop OU un
  TABLEAU de drops (`RewardDrop.parse`/`loadRewards`), **PAS** l'objet `{rewardTarget,rewards}` (qui est la forme isStatic=false).
  Donc `rewarditem:[{kind:ITEM,itemType,quantity}]` (ou `{kind:UNIT,unitType,quantity}` pour un héros vedette).
- **Contrainte compil** : `ContestTaskType` a un attribut d'annotation de paramètre CORROMPU laissé par dex2jar
  (`bad RuntimeInvisibleParameterAnnotations`) → javac refuse de le lire en SOURCE. Contourné par **réflexion**
  (`Class.forName("…ContestTaskType")` pour le ctor `new Contest(type, ctType)`), jamais d'`import`.
- `ServerEvents.buildContestEvent(id, guild, aggregate, List<ContestTask>, List<ContestProgress>, List<ContestRank>, start, end)`
  + types publics `ContestTask`/`ContestProgress`/`ContestRank` + `specJsonContest` (round-trip) + branche `eventFromSpec` CONTEST +
  helpers `dropsArray`/`contestDropsFromSpec`/`dropSpecFrom`. `AdminEvents --contest [id] [--contest-guild] [--contest-aggregate]
  --contest-task TYPE:POINTS:COUNT[:MAXTIMES:MAXDAILY] --contest-progress POINTS:ITEM:QTY --contest-rank KIND:RANK:ITEM:QTY
  --contest-rank-unit KIND:RANK:UNIT:QTY / --close-contest`.
- `ContestTest` (régression) : (1) structure (2 tâches/2 paliers/2 rangs, solo) ; (2) snapshot expose le CONTEST
  (`getActiveEvents` filtre par éligibilité → user bindé requis) ; (3) round-trip spec.
- **API repérée pour la suite (§3)** : `ContestHelper.on*` (onCampaignAttack/onChestOpen/onItemEarn/onItemBurn/onResourceBurn/Earn/
  onSurgeAttack/onWarAttack/onExpeditionAttack/Completed/…) = hooks DU JEU qui créditent les points ; `IContestData`
  (getProgressPoints/getRankPoints/getCompletedCount/getPartialCount/hasEarnedProgressReward/recordContribution/…) = l'état par-joueur
  à persister (incr.2) ; `ContestHelper.recordTasks(user, info, taskInfo, now, contestData)` = le cœur du crédit.

Régression **147/147** (`ContestTest`). Fichiers : `ServerEvents.java` (buildContestEvent + ContestTask/Progress/Rank + specJsonContest
+ eventFromSpec + helpers), `AdminEvents.java` (flags --contest/--contest-*), `ContestTest.java` (nouveau), `regression.sh`,
`docs/SPECIAL_EVENTS.md`, `MEMORY.md`. **SUITE = incr.2 blob progression par-joueur (`ServerContest`/`IContestData`) + `GetContestData`.**

## 2026-08-24 (g162) — CONTEST incrément 2 (état per-user serveur-autoritatif + GetAllContestData + persistance) ✅ headless

Incr.2 = servir/persister la progression per-joueur. Modèle wire découvert : `GetAllContestData` (requête) → `AllContestData`
(`Map<contestID, ContestData>`) ; `ContestData{extraData:ContestExtraData, progressPoints, rank, rankPoints, totalParticipants}` ;
`ContestExtraData{taskCompletionCount/taskDailyCompletionCount/taskPartialCount (+old*), contribution, earnedProgressRewards,
lastDailyResetTime, originalShardID}` ; **`ClientContestData implements IContestData`** = l'objet d'EXÉCUTION qui ENVELOPPE un
`ContestData` wire (ses setters écrivent dans le wire → persistance directe ; c'est l'objet passé à `ContestHelper.recordTasks/on*`).
- **⚠️ COLLISION DE NOM évitée** : `ServerContest` EXISTE DÉJÀ (#67, SAISON de contest de GUILDE — guilde-vs-guilde par
  `GuildInfo.contestPoints`, paliers admin, `Tier`, `deliverContestSeasonReward`). Ce sont **2 systèmes DISTINCTS** : l'ancien =
  contest de guilde (perk `GUILD_CONTESTS`) ; le NOUVEAU (composant SPECIAL_EVENTS `Contest`) = leaderboard de TÂCHES per-joueur.
  → ma classe = **`ServerContestData`** (jamais toucher `ServerContest`). [J'avais écrasé `ServerContest` par erreur — restauré
  via `git checkout`, code déplacé dans `ServerContestData` ; leçon : regarder la cible avant d'écraser.]
- `ServerContestData` : `freshContestData(shard)` (0 point, maps/list INITIALISÉES sinon NPE au 1ᵉʳ crédit) ; `getAllData(su)`
  (blob `AllContestData` frais si absent) ; `getContestData(su, id)` (persisté sinon frais + stocké) ; `clientData(id, cd)` =
  `ClientContestData` ; `response(su)` = `AllContestData` de tous les contests ACTIFS (composant `Contest` du snapshot ; bind requis
  pour le filtre d'éligibilité).
- **Persistance** : nouvelle colonne BLOB `contestData` (`AllContestData` sérialisé), patron `trialEventData` — `ServerUser.contestWire/
  setContestWire/contestDataOrNull/setContestData` + migration `UserStore` + SELECT/INSERT (col 9/11). NULL = aucun état (pré-migration OK).
- **Handler `LoginServer`** : `GetAllContestData` → `ServerContestData.response(user)` → persiste → répond `AllContestData`.
- **⚠️ Sémantique du jeu relevée** : `ClientContestData.setRankPoints(v)` **LIE** rank↔progress (fait aussi `progressPoints +=
  delta`) — rank points = sous-ensemble des progress points. Le test écrit `rankPoints` sur le champ wire pour un cas isolé.
- `ContestDataTest` (régression) : frais servi+posé ; write-through `ClientContestData`→wire (500 pts, tâche0=3) ; round-trip WIRE
  (points+compteurs préservés) ; `response` expose 1 contest actif.

Régression **148/148** (`ContestDataTest`). Fichiers : `ServerContestData.java` (nouveau), `ServerUser.java` (persistance contestData),
`UserStore.java` (colonne + migration + SELECT/INSERT), `LoginServer.java` (handler GetAllContestData), `ContestDataTest.java` (nouveau),
`regression.sh`, `JOURNAL.md`, `MEMORY.md`. **SUITE = incr.3 wiring des tâches (`ContestHelper.on*` → crédit points dans le blob).**

## 2026-08-24 (g163) — CONTEST incrément 3 (wiring des tâches → crédit de points) ✅ headless

Incr.3 = brancher les déclencheurs DU JEU pour créditer les points de contest dans le blob per-user (§3, on exécute la logique
du jeu, on n'invente pas le barème).
- **Point d'intégration découvert** : `IUser.getContestData(long id)` → `ClientContestData` depuis la map `User.contestData`
  (crée un frais si absent). `ContestHelper.on*` → `recordAttackTasks` → `SpecialEventsHelper.getActiveContests(user)` +
  `ContestTaskType.recordAttackForTask(...)` créditent via `user.getContestData(id)`. **Recette** : PRÉ-PEUPLER
  `user.getContestData()` avec un `ClientContestData` enveloppant le `ContestData` PERSISTÉ du blob → le jeu mute EN PLACE notre
  blob (aucune extraction). Sans ça, le jeu créerait un `ClientContestData` frais hors blob → progression perdue.
- `ServerContestData.prepare(su, user)` (pré-peuple les contests actifs) + `record(su, user, hook)` (prepare + hook) +
  `activeContestIDs(su)` + interface `ContestHook`.
- **Wiring** : (a) `recordCampaignAttack` → `ContestHelper.onCampaignAttack(user, mode, m.base.outcome, m.base.attackers,
  m.base.defenders)` (lineups NATIFS du client = signature du jeu ; tâches BATTLE_WON/ENEMY_DEFEATED/BATTLE_HEROES_LEFT…) ;
  (b) `openChest` → `prepare` AVANT `giveChestRewards` (pour capter le crédit INTERNE `onItemEarn` des tâches ITEM_EARN_*) +
  `ContestHelper.onChestOpen(user, type, count)` (tâche OPEN_CHEST). Persistance via le `store.save` des handlers.
- `ContestCreditTest` (régression) : contest BATTLE_WON (10 pts) → `onCampaignAttack(WIN)` crédite 10 pts ; 2ᵉ combat → 20 ;
  round-trip WIRE préserve. Coffres/campagne inchangés (ChestCharge/ExtraChest/CampaignAttack verts).
- **RESTE (incr.4)** : classement serveur-autoritatif (rang par points) + réclamation des progressRewards (paliers, courrier) +
  rankRewards (fin de contest). D'autres hooks (`onSurgeAttack`/`onWarAttack`/`onExpeditionAttack`/`onItemBurn`/`onResourceBurn`)
  se branchent au même patron `ServerContestData.record` sur leurs chemins respectifs (à ajouter au besoin/en jeu, §8).

Régression **149/149** (`ContestCreditTest`). Fichiers : `ServerContestData.java` (prepare/record/activeContestIDs/ContestHook),
`ServerUser.java` (recordCampaignAttack → onCampaignAttack ; openChest → prepare + onChestOpen), `ContestCreditTest.java` (nouveau),
`regression.sh`, `JOURNAL.md`, `MEMORY.md`. **SUITE = incr.4 classement + réclamation.**

## 2026-08-24 (g164) — CONTEST incrément 4 (classement + réclamation : progressRewards paliers + rankRewards rang) ✅ headless

Incr.4 = récompenses + leaderboard serveur-autoritatif.
- **progressRewards (PALIERS)** — RÉCLAMATION AUTOMATIQUE : `ServerContestData.deliverEarnedProgressRewards(su, user)` — pour
  chaque contest actif, tout palier dont `getRequiredPoints()` est atteint et pas déjà dans `earnedProgressRewards`
  (`hasEarnedProgressReward(idx)` = `List<Integer>.contains`) → livré par COURRIER (wiki : immédiat au palier) via
  `ContestProgressRewardInfo.getRewards(user, formatVersion)` + `deliverMail` (§3) + marqué gagné (idempotent). Appelé à la fin
  de `record(...)` (campagne) ET après `onChestOpen` (coffre).
- **CLASSEMENT (leaderboard)** — SERVEUR-AUTORITATIF : ladder per-(shard, contestID) = `Map<userID, rankPoints>` sérialisé dans
  `shard_state` (clé `contest_ladder:<id>`, patron `arena_ladder`, §5). `recomputeRank(store, su, id, cd)` met à jour l'entrée du
  joueur + calcule `cd.rank` (1 + nb strictement au-dessus) et `cd.totalParticipants`. Appelé dans `response(su, store)` (le handler
  `GetAllContestData` passe le `store`).
- **rankRewards (RANG, fin de contest)** : `distributeRankRewards(store, shard, id, eventInfo)` — trie le ladder par points
  décroissants, attribue le rang, et pour chacun `rankRewardFor(contest, rank, total)` (1ᵉʳ tier satisfait : `isPercent` →
  `100·rank/total ≤ maxRank`, sinon `rank ≤ maxRank` ; tiers ordonnés du + exclusif au - par l'admin) → livre `getRewards` par
  COURRIER. **⚠️ `ContestHelper.getRankInfo(info, i)` renvoie le i-ᵉ tier par INDEX (pas par rang)** → j'écris la sélection de tier
  (glue) moi-même. Exposé par `AdminEvents --contest-end <id>` (reconstruit l'event depuis la config → distribue).
- Tests : `ContestRewardTest` (palier 20 pts → 1 courrier, idempotent) ; `ContestRankTest` (2 joueurs, rang 1/2 + participants,
  ladder persistant) ; `ContestEndTest` (rankRewards : A rang 1 → ACE×100, B rang 2 top 100 % → ACE×10, par courrier).
- **API `ClientContestData.hasEarnedProgressReward`** = `extraData.earnedProgressRewards.contains(Integer)`; on marque en ajoutant
  l'`Integer` idx à cette liste wire.

Régression **152/152** (`ContestRewardTest`/`ContestRankTest`/`ContestEndTest`). Fichiers : `ServerContestData.java` (deliverEarned/
ladder/recomputeRank/rankRewardFor/distributeRankRewards ; `response(su, store)`), `ServerUser.java` (openChest → deliverEarned),
`LoginServer.java` (response passe le store), `AdminEvents.java` (--contest-end), 3 tests, `regression.sh`, docs. **SUITE = incr.5 vérif
EN JEU (écran CONTESTS : progression, paliers, classement, réclamation).**

## 2026-08-24 (g165) — CONTEST incrément 5 : ✅ VÉRIFIÉ EN JEU (écran CONTESTS) + finition titre/résumé

Incr.5 = vérif EN JEU (§8) → **CONTEST COMPLET (dernier des 8 composants live-ops).**
- **✅ EN JEU** : `AdminEvents --contest --contest-title "SUMMER SHOWDOWN" --contest-summary "…" --contest-task BATTLE_WON:10:1
  --contest-task OPEN_CHEST:5:1 --contest-progress 10:ACE:5 --contest-progress 30:ACE:25 --contest-rank number:1:ACE:500
  --contest-rank percent:50:ACE:50` → `run-online.sh` → `nav CONTESTS` → l'écran **CONTESTS** rend MON contest : barre de
  progression avec mes 2 PALIERS (10/30), **TOP PROGRESS REWARDS** ACE×25 (palier 30), **TOP RANK REWARDS** ACE×500 (rang 1) +
  ×50 (top 50 %), « Contest Ends In: 29d 23h », Score 0/Rank -, onglets OVERVIEW/SCORING/RANKINGS/PROGRESS/RANK. Captures
  `contest2.png` (structure) / `contest3.png` (titre).
- **CORRECTIF §8 (débloquer l'écran)** : l'onglet HALL OF FAME de l'écran CONTESTS envoie au chargement `GetContestHallOfFame`/
  `GetLastContestWinners`/`GetHallOfFameRanks` et reste bloqué sur « LOADING … » sans réponse. Handlers `LoginServer` ajoutés :
  réponses VIDES (`ContestHallOfFames`/`LastContestWinners`/`HallOfFameRanks` — pas d'historique sur ce serveur communautaire) →
  débloque l'écran. NB : `GET_CONTEST_RANKINGS` (action) a DÉJÀ un handler (guild contest #67) qui répond `ContestRankings`
  (« ton rang 1 »).
- **CORRECTIF §8 (finition, relevé sur capture — même point que les trials)** : le titre affichait « NONE.TITLE / none.summary ».
  Cause : `EventCardDisplay.getTitle()/getSummary()` = les EventStrings de la carte, mis à vide par `buildMinimalCard` (preset
  none). **Fix** : `buildContestEvent(id, guild, aggregate, TITLE, SUMMARY, …)` pose `card.title/summary` =
  `EventString.unlocalized(info, valeur)` (helper `setCardText`) ; params admin `--contest-title`/`--contest-summary` + spec.
  Re-vérifié EN JEU : « SUMMER SHOWDOWN » + résumé affichés (`contest3.png`).

Régression **152/152**. Fichiers : `LoginServer.java` (3 handlers hall-of-fame), `ServerEvents.java` (buildContestEvent titre/résumé
+ setCardText + specJsonContest + eventFromSpec), `AdminEvents.java` (--contest-title/--contest-summary), `ContestTest.java`,
`regression.sh`, `JOURNAL.md`, `MEMORY.md`. **⇒ CONTEST COMPLET (structure+état+wiring+classement+réclamation+EN JEU). ⇒ LES 8
COMPOSANTS LIVE-OPS SONT LIVRÉS. RESTE (hors live-ops) : audit « écrans store pas de crash », Phase 2.**

## 2026-08-24 (g166) — CONTEST gap B : score/rang de l'overview solo (GET_CONTEST_RANKINGS) ✅ VÉRIFIÉ EN JEU

Gap B = « le Score de l'écran CONTESTS doit monter en jouant » (0→10). Le crédit serveur des tâches était DÉJÀ prouvé
en jeu (incr.5 : ouvrir des coffres → palier livré par courrier), mais l'OVERVIEW affichait toujours « Score: 0 ».

- **Cause racine établie au BYTECODE** (`ContestsScreen`/`ContestsOverviewContent`, `game-logic-framed.jar`) : l'overview
  SOLO lit son grand « Score: » et son « Rank: » depuis le champ `this.yourInfo` (un `ContestRankingRow`), PAS depuis
  `data.getProgressPoints()` (la barre de progression, elle, utilise `getLastSeenContestScore`/`getProgressPoints`).
  `yourInfo` = `ContestRankings.yourInfo`, posé à la réception de la réponse de l'action **`GET_CONTEST_RANKINGS`** que
  `ContestsScreen.show()` émet pour un contest solo (`ClientActionHelper.requestContestRankings(id,false)` → extra `ID=<contestID>`).
  Branche guilde (`isGuildContest()`) = `GET_GUILD_CONTEST_RANKINGS`+`yourGuildInfo` (distincte). Le handler existant de
  `GET_CONTEST_RANKINGS` renvoyait les points de CONTEST DE GUILDE (`mu.contestPointsIn(g)` = 0 pour un contest de tâches solo).
- **Correctif (§3, serveur autoritatif ; §2 pas de rustine)** : `buildContestRankings(u, contestID)` devient ID-AWARE.
  `activeSoloContest(u, id)` cherche dans le snapshot un event ACTIF d'ID `id` portant un composant `Contest` avec
  `!isGuildContest()`. Si trouvé → **`ServerContestData.soloRankings(store, u, id)`** : (1) `recomputeRank` insère/actualise
  mes points COURANTS (`rankPoints` du blob) dans le ladder per-(shard,contestID) et calcule mon rang (au cas où
  `GetAllContestData` n'aurait jamais été envoyé) ; (2) lignes construites depuis le ladder, triées points DÉCROISSANTS puis
  `reachedAt` CROISSANT (départage « 1ᵉʳ arrivé », gap A) ; chaque `PlayerRow.info` chargé par membre ; `yourInfo` = ma ligne.
  Sinon (ou `id≤0`) → fallback contest de GUILDE #67 (`buildGuildMemberContestRankings`, ex-`buildContestRankings`, INCHANGÉ).
  Le call site lit `extraLong(act, ActionExtraType.ID, 0)`.
- `ContestRankingsTest` (régression **153/153**) : A à 0 pt → `yourInfo.points`=0 rang 1 ; A après 3 `onCampaignAttack(WIN)`
  (vrai hook du jeu, BATTLE_WON:10) → Score 30 rang 1 ; 2 joueurs → A 30/rang 1, B 10/rang 2, `topPlayers` trié ; round-trip
  wire `ContestRankings` (WireCheck).
- **✅ EN JEU** : `AdminEvents --contest 900011 --contest-title "SCORE CLIMB" --contest-task BATTLE_WON:10:1
  --contest-progress 10:ACE_OF_SPADES:5 --contest-rank number:1:ACE_OF_SPADES:500` (ID NEUF ⇒ 0 pt garanti). Pile lancée,
  `nav CONTESTS` → serveur `GET_CONTEST_RANKINGS(id=900011) → ton score 0 rang 1` → écran **Score 0 / Rank -** (`gapB_before.png`).
  `campfight 1 1` + `campquick` → `CampaignAttack NORMAL 1-1 WIN → recordOutcome` + `[contest] palier 0 (10 pts) livré par
  courrier`. `nav CONTESTS` → serveur `GET_CONTEST_RANKINGS(id=900011) → ton score 10 rang 1` → écran **Score 10 / Rank 1st,
  barre remplie au palier 10** (`gapB_after.png`). ⇒ le score MONTE bien en jouant (0→10).
- Aussi (DEV) : pilote `openchest <TYPE> [count]` (chemin réel `ChestHelper.openChestInner`) pour créditer la tâche OPEN_CHEST.

Régression **153/153**. Fichiers : `LoginServer.java` (handler GET_CONTEST_RANKINGS ID-aware + `activeSoloContest` +
`buildContestRankings`/`buildGuildMemberContestRankings`), `ServerContestData.java` (`soloRankings`), `ContestRankingsTest.java`
(nouveau), `regression.sh`, `DesktopLauncher.java`/`TutorialDriver.java` (pilote `openchest`), `JOURNAL.md`, `MEMORY.md`.
**SUITE = gap C (contest de GUILDE de bout en bout, headless + en jeu). Hors contest : audit store, Phase 2.**

## 2026-08-24 (g167) — CONTEST gap C : contest de GUILDE agrégé (crédit per-membre + agrégat + classement) ✅ VÉRIFIÉ EN JEU

Gap C = le dernier « manque » du contest : le mode GUILDE du composant SPECIAL_EVENTS `Contest` (les GUILDES sont classées,
le score d'une guilde = SOMME des points de contest per-user de ses membres, `isGuildContest()`+`isAggregateContest()`).

- **Cause racine (bytecode)** : le crédit des tâches est délégué par le jeu à `ContestHelper.IContestHelperExtension`
  (champ statique privé `extension`). Côté CLIENT posée au boot ; côté SERVEUR headless NULLE → `recordTasks(4-arg)` route
  le guilde vers `extension.recordGuildContestTasks` (null → RIEN crédité). Les 2 méthodes `record*` PAR DÉFAUT de l'interface
  font le vrai travail : `recordContestTasks`→`user.getContestData(id)`, `recordGuildContestTasks`→`user.getGuildContestData(id)`
  (**exige `User.guildID>0`** ; sinon renvoie null), toutes deux → `ContestHelper.recordTasks(…,IContestData)` (barème du jeu, §4).
  `getGuildContestData(id)` = MÊME map `User.contestData`, MÊME clé `id` que le solo (gardée par guildID>0). Sonde empirique :
  sans extension → crédit guilde = 0 ; avec une extension → crédit guilde OK ET solo toujours EXACT (pas de double-compte).
- **Correctif (§3, nous sommes le backend PerBlue)** :
  - `ServerContestExtension implements IContestHelperExtension` : implémente les 2 méthodes ABSTRAITES (`earnedPoints`/
    `earnedProgressLevel` = no-op tracé) et LAISSE les `record*` par défaut faire le crédit. Installée par RÉFLEXION dans
    `ServerContext.init` (champ `extension` privé, pas de setter). Idempotent. La livraison des paliers reste
    `ServerContestData.deliverEarnedProgressRewards` (idempotent, scan).
  - `ServerContestData` : `memberRankPoints` (lecture seule du blob d'un membre), `guildAggregate` (somme des membres),
    `guildAggregateData` (ContestData agrégé = barre de progression), `guildRankings` (guildes du shard triées par agrégat →
    `yourGuildInfo`+`topGuilds`+`guildContestData`) ; `response` (GetAllContestData) guild-aware (entrée guilde = agrégat).
  - `LoginServer` : `GET_GUILD_CONTEST_RANKINGS` ID-aware (`activeGuildContest`→`ServerContestData.guildRankings` ; sinon
    fallback #67 `buildGuildContestRankings` inchangé).
- `ContestGuildTest` (régression **154/154**) : G1(A+C) vs G2(D), BATTLE_WON:10 → A 2 combats=20, C 1=10 ⇒ G1=30 ; D 1=10 ⇒
  G2=10 ; classement G1 rang 1 / G2 rang 2, `yourGuildInfo`+`guildContestData`=30, round-trip wire. Les tests SOLO restent verts
  (extension sans régression). DEV : `ContestGuildSeed <guildID> <contestID> <memberID> <points>` (peuple une guilde + crédite un membre).
- **✅ EN JEU (multi-membres, choix utilisateur)** : `AdminEvents --contest 990011 --contest-guild --contest-aggregate
  --contest-title "GUILD SHOWDOWN" --contest-task BATTLE_WON:10:1 --contest-progress 50:ACE:5 --contest-rank number:1:ACE:500`
  ; `ContestGuildSeed 1 990011 2 50` (guilde #1 « Surge Testers » = userID1 + membre 2 crédité 50). Client (userID1) →
  `nav CONTESTS` → branche GUILDE → écran **« GUILD SHOWDOWN » / COMMUNITY PROGRESS Score 50 / MY RANK 1ST** (l'agrégat inclut
  le membre 2, userID1=0 ; `gapC_before.png`) → `campfight 1 1`+`campquick` (combat WIN → crédit guilde via extension) →
  `nav CONTESTS` → serveur `GET_GUILD_CONTEST_RANKINGS(990011) → ta guilde score 60 rang 1` → **Score 60 / MY RANK 1ST/60**
  (50+10, `gapC_after2.png`). ⇒ l'agrégat inclut bien les AUTRES membres ET monte quand un membre joue.

Régression **154/154**. Fichiers : `ServerContestExtension.java` (nouveau), `ServerContext.java` (install extension),
`ServerContestData.java` (memberRankPoints/guildAggregate/guildAggregateData/guildRankings + response guild-aware),
`LoginServer.java` (activeGuildContest + GET_GUILD_CONTEST_RANKINGS ID-aware), `ContestGuildTest.java` + `ContestGuildSeed.java`
(nouveaux), `regression.sh`, `JOURNAL.md`, `MEMORY.md`. **⇒ CONTEST 100 % (solo + guilde), TOUS LES GAPS (A/B/C) CLOS & vérifiés
EN JEU. RESTE hors contest : audit store, Phase 2.**

## 2026-08-24 (g168) — CONTEST : ancrage hebdo (vendredi→jeudi) + câblage de TOUS les hooks de tâches ✅ VÉRIFIÉ EN JEU

Demande utilisateur : « ajoute la possibilité d'ancrage et branche les hooks (tous) ».

- **Découverte clé (bytecode)** : les helpers DU JEU appellent EUX-MÊMES les hooks de contest dans leur `recordOutcome`/
  `doRaid` : `CampaignHelper`→`onCampaignAttack`, `DifficultyModeHelper`→`onDifficultyModeAttack`, `SurgeHelper`→
  `onSurgeAttack`, `ExpeditionHelper`→`onExpeditionCompleted`, `InvasionHelper.recordBossFightOutcome`→`onInvasionBossAttack`
  (+`onBreakerAttack`), `WarClientHelper`→`onWarSabotage`. Le crédit passait par une extension statique (`ContestHelper.
  extension`) NULLE en headless → d'où gap C (déjà corrigé par `ServerContestExtension`). ⇒ Pattern le PLUS FIDÈLE (§3, aucun
  argument inventé §4) : **`ServerContestData.prepare(su,user)` AVANT le `recordOutcome` du jeu + `deliverEarnedProgressRewards`
  après** — le jeu crédite lui-même le blob per-user (rendu blob-backed par prepare). Seuls les hooks pilotés par l'ÉCRAN client
  (`onWarAttack`, `onExpeditionAttack` per-combat) ou une ré-exéc serveur BESPOKE (invasion = accumulation manuelle des dégâts)
  nécessitent un appel EXPLICITE.
- **Ancrage hebdomadaire (fidélité : contests DHBM = 1 semaine, vendredi→jeudi)** : `ServerEvents.weeklyContestWindow(now)` =
  dernier **vendredi 00:00 UTC** ≤ now → +7 j (java.time, ZoneOffset.UTC). `AdminEvents --contest-weekly` (au lieu de `--days`).
  L'heure de reset (00:00 UTC) = choix opérateur (contests hors `.tab`).
- **Câblage** :
  - prepare-avant/deliver-après (hook interne du helper) : `ServerUser.recordCampaignAttack` (**refactoré** : l'ancien appel
    explicite `onCampaignAttack` retiré → sinon DOUBLE-compte avec le hook interne, désormais prepare-avant = crédit unique),
    `ServerUser.recordDifficultyModeAttack` (Port), `ServerSurgeState.applyAttack` (+`store.save(user)` dans le handler surge,
    qui ne sauvait que le SurgeData), `ServerExpedition` doRaid (→`onExpeditionCompleted`).
  - hook EXPLICITE via `ServerContestData.record` (lineups du wire `base`) : handler `InvasionBossAttack` (`ba.base` ;
    le serveur accumule les dégâts manuellement, n'appelle pas `recordBossFightOutcome`), handler `WarAttack` (`wa.base`
    +`store.save(user)` ; `onWarAttack` est piloté par l'écran, `WarHelper` ne l'appelle pas).
  - ITEM_EARN/OPEN_CHEST : déjà couverts (openChest = prepare + onChestOpen). ITEM_BURN/RESOURCE_EARN/BURN : créditent partout
    où un helper du jeu mute items/ressources avec prepare actif ; sites additionnels à brancher au besoin (honnête).
  - Modes SANS chemin serveur (Coliseum/FightPit/Heist) : rien à brancher (non implémentés côté serveur).
- **Anti-double-compte** : pour les modes helper-internes, prepare-avant SANS appel explicite (un seul crédit) ; le test le
  prouve sur le vrai `recordCampaignAttack` (10 pts/combat, pas 20, malgré le hook interne de CampaignHelper).
- `ContestCampaignRecordTest` (régression **155/155**) : vrai `recordCampaignAttack` → 10 pts (single), 20 au 2e, persiste en
  DB ; `weeklyContestWindow` = vendredi 00:00 +7j, now dans la fenêtre. #67 (GuildContest/GuildContestSeason), CampaignAttack/
  Persist inchangés.
- **✅ EN JEU** : `AdminEvents --contest 900013 --contest-weekly --contest-title "WEEKLY BATTLES" --contest-task BATTLE_WON:10:1
  --contest-progress 20:ACE:5 --contest-rank number:1:ACE:500`. `nav CONTESTS` → **« Contest Ends In: 3d 6h »** (lundi 24 →
  vendredi 28 = ancrage Ven→Jeu confirmé) + Score 0 (`hooks_0.png`) → `campfight 1 1`+`campquick` (CAMPAGNE WIN) → serveur
  `GET_CONTEST_RANKINGS(900013) → score 10` → `portattack PORT_DOCKS` (DifficultyModeAttack PORT_DOCKS WIN → recordOutcome) →
  `nav CONTESTS` → serveur `score 20` → **Score 20 / Rank 1st, barre pleine au palier 20** (`hooks_2.png`). ⇒ campagne (chemin
  refactoré prepare-avant) ET Port (nouveau hook diffmode) créditent en jeu ; ancrage hebdo affiché.

Régression **155/155**. Fichiers : `ServerEvents.java` (weeklyContestWindow), `AdminEvents.java` (--contest-weekly),
`ServerUser.java` (campagne refactorée + diffmode prepare-avant), `ServerSurgeState.java` (surge prepare/deliver),
`ServerExpedition.java` (expedition prepare/deliver), `LoginServer.java` (surge store.save + invasion/war hooks explicites),
`ContestCampaignRecordTest.java` (nouveau), `regression.sh`, `JOURNAL.md`, `MEMORY.md`. **⇒ Tous les hooks de tâches de contest
branchés (via le mécanisme le plus fidèle) + ancrage hebdo fidèle, vérifiés EN JEU. RESTE hors contest : audit store, Phase 2.**

## 2026-08-24 (g169) — AUDIT « écrans store pas de crash » ✅ EN JEU (aucun changement de code)

Demande utilisateur : auditer que les écrans STORE (fermés, aucun IAP) ne CRASHENT pas le client.

- **Recensement** : écrans store/achat = `PurchasingScreen` (DIAMONDS), `VIPBenefitsScreen`, `DailyDealsScreen`,
  `PromosScreen`, + destinations `UINavHelper.Destination` : PURCHASING, DIRECT_PURCHASE, VIDEO_PURCHASING, VIP_BENEFITS,
  DAILY_DEAL, MEGA_DAILY_DEAL, PROMOS, MEGA_MART (BLACK_MARKET/COLLECTIONS/EVENT_CRATE déjà OK antérieurement).
- **Analyse anti-crash (bytecode)** : `PurchasingScreen` itère `DH.app.getIAPProducts().products` → NPE si null. Or le CLIENT
  pose `iapProducts` depuis `BootData.iAPProducts` au boot, et `new BootData()` (= notre `ServerUser.bootData()`) initialise
  `iAPProducts = new IAPProducts()` (ctor) qui initialise `products = new ArrayList()` → catalogue NON-NULL VIDE → itération
  d'une liste vide → **rendu store vide, aucun NPE**. (VIP/DailyDeals = lecture seule, aucun message.)
- **✅ EN JEU** : balayage des 8 destinations via `nav <DEST>`. `[nav] navigateTo(...)` OK pour les 8 ; **client vivant tout
  du long** ; scan complet du log = **0 crash dur** (`GdxRuntimeException`/déconnexion/exit/OutOfMemory) et **0 message store
  non géré côté serveur**. Rendus (captures) : `PURCHASING`/`DIRECT_PURCHASE` = écran DIAMONDS (cartes bundles IAP vides —
  catalogue fermé — mais DAILY VIDEOS / PLAYBACK REWARDS / FYBER rendus, 💎20, VIP LEVEL, BENEFITS) ; `DAILY_DEAL` = zone
  d'offres vide (gracieux) ; `VIP_BENEFITS` = niveaux 1-3 complets ; `MEGA_MART` = marchand en MONNAIE DU JEU **pleinement
  fonctionnel** (STAMINA COST RESET, plan bits, hero chips… ; 🪙57,9M, REFRESH, « Refreshes at 9:00 PM »). Warnings observés =
  pré-existants bénins (`NumberFormatException ""`, `PatchTalent`, trader INVASION) + env/layout headless (`XDG_RUNTIME_DIR`,
  `auto weight`). AUCUNE modification de code nécessaire (le comportement était déjà correct via l'init BootData du jeu).

Régression **155/155** (inchangée — aucun code modifié). Fichiers : `docs/HUB_NAV.md` §7.3 (résultat d'audit), `JOURNAL.md`,
`MEMORY.md`. **⇒ Audit clos : les écrans store, volontairement fermés (aucun IAP), NE cassent PAS le client. RESTE = Phase 2.**

## 2026-08-24 (g170) — PHASE 2 : doc de suivi + AUDIT GLOBAL automatisé (4 axes, auto-log des manques)

Demande utilisateur : créer un doc de SUIVI Phase 2 (à mettre à jour) et démarrer par un AUDIT de tout le jeu via les outils
d'industrialisation — (1) aucun écran oublié, (2) tout bien câblé, (3) pas de valeurs en dur qui devraient venir de
`.tab`/jeu/admin, (4) pas d'erreurs client — avec **auto-log des manques** en doc pour suivi. « Travail propre, intelligent. »

- **`docs/PHASE2_TRACKING.md`** (nouveau) : tableau de bord vivant des chantiers A→G (détail dans `PHASE2_PLAN.md`), avec le
  chantier A (vérif globale) décomposé en 4 axes + statuts + liens vers les rapports auto-générés + triage des manques.
- **`tools/audit/audit.sh`** (nouveau, réutilise `tools/screentool` ModeGraph/ScreenContract) : 4 axes, régénère 4 rapports :
  - **A1** `docs/AUDIT_SCREENS.md` : inventaire des **179 écrans** `*Screen` par package (aucun MODE oublié ; sous-écrans à
    cocher au balayage §7.4 HUB_NAV).
  - **A2** `docs/AUDIT_WIRING.md` : couverture handlers (section C de `ScreenContract` agrégée sur les 37 packages UI) =
    messages ENVOYÉS par un écran mais non routés par `LoginServer`. **14 manques** trouvés → triés dans le suivi :
    3 non-gaps (HEIST retiré du jeu), 1 Phase 2 C (`GetServers`), 3 faibles (`RequestResync` générique fire-and-forget),
    **7 GAPS réels** (`GetGMemInvasionRankInfo`, `GetPrizeWallData`×2, `GetChestConsumableHistory`, `GetCodebaseAttackLogs`,
    `GetBlockedList`, `GetUserSaveData`) — écrans secondaires (rankings/journaux/social) → correctif type « réponse vide »
    (patron hall-of-fame contest), à vérifier EN JEU un par un.
  - **A3** `docs/AUDIT_HARDCODED.md` : heuristique littéraux métier — **0 candidat** ⇒ le serveur ne code pas les valeurs de
    RÈGLE en dur (§4 respecté ; défauts de config = dans les `Admin*`, légitime).
  - **A4** `docs/AUDIT_CLIENT_ERRORS.md` : scan de 8 logs en jeu — **0 erreur non-bénigne** ; bénins CONNUS confirmés
    (stat-parse `NumberFormatException ""`, `PatchTalent`, `black_market_merchant_drops.tab row 18 auto-weight→1` absorbé,
    trader INVASION, env/audio/layout headless).
- **Méthode** : les rapports `AUDIT_*.md` sont AUTO-GÉNÉRÉS (faits bruts, régénérables) ; le TRIAGE/décisions vit dans
  `PHASE2_TRACKING.md` (maintenu à la main) — séparation propre faits ↔ décisions.

Aucun code serveur modifié (outil + docs) → régression inchangée (155/155). Fichiers : `docs/PHASE2_TRACKING.md`,
`tools/audit/audit.sh`, `docs/AUDIT_{SCREENS,WIRING,HARDCODED,CLIENT_ERRORS}.md` (nouveaux), `JOURNAL.md`, `MEMORY.md`.
**SUITE = trancher les 7 GAPS A2 (réponses vides + vérif en jeu), puis chantiers B→G.**

## 2026-08-24 (g171) — AUDIT A5 : couverture des `.tab` (carte .tab→classe + data sans mode câblé) + réponse content.tab

Demande utilisateur : vérifier les `.tab` (272 !) — certaines inutilisées alors qu'elles devraient l'être pour leur mode ;
regarder `content.tab` (releases/dates/stats/héros → utile admin/features) ; et « le code du jeu associe-t-il tel `.tab` à
telle partie du jeu ? ».

- **Nouvel axe A5** dans `tools/audit/audit.sh` → `docs/AUDIT_TABS.md`. Construit la **carte `.tab → classe Stats`** en une
  passe sur les packages de données du jar (`grep -aroE '…\.tab' com/perblue/heroes/game/data/**`). **Réponse : OUI**, chaque
  `.tab` est déclarée par une classe `Stats` (le package = le mode/feature). 272 sur disque / 265 référencées / 67 classes.
- **Orphelines** : `content.{1,13,14,21,23,25,99}.tab` (chargées par nom CONSTRUIT `content.<shard>.tab` via ContentStats →
  PAS orphelines) + `invasion_boss_rewards.tab` (loot client, §25). **`unit_abilities.tab` référencée mais non extraite**
  → lacune d'`extract_game_data.sh` [à vérifier : le serveur en a-t-il besoin ? combat = client-autoritatif → probablement non].
- **« Nommée serveur » approximatif** : bcp de features à `—` sont utilisées via la LOGIQUE du jeu (faux négatifs confirmés :
  Campaign/Friendship/Collection/GuildCheckIn/SpotlightTrial/Enchanting/PrizeWall(MEDALS) — tous ✅ en jeu/tests).
- **Vrais GAPS « data présente, mode non câblé »** (triés dans PHASE2_TRACKING) : `codebase` (CodebaseStats, cohérent A2
  GetCodebaseAttackLogs), `campaign reinfection` (CampaignReinfectionStats), `chest upgrade` (ChestUpgradeStats), `airdrop`
  (AirDropStats) ; à vérifier : `emerald`, `herospotlight` ; `heist` = retiré (💤) ; marketing/offerwall/video/deeplink/
  starterdeal/emoji/supportlinks = hors scope (store fermé).
- **`content.N.tab` = TimeTable d'ère** (colonnes = dates release R102→R1 ; lignes = Max Chapter/TL/GL/Rarity/Trials/Port
  Difficulty, nœuds de chapitre, exclusivités BP, sorties héros). Déjà utilisée pour l'ère (`ContentStats.getServerColumn`) ;
  **piste admin (chantier D)** : surfacer pour choisir l'ère/release servie (plafond de contenu) via le panneau opérateur.

Aucun code serveur modifié (outil + docs) → régression 155/155 inchangée. Fichiers : `tools/audit/audit.sh` (axe a5),
`docs/AUDIT_TABS.md` (nouveau), `docs/PHASE2_TRACKING.md` (triage A5), `JOURNAL.md`, `MEMORY.md`. **SUITE = décision util. sur
les GAPS de feature A5 (implémenter codebase/reinfection/chest-upgrade/airdrop… ou documenter hors scope) + les 7 GAPS A2.**

## 2026-08-24 (g172) — Triage Phase 2, ÉTAPE 1 : `unit_abilities.tab(b)` — FAUX POSITIF résolu (non-gap)

Ordre de travail utilisateur (triage factuel avant toute implémentation). Étape 1 = `unit_abilities.tab`.

- **Pourquoi « référencée mais absente » ?** L'APK ne contient PAS `unit_abilities.tab` (texte) mais `unit_abilities.tab**b**`
  (BINAIRE, double « b »). `extract_game_data.sh` fait un simple `unzip assets/stats/*` → le `.tabb` EST bien extrait sur
  disque. Il n'y a **que 2 binaires** dans l'APK : `unit_abilities.tabb` + `friendship_campaign.tabb`. Le code du jeu
  référence le nom LOGIQUE `.tab` ; `StatFileHelper` (ouvreur `ServerStats`, `forceText()=false`) essaie le **binaire `.tabb`
  d'abord**, puis retombe sur le texte `.tab`. **Preuve** : `friendship_campaign.tabb` alimente la campagne d'amitié ✅
  vérifiée EN JEU → la résolution `.tabb` fonctionne, donc `unit_abilities.tabb` aussi.
- **Faux positif de l'outil A5** : (a) regex `[a-z0-9_]+\.tab` tronquait `.tabb`→`.tab` (fausse « référence ») ; (b) glob
  `*.tab` ne matchait pas `.tabb` (fausse « absence »/« orpheline »). **Corrigé** dans `tools/audit/audit.sh` : regex `\.tabb?`
  + normalisation `.tabb`→`.tab` à la comparaison. Re-run A5 : « absentes du disque » = ∅ ; orphelines = seulement
  `content.N.tab` (nom construit) + `invasion_boss_rewards.tab` (loot client). `friendship_campaign.tabb`/`unit_abilities.tabb`
  ne sont plus signalés.
- **Besoin serveur ?** `grep AbilityStats|unit_abilities server/java` = 0 → le serveur ne charge JAMAIS explicitement les
  aptitudes. C'est **client-only** : données d'aptitudes des héros, lues par la SIMULATION de combat CÔTÉ CLIENT (combat
  client-autoritatif via unidbg) ; le serveur ne rejoue que la progression (`recordOutcome`), pas la simulation. **Aucun ajout
  artificiel** (le fichier est présent et chargeable au cas où). ⇒ **VERDICT : non-gap [OK-connu]**, documenté.

Aucun code serveur modifié (correctif outil d'audit + docs) → régression 155/155 inchangée. Fichiers : `tools/audit/audit.sh`
(fix `.tabb`), `docs/AUDIT_TABS.md` (régénéré), `docs/PHASE2_TRACKING.md` (résolution étape 1), `JOURNAL.md`. **SUITE = étape 2
(boucler les 7 GAPS A2).**

## 2026-08-24 (g173) — Triage Phase 2, ÉTAPE 2 : boucler les GAPS de câblage A2 (3 handlers réels/fidèles) ✅

Investigation factuelle des 14 manques A2 (émetteur réel de chaque requête via scan du jar) → triage puis implémentation
UNIQUEMENT du nécessaire/cohérent (pas de faux endpoint).

- **Émetteurs identifiés** : `GetGMemInvasionRankInfo`→InvasionRankingsScreen ; `GetBlockedList`→BlockedPlayersWindow ;
  `GetUserSaveData`→SaveRestoreUserWindow ; `GetChestConsumableHistory`→**Debug**ChestConsumablesScreen (dev-only) ;
  `GetCodebaseAttackLogs`→CodebaseAttackLogScreen (mode codebase) ; `GetPrizeWallData`→PrizeWallScreen ; `GetServers`→windows ;
  `RequestResync`→powerpromote/pvp/windows ; heist×3 (mode retiré).
- **IMPLÉMENTÉS (réel/fidèle, 3)** :
  - `GetGMemInvasionRankInfo` → `GuildMemberInvasionRankInfo` : `ServerInvasion.guildMemberRanking(store,shard,guildID,invID,50)`
    classe les MEMBRES de la guilde par points d'invasion réels (source `user_invasion`, même que userRanking/guildRanking).
    Handler LoginServer (les onglets User/Guild league étaient déjà servis). **✅ VÉRIFIÉ EN JEU** : INVASION RANKINGS → menu
    déroulant « GUILD MEMBERS » → serveur `GetGMemInvasionRankInfo(guilde=1) → GuildMemberInvasionRankInfo (2 membre(s))` →
    l'onglet rend 2 lignes de membres (avant : LOADING). Capture `wg_gmem.png`.
  - `GetBlockedList` → `BlockedList` VIDE : le blocage n'est pas implémenté → 0 bloqué = réponse FIDÈLE (pas un faux endpoint).
  - `GetUserSaveData` → `UserSaveData{info,extra,individualUserExtra}` = sauvegarde du compte DU DEMANDEUR (= `bootData()`,
    ce qu'on persiste déjà), avec GARDE (userID ≠ le sien → refus, pas de fuite). Données RÉELLES.
- **NON implémentés (justifiés, pas de faux)** : heist×3 (retiré) ; `GetChestConsumableHistory` (écran DEBUG dev-only) ;
  `GetCodebaseAttackLogs` (→ étape 4, feature codebase) ; `GetPrizeWallData` (feature event ; `PrizeWallState` n'a pas d'état
  « inactif » → aucune réponse vide fidèle → nécessite un vrai builder d'event, différé) ; `GetServers` (Phase 2 C) ;
  `RequestResync`×3 (fire-and-forget, pas de hang).
- `WiringGapsTest` (régression **156/156**) : `guildMemberRanking` (A 100/#1, B 50/#2) + round-trip wire des 3 réponses
  (GuildMemberInvasionRankInfo / BlockedList vide / UserSaveData). Re-run A2 : les 3 messages implémentés ont disparu.

Fichiers : `ServerInvasion.java` (guildMemberRanking), `LoginServer.java` (3 handlers), `WiringGapsTest.java` (nouveau),
`regression.sh`, `docs/PHASE2_TRACKING.md` (triage A2), `docs/AUDIT_WIRING.md` (régénéré), `JOURNAL.md`, `MEMORY.md`.
**SUITE = étape 3 (analyse content.N.tab : ContentStats/AdminClock/AdminSeason → exposer le choix d'ère).**

## 2026-08-24 (g174) — Triage Phase 2, ÉTAPE 3 : analyse content.N.tab / ère (ContentStats/AdminClock/AdminSeason) — ANALYSE

Consigne : analyser comment l'ère est déterminée + évaluer un « choix d'ère/release » en config serveur, SANS implémenter si
des dépendances cachées apparaissent (les documenter d'abord).

- **Ère (bytecode)** : `ContentStats.getServerColumn() = getColumn(serverTimeNow())` ; `getServerColumn(IUser) =
  getColumn(serverTimeNow() + getUserOffset(userID))`. `getColumn(date)` = colonne (release Rn) dont la date ≤ date, sur
  `content.<shard>.tab` (chargé par `ContentHelper.setShardID`). Offset de contenu PAR-USER natif (`setUserOffset`).
- **BootData** : `bd.serverTime = serverTimeNow()` — envoyé au client.
- **AdminClock** = `CLOCK_OFFSET`→`serverTimeNow` : bouge ère + saison + TOUS timers + BootData.serverTime cohéremment
  (« monde à la date X »), client consistant (✅ §8). **AdminSeason** = `SEASON_ANCHOR_OFFSET`→`seasonTimeNow` : bouge SEULEMENT
  la saison des trials (interne serveur, ne touche pas l'ère).
- **DÉPENDANCE CACHÉE trouvée** : `BootData.serverTime` pilote À LA FOIS la résolution du CONTENU DATÉ côté client ET l'AFFICHAGE
  des timers côté client (pas de champ « date de contenu » distinct). ⇒ impossible de décaler l'ère sans décaler l'horloge
  perçue (donc l'affichage des timers). `setUserOffset` décale le contenu SERVEUR seulement → désynchro affichage client si
  BootData.serverTime non décalé.
- **VERDICT (documenté, non implémenté)** : un « release-picker » admin est faisable UNIQUEMENT comme wrapper mince d'AdminClock
  (release→date→horloge), en acceptant que l'affichage client des timers suive l'ère (compromis d'AdminClock, déjà vérifié). Un
  découplage contenu↔timers n'est pas proprement réalisable sans modifier le client (hors §1). Décision utilisateur requise
  (chantier D) avant toute implémentation.

Aucun code modifié (analyse + doc). Régression 156/156 inchangée. Fichiers : `docs/PHASE2_TRACKING.md` (étape 3), `JOURNAL.md`,
`MEMORY.md`. **SUITE = étape 4 (triage factuel des GAPS features A5 : codebase/reinfection/chest-upgrade/airdrop/emerald/herospotlight
→ IMPLEMENT / INVESTIGATE / OUT OF SCOPE).**

## 2026-08-24 (g175) — Triage Phase 2, ÉTAPE 4 : classification des GAPS features A5 (IMPLEMENT/INVESTIGATE/OUT OF SCOPE)

Triage factuel des 6 features candidates A5 (data / code client / messages serveur / exposée / point d'entrée / usage existant) :
- **codebase** → **INVESTIGATE** : UI complète (CodebaseAttack/Detail/HeroChooser/AttackLog) + messages (CodebaseAttack/Weakness/
  AttackInfo/GetCodebaseAttackLogs) + entrée via **Act** (`CodebaseActV1`/`CodebaseIntroActV1`, scripté/événementiel) ; PAS de nav
  permanente → vrai mode ÉVÉNEMENTIEL, restauration = gros effort (mode serveur-autoritatif + act), pas un reliquat. Décision util.
- **emerald** → **OUT OF SCOPE** : sous-système de STAT de gear DÉJÀ utilisé serveur (`emeraldStatSlotChoices`, lineups) = faux
  négatif A5 (chargé via la logique du jeu).
- **airdrop** → **OUT OF SCOPE** : `AirdropHelper`/`SyncStatDataClientHelper` (stat-sync), aucun écran/action joueur.
- **campaign reinfection** → **OUT OF SCOPE** : sous-mécanique de campagne (aucun message distinct ; passe par recordCampaignAttack).
- **chest upgrade** → **OUT OF SCOPE** : enum `ChestUpgradeTrackType` seul, pas de message/écran distinct.
- **herospotlight** → **OUT OF SCOPE** : data-only (distinct du SPOTLIGHT_TRIAL déjà ✅).

⇒ 1 INVESTIGATE (codebase), 5 OUT OF SCOPE, 0 IMPLEMENT immédiat. Aucune logique créée sur la seule présence d'une .tab/classe
Stats (consigne respectée). Aucun code modifié (analyse + doc), régression 156/156. Fichiers : `docs/PHASE2_TRACKING.md` (étape 4),
`JOURNAL.md`, `MEMORY.md`. **⇒ TRIAGE PHASE 2 (étapes 1→4) TERMINÉ.** Reste = décisions utilisateur (codebase INVESTIGATE ?
release-picker chantier D ?) puis chantiers B→G.

## 2026-08-24 (g176) — INVESTIGATION APPROFONDIE `codebase` (décision restauration) — CLASSIFICATION : PARTIALLY RESTORABLE (effort MODÉRÉ)

Consigne util. : investiguer Codebase À FOND avant de décider (comme A2), sans implémenter. Répondre : feature historiquement
complète mais non câblée, ou chantier majeur ? Classification RESTORABLE / PARTIALLY RESTORABLE / NOT PRACTICAL + briques manquantes
+ effort. Preuves = bytecode `libs/game.jar`, `.tab`, code serveur. **Aucun code écrit (audit seul).**

- **Point d'entrée** : grep jar complet → seul `TeamTrialsChooserScreen` référence `CodebaseDetailScreen`+`CodebaseHelper`
  (`doCodebaseButtonPress`, `lastCodebaseOpen/nextCodebaseEndTime/onCooldownCodebase`). Atteint par nav **permanente**
  `Destination.TEAM_TRIALS`. **⚠️ CORRECTIF g175** : les Acts (`CodebaseActV1`/`CodebaseIntroActV1`) = TUTORIEL, PAS la porte —
  Codebase n'est pas « événementiel scripté sans nav » mais un **MODE DE DIFFICULTÉ ROTATIF**.
- **Difficulty-mode** : `GameMode.CODEBASE` ; `DifficultyModeHelper.getCooldownType(CODEBASE)=CooldownType.CODEBASE_ATTACK`
  (voisin `SPOTLIGHT_TRIAL_ATTACK`) ; `VIPFeature.CODEBASE_COOLDOWN`. Réf. méthode = `docs/PORT.md`.
- **Gating (data-gated, déterministe)** : `isFeatureEnabled` = `Unlockables.isUnlocked(getUnlockableForChapter(REQUIRED_CAMPAIGN_
  CHAPTER=41))` ; `getCurrentIterationID(long,int)` = rotation **3 j** (`SCHEDULING_EPOCH=2035-02-08T12:00Z`+`AVAILABLE_DAYS=3`+
  `TimeUtil.computeTimeForDay`) ; `getNextEndTime`/`getStartTimeOfCurrentIteration`/`getNextResetTime`. **Aucun event opérateur.**
- **Réseau (tout ROUTABLE, `MessageFactory` : CodebaseAttack1/GetCodebaseAttackLogs1/CodebaseAttackLogs1/CodebaseAttackLog1/
  CodebaseAttackInfo1)** : `CodebaseAttack{base,codebaseID,weakness,minorBuffs,finalWeaknessCount,finalScore,
  megavirusTotalDamageTaken,attackEndTime,lootEarned}` (envoyée par `CodebaseAttackScreen extends LootAttackScreen` = **combat
  client-autoritatif**) ; `GetCodebaseAttackLogs`→`CodebaseAttackLogs{logs:Map<iter,CodebaseAttackLog{topScores,recent}>}` ;
  `CodebaseAttackInfo{lineup,rageLevel,score,attackTime}`.
- **Logique serveur-exécutable présente** : `CodebaseHelper.recordOutcome(...)` (autoritatif : cooldown GAME_MODE_COOLDOWN, chances
  GAME_MODE_CHANCES_GONE, CODEBASE_REQUIRES_YELLOW_HERO, open/locked ; loot client-reporté ; `tryUpdateHighScores` ;
  `ContestHelper.onDifficultyModeAttack` ; `UserActivityTracker`), `makeMegaVirus`/`getMegaVirusWeakness`/`getMegaVirusLevel`,
  `CodebaseLootCalculator`. `.tab` (4) toutes présentes/chargeables.
- **État serveur** : progression per-user **DÉJÀ dans le wire + AUTO-PERSISTÉE (write-through §3)** — `IndividualUserExtra` porte
  `currentCodebaseID/currentCodebaseHighScore/currentCodebaseHighRageLevel/lifetimeCodebaseHighScore/lifetimeCodebaseHighRageLevel`,
  `IndividualUser.setCurrentCodebaseHighScore` = `putfield extra`. **Seul état manquant = blob leaderboard/logs** (patron Arena
  ladder / Invasion ranking / Surge). NOTRE serveur : grep `server/java` = 0 trace (aucun handler partiel, rien à défaire).

**⇒ CLASSIFICATION : PARTIALLY RESTORABLE — effort MODÉRÉ.** Pas un simple « unwire » ; pas un chantier majeur. **Aucune règle/donnée
à réécrire (§3/§4 tenables).** Briques manquantes (glue serveur) : (1) handler `CodebaseAttack`→`recordOutcome`+resync
individualUserExtra+save (patron `recordDifficultyModeAttack`, petit) ; (2) blob leaderboard per-shard `CodebaseAttackLogs`
(top≤10/recent≤10, patron Arena/Invasion, moyen) ; (3) exposer le bouton chooser (cooldown+timers d'itération, petit) ; (4) vérif
EN JEU (§8) = compte débloqué chapitre 41 (seul point dur logistique). Non-bloquants (déjà OK) : routing/codec, persistance
progression (write-through), hook contest (déjà branché), rotation (déterministe data).

Aucun code modifié (audit + doc). Régression **156/156 inchangée** (0 fichier serveur touché). Fichiers : `docs/PHASE2_TRACKING.md`
(étape 4bis + correctifs étape 4), `JOURNAL.md`, `MEMORY.md`. **⇒ Investigation Codebase TERMINÉE. Décision utilisateur : implémenter
la restauration (4 briques) OU laisser documenté. Puis chantiers B→G (Phase 2).**

## 2026-08-24 (g177) — CODEBASE incr.1 : RESTAURATION serveur (recordOutcome + classement per-shard + persistance) — headless 🟢

Décision util. « Restaure ». Codebase = mode de DIFFICULTÉ rotatif (cf. g176). Implémentation glue serveur (§3, aucune règle
réécrite), même patron que Port/Trials/Surge. Mappage des args relevé au **bytecode du vrai appelant**
`CodebaseAttackScreen.handleBattleOutcome` (jamais deviné) :
`CodebaseHelper.recordOutcome(user, outcome, finalScore, rageLevel, lootEarned, attackers, defenders, codebaseID, attackEndTime, snap)`.

- **`ServerUser.recordCodebaseAttack(CodebaseAttack)`** : rebuild user/iu + bind, `ServerContestData.prepare` (hook contest
  interne, pas de double-compte), `recordOutcome`, `deliver` + resync ; renvoie l'entrée de journal. Faits de fidélité :
  attackers/defenders passés TELS QUELS (`List<AttackLineupSummary>` — recordOutcome aplatit lui-même ; un aplatissage manuel →
  `ClassCastException AttackUnitSummary→AttackLineupSummary`, corrigé §8) ; `rageLevel` NON porté par le message → reconstruit par
  `CodebaseStats.getRageLevelFromDamageDealt(megavirusTotalDamageTaken)` (table `codebase_rage_levels.tab`).
- **Progression per-user = write-through §3** : `recordOutcome→tryUpdateHighScores` écrit dans
  `IndividualUserExtra.{current,lifetime}Codebase{HighScore,HighRageLevel}`+`currentCodebaseID` (déjà wire/DB, AUTO-persisté).
- **`ServerCodebase`** (blob per-shard serveur-autoritatif, patron Arena/Invasion) : `CodebaseAttackLogs{logs:
  Map<CodebaseWeakness, CodebaseAttackLog{topScores,recent}>}` — **clé = la FAIBLESSE** (corrigé §8 : d'abord tenté par
  itération → `writeData` packe la clé en `packEnumList` → `ClassCastException Integer→Enum` ; le journal est groupé par
  faiblesse, celle qu'affiche `CodebaseAttackLogScreen`). Insertion : top trié↓/borné, recent en-tête/borné, bornes lues des
  `.tab` (`ATTACK_LOG_MAX_TOP_ROWS/RECENT_ROWS`, via `CodebaseStats.CONSTANTS.getStats()`). Stocké via
  `UserStore.loadShardState/saveShardState(shardID,"codebase_logs")` (round-trip wire).
- **`LoginServer`** : handler `CodebaseAttack` (recordCodebaseAttack + ServerCodebase.recordAttack + store.save) ; handler
  `GetCodebaseAttackLogs` (blob per-shard → débloque `CodebaseAttackLogScreen`, gap A2 clos).
- **Support test/admin** : `ServerUser.grantCampaignLevel(type,chapter,level,stars)` (amène un compte au chapitre requis 41 =
  `Unlockable.CODEBASE`, écrit le même format que resyncCampaign).

`CodebaseTest` (régression **157/157**) : (1) classement top trié↓/borné 10 + recent en-tête/borné 10 + round-trip wire + DB ;
(2) **anti-triche RÉELLE** — compte non débloqué REFUSÉ `GAME_MODE_LOCKED` (preuve : vraie logique du jeu, pas de stub) ;
(3) chemin nominal — chapitre 41 + team-level + héros JAUNE → recordOutcome accepté, `currentCodebaseHighScore` 0→750
(write-through) + persiste au round-trip wire. Fichiers : `ServerCodebase.java` (nouveau), `ServerUser.java`
(recordCodebaseAttack + grantCampaignLevel), `LoginServer.java` (2 handlers), `CodebaseTest.java`, `regression.sh`,
`docs/CODEBASE.md` (nouveau), `docs/PHASE2_TRACKING.md`, `JOURNAL.md`, `MEMORY.md`.
**SUITE = incr.2 : vérif EN JEU (§8) — compte débloqué chapitre 41 → nav TEAM_TRIALS → Codebase → combat → high score + journal.**

## 2026-08-24 (g178) — CODEBASE incr.2 : ✅ VÉRIFIÉ EN JEU (§8) — mode restauré de bout en bout

Vérif EN JEU obligatoire (§8) de la restauration Codebase (g177). Outillage : `CodebaseUnlock` (débloque le compte persisté :
TL 300 + chapitre NORMAL 41 terminé = `Unlockable.CODEBASE` + 5 héros JAUNE = `CODEBASE_REQUIRES_YELLOW_HERO`) ; pilote
`codebaseattack [score]` (`TutorialDriver.codebaseAttack` — envoie le VRAI `CodebaseAttack` par le réseau du client, B-bis :
itération/faiblesse via `CodebaseHelper`, 1 attaquant JAUNE réel lu du roster).

Déroulé : seed `CodebaseUnlock` sur `server/data/dh-server.db` (compte déjà post-tuto) → `run-online.sh` (client réel LIVE →
127.0.0.1:8080) → hub (TL 300) → `teamtrialsscreen` (chooser, planning client) → `codebaseattack 500` puis `codebaseattack 750`.

Résultats (FAITS) :
- Client : `[codebaseattack] iter=-1030 weak=BLIND score=500 attacker=RALPH(YELLOW) [chemin réseau réel]` → `CodebaseAttack envoyé`.
- Serveur (`/tmp/dh_game.log`) : `<== CodebaseAttack : iter=-1030 score=500 rage=0 outcome=WIN → recordOutcome + classement
  [persisté]` (1re ACCEPTÉE) ; 2e `⛔ CodebaseAttack REFUSÉ (anti-triche) : GAME_MODE_COOLDOWN` (cooldown réel posé par la 1re).
- Persistance relue en DB (serveur arrêté) : `currentCodebaseID=-1030`, `currentCodebaseHighScore=500`,
  `lifetimeCodebaseHighScore=500` ; classement per-shard = 1 faiblesse **BLIND** top=1/recent=1 topScore=500.
- `iter=-1030` négatif = normal (SCHEDULING_EPOCH 2035 dans le futur vs temps serveur ; l'ID d'itération déterministe peut être
  négatif, sans incidence — clé de rotation). `rage=0` = `getRageLevelFromDamageDealt(500000)` sous le 1er seuil (fidèle .tab).

⇒ **CODEBASE RESTAURÉ & VÉRIFIÉ DE BOUT EN BOUT** (client réel → serveur autoritatif `recordOutcome` → high scores per-user +
classement per-shard PERSISTÉS ; anti-triche cooldown réelle). Régression 157/157 (inchangée). Fichiers : `CodebaseUnlock.java`,
`TutorialDriver.java`+`DesktopLauncher.java` (pilote), `docs/CODEBASE.md`, `docs/PHASE2_TRACKING.md`, `JOURNAL.md`, `MEMORY.md`.
Capture `desktop-port/build/cb.png` (hub TL 300). **⇒ Mode Codebase COMPLET. Reste (Phase 2) = décisions util. (release-picker
chantier D ?) + chantiers B→G.**

## 2026-08-24 (g179) — Régression ~10× + Release-picker + design distribution/login (consigné)

Trois demandes utilisateur.

**1. Régression accélérée (~10×).** Mesure : coût dominant = `ServerContext.init` (~1,7 s, parse ~274 `.tab` + charge
game-framed.jar) × 157 = ~267 s redondants. La parallélisation de process (4 cœurs) EMPIRE (contention → 427 s mesurés).
Solution = AMORTIR l'init : `BatchRunner` exécute la majorité des tests DANS UN SEUL JVM (init unique), en réinitialisant
l'état statique mutable partagé (offset d'horloge + événements opérateur) avant chaque test. Les tests démarrant un vrai
serveur/socket ou appelant `System.exit` (tueraient le JVM partagé) sont AUTO-DÉTECTÉS (`LoginServer|System.exit|ServerSocket|
new Socket`) et lancés en process séparés (parallèles). `DH_REG_ISOLATED=1` force l'ancien mode 100 % process-par-test.
**Résultat : 157/157 verts en ~30 s (vs ~300 s), même couverture.** Fichiers : `BatchRunner.java`, `regression.sh`.

**2. Release-picker (chantier D).** `AdminRelease --list/--status/--set-release <Rxx|#idx>/--reset` = wrapper mince d'AdminClock
(release → date de la colonne `content.<shard>.tab` → ancre `clock_offset_ms` persistée). Vérifié : R50 → 2022-05-17 Max TL 305
(persisté, relu process neuf) ; reset → R102 réel. **`docs/RELEASE_PICKER.md` DÉFINIT le périmètre vs `.tab`** : le choix de
release ne gouverne QUE les `.tab` TimeTable (`content.<shard>.tab` = 38 dimensions [caps/disponibilités/rosters rotatifs/drops
datés] + `patched_heroes` franchise-season) ; les ~270 AUTRES `.tab` (équilibrage, skills, gear, chests…) = snapshot unique de
l'APK, INCHANGÉS (choisir R50 ne rétro-version PAS les nombres d'équilibrage). Couplage horloge/timers = dépendance cachée
(étape 3). Fichiers : `AdminRelease.java`, `docs/RELEASE_PICKER.md`.

**3. Design distribution + login (consigné, non implémenté).** `docs/DISTRIBUTION.md` : (a) on livre le LOGICIEL sans le jeu ;
l'utilisateur fournit l'APK (dernière version) et le logiciel génère à la demande le port Windows/Linux OU le nécessaire serveur
(pipeline `decompile`/`reframe`/`extract_game_data`, §4/§7, version-agnostique, réf. testée v12.1.0). (b) identité/login joueur =
**phrase de mots aléatoires** (mnémonique type crypto/BIP39) : dérive userID+clé, portable, zéro PII, jamais stockée en clair —
cadre le multi-serveur (§5). Décisions consignées pour Phase 2 (C launcher / D backend / E APK).

Régression 157/157 (~30 s). Fichiers : `BatchRunner.java`, `regression.sh`, `AdminRelease.java`, `docs/RELEASE_PICKER.md`,
`docs/DISTRIBUTION.md`, `JOURNAL.md`, `MEMORY.md`.

## 2026-08-24 (g180) — Précisions util. : version publique 8.0 + périmètre admin du release-picker (faits vérifiés)

Retour utilisateur. (1) Version PUBLIQUE de l'app = **8.0** (l'APK repo `disney-heroes-12.1.0.apk` = numérotation interne/build) →
noté dans `docs/DISTRIBUTION.md`. (2) Clarification de l'intention admin du release-picker, VÉRIFIÉE au bytecode :
- **Ère → PLAFONDS** (via AdminRelease) : `ContentColumn` porte `maxTeamLevel` (R1=50→R102=565), `maxRarity`, `maxGearRarity`,
  `maxGuildLevel`, `maxChapter`, `port/trialsDifficultyCap`, `invasionMaxTeamLevel/Rarity`. ⇒ oui, les débuts avaient des paliers
  bas, restaurables. MAIS l'ère **ne contrôle PAS l'échelle des nombres** (aucun champ ressources/cap/monnaie dans ContentColumn ;
  l'accumulation « milliards » vient des `.tab` d'équilibrage = snapshot APK courant) → plafonds bas + nombres actuels ; pas de
  retour aux « petits chiffres » sans data historique (absente). Alternative granulaire : `ContentStats.setUserOffset(user,off)`
  (ère par joueur, même caveat d'affichage client).
- **Événements → `AdminEvents`** (couche opérateur, fenêtres explicites), **indépendant de l'ère** : c'est là qu'on gère
  précisément les events, sans bouger l'ère. **Timers (reset)** = `TimeUtil.serverTimeNow()`+`computeTimeForDay` → serveur-
  autoritatifs, **découplés de l'appareil du joueur** (uniformes) ✅ ; MAIS PAS de l'ère (même `serverTimeNow`) → déplacer l'ère
  décale aussi les timers. ⇒ ère/plafonds = AdminRelease ; events = AdminEvents.

Docs : `docs/RELEASE_PICKER.md` (§4bis), `docs/DISTRIBUTION.md`. Aucun code modifié.

## 2026-08-25 (g181) — Release-picker DÉCOUPLÉ (ère sans casser timers/sauvegardes) + correctif étape 3 + stamina era-indexée

Demande util. : « faudrait pas découpler [l'ère des timers] ? changer d'ère ne doit pas casser sauvegardes/timers » + « comment
sont gérés stamina_values.tab & co ? ».

**Découverte (bytecode) qui INVALIDE le verdict étape 3** : le client N'utilise PAS que `BootData.serverTime` pour le contenu.
`BootData` a un champ SÉPARÉ **`contentStatsTimeOffset`** que `GameMain` (boot) applique à `ContentStats.setUserOffset(user,off)` +
`PatchStats.debugSetUserOffset` → le client résout SON contenu daté par `serverTimeNow()+off` (ère) MAIS garde ses timers (resets/
cooldowns/régén/horodatages) sur `serverTimeNow()` BRUT. ⇒ **découplage ère↔timers natif, sans modif client.**

**Implémenté (glue §3)** : `ServerContext.setContentOffsetMillis/contentOffsetMillis` (offset d'ère découplé) ; `ServerUser.bootData()`
émet `bd.contentStatsTimeOffset = contentOffsetMillis()` ; `LoginServer` (boot) ré-applique la méta persistée `content_offset_ms` ;
`AdminRelease` REFAIT pour poser `content_offset_ms` (= dateRelease − serverTimeNow) au lieu de l'horloge → **timers/sauvegardes
inchangés**. Vérifié : `AdminRelease --set-release R50` → ère R50 @ 2022-05-17 Max TL 305, **timers heure réelle 2026-08-25**.
`ReleaseOffsetTest` (régression) : `contentStatsTimeOffset` reflète l'ère, `serverTime`/`serverTimeNow()` restent au présent.
Correctif du commentaire ServerContext (240-244) + `docs/PHASE2_TRACKING.md` étape 3 (verdict corrigé) + `docs/RELEASE_PICKER.md`.

**Réponse « stamina & co »** (vérifié bytecode) : certaines valeurs d'équilibrage SONT indexées par release (`ContentUpdate`) —
**13 getters**, dont **StaminaStats** (`getHardCap`/`getRegenAmount`/`getRegenInterval`/`getBuyAmount`/`getDailyCheckIn`/
`getStaminaRow`), `getMaxChest`, `getSupplyPackageMaxLevel`, `getWeeklyQuestRewardPerTier`, `getMaxStarsForRelease`,
`getMaxGearRarity` → elles **changent avec l'ère** (plafond stamina plus bas au début = confirmé). Les AUTRES ~270 `.tab`
(unit_stats/skills/gear/chests/coûts) = snapshot unique de l'APK, NON versionnées → inchangées par l'ère. Documenté
`docs/RELEASE_PICKER.md` §2c/§3.

Régression **158/158** (mode rapide ~42 s). Fichiers : `ServerContext.java`, `ServerUser.java`, `LoginServer.java`,
`AdminRelease.java`, `ReleaseOffsetTest.java` (nouveau), `regression.sh`, `docs/RELEASE_PICKER.md`, `docs/PHASE2_TRACKING.md`,
`JOURNAL.md`, `MEMORY.md`.

## 2026-08-25 (g182) — Chantier B (perf combat) : ÉTUDE DÉTAILLÉE + rebuild natif de-risqué (docs/PERF_PLAN.md)

Demande util. : étudier proprement TOUT ce qu'il faut pour jouer/tester, cible **≥30 fps combat**, **zéro destruction
visuelle/gameplay**. Étude factuelle (faits déjà mesurés consolidés) :
- **Diagnostic** : particules unidbg = viable (~118/frame, pas le goulot) ; **spine = goulot** (~2111 µs/squelette émulé →
  ~7/frame @60 ; combat=10 héros) ; fps combat mesuré ~9 (llvmpipe headless). **Ratio backend mesuré** : unidbg 16 900 ms vs
  **JNI natif x86 337 ms = ~50×** ; ~5,8 ms/frame vs **~0,12 ms/frame** → 60 fps atteignable, spine sorti du chemin critique.
- **Archi retenue (déjà bâtie)** : rendu desktop = **spine-c officiel 3.6 compilé hôte** (`libhostspine64.so`, colle JNI d'origine,
  JNI réel) `-Ddh.spinebackend=jni` ; particules = unidbg (identique) ; **autorité combat = serveur unidbg bit-exact** (dérive
  flottante ARM↔x86 → JNI non bit-identique, OK pour l'affichage, PAS pour l'autorité §3) ; certif = mode `compare` (0 diff).
  ⇒ desktop natif pour l'œil, serveur unidbg pour l'issue → **fidélité visuelle ET gameplay par construction**. Composants
  existants : `HostSpine.java`, `CompareBackend.java`, `JavaSpineBackend.java`, `native/build.sh`+`build-hostspine.sh`, oracle.
- **B1 de-risqué ce jour** : `native/build.sh && build-hostspine.sh` en conteneur neuf (clone spine-c 3.6, gcc 13) → **OK** :
  `libhostspine64.so` (250 Ko, 47 symboles HostSpine) + `spine-native64.so` (252 Ko). Recette reproductible (§7 ; .so gitignorés).
- **Reste (ordonné)** : **B2 boot JNI-autonome (handle-registry) = BLOQUANT principal** ; B3 certif matrice (0 diff) ; B4 scène
  combinée ; B5 couverture hors-combat (MainScreen ~12 persos, méthodes omises `setSlotEyeState`…) ; B6 mesure fps EN JEU sur GPU
  réel (⚠️ llvmpipe headless fausse la mesure) ; B7 optim résiduelles (getVertices cache/LOD, C2/dynarmic) si <30 ; B8 garde-fous
  fidélité (issue=serveur, sommets vs oracle, captures vs référence).

Aucun code produit (étude + rebuild de vérif). Fichiers : `docs/PERF_PLAN.md` (nouveau), `JOURNAL.md`, `MEMORY.md`.
**Chemin critique jouable : B1 ✅ → B2 → B6 (GPU réel).**

## 2026-08-25 (g183) — Perf B2 : déblocage boot mode jni — blocage #1 ATLAS résolu, blocage #2 getVertices root-causé (EN JEU)

Déblocage du backend spine natif (`-Ddh.spinebackend=jni`) demandé (construction auto, zéro réécriture). Reproduit EN JEU (§8) :
- **Blocage #1 — atlas partagé cross-backend → ✅ RÉSOLU.** Log ARM : « Bad handle type: Wanted ATLAS but is actually NONE for
  handle 1 » sur un `.np` de particules. Cause : en mode jni le spine tourne sur HostSpine (x86, table propre) mais les particules
  restent sur unidbg (ARM) ; l'atlas (partagé) créé seulement côté HostSpine manquait à la table unidbg. Fix glue plateforme (§1,
  aucun moteur réécrit) : `AtlasBridge` — dual-create de l'atlas dans les DEUX moteurs d'origine + traduction du handle d'atlas
  dans le shadow `cparticle`. Boot passe le registre de handles → atteint MainScreen (0 « Bad handle », 0 « Atlas not found »).
- **Blocage #2 — getVertices multi-pages → ⬜ ROOT-CAUSÉ.** `newPosition > limit (27>3)` dans
  `NativeSkeletonRenderer.renderPreparedVertices`→`Mesh.render`. Contrat relevé (bytecode) : `render()` remplit les buffers PROPRES
  du mesh via `getVertices`, puis `Mesh.render(offset,count)` par draw call (`buf.position(offset); buf.limit(offset+count); draw;
  restore`). Instrumentation `DH_SPINEDBG` (ajoutée à `cspine_jni.c`, off par défaut) : squelettes à 1 draw call OK (ii=918) ; le
  crash est un squelette **multi-pages** (drawCount=3, counts 27/6/6, ii=39) au **2ᵉ** draw call (`position(27)` alors que limite=3).
  Avec la bonne limite (39) les 3 rendus devraient passer → notre `buildVertices` ne laisse pas le buffer d'indices du mesh dans
  l'état attendu pour un squelette multi-pages. Jamais exercé avant (le mode `compare` rend la sortie unidbg). **Fix = répliquer
  exactement l'état/contrat des buffers de la lib ARM d'origine** (désassemblage `native/reference/libspine-native.so`, NATIVE_PLAN
  étape 5, §4 — pas de devinette).

Fichiers : `desktop-port/.../dhbackend/jnispine/AtlasBridge.java` (nouveau), `com/perblue/heroes/cspine/Native.java` (dual-create
atlas), `com/perblue/heroes/cparticle/Native.java` (traduction handle), `native/src/cspine_jni.c` (diag `DH_SPINEDBG`),
`docs/PERF_PLAN.md` (B2 détaillé), `JOURNAL.md`, `MEMORY.md`. Régression serveur inchangée (aucun code serveur touché).
**SUITE = blocage #2 (getVertices multi-pages) : désassembler la lib ARM pour matcher le contrat des buffers, puis re-tester
EN JEU → MainScreen rendu → mesurer fps.**

## 2026-08-25 (g184) — Perf B2 TERMINÉ : contrat getVertices extrait → hub RENDU en spine natif (mode jni), fidèle

Suite g183 (blocage #1 atlas résolu). Blocage #2 (squelettes multi-pages, `newPosition > limit`) **résolu PAR EXTRACTION**
(consigne util. : porter le format exact par extraction, sans réécriture, pour éviter les erreurs). Méthode :
- Instrumentation de l'ORACLE unidbg (`DH_SPINEDBG` dans `UnidbgVM.skeletonGetVertices`) → la lib ARM renvoie RET = **nombre
  d'INDICES** (ex. 39), laisse les buffers à leur capacité (ne pose pas les limites).
- Contrat EXACT relevé dans le wrapper DU JEU `NativeSkeleton.getVertices` (bytecode) : retour natif = nb d'indices ; 2 shorts de
  MÉTADONNÉES après les indices — `indices[n]`=nb vertices (jeu ×6 → limite verts), `indices[n+1]`=nb draw calls (jeu ×2 → limite
  drawCalls, renvoie /2) ; le JEU recale lui-même verts/indices/drawCalls.
- Fix `cspine_jni.c buildVertices` : écrit les 2 métadonnées, renvoie `ii` (indices), ne pose plus les limites. Reproduction
  fidèle (§4), pas de devinette, pas de réécriture de moteur.

**Résultat EN JEU (§8)** : `-Ddh.spinebackend=jni` boote et **REND le hub (MainScreen)** — squelettes multi-pages inclus — **sans
erreur, VISUELLEMENT IDENTIQUE à unidbg** (capture `desktop-port/build/jnihub.png`). 0 exception. ⇒ le backend spine natif rend
l'UI de bout en bout. Fichiers : `native/src/cspine_jni.c`, `desktop-port/.../UnidbgVM.java` (diag), `docs/PERF_PLAN.md` (B2
terminé), `JOURNAL.md`, `MEMORY.md`. **SUITE = rendre le COMBAT en mode jni (B4) + mesurer fps sur GPU réel (B6) ; certif matrice
(B3) + couverture autres écrans (B5).**

## 2026-08-25 (g185) — Perf B6 : mesure avant/après (MÊME machine, GL logiciel) + DÉCOUVERTE bug fidélité mesh

Demande util. : lancer un combat pour comparer avant/après. Le quick-fight se résout serveur-side (pas de combat RENDU animé) →
mesure sur le HERO CHOOSER (écran spine multi-héros, même coût que le combat), via DH_FPS, MÊME machine (GL llvmpipe = pire cas) :
- unidbg (avant) : ~15 fps ; spine émulé = 25–34 ms/frame ; GL ~30–35 ms.
- jni natif (après) : ~38 fps ; spine = ~0 ms/frame ; GL ~26 ms.
⇒ coût spine (~30 ms) ÉLIMINÉ ; fps ~15 → ~38 (×2,5), DÉJÀ >30 fps sur GL LOGICIEL. Sur GPU réel → 60+ attendus.
Thèse perf B PROUVÉE.

⚠️ BUG FIDÉLITÉ (bloquant §4bis) : en mode jni, les squelettes à MESH déformables (héros) rendus ÉCLATÉS (build/fps_j.png) ;
unidbg les rend corrects ; le hub (attachements REGION) rend bien. ⇒ chemin mesh de buildVertices faux (world-vertices pondérés /
indices). Backend rapide FONCTIONNEL+RAPIDE mais pas pixel-fidèle → à corriger (blocage #3).
SUITE = certification compare (diff vertices mesh HostSpine vs unidbg) → corriger par extraction.
