# PROTOCOL — réseau & contenu (reverse depuis le jeu)

Établi **depuis le bytecode du client** (source de vérité). But : savoir ce que le serveur
doit servir pour franchir le chargement (login → BootData) et jouer. Disney Heroes réutilise
la **même conception** que DragonSoul (cf. dépôt de référence `desktop-port/PROTOCOL.md`),
mais avec des **noms de classes non obfusqués** → voir [`RECON.md`](RECON.md).

> Statut : cette 1ʳᵉ version s'appuie sur la recon `strings` + le protocole DragonSoul
> (identique par construction studio). Les valeurs marquées **[À EXTRAIRE]** doivent être
> confirmées par décompilation de cet APK avant toute implémentation (principe §3 : la
> source de vérité est le jeu, pas notre mémoire).

## 0. Cible réseau — `com.perblue.heroes.ServerType`
Constructeur (protocol, gameHost, gamePort, contentLocation). Observé :
- Contenu LIVE : `http://content.disneyheroesgame.com/live/index.txt`
- Login : `login.disneyheroesgame.com` (staging : `login.staging…`, `dhstaging…:10070`)
- **[À EXTRAIRE]** : gameHost/gamePort/protocole exacts de LIVE, et si l'APK est patché
  (DragonSoul « Fixed2 » était patché vers `127.0.0.1:8080`). Pour un port propre, on
  réécrira l'adresse cible vers notre serveur (par la passerelle ou réécriture `ServerType`
  au démarrage — **sans patcher le bytecode**).

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
- **Clé XOR** : **[À EXTRAIRE]** de `DHXORConnectionWrapper` (secret partagé constant, pas
  de handshake). Le serveur utilisera la **même** classe/clé (XOR symétrique).

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

## 3. Architecture serveur retenue
Serveur **Java réutilisant les classes du jeu** (jar décompilé), pas de réimplémentation
binaire :
1. `ServerSocket`. Peek des 1ers octets → HTTP (contenu/login) vs TCP jeu.
2. Contenu : mini-HTTP `index.txt` + redirection assets.
3. Login : répondre à `POST /login`.
4. Jeu : `DHXORConnectionWrapper` (codec+clé exacts) → lire frames
   `[int32 LE len][wrapIn → MessageFactory.readMessage]` → `ClientInfo` → construire et
   renvoyer `BootData` (`writeAll` + `wrapOut` + préfixe longueur).

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
