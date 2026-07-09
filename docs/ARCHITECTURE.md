# ARCHITECTURE — portage desktop + serveur (miroir DragonSoul)

Cible : faire tourner Disney Heroes (quasi non modifié) sur desktop/Android avec un
serveur autoritatif ré-hébergeable. Conçu **en miroir** du portage DragonSoul (dépôt de
référence `dragonsoul-web`, dossier `desktop-port/`). Voir [`PRINCIPLES.md`](PRINCIPLES.md),
[`PROTOCOL.md`](PROTOCOL.md), [`ASSETS.md`](ASSETS.md).

## Vue d'ensemble

```
        ┌─────────────────────────────────────────┐
        │  Jeu Disney Heroes (libGDX, bytecode)    │
        │  com.perblue.heroes.*  (réutilisé tel    │
        │  quel ; remap non-sémantique si besoin)  │
        └───────────────┬─────────────────────────┘
     couche plateforme  │  (remplacée sur desktop)
   ┌────────────────────▼─────────────────────────┐
   │  Backend desktop LWJGL3 « dhbackend »        │  (miroir de dsbackend/)
   │  Application/Graphics/Input/Files/Audio/GL/Net│
   └────────────────────┬─────────────────────────┘
        Socket TCP brut  │  + HTTP (login/contenu)
   ┌────────────────────▼─────────────────────────┐
   │  Passerelle locale 127.0.0.1                  │
   │   • sert le contenu (index.txt)               │
   │   • redirige les assets → archive.org / copie │
   │   • auth mot de passe (hors protocole jeu)    │
   │   • relaie le protocole de jeu vers →         │
   └────────────────────┬─────────────────────────┘
                         │  host:port (choisi)
   ┌────────────────────▼─────────────────────────┐
   │  Serveur hébergeable (Java)                   │
   │   • réutilise les classes DU JEU (codec XOR,  │
   │     MessageFactory, BootData…)                │
   │   • login (ClientInfo→BootData), monde/état   │
   │   • autoritatif ; données = game-data/ (.tab) │
   │   • persistance SQLite ; mot de passe ; liste │
   └───────────────────────────────────────────────┘
```

## Composants

### A. Backend desktop (`dhbackend/`, à créer — miroir `dsbackend/`)
Remplace la couche Android par une implémentation LWJGL3 :
`Application`, `Graphics`, `Input`, `Files`, `Audio` (OpenAL + STB Vorbis), `GL20`,
`Net` (HTTP via `HttpURLConnection`), `Preferences`, `DeviceInfo`, bridges
(Social/Analytics/… en NO-OP documentés). Chaque shim est **RÉEL** ou explicitement
noté (voir principe §2). Un `DsDriver`-équivalent permet le **pilotage headless** (scripts
tap/move/key/screenshot) pour les tests automatisés.

Décisions de build à confirmer sur cette version : version exacte de libGDX (parité ABI
native), densité/compression d'assets fournies (l'index.txt liste `XHDPI` + `ETC1/ETC2/
PVRTC`), remap ASM des collisions de noms si présentes.

### B. Serveur hébergeable (Java, réutilise les classes du jeu)
- **Codec identique au client** : `DHXORConnectionWrapper` / `XORConnectionWrapper` +
  `MessageFactory` du jeu → sérialisation/chiffrement 100 % conformes, zéro
  réimplémentation binaire.
- **Modules** : login (`ClientInfo1` → `BootData1`), monde/état, contenu (index.txt).
- **Données** : chargées depuis `game-data/` (les `.tab`/`.properties` extraits), **jamais**
  recopiées à la main. Le serveur applique les règles **en miroir du code du jeu**.
- **Persistance** : SQLite (un état complet par joueur).
- **Config hébergeur** : nom, port, mot de passe optionnel, déclaration à un directory.

### C. Multi-serveur, sécurité, découverte
- **Passerelle locale** : le jeu croit parler à son adresse LIVE ; la passerelle route
  (contenu HTTP vs protocole jeu), gère l'**auth mot de passe** (challenge-réponse
  `HMAC(mdp, nonce)`) **hors** du protocole du jeu, puis relaie.
- **Mode sécurisé** : option de dériver la clé du codec passerelle↔serveur depuis le mdp.
- **Découverte** : Direct IP (toujours) + beacon LAN (UDP) + **liste communautaire JSON**
  (URL configurable, décentralisable). Master server = option future.
- **Sélecteur de serveur** au démarrage (UI launcher, hors UI du jeu).

## Spécificité Disney Heroes vs DragonSoul
- Noms **non obfusqués** → réutilisation directe des classes du jeu (codec, MessageFactory).
- `index.txt` **disponible** + assets **archivés** → l'`AssetUpdater` peut être satisfait
  proprement en redirigeant les téléchargements (voir `ASSETS.md`), au lieu de gérer des
  assets manquants comme chez DragonSoul.

## Découpage incrémental (vérifiable à chaque étape)
1. Décompiler → jar + extraire clé XOR / `ServerType` / révision de contenu.
2. **Serveur de contenu v0** : sert `index.txt` + redirige assets → débloque l'AssetUpdater.
3. **Backend desktop minimal** : lance le jeu jusqu'à l'écran de chargement.
4. **Serveur login v1** : `ClientInfo1` → `BootData1` (classes du jeu) → franchir le loading.
5. **Persistance** + **sélecteur/mot de passe** + **directory**.
6. **Serveur autoritatif** complet (règles en miroir du jeu, données `game-data/`).
