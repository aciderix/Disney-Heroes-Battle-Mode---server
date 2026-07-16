#!/usr/bin/env bash
# Compile et exécute les smoke tests prouvant la réutilisation des classes du jeu :
#   - CodecRoundTrip     : codec réseau (DHXORConnectionWrapper = Deflate + XOR)
#   - MessageRoundTrip   : sérialisation des messages (BootData writeAll <-> MessageFactory)
#   - HandshakeRoundTrip : handshake login TCP bout-en-bout (ClientInfo1 -> BootData1)
# Pré-requis : libs/game.jar + libs/commons-logging.jar (via tools/decompile.sh <apk>).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[[ -f "$ROOT/libs/game.jar" ]] || { echo "libs/game.jar manquant → lancer d'abord tools/decompile.sh <apk>" >&2; exit 1; }
export JAVA_TOOL_OPTIONS=

# game.jar (dex2jar) n'a pas de StackMapTable → sous -Xverify:none la JVM plante par intermittence
# au GC (generateOopMap.cpp, « Illegal class file … in method getDefaultStats », SIGABRT non
# déterministe). Si game-framed.jar (COMPUTE_FRAMES, produit par run-online.sh) existe, on l'utilise
# SANS -Xverify:none (tests déterministes). Sinon, repli sur game.jar + -Xverify:none (le crash reste
# possible mais rare sur ces tests courts). Sémantique identique dans les deux cas.
if [[ -f "$ROOT/libs/game-framed.jar" ]]; then
  GAMEJAR="$ROOT/libs/game-framed.jar"; VERIFY=()
else
  GAMEJAR="$ROOT/libs/game.jar"; VERIFY=(-Xverify:none)
fi
CP="$GAMEJAR:$ROOT/libs/commons-logging.jar"
SMOKE="$ROOT/server/smoke"
JAVASRC="$ROOT/server/java"
OUT="$SMOKE/out"; rm -rf "$OUT"; mkdir -p "$OUT"

# Compile les sources serveur (fabrique same-package + LoginServer) puis les smoke tests réseau.
# NB : HandshakeRoundTrip est OBSOLÈTE (ancien ctor LoginServer(port, provider), remplacé par
# LoginServer(port, ServerUser, UserStore) — cf. SHIMS TODO #4) → retiré du harnais. Les tests de
# régression courants (logique du jeu) se lancent à part avec le classpath complet (sqlite/joda/slf4j) :
#   ResourceTest, CampaignAttackTest, CampaignPersistTest, EquipTest, ChestWireTest, …
javac -cp "$CP" -d "$OUT" \
  $(find "$JAVASRC" -name '*.java') \
  "$SMOKE"/CodecRoundTrip.java "$SMOKE"/MessageRoundTrip.java

RUN=(java "${VERIFY[@]}" -cp "$CP:$OUT")
echo "--- CodecRoundTrip ---";     "${RUN[@]}" CodecRoundTrip
echo "--- MessageRoundTrip ---";   "${RUN[@]}" MessageRoundTrip
