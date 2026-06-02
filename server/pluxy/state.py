"""Conteneur d'état partagé entre les routes (injecté dans app.state)."""
from __future__ import annotations

from pathlib import Path

from .config import ConfigManager
from .library import Library
from .transcoder import TranscodeManager


class AppState:
    def __init__(self, base_dir: Path):
        self.cfgm = ConfigManager(base_dir)
        self.library = Library(self.cfgm)
        self.transcoder = TranscodeManager(self.cfgm.cfg)
        # Indexation initiale (non bloquante au besoin ; ici synchrone au boot).
        try:
            self.library.scan()
        except Exception:
            pass


def get_state(request) -> AppState:
    return request.app.state.pluxy
