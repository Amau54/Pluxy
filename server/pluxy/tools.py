"""Utilitaires : disponibilité FFmpeg/ffprobe + drapeaux subprocess (Windows)."""
from __future__ import annotations

import os
import shutil
import subprocess
from functools import lru_cache

from .config import PluxyConfig

# Évite le flash d'une fenêtre console à chaque ffprobe/ffmpeg sous Windows.
NO_WINDOW = 0x08000000 if os.name == "nt" else 0


@lru_cache(maxsize=8)
def _which(path: str) -> bool:
    if shutil.which(path):
        return True
    # Chemin absolu ou commande directe : test rapide -version.
    try:
        subprocess.run([path, "-version"], capture_output=True, timeout=5,
                       creationflags=NO_WINDOW)
        return True
    except Exception:
        return False


def ffmpeg_available(cfg: PluxyConfig) -> bool:
    return _which(cfg.ffmpeg.ffmpeg_path)


def ffprobe_available(cfg: PluxyConfig) -> bool:
    return _which(cfg.ffmpeg.ffprobe_path)


@lru_cache(maxsize=4)
def _has_filter(ffmpeg_path: str, name: str) -> bool:
    try:
        out = subprocess.run([ffmpeg_path, "-hide_banner", "-filters"],
                             capture_output=True, text=True, timeout=10,
                             creationflags=NO_WINDOW)
        return any(line.split()[1:2] == [name]
                   for line in out.stdout.splitlines() if line.strip())
    except Exception:
        return False


def has_libplacebo(cfg: PluxyConfig) -> bool:
    """libplacebo (tone mapping HDR->SDR de très haute qualité, GPU Vulkan)."""
    return _has_filter(cfg.ffmpeg.ffmpeg_path, "libplacebo")
