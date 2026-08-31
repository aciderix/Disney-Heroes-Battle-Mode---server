# LAUNCHER_UI — spécification EXHAUSTIVE du front (C2b), dérivée du code

> **But (demande utilisateur, 2026-08-31)** : définir **exactement** tous les éléments de l'interface **à partir du
> code** (pour ne rien oublier), penser l'**ergonomie / la clarté**, **séparer les parties** (compte, serveurs,
> génération, hébergement, administration, réglages), et **séparer strictement le front du back** (pas de monolithe)
> pour qu'un designer (ex. Gemini) puisse retravailler l'esthétique librement.
>
> Ce document est la **source de vérité de l'UI** : chaque écran/élément est mappé sur un **endpoint RÉEL** du
> launcher-core (`server/java/dhlauncher/LauncherDaemon.java`). Les manques du backend sont **signalés** (§8, pas de
> faux « OK ») dans **§7 Endpoints à ajouter** — le front ne doit pas prétendre offrir ce qui n'existe pas encore.

---

## 0. Principes d'architecture UI (non négociables)

1. **Front/back découplés par UN SEUL point de contact** : le front NE connaît QUE le module **`daemonClient`**
   (§1) — un wrapper typé des 15 endpoints HTTP du daemon local (`127.0.0.1:<port>`). Aucun composant visuel ne
   fait de `fetch` direct. ⇒ on peut refaire toute l'esthétique sans toucher à la logique, et inversement.
2. **Couches** : `daemonClient` (I/O) → `stores` (état: session, hostStatus, buildStatus, servers, settings) →
   `views` (écrans) → `components` (briques réutilisables, sans logique réseau). Voir **§8 Arborescence**.
3. **Aucun secret ne quitte la machine** : la **phrase mnémonique** et la **clé privée** restent locales ; le front
   les envoie au **daemon local** (loopback), jamais à un serveur distant. Le daemon signe et ne transmet que la
   signature (cf. `LauncherDaemon.auth`).
4. **Tous les états d'un écran sont explicites** : `idle | loading | empty | error | success`. Chaque appel réseau
   a un rendu pour chacun. Jamais d'écran figé sans feedback.
5. **i18n dès le départ** : tous les libellés dans un dictionnaire (`fr`, `en`…). Le jeu gère déjà plusieurs langues.
6. **Esthétique isolée** : design tokens (couleurs, espacements, typographie) dans un fichier CSS/variables unique,
   composants « sans style codé en dur » → un designer remplace le thème sans lire la logique.

---

## 1. Contrat backend = `daemonClient` (le SEUL point de contact réseau)

Base : `http://127.0.0.1:<daemonPort>` (défaut 8090). Corps des POST : `application/x-www-form-urlencoded`.
Réponses : JSON. **Table intégrale** (source : `LauncherDaemon.java`) :

| Fonction client | Méthode + chemin | Params (form) | Réponse (JSON) | Écran(s) |
|---|---|---|---|---|
| `health()` | `GET /health` | — | `{ok:true}` | boot (attente daemon) |
| `identityGenerate()` | `POST /identity/generate` | — | `{phrase, userID, publicKey}` | Compte▸Nouveau |
| `identityRegister(phrase, serverAuthUrl)` | `POST /identity/register` | `phrase, serverAuthUrl` | `{ok, userID, loginRequestID}` \| `{ok:false,error}` | Compte▸Nouveau (1re co au serveur) |
| `identityLogin(phrase, serverAuthUrl)` | `POST /identity/login` | `phrase, serverAuthUrl` | `{ok, userID, loginRequestID}` \| `{ok:false,error}` | Compte▸Restaurer |
| `serversList()` | `GET /servers` | — | `[{id,name,host,contentPort,gamePort,authPort}]` | Serveurs |
| `serverAdd(name, host, contentPort?, gamePort?, authPort?)` | `POST /servers` | `name, host, contentPort=8080, gamePort=8081, authPort=8082` | `{id,name,host,contentPort,gamePort,authPort}` \| `{error}` | Serveurs▸Ajouter |
| `serverRemove(id)` | `POST /servers/remove` | `id` | `{ok:true|false}` | Serveurs |
| `serverPing(host, port)` | `POST /servers/ping` | `host, port=8081` | `{reachable, latencyMs?}` | Serveurs (indicateur) |
| `hostStart({bundleDir?, contentPort?, gamePort?, authPort?, strict?})` | `POST /host/start` | `bundleDir, contentPort=8080, gamePort=8081, authPort=8082, strict=false` | **hostStatus** (voir ci-dessous) | Héberger |
| `hostStop()` | `POST /host/stop` | — | **hostStatus** | Héberger |
| `hostStatus()` | `GET /host/status` | — | `{running, gamePortListening, contentPort, gamePort, authPort, strict, serverPid, contentPid, uptimeMs}` | Héberger (polling) |
| `buildStart({apkPath, target?, outDir?, full?, pkg?})` | `POST /build/start` | `apkPath, target=server\|client\|apk, outDir, full=false, pkg=true` | **buildStatus** | Générer |
| `buildStatus()` | `GET /build/status` | — | `{state:IDLE\|RUNNING\|DONE\|FAILED, target, step, outDir, log}` | Générer (polling + tail log) |
| `playStart({clientDir, serverId?, serverHost?, contentPort?, userID?, strict?})` | `POST /play` | `clientDir, serverId \| serverHost+contentPort=8080, userID=0, strict=false` | **playStatus** | Jouer |
| `playStop()` | `POST /play/stop` | — | **playStatus** | Jouer |
| `playStatus()` | `GET /play/status` | — | `{running, pid, server, userID, strict, uptimeMs}` | Jouer (polling) |

**Types TS dérivés** (`types.ts`) :
```ts
export type Identity = { phrase: string; userID: number; publicKey: string };
export type Session  = { userID: number; loginRequestID: string; serverId: string };
export type Server   = { id: string; name: string; host: string; contentPort: number; gamePort: number; authPort: number };
export type Ping     = { reachable: boolean; latencyMs?: number };
export type HostStatus  = { running: boolean; gamePortListening: boolean; contentPort: number; gamePort: number;
                            authPort: number; strict: boolean; serverPid: number; contentPid: number; uptimeMs: number };
export type BuildState  = "IDLE" | "RUNNING" | "DONE" | "FAILED";
export type BuildStatus = { state: BuildState; target: "SERVER"|"CLIENT"|"APK"; step: string; outDir: string; log: string };
export type PlayStatus  = { running: boolean; pid: number; server: string; userID: number; strict: boolean; uptimeMs: number };
```

> **Conventions de ports** (défaut, `LauncherConfig`) : content **8080**, jeu **8081**, auth **8082**.
> **`serverAuthUrl`** attendu par login/register = `http://<host>:<authPort>` (cf. `Server.authUrl()`).
> **`-Ddh.server`** (redirection `ServerType.LIVE`) = `<host>:<contentPort>` (cf. `Server.serverProp()`).

---

## 2. Écran 0 — PREMIER LANCEMENT : avertissement à lire **en entier** et **accepter**

**Gate obligatoire** : tant que l'utilisateur n'a pas accepté, aucune autre partie n'est accessible. Acceptation
persistée localement (`settings.json → disclaimerAcceptedVersion`). Le bouton **« J'ai lu et j'accepte »** n'est
**activé qu'après défilement jusqu'en bas** (preuve de lecture) + case cochée. Bouton **« Refuser et quitter »** ferme
l'app.

**Texte (FR, versionné `v1` ; à faire relire — ce n'est pas un avis juridique) :**

> **AVERTISSEMENT — À LIRE INTÉGRALEMENT**
>
> Ce logiciel (« le Launcher ») est un **projet amateur, indépendant et à but non lucratif**. Il **n'a AUCUN lien**
> avec **Disney**, **PerBlue**, ni aucun de leurs partenaires, et **n'est ni approuvé, ni sponsorisé, ni affilié** à
> eux d'aucune manière.
>
> Le jeu **Disney Heroes: Battle Mode**, son code, ses images, ses personnages, ses sons, ses musiques et **tout son
> contenu** demeurent la **propriété exclusive de Disney, de PerBlue et de leurs ayants droit respectifs**. Toutes les
> marques et personnages cités appartiennent à leurs propriétaires respectifs.
>
> Le Launcher **ne distribue PAS le jeu** ni aucun de ses contenus protégés : il ne contient **aucun fichier du jeu**.
> Pour l'utiliser, **vous devez fournir vous-même** votre propre copie de l'application (APK), que **vous devez
> posséder légalement**. Vous êtes seul responsable de la légalité de son obtention et de son usage dans votre pays.
>
> Le Launcher est fourni **entièrement gratuitement**. Je **n'autorise ni ne cautionne aucune utilisation à des fins
> commerciales ou financières** (vente, revente, dons contre accès, publicité, monétisation de serveurs, etc.). Toute
> personne qui hébergerait un serveur le fait **à titre privé et gratuit**, sous sa **seule responsabilité**.
>
> Le Launcher est fourni **« EN L'ÉTAT », SANS AUCUNE GARANTIE**, sans support, et **sans aucune responsabilité** de
> l'auteur quant à d'éventuels dommages, pertes de données, ou conséquences de son utilisation. **Vous l'utilisez à vos
> propres risques.**
>
> Ce projet existe à des fins **d'étude, d'archivage et d'usage personnel** entre particuliers. Si un ayant droit le
> demande, l'auteur cessera la distribution.
>
> **Auteur** : Aciderix — **contact / retrait sur demande** : fromthenext77@gmail.com
>
> En cochant la case ci-dessous, vous déclarez **avoir lu et compris** cet avertissement et **l'accepter intégralement**.
>
> ☐ *J'ai lu et j'accepte l'intégralité de cet avertissement.*    **[ Refuser et quitter ]  [ J'ai lu et j'accepte ]**

---

## 3. Architecture de l'information (navigation)

Après le gate, **app à barre latérale** (sidebar) + zone principale. Regroupement par **intention**, pas par endpoint :

```
┌──────────────┬─────────────────────────────────────────────┐
│  ▸ JOUER      │                                              │
│  ▸ SERVEURS   │            zone principale (écran actif)      │
│  ▸ HÉBERGER   │                                              │
│  ▸ GÉNÉRER    │                                              │
│  ▸ ADMIN      │   (barre du haut : COMPTE ⟨userID / statut⟩,  │
│  ▸ RÉGLAGES   │    langue, indicateur daemon ●)               │
└──────────────┴─────────────────────────────────────────────┘
```

- **COMPTE** (coin haut-droit, toujours visible) : identité active (userID abrégé), état de connexion au serveur
  sélectionné, menu ⟨Changer de compte / Se déconnecter⟩.
- **Indicateur daemon ●** : vert si `health()` OK, rouge sinon (avec « relancer le launcher »).
- **Groupes** : **Jouer** + **Serveurs** = usage joueur ; **Héberger** + **Générer** + **Admin** = usage hébergeur
  (peuvent être masqués derrière un mode « avancé » dans Réglages pour un joueur simple). **Réglages** = transversal.

**Flux 1er run** : Gate(§2) → Compte▸Nouveau (§4.1) → Serveurs (localhost/héberger) → Jouer.
**Flux retour** : (auto-login si « se souvenir ») → Serveurs → Jouer.

---

## 4. Écrans détaillés (éléments ↔ endpoints)

### 4.1 COMPTE (identité) — `onglet segmenté [ Nouveau | Restaurer ]`
**But** : obtenir une **session authentifiée** (`{userID, loginRequestID}`) auprès de l'`AuthService` du serveur choisi.

- **Nouveau compte**
  - Bouton **« Générer une phrase »** → `identityGenerate()` → affiche les **8 mots BIP39** en grand, **numérotés**,
    avec bouton **Copier** et **Révéler/Masquer**.
  - Encart d'alerte : « **Notez ces 8 mots.** C'est votre **seule** clé. **Aucune récupération** possible en cas de
    perte. »
  - Case obligatoire ☑ « Je les ai notés en lieu sûr ».
  - Sélecteur de **serveur cible** (liste des favoris, cf. 4.2) — nécessaire pour `serverAuthUrl`.
  - Bouton **« Créer le compte »** → `identityRegister(phrase, serverAuthUrl)` → `{ok, userID, loginRequestID}` →
    stocke la **Session** → va à **Jouer**. Erreurs : `{ok:false, error: challenge|register|verify|…}` → message clair.
- **Restaurer**
  - **8 champs** avec **autocomplétion BIP39** (2048 mots) + **validation checksum en direct** (invalide → bord rouge
    + « faute de frappe ? »). *(La validation checksum existe côté core : `MnemonicIdentity.isValid` ; l'idéal est de
    l'exposer aussi côté front pour un retour instantané — cf. §7.)*
  - Sélecteur de **serveur cible**.
  - Bouton **« Se connecter »** → `identityLogin(phrase, serverAuthUrl)` → Session → **Jouer**.
- **Se souvenir de moi** (option) : cache local chiffré (mot de passe local optionnel) de la phrase. **Par défaut OFF**
  ; la phrase en clair n'est **jamais** persistée sans ce choix explicite. *(Persistance chiffrée = §7, à ajouter.)*

**États** : loading (spinner sur le bouton), error (bandeau), success (redirection).

### 4.2 SERVEURS (recherche / favoris)
**But** : gérer la liste des serveurs et en **sélectionner un** (pour Compte / Jouer / Héberger).

- **Liste des favoris** (`serversList()`) → une **carte par serveur** : `name`, `host:contentPort`, badges de ports
  (content/jeu/auth), et **indicateur de disponibilité** via `serverPing(host, gamePort)` → point **vert/rouge** +
  **latence (ms)**. Bouton **Sélectionner** (radio), **Supprimer** (`serverRemove(id)` avec confirmation).
- **Ajouter un serveur** (formulaire) : `name`, `host`, et (repliés sous « Ports avancés ») `contentPort=8080`,
  `gamePort=8081`, `authPort=8082` → `serverAdd(...)`. Validation : `name/host` sans `| " \ \n` (cf. `LauncherConfig.safe`).
- **Raccourci « localhost »** : bouton pré-rempli `127.0.0.1` (pour jouer sur son propre serveur hébergé).
- **Actions par serveur** : **Rejoindre** (→ Compte si pas de session, sinon → Jouer), **Héberger** (→ Héberger).
- ⚠️ **Manques backend** (§7) : le ping ne donne que `reachable + latencyMs`. **La version du serveur et le nombre de
  joueurs en ligne ne sont PAS exposés** aujourd'hui → à NE PAS afficher tant que l'endpoint serveur n'existe pas
  (`/status` serveur à ajouter). Ne pas inventer ces valeurs.

### 4.3 JOUER
**But** : lancer le **client** (port PC) sur le serveur sélectionné avec le compte authentifié.
- Récap : **compte** (userID), **serveur** (nom, latence via ping), **mode** (permissif/strict selon le serveur).
- **Gros bouton « JOUER »** → `playStart({clientDir, serverId | serverHost+contentPort, [userID], [strict]})`
  (**endpoint LIVRÉ**, `PlayManager`). Le client bundle est lancé avec `DH_SERVER=host:contentPort` (redirige
  `ServerType.LIVE`) et, en **permissif**, `DH_USERID=<userID>`.
- **Polling `playStatus()`** → `{running, pid, server, userID, strict, uptimeMs}` : `lancement… | en cours (PID, temps)
  | fermé`. Bouton **« Arrêter »** → `playStop()`.
- Le **`clientDir`** (dossier du bundle port PC) vient de Générer (dernier build CLIENT) ou de Réglages.
- ⚠️ **Strict vers un serveur DISTANT** : couvert seulement pour le serveur hébergé **en local** (le billet est frappé
  côté serveur). L'injection du `loginRequestID` dans le `ClientInfo` pour un **serveur distant strict** = incrément
  séparé (hook client) — cf. §7. En attendant : permissif (DH_USERID) partout, strict en local.

### 4.4 HÉBERGER (auto-hébergement local — panneau minimal)
**But** : démarrer/arrêter un serveur de jeu **sur la machine du joueur** et voir son état. (Panneau **minimal** ;
l'**administration live-ops** = §4.6.)

- **Source du serveur** : (a) **bundle généré** (champ `bundleDir` → sélecteur de dossier ; recommandé, autonome) ;
  (b) **dev/classpath courant** (si `bundleDir` vide) — réservé au mode avancé.
- **Options de démarrage** : `contentPort` (8080), `gamePort` (8081), `authPort` (8082), **interrupteur `strict`**
  (auth mnémonique obligatoire — ON = comptes par phrase requis ; OFF = permissif).
- Bouton **« Héberger »** → `hostStart({...})` ; bouton **« Arrêter »** → `hostStop()`.
- **Panneau de statut** (polling `hostStatus()` ~1 s) : voyant **running**, voyant **gamePortListening** (« en écoute »),
  ports, **strict**, **serverPid/contentPid**, **uptime** (formaté). 
- **Logs** : afficher le **tail** des fichiers écrits par `HostManager` (`server/data/host-server.log`,
  `host-content.log`, ou `host.log` en mode bundle). *(Le tail des logs hôte n'a pas encore d'endpoint — cf. §7.)*
- Bouton **« Jouer sur ce serveur »** (raccourci → sélectionne `127.0.0.1` puis Jouer).

### 4.5 GÉNÉRER (build depuis l'APK — « usine » clé-en-main)
**But** : produire les **bundles** (serveur / port PC) depuis **l'APK fourni par l'utilisateur**.

- **Sélecteur d'APK** (`apkPath`) — obligatoire. Rappel : « votre propre copie ».
- **Cible** (`target`) : **SERVER** (héberger) | **CLIENT** (port PC jouable) | **APK** (patch mobile — **à venir**,
  le core répond un **refus honnête** `FAILED`, à afficher comme « bientôt » et non comme une erreur).
- **Dossier de sortie** (`outDir`) — sélecteur de dossier.
- Options avancées : `full` (decompile+reframe complet vs données seules), `pkg` (packaging autonome, défaut ON).
- Bouton **« Générer »** → `buildStart({...})` puis **polling `buildStatus()`** :
  - **barre d'étapes** dérivée de `step` (ex. `extract → decompile → reframe → jar-server → jlink-runtime →
    untar-python → package-*`) + **état** `RUNNING/DONE/FAILED`.
  - **Console** : `log` (tail) en monospace, défilement auto.
  - À **DONE** : chemin `outDir` cliquable (« ouvrir le dossier »), bouton **« Héberger ce bundle »** (→ Héberger avec
    `bundleDir` pré-rempli) pour SERVER, ou **« C'est prêt à jouer »** pour CLIENT.
  - À **FAILED** : `step` + `log` mis en avant, bouton « réessayer ».
- ⚠️ Réseau requis pendant la génération (téléchargement CPython + assets) → le signaler.

### 4.6 ADMIN — PANNEAU OPÉRATEUR (chantier D) — visible seulement pour un serveur qu'on héberge soi-même
**But** : administrer le serveur hébergé. C'est un **panneau à onglets** couvrant **5 domaines** ; chacun est mappé sur
ce que le **code supporte réellement** (§8 : pas de faux « OK »). **Aucun n'a encore d'endpoint launcher** → tous
requièrent de **nouveaux endpoints `/admin/*`** (§7). Statut par domaine :

| Onglet Admin | Domaine | Capacité dans le code | Statut |
|---|---|---|---|
| **A. Events** | live-ops (remises, bonus, trials, contest, modes ouverts…) | ✅ `ServerEvents` (13 builders) + `shard_state` | **exposer** |
| **B. Monitoring** | joueurs en ligne, connexions, uptime, logs | 🟡 `LoginServer.online` + `connectionsAccepted` + logs hôte | **exposer** |
| **C. Joueurs** | éditer un compte (ressources, héros, TL, campagne, déblocages, tutos) | 🟡 `ServerUser.giveResource/grantHero/grantCampaignLevel/completeAllTutorials/SetTeamLevel/CodebaseUnlock` | **exposer** (autoritatif, à journaliser) |
| **D. Ère de contenu** | choisir la **release** (R1…R102) = ancrer l'horloge du jeu à cette ère | 🟡 `docs/RELEASE_PICKER.md` + outil `AdminRelease` + ancre d'horloge (`ServerContext`) | **exposer** |
| **E. Modération** | **ban / mute / kick** joueur | ❌ **ABSENT** (seul `KickFromGuild` = feature de jeu, pas opérateur) | **À CONSTRUIRE** |

#### A. Events (live-ops) — un **éditeur d'events**
`ServerEvents` (builders `SpecialEventInfo`) persistés dans `shard_state`, chargés au boot par `LoginServer`
(`installOperatorEvents`) ; aujourd'hui pilotés par l'outil CLI **`AdminEvents`** (spec JSON `{kind,…}` →
`ServerEvents.fromJson`). Chaque « composant » = un `kind` :
une **liste d'overrides actifs** (avec fenêtre `start/end`) + un bouton **« Ajouter un event »** ouvrant un formulaire
selon le `kind` choisi :

| `kind` (composant) | Champs opérateur (dérivés du builder) | Effet |
|---|---|---|
| **MODES_OPEN** | `modes: GameMode[]`, `start`, `end` | force l'**ouverture de modes** (ex. Invasion, Port…) sur une fenêtre |
| **DROP_BONUS** | `modes: GameMode[]`, `bonus:int`, `start`, `end` | **bonus de butin** sur des modes |
| **CHEST_DISCOUNT** | `chests: ChestType[]`, `percentOff:int`(50), `start`, `end` | **remise sur coffres** |
| **INCREASED_CHANCES** | `chances: Map<String,int>`, `start`, `end` | **hausse des chances** (drop) |
| **MERCHANT_DISCOUNT** | `merchants: MerchantType[]`, `percentOff`, `start`, `end` | remise **marchands** |
| **MERCHANT_REFRESH_DISCOUNT** | `merchants: MerchantType[]`, `percentOff`, `start`, `end` | remise **refresh marchand** |
| **MISC_BONUS** | `mults: MultiplierType[]`, `bonus`, `start`, `end` | **bonus divers** (multiplicateurs) |
| **MISC_DISCOUNT** | `mults: MultiplierType[]`, `percentOff`, `start`, `end` | **remises diverses** |
| **FLAG_USER_ON_LOGIN** | `flags: UserFlag[]`, `start`, `end` | pose des **drapeaux joueur** au login |
| **TEAM_LEVEL** | `teamLevel:int`, `drops:json[]`, `everyX:bool`, `start`, `end` | récompenses **niveau d'équipe** |
| **TRIAL / FRANCHISE_TRIAL** | `trialType`, `chances`, `title`, `trialIndex`, `modifiersPerNode`, `start`, `end` | **épreuves** (trials) |
| **EXTRA_CHEST** | `cost:int`, `currency: ResourceType`, `start`, `end` | **coffre bonus** payant |
| **CONTEST** | `guild:bool`, `aggregate:bool`, `title`, `summary`, `tasks`, `progress`, `ranks`, `start`, `end` | **classement/contest** |

**Ergonomie de l'éditeur** : chaque champ « énumération » (`GameMode`, `ChestType`, `MerchantType`, `MultiplierType`,
`UserFlag`, `ResourceType`, `GenericTrialType`) = **multi-select** alimenté par la **liste réelle des enums** (à exposer
par un endpoint `/admin/enums`, §7 — **ne pas coder en dur**). `start/end` = **sélecteurs date-heure**. Aperçu JSON de
la spec (`{kind,…}`) avant application. Actions : **Appliquer** (persiste + `refresh`), **Retirer**, **Vider tout**.

#### B. Monitoring
- **Joueurs en ligne** (liste userID + depuis quand) et **connexions acceptées** — source `LoginServer.online`
  (`ConcurrentHashMap<userID,connexion>`) + `connectionsAccepted`. → endpoint `GET /admin/monitor`.
- **État serveur** : ports, strict, uptime (déjà via `hostStatus`).
- **Logs** : tail de `host-server.log` / `host-content.log` / `host.log` → endpoint `GET /host/logs?which=&tail=N`.
- **Kick** (déconnecter un joueur en ligne) : faisable via la map `online` (fermer la connexion) — mais **à coder**
  (recouvre la Modération E).

#### C. Gestion des joueurs (édition autoritative — À JOURNALISER)
Rechercher un compte (par userID) puis appliquer des mutations qui **existent déjà** comme méthodes `ServerUser`
(utilisées par les outils dev `CodebaseUnlock`/`SetTeamLevel`/`StrictAuthAccount`) :
- **Ressources** : `giveResource(type, montant)` (or, diamants, énergie…).
- **Héros** : `grantHero(type[, rareté, niveau, étoiles])`.
- **Niveau d'équipe** : `SetTeamLevel` / resync TL.
- **Campagne** : `grantCampaignLevel(type, chapitre, niveau, étoiles)`.
- **Tutoriels** : `completeAllTutorials()`.
- **Déblocage global** : `CodebaseUnlock` (chapitre 41 + roster + ressources).
→ endpoints `POST /admin/player/{lookup,giveResource,grantHero,setTeamLevel,grantCampaign,completeTutorials,unlock}`.
⚠️ Puissant (triche opérateur) → **confirmation + journalisation** obligatoires dans l'UI.

#### D. Ère de contenu (release picker)
Choisir une **release `R1…R102`** = **poser l'ancre d'horloge** du jeu à la date de début de cette ère (gouverne héros/
objets disponibles, sorties, saisons — `content.<shard>.tab`/`ContentStats`, `patched_heroes*`, cf.
`docs/RELEASE_PICKER.md`). Outil existant : **`AdminRelease`**. → endpoints `GET /admin/releases` (liste R1…R102 + date),
`POST /admin/release {name}`. Inclut aussi le **décalage d'horloge** libre (`-Ddh.clock.offset.hours`, mode test).

#### E. Modération — **À CONSTRUIRE** (n'existe pas dans le code)
Aucun système de **ban / mute / kick opérateur** aujourd'hui (`KickFromGuild` = expulsion de guilde côté **jeu**, pas
modération). À bâtir comme incrément serveur : **liste de bans** (rejet au login dans `LoginServer`, à côté de l'auth
strict), **mute** (drapeau anti-chat), **kick** (fermer la connexion via `online`). → endpoints
`GET/POST /admin/moderation/{bans,ban,unban,mute,kick}`. **Le front affiche cet onglet « à construire »**, pas simulé.

> **Cadrage honnête** : §4.6 (A→E) est la **cible chantier D**. Seuls les **capacités sous-jacentes** existent (A/B/C/D) ;
> **aucun endpoint `/admin/*` n'est encore livré**, et la **Modération (E) n'existe même pas** côté serveur. Le front
> affiche chaque onglet **« à venir »/« à construire »** tant que l'endpoint n'existe pas, et **ne simule jamais** un effet.

### 4.7 RÉGLAGES
**But** : préférences locales + entretien. (Persistance `settings.json` **à ajouter** — §7.)
- **Client / rendu** : résolution, **qualité spine** (`jni` rapide / `unidbg` fidèle), langue, `LIBGL_ALWAYS_SOFTWARE`.
- **Chemins** : APK par défaut, dossier de sortie des builds, dossier des bundles.
- **Sécurité locale** : mot de passe local (chiffre « se souvenir de moi »), **gestion des comptes locaux** (oublier).
- **Avancé** : afficher/masquer les sections Héberger/Générer/Admin (profil « joueur simple » vs « hébergeur »).
- **Maintenance** : **re-générer** (→ Générer), voir le dossier de config, port du daemon.
- **À propos** : version, **relire l'avertissement** (§2), licences tierces (JDK, CPython, LWJGL, unidbg…).

---

## 5. Inventaire des composants réutilisables (sans logique réseau, restylables)

`StatusDot` (vert/rouge/orange) · `PortBadge` · `MnemonicWordGrid` (8 mots, copier/révéler) · `Bip39Input`
(autocomplétion + checksum) · `ServerCard` · `StepProgress` (barre d'étapes build) · `LogConsole` (tail monospace,
autoscroll) · `KeyValueList` (statut host) · `Toggle` (strict, avancé) · `PortsAdvanced` (repliable) · `FilePicker` /
`DirPicker` · `ConfirmDialog` · `Banner` (info/error/success) · `Spinner` · `EnumMultiSelect` (admin) · `DateTimeRange`
(admin) · `EventEditor` (admin) · `DisclaimerGate` (§2). **Aucun** ne contient d'URL/`fetch` : ils reçoivent données +
callbacks en props.

---

## 6. Modèle d'état (stores) — ce que le front garde en mémoire

`daemonStore` (port, health) · `sessionStore` (Identity éphémère, Session courante, « se souvenir » chiffré) ·
`serversStore` (favoris + pings) · `hostStore` (HostStatus, polling) · `buildStore` (BuildStatus, polling, log) ·
`settingsStore` (settings.json) · `uiStore` (écran actif, langue, profil simple/avancé, disclaimerAccepted).

---

## 7. Manques backend à combler (checklist — le front en dépend)

Le front **ne doit pas** exposer ces fonctions tant que l'endpoint n'existe pas (§8 « pas de faux OK ») :

1. ~~`POST /play` + `GET /play/status`~~ — **LIVRÉ** (`PlayManager`, endpoints `/play`, `/play/stop`, `/play/status` ;
   `PlayLifecycleTest`). Reste : **hook client `loginRequestID`** pour le **strict DISTANT** (aujourd'hui : permissif
   partout + strict local ok).
2. **`GET /status` côté SERVEUR de jeu** (ou via content_server) exposant **version** + **nb joueurs en ligne** →
   pour les cartes Serveurs. (Aujourd'hui : `ping` ne donne que TCP + latence.)
3. **`POST /identity/validate {phrase}` → `{valid, checksum}`** (ou validation BIP39 embarquée côté front) — retour
   instantané dans Restaurer. (Le core a `MnemonicIdentity.isValid` ; non exposé.)
4. **Persistance des réglages** : `GET/POST /settings` (résolution, spine, langue, APK, disclaimer accepté, profil) —
   `settings.json`. (Aujourd'hui : seul `servers.txt` est persisté.)
5. **« Se souvenir de moi » chiffré** : `POST /identity/remember {phrase, localPassword}` / `POST /identity/forget` /
   déchiffrement au démarrage. (Aujourd'hui : absent ; la phrase n'est pas persistée.)
6. **Tail des logs hôte** : `GET /host/logs?which=server|content|bundle&tail=N`. (Fichiers écrits mais pas servis.)
7. **ADMIN — panneau opérateur (chantier D, 5 domaines §4.6)** :
   - **A. Events** : `GET/POST /admin/events`, `/admin/events/remove`, `/admin/events/clear`, `GET /admin/enums`
     (enums réelles). Base : `ServerEvents.fromJson`/`setOperatorEvents` + `shard_state`. *(capacité prête)*
   - **B. Monitoring** : ✅ **LIVRÉ (inc.6a, g208)** — `GET /admin/monitor` (joueurs en ligne via `LoginServer.online`,
     connexions, uptime, strict) + `GET /host/logs?which=server|content&tail=N`. Archi : `dhserver.admin.AdminService`
     (dans la JVM serveur, jeton opérateur obligatoire, bind 127.0.0.1 par défaut / exposable réseau) ; le daemon
     PROXIFIE `/admin/monitor` (jeton injecté ; 503 si aucun serveur hébergé). `AdminMonitorTest`/`AdminProxyTest`.
   - **C. Joueurs** : `POST /admin/player/{lookup,giveResource,grantHero,setTeamLevel,grantCampaign,completeTutorials,unlock}`
     (méthodes `ServerUser` existantes ; **journaliser**). *(capacité prête)*
   - **D. Ère de contenu** : ✅ **LIVRÉ (inc.6b, g209)** — `GET /admin/releases`, `POST /admin/release {name|#idx|reset}`,
     `GET|POST /admin/clock {offsetHours}`. Helper `dhserver.admin.ContentEra` (source unique, réutilisée par le CLI
     `AdminRelease`). Ère découplée (ne touche ni sauvegardes ni timers), appliquée à chaud. Proxy daemon générique.
     `AdminMonitorTest`/`AdminProxyTest`.
   - **E. Modération** : `GET/POST /admin/moderation/{bans,ban,unban,mute,kick}` — **à CONSTRUIRE côté serveur**
     (liste de bans rejetée au login + mute + kick via `online`) ; **rien n'existe** aujourd'hui.
8. **DELETE réel pour `/servers`** (aujourd'hui `POST /servers/remove`) — cosmétique ; garder tel quel côté client.

*(Ces ajouts sont des incréments backend séparés ; le front C2b peut démarrer sur le périmètre EXISTANT :
Compte + Serveurs + Héberger + Générer, avec Jouer/Admin marqués « à venir ».)*

---

## 8. Séparation front/back concrète (arborescence proposée — non monolithique)

```
launcher-ui/                      (Tauri + React ; AUCUN code de jeu)
├─ src/
│  ├─ api/
│  │   ├─ daemonClient.ts         ← SEUL fichier qui parle au daemon (les 15 endpoints + à venir)
│  │   └─ types.ts                ← types §1
│  ├─ stores/                     ← état (§6), sans JSX
│  ├─ views/                      ← 1 fichier par écran (§4), consomment stores + components
│  │   ├─ DisclaimerGate.tsx  Account.tsx  Servers.tsx  Play.tsx
│  │   ├─ Host.tsx  Generate.tsx  Admin.tsx  Settings.tsx
│  ├─ components/                 ← briques réutilisables (§5), zéro réseau
│  ├─ i18n/  fr.ts  en.ts
│  └─ theme/  tokens.css          ← COULEURS/ESPACEMENTS/TYPO ISOLÉS (le designer ne touche que ça + components)
└─ src-tauri/                     ← shell Tauri : démarre le daemon Java embarqué, sélecteurs de fichiers natifs
```
**Règle de séparation** : un designer (Gemini) peut **remplacer `theme/` + `components/`** et réorganiser `views/`
**sans jamais** ouvrir `api/` ni `stores/`. Toute la logique (réseau, état, flux) vit sous `api/`+`stores/`.

**Shell Tauri** : (a) au lancement, **démarre le daemon** (`runtime/jdk/bin/java -cp dhlauncher.jar
dhlauncher.LauncherDaemon --port <libre> --project <tooling>`) et attend `health()` ; (b) fournit les **sélecteurs
natifs** (APK, dossiers) via l'API Tauri ; (c) transmet le **port du daemon** au front. Le front reste **100 % web**
(reskinnable), le natif se limite au lancement du process + dialogues fichiers.

---

## 9. Ordre de livraison suggéré (incréments, testables)
1. **Shell Tauri + daemonClient + DisclaimerGate + health** (le squelette parle au daemon).
2. **Compte** (generate/register/login) + **Serveurs** (list/add/remove/ping).
3. **Héberger** (host start/stop/status + polling) + **Générer** (build start/status + StepProgress/LogConsole).
4. **Backend `/play`** puis **écran Jouer** → **vérif EN JEU strict de bout en bout** (login mnémonique → boot → jouer).
5. **Réglages** (settings persistés) + **Se souvenir** chiffré.
6. **Admin (chantier D)** : endpoints `/admin/*` + éditeur d'events.
```
```
