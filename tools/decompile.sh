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
command -v mvn >/dev/null || { echo "maven requis (mvn introuvable)" >&2; exit 1; }

OUT="$ROOT/libs"; mkdir -p "$OUT"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

D2J_VERSION="2.4.28"
cat > "$WORK/pom.xml" <<XML
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dh</groupId><artifactId>decompile</artifactId><version>1</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>de.femtopedia.dex2jar</groupId>
      <artifactId>dex-tools</artifactId>
      <version>${D2J_VERSION}</version>
    </dependency>
    <dependency>
      <groupId>commons-logging</groupId>
      <artifactId>commons-logging</artifactId>
      <version>1.2</version>
    </dependency>
  </dependencies>
</project>
XML

echo "[decompile] récupération de dex2jar ${D2J_VERSION} (Maven Central)…"
( cd "$WORK" && mvn -q dependency:copy-dependencies -DoutputDirectory=lib )

# commons-logging placé à côté du jar de sortie pour l'exécution du serveur.
cp "$WORK"/lib/commons-logging-*.jar "$OUT/commons-logging.jar"

echo "[decompile] dex2jar : $APK → libs/game.jar"
# -Xverify:none pour l'outil lui-même n'est pas nécessaire ; -f force malgré des classes
# partielles (SDK pub tiers). JAVA_TOOL_OPTIONS vidé pour un log propre.
JAVA_TOOL_OPTIONS= java -cp "$WORK/lib/*" com.googlecode.dex2jar.tools.Dex2jarCmd \
    -f -o "$OUT/game.jar" "$APK"

echo "[decompile] OK → $OUT/game.jar (+ $OUT/commons-logging.jar)"
echo "[decompile] test : JAVA_TOOL_OPTIONS= java -Xverify:none -cp \"libs/game.jar:libs/commons-logging.jar:server/smoke\" CodecRoundTrip"
