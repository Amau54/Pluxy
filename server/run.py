"""Lanceur Pluxy. `python run.py`"""
from __future__ import annotations

import json
from pathlib import Path

import uvicorn

from pluxy.netinfo import primary_lan_ip

BASE = Path(__file__).resolve().parent


def _read_host_port() -> tuple[str, int]:
    src = BASE / "config.json"
    if not src.exists():
        src = BASE / "config.default.json"
    data = json.loads(src.read_text(encoding="utf-8"))
    srv = data.get("server", {})
    return srv.get("host", "0.0.0.0"), int(srv.get("port", 8420))


if __name__ == "__main__":
    host, port = _read_host_port()
    lan = primary_lan_ip()
    print("  ----------------------------------------------")
    print("   Pluxy serveur demarre")
    print(f"   UI config : http://{lan}:{port}/")
    print(f"   API/docs  : http://{lan}:{port}/docs")
    print(f"   Le client Android TV detecte ce serveur")
    print(f"   automatiquement (IP {lan}).")
    print("  ----------------------------------------------")
    uvicorn.run("pluxy.main:app", host=host, port=port, reload=False)
