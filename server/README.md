# Serveur — Disney Heroes

Composants du serveur ré-hébergeable. Voir [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md),
[`../docs/ASSETS.md`](../docs/ASSETS.md), [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## `content_server.py` — serveur de contenu v0 (assets) ✅
Remplace `content.disneyheroesgame.com` (hors ligne) pour satisfaire l'`AssetUpdater` du
jeu **sans le modifier**. Stdlib Python, zéro dépendance → hébergeable partout.

```bash
server/run-content-server.sh --port 8080
# ou : python3 server/content_server.py --port 8080 [options]
```

Options (ou variables d'environnement) :
| Option | Env | Défaut | Rôle |
|---|---|---|---|
| `--port` | `DH_CONTENT_PORT` | `8080` | port d'écoute |
| `--host` | `DH_CONTENT_BIND` | `0.0.0.0` | interface d'écoute |
| `--index` | — | `../index.txt` | manifeste servi |
| `--cache` | `DH_ASSETS_CACHE` | `../assets-cache` | copies locales des `.zip` (prioritaires) |
| `--archive-base` | `DH_ARCHIVE_BASE` | archive.org du projet | base de redirection des `.zip` |
| `--rewrite-host` | `DH_REWRITE_HOST` | en-tête `Host` | host:port écrit dans les URLs de l'index |

Endpoints :
- `GET /live/index.txt` → manifeste, **URLs d'archives réécrites** vers ce serveur
  (`/live/<nom>.zip`). Le jeu filtre lui-même par device/version (cf. ASSETS.md).
- `GET|HEAD /live/<nom>.zip` → copie locale (`--cache`) si présente, sinon **302** vers
  l'archive publique (archive.org, qui gère les requêtes *range*).
- `GET /health` → sonde.

Vérifié (2026-07-09) : index réécrit correctement ; `.zip` → 302 → archive.org → 200 avec
`Content-Length` identique à la colonne `Size` de l'index.

### Brancher le jeu dessus
Rediriger `ServerType.LIVE.contentLocation` vers `http://<host>:<port>/live/index.txt`
(réécriture par réflexion au démarrage du launcher / passerelle locale, **sans patch
bytecode** — cf. PROTOCOL.md §0). L'APK n'étant pas patché, LIVE pointe par défaut vers les
domaines PerBlue hors ligne.

### Copie locale (hébergeur autonome)
Pré-télécharger les `.zip` dans `assets-cache/` (non committé) pour servir sans dépendre
d'archive.org. Un outil de pré-fetch depuis `index.txt` sera ajouté (`tools/`).

## À venir
- Serveur de **login** (`POST /login`) + **protocole de jeu** TCP (Java, réutilise les
  classes du jeu : `DHXORConnectionWrapper`, `MessageFactory`, `BootData`).
- **Passerelle** unifiée (route contenu HTTP vs jeu TCP) + **mot de passe** + **liste de
  serveurs**. Cf. ARCHITECTURE.md.
