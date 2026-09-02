#!/usr/bin/env bash
# Opt.3 « JNI natif », variante WINDOWS — construit libhostspine64.dll : le VRAI runtime spine-c officiel 3.6 +
# la colle JNI d'origine, CROSS-COMPILÉS pour Windows x86-64 via MinGW (x86_64-w64-mingw32-gcc), liés à la classe
# dhbackend.jnispine.HostSpine (mêmes transformations MÉCANIQUES que build-hostspine.sh : rename des symboles +
# unification des tables de handles). Logique C INCHANGÉE (§1/§4) — même source, même glue, autre cible d'ABI.
#
# But : le port PC Windows (majorité des joueurs) bénéficie du backend spine jni rapide (~50× vs unidbg), pas
# seulement Linux. Sur x86-64 il n'y a qu'une convention d'appel → les symboles Java_* restent des noms C nus.
#
# Prérequis : native/build.sh d'abord (clone spine-c 3.6, compile les objets spine-c HÔTE + génère les en-têtes
# JNI depuis game.jar — les en-têtes sont indépendants de la plateforme). MinGW installé (paquet gcc-mingw-w64).
set -e
cd "$(dirname "$0")"

CC="${MINGW_CC:-x86_64-w64-mingw32-gcc}"
command -v "$CC" >/dev/null 2>&1 || { echo "[hostspine-win] MinGW absent ($CC) — installe gcc-mingw-w64-x86-64"; exit 1; }

INC="spine-c/spine-c/spine-c/include"
[ -d "$INC/spine" ] || { echo "[hostspine-win] en-têtes spine-c introuvables — lance d'abord ./build.sh"; exit 1; }
HDR="build/jni-headers"
[ -d "$HDR" ] || { echo "[hostspine-win] en-têtes JNI introuvables ($HDR) — lance d'abord ./build.sh"; exit 1; }

# jni.h (indépendant plateforme) vient du JDK ; jni_md.h (win32) est fourni dans native/win/ pour la cross-compil.
JAVAC=$(command -v javac); JDK="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
JNI_INC="$JDK/include"
[ -f "$JNI_INC/jni.h" ] || { echo "[hostspine-win] jni.h introuvable ($JNI_INC)"; exit 1; }
JNI_MD_WIN="win"   # notre native/win/jni_md.h

mkdir -p build/obj-win build/hostjni-win

# 1) objets spine-c compilés POUR WINDOWS (MinGW) — pas de -fPIC sous Windows.
echo "[hostspine-win] compilation de spine-c (MinGW) ..."
for f in "spine-c/spine-c/spine-c"/src/spine/*.c; do
  o="build/obj-win/spine_$(basename "${f%.c}").o"
  [ "$o" -nt "$f" ] || "$CC" -c -O2 -I"$INC" -o "$o" "$f"
done

# 2) même transformation MÉCANIQUE que build-hostspine.sh (rename symboles JNI + unification des tables de handles).
sed -e 's/Java_com_perblue_heroes_cspine_Native_/Java_dhbackend_jnispine_HostSpine_/g' \
    -e 's/&t_skelData/\&t_atlas/g' -e 's/&t_skel\b/\&t_atlas/g' \
    -e 's/&t_asd/\&t_atlas/g' -e 's/&t_animState/\&t_atlas/g' \
    src/cspine_jni.c > build/hostjni-win/hostspine_jni.c

# 3) compile la glue JNI (jni.h du JDK + jni_md.h win32 fourni + en-têtes générés + spine-c).
"$CC" -c -O2 -I"$INC" -I"$JNI_INC" -I"$JNI_MD_WIN" -I"$HDR" \
    -o build/hostjni-win/hostspine_jni.o build/hostjni-win/hostspine_jni.c

# 4) link -> DLL. -static-libgcc : pas de dépendance à libgcc_s_seh-1.dll côté joueur (DLL autonome).
"$CC" -shared -o build/libhostspine64.dll build/obj-win/spine_*.o build/hostjni-win/hostspine_jni.o \
    -lm -static-libgcc -Wl,--kill-at
echo "[hostspine-win] OK : build/libhostspine64.dll ($(x86_64-w64-mingw32-nm -g --defined-only build/libhostspine64.dll 2>/dev/null | grep -c HostSpine) symboles HostSpine)"
