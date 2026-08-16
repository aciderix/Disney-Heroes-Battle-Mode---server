#!/bin/bash
# Post-compaction reprise hook (double sécurité — exigence utilisateur).
#
# Se déclenche sur SessionStart *après* une compression de contexte (manuelle
# via /compact OU automatique) : Claude Code relance la session avec
# source="compact". Ce hook injecte alors, dans le tout nouveau contexte, une
# consigne EXPLICITE et OBLIGATOIRE d'exécuter le RITUEL DE REPRISE EN ENTIER
# AVANT TOUTE CHOSE — en y intégrant les derniers commits (git log). C'est une
# sécurité redondante qui s'ajoute au handoff de compression écrit explicitement.
#
# Registered in .claude/settings.json under hooks.SessionStart (matcher "compact").
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

# Ne rien injecter si ce N'EST PAS une reprise après compression.
# (matcher "compact" dans settings.json le garantit déjà ; ceci est défensif.)
if [ "$SOURCE" != "compact" ]; then
    exit 0
fi

# --- Derniers commits, à intégrer dans la consigne ----------------------------
GITLOG="$(git -C "$PROJECT_DIR" log --oneline -25 2>/dev/null || echo '(git log indisponible — lance `git log --oneline -25` toi-même)')"

# --- Construire la consigne de reprise (French : langue de travail du projet) --
read -r -d '' RITUAL <<'EOF' || true
⚠️⚠️ REPRISE APRÈS COMPRESSION DE CONTEXTE — OBLIGATOIRE, EXIGENCE UTILISATEUR ⚠️⚠️

Le contexte vient d'être compressé (compaction manuelle ou automatique). AVANT
TOUTE CHOSE — avant d'écrire la moindre ligne de code, avant de lancer le moindre
outil — tu DOIS exécuter le RITUEL DE REPRISE **EN ENTIER**. Ne devine JAMAIS
l'état : reconstruis-le en LISANT (règle CLAUDE.md §« reprise procedure »).

RITUEL DE REPRISE (dans cet ordre, intégralement) :
  1. Lis **EN ENTIER** MEMORY.md (doc de récupération ; entrées du HAUT = état courant).
  2. Relis les derniers commits — `git log --oneline -25` (extrait injecté ci-dessous).
  3. Lis les dernières entrées de JOURNAL.md.
  4. Lis **EN ENTIER** docs/SHIMS.md.
  5. Lis : docs/PRINCIPLES.md, docs/PROTOCOL.md, docs/SERVER_PLAN.md,
     docs/ARCHITECTURE.md, docs/SCREEN_PIPELINE.md, docs/HEADLESS_VERIFICATION.md.
  6. Lis le doc du **MODE EN COURS** (voir la dernière entrée de MEMORY.md —
     actuellement docs/PORT.md).
  7. Lis CLAUDE.md.
  8. ÉNUMÈRE : les règles §1-§8 (docs/PRINCIPLES.md, toujours incontournables) ;
     les astuces / méthodologies / commandes documentées ; et TOUS les outils
     d'industrialisation (tools/screentool : contract.sh / ModeGraph / WireCheck /
     ClientOracle ; pilotes en jeu ; server/smoke/regression.sh).
  9. Fais **LE POINT** sur l'état et sur ce qui a été transmis au handoff, PUIS
     seulement reprends le travail.

RÈGLE PERMANENTE INCONTOURNABLE : rien n'est facultatif/absent tant que ce n'est
pas PROUVÉ et validé par l'utilisateur. Toute vérification de mode est EN JEU
(client réel → serveur → persistance → affichage visuel), pas seulement headless (§8).

Et rappelle à ton propre successeur, dans TON prochain handoff de compression,
d'appliquer ce rituel en premier (double sécurité explicite, en plus de ce hook).
EOF

# --- Émettre en JSON (additionalContext) pour injection fiable dans le contexte -
PROJECT_DIR="$PROJECT_DIR" GITLOG="$GITLOG" RITUAL="$RITUAL" python3 <<'PY'
import json, os
ritual = os.environ["RITUAL"]
gitlog = os.environ["GITLOG"]
ctx = ritual + "\n\n=== git log --oneline -25 (derniers commits) ===\n" + gitlog + "\n"
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "SessionStart",
        "additionalContext": ctx,
    }
}))
PY
