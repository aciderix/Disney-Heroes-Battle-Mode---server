#!/bin/bash
# Reprise hook (double sécurité — exigence utilisateur).
#
# Se déclenche sur SessionStart en DÉBUT DE SESSION (source="startup"/"resume"/
# "clear") ET *après* une compression de contexte (manuelle via /compact OU
# automatique → source="compact"). Ce hook injecte alors, dans le nouveau
# contexte, une consigne EXPLICITE et OBLIGATOIRE d'exécuter le RITUEL DE REPRISE
# EN ENTIER AVANT TOUTE CHOSE — en y intégrant les derniers commits (git log).
# C'est une sécurité redondante qui s'ajoute au handoff de compression écrit.
#
# Registered in .claude/settings.json under hooks.SessionStart
# (matcher "startup|resume|clear|compact").
set -euo pipefail

# --- Lire l'entrée du hook (stdin JSON) pour récupérer la source ---------------
INPUT="$(cat 2>/dev/null || true)"

# Racine du dépôt (fournie par Claude Code ; repli sur le cwd du hook).
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# Extraire "source" du JSON d'entrée (python3 = présent dans ce projet).
SOURCE="$(printf '%s' "$INPUT" | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin).get("source",""))
except Exception:
    print("")' 2>/dev/null || echo "")"

# N'injecter que sur un vrai (re)démarrage de session : startup / resume / clear
# / compact. (Le matcher de settings.json le garantit déjà ; ceci est défensif —
# tout autre source repart sans rien injecter.)
case "$SOURCE" in
    startup|resume|clear|compact) : ;;
    *) exit 0 ;;
esac

# Libellé d'en-tête adapté à la cause du déclenchement.
if [ "$SOURCE" = "compact" ]; then
    HEADER='⚠️⚠️ REPRISE APRÈS COMPRESSION DE CONTEXTE — OBLIGATOIRE, EXIGENCE UTILISATEUR ⚠️⚠️'
    CAUSE='Le contexte vient d'"'"'être compressé (compaction manuelle ou automatique).'
else
    HEADER='⚠️⚠️ DÉBUT DE SESSION — RITUEL DE REPRISE OBLIGATOIRE, EXIGENCE UTILISATEUR ⚠️⚠️'
    CAUSE="Nouvelle session (source=\"$SOURCE\" : démarrage, reprise ou /clear)."
fi

# --- Derniers commits, à intégrer dans la consigne ----------------------------
GITLOG="$(git -C "$PROJECT_DIR" log --oneline -25 2>/dev/null || echo '(git log indisponible — lance `git log --oneline -25` toi-même)')"

# --- État courant extrait de MEMORY.md : la ligne « Dernière mise à jour » + les
#     premières puces d'état « > **gNNN … » qui suivent (le HAUT = état courant). --
MEMORY_FILE="$PROJECT_DIR/MEMORY.md"
if [ -f "$MEMORY_FILE" ]; then
    MEM_STATE="$(awk '
        /^Dernière mise à jour/ {grab=1}
        grab {
            if ($0 ~ /^> \*\*/) {n++}
            if (n > 5) exit
            print
        }
    ' "$MEMORY_FILE" 2>/dev/null || true)"
    [ -n "$MEM_STATE" ] || MEM_STATE="(section « Dernière mise à jour » introuvable — lis MEMORY.md toi-même)"
else
    MEM_STATE="ATTENTION : MEMORY.md ABSENT — le reconstituer avant toute modification."
fi

# --- Dernières entrées de JOURNAL.md : le fichier est ANTI-CHRONOLOGIQUE (le plus
#     récent EN HAUT) → on prend les premiers en-têtes « ## … (gNNN) … ». ---------
JOURNAL_FILE="$PROJECT_DIR/JOURNAL.md"
if [ -f "$JOURNAL_FILE" ]; then
    JOURNAL_LAST="$(grep -E '^## .*\(g[0-9]+' "$JOURNAL_FILE" 2>/dev/null | head -n 6 || true)"
    if [ -n "$JOURNAL_LAST" ]; then
        MAXG="$(printf '%s\n' "$JOURNAL_LAST" | grep -oE 'g[0-9]+' | grep -oE '[0-9]+' | sort -n | tail -n 1 || true)"
        [ -n "$MAXG" ] && JOURNAL_LAST="$JOURNAL_LAST
Prochain incrément disponible : g$((MAXG + 1))"
    else
        JOURNAL_LAST="(aucune entrée « ## … (gNNN) » trouvée — lis JOURNAL.md toi-même)"
    fi
else
    JOURNAL_LAST="ATTENTION : JOURNAL.md ABSENT — le créer avant toute modification."
fi

# --- Construire la consigne de reprise (French : langue de travail du projet) --
read -r -d '' RITUAL_BODY <<'EOF' || true
AVANT TOUTE CHOSE — avant d'écrire la moindre ligne de code, avant de lancer le
moindre outil — tu DOIS exécuter le RITUEL DE REPRISE **EN ENTIER**. Ne devine
JAMAIS l'état : reconstruis-le en LISANT (règle CLAUDE.md §« reprise procedure »).

RITUEL DE REPRISE (dans cet ordre, intégralement) :
  1. Lis **EN ENTIER** MEMORY.md (doc de récupération ; entrées du HAUT = état courant).
  2. Relis les derniers commits — `git log --oneline -25` (extrait injecté ci-dessous).
  3. Lis les dernières entrées de JOURNAL.md.
  4. Lis **EN ENTIER** docs/SHIMS.md.
  5. Lis : docs/PRINCIPLES.md, docs/PROTOCOL.md, docs/SERVER_PLAN.md,
     docs/ARCHITECTURE.md, docs/SCREEN_PIPELINE.md, docs/HEADLESS_VERIFICATION.md.
  6. Lis le doc du **MODE / SUJET EN COURS** — celui désigné par la procédure de
     reprise et la dernière entrée d'état en tête de MEMORY.md (extrait injecté ci-dessous).
  7. Lis CLAUDE.md.
  8. ÉNUMÈRE les RÈGLES DE TRAVAIL §1-§8 (docs/PRINCIPLES.md — INCONTOURNABLES) :
       §1 Modifications minimales du jeu (jamais patcher la logique du jeu).
       §2 Aucune rustine — un shim est RÉEL, ou explicitement PARTIEL/NO-OP + risque (docs/SHIMS.md).
       §3 Serveur autoritatif — il LIT & EXÉCUTE le code+données du jeu (jamais réimplémenter la règle).
       §4 Ne JAMAIS inventer une valeur/règle — l'EXTRAIRE du .tab / bytecode (§4bis : fidélité vérifiée).
       §5 Multi-serveur dès le départ.  §6 Persistance complète et fidèle.
       §7 Reproductibilité & reprise (artefacts lourds régénérés par script, gitignorés ;
          l'identifiant de modèle N'APPARAÎT JAMAIS dans un commit/PR/artefact — chat uniquement).
       §8 Aucune supposition — travailler sur les FAITS ; vérif EN JEU OBLIGATOIRE (🟢 headless ≠ ✅).
     Puis les astuces / méthodologies / commandes documentées (§6bis lancement, §6ter pilotage,
     §6quater outils) et TOUS les outils d'industrialisation (tools/screentool : contract.sh /
     ModeGraph / WireCheck / ClientOracle ; pilotes en jeu ; server/smoke/regression.sh).
  9. Fais **LE POINT** sur l'état et sur ce qui a été transmis au handoff, PUIS
     seulement reprends le travail.

RÈGLE PERMANENTE INCONTOURNABLE : rien n'est facultatif/absent tant que ce n'est
pas PROUVÉ et validé par l'utilisateur. Toute vérification de mode est EN JEU
(client réel → serveur → persistance → affichage visuel), pas seulement headless (§8).

Et rappelle à ton propre successeur, dans TON prochain handoff de compression,
d'appliquer ce rituel en premier (double sécurité explicite, en plus de ce hook).
EOF

# Composer le rituel final : en-tête + cause (dynamiques selon la source) + corps.
RITUAL="$HEADER

$CAUSE
$RITUAL_BODY"

# --- Émettre en JSON (additionalContext) pour injection fiable dans le contexte -
PROJECT_DIR="$PROJECT_DIR" GITLOG="$GITLOG" RITUAL="$RITUAL" \
MEM_STATE="$MEM_STATE" JOURNAL_LAST="$JOURNAL_LAST" python3 <<'PY'
import json, os
ritual = os.environ["RITUAL"]
gitlog = os.environ["GITLOG"]
mem = os.environ["MEM_STATE"]
journal = os.environ["JOURNAL_LAST"]
ctx = (
    ritual
    + "\n\n=== git log --oneline -25 (derniers commits) ===\n" + gitlog
    + "\n\n=== État courant (MEMORY.md — extrait ; NE REMPLACE PAS la lecture complète) ===\n" + mem
    + "\n\n=== Dernières entrées (JOURNAL.md — extrait, le plus récent en tête) ===\n" + journal
    + "\n\n(Ces extraits sont un RAPPEL, pas un substitut : lis MEMORY.md et JOURNAL.md en entier comme l'exige le rituel.)\n"
)
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "SessionStart",
        "additionalContext": ctx,
    }
}))
PY
