#!/usr/bin/env bash
# merge_release.sh — met à jour une installation launcher EXISTANTE (dossier extrait d'une ancienne
# release) avec une nouvelle release téléchargée, en préservant ce qui est coûteux à régénérer.
#
# Pourquoi ce script (g256) : lors d'une mise à jour manuelle faite pendant une session de test, seul
# `tooling/build/generated-server` (un bundle serveur déjà PACKAGÉ, gitignoré) avait été préservé — pas
# `tooling/libs/game.jar`/`game-framed.jar` (le vrai cache INTERMÉDIAIRE de décompilation dont
# `run-desktop.sh`/`decompile.sh` ont besoin pour générer un NOUVEAU serveur/client). Résultat : « Générer »
# a échoué 2 fois (StripJar : jar manquant) avant qu'on relance `decompile.sh` à la main. Ce script évite
# de refaire cette erreur : il copie TOUT ce qui est régénérable-mais-coûteux (§7) depuis l'ancienne
# installation vers la nouvelle, jamais l'inverse (la nouvelle release fait toujours foi pour le code).
#
# Usage : tools/merge_release.sh <ancien_dossier_launcher> <nouveau_dossier_launcher_fraîchement_extrait>
#         (dry-run par défaut : affiche ce qui SERAIT copié ; ajouter --apply pour copier réellement)
set -euo pipefail

OLD="${1:-}"; NEW="${2:-}"; APPLY="${3:-}"
if [[ -z "$OLD" || -z "$NEW" ]]; then
  echo "usage: $0 <ancien_dossier> <nouveau_dossier> [--apply]" >&2
  exit 1
fi
[[ -d "$OLD" ]] || { echo "[merge_release] ancien dossier introuvable: $OLD" >&2; exit 1; }
[[ -d "$NEW" ]] || { echo "[merge_release] nouveau dossier introuvable: $NEW" >&2; exit 1; }

DRY=1; [[ "$APPLY" == "--apply" ]] && DRY=0
copy() {  # copy <chemin relatif depuis la racine du dossier launcher>
  local rel="$1" src="$OLD/$1" dst="$NEW/$1"
  [[ -e "$src" ]] || return 0                      # rien à préserver côté ancien install
  [[ -e "$dst" ]] && { echo "[merge_release] déjà présent dans le nouveau, ignoré : $rel"; return 0; }
  if [[ $DRY -eq 1 ]]; then
    echo "[merge_release] (dry-run) copierait : $rel"
  else
    mkdir -p "$(dirname "$dst")"
    cp -r "$src" "$dst"
    echo "[merge_release] copié : $rel"
  fi
}

# 1) Cache de décompilation INTERMÉDIAIRE (§7, régénérable mais coûteux — plusieurs minutes) — le vrai
#    gap qui a cassé « Générer » : sans lui, run-desktop.sh échoue (StripJar: jar runtime absent).
copy "tooling/libs/game.jar"
copy "tooling/libs/game-framed.jar"
copy "tooling/libs/commons-logging.jar"

# 2) Bundle serveur déjà packagé (pratique pour re-héberger sans re-générer, mais PAS suffisant seul —
#    c'est ce qui avait été préservé la dernière fois, en pensant à tort que ça couvrait le cache ci-dessus).
copy "tooling/build/generated-server"

# 3) APK/XAPK source de l'utilisateur (fourni une fois, jamais dans le repo/la release) — sans lui,
#    aucune régénération n'est possible du tout.
for f in "$OLD"/*.apk "$OLD"/*.xapk; do
  [[ -e "$f" ]] || continue
  copy "$(basename "$f")"
done

if [[ $DRY -eq 1 ]]; then
  echo "[merge_release] dry-run terminé — relancer avec --apply pour copier réellement."
fi
