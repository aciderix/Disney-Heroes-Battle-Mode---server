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
    def __init__(self, index_path, cache_dir, archive_base, rewrite_host):
        self.index_path = index_path
        self.cache_dir = cache_dir
        self.archive_base = archive_base.rstrip("/")
        self.rewrite_host = rewrite_host  # e.g. "1.2.3.4:8080" or None (use Host header)


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

    def _serve(self, head_only: bool):
        path = self.path.split("?", 1)[0]

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

    def _serve_archive(self, name: str, head_only: bool):
        # 1) copie locale prioritaire (hébergeur autonome, sans dépendre d'archive.org)
        if self.cfg.cache_dir:
            local = os.path.join(self.cfg.cache_dir, name)
            if os.path.isfile(local):
                size = os.path.getsize(local)
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(size))
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()
                if not head_only:
                    with open(local, "rb") as f:
                        while True:
                            chunk = f.read(1 << 16)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                return
        # 2) sinon redirection vers l'archive publique (archive.org suit les ranges)
        target = f"{self.cfg.archive_base}/{name}"
        self.send_response(302)
        self.send_header("Location", target)
        self.send_header("Content-Length", "0")
        self.end_headers()

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
    args = ap.parse_args(argv)

    if not os.path.isfile(args.index):
        ap.error(f"index introuvable: {args.index}")
    cache = args.cache if args.cache and os.path.isdir(args.cache) else None

    Handler.cfg = Config(args.index, cache, args.archive_base, args.rewrite_host)
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
