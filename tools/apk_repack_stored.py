#!/usr/bin/env python3
# Ré-empaquète un dossier extrait en APK/zip, en STOCKANT (non-compressé) `resources.arsc` et les `.so`.
#
# Pourquoi : depuis Android 11, une app qui cible l'API 30+ (Disney Heroes = targetSdk 35) DOIT avoir son
# `resources.arsc` stocké NON-COMPRESSÉ et aligné sur 4 octets, sinon l'installation échoue avec
# `INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED` (« -124 ... resources.arsc ... stored uncompressed and aligned
# on a 4-byte boundary »). `jar cf` (utilisé avant pour ré-empaqueter après patch du dex) compressait TOUT, y
# compris `resources.arsc` → APK refusé. `zipalign` (dans uber-apk-signer) ALIGNE mais ne DÉCOMPRESSE pas : il faut
# donc stocker `resources.arsc` non-compressé AVANT la signature. On stocke aussi les `.so` non-compressés (bonne
# pratique : permet à zipalign -p de les page-aligner ; sans effet néfaste sinon). Le reste reste compressé (DEFLATE)
# pour garder l'APK léger.
#
# Usage : apk_repack_stored.py <dossier_extrait> <sortie.apk>
import sys, os, zipfile

def main():
    if len(sys.argv) != 3:
        print("usage: apk_repack_stored.py <src_dir> <out.apk>", file=sys.stderr); sys.exit(2)
    src, out = sys.argv[1], sys.argv[2]
    def stored(arc):
        return arc == "resources.arsc" or arc.endswith(".so")
    entries = []
    for root, _dirs, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, src).replace(os.sep, "/")
            entries.append((full, arc))
    # resources.arsc en tête (lecture/alignement), puis ordre stable
    entries.sort(key=lambda t: (t[1] != "resources.arsc", t[1]))
    n_store = 0
    with zipfile.ZipFile(out, "w") as z:
        for full, arc in entries:
            if stored(arc):
                z.write(full, arc, compress_type=zipfile.ZIP_STORED); n_store += 1
            else:
                z.write(full, arc, compress_type=zipfile.ZIP_DEFLATED)
    print("[repack] %d entrées (%d stockées non-compressées : resources.arsc + .so)" % (len(entries), n_store))

if __name__ == "__main__":
    main()
