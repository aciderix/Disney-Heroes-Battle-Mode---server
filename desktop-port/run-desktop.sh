#!/usr/bin/env bash
# Compile + lance Disney Heroes desktop avec le backend LWJGL3 MAISON (dhbackend/*) contre le
# core libGDX du jeu, sous Xvfb + OpenGL logiciel (Mesa llvmpipe). Extrait assets/ressources
# de l'APK committé au 1er lancement.
#
# Options (env) : DH_SERVER=host:port (redirige ServerType.LIVE via -Ddh.server),
#                 DH_TIMEOUT=sec, DH_FRAMES=N, DH_SHOT=chemin.ppm.
set -e
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS=
export LIBGL_ALWAYS_SOFTWARE=1

APK="../game/disney-heroes-12.1.0.apk"
BUILD="build"
ASSETS="$BUILD/apk/assets"
RESD="$BUILD/apk-resources"

# game.jar embarque LWJGL **2** (réduit par ProGuard) + les backends libGDX bundlés, qui
# masquent nos classes LWJGL3 (même noms org.lwjgl.*). On produit game-logic.jar SANS
# org/lwjgl/** ni com/badlogic/gdx/backends/** (core libGDX PerBlue CONSERVÉ) et build.gradle
# compile contre lui. Fabriqué AVANT gradle. game.jar d'origine intact.
GAMELOGIC="../libs/game-logic.jar"
SRC_GAME="../libs/game.jar"
if [ ! -f "$GAMELOGIC" ] || [ "$SRC_GAME" -nt "$GAMELOGIC" ]; then
  echo "[desktop] fabrication de game-logic.jar (sans org/lwjgl, sans backends bundlés) ..."
  cp "$SRC_GAME" "$GAMELOGIC"
  zip -q -d "$GAMELOGIC" 'org/lwjgl/*' 'com/badlogic/gdx/backends/*' >/dev/null 2>&1 || true
fi

# --- Outils de bytecode (ASM) : réframe game-logic + re-cible spine sur l'ABI PerBlue ---
MVN="https://repo1.maven.org/maven2"
ASM="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"
if [ ! -f "$ASM" ]; then
  ASM="$BUILD/asm-9.7.jar"; [ -f "$ASM" ] || { echo "[desktop] téléchargement ASM ..."; mkdir -p "$BUILD"; curl -fsSL -o "$ASM" "$MVN/org/ow2/asm/asm/9.7/asm-9.7.jar"; }
fi
REFRAME_CLS="../tools/reframe/classes"
[ -f "$REFRAME_CLS/ReframeJar.class" ] || { mkdir -p "$REFRAME_CLS"; javac -cp "$ASM" -d "$REFRAME_CLS" ../tools/reframe/src/ReframeJar.java; }
# Lib native `spine-native` de PerBlue (squelettes Spine + particules), rebâtie pour desktop x86_64
# sur le runtime spine-c OFFICIEL (native/). Le jeu la charge via SharedLibraryLoader ("spine-native")
# → son code d'origine cspine.*/cparticle.* tourne INCHANGÉ. Voir native/NATIVE_PLAN.md.
SPINE_NATIVE="../native/build/spine-native64.so"
if [ ! -f "$SPINE_NATIVE" ] || [ ../native/src/cspine_jni.c -nt "$SPINE_NATIVE" ]; then
  echo "[desktop] build de la lib native spine-native64.so ..."
  (cd ../native && bash build.sh) 2>&1 | grep -viE 'Picked up|fread|warning|extension.c|\^~' | tail -3
fi

echo "[desktop] compilation ..."
gradle --no-daemon -q compileJava 2>/dev/null | grep -v 'Picked up' || true
RUNTIME_CP=$(gradle --no-daemon -q printRuntimeClasspath 2>/dev/null | grep -v 'Picked up' | tail -1)

# game.jar vient de dex2jar : bytecode SANS StackMapTable. Sous -Xverify:none la JVM calcule
# paresseusement les oop-maps (generateOopMap.cpp) et PLANTE sur certaines méthodes (« Illegal
# class file ... in method loadBinaryData »). On réécrit tout game-logic.jar avec COMPUTE_FRAMES
# (frames valides) → vérificateur rapide par table, plus de crash, et on peut retirer -Xverify:none.
FRAMED="../libs/game-logic-framed.jar"
if [ ! -f "$FRAMED" ] || [ "$GAMELOGIC" -nt "$FRAMED" ]; then
  echo "[desktop] reframe de game-logic.jar (COMPUTE_FRAMES, ~10s) ..."
  java -cp "$REFRAME_CLS:$ASM:$RUNTIME_CP" ReframeJar "$GAMELOGIC" "$FRAMED" | grep -v 'Picked up' || true
fi

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

# Extrait le natif libGDX (libgdx64.so) du jar gdx-platform natives-desktop du classpath.
NATDIR="$BUILD/native"
if [ ! -f "$NATDIR/libgdx64.so" ]; then
  mkdir -p "$NATDIR"
  GDXJAR=$(echo "$RUNTIME_CP" | tr ':' '\n' | grep 'gdx-platform.*natives-desktop.jar' | head -1)
  [ -n "$GDXJAR" ] && unzip -oq "$GDXJAR" 'libgdx64.so' -d "$NATDIR" || echo "[desktop] WARN: gdx-platform natives introuvable"
fi
# spine-native64.so à la RACINE du classpath → SharedLibraryLoader.load("spine-native") l'extrait
# (getResourceAsStream("/spine-native64.so")) et le charge. C'est le mécanisme d'origine du jeu.
# Nom attendu par SharedLibraryLoader sous Linux : préfixe `lib` (mapLibraryName -> libspine-native64.so).
mkdir -p "$NATDIR"; cp -f "$SPINE_NATIVE" "$NATDIR/libspine-native64.so" 2>/dev/null || echo "[desktop] WARN: spine-native64.so absent"

# $ASSETS/$RESD sur le classpath → FileHandles internes + getResourceAsStream résolvent les
# assets/ressources du jeu. RUNTIME_CP contient déjà game-logic.jar (via build.gradle).
# game-logic-framed.jar AVANT RUNTIME_CP (qui contient l'original non-framé) → il l'ombrage.
# $NATDIR sur le classpath → SharedLibraryLoader y trouve spine-native64.so.
CP="$BUILD/classes/java/main:$FRAMED:$NATDIR:$ASSETS:$RESD:$RUNTIME_CP"

# Xvfb si pas d'affichage.
if [ -z "${DISPLAY:-}" ]; then
  Xvfb :99 -screen 0 1280x720x24 >/tmp/xvfb.log 2>&1 &
  XVFB_PID=$!
  export DISPLAY=:99
  sleep 1
  echo "[desktop] Xvfb :99 (pid $XVFB_PID)"
fi

# -XX:TieredStopAtLevel=1 : n'utiliser que le JIT C1. Le C2 (compilation agressive) plante
# sur le bytecode issu de dex2jar (GraphKit::use_exception_state) — bug JIT, pas notre logique.
# game-logic-framed.jar a des StackMapTable valides → plus besoin de -Xverify:none. On garde
# -XX:TieredStopAtLevel=1 (C1 seul) par prudence sur le bytecode dex2jar (le C2 avait planté).
JOPTS="-XX:TieredStopAtLevel=1 -Dorg.lwjgl.util.Debug=false -Ddh.rundir=$BUILD/run"
[ -f "$NATDIR/libgdx64.so" ] && JOPTS="$JOPTS -Ddh.gdxnative=$NATDIR/libgdx64.so"
[ -n "${DH_SERVER:-}" ] && JOPTS="$JOPTS -Ddh.server=$DH_SERVER"
[ -n "${DH_FRAMES:-}" ] && JOPTS="$JOPTS -Ddh.frames=$DH_FRAMES"
[ -n "${DH_SHOT:-}" ] && JOPTS="$JOPTS -Ddh.shot=$DH_SHOT"

echo "[desktop] lancement (GameMain via backend LWJGL3 maison) ..."
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
