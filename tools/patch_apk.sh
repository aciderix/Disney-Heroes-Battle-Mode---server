#!/usr/bin/env bash
# PATCH APK (brique 4a) — redirige un APK Disney Heroes 12.1.0 vers un serveur AUTO-HÉBERGÉ, puis le RE-SIGNE.
# Chirurgical (dex seul, pas de reconstruction de ressources) : on ne touche QUE la redirection ServerType.LIVE
# (protocole/hôte/port/URL de contenu), via tools/apk_redirect_smali.py. Aucune règle de jeu modifiée (§1).
#
# ⚠️ L'APK patché est RE-SIGNÉ avec une clé (debug par défaut) → à installer HORS Play Store (side-load). On NE
#    redistribue PAS l'APK : le JOUEUR fournit et patche le SIEN (comme pour les bundles serveur/client, §7 copyright).
#
# Usage : tools/patch_apk.sh <in.apk> <host> <port> [out.apk]
#   ex.  tools/patch_apk.sh mon.apk 192.168.1.20 8080  →  mon-dh-<host>.apk
set -uo pipefail
IN="${1:?usage: patch_apk.sh <in.apk> <host> <port> [out.apk]}"
HOST="${2:?host requis}"
PORT="${3:?port requis}"
OUT="${4:-${IN%.apk}-dh-${HOST}.apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/libs/apktools"      # jars d'outils (gitignorés, régénérables — §7)
mkdir -p "$CACHE"
# Python : "python3" est la convention Linux/macOS ; sur Windows (python-build-standalone embarqué OU install
# système) seul "python.exe" existe, PAS "python3.exe" — un "python3" en dur échouait sur Windows même avec le
# bon dossier sur PATH. PIRE : `command -v python3` seul ne suffit PAS pour détecter ce cas — Windows enregistre
# un "App Execution Alias" STUB à `%LOCALAPPDATA%\Microsoft\WindowsApps\python3.exe` (fichier RÉEL, donc
# `command -v` le trouve et déclare succès) qui affiche juste "Python was not found… Microsoft Store" et QUITTE
# EN ÉCHEC (code 49) sans rien faire. Seul un test d'EXÉCUTION réelle (`--version`) distingue un vrai Python de
# ce stub. Vérifié EN JEU.
PY=python3; "$PY" --version >/dev/null 2>&1 || PY=python
export JAVA_TOOL_OPTIONS=

# --- 0) outils (téléchargés une fois, comme dex2jar dans decompile.sh) ---
fetch() { # <url> <dest>
  [ -s "$2" ] && return 0
  echo "[apk] téléchargement $(basename "$2") ..."
  curl -sSL --retry 3 --max-time 180 -o "$2" "$1" || { echo "[apk] ✖ échec téléchargement $1"; exit 1; }
}
fetch "https://github.com/baksmali/smali/releases/download/v2.5.2/baksmali-2.5.2.jar"        "$CACHE/baksmali.jar"
fetch "https://github.com/baksmali/smali/releases/download/v2.5.2/smali-2.5.2.jar"            "$CACHE/smali.jar"
fetch "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar" "$CACHE/signer.jar"
fetch "https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar"  "$CACHE/APKEditor.jar"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
# XAPK (base + config splits) → FUSION en universel d'abord (sinon install « incompatible » + pas de .so)
if unzip -l "$IN" 2>/dev/null | grep -qE '\.apk$'; then
  echo "[apk] XAPK détecté → fusion en APK universel (APKEditor) ..."
  java -jar "$CACHE/APKEditor.jar" m -i "$IN" -o "$WORK/app.apk" >/dev/null 2>&1 || { echo "[apk] ✖ fusion XAPK"; exit 1; }
else
  cp "$IN" "$WORK/app.apk"
fi

# --- 1) repérer le dex qui contient la redirection LIVE ---
echo "[apk] recherche du dex ServerType ..."
unzip -o -q "$WORK/app.apk" 'classes*.dex' -d "$WORK/dex"
DEX=""
for d in "$WORK"/dex/classes*.dex; do
  if grep -aql "login.disneyheroesgame.com" "$d"; then DEX="$d"; break; fi
done
[ -n "$DEX" ] || { echo "[apk] ✖ ServerType (login.disneyheroesgame.com) introuvable dans les dex — APK non reconnu."; exit 1; }
DEXNAME="$(basename "$DEX")"
echo "[apk] ServerType dans $DEXNAME"

# --- 2) désassembler CE dex, patcher ServerType.LIVE, réassembler ---
java -jar "$CACHE/baksmali.jar" disassemble "$DEX" -o "$WORK/smali" >/dev/null 2>&1 || { echo "[apk] ✖ baksmali"; exit 1; }
ST="$WORK/smali/com/perblue/heroes/ServerType.smali"
[ -f "$ST" ] || { echo "[apk] ✖ ServerType.smali absent après baksmali"; exit 1; }
"$PY" "$ROOT/tools/apk_redirect_smali.py" "$ST" "$HOST" "$PORT" "http://$HOST:$PORT/live/index.txt" || exit 1
java -jar "$CACHE/smali.jar" assemble "$WORK/smali" -o "$WORK/$DEXNAME" >/dev/null 2>&1 || { echo "[apk] ✖ smali (réassemblage)"; exit 1; }
grep -aql "$HOST" "$WORK/$DEXNAME" || { echo "[apk] ✖ l'hôte patché est absent du dex réassemblé"; exit 1; }

# --- 3) remplacer le dex dans l'apk + retirer l'ancienne signature ---
# PAS de `zip -q`/`zip -qd` (ajout/retrait d'entrées EN PLACE) : `zip` n'est PAS installé par défaut avec Git for
# Windows (seul `unzip` l'est) → échouait SILENCIEUSEMENT (`|| true`) → le dex patché n'était jamais injecté ET les
# anciennes signatures restaient → APK invalide. Correctif PORTABLE (extraire → remplacer/retirer sur le système de
# fichiers → ré-empaqueter), sans dépendre de `zip` : `unzip`+`jar` suffisent (comme ailleurs dans ce dépôt).
APKTMP="$WORK/app-extract"; rm -rf "$APKTMP"; mkdir -p "$APKTMP"
( cd "$APKTMP" && unzip -oq "$WORK/app.apk" )
cp "$WORK/$DEXNAME" "$APKTMP/$DEXNAME"
rm -f "$APKTMP"/META-INF/*.RSA "$APKTMP"/META-INF/*.SF "$APKTMP"/META-INF/*.MF 2>/dev/null || true
( cd "$APKTMP" && jar cf "$WORK/app.apk" . )
rm -rf "$APKTMP"

# --- 4) zipalign + re-signer (clé debug intégrée ; le signer vérifie lui-même la signature à la fin) ---
echo "[apk] zipalign + signature ..."
SIGLOG="$(java -jar "$CACHE/signer.jar" --apks "$WORK/app.apk" --overwrite 2>&1)" || { echo "[apk] ✖ signature"; echo "$SIGLOG" | tail -5; exit 1; }
echo "$SIGLOG" | grep -aiE 'signature verified|zipalign (success|verified)' | sed 's/^/[apk]   /'
echo "$SIGLOG" | grep -aiq 'signature verified' || { echo "[apk] ✖ signature non vérifiée par le signer"; exit 1; }
cp "$WORK/app.apk" "$OUT"   # --overwrite a réécrit l'apk signé sur place

# --- 5) vérification de la redirection dans l'APK signé final ---
V="$WORK/verify"; mkdir -p "$V"; unzip -o -q "$OUT" "$DEXNAME" -d "$V"
if grep -aql "$HOST" "$V/$DEXNAME" && ! grep -aql "login.disneyheroesgame.com" "$V/$DEXNAME"; then
  echo "[apk] ✅ redirection OK dans l'APK signé (nouvel hôte présent, ancien absent)"
else
  echo "[apk] ✖ vérification de la redirection échouée"; exit 1
fi
echo "[apk] ✅ APK patché prêt : $OUT"
echo "[apk]   → installer HORS store (autoriser les sources inconnues) ; le jeu se connectera à http://$HOST:$PORT"
