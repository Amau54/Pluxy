"""Miniatures vidéo locales, paresseuses et bornées."""
from __future__ import annotations

import os
import subprocess
import threading
import tempfile
from weakref import WeakValueDictionary
from pathlib import Path

from .config import PluxyConfig
from .models import MediaItem
from .tools import NO_WINDOW

MAX_THUMBNAIL_BYTES = 1_500_000
MAX_CACHE_BYTES = 256 * 1024 * 1024


def capture_second(duration: float) -> float:
    """Évite générique/logo noir : milieu pour un clip, 10 % borné pour un film."""
    if duration <= 0:
        return 10.0
    if duration < 20:
        return max(0.0, duration * 0.5)
    return min(120.0, max(5.0, duration * 0.1))


class VideoThumbnailer:
    def __init__(self, cfg: PluxyConfig, data_dir: Path):
        self.cfg = cfg
        self.cache_dir = data_dir / "thumbnails"
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._slots = threading.BoundedSemaphore(2)
        self._lock = threading.Lock()
        self._key_locks = WeakValueDictionary()

    def jpeg(self, item: MediaItem) -> Path | None:
        if item.kind not in {"movie", "series"}:
            return None
        try:
            source = Path(item.path)
            stamp = source.stat().st_mtime_ns
        except OSError:
            return None
        target = self.cache_dir / f"{item.id}-{stamp:x}-{item.size:x}.jpg"
        if target.is_file() and 0 < target.stat().st_size <= MAX_THUMBNAIL_BYTES:
            return target
        with self._lock:
            lock = self._key_locks.setdefault(str(target), threading.Lock())
        if not lock.acquire(blocking=False):
            raise ThumbnailBusy
        try:
            return self._generate(source, target, item)
        finally:
            lock.release()

    def _generate(self, source: Path, target: Path, item: MediaItem) -> Path | None:
        if not self._slots.acquire(blocking=False):
            raise ThumbnailBusy
        try:
            if target.is_file() and target.stat().st_size > 0:
                return target
            with tempfile.NamedTemporaryFile(dir=self.cache_dir, suffix=".part", delete=False) as handle:
                tmp = Path(handle.name)
            cmd = [
                self.cfg.ffmpeg.ffmpeg_path,
                "-hide_banner", "-loglevel", "error", "-nostdin",
                "-ss", f"{capture_second(item.duration):.3f}",
                "-i", str(source), "-map", "0:v:0", "-frames:v", "1",
                "-vf", "scale=512:-2:force_original_aspect_ratio=decrease",
                "-c:v", "mjpeg", "-q:v", "4", "-f", "image2", "-y", str(tmp),
            ]
            try:
                result = subprocess.run(
                    cmd,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=25,
                    creationflags=NO_WINDOW,
                )
            except (OSError, subprocess.TimeoutExpired):
                tmp.unlink(missing_ok=True)
                return None
            if result.returncode != 0 or not tmp.is_file():
                tmp.unlink(missing_ok=True)
                return None
            size = tmp.stat().st_size
            if size <= 0 or size > MAX_THUMBNAIL_BYTES:
                tmp.unlink(missing_ok=True)
                return None
            os.replace(tmp, target)
            self._prune()
            return target
        finally:
            if "tmp" in locals():
                tmp.unlink(missing_ok=True)
            self._slots.release()

    def _prune(self) -> None:
        try:
            files = sorted(
                (path for path in self.cache_dir.glob("*.jpg") if path.is_file()),
                key=lambda path: path.stat().st_mtime_ns,
                reverse=True,
            )
        except OSError:
            return
        total = 0
        for path in files:
            try:
                total += path.stat().st_size
                if total > MAX_CACHE_BYTES:
                    path.unlink(missing_ok=True)
            except OSError:
                continue


class ThumbnailBusy(RuntimeError):
    pass
