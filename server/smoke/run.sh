#!/usr/bin/env bash
# Compile et exécute le smoke test du codec du jeu.
# Pré-requis : libs/game.jar + libs/commons-logging.jar (via tools/decompile.sh <apk>).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CP="$ROOT/libs/game.jar:$ROOT/libs/commons-logging.jar"
[[ -f "$ROOT/libs/game.jar" ]] || { echo "libs/game.jar manquant → lancer d'abord tools/decompile.sh <apk>" >&2; exit 1; }
export JAVA_TOOL_OPTIONS=
javac -cp "$CP" -d "$ROOT/server/smoke" "$ROOT/server/smoke/CodecRoundTrip.java"
# -Xverify:none : le bytecode dex2jar n'a pas les stackmap frames (contrôle de CHARGEMENT).
exec java -Xverify:none -cp "$CP:$ROOT/server/smoke" CodecRoundTrip
