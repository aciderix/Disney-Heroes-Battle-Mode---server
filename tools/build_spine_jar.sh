#!/usr/bin/env bash
# Régénère libs/spine-libgdx-perblue.jar — le runtime Java Spine (com.esotericsoftware.spine) utilisé par le
# backend d'animation Opt.3 (#28) du client desktop (JavaSpineBackend). Ce jar est un DÉRIVÉ (git-ignoré) :
# il se reconstruit depuis les sources OFFICIELLES spine-runtimes 3.6 (les mêmes que native/build.sh clone pour
# spine-c), compilées contre gdx-1.9.7 (Maven Central). §7 reproductibilité : rien à installer à la main.
#
# Sans ce jar, le module desktop NE COMPILE PAS (« package com.esotericsoftware.spine does not exist »).
# À lancer après un environnement neuf (reprovision) où les artefacts de build ont été perdus.
#
# Usage : tools/build_spine_jar.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_TOOL_OPTIONS=
OUT="$ROOT/libs/spine-libgdx-perblue.jar"
# spine-runtimes 3.6 : cloné dans /tmp/spine-runtimes (cible du symlink committé native/spine-c → ce chemin,
# comme native/build.sh). Ephémère/hors dépôt ; le symlink committé reste intact.
SPINE_DIR="/tmp/spine-runtimes"
SRC="$SPINE_DIR/spine-libgdx/spine-libgdx/src"
GDX="$ROOT/build/spinebuild/gdx-1.9.7.jar"
MVN="https://repo1.maven.org/maven2"

# 1) sources spine-runtimes 3.6 (dépôt OFFICIEL). PRÉFÉRENCE : archive tar.gz → AUCUN `git` requis (autonomie du
#    build client sur le PC de l'utilisateur, souvent sans git — surtout Windows). REPLI : `git clone` si l'archive
#    est indisponible (proxy restrictif) ET que git est présent. Rien n'est redistribué : l'utilisateur compile la
#    source publique lui-même (licence Spine Runtime respectée).
if [ ! -d "$SRC/com/esotericsoftware/spine" ]; then
  echo "[spine-jar] récupération des sources spine-runtimes 3.6 → $SPINE_DIR ..."
  rm -rf "$SPINE_DIR"; mkdir -p "$SPINE_DIR"
  TGZ="$(mktemp)"
  if curl -fsSL -o "$TGZ" "https://codeload.github.com/EsotericSoftware/spine-runtimes/tar.gz/refs/heads/3.6" \
       && tar -xzf "$TGZ" -C "$SPINE_DIR" --strip-components=1; then
    echo "[spine-jar]   → via archive tar.gz (sans git)"
  elif command -v git >/dev/null 2>&1; then
    echo "[spine-jar]   → archive indisponible, repli sur git clone"
    rm -rf "$SPINE_DIR"
    git clone --depth 1 --branch 3.6 https://github.com/EsotericSoftware/spine-runtimes.git "$SPINE_DIR"
  else
    echo "[spine-jar] ✖ sources introuvables : ni archive tar.gz (réseau/proxy) ni git. Installez git ou débloquez codeload.github.com." >&2
    rm -f "$TGZ"; exit 1
  fi
  rm -f "$TGZ"
fi

# 2) gdx-1.9.7 (dépendance de compilation, Maven Central)
mkdir -p "$ROOT/build/spinebuild"
[ -f "$GDX" ] || { echo "[spine-jar] récupération gdx-1.9.7 (Maven) ..."; curl -fsSL -o "$GDX" "$MVN/com/badlogicgames/gdx/gdx/1.9.7/gdx-1.9.7.jar"; }

# 3) compile le runtime Java spine → jar (com.esotericsoftware.spine uniquement, aucune classe badlogic)
CLS="$ROOT/build/spinebuild/classes"; rm -rf "$CLS"; mkdir -p "$CLS"
echo "[spine-jar] compilation spine-libgdx 3.6 contre gdx-1.9.7 ..."
javac -nowarn -cp "$GDX" -d "$CLS" $(find "$SRC" -name '*.java')
( cd "$CLS" && jar cf "$OUT" com )
echo "[spine-jar] OK → $OUT ($(unzip -l "$OUT" 2>/dev/null | grep -c 'com/esotericsoftware/spine') classes spine)"
