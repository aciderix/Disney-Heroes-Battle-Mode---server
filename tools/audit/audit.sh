#!/usr/bin/env bash
# OUTIL D'AUDIT GLOBAL (Phase 2, chantier A) — vérifie TOUT le jeu d'un coup et AUTO-LOGGE les manques dans docs/AUDIT_*.md.
# Réutilise les outils d'industrialisation (tools/screentool : ModeGraph + ScreenContract).
#
# 4 axes (cf. docs/PHASE2_TRACKING.md) :
#   A1  inventaire exhaustif des écrans (aucun oublié)                → docs/AUDIT_SCREENS.md
#   A2  câblage : messages envoyés par un écran sans handler serveur  → docs/AUDIT_WIRING.md
#   A3  valeurs en dur serveur (devraient venir de .tab/jeu/admin)    → docs/AUDIT_HARDCODED.md
#   A4  erreurs client (scan des logs en jeu)                         → docs/AUDIT_CLIENT_ERRORS.md
#
# Usage : tools/audit/audit.sh [a1|a2|a3|a4|all]   (défaut: all)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
export JAVA_TOOL_OPTIONS=
WHICH="${1:-all}"
DOCS="$ROOT/docs"; STAMP="$(date -u +%Y-%m-%dT%H:%MZ)"
GAME="$ROOT/libs/game-logic-framed.jar"; [ -f "$GAME" ] || GAME="$ROOT/libs/game-framed.jar"; [ -f "$GAME" ] || GAME="$ROOT/libs/game.jar"
GAMESRV="$ROOT/libs/game-framed.jar"; [ -f "$GAMESRV" ] || GAMESRV="$ROOT/libs/game.jar"
ASM="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"; [ -f "$ASM" ] || ASM="$ROOT/desktop-port/build/asm-9.7.jar"

pkgs() { unzip -l "$GAME" 2>/dev/null | grep -oE 'com/perblue/heroes/ui/[a-zA-Z]+/' | sort -u; }
screens() { unzip -l "$GAME" 2>/dev/null | grep -oE 'com/perblue/heroes/ui/[a-zA-Z/]*[A-Za-z]+Screen\.class' | grep -vE '\$' | sed 's#\.class##' | sort -u; }

# ---------- A1 : inventaire des écrans + couverture ----------------------------------------------------------------------
a1() {
  local OUT="$DOCS/AUDIT_SCREENS.md"
  local total; total=$(screens | wc -l | tr -d ' ')
  {
    echo "# AUDIT A1 — inventaire des écrans (aucun oublié)"
    echo
    echo "> AUTO-GÉNÉRÉ par \`tools/audit/audit.sh a1\` — $STAMP. Ne pas éditer à la main (sauf colonne « note » de triage)."
    echo ">"
    echo "> **$total écrans** \`*Screen\` (hors classes internes) dans le jar client. Un écran « couvert » = son MODE est ✅/🟢 dans"
    echo "> \`EXPLORATION.md\`, OU c'est un widget/composant non navigable. Les autres = **à auditer** (candidats oubli)."
    echo
    echo "## Écrans par package"
    local pk cnt scr modestat
    for pk in $(pkgs); do
      cnt=$(screens | grep -c "^${pk}[A-Za-z]*Screen$" || true)
      [ "$cnt" = "0" ] && continue
      echo
      echo "### \`${pk}\` ($cnt écran(s))"
      screens | grep "^${pk}[A-Za-z]*Screen$" | sed 's#.*/##' | while read -r scr; do
        echo "- [ ] \`$scr\`"
      done
    done
    echo
    echo "## Écrans à la racine \`ui/screens/\`"
    screens | grep "^com/perblue/heroes/ui/screens/[A-Za-z]*Screen$" | sed 's#.*/##' | while read -r scr; do
      echo "- [ ] \`$scr\`"
    done
  } > "$OUT"
  echo "[A1] $OUT écrit ($total écrans)"
}

# ---------- A2 : câblage (handlers manquants) ---------------------------------------------------------------------------
a2() {
  local OUT="$DOCS/AUDIT_WIRING.md"
  local CLS="$ROOT/tools/screentool/classes" SRV="$ROOT/tools/screentool/server-classes"
  local CPF="$GAMESRV:$ROOT/libs/commons-logging.jar:$ROOT/libs/sqlite-jdbc.jar:$ROOT/libs/slf4j-api.jar:$ROOT/libs/joda-time.jar"
  # (re)compile outils + classes serveur (couverture = instanceof de LoginServer)
  mkdir -p "$CLS"
  javac -cp "$ASM" -d "$CLS" "$ROOT/tools/screentool/src/ScreenContract.java" "$ROOT/tools/screentool/src/ModeGraph.java" 2>&1 | grep -v 'Picked up' || true
  if [ ! -d "$SRV" ] || [ -n "$(find "$ROOT/server/java" -name '*.java' -newer "$SRV" 2>/dev/null | head -1)" ]; then
    rm -rf "$SRV"; mkdir -p "$SRV"
    javac -cp "$CPF" -d "$SRV" $(find "$ROOT/server/java" -name '*.java') 2>&1 | grep -viE 'Picked up|warning|Note:' || true
  fi
  rm -f "$ROOT"/javac.*.args 2>/dev/null || true
  local TMP; TMP="$(mktemp)"
  local pk gaps=0
  for pk in $(pkgs) "com/perblue/heroes/ui/screens/"; do
    local rep; rep="$(java -cp "$CLS:$ASM:$GAMESRV" ScreenContract "$GAMESRV" "$SRV" "$pk" 2>/dev/null | grep -v 'Picked up')"
    # section C : lignes [MANQUE] = message envoyé sans handler
    echo "$rep" | grep -E '^\s*\[MANQUE\]' | sed "s#^#${pk}\t#" >> "$TMP" || true
  done
  gaps=$(sort -u "$TMP" | wc -l | tr -d ' ')
  {
    echo "# AUDIT A2 — câblage des handlers (messages envoyés sans route serveur)"
    echo
    echo "> AUTO-GÉNÉRÉ par \`tools/audit/audit.sh a2\` — $STAMP. Section C de \`ScreenContract\` agrégée sur tous les packages UI."
    echo "> Un \`[MANQUE]\` = un message que l'écran ENVOIE (client→serveur) mais que \`LoginServer\` ne route pas (instanceof) →"
    echo "> risque « écran vide / bouton inerte ». À trancher : implémenter le handler, ou justifier \`[OK-connu]\` (faux positif :"
    echo "> message construit localement, non envoyé ; ou handler via un chemin non détecté par l'instanceof)."
    echo
    echo "**$gaps manque(s) potentiel(s).**"
    echo
    echo "| Package | Message sans handler |"
    echo "|---|---|"
    sort -u "$TMP" | while IFS=$'\t' read -r pkg line; do
      local msg; msg="$(echo "$line" | sed -E 's#.*\[MANQUE\]\s*##; s# — .*##')"
      echo "| \`$pkg\` | \`$msg\` |"
    done
  } > "$OUT"
  rm -f "$TMP"
  echo "[A2] $OUT écrit ($gaps manque(s))"
}

# ---------- A3 : valeurs en dur serveur ---------------------------------------------------------------------------------
a3() {
  local OUT="$DOCS/AUDIT_HARDCODED.md"
  local TMP; TMP="$(mktemp)"
  # Heuristique : littéraux numériques « métier » (coûts/limites/durées/pourcentages) écrits en dur dans server/java,
  # HORS contextes légitimes (index de colonne wire, tailles de buffer, versions de migration, indices de boucle, 0/1/-1,
  # constantes de temps ms évidentes). Faux positifs attendus → triage manuel. On signale les CANDIDATS.
  # (a) littéraux ANCRÉS sur un mot-clé métier (cost/limit/max/percent/…) ; (b) `return <n≥2 chiffres>` en contexte métier.
  grep -rnE '(cost|price|limit|max|min|amount|percent|bonus|reward|qty|quantity|duration|cooldown|threshold|cap|rate|weight|chance|point|stamina|energy|diamond|gold)[A-Za-z]*\s*[=<>!]=?\s*[0-9]{2,}' \
    server/java --include='*.java' 2>/dev/null >> "$TMP" || true
  grep -rnE 'return\s+[0-9]{2,}[LlFfDd]?\s*;' server/java --include='*.java' 2>/dev/null >> "$TMP" || true
  # Exclusions : contextes TECHNIQUES légitimes (colonnes wire/SQL, versions de migration, temps ms, ports, tailles, indices),
  # commentaires, et petits nombres non métier. Puis dédup + tri.
  local RAW; RAW="$(mktemp)"; mv "$TMP" "$RAW"; TMP="$(mktemp)"
  grep -viE 'shardID|userID|guildID|version|col[ )]|column|migrat|serialVersion|0x|[^0-9](60|24|100|1000|3600|86_?400|1_?000|8080|8081)[^0-9]|//|\* |ByteBuffer|readInt|writeInt|getInt|new byte|\.length|charAt|substring|port|timeout|sleep|99999' \
    "$RAW" 2>/dev/null | sed -E 's/^([^:]+:[0-9]+):\s*/\1  /' | sort -u >> "$TMP" || true
  rm -f "$RAW"
  local cand; cand=$(wc -l < "$TMP" | tr -d ' ')
  {
    echo "# AUDIT A3 — valeurs en dur côté serveur (candidats)"
    echo
    echo "> AUTO-GÉNÉRÉ par \`tools/audit/audit.sh a3\` — $STAMP. **Heuristique** (littéraux « métier » : cost/limit/max/percent/…)."
    echo "> **Beaucoup de faux positifs attendus** (défauts admin légitimes, valeurs de test, bornes techniques). CHAQUE candidat"
    echo "> se tranche à la main : (a) **RÉEL** = doit venir de \`.tab\`/code du jeu/param admin → à corriger (§4) ; (b) **OK** ="
    echo "> défaut opérateur/param admin/technique légitime → marquer \`[OK]\` avec justification (ne plus re-signaler)."
    echo ">"
    echo "> Rappel §4 : une VALEUR DE RÈGLE (coût, chance, palier, barème) ne s'invente pas — elle s'extrait de \`.tab\`/bytecode."
    echo "> Une valeur de CONFIG opérateur (défaut d'un flag AdminEvents) EST légitime en dur (c'est un défaut, pas une règle)."
    echo
    if [ "$cand" = "0" ]; then
      echo "**0 candidat.** ✅ Aucun littéral métier (cost/limit/max/percent/…) ni \`return <n>\` codé en dur détecté dans"
      echo "\`server/java\` par l'heuristique → cohérent avec §4 (le serveur lit ses valeurs depuis \`.tab\`/code du jeu ; les"
      echo "défauts de config restent dans les outils \`Admin*\`, pas dans la logique serveur). Élargir l'heuristique si besoin."
    else
      echo "**$cand candidat(s) à trier.**"
      echo
      echo '```'
      sort "$TMP"
      echo '```'
    fi
  } > "$OUT"
  rm -f "$TMP"
  echo "[A3] $OUT écrit ($cand candidat(s))"
}

# ---------- A4 : erreurs client (scan logs en jeu) --------------------------------------------------------------------
a4() {
  local OUT="$DOCS/AUDIT_CLIENT_ERRORS.md"
  local LOGS; LOGS="$(ls -t /tmp/dh_run*.log /tmp/dh_game.log 2>/dev/null | head -8)"
  local TMP; TMP="$(mktemp)"
  # Bénins CONNUS (documentés SHIMS / pré-existants) → exclus du bruit.
  local BENIGN='NumberFormatException|PatchTalent.PREDICTIVE_FORTIFICATION|XDG_RUNTIME_DIR|Can.t assign auto weight|table de marchand introuvable: INVASION|SLF4J|StaticLoggerBinder|onMissingRow|onUnknownRow|GeneralStats|sound not loaded|BlackMarketDropStats|Error creating class.*black_market|RecordMissingString|getClass\(\).*because .<local|INFO:|glitch|crate_gold|Cannot invoke .Object.getClass'
  if [ -n "$LOGS" ]; then
    for f in $LOGS; do
      grep -aiE 'Exception|Error|NullPointer|GdxRuntime|FATAL|Caused by|Disconnected|OutOfMemory' "$f" 2>/dev/null \
        | grep -avE "$BENIGN" \
        | sed -E 's/[0-9]+/N/g' | sort | uniq -c | sort -rn | sed "s#^#$(basename "$f")  #" >> "$TMP" || true
    done
  fi
  local n; n=$(wc -l < "$TMP" | tr -d ' ')
  {
    echo "# AUDIT A4 — erreurs client (scan des logs en jeu)"
    echo
    echo "> AUTO-GÉNÉRÉ par \`tools/audit/audit.sh a4\` — $STAMP. Scan de \`/tmp/dh_run*.log\` + \`/tmp/dh_game.log\` (dernières exécutions)."
    echo "> Bénins CONNUS exclus (stat warnings, trader INVASION, XDG/layout headless — cf. SHIMS). Chiffres normalisés (\`N\`) + dédup."
    echo "> Logs scannés : ${LOGS:-（aucun — lance run-online.sh puis rejoue les écrans）}"
    echo
    if [ "$n" = "0" ]; then
      echo "**Aucune erreur client non-bénigne détectée** dans les logs scannés. ✅"
      echo
      echo "(Pour un scan à jour : lancer la pile, rejouer les écrans à auditer, puis relancer \`audit.sh a4\`.)"
    else
      echo "**$n motif(s) d'erreur non-bénin(s)** (fréquence × motif normalisé) :"
      echo
      echo '```'
      cat "$TMP"
      echo '```'
    fi
  } > "$OUT"
  rm -f "$TMP"
  echo "[A4] $OUT écrit ($n motif(s))"
}

# ---------- A5 : couverture des .tab (data sans mode câblé + orphelines) ----------------------------------------------
a5() {
  local OUT="$DOCS/AUDIT_TABS.md"
  local WORK; WORK="$(mktemp -d)"
  # 1) carte .tab → classe Stats (une passe sur les packages de données du jar)
  ( cd "$WORK"; unzip -oq "$GAME" 'com/perblue/heroes/game/data/*' 'com/perblue/common/stats/*' 2>/dev/null )
  local MAP; MAP="$(mktemp)"
  grep -aroE '[a-z0-9_]+\.tab' "$WORK" --include='*.class' 2>/dev/null \
    | sed -E "s#^${WORK}/##; s#\.class:# #" \
    | awk '{cls=$1; tab=$2; sub(/\$.*/,"",cls); gsub("/",".",cls); print tab"\t"cls}' | sort -u > "$MAP"
  # 2) .tab sur disque vs référencées
  ls "$ROOT/game-data/stats"/*.tab 2>/dev/null | sed 's#.*/##' | sort -u > "$WORK/ondisk"
  cut -f1 "$MAP" | sed -E 's/^0//' | sort -u > "$WORK/refd"   # (le '0' initial = artefact de concat strings)
  # 3) classes Stats nommées dans server/java
  local USED; USED="$(mktemp)"
  cut -f2 "$MAP" | sort -u | while read -r cls; do
    local s="${cls##*.}"; grep -rqE "\\b$s\\b" "$ROOT/server/java" --include='*.java' 2>/dev/null && echo "$cls"
  done | sort -u > "$USED"
  {
    echo "# AUDIT A5 — couverture des \`.tab\` (données du jeu)"
    echo
    echo "> AUTO-GÉNÉRÉ par \`tools/audit/audit.sh a5\` — $STAMP. Carte \`.tab → classe Stats\` (le code du jeu associe CHAQUE"
    echo "> \`.tab\` à une classe \`Stats\` = la « partie du jeu » concernée ; le PACKAGE = le mode/feature). \"Nommée serveur\" ="
    echo "> la classe apparaît dans \`server/java\` (⚠️ approximation : une classe NON nommée peut être chargée par la LOGIQUE"
    echo "> du jeu que le serveur exécute — ex. CampaignStats via CampaignHelper — donc « non nommée » ≠ « inutilisée »)."
    echo
    echo "**$(wc -l < "$WORK/ondisk" | tr -d ' ') \`.tab\` sur disque · $(cut -f1 "$MAP"|sort -u|wc -l|tr -d ' ') référencées par le code · $(cut -f2 "$MAP"|sort -u|wc -l|tr -d ' ') classes Stats ($(wc -l < "$USED"|tr -d ' ') nommées serveur).**"
    echo
    echo "## .tab SUR DISQUE mais NON référencées par une classe (orphelines / chargées par nom dynamique)"
    comm -23 "$WORK/ondisk" "$WORK/refd" | sed 's/^/- `/; s/$/`/'
    echo
    echo "> NB : les \`content.N.tab\` sont chargées par nom CONSTRUIT (\`content.<shard>.tab\`, ContentStats) → non orphelines."
    echo
    echo "## .tab RÉFÉRENCÉES par le code mais ABSENTES du disque (à extraire ? gap d'extraction)"
    comm -13 "$WORK/ondisk" "$WORK/refd" | grep -vE '^$' | sed 's/^/- `/; s/$/`/' || true
    echo
    echo "## Carte par FEATURE (package) — classe Stats + nb .tab + nommée serveur"
    echo
    echo "| Feature (package) | Classe Stats | .tab | Nommée serveur |"
    echo "|---|---|---|---|"
    cut -f2 "$MAP" | sort -u | while read -r cls; do
      local pkg="${cls#com.perblue.heroes.game.data.}"; pkg="${pkg#com.perblue.common.stats.}"
      local n; n=$(awk -F'\t' -v c="$cls" '$2==c{print}' "$MAP" | wc -l | tr -d ' ')
      local named="—"; grep -qxF "$cls" "$USED" && named="✅"
      echo "| \`${pkg%.*}\` | \`${cls##*.}\` | $n | $named |"
    done
  } > "$OUT"
  rm -rf "$WORK" "$MAP" "$USED"
  echo "[A5] $OUT écrit"
}

case "$WHICH" in
  a1) a1 ;; a2) a2 ;; a3) a3 ;; a4) a4 ;; a5) a5 ;;
  all) a1; a2; a3; a4; a5 ;;
  *) echo "Usage: $0 [a1|a2|a3|a4|a5|all]"; exit 1 ;;
esac
echo "[audit] terminé ($WHICH)."
