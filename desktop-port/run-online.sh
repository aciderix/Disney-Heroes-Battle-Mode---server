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
CP="$ROOT/libs/game.jar:$ROOT/libs/commons-logging.jar:$ROOT/libs/sqlite-jdbc.jar:$ROOT/libs/slf4j-api.jar"

# Dépendances de persistance (SQLite) — récupérées à la demande (non committées, régénérables),
# comme ASM pour le port. Le serveur reste sans dépendance à installer par l'utilisateur.
MVN="https://repo1.maven.org/maven2"
[ -f "$ROOT/libs/sqlite-jdbc.jar" ] || curl -fsSL -o "$ROOT/libs/sqlite-jdbc.jar" \
    "$MVN/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar"
[ -f "$ROOT/libs/slf4j-api.jar" ] || curl -fsSL -o "$ROOT/libs/slf4j-api.jar" \
    "$MVN/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"

# Compile le serveur de jeu (server/java) si besoin.
SRVOUT="build/server-classes"; mkdir -p "$SRVOUT"
javac -cp "$CP" -d "$SRVOUT" $(find "$ROOT/server/java" -name '*.java') 2>&1 | grep -v 'Picked up' || true

cleanup() { for p in "$CONTENT_PID" "$GAME_PID"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

echo "[online] serveur de contenu+login :$HTTP_PORT ..."
python3 "$ROOT/server/content_server.py" --port "$HTTP_PORT" --rewrite-host "127.0.0.1:$HTTP_PORT" \
        --game-server "127.0.0.1:$GAME_PORT" >/tmp/dh_content.log 2>&1 &
CONTENT_PID=$!

echo "[online] serveur de jeu TCP :$GAME_PORT ..."
java -Xverify:none -XX:TieredStopAtLevel=1 -Ddh.db="$ROOT/server/data/dh-server.db" \
     -cp "$CP:$SRVOUT" dhserver.LoginServer "$GAME_PORT" >/tmp/dh_game.log 2>&1 &
GAME_PID=$!
sleep 2

echo "[online] client (ServerType.LIVE -> 127.0.0.1:$HTTP_PORT) ..."
DH_SERVER="127.0.0.1:$HTTP_PORT" DH_TIMEOUT="${DH_TIMEOUT:-90}" DH_FRAMES="${DH_FRAMES:-120}" \
    DH_SHOT="${DH_SHOT:-build/online.ppm}" ./run-desktop.sh || true

echo "=== log serveur de jeu (TCP) ==="; tail -15 /tmp/dh_game.log 2>/dev/null
echo "=== log serveur de contenu/login ==="; grep -iE 'login|POST|GET /live/index' /tmp/dh_content.log 2>/dev/null | tail -10
