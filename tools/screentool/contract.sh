#!/usr/bin/env bash
# OUTIL D'INDUSTRIALISATION (#73) — CONTRAT d'écran ancré bytecode + couverture handlers.
# Usage : tools/screentool/contract.sh <prefixe1[,prefixe2,...]>
#   ex.  tools/screentool/contract.sh com/perblue/heroes/ui/surge/SurgeScreen
#        tools/screentool/contract.sh com/perblue/heroes/ui/invasion/InvasionBreakerScreen,com/perblue/heroes/ui/invasion/InvasionBreakerQuestDisplay
# Astuce : lister les écrans d'un mode →  unzip -l libs/game.jar | grep -oE 'com/perblue/heroes/ui/<mode>/[A-Za-z]+Screen'
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_TOOL_OPTIONS=
[ $# -ge 1 ] || { echo "Usage: $0 <prefixe-classe-ecran[,prefixe2,...]>"; exit 1; }

ASM="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"
[ -f "$ASM" ] || ASM="$ROOT/desktop-port/build/asm-9.7.jar"
[ -f "$ASM" ] || { echo "ASM 9.7 introuvable (lancer run-online.sh une fois pour le récupérer)"; exit 1; }
GAME="$ROOT/libs/game-framed.jar"; [ -f "$GAME" ] || GAME="$ROOT/libs/game.jar"

# 1) compiler l'outil si besoin
CLS="$ROOT/tools/screentool/classes"
if [ ! -f "$CLS/ScreenContract.class" ] || [ "$ROOT/tools/screentool/src/ScreenContract.java" -nt "$CLS/ScreenContract.class" ]; then
  mkdir -p "$CLS"; javac -cp "$ASM" -d "$CLS" "$ROOT/tools/screentool/src/ScreenContract.java" 2>&1 | grep -v 'Picked up' || true
fi

# 2) classes serveur À JOUR (pour la couverture handlers = instanceof de LoginServer*)
SRV="$ROOT/tools/screentool/server-classes"
CPF="$GAME:$ROOT/libs/commons-logging.jar:$ROOT/libs/sqlite-jdbc.jar:$ROOT/libs/slf4j-api.jar:$ROOT/libs/joda-time.jar"
if [ ! -d "$SRV" ] || [ -n "$(find "$ROOT/server/java" -name '*.java' -newer "$SRV" 2>/dev/null | head -1)" ]; then
  rm -rf "$SRV"; mkdir -p "$SRV"
  javac -cp "$CPF" -d "$SRV" $(find "$ROOT/server/java" -name '*.java') 2>&1 | grep -viE 'Picked up|warning|Note:' || true
fi

# 3) rapport
java -cp "$CLS:$ASM:$GAME" ScreenContract "$GAME" "$SRV" "$1" 2>&1 | grep -v 'Picked up'
