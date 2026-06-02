"""Utilitaires : détection de la disponibilité de FFmpeg/ffprobe (mise en cache)."""
from __future__ import annotations

import shutil
import subprocess
from functools import lru_cache

from .config import PluxyConfig


@lru_cache(maxsize=8)
def _which(path: str) -> bool:
    if shutil.which(path):
        return True
    # Chemin absolu ou commande directe : test rapide -version.
    try:
        subprocess.run([path, "-version"], capture_output=True, timeout=5)
        return True
    except Exception:
        return False


def ffmpeg_available(cfg: PluxyConfig) -> bool:
    return _which(cfg.ffmpeg.ffmpeg_path)


def ffprobe_available(cfg: PluxyConfig) -> bool:
    return _which(cfg.ffmpeg.ffprobe_path)
