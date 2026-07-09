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

## Algorithme de l'AssetUpdater (décompilé — `com.perblue.heroes.assets_external`)

Classes : `ExternalAssetManager` (orchestre), `AssetIndexDownloader` (télécharge/parse
`index.txt`, décide), `ArchiveInfo` (une ligne : `url`, `revision`, `expectedFileSize`),
`ContentServerKeys` (noms de colonnes), `AssetCategory` (enum des catégories).

**1. Parse + filtrage des lignes** (`AssetIndexDownloader$3.onComplete` → `lambda$onComplete$0`) :
le client ne **retient** que les lignes dont :
- `Environment` == son environnement (`LIVE`) ;
- `Density`/`Compression` == celles de son device (SON : `soundDensity`/`soundCompression` ;
  TEXT : `Density` + compression enum ; PNG/monde : `density` + `compression`, ex. `XHDPI` +
  `ETC1`/`ETC2`/`PVRTC` selon GPU). ⇒ **on sert l'`index.txt` d'origine tel quel** ; le
  client sélectionne lui-même les lignes de son device.

**2. Filtrage par version** (`retainRowsForVersion(rows, gameVersion)`) :
retire les lignes dont `GameVersion` est **plus récente que la version du client**
(`gameVersion.compareTo(new VersionNumber(row.GameVersion)) < 0`). Autrement dit, un client
ne télécharge **jamais** du contenu prévu pour une version **plus récente** que lui ; le
contenu **égal ou plus ancien est conservé**.

**3. Décision par catégorie** (`checkArchives`, revision-based, **aucun test de GameVersion**) :
pour chaque `AssetCategory` où `shouldDownload()` :
- si l'URL d'index a changé (pref `<CAT>_INDEX`) → redownload complet forcé ;
- `getMostRecentCompleteArchive` = ligne `Mode==COMPLETE` & `Category` match, **révision max** ;
- si déjà téléchargée (pref bool sur l'`url`) → « up-to-date! » ;
- `getLatestDownloadedRevision` == -1 (install neuve) → **« complete download: rev N »** ;
- sinon si rev locale ≥ rev complète → applique `getNeededIncrementalArchives` (les
  `Mode==INCREMENTAL` de rev locale → cible) : **« incremental download »** ou « no incrementals ».
- Garde-fou anti-boucle : `ExternalAssetManager.handleBootLoop` (« Avoiding infinite
  download loop, external content state is broken »).

## ✅ RISQUE #1 — RÉSOLU (version APK vs GameVersion de l'index)
L'APK est en **12.1.0**, l'`index.txt` en `GameVersion 7.8.1/7.9`. `retainRowsForVersion`
ne retire que les lignes **plus récentes** que le client. Le client (12.1.0) étant **plus
récent** que `7.8.1`/`7.9`, **toutes les lignes archivées sont conservées**. Et `checkArchives`
décide **uniquement sur la révision** (jamais sur GameVersion). ⇒ **cet APK accepte les
assets archivés** : install neuve → télécharge `COMPLETE rev 325`, puis `INCREMENTAL rev 326`.
La colonne `GameVersion` de l'index n'est **pas** un critère de rejet ici (elle ne bloque que
du contenu *futur*). **Aucune rustine nécessaire** : on sert réellement le bon contenu.

> Risque résiduel (distinct, plus tard) : **complétude runtime**. Si le code 12.1.0 référence
> des assets plus récents que la rév. 325/326 archivée, certains peuvent manquer au *chargement*
> (autre chemin : « loadDynamicUI setting MISSING_ADDITIONAL true for … »). Cela se constate
> **en exécutant** le jeu ; à traiter alors proprement (compléter le contenu / tolérer les
> assets absents comme DragonSoul), pas par contournement du gate de téléchargement.

## Copie locale (option hébergeur)
Un hébergeur peut pré-télécharger les archives dans `assets-cache/` (non committé) pour
servir le contenu sans dépendre d'archive.org (latence, disponibilité). Un outil de
pré-fetch depuis `index.txt` sera ajouté (`tools/`).
