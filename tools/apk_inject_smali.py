#!/usr/bin/env python3
"""
PATCH APK (brique 4c + V3 3b) — injecte, dans le smali désassemblé du dex du jeu :
 (1) `ServerType.setLive(host, port)` (méthode statique qui redirige LIVE) ;
 (2) `ServerType.dhBoot(Context)` : lit les SharedPreferences « dhserver » (écrites par l'écran de sélection) →
     `setLive(host, port)` ET, si un compte est choisi, `BuildOptions.TEST_USER_ID = getLong("userID")` ;
 (3) un HOOK au tout début d'`AndroidLauncher.onCreate` = un simple `invoke-static {p0} dhBoot` (registre-sûr) ;
 (4) `ServerType.dhLoginUserID(J)J` = `TEST_USER_ID ?: orig`, appelé en tête de `GameMain.connectToLoginServer`
     pour ÉCRASER le userID du /login HTTP (le param qui alimente `map.put("userID", …)`, relevé au bytecode) →
     le mint nominatif vise le compte mnémonique authentifié (flux STRICT). Ouvert / sans compte : TEST_USER_ID
     null → aucun changement. Idempotent. Aucune règle de jeu modifiée (§1) — seuls l'adresse et le userID de
     login (deux valeurs de plateforme, comme le fait le port desktop par réflexion) sont fixés au boot.

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

# dhBoot(Context) : lit dhserver → setLive(host,port) + (si compte) TEST_USER_ID = getLong("userID").
# Registre-sûr (méthode dédiée, .locals propres) — le hook onCreate n'utilise plus que p0.
DHBOOT = '''
.method public static dhBoot(Landroid/content/Context;)V
    .locals 5
    const-string v0, "dhserver"
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "host"
    const/4 v2, 0x0
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    if-eqz v1, :dh_done
    const-string v2, "port"
    const/4 v3, -0x1
    invoke-interface {v0, v2, v3}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v2
    if-lez v2, :dh_done
    invoke-static {v1, v2}, Lcom/perblue/heroes/ServerType;->setLive(Ljava/lang/String;I)V
    const-string v1, "userID"
    invoke-interface {v0, v1}, Landroid/content/SharedPreferences;->contains(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :dh_done
    const-string v1, "userID"
    const-wide/16 v3, 0x0
    invoke-interface {v0, v1, v3, v4}, Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J
    move-result-wide v3
    invoke-static {v3, v4}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
    move-result-object v1
    sput-object v1, Lcom/perblue/heroes/BuildOptions;->TEST_USER_ID:Ljava/lang/Long;
    :dh_done
    return-void
.end method
'''

# dhLoginUserID(orig) = TEST_USER_ID ?: orig — appelé en tête de connectToLoginServer pour fixer le userID du /login.
DHLOGINUSERID = '''
.method public static dhLoginUserID(J)J
    .locals 2
    sget-object v0, Lcom/perblue/heroes/BuildOptions;->TEST_USER_ID:Ljava/lang/Long;
    if-eqz v0, :dh_orig
    invoke-virtual {v0}, Ljava/lang/Long;->longValue()J
    move-result-wide v0
    return-wide v0
    :dh_orig
    return-wide p0
.end method
'''

# hook onCreate : un seul invoke-static (registre-sûr).
HOOK = '''
    invoke-static {p0}, Lcom/perblue/heroes/ServerType;->dhBoot(Landroid/content/Context;)V
'''

# override du userID de login (2 instructions, aucun registre temporaire).
LOGIN_OVERRIDE = '''    invoke-static {p1, p2}, Lcom/perblue/heroes/ServerType;->dhLoginUserID(J)J
    move-result-wide p1
'''


def main(d: str) -> None:
    st = d + "/com/perblue/heroes/ServerType.smali"
    al = d + "/com/perblue/heroes/android/AndroidLauncher.smali"
    gm = d + "/com/perblue/heroes/GameMain.smali"

    s = open(st, encoding="utf-8").read()
    if "->setLive(" not in s:
        open(st, "a", encoding="utf-8").write(SETLIVE)
        print("[smali] ServerType.setLive ajouté")
    else:
        print("[smali] ServerType.setLive déjà présent")
    if "dhBoot(Landroid/content/Context;)V" not in s:
        open(st, "a", encoding="utf-8").write(DHBOOT + DHLOGINUSERID)
        print("[smali] ServerType.dhBoot + dhLoginUserID ajoutés")
    else:
        print("[smali] ServerType.dhBoot déjà présent")

    a = open(al, encoding="utf-8").read()
    if "->dhBoot(" in a:
        print("[smali] hook onCreate déjà présent")
    else:
        m = re.search(r'(\.method protected onCreate\(Landroid/os/Bundle;\)V\s*\n\s*\.(?:registers|locals) \d+\n)', a)
        if not m:
            print("ERREUR : AndroidLauncher.onCreate(Bundle) introuvable"); sys.exit(1)
        a = a[:m.end()] + HOOK + a[m.end():]
        open(al, "w", encoding="utf-8").write(a)
        print("[smali] hook onCreate ajouté (invoke dhBoot)")

    # (4) override du userID de login dans connectToLoginServer → /login HTTP porte le userID mnémonique.
    g = open(gm, encoding="utf-8").read()
    if "->dhLoginUserID(" in g:
        print("[smali] override userID login déjà présent")
    else:
        m = re.search(r'(\.method private connectToLoginServer\(JILcom/perblue/heroes/ServerType;Z\)V\s*\n\s*\.(?:registers|locals) \d+\n)', g)
        if not m:
            print("ERREUR : GameMain.connectToLoginServer(JIL…;Z)V introuvable"); sys.exit(1)
        g = g[:m.end()] + LOGIN_OVERRIDE + g[m.end():]
        open(gm, "w", encoding="utf-8").write(g)
        print("[smali] override userID login ajouté (connectToLoginServer → dhLoginUserID)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: apk_inject_smali.py <smaliDir>")
    main(sys.argv[1])
