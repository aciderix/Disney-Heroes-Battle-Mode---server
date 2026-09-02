#!/usr/bin/env bash
# Compile + lance Disney Heroes desktop avec le backend LWJGL3 MAISON (dhbackend/*) contre le
# core libGDX du jeu, sous Xvfb + OpenGL logiciel (Mesa llvmpipe). Extrait assets/ressources
# de l'APK committé au 1er lancement.
#
# Options (env) : DH_SERVER=host:port (redirige ServerType.LIVE via -Ddh.server),
#                 DH_TIMEOUT=sec, DH_FRAMES=N, DH_SHOT=chemin.ppm.
set -e
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS=
export LIBGL_ALWAYS_SOFTWARE=1
# Locale UTF-8 : sans ça le conteneur est en POSIX/ASCII (sun.jnu.encoding=ANSI_X3.4-1968) et
# l'extraction d'assets échoue sur des noms de fichiers Unicode (ex. un .skel d'unité) —
# InvalidPathException dans applyFileAttributes -> tâche d'extraction avortée -> catégorie SOUND
# incomplète -> le tuto ne démarre jamais. C.utf8 (dispo) donne sun.jnu.encoding=UTF-8 : les noms
# Unicode s'encodent correctement. Correctif de plateforme (lanceur), pas une modif du jeu.
export LC_ALL="${LC_ALL:-C.utf8}"

# Classpath OS-correct : sous Git Bash/MSYS (Windows), un java.exe NATIF appelé avec un classpath COMPOSITE
# (plusieurs entrées jointes) contenant des chemins POSIX (ex. issus de $HOME/../ ou de mktemp) n'a PAS ses
# arguments traduits de façon fiable (vérifié EN JEU : `java -cp "posix1;posix2" X` échoue même avec le bon
# séparateur) → NATIVE() convertit chaque chemin en forme Windows native via `cygpath -w` (fourni par Git for
# Windows) avant assemblage ; PSEP est le séparateur de classpath OS-correct (";" Windows / ":" ailleurs). Sur
# Linux/macOS les deux sont des no-op (déjà éprouvé).
PSEP=":"; NATIVE() { printf '%s' "$1"; }
case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PSEP=";"; NATIVE() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; } ;;
esac

# APK : fourni par le launcher via DH_APK (le package embarqué n'inclut PAS l'APK — copyright/joueur) ; repli dev.
APK="${DH_APK:-../game/disney-heroes-12.1.0.apk}"
BUILD="build"
ASSETS="$BUILD/apk/assets"
RESD="$BUILD/apk-resources"

# game.jar embarque LWJGL **2** (réduit par ProGuard) + les backends libGDX bundlés, qui
# masquent nos classes LWJGL3 (même noms org.lwjgl.*). On produit game-logic.jar SANS
# org/lwjgl/** ni com/badlogic/gdx/backends/** (core libGDX PerBlue CONSERVÉ) et build.gradle
# compile contre lui. Fabriqué AVANT gradle. game.jar d'origine intact.
GAMELOGIC="../libs/game-logic.jar"
SRC_GAME="../libs/game.jar"
if [ ! -f "$GAMELOGIC" ] || [ "$SRC_GAME" -nt "$GAMELOGIC" ]; then
  echo "[desktop] fabrication de game-logic.jar (sans org/lwjgl, sans backends bundlés) ..."
  # PAS de `zip -d` (retrait d'entrées en place) : `zip` n'est PAS installé par défaut avec Git for Windows (seul
  # `unzip` l'est) → la commande échouait SILENCIEUSEMENT (`|| true`) → org/lwjgl/** (LWJGL2 réduit embarqué par
  # PerBlue) restait dans game-logic.jar et MASQUAIT le vrai LWJGL3 à la compilation gradle → « cannot find symbol »
  # sur des dizaines de méthodes GL* (signatures réduites/absentes). Vérifié EN JEU (Windows).
  # PAS d'extraction sur disque non plus (unzip puis jar cf) : ~65k petites entrées = ~130k opérations fichier →
  # sur Windows (NTFS + antivirus temps réel scannant chaque fichier), PLUSIEURS MINUTES. StripJar.java (JDK pur,
  # java.util.zip) filtre en UN SEUL passage streaming zip→zip (jamais de fichier individuel sur disque) : le MÊME
  # travail en quelques secondes (vérifié : 73 Mo/~65k entrées → 7 s contre 15+ min). Compilé une fois, mis en cache.
  SJ_CLS="../tools/reframe/classes"
  [ -f "$SJ_CLS/StripJar.class" ] || { mkdir -p "$SJ_CLS"; javac -encoding UTF-8 -d "$SJ_CLS" ../tools/reframe/src/StripJar.java; }
  java -cp "$SJ_CLS" StripJar "$SRC_GAME" "$GAMELOGIC" "org/lwjgl/" "com/badlogic/gdx/backends/"
fi

# --- Outils de bytecode (ASM) : réframe game-logic + re-cible spine sur l'ABI PerBlue ---
MVN="https://repo1.maven.org/maven2"
ASM="$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar"
if [ ! -f "$ASM" ]; then
  ASM="$BUILD/asm-9.7.jar"; [ -f "$ASM" ] || { echo "[desktop] téléchargement ASM ..."; mkdir -p "$BUILD"; curl -fsSL -o "$ASM" "$MVN/org/ow2/asm/asm/9.7/asm-9.7.jar"; }
fi
REFRAME_CLS="../tools/reframe/classes"
[ -f "$REFRAME_CLS/ReframeJar.class" ] || { mkdir -p "$REFRAME_CLS"; javac -encoding UTF-8 -cp "$ASM" -d "$REFRAME_CLS" ../tools/reframe/src/ReframeJar.java; }
# Spine + particules : on exécute le VRAI binaire natif d'origine de PerBlue
# (native/reference/libspine-native.so, ARM, committé) IN-PROCESS via unidbg. Les shadows
# com.perblue.heroes.cspine/cparticle.Native (desktop-port/src) dispatchent vers dhbackend.unidbg.UnidbgVM.
# ⇒ le CODE D'ORIGINE de spine ET des particules tourne, zéro réécriture. Voir native/unidbg/README.md.
SPINE_LIB="../native/reference/libspine-native.so"
[ -f "$SPINE_LIB" ] || echo "[desktop] WARN: $SPINE_LIB (binaire ARM d'origine) introuvable"

# Dépendance locale OBLIGATOIRE : spine-libgdx-perblue.jar (artefact dérivé git-ignoré, régénérable). Sans lui,
# build.gradle (`implementation files('../libs/spine-libgdx-perblue.jar')`) ne trouve AUCUNE classe spine → 94 erreurs
# `cannot find symbol` → compilation ÉCHOUE. Sur un environnement neuf il n'existe pas encore → on le RÉGÉNÈRE (bug #5).
if [ ! -f ../libs/spine-libgdx-perblue.jar ]; then
  echo "[desktop] spine-libgdx-perblue.jar absent → génération (tools/build_spine_jar.sh) ..."
  ( cd .. && tools/build_spine_jar.sh ) || { echo "[desktop] ✖ build_spine_jar.sh a ÉCHOUÉ — client non compilable"; exit 1; }
fi

# Backend AUDIO : le paquet audio OpenAL de libGDX 1.9.7 (com.badlogic.gdx.backends.lwjgl3.audio.*, AUTO-CONTENU) —
# le VRAI code du jeu pour décoder OGG/WAV/MP3 + jouer via OpenAL (§3/§4, aucune réécriture). game-logic.jar a
# strippé com/badlogic/gdx/backends/* → on ré-empaquete SEULEMENT le sous-paquet audio dans un jar dédié dont
# build.gradle dépend, et DhAudio y délègue. Régénérable (gitignoré libs/*.jar, §7).
GDXAUDIO="../libs/gdx-lwjgl3-audio.jar"
if [ ! -f "$GDXAUDIO" ]; then
  echo "[desktop] fabrication de gdx-lwjgl3-audio.jar (backend audio OpenAL libGDX 1.9.7) ..."
  mkdir -p "$BUILD"
  BJ="$BUILD/gdx-backend-lwjgl3-1.9.7.jar"
  [ -f "$BJ" ] || curl -fsSL -o "$BJ" "$MVN/com/badlogicgames/gdx/gdx-backend-lwjgl3/1.9.7/gdx-backend-lwjgl3-1.9.7.jar" \
    || { echo "[desktop] ✖ téléchargement gdx-backend-lwjgl3 (audio)"; exit 1; }
  rm -rf "$BUILD/gdxaudio"; mkdir -p "$BUILD/gdxaudio"
  ( cd "$BUILD/gdxaudio" && unzip -oq "../gdx-backend-lwjgl3-1.9.7.jar" 'com/badlogic/gdx/backends/lwjgl3/audio/*' )
  jar cf "$GDXAUDIO" -C "$BUILD/gdxaudio" . \
    || { echo "[desktop] ✖ empaquetage gdx-lwjgl3-audio.jar"; exit 1; }
fi

echo "[desktop] compilation ..."
# NE PAS avaler les erreurs (ancien `2>/dev/null … || true` masquait un échec complet → jar client VIDE, "Jouer"
# crashait « ClassNotFoundException dhdesktop.DesktopLauncher » sans le moindre message — bug #5). On garde stderr
# VISIBLE et on ÉCHOUE si gradle échoue, pour que BuildManager remonte un vrai statut d'erreur.
# Gradle : on privilégie le WRAPPER embarqué (`./gradlew`, versionné) → pas besoin d'un gradle SYSTÈME installé
# (autonomie). Repli sur un `gradle` du PATH si le wrapper est absent.
GRADLE="./gradlew"; [ -x ./gradlew ] || GRADLE="gradle"
"$GRADLE" --no-daemon compileJava 2>&1 | grep -v 'Picked up'
gstatus=${PIPESTATUS[0]}
if [ "$gstatus" -ne 0 ]; then echo "[desktop] ✖ compileJava a ÉCHOUÉ (rc=$gstatus) — voir les erreurs ci-dessus"; exit 1; fi
RUNTIME_CP=$("$GRADLE" --no-daemon -q printRuntimeClasspath 2>/dev/null | grep -v 'Picked up' | tail -1)

# game.jar vient de dex2jar : bytecode SANS StackMapTable. Sous -Xverify:none la JVM calcule
# paresseusement les oop-maps (generateOopMap.cpp) et PLANTE sur certaines méthodes (« Illegal
# class file ... in method loadBinaryData »). On réécrit tout game-logic.jar avec COMPUTE_FRAMES
# (frames valides) → vérificateur rapide par table, plus de crash, et on peut retirer -Xverify:none.
FRAMED="../libs/game-logic-framed.jar"
if [ ! -f "$FRAMED" ] || [ "$GAMELOGIC" -nt "$FRAMED" ]; then
  echo "[desktop] reframe de game-logic.jar (COMPUTE_FRAMES, ~10s) ..."
  java -cp "$(NATIVE "$REFRAME_CLS")$PSEP$(NATIVE "$ASM")$PSEP$RUNTIME_CP" ReframeJar "$GAMELOGIC" "$FRAMED" | grep -v 'Picked up' || true
fi

# APK d'extraction : si le joueur fournit un XAPK/.apks (base + splits config), les TEXTURES
# (variantes ETC1/ETC2/…) et les .so sont dans des splits SÉPARÉS — le base.apk seul n'a PAS
# d'assets/ETC1 → le jeu crashe au boot (« Asset not loaded: ETC1/world/color_mask.png »). On
# FUSIONNE donc le XAPK en APK universel (APKEditor) AVANT extraction, comme le chemin patch APK
# (tools/apk_inject_picker.sh, prouvé g230). Un APK universel déjà complet passe tel quel. Paresseux :
# seulement quand une extraction est nécessaire (assets ou ressources manquants).
EXTRACT_APK="$APK"
if [ ! -d "$ASSETS" ] || [ ! -d "$RESD" ]; then
  if unzip -l "$APK" 2>/dev/null | grep -qE '\.apk$'; then
    APKEDITOR="../libs/apktools/APKEditor.jar"
    if [ ! -s "$APKEDITOR" ]; then
      echo "[desktop] téléchargement APKEditor (fusion XAPK) ..."
      mkdir -p "../libs/apktools"
      curl -sSL --retry 3 --max-time 300 -o "$APKEDITOR" \
        "https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar" \
        || { echo "[desktop] ✖ téléchargement APKEditor" >&2; exit 1; }
    fi
    mkdir -p "$BUILD"
    if [ ! -s "$BUILD/universal.apk" ]; then
      echo "[desktop] XAPK détecté → fusion en APK universel (APKEditor) ..."
      java -jar "$APKEDITOR" m -i "$APK" -o "$BUILD/universal.apk" >/dev/null 2>&1 \
        || { echo "[desktop] ✖ fusion XAPK (APKEditor)" >&2; exit 1; }
    fi
    EXTRACT_APK="$BUILD/universal.apk"
    echo "[desktop]   universel : $(unzip -l "$EXTRACT_APK" 2>/dev/null | grep -c '\.so$') .so"
  fi
fi

# Assets (accédés par le jeu en chemins relatifs "ui/...", "world/...") + ressources classpath
# (com/perblue/... : .tab/.properties/.glsl que dex2jar a laissées de côté — tout ce qui n'est
# pas assets/res/lib/dex/META-INF). UN SEUL passage d'extraction COMPLET (sans motif d'inclusion),
# puis répartition par déplacement de dossiers — PAS deux extractions filtrées par motif.
# Raison (trouvée EN JEU, Windows, g255) : l'UnZip 6.00 (2009) fourni avec Git for Windows NE
# RÉCURSE PAS dans les sous-dossiers avec un motif d'inclusion `'assets/*'` (vérifié : `unzip -l
# EXTRACT_APK 'assets/*'` ne listait que les 9 fichiers À LA RACINE de assets/, aucun des ~3046
# fichiers dans ses sous-dossiers ETC/, stats/, sound/, fonts/, shaders/, joda/, strings/… — sur
# CE build, le dossier de textures générique `ETC/` [détection dynamique du jeu, `initTextureCompressionType`]
# disparaissait ENTIÈREMENT → assets incomplets → crash potentiel au lancement). Une extraction SANS
# motif (`unzip -oq EXTRACT_APK -d STAGING`, testée : 133 Mo, `assets/ETC/` bien présent) n'a pas ce
# problème → on extrait tout une fois, puis on RÉPARTIT par déplacement (fiable, indépendant du motif).
if [ ! -d "$ASSETS" ] || [ ! -d "$RESD" ]; then
  echo "[desktop] extraction complète de l'APK (assets + ressources classpath) ..."
  STAGING="$BUILD/apk-staging"
  rm -rf "$STAGING"; mkdir -p "$STAGING"
  unzip -oq "$EXTRACT_APK" -d "$STAGING"
  mkdir -p "$(dirname "$ASSETS")"
  rm -rf "$ASSETS"; mv "$STAGING/assets" "$ASSETS"
  rm -rf "$STAGING/res" "$STAGING/lib" "$STAGING/META-INF" "$STAGING"/*.dex \
         "$STAGING/AndroidManifest.xml" "$STAGING/resources.arsc"
  rm -rf "$RESD"; mv "$STAGING" "$RESD"
fi

# Overlay des CHAÎNES manquantes : le bundle de l'APK (12.1.0) précède certaines clés que le CODE de
# game.jar référence (ex. InvasionUI.GUILD_DAILY_BOSS_LIMIT_INTERVAL) → le client affiche la CLÉ brute.
# On complète depuis notre extraction game-data/strings (qui suit game.jar), en n'AJOUTANT QUE les clés
# ABSENTES (jamais d'écrasement : on ne touche pas au libellé d'origine de la version du client). §4 : valeurs
# extraites, pas inventées. Idempotent (relançable). Cf. docs/INVASION.md §BOSS BATTLES.
if [ -d "$ROOT/game-data/strings" ] && [ -d "$ASSETS/strings" ]; then
  python3 - "$ROOT/game-data/strings" "$ASSETS/strings" <<'PY' || true
import os, sys
src, dst = sys.argv[1], sys.argv[2]
def keys(path):
    k=set()
    if not os.path.exists(path): return k
    for line in open(path, encoding='utf-8', errors='replace'):
        s=line.strip()
        if not s or s.startswith('#') or '=' not in s: continue
        k.add(s.split('=',1)[0].strip())
    return k
added_total=0
for fn in os.listdir(src):
    if not fn.endswith('.properties'): continue
    d=os.path.join(dst,fn)
    if not os.path.exists(d): continue          # ne crée pas de bundle absent du client
    have=keys(d); missing=[]
    for line in open(os.path.join(src,fn), encoding='utf-8', errors='replace'):
        s=line.rstrip('\n')
        t=s.strip()
        if not t or t.startswith('#') or '=' not in t: continue
        key=t.split('=',1)[0].strip()
        if key not in have: missing.append(s); have.add(key)
    if missing:
        with open(d,'a',encoding='utf-8') as f:
            f.write('\n# --- clés ajoutées depuis game-data/strings (absentes du bundle APK) ---\n')
            f.write('\n'.join(missing)+'\n')
        added_total+=len(missing)
if added_total: print(f"[desktop] overlay chaînes : {added_total} clé(s) manquante(s) complétée(s)")
PY
fi

# Extrait le natif libGDX (libgdx64.so) du jar gdx-platform natives-desktop du classpath.
NATDIR="$BUILD/native"
if [ ! -f "$NATDIR/libgdx64.so" ]; then
  mkdir -p "$NATDIR"
  GDXJAR=$(echo "$RUNTIME_CP" | tr "$PSEP" '\n' | grep 'gdx-platform.*natives-desktop.jar' | head -1)
  [ -n "$GDXJAR" ] && unzip -oq "$GDXJAR" 'libgdx64.so' -d "$NATDIR" || echo "[desktop] WARN: gdx-platform natives introuvable"
fi
# (Plus de spine-native64.so : spine/particules passent par unidbg + le binaire ARM d'origine.)

# $ASSETS/$RESD sur le classpath → FileHandles internes + getResourceAsStream résolvent les
# assets/ressources du jeu. RUNTIME_CP contient déjà game-logic.jar (via build.gradle).
# game-logic-framed.jar AVANT RUNTIME_CP (qui contient l'original non-framé) → il l'ombrage.
# $NATDIR sur le classpath → SharedLibraryLoader y trouve spine-native64.so.
CP="$(NATIVE "$BUILD/classes/java/main")$PSEP$(NATIVE "$FRAMED")$PSEP$(NATIVE "$NATDIR")$PSEP$(NATIVE "$ASSETS")$PSEP$(NATIVE "$RESD")$PSEP$RUNTIME_CP"

# BUILD-ONLY (C2a-4b, packaging client) : tous les artefacts sont construits (game-logic-framed, gradle,
# assets, ressources, natifs). On émet un MANIFESTE des chemins et on SORT sans lancer → le packager
# (dhlauncher.BuildManager cible CLIENT) assemble un bundle autonome à partir de ça.
if [ -n "${DH_BUILD_ONLY:-}" ]; then
  # PERF : backend spine JNI natif (libhostspine64.{so|dll}) — le VRAI runtime spine-c officiel compilé pour
  # l'hôte, ~50× plus rapide qu'unidbg (spine sort du hot-path émulé). On le CONSTRUIT à la génération SI un
  # compilateur C est présent ; sinon repli SILENCIEUX sur unidbg (le client tourne, plus lent). Idempotent.
  # build.sh a besoin de game.jar (en-têtes JNI) — présent après le decompile de l'APK. Fidélité jni certifiée (B3/B5).
  # OS-aware : Windows (Git Bash MSYS/MinGW) → libhostspine64.dll (MinGW) ; sinon → libhostspine64.so (gcc).
  case "$(uname -s 2>/dev/null || echo linux)" in
    MINGW*|MSYS*|CYGWIN*) HS_SCRIPT="build-hostspine-win.sh"; HS_LIB="libhostspine64.dll"; HS_CC="x86_64-w64-mingw32-gcc" ;;
    *)                    HS_SCRIPT="build-hostspine.sh";     HS_LIB="libhostspine64.so";  HS_CC="gcc" ;;
  esac
  if [ ! -f "$(cd .. && pwd)/native/build/$HS_LIB" ]; then
    if command -v "$HS_CC" >/dev/null 2>&1 || command -v cc >/dev/null 2>&1; then
      echo "[desktop] build du backend spine natif rapide ($HS_LIB) ..."
      ( cd ../native && bash build.sh && bash "$HS_SCRIPT" ) >/dev/null 2>&1 \
        && echo "[desktop]   $HS_LIB OK → backend jni rapide embarqué" \
        || echo "[desktop]   WARN: build hostspine échoué → le client généré utilisera unidbg (plus lent, mais fonctionnel)"
    else
      echo "[desktop] compilateur C ($HS_CC) absent → backend jni rapide non construit ; le client utilisera unidbg (plus lent). Installez un compilateur C pour le mode rapide."
    fi
  fi
  # `pwd` sous Git Bash/MSYS renvoie une forme POSIX ("/c/Users/...") — écrite telle quelle dans le manifeste puis
  # lue côté JAVA (BuildManager, hors bash, aucune traduction MSYS) : `new File("/c/Users/...")` sur Windows
  # traite "/" comme racine du LECTEUR COURANT et "c" comme un simple nom de dossier (pas une lettre de lecteur) →
  # chemin cassé (`\c\Users\...`, sans "C:"). Vérifié EN JEU. NATIVE() (déjà définie plus haut, `cygpath -w` sous
  # Windows) convertit en forme native AVANT d'écrire le manifeste.
  ABS() { (cd "$(dirname "$1")" && NATIVE "$(pwd)/$(basename "$1")"); }
  { echo "RUNTIME_CP=$RUNTIME_CP"
    echo "CLASSES=$(ABS "$BUILD/classes/java/main")"
    echo "FRAMED=$(ABS "$FRAMED")"
    echo "NATDIR=$(ABS "$NATDIR")"
    echo "ASSETS=$(ABS "$ASSETS")"
    echo "RESD=$(ABS "$RESD")"
    echo "SPINE_LIB=$(ABS "$SPINE_LIB")"
    echo "HOSTSPINE=$( NB="$(cd .. && pwd)/native/build"; [ -f "$NB/$HS_LIB" ] && NATIVE "$NB/$HS_LIB" || { [ -f "$NB/libhostspine64.so" ] && NATIVE "$NB/libhostspine64.so" || echo ""; } )"
  } > "$BUILD/client-manifest.env"
  echo "[desktop] build-only : manifeste écrit ($BUILD/client-manifest.env)"
  exit 0
fi

# Xvfb si pas d'affichage.
if [ -z "${DISPLAY:-}" ]; then
  Xvfb :99 -screen 0 1280x720x24 >/tmp/xvfb.log 2>&1 &
  XVFB_PID=$!
  export DISPLAY=:99
  sleep 1
  echo "[desktop] Xvfb :99 (pid $XVFB_PID)"
fi

# -XX:TieredStopAtLevel=1 : n'utiliser que le JIT C1. Le C2 (compilation agressive) plante
# sur le bytecode issu de dex2jar (GraphKit::use_exception_state) — bug JIT, pas notre logique.
# game-logic-framed.jar a des StackMapTable valides → plus besoin de -Xverify:none. On garde
# -XX:TieredStopAtLevel=1 (C1 seul) par prudence sur le bytecode dex2jar (le C2 avait planté).
JOPTS="-XX:TieredStopAtLevel=1 -Dorg.lwjgl.util.Debug=false -Ddh.rundir=$BUILD/run"
# Binaire natif ARM d'origine chargé par UnidbgVM (spine + particules via unidbg).
JOPTS="$JOPTS -Ddh.spinelib=$(cd .. && pwd)/native/reference/libspine-native.so"
[ -f "$NATDIR/libgdx64.so" ] && JOPTS="$JOPTS -Ddh.gdxnative=$NATDIR/libgdx64.so"
[ -n "${DH_SERVER:-}" ] && JOPTS="$JOPTS -Ddh.server=$DH_SERVER"
[ -n "${DH_USERID:-}" ] && JOPTS="$JOPTS -Ddh.userid=$DH_USERID"   # AUTH play : force le compte (BuildOptions.TEST_USER_ID au boot)
[ -n "${DH_USERID_RELOGIN:-}" ] && JOPTS="$JOPTS -Ddh.userid.relogin=$DH_USERID_RELOGIN"   # STRICT : re-login /login pour le mint
[ -n "${DH_AUTOTAP:-}" ] && JOPTS="$JOPTS -Ddh.autotap=$DH_AUTOTAP"
[ -n "${DH_FPS:-}" ] && JOPTS="$JOPTS -Ddh.fps=$DH_FPS"
[ -n "${DH_AUTOFIGHT:-}" ] && JOPTS="$JOPTS -Ddh.autofight=$DH_AUTOFIGHT"
[ -n "${DH_TUTODBG:-}" ] && JOPTS="$JOPTS -Ddh.tutodrive.debug=$DH_TUTODBG"
[ -n "${DH_AUTOEQUIP:-}" ] && JOPTS="$JOPTS -Ddh.autoequip=$DH_AUTOEQUIP"
[ -n "${DH_GOSIGNIN:-}" ] && JOPTS="$JOPTS -Ddh.gosignin=$DH_GOSIGNIN"
[ -n "${DH_CLICKFILE:-}" ] && JOPTS="$JOPTS -Ddh.clickfile=$DH_CLICKFILE"
[ -n "${DH_FRAMES:-}" ] && JOPTS="$JOPTS -Ddh.frames=$DH_FRAMES"
[ -n "${DH_SHOT:-}" ] && JOPTS="$JOPTS -Ddh.shot=$DH_SHOT"
[ -n "${DH_SHOTEVERY:-}" ] && JOPTS="$JOPTS -Ddh.shotevery=$DH_SHOTEVERY"
[ -n "${DH_TUTOREC:-}" ] && JOPTS="$JOPTS -Ddh.tutorec=$DH_TUTOREC"
[ -n "${DH_TAPHOLD:-}" ] && JOPTS="$JOPTS -Ddh.taphold=$DH_TAPHOLD"
[ -n "${DH_MAPPROBE:-}" ] && JOPTS="$JOPTS -Ddh.mapprobe=$DH_MAPPROBE"
[ -n "${DH_PROBEACTOR:-}" ] && JOPTS="$JOPTS -Ddh.probeactor=$DH_PROBEACTOR"
# DEV : spike Opt.2 (#27) — exécuter le vrai HeadlessCombat dans le client headless (mesure + oracle).
[ -n "${DH_COMBATSPIKE:-}" ] && JOPTS="$JOPTS -Ddh.combatspike=$DH_COMBATSPIKE"
[ -n "${DH_COMBATSPIKE_EXIT:-}" ] && JOPTS="$JOPTS -Ddh.combatspike.exit=$DH_COMBATSPIKE_EXIT"
[ -n "${DH_COMBATSPIKE_CH:-}" ] && JOPTS="$JOPTS -Ddh.combatspike.ch=$DH_COMBATSPIKE_CH"
[ -n "${DH_COMBATSPIKE_LV:-}" ] && JOPTS="$JOPTS -Ddh.combatspike.lv=$DH_COMBATSPIKE_LV"
[ -n "${DH_COMBATSPIKE_SEED:-}" ] && JOPTS="$JOPTS -Ddh.combatspike.seed=$DH_COMBATSPIKE_SEED"
[ -n "${DH_COMBATSPIKE_N:-}" ] && JOPTS="$JOPTS -Ddh.combatspike.n=$DH_COMBATSPIKE_N"
[ -n "${DH_CSPINEPROFILE:-}" ] && JOPTS="$JOPTS -Ddh.cspineprofile=$DH_CSPINEPROFILE"
[ -n "${DH_DYNARMIC:-}" ] && JOPTS="$JOPTS -Ddh.dynarmic=$DH_DYNARMIC"
# DEV : backend spine Opt.3 (#28) — router l'animation du combat vers le runtime Java (spine-libgdx-perblue)
# au lieu d'unidbg. Le runtime Java (SkeletonBinary) exige DataInput.readString(), absent du stub 215o de
# game-logic (dex2jar) : on fait gagner le DataInput COMPLET de gdx-1.9.7 en le déposant dans le dir de classes
# (PREMIER sur le CP → ombrage le stub). Superset correct → inoffensif pour le chemin unidbg par défaut.
# Backend spine du CLIENT : jni (spine natif) par DÉFAUT depuis 2026-08-30 (perf ~50× / spine hors du hot-path,
# fidélité certifiée B3/B5 : V/U/dark/alpha bit-exacts, héros + combat rendus parfaitement EN JEU). Le SERVEUR
# (dhserver.LoginServer, process SÉPARÉ sans -Ddh.spinebackend) reste sur unidbg BIT-EXACT pour l'AUTORITÉ de
# combat (§3/§8). Repli explicite : DH_SPINEBACKEND=unidbg. Repli AUTO si la lib native manque (checkout sans build).
DH_SPINEBACKEND="${DH_SPINEBACKEND:-jni}"
HOSTLIB="$(cd .. && pwd)/native/build/libhostspine64.so"
if { [ "$DH_SPINEBACKEND" = "jni" ] || [ "$DH_SPINEBACKEND" = "compare" ]; } && [ ! -f "$HOSTLIB" ]; then
  echo "[desktop] WARN: $HOSTLIB introuvable → repli sur unidbg (build : native/build.sh puis native/build-hostspine.sh)"
  DH_SPINEBACKEND=unidbg
fi
if [ "$DH_SPINEBACKEND" = "jni" ] || [ "$DH_SPINEBACKEND" = "compare" ]; then
  # jni : le VRAI spine-c officiel 3.6 (colle cspine_jni.c) compilé HÔTE x86-64, en JNI réel (pas d'émulation).
  # compare : harnais différentiel — le jeu tourne sur unidbg (oracle), le JNI tourne en parallèle et on diffe.
  # Les deux ont besoin de libhostspine64.so (classe HostSpine).
  JOPTS="$JOPTS -Ddh.spinebackend=${DH_SPINEBACKEND} -Ddh.hostspine=$HOSTLIB"
elif [ "${DH_SPINEBACKEND:-}" = "java" ]; then
  JOPTS="$JOPTS -Ddh.spinebackend=java"
  # libGDX vient de game-logic.jar (PerBlue), STRIPPÉ par ProGuard : certaines classes utilitaires ont perdu
  # des méthodes que spine-libgdx-perblue utilise (ex. DataInput.readString, IntSet.clear). Le gdx-1.9.7 COMPLET
  # (cache gradle) les a → on dépose SES versions dans le dir de classes (PREMIER sur le CP), qui ombragent les
  # stubs. On ne remplace QUE des classes STRIPPÉES (superset correct), JAMAIS une classe MODIFIÉE par PerBlue
  # (ex. Array : add()->boolean au lieu de void ; l'ombrer casserait le jeu ET spine). Inoffensif pour unidbg.
  GDXFULL=$(find "$HOME/.gradle" -name 'gdx-1.9.7.jar' -path '*com.badlogicgames.gdx*' 2>/dev/null | head -1)
  if [ -n "$GDXFULL" ]; then
    for K in DataInput IntSet; do
      unzip -oq "$GDXFULL" "com/badlogic/gdx/utils/$K.class" -d "$BUILD/classes/java/main"
    done
  else echo "[desktop] WARN: gdx-1.9.7.jar (classes complètes) introuvable → backend java échouera"; fi
fi

echo "[desktop] spine backend = ${DH_SPINEBACKEND:-unidbg} (défaut jni ; serveur = unidbg bit-exact pour l'autorité)"
echo "[desktop] lancement (GameMain via backend LWJGL3 maison) ..."
set +e
if [ -n "${DH_TIMEOUT:-}" ]; then
  timeout "${DH_TIMEOUT}" java $JOPTS -cp "$CP" dhdesktop.DesktopLauncher
else
  java $JOPTS -cp "$CP" dhdesktop.DesktopLauncher
fi
RC=$?
set -e
[ -n "${XVFB_PID:-}" ] && kill "$XVFB_PID" 2>/dev/null || true
exit $RC
