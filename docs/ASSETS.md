# ASSETS — pipeline de contenu (index.txt + archive.org) & obtention de l'APK

Voir [`PRINCIPLES.md`](PRINCIPLES.md) (§1 modifications minimales), [`RECON.md`](RECON.md),
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Obtenir l'APK (non committé)
L'APK est **copyright PerBlue** et pèse ~96 Mo → **non committé** (voir `.gitignore`).
Source : Google Drive `https://drive.google.com/file/d/1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF/view`.
Version : **12.1.0** (2023-02-22). C'est le **base APK** d'un App Bundle : il ne contient
**pas** les `.so` natifs (splits par ABI) — à récupérer séparément pour le port desktop/Android.

Après téléchargement, extraire les données de jeu (source de vérité) :
```bash
tools/extract_game_data.sh path/to/disney-heroes.apk
# → game-data/stats/*.tab (274) + game-data/strings/**/*.properties (325)
```

## Le manifeste `index.txt`
Présent dans le dépôt (`index.txt` == `disney_heroes_live_index.txt`). Format TSV avec
en-tête :
```
Mode  Category  Environment  Density  Compression  GameVersion  Revision  URL  Size
```
- `Mode` : `COMPLETE` (archive complète d'une catégorie, rev 325) ou `INCREMENTAL`
  (delta vers rev 326).
- `Category` : `WORLD_INITIAL_INTERNAL`, `WORLD_ADDITIONAL`, `UI_INITIAL`, `UI_DYNAMIC`,
  `SOUND`, `TEXT`/`TEXT_<lang>`, `FONT_*`, `UI_PARTICLES_INITIAL`, `UI_BOOSTER_INITIAL`…
- `URL` : `http://content.disneyheroesgame.com/live/<NOM>.zip` (serveur d'origine hors ligne).
- `Size` : taille en octets.

L'`AssetUpdater` du jeu télécharge `index.txt` puis les archives des catégories qui lui
manquent, et **gate le boot** tant que le contenu additionnel est absent (`MISSING_ADDITIONAL`).

## Assets archivés (archive.org)
Toutes les archives listées dans `index.txt` ont été **uploadées sur archive.org** :
`https://archive.org/download/disney-heroes-battle-mode-live-assets/<NOM>.zip`
(upload via `upload_batch.py` + `.github/workflows/upload_to_ia.yml`).

## Stratégie de remplacement du téléchargement (sans modifier le jeu)
Le serveur de contenu local (composant de la passerelle, cf. ARCHITECTURE.md) :
1. Répond à `GET /live/index.txt` par un manifeste **cohérent avec ce que cet APK attend**.
2. Pour chaque archive demandée, **redirige (HTTP 302) ou relaie** vers
   `archive.org/download/disney-heroes-battle-mode-live-assets/<NOM>.zip`, ou sert une
   **copie locale** (`assets-cache/`, non committée) si présente.

Ainsi l'`AssetUpdater` s'exécute **normalement** : le jeu télécharge/valide son contenu
comme en production, mais depuis l'archive. **Aucune rustine** : on ne force pas le flag
`MISSING_ADDITIONAL`, on fournit réellement le contenu.

> **RISQUE #1 (à résoudre)** : l'APK est en 12.1.0 alors que `index.txt` annonce
> `GameVersion 7.8.1 / Revision 325-326`. Il faut vérifier, par décompilation de
> l'`AssetUpdater`, **quelle révision/version de contenu cet APK exige**, et si les assets
> archivés (rev 325/326) la satisfont. Trois cas :
> - **Match** → on sert tel quel.
> - **L'APK exige une révision plus récente** → retrouver l'`index.txt`/les assets de la
>   bonne révision, ou l'APK correspondant à la révision archivée.
> - **Incrémental requis** → servir COMPLETE(325) + INCREMENTAL(326) tel que le jeu l'attend.
> Ne **jamais** contourner le contrôle de révision (principe §2).

## Copie locale (option hébergeur)
Un hébergeur peut pré-télécharger les archives dans `assets-cache/` (non committé) pour
servir le contenu sans dépendre d'archive.org (latence, disponibilité). Un outil de
pré-fetch depuis `index.txt` sera ajouté (`tools/`).
