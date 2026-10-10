"""Aperçus de navigation : une extraction à la fois, hors des requêtes HTTP."""

from __future__ import annotations

from collections import OrderedDict
import hashlib
import math
from pathlib import Path
import os
import subprocess
import threading
import time

from .tools import NO_WINDOW

STEP_SECONDS = 10
MAX_IMAGE_BYTES = 256 * 1024
MAX_CACHE_BYTES = 32 * 1024 * 1024


def preview_second(position: float, duration: float) -> int:
    if not math.isfinite(position) or position < 0:
        raise ValueError("Position invalide")
    if math.isfinite(duration) and duration > 0:
        position = min(position, max(0, duration - 0.1))
    return int(position // STEP_SECONDS) * STEP_SECONDS


class SeekPreviews:
    def __init__(self, cfg, data_dir: Path):
        self.cfg = cfg
        self.cache = data_dir / "seek-previews"
        self.cache.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._pending = OrderedDict()
        self._failed = OrderedDict()
        self._active = None
        self._worker = None
        self._closed = False

    def request(self, item, position: float) -> tuple[Path | None, bool]:
        """Retourne immédiatement (image, en préparation), sans lancer FFmpeg sur HTTP."""
        if item.kind not in {"movie", "series", "clip"}:
            return None, False
        second = preview_second(position, item.duration or 0)
        try:
            source = Path(item.path)
            info = source.stat()
        except OSError:
            return None, False
        key = hashlib.sha256(
            f"{item.id}:{info.st_mtime_ns}:{info.st_size}:{second}".encode()
        ).hexdigest()
        target = self.cache / f"{key}.jpg"
        try:
            if target.is_file() and 0 < target.stat().st_size <= MAX_IMAGE_BYTES:
                return target, False
        except OSError:
            pass
        with self._lock:
            if self._closed or self._failed.get(key, 0) > time.monotonic():
                return None, False
            if self._active != key:
                # Une nouvelle destination remplace les anciennes demandes du même film.
                self._pending[item.id] = (key, source, target, second)
                self._pending.move_to_end(item.id)
                while len(self._pending) > 4:
                    self._pending.popitem(last=False)
            if self._worker is None:
                self._worker = threading.Thread(
                    target=self._run, name="pluxy-seek-preview", daemon=True
                )
                self._worker.start()
        return None, True

    def _run(self):
        while True:
            with self._lock:
                if self._closed or not self._pending:
                    self._worker = None
                    self._active = None
                    return
                _, (key, source, target, second) = self._pending.popitem(last=False)
                self._active = key
            try:
                ok = self._capture(source, target, second)
            except Exception:
                # Une erreur d'extraction ne doit pas immobiliser toute la file.
                ok = False
            with self._lock:
                self._active = None
                if not ok:
                    self._failed[key] = time.monotonic() + 60
                    while len(self._failed) > 256:
                        self._failed.popitem(last=False)
            if ok:
                self._prune()

    def _capture(self, source, target, second):
        temporary = target.with_suffix(".tmp.jpg")
        command = [
            self.cfg.ffmpeg.ffmpeg_path,
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-threads",
            "1",
            "-ss",
            str(second),
            "-i",
            str(source),
            "-map",
            "0:v:0",
            "-frames:v",
            "1",
            "-an",
            "-sn",
            "-vf",
            "scale=320:180:force_original_aspect_ratio=decrease",
            "-threads",
            "1",
            "-q:v",
            "6",
            "-y",
            str(temporary),
        ]
        try:
            priority = subprocess.IDLE_PRIORITY_CLASS if os.name == "nt" else 0
            result = subprocess.run(
                command,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=6,
                creationflags=NO_WINDOW | priority,
            )
            if (
                result.returncode
                or not temporary.is_file()
                or not 0 < temporary.stat().st_size <= MAX_IMAGE_BYTES
            ):
                return False
            os.replace(temporary, target)
            return True
        except (OSError, subprocess.TimeoutExpired):
            return False
        finally:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass

    def _prune(self):
        try:
            files = sorted(
                self.cache.glob("*.jpg"), key=lambda p: p.stat().st_mtime, reverse=True
            )
            total = 0
            for path in files:
                total += path.stat().st_size
                if total > MAX_CACHE_BYTES:
                    path.unlink(missing_ok=True)
        except OSError:
            pass

    def close(self):
        with self._lock:
            self._closed = True
            self._pending.clear()
