#!/usr/bin/env python3
"""
Disney Heroes — Serveur de contenu v0 (assets)

Rôle (voir docs/ASSETS.md, docs/ARCHITECTURE.md) : remplacer le serveur de contenu
d'origine (content.disneyheroesgame.com, hors ligne) pour que l'AssetUpdater du jeu se
déroule normalement, SANS modifier le jeu.

- GET /live/index.txt      -> sert le manifeste, avec les URLs d'archives RÉÉCRITES pour
                              pointer vers CE serveur (/live/<nom>.zip). Le jeu filtre
                              lui-même les lignes de son device/version (cf. ASSETS.md).
- GET|HEAD /live/<nom>.zip -> sert une copie locale si présente (--cache), sinon redirige
                              (302) vers l'archive publique (archive.org).

Aucune dépendance externe (stdlib) → hébergeable partout. Zéro rustine : on sert
réellement le bon contenu (décision de téléchargement = révision, cf. RISQUE #1 résolu).

Pour brancher le jeu dessus : rediriger ServerType.LIVE.contentLocation vers
http://<host>:<port>/live/index.txt (réécriture par réflexion au démarrage / passerelle,
sans patcher le bytecode). Voir docs/PROTOCOL.md §0.

Usage :
  server/content_server.py [--port 8080] [--index index.txt]
      [--cache assets-cache] [--archive-base <url>] [--rewrite-host host:port]
"""
import argparse
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_ARCHIVE_BASE = "https://archive.org/download/disney-heroes-battle-mode-live-assets"
ARCHIVE_NAME_RE = re.compile(r"/live/([A-Za-z0-9_.\-]+\.zip)$")


class Config:
    def __init__(self, index_path, cache_dir, archive_base, rewrite_host, game_server):
        self.index_path = index_path
        self.cache_dir = cache_dir
        self.archive_base = archive_base.rstrip("/")
        self.rewrite_host = rewrite_host  # e.g. "1.2.3.4:8080" or None (use Host header)
        self.game_server = game_server    # adresse TCP du serveur de jeu, ex. "127.0.0.1:8081"


def build_index(raw: str, base_url: str) -> bytes:
    """Réécrit la colonne URL de l'index pour pointer vers base_url/live/<nom>.zip.

    Le format est TSV : ...  URL  Size (l'URL est l'avant-dernière colonne). On ne
    remplace que le préfixe d'hôte/chemin en gardant le nom de fichier d'origine, donc
    l'ordre et le contenu des autres colonnes restent identiques au manifeste du jeu.
    """
    out_lines = []
    for line in raw.splitlines():
        m = re.search(r"https?://\S+/([A-Za-z0-9_.\-]+\.zip)", line)
        if m:
            name = m.group(1)
            line = line[: m.start()] + f"{base_url}/live/{name}" + line[m.end():]
        out_lines.append(line)
    return ("\n".join(out_lines) + "\n").encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    cfg: Config = None  # set on the server instance's handler class

    def _base_url(self) -> str:
        host = self.cfg.rewrite_host or self.headers.get("Host") or "127.0.0.1"
        return f"http://{host}"

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path.rstrip("/") == "/login":
            return self._serve_login()
        self._send(404, "text/plain", b"not found\n", head_only=False)

    def _serve_login(self):
        # Le client POST des params device (form-encoded, dont `userID`) puis attend un JSON :
        #   {"status":"good","data":"<host>:<port>[:tls]","requestID":"..."}
        # `data` = adresse du serveur de JEU (TCP) vers lequel le client ouvre le socket
        # (GruntNIOTCPServer / server/java/dhserver/LoginServer.java). `requestID` = le BILLET que le client
        # recopie dans son ClientInfo.loginRequestID (cf. GameMain). En mode STRICT (DH_AUTH_URL défini), on
        # demande un billet NOMINATIF à l'AuthService (/auth/mint) pour le userID qui vient de s'authentifier via
        # le launcher (défi-réponse) ; sinon requestID="" (permissif, comportement historique).
        import json as _json
        import os
        from urllib.parse import parse_qs, urlsplit
        user_id = None
        try:
            # Le client du jeu émet un GET dont les params device (dont `userID`) sont dans la QUERY-STRING
            # (libGDX `HttpRequestBuilder.formEncodedContent` sur un GET → query), PAS dans le corps. On lit
            # donc la query d'abord, puis le corps en repli (POST éventuel).
            params = parse_qs(urlsplit(self.path).query)
            if not (params.get("userID") or params.get("userId")):
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length) if length else b""
                if raw:
                    params = parse_qs(raw.decode("utf-8", "replace"))
            vals = params.get("userID") or params.get("userId")
            if vals:
                user_id = vals[0]
        except Exception:
            pass
        request_id = ""
        auth_url = os.environ.get("DH_AUTH_URL")
        # STRICT — LOGIN UNIQUE : le client de jeu boote toujours avec LoadingScreen(userID=0) EN DUR, donc son
        # /login porte userID=0. Dans le vrai jeu, l'identité est résolue par l'APPAREIL. Ici, c'est le LAUNCHER
        # qui connaît le userID (dérivé de la phrase) et l'a authentifié auprès de l'AuthService ; il nous le
        # fournit via DH_MINT_USERID → on frappe le billet nominatif pour CE compte (indépendant du userID=0 du
        # /login). ⇒ login UNIQUE : le nouveau joueur déroule l'intro, l'avancé reprend, TOUS en mode strict.
        # Le userID authentifié fourni par le launcher : soit en clair (DH_MINT_USERID), soit dans un FICHIER
        # (DH_MINT_USERID_FILE) que le launcher écrit APRÈS avoir authentifié le compte (le content_server, lui,
        # démarre avant de connaître le userID). Repli = le userID reçu dans le /login.
        mint_id = os.environ.get("DH_MINT_USERID")
        if not mint_id:
            mint_file = os.environ.get("DH_MINT_USERID_FILE")
            if mint_file:
                try:
                    with open(mint_file) as fh:
                        mint_id = fh.read().strip()
                except Exception:
                    mint_id = None
        mint_id = mint_id or user_id
        if auth_url and mint_id:
            try:
                request_id = self._mint_ticket(auth_url, mint_id)
                sys.stderr.write("[content] /login (userID reçu=%s → mint compte launcher=%s) → billet=%r (strict)\n"
                                 % (user_id, mint_id, request_id))
            except Exception as e:
                sys.stderr.write("[content] /auth/mint échec (userID=%s): %s\n" % (mint_id, e))
        body = _json.dumps({"status": "good", "data": self.cfg.game_server, "requestID": request_id}).encode("utf-8")
        self._send(200, "application/json", body, head_only=False)

    def _mint_ticket(self, auth_url, user_id):
        """Flux « Jouer » strict : demande un billet nominatif (loginRequestID) à l'AuthService. "" si non authentifié."""
        import urllib.request, urllib.parse, json as _json
        data = urllib.parse.urlencode({"userID": user_id}).encode("utf-8")
        req = urllib.request.Request(auth_url.rstrip("/") + "/auth/mint", data=data,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(req, timeout=3) as resp:
                if resp.status == 200:
                    return _json.loads(resp.read().decode("utf-8")).get("loginRequestID", "")
        except urllib.error.HTTPError:
            return ""   # 401 = joueur non authentifié → pas de billet
        return ""

    def _serve(self, head_only: bool):
        path = self.path.split("?", 1)[0]

        # Le client peut faire le login en GET ou POST selon la version → gérer les deux.
        if path.rstrip("/") == "/login":
            return self._serve_login()

        if path.rstrip("/") in ("/live/index.txt", "/index.txt"):
            return self._serve_index(head_only)

        m = ARCHIVE_NAME_RE.search(path)
        if m:
            return self._serve_archive(m.group(1), head_only)

        # santé / racine
        if path in ("/", "/health", "/status"):
            body = b"disney-heroes content server v0\n"
            self._send(200, "text/plain", body, head_only)
            return

        self._send(404, "text/plain", b"not found\n", head_only)

    def _serve_index(self, head_only: bool):
        try:
            with open(self.cfg.index_path, "r", encoding="utf-8") as f:
                raw = f.read()
        except OSError as e:
            self._send(500, "text/plain", f"index unreadable: {e}\n".encode(), head_only)
            return
        body = build_index(raw, self._base_url())
        # Le jeu attend un manifeste texte ; type volontairement simple.
        self._send(200, "text/plain; charset=utf-8", body, head_only)

    # verrou par-nom : si deux clients demandent le même zip en même temps, un seul le télécharge.
    _dl_locks = {}
    _dl_locks_guard = None

    def _serve_archive(self, name: str, head_only: bool):
        rng = self.headers.get("Range")
        # 1) copie locale prioritaire (hébergeur autonome, sans dépendre d'archive.org)
        if self.cfg.cache_dir:
            local = os.path.join(self.cfg.cache_dir, name)
            if os.path.isfile(local):
                return self._serve_local_file(local, head_only, rng)
            # 2) PAS en cache → on TÉLÉCHARGE LE FICHIER COMPLET côté serveur (retries + reprise), on l'ENREGISTRE
            #    dans le cache, PUIS on le sert. On ne streame JAMAIS un flux partiel : une coupure intermittente
            #    d'archive.org donnerait sinon un zip TRONQUÉ au client (« Could not read local file header »).
            #    Bénéfice turnkey : le cache = miroir local qui se construit → clients suivants servis en local.
            import threading
            if Handler._dl_locks_guard is None:
                Handler._dl_locks_guard = threading.Lock()
            with Handler._dl_locks_guard:
                lock = Handler._dl_locks.setdefault(name, threading.Lock())
            with lock:
                if not os.path.isfile(local):  # re-check : un autre thread a pu le télécharger pendant l'attente
                    os.makedirs(self.cfg.cache_dir, exist_ok=True)
                    part = local + ".part"
                    target = f"{self.cfg.archive_base}/{name}"
                    # Téléchargement STDLIB (urllib) — plus de dépendance `curl` (zéro-install). urllib suit la
                    # redirection 302 d'archive.org ; reprise (Range) + retries = robuste aux coupures intermittentes.
                    sys.stderr.write("[content] cache MISS %s → téléchargement archive.org (stdlib, reprise+retries)…\n" % name)
                    ok = self._download_to_file(target, part)
                    if not ok or not os.path.isfile(part) or os.path.getsize(part) == 0:
                        sys.stderr.write("[content] échec téléchargement %s\n" % name)
                        if os.path.isfile(part):
                            try: os.remove(part)
                            except OSError: pass
                        return self._send(502, "text/plain", b"archive fetch failed\n", head_only)
                    os.replace(part, local)  # atomique → jamais de fichier partiel visible sous son nom final
                    sys.stderr.write("[content] cache RENSEIGNÉ %s (%d o)\n" % (name, os.path.getsize(local)))
            return self._serve_local_file(local, head_only, rng)
        # 3) sans cache : repli best-effort (streaming direct). Peut donner un partiel si archive.org coupe.
        self._relay_stream(name, head_only, rng)

    def _serve_local_file(self, local: str, head_only: bool, rng):
        """Sert un fichier local COMPLET, avec support Range (206) pour la reprise côté client."""
        size = os.path.getsize(local)
        start, end = 0, size - 1
        if rng:
            try:
                spec = rng.split("=", 1)[-1]
                a, b = (spec.split("-") + [""])[:2]
                start = int(a) if a else 0
                end = int(b) if b else size - 1
            except Exception:
                start, end = 0, size - 1
        length = max(0, end - start + 1)
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if not head_only:
            with open(local, "rb") as f:
                f.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = f.read(min(1 << 16, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)

    def _download_to_file(self, url: str, part: str, retries: int = 6) -> bool:
        """Télécharge `url` → fichier `part` en STDLIB (urllib) — plus de `curl`. REPRISE : si `part` existe déjà,
        on demande un Range depuis sa taille (append sur 206 ; restart sur 200). Retries avec backoff. urllib suit
        les redirections (302 archive.org). Renvoie True si le transfert s'est terminé sans exception."""
        import urllib.request, time
        for attempt in range(1, retries + 1):
            have = os.path.getsize(part) if os.path.isfile(part) else 0
            req = urllib.request.Request(url, headers={"User-Agent": "dh-content/1.0"})
            if have:
                req.add_header("Range", "bytes=%d-" % have)
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    code = getattr(resp, "status", None) or resp.getcode()
                    # 206 = le serveur honore la reprise → on APPEND ; sinon (200) on repart de zéro.
                    if have and code == 206:
                        mode = "ab"
                    else:
                        mode, have = "wb", 0
                    with open(part, mode) as f:
                        while True:
                            chunk = resp.read(1 << 16)
                            if not chunk:
                                break
                            f.write(chunk)
                    return True
            except Exception as e:
                sys.stderr.write("[content]   tentative %d/%d échouée (%s)\n" % (attempt, retries, e))
                time.sleep(min(2 * attempt, 10))
        return False

    def _relay_stream(self, name: str, head_only: bool, rng):
        import urllib.request
        target = f"{self.cfg.archive_base}/{name}"
        req = urllib.request.Request(target, headers={"User-Agent": "dh-content/1.0"})
        if rng:
            req.add_header("Range", "bytes=" + rng.split("=", 1)[-1])
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                self.send_response(206 if rng else 200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()
                if not head_only:
                    while True:
                        chunk = resp.read(1 << 16)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
        except Exception as e:
            try: self._send(502, "text/plain", f"relay error: {e}\n".encode(), head_only)
            except Exception: pass

    def _send(self, code: int, ctype: str, body: bytes, head_only: bool):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("[content] %s - %s\n" % (self.address_string(), fmt % args))


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    ap = argparse.ArgumentParser(description="Disney Heroes content server v0")
    ap.add_argument("--port", type=int, default=int(os.environ.get("DH_CONTENT_PORT", "8080")))
    ap.add_argument("--host", default=os.environ.get("DH_CONTENT_BIND", "0.0.0.0"))
    ap.add_argument("--index", default=os.path.join(repo, "index.txt"))
    ap.add_argument("--cache", default=os.environ.get("DH_ASSETS_CACHE", os.path.join(repo, "assets-cache")))
    ap.add_argument("--archive-base", default=os.environ.get("DH_ARCHIVE_BASE", DEFAULT_ARCHIVE_BASE))
    ap.add_argument("--rewrite-host", default=os.environ.get("DH_REWRITE_HOST"),
                    help="host:port à écrire dans les URLs de l'index (défaut: en-tête Host)")
    ap.add_argument("--game-server", default=os.environ.get("DH_GAME_SERVER", "127.0.0.1:8081"),
                    help="adresse TCP du serveur de jeu renvoyée par POST /login (défaut 127.0.0.1:8081)")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.index):
        ap.error(f"index introuvable: {args.index}")
    cache = args.cache if args.cache and os.path.isdir(args.cache) else None

    Handler.cfg = Config(args.index, cache, args.archive_base, args.rewrite_host, args.game_server)
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    sys.stderr.write(
        f"[content] serving index {args.index}\n"
        f"[content] cache: {cache or '(none)'} | archive: {args.archive_base}\n"
        f"[content] listening on http://{args.host}:{args.port}/live/index.txt\n"
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("[content] shutting down\n")
        httpd.shutdown()


if __name__ == "__main__":
    main()
