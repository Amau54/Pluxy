"""Conteneur d'état partagé entre les routes (injecté dans app.state)."""
from __future__ import annotations

import threading
import os
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .config import ConfigManager
from .discovery import DiscoveryResponder
from .library import Library
from .metadata import MetadataProvider
from .paths import data_dir
from .transcoder import TranscodeManager
from .thumbnails import VideoThumbnailer
from .seek_previews import SeekPreviews
from .vodhls import VodHls
from .watchstate import WatchStore
from .catalogue import Catalogue


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
        self.vod = VodHls(self.cfgm.cfg, data)
        self.metadata = MetadataProvider(self.cfgm, data)
        self.watch = WatchStore(data)
        self.catalogue = Catalogue(self.library, self.metadata, self.watch)
        self.thumbnails = VideoThumbnailer(self.cfgm.cfg, data)
        self.seek_previews = SeekPreviews(self.cfgm.cfg, data)
        # Dernière décision de lecture par média (capacités client réelles) — réutilisée
        # par les endpoints de flux (remux) pour cibler le bon codec audio.
        self._decision_lock = threading.RLock()
        self._decisions: OrderedDict[tuple[str, str], tuple] = OrderedDict()
        self.probe_slots = threading.BoundedSemaphore(4)
        self.remux_slots = threading.BoundedSemaphore(4)
        self.hls_request_slots = threading.BoundedSemaphore(8)
        # HLS waits must not occupy asyncio's shared executor, which also runs
        # control work (settings/decisions) and shutdown/reaping.
        self.hls_executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="pluxy-hls")
        self.active_host = os.environ.get("PLUXY_ACTIVE_HOST", self.cfgm.cfg.server.host)
        self.active_port = int(os.environ.get("PLUXY_ACTIVE_PORT", self.cfgm.cfg.server.port))
        self._job_lock = threading.RLock()
        self._closing = threading.Event()
        self._scan_thread: threading.Thread | None = None
        self._enrich_thread: threading.Thread | None = None
        self.enrich_status = {"status": "idle", "processed": 0, "errors": 0, "finished_at": None}
        self.discovery = DiscoveryResponder(self.cfgm, active_port=self.active_port)
        self.discovery.start()
        # L'index persistant est déjà chargé : la liste est dispo immédiatement.
        # On (re)scanne en arrière-plan pour ne pas bloquer le démarrage.
        self.scan_async()

    def scan_async(self) -> bool:
        """Lance un scan de la bibliothèque en arrière-plan (+ enrichissement)."""
        def worker():
            try:
                scan_ok = False
                try:
                    self.library.scan(stop_event=self._closing)
                    scan_ok = not self._closing.is_set()
                except Exception:
                    pass
                if scan_ok and self.library.scan_status.get("status") == "success":
                    self.watch.prune({item.id for item in self.library.all()})
                if self.cfgm.cfg.metadata.auto_fetch_on_scan:
                    self.enrich_library_async()
            finally:
                with self._job_lock:
                    if self._scan_thread is threading.current_thread():
                        self._scan_thread = None

        with self._job_lock:
            if self._closing.is_set():
                return False
            if self._scan_thread and self._scan_thread.is_alive():
                return False
            self._scan_thread = threading.Thread(target=worker, name="pluxy-library-scan", daemon=True)
            self._scan_thread.start()
            return True

    def enrich_library_async(self, force: bool = False) -> bool:
        def worker():
            try:
                self._enrich(force)
            except Exception:
                self.enrich_status = {**self.enrich_status, "status": "error",
                                      "errors": self.enrich_status["errors"] + 1}
            finally:
                status = self.enrich_status["status"]
                if status != "error":
                    status = "cancelled" if self._closing.is_set() else (
                        "warning" if self.enrich_status["errors"] else "success")
                self.enrich_status = {**self.enrich_status, "status": status, "finished_at": time.time()}
                with self._job_lock:
                    if self._enrich_thread is threading.current_thread():
                        self._enrich_thread = None

        with self._job_lock:
            if self._closing.is_set():
                return False
            if self._enrich_thread and self._enrich_thread.is_alive():
                return False
            self._enrich_thread = threading.Thread(
                target=worker,
                name="pluxy-metadata-enrich",
                daemon=True,
            )
            self._enrich_thread.start()
            return True

    def remember_decision(self, client: str, item_id: str, value: tuple) -> None:
        key = (client, item_id)
        with self._decision_lock:
            self._decisions[key] = value
            self._decisions.move_to_end(key)
            while len(self._decisions) > 256:
                self._decisions.popitem(last=False)

    def decision_for(self, client: str, item_id: str):
        key = (client, item_id)
        with self._decision_lock:
            value = self._decisions.get(key)
            if value is not None:
                self._decisions.move_to_end(key)
            return value

    def _enrich(self, force: bool = False) -> None:
        self.enrich_status = {"status": "running", "processed": 0, "matched": 0, "unmatched": 0, "errors": 0, "finished_at": None}
        if force:                       # repart d'un cache série propre (1 appel TV/série)
            self.metadata.clear_tv_cache()
        for item in self.library.all():
            if self._closing.is_set():
                break
            if item.kind not in {"movie", "series"}:
                continue
            try:
                meta = self.metadata.get(item, force=force, share_series=True)
                outcome = "errors" if meta.lookup_error else "matched" if meta.matched else "unmatched"
                self.enrich_status = {**self.enrich_status, outcome: self.enrich_status[outcome] + 1}
            except Exception:
                self.enrich_status = {**self.enrich_status, "errors": self.enrich_status["errors"] + 1}
            self.enrich_status = {**self.enrich_status, "processed": self.enrich_status["processed"] + 1}

    def close(self) -> None:
        """Libère les ressources externes même lors d'un arrêt avec lecteurs actifs."""
        with self._job_lock:
            self._closing.set()
        self.seek_previews.close()
        try:
            self.discovery.stop()
        finally:
            try:
                # Stop production before joining waiters: their segment waits
                # can finish without work queued behind themselves.
                self.vod.stop_all()
            finally:
                try:
                    self.transcoder.stop_all()
                finally:
                    self.hls_executor.shutdown(wait=True, cancel_futures=True)


def get_state(request) -> AppState:
    return request.app.state.pluxy
