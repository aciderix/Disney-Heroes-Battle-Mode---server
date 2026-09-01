#!/usr/bin/env python3
"""
PATCH APK (brique 4c) — injecte, dans le smali désassemblé du dex du jeu : (1) `ServerType.setLive(host, port)` (méthode
statique qui redirige LIVE) ; (2) un HOOK au tout début d'`AndroidLauncher.onCreate` qui lit les SharedPreferences
« dhserver » (écrites par l'écran de sélection) et appelle `setLive`. Idempotent. Aucune règle de jeu modifiée (§1) —
seule l'adresse du serveur est fixée au boot d'après le choix du joueur.

Usage : apk_inject_smali.py <smaliDir>
"""
import re
import sys

SETLIVE = '''
.method public static setLive(Ljava/lang/String;I)V
    .locals 3
    sget-object v0, Lcom/perblue/heroes/ServerType;->LIVE:Lcom/perblue/heroes/ServerType;
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "http://"
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    iput-object v1, v0, Lcom/perblue/heroes/ServerType;->gameHost:Ljava/lang/String;
    iput p1, v0, Lcom/perblue/heroes/ServerType;->gamePort:I
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "http://"
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v2, ":"
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v2, "/live/index.txt"
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    iput-object v1, v0, Lcom/perblue/heroes/ServerType;->contentLocation:Ljava/lang/String;
    return-void
.end method
'''

HOOK = '''
    const-string v0, "dhserver"
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "host"
    const/4 v2, 0x0
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    if-eqz v1, :dh_skip
    const-string v2, "port"
    const/4 v3, -0x1
    invoke-interface {v0, v2, v3}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v2
    if-lez v2, :dh_skip
    invoke-static {v1, v2}, Lcom/perblue/heroes/ServerType;->setLive(Ljava/lang/String;I)V
    :dh_skip
'''


def main(d: str) -> None:
    st = d + "/com/perblue/heroes/ServerType.smali"
    al = d + "/com/perblue/heroes/android/AndroidLauncher.smali"

    s = open(st, encoding="utf-8").read()
    if "->setLive(" not in s:
        open(st, "a", encoding="utf-8").write(SETLIVE)
        print("[smali] ServerType.setLive ajouté")
    else:
        print("[smali] ServerType.setLive déjà présent")

    a = open(al, encoding="utf-8").read()
    if "dhserver" in a:
        print("[smali] hook onCreate déjà présent")
    else:
        m = re.search(r'(\.method protected onCreate\(Landroid/os/Bundle;\)V\s*\n\s*\.registers \d+\n)', a)
        if not m:
            print("ERREUR : AndroidLauncher.onCreate(Bundle) introuvable"); sys.exit(1)
        a = a[:m.end()] + HOOK + a[m.end():]
        open(al, "w", encoding="utf-8").write(a)
        print("[smali] hook onCreate ajouté (lit dhserver → setLive)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: apk_inject_smali.py <smaliDir>")
    main(sys.argv[1])
