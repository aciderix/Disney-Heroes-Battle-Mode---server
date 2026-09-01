#!/usr/bin/env python3
"""
PATCH APK (brique 4c) — édite l'AndroidManifest (décodé par apktool) pour faire de l'écran de sélection
`com.perblue.dhlauncher.DhServerPicker` le point d'entrée (LAUNCHER), à la place du jeu. Le jeu (`AndroidLauncher`)
reste démarrable par le picker (start explicite par nom de classe). On RETIRE l'intent MAIN/LAUNCHER d'`AndroidLauncher`
(en gardant ses deep-links) et on AJOUTE l'activity du picker avec MAIN/LAUNCHER. Idempotent.

Usage : apk_manifest_picker.py <AndroidManifest.xml>
"""
import re
import sys

PICKER = "com.perblue.dhlauncher.DhServerPicker"
GAME = "com.perblue.heroes.android.AndroidLauncher"


def main(path: str) -> None:
    x = open(path, encoding="utf-8").read()
    if PICKER in x:
        print("[manifest] picker déjà présent — rien à faire"); return

    # 1) retirer l'intent-filter MAIN/LAUNCHER d'AndroidLauncher (bloc exact, sans label ni data)
    launcher_filter = re.compile(
        r'\s*<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*/>\s*'
        r'<category android:name="android\.intent\.category\.LAUNCHER"\s*/>\s*</intent-filter>', re.S)
    m = re.search(r'(<activity[^>]*' + re.escape(GAME) + r'.*?</activity>)', x, re.S)
    if not m:
        print("ERREUR : activity AndroidLauncher introuvable"); sys.exit(1)
    block = m.group(1)
    block2 = launcher_filter.sub("", block, count=1)
    if block2 == block:
        print("ERREUR : intent-filter MAIN/LAUNCHER d'AndroidLauncher introuvable"); sys.exit(1)
    x = x[:m.start(1)] + block2 + x[m.end(1):]

    # 2) insérer l'activity du picker (LAUNCHER) juste avant l'activity du jeu
    picker_activity = (
        '<activity android:name="' + PICKER + '" android:exported="true" '
        'android:label="@string/app_name" android:launchMode="singleTask" '
        'android:configChanges="keyboard|keyboardHidden|orientation|screenSize" '
        'android:screenOrientation="sensorLandscape">'
        '<intent-filter>'
        '<action android:name="android.intent.action.MAIN"/>'
        '<category android:name="android.intent.category.LAUNCHER"/>'
        '</intent-filter></activity>\n        ')
    idx = x.find("<activity", x.find(GAME) - 400 if GAME in x else 0)
    # réinsérer proprement : avant le bloc <activity ...GAME...>
    gm = re.search(r'<activity[^>]*' + re.escape(GAME), x)
    x = x[:gm.start()] + picker_activity + x[gm.start():]

    open(path, "w", encoding="utf-8").write(x)
    print("[manifest] picker = LAUNCHER ; MAIN/LAUNCHER retiré d'AndroidLauncher")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: apk_manifest_picker.py <AndroidManifest.xml>")
    main(sys.argv[1])
