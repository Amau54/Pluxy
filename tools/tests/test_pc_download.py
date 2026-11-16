"""Exercise resumed downloads against a real local HTTP endpoint."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.skipif(os.name != "nt", reason="Windows bootstrap")
@pytest.mark.parametrize("scenario", ["resume", "stall", "corrupt", "no_range"])
def test_download_recovers_and_checks_integrity(tmp_path, scenario):
    body = b"pluxy-download-fixture" * 10000
    requests = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            requested = self.headers.get("Range")
            requests.append(requested)
            if scenario == "stall" and len(requests) == 1:
                time.sleep(5)
                return
            offset = int(requested.split("=")[1].split("-")[0]) if requested else 0
            if scenario == "no_range":
                offset = 0
            response = b"X" * len(body) if scenario == "corrupt" and len(requests) == 1 else body[offset:]
            self.send_response(206 if offset else 200)
            self.send_header("Content-Length", str(len(response)))
            if offset:
                self.send_header("Content-Range", f"bytes {offset}-{len(body)-1}/{len(body)}")
            self.end_headers()
            try:
                self.wfile.write(response)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    target = tmp_path / "component.bin"
    if scenario in ("resume", "no_range"):
        target.write_bytes(body[:15000])
    script = tmp_path / "download.ps1"
    script.write_text(f"""
$ErrorActionPreference='Stop'
. '{ROOT / 'tools/PC-Download.ps1'}'
Save-PCComponent -Uri 'http://127.0.0.1:{server.server_port}/file' -Destination '{target}' -Sha256 '{hashlib.sha256(body).hexdigest()}' -Label 'Test' -StallSeconds 2
""", encoding="utf-8-sig")
    try:
        shell = shutil.which("powershell.exe")
        result = subprocess.run([shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
                                capture_output=True, timeout=35)
        assert result.returncode == 0, (result.stdout, result.stderr)
        assert target.read_bytes() == body
        assert b"100 %" in result.stdout
        if scenario == "resume":
            assert requests == ["bytes=15000-"]
        else:
            assert len(requests) >= 2
    finally:
        server.shutdown()
        server.server_close()
