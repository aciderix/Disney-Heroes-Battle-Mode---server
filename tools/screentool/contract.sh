#!/usr/bin/env bash
# OUTIL D'INDUSTRIALISATION (#73/#74) — CONTRAT d'écran ancré bytecode + couverture handlers.
# Usage : tools/screentool/contract.sh <prefixe1[,prefixe2,...]>
#         tools/screentool/contract.sh --mode <graine>   (#74 levier A : union AUTOMATIQUE du mode via ModeGraph)
#   ex.  tools/screentool/contract.sh com/perblue/heroes/ui/surge/SurgeScreen
#        tools/screentool/contract.sh --mode com/perblue/heroes/ui/surge/   (préfixe de package)
#        tools/screentool/contract.sh --mode Arena                          (token, mode éparpillé)
# --mode découvre TOUTES les classes du mode (écrans + hero choosers + helpers, même hors package) puis en
# extrait le contrat complet → supprime la « Limite 1 » (portée par-classe) de ScreenContract.
# Astuce : lister les écrans d'un mode →  unzip -l libs/game.jar | grep -oE 'com/perblue/heroes/ui/<mode>/[A-Za-z]+Screen'
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_TOOL_OPTIONS=
[ $# -ge 1 ] || { echo "Usage: $0 <prefixe-classe-ecran[,prefixe2,...]>  |  --mode <graine>"; exit 1; }

ASM="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"
[ -f "$ASM" ] || ASM="$ROOT/desktop-port/build/asm-9.7.jar"
[ -f "$ASM" ] || { echo "ASM 9.7 introuvable (lancer run-online.sh une fois pour le récupérer)"; exit 1; }
GAME="$ROOT/libs/game-framed.jar"; [ -f "$GAME" ] || GAME="$ROOT/libs/game.jar"

# 1) compiler les outils si besoin (ScreenContract + ModeGraph)
CLS="$ROOT/tools/screentool/classes"
if [ ! -f "$CLS/ScreenContract.class" ] || [ "$ROOT/tools/screentool/src/ScreenContract.java" -nt "$CLS/ScreenContract.class" ]; then
  mkdir -p "$CLS"; javac -cp "$ASM" -d "$CLS" "$ROOT/tools/screentool/src/ScreenContract.java" 2>&1 | grep -v 'Picked up' || true
fi
if [ ! -f "$CLS/ModeGraph.class" ] || [ "$ROOT/tools/screentool/src/ModeGraph.java" -nt "$CLS/ModeGraph.class" ]; then
  mkdir -p "$CLS"; javac -cp "$ASM" -d "$CLS" "$ROOT/tools/screentool/src/ModeGraph.java" 2>&1 | grep -v 'Picked up' || true
fi

# 1bis) --mode <graine> : découverte AUTOMATIQUE de l'union des classes du mode (ModeGraph), puis contrat dessus.
MODE_SEED=""
if [ "$1" = "--mode" ]; then
  [ $# -ge 2 ] || { echo "Usage: $0 --mode <graine>"; exit 1; }
  MODE_SEED="$2"
  echo "[mode] découverte des classes du mode (graine « $MODE_SEED ») via ModeGraph…"
  java -cp "$CLS:$ASM" ModeGraph "$GAME" "$MODE_SEED" 2>&1 | grep -v 'Picked up'
  UNION="$(java -cp "$CLS:$ASM" ModeGraph "$GAME" "$MODE_SEED" --list 2>/dev/null | grep -v 'Picked up')"
  [ -n "$UNION" ] || { echo "[mode] aucune classe trouvée pour cette graine"; exit 1; }
  set -- "$UNION"
  echo; echo "[mode] → CONTRAT COMPLET sur l'union ($(echo "$UNION" | tr ',' '\n' | wc -l) classes) :"; echo
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
