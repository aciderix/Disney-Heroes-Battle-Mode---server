#!/usr/bin/env bash
# Extract the game's authoritative data tables from the Disney Heroes APK.
#
# Principle (see docs/PRINCIPLES.md §4): the server must NEVER hand-copy game
# data. All balance tables, stats, items, skills, etc. are extracted directly
# from the official APK by this script, so the data stays in sync with the game.
#
# Usage: tools/extract_game_data.sh path/to/disney-heroes.apk
#
# Populates:
#   game-data/stats/*.tab          274 tab-separated balance/stat tables
#   game-data/strings/**/*.properties   localized strings (source of truth for text)
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 path/to/disney-heroes.apk" >&2
  echo "  (the APK is not committed — see docs/ASSETS.md for how to obtain it)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/game-data"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Extracting game data from $APK ..."
unzip -o -q "$APK" "assets/stats/*" "assets/strings/*" -d "$TMP"

rm -rf "$DEST/stats" "$DEST/strings"
mkdir -p "$DEST"
mv "$TMP/assets/stats"   "$DEST/stats"
mv "$TMP/assets/strings" "$DEST/strings"

echo "  stats:   $(find "$DEST/stats"   -type f | wc -l) files"
echo "  strings: $(find "$DEST/strings" -type f | wc -l) files"
echo "Done. Data is the server's source of truth; commit it after extraction."
