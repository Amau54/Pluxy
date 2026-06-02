"""Suivi de progression de lecture (reprise façon Plex), persisté sur disque."""
from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Dict


class WatchStore:
    def __init__(self, base_dir: Path):
        self.path = base_dir / ".pluxy_watch.json"
        self._lock = threading.RLock()
        self._data: Dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            try:
                self._data = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                self._data = {}

    def _save(self) -> None:
        try:
            self.path.write_text(
                json.dumps(self._data, ensure_ascii=False), encoding="utf-8"
            )
        except Exception:
            pass

    def get(self, item_id: str) -> dict:
        with self._lock:
            return self._data.get(item_id, {"position_ms": 0, "duration_ms": 0})

    def set(self, item_id: str, position_ms: int, duration_ms: int) -> None:
        with self._lock:
            # Au-delà de ~95 %, on considère le film "vu" -> reprise à 0.
            watched = duration_ms > 0 and position_ms / duration_ms > 0.95
            self._data[item_id] = {
                "position_ms": 0 if watched else max(0, position_ms),
                "duration_ms": max(0, duration_ms),
                "watched": watched,
            }
            self._save()
