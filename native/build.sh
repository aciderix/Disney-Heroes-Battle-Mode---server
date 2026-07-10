#!/usr/bin/env bash
# Construit spine-native64.so : l'implémentation DESKTOP x86_64 de la lib native `spine-native` de
# PerBlue (interface JNI EXACTE de com.perblue.heroes.cspine.Native / cparticle.Native), au-dessus du
# runtime spine-c OFFICIEL (Esoteric 3.6). But : faire tourner le CODE JAVA D'ORIGINE du jeu
# (cspine.*/cparticle.*) INCHANGÉ — pas de réécriture Java. Voir native/NATIVE_PLAN.md.
#
# spine-c est récupéré (non committé — licence Spine Runtimes) ; notre glue JNI est dans native/src/.
set -e
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

SPINEC_DIR="spine-c"                      # spine-c/spine-c/{src,include}
SPINEC="$SPINEC_DIR/spine-c/spine-c"
if [ ! -d "$SPINEC/src/spine" ]; then
  echo "[native] récupération de spine-c 3.6 (officiel) ..."
  rm -rf "$SPINEC_DIR"
  git clone --depth 1 --branch 3.6 https://github.com/EsotericSoftware/spine-runtimes.git "$SPINEC_DIR"
fi
INC="$SPINEC/include"
[ -d "$INC/spine" ] || { echo "[native] ERREUR: en-têtes spine-c introuvables ($INC)"; exit 1; }

# JDK / JNI
JAVAC=$(command -v javac)
JDK="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
JNI_INC="$JDK/include"
JNI_MD="$JNI_INC/linux"
[ -f "$JNI_INC/jni.h" ] || { echo "[native] ERREUR: jni.h introuvable ($JNI_INC)"; exit 1; }

# En-têtes JNI générés depuis les classes Native DU JEU (signatures mangle exactes).
# On reconstruit des déclarations `native` (sans corps) à partir des classes du jeu et on émet les .h.
HDR=build/jni-headers
if [ ! -f "$HDR/com_perblue_heroes_cspine_Native.h" ] || [ "$ROOT/libs/game.jar" -nt "$HDR/com_perblue_heroes_cspine_Native.h" ]; then
  echo "[native] génération des en-têtes JNI depuis game.jar ..."
  mkdir -p build/jni-decl/com/perblue/heroes/cspine build/jni-decl/com/perblue/heroes/cparticle "$HDR"
  # Les stubs de déclarations sont maintenus à la main dans native/jni-decl/ (dérivés de javap).
  cp -r jni-decl/* build/jni-decl/ 2>/dev/null || true
  "$JAVAC" -cp "$ROOT/libs/game.jar" -h "$HDR" -d build/jni-decl/out $(find build/jni-decl -name '*.java') \
    2>&1 | grep -viE 'Picked up|warning' || true
fi

mkdir -p build/obj
echo "[native] compilation de spine-c ..."
for f in "$SPINEC"/src/spine/*.c; do
  o="build/obj/spine_$(basename "${f%.c}").o"
  [ "$o" -nt "$f" ] || gcc -c -O2 -fPIC -I"$INC" -o "$o" "$f"
done

echo "[native] compilation de la glue JNI ..."
for f in src/*.c; do
  [ -e "$f" ] || { echo "[native] (pas encore de glue JNI dans native/src/)"; continue; }
  o="build/obj/$(basename "${f%.c}").o"
  gcc -c -O2 -fPIC -I"$INC" -I"$JNI_INC" -I"$JNI_MD" -I"$HDR" -o "$o" "$f"
done

if ls src/*.c >/dev/null 2>&1; then
  echo "[native] link -> spine-native64.so ..."
  gcc -shared -fPIC -o build/spine-native64.so build/obj/*.o -lm
  echo "[native] OK : $(ls -l build/spine-native64.so | awk '{print $5}') octets"
else
  echo "[native] spine-c compilé ($(ls build/obj/spine_*.o | wc -l) objets). Glue JNI = prochaine étape."
fi
