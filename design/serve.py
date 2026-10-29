#!/usr/bin/env python3
"""Preview server for the Morningstar design mockups.

Serves the repository root so `design/morningstar-home.html` can reach the real bundled fonts at
`composeApp/src/androidMain/res/font/*.ttf` with relative URLs, and redirects `/` to the mockup so
the preview pane opens on the screen instead of a directory listing.

    python3 design/serve.py [--port 4173] [--bind 0.0.0.0]

Dev-preview only. No auth, no TLS, no caching — do not expose this beyond a local session.
"""

from __future__ import annotations

import argparse
import functools
import http.server
import os
import socketserver
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANDING = "/design/morningstar-home.html"


class Handler(http.server.SimpleHTTPRequestHandler):
    """SimpleHTTPRequestHandler with the two things the preview actually needs."""

    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".ttf": "font/ttf",
        ".woff2": "font/woff2",
        ".svg": "image/svg+xml",
        ".webmanifest": "application/manifest+json",
    }

    def do_GET(self) -> None:  # noqa: N802 - stdlib naming
        if self.path in ("/", "/index.html"):
            self.send_response(302)
            self.send_header("Location", LANDING)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        super().do_GET()

    def end_headers(self) -> None:
        # A stale mockup is worse than no mockup: never let the browser hold onto one.
        self.send_header("Cache-Control", "no-store, max-age=0")
        super().end_headers()

    def log_message(self, fmt: str, *args) -> None:  # keep the log to one readable line
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True  # survive a restart on the same port
    daemon_threads = True


def main() -> int:
    parser = argparse.ArgumentParser(description="Morningstar design preview server")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "4173")))
    parser.add_argument("--bind", default="0.0.0.0", help="0.0.0.0 so the sandbox proxy can reach it")
    args = parser.parse_args()

    handler = functools.partial(Handler, directory=REPO_ROOT)
    with Server((args.bind, args.port), handler) as httpd:
        print(f"Morningstar preview: http://{args.bind}:{args.port}{LANDING}", flush=True)
        print(f"serving {REPO_ROOT}", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
