from __future__ import annotations

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from tools import device_soak


MEDIA_SIZE = 1024 * 1024


class _PluxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 - nom imposé par BaseHTTPRequestHandler
        if self.path == "/api/library/items":
            self._json(
                [
                    {
                        "id": "video-1",
                        "kind": "movie",
                        "size": MEDIA_SIZE,
                        "container": "mkv",
                        "video_codec": "hevc",
                        "height": 2160,
                    }
                ]
            )
            return
        if self.path == "/health":
            self._json({"status": "ok"})
            return
        if self.path == "/stream/direct/video-1":
            header = self.headers.get("Range", "")
            if not header.startswith("bytes="):
                self.send_error(400)
                return
            start_text, end_text = header.removeprefix("bytes=").split("-", maxsplit=1)
            start, end = int(start_text), int(end_text)
            body = bytes((position % 251 for position in range(start, end + 1)))
            self.send_response(206)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Range", f"bytes {start}-{end}/{MEDIA_SIZE}")
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def _json(self, value: object) -> None:
        body = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *args: object) -> None:
        pass


@pytest.fixture
def fake_server() -> str:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _PluxyHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_soak_runner_executes_health_and_random_range_cycles(fake_server: str) -> None:
    args = argparse.Namespace(
        server=fake_server,
        item_id=None,
        duration_minutes=1,
        cycles=3,
        interval_seconds=0,
        adb_serial=None,
        adb_every=1,
        seed=42,
    )

    report = device_soak.run(args)

    assert report["cycles"] == 3
    assert report["failures"] == 0
    assert report["success_rate"] == 1.0
    assert all(sample["range_bytes"] == 256 * 1024 for sample in report["samples"])


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1:8420",
        "http://example.com:8420",
        "http://user:password@127.0.0.1:8420",
        "http://127.0.0.1:8420/api",
    ],
)
def test_soak_runner_rejects_non_local_or_ambiguous_server_urls(url: str) -> None:
    with pytest.raises(argparse.ArgumentTypeError):
        device_soak.private_server_url(url)
