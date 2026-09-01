#!/usr/bin/env bash
# PATCH APK (brique 4c-2) — injecte l'ÉCRAN DE SÉLECTION DE SERVEUR au lancement, puis re-signe.
# Accepte un APK universel OU un XAPK/.apks (base + config splits) → FUSIONNÉ en universel d'abord (APKEditor), sinon
# l'install échoue (« incompatible » : splits requis manquants + pas de .so). Injection CHIRURGICALE : on ne remplace que
# le manifeste + les dex du jeu + on ajoute le dex du picker ; libs/textures/ressources RESTENT intacts (pas de rebuild
# lourd). Aucune règle de jeu modifiée (§1). APK re-signé → installer HORS Play Store (le joueur patche le SIEN, §7).
#
# Usage : tools/apk_inject_picker.sh <in.(x)apk> <directoryUrl> <anonKey> [out.apk]
set -uo pipefail
IN="${1:?usage: apk_inject_picker.sh <in.(x)apk> <directoryUrl> <anonKey> [out.apk]}"
DIR_URL="${2:?directoryUrl requis (ou \"\")}"
DIR_KEY="${3:?anonKey requis (ou \"\")}"
OUT="${4:-${IN%.*}-dh-picker.apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/libs/apktools"; mkdir -p "$CACHE"
export JAVA_TOOL_OPTIONS=

fetch() { [ -s "$2" ] && return 0; echo "[inj] dl $(basename "$2")"; curl -sSL --retry 3 --max-time 300 -o "$2" "$1" || { echo "[inj] ✖ dl $1"; exit 1; }; }
fetch "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar"                          "$CACHE/apktool.jar"
fetch "https://github.com/baksmali/smali/releases/download/v2.5.2/baksmali-2.5.2.jar"                  "$CACHE/baksmali.jar"
fetch "https://github.com/baksmali/smali/releases/download/v2.5.2/smali-2.5.2.jar"                     "$CACHE/smali.jar"
fetch "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar" "$CACHE/signer.jar"
fetch "https://maven.google.com/com/android/tools/r8/8.3.37/r8-8.3.37.jar"                             "$CACHE/r8.jar"
fetch "https://raw.githubusercontent.com/Sable/android-platforms/master/android-33/android.jar"        "$CACHE/android.jar"
fetch "https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar"            "$CACHE/APKEditor.jar"

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT

# --- 0) XAPK ? (zip contenant plusieurs .apk) → fusion en universel ---
APK="$IN"
if unzip -l "$IN" 2>/dev/null | grep -qE '\.apk$'; then
  echo "[inj] XAPK détecté → fusion en APK universel (APKEditor) ..."
  java -jar "$CACHE/APKEditor.jar" m -i "$IN" -o "$W/universal.apk" >/dev/null 2>&1 || { echo "[inj] ✖ fusion XAPK"; exit 1; }
  APK="$W/universal.apk"
  echo "[inj]   universel : $(unzip -l "$APK" | grep -c '\.so$') .so, $(du -h "$APK" | cut -f1)"
fi

# --- 1) compiler l'écran de sélection (+ crypto identité mobile) → picker.dex ---
echo "[inj] compilation de l'écran de sélection ..."
mkdir -p "$W/src/com/perblue/dhlauncher"
sed -e "s#__DH_DIRECTORY_URL__#${DIR_URL//&/\\&}#g" -e "s#__DH_DIRECTORY_ANON_KEY__#${DIR_KEY//&/\\&}#g" \
    "$ROOT/mobile/DhServerPicker.java" > "$W/src/com/perblue/dhlauncher/DhServerPicker.java"
# Identité mnémonique mobile (V3) : Ed25519 pur-Java + MobileIdentity + wordlist BIP39 (source unique = celle du
# serveur, package renommé sous le picker). Requis par la crypto d'auth strict côté mobile.
cp "$ROOT/mobile/Ed25519.java" "$ROOT/mobile/MobileIdentity.java" "$ROOT/mobile/MobileAuth.java" "$ROOT/mobile/MobileInfoVerifier.java" "$W/src/com/perblue/dhlauncher/"
sed 's/^package dhserver.auth;/package com.perblue.dhlauncher;/' \
    "$ROOT/server/java/dhserver/auth/Bip39Wordlist.java" > "$W/src/com/perblue/dhlauncher/Bip39Wordlist.java"
javac -bootclasspath "$CACHE/android.jar" -source 8 -target 8 -d "$W/cls" \
    $(find "$W/src/com/perblue/dhlauncher" -name '*.java') 2>"$W/javac.log" \
  || { echo "[inj] ✖ javac picker"; grep -v warning "$W/javac.log" | head; exit 1; }
java -cp "$CACHE/r8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$CACHE/android.jar" --output "$W" $(find "$W/cls" -name '*.class') >/dev/null 2>&1 \
  || { echo "[inj] ✖ d8 picker"; exit 1; }
mv "$W/classes.dex" "$W/picker.dex"

# --- 2) manifeste édité : décoder (apktool -s), picker=LAUNCHER, recompiler → EXTRAIRE le manifeste binaire ---
echo "[inj] édition du manifeste ..."
java -jar "$CACHE/apktool.jar" d -s -f "$APK" -o "$W/dec" >/dev/null 2>&1 || { echo "[inj] ✖ apktool d"; exit 1; }
python3 "$ROOT/tools/apk_manifest_picker.py" "$W/dec/AndroidManifest.xml" || exit 1
java -jar "$CACHE/apktool.jar" b "$W/dec" -o "$W/rebuilt.apk" >/dev/null 2>&1 || { echo "[inj] ✖ apktool b (manifeste)"; exit 1; }
mkdir -p "$W/mf"; unzip -o -q "$W/rebuilt.apk" AndroidManifest.xml -d "$W/mf"

# --- 3) patcher le dex du jeu (ServerType.setLive + hook onCreate) ---
echo "[inj] patch smali (setLive + hook) ..."
unzip -o -q "$APK" 'classes*.dex' -d "$W/dex"
GDEX=""; for d in "$W"/dex/classes*.dex; do grep -aql "Lcom/perblue/heroes/android/AndroidLauncher;" "$d" && { GDEX="$d"; break; }; done
[ -n "$GDEX" ] || { echo "[inj] ✖ dex AndroidLauncher introuvable"; exit 1; }
GNAME="$(basename "$GDEX")"
java -jar "$CACHE/baksmali.jar" disassemble "$GDEX" -o "$W/smali" >/dev/null 2>&1 || { echo "[inj] ✖ baksmali"; exit 1; }
python3 "$ROOT/tools/apk_inject_smali.py" "$W/smali" || exit 1
java -jar "$CACHE/smali.jar" assemble "$W/smali" -o "$W/$GNAME" >/dev/null 2>&1 || { echo "[inj] ✖ smali"; exit 1; }

# --- 4) INJECTION CHIRURGICALE dans l'APK universel : manifeste + dex jeu (remplacés) + picker (nouveau classesN.dex) ---
echo "[inj] injection (manifeste + dex) — libs/textures intactes ..."
NEXT=$(( $(ls "$W"/dex/classes*.dex | wc -l) + 1 ))
cp "$APK" "$W/out.apk"
cp "$W/mf/AndroidManifest.xml" "$W/AndroidManifest.xml"
cp "$W/$GNAME" "$W/patched-$GNAME"
cp "$W/picker.dex" "$W/classes${NEXT}.dex"
( cd "$W" && cp "patched-$GNAME" "$GNAME" && zip -q out.apk AndroidManifest.xml "$GNAME" "classes${NEXT}.dex" \
     && zip -qd out.apk 'META-INF/*.RSA' 'META-INF/*.SF' 'META-INF/*.MF' >/dev/null 2>&1 || true )

# --- 5) zipalign + re-signer ---
echo "[inj] zipalign + signature ..."
SIGLOG="$(java -jar "$CACHE/signer.jar" --apks "$W/out.apk" --overwrite 2>&1)" || { echo "[inj] ✖ signature"; echo "$SIGLOG" | tail -6; exit 1; }
echo "$SIGLOG" | grep -aiq 'signature verified' || { echo "[inj] ✖ signature non vérifiée"; exit 1; }
cp "$W/out.apk" "$OUT"

# --- 6) vérifications structurelles ---
V="$W/v"; mkdir -p "$V"; unzip -o -q "$OUT" "classes${NEXT}.dex" "$GNAME" -d "$V"
grep -aql "com/perblue/dhlauncher/DhServerPicker" "$V/classes${NEXT}.dex" && echo "[inj] ✅ écran de sélection présent (classes${NEXT}.dex)"
grep -aql "setLive" "$V/$GNAME" && echo "[inj] ✅ ServerType.setLive + hook dans $GNAME"
echo "[inj] ✅ APK universel patché : $OUT  ($(unzip -l "$OUT" | grep -c '\.so$') .so, $(du -h "$OUT" | cut -f1))"
echo "[inj]   → installer HORS store ; au lancement, l'écran de choix s'affiche."
