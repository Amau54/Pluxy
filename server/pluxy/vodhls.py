"""
HLS VOD à la demande : barre de lecture = film ENTIER + seek partout.

Au lieu d'un flux « live » à durée inconnue, on génère une playlist VOD complète
(tous les segments listés, durée totale connue) ; chaque segment n'est transcodé
QU'À LA DEMANDE quand le lecteur le réclame (clic sur la barre). Résultat : une
barre de lecture classique sur laquelle on peut aller n'importe où, même sans
pré-chargement.
"""
from __future__ import annotations

import math
import os
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Dict, Optional, Tuple

from .config import PluxyConfig
from .models import MediaInfo, PlaybackDecision, PlaybackMode
from .tools import NO_WINDOW
from .transcoder import build_transcode_cmd


class VodHls:
    def __init__(self, cfg: PluxyConfig, _base_dir: Path):
        self.base = Path(cfg.server.transcode_temp_dir).resolve()
        self.base.mkdir(parents=True, exist_ok=True)
        self.seg_time = float(max(3, cfg.network.hls_segment_duration))
        self._locks: Dict[str, threading.Lock] = {}
        self._glock = threading.Lock()
        # Limite le nombre de segments transcodés en parallèle (charge GPU maîtrisée).
        self._sem = threading.Semaphore(2)
        # Décision + media enregistrés au moment du /decide, réutilisés par segment.
        self._ctx: Dict[str, Tuple[MediaInfo, PlaybackDecision]] = {}

    # -- Contexte (media + décision) -------------------------------------- #
    def register(self, item_id: str, variant: str, media: MediaInfo,
                 decision: PlaybackDecision) -> None:
        self._ctx[f"{item_id}_{variant}"] = (media, decision)

    def _context(self, item_id: str, variant: str) -> Optional[Tuple[MediaInfo, PlaybackDecision]]:
        return self._ctx.get(f"{item_id}_{variant}")

    # -- Playlist VOD ----------------------------------------------------- #
    def playlist(self, duration: float) -> str:
        T = self.seg_time
        total = max(duration, T)
        n = max(1, math.ceil(total / T))
        out = ["#EXTM3U", "#EXT-X-VERSION:3",
               f"#EXT-X-TARGETDURATION:{int(math.ceil(T))}",
               "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:VOD"]
        for i in range(n):
            dur = T if i < n - 1 else (total - (n - 1) * T)
            out.append(f"#EXTINF:{dur:.3f},")
            out.append(f"seg_{i:05d}.ts")
        out.append("#EXT-X-ENDLIST")
        return "\n".join(out) + "\n"

    # -- Segment à la demande --------------------------------------------- #
    def _dir(self, item_id: str, variant: str) -> Path:
        return self.base / f"vod_{item_id}_{variant}"

    def segment(self, cfg: PluxyConfig, item_id: str, variant: str, index: int) -> Optional[Path]:
        ctx = self._context(item_id, variant)
        if ctx is None:
            return None
        media, decision = ctx
        d = self._dir(item_id, variant)
        d.mkdir(parents=True, exist_ok=True)
        out = d / f"seg_{index:05d}.ts"
        if out.exists() and out.stat().st_size > 0:
            _touch(d)
            return out

        lock = self._lock_for(f"{item_id}_{variant}_{index}")
        with lock:
            if out.exists() and out.stat().st_size > 0:
                return out
            start = index * self.seg_time
            tmp = d / f"seg_{index:05d}.part"
            cmd = build_transcode_cmd(
                media, decision, cfg, d,
                start_time=start, compat=(variant == "compat"),
                segment_out=tmp, seg_duration=self.seg_time,
            )
            with self._sem:
                try:
                    p = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, cwd=str(d),
                                       creationflags=NO_WINDOW, timeout=120)
                except Exception:
                    return None
            if p.returncode != 0 or not tmp.exists() or tmp.stat().st_size == 0:
                tmp.unlink(missing_ok=True)
                return None
            os.replace(tmp, out)
        _touch(d)
        return out

    def _lock_for(self, key: str) -> threading.Lock:
        with self._glock:
            return self._locks.setdefault(key, threading.Lock())

    # -- Nettoyage -------------------------------------------------------- #
    def cleanup_item(self, item_id: str) -> None:
        for variant in ("main", "compat"):
            self._ctx.pop(f"{item_id}_{variant}", None)
            shutil.rmtree(self._dir(item_id, variant), ignore_errors=True)

    def reap_idle(self, max_idle: float = 900.0) -> None:
        now = time.time()
        for d in self.base.glob("vod_*"):
            try:
                if now - d.stat().st_mtime > max_idle:
                    shutil.rmtree(d, ignore_errors=True)
            except Exception:
                pass


def _touch(p: Path) -> None:
    try:
        os.utime(p, None)
    except Exception:
        pass
