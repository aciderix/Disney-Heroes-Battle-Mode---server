#!/usr/bin/env bash
# Télécharge des archives d'assets depuis l'archive.org du projet et les extrait dans le
# dossier d'assets classpath du port desktop, pour fournir localement le contenu que le jeu
# téléchargeait jadis (WORLD/UI/SOUND/TEXT…). Contenu RÉEL (pas de rustine) — cf. docs/ASSETS.md.
#
# Usage : tools/fetch_assets.sh [CATEGORIE ...]
#   sans argument : jeu d'archives "initial" ETC1 (le minimum pour booter le rendu).
#   sinon : noms de fichiers .zip (ou motifs) présents dans l'index.
#
# Les .zip sont cachés dans assets-cache/ (non committé) ; extraits dans
# desktop-port/build/apk/assets/ (racine classpath du jeu, ex. ETC1/world/...).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IA="https://archive.org/download/disney-heroes-battle-mode-live-assets"
CACHE="$ROOT/assets-cache"
DEST="$ROOT/desktop-port/build/apk/assets"
mkdir -p "$CACHE" "$DEST"

# Jeu par défaut : contenu "initial" ETC1 (densité XHDPI) + TEXT — suffisant pour le boot.
DEFAULT=(
  COMPLETE_LIVE_WORLD_INITIAL_INTERNAL_XHDPI_ETC1_325.zip
  COMPLETE_LIVE_UI_INITIAL_XHDPI_ETC1_325.zip
  COMPLETE_LIVE_UI_DYNAMIC_XHDPI_ETC1_325.zip
  COMPLETE_LIVE_UI_PARTICLES_INITIAL_XHDPI_ETC1_325.zip
  COMPLETE_LIVE_UI_BOOSTER_INITIAL_XHDPI_ETC1_325.zip
  COMPLETE_LIVE_TEXT_XHDPI_TXT_325.zip
)

ARCHIVES=("$@")
[ ${#ARCHIVES[@]} -eq 0 ] && ARCHIVES=("${DEFAULT[@]}")

for name in "${ARCHIVES[@]}"; do
  zip="$CACHE/$name"
  if [ ! -f "$zip" ]; then
    echo "[assets] téléchargement $name ..."
    curl -fSL -o "$zip" "$IA/$name" || { echo "[assets] ÉCHEC $name" >&2; rm -f "$zip"; continue; }
  fi
  echo "[assets] extraction $name -> $DEST"
  unzip -oq "$zip" -d "$DEST"
done
echo "[assets] terminé. Assets dans $DEST"
