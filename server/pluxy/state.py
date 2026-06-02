"""Conteneur d'état partagé entre les routes (injecté dans app.state)."""
from __future__ import annotations

import threading
from pathlib import Path

from .config import ConfigManager
from .discovery import DiscoveryResponder
from .library import Library
from .metadata import MetadataProvider
from .transcoder import TranscodeManager


class AppState:
    def __init__(self, base_dir: Path):
        self.cfgm = ConfigManager(base_dir)
        self.library = Library(self.cfgm)
        self.transcoder = TranscodeManager(self.cfgm.cfg)
        self.metadata = MetadataProvider(self.cfgm, base_dir)
        self.discovery = DiscoveryResponder(self.cfgm)
        self.discovery.start()
        try:
            self.library.scan()
        except Exception:
            pass

    def enrich_library_async(self, force: bool = False) -> None:
        """Récupère les métadonnées de toute la bibliothèque en arrière-plan."""
        def worker():
            for item in self.library.all():
                try:
                    self.metadata.get(item, force=force)
                except Exception:
                    continue

        threading.Thread(target=worker, daemon=True).start()


def get_state(request) -> AppState:
    return request.app.state.pluxy
