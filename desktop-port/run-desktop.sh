#!/usr/bin/env bash
# Compile + lance Disney Heroes desktop : réutilise le backend LwjglApplication (LWJGL2)
# BUNDLÉ dans le jeu + GameMain, sous Xvfb avec OpenGL logiciel (Mesa llvmpipe).
# Extrait les assets/ressources de l'APK committé au 1er lancement.
#
# Options (env) : DH_SERVER=host:port (redirige ServerType.LIVE via -Ddh.server),
#                 DH_TIMEOUT=sec (arrêt auto pour capture), DH_SHOT=chemin.ppm.
set -e
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS=
export LIBGL_ALWAYS_SOFTWARE=1

APK="../game/disney-heroes-12.1.0.apk"
BUILD="build"
ASSETS="$BUILD/apk/assets"
RESD="$BUILD/apk-resources"

echo "[desktop] compilation ..."
gradle --no-daemon -q compileJava 2>/dev/null | grep -v 'Picked up' || true
RUNTIME_CP=$(gradle --no-daemon -q printRuntimeClasspath 2>/dev/null | grep -v 'Picked up' | tail -1)

# Assets de l'APK (accédés par le jeu en chemins relatifs "ui/...", "world/...").
if [ ! -d "$ASSETS" ]; then
  echo "[desktop] extraction des assets de l'APK ..."
  mkdir -p "$BUILD/apk"
  unzip -oq "$APK" 'assets/*' -d "$BUILD/apk"
fi

# Ressources au racine du classpath (com/perblue/... : .tab/.properties/.glsl) que dex2jar
# a laissées de côté — tout ce qui n'est pas assets/res/lib/dex/META-INF.
if [ ! -d "$RESD" ]; then
  echo "[desktop] extraction des ressources classpath de l'APK ..."
  mkdir -p "$RESD"
  unzip -oq "$APK" -x 'assets/*' 'res/*' 'lib/*' 'META-INF/*' '*.dex' \
     'AndroidManifest.xml' 'resources.arsc' -d "$RESD" || true
fi

# game.jar embarque une distribution LWJGL **2** (réduite par ProGuard) qui entre en conflit
# avec le LWJGL **3** de Maven (mêmes noms org.lwjgl.*, API différente). On produit un
# game-logic.jar SANS org/lwjgl/** (et sans les backends libGDX bundlés) → seul le LWJGL3/
# libGDX de Maven fournit ces classes. game.jar d'origine n'est pas modifié.
GAMELOGIC="$BUILD/game-logic.jar"
SRC_GAME="../libs/game.jar"
if [ ! -f "$GAMELOGIC" ] || [ "$SRC_GAME" -nt "$GAMELOGIC" ]; then
  echo "[desktop] fabrication de game-logic.jar (sans org/lwjgl, sans backends bundlés) ..."
  cp "$SRC_GAME" "$GAMELOGIC"
  zip -q -d "$GAMELOGIC" 'org/lwjgl/*' 'com/badlogic/gdx/backends/*' >/dev/null 2>&1 || true
fi
# Remplace game.jar par game-logic.jar dans le classpath runtime résolu par gradle.
RUNTIME_CP_NOGAME=$(echo "$RUNTIME_CP" | tr ':' '\n' | grep -v '/libs/game.jar$' | paste -sd:)

# $ASSETS/$RESD sur le classpath → FileHandles internes + getResourceAsStream résolvent les
# assets/ressources du jeu. Le libGDX complet de Maven prime (game-logic sans backends/lwjgl).
CP="$BUILD/classes/java/main:$ASSETS:$RESD:$RUNTIME_CP_NOGAME:$GAMELOGIC"

# Xvfb si pas d'affichage.
if [ -z "${DISPLAY:-}" ]; then
  Xvfb :99 -screen 0 1280x720x24 >/tmp/xvfb.log 2>&1 &
  XVFB_PID=$!
  export DISPLAY=:99
  sleep 1
  echo "[desktop] Xvfb :99 (pid $XVFB_PID)"
fi

JOPTS="-Xverify:none -Dorg.lwjgl.util.Debug=false"
[ -n "${DH_SERVER:-}" ] && JOPTS="$JOPTS -Ddh.server=$DH_SERVER"

echo "[desktop] lancement (GameMain via backend LWJGL3) ..."
set +e
if [ -n "${DH_TIMEOUT:-}" ]; then
  timeout "${DH_TIMEOUT}" java $JOPTS -cp "$CP" dhdesktop.DesktopLauncher
else
  java $JOPTS -cp "$CP" dhdesktop.DesktopLauncher
fi
RC=$?
set -e
[ -n "${XVFB_PID:-}" ] && kill "$XVFB_PID" 2>/dev/null || true
exit $RC
