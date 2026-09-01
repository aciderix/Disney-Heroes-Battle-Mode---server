#!/usr/bin/env bash
# PATCH APK (brique 4c-2) — injecte l'ÉCRAN DE SÉLECTION DE SERVEUR au lancement du jeu mobile, puis re-signe.
# Au démarrage : l'écran liste l'annuaire (+ saisie manuelle) → au choix, le jeu se lance sur le serveur retenu.
# Chirurgical : manifeste (apktool) + 1 dex du jeu patché (setLive + hook, baksmali/smali) + 1 dex ajouté (le picker, d8).
#
# ⚠️ APK RE-SIGNÉ → installer HORS Play Store. On NE redistribue PAS l'APK (le joueur patche le SIEN, §7).
#
# Usage : tools/apk_inject_picker.sh <in.apk> <directoryUrl> <anonKey> [out.apk]
#   directoryUrl/anonKey = URL du projet Supabase + clé anon PUBLIQUE (embarqués dans l'écran ; jamais le service_role).
set -uo pipefail
IN="${1:?usage: apk_inject_picker.sh <in.apk> <directoryUrl> <anonKey> [out.apk]}"
DIR_URL="${2:?directoryUrl requis (ou \"\" pour annuaire desactive)}"
DIR_KEY="${3:?anonKey requis (ou \"\")}"
OUT="${4:-${IN%.apk}-dh-picker.apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/libs/apktools"; mkdir -p "$CACHE"
export JAVA_TOOL_OPTIONS=

fetch() { [ -s "$2" ] && return 0; echo "[inj] dl $(basename "$2")"; curl -sSL --retry 3 --max-time 240 -o "$2" "$1" || { echo "[inj] ✖ dl $1"; exit 1; }; }
fetch "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar"                          "$CACHE/apktool.jar"
fetch "https://github.com/baksmali/smali/releases/download/v2.5.2/baksmali-2.5.2.jar"                  "$CACHE/baksmali.jar"
fetch "https://github.com/baksmali/smali/releases/download/v2.5.2/smali-2.5.2.jar"                     "$CACHE/smali.jar"
fetch "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar" "$CACHE/signer.jar"
fetch "https://maven.google.com/com/android/tools/r8/8.3.37/r8-8.3.37.jar"                             "$CACHE/r8.jar"
fetch "https://raw.githubusercontent.com/Sable/android-platforms/master/android-33/android.jar"        "$CACHE/android.jar"

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT

# --- 1) compiler l'écran de sélection (placeholders annuaire substitués) → picker.dex ---
echo "[inj] compilation de l'écran de sélection ..."
mkdir -p "$W/src/com/perblue/dhlauncher"
sed -e "s#__DH_DIRECTORY_URL__#${DIR_URL//&/\\&}#g" -e "s#__DH_DIRECTORY_ANON_KEY__#${DIR_KEY//&/\\&}#g" \
    "$ROOT/mobile/DhServerPicker.java" > "$W/src/com/perblue/dhlauncher/DhServerPicker.java"
javac -bootclasspath "$CACHE/android.jar" -source 8 -target 8 -d "$W/cls" "$W/src/com/perblue/dhlauncher/DhServerPicker.java" 2>"$W/javac.log" \
  || { echo "[inj] ✖ javac picker"; grep -v warning "$W/javac.log" | head; exit 1; }
java -cp "$CACHE/r8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$CACHE/android.jar" --output "$W" $(find "$W/cls" -name '*.class') >/dev/null 2>&1 \
  || { echo "[inj] ✖ d8 picker"; exit 1; }
mv "$W/classes.dex" "$W/picker.dex"

# --- 2) manifeste : picker = LAUNCHER (apktool -s = manifeste+res, dex bruts conservés) ---
echo "[inj] édition du manifeste ..."
java -jar "$CACHE/apktool.jar" d -s -f "$IN" -o "$W/dec" >/dev/null 2>&1 || { echo "[inj] ✖ apktool d"; exit 1; }
python3 "$ROOT/tools/apk_manifest_picker.py" "$W/dec/AndroidManifest.xml" || exit 1
java -jar "$CACHE/apktool.jar" b "$W/dec" -o "$W/manifest.apk" >/dev/null 2>&1 || { echo "[inj] ✖ apktool b"; exit 1; }

# --- 3) patcher le dex du jeu (ServerType.setLive + hook onCreate) ---
echo "[inj] patch smali (setLive + hook) ..."
unzip -o -q "$W/manifest.apk" 'classes*.dex' -d "$W/dex"
GDEX=""; for d in "$W"/dex/classes*.dex; do grep -aql "Lcom/perblue/heroes/android/AndroidLauncher;" "$d" && { GDEX="$d"; break; }; done
[ -n "$GDEX" ] || { echo "[inj] ✖ dex AndroidLauncher introuvable"; exit 1; }
GNAME="$(basename "$GDEX")"
java -jar "$CACHE/baksmali.jar" disassemble "$GDEX" -o "$W/smali" >/dev/null 2>&1 || { echo "[inj] ✖ baksmali"; exit 1; }
python3 "$ROOT/tools/apk_inject_smali.py" "$W/smali" || exit 1
java -jar "$CACHE/smali.jar" assemble "$W/smali" -o "$W/$GNAME" >/dev/null 2>&1 || { echo "[inj] ✖ smali"; exit 1; }

# --- 4) placer les dex : jeu patché (remplace) + picker (nouveau classesN.dex) ---
NEXT=$(( $(ls "$W"/dex/classes*.dex | wc -l) + 1 ))
cp "$W/$GNAME" "$W/dex/$GNAME"                 # ← le dex du jeu PATCHÉ (setLive + hook) écrase l'extrait brut
cp "$W/picker.dex" "$W/dex/classes${NEXT}.dex"
cp "$W/manifest.apk" "$W/out.apk"
( cd "$W/dex" && zip -q "$W/out.apk" "$GNAME" "classes${NEXT}.dex" )
( cd "$W" && zip -qd out.apk 'META-INF/*.RSA' 'META-INF/*.SF' 'META-INF/*.MF' >/dev/null 2>&1 || true )

# --- 5) zipalign + re-signer ---
echo "[inj] zipalign + signature ..."
SIGLOG="$(java -jar "$CACHE/signer.jar" --apks "$W/out.apk" --overwrite 2>&1)" || { echo "[inj] ✖ signature"; echo "$SIGLOG" | tail -5; exit 1; }
echo "$SIGLOG" | grep -aiq 'signature verified' || { echo "[inj] ✖ signature non vérifiée"; exit 1; }
cp "$W/out.apk" "$OUT"

# --- 6) vérifications structurelles ---
V="$W/v"; mkdir -p "$V"; unzip -o -q "$OUT" 'AndroidManifest.xml' "classes${NEXT}.dex" "$GNAME" -d "$V"
grep -aql "com/perblue/dhlauncher/DhServerPicker" "$V/classes${NEXT}.dex" && echo "[inj] ✅ écran de sélection présent (classes${NEXT}.dex)"
grep -aql "setLive" "$V/$GNAME" && echo "[inj] ✅ ServerType.setLive + hook dans $GNAME"
echo "[inj] ✅ APK prêt : $OUT"
echo "[inj]   → installer HORS store ; au lancement, l'écran de choix de serveur s'affiche."
