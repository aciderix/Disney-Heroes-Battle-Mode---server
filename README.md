# Disney Heroes: Battle Mode — Portage Desktop + Serveur

Projet de **préservation** : faire tourner *Disney Heroes: Battle Mode* (PerBlue, serveurs
fermés) sur **Windows / Linux / Android** avec un **serveur autoritatif** que n'importe quel
joueur peut héberger — en réutilisant au maximum le code et les données du jeu, avec le
**minimum de modifications**. Approche reproduite depuis le portage DragonSoul du même studio.

## Démarrage rapide (agents & contributeurs)
- **Lire d'abord [`MEMORY.md`](MEMORY.md)** — récupération de contexte : état courant,
  architecture, liens, hiérarchie des fichiers, prochaines étapes.
- **Journal détaillé** : [`JOURNAL.md`](JOURNAL.md).
- **Règles de dev (non négociables)** : [`docs/PRINCIPLES.md`](docs/PRINCIPLES.md).

## Documentation
| Doc | Contenu |
|---|---|
| [`docs/PRINCIPLES.md`](docs/PRINCIPLES.md) | Principes : modifs minimales, aucune rustine, serveur = source de vérité, extraction auto des données. |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Architecture cible (backend desktop + passerelle + serveur), multi-serveur, sécurité. |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | Protocole réseau & contenu (reverse depuis le bytecode). |
| [`docs/ASSETS.md`](docs/ASSETS.md) | Pipeline de contenu (`index.txt` + archive.org), obtention de l'APK, RISQUE version. |
| [`docs/RECON.md`](docs/RECON.md) | Findings de reconnaissance de l'APK. |

## Données du jeu (source de vérité)
Extraites de l'APK, **jamais recopiées à la main** :
```bash
tools/extract_game_data.sh path/to/disney-heroes.apk
```
→ `game-data/stats/*.tab` (274 tables d'équilibrage) + `game-data/strings/**/*.properties`.

## Assets
Le manifeste `index.txt` est présent ; les archives d'assets live sont préservées sur
[archive.org](https://archive.org/download/disney-heroes-battle-mode-live-assets). Voir
[`docs/ASSETS.md`](docs/ASSETS.md).

## Statut
Bootstrap réalisé (recon APK, extraction des données, docs, mémoire). Prochaine étape :
décompilation ciblée (clé XOR, `ServerType`, révision de contenu) puis serveur de contenu v0.
Voir [`MEMORY.md`](MEMORY.md) §7.

> Projet communautaire de préservation, sans lien avec PerBlue/Disney. Les binaires et
> assets du jeu restent la propriété de leurs ayants droit et ne sont pas redistribués ici.
