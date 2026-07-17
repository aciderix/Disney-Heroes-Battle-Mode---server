#!/usr/bin/env bash
# Lance la pile complète en local et le client, pour tester le flux de connexion RÉEL :
#   - serveur de contenu + login (Python) sur :8080  (POST /login -> adresse du serveur de jeu)
#   - serveur de jeu TCP (Java, LoginServer) sur :8081  (ClientInfo1 -> BootData1)
#   - client desktop, ServerType.LIVE redirigé vers 127.0.0.1:8080
# But : vérifier que le vrai client se connecte à NOTRE serveur (pas de rustine).
set -e
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
export JAVA_TOOL_OPTIONS=

HTTP_PORT=8080
GAME_PORT=8081
CP="$ROOT/libs/game.jar:$ROOT/libs/commons-logging.jar:$ROOT/libs/sqlite-jdbc.jar:$ROOT/libs/slf4j-api.jar:$ROOT/libs/joda-time.jar"

# Dépendances serveur — récupérées à la demande (non committées, régénérables), comme ASM pour le
# port. Le serveur reste sans dépendance à installer par l'utilisateur.
MVN="https://repo1.maven.org/maven2"
[ -f "$ROOT/libs/sqlite-jdbc.jar" ] || curl -fsSL -o "$ROOT/libs/sqlite-jdbc.jar" \
    "$MVN/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar"
[ -f "$ROOT/libs/slf4j-api.jar" ] || curl -fsSL -o "$ROOT/libs/slf4j-api.jar" \
    "$MVN/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
# joda-time : game.jar contient les CLASSES joda (dex2jar) mais PAS la donnée fuseaux
# (org/joda/time/tz/data/*), que TimeUtil charge → on fournit le jar standard (ses classes sont
# ombrées par celles de game.jar, seule la ressource data est utilisée). C'est une donnée du jeu.
[ -f "$ROOT/libs/joda-time.jar" ] || curl -fsSL -o "$ROOT/libs/joda-time.jar" \
    "$MVN/joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"

# --- Reframe (StackMapTable) de game.jar → game-framed.jar (comme le client, cf. run-desktop.sh) ---
# Le bytecode dex2jar de game.jar n'a pas de StackMapTable ; sous -Xverify:none la JVM calcule
# paresseusement les oop-maps (generateOopMap.cpp) et PLANTE par intermittence pendant un GC qui
# scanne certaines méthodes (« Illegal class file ... in method getDefaultStats », SIGABRT non
# déterministe — observé sur les stats/perks de guilde). On réécrit game.jar avec COMPUTE_FRAMES
# (frames valides) → vérificateur rapide par table, plus de generateOopMap, et on RETIRE -Xverify:none.
# Régénéré à la demande (non committé), comme game-logic-framed.jar côté client. Sémantique inchangée.
ASM="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"
[ -f "$ASM" ] || { ASM="build/asm-9.7.jar"; [ -f "$ASM" ] || { mkdir -p build; curl -fsSL -o "$ASM" "$MVN/org/ow2/asm/asm/9.7/asm-9.7.jar"; }; }
REFRAME_CLS="$ROOT/tools/reframe/classes"
[ -f "$REFRAME_CLS/ReframeJar.class" ] || { mkdir -p "$REFRAME_CLS"; javac -cp "$ASM" -d "$REFRAME_CLS" "$ROOT/tools/reframe/src/ReframeJar.java"; }
FRAMED="$ROOT/libs/game-framed.jar"
if [ ! -f "$FRAMED" ] || [ "$ROOT/libs/game.jar" -nt "$FRAMED" ]; then
  echo "[online] reframe de game.jar (COMPUTE_FRAMES, ~15s) ..."
  java -cp "$REFRAME_CLS:$ASM:$ROOT/libs/game.jar" ReframeJar "$ROOT/libs/game.jar" "$FRAMED" 2>&1 | grep -v 'Picked up' || true
fi
# Classpath serveur : game-framed.jar (frames valides) À LA PLACE de game.jar → plus de -Xverify:none.
CPF="$FRAMED:$ROOT/libs/commons-logging.jar:$ROOT/libs/sqlite-jdbc.jar:$ROOT/libs/slf4j-api.jar:$ROOT/libs/joda-time.jar"

# Compile le serveur de jeu (server/java) si besoin.
SRVOUT="build/server-classes"; mkdir -p "$SRVOUT"
javac -cp "$CPF" -d "$SRVOUT" $(find "$ROOT/server/java" -name '*.java') 2>&1 | grep -v 'Picked up' || true

cleanup() { for p in "$CONTENT_PID" "$GAME_PID"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

# --- Garde-fou : détecter/arrêter d'ANCIENS serveurs de CE projet avant d'en relancer ---
# Évite les serveurs zombies (plusieurs LoginServer sur le même port → le client parle à l'un
# et on lit le log de l'autre). DH_KILL_OLD=0 pour seulement alerter et abandonner.
port_busy() { (exec 3<>/dev/tcp/127.0.0.1/"$1") 2>/dev/null; }
# Détection des ANCIENS process de CE projet : serveurs (LoginServer/content_server) ET client jeu
# (DesktopLauncher) ET Xvfb :99. Un client orphelin d'une run précédente peut fausser les captures /
# retenir le display → on le nettoie aussi (pas seulement les serveurs).
old=$(pgrep -f 'dhserver.LoginServer|content_server.py|dhdesktop.DesktopLauncher' 2>/dev/null | tr '\n' ' ' || true)
if [ -n "$old" ] || port_busy "$HTTP_PORT" || port_busy "$GAME_PORT"; then
  echo "[online] ⚠ process DÉJÀ en cours (PID: ${old:-?} ; ports $HTTP_PORT/$GAME_PORT)."
  if [ "${DH_KILL_OLD:-1}" = "1" ]; then
    echo "[online]   → arrêt des anciens (serveur + client + Xvfb ; DH_KILL_OLD=0 pour désactiver)"
    pkill -f 'dhserver.LoginServer'    2>/dev/null || true
    pkill -f 'content_server.py'       2>/dev/null || true
    pkill -f 'dhdesktop.DesktopLauncher' 2>/dev/null || true
    pkill -f 'Xvfb :99'                2>/dev/null || true
    for i in 1 2 3 4 5; do port_busy "$HTTP_PORT" || port_busy "$GAME_PORT" || break; sleep 1; done
  fi
  if port_busy "$HTTP_PORT" || port_busy "$GAME_PORT"; then
    echo "[online] ✖ port(s) toujours occupé(s) ($HTTP_PORT/$GAME_PORT) — abandon."
    echo "[online]   Libère-les (ex. 'pkill -f dhserver.LoginServer; pkill -f content_server.py') puis relance."
    exit 1
  fi
fi

echo "[online] serveur de contenu+login :$HTTP_PORT ..."
python3 "$ROOT/server/content_server.py" --port "$HTTP_PORT" --rewrite-host "127.0.0.1:$HTTP_PORT" \
        --game-server "127.0.0.1:$GAME_PORT" >/tmp/dh_content.log 2>&1 &
CONTENT_PID=$!

echo "[online] serveur de jeu TCP :$GAME_PORT ..."
# game-framed.jar a des StackMapTable valides → plus de -Xverify:none (plus de SIGABRT oop-map).
# On garde -XX:TieredStopAtLevel=1 (C1 seul) par prudence sur le bytecode dex2jar (le C2 avait planté).
java -XX:TieredStopAtLevel=1 -Ddh.db="$ROOT/server/data/dh-server.db" \
     -Ddh.stats="$ROOT/game-data/stats" \
     -cp "$CPF:$SRVOUT" dhserver.LoginServer "$GAME_PORT" >/tmp/dh_game.log 2>&1 &
GAME_PID=$!
sleep 2

echo "[online] client (ServerType.LIVE -> 127.0.0.1:$HTTP_PORT) ..."
# DH_FRAMES : non défini → 120 frames ; défini VIDE (DH_FRAMES=) → NON plafonné (joue tout le tuto,
# borné par DH_TIMEOUT). DH_AUTOTAP/DH_AUTOFIGHT passent au pilote DEV.
DH_SERVER="127.0.0.1:$HTTP_PORT" DH_TIMEOUT="${DH_TIMEOUT:-90}" DH_FRAMES="${DH_FRAMES-120}" \
    DH_AUTOTAP="${DH_AUTOTAP:-}" DH_AUTOFIGHT="${DH_AUTOFIGHT:-}" \
    DH_TUTODBG="${DH_TUTODBG:-}" DH_FPS="${DH_FPS:-}" DH_SHOTEVERY="${DH_SHOTEVERY:-}" DH_TUTOREC="${DH_TUTOREC:-}" DH_TAPHOLD="${DH_TAPHOLD:-}" \
    DH_MAPPROBE="${DH_MAPPROBE:-}" DH_PROBEACTOR="${DH_PROBEACTOR:-}" \
    DH_COMBATSPIKE="${DH_COMBATSPIKE:-}" DH_COMBATSPIKE_EXIT="${DH_COMBATSPIKE_EXIT:-}" \
    DH_COMBATSPIKE_CH="${DH_COMBATSPIKE_CH:-}" DH_COMBATSPIKE_LV="${DH_COMBATSPIKE_LV:-}" \
    DH_COMBATSPIKE_SEED="${DH_COMBATSPIKE_SEED:-}" DH_CSPINEPROFILE="${DH_CSPINEPROFILE:-}" \
    DH_SPINEBACKEND="${DH_SPINEBACKEND:-}" \
    DH_SHOT="${DH_SHOT:-build/online.ppm}" ./run-desktop.sh || true

echo "=== log serveur de jeu (TCP) ==="; tail -15 /tmp/dh_game.log 2>/dev/null
echo "=== log serveur de contenu/login ==="; grep -iE 'login|POST|GET /live/index' /tmp/dh_content.log 2>/dev/null | tail -10
