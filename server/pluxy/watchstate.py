"""Suivi de progression de lecture (reprise façon Plex), persisté sur disque."""
from __future__ import annotations

import json
import math
import re
import shutil
import threading
import time
from pathlib import Path
from typing import Dict

MAX_WATCH_ENTRIES = 10_000
MAX_PROGRESS_SESSIONS = 128
SESSION_TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


class WatchStore:
    def __init__(self, base_dir: Path):
        self.path = base_dir / ".pluxy_watch.json"
        self._lock = threading.RLock()
        self._data: Dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        from .config import _read_config_text
        try:
            loaded = json.loads(_read_config_text(self.path))
        except FileNotFoundError:
            return
        except (ValueError, TypeError):
            shutil.copy2(self.path, self.path.with_name(f"{self.path.name}.invalid-{time.time_ns()}"))
            return
        if not isinstance(loaded, dict):
            shutil.copy2(self.path, self.path.with_name(f"{self.path.name}.invalid-{time.time_ns()}"))
            return
        if isinstance(loaded, dict):
            try:
                clean: Dict[str, dict] = {}
                for index, (item_id, value) in enumerate(loaded.items()):
                    if index >= MAX_WATCH_ENTRIES:
                        break
                    if not isinstance(item_id, str) or len(item_id) > 64 or not isinstance(value, dict):
                        continue
                    try:
                        clean[item_id] = {
                            "position_ms": max(0, int(value.get("position_ms", 0) or 0)),
                            "duration_ms": max(0, int(value.get("duration_ms", 0) or 0)),
                            "watched": bool(value.get("watched", False)),
                            "last_played_at": self._timestamp(value.get("last_played_at", 0)),
                        }
                        sessions = value.get("_sessions", {})
                        if isinstance(sessions, dict):
                            clean[item_id]["_sessions"] = {
                                key: sequence for key, sequence in list(sessions.items())[-MAX_PROGRESS_SESSIONS:]
                                if isinstance(key, str) and SESSION_TOKEN_RE.fullmatch(key)
                                and isinstance(sequence, int) and 0 <= sequence <= 2**63 - 1
                            }
                        active = value.get("_active_session")
                        if active in clean[item_id].get("_sessions", {}):
                            clean[item_id]["_active_session"] = active
                    except (TypeError, ValueError, OverflowError):
                        continue
                self._data = clean
                if len(clean) != len(loaded):
                    self._save()
            except (ValueError, TypeError):
                raise ValueError("Historique de lecture invalide")

    @staticmethod
    def _timestamp(value) -> float:
        timestamp = float(value or 0)
        return max(0.0, timestamp) if math.isfinite(timestamp) else 0.0

    def _save(self, data: dict | None = None) -> None:
        from .paths import atomic_write_text
        atomic_write_text(self.path, json.dumps(self._data if data is None else data, ensure_ascii=False, allow_nan=False))

    def get(self, item_id: str) -> dict:
        with self._lock:
            value = self._data.get(
                item_id,
                {
                    "position_ms": 0,
                    "duration_ms": 0,
                    "watched": False,
                    "last_played_at": 0.0,
                },
            )
            return {key: value[key] for key in ("position_ms", "duration_ms", "watched", "last_played_at")}

    def set(self, item_id: str, position_ms: int, duration_ms: int,
            session_id: str | None = None, sequence: int | None = None) -> bool:
        with self._lock:
            if (session_id is None) != (sequence is None):
                raise ValueError("Session et sequence doivent etre fournies ensemble")
            if session_id is not None and (
                not SESSION_TOKEN_RE.fullmatch(session_id) or not isinstance(sequence, int)
                or sequence < 0 or sequence > 2**63 - 1
            ):
                raise ValueError("Session ou sequence invalide")
            sessions = dict(self._data.get(item_id, {}).get("_sessions", {}))
            active = self._data.get(item_id, {}).get("_active_session")
            if session_id is not None:
                if (session_id in sessions and active != session_id) or sequence <= sessions.get(session_id, -1):
                    return False
                sessions.pop(session_id, None)
                sessions[session_id] = sequence
                while len(sessions) > MAX_PROGRESS_SESSIONS:
                    sessions.pop(next(iter(sessions)))
                active = session_id
            # Au-delà de ~95 %, on considère le film "vu" -> reprise à 0.
            watched = duration_ms > 0 and position_ms / duration_ms > 0.95
            candidate = dict(self._data)
            candidate[item_id] = {
                "position_ms": 0 if watched else max(0, position_ms),
                "duration_ms": max(0, duration_ms),
                "watched": watched,
                "last_played_at": time.time(),
                "_sessions": sessions,
                "_active_session": active,
            }
            self._save(candidate)
            self._data = candidate
            return True

    def prune(self, valid_ids: set[str]) -> None:
        with self._lock:
            stale = set(self._data) - valid_ids
            if not stale:
                return
            candidate = {key: value for key, value in self._data.items() if key in valid_ids}
            self._save(candidate)
            self._data = candidate
