#!/usr/bin/env bash
# Compile + lance le smoke test GL sous un affichage virtuel (Xvfb) avec OpenGL logiciel
# (Mesa llvmpipe). Prouve que le pipeline de rendu headless fonctionne dans ce conteneur.
# Produit une capture build/gl-smoke.ppm.
set -e
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS=
export LIBGL_ALWAYS_SOFTWARE=1   # force llvmpipe (pas de GPU en conteneur)

# NB : le smoke test ne dépend que de LWJGL. On EXCLUT libs/game.jar du classpath car il
# embarque une distribution LWJGL **2** (757 classes org/lwjgl) qui entre en collision avec
# LWJGL 3.3.4 (voir desktop-port/PROGRESS.md). Le launcher du jeu utilisera plutôt le backend
# LWJGL2 *bundlé* dans game.jar (com.badlogic.gdx.backends.lwjgl.LwjglApplication).
echo "[gl-smoke] résolution du classpath LWJGL ..."
FULL_CP=$(gradle --no-daemon -q printRuntimeClasspath 2>/dev/null | grep -v 'Picked up' | tail -1)
LWJGL_CP=$(echo "$FULL_CP" | tr ':' '\n' | grep -E 'lwjgl' | paste -sd:)
echo "[gl-smoke] compilation ..."
mkdir -p build/smoke
javac -cp "$LWJGL_CP" -d build/smoke src/main/java/desktop/GLSmokeTest.java
CP="build/smoke:$LWJGL_CP"

# Démarre Xvfb si pas déjà d'affichage.
STARTED_XVFB=""
if [ -z "${DISPLAY:-}" ]; then
  Xvfb :99 -screen 0 1280x720x24 >/tmp/xvfb.log 2>&1 &
  XVFB_PID=$!
  STARTED_XVFB=1
  export DISPLAY=:99
  sleep 1
  echo "[gl-smoke] Xvfb démarré sur :99 (pid $XVFB_PID)"
fi

echo "[gl-smoke] lancement ..."
set +e
java -Dorg.lwjgl.util.Debug=false -cp "$CP" desktop.GLSmokeTest "${1:-build/gl-smoke.ppm}"
RC=$?
set -e

[ -n "$STARTED_XVFB" ] && kill "$XVFB_PID" 2>/dev/null || true
exit $RC
