#!/usr/bin/env bash
# Opt.3 « JNI natif » (#28) — construit libhostspine64.so : le VRAI runtime spine-c officiel 3.6 + la colle
# JNI d'origine (src/cspine_jni.c), compilés pour l'HÔTE x86-64, mais liés à la classe dhbackend.jnispine.HostSpine
# (au lieu de com.perblue.heroes.cspine.Native) pour cohabiter avec le shadow. Deux transformations MÉCANIQUES
# (logique C inchangée) :
#   1) rename des symboles JNI  Java_com_perblue_heroes_cspine_Native_*  ->  Java_dhbackend_jnispine_HostSpine_*
#   2) UNIFICATION des tables de handles (t_atlas/t_skel/... -> une seule) → handles GLOBALEMENT uniques, comme
#      le binaire ARM de PerBlue (le registre de handles Java du jeu l'exige).
# Prérequis : native/build.sh d'abord (compile les objets spine-c dans build/obj/ + en-têtes JNI).
set -e
cd "$(dirname "$0")"
[ -d build/obj ] || { echo "[hostspine] lance d'abord ./build.sh (objets spine-c manquants)"; exit 1; }
ls build/obj/spine_*.o >/dev/null 2>&1 || { echo "[hostspine] objets spine-c manquants (./build.sh)"; exit 1; }

INC="spine-c/spine-c/spine-c/include"
JAVAC=$(command -v javac); JDK="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
mkdir -p build/hostjni

# 1) rename des symboles JNI + 2) unification des tables (toutes -> &t_atlas)
sed -e 's/Java_com_perblue_heroes_cspine_Native_/Java_dhbackend_jnispine_HostSpine_/g' \
    -e 's/&t_skelData/\&t_atlas/g' -e 's/&t_skel\b/\&t_atlas/g' \
    -e 's/&t_asd/\&t_atlas/g' -e 's/&t_animState/\&t_atlas/g' \
    src/cspine_jni.c > build/hostjni/hostspine_jni.c

gcc -c -O2 -fPIC -I"$INC" -I"$JDK/include" -I"$JDK/include/linux" -Ibuild/jni-headers \
    -o build/hostjni/hostspine_jni.o build/hostjni/hostspine_jni.c
gcc -shared -fPIC -o build/libhostspine64.so build/obj/spine_*.o build/hostjni/hostspine_jni.o -lm
echo "[hostspine] OK : build/libhostspine64.so ($(nm -D build/libhostspine64.so | grep -c HostSpine) symboles HostSpine)"
