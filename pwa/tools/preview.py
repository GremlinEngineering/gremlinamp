#!/usr/bin/env python3
"""Serve the built PWA packages on localhost with the current public data."""
import argparse
import shutil
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


class Handler(SimpleHTTPRequestHandler):
    data_paths = ('/music/', '/game-worlds/orbita/')
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, '.wasm': 'application/wasm',
                      '.webmanifest': 'application/manifest+json', '.js': 'text/javascript'}

    def do_GET(self):
        path = urlsplit(self.path).path
        if path.startswith(self.data_paths):
            self.proxy()
        elif path.startswith('/pwa/'):
            self.path = self.path[4:]
            super().do_GET()
        else:
            self.send_error(404)

    def proxy(self):
        request = Request('https://games.oopssre.ru' + self.path)
        if self.headers.get('Range'):
            request.add_header('Range', self.headers['Range'])
        try:
            with urlopen(request, timeout=20) as response:
                self.send_response(response.status)
                for key in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges', 'ETag'):
                    if response.headers.get(key):
                        self.send_header(key, response.headers[key])
                self.end_headers()
                shutil.copyfileobj(response, self.wfile)
        except HTTPError as error:
            self.send_error(error.code)
        except (ConnectionError, TimeoutError, URLError):
            self.close_connection = True


if __name__ == '__main__':
    cli = argparse.ArgumentParser()
    cli.add_argument('--port', type=int, default=8766)
    args = cli.parse_args()
    root = Path(__file__).resolve().parents[1] / 'dist'
    with ThreadingHTTPServer(('127.0.0.1', args.port), partial(Handler, directory=root)) as server:
        print(f'http://127.0.0.1:{args.port}/pwa/gremlinamp/\nhttp://127.0.0.1:{args.port}/pwa/orbita/', flush=True)
        server.serve_forever()
