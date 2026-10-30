"""Local read-only proxy for official Google Maven and Maven Central."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import sys

UPSTREAM = {
    "google": "https://dl.google.com/dl/android/maven2/",
    "central": "https://repo.maven.apache.org/maven2/",
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_HEAD(self):
        self.fetch(True)

    def do_GET(self):
        self.fetch(False)

    def fetch(self, head):
        parts = self.path.lstrip("/").split("/", 1)
        if len(parts) != 2 or parts[0] not in UPSTREAM or ".." in parts[1]:
            self.send_error(404)
            return
        url = UPSTREAM[parts[0]] + parts[1]
        try:
            with urlopen(Request(url, method="HEAD" if head else "GET"), timeout=40) as response:
                data = b"" if head else response.read()
                self.send_response(response.status)
                self.send_header("Content-Length", response.headers.get("Content-Length") if head else str(len(data)))
                self.send_header("Content-Type", response.headers.get("Content-Type", "application/octet-stream"))
                self.end_headers()
                if not head:
                    self.wfile.write(data)
        except HTTPError as error:
            self.send_error(error.code)
        except Exception as error:
            self.send_error(502, str(error))


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8765), Handler).serve_forever()
