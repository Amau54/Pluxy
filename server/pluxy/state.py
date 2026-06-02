"""Conteneur d'état partagé entre les routes (injecté dans app.state)."""
from __future__ import annotations

import threading
from pathlib import Path

from .config import ConfigManager
from .discovery import DiscoveryResponder
from .library import Library
from .metadata import MetadataProvider
from .paths import data_dir
from .transcoder import TranscodeManager
from .watchstate import WatchStore


class AppState:
    def __init__(self, base_dir: Path):
        # Données utilisateur dans un emplacement STABLE (hors dossier projet) :
        # config, index bibliothèque, métadonnées et reprise survivent aux MAJ.
        data = data_dir()
        self.cfgm = ConfigManager(
            data_dir=data,
            default_path=base_dir / "config.default.json",
            legacy_dir=base_dir,                       # migre l'ancien server/config.json
        )
        self.library = Library(self.cfgm, data, legacy_dir=base_dir)
        self.transcoder = TranscodeManager(self.cfgm.cfg)
        self.metadata = MetadataProvider(self.cfgm, data)
        self.watch = WatchStore(data)
        self.discovery = DiscoveryResponder(self.cfgm)
        self.discovery.start()
        # L'index persistant est déjà chargé : la liste est dispo immédiatement.
        # On (re)scanne en arrière-plan pour ne pas bloquer le démarrage.
        self.scan_async()

    def scan_async(self) -> None:
        """Lance un scan de la bibliothèque en arrière-plan (+ enrichissement)."""
        def worker():
            try:
                self.library.scan()
            except Exception:
                pass
            if self.cfgm.cfg.metadata.auto_fetch_on_scan:
                self._enrich()

        threading.Thread(target=worker, daemon=True).start()

    def enrich_library_async(self, force: bool = False) -> None:
        threading.Thread(target=lambda: self._enrich(force), daemon=True).start()

    def _enrich(self, force: bool = False) -> None:
        for item in self.library.all():
            try:
                self.metadata.get(item, force=force)
            except Exception:
                continue


def get_state(request) -> AppState:
    return request.app.state.pluxy
