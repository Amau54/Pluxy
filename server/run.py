"""Lanceur Pluxy. `python run.py`"""
from __future__ import annotations

from pathlib import Path
import os

import uvicorn

from pluxy.netinfo import primary_lan_ip

BASE = Path(__file__).resolve().parent


def _read_host_port() -> tuple[str, int]:
    from pluxy.config import ConfigManager
    from pluxy.paths import data_dir
    # Use the same validation and corrupt-file recovery as the application.
    cfg = ConfigManager(data_dir(), BASE / "config.default.json", legacy_dir=BASE).cfg
    return cfg.server.host, cfg.server.port


if __name__ == "__main__":
    host, port = _read_host_port()
    os.environ["PLUXY_ACTIVE_HOST"] = host
    os.environ["PLUXY_ACTIVE_PORT"] = str(port)
    lan = primary_lan_ip()
    print("  ----------------------------------------------")
    print("   Pluxy serveur demarre")
    print(f"   UI config : http://localhost:{port}/ (PC uniquement)")
    print(f"   API/docs  : http://localhost:{port}/docs")
    print(f"   Streaming : http://{lan}:{port}/ (LAN prive)")
    print("   Le client Android TV detecte ce serveur")
    print(f"   automatiquement (IP {lan}).")
    print("  ----------------------------------------------")
    # pythonw.exe has no console streams for Uvicorn's default logging setup.
    uvicorn.run("pluxy.main:app", host=host, port=port, reload=False, log_config=None)
