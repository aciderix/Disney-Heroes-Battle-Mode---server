#!/usr/bin/env bash
# Compile et exécute les smoke tests qui prouvent la réutilisation des classes du jeu :
#   - CodecRoundTrip     : codec réseau (DHXORConnectionWrapper = Deflate + XOR)
#   - MessageRoundTrip   : sérialisation des messages (BootData writeAll <-> MessageFactory)
# Pré-requis : libs/game.jar + libs/commons-logging.jar (via tools/decompile.sh <apk>).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CP="$ROOT/libs/game.jar:$ROOT/libs/commons-logging.jar"
[[ -f "$ROOT/libs/game.jar" ]] || { echo "libs/game.jar manquant → lancer d'abord tools/decompile.sh <apk>" >&2; exit 1; }
export JAVA_TOOL_OPTIONS=
SMOKE="$ROOT/server/smoke"
javac -cp "$CP" -d "$SMOKE" "$SMOKE"/CodecRoundTrip.java "$SMOKE"/MessageRoundTrip.java
# -Xverify:none : le bytecode dex2jar n'a pas les stackmap frames (contrôle de CHARGEMENT).
echo "--- CodecRoundTrip ---";   java -Xverify:none -cp "$CP:$SMOKE" CodecRoundTrip
echo "--- MessageRoundTrip ---"; java -Xverify:none -cp "$CP:$SMOKE" MessageRoundTrip
