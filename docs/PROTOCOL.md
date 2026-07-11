# PROTOCOL — réseau & contenu (reverse depuis le jeu)

Établi **depuis le bytecode du client** (source de vérité). But : savoir ce que le serveur
doit servir pour franchir le chargement (login → BootData) et jouer. Disney Heroes réutilise
la **même conception** que DragonSoul (cf. dépôt de référence `desktop-port/PROTOCOL.md`),
mais avec des **noms de classes non obfusqués** → voir [`RECON.md`](RECON.md).

> Statut : cette 1ʳᵉ version s'appuie sur la recon `strings` + le protocole DragonSoul
> (identique par construction studio). Les valeurs marquées **[À EXTRAIRE]** doivent être
> confirmées par décompilation de cet APK avant toute implémentation (principe §3 : la
> source de vérité est le jeu, pas notre mémoire).

## 0. Cible réseau — `com.perblue.heroes.ServerType`  ✅ EXTRAIT
Enum, ctor `(String name, int ordinal, String protocol, String loginHost, int port,
String contentLocation)`. Valeurs décompilées (`classes4.dex`) :

| Type | protocol | loginHost | port | contentLocation |
|---|---|---|---|---|
| **LIVE** (défaut) | `https://` | `login.disneyheroesgame.com` | `443` | `http://content.disneyheroesgame.com/live/index.txt` |
| STAGING | `https://` | `login.staging.disneyheroesgame.com` | `443` | `http://dhstaging.disneyheroesgame.com:10070/content/beta/index.txt` |
| LOCAL | `http://` | `localhost` | `8080` | — |
| NONE/TRUNK/DEV | — | — | — | — |

- ⚠️ **Cet APK n'est PAS patché** (contrairement à DragonSoul « Fixed2 » → `127.0.0.1`).
  LIVE pointe vers les vrais domaines PerBlue (hors ligne).
- **Pas de gameHost/gamePort TCP dans `ServerType`** : le login se fait en **HTTPS** vers
  `login.disneyheroesgame.com:443`, qui **renvoie l'adresse du serveur de jeu** (séquence
  en 2 étapes, cf. §1.3). ⇒ pour rediriger vers notre serveur **sans patcher le bytecode**,
  on réécrit `ServerType.LIVE` par réflexion au démarrage (ou passerelle) vers notre host.

## 1. Transport de jeu (TCP)
Conception DragonSoul (à confirmer identique ici) :
- Connexion : `java.net.Socket` brut vers `gameHost:gamePort` (`TcpNoDelay`, `KeepAlive`).
- **Framing par message** : `[ int32 little-endian : longueur du corps wrappé ][ corps wrappé ]`
  (l'entier est écrit par un `packInt` = int32 LE, **pas** un varint).
- `corps wrappé = codec.wrapOut( message.writeAll(writer) )`.

### 1.1 Codec (chiffrement + compression)
- Interface `com.perblue.grunt.translate.ConnectionWrapper` (`wrapOut/wrapIn/closeIn/Out`).
- Impl jeu : `com.perblue.heroes.network.{XORConnectionWrapper,DHXORConnectionWrapper}`
  au-dessus de `com.perblue.common.network.XORConnectionWrapper` (+ `XORCipher`,
  `DeflateConnectionWrapper`, `StackedConnectionWrapper`).
- Algo : **Deflate puis XOR roulant** par clé (comme DragonSoul).
- Structure décompilée (`classes4.dex`) :
  `DHXORConnectionWrapper extends StackedConnectionWrapper` avec, dans son ctor :
  `new StackedConnectionWrapper( new DeflateConnectionWrapper(), new XORConnectionWrapper2(KEY) )`.
  ⇒ **wrapOut = Deflate puis XOR(KEY)** (et inverse en wrapIn).
- **Clé XOR** ✅ EXTRAITE — champ statique `DHXORConnectionWrapper.KEY` (8 octets) :
  ```
  hex     : CE 85 D4 F9 29 A8 24 56
  signés  : { -50, -123, -44, -7, 41, -88, 36, 86 }
  ```
  Secret partagé constant (pas de handshake). Le serveur réutilise la **même** classe/clé
  (XOR symétrique) — idéalement en réutilisant directement `DHXORConnectionWrapper` du jar.

### 1.2 Modèle de message
- Registre : `com.perblue.heroes.network.messages.MessageFactory`
  (`readMessage(reader)` désérialise n'importe quel message).
- Framework : `com.perblue.grunt.translate.GruntMessageFactory` + base message grunt
  (`messageNumber`, `responseMessageNumber`, `version`, `getFullName()`, `writeAll`,
  `writeData`/`writeDataV1`).
- Messages : `com.perblue.heroes.network.messages.*`. FULL_NAME façon `ClientInfo1`,
  `BootData1` (nom + version). Chaque message : ctor `()` (envoi), ctor `(reader)` (réception).
  - Login : `ClientInfo` (envoyé par le client).
  - Réponse : `BootData` (attendue du serveur ; log « Beginning GameMain handleBootData »).

### 1.3 Séquence de login (à confirmer sur cette version)
DragonSoul : **deux étapes** — (1) `POST /login` HTTP (params device) → status/redirect
+ adresse du serveur de jeu ; (2) `startNetwork` ouvre le Socket TCP, envoie `ClientInfo1`,
attend `BootData1`. **[À CONFIRMER]** que Disney Heroes 12.1.0 suit la même séquence
(`RPGMain`/`GameMain`, `NetworkProvider`).

## 2. Contenu (HTTP) — voir [`ASSETS.md`](ASSETS.md)
- `AssetUpdater` télécharge `index.txt` puis les archives manquantes ; gate `MISSING_ADDITIONAL`.
- Colonnes de l'index (observées) : `Mode Category Environment Density Compression
  GameVersion Revision URL Size` (TSV). Le serveur sert `index.txt` + redirige les archives
  vers archive.org / copie locale.

## 2bis. Sérialisation des messages ✅ VÉRIFIÉE (classes du jeu, sans libGDX)
API confirmée sur `libs/game.jar` (JVM desktop, `-Xverify:none` + `commons-logging`) :
- Base : `com.perblue.grunt.translate.GruntMessage` (`writeAll(GruntOutputStream)`,
  `writeData`/`writeDataV1`, `getFullName()`, `getMessageNumber()`, `setMessageNumber`,
  `readSingle`). Messages du jeu : `com.perblue.heroes.network.messages.*` (`BootData` →
  `getFullName()=="BootData1"`, `ClientInfo`, …).
- Écrire : `GruntOutputStream out = new GruntOutputStream(); msg.writeAll(out); byte[] b = out.getBytes();`
- Lire : `MessageFactory.getInstance().readMessage(new GruntInputStream(b))` → `GruntMessage`.
- **Round-trip prouvé** (`server/smoke/MessageRoundTrip`) : `BootData`(serverTime, flags,
  loginEvent) → `writeAll` → `readMessage` → mêmes champs. `MessageFactory.getInstance()` et
  `new BootData()` se chargent **sans libGDX**. ⇒ le serveur construit/décode les messages
  avec le format wire **exact** du jeu, zéro réimplémentation.

## 2ter. Handshake de jeu ✅ PROUVÉ bout-en-bout (socket TCP, classes du jeu)
On **réutilise la pile serveur DU JEU** au lieu de refaire le framing à la main : le jeu
embarque `com.perblue.grunt.translate.GruntNIOTCPServer` (serveur NIO, même framing/codec
que le client) — *package-private*, instancié via `GruntServerFactory` (classe ajoutée dans
le même package, cf. SHIMS.md ; le ctor n'active pas le thread → on lève `running` + start).
- `server/java/dhserver/LoginServer.java` : `GruntNIOTCPServer(port, MessageFactory, exec,
  listener, DHXORConnectionWrapper.class, …)` ; sur `ClientInfo` reçu → `send(BootData)`.
- Vérifié (`server/smoke/HandshakeRoundTrip`) : un client `GruntBuilder` envoie `ClientInfo1`,
  le serveur répond `BootData1`, le client le décode (champs corrects). **Sans libGDX.**
- ⇒ Le framing/longueur/codec sont gérés par les classes du jeu → pas besoin de reverser
  `packInt` à la main. Reste à déterminer les **champs minimaux de `BootData1`** que le
  **vrai** client déréférence (source de vérité = le client réel, pas d'invention).

## 3. Architecture serveur retenue
Serveur **Java réutilisant les classes du jeu** (jar décompilé), pas de réimplémentation
binaire :
1. **Contenu** (fait) : `server/content_server.py` — `index.txt` + redirection assets.
2. **Login HTTP** : répondre à `POST /login` (format à reverser dans `RPGMain`/`GameMain`).
3. **Jeu TCP** (squelette fait) : `LoginServer` réutilise `GruntNIOTCPServer` + codec +
   `MessageFactory` du jeu → `ClientInfo1` → `BootData1`. Framing/codec par les classes du jeu.

## 4. Fichiers clés (client, pour reprise)
```
com.perblue.heroes.ServerType                              # adresses réseau
com.perblue.heroes.network.NetworkProvider                 # connect/sendMessage/adresse
com.perblue.heroes.network.GameServerAddress
com.perblue.heroes.network.{XOR,DHXOR}ConnectionWrapper    # codec + clé
com.perblue.common.network.{XORConnectionWrapper,XORCipher,DeflateConnectionWrapper}
com.perblue.grunt.translate.{GruntMessageFactory,ConnectionWrapper}
com.perblue.heroes.network.messages.MessageFactory         # readMessage (registre)
com.perblue.heroes.network.messages.{ClientInfo,BootData}
com.perblue.heroes.*.AssetUpdater                          # contenu HTTP + révision embarquée
com.perblue.heroes.ui.windows.debug.SaveRestoreUserWindow  # save/restore user (persistance)
```

## 5. À faire
- [ ] Décompiler → confirmer §1 (framing/codec/clé XOR), §0 (ServerType LIVE), §1.3 (login).
- [ ] Champs **minimaux requis** de `BootData1` pour ne pas planter le client.
- [ ] Boucle serveur complète (accept → ClientInfo → BootData → messages suivants).

## 6. Flux post-BootData OBSERVÉ (empirique, client = source de vérité) — 2026-07-11
Serveur instrumenté (`dhserver.LoginServer` journalise chaque message reçu). Nouveau joueur (userID=0) :
```
(pré-login) ArchiveDownloadTracking1 x N, DownloadTime1   # télémétrie de téléchargement d'assets
ClientInfo1                         -> BootData1 (réponse)
ClockChange1, SettingsSync1, RecordNetworkEvents1, LoadTime1 x2, PerfReport1   # télémétrie (one-way)
Ping1                               -> **Ping1 (écho)** REQUIS
```
- **Ping1** (`timestamp/serverReceive/serverTime/serverDelay`) = keepalive/latence. **Sans écho, le
  chien de garde du client ferme la connexion** → boucle « Reconnecting… ». ⇒ le serveur DOIT échoer
  Ping (serverReceive/serverTime = now). ✅ implémenté → session STABLE dans le hub.
- **BootData nouveau joueur → TUTO — ✅ FAIT & VÉRIFIÉ EN JEU (2026-07-11).** `new BootData()` produit
  **tous** les champs non-null (initialiseurs du jeu — `userInfo/userExtra/privateUserInfo/guildInfo/
  currentServer/allContests/individualUserExtra/invasionInfo/specialEvents/…`, `statData* = HashMap`,
  `loginEvent=""`). Le routage tuto passe par **`individualUserExtra.tutorialActs`** :
  `handleBootData` → `getIndividualUser` → `IndividualUser.setExtra` remplit les actes ;
  `TutorialHelper.completedTutorialAct(type)` renvoie **true quand l'acte est ABSENT** (tuto « fait/sauté »),
  et se complète sur `step >= act.getMaxStep()`. ⇒ un nouveau joueur doit porter **TOUS** les
  `TutorialHelper.NEW_USER_ACTS` (122 types) à `step 0` (IN_PROG), sinon des features ne seraient jamais
  introduites. La liste + versions sont **lues dans le registre du jeu** (`NEW_USER_ACTS` + `ACTS`), zéro
  saisie — `server/java/dhserver/NewUserState.java`. **Fidélité** : INTRO = **`IntroTutorialActV2`** (v2, seule
  version enregistrée dans le 12.1.0 ; l'ancienne note « V1 » était erronée). Vérifié : le client lance
  IntroTutorialActV2 (INITIAL→SCREEN_WAIT→…→GATE_DIALOG_1_A), rend la scène d'ouverture (Ralph + Vanellope)
  et émet **`ChangeTutorialStep1`**. Les `SEVERE: Missing row in tutorials.tab` sont **intrinsèques au
  chargement de `tutorials.tab`** (incluent `REMOVED__CRYPT`, hors de mes actes) — comportement d'origine
  tolérant (`TutorialStats.onMissingRow`), pas causé par le serveur, aucune rustine.
- **Handlers du tuto (étape 4) — ✅ FAIT (intro).** Le tutoriel d'intro est **100% piloté par le client** :
  `IntroTutorialActV2` **n'émet aucun message réseau** et son combat est **local** (`CombatSimHelper`,
  `pause/resumeCombat`, timers). La **seule** sortie serveur est **`ChangeTutorialStep1`** (fire-and-forget).
  `ServerUser` applique la progression (`step` absolu ; `maxStep` = plus haut pas vu) → la reconnexion
  renvoie un BootData à jour. Vérifié en jeu (pilote headless `dh.autotap`) : intro jouable **de bout en
  bout jusqu'au 1ᵉʳ combat** (GATE_DIALOG → TRANSFORM → COMBAT1 cinématique + logo), serveur = 0 réponse,
  0 message inconnu. Les actions post-intro **server-validées** (nom, campagne réelle, récompenses) →
  handlers du hub (étape 6).
- **Incohérence stats `.tab` — ✅ NON-PROBLÈME (2 corrections successives de mes lectures)** :
  1ʳᵉ hypothèse « 7.9 vs 12.1.0 » = FAUSSE. 2ᵉ hypothèse « crash `<clinit>` » = **FAUSSE aussi**.
  Fait vérifié : la valeur `PREDICTIVE_FORTIFICATION` (ligne 159, EVIL_QUEEN) de
  `patched_heroes_talent_assignments.tab` (APK 12.1.0 de base) est absente du code 12.1.0 → `saveRow`
  lève `IllegalArgumentException`. MAIS `RowGeneralStats.parseStats` **enveloppe `saveRow` en try/catch →
  `onStatError`** qui **LOGue** (`LOG.error(msg, throwable)` — d'où la stack imprimée, illusion de crash)
  puis **`return`** ; la ligne est **sautée** (`finishRow(false)`). Vérifié : **0 `ExceptionInInitializerError`**
  dans le run. ⇒ **comportement d'origine du jeu** (tolère une `.tab` bootstrap imparfaite), **pas un
  crash, pas de rustine, rien à corriger**. Le serveur pourra envoyer `statDataTxt/Bin` pour de vrais
  changements d'équilibrage live, mais ce n'est ni un bug ni bloquant.
