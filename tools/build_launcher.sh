#!/usr/bin/env bash
# ============================================================================
# build_launcher.sh — assemble un PACKAGE LAUNCHER autonome (clé-en-main), lançable
# par un joueur lambda SANS rien installer. Multi-OS : appelé par la GitHub Action
# `launcher-release.yml` sur ubuntu-latest ET windows-latest (via bash du runner),
# et lançable en local. NE CONTIENT AUCUN CODE DU JEU (compile game-free : dhlauncher
# + dhserver.auth.MnemonicIdentity/Bip39Wordlist) → distribuable, copyright-propre.
#
# Le package embarque :
#   - dhlauncher.jar            (launcher-core : identité + /host/* + /build/*)
#   - runtime/jdk/              (JDK COMPLET de l'OS : le launcher fait javac/jlink/jar
#                                pour GÉNÉRER les bundles serveur/client depuis l'APK)
#   - runtime/python/           (CPython relocatable : content_server pur-stdlib)
#   - tooling repo (server/, desktop-port/, tools/, sans game-data ni APK — gitignorés)
#   - run-launcher.sh / .bat
#
# Le joueur fournit SON APK ; le launcher extrait données+artefacts et génère les bundles.
# Le JDK/python embarqués sont ceux de l'OS de BUILD (jlink/py-standalone ne cross-compilent
# pas) → un package Linux embarque un runtime Linux, un package Windows un runtime Windows.
#
# Env : OSKIND=linux|windows|macos (défaut: déduit) ; PY_TAG/PY_VER (défaut ci-dessous) ;
#       OUT=<dossier de sortie> (défaut: build/launcher-<oskind>) ; JAVA_HOME (JDK de build).
# ============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PY_TAG="${PY_TAG:-20240814}"
PY_VER="${PY_VER:-3.11.9}"

# --- OS/arch → triplet python-build-standalone + nom exécutable ---
case "${OSKIND:-$(uname -s)}" in
  linux|Linux)    OSKIND=linux;   PY_TRIPLE="x86_64-unknown-linux-gnu";  PY_EXE="bin/python3" ;;
  windows|*NT*|MINGW*|MSYS*) OSKIND=windows; PY_TRIPLE="x86_64-pc-windows-msvc"; PY_EXE="python.exe" ;;
  macos|Darwin)   OSKIND=macos;   PY_TRIPLE="$([ "$(uname -m)" = arm64 ] && echo aarch64-apple-darwin || echo x86_64-apple-darwin)"; PY_EXE="bin/python3" ;;
  *) echo "OSKIND non géré : ${OSKIND:-?}" >&2; exit 2 ;;
esac
OUT="${OUT:-$ROOT/build/launcher-$OSKIND}"
JAVA_HOME="${JAVA_HOME:?JAVA_HOME (JDK de build) requis}"

echo "== build_launcher : OS=$OSKIND  OUT=$OUT  JDK=$JAVA_HOME =="
rm -rf "$OUT"; mkdir -p "$OUT/runtime"

# --- 1) compile le launcher-core GAME-FREE (pas de game.jar) → dhlauncher.jar ---
echo "== compile launcher-core (game-free) =="
CLS="$OUT/.cls"; mkdir -p "$CLS"
"$JAVA_HOME/bin/javac" -d "$CLS" \
  server/java/dhlauncher/*.java \
  server/java/dhserver/auth/MnemonicIdentity.java \
  server/java/dhserver/auth/Bip39Wordlist.java \
  server/java/dhserver/directory/ServerIdentity.java \
  server/java/dhserver/directory/ServerInfo.java
"$JAVA_HOME/bin/jar" cf "$OUT/dhlauncher.jar" -C "$CLS" .
rm -rf "$CLS"

# --- 2) embarque un JDK COMPLET (javac/jlink/jar requis pour générer les bundles) ---
# On copie le JDK de build (même OS). jlink ne peut pas produire un JDK ; on copie donc l'arbre complet.
# ⚠️ `$JAVA_HOME` peut être un LIEN SYMBOLIQUE (cache d'outils GitHub sous Linux) : `cp -a` préserverait le lien
# → package sans le JDK (lien mort à l'extraction). On DÉRÉFÉRENCE (`-L`) et on copie le CONTENU (`/.`) → vrais fichiers.
echo "== embarque le JDK ($JAVA_HOME) =="
mkdir -p "$OUT/runtime/jdk"
cp -RLp "$JAVA_HOME/." "$OUT/runtime/jdk/"

# --- 3) embarque un CPython relocatable (content_server pur-stdlib) ---
echo "== télécharge python-build-standalone $PY_VER ($PY_TRIPLE) =="
PY_URL="https://github.com/astral-sh/python-build-standalone/releases/download/$PY_TAG/cpython-$PY_VER+$PY_TAG-$PY_TRIPLE-install_only.tar.gz"
# Extraction en CHEMIN RELATIF (cd dans runtime/) : sur Windows/Git Bash, un `-f D:\…` est pris par GNU tar
# pour un hôte distant (« Cannot connect to D: »). En relatif (pas de deux-points) → local, cross-OS. Se déballe en python/.
( cd "$OUT/runtime" && curl -fsSL "$PY_URL" -o python.tar.gz && tar -xzf python.tar.gz && rm -f python.tar.gz )
echo "== smoke python embarqué =="
"$OUT/runtime/python/$PY_EXE" --version

# --- 4) tooling repo nécessaire au launcher (aucun code de jeu ; game-data/APK gitignorés) ---
echo "== copie du tooling repo =="
for d in server desktop-port tools; do
  mkdir -p "$OUT/tooling/$d"
  # exclut les artefacts lourds/gitignorés (build/, libs/*.jar, game-data/, caches)
  (cd "$ROOT" && git ls-files "$d" | while read -r f; do
     mkdir -p "$OUT/tooling/$(dirname "$f")"; cp "$ROOT/$f" "$OUT/tooling/$f"; done)
done

# --- 5) scripts de lancement du launcher (utilisent le JDK embarqué) ---
cat > "$OUT/run-launcher.sh" <<'EOF'
#!/usr/bin/env bash
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="$DIR/runtime/jdk"
PATH="$JAVA_HOME/bin:$DIR/runtime/python/bin:$PATH"; export PATH
# projectDir = tooling embarqué ; port du daemon HTTP local (défaut 8090)
"$JAVA_HOME/bin/java" -cp "$DIR/dhlauncher.jar" dhlauncher.LauncherDaemon \
   --project "$DIR/tooling" --port "${DH_LAUNCHER_PORT:-8090}" "$@"
EOF
chmod +x "$OUT/run-launcher.sh"

cat > "$OUT/run-launcher.bat" <<'EOF'
@echo off
set DIR=%~dp0
set JAVA_HOME=%DIR%runtime\jdk
set PATH=%JAVA_HOME%\bin;%DIR%runtime\python;%PATH%
if "%DH_LAUNCHER_PORT%"=="" set DH_LAUNCHER_PORT=8090
"%JAVA_HOME%\bin\java.exe" -cp "%DIR%dhlauncher.jar" dhlauncher.LauncherDaemon --project "%DIR%tooling" --port %DH_LAUNCHER_PORT% %*
EOF

echo "== PACKAGE LAUNCHER prêt : $OUT =="
du -sh "$OUT" 2>/dev/null || true
