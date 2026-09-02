#!/usr/bin/env bash
# Décompile l'APK Disney Heroes en un jar Java chargeable (libs/game.jar), pour que le
# serveur réutilise DIRECTEMENT les classes du jeu (codec, MessageFactory, BootData…).
#
# Reproductible : récupère dex2jar (fork maintenu de.femtopedia.dex2jar) depuis Maven
# Central via Maven, puis convertit les .dex → classes Java. Artefact NON committé
# (régénérable ; voir .gitignore) — copyright PerBlue.
#
# Usage : tools/decompile.sh path/to/disney-heroes.apk
# Sortie : libs/game.jar  (+ note d'exécution ci-dessous)
#
# ⚠️ Chargement des classes issues de dex2jar :
#   - lancer la JVM avec  -Xverify:none  (le bytecode dex2jar n'a pas les stackmap frames
#     exigées par le vérificateur Java 7+ ; la vérification est un contrôle de CHARGEMENT,
#     la désactiver ne change RIEN à l'exécution — cf. DragonSoul SHIMS).
#   - ajouter commons-logging au classpath (dépendance de com.perblue.common.logging).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# APK committé par défaut (game/), sinon passer un chemin en argument.
APK="${1:-$ROOT/game/disney-heroes-12.1.0.apk}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 [path/to/disney-heroes.apk]  (défaut: game/disney-heroes-12.1.0.apk)" >&2
  exit 1
fi

OUT="$ROOT/libs"; mkdir -p "$OUT"

# IDEMPOTENT (perf) : si libs/game.jar existe déjà et est PLUS RÉCENT que l'APK source (+ commons-logging.jar
# présent), la décompilation dex2jar (la plus LOURDE étape du pipeline, plusieurs minutes pour ~65k classes)
# est SAUTÉE. Supprimer libs/game.jar force une régénération. Mêmes règles que le reframe/gradle du dépôt
# (`[ ! -f … ] || [ … -nt … ]`).
if [ -f "$OUT/game.jar" ] && [ -f "$OUT/commons-logging.jar" ] && [ "$OUT/game.jar" -nt "$APK" ]; then
  echo "[decompile] libs/game.jar déjà à jour (plus récent que l'APK) → décompilation SAUTÉE (supprimer libs/game.jar pour forcer)"
  exit 0
fi
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# dex2jar (fork femtopedia 2.4.28) + ses dépendances RÉSOLUES, en URLs Maven Central DIRECTES (pinnées) — plus besoin
# de `mvn` sur la machine (autonomie du « Générer »). Liste = `mvn dependency:copy-dependencies` du pom d'origine,
# figée (asm core non requis pour Dex2jarCmd, vérifié). Cache gitignoré `libs/apktools/dex2jar` (téléchargé une fois).
D2J_VERSION="2.4.28"
MVN_BASE="https://repo1.maven.org/maven2"
CACHE="$ROOT/libs/apktools/dex2jar"; mkdir -p "$CACHE"
D2J_JARS=(
  "de/femtopedia/dex2jar/dex-tools/2.4.28/dex-tools-2.4.28.jar"
  "de/femtopedia/dex2jar/dex-translator/2.4.28/dex-translator-2.4.28.jar"
  "de/femtopedia/dex2jar/d2j-smali/2.4.28/d2j-smali-2.4.28.jar"
  "de/femtopedia/dex2jar/d2j-jasmin/2.4.28/d2j-jasmin-2.4.28.jar"
  "de/femtopedia/dex2jar/dex-writer/2.4.28/dex-writer-2.4.28.jar"
  "de/femtopedia/dex2jar/d2j-base-cmd/2.4.28/d2j-base-cmd-2.4.28.jar"
  "de/femtopedia/dex2jar/d2j-external/2.4.28/d2j-external-2.4.28.jar"
  "de/femtopedia/dex2jar/dex-ir/2.4.28/dex-ir-2.4.28.jar"
  "de/femtopedia/dex2jar/dex-reader/2.4.28/dex-reader-2.4.28.jar"
  "de/femtopedia/dex2jar/dex-reader-api/2.4.28/dex-reader-api-2.4.28.jar"
  "org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar"
  "org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar"
  "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"
  "org/ow2/asm/asm-util/9.8/asm-util-9.8.jar"
  "org/antlr/antlr-runtime/3.5.3/antlr-runtime-3.5.3.jar"
  "org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar"
  "commons-logging/commons-logging/1.2/commons-logging-1.2.jar"
)
echo "[decompile] récupération de dex2jar ${D2J_VERSION} (Maven Central, URLs directes — sans maven)…"
mkdir -p "$WORK/lib"
for p in "${D2J_JARS[@]}"; do
  name="$(basename "$p")"
  [ -f "$CACHE/$name" ] || curl -fsSL -o "$CACHE/$name" "$MVN_BASE/$p" \
    || { echo "[decompile] échec téléchargement $name" >&2; exit 1; }
  cp "$CACHE/$name" "$WORK/lib/$name"
done

# commons-logging placé à côté du jar de sortie pour l'exécution du serveur.
cp "$WORK"/lib/commons-logging-*.jar "$OUT/commons-logging.jar"

echo "[decompile] dex2jar : $APK → libs/game.jar"
# -Xverify:none pour l'outil lui-même n'est pas nécessaire ; -f force malgré des classes
# partielles (SDK pub tiers). JAVA_TOOL_OPTIONS vidé pour un log propre.
# Classpath : sous Git Bash/MSYS (Windows), un java.exe NATIF appelé avec un classpath WILDCARD POSIX
# ("$WORK/lib/*") n'a PAS ses arguments traduits (le "*" casse l'heuristique de traduction automatique des
# chemins POSIX→Windows de MSYS) → 0 jar chargé → ClassNotFoundException sur la classe principale (reproduit et
# vérifié). Sur Windows on construit donc un classpath EXPLICITE (chaque jar converti en chemin natif via
# `cygpath -w`, fourni par Git for Windows) joint par ";" (séparateur Windows) ; sur Linux/macOS le wildcard
# "dir/*" (déjà éprouvé, MSYS absent) reste utilisé tel quel.
CP="$WORK/lib/*"
if command -v cygpath >/dev/null 2>&1; then
  CP=""
  for j in "$WORK"/lib/*.jar; do CP="$CP;$(cygpath -w "$j")"; done
  CP="${CP#;}"
fi
JAVA_TOOL_OPTIONS= java -cp "$CP" com.googlecode.dex2jar.tools.Dex2jarCmd \
    -f -o "$OUT/game.jar" "$APK"

echo "[decompile] OK → $OUT/game.jar (+ $OUT/commons-logging.jar)"
echo "[decompile] test : JAVA_TOOL_OPTIONS= java -Xverify:none -cp \"libs/game.jar:libs/commons-logging.jar:server/smoke\" CodecRoundTrip"
