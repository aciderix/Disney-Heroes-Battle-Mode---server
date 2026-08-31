#!/usr/bin/env python3
"""
ANNUAIRE / PATCH APK (brique 4a) — réécrit la redirection de `ServerType.LIVE` dans le smali désassemblé, pour pointer un
client mobile vers un serveur auto-hébergé (au lieu des serveurs officiels éteints). NON destructif du reste : on ne touche
QUE les 4 valeurs de LIVE (protocole, hôte, port, URL de contenu), repérées par le mapping REGISTRE→PARAMÈTRE de l'appel
constructeur (robuste aux numéros de ligne). Aucune règle de jeu modifiée (§1) — seule l'adresse du serveur change.

Constructeur (relevé bytecode 12.1.0) : ServerType(name, ordinal, protocole, hote, port, contenu) ;
  gameHost = protocole + hote ; gamePort = port ; contentLocation = contenu (ou dérivé si absent).
L'URL de login émise par le client = gameHost + ":" + gamePort + "/login".

Usage : apk_redirect_smali.py <ServerType.smali> <host> <port> <contentUrl>
"""
import re
import sys


def patch(path: str, host: str, port: int, content_url: str) -> None:
    with open(path, "r", encoding="utf-8") as fh:
        lines = fh.readlines()

    # 1) trouver l'appel constructeur de LIVE : invoke-direct/range {vLOW .. vHIGH}, ...ServerType;-><init>
    #    précédé (quelques lignes au-dessus) par `const-string vN, "LIVE"`.
    inv_re = re.compile(r'invoke-direct/range \{v(\d+) \.\. v(\d+)\}, Lcom/perblue/heroes/ServerType;-><init>')
    live_idx = None
    low = None
    for i, ln in enumerate(lines):
        m = inv_re.search(ln)
        if not m:
            continue
        # LIVE ? chercher un const-string "LIVE" dans les ~12 lignes précédentes
        window = "".join(lines[max(0, i - 12):i])
        if re.search(r'const-string(?:/jumbo)? v\d+, "LIVE"', window):
            live_idx = i
            low = int(m.group(1))
            break
    if live_idx is None or low is None:
        raise SystemExit("ERREUR : appel constructeur de ServerType.LIVE introuvable (structure inattendue).")

    # 2) mapping registre→param : {this=low, name=low+1, ordinal=low+2, protocole=low+3, hote=low+4, port=low+5, contenu=low+6}
    reg_proto, reg_host, reg_port, reg_content = low + 3, low + 4, low + 5, low + 6

    # 3) remonter depuis l'invoke, patcher la dernière affectation de chacun de ces registres
    want = {reg_proto: ("str", "http://"), reg_host: ("str", host),
            reg_port: ("int", port), reg_content: ("str", content_url)}
    done = set()
    str_re = re.compile(r'^(\s*)const-string(?:/jumbo)? v(\d+), ".*"\s*$')
    int_re = re.compile(r'^(\s*)const(?:/16|/4|/high16)? v(\d+), .*$')
    for j in range(live_idx - 1, -1, -1):
        if len(done) == len(want):
            break
        sm = str_re.match(lines[j])
        im = int_re.match(lines[j]) if not sm else None
        reg = int((sm or im).group(2)) if (sm or im) else None
        if reg is None or reg not in want or reg in done:
            continue
        kind, val = want[reg]
        indent = (sm or im).group(1)
        if kind == "str" and sm:
            lines[j] = f'{indent}const-string/jumbo v{reg}, "{val}"\n'
            done.add(reg)
        elif kind == "int" and im:
            lines[j] = f'{indent}const/16 v{reg}, 0x{val:x}\n'
            done.add(reg)

    missing = [r for r in want if r not in done]
    if missing:
        raise SystemExit(f"ERREUR : registres non patchés {missing} (affectations introuvables avant l'invoke).")

    with open(path, "w", encoding="utf-8") as fh:
        fh.writelines(lines)
    print(f"[patch] ServerType.LIVE → http://{host}:{port} (login) · contenu {content_url}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: apk_redirect_smali.py <ServerType.smali> <host> <port> <contentUrl>")
    patch(sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4])
