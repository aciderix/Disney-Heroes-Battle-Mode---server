#!/usr/bin/env bash
# Lance le serveur de contenu Disney Heroes (v0). Aucune dépendance (Python stdlib).
# Voir server/README.md et docs/ASSETS.md.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 server/content_server.py "$@"
