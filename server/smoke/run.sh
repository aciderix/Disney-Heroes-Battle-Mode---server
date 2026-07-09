#!/usr/bin/env bash
# Compile et exécute les smoke tests prouvant la réutilisation des classes du jeu :
#   - CodecRoundTrip     : codec réseau (DHXORConnectionWrapper = Deflate + XOR)
#   - MessageRoundTrip   : sérialisation des messages (BootData writeAll <-> MessageFactory)
#   - HandshakeRoundTrip : handshake login TCP bout-en-bout (ClientInfo1 -> BootData1)
# Pré-requis : libs/game.jar + libs/commons-logging.jar (via tools/decompile.sh <apk>).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CP="$ROOT/libs/game.jar:$ROOT/libs/commons-logging.jar"
[[ -f "$ROOT/libs/game.jar" ]] || { echo "libs/game.jar manquant → lancer d'abord tools/decompile.sh <apk>" >&2; exit 1; }
export JAVA_TOOL_OPTIONS=
SMOKE="$ROOT/server/smoke"
JAVASRC="$ROOT/server/java"
OUT="$SMOKE/out"; rm -rf "$OUT"; mkdir -p "$OUT"

# Compile les sources serveur (fabrique same-package + LoginServer) puis les smoke tests.
javac -cp "$CP" -d "$OUT" \
  $(find "$JAVASRC" -name '*.java') \
  "$SMOKE"/CodecRoundTrip.java "$SMOKE"/MessageRoundTrip.java "$SMOKE"/HandshakeRoundTrip.java

RUN=(java -Xverify:none -cp "$CP:$OUT")
echo "--- CodecRoundTrip ---";     "${RUN[@]}" CodecRoundTrip
echo "--- MessageRoundTrip ---";   "${RUN[@]}" MessageRoundTrip
echo "--- HandshakeRoundTrip ---"; "${RUN[@]}" HandshakeRoundTrip
